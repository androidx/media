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

package androidx.media3.demo.compose.buttons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.ui.compose.material3.buttons.MuteButton
import androidx.media3.ui.compose.material3.buttons.NextButton
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.PlaybackSpeedBottomSheetButton
import androidx.media3.ui.compose.material3.buttons.PreviousButton
import androidx.media3.ui.compose.material3.buttons.RepeatButton
import androidx.media3.ui.compose.material3.buttons.SeekBackButton
import androidx.media3.ui.compose.material3.buttons.SeekForwardButton
import androidx.media3.ui.compose.material3.buttons.ShuffleButton
import androidx.media3.ui.compose.material3.indicator.PositionAndDurationText
import androidx.media3.ui.compose.material3.indicator.ProgressSlider

@Composable
private fun RowControls(
  modifier: Modifier = Modifier,
  horizontalArrangement: Arrangement.Horizontal = Arrangement.Center,
  verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
  additionalSpacer: Float? = null,
  buttons: List<@Composable () -> Unit>,
) {
  Row(modifier, horizontalArrangement, verticalAlignment) {
    buttons.forEachIndexed { index, button ->
      button()
      if (index < buttons.lastIndex && additionalSpacer != null) {
        Spacer(Modifier.weight(additionalSpacer))
      }
    }
  }
}

/**
 * All controls - buttons and progress indicators
 *
 * ```
 * |----------------------------------------------------|
 * |----------------------------------------------------|
 * |----------------------------------------------------|
 * |----Prev--SeekBack--PlayPause--SeekForward--Next----|
 * |----------------------------------------------------|
 * |----------------------------------------------------|
 * |xxxxxxxxxxxxxxxxxxxxxxxxxxxO------------------------|
 * |00:01-02:34-----------Speed--Shuffle--Repeat--Mute--|
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoxScope.Controls(player: Player) {
  val buttonModifier = Modifier.size(50.dp).background(Color.Gray.copy(alpha = 0.1f), CircleShape)
  // Central controls
  RowControls(
    Modifier.fillMaxWidth().align(Alignment.Center),
    buttons =
      listOf(
        { PreviousButton(player, buttonModifier) },
        { SeekBackButton(player, buttonModifier) },
        { PlayPauseButton(player, buttonModifier) },
        { SeekForwardButton(player, buttonModifier) },
        { NextButton(player, buttonModifier) },
      ),
  )
  // Button panel controls
  Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
    ProgressSlider(player, Modifier.fillMaxWidth().padding(horizontal = 15.dp))
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .background(Color.Gray.copy(alpha = 0.4f))
          .padding(horizontal = 15.dp),
      horizontalArrangement = Arrangement.Start,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      PositionAndDurationText(player)
      Spacer(Modifier.weight(1f))
      PlaybackSpeedBottomSheetButton(
        player,
        colors = ButtonDefaults.textButtonColors(contentColor = Color.Black),
      )
      ShuffleButton(player)
      RepeatButton(player)
      MuteButton(player)
    }
  }
}
