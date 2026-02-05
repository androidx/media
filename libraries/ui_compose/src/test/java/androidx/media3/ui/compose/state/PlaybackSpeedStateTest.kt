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
import androidx.media3.common.Player
import androidx.media3.test.utils.FakePlayer
import androidx.media3.ui.compose.testutils.createReadyPlayerWithTwoItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.AdditionalAnswers.delegatesTo
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify

/** Unit test for [PlaybackSpeedState]. */
@RunWith(AndroidJUnit4::class)
class PlaybackSpeedStateTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun addSetSpeedAndPitchCommandToPlayer_stateTogglesFromDisabledToEnabled() {
    val player = FakePlayer()
    player.removeCommands(Player.COMMAND_SET_SPEED_AND_PITCH)
    lateinit var state: PlaybackSpeedState
    composeTestRule.setContent { state = rememberPlaybackSpeedState(player = player) }

    assertThat(state.isEnabled).isFalse()

    player.addCommands(Player.COMMAND_SET_SPEED_AND_PITCH)
    composeTestRule.waitForIdle()

    assertThat(state.isEnabled).isTrue()
  }

  @Test
  fun removeSetSpeedAndPitchCommandToPlayer_stateTogglesFromEnabledToDisabled() {
    val player = FakePlayer()
    lateinit var state: PlaybackSpeedState
    composeTestRule.setContent { state = rememberPlaybackSpeedState(player = player) }

    assertThat(state.isEnabled).isTrue()

    player.removeCommands(Player.COMMAND_SET_SPEED_AND_PITCH)
    composeTestRule.waitForIdle()

    assertThat(state.isEnabled).isFalse()
  }

  @Test
  fun updatePlaybackSpeed_whenCommandNotAvailable_isNoOp() {
    val player = FakePlayer()
    player.removeCommands(Player.COMMAND_SET_SPEED_AND_PITCH)
    val spyPlayer = mock(Player::class.java, delegatesTo<Player>(player))
    val state = PlaybackSpeedState(spyPlayer)
    check(!state.isEnabled)
    reset(spyPlayer)

    state.updatePlaybackSpeed(1.5f)

    verify(spyPlayer, never()).setPlaybackSpeed(anyFloat())
  }

  @Test
  fun updatePlaybackSpeed_stateBecomesDisabled_isNoOp() {
    val player = createReadyPlayerWithTwoItems()
    player.setPlaybackSpeed(2f)
    val spyPlayer = mock(Player::class.java, delegatesTo<Player>(player))
    lateinit var state: PlaybackSpeedState
    composeTestRule.setContent { state = rememberPlaybackSpeedState(spyPlayer) }
    reset(spyPlayer)

    player.removeCommands(Player.COMMAND_SET_SPEED_AND_PITCH)
    composeTestRule.waitForIdle()

    state.updatePlaybackSpeed(1.5f)

    verify(spyPlayer, never()).setPlaybackSpeed(anyFloat())
  }

  @Test
  fun updatePlaybackSpeed_justAfterCommandRemovedWhileStillEnabled_isNoOp() {
    val player = createReadyPlayerWithTwoItems()
    player.setPlaybackSpeed(2f)
    val spyPlayer = mock(Player::class.java, delegatesTo<Player>(player))
    lateinit var state: PlaybackSpeedState
    composeTestRule.setContent { state = rememberPlaybackSpeedState(spyPlayer) }
    reset(spyPlayer)

    // Simulate command becoming disabled without yet receiving the event callback
    player.removeCommands(Player.COMMAND_SET_SPEED_AND_PITCH)
    check(state.isEnabled)
    state.updatePlaybackSpeed(1.5f)

    verify(spyPlayer, never()).setPlaybackSpeed(anyFloat())
  }

  @Test
  fun playerPlaybackSpeedChanged_statePlaybackSpeedChanged() {
    val player = FakePlayer()

    lateinit var state: PlaybackSpeedState
    composeTestRule.setContent { state = rememberPlaybackSpeedState(player = player) }

    assertThat(state.playbackSpeed).isEqualTo(1f)

    player.playbackParameters = player.playbackParameters.withSpeed(1.5f)
    composeTestRule.waitForIdle()

    assertThat(state.playbackSpeed).isEqualTo(1.5f)
  }

  @Test
  fun stateUpdatePlaybackSpeed_playerPlaybackSpeedChanged() {
    val player = FakePlayer()
    val state = PlaybackSpeedState(player)
    assertThat(state.playbackSpeed).isEqualTo(1f)

    state.updatePlaybackSpeed(2.7f)

    assertThat(player.playbackParameters.speed).isEqualTo(2.7f)
  }

  @Test
  fun playerIncreasesPlaybackSpeedBeforeEventListenerRegisters_observeGetsTheLatestValues_uiIconInSync() {
    val player = FakePlayer()

    lateinit var state: PlaybackSpeedState
    composeTestRule.setContent {
      // Schedule LaunchedEffect to update player state before PlaybackSpeedState is created.
      // This update could end up being executed *before* PlaybackSpeedState schedules the start of
      // event listening and we don't want to lose it.
      LaunchedEffect(player) { player.setPlaybackSpeed(player.playbackParameters.speed + 1f) }
      state = rememberPlaybackSpeedState(player = player)
    }

    // UI syncs up with the fact that we increased playback speed
    assertThat(state.playbackSpeed).isEqualTo(2f)
  }

  @Test
  fun nullPlayer_buttonStateIsDisabled() {
    lateinit var state: PlaybackSpeedState
    composeTestRule.setContent { state = rememberPlaybackSpeedState(player = null) }

    assertThat(state.isEnabled).isFalse()
  }

  @Test
  fun nullPlayer_updatePlaybackSpeed_isNoOp() {
    val state = PlaybackSpeedState(player = null)

    assertThat(state.isEnabled).isFalse()
    state.updatePlaybackSpeed(1.5f)
  }

  @Test
  fun playerBecomesNullRoundTrip_buttonStateBecomesDisabledAndEnabled() {
    val player = createReadyPlayerWithTwoItems()

    lateinit var state: PlaybackSpeedState
    lateinit var isPlayerNull: MutableState<Boolean>
    composeTestRule.setContent {
      isPlayerNull = remember { mutableStateOf(false) }
      state = rememberPlaybackSpeedState(player = if (isPlayerNull.value) null else player)
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
