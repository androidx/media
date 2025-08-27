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

import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_GET_CURRENT_MEDIA_ITEM
import androidx.media3.common.Player.STATE_READY
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

/** Unit test for [ProgressStateWithTickCount]. */
@RunWith(AndroidJUnit4::class)
class ProgressStateWithTickCountTest {

  private val testDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
  @OptIn(ExperimentalTestApi::class)
  @get:Rule
  val composeTestRule = createComposeRule(testDispatcher)

  @Test
  fun progressUpdatingTenTimes_positionChangesByOneTick() =
    runTest(testDispatcher) {
      val player = createReadyPlayerWithSingleItem()
      player.setPositionSupplierDrivenBy(testDispatcher.scheduler)
      val currentItemDuration = player.duration
      lateinit var state: ProgressStateWithTickCount
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickCount(
            player,
            totalTickCount = 10,
            scope = rememberCoroutineScopeWithBackgroundCancellation(),
          )
      }

      advanceTimeByInclusive((currentItemDuration / 10).milliseconds)

      assertThat(player.currentPosition).isEqualTo(currentItemDuration / 10)
      assertThat(state.currentPositionProgress).isEqualTo(0.1f)
    }

  @Test
  fun progressUpdatingTenTimes_moveClockByFractionalTicks_positionUpdatesOnTheGrid() =
    runTest(testDispatcher) {
      val player = createReadyPlayerWithSingleItem()
      player.setPositionSupplierDrivenBy(testDispatcher.scheduler)
      lateinit var state: ProgressStateWithTickCount
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickCount(
            player,
            totalTickCount = 10,
            scope = rememberCoroutineScopeWithBackgroundCancellation(),
          )
      }

      advanceTimeByInclusive(2345.milliseconds)

      assertThat(player.currentPosition).isEqualTo(2_345)
      assertThat(state.currentPositionProgress).isEqualTo(0.2f)
    }

  @Test
  fun totalTickCountZero_currentPositionTickAlwaysZero() =
    runTest(testDispatcher) {
      val player = createReadyPlayerWithSingleItem()
      player.setPositionSupplierDrivenBy(testDispatcher.scheduler)
      lateinit var state: ProgressStateWithTickCount
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickCount(
            player,
            totalTickCount = 0,
            scope = rememberCoroutineScopeWithBackgroundCancellation(),
          )
      }

      composeTestRule.mainClock.advanceTimeByFrame()

      // Even though real position moves, it's impossible to tell which tick it corresponds to
      assertThat(player.currentPosition).isEqualTo(16)
      assertThat(state.currentPositionProgress).isEqualTo(0f)

      advanceTimeByInclusive(1984.milliseconds)

      assertThat(player.currentPosition).isEqualTo(2000)
      assertThat(state.currentPositionProgress).isEqualTo(0f)
    }

  @Test
  fun progressUpdateWithCoPrimeDeltaAndSpeedNumerator_positionDriftsThenEvensOut() =
    runTest(testDispatcher) {
      val player = createReadyPlayerWithSingleItem()
      player.setPlaybackSpeed(1.5f) // Awkward division of 1000 by 3/2 where 1000 and 3 are coprime.
      player.setPositionSupplierDrivenBy(testDispatcher.scheduler)
      lateinit var state: ProgressStateWithTickCount
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickCount(
            player,
            totalTickCount = 10,
            scope = rememberCoroutineScopeWithBackgroundCancellation(),
          )
      }

      advanceTimeByInclusive(666.milliseconds)

      assertThat(player.currentPosition).isEqualTo(999)
      assertThat(state.currentPositionProgress).isEqualTo(0.1f)

      advanceTimeByInclusive(400.milliseconds)

      assertThat(player.currentPosition).isEqualTo(1599)
      assertThat(state.currentPositionProgress).isEqualTo(0.2f)

      advanceTimeByInclusive(267.milliseconds)

      assertThat(player.currentPosition).isEqualTo(1999)
      assertThat(state.currentPositionProgress).isEqualTo(0.2f)

      advanceTimeByInclusive(667.milliseconds)

      assertThat(player.currentPosition).isEqualTo(3000)
      assertThat(state.currentPositionProgress).isEqualTo(0.3f) // catches up with the grid

      advanceTimeByInclusive(1000.milliseconds)

      assertThat(player.currentPosition).isEqualTo(4500)
      assertThat(state.currentPositionProgress).isEqualTo(0.5f)

      advanceTimeByInclusive(1000.milliseconds)

      assertThat(player.currentPosition).isEqualTo(6_000)
      assertThat(state.currentPositionProgress).isEqualTo(0.6f) // catches up with the grid
    }

  @Test
  fun playerWithoutRelevantCommand_timePassesButValuesRemainDefaultAndUnchanged() =
    runTest(testDispatcher) {
      val player = createReadyPlayerWithSingleItem()
      player.setPositionSupplierDrivenBy(testDispatcher.scheduler)
      player.removeCommands(COMMAND_GET_CURRENT_MEDIA_ITEM)
      lateinit var state: ProgressStateWithTickCount
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickCount(
            player,
            totalTickCount = 10,
            scope = rememberCoroutineScopeWithBackgroundCancellation(),
          )
      }

      advanceTimeByInclusive(2345.milliseconds)

      assertThat(state.currentPositionProgress).isEqualTo(0f)
      assertThat(state.bufferedPositionProgress).isEqualTo(0f)
    }

  @Test
  fun playerReadyAndPlaying_durationKnownLater_updatePropagatesImmediatelyAsEvent() =
    runTest(testDispatcher) {
      val player =
        TestSimpleBasePlayer(
          playbackState = STATE_READY,
          playWhenReady = true,
          playlist = listOf(MediaItemData.Builder("SingleItem").build()),
        )
      player.setPositionSupplierDrivenBy(testDispatcher.scheduler)
      lateinit var state: ProgressStateWithTickCount
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickCount(
            player,
            totalTickCount = 10,
            scope = rememberCoroutineScopeWithBackgroundCancellation(),
          )
      }
      assertThat(state.currentPositionProgress).isEqualTo(0f)

      advanceTimeByInclusive(2345.milliseconds)
      assertThat(state.currentPositionProgress).isEqualTo(0f)

      advanceTimeByInclusive(2345.milliseconds)
      player.setDuration("SingleItem", 10_000)
      composeTestRule.waitForIdle()

      assertThat(player.duration).isEqualTo(10_000)
      assertThat(player.currentPosition).isEqualTo(4690)
      assertThat(state.currentPositionProgress).isEqualTo(0.5f)
    }

  @Test
  fun playerReadyAndPlaying_durationUnknown_currentPositionTickAlwaysZero() =
    runTest(testDispatcher) {
      val player =
        TestSimpleBasePlayer(
          playbackState = STATE_READY,
          playWhenReady = true,
          playlist = listOf(MediaItemData.Builder("SingleItem").build()),
        )
      player.setPositionSupplierDrivenBy(testDispatcher.scheduler)
      lateinit var state: ProgressStateWithTickCount
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickCount(
            player,
            totalTickCount = 10,
            scope = rememberCoroutineScopeWithBackgroundCancellation(),
          )
      }
      val durationUnknownUpdate = FALLBACK_UPDATE_INTERVAL_MS
      assertThat(state.currentPositionProgress).isEqualTo(0f)
      assertThat(player.duration).isEqualTo(C.TIME_UNSET)

      advanceTimeByInclusive((durationUnknownUpdate - 100).milliseconds)

      assertThat(state.currentPositionProgress).isEqualTo(0f)
      assertThat(player.currentPosition).isEqualTo(durationUnknownUpdate - 100)

      advanceTimeByInclusive(100.milliseconds)
      assertThat(state.currentPositionProgress).isEqualTo(0f)
      assertThat(player.currentPosition).isEqualTo(durationUnknownUpdate)
    }

  @Test
  fun playerReadyAndPaused_bufferedDurationKnownLater_updatePropagatesAtFallbackInterval() =
    runTest(testDispatcher) {
      val player = createReadyPlayerWithSingleItem()
      player.pause()
      lateinit var state: ProgressStateWithTickCount
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickCount(player, totalTickCount = 10, scope = backgroundScope)
      }
      val pausedUpdate = FALLBACK_UPDATE_INTERVAL_MS
      advanceTimeByInclusive(200.milliseconds)
      player.setBufferedPositionMs(1234)

      advanceTimeByInclusive(300.milliseconds)

      assertThat(state.bufferedPositionProgress).isEqualTo(0f)

      advanceTimeByInclusive((pausedUpdate - 500).milliseconds)

      assertThat(player.bufferedPosition).isEqualTo(1234)
      assertThat(state.bufferedPositionProgress).isEqualTo(0.1f)
    }

  @Test
  fun updateTickCount_updatesProgressInterval() =
    runTest(testDispatcher) {
      val player = createReadyPlayerWithSingleItem()
      player.setPositionSupplierDrivenBy(testDispatcher.scheduler)
      lateinit var state: ProgressStateWithTickCount
      lateinit var totalTickCount: MutableIntState
      composeTestRule.setContent {
        totalTickCount = remember { mutableIntStateOf(10) }
        state =
          rememberProgressStateWithTickCount(
            player,
            totalTickCount = totalTickCount.intValue,
            scope = rememberCoroutineScopeWithBackgroundCancellation(),
          )
      }

      advanceTimeByInclusive(1000.milliseconds)

      assertThat(player.currentPosition).isEqualTo(1000)
      assertThat(state.currentPositionProgress).isEqualTo(0.1f)

      // new interval grid is 2000ms, not 1000ms as before
      totalTickCount.intValue = 5
      composeTestRule.waitForIdle()

      assertThat(player.currentPosition).isEqualTo(1016)
      assertThat(state.currentPositionProgress).isEqualTo(0.2f)

      advanceTimeByInclusive(1000.milliseconds)

      assertThat(player.currentPosition).isEqualTo(2016)
      assertThat(state.currentPositionProgress).isEqualTo(0.2f)

      advanceTimeByInclusive(3000.milliseconds)
      assertThat(player.currentPosition).isEqualTo(5016)
      assertThat(state.currentPositionProgress).isEqualTo(0.6f)

      advanceTimeByInclusive(4984.milliseconds)
      assertThat(player.currentPosition).isEqualTo(10000)
      assertThat(state.currentPositionProgress).isEqualTo(1f)
    }

  @Test
  fun playerIdle_reportsInitialPlaceholderDataAndDoesNotBlockMainThread() =
    runTest(testDispatcher) {
      val player = TestSimpleBasePlayer(playbackState = Player.STATE_IDLE, playlist = listOf())
      lateinit var state: ProgressStateWithTickCount
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickCount(
            player,
            totalTickCount = 10,
            scope = rememberCoroutineScopeWithBackgroundCancellation(),
          )
      }
      assertThat(state.currentPositionProgress).isEqualTo(0f)
      assertThat(state.bufferedPositionProgress).isEqualTo(0f)

      // Wait for any pending updates to verify the state stays the same and is not blocked on the
      // main thread.
      advanceTimeByInclusive(200.milliseconds)

      assertThat(state.currentPositionProgress).isEqualTo(0f)
      assertThat(state.bufferedPositionProgress).isEqualTo(0f)
    }

  @Test
  fun playerEnded_reportsFinalStateAndDoesNotBlockMainThread() =
    runTest(testDispatcher) {
      val player = createReadyPlayerWithSingleItem()
      lateinit var state: ProgressStateWithTickCount
      composeTestRule.setContent {
        state =
          rememberProgressStateWithTickCount(
            player,
            totalTickCount = 10,
            scope = rememberCoroutineScopeWithBackgroundCancellation(),
          )
      }
      player.setPosition(10_000)
      player.setBufferedPositionMs(10_000)
      composeTestRule.waitForIdle()
      // TODO: b/436159565 - Remove runCurrent() when `compose.ui:ui-test` is updated to include
      //    aosp/3208355, which makes waitForIdle() sufficient. Will require composeBom upgrade.
      testScheduler.runCurrent()

      // Check state before change to ENDED
      assertThat(state.currentPositionProgress).isEqualTo(1f)
      assertThat(state.bufferedPositionProgress).isEqualTo(1f)

      player.setPlaybackState(Player.STATE_ENDED)

      // Immediately after the change before running the playback state update
      assertThat(state.currentPositionProgress).isEqualTo(1f)
      assertThat(state.bufferedPositionProgress).isEqualTo(1f)

      composeTestRule.waitForIdle()
      // TODO: b/436159565 - Remove runCurrent() when `compose.ui:ui-test` is updated to include
      //    aosp/3208355, which makes waitForIdle() sufficient. Will require composeBom upgrade.
      testScheduler.runCurrent()

      // After completing any pending updates to ensure the main thread is not blocked.
      assertThat(state.currentPositionProgress).isEqualTo(1f)
      assertThat(state.bufferedPositionProgress).isEqualTo(1f)
    }
}
