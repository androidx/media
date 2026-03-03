package androidx.media3.ui.compose.material3.buttons.trackselection

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.R
import androidx.media3.ui.compose.state.TrackSelectionParametersState


@UnstableApi
@Composable
fun AudioLanguageDialog(
    state: TrackSelectionParametersState,
    onDismissRequest: () -> Unit,
    onOptionSelected: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.track_selection_title_audio)) },
        text = {
            LazyColumn {
                val groups = state.tracks.groups
                for (i in 0 until groups.size) {
                    val group = groups[i]
                    if (group.type == C.TRACK_TYPE_AUDIO) {
                        items(group.length) { trackIndex ->
                            val format = group.getTrackFormat(trackIndex)
                            val isSelected = group.isTrackSelected(trackIndex)
                            // Basic language display
                            val trackLabel = getAudioTrackLabel(format)

                            TrackSelectionOption(
                                text = trackLabel,
                                selected = isSelected,
                                onClick = {
                                    val override = TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
                                    val newParams = state.trackSelectionParameters
                                        .buildUpon()
                                        .setOverrideForType(override)
                                        .build()
                                    state.updateTrackSelectionParameters(newParams)
                                    onOptionSelected()
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}
