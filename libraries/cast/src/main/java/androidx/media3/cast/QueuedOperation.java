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
package androidx.media3.cast;

import static androidx.media3.cast.CastTimeline.ItemData.UNKNOWN_CONTENT_ID;
import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Math.max;
import static java.lang.Math.min;

import androidx.annotation.Nullable;
import androidx.media3.cast.CastTimeline.ItemData;
import androidx.media3.cast.CastTimeline.ItemUid;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.PlayerTransferState;
import androidx.media3.common.Timeline;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaQueueData;
import com.google.android.gms.cast.MediaQueueItem;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.cast.framework.media.RemoteMediaClient.MediaChannelResult;
import com.google.android.gms.common.api.PendingResult;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Encapsulates a mutating operation on the Cast media queue or playback position. */
/* package */ abstract class QueuedOperation {

  /**
   * Immutable snapshot of the queue state (timeline, current window index, and masked playback
   * position).
   */
  public static final class QueueSnapshot {
    public static final QueueSnapshot EMPTY =
        new QueueSnapshot(
            CastTimeline.EMPTY_CAST_TIMELINE,
            /* currentWindowIndex= */ 0,
            /* maskedPositionMs= */ C.TIME_UNSET);

    public final CastTimeline timeline;
    public final int currentWindowIndex;

    /**
     * The optimistically masked playback position in milliseconds, or {@link C#TIME_UNSET} if no
     * operation involved in the creation of this snapshot affects the playback position (in which
     * case the receiver-reported playback position can be used).
     */
    public final long maskedPositionMs;

    public QueueSnapshot(CastTimeline timeline, int currentWindowIndex, long maskedPositionMs) {
      this.timeline = timeline;
      this.currentWindowIndex = currentWindowIndex;
      this.maskedPositionMs = maskedPositionMs;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof QueueSnapshot)) {
        return false;
      }
      QueueSnapshot other = (QueueSnapshot) obj;
      return timeline.equals(other.timeline)
          && currentWindowIndex == other.currentWindowIndex
          && maskedPositionMs == other.maskedPositionMs;
    }

    @Override
    public int hashCode() {
      int result = timeline.hashCode();
      result = 31 * result + currentWindowIndex;
      result = 31 * result + Long.hashCode(maskedPositionMs);
      return result;
    }
  }

  /** Returns a human-readable label for logging. */
  public abstract String getName();

  /**
   * Returns whether this operation replaces all prior pending operations (for example, {@link
   * SetMediaItemsOperation}).
   */
  public boolean replacesPreviousOperations() {
    return false;
  }

  /**
   * Applies this operation's optimistic changes onto {@code snapshot} and returns the resulting
   * {@link QueueSnapshot}.
   */
  public abstract QueueSnapshot createMaskedSnapshot(
      QueueSnapshot snapshot, CastTimelineTracker timelineTracker);

  /**
   * Returns whether the operation is ready to be executed.
   *
   * <p>For example, an operation is not ready to be executed if it targets an item whose synthetic
   * UID has not yet been mapped to a receiver item ID (such as when a preceding {@link
   * AddMediaItemsOperation} or {@link SetMediaItemsOperation} has not yet been confirmed by the
   * receiver).
   */
  public abstract boolean canExecute(
      CastTimelineTracker timelineTracker, RemoteMediaClient remoteMediaClient);

  /**
   * Executes the operation and returns the {@link PendingResult}.
   *
   * @param remoteMediaClient The {@link RemoteMediaClient} on which to execute the operation.
   * @param timelineTracker The {@link CastTimelineTracker} used to resolve or register items.
   * @param playerTransferState The {@link PlayerTransferState} at the moment of execution, for
   *     operations that require the current player state when dispatched.
   */
  public abstract PendingResult<MediaChannelResult> execute(
      RemoteMediaClient remoteMediaClient,
      CastTimelineTracker timelineTracker,
      PlayerTransferState playerTransferState);

  /**
   * Base operation for commands that register and insert new {@link MediaItem MediaItems} into the
   * queue.
   */
  /* package */ abstract static class MediaItemsInsertionOperation extends QueuedOperation {
    protected final ImmutableList<MediaItem> mediaItems;
    private ImmutableList<ItemUid> itemUids;
    protected MediaQueueItem[] queueItems;

    protected MediaItemsInsertionOperation(ImmutableList<MediaItem> mediaItems) {
      this.mediaItems = mediaItems;
      this.itemUids = ImmutableList.of();
      this.queueItems = new MediaQueueItem[0];
    }

    protected final CastTimeline registerAndCreateMaskingTimeline(
        CastTimeline currentTimeline, int index, CastTimelineTracker timelineTracker) {
      if (itemUids.isEmpty() && !mediaItems.isEmpty()) {
        CastTimelineTracker.RegisteredMediaItems registeredItems =
            timelineTracker.registerMediaItems(mediaItems);
        this.itemUids = registeredItems.itemUids;
        this.queueItems = registeredItems.queueItems;
      }
      return createMaskingTimeline(currentTimeline, index, itemUids, mediaItems);
    }

    protected final PendingResult<MediaChannelResult> executeLoad(
        RemoteMediaClient remoteMediaClient,
        PlayerTransferState playerTransferState,
        int startIndex,
        long startPositionMs) {
      MediaQueueData mediaQueueData =
          new MediaQueueData.Builder()
              .setItems(Arrays.asList(queueItems))
              .setStartIndex(startIndex)
              .setRepeatMode(toCastRepeatMode(playerTransferState.getRepeatMode()))
              .setStartTime(startPositionMs)
              .build();
      MediaLoadRequestData loadRequestData =
          new MediaLoadRequestData.Builder()
              .setAutoplay(playerTransferState.getPlayWhenReady())
              .setQueueData(mediaQueueData)
              .setCurrentTime(startPositionMs)
              .build();
      return remoteMediaClient.load(loadRequestData);
    }

    private static int toCastRepeatMode(@Player.RepeatMode int repeatMode) {
      switch (repeatMode) {
        case Player.REPEAT_MODE_ONE:
          return MediaStatus.REPEAT_MODE_REPEAT_SINGLE;
        case Player.REPEAT_MODE_ALL:
          return MediaStatus.REPEAT_MODE_REPEAT_ALL;
        case Player.REPEAT_MODE_OFF:
          return MediaStatus.REPEAT_MODE_REPEAT_OFF;
        default:
          throw new IllegalArgumentException();
      }
    }

    private static CastTimeline createMaskingTimeline(
        CastTimeline currentTimeline,
        int index,
        List<ItemUid> itemUids,
        List<MediaItem> mediaItems) {
      checkArgument(itemUids.size() == mediaItems.size());
      int currentWindowCount = currentTimeline.getWindowCount();
      int insertIndex = min(max(0, index), currentWindowCount);
      int addedCount = mediaItems.size();
      int newWindowCount = currentWindowCount + addedCount;
      if (newWindowCount == 0) {
        return CastTimeline.EMPTY_CAST_TIMELINE;
      }

      List<ItemUid> newUids = new ArrayList<>(newWindowCount);
      Map<ItemUid, ItemData> newItemIdToData = new HashMap<>();
      Timeline.Window window = new Timeline.Window();

      for (int i = 0; i < currentWindowCount; i++) {
        ItemUid uid = (ItemUid) currentTimeline.getWindow(i, window).uid;
        newItemIdToData.put(
            uid,
            new ItemData(
                window.durationUs,
                window.defaultPositionUs,
                window.isLive(),
                window.mediaItem,
                UNKNOWN_CONTENT_ID));
      }

      for (int i = 0; i < insertIndex; i++) {
        newUids.add((ItemUid) currentTimeline.getWindow(i, window).uid);
      }

      for (int i = 0; i < addedCount; i++) {
        ItemUid uid = itemUids.get(i);
        if (currentTimeline.getIndexOfPeriod(uid) != C.INDEX_UNSET) {
          // Receiver status updates can arrive before the PendingResult callback for
          // queueInsertItems. Skip items that are already present in currentTimeline to avoid
          // inserting them a second time when recomputing the masked snapshot.
          continue;
        }
        MediaItem mediaItem = mediaItems.get(i);
        newUids.add(uid);
        newItemIdToData.put(
            uid,
            new ItemData(
                /* durationUs= */ C.TIME_UNSET,
                /* defaultPositionUs= */ mediaItem.clippingConfiguration.startPositionUs,
                /* isLive= */ false,
                mediaItem,
                UNKNOWN_CONTENT_ID));
      }

      for (int i = insertIndex; i < currentWindowCount; i++) {
        newUids.add((ItemUid) currentTimeline.getWindow(i, window).uid);
      }

      if (newUids.size() == currentWindowCount) {
        return currentTimeline;
      }

      return new CastTimeline(newUids, newItemIdToData);
    }
  }

  /** Command for {@code setMediaItems(...)}. */
  public static final class SetMediaItemsOperation extends MediaItemsInsertionOperation {
    private final int startIndex;
    private final long startPositionMs;

    public SetMediaItemsOperation(
        ImmutableList<MediaItem> mediaItems, int startIndex, long startPositionMs) {
      super(mediaItems);
      this.startIndex = mediaItems.isEmpty() ? 0 : min(startIndex, mediaItems.size() - 1);
      this.startPositionMs = startPositionMs;
    }

    @Override
    public String getName() {
      return "Load";
    }

    @Override
    public boolean replacesPreviousOperations() {
      return true;
    }

    @Override
    public QueueSnapshot createMaskedSnapshot(
        QueueSnapshot snapshot, CastTimelineTracker timelineTracker) {
      CastTimeline maskedTimeline =
          registerAndCreateMaskingTimeline(
              CastTimeline.EMPTY_CAST_TIMELINE, /* index= */ 0, timelineTracker);
      return new QueueSnapshot(maskedTimeline, startIndex, startPositionMs);
    }

    @Override
    public boolean canExecute(
        CastTimelineTracker timelineTracker, RemoteMediaClient remoteMediaClient) {
      return true;
    }

    @Override
    public PendingResult<MediaChannelResult> execute(
        RemoteMediaClient remoteMediaClient,
        CastTimelineTracker timelineTracker,
        PlayerTransferState playerTransferState) {
      return executeLoad(remoteMediaClient, playerTransferState, startIndex, startPositionMs);
    }
  }

  /** Command for {@code addMediaItems(...)}. */
  public static final class AddMediaItemsOperation extends MediaItemsInsertionOperation {
    @Nullable private final Object insertBeforePeriodUid;

    public AddMediaItemsOperation(
        @Nullable Object insertBeforePeriodUid, ImmutableList<MediaItem> mediaItems) {
      super(mediaItems);
      this.insertBeforePeriodUid = insertBeforePeriodUid;
    }

    @Override
    public String getName() {
      return "Insert items";
    }

    @Override
    public QueueSnapshot createMaskedSnapshot(
        QueueSnapshot snapshot, CastTimelineTracker timelineTracker) {
      CastTimeline currentTimeline = snapshot.timeline;
      int effectiveIndex;
      if (currentTimeline.isEmpty()) {
        effectiveIndex = 0;
      } else if (insertBeforePeriodUid != null) {
        effectiveIndex = currentTimeline.getIndexOfPeriod(insertBeforePeriodUid);
        if (effectiveIndex == C.INDEX_UNSET) {
          return snapshot;
        }
      } else {
        effectiveIndex = currentTimeline.getWindowCount();
      }
      Timeline.Window window = new Timeline.Window();
      @Nullable
      Object playingUid =
          !currentTimeline.isEmpty()
              ? currentTimeline.getWindow(snapshot.currentWindowIndex, window).uid
              : null;
      CastTimeline newTimeline =
          registerAndCreateMaskingTimeline(currentTimeline, effectiveIndex, timelineTracker);
      if (newTimeline.equals(currentTimeline)) {
        return snapshot;
      }
      int newWindowIndex = 0;
      if (playingUid != null) {
        int idx = newTimeline.getIndexOfPeriod(playingUid);
        newWindowIndex = idx != C.INDEX_UNSET ? idx : 0;
      }
      return new QueueSnapshot(newTimeline, newWindowIndex, snapshot.maskedPositionMs);
    }

    @Override
    public boolean canExecute(
        CastTimelineTracker timelineTracker, RemoteMediaClient remoteMediaClient) {
      if (isReceiverQueueEmpty(remoteMediaClient)) {
        return true;
      }
      if (insertBeforePeriodUid != null) {
        return timelineTracker.getReceiverItemId(insertBeforePeriodUid)
            != MediaQueueItem.INVALID_ITEM_ID;
      }
      return true;
    }

    @Override
    public PendingResult<MediaChannelResult> execute(
        RemoteMediaClient remoteMediaClient,
        CastTimelineTracker timelineTracker,
        PlayerTransferState playerTransferState) {
      if (isReceiverQueueEmpty(remoteMediaClient)) {
        return executeLoad(
            remoteMediaClient, playerTransferState, /* startIndex= */ 0, /* startPositionMs= */ 0);
      }
      int insertBeforeItemId =
          insertBeforePeriodUid != null
              ? timelineTracker.getReceiverItemId(insertBeforePeriodUid)
              : MediaQueueItem.INVALID_ITEM_ID;
      return remoteMediaClient.queueInsertItems(
          queueItems, insertBeforeItemId, /* customData= */ null);
    }

    private static boolean isReceiverQueueEmpty(RemoteMediaClient remoteMediaClient) {
      return remoteMediaClient.getMediaStatus() == null
          || remoteMediaClient.getMediaQueue().getItemIds().length == 0;
    }
  }

  /** Command for {@code moveMediaItems(...)}. */
  public static final class MoveMediaItemsOperation extends QueuedOperation {
    private final ImmutableList<Object> uidsToMove;
    @Nullable private final Object insertBeforePeriodUid;

    public MoveMediaItemsOperation(
        List<Object> uidsToMove, @Nullable Object insertBeforePeriodUid) {
      this.uidsToMove = ImmutableList.copyOf(uidsToMove);
      this.insertBeforePeriodUid = insertBeforePeriodUid;
    }

    @Override
    public String getName() {
      return "Reorder items";
    }

    @Override
    public QueueSnapshot createMaskedSnapshot(
        QueueSnapshot snapshot, CastTimelineTracker timelineTracker) {
      CastTimeline currentTimeline = snapshot.timeline;
      int playlistSize = currentTimeline.getWindowCount();
      if (playlistSize == 0 || uidsToMove.isEmpty()) {
        return snapshot;
      }
      if (insertBeforePeriodUid != null
          && currentTimeline.getIndexOfPeriod(insertBeforePeriodUid) == C.INDEX_UNSET) {
        return snapshot;
      }

      List<ItemUid> currentUids = new ArrayList<>(playlistSize);
      List<ItemUid> remainingUids = new ArrayList<>(playlistSize);
      Map<ItemUid, ItemData> newItemIdToData = new HashMap<>();
      Timeline.Window window = new Timeline.Window();

      for (int i = 0; i < playlistSize; i++) {
        ItemUid uid = (ItemUid) currentTimeline.getWindow(i, window).uid;
        currentUids.add(uid);
        if (!uidsToMove.contains(uid)) {
          remainingUids.add(uid);
        }
        newItemIdToData.put(
            uid,
            new ItemData(
                window.durationUs,
                window.defaultPositionUs,
                window.isLive(),
                window.mediaItem,
                UNKNOWN_CONTENT_ID));
      }

      List<ItemUid> movedUids = new ArrayList<>(uidsToMove.size());
      for (int i = 0; i < uidsToMove.size(); i++) {
        Object uid = uidsToMove.get(i);
        if (uid instanceof ItemUid && newItemIdToData.containsKey(uid)) {
          movedUids.add((ItemUid) uid);
        }
      }
      if (movedUids.isEmpty()) {
        return snapshot;
      }

      int insertPos =
          insertBeforePeriodUid != null
              ? remainingUids.indexOf(insertBeforePeriodUid)
              : remainingUids.size();
      if (insertPos == -1) {
        return snapshot;
      }
      remainingUids.addAll(insertPos, movedUids);

      if (remainingUids.equals(currentUids)) {
        return snapshot;
      }

      Object playingUid = currentUids.get(snapshot.currentWindowIndex);
      CastTimeline newTimeline = new CastTimeline(remainingUids, newItemIdToData);
      int idx = newTimeline.getIndexOfPeriod(playingUid);
      int newWindowIndex = idx != C.INDEX_UNSET ? idx : 0;
      return new QueueSnapshot(newTimeline, newWindowIndex, snapshot.maskedPositionMs);
    }

    @Override
    public boolean canExecute(
        CastTimelineTracker timelineTracker, RemoteMediaClient remoteMediaClient) {
      if (remoteMediaClient.getMediaStatus() == null) {
        return false;
      }
      for (Object uid : uidsToMove) {
        if (timelineTracker.getReceiverItemId(uid) == MediaQueueItem.INVALID_ITEM_ID) {
          return false;
        }
      }
      if (insertBeforePeriodUid != null
          && timelineTracker.getReceiverItemId(insertBeforePeriodUid)
              == MediaQueueItem.INVALID_ITEM_ID) {
        return false;
      }
      return true;
    }

    @Override
    public PendingResult<MediaChannelResult> execute(
        RemoteMediaClient remoteMediaClient,
        CastTimelineTracker timelineTracker,
        PlayerTransferState playerTransferState) {
      int[] receiverUids = new int[uidsToMove.size()];
      for (int i = 0; i < uidsToMove.size(); i++) {
        receiverUids[i] = timelineTracker.getReceiverItemId(uidsToMove.get(i));
      }
      int receiverInsertBeforeId =
          insertBeforePeriodUid != null
              ? timelineTracker.getReceiverItemId(insertBeforePeriodUid)
              : MediaQueueItem.INVALID_ITEM_ID;
      return remoteMediaClient.queueReorderItems(
          receiverUids, receiverInsertBeforeId, /* customData= */ null);
    }
  }

  /** Command for {@code removeMediaItems(...)}. */
  public static final class RemoveMediaItemsOperation extends QueuedOperation {
    private final ImmutableList<Object> uidsToRemove;

    public RemoveMediaItemsOperation(List<Object> uidsToRemove) {
      this.uidsToRemove = ImmutableList.copyOf(uidsToRemove);
    }

    @Override
    public String getName() {
      return "Remove items";
    }

    @Override
    public QueueSnapshot createMaskedSnapshot(
        QueueSnapshot snapshot, CastTimelineTracker timelineTracker) {
      CastTimeline currentTimeline = snapshot.timeline;
      if (currentTimeline.isEmpty() || uidsToRemove.isEmpty()) {
        return snapshot;
      }
      int currentWindowCount = currentTimeline.getWindowCount();
      int currentWindowIndex = snapshot.currentWindowIndex;
      Timeline.Window window = new Timeline.Window();
      Object playingUid = currentTimeline.getWindow(currentWindowIndex, window).uid;
      boolean playingPeriodRemoved = uidsToRemove.contains(playingUid);

      List<ItemUid> newUids = new ArrayList<>(currentWindowCount);
      Map<ItemUid, ItemData> newItemIdToData = new HashMap<>();
      int removedBefore = 0;
      int removedCount = 0;

      for (int i = 0; i < currentWindowCount; i++) {
        ItemUid uid = (ItemUid) currentTimeline.getWindow(i, window).uid;
        if (uidsToRemove.contains(uid)) {
          removedCount++;
          if (i < currentWindowIndex) {
            removedBefore++;
          }
          continue;
        }
        newUids.add(uid);
        newItemIdToData.put(
            uid,
            new ItemData(
                window.durationUs,
                window.defaultPositionUs,
                window.isLive(),
                window.mediaItem,
                UNKNOWN_CONTENT_ID));
      }

      if (removedCount == 0) {
        return snapshot;
      }

      CastTimeline newTimeline =
          newUids.isEmpty()
              ? CastTimeline.EMPTY_CAST_TIMELINE
              : new CastTimeline(newUids, newItemIdToData);
      int newWindowIndex =
          !newTimeline.isEmpty()
              ? min(currentWindowIndex - removedBefore, newTimeline.getWindowCount() - 1)
              : 0;
      long newMaskedPositionMs =
          playingPeriodRemoved
              ? (!newTimeline.isEmpty()
                  ? newTimeline.getWindow(newWindowIndex, window).getDefaultPositionMs()
                  : 0L)
              : snapshot.maskedPositionMs;
      return new QueueSnapshot(newTimeline, newWindowIndex, newMaskedPositionMs);
    }

    @Override
    public boolean canExecute(
        CastTimelineTracker timelineTracker, RemoteMediaClient remoteMediaClient) {
      if (remoteMediaClient.getMediaStatus() == null) {
        return false;
      }
      for (Object uid : uidsToRemove) {
        if (timelineTracker.getReceiverItemId(uid) == MediaQueueItem.INVALID_ITEM_ID) {
          return false;
        }
      }
      return true;
    }

    @Override
    public PendingResult<MediaChannelResult> execute(
        RemoteMediaClient remoteMediaClient,
        CastTimelineTracker timelineTracker,
        PlayerTransferState playerTransferState) {
      int[] receiverUids = new int[uidsToRemove.size()];
      for (int i = 0; i < uidsToRemove.size(); i++) {
        receiverUids[i] = timelineTracker.getReceiverItemId(uidsToRemove.get(i));
      }
      return remoteMediaClient.queueRemoveItems(receiverUids, /* customData= */ null);
    }
  }

  /** Command for {@code seekTo(...)}. */
  public static final class SeekOperation extends QueuedOperation {
    private final long positionMs;
    private final Object targetPeriodUid;
    private final boolean isWindowChange;

    public SeekOperation(long positionMs, Object targetPeriodUid, boolean isWindowChange) {
      this.positionMs = positionMs;
      this.targetPeriodUid = targetPeriodUid;
      this.isWindowChange = isWindowChange;
    }

    @Override
    public String getName() {
      return "Seek";
    }

    @Override
    public QueueSnapshot createMaskedSnapshot(
        QueueSnapshot snapshot, CastTimelineTracker timelineTracker) {
      CastTimeline timeline = snapshot.timeline;
      if (timeline.isEmpty()) {
        return new QueueSnapshot(timeline, /* currentWindowIndex= */ 0, positionMs);
      }
      int resolvedIndex = timeline.getIndexOfPeriod(targetPeriodUid);
      if (resolvedIndex != C.INDEX_UNSET) {
        return new QueueSnapshot(timeline, resolvedIndex, positionMs);
      }
      int fallbackIndex = min(snapshot.currentWindowIndex, timeline.getWindowCount() - 1);
      return new QueueSnapshot(timeline, fallbackIndex, C.TIME_UNSET);
    }

    @Override
    public boolean canExecute(
        CastTimelineTracker timelineTracker, RemoteMediaClient remoteMediaClient) {
      if (remoteMediaClient.getMediaStatus() == null) {
        return false;
      }
      if (isWindowChange) {
        return timelineTracker.getReceiverItemId(targetPeriodUid) != MediaQueueItem.INVALID_ITEM_ID;
      }
      return true;
    }

    @Override
    public PendingResult<MediaChannelResult> execute(
        RemoteMediaClient remoteMediaClient,
        CastTimelineTracker timelineTracker,
        PlayerTransferState playerTransferState) {
      if (isWindowChange) {
        int receiverItemId = timelineTracker.getReceiverItemId(targetPeriodUid);
        return remoteMediaClient.queueJumpToItem(
            receiverItemId, positionMs, /* customData= */ null);
      }
      return remoteMediaClient.seek(positionMs);
    }
  }
}
