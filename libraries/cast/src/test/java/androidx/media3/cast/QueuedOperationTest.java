/*
 * Copyright (C) 2026 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.cast.CastTimeline.ItemData;
import androidx.media3.cast.CastTimeline.ItemUid;
import androidx.media3.cast.QueuedOperation.AddMediaItemsOperation;
import androidx.media3.cast.QueuedOperation.MoveMediaItemsOperation;
import androidx.media3.cast.QueuedOperation.QueueSnapshot;
import androidx.media3.cast.QueuedOperation.RemoveMediaItemsOperation;
import androidx.media3.cast.QueuedOperation.SeekOperation;
import androidx.media3.cast.QueuedOperation.SetMediaItemsOperation;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.Timeline.Window;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link QueuedOperation#createMaskedSnapshot}. */
@RunWith(AndroidJUnit4.class)
public final class QueuedOperationTest {

  private CastTimelineTracker timelineTracker;

  @Before
  public void setUp() {
    timelineTracker = new CastTimelineTracker(new DefaultMediaItemConverter());
  }

  @Test
  public void setMediaItems_createMaskedSnapshot_createsSnapshotWithItemsStartIndexAndPosition() {
    MediaItem item1 = createMediaItem("id1");
    MediaItem item2 = createMediaItem("id2");
    SetMediaItemsOperation operation =
        new SetMediaItemsOperation(
            ImmutableList.of(item1, item2), /* startIndex= */ 1, /* startPositionMs= */ 12_345L);

    QueueSnapshot snapshot = operation.createMaskedSnapshot(QueueSnapshot.EMPTY, timelineTracker);

    assertThat(snapshot.currentWindowIndex).isEqualTo(1);
    assertThat(snapshot.maskedPositionMs).isEqualTo(12_345L);
    CastTimeline timeline = snapshot.timeline;
    assertThat(timeline.getWindowCount()).isEqualTo(2);
    Window window0 = timeline.getWindow(/* windowIndex= */ 0, new Window());
    Window window1 = timeline.getWindow(/* windowIndex= */ 1, new Window());
    assertThat(window0.mediaItem).isEqualTo(item1);
    assertThat(window0.durationUs).isEqualTo(C.TIME_UNSET);
    assertThat(window0.isDynamic).isTrue();
    assertThat(window1.mediaItem).isEqualTo(item2);
    assertThat(window1.durationUs).isEqualTo(C.TIME_UNSET);
    assertThat(window1.isDynamic).isTrue();
    assertThat(window0.uid).isNotEqualTo(window1.uid);
  }

  @Test
  public void setMediaItems_createMaskedSnapshot_withClippingConfiguration_setsDefaultPosition() {
    MediaItem item1 =
        createMediaItem("id1")
            .buildUpon()
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(5_000L).build())
            .build();
    MediaItem item2 = createMediaItem("id2");
    SetMediaItemsOperation operation =
        new SetMediaItemsOperation(
            ImmutableList.of(item1, item2), /* startIndex= */ 0, /* startPositionMs= */ 0L);

    QueueSnapshot snapshot = operation.createMaskedSnapshot(QueueSnapshot.EMPTY, timelineTracker);

