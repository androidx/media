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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Remembers the value of [ErrorState] created based on the passed [Player] and launches a coroutine
 * to listen to [Player's][Player] error changes.
 */
@UnstableApi
@Composable
fun rememberErrorState(player: Player?): ErrorState {
  val errorState = remember(player) { ErrorState(player) }
  LaunchedEffect(player) { errorState.observe() }
  return errorState
}

/**
 * State holder that tracks the [Player.getPlayerError] by listening to [Player.EVENT_PLAYER_ERROR]
 * and [Player.EVENT_PLAYBACK_STATE_CHANGED].
 */
@UnstableApi
class ErrorState(private val player: Player?) {
  var error: PlaybackException? by mutableStateOf(player?.playerError)
    private set

  private val playerStateObserver: PlayerStateObserver? =
    player?.observeState(Player.EVENT_PLAYER_ERROR, Player.EVENT_PLAYBACK_STATE_CHANGED) {
      error = player.playerError
    }

  /** Subscribes to updates from [Player.Events] to update the [error] state. */
  suspend fun observe() = playerStateObserver?.observe()
}
