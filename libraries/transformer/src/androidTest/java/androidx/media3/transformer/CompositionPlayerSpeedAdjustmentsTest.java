/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.transformer;

import static androidx.media3.test.utils.AssetInfo.MOV_WITH_PCM_AUDIO;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_ASSET;
import static androidx.media3.test.utils.FormatSupportAssumptions.assumeFormatsSupported;
import static androidx.media3.test.utils.TestUtil.createByteCountingAudioProcessor;
import static com.google.common.truth.Truth.assertThat;

import android.app.Instrumentation;
import android.content.Context;
import android.util.Pair;
import android.view.SurfaceView;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.effect.GlEffect;
import androidx.media3.test.utils.TestSpeedProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link CompositionPlayer} with Speed Adjustments. */
@RunWith(AndroidJUnit4.class)
public class CompositionPlayerSpeedAdjustmentsTest {
  // Emulators take considerably longer to run each test.
  private static final long TEST_TIMEOUT_MS = 30_000;

  @Rule public final TestName testName = new TestName();

  @Rule
  public ActivityScenarioRule<SurfaceTestActivity> rule =
      new ActivityScenarioRule<>(SurfaceTestActivity.class);

  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context applicationContext = instrumentation.getContext().getApplicationContext();

  private CompositionPlayer compositionPlayer;
  private PlayerTestListener playerListener;
  private SurfaceView surfaceView;
  private String testId;

  @Before
  public void setup() {
    testId = testName.getMethodName();
    playerListener = new PlayerTestListener(TEST_TIMEOUT_MS);
    rule.getScenario().onActivity(activity -> surfaceView = activity.getSurfaceView());
  }

  @After
  public void closeActivity() {
    if (compositionPlayer != null) {
      instrumentation.runOnMainSync(compositionPlayer::release);
    }
    rule.getScenario().close();
  }

  @Test
  public void videoPreview_withSpeedAdjustment_timestampsAreCorrect() throws Exception {
    Pair<AudioProcessor, Effect> effects =
        Effects.createExperimentalSpeedChangingEffect(
            TestSpeedProvider.createWithStartTimes(
                new long[] {0, 300_000L, 600_000L}, new float[] {2f, 1f, 0.5f}));
    EditedMediaItem video =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setDurationUs(1_000_000)
            .setEffects(
                new Effects(ImmutableList.of(effects.first), ImmutableList.of(effects.second)))
            .build();

    ImmutableList<Long> timestampsFromCompositionPlayer = getTimestampsFromCompositionPlayer(video);

    assertThat(timestampsFromCompositionPlayer)
        .containsExactly(
            0L, 16683L, 33366L, 50050L, 66733L, 83416L, 100100L, 116783L, 133466L, 150300L, 183666L,
            217033L, 250400L, 283766L, 317133L, 350500L, 383866L, 417233L, 451200L, 517932L,
            584666L, 651400L, 718132L, 784866L, 851600L, 918332L, 985066L, 1051800L, 1118532L,
            1185266L);
  }

  @Test
  public void setSpeed_withAudioAndVideo_modifiesOutputCorrectly() throws Exception {
    assumeFormatsSupported(
        applicationContext, testId, MOV_WITH_PCM_AUDIO.videoFormat, /* outputFormat= */ null);

    AtomicInteger bytes = new AtomicInteger();
    AudioProcessor processor = createByteCountingAudioProcessor(bytes);
    SpeedProvider provider =
        TestSpeedProvider.createWithStartTimes(
            new long[] {0, 300_000L, 600_000L}, new float[] {2f, 1f, 0.5f});
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(MOV_WITH_PCM_AUDIO.uri))
            .setDurationUs(2_000_000)
            .setEffects(new Effects(ImmutableList.of(processor), ImmutableList.of()))
            .setSpeed(provider)
            .build();

    ImmutableList<Long> timestampsFromCompositionPlayer = getTimestampsFromCompositionPlayer(item);

