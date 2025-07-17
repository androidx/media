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

package androidx.media3.demo.compose.indicator

import androidx.annotation.IntRange
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.media3.common.Player
import androidx.media3.common.util.Util.getStringForTime
import androidx.media3.ui.compose.state.ProgressStateWithTickInterval
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval

/**
 * An example of a progress indicator that represents the [Player's][Player]
 * [ProgressStateWithTickInterval] in textual form.
 *
 * It displays the up-to-date current position and duration of the media at the granularity of the
 * provided [timeStepSeconds]. For example, for shorter videos, a 1-second granularity
 * (`timeSecondsStep = 1`) might be more appropriate, whereas for a multiple-hour long movie, a
 * 1-minute granularity (`timeSecondsStep = 60`) might be enough. Tuning this parameter can help you
 * avoid unnecessary recompositions.
 *
 * @param timeStepSeconds Delta of the media time that constitutes a progress step, in seconds.
 */
@Composable
fun TextProgressIndicator(
  player: Player,
  modifier: Modifier = Modifier,
  @IntRange(from = 0) timeStepSeconds: Int = 1,
) {
  val progressState =
    rememberProgressStateWithTickInterval(
      player,
      tickIntervalMs = timeStepSeconds.toLong().times(1000),
    )
  val current = getStringForTime(progressState.currentPositionMs)
  // duration will not change as often as current position
  val duration by remember { derivedStateOf { getStringForTime(progressState.durationMs) } }
  BasicText("$current - $duration", modifier)
}
