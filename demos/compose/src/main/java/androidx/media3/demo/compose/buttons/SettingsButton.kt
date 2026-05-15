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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.ui.compose.buttons.ShuffleButton
import androidx.media3.ui.compose.material3.R
import androidx.media3.ui.compose.material3.buttons.RepeatButton

/** A Material3 [IconButton] with a settings icon. */
@Composable
fun SettingsButton(
  modifier: Modifier = Modifier,
  onSettingsClick: () -> Unit,
  colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
  interactionSource: MutableInteractionSource? = null,
) {
  IconButton(
    onClick = onSettingsClick,
    modifier = modifier,
    colors = colors,
    interactionSource = interactionSource,
  ) {
    Icon(
      painter = painterResource(R.drawable.media3_icon_settings),
      contentDescription = "Settings",
    )
  }
}

/** The content displayed inside a bottom sheet for settings selection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsBottomSheet(
  player: Player?,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  ModalBottomSheet(onDismissRequest = onDismissRequest, modifier = modifier) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      ListItem(
        headlineContent = { Text("Playback Speed") },
        trailingContent = {
          // Custom PlaybackSpeed button, brings up a bottom sheet, not default toggle
          PlaybackSpeedBottomSheetButton(
            player,
            colors =
              ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
          )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
      )
      ListItem(
        headlineContent = { Text("Shuffle Mode") },
        trailingContent = {
          // Custom shuffle button, switch-toggle, not icon button
          ShuffleButton(player) {
            Switch(checked = shuffleOn, onCheckedChange = { onClick() }, enabled = isEnabled)
          }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
      )
      ListItem(
        headlineContent = { Text("Repeat Mode") },
        trailingContent = {
          // Default repeat button
          RepeatButton(
            player = player,
            colors =
              IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
          )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
      )
    }
  }
}
