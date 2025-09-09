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
import androidx.media3.ui.compose.buttons.SeekBackButton as SeekBackStateContainer
import androidx.media3.ui.compose.material3.R
import androidx.media3.ui.compose.state.SeekBackButtonState

/**
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that seeks back in the current
 * media item.
 *
 * When clicked, it attempts to seek back in the [player] by the amount returned by
 * [Player.getSeekBackIncrement]. The button's state (e.g., whether it's enabled) is managed by a
 * [SeekBackButtonState] instance derived from the provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param painter The supplier for [Painter] used for the icon displayed on the button.
 * @param contentDescription The content description for accessibility purposes.
 * @param onClick The action to be performed when the button is clicked. This lambda has
 *   [SeekBackButtonState] as its receiver, providing access to the button's current state (e.g.,
 *   [SeekBackButtonState.isEnabled]). The default behavior is to call
 *   [SeekBackButtonState.onClick], which seeks back in the current media item. Consumers can
 *   customize this behavior:
 * * To add custom logic while still performing the default seek back action, call `this.onClick()`
 *   within your lambda.
 * * To completely override the default behavior, implement your custom logic without calling
 *   `this.onClick()`. Note that in this case, the button might still be enabled based on the player
 *   state, so ensure your custom logic handles cases where seeking is not possible.
 */
@UnstableApi
@Composable
fun SeekBackButton(
  player: Player,
  modifier: Modifier = Modifier,
  painter: @Composable SeekBackButtonState.() -> Painter = defaultSeekBackPainterIcon,
  contentDescription: @Composable SeekBackButtonState.() -> String =
    defaultSeekBackContentDescription,
  onClick: SeekBackButtonState.() -> Unit = SeekBackButtonState::onClick,
) {
  // Capture the onClick *parameter* in a local variable.
  // This avoids shadowing the SeekBackButtonState's onClick() *member function*
  // inside the SeekBackStateContainer's lambda.
  val customOnClick: SeekBackButtonState.() -> Unit = onClick
  SeekBackStateContainer(player) {
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
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that seeks back in the current
 * media item.
 *
 * When clicked, it attempts to seek back in the [player] by the amount returned by
 * [Player.getSeekBackIncrement]. The button's state (e.g., whether it's enabled) is managed by a
 * [SeekBackButtonState] instance derived from the provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param imageVector The supplier for [ImageVector] used for the icon displayed on the button.
 * @param contentDescription The content description for accessibility purposes.
 * @param onClick The action to be performed when the button is clicked. This lambda has
 *   [SeekBackButtonState] as its receiver, providing access to the button's current state (e.g.,
 *   [SeekBackButtonState.isEnabled]). The default behavior is to call
 *   [SeekBackButtonState.onClick], which seeks back in the current media item. Consumers can
 *   customize this behavior:
 * * To add custom logic while still performing the default seek back action, call `this.onClick()`
 *   within your lambda.
 * * To completely override the default behavior, implement your custom logic without calling
 *   `this.onClick()`. Note that in this case, the button might still be enabled based on the player
 *   state, so ensure your custom logic handles cases where seeking is not possible.
 */
@JvmName("SeekBackButtonWithImageVector")
@UnstableApi
@Composable
fun SeekBackButton(
  player: Player,
  modifier: Modifier = Modifier,
  imageVector: SeekBackButtonState.() -> ImageVector,
  contentDescription: @Composable SeekBackButtonState.() -> String =
    defaultSeekBackContentDescription,
  onClick: SeekBackButtonState.() -> Unit = SeekBackButtonState::onClick,
) {
  // Capture the onClick *parameter* in a local variable.
  // This avoids shadowing the SeekBackButtonState's onClick() *member function*
  // inside the SeekBackStateContainer's lambda.
  val customOnClick: SeekBackButtonState.() -> Unit = onClick
  SeekBackStateContainer(player) {
    ClickableIconButton(
      modifier,
      isEnabled,
      icon = imageVector(),
      contentDescription = contentDescription(),
      onClick = { customOnClick() },
    )
  }
}

private val defaultSeekBackContentDescription: @Composable SeekBackButtonState.() -> String =
  @Composable {
    when (seekBackAmountMs) {
      in 2500..7500 -> pluralStringResource(R.plurals.seek_back_by_amount_button, count = 5)
      in 7500..12500 -> pluralStringResource(R.plurals.seek_back_by_amount_button, count = 10)
      in 12500..20000 -> pluralStringResource(R.plurals.seek_back_by_amount_button, count = 15)
      in 20000..40000 -> pluralStringResource(R.plurals.seek_back_by_amount_button, count = 30)
      else -> stringResource(R.string.seek_back_button)
    }
  }

private val defaultSeekBackPainterIcon: @Composable SeekBackButtonState.() -> Painter =
  @Composable {
    when (seekBackAmountMs) {
      in 2500..7500 -> painterResource(R.drawable.media3_icon_skip_back_5)
      in 7500..12500 -> painterResource(R.drawable.media3_icon_skip_back_10)
      in 12500..20000 -> painterResource(R.drawable.media3_icon_skip_back_15)
      in 20000..40000 -> painterResource(R.drawable.media3_icon_skip_back_30)
      else -> painterResource(R.drawable.media3_icon_skip_back)
    }
  }
