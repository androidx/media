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
import androidx.media3.ui.compose.state.RepeatButtonState
import androidx.media3.ui.compose.state.rememberRepeatButtonState

@UnstableApi
@Composable
fun RepeatButton(
  player: Player,
  toggleModeSequence: List<@Player.RepeatMode Int> =
    listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL),
  content: @Composable RepeatButtonState.() -> Unit,
) {
  rememberRepeatButtonState(player, toggleModeSequence).content()
}
