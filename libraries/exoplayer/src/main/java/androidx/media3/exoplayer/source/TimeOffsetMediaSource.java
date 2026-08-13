/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.source;

import androidx.media3.exoplayer.upstream.Allocator;
import java.util.ArrayList;

/**
 * A {@link MediaSource} that applies a time offset to the timestamps of a wrapped {@link
 * MediaSource}, and allows updating the offset during playback.
 *
 * <p>A positive offset shifts the samples of the wrapped source to later positions on the playback
 * timeline, a negative offset shifts them to earlier positions.
 *
 * <p>The {@link androidx.media3.common.Timeline} of the wrapped source is not adjusted, so this
 * source is intended to be merged with another source that defines the timeline, for example in a
 * {@link MergingMediaSource}.
 */
/* package */ final class TimeOffsetMediaSource extends WrappingMediaSource {

  private final ArrayList<TimeOffsetMediaPeriod> activeMediaPeriods;

  private long timeOffsetUs;

  /**
   * Creates the time offset source.
   *
   * @param mediaSource The wrapped {@link MediaSource}.
   * @param timeOffsetUs The offset to apply to all timestamps coming from the wrapped source, in
   *     microseconds.
   */
  public TimeOffsetMediaSource(MediaSource mediaSource, long timeOffsetUs) {
    super(mediaSource);
    this.timeOffsetUs = timeOffsetUs;
    this.activeMediaPeriods = new ArrayList<>();
  }

  /**
   * Updates the offset that is applied to all timestamps coming from the wrapped source.
   *
   * <p>Must be called on the playback thread.
   *
   * <p>The new offset is applied to all future interactions with this source and its active
   * {@linkplain MediaPeriod media periods}. Data already read from the sample streams of active
   * periods is unaffected, see {@link TimeOffsetMediaPeriod#updateTimeOffsetUs(long)}.
   *
   * @param timeOffsetUs The offset to apply to all timestamps coming from the wrapped source, in
   *     microseconds.
   */
  public void setTimeOffsetUs(long timeOffsetUs) {
    this.timeOffsetUs = timeOffsetUs;
    for (int i = 0; i < activeMediaPeriods.size(); i++) {
      activeMediaPeriods.get(i).updateTimeOffsetUs(timeOffsetUs);
    }
  }

  @Override
  public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
    TimeOffsetMediaPeriod mediaPeriod =
        new TimeOffsetMediaPeriod(
            mediaSource.createPeriod(id, allocator, startPositionUs - timeOffsetUs), timeOffsetUs);
    activeMediaPeriods.add(mediaPeriod);
    return mediaPeriod;
  }

  @Override
  public void releasePeriod(MediaPeriod mediaPeriod) {
    TimeOffsetMediaPeriod timeOffsetMediaPeriod = (TimeOffsetMediaPeriod) mediaPeriod;
    activeMediaPeriods.remove(timeOffsetMediaPeriod);
    mediaSource.releasePeriod(timeOffsetMediaPeriod.getWrappedMediaPeriod());
  }
}
