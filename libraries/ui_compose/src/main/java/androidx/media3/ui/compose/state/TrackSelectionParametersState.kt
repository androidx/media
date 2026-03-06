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
 * launch a coroutine to listen to [Player's][Player] changes. If the [Player] instance changes between
 * compositions, produce and remember a new value.
 */
@UnstableApi
@Composable
fun rememberTrackSelectionParametersState(player: Player?): TrackSelectionParametersState {
    val trackSelectionState = remember(player) { TrackSelectionParametersState(player) }
    LaunchedEffect(player) { trackSelectionState.observe() }
    return trackSelectionState
}

/**
 * State that holds the current [Tracks] and [TrackSelectionParameters] of a [Player].
 *
 * This state is suitable for advanced use cases where direct access to the player's track
 * information and parameters is needed, without any additional UI abstraction.
 *
 * @property[tracks] The current [Tracks] available in the player.
 * @property[trackSelectionParameters] The current [TrackSelectionParameters] acting on the player.
 */
@UnstableApi
class TrackSelectionParametersState(private val player: Player?) {

    var tracks: Tracks by mutableStateOf(player?.currentTracks ?: Tracks.EMPTY)
        private set

    var trackSelectionParameters: TrackSelectionParameters by mutableStateOf(
        player?.trackSelectionParameters ?: TrackSelectionParameters.DEFAULT
    )
        private set

    private val playerStateObserver = player?.observeState(
        Player.EVENT_TRACKS_CHANGED,
        Player.EVENT_TRACK_SELECTION_PARAMETERS_CHANGED,
    ) {
        tracks = player.currentTracks
        trackSelectionParameters = player.trackSelectionParameters
    }

    /**
     * Applies new track selection parameters to the player.
     */
    fun updateTrackSelectionParameters(params: TrackSelectionParameters) {
        player?.trackSelectionParameters = params
    }

    /**
     * Subscribes to updates from [Player.Events] and listens to
     * [Player.EVENT_TRACKS_CHANGED] and [Player.EVENT_TRACK_SELECTION_PARAMETERS_CHANGED]
     * in order to determine whether the state properties should be updated.
     */
    suspend fun observe(): Nothing? = playerStateObserver?.observe()
}
