/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.docsamples

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.SeekBackButton
import androidx.media3.ui.compose.material3.buttons.SeekForwardButton
import androidx.media3.ui.compose.material3.indicator.DurationText
import androidx.media3.ui.compose.material3.indicator.PositionAndDurationText
import androidx.media3.ui.compose.material3.indicator.PositionText
import androidx.media3.ui.compose.material3.indicator.ProgressSlider

@OptIn(UnstableApi::class)
class ComposeMaterial3Snippets {

  @Composable
  fun PlayerControls(player: Player) {
    // [START android_compose_material3_player_controls]
    // The library provides styled UI components
    Row {
      SeekBackButton(player)
      PlayPauseButton(player)
      SeekForwardButton(player)
    }
    // [END android_compose_material3_player_controls]
  }

  // [START android_compose_material3_player_progress_controls]
  // You can rearrange the composables into a layout that suits your needs
  @Composable
  fun PlayerProgressControlsLeftAligned(player: Player) {
    Row {
      PositionAndDurationText(player)
      ProgressSlider(player)
    }
  }

  @Composable
  fun PlayerProgressControlsCenterAligned(player: Player) {
    Row {
      PositionText(player)
      ProgressSlider(player)
      DurationText(player)
    }
  }

  // [END android_compose_material3_player_progress_controls]

  @Composable
  fun ThemedPlayerControls(player: Player) {
    // [START android_compose_themed_player_controls]
    MaterialTheme(
      colorScheme =
        lightColorScheme(
          primary = Color.Red, // Change the primary color for the button
          onPrimary = Color.White,
        )
    ) {
      // The PlayPauseButton will now use the custom colors
      PlayPauseButton(player)
    }
    // [END android_compose_themed_player_controls]
  }
}
