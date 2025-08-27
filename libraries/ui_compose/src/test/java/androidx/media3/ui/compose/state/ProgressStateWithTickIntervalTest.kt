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

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_GET_CURRENT_MEDIA_ITEM
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.test.utils.TestSimpleBasePlayer
import androidx.media3.ui.compose.testutils.advanceTimeByInclusive
import androidx.media3.ui.compose.testutils.createReadyPlayerWithSingleItem
import androidx.media3.ui.compose.testutils.rememberCoroutineScopeWithBackgroundCancellation
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [ProgressStateWithTickInterval]. */
@RunWith(AndroidJUnit4::class)
class ProgressStateWithTickIntervalTest {

  private val testDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
  @OptIn(ExperimentalTestApi::class)
  @get:Rule
  val composeTestRule = createComposeRule(testDispatcher)

  @Test
  fun progressUpdatingOnTheSecondMark_positionChangesByOneSecond() =
    runTest(testDispatcher) {
      val player = createReadyPlayerWithSingleItem()
      player.setPositionSupplierDrivenBy(testDispatcher.scheduler)
      lateinit var state: ProgressStateWithTickInterval
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickInterval(
            player,
            tickIntervalMs = 1000,
            scope = backgroundScope,
          )
      }

      advanceTimeByInclusive(1000.milliseconds)

