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
import androidx.media3.cast.CastTimeline.ItemData;
import androidx.media3.cast.CastTimeline.ItemUid;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Util;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaQueueItem;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.media.MediaQueue;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.common.collect.ImmutableList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Creates {@link CastTimeline CastTimelines} from cast receiver app status updates.
 *
 * <p>This class keeps track of the duration reported by the current item to fill any missing
 * durations in the media queue items [See internal: b/65152553].
 */
/* package */ final class CastTimelineTracker {
  private static final String TAG = "CastTlTracker";
  /* package */ static final String KEY_SYNTHETIC_ID = "m3-syntheticId";

  // Maximum number of queue item IDs fetched from the remote client per pass. This is also the
  // cache size configured on MediaQueue to prevent cache evictions during multi-pass fetching.
  @VisibleForTesting /* package */ static final int MAX_FETCH_COUNT = 20;

  private final Map<ItemUid, ItemData> itemIdToData;
  private final Map<ItemUid, MediaItem> mediaItemsBySyntheticId;
  // Maps the receiver assigned id to the synthetic id for media items. The synthetic id is used as
  // the stable uid of the media item in CastTimeline and abstracts away the receiver assigned id
  // from consumers.
  private final SparseArray<ItemUid> receiverItemIdToUid;
  private final Map<ItemUid, Integer> uidToReceiverItemId;
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
    itemIdToData = new HashMap<>();
    mediaItemsBySyntheticId = new HashMap<>();
    receiverItemIdToUid = new SparseArray<>();
    uidToReceiverItemId = new HashMap<>();
    mediaItemsByContentId = new HashMap<>();
  }

  /**
   * Returns the {@link ItemUid} associated with {@code receiverItemId}, or {@code null} if not
   * found.
   */
  @Nullable
  public ItemUid getItemUid(int receiverItemId) {
    return receiverItemIdToUid.get(receiverItemId);
  }

  /**
   * Returns the receiver item ID for {@code uid}, or {@link MediaQueueItem#INVALID_ITEM_ID} if not
   * found.
   */
  public int getReceiverItemId(@Nullable Object uid) {
    if (uid == null || !(uid instanceof ItemUid)) {
      return MediaQueueItem.INVALID_ITEM_ID;
    }
    Integer receiverId = uidToReceiverItemId.get(uid);
    return receiverId != null ? receiverId : MediaQueueItem.INVALID_ITEM_ID;
  }

  /** Resets all item data and UID mappings. */
  public void reset() {
    itemIdToData.clear();
    mediaItemsBySyntheticId.clear();
    receiverItemIdToUid.clear();
    uidToReceiverItemId.clear();
    mediaItemsByContentId.clear();
  }

  /**
   * Prepares {@link MediaQueueItem}s for the given {@link MediaItem}s and registers them with
   * unique synthetic IDs in the tracker.
   *
   * @param mediaItems The media items to convert and register.
   * @return The array of enriched {@link MediaQueueItem}s to send to the Cast receiver.
   */
  public MediaQueueItem[] registerMediaItems(List<MediaItem> mediaItems) {
    MediaQueueItem[] mediaQueueItems = new MediaQueueItem[mediaItems.size()];
    for (int i = 0; i < mediaItems.size(); i++) {
      MediaItem mediaItem = mediaItems.get(i);
      ItemUid itemUid = ItemUid.generateItemUid();
      MediaQueueItem queueItem = mediaItemConverter.toMediaQueueItem(mediaItem);
      MediaQueueItem updatedMediaQueueItem = attachSyntheticId(queueItem, itemUid);
      mediaQueueItems[i] = updatedMediaQueueItem;
      registerMediaItem(itemUid, mediaItem, updatedMediaQueueItem);
    }
    return mediaQueueItems;
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
      ItemUid uid = getOrCreateItemUid(itemId);
      ItemData itemData = itemIdToData.get(uid);
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
    ItemUid currentItemUid = getOrCreateItemUid(currentItemId, currentMediaInfo);
    @Nullable MediaItem mediaItem = getMediaItem(currentItemUid, currentMediaInfo);
    updateItemData(
        currentItemUid,
        mediaItem != null ? mediaItem : MediaItem.EMPTY,
        currentMediaInfo,
        currentMediaInfo.getContentId(),
        /* defaultPositionUs= */ C.TIME_UNSET);

    ImmutableList.Builder<ItemUid> uids = ImmutableList.builderWithExpectedSize(itemIds.length);
    for (int itemId : itemIds) {
      uids.add(getOrCreateItemUid(itemId));
    }
    return new CastTimeline(uids.build(), itemIdToData);
  }

  /**
   * Registers a media item with its corresponding synthetic ID.
   *
   * @param itemUid The unique {@link ItemUid} generated for the item.
   * @param mediaItem The {@link MediaItem}.
   * @param queueItem The {@link MediaQueueItem} associated with the media item.
   */
  private void registerMediaItem(ItemUid itemUid, MediaItem mediaItem, MediaQueueItem queueItem) {
    mediaItemsBySyntheticId.put(itemUid, mediaItem);
    @Nullable MediaInfo mediaInfo = queueItem.getMedia();
    if (mediaInfo != null && mediaInfo.getContentId() != null) {
      mediaItemsByContentId.put(mediaInfo.getContentId(), mediaItem);
    }
  }

  private void updateItemDataFromQueueItem(MediaQueueItem queueItem) {
    long defaultPositionUs = (long) (queueItem.getStartTime() * C.MICROS_PER_SECOND);
    @Nullable MediaInfo mediaInfo = queueItem.getMedia();
    String contentId = mediaInfo != null ? mediaInfo.getContentId() : UNKNOWN_CONTENT_ID;
    ItemUid itemUid = getOrCreateItemUid(queueItem.getItemId(), queueItem.getMedia());
    @Nullable MediaItem mediaItem = getMediaItem(itemUid, mediaInfo);
    if (mediaItem == null || mediaItem == MediaItem.EMPTY) {
      try {
        mediaItem = mediaItemConverter.toMediaItem(queueItem);
      } catch (Exception e) {
        // TODO(b/524966241): Remove try/catch once the converter doesn't throw parsing
        // exceptions.
        Log.w(TAG, "Failed to convert MediaQueueItem to MediaItem");
      }
    }
    updateItemData(
        itemUid,
        mediaItem != null ? mediaItem : MediaItem.EMPTY,
        mediaInfo,
        contentId,
        defaultPositionUs);
  }

  private void updateItemData(
      ItemUid uid,
      MediaItem mediaItem,
      @Nullable MediaInfo mediaInfo,
      String contentId,
      long defaultPositionUs) {
    ItemData previousData = itemIdToData.get(uid);
    if (previousData == null) {
      previousData = ItemData.EMPTY;
    }
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
        uid,
        previousData.copyWithNewValues(
            durationUs, defaultPositionUs, isLive, mediaItem, contentId));
  }

  private void removeUnusedItemDataEntries(int[] itemIds) {
    HashSet<Integer> activeReceiverIds = new HashSet<>(/* initialCapacity= */ itemIds.length * 2);
    for (int id : itemIds) {
      activeReceiverIds.add(id);
    }

    for (int i = receiverItemIdToUid.size() - 1; i >= 0; i--) {
      int receiverId = receiverItemIdToUid.keyAt(i);
      if (!activeReceiverIds.contains(receiverId)) {
        ItemUid uid = receiverItemIdToUid.valueAt(i);
        receiverItemIdToUid.removeAt(i);
        uidToReceiverItemId.remove(uid);
        mediaItemsBySyntheticId.remove(uid);
        ItemData data = itemIdToData.remove(uid);
        if (data != null) {
          mediaItemsByContentId.remove(data.contentId);
        }
      }
    }
  }

  /**
   * Returns the {@link MediaItem} associated with the given {@link ItemUid} and {@link MediaInfo}.
   * The method tries to look up the media item using the {@link ItemUid} first and then the {@link
   * MediaInfo#getContentId()} if the {@link ItemUid} is not found.
   *
   * @param uid The {@link ItemUid} of the media item.
   * @param mediaInfo The {@link MediaInfo} of the media item.
   * @return The {@link MediaItem} associated with the given {@link ItemUid} and {@link MediaInfo},
   *     or {@code null} if not found.
   */
  @Nullable
  private MediaItem getMediaItem(ItemUid uid, @Nullable MediaInfo mediaInfo) {
    MediaItem mediaItem = mediaItemsBySyntheticId.get(uid);
    if (mediaItem != null) {
      return mediaItem;
    }
    ItemData itemData = itemIdToData.get(uid);
    if (itemData != null && itemData.mediaItem != MediaItem.EMPTY) {
      return itemData.mediaItem;
    }
    if (mediaInfo != null && mediaInfo.getContentId() != null) {
      return mediaItemsByContentId.get(mediaInfo.getContentId());
    }
    return null;
  }

  /**
   * Returns the existing {@link ItemUid} for {@code receiverItemId}, or creates and caches a new
   * one if none exists.
   */
  private ItemUid getOrCreateItemUid(int receiverItemId) {
    return getOrCreateItemUid(receiverItemId, /* mediaInfo= */ null);
  }

  /**
   * Returns the existing {@link ItemUid} for {@code receiverItemId}, or creates and caches a new
   * one using synthetic ID extracted from {@code mediaInfo} (or a randomly generated ID if not
   * present).
   */
  private ItemUid getOrCreateItemUid(int receiverItemId, @Nullable MediaInfo mediaInfo) {
    ItemUid uid = receiverItemIdToUid.get(receiverItemId);
    if (uid == null) {
      @Nullable ItemUid syntheticUid = getSyntheticItemUid(mediaInfo);
      // The syntheticUid can be absent if the media item is from a sender that does not add
      // synthetic IDs. In that case, we generate a random ID and associate the media item with it.
      uid = syntheticUid != null ? syntheticUid : ItemUid.generateItemUid();
      receiverItemIdToUid.put(receiverItemId, uid);
      uidToReceiverItemId.put(uid, receiverItemId);
    }
    return uid;
  }

  /**
   * Enriches the given {@link MediaQueueItem} by embedding the synthetic ID into its {@link
   * MediaInfo#getCustomData()} at the top-level under key {@code "m3-syntheticId"}. If {@code
   * customData} is {@code null}, indicating a custom converter, it is left unchanged.
   */
  private static MediaQueueItem attachSyntheticId(MediaQueueItem queueItem, ItemUid syntheticId) {
    MediaInfo mediaInfo = queueItem.getMedia();
    if (mediaInfo == null) {
      return queueItem;
    }
    JSONObject customData = mediaInfo.getCustomData();
    if (customData != null) {
      try {
        customData.put(KEY_SYNTHETIC_ID, syntheticId.toString());
      } catch (JSONException e) {
        Log.w(TAG, "Failed to attach syntheticId to customData");
      }
    }
    return queueItem;
  }

  @Nullable
  private static ItemUid getSyntheticItemUid(@Nullable MediaInfo mediaInfo) {
    if (mediaInfo == null) {
      return null;
    }
    @Nullable JSONObject customData = mediaInfo.getCustomData();
    if (customData == null || customData.isNull(KEY_SYNTHETIC_ID)) {
      return null;
    }
    String syntheticId = customData.optString(KEY_SYNTHETIC_ID);
    return !syntheticId.isEmpty() ? ItemUid.of(syntheticId) : null;
  }
}
