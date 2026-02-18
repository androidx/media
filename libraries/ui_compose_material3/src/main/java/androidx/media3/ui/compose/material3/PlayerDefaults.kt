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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.ui.compose.material3.buttons.MuteButton
import androidx.media3.ui.compose.material3.buttons.NextButton
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.PlaybackSpeedToggleButton
import androidx.media3.ui.compose.material3.buttons.PreviousButton
import androidx.media3.ui.compose.material3.buttons.RepeatButton
import androidx.media3.ui.compose.material3.buttons.SeekBackButton
import androidx.media3.ui.compose.material3.buttons.SeekForwardButton
import androidx.media3.ui.compose.material3.buttons.ShuffleButton
import androidx.media3.ui.compose.material3.indicator.PositionAndDurationText
import androidx.media3.ui.compose.material3.indicator.ProgressSlider

internal object PlayerDefaults {

  @get:Composable
  val defaultIconButtonColors: IconButtonColors
    get() = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)

  @get:Composable
  val defaultTextButtonColors: ButtonColors
    get() = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)

  @get:Composable
  val defaultTextColor: Color
    get() = MaterialTheme.colorScheme.primary

  @Composable
  fun TopControls(
    player: Player?,
    visible: Boolean,
    modifier: Modifier = Modifier,
    innerModifier: Modifier = Modifier,
  ) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
      Row(modifier = modifier, horizontalArrangement = Arrangement.End) {
        MuteButton(player, innerModifier, colors = defaultIconButtonColors)
      }
    }
  }

  @Composable
  fun CenterControls(
    player: Player?,
    showControls: Boolean,
    modifier: Modifier = Modifier,
    innerModifier: Modifier = Modifier,
  ) {
    AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
      Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        PreviousButton(player, innerModifier, colors = defaultIconButtonColors)
        SeekBackButton(player, innerModifier, colors = defaultIconButtonColors)
        PlayPauseButton(player, innerModifier, colors = defaultIconButtonColors)
        SeekForwardButton(player, innerModifier, colors = defaultIconButtonColors)
        NextButton(player, innerModifier, colors = defaultIconButtonColors)
      }
    }
  }

  @Composable
  fun BottomControls(player: Player?, showControls: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
      Column(modifier) {
        ProgressSlider(player)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.Start,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          PositionAndDurationText(player, color = defaultTextColor)
          Spacer(Modifier.weight(1f))
          PlaybackSpeedToggleButton(player, colors = defaultTextButtonColors)
          ShuffleButton(player, colors = defaultIconButtonColors)
          RepeatButton(player, colors = defaultIconButtonColors)
        }
      }
    }
  }

  @Composable
  fun Shutter(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
  }
}
