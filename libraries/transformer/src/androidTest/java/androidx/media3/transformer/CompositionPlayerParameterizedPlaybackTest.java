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

import static androidx.media3.common.util.Util.isRunningOnEmulator;
import static androidx.media3.transformer.CompositionAssetInfo.MULTI_SEQUENCE_IMAGE_CONFIGS;
import static androidx.media3.transformer.CompositionAssetInfo.MULTI_SEQUENCE_MISMATCHED_DURATION_CONFIGS;
import static androidx.media3.transformer.CompositionAssetInfo.MULTI_SEQUENCE_VIDEO_CONFIGS;
import static androidx.media3.transformer.CompositionAssetInfo.SINGLE_SEQUENCE_CONFIGS;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.TruthJUnit.assume;

import android.content.Context;
import android.view.SurfaceView;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.VideoGraph;
import androidx.media3.effect.Frame;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.effect.MultipleInputVideoGraph;
import androidx.media3.effect.PacketConsumer;
import androidx.media3.effect.SingleInputVideoGraph;
import androidx.media3.test.utils.RecordingPacketConsumer;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import java.util.List;
import java.util.concurrent.TimeoutException;
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
  private @MonotonicNonNull PlayerTestListener playerTestListener;
  private @MonotonicNonNull SurfaceView surfaceView;

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
          .addAll(MULTI_SEQUENCE_IMAGE_CONFIGS)
          .addAll(MULTI_SEQUENCE_MISMATCHED_DURATION_CONFIGS)
          .build();
    }
  }

  private static class FrameConsumerConfigsProvider extends TestParameterValuesProvider {
    @Override
    protected List<CompositionAssetInfo> provideValues(
        TestParameterValuesProvider.Context context) {
      // TODO: b/418785194 - Expand this once mismatched sequence lengths are supported.
      return new ImmutableList.Builder<CompositionAssetInfo>()
          .addAll(SINGLE_SEQUENCE_CONFIGS)
          .addAll(MULTI_SEQUENCE_IMAGE_CONFIGS)
          .addAll(MULTI_SEQUENCE_VIDEO_CONFIGS)
          .build();
    }
  }

  @Before
  public void setup() {
    rule.getScenario().onActivity(activity -> surfaceView = activity.getSurfaceView());
    playerTestListener = new PlayerTestListener(TEST_TIMEOUT_MS);
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
  public void playback_packetConsumer(
      @TestParameter(valuesProvider = FrameConsumerConfigsProvider.class)
          CompositionAssetInfo compositionAssetInfo)
      throws Exception {
    // The MediaCodec decoder's output surface is sometimes dropping frames on emulator despite
    // using MediaFormat.KEY_ALLOW_FRAME_DROP.
    assume()
        .withMessage("Skipped on emulator due to surface dropping frames")
        .that(isRunningOnEmulator())
        .isFalse();
    RecordingPacketConsumer<List<HardwareBufferFrame>> packetConsumer =
        new RecordingPacketConsumer<>();
    packetConsumer.setOnQueue(
        (frames) -> {
          for (HardwareBufferFrame frame : frames) {
            frame.release();
          }
          return null;
        });
    ImmutableList<Long> expectedVideoTimestampsUs =
        compositionAssetInfo.getExpectedVideoTimestampsUs();

    Composition composition = compositionAssetInfo.getComposition();
    runCompositionPlayer(composition, /* packetConsumerFactory= */ () -> packetConsumer);

    List<List<HardwareBufferFrame>> queuedPackets = packetConsumer.getQueuedPayloads();
    // TODO: b/449956936 - add EOS to CompositionPlayer packet consumer and wait until its received.
    assertThat(queuedPackets.size()).isAtLeast(expectedVideoTimestampsUs.size() - 2);
    for (int packetIndex = 0; packetIndex < queuedPackets.size(); packetIndex++) {
      long presentationTimeUs = queuedPackets.get(packetIndex).get(0).presentationTimeUs;
      assertThat(presentationTimeUs).isEqualTo(expectedVideoTimestampsUs.get(packetIndex));
      assertThat(queuedPackets.get(0)).hasSize(composition.sequences.size());
      for (int sequenceIndex = 0;
          sequenceIndex < queuedPackets.get(packetIndex).size();
          ++sequenceIndex) {
        Frame.Metadata metadata = queuedPackets.get(packetIndex).get(sequenceIndex).getMetadata();
        assertThat(metadata).isInstanceOf(CompositionFrameMetadata.class);
        CompositionFrameMetadata compositionFrameMetadata = (CompositionFrameMetadata) metadata;
        assertThat(compositionFrameMetadata.sequenceIndex).isEqualTo(sequenceIndex);
        // CompositionPlayer replaces TimestampAdjustment effects with InactiveTimestampAdjustment.
        // Assert on the non-edited MediaItem.
        MediaItem itemFromMetadata = itemFromMetadata(compositionFrameMetadata);
        MediaItem expectedItemAtTime =
            expectedItemAtTime(composition, sequenceIndex, presentationTimeUs);
        assertThat(itemFromMetadata).isEqualTo(expectedItemAtTime);
      }
    }
  }

  private void runCompositionPlayer(Composition composition, VideoGraph.Factory videoGraphFactory)
      throws PlaybackException, TimeoutException {
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
              player.addListener(playerTestListener);
              player.setComposition(composition);
              player.prepare();
              player.play();
            });
    playerTestListener.waitUntilPlayerEnded();
  }

  private void runCompositionPlayer(
      Composition composition,
      PacketConsumer.Factory<List<HardwareBufferFrame>> packetConsumerFactory)
      throws PlaybackException, TimeoutException {
    getInstrumentation()
        .runOnMainSync(
            () -> {
              player =
                  new CompositionPlayer.Builder(context)
                      .setPacketConsumerFactory(packetConsumerFactory)
                      .experimentalSetLateThresholdToDropInputUs(C.TIME_UNSET)
                      .build();
              // Set a surface on the player even though there is no UI on this test. We need a
              // surface otherwise the player will skip/drop video frames.
              player.setVideoSurfaceView(surfaceView);
              player.addListener(playerTestListener);
              player.setComposition(composition);
              player.prepare();
              player.play();
            });
    playerTestListener.waitUntilPlayerEnded();
  }

  private static MediaItem itemFromMetadata(CompositionFrameMetadata metadata) {
    return metadata
        .composition
        .sequences
        .get(metadata.sequenceIndex)
        .editedMediaItems
        .get(metadata.itemIndex)
        .mediaItem;
  }

  private static MediaItem expectedItemAtTime(
      Composition composition, int sequenceIndex, long presentationTimeUs) {
    EditedMediaItemSequence sequence = composition.sequences.get(sequenceIndex);
    int itemIndex = 0;
    while (itemIndex < sequence.editedMediaItems.size()) {
      long itemDurationUs = sequence.editedMediaItems.get(itemIndex).getPresentationDurationUs();
      if (presentationTimeUs < itemDurationUs) {
        break;
      }
      presentationTimeUs -= itemDurationUs;
      itemIndex++;
    }
    return sequence.editedMediaItems.get(itemIndex).mediaItem;
  }
}
