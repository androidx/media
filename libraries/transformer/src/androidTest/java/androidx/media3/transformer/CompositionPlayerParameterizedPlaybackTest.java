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
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET;
import static androidx.media3.transformer.AndroidTestUtil.PNG_ASSET;
import static androidx.media3.transformer.AndroidTestUtil.WAV_ASSET;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.TruthJUnit.assume;

import android.content.Context;
import android.view.SurfaceView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.VideoGraph;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.MultipleInputVideoGraph;
import androidx.media3.effect.SingleInputVideoGraph;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.concurrent.TimeoutException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/** Parameterized playback tests for {@link CompositionPlayer}. */
@RunWith(Parameterized.class)
public class CompositionPlayerParameterizedPlaybackTest {

  private static final long TEST_TIMEOUT_MS = isRunningOnEmulator() ? 30_000 : 20_000;
  private static final Input IMAGE_INPUT =
      new Input(
          new EditedMediaItem.Builder(
                  new MediaItem.Builder()
                      .setUri(PNG_ASSET.uri)
                      .setImageDurationMs(usToMs(/* timeUs= */ 500_000))
                      .build())
              .setDurationUs(500_000)
              .build(),
          // 200 ms at 30 fps (default frame rate)
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
  private static final Input VIDEO_INPUT_WITHOUT_AUDIO =
      new Input(
          new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
              .setDurationUs(MP4_ASSET.videoDurationUs)
              .setRemoveAudio(true)
              .build(),
          MP4_ASSET.videoTimestampsUs,
          /* inputName= */ "Video_no_audio");
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
  @Parameter public @MonotonicNonNull TestConfig testConfig;

  @Parameters(name = "{0}")
  public static ImmutableList<TestConfig> params() {
    ImmutableList.Builder<TestConfig> configs = new ImmutableList.Builder<>();
    // Single asset.
    configs.add(new TestConfig(new InputSequence(VIDEO_INPUT)));
    configs.add(new TestConfig(new InputSequence(IMAGE_INPUT)));
    configs.add(new TestConfig(new InputSequence(AUDIO_INPUT)));

    // Single sequence.
    configs.add(
        new TestConfig(
            new InputSequence(
                VIDEO_INPUT, VIDEO_INPUT, VIDEO_INPUT, IMAGE_INPUT, IMAGE_INPUT, IMAGE_INPUT)));
    configs.add(
        new TestConfig(
            new InputSequence(
                IMAGE_INPUT, VIDEO_INPUT, IMAGE_INPUT, VIDEO_INPUT, IMAGE_INPUT, VIDEO_INPUT)));
    configs.add(
        new TestConfig(
            new InputSequence(VIDEO_INPUT, AUDIO_INPUT, IMAGE_INPUT, AUDIO_INPUT, VIDEO_INPUT)));
    configs.add(
        new TestConfig(
            new InputSequence(VIDEO_INPUT_WITHOUT_AUDIO, VIDEO_INPUT, VIDEO_INPUT_WITHOUT_AUDIO)));
    configs.add(
        new TestConfig(new InputSequence(VIDEO_INPUT, VIDEO_INPUT_WITHOUT_AUDIO, VIDEO_INPUT)));
    // TODO: b/414777457 - Enable once sequences ending with audio is fixed.
    // configs.add(new TestConfig(new InputSequence(VIDEO_INPUT, AUDIO_INPUT)));
    // TODO: b/412585977 - Enable once implicit gaps are implemented.
    // configs.add(new TestConfig(new InputSequence(AUDIO_INPUT,
    // VIDEO_INPUT).withForceVideoTrack()));

    // Multiple sequence.
    configs.add(
        new TestConfig(
            new InputSequence(IMAGE_INPUT, IMAGE_INPUT, IMAGE_INPUT),
            new InputSequence(IMAGE_INPUT, IMAGE_INPUT, IMAGE_INPUT)));
    // TODO: b/405966202 - Enable after propagating an EOS signal after each MediaItem.
    // configs.add(
    //     new TestConfig(
    //         new InputSequence(VIDEO_INPUT, VIDEO_INPUT, VIDEO_INPUT),
    //         new InputSequence(VIDEO_INPUT, VIDEO_INPUT, VIDEO_INPUT)));
    configs.add(
        new TestConfig(
            new InputSequence(VIDEO_INPUT, AUDIO_INPUT, VIDEO_INPUT),
            new InputSequence(IMAGE_INPUT)));
    // TODO: b/418785194 - Enable once fixed.
    // configs.add(
    //     new TestConfig(
    //         new InputSequence(AUDIO_INPUT), new InputSequence(VIDEO_INPUT)));
    // TODO: b/421358098 - Enable once fixed.
    // configs.add(
    //     new TestConfig(
    //         new InputSequence(VIDEO_INPUT), new InputSequence(VIDEO_INPUT, VIDEO_INPUT)));
    configs.add(
        new TestConfig(
            new InputSequence(VIDEO_INPUT, VIDEO_INPUT),
            new InputSequence(/* isLooping= */ false, AUDIO_INPUT)));
    // TODO: b/419479048 - Enable once looping videos are supported.
    // configs.add(
    //     new TestConfig(
    //         new InputSequence(VIDEO_INPUT, VIDEO_INPUT),
    //         new InputSequence(VIDEO_INPUT).withIsLooping()));

    return configs.build();
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
  public void playback_singleInputVideoGraph() throws Exception {
    // The MediaCodec decoder's output surface is sometimes dropping frames on emulator despite
    // using MediaFormat.KEY_ALLOW_FRAME_DROP.
    assume()
        .withMessage("Skipped on emulator due to surface dropping frames")
        .that(isRunningOnEmulator())
        .isFalse();
    assume()
        .withMessage("Skipped due to input containing multiple sequences")
        .that(testConfig.inputSequences.size())
        .isEqualTo(1);
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
  public void playback_multipleInputVideoGraph() throws Exception {
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

  private void runCompositionPlayer(Composition composition, VideoGraph.Factory videoGraphFactory)
      throws PlaybackException, TimeoutException {
    getInstrumentation()
        .runOnMainSync(
            () -> {
              player =
                  new CompositionPlayer.Builder(context)
                      .setVideoGraphFactory(videoGraphFactory)
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
      this.durationUs = editedMediaItem.durationUs;
      this.inputName = inputName;
    }
  }
}
