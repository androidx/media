/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.source.ads;

import static androidx.media3.common.C.INDEX_UNSET;
import static androidx.media3.common.C.MICROS_PER_SECOND;
import static androidx.media3.common.C.TIME_END_OF_SOURCE;
import static androidx.media3.common.C.TIME_UNSET;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.Timeline.Period;
import androidx.media3.test.utils.FakeTimeline;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link AdTimeline}. */
@RunWith(AndroidJUnit4.class)
public class AdTimelineTest {

  @Test
  public void getPeriod_multiPeriod_returnsCorrectAdPlaybackStateForEachPeriod() {
    String windowId = "windowId";
    FakeTimeline contentTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder()
                .setPeriodCount(3)
                .setUid(windowId)
                .setSeekable(true)
                .setDurationUs(60 * MICROS_PER_SECOND)
                .setWindowPositionInFirstPeriodUs(0)
                .build());
    AdTimeline adTimeline =
        new AdTimeline(
            contentTimeline,
            new AdPlaybackState(
                "adsId",
                0L,
                10 * MICROS_PER_SECOND, // Period 0:  0s - 20s
                25 * MICROS_PER_SECOND,
                35 * MICROS_PER_SECOND, // Period 1: 20s - 40s
                45 * MICROS_PER_SECOND,
                55 * MICROS_PER_SECOND // Period 2: 40s - 60s
                ));
    Period period0 = new Period();

    adTimeline.getPeriod(0, period0);

    // Period durations are uniformly split windowDuration/periodCount
    assertThat(period0.durationUs).isEqualTo(20 * MICROS_PER_SECOND);

    // Positions within the 0th period
    assertThat(period0.getAdGroupIndexForPositionUs(1 * MICROS_PER_SECOND)).isEqualTo(0);
    assertThat(period0.getAdGroupIndexAfterPositionUs(1 * MICROS_PER_SECOND)).isEqualTo(1);
    assertThat(period0.getAdGroupIndexForPositionUs(19 * MICROS_PER_SECOND)).isEqualTo(1);
    // No more ads to be played in 0th period
    assertThat(period0.getAdGroupIndexAfterPositionUs(19 * MICROS_PER_SECOND))
        .isEqualTo(INDEX_UNSET);

    Period period1 = new Period();

    adTimeline.getPeriod(1, period1);

    // Positions within the 1st period
    assertThat(period1.getAdGroupIndexForPositionUs(1 * MICROS_PER_SECOND)).isEqualTo(1); // 21s
    assertThat(period1.getAdGroupIndexAfterPositionUs(1 * MICROS_PER_SECOND)).isEqualTo(2); // 21s
    assertThat(period1.getAdGroupIndexForPositionUs(10 * MICROS_PER_SECOND)).isEqualTo(2); // 30s
    assertThat(period1.getAdGroupIndexAfterPositionUs(10 * MICROS_PER_SECOND)).isEqualTo(3); // 30s
    assertThat(period1.getAdGroupIndexForPositionUs(19 * MICROS_PER_SECOND)).isEqualTo(3); // 39s
    // No more ads to be played in 1st period
    assertThat(period1.getAdGroupIndexAfterPositionUs(19 * MICROS_PER_SECOND))
        .isEqualTo(INDEX_UNSET); // 39s

    Period period2 = new Period();

    adTimeline.getPeriod(2, period2);

