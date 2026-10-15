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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.Tracks
import androidx.media3.test.utils.FakePlayer
import androidx.media3.ui.compose.testutils.createTestTracks
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.AdditionalAnswers.delegatesTo
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

/**
 * Unit tests for [MotionPhotoState], [MotionPhotoState.VisualState], and
 * [rememberMotionPhotoState].
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MotionPhotoStateTest {

  private val testMediaItem = MediaItem.fromUri("file:///motion_photo.jpg")

  @Test
  fun rememberMotionPhotoState_withMediaItemWithoutUri_throwsIllegalArgumentException() =
    runComposeUiTest {
      assertThrows(IllegalArgumentException::class.java) {
        setContent {
          val unused = rememberMotionPhotoState(player = null, mediaItem = MediaItem.EMPTY)
        }
      }
    }

  @Test
  fun rememberMotionPhotoState_withPlayer_replacesPlaylistAndPreparesAndPauses() =
    runComposeUiTest {
      // TODO(b/573079830): Update FakePlayer.handleSetMediaItems so it generates unique playlist
      // UIDs for items that share the default empty mediaId, then remove this explicit mediaId.
      val secondMediaItem =
        MediaItem.Builder().setMediaId("other_video").setUri("file:///other_video.mp4").build()
      val player = FakePlayer(bufferingDelayMs = 0, playWhenReady = true)
      player.setMediaItems(listOf(testMediaItem, secondMediaItem))

      setContent {
        val unused = rememberMotionPhotoState(player = player, mediaItem = testMediaItem)
      }
      waitForIdle()

      assertThat(player.mediaItemCount).isEqualTo(1)
      assertThat(player.currentMediaItem).isEqualTo(testMediaItem)
      assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun rememberMotionPhotoState_whenPlaylistAlreadyMatchesMediaItem_doesNotReplacePlaylist() =
    runComposeUiTest {
      val existingItem = testMediaItem.buildUpon().build()
      val player = FakePlayer(bufferingDelayMs = 0)
      player.setMediaItem(existingItem)
      player.prepare()

      setContent {
        val unused = rememberMotionPhotoState(player = player, mediaItem = testMediaItem)
      }
      waitForIdle()

      assertThat(player.mediaItemCount).isEqualTo(1)
      assertThat(player.currentMediaItem).isSameInstanceAs(existingItem)
    }

  @Test
  fun rememberMotionPhotoState_withoutSetMediaItemAndPlayPauseCommands_keepsPlaylistAndPlayState() =
    runComposeUiTest {
      val existingMediaItem = MediaItem.fromUri("file:///existing_video.mp4")
      val player = FakePlayer(bufferingDelayMs = 0, playWhenReady = false)
      player.setMediaItem(existingMediaItem)
      player.prepare()
      player.removeCommands(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_PLAY_PAUSE)
      // TODO(b/573079830): Add a strict command verification mode to FakePlayer/SimpleBasePlayer
      // that throws when unavailable commands are invoked, then remove this Mockito spy wrapper.
      val spyPlayer = mock(Player::class.java, delegatesTo<Player>(player))
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = spyPlayer,
            mediaItem = testMediaItem,
            initialShowPhoto = false,
          )
      }
      waitForIdle()

      assertThat(player.currentMediaItem).isSameInstanceAs(existingMediaItem)
      assertThat(player.playWhenReady).isFalse()

      state.showPhoto = true
      waitForIdle()
      assertThat(player.playWhenReady).isFalse()

      state.showPhoto = false
      waitForIdle()
      assertThat(player.playWhenReady).isFalse()

      verify(spyPlayer, never()).setMediaItem(any())
      verify(spyPlayer, never()).playWhenReady = anyBoolean()
      verify(spyPlayer, never()).play()
      verify(spyPlayer, never()).pause()
    }

  @Test
  fun rememberMotionPhotoState_whenDisposed_doesNotPauseOrStopPlayer() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    var inComposition by mutableStateOf(true)
    setContent {
      if (inComposition) {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowPhoto = false,
          )
      }
    }
    waitForIdle()
    assertThat(player.playWhenReady).isTrue()
    assertThat(player.playbackState).isEqualTo(Player.STATE_READY)

    inComposition = false
    waitForIdle()

    assertThat(player.playWhenReady).isTrue()
    assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
  }

  @Test
  fun rememberMotionPhotoState_whenMediaItemUpdated_resetsShowPhotoAndReplacesItemWithoutStopping() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      var currentItem by mutableStateOf(testMediaItem)
      val observedPlaybackStates = mutableListOf<@Player.State Int>()
      lateinit var state: MotionPhotoState
      setContent { state = rememberMotionPhotoState(player = player, mediaItem = currentItem) }
      waitForIdle()
      state.showPhoto = false
      waitForIdle()
      assertThat(state.showPhoto).isFalse()
      assertThat(player.playWhenReady).isTrue()
      player.addListener(
        object : Player.Listener {
          override fun onPlaybackStateChanged(playbackState: @Player.State Int) {
            observedPlaybackStates.add(playbackState)
          }
        }
      )

      val secondMediaItem = MediaItem.fromUri("file:///second_motion_photo.jpg")
      currentItem = secondMediaItem
      waitForIdle()

      assertThat(state.showPhoto).isTrue()
      assertThat(state.mediaItem).isEqualTo(secondMediaItem)
      assertThat(player.currentMediaItem).isEqualTo(secondMediaItem)
      assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
      assertThat(player.playWhenReady).isFalse()
      assertThat(observedPlaybackStates).doesNotContain(Player.STATE_IDLE)
    }

  @Test
  fun showPhoto_toggledOffAndOn_restartsFromDefaultPositionAndUpdatesPlayWhenReady() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      lateinit var state: MotionPhotoState
      setContent { state = rememberMotionPhotoState(player = player, mediaItem = testMediaItem) }
      waitForIdle()
      assertThat(state.showPhoto).isTrue()
      player.setPosition(400L)

      state.showPhoto = false
      assertThat(state.showPhoto).isFalse()
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.currentPosition).isEqualTo(0L)
      assertThat(player.playWhenReady).isTrue()

      player.setPosition(300L)
      state.showPhoto = true
      assertThat(state.showPhoto).isTrue()
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.currentPosition).isEqualTo(0L)
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun rememberMotionPhotoState_whenPlaybackFails_fallsBackToPhotoWithoutMutatingShowPhoto() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowPhoto = false,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      assertThat(state.showPhoto).isFalse()
      assertThat(state.visualState.isPhotoVisible).isFalse()

      player.setPlayerError(
        PlaybackException("Test error", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
      )
      waitForIdle()

      assertThat(state.showPhoto).isFalse()
      assertThat(state.visualState.isPhotoVisible).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_withPreloadedAudioOnlyTracks_keepsPhotoVisible() = runComposeUiTest {
    val player =
      FakePlayer(
        bufferingDelayMs = 0,
        playlist =
          listOf(
            MediaItemData.Builder("motion_photo")
              .setMediaItem(testMediaItem)
              .setTracks(createTestTracks(selectedVideoTrackIndex = null))
              .build()
          ),
      )
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowPhoto = false,
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isTrue()
  }

  @Test
  fun rememberMotionPhotoState_whenTracksChange_updatesPhotoVisibility() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowPhoto = false,
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()

    player.setTracks(createTestTracks(selectedVideoTrackIndex = null))
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isTrue()

    player.setTracks(createTestTracks(selectedVideoTrackIndex = 0))
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isFalse()

    // Clearing tracks to empty resets the first-frame gate until a new frame is rendered.
    player.setTracks(Tracks.EMPTY)
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isTrue()
  }

  @Test
  fun rememberMotionPhotoState_whenMediaItemUpdatedAfterPlayerError_clearsErrorAndPreparesNewItem() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      var currentItem by mutableStateOf(testMediaItem)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = currentItem,
            initialShowPhoto = false,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      assertThat(state.visualState.isPhotoVisible).isFalse()

      player.setPlayerError(
        PlaybackException("First item error", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
      )
      waitForIdle()
      assertThat(state.showPhoto).isFalse()
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playerError).isNotNull()

      val secondMediaItem = MediaItem.fromUri("file:///second_motion_photo.jpg")
      currentItem = secondMediaItem
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()

      assertThat(player.currentMediaItem).isEqualTo(secondMediaItem)
      assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
      assertThat(player.playerError).isNull()
      assertThat(state.visualState.isPhotoVisible).isFalse()
    }

  @Test
  fun rememberMotionPhotoState_whenPlayerReplaced_unregistersListenerFromPreviousPlayer() =
    runComposeUiTest {
      val player1 = FakePlayer(bufferingDelayMs = 0)
      val player2 = FakePlayer(bufferingDelayMs = 0)
      var currentPlayer by mutableStateOf<Player?>(player1)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = currentPlayer,
            mediaItem = testMediaItem,
            initialShowPhoto = false,
          )
      }
      waitForIdle()

      currentPlayer = player2
      waitForIdle()
      player2.renderFirstFrame(true)
      waitForIdle()

      // Error on detached player1 does not affect state.
      player1.setPlayerError(
        PlaybackException("Detached error", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
      )
      waitForIdle()

      assertThat(state.showPhoto).isFalse()
      assertThat(state.visualState.isPhotoVisible).isFalse()

      // Error on active player2 triggers fallback to still photo without mutating showPhoto.
      player2.setPlayerError(
        PlaybackException("Active error", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
      )
      waitForIdle()

      assertThat(state.showPhoto).isFalse()
      assertThat(state.visualState.isPhotoVisible).isTrue()
    }

  @Test
  fun showPhoto_afterPlayerError_doesNotPrepareOnTrueAndRetriesOnFalse() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowPhoto = false,
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()
    player.setPlayerError(
      PlaybackException("Test error", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
    )
    waitForIdle()

    // Returning target state to the still photo does not clear the error or re-prepare.
    state.showPhoto = true
    waitForIdle()

    assertThat(state.showPhoto).isTrue()
    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
    assertThat(player.playerError).isNotNull()

    // Requesting motion playback retries preparation and clears the error.
    state.showPhoto = false
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()

    assertThat(state.showPhoto).isFalse()
    assertThat(state.visualState.isPhotoVisible).isFalse()
    assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
    assertThat(player.playerError).isNull()
    assertThat(player.playWhenReady).isTrue()
  }

  @Test
  fun showPhoto_setFalseDirectlyAfterPlayerError_repreparesPlayerAndRecovers() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowPhoto = false,
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()
    player.setPlayerError(
      PlaybackException("Test error", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
    )
    waitForIdle()
    assertThat(state.showPhoto).isFalse()
    assertThat(state.visualState.isPhotoVisible).isTrue()

    state.showPhoto = false
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()

    assertThat(state.showPhoto).isFalse()
    assertThat(state.visualState.isPhotoVisible).isFalse()
    assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
    assertThat(player.playerError).isNull()
    assertThat(player.playWhenReady).isTrue()
  }

  @Test
  fun rememberMotionPhotoState_whenPlayerAlreadyHasErrorOnSameMediaItem_keepsErrorAndPhotoVisible() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      player.setMediaItem(testMediaItem)
      player.prepare()
      player.setPlayerError(
        PlaybackException("Preexisting error", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
      )
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowPhoto = false,
          )
      }
      waitForIdle()

      assertThat(player.playerError).isNotNull()
      assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
      assertThat(state.visualState.isPhotoVisible).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_whenAvailableCommandsChange_updatesPhotoVisibility() =
    runComposeUiTest {
      val player =
        FakePlayer(
          bufferingDelayMs = 0,
          playlist =
            listOf(
              MediaItemData.Builder("motion_photo")
                .setMediaItem(testMediaItem)
                .setTracks(createTestTracks(selectedVideoTrackIndex = null))
                .build()
            ),
        )
      player.removeCommands(Player.COMMAND_GET_TRACKS)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowPhoto = false,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      assertThat(state.visualState.isPhotoVisible).isFalse()

      player.addCommands(Player.COMMAND_GET_TRACKS)
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_whenMotionStarted_keepsPhotoVisibleUntilFirstFrameRendered() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowPhoto = false,
          )
      }
      waitForIdle()

      // Still photo overlay is kept visible until onRenderedFirstFrame (anti-flicker).
      assertThat(state.visualState.isPhotoVisible).isTrue()

      player.renderFirstFrame(true)
      waitForIdle()
      assertThat(state.visualState.isPhotoVisible).isFalse()

      // Switching back to still photo and replaying on the already-primed surface does not block on
      // a second EVENT_RENDERED_FIRST_FRAME.
      state.showPhoto = true
      waitForIdle()
      assertThat(state.visualState.isPhotoVisible).isTrue()

      state.showPhoto = false
      waitForIdle()
      assertThat(state.visualState.isPhotoVisible).isFalse()
    }
}
