/*
 * Copyright 2025 The Android Open Source Project
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
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.media3.common.C;
import androidx.media3.common.util.BackgroundExecutor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLiveSeekableRange;
import com.google.android.gms.cast.MediaQueueItem;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class CastUtilsTest {

  @Test
  public void checkRunningOnMainThread_onMainThread_doesNotThrowException() {
    CastUtils.verifyMainThread();
  }

  @Test
  public void checkRunningOnMainThread_onBackgroundThread_throwsIllegalStateException()
      throws Exception {
    AtomicReference<Throwable> exceptionRef = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    BackgroundExecutor.get()
        .execute(() -> {
          try {
            CastUtils.verifyMainThread();
          } catch (Throwable t) {
            exceptionRef.set(t);
          } finally {
            latch.countDown();
          }
        });
    assertThat(latch.await(5, SECONDS)).isTrue();

    Throwable thrown = exceptionRef.get();
    assertThat(thrown).isInstanceOf(IllegalStateException.class);
  }

  @Test
  public void hasQueueEnded_whenIdleWithReasonFinishedAndNoActiveOrLoadingItem_returnsTrue() {
    RemoteMediaClient mockClient = mock(RemoteMediaClient.class);
    MediaStatus mockStatus = mock(MediaStatus.class);
    when(mockClient.getMediaStatus()).thenReturn(mockStatus);
    when(mockClient.getPlayerState()).thenReturn(MediaStatus.PLAYER_STATE_IDLE);
    when(mockClient.getIdleReason()).thenReturn(MediaStatus.IDLE_REASON_FINISHED);
    when(mockStatus.getLoadingItemId()).thenReturn(MediaQueueItem.INVALID_ITEM_ID);
    when(mockStatus.getCurrentItemId()).thenReturn(MediaQueueItem.INVALID_ITEM_ID);

    assertThat(CastUtils.hasQueueEnded(mockClient)).isTrue();
  }

  @Test
  public void hasQueueEnded_whenIdleWithReasonFinishedButNextItemLoading_returnsFalse() {
    RemoteMediaClient mockClient = mock(RemoteMediaClient.class);
    MediaStatus mockStatus = mock(MediaStatus.class);
    when(mockClient.getMediaStatus()).thenReturn(mockStatus);
    when(mockClient.getPlayerState()).thenReturn(MediaStatus.PLAYER_STATE_IDLE);
    when(mockClient.getIdleReason()).thenReturn(MediaStatus.IDLE_REASON_FINISHED);
    when(mockStatus.getLoadingItemId()).thenReturn(2);

    assertThat(CastUtils.hasQueueEnded(mockClient)).isFalse();
  }

  @Test
  public void hasQueueEnded_whenIdleWithReasonInterrupted_returnsFalse() {
    RemoteMediaClient mockClient = mock(RemoteMediaClient.class);
    MediaStatus mockStatus = mock(MediaStatus.class);
    when(mockClient.getMediaStatus()).thenReturn(mockStatus);
    when(mockClient.getPlayerState()).thenReturn(MediaStatus.PLAYER_STATE_IDLE);
    when(mockClient.getIdleReason()).thenReturn(MediaStatus.IDLE_REASON_INTERRUPTED);
    when(mockStatus.getLoadingItemId()).thenReturn(MediaQueueItem.INVALID_ITEM_ID);
    when(mockStatus.getCurrentItemId()).thenReturn(MediaQueueItem.INVALID_ITEM_ID);

    assertThat(CastUtils.hasQueueEnded(mockClient)).isFalse();
  }

  @Test
  public void hasQueueEnded_whenPlaying_returnsFalse() {
    RemoteMediaClient mockClient = mock(RemoteMediaClient.class);
    MediaStatus mockStatus = mock(MediaStatus.class);
    when(mockClient.getMediaStatus()).thenReturn(mockStatus);
    when(mockClient.getPlayerState()).thenReturn(MediaStatus.PLAYER_STATE_PLAYING);
    when(mockClient.getIdleReason()).thenReturn(MediaStatus.IDLE_REASON_NONE);

    assertThat(CastUtils.hasQueueEnded(mockClient)).isFalse();
  }

  @Test
  public void hasQueueEnded_whenMediaStatusIsNull_returnsFalse() {
    RemoteMediaClient mockClient = mock(RemoteMediaClient.class);
    when(mockClient.getMediaStatus()).thenReturn(null);
    when(mockClient.getPlayerState()).thenReturn(MediaStatus.PLAYER_STATE_IDLE);
    when(mockClient.getIdleReason()).thenReturn(MediaStatus.IDLE_REASON_FINISHED);

    assertThat(CastUtils.hasQueueEnded(mockClient)).isFalse();
  }

  @Test
  public void hasQueueEnded_whenIdleWithReasonFinishedButCurrentItemStillPresent_returnsFalse() {
    RemoteMediaClient mockClient = mock(RemoteMediaClient.class);
    MediaStatus mockStatus = mock(MediaStatus.class);
    when(mockClient.getMediaStatus()).thenReturn(mockStatus);
    when(mockClient.getPlayerState()).thenReturn(MediaStatus.PLAYER_STATE_IDLE);
    when(mockClient.getIdleReason()).thenReturn(MediaStatus.IDLE_REASON_FINISHED);
    when(mockStatus.getLoadingItemId()).thenReturn(MediaQueueItem.INVALID_ITEM_ID);
    when(mockStatus.getCurrentItemId()).thenReturn(1);

    assertThat(CastUtils.hasQueueEnded(mockClient)).isFalse();
  }

  @Test
  public void getLiveSeekableRangeStartMs_whenLiveStreamWithRange_returnsApproximateStart() {
    RemoteMediaClient mockClient = mock(RemoteMediaClient.class);
    MediaStatus mockStatus = mock(MediaStatus.class);
    MediaInfo liveMediaInfo =
        new MediaInfo.Builder("https://example.com/live.m3u8")
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .build();
    MediaLiveSeekableRange seekableRange =
        new MediaLiveSeekableRange.Builder().setStartTime(100_000L).setEndTime(160_000L).build();
    when(mockClient.getMediaStatus()).thenReturn(mockStatus);
    when(mockStatus.getMediaInfo()).thenReturn(liveMediaInfo);
    when(mockStatus.getLiveSeekableRange()).thenReturn(seekableRange);
    when(mockClient.getApproximateLiveSeekableRangeStart()).thenReturn(105_000L);

    assertThat(CastUtils.getLiveSeekableRangeStartMs(mockClient)).isEqualTo(105_000L);
  }

  @Test
  public void getLiveSeekableRangeStartMs_whenVodStream_returnsTimeUnset() {
    RemoteMediaClient mockClient = mock(RemoteMediaClient.class);
    MediaStatus mockStatus = mock(MediaStatus.class);
    MediaInfo vodMediaInfo =
        new MediaInfo.Builder("https://example.com/vod.mp4")
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .build();
    MediaLiveSeekableRange seekableRange =
        new MediaLiveSeekableRange.Builder().setStartTime(100_000L).setEndTime(160_000L).build();
    when(mockClient.getMediaStatus()).thenReturn(mockStatus);
    when(mockStatus.getMediaInfo()).thenReturn(vodMediaInfo);
    when(mockStatus.getLiveSeekableRange()).thenReturn(seekableRange);
    when(mockClient.getApproximateLiveSeekableRangeStart()).thenReturn(105_000L);

    assertThat(CastUtils.getLiveSeekableRangeStartMs(mockClient)).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void getLiveSeekableRangeStartMs_whenLoadingItemIsSet_returnsTimeUnset() {
    RemoteMediaClient mockClient = mock(RemoteMediaClient.class);
    MediaStatus mockStatus = mock(MediaStatus.class);
    MediaInfo liveMediaInfo =
        new MediaInfo.Builder("https://example.com/live.m3u8")
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .build();
    MediaLiveSeekableRange seekableRange =
        new MediaLiveSeekableRange.Builder().setStartTime(100_000L).setEndTime(160_000L).build();
    when(mockClient.getMediaStatus()).thenReturn(mockStatus);
    when(mockStatus.getLoadingItemId()).thenReturn(2);
    when(mockStatus.getMediaInfo()).thenReturn(liveMediaInfo);
    when(mockStatus.getLiveSeekableRange()).thenReturn(seekableRange);
    when(mockClient.getApproximateLiveSeekableRangeStart()).thenReturn(105_000L);

    assertThat(CastUtils.getLiveSeekableRangeStartMs(mockClient)).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void getLiveSeekableRangeStartMs_whenMissingRange_returnsTimeUnset() {
    RemoteMediaClient mockClient = mock(RemoteMediaClient.class);
    MediaStatus mockStatus = mock(MediaStatus.class);
    MediaInfo liveMediaInfo =
        new MediaInfo.Builder("https://example.com/live.m3u8")
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .build();
    when(mockClient.getMediaStatus()).thenReturn(mockStatus);
    when(mockStatus.getMediaInfo()).thenReturn(liveMediaInfo);
    when(mockStatus.getLiveSeekableRange()).thenReturn(null);

    assertThat(CastUtils.getLiveSeekableRangeStartMs(mockClient)).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void toWindowPositionMs_whenLiveStreamWithRange_subtractsStartAndClampsToZero() {
    RemoteMediaClient mockClient = mock(RemoteMediaClient.class);
    MediaStatus mockStatus = mock(MediaStatus.class);
    MediaInfo liveMediaInfo =
        new MediaInfo.Builder("https://example.com/live.m3u8")
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .build();
    MediaLiveSeekableRange seekableRange =
        new MediaLiveSeekableRange.Builder().setStartTime(100_000L).setEndTime(160_000L).build();
    when(mockClient.getMediaStatus()).thenReturn(mockStatus);
    when(mockStatus.getMediaInfo()).thenReturn(liveMediaInfo);
    when(mockStatus.getLiveSeekableRange()).thenReturn(seekableRange);
    when(mockClient.getApproximateLiveSeekableRangeStart()).thenReturn(105_000L);

    assertThat(CastUtils.toWindowPositionMs(mockClient, 120_000L)).isEqualTo(15_000L);
    assertThat(CastUtils.toWindowPositionMs(mockClient, 90_000L)).isEqualTo(0L);
  }

  @Test
  public void toWindowPositionMs_whenNoSeekableLiveStream_returnsStreamPositionUnchanged() {
    RemoteMediaClient mockClient = mock(RemoteMediaClient.class);
    when(mockClient.getMediaStatus()).thenReturn(null);

    assertThat(CastUtils.toWindowPositionMs(mockClient, 42_000L)).isEqualTo(42_000L);
  }
}
