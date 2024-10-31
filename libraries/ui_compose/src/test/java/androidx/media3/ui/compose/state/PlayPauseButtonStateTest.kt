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

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [PlayPauseButtonState]. */
@RunWith(AndroidJUnit4::class)
class PlayPauseButtonStateTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun playerIsBuffering_pausePlayer_playIconShowing() {
    val player = TestPlayer()
    player.playbackState = Player.STATE_BUFFERING
    player.play()

    lateinit var state: PlayPauseButtonState
    composeTestRule.setContent { state = rememberPlayPauseButtonState(player = player) }

    assertThat(state.showPlay).isFalse()

    player.pause()
    composeTestRule.waitForIdle()

    assertThat(state.showPlay).isTrue()
  }

  @Test
  fun playerIsIdling_preparePlayer_pauseIconShowing() {
    val player = TestPlayer()
    player.playbackState = Player.STATE_IDLE
    player.play()

    lateinit var state: PlayPauseButtonState
    composeTestRule.setContent { state = rememberPlayPauseButtonState(player = player) }

    assertThat(state.showPlay).isTrue()

    player.prepare()
    composeTestRule.waitForIdle()

    assertThat(state.showPlay).isFalse()
  }

  @Test
  fun addPlayPauseCommandToPlayer_buttonStateTogglesFromDisabledToEnabled() {
    val player = TestPlayer()
    player.playbackState = Player.STATE_READY
    player.play()
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
    val player = TestPlayer()
    player.playbackState = Player.STATE_READY
    player.play()

    val state = PlayPauseButtonState(player)

    assertThat(state.showPlay).isFalse()
    assertThat(player.playWhenReady).isTrue()

    state.onClick() // Player pauses

    assertThat(player.playWhenReady).isFalse()
  }

  @Test
  fun playerInEndedState_buttonClicked_playerBuffersAndPlays() {
    val player = TestPlayer()
    player.playbackState = Player.STATE_ENDED
    player.setPosition(456)
    val state = PlayPauseButtonState(player)

    assertThat(state.showPlay).isTrue()

    state.onClick() // Player seeks to default position and plays

    assertThat(player.contentPosition).isEqualTo(0)
    assertThat(player.playWhenReady).isTrue()
    assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
  }

  @Test
  fun playerInIdleState_buttonClicked_playerBuffersAndPlays() {
    val player = TestPlayer()
    player.playbackState = Player.STATE_IDLE
    val state = PlayPauseButtonState(player)

    assertThat(state.showPlay).isTrue() // Player not prepared, Play icon

    state.onClick() // Player prepares and goes into buffering

    assertThat(player.playWhenReady).isTrue()
    assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
  }
}

private class TestPlayer : SimpleBasePlayer(Looper.myLooper()!!) {
  private var state =
    State.Builder()
      .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
      .setPlaylist(ImmutableList.of(MediaItemData.Builder(/* uid= */ Any()).build()))
      .build()

  override fun getState(): State {
    return state
  }

  override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
    state =
      state
        .buildUpon()
        .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .build()
    return Futures.immediateVoidFuture()
  }

  override fun handlePrepare(): ListenableFuture<*> {
    state =
      state
        .buildUpon()
        .setPlayerError(null)
        .setPlaybackState(if (state.timeline.isEmpty) STATE_ENDED else STATE_BUFFERING)
        .build()
    return Futures.immediateVoidFuture()
  }

  override fun handleSeek(
    mediaItemIndex: Int,
    positionMs: Long,
    seekCommand: @Player.Command Int,
  ): ListenableFuture<*> {
    state =
      state.buildUpon().setPlaybackState(STATE_BUFFERING).setContentPositionMs(positionMs).build()
    return Futures.immediateVoidFuture()
  }

  fun setPlaybackState(playbackState: @Player.State Int) {
    state = state.buildUpon().setPlaybackState(playbackState).build()
    invalidateState()
  }

  fun setPosition(positionMs: Long) {
    state = state.buildUpon().setContentPositionMs(positionMs).build()
    invalidateState()
  }

  fun removeCommands(vararg commands: @Player.Command Int) {
    state =
      state
        .buildUpon()
        .setAvailableCommands(
          Player.Commands.Builder().addAllCommands().removeAll(*commands).build()
        )
        .build()
    invalidateState()
  }

  fun addCommands(vararg commands: @Player.Command Int) {
    state =
      state
        .buildUpon()
        .setAvailableCommands(Player.Commands.Builder().addAll(*commands).build())
        .build()
    invalidateState()
  }
}
