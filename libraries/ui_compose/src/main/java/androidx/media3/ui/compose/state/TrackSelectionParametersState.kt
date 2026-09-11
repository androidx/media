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
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

/**
 * Remembers the value of [TrackSelectionParametersState] created based on the passed [Player] and
 * launches a coroutine to listen to [Player] changes. If the [Player] instance changes between
 * compositions, a new value is produced and remembered.
 *
 * @param player The [Player] to observe, or null.
 * @return A [TrackSelectionParametersState] instance holding the current state.
 * @see [TrackSelectionParametersState]
 */
@UnstableApi
@Composable
fun rememberTrackSelectionParametersState(player: Player?): TrackSelectionParametersState {
  val trackSelectionParametersState = remember(player) { TrackSelectionParametersState(player) }
  LaunchedEffect(player) { trackSelectionParametersState.observe() }
  return trackSelectionParametersState
}

/**
 * A state holder that exposes the current [Tracks] and [TrackSelectionParameters] of a [Player] as
 * Compose state.
 *
 * This state is suitable for advanced use cases where direct access to the player's track
 * information and parameters is needed, without any additional UI abstraction.
 *
 * @property tracks The current [Tracks] available in the player.
 * @property trackSelectionParameters The current [TrackSelectionParameters] acting on the player.
 * @property canSetTrackSelectionParameters Whether [Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS]
 *   is available on the player.
 */
@UnstableApi
class TrackSelectionParametersState(private val player: Player?) {

  var tracks: Tracks by mutableStateOf(player.getTracksIfCommandAvailable())
    private set

  var trackSelectionParameters: TrackSelectionParameters by
    mutableStateOf(player?.trackSelectionParameters ?: TrackSelectionParameters.DEFAULT)
    private set

  var canSetTrackSelectionParameters: Boolean by
    mutableStateOf(
      player?.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS) == true
    )
    private set

  private val playerStateObserver: PlayerStateObserver? =
    player?.observeState(
      Player.EVENT_TRACKS_CHANGED,
      Player.EVENT_TRACK_SELECTION_PARAMETERS_CHANGED,
      Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
    ) {
      tracks = it.getTracksIfCommandAvailable()
      trackSelectionParameters = it.trackSelectionParameters
      canSetTrackSelectionParameters =
        it.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
    }

  /**
   * Applies new track selection parameters to the player.
   *
   * @param params The new [TrackSelectionParameters] to apply.
   * @see [Player.getTrackSelectionParameters]
   */
  fun updateTrackSelectionParameters(params: TrackSelectionParameters) {
    player
      ?.takeIf { it.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS) }
      ?.trackSelectionParameters = params
  }

  /**
   * Subscribes to updates from [Player.Events] and listens to [Player.EVENT_TRACKS_CHANGED],
   * [Player.EVENT_TRACK_SELECTION_PARAMETERS_CHANGED], and
   * [Player.EVENT_AVAILABLE_COMMANDS_CHANGED] to update the state properties.
   *
   * This is a suspending function that runs indefinitely until cancelled.
   */
  suspend fun observe() = playerStateObserver?.observe()

  private fun Player?.getTracksIfCommandAvailable(): Tracks =
    this?.takeIf { it.isCommandAvailable(Player.COMMAND_GET_TRACKS) }?.currentTracks ?: Tracks.EMPTY
}
