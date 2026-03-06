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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi

@UnstableApi
enum class OnOffState {
    OFF, ON_DEFAULT, ON_ALWAYS
}

@UnstableApi
data class TrackSelectionOption(
    val isSupported: Boolean,
    val isSelected: Boolean,
    val groupIndex: Int,
    val trackIndex: Int,
    val format: Format
)

@UnstableApi
@Composable
fun rememberTrackSelectionState(
    player: Player?, trackType: @C.TrackType Int
): TrackSelectionState {
    val parametersState = rememberTrackSelectionParametersState(player)
    return remember(parametersState, trackType) {
        TrackSelectionState(parametersState, trackType)
    }
}

@UnstableApi
class TrackSelectionState(
    private val parametersState: TrackSelectionParametersState, val trackType: @C.TrackType Int
) {

    /**
     * Whether track selection is possible for the given [trackType].
     *
     * This is true if there is at least one track group of the [trackType] available.
     */
    val isEnabled: Boolean by derivedStateOf {
        parametersState.tracks.groups.any { it.type == trackType }
    }

    /**
     * The current [OnOffState] determining the selection logic for the [trackType].
     *
     * - [OnOffState.OFF]: The track type is explicitly disabled.
     * - [OnOffState.ON_DEFAULT]: The track type is enabled and will use the player's default adaptive selection.
     * - [OnOffState.ON_ALWAYS]: The track type is enabled and a specific track override is applied.
     */
    val onOffState: OnOffState by derivedStateOf {
        val params = parametersState.trackSelectionParameters
        if (params.disabledTrackTypes.contains(trackType)) {
            OnOffState.OFF
        } else {
            // Check if we have an override for this type.
            // We check if any of the overrides apply to a TrackGroup of our trackType.
            val hasOverride = params.overrides.keys.any { group ->
                parametersState.tracks.groups.any { it.type == trackType && it.mediaTrackGroup == group }
            }
            if (hasOverride) OnOffState.ON_ALWAYS else OnOffState.ON_DEFAULT
        }
    }

    /**
     * The currently selected [TrackSelectionOption], or `null` if the selection is automatic.
     *
     * This option is derived from the current [OnOffState] and the active [TrackSelectionOverride].
     */
    val selectedOption: TrackSelectionOption? by derivedStateOf {
        if (onOffState != OnOffState.ON_ALWAYS) {
            null
        } else {
            val params = parametersState.trackSelectionParameters
            val checkGroups = parametersState.tracks.groups
            // Find the override that caused ON_ALWAYS
            val override = params.overrides.entries.firstOrNull { (group, _) ->
                checkGroups.any { it.type == trackType && it.mediaTrackGroup == group }
            }?.value

            if (override != null && override.trackIndices.isNotEmpty()) {
                // Assuming single track selection for this simple state holder
                val selectedTrackIndex = override.trackIndices[0]
                val selectedGroup = override.mediaTrackGroup
                selectionOptions.firstOrNull { option ->
                    checkGroups[option.groupIndex].mediaTrackGroup == selectedGroup &&
                            option.trackIndex == selectedTrackIndex
                }
            } else {
                null
            }
        }
    }

    /**
     * The list of available [TrackSelectionOption]s for the [trackType].
     *
     * This list includes all tracks from all track groups of the [trackType].
     */
    val selectionOptions: List<TrackSelectionOption> by derivedStateOf {
        val list = mutableListOf<TrackSelectionOption>()
        val groups = parametersState.tracks.groups
        for (i in 0 until groups.size) {
            val group = groups[i]
            if (group.type == trackType) {
                for (j in 0 until group.length) {
                    list.add(
                        TrackSelectionOption(
                            isSupported = group.isTrackSupported(j),
                            isSelected = group.isTrackSelected(j),
                            groupIndex = i,
                            trackIndex = j,
                            format = group.getTrackFormat(j)
                        )
                    )
                }
            }
        }
        list
    }

    fun setOnOff(state: OnOffState) {
        val builder = parametersState.trackSelectionParameters.buildUpon()
        when (state) {
            OnOffState.OFF -> builder.setTrackTypeDisabled(trackType, true)
            OnOffState.ON_DEFAULT -> {
                builder.setTrackTypeDisabled(trackType, false)
                builder.clearOverridesOfType(trackType)
            }
            OnOffState.ON_ALWAYS -> {
                builder.setTrackTypeDisabled(trackType, false)
            }
        }
        parametersState.updateTrackSelectionParameters(builder.build())
    }

    fun selectOption(option: TrackSelectionOption?) {
        val builder = parametersState.trackSelectionParameters.buildUpon()
        builder.setTrackTypeDisabled(trackType, false) // Ensure enabled

        if (option == null) {
            // "Auto" -> Clear overrides
            builder.clearOverridesOfType(trackType)
        } else {
            val group = parametersState.tracks.groups[option.groupIndex]
            val override = TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex)
            builder.setOverrideForType(override)
        }
        parametersState.updateTrackSelectionParameters(builder.build())
    }
}
