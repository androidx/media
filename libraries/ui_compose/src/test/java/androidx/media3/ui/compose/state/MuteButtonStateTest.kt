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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.media3.common.Player
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.AdditionalAnswers.delegatesTo
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

/** Unit test for [MuteButtonState]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MuteButtonStateTest {

  @Test
  fun playerHasVolume_mutePlayer_mutedIconShowing() = runComposeUiTest {
    val player = FakePlayer()
    player.volume = 0.5f

    lateinit var state: MuteButtonState
    setContent { state = rememberMuteButtonState(player) }

    assertThat(state.showMuted).isFalse()

    player.mute()
    waitForIdle()

    assertThat(state.showMuted).isTrue()
  }

  @Test
  fun playerIsMuted_setNonZeroVolumeOnPlayer_unmutedIconShowing() = runComposeUiTest {
    val player = FakePlayer()
    player.mute()

    lateinit var state: MuteButtonState
    setContent { state = rememberMuteButtonState(player) }

    assertThat(state.showMuted).isTrue()

    player.setVolume(0.34f)
    waitForIdle()

    assertThat(state.showMuted).isFalse()
  }

  @Test
  fun onClick_stateIsDisabled_isNoOp() {
    val player = FakePlayer()
    player.removeCommands(Player.COMMAND_SET_VOLUME)
    val spyPlayer = mock(Player::class.java, delegatesTo<Player>(player))
    val state = MuteButtonState(spyPlayer)
    check(!state.isEnabled)

    state.onClick()

    verify(spyPlayer, never()).mute()
    verify(spyPlayer, never()).unmute()
  }

  @Test
  fun onClick_stateBecomesDisabled_isNoOp() = runComposeUiTest {
    val player = FakePlayer()
    val spyPlayer = mock(Player::class.java, delegatesTo<Player>(player))
    lateinit var state: MuteButtonState
    setContent { state = rememberMuteButtonState(spyPlayer) }

    player.removeCommands(Player.COMMAND_SET_VOLUME)
    waitForIdle()
    state.onClick()

    verify(spyPlayer, never()).mute()
    verify(spyPlayer, never()).unmute()
  }

  @Test
  fun onClick_justAfterCommandRemovedWhileStillEnabled_isNoOp() = runComposeUiTest {
    val player = FakePlayer()
    player.volume = 0.7f
    val spyPlayer = mock(Player::class.java, delegatesTo<Player>(player))
    lateinit var state: MuteButtonState
    setContent { state = rememberMuteButtonState(spyPlayer) }

    // Simulate command becoming disabled without yet receiving the event callback
    player.removeCommands(Player.COMMAND_SET_VOLUME)
    check(state.isEnabled)
    state.onClick()

    verify(spyPlayer, never()).mute()
    verify(spyPlayer, never()).unmute()
  }

  @Test
  fun addSetVolumeCommandToPlayer_buttonStateTogglesFromDisabledToEnabled() = runComposeUiTest {
    val player = FakePlayer()
    player.removeCommands(Player.COMMAND_SET_VOLUME)

    lateinit var state: MuteButtonState
    setContent { state = rememberMuteButtonState(player) }

    assertThat(state.isEnabled).isFalse()

    player.addCommands(Player.COMMAND_SET_VOLUME)
    waitForIdle()

    assertThat(state.isEnabled).isTrue()
  }

  @Test
  fun addGetVolumeCommandToPlayer_buttonStateTogglesFromDisabledToEnabled() = runComposeUiTest {
    val player = FakePlayer()
    player.removeCommands(Player.COMMAND_GET_VOLUME)

    lateinit var state: MuteButtonState
    setContent { state = rememberMuteButtonState(player) }

    assertThat(state.isEnabled).isFalse()

    player.addCommands(Player.COMMAND_GET_VOLUME)
    waitForIdle()

    assertThat(state.isEnabled).isTrue()
  }

  @Test
  fun addSetGetVolumeCommandToPlayer_buttonStateTogglesFromDisabledToEnabled() = runComposeUiTest {
    val player = FakePlayer()
    player.removeCommands(Player.COMMAND_GET_VOLUME, Player.COMMAND_SET_VOLUME)
    lateinit var state: MuteButtonState
    setContent { state = rememberMuteButtonState(player) }
    assertThat(state.isEnabled).isFalse()

    player.addCommands(Player.COMMAND_GET_VOLUME)
    waitForIdle()
    assertThat(state.isEnabled).isFalse()

    player.addCommands(Player.COMMAND_SET_VOLUME)
    waitForIdle()
    assertThat(state.isEnabled).isTrue()
  }

  @Test
  fun noGetVolumeCommand_volumeNonZero_buttonStateShowsUnmuted() = runComposeUiTest {
    val player = FakePlayer()
    player.addCommands(Player.COMMAND_SET_VOLUME)
    player.removeCommands(Player.COMMAND_GET_VOLUME)
    player.volume = 0.7f

    lateinit var state: MuteButtonState
    setContent { state = rememberMuteButtonState(player) }

    assertThat(state.isEnabled).isFalse()
    assertThat(state.showMuted).isFalse()
  }

  @Test
  fun noGetVolumeCommand_volumeZero_buttonStateShowsUnmuted() = runComposeUiTest {
    val player = FakePlayer()
    player.addCommands(Player.COMMAND_SET_VOLUME)
    player.removeCommands(Player.COMMAND_GET_VOLUME)
    player.volume = 0f

    lateinit var state: MuteButtonState
    setContent { state = rememberMuteButtonState(player) }

    assertThat(state.isEnabled).isFalse()
    assertThat(state.showMuted).isFalse()
  }

  @Test
  fun playerHasVolume_buttonClicked_playerMuted() {
    val player = FakePlayer()
    player.volume = 0.7f

    val state = MuteButtonState(player)

    assertThat(state.showMuted).isFalse()

    state.onClick()

    assertThat(player.volume).isEqualTo(0.0f)
  }

  @Test
  fun playerIsMutedWithAnonZeroPrevVolume_buttonClicked_playerVolumeReturnsToPreMuted() {
    val player = FakePlayer()
    player.volume = 0.7f
    player.mute()
    val state = MuteButtonState(player)
    assertThat(state.showMuted).isTrue()

    state.onClick()

    assertThat(player.volume).isEqualTo(0.7f)
  }

  @Test
  fun playerIsMutedFromTheStart_buttonClicked_playerVolumeIs1() {
    val player = FakePlayer()
    player.mute()
    val state = MuteButtonState(player)

    assertThat(state.showMuted).isTrue()

    state.onClick()

    assertThat(player.volume).isEqualTo(1.0f)
  }

  @Test
  fun playerIsMutedBeforeEventListenerRegisters_observeGetsTheLatestValues_uiIconInSync() =
    runComposeUiTest {
      val player = FakePlayer()

      lateinit var state: MuteButtonState
      setContent {
        // Schedule LaunchedEffect to update player state before MuteButtonState is created.
        // This update could end up being executed *before* MuteButtonState schedules the start
        // of event listening and we don't want to lose it.
        LaunchedEffect(player) { player.mute() }
        state = rememberMuteButtonState(player)
      }

      // UI catches up with the fact that player.mute() happened because observe() started by
      // getting
      // the most recent values
      assertThat(state.showMuted).isTrue()
    }

  @Test
  fun playerChangesAvailableCommandsBeforeEventListenerRegisters_observeGetsTheLatestValues_uiIconInSync() =
    runComposeUiTest {
      val player = FakePlayer()

      lateinit var state: MuteButtonState
      setContent {
        // Schedule LaunchedEffect to update player state before MuteButtonState is created.
        // This update could end up being executed *before* MuteButtonState schedules the start of
        // event listening and we don't want to lose it.
        LaunchedEffect(player) { player.removeCommands(Player.COMMAND_SET_VOLUME) }
        state = rememberMuteButtonState(player)
      }

      // UI syncs up with the fact that MuteButton is now disabled
      assertThat(state.isEnabled).isFalse()
    }

  @Test
  fun nullPlayer_buttonStateIsDisabled() = runComposeUiTest {
    lateinit var state: MuteButtonState
    setContent { state = rememberMuteButtonState(player = null) }

    assertThat(state.isEnabled).isFalse()
    assertThat(state.showMuted).isFalse()
  }

  @Test
  fun nullPlayer_onClick_isNoOp() {
    val state = MuteButtonState(player = null)

    assertThat(state.isEnabled).isFalse()
    state.onClick()
  }

  @Test
  fun playerBecomesNullRoundTrip_buttonStateBecomesDisabledAndEnabled() = runComposeUiTest {
    val player = FakePlayer()

    lateinit var state: MuteButtonState
    lateinit var isPlayerNull: MutableState<Boolean>
    setContent {
      isPlayerNull = remember { mutableStateOf(false) }
      state = rememberMuteButtonState(player = if (isPlayerNull.value) null else player)
    }
    assertThat(state.isEnabled).isTrue()

    isPlayerNull.value = true
    waitForIdle()

    assertThat(state.isEnabled).isFalse()

    isPlayerNull.value = false
    waitForIdle()

    assertThat(state.isEnabled).isTrue()
  }
}
