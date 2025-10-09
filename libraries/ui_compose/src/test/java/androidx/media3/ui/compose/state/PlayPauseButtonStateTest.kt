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
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.test.utils.TestSimpleBasePlayer
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance
import androidx.media3.ui.compose.testutils.createReadyPlayerWithTwoItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [PlayPauseButtonState]. */
@RunWith(AndroidJUnit4::class)
class PlayPauseButtonStateTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun playerIsBuffering_pausePlayer_playIconShowing() {
    val player = createReadyPlayerWithTwoItems()
    player.playbackState = Player.STATE_BUFFERING
    lateinit var state: PlayPauseButtonState
    composeTestRule.setContent { state = rememberPlayPauseButtonState(player = player) }

    assertThat(state.showPlay).isFalse()

    player.pause()
    composeTestRule.waitForIdle()

    assertThat(state.showPlay).isTrue()
  }

  @Test
  fun playerIsIdling_preparePlayer_pauseIconShowing() {
    val player = createReadyPlayerWithTwoItems()
    player.playbackState = Player.STATE_IDLE
    lateinit var state: PlayPauseButtonState
    composeTestRule.setContent { state = rememberPlayPauseButtonState(player = player) }

    assertThat(state.showPlay).isTrue()

    player.prepare()
    composeTestRule.waitForIdle()

    assertThat(state.showPlay).isFalse()
  }

  @Test
  fun onClick_whenCommandNotAvailable_throwsIllegalStateException() {
    val player = createReadyPlayerWithTwoItems()
    player.removeCommands(Player.COMMAND_PLAY_PAUSE)
    val state = PlayPauseButtonState(player)

    assertThat(state.isEnabled).isFalse()
    assertThrows(IllegalStateException::class.java) { state.onClick() }
  }

  @Test
  fun onClick_stateBecomesDisabledAfterFirstClick_throwsException() {
    val player = createReadyPlayerWithTwoItems()
    val state = PlayPauseButtonState(player)

    state.onClick()
    // simulate state becoming disabled atomically, i.e. without yet receiving the relevant event
    player.removeCommands(Player.COMMAND_PLAY_PAUSE)

    assertThrows(IllegalStateException::class.java) { state.onClick() }
  }

  @Test
  fun addPlayPauseCommandToPlayer_buttonStateTogglesFromDisabledToEnabled() {
    val player = createReadyPlayerWithTwoItems()
    player.removeCommands(Player.COMMAND_PLAY_PAUSE)

    lateinit var state: PlayPauseButtonState
    composeTestRule.setContent { state = rememberPlayPauseButtonState(player = player) }

    assertThat(state.isEnabled).isFalse()

    player.addCommands(Player.COMMAND_PLAY_PAUSE)
    composeTestRule.waitForIdle()

    assertThat(state.isEnabled).isTrue()
  }

  @Test
  fun playerInReadyState_buttonClicked_playerPaused() {
    val player = createReadyPlayerWithTwoItems()
    val state = PlayPauseButtonState(player)

    assertThat(state.showPlay).isFalse()
    assertThat(player.playWhenReady).isTrue()

    state.onClick() // Player pauses

    assertThat(player.playWhenReady).isFalse()
  }

  @Test
  fun playerInEndedState_buttonClicked_playerPlaysFromBeginning() {
    val player =
      TestSimpleBasePlayer(
        playbackState = Player.STATE_ENDED,
        playlist = listOf(MediaItemData.Builder("SingleItem").setDurationUs(456).build()),
      )
    player.setPosition(456)
    val state = PlayPauseButtonState(player)

    assertThat(state.showPlay).isTrue()

    state.onClick() // Player seeks to default position and plays

    // The position is masked immediately
    assertThat(player.contentPosition).isEqualTo(0)

    advance(player).untilState(Player.STATE_READY)
    // The player starts playing when the buffering from the seek is complete
    assertThat(player.isPlaying).isTrue()
  }

  @Test
  fun playerInIdleState_buttonClicked_playerBuffersButDoesntPlay() {
    val player = createReadyPlayerWithTwoItems()
    player.playbackState = Player.STATE_IDLE
    val state = PlayPauseButtonState(player)

    assertThat(state.showPlay).isTrue() // Player not prepared, Play icon

    state.onClick() // Player prepares and goes into buffering

    assertThat(player.playWhenReady).isTrue()
    assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
    assertThat(player.isPlaying).isFalse()
  }

  @Test
  fun playerIsScheduledToPlayBeforeEventListenerRegisters_observeGetsTheLatestValues_uiIconInSync() {
    val player = createReadyPlayerWithTwoItems()
    player.playbackState = Player.STATE_BUFFERING

    lateinit var state: PlayPauseButtonState
    composeTestRule.setContent {
      // Schedule LaunchedEffect to update player state before PlayPauseButtonState is created.
      // This update could end up being executed *before* PlayPauseButtonState schedules the start
      // of event listening and we don't want to lose it.
      LaunchedEffect(player) { player.play() }
      state = rememberPlayPauseButtonState(player = player)
    }

    // UI catches up with the fact that player.play() happened because observe() started by getting
    // the most recent values
    assertThat(state.showPlay).isFalse()
  }
}
