/*
 * Copyright (C) 2017 The Android Open Source Project
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

import androidx.annotation.VisibleForTesting;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Assertions;
import androidx.media3.exoplayer.source.ForwardingTimeline;


/**
 * A custom {@link Timeline} for sources that have {@link AdPlaybackState} split among multiple periods.
 * <br/>
 * For each period a modified {@link AdPlaybackState} is created for each period:
 * <ul>
 * <li> ad group time is offset relative to period start time </li>
 * <li> ad groups after period end time are marked as skipped </li>
 * <li> post-roll ad group is kept only for last period </li>
 * <li> ad group count and indices are kept unchanged </li>
 * </ul>
 */
@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
public final class MultiPeriodAdTimeline extends ForwardingTimeline {

  private final AdPlaybackState[] adPlaybackStates;

  /**
   * Creates a new timeline with a single period containing ads.
   *
   * @param contentTimeline The timeline of the content alongside which ads will be played.
   * @param adPlaybackState The state of the media's ads.
   */
  public MultiPeriodAdTimeline(Timeline contentTimeline, AdPlaybackState adPlaybackState) {
    super(contentTimeline);
    final int periodCount = contentTimeline.getPeriodCount();
    // for period count == 1 SinglePeriodAdTimeline should be used
    Assertions.checkState(periodCount > 1);
    Assertions.checkState(contentTimeline.getWindowCount() == 1);
    this.adPlaybackStates = new AdPlaybackState[periodCount];

    final Timeline.Period period = new Timeline.Period();
    long periodStartOffsetUs = 0;
    for (int periodIndex = 0; periodIndex < periodCount; periodIndex++) {
      timeline.getPeriod(periodIndex, period);
      final long periodDurationUs = period.durationUs;
      adPlaybackStates[periodIndex] = forPeriod(adPlaybackState, periodStartOffsetUs,
          periodDurationUs, periodIndex == periodCount - 1);
      periodStartOffsetUs += periodDurationUs;
    }
  }

  @Override
  public Period getPeriod(int periodIndex, Period period, boolean setIds) {
    timeline.getPeriod(periodIndex, period, setIds);

    period.set(
        period.id,
        period.uid,
        period.windowIndex,
        period.durationUs,
        period.getPositionInWindowUs(),
        adPlaybackStates[periodIndex],
        period.isPlaceholder);
    return period;
  }

  /**
   * @param adPlaybackState     original state is immutable always new modified copy is created
   * @param periodStartOffsetUs period start time offset from start of timeline (microseconds)
   * @param periodDurationUs    period duration (microseconds)
   * @param isLastPeriod        true if this is the last period
   * @return adPlaybackState modified for period
   */
  private AdPlaybackState forPeriod(
      AdPlaybackState adPlaybackState,
      long periodStartOffsetUs,
      long periodDurationUs,
      boolean isLastPeriod) {
    final long periodEndUs = periodStartOffsetUs + periodDurationUs;
    for (int adGroupIndex = 0; adGroupIndex < adPlaybackState.adGroupCount; adGroupIndex++) {
      final long adGroupTimeUs = adPlaybackState.getAdGroup(adGroupIndex).timeUs;
      if (adGroupTimeUs == C.TIME_END_OF_SOURCE) {
        if (!isLastPeriod) {
          adPlaybackState = adPlaybackState.withSkippedAdGroup(adGroupIndex);
        }
      } else {
        if (periodEndUs < adGroupTimeUs) {
          // this cue point belongs to next periods
          adPlaybackState = adPlaybackState.withSkippedAdGroup(adGroupIndex);
        }
        // start time relative to period start
        adPlaybackState = adPlaybackState.withAdGroupTimeUs(adGroupIndex,
            adGroupTimeUs - periodStartOffsetUs);
      }
    }
    return adPlaybackState;
  }

}
