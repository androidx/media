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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.listenTo
import androidx.media3.common.util.UnstableApi
import com.google.common.base.Preconditions.checkState

/**
 * Remembers the value of [SeekBackButtonState] created based on the passed [Player] and launch a
 * coroutine to listen to [Player's][Player] changes. If the [Player] instance changes between
 * compositions, produce and remember a new value.
 */
@UnstableApi
@Composable
fun rememberSeekBackButtonState(player: Player): SeekBackButtonState {
  val seekBackButtonState = remember(player) { SeekBackButtonState(player) }
  LaunchedEffect(player) { seekBackButtonState.observe() }
  return seekBackButtonState
}

/**
 * State that holds all interactions to correctly deal with a UI component representing a seek back
 * button.
 *
 * @property[isEnabled] determined by `isCommandAvailable(Player.COMMAND_SEEK_BACK)`
 * @property[seekBackAmountMs] determined by [Player's][Player] `seekBackIncrement`.
 */
@UnstableApi
class SeekBackButtonState(private val player: Player) {
  var isEnabled by mutableStateOf(isSeekBackEnabled(player))
    private set

  var seekBackAmountMs by mutableLongStateOf(player.seekBackIncrement)
    private set

  /**
   * Handles the interaction with the SeekBackButton by seeking back in the current
   * [androidx.media3.common.MediaItem] by [seekBackAmountMs] milliseconds.
   *
   * This method must only be programmatically called if the [state is enabled][isEnabled]. However,
   * it can be freely provided into containers that take care of skipping the [onClick] if a
   * particular UI node is not enabled (see Compose Clickable Modifier).
   *
   * @see [Player.seekBack]
   * @see [Player.COMMAND_SEEK_BACK]
   */
  fun onClick() {
    checkState(isSeekBackEnabled(player), "COMMAND_SEEK_BACK is not available.")
    player.seekBack()
  }

  /**
   * Subscribes to updates from [Player.Events] and listens to
   * * [Player.EVENT_AVAILABLE_COMMANDS_CHANGED] in order to determine whether the button should be
   *   enabled, i.e. respond to user input.
   * * [Player.EVENT_SEEK_BACK_INCREMENT_CHANGED] to get the newest seek back increment.
   */
  suspend fun observe(): Nothing {
    isEnabled = isSeekBackEnabled(player)
    seekBackAmountMs = player.seekBackIncrement
    player.listenTo(
      Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
      Player.EVENT_SEEK_BACK_INCREMENT_CHANGED,
    ) {
      isEnabled = isSeekBackEnabled(this)
      seekBackAmountMs = seekBackIncrement
    }
  }

  private fun isSeekBackEnabled(player: Player) =
    player.isCommandAvailable(Player.COMMAND_SEEK_BACK)
}
