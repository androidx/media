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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.buttons.NextButton as NextButtonStateContainer
import androidx.media3.ui.compose.material3.R
import androidx.media3.ui.compose.state.NextButtonState

/**
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that seeks to the next media
 * item.
 *
 * When clicked, it attempts to advance the [player] to the next media item in its current playlist.
 * The button's state (e.g., whether it's enabled) is managed by a [NextButtonState] instance
 * derived from the provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param painter The supplier for [Painter] used for the icon displayed on the button. Defaults to
 *   [R.drawable.media3_icon_next].
 * @param contentDescription The content description for accessibility purposes. Defaults to
 *   [R.string.next_button].
 * @param onClick The action to be performed when the button is clicked. This lambda has
 *   [NextButtonState] as its receiver, providing access to the button's current state (e.g.,
 *   [NextButtonState.isEnabled]). The default behavior is to call [NextButtonState.onClick], which
 *   advances the [player] to the next item if available. Consumers can customize this behavior:
 * * To add custom logic while still performing the default next action, call `this.onClick()`
 *   within your lambda.
 * * To completely override the default behavior, implement your custom logic without calling
 *   `this.onClick()`. Note that in this case, the button might still be enabled based on the player
 *   state, so ensure your custom logic handles cases where advancing is not possible.
 */
@UnstableApi
@Composable
fun NextButton(
  player: Player,
  modifier: Modifier = Modifier,
  painter: @Composable NextButtonState.() -> Painter = {
    painterResource(R.drawable.media3_icon_next)
  },
  contentDescription: @Composable NextButtonState.() -> String = {
    stringResource(R.string.next_button)
  },
  onClick: NextButtonState.() -> Unit = NextButtonState::onClick,
) {
  // Capture the onClick *parameter* in a local variable.
  // This avoids shadowing the NextButtonState's onClick() *member function*
  // inside the NextButtonStateContainer's lambda.
  val customOnClick: NextButtonState.() -> Unit = onClick
  NextButtonStateContainer(player) {
    ClickableIconButton(
      modifier,
      isEnabled,
      icon = painter(),
      contentDescription = contentDescription(),
      onClick = { customOnClick() },
    )
  }
}

/**
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that seeks to the next media
 * item.
 *
 * When clicked, it attempts to advance the [player] to the next media item in its current playlist.
 * The button's state (e.g., whether it's enabled) is managed by a [NextButtonState] instance
 * derived from the provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param imageVector The supplier for [ImageVector] used for the icon displayed on the button.
 * @param contentDescription The content description for accessibility purposes. Defaults to
 *   [R.string.next_button].
 * @param onClick The action to be performed when the button is clicked. This lambda has
 *   [NextButtonState] as its receiver, providing access to the button's current state (e.g.,
 *   [NextButtonState.isEnabled]). The default behavior is to call [NextButtonState.onClick], which
 *   advances the [player] to the next item if available. Consumers can customize this behavior:
 * * To add custom logic while still performing the default next action, call `this.onClick()`
 *   within your lambda.
 * * To completely override the default behavior, implement your custom logic without calling
 *   `this.onClick()`. Note that in this case, the button might still be enabled based on the player
 *   state, so ensure your custom logic handles cases where advancing is not possible.
 */
@JvmName("NextButtonWithImageVector")
@UnstableApi
@Composable
fun NextButton(
  player: Player,
  modifier: Modifier = Modifier,
  imageVector: @Composable NextButtonState.() -> ImageVector,
  contentDescription: @Composable NextButtonState.() -> String = {
    stringResource(R.string.next_button)
  },
  onClick: NextButtonState.() -> Unit = NextButtonState::onClick,
) {
  // Capture the onClick *parameter* in a local variable.
  // This avoids shadowing the NextButtonState's onClick() *member function*
  // inside the NextButtonStateContainer's lambda.
  val customOnClick: NextButtonState.() -> Unit = onClick
  NextButtonStateContainer(player) {
    ClickableIconButton(
      modifier,
      isEnabled,
      icon = imageVector(),
      contentDescription = contentDescription(),
      onClick = { customOnClick() },
    )
  }
}
