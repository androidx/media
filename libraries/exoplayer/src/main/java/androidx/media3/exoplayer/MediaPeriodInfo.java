/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.media3.exoplayer;

import static com.google.common.base.Preconditions.checkArgument;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import java.util.Objects;

/** Stores the information required to load and play a {@link MediaPeriod}. */
/* package */ final class MediaPeriodInfo {

  /** The media period's identifier. */
  public final MediaPeriodId id;

  /** The start position of the media to play within the media period, in microseconds. */
  public final long startPositionUs;

  /**
   * The applied forward projection of the start position when preloading live streams in
   * microseconds, or {@link C#TIME_UNSET} if no projection was applied.
   */
  public final long liveStreamStartPositionProjectionUs;

  /**
   * The requested next start position for the current timeline period, in microseconds, or {@link
   * C#TIME_UNSET} if the period was requested to start at its default position.
   *
   * <p>Note that if {@link #id} refers to an ad, this is the requested start position for the
   * suspended content.
   */
  public final long requestedContentPositionUs;

  /**
   * The duration of the media period in microseconds, or {@link C#TIME_UNSET} if not known. Note
   * that the actual duration may be clipped in order to play a following ad group.
   */
  public final long durationUs;

  /**
   * Whether this media period is followed by a transition to another media period of the same
   * server-side inserted ad stream. If true, {@link #isLastInTimelinePeriod}, {@link
   * #isLastInTimelineWindow} and {@link #isFinal} will all be false.
   */
  public final boolean isFollowedByTransitionToSameStream;

  /**
   * Whether this is the last media period in its timeline period (e.g., a postroll ad, or a media
   * period corresponding to a timeline period without ads).
   */
  public final boolean isLastInTimelinePeriod;

  /** Whether this is the last media period in its timeline window. */
  public final boolean isLastInTimelineWindow;

  /**
   * Whether this is the last media period in the entire timeline. If true, {@link
   * #isLastInTimelinePeriod} will also be true.
   */
  public final boolean isFinal;

  MediaPeriodInfo(
      MediaPeriodId id,
      long startPositionUs,
      long liveStreamStartPositionProjectionUs,
      long requestedContentPositionUs,
      long durationUs,
      boolean isFollowedByTransitionToSameStream,
      boolean isLastInTimelinePeriod,
      boolean isLastInTimelineWindow,
      boolean isFinal) {
    checkArgument(!isFinal || isLastInTimelinePeriod);
    checkArgument(!isLastInTimelineWindow || isLastInTimelinePeriod);
    checkArgument(
        !isFollowedByTransitionToSameStream
            || (!isLastInTimelinePeriod && !isLastInTimelineWindow && !isFinal));
    this.id = id;
    this.startPositionUs = startPositionUs;
    this.liveStreamStartPositionProjectionUs = liveStreamStartPositionProjectionUs;
    this.requestedContentPositionUs = requestedContentPositionUs;
    this.durationUs = durationUs;
    this.isFollowedByTransitionToSameStream = isFollowedByTransitionToSameStream;
    this.isLastInTimelinePeriod = isLastInTimelinePeriod;
    this.isLastInTimelineWindow = isLastInTimelineWindow;
    this.isFinal = isFinal;
  }

  /**
   * Returns a copy of this instance with the start position and its projection set to the specified
   * value. May return the same instance if nothing changed.
   */
  public MediaPeriodInfo copyWithStartPositionUs(
      long startPositionUs, long liveStreamStartPositionProjectionUs) {
    return startPositionUs == this.startPositionUs
            && liveStreamStartPositionProjectionUs == this.liveStreamStartPositionProjectionUs
        ? this
        : new MediaPeriodInfo(
            id,
            startPositionUs,
            liveStreamStartPositionProjectionUs,
            requestedContentPositionUs,
            durationUs,
            isFollowedByTransitionToSameStream,
            isLastInTimelinePeriod,
            isLastInTimelineWindow,
            isFinal);
  }

  /**
   * Returns a copy of this instance with the requested content position set to the specified value.
   * May return the same instance if nothing changed.
   */
  public MediaPeriodInfo copyWithRequestedContentPositionUs(long requestedContentPositionUs) {
    return requestedContentPositionUs == this.requestedContentPositionUs
        ? this
        : new MediaPeriodInfo(
            id,
            startPositionUs,
            liveStreamStartPositionProjectionUs,
            requestedContentPositionUs,
            durationUs,
            isFollowedByTransitionToSameStream,
            isLastInTimelinePeriod,
            isLastInTimelineWindow,
            isFinal);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    MediaPeriodInfo that = (MediaPeriodInfo) o;
    return startPositionUs == that.startPositionUs
        && requestedContentPositionUs == that.requestedContentPositionUs
        && durationUs == that.durationUs
        && isFollowedByTransitionToSameStream == that.isFollowedByTransitionToSameStream
        && isLastInTimelinePeriod == that.isLastInTimelinePeriod
        && isLastInTimelineWindow == that.isLastInTimelineWindow
        && isFinal == that.isFinal
        && Objects.equals(id, that.id);
  }

  @Override
  public int hashCode() {
    int result = 17;
    result = 31 * result + id.hashCode();
    result = 31 * result + (int) startPositionUs;
    result = 31 * result + (int) requestedContentPositionUs;
    result = 31 * result + (int) durationUs;
    result = 31 * result + (isFollowedByTransitionToSameStream ? 1 : 0);
    result = 31 * result + (isLastInTimelinePeriod ? 1 : 0);
    result = 31 * result + (isLastInTimelineWindow ? 1 : 0);
    result = 31 * result + (isFinal ? 1 : 0);
    return result;
  }
}
