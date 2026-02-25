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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.base.Preconditions.checkState
import kotlinx.coroutines.CoroutineScope

/**
 * Remember the value of [ProgressStateWithTickCount] created based on the passed [Player] and
 * launch a coroutine to listen to [Player's][Player] changes. If the [Player] instance changes
 * between compositions, produce and remember a new value.
 *
 * [ProgressStateWithTickCount] is a state holder optimised for updates according to the discrete
 * number of ticks that make up the full progress. Non-textual UI components are limited in how much
 * progress they can show by their screen representation, such as the number of pixels. In this
 * case, it is more important to track progress in ticks/steps that correspond to a visible "jump"
 * rather than a particular real time or media time frequency. Make sure to tune the
 * [totalTickCount] value for the shape of the composable that is consuming this state. This will
 * help you keep the recomposition count low, since the updates will be triggered at the frequency
 * that matches the needs of the UI.
 *
 * @param player The player whose progress to observe.
 * @param totalTickCount If strictly greater than 0, specifies the amounts of discrete values,
 *   evenly distributed across the whole duration of the current media item. If 0,
 *   [ProgressStateWithTickCount.currentPositionProgress] and
 *   [ProgressStateWithTickCount.bufferedPositionProgress] will remain 0 until this value becomes
 *   positive. Must not be negative.
 * @param scope Coroutine scope whose context is used to launch the progress update job. When scoped
 *   to some UI element, the scope of the Composable will ensure the job is cancelled when the
 *   element is disposed.
 * @return The remembered [ProgressStateWithTickCount] instance.
 */
@UnstableApi
@Composable
fun rememberProgressStateWithTickCount(
  player: Player?,
  @IntRange(from = 0) totalTickCount: Int = 0,
  scope: CoroutineScope = rememberCoroutineScope(),
): ProgressStateWithTickCount {
  val progressState = remember(player) { ProgressStateWithTickCount(player, totalTickCount, scope) }
  LaunchedEffect(player) { progressState.observe() }
  LaunchedEffect(totalTickCount) { progressState.updateTotalTickCount(totalTickCount) }
  return progressState
}

/**
 * State that aims to hold accurate progress information (position, bufferedPosition, duration) that
 * is required for a UI component representing a non-textual progress indicator, e.g. circular or
 * linear. Such components are limited in how much progress they can show by their screen
 * representation, such as the number of pixels. In this case, it is more important to track
 * progress that can correspond to a visual jump rather than a particular media clock frequency.
 *
 * In most cases, this will be created via [rememberProgressStateWithTickCount].
 *
 * @param player The player whose progress to observe.
 * @param totalTickCount If strictly greater than 0, specifies the amounts of discrete values,
 *   evenly distributed across the whole duration of the current media item. If 0,
 *   [currentPositionProgress] and [bufferedPositionProgress] will remain 0 until this value becomes
 *   positive. Must not be negative.
 * @param scope Coroutine scope whose context is used to launch the progress update job. When scoped
 *   to some UI element, the scope of the Composable will ensure the job is cancelled when the
 *   element is disposed.
 * @property[currentPositionProgress] The progress of the current content of the Player as
 *   represented by [Player.getCurrentPosition]. The values range from 0.0 (represents no progress)
 *   and 1.0 (represents full progress and reaching [Player.getDuration]). Values outside of this
 *   range are coerced into the range. Values are rounded to the nearest multiple of
 *   1/totalTickCount.
 * @property[bufferedPositionProgress] An estimate of the progress in the current content or ad up
 *   to which data is buffered as represented by [Player.getBufferedPosition]. The values range from
 *   0.0 (represents no progress) and 1.0 (represents full progress and reaching
 *   [Player.getDuration]). Values outside of this range are coerced into the range. Values are
 *   rounded to the nearest multiple of 1/totalTickCount.
 */
