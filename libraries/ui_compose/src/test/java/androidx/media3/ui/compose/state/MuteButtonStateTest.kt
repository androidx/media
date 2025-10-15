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
import androidx.media3.common.Player
import androidx.media3.test.utils.TestSimpleBasePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [MuteButtonState]. */
@RunWith(AndroidJUnit4::class)
class MuteButtonStateTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun playerHasVolume_mutePlayer_mutedIconShowing() {
    val player = TestSimpleBasePlayer()
    player.volume = 0.5f

    lateinit var state: MuteButtonState
    composeTestRule.setContent { state = rememberMuteButtonState(player) }

    assertThat(state.showMuted).isFalse()

    player.mute()
    composeTestRule.waitForIdle()

    assertThat(state.showMuted).isTrue()
  }

  @Test
  fun playerIsMuted_setNonZeroVolumeOnPlayer_unmutedIconShowing() {
    val player = TestSimpleBasePlayer()
    player.mute()

    lateinit var state: MuteButtonState
    composeTestRule.setContent { state = rememberMuteButtonState(player) }

    assertThat(state.showMuted).isTrue()

    player.setVolume(0.34f)
    composeTestRule.waitForIdle()

    assertThat(state.showMuted).isFalse()
  }

  @Test
  fun onClick_stateIsDisabled_throwsException() {
    val player = TestSimpleBasePlayer()
    player.removeCommands(Player.COMMAND_SET_VOLUME)
    val state = MuteButtonState(player)

    assertThat(state.isEnabled).isFalse()
    assertThrows(IllegalStateException::class.java) { state.onClick() }
  }

  @Test
  fun onClick_stateBecomesDisabledAfterFirstClick_throwsException() {
    val player = TestSimpleBasePlayer()
    val state = MuteButtonState(player)

    state.onClick()
    // simulate state becoming disabled atomically, i.e. without yet receiving the relevant event
    player.removeCommands(Player.COMMAND_SET_VOLUME)

    assertThrows(IllegalStateException::class.java) { state.onClick() }
  }

  @Test
  fun addSetVolumeCommandToPlayer_buttonStateTogglesFromDisabledToEnabled() {
    val player = TestSimpleBasePlayer()
    player.removeCommands(Player.COMMAND_SET_VOLUME)

    lateinit var state: MuteButtonState
    composeTestRule.setContent { state = rememberMuteButtonState(player) }

    assertThat(state.isEnabled).isFalse()

    player.addCommands(Player.COMMAND_SET_VOLUME)
    composeTestRule.waitForIdle()

    assertThat(state.isEnabled).isTrue()
  }

  @Test
  fun addGetVolumeCommandToPlayer_buttonStateTogglesFromDisabledToEnabled() {
    val player = TestSimpleBasePlayer()
    player.removeCommands(Player.COMMAND_GET_VOLUME)

    lateinit var state: MuteButtonState
    composeTestRule.setContent { state = rememberMuteButtonState(player) }

    assertThat(state.isEnabled).isFalse()

    player.addCommands(Player.COMMAND_GET_VOLUME)
    composeTestRule.waitForIdle()

    assertThat(state.isEnabled).isTrue()
  }

  @Test
  fun addSetGetVolumeCommandToPlayer_buttonStateTogglesFromDisabledToEnabled() {
    val player = TestSimpleBasePlayer()
    player.removeCommands(Player.COMMAND_GET_VOLUME, Player.COMMAND_SET_VOLUME)
    lateinit var state: MuteButtonState
    composeTestRule.setContent { state = rememberMuteButtonState(player) }
    assertThat(state.isEnabled).isFalse()

    player.addCommands(Player.COMMAND_GET_VOLUME)
    composeTestRule.waitForIdle()
    assertThat(state.isEnabled).isFalse()

    player.addCommands(Player.COMMAND_SET_VOLUME)
    composeTestRule.waitForIdle()
    assertThat(state.isEnabled).isTrue()
  }

  @Test
  fun noGetVolumeCommand_volumeNonZero_buttonStateShowsUnmuted() {
    val player = TestSimpleBasePlayer()
    player.addCommands(Player.COMMAND_SET_VOLUME)
    player.removeCommands(Player.COMMAND_GET_VOLUME)
    player.volume = 0.7f

    lateinit var state: MuteButtonState
    composeTestRule.setContent { state = rememberMuteButtonState(player) }

    assertThat(state.isEnabled).isFalse()
    assertThat(state.showMuted).isFalse()
  }

  @Test
  fun noGetVolumeCommand_volumeZero_buttonStateShowsUnmuted() {
    val player = TestSimpleBasePlayer()
    player.addCommands(Player.COMMAND_SET_VOLUME)
    player.removeCommands(Player.COMMAND_GET_VOLUME)
    player.volume = 0f

    lateinit var state: MuteButtonState
    composeTestRule.setContent { state = rememberMuteButtonState(player) }

    assertThat(state.isEnabled).isFalse()
    assertThat(state.showMuted).isFalse()
  }

  @Test
  fun playerHasVolume_buttonClicked_playerMuted() {
    val player = TestSimpleBasePlayer()
    player.volume = 0.7f

    val state = MuteButtonState(player)

    assertThat(state.showMuted).isFalse()

    state.onClick()

    assertThat(player.volume).isEqualTo(0.0f)
  }

  @Test
  fun playerIsMutedWithAnonZeroPrevVolume_buttonClicked_playerVolumeReturnsToPreMuted() {
    val player = TestSimpleBasePlayer()
    player.volume = 0.7f
    player.mute()
    val state = MuteButtonState(player)
    assertThat(state.showMuted).isTrue()

    state.onClick()

    assertThat(player.volume).isEqualTo(0.7f)
  }

  @Test
  fun playerIsMutedFromTheStart_buttonClicked_playerVolumeIs1() {
    val player = TestSimpleBasePlayer()
    player.mute()
    val state = MuteButtonState(player)

    assertThat(state.showMuted).isTrue()

    state.onClick()

    assertThat(player.volume).isEqualTo(1.0f)
  }

  @Test
  fun playerIsMutedBeforeEventListenerRegisters_observeGetsTheLatestValues_uiIconInSync() {
    val player = TestSimpleBasePlayer()

    lateinit var state: MuteButtonState
    composeTestRule.setContent {
      // Schedule LaunchedEffect to update player state before MuteButtonState is created.
      // This update could end up being executed *before* MuteButtonState schedules the start
      // of event listening and we don't want to lose it.
      LaunchedEffect(player) { player.mute() }
      state = rememberMuteButtonState(player)
    }

    // UI catches up with the fact that player.mute() happened because observe() started by getting
    // the most recent values
    assertThat(state.showMuted).isTrue()
  }

  @Test
  fun playerChangesAvailableCommandsBeforeEventListenerRegisters_observeGetsTheLatestValues_uiIconInSync() {
    val player = TestSimpleBasePlayer()

    lateinit var state: MuteButtonState
    composeTestRule.setContent {
      // Schedule LaunchedEffect to update player state before MuteButtonState is created.
      // This update could end up being executed *before* MuteButtonState schedules the start of
      // event listening and we don't want to lose it.
      LaunchedEffect(player) { player.removeCommands(Player.COMMAND_SET_VOLUME) }
      state = rememberMuteButtonState(player)
    }

    // UI syncs up with the fact that MuteButton is now disabled
    assertThat(state.isEnabled).isFalse()
  }
}
