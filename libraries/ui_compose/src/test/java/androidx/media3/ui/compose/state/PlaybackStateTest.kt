/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.ui.compose.state

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [PlaybackState]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackStateTest {

  @Test
  fun playbackState_whenStateIsBuffering_returnsBufferingState() {
    val player =
      FakePlayer(
        playbackState = Player.STATE_BUFFERING,
        playlist = listOf(MediaItemData.Builder("item").build()),
      )

    val state = PlaybackState(player)

    assertThat(state.playbackState).isEqualTo(Player.STATE_BUFFERING)
  }

  @Test
  fun playbackState_whenStateIsEnded_returnsEndedState() {
    val player =
      FakePlayer(
        playbackState = Player.STATE_ENDED,
        playlist = listOf(MediaItemData.Builder("item").build()),
      )

    val state = PlaybackState(player)

    assertThat(state.playbackState).isEqualTo(Player.STATE_ENDED)
  }

  @Test
  fun playerError_whenPlayerHasError_returnsError() {
    val player = FakePlayer(playlist = listOf(MediaItemData.Builder("item").build()))
    player.setPlayerError(PlaybackException(null, null, PlaybackException.ERROR_CODE_UNSPECIFIED))

    val state = PlaybackState(player)

    assertThat(state.playerError).isNotNull()
  }

  @Test
  fun isPlaying_whenPlayerIsPlaying_returnsTrue() {
    val player =
      FakePlayer(
        playbackState = Player.STATE_READY,
        playWhenReady = true,
        playlist = listOf(MediaItemData.Builder("item").build()),
      )

    val state = PlaybackState(player)

    assertThat(state.isPlaying).isTrue()
  }

  @Test
  fun playbackState_whenPlayerIsNull_returnsDefaultValues() {
    val state = PlaybackState(player = null)

    assertThat(state.playbackState).isEqualTo(Player.STATE_IDLE)
    assertThat(state.playWhenReady).isFalse()
    assertThat(state.isPlaying).isFalse()
    assertThat(state.playerError).isNull()
    assertThat(state.playbackSuppressionReason).isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
  }

  @Test
  fun playbackSuppressionReason_whenPlayerTransitionsSuppressionReason_updates() =
    runComposeUiTest {
      val player = FakePlayer(playlist = listOf(MediaItemData.Builder("item").build()))
      lateinit var state: PlaybackState
      setContent { state = rememberPlaybackState(player = player) }

      assertThat(state.playbackSuppressionReason).isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE)

      player.setPlaybackSuppressionReason(
        Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
      )
      waitForIdle()

      assertThat(state.playbackSuppressionReason)
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
    }

  @Test
  fun playerTransitionsToBuffering_playbackStateUpdates() = runComposeUiTest {
    val player = FakePlayer(playlist = listOf(MediaItemData.Builder("item").build()))
    lateinit var state: PlaybackState
    setContent { state = rememberPlaybackState(player = player) }

    assertThat(state.playbackState).isEqualTo(Player.STATE_IDLE)

    player.setPlaybackState(Player.STATE_BUFFERING)
    waitForIdle()

    assertThat(state.playbackState).isEqualTo(Player.STATE_BUFFERING)
  }

  @Test
  fun playerTransitionsToEnded_playbackStateUpdates() = runComposeUiTest {
    val player = FakePlayer(playlist = listOf(MediaItemData.Builder("item").build()))
    lateinit var state: PlaybackState
    setContent { state = rememberPlaybackState(player = player) }

    assertThat(state.playbackState).isEqualTo(Player.STATE_IDLE)

    player.setPlaybackState(Player.STATE_ENDED)
    waitForIdle()

    assertThat(state.playbackState).isEqualTo(Player.STATE_ENDED)
  }

  @Test
  fun playerTransitionsToError_playerErrorUpdates() = runComposeUiTest {
    val player = FakePlayer(playlist = listOf(MediaItemData.Builder("item").build()))
    lateinit var state: PlaybackState
    setContent { state = rememberPlaybackState(player = player) }

    assertThat(state.playerError).isNull()

    player.setPlayerError(PlaybackException(null, null, PlaybackException.ERROR_CODE_UNSPECIFIED))
    waitForIdle()

    assertThat(state.playerError).isNotNull()
  }

  @Test
  fun playerTransitionsToPlaying_isPlayingUpdates() = runComposeUiTest {
    val player = FakePlayer(playlist = listOf(MediaItemData.Builder("item").build()))
    lateinit var state: PlaybackState
    setContent { state = rememberPlaybackState(player = player) }

    assertThat(state.isPlaying).isFalse()

    player.setPlaybackState(Player.STATE_READY)
    player.play()
    waitForIdle()

    assertThat(state.isPlaying).isTrue()
  }
}
