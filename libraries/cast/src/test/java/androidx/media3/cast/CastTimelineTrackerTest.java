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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.media3.cast.CastTimeline.ItemUid;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline.Window;
import androidx.media3.common.util.Util;
import androidx.media3.test.utils.TimelineAsserts;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaQueueItem;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.media.MediaQueue;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** Tests for {@link CastTimelineTracker}. */
@RunWith(RobolectricTestParameterInjector.class)
public class CastTimelineTrackerTest {

  private static final long DURATION_2_MS = 2000;
  private static final long DURATION_3_MS = 3000;
  private static final long DURATION_4_MS = 4000;
  private static final long DURATION_5_MS = 5000;

  private MediaItemConverter mediaItemConverter;
  private CastTimelineTracker castTimelineTracker;

  @Before
  public void init() {
    mediaItemConverter = new DefaultMediaItemConverter();
    castTimelineTracker = new CastTimelineTracker(mediaItemConverter);
  }

  /** Tests that duration of the current media info is correctly propagated to the timeline. */
  @Test
  public void getCastTimelinePersistsDuration() {
    CastTimelineTracker tracker = new CastTimelineTracker(new DefaultMediaItemConverter());

    RemoteMediaClient remoteMediaClient =
        mockRemoteMediaClient(
            /* itemIds= */ new int[] {1, 2, 3, 4, 5},
            /* currentItemId= */ 2,
            /* currentDurationMs= */ DURATION_2_MS);
    TimelineAsserts.assertPeriodDurations(
        tracker.getCastTimeline(remoteMediaClient),
        C.TIME_UNSET,
        Util.msToUs(DURATION_2_MS),
        C.TIME_UNSET,
        C.TIME_UNSET,
        C.TIME_UNSET);

    remoteMediaClient =
        mockRemoteMediaClient(
            /* itemIds= */ new int[] {1, 2, 3},
            /* currentItemId= */ 3,
            /* currentDurationMs= */ DURATION_3_MS);
    TimelineAsserts.assertPeriodDurations(
        tracker.getCastTimeline(remoteMediaClient),
        C.TIME_UNSET,
        Util.msToUs(DURATION_2_MS),
        Util.msToUs(DURATION_3_MS));

    remoteMediaClient =
        mockRemoteMediaClient(
            /* itemIds= */ new int[] {1, 3},
            /* currentItemId= */ 3,
            /* currentDurationMs= */ DURATION_3_MS);
    TimelineAsserts.assertPeriodDurations(
        tracker.getCastTimeline(remoteMediaClient), C.TIME_UNSET, Util.msToUs(DURATION_3_MS));

    remoteMediaClient =
        mockRemoteMediaClient(
            /* itemIds= */ new int[] {1, 2, 3, 4, 5},
            /* currentItemId= */ 4,
            /* currentDurationMs= */ DURATION_4_MS);
    TimelineAsserts.assertPeriodDurations(
        tracker.getCastTimeline(remoteMediaClient),
        C.TIME_UNSET,
        C.TIME_UNSET,
        Util.msToUs(DURATION_3_MS),
        Util.msToUs(DURATION_4_MS),
        C.TIME_UNSET);

    remoteMediaClient =
        mockRemoteMediaClient(
            /* itemIds= */ new int[] {1, 2, 3, 4, 5},
            /* currentItemId= */ 5,
            /* currentDurationMs= */ DURATION_5_MS);
    TimelineAsserts.assertPeriodDurations(
        tracker.getCastTimeline(remoteMediaClient),
        C.TIME_UNSET,
        C.TIME_UNSET,
        Util.msToUs(DURATION_3_MS),
        Util.msToUs(DURATION_4_MS),
        Util.msToUs(DURATION_5_MS));
  }

  @Test
  public void getCastTimeline_registerMediaItemAndReset_correctMediaItemsInTimeline() {
    RemoteMediaClient mockRemoteMediaClient = mock(RemoteMediaClient.class);
    MediaQueue mockMediaQueue = mock(MediaQueue.class);
    MediaStatus mockMediaStatus = mock(MediaStatus.class);
    ImmutableList<MediaItem> playlistMediaItems =
        ImmutableList.of(createMediaItem(0), createMediaItem(1));
    MediaQueueItem[] registeredQueueItems =
        castTimelineTracker.registerMediaItems(playlistMediaItems).queueItems;
    MediaQueueItem queueItem0 =
        new MediaQueueItem.Builder(registeredQueueItems[0].getMedia()).setItemId(0).build();
    MediaQueueItem queueItem1 =
        new MediaQueueItem.Builder(registeredQueueItems[1].getMedia()).setItemId(1).build();
    MediaQueueItem[] playlistMediaQueueItems = new MediaQueueItem[] {queueItem0, queueItem1};
    // Mock remote media client state after adding two items.
    when(mockRemoteMediaClient.getMediaQueue()).thenReturn(mockMediaQueue);
    when(mockMediaQueue.getItemIds()).thenReturn(new int[] {0, 1});
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(mockMediaStatus);
    when(mockMediaStatus.getCurrentItemId()).thenReturn(0);
    when(mockMediaStatus.getMediaInfo()).thenReturn(playlistMediaQueueItems[0].getMedia());
    when(mockMediaStatus.getQueueItems()).thenReturn(Arrays.asList(playlistMediaQueueItems));

    CastTimeline castTimeline = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    assertThat(castTimeline.getWindowCount()).isEqualTo(2);
    assertThat(castTimeline.getWindow(/* windowIndex= */ 0, new Window()).mediaItem)
        .isEqualTo(playlistMediaItems.get(0));
    assertThat(castTimeline.getWindow(/* windowIndex= */ 1, new Window()).mediaItem)
        .isEqualTo(playlistMediaItems.get(1));

    MediaItem thirdMediaItem = createMediaItem(2);
    MediaQueueItem[] newQueueItems =
        castTimelineTracker.registerMediaItems(ImmutableList.of(thirdMediaItem)).queueItems;
    MediaQueueItem thirdMediaQueueItem =
        new MediaQueueItem.Builder(newQueueItems[0].getMedia()).setItemId(2).build();
    // Mock remote media client state after a single item overrides the previous playlist.
    when(mockMediaQueue.getItemIds()).thenReturn(new int[] {2});
    when(mockMediaStatus.getCurrentItemId()).thenReturn(2);
    when(mockMediaStatus.getMediaInfo()).thenReturn(thirdMediaQueueItem.getMedia());
    when(mockMediaStatus.getQueueItems()).thenReturn(ImmutableList.of(thirdMediaQueueItem));

    castTimeline = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    assertThat(castTimeline.getWindowCount()).isEqualTo(1);
    assertThat(castTimeline.getWindow(/* windowIndex= */ 0, new Window()).mediaItem)
        .isEqualTo(thirdMediaItem);
  }

