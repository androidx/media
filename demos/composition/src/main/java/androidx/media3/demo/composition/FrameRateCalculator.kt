/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.demo.composition

import androidx.annotation.OptIn
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.LongArrayQueue

/**
 * Calculates the frame rate using a moving average window.
 *
 * This class is not thread-safe.
 *
 * Note: Although this class is designed to be testable by accepting a [Clock] instance, the demo
 * modules currently do not have a unit test framework configured.
 */
@OptIn(ExperimentalApi::class)
internal class FrameRateCalculator(
  private val clock: Clock = Clock.DEFAULT,
  private val windowDurationMs: Long = 3000L,
  private val minUpdateIntervalMs: Long = 250L,
) {
  private val frameTimestampsMs = LongArrayQueue()
  private var lastFpsUpdateTimeMs = 0L
  private var lastCalculatedFps = 0f

  /**
   * Registers a frame release time and returns the updated FPS if the minimum update interval has
   * elapsed since the last update, or null otherwise.
   */
  fun registerFrameAndGetFps(nowMs: Long = clock.elapsedRealtime()): Float? {
    frameTimestampsMs.add(nowMs)

    // Prune timestamps outside the moving average window
    var oldestMs = nowMs
    while (!frameTimestampsMs.isEmpty()) {
      oldestMs = frameTimestampsMs.element()
      if (nowMs - oldestMs > windowDurationMs) {
        frameTimestampsMs.remove()
      } else {
        break
      }
    }

    val durationMs = nowMs - oldestMs
    val frameCount = frameTimestampsMs.size()
    if (durationMs > 0 && frameCount > 1) {
      lastCalculatedFps = (frameCount - 1).toFloat() * 1000f / durationMs
    } else {
      lastCalculatedFps = 0f
    }

    if (nowMs - lastFpsUpdateTimeMs >= minUpdateIntervalMs) {
      lastFpsUpdateTimeMs = nowMs
      return lastCalculatedFps
    }
    return null
  }

  /** Resets the calculator's state. */
  fun reset() {
    frameTimestampsMs.clear()
    lastFpsUpdateTimeMs = 0L
    lastCalculatedFps = 0f
  }
}
