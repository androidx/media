/*
 * Copyright 2026 The Android Open Source Project
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

import static androidx.media3.common.Player.STATE_ENDED;
import static androidx.media3.common.util.Util.isRunningOnEmulator;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S;
import static androidx.media3.test.utils.PlayerFence.futureWhen;
import static androidx.media3.transformer.GlFrameProcessorTestUtil.closeTestingGlResources;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.app.Instrumentation;
import android.content.Context;
import android.view.SurfaceView;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Util;
import androidx.media3.effect.DefaultGlFrameProcessor;
import androidx.media3.effect.DefaultGlObjectsProvider;
import androidx.media3.effect.FrameProcessorUtils;
import androidx.media3.effect.ndk.HardwareBufferJni;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link CompositionPlayer} using {@link DefaultGlFrameProcessor}. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 28)
public final class CompositionPlayerDefaultGlFrameProcessorTest {

  private static final long TEST_TIMEOUT_MS = isRunningOnEmulator() ? 20_000 : 10_000;

  private static final EditedMediaItem MP4_EDITED_MEDIA_ITEM_10_MS =
      new EditedMediaItem.Builder(
              new MediaItem.Builder()
                  .setUri(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.uri)
                  .setClippingConfiguration(
                      new MediaItem.ClippingConfiguration.Builder().setEndPositionMs(10).build())
                  .build())
          .setDurationUs(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.videoDurationUs)
          .build();
  private static final Composition SINGLE_ITEM_COMPOSITION =
      new Composition.Builder(
              EditedMediaItemSequence.withVideoFrom(ImmutableList.of(MP4_EDITED_MEDIA_ITEM_10_MS)))
          .build();

  @Rule
  public ActivityScenarioRule<SurfaceTestActivity> rule =
      new ActivityScenarioRule<>(SurfaceTestActivity.class);

  @Rule public final TestName testName = new TestName();

  private final Instrumentation instrumentation;
  private final Context context;

  private @MonotonicNonNull CompositionPlayer compositionPlayer;
  private @MonotonicNonNull SurfaceView surfaceView;
  private @MonotonicNonNull ListeningExecutorService glExecutorService;
  private @MonotonicNonNull GlObjectsProvider glObjectsProvider;

  public CompositionPlayerDefaultGlFrameProcessorTest() {
    instrumentation = InstrumentationRegistry.getInstrumentation();
    context = ApplicationProvider.getApplicationContext();
  }

  @Before
  public void setupSurfacesAndExecutor() throws Exception {
    rule.getScenario()
        .onActivity(
            activity -> {
              surfaceView = activity.getSurfaceView();
            });
    glExecutorService =
        MoreExecutors.listeningDecorator(Util.newSingleThreadExecutor("CompositionTest:GL"));
    glObjectsProvider = new DefaultGlObjectsProvider();
    glExecutorService
        .submit(
            () -> {
              try {
                FrameProcessorUtils.setupOpenGl(glObjectsProvider);
              } catch (GlUtil.GlException e) {
                throw new AssertionError(e);
              }
            })
        .get(TEST_TIMEOUT_MS, MILLISECONDS);
  }

  @After
  public void releaseResources() {
    instrumentation.runOnMainSync(
        () -> {
          if (compositionPlayer != null) {
            compositionPlayer.release();
          }
        });
    rule.getScenario().close();
    @Nullable Exception releasingException = null;
    if (glExecutorService != null) {
      releasingException =
          closeTestingGlResources(glExecutorService, glObjectsProvider, TEST_TIMEOUT_MS);
      glExecutorService.shutdown();
    }
    if (releasingException != null) {
      throw new AssertionError(releasingException);
    }
  }

  @Test
  public void compositionPlayer_withDefaultGlFrameProcessor_outputsFrameBeforeEnding()
      throws Exception {
    SettableFuture<Void> endedFuture = SettableFuture.create();
    Queue<Long> videoTimestamps = new ConcurrentLinkedQueue<>();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = createCompositionPlayer();
          endedFuture.setFuture(
              futureWhen(compositionPlayer).entersPlaybackState(Player.STATE_ENDED));
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.setVideoFrameMetadataListener(
              (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {
                videoTimestamps.add(presentationTimeUs);
              });
          compositionPlayer.addListener(
              new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                  if (playbackState == STATE_ENDED) {
                    videoTimestamps.add(-1L);
                  }
                }
              });
          compositionPlayer.setComposition(SINGLE_ITEM_COMPOSITION);
          compositionPlayer.prepare();
          compositionPlayer.play();
        });
    endedFuture.get(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(videoTimestamps).containsExactly(0L, -1L).inOrder();
  }

  @Test
  public void compositionPlayer_playAfterEnded_doesNotTimeout() throws Exception {
    SettableFuture<Void> endedFuture = SettableFuture.create();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = createCompositionPlayer();
          compositionPlayer.setVideoSurfaceView(surfaceView);
          endedFuture.setFuture(
              futureWhen(compositionPlayer).entersPlaybackState(Player.STATE_ENDED));
          compositionPlayer.setComposition(SINGLE_ITEM_COMPOSITION);
          compositionPlayer.prepare();
          compositionPlayer.play();
        });
    endedFuture.get(TEST_TIMEOUT_MS, MILLISECONDS);

    SettableFuture<Void> endedAfterPlayFuture = SettableFuture.create();
    instrumentation.runOnMainSync(
        () -> {
          endedAfterPlayFuture.setFuture(
              futureWhen(compositionPlayer).entersPlaybackState(Player.STATE_ENDED));
          compositionPlayer.play();
        });
    endedAfterPlayFuture.get(TEST_TIMEOUT_MS, MILLISECONDS);
  }

  private CompositionPlayer createCompositionPlayer() {
    DefaultGlFrameProcessor.Factory frameProcessorFactory =
        new DefaultGlFrameProcessor.Factory(
            context, glObjectsProvider, HardwareBufferJni.INSTANCE, glExecutorService);
    return new CompositionPlayer.Builder(context)
        .setNativeHardwareBufferHelpers(HardwareBufferJni.INSTANCE)
        .setFrameProcessorFactory(frameProcessorFactory)
        // Disable frame dropping.
        .experimentalSetLateThresholdToDropInputUs(C.TIME_UNSET)
        .build();
  }
}
