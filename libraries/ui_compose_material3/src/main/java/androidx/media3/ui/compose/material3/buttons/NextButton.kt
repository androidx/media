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

/**
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that seeks to the next media
 * item.
 *
 * When clicked, it attempts to advance the [player] to the next media item in its current playlist.
 * The button's state (e.g., whether it's enabled) is managed by a
 * [NextButtonState][androidx.media3.ui.compose.state.NextButtonState] instance derived from the
 * provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param painter The [Painter] used for the icon displayed on the button. Defaults to
 *   [R.drawable.media3_icon_next].
 * @param contentDescription The content description for accessibility purposes. Defaults to
 *   [R.string.next_button].
 */
@UnstableApi
@Composable
fun NextButton(
  player: Player,
  modifier: Modifier = Modifier,
  painter: Painter = painterResource(R.drawable.media3_icon_next),
  contentDescription: String = stringResource(R.string.next_button),
) {
  NextButtonStateContainer(player) {
    ClickableIconButton(
      modifier,
      isEnabled,
      icon = painter,
      contentDescription = contentDescription,
      onClick = ::onClick,
    )
  }
}

/**
 * A Material3 [IconButton][androidx.compose.material3.IconButton] that seeks to the next media
 * item.
 *
 * When clicked, it attempts to advance the [player] to the next media item in its current playlist.
 * The button's state (e.g., whether it's enabled) is managed by a
 * [NextButtonState][androidx.media3.ui.compose.state.NextButtonState] instance derived from the
 * provided [player].
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param imageVector The [ImageVector] used for the icon displayed on the button.
 * @param contentDescription The content description for accessibility purposes. Defaults to
 *   [R.string.next_button].
 */
@UnstableApi
@Composable
fun NextButton(
  player: Player,
  modifier: Modifier = Modifier,
  imageVector: ImageVector,
  contentDescription: String = stringResource(R.string.next_button),
) {
  NextButtonStateContainer(player) {
    ClickableIconButton(
      modifier,
      isEnabled,
      icon = imageVector,
      contentDescription = contentDescription,
      onClick = ::onClick,
    )
  }
}
