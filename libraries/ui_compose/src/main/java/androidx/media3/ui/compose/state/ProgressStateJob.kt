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

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.withFrameMillis
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.listen
import androidx.media3.common.util.Assertions.checkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class ProgressStateJob(
  private val player: Player,
  private val scope: CoroutineScope,
  private val intervalMsSupplier: () -> Long,
  private val scheduledTask: () -> Unit,
) {
  private var updateJob: Job? = null

  /**
   * Subscribes to updates from [Player.Events] to track changes of progress-related information in
   * an asynchronous way.
   */
  internal suspend fun observeProgress(): Nothing = coroutineScope {
    // otherwise we don't update on recomposition of UI, only on Player.Events
    cancelPendingUpdatesAndRelaunch()
    player.listen { events ->
      if (
        events.containsAny(
          Player.EVENT_IS_PLAYING_CHANGED,
          Player.EVENT_POSITION_DISCONTINUITY,
          Player.EVENT_TIMELINE_CHANGED,
          Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
          Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
        )
      ) {
        scheduledTask()
        if (player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)) {
          cancelPendingUpdatesAndRelaunch()
        } else {
          updateJob?.cancel()
        }
      }
    }
  }

  internal fun cancelPendingUpdatesAndRelaunch() {
    updateJob?.cancel()
    scheduledTask()
    updateJob =
      scope.launch {
        while (isActive) {
          smartDelay()
          scheduledTask()
        }
      }
  }

  /**
   * Delay the polling of the [Player] by the time delta that corresponds to the interval supplied
   * by [intervalMsSupplier]. If the duration of one step is zero, the polling is suspended until
   * the next frame is requested, preventing unnecessarily frequent updates that will not be visible
   * on the screen.
   *
   * Playback speed is taken into account as well, since it expands or shrinks the effective media
   * duration.
   */
  private suspend fun smartDelay() {
    val oneStepDurationMs = intervalMsSupplier()
    checkState(
      oneStepDurationMs >= 0 || oneStepDurationMs == C.TIME_UNSET,
      "Provided intervalMsSupplier is negative: $oneStepDurationMs",
    )
    if (player.isPlaying && oneStepDurationMs != C.TIME_UNSET) {
      if (oneStepDurationMs < MIN_UPDATE_INTERVAL_MS * player.playbackParameters.speed) {
        // oneStepDurationMs == 0 is a requested continuous update,
        // otherwise throttled by Recomposition frequency
        withFrameMillis {}
      } else {
        val mediaTimeToNextStepMs = oneStepDurationMs - player.currentPosition % oneStepDurationMs
        // Convert the specified interval to wall-clock time
        var realTimeToNextStepMs = mediaTimeToNextStepMs / player.playbackParameters.speed
        // Prevent unnecessarily short sleep which results in increased scheduledTask() frequency
        if (realTimeToNextStepMs < MIN_UPDATE_INTERVAL_MS) {
          realTimeToNextStepMs += oneStepDurationMs / player.playbackParameters.speed
        }
        // Prevent infinite delays by 0
        delay(realTimeToNextStepMs.toLong().coerceAtLeast(1L))
      }
    } else if (
      (player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE) ||
        oneStepDurationMs == C.TIME_UNSET
    ) {
      delay(PAUSED_UPDATE_INTERVAL_MS)
    }
  }
}

internal fun getCurrentPositionMsOrDefault(player: Player): Long {
  return if (player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)) {
    player.currentPosition
  } else {
    0
  }
}

internal fun getBufferedPositionMsOrDefault(player: Player): Long {
  return if (player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)) {
    player.bufferedPosition
  } else {
    0
  }
}

internal fun getDurationMsOrDefault(player: Player): Long {
  return if (player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)) {
    player.duration
  } else {
    C.TIME_UNSET
  }
}

// Taking highest frame rate as 120fps, interval is 1000/120
private const val MIN_UPDATE_INTERVAL_MS = 8L
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
const val PAUSED_UPDATE_INTERVAL_MS = 1000L
