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

package androidx.media3.ui.compose.state

import androidx.media3.common.C
import androidx.media3.common.util.Clock
import kotlin.math.abs

/**
 * Utility class to handle calculating absolute and relative positions for live streams, including
 * compensating for player position estimation jitter during live manifest refreshes.
 */
internal class LiveProgressCalculator(private val clock: Clock = Clock.DEFAULT) {

  private var baseWindowStartTimeMs = C.TIME_UNSET
  private var baseRealTimeMs = C.TIME_UNSET
  private var lastSeenWindowStartTimeMs = C.TIME_UNSET
  private var lastWindowAdvanceRealTimeMs = C.TIME_UNSET
  private var windowAdvanceIntervalMs = C.TIME_UNSET
  private var lastSeenWindowDurationMs = C.TIME_UNSET
  private var isWindowSliding = true

  /**
   * Calculates the current position in milliseconds for a live stream. This uses a smoothed window
   * start time that tries to avoid sudden jumps when the underlying manifest updates the live
   * window.
   *
   * ```
   * Real Timeline:
   * -------------------------------------------------------------------->
   *
   * Manifest Timeline (discrete updates, can jitter on segment shifts):
   *              windowStartTimeMs
   *              |------------------------|----------------------------->
   *              0                        currentPositionMs (raw offset)
   *              \________________________/
   *                  absolutePositionMs = windowStartTimeMs + currentPositionMs
   * ```
   *
   * Smoothed Timeline (anchored at baseWindowStartTimeMs, advances in real time):
   * ```
   *   baseWindowStartTimeMs
   *   |===============> (realTimeMs - baseRealTimeMs)
   *                   \
   *             smoothWindowStartTimeMs
   *                    |------------------|
   *                    0          renderedRelativePositionMs
   *                               = absolutePositionMs - smoothWindowStartTimeMs
   * ```
   *
   * By subtracting the smoothly advancing window start time from the absolute position, estimation
   * jitter caused by periodic manifest refreshes is cancelled out.
   */
  fun calculateAvailableWindowPosition(
    currentPositionMs: Long,
    windowStartTimeMs: Long,
    durationMs: Long = C.TIME_UNSET,
    presentationStartTimeMs: Long = C.TIME_UNSET,
  ): Long {
    if (windowStartTimeMs == C.TIME_UNSET) {
      return currentPositionMs
    }
    val realTimeMs = clock.elapsedRealtime()
    maybeResyncSmoothWindowStartTime(
      windowStartTimeMs,
      durationMs,
      presentationStartTimeMs,
      realTimeMs,
    )
    val smoothWindowStartTimeMs =
      if (isWindowSliding) {
        baseWindowStartTimeMs + (realTimeMs - baseRealTimeMs)
      } else {
        windowStartTimeMs
      }
    val absolutePositionMs = windowStartTimeMs + currentPositionMs
    val renderedRelativePositionMs = absolutePositionMs - smoothWindowStartTimeMs
    return if (durationMs > 0) {
      renderedRelativePositionMs.coerceAtMost(durationMs)
    } else {
      renderedRelativePositionMs
    }
  }

  /**
   * Notifies the calculator that a timeline change occurred with the given [windowStartTimeMs] and
   * [windowDurationMs].
   *
   * If [windowStartTimeMs] is unchanged but the window grew, the window is expanding rather than
   * sliding, so extrapolation is paused until [windowStartTimeMs] advances again. Timeline updates
   * that change neither (for example, periodic manifest refreshes of a sliding window between two
   * segment boundaries) are ignored.
   */
  fun onTimelineChanged(windowStartTimeMs: Long, windowDurationMs: Long) {
    if (windowStartTimeMs == C.TIME_UNSET) {
      reset()
      return
    }
    val windowGrew =
      lastSeenWindowDurationMs != C.TIME_UNSET && windowDurationMs > lastSeenWindowDurationMs
    if (windowStartTimeMs == lastSeenWindowStartTimeMs && windowGrew) {
      isWindowSliding = false
      baseWindowStartTimeMs = windowStartTimeMs
      baseRealTimeMs = clock.elapsedRealtime()
    }
    lastSeenWindowDurationMs = windowDurationMs
  }

