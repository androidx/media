package androidx.media3.ui.compose.material3.buttons.trackselection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.R
import androidx.media3.ui.compose.material3.buttons.ClickableIconButton
import androidx.media3.ui.compose.state.OnOffState
import androidx.media3.ui.compose.state.TrackSelectionParametersState
import androidx.media3.ui.compose.state.TrackSelectionState
import androidx.media3.ui.compose.state.rememberTrackSelectionParametersState
import androidx.media3.ui.compose.state.rememberTrackSelectionState

/**
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that opens a settings dialog
 * for track selection (Video Resolution and Audio Language).
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param tint Tint to be applied to the icon.
 */
@UnstableApi
@Composable
fun TrackSelectionDialogButton(
    player: Player?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    var showMainDialog by remember { mutableStateOf(false) }
    var showVideoDialog by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }

    val videoState = rememberTrackSelectionState(player, C.TRACK_TYPE_VIDEO)
    val audioState = rememberTrackSelectionParametersState(player)

    ClickableIconButton(
        modifier = modifier,
        enabled = true,
        icon = painterResource(R.drawable.media3_icon_settings),
        contentDescription = "Settings",
        tint = tint,
        onClick = { showMainDialog = true },
    )

    if (showMainDialog) {
        AlertDialog(
            onDismissRequest = { showMainDialog = false },
            title = { Text(stringResource(R.string.track_selection_settings_title)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    TrackSelectionDialogRow(
                        title = stringResource(R.string.track_selection_quality_title),
                        subtitle = getVideoLabel(videoState),
                        icon = painterResource(R.drawable.media3_icon_settings),
                        onClick = {
                            showMainDialog = false
                            showVideoDialog = true
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    TrackSelectionDialogRow(
                        title = stringResource(R.string.track_selection_audio_title),
                        subtitle = getAudioLabel(audioState),
                        icon = painterResource(R.drawable.media3_icon_play),
                        onClick = {
                            showMainDialog = false
                            showAudioDialog = true
                        }
                    )
                }
            },
            confirmButton = {}
        )
    }

    if (showVideoDialog) {
        VideoResolutionDialog(
            state = videoState,
            onDismissRequest = {
                showVideoDialog = false
                showMainDialog = true
            },
            onOptionSelected = {
                showVideoDialog = false
                showMainDialog = false
            }
        )
    }

    if (showAudioDialog) {
        AudioLanguageDialog(
            state = audioState,
            onDismissRequest = {
                showAudioDialog = false
                showMainDialog = true
            },
            onOptionSelected = {
                showAudioDialog = false
                showMainDialog = false
            }
        )
    }
}

@Composable
private fun TrackSelectionDialogRow(
    title: String,
    subtitle: String,
    icon: Painter,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 16.dp), // Added horizontal padding for standard dialog look
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            modifier = Modifier.width(24.dp).height(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@UnstableApi
@Composable
private fun getVideoLabel(state: TrackSelectionState): String {
    return when (state.onOffState) {
        OnOffState.OFF -> stringResource(R.string.track_selection_off)
        OnOffState.ON_DEFAULT -> if (state.selectedOption == null) stringResource(R.string.track_selection_auto) else getVideoTrackLabel(state.selectedOption!!.format)
        OnOffState.ON_ALWAYS -> getVideoTrackLabel(state.selectedOption!!.format)
    }
}

@UnstableApi
@Composable
private fun getAudioLabel(state: TrackSelectionParametersState): String {
    // Iterate to find selected audio track
    val groups = state.tracks.groups
    for (i in 0 until groups.size) {
        val group = groups[i]
        if (group.type == C.TRACK_TYPE_AUDIO) {
            for (trackIndex in 0 until group.length) {
                if (group.isTrackSelected(trackIndex)) {
                    val format = group.getTrackFormat(trackIndex)
                    return getAudioTrackLabel(format)
                }
            }
        }
    }
    return stringResource(R.string.track_selection_default)
}
