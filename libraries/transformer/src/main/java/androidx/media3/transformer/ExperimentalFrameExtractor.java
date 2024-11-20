/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.transformer;

import static androidx.media3.common.PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Util.usToMs;
import static androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.Util;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import androidx.media3.effect.MatrixTransformation;
import androidx.media3.effect.PassthroughShaderProgram;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.initialization.qual.Initialized;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Extracts decoded frames from {@link MediaItem}.
 *
 * <p>This class is experimental and will be renamed or removed in a future release.
 *
 * <p>Frame extractor instances must be accessed from a single application thread.
 */
/* package */ final class ExperimentalFrameExtractor implements AnalyticsListener {

  /** Configuration for the frame extractor. */
  // TODO: b/350498258 - Add configuration for decoder selection.
  public static final class Configuration {

    /** A builder for {@link Configuration} instances. */
    public static final class Builder {
      private SeekParameters seekParameters;

      /** Creates a new instance with default values. */
      public Builder() {
        seekParameters = SeekParameters.DEFAULT;
      }

      /**
       * Sets the parameters that control how seek operations are performed. Defaults to {@link
       * SeekParameters#DEFAULT}.
       *
       * @param seekParameters The {@link SeekParameters}.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setSeekParameters(SeekParameters seekParameters) {
        this.seekParameters = seekParameters;
        return this;
      }

      /** Builds a new {@link Configuration} instance. */
      public Configuration build() {
        return new Configuration(seekParameters);
      }
    }

    /** The {@link SeekParameters}. */
    public final SeekParameters seekParameters;

    private Configuration(SeekParameters seekParameters) {
      this.seekParameters = seekParameters;
    }
  }

  /** Stores an extracted and decoded video frame. */
  public static final class Frame {

    /** The presentation timestamp of the extracted frame, in milliseconds. */
    public final long presentationTimeMs;

    /** The extracted frame contents. */
    public final Bitmap bitmap;

    private Frame(long presentationTimeMs, Bitmap bitmap) {
      this.presentationTimeMs = presentationTimeMs;
      this.bitmap = bitmap;
    }
  }

  private final ExoPlayer player;
  private final Handler playerApplicationThreadHandler;

  /**
   * A {@link SettableFuture} representing the frame currently being extracted. Accessed on both the
   * {@linkplain ExoPlayer#getApplicationLooper() ExoPlayer application thread}, and the video
   * effects GL thread.
   */
  private final AtomicReference<@NullableType SettableFuture<Frame>>
      frameBeingExtractedFutureAtomicReference;

  /**
   * The last {@link SettableFuture} returned by {@link #getFrame(long)}. Accessed on the frame
   * extractor application thread.
   */
  private SettableFuture<Frame> lastRequestedFrameFuture;

  /**
   * The last {@link Frame} that was extracted successfully. Accessed on the {@linkplain
   * ExoPlayer#getApplicationLooper() ExoPlayer application thread}.
   */
  private @MonotonicNonNull Frame lastExtractedFrame;

  /**
   * Creates an instance.
   *
   * @param context {@link Context}.
   * @param configuration The {@link Configuration} for this frame extractor.
   * @param mediaItem The {@link MediaItem} from which frames are extracted.
   * @param effects The {@link List} of {@linkplain Effect video effects} to apply to the extracted
   *     video frames.
   */
  // TODO: b/350498258 - Support changing the MediaItem.
  public ExperimentalFrameExtractor(
      Context context, Configuration configuration, MediaItem mediaItem, List<Effect> effects) {
    player =
        new ExoPlayer.Builder(
                context,
                /* renderersFactory= */ (eventHandler,
                    videoRendererEventListener,
                    audioRendererEventListener,
                    textRendererOutput,
                    metadataRendererOutput) ->
                    new Renderer[] {
                      new FrameExtractorRenderer(context, videoRendererEventListener)
                    })
            .setSeekParameters(configuration.seekParameters)
            .build();
    playerApplicationThreadHandler = new Handler(player.getApplicationLooper());
    lastRequestedFrameFuture = SettableFuture.create();
    // TODO: b/350498258 - Extracting the first frame is a workaround for ExoPlayer.setVideoEffects
    //   returning incorrect timestamps if we seek the player before rendering starts from zero.
    frameBeingExtractedFutureAtomicReference = new AtomicReference<>(lastRequestedFrameFuture);
    // TODO: b/350498258 - Refactor this and remove declaring this reference as initialized
    //  to satisfy the nullness checker.
    @SuppressWarnings("nullness:assignment")
    @Initialized
    ExperimentalFrameExtractor thisRef = this;
    playerApplicationThreadHandler.post(
        () -> {
          player.addAnalyticsListener(thisRef);
          player.setVideoEffects(buildVideoEffects(effects));
          player.setMediaItem(mediaItem);
          player.setPlayWhenReady(false);
          player.prepare();
        });
  }

  /**
   * Extracts a representative {@link Frame} for the specified video position.
   *
   * @param positionMs The time position in the {@link MediaItem} for which a frame is extracted.
   * @return A {@link ListenableFuture} of the result.
   */
  public ListenableFuture<Frame> getFrame(long positionMs) {
    SettableFuture<Frame> frameSettableFuture = SettableFuture.create();
    // Process frameSettableFuture after lastRequestedFrameFuture completes.
    // If lastRequestedFrameFuture is done, the callbacks are invoked immediately.
    Futures.addCallback(
        lastRequestedFrameFuture,
        new FutureCallback<Frame>() {
          @Override
          public void onSuccess(Frame result) {
            playerApplicationThreadHandler.post(
                () -> {
                  lastExtractedFrame = result;
                  @Nullable PlaybackException playerError;
                  if (player.isReleased()) {
                    playerError =
                        new PlaybackException(
                            "The player is already released",
                            null,
                            ERROR_CODE_FAILED_RUNTIME_CHECK);
                  } else {
                    playerError = player.getPlayerError();
                  }
                  if (playerError != null) {
                    frameSettableFuture.setException(playerError);
                  } else {
                    checkState(
                        frameBeingExtractedFutureAtomicReference.compareAndSet(
                            null, frameSettableFuture));
                    player.seekTo(positionMs);
                  }
                });
          }

          @Override
          public void onFailure(Throwable t) {
            frameSettableFuture.setException(t);
          }
        },
        directExecutor());
    lastRequestedFrameFuture = frameSettableFuture;
    return lastRequestedFrameFuture;
  }

  /**
   * Releases the underlying resources. This method must be called when the frame extractor is no
   * longer required. The frame extractor must not be used after calling this method.
   */
  public void release() {
    if (player.getApplicationLooper() == Looper.myLooper()) {
      player.release();
      return;
    }
    ConditionVariable waitForRelease = new ConditionVariable();
    playerApplicationThreadHandler.removeCallbacksAndMessages(null);
    playerApplicationThreadHandler.post(
        () -> {
          player.release();
          waitForRelease.open();
        });
    waitForRelease.blockUninterruptible();
  }

  // AnalyticsListener

  @Override
  public void onPlayerError(EventTime eventTime, PlaybackException error) {
    // Fail the next frame to be extracted. Errors will propagate to later pending requests via
    // Future callbacks.
    @Nullable
    SettableFuture<Frame> frameBeingExtractedFuture =
        frameBeingExtractedFutureAtomicReference.getAndSet(null);
    if (frameBeingExtractedFuture != null) {
      frameBeingExtractedFuture.setException(error);
    }
  }

  @Override
  public void onPositionDiscontinuity(
      EventTime eventTime,
      Player.PositionInfo oldPosition,
      Player.PositionInfo newPosition,
      @Player.DiscontinuityReason int reason) {
    if (oldPosition.equals(newPosition) && reason == DISCONTINUITY_REASON_SEEK) {
      // When the new seeking position resolves to the old position, no frames are rendered.
      // Repeat the previously returned frame.
      SettableFuture<Frame> frameBeingExtractedFuture =
          checkNotNull(frameBeingExtractedFutureAtomicReference.getAndSet(null));
      frameBeingExtractedFuture.set(checkNotNull(lastExtractedFrame));
    }
  }

  @VisibleForTesting
  /* package */ ListenableFuture<@NullableType DecoderCounters> getDecoderCounters() {
    SettableFuture<@NullableType DecoderCounters> decoderCountersSettableFuture =
        SettableFuture.create();
    playerApplicationThreadHandler.post(
        () -> decoderCountersSettableFuture.set(player.getVideoDecoderCounters()));
    return decoderCountersSettableFuture;
  }

  private ImmutableList<Effect> buildVideoEffects(List<Effect> effects) {
    ImmutableList.Builder<Effect> listBuilder = new ImmutableList.Builder<>();
    listBuilder.addAll(effects);
    listBuilder.add(
        (MatrixTransformation)
            presentationTimeUs -> {
              Matrix mirrorY = new Matrix();
              mirrorY.setScale(/* sx= */ 1, /* sy= */ -1);
              return mirrorY;
            });
    listBuilder.add(new FrameReader());
    return listBuilder.build();
  }

  private final class FrameReader implements GlEffect {
    @Override
    public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) {
      // TODO: b/350498258 - Support HDR.
      return new FrameReadingGlShaderProgram();
    }
  }

  private final class FrameReadingGlShaderProgram extends PassthroughShaderProgram {
    private static final int BYTES_PER_PIXEL = 4;

    private ByteBuffer byteBuffer = ByteBuffer.allocateDirect(0);

    @Override
    public void queueInputFrame(
        GlObjectsProvider glObjectsProvider, GlTextureInfo inputTexture, long presentationTimeUs) {
      int pixelBufferSize = inputTexture.width * inputTexture.height * BYTES_PER_PIXEL;
      if (byteBuffer.capacity() != pixelBufferSize) {
        byteBuffer = ByteBuffer.allocateDirect(pixelBufferSize);
      }
      byteBuffer.clear();
      try {
        GlUtil.focusFramebufferUsingCurrentContext(
            inputTexture.fboId, inputTexture.width, inputTexture.height);
        GlUtil.checkGlError();
        GLES20.glReadPixels(
            /* x= */ 0,
            /* y= */ 0,
            inputTexture.width,
            inputTexture.height,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            byteBuffer);
        GlUtil.checkGlError();
      } catch (GlUtil.GlException e) {
        onError(e);
        return;
      }
      // According to https://www.khronos.org/opengl/wiki/Pixel_Transfer#Endian_issues,
      // the colors will have the order RGBA in client memory. This is what the bitmap expects:
      // https://developer.android.com/reference/android/graphics/Bitmap.Config.
      Bitmap bitmap =
          Bitmap.createBitmap(inputTexture.width, inputTexture.height, Bitmap.Config.ARGB_8888);
      bitmap.copyPixelsFromBuffer(byteBuffer);

      SettableFuture<Frame> frameBeingExtractedFuture =
          checkNotNull(frameBeingExtractedFutureAtomicReference.getAndSet(null));
      frameBeingExtractedFuture.set(new Frame(usToMs(presentationTimeUs), bitmap));
      // Drop frame: do not call outputListener.onOutputFrameAvailable().
      // Block effects pipeline: do not call inputListener.onReadyToAcceptInputFrame().
      // The effects pipeline will unblock and receive new frames when flushed after a seek.
      getInputListener().onInputFrameProcessed(inputTexture);
    }
  }

  /** A custom MediaCodecVideoRenderer that renders only one frame per position reset. */
  private static final class FrameExtractorRenderer extends MediaCodecVideoRenderer {

    private boolean frameRenderedSinceLastReset;
    private List<Effect> effectsFromPlayer;
    private @MonotonicNonNull Effect rotation;

    public FrameExtractorRenderer(
        Context context, VideoRendererEventListener videoRendererEventListener) {
      super(
          context,
          /* mediaCodecSelector= */ DEFAULT,
          /* allowedJoiningTimeMs= */ 0,
          Util.createHandlerForCurrentOrMainLooper(),
          videoRendererEventListener,
          /* maxDroppedFramesToNotify= */ 0);
      effectsFromPlayer = ImmutableList.of();
    }

    @Override
    public void setVideoEffects(List<Effect> effects) {
      effectsFromPlayer = effects;
      setEffectsWithRotation();
    }

    @Override
    @Nullable
    protected DecoderReuseEvaluation onInputFormatChanged(FormatHolder formatHolder)
        throws ExoPlaybackException {
      if (formatHolder.format != null) {
        Format format = formatHolder.format;
        if (format.rotationDegrees != 0) {
          // Some decoders do not apply rotation. It's no extra cost to rotate with a GL matrix
          // transformation effect instead.
          // https://developer.android.com/reference/android/media/MediaCodec#transformations-when-rendering-onto-surface
          rotation =
              new ScaleAndRotateTransformation.Builder()
                  .setRotationDegrees(360 - format.rotationDegrees)
                  .build();
          setEffectsWithRotation();
          formatHolder.format = format.buildUpon().setRotationDegrees(0).build();
        }
      }
      return super.onInputFormatChanged(formatHolder);
    }

    private void setEffectsWithRotation() {
      ImmutableList.Builder<Effect> effectBuilder = new ImmutableList.Builder<>();
      if (rotation != null) {
        effectBuilder.add(rotation);
      }
      effectBuilder.addAll(effectsFromPlayer);
      super.setVideoEffects(effectBuilder.build());
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
      if (!frameRenderedSinceLastReset) {
        super.render(positionUs, elapsedRealtimeUs);
      }
    }

    @Override
    protected boolean processOutputBuffer(
        long positionUs,
        long elapsedRealtimeUs,
        @Nullable MediaCodecAdapter codec,
        @Nullable ByteBuffer buffer,
        int bufferIndex,
        int bufferFlags,
        int sampleCount,
        long bufferPresentationTimeUs,
        boolean isDecodeOnlyBuffer,
        boolean isLastBuffer,
        Format format)
        throws ExoPlaybackException {
      if (frameRenderedSinceLastReset) {
        return false;
      }
      return super.processOutputBuffer(
          positionUs,
          elapsedRealtimeUs,
          codec,
          buffer,
          bufferIndex,
          bufferFlags,
          sampleCount,
          bufferPresentationTimeUs,
          isDecodeOnlyBuffer,
          isLastBuffer,
          format);
    }

    @Override
    protected void renderOutputBufferV21(
        MediaCodecAdapter codec, int index, long presentationTimeUs, long releaseTimeNs) {
      if (frameRenderedSinceLastReset) {
        // Do not skip this buffer to prevent the decoder from making more progress.
        return;
      }
      frameRenderedSinceLastReset = true;
      super.renderOutputBufferV21(codec, index, presentationTimeUs, releaseTimeNs);
    }

    @Override
    protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
      frameRenderedSinceLastReset = false;
      super.onPositionReset(positionUs, joining);
    }
  }
}