  @Test
  public void getCastTimeline_registerMediaItem_forAddition_correctMediaItemsInTimeline() {
    RemoteMediaClient mockRemoteMediaClient = mock(RemoteMediaClient.class);
    MediaQueue mockMediaQueue = mock(MediaQueue.class);
    MediaStatus mockMediaStatus = mock(MediaStatus.class);
    ImmutableList<MediaItem> playlistMediaItems =
        ImmutableList.of(createMediaItem(0), createMediaItem(1));
    MediaQueueItem[] initialItems =
        castTimelineTracker.registerMediaItems(playlistMediaItems).queueItems;
    MediaQueueItem queueItem0 =
        new MediaQueueItem.Builder(initialItems[0].getMedia()).setItemId(0).build();
    MediaQueueItem queueItem1 =
        new MediaQueueItem.Builder(initialItems[1].getMedia()).setItemId(1).build();
    MediaQueueItem[] playlistQueueItems = new MediaQueueItem[] {queueItem0, queueItem1};
    ImmutableList<MediaItem> secondPlaylistMediaItems =
        new ImmutableList.Builder<MediaItem>()
            .addAll(playlistMediaItems)
            .add(createMediaItem(2))
            .build();
    when(mockRemoteMediaClient.getMediaQueue()).thenReturn(mockMediaQueue);
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(mockMediaStatus);
    // Mock remote media client state after two items have been added.
    when(mockMediaQueue.getItemIds()).thenReturn(new int[] {0, 1});
    when(mockMediaStatus.getCurrentItemId()).thenReturn(0);
    when(mockMediaStatus.getMediaInfo()).thenReturn(playlistQueueItems[0].getMedia());
    when(mockMediaStatus.getQueueItems()).thenReturn(Arrays.asList(playlistQueueItems));

    CastTimeline castTimeline = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    assertThat(castTimeline.getWindowCount()).isEqualTo(2);
    assertThat(castTimeline.getWindow(/* windowIndex= */ 0, new Window()).mediaItem)
        .isEqualTo(playlistMediaItems.get(0));
    assertThat(castTimeline.getWindow(/* windowIndex= */ 1, new Window()).mediaItem)
        .isEqualTo(playlistMediaItems.get(1));

    // Mock remote media client state after adding a third item.
    MediaQueueItem[] additionItems =
        castTimelineTracker.registerMediaItems(ImmutableList.of(secondPlaylistMediaItems.get(2)))
            .queueItems;
    MediaQueueItem thirdQueueItem =
        new MediaQueueItem.Builder(additionItems[0].getMedia()).setItemId(2).build();
    List<MediaQueueItem> playlistThreeQueueItems =
        new ArrayList<>(Arrays.asList(playlistQueueItems));
    playlistThreeQueueItems.add(thirdQueueItem);
    when(mockMediaQueue.getItemIds()).thenReturn(new int[] {0, 1, 2});
    when(mockMediaStatus.getQueueItems()).thenReturn(playlistThreeQueueItems);

    castTimeline = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    assertThat(castTimeline.getWindowCount()).isEqualTo(3);
    assertThat(castTimeline.getWindow(/* windowIndex= */ 0, new Window()).mediaItem)
        .isEqualTo(secondPlaylistMediaItems.get(0));
    assertThat(castTimeline.getWindow(/* windowIndex= */ 1, new Window()).mediaItem)
        .isEqualTo(secondPlaylistMediaItems.get(1));
    assertThat(castTimeline.getWindow(/* windowIndex= */ 2, new Window()).mediaItem)
        .isEqualTo(secondPlaylistMediaItems.get(2));
  }

  @Test
  public void getCastTimeline_itemsRemoved_correctMediaItemsInTimelineAndMapCleanedUp() {
    RemoteMediaClient mockRemoteMediaClient = mock(RemoteMediaClient.class);
    MediaQueue mockMediaQueue = mock(MediaQueue.class);
    MediaStatus mockMediaStatus = mock(MediaStatus.class);
    ImmutableList<MediaItem> playlistMediaItems =
        ImmutableList.of(createMediaItem(0), createMediaItem(1));
    MediaQueueItem[] registeredItems =
        castTimelineTracker.registerMediaItems(playlistMediaItems).queueItems;
    MediaQueueItem queueItem0 =
        new MediaQueueItem.Builder(registeredItems[0].getMedia()).setItemId(0).build();
    MediaQueueItem queueItem1 =
        new MediaQueueItem.Builder(registeredItems[1].getMedia()).setItemId(1).build();
    MediaQueueItem[] initialPlaylistTwoQueueItems = new MediaQueueItem[] {queueItem0, queueItem1};
    when(mockRemoteMediaClient.getMediaQueue()).thenReturn(mockMediaQueue);
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(mockMediaStatus);
    // Mock remote media client state with two items in the queue.
    when(mockMediaQueue.getItemIds()).thenReturn(new int[] {0, 1});
    when(mockMediaStatus.getCurrentItemId()).thenReturn(0);
    when(mockMediaStatus.getMediaInfo()).thenReturn(initialPlaylistTwoQueueItems[0].getMedia());
    when(mockMediaStatus.getQueueItems()).thenReturn(Arrays.asList(initialPlaylistTwoQueueItems));

    CastTimeline castTimeline = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    assertThat(castTimeline.getWindowCount()).isEqualTo(2);
    assertThat(castTimelineTracker.mediaItemsByContentId).hasSize(2);

    // Mock remote media client state after the first item has been removed.
    when(mockMediaQueue.getItemIds()).thenReturn(new int[] {1});
    when(mockMediaStatus.getCurrentItemId()).thenReturn(1);
    when(mockMediaStatus.getMediaInfo()).thenReturn(initialPlaylistTwoQueueItems[1].getMedia());
    when(mockMediaStatus.getQueueItems())
        .thenReturn(ImmutableList.of(initialPlaylistTwoQueueItems[1]));

    castTimeline = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    assertThat(castTimeline.getWindowCount()).isEqualTo(1);
    assertThat(castTimeline.getWindow(/* windowIndex= */ 0, new Window()).mediaItem)
        .isEqualTo(playlistMediaItems.get(1));
    // Assert that the removed item has been removed from the content ID map.
    assertThat(castTimelineTracker.mediaItemsByContentId).hasSize(1);

    // Mock remote media client state for empty queue.
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(null);
    when(mockMediaQueue.getItemIds()).thenReturn(new int[0]);
    when(mockMediaStatus.getCurrentItemId()).thenReturn(MediaQueueItem.INVALID_ITEM_ID);
    when(mockMediaStatus.getMediaInfo()).thenReturn(null);
    when(mockMediaStatus.getQueueItems()).thenReturn(ImmutableList.of());

    castTimeline = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    assertThat(castTimeline.getWindowCount()).isEqualTo(0);
    // Queue is not emptied when remote media client is empty. See [Internal ref: b/128825216].
    assertThat(castTimelineTracker.mediaItemsByContentId).hasSize(1);
  }

  @Test
  public void getCastTimeline_mediaStatusIsNull_returnsEmptyTimeline() {
    RemoteMediaClient mockRemoteMediaClient = mock(RemoteMediaClient.class);
    MediaQueue mediaQueue = mock(MediaQueue.class);
    when(mockRemoteMediaClient.getMediaQueue()).thenReturn(mediaQueue);
    when(mediaQueue.getItemIds()).thenReturn(new int[0]);
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(null);

    assertThat(castTimelineTracker.getCastTimeline(mockRemoteMediaClient).isEmpty()).isTrue();
  }

  @Test
  public void getCastTimeline_mediaInfoIsNull_returnsEmptyTimeline() {
    RemoteMediaClient mockRemoteMediaClient = mock(RemoteMediaClient.class);
    MediaQueue mediaQueue = mock(MediaQueue.class);
    when(mockRemoteMediaClient.getMediaQueue()).thenReturn(mediaQueue);
    when(mediaQueue.getItemIds()).thenReturn(new int[0]);
    MediaStatus mediaStatus = mock(MediaStatus.class);
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(mediaStatus);
    when(mediaStatus.getMediaInfo()).thenReturn(null);

    assertThat(castTimelineTracker.getCastTimeline(mockRemoteMediaClient).isEmpty()).isTrue();
  }

  @Test
  public void getCastTimeline_noCustomData_returnsFallbackMediaItems() {
    RemoteMediaClient mockRemoteMediaClient = mock(RemoteMediaClient.class);
    MediaQueue mockMediaQueue = mock(MediaQueue.class);
    MediaStatus mockMediaStatus = mock(MediaStatus.class);

    // Construct MediaInfo without custom data
    MediaInfo mediaInfo =
        new MediaInfo.Builder("https://example.com/audio.mp3")
            .setContentType(MimeTypes.AUDIO_MPEG)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .build();
    MediaQueueItem queueItem = new MediaQueueItem.Builder(mediaInfo).setItemId(1).build();

    when(mockRemoteMediaClient.getMediaQueue()).thenReturn(mockMediaQueue);
    when(mockMediaQueue.getItemIds()).thenReturn(new int[] {1});
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(mockMediaStatus);
    when(mockMediaStatus.getCurrentItemId()).thenReturn(1);
    when(mockMediaStatus.getMediaInfo()).thenReturn(mediaInfo);
    when(mockMediaStatus.getQueueItems()).thenReturn(Collections.singletonList(queueItem));

    // This should NOT crash, even if customData is missing
    CastTimeline castTimeline = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    assertThat(castTimeline.getWindowCount()).isEqualTo(1);
    Window window = castTimeline.getWindow(/* windowIndex= */ 0, new Window());
    assertThat(window.mediaItem.localConfiguration.uri.toString())
        .isEqualTo("https://example.com/audio.mp3");
    assertThat(window.mediaItem.localConfiguration.mimeType).isEqualTo(MimeTypes.AUDIO_MPEG);
  }

