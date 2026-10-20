/*
 * Copyright 2026 The Android Open Source Project
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

package androidx.media3.demo.compose.buttons

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.demo.compose.R

/** A Material3 [IconButton] that enters fullscreen, or exits it if [isFullscreen] is `true`. */
@Composable
internal fun FullscreenButton(
  isFullscreen: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  iconSize: Dp = 24.dp,
  shape: Shape = IconButtonDefaults.standardShape,
  colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
  tint: Color = Color.Unspecified,
  interactionSource: MutableInteractionSource? = null,
) {
  IconButton(
    onClick = onClick,
    modifier = modifier,
    enabled = enabled,
    colors = colors,
    interactionSource = interactionSource,
    shape = shape,
  ) {
    Icon(
      modifier = Modifier.size(iconSize),
      painter =
        painterResource(
          if (isFullscreen) R.drawable.media3_icon_fullscreen_exit
          else R.drawable.media3_icon_fullscreen_enter
        ),
      contentDescription = if (isFullscreen) "Exit fullscreen" else "Enter fullscreen",
      tint = tint.takeOrElse { LocalContentColor.current },
    )
  }
}
