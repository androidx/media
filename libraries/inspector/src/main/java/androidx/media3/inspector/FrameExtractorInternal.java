/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.inspector;

import static android.graphics.Bitmap.Config.ARGB_8888;
import static android.graphics.Bitmap.Config.RGBA_1010102;
import static android.graphics.Bitmap.Config.RGBA_F16;
import static android.graphics.ColorSpace.Named.BT2020_HLG;
import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.C.COLOR_TRANSFER_HLG;
import static androidx.media3.common.ColorInfo.SDR_BT709_LIMITED;
import static androidx.media3.common.ColorInfo.isTransferHdr;
import static androidx.media3.common.PlaybackException.ERROR_CODE_INVALID_STATE;
import static androidx.media3.common.util.GlUtil.createRgb10A2Texture;
import static androidx.media3.common.util.GlUtil.createTexture;
import static androidx.media3.common.util.Util.usToMs;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.CallSuper;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.Util;
import androidx.media3.effect.DefaultVideoFrameProcessor;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import androidx.media3.effect.MatrixTransformation;
import androidx.media3.effect.PassthroughShaderProgram;
import androidx.media3.effect.R;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.effect.SingleInputVideoGraph;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.metadata.ThumbnailMetadata;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ExecutionSequencer;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Manages the internal logic for extracting frames from a {@link MediaItem}.
 *
 * <p>This class contains the core implementation for frame extraction, including the management of
 * a shared {@link ExoPlayer} instance and a task queue. It is responsible for serializing
 * extraction requests and managing the player lifecycle to ensure thread-safety and efficient
 * resource use.
 */