  @Test
  public void getCastTimeline_receiverReportsStreamTypeLive_windowIsLive() {
    RemoteMediaClient mockRemoteMediaClient = mock(RemoteMediaClient.class);
    MediaQueue mockMediaQueue = mock(MediaQueue.class);
    MediaStatus mockMediaStatus = mock(MediaStatus.class);
    MediaInfo mediaInfo =
        new MediaInfo.Builder("https://example.com/live.m3u8")
            .setContentType(MimeTypes.APPLICATION_M3U8)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .build();
    MediaQueueItem queueItem = new MediaQueueItem.Builder(mediaInfo).setItemId(1).build();
    when(mockRemoteMediaClient.getMediaQueue()).thenReturn(mockMediaQueue);
    when(mockMediaQueue.getItemIds()).thenReturn(new int[] {1});
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(mockMediaStatus);
    when(mockMediaStatus.getCurrentItemId()).thenReturn(1);
    when(mockMediaStatus.getMediaInfo()).thenReturn(mediaInfo);
    when(mockMediaStatus.getQueueItems()).thenReturn(Collections.singletonList(queueItem));

    CastTimeline castTimeline = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    assertThat(castTimeline.getWindowCount()).isEqualTo(1);
    Window window = castTimeline.getWindow(/* windowIndex= */ 0, new Window());
    assertThat(window.isLive()).isTrue();
    assertThat(window.liveConfiguration).isEqualTo(MediaItem.LiveConfiguration.UNSET);
  }

  @Test
  public void getCastTimeline_activeMediaInfoLiveAndQueueItemStale_windowIsLive(
      @TestParameter({"0" /* STREAM_TYPE_NONE */, "1" /* STREAM_TYPE_BUFFERED */})
          int staleStreamType) {
    RemoteMediaClient mockRemoteMediaClient = mock(RemoteMediaClient.class);
    MediaQueue mockMediaQueue = mock(MediaQueue.class);
    MediaStatus mockMediaStatus = mock(MediaStatus.class);
    MediaInfo staleQueueMediaInfo =
        new MediaInfo.Builder("https://example.com/live.m3u8")
            .setContentType(MimeTypes.APPLICATION_M3U8)
            .setStreamType(staleStreamType)
            .build();
    MediaQueueItem queueItem = new MediaQueueItem.Builder(staleQueueMediaInfo).setItemId(1).build();
    MediaInfo activeMediaInfo =
        new MediaInfo.Builder("https://example.com/live.m3u8")
            .setContentType(MimeTypes.APPLICATION_M3U8)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .build();
    when(mockRemoteMediaClient.getMediaQueue()).thenReturn(mockMediaQueue);
    when(mockMediaQueue.getItemIds()).thenReturn(new int[] {1});
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(mockMediaStatus);
    when(mockMediaStatus.getCurrentItemId()).thenReturn(1);
    when(mockMediaStatus.getMediaInfo()).thenReturn(activeMediaInfo);
    when(mockMediaStatus.getQueueItems()).thenReturn(Collections.singletonList(queueItem));

    CastTimeline castTimeline = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    assertThat(castTimeline.getWindowCount()).isEqualTo(1);
    Window window = castTimeline.getWindow(/* windowIndex= */ 0, new Window());
    assertThat(window.isLive()).isTrue();
    assertThat(window.liveConfiguration).isEqualTo(MediaItem.LiveConfiguration.UNSET);
  }

  @Test
  public void getCastTimeline_unconfiguredLiveStreamResolvedByReceiver_windowIsLive() {
    RemoteMediaClient mockRemoteMediaClient = mock(RemoteMediaClient.class);
    MediaQueue mockMediaQueue = mock(MediaQueue.class);
    MediaStatus mockMediaStatus = mock(MediaStatus.class);
    MediaItem unconfiguredItem =
        new MediaItem.Builder()
            .setUri("https://example.com/live.m3u8")
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build();
    MediaQueueItem[] registeredItems =
        castTimelineTracker.registerMediaItems(ImmutableList.of(unconfiguredItem)).queueItems;
    MediaInfo resolvedMediaInfo =
        new MediaInfo.Builder(registeredItems[0].getMedia().getContentId())
            .setContentType(registeredItems[0].getMedia().getContentType())
            .setContentUrl(registeredItems[0].getMedia().getContentUrl())
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setCustomData(registeredItems[0].getMedia().getCustomData())
            .build();
    MediaQueueItem activeQueueItem =
        new MediaQueueItem.Builder(resolvedMediaInfo).setItemId(1).build();
    when(mockRemoteMediaClient.getMediaQueue()).thenReturn(mockMediaQueue);
    when(mockMediaQueue.getItemIds()).thenReturn(new int[] {1});
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(mockMediaStatus);
    when(mockMediaStatus.getCurrentItemId()).thenReturn(1);
    when(mockMediaStatus.getMediaInfo()).thenReturn(resolvedMediaInfo);
    when(mockMediaStatus.getQueueItems()).thenReturn(Collections.singletonList(activeQueueItem));

    CastTimeline castTimeline = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    assertThat(castTimeline.getWindowCount()).isEqualTo(1);
    Window window = castTimeline.getWindow(/* windowIndex= */ 0, new Window());
    assertThat(window.isLive()).isTrue();
    assertThat(window.liveConfiguration).isEqualTo(MediaItem.LiveConfiguration.UNSET);
    assertThat(window.mediaItem).isEqualTo(unconfiguredItem);
  }

  @Test
  public void getCastTimeline_slidingWindowQueueItems_fetchesOutofWindowItemsFromMediaQueue() {
    RemoteMediaClient mockRemoteMediaClient = mock(RemoteMediaClient.class);
    MediaQueue mockMediaQueue = mock(MediaQueue.class);
    MediaStatus mockMediaStatus = mock(MediaStatus.class);
    ImmutableList<MediaItem> playlistMediaItems =
        ImmutableList.of(createMediaItem(0), createMediaItem(1), createMediaItem(2));
    MediaQueueItem[] registeredItems =
        castTimelineTracker.registerMediaItems(playlistMediaItems).queueItems;
    MediaQueueItem queueItem0 =
        new MediaQueueItem.Builder(registeredItems[0].getMedia()).setItemId(0).build();
    MediaQueueItem queueItem1 =
        new MediaQueueItem.Builder(registeredItems[1].getMedia()).setItemId(1).build();
    MediaQueueItem queueItem2 =
        new MediaQueueItem.Builder(registeredItems[2].getMedia()).setItemId(2).build();
    MediaQueueItem[] queueItems = new MediaQueueItem[] {queueItem0, queueItem1, queueItem2};
    when(mockRemoteMediaClient.getMediaQueue()).thenReturn(mockMediaQueue);
    when(mockMediaQueue.getItemIds()).thenReturn(new int[] {0, 1, 2});
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(mockMediaStatus);
    when(mockMediaStatus.getCurrentItemId()).thenReturn(0);
    when(mockMediaStatus.getMediaInfo()).thenReturn(queueItems[0].getMedia());
    // Simulate sliding window: getQueueItems only returns the first 2 items (0 and 1)
    when(mockMediaStatus.getQueueItems()).thenReturn(Arrays.asList(queueItems[0], queueItems[1]));
    // Out-of-window item (index 2) is fetched on demand via mediaQueue.getItemAtIndex(2, true)
    when(mockMediaQueue.getItemAtIndex(2, /* fetchIfNeeded= */ true)).thenReturn(queueItems[2]);

    CastTimeline castTimeline = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    assertThat(castTimeline.getWindowCount()).isEqualTo(3);
    assertThat(castTimeline.getWindow(/* windowIndex= */ 0, new Window()).mediaItem)
        .isEqualTo(playlistMediaItems.get(0));
    assertThat(castTimeline.getWindow(/* windowIndex= */ 1, new Window()).mediaItem)
        .isEqualTo(playlistMediaItems.get(1));
    assertThat(castTimeline.getWindow(/* windowIndex= */ 2, new Window()).mediaItem)
        .isEqualTo(playlistMediaItems.get(2));
  }

