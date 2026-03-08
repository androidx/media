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

package androidx.media3.ui.compose.buttons

import androidx.compose.runtime.Composable
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.TrackSelectionParametersState
import androidx.media3.ui.compose.state.rememberTrackSelectionParametersState

/**
 * A state container for track selection parameters.
 *
 * This composable exposes the [TrackSelectionParametersState] to its [content] lambda,
 * allowing developers to build custom track selection UIs using player tracks and parameters.
 *
 * @param player The [Player] to control.
 * @param content The composable content to be displayed, which has access to the [TrackSelectionParametersState].
 */
@UnstableApi
@Composable
fun TrackSelectionParametersButton(
    player: Player?,
    content: @Composable TrackSelectionParametersState.() -> Unit,
) {
    rememberTrackSelectionParametersState(player).content()
}
