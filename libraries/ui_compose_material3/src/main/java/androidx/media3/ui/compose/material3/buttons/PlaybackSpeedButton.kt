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

package androidx.media3.ui.compose.material3.buttons

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.buttons.PlaybackSpeedControl
import androidx.media3.ui.compose.material3.R

/**
 * A Material3 [TextButton] that toggles the playback speed of the player.
 *
 * When clicked, it cycles through the available playback speeds in the order defined by
 * [speedSelection]. If the current playback speed is not among the ones in the list, a click will
 * toggle the speed to the next value that is greater than the current speed. If all speeds in
 * [speedSelection] are smaller than the current speed, it will cycle back to the first speed in the
 * list.
 *
 * The button's state (e.g., whether it's enabled and the current playback speed) is managed by a
 * [PlaybackSpeedState][androidx.media3.ui.compose.state.PlaybackSpeedState] instance derived from
 * the provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param colors [ButtonColors] to be used for the button.
 * @param interactionSource The [MutableInteractionSource] for the button.
 * @param speedSelection The sequence of speeds to cycle through when the button is clicked.
 */
@UnstableApi
@Composable
fun PlaybackSpeedToggleButton(
  player: Player?,
  modifier: Modifier = Modifier,
  colors: ButtonColors = ButtonDefaults.textButtonColors(),
  interactionSource: MutableInteractionSource? = null,
  speedSelection: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f),
) {
  PlaybackSpeedControl(player) {
    TextButton(
      onClick = {
        val nextSpeed = speedSelection.firstOrNull { it > playbackSpeed } ?: speedSelection.first()
        updatePlaybackSpeed(nextSpeed)
      },
      enabled = isEnabled,
      modifier = modifier,
      colors = colors,
      interactionSource = interactionSource,
    ) {
      Text(text = stringResource(R.string.playback_speed_format, playbackSpeed))
    }
  }
}
