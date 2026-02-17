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

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
internal fun ClickableIconButton(
  modifier: Modifier,
  enabled: Boolean,
  icon: Painter,
  contentDescription: String,
  colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
  tint: Color = Color.Unspecified,
  onClick: () -> Unit,
) {
  IconButton(onClick = onClick, modifier = modifier, enabled = enabled, colors = colors) {
    Icon(
      painter = icon,
      contentDescription = contentDescription,
      tint = tint.takeOrElse { LocalContentColor.current },
    )
  }
}

@Composable
internal fun ClickableIconButton(
  modifier: Modifier,
  enabled: Boolean,
  icon: ImageVector,
  contentDescription: String,
  colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
  tint: Color = Color.Unspecified,
  onClick: () -> Unit,
) {
  IconButton(onClick = onClick, modifier = modifier, enabled = enabled, colors = colors) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = tint.takeOrElse { LocalContentColor.current },
    )
  }
}
