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

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.buttons.PlayPauseButton as PlayPauseStateContainer
import androidx.media3.ui.compose.material3.R
import androidx.media3.ui.compose.state.PlayPauseButtonState

/**
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that plays or pauses the current
 * media item.
 *
 * When clicked, it will pause the [player] if it's currently playing, or play it otherwise. The
 * button's state (e.g., whether it's enabled and the current play/pause icon) is managed by a
 * [PlayPauseButtonState] instance derived from the provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param painter The supplier for [Painter] used for the icon displayed on the button. This is a
 *   composable lambda with [PlayPauseButtonState] as its receiver, allowing the icon to be updated
 *   based on the button's current state (e.g., [PlayPauseButtonState.showPlay]).
 * @param contentDescription The content description for accessibility purposes.
 * @param tint Tint to be applied to [painter]. If [Color.Unspecified] is provided, then no tint is
 *   applied.
 * @param onClick The action to be performed when the button is clicked. This lambda has
 *   [PlayPauseButtonState] as its receiver, providing access to the button's current state (e.g.,
 *   [PlayPauseButtonState.isEnabled]). The default behavior is to call
 *   [PlayPauseButtonState.onClick], which toggles the [player's][player] play/pause state.
 *   Consumers can customize this behavior:
 * * To add custom logic while still performing the default action, call `this.onClick()` within
 *   your lambda.
 * * To completely override the default behavior, implement your custom logic without calling
 *   `this.onClick()`.
 */
@UnstableApi
@Composable
fun PlayPauseButton(
  player: Player?,
  modifier: Modifier = Modifier,
  painter: @Composable PlayPauseButtonState.() -> Painter = defaultPlayPausePainterIcon,
  contentDescription: @Composable PlayPauseButtonState.() -> String =
    defaultPlayPauseContentDescription,
  tint: Color = Color.Unspecified,
  onClick: PlayPauseButtonState.() -> Unit = PlayPauseButtonState::onClick,
) {
  // Capture the onClick *parameter* in a local variable.
  // This avoids shadowing the PlayPauseButtonState's onClick() *member function*
  // inside the PlayPauseStateContainer's lambda.
  val customOnClick: PlayPauseButtonState.() -> Unit = onClick
  PlayPauseStateContainer(player) {
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
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that plays or pauses the current
 * media item.
 *
 * When clicked, it will pause the [player] if it's currently playing, or play it otherwise. The
 * button's state (e.g., whether it's enabled and the current play/pause icon) is managed by a
 * [PlayPauseButtonState] instance derived from the provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param imageVector The supplier for [ImageVector] used for the icon displayed on the button. This
 *   is a composable lambda with [PlayPauseButtonState] as its receiver, allowing the icon to be
 *   updated based on the button's current state (e.g., [PlayPauseButtonState.showPlay]).
 * @param contentDescription The content description for accessibility purposes.
 * @param tint Tint to be applied to [imageVector]. If [Color.Unspecified] is provided, then no tint
 *   is applied.
 * @param onClick The action to be performed when the button is clicked. This lambda has
 *   [PlayPauseButtonState] as its receiver, providing access to the button's current state (e.g.,
 *   [PlayPauseButtonState.isEnabled]). The default behavior is to call
 *   [PlayPauseButtonState.onClick], which toggles the [player's][player] play/pause state.
 *   Consumers can customize this behavior:
 * * To add custom logic while still performing the default action, call `this.onClick()` within
 *   your lambda.
 * * To completely override the default behavior, implement your custom logic without calling
 *   `this.onClick()`.
 */
@JvmName("PlayPauseButtonWithImageVector")
@UnstableApi
@Composable
fun PlayPauseButton(
  player: Player?,
  modifier: Modifier = Modifier,
  imageVector: PlayPauseButtonState.() -> ImageVector,
  contentDescription: @Composable PlayPauseButtonState.() -> String =
    defaultPlayPauseContentDescription,
  tint: Color = Color.Unspecified,
  onClick: PlayPauseButtonState.() -> Unit = PlayPauseButtonState::onClick,
) {
  // Capture the onClick *parameter* in a local variable.
  // This avoids shadowing the PlayPauseButtonState's onClick() *member function*
  // inside the PlayPauseStateContainer's lambda.
  val customOnClick: PlayPauseButtonState.() -> Unit = onClick
  PlayPauseStateContainer(player) {
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

private val defaultPlayPauseContentDescription: @Composable PlayPauseButtonState.() -> String =
  @Composable {
    if (showPlay) stringResource(R.string.playpause_button_play)
    else stringResource(R.string.playpause_button_pause)
  }

private val defaultPlayPausePainterIcon: @Composable PlayPauseButtonState.() -> Painter =
  @Composable {
    if (showPlay) painterResource(R.drawable.media3_icon_play)
    else painterResource(R.drawable.media3_icon_pause)
  }
