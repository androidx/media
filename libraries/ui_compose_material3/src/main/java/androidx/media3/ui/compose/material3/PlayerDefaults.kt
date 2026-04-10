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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    get() = IconButtonDefaults.iconButtonColors(contentColor = PlayerTokens.buttonContentColor)

  @get:Composable
  val defaultTextButtonColors: ButtonColors
    get() = ButtonDefaults.textButtonColors(contentColor = PlayerTokens.buttonContentColor)

  @Composable
  fun TopControls(player: Player?, visible: Boolean) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
      Row(
        modifier =
          Modifier.fillMaxWidth().padding(horizontal = PlayerTokens.ControlsHorizontalPadding),
        horizontalArrangement = Arrangement.End,
      ) {
        MuteButton(
          player,
          Modifier.background(PlayerTokens.controlsBackgroundColor, ButtonDefaults.shape),
          colors = defaultIconButtonColors,
        )
      }
    }
  }

  @Composable
  fun CenterControls(player: Player?, showControls: Boolean) {
    AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement =
          Arrangement.spacedBy(PlayerTokens.CenterControlsSpacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val buttonModifier =
          Modifier.size(PlayerTokens.CenterControlsButtonSize)
            .background(PlayerTokens.controlsBackgroundColor, ButtonDefaults.shape)
        PreviousButton(player, buttonModifier, colors = defaultIconButtonColors)
        SeekBackButton(player, buttonModifier, colors = defaultIconButtonColors)
        PlayPauseButton(player, buttonModifier, colors = defaultIconButtonColors)
        SeekForwardButton(player, buttonModifier, colors = defaultIconButtonColors)
        NextButton(player, buttonModifier, colors = defaultIconButtonColors)
      }
    }
  }

  @Composable
  fun BottomControls(player: Player?, showControls: Boolean) {
    AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
      Column(Modifier.fillMaxWidth().padding(horizontal = PlayerTokens.ControlsHorizontalPadding)) {
        ProgressSlider(player)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.Start,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          PositionAndDurationText(player, color = PlayerTokens.textColor)
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
    Box(modifier.fillMaxSize().background(PlayerTokens.shutterColor))
  }
}
