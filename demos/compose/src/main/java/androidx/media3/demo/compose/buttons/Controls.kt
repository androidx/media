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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
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
  buttons: List<@Composable () -> Unit>,
  horizontalArrangement: Arrangement.Horizontal = Arrangement.Center,
  verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
  additionalSpacer: Float? = null,
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
@Composable
internal fun BoxScope.Controls(
  player: Player?,
  modifier: Modifier = Modifier.matchParentSize(),
  topBar: @Composable BoxScope.() -> Unit = {},
  centerControls: @Composable BoxScope.() -> Unit = { DefaultCenterControls(player) },
  bottomBar: @Composable BoxScope.() -> Unit = { DefaultBottomBar(player) },
) {
  Box(modifier) {
    Box(Modifier.align(Alignment.TopCenter)) { topBar() }
    Box(Modifier.align(Alignment.Center)) { centerControls() }
    Box(Modifier.align(Alignment.BottomCenter)) { bottomBar() }
  }
}

@Composable
private fun DefaultCenterControls(
  player: Player?,
  modifier: Modifier = Modifier,
  buttonModifier: Modifier =
    Modifier.size(50.dp).background(Color.Gray.copy(alpha = 0.1f), CircleShape),
  buttons: List<@Composable () -> Unit> =
    listOf(
      { PreviousButton(player, buttonModifier) },
      { SeekBackButton(player, buttonModifier) },
      { PlayPauseButton(player, buttonModifier) },
      { SeekForwardButton(player, buttonModifier) },
      { NextButton(player, buttonModifier) },
    ),
) {
  RowControls(modifier.fillMaxWidth(), buttons)
}

@Composable
private fun DefaultBottomBar(
  player: Player?,
  modifier: Modifier = Modifier,
  progressSlider: @Composable (Player?) -> Unit = {
    ProgressSlider(player = it, Modifier.padding(horizontal = 15.dp))
  },
  bottomRow: @Composable (Player?) -> Unit = {
    DefaultBottomRow(
      player = it,
      Modifier.background(Color.Gray.copy(alpha = 0.4f)).padding(horizontal = 15.dp),
    )
  },
) {
  Column(modifier.fillMaxWidth()) {
    progressSlider(player)
    bottomRow(player)
  }
}

@Composable
private fun DefaultBottomRow(player: Player?, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier,
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
