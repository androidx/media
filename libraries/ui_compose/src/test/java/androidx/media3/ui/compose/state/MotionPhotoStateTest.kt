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

import android.annotation.SuppressLint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.Util.msToUs
import androidx.media3.test.utils.FakePlayer
import androidx.media3.ui.compose.testutils.createTestTracks
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unit tests for [MotionPhotoState], [MotionPhotoConfig], [MotionPhotoState.VisualState], and
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
      val secondMediaItem = MediaItem.fromUri("file:///other_video.mp4")
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
      val player =
        FakePlayer(bufferingDelayMs = 0, playWhenReady = false, throwWhenCommandUnavailable = true)
      player.setMediaItem(existingMediaItem)
      player.prepare()
      player.removeCommands(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_PLAY_PAUSE)
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

      assertThat(player.currentMediaItem).isSameInstanceAs(existingMediaItem)
      assertThat(player.playWhenReady).isFalse()

      state.showPhoto = true
      waitForIdle()
      assertThat(player.playWhenReady).isFalse()

      state.showPhoto = false
      waitForIdle()
      assertThat(player.playWhenReady).isFalse()
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
  fun rememberMotionPhotoState_withDefaultArguments_showsPhotoWithDefaultConfig() =
    runComposeUiTest {
      lateinit var state: MotionPhotoState

      setContent { state = rememberMotionPhotoState(player = null, mediaItem = testMediaItem) }
      waitForIdle()

      assertThat(state.showPhoto).isTrue()
      assertThat(state.config).isEqualTo(MotionPhotoConfig.DEFAULT)
      assertThat(state.visualState.isPhotoVisible).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_withPreconfiguredPlayer_resetsRepeatModeAndRewindsToDefaultPosition() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      player.setMediaItem(testMediaItem)
      player.prepare()
      player.repeatMode = Player.REPEAT_MODE_ALL
      player.setPosition(400L)

      setContent {
        val unused = rememberMotionPhotoState(player = player, mediaItem = testMediaItem)
      }
      waitForIdle()

      assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_OFF)
      assertThat(player.currentPosition).isEqualTo(0L)
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun showPhoto_toggledOffAndOn_updatesPlayWhenReadyAndReturnsToPhotoOnEnded() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    lateinit var state: MotionPhotoState
    setContent { state = rememberMotionPhotoState(player = player, mediaItem = testMediaItem) }
    waitForIdle()

    state.showPhoto = false
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isFalse()
    assertThat(player.playWhenReady).isTrue()

    state.showPhoto = true
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playWhenReady).isFalse()

    state.showPhoto = false
    waitForIdle()
    assertThat(state.visualState.isPhotoVisible).isFalse()
    assertThat(player.playWhenReady).isTrue()

    player.setPlaybackState(Player.STATE_ENDED)
    waitForIdle()

    assertThat(state.showPhoto).isTrue()
    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playWhenReady).isFalse()
  }

  @Test
  fun showPhoto_whenSetToTrueAfterRenderingFirstFrame_pausesImmediatelyAndRewindsAfterTransitionDuration() =
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
      mainClock.autoAdvance = false
      player.setPosition(300L)

      state.showPhoto = true
      mainClock.advanceTimeBy(100L)
      waitForIdle()

      // During the 200ms fade-in, the player is paused on its current frame without jumping to 0ms.
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()
      assertThat(player.currentPosition).isAtLeast(300L)

      mainClock.advanceTimeBy(150L)
      waitForIdle()

      // Once transitionDurationMs (200ms) elapses, the player rewinds underneath the opaque photo.
      assertThat(player.currentPosition).isEqualTo(0L)
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun showPhoto_whenRewoundAfterTransitionDuration_doesNotSeekAgainWhenResumingPlayback() =
    runComposeUiTest {
      val player =
        FakePlayer(
          bufferingDelayMs = 0,
          playlist =
            listOf(
              MediaItemData.Builder("motion_photo")
                .setMediaItem(testMediaItem)
                .setDefaultPositionUs(100_000L)
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
      player.setPosition(300L)
      var seekCount = 0
      player.addListener(
        object : Player.Listener {
          override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: @Player.DiscontinuityReason Int,
          ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
              seekCount++
            }
          }
        }
      )

      state.showPhoto = true
      mainClock.advanceTimeBy(200L)
      waitForIdle()
      assertThat(player.currentPosition).isEqualTo(100L)
      assertThat(seekCount).isEqualTo(1)

      state.showPhoto = false
      waitForIdle()

      assertThat(player.currentPosition).isAtLeast(100L)
      assertThat(seekCount).isEqualTo(1)
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

  @Test
  fun rememberMotionPhotoState_withLoopConfig_setsRepeatModeOne() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowPhoto = false,
          initialConfig = MotionPhotoConfig.LOOP,
        )
    }
    waitForIdle()

    assertThat(state.config).isEqualTo(MotionPhotoConfig.LOOP)
    assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE)
  }

  @Test
  fun rememberMotionPhotoState_withLoopConfigWithoutSetRepeatModeCommand_emulatesLoopOnEnded() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      player.removeCommands(Player.COMMAND_SET_REPEAT_MODE)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowPhoto = false,
            initialConfig = MotionPhotoConfig.LOOP,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      player.setPosition(300L)

      player.setPlaybackState(Player.STATE_ENDED)
      waitForIdle()

      assertThat(state.showPhoto).isFalse()
      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.currentPosition).isEqualTo(0L)
      assertThat(player.playWhenReady).isTrue()

      // When seeking to default position is also unavailable, falls back to showing the photo.
      player.removeCommands(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
      player.setPlaybackState(Player.STATE_ENDED)
      waitForIdle()

      assertThat(state.showPhoto).isTrue()
      assertThat(state.visualState.isPhotoVisible).isTrue()

      // When COMMAND_SET_REPEAT_MODE becomes available later, repeatMode is synced.
      player.addCommands(Player.COMMAND_SET_REPEAT_MODE)
      waitForIdle()

      assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE)
    }

  @Test
  fun rememberMotionPhotoState_whenPlayerAttachedFromNullWithLoopConfig_setsRepeatModeOne() =
    runComposeUiTest {
      var currentPlayer by mutableStateOf<Player?>(null)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = currentPlayer,
            mediaItem = testMediaItem,
            initialConfig = MotionPhotoConfig.LOOP,
          )
      }
      waitForIdle()
      assertThat(state.config).isEqualTo(MotionPhotoConfig.LOOP)

      val newPlayer = FakePlayer(bufferingDelayMs = 0)
      currentPlayer = newPlayer
      waitForIdle()

      assertThat(newPlayer.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE)
    }

  @Test
  fun config_toggledBetweenDefaultAndLoop_updatesConfigAndPlayerRepeatMode() = runComposeUiTest {
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
    assertThat(state.config).isEqualTo(MotionPhotoConfig.DEFAULT)
    assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_OFF)

    state.config = MotionPhotoConfig.LOOP
    waitForIdle()

    assertThat(state.config).isEqualTo(MotionPhotoConfig.LOOP)
    assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE)
    assertThat(state.showPhoto).isFalse()

    state.config = state.config.copy(isLooping = false)
    waitForIdle()

    assertThat(state.config).isEqualTo(MotionPhotoConfig.DEFAULT)
    assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_OFF)
    assertThat(state.showPhoto).isFalse()

    player.setPlaybackState(Player.STATE_ENDED)
    waitForIdle()

    assertThat(state.showPhoto).isTrue()
  }

  @Test
  fun showPhoto_whenSetToTrueWithZeroTransitionDuration_rewindsImmediately() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowPhoto = false,
          initialConfig = MotionPhotoConfig(transitionDurationMs = 0L),
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()
    mainClock.autoAdvance = false
    player.setPosition(300L)

    state.showPhoto = true

    assertThat(player.playWhenReady).isFalse()
    assertThat(player.currentPosition).isEqualTo(0L)
  }

  @Test
  fun showPhoto_whenReToggledToFalseBeforeTransitionElapses_rewindsAndCancelsDeferredRewind() =
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
      mainClock.autoAdvance = false
      player.setPosition(300L)
      state.showPhoto = true
      mainClock.advanceTimeBy(50L)
      waitForIdle()
      assertThat(player.currentPosition).isAtLeast(300L)

      // Re-triggering motion before the 200ms fade-in completes rewinds immediately and cancels the
      // pending deferred rewind.
      state.showPhoto = false
      waitForIdle()

      assertThat(player.currentPosition).isEqualTo(0L)
      assertThat(player.playWhenReady).isTrue()
      assertThat(state.visualState.isPhotoVisible).isFalse()

      // Advancing past the remaining transition duration verifies the cancelled deferred rewind
      // does not fire and rewind the newly started playback back to 0ms.
      player.setPosition(120L)
      mainClock.advanceTimeBy(200L)
      waitForIdle()
      assertThat(player.currentPosition).isAtLeast(120L)
    }

  @Test
  fun config_whenUnrelatedPropertyChangesDuringDeferredRewind_doesNotRestartDelay() =
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
      mainClock.autoAdvance = false
      player.setPosition(300L)
      state.showPhoto = true
      mainClock.advanceTimeBy(100L)
      waitForIdle()

      state.config = state.config.copy(isLooping = true)
      mainClock.advanceTimeBy(50L)
      waitForIdle()

      assertThat(player.currentPosition).isAtLeast(300L)

      // The original 200ms delay completes after the remaining 50ms instead of restarting.
      mainClock.advanceTimeBy(50L)
      waitForIdle()

      assertThat(player.currentPosition).isEqualTo(0L)
    }

  @Test
  fun config_whenTransitionDurationSetToZeroDuringDeferredRewind_completesOriginalDelayBeforeRewinding() =
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
      mainClock.autoAdvance = false
      player.setPosition(300L)
      state.showPhoto = true
      mainClock.advanceTimeBy(50L)
      waitForIdle()

      state.config = state.config.copy(transitionDurationMs = 0L)
      mainClock.advanceTimeBy(100L)
      waitForIdle()

      // The crossfade that is already running keeps its original 200ms, so the rewind is neither
      // pulled forward (where it would show through the half-faded photo) nor lost.
      assertThat(player.currentPosition).isAtLeast(300L)

      mainClock.advanceTimeBy(50L)
      waitForIdle()

      assertThat(player.currentPosition).isEqualTo(0L)
    }

  @Test
  fun config_whenResumeVideoFromPreviousPositionEnabledDuringDeferredRewind_cancelsRewind() =
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
      mainClock.autoAdvance = false
      player.setPosition(300L)
      state.showPhoto = true
      mainClock.advanceTimeBy(50L)
      waitForIdle()

      state.config = state.config.copy(resumeVideoFromPreviousPosition = true)
      mainClock.advanceTimeBy(200L)
      waitForIdle()

      assertThat(player.currentPosition).isAtLeast(300L)

      state.showPhoto = false
      waitForIdle()

      assertThat(player.currentPosition).isAtLeast(300L)
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_whenPlayerReplacedDuringDeferredRewind_doesNotRewindPreviousPlayer() =
    runComposeUiTest {
      val firstPlayer = FakePlayer(bufferingDelayMs = 0)
      val secondPlayer = FakePlayer(bufferingDelayMs = 0)
      var currentPlayer by mutableStateOf<Player>(firstPlayer)
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
      firstPlayer.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      firstPlayer.setPosition(300L)
      state.showPhoto = true
      mainClock.advanceTimeBy(50L)
      waitForIdle()

      currentPlayer = secondPlayer
      waitForIdle()
      mainClock.advanceTimeUntil { state.player === secondPlayer }
      mainClock.advanceTimeBy(200L)
      waitForIdle()

      assertThat(firstPlayer.currentPosition).isAtLeast(300L)
    }

  @Test
  fun rememberMotionPhotoState_whenMediaItemChangesDuringDeferredRewind_doesNotRewindNewMediaItem() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      val secondMediaItem = MediaItem.fromUri("file:///second_motion_photo.jpg")
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
      mainClock.autoAdvance = false
      player.setPosition(300L)
      state.showPhoto = true
      mainClock.advanceTimeBy(50L)
      waitForIdle()

      // A new state binds the same player and starts playing the new item mid-delay.
      currentItem = secondMediaItem
      waitForIdle()
      mainClock.advanceTimeUntil { player.currentMediaItem == secondMediaItem }
      assertThat(player.playWhenReady).isTrue()
      player.setPosition(120L)
      mainClock.advanceTimeBy(200L)
      waitForIdle()

      // The previous state's pending rewind must not seek the new item.
      assertThat(player.currentPosition).isAtLeast(120L)
    }

  @Test
  fun showPhoto_withResumeVideoFromPreviousPosition_pausesAndResumesWithoutRewindingAndRestartsAfterEnded() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      player.setMediaItem(testMediaItem)
      player.prepare()
      player.setPosition(250L)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialConfig = MotionPhotoConfig(resumeVideoFromPreviousPosition = true),
          )
      }
      waitForIdle()
      // Initial bind with resumeVideoFromPreviousPosition = true preserves existing position.
      assertThat(player.currentPosition).isEqualTo(250L)

      state.showPhoto = false
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      assertThat(player.currentPosition).isAtLeast(250L)
      assertThat(player.playWhenReady).isTrue()

      player.setPosition(400L)
      state.showPhoto = true
      waitForIdle()

      assertThat(player.playWhenReady).isFalse()
      assertThat(player.currentPosition).isAtLeast(400L)

      state.showPhoto = false
      waitForIdle()

      assertThat(player.playWhenReady).isTrue()
      assertThat(player.currentPosition).isAtLeast(400L)

      // Reaching STATE_ENDED returns to photo; subsequent play trigger rewinds to 0L to restart.
      player.setPlaybackState(Player.STATE_ENDED)
      waitForIdle()
      assertThat(state.showPhoto).isTrue()

      state.showPhoto = false
      waitForIdle()

      assertThat(player.currentPosition).isEqualTo(0L)
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun config_whenResumeVideoFromPreviousPositionDisabledWhileShowingPhoto_rewindsToDefaultPosition() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowPhoto = false,
            initialConfig = MotionPhotoConfig(resumeVideoFromPreviousPosition = true),
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()

      player.setPosition(350L)
      state.showPhoto = true
      waitForIdle()
      assertThat(player.currentPosition).isAtLeast(350L)

      // Disabling resumeVideoFromPreviousPosition while showing the photo rewinds the player.
      state.config = state.config.copy(resumeVideoFromPreviousPosition = false)
      waitForIdle()

      assertThat(player.currentPosition).isEqualTo(0L)
    }

  @Test
  fun config_whenTransitionDurationUpdated_updatesVisualStateTransitionDuration() =
    runComposeUiTest {
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = null,
            mediaItem = testMediaItem,
            initialConfig = MotionPhotoConfig(transitionDurationMs = 450L),
          )
      }
      waitForIdle()
      assertThat(state.visualState.transitionDurationMs).isEqualTo(450L)

      state.config = state.config.copy(transitionDurationMs = 100L)
      waitForIdle()

      assertThat(state.visualState.transitionDurationMs).isEqualTo(100L)
    }

  @Test
  fun config_whenResumeVideoFromPreviousPositionDisabledWhilePlayingMotion_doesNotRewindOrInterruptPlayback() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowPhoto = false,
            initialConfig = MotionPhotoConfig(resumeVideoFromPreviousPosition = true),
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      player.setPosition(350L)

      state.config = state.config.copy(resumeVideoFromPreviousPosition = false)
      waitForIdle()

      assertThat(state.showPhoto).isFalse()
      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isTrue()
      assertThat(player.currentPosition).isAtLeast(350L)
    }

  @Test
  fun rememberMotionPhotoState_withStartBoundaryStartOfVideoAndOffset_setsClippingStartPosition() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)

      setContent {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialConfig =
              MotionPhotoConfig(
                startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                startPositionOffsetMs = 200L,
              ),
          )
      }
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(200L)
      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionUs)
        .isEqualTo(200_000L)
    }

  @Test
  fun rememberMotionPhotoState_withStartBoundaryEndOfVideoAndNegativeOffset_setsClippingRelativeToDuration() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowPhoto = false,
            initialConfig =
              MotionPhotoConfig(
                startBoundary = MotionPhotoConfig.BOUNDARY_END_OF_VIDEO,
                startPositionOffsetMs = -300L,
              ),
          )
      }
      waitForIdle()

      // Before duration is known, the unclipped item is prepared for metadata resolution while
      // keeping the photo visible and playback paused.
      assertThat(player.currentMediaItem).isEqualTo(testMediaItem)
      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(0L)
      assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
      assertThat(player.playWhenReady).isFalse()
      assertThat(state.visualState.isPhotoVisible).isTrue()

      player.setDuration(player.currentTimeline.getWindow(0, Timeline.Window()).uid, 1_000L)
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(700L)
      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isTrue()

      // An offset that reaches before the start of the video is clamped to it.
      state.config = state.config.copy(startPositionOffsetMs = -1_500L)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(0L)
      assertThat(state.showPhoto).isFalse()
      assertThat(player.playWhenReady).isTrue()

      // A start at the end of the video leaves nothing to play: playback pauses and the photo is
      // shown without mutating showPhoto or the clipped item on the player.
      state.config = state.config.copy(startPositionOffsetMs = 0L)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(0L)
      assertThat(state.showPhoto).isFalse()
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      // Restoring a valid offset resumes playback.
      state.config = state.config.copy(startPositionOffsetMs = -300L)
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(700L)
      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun config_whenStartBoundaryUpdated_setsClippedMediaItemAndRestartsFromStartPosition() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowPhoto = false,
            initialConfig =
              MotionPhotoConfig(
                startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                startPositionOffsetMs = 200L,
              ),
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(200L)
      assertThat(state.visualState.isPhotoVisible).isFalse()
      player.setPosition(300L)

      state.config =
        state.config.copy(
          startBoundary = MotionPhotoConfig.BOUNDARY_END_OF_VIDEO,
          startPositionOffsetMs = -350L,
        )
      waitForIdle()

      // The clipped item is set again, so playback restarts from the new start position and the
      // photo covers the video until the first frame of the new range has rendered.
      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(650L)
      assertThat(player.currentPosition).isEqualTo(0L)
      assertThat(player.playWhenReady).isTrue()
      assertThat(state.visualState.isPhotoVisible).isTrue()
      player.renderFirstFrame(true)
      waitForIdle()
      assertThat(state.visualState.isPhotoVisible).isFalse()

      // Without COMMAND_SET_MEDIA_ITEM, clipping updates are skipped.
      player.removeCommands(Player.COMMAND_SET_MEDIA_ITEM)
      state.config = state.config.copy(startPositionOffsetMs = -200L)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(650L)

      // An offset equal to the video duration leaves an empty playback range.
      player.addCommands(Player.COMMAND_SET_MEDIA_ITEM)
      state.config =
        state.config.copy(
          startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
          startPositionOffsetMs = 1_000L,
        )
      waitForIdle()

      assertThat(state.showPhoto).isFalse()
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun config_withClippedMediaItem_ignoresClippedDurationAndUsesOriginalDuration() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowPhoto = false,
            initialConfig =
              MotionPhotoConfig(
                startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                startPositionOffsetMs = 600L,
              ),
          )
      }
      waitForIdle()
      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(600L)

      // A prepared clipped item reports the clipped window length (400ms) as its duration and the
      // duration of the whole video as the original duration of its period.
      val uid = player.currentTimeline.getWindow(0, Timeline.Window()).uid
      player.setDuration(uid, 400L)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(600L)
      assertThat(player.playWhenReady).isTrue()

      player.setOriginalDuration(uid, 1_200L)
      waitForIdle()
      state.config =
        state.config.copy(
          startBoundary = MotionPhotoConfig.BOUNDARY_END_OF_VIDEO,
          startPositionOffsetMs = -300L,
        )
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(900L)
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_whenPlayerAlreadyHoldsClippedMediaItem_adoptsClippingWithoutSettingItemAgain() =
    runComposeUiTest {
      // A player handed over across a configuration change already plays the clipped item.
      val player =
        createSeekableFakePlayer(
          durationMs = 800L,
          mediaItem =
            testMediaItem
              .buildUpon()
              .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(200L).build()
              )
              .build(),
        )
      player.setOriginalDuration("motion_photo", 1_000L)
      player.pause()
      player.setPosition(300L)
      val clippedItemUid = player.currentTimeline.getWindow(0, Timeline.Window()).uid

      setContent {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowPhoto = false,
            initialConfig =
              MotionPhotoConfig(
                resumeVideoFromPreviousPosition = true,
                startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                startPositionOffsetMs = 200L,
              ),
          )
      }
      waitForIdle()

      assertThat(player.currentTimeline.getWindow(0, Timeline.Window()).uid)
        .isEqualTo(clippedItemUid)
      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(200L)
      assertThat(player.currentPosition).isAtLeast(300L)
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_whenClippedMediaItemDoesNotReportOriginalDuration_preparesUnclippedItemToLearnDuration() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = C.TIME_UNSET,
          mediaItem =
            testMediaItem
              .buildUpon()
              .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(200L).build()
              )
              .build(),
        )
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowPhoto = false,
            initialConfig =
              MotionPhotoConfig(
                startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                startPositionOffsetMs = 200L,
              ),
          )
      }
      waitForIdle()

      // The unclipped item is prepared to learn the duration while the photo stays visible.
      assertThat(player.currentMediaItem).isEqualTo(testMediaItem)
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      player.setDuration(player.currentTimeline.getWindow(0, Timeline.Window()).uid, 1_000L)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(200L)
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_withEndBoundaryStartOfVideoAndOffset_setsClippingEndPosition() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      setContent {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialConfig =
              MotionPhotoConfig(
                endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                endPositionOffsetMs = 400L,
              ),
          )
      }
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(0L)
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(400L)
    }

  @Test
  fun rememberMotionPhotoState_withEndBoundaryEndOfVideoAndNegativeOffset_setsClippingRelativeToDuration() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowPhoto = false,
            initialConfig =
              MotionPhotoConfig(
                endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                endPositionOffsetMs = 400L,
              ),
          )
      }
      waitForIdle()
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(400L)

      // Switching to BOUNDARY_END_OF_VIDEO while duration is still unknown prepares the unclipped
      // item to learn the duration while keeping the photo visible and playback paused.
      state.config =
        MotionPhotoConfig(
          endBoundary = MotionPhotoConfig.BOUNDARY_END_OF_VIDEO,
          endPositionOffsetMs = -300L,
        )
      waitForIdle()

      assertThat(player.currentMediaItem).isEqualTo(testMediaItem)
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs)
        .isEqualTo(C.TIME_END_OF_SOURCE)
      assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
      assertThat(player.playWhenReady).isFalse()
      assertThat(state.visualState.isPhotoVisible).isTrue()

      player.setDuration(player.currentTimeline.getWindow(0, Timeline.Window()).uid, 1_000L)
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(0L)
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(700L)
      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun config_whenEndBoundaryUpdated_setsClippedMediaItemAndRestartsFromStartPosition() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowPhoto = false,
            initialConfig =
              MotionPhotoConfig(
                startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                startPositionOffsetMs = 100L,
              ),
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      player.setPosition(250L)
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs)
        .isEqualTo(C.TIME_END_OF_SOURCE)
      assertThat(state.visualState.isPhotoVisible).isFalse()

      state.config =
        state.config.copy(
          endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
          endPositionOffsetMs = 600L,
        )
      waitForIdle()

      // The clipped item is set again, so playback restarts from the start position and the photo
      // covers the video until the first frame of the new range has rendered.
      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(100L)
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
      assertThat(player.currentPosition).isEqualTo(0L)
      assertThat(player.playWhenReady).isTrue()
      assertThat(state.visualState.isPhotoVisible).isTrue()
      player.renderFirstFrame(true)
      waitForIdle()
      assertThat(state.visualState.isPhotoVisible).isFalse()

      // An end boundary beyond the video duration is passed through and clamped by the player.
      state.config = state.config.copy(endPositionOffsetMs = 1_500L)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(1_500L)
      assertThat(player.playWhenReady).isTrue()
      assertThat(state.showPhoto).isFalse()
    }

  @Test
  fun config_whenEndBoundaryUpdatedWhileShowingPhoto_keepsPlayWhenReadyFalse() = runComposeUiTest {
    val player = createSeekableFakePlayer(durationMs = 1_000L)
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowPhoto = true,
        )
    }
    waitForIdle()
    assertThat(player.playWhenReady).isFalse()

    state.config =
      state.config.copy(
        endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
        endPositionOffsetMs = 600L,
      )
    waitForIdle()

    assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
    assertThat(player.playWhenReady).isFalse()
    assertThat(state.showPhoto).isTrue()
    assertThat(state.visualState.isPhotoVisible).isTrue()
  }

  @Test
  fun rememberMotionPhotoState_withEndBoundaryBeyondUnknownDuration_setsClippedMediaItemOnce() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = C.TIME_UNSET)
      var playlistChangeCount = 0
      player.addListener(
        object : Player.Listener {
          override fun onMediaItemTransition(
            mediaItem: MediaItem?,
            reason: @Player.MediaItemTransitionReason Int,
          ) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
              playlistChangeCount++
            }
          }
        }
      )
      setContent {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowPhoto = false,
            initialConfig =
              MotionPhotoConfig(
                endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                endPositionOffsetMs = 1_500L,
              ),
          )
      }
      waitForIdle()
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(1_500L)
      assertThat(playlistChangeCount).isEqualTo(1)

      // Learning the duration does not set the clipped item again.
      player.setOriginalDuration(player.currentTimeline.getWindow(0, Timeline.Window()).uid, 1_000L)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(1_500L)
      assertThat(playlistChangeCount).isEqualTo(1)
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_whenClippedPlaybackEnds_showsPhoto() = runComposeUiTest {
    val player = createSeekableFakePlayer(durationMs = 1_000L)
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowPhoto = false,
          initialConfig =
            MotionPhotoConfig(
              startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
              startPositionOffsetMs = 100L,
              endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
              endPositionOffsetMs = 600L,
            ),
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()
    assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(100L)
    assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
    assertThat(state.visualState.isPhotoVisible).isFalse()

    player.setPlaybackState(Player.STATE_ENDED)
    waitForIdle()

    assertThat(state.showPhoto).isTrue()
    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playWhenReady).isFalse()
  }

  @Test
  fun rememberMotionPhotoState_withEndBoundaryLessThanOrEqualToStartBoundary_keepsPhotoVisibleAndPauses() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowPhoto = false,
            initialConfig =
              MotionPhotoConfig(
                startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                startPositionOffsetMs = 500L,
                endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                endPositionOffsetMs = 500L,
              ),
          )
      }
      waitForIdle()

      assertThat(state.showPhoto).isFalse()
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      // An end before the start leaves nothing to play either.
      state.config = state.config.copy(startPositionOffsetMs = 600L)
      waitForIdle()

      assertThat(state.showPhoto).isFalse()
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      // Changing only endBoundary (with offset 0) from BOUNDARY_START_OF_VIDEO to
      // BOUNDARY_END_OF_VIDEO makes the range valid and resumes playback.
      state.config = MotionPhotoConfig(endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO)
      waitForIdle()
      assertThat(player.playWhenReady).isFalse()

      state.config = state.config.copy(endBoundary = MotionPhotoConfig.BOUNDARY_END_OF_VIDEO)
      waitForIdle()

      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun motionPhotoConfig_withInvalidStartBoundaryOrOffset_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException::class.java) { MotionPhotoConfig(startBoundary = 0) }
    assertThrows(IllegalArgumentException::class.java) {
      MotionPhotoConfig(
        startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
        startPositionOffsetMs = -1L,
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      MotionPhotoConfig(
        startBoundary = MotionPhotoConfig.BOUNDARY_END_OF_VIDEO,
        startPositionOffsetMs = 1L,
      )
    }
  }

  @Test
  fun motionPhotoConfig_withInvalidEndBoundaryOrOffset_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException::class.java) { MotionPhotoConfig(endBoundary = 0) }
    assertThrows(IllegalArgumentException::class.java) {
      MotionPhotoConfig(
        endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
        endPositionOffsetMs = -1L,
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      MotionPhotoConfig(
        endBoundary = MotionPhotoConfig.BOUNDARY_END_OF_VIDEO,
        endPositionOffsetMs = 1L,
      )
    }
  }

  @Test
  @SuppressLint("Range")
  fun motionPhotoConfig_withNegativeTransitionDuration_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException::class.java) {
      MotionPhotoConfig(transitionDurationMs = -1L)
    }
  }

  @Test
  fun motionPhotoConfig_copyAndPresets_hasExpectedPropertiesAndEquality() {
    val defaultConfig = MotionPhotoConfig.DEFAULT
    assertThat(defaultConfig.isLooping).isFalse()
    assertThat(defaultConfig.transitionDurationMs).isEqualTo(200L)
    assertThat(defaultConfig.resumeVideoFromPreviousPosition).isFalse()
    assertThat(defaultConfig.startBoundary).isEqualTo(MotionPhotoConfig.BOUNDARY_START_OF_VIDEO)
    assertThat(defaultConfig.startPositionOffsetMs).isEqualTo(0L)
    assertThat(defaultConfig.endBoundary).isEqualTo(MotionPhotoConfig.BOUNDARY_END_OF_VIDEO)
    assertThat(defaultConfig.endPositionOffsetMs).isEqualTo(0L)
    assertThat(defaultConfig).isEqualTo(MotionPhotoConfig())
    assertThat(defaultConfig.hashCode()).isEqualTo(MotionPhotoConfig().hashCode())

    val loopConfig = MotionPhotoConfig.LOOP
    assertThat(loopConfig.isLooping).isTrue()
    assertThat(loopConfig.transitionDurationMs).isEqualTo(200L)
    assertThat(loopConfig.resumeVideoFromPreviousPosition).isFalse()
    assertThat(loopConfig.startBoundary).isEqualTo(MotionPhotoConfig.BOUNDARY_START_OF_VIDEO)
    assertThat(loopConfig.startPositionOffsetMs).isEqualTo(0L)
    assertThat(loopConfig.endBoundary).isEqualTo(MotionPhotoConfig.BOUNDARY_END_OF_VIDEO)
    assertThat(loopConfig.endPositionOffsetMs).isEqualTo(0L)
    assertThat(loopConfig).isEqualTo(defaultConfig.copy(isLooping = true))
    assertThat(loopConfig).isNotEqualTo(defaultConfig)
    assertThat(loopConfig.copy(isLooping = false)).isEqualTo(defaultConfig)

    val customConfig =
      defaultConfig.copy(
        transitionDurationMs = 350L,
        resumeVideoFromPreviousPosition = true,
        startBoundary = MotionPhotoConfig.BOUNDARY_END_OF_VIDEO,
        startPositionOffsetMs = -250L,
        endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
        endPositionOffsetMs = 600L,
      )
    assertThat(customConfig.isLooping).isFalse()
    assertThat(customConfig.transitionDurationMs).isEqualTo(350L)
    assertThat(customConfig.resumeVideoFromPreviousPosition).isTrue()
    assertThat(customConfig.startBoundary).isEqualTo(MotionPhotoConfig.BOUNDARY_END_OF_VIDEO)
    assertThat(customConfig.startPositionOffsetMs).isEqualTo(-250L)
    assertThat(customConfig.endBoundary).isEqualTo(MotionPhotoConfig.BOUNDARY_START_OF_VIDEO)
    assertThat(customConfig.endPositionOffsetMs).isEqualTo(600L)
    assertThat(customConfig).isNotEqualTo(defaultConfig)
    assertThat(customConfig).isNotEqualTo(customConfig.copy(startPositionOffsetMs = -100L))
    assertThat(customConfig.hashCode())
      .isNotEqualTo(customConfig.copy(startPositionOffsetMs = -100L).hashCode())
    assertThat(customConfig).isNotEqualTo(customConfig.copy(endPositionOffsetMs = 500L))
    assertThat(customConfig.hashCode())
      .isNotEqualTo(customConfig.copy(endPositionOffsetMs = 500L).hashCode())
    assertThat(
        customConfig.copy(
          endBoundary = MotionPhotoConfig.BOUNDARY_END_OF_VIDEO,
          endPositionOffsetMs = 0L,
        )
      )
      .isNotEqualTo(customConfig.copy(endPositionOffsetMs = 0L))
    assertThat(
        customConfig
          .copy(endBoundary = MotionPhotoConfig.BOUNDARY_END_OF_VIDEO, endPositionOffsetMs = 0L)
          .hashCode()
      )
      .isNotEqualTo(customConfig.copy(endPositionOffsetMs = 0L).hashCode())
    assertThat(
        customConfig.copy(
          startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
          startPositionOffsetMs = 0L,
        )
      )
      .isNotEqualTo(customConfig.copy(startPositionOffsetMs = 0L))
    assertThat(
        customConfig
          .copy(
            startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
            startPositionOffsetMs = 0L,
          )
          .hashCode()
      )
      .isNotEqualTo(customConfig.copy(startPositionOffsetMs = 0L).hashCode())
    assertThat(customConfig.hashCode())
      .isEqualTo(
        MotionPhotoConfig(
            transitionDurationMs = 350L,
            resumeVideoFromPreviousPosition = true,
            startBoundary = MotionPhotoConfig.BOUNDARY_END_OF_VIDEO,
            startPositionOffsetMs = -250L,
            endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
            endPositionOffsetMs = 600L,
          )
          .hashCode()
      )
  }

  private fun createSeekableFakePlayer(
    durationMs: Long = 1_000L,
    mediaItem: MediaItem = testMediaItem,
  ): FakePlayer =
    FakePlayer(
      bufferingDelayMs = 0,
      playlist =
        listOf(
          MediaItemData.Builder("motion_photo")
            .setMediaItem(mediaItem)
            .setDurationUs(msToUs(durationMs))
            .build()
        ),
    )
}
