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
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_GET_CURRENT_MEDIA_ITEM
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.test.utils.FakePlayer
import androidx.media3.ui.compose.testutils.advancePrecisely
import androidx.media3.ui.compose.testutils.createReadyPlayerWithSingleItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [ProgressStateWithTickCount]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ProgressStateWithTickCountTest {

  @Test
  fun rememberProgressStateWithTickCount_withNullPlayer_returnsDefaultValues() = runComposeUiTest {
    lateinit var state: ProgressStateWithTickCount
    setContent { state = rememberProgressStateWithTickCount(player = null, totalTickCount = 10) }

    assertThat(state.currentPositionProgress).isEqualTo(0f)
    assertThat(state.bufferedPositionProgress).isEqualTo(0f)
    assertThat(state.changingProgressEnabled).isFalse()
  }

  @Test
  fun progressUpdatingTenTimes_positionChangesByOneTick() = runComposeUiTest {
    val player = createReadyPlayerWithSingleItem()
    player.setPositionSupplierDrivenBy(mainClock.scheduler)
    val currentItemDuration = player.duration
    lateinit var state: ProgressStateWithTickCount
    setContent { state = rememberProgressStateWithTickCount(player, totalTickCount = 10) }

    mainClock.advancePrecisely(currentItemDuration / 10)

    assertThat(player.currentPosition).isEqualTo(currentItemDuration / 10)
    assertThat(state.currentPositionProgress).isEqualTo(0.1f)
  }

  @Test
  fun progressUpdatingTenTimes_moveClockByFractionalTicks_positionUpdatesOnTheGrid() =
    runComposeUiTest {
      val player = createReadyPlayerWithSingleItem()
      player.setPositionSupplierDrivenBy(mainClock.scheduler)
      lateinit var state: ProgressStateWithTickCount
      setContent { state = rememberProgressStateWithTickCount(player, totalTickCount = 10) }

      mainClock.advancePrecisely(2345)

      assertThat(player.currentPosition).isEqualTo(2_345)
      assertThat(state.currentPositionProgress).isEqualTo(0.2f)
    }

  @Test
  fun totalTickCountZero_currentPositionTickAlwaysZero() = runComposeUiTest {
    val player = createReadyPlayerWithSingleItem()
    player.setPositionSupplierDrivenBy(mainClock.scheduler)
    lateinit var state: ProgressStateWithTickCount
    setContent { state = rememberProgressStateWithTickCount(player, totalTickCount = 0) }

    mainClock.advanceTimeByFrame()

    // Even though real position moves, it's impossible to tell which tick it corresponds to
    assertThat(player.currentPosition).isEqualTo(16)
    assertThat(state.currentPositionProgress).isEqualTo(0f)

    mainClock.advancePrecisely(1984)

    assertThat(player.currentPosition).isEqualTo(2000)
    assertThat(state.currentPositionProgress).isEqualTo(0f)
  }

  @Test
  fun progressUpdateWithCoPrimeDeltaAndSpeedNumerator_positionDriftsThenEvensOut() =
    runComposeUiTest {
      val player = createReadyPlayerWithSingleItem()
      player.setPlaybackSpeed(1.5f) // Awkward division of 1000 by 3/2 where 1000 and 3 are coprime.
      player.setPositionSupplierDrivenBy(mainClock.scheduler)
      lateinit var state: ProgressStateWithTickCount
      setContent { state = rememberProgressStateWithTickCount(player, totalTickCount = 10) }

      mainClock.advancePrecisely(666)

      assertThat(player.currentPosition).isEqualTo(999)
      assertThat(state.currentPositionProgress).isEqualTo(0.1f)

      mainClock.advancePrecisely(400)

      assertThat(player.currentPosition).isEqualTo(1599)
      assertThat(state.currentPositionProgress).isEqualTo(0.2f)

      mainClock.advancePrecisely(267)

      assertThat(player.currentPosition).isEqualTo(1999)
      assertThat(state.currentPositionProgress).isEqualTo(0.2f)

      mainClock.advancePrecisely(667)

      assertThat(player.currentPosition).isEqualTo(3000)
      assertThat(state.currentPositionProgress).isEqualTo(0.3f) // catches up with the grid

      mainClock.advancePrecisely(1000)

      assertThat(player.currentPosition).isEqualTo(4500)
      assertThat(state.currentPositionProgress).isEqualTo(0.5f)

      mainClock.advancePrecisely(1000)

      assertThat(player.currentPosition).isEqualTo(6_000)
      assertThat(state.currentPositionProgress).isEqualTo(0.6f) // catches up with the grid
    }

  @Test
  fun playerWithoutRelevantCommand_timePassesButValuesRemainDefaultAndUnchanged() =
    runComposeUiTest {
      val player = createReadyPlayerWithSingleItem()
      player.setPositionSupplierDrivenBy(mainClock.scheduler)
      player.removeCommands(COMMAND_GET_CURRENT_MEDIA_ITEM)
      lateinit var state: ProgressStateWithTickCount
      setContent { state = rememberProgressStateWithTickCount(player, totalTickCount = 10) }

      mainClock.advancePrecisely(2345)

      assertThat(state.currentPositionProgress).isEqualTo(0f)
      assertThat(state.bufferedPositionProgress).isEqualTo(0f)
    }

  @Test
  fun playerReadyAndPlaying_durationKnownLater_updatePropagatesImmediatelyAsEvent() =
    runComposeUiTest {
      val player =
        FakePlayer(
          playbackState = STATE_READY,
          playWhenReady = true,
          playlist = listOf(MediaItemData.Builder("SingleItem").build()),
        )
      player.setPositionSupplierDrivenBy(mainClock.scheduler)
      lateinit var state: ProgressStateWithTickCount
      setContent { state = rememberProgressStateWithTickCount(player, totalTickCount = 10) }
      assertThat(state.currentPositionProgress).isEqualTo(0f)

      mainClock.advancePrecisely(2345)
      assertThat(state.currentPositionProgress).isEqualTo(0f)

      mainClock.advancePrecisely(2345)
      player.setDuration("SingleItem", 10_000)
      waitForIdle()

      assertThat(player.duration).isEqualTo(10_000)
      assertThat(player.currentPosition).isEqualTo(4690)
      assertThat(state.currentPositionProgress).isEqualTo(0.5f)
    }

  @Test
  fun playerReadyAndPlaying_durationUnknown_currentPositionTickAlwaysZero() = runComposeUiTest {
    val player =
      FakePlayer(
        playbackState = STATE_READY,
        playWhenReady = true,
        playlist = listOf(MediaItemData.Builder("SingleItem").build()),
      )
    player.setPositionSupplierDrivenBy(mainClock.scheduler)
    lateinit var state: ProgressStateWithTickCount
    setContent { state = rememberProgressStateWithTickCount(player, totalTickCount = 10) }
    val durationUnknownUpdate = FALLBACK_UPDATE_INTERVAL_MS
    assertThat(state.currentPositionProgress).isEqualTo(0f)
    assertThat(state.bufferedPositionProgress).isEqualTo(0f)
    assertThat(state.changingProgressEnabled).isFalse()
    assertThat(player.duration).isEqualTo(C.TIME_UNSET)

    mainClock.advancePrecisely(durationUnknownUpdate - 100)

    assertThat(state.currentPositionProgress).isEqualTo(0f)
    assertThat(state.bufferedPositionProgress).isEqualTo(0f)
    assertThat(state.changingProgressEnabled).isFalse()
    assertThat(player.currentPosition).isEqualTo(durationUnknownUpdate - 100)

    mainClock.advancePrecisely(100)
    assertThat(state.currentPositionProgress).isEqualTo(0f)
    assertThat(state.bufferedPositionProgress).isEqualTo(0f)
    assertThat(state.changingProgressEnabled).isFalse()
    assertThat(player.currentPosition).isEqualTo(durationUnknownUpdate)
  }

  @Test
  fun playerReadyAndPaused_bufferedDurationKnownLater_updatePropagatesAtFallbackInterval() =
    runComposeUiTest {
      val player = createReadyPlayerWithSingleItem()
      player.pause()
      lateinit var state: ProgressStateWithTickCount
      setContent { state = rememberProgressStateWithTickCount(player, totalTickCount = 10) }
      val pausedUpdate = FALLBACK_UPDATE_INTERVAL_MS
      mainClock.advancePrecisely(200)
      player.setBufferedPositionMs(1234)

      mainClock.advancePrecisely(300)

      assertThat(state.bufferedPositionProgress).isEqualTo(0f)

      mainClock.advancePrecisely(pausedUpdate - 500)

      assertThat(player.bufferedPosition).isEqualTo(1234)
      assertThat(state.bufferedPositionProgress).isEqualTo(0.1f)
    }

  @Test
  fun updateTickCount_updatesProgressInterval() = runComposeUiTest {
    val player = createReadyPlayerWithSingleItem()
    player.setPositionSupplierDrivenBy(mainClock.scheduler)
    lateinit var state: ProgressStateWithTickCount
    lateinit var totalTickCount: MutableIntState
    setContent {
      totalTickCount = remember { mutableIntStateOf(10) }
      state = rememberProgressStateWithTickCount(player, totalTickCount = totalTickCount.intValue)
    }

    mainClock.advancePrecisely(1000)

    assertThat(player.currentPosition).isEqualTo(1000)
    assertThat(state.currentPositionProgress).isEqualTo(0.1f)

    // new interval grid is 2000ms, not 1000ms as before
    totalTickCount.intValue = 5
    waitForIdle()

    assertThat(player.currentPosition).isEqualTo(1016)
    assertThat(state.currentPositionProgress).isEqualTo(0.2f)

    mainClock.advancePrecisely(1000)

    assertThat(player.currentPosition).isEqualTo(2016)
    assertThat(state.currentPositionProgress).isEqualTo(0.2f)

    mainClock.advancePrecisely(3000)
    assertThat(player.currentPosition).isEqualTo(5016)
    assertThat(state.currentPositionProgress).isEqualTo(0.6f)

    mainClock.advancePrecisely(4984)
    assertThat(player.currentPosition).isEqualTo(10000)
    assertThat(state.currentPositionProgress).isEqualTo(1f)
  }

  @Test
  fun updateTickCount_withoutObserving_doesNotUpdateRegularly() = runComposeUiTest {
    val player = createReadyPlayerWithSingleItem()
    player.setPositionSupplierDrivenBy(mainClock.scheduler)
    lateinit var state: ProgressStateWithTickCount
    setContent {
      val testScope = rememberCoroutineScope()
      state = remember { ProgressStateWithTickCount(player, totalTickCount = 10, testScope) }
    }

    // Assert no progress in state even if clock advances.
    mainClock.advancePrecisely(1000)
    assertThat(player.currentPosition).isEqualTo(1000)
    assertThat(state.currentPositionProgress).isEqualTo(0f)

    // Set new tick count and assert there is just a momentary update but no automatic progress
    state.updateTotalTickCount(100)
    waitForIdle()
    mainClock.advancePrecisely(1000)

    assertThat(player.currentPosition).isAtLeast(2000)
    assertThat(state.currentPositionProgress).isEqualTo(0.1f)
  }

  @Test
  fun playerIdle_reportsInitialPlaceholderDataAndDoesNotBlockMainThread() = runComposeUiTest {
    val player = FakePlayer(playbackState = Player.STATE_IDLE, playlist = listOf())
    lateinit var state: ProgressStateWithTickCount
    setContent { state = rememberProgressStateWithTickCount(player, totalTickCount = 10) }
    assertThat(state.currentPositionProgress).isEqualTo(0f)
    assertThat(state.bufferedPositionProgress).isEqualTo(0f)

    // Wait for any pending updates to verify the state stays the same and is not blocked on the
    // main thread.
    mainClock.advancePrecisely(200)

    assertThat(state.currentPositionProgress).isEqualTo(0f)
    assertThat(state.bufferedPositionProgress).isEqualTo(0f)
  }

  @Test
  fun playerEnded_reportsFinalStateAndDoesNotBlockMainThread() = runComposeUiTest {
    val player = createReadyPlayerWithSingleItem()
    lateinit var state: ProgressStateWithTickCount
    setContent { state = rememberProgressStateWithTickCount(player, totalTickCount = 10) }
    player.setPosition(10_000)
    player.setBufferedPositionMs(10_000)
    waitForIdle()

    // Check state before change to ENDED
    assertThat(state.currentPositionProgress).isEqualTo(1f)
    assertThat(state.bufferedPositionProgress).isEqualTo(1f)

    player.setPlaybackState(Player.STATE_ENDED)

    // Immediately after the change before running the playback state update
    assertThat(state.currentPositionProgress).isEqualTo(1f)
    assertThat(state.bufferedPositionProgress).isEqualTo(1f)

    waitForIdle()

    // After completing any pending updates to ensure the main thread is not blocked.
    assertThat(state.currentPositionProgress).isEqualTo(1f)
    assertThat(state.bufferedPositionProgress).isEqualTo(1f)
  }

  @Test
  fun observe_goesOutOfScope_stopsUpdatingRegularly() = runComposeUiTest {
    val player = createReadyPlayerWithSingleItem()
    player.setPositionSupplierDrivenBy(mainClock.scheduler)
    lateinit var state: ProgressStateWithTickCount
    lateinit var observeEnabled: MutableState<Boolean>
    setContent {
      observeEnabled = remember { mutableStateOf(true) }
      val testScope = rememberCoroutineScope()
      state = remember { ProgressStateWithTickCount(player, totalTickCount = 10, testScope) }
      LaunchedEffect(observeEnabled.value) {
        if (observeEnabled.value) {
          state.observe()
        }
      }
    }

    // Assert progress if clock advances.
    mainClock.advancePrecisely(1000)
    assertThat(player.currentPosition).isEqualTo(1000)
    assertThat(state.currentPositionProgress).isEqualTo(0.1f)

    // Stop observing and verify no further updates.
    observeEnabled.value = false
    waitForIdle()
    mainClock.advancePrecisely(1000)

    assertThat(player.currentPosition).isAtLeast(2000)
    assertThat(state.currentPositionProgress).isEqualTo(0.1f)
  }

  @Test
  fun progressToPosition_returnsCorrectPosition() = runComposeUiTest {
    val player = createReadyPlayerWithSingleItem() // duration is 10_000ms
    lateinit var state: ProgressStateWithTickCount
    setContent { state = rememberProgressStateWithTickCount(player, totalTickCount = 10) }

    assertThat(state.progressToPosition(0f)).isEqualTo(0L)
    assertThat(state.progressToPosition(0.25f)).isEqualTo(2500L)
    assertThat(state.progressToPosition(0.5f)).isEqualTo(5000L)
    assertThat(state.progressToPosition(1f)).isEqualTo(10000L)
  }

  @Test
  fun progressToPosition_withNullPlayer_returnsZero() = runComposeUiTest {
    lateinit var state: ProgressStateWithTickCount
    setContent { state = rememberProgressStateWithTickCount(player = null, totalTickCount = 10) }

    assertThat(state.progressToPosition(0.5f)).isEqualTo(0L)
  }

  @Test
  fun progressToPosition_withDurationUnset_returnsZero() = runComposeUiTest {
    val player =
      FakePlayer(
        playbackState = STATE_READY,
        playWhenReady = true,
        playlist = listOf(MediaItemData.Builder("SingleItem").build()),
      )
    lateinit var state: ProgressStateWithTickCount
    setContent { state = rememberProgressStateWithTickCount(player, totalTickCount = 10) }

    assertThat(player.duration).isEqualTo(C.TIME_UNSET)
    assertThat(state.progressToPosition(0.5f)).isEqualTo(0L)
  }
}
