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

package androidx.media3.ui.compose.material3.button

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.buttons.SeekForwardButton as SeekForwardStateContainer
import androidx.media3.ui.compose.material3.R
import androidx.media3.ui.compose.state.SeekForwardButtonState

/**
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that seeks forward in the current
 * media item.
 *
 * When clicked, it attempts to seek forward in the [player] by the amount returned by
 * [Player.getSeekForwardIncrement]. The button's state (e.g., whether it's enabled) is managed by a
 * [SeekForwardButtonState] instance derived from the provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param painter The supplier for [Painter] used for the icon displayed on the button. Defaults to
 *   an icon that changes based on the seek forward increment.
 * @param contentDescription The content description for accessibility purposes. Defaults to a
 *   string that changes based on the seek forward increment.
 * @param onClick The action to be performed when the button is clicked. This lambda has
 *   [SeekForwardButtonState] as its receiver, providing access to the button's current state (e.g.,
 *   [SeekForwardButtonState.isEnabled]). The default behavior is to call
 *   [SeekForwardButtonState.onClick], which seeks forward in the current media item. Consumers can
 *   customize this behavior:
 * * To add custom logic while still performing the default seek forward action, call
 *   `this.onClick()` within your lambda.
 * * To completely override the default behavior, implement your custom logic without calling
 *   `this.onClick()`. Note that in this case, the button might still be enabled based on the player
 *   state, so ensure your custom logic handles cases where seeking is not possible.
 */
@UnstableApi
@Composable
fun SeekForwardButton(
  player: Player,
  modifier: Modifier = Modifier,
  painter: @Composable SeekForwardButtonState.() -> Painter = defaultSeekForwardPainterIcon,
  contentDescription: @Composable SeekForwardButtonState.() -> String =
    defaultSeekForwardContentDescription,
  onClick: SeekForwardButtonState.() -> Unit = SeekForwardButtonState::onClick,
) {
  // Capture the onClick *parameter* in a local variable.
  // This avoids shadowing the SeekForwardButtonState's onClick() *member function*
  // inside the SeekForwardStateContainer's lambda.
  val customOnClick: SeekForwardButtonState.() -> Unit = onClick
  SeekForwardStateContainer(player) {
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
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that seeks forward in the current
 * media item.
 *
 * When clicked, it attempts to seek forward in the [player] by the amount returned by
 * [Player.getSeekForwardIncrement]. The button's state (e.g., whether it's enabled) is managed by a
 * [SeekForwardButtonState] instance derived from the provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param imageVector The supplier for [ImageVector] used for the icon displayed on the button.
 * @param contentDescription The content description for accessibility purposes. Defaults to a
 *   string that changes based on the seek forward increment.
 * @param onClick The action to be performed when the button is clicked. This lambda has
 *   [SeekForwardButtonState] as its receiver, providing access to the button's current state (e.g.,
 *   [SeekForwardButtonState.isEnabled]). The default behavior is to call
 *   [SeekForwardButtonState.onClick], which seeks forward in the current media item. Consumers can
 *   customize this behavior:
 * * To add custom logic while still performing the default seek forward action, call
 *   `this.onClick()` within your lambda.
 * * To completely override the default behavior, implement your custom logic without calling
 *   `this.onClick()`. Note that in this case, the button might still be enabled based on the player
 *   state, so ensure your custom logic handles cases where seeking is not possible.
 */
@JvmName("SeekForwardButtonWithImageVector")
@UnstableApi
@Composable
fun SeekForwardButton(
  player: Player,
  modifier: Modifier = Modifier,
  imageVector: SeekForwardButtonState.() -> ImageVector,
  contentDescription: @Composable SeekForwardButtonState.() -> String =
    defaultSeekForwardContentDescription,
  onClick: SeekForwardButtonState.() -> Unit = SeekForwardButtonState::onClick,
) {
  // Capture the onClick *parameter* in a local variable.
  // This avoids shadowing the SeekForwardButtonState's onClick() *member function*
  // inside the SeekForwardStateContainer's lambda.
  val customOnClick: SeekForwardButtonState.() -> Unit = onClick
  SeekForwardStateContainer(player) {
    ClickableIconButton(
      modifier,
      isEnabled,
      icon = imageVector(),
      contentDescription = contentDescription(),
      onClick = { customOnClick() },
    )
  }
}

private val defaultSeekForwardContentDescription: @Composable SeekForwardButtonState.() -> String =
  @Composable {
    when (seekForwardAmountMs) {
      in 2500..7500 -> pluralStringResource(R.plurals.seek_forward_by_amount_button, count = 5)
      in 7500..12500 -> pluralStringResource(R.plurals.seek_forward_by_amount_button, count = 10)
      in 12500..20000 -> pluralStringResource(R.plurals.seek_forward_by_amount_button, count = 15)
      in 20000..40000 -> pluralStringResource(R.plurals.seek_forward_by_amount_button, count = 30)
      else -> stringResource(R.string.seek_forward_button)
    }
  }

private val defaultSeekForwardPainterIcon: @Composable SeekForwardButtonState.() -> Painter =
  @Composable {
    when (seekForwardAmountMs) {
      in 2500..7500 -> painterResource(R.drawable.media3_icon_skip_forward_5)
      in 7500..12500 -> painterResource(R.drawable.media3_icon_skip_forward_10)
      in 12500..20000 -> painterResource(R.drawable.media3_icon_skip_forward_15)
      in 20000..40000 -> painterResource(R.drawable.media3_icon_skip_forward_30)
      else -> painterResource(R.drawable.media3_icon_skip_forward)
    }
  }
