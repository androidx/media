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
import androidx.media3.common.listenTo
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class ProgressStateJob(
  private val player: Player,
  private val scope: CoroutineScope,
  private val nextMediaTickMsSupplier: () -> Long,
  private val shouldScheduleTask: () -> Boolean,
  private val scheduledTask: () -> Unit,
) {
  private var updateJob: Job? = null

  /**
   * Subscribes to updates from [Player.Events] to track changes of progress-related information in
   * an asynchronous way.
   */
  internal suspend fun observeProgress(): Nothing = coroutineScope {
    // otherwise we don't update on recomposition of UI, only on Player.Events
    cancelPendingUpdatesAndMaybeRelaunch()
    player.listenTo(
      Player.EVENT_IS_PLAYING_CHANGED,
      Player.EVENT_POSITION_DISCONTINUITY,
      Player.EVENT_TIMELINE_CHANGED,
      Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
      Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
    ) {
      scheduledTask()
      if (player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)) {
        cancelPendingUpdatesAndMaybeRelaunch()
      } else {
        updateJob?.cancel()
      }
    }
  }

  internal fun cancelPendingUpdatesAndMaybeRelaunch() {
    updateJob?.cancel()
    scheduledTask()
    if (shouldScheduleTask()) {
      updateJob =
        scope.launch {
          while (isActive) {
            smartDelay()
            scheduledTask()
          }
        }
    }
  }

  /**
   * Delay the polling of the [Player] until the current position reaches the value supplied by
   * [nextMediaTickMsSupplier]. If the time delta from until that next tick is less that
   * [MIN_UPDATE_INTERVAL_MS], the polling is suspended until the next frame is requested,
   * preventing unnecessarily frequent updates that will not be visible on the screen.
   *
   * Playback speed is taken into account as well, since it expands or shrinks the effective media
   * duration.
   */
  private suspend fun smartDelay() {
    if (player.isPlaying) {
      val mediaTimeToNextTickMs = nextMediaTickMsSupplier() - getCurrentPositionMsOrDefault(player)
      // Convert the interval to wall-clock time
      val realTimeToNextTickMs = mediaTimeToNextTickMs / player.playbackParameters.speed
      if (realTimeToNextTickMs < MIN_UPDATE_INTERVAL_MS) {
        // Throttle by recomposition frequency
        withFrameMillis {}
      } else {
        // Prevent infinite delays by 0
        delay(realTimeToNextTickMs.toLong().coerceAtLeast(1L))
      }
    } else {
      delay(FALLBACK_UPDATE_INTERVAL_MS)
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

internal fun isReadyOrBuffering(player: Player): Boolean =
  player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING

// Taking highest frame rate as 120fps, interval is 1000/120
@UnstableApi const val MIN_UPDATE_INTERVAL_MS = 8L
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
const val FALLBACK_UPDATE_INTERVAL_MS = 1000L
