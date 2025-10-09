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

package androidx.media3.ui.compose.state

import androidx.annotation.IntRange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope

/**
 * Remember the value of [ProgressStateWithTickInterval] created based on the passed [Player] and
 * launch a coroutine to listen to [Player's][Player] changes. If the [Player] instance changes
 * between compositions, produce and remember a new value.
 *
 * [ProgressStateWithTickInterval] is a state holder optimised for updates according to the media
 * clock at the provided [tickIntervalMs] interval. Make sure to tune the interval value for the
 * shape of the composable that is consuming this state.
 *
 * @param player The player whose progress to observe.
 * @param tickIntervalMs Delta of the media time that constitutes a progress step, in milliseconds.
 *   A value of 0 indicates continuous updates, coming at the rate of Compose runtime requesting new
 *   frames. The [ProgressStateWithTickInterval.currentPositionMs] will aim to be updated at the
 *   [tickIntervalMs] interval. The updates might sometimes be more frequent, or less frequent for
 *   small values of [tickIntervalMs] (where the screen is refreshing less frequently).
 * @param scope Coroutine scope whose context is used to launch the progress update job. When scoped
 *   to some UI element, the scope of the Composable will ensure the job is cancelled when the
 *   element is disposed.
 * @return The remembered [ProgressStateWithTickInterval] instance.
 */
@UnstableApi
@Composable
fun rememberProgressStateWithTickInterval(
  player: Player,
  @IntRange(from = 0) tickIntervalMs: Long = 0,
  scope: CoroutineScope = rememberCoroutineScope(),
): ProgressStateWithTickInterval {
  val progressStateWithTickInterval =
    remember(player, tickIntervalMs) {
      ProgressStateWithTickInterval(player, tickIntervalMs, scope)
    }
  LaunchedEffect(player) { progressStateWithTickInterval.observe() }
  return progressStateWithTickInterval
}

/**
 * State that holds playback progress information of a [Player]. This includes playback position,
 * buffered position and duration.
 *
 * This class is optimised for state changes according to the media clock. A composable UI element
 * that makes use of such [androidx.compose.runtime.MutableState] elements will recompose at the
 * frequency of those updates, leading to a more efficient performance.
 *
 * For example, for a textual-based UI, the current position usually requires "ticking" on the
 * second, on the minute or some other step definition according to the media clock rather than
 * elapsed real time. The coarseness of the interval can be carefully chosen as the granularity of
 * the displayed string.
 *
 * In most cases, this will be created via [rememberProgressStateWithTickInterval].
 *
 * @param player The player whose progress to observe.
 * @param tickIntervalMs Delta of the media time that constitutes a progress step/tick, in
 *   milliseconds. A value of 0 indicates continuous updates, coming at the rate of Compose runtime
 *   requesting new frames. The [currentPositionMs] will aim to be updated at the [tickIntervalMs]
 *   interval. The updates might sometimes be more frequent, or less frequent for small values of
 *   [tickIntervalMs] (where the screen is refreshing less frequently).
 * @param scope Coroutine scope whose context is used to launch the progress update job. When scoped
 *   to some UI element, the scope of the Composable will ensure the job is cancelled when the
 *   element is disposed.
 * @property[currentPositionMs] The playback position in the current content or ad, in milliseconds,
 *   matches [Player.getCurrentPosition] that is rounded to a multiple of [tickIntervalMs].
 * @property[bufferedPositionMs] An estimate of the position in the current content or ad up to
 *   which data is buffered, in milliseconds.
 * @property[durationMs] The duration of the current content or ad in milliseconds, matches
 *   [Player.getDuration].
 */
@UnstableApi
class ProgressStateWithTickInterval(
  private val player: Player,
  @IntRange(from = 0) private val tickIntervalMs: Long = 0,
  scope: CoroutineScope,
) {
  var currentPositionMs by mutableLongStateOf(0L)
    private set

  var bufferedPositionMs by mutableLongStateOf(0L)
    private set

  var durationMs by mutableLongStateOf(0L)
    private set

  private val updateJob =
    ProgressStateJob(
      player,
      scope,
      nextMediaTickMsSupplier = ::nextMediaWakeUpPositionMs,
      shouldScheduleTask = { isReadyOrBuffering(player) },
      scheduledTask = ::updateProgress,
    )

  init {
    require(tickIntervalMs >= 0)
    updateProgress()
  }

  /**
   * Subscribes to updates from [Player.Events] to track changes of progress-related information in
   * an asynchronous way.
   */
  suspend fun observe(): Nothing = updateJob.observeProgress()

  private fun nextMediaWakeUpPositionMs(): Long {
    if (tickIntervalMs == 0L) {
      return 0
    }
    val currentPositionMs = getCurrentPositionMsOrDefault(player)
    val nextTickIndex = currentPositionMs / tickIntervalMs + 1
    var nextMediaWakeUpPositionMs = nextTickIndex * tickIntervalMs
    val idealDelayDuration = nextMediaWakeUpPositionMs - currentPositionMs
    if (idealDelayDuration < MIN_UPDATE_INTERVAL_MS) {
      nextMediaWakeUpPositionMs = (nextTickIndex + 1) * tickIntervalMs
    }
    return nextMediaWakeUpPositionMs
  }

  private fun updateProgress() {
    currentPositionMs = snapPositionToNearestTick(::getCurrentPositionMsOrDefault)
    bufferedPositionMs = snapPositionToNearestTick(::getBufferedPositionMsOrDefault)
    durationMs = getDurationMsOrDefault(player)
  }

  /**
   * Round the actual position to the nearest tick (i.e. integer number of tickIntervals). Rounding
   * happens in a stop-watch manner, i.e. always down - hence integer division.
   *
   * Note how this is different to [ProgressStateWithTickCount] rounding that takes half of the
   * interval into account to round up.
   */
  private fun snapPositionToNearestTick(positionSupplier: (Player) -> Long): Long {
    val actualPositionMs = positionSupplier(player)
    if (tickIntervalMs == 0L || actualPositionMs % tickIntervalMs == 0L) {
      return actualPositionMs
    }
    val adjustedCurrentTick = (actualPositionMs + POSITION_CORRECTION_OFFSET_MS) / tickIntervalMs
    return adjustedCurrentTick * tickIntervalMs
  }
}

private const val POSITION_CORRECTION_OFFSET_MS = 10