@UnstableApi
class ProgressStateWithTickCount(
  private val player: Player?,
  @IntRange(from = 0) private var totalTickCount: Int = 0,
  scope: CoroutineScope,
) {
  var currentPositionProgress by mutableFloatStateOf(0f)
    private set

  var bufferedPositionProgress by mutableFloatStateOf(0f)
    private set

  /**
   * Whether the user is allowed to change the progress of the player, for example by dragging or
   * tapping a slider ob the UI side or programmatically calling [updateCurrentPositionProgress].
   *
   * This value is derived from the underlying [Player] state. It will be `true` only if
   * [Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM] is available and the duration of the current media
   * item is known (i.e., not [C.TIME_UNSET]).
   */
  var changingProgressEnabled by mutableStateOf(false)
    private set

  private val updateJob =
    player?.let {
      ProgressStateJob(
        player = it,
        scope = scope,
        nextMediaTickMsSupplier = { nextMediaWakeUpPositionMs(player = it) },
        shouldScheduleTask = {
          isReadyOrBuffering(player = it) &&
            canCalculateTicks(totalTickCount, getDurationMsOrDefault(player = it))
        },
        scheduledTask = { updateProgress(player = it) },
      )
    }

  /**
   * Dynamically set [totalTickCount] to another value with the change taking effect immediately,
   * leading to a change of position progress polling interval.
   */
  fun updateTotalTickCount(newTotalTickCount: Int) {
    if (totalTickCount != newTotalTickCount) {
      totalTickCount = newTotalTickCount
      updateJob?.cancelPendingUpdatesAndMaybeRelaunch()
    }
  }

  /**
   * Moves the player to a position represented by [progress] which is a value between 0..1,
   * effectively updating [currentPositionProgress].
   *
   * This method does nothing if [Player.duration] is unknown or unset or if
   * [Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM] is not available.
   */
  fun updateCurrentPositionProgress(progress: Float) {
    val player = player ?: return
    val durationMs = getDurationMsOrDefault(player)
    if (
      durationMs != C.TIME_UNSET &&
        durationMs > 0L &&
        player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
    ) {
      player.seekTo((progress * durationMs).toLong())
    }
  }

  /** Converts a progress value (0.0 to 1.0) to a position in the current media in milliseconds. */
  fun progressToPosition(progress: Float): Long {
    val player = player ?: return 0
    val durationMs = getDurationMsOrDefault(player)
    if (durationMs == C.TIME_UNSET || durationMs <= 0) {
      return 0
    }
    return (progress * durationMs).toLong()
  }

  init {
    require(totalTickCount >= 0)
  }

  /**
   * Subscribes to updates from [Player.Events] to track changes of progress-related information in
   * an asynchronous way.
   */
  suspend fun observe() = updateJob?.observeProgress()

  private fun nextMediaWakeUpPositionMs(player: Player): Long {
    checkState(totalTickCount != 0)
    val durationMs = getDurationMsOrDefault(player)
    checkState(durationMs != C.TIME_UNSET)
    val currentPositionTick =
      getPositionTick(getCurrentPositionMsOrDefault(player), durationMs, totalTickCount)
    val nextTickIndex = currentPositionTick + 1
    val midInterval = durationMs / (2 * totalTickCount)
    return (nextTickIndex * durationMs) / totalTickCount - midInterval
  }

  private fun updateProgress(player: Player) {
    val duration = getDurationMsOrDefault(player)
    currentPositionProgress =
      positionToProgress(getCurrentPositionMsOrDefault(player), duration, totalTickCount)
    bufferedPositionProgress =
      positionToProgress(getBufferedPositionMsOrDefault(player), duration, totalTickCount)
    changingProgressEnabled =
      player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) &&
        getDurationMsOrDefault(player) != C.TIME_UNSET
  }

  private fun getPositionTick(position: Long, duration: Long, totalTickCount: Int): Int {
    // 1. accounting for player position estimation jitter with 10ms correction/990ms rounding
    // 2. rounding from half an interval (unlike the stopwatch nature of ProgressStateWithInterval
    // where rounding only happened at the tick plus jitter). Round up the pixel half way through.
    val midInterval = duration / (2 * totalTickCount)
    return ((position + POSITION_CORRECTION_OFFSET_MS + midInterval) * totalTickCount / duration)
      .toInt()
      .coerceIn(0, totalTickCount)
  }

  private fun positionToProgress(positionMs: Long, durationMs: Long, totalTickCount: Int): Float {
    if (!canCalculateTicks(totalTickCount, durationMs)) {
      return 0f
    }
    val tickIndex = getPositionTick(positionMs, durationMs, totalTickCount)
    return tickIndex.toFloat() / totalTickCount
  }

  private fun canCalculateTicks(totalTickCount: Int, durationMs: Long): Boolean =
    totalTickCount != 0 && durationMs != C.TIME_UNSET && durationMs > 0L
}

private const val POSITION_CORRECTION_OFFSET_MS = 10
