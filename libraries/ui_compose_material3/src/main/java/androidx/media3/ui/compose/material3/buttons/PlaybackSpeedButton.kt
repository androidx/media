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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.buttons.PlaybackSpeedControl
import androidx.media3.ui.compose.material3.R
import androidx.media3.ui.compose.state.PlaybackSpeedState

/**
 * A Material3 [TextButton] that toggles the playback speed of the player.
 *
 * ![PlaybackSpeedToggleButton
 * image](https://developer.android.com/images/reference/androidx/media3/ui/compose/material3/playback-speed-toggle-button.svg)
 *
 * When clicked, it cycles through the available playback speeds in the order defined by
 * [speedSelection]. If the current playback speed is not among the ones in the list, a click will
 * toggle the speed to the next value that is greater than the current speed. If all speeds in
 * [speedSelection] are smaller than the current speed, it will cycle back to the first speed in the
 * list.
 *
 * The button's state (e.g., whether it's enabled and the current playback speed) is managed by a
 * [PlaybackSpeedState] instance derived from the provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param speedSelection The sequence of speeds to cycle through when the button is clicked.
 * @param shape Defines the shape of this button's container, border (when [border] is not null),
 *   and shadow (when using [elevation]). See [ButtonDefaults.textShape].
 * @param colors [ButtonColors] that will be used to resolve the colors for this button in different
 *   states. See [ButtonDefaults.textButtonColors].
 * @param elevation [ButtonElevation] used to resolve the elevation for this button in different
 *   states. A `TextButton` typically has no elevation. See [ButtonDefaults.buttonElevation].
 * @param border The border to draw around the container of this button.
 * @param contentPadding The spacing values to apply internally between the container and the
 *   content. See [ButtonDefaults.TextButtonContentPadding].
 * @param interactionSource An optional hoisted [MutableInteractionSource] for observing and
 *   emitting [Interaction][androidx.compose.foundation.interaction.Interaction]s for this button.
 *   You can use this to change the button's appearance or preview the button in different states.
 *   Note that if `null` is provided, interactions will still happen internally.
 * @param content A composable lambda with [PlaybackSpeedState] as its receiver, allowing the
 *   content of the button to be customized.
 */
@UnstableApi
@Composable
fun PlaybackSpeedToggleButton(
  player: Player?,
  modifier: Modifier = Modifier,
  speedSelection: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f),
  shape: Shape = ButtonDefaults.textShape,
  colors: ButtonColors = ButtonDefaults.textButtonColors(),
  elevation: ButtonElevation? = null,
  border: BorderStroke? = null,
  contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
  interactionSource: MutableInteractionSource? = null,
  content: @Composable PlaybackSpeedState.() -> Unit = {
    Text(text = stringResource(R.string.playback_speed_format, playbackSpeed))
  },
) {
  PlaybackSpeedControl(player) {
    TextButton(
      onClick = {
        val nextSpeed = speedSelection.firstOrNull { it > playbackSpeed } ?: speedSelection.first()
        updatePlaybackSpeed(nextSpeed)
      },
      enabled = isEnabled,
      modifier = modifier,
      shape = shape,
      colors = colors,
      elevation = elevation,
      border = border,
      contentPadding = contentPadding,
      interactionSource = interactionSource,
    ) {
      content()
    }
  }
}
