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
import androidx.media3.ui.compose.utils.TestPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [SeekForwardButtonState]. */
@RunWith(AndroidJUnit4::class)
class SeekForwardButtonStateTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun addSeekForwardCommandToPlayer_buttonStateTogglesFromDisabledToEnabled() {
    val player = TestPlayer()
    player.playbackState = Player.STATE_READY
    player.playWhenReady = true
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
    val player = TestPlayer()
    player.playbackState = Player.STATE_READY
    player.playWhenReady = true

    lateinit var state: SeekForwardButtonState
    composeTestRule.setContent { state = rememberSeekForwardButtonState(player = player) }

    assertThat(state.isEnabled).isTrue()

    player.removeCommands(Player.COMMAND_SEEK_FORWARD)
    composeTestRule.waitForIdle()

    assertThat(state.isEnabled).isFalse()
  }

  @Test
  fun playerChangeSeekForwardIncrement_buttonStateGetsUpdatedValue() {
    val player = TestPlayer()

    lateinit var state: SeekForwardButtonState
    composeTestRule.setContent { state = rememberSeekForwardButtonState(player = player) }

    assertThat(state.seekForwardAmountMs).isEqualTo(C.DEFAULT_SEEK_FORWARD_INCREMENT_MS)

    player.setSeekForwardIncrementMs(12_300)
    composeTestRule.waitForIdle()

    assertThat(state.seekForwardAmountMs).isEqualTo(12_300)
  }

  @Test
  fun positionNonZero_buttonClicked_positionJumpsForwardBySpecifiedAmount() {
    val player = TestPlayer()
    player.playbackState = Player.STATE_READY
    player.playWhenReady = true
    player.setPosition(500)
    player.setSeekForwardIncrementMs(300)
    val state = SeekForwardButtonState(player)

    assertThat(player.currentPosition).isEqualTo(500)

    state.onClick()

    assertThat(player.currentPosition).isEqualTo(800)
  }

  @Test
  fun remainingDurationSmallerThanIncrement_buttonClicked_positionJumpsToTheEndOfCurrentMediaItem() {
    val player = TestPlayer()
    player.playbackState = Player.STATE_READY
    player.playWhenReady = true
    val state = SeekForwardButtonState(player)

    assertThat(player.currentPosition).isEqualTo(0)
    assertThat(player.duration - player.currentPosition).isLessThan(player.seekForwardIncrement)

    state.onClick()

    assertThat(player.currentPosition).isEqualTo(player.duration)
  }

  @Test
  fun positionAtTheEnd_buttonClicked_positionDoesNotMove() {
    val player = TestPlayer()
    player.playbackState = Player.STATE_READY
    player.playWhenReady = true
    player.setPosition(player.duration)
    val state = SeekForwardButtonState(player)

    assertThat(player.currentPosition).isEqualTo(player.duration)

    state.onClick()

    assertThat(player.currentPosition).isEqualTo(player.duration)
  }

  @Test
  fun playerChangesAvailableCommandsBeforeEventListenerRegisters_observeGetsTheLatestValues_uiIconInSync() {
    val player = TestPlayer()

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
