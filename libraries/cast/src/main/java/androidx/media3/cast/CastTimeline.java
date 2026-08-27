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

import androidx.annotation.Nullable;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import com.google.android.gms.cast.MediaInfo;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A {@link Timeline} for Cast media queues. */
/* package */ final class CastTimeline extends Timeline {

  /** An identifier for a period or window in a {@link CastTimeline}. */
  public static final class ItemUid {
    private final String value;

    private ItemUid(String value) {
      this.value = value;
    }

    public static ItemUid generateItemUid() {
      return new ItemUid(UUID.randomUUID().toString());
    }

    @Override
    public boolean equals(@Nullable Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof ItemUid)) {
        return false;
      }
      return value.equals(((ItemUid) other).value);
    }

    @Override
    public int hashCode() {
      return value.hashCode();
    }

    @Override
    public String toString() {
      return value;
    }
  }

  /** Holds {@link Timeline} related data for a Cast media item. */
  public static final class ItemData {

    /* package */ static final String UNKNOWN_CONTENT_ID = "UNKNOWN_CONTENT_ID";

    /** Holds no media information. */
    public static final ItemData EMPTY =
        new ItemData(
            /* durationUs= */ C.TIME_UNSET,
            /* defaultPositionUs= */ C.TIME_UNSET,
            /* isLive= */ false,
            MediaItem.EMPTY,
            UNKNOWN_CONTENT_ID);

    /** The duration of the item in microseconds, or {@link C#TIME_UNSET} if unknown. */
    public final long durationUs;

    /**
     * The default start position of the item in microseconds, or {@link C#TIME_UNSET} if unknown.
     */
    public final long defaultPositionUs;

    /** Whether the item is live content, or {@code false} if unknown. */
    public final boolean isLive;

    /** The original media item that has been set or added to the playlist. */
    public final MediaItem mediaItem;

    /** The {@linkplain MediaInfo#getContentId() content ID} of the cast media queue item. */
    public final String contentId;

    /**
     * Creates an instance.
     *
     * @param durationUs See {@link #durationsUs}.
     * @param defaultPositionUs See {@link #defaultPositionUs}.
     * @param isLive See {@link #isLive}.
     * @param mediaItem See {@link #mediaItem}.
     * @param contentId See {@link #contentId}.
     */
    public ItemData(
        long durationUs,
        long defaultPositionUs,
        boolean isLive,
        MediaItem mediaItem,
        String contentId) {
      this.durationUs = durationUs;
      this.defaultPositionUs = defaultPositionUs;
      this.isLive = isLive;
      this.mediaItem = mediaItem;
      this.contentId = contentId;
    }

    /**
     * Returns a copy of this instance with the given values.
     *
     * @param durationUs The duration in microseconds, or {@link C#TIME_UNSET} if unknown.
     * @param defaultPositionUs The default start position in microseconds, or {@link C#TIME_UNSET}
     *     if unknown.
     * @param isLive Whether the item is live, or {@code false} if unknown.
     * @param mediaItem The media item.
     * @param contentId The content ID.
     */
    public ItemData copyWithNewValues(
        long durationUs,
        long defaultPositionUs,
        boolean isLive,
        MediaItem mediaItem,
        String contentId) {
      if (durationUs == this.durationUs
          && defaultPositionUs == this.defaultPositionUs
          && isLive == this.isLive
          && contentId.equals(this.contentId)
          && mediaItem.equals(this.mediaItem)) {
        return this;
      }
      return new ItemData(durationUs, defaultPositionUs, isLive, mediaItem, contentId);
    }
  }

  /** {@link Timeline} for a cast queue that has no items. */
  public static final CastTimeline EMPTY_CAST_TIMELINE =
      new CastTimeline(ImmutableList.of(), ImmutableMap.of());

  // In CastTimeline, periods and windows have a 1:1 correspondence. Period IDs and window UIDs are
  // used interchangeably in this class.
  private final Map<ItemUid, Integer> periodUidsToIndex;
  private final ImmutableList<MediaItem> mediaItems;
  private final ImmutableList<ItemUid> periodUids;
  private final long[] durationsUs;
  private final long[] defaultPositionsUs;
  private final boolean[] isLive;

  /**
   * Creates a Cast timeline from the given data.
   *
   * @param itemIds The ids of the items in the timeline.
   * @param itemIdToData Maps item ids to {@link ItemData}.
   */
  public CastTimeline(List<ItemUid> itemIds, Map<ItemUid, ItemData> itemIdToData) {
    int itemCount = itemIds.size();
    periodUids = ImmutableList.copyOf(itemIds);
    periodUidsToIndex = new HashMap<>(itemCount);
    durationsUs = new long[itemCount];
    defaultPositionsUs = new long[itemCount];
    isLive = new boolean[itemCount];
    ImmutableList.Builder<MediaItem> mediaItemsBuilder =
        ImmutableList.builderWithExpectedSize(itemCount);
    for (int i = 0; i < periodUids.size(); i++) {
      ItemUid id = periodUids.get(i);
      periodUidsToIndex.put(id, i);
      ItemData data = itemIdToData.get(id);
      if (data == null) {
        data = ItemData.EMPTY;
      }
      mediaItemsBuilder.add(data.mediaItem);
      durationsUs[i] = data.durationUs;
      defaultPositionsUs[i] = data.defaultPositionUs == C.TIME_UNSET ? 0 : data.defaultPositionUs;
      isLive[i] = data.isLive;
    }
    mediaItems = mediaItemsBuilder.build();
  }

  // Timeline implementation.

  @Override
  public int getWindowCount() {
    return periodUids.size();
  }

  @Override
  public Window getWindow(int windowIndex, Window window, long defaultPositionProjectionUs) {
    long durationUs = durationsUs[windowIndex];
    boolean isDynamic = durationUs == C.TIME_UNSET;
    return window.set(
        /* uid= */ periodUids.get(windowIndex),
        /* mediaItem= */ mediaItems.get(windowIndex),
        /* manifest= */ null,
        /* presentationStartTimeMs= */ C.TIME_UNSET,
        /* windowStartTimeMs= */ C.TIME_UNSET,
        /* elapsedRealtimeEpochOffsetMs= */ C.TIME_UNSET,
        /* isSeekable= */ !isDynamic,
        isDynamic,
        isLive[windowIndex] ? mediaItems.get(windowIndex).liveConfiguration : null,
        defaultPositionsUs[windowIndex],
        durationUs,
        /* firstPeriodIndex= */ windowIndex,
        /* lastPeriodIndex= */ windowIndex,
        /* positionInFirstPeriodUs= */ 0);
  }

  @Override
  public int getPeriodCount() {
    return periodUids.size();
  }

  @Override
  public Period getPeriod(int periodIndex, Period period, boolean setIds) {
    ItemUid id = periodUids.get(periodIndex);
    return period.set(
        /* id= */ id,
        /* uid= */ id,
        periodIndex,
        durationsUs[periodIndex],
        /* positionInWindowUs= */ 0,
        AdPlaybackState.NONE,
        /* isPlaceholder= */ false);
  }

  @Override
  public int getIndexOfPeriod(Object uid) {
    if (!(uid instanceof ItemUid)) {
      return C.INDEX_UNSET;
    }
    Integer index = periodUidsToIndex.get(uid);
    return index != null ? index : C.INDEX_UNSET;
  }

  @Override
  public Object getUidOfPeriod(int periodIndex) {
    return periodUids.get(periodIndex);
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
    return periodUids.equals(that.periodUids)
        && Arrays.equals(durationsUs, that.durationsUs)
        && Arrays.equals(defaultPositionsUs, that.defaultPositionsUs)
        && Arrays.equals(isLive, that.isLive)
        && mediaItems.equals(that.mediaItems);
  }

  @Override
  public int hashCode() {
    int result = periodUids.hashCode();
    result = 31 * result + Arrays.hashCode(durationsUs);
    result = 31 * result + Arrays.hashCode(defaultPositionsUs);
    result = 31 * result + Arrays.hashCode(isLive);
    result = 31 * result + mediaItems.hashCode();
    return result;
  }
}