  @Test
  public void castTimeline_equals_validatesMediaItems() {
    ImmutableList<MediaItem> playlistMediaItems =
        ImmutableList.of(createMediaItem(0), createMediaItem(1));
    // Setup tracker 1: Item at index 1 is MediaItem.EMPTY (not registered in placeholderTracker)
    CastTimelineTracker placeholderTracker = new CastTimelineTracker(mediaItemConverter);
    MediaQueueItem[] placeholderItems =
        placeholderTracker.registerMediaItems(ImmutableList.of(playlistMediaItems.get(0)))
            .queueItems;
    MediaQueueItem queueItem0 =
        new MediaQueueItem.Builder(placeholderItems[0].getMedia()).setItemId(0).build();
    MediaQueueItem queueItem1 =
        new MediaQueueItem.Builder(mediaItemConverter.toMediaQueueItem(playlistMediaItems.get(1)))
            .setItemId(1)
            .build();
    MediaQueueItem[] queueItems = new MediaQueueItem[] {queueItem0, queueItem1};
    RemoteMediaClient mockPlaceholderClient = mock(RemoteMediaClient.class);
    MediaQueue mockPlaceholderQueue = mock(MediaQueue.class);
    MediaStatus mockPlaceholderStatus = mock(MediaStatus.class);
    when(mockPlaceholderClient.getMediaQueue()).thenReturn(mockPlaceholderQueue);
    when(mockPlaceholderQueue.getItemIds()).thenReturn(new int[] {0, 1});
    when(mockPlaceholderClient.getMediaStatus()).thenReturn(mockPlaceholderStatus);
    when(mockPlaceholderStatus.getCurrentItemId()).thenReturn(0);
    when(mockPlaceholderStatus.getMediaInfo()).thenReturn(queueItems[0].getMedia());
    when(mockPlaceholderStatus.getQueueItems())
        .thenReturn(Collections.singletonList(queueItems[0]));

    // Setup tracker 2: Item at index 1 is resolved to the real local MediaItem
    CastTimelineTracker resolvedTracker = new CastTimelineTracker(mediaItemConverter);
    MediaQueueItem[] resolvedItems =
        resolvedTracker.registerMediaItems(playlistMediaItems).queueItems;
    MediaQueueItem resolvedItem0 =
        new MediaQueueItem.Builder(resolvedItems[0].getMedia()).setItemId(0).build();
    MediaQueueItem resolvedItem1 =
        new MediaQueueItem.Builder(resolvedItems[1].getMedia()).setItemId(1).build();
    RemoteMediaClient mockResolvedClient = mock(RemoteMediaClient.class);
    MediaQueue mockResolvedQueue = mock(MediaQueue.class);
    MediaStatus mockResolvedStatus = mock(MediaStatus.class);
    MediaQueueItem[] resolvedQueueItems = new MediaQueueItem[] {resolvedItem0, resolvedItem1};
    when(mockResolvedClient.getMediaQueue()).thenReturn(mockResolvedQueue);
    when(mockResolvedQueue.getItemIds()).thenReturn(new int[] {0, 1});
    when(mockResolvedClient.getMediaStatus()).thenReturn(mockResolvedStatus);
    when(mockResolvedStatus.getCurrentItemId()).thenReturn(0);
    when(mockResolvedStatus.getMediaInfo()).thenReturn(resolvedQueueItems[0].getMedia());
    when(mockResolvedStatus.getQueueItems())
        .thenReturn(Collections.singletonList(resolvedQueueItems[0]));
    when(mockResolvedQueue.getItemAtIndex(1, /* fetchIfNeeded= */ true))
        .thenReturn(resolvedQueueItems[1]);

    CastTimeline placeholderTimeline = placeholderTracker.getCastTimeline(mockPlaceholderClient);
    CastTimeline resolvedTimeline = resolvedTracker.getCastTimeline(mockResolvedClient);

    assertThat(placeholderTimeline.equals(resolvedTimeline)).isFalse();
    assertThat(placeholderTimeline.hashCode()).isNotEqualTo(resolvedTimeline.hashCode());
  }

  @Test
  public void getCastTimeline_itemFetchInFlight_returnsPlaceholderItem() {
    RemoteMediaClient mockRemoteMediaClient = mock(RemoteMediaClient.class);
    MediaQueue mockMediaQueue = mock(MediaQueue.class);
    MediaStatus mockMediaStatus = mock(MediaStatus.class);
    ImmutableList<MediaItem> playlistMediaItems =
        ImmutableList.of(createMediaItem(0), createMediaItem(1), createMediaItem(2));
    MediaQueueItem[] registeredItems =
        castTimelineTracker.registerMediaItems(playlistMediaItems).queueItems;
    MediaQueueItem queueItem0 =
        new MediaQueueItem.Builder(registeredItems[0].getMedia()).setItemId(0).build();
    MediaQueueItem queueItem1 =
        new MediaQueueItem.Builder(registeredItems[1].getMedia()).setItemId(1).build();
    MediaQueueItem queueItem2 =
        new MediaQueueItem.Builder(registeredItems[2].getMedia()).setItemId(2).build();
    MediaQueueItem[] queueItems = new MediaQueueItem[] {queueItem0, queueItem1, queueItem2};
    when(mockRemoteMediaClient.getMediaQueue()).thenReturn(mockMediaQueue);
    when(mockMediaQueue.getItemIds()).thenReturn(new int[] {0, 1, 2});
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(mockMediaStatus);
    when(mockMediaStatus.getCurrentItemId()).thenReturn(0);
    when(mockMediaStatus.getMediaInfo()).thenReturn(queueItems[0].getMedia());
    when(mockMediaStatus.getQueueItems()).thenReturn(Arrays.asList(queueItems[0], queueItems[1]));
    when(mockMediaQueue.getItemAtIndex(2, /* fetchIfNeeded= */ true)).thenReturn(null);

    CastTimeline timeline = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    assertThat(castTimelineTracker.hasPendingQueueFetches()).isTrue();
    assertThat(timeline.getWindowCount()).isEqualTo(3);
    assertThat(timeline.getWindow(/* windowIndex= */ 2, new Window()).mediaItem)
        .isEqualTo(MediaItem.EMPTY);
  }

  @Test
  public void getCastTimeline_inFlightFetchCompletes_updatesPlaceholderToRealItem() {
    ImmutableList<MediaItem> playlistMediaItems =
        ImmutableList.of(createMediaItem(0), createMediaItem(1), createMediaItem(2));

    // Setup in-flight state (index 2 is MediaItem.EMPTY)
    CastTimelineTracker inFlightTracker = new CastTimelineTracker(mediaItemConverter);
    MediaQueueItem[] inFlightRegistered =
        inFlightTracker.registerMediaItems(playlistMediaItems).queueItems;
    MediaQueueItem queueItem0 =
        new MediaQueueItem.Builder(inFlightRegistered[0].getMedia()).setItemId(0).build();
    MediaQueueItem queueItem1 =
        new MediaQueueItem.Builder(inFlightRegistered[1].getMedia()).setItemId(1).build();
    MediaQueueItem queueItem2 =
        new MediaQueueItem.Builder(inFlightRegistered[2].getMedia()).setItemId(2).build();
    MediaQueueItem[] inFlightQueueItems = new MediaQueueItem[] {queueItem0, queueItem1, queueItem2};
    RemoteMediaClient mockInFlightClient = mock(RemoteMediaClient.class);
    MediaQueue mockInFlightQueue = mock(MediaQueue.class);
    MediaStatus mockInFlightStatus = mock(MediaStatus.class);
    when(mockInFlightClient.getMediaQueue()).thenReturn(mockInFlightQueue);
    when(mockInFlightQueue.getItemIds()).thenReturn(new int[] {0, 1, 2});
    when(mockInFlightClient.getMediaStatus()).thenReturn(mockInFlightStatus);
    when(mockInFlightStatus.getCurrentItemId()).thenReturn(0);
    when(mockInFlightStatus.getMediaInfo()).thenReturn(inFlightQueueItems[0].getMedia());
    when(mockInFlightStatus.getQueueItems())
        .thenReturn(Arrays.asList(inFlightQueueItems[0], inFlightQueueItems[1]));
    when(mockInFlightQueue.getItemAtIndex(2, /* fetchIfNeeded= */ true)).thenReturn(null);

    // Setup completed state (index 2 resolved from MediaQueue cache)
    CastTimelineTracker completedTracker = new CastTimelineTracker(mediaItemConverter);
    MediaQueueItem[] completedRegistered =
        completedTracker.registerMediaItems(playlistMediaItems).queueItems;
    MediaQueueItem completed0 =
        new MediaQueueItem.Builder(completedRegistered[0].getMedia()).setItemId(0).build();
    MediaQueueItem completed1 =
        new MediaQueueItem.Builder(completedRegistered[1].getMedia()).setItemId(1).build();
    MediaQueueItem completed2 =
        new MediaQueueItem.Builder(completedRegistered[2].getMedia()).setItemId(2).build();
    RemoteMediaClient mockCompletedClient = mock(RemoteMediaClient.class);
    MediaQueue mockCompletedQueue = mock(MediaQueue.class);
    MediaStatus mockCompletedStatus = mock(MediaStatus.class);
    MediaQueueItem[] completedQueueItems =
        new MediaQueueItem[] {completed0, completed1, completed2};
    when(mockCompletedClient.getMediaQueue()).thenReturn(mockCompletedQueue);
    when(mockCompletedQueue.getItemIds()).thenReturn(new int[] {0, 1, 2});
    when(mockCompletedClient.getMediaStatus()).thenReturn(mockCompletedStatus);
    when(mockCompletedStatus.getCurrentItemId()).thenReturn(0);
    when(mockCompletedStatus.getMediaInfo()).thenReturn(completedQueueItems[0].getMedia());
    when(mockCompletedStatus.getQueueItems())
        .thenReturn(Arrays.asList(completedQueueItems[0], completedQueueItems[1]));
    when(mockCompletedQueue.getItemAtIndex(2, /* fetchIfNeeded= */ true))
        .thenReturn(completedQueueItems[2]);

    CastTimeline inFlightTimeline = inFlightTracker.getCastTimeline(mockInFlightClient);
    CastTimeline completedTimeline = completedTracker.getCastTimeline(mockCompletedClient);

    assertThat(inFlightTimeline.equals(completedTimeline)).isFalse();
    assertThat(completedTimeline.getWindow(/* windowIndex= */ 2, new Window()).mediaItem)
        .isEqualTo(playlistMediaItems.get(2));
  }

