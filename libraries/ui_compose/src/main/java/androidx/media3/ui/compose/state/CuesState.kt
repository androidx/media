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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi

/**
 * Remembers the value of [CuesState] created based on the passed [Player] and launches a coroutine
 * to listen to [Player's][Player] cue changes. If the [Player] instance changes between
 * compositions, produces and remembers a new value.
 */
@UnstableApi
@Composable
fun rememberCuesState(player: Player?): CuesState {
  val cuesState = remember(player) { CuesState(player) }
  LaunchedEffect(player) { cuesState.observe() }
  return cuesState
}

/**
 * State holder that converts [Player.Listener.onCues] events into a State of [List<Cue>] and tracks
 * the availability of text commands.
 *
 * @param player The [Player] whose cues are observed.
 * @property cues The current list of cues to be displayed. Defaults to an empty list and is also
 *   empty when [Player.COMMAND_GET_TEXT] is not available.
 */
@UnstableApi
class CuesState(private val player: Player?) {
  var cues: List<Cue> by mutableStateOf(emptyList())
    private set

  private val playerStateObserver: PlayerStateObserver? =
    player?.observeState(Player.EVENT_CUES, Player.EVENT_AVAILABLE_COMMANDS_CHANGED) {
      val isEnabled = player.isCommandAvailable(Player.COMMAND_GET_TEXT)
      cues = if (isEnabled) player.currentCues.cues else emptyList()
    }

  /**
   * Subscribes to updates from [Player.Events] and listens to [Player.EVENT_CUES] and
   * [Player.EVENT_AVAILABLE_COMMANDS_CHANGED] to update the [cues] state.
   */
  suspend fun observe() = playerStateObserver?.observe()
}
