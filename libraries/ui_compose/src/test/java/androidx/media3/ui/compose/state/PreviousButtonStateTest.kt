/*
 * Copyright 2024 The Android Open Source Project
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
import androidx.media3.common.Player
import androidx.media3.test.utils.FakePlayer
import androidx.media3.ui.compose.testutils.createReadyPlayerWithTwoItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [PreviousButtonState]. */
@RunWith(AndroidJUnit4::class)
class PreviousButtonStateTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun addSeekPrevCommandToPlayer_buttonStateTogglesFromDisabledToEnabled() {
    val player = createReadyPlayerWithTwoItems()
    player.removeCommands(Player.COMMAND_SEEK_TO_PREVIOUS)

    lateinit var state: PreviousButtonState
    composeTestRule.setContent { state = rememberPreviousButtonState(player = player) }

    assertThat(state.isEnabled).isFalse()

    composeTestRule.runOnUiThread { player.addCommands(Player.COMMAND_SEEK_TO_PREVIOUS) }
    composeTestRule.waitForIdle()

    assertThat(state.isEnabled).isTrue()
  }

  @Test
  fun removeSeekPrevCommandToPlayer_buttonStateTogglesFromEnabledToDisabled() {
    val player = createReadyPlayerWithTwoItems()

    lateinit var state: PreviousButtonState
    composeTestRule.setContent { state = rememberPreviousButtonState(player = player) }

    assertThat(state.isEnabled).isTrue()

    composeTestRule.runOnUiThread { player.removeCommands(Player.COMMAND_SEEK_TO_PREVIOUS) }
    composeTestRule.waitForIdle()

    assertThat(state.isEnabled).isFalse()
  }

  @Test
  fun onClick_whenCommandNotAvailable_throwsIllegalStateException() {
    val player = createReadyPlayerWithTwoItems()
    player.removeCommands(Player.COMMAND_SEEK_TO_PREVIOUS)
    val state = PreviousButtonState(player)

    assertThat(state.isEnabled).isFalse()
    assertThrows(IllegalStateException::class.java) { state.onClick() }
  }

  @Test
  fun onClick_stateBecomesDisabled_throwsException() {
    val player = createReadyPlayerWithTwoItems()
    player.seekToDefaultPosition(1)
    lateinit var state: PreviousButtonState
    composeTestRule.setContent { state = rememberPreviousButtonState(player) }

    player.removeCommands(Player.COMMAND_SEEK_TO_PREVIOUS)
    composeTestRule.waitForIdle()

    assertThrows(IllegalStateException::class.java) { state.onClick() }
  }

  @Test
  fun onClick_justAfterCommandRemovedWhileStillEnabled_isNoOp() {
    val player = createReadyPlayerWithTwoItems()
    player.seekToDefaultPosition(1)
    lateinit var state: PreviousButtonState
    composeTestRule.setContent { state = rememberPreviousButtonState(player) }

    // Simulate command becoming disabled without yet receiving the event callback
    player.removeCommands(Player.COMMAND_SEEK_TO_PREVIOUS)
    check(state.isEnabled)
    state.onClick()

    assertThat(player.currentMediaItemIndex).isEqualTo(1)
  }

  @Test
  fun playerInReadyState_prevButtonClicked_sameItemPlayingFromBeginning() {
    val player = createReadyPlayerWithTwoItems()
    val state = PreviousButtonState(player)

    assertThat(player.currentMediaItemIndex).isEqualTo(0)

    state.onClick()

    assertThat(player.currentMediaItemIndex).isEqualTo(0)
  }

  @Test
  fun playerChangesAvailableCommandsBeforeEventListenerRegisters_observeGetsTheLatestValues_uiIconInSync() {
    val player = FakePlayer()

    lateinit var state: PreviousButtonState
    composeTestRule.setContent {
      // Schedule LaunchedEffect to update player state before PreviousButtonState is created.
      // This update could end up being executed *before* PreviousButtonState schedules the start of
      // event listening and we don't want to lose it.
      LaunchedEffect(player) { player.removeCommands(Player.COMMAND_SEEK_TO_PREVIOUS) }
      state = rememberPreviousButtonState(player = player)
    }

    // UI syncs up with the fact that PreviousButton is now disabled
    assertThat(state.isEnabled).isFalse()
  }

  @Test
  fun nullPlayer_buttonStateIsDisabled() {
    lateinit var state: PreviousButtonState
    composeTestRule.setContent { state = rememberPreviousButtonState(player = null) }

    assertThat(state.isEnabled).isFalse()
  }

  @Test
  fun nullPlayer_onClick_throwsIllegalStateException() {
    val state = PreviousButtonState(player = null)

    assertThat(state.isEnabled).isFalse()
    assertThrows(IllegalStateException::class.java) { state.onClick() }
  }

  @Test
  fun playerBecomesNullRoundTrip_buttonStateBecomesDisabledAndEnabled() {
    val player = createReadyPlayerWithTwoItems()

    lateinit var state: PreviousButtonState
    lateinit var isPlayerNull: MutableState<Boolean>
    composeTestRule.setContent {
      isPlayerNull = remember { mutableStateOf(false) }
      state = rememberPreviousButtonState(player = if (isPlayerNull.value) null else player)
    }
    assertThat(state.isEnabled).isTrue()

    isPlayerNull.value = true
    composeTestRule.waitForIdle()

    assertThat(state.isEnabled).isFalse()

    isPlayerNull.value = false
    composeTestRule.waitForIdle()

    assertThat(state.isEnabled).isTrue()
  }
}