      assertThat(player.currentPosition).isEqualTo(1000)
      assertThat(state.currentPositionMs).isEqualTo(1000)
    }

  @Test
  fun progressUpdatingOnTheSecondMark_moveClockByFractionalSeconds_positionUpdatesOnTheGrid() =
    runTest(testDispatcher) {
      val player = createReadyPlayerWithSingleItem()
      player.setPositionSupplierDrivenBy(testDispatcher.scheduler)
      lateinit var state: ProgressStateWithTickInterval
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickInterval(
            player,
            tickIntervalMs = 1000,
            scope = backgroundScope,
          )
      }

      advanceTimeByInclusive(2345.milliseconds)

      assertThat(player.currentPosition).isEqualTo(2345)
      assertThat(state.currentPositionMs).isEqualTo(2000)
    }

  @Test
  fun progressUpdatingOnTheSecondMark_moveClockByFractionalSeconds_positionRoundsUpToTheGrid() =
    runTest(testDispatcher) {
      val player = createReadyPlayerWithSingleItem()
      player.setPositionSupplierDrivenBy(testDispatcher.scheduler)
      lateinit var state: ProgressStateWithTickInterval
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickInterval(
            player,
            tickIntervalMs = 500,
            scope = backgroundScope,
          )
      }

      advanceTimeByInclusive(1800.milliseconds)
      player.setDuration("SingleItem", 5000)
      composeTestRule.waitForIdle()

      assertThat(player.currentPosition).isEqualTo(1800)
      assertThat(state.currentPositionMs).isEqualTo(1500)

      advanceTimeByInclusive(195.milliseconds)
      player.setDuration("SingleItem", 7000)
      composeTestRule.waitForIdle()

      assertThat(player.currentPosition).isEqualTo(1995)
      assertThat(state.currentPositionMs).isEqualTo(2000)
    }

  @Test
  fun progressUpdatingContinuouslyEveryFrame_positionChangesByOneFrame() =
    runTest(testDispatcher) {
      val player = createReadyPlayerWithSingleItem()
      player.setPositionSupplierDrivenBy(testDispatcher.scheduler)
      lateinit var state: ProgressStateWithTickInterval
      // Prevent infinite scheduling loop for withFrameMillis, override here before setContent
      composeTestRule.mainClock.autoAdvance = false
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickInterval(
            player,
            tickIntervalMs = 0,
            scope = rememberCoroutineScopeWithBackgroundCancellation(),
          )
      }

      composeTestRule.mainClock.advanceTimeByFrame()

      assertThat(player.currentPosition).isEqualTo(16)
      assertThat(state.currentPositionMs).isEqualTo(16)
    }

  @Test
  fun progressUpdatingContinuouslyEveryFrame_moveClockByFractionalFrames_positionUpdatesOnTheGrid() =
    runTest(testDispatcher) {
      val player = createReadyPlayerWithSingleItem()
      player.setPositionSupplierDrivenBy(testDispatcher.scheduler)
      lateinit var state: ProgressStateWithTickInterval
      // Prevent infinite scheduling loop for withFrameMillis, override here before setContent
      composeTestRule.mainClock.autoAdvance = false
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickInterval(
            player,
            tickIntervalMs = 0,
            scope = rememberCoroutineScopeWithBackgroundCancellation(),
          )
      }

      advanceTimeByInclusive(30.milliseconds)

      assertThat(player.currentPosition).isEqualTo(30)
      assertThat(state.currentPositionMs).isEqualTo(16)
    }

  @Test
  fun progressUpdateWithCoPrimeDeltaAndSpeedNumerator_positionDriftsThenEvensOut() =
    runTest(testDispatcher) {
      val player =
        TestSimpleBasePlayer(
          playbackState = Player.STATE_READY,
          playWhenReady = true,
          playlist = listOf(MediaItemData.Builder("SingleItem").build()),
          playbackSpeed = 1.5f, // Awkward division of 1000 by 3/2 where 1000 and 3 are coprime.
        )
      player.setPositionSupplierDrivenBy(testDispatcher.scheduler)
      lateinit var state: ProgressStateWithTickInterval
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickInterval(
            player,
            tickIntervalMs = 1000,
            scope = backgroundScope,
          )
      }

      advanceTimeByInclusive(3000.milliseconds)

      assertThat(player.currentPosition).isEqualTo(4500)
      assertThat(state.currentPositionMs).isWithin(1).of(4000)

      advanceTimeByInclusive(1000.milliseconds)

      assertThat(player.currentPosition).isEqualTo(6000)
      assertThat(state.currentPositionMs).isEqualTo(6000)
    }

  @Test
  fun progressUpdatesSlightlyOffTheGrid_preventUnnecessaryUpdatesSinceAlreadyCloseEnough() =
    runTest(testDispatcher) {
      val player =
        TestSimpleBasePlayer(
          playbackState = Player.STATE_READY,
          playWhenReady = true,
          playlist = listOf(MediaItemData.Builder("SingleItem").build()),
          playbackSpeed = 1.5f, // Awkward division of 1000 by 3/2 where 1000 and 3 are coprime.
        )
      player.setPositionSupplierDrivenBy(testDispatcher.scheduler)
      lateinit var state: ProgressStateWithTickInterval
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickInterval(
            player,
            tickIntervalMs = 1000,
            scope = rememberCoroutineScopeWithBackgroundCancellation(),
          )
      }

      advanceTimeByInclusive(666.milliseconds)

      assertThat(player.currentPosition).isEqualTo(999)
      assertThat(state.currentPositionMs).isEqualTo(1000)

      advanceTimeByInclusive(400.milliseconds)

      assertThat(player.currentPosition).isEqualTo(1599)
      assertThat(state.currentPositionMs).isEqualTo(1000)

      advanceTimeByInclusive(267.milliseconds)

      assertThat(player.currentPosition).isEqualTo(1999)
      assertThat(state.currentPositionMs).isEqualTo(2000)

      advanceTimeByInclusive(667.milliseconds)

      assertThat(player.currentPosition).isEqualTo(3000)
      assertThat(state.currentPositionMs).isEqualTo(3000) // catches up with the grid
    }

  @Test
  fun playerWithAndWithoutRelevantCommand_stateGetsTrueAndDefaultValues() =
    runTest(testDispatcher) {
      val player = createReadyPlayerWithSingleItem()
      player.setPositionSupplierDrivenBy(testDispatcher.scheduler)
      player.removeCommands(COMMAND_GET_CURRENT_MEDIA_ITEM)
      lateinit var state: ProgressStateWithTickInterval
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickInterval(
            player,
            tickIntervalMs = 1000,
            scope = backgroundScope,
          )
      }

      advanceTimeByInclusive(2345.milliseconds)

      assertThat(state.currentPositionMs).isEqualTo(0)
      assertThat(state.bufferedPositionMs).isEqualTo(0)
      assertThat(state.durationMs).isEqualTo(C.TIME_UNSET)

      player.addCommands(COMMAND_GET_CURRENT_MEDIA_ITEM)
      advanceTimeByInclusive(2345.milliseconds)

      assertThat(state.currentPositionMs).isEqualTo(4000)
      assertThat(state.bufferedPositionMs).isEqualTo(4000)
      assertThat(state.durationMs).isEqualTo(10_000)

      player.removeCommands(COMMAND_GET_CURRENT_MEDIA_ITEM)
      advanceTimeByInclusive(2345.milliseconds)

      assertThat(state.currentPositionMs).isEqualTo(0)
      assertThat(state.bufferedPositionMs).isEqualTo(0)
      assertThat(state.durationMs).isEqualTo(C.TIME_UNSET)
    }

  @Test
  fun durationKnownStraightAway() =
    runTest(testDispatcher) {
      val player = createReadyPlayerWithSingleItem()
      lateinit var state: ProgressStateWithTickInterval
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickInterval(
            player,
            tickIntervalMs = 1000,
            scope = backgroundScope,
          )
      }

      assertThat(state.durationMs).isEqualTo(10_000)
      assertThat(player.duration).isEqualTo(10_000)
    }

  @Test
  fun playerReadyAndPlaying_durationKnownLater_updatePropagatesImmediatelyAsEvent() =
    runTest(testDispatcher) {
      val player =
        TestSimpleBasePlayer(
          playbackState = Player.STATE_READY,
          playWhenReady = true,
          playlist = listOf(MediaItemData.Builder("SingleItem").build()),
        )
      player.setPositionSupplierDrivenBy(testScheduler)
      lateinit var state: ProgressStateWithTickInterval
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickInterval(
            player,
            tickIntervalMs = 1000,
            scope = backgroundScope,
          )
      }
      assertThat(state.durationMs).isEqualTo(C.TIME_UNSET)

      advanceTimeByInclusive(2345.milliseconds)
      player.setDuration("SingleItem", 10_000)
      composeTestRule.waitForIdle()

      assertThat(state.durationMs).isEqualTo(10_000)
      assertThat(player.duration).isEqualTo(10_000)
      assertThat(player.currentPosition).isEqualTo(2345)
    }

  @Test
  fun playerReadyAndPaused_bufferedPositionIncreases_updatePropagatesAfterOneSecond() =
    runTest(testDispatcher) {
      val player =
        TestSimpleBasePlayer(
          playbackState = Player.STATE_READY,
          playWhenReady = false,
          playlist = listOf(MediaItemData.Builder("SingleItem").build()),
        )
      lateinit var state: ProgressStateWithTickInterval
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickInterval(
            player,
            tickIntervalMs = 100,
            scope = backgroundScope,
          )
      }
      assertThat(state.durationMs).isEqualTo(C.TIME_UNSET)
      advanceTimeByInclusive(200.milliseconds)
      player.setBufferedPositionMs(123)

      advanceTimeByInclusive(300.milliseconds)

      assertThat(player.bufferedPosition).isEqualTo(123)
      assertThat(state.bufferedPositionMs).isEqualTo(0)

      advanceTimeByInclusive((FALLBACK_UPDATE_INTERVAL_MS - 500).milliseconds)

      assertThat(player.bufferedPosition).isEqualTo(123)
      assertThat(state.bufferedPositionMs).isEqualTo(100)
    }

  @Test
  fun playerIdle_reportsInitialPlaceholderDataAndDoesNotBlockMainThread() =
    runTest(testDispatcher) {
      val player = TestSimpleBasePlayer(playbackState = Player.STATE_IDLE, playlist = listOf())
      lateinit var state: ProgressStateWithTickInterval
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickInterval(
            player,
            tickIntervalMs = 100,
            scope = backgroundScope,
          )
      }
      assertThat(state.durationMs).isEqualTo(C.TIME_UNSET)
      assertThat(state.currentPositionMs).isEqualTo(0)
      assertThat(state.bufferedPositionMs).isEqualTo(0)

      // Wait for any pending updates to verify the state stays the same and is not blocked on the
      // main thread.
      advanceTimeByInclusive(200.milliseconds)

      assertThat(state.durationMs).isEqualTo(C.TIME_UNSET)
      assertThat(state.currentPositionMs).isEqualTo(0)
      assertThat(state.bufferedPositionMs).isEqualTo(0)
    }

  @Test
  fun playerEnded_reportsFinalStateAndDoesNotBlockMainThread() =
    runTest(testDispatcher) {
      val player = createReadyPlayerWithSingleItem()
      lateinit var state: ProgressStateWithTickInterval
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickInterval(
            player,
            tickIntervalMs = 100,
            scope = backgroundScope,
          )
      }
      player.setPosition(10_000)
      player.setBufferedPositionMs(10_000)
      composeTestRule.waitForIdle()
      // TODO: b/436159565 - Remove runCurrent() when `compose.ui:ui-test` is updated to include
      //    aosp/3208355, which makes waitForIdle() sufficient. Will require composeBom upgrade.
      testScheduler.runCurrent()

      // Check state before change to ENDED
      assertThat(state.durationMs).isEqualTo(10_000)
      assertThat(state.currentPositionMs).isEqualTo(10_000)
      assertThat(state.bufferedPositionMs).isEqualTo(10_000)

      player.setPlaybackState(Player.STATE_ENDED)

      // Immediately after the change before running the playback state update
      assertThat(state.durationMs).isEqualTo(10_000)
      assertThat(state.currentPositionMs).isEqualTo(10_000)
      assertThat(state.bufferedPositionMs).isEqualTo(10_000)

      composeTestRule.waitForIdle()
      // TODO: b/436159565 - Remove runCurrent() when `compose.ui:ui-test` is updated to include
      //    aosp/3208355, which makes waitForIdle() sufficient. Will require composeBom upgrade.
      testScheduler.runCurrent()

      // After completing any pending updates to ensure the main thread is not blocked.
      assertThat(state.durationMs).isEqualTo(10_000)
      assertThat(state.currentPositionMs).isEqualTo(10_000)
      assertThat(state.bufferedPositionMs).isEqualTo(10_000)
    }
}
