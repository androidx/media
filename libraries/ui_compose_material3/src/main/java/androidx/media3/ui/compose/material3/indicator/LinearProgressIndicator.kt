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

package androidx.media3.ui.compose.material3.indicator

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.indicators.ProgressIndicator
import kotlinx.coroutines.CoroutineScope

/**
 * A Material3 [LinearProgressIndicator] that displays the current position of the player.
 *
 * @param player The [Player] to get the progress from.
 * @param modifier The [Modifier] to be applied to the progress indicator.
 */
@Composable
@UnstableApi
fun LinearProgressIndicator(player: Player?, modifier: Modifier = Modifier) {
  LinearProgressIndicator(
    player,
    modifier,
    rememberCoroutineScope(),
    MaterialTheme.colorScheme.primary,
  )
}

/**
 * A Material3 [LinearProgressIndicator] that displays the current position of the player.
 *
 * @param player The [Player] to get the progress from.
 * @param modifier The [Modifier] to be applied to the progress indicator.
 * @param scope The [CoroutineScope] to use for listening to player progress updates.
 * @param color The color of the progress indicator.
 */
@UnstableApi
@Composable
fun LinearProgressIndicator(
  player: Player?,
  modifier: Modifier = Modifier,
  scope: CoroutineScope = rememberCoroutineScope(),
  color: Color = MaterialTheme.colorScheme.primary,
) {
  var totalTickCount by remember { mutableIntStateOf(0) }
  ProgressIndicator(player, totalTickCount, scope) {
    LinearProgressIndicator(
      progress = { currentPositionProgress },
      modifier = modifier.fillMaxWidth().onSizeChanged { totalTickCount = it.width },
      color = color,
    )
  }
}
