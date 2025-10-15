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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.test.utils.TestSimpleBasePlayer
import androidx.media3.ui.compose.testutils.createReadyPlayerWithTwoItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [SeekForwardButtonState]. */
@RunWith(AndroidJUnit4::class)
class SeekForwardButtonStateTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun addSeekForwardCommandToPlayer_buttonStateTogglesFromDisabledToEnabled() {
    val player = TestSimpleBasePlayer()
    player.removeCommands(Player.COMMAND_SEEK_FORWARD)

    lateinit var state: SeekForwardButtonState
    composeTestRule.setContent { state = rememberSeekForwardButtonState(player = player) }

    assertThat(state.isEnabled).isFalse()

    player.addCommands(Player.COMMAND_SEEK_FORWARD)
    composeTestRule.waitForIdle()

    assertThat(state.isEnabled).isTrue()
  }

  @Test
  fun removeSeekForwardCommandToPlayer_buttonStateTogglesFromEnabledToDisabled() {
    val player = createReadyPlayerWithTwoItems()
    lateinit var state: SeekForwardButtonState
    composeTestRule.setContent { state = rememberSeekForwardButtonState(player = player) }

    assertThat(state.isEnabled).isTrue()

    player.removeCommands(Player.COMMAND_SEEK_FORWARD)
    composeTestRule.waitForIdle()

    assertThat(state.isEnabled).isFalse()
  }

  @Test
  fun onClick_whenCommandNotAvailable_throwsIllegalStateException() {
    val player = TestSimpleBasePlayer()
    player.removeCommands(Player.COMMAND_SEEK_FORWARD)
    lateinit var state: SeekForwardButtonState
    composeTestRule.setContent { state = rememberSeekForwardButtonState(player = player) }

    assertThat(state.isEnabled).isFalse()
    assertThrows(IllegalStateException::class.java) { state.onClick() }
  }

  @Test
  fun onClick_stateBecomesDisabledAfterFirstClick_throwsException() {
    val player = createReadyPlayerWithTwoItems()
    val state = SeekForwardButtonState(player)

    state.onClick()
    // simulate state becoming disabled atomically, i.e. without yet receiving the relevant event
    player.removeCommands(Player.COMMAND_SEEK_FORWARD)

    assertThrows(IllegalStateException::class.java) { state.onClick() }
  }

  @Test
  fun playerChangeSeekForwardIncrement_buttonStateGetsUpdatedValue() {
    val player = TestSimpleBasePlayer()

    lateinit var state: SeekForwardButtonState
    composeTestRule.setContent { state = rememberSeekForwardButtonState(player = player) }

    assertThat(state.seekForwardAmountMs).isEqualTo(C.DEFAULT_SEEK_FORWARD_INCREMENT_MS)

    player.setSeekForwardIncrementMs(12_300)
    composeTestRule.waitForIdle()

    assertThat(state.seekForwardAmountMs).isEqualTo(12_300)
  }

  @Test
  fun positionNonZero_buttonClicked_positionJumpsForwardBySpecifiedAmount() {
    val player = createReadyPlayerWithTwoItems()
    player.setPosition(500)
    player.setSeekForwardIncrementMs(300)
    val state = SeekForwardButtonState(player)

    assertThat(player.currentPosition).isEqualTo(500)

    state.onClick()

    assertThat(player.currentPosition).isEqualTo(800)
  }

  @Test
  fun remainingDurationSmallerThanIncrement_buttonClicked_positionJumpsToTheEndOfCurrentMediaItem() {
    val player = createReadyPlayerWithTwoItems()
    val state = SeekForwardButtonState(player)

    assertThat(player.currentPosition).isEqualTo(0)
    assertThat(player.duration - player.currentPosition).isLessThan(player.seekForwardIncrement)

    state.onClick()

    assertThat(player.currentPosition).isEqualTo(player.duration)
  }

  @Test
  fun positionAtTheEnd_buttonClicked_positionDoesNotMove() {
    val player = createReadyPlayerWithTwoItems()
    player.setPosition(player.duration)
    val state = SeekForwardButtonState(player)

    assertThat(player.currentPosition).isEqualTo(player.duration)

    state.onClick()

    assertThat(player.currentPosition).isEqualTo(player.duration)
  }

  @Test
  fun playerChangesAvailableCommandsBeforeEventListenerRegisters_observeGetsTheLatestValues_uiIconInSync() {
    val player = TestSimpleBasePlayer()

    lateinit var state: SeekForwardButtonState
    composeTestRule.setContent {
      // Schedule LaunchedEffect to update player state before SeekForwardButtonState is created.
      // This update could end up being executed *before* SeekForwardButtonState schedules the start
      // of
      // event listening and we don't want to lose it.
      LaunchedEffect(player) { player.removeCommands(Player.COMMAND_SEEK_FORWARD) }
      state = rememberSeekForwardButtonState(player = player)
    }

    // UI syncs up with the fact that SeekForwardButton is now disabled
    assertThat(state.isEnabled).isFalse()
  }
}
