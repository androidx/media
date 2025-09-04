/*
 * Copyright 2024 The Android Open Source Project
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
import androidx.media3.common.listenTo
import androidx.media3.common.util.UnstableApi
import com.google.common.base.Preconditions.checkState

/**
 * Remember the value of [ShuffleButtonState] created based on the passed [Player] and launch a
 * coroutine to listen to [Player's][Player] changes. If the [Player] instance changes between
 * compositions, produce and remember a new value.
 */
@UnstableApi
@Composable
fun rememberShuffleButtonState(player: Player): ShuffleButtonState {
  val shuffleButtonState = remember(player) { ShuffleButtonState(player) }
  LaunchedEffect(player) { shuffleButtonState.observe() }
  return shuffleButtonState
}

/**
 * State that holds all interactions to correctly deal with a UI component representing a Shuffle
 * On/Off button.
 *
 * @property[isEnabled] determined by `isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)`
 * @property[shuffleOn] determined by [Player's][Player] `shuffleModeEnabled`
 */
@UnstableApi
class ShuffleButtonState(private val player: Player) {
  var isEnabled by mutableStateOf(isShuffleEnabled(player))
    private set

  var shuffleOn by mutableStateOf(player.shuffleModeEnabled)
    private set

  /**
   * Handles the interaction with the ShuffleButton according to the current state of the [Player].
   *
   * This method must only be programmatically called if the [state is enabled][isEnabled]. However,
   * it can be freely provided into containers that take care of skipping the [onClick] if a
   * particular UI node is not enabled (see Compose Clickable Modifier).
   *
   * @see [Player.setShuffleModeEnabled]
   * @see [Player.COMMAND_SET_SHUFFLE_MODE]
   */
  fun onClick() {
    checkState(isShuffleEnabled(player), "COMMAND_SET_SHUFFLE_MODE is not available.")
    player.shuffleModeEnabled = !player.shuffleModeEnabled
  }

  /**
   * Subscribes to updates from [Player.Events] and listens to
   * * [Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED] in order to determine the latest
   *   [Player.getShuffleModeEnabled].
   * * [Player.EVENT_AVAILABLE_COMMANDS_CHANGED] in order to determine whether the button should be
   *   enabled, i.e. respond to user input.
   */
  suspend fun observe(): Nothing {
    shuffleOn = player.shuffleModeEnabled
    isEnabled = isShuffleEnabled(player)
    player.listenTo(
      Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
      Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
    ) {
      shuffleOn = shuffleModeEnabled
      isEnabled = isShuffleEnabled(player)
    }
  }

  private fun isShuffleEnabled(player: Player) =
    player.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE)
}