    // 3250 ms @ mono 48 KHz = 55125 samples
    // Allow a tolerance equal to number of speed regions.
    assertThat(bytes.get() / 4).isWithin(3).of(156000);
    assertThat(timestampsFromCompositionPlayer)
        .containsExactly(
            0L, 10000L, 20000L, 30000L, 40000L, 50000L, 60000L, 70000L, 80000L, 90000L, 100000L,
            110000L, 120000L, 130000L, 140000L, 150000L, 170000L, 190000L, 210000L, 230000L,
            250000L, 270000L, 290000L, 310000L, 330000L, 350000L, 370000L, 390000L, 410000L,
            430000L, 450000L, 490000L, 530000L, 570000L, 610000L, 650000L, 690000L, 730000L,
            770000L, 810000L, 850000L, 890000L, 930000L, 970000L, 1010000L, 1050000L, 1090000L,
            1130000L, 1170000L, 1210000L, 1250000L, 1290000L, 1330000L, 1370000L, 1410000L,
            1450000L, 1490000L, 1530000L, 1570000L, 1610000L, 1650000L, 1690000L, 1730000L,
            1770000L, 1810000L, 1850000L, 1890000L, 1930000L, 1970000L, 2010000L, 2050000L,
            2090000L, 2130000L, 2170000L, 2210000L, 2250000L, 2290000L, 2330000L, 2370000L,
            2410000L, 2450000L, 2490000L, 2530000L, 2570000L, 2610000L, 2650000L, 2690000L,
            2730000L, 2770000L, 2810000L, 2850000L, 2890000L, 2930000L, 2970000L, 3010000L,
            3050000L, 3090000L, 3130000L, 3170000L, 3210000L);
  }

  @Test
  public void setSpeed_withAudioOnly_outputsExpectedNumberOfSamples() throws Exception {
    AtomicInteger bytes = new AtomicInteger();
    AudioProcessor processor = createByteCountingAudioProcessor(bytes);
    SpeedProvider provider =
        TestSpeedProvider.createWithStartTimes(
            new long[] {0, 300_000L, 600_000L}, new float[] {2f, 1f, 0.5f});

    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
            .setDurationUs(1_000_000)
            .setEffects(new Effects(ImmutableList.of(processor), ImmutableList.of()))
            .setSpeed(provider)
            .build();

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(applicationContext).build();
          compositionPlayer.addListener(playerListener);
          compositionPlayer.setComposition(
              new Composition.Builder(EditedMediaItemSequence.withAudioFrom(ImmutableList.of(item)))
                  .build());
          compositionPlayer.prepare();
          compositionPlayer.play();
        });

    playerListener.waitUntilPlayerEnded();

    // 1250 ms @ mono 44.1 KHz = 55125 samples
    // Allow a tolerance equal to number of speed regions.
    assertThat(bytes.get() / 2).isWithin(3).of(55125);
  }

  @Test
  public void setSpeed_onSecondarySequence_outputsExpectedNumberOfSamples() throws Exception {
    AtomicInteger bytes = new AtomicInteger();
    AudioProcessor processor = createByteCountingAudioProcessor(bytes);
    SpeedProvider provider =
        TestSpeedProvider.createWithStartTimes(
            new long[] {0, 300_000L, 600_000L}, new float[] {2f, 1f, 0.5f});

    EditedMediaItem primaryItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
            .setDurationUs(1_000_000)
            .build();

    EditedMediaItem secondaryItem = primaryItem.buildUpon().setSpeed(provider).build();

    EditedMediaItemSequence primarySequence =
        EditedMediaItemSequence.withAudioFrom(ImmutableList.of(primaryItem));
    EditedMediaItemSequence secondarySequence =
        EditedMediaItemSequence.withAudioFrom(ImmutableList.of(secondaryItem));

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer = new CompositionPlayer.Builder(applicationContext).build();
          compositionPlayer.addListener(playerListener);
          compositionPlayer.setComposition(
              new Composition.Builder(primarySequence, secondarySequence)
                  .setEffects(new Effects(ImmutableList.of(processor), ImmutableList.of()))
                  .build());
          compositionPlayer.prepare();
          compositionPlayer.play();
        });
    playerListener.waitUntilPlayerEnded();

    // 1250 ms @ mono 44.1 KHz = 55125 samples
    // Allow a tolerance equal to number of speed regions.
    assertThat(bytes.get() / 2).isWithin(3).of(55125);
  }

  @Test
  public void setSpeed_withVideoOnly_modifiesOutputCorrectly() throws Exception {
    EditedMediaItem video =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setDurationUs(1_000_000)
            .setRemoveAudio(true)
            .setSpeed(
                TestSpeedProvider.createWithStartTimes(
                    new long[] {0, 300_000L, 600_000L}, new float[] {2f, 1f, 0.5f}))
            .build();

    ImmutableList<Long> timestampsFromCompositionPlayer = getTimestampsFromCompositionPlayer(video);

    assertThat(timestampsFromCompositionPlayer)
        .containsExactly(
            0L, 16683L, 33366L, 50050L, 66733L, 83416L, 100100L, 116783L, 133466L, 150300L, 183666L,
            217033L, 250400L, 283766L, 317133L, 350500L, 383866L, 417233L, 451200L, 517932L,
            584666L, 651400L, 718132L, 784866L, 851600L, 918332L, 985066L, 1051800L, 1118532L,
            1185266L);
  }

  private ImmutableList<Long> getTimestampsFromCompositionPlayer(EditedMediaItem item)
      throws Exception {
    InputTimestampRecordingShaderProgram timestampRecordingShaderProgram =
        new InputTimestampRecordingShaderProgram();
    ImmutableList<EditedMediaItem> timestampRecordingEditedMediaItems =
        appendVideoEffects(
            item,
            /* effects= */ ImmutableList.of(
                (GlEffect) (context, useHdr) -> timestampRecordingShaderProgram));

    instrumentation.runOnMainSync(
        () -> {
          compositionPlayer =
              new CompositionPlayer.Builder(applicationContext)
                  .experimentalSetLateThresholdToDropInputUs(C.TIME_UNSET)
                  .build();
          // Set a surface on the player even though there is no UI on this test. We need a surface
          // otherwise the player will skip/drop video frames.
          compositionPlayer.setVideoSurfaceView(surfaceView);
          compositionPlayer.addListener(playerListener);
          compositionPlayer.setComposition(
              new Composition.Builder(
                      EditedMediaItemSequence.withAudioAndVideoFrom(
                          timestampRecordingEditedMediaItems))
                  .build());
          compositionPlayer.prepare();
          compositionPlayer.play();
        });

    playerListener.waitUntilPlayerEnded();

    return timestampRecordingShaderProgram.getInputTimestampsUs();
  }

  private static ImmutableList<EditedMediaItem> appendVideoEffects(
      EditedMediaItem item, List<Effect> effects) {
    return ImmutableList.of(
        item.buildUpon()
            .setEffects(
                new Effects(
                    item.effects.audioProcessors,
                    new ImmutableList.Builder<Effect>()
                        .addAll(item.effects.videoEffects)
                        .addAll(effects)
                        .build()))
            .build());
  }
}
