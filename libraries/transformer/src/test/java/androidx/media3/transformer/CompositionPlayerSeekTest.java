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
import static androidx.media3.common.Player.STATE_READY;
import static androidx.media3.test.utils.AssetInfo.MP4_15FPS;
import static androidx.media3.test.utils.AssetInfo.MP4_ADVANCED_ASSET;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S;
import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig.CODEC_INFO_AVC;
import static androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig.CODEC_INFO_RAW;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static androidx.media3.transformer.TestUtil.FPS_10;
import static androidx.media3.transformer.TestUtil.FPS_30;
import static androidx.media3.transformer.TestUtil.FPS_60;
import static androidx.media3.transformer.TestUtil.assertTimestampsMatchFrameRate;
import static androidx.media3.transformer.TestUtil.buildComposition;
import static androidx.media3.transformer.TestUtil.getQueuedContentTimesUs;
import static androidx.media3.transformer.TestUtil.setupAndPrepareHardwareBufferPlayer;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.util.Rational;
import androidx.media3.test.utils.CapturingFrameProcessor;
import androidx.media3.test.utils.FakeFrameProcessor;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameter;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** Robolectric parameterized seek tests for {@link CompositionPlayer}. */
@RunWith(RobolectricTestParameterInjector.class)
public final class CompositionPlayerSeekTest {

  @Rule
  public ShadowMediaCodecConfig shadowMediaCodecConfig =
      ShadowMediaCodecConfig.withCodecs(
          /* decoders= */ ImmutableList.of(CODEC_INFO_AVC),
          /* encoders= */ ImmutableList.of(CODEC_INFO_RAW));

  @TestParameter boolean isScrubbingModeEnabled;

  @MonotonicNonNull CompositionPlayer player;

  private CapturingFrameProcessor.Factory frameProcessorFactory;

  @Before
  public void setUp() {
    frameProcessorFactory =
        new CapturingFrameProcessor.Factory(
            new FakeFrameProcessor.Factory(/* shouldCompleteIncomingFrames= */ true));
  }

  @After
  public void tearDown() {
    if (player != null) {
      player.release();
    }
  }

  @Test
  public void seek_withAggregationFpsHigherThanIntrinsic_outputsFramesAtTargetFps()
      throws Exception {
    // Asset (30):  ... | 167 |     | 200 |     | 233 | ... | 733 |     | 767 | ...
    // Target (60): ... | 167 | 183 | 200 | 217 | 233 | ... | 733 | 750 | 767 | ...
    //                                 ^                             ^
    // Seek request:                 200ms                         750ms
    //                                 |                             |
    //                                 |                             +-----+
    //                                 v                                   v
    // First output:                 200ms                               767ms
    //                                                     (Aggregator isn't aware of original seek
    //                                                      time and assumes it based on first
    //                                                      decoded frame of 767. Not important to
    //                                                      fix since content of a 750 would be
    //                                                      identical anyway.)
    Composition composition =
        buildComposition(
            ImmutableList.of(ImmutableList.of(MP4_ASSET_WITH_INCREASING_TIMESTAMPS)), FPS_60);

    runSeekAndPlaybackAssertingTimestamps(
        composition,
        ImmutableList.of(750L, 200L),
        /* expectedPostSeekTimestampsUs= */ ImmutableList.of(766_667L, 200_000L),
        /* targetFps= */ 60);
  }

  @Test
  public void seek_withAggregationFpsLowerThanIntrinsic_outputsFramesAtTargetFps()
      throws Exception {
    // Asset (30):  ... | 200 | 233 | 267 | 300 | ... | 700 | 733 | 767 | 800 | ...
    // Target (10): ... | 200 |           | 300 | ... | 700 |           | 800 | ...
    //                     ^     ^                                ^
    // Seek request:     200ms 233ms                            750ms
    //                     |     |                                |
    //                     |     +-----------+                    +--------+
    //                     v                 v                             v
    // First output:     200ms             300ms                          800ms
    Composition composition =
        buildComposition(
            ImmutableList.of(ImmutableList.of(MP4_ASSET_WITH_INCREASING_TIMESTAMPS)), FPS_10);

    runSeekAndPlaybackAssertingTimestamps(
        composition,
        ImmutableList.of(750L, 200L, 233L),
        /* expectedPostSeekTimestampsUs= */ ImmutableList.of(800_000L, 200_000L, 300_000L),
        /* targetFps= */ 10);
  }

