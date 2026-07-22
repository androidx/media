/*
 * Copyright 2025 The Android Open Source Project
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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.util.Util.isRunningOnEmulator;
import static androidx.media3.test.utils.CompositionAssetInfo.MULTI_SEQUENCE_CONFIGS;
import static androidx.media3.test.utils.CompositionAssetInfo.MULTI_SEQUENCE_VIDEO_CONFIGS;
import static androidx.media3.test.utils.CompositionAssetInfo.SINGLE_SEQUENCE_CONFIGS;
import static androidx.media3.test.utils.EditedMediaItemAssetInfo.VIDEO_ONLY_CLIPPED_HALF_SPEED;
import static androidx.media3.test.utils.EditedMediaItemAssetInfo.VIDEO_ONLY_CLIPPED_TWICE_SPEED;
import static androidx.media3.transformer.GlFrameProcessorTestUtil.closeTestingGlResources;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.TruthJUnit.assume;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.view.SurfaceView;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.Player;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Util;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.effect.DefaultGlFrameProcessor;
import androidx.media3.effect.DefaultGlObjectsProvider;
import androidx.media3.effect.FrameProcessorUtils;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.MultipleInputVideoGraph;
import androidx.media3.effect.SingleInputVideoGraph;
import androidx.media3.effect.ndk.HardwareBufferJni;
import androidx.media3.test.utils.CapturingFrameProcessor;
import androidx.media3.test.utils.CompositionAssetInfo;
import androidx.media3.test.utils.FrameProcessorTestUtil;
import androidx.media3.test.utils.PlayerFence;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Parameterized playback tests for {@link CompositionPlayer}. */
@RunWith(TestParameterInjector.class)
public class CompositionPlayerParameterizedPlaybackTest {

  private static final long TEST_TIMEOUT_MS = isRunningOnEmulator() ? 30_000 : 20_000;

  @Rule
  public ActivityScenarioRule<SurfaceTestActivity> rule =
      new ActivityScenarioRule<>(SurfaceTestActivity.class);

  private final Context context = getInstrumentation().getContext().getApplicationContext();

  private @MonotonicNonNull CompositionPlayer player;
  private @MonotonicNonNull SurfaceView surfaceView;
  private @MonotonicNonNull ListeningExecutorService glExecutorService;
  private @MonotonicNonNull GlObjectsProvider glObjectsProvider;

  private static class SingleInputVideoGraphConfigsProvider extends TestParameterValuesProvider {
    @Override
    protected List<CompositionAssetInfo> provideValues(
        TestParameterValuesProvider.Context context) {
      return SINGLE_SEQUENCE_CONFIGS;
    }
  }

  private static class MultipleInputVideoGraphConfigsProvider extends TestParameterValuesProvider {
    @Override
    protected List<CompositionAssetInfo> provideValues(
        TestParameterValuesProvider.Context context) {
      return new ImmutableList.Builder<CompositionAssetInfo>()
          .addAll(SINGLE_SEQUENCE_CONFIGS)
          .addAll(MULTI_SEQUENCE_CONFIGS)
          .build();
    }
  }

  private static class FrameConsumerConfigsProvider extends TestParameterValuesProvider {
    @Override
    protected List<CompositionAssetInfo> provideValues(
        TestParameterValuesProvider.Context context) {
      ImmutableList<CompositionAssetInfo> allConfigs =
          new ImmutableList.Builder<CompositionAssetInfo>()
              .addAll(SINGLE_SEQUENCE_CONFIGS)
              .addAll(MULTI_SEQUENCE_VIDEO_CONFIGS)
              .addAll(MULTI_SEQUENCE_CONFIGS)
              .build();
      ImmutableList.Builder<CompositionAssetInfo> filteredConfigs = new ImmutableList.Builder<>();
      // DefaultGlFrameProcessor doesn't support speed changing video effects.
      // TODO: b/530108514 - Move tests to use EditedMediaItem.setSpeed().
      for (CompositionAssetInfo config : allConfigs) {
        String configString = config.toString();
        if (configString.contains(VIDEO_ONLY_CLIPPED_HALF_SPEED.name)
            || configString.contains(VIDEO_ONLY_CLIPPED_TWICE_SPEED.name)) {
          continue;
        }
        filteredConfigs.add(config);
      }
      return filteredConfigs.build();
    }
  }

  @Before
  public void setup() throws Exception {
    rule.getScenario().onActivity(activity -> surfaceView = activity.getSurfaceView());
    if (SDK_INT >= 26) {
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
  }

  @After
  public void tearDown() {
    getInstrumentation()
        .runOnMainSync(
            () -> {
              if (player != null) {
                player.release();
              }
            });
    rule.getScenario().close();
    @Nullable Exception releasingException = null;
    if (SDK_INT >= 26 && glExecutorService != null) {
      releasingException =
          closeTestingGlResources(glExecutorService, glObjectsProvider, TEST_TIMEOUT_MS);
      glExecutorService.shutdown();
    }
    if (releasingException != null) {
      throw new AssertionError(releasingException);
    }
  }

  @Test
  public void playback_singleInputVideoGraph(
      @TestParameter(valuesProvider = SingleInputVideoGraphConfigsProvider.class)
          CompositionAssetInfo compositionAssetInfo)
      throws Exception {
    // The MediaCodec decoder's output surface is sometimes dropping frames on emulator despite
    // using MediaFormat.KEY_ALLOW_FRAME_DROP.
    assume()
        .withMessage("Skipped on emulator due to surface dropping frames")
        .that(isRunningOnEmulator())
        .isFalse();
    InputTimestampRecordingShaderProgram inputTimestampRecordingShaderProgram =
        new InputTimestampRecordingShaderProgram();
    Composition composition =
        compositionAssetInfo
            .getComposition()
            .buildUpon()
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(),
                    /* videoEffects= */ ImmutableList.of(
                        (GlEffect) (context, useHdr) -> inputTimestampRecordingShaderProgram)))
            .build();

