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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.buttons.PlaybackSpeedControl
import androidx.media3.ui.compose.material3.R
import androidx.media3.ui.compose.state.PlaybackSpeedState
import kotlin.math.round

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
 * [PlaybackSpeedState] instance derived from the provided [player].
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

/**
 * A Material3 [TextButton] that, when clicked, displays a [ModalBottomSheet] for selecting the
 * playback speed.
 *
 * The button's text displays the current playback speed, formatted using the string resource
 * `R.string.playback_speed_format`. When the button is clicked, a bottom sheet is shown, providing
 * controls to adjust the playback speed. The content of the bottom sheet is customizable via the
 * [content] lambda.
 *
 * The button's state (e.g., whether it's enabled and the current playback speed) is managed by a
 * [PlaybackSpeedState] instance derived from the provided [player]. The button's text is formatted
 * using the string resource `R.string.playback_speed_format`.
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param colors [ButtonColors] to be used for the button.
 * @param interactionSource The [MutableInteractionSource] for the button.
 * @param content The composable content to be displayed inside the [ModalBottomSheet]. The content
 *   receives a [PlaybackSpeedState] as a receiver and a lambda to dismiss the sheet. The default
 *   content is a [PlaybackSpeedBottomSheet].
 */
@OptIn(ExperimentalMaterial3Api::class) // for rememberModalBottomSheetState
@UnstableApi
@Composable
fun PlaybackSpeedBottomSheetButton(
  player: Player?,
  modifier: Modifier = Modifier,
  colors: ButtonColors = ButtonDefaults.textButtonColors(),
  interactionSource: MutableInteractionSource? = null,
  content: @Composable PlaybackSpeedState.(onDismissRequest: () -> Unit) -> Unit =
    defaultPlaybackSpeedBottomSheet,
) {
  var showBottomSheet by remember { mutableStateOf(false) }
  PlaybackSpeedControl(player) {
    TextButton(
      onClick = { showBottomSheet = true },
      enabled = isEnabled,
      modifier = modifier,
      colors = colors,
      interactionSource = interactionSource,
    ) {
      Text(text = stringResource(R.string.playback_speed_format, playbackSpeed))
    }

    if (showBottomSheet) {
      content { showBottomSheet = false }
    }
  }
}

