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
import androidx.media3.ui.compose.buttons.MuteButton as MuteStateContainer
import androidx.media3.ui.compose.material3.R
import androidx.media3.ui.compose.state.MuteButtonState

/**
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that mutes or unmutes the player.
 *
 * When clicked, it will mute the [player] if it's currently unmuted, and unmute it otherwise. The
 * button's state (e.g., whether it's enabled and the current mute icon) is managed by a
 * [MuteButtonState] instance derived from the provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param painter The supplier for [Painter] used for the icon displayed on the button. This is a
 *   composable lambda with [MuteButtonState] as its receiver, allowing the icon to be updated based
 *   on the button's current state (e.g. [MuteButtonState.showMuted]).
 * @param contentDescription The content description for accessibility purposes.
 * @param tint Tint to be applied to [painter]. If [Color.Unspecified] is provided, then no tint is
 *   applied.
 * @param onClick The action to be performed when the button is clicked. This lambda has
 *   [MuteButtonState] as its receiver, providing access to the button's current state (e.g.,
 *   [MuteButtonState.isEnabled]). The default behavior is to call [MuteButtonState.onClick], which
 *   toggles the [player's][player] mute state. Consumers can customize this behavior:
 * * To add custom logic while still performing the default action, call `this.onClick()` within
 *   your lambda.
 * * To completely override the default behavior, implement your custom logic without calling
 *   `this.onClick()`.
 */
@UnstableApi
@Composable
fun MuteButton(
  player: Player?,
  modifier: Modifier = Modifier,
  painter: @Composable MuteButtonState.() -> Painter = defaultMutePainterIcon,
  contentDescription: @Composable MuteButtonState.() -> String = defaultMuteContentDescription,
  tint: Color = Color.Unspecified,
  onClick: MuteButtonState.() -> Unit = MuteButtonState::onClick,
) {
  // Capture the onClick *parameter* in a local variable.
  // This avoids shadowing the MuteButtonState's onClick() *member function*
  // inside the MuteStateContainer's lambda.
  val customOnClick: MuteButtonState.() -> Unit = onClick
  MuteStateContainer(player) {
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
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that mutes or unmutes the player.
 *
 * When clicked, it will mute the [player] if it's currently unmuted, and unmute it otherwise. The
 * button's state (e.g., whether it's enabled and the current mute icon) is managed by a
 * [MuteButtonState] instance derived from the provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param imageVector The supplier for [ImageVector] used for the icon displayed on the button. This
 *   is a composable lambda with [MuteButtonState] as its receiver, allowing the icon to be updated
 *   based on the button's current state (e.g. [MuteButtonState.showMuted]).
 * @param tint Tint to be applied to [imageVector]. If [Color.Unspecified] is provided, then no tint
 *   is applied.
 * @param contentDescription The content description for accessibility purposes.
 * @param onClick The action to be performed when the button is clicked. This lambda has
 *   [MuteButtonState] as its receiver, providing access to the button's current state (e.g.,
 *   [MuteButtonState.isEnabled]). The default behavior is to call [MuteButtonState.onClick], which
 *   toggles the [player's][player] mute state. Consumers can customize this behavior:
 * * To add custom logic while still performing the default action, call `this.onClick()` within
 *   your lambda.
 * * To completely override the default behavior, implement your custom logic without calling
 *   `this.onClick()`.
 */
@JvmName("MuteButtonWithImageVector")
@UnstableApi
@Composable
fun MuteButton(
  player: Player?,
  modifier: Modifier = Modifier,
  imageVector: MuteButtonState.() -> ImageVector,
  contentDescription: @Composable MuteButtonState.() -> String = defaultMuteContentDescription,
  tint: Color = Color.Unspecified,
  onClick: MuteButtonState.() -> Unit = MuteButtonState::onClick,
) {
  // Capture the onClick *parameter* in a local variable.
  // This avoids shadowing the MuteButtonState's onClick() *member function*
  // inside the MuteStateContainer's lambda.
  val customOnClick: MuteButtonState.() -> Unit = onClick
  MuteStateContainer(player) {
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

private val defaultMuteContentDescription: @Composable MuteButtonState.() -> String =
  @Composable {
    if (showMuted) stringResource(R.string.mute_button_shown_muted)
    else stringResource(R.string.mute_button_shown_unmuted)
  }

private val defaultMutePainterIcon: @Composable MuteButtonState.() -> Painter =
  @Composable {
    if (showMuted) painterResource(R.drawable.media3_icon_volume_off)
    else painterResource(R.drawable.media3_icon_volume_up)
  }
