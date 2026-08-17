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
package androidx.media3.cast;

import static androidx.media3.cast.CastTimeline.ItemData.UNKNOWN_CONTENT_ID;
import static com.google.common.base.Preconditions.checkNotNull;

import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Util;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaQueueItem;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.media.MediaQueue;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Creates {@link CastTimeline CastTimelines} from cast receiver app status updates.
 *
 * <p>This class keeps track of the duration reported by the current item to fill any missing
 * durations in the media queue items [See internal: b/65152553].
 */
/* package */ final class CastTimelineTracker {
  private static final String TAG = "CastTlTracker";

  // Maximum number of queue item IDs fetched from the remote client per pass. This is also the
  // cache size configured on MediaQueue to prevent cache evictions during multi-pass fetching.
  @VisibleForTesting /* package */ static final int MAX_FETCH_COUNT = 20;

  private final SparseArray<CastTimeline.ItemData> itemIdToData;
  private final MediaItemConverter mediaItemConverter;
  @VisibleForTesting /* package */ final HashMap<String, MediaItem> mediaItemsByContentId;

  /**
   * Creates an instance.
   *
   * @param mediaItemConverter The converter used to convert from a {@link MediaQueueItem} to a
   *     {@link MediaItem}.
   */
  public CastTimelineTracker(MediaItemConverter mediaItemConverter) {
    this.mediaItemConverter = mediaItemConverter;
    itemIdToData = new SparseArray<>();
    mediaItemsByContentId = new HashMap<>();
  }

  /**
   * Called when media items {@linkplain Player#setMediaItems have been set to the playlist} and are
   * sent to the cast playback queue. A future queue update of the {@link RemoteMediaClient} will
   * reflect this addition.
   *
   * @param mediaItems The media items that have been set.
   * @param mediaQueueItems The corresponding media queue items.
   */
  public void onMediaItemsSet(List<MediaItem> mediaItems, MediaQueueItem[] mediaQueueItems) {
    mediaItemsByContentId.clear();
    onMediaItemsAdded(mediaItems, mediaQueueItems);
  }

  /**
   * Called when media items {@linkplain Player#addMediaItems(List) have been added} and are sent to
   * the cast playback queue. A future queue update of the {@link RemoteMediaClient} will reflect
   * this addition.
   *
   * @param mediaItems The media items that have been added.
   * @param mediaQueueItems The corresponding media queue items.
   */
  public void onMediaItemsAdded(List<MediaItem> mediaItems, MediaQueueItem[] mediaQueueItems) {
    for (int i = 0; i < mediaItems.size(); i++) {
      mediaItemsByContentId.put(
          checkNotNull(mediaQueueItems[i].getMedia()).getContentId(), mediaItems.get(i));
    }
  }

  /**
   * Returns a {@link CastTimeline} that represents the state of the given {@code
   * remoteMediaClient}.
   *
   * <p>Returned timelines may contain values obtained from {@code remoteMediaClient} in previous
   * invocations of this method.
   *
   * @param remoteMediaClient The Cast media client.
   * @return A {@link CastTimeline} that represents the given {@code remoteMediaClient} status.
   */
  public CastTimeline getCastTimeline(RemoteMediaClient remoteMediaClient) {
    MediaQueue mediaQueue = remoteMediaClient.getMediaQueue();
    int[] itemIds = mediaQueue.getItemIds();
    if (itemIds.length > 0) {
      // Only remove unused items when there is something in the queue to avoid removing all entries
      // if the remote media client clears the queue temporarily. See [Internal ref: b/128825216].
      removeUnusedItemDataEntries(itemIds);
    }

    // TODO: Reset state when the app instance changes [Internal ref: b/129672468].
    MediaStatus mediaStatus = remoteMediaClient.getMediaStatus();
    if (mediaStatus == null || mediaStatus.getMediaInfo() == null) {
      return CastTimeline.EMPTY_CAST_TIMELINE;
    }

    for (MediaQueueItem queueItem : mediaStatus.getQueueItems()) {
      updateItemDataFromQueueItem(queueItem);
    }

    int currentItemId = mediaStatus.getCurrentItemId();
    int currentItemIndex = Util.linearSearch(itemIds, currentItemId);
    if (currentItemIndex == C.INDEX_UNSET) {
      // This is not expected to happen, but prevents us from running out of bounds in the following
      // loop.
      currentItemIndex = 0;
    }

    // Fetch missing item metadata starting from the current playback index forward.
    // To prevent silent evictions in the MediaQueue fetch buffer (which is hard-capped at
    // MediaQueue's internal DEFAULT_MAX_FETCH_COUNT = 20 items), we cap network fetch triggers per
    // pass to MAX_FETCH_COUNT and fall back to cache-only lookups (fetchIfNeeded = false) for
    // remaining items.
    int fetchCount = 0;
    for (int step = 0; step < itemIds.length; step++) {
      int i = (currentItemIndex + step) % itemIds.length;
      int itemId = itemIds[i];
      CastTimeline.ItemData itemData = itemIdToData.get(itemId);
      if (itemData == null || itemData.mediaItem == MediaItem.EMPTY) {
        boolean fetchIfNeeded = fetchCount < MAX_FETCH_COUNT;
        MediaQueueItem queueItem = mediaQueue.getItemAtIndex(i, fetchIfNeeded);
        if (queueItem != null) {
          updateItemDataFromQueueItem(queueItem);
        } else if (fetchIfNeeded) {
          fetchCount++;
        }
      }
    }

    // Process mediaStatus.getMediaInfo() after mediaStatus.getQueueItems()[...].getMedia(). Static
    // queue items in the Cast SDK do not receive dynamic runtime updates (such as transitioning
    // from buffered to live upon manifest loading). Updating from mediaStatus.getMediaInfo() second
    // ensures that active runtime playback state is preserved and not overwritten by stale queue
    // item metadata.
    MediaInfo currentMediaInfo = checkNotNull(mediaStatus.getMediaInfo());
    String currentContentId = currentMediaInfo.getContentId();
    MediaItem mediaItem = mediaItemsByContentId.get(currentContentId);
    updateItemData(
        currentItemId,
        mediaItem != null ? mediaItem : MediaItem.EMPTY,
        currentMediaInfo,
        currentContentId,
        /* defaultPositionUs= */ C.TIME_UNSET);

    return new CastTimeline(itemIds, itemIdToData);
  }

  private void updateItemDataFromQueueItem(MediaQueueItem queueItem) {
    long defaultPositionUs = (long) (queueItem.getStartTime() * C.MICROS_PER_SECOND);
    @Nullable MediaInfo mediaInfo = queueItem.getMedia();
    String contentId = mediaInfo != null ? mediaInfo.getContentId() : UNKNOWN_CONTENT_ID;
    @Nullable MediaItem mediaItem = mediaItemsByContentId.get(contentId);
    if (mediaItem == null) {
      try {
        mediaItem = mediaItemConverter.toMediaItem(queueItem);
      } catch (Exception e) {
        // TODO(b/524966241): Remove try/catch once the converter doesn't throw parsing
        // exceptions.
        Log.w(TAG, "Failed to convert MediaQueueItem to MediaItem");
      }
    }
    updateItemData(
        queueItem.getItemId(),
        mediaItem != null ? mediaItem : MediaItem.EMPTY,
        mediaInfo,
        contentId,
        defaultPositionUs);
  }

  private void updateItemData(
      int itemId,
      MediaItem mediaItem,
      @Nullable MediaInfo mediaInfo,
      String contentId,
      long defaultPositionUs) {
    CastTimeline.ItemData previousData = itemIdToData.get(itemId, CastTimeline.ItemData.EMPTY);
    long durationUs = CastUtils.getStreamDurationUs(mediaInfo);
    if (durationUs == C.TIME_UNSET) {
      durationUs = previousData.durationUs;
    }
    boolean isLive =
        mediaInfo == null
            ? previousData.isLive
            : mediaInfo.getStreamType() == MediaInfo.STREAM_TYPE_LIVE;
    if (defaultPositionUs == C.TIME_UNSET) {
      defaultPositionUs = previousData.defaultPositionUs;
    }
    // The media item can be empty if it originates from another sender. We use previous data as
    // itemIdToData is populated when all media items from the queue are updated.
    if (mediaItem == MediaItem.EMPTY) {
      mediaItem = previousData.mediaItem;
    }
    itemIdToData.put(
        itemId,
        previousData.copyWithNewValues(
            durationUs, defaultPositionUs, isLive, mediaItem, contentId));
  }

  private void removeUnusedItemDataEntries(int[] itemIds) {
    HashSet<Integer> scratchItemIds = new HashSet<>(/* initialCapacity= */ itemIds.length * 2);
    for (int id : itemIds) {
      scratchItemIds.add(id);
    }

    int index = 0;
    while (index < itemIdToData.size()) {
      if (!scratchItemIds.contains(itemIdToData.keyAt(index))) {
        CastTimeline.ItemData itemData = itemIdToData.valueAt(index);
        mediaItemsByContentId.remove(itemData.contentId);
        itemIdToData.removeAt(index);
      } else {
        index++;
      }
    }
  }
}
