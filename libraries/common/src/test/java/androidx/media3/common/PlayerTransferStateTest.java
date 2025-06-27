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
package androidx.media3.common;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;

import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link PlayerTransferState}. */
@RunWith(AndroidJUnit4.class)
public class PlayerTransferStateTest {

  @Test
  public void populateFrom_simpleBasePlayer_createsCorrectState() {
    SimpleBasePlayer player = new StateHolderPlayer();
    player.setPlayWhenReady(true);
    player.setRepeatMode(Player.REPEAT_MODE_ONE);
    player.setShuffleModeEnabled(true);
    MediaItem mediaItem1 = new MediaItem.Builder().setMediaId("id1").build();
    MediaItem mediaItem2 = new MediaItem.Builder().setMediaId("id2").build();
    player.setMediaItems(ImmutableList.of(mediaItem1, mediaItem2));
    player.seekTo(/* mediaItemIndex= */ 1, /* positionMs= */ 5000);
    player.setPlaybackParameters(new PlaybackParameters(/* speed= */ 1.5f, /* pitch= */ 0.8f));

    PlayerTransferState state = PlayerTransferState.fromPlayer(player);

    assertThat(state.getPlayWhenReady()).isTrue();
    assertThat(state.getRepeatMode()).isEqualTo(Player.REPEAT_MODE_ONE);
    assertThat(state.getShuffleModeEnabled()).isTrue();
    assertThat(state.getCurrentMediaItemIndex()).isEqualTo(1);
    assertThat(state.getCurrentPosition()).isEqualTo(5000);
    assertThat(state.getMediaItems()).containsExactly(mediaItem1, mediaItem2).inOrder();
    assertThat(state.getPlaybackParameters().speed).isEqualTo(1.5f);
    assertThat(state.getPlaybackParameters().pitch).isEqualTo(0.8f);
  }

  @Test
  public void setToPlayer_simpleBasePlayer_appliesCorrectState() {
    MediaItem mediaItem1 = new MediaItem.Builder().setMediaId("id1").build();
    MediaItem mediaItem2 = new MediaItem.Builder().setMediaId("id2").build();
    PlayerTransferState state =
        new PlayerTransferState.Builder()
            .setPlayWhenReady(false)
            .setRepeatMode(Player.REPEAT_MODE_ALL)
            .setShuffleModeEnabled(false)
            .setCurrentMediaItemIndex(0)
            .setCurrentPosition(1234)
            .setMediaItems(ImmutableList.of(mediaItem1, mediaItem2))
            .setPlaybackParameters(new PlaybackParameters(/* speed= */ 0.5f, /* pitch= */ 1.2f))
            .build();
    SimpleBasePlayer player = new StateHolderPlayer();
    // Set some initial state on the player to ensure it's overwritten.
    player.setPlayWhenReady(true);
    player.setRepeatMode(Player.REPEAT_MODE_OFF);
    player.setShuffleModeEnabled(true);
    player.seekTo(/* mediaItemIndex= */ 0, /* positionMs= */ 100);
    player.setPlaybackParameters(PlaybackParameters.DEFAULT);
    player.setMediaItems(ImmutableList.of(new MediaItem.Builder().setMediaId("id3").build()));

    state.setToPlayer(player);

    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(player.getRepeatMode()).isEqualTo(Player.REPEAT_MODE_ALL);
    assertThat(player.getShuffleModeEnabled()).isFalse();
    assertThat(player.getCurrentMediaItemIndex()).isEqualTo(0);
    assertThat(player.getCurrentPosition()).isEqualTo(1234);
    assertThat(player.getMediaItemCount()).isEqualTo(2);
    assertThat(player.getMediaItemAt(0)).isEqualTo(mediaItem1);
    assertThat(player.getMediaItemAt(1)).isEqualTo(mediaItem2);
    assertThat(player.getPlaybackParameters().speed).isEqualTo(0.5f);
    assertThat(player.getPlaybackParameters().pitch).isEqualTo(1.2f);
  }

  @Test
  public void buildUpon_createsIdenticalCopy() {
    MediaItem mediaItem1 = new MediaItem.Builder().setMediaId("original1").build();
    MediaItem mediaItem2 = new MediaItem.Builder().setMediaId("original2").build();
    PlaybackParameters originalPlaybackParameters = new PlaybackParameters(1.1f, 0.9f);
    PlayerTransferState originalState =
        new PlayerTransferState.Builder()
            .setPlayWhenReady(true)
            .setRepeatMode(Player.REPEAT_MODE_ONE)
            .setShuffleModeEnabled(true)
            .setCurrentMediaItemIndex(0)
            .setCurrentPosition(1000)
            .setMediaItems(ImmutableList.of(mediaItem1, mediaItem2))
            .setPlaybackParameters(originalPlaybackParameters)
            .build();

    PlayerTransferState copiedState = originalState.buildUpon().build();

    assertThat(copiedState.getPlayWhenReady()).isEqualTo(originalState.getPlayWhenReady());
    assertThat(copiedState.getRepeatMode()).isEqualTo(originalState.getRepeatMode());
    assertThat(copiedState.getShuffleModeEnabled())
        .isEqualTo(originalState.getShuffleModeEnabled());
    assertThat(copiedState.getCurrentMediaItemIndex())
        .isEqualTo(originalState.getCurrentMediaItemIndex());
    assertThat(copiedState.getCurrentPosition()).isEqualTo(originalState.getCurrentPosition());
    assertThat(copiedState.getMediaItems()).isEqualTo(originalState.getMediaItems());
    assertThat(copiedState.getPlaybackParameters())
        .isEqualTo(originalState.getPlaybackParameters());
  }

