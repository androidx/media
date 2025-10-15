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

/** Unit test for [SeekBackButtonState]. */
@RunWith(AndroidJUnit4::class)
class SeekBackButtonStateTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun addSeekBackCommandToPlayer_buttonStateTogglesFromDisabledToEnabled() {
    val player = createReadyPlayerWithTwoItems()
    player.removeCommands(Player.COMMAND_SEEK_BACK)

    lateinit var state: SeekBackButtonState
    composeTestRule.setContent { state = rememberSeekBackButtonState(player = player) }

    assertThat(state.isEnabled).isFalse()

    player.addCommands(Player.COMMAND_SEEK_BACK)
    composeTestRule.waitForIdle()

    assertThat(state.isEnabled).isTrue()
  }

  @Test
  fun removeSeekBackCommandToPlayer_buttonStateTogglesFromEnabledToDisabled() {
    val player = createReadyPlayerWithTwoItems()
    lateinit var state: SeekBackButtonState
    composeTestRule.setContent { state = rememberSeekBackButtonState(player = player) }

    assertThat(state.isEnabled).isTrue()

    player.removeCommands(Player.COMMAND_SEEK_BACK)
    composeTestRule.waitForIdle()

    assertThat(state.isEnabled).isFalse()
  }

  @Test
  fun onClick_whenCommandNotAvailable_throwsIllegalStateException() {
    val player = createReadyPlayerWithTwoItems()
    player.removeCommands(Player.COMMAND_SEEK_BACK)
    val state = SeekBackButtonState(player)

    assertThat(state.isEnabled).isFalse()
    assertThrows(IllegalStateException::class.java) { state.onClick() }
  }

  @Test
  fun onClick_stateBecomesDisabledAfterFirstClick_throwsException() {
    val player = createReadyPlayerWithTwoItems()
    val state = SeekBackButtonState(player)

    state.onClick()
    // simulate state becoming disabled atomically, i.e. without yet receiving the relevant event
    player.removeCommands(Player.COMMAND_SEEK_BACK)

    assertThrows(IllegalStateException::class.java) { state.onClick() }
  }

  @Test
  fun playerChangeSeekBackIncrement_buttonStateGetsUpdatedValue() {
    val player = TestSimpleBasePlayer()

    lateinit var state: SeekBackButtonState
    composeTestRule.setContent { state = rememberSeekBackButtonState(player = player) }

    assertThat(state.seekBackAmountMs).isEqualTo(C.DEFAULT_SEEK_BACK_INCREMENT_MS)

    player.setSeekBackIncrementMs(1_230)
    composeTestRule.waitForIdle()

    assertThat(state.seekBackAmountMs).isEqualTo(1_230)
  }

  @Test
  fun positionAtTheStart_buttonClicked_positionDoesNotChange() {
    val player = createReadyPlayerWithTwoItems()
    val state = SeekBackButtonState(player)

    assertThat(player.currentPosition).isEqualTo(0)

    state.onClick()

    assertThat(player.currentPosition).isEqualTo(0)
  }

  @Test
  fun positionNonZero_buttonClicked_positionJumpsBackBySpecifiedAmount() {
    val player = createReadyPlayerWithTwoItems()
    player.setPosition(700)
    player.setSeekBackIncrementMs(300)
    val state = SeekBackButtonState(player)

    assertThat(player.currentPosition).isEqualTo(700)

    state.onClick()

    assertThat(player.currentPosition).isEqualTo(400)
  }

  @Test
  fun playerChangesAvailableCommandsBeforeEventListenerRegisters_observeGetsTheLatestValues_uiIconInSync() {
    val player = TestSimpleBasePlayer()

    lateinit var state: SeekBackButtonState
    composeTestRule.setContent {
      // Schedule LaunchedEffect to update player state before SeekBackButtonState is created.
      // This update could end up being executed *before* SeekBackButtonState schedules the start of
      // event listening and we don't want to lose it.
      LaunchedEffect(player) { player.removeCommands(Player.COMMAND_SEEK_BACK) }
      state = rememberSeekBackButtonState(player = player)
    }

    // UI syncs up with the fact that SeekBackButton is now disabled
    assertThat(state.isEnabled).isFalse()
  }
}
