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

package androidx.media3.ui.compose.material3.buttons

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.buttons.RepeatButton as RepeatStateContainer
import androidx.media3.ui.compose.material3.R
import androidx.media3.ui.compose.state.RepeatButtonState

/**
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that toggles the repeat mode of
 * the player.
 *
 * When clicked, it cycles through the available repeat modes in the order defined by
 * [toggleModeSequence]. The button's state (e.g., whether it's enabled and the current repeat mode
 * icon) is managed by a [RepeatButtonState] instance derived from the provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param toggleModeSequence The sequence of repeat modes to cycle through when the button is
 *   clicked.
 * @param painter The supplier for [Painter] used for the icon displayed on the button. This is a
 *   composable lambda with [RepeatButtonState] as its receiver, allowing the icon to be updated
 *   based on the button's current state (e.g. [RepeatButtonState.repeatModeState]).
 * @param contentDescription The content description for accessibility purposes.
 * @param tint Tint to be applied to [painter]. If [Color.Unspecified] is provided, then no tint is
 *   applied.
 * @param onClick The action to be performed when the button is clicked. This lambda has
 *   [RepeatButtonState] as its receiver, providing access to the button's current state (e.g.,
 *   [RepeatButtonState.isEnabled]). The default behavior is to call [RepeatButtonState.onClick],
 *   which updates the [player's][player] repeat mode. Consumers can customize this behavior:
 * * To add custom logic while still performing the default action, call `this.onClick()` within
 *   your lambda.
 * * To completely override the default behavior, implement your custom logic without calling
 *   `this.onClick()`. Note that in this case, the button might still be enabled based on the player
 *   state, so ensure your custom logic handles cases where modifying the repeat mode is not
 *   possible.
 */
@UnstableApi
@Composable
fun RepeatButton(
  player: Player,
  modifier: Modifier = Modifier,
  toggleModeSequence: List<@Player.RepeatMode Int> =
    listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL),
  painter: @Composable RepeatButtonState.() -> Painter = defaultRepeatModePainterIcon,
  contentDescription: @Composable RepeatButtonState.() -> String =
    defaultRepeatModeContentDescription,
  tint: Color = Color.Unspecified,
  onClick: RepeatButtonState.() -> Unit = RepeatButtonState::onClick,
) {
  // Capture the onClick *parameter* in a local variable.
  // This avoids shadowing the RepeatButtonState's onClick() *member function*
  // inside the RepeatStateContainer's lambda.
  val customOnClick: RepeatButtonState.() -> Unit = onClick
  RepeatStateContainer(player, toggleModeSequence) {
    ClickableIconButton(
      modifier,
      isEnabled,
      icon = painter(),
      contentDescription = contentDescription(),
      tint = tint,
      onClick = { customOnClick() },
    )
  }
}

/**
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that toggles the repeat mode of
 * the player.
 *
 * When clicked, it cycles through the available repeat modes in the order defined by
 * [toggleModeSequence]. The button's state (e.g., whether it's enabled and the current repeat mode
 * icon) is managed by a [RepeatButtonState] instance derived from the provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param toggleModeSequence The sequence of repeat modes to cycle through when the button is
 *   clicked.
 * @param imageVector The supplier for [ImageVector] used for the icon displayed on the button. This
 *   is a composable lambda with [RepeatButtonState] as its receiver, allowing the icon to be
 *   updated based on the button's current state (e.g. [RepeatButtonState.repeatModeState]).
 * @param contentDescription The content description for accessibility purposes.
 * @param tint Tint to be applied to [imageVector]. If [Color.Unspecified] is provided, then no tint
 *   is applied.
 * @param onClick The action to be performed when the button is clicked. This lambda has
 *   [RepeatButtonState] as its receiver, providing access to the button's current state (e.g.,
 *   [RepeatButtonState.isEnabled]). The default behavior is to call [RepeatButtonState.onClick],
 *   which updates the [player's][player] repeat mode. Consumers can customize this behavior:
 * * To add custom logic while still performing the default action, call `this.onClick()` within
 *   your lambda.
 * * To completely override the default behavior, implement your custom logic without calling
 *   `this.onClick()`. Note that in this case, the button might still be enabled based on the player
 *   state, so ensure your custom logic handles cases where modifying the repeat mode is not
 *   possible.
 */
@JvmName("RepeatButtonWithImageVector")
@UnstableApi
@Composable
fun RepeatButton(
  player: Player,
  modifier: Modifier = Modifier,
  toggleModeSequence: List<@Player.RepeatMode Int> =
    listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL),
  imageVector: RepeatButtonState.() -> ImageVector,
  contentDescription: @Composable RepeatButtonState.() -> String =
    defaultRepeatModeContentDescription,
  tint: Color = Color.Unspecified,
  onClick: RepeatButtonState.() -> Unit = RepeatButtonState::onClick,
) {
  // Capture the onClick *parameter* in a local variable.
  // This avoids shadowing the RepeatButtonState's onClick() *member function*
  // inside the RepeatStateContainer's lambda.
  val customOnClick: RepeatButtonState.() -> Unit = onClick
  RepeatStateContainer(player, toggleModeSequence) {
    ClickableIconButton(
      modifier,
      isEnabled,
      icon = imageVector(),
      contentDescription = contentDescription(),
      tint = tint,
      onClick = { customOnClick() },
    )
  }
}

private val defaultRepeatModeContentDescription: @Composable RepeatButtonState.() -> String =
  @Composable {
    when (repeatModeState) {
      Player.REPEAT_MODE_OFF -> stringResource(R.string.repeat_button_repeat_off)
      Player.REPEAT_MODE_ONE -> stringResource(R.string.repeat_button_repeat_one)
      else -> stringResource(R.string.repeat_button_repeat_all)
    }
  }

private val defaultRepeatModePainterIcon: @Composable RepeatButtonState.() -> Painter =
  @Composable {
    when (repeatModeState) {
      Player.REPEAT_MODE_OFF -> painterResource(R.drawable.media3_icon_repeat_all)
      Player.REPEAT_MODE_ONE -> painterResource(R.drawable.media3_icon_repeat_one_filled)
      else -> painterResource(R.drawable.media3_icon_repeat_all_filled)
    }
  }