  /** Clears all smoothing state, so that the next calculation starts from the raw position. */
  fun reset() {
    baseWindowStartTimeMs = C.TIME_UNSET
    baseRealTimeMs = C.TIME_UNSET
    lastSeenWindowStartTimeMs = C.TIME_UNSET
    lastWindowAdvanceRealTimeMs = C.TIME_UNSET
    windowAdvanceIntervalMs = C.TIME_UNSET
    lastSeenWindowDurationMs = C.TIME_UNSET
    isWindowSliding = true
  }

  private fun maybeResyncSmoothWindowStartTime(
    windowStartTimeMs: Long,
    durationMs: Long,
    presentationStartTimeMs: Long,
    realTimeMs: Long,
  ) {
    if (baseWindowStartTimeMs == C.TIME_UNSET) {
      baseWindowStartTimeMs = windowStartTimeMs
      baseRealTimeMs = realTimeMs
      lastSeenWindowStartTimeMs = windowStartTimeMs
      lastWindowAdvanceRealTimeMs = realTimeMs
      if (lastSeenWindowDurationMs == C.TIME_UNSET) {
        lastSeenWindowDurationMs = durationMs
      }
      isWindowSliding =
        presentationStartTimeMs == C.TIME_UNSET || windowStartTimeMs > presentationStartTimeMs
      return
    }

    // Evaluate drift when a new window start time is reported by the player (e.g. from a manifest
    // refresh). Between manifest refreshes, the smoothed window timeline advances continuously in
    // real time, cancelling out discrete segment shifts. If a manifest update arrives with
    // significant drift caused by a stream discontinuity or seek, resync the base anchor
    // immediately.
    if (windowStartTimeMs != lastSeenWindowStartTimeMs) {
      val windowAdvanced = windowStartTimeMs > lastSeenWindowStartTimeMs
      if (windowAdvanced) {
        windowAdvanceIntervalMs = realTimeMs - lastWindowAdvanceRealTimeMs
      }
      if (!isWindowSliding && windowAdvanced) {
        isWindowSliding = true
        baseWindowStartTimeMs = windowStartTimeMs
        baseRealTimeMs = realTimeMs
      } else {
        val windowDriftMs = windowStartTimeMs - baseWindowStartTimeMs
        val realTimeDriftMs = realTimeMs - baseRealTimeMs
        if (!windowAdvanced || abs(realTimeDriftMs - windowDriftMs) > DRIFT_RESYNC_THRESHOLD_MS) {
          baseWindowStartTimeMs = windowStartTimeMs
          baseRealTimeMs = realTimeMs
        }
        isWindowSliding = windowAdvanced
      }
      lastSeenWindowStartTimeMs = windowStartTimeMs
      lastWindowAdvanceRealTimeMs = realTimeMs
    } else if (
      isWindowSliding && realTimeMs - lastWindowAdvanceRealTimeMs > extrapolationTimeoutMs()
    ) {
      // If the window start time has not advanced for a while, stop extrapolating so that playback
      // progress is not frozen on a non-advancing window.
      isWindowSliding = false
      baseWindowStartTimeMs = windowStartTimeMs
      baseRealTimeMs = realTimeMs
    }
  }

  /**
   * Returns how long to keep extrapolating without the window start time advancing. Adapts to the
   * observed interval between window advances, so that streams with long segments (e.g. 6s HLS
   * segments) are not mistaken for non-sliding windows between two segment boundaries.
   */
  private fun extrapolationTimeoutMs(): Long =
    if (windowAdvanceIntervalMs == C.TIME_UNSET) {
      MIN_EXTRAPOLATION_TIMEOUT_MS
    } else {
      maxOf(MIN_EXTRAPOLATION_TIMEOUT_MS, 2 * windowAdvanceIntervalMs)
    }

  private companion object {
    const val DRIFT_RESYNC_THRESHOLD_MS = 2_000L
    const val MIN_EXTRAPOLATION_TIMEOUT_MS = 5_000L
  }
}
