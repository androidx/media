/*
 * Copyright 2018 The Android Open Source Project
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

import static android.support.v4.media.session.MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS;
import static androidx.media3.test.session.common.TestUtils.VOLUME_CHANGE_TIMEOUT_MS;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.RatingCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.MediaSessionCompat.QueueItem;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v4.media.session.PlaybackStateCompat.RepeatMode;
import android.support.v4.media.session.PlaybackStateCompat.ShuffleMode;
import androidx.media.AudioManagerCompat;
import androidx.media.VolumeProviderCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Rating;
import androidx.media3.common.StarRating;
import androidx.media3.test.session.common.HandlerThreadTestRule;
import androidx.media3.test.session.common.MainLooperTestRule;
import androidx.media3.test.session.common.MockActivity;
import androidx.media3.test.session.common.PollingCheck;
import androidx.media3.test.session.common.TestUtils;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link MediaSessionCompat.Callback} with {@link MediaController}. */
@RunWith(AndroidJUnit4.class)
@SmallTest
@SuppressWarnings("deprecation") // Tests behavior of deprecated MediaSessionCompat.Callback
public class MediaSessionCompatCallbackWithMediaControllerTest {
  private static final String TAG = "MediaControllerTest";

  private static final long TIMEOUT_MS = 3000L;
  private static final long NO_OP_TIMEOUT_MS = 100L;

  @ClassRule public static MainLooperTestRule mainLooperTestRule = new MainLooperTestRule();

  @Rule public final HandlerThreadTestRule threadTestRule = new HandlerThreadTestRule(TAG);

  @Rule public final RemoteControllerTestRule controllerTestRule = new RemoteControllerTestRule();

  private Context context;
  private MediaSessionCompat session;
  private MediaSessionCallback sessionCallback;
  private AudioManager audioManager;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    Intent sessionActivity = new Intent(context, MockActivity.class);
    // Create this test specific MediaSession to use our own Handler.
    PendingIntent intent =
        PendingIntent.getActivity(context, 0, sessionActivity, PendingIntent.FLAG_IMMUTABLE);

    sessionCallback = new MediaSessionCallback();
    session = new MediaSessionCompat(context, TAG + "Compat");
    session.setCallback(sessionCallback, threadTestRule.getHandler());
    session.setSessionActivity(intent);
    session.setActive(true);

    audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
  }

  @After
  public void cleanUp() {
    if (session != null) {
      session.release();
      session = null;
    }
  }

  private RemoteMediaController createControllerAndWaitConnection() throws Exception {
    SessionToken sessionToken =
        SessionToken.createSessionToken(context, session.getSessionToken()).get();
    return controllerTestRule.createRemoteController(sessionToken);
  }

  @Test
  public void play() throws Exception {
    List<MediaItem> testList =
        MediaTestUtils.createMediaItems(/* size= */ 2, /* buildWithUri= */ true);
    List<QueueItem> testQueue = MediaTestUtils.convertToQueueItemsWithoutBitmap(testList);
    session.setQueue(testQueue);
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.ACTION_PLAY);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.play();
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onPlayCalledCount).isEqualTo(1);
  }

  @Test
  public void pause() throws Exception {
    List<MediaItem> testList =
        MediaTestUtils.createMediaItems(/* size= */ 2, /* buildWithUri= */ true);
    List<QueueItem> testQueue = MediaTestUtils.convertToQueueItemsWithoutBitmap(testList);
    session.setQueue(testQueue);
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.ACTION_PAUSE);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.pause();
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onPauseCalled).isEqualTo(true);
  }

  @Test
  public void prepare() throws Exception {
    List<MediaItem> testList =
        MediaTestUtils.createMediaItems(/* size= */ 2, /* buildWithUri= */ true);
    List<QueueItem> testQueue = MediaTestUtils.convertToQueueItemsWithoutBitmap(testList);
    session.setQueue(testQueue);
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(PlaybackStateCompat.STATE_NONE, PlaybackStateCompat.ACTION_PREPARE);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.prepare();

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onPrepareCalled).isEqualTo(true);
  }

  @Test
  public void stop() throws Exception {
    List<MediaItem> testList =
        MediaTestUtils.createMediaItems(/* size= */ 2, /* buildWithUri= */ true);
    List<QueueItem> testQueue = MediaTestUtils.convertToQueueItemsWithoutBitmap(testList);
    session.setQueue(testQueue);
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(
        PlaybackStateCompat.STATE_NONE,
        PlaybackStateCompat.ACTION_PREPARE | PlaybackStateCompat.ACTION_STOP);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(/* count= */ 2);

    controller.prepare();
    controller.stop();

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onPrepareCalled).isTrue();
    assertThat(sessionCallback.onStopCalled).isTrue();
  }

  @Test
  public void stop_resetsInitializationState() throws Exception {
    List<MediaItem> testList = MediaTestUtils.createMediaItemsWithArtworkData(2);
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(
        PlaybackStateCompat.STATE_PAUSED,
        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
            | PlaybackStateCompat.ACTION_PLAY
            | PlaybackStateCompat.ACTION_STOP);
    RemoteMediaController controller = createControllerAndWaitConnection();
    controller.setMediaItems(testList);
    sessionCallback.reset(2);
    controller.play();
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();

    sessionCallback.reset(1);
    controller.stop();
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    sessionCallback.reset(2);
    controller.prepare();

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onPlayFromMediaIdCalled).isTrue();
    assertThat(sessionCallback.onAddQueueItemAtCalledCount).isEqualTo(1);
  }

  @Test
  public void seekToDefaultPosition() throws Exception {
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.seekToDefaultPosition();
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onSeekToCalled).isTrue();
    assertThat(sessionCallback.seekPosition).isEqualTo(0);
  }

  @Test
  public void seekToDefaultPosition_withMediaItemIndex() throws Exception {
    int testMediaItemIndex = 1;
    List<QueueItem> testQueue = MediaTestUtils.createQueueItems(/* size= */ 3);

    session.setQueue(testQueue);
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    RemoteMediaController controller = createControllerAndWaitConnection();

    sessionCallback.reset(2);

    controller.seekToDefaultPosition(testMediaItemIndex);
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onSkipToQueueItemCalled).isTrue();
    assertThat(sessionCallback.queueItemId)
        .isEqualTo(testQueue.get(testMediaItemIndex).getQueueId());
    assertThat(sessionCallback.onSeekToCalled).isTrue();
    assertThat(sessionCallback.seekPosition).isEqualTo(0);
  }

  @Test
  public void seekToDefaultPosition_withFakeMediaItemIndex_seeksWithPosition() throws Exception {
    List<QueueItem> testQueue = MediaTestUtils.createQueueItems(/* size= */ 3);
    int fakeItemIndex = testQueue.size();
    MediaMetadataCompat testMetadata =
        new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, "media_id")
            .build();

    session.setQueue(testQueue);
    session.setMetadata(testMetadata);
    RemoteMediaController controller = createControllerAndWaitConnection();

    sessionCallback.reset(1);

    controller.seekToDefaultPosition(fakeItemIndex);
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onSkipToQueueItemCalled).isFalse();
    assertThat(sessionCallback.onSeekToCalled).isTrue();
    assertThat(sessionCallback.seekPosition).isEqualTo(0);
  }

  @Test
  public void seekTo() throws Exception {
    long testPositionMs = 12125L;

    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.seekTo(testPositionMs);
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onSeekToCalled).isTrue();
    assertThat(sessionCallback.seekPosition).isEqualTo(testPositionMs);
  }

  @Test
  public void seekTo_withMediaItemIndex() throws Exception {
    int testMediaItemIndex = 1;
    long testPositionMs = 12L;

    List<QueueItem> testQueue = MediaTestUtils.createQueueItems(/* size= */ 3);

    session.setQueue(testQueue);
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    RemoteMediaController controller = createControllerAndWaitConnection();

    sessionCallback.reset(2);

    controller.seekTo(testMediaItemIndex, testPositionMs);
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onSkipToQueueItemCalled).isTrue();
    assertThat(sessionCallback.queueItemId)
        .isEqualTo(testQueue.get(testMediaItemIndex).getQueueId());
    assertThat(sessionCallback.onSeekToCalled).isTrue();
    assertThat(sessionCallback.seekPosition).isEqualTo(testPositionMs);
  }

  @Test
  public void seekBack_notifiesOnRewind() throws Exception {
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.seekBack();

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onRewindCalled).isTrue();
  }

  @Test
  public void seekForward_notifiesOnFastForward() throws Exception {
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.seekForward();

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onFastForwardCalled).isTrue();
  }

  @Test
  public void setPlaybackSpeed_notifiesOnSetPlaybackSpeed() throws Exception {
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    float testSpeed = 2.0f;
    controller.setPlaybackSpeed(testSpeed);
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onSetPlaybackSpeedCalled).isTrue();
    assertThat(sessionCallback.speed).isEqualTo(testSpeed);
  }

  @Test
  public void setPlaybackParameters_notifiesOnSetPlaybackSpeed() throws Exception {
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    PlaybackParameters playbackParameters = new PlaybackParameters(/* speed= */ 1.2f);
    controller.setPlaybackParameters(playbackParameters);
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onSetPlaybackSpeedCalled).isTrue();
    assertThat(sessionCallback.speed).isEqualTo(playbackParameters.speed);
  }

  @Test
  public void setPlaybackParameters_withDefault_notifiesOnSetPlaybackSpeedWithDefault()
      throws Exception {
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.setPlaybackParameters(PlaybackParameters.DEFAULT);
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onSetPlaybackSpeedCalled).isTrue();
    assertThat(sessionCallback.speed).isEqualTo(PlaybackParameters.DEFAULT.speed);
  }

  @Test
  public void addMediaItems() throws Exception {
    int size = 2;
    List<MediaItem> testList = MediaTestUtils.createMediaItemsWithArtworkData(size);
    List<QueueItem> testQueue = MediaTestUtils.convertToQueueItemsWithoutBitmap(testList);

    session.setQueue(testQueue);
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(PlaybackStateCompat.STATE_PLAYING, /* actions= */ 0);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(size);

    int testIndex = 1;
    controller.addMediaItems(testIndex, testList);

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onAddQueueItemAtCalledCount).isEqualTo(size);
    for (int i = 0; i < size; i++) {
      assertThat(sessionCallback.queueIndices.get(i)).isEqualTo(testIndex + i);
      assertThat(sessionCallback.queueDescriptionListForAdd.get(i).getMediaId())
          .isEqualTo(testList.get(i).mediaId);
      assertThat(sessionCallback.queueDescriptionListForAdd.get(i).getIconBitmap()).isNotNull();
    }
  }

  @Test
  public void removeMediaItems() throws Exception {
    List<MediaItem> testList =
        MediaTestUtils.createMediaItems(/* size= */ 4, /* buildWithUri= */ true);
    int fromIndex = 1;
    int toIndex = 3;
    int count = toIndex - fromIndex;

    session.setQueue(MediaTestUtils.convertToQueueItemsWithoutBitmap(testList));
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(PlaybackStateCompat.STATE_BUFFERING, /* actions= */ 0);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(count);

    controller.removeMediaItems(fromIndex, toIndex);

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onRemoveQueueItemCalledCount).isEqualTo(count);
    for (int i = 0; i < count; i++) {
      assertThat(sessionCallback.queueDescriptionListForRemove.get(i).getMediaId())
          .isEqualTo(testList.get(fromIndex + i).mediaId);
    }
  }

  @Test
  public void seekToPreviousMediaItem() throws Exception {
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.seekToPreviousMediaItem();
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onSkipToPreviousCalled).isTrue();
  }

  @Test
  public void seekToNextMediaItem() throws Exception {
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.seekToNextMediaItem();
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onSkipToNextCalled).isTrue();
  }

  @Test
  public void setMediaItems_nonEmptyList_startFromFirstMediaItem() throws Exception {
    int size = 3;
    List<MediaItem> testList = MediaTestUtils.createMediaItemsWithArtworkData(size);

    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(
        PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(size);

    controller.setMediaItems(testList);

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onPlayFromMediaIdCalled).isTrue();
    assertThat(sessionCallback.mediaId).isEqualTo(testList.get(0).mediaId);
    for (int i = 0; i < size - 1; i++) {
      assertThat(sessionCallback.queueIndices.get(i)).isEqualTo(i);
      assertThat(sessionCallback.queueDescriptionListForAdd.get(i).getMediaId())
          .isEqualTo(testList.get(i + 1).mediaId);
      assertThat(sessionCallback.queueDescriptionListForAdd.get(i).getIconBitmap()).isNotNull();
    }
  }

  @Test
  public void setMediaItems_nonEmptyList_startFromNonFirstMediaItem() throws Exception {
    int size = 5;
    List<MediaItem> testList = MediaTestUtils.createMediaItemsWithArtworkData(size);

    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(
        PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(size);
    int testStartIndex = 2;

    controller.setMediaItems(testList, testStartIndex, /* startPositionMs= */ C.TIME_UNSET);

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onPlayFromMediaIdCalled).isTrue();
    assertThat(sessionCallback.mediaId).isEqualTo(testList.get(testStartIndex).mediaId);
    for (int i = 0; i < size - 1; i++) {
      assertThat(sessionCallback.queueIndices.get(i)).isEqualTo(i);
      int adjustedIndex = (i < testStartIndex) ? i : i + 1;
      assertThat(sessionCallback.queueDescriptionListForAdd.get(i).getMediaId())
          .isEqualTo(testList.get(adjustedIndex).mediaId);
      assertThat(sessionCallback.queueDescriptionListForAdd.get(i).getIconBitmap()).isNotNull();
    }
  }

  @Test
  public void setMediaItems_emptyList() throws Exception {
    int size = 3;
    List<MediaItem> testList = MediaTestUtils.createMediaItems(size, /* buildWithUri= */ true);
    List<QueueItem> testQueue = MediaTestUtils.convertToQueueItemsWithoutBitmap(testList);

    session.setQueue(testQueue);
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(
        PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(size);

    controller.setMediaItems(ImmutableList.of());

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    for (int i = 0; i < size; i++) {
      assertThat(sessionCallback.queueDescriptionListForRemove.get(i).getMediaId())
          .isEqualTo(testList.get(i).mediaId);
    }
  }

  @Test
  public void setShuffleMode() throws Exception {
    session.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_NONE);
    setPlaybackStateAndActions(
        PlaybackStateCompat.STATE_NONE, PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.setShuffleModeEnabled(true);
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onSetShuffleModeCalled).isTrue();
    assertThat(sessionCallback.shuffleMode).isEqualTo(PlaybackStateCompat.SHUFFLE_MODE_ALL);
  }

  @Test
  public void setRepeatMode() throws Exception {
    int testRepeatMode = Player.REPEAT_MODE_ALL;

    session.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_NONE);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.setRepeatMode(testRepeatMode);
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onSetRepeatModeCalled).isTrue();
    assertThat(sessionCallback.repeatMode).isEqualTo(testRepeatMode);
  }

  @Test
  public void setDeviceVolume_forRemotePlayback_callsSetVolumeTo() throws Exception {
    int maxVolume = 100;
    int currentVolume = 23;
    int volumeControlType = VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE;
    TestVolumeProvider volumeProvider =
        new TestVolumeProvider(volumeControlType, maxVolume, currentVolume);
    session.setPlaybackToRemote(volumeProvider);
    RemoteMediaController controller = createControllerAndWaitConnection();

    int targetVolume = 50;
    controller.setDeviceVolume(targetVolume);
    assertThat(volumeProvider.latch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(volumeProvider.setVolumeToCalled).isTrue();
    assertThat(volumeProvider.volume).isEqualTo(targetVolume);
  }

  @Test
  public void increaseDeviceVolume_forRemotePlayback_callsAdjustVolumeWithDirectionRaise()
      throws Exception {
    int maxVolume = 100;
    int currentVolume = 23;
    int volumeControlType = VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE;
    TestVolumeProvider volumeProvider =
        new TestVolumeProvider(volumeControlType, maxVolume, currentVolume);
    session.setPlaybackToRemote(volumeProvider);
    RemoteMediaController controller = createControllerAndWaitConnection();

    controller.increaseDeviceVolume();
    assertThat(volumeProvider.latch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(volumeProvider.adjustVolumeCalled).isTrue();
    assertThat(volumeProvider.direction).isEqualTo(AudioManager.ADJUST_RAISE);
  }

  @Test
  public void decreaseDeviceVolume_forRemotePlayback_callsAdjustVolumeWithDirectionLower()
      throws Exception {
    int maxVolume = 100;
    int currentVolume = 23;
    int volumeControlType = VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE;
    int volumeFlags = C.VOLUME_FLAG_SHOW_UI;
    TestVolumeProvider volumeProvider =
        new TestVolumeProvider(volumeControlType, maxVolume, currentVolume);
    session.setPlaybackToRemote(volumeProvider);
    RemoteMediaController controller = createControllerAndWaitConnection();

    controller.decreaseDeviceVolume(volumeFlags);
    assertThat(volumeProvider.latch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(volumeProvider.adjustVolumeCalled).isTrue();
    assertThat(volumeProvider.direction).isEqualTo(AudioManager.ADJUST_LOWER);
  }

  @Test
  public void setDeviceVolume_forLocalPlayback_setsStreamVolume() throws Exception {
    if (audioManager.isVolumeFixed()) {
      // This test is not eligible for this device.
      return;
    }

    // STREAM_ALARM in order not to consider 'Do Not Disturb' or 'Volume limit'.
    int stream = AudioManager.STREAM_ALARM;
    int maxVolume = AudioManagerCompat.getStreamMaxVolume(audioManager, stream);
    int minVolume = AudioManagerCompat.getStreamMinVolume(audioManager, stream);
    if (maxVolume <= minVolume) {
      return;
    }
    int volumeFlags = C.VOLUME_FLAG_SHOW_UI | C.VOLUME_FLAG_VIBRATE;

    session.setPlaybackToLocal(stream);
    RemoteMediaController controller = createControllerAndWaitConnection();
    int originalVolume = audioManager.getStreamVolume(stream);
    int targetVolume = originalVolume == minVolume ? originalVolume + 1 : originalVolume - 1;

    controller.setDeviceVolume(targetVolume, volumeFlags);
    PollingCheck.waitFor(
        VOLUME_CHANGE_TIMEOUT_MS, () -> targetVolume == audioManager.getStreamVolume(stream));

    // Set back to original volume.
    audioManager.setStreamVolume(stream, originalVolume, /* flags= */ 0);
  }

  @Test
  public void increaseDeviceVolume_forLocalPlayback_increasesStreamVolume() throws Exception {
    if (audioManager.isVolumeFixed()) {
      // This test is not eligible for this device.
      return;
    }

    // STREAM_ALARM in order not to consider 'Do Not Disturb' or 'Volume limit'.
    int stream = AudioManager.STREAM_ALARM;
    int maxVolume = AudioManagerCompat.getStreamMaxVolume(audioManager, stream);
    int minVolume = AudioManagerCompat.getStreamMinVolume(audioManager, stream);
    if (maxVolume <= minVolume) {
      return;
    }
    int volumeFlags = C.VOLUME_FLAG_SHOW_UI | C.VOLUME_FLAG_VIBRATE;

    session.setPlaybackToLocal(stream);
    RemoteMediaController controller = createControllerAndWaitConnection();
    int originalVolume = audioManager.getStreamVolume(stream);
    audioManager.setStreamVolume(stream, minVolume, /* flags= */ 0);
    int targetVolume = minVolume + 1;

    controller.increaseDeviceVolume(volumeFlags);
    PollingCheck.waitFor(
        VOLUME_CHANGE_TIMEOUT_MS, () -> targetVolume == audioManager.getStreamVolume(stream));

    // Set back to original volume.
    audioManager.setStreamVolume(stream, originalVolume, /* flags= */ 0);
  }

  @Test
  public void decreaseDeviceVolume_forLocalPlayback_decreasesStreamVolume() throws Exception {
    if (audioManager.isVolumeFixed()) {
      // This test is not eligible for this device.
      return;
    }

    // STREAM_ALARM in order not to consider 'Do Not Disturb' or 'Volume limit'.
    int stream = AudioManager.STREAM_ALARM;
    int maxVolume = AudioManagerCompat.getStreamMaxVolume(audioManager, stream);
    int minVolume = AudioManagerCompat.getStreamMinVolume(audioManager, stream);
    if (maxVolume <= minVolume) {
      return;
    }
    int volumeFlags = C.VOLUME_FLAG_SHOW_UI | C.VOLUME_FLAG_VIBRATE;

    session.setPlaybackToLocal(stream);
    RemoteMediaController controller = createControllerAndWaitConnection();
    int originalVolume = audioManager.getStreamVolume(stream);
    audioManager.setStreamVolume(stream, maxVolume, /* flags= */ 0);
    int targetVolume = maxVolume - 1;

    controller.decreaseDeviceVolume(volumeFlags);
    PollingCheck.waitFor(
        VOLUME_CHANGE_TIMEOUT_MS, () -> targetVolume == audioManager.getStreamVolume(stream));

    // Set back to original volume.
    audioManager.setStreamVolume(stream, originalVolume, /* flags= */ 0);
  }

  @Test
  public void setDeviceMuted_mute_forLocalPlayback_mutesStreamVolume() throws Exception {
    if (audioManager.isVolumeFixed()) {
      // This test is not eligible for this device.
      return;
    }

    int stream = AudioManager.STREAM_MUSIC;
    int maxVolume = AudioManagerCompat.getStreamMaxVolume(audioManager, stream);
    int minVolume = AudioManagerCompat.getStreamMinVolume(audioManager, stream);
    if (maxVolume <= minVolume) {
      return;
    }
    int volumeFlags = C.VOLUME_FLAG_VIBRATE;

    session.setPlaybackToLocal(stream);
    RemoteMediaController controller = createControllerAndWaitConnection();
    boolean wasMuted = audioManager.isStreamMute(stream);
    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, /* flags= */ 0);

    controller.setDeviceMuted(true, volumeFlags);
    PollingCheck.waitFor(VOLUME_CHANGE_TIMEOUT_MS, () -> audioManager.isStreamMute(stream));

    // Set back to original mute state.
    audioManager.adjustStreamVolume(
        stream, wasMuted ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, /* flags= */ 0);
  }

  @Test
  public void setDeviceMuted_unmute_forLocalPlayback_unmutesStreamVolume() throws Exception {
    if (audioManager.isVolumeFixed()) {
      // This test is not eligible for this device.
      return;
    }

    int stream = AudioManager.STREAM_MUSIC;
    int maxVolume = AudioManagerCompat.getStreamMaxVolume(audioManager, stream);
    int minVolume = AudioManagerCompat.getStreamMinVolume(audioManager, stream);
    if (maxVolume <= minVolume) {
      return;
    }
    session.setPlaybackToLocal(stream);
    RemoteMediaController controller = createControllerAndWaitConnection();
    boolean wasMuted = audioManager.isStreamMute(stream);
    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, /* flags= */ 0);

    controller.setDeviceMuted(false);
    PollingCheck.waitFor(VOLUME_CHANGE_TIMEOUT_MS, () -> !audioManager.isStreamMute(stream));

    // Set back to original mute state.
    audioManager.adjustStreamVolume(
        stream, wasMuted ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, /* flags= */ 0);
  }

  @Test
  public void sendCustomCommand_withExtrasAsMethodParameter_triggersOnCustomAction()
      throws Exception {
    String command = "test_custom_command";
    Bundle testArgs = new Bundle();
    testArgs.putString("args", "test_args");
    SessionCommand testCommand = new SessionCommand(command, /* extras= */ Bundle.EMPTY);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    SessionResult result = controller.sendCustomCommand(testCommand, testArgs);

    assertThat(result.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS);
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onCustomActionCalled).isTrue();
    assertThat(sessionCallback.action).isEqualTo(command);
    assertThat(TestUtils.equals(testArgs, sessionCallback.extras)).isTrue();
  }

  @Test
  public void sendCustomCommand_withExtrasInSessionCommand_triggersOnCustomAction()
      throws Exception {
    String command = "test_custom_command";
    Bundle testArgs = new Bundle();
    testArgs.putString("args", "test_args");
    SessionCommand testCommand = new SessionCommand(command, testArgs);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    SessionResult result = controller.sendCustomCommand(testCommand, /* args= */ Bundle.EMPTY);

    assertThat(result.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS);
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onCustomActionCalled).isTrue();
    assertThat(sessionCallback.action).isEqualTo(command);
    assertThat(TestUtils.equals(testArgs, sessionCallback.extras)).isTrue();
  }

  @Test
  public void
      sendCustomCommand_withExtrasInSessionCommandAndMethodParameter_triggersOnCustomAction()
          throws Exception {
    String command = "test_custom_command";
    Bundle testArgsCommand = new Bundle();
    testArgsCommand.putString("args1", "test_command");
    testArgsCommand.putString("args2", "test_command");
    Bundle testArgsParameter = new Bundle();
    testArgsParameter.putString("args1", "test_parameter");
    testArgsParameter.putString("args3", "test_parameter");
    Bundle expectedCombinedParameters = new Bundle();
    expectedCombinedParameters.putString("args1", "test_parameter");
    expectedCombinedParameters.putString("args2", "test_command");
    expectedCombinedParameters.putString("args3", "test_parameter");
    SessionCommand testCommand = new SessionCommand(command, testArgsCommand);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    SessionResult result = controller.sendCustomCommand(testCommand, testArgsParameter);

    assertThat(result.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS);
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onCustomActionCalled).isTrue();
    assertThat(sessionCallback.action).isEqualTo(command);
    assertThat(TestUtils.equals(expectedCombinedParameters, sessionCallback.extras)).isTrue();
  }

  @Test
  public void setRatingWithMediaId() throws Exception {
    float ratingValue = 3.5f;
    Rating rating = new StarRating(5, ratingValue);
    String mediaId = "media_id";
    MediaMetadataCompat metadata =
        new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaId)
            .build();
    session.setMetadata(metadata);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.setRating(mediaId, rating);
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onSetRatingCalled).isTrue();
    assertThat(sessionCallback.rating.getRatingStyle()).isEqualTo(RatingCompat.RATING_5_STARS);
    assertThat(sessionCallback.rating.getStarRating()).isEqualTo(3.5f);
  }

  @Test
  public void setRatingWithoutMediaId() throws Exception {
    float ratingValue = 3.5f;
    Rating rating = new StarRating(5, ratingValue);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.setRating(rating);
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onSetRatingCalled).isTrue();
    assertThat(sessionCallback.rating.getRatingStyle()).isEqualTo(RatingCompat.RATING_5_STARS);
    assertThat(sessionCallback.rating.getStarRating()).isEqualTo(3.5f);
  }

  @Test
  public void seekToNext_callsOnSkipToNext() throws Exception {
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.seekToNext();

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onSkipToNextCalled).isTrue();
  }

  @Test
  public void seekToPrevious_callsOnSkipToPrevious() throws Exception {
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.seekToPrevious();

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onSkipToPreviousCalled).isTrue();
  }

  @Test
  public void setMediaItems_paused_doesNotInitializeUntilPlay() throws Exception {
    List<MediaItem> testList = MediaTestUtils.createMediaItemsWithArtworkData(3);
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(
        PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.setMediaItems(testList);

    assertThat(sessionCallback.await(NO_OP_TIMEOUT_MS)).isFalse();
    assertThat(sessionCallback.onPrepareFromMediaIdCalled).isFalse();
    assertThat(sessionCallback.onPlayFromMediaIdCalled).isFalse();

    controller.play();

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onPlayFromMediaIdCalled).isTrue();
  }

  @Test
  public void setMediaItems_thenAddBeforeInitialize_doesNotInitializeUntilPlay() throws Exception {
    List<MediaItem> testList = MediaTestUtils.createMediaItemsWithArtworkData(3);
    List<MediaItem> addedList = MediaTestUtils.createMediaItemsWithArtworkData(1);
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(
        PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(4);

    controller.setMediaItems(testList);
    assertThat(sessionCallback.await(NO_OP_TIMEOUT_MS)).isFalse();
    controller.addMediaItems(addedList);
    assertThat(sessionCallback.await(NO_OP_TIMEOUT_MS)).isFalse();
    assertThat(sessionCallback.onPrepareFromMediaIdCalled).isFalse();
    assertThat(sessionCallback.onPlayFromMediaIdCalled).isFalse();
    assertThat(sessionCallback.onAddQueueItemAtCalledCount).isEqualTo(0);

    controller.play();

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onPlayFromMediaIdCalled).isTrue();
    assertThat(sessionCallback.onAddQueueItemAtCalledCount).isEqualTo(3);
  }

  @Test
  public void setMediaItems_thenRemoveBeforeInitialize_doesNotInitializeUntilPlay()
      throws Exception {
    List<MediaItem> testList = MediaTestUtils.createMediaItemsWithArtworkData(3);
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(
        PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.setMediaItems(testList);
    assertThat(sessionCallback.await(NO_OP_TIMEOUT_MS)).isFalse();
    controller.removeMediaItem(2);
    assertThat(sessionCallback.await(NO_OP_TIMEOUT_MS)).isFalse();
    assertThat(sessionCallback.onPrepareFromMediaIdCalled).isFalse();
    assertThat(sessionCallback.onPlayFromMediaIdCalled).isFalse();
    assertThat(sessionCallback.onRemoveQueueItemCalledCount).isEqualTo(0);

    controller.play();

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onPlayFromMediaIdCalled).isTrue();
    assertThat(sessionCallback.onRemoveQueueItemCalledCount).isEqualTo(0);
  }

  @Test
  public void setMediaItems_pausedAndSupportPrepare_initializesImmediately() throws Exception {
    List<MediaItem> testList = MediaTestUtils.createMediaItemsWithArtworkData(3);
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(
        PlaybackStateCompat.STATE_PAUSED,
        PlaybackStateCompat.ACTION_PREPARE
            | PlaybackStateCompat.ACTION_PLAY
            | PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID
            | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID);
    RemoteMediaController controller = createControllerAndWaitConnection();
    waitForCondition(() -> controller.hasQueueCommandsSupport(), TIMEOUT_MS);
    sessionCallback.reset(1);

    controller.setMediaItems(testList);

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onPrepareFromMediaIdCalled).isTrue();
  }

  @Test
  public void setMediaItems_thenPrepare_initializes() throws Exception {
    List<MediaItem> testList = MediaTestUtils.createMediaItems(3, /* buildWithUri= */ false);
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(
        PlaybackStateCompat.STATE_NONE, PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.setMediaItems(testList);

    assertThat(sessionCallback.await(NO_OP_TIMEOUT_MS)).isFalse();

    controller.prepare();

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onPrepareFromMediaIdCalled).isTrue();
  }

  @Test
  public void setMediaItems_withMediaUri_initializesWithPlayFromUri() throws Exception {
    Uri testUri = Uri.parse("http://test.com");
    MediaItem testItem =
        new MediaItem.Builder()
            .setMediaId("id")
            .setRequestMetadata(
                new MediaItem.RequestMetadata.Builder().setMediaUri(testUri).build())
            .build();
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(
        PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.ACTION_PLAY_FROM_URI);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.setMediaItem(testItem);
    controller.play();

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onPlayFromUriCalled).isTrue();
    assertThat(sessionCallback.uri).isEqualTo(testUri);
  }

  @Test
  public void setMediaItems_withSearchQuery_initializesWithPlayFromSearch() throws Exception {
    String testQuery = "test query";
    MediaItem testItem =
        new MediaItem.Builder()
            .setMediaId("id")
            .setRequestMetadata(
                new MediaItem.RequestMetadata.Builder().setSearchQuery(testQuery).build())
            .build();
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(
        PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.setMediaItem(testItem);
    controller.play();

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onPlayFromSearchCalled).isTrue();
    assertThat(sessionCallback.query).isEqualTo(testQuery);
  }

  @Test
  public void setMediaItems_withStartPosition_seeksAfterPlayFromMediaId() throws Exception {
    long startPositionMs = 1000L;
    List<MediaItem> testList = MediaTestUtils.createMediaItemsWithArtworkData(1);
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(
        PlaybackStateCompat.STATE_PAUSED,
        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID | PlaybackStateCompat.ACTION_SEEK_TO);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(2);

    controller.setMediaItems(testList, /* startIndex= */ 0, startPositionMs);
    controller.play();

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onPlayFromMediaIdCalled).isTrue();
    assertThat(sessionCallback.onSeekToCalled).isTrue();
    assertThat(sessionCallback.seekPosition).isEqualTo(startPositionMs);
  }

  @Test
  public void setMediaItems_replacementBeforeInitialize_initializesWithSecondList()
      throws Exception {
    List<MediaItem> testList1 = MediaTestUtils.createMediaItems(/* buildWithUri= */ false, "id1");
    List<MediaItem> testList2 =
        MediaTestUtils.createMediaItems(/* buildWithUri= */ false, "id2", "id3");
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(
        PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.setMediaItems(testList1);
    assertThat(sessionCallback.await(NO_OP_TIMEOUT_MS)).isFalse();
    controller.setMediaItems(testList2);
    assertThat(sessionCallback.await(NO_OP_TIMEOUT_MS)).isFalse();
    sessionCallback.reset(2);
    controller.play();

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.mediaId).isEqualTo(testList2.get(0).mediaId);
    assertThat(sessionCallback.onAddQueueItemAtCalledCount).isEqualTo(1);
    assertThat(sessionCallback.queueDescriptionListForAdd.get(0).getMediaId())
        .isEqualTo(testList2.get(1).mediaId);
  }

  @Test
  public void setMediaItems_withoutChangeMediaItemsCommand_initializesOnlyFirstItem()
      throws Exception {
    List<MediaItem> testList = MediaTestUtils.createMediaItemsWithArtworkData(2);
    session.setFlags(0); // No FLAG_HANDLES_QUEUE_COMMANDS
    setPlaybackStateAndActions(
        PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(1);

    controller.setMediaItems(testList);
    controller.play();

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onPlayFromMediaIdCalled).isTrue();
    assertThat(sessionCallback.onAddQueueItemAtCalledCount).isEqualTo(0);
  }

  @Test
  public void addMediaItems_afterInitialize_addsImmediately() throws Exception {
    List<MediaItem> testList = MediaTestUtils.createMediaItemsWithArtworkData(2);
    List<MediaItem> addedList = MediaTestUtils.createMediaItemsWithArtworkData(1);
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(
        PlaybackStateCompat.STATE_PAUSED, PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID);
    RemoteMediaController controller = createControllerAndWaitConnection();
    controller.setMediaItems(testList);
    sessionCallback.reset(2);
    controller.play();
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    session.setQueue(MediaTestUtils.convertToQueueItemsWithoutBitmap(testList));
    // Wait until the controller accepts the new platform state for further operations.
    Thread.sleep(2 * MediaController.DEFAULT_PLATFORM_CALLBACK_AGGREGATION_TIMEOUT_MS);

    sessionCallback.reset(1);
    controller.addMediaItems(addedList);

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onAddQueueItemAtCalledCount).isEqualTo(1);
  }

  @Test
  public void removeMediaItem_afterInitialize_removesImmediately() throws Exception {
    List<MediaItem> testList = MediaTestUtils.createMediaItemsWithArtworkData(2);
    session.setFlags(FLAG_HANDLES_QUEUE_COMMANDS);
    setPlaybackStateAndActions(
        PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID);
    RemoteMediaController controller = createControllerAndWaitConnection();
    sessionCallback.reset(2);
    controller.setMediaItems(testList);
    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    session.setQueue(MediaTestUtils.convertToQueueItemsWithoutBitmap(testList));
    // Wait until the controller accepts the new platform state for further operations.
    Thread.sleep(2 * MediaController.DEFAULT_PLATFORM_CALLBACK_AGGREGATION_TIMEOUT_MS);

    sessionCallback.reset(1);
    controller.removeMediaItem(1);

    assertThat(sessionCallback.await(TIMEOUT_MS)).isTrue();
    assertThat(sessionCallback.onRemoveQueueItemCalledCount).isEqualTo(1);
  }

  private void setPlaybackStateAndActions(int state, long actions) {
    PlaybackStateCompat playbackState =
        new PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, /* position= */ 0, /* playbackSpeed= */ 1.0f)
            .build();
    session.setPlaybackState(playbackState);
  }

  private static void waitForCondition(Callable<Boolean> condition, long timeoutMs)
      throws Exception {
    long startTime = System.currentTimeMillis();
    while (!condition.call()) {
      if (System.currentTimeMillis() - startTime > timeoutMs) {
        throw new TimeoutException("Condition not met within " + timeoutMs + " ms");
      }
      Thread.sleep(100);
    }
  }

  private static class TestVolumeProvider extends VolumeProviderCompat {
    CountDownLatch latch = new CountDownLatch(1);
    boolean setVolumeToCalled;
    boolean adjustVolumeCalled;
    int volume;
    int direction;

    TestVolumeProvider(int controlType, int maxVolume, int currentVolume) {
      super(controlType, maxVolume, currentVolume);
    }

    @Override
    public void onSetVolumeTo(int volume) {
      setVolumeToCalled = true;
      this.volume = volume;
      latch.countDown();
    }

    @Override
    public void onAdjustVolume(int direction) {
      adjustVolumeCalled = true;
      this.direction = direction;
      latch.countDown();
    }
  }

  private static class MediaSessionCallback extends MediaSessionCompat.Callback {
    private CountDownLatch latch = new CountDownLatch(1);
    private long seekPosition;
    private float speed;
    private long queueItemId;
    private RatingCompat rating;
    private String mediaId;
    private String query;
    private Uri uri;
    private String action;
    private Bundle extras;
    @RepeatMode private int repeatMode;
    @ShuffleMode private int shuffleMode;
    private final List<Integer> queueIndices = new ArrayList<>();
    private final List<MediaDescriptionCompat> queueDescriptionListForAdd = new ArrayList<>();
    private final List<MediaDescriptionCompat> queueDescriptionListForRemove = new ArrayList<>();

    private int onPlayCalledCount;
    private boolean onPauseCalled;
    private boolean onStopCalled;
    private boolean onSkipToPreviousCalled;
    private boolean onSkipToNextCalled;
    private boolean onSeekToCalled;
    private boolean onFastForwardCalled;
    private boolean onRewindCalled;
    private boolean onSetPlaybackSpeedCalled;
    private boolean onSkipToQueueItemCalled;
    private boolean onSetRatingCalled;
    private boolean onPlayFromMediaIdCalled;
    private boolean onPlayFromSearchCalled;
    private boolean onPlayFromUriCalled;
    private boolean onCustomActionCalled;
    private boolean onPrepareCalled;
    private boolean onPrepareFromMediaIdCalled;
    private boolean onSetRepeatModeCalled;
    private boolean onSetShuffleModeCalled;
    private int onAddQueueItemAtCalledCount;
    private int onRemoveQueueItemCalledCount;

    private void reset(int count) {
      latch = new CountDownLatch(count);
      seekPosition = -1;
      speed = -1.0f;
      queueItemId = -1;
      rating = null;
      mediaId = null;
      query = null;
      uri = null;
      action = null;
      extras = null;
      repeatMode = PlaybackStateCompat.REPEAT_MODE_NONE;
      shuffleMode = PlaybackStateCompat.SHUFFLE_MODE_NONE;
      queueIndices.clear();
      queueDescriptionListForAdd.clear();
      queueDescriptionListForRemove.clear();

      onPlayCalledCount = 0;
      onPauseCalled = false;
      onStopCalled = false;
      onSkipToPreviousCalled = false;
      onSkipToNextCalled = false;
      onSkipToQueueItemCalled = false;
      onSeekToCalled = false;
      onFastForwardCalled = false;
      onRewindCalled = false;
      onSetPlaybackSpeedCalled = false;
      onSetRatingCalled = false;
      onPlayFromMediaIdCalled = false;
      onPlayFromSearchCalled = false;
      onPlayFromUriCalled = false;
      onCustomActionCalled = false;
      onPrepareCalled = false;
      onPrepareFromMediaIdCalled = false;
      onSetRepeatModeCalled = false;
      onSetShuffleModeCalled = false;
      onAddQueueItemAtCalledCount = 0;
      onRemoveQueueItemCalledCount = 0;
    }

    private boolean await(long timeoutMs) {
      try {
        return latch.await(timeoutMs, MILLISECONDS);
      } catch (InterruptedException e) {
        return false;
      }
    }

    @Override
    public void onPlay() {
      onPlayCalledCount++;
      latch.countDown();
    }

    @Override
    public void onPause() {
      onPauseCalled = true;
      latch.countDown();
    }

    @Override
    public void onStop() {
      onStopCalled = true;
      latch.countDown();
    }

    @Override
    public void onSkipToPrevious() {
      onSkipToPreviousCalled = true;
      latch.countDown();
    }

    @Override
    public void onSkipToNext() {
      onSkipToNextCalled = true;
      latch.countDown();
    }

    @Override
    public void onSeekTo(long pos) {
      onSeekToCalled = true;
      seekPosition = pos;
      latch.countDown();
    }

    @Override
    public void onFastForward() {
      onFastForwardCalled = true;
      latch.countDown();
    }

    @Override
    public void onRewind() {
      onRewindCalled = true;
      latch.countDown();
    }

    @Override
    public void onSetPlaybackSpeed(float speed) {
      onSetPlaybackSpeedCalled = true;
      this.speed = speed;
      latch.countDown();
    }

    @Override
    public void onSetRating(RatingCompat rating) {
      onSetRatingCalled = true;
      this.rating = rating;
      latch.countDown();
    }

    @Override
    public void onPlayFromMediaId(String mediaId, Bundle extras) {
      onPlayFromMediaIdCalled = true;
      this.mediaId = mediaId;
      this.extras = extras;
      latch.countDown();
    }

    @Override
    public void onPlayFromSearch(String query, Bundle extras) {
      onPlayFromSearchCalled = true;
      this.query = query;
      this.extras = extras;
      latch.countDown();
    }

    @Override
    public void onPlayFromUri(Uri uri, Bundle extras) {
      onPlayFromUriCalled = true;
      this.uri = uri;
      this.extras = extras;
      latch.countDown();
    }

    @Override
    public void onCustomAction(String action, Bundle extras) {
      onCustomActionCalled = true;
      this.action = action;
      this.extras = extras;
      latch.countDown();
    }

    @Override
    public void onSkipToQueueItem(long id) {
      onSkipToQueueItemCalled = true;
      queueItemId = id;
      latch.countDown();
    }

    @Override
    public void onPrepare() {
      onPrepareCalled = true;
      latch.countDown();
    }

    @Override
    public void onPrepareFromMediaId(String mediaId, Bundle extras) {
      onPrepareFromMediaIdCalled = true;
      this.mediaId = mediaId;
      this.extras = extras;
      latch.countDown();
    }

    @Override
    public void onPrepareFromSearch(String query, Bundle extras) {
      this.query = query;
      this.extras = extras;
      latch.countDown();
    }

    @Override
    public void onPrepareFromUri(Uri uri, Bundle extras) {
      this.uri = uri;
      this.extras = extras;
      latch.countDown();
    }

    @Override
    public void onSetRepeatMode(@RepeatMode int repeatMode) {
      onSetRepeatModeCalled = true;
      this.repeatMode = repeatMode;
      latch.countDown();
    }

    @Override
    public void onAddQueueItem(MediaDescriptionCompat description) {
      queueDescriptionListForAdd.add(description);
      latch.countDown();
    }

    @Override
    public void onAddQueueItem(MediaDescriptionCompat description, int index) {
      onAddQueueItemAtCalledCount++;
      queueIndices.add(index);
      queueDescriptionListForAdd.add(description);
      latch.countDown();
    }

    @Override
    public void onRemoveQueueItem(MediaDescriptionCompat description) {
      onRemoveQueueItemCalledCount++;
      queueDescriptionListForRemove.add(description);
      latch.countDown();
    }

    @Override
    public void onSetCaptioningEnabled(boolean enabled) {
      latch.countDown();
    }

    @Override
    public void onSetShuffleMode(@ShuffleMode int shuffleMode) {
      onSetShuffleModeCalled = true;
      this.shuffleMode = shuffleMode;
      latch.countDown();
    }
  }
}
