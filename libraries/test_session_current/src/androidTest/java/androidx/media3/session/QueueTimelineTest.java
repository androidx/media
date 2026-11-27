/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.session;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.session.legacy.MediaDescriptionCompat;
import androidx.media3.session.legacy.MediaSessionCompat.QueueItem;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link QueueTimeline}. */
@RunWith(AndroidJUnit4.class)
public class QueueTimelineTest {

  @Test
  public void getIndexOfPeriod_returnsPeriodIndex() {
    QueueTimeline timeline =
        QueueTimeline.create(
            ImmutableList.of(
                new QueueItem(
                    new MediaDescriptionCompat.Builder().setMediaId("id1").build(), /* id= */ 1),
                new QueueItem(
                    new MediaDescriptionCompat.Builder().setMediaId("id2").build(), /* id= */ 2)));

    assertThat(timeline.getIndexOfPeriod("1")).isEqualTo(0);
    assertThat(timeline.getIndexOfPeriod("2")).isEqualTo(1);
  }

  @Test
  public void getIndexOfPeriod_withInvalidUid_returnsIndexUnset() {
    QueueTimeline timeline =
        QueueTimeline.create(
            ImmutableList.of(
                new QueueItem(
                    new MediaDescriptionCompat.Builder().setMediaId("id1").build(), /* id= */ 1),
                new QueueItem(
                    new MediaDescriptionCompat.Builder().setMediaId("id2").build(), /* id= */ 2)));

    assertThat(timeline.getIndexOfPeriod("invalid")).isEqualTo(C.INDEX_UNSET);
    assertThat(timeline.getIndexOfPeriod(-1L)).isEqualTo(C.INDEX_UNSET);
    assertThat(timeline.getIndexOfPeriod(3L)).isEqualTo(C.INDEX_UNSET);
    assertThat(timeline.getIndexOfPeriod(100L)).isEqualTo(C.INDEX_UNSET);
  }

  @Test
  public void getUidOfPeriod_returnsPeriodIndex() {
    QueueTimeline timeline =
        QueueTimeline.create(
            ImmutableList.of(
                new QueueItem(
                    new MediaDescriptionCompat.Builder().setMediaId("id1").build(), /* id= */ 1),
                new QueueItem(
                    new MediaDescriptionCompat.Builder().setMediaId("id2").build(), /* id= */ 2)));

    assertThat(timeline.getUidOfPeriod(0)).isEqualTo("1");
    assertThat(timeline.getUidOfPeriod(1)).isEqualTo("2");
  }

  @Test
  public void getWindow_containsCorrectUid() {
    QueueTimeline timeline =
        QueueTimeline.create(
            ImmutableList.of(
                new QueueItem(
                    new MediaDescriptionCompat.Builder().setMediaId("id1").build(), /* id= */ 1),
                new QueueItem(
                    new MediaDescriptionCompat.Builder().setMediaId("id2").build(), /* id= */ 2)));
    Timeline.Window window = new Timeline.Window();

    timeline.getWindow(/* windowIndex= */ 0, window);
    assertThat(window.uid).isEqualTo("1");

    timeline.getWindow(/* windowIndex= */ 1, window);
    assertThat(window.uid).isEqualTo("2");
  }

  @Test
  public void getUidOfPeriod_afterShuffle_returnsCorrectUid() {
    QueueItem item1 =
        new QueueItem(new MediaDescriptionCompat.Builder().setMediaId("id1").build(), /* id= */ 1);
    QueueItem item2 =
        new QueueItem(new MediaDescriptionCompat.Builder().setMediaId("id2").build(), /* id= */ 2);

    QueueTimeline shuffledTimeline = QueueTimeline.create(ImmutableList.of(item2, item1));

    assertThat(shuffledTimeline.getUidOfPeriod(0)).isEqualTo("2");
    assertThat(shuffledTimeline.getUidOfPeriod(1)).isEqualTo("1");

    assertThat(shuffledTimeline.getIndexOfPeriod("2")).isEqualTo(0);
    assertThat(shuffledTimeline.getIndexOfPeriod("1")).isEqualTo(1);
  }

