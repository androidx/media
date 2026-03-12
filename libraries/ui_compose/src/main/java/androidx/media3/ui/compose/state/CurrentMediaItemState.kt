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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Remembers the value of [CurrentMediaItemState] created based on the passed [Player] and launch a
 * coroutine to listen to [Player's][Player] changes. If the [Player] instance changes between
 * compositions, produce and remember a new value.
 */
@UnstableApi
@Composable
fun rememberCurrentMediaItemState(player: Player?): CurrentMediaItemState {
  val currentMediaItemState = remember(player) { CurrentMediaItemState(player) }
  LaunchedEffect(player) { currentMediaItemState.observe() }
  return currentMediaItemState
}

/**
 * A state holder that observes a [Player] and provides reactive updates for properties of the
 * currently playing media item.
 *
 * This state includes both static configuration from the [MediaItem] and dynamic information
 * obtained from the player's [androidx.media3.common.Timeline] and [MediaMetadata].
 *
 * Use [rememberCurrentMediaItemState] to create and manage the lifecycle of this state within a
 * Composable.
 *
 * @property mediaItem The currently playing [MediaItem], or `null` if no item is being played or
 *   the [Player.COMMAND_GET_CURRENT_MEDIA_ITEM] is not available.
 * @property mediaMetadata The combined [MediaMetadata] for the current item, including both static
 *   metadata from the [MediaItem] and dynamic updates from the stream. Returns
 *   [MediaMetadata.EMPTY] if metadata is not available.
 * @property isLive Whether the currently playing item is a live stream.
 * @property isAd Whether the player is currently playing an ad. When `true`, properties like
 *   [durationMs] will reflect the duration of the current ad rather than the main content.
 * @property durationMs The duration of the currently playing item (content or ad) in milliseconds,
 *   or [C.TIME_UNSET] if the duration is unknown or the command is not available.
 */
@UnstableApi
class CurrentMediaItemState(private val player: Player?) {
  var mediaItem: MediaItem? by mutableStateOf(null)
    private set

  var mediaMetadata: MediaMetadata by mutableStateOf(MediaMetadata.EMPTY)
    private set

  var isLive: Boolean by mutableStateOf(false)
    private set

  var isAd: Boolean by mutableStateOf(false)
    private set

  var durationMs: Long by mutableLongStateOf(C.TIME_UNSET)
    private set

  private val playerStateObserver: PlayerStateObserver? =
    player?.observeState(
      Player.EVENT_POSITION_DISCONTINUITY,
      Player.EVENT_TIMELINE_CHANGED,
      Player.EVENT_MEDIA_METADATA_CHANGED,
      Player.EVENT_METADATA,
      Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
    ) {
      val canGetCurrentMediaItem = player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
      val canGetMetadata = player.isCommandAvailable(Player.COMMAND_GET_METADATA)

      mediaItem = if (canGetCurrentMediaItem) player.currentMediaItem else null
      isLive = if (canGetCurrentMediaItem) player.isCurrentMediaItemLive else false
      isAd = if (canGetCurrentMediaItem) player.isPlayingAd else false
      durationMs = if (canGetCurrentMediaItem) player.duration else C.TIME_UNSET
      mediaMetadata = if (canGetMetadata) player.mediaMetadata else MediaMetadata.EMPTY
    }

  suspend fun observe() {
    playerStateObserver?.observe()
  }
}
