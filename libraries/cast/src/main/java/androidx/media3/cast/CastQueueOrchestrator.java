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

import androidx.annotation.Nullable;
import androidx.media3.cast.CastTimeline.ItemUid;
import androidx.media3.cast.QueuedOperation.QueueSnapshot;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.Player.TimelineChangeReason;
import androidx.media3.common.PlayerTransferState;
import androidx.media3.common.util.Log;
import com.google.android.gms.cast.CastStatusCodes;
import com.google.android.gms.cast.MediaQueueItem;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.cast.framework.media.RemoteMediaClient.MediaChannelResult;
import com.google.android.gms.common.api.Status;
import java.util.ArrayDeque;
import java.util.function.Supplier;

/**
 * Serializes Cast queue mutations and computes optimistic masked {@link QueueSnapshot
 * QueueSnapshots} on top of the receiver state as reported by the Cast SDK.
 */
/* package */ final class CastQueueOrchestrator {

  /** Listener for changes to the exposed {@link QueueSnapshot}. */
  public interface StateChangeListener {
    /**
     * Called when the current {@link QueueSnapshot} changes.
     *
     * @param snapshot The new {@link QueueSnapshot}.
     * @param timelineChangeReason The {@link TimelineChangeReason} if listeners should be notified
     *     of the change, or {@code null} when resetting the snapshot as a result of {@link #reset}
     *     or if the timeline doesn't change.
     */
    void onQueueSnapshotChanged(
        QueueSnapshot snapshot, @Nullable @TimelineChangeReason Integer timelineChangeReason);
  }

  private static final String TAG = "CastQueueOrchestrator";

  /**
   * Used to fetch the current {@link PlayerTransferState} when executing a {@link QueuedOperation}.
   */
  private final Supplier<PlayerTransferState> playerTransferStateSupplier;

  private final CastTimelineTracker timelineTracker;
  private final StateChangeListener onStateChangedListener;
  private final ArrayDeque<QueuedOperation> queuedOperations;
  @Nullable private QueuedOperation activeOperation;

  /** The {@link QueueSnapshot} that represents the receiver state as reported by the Cast SDK. */
  private QueueSnapshot baseSnapshot;

  /**
   * The {@link QueueSnapshot} obtained after applying the masking from all queued operations to
   * {@link #baseSnapshot}.
   */
  private QueueSnapshot maskedSnapshot;

  public CastQueueOrchestrator(
      Supplier<PlayerTransferState> playerTransferStateSupplier,
      MediaItemConverter mediaItemConverter,
      StateChangeListener onStateChangedListener) {
    this.playerTransferStateSupplier = playerTransferStateSupplier;
    this.timelineTracker = new CastTimelineTracker(mediaItemConverter);
    this.onStateChangedListener = onStateChangedListener;
    this.queuedOperations = new ArrayDeque<>();
    this.baseSnapshot = QueueSnapshot.EMPTY;
    this.maskedSnapshot = QueueSnapshot.EMPTY;
  }

  /** Returns the {@link ItemUid} for the given receiver item ID. */
  @Nullable
  public ItemUid getItemUid(int receiverItemId) {
    return timelineTracker.getItemUid(receiverItemId);
  }

  /**
   * Clears all pending and active operations and resets the orchestrator state.
   *
   * @param clearSnapshots {@code true} to reset the current {@link QueueSnapshot} to {@link
   *     QueueSnapshot#EMPTY} (for example, when connecting to a new Cast session so state from a
   *     previous session is not carried over), or {@code false} to retain the last known {@link
   *     QueueSnapshot} (for example, when a Cast session ends and the player preserves its final
   *     timeline and playback state).
   */
  public void reset(boolean clearSnapshots) {
    queuedOperations.clear();
    activeOperation = null;
    if (clearSnapshots) {
      baseSnapshot = QueueSnapshot.EMPTY;
      maskedSnapshot = QueueSnapshot.EMPTY;
    } else {
      baseSnapshot = maskedSnapshot;
    }
    timelineTracker.reset();
    onStateChangedListener.onQueueSnapshotChanged(maskedSnapshot, /* timelineChangeReason= */ null);
  }

  /**
   * Enqueues a {@link QueuedOperation}, updates the masked {@link QueueSnapshot}, and dispatches
   * the next eligible operation to {@code remoteMediaClient} if none is currently active.
   */
  public void enqueue(QueuedOperation operation, RemoteMediaClient remoteMediaClient) {
    QueueSnapshot previousSnapshot = maskedSnapshot;
    if (operation.replacesPreviousOperations()) {
      queuedOperations.clear();
      activeOperation = null;
      timelineTracker.reset();
    }
    queuedOperations.addLast(operation);
    maskedSnapshot = operation.createMaskedSnapshot(maskedSnapshot, timelineTracker);
    dispatchNextOperationIfPossible(remoteMediaClient);
    if (!maskedSnapshot.equals(previousSnapshot)) {
      onStateChangedListener.onQueueSnapshotChanged(
          maskedSnapshot, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED);
    }
  }

  /**
   * Updates the receiver {@link #baseSnapshot} from {@code remoteMediaClient}, recomputes {@link
   * #maskedSnapshot}, dispatches any unblocked queued operations, and notifies the state change
   * listener if {@link #maskedSnapshot} has changed.
   *
   * <p>This method should be called when the receiver queue state changes. For example, upon {@code
   * onStatusUpdated()} or {@code mediaQueueChanged()}.
   */
  public void onReceiverStateUpdated(@Nullable RemoteMediaClient remoteMediaClient) {
    CastTimeline receiverTimeline =
        remoteMediaClient != null && remoteMediaClient.getMediaStatus() != null
            ? timelineTracker.getCastTimeline(remoteMediaClient)
            : CastTimeline.EMPTY_CAST_TIMELINE;
    int receiverWindowIndex =
        remoteMediaClient != null
            ? fetchReceiverWindowIndex(remoteMediaClient, timelineTracker, receiverTimeline)
            : 0;
    QueueSnapshot previousSnapshot = maskedSnapshot;
    boolean isReceiverTimelineComplete = !timelineTracker.hasPendingQueueFetches();
    if (isReceiverTimelineComplete
        || (queuedOperations.isEmpty() && maskedSnapshot.timeline.isEmpty())) {
      baseSnapshot = new QueueSnapshot(receiverTimeline, receiverWindowIndex, C.TIME_UNSET);
      recomputeMaskedSnapshot();
    }
    if (remoteMediaClient != null) {
      dispatchNextOperationIfPossible(remoteMediaClient);
    }
    if (!maskedSnapshot.equals(previousSnapshot)) {
      onStateChangedListener.onQueueSnapshotChanged(
          maskedSnapshot, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE);
    }
  }

  private void dispatchNextOperationIfPossible(RemoteMediaClient remoteMediaClient) {
    if (activeOperation != null || queuedOperations.isEmpty()) {
      return;
    }
    // Wait for pending MediaQueue item fetches to complete before dispatching incremental
    // operations, so timelineTracker has mapped all synthetic UIDs from the preceding operation
    // to their receiver item IDs. Operations that replace the entire queue (such as
    // SetMediaItemsOperation) do not depend on prior receiver item IDs and can execute
    // immediately.
    if (!queuedOperations.peekFirst().replacesPreviousOperations()
        && timelineTracker.hasPendingQueueFetches()) {
      return;
    }
    // When the receiver timeline is fully fetched and no local operation is in flight, any
    // target item UID from a previously completed operation has already been mapped to a
    // receiver item ID. If the next queued operation still cannot execute, its target item no
    // longer exists on the receiver (for example, because another sender removed it), so drop
    // it to avoid permanently blocking subsequent operations.
    boolean droppedOperation = false;
    while (!queuedOperations.isEmpty()
        && !queuedOperations.peekFirst().canExecute(timelineTracker, remoteMediaClient)) {
      QueuedOperation dropped = queuedOperations.removeFirst();
      droppedOperation = true;
      Log.w(
          TAG,
          "Dropping queued operation because its target item is not present on the receiver: "
              + dropped.getName());
    }
    if (droppedOperation) {
      recomputeMaskedSnapshot();
    }
    if (queuedOperations.isEmpty()) {
      return;
    }
    QueuedOperation head = queuedOperations.peekFirst();
    activeOperation = head;
    head.execute(remoteMediaClient, timelineTracker, playerTransferStateSupplier.get())
        .setResultCallback(
            mediaChannelResult -> onOperationResult(head, mediaChannelResult, remoteMediaClient));
  }

  private void onOperationResult(
      QueuedOperation operation,
      MediaChannelResult mediaChannelResult,
      RemoteMediaClient remoteMediaClient) {
    // Reference check: if an operation that replaces previous operations (such as
    // SetMediaItemsOperation) or reset() cleared or replaced activeOperation while this operation
    // was in flight, ignore the stale callback.
    if (operation != activeOperation) {
      return;
    }
    activeOperation = null;
    @Nullable Status status = mediaChannelResult != null ? mediaChannelResult.getStatus() : null;
    boolean isError =
        status != null && !status.isSuccess() && status.getStatusCode() != CastStatusCodes.REPLACED;
    if (isError) {
      CastUtils.logOperationFailedIfStatusError(TAG, operation.getName(), mediaChannelResult);
      queuedOperations.clear();
      maskedSnapshot = baseSnapshot;
      onStateChangedListener.onQueueSnapshotChanged(
          maskedSnapshot, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE);
      return;
    }
    if (queuedOperations.peekFirst() == operation) {
      queuedOperations.removeFirst();
    }
    onReceiverStateUpdated(remoteMediaClient);
  }

  private void recomputeMaskedSnapshot() {
    QueueSnapshot current = baseSnapshot;
    for (QueuedOperation operation : queuedOperations) {
      current = operation.createMaskedSnapshot(current, timelineTracker);
    }
    maskedSnapshot = current;
  }

  private static int fetchReceiverWindowIndex(
      RemoteMediaClient remoteMediaClient,
      CastTimelineTracker timelineTracker,
      CastTimeline timeline) {
    int itemId = CastUtils.getCurrentOrLoadingItemId(remoteMediaClient);
    int windowIndex = C.INDEX_UNSET;
    if (itemId != MediaQueueItem.INVALID_ITEM_ID) {
      ItemUid itemUid = timelineTracker.getItemUid(itemId);
      if (itemUid != null) {
        windowIndex = timeline.getIndexOfPeriod(itemUid);
      }
    }
    if (windowIndex == C.INDEX_UNSET) {
      windowIndex = 0;
    }
    return windowIndex;
  }
}