  @Test
  public void getCastTimeline_missingItemsExceedMaxFetchCount_capsNetworkFetchesAtMaxFetchCount() {
    // Create a queue with more missing items than MAX_FETCH_COUNT
    int overflowItems = 15;
    int totalItems = CastTimelineTracker.MAX_FETCH_COUNT + overflowItems;
    MediaQueueItem[] queueItems = new MediaQueueItem[totalItems];
    int[] itemIds = new int[totalItems];
    for (int i = 0; i < totalItems; i++) {
      MediaItem mediaItem = createMediaItem(i);
      queueItems[i] = createMediaQueueItem(mediaItem, ItemUid.generateItemUid(), i);
      itemIds[i] = i;
    }
    RemoteMediaClient mockRemoteMediaClient = mock(RemoteMediaClient.class);
    MediaQueue mockMediaQueue = mock(MediaQueue.class);
    MediaStatus mockMediaStatus = mock(MediaStatus.class);
    when(mockRemoteMediaClient.getMediaQueue()).thenReturn(mockMediaQueue);
    when(mockMediaQueue.getItemIds()).thenReturn(itemIds);
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(mockMediaStatus);
    when(mockMediaStatus.getCurrentItemId()).thenReturn(0);
    when(mockMediaStatus.getMediaInfo()).thenReturn(queueItems[0].getMedia());
    when(mockMediaStatus.getQueueItems()).thenReturn(Collections.singletonList(queueItems[0]));

    CastTimeline unused = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    // First MAX_FETCH_COUNT missing items (indices 1..MAX_FETCH_COUNT) requested with fetchIfNeeded
    // = true
    for (int i = 1; i <= CastTimelineTracker.MAX_FETCH_COUNT; i++) {
      verify(mockMediaQueue).getItemAtIndex(i, /* fetchIfNeeded= */ true);
    }
    // Overflow missing items beyond MAX_FETCH_COUNT requested with fetchIfNeeded = false
    for (int i = CastTimelineTracker.MAX_FETCH_COUNT + 1; i < totalItems; i++) {
      verify(mockMediaQueue).getItemAtIndex(i, /* fetchIfNeeded= */ false);
      verify(mockMediaQueue, never()).getItemAtIndex(i, /* fetchIfNeeded= */ true);
    }
  }

  @Test
  public void getCastTimeline_duplicateMediaItemsInQueue_distinguishesEachItem() {
    RemoteMediaClient mockRemoteMediaClient = mock(RemoteMediaClient.class);
    MediaQueue mockMediaQueue = mock(MediaQueue.class);
    MediaStatus mockMediaStatus = mock(MediaStatus.class);
    MediaItem duplicateItem1 =
        new MediaItem.Builder()
            .setUri("http://example.com/audio.mp3")
            .setMimeType(MimeTypes.AUDIO_MPEG)
            .setTag("first_instance")
            .build();
    MediaItem duplicateItem2 =
        new MediaItem.Builder()
            .setUri("http://example.com/audio.mp3")
            .setMimeType(MimeTypes.AUDIO_MPEG)
            .setTag("second_instance")
            .build();
    MediaQueueItem[] registeredItems =
        castTimelineTracker.registerMediaItems(ImmutableList.of(duplicateItem1, duplicateItem2))
            .queueItems;
    MediaQueueItem queueItem1 =
        new MediaQueueItem.Builder(registeredItems[0].getMedia()).setItemId(101).build();
    MediaQueueItem queueItem2 =
        new MediaQueueItem.Builder(registeredItems[1].getMedia()).setItemId(102).build();
    MediaQueueItem[] playlistMediaQueueItems = new MediaQueueItem[] {queueItem1, queueItem2};
    when(mockRemoteMediaClient.getMediaQueue()).thenReturn(mockMediaQueue);
    when(mockMediaQueue.getItemIds()).thenReturn(new int[] {101, 102});
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(mockMediaStatus);
    when(mockMediaStatus.getCurrentItemId()).thenReturn(101);
    when(mockMediaStatus.getMediaInfo()).thenReturn(queueItem1.getMedia());
    when(mockMediaStatus.getQueueItems()).thenReturn(Arrays.asList(playlistMediaQueueItems));

    CastTimeline castTimeline = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    assertThat(castTimeline.getWindowCount()).isEqualTo(2);
    assertThat(castTimeline.getWindow(/* windowIndex= */ 0, new Window()).mediaItem)
        .isEqualTo(duplicateItem1);
    assertThat(castTimeline.getWindow(/* windowIndex= */ 1, new Window()).mediaItem)
        .isEqualTo(duplicateItem2);
  }

  @Test
  public void getCastTimeline_missingSyntheticId_fallsBackToConverter() {
    RemoteMediaClient mockRemoteMediaClient = mock(RemoteMediaClient.class);
    MediaQueue mockMediaQueue = mock(MediaQueue.class);
    MediaStatus mockMediaStatus = mock(MediaStatus.class);
    MediaInfo mediaInfo =
        new MediaInfo.Builder("https://example.com/external.mp3")
            .setContentType(MimeTypes.AUDIO_MPEG)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .build();
    MediaQueueItem queueItem = new MediaQueueItem.Builder(mediaInfo).setItemId(201).build();
    when(mockRemoteMediaClient.getMediaQueue()).thenReturn(mockMediaQueue);
    when(mockMediaQueue.getItemIds()).thenReturn(new int[] {201});
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(mockMediaStatus);
    when(mockMediaStatus.getCurrentItemId()).thenReturn(201);
    when(mockMediaStatus.getMediaInfo()).thenReturn(mediaInfo);
    when(mockMediaStatus.getQueueItems()).thenReturn(ImmutableList.of(queueItem));

    CastTimeline castTimeline = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    assertThat(castTimeline.getWindowCount()).isEqualTo(1);
    Window window = castTimeline.getWindow(/* windowIndex= */ 0, new Window());
    assertThat(window.mediaItem.localConfiguration.uri.toString())
        .isEqualTo("https://example.com/external.mp3");
  }

