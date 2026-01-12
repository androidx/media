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

package androidx.media3.ui.compose.buttons

import androidx.compose.runtime.Composable
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.PlaybackSpeedState
import androidx.media3.ui.compose.state.rememberPlaybackSpeedState

/**
 * A state container for building custom UI for playback speed control.
 *
 * This composable manages the state of a playback speed control, including the current speed and
 * whether the control is enabled. The UI is provided by the [content] lambda, which has access to
 * the [PlaybackSpeedState].
 *
 * @param player The [Player] to control.
 * @param content A composable that receives the [PlaybackSpeedState] and defines the UI.
 */
@UnstableApi
@Composable
fun PlaybackSpeedControl(player: Player?, content: @Composable PlaybackSpeedState.() -> Unit) {
  rememberPlaybackSpeedState(player).content()
}
