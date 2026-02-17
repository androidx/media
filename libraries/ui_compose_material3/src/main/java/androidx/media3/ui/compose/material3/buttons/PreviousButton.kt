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

package androidx.media3.ui.compose.material3.buttons

import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.buttons.PreviousButton as PreviousButtonContainer
import androidx.media3.ui.compose.material3.R
import androidx.media3.ui.compose.state.PreviousButtonState

/**
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that seeks to the previous media
 * item.
 *
 * When clicked, it attempts to advance the [player] to the previous media item in its current
 * playlist. The button's state (e.g., whether it's enabled) is managed by a [PreviousButtonState]
 * instance derived from the provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param painter The supplier for [Painter] used for the icon displayed on the button. Defaults to
 *   [R.drawable.media3_icon_previous].
 * @param contentDescription The content description for accessibility purposes. Defaults to
 *   [R.string.previous_button].
 * @param colors [IconButtonColors] that will be used to resolve the colors used for this icon
 *   button in different states. See [IconButtonDefaults.iconButtonColors].
 * @param tint Tint to be applied to [painter]. If [Color.Unspecified] is provided, then no tint is
 *   applied.
 * @param onClick The action to be performed when the button is clicked. This lambda has
 *   [PreviousButtonState] as its receiver, providing access to the button's current state (e.g.,
 *   [PreviousButtonState.isEnabled]). The default behavior is to call
 *   [PreviousButtonState.onClick], which seeks the [player] to the previous item if available.
 *   Consumers can customize this behavior:
 * * To add custom logic while still performing the default previous action, call `this.onClick()`
 *   within your lambda.
 * * To completely override the default behavior, implement your custom logic without calling
 *   `this.onClick()`. Note that in this case, the button might still be enabled based on the player
 *   state, so ensure your custom logic handles cases where seeking is not possible.
 */
@UnstableApi
@Composable
fun PreviousButton(
  player: Player?,
  modifier: Modifier = Modifier,
  painter: @Composable PreviousButtonState.() -> Painter = {
    painterResource(R.drawable.media3_icon_previous)
  },
  contentDescription: @Composable PreviousButtonState.() -> String = {
    stringResource(R.string.previous_button)
  },
  colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
  tint: Color = Color.Unspecified,
  onClick: PreviousButtonState.() -> Unit = PreviousButtonState::onClick,
) {
  // Capture the onClick *parameter* in a local variable.
  // This avoids shadowing the PreviousButtonState's onClick() *member function*
  // inside the PreviousButtonContainer's lambda.
  val customOnClick: PreviousButtonState.() -> Unit = onClick
  PreviousButtonContainer(player) {
    ClickableIconButton(
      modifier,
      isEnabled,
      icon = painter(),
      contentDescription = contentDescription(),
      colors = colors,
      tint = tint,
      onClick = { customOnClick() },
    )
  }
}

/**
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that seeks to the previous media
 * item.
 *
 * When clicked, it attempts to advance the [player] to the previous media item in its current
 * playlist. The button's state (e.g., whether it's enabled) is managed by a [PreviousButtonState]
 * instance derived from the provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param imageVector The supplier for [ImageVector] used for the icon displayed on the button.
 * @param contentDescription The content description for accessibility purposes. Defaults to
 *   [R.string.previous_button].
 * @param colors [IconButtonColors] that will be used to resolve the colors used for this icon
 *   button in different states. See [IconButtonDefaults.iconButtonColors].
 * @param tint Tint to be applied to [imageVector]. If [Color.Unspecified] is provided, then no tint
 *   is applied.
 * @param onClick The action to be performed when the button is clicked. This lambda has
 *   [PreviousButtonState] as its receiver, providing access to the button's current state (e.g.,
 *   [PreviousButtonState.isEnabled]). The default behavior is to call
 *   [PreviousButtonState.onClick], which seeks the [player] to the previous item if available.
 *   Consumers can customize this behavior:
 * * To add custom logic while still performing the default previous action, call `this.onClick()`
 *   within your lambda.
 * * To completely override the default behavior, implement your custom logic without calling
 *   `this.onClick()`. Note that in this case, the button might still be enabled based on the player
 *   state, so ensure your custom logic handles cases where seeking is not possible.
 */
@UnstableApi
@Composable
@JvmName("PreviousButtonWithImageVector")
fun PreviousButton(
  player: Player,
  modifier: Modifier = Modifier,
  imageVector: @Composable PreviousButtonState.() -> ImageVector,
  contentDescription: @Composable PreviousButtonState.() -> String = {
    stringResource(R.string.previous_button)
  },
  colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
  tint: Color = Color.Unspecified,
  onClick: PreviousButtonState.() -> Unit = PreviousButtonState::onClick,
) {
  // Capture the onClick *parameter* in a local variable.
  // This avoids shadowing the PreviousButtonState's onClick() *member function*
  // inside the PreviousButtonContainer's lambda.
  val customOnClick: PreviousButtonState.() -> Unit = onClick
  PreviousButtonContainer(player) {
    ClickableIconButton(
      modifier,
      isEnabled,
      icon = imageVector(),
      contentDescription = contentDescription(),
      colors = colors,
      tint = tint,
      onClick = { customOnClick() },
    )
  }
}
