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

package androidx.media3.ui.compose.indicators

import androidx.annotation.IntRange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.ProgressStateWithTickCount
import androidx.media3.ui.compose.state.rememberProgressStateWithTickCount
import kotlinx.coroutines.CoroutineScope

/**
 * A state container for a progress indicator that provides progress updates from a [Player].
 *
 * This composable manages the state of the player's progress and exposes a
 * [ProgressStateWithTickCount] object to its [content] lambda. This allows for the creation of
 * custom progress indicators.
 *
 * @param player The [Player] to get the progress from.
 * @param totalTickCount The number of discrete values, evenly distributed across the whole duration
 *   of the current media item.
 * @param scope The [CoroutineScope] to use for listening to player progress updates.
 * @param content The composable content to be displayed for the progress indicator.
 */
@UnstableApi
@Composable
fun ProgressIndicator(
  player: Player?,
  @IntRange(from = 0) totalTickCount: Int = 0,
  scope: CoroutineScope = rememberCoroutineScope(),
  content: @Composable ProgressStateWithTickCount.() -> Unit,
) {
  rememberProgressStateWithTickCount(player, totalTickCount, scope).content()
}
