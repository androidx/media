/*
 * Copyright 2025 The Android Open Source Project
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.listen
import androidx.media3.common.util.UnstableApi

/**
 * Remembers the value of [MuteButtonState] created based on the passed [Player] and launch a
 * coroutine to listen to [Player's][Player] changes. If the [Player] instance changes between
 * compositions, produce and remember a new value.
 */
@UnstableApi
@Composable
fun rememberMuteButtonState(player: Player): MuteButtonState {
  val muteButtonState = remember(player) { MuteButtonState(player) }
  LaunchedEffect(player) { muteButtonState.observe() }
  return muteButtonState
}

/**
 * State that holds all interactions to correctly deal with a UI component representing a Mute
 * button.
 *
 * @property[isEnabled] determined by `isCommandAvailable(Player.COMMAND_SET_VOLUME)`
 * @property[showMuted] determined by [Player]'s volume being 0.0f
 */
@UnstableApi
class MuteButtonState(private val player: Player) {
  var isEnabled by mutableStateOf(isMutingEnabled(player))
    private set

  var showMuted by mutableStateOf(isMuted(player))
    private set

  /**
   * Handles the interaction with the Mute button according to the current state of the [Player].
   * Toggled between a muted state (volume of the Player is 0) and non-muted. Does not influence the
   * volume of the device.
   *
   * @see [Player.mute]
   * @see [Player.unmute]
   */
  fun onClick() {
    if (player.volume == 0f) player.unmute() else player.mute()
  }

  /**
   * Subscribes to updates from [Player.Events] and listens to
   * * [Player.EVENT_VOLUME_CHANGED] in order to determine whether a mute button should show a mute
   *   or unmuted icon.
   * * [Player.EVENT_AVAILABLE_COMMANDS_CHANGED] in order to determine whether the button should be
   *   enabled, i.e. respond to user input.
   */
  suspend fun observe(): Nothing {
    showMuted = isMuted(player)
    isEnabled = isMutingEnabled(player)
    player.listen { events ->
      if (
        events.containsAny(Player.EVENT_VOLUME_CHANGED, Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
      ) {
        showMuted = isMuted(this)
        isEnabled = isMutingEnabled(this)
      }
    }
  }

  private fun isMuted(player: Player) = getVolumeWithCommandCheck(player) == 0f

  private fun getVolumeWithCommandCheck(player: Player): Float {
    return if (player.isCommandAvailable(Player.COMMAND_GET_VOLUME)) player.volume else 1f
  }

  private fun isMutingEnabled(player: Player) =
    player.isCommandAvailable(Player.COMMAND_GET_VOLUME) &&
      player.isCommandAvailable(Player.COMMAND_SET_VOLUME)
}
