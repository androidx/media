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
import static androidx.media3.common.util.Util.usToMs;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_SRGB;
import static androidx.media3.test.utils.AssetInfo.MP4_VIDEO_ONLY_ASSET;
import static androidx.media3.test.utils.AssetInfo.PNG_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_ASSET;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.TruthJUnit.assume;

import android.content.Context;
import android.util.Pair;
import android.view.SurfaceView;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.Consumer;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlTextureFrame;
import androidx.media3.effect.MultipleInputVideoGraph;
import androidx.media3.effect.SingleInputVideoGraph;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import java.util.ArrayList;
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
  private static final Pair<AudioProcessor, Effect> HALF_SPEED_CHANGE_EFFECTS =
      Effects.createExperimentalSpeedChangingEffect(
          new SpeedProvider() {
            @Override
            public float getSpeed(long timeUs) {
              return 0.5f;
            }

            @Override
            public long getNextSpeedChangeTimeUs(long timeUs) {
              // Adjust speed for all timestamps.
              return C.TIME_UNSET;
            }
          });
  private static final Pair<AudioProcessor, Effect> TWICE_SPEED_CHANGE_EFFECTS =
      Effects.createExperimentalSpeedChangingEffect(
          new SpeedProvider() {
            @Override
            public float getSpeed(long timeUs) {
              return 2f;
            }

            @Override
            public long getNextSpeedChangeTimeUs(long timeUs) {
              // Adjust speed for all timestamps.
              return C.TIME_UNSET;
            }
          });
  private static final Input IMAGE_INPUT =
      new Input(
          new EditedMediaItem.Builder(
                  new MediaItem.Builder()
                      .setUri(PNG_ASSET.uri)
                      .setImageDurationMs(usToMs(/* timeUs= */ 500_000))
                      .build())
              .setDurationUs(500_000)
              .build(),
          ImmutableList.of(
              0L, 33_333L, 66_667L, 100_000L, 133_333L, 166_667L, 200_000L, 233_333L, 266_667L,
              300_000L, 333_333L, 366_667L, 400_000L, 433_333L, 466_667L),
          /* inputName= */ "Image");
  private static final Input VIDEO_INPUT =
      new Input(
          new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
              .setDurationUs(MP4_ASSET.videoDurationUs)
              .build(),
          MP4_ASSET.videoTimestampsUs,
          /* inputName= */ "Video");
  private static final Input VIDEO_INPUT_SRGB =
      new Input(
          new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET_SRGB.uri))
              .setDurationUs(MP4_ASSET_SRGB.videoDurationUs)
              .build(),
          MP4_ASSET_SRGB.videoTimestampsUs,
          /* inputName= */ "Video_srgb");
  private static final Input VIDEO_INPUT_WITHOUT_AUDIO =
      new Input(
          new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
              .setDurationUs(MP4_ASSET.videoDurationUs)
              .setRemoveAudio(true)
              .build(),
          MP4_ASSET.videoTimestampsUs,
          /* inputName= */ "Video_no_audio");

  private static final MediaItem VIDEO_ONLY_CLIPPED =
      MediaItem.fromUri(MP4_VIDEO_ONLY_ASSET.uri)
          .buildUpon()
          .setClippingConfiguration(
              new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(500).build())
          .build();
  private static final Input VIDEO_ONLY_CLIPPED_TWICE_SPEED =
      new Input(
          new EditedMediaItem.Builder(VIDEO_ONLY_CLIPPED)
              .setDurationUs(MP4_VIDEO_ONLY_ASSET.videoDurationUs)
              .setRemoveAudio(true)
              .setEffects(
                  new Effects(
                      /* audioProcessors= */ ImmutableList.of(),
                      /* videoEffects= */ ImmutableList.of(TWICE_SPEED_CHANGE_EFFECTS.second)))
              .build(),
          /* expectedVideoTimestampsUs= */ ImmutableList.of(
              // The first timestamp is at clipping point, 500ms and speed up 2x to 250ms. The
              // last is at (967633 - 500_000) / 2
              250L,
              16933L,
              33616L,
              50300L,
              66983L,
              83666L,
              100350L,
              117033L,
              133716L,
              150400L,
              167083L,
              183766L,
              200450L,
              217133L,
              233816L),
          /* inputName= */ "Video_only_clippped_half_speed");
  private static final Input VIDEO_ONLY_CLIPPED_HALF_SPEED =
      new Input(
          new EditedMediaItem.Builder(VIDEO_ONLY_CLIPPED)
              .setDurationUs(MP4_VIDEO_ONLY_ASSET.videoDurationUs)
              .setRemoveAudio(true)
              .setEffects(
                  new Effects(
                      /* audioProcessors= */ ImmutableList.of(),
                      /* videoEffects= */ ImmutableList.of(HALF_SPEED_CHANGE_EFFECTS.second)))
              .build(),
          /* expectedVideoTimestampsUs= */ ImmutableList.of(
              // The first timestamp is at clipping point, 500ms and slowed down 2x to 1000ms. The
              // last is at (967633 - 500_000) x 2
              1000L,
              67732L,
              134466L,
              201200L,
              267932L,
              334666L,
              401400L,
              468132L,
              534866L,
              601600L,
              668332L,
              735066L,
              801800L,
              868532L,
              935266L),
          /* inputName= */ "Video_only_clippped_half_speed");
  private static final Input AUDIO_INPUT =
      new Input(
          new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
              .setDurationUs(1_000_000)
              .build(),
          /* expectedVideoTimestampsUs= */ ImmutableList.of(),
          /* inputName= */ "Audio");

  @Rule
  public ActivityScenarioRule<SurfaceTestActivity> rule =
      new ActivityScenarioRule<>(SurfaceTestActivity.class);

  private final Context context = getInstrumentation().getContext().getApplicationContext();

  private @MonotonicNonNull CompositionPlayer player;
  private @MonotonicNonNull PlayerTestListener playerTestListener;
  private @MonotonicNonNull SurfaceView surfaceView;

  private static final ImmutableList<TestConfig> singleSequenceConfigs =
      ImmutableList.of(
          new TestConfig(new InputSequence(VIDEO_INPUT)),
          new TestConfig(new InputSequence(VIDEO_INPUT_SRGB)),
          new TestConfig(new InputSequence(IMAGE_INPUT)),
          new TestConfig(new InputSequence(AUDIO_INPUT)),
          new TestConfig(
              new InputSequence(
                  VIDEO_INPUT, VIDEO_INPUT, VIDEO_INPUT, IMAGE_INPUT, IMAGE_INPUT, IMAGE_INPUT)),
          new TestConfig(
              new InputSequence(
                  IMAGE_INPUT, VIDEO_INPUT, IMAGE_INPUT, VIDEO_INPUT, IMAGE_INPUT, VIDEO_INPUT)),
          new TestConfig(
              new InputSequence(VIDEO_INPUT, AUDIO_INPUT, IMAGE_INPUT, AUDIO_INPUT, VIDEO_INPUT)),
          new TestConfig(
              new InputSequence(VIDEO_INPUT_WITHOUT_AUDIO, VIDEO_INPUT, VIDEO_INPUT_WITHOUT_AUDIO)),
          new TestConfig(new InputSequence(VIDEO_INPUT, VIDEO_INPUT_WITHOUT_AUDIO, VIDEO_INPUT)),
          new TestConfig(new InputSequence(VIDEO_INPUT, AUDIO_INPUT)),
          // TODO: b/412585977 - Enable once implicit gaps are implemented.
          // configs.add(new TestConfig(new InputSequence(AUDIO_INPUT,
          // VIDEO_INPUT).withForceVideoTrack()));
          new TestConfig(new InputSequence(VIDEO_ONLY_CLIPPED_HALF_SPEED)),
          new TestConfig(new InputSequence(VIDEO_ONLY_CLIPPED_TWICE_SPEED)),
          new TestConfig(
              new InputSequence(VIDEO_ONLY_CLIPPED_TWICE_SPEED, VIDEO_ONLY_CLIPPED_TWICE_SPEED)),
          new TestConfig(
              new InputSequence(VIDEO_ONLY_CLIPPED_TWICE_SPEED, VIDEO_ONLY_CLIPPED_HALF_SPEED)),
          new TestConfig(
              new InputSequence(VIDEO_ONLY_CLIPPED_HALF_SPEED, VIDEO_ONLY_CLIPPED_TWICE_SPEED)),
          new TestConfig(
              new InputSequence(VIDEO_ONLY_CLIPPED_HALF_SPEED, VIDEO_ONLY_CLIPPED_HALF_SPEED)),
          new TestConfig(
              new InputSequence(
                  VIDEO_INPUT, VIDEO_INPUT_SRGB, VIDEO_INPUT, IMAGE_INPUT, VIDEO_INPUT_SRGB)));

  private static final ImmutableList<TestConfig> multiSequenceImageConfigs =
      ImmutableList.of(
          new TestConfig(
              new InputSequence(IMAGE_INPUT, IMAGE_INPUT, IMAGE_INPUT),
              new InputSequence(IMAGE_INPUT, IMAGE_INPUT, IMAGE_INPUT)));

  private static final ImmutableList<TestConfig> multiSequenceVideoConfigs =
      ImmutableList.of(
          new TestConfig(
              new InputSequence(VIDEO_INPUT, VIDEO_INPUT, VIDEO_INPUT),
              new InputSequence(VIDEO_INPUT, VIDEO_INPUT, VIDEO_INPUT)));

  private static final ImmutableList<TestConfig> multiSequenceMismatchedSequenceDurationConfigs =
      ImmutableList.of(
          new TestConfig(
              new InputSequence(VIDEO_INPUT, AUDIO_INPUT, VIDEO_INPUT),
              new InputSequence(IMAGE_INPUT)),
          // TODO: b/418785194 - Enable once fixed.
          //     new TestConfig(
          //         new InputSequence(AUDIO_INPUT), new InputSequence(VIDEO_INPUT)),
          // TODO: b/421358098 - Enable once fixed.
          //     new TestConfig(
          //         new InputSequence(VIDEO_INPUT), new InputSequence(VIDEO_INPUT, VIDEO_INPUT)),
          new TestConfig(
              new InputSequence(VIDEO_INPUT, VIDEO_INPUT),
              new InputSequence(/* isLooping= */ false, AUDIO_INPUT))
          // TODO: b/419479048 - Enable once looping videos are supported.
          //     new TestConfig(
          //         new InputSequence(VIDEO_INPUT, VIDEO_INPUT),
          //         new InputSequence(VIDEO_INPUT).withIsLooping()),
          );

  private static class SingleInputVideoGraphConfigsProvider extends TestParameterValuesProvider {
    @Override
    protected List<TestConfig> provideValues(TestParameterValuesProvider.Context context) {
      return singleSequenceConfigs;
    }
  }

  private static class MultipleInputVideoGraphConfigsProvider extends TestParameterValuesProvider {
    @Override
    protected List<TestConfig> provideValues(TestParameterValuesProvider.Context context) {
      return new ImmutableList.Builder<TestConfig>()
          .addAll(singleSequenceConfigs)
          .addAll(multiSequenceImageConfigs)
          .addAll(multiSequenceMismatchedSequenceDurationConfigs)
          .build();
    }
  }

  private static class FrameConsumerConfigsProvider extends TestParameterValuesProvider {
    @Override
    protected List<TestConfig> provideValues(TestParameterValuesProvider.Context context) {
      // TODO: b/418785194 - Expand this once mismatched sequence lengths are supported.
      return new ImmutableList.Builder<TestConfig>()
          .addAll(singleSequenceConfigs)
          .addAll(multiSequenceImageConfigs)
          .addAll(multiSequenceVideoConfigs)
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
          TestConfig testConfig)
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
        testConfig
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
        .isEqualTo(testConfig.getExpectedVideoTimestampsUs());
  }

  @Test
  public void playback_multipleInputVideoGraph(
      @TestParameter(valuesProvider = MultipleInputVideoGraphConfigsProvider.class)
          TestConfig testConfig)
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
        testConfig
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
        .isEqualTo(testConfig.getExpectedVideoTimestampsUs());
  }

  @Test
  public void playback_frameConsumer(
      @TestParameter(valuesProvider = FrameConsumerConfigsProvider.class) TestConfig testConfig)
      throws Exception {
    // The MediaCodec decoder's output surface is sometimes dropping frames on emulator despite
    // using MediaFormat.KEY_ALLOW_FRAME_DROP.
    assume()
        .withMessage("Skipped on emulator due to surface dropping frames")
        .that(isRunningOnEmulator())
        .isFalse();
    RecordingFrameConsumer frameConsumer = new RecordingFrameConsumer();

    runCompositionPlayer(testConfig.getComposition(), frameConsumer::queue);

    assertThat(frameConsumer.getInputPresentationTimesUs())
        .isEqualTo(testConfig.getExpectedVideoTimestampsUs());
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
      Composition composition, Consumer<List<GlTextureFrame>> frameConsumer)
      throws PlaybackException, TimeoutException {
    getInstrumentation()
        .runOnMainSync(
            () -> {
              player =
                  new CompositionPlayer.Builder(context)
                      .experimentalSetFrameConsumer(frameConsumer)
                      .setGlObjectsProvider(new CompositionPlayer.SingleContextGlObjectsProvider())
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

  private static final class TestConfig {
    private final ImmutableList<InputSequence> inputSequences;

    public TestConfig(InputSequence sequence, InputSequence... inputSequences) {
      this.inputSequences =
          new ImmutableList.Builder<InputSequence>().add(sequence).add(inputSequences).build();
    }

    public Composition getComposition() {
      return new Composition.Builder(
              ImmutableList.copyOf(
                  Iterables.transform(inputSequences, InputSequence::getEditedMediaItemSequence)))
          .build();
    }

    public ImmutableList<Long> getExpectedVideoTimestampsUs() {
      // When there are multiple sequences, output timestamps should match those of the primary
      // sequence.
      return inputSequences.get(0).getExpectedVideoTimestampsUs();
    }

    @Override
    public String toString() {
      StringBuilder stringBuilder = new StringBuilder();
      for (InputSequence inputSequence : inputSequences) {
        stringBuilder.append("(");
        stringBuilder.append(inputSequence);
        stringBuilder.append(")");
      }
      return stringBuilder.toString();
    }
  }

  private static final class InputSequence {
    private final ImmutableList<Input> inputs;
    private final boolean isLooping;

    public InputSequence(Input input, Input... inputs) {
      this(/* isLooping= */ false, input, inputs);
    }

    public InputSequence(boolean isLooping, Input input, Input... inputs) {
      this.inputs = new ImmutableList.Builder<Input>().add(input).add(inputs).build();
      this.isLooping = isLooping;
    }

    public EditedMediaItemSequence getEditedMediaItemSequence() {
      EditedMediaItemSequence.Builder sequenceBuilder = new EditedMediaItemSequence.Builder();
      for (Input input : inputs) {
        sequenceBuilder.addItem(input.editedMediaItem);
      }
      sequenceBuilder.setIsLooping(isLooping);
      return sequenceBuilder.build();
    }

    public ImmutableList<Long> getExpectedVideoTimestampsUs() {
      ImmutableList.Builder<Long> expectedVideoTimestampsUs = new ImmutableList.Builder<>();
      long previousDuration = 0;
      for (Input input : inputs) {
        long finalPreviousDuration = previousDuration;
        expectedVideoTimestampsUs.addAll(
            Iterables.transform(
                input.expectedVideoTimestampsUs,
                timestampUs -> finalPreviousDuration + timestampUs));
        previousDuration += input.durationUs;
      }
      return expectedVideoTimestampsUs.build();
    }

    @Override
    public String toString() {
      StringBuilder stringBuilder = new StringBuilder();
      for (Input input : inputs) {
        stringBuilder.append(input.inputName);
      }
      if (isLooping) {
        stringBuilder.append("Loop");
      }
      return stringBuilder.toString();
    }
  }

  private static final class Input {
    private final EditedMediaItem editedMediaItem;
    private final ImmutableList<Long> expectedVideoTimestampsUs;
    private final long durationUs;
    private final String inputName;

    public Input(
        EditedMediaItem editedMediaItem,
        ImmutableList<Long> expectedVideoTimestampsUs,
        String inputName) {
      this.editedMediaItem = editedMediaItem;
      this.expectedVideoTimestampsUs = expectedVideoTimestampsUs;
      this.durationUs = editedMediaItem.getPresentationDurationUs();
      this.inputName = inputName;
    }
  }

  private static final class RecordingFrameConsumer {
    private final List<List<GlTextureFrame>> queuedPackets;

    private RecordingFrameConsumer() {
      queuedPackets = new ArrayList<>();
    }

    private void queue(List<GlTextureFrame> frames) {
      for (GlTextureFrame frame : frames) {
        frame.release();
      }
      queuedPackets.add(frames);
    }

    private List<Long> getInputPresentationTimesUs() {
      ArrayList<Long> presentationTimesUs = new ArrayList<>();
      for (List<GlTextureFrame> frames : queuedPackets) {
        presentationTimesUs.add(frames.get(0).presentationTimeUs);
      }
      return presentationTimesUs;
    }
  }
}
