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
import static androidx.media3.test.utils.AssetInfo.JPG_SINGLE_PIXEL_ASSET;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.SystemClock;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.AssetDataSource;
import androidx.media3.datasource.DataSourceUtil;
import androidx.media3.datasource.DataSpec;
import androidx.media3.effect.DefaultVideoFrameProcessor;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import androidx.media3.effect.PassthroughShaderProgram;
import androidx.media3.effect.SingleInputVideoGraph;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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
  public void videoSurfaceHolderDestroyed_releaseBlocksMainThread() throws Exception {
    // Note this method tests that the main thread is blocked via a customized VideoGraph, but it
    // doesn't check that the main thread is blocked until all operations involved in clearing the
    // surface are done.
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    ConditionVariable surfaceClearStarted = new ConditionVariable();
    ConditionVariable allowSurfaceClearToComplete = new ConditionVariable();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer =
              new CompositionPlayer.Builder(applicationContext)
                  .setVideoGraphFactory(
                      /* videoGraphFactory= */ new VideoGraph.Factory() {
                        @Override
                        public VideoGraph create(
                            Context context,
                            ColorInfo outputColorInfo,
                            DebugViewProvider debugViewProvider,
                            VideoGraph.Listener listener,
                            Executor listenerExecutor,
                            long initialTimestampOffsetUs,
                            boolean renderFramesAutomatically) {
                          return new SingleInputVideoGraph(
                              applicationContext,
                              new DefaultVideoFrameProcessor.Factory.Builder().build(),
                              ColorInfo.SDR_BT709_LIMITED,
                              new VideoGraph.Listener() {},
                              DebugViewProvider.NONE,
                              directExecutor(),
                              /* renderFramesAutomatically= */ true) {

                            @Override
                            public void setOutputSurfaceInfo(
                                @Nullable SurfaceInfo outputSurfaceInfo) {
                              if (outputSurfaceInfo == null) {
                                // Signal that the surface clearing has started on the playback
                                // thread.
                                surfaceClearStarted.open();
                                // Wait for the test thread to allow completion.
                                boolean allowSurfaceClearToCompleteOpened = false;
                                try {
                                  allowSurfaceClearToCompleteOpened =
                                      allowSurfaceClearToComplete.block(TEST_TIMEOUT_MS);
                                } catch (InterruptedException e) {
                                  Thread.currentThread().interrupt();
                                }
                                assertThat(allowSurfaceClearToCompleteOpened).isTrue();
                              }
                              super.setOutputSurfaceInfo(outputSurfaceInfo);
                            }
                          };
                        }

                        @Override
                        public boolean supportsMultipleInputs() {
                          return false;
                        }
                      })
                  .build();
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

    listener.waitUntilPlayerReady();

    ConditionVariable mainThreadBlocked = new ConditionVariable();
    Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    instrumentation.runOnMainSync(
        () -> {
          // Triggers surfaceDestroyed to run. Note this method returns after
          // SurfaceHolder.surfaceDestroyed returns
          mainThreadHandler.post(() -> surfaceView.setVisibility(View.GONE));
          // If the main thread is not blocked when clearing the surface, this line would run
          // immediately, and the assertion on the ConditionalVariable would block.
          mainThreadHandler.post(mainThreadBlocked::open);
        });

    // Wait until clearing the surface begins.
    assertThat(surfaceClearStarted.block(TEST_TIMEOUT_MS)).isTrue();
    // This should be still in the closed state because the VideoGraph has not released the surface.
    assertThat(mainThreadBlocked.isOpen()).isFalse();
    // Continue releasing, this should eventually unblock the main thread.
    allowSurfaceClearToComplete.open();
    // Asserts that the main thread is opened after clearing the surface.
    assertThat(mainThreadBlocked.block(TEST_TIMEOUT_MS)).isTrue();
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

  @Test
  public void release_videoGraphWrapperBlockedDuringRelease_doesNotBlockCallingThreadIndefinitely()
      throws Exception {
    PlayerTestListener playerTestListener = new PlayerTestListener(TEST_TIMEOUT_MS);
    EditedMediaItem video =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setDurationUs(MP4_ASSET.videoDurationUs)
            .build();
    CountDownLatch blockingLatch = new CountDownLatch(1);
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
                          return new BlockingReleaseVideoGraph(
                              context,
                              outputColorInfo,
                              debugViewProvider,
                              listener,
                              listenerExecutor,
                              renderFramesAutomatically,
                              blockingLatch);
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
    assertThat(playerTestListener.getException()).isNotNull();
  }

  @Test
  public void setGlExecutorService_runsEffectsPipelineOnExecutorService()
      throws PlaybackException, TimeoutException, InterruptedException {
    AtomicReference<Thread> testGlThread = new AtomicReference<>();
    CountDownLatch frameQueuedLatch = new CountDownLatch(1);
    ExecutorService executorService =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              testGlThread.set(new Thread(runnable, /* threadName= */ "TestThread"));
              return testGlThread.get();
            });
    GlShaderProgram threadCheckinghaderProgram =
        new PassthroughShaderProgram() {
          @Override
          public void queueInputFrame(
              GlObjectsProvider glObjectsProvider,
              GlTextureInfo inputTexture,
              long presentationTimeUs) {
            assertThat(Thread.currentThread()).isEqualTo(testGlThread.get());
            frameQueuedLatch.countDown();
            super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs);
          }
        };
    ImmutableList<Effect> effects =
        ImmutableList.of((GlEffect) (context, useHdr) -> threadCheckinghaderProgram);

    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer =
              new CompositionPlayer.Builder(applicationContext)
                  .setGlThreadExecutorService(executorService)
                  .build();
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(listener);
          compositionPlayer.setComposition(
              new Composition.Builder(
                      new EditedMediaItemSequence.Builder(
                              new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
                                  .setDurationUs(MP4_ASSET.videoDurationUs)
                                  .setEffects(
                                      new Effects(
                                          /* audioProcessors= */ ImmutableList.of(), effects))
                                  .build())
                          .build())
                  .build());
          compositionPlayer.prepare();
        });

    listener.waitUntilFirstFrameRendered();
    assertThat(frameQueuedLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
  }

  @Test
  public void videoPlayback_dropsFrames() throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    AtomicInteger droppedFramesCounter = new AtomicInteger();
    ConditionVariable playerReleased = new ConditionVariable();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer =
              new CompositionPlayer.Builder(applicationContext)
                  .setAudioSink(new AudioSinkWithJumpingPosition(applicationContext))
                  .build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(listener);
          compositionPlayer.addAnalyticsListener(
              new AnalyticsListener() {
                @Override
                public void onDroppedVideoFrames(
                    EventTime eventTime, int droppedFrames, long elapsedMs) {
                  droppedFramesCounter.getAndAdd(droppedFrames);
                }

                @Override
                public void onPlayerReleased(EventTime eventTime) {
                  playerReleased.open();
                }
              });
          compositionPlayer.setComposition(
              new Composition.Builder(
                      EditedMediaItemSequence.withVideoFrom(
                          ImmutableList.of(
                              new EditedMediaItem.Builder(
                                      MediaItem.fromUri(
                                          MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.uri))
                                  .setDurationUs(
                                      MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S
                                          .videoDurationUs)
                                  .build())))
                  .build());
          compositionPlayer.prepare();
          compositionPlayer.play();
        });
    listener.waitUntilPlayerEnded();
    // Wait until the player is released so that all AnalyticsListener events are received.
    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer.release();
          compositionPlayer = null;
        });
    playerReleased.block(TEST_TIMEOUT_MS);

    assertThat(droppedFramesCounter.get()).isGreaterThan(0);
  }

  @Test
  public void videoPlayback_withoutLateThresholdToDropInputUs_dropsFrames() throws Exception {
    PlayerTestListener listener = new PlayerTestListener(TEST_TIMEOUT_MS);
    AtomicInteger droppedFramesCounter = new AtomicInteger();
    ConditionVariable playerReleased = new ConditionVariable();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer =
              new CompositionPlayer.Builder(applicationContext)
                  .experimentalSetLateThresholdToDropInputUs(C.TIME_UNSET)
                  .setAudioSink(new AudioSinkWithJumpingPosition(applicationContext))
                  .build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(listener);
          compositionPlayer.addAnalyticsListener(
              new AnalyticsListener() {
                @Override
                public void onDroppedVideoFrames(
                    EventTime eventTime, int droppedFrames, long elapsedMs) {
                  droppedFramesCounter.getAndAdd(droppedFrames);
                }

                @Override
                public void onPlayerReleased(EventTime eventTime) {
                  playerReleased.open();
                }
              });
          compositionPlayer.setComposition(
              new Composition.Builder(
                      EditedMediaItemSequence.withVideoFrom(
                          ImmutableList.of(
                              new EditedMediaItem.Builder(
                                      MediaItem.fromUri(
                                          MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.uri))
                                  .setDurationUs(
                                      MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S
                                          .videoDurationUs)
                                  .build())))
                  .build());
          compositionPlayer.prepare();
          compositionPlayer.play();
        });
    listener.waitUntilPlayerEnded();
    // Wait until the player is released so that all AnalyticsListener events are received.
    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer.release();
          compositionPlayer = null;
        });
    playerReleased.block(TEST_TIMEOUT_MS);

    assertThat(droppedFramesCounter.get()).isGreaterThan(0);
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

  private static final class BlockingReleaseVideoGraph extends SingleInputVideoGraph {

    private final CountDownLatch blockingLatch;

    public BlockingReleaseVideoGraph(
        Context context,
        ColorInfo outputColorInfo,
        DebugViewProvider debugViewProvider,
        Listener listener,
        Executor listenerExecutor,
        boolean renderFramesAutomatically,
        CountDownLatch blockingLatch) {
      super(
          context,
          new DefaultVideoFrameProcessor.Factory.Builder().build(),
          outputColorInfo,
          listener,
          debugViewProvider,
          listenerExecutor,
          renderFramesAutomatically);
      this.blockingLatch = blockingLatch;
    }

    @Override
    public void release() {
      super.release();
      try {
        blockingLatch.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * A {@link ForwardingAudioSink} to {@link DefaultAudioSink}, with position that jumps forward
   * periodically in order to force dropped video frames.
   */
  private static class AudioSinkWithJumpingPosition extends ForwardingAudioSink {
    private static final long POSITION_JUMP_INTERVAL_US = 1_000_000;
    private static final long POSITION_JUMP_AMOUNT_US = 300_000;

    long nextJumpUs = C.TIME_UNSET;
    long offsetUs = 0;

    AudioSinkWithJumpingPosition(Context context) {
      super(new DefaultAudioSink.Builder(context).build());
    }

    @Override
    public long getCurrentPositionUs(boolean sourceEnded) {
      long superPositionUs = super.getCurrentPositionUs(sourceEnded);
      if (superPositionUs == CURRENT_POSITION_NOT_SET) {
        return superPositionUs;
      }
      if (nextJumpUs == C.TIME_UNSET || superPositionUs >= nextJumpUs) {
        nextJumpUs = superPositionUs + POSITION_JUMP_INTERVAL_US;
        offsetUs += POSITION_JUMP_AMOUNT_US;
      }
      return superPositionUs + offsetUs;
    }
  }
}
