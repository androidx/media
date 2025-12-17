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

import static androidx.media3.test.session.common.CommonConstants.SUPPORT_APP_PACKAGE_NAME;
import static androidx.media3.test.session.common.TestUtils.TIMEOUT_MS;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.HeartRating;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Rating;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.Util;
import androidx.media3.session.MediaSession.ControllerInfo;
import androidx.media3.test.session.common.HandlerThreadTestRule;
import androidx.media3.test.session.common.MainLooperTestRule;
import androidx.media3.test.session.common.TestUtils;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for the underlying {@link Player} of {@link MediaSession}. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class MediaSessionPlayerTest {

  @ClassRule public static MainLooperTestRule mainLooperTestRule = new MainLooperTestRule();

  @Rule
  public final HandlerThreadTestRule threadTestRule =
      new HandlerThreadTestRule("MediaSessionPlayerTest");

  @Rule
  public final RemoteControllerTestRule remoteControllerTestRule = new RemoteControllerTestRule();

  private final AtomicReference<Rating> onSetRatingParameter = new AtomicReference<>();
  private final ConditionVariable onSetRatingCalledCondition = new ConditionVariable();
  private final AtomicReference<SessionCommand> onCustomCommandParameter = new AtomicReference<>();
  private final ConditionVariable onCustomCommandCalledCondition = new ConditionVariable();
  private final SessionCommand customCommand = new SessionCommand("action", Bundle.EMPTY);

  private MediaSession session;
  private MockPlayer player;
  private RemoteMediaController controller;
  private HandlerThread asyncHandlerThread;

  @Before
  public void setUp() throws Exception {
    player =
        new MockPlayer.Builder()
            .setApplicationLooper(threadTestRule.getHandler().getLooper())
            .setMediaItems(/* itemCount= */ 5)
            .setChangePlayerStateWithTransportControl(true)
            .build();
    // Set player remote to allow tests with device volume changes.
    player.deviceInfo =
        new DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(100).build();
    asyncHandlerThread = new HandlerThread("AsyncHandlerThread");
    asyncHandlerThread.start();
    Handler asyncHandler = new Handler(asyncHandlerThread.getLooper());
    session =
        new MediaSession.Builder(ApplicationProvider.getApplicationContext(), player)
            .setCallback(
                new MediaSession.Callback() {
                  @Override
                  public MediaSession.ConnectionResult onConnect(
                      MediaSession session, MediaSession.ControllerInfo controller) {
                    if (SUPPORT_APP_PACKAGE_NAME.equals(controller.getPackageName())) {
                      return MediaSession.ConnectionResult.accept(
                          MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                              .buildUpon()
                              .add(customCommand)
                              .build(),
                          MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS);
                    }
                    return MediaSession.ConnectionResult.reject();
                  }

                  @Override
                  public ListenableFuture<SessionResult> onSetRating(
                      MediaSession mediaSession, ControllerInfo controller, Rating rating) {
                    onSetRatingParameter.set(rating);
                    onSetRatingCalledCondition.open();
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                  }

                  @Override
                  public ListenableFuture<SessionResult> onCustomCommand(
                      MediaSession mediaSession,
                      ControllerInfo controller,
                      SessionCommand customCommand,
                      Bundle args) {
                    onCustomCommandParameter.set(customCommand);
                    onCustomCommandCalledCondition.open();
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                  }

                  @Override
                  public ListenableFuture<List<MediaItem>> onAddMediaItems(
                      MediaSession mediaSession,
                      MediaSession.ControllerInfo controller,
                      List<MediaItem> mediaItems) {
                    // Send empty message and return mediaItems once done to simulate asynchronous
                    // media item resolution.
                    return Util.postOrRunWithCompletion(asyncHandler, () -> {}, mediaItems);
                  }
                })
            .build();

    // Create a default MediaController in client app.
    controller = remoteControllerTestRule.createRemoteController(session.getToken());
  }

  @After
  public void tearDown() throws Exception {
    controller.release();
    session.release();
    asyncHandlerThread.quit();
  }

  @Test
  public void play() throws Exception {
    controller.play();

    player.awaitMethodCalled(MockPlayer.METHOD_PLAY, TIMEOUT_MS);
  }

  @Test
  public void pause() throws Exception {
    controller.pause();

    player.awaitMethodCalled(MockPlayer.METHOD_PAUSE, TIMEOUT_MS);
  }

  @Test
  public void prepare() throws Exception {
    controller.prepare();

    player.awaitMethodCalled(MockPlayer.METHOD_PREPARE, TIMEOUT_MS);
  }

  @Test
  public void stop() throws Exception {
    controller.stop();

    player.awaitMethodCalled(MockPlayer.METHOD_STOP, TIMEOUT_MS);
  }

  @Test
  public void setPlayWhenReady() throws Exception {
    boolean testPlayWhenReady = true;

    controller.setPlayWhenReady(testPlayWhenReady);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_PLAY_WHEN_READY, TIMEOUT_MS);
    assertThat(player.playWhenReady).isEqualTo(testPlayWhenReady);
  }

  @Test
  public void seekToDefaultPosition() throws Exception {
    controller.seekToDefaultPosition();

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_DEFAULT_POSITION, TIMEOUT_MS);
  }

  @Test
  public void seekToDefaultPosition_withMediaItemIndex() throws Exception {
    int mediaItemIndex = 3;

    controller.seekToDefaultPosition(mediaItemIndex);

    player.awaitMethodCalled(
        MockPlayer.METHOD_SEEK_TO_DEFAULT_POSITION_WITH_MEDIA_ITEM_INDEX, TIMEOUT_MS);
    assertThat(player.seekMediaItemIndex).isEqualTo(mediaItemIndex);
  }

  @Test
  public void seekToDefaultPosition_withMediaItemIndexWithoutGetTimelineCommand() throws Exception {
    MockPlayer player =
        new MockPlayer.Builder()
            .setApplicationLooper(threadTestRule.getHandler().getLooper())
            .setMediaItems(/* itemCount= */ 5)
            .build();
    player.currentMediaItemIndex = 3;
    player.currentPeriodIndex = 3;
    MediaSession session =
        new MediaSession.Builder(ApplicationProvider.getApplicationContext(), player)
            .setCallback(
                new MediaSession.Callback() {
                  @Override
                  public MediaSession.ConnectionResult onConnect(
                      MediaSession session, MediaSession.ControllerInfo controller) {
                    SessionCommands sessionCommands =
                        new SessionCommands.Builder().addAllSessionCommands().build();
                    Player.Commands playerCommands =
                        new Player.Commands.Builder()
                            .addAllCommands()
                            .remove(Player.COMMAND_GET_TIMELINE)
                            .build();
                    return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands);
                  }
                })
            .setId("seekToDefaultPosition_withMediaItemIndexWithoutGetTimelineCommand")
            .build();
    RemoteMediaController controller =
        remoteControllerTestRule.createRemoteController(session.getToken());

    // The controller should only be able to see the current item without Timeline access.
    controller.seekToDefaultPosition(/* mediaItemIndex= */ 0);
    player.awaitMethodCalled(
        MockPlayer.METHOD_SEEK_TO_DEFAULT_POSITION_WITH_MEDIA_ITEM_INDEX, TIMEOUT_MS);
    controller.release();
    session.release();
    player.release();

    assertThat(player.seekMediaItemIndex).isEqualTo(3);
  }

  @Test
  public void seekTo() throws Exception {
    long seekPositionMs = 12125L;
    controller.seekTo(seekPositionMs);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO, TIMEOUT_MS);
    assertThat(player.seekPositionMs).isEqualTo(seekPositionMs);
  }

  @Test
  public void seekTo_withMediaItemIndex() throws Exception {
    int mediaItemIndex = 3;
    long seekPositionMs = 12125L;

    controller.seekTo(mediaItemIndex, seekPositionMs);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_WITH_MEDIA_ITEM_INDEX, TIMEOUT_MS);
    assertThat(player.seekMediaItemIndex).isEqualTo(mediaItemIndex);
    assertThat(player.seekPositionMs).isEqualTo(seekPositionMs);
  }

  @Test
  public void seekTo_withMediaItemIndexWithoutGetTimelineCommand() throws Exception {
    MockPlayer player =
        new MockPlayer.Builder()
            .setApplicationLooper(threadTestRule.getHandler().getLooper())
            .setMediaItems(/* itemCount= */ 5)
            .build();
    player.currentMediaItemIndex = 3;
    player.currentPeriodIndex = 3;
    MediaSession session =
        new MediaSession.Builder(ApplicationProvider.getApplicationContext(), player)
            .setCallback(
                new MediaSession.Callback() {
                  @Override
                  public MediaSession.ConnectionResult onConnect(
                      MediaSession session, MediaSession.ControllerInfo controller) {
                    SessionCommands sessionCommands =
                        new SessionCommands.Builder().addAllSessionCommands().build();
                    Player.Commands playerCommands =
                        new Player.Commands.Builder()
                            .addAllCommands()
                            .remove(Player.COMMAND_GET_TIMELINE)
                            .build();
                    return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands);
                  }
                })
            .setId("seekTo_withMediaItemIndexWithoutGetTimelineCommand")
            .build();
    RemoteMediaController controller =
        remoteControllerTestRule.createRemoteController(session.getToken());

    // The controller should only be able to see the current item without Timeline access.
    controller.seekTo(/* mediaItemIndex= */ 0, /* positionMs= */ 2000);
    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_WITH_MEDIA_ITEM_INDEX, TIMEOUT_MS);
    controller.release();
    session.release();
    player.release();

    assertThat(player.seekMediaItemIndex).isEqualTo(3);
    assertThat(player.seekPositionMs).isEqualTo(2000);
  }

  @Test
  public void setPlaybackSpeed() throws Exception {
    float testSpeed = 1.5f;

    controller.setPlaybackSpeed(testSpeed);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_PLAYBACK_SPEED, TIMEOUT_MS);
    assertThat(player.playbackParameters.speed).isEqualTo(testSpeed);
  }

  @Test
  public void setPlaybackParameters() throws Exception {
    PlaybackParameters testPlaybackParameters =
        new PlaybackParameters(/* speed= */ 1.4f, /* pitch= */ 2.3f);

    controller.setPlaybackParameters(testPlaybackParameters);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_PLAYBACK_PARAMETERS, TIMEOUT_MS);
    assertThat(player.playbackParameters).isEqualTo(testPlaybackParameters);
  }

  @Test
  public void setMediaItem() throws Exception {
    MediaItem item = MediaTestUtils.createMediaItem("setMediaItem", /* buildWithUri= */ true);
    long startPositionMs = 333L;
    boolean resetPosition = true;
    player.startPositionMs = startPositionMs;
    player.resetPosition = resetPosition;

    controller.setMediaItem(item);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_MEDIA_ITEMS_WITH_RESET_POSITION, TIMEOUT_MS);
    assertThat(player.mediaItems).containsExactly(item);
    assertThat(player.startPositionMs).isEqualTo(startPositionMs);
    assertThat(player.resetPosition).isEqualTo(resetPosition);
  }

  @Test
  public void setMediaItem_withStartPosition() throws Exception {
    MediaItem item =
        MediaTestUtils.createMediaItem("setMediaItem_withStartPosition", /* buildWithUri= */ true);
    long startPositionMs = 333L;
    boolean resetPosition = true;
    player.startPositionMs = startPositionMs;
    player.resetPosition = resetPosition;

    controller.setMediaItem(item);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_MEDIA_ITEMS_WITH_RESET_POSITION, TIMEOUT_MS);
    assertThat(player.mediaItems).containsExactly(item);
    assertThat(player.startPositionMs).isEqualTo(startPositionMs);
    assertThat(player.resetPosition).isEqualTo(resetPosition);
  }

  @Test
  public void setMediaItem_withResetPosition() throws Exception {
    MediaItem item =
        MediaTestUtils.createMediaItem("setMediaItem_withResetPosition", /* buildWithUri= */ true);
    long startPositionMs = 333L;
    boolean resetPosition = true;
    player.startPositionMs = startPositionMs;
    player.resetPosition = resetPosition;

    controller.setMediaItem(item);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_MEDIA_ITEMS_WITH_RESET_POSITION, TIMEOUT_MS);
    assertThat(player.mediaItems).containsExactly(item);
    assertThat(player.startPositionMs).isEqualTo(startPositionMs);
    assertThat(player.resetPosition).isEqualTo(resetPosition);
  }

  @Test
  public void setMediaItems() throws Exception {
    List<MediaItem> items =
        MediaTestUtils.createMediaItems(/* size= */ 2, /* buildWithUri= */ true);

    controller.setMediaItems(items);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_MEDIA_ITEMS_WITH_RESET_POSITION, TIMEOUT_MS);
    assertThat(player.mediaItems).isEqualTo(items);
    assertThat(player.resetPosition).isTrue();
  }

  @Test
  public void setMediaItems_withResetPosition() throws Exception {
    List<MediaItem> items =
        MediaTestUtils.createMediaItems(/* size= */ 2, /* buildWithUri= */ true);

    controller.setMediaItems(items, /* resetPosition= */ true);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_MEDIA_ITEMS_WITH_RESET_POSITION, TIMEOUT_MS);
    assertThat(player.mediaItems).isEqualTo(items);
    assertThat(player.resetPosition).isTrue();
  }

  @Test
  public void setMediaItems_withStartMediaItemIndex() throws Exception {
    List<MediaItem> items =
        MediaTestUtils.createMediaItems(/* size= */ 2, /* buildWithUri= */ true);
    int startMediaItemIndex = 1;
    long startPositionMs = 1234;

    controller.setMediaItems(items, startMediaItemIndex, startPositionMs);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_MEDIA_ITEMS_WITH_START_INDEX, TIMEOUT_MS);
    assertThat(player.mediaItems).isEqualTo(items);
    assertThat(player.startMediaItemIndex).isEqualTo(startMediaItemIndex);
    assertThat(player.startPositionMs).isEqualTo(startPositionMs);
  }

  @Test
  public void setMediaItems_withDuplicatedItems() throws Exception {
    int listSize = 4;
    List<MediaItem> list = MediaTestUtils.createMediaItems(listSize, /* buildWithUri= */ true);
    list.set(2, list.get(1));

    controller.setMediaItems(list);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_MEDIA_ITEMS_WITH_RESET_POSITION, TIMEOUT_MS);
    assertThat(player.mediaItems.size()).isEqualTo(listSize);
    for (int i = 0; i < listSize; i++) {
      assertThat(player.mediaItems.get(i).mediaId).isEqualTo(list.get(i).mediaId);
    }
  }

  @Test
  public void setMediaItems_withLongPlaylist() throws Exception {
    int listSize = 5000;
    // Make client app to generate a long list, and call setMediaItems() with it.
    controller.createAndSetFakeMediaItems(listSize);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_MEDIA_ITEMS_WITH_RESET_POSITION, TIMEOUT_MS);
    assertThat(player.mediaItems).isNotNull();
    assertThat(player.mediaItems.size()).isEqualTo(listSize);
    for (int i = 0; i < listSize; i++) {
      // Each item's media ID will be same as its index.
      assertThat(player.mediaItems.get(i).mediaId).isEqualTo(TestUtils.getMediaIdInFakeTimeline(i));
    }
  }

  @Test
  public void setPlaylistMetadata() throws Exception {
    MediaMetadata playlistMetadata = new MediaMetadata.Builder().setTitle("title").build();

    controller.setPlaylistMetadata(playlistMetadata);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_PLAYLIST_METADATA, TIMEOUT_MS);
    assertThat(player.playlistMetadata).isEqualTo(playlistMetadata);
  }

  @Test
  public void addMediaItem() throws Exception {
    MediaItem mediaItem = MediaTestUtils.createMediaItem("addMediaItem", /* buildWithUri= */ true);

    controller.addMediaItem(mediaItem);

    player.awaitMethodCalled(MockPlayer.METHOD_ADD_MEDIA_ITEMS, TIMEOUT_MS);
    assertThat(player.mediaItems).hasSize(6);
  }

  @Test
  public void addMediaItem_withIndex() throws Exception {
    int index = 2;
    MediaItem mediaItem =
        MediaTestUtils.createMediaItem("addMediaItem_withIndex", /* buildWithUri= */ true);

    controller.addMediaItem(index, mediaItem);

    player.awaitMethodCalled(MockPlayer.METHOD_ADD_MEDIA_ITEMS_WITH_INDEX, TIMEOUT_MS);
    assertThat(player.index).isEqualTo(index);
    assertThat(player.mediaItems).hasSize(6);
  }

  @Test
  public void addMediaItem_withIndexWithoutGetTimelineCommand() throws Exception {
    MockPlayer player =
        new MockPlayer.Builder()
            .setApplicationLooper(threadTestRule.getHandler().getLooper())
            .setMediaItems(/* itemCount= */ 5)
            .build();
    player.currentMediaItemIndex = 3;
    player.currentPeriodIndex = 3;
    MediaSession session =
        new MediaSession.Builder(ApplicationProvider.getApplicationContext(), player)
            .setCallback(
                new MediaSession.Callback() {
                  @Override
                  public MediaSession.ConnectionResult onConnect(
                      MediaSession session, MediaSession.ControllerInfo controller) {
                    SessionCommands sessionCommands =
                        new SessionCommands.Builder().addAllSessionCommands().build();
                    Player.Commands playerCommands =
                        new Player.Commands.Builder()
                            .addAllCommands()
                            .remove(Player.COMMAND_GET_TIMELINE)
                            .build();
                    return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands);
                  }

                  @Override
                  public ListenableFuture<List<MediaItem>> onAddMediaItems(
                      MediaSession mediaSession,
                      MediaSession.ControllerInfo controller,
                      List<MediaItem> mediaItems) {
                    return Futures.immediateFuture(mediaItems);
                  }
                })
            .setId("addMediaItem_withIndexWithoutGetTimelineCommand")
            .build();
    RemoteMediaController controller =
        remoteControllerTestRule.createRemoteController(session.getToken());
    MediaItem mediaItem =
        MediaTestUtils.createMediaItem("addMediaItem_withIndex", /* buildWithUri= */ true);

    // The controller should only be able to see the current item without Timeline access.
    controller.addMediaItem(/* index= */ 1, mediaItem);
    player.awaitMethodCalled(MockPlayer.METHOD_ADD_MEDIA_ITEMS_WITH_INDEX, TIMEOUT_MS);
    controller.release();
    session.release();
    player.release();

    assertThat(player.index).isEqualTo(4);
  }

  @Test
  public void addMediaItems() throws Exception {
    int size = 2;
    List<MediaItem> mediaItems = MediaTestUtils.createMediaItems(size, /* buildWithUri= */ true);

    controller.addMediaItems(mediaItems);

    player.awaitMethodCalled(MockPlayer.METHOD_ADD_MEDIA_ITEMS, TIMEOUT_MS);
    assertThat(player.mediaItems).hasSize(7);
  }

  @Test
  public void addMediaItems_withIndex() throws Exception {
    int index = 0;
    int size = 2;
    List<MediaItem> mediaItems = MediaTestUtils.createMediaItems(size, /* buildWithUri= */ true);

    controller.addMediaItems(index, mediaItems);

    player.awaitMethodCalled(MockPlayer.METHOD_ADD_MEDIA_ITEMS_WITH_INDEX, TIMEOUT_MS);
    assertThat(player.index).isEqualTo(index);
    assertThat(player.mediaItems).hasSize(7);
  }

  @Test
  public void addMediaItems_withIndexWithoutGetTimelineCommand() throws Exception {
    MockPlayer player =
        new MockPlayer.Builder()
            .setApplicationLooper(threadTestRule.getHandler().getLooper())
            .setMediaItems(/* itemCount= */ 5)
            .build();
    player.currentMediaItemIndex = 3;
    player.currentPeriodIndex = 3;
    MediaSession session =
        new MediaSession.Builder(ApplicationProvider.getApplicationContext(), player)
            .setCallback(
                new MediaSession.Callback() {
                  @Override
                  public MediaSession.ConnectionResult onConnect(
                      MediaSession session, MediaSession.ControllerInfo controller) {
                    SessionCommands sessionCommands =
                        new SessionCommands.Builder().addAllSessionCommands().build();
                    Player.Commands playerCommands =
                        new Player.Commands.Builder()
                            .addAllCommands()
                            .remove(Player.COMMAND_GET_TIMELINE)
                            .build();
                    return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands);
                  }

                  @Override
                  public ListenableFuture<List<MediaItem>> onAddMediaItems(
                      MediaSession mediaSession,
                      MediaSession.ControllerInfo controller,
                      List<MediaItem> mediaItems) {
                    return Futures.immediateFuture(mediaItems);
                  }
                })
            .setId("addMediaItems_withIndexWithoutGetTimelineCommand")
            .build();
    RemoteMediaController controller =
        remoteControllerTestRule.createRemoteController(session.getToken());
    MediaItem mediaItem =
        MediaTestUtils.createMediaItem("addMediaItem_withIndex", /* buildWithUri= */ true);

    // The controller should only be able to see the current item without Timeline access.
    controller.addMediaItems(/* index= */ 1, ImmutableList.of(mediaItem, mediaItem));
    player.awaitMethodCalled(MockPlayer.METHOD_ADD_MEDIA_ITEMS_WITH_INDEX, TIMEOUT_MS);
    controller.release();
    session.release();
    player.release();

    assertThat(player.index).isEqualTo(4);
  }

  @Test
  public void removeMediaItem() throws Exception {
    int index = 3;

    controller.removeMediaItem(index);

    player.awaitMethodCalled(MockPlayer.METHOD_REMOVE_MEDIA_ITEM, TIMEOUT_MS);
    assertThat(player.index).isEqualTo(index);
  }

  @Test
  public void removeMediaItem_withoutGetTimelineCommand() throws Exception {
    MockPlayer player =
        new MockPlayer.Builder()
            .setApplicationLooper(threadTestRule.getHandler().getLooper())
            .setMediaItems(/* itemCount= */ 5)
            .build();
    player.currentMediaItemIndex = 3;
    player.currentPeriodIndex = 3;
    MediaSession session =
        new MediaSession.Builder(ApplicationProvider.getApplicationContext(), player)
            .setCallback(
                new MediaSession.Callback() {
                  @Override
                  public MediaSession.ConnectionResult onConnect(
                      MediaSession session, MediaSession.ControllerInfo controller) {
                    SessionCommands sessionCommands =
                        new SessionCommands.Builder().addAllSessionCommands().build();
                    Player.Commands playerCommands =
                        new Player.Commands.Builder()
                            .addAllCommands()
                            .remove(Player.COMMAND_GET_TIMELINE)
                            .build();
                    return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands);
                  }
                })
            .setId("removeMediaItem_withoutGetTimelineCommand")
            .build();
    RemoteMediaController controller =
        remoteControllerTestRule.createRemoteController(session.getToken());

    // The controller should only be able to see the current item without Timeline access.
    controller.removeMediaItem(/* index= */ 0);
    player.awaitMethodCalled(MockPlayer.METHOD_REMOVE_MEDIA_ITEM, TIMEOUT_MS);
    controller.release();
    session.release();
    player.release();

    assertThat(player.index).isEqualTo(3);
  }

  @Test
  public void removeMediaItems() throws Exception {
    int fromIndex = 0;
    int toIndex = 3;

    controller.removeMediaItems(fromIndex, toIndex);

    player.awaitMethodCalled(MockPlayer.METHOD_REMOVE_MEDIA_ITEMS, TIMEOUT_MS);
    assertThat(player.fromIndex).isEqualTo(fromIndex);
    assertThat(player.toIndex).isEqualTo(toIndex);
  }

  @Test
  public void removeMediaItems_withoutGetTimelineCommand() throws Exception {
    MockPlayer player =
        new MockPlayer.Builder()
            .setApplicationLooper(threadTestRule.getHandler().getLooper())
            .setMediaItems(/* itemCount= */ 5)
            .build();
    player.currentMediaItemIndex = 3;
    player.currentPeriodIndex = 3;
    MediaSession session =
        new MediaSession.Builder(ApplicationProvider.getApplicationContext(), player)
            .setCallback(
                new MediaSession.Callback() {
                  @Override
                  public MediaSession.ConnectionResult onConnect(
                      MediaSession session, MediaSession.ControllerInfo controller) {
                    SessionCommands sessionCommands =
                        new SessionCommands.Builder().addAllSessionCommands().build();
                    Player.Commands playerCommands =
                        new Player.Commands.Builder()
                            .addAllCommands()
                            .remove(Player.COMMAND_GET_TIMELINE)
                            .build();
                    return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands);
                  }
                })
            .setId("removeMediaItems_withoutGetTimelineCommand")
            .build();
    RemoteMediaController controller =
        remoteControllerTestRule.createRemoteController(session.getToken());

    // The controller should only be able to see the current item without Timeline access.
    controller.removeMediaItems(/* fromIndex= */ 0, /* toIndex= */ 0);
    player.awaitMethodCalled(MockPlayer.METHOD_REMOVE_MEDIA_ITEMS, TIMEOUT_MS);
    controller.release();
    session.release();
    player.release();

    assertThat(player.fromIndex).isEqualTo(3);
    assertThat(player.toIndex).isEqualTo(3);
  }

  @Test
  public void clearMediaItems() throws Exception {
    controller.clearMediaItems();

    player.awaitMethodCalled(MockPlayer.METHOD_CLEAR_MEDIA_ITEMS, TIMEOUT_MS);
  }

  @Test
  public void moveMediaItem() throws Exception {
    int index = 4;
    int newIndex = 1;

    controller.moveMediaItem(index, newIndex);

    player.awaitMethodCalled(MockPlayer.METHOD_MOVE_MEDIA_ITEM, TIMEOUT_MS);
    assertThat(player.index).isEqualTo(index);
    assertThat(player.newIndex).isEqualTo(newIndex);
  }

  @Test
  public void moveMediaItems() throws Exception {
    int fromIndex = 0;
    int toIndex = 2;
    int newIndex = 1;

    controller.moveMediaItems(fromIndex, toIndex, newIndex);

    player.awaitMethodCalled(MockPlayer.METHOD_MOVE_MEDIA_ITEMS, TIMEOUT_MS);
    assertThat(player.fromIndex).isEqualTo(fromIndex);
    assertThat(player.toIndex).isEqualTo(toIndex);
    assertThat(player.newIndex).isEqualTo(newIndex);
  }

  @Test
  public void replaceMediaItem() throws Exception {
    player.setMediaItems(MediaTestUtils.createMediaItems(4, true));
    MediaItem mediaItem =
        MediaTestUtils.createMediaItem("replaceMediaItem", /* buildWithUri= */ true);

    controller.replaceMediaItem(/* index= */ 2, mediaItem);

    player.awaitMethodCalled(MockPlayer.METHOD_REPLACE_MEDIA_ITEM, TIMEOUT_MS);
    assertThat(player.index).isEqualTo(2);
    assertThat(player.mediaItems).hasSize(4);
    assertThat(player.mediaItems.get(2)).isEqualTo(mediaItem);
  }

  @Test
  public void replaceMediaItems() throws Exception {
    player.setMediaItems(MediaTestUtils.createMediaItems(4, /* buildWithUri= */ true));
    List<MediaItem> mediaItems = MediaTestUtils.createMediaItems(2, /* buildWithUri= */ true);

    controller.replaceMediaItems(/* fromIndex= */ 1, /* toIndex= */ 2, mediaItems);

    player.awaitMethodCalled(MockPlayer.METHOD_REPLACE_MEDIA_ITEMS, TIMEOUT_MS);
    assertThat(player.fromIndex).isEqualTo(1);
    assertThat(player.toIndex).isEqualTo(2);
    assertThat(player.mediaItems).hasSize(5);
    assertThat(player.mediaItems.get(1)).isEqualTo(mediaItems.get(0));
    assertThat(player.mediaItems.get(2)).isEqualTo(mediaItems.get(1));
  }

  @Test
  public void seekToPreviousMediaItem() throws Exception {
    controller.seekToPreviousMediaItem();

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_PREVIOUS_MEDIA_ITEM, TIMEOUT_MS);
  }

  @Test
  public void seekToNextMediaItem() throws Exception {
    controller.seekToNextMediaItem();

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_NEXT_MEDIA_ITEM, TIMEOUT_MS);
  }

  @Test
  public void seekToPrevious() throws Exception {
    controller.seekToPrevious();

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_PREVIOUS, TIMEOUT_MS);
  }

  @Test
  public void seekToNext() throws Exception {
    controller.seekToNext();

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_NEXT, TIMEOUT_MS);
  }

  @Test
  public void setShuffleModeEnabled() throws Exception {
    boolean testShuffleModeEnabled = true;

    controller.setShuffleModeEnabled(testShuffleModeEnabled);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_SHUFFLE_MODE, TIMEOUT_MS);
    assertThat(player.shuffleModeEnabled).isEqualTo(testShuffleModeEnabled);
  }

  @Test
  public void setRepeatMode() throws Exception {
    int testRepeatMode = Player.REPEAT_MODE_ALL;

    controller.setRepeatMode(testRepeatMode);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_REPEAT_MODE, TIMEOUT_MS);
    assertThat(player.repeatMode).isEqualTo(testRepeatMode);
  }

  @Test
  public void setVolume() throws Exception {
    float testVolume = .123f;

    controller.setVolume(testVolume);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_VOLUME, TIMEOUT_MS);
    assertThat(player.volume).isEqualTo(testVolume);
  }

  @Test
  public void setDeviceVolume() throws Exception {
    int testVolume = 12;

    controller.setDeviceVolume(testVolume);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_DEVICE_VOLUME, TIMEOUT_MS);
    assertThat(player.deviceVolume).isEqualTo(testVolume);
  }

  @Test
  public void increaseDeviceVolume() throws Exception {
    controller.increaseDeviceVolume();

    player.awaitMethodCalled(MockPlayer.METHOD_INCREASE_DEVICE_VOLUME, TIMEOUT_MS);
  }

  @Test
  public void decreaseDeviceVolume() throws Exception {
    controller.decreaseDeviceVolume();

    player.awaitMethodCalled(MockPlayer.METHOD_DECREASE_DEVICE_VOLUME, TIMEOUT_MS);
  }

  @Test
  public void setDeviceMuted() throws Exception {
    player.deviceMuted = false;

    controller.setDeviceMuted(true);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_DEVICE_MUTED, TIMEOUT_MS);
    assertThat(player.deviceMuted).isTrue();
  }

  @Test
  public void setAudioAttributes() throws Exception {
    AudioAttributes audioAttributes =
        new AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SONIFICATION)
            .setUsage(C.USAGE_ALARM)
            .build();

    controller.setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_AUDIO_ATTRIBUTES, TIMEOUT_MS);
    assertThat(player.getAudioAttributes()).isEqualTo(audioAttributes);
  }

  @Test
  public void seekBack() throws Exception {
    controller.seekBack();

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_BACK, TIMEOUT_MS);
  }

  @Test
  public void seekForward() throws Exception {
    controller.seekForward();

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_FORWARD, TIMEOUT_MS);
  }

  @Test
  public void setTrackSelectionParameters() throws Exception {
    TrackSelectionParameters trackSelectionParameters =
        TrackSelectionParameters.DEFAULT.buildUpon().setMaxAudioBitrate(10).build();

    controller.setTrackSelectionParameters(trackSelectionParameters);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_TRACK_SELECTION_PARAMETERS, TIMEOUT_MS);
    assertThat(player.trackSelectionParameters).isEqualTo(trackSelectionParameters);
  }

  @Test
  public void sendCustomCommand_malformedPredefinedCommand_doesNotCrash() throws Exception {
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    SessionResult result =
        controller.sendCustomCommand(
            new SessionCommand("androidx.media3.session.PLAYER_COMMAND_invalid", Bundle.EMPTY),
            Bundle.EMPTY);

    // Do something else to know that the malformed custom command has been handled and ignored.
    controller.prepare();
    player.awaitMethodCalled(MockPlayer.METHOD_PREPARE, TIMEOUT_MS);
    assertThat(result.resultCode).isNotEqualTo(SessionResult.RESULT_SUCCESS);
  }

  @Test
  public void executeCommandButtonAction_playPauseWithoutParam_togglesPlayWhenReady()
      throws Exception {
    player.playWhenReady = false;
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PLAY)
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_PLAY_WHEN_READY, TIMEOUT_MS);
    assertThat(player.playWhenReady).isTrue();
  }

  @Test
  public void executeCommandButtonAction_playPauseWithParam_setsPlayWhenReady() throws Exception {
    player.playWhenReady = true;
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PAUSE)
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE, false)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_PLAY_WHEN_READY, TIMEOUT_MS);
    assertThat(player.playWhenReady).isFalse();
  }

  @Test
  public void executeCommandButtonAction_prepare_callsPrepare() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PLAY)
            .setPlayerCommand(Player.COMMAND_PREPARE)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_PREPARE, TIMEOUT_MS);
  }

  @Test
  public void executeCommandButtonAction_stop_callsStop() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_STOP)
            .setPlayerCommand(Player.COMMAND_STOP)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_STOP, TIMEOUT_MS);
  }

  @Test
  public void executeCommandButtonAction_seekToPrevious_callsSeekToPrevious() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_PREVIOUS, TIMEOUT_MS);
  }

  @Test
  public void executeCommandButtonAction_seekToNext_callsSeekToNext() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_NEXT)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_NEXT, TIMEOUT_MS);
  }

  @Test
  public void executeCommandButtonAction_seekToPreviousMediaItem_callsSeekToPreviousMediaItem()
      throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_PREVIOUS_MEDIA_ITEM, TIMEOUT_MS);
  }

  @Test
  public void executeCommandButtonAction_seekToNextMediaItem_callsSeekToNextMediaItem()
      throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_NEXT)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_NEXT_MEDIA_ITEM, TIMEOUT_MS);
  }

  @Test
  public void executeCommandButtonAction_seekBack_callsSeekBack() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_BACK, TIMEOUT_MS);
  }

  @Test
  public void executeCommandButtonAction_seekForward_callsSeekForward() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_FORWARD, TIMEOUT_MS);
  }

  @Test
  public void executeCommandButtonAction_seekToDefaultPosition_callsSeekToDefaultPosition()
      throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_DEFAULT_POSITION, TIMEOUT_MS);
  }

  @Test
  public void executeCommandButtonAction_seekInCurrentMediaItem_seeksToPosition() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_REWIND)
            .setPlayerCommand(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, 1234L)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO, TIMEOUT_MS);
    assertThat(player.seekPositionMs).isEqualTo(1234L);
  }

  @Test
  public void executeCommandButtonAction_seekToMediaItem_seeksToMediaItem() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_NEXT)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_MEDIA_ITEM, 1)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(
        MockPlayer.METHOD_SEEK_TO_DEFAULT_POSITION_WITH_MEDIA_ITEM_INDEX, TIMEOUT_MS);
    assertThat(player.seekMediaItemIndex).isEqualTo(1);
  }

  @Test
  public void executeCommandButtonAction_setSpeed_setsPlaybackSpeed() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PLAYBACK_SPEED)
            .setPlayerCommand(Player.COMMAND_SET_SPEED_AND_PITCH, 1.5f)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_PLAYBACK_SPEED, TIMEOUT_MS);
    assertThat(player.playbackParameters.speed).isEqualTo(1.5f);
  }

  @Test
  public void executeCommandButtonAction_setShuffleModeWithoutParam_togglesShuffleMode()
      throws Exception {
    player.shuffleModeEnabled = false;
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_SHUFFLE_ON)
            .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_SHUFFLE_MODE, TIMEOUT_MS);
    assertThat(player.shuffleModeEnabled).isTrue();
  }

  @Test
  public void executeCommandButtonAction_setShuffleModeWithParam_setsShuffleMode()
      throws Exception {
    player.shuffleModeEnabled = false;
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_SHUFFLE_ON)
            .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE, true)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_SHUFFLE_MODE, TIMEOUT_MS);
    assertThat(player.shuffleModeEnabled).isTrue();
  }

  @Test
  public void executeCommandButtonAction_setRepeatMode_setsRepeatMode() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_REPEAT_ONE)
            .setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE, Player.REPEAT_MODE_ONE)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_REPEAT_MODE, TIMEOUT_MS);
    assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE);
  }

  @Test
  public void executeCommandButtonAction_setMediaItem_setsMediaItem() throws Exception {
    MediaItem mediaItem = new MediaItem.Builder().setMediaId("id").build();
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PLAY)
            .setPlayerCommand(Player.COMMAND_SET_MEDIA_ITEM, mediaItem)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_MEDIA_ITEMS_WITH_RESET_POSITION, TIMEOUT_MS);
    assertThat(player.mediaItems).containsExactly(mediaItem);
  }

  @Test
  public void executeCommandButtonAction_setPlaylistMetadata_setsPlaylistMetadata()
      throws Exception {
    MediaMetadata mediaMetadata = new MediaMetadata.Builder().setTitle("title").build();
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PLAYLIST_ADD)
            .setPlayerCommand(Player.COMMAND_SET_PLAYLIST_METADATA, mediaMetadata)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_PLAYLIST_METADATA, TIMEOUT_MS);
    assertThat(player.playlistMetadata).isEqualTo(mediaMetadata);
  }

  @Test
  public void executeCommandButtonAction_setVolumeWithParam_setsVolume() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_VOLUME_UP)
            .setPlayerCommand(Player.COMMAND_SET_VOLUME, 0.5f)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_VOLUME, TIMEOUT_MS);
    assertThat(player.volume).isEqualTo(0.5f);
  }

  @Test
  public void executeCommandButtonAction_setVolumeWithoutParam_togglesMute() throws Exception {
    player.deviceMuted = false;
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_VOLUME_OFF)
            .setPlayerCommand(Player.COMMAND_SET_VOLUME)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_VOLUME, TIMEOUT_MS);
    assertThat(player.getVolume()).isEqualTo(0);
  }

  @Test
  public void executeCommandButtonAction_setTrackSelectionParameters_setsTrackSelectionParameters()
      throws Exception {
    TrackSelectionParameters trackSelectionParameters =
        TrackSelectionParameters.DEFAULT.buildUpon().setMaxVideoBitrate(1000).build();
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_QUALITY)
            .setPlayerCommand(
                Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS, trackSelectionParameters)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_TRACK_SELECTION_PARAMETERS, TIMEOUT_MS);
    assertThat(player.trackSelectionParameters).isEqualTo(trackSelectionParameters);
  }

  @Test
  public void executeCommandButtonAction_setRating_setsRating() throws Exception {
    Rating rating = new HeartRating(true);
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_HEART_FILLED)
            .setSessionCommand(
                new SessionCommand(SessionCommand.COMMAND_CODE_SESSION_SET_RATING), rating)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    assertThat(onSetRatingCalledCondition.block(TIMEOUT_MS)).isTrue();
    assertThat(onSetRatingParameter.get()).isEqualTo(rating);
  }

  @Test
  public void executeCommandButtonAction_customCommand_sendsCustomCommand() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_ALBUM)
            .setSessionCommand(customCommand)
            .setDisplayName("test")
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCommandButtonAction(0);

    assertThat(onCustomCommandCalledCondition.block(TIMEOUT_MS)).isTrue();
    assertThat(onCustomCommandParameter.get()).isEqualTo(customCommand);
  }

  @Test
  public void executeCustomLayoutAction_playPauseWithoutParamToFalse_togglesPlayWhenReady()
      throws Exception {
    player.playWhenReady = true;
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PLAY)
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_PLAY_WHEN_READY, TIMEOUT_MS);
    assertThat(player.playWhenReady).isFalse();
  }

  @Test
  public void executeCustomLayoutAction_playPauseWithParamToFalse_setsPlayWhenReady()
      throws Exception {
    player.playWhenReady = true;
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PAUSE)
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE, false)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_PLAY_WHEN_READY, TIMEOUT_MS);
    assertThat(player.playWhenReady).isFalse();
  }

  @Test
  public void executeCustomLayoutAction_playPauseWithoutParamToTrue_preparesAndSetsPlayWhenReady()
      throws Exception {
    player.playWhenReady = false;
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PLAY)
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_PREPARE, TIMEOUT_MS);
    player.awaitMethodCalled(MockPlayer.METHOD_PLAY, TIMEOUT_MS);
    assertThat(player.playWhenReady).isTrue();
  }

  @Test
  public void executeCustomLayoutAction_playPauseWithParamToTrue_preparesAndSetsPlayWhenReady()
      throws Exception {
    player.playWhenReady = false;
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PAUSE)
            .setPlayerCommand(Player.COMMAND_PLAY_PAUSE, true)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_PREPARE, TIMEOUT_MS);
    player.awaitMethodCalled(MockPlayer.METHOD_PLAY, TIMEOUT_MS);
    assertThat(player.playWhenReady).isTrue();
  }

  @Test
  public void executeCustomLayoutAction_prepare_callsPrepare() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PLAY)
            .setPlayerCommand(Player.COMMAND_PREPARE)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_PREPARE, TIMEOUT_MS);
  }

  @Test
  public void executeCustomLayoutAction_stop_callsStop() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_STOP)
            .setPlayerCommand(Player.COMMAND_STOP)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_STOP, TIMEOUT_MS);
  }

  @Test
  public void executeCustomLayoutAction_seekToPrevious_callsSeekToPrevious() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_PREVIOUS, TIMEOUT_MS);
  }

  @Test
  public void executeCustomLayoutAction_seekToNext_callsSeekToNext() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_NEXT)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_NEXT, TIMEOUT_MS);
  }

  @Test
  public void executeCustomLayoutAction_seekToPreviousMediaItem_callsSeekToPreviousMediaItem()
      throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_PREVIOUS_MEDIA_ITEM, TIMEOUT_MS);
  }

  @Test
  public void executeCustomLayoutAction_seekToNextMediaItem_callsSeekToNextMediaItem()
      throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_NEXT)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_NEXT_MEDIA_ITEM, TIMEOUT_MS);
  }

  @Test
  public void executeCustomLayoutAction_seekBack_callsSeekBack() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_BACK, TIMEOUT_MS);
  }

  @Test
  public void executeCustomLayoutAction_seekForward_callsSeekForward() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_FORWARD, TIMEOUT_MS);
  }

  @Test
  public void executeCustomLayoutAction_seekToDefaultPosition_callsSeekToDefaultPosition()
      throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_DEFAULT_POSITION, TIMEOUT_MS);
  }

  @Test
  public void executeCustomLayoutAction_seekInCurrentMediaItem_seeksToPosition() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_REWIND)
            .setPlayerCommand(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, 1234L)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO, TIMEOUT_MS);
    assertThat(player.seekPositionMs).isEqualTo(1234L);
  }

  @Test
  public void executeCustomLayoutAction_seekToMediaItem_seeksToMediaItem() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_NEXT)
            .setPlayerCommand(Player.COMMAND_SEEK_TO_MEDIA_ITEM, 1)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(
        MockPlayer.METHOD_SEEK_TO_DEFAULT_POSITION_WITH_MEDIA_ITEM_INDEX, TIMEOUT_MS);
    assertThat(player.seekMediaItemIndex).isEqualTo(1);
  }

  @Test
  public void executeCustomLayoutAction_setSpeed_setsPlaybackSpeed() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PLAYBACK_SPEED)
            .setPlayerCommand(Player.COMMAND_SET_SPEED_AND_PITCH, 1.5f)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_PLAYBACK_SPEED, TIMEOUT_MS);
    assertThat(player.playbackParameters.speed).isEqualTo(1.5f);
  }

  @Test
  public void executeCustomLayoutAction_setShuffleModeWithoutParam_togglesShuffleMode()
      throws Exception {
    player.shuffleModeEnabled = false;
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_SHUFFLE_ON)
            .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_SHUFFLE_MODE, TIMEOUT_MS);
    assertThat(player.shuffleModeEnabled).isTrue();
  }

  @Test
  public void executeCustomLayoutAction_setShuffleModeWithParam_setsShuffleMode() throws Exception {
    player.shuffleModeEnabled = false;
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_SHUFFLE_ON)
            .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE, true)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_SHUFFLE_MODE, TIMEOUT_MS);
    assertThat(player.shuffleModeEnabled).isTrue();
  }

  @Test
  public void executeCustomLayoutAction_setRepeatMode_setsRepeatMode() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_REPEAT_ONE)
            .setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE, Player.REPEAT_MODE_ONE)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_REPEAT_MODE, TIMEOUT_MS);
    assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE);
  }

  @Test
  public void executeCustomLayoutAction_setMediaItem_setsMediaItem() throws Exception {
    MediaItem mediaItem = new MediaItem.Builder().setMediaId("id").build();
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PLAY)
            .setPlayerCommand(Player.COMMAND_SET_MEDIA_ITEM, mediaItem)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_MEDIA_ITEMS_WITH_RESET_POSITION, TIMEOUT_MS);
    assertThat(player.mediaItems).containsExactly(mediaItem);
  }

  @Test
  public void executeCustomLayoutAction_setPlaylistMetadata_setsPlaylistMetadata()
      throws Exception {
    MediaMetadata mediaMetadata = new MediaMetadata.Builder().setTitle("title").build();
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_PLAYLIST_ADD)
            .setPlayerCommand(Player.COMMAND_SET_PLAYLIST_METADATA, mediaMetadata)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_PLAYLIST_METADATA, TIMEOUT_MS);
    assertThat(player.playlistMetadata).isEqualTo(mediaMetadata);
  }

  @Test
  public void executeCustomLayoutAction_setVolumeWithParam_setsVolume() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_VOLUME_UP)
            .setPlayerCommand(Player.COMMAND_SET_VOLUME, 0.5f)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_VOLUME, TIMEOUT_MS);
    assertThat(player.volume).isEqualTo(0.5f);
  }

  @Test
  public void executeCustomLayoutAction_setVolumeWithoutParam_togglesMute() throws Exception {
    player.deviceMuted = false;
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_VOLUME_OFF)
            .setPlayerCommand(Player.COMMAND_SET_VOLUME)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_VOLUME, TIMEOUT_MS);
    assertThat(player.getVolume()).isEqualTo(0);
  }

  @Test
  public void executeCustomLayoutAction_setTrackSelectionParameters_setsTrackSelectionParameters()
      throws Exception {
    TrackSelectionParameters trackSelectionParameters =
        TrackSelectionParameters.DEFAULT.buildUpon().setMaxVideoBitrate(1000).build();
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_QUALITY)
            .setPlayerCommand(
                Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS, trackSelectionParameters)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    player.awaitMethodCalled(MockPlayer.METHOD_SET_TRACK_SELECTION_PARAMETERS, TIMEOUT_MS);
    assertThat(player.trackSelectionParameters).isEqualTo(trackSelectionParameters);
  }

  @Test
  public void executeCustomLayoutAction_setRating_setsRating() throws Exception {
    Rating rating = new HeartRating(true);
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_HEART_FILLED)
            .setSessionCommand(
                new SessionCommand(SessionCommand.COMMAND_CODE_SESSION_SET_RATING), rating)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    assertThat(onSetRatingCalledCondition.block(TIMEOUT_MS)).isTrue();
    assertThat(onSetRatingParameter.get()).isEqualTo(rating);
  }

  @Test
  public void executeCustomLayoutAction_customCommand_sendsCustomCommand() throws Exception {
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_ALBUM)
            .setSessionCommand(customCommand)
            .setDisplayName("test")
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build();
    session.setMediaButtonPreferences(ImmutableList.of(button));
    controller = remoteControllerTestRule.createRemoteController(session.getToken());

    controller.executeCustomLayoutAction(0);

    assertThat(onCustomCommandCalledCondition.block(TIMEOUT_MS)).isTrue();
    assertThat(onCustomCommandParameter.get()).isEqualTo(customCommand);
  }

  @Test
  public void mixedAsyncAndSyncCommands_calledInCorrectOrder() throws Exception {
    List<MediaItem> initialItems =
        MediaTestUtils.createMediaItems(/* size= */ 2, /* buildWithUri= */ true);
    List<MediaItem> addedItems =
        MediaTestUtils.createMediaItems(/* size= */ 3, /* buildWithUri= */ true);

    controller.setMediaItemsPreparePlayAddItemsSeek(initialItems, addedItems, /* seekIndex= */ 3);
    player.awaitMethodCalled(MockPlayer.METHOD_PREPARE, TIMEOUT_MS);
    boolean setMediaItemsCalledBeforePrepare =
        player.hasMethodBeenCalled(MockPlayer.METHOD_SET_MEDIA_ITEMS_WITH_RESET_POSITION);
    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_WITH_MEDIA_ITEM_INDEX, TIMEOUT_MS);
    boolean addMediaItemsCalledBeforeSeek =
        player.hasMethodBeenCalled(MockPlayer.METHOD_ADD_MEDIA_ITEMS);

    assertThat(setMediaItemsCalledBeforePrepare).isTrue();
    assertThat(addMediaItemsCalledBeforeSeek).isTrue();
    assertThat(player.hasMethodBeenCalled(MockPlayer.METHOD_PLAY)).isTrue();
    assertThat(player.mediaItems).hasSize(5);
    assertThat(player.seekMediaItemIndex).isEqualTo(3);
  }

  @Test
  public void
      getControllerForCurrentRequest_withMediaControllerAndSimplePlayerMethod_returnsControllerFromPlayerMethod()
          throws Exception {
    AtomicReference<MediaSession.ControllerInfo> controllerInfoFromPlayerMethod =
        new AtomicReference<>();
    AtomicReference<MediaSession> sessionReference = new AtomicReference<>();
    CountDownLatch eventHandled = new CountDownLatch(1);
    Player player =
        new SimpleBasePlayer(Looper.getMainLooper()) {
          @Override
          protected State getState() {
            return new State.Builder()
                .setAvailableCommands(new Commands.Builder().add(Player.COMMAND_PLAY_PAUSE).build())
                .build();
          }

          @Override
          protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
            controllerInfoFromPlayerMethod.set(
                sessionReference.get().getControllerForCurrentRequest());
            eventHandled.countDown();
            return Futures.immediateVoidFuture();
          }
        };
    Context context = ApplicationProvider.getApplicationContext();
    MediaSession session = new MediaSession.Builder(context, player).setId("test").build();
    sessionReference.set(session);
    Bundle controllerHints = new Bundle();
    controllerHints.putString("key", "value");
    MediaController controller =
        new MediaController.Builder(context, session.getToken())
            .setConnectionHints(controllerHints)
            .buildAsync()
            .get();

    MainLooperTestRule.runOnMainSync(controller::play);
    eventHandled.await();
    session.release();

    assertThat(controllerInfoFromPlayerMethod.get().getConnectionHints().getString("key"))
        .isEqualTo("value");
  }

  @Test
  public void
      getControllerForCurrentRequest_withMediaControllerAndAsyncSetMediaItemMethod_returnsControllerFromPlayerMethod()
          throws Exception {
    AtomicReference<MediaSession.ControllerInfo> controllerInfoFromPlayerMethod =
        new AtomicReference<>();
    AtomicReference<MediaSession> sessionReference = new AtomicReference<>();
    CountDownLatch eventHandled = new CountDownLatch(1);
    Player player =
        new SimpleBasePlayer(Looper.getMainLooper()) {
          @Override
          protected State getState() {
            return new State.Builder()
                .setAvailableCommands(
                    new Commands.Builder().add(Player.COMMAND_SET_MEDIA_ITEM).build())
                .build();
          }

          @Override
          protected ListenableFuture<?> handleSetMediaItems(
              List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
            controllerInfoFromPlayerMethod.set(
                sessionReference.get().getControllerForCurrentRequest());
            eventHandled.countDown();
            return Futures.immediateVoidFuture();
          }
        };
    Context context = ApplicationProvider.getApplicationContext();
    MediaSession session =
        new MediaSession.Builder(context, player)
            .setId("test")
            .setCallback(
                new MediaSession.Callback() {
                  @Override
                  public ListenableFuture<List<MediaItem>> onAddMediaItems(
                      MediaSession mediaSession,
                      MediaSession.ControllerInfo controller,
                      List<MediaItem> mediaItems) {
                    // Resolve media items asynchronously.
                    return Util.postOrRunWithCompletion(
                        threadTestRule.getHandler(), () -> {}, mediaItems);
                  }
                })
            .build();
    sessionReference.set(session);
    Bundle controllerHints = new Bundle();
    controllerHints.putString("key", "value");
    MediaController controller =
        new MediaController.Builder(context, session.getToken())
            .setConnectionHints(controllerHints)
            .buildAsync()
            .get();

    MainLooperTestRule.runOnMainSync(() -> controller.setMediaItem(MediaItem.fromUri("test://")));
    eventHandled.await();
    session.release();

    assertThat(controllerInfoFromPlayerMethod.get().getConnectionHints().getString("key"))
        .isEqualTo("value");
  }

  @Test
  public void
      getControllerForCurrentRequest_withMediaControllerAndAsyncAddMediaItemMethod_returnsControllerFromPlayerMethod()
          throws Exception {
    AtomicReference<MediaSession.ControllerInfo> controllerInfoFromPlayerMethod =
        new AtomicReference<>();
    AtomicReference<MediaSession> sessionReference = new AtomicReference<>();
    CountDownLatch eventHandled = new CountDownLatch(1);
    Player player =
        new SimpleBasePlayer(Looper.getMainLooper()) {
          @Override
          protected State getState() {
            return new State.Builder()
                .setAvailableCommands(
                    new Commands.Builder().add(Player.COMMAND_CHANGE_MEDIA_ITEMS).build())
                .build();
          }

          @Override
          protected ListenableFuture<?> handleAddMediaItems(int index, List<MediaItem> mediaItems) {
            controllerInfoFromPlayerMethod.set(
                sessionReference.get().getControllerForCurrentRequest());
            eventHandled.countDown();
            return Futures.immediateVoidFuture();
          }
        };
    Context context = ApplicationProvider.getApplicationContext();
    MediaSession session =
        new MediaSession.Builder(context, player)
            .setId("test")
            .setCallback(
                new MediaSession.Callback() {
                  @Override
                  public ListenableFuture<List<MediaItem>> onAddMediaItems(
                      MediaSession mediaSession,
                      MediaSession.ControllerInfo controller,
                      List<MediaItem> mediaItems) {
                    // Resolve media items asynchronously.
                    return Util.postOrRunWithCompletion(
                        threadTestRule.getHandler(), () -> {}, mediaItems);
                  }
                })
            .build();
    sessionReference.set(session);
    Bundle controllerHints = new Bundle();
    controllerHints.putString("key", "value");
    MediaController controller =
        new MediaController.Builder(context, session.getToken())
            .setConnectionHints(controllerHints)
            .buildAsync()
            .get();

    MainLooperTestRule.runOnMainSync(() -> controller.addMediaItem(MediaItem.fromUri("test://")));
    eventHandled.await();
    session.release();

    assertThat(controllerInfoFromPlayerMethod.get().getConnectionHints().getString("key"))
        .isEqualTo("value");
  }

  @Test
  public void
      getControllerForCurrentRequest_withMediaControllerCompatAndSimplePlayerMethod_returnsControllerFromPlayerMethod()
          throws Exception {
    AtomicReference<MediaSession.ControllerInfo> controllerInfoFromPlayerMethod =
        new AtomicReference<>();
    AtomicReference<MediaSession> sessionReference = new AtomicReference<>();
    CountDownLatch eventHandled = new CountDownLatch(1);
    Player player =
        new SimpleBasePlayer(Looper.getMainLooper()) {
          @Override
          protected State getState() {
            return new State.Builder()
                .setAvailableCommands(new Commands.Builder().add(Player.COMMAND_PLAY_PAUSE).build())
                .build();
          }

          @Override
          protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
            controllerInfoFromPlayerMethod.set(
                sessionReference.get().getControllerForCurrentRequest());
            eventHandled.countDown();
            return Futures.immediateVoidFuture();
          }
        };
    Context context = ApplicationProvider.getApplicationContext();
    MediaSession session = new MediaSession.Builder(context, player).setId("test").build();
    sessionReference.set(session);
    MediaControllerCompat controller =
        new MediaControllerCompat(
            context, MediaSessionCompat.Token.fromToken(session.getPlatformToken()));

    controller.getTransportControls().play();
    eventHandled.await();
    session.release();

    assertThat(controllerInfoFromPlayerMethod.get().getInterfaceVersion()).isEqualTo(0);
  }

  @Test
  public void
      getControllerForCurrentRequest_withMediaControllerCompatAndAsyncPlayFromMethod_returnsControllerFromPlayerMethod()
          throws Exception {
    AtomicReference<MediaSession.ControllerInfo> controllerInfoFromPlayerMethod =
        new AtomicReference<>();
    AtomicReference<MediaSession> sessionReference = new AtomicReference<>();
    CountDownLatch eventHandled = new CountDownLatch(1);
    Player player =
        new SimpleBasePlayer(Looper.getMainLooper()) {
          @Override
          protected State getState() {
            return new State.Builder()
                .setAvailableCommands(
                    new Commands.Builder().add(Player.COMMAND_SET_MEDIA_ITEM).build())
                .build();
          }

          @Override
          protected ListenableFuture<?> handleSetMediaItems(
              List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
            controllerInfoFromPlayerMethod.set(
                sessionReference.get().getControllerForCurrentRequest());
            eventHandled.countDown();
            return Futures.immediateVoidFuture();
          }
        };
    Context context = ApplicationProvider.getApplicationContext();
    MediaSession session =
        new MediaSession.Builder(context, player)
            .setId("test")
            .setCallback(
                new MediaSession.Callback() {
                  @Override
                  public ListenableFuture<List<MediaItem>> onAddMediaItems(
                      MediaSession mediaSession,
                      MediaSession.ControllerInfo controller,
                      List<MediaItem> mediaItems) {
                    // Resolve media items asynchronously.
                    return Util.postOrRunWithCompletion(
                        threadTestRule.getHandler(), () -> {}, mediaItems);
                  }
                })
            .build();
    sessionReference.set(session);
    MediaControllerCompat controller =
        new MediaControllerCompat(
            context, MediaSessionCompat.Token.fromToken(session.getPlatformToken()));

    controller.getTransportControls().playFromUri(Uri.parse("test://"), Bundle.EMPTY);
    eventHandled.await();
    session.release();

    assertThat(controllerInfoFromPlayerMethod.get().getInterfaceVersion()).isEqualTo(0);
  }

  @Test
  public void
      getControllerForCurrentRequest_withMediaControllerCompatAndAsyncAddToQueueMethod_returnsControllerFromPlayerMethod()
          throws Exception {
    AtomicReference<MediaSession.ControllerInfo> controllerInfoFromPlayerMethod =
        new AtomicReference<>();
    AtomicReference<MediaSession> sessionReference = new AtomicReference<>();
    CountDownLatch eventHandled = new CountDownLatch(1);
    Player player =
        new SimpleBasePlayer(Looper.getMainLooper()) {
          @Override
          protected State getState() {
            return new State.Builder()
                .setAvailableCommands(
                    new Commands.Builder().add(Player.COMMAND_CHANGE_MEDIA_ITEMS).build())
                .build();
          }

          @Override
          protected ListenableFuture<?> handleAddMediaItems(int index, List<MediaItem> mediaItems) {
            controllerInfoFromPlayerMethod.set(
                sessionReference.get().getControllerForCurrentRequest());
            eventHandled.countDown();
            return Futures.immediateVoidFuture();
          }
        };
    Context context = ApplicationProvider.getApplicationContext();
    MediaSession session =
        new MediaSession.Builder(context, player)
            .setId("test")
            .setCallback(
                new MediaSession.Callback() {
                  @Override
                  public ListenableFuture<List<MediaItem>> onAddMediaItems(
                      MediaSession mediaSession,
                      MediaSession.ControllerInfo controller,
                      List<MediaItem> mediaItems) {
                    // Resolve media items asynchronously.
                    return Util.postOrRunWithCompletion(
                        threadTestRule.getHandler(), () -> {}, mediaItems);
                  }
                })
            .build();
    sessionReference.set(session);

    MainLooperTestRule.runOnMainSync(
        () -> {
          MediaControllerCompat controller =
              new MediaControllerCompat(
                  context, MediaSessionCompat.Token.fromToken(session.getPlatformToken()));
          controller.addQueueItem(new MediaDescriptionCompat.Builder().setMediaId("id").build());
        });
    eventHandled.await();
    session.release();

    assertThat(controllerInfoFromPlayerMethod.get().getInterfaceVersion()).isEqualTo(0);
  }
}