  @Test
  public void buildUpon_allowsModifications() {
    MediaItem mediaItem1 = new MediaItem.Builder().setMediaId("original1").build();
    MediaItem mediaItem2 = new MediaItem.Builder().setMediaId("original2").build();
    PlaybackParameters originalPlaybackParameters = new PlaybackParameters(1.1f, 0.9f);
    PlayerTransferState originalState =
        new PlayerTransferState.Builder()
            .setPlayWhenReady(true)
            .setRepeatMode(Player.REPEAT_MODE_ONE)
            .setShuffleModeEnabled(true)
            .setCurrentMediaItemIndex(0)
            .setCurrentPosition(1000)
            .setMediaItems(ImmutableList.of(mediaItem1, mediaItem2))
            .setPlaybackParameters(originalPlaybackParameters)
            .build();
    MediaItem newMediaItem = new MediaItem.Builder().setMediaId("new").build();
    PlaybackParameters newPlaybackParameters = new PlaybackParameters(2.0f);

    PlayerTransferState modifiedState =
        originalState
            .buildUpon()
            .setCurrentMediaItemIndex(1)
            .setCurrentPosition(500)
            .setMediaItems(ImmutableList.of(newMediaItem))
            .setPlaybackParameters(newPlaybackParameters)
            .build();

    // The following are unchanged.
    assertThat(modifiedState.getPlayWhenReady()).isTrue();
    assertThat(modifiedState.getRepeatMode()).isEqualTo(Player.REPEAT_MODE_ONE);
    assertThat(modifiedState.getShuffleModeEnabled()).isTrue();
    // The following are modified.
    assertThat(modifiedState.getCurrentMediaItemIndex()).isEqualTo(1); // Modified
    assertThat(modifiedState.getCurrentPosition()).isEqualTo(500); // Modified
    assertThat(modifiedState.getMediaItems()).containsExactly(newMediaItem); // Modified
    assertThat(modifiedState.getPlaybackParameters()).isEqualTo(newPlaybackParameters); // Modified
  }

  /** A {@link Player} that holds state and supports its modification through setters. */
  private static final class StateHolderPlayer extends SimpleBasePlayer {

    private State state;

    private static final Commands AVAILABLE_COMMANDS =
        new Commands.Builder()
            .addAll(
                COMMAND_PLAY_PAUSE,
                COMMAND_SET_REPEAT_MODE,
                COMMAND_SET_SHUFFLE_MODE,
                COMMAND_SET_SPEED_AND_PITCH,
                COMMAND_GET_CURRENT_MEDIA_ITEM,
                COMMAND_GET_TIMELINE,
                COMMAND_SET_MEDIA_ITEM,
                COMMAND_CHANGE_MEDIA_ITEMS,
                COMMAND_SEEK_TO_MEDIA_ITEM)
            .build();

    private StateHolderPlayer() {
      super(Looper.getMainLooper());
      state = new State.Builder().setAvailableCommands(AVAILABLE_COMMANDS).build();
    }

    @Override
    protected State getState() {
      return state;
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
      state =
          state
              .buildUpon()
              .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
              .build();
      return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetRepeatMode(int repeatMode) {
      state = state.buildUpon().setRepeatMode(repeatMode).build();
      return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetShuffleModeEnabled(boolean shuffleModeEnabled) {
      state = state.buildUpon().setShuffleModeEnabled(shuffleModeEnabled).build();
      return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetPlaybackParameters(
        PlaybackParameters playbackParameters) {
      state = state.buildUpon().setPlaybackParameters(playbackParameters).build();

      return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetMediaItems(
        List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
      ImmutableList<MediaItemData> mediaItemDatas =
          mediaItems.stream()
              .map(it -> new MediaItemData.Builder(/* uid= */ it).setMediaItem(it).build())
              .collect(toImmutableList());
      state =
          state
              .buildUpon()
              .setPlaylist(mediaItemDatas)
              .setContentPositionMs(startPositionMs)
              .setCurrentMediaItemIndex(startIndex)
              .build();
      return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
      state =
          state
              .buildUpon()
              .setContentPositionMs(positionMs)
              .setCurrentMediaItemIndex(mediaItemIndex)
              .build();
      return Futures.immediateVoidFuture();
    }
  }
}