  @Test
  public void seek_withAggregationFpsMatchingIntrinsic_outputsFramesAtTargetFps() throws Exception {
    // Asset (30):  ... | 167 |     | 200 |     | 233 | ... | 733 |     | 767 | ...
    // Target (30): ... | 167 |     | 200 |     | 233 | ... | 733 |     | 767 | ...
    //                                 ^                             ^
    // Seek request:                 200ms                         750ms
    //                                 |                             |
    //                                 |                             +-----+
    //                                 v                                   v
    // First output:                 200ms                               767ms
    Composition composition =
        buildComposition(
            ImmutableList.of(ImmutableList.of(MP4_ASSET_WITH_INCREASING_TIMESTAMPS)), FPS_30);

    runSeekAndPlaybackAssertingTimestamps(
        composition,
        ImmutableList.of(750L, 200L),
        /* expectedPostSeekTimestampsUs= */ ImmutableList.of(766_667L, 200_000L),
        /* targetFps= */ 30);
  }

  @Test
  public void seek_withAggregationFpsHigherThanIntrinsicAndBFrame_outputsFramesAtTargetFps()
      throws Exception {
    // Asset (30):  ... |200.2p| ...     |734.1b|      |767.4b|     |800.8b|     |834.2p| ...
    // Target (60): ... | 200  | 217 | ...      | 750 | 767  | 783 | 800  | 817 | 833  | 850  | ...
    //                     ^                       ^
    // Seek request:     200ms                   750ms
    //                     |                       |
    //                     +-----+                 +--------------------------------------+
    //                           v                                                        v
    // First output:           217ms                                                    850ms
    //         (Aggregator isn't aware of original seek                   (B-frames 767.4 and 800.8
    //          time and assumes it based on first                         are discarded post-seek;
    //          decoded frame of 200.2. Not important to                   first output is based
    //          fix since content of a 200.2 would be                      on P-frame 834.2 and
    //          identical anyway.)                                         next target tick is 850)
    Composition composition =
        buildComposition(ImmutableList.of(ImmutableList.of(MP4_ADVANCED_ASSET)), FPS_60);

    runSeekAndPlaybackAssertingTimestamps(
        composition,
        ImmutableList.of(750L, 200L),
        /* expectedPostSeekTimestampsUs= */ ImmutableList.of(850_000L, 216_667L),
        /* targetFps= */ 60);
  }

  @Test
  public void seek_withAggregationFpsLowerThanIntrinsicAndBFrame_outputsFramesAtTargetFps()
      throws Exception {
    // Asset (30):  ... |200.2p| ...        |701.7p|734.1b|767.4b|800.8b|834.2p|867.5b|900.9b| ...
    // Target (10): ... | 200  | 300  | ... | 700  |             | 800  |             | 900  | ...
    //                     ^                              ^
    // Seek request:     200ms                          750ms
    //                     |                              |
    //                     +-----+                        +-------------------------------+
    //                           v                                                        v
    // First output:           300ms                                                   900ms
    //         (Aggregator isn't aware of original seek                   (B-frames 767.4 and 800.8
    //          time and assumes it based on first                         are discarded post-seek;
    //          decoded frame of 200.2. Not important to                   first output is based
    //          fix since content of a 200.2 would be                      on P-frame 834.2 and
    //          identical anyway.)                                         next target tick is 900)
    Composition composition =
        buildComposition(ImmutableList.of(ImmutableList.of(MP4_ADVANCED_ASSET)), FPS_10);

    runSeekAndPlaybackAssertingTimestamps(
        composition,
        ImmutableList.of(750L, 200L),
        /* expectedPostSeekTimestampsUs= */ ImmutableList.of(900_000L, 300_000L),
        /* targetFps= */ 10);
  }

  @Test
  public void seek_withAggregationFpsAndVaryingFpsInSingleSequence_outputsFramesAtTargetFps()
      throws Exception {
    // Seek 400ms & 500ms (Asset 1 with 15 FPS):
    //   Asset (15):  ... | 400 |     | 467 |     | 533 | ...
    //   Target (30): ... | 400 | 433 | 467 | 500 | 533 | ...
    //                       ^                 ^
    //   Seek request:     400ms             500ms
    //                       |                 |
    //                       |                 +-----+
    //                       v                       v
    //   First output:     400ms                   533ms
    //
    // Seek 1490ms & 1600ms (Asset 2 with 30 FPS):
    //   Asset (30):      ... | 1497 | ...  | 1597 | 1630 | ...
    //   Target (30):     ...  | 1500 | ...  | 1600 | 1633 | ...
    //                            ^             ^
    //   Seek request:          1490ms        1600ms
    //                            |             |
    //                            +-----+       +-------+
    //                                  v               v
    //   First output:                1500ms          1633ms
    //
    // Seek 2130ms & 2500ms (Asset 3 with 60 FPS):
    //   Asset (60):  ... | 2130 | ... | 2497 | 2513 | 2530 | 2547 | 2563 | 2580 | 2597 | ...
    //   Target (30): ...  | 2133 | ...  | 2500 |      | 2533 |      | 2567 |      | 2600 | ...
    //                      ^               ^                                   ^
    //   Seek request:    2130ms          2500ms                              2590ms
    //                      |               |                                   |
    //                      ++              +-------------+                     +-----------+
    //                       v                            v                                 v
    //   First output:    2133ms                       2533ms                             2600ms
    Composition composition =
        buildComposition(
            ImmutableList.of(
                ImmutableList.of(
                    MP4_15FPS, // 15 FPS
                    MP4_ASSET_WITH_INCREASING_TIMESTAMPS, // 30 FPS
                    MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S)), // 60 FPS
            FPS_30);

    runSeekAndPlaybackAssertingTimestamps(
        composition,
        ImmutableList.of(1600L, 2590L, 400L, 1490L, 2130L, 500L, 2500L),
        /* expectedPostSeekTimestampsUs= */ ImmutableList.of(
            1_633_333L, 2_600_000L, 400_000L, 1_500_000L, 2_133_333L, 533_333L, 2_533_333L),
        /* targetFps= */ 30);
  }

