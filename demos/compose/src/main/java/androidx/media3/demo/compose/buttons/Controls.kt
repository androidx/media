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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.demo.compose.indicator.HorizontalLinearProgressIndicator
import androidx.media3.demo.compose.indicator.TextProgressIndicator
import androidx.media3.ui.compose.material3.buttons.MuteButton
import androidx.media3.ui.compose.material3.buttons.NextButton
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.PreviousButton
import androidx.media3.ui.compose.material3.buttons.RepeatButton
import androidx.media3.ui.compose.material3.buttons.SeekBackButton
import androidx.media3.ui.compose.material3.buttons.SeekForwardButton
import androidx.media3.ui.compose.material3.buttons.ShuffleButton

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
    HorizontalLinearProgressIndicator(player, Modifier.fillMaxWidth())
    Row(
      modifier = Modifier.fillMaxWidth().background(Color.Gray.copy(alpha = 0.4f)),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextProgressIndicator(player, Modifier.align(Alignment.CenterVertically))
      Spacer(Modifier.weight(1f))
      PlaybackSpeedPopUpButton(player)
      ShuffleButton(player)
      RepeatButton(player)
      MuteButton(player)
    }
  }
}
