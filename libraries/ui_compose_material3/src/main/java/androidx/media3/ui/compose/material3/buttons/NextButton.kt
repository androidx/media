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

import androidx.annotation.VisibleForTesting
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.buttons.NextButton as NextButtonStateContainer
import androidx.media3.ui.compose.material3.R

/**
 * A Material3 [IconButton] that seeks to the next media item.
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param icon The icon to be displayed on the button.
 * @param contentDescription The content description for accessibility.
 */
@UnstableApi
@Composable
fun NextButton(
  player: Player,
  modifier: Modifier = Modifier,
  icon: ImageVector = Icons.Default.SkipNext,
  contentDescription: String = stringResource(R.string.next_button),
) {
  NextButtonStateContainer(player) {
    NextButton(modifier, icon, contentDescription, isEnabled, ::onClick)
  }
}

/**
 * A stateless Material3 [IconButton] for seeking to the next media item.
 *
 * @param modifier The [Modifier] to be applied to the button.
 * @param icon The icon to be displayed on the button.
 * @param contentDescription The content description for accessibility.
 * @param enabled Whether the button is enabled.
 * @param onClick The action to be performed when the button is clicked.
 */
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
@Preview
@Composable
fun NextButton(
  modifier: Modifier = Modifier,
  icon: ImageVector = Icons.Default.SkipNext,
  contentDescription: String = stringResource(R.string.next_button),
  enabled: Boolean = true,
  onClick: () -> Unit = {},
) {
  IconButton(onClick, modifier, enabled) { Icon(icon, contentDescription) }
}
