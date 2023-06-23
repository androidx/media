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
package androidx.media3.cast;

import android.os.SystemClock;
import android.util.SparseArray;
import android.util.SparseIntArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Util;
import com.google.android.gms.cast.MediaInfo;
import java.util.Arrays;
import java.util.Objects;

/** A {@link Timeline} for Cast media queues. */
/* package */ final class CastTimeline extends Timeline {

  /** Holds {@link Timeline} related data for a Cast media item. */
  public static final class ItemData {

    /* package */ static final String UNKNOWN_CONTENT_ID = "UNKNOWN_CONTENT_ID";

    /** Holds no media information. */
    public static final ItemData EMPTY =
        new ItemData(
            /* windowDurationUs= */ C.TIME_UNSET,
            /* periodDurationUs= */ C.TIME_UNSET,
            /* windowStartOffsetUs= */ C.TIME_UNSET,
            /* isLive= */ false,
            /* isMovingLiveWindow= */ false,
            MediaItem.EMPTY,
            UNKNOWN_CONTENT_ID);

    /** The duration of the item in microseconds, or {@link C#TIME_UNSET} if unknown. */
    public final long windowDurationUs;
    /** The window offset from the beginning of the period in microseconds. */
    public final long windowStartOffsetUs;
    /**
     * The duration of the underlying period in microseconds, or {@link C#TIME_UNSET} if unknown.
     * For vod content this will match the window duration. For live content this will be
     * {@link C#TIME_UNSET} or the duration from 0 until the end of the stream.
     */
    public final long periodDurationUs;
    /** Whether the item is live content, or {@code false} if unknown. */
    public final boolean isLive;
    /**
     * Whether the current seekable range is a fixed-length moving window (true) or if it is an
     * expanding range (false). Only applicable if {@link #isLive} is true.
     */
    public final boolean isMovingLiveWindow;
    /** The original media item that has been set or added to the playlist. */
    public final MediaItem mediaItem;
    /** The {@linkplain MediaInfo#getContentId() content ID} of the cast media queue item. */
    public final String contentId;

    /**
     * Creates an instance.
     *
     * @param windowDurationUs See {@link #windowDurationsUs}.
     * @param periodDurationUs See {@link #periodDurationUs}.
     * @param windowStartOffsetUs See {@link #windowStartOffsetUs}
     * @param isLive See {@link #isLive}.
     * @param isMovingLiveWindow See {@link #isMovingLiveWindow}.
     * @param mediaItem See {@link #mediaItem}.
     * @param contentId See {@link #contentId}.
     */
    public ItemData(
        long windowDurationUs,
        long periodDurationUs,
        long windowStartOffsetUs,
        boolean isLive,
        boolean isMovingLiveWindow,
        MediaItem mediaItem,
        String contentId) {
      this.windowDurationUs = windowDurationUs;
      this.periodDurationUs = periodDurationUs;
      this.windowStartOffsetUs = windowStartOffsetUs;
      this.isLive = isLive;
      this.isMovingLiveWindow = isMovingLiveWindow;
      this.mediaItem = mediaItem;
      this.contentId = contentId;
    }

    /**
     * Returns a copy of this instance with the given values.
     *
     * @param windowDurationUs See {@link #windowDurationsUs}.
     * @param periodDurationUs See {@link #periodDurationUs}.
     * @param windowStartOffsetUs See {@link #windowStartOffsetUs}
     * @param isLive See {@link #isLive}.
     * @param isMovingLiveWindow See {@link #isMovingLiveWindow}.
     * @param mediaItem See {@link #mediaItem}.
     * @param contentId See {@link #contentId}.
     */
    public ItemData copyWithNewValues(
        long windowDurationUs,
        long periodDurationUs,
        long windowStartOffsetUs,
        boolean isLive,
        boolean isMovingLiveWindow,
        MediaItem mediaItem,
        String contentId) {
      if (windowDurationUs == this.windowDurationUs
          && periodDurationUs == this.periodDurationUs
          && windowStartOffsetUs == this.windowStartOffsetUs
          && isLive == this.isLive
          && isMovingLiveWindow == this.isMovingLiveWindow
          && contentId.equals(this.contentId)
          && mediaItem.equals(this.mediaItem)) {
        return this;
      }
      return new ItemData(windowDurationUs, periodDurationUs, windowStartOffsetUs,
          isLive, isMovingLiveWindow, mediaItem, contentId);
    }
  }

  /** {@link Timeline} for a cast queue that has no items. */
  public static final CastTimeline EMPTY_CAST_TIMELINE =
      new CastTimeline(new int[0], new SparseArray<>());

  private final SparseIntArray idsToIndex;
  private final MediaItem[] mediaItems;
  private final int[] ids;
  private final long[] windowDurationsUs;
  private final long[] periodDurationsUs;
  private final long[] windowStartOffsetUs;
  private final boolean[] isLive;
  private final boolean[] isMovingLiveWindow;
  private final long creationUnixTimeMs;

  /**
   * Creates a Cast timeline from the given data.
   *
   * @param itemIds The ids of the items in the timeline.
   * @param itemIdToData Maps item ids to {@link ItemData}.
   */
  public CastTimeline(int[] itemIds, SparseArray<ItemData> itemIdToData) {
    creationUnixTimeMs = SystemClock.elapsedRealtime();
    int itemCount = itemIds.length;
    idsToIndex = new SparseIntArray(itemCount);
    ids = Arrays.copyOf(itemIds, itemCount);
    windowDurationsUs = new long[itemCount];
    periodDurationsUs = new long[itemCount];
    windowStartOffsetUs = new long[itemCount];
    isLive = new boolean[itemCount];
    isMovingLiveWindow = new boolean[itemCount];
    mediaItems = new MediaItem[itemCount];
    for (int i = 0; i < ids.length; i++) {
      int id = ids[i];
      idsToIndex.put(id, i);
      ItemData data = itemIdToData.get(id, ItemData.EMPTY);
      mediaItems[i] = data.mediaItem;
      windowDurationsUs[i] = data.windowDurationUs;
      periodDurationsUs[i] = data.periodDurationUs;
      windowStartOffsetUs[i] = data.windowStartOffsetUs;
      isLive[i] = data.isLive;
      isMovingLiveWindow[i] = data.isMovingLiveWindow;
    }
  }

  // Timeline implementation.

  @Override
  public int getWindowCount() {
    return ids.length;
  }

  @Override
  public Window getWindow(int windowIndex, Window window, long defaultPositionProjectionUs) {
    long windowDurationUs = windowDurationsUs[windowIndex];
    long periodDurationsUs = this.periodDurationsUs[windowIndex];
    long windowStartOffsetUs = this.windowStartOffsetUs[windowIndex];

    // Account for the elapsed time since this Timeline was created.
    boolean windowIsLive = isLive[windowIndex];
    if (windowIsLive && windowDurationUs != C.TIME_UNSET) {
      long elapsedTimeUs = Util.msToUs(SystemClock.elapsedRealtime() - creationUnixTimeMs);
      if (isMovingLiveWindow[windowIndex]) {
        windowStartOffsetUs += elapsedTimeUs;
      } else {
        windowDurationUs += elapsedTimeUs;
      }
    }

    boolean isDynamic = windowDurationUs == C.TIME_UNSET || windowDurationUs != periodDurationsUs;
    return window.set(
        /* uid= */ ids[windowIndex],
        /* mediaItem= */ mediaItems[windowIndex],
        /* manifest= */ null,
        /* presentationStartTimeMs= */ C.TIME_UNSET,
        /* windowStartTimeMs= */ C.TIME_UNSET,
        /* elapsedRealtimeEpochOffsetMs= */ C.TIME_UNSET,
        /* isSeekable= */ windowDurationUs != C.TIME_UNSET,
        /* isDynamic= */ isDynamic,
        /* liveConfiguration= */ windowIsLive ? mediaItems[windowIndex].liveConfiguration : null,
        /* defaultPositionUs= */ (windowIsLive && isDynamic) ? windowDurationUs : 0,
        /* durationUs= */ windowDurationUs,
        /* firstPeriodIndex= */ windowIndex,
        /* lastPeriodIndex= */ windowIndex,
        /* positionInFirstPeriodUs= */ windowStartOffsetUs);
  }

  @Override
  public int getPeriodCount() {
    return ids.length;
  }

  @Override
  public Period getPeriod(int periodIndex, Period period, boolean setIds) {
    int id = ids[periodIndex];
    long positionInWindowUs = -windowStartOffsetUs[periodIndex];
    if (isLive[periodIndex] && isMovingLiveWindow[periodIndex]) {
      long elapsedTimeUs = Util.msToUs(SystemClock.elapsedRealtime() - creationUnixTimeMs);
      positionInWindowUs -= elapsedTimeUs;
    }
    return period.set(id, id, periodIndex, periodDurationsUs[periodIndex], positionInWindowUs);
  }

  @Override
  public int getIndexOfPeriod(Object uid) {
    return uid instanceof Integer ? idsToIndex.get((int) uid, C.INDEX_UNSET) : C.INDEX_UNSET;
  }

  @Override
  public Integer getUidOfPeriod(int periodIndex) {
    return ids[periodIndex];
  }

  // equals and hashCode implementations.

  @Override
  public boolean equals(@Nullable Object other) {
    if (this == other) {
      return true;
    } else if (!(other instanceof CastTimeline)) {
      return false;
    }
    CastTimeline that = (CastTimeline) other;
    return Arrays.equals(ids, that.ids)
        && creationUnixTimeMs == that.creationUnixTimeMs
        && Arrays.equals(windowDurationsUs, that.windowDurationsUs)
        && Arrays.equals(periodDurationsUs, that.periodDurationsUs)
        && Arrays.equals(windowStartOffsetUs, that.windowStartOffsetUs)
        && Arrays.equals(isLive, that.isLive)
        && Arrays.equals(isMovingLiveWindow, that.isMovingLiveWindow);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(super.hashCode(), creationUnixTimeMs);
    result = 31 * result + Arrays.hashCode(ids);
    result = 31 * result + Arrays.hashCode(windowDurationsUs);
    result = 31 * result + Arrays.hashCode(periodDurationsUs);
    result = 31 * result + Arrays.hashCode(windowStartOffsetUs);
    result = 31 * result + Arrays.hashCode(isLive);
    result = 31 * result + Arrays.hashCode(isMovingLiveWindow);
    return result;
  }
}
