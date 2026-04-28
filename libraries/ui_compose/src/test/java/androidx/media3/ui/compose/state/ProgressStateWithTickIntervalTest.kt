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

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_GET_CURRENT_MEDIA_ITEM
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.test.utils.FakePlayer
import androidx.media3.ui.compose.testutils.advancePrecisely
import androidx.media3.ui.compose.testutils.createReadyPlayerWithSingleItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [ProgressStateWithTickInterval]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ProgressStateWithTickIntervalTest {

  @Test
  fun progressUpdatingOnTheSecondMark_positionChangesByOneSecond() = runComposeUiTest {
    val player = createReadyPlayerWithSingleItem()
    player.setPositionSupplierDrivenBy(mainClock.scheduler)
    lateinit var state: ProgressStateWithTickInterval
    setContent { state = rememberProgressStateWithTickInterval(player, tickIntervalMs = 1000) }

    mainClock.advancePrecisely(1000)

    assertThat(player.currentPosition).isEqualTo(1000)
    assertThat(state.currentPositionMs).isEqualTo(1000)
  }

  @Test
  fun progressUpdatingOnTheSecondMark_moveClockByFractionalSeconds_positionUpdatesOnTheGrid() =
    runComposeUiTest {
      val player = createReadyPlayerWithSingleItem()
      player.setPositionSupplierDrivenBy(mainClock.scheduler)
      lateinit var state: ProgressStateWithTickInterval
      setContent { state = rememberProgressStateWithTickInterval(player, tickIntervalMs = 1000) }

      mainClock.advancePrecisely(2345)

      assertThat(player.currentPosition).isEqualTo(2345)
      assertThat(state.currentPositionMs).isEqualTo(2000)
    }

  @Test
  fun progressUpdatingOnTheSecondMark_moveClockByFractionalSecondsWholeFrames_positionUpdatesOnTheGrid() =
    runComposeUiTest {
      val player = createReadyPlayerWithSingleItem()
      player.setPositionSupplierDrivenBy(mainClock.scheduler)
      lateinit var state: ProgressStateWithTickInterval
      setContent { state = rememberProgressStateWithTickInterval(player, tickIntervalMs = 1000) }

      mainClock.advancePrecisely(2345, ignoreFrameDuration = false)

      assertThat(player.currentPosition).isEqualTo(2352) // 147*16ms
      assertThat(state.currentPositionMs).isEqualTo(2000)
    }

  @Test
  fun progressUpdatingOnTheSecondMark_moveClockByFractionalSeconds_positionRoundsUpToTheGrid() =
    runComposeUiTest {
      val player = createReadyPlayerWithSingleItem()
      player.setPositionSupplierDrivenBy(mainClock.scheduler)
      lateinit var state: ProgressStateWithTickInterval
      setContent { state = rememberProgressStateWithTickInterval(player, tickIntervalMs = 500) }

      mainClock.advancePrecisely(1800)
      player.setDuration("SingleItem", 5000)
      waitForIdle()

      assertThat(player.currentPosition).isEqualTo(1800)
      assertThat(state.currentPositionMs).isEqualTo(1500)

      mainClock.advancePrecisely(195)
      player.setDuration("SingleItem", 7000)
      waitForIdle()

      assertThat(player.currentPosition).isEqualTo(1995)
      assertThat(state.currentPositionMs).isEqualTo(2000)
    }

  @Test
  fun progressUpdatingContinuouslyEveryFrame_positionChangesByOneFrame() = runComposeUiTest {
    val player = createReadyPlayerWithSingleItem()
    player.setPositionSupplierDrivenBy(mainClock.scheduler)
    lateinit var state: ProgressStateWithTickInterval
    // Prevent infinite scheduling loop for withFrameMillis, override here before setContent
    mainClock.autoAdvance = false
    setContent { state = rememberProgressStateWithTickInterval(player, tickIntervalMs = 0) }

    mainClock.advanceTimeByFrame()

    assertThat(player.currentPosition).isEqualTo(16)
    assertThat(state.currentPositionMs).isEqualTo(16)
  }

  @Test
  fun progressUpdatingContinuouslyEveryFrame_moveClockByFractionalFrames_positionUpdatesOnTheGrid() =
    runComposeUiTest {
      val player = createReadyPlayerWithSingleItem()
      player.setPositionSupplierDrivenBy(mainClock.scheduler)
      lateinit var state: ProgressStateWithTickInterval
      // Prevent infinite scheduling loop for withFrameMillis, override here before setContent
      mainClock.autoAdvance = false
      setContent { state = rememberProgressStateWithTickInterval(player, tickIntervalMs = 0) }

      mainClock.advancePrecisely(30)

      assertThat(player.currentPosition).isEqualTo(30)
      assertThat(state.currentPositionMs).isEqualTo(16)
    }

  @Test
  fun progressUpdateWithCoPrimeDeltaAndSpeedNumerator_positionDriftsThenEvensOut() =
    runComposeUiTest {
      val player =
        FakePlayer(
          playbackState = Player.STATE_READY,
          playWhenReady = true,
          playlist = listOf(MediaItemData.Builder("SingleItem").build()),
          playbackSpeed = 1.5f, // Awkward division of 1000 by 3/2 where 1000 and 3 are coprime.
        )
      player.setPositionSupplierDrivenBy(mainClock.scheduler)
      lateinit var state: ProgressStateWithTickInterval
      setContent { state = rememberProgressStateWithTickInterval(player, tickIntervalMs = 1000) }

      mainClock.advancePrecisely(3000)

      assertThat(player.currentPosition).isEqualTo(4500)
      assertThat(state.currentPositionMs).isWithin(1).of(4000)

      mainClock.advancePrecisely(1000)

      assertThat(player.currentPosition).isEqualTo(6000)
      assertThat(state.currentPositionMs).isEqualTo(6000)
    }

  @Test
  fun progressUpdatesSlightlyOffTheGrid_preventUnnecessaryUpdatesSinceAlreadyCloseEnough() =
    runComposeUiTest {
      val player =
        FakePlayer(
          playbackState = Player.STATE_READY,
          playWhenReady = true,
          playlist = listOf(MediaItemData.Builder("SingleItem").build()),
          playbackSpeed = 1.5f, // Awkward division of 1000 by 3/2 where 1000 and 3 are coprime.
        )
      player.setPositionSupplierDrivenBy(mainClock.scheduler)
      lateinit var state: ProgressStateWithTickInterval
      setContent { state = rememberProgressStateWithTickInterval(player, tickIntervalMs = 1000) }

      mainClock.advancePrecisely(666)

      assertThat(player.currentPosition).isEqualTo(999)
      assertThat(state.currentPositionMs).isEqualTo(1000)

      mainClock.advancePrecisely(400)

      assertThat(player.currentPosition).isEqualTo(1599)
      assertThat(state.currentPositionMs).isEqualTo(1000)

      mainClock.advancePrecisely(267)

      assertThat(player.currentPosition).isEqualTo(1999)
      assertThat(state.currentPositionMs).isEqualTo(2000)

      mainClock.advancePrecisely(667)

      assertThat(player.currentPosition).isEqualTo(3000)
      assertThat(state.currentPositionMs).isEqualTo(3000) // catches up with the grid
    }

  @Test
  fun playerWithAndWithoutRelevantCommand_stateGetsTrueAndDefaultValues() = runComposeUiTest {
    val player = createReadyPlayerWithSingleItem()
    player.setPositionSupplierDrivenBy(mainClock.scheduler)
    player.removeCommands(COMMAND_GET_CURRENT_MEDIA_ITEM)
    lateinit var state: ProgressStateWithTickInterval
    setContent { state = rememberProgressStateWithTickInterval(player, tickIntervalMs = 1000) }

    mainClock.advancePrecisely(2345)

    assertThat(state.currentPositionMs).isEqualTo(0)
    assertThat(state.bufferedPositionMs).isEqualTo(0)
    assertThat(state.durationMs).isEqualTo(C.TIME_UNSET)

    player.addCommands(COMMAND_GET_CURRENT_MEDIA_ITEM)
    waitForIdle()
    mainClock.advancePrecisely(2345)

    assertThat(state.currentPositionMs).isEqualTo(4000)
    assertThat(state.bufferedPositionMs).isEqualTo(4000)
    assertThat(state.durationMs).isEqualTo(10_000)

    player.removeCommands(COMMAND_GET_CURRENT_MEDIA_ITEM)
    waitForIdle()
    mainClock.advancePrecisely(2345)

    assertThat(state.currentPositionMs).isEqualTo(0)
    assertThat(state.bufferedPositionMs).isEqualTo(0)
    assertThat(state.durationMs).isEqualTo(C.TIME_UNSET)
  }

  @Test
  fun durationKnownStraightAway() = runComposeUiTest {
    val player = createReadyPlayerWithSingleItem()
    lateinit var state: ProgressStateWithTickInterval
    setContent { state = rememberProgressStateWithTickInterval(player, tickIntervalMs = 1000) }

    assertThat(state.durationMs).isEqualTo(10_000)
    assertThat(player.duration).isEqualTo(10_000)
  }

  @Test
  fun playerReadyAndPlaying_durationKnownLater_updatePropagatesImmediatelyAsEvent() =
    runComposeUiTest {
      val player =
        FakePlayer(
          playbackState = Player.STATE_READY,
          playWhenReady = true,
          playlist = listOf(MediaItemData.Builder("SingleItem").build()),
        )
      player.setPositionSupplierDrivenBy(mainClock.scheduler)
      lateinit var state: ProgressStateWithTickInterval
      setContent { state = rememberProgressStateWithTickInterval(player, tickIntervalMs = 1000) }
      assertThat(state.durationMs).isEqualTo(C.TIME_UNSET)

      mainClock.advancePrecisely(2345)
      player.setDuration("SingleItem", 10_000)
      waitForIdle()

      assertThat(state.durationMs).isEqualTo(10_000)
      assertThat(player.duration).isEqualTo(10_000)
      assertThat(player.currentPosition).isEqualTo(2345)
    }

  @Test
  fun playerReadyAndPaused_bufferedPositionIncreases_updatePropagatesAfterOneSecond() =
    runComposeUiTest {
      val player =
        FakePlayer(
          playbackState = Player.STATE_READY,
          playWhenReady = false,
          playlist = listOf(MediaItemData.Builder("SingleItem").build()),
        )
      lateinit var state: ProgressStateWithTickInterval
      setContent { state = rememberProgressStateWithTickInterval(player, tickIntervalMs = 100) }
      assertThat(state.durationMs).isEqualTo(C.TIME_UNSET)
      mainClock.advancePrecisely(200)
      player.setBufferedPositionMs(123)

      mainClock.advancePrecisely(300)

      assertThat(player.bufferedPosition).isEqualTo(123)
      assertThat(state.bufferedPositionMs).isEqualTo(0)

      mainClock.advancePrecisely(FALLBACK_UPDATE_INTERVAL_MS - 500)

      assertThat(player.bufferedPosition).isEqualTo(123)
      assertThat(state.bufferedPositionMs).isEqualTo(100)
    }

  @Test
  fun playerIdle_reportsInitialPlaceholderDataAndDoesNotBlockMainThread() = runComposeUiTest {
    val player = FakePlayer(playbackState = Player.STATE_IDLE, playlist = listOf())
    lateinit var state: ProgressStateWithTickInterval
    setContent { state = rememberProgressStateWithTickInterval(player, tickIntervalMs = 100) }
    assertThat(state.durationMs).isEqualTo(C.TIME_UNSET)
    assertThat(state.currentPositionMs).isEqualTo(0)
    assertThat(state.bufferedPositionMs).isEqualTo(0)

    // Wait for any pending updates to verify the state stays the same and is not blocked on the
    // main thread.
    mainClock.advancePrecisely(200)

    assertThat(state.durationMs).isEqualTo(C.TIME_UNSET)
    assertThat(state.currentPositionMs).isEqualTo(0)
    assertThat(state.bufferedPositionMs).isEqualTo(0)
  }

  @Test
  fun playerEnded_reportsFinalStateAndDoesNotBlockMainThread() = runComposeUiTest {
    val player = createReadyPlayerWithSingleItem()
    lateinit var state: ProgressStateWithTickInterval
    setContent { state = rememberProgressStateWithTickInterval(player, tickIntervalMs = 100) }
    player.setPosition(10_000)
    player.setBufferedPositionMs(10_000)
    waitForIdle()

    // Check state before change to ENDED
    assertThat(state.durationMs).isEqualTo(10_000)
    assertThat(state.currentPositionMs).isEqualTo(10_000)
    assertThat(state.bufferedPositionMs).isEqualTo(10_000)

    player.setPlaybackState(Player.STATE_ENDED)

    // Immediately after the change before running the playback state update
    assertThat(state.durationMs).isEqualTo(10_000)
    assertThat(state.currentPositionMs).isEqualTo(10_000)
    assertThat(state.bufferedPositionMs).isEqualTo(10_000)

    waitForIdle()

    // After completing any pending updates to ensure the main thread is not blocked.
    assertThat(state.durationMs).isEqualTo(10_000)
    assertThat(state.currentPositionMs).isEqualTo(10_000)
    assertThat(state.bufferedPositionMs).isEqualTo(10_000)
  }

  @Test
  fun observe_goesOutOfScope_stopsUpdatingRegularly() = runComposeUiTest {
    val player = createReadyPlayerWithSingleItem()
    player.setPositionSupplierDrivenBy(mainClock.scheduler)
    lateinit var state: ProgressStateWithTickInterval
    lateinit var observeEnabled: MutableState<Boolean>
    setContent {
      observeEnabled = remember { mutableStateOf(true) }
      val testScope = rememberCoroutineScope()
      state = remember { ProgressStateWithTickInterval(player, tickIntervalMs = 1000, testScope) }
      LaunchedEffect(observeEnabled.value) {
        if (observeEnabled.value) {
          state.observe()
        }
      }
    }

    // Assert progress if clock advances.
    mainClock.advancePrecisely(1000)
    assertThat(player.currentPosition).isEqualTo(1000)
    assertThat(state.currentPositionMs).isEqualTo(1000)

    // Stop observing and verify no further updates.
    observeEnabled.value = false
    waitForIdle()
    mainClock.advancePrecisely(1000)

    assertThat(player.currentPosition).isAtLeast(2000)
    assertThat(state.currentPositionMs).isEqualTo(1000)
  }
}