  @Test
  public void getUidOfPeriod_withDuplicateQueueIds_returnsUniqueUids() {
    QueueItem item1 =
        new QueueItem(new MediaDescriptionCompat.Builder().setMediaId("id1").build(), /* id= */ 1);
    QueueItem item2 =
        new QueueItem(new MediaDescriptionCompat.Builder().setMediaId("id2").build(), /* id= */ 2);
    QueueItem item3 =
        new QueueItem(new MediaDescriptionCompat.Builder().setMediaId("id3").build(), /* id= */ 1);

    QueueTimeline timeline = QueueTimeline.create(ImmutableList.of(item1, item2, item3));

    assertThat(timeline.getUidOfPeriod(0)).isEqualTo("1");
    assertThat(timeline.getUidOfPeriod(1)).isEqualTo("2");
    assertThat(timeline.getUidOfPeriod(2)).isEqualTo("1_1");

    assertThat(timeline.getIndexOfPeriod("1")).isEqualTo(0);
    assertThat(timeline.getIndexOfPeriod("2")).isEqualTo(1);
    assertThat(timeline.getIndexOfPeriod("1_1")).isEqualTo(2);
  }

  @Test
  public void getIndexOfPeriod_withNonStringUid_returnsIndexUnset() {
    QueueTimeline timeline =
        QueueTimeline.create(
            ImmutableList.of(
                new QueueItem(
                    new MediaDescriptionCompat.Builder().setMediaId("id1").build(), /* id= */ 1),
                new QueueItem(
                    new MediaDescriptionCompat.Builder().setMediaId("id2").build(), /* id= */ 2)));

    assertThat(timeline.getIndexOfPeriod(1)).isEqualTo(C.INDEX_UNSET);
    assertThat(timeline.getIndexOfPeriod(1L)).isEqualTo(C.INDEX_UNSET);
    assertThat(timeline.getIndexOfPeriod(1.0f)).isEqualTo(C.INDEX_UNSET);
    assertThat(timeline.getIndexOfPeriod((short) 1)).isEqualTo(C.INDEX_UNSET);
  }

  @Test
  public void uidsAreStableAcrossCopies() {
    QueueItem item1 =
        new QueueItem(new MediaDescriptionCompat.Builder().setMediaId("id1").build(), /* id= */ 1);
    QueueItem item2 =
        new QueueItem(new MediaDescriptionCompat.Builder().setMediaId("id2").build(), /* id= */ 2);
    QueueTimeline timeline = QueueTimeline.create(ImmutableList.of(item1, item2));

    Object uid1Before = timeline.getUidOfPeriod(0);
    Object uid2Before = timeline.getUidOfPeriod(1);

    // Copy with new media item (replace item 2)
    MediaItem newItem = new MediaItem.Builder().setMediaId("newId").build();
    QueueTimeline timelineAfterReplace =
        timeline.copyWithNewMediaItem(/* replaceIndex= */ 1, newItem, /* durationMs= */ 1000);

    assertThat(timelineAfterReplace.getUidOfPeriod(0)).isEqualTo(uid1Before);
    assertThat(timelineAfterReplace.getUidOfPeriod(1)).isEqualTo(uid2Before);

    // Copy with new media items (insert at index 0, shifting item 1 and 2)
    MediaItem insertedItem = new MediaItem.Builder().setMediaId("insertedId").build();
    QueueTimeline timelineAfterInsert =
        timeline.copyWithNewMediaItems(/* index= */ 0, ImmutableList.of(insertedItem));

    assertThat(timelineAfterInsert.getUidOfPeriod(0)).isNotNull();
    assertThat(timelineAfterInsert.getUidOfPeriod(0)).isNotEqualTo(uid1Before);
    assertThat(timelineAfterInsert.getUidOfPeriod(0)).isNotEqualTo(uid2Before);
    assertThat(timelineAfterInsert.getUidOfPeriod(1)).isEqualTo(uid1Before);
    assertThat(timelineAfterInsert.getUidOfPeriod(2)).isEqualTo(uid2Before);

    // Copy with fake media item
    MediaItem fakeItem = new MediaItem.Builder().setMediaId("fakeId").build();
    QueueTimeline timelineWithFake =
        timeline.copyWithFakeMediaItem(fakeItem, /* durationMs= */ 2000);

    assertThat(timelineWithFake.getUidOfPeriod(0)).isEqualTo(uid1Before);
    assertThat(timelineWithFake.getUidOfPeriod(1)).isEqualTo(uid2Before);
    assertThat(timelineWithFake.getUidOfPeriod(2)).isNotNull();
    assertThat(timelineWithFake.getUidOfPeriod(2)).isNotEqualTo(uid1Before);
    assertThat(timelineWithFake.getUidOfPeriod(2)).isNotEqualTo(uid2Before);

    // Copy with removed media items (remove item 1)
    QueueTimeline timelineAfterRemove =
        timeline.copyWithRemovedMediaItems(/* fromIndex= */ 0, /* toIndex= */ 1);

    assertThat(timelineAfterRemove.getUidOfPeriod(0)).isEqualTo(uid2Before);
  }
}
