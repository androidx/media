/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.transformer;

import static androidx.media3.common.util.Util.isRunningOnEmulator;
import static androidx.media3.transformer.AndroidTestUtil.JPG_SINGLE_PIXEL_ASSET;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static org.junit.Assert.assertThrows;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.util.Pair;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.SystemClock;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.AssetDataSource;
import androidx.media3.datasource.DataSourceUtil;
import androidx.media3.datasource.DataSpec;
import androidx.media3.effect.DefaultVideoFrameProcessor;
import androidx.media3.effect.SingleInputVideoGraph;
import androidx.media3.exoplayer.image.ExternallyLoadedImageDecoder;
import androidx.media3.exoplayer.image.ExternallyLoadedImageDecoder.ExternalImageRequest;
import androidx.media3.exoplayer.image.ImageDecoderException;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.ExternalLoader;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.test.utils.TestSpeedProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link CompositionPlayer} */
@RunWith(AndroidJUnit4.class)
public class CompositionPlayerTest {

  private static final long TEST_TIMEOUT_MS = isRunningOnEmulator() ? 20_000 : 10_000;

  @Rule
  public ActivityScenarioRule<SurfaceTestActivity> rule =
      new ActivityScenarioRule<>(SurfaceTestActivity.class);

  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context applicationContext = instrumentation.getContext().getApplicationContext();

  private CompositionPlayer compositionPlayer;
  private SurfaceView surfaceView;
  private SurfaceHolder surfaceHolder;
  private TextureView textureView;

  @Before
  public void setupSurfaces() {
    rule.getScenario()
        .onActivity(
            activity -> {
              surfaceView = activity.getSurfaceView();
              textureView = activity.getTextureView();
            });
    surfaceHolder = surfaceView.getHolder();
  }

  @After
  public void closeActivity() {
    rule.getScenario().close();
  }

  @After
  public void releasePlayer() {
    instrumentation.runOnMainSync(
        () -> {
          if (compositionPlayer != null) {
            compositionPlayer.release();
          }
        });
  }