    // Positions within the 2nd period
    assertThat(period2.getAdGroupIndexForPositionUs(1 * MICROS_PER_SECOND)).isEqualTo(3); // 41s
    assertThat(period2.getAdGroupIndexAfterPositionUs(1 * MICROS_PER_SECOND)).isEqualTo(4); // 41s
    assertThat(period2.getAdGroupIndexForPositionUs(10 * MICROS_PER_SECOND)).isEqualTo(4); // 50s
    assertThat(period2.getAdGroupIndexAfterPositionUs(10 * MICROS_PER_SECOND)).isEqualTo(5); // 50s
    assertThat(period2.getAdGroupIndexForPositionUs(19 * MICROS_PER_SECOND)).isEqualTo(5); // 59s
    // No more ads to be played in 2nd period
    assertThat(period2.getAdGroupIndexAfterPositionUs(19 * MICROS_PER_SECOND))
        .isEqualTo(INDEX_UNSET); // 59s
  }

  @Test
  public void getPeriod_postRoll_onlyKeptInLastPeriod() {
    String windowId = "windowId";
    FakeTimeline contentTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder()
                .setPeriodCount(2)
                .setUid(windowId)
                .setSeekable(true)
                .setDurationUs(60 * MICROS_PER_SECOND)
                .setWindowPositionInFirstPeriodUs(0)
                .build());
    AdTimeline adTimeline =
        new AdTimeline(
            contentTimeline,
            new AdPlaybackState(
                "adsId", TIME_END_OF_SOURCE // Period 1: 30s - 60s
                ));
    Period period0 = new Period();

    adTimeline.getPeriod(0, period0);

    // Period durations are uniformly split windowDuration/periodCount
    assertThat(period0.durationUs).isEqualTo(30 * MICROS_PER_SECOND);
    assertThat(period0.getAdGroupIndexForPositionUs(15 * MICROS_PER_SECOND)).isEqualTo(INDEX_UNSET);
    // Post-roll should not be played in 0th period
    assertThat(period0.getAdGroupIndexAfterPositionUs(15 * MICROS_PER_SECOND))
        .isEqualTo(INDEX_UNSET);

    Period period1 = new Period();

    adTimeline.getPeriod(1, period1);

    assertThat(period1.getAdGroupIndexForPositionUs(29 * MICROS_PER_SECOND))
        .isEqualTo(INDEX_UNSET); // 59s
    // Post-roll in the end
    assertThat(period1.getAdGroupIndexAfterPositionUs(29 * MICROS_PER_SECOND)).isEqualTo(0); // 59s
  }

  @Test
  public void getPeriod_setsCorrectContentDuration() {
    String windowId = "windowId";
    FakeTimeline contentTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder()
                .setPeriodCount(2)
                .setUid(windowId)
                .setSeekable(true)
                .setDurationUs(50 * MICROS_PER_SECOND)
                .setWindowPositionInFirstPeriodUs(0)
                .build());
    AdPlaybackState adPlaybackState =
        new AdPlaybackState("adsId", 10 * MICROS_PER_SECOND)
            .withContentDurationUs(50 * MICROS_PER_SECOND);
    AdTimeline adTimeline = new AdTimeline(contentTimeline, adPlaybackState);
    Period period0 = new Period();
    Period period1 = new Period();

    adTimeline.getPeriod(0, period0);
    adTimeline.getPeriod(1, period1);

    // Period 0 duration is 25s. Content duration in AdPlaybackState should be adjusted to 25s.
    assertThat(period0.adPlaybackState.contentDurationUs).isEqualTo(25 * MICROS_PER_SECOND);
    // Period 1 duration is 25s. Content duration in AdPlaybackState should be adjusted to 25s.
    assertThat(period1.adPlaybackState.contentDurationUs).isEqualTo(25 * MICROS_PER_SECOND);
  }

  @Test
  public void getPeriod_lastPeriodUnsetDuration_fallsBackToAdPlaybackStateContentDuration() {
    String windowId = "windowId";
    FakeTimeline contentTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder()
                .setPeriodCount(1)
                .setUid(windowId)
                .setSeekable(true)
                .setDurationUs(TIME_UNSET)
                .setWindowPositionInFirstPeriodUs(0)
                .build());
    AdPlaybackState adPlaybackState =
        new AdPlaybackState("adsId", 10 * MICROS_PER_SECOND)
            .withContentDurationUs(50 * MICROS_PER_SECOND);
    AdTimeline adTimeline = new AdTimeline(contentTimeline, adPlaybackState);
    Period period = new Period();

    adTimeline.getPeriod(0, period);

    // Content duration should fall back to adPlaybackState.contentDurationUs (50s) because period
    // duration is unset.
    assertThat(period.durationUs).isEqualTo(50 * MICROS_PER_SECOND);
    assertThat(period.adPlaybackState.contentDurationUs).isEqualTo(50 * MICROS_PER_SECOND);
  }
}
