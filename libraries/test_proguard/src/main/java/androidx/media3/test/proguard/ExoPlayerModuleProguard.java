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
package androidx.media3.test.proguard;

import static com.google.common.base.Preconditions.checkState;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.Flags;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.Util;
import androidx.media3.database.DefaultDatabaseProvider;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.exoplayer.video.VideoSink;
import com.google.common.collect.ImmutableList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Class exercising reflection code in the ExoPlayer module that relies on a correct proguard
 * config.
 *
 * <p>Note on adding tests: Verify that tests fail without the relevant proguard config. Be careful
 * with adding new direct class references that may let other tests pass without proguard config.
 */
public final class ExoPlayerModuleProguard {

  private ExoPlayerModuleProguard() {}

  /** Creates and releases an {@link ExoPlayer}. */
  public static void createAndReleaseExoPlayer(Context context) {
    ExoPlayer player = new ExoPlayer.Builder(context).build();
    player.release();
  }

  /** Creates a LibvpxVideoRenderer with {@link DefaultRenderersFactory}. */
  public static void createLibvpxVideoRendererWithDefaultRenderersFactory(Context context) {
    for (Renderer renderer : createDefaultRenderersFactoryRenderers(context)) {
      // Don't use instanceof to prevent including the class in the apk directly.
      if ("LibvpxVideoRenderer".equals(renderer.getName())) {
        return;
      }
    }
    throw new IllegalStateException();
  }

  /** Creates a Libdav1dVideoRenderer with {@link DefaultRenderersFactory}. */
  public static void createLibdav1dVideoRendererWithDefaultRenderersFactory(Context context) {
    Flags.disableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    try {
      Renderer[] renderers = createDefaultRenderersFactoryRenderers(context);
      try {
        for (Renderer renderer : renderers) {
          // Don't use instanceof to prevent including the class in the apk directly.
          if (renderer.getName().equals("Libdav1dVideoRenderer")) {
            return;
          }
        }
      } finally {
        for (Renderer renderer : renderers) {
          renderer.release();
        }
      }
    } finally {
      Flags.resetForTesting();
    }
    throw new IllegalStateException();
  }