// TODO(b/442827020): Make this class package-private after ExperimentalFrameExtractor is removed.
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public final class FrameExtractorInternal {

  /** A plain data object holding all configuration for a frame extraction request. */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  public static final class FrameExtractionRequest {
    public final Context context;
    public final MediaItem mediaItem;
    public final List<Effect> effects;
    public final SeekParameters seekParameters;
    public final MediaCodecSelector mediaCodecSelector;
    @Nullable public final GlObjectsProvider glObjectsProvider;
    @Nullable public final MediaSource.Factory mediaSourceFactory;
    public final boolean extractHdrFrames;
    public final long positionMs;

    public FrameExtractionRequest(
        Context context,
        MediaItem mediaItem,
        List<Effect> effects,
        SeekParameters seekParameters,
        MediaCodecSelector mediaCodecSelector,
        @Nullable GlObjectsProvider glObjectsProvider,
        @Nullable MediaSource.Factory mediaSourceFactory,
        boolean extractHdrFrames,
        long positionMs) {
      this.context = context;
      this.mediaItem = mediaItem;
      this.effects = effects;
      this.seekParameters = seekParameters;
      this.mediaCodecSelector = mediaCodecSelector;
      this.glObjectsProvider = glObjectsProvider;
      this.mediaSourceFactory = mediaSourceFactory;
      this.extractHdrFrames = extractHdrFrames;
      this.positionMs = positionMs;
    }

    /** Creates a copy of this request with a new position. */
    public FrameExtractionRequest copyWithPositionMs(long positionMs) {
      if (this.positionMs == positionMs) {
        return this;
      }
      return new FrameExtractionRequest(
          this.context,
          this.mediaItem,
          this.effects,
          this.seekParameters,
          this.mediaCodecSelector,
          this.glObjectsProvider,
          this.mediaSourceFactory,
          this.extractHdrFrames,
          positionMs);
    }
  }

  private static final Object LOCK = new Object();

  @SuppressWarnings("NonFinalStaticField") // Required for lazy initialization.
  @GuardedBy("LOCK")
  @Nullable
  private static FrameExtractorInternal instance;

  private final AtomicInteger referenceCount;

  private final ExecutionSequencer executionSequencer;
  private final Handler playerHandler;
  private final AtomicBoolean extractedFrameNeedsRendering;
  private final AtomicReference<
          CallbackToFutureAdapter.@NullableType Completer<FrameExtractor.Frame>>
      activeTaskCompleter;

  @Nullable private ExoPlayer player;

  @Nullable private FrameExtractor.Frame lastSeekDedupeFrame;

  private MediaCodecSelector currentMediaCodecSelector;

  private boolean currentExtractHdrFrames;

  @Nullable private GlObjectsProvider currentGlObjectsProvider;
  @Nullable private MediaSource.Factory currentMediaSourceFactory;
  private long thumbnailPresentationTimeMs;

  private FrameExtractorInternal() {
    referenceCount = new AtomicInteger(0);
    executionSequencer = ExecutionSequencer.create();
    extractedFrameNeedsRendering = new AtomicBoolean(false);
    activeTaskCompleter = new AtomicReference<>();
    this.playerHandler = new Handler(Looper.getMainLooper());
    this.currentMediaCodecSelector = MediaCodecSelector.DEFAULT;
    thumbnailPresentationTimeMs = C.TIME_UNSET;
  }

  public static FrameExtractorInternal getInstance() {
    synchronized (LOCK) {
      if (instance == null) {
        instance = new FrameExtractorInternal();
      }
      return instance;
    }
  }

  /** Increments the reference count. */
  public void addReference() {
    referenceCount.incrementAndGet();
  }

  /** Decrements the reference count and releases resources if the count reaches zero. */
  public void releaseReference() {
    ListenableFuture<Void> unused =
        executionSequencer.submit(
            () -> {
              if (referenceCount.decrementAndGet() == 0) {
                if (player != null) {
                  player.release();
                  player = null;
                }
                currentMediaCodecSelector = MediaCodecSelector.DEFAULT;
                currentExtractHdrFrames = false;
                currentGlObjectsProvider = null;
                currentMediaSourceFactory = null;
                lastSeekDedupeFrame = null;
                thumbnailPresentationTimeMs = C.TIME_UNSET;
              }
              return null;
            },
            playerHandler::post);
  }

  /** Submits a frame extraction task to the sequential queue. */
  public ListenableFuture<FrameExtractor.Frame> submitTask(FrameExtractionRequest request) {
    return executionSequencer.submitAsync(
        () -> {
          boolean needsNewPlayer =
              player == null
                  // TODO: b/457376636 - reuse player when switching between HDR and SDR, after the
                  // video processing pipeline is updated.
                  || currentExtractHdrFrames
                  || request.extractHdrFrames
                  // TODO: b/457376636 - reuse the player on error when the video frame processor
                  // can recover from errors.
                  || player.getPlayerError() != null
                  || request.mediaCodecSelector != currentMediaCodecSelector
                  || request.glObjectsProvider != currentGlObjectsProvider
                  || request.mediaSourceFactory != currentMediaSourceFactory;

          boolean needsPrepare =
              needsNewPlayer
                  || !request.mediaItem.equals(checkNotNull(player).getCurrentMediaItem());

          boolean isThumbnailRequest = request.positionMs == C.TIME_UNSET;

          if (needsPrepare) {
            ListenableFuture<FrameExtractor.Frame> prepareFuture =
                processTask(
                    request.copyWithPositionMs(/* positionMs= */ 0),
                    needsNewPlayer,
                    /* needsPrepare= */ true);

            return Futures.transformAsync(
                prepareFuture,
                (prepareResult) -> {
                  long timestampToExtractMs =
                      isThumbnailRequest ? getThumbnailPresentationTimeMs() : request.positionMs;
                  if (prepareResult.presentationTimeMs == timestampToExtractMs) {
                    return Futures.immediateFuture(prepareResult);
                  } else {
                    return processTask(
                        request.copyWithPositionMs(timestampToExtractMs),
                        /* needsNewPlayer= */ false,
                        /* needsPrepare= */ false);
                  }
                },
                playerHandler::post);
          } else {
            long timestampToExtractMs =
                isThumbnailRequest ? getThumbnailPresentationTimeMs() : request.positionMs;
            return processTask(
                request.copyWithPositionMs(timestampToExtractMs), needsNewPlayer, needsPrepare);
          }
        },
        playerHandler::post);
  }

  /** Gets the decoder counters from the player. */
  public ListenableFuture<@NullableType DecoderCounters> getDecoderCounters() {
    return CallbackToFutureAdapter.<@NullableType DecoderCounters>getFuture(
        completer -> {
          ListenableFuture<Void> unused =
              executionSequencer.submit(
                  () -> {
                    DecoderCounters counters =
                        (player != null) ? player.getVideoDecoderCounters() : null;
                    completer.set(counters);
                    return null;
                  },
                  playerHandler::post);
          return "FrameExtractorInternal.getDecoderCounters";
        });
  }

  private long getThumbnailPresentationTimeMs() {
    return thumbnailPresentationTimeMs != C.TIME_UNSET ? thumbnailPresentationTimeMs : 0;
  }

  private ListenableFuture<FrameExtractor.Frame> processTask(
      FrameExtractionRequest request, boolean needsNewPlayer, boolean needsPrepare) {
    return CallbackToFutureAdapter.getFuture(
        completer -> {
          if (!activeTaskCompleter.compareAndSet(null, completer)) {
            completer.setException(new IllegalStateException("Another task is already active"));
            return "FrameExtractorInternal.processTask - conflict";
          }

          ensurePlayerInitialized(request, needsNewPlayer);

          FrameReader frameReader = new FrameReader(this);
          ImmutableList<Effect> videoEffects = buildVideoEffects(request.effects, frameReader);

          ExoPlayer player = checkNotNull(this.player);
          if (needsPrepare) {
            lastSeekDedupeFrame = null;
            extractedFrameNeedsRendering.set(true);
            thumbnailPresentationTimeMs = C.TIME_UNSET;
            player.setVideoEffects(videoEffects);
            player.setMediaItem(request.mediaItem);
            player.setSeekParameters(request.seekParameters);
            player.prepare();
          } else {
            extractedFrameNeedsRendering.set(false);
            player.setVideoEffects(videoEffects);
            player.setSeekParameters(request.seekParameters);
            player.seekTo(request.positionMs);
          }
          return "FrameExtractorInternal.processTask - scheduled";
        });
  }

  private void ensurePlayerInitialized(FrameExtractionRequest request, boolean needsNewPlayer) {
    if (needsNewPlayer) {
      if (player != null) {
        player.release();
      }

      currentMediaCodecSelector = request.mediaCodecSelector;
      currentExtractHdrFrames = request.extractHdrFrames;
      currentGlObjectsProvider = request.glObjectsProvider;
      currentMediaSourceFactory = request.mediaSourceFactory;

      MediaSource.Factory mediaSourceFactoryToUse;
      if (request.mediaSourceFactory != null) {
        mediaSourceFactoryToUse = request.mediaSourceFactory;
      } else {
        mediaSourceFactoryToUse =
            new DefaultMediaSourceFactory(request.context, new DefaultExtractorsFactory());
      }

      player =
          new ExoPlayer.Builder(
                  request.context,
                  /* renderersFactory= */ (eventHandler,
                      videoRendererEventListener,
                      audioRendererEventListener,
                      textRendererOutput,
                      metadataRendererOutput) ->
                      new Renderer[] {
                        new FrameExtractorRenderer(
                            request.context,
                            playerHandler,
                            request.mediaCodecSelector,
                            videoRendererEventListener,
                            !request.extractHdrFrames,
                            request.glObjectsProvider,
                            extractedFrameNeedsRendering,
                            this)
                      },
                  mediaSourceFactoryToUse)
              .setLooper(playerHandler.getLooper())
              .experimentalSetDynamicSchedulingEnabled(true)
              .build();
      player.addAnalyticsListener(new PlayerListener(this));
      player.setPlayWhenReady(false);
    }
  }

  private static ImmutableList<Effect> buildVideoEffects(
      List<Effect> effects, FrameReader frameReader) {
    ImmutableList.Builder<Effect> listBuilder = new ImmutableList.Builder<>();
    listBuilder.addAll(effects);
    listBuilder.add(
        (MatrixTransformation)
            presentationTimeUs -> {
              Matrix mirrorY = new Matrix();
              mirrorY.setScale(/* sx= */ 1, /* sy= */ -1);
              return mirrorY;
            });
    listBuilder.add(frameReader);
    return listBuilder.build();
  }

  private static final class PlayerListener implements AnalyticsListener {
    private final FrameExtractorInternal internal;

    private PlayerListener(FrameExtractorInternal internal) {
      this.internal = internal;
    }

    @Override
    public void onPlayerError(EventTime eventTime, PlaybackException error) {
      CallbackToFutureAdapter.Completer<FrameExtractor.Frame> frameBeingExtractedCompleter =
          checkNotNull(internal.activeTaskCompleter.getAndSet(null));
      frameBeingExtractedCompleter.setException(error);
    }

    @Override
    public void onPlaybackStateChanged(EventTime eventTime, @Player.State int state) {
      if (state == Player.STATE_READY) {
        // The player enters STATE_BUFFERING at the start of a seek.
        // At the end of a seek, the player enters STATE_READY after the video renderer position
        // has been reset, and the renderer reports that it's ready.
        if (!internal.extractedFrameNeedsRendering.get()) {
          // If the seek resolves to the current position, the renderer position will not be reset
          // and extractedFrameNeedsRendering remains false. No frames are rendered. Repeat the
          // previously returned frame.
          CallbackToFutureAdapter.Completer<FrameExtractor.Frame> frameBeingExtractedCompleter =
              checkNotNull(internal.activeTaskCompleter.getAndSet(null));
          frameBeingExtractedCompleter.set(checkNotNull(internal.lastSeekDedupeFrame));
        }
      }
    }
  }

  private static final class FrameReader implements GlEffect {
    private final FrameExtractorInternal internal;

    private FrameReader(FrameExtractorInternal internal) {
      this.internal = internal;
    }

    @Override
    public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr)
        throws VideoFrameProcessingException {
      return new FrameReadingGlShaderProgram(context, useHdr, internal);
    }
  }

  private static final class FrameReadingGlShaderProgram extends PassthroughShaderProgram {
    private final int bytesPerPixel;
    private final boolean useHdr;
    private final boolean hdrUses16BitFloat;
    private final FrameExtractorInternal internal;

    /** The visible portion of the frame. */
    private final ImmutableList<float[]> visiblePolygon =
        ImmutableList.of(
            new float[] {-1, -1, 0, 1},
            new float[] {-1, 1, 0, 1},
            new float[] {1, 1, 0, 1},
            new float[] {1, -1, 0, 1});

    private @MonotonicNonNull GlTextureInfo hlgTextureInfo;
    private @MonotonicNonNull GlProgram glProgram;

    private ByteBuffer byteBuffer;

    private FrameReadingGlShaderProgram(
        Context context, boolean useHdr, FrameExtractorInternal internal)
        throws VideoFrameProcessingException {
      super();
      this.useHdr = useHdr;
      this.internal = internal;
      byteBuffer = ByteBuffer.allocateDirect(0);

      if (useHdr) {
        checkState(SDK_INT >= 34);
        try {
          glProgram =
              new GlProgram(
                  context,
                  /* vertexShaderResId= */ R.raw.vertex_shader_transformation_es3,
                  /* fragmentShaderResId= */ R.raw.fragment_shader_oetf_es3);
        } catch (IOException | GlUtil.GlException e) {
          throw new VideoFrameProcessingException(e);
        }
        glProgram.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix());
        glProgram.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix());
        glProgram.setFloatsUniform("uRgbMatrix", GlUtil.create4x4IdentityMatrix());
        glProgram.setIntUniform("uOutputColorTransfer", COLOR_TRANSFER_HLG);
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.createVertexBuffer(visiblePolygon),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
      }
      // RGBA_1010102 Bitmaps cannot be saved to file prior to API 36. See b/438163272.
      hdrUses16BitFloat = SDK_INT <= 35;
      bytesPerPixel = useHdr && hdrUses16BitFloat ? 8 : 4;
    }

    @Override
    public void queueInputFrame(
        GlObjectsProvider glObjectsProvider, GlTextureInfo inputTexture, long presentationTimeUs) {
      ensureConfigured(glObjectsProvider, inputTexture.width, inputTexture.height);
      Bitmap bitmap;
      if (useHdr) {
        if (SDK_INT < 34 || hlgTextureInfo == null) {
          onError(
              new VideoFrameProcessingException(
                  ExoPlaybackException.createForUnexpected(
                      new IllegalArgumentException(), ERROR_CODE_INVALID_STATE)));
          return;
        }
        try {
          GlUtil.focusFramebufferUsingCurrentContext(
              hlgTextureInfo.fboId, hlgTextureInfo.width, hlgTextureInfo.height);
          GlUtil.checkGlError();
          checkNotNull(glProgram).use();
          glProgram.setSamplerTexIdUniform(
              "uTexSampler", inputTexture.texId, /* texUnitIndex= */ 0);
          glProgram.bindAttributesAndUniforms();
          GLES20.glDrawArrays(
              GLES20.GL_TRIANGLE_FAN, /* first= */ 0, /* count= */ visiblePolygon.size());
          GlUtil.checkGlError();
          // For OpenGL format, internalFormat, type see the docs:
          // https://registry.khronos.org/OpenGL-Refpages/es3/html/glReadPixels.xhtml
          // https://registry.khronos.org/OpenGL-Refpages/es3.0/html/glTexImage2D.xhtml
          GLES20.glReadPixels(
              /* x= */ 0,
              /* y= */ 0,
              hlgTextureInfo.width,
              hlgTextureInfo.height,
              /* format= */ GLES20.GL_RGBA,
              /* type= */ hdrUses16BitFloat
                  ? GLES30.GL_HALF_FLOAT
                  : GLES30.GL_UNSIGNED_INT_2_10_10_10_REV,
              byteBuffer);
          GlUtil.checkGlError();
        } catch (GlUtil.GlException e) {
          onError(new VideoFrameProcessingException(e));
          return;
        }
        bitmap =
            Bitmap.createBitmap(
                /* display= */ null,
                hlgTextureInfo.width,
                hlgTextureInfo.height,
                hdrUses16BitFloat ? RGBA_F16 : RGBA_1010102,
                /* hasAlpha= */ false,
                ColorSpace.get(BT2020_HLG));
      } else {
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
          onError(new VideoFrameProcessingException(e));
          return;
        }
        // According to https://www.khronos.org/opengl/wiki/Pixel_Transfer#Endian_issues,
        // the colors will have the order RGBA in client memory. This is what the bitmap expects:
        // https://developer.android.com/reference/android/graphics/Bitmap.Config.
        bitmap = Bitmap.createBitmap(inputTexture.width, inputTexture.height, ARGB_8888);
      }
      bitmap.copyPixelsFromBuffer(byteBuffer);

      CallbackToFutureAdapter.Completer<FrameExtractor.Frame> frameBeingExtractedCompleter =
          checkNotNull(internal.activeTaskCompleter.getAndSet(null));
      FrameExtractor.Frame frame = new FrameExtractor.Frame(usToMs(presentationTimeUs), bitmap);
      internal.lastSeekDedupeFrame = frame;
      frameBeingExtractedCompleter.set(frame);
      // Drop frame: do not call outputListener.onOutputFrameAvailable().
      // Block effects pipeline: do not call inputListener.onReadyToAcceptInputFrame().
      // The effects pipeline will unblock and receive new frames when flushed after a seek.
      getInputListener().onInputFrameProcessed(inputTexture);
    }

    private void ensureConfigured(GlObjectsProvider glObjectsProvider, int width, int height) {
      int pixelBufferSize = width * height * bytesPerPixel;
      if (byteBuffer.capacity() != pixelBufferSize) {
        byteBuffer = ByteBuffer.allocateDirect(pixelBufferSize);
      }
      byteBuffer.clear();

      if (useHdr) {
        if (hlgTextureInfo == null
            || hlgTextureInfo.width != width
            || hlgTextureInfo.height != height) {
          try {
            if (hlgTextureInfo != null) {
              hlgTextureInfo.release();
            }
            int texId =
                hdrUses16BitFloat
                    ? createTexture(width, height, /* useHighPrecisionColorComponents= */ true)
                    : createRgb10A2Texture(width, height);
            hlgTextureInfo = glObjectsProvider.createBuffersForTexture(texId, width, height);
          } catch (GlUtil.GlException e) {
            onError(new VideoFrameProcessingException(e));
          }
        }
      }
    }
  }

  /** A custom MediaCodecVideoRenderer that renders only one frame per position reset. */
  private static final class FrameExtractorRenderer extends MediaCodecVideoRenderer {
    private final boolean toneMapHdrToSdr;
    @Nullable private final GlObjectsProvider glObjectsProvider;
    private final AtomicBoolean extractedFrameNeedsRendering;
    private final FrameExtractorInternal internal;

    private boolean frameRenderedSinceLastPositionReset;
    private List<Effect> effectsFromPlayer;
    @Nullable private Effect rotation;

    private FrameExtractorRenderer(
        Context context,
        Handler playerHandler,
        MediaCodecSelector mediaCodecSelector,
        VideoRendererEventListener videoRendererEventListener,
        boolean toneMapHdrToSdr,
        @Nullable GlObjectsProvider glObjectsProvider,
        AtomicBoolean extractedFrameNeedsRendering,
        FrameExtractorInternal internal) {
      super(
          new Builder(context)
              .setMediaCodecSelector(mediaCodecSelector)
              .setAllowedJoiningTimeMs(0)
              .setEventHandler(playerHandler)
              .setEventListener(videoRendererEventListener)
              .setMaxDroppedFramesToNotify(0));
      this.toneMapHdrToSdr = toneMapHdrToSdr;
      this.glObjectsProvider = glObjectsProvider;
      this.extractedFrameNeedsRendering = extractedFrameNeedsRendering;
      this.internal = internal;
      effectsFromPlayer = ImmutableList.of();
    }

    @Override
    protected PlaybackVideoGraphWrapper createPlaybackVideoGraphWrapper(
        Context context, VideoFrameReleaseControl videoFrameReleaseControl) {
      // Avoid calling super.createPlaybackVideoGraphWrapper() to bypass reflection. Direct
      // instantiation makes the dependency explicit to R8's static analysis, ensuring required
      // classes are preserved.
      DefaultVideoFrameProcessor.Factory.Builder videoFrameProcessorFactoryBuilder =
          new DefaultVideoFrameProcessor.Factory.Builder();
      if (glObjectsProvider != null) {
        videoFrameProcessorFactoryBuilder.setGlObjectsProvider(glObjectsProvider);
      }
      VideoGraph.Factory videoGraphFactory =
          new SingleInputVideoGraph.Factory(videoFrameProcessorFactoryBuilder.build());
      return new PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
          .experimentalSetLateThresholdToDropInputUs(C.TIME_UNSET)
          .setEnablePlaylistMode(true)
          .setClock(getClock())
          .setVideoGraphFactory(videoGraphFactory)
          .build();
    }

    @Override
    protected void onStreamChanged(
        Format[] formats,
        long startPositionUs,
        long offsetUs,
        MediaSource.MediaPeriodId mediaPeriodId)
        throws ExoPlaybackException {
      super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId);
      frameRenderedSinceLastPositionReset = false;
      setRotation(null);

      for (Format format : formats) {
        if (MimeTypes.isVideo(format.sampleMimeType)) {
          if (format.metadata != null) {
            @Nullable
            ThumbnailMetadata thumbnailMetadata =
                format.metadata.getFirstEntryOfType(ThumbnailMetadata.class);
            if (thumbnailMetadata != null && thumbnailMetadata.presentationTimeUs >= 0) {
              internal.thumbnailPresentationTimeMs =
                  Util.usToMs(thumbnailMetadata.presentationTimeUs);
              break;
            }
          }
        }
      }
    }

    @Override
    public void setVideoEffects(List<Effect> effects) {
      effectsFromPlayer = effects;
      setEffectsWithRotation();
    }

    @CallSuper
    @Override
    protected boolean maybeInitializeProcessingPipeline(Format format) throws ExoPlaybackException {
      if (isTransferHdr(format.colorInfo) && toneMapHdrToSdr) {
        // Setting the VideoSink format to SDR_BT709_LIMITED tone maps to SDR.
        format = format.buildUpon().setColorInfo(SDR_BT709_LIMITED).build();
      }
      return super.maybeInitializeProcessingPipeline(format);
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
          setRotation(
              new ScaleAndRotateTransformation.Builder()
                  .setRotationDegrees(360 - format.rotationDegrees)
                  .build());
          formatHolder.format = format.buildUpon().setRotationDegrees(0).build();
        }
      }
      return super.onInputFormatChanged(formatHolder);
    }

    private void setRotation(@Nullable Effect rotation) {
      this.rotation = rotation;
      setEffectsWithRotation();
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
    public boolean isReady() {
      // When using FrameReadingGlShaderProgram, frames will not be rendered to the output surface,
      // and VideoFrameRenderControl.onFrameAvailableForRendering will not be called. The base class
      // never becomes ready. Treat this renderer as ready if a frame has been rendered into the
      // effects pipeline. The renderer needs to become ready for ExoPlayer to enter STATE_READY.
      return frameRenderedSinceLastPositionReset;
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
      if (!frameRenderedSinceLastPositionReset) {
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
      if (frameRenderedSinceLastPositionReset) {
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
      if (frameRenderedSinceLastPositionReset) {
        // Do not skip this buffer to prevent the decoder from making more progress.
        return;
      }
      frameRenderedSinceLastPositionReset = true;
      super.renderOutputBufferV21(codec, index, presentationTimeUs, releaseTimeNs);
    }

    @Override
    protected void onPositionReset(
        long positionUs, boolean joining, boolean sampleStreamIsResetToKeyFrame)
        throws ExoPlaybackException {
      frameRenderedSinceLastPositionReset = false;
      extractedFrameNeedsRendering.set(true);
      super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame);
    }
  }
}