    assertThat(snapshot.timeline.getWindowCount()).isEqualTo(2);
    Window window0 = snapshot.timeline.getWindow(/* windowIndex= */ 0, new Window());
    Window window1 = snapshot.timeline.getWindow(/* windowIndex= */ 1, new Window());
    assertThat(window0.defaultPositionUs).isEqualTo(5_000_000L);
    assertThat(window1.defaultPositionUs).isEqualTo(0L);
  }

  @Test
  public void setMediaItems_createMaskedSnapshot_emptyList_createsEmptySnapshot() {
    SetMediaItemsOperation operation =
        new SetMediaItemsOperation(
            ImmutableList.of(), /* startIndex= */ 0, /* startPositionMs= */ 0L);

    QueueSnapshot snapshot = operation.createMaskedSnapshot(QueueSnapshot.EMPTY, timelineTracker);

    assertThat(snapshot.timeline.isEmpty()).isTrue();
    assertThat(snapshot.timeline.getWindowCount()).isEqualTo(0);
    assertThat(snapshot.currentWindowIndex).isEqualTo(0);
  }

  @Test
  public void addMediaItems_createMaskedSnapshot_prepend_insertsAtBeginningAndShiftsWindowIndex() {
    QueueSnapshot initialSnapshot = createSampleSnapshot(/* currentWindowIndex= */ 1, "A", "B");
    Object uidA = initialSnapshot.timeline.getWindow(0, new Window()).uid;
    Object uidB = initialSnapshot.timeline.getWindow(1, new Window()).uid;
    MediaItem itemC = createMediaItem("C");
    AddMediaItemsOperation operation =
        new AddMediaItemsOperation(/* insertBeforePeriodUid= */ uidA, ImmutableList.of(itemC));

    QueueSnapshot updatedSnapshot =
        operation.createMaskedSnapshot(initialSnapshot, timelineTracker);

    CastTimeline updatedTimeline = updatedSnapshot.timeline;
    assertThat(updatedTimeline.getWindowCount()).isEqualTo(3);
    assertThat(getMediaIdAt(updatedTimeline, 0)).isEqualTo("C");
    assertThat(getMediaIdAt(updatedTimeline, 1)).isEqualTo("A");
    assertThat(getMediaIdAt(updatedTimeline, 2)).isEqualTo("B");
    assertThat(updatedTimeline.getWindow(1, new Window()).uid).isEqualTo(uidA);
    assertThat(updatedTimeline.getWindow(2, new Window()).uid).isEqualTo(uidB);
    // Playing item "B" moved from index 1 to index 2.
    assertThat(updatedSnapshot.currentWindowIndex).isEqualTo(2);
  }

  @Test
  public void addMediaItems_createMaskedSnapshot_middle_insertsAtGivenIndex() {
    QueueSnapshot initialSnapshot = createSampleSnapshot(/* currentWindowIndex= */ 0, "A", "B");
    Object uidB = initialSnapshot.timeline.getWindow(1, new Window()).uid;
    MediaItem itemC = createMediaItem("C");
    AddMediaItemsOperation operation =
        new AddMediaItemsOperation(/* insertBeforePeriodUid= */ uidB, ImmutableList.of(itemC));

    QueueSnapshot updatedSnapshot =
        operation.createMaskedSnapshot(initialSnapshot, timelineTracker);

    assertThat(updatedSnapshot.timeline.getWindowCount()).isEqualTo(3);
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 0)).isEqualTo("A");
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 1)).isEqualTo("C");
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 2)).isEqualTo("B");
    assertThat(updatedSnapshot.currentWindowIndex).isEqualTo(0);
  }

  @Test
  public void addMediaItems_createMaskedSnapshot_append_insertsAtEnd() {
    QueueSnapshot initialSnapshot = createSampleSnapshot(/* currentWindowIndex= */ 0, "A", "B");
    MediaItem itemC = createMediaItem("C");
    AddMediaItemsOperation operation =
        new AddMediaItemsOperation(/* insertBeforePeriodUid= */ null, ImmutableList.of(itemC));

    QueueSnapshot updatedSnapshot =
        operation.createMaskedSnapshot(initialSnapshot, timelineTracker);

    assertThat(updatedSnapshot.timeline.getWindowCount()).isEqualTo(3);
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 0)).isEqualTo("A");
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 1)).isEqualTo("B");
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 2)).isEqualTo("C");
  }

  @Test
  public void addMediaItems_createMaskedSnapshot_missingAnchorUid_returnsSameSnapshot() {
    QueueSnapshot initialSnapshot = createSampleSnapshot(/* currentWindowIndex= */ 0, "A", "B");
    MediaItem itemC = createMediaItem("C");
    AddMediaItemsOperation operation =
        new AddMediaItemsOperation(
            /* insertBeforePeriodUid= */ ItemUid.generateItemUid(), ImmutableList.of(itemC));

    QueueSnapshot updatedSnapshot =
        operation.createMaskedSnapshot(initialSnapshot, timelineTracker);

    assertThat(updatedSnapshot).isSameInstanceAs(initialSnapshot);
  }

  @Test
  public void addMediaItems_createMaskedSnapshot_toEmptySnapshot_createsSnapshotWithItems() {
    MediaItem itemA = createMediaItem("A");
    AddMediaItemsOperation operation =
        new AddMediaItemsOperation(/* insertBeforePeriodUid= */ null, ImmutableList.of(itemA));

    QueueSnapshot updatedSnapshot =
        operation.createMaskedSnapshot(QueueSnapshot.EMPTY, timelineTracker);

    assertThat(updatedSnapshot.timeline.getWindowCount()).isEqualTo(1);
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 0)).isEqualTo("A");
    assertThat(updatedSnapshot.currentWindowIndex).isEqualTo(0);
  }

  @Test
  public void removeMediaItems_createMaskedSnapshot_singleItem_removesItemAndShiftsWindowIndex() {
    QueueSnapshot initialSnapshot =
        createSampleSnapshot(/* currentWindowIndex= */ 2, "A", "B", "C");
    Object uidA = initialSnapshot.timeline.getWindow(0, new Window()).uid;
    Object uidB = initialSnapshot.timeline.getWindow(1, new Window()).uid;
    Object uidC = initialSnapshot.timeline.getWindow(2, new Window()).uid;
    RemoveMediaItemsOperation operation = new RemoveMediaItemsOperation(ImmutableList.of(uidB));

    QueueSnapshot updatedSnapshot =
        operation.createMaskedSnapshot(initialSnapshot, timelineTracker);

    assertThat(updatedSnapshot.timeline.getWindowCount()).isEqualTo(2);
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 0)).isEqualTo("A");
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 1)).isEqualTo("C");
    assertThat(updatedSnapshot.timeline.getWindow(0, new Window()).uid).isEqualTo(uidA);
    assertThat(updatedSnapshot.timeline.getWindow(1, new Window()).uid).isEqualTo(uidC);
    // Playing item "C" shifted from index 2 to index 1.
    assertThat(updatedSnapshot.currentWindowIndex).isEqualTo(1);
  }

  @Test
  public void removeMediaItems_createMaskedSnapshot_range_removesItems() {
    QueueSnapshot initialSnapshot =
        createSampleSnapshot(/* currentWindowIndex= */ 0, "A", "B", "C", "D");
    Object uidB = initialSnapshot.timeline.getWindow(1, new Window()).uid;
    Object uidC = initialSnapshot.timeline.getWindow(2, new Window()).uid;
    RemoveMediaItemsOperation operation =
        new RemoveMediaItemsOperation(ImmutableList.of(uidB, uidC));

    QueueSnapshot updatedSnapshot =
        operation.createMaskedSnapshot(initialSnapshot, timelineTracker);

    assertThat(updatedSnapshot.timeline.getWindowCount()).isEqualTo(2);
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 0)).isEqualTo("A");
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 1)).isEqualTo("D");
  }

  @Test
  public void removeMediaItems_createMaskedSnapshot_all_returnsEmptySnapshot() {
    QueueSnapshot initialSnapshot = createSampleSnapshot(/* currentWindowIndex= */ 1, "A", "B");
    Object uidA = initialSnapshot.timeline.getWindow(0, new Window()).uid;
    Object uidB = initialSnapshot.timeline.getWindow(1, new Window()).uid;
    RemoveMediaItemsOperation operation =
        new RemoveMediaItemsOperation(ImmutableList.of(uidA, uidB));

    QueueSnapshot updatedSnapshot =
        operation.createMaskedSnapshot(initialSnapshot, timelineTracker);

    assertThat(updatedSnapshot.timeline.isEmpty()).isTrue();
    assertThat(updatedSnapshot.currentWindowIndex).isEqualTo(0);
    assertThat(updatedSnapshot.maskedPositionMs).isEqualTo(0L);
  }

  @Test
  public void removeMediaItems_createMaskedSnapshot_emptyRange_returnsSameSnapshot() {
    QueueSnapshot initialSnapshot = createSampleSnapshot(/* currentWindowIndex= */ 0, "A", "B");
    RemoveMediaItemsOperation operation = new RemoveMediaItemsOperation(ImmutableList.of());

    QueueSnapshot updatedSnapshot =
        operation.createMaskedSnapshot(initialSnapshot, timelineTracker);

    assertThat(updatedSnapshot).isSameInstanceAs(initialSnapshot);
  }

  @Test
  public void moveMediaItems_createMaskedSnapshot_forward_movesItemsAndTracksPlayingWindow() {
    QueueSnapshot initialSnapshot =
        createSampleSnapshot(/* currentWindowIndex= */ 0, "A", "B", "C", "D");
    Object uidA = initialSnapshot.timeline.getWindow(0, new Window()).uid;
    Object uidD = initialSnapshot.timeline.getWindow(3, new Window()).uid;
    // Move "A" before "D" -> [B, C, A, D]
    MoveMediaItemsOperation operation =
        new MoveMediaItemsOperation(ImmutableList.of(uidA), /* insertBeforePeriodUid= */ uidD);

    QueueSnapshot updatedSnapshot =
        operation.createMaskedSnapshot(initialSnapshot, timelineTracker);

    assertThat(updatedSnapshot.timeline.getWindowCount()).isEqualTo(4);
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 0)).isEqualTo("B");
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 1)).isEqualTo("C");
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 2)).isEqualTo("A");
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 3)).isEqualTo("D");
    // Playing item "A" moved from index 0 to index 2.
    assertThat(updatedSnapshot.currentWindowIndex).isEqualTo(2);
  }

  @Test
  public void moveMediaItems_createMaskedSnapshot_backward_movesItemsCorrectly() {
    QueueSnapshot initialSnapshot =
        createSampleSnapshot(/* currentWindowIndex= */ 0, "A", "B", "C", "D");
    Object uidB = initialSnapshot.timeline.getWindow(1, new Window()).uid;
    Object uidD = initialSnapshot.timeline.getWindow(3, new Window()).uid;
    // Move "D" before "B" -> [A, D, B, C]
    MoveMediaItemsOperation operation =
        new MoveMediaItemsOperation(ImmutableList.of(uidD), /* insertBeforePeriodUid= */ uidB);

    QueueSnapshot updatedSnapshot =
        operation.createMaskedSnapshot(initialSnapshot, timelineTracker);

    assertThat(updatedSnapshot.timeline.getWindowCount()).isEqualTo(4);
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 0)).isEqualTo("A");
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 1)).isEqualTo("D");
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 2)).isEqualTo("B");
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 3)).isEqualTo("C");
  }

  @Test
  public void moveMediaItems_createMaskedSnapshot_slice_movesMultipleItems() {
    QueueSnapshot initialSnapshot =
        createSampleSnapshot(/* currentWindowIndex= */ 0, "A", "B", "C", "D");
    Object uidA = initialSnapshot.timeline.getWindow(0, new Window()).uid;
    Object uidB = initialSnapshot.timeline.getWindow(1, new Window()).uid;
    // Move "A", "B" to end -> [C, D, A, B]
    MoveMediaItemsOperation operation =
        new MoveMediaItemsOperation(
            ImmutableList.of(uidA, uidB), /* insertBeforePeriodUid= */ null);

    QueueSnapshot updatedSnapshot =
        operation.createMaskedSnapshot(initialSnapshot, timelineTracker);

    assertThat(updatedSnapshot.timeline.getWindowCount()).isEqualTo(4);
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 0)).isEqualTo("C");
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 1)).isEqualTo("D");
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 2)).isEqualTo("A");
    assertThat(getMediaIdAt(updatedSnapshot.timeline, 3)).isEqualTo("B");
  }

  @Test
  public void moveMediaItems_createMaskedSnapshot_samePosition_returnsSameSnapshot() {
    QueueSnapshot initialSnapshot =
        createSampleSnapshot(/* currentWindowIndex= */ 0, "A", "B", "C");
    Object uidB = initialSnapshot.timeline.getWindow(1, new Window()).uid;
    Object uidC = initialSnapshot.timeline.getWindow(2, new Window()).uid;
    MoveMediaItemsOperation operation =
        new MoveMediaItemsOperation(ImmutableList.of(uidB), /* insertBeforePeriodUid= */ uidC);

    QueueSnapshot updatedSnapshot =
        operation.createMaskedSnapshot(initialSnapshot, timelineTracker);

    assertThat(updatedSnapshot).isSameInstanceAs(initialSnapshot);
  }

  @Test
  public void moveMediaItems_createMaskedSnapshot_preservesItemDataAndUids() {
    MediaItem itemA = createMediaItem("A");
    MediaItem itemB = createMediaItem("B");
    ItemUid uidA = ItemUid.generateItemUid();
    ItemUid uidB = ItemUid.generateItemUid();
    Map<ItemUid, ItemData> itemDataMap = new HashMap<>();
    itemDataMap.put(
        uidA,
        new ItemData(
            /* durationUs= */ 100_000_000L,
            /* defaultPositionUs= */ 5_000_000L,
            /* isLive= */ false,
            itemA,
            "A"));
    itemDataMap.put(
        uidB,
        new ItemData(
            /* durationUs= */ 200_000_000L,
            /* defaultPositionUs= */ 10_000_000L,
            /* isLive= */ true,
            itemB,
            "B"));
    CastTimeline initialTimeline = new CastTimeline(ImmutableList.of(uidA, uidB), itemDataMap);
    QueueSnapshot initialSnapshot =
        new QueueSnapshot(initialTimeline, /* currentWindowIndex= */ 0, C.TIME_UNSET);
    MoveMediaItemsOperation operation =
        new MoveMediaItemsOperation(ImmutableList.of(uidA), /* insertBeforePeriodUid= */ null);

    QueueSnapshot updatedSnapshot =
        operation.createMaskedSnapshot(initialSnapshot, timelineTracker);

    assertThat(updatedSnapshot.timeline.getWindowCount()).isEqualTo(2);
    Window window0 = updatedSnapshot.timeline.getWindow(0, new Window());
    Window window1 = updatedSnapshot.timeline.getWindow(1, new Window());
    assertThat(window0.uid).isEqualTo(uidB);
    assertThat(window0.durationUs).isEqualTo(200_000_000L);
    assertThat(window0.defaultPositionUs).isEqualTo(10_000_000L);
    assertThat(window0.isLive()).isTrue();

    assertThat(window1.uid).isEqualTo(uidA);
    assertThat(window1.durationUs).isEqualTo(100_000_000L);
    assertThat(window1.defaultPositionUs).isEqualTo(5_000_000L);
    assertThat(window1.isLive()).isFalse();
  }

  @Test
  public void seek_createMaskedSnapshot_updatesWindowIndexAndMaskedPosition() {
    QueueSnapshot initialSnapshot =
        createSampleSnapshot(/* currentWindowIndex= */ 0, "A", "B", "C");
    Object uidC = initialSnapshot.timeline.getWindow(2, new Window()).uid;
    SeekOperation seekOperation =
        new SeekOperation(
            /* positionMs= */ 45_000L, /* targetPeriodUid= */ uidC, /* isWindowChange= */ true);

    QueueSnapshot updatedSnapshot =
        seekOperation.createMaskedSnapshot(initialSnapshot, timelineTracker);

    assertThat(updatedSnapshot.timeline).isSameInstanceAs(initialSnapshot.timeline);
    assertThat(updatedSnapshot.currentWindowIndex).isEqualTo(2);
    assertThat(updatedSnapshot.maskedPositionMs).isEqualTo(45_000L);
  }

  @Test
  public void addMediaItems_createMaskedSnapshot_calledTwice_isIdempotent() {
    QueueSnapshot initialSnapshot = createSampleSnapshot(/* currentWindowIndex= */ 0, "A", "B");
    Object uidB = initialSnapshot.timeline.getWindow(1, new Window()).uid;
    MediaItem itemC = createMediaItem("C");
    AddMediaItemsOperation operation =
        new AddMediaItemsOperation(/* insertBeforePeriodUid= */ uidB, ImmutableList.of(itemC));

    QueueSnapshot firstMaskedSnapshot =
        operation.createMaskedSnapshot(initialSnapshot, timelineTracker);
    QueueSnapshot secondMaskedSnapshot =
        operation.createMaskedSnapshot(firstMaskedSnapshot, timelineTracker);

    assertThat(secondMaskedSnapshot).isSameInstanceAs(firstMaskedSnapshot);
    assertThat(secondMaskedSnapshot.timeline.getWindowCount()).isEqualTo(3);
  }

  @Test
  public void addMediaItems_createMaskedSnapshot_whenSomeItemsAlreadyPresent_insertsOnlyMissing() {
    QueueSnapshot initialSnapshot = createSampleSnapshot(/* currentWindowIndex= */ 0, "A", "B");
    MediaItem itemC = createMediaItem("C");
    MediaItem itemD = createMediaItem("D");
    AddMediaItemsOperation operation =
        new AddMediaItemsOperation(
            /* insertBeforePeriodUid= */ null, ImmutableList.of(itemC, itemD));

    // First call registers synthetic UIDs for C (index 2) and D (index 3).
    QueueSnapshot firstMasked = operation.createMaskedSnapshot(initialSnapshot, timelineTracker);
    Object uidC = firstMasked.timeline.getWindow(2, new Window()).uid;

    // Simulate a partial receiver snapshot where C is already present (for example, [A, B, C])
    // but D is not.
    QueueSnapshot partialSnapshot =
        new RemoveMediaItemsOperation(
                ImmutableList.of(firstMasked.timeline.getWindow(3, new Window()).uid))
            .createMaskedSnapshot(firstMasked, timelineTracker);

    QueueSnapshot remasked = operation.createMaskedSnapshot(partialSnapshot, timelineTracker);

    assertThat(remasked.timeline.getWindowCount()).isEqualTo(4);
    assertThat(remasked.timeline.getWindow(2, new Window()).uid).isEqualTo(uidC);
    assertThat(getMediaIdAt(remasked.timeline, 2)).isEqualTo("C");
    assertThat(getMediaIdAt(remasked.timeline, 3)).isEqualTo("D");
  }

  @Test
  public void removeMediaItems_createMaskedSnapshot_calledTwice_isIdempotent() {
    QueueSnapshot initialSnapshot =
        createSampleSnapshot(/* currentWindowIndex= */ 2, "A", "B", "C");
    Object uidB = initialSnapshot.timeline.getWindow(1, new Window()).uid;
    RemoveMediaItemsOperation operation = new RemoveMediaItemsOperation(ImmutableList.of(uidB));

    QueueSnapshot firstMaskedSnapshot =
        operation.createMaskedSnapshot(initialSnapshot, timelineTracker);
    QueueSnapshot secondMaskedSnapshot =
        operation.createMaskedSnapshot(firstMaskedSnapshot, timelineTracker);

    assertThat(secondMaskedSnapshot).isSameInstanceAs(firstMaskedSnapshot);
    assertThat(secondMaskedSnapshot.timeline.getWindowCount()).isEqualTo(2);
    assertThat(secondMaskedSnapshot.currentWindowIndex).isEqualTo(1);
  }

  @Test
  public void moveMediaItems_createMaskedSnapshot_calledTwice_isIdempotent() {
    QueueSnapshot initialSnapshot =
        createSampleSnapshot(/* currentWindowIndex= */ 0, "A", "B", "C", "D");
    Object uidA = initialSnapshot.timeline.getWindow(0, new Window()).uid;
    Object uidD = initialSnapshot.timeline.getWindow(3, new Window()).uid;
    MoveMediaItemsOperation operation =
        new MoveMediaItemsOperation(ImmutableList.of(uidA), /* insertBeforePeriodUid= */ uidD);

    QueueSnapshot firstMaskedSnapshot =
        operation.createMaskedSnapshot(initialSnapshot, timelineTracker);
    QueueSnapshot secondMaskedSnapshot =
        operation.createMaskedSnapshot(firstMaskedSnapshot, timelineTracker);

    assertThat(secondMaskedSnapshot).isSameInstanceAs(firstMaskedSnapshot);
    assertThat(secondMaskedSnapshot.timeline.getWindowCount()).isEqualTo(4);
    assertThat(getMediaIdAt(secondMaskedSnapshot.timeline, 0)).isEqualTo("B");
    assertThat(getMediaIdAt(secondMaskedSnapshot.timeline, 1)).isEqualTo("C");
    assertThat(getMediaIdAt(secondMaskedSnapshot.timeline, 2)).isEqualTo("A");
    assertThat(getMediaIdAt(secondMaskedSnapshot.timeline, 3)).isEqualTo("D");
    assertThat(secondMaskedSnapshot.currentWindowIndex).isEqualTo(2);
  }

  private QueueSnapshot createSampleSnapshot(int currentWindowIndex, String... mediaIds) {
    ImmutableList.Builder<MediaItem> items = ImmutableList.builder();
    for (String id : mediaIds) {
      items.add(createMediaItem(id));
    }
    SetMediaItemsOperation setOp =
        new SetMediaItemsOperation(
            items.build(), currentWindowIndex, /* startPositionMs= */ C.TIME_UNSET);
    return setOp.createMaskedSnapshot(QueueSnapshot.EMPTY, timelineTracker);
  }

  private static MediaItem createMediaItem(String id) {
    return new MediaItem.Builder()
        .setMediaId(id)
        .setUri("http://example.com/" + id)
        .setMimeType(MimeTypes.APPLICATION_MP4)
        .build();
  }

  private static String getMediaIdAt(Timeline timeline, int windowIndex) {
    return timeline.getWindow(windowIndex, new Window()).mediaItem.mediaId;
  }
}