/**
 * The content displayed inside a bottom sheet for playback speed selection.
 *
 * The default header displays the current speed using `R.string.playback_speed_format`. The default
 * controls include a slider and plus/minus buttons. The plus button uses
 * `R.drawable.media3_icon_plus` and content description `R.string.playback_speed_increase`. The
 * minus button uses `R.drawable.media3_icon_minus` and content description
 * `R.string.playback_speed_decrease`. The default presets are displayed as a row of
 * [OutlinedButton] instances.
 *
 * @param state The [PlaybackSpeedState] that holds the current playback speed.
 * @param onDismissRequest A lambda to be executed to dismiss the sheet.
 * @param modifier The [Modifier] to be applied to the sheet.
 * @param sheetState The state of the bottom sheet.
 * @param presetSpeeds A list of floating-point values for the preset speed buttons.
 * @param speedStep The increment/decrement value for the plus and minus buttons. This is also used
 *   to calculate the number of steps in the slider, by dividing the [speedRange] by this value.
 * @param speedRange The range of available speeds for the slider. The size of this range combined
 *   with [speedStep] determines the number of steps in the slider.
 * @param header A slot for the sheet header (default: current speed text).
 * @param controls A slot for the slider and adjusters (default: slider with +/- buttons).
 * @param presets A slot for preset buttons (default: row of preset speed buttons).
 */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackSpeedBottomSheet(
  state: PlaybackSpeedState,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  sheetState: SheetState = rememberModalBottomSheetState(),
  presetSpeeds: List<Float> = listOf(0.25f, 1.0f, 1.25f, 1.5f, 2.0f),
  speedStep: Float = 0.05f,
  speedRange: ClosedFloatingPointRange<Float> = 0.25f..2.0f,
  header: @Composable PlaybackSpeedState.() -> Unit = defaultPlaybackSpeedText,
  controls: @Composable PlaybackSpeedState.() -> Unit = {
    PlaybackSpeedSliderWithAdjusters(state = this, speedStep, speedRange)
  },
  presets: @Composable PlaybackSpeedState.() -> Unit = {
    PlaybackSpeedPresets(state = this, presetSpeeds, onDismissRequest)
  },
) {
  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    sheetState = sheetState,
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      state.header()
      state.controls()
      state.presets()
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
private val defaultPlaybackSpeedBottomSheet: @Composable PlaybackSpeedState.(() -> Unit) -> Unit =
  @Composable { onDismiss -> PlaybackSpeedBottomSheet(state = this, onDismissRequest = onDismiss) }

private val defaultPlaybackSpeedText: @Composable PlaybackSpeedState.() -> Unit =
  @Composable {
    Text(
      text = stringResource(R.string.playback_speed_format, playbackSpeed),
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.primary,
    )
  }

@Composable
private fun PlaybackSpeedSliderWithAdjusters(
  state: PlaybackSpeedState,
  speedStep: Float,
  speedRange: ClosedFloatingPointRange<Float>,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    PlaybackSpeedAdjusterButton(
      state,
      painterResource(R.drawable.media3_icon_minus),
      stringResource(R.string.playback_speed_decrease, speedStep),
      -speedStep,
      speedRange,
      enabled = state.isEnabled && state.playbackSpeed > speedRange.start,
    )
    PlaybackSpeedSlider(state, speedRange, speedStep, Modifier.weight(1f))
    PlaybackSpeedAdjusterButton(
      state,
      painterResource(R.drawable.media3_icon_plus),
      stringResource(R.string.playback_speed_increase, speedStep),
      speedStep,
      speedRange,
      enabled = state.isEnabled && state.playbackSpeed < speedRange.endInclusive,
    )
  }
}

@Composable
private fun PlaybackSpeedAdjusterButton(
  state: PlaybackSpeedState,
  painter: Painter,
  contentDescription: String? = null,
  speedStep: Float,
  speedRange: ClosedFloatingPointRange<Float>,
  enabled: Boolean,
) {
  OutlinedIconButton(
    onClick = {
      val newSpeed = (state.playbackSpeed + speedStep).coerceIn(speedRange)
      state.updatePlaybackSpeed((round(newSpeed * 100) / 100f))
    },
    enabled = enabled,
  ) {
    Icon(painter = painter, contentDescription = contentDescription)
  }
}

@Composable
private fun PlaybackSpeedSlider(
  state: PlaybackSpeedState,
  speedRange: ClosedFloatingPointRange<Float>,
  speedStep: Float,
  modifier: Modifier = Modifier,
) {
  Slider(
    value = state.playbackSpeed,
    onValueChange = { newSpeed -> state.updatePlaybackSpeed(newSpeed) },
    valueRange = speedRange,
    steps = ((speedRange.endInclusive - speedRange.start) / speedStep).toInt() - 1,
    colors =
      SliderDefaults.colors(
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
      ),
    modifier = modifier,
  )
}

@Composable
private fun PlaybackSpeedPresets(
  state: PlaybackSpeedState,
  presetSpeeds: List<Float>,
  onPresetSelected: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceAround,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for (speed in presetSpeeds) {
      OutlinedButton(
        modifier = Modifier.weight((1.0 / presetSpeeds.size).toFloat(), fill = false),
        onClick = {
          state.updatePlaybackSpeed(speed)
          onPresetSelected()
        },
      ) {
        Text(
          text = "%.2f".format(speed),
          fontWeight = if (speed == state.playbackSpeed) FontWeight.Bold else FontWeight.Normal,
        )
      }
    }
  }
}