  @Test
  public void getCastTimeline_unknownSyntheticId_fallsBackToConverter() throws Exception {
    RemoteMediaClient mockRemoteMediaClient = mock(RemoteMediaClient.class);
    MediaQueue mockMediaQueue = mock(MediaQueue.class);
    MediaStatus mockMediaStatus = mock(MediaStatus.class);
    JSONObject customData = new JSONObject();
    JSONObject mediaItemJson = new JSONObject();
    mediaItemJson.put("uri", "https://example.com/foreign.mp3");
    mediaItemJson.put("mediaId", "foreign_media_id");
    customData.put("mediaItem", mediaItemJson);
    customData.put("m3-syntheticId", "foreign-synthetic-id-999");
    MediaInfo mediaInfo =
        new MediaInfo.Builder("https://example.com/foreign.mp3")
            .setContentType(MimeTypes.AUDIO_MPEG)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setCustomData(customData)
            .build();
    MediaQueueItem queueItem = new MediaQueueItem.Builder(mediaInfo).setItemId(301).build();
    when(mockRemoteMediaClient.getMediaQueue()).thenReturn(mockMediaQueue);
    when(mockMediaQueue.getItemIds()).thenReturn(new int[] {301});
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(mockMediaStatus);
    when(mockMediaStatus.getCurrentItemId()).thenReturn(301);
    when(mockMediaStatus.getMediaInfo()).thenReturn(mediaInfo);
    when(mockMediaStatus.getQueueItems()).thenReturn(ImmutableList.of(queueItem));

    CastTimeline castTimeline = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    assertThat(castTimeline.getWindowCount()).isEqualTo(1);
    Window window = castTimeline.getWindow(/* windowIndex= */ 0, new Window());
    assertThat(window.mediaItem.localConfiguration.uri.toString())
        .isEqualTo("https://example.com/foreign.mp3");
  }

  @Test
  public void registerMediaItems_attachesSyntheticIdToCustomData() throws Exception {
    MediaItem mediaItem1 = new MediaItem.Builder().setUri("http://example.com/1").build();
    MediaItem mediaItem2 = new MediaItem.Builder().setUri("http://example.com/2").build();

    MediaQueueItem[] queueItems =
        castTimelineTracker.registerMediaItems(ImmutableList.of(mediaItem1, mediaItem2)).queueItems;

    assertThat(queueItems).hasLength(2);
    JSONObject customData1 = queueItems[0].getMedia().getCustomData();
    JSONObject customData2 = queueItems[1].getMedia().getCustomData();
    assertThat(customData1).isNotNull();
    assertThat(customData2).isNotNull();
    String syntheticId1 = customData1.getString("m3-syntheticId");
    String syntheticId2 = customData2.getString("m3-syntheticId");
    assertThat(syntheticId1).isNotEmpty();
    assertThat(syntheticId2).isNotEmpty();
    assertThat(syntheticId1).isNotEqualTo(syntheticId2);
  }

  @Test
  public void getCastTimeline_registeredMediaItems_populatesTimelineWithMediaItemsAndSyntheticUids()
      throws Exception {
    MediaItem mediaItem1 =
        new MediaItem.Builder().setUri("http://example.com/1").setTag("tag1").build();
    MediaItem mediaItem2 =
        new MediaItem.Builder().setUri("http://example.com/2").setTag("tag2").build();
    MediaQueueItem[] queueItems =
        castTimelineTracker.registerMediaItems(ImmutableList.of(mediaItem1, mediaItem2)).queueItems;
    String syntheticId1 = queueItems[0].getMedia().getCustomData().getString("m3-syntheticId");
    String syntheticId2 = queueItems[1].getMedia().getCustomData().getString("m3-syntheticId");
    MediaQueueItem queueItem1 =
        new MediaQueueItem.Builder(queueItems[0].getMedia()).setItemId(101).build();
    MediaQueueItem queueItem2 =
        new MediaQueueItem.Builder(queueItems[1].getMedia()).setItemId(102).build();
    RemoteMediaClient mockRemoteMediaClient = mock(RemoteMediaClient.class);
    MediaQueue mockMediaQueue = mock(MediaQueue.class);
    MediaStatus mockMediaStatus = mock(MediaStatus.class);
    when(mockRemoteMediaClient.getMediaQueue()).thenReturn(mockMediaQueue);
    when(mockMediaQueue.getItemIds()).thenReturn(new int[] {101, 102});
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(mockMediaStatus);
    when(mockMediaStatus.getCurrentItemId()).thenReturn(101);
    when(mockMediaStatus.getMediaInfo()).thenReturn(queueItem1.getMedia());
    when(mockMediaStatus.getQueueItems()).thenReturn(ImmutableList.of(queueItem1, queueItem2));

    CastTimeline timeline = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    assertThat(timeline.getWindowCount()).isEqualTo(2);
    Window window1 = timeline.getWindow(/* windowIndex= */ 0, new Window());
    Window window2 = timeline.getWindow(/* windowIndex= */ 1, new Window());
    assertThat(window1.mediaItem).isEqualTo(mediaItem1);
    assertThat(window2.mediaItem).isEqualTo(mediaItem2);
    assertThat(window1.uid).isEqualTo(ItemUid.of(syntheticId1));
    assertThat(window2.uid).isEqualTo(ItemUid.of(syntheticId2));
  }

  @Test
  public void registerMediaItems_multipleRegistrations_resolvesAllItemsInTimeline()
      throws Exception {
    MediaItem mediaItem1 =
        new MediaItem.Builder().setUri("http://example.com/1").setTag("tag1").build();
    CastTimelineTracker.RegisteredMediaItems initialResult =
        castTimelineTracker.registerMediaItems(ImmutableList.of(mediaItem1));
    MediaQueueItem[] initialQueueItems = initialResult.queueItems;
    MediaItem mediaItem2 =
        new MediaItem.Builder().setUri("http://example.com/2").setTag("tag2").build();
    CastTimelineTracker.RegisteredMediaItems additionalResult =
        castTimelineTracker.registerMediaItems(ImmutableList.of(mediaItem2));
    MediaQueueItem[] additionalQueueItems = additionalResult.queueItems;
    MediaQueueItem queueItem1 =
        new MediaQueueItem.Builder(initialQueueItems[0].getMedia()).setItemId(101).build();
    MediaQueueItem queueItem2 =
        new MediaQueueItem.Builder(additionalQueueItems[0].getMedia()).setItemId(102).build();
    RemoteMediaClient mockRemoteMediaClient = mock(RemoteMediaClient.class);
    MediaQueue mockMediaQueue = mock(MediaQueue.class);
    MediaStatus mockMediaStatus = mock(MediaStatus.class);
    when(mockRemoteMediaClient.getMediaQueue()).thenReturn(mockMediaQueue);
    when(mockMediaQueue.getItemIds()).thenReturn(new int[] {101, 102});
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(mockMediaStatus);
    when(mockMediaStatus.getCurrentItemId()).thenReturn(101);
    when(mockMediaStatus.getMediaInfo()).thenReturn(queueItem1.getMedia());
    when(mockMediaStatus.getQueueItems()).thenReturn(ImmutableList.of(queueItem1, queueItem2));

    CastTimeline timeline = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    assertThat(timeline.getWindowCount()).isEqualTo(2);
    assertThat(timeline.getWindow(/* windowIndex= */ 0, new Window()).mediaItem)
        .isEqualTo(mediaItem1);
    assertThat(timeline.getWindow(/* windowIndex= */ 1, new Window()).mediaItem)
        .isEqualTo(mediaItem2);
  }

  @Test
  public void reset_clearsPreviousRegistrationsInTracker() throws Exception {
    MediaItem mediaItem1 =
        new MediaItem.Builder().setUri("http://example.com/1").setTag("tag1").build();
    MediaQueueItem[] initialQueueItems =
        castTimelineTracker.registerMediaItems(ImmutableList.of(mediaItem1)).queueItems;

    castTimelineTracker.reset();
    MediaItem mediaItem2 =
        new MediaItem.Builder().setUri("http://example.com/2").setTag("tag2").build();
    MediaQueueItem[] newQueueItems =
        castTimelineTracker.registerMediaItems(ImmutableList.of(mediaItem2)).queueItems;
    MediaQueueItem oldQueueItem =
        new MediaQueueItem.Builder(initialQueueItems[0].getMedia()).setItemId(101).build();
    MediaQueueItem newQueueItem =
        new MediaQueueItem.Builder(newQueueItems[0].getMedia()).setItemId(102).build();
    RemoteMediaClient mockRemoteMediaClient = mock(RemoteMediaClient.class);
    MediaQueue mockMediaQueue = mock(MediaQueue.class);
    MediaStatus mockMediaStatus = mock(MediaStatus.class);
    when(mockRemoteMediaClient.getMediaQueue()).thenReturn(mockMediaQueue);
    when(mockMediaQueue.getItemIds()).thenReturn(new int[] {101, 102});
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(mockMediaStatus);
    when(mockMediaStatus.getCurrentItemId()).thenReturn(102);
    when(mockMediaStatus.getMediaInfo()).thenReturn(newQueueItem.getMedia());
    when(mockMediaStatus.getQueueItems()).thenReturn(ImmutableList.of(oldQueueItem, newQueueItem));

    CastTimeline timeline = castTimelineTracker.getCastTimeline(mockRemoteMediaClient);

    assertThat(timeline.getWindowCount()).isEqualTo(2);
    // Old item was cleared by reset(): sender-only custom tag is
    // lost, fallback resolved from converter
    assertThat(
            timeline.getWindow(/* windowIndex= */ 0, new Window()).mediaItem.localConfiguration.tag)
        .isNull();
    // New item registered after reset retains its custom sender tag
    assertThat(timeline.getWindow(/* windowIndex= */ 1, new Window()).mediaItem)
        .isEqualTo(mediaItem2);
  }