  @Test
  public void setVideoSurfaceView_beforeSettingComposition_surfaceViewIsPassed() throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(applicationContext).build();
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(
              new Composition.Builder(
                      new EditedMediaItemSequence.Builder(
                              new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
                                  .setDurationUs(MP4_ASSET.videoDurationUs)
                                  .build())
                          .build())
                  .build());
          compositionPlayer.prepare();
        });

    listener.waitUntilFirstFrameRendered();
  }

  @Test
  public void setVideoSurfaceView_afterSettingComposition_surfaceViewIsPassed() throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(applicationContext).build();
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(
              new Composition.Builder(
                      new EditedMediaItemSequence.Builder(
                              new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
                                  .setDurationUs(MP4_ASSET.videoDurationUs)
                                  .build())
                          .build())
                  .build());
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.prepare();
        });

    listener.waitUntilFirstFrameRendered();
  }

  @Test
  public void setVideoSurfaceHolder_beforeSettingComposition_surfaceHolderIsPassed()
      throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(applicationContext).build();
          compositionPlayer.setVideoSurfaceHolder(surfaceHolder);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(
              new Composition.Builder(
                      new EditedMediaItemSequence.Builder(
                              new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
                                  .setDurationUs(MP4_ASSET.videoDurationUs)
                                  .build())
                          .build())
                  .build());
          compositionPlayer.prepare();
        });

    listener.waitUntilFirstFrameRendered();
  }

  @Test
  public void setVideoSurfaceHolder_afterSettingComposition_surfaceHolderIsPassed()
      throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(applicationContext).build();
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(
              new Composition.Builder(
                      new EditedMediaItemSequence.Builder(
                              new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
                                  .setDurationUs(MP4_ASSET.videoDurationUs)
                                  .build())
                          .build())
                  .build());
          compositionPlayer.setVideoSurfaceHolder(surfaceHolder);
          compositionPlayer.prepare();
        });

    listener.waitUntilFirstFrameRendered();
  }

  @Test
  public void setVideoTextureView_throws() {
    AtomicReference<UnsupportedOperationException> exception = new AtomicReference<>();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(applicationContext).build();
          try {
            compositionPlayer.setVideoTextureView(textureView);
          } catch (UnsupportedOperationException e) {
            exception.set(e);
          }
        });

    assertThat(exception.get()).isNotNull();
  }

  @Test
  public void imagePreview_imagePlaysForSetDuration() throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(applicationContext).build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(
              new Composition.Builder(
                      new EditedMediaItemSequence.Builder(
                              new EditedMediaItem.Builder(
                                      new MediaItem.Builder()
                                          .setUri(JPG_SINGLE_PIXEL_ASSET.uri)
                                          .setImageDurationMs(1_000)
                                          .build())
                                  .setFrameRate(30)
                                  .build())
                          .build())
                  .build());
          compositionPlayer.prepare();
        });

    listener.waitUntilFirstFrameRendered();
    listener.waitUntilPlayerReady();
    long playbackStartTimeMs = SystemClock.DEFAULT.elapsedRealtime();
    instrumentation.runOnMainSync(() -> compositionPlayer.play());
    listener.waitUntilPlayerEnded();
    long playbackRealTimeMs = SystemClock.DEFAULT.elapsedRealtime() - playbackStartTimeMs;

    // Video frames are not rendered exactly at the time corresponding to their presentation
    // timestamp, and the differences accumulate.
    assertThat(playbackRealTimeMs).isAtLeast(900);
  }

  @Test
  public void imagePreview_externallyLoadedImage() throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    ExternalLoader externalImageLoader =
        loadRequest -> immediateFuture(Util.getUtf8Bytes(loadRequest.uri.toString()));
    MediaSource.Factory mediaSourceFactory =
        new DefaultMediaSourceFactory(applicationContext)
            .setExternalImageLoader(externalImageLoader);

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer =
              new CompositionPlayer.Builder(applicationContext)
                  .setMediaSourceFactory(mediaSourceFactory)
                  .setImageDecoderFactory(
                      new ExternallyLoadedImageDecoder.Factory(
                          new TestExternallyLoadedBitmapResolver()))
                  .build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(
              new Composition.Builder(
                      new EditedMediaItemSequence.Builder(
                              new EditedMediaItem.Builder(
                                      new MediaItem.Builder()
                                          .setUri(JPG_SINGLE_PIXEL_ASSET.uri)
                                          .setMimeType(
                                              MimeTypes.APPLICATION_EXTERNALLY_LOADED_IMAGE)
                                          .setImageDurationMs(1_000)
                                          .build())
                                  .build())
                          .build())
                  .build());
          compositionPlayer.prepare();
        });

    listener.waitUntilFirstFrameRendered();
  }

  @Test
  public void videoPreview_withSpeedUp_playerEnds() throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    Pair<AudioProcessor, Effect> effects =
        Effects.createExperimentalSpeedChangingEffect(
            TestSpeedProvider.createWithStartTimes(new long[] {0}, new float[] {2f}));
    EditedMediaItem video =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setDurationUs(MP4_ASSET.videoDurationUs)
            .setEffects(
                new Effects(ImmutableList.of(effects.first), ImmutableList.of(effects.second)))
            .build();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(applicationContext).build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(
              new Composition.Builder(new EditedMediaItemSequence.Builder(video).build()).build());
          compositionPlayer.prepare();
          compositionPlayer.play();
        });

    listener.waitUntilPlayerEnded();
  }

  @Test
  public void videoPreview_withSlowDown_playerEnds() throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    Pair<AudioProcessor, Effect> effects =
        Effects.createExperimentalSpeedChangingEffect(
            TestSpeedProvider.createWithStartTimes(new long[] {0}, new float[] {0.5f}));
    EditedMediaItem video =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setDurationUs(MP4_ASSET.videoDurationUs)
            .setEffects(
                new Effects(ImmutableList.of(effects.first), ImmutableList.of(effects.second)))
            .build();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(applicationContext).build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(
              new Composition.Builder(new EditedMediaItemSequence.Builder(video).build()).build());
          compositionPlayer.prepare();
          compositionPlayer.play();
        });

    listener.waitUntilPlayerEnded();
  }

  @Test
  public void setGlObjectsProvider_withFailingImplementation_throws() {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    EditedMediaItem video =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setDurationUs(MP4_ASSET.videoDurationUs)
            .build();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer =
              new CompositionPlayer.Builder(applicationContext)
                  .setGlObjectsProvider(
                      new GlObjectsProvider() {
                        @Override
                        public EGLContext createEglContext(
                            EGLDisplay eglDisplay, int openGlVersion, int[] configAttributes) {
                          throw new UnsupportedOperationException();
                        }

                        @Override
                        public EGLSurface createEglSurface(
                            EGLDisplay eglDisplay,
                            Object surface,
                            @C.ColorTransfer int colorTransfer,
                            boolean isEncoderInputSurface) {
                          throw new UnsupportedOperationException();
                        }

                        @Override
                        public EGLSurface createFocusedPlaceholderEglSurface(
                            EGLContext eglContext, EGLDisplay eglDisplay) {
                          throw new UnsupportedOperationException();
                        }

                        @Override
                        public GlTextureInfo createBuffersForTexture(
                            int texId, int width, int height) {
                          throw new UnsupportedOperationException();
                        }

                        @Override
                        public void release(EGLDisplay eglDisplay) {
                          throw new UnsupportedOperationException();
                        }
                      })
                  .build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(
              new Composition.Builder(new EditedMediaItemSequence.Builder(video).build()).build());
          compositionPlayer.prepare();
          compositionPlayer.play();
        });

    assertThrows(PlaybackException.class, listener::waitUntilPlayerEnded);
  }

  @Test
  public void release_videoGraphWrapperFailsDuringRelease_playerDoesNotRaiseError()
      throws Exception {
    PlayerTestListener playerTestListener = new PlayerTestListener(TEST_TIMEOUT_MS);
    EditedMediaItem video =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setDurationUs(MP4_ASSET.videoDurationUs)
            .build();
    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer =
              new CompositionPlayer.Builder(applicationContext)
                  .setVideoGraphFactory(
                      new VideoGraph.Factory() {
                        @Override
                        public VideoGraph create(
                            Context context,
                            ColorInfo outputColorInfo,
                            DebugViewProvider debugViewProvider,
                            VideoGraph.Listener listener,
                            Executor listenerExecutor,
                            long initialTimestampOffsetUs,
                            boolean renderFramesAutomatically) {
                          return new FailingReleaseVideoGraph(
                              context,
                              outputColorInfo,
                              debugViewProvider,
                              listener,
                              listenerExecutor,
                              renderFramesAutomatically);
                        }

                        @Override
                        public boolean supportsMultipleInputs() {
                          return false;
                        }
                      })
                  .build();
          compositionPlayer.addListener(playerTestListener);
          compositionPlayer.setComposition(
              new Composition.Builder(new EditedMediaItemSequence.Builder(video).build()).build());
          compositionPlayer.prepare();
          compositionPlayer.play();
        });
    // Wait until the player is ended to make sure the VideoGraph has been created.
    playerTestListener.waitUntilPlayerEnded();

    instrumentation.runOnMainSync(compositionPlayer::release);
  }

  private static final class TestExternallyLoadedBitmapResolver
      implements ExternallyLoadedImageDecoder.BitmapResolver {
    @Override
    public ListenableFuture<Bitmap> resolve(ExternalImageRequest request) {
      try {
        // The test serializes the image URI string to a byte array.
        AssetDataSource assetDataSource =
            new AssetDataSource(ApplicationProvider.getApplicationContext());
        assetDataSource.open(new DataSpec.Builder().setUri(request.uri).build());
        byte[] imageData = DataSourceUtil.readToEnd(assetDataSource);
        return immediateFuture(BitmapFactory.decodeByteArray(imageData, 0, imageData.length));
      } catch (IOException e) {
        return immediateFailedFuture(new ImageDecoderException(e));
      }
    }
  }

  private static final class FailingReleaseVideoGraph extends SingleInputVideoGraph {
    public FailingReleaseVideoGraph(
        Context context,
        ColorInfo outputColorInfo,
        DebugViewProvider debugViewProvider,
        Listener listener,
        Executor listenerExecutor,
        boolean renderFramesAutomatically) {
      super(
          context,
          new DefaultVideoFrameProcessor.Factory.Builder().build(),
          outputColorInfo,
          listener,
          debugViewProvider,
          listenerExecutor,
          renderFramesAutomatically);
    }

    @Override
    public void release() {
      super.release();
      throw new RuntimeException("VideoGraph release error");
    }
  }
}
