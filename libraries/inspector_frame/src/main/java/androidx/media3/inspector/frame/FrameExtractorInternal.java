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
package androidx.media3.inspector.frame;

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
import static java.lang.Math.max;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Gainmap;
import android.graphics.Matrix;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.CallSuper;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
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
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlRect;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Log;
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
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.LoadControl;
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
/* package */ final class FrameExtractorInternal {
  private static final String TAG = "FrameExtractorInternal";

  /** A plain data object holding all configuration for a frame extraction request. */
  /* package */ static final class FrameExtractionRequest {
    private final Context context;
    private final MediaItem mediaItem;
    private final List<Effect> effects;
    private final SeekParameters seekParameters;
    private final MediaCodecSelector mediaCodecSelector;
    @Nullable private final GlObjectsProvider glObjectsProvider;
    @Nullable private final MediaSource.Factory mediaSourceFactory;
    private final boolean extractHdrFrames;
    private final boolean enableUltraHdr;
    private final long positionMs;

    /* package */ FrameExtractionRequest(
        Context context,
        MediaItem mediaItem,
        List<Effect> effects,
        SeekParameters seekParameters,
        MediaCodecSelector mediaCodecSelector,
        @Nullable GlObjectsProvider glObjectsProvider,
        @Nullable MediaSource.Factory mediaSourceFactory,
        boolean extractHdrFrames,
        boolean enableUltraHdr,
        long positionMs) {
      this.context = context;
      this.mediaItem = mediaItem;
      this.effects = effects;
      this.seekParameters = seekParameters;
      this.mediaCodecSelector = mediaCodecSelector;
      this.glObjectsProvider = glObjectsProvider;
      this.mediaSourceFactory = mediaSourceFactory;
      this.extractHdrFrames = extractHdrFrames;
      this.enableUltraHdr = enableUltraHdr;
      this.positionMs = positionMs;
    }

    /** Creates a copy of this request with a new position. */
    /* package */ FrameExtractionRequest copyWithPositionMs(long positionMs) {
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
          this.enableUltraHdr,
          positionMs);
    }
  }

  private static final Object LOCK = new Object();

  // Matches libultrahdr's default max content boost for HLG (hdr_white_nits / kSdrWhiteNits =
  // 1000.0f / 203.0f).
  // See: https://github.com/google/libultrahdr/blob/main/lib/include/ultrahdr/gainmapmath.h
  // (kHlgMaxNits, kSdrWhiteNits) and
  // https://github.com/google/libultrahdr/blob/main/lib/src/jpegr.cpp (max_content_boost
  // calculation)
  private static final float ULTRA_HDR_MAX_BOOST = 1000.0f / 203.0f;
  private static final float ULTRA_HDR_MIN_BOOST = 1.0f;
  private static final int ULTRA_HDR_GAINMAP_SCALE_FACTOR = 4;

  private static final MatrixTransformation MIRROR_Y_TRANSFORMATION =
      presentationTimeUs -> {
        Matrix mirrorY = new Matrix();
        mirrorY.setScale(/* sx= */ 1, /* sy= */ -1);
        return mirrorY;
      };

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

  private boolean extractHdrFrames;
  private boolean enableUltraHdr;

  @Nullable private GlObjectsProvider currentGlObjectsProvider;
  @Nullable private MediaSource.Factory currentMediaSourceFactory;
  private long thumbnailPresentationTimeMs;

  private FrameExtractorInternal() {
    referenceCount = new AtomicInteger(0);
    executionSequencer = ExecutionSequencer.create();
    extractedFrameNeedsRendering = new AtomicBoolean(false);
    activeTaskCompleter = new AtomicReference<>();
    this.playerHandler = new Handler(Looper.getMainLooper());
    this.currentMediaCodecSelector = MediaCodecSelector.PREFER_SOFTWARE;
    this.extractHdrFrames = false;
    this.enableUltraHdr = false;
    thumbnailPresentationTimeMs = C.TIME_UNSET;
  }

  /* package */ static FrameExtractorInternal getInstance() {
    synchronized (LOCK) {
      if (instance == null) {
        instance = new FrameExtractorInternal();
      }
      return instance;
    }
  }

  /** Increments the reference count. */
  /* package */ void addReference() {
    referenceCount.incrementAndGet();
  }

  /** Decrements the reference count and releases resources if the count reaches zero. */
  /* package */ void releaseReference() {
    ListenableFuture<Void> unused =
        executionSequencer.submit(
            () -> {
              if (referenceCount.decrementAndGet() == 0) {
                if (player != null) {
                  player.release();
                  player = null;
                }
                currentMediaCodecSelector = MediaCodecSelector.PREFER_SOFTWARE;
                extractHdrFrames = false;
                enableUltraHdr = false;
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
  /* package */ ListenableFuture<FrameExtractor.Frame> submitTask(FrameExtractionRequest request) {
    return executionSequencer.submitAsync(
        () -> {
          ExoPlayer activePlayer = player;
          boolean mediaItemChanged =
              activePlayer == null || !request.mediaItem.equals(activePlayer.getCurrentMediaItem());
          boolean needsNewPlayer =
              activePlayer == null
                  || needsNewPlayerDueToChangingHdr(request, mediaItemChanged)
                  // TODO: b/457376636 - reuse the player on error when the video frame processor
                  // can recover from errors.
                  || activePlayer.getPlayerError() != null
                  || request.mediaCodecSelector != currentMediaCodecSelector
                  || request.glObjectsProvider != currentGlObjectsProvider
                  || request.mediaSourceFactory != currentMediaSourceFactory;

          boolean needsPrepare = needsNewPlayer || mediaItemChanged;

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
            Tracks tracks = checkNotNull(player).getCurrentTracks();
            assertTracksSupported(tracks);

            long timestampToExtractMs =
                isThumbnailRequest ? getThumbnailPresentationTimeMs() : request.positionMs;
            return processTask(
                request.copyWithPositionMs(timestampToExtractMs), needsNewPlayer, needsPrepare);
          }
        },
        playerHandler::post);
  }

  /** Gets the decoder counters from the player. */
  /* package */ ListenableFuture<@NullableType DecoderCounters> getDecoderCounters() {
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

  private static void assertTracksSupported(Tracks tracks) {
    if (!tracks.containsType(C.TRACK_TYPE_VIDEO)) {
      throw new IllegalArgumentException("Media item does not contain any video tracks.");
    }

    boolean hasSelectedClearVideoTrack = false;
    boolean hasDrmVideoTrack = false;

    for (Tracks.Group group : tracks.getGroups()) {
      if (group.getType() == C.TRACK_TYPE_VIDEO) {
        for (int i = 0; i < group.length; i++) {
          Format format = group.getTrackFormat(i);
          if (format.drmInitData != null || format.cryptoType != C.CRYPTO_TYPE_NONE) {
            hasDrmVideoTrack = true;
          } else if (group.isTrackSelected(i)) {
            hasSelectedClearVideoTrack = true;
          }
        }
      }
    }

    if (hasDrmVideoTrack && !hasSelectedClearVideoTrack) {
      throw new UnsupportedOperationException(
          "Frame extraction from DRM-protected media is not supported.");
    }
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
      extractHdrFrames = request.extractHdrFrames;
      enableUltraHdr = request.enableUltraHdr;
      currentGlObjectsProvider = request.glObjectsProvider;
      currentMediaSourceFactory = request.mediaSourceFactory;

      MediaSource.Factory mediaSourceFactoryToUse;
      if (request.mediaSourceFactory != null) {
        mediaSourceFactoryToUse = request.mediaSourceFactory;
      } else {
        mediaSourceFactoryToUse =
            new DefaultMediaSourceFactory(request.context, new DefaultExtractorsFactory());
      }

      LoadControl loadControl =
          new DefaultLoadControl.Builder()
              // Buffer 1000ms to safely cover reference frame reordering delays (e.g. B-frames).
              .setBufferDurationsMs(
                  /* minBufferMs= */ 1000,
                  /* maxBufferMs= */ 1000,
                  /* bufferForPlaybackMs= */ 0,
                  /* bufferForPlaybackAfterRebufferMs= */ 0)
              .build();

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
                            /* toneMapHdrToSdr= */ !request.extractHdrFrames
                                && !request.enableUltraHdr,
                            request.glObjectsProvider,
                            extractedFrameNeedsRendering,
                            this)
                      },
                  mediaSourceFactoryToUse)
              .setLoadControl(loadControl)
              .setLooper(playerHandler.getLooper())
              .build();
      player.addAnalyticsListener(new PlayerListener(this));
      player.setPlayWhenReady(false);
    }
  }

  private boolean needsNewPlayerDueToChangingHdr(
      FrameExtractionRequest request, boolean mediaItemChanged) {
    // TODO: b/457376636 - The video processing pipeline doesn't support switching between
    // HDR and SDR input. Recreate the player if the item is changing and HDR could be used, or
    // if the item stays the same but HDR settings are changing.
    boolean requestUsesHdrPipeline = request.extractHdrFrames || request.enableUltraHdr;
    boolean currentUsesHdrPipeline = extractHdrFrames || enableUltraHdr;
    return mediaItemChanged
        ? (requestUsesHdrPipeline || currentUsesHdrPipeline)
        : requestUsesHdrPipeline != currentUsesHdrPipeline;
  }

  private static ImmutableList<Effect> buildVideoEffects(
      List<Effect> effects, FrameReader frameReader) {
    ImmutableList.Builder<Effect> listBuilder = new ImmutableList.Builder<>();
    listBuilder.addAll(effects);
    listBuilder.add(MIRROR_Y_TRANSFORMATION);
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
          internal.activeTaskCompleter.getAndSet(null);
      if (frameBeingExtractedCompleter != null) {
        frameBeingExtractedCompleter.setException(error);
      }
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
              internal.activeTaskCompleter.getAndSet(null);
          if (frameBeingExtractedCompleter != null) {
            if (internal.lastSeekDedupeFrame != null) {
              frameBeingExtractedCompleter.set(internal.lastSeekDedupeFrame);
            } else {
              frameBeingExtractedCompleter.setException(
                  new IllegalStateException(
                      "Expected to deduplicate frame, but no previous frame was found."));
            }
          }
        }
      } else if (state == Player.STATE_ENDED) {
        CallbackToFutureAdapter.Completer<FrameExtractor.Frame> frameBeingExtractedCompleter =
            internal.activeTaskCompleter.getAndSet(null);
        if (frameBeingExtractedCompleter != null) {
          frameBeingExtractedCompleter.setException(
              new IllegalStateException("Reached end of stream without extracting a frame."));
        }
      }
    }

    @Override
    public void onTracksChanged(EventTime eventTime, Tracks tracks) {
      if (tracks.isEmpty()) {
        return;
      }
      try {
        assertTracksSupported(tracks);
      } catch (RuntimeException e) {
        CallbackToFutureAdapter.Completer<FrameExtractor.Frame> frameBeingExtractedCompleter =
            internal.activeTaskCompleter.getAndSet(null);
        if (frameBeingExtractedCompleter != null) {
          frameBeingExtractedCompleter.setException(e);
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

    @Override
    public boolean equals(@Nullable Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null || getClass() != obj.getClass()) {
        return false;
      }
      FrameReader that = (FrameReader) obj;
      return internal.equals(that.internal);
    }

    @Override
    public int hashCode() {
      return internal.hashCode();
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

    private @MonotonicNonNull GlTextureInfo ultraHdrTextureInfo;
    private int gainmapTextureId;
    private @MonotonicNonNull GlTextureInfo downscaledGainmapTextureInfo;
    private int downscaledGainmapTextureId;

    private ByteBuffer byteBuffer;
    private ByteBuffer gainmapByteBuffer;

    private FrameReadingGlShaderProgram(
        Context context, boolean useHdr, FrameExtractorInternal internal)
        throws VideoFrameProcessingException {
      super();
      this.useHdr = useHdr;
      this.internal = internal;
      gainmapTextureId = C.INDEX_UNSET;
      downscaledGainmapTextureId = C.INDEX_UNSET;
      byteBuffer = ByteBuffer.allocateDirect(0);
      gainmapByteBuffer = ByteBuffer.allocateDirect(0);

      if (useHdr) {
        checkState(SDK_INT >= 34);
        try {
          int fragmentShaderResId =
              shouldReadUltraHdrFrame()
                  ? R.raw.fragment_shader_hdr_to_ultra_hdr_es3
                  : R.raw.fragment_shader_oetf_es3;
          glProgram =
              new GlProgram(
                  context,
                  /* vertexShaderResId= */ R.raw.vertex_shader_transformation_es3,
                  fragmentShaderResId);
        } catch (IOException | GlUtil.GlException e) {
          throw new VideoFrameProcessingException(e);
        }
        glProgram.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix());
        glProgram.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix());
        if (shouldReadUltraHdrFrame()) {
          glProgram.setFloatUniform("uMaxBoost", ULTRA_HDR_MAX_BOOST);
          // TODO: b/524121859 - Obtain SDR and HDR nits from metadata instead of hardcoding.
          glProgram.setFloatUniform("uSdrReferenceWhiteNits", 203.0f);
          glProgram.setFloatUniform("uHdrPeakNits", 1000.0f);
        } else {
          glProgram.setFloatsUniform("uRgbMatrix", GlUtil.create4x4IdentityMatrix());
          glProgram.setIntUniform("uOutputColorTransfer", COLOR_TRANSFER_HLG);
        }
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.createVertexBuffer(visiblePolygon),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
      }
      // RGBA_1010102 Bitmaps cannot be saved to file prior to API 36. See b/438163272.
      hdrUses16BitFloat = SDK_INT <= 35;
      bytesPerPixel = (useHdr && !shouldReadUltraHdrFrame() && hdrUses16BitFloat) ? 8 : 4;
    }

    @Override
    public void queueInputFrame(
        GlObjectsProvider glObjectsProvider, GlTextureInfo inputTexture, long presentationTimeUs) {
      ensureConfigured(glObjectsProvider, inputTexture.width, inputTexture.height);
      Bitmap bitmap;

      if (shouldReadUltraHdrFrame()) {
        GlTextureInfo ultraHdrTextureInfo = checkNotNull(this.ultraHdrTextureInfo);
        GlTextureInfo downscaledGainmapTextureInfo =
            checkNotNull(this.downscaledGainmapTextureInfo);
        try {
          // 1. Render HDR input texture to the MRT FBO (SDR + Gainmap)
          GlUtil.focusFramebufferUsingCurrentContext(
              ultraHdrTextureInfo.fboId, ultraHdrTextureInfo.width, ultraHdrTextureInfo.height);
          GlUtil.checkGlError();
          checkNotNull(glProgram).use();
          glProgram.setSamplerTexIdUniform(
              "uTexSampler", inputTexture.texId, /* texUnitIndex= */ 0);
          glProgram.bindAttributesAndUniforms();
          GLES20.glDrawArrays(
              GLES20.GL_TRIANGLE_FAN, /* first= */ 0, /* count= */ visiblePolygon.size());
          GlUtil.checkGlError();

          // Blit full-res gainmap (attachment 1) to downscaled FBO (attachment 0)
          GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT1);
          GlUtil.blitFrameBuffer(
              ultraHdrTextureInfo.fboId,
              new GlRect(ultraHdrTextureInfo.width, ultraHdrTextureInfo.height),
              downscaledGainmapTextureInfo.fboId,
              new GlRect(downscaledGainmapTextureInfo.width, downscaledGainmapTextureInfo.height));

          // 2. Read SDR pixels from COLOR_ATTACHMENT0 of full-res FBO
          GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, ultraHdrTextureInfo.fboId);
          GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0);
          GLES20.glReadPixels(
              /* x= */ 0,
              /* y= */ 0,
              /* width= */ ultraHdrTextureInfo.width,
              /* height= */ ultraHdrTextureInfo.height,
              /* format= */ GLES20.GL_RGBA,
              /* type= */ GLES20.GL_UNSIGNED_BYTE,
              /* pixels= */ byteBuffer);
          GlUtil.checkGlError();

          // 3. Read Gainmap pixels from COLOR_ATTACHMENT0 of downscaled FBO
          GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, downscaledGainmapTextureInfo.fboId);
          GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0);
          GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1);
          GLES20.glReadPixels(
              /* x= */ 0,
              /* y= */ 0,
              /* width= */ downscaledGainmapTextureInfo.width,
              /* height= */ downscaledGainmapTextureInfo.height,
              /* format= */ GLES30.GL_RED,
              /* type= */ GLES20.GL_UNSIGNED_BYTE,
              /* pixels= */ gainmapByteBuffer);
          GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 4);
          GlUtil.checkGlError();
        } catch (GlUtil.GlException e) {
          onError(new VideoFrameProcessingException(e));
          return;
        }

        // 4. Create Bitmaps directly from buffers (No CPU unpacking loop)
        byteBuffer.rewind();
        Bitmap sdrBitmap =
            Bitmap.createBitmap(ultraHdrTextureInfo.width, ultraHdrTextureInfo.height, ARGB_8888);
        sdrBitmap.copyPixelsFromBuffer(byteBuffer);

        gainmapByteBuffer.rewind();
        Bitmap gainmapBitmap =
            Bitmap.createBitmap(
                downscaledGainmapTextureInfo.width,
                downscaledGainmapTextureInfo.height,
                Bitmap.Config.ALPHA_8);
        gainmapBitmap.copyPixelsFromBuffer(gainmapByteBuffer);

        Gainmap gainmap = new Gainmap(gainmapBitmap);
        gainmap.setRatioMax(ULTRA_HDR_MAX_BOOST, ULTRA_HDR_MAX_BOOST, ULTRA_HDR_MAX_BOOST);
        gainmap.setRatioMin(ULTRA_HDR_MIN_BOOST, ULTRA_HDR_MIN_BOOST, ULTRA_HDR_MIN_BOOST);
        gainmap.setDisplayRatioForFullHdr(ULTRA_HDR_MAX_BOOST);
        gainmap.setMinDisplayRatioForHdrTransition(ULTRA_HDR_MIN_BOOST);
        sdrBitmap.setGainmap(gainmap);

        bitmap = sdrBitmap;

      } else {
        // HLG or SDR Flow
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
      }

      CallbackToFutureAdapter.Completer<FrameExtractor.Frame> frameBeingExtractedCompleter =
          internal.activeTaskCompleter.getAndSet(null);
      if (frameBeingExtractedCompleter != null) {
        FrameExtractor.Frame frame = new FrameExtractor.Frame(usToMs(presentationTimeUs), bitmap);
        internal.lastSeekDedupeFrame = frame;
        frameBeingExtractedCompleter.set(frame);
      }
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

      if (shouldReadUltraHdrFrame()) {
        int downscaledWidth = max(1, width / ULTRA_HDR_GAINMAP_SCALE_FACTOR);
        int downscaledHeight = max(1, height / ULTRA_HDR_GAINMAP_SCALE_FACTOR);
        int gainmapSize = downscaledWidth * downscaledHeight * 1;
        if (gainmapByteBuffer.capacity() != gainmapSize) {
          gainmapByteBuffer = ByteBuffer.allocateDirect(gainmapSize);
        }
        gainmapByteBuffer.clear();
      }

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

      if (shouldReadUltraHdrFrame()) {
        if (ultraHdrTextureInfo == null
            || ultraHdrTextureInfo.width != width
            || ultraHdrTextureInfo.height != height) {
          try {
            if (ultraHdrTextureInfo != null) {
              ultraHdrTextureInfo.release();
            }
            if (gainmapTextureId != C.INDEX_UNSET) {
              GlUtil.deleteTexture(gainmapTextureId);
              gainmapTextureId = C.INDEX_UNSET;
            }

            int sdrTexId =
                createTexture(width, height, /* useHighPrecisionColorComponents= */ false);
            ultraHdrTextureInfo =
                glObjectsProvider.createBuffersForTexture(sdrTexId, width, height);

            gainmapTextureId = createRed8Texture(width, height);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ultraHdrTextureInfo.fboId);
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT1,
                GLES20.GL_TEXTURE_2D,
                gainmapTextureId,
                /* level= */ 0);
            GlUtil.checkGlError();

            int[] drawBuffers = {GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1};
            GLES30.glDrawBuffers(2, drawBuffers, /* offset= */ 0);
            GlUtil.checkGlError();
          } catch (GlUtil.GlException e) {
            onError(new VideoFrameProcessingException(e));
          }
        }

        int downscaledWidth = max(1, width / ULTRA_HDR_GAINMAP_SCALE_FACTOR);
        int downscaledHeight = max(1, height / ULTRA_HDR_GAINMAP_SCALE_FACTOR);
        if (downscaledGainmapTextureInfo == null
            || downscaledGainmapTextureInfo.width != downscaledWidth
            || downscaledGainmapTextureInfo.height != downscaledHeight) {
          try {
            if (downscaledGainmapTextureInfo != null) {
              downscaledGainmapTextureInfo.release();
            }
            if (downscaledGainmapTextureId != C.INDEX_UNSET) {
              GlUtil.deleteTexture(downscaledGainmapTextureId);
              downscaledGainmapTextureId = C.INDEX_UNSET;
            }

            downscaledGainmapTextureId = createRed8Texture(downscaledWidth, downscaledHeight);
            downscaledGainmapTextureInfo =
                glObjectsProvider.createBuffersForTexture(
                    downscaledGainmapTextureId, downscaledWidth, downscaledHeight);
          } catch (GlUtil.GlException e) {
            onError(new VideoFrameProcessingException(e));
          }
        }
      }
    }

    private static int createRed8Texture(int width, int height) throws GlUtil.GlException {
      int texId = GlUtil.generateTexture();
      GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, texId, GLES20.GL_LINEAR);
      GLES30.glTexImage2D(
          GLES30.GL_TEXTURE_2D,
          /* level= */ 0,
          GLES30.GL_R8,
          width,
          height,
          /* border= */ 0,
          GLES30.GL_RED,
          GLES30.GL_UNSIGNED_BYTE,
          /* buffer= */ null);
      GlUtil.checkGlError();
      return texId;
    }

    @Override
    public void release() throws VideoFrameProcessingException {
      // TODO(b/517020679): use FrameProcessorUtils.runAllAndAccumulateExceptions once available.
      super.release();
      if (glProgram != null) {
        try {
          glProgram.delete();
        } catch (GlUtil.GlException e) {
          Log.w(TAG, "Error deleting glProgram", e);
        }
      }
      if (hlgTextureInfo != null) {
        try {
          hlgTextureInfo.release();
        } catch (GlUtil.GlException e) {
          Log.w(TAG, "Error releasing hlgTextureInfo", e);
        }
      }
      if (ultraHdrTextureInfo != null) {
        try {
          ultraHdrTextureInfo.release();
        } catch (GlUtil.GlException e) {
          Log.w(TAG, "Error releasing ultraHdrTextureInfo", e);
        }
      }
      if (gainmapTextureId != C.INDEX_UNSET) {
        try {
          GlUtil.deleteTexture(gainmapTextureId);
        } catch (GlUtil.GlException e) {
          Log.w(TAG, "Error deleting gainmapTexture", e);
        }
      }

      if (downscaledGainmapTextureInfo != null) {
        try {
          downscaledGainmapTextureInfo.release();
        } catch (GlUtil.GlException e) {
          Log.w(TAG, "Error releasing downscaledGainmapTextureInfo", e);
        }
      }
      if (downscaledGainmapTextureId != C.INDEX_UNSET) {
        try {
          GlUtil.deleteTexture(downscaledGainmapTextureId);
        } catch (GlUtil.GlException e) {
          Log.w(TAG, "Error deleting downscaledGainmapTexture", e);
        }
      }
    }

    private boolean shouldReadUltraHdrFrame() {
      return SDK_INT >= 34 && useHdr && !internal.extractHdrFrames && internal.enableUltraHdr;
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
