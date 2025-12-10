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

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.buttons.ShuffleButton as ShuffleStateContainer
import androidx.media3.ui.compose.material3.R
import androidx.media3.ui.compose.state.ShuffleButtonState

/**
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that toggles the shuffle mode of
 * the player.
 *
 * When clicked, it enables or disables the player's shuffle mode. The button's state (e.g., whether
 * it's enabled and the shuffle icon) is managed by a [ShuffleButtonState] instance derived from the
 * provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param painter The supplier for [Painter] used for the icon displayed on the button. This is a
 *   composable lambda with [ShuffleButtonState] as its receiver, allowing the icon to be updated
 *   based on the button's current state (e.g. [ShuffleButtonState.shuffleOn]).
 * @param contentDescription The content description for accessibility purposes.
 * @param tint Tint to be applied to [painter]. If [Color.Unspecified] is provided, then no tint is
 *   applied.
 * @param onClick The action to be performed when the button is clicked. This lambda has
 *   [ShuffleButtonState] as its receiver, providing access to the button's current state (e.g.,
 *   [ShuffleButtonState.isEnabled]). The default behavior is to call [ShuffleButtonState.onClick],
 *   which toggles the [player's][player] shuffle mode. Consumers can customize this behavior:
 * * To add custom logic while still performing the default action, call `this.onClick()` within
 *   your lambda.
 * * To completely override the default behavior, implement your custom logic without calling
 *   `this.onClick()`. Note that in this case, the button might still be enabled based on the player
 *   state, so ensure your custom logic handles cases where modifying the shuffle mode is not
 *   possible.
 */
@UnstableApi
@Composable
fun ShuffleButton(
  player: Player,
  modifier: Modifier = Modifier,
  painter: @Composable ShuffleButtonState.() -> Painter = defaultShufflePainterIcon,
  contentDescription: @Composable ShuffleButtonState.() -> String =
    defaultShuffleContentDescription,
  tint: Color = LocalContentColor.current,
  onClick: ShuffleButtonState.() -> Unit = ShuffleButtonState::onClick,
) {
  // Capture the onClick *parameter* in a local variable.
  // This avoids shadowing the ShuffleButtonState's onClick() *member function*
  // inside the ShuffleStateContainer's lambda.
  val customOnClick: ShuffleButtonState.() -> Unit = onClick
  ShuffleStateContainer(player) {
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
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that toggles the shuffle mode of
 * the player.
 *
 * When clicked, it enables or disables the player's shuffle mode. The button's state (e.g., whether
 * it's enabled and the shuffle icon) is managed by a [ShuffleButtonState] instance derived from the
 * provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param imageVector The supplier for [ImageVector] used for the icon displayed on the button. This
 *   is a composable lambda with [ShuffleButtonState] as its receiver, allowing the icon to be
 *   updated based on the button's current state (e.g. [ShuffleButtonState.shuffleOn]).
 * @param tint Tint to be applied to [imageVector]. If [Color.Unspecified] is provided, then no tint
 *   is applied.
 * @param contentDescription The content description for accessibility purposes.
 * @param onClick The action to be performed when the button is clicked. This lambda has
 *   [ShuffleButtonState] as its receiver, providing access to the button's current state (e.g.,
 *   [ShuffleButtonState.isEnabled]). The default behavior is to call [ShuffleButtonState.onClick],
 *   which toggles the [player's][player] shuffle mode. Consumers can customize this behavior:
 * * To add custom logic while still performing the default action, call `this.onClick()` within
 *   your lambda.
 * * To completely override the default behavior, implement your custom logic without calling
 *   `this.onClick()`. Note that in this case, the button might still be enabled based on the player
 *   state, so ensure your custom logic handles cases where modifying the shuffle mode is not
 *   possible.
 */
@JvmName("ShuffleButtonWithImageVector")
@UnstableApi
@Composable
fun ShuffleButton(
  player: Player,
  modifier: Modifier = Modifier,
  imageVector: ShuffleButtonState.() -> ImageVector,
  contentDescription: @Composable ShuffleButtonState.() -> String =
    defaultShuffleContentDescription,
  tint: Color = LocalContentColor.current,
  onClick: ShuffleButtonState.() -> Unit = ShuffleButtonState::onClick,
) {
  // Capture the onClick *parameter* in a local variable.
  // This avoids shadowing the ShuffleButtonState's onClick() *member function*
  // inside the ShuffleStateContainer's lambda.
  val customOnClick: ShuffleButtonState.() -> Unit = onClick
  ShuffleStateContainer(player) {
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

private val defaultShuffleContentDescription: @Composable ShuffleButtonState.() -> String =
  @Composable {
    if (shuffleOn) stringResource(R.string.shuffle_button_shuffle_on)
    else stringResource(R.string.shuffle_button_shuffle_off)
  }

private val defaultShufflePainterIcon: @Composable ShuffleButtonState.() -> Painter =
  @Composable {
    if (shuffleOn) painterResource(R.drawable.media3_icon_shuffle_on_filled)
    else painterResource(R.drawable.media3_icon_shuffle_on)
  }