  /**
   * Queries AV1 support and initializes a {@code Dav1dCodecAdapter} via {@link
   * DefaultRenderersFactory} without falling back to platform {@code MediaCodec} decoders.
   */
  public static void createDav1dCodecAdapterWithDefaultRenderersFactory(Context context)
      throws Exception {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);
    try {
      Format format =
          new Format.Builder()
              .setSampleMimeType(MimeTypes.VIDEO_AV1)
              .setWidth(320)
              .setHeight(240)
              .build();
      initializeExtensionCodecViaDefaultRenderersFactory(
          context, MediaCodecVideoRenderer.class, format);
    } finally {
      Flags.resetForTesting();
    }
  }

  /** Creates a ExperimentalFfmpegVideoRenderer with {@link DefaultRenderersFactory}. */
  public static void createFfmpegVideoRendererWithDefaultRenderersFactory(Context context) {
    for (Renderer renderer : createDefaultRenderersFactoryRenderers(context)) {
      // Don't use instanceof to prevent including the class in the apk directly.
      if ("ExperimentalFfmpegVideoRenderer".equals(renderer.getName())) {
        return;
      }
    }
    throw new IllegalStateException();
  }

  /** Creates a LibopusAudioRenderer with {@link DefaultRenderersFactory}. */
  public static void createLibopusAudioRendererWithDefaultRenderersFactory(Context context) {
    for (Renderer renderer : createDefaultRenderersFactoryRenderers(context)) {
      // Don't use instanceof to prevent including the class in the apk directly.
      if ("LibopusAudioRenderer".equals(renderer.getName())) {
        return;
      }
    }
    throw new IllegalStateException();
  }

  /** Creates a LibflacAudioRenderer with {@link DefaultRenderersFactory}. */
  public static void createLibflacAudioRendererWithDefaultRenderersFactory(Context context) {
    for (Renderer renderer : createDefaultRenderersFactoryRenderers(context)) {
      // Don't use instanceof to prevent including the class in the apk directly.
      if ("LibflacAudioRenderer".equals(renderer.getName())) {
        return;
      }
    }
    throw new IllegalStateException();
  }

  /** Creates a FfmpegAudioRenderer with {@link DefaultRenderersFactory}. */
  public static void createFfmpegAudioRendererWithDefaultRenderersFactory(Context context) {
    for (Renderer renderer : createDefaultRenderersFactoryRenderers(context)) {
      // Don't use instanceof to prevent including the class in the apk directly.
      if ("FfmpegAudioRenderer".equals(renderer.getName())) {
        return;
      }
    }
    throw new IllegalStateException();
  }

  /** Creates a MidiRenderer with {@link DefaultRenderersFactory}. */
  public static void createMidiRendererWithDefaultRenderersFactory(Context context) {
    for (Renderer renderer : createDefaultRenderersFactoryRenderers(context)) {
      // Don't use instanceof to prevent including the class in the apk directly.
      if (Objects.equals(renderer.getName(), "MidiRenderer")) {
        return;
      }
    }
    throw new IllegalStateException();
  }

  /** Creates a IamfAudioRenderer with {@link DefaultRenderersFactory}. */
  public static void createIamfAudioRendererWithDefaultRenderersFactory(Context context) {
    for (Renderer renderer : createDefaultRenderersFactoryRenderers(context)) {
      // Don't use instanceof to prevent including the class in the apk directly.
      if (Objects.equals(renderer.getName(), "IamfAudioRenderer")) {
        return;
      }
    }
    throw new IllegalStateException();
  }

  /** Creates a DASH downloader with {@link DefaultDownloaderFactory}. */
  public static void createDashDownloaderWithDefaultDownloaderFactory(Context context) {
    CacheDataSource.Factory cacheDataSourceFactory = createTestCacheDataSourceFactory(context);
    try {
      DefaultDownloaderFactory downloaderFactory =
          new DefaultDownloaderFactory(cacheDataSourceFactory, /* executor= */ Runnable::run);
      downloaderFactory.createDownloader(
          new DownloadRequest.Builder(/* id= */ "contentId", Uri.EMPTY)
              .setMimeType(MimeTypes.APPLICATION_MPD)
              .build());
    } finally {
      cacheDataSourceFactory.getCache().release();
    }
  }

  /** Creates an HLS downloader with {@link DefaultDownloaderFactory}. */
  public static void createHlsDownloaderWithDefaultDownloaderFactory(Context context) {
    CacheDataSource.Factory cacheDataSourceFactory = createTestCacheDataSourceFactory(context);
    try {
      DefaultDownloaderFactory downloaderFactory =
          new DefaultDownloaderFactory(cacheDataSourceFactory, /* executor= */ Runnable::run);
      downloaderFactory.createDownloader(
          new DownloadRequest.Builder(/* id= */ "contentId", Uri.EMPTY)
              .setMimeType(MimeTypes.APPLICATION_M3U8)
              .build());
    } finally {
      cacheDataSourceFactory.getCache().release();
    }
  }

  /** Creates a SmoothStreaming downloader with {@link DefaultDownloaderFactory}. */
  public static void createSsDownloaderWithDefaultDownloaderFactory(Context context) {
    CacheDataSource.Factory cacheDataSourceFactory = createTestCacheDataSourceFactory(context);
    try {
      DefaultDownloaderFactory downloaderFactory =
          new DefaultDownloaderFactory(cacheDataSourceFactory, /* executor= */ Runnable::run);
      downloaderFactory.createDownloader(
          new DownloadRequest.Builder(/* id= */ "contentId", Uri.EMPTY)
              .setMimeType(MimeTypes.APPLICATION_SS)
              .build());
    } finally {
      cacheDataSourceFactory.getCache().release();
    }
  }

  /** Creates a DASH media source with {@link DefaultMediaSourceFactory}. */
  public static void createDashMediaSourceWithDefaultMediaSourceFactory(Context context) {
    DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(context);
    mediaSourceFactory.createMediaSource(
        new MediaItem.Builder().setUri(Uri.EMPTY).setMimeType(MimeTypes.APPLICATION_MPD).build());
  }

  /** Creates an HLS media source with {@link DefaultMediaSourceFactory}. */
  public static void createHlsMediaSourceWithDefaultMediaSourceFactory(Context context) {
    DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(context);
    mediaSourceFactory.createMediaSource(
        new MediaItem.Builder().setUri(Uri.EMPTY).setMimeType(MimeTypes.APPLICATION_M3U8).build());
  }

  /** Creates a SmoothStreaming media source with {@link DefaultMediaSourceFactory}. */
  public static void createSsMediaSourceWithDefaultMediaSourceFactory(Context context) {
    DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(context);
    mediaSourceFactory.createMediaSource(
        new MediaItem.Builder().setUri(Uri.EMPTY).setMimeType(MimeTypes.APPLICATION_SS).build());
  }

  /** Creates an RTSP media source. */
  public static void createRtspMediaSourceWithDefaultMediaSourceFactory(Context context) {
    DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(context);
    mediaSourceFactory.createMediaSource(
        new MediaItem.Builder().setUri(Uri.EMPTY).setMimeType(MimeTypes.APPLICATION_RTSP).build());
  }

  /**
   * Creates a {@link PlaybackVideoGraphWrapper} then {@linkplain VideoSink#initialize(Format)
   * initializes} the sink in order to test reflective access in {@link PlaybackVideoGraphWrapper}.
   */
  public static void setVideoEffectsAndCheckReflectiveAccess(Context context)
      throws VideoSink.VideoSinkException {
    ExoPlayer player = new ExoPlayer.Builder(context).build();
    player.setVideoEffects(VideoFrameProcessor.REDRAW);
    player.release();
    VideoFrameReleaseControl.FrameTimingEvaluator frameTimingEvaluator =
        new VideoFrameReleaseControl.FrameTimingEvaluator() {
          @Override
          public boolean shouldForceReleaseFrame(long earlyUs, long elapsedSinceLastReleaseUs) {
            return false;
          }

          @Override
          public boolean shouldDropFrame(
              long earlyUs, long elapsedRealtimeUs, boolean isLastFrame) {
            return false;
          }

          @Override
          public boolean shouldIgnoreFrame(
              long earlyUs,
              long positionUs,
              long elapsedRealtimeUs,
              boolean isLastFrame,
              boolean treatDroppedBuffersAsSkipped) {
            return false;
          }
        };
    VideoFrameReleaseControl videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            context,
            frameTimingEvaluator,
            /* allowedJoiningTimeMs= */ 0,
            /* skipBuffersWithIdenticalReleaseTime= */ true);
    PlaybackVideoGraphWrapper playbackVideoGraphWrapper =
        new PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl).build();
    playbackVideoGraphWrapper.getSink(/* inputIndex= */ 0).initialize(new Format.Builder().build());
  }

  private static void initializeExtensionCodecViaDefaultRenderersFactory(
      Context context, Class<? extends Renderer> rendererClass, Format format) throws Exception {
    DefaultRenderersFactory renderersFactory =
        new DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setMediaCodecSelector(
                (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> ImmutableList.of());
    Renderer[] renderers =
        renderersFactory.createRenderers(
            Util.createHandlerForCurrentOrMainLooper(),
            new VideoRendererEventListener() {},
            new AudioRendererEventListener() {},
            /* textRendererOutput= */ cues -> {},
            /* metadataRendererOutput= */ metadata -> {});
    try {
      for (Renderer renderer : renderers) {
        if (!rendererClass.isInstance(renderer)) {
          continue;
        }
        AtomicBoolean formatRead = new AtomicBoolean();
        AtomicBoolean sampleBufferRequested = new AtomicBoolean();
        try {
          renderer.init(/* index= */ 0, PlayerId.UNSET, Clock.DEFAULT);
          checkState(
              RendererCapabilities.getFormatSupport(
                      renderer.getCapabilities().supportsFormat(format))
                  == C.FORMAT_HANDLED);
          SampleStream formatOnlySampleStream =
              new SampleStream() {
                @Override
                public boolean isReady() {
                  return true;
                }

                @Override
                public void maybeThrowError() {}

                @Override
                public int readData(
                    FormatHolder formatHolder,
                    DecoderInputBuffer buffer,
                    @ReadFlags int readFlags) {
                  if (!formatRead.getAndSet(true)) {
                    formatHolder.format = format;
                    return C.RESULT_FORMAT_READ;
                  }
                  if ((readFlags & FLAG_OMIT_SAMPLE_DATA) == 0) {
                    sampleBufferRequested.set(true);
                  }
                  return C.RESULT_NOTHING_READ;
                }

                @Override
                public int skipData(long positionUs) {
                  return 0;
                }
              };
          renderer.enable(
              RendererConfiguration.DEFAULT,
              new Format[] {format},
              formatOnlySampleStream,
              /* positionUs= */ 0,
              /* joining= */ false,
              /* mayRenderStartOfStream= */ true,
              /* startPositionUs= */ 0,
              /* offsetUs= */ 0,
              new MediaSource.MediaPeriodId(new Object()));
          renderer.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
        } finally {
          if (renderer.getState() == Renderer.STATE_ENABLED) {
            renderer.disable();
          }
          renderer.reset();
        }
        checkState(formatRead.get() && sampleBufferRequested.get());
        return;
      }
      throw new IllegalStateException();
    } finally {
      for (Renderer renderer : renderers) {
        renderer.release();
      }
    }
  }

  private static Renderer[] createDefaultRenderersFactoryRenderers(Context context) {
    DefaultRenderersFactory renderersFactory =
        new DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
    return renderersFactory.createRenderers(
        Util.createHandlerForCurrentOrMainLooper(),
        new VideoRendererEventListener() {},
        new AudioRendererEventListener() {},
        /* textRendererOutput= */ cues -> {},
        /* metadataRendererOutput= */ metadata -> {});
  }

  private static CacheDataSource.Factory createTestCacheDataSourceFactory(Context context) {
    CacheDataSource.Factory cacheDataSourceFactory = new CacheDataSource.Factory();
    Cache cache =
        new SimpleCache(
            context.getCacheDir(),
            new NoOpCacheEvictor(),
            new DefaultDatabaseProvider(
                new SQLiteOpenHelper(
                    /* context= */ null, /* name= */ null, /* factory= */ null, /* version= */ 1) {
                  @Override
                  public void onCreate(SQLiteDatabase db) {
                    // Do nothing.
                  }

                  @Override
                  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                    // Do nothing.
                  }
                }));
    cacheDataSourceFactory.setCache(cache);
    return cacheDataSourceFactory;
  }
}
