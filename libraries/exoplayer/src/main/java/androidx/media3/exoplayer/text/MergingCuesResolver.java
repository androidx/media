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

package androidx.media3.exoplayer.text;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Math.max;
import static java.lang.Math.min;

import androidx.media3.common.C;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.extractor.text.CuesWithTiming;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A {@link CuesResolver} which merges possibly-overlapping {@link CuesWithTiming} instances.
 *
 * <p>This implementation only accepts with {@link CuesWithTiming} with a set {@link
 * CuesWithTiming#durationUs}.
 */
// TODO: b/181312195 - Add memoization
/* package */ final class MergingCuesResolver implements CuesResolver {

  /**
   * A {@link Comparator} which sorts cues in ascending display priority, for compatibility with the
   * ordering defined for {@link CueGroup#cues}.
   *
   * <p>Sorts first by start time ascending (later cues should be shown on top of older ones), then
   * by duration descending (shorter duration cues that start at the same time should be shown on
   * top, as the one underneath will be visible after they disappear).
   */
  private static final Comparator<CuesWithTiming> CUES_DISPLAY_PRIORITY_COMPARATOR =
      Comparator.comparingLong((CuesWithTiming c) -> c.startTimeUs)
          .thenComparing(Comparator.comparingLong((CuesWithTiming c) -> c.durationUs).reversed());

  /** Sorted by {@link CuesWithTiming#startTimeUs} ascending. */
  private final List<CuesWithTiming> cuesWithTimingList;

  public MergingCuesResolver() {
    cuesWithTimingList = new ArrayList<>();
  }

  @Override
  public boolean addCues(CuesWithTiming cues, long currentPositionUs) {
    checkArgument(cues.startTimeUs != C.TIME_UNSET);
    checkArgument(cues.durationUs != C.TIME_UNSET);
    boolean cuesAreShownAtCurrentTime =
        cues.startTimeUs <= currentPositionUs && currentPositionUs < cues.endTimeUs;
    for (int i = cuesWithTimingList.size() - 1; i >= 0; i--) {
      if (cues.startTimeUs >= cuesWithTimingList.get(i).startTimeUs) {
        cuesWithTimingList.add(i + 1, cues);
        return cuesAreShownAtCurrentTime;
      }
    }
    cuesWithTimingList.add(0, cues);
    return cuesAreShownAtCurrentTime;
  }

  @Override
  public ImmutableList<Cue> getCuesAtTimeUs(long timeUs) {
    if (cuesWithTimingList.isEmpty() || timeUs < cuesWithTimingList.get(0).startTimeUs) {
      return ImmutableList.of();
    }

    List<CuesWithTiming> visibleCues = new ArrayList<>();
    for (int i = 0; i < cuesWithTimingList.size(); i++) {
      CuesWithTiming cues = cuesWithTimingList.get(i);
      if (timeUs >= cues.startTimeUs && timeUs < cues.endTimeUs) {
        visibleCues.add(cues);
      }
      if (timeUs < cues.startTimeUs) {
        break;
      }
    }
    ImmutableList<CuesWithTiming> sortedResult =
        ImmutableList.sortedCopyOf(CUES_DISPLAY_PRIORITY_COMPARATOR, visibleCues);
    ImmutableList.Builder<Cue> result = ImmutableList.builder();
    for (int i = 0; i < sortedResult.size(); i++) {
      result.addAll(sortedResult.get(i).cues);
    }
    return result.build();
  }

  @Override
  public void discardCuesBeforeTimeUs(long timeUs) {
    for (int i = 0; i < cuesWithTimingList.size(); i++) {
      long startTimeUs = cuesWithTimingList.get(i).startTimeUs;
      if (timeUs > startTimeUs && timeUs > cuesWithTimingList.get(i).endTimeUs) {
        // In most cases only a single item will be removed in each invocation of this method, so
        // the inefficiency of removing items one-by-one inside a loop is mitigated.
        cuesWithTimingList.remove(i);
        i--;
      } else if (timeUs < startTimeUs) {
        break;
      }
    }
  }

  @Override
  public long getPreviousCueChangeTimeUs(long timeUs) {
    if (cuesWithTimingList.isEmpty() || timeUs < cuesWithTimingList.get(0).startTimeUs) {
      return C.TIME_UNSET;
    }
    long result = cuesWithTimingList.get(0).startTimeUs;
    for (int i = 0; i < cuesWithTimingList.size(); i++) {
      long startTimeUs = cuesWithTimingList.get(i).startTimeUs;
      long endTimeUs = cuesWithTimingList.get(i).endTimeUs;
      if (endTimeUs <= timeUs) {
        result = max(result, endTimeUs);
      } else if (startTimeUs <= timeUs) {
        result = max(result, startTimeUs);
      } else {
        break;
      }
    }
    return result;
  }

  @Override
  public long getNextCueChangeTimeUs(long timeUs) {
    long result = C.TIME_UNSET;
    for (int i = 0; i < cuesWithTimingList.size(); i++) {
      long startTimeUs = cuesWithTimingList.get(i).startTimeUs;
      long endTimeUs = cuesWithTimingList.get(i).endTimeUs;
      if (timeUs < startTimeUs) {
        result = result == C.TIME_UNSET ? startTimeUs : min(result, startTimeUs);
        break;
      } else if (timeUs < endTimeUs) {
        result = result == C.TIME_UNSET ? endTimeUs : min(result, endTimeUs);
      }
    }
    return result != C.TIME_UNSET ? result : C.TIME_END_OF_SOURCE;
  }

  @Override
  public void clear() {
    cuesWithTimingList.clear();
  }
}
