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

package androidx.media3.demo.compose.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.demo.compose.editing.VideoCropper
import androidx.media3.demo.compose.editing.VideoCropperDefaults
import androidx.media3.demo.compose.editing.rememberVideoCropperState

private enum class AspectRatioPreset(val label: String, val ratio: Float?) {
  ORIGINAL("Original", null),
  SQUARE("1:1", 1f),
  SIXTEEN_NINE("16:9", 16f / 9f),
  NINE_SIXTEEN("9:16", 9f / 16f),
  FOUR_THREE("4:3", 4f / 3f),
  THREE_FOUR("3:4", 3f / 4f),
  THREE_TWO("3:2", 3f / 2f),
  TWO_THREE("2:3", 2f / 3f),
  FIVE_FOUR("5:4", 5f / 4f),
  FOUR_FIVE("4:5", 4f / 5f),
}

@Composable
internal fun VideoCropperScreen(player: Player?, modifier: Modifier = Modifier) {
  var selectedPreset by rememberSaveable { mutableStateOf(AspectRatioPreset.ORIGINAL) }
  val cropperState =
    rememberVideoCropperState(player = player, targetAspectRatio = selectedPreset.ratio)

  Column(
    modifier =
      modifier
        .background(MaterialTheme.colorScheme.background)
        .statusBarsPadding()
        .navigationBarsPadding()
  ) {
    VideoCropper(
      state = cropperState,
      modifier = Modifier.weight(1f).fillMaxWidth(),
      colors =
        VideoCropperDefaults.colors(
          idleOverlayColor = MaterialTheme.colorScheme.background,
          interactingOverlayColor = MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
          bracketColor = MaterialTheme.colorScheme.primary,
          borderColor = MaterialTheme.colorScheme.background,
        ),
    )

    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(16.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      for (preset in AspectRatioPreset.entries) {
        val isSelected = selectedPreset == preset
        val onClick = {
          selectedPreset = preset
          if (preset == AspectRatioPreset.ORIGINAL) {
            cropperState.cropRect = Rect(0f, 0f, 1f, 1f)
          }
        }
        if (isSelected) {
          Button(onClick = onClick) { Text(text = preset.label) }
        } else {
          OutlinedButton(onClick = onClick) { Text(text = preset.label) }
        }
      }
    }
  }
}