  @Test
  public void registerMediaItems_customDataIsNull_doesNotAttachCustomData() {
    MediaItemConverter customConverter =
        new MediaItemConverter() {
          @Override
          public MediaQueueItem toMediaQueueItem(MediaItem mediaItem) {
            MediaInfo mediaInfo =
                new MediaInfo.Builder(mediaItem.mediaId).setContentType("video/mp4").build();
            return new MediaQueueItem.Builder(mediaInfo).build();
          }

          @Override
          public MediaItem toMediaItem(MediaQueueItem mediaQueueItem) {
            return new MediaItem.Builder()
                .setMediaId(mediaQueueItem.getMedia().getContentId())
                .build();
          }
        };
    CastTimelineTracker customTracker = new CastTimelineTracker(customConverter);

    MediaItem mediaItem = new MediaItem.Builder().setMediaId("custom_media_id").build();
    MediaQueueItem[] queueItems =
        customTracker.registerMediaItems(ImmutableList.of(mediaItem)).queueItems;

    assertThat(queueItems).hasLength(1);
    assertThat(queueItems[0].getMedia().getCustomData()).isNull();
  }

  @Test
  public void
      getCastTimeline_inFlightItemAddition_preservesInFlightItemAcrossIntermediateUpdates() {
    MediaItem item0 =
        new MediaItem.Builder()
            .setUri("http://example.com/0")
            .setMediaId("item_0")
            .setTag("custom_tag_0")
            .build();
    CastTimelineTracker.RegisteredMediaItems initialResult =
        castTimelineTracker.registerMediaItems(ImmutableList.of(item0));
    MediaQueueItem[] initialQueueItems = initialResult.queueItems;
    MediaQueueItem queueItem0 =
        new MediaQueueItem.Builder(initialQueueItems[0].getMedia()).setItemId(100).build();
    RemoteMediaClient mockClient = mock(RemoteMediaClient.class);
    MediaQueue mockQueue = mock(MediaQueue.class);
    MediaStatus mockStatus = mock(MediaStatus.class);
    when(mockClient.getMediaQueue()).thenReturn(mockQueue);
    when(mockClient.getMediaStatus()).thenReturn(mockStatus);
    when(mockQueue.getItemIds()).thenReturn(new int[] {100});
    when(mockStatus.getCurrentItemId()).thenReturn(100);
    when(mockStatus.getMediaInfo()).thenReturn(queueItem0.getMedia());
    when(mockStatus.getQueueItems()).thenReturn(ImmutableList.of(queueItem0));
    // Client initiates addition of Item 1 (in-flight)
    MediaItem item1 =
        new MediaItem.Builder()
            .setUri("http://example.com/1")
            .setMediaId("item_1")
            .setTag("custom_tag_1")
            .build();
    MediaQueueItem[] inFlightQueueItems =
        castTimelineTracker.registerMediaItems(ImmutableList.of(item1)).queueItems;
    // Intermediate getCastTimeline call while queue addition is still in-flight would trigger
    // clean-up of unused items in the tracker. The in-flight item should be preserved.
    CastTimeline unused = castTimelineTracker.getCastTimeline(mockClient);
    MediaQueueItem queueItem1 =
        new MediaQueueItem.Builder(inFlightQueueItems[0].getMedia()).setItemId(101).build();
    when(mockQueue.getItemIds()).thenReturn(new int[] {100, 101});
    when(mockStatus.getQueueItems()).thenReturn(ImmutableList.of(queueItem0, queueItem1));

    CastTimeline confirmedTimeline = castTimelineTracker.getCastTimeline(mockClient);

    assertThat(confirmedTimeline.getWindowCount()).isEqualTo(2);
    Window window1 = confirmedTimeline.getWindow(/* windowIndex= */ 1, new Window());
    assertThat(window1.mediaItem).isEqualTo(item1);
    assertThat(window1.mediaItem.localConfiguration.tag).isEqualTo("custom_tag_1");
  }

  @Test
  public void registerMediaItems_registersPendingItemsAndBindsUidOnConfirmation() {
    MediaItem item =
        new MediaItem.Builder()
            .setUri("http://example.com/item")
            .setMediaId("item_0")
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(5000).build())
            .build();

    CastTimelineTracker.RegisteredMediaItems result =
        castTimelineTracker.registerMediaItems(ImmutableList.of(item));

    assertThat(result.itemUids).hasSize(1);
    ItemUid registeredUid = result.itemUids.get(0);
    assertThat(castTimelineTracker.getReceiverItemId(registeredUid))
        .isEqualTo(MediaQueueItem.INVALID_ITEM_ID);

    MediaQueueItem receiverQueueItem =
        new MediaQueueItem.Builder(result.queueItems[0].getMedia()).setItemId(100).build();
    RemoteMediaClient mockClient = mock(RemoteMediaClient.class);
    MediaQueue mockQueue = mock(MediaQueue.class);
    MediaStatus mockStatus = mock(MediaStatus.class);
    when(mockClient.getMediaQueue()).thenReturn(mockQueue);
    when(mockClient.getMediaStatus()).thenReturn(mockStatus);
    when(mockQueue.getItemIds()).thenReturn(new int[] {100});
    when(mockStatus.getCurrentItemId()).thenReturn(100);
    when(mockStatus.getMediaInfo()).thenReturn(receiverQueueItem.getMedia());
    when(mockStatus.getQueueItems()).thenReturn(ImmutableList.of(receiverQueueItem));

    CastTimeline timeline = castTimelineTracker.getCastTimeline(mockClient);

