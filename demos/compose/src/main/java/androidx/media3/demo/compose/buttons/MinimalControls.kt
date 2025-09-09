/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.media3.demo.compose.buttons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.ui.compose.material3.button.NextButton
import androidx.media3.ui.compose.material3.button.PlayPauseButton
import androidx.media3.ui.compose.material3.button.PreviousButton
import androidx.media3.ui.compose.material3.button.SeekBackButton
import androidx.media3.ui.compose.material3.button.SeekForwardButton

/**
 * Minimal playback controls for a [Player].
 *
 * Includes buttons for seeking to a previous/next items, seeking back/forward a couple of seconds,
 * or playing/pausing the playback.
 */
@Composable
internal fun MinimalControls(player: Player, modifier: Modifier = Modifier) {
  val graySemiTransparentBackground = Color.Gray.copy(alpha = 0.1f)
  val modifierForIconButton =
    Modifier.size(40.dp).background(graySemiTransparentBackground, CircleShape)
  Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
    Spacer(Modifier.weight(1f))
    PreviousButton(player, modifierForIconButton)
    Spacer(Modifier.weight(0.3f))
    SeekBackButton(player, modifierForIconButton)
    Spacer(Modifier.weight(0.3f))
    PlayPauseButton(player, modifierForIconButton)
    Spacer(Modifier.weight(0.3f))
    SeekForwardButton(player, modifierForIconButton)
    Spacer(Modifier.weight(0.3f))
    NextButton(player, modifierForIconButton)
    Spacer(Modifier.weight(1f))
  }
}
