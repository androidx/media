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
import androidx.media3.test.utils.FakeClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [LiveProgressCalculator]. */
@RunWith(AndroidJUnit4::class)
class LiveProgressCalculatorTest {

  private val fakeClock = FakeClock(/* initialTimeMs= */ 0L)
  private lateinit var calculator: LiveProgressCalculator

  @Before
  fun setUp() {
    calculator = LiveProgressCalculator(fakeClock)
  }

  @Test
  fun calculateAvailableWindowPosition_whenWindowStartTimeUnset_returnsCurrentPosition() {
    val result =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = 5_000L,
        windowStartTimeMs = C.TIME_UNSET,
      )

    assertThat(result).isEqualTo(5_000L)
  }

  @Test
  fun calculateAvailableWindowPosition_withoutManifestUpdate_doesNotJumpAtTwoSeconds() {
    val windowStartTimeMs = 100_000L
    val initialCurrentPositionMs = 20_000L

    val posAt0s =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = initialCurrentPositionMs,
        windowStartTimeMs = windowStartTimeMs,
      )
    assertThat(posAt0s).isEqualTo(initialCurrentPositionMs)

    // Advance real time by 2.5 seconds (past the 2.0s drift threshold) with player advancing by
    // 2.5s
    fakeClock.advanceTime(2_500)
    val posAt2500ms =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = initialCurrentPositionMs + 2_500L,
        windowStartTimeMs = windowStartTimeMs,
      )

    // Position relative to the smoothed window stays continuous without a sudden 2s jump
    assertThat(posAt2500ms).isEqualTo(initialCurrentPositionMs)
  }

  @Test
  fun calculateAvailableWindowPosition_withDiscontinuity_resyncsPosition() {
    val windowStartTimeMs = 100_000L
    val initial =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = 10_000L,
        windowStartTimeMs = windowStartTimeMs,
      )
    assertThat(initial).isEqualTo(10_000L)

    // Advance real time by 1s, but window jump by 60s (large discontinuity > 2s threshold)
    fakeClock.advanceTime(1_000)
    val newWindowStartTimeMs = windowStartTimeMs + 60_000L
    val result =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = 10_000L,
        windowStartTimeMs = newWindowStartTimeMs,
      )

    // Resyncs immediately to the new window anchor
    assertThat(result).isEqualTo(10_000L)
  }

  @Test
  fun calculateAvailableWindowPosition_withCatchUpPlayback_advancesRelativeToLiveWindow() {
    val windowStartTimeMs = 100_000L
    val initialPositionMs = 20_000L

    val initial =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = initialPositionMs,
        windowStartTimeMs = windowStartTimeMs,
      )
    assertThat(initial).isEqualTo(initialPositionMs)

    // Advance real time by 1s (1_000ms), but player plays at 1.1x speed (1_100ms advanced)
    fakeClock.advanceTime(1_000)
    val result =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = initialPositionMs + 1_100L,
        windowStartTimeMs = windowStartTimeMs,
      )

    // Player position relative to the smoothed window advances by 100ms
    assertThat(result).isEqualTo(initialPositionMs + 100L)
  }

  @Test
  fun calculateAvailableWindowPosition_withPeriodicManifestUpdates_maintainsSmoothPositionAcrossTenSeconds() {
    var windowStartTimeMs = 100_000L
    val initialPositionMs = 20_000L

    val posAt0s =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = initialPositionMs,
        windowStartTimeMs = windowStartTimeMs,
      )
    assertThat(posAt0s).isEqualTo(initialPositionMs)

    // Simulate 10 seconds of playback at 1.0x speed, with manifest refreshes every 2 seconds
    for (second in 1..10) {
      fakeClock.advanceTime(1_000)
      if (second % 2 == 0) {
        // Manifest updates: window slides forward by 2 seconds
        windowStartTimeMs += 2_000L
      }
      // ExoPlayer position is relative to windowStartTimeMs:
      // absolute position = 100_000 + initialPositionMs + second * 1_000
      // currentPosition = absolute position - windowStartTimeMs
      val currentPositionMs = (100_000L + initialPositionMs + second * 1_000L) - windowStartTimeMs
      val renderedPositionMs =
        calculator.calculateAvailableWindowPosition(
          currentPositionMs = currentPositionMs,
          windowStartTimeMs = windowStartTimeMs,
        )
      // At 1.0x playback speed, position relative to the sliding window remains continuous
      assertThat(renderedPositionMs).isEqualTo(initialPositionMs)
    }
  }

  @Test
  fun calculateAvailableWindowPosition_windowAtPresentationStart_advancesPosition() {
    val presentationStartTimeMs = 100_000L
    val windowStartTimeMs = 100_000L

    val posAt0s =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = 5_000L,
        windowStartTimeMs = windowStartTimeMs,
        presentationStartTimeMs = presentationStartTimeMs,
      )
    assertThat(posAt0s).isEqualTo(5_000L)

    fakeClock.advanceTime(2_000)
    val posAt2s =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = 7_000L,
        windowStartTimeMs = windowStartTimeMs,
        presentationStartTimeMs = presentationStartTimeMs,
      )

    // Since windowStartTimeMs has not advanced past presentationStartTimeMs, progress increases
    assertThat(posAt2s).isEqualTo(7_000L)
  }

  @Test
  fun onTimelineChanged_expandingWindow_advancesPosition() {
    val windowStartTimeMs = 150_000L

    val posAt0s =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = 10_000L,
        windowStartTimeMs = windowStartTimeMs,
        durationMs = 50_000L,
      )
    assertThat(posAt0s).isEqualTo(10_000L)

    // Manifest update occurs, windowStartTimeMs does not advance but the window grows
    fakeClock.advanceTime(2_000)
    calculator.onTimelineChanged(windowStartTimeMs = windowStartTimeMs, windowDurationMs = 52_000L)

    val posAt2s =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = 12_000L,
        windowStartTimeMs = windowStartTimeMs,
        durationMs = 52_000L,
      )
    assertThat(posAt2s).isEqualTo(12_000L)
  }

  @Test
  fun onTimelineChanged_unchangedWindowBetweenSegments_keepsSmoothPosition() {
    // DASH-like stream: the window slides by 2s every 2s, and an extra timeline update with an
    // unchanged window is reported between two segment boundaries.
    var windowStartTimeMs = 100_000L
    val durationMs = 60_000L
    val initialPositionMs = 30_000L
    val posAt0s =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = initialPositionMs,
        windowStartTimeMs = windowStartTimeMs,
        durationMs = durationMs,
      )
    assertThat(posAt0s).isEqualTo(initialPositionMs)

    for (second in 1..10) {
      fakeClock.advanceTime(1_000)
      if (second % 2 == 0) {
        windowStartTimeMs += 2_000L
      } else {
        calculator.onTimelineChanged(windowStartTimeMs, durationMs)
      }
      val currentPositionMs = (100_000L + initialPositionMs + second * 1_000L) - windowStartTimeMs
      val renderedPositionMs =
        calculator.calculateAvailableWindowPosition(
          currentPositionMs = currentPositionMs,
          windowStartTimeMs = windowStartTimeMs,
          durationMs = durationMs,
        )

      assertThat(renderedPositionMs).isEqualTo(initialPositionMs)
    }
  }

  @Test
  fun calculateAvailableWindowPosition_windowAdvancingEverySixSeconds_keepsSmoothPosition() {
    // HLS-like stream with 6s segments, starting 3s before the next segment boundary.
    var windowStartTimeMs = 100_000L
    val durationMs = 60_000L
    val initialPositionMs = 30_000L
    val posAt0s =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = initialPositionMs,
        windowStartTimeMs = windowStartTimeMs,
        durationMs = durationMs,
      )
    assertThat(posAt0s).isEqualTo(initialPositionMs)

    val renderedPositionsMs = mutableListOf<Long>()
    for (step in 1..60) {
      fakeClock.advanceTime(500)
      if (step % 12 == 6) {
        windowStartTimeMs += 6_000L
      }
      val currentPositionMs = (100_000L + initialPositionMs + step * 500L) - windowStartTimeMs
      renderedPositionsMs +=
        calculator.calculateAvailableWindowPosition(
          currentPositionMs = currentPositionMs,
          windowStartTimeMs = windowStartTimeMs,
          durationMs = durationMs,
        )
    }

    // The smoothed window re-anchors once on the first window advance, then stays continuous across
    // all following segment boundaries, including when no advance happens for more than 5s.
    assertThat(renderedPositionsMs.drop(5).distinct()).containsExactly(27_000L)
  }

  @Test
  fun onTimelineChanged_unsetWindowStart_resetsSmoothing() {
    val windowStartTimeMs = 100_000L
    val posAt0s =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = 10_000L,
        windowStartTimeMs = windowStartTimeMs,
      )
    assertThat(posAt0s).isEqualTo(10_000L)
    fakeClock.advanceTime(1_000)

    calculator.onTimelineChanged(windowStartTimeMs = C.TIME_UNSET, windowDurationMs = C.TIME_UNSET)

    val result =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = 11_000L,
        windowStartTimeMs = windowStartTimeMs,
      )
    assertThat(result).isEqualTo(11_000L)
  }

  @Test
  fun calculateAvailableWindowPosition_stalledWindowStart_advancesPositionAfterTimeout() {
    val windowStartTimeMs = 150_000L

    val posAt0s =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = 10_000L,
        windowStartTimeMs = windowStartTimeMs,
      )
    assertThat(posAt0s).isEqualTo(10_000L)

    // Advance past the 5s extrapolation timeout without windowStartTimeMs advancing
    fakeClock.advanceTime(6_000)
    val posAt6s =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = 16_000L,
        windowStartTimeMs = windowStartTimeMs,
      )
    assertThat(posAt6s).isEqualTo(16_000L)
  }

  @Test
  fun calculateAvailableWindowPosition_exceedingDuration_clampsToDuration() {
    val windowStartTimeMs = 100_000L
    val result =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = 55_000L,
        windowStartTimeMs = windowStartTimeMs,
        durationMs = 50_000L,
      )

    assertThat(result).isEqualTo(50_000L)
  }

  @Test
  fun calculateAvailableWindowPosition_behindWindowStart_returnsNegativePosition() {
    var windowStartTimeMs = 100_000L
    val posAt0s =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = 10_000L,
        windowStartTimeMs = windowStartTimeMs,
        durationMs = 60_000L,
      )
    assertThat(posAt0s).isEqualTo(10_000L)

    // Simulate pausing while the live window slides forward by 30s (in 2s steps), so the paused
    // position (absolute 110_000ms) falls 20s behind the new window start (130_000ms).
    for (step in 1..15) {
      fakeClock.advanceTime(2_000)
      windowStartTimeMs += 2_000L
      val currentPositionMs = 110_000L - windowStartTimeMs
      val renderedPositionMs =
        calculator.calculateAvailableWindowPosition(
          currentPositionMs = currentPositionMs,
          windowStartTimeMs = windowStartTimeMs,
          durationMs = 60_000L,
        )
      assertThat(renderedPositionMs).isEqualTo(10_000L - step * 2_000L)
    }
  }

  @Test
  fun reset_clearsState() {
    val windowStartTimeMs = 100_000L
    val initial =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = 10_000L,
        windowStartTimeMs = windowStartTimeMs,
      )
    assertThat(initial).isEqualTo(10_000L)
    // Without a reset, the smoothed window would have advanced by 1s, rendering 10_000.
    fakeClock.advanceTime(1_000)

    calculator.reset()

    val resultAfterReset =
      calculator.calculateAvailableWindowPosition(
        currentPositionMs = 11_000L,
        windowStartTimeMs = windowStartTimeMs,
      )
    assertThat(resultAfterReset).isEqualTo(11_000L)
  }
}
