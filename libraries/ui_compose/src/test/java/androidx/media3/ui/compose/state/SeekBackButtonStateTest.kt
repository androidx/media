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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.test.utils.FakePlayer
import androidx.media3.ui.compose.testutils.createReadyPlayerWithTwoItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.AdditionalAnswers.delegatesTo
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

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
  fun onClick_whenCommandNotAvailable_isNoOp() {
    val player = createReadyPlayerWithTwoItems()
    player.removeCommands(Player.COMMAND_SEEK_BACK)
    val spyPlayer = mock(Player::class.java, delegatesTo<Player>(player))
    val state = SeekBackButtonState(spyPlayer)
    check(!state.isEnabled)

    state.onClick()

    verify(spyPlayer, never()).seekBack()
  }

  @Test
  fun onClick_stateBecomesDisabled_isNoOp() {
    val player = createReadyPlayerWithTwoItems()
    val spyPlayer = mock(Player::class.java, delegatesTo<Player>(player))
    lateinit var state: SeekBackButtonState
    composeTestRule.setContent { state = rememberSeekBackButtonState(spyPlayer) }

    player.removeCommands(Player.COMMAND_SEEK_BACK)
    composeTestRule.waitForIdle()
    state.onClick()

    verify(spyPlayer, never()).seekBack()
  }

  @Test
  fun onClick_justAfterCommandRemovedWhileStillEnabled_isNoOp() {
    val player = createReadyPlayerWithTwoItems()
    player.playWhenReady = false
    player.setPosition(1000)
    val spyPlayer = mock(Player::class.java, delegatesTo<Player>(player))
    lateinit var state: SeekBackButtonState
    composeTestRule.setContent { state = rememberSeekBackButtonState(spyPlayer) }

    // Simulate command becoming disabled without yet receiving the event callback
    player.removeCommands(Player.COMMAND_SEEK_BACK)
    check(state.isEnabled)
    state.onClick()

    verify(spyPlayer, never()).seekBack()
  }

  @Test
  fun playerChangeSeekBackIncrement_buttonStateGetsUpdatedValue() {
    val player = FakePlayer()

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
    player.playWhenReady = false
    player.setPosition(700)
    player.setSeekBackIncrementMs(300)
    val state = SeekBackButtonState(player)

    assertThat(player.currentPosition).isEqualTo(700)

    state.onClick()

    assertThat(player.currentPosition).isEqualTo(400)
  }

  @Test
  fun playerChangesAvailableCommandsBeforeEventListenerRegisters_observeGetsTheLatestValues_uiIconInSync() {
    val player = FakePlayer()

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

  @Test
  fun nullPlayer_buttonStateIsDisabled() {
    lateinit var state: SeekBackButtonState
    composeTestRule.setContent { state = rememberSeekBackButtonState(player = null) }

    assertThat(state.isEnabled).isFalse()
    assertThat(state.seekBackAmountMs).isEqualTo(0)
  }

  @Test
  fun nullPlayer_onClick_isNoOp() {
    val state = SeekBackButtonState(player = null)

    assertThat(state.isEnabled).isFalse()
    state.onClick()
  }

  @Test
  fun playerBecomesNullRoundTrip_buttonStateBecomesDisabledAndEnabled() {
    val player = createReadyPlayerWithTwoItems()

    lateinit var state: SeekBackButtonState
    lateinit var isPlayerNull: MutableState<Boolean>
    composeTestRule.setContent {
      isPlayerNull = remember { mutableStateOf(false) }
      state = rememberSeekBackButtonState(player = if (isPlayerNull.value) null else player)
    }
    assertThat(state.isEnabled).isTrue()

    isPlayerNull.value = true
    composeTestRule.waitForIdle()

    assertThat(state.isEnabled).isFalse()
    assertThat(state.seekBackAmountMs).isEqualTo(0)

    isPlayerNull.value = false
    composeTestRule.waitForIdle()

    assertThat(state.isEnabled).isTrue()
    assertThat(state.seekBackAmountMs).isEqualTo(C.DEFAULT_SEEK_BACK_INCREMENT_MS)
  }
}
