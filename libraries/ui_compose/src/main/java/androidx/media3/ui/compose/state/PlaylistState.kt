/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.ui.compose.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi

/**
 * Remembers the value of [PlaylistState] created based on the passed [Player] and launch a
 * coroutine to listen to [Player's][Player] changes. If the [Player] instance changes between
 * compositions, produce and remember a new value.
 *
 * @param player The [Player] instance to observe.
 * @return A [PlaylistState] instance that updates based on the player's state.
 */
@UnstableApi
@Composable
fun rememberPlaylistState(player: Player?): PlaylistState {
  val playlistState = remember(player) { PlaylistState(player) }
  LaunchedEffect(player) { playlistState.observe() }
  return playlistState
}

/**
 * State that holds information about the current playback playlist.
 *
 * This state does not have a one-on-one relationship with a UI component like a button, icon, or
 * slider. Instead, it logically groups various playlist-related concepts and values that can be
 * displayed on the screen dynamically.
 *
 * @param player The [Player] instance to observe.
 * @property timeline The current [Timeline] of the player. [Timeline.EMPTY] if playlist is empty or
 *   command is not available.
 * @property currentMediaItemIndex The index of the current [MediaItem]. [C.INDEX_UNSET] if playlist
 *   is empty or command is not available.
 * @property mediaItemCount The total number of media items in the playlist. 0 if playlist is empty
 *   or command is not available.
 * @property playlistMetadata The [MediaMetadata] of the entire playlist. [MediaMetadata.EMPTY] if
 *   not set or command is not available.
 */
@UnstableApi
class PlaylistState(private val player: Player?) {
  private var canGetTimeline: Boolean = false
  private var canGetMetadata: Boolean = false

  var timeline: Timeline by mutableStateOf(Timeline.EMPTY)
    private set

  var currentMediaItemIndex by mutableIntStateOf(C.INDEX_UNSET)
    private set

  val mediaItemCount: Int
    get() = if (canGetTimeline) timeline.windowCount else 0

  var playlistMetadata: MediaMetadata by mutableStateOf(MediaMetadata.EMPTY)
    private set

  private val playerStateObserver =
    player?.observeState(
      Player.EVENT_TIMELINE_CHANGED,
      Player.EVENT_POSITION_DISCONTINUITY,
      Player.EVENT_PLAYLIST_METADATA_CHANGED,
      Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
    ) { player ->
      canGetTimeline = player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)
      canGetMetadata = player.isCommandAvailable(Player.COMMAND_GET_METADATA)

      playlistMetadata = if (canGetMetadata) player.playlistMetadata else MediaMetadata.EMPTY
      timeline = if (canGetTimeline) player.currentTimeline else Timeline.EMPTY
      currentMediaItemIndex = if (canGetTimeline) player.currentMediaItemIndex else C.INDEX_UNSET
    }

  /**
   * Returns the [MediaItem] at the given [index] in the playlist.
   *
   * @throws IndexOutOfBoundsException if the index is out of bounds.
   */
  fun getMediaItemAt(index: Int): MediaItem {
    if (!canGetTimeline || index < 0 || index >= timeline.windowCount)
      throw IndexOutOfBoundsException()
    val window = Timeline.Window()
    return timeline.getWindow(index, window).mediaItem
  }

  /**
   * Seeks to the default position of the media item at the given index in the playlist.
   *
   * This method does nothing if the player is null or [Player.COMMAND_SEEK_TO_MEDIA_ITEM] is not
   * available.
   *
   * @param index The index of the media item to seek to.
   */
  fun seekToMediaItem(index: Int) {
    player?.let {
      if (
        it.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM) && index != currentMediaItemIndex
      ) {
        it.seekToDefaultPosition(index)
      }
    }
  }

  /**
   * Subscribes to updates from [Player.Events] and listens to
   * * [Player.EVENT_TIMELINE_CHANGED]
   * * [Player.EVENT_POSITION_DISCONTINUITY]
   * * [Player.EVENT_PLAYLIST_METADATA_CHANGED]
   * * [Player.EVENT_AVAILABLE_COMMANDS_CHANGED]
   */
  suspend fun observe() {
    playerStateObserver?.observe()
  }
}
