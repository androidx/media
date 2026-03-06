package androidx.media3.ui.compose.material3.buttons.trackselection

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.R
import androidx.media3.ui.compose.state.OnOffState
import androidx.media3.ui.compose.state.TrackSelectionState


@UnstableApi
@Composable
fun VideoResolutionDialog(
    state: TrackSelectionState,
    onDismissRequest: () -> Unit,
    onOptionSelected: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.track_selection_title_video)) },
        text = {
            LazyColumn {
                items(state.selectionOptions.size) { index ->
                    val option = state.selectionOptions[index]
                    val trackLabel = getVideoTrackLabel(option.format)

                    TrackSelectionOption(
                        text = trackLabel,
                        selected = state.selectedOption == option,
                        onClick = {
                            state.selectOption(option)
                            onOptionSelected()
                        }
                    )
                }

                item {
                    TrackSelectionOption(
                        text = stringResource(R.string.track_selection_auto),
                        selected = state.selectedOption == null && state.onOffState == OnOffState.ON_DEFAULT,
                        onClick = {
                            state.selectOption(null)
                            onOptionSelected()
                        }
                    )
                }

                item {
                    TrackSelectionOption(
                        text = stringResource(R.string.track_selection_off),
                        selected = state.onOffState == OnOffState.OFF,
                        onClick = {
                            state.setOnOff(OnOffState.OFF)
                            onOptionSelected()
                        }
                    )
                }
            }
        },
        confirmButton = {}
    )
}
