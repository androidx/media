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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.STATE_ENDED
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

/** Unit test for [NextButtonState]. */
@RunWith(AndroidJUnit4::class)
class NextButtonStateTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun addSeekNextCommandToPlayer_buttonStateTogglesFromDisabledToEnabled() {
    val player = createReadyPlayerWithTwoItems()
    player.removeCommands(Player.COMMAND_SEEK_TO_NEXT)

    lateinit var state: NextButtonState
    composeTestRule.setContent { state = rememberNextButtonState(player = player) }

    assertThat(state.isEnabled).isFalse()

    player.addCommands(Player.COMMAND_SEEK_TO_NEXT)
    composeTestRule.waitForIdle()

    assertThat(state.isEnabled).isTrue()
  }

  @Test
  fun removeSeekNextCommandToPlayer_buttonStateTogglesFromEnabledToDisabled() {
    val player = createReadyPlayerWithTwoItems()

    lateinit var state: NextButtonState
    composeTestRule.setContent { state = rememberNextButtonState(player = player) }

    assertThat(state.isEnabled).isTrue()

    player.removeCommands(Player.COMMAND_SEEK_TO_NEXT)
    composeTestRule.waitForIdle()

    assertThat(state.isEnabled).isFalse()
  }

  @Test
  fun stateIsDisabledStraightAway() {
    val player = TestSimpleBasePlayer()
    val state = NextButtonState(player)

    assertThat(state.isEnabled).isFalse()
  }

  @Test
  fun onClick_stateIsDisabled_throwsException() {
    val player = createReadyPlayerWithTwoItems()
    player.removeCommands(Player.COMMAND_SEEK_TO_NEXT)
    val state = NextButtonState(player)

    assertThat(state.isEnabled).isFalse()
    assertThrows(IllegalStateException::class.java) { state.onClick() }
  }

  @Test
  fun onClick_stateBecomesDisabledAfterFirstClick_throwsException() {
    val player = createReadyPlayerWithTwoItems()
    val state = NextButtonState(player)

    state.onClick()

    assertThrows(IllegalStateException::class.java) { state.onClick() }
  }

  @Test
  fun clickNextOnPenultimateMediaItem_buttonStateTogglesFromEnabledToDisabled() {
    val player = createReadyPlayerWithTwoItems()
    lateinit var state: NextButtonState
    composeTestRule.setContent { state = rememberNextButtonState(player = player) }

    assertThat(state.isEnabled).isTrue()

    player.seekToNext()
    composeTestRule.waitForIdle()

    assertThat(state.isEnabled).isFalse()
  }

  @Test
  fun playerInReadyState_buttonClicked_nextItemPlaying() {
    val player = createReadyPlayerWithTwoItems()
    val state = NextButtonState(player)

    assertThat(player.currentMediaItemIndex).isEqualTo(0)

    state.onClick()

    assertThat(player.currentMediaItemIndex).isEqualTo(1)
  }

  @Test
  fun playerInEndedState_singleDynamicLiveItem_onClickToDefaultPosition() {
    val player =
      TestSimpleBasePlayer(
        playbackState = STATE_ENDED,
        playWhenReady = true,
        playlist =
          listOf(
            MediaItemData.Builder("SingleItem")
              .setDurationUs(10_000)
              .setIsDynamic(true)
              .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(2000).build()
              )
              .build()
          ),
      )
    player.setPosition(10_000)
    val state = NextButtonState(player)
    assertThat(player.currentPosition).isEqualTo(10_000)
    assertThat(player.isPlaying).isFalse()

    state.onClick()

    // Position is masked immediately
    assertThat(player.currentPosition).isEqualTo(0)

    advance(player).untilState(Player.STATE_READY)
    // Player starts playing once the buffering from the seek is complete.
    assertThat(player.isPlaying).isTrue()
  }

  @Test
  fun playerReachesLastItemWithDisabledNextButtonBeforeEventListenerRegisters_observeGetsTheLatestValues_uiIconInSync() {
    val player = createReadyPlayerWithTwoItems()

    lateinit var state: NextButtonState
    composeTestRule.setContent {
      // Schedule LaunchedEffect to update player state before NextButtonState is created.
      // This update could end up being executed *before* NextButtonState schedules the start of
      // event listening and we don't want to lose it.
      LaunchedEffect(player) { player.seekToNext() }
      state = rememberNextButtonState(player = player)
    }

    // UI syncs up with the fact that we reached the last media item and NextButton is now disabled
    assertThat(state.isEnabled).isFalse()
  }
}
