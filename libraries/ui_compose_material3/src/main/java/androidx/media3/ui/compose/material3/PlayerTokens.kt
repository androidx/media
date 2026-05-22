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
package androidx.media3.ui.compose.material3

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal object PlayerTokens {
  val MediumButtonSize = 50.dp
  val LargeButtonSize = 63.dp
  val MediumIconSize: Dp = 24.dp
  val LargeIconSize: Dp = 40.dp
  val CenterControlsSpacing = 10.dp
  val ControlsHorizontalPadding = 15.dp
  val BottomControlsHorizontalPadding = 8.dp

  val bottomControlsGradient: Brush
    @Composable
    get() =
      Brush.verticalGradient(
        colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background)
      )

  val shutterColor: Color
    @Composable get() = MaterialTheme.colorScheme.scrim

  val controlsBackgroundColor: Color
    @Composable get() = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
}