  @Test
  public void seek_withAggregationFpsAndVaryingFpsInMultiSequence_outputsFramesAtTargetFps()
      throws Exception {
    // Parallel tracks:
    // 15 FPS Track: ... | 267 |     | 333 | ...         ... | 733 |                | 800 | ...
    // 30 FPS Track: ... | 267 | 300 | 333 | ... | 700 |     | 733 |     | 767 | ...
    // 60 FPS Track: ... | 267 | 300 | 333 | ... | 700 | 717 | 733 | 750 | 767 | ...
    // Target (30):  ... | 267 | 300 | 333 | ... | 700 |     | 733 |     | 767 | ...
    //                            ^                    ^              ^
    // Seek request:            300ms                710ms          750ms
    //                            |                    +--------+     +-----+
    //                            v                             v           v
    // First output:            300ms                         733ms       767ms
    Composition composition =
        buildComposition(
            ImmutableList.of(
                ImmutableList.of(MP4_15FPS), // 15 FPS
                ImmutableList.of(MP4_ASSET_WITH_INCREASING_TIMESTAMPS), // 30 FPS
                ImmutableList.of(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S)), // 60 FPS
            FPS_30);

    runSeekAndPlaybackAssertingTimestamps(
        composition,
        ImmutableList.of(710L, 300L, 750L),
        /* expectedPostSeekTimestampsUs= */ ImmutableList.of(733_333L, 300_000L, 766_667L),
        /* targetFps= */ 30);
  }

  private void runSeekAndPlaybackAssertingTimestamps(
      Composition composition,
      List<Long> seekTimesMs,
      List<Long> expectedPostSeekTimestampsUs,
      int targetFps)
      throws Exception {
    player = setupAndPrepareHardwareBufferPlayer(composition, frameProcessorFactory);
    CapturingFrameProcessor frameProcessor = frameProcessorFactory.getCreatedProcessor();

    runMainLooperUntil(() -> !getQueuedContentTimesUs(frameProcessor).isEmpty());
    ImmutableList<Long> initialTimestampsUs = getQueuedContentTimesUs(frameProcessor);
    player.setScrubbingModeEnabled(isScrubbingModeEnabled);
    int numSeeks = seekTimesMs.size();
    ImmutableList.Builder<Long> actualSeekTimestampsUs = ImmutableList.builder();
    ImmutableList<Long> queuedTimestampsUs = initialTimestampsUs;
    for (int i = 0; i < numSeeks; i++) {
      int indexOfSeekFrame = queuedTimestampsUs.size();
      player.seekTo(seekTimesMs.get(i));
      advance(player).untilState(STATE_READY);
      runMainLooperUntil(() -> getQueuedContentTimesUs(frameProcessor).size() > indexOfSeekFrame);
      queuedTimestampsUs = getQueuedContentTimesUs(frameProcessor);
      actualSeekTimestampsUs.add(queuedTimestampsUs.get(indexOfSeekFrame));
    }
    player.setScrubbingModeEnabled(false);
    int packetsQueuedBeforePlay = queuedTimestampsUs.size();
    player.play();
    advance(player).untilState(STATE_ENDED);

    ImmutableList<Long> finalTimestampsUs = getQueuedContentTimesUs(frameProcessor);
    ImmutableList<Long> resumedFrameTimestampsUs =
        finalTimestampsUs.subList(packetsQueuedBeforePlay - 1, finalTimestampsUs.size());
    Rational expectedFps = new Rational(targetFps, 1);
    assertWithMessage("Queued events: %s", frameProcessor.getQueuedEvents())
        .that(initialTimestampsUs)
        .isNotEmpty();
    assertThat(initialTimestampsUs.get(0)).isEqualTo(0);
    assertThat(actualSeekTimestampsUs.build()).isEqualTo(expectedPostSeekTimestampsUs);
    assertTimestampsMatchFrameRate(resumedFrameTimestampsUs, expectedFps);
  }
}