    runCompositionPlayer(composition, new SingleInputVideoGraph.Factory());

    assertThat(inputTimestampRecordingShaderProgram.getInputTimestampsUs())
        .isEqualTo(compositionAssetInfo.getExpectedVideoTimestampsUs());
  }

  @Test
  public void playback_multipleInputVideoGraph(
      @TestParameter(valuesProvider = MultipleInputVideoGraphConfigsProvider.class)
          CompositionAssetInfo compositionAssetInfo)
      throws Exception {
    // The MediaCodec decoder's output surface is sometimes dropping frames on emulator despite
    // using MediaFormat.KEY_ALLOW_FRAME_DROP.
    assume()
        .withMessage("Skipped on emulator due to surface dropping frames")
        .that(isRunningOnEmulator())
        .isFalse();
    InputTimestampRecordingShaderProgram inputTimestampRecordingShaderProgram =
        new InputTimestampRecordingShaderProgram();
    Composition composition =
        compositionAssetInfo
            .getComposition()
            .buildUpon()
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(),
                    /* videoEffects= */ ImmutableList.of(
                        (GlEffect) (context, useHdr) -> inputTimestampRecordingShaderProgram)))
            .build();

    runCompositionPlayer(composition, new MultipleInputVideoGraph.Factory());

    assertThat(inputTimestampRecordingShaderProgram.getInputTimestampsUs())
        .isEqualTo(compositionAssetInfo.getExpectedVideoTimestampsUs());
  }

  @Test
  @SdkSuppress(minSdkVersion = 28)
  public void playback_frameProcessor(
      @TestParameter(valuesProvider = FrameConsumerConfigsProvider.class)
          CompositionAssetInfo compositionAssetInfo)
      throws Exception {
    assume()
        .withMessage("Skipped on emulator due to surface dropping frames")
        .that(isRunningOnEmulator())
        .isFalse();

    CapturingFrameProcessor.Factory recordingFrameProcessorFactory =
        new CapturingFrameProcessor.Factory(
            new DefaultGlFrameProcessor.Factory(
                context, glObjectsProvider, HardwareBufferJni.INSTANCE, glExecutorService));
    Composition composition = compositionAssetInfo.getComposition();
    runCompositionPlayer(composition, recordingFrameProcessorFactory);

    CapturingFrameProcessor frameProcessor = recordingFrameProcessorFactory.getCreatedProcessor();
    assertThat(frameProcessor).isNotNull();
    if (compositionAssetInfo.hasVideo()) {
      assertThat(frameProcessor.isEnded()).isTrue();
      FrameProcessorTestUtil.assertPlaybackOutput(frameProcessor, compositionAssetInfo);
    } else {
      // TODO: b/534326875 - Remove this once audio only Compositions are handled.
      assertThat(frameProcessor.isEnded()).isFalse();
      assertThat(frameProcessor.getQueuedEvents()).isEmpty();
    }
  }

  private void runCompositionPlayer(Composition composition, VideoGraph.Factory videoGraphFactory)
      throws Exception {
    SettableFuture<Void> endedFuture = SettableFuture.create();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              player =
                  new CompositionPlayer.Builder(context)
                      .setVideoGraphFactory(videoGraphFactory)
                      .experimentalSetLateThresholdToDropInputUs(C.TIME_UNSET)
                      .build();
              // Set a surface on the player even though there is no UI on this test. We need a
              // surface otherwise the player will skip/drop video frames.
              player.setVideoSurfaceView(surfaceView);
              endedFuture.setFuture(futureWhen(player).entersPlaybackState(Player.STATE_ENDED));
              player.setComposition(composition);
              player.prepare();
              player.play();
            });
    endedFuture.get();
  }

  @RequiresApi(28)
  private void runCompositionPlayer(
      Composition composition, FrameProcessor.Factory frameProcessorFactory) throws Exception {
    SettableFuture<Void> endedFuture = SettableFuture.create();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              player =
                  new CompositionPlayer.Builder(context)
                      .setNativeHardwareBufferHelpers(HardwareBufferJni.INSTANCE)
                      .setFrameProcessorFactory(frameProcessorFactory)
                      .experimentalSetLateThresholdToDropInputUs(C.TIME_UNSET)
                      .build();
              // Set a surface on the player even though there is no UI on this test. We need a
              // surface otherwise the player will skip/drop video frames.
              player.setVideoSurfaceView(surfaceView);
              endedFuture.setFuture(futureWhen(player).entersPlaybackState(Player.STATE_ENDED));
              player.setComposition(composition);
              player.prepare();
              player.play();
            });
    endedFuture.get();
  }

  private static PlayerFence futureWhen(Player player) {
    return PlayerFence.futureWhen(player).withTimeoutMs(TEST_TIMEOUT_MS);
  }
}
