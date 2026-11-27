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
import androidx.media3.ui.compose.state.ProgressStateWithTickInterval
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import kotlinx.coroutines.CoroutineScope

/**
 * A Composable that provides player progress information to a content lambda, allowing for the
 * creation of custom progress indicators.
 *
 * This function does not render any UI itself. Instead, it manages the state of the player's
 * progress and exposes a [ProgressStateWithTickInterval] object to its [content] lambda. This
 * allows for complete control over the layout and appearance of the progress display.
 *
 * Note that the reliance on [ProgressStateWithTickInterval] implies that the UI responsiveness and
 * precision is optimised with the media-clock in mind. That makes it most suitable for displaying
 * time-based text progress (rather than pixel-based slider/bar), hence the name of this Composable.
 *
 * It serves as a state provider, decoupling the logic of listening to player progress from the UI
 * presentation, which is a flexible pattern recommended for building reusable Composables.
 *
 * Example usage:
 * ```
 * TimeText(player) {
 *   // `this` is a `ProgressStateWithTickInterval`
 *   val currentPosition = Util.getStringForTime(this.currentPositionMs)
 *   val duration = Util.getStringForTime(this.durationMs)
 *   Text("$currentPosition / $duration")
 * }
 * ```
 *
 * @param player The [Player] to get the progress from.
 * @param tickIntervalMs The granularity of the progress updates in milliseconds. A smaller value
 *   means more frequent updates, which may cause more recompositions.
 * @param scope The [CoroutineScope] to use for listening to player progress updates.
 * @param content A content lambda that receives a [ProgressStateWithTickInterval] as its receiver
 *   scope, which can be used to build a custom UI.
 */
@UnstableApi
@Composable
fun TimeText(
  player: Player?,
  @IntRange(from = 0) tickIntervalMs: Int = 1000,
  scope: CoroutineScope = rememberCoroutineScope(),
  content: @Composable ProgressStateWithTickInterval.() -> Unit,
) {
  rememberProgressStateWithTickInterval(player, tickIntervalMs = tickIntervalMs.toLong(), scope)
    .content()
}