    assertThat(timeline.getWindowCount()).isEqualTo(1);
    Window window = timeline.getWindow(/* windowIndex= */ 0, new Window());
    assertThat(window.uid).isEqualTo(registeredUid);
    assertThat(window.mediaItem).isEqualTo(item);
    assertThat(castTimelineTracker.getReceiverItemId(registeredUid)).isEqualTo(100);
  }

  @Test
  public void
      registerMediaItems_multipleRegistrations_registersPendingItemsAndBindsUidOnConfirmation() {
    MediaItem item0 =
        new MediaItem.Builder().setUri("http://example.com/0").setMediaId("item_0").build();
    CastTimelineTracker.RegisteredMediaItems setResult =
        castTimelineTracker.registerMediaItems(ImmutableList.of(item0));

    MediaItem item1 =
        new MediaItem.Builder()
            .setUri("http://example.com/1")
            .setMediaId("item_1")
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(3000).build())
            .build();
    CastTimelineTracker.RegisteredMediaItems addResult =
        castTimelineTracker.registerMediaItems(ImmutableList.of(item1));

    assertThat(addResult.itemUids).hasSize(1);
    ItemUid addedUid = addResult.itemUids.get(0);
    assertThat(castTimelineTracker.getReceiverItemId(addedUid))
        .isEqualTo(MediaQueueItem.INVALID_ITEM_ID);

    MediaQueueItem receiverQueueItem1 =
        new MediaQueueItem.Builder(addResult.queueItems[0].getMedia()).setItemId(101).build();
    MediaQueueItem receiverQueueItem0 =
        new MediaQueueItem.Builder(setResult.queueItems[0].getMedia()).setItemId(100).build();
    RemoteMediaClient mockClient = mock(RemoteMediaClient.class);
    MediaQueue mockQueue = mock(MediaQueue.class);
    MediaStatus mockStatus = mock(MediaStatus.class);
    when(mockClient.getMediaQueue()).thenReturn(mockQueue);
    when(mockClient.getMediaStatus()).thenReturn(mockStatus);
    when(mockQueue.getItemIds()).thenReturn(new int[] {101, 100});
    when(mockStatus.getCurrentItemId()).thenReturn(101);
    when(mockStatus.getMediaInfo()).thenReturn(receiverQueueItem1.getMedia());
    when(mockStatus.getQueueItems())
        .thenReturn(ImmutableList.of(receiverQueueItem1, receiverQueueItem0));

    CastTimeline timeline = castTimelineTracker.getCastTimeline(mockClient);

    assertThat(timeline.getWindowCount()).isEqualTo(2);
    Window confirmedWindow0 = timeline.getWindow(/* windowIndex= */ 0, new Window());
    assertThat(confirmedWindow0.uid).isEqualTo(addedUid);
    assertThat(confirmedWindow0.mediaItem).isEqualTo(item1);
    assertThat(castTimelineTracker.getReceiverItemId(addedUid)).isEqualTo(101);
  }

  @Test
  public void
      getCastTimeline_lateJoiningSender_doesNotReplacePlaceholderUidWithForeignSyntheticId() {
    MediaItem item0 = createMediaItem(0);
    MediaItem item1 = createMediaItem(1);
    MediaQueueItem queueItem0 = createMediaQueueItem(item0, ItemUid.of("foreign-uid-0"), 100);
    MediaQueueItem queueItem1 = createMediaQueueItem(item1, ItemUid.of("foreign-uid-1"), 101);

    RemoteMediaClient mockClient = mock(RemoteMediaClient.class);
    MediaQueue mockQueue = mock(MediaQueue.class);
    MediaStatus mockStatus = mock(MediaStatus.class);
    when(mockClient.getMediaQueue()).thenReturn(mockQueue);
    when(mockClient.getMediaStatus()).thenReturn(mockStatus);
    when(mockQueue.getItemIds()).thenReturn(new int[] {100, 101});
    when(mockStatus.getCurrentItemId()).thenReturn(100);
    when(mockStatus.getMediaInfo()).thenReturn(queueItem0.getMedia());
    // Pass 1: item 100 is in MediaStatus, item 101 is outside the initial window (uncached).
    when(mockStatus.getQueueItems()).thenReturn(ImmutableList.of(queueItem0));
    when(mockQueue.getItemAtIndex(1, /* fetchIfNeeded= */ true)).thenReturn(null);

    CastTimeline incompleteTimeline = castTimelineTracker.getCastTimeline(mockClient);

    assertThat(castTimelineTracker.hasPendingQueueFetches()).isTrue();
    assertThat(incompleteTimeline.getWindowCount()).isEqualTo(2);
    // Item 100 was immediately present in MediaStatus, so it gets the foreign synthetic UID.
    assertThat(incompleteTimeline.getWindow(0, new Window()).uid)
        .isEqualTo(ItemUid.of("foreign-uid-0"));
    // Item 101 was uncached on Pass 1, so it gets a generated placeholder UID.
    Object placeholderUid1 = incompleteTimeline.getWindow(1, new Window()).uid;
    assertThat(placeholderUid1).isNotEqualTo(ItemUid.of("foreign-uid-1"));
    assertThat(incompleteTimeline.getWindow(1, new Window()).mediaItem).isEqualTo(MediaItem.EMPTY);

    // Pass 2: MediaQueue finishes fetching item 101 (which carries "foreign-uid-1" from another
    // sender). Since "foreign-uid-1" was not registered in this sender's mediaItemsBySyntheticId,
    // the tracker must preserve placeholderUid1 to avoid UID churn while populating its MediaItem.
    when(mockQueue.getItemAtIndex(1, /* fetchIfNeeded= */ true)).thenReturn(queueItem1);

    CastTimeline completeTimeline = castTimelineTracker.getCastTimeline(mockClient);

    assertThat(castTimelineTracker.hasPendingQueueFetches()).isFalse();
    assertThat(completeTimeline.getWindow(1, new Window()).uid).isEqualTo(placeholderUid1);
    assertThat(completeTimeline.getWindow(1, new Window()).mediaItem)
        .isEqualTo(mediaItemConverter.toMediaItem(queueItem1));
  }

  @Test
  public void
      getCastTimeline_whenLoadingNewItemWhileCurrentItemIdIsStale_preservesDistinctItemUids() {
    RemoteMediaClient mockClient = mock(RemoteMediaClient.class);
    MediaQueue mockQueue = mock(MediaQueue.class);
    MediaStatus mockStatus = mock(MediaStatus.class);
    when(mockClient.getMediaQueue()).thenReturn(mockQueue);
    when(mockClient.getMediaStatus()).thenReturn(mockStatus);

    ImmutableList<MediaItem> playlist = ImmutableList.of(createMediaItem(0), createMediaItem(1));
    CastTimelineTracker.RegisteredMediaItems registered =
        castTimelineTracker.registerMediaItems(playlist);
    MediaQueueItem queueItem0 =
        new MediaQueueItem.Builder(registered.queueItems[0].getMedia()).setItemId(10).build();
    MediaQueueItem queueItem1 =
        new MediaQueueItem.Builder(registered.queueItems[1].getMedia()).setItemId(20).build();

    // Receiver is loading item 20 after a seek, while currentItemId still reports item 10 and
    // mediaStatus.getMediaInfo() already describes item 20.
    when(mockQueue.getItemIds()).thenReturn(new int[] {10, 20});
    when(mockStatus.getCurrentItemId()).thenReturn(10);
    when(mockStatus.getLoadingItemId()).thenReturn(20);
    when(mockStatus.getMediaInfo()).thenReturn(queueItem1.getMedia());
    when(mockStatus.getQueueItems()).thenReturn(ImmutableList.of(queueItem0));
    when(mockQueue.getItemAtIndex(1, /* fetchIfNeeded= */ true)).thenReturn(queueItem1);

    CastTimeline timeline = castTimelineTracker.getCastTimeline(mockClient);

    assertThat(timeline.getWindowCount()).isEqualTo(2);
    assertThat(timeline.getWindow(0, new Window()).uid).isEqualTo(registered.itemUids.get(0));
    assertThat(timeline.getWindow(1, new Window()).uid).isEqualTo(registered.itemUids.get(1));
    assertThat(castTimelineTracker.getItemUid(10)).isEqualTo(registered.itemUids.get(0));
    assertThat(castTimelineTracker.getItemUid(20)).isEqualTo(registered.itemUids.get(1));
  }

  private MediaItem createMediaItem(int uid) {
    return new MediaItem.Builder()
        .setUri("http://www.google.com/" + uid)
        .setMimeType(MimeTypes.AUDIO_MPEG)
        .setTag(uid)
        .build();
  }

  private static MediaQueueItem createMediaQueueItem(
      MediaItem mediaItem, ItemUid itemUid, int uid) {
    MediaQueueItem queueItem =
        new MediaQueueItem.Builder(new DefaultMediaItemConverter().toMediaQueueItem(mediaItem))
            .setItemId(uid)
            .build();
    JSONObject customData =
        queueItem.getMedia() != null ? queueItem.getMedia().getCustomData() : null;
    if (customData != null) {
      try {
        customData.put(CastTimelineTracker.KEY_SYNTHETIC_ID, itemUid.toString());
      } catch (JSONException e) {
        throw new RuntimeException(e);
      }
    }
    return queueItem;
  }

  private static RemoteMediaClient mockRemoteMediaClient(
      int[] itemIds, int currentItemId, long currentDurationMs) {
    RemoteMediaClient remoteMediaClient = mock(RemoteMediaClient.class);
    MediaStatus status = mock(MediaStatus.class);
    when(status.getQueueItems()).thenReturn(Collections.emptyList());
    when(remoteMediaClient.getMediaStatus()).thenReturn(status);
    when(status.getMediaInfo()).thenReturn(getMediaInfo(currentDurationMs));
    when(status.getCurrentItemId()).thenReturn(currentItemId);
    MediaQueue mediaQueue = mockMediaQueue(itemIds);
    when(remoteMediaClient.getMediaQueue()).thenReturn(mediaQueue);
    return remoteMediaClient;
  }

  private static MediaQueue mockMediaQueue(int[] itemIds) {
    MediaQueue mediaQueue = mock(MediaQueue.class);
    when(mediaQueue.getItemIds()).thenReturn(itemIds);
    return mediaQueue;
  }

  private static MediaInfo getMediaInfo(long durationMs) {
    return new MediaInfo.Builder(/* contentId= */ "")
        .setStreamDuration(durationMs)
        .setContentType(MimeTypes.APPLICATION_MP4)
        .setStreamType(MediaInfo.STREAM_TYPE_NONE)
        .build();
  }
}
