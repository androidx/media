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

package androidx.media3.ui.compose.motionphoto

import android.annotation.SuppressLint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.Util.msToUs
import androidx.media3.extractor.metadata.MotionPhotoMetadata
import androidx.media3.test.utils.FakePlayer
import androidx.media3.test.utils.createTestTracks
import androidx.media3.test.utils.createVideoFormat
import androidx.media3.ui.compose.motionphoto.MotionPhotoConfig.Boundary
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.shadows.ShadowLooper

/**
 * Unit tests for [MotionPhotoState], [MotionPhotoConfig], [MotionPhotoState.VisualState], and
 * [rememberMotionPhotoState].
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MotionPhotoStateTest {

  private val testMediaItem = MediaItem.fromUri("file:///motion_photo.jpg")
  private val testError = playbackError("Test error")

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
      val state = setContentWithMotionPhotoState(player, initialShowMotion = true)

      assertThat(player.currentMediaItem).isSameInstanceAs(existingMediaItem)
      assertThat(player.playWhenReady).isFalse()

      state.showStillPhoto()
      assertThat(player.playWhenReady).isFalse()

      state.playMotion()
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
            initialShowMotion = true,
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
  fun rememberMotionPhotoState_whenMediaItemUpdated_resetsMotionAndReplacesItemWithoutStopping() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      var currentItem by mutableStateOf(testMediaItem)
      val observedPlaybackStates = mutableListOf<@Player.State Int>()
      lateinit var state: MotionPhotoState
      setContent { state = rememberMotionPhotoState(player = player, mediaItem = currentItem) }
      waitForIdle()
      state.playMotion()
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

      assertThat(state.mediaItem).isEqualTo(secondMediaItem)
      assertThat(player.currentMediaItem).isEqualTo(secondMediaItem)
      assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
      assertThat(player.playWhenReady).isFalse()
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(observedPlaybackStates).doesNotContain(Player.STATE_IDLE)
    }

  @Test
  fun rememberMotionPhotoState_withDefaultArguments_showsPhotoWithDefaultConfig() =
    runComposeUiTest {
      val state = setContentWithMotionPhotoState(player = null)

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
  fun playMotionAndShowStillPhoto_updatesPlayWhenReadyAndReturnsToPhotoOnEnded() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      val state = setContentWithMotionPhotoState(player)

      state.playMotion()
      assertThat(player.playWhenReady).isTrue()
      player.renderFirstFrame(true)
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isTrue()

      state.showStillPhoto()

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      state.playMotion()
      assertThat(state.visualState.isPhotoVisible).isFalse()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)
      assertThat(player.playWhenReady).isTrue()

      player.setPlaybackState(Player.STATE_ENDED)
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun toggle_togglesBetweenMotionAndStillPhotoAndReplaysAfterEnded() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    val state = setContentWithMotionPhotoState(player)

    // Initial toggle starts motion immediately before first frame renders.
    state.toggle()
    assertThat(player.playWhenReady).isTrue()
    player.renderFirstFrame(true)
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isFalse()
    assertThat(player.playWhenReady).isTrue()

    // Toggling while motion is playing returns to the still photo.
    state.toggle()

    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playWhenReady).isFalse()

    // Toggling again resumes motion; reaching STATE_ENDED resets the active motion state so the
    // next toggle replays motion from the start.
    state.toggle()
    player.setPlaybackState(Player.STATE_ENDED)
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playWhenReady).isFalse()

    state.toggle()

    assertThat(state.visualState.isPhotoVisible).isFalse()
    assertThat(player.playWhenReady).isFalse()
    advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)
    assertThat(player.playWhenReady).isTrue()
  }

  @Test
  fun toggle_withoutPlayer_togglesMotionAndControlsAutoPlayOnAttach() = runComposeUiTest {
    var currentPlayer by mutableStateOf<Player?>(null)
    lateinit var state: MotionPhotoState
    setContent {
      state = rememberMotionPhotoState(player = currentPlayer, mediaItem = testMediaItem)
    }
    waitForIdle()

    state.toggle()

    assertThat(state.visualState.isPhotoVisible).isTrue()

    // Toggling off before a player attaches cancels the queued motion request.
    state.toggle()

    val player1 = FakePlayer(bufferingDelayMs = 0)
    currentPlayer = player1
    waitForIdle()

    assertThat(player1.playWhenReady).isFalse()

    // Toggling on while detached queues motion to start once a player attaches.
    currentPlayer = null
    waitForIdle()
    state.toggle()

    val player2 = FakePlayer(bufferingDelayMs = 0)
    currentPlayer = player2
    waitForIdle()

    assertThat(player2.playWhenReady).isTrue()
  }

  @Test
  fun showStillPhoto_whenAlreadyShowingStillPhoto_doesNotSyncOrSeek() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    val state = setContentWithMotionPhotoState(player)
    player.setPosition(300L)
    val seekPositionsMs = recordSeekPositionsMs(player)

    state.showStillPhoto()

    assertThat(player.playWhenReady).isFalse()
    assertThat(player.currentPosition).isEqualTo(300L)
    assertThat(seekPositionsMs).isEmpty()
  }

  @Test
  fun playMotion_whenAlreadyPlayingMotion_doesNotSeek() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    val state = setContentWithPlayingMotionPhotoState(player)
    mainClock.autoAdvance = false
    player.setPosition(300L)
    val seekPositionsMs = recordSeekPositionsMs(player)

    state.playMotion()

    assertThat(state.visualState.isPhotoVisible).isFalse()
    assertThat(player.playWhenReady).isTrue()
    assertThat(player.currentPosition).isAtLeast(300L)
    assertThat(seekPositionsMs).isEmpty()
  }

  @Test
  fun showStillPhoto_duringDeferredRewindAfterEnded_forcesPhotoWithoutRestartingRewind() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      val state = setContentWithPlayingMotionPhotoState(player)
      mainClock.autoAdvance = false
      player.setPosition(300L)
      player.setPlaybackState(Player.STATE_ENDED)
      waitForIdle()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 2)

      state.showStillPhoto()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 2)

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()
      assertThat(player.currentPosition).isEqualTo(0L)

      // Switching to looping afterward keeps playback paused, confirming showStillPhoto() forced
      // the still photo rather than leaving only the ended state.
      state.config = MotionPhotoConfig.LOOP

      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun config_whenLoopEnabledWhileStillPhotoRequested_keepsStillPhoto() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    val state = setContentWithMotionPhotoState(player)

    state.config = MotionPhotoConfig.LOOP

    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE)
    assertThat(player.playWhenReady).isFalse()
  }

  @Test
  fun showStillPhoto_afterRenderingFirstFrame_pausesImmediatelyAndRewindsAfterTransitionDuration() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      val state = setContentWithPlayingMotionPhotoState(player)
      mainClock.autoAdvance = false
      player.setPosition(300L)

      state.showStillPhoto()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 2)

      // During the fade-in, the player is paused on its current frame without jumping to 0ms.
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()
      assertThat(player.currentPosition).isAtLeast(300L)

      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 2)

      // Once transitionDurationMs elapses, the player rewinds underneath the opaque photo.
      assertThat(player.currentPosition).isEqualTo(0L)
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun playMotion_afterDeferredRewindCompletes_doesNotSeekAgainWhenResumingPlayback() =
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
      val state = setContentWithPlayingMotionPhotoState(player)
      player.setPosition(300L)
      state.showStillPhoto()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)
      val seekPositionsMs = recordSeekPositionsMs(player)

      state.playMotion()

      assertThat(seekPositionsMs).isEmpty()
    }

  @Test
  fun rememberMotionPhotoState_whenPlaybackFails_fallsBackToPhoto() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    val state = setContentWithPlayingMotionPhotoState(player)
    assertThat(state.visualState.isPhotoVisible).isFalse()

    player.setPlayerError(testError)
    waitForIdle()

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
    val state = setContentWithPlayingMotionPhotoState(player)

    assertThat(state.visualState.isPhotoVisible).isTrue()
  }

  @Test
  fun toggle_withAudioOnlyTracks_showsStillPhotoAndPauses() = runComposeUiTest {
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
    val state = setContentWithPlayingMotionPhotoState(player)

    state.toggle()

    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playWhenReady).isFalse()

    // Even if video tracks appear later, the cancelled motion request keeps playback paused.
    player.setTracks(createTestTracks(selectedVideoTrackIndex = 0))
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playWhenReady).isFalse()
  }

  @Test
  fun rememberMotionPhotoState_whenTracksChange_updatesPhotoVisibility() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    val state = setContentWithPlayingMotionPhotoState(player)

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
            initialShowMotion = true,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      assertThat(state.visualState.isPhotoVisible).isFalse()

      player.setPlayerError(playbackError("First item error"))
      waitForIdle()
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
            initialShowMotion = true,
          )
      }
      waitForIdle()

      currentPlayer = player2
      waitForIdle()
      player2.renderFirstFrame(true)
      waitForIdle()

      // Error on detached player1 does not affect state.
      player1.setPlayerError(playbackError("Detached error"))
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player2.playWhenReady).isTrue()

      // Error on active player2 falls back to still photo.
      player2.setPlayerError(playbackError("Active error"))
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isTrue()
    }

  @Test
  fun showStillPhoto_afterPlayerError_doesNotPrepareOrClearError() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    val state = setContentWithPlayingMotionPhotoState(player)
    player.setPlayerError(testError)
    waitForIdle()

    state.showStillPhoto()

    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
    assertThat(player.playerError).isNotNull()
    assertThat(player.playWhenReady).isFalse()
  }

  @Test
  fun playMotion_afterPlayerError_repreparesPlayerAndRecovers() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    val state = setContentWithPlayingMotionPhotoState(player)
    player.setPlayerError(testError)
    waitForIdle()

    state.playMotion()
    player.renderFirstFrame(true)
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isFalse()
    assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
    assertThat(player.playerError).isNull()
    assertThat(player.playWhenReady).isTrue()
  }

  @Test
  fun playMotion_afterPlayerErrorOnBufferingPlayer_holdsPlayWhenReadyUntilFirstFrameAndTransition() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 1_000L)
      val playWhenReadyHistory = mutableListOf<Boolean>()
      val state = setContentWithMotionPhotoState(player)
      ShadowLooper.idleMainLooper(1_000L, TimeUnit.MILLISECONDS)
      waitForIdle()
      player.setPlayerError(testError)
      waitForIdle()
      player.addListener(
        object : Player.Listener {
          override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            playWhenReadyHistory.add(playWhenReady)
          }
        }
      )
      mainClock.autoAdvance = false

      state.playMotion()

      assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
      assertThat(player.playWhenReady).isFalse()

      waitForIdle()

      assertThat(playWhenReadyHistory).doesNotContain(true)
      assertThat(player.playWhenReady).isFalse()
      assertThat(state.visualState.isPhotoVisible).isTrue()

      player.renderFirstFrame(true)
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isFalse()

      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)

      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun toggle_afterPlayerError_repreparesPlayerAndRecoversOnFirstToggle() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    val state = setContentWithPlayingMotionPhotoState(player)
    player.setPlayerError(testError)
    waitForIdle()

    state.toggle()
    player.renderFirstFrame(true)
    waitForIdle()

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
      player.setPlayerError(playbackError("Preexisting error"))

      val state = setContentWithMotionPhotoState(player, initialShowMotion = true)

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
      val state = setContentWithPlayingMotionPhotoState(player)
      assertThat(state.visualState.isPhotoVisible).isFalse()

      player.addCommands(Player.COMMAND_GET_TRACKS)
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_whenMotionStarted_keepsPhotoVisibleUntilFirstFrameRendered() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      val state = setContentWithMotionPhotoState(player, initialShowMotion = true)

      // Still photo overlay is kept visible until onRenderedFirstFrame (anti-flicker).
      assertThat(state.visualState.isPhotoVisible).isTrue()

      player.renderFirstFrame(true)
      waitForIdle()
      assertThat(state.visualState.isPhotoVisible).isFalse()

      // Switching back to still photo and replaying on the already-primed surface does not block on
      // a second EVENT_RENDERED_FIRST_FRAME.
      state.showStillPhoto()
      assertThat(state.visualState.isPhotoVisible).isTrue()

      state.playMotion()
      assertThat(state.visualState.isPhotoVisible).isFalse()
    }

  @Test
  fun rememberMotionPhotoState_withLoopConfig_setsRepeatModeOne() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    val state =
      setContentWithMotionPhotoState(
        player,
        initialShowMotion = true,
        initialConfig = MotionPhotoConfig.LOOP,
      )

    assertThat(state.config).isEqualTo(MotionPhotoConfig.LOOP)
    assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE)
  }

  @Test
  fun rememberMotionPhotoState_withLoopConfigWithoutSetRepeatModeCommand_emulatesLoopOnEnded() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      player.removeCommands(Player.COMMAND_SET_REPEAT_MODE)
      val state =
        setContentWithPlayingMotionPhotoState(player, initialConfig = MotionPhotoConfig.LOOP)
      player.setPosition(300L)

      player.setPlaybackState(Player.STATE_ENDED)
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.currentPosition).isEqualTo(0L)
      assertThat(player.playWhenReady).isTrue()

      // When seeking to default position is also unavailable, falls back to showing the photo.
      player.removeCommands(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
      player.setPlaybackState(Player.STATE_ENDED)
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

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
    val state = setContentWithMotionPhotoState(player, initialShowMotion = true)
    assertThat(state.config).isEqualTo(MotionPhotoConfig.DEFAULT)
    assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_OFF)

    state.config = MotionPhotoConfig.LOOP

    assertThat(state.config).isEqualTo(MotionPhotoConfig.LOOP)
    assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE)
    assertThat(player.playWhenReady).isTrue()

    state.config = state.config.copy(loopMode = MotionPhotoConfig.LOOP_MODE_OFF)

    assertThat(state.config).isEqualTo(MotionPhotoConfig.DEFAULT)
    assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_OFF)
    assertThat(player.playWhenReady).isTrue()
  }

  @Test
  fun config_whenLoopEnabledAfterMotionEnded_restartsMotion() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    val state = setContentWithPlayingMotionPhotoState(player)
    player.setPosition(300L)
    player.setPlaybackState(Player.STATE_ENDED)
    waitForIdle()
    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playWhenReady).isFalse()

    state.config = MotionPhotoConfig.LOOP

    assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE)
    assertThat(player.currentPosition).isEqualTo(0L)
    advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)
    assertThat(player.playWhenReady).isTrue()
  }

  @Test
  fun config_whenLoopAndEndBoundaryUpdatedAfterMotionEnded_restartsFromStartPosition() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      val state =
        setContentWithPlayingMotionPhotoState(
          player,
          initialConfig = MotionPhotoConfig(rangeEnd = Boundary.START_OF_VIDEO.plusMs(500L)),
        )
      mainClock.autoAdvance = false
      player.setPosition(400L)
      player.setPlaybackState(Player.STATE_ENDED)
      waitForIdle()
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.currentPosition).isAtLeast(400L)

      state.config = MotionPhotoConfig.LOOP

      assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE)
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs)
        .isEqualTo(C.TIME_END_OF_SOURCE)
      assertThat(player.currentPosition).isEqualTo(0L)
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun showStillPhoto_withZeroTransitionDuration_rewindsImmediately() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    val state =
      setContentWithPlayingMotionPhotoState(
        player,
        initialConfig = MotionPhotoConfig(transitionDurationMs = 0L),
      )
    mainClock.autoAdvance = false
    player.setPosition(300L)

    state.showStillPhoto()

    assertThat(player.playWhenReady).isFalse()
    assertThat(player.currentPosition).isEqualTo(0L)
  }

  @Test
  fun playMotion_beforeTransitionElapses_rewindsAndCancelsDeferredRewind() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    val state = setContentWithPlayingMotionPhotoState(player)
    mainClock.autoAdvance = false
    player.setPosition(300L)
    state.showStillPhoto()
    advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)
    assertThat(player.currentPosition).isAtLeast(300L)

    // Re-triggering motion before the fade-in completes rewinds immediately and cancels the pending
    // deferred rewind.
    state.playMotion()

    assertThat(player.currentPosition).isEqualTo(0L)
    assertThat(player.playWhenReady).isFalse()
    assertThat(state.visualState.isPhotoVisible).isFalse()

    // Advancing past the remaining transition duration verifies the cancelled deferred rewind
    // does not fire and rewind the newly started playback back to 0ms.
    player.setPosition(120L)
    advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)
    assertThat(player.playWhenReady).isTrue()
    assertThat(player.currentPosition).isAtLeast(120L)
  }

  @Test
  fun config_whenUnrelatedPropertyChangesDuringDeferredRewind_doesNotRestartDelay() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      val state = setContentWithPlayingMotionPhotoState(player)
      mainClock.autoAdvance = false
      player.setPosition(300L)
      state.showStillPhoto()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 2)

      state.config = state.config.copy(loopMode = MotionPhotoConfig.LOOP_MODE_FROM_RANGE_START)
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)

      assertThat(player.currentPosition).isAtLeast(300L)

      // The original delay completes after the remaining quarter instead of restarting.
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)

      assertThat(player.currentPosition).isEqualTo(0L)
    }

  @Test
  fun config_whenTransitionDurationSetToZeroDuringDeferredRewind_completesOriginalDelayBeforeRewinding() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      val state = setContentWithPlayingMotionPhotoState(player)
      mainClock.autoAdvance = false
      player.setPosition(300L)
      state.showStillPhoto()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)

      state.config = state.config.copy(transitionDurationMs = 0L)
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 2)

      // The crossfade that is already running keeps its original duration, so the rewind is
      // neither pulled forward (where it would show through the half-faded photo) nor lost.
      assertThat(player.currentPosition).isAtLeast(300L)

      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)

      assertThat(player.currentPosition).isEqualTo(0L)
    }

  @Test
  fun config_whenResumeVideoFromPreviousPositionEnabledDuringDeferredRewind_cancelsRewind() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      val state = setContentWithPlayingMotionPhotoState(player)
      mainClock.autoAdvance = false
      player.setPosition(300L)
      state.showStillPhoto()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)

      state.config = state.config.copy(resumeVideoFromPreviousPosition = true)
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)

      assertThat(player.currentPosition).isAtLeast(300L)

      state.playMotion()
      assertThat(player.playWhenReady).isFalse()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)

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
            initialShowMotion = true,
          )
      }
      waitForIdle()
      firstPlayer.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      firstPlayer.setPosition(300L)
      state.showStillPhoto()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)

      currentPlayer = secondPlayer
      waitForIdle()
      mainClock.advanceTimeUntil { state.player === secondPlayer }
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)

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
            initialShowMotion = true,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      player.setPosition(300L)
      state.showStillPhoto()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)

      // A new state binds the same player and starts playing the new item mid-delay.
      currentItem = secondMediaItem
      waitForIdle()
      mainClock.advanceTimeUntil { player.currentMediaItem == secondMediaItem }
      assertThat(player.playWhenReady).isTrue()
      player.setPosition(120L)
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)

      // The previous state's pending rewind must not seek the new item.
      assertThat(player.currentPosition).isAtLeast(120L)
    }

  @Test
  fun playMotion_withZeroTransitionDuration_unpausesImmediatelyEvenAfterFirstFrameRendered() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      val state =
        setContentWithMotionPhotoState(
          player,
          initialConfig = MotionPhotoConfig(transitionDurationMs = 0L),
        )
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false

      state.playMotion()

      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun showStillPhoto_beforeDeferredPlayElapses_cancelsPendingPlay() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    val state = setContentWithMotionPhotoState(player)
    player.renderFirstFrame(true)
    waitForIdle()
    mainClock.autoAdvance = false

    state.playMotion()
    advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)
    assertThat(player.playWhenReady).isFalse()

    state.showStillPhoto()
    advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)

    assertThat(player.playWhenReady).isFalse()
  }

  @Test
  fun config_whenBoundaryUpdatedDuringDeferredPlay_updatesClippingInPlaceWithoutRestartingDelay() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      val state = setContentWithMotionPhotoState(player)
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false

      state.playMotion()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 2)
      assertThat(player.playWhenReady).isFalse()

      // Updating the clipping in place keeps the rendered frame and the ongoing crossfade, so the
      // transition delay completes after the remaining half instead of restarting.
      state.config = state.config.copy(rangeEnd = Boundary.START_OF_VIDEO.plusMs(600L))
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isFalse()

      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)

      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun config_whenTransitionDurationSetToZeroDuringDeferredPlay_completesOriginalDelayBeforePlaying() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      val state = setContentWithMotionPhotoState(player)
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      state.playMotion()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)

      state.config = state.config.copy(transitionDurationMs = 0L)
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 2)

      // The crossfade that is already running keeps its original duration, so play stays deferred
      // until the remaining delay completes.
      assertThat(player.playWhenReady).isFalse()

      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)

      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_withInitialShowMotionTrueWhilePreparing_unpausesAfterFirstFrameAndCrossfade() =
    runComposeUiTest {
      // The player buffers for a while when prepared, as ExoPlayer does.
      val player = createSeekableFakePlayer(durationMs = 1_000L, bufferingDelayMs = 100L)
      val state = setContentWithMotionPhotoState(player, initialShowMotion = true)
      mainClock.autoAdvance = false

      // Playback is held back while the first frame of the range is still being prepared.
      assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
      assertThat(player.playWhenReady).isFalse()
      assertThat(state.visualState.isPhotoVisible).isTrue()

      // The first frame starts the crossfade, and playback starts once it has completed.
      player.renderFirstFrame(true)
      waitForIdle()
      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isFalse()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)

      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_whenPlayerBecomesReadyWithoutRenderingFirstFrame_unpausesImmediately() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L, bufferingDelayMs = 100L)
      val state = setContentWithMotionPhotoState(player, initialShowMotion = true)
      assertThat(player.playWhenReady).isFalse()

      // A player that becomes ready without having rendered a frame (for example because its
      // surface was replaced while paused) only renders once it plays.
      ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
      waitForIdle()

      assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
      assertThat(player.playWhenReady).isTrue()
      assertThat(state.visualState.isPhotoVisible).isTrue()

      // Once playback produces that first frame, the photo crossfades out without re-pausing.
      mainClock.autoAdvance = false
      player.renderFirstFrame(true)
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun showStillPhoto_whilePlaybackIsPendingFirstFrame_staysPausedAfterFirstFrame() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L, bufferingDelayMs = 100L)
      val state = setContentWithMotionPhotoState(player, initialShowMotion = true)
      assertThat(player.playWhenReady).isFalse()

      state.showStillPhoto()
      renderFirstFrameAndCompleteTransition(player)

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun config_whenBoundaryBecomesEmptyDuringDeferredPlay_cancelsPendingPlay() = runComposeUiTest {
    val player = createSeekableFakePlayer(durationMs = 1_000L)
    val state = setContentWithMotionPhotoState(player)
    player.renderFirstFrame(true)
    waitForIdle()
    mainClock.autoAdvance = false

    state.playMotion()
    advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)
    assertThat(player.playWhenReady).isFalse()

    state.config = state.config.copy(rangeStart = Boundary.END_OF_VIDEO)
    advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)

    assertThat(player.playWhenReady).isFalse()
    assertThat(state.visualState.isPhotoVisible).isTrue()
  }

  @Test
  fun rememberMotionPhotoState_whenPlayerReplacedDuringDeferredPlay_doesNotUnpausePreviousPlayer() =
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
            initialShowMotion = false,
          )
      }
      waitForIdle()
      firstPlayer.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      state.playMotion()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)
      assertThat(firstPlayer.playWhenReady).isFalse()

      currentPlayer = secondPlayer
      waitForIdle()
      mainClock.advanceTimeUntil { state.player === secondPlayer }
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)

      assertThat(firstPlayer.playWhenReady).isFalse()
    }

  @Test
  fun rememberMotionPhotoState_whenMediaItemChangesDuringDeferredPlay_doesNotUnpauseNewMediaItem() =
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
            initialShowMotion = false,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      state.playMotion()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)
      assertThat(player.playWhenReady).isFalse()

      // A new state binds the same player with initialShowMotion = false mid-delay.
      currentItem = secondMediaItem
      waitForIdle()
      mainClock.advanceTimeUntil { player.currentMediaItem == secondMediaItem }
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)

      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun playMotion_whenPlayPauseCommandRemovedDuringDeferredPlay_keepsPlayWhenReadyFalseWithoutThrowing() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0, throwWhenCommandUnavailable = true)
      val state = setContentWithMotionPhotoState(player)
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      state.playMotion()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)
      assertThat(player.playWhenReady).isFalse()

      player.removeCommands(Player.COMMAND_PLAY_PAUSE)
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)

      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun rememberMotionPhotoState_withInitialShowMotionTrueOnIdleBufferingPlayer_neverSetsPlayWhenReadyBeforeFirstFrame() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 100L)
      val playWhenReadyHistory = mutableListOf<Boolean>()
      player.addListener(
        object : Player.Listener {
          override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            playWhenReadyHistory.add(playWhenReady)
          }
        }
      )

      val state = setContentWithMotionPhotoState(player, initialShowMotion = true)

      assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
      assertThat(player.playWhenReady).isFalse()
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(playWhenReadyHistory).doesNotContain(true)

      renderFirstFrameAndCompleteTransition(player)

      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_whenPlayerStopsDuringDeferredPlay_cancelsPendingPlayAndHoldsUntilRePreparedFirstFrame() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L, bufferingDelayMs = 1_000L)
      val state = setContentWithMotionPhotoState(player)
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      state.playMotion()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)
      assertThat(player.playWhenReady).isFalse()

      player.stop()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)

      assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
      assertThat(player.playWhenReady).isFalse()
      assertThat(state.visualState.isPhotoVisible).isTrue()

      player.prepare()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)

      assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
      assertThat(player.playWhenReady).isFalse()

      player.renderFirstFrame(true)
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isFalse()

      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)

      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_whenTracksBecomeEmptyDuringDeferredPlay_cancelsPendingPlayAndHoldsUntilNextFirstFrame() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          bufferingDelayMs = 1_000L,
          tracks = createTestTracks(selectedVideoTrackIndex = 0),
        )
      val state = setContentWithMotionPhotoState(player)
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      state.playMotion()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)
      assertThat(player.playWhenReady).isFalse()

      player.setTracks(Tracks.EMPTY)
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)

      assertThat(player.playWhenReady).isFalse()
      assertThat(state.visualState.isPhotoVisible).isTrue()

      renderFirstFrameAndCompleteTransition(player)

      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_whenMotionAvailabilityTogglesDuringPendingFirstFrame_updatesPlayWhenReadyAndRestoresHold() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L, bufferingDelayMs = 1_000L)
      val state = setContentWithMotionPhotoState(player, initialShowMotion = true)
      assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
      assertThat(player.playWhenReady).isFalse()

      player.setTracks(createTestTracks(selectedVideoTrackIndex = null))
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isTrue()

      player.setTracks(createTestTracks(selectedVideoTrackIndex = 0))
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      renderFirstFrameAndCompleteTransition(player)

      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_whenSetMediaItemCommandBecomesAvailableWithInitialShowMotionTrue_holdsUntilNextFirstFrame() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 1_000L)
      player.setMediaItem(MediaItem.fromUri("file:///other_motion_photo.jpg"))
      player.prepare()
      player.removeCommands(Player.COMMAND_SET_MEDIA_ITEM)
      val state = setContentWithMotionPhotoState(player, initialShowMotion = true)
      renderFirstFrameAndCompleteTransition(player)
      assertThat(player.playWhenReady).isTrue()
      mainClock.autoAdvance = false

      player.addCommands(Player.COMMAND_SET_MEDIA_ITEM)
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)

      assertThat(player.currentMediaItem).isEqualTo(testMediaItem)
      assertThat(player.playWhenReady).isFalse()
      assertThat(state.visualState.isPhotoVisible).isTrue()

      renderFirstFrameAndCompleteTransition(player)

      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun playMotionAndShowStillPhoto_withResumeVideoFromPreviousPosition_pausesAndResumesWithoutRewindingAndRestartsAfterEnded() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      player.setMediaItem(testMediaItem)
      player.prepare()
      player.setPosition(250L)
      val state =
        setContentWithMotionPhotoState(
          player,
          initialConfig = MotionPhotoConfig(resumeVideoFromPreviousPosition = true),
        )
      // Initial bind with resumeVideoFromPreviousPosition = true preserves existing position.
      assertThat(player.currentPosition).isEqualTo(250L)

      state.playMotion()
      player.renderFirstFrame(true)
      waitForIdle()
      assertThat(player.currentPosition).isAtLeast(250L)
      assertThat(player.playWhenReady).isTrue()

      player.setPosition(400L)
      state.showStillPhoto()

      assertThat(player.playWhenReady).isFalse()
      assertThat(player.currentPosition).isAtLeast(400L)

      state.playMotion()
      assertThat(player.playWhenReady).isFalse()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)

      assertThat(player.playWhenReady).isTrue()
      assertThat(player.currentPosition).isAtLeast(400L)

      // Reaching STATE_ENDED returns to photo; subsequent playMotion() rewinds to 0L to restart.
      player.setPlaybackState(Player.STATE_ENDED)
      waitForIdle()
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      state.playMotion()
      assertThat(player.currentPosition).isEqualTo(0L)
      assertThat(player.playWhenReady).isFalse()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)

      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun config_whenResumeVideoFromPreviousPositionDisabledWhileShowingPhoto_rewindsToDefaultPosition() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      val state =
        setContentWithPlayingMotionPhotoState(
          player,
          initialConfig = MotionPhotoConfig(resumeVideoFromPreviousPosition = true),
        )

      player.setPosition(350L)
      state.showStillPhoto()
      assertThat(player.currentPosition).isAtLeast(350L)

      // Disabling resumeVideoFromPreviousPosition while showing the photo rewinds the player.
      state.config = state.config.copy(resumeVideoFromPreviousPosition = false)

      assertThat(player.currentPosition).isEqualTo(0L)
    }

  @Test
  fun config_whenTransitionDurationUpdated_updatesVisualStateTransitionDuration() =
    runComposeUiTest {
      val state =
        setContentWithMotionPhotoState(
          player = null,
          initialConfig = MotionPhotoConfig(transitionDurationMs = 450L),
        )
      assertThat(state.visualState.transitionDurationMs).isEqualTo(450L)

      state.config = state.config.copy(transitionDurationMs = 100L)

      assertThat(state.visualState.transitionDurationMs).isEqualTo(100L)
    }

  @Test
  fun config_whenResumeVideoFromPreviousPositionDisabledWhilePlayingMotion_doesNotRewindOrInterruptPlayback() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      val state =
        setContentWithPlayingMotionPhotoState(
          player,
          initialConfig = MotionPhotoConfig(resumeVideoFromPreviousPosition = true),
        )
      player.setPosition(350L)

      state.config = state.config.copy(resumeVideoFromPreviousPosition = false)

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
            initialConfig = MotionPhotoConfig(rangeStart = Boundary.START_OF_VIDEO.plusMs(200L)),
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
      val state =
        setContentWithMotionPhotoState(
          player,
          initialShowMotion = true,
          initialConfig = MotionPhotoConfig(rangeStart = Boundary.END_OF_VIDEO.minusMs(300L)),
        )

      // Before duration is known, the unclipped item is prepared for metadata resolution while
      // keeping the photo visible and playback paused.
      assertThat(player.currentMediaItem).isEqualTo(testMediaItem)
      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(0L)
      assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
      assertThat(player.playWhenReady).isFalse()
      assertThat(state.visualState.isPhotoVisible).isTrue()

      player.setOriginalDuration(player.currentTimeline.getWindow(0, Timeline.Window()).uid, 1_000L)
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(700L)
      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isTrue()

      // An offset that reaches before the start of the video is clamped to it.
      state.config = state.config.copy(rangeStart = Boundary.END_OF_VIDEO.minusMs(1_500L))

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(0L)
      assertThat(player.playWhenReady).isTrue()

      // A start at the end of the video leaves nothing to play: playback pauses and the photo is
      // shown without cancelling the motion request or mutating the clipped item on the player.
      state.config = state.config.copy(rangeStart = Boundary.END_OF_VIDEO)

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(0L)
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      // Restoring a valid offset resumes playback in place after the crossfade from the still photo
      // completes, without resetting the first-frame gate.
      state.config = state.config.copy(rangeStart = Boundary.END_OF_VIDEO.minusMs(300L))

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(700L)
      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isFalse()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun config_whenStartBoundaryUpdated_updatesClippedMediaItemInPlaceAndRestartsFromStartPosition() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      val state =
        setContentWithPlayingMotionPhotoState(
          player,
          initialConfig = MotionPhotoConfig(rangeStart = Boundary.START_OF_VIDEO.plusMs(200L)),
        )
      val clippedItemUid = player.currentTimeline.getWindow(0, Timeline.Window()).uid
      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(200L)
      assertThat(state.visualState.isPhotoVisible).isFalse()
      player.setPosition(300L)

      state.config = state.config.copy(rangeStart = Boundary.END_OF_VIDEO.minusMs(350L))

      // The clipped item is updated in place and playback restarts from the new start position
      // without resetting the first-frame gate.
      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(650L)
      assertThat(player.currentTimeline.getWindow(0, Timeline.Window()).uid)
        .isEqualTo(clippedItemUid)
      assertThat(player.currentPosition).isEqualTo(0L)
      assertThat(player.playWhenReady).isTrue()
      assertThat(state.visualState.isPhotoVisible).isFalse()

      // Without COMMAND_CHANGE_MEDIA_ITEMS, in-place clipping updates are skipped until the
      // command becomes available.
      player.removeCommands(Player.COMMAND_CHANGE_MEDIA_ITEMS)
      state.config = state.config.copy(rangeStart = Boundary.END_OF_VIDEO.minusMs(200L))

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(650L)

      player.addCommands(Player.COMMAND_CHANGE_MEDIA_ITEMS)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(800L)

      // An offset equal to the video duration leaves an empty playback range.
      state.config = state.config.copy(rangeStart = Boundary.START_OF_VIDEO.plusMs(1_000L))

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun config_withClippedMediaItem_ignoresClippedDurationAndUsesOriginalDuration() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      val state =
        setContentWithMotionPhotoState(
          player,
          initialShowMotion = true,
          initialConfig = MotionPhotoConfig(rangeStart = Boundary.START_OF_VIDEO.plusMs(600L)),
        )
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
      state.config = state.config.copy(rangeStart = Boundary.END_OF_VIDEO.minusMs(300L))

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
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                resumeVideoFromPreviousPosition = true,
                rangeStart = Boundary.START_OF_VIDEO.plusMs(200L),
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
  fun rememberMotionPhotoState_withEndBoundaryStartOfVideoAndOffset_setsClippingEndPosition() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      setContent {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialConfig = MotionPhotoConfig(rangeEnd = Boundary.START_OF_VIDEO.plusMs(400L)),
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
      val state =
        setContentWithMotionPhotoState(
          player,
          initialShowMotion = true,
          initialConfig = MotionPhotoConfig(rangeEnd = Boundary.START_OF_VIDEO.plusMs(400L)),
        )
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(400L)

      // Switching to Boundary.END_OF_VIDEO while duration is still unknown keeps the photo visible
      // and pauses playback until the original duration is reported.
      state.config = MotionPhotoConfig(rangeEnd = Boundary.END_OF_VIDEO.minusMs(300L))

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(400L)
      assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
      assertThat(player.playWhenReady).isFalse()
      assertThat(state.visualState.isPhotoVisible).isTrue()

      player.setOriginalDuration(player.currentTimeline.getWindow(0, Timeline.Window()).uid, 1_000L)
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(0L)
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(700L)
      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isTrue()

      // When the original duration becomes unknown again (e.g. C.TIME_UNSET), playback pauses and
      // the photo is shown again rather than keeping the previously cached duration.
      player.setOriginalDuration(
        player.currentTimeline.getWindow(0, Timeline.Window()).uid,
        C.TIME_UNSET,
      )
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun config_whenEndUpdated_replacesClippedMediaItemKeepingPosition() = runComposeUiTest {
    val player = createSeekableFakePlayer(durationMs = 1_000L)
    val state =
      setContentWithPlayingMotionPhotoState(
        player,
        initialConfig = MotionPhotoConfig(rangeStart = Boundary.START_OF_VIDEO.plusMs(100L)),
      )
    val clippedItemUid = player.currentTimeline.getWindow(0, Timeline.Window()).uid
    player.setPosition(250L)
    assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs)
      .isEqualTo(C.TIME_END_OF_SOURCE)
    assertThat(state.visualState.isPhotoVisible).isFalse()

    state.config = state.config.copy(rangeEnd = Boundary.START_OF_VIDEO.plusMs(600L))

    // The start position is unchanged, so the clipped item is updated in place keeping the
    // current playback position and without resetting the first-frame gate.
    assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(100L)
    assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
    assertThat(player.currentTimeline.getWindow(0, Timeline.Window()).uid).isEqualTo(clippedItemUid)
    assertThat(player.currentPosition).isAtLeast(250L)
    assertThat(player.playWhenReady).isTrue()
    assertThat(state.visualState.isPhotoVisible).isFalse()

    // An end boundary beyond the video duration is passed through and clamped by the player.
    state.config = state.config.copy(rangeEnd = Boundary.START_OF_VIDEO.plusMs(1_500L))

    assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(1_500L)
    assertThat(player.playWhenReady).isTrue()
  }

  @Test
  fun config_whenEndBoundaryUpdatedToBeforeCurrentPosition_restartsFromStartPosition() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      val state =
        setContentWithPlayingMotionPhotoState(
          player,
          initialConfig = MotionPhotoConfig(rangeStart = Boundary.START_OF_VIDEO.plusMs(100L)),
        )
      player.setPosition(800L)

      state.config = state.config.copy(rangeEnd = Boundary.START_OF_VIDEO.plusMs(600L))

      // The current position lies beyond the new range, so playback restarts from its start.
      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(100L)
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
      assertThat(player.currentPosition).isEqualTo(0L)
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun config_whenEndBoundaryUpdatedToCurrentPosition_restartsFromStartPosition() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      val state =
        setContentWithPlayingMotionPhotoState(
          player,
          initialConfig = MotionPhotoConfig(rangeStart = Boundary.START_OF_VIDEO.plusMs(100L)),
        )
      // Paused so that the position does not advance before the config change is applied.
      player.pause()
      player.setPosition(500L)

      // The new range [100ms, 600ms] is exactly as long as the current position.
      state.config = state.config.copy(rangeEnd = Boundary.START_OF_VIDEO.plusMs(600L))

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
      assertThat(player.currentPosition).isEqualTo(0L)
    }

  @Test
  fun config_whenEndBoundaryUpdatedToJustAfterCurrentPosition_keepsPosition() = runComposeUiTest {
    val player = createSeekableFakePlayer(durationMs = 1_000L)
    val state =
      setContentWithPlayingMotionPhotoState(
        player,
        initialConfig = MotionPhotoConfig(rangeStart = Boundary.START_OF_VIDEO.plusMs(100L)),
      )
    // Paused so that the position does not advance before the config change is applied.
    player.pause()
    player.setPosition(499L)

    // The new range [100ms, 600ms] is 1ms longer than the current position.
    state.config = state.config.copy(rangeEnd = Boundary.START_OF_VIDEO.plusMs(600L))

    assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
    assertThat(player.currentPosition).isEqualTo(499L)
  }

  @Test
  fun config_whenStartAndEndBoundaryUpdated_restartsFromNewStartPosition() = runComposeUiTest {
    val player = createSeekableFakePlayer(durationMs = 1_000L)
    val state =
      setContentWithPlayingMotionPhotoState(
        player,
        initialConfig = MotionPhotoConfig(rangeStart = Boundary.START_OF_VIDEO.plusMs(100L)),
      )
    player.setPosition(250L)

    state.config =
      state.config.copy(
        rangeStart = Boundary.START_OF_VIDEO.plusMs(200L),
        rangeEnd = Boundary.START_OF_VIDEO.plusMs(600L),
      )

    assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(200L)
    assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
    assertThat(player.currentPosition).isEqualTo(0L)
    assertThat(player.playWhenReady).isTrue()
  }

  @Test
  fun config_whenEndBoundaryUpdatedWhileShowingPhotoWithResumeFromPreviousPosition_keepsPosition() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      val state =
        setContentWithPlayingMotionPhotoState(
          player,
          initialConfig =
            MotionPhotoConfig(
              resumeVideoFromPreviousPosition = true,
              rangeStart = Boundary.START_OF_VIDEO.plusMs(100L),
            ),
        )
      state.showStillPhoto()
      player.setPosition(300L)
      assertThat(player.playWhenReady).isFalse()

      state.config = state.config.copy(rangeEnd = Boundary.START_OF_VIDEO.plusMs(600L))

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
      assertThat(player.currentPosition).isEqualTo(300L)
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun config_whenEndBoundaryUpdatedWhileShowingPhotoWithResumeFalse_restartsFromStartPosition() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      val state =
        setContentWithPlayingMotionPhotoState(
          player,
          initialConfig =
            MotionPhotoConfig(
              resumeVideoFromPreviousPosition = false,
              rangeStart = Boundary.START_OF_VIDEO.plusMs(100L),
            ),
        )
      mainClock.autoAdvance = false
      player.setPosition(300L)
      state.showStillPhoto()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 4)
      assertThat(player.currentPosition).isAtLeast(300L)
      val seekPositionsMs = recordSeekPositionsMs(player)

      state.config = state.config.copy(rangeEnd = Boundary.START_OF_VIDEO.plusMs(600L))

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
      assertThat(player.currentPosition).isEqualTo(0L)
      assertThat(player.playWhenReady).isFalse()
      assertThat(seekPositionsMs).containsExactly(0L)
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)
      assertThat(seekPositionsMs).containsExactly(0L)
    }

  @Test
  fun rememberMotionPhotoState_whenPlayerAlreadyHoldsMediaItemAndEndBoundaryChangesWithResumeFalse_rewindsToDefaultPosition() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      player.pause()
      player.setPosition(300L)

      setContent {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                resumeVideoFromPreviousPosition = false,
                rangeEnd = Boundary.START_OF_VIDEO.plusMs(600L),
              ),
          )
      }
      waitForIdle()

      assertThat(player.currentTimeline.getWindow(0, Timeline.Window()).uid)
        .isEqualTo("motion_photo")
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
      assertThat(player.currentPosition).isEqualTo(0L)
    }

  @Test
  fun config_whenEndBoundaryUpdatedWhileShowingPhoto_keepsPlayWhenReadyFalse() = runComposeUiTest {
    val player = createSeekableFakePlayer(durationMs = 1_000L)
    val state = setContentWithMotionPhotoState(player)
    assertThat(player.playWhenReady).isFalse()

    state.config = state.config.copy(rangeEnd = Boundary.START_OF_VIDEO.plusMs(600L))

    assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
    assertThat(player.playWhenReady).isFalse()
    assertThat(state.visualState.isPhotoVisible).isTrue()
  }

  @Test
  fun rememberMotionPhotoState_withEndBoundaryBeyondUnknownDuration_setsClippedMediaItemOnce() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      setContent {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig = MotionPhotoConfig(rangeEnd = Boundary.START_OF_VIDEO.plusMs(1_500L)),
          )
      }
      waitForIdle()
      val initialClippedItem = checkNotNull(player.currentMediaItem)
      assertThat(initialClippedItem.clippingConfiguration.endPositionMs).isEqualTo(1_500L)

      // Learning the duration does not update the clipped item again.
      player.setOriginalDuration(player.currentTimeline.getWindow(0, Timeline.Window()).uid, 1_000L)
      waitForIdle()

      assertThat(player.currentMediaItem).isSameInstanceAs(initialClippedItem)
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun toggle_whilePlaybackRangeEmpty_cancelsMotionRequest() = runComposeUiTest {
    val player = createSeekableFakePlayer(durationMs = 1_000L)
    val state =
      setContentWithMotionPhotoState(
        player,
        initialShowMotion = true,
        initialConfig =
          MotionPhotoConfig(
            rangeStart = Boundary.START_OF_VIDEO.plusMs(500L),
            rangeEnd = Boundary.START_OF_VIDEO.plusMs(500L),
          ),
      )
    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playWhenReady).isFalse()

    state.toggle()

    // Making the range valid afterward does not start playback because toggle() cancelled the
    // motion request.
    state.config = MotionPhotoConfig.DEFAULT

    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playWhenReady).isFalse()
  }

  @Test
  fun rememberMotionPhotoState_withEndBoundaryLessThanOrEqualToStartBoundary_keepsPhotoVisibleAndPauses() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      val state =
        setContentWithMotionPhotoState(
          player,
          initialShowMotion = true,
          initialConfig =
            MotionPhotoConfig(
              rangeStart = Boundary.START_OF_VIDEO.plusMs(500L),
              rangeEnd = Boundary.START_OF_VIDEO.plusMs(500L),
            ),
        )

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      // An end before the start leaves nothing to play either.
      state.config = state.config.copy(rangeStart = Boundary.START_OF_VIDEO.plusMs(600L))

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      // Changing only rangeEnd from Boundary.START_OF_VIDEO to Boundary.END_OF_VIDEO makes the
      // range valid and resumes playback.
      state.config = MotionPhotoConfig(rangeEnd = Boundary.START_OF_VIDEO)
      assertThat(player.playWhenReady).isFalse()

      state.config = state.config.copy(rangeEnd = Boundary.END_OF_VIDEO)

      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_withStartBoundaryPhotoFrameAndMetadata_setsClippingToMetadataTimestampAndOffset() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
        )

      setContent {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialConfig = MotionPhotoConfig(rangeStart = Boundary.PHOTO_FRAME.minusMs(50L)),
          )
      }
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(350L)
    }

  @Test
  fun rememberMotionPhotoState_withStartBoundaryPhotoFrameAtOrBeyondVideoDuration_keepsPhotoVisibleAndPauses() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 1_000_000L),
        )

      val state =
        setContentWithMotionPhotoState(
          player,
          initialShowMotion = true,
          initialConfig = MotionPhotoConfig(rangeStart = Boundary.PHOTO_FRAME),
        )

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      // A photo frame beyond the video duration also leaves nothing to play.
      player.setTracks(
        createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 1_200_000L)
      )
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun rememberMotionPhotoState_withPhotoFrameFallbackAndOffsetsAroundPhotoFrame_playsEndOfVideo() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTestTracks(selectedVideoTrackIndex = 0),
        )
      setContentWithMotionPhotoState(
        player,
        initialShowMotion = true,
        initialConfig =
          MotionPhotoConfig(
            rangeStart = Boundary.PHOTO_FRAME.minusMs(300L),
            rangeEnd = Boundary.PHOTO_FRAME.plusMs(300L),
          ),
      )

      // The still photo is treated as the last frame of the video, so the range around it becomes
      // the last 300ms of the video, with the end clamped to it.
      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(700L)
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs)
        .isEqualTo(C.TIME_END_OF_SOURCE)
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_withPlayToPhotoPresetAndPhotoFrameAtStartOfVideo_keepsPhotoVisibleAndPauses() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 0L),
        )

      val state =
        setContentWithMotionPhotoState(
          player,
          initialShowMotion = true,
          initialConfig = MotionPhotoConfig.PLAY_TO_PHOTO,
        )

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun rememberMotionPhotoState_whenPlayerBecomesReadyWithoutReportingTracks_fallsBackToEndOfVideo() =
    runComposeUiTest {
      // The player is still buffering the item when the state binds and never reports any tracks.
      val player = createSeekableFakePlayer(durationMs = 1_000L, bufferingDelayMs = 100L)

      val state =
        setContentWithMotionPhotoState(
          player,
          initialShowMotion = true,
          initialConfig = MotionPhotoConfig(rangeEnd = Boundary.PHOTO_FRAME.minusMs(300L)),
        )

      assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
      assertThat(player.currentMediaItem).isEqualTo(testMediaItem)
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
      waitForIdle()

      assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(700L)
      assertThat(player.playWhenReady).isTrue()

      player.renderFirstFrame(true)
      waitForIdle()
      assertThat(state.visualState.isPhotoVisible).isFalse()

      // Rebuffering after tracks were already treated as known keeps the fallback clipping and
      // continues playing without showing the still photo again.
      player.setPlaybackState(Player.STATE_BUFFERING)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(700L)
      assertThat(player.playWhenReady).isTrue()
      assertThat(state.visualState.isPhotoVisible).isFalse()
    }

  @Test
  fun rememberMotionPhotoState_whenTracksNoLongerCarryMotionPhotoMetadata_doesNotKeepStalePhotoFrame() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
        )
      val state =
        setContentWithPlayingMotionPhotoState(
          player,
          initialConfig = MotionPhotoConfig.PLAY_TO_PHOTO,
        )

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs).isEqualTo(401_000L)
      assertThat(state.visualState.isPhotoVisible).isFalse()

      // Populated tracks without the metadata treat the still photo as the last frame of the video
      // again, so the end boundary is updated in place to the end of the video.
      player.setTracks(createTestTracks(selectedVideoTrackIndex = 0))
      waitForIdle()

      assertThat(player.currentMediaItem).isEqualTo(testMediaItem)
      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isTrue()

      // Restoring the metadata resumes playback, then removing COMMAND_GET_TRACKS resets the
      // cached timestamp rather than keeping the stale photo frame.
      player.setTracks(createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L))
      waitForIdle()
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs).isEqualTo(401_000L)

      player.removeCommands(Player.COMMAND_GET_TRACKS)
      waitForIdle()

      assertThat(player.currentMediaItem).isEqualTo(testMediaItem)
      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_withPlayToPhotoPresetAndMetadata_setsClippingEndToPhotoFrameInUs() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 600_666L),
        )

      setContent {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialConfig = MotionPhotoConfig.PLAY_TO_PHOTO,
          )
      }
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionUs).isEqualTo(0L)
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs).isEqualTo(601_666L)
    }

  @Test
  fun rememberMotionPhotoState_withStartBoundaryPhotoFrameWithoutMetadata_leavesNothingToPlay() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTestTracks(selectedVideoTrackIndex = 0),
        )

      val state =
        setContentWithMotionPhotoState(
          player,
          initialShowMotion = true,
          initialConfig = MotionPhotoConfig(rangeStart = Boundary.PHOTO_FRAME),
        )

      // The still photo is treated as the last frame of the video, so a start at or after it leaves
      // nothing to play: the unclipped item stays on the player with the photo visible and playback
      // paused, without cancelling the motion request.
      assertThat(player.currentMediaItem).isEqualTo(testMediaItem)
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      state.config = state.config.copy(rangeStart = Boundary.PHOTO_FRAME.plusMs(100L))

      assertThat(player.currentMediaItem).isEqualTo(testMediaItem)
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      // A negative offset resolves relative to Boundary.END_OF_VIDEO rather than staying unset.
      state.config = state.config.copy(rangeStart = Boundary.PHOTO_FRAME.minusMs(200L))

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(800L)
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_withBoundaryPhotoFrameAndUnsetMetadataTimestamp_fallsBackToEndOfVideo() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = C.TIME_UNSET),
        )

      setContent {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialConfig =
              MotionPhotoConfig(
                rangeStart = Boundary.PHOTO_FRAME.minusMs(400L),
                rangeEnd = Boundary.PHOTO_FRAME.minusMs(100L),
              ),
          )
      }
      waitForIdle()

      // Both boundaries resolve relative to the end of the video, without the photo frame
      // tolerance.
      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(600L)
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(900L)
    }

  @Test
  fun rememberMotionPhotoState_withEndBoundaryPhotoFrameWithoutGetTracksCommandAndUnknownDuration_fallsBackToEndOfVideoOnceDurationIsKnown() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 100L)
      player.removeCommands(Player.COMMAND_GET_TRACKS)

      val state =
        setContentWithMotionPhotoState(
          player,
          initialShowMotion = true,
          initialConfig = MotionPhotoConfig(rangeEnd = Boundary.PHOTO_FRAME.minusMs(300L)),
        )

      assertThat(player.currentMediaItem).isEqualTo(testMediaItem)
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      player.setOriginalDuration(player.currentTimeline.getWindow(0, Timeline.Window()).uid, 1_000L)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(700L)
      assertThat(player.playWhenReady).isFalse()

      renderFirstFrameAndCompleteTransition(player)

      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_withPlayToPhotoPreset_showsPhotoWhenPlaybackEnds() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
        )
      val state =
        setContentWithPlayingMotionPhotoState(
          player,
          initialConfig = MotionPhotoConfig.PLAY_TO_PHOTO,
        )
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs).isEqualTo(401_000L)
      assertThat(state.visualState.isPhotoVisible).isFalse()

      player.setPlaybackState(Player.STATE_ENDED)
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun rememberMotionPhotoState_whenTracksChangeWithMotionPhotoMetadata_updatesClippingConfiguration() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTestTracks(selectedVideoTrackIndex = 0),
        )

      setContent {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialConfig = MotionPhotoConfig(rangeStart = Boundary.PHOTO_FRAME),
          )
      }
      waitForIdle()
      // Tracks without MotionPhotoMetadata treat the still photo as the last frame of the video, so
      // a start at the photo frame leaves nothing to play and the unclipped item on the player.
      assertThat(player.currentMediaItem).isEqualTo(testMediaItem)

      player.setTracks(createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 320_000L))
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(320L)
    }

  @Test
  fun rememberMotionPhotoState_withEndBoundaryPhotoFrameAndDurationKnownBeforeTracks_playsToEndOfVideoOnceTracksAreKnown() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L, bufferingDelayMs = 100L)
      val setClippingEndPositionsUs = recordSetClippingEndPositionsUs(player)

      val state =
        setContentWithMotionPhotoState(
          player,
          initialShowMotion = true,
          initialConfig = MotionPhotoConfig.PLAY_TO_PHOTO,
        )

      // The photo frame stays unresolved until the tracks of the item are known, so the unclipped
      // item stays on the player with the photo visible and playback paused.
      assertThat(player.currentMediaItem).isEqualTo(testMediaItem)
      assertThat(setClippingEndPositionsUs).isEmpty()
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      player.setTracks(createTestTracks(selectedVideoTrackIndex = 0))
      waitForIdle()

      // Tracks without MotionPhotoMetadata fall back to the end of the video, so the unclipped item
      // plays as it is without being set again, once its first frame has rendered underneath the
      // photo and the crossfade has completed.
      assertThat(player.currentMediaItem).isEqualTo(testMediaItem)
      assertThat(setClippingEndPositionsUs).isEmpty()
      assertThat(player.playWhenReady).isFalse()
      renderFirstFrameAndCompleteTransition(player)
      assertThat(player.playWhenReady).isTrue()

      // The tracks reported again do not set the item either.
      player.setTracks(createTestTracks(selectedVideoTrackIndex = 1))
      waitForIdle()

      assertThat(setClippingEndPositionsUs).isEmpty()
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_withEndBoundaryPhotoFrameAndMetadataKnownAfterDuration_appliesPhotoFrameWithoutFallbackItem() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L, bufferingDelayMs = 100L)
      val setClippingEndPositionsUs = recordSetClippingEndPositionsUs(player)

      setContent {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialConfig = MotionPhotoConfig(rangeEnd = Boundary.PHOTO_FRAME.minusMs(100L)),
          )
      }
      waitForIdle()
      assertThat(setClippingEndPositionsUs).isEmpty()

      player.setTracks(createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 320_000L))
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs).isEqualTo(220_000L)
      assertThat(setClippingEndPositionsUs).containsExactly(220_000L)
    }

  @Test
  fun rememberMotionPhotoState_whenPlayerAlreadyReadyWithoutTracks_fallsBackToEndOfVideoImmediately() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L, bufferingDelayMs = 0L)
      player.prepare()

      setContent {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig = MotionPhotoConfig(rangeEnd = Boundary.PHOTO_FRAME.minusMs(300L)),
          )
      }
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(700L)
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_whenUnpreparedPlayerBecomesReadySynchronouslyDuringBind_fallsBackToEndOfVideoImmediately() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L, bufferingDelayMs = 0L)

      setContent {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig = MotionPhotoConfig(rangeEnd = Boundary.PHOTO_FRAME.minusMs(300L)),
          )
      }
      waitForIdle()

      assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(700L)
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_whenPlayerAlreadyEndedWithoutTracks_fallsBackToEndOfVideoImmediately() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L, bufferingDelayMs = 100L)
      player.setPlaybackState(Player.STATE_ENDED)

      setContent {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialConfig = MotionPhotoConfig(rangeEnd = Boundary.PHOTO_FRAME.minusMs(300L)),
          )
      }
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(700L)
    }

  @Test
  fun rememberMotionPhotoState_whenPlayerEndsWithoutReportingTracks_fallsBackToEndOfVideo() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L, bufferingDelayMs = 100L)

      setContent {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialConfig = MotionPhotoConfig(rangeEnd = Boundary.PHOTO_FRAME.minusMs(300L)),
          )
      }
      waitForIdle()
      assertThat(player.currentMediaItem).isEqualTo(testMediaItem)

      player.setPlaybackState(Player.STATE_ENDED)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(700L)
    }

  @Test
  fun rememberMotionPhotoState_whenFreshPlayerBecomesReadySynchronouslyDuringBindWithoutDuration_fallsBackOnceDurationIsKnown() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)

      val state =
        setContentWithMotionPhotoState(
          player,
          initialShowMotion = true,
          initialConfig = MotionPhotoConfig(rangeEnd = Boundary.PHOTO_FRAME.minusMs(300L)),
        )

      assertThat(player.currentMediaItem).isEqualTo(testMediaItem)
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      player.setOriginalDuration(player.currentTimeline.getWindow(0, Timeline.Window()).uid, 1_000L)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(700L)
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_whenPlayerStopsWithEmptyTracks_doesNotFallBackBeforeRePreparing() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          bufferingDelayMs = 100L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
        )
      val setClippingEndPositionsUs = recordSetClippingEndPositionsUs(player)

      setContent {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialConfig = MotionPhotoConfig(rangeEnd = Boundary.PHOTO_FRAME.minusMs(100L)),
          )
      }
      waitForIdle()
      assertThat(setClippingEndPositionsUs).containsExactly(300_000L)

      // Stop and re-prepare into STATE_BUFFERING, then clear tracks via EVENT_TRACKS_CHANGED
      // while buffering (matching ExoPlayer's lifecycle).
      player.stop()
      waitForIdle()
      player.prepare()
      waitForIdle()
      player.setTracks(Tracks.EMPTY)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs).isEqualTo(300_000L)
      assertThat(setClippingEndPositionsUs).containsExactly(300_000L)

      player.setTracks(createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L))
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs).isEqualTo(300_000L)
      assertThat(setClippingEndPositionsUs).containsExactly(300_000L)
    }

  @Test
  fun rememberMotionPhotoState_whenTracklessPlayerStopsAndRePrepares_resetsKnownTracksInIdle() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L, bufferingDelayMs = 100L)

      val state =
        setContentWithMotionPhotoState(
          player,
          initialShowMotion = true,
          initialConfig = MotionPhotoConfig(rangeEnd = Boundary.PHOTO_FRAME.minusMs(100L)),
        )
      ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
      waitForIdle()
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(900L)
      assertThat(player.playWhenReady).isTrue()

      player.stop()
      waitForIdle()
      player.prepare()
      waitForIdle()

      // Transitioning through STATE_IDLE with empty tracks reset hasKnownTracks, so playback waits
      // while buffering rather than keeping the stale fallback state.
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      player.setTracks(createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L))
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs).isEqualTo(300_000L)
      assertThat(player.playWhenReady).isFalse()

      renderFirstFrameAndCompleteTransition(player)

      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_whenPlayerReplacedWithBufferingPlayer_doesNotCarryOverHasKnownTracks() =
    runComposeUiTest {
      val firstPlayer =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTestTracks(selectedVideoTrackIndex = 0),
        )
      val secondPlayer = createSeekableFakePlayer(durationMs = 1_000L, bufferingDelayMs = 100L)
      var currentPlayer by mutableStateOf(firstPlayer)

      setContent {
        val unused =
          rememberMotionPhotoState(
            player = currentPlayer,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig = MotionPhotoConfig(rangeEnd = Boundary.PHOTO_FRAME.minusMs(100L)),
          )
      }
      waitForIdle()
      assertThat(firstPlayer.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(900L)

      secondPlayer.prepare()
      currentPlayer = secondPlayer
      waitForIdle()

      // Replacing with an already-buffering player must reset hasKnownTracks so secondPlayer stays
      // unclipped and paused until its own tracks are reported.
      assertThat(secondPlayer.currentMediaItem).isEqualTo(testMediaItem)
      assertThat(secondPlayer.playWhenReady).isFalse()

      secondPlayer.setTracks(
        createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L)
      )
      waitForIdle()

      assertThat(secondPlayer.currentMediaItem?.clippingConfiguration?.endPositionUs)
        .isEqualTo(300_000L)
      assertThat(secondPlayer.playWhenReady).isFalse()

      renderFirstFrameAndCompleteTransition(secondPlayer)

      assertThat(secondPlayer.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_withStartAndEndBoundaryAtSamePhotoFrame_keepsPhotoVisibleAndPauses() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
        )

      val state =
        setContentWithMotionPhotoState(
          player,
          initialShowMotion = true,
          initialConfig =
            MotionPhotoConfig(rangeStart = Boundary.PHOTO_FRAME, rangeEnd = Boundary.PHOTO_FRAME),
        )

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun rememberMotionPhotoState_withPhotoFrameEndAndNonZeroOffset_doesNotApplyPhotoFrameTolerance() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
        )

      val state =
        setContentWithMotionPhotoState(
          player,
          initialConfig = MotionPhotoConfig(rangeEnd = Boundary.PHOTO_FRAME.plusMs(500L)),
        )

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs).isEqualTo(900_000L)

      state.config = state.config.copy(rangeEnd = Boundary.PHOTO_FRAME.minusMs(1L))

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs).isEqualTo(399_000L)
    }

  @Test
  fun rememberMotionPhotoState_withPhotoFrameStartOffsetOneMsBeforeEnd_clipsRangeWithTolerance() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
        )
      setContentWithMotionPhotoState(
        player,
        initialShowMotion = true,
        initialConfig =
          MotionPhotoConfig(
            rangeStart = Boundary.PHOTO_FRAME.minusMs(1L),
            rangeEnd = Boundary.PHOTO_FRAME,
          ),
      )

      // The range [399ms, 400ms] is not empty, and the tolerance is applied to the end position.
      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionUs)
        .isEqualTo(399_000L)
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs).isEqualTo(401_000L)
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun config_whenEndBoundaryUpdatedToPhotoFrameAtCurrentPosition_restartsFromStartOfRange() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 600_000L),
        )
      val state =
        setContentWithPlayingMotionPhotoState(
          player,
          initialConfig = MotionPhotoConfig(rangeStart = Boundary.START_OF_VIDEO.plusMs(100L)),
        )
      // 500ms into the [100ms, 1000ms] range is 600ms in the video, right at the photo frame.
      player.setPosition(500L)

      state.config = state.config.copy(rangeEnd = Boundary.PHOTO_FRAME)

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs).isEqualTo(601_000L)
      assertThat(player.currentPosition).isEqualTo(0L)
    }

  @Test
  fun rememberMotionPhotoState_withPreloadedReadyPlayerAndUpdatedClipping_seeksOnce() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      player.prepare()
      val seekPositionsMs = recordSeekPositionsMs(player)

      setContentWithMotionPhotoState(
        player,
        initialShowMotion = true,
        initialConfig = MotionPhotoConfig(rangeStart = Boundary.START_OF_VIDEO.plusMs(100L)),
      )

      assertThat(seekPositionsMs).containsExactly(0L)
      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(100L)
    }

  @Test
  fun rememberMotionPhotoState_withPreloadedIdlePlayerAndUpdatedClipping_seeksOnce() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      val seekPositionsMs = recordSeekPositionsMs(player)

      setContentWithMotionPhotoState(
        player,
        initialShowMotion = true,
        initialConfig = MotionPhotoConfig(rangeStart = Boundary.START_OF_VIDEO.plusMs(100L)),
      )

      assertThat(seekPositionsMs).containsExactly(0L)
      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(100L)
    }

  @Test
  fun rememberMotionPhotoState_withEmptyPlayerWhenSynchronousPreparePopulatesMetadata_doesNotSeek() =
    runComposeUiTest {
      val fakePlayer = FakePlayer(bufferingDelayMs = 10_000L)
      val player =
        object : ForwardingPlayer(fakePlayer) {
          override fun prepare() {
            super.prepare()
            val windowUid = fakePlayer.currentTimeline.getWindow(0, Timeline.Window()).uid
            fakePlayer.setOriginalDuration(windowUid, 1_000L)
            fakePlayer.setTracks(
              createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L)
            )
          }
        }
      val seekPositionsMs = recordSeekPositionsMs(player)

      setContentWithMotionPhotoState(player, initialConfig = MotionPhotoConfig.PLAY_TO_PHOTO)

      assertThat(seekPositionsMs).isEmpty()
      assertThat(fakePlayer.currentMediaItem?.clippingConfiguration?.endPositionUs)
        .isEqualTo(401_000L)
    }

  @Test
  fun rememberMotionPhotoState_withPreloadedPlayerWhenSynchronousPreparePopulatesMetadata_seeksOnce() =
    runComposeUiTest {
      val fakePlayer =
        createSeekableFakePlayer(durationMs = C.TIME_UNSET, bufferingDelayMs = 10_000L)
      val player =
        object : ForwardingPlayer(fakePlayer) {
          override fun prepare() {
            super.prepare()
            fakePlayer.setOriginalDuration("motion_photo", 1_000L)
            fakePlayer.setTracks(
              createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L)
            )
          }
        }
      val seekPositionsMs = recordSeekPositionsMs(player)

      setContentWithMotionPhotoState(player, initialConfig = MotionPhotoConfig.PLAY_TO_PHOTO)

      assertThat(seekPositionsMs).containsExactly(0L)
      assertThat(fakePlayer.currentMediaItem?.clippingConfiguration?.endPositionUs)
        .isEqualTo(401_000L)
    }

  @Test
  fun rememberMotionPhotoState_whenSynchronousPrepareClearsErrorAndPopulatesDuration_holdsPlayWhenReady() =
    runComposeUiTest {
      val fakePlayer =
        createSeekableFakePlayer(
          durationMs = C.TIME_UNSET,
          mediaItem = MediaItem.fromUri("file:///other_photo.jpg"),
          bufferingDelayMs = 10_000L,
        )
      fakePlayer.prepare()
      fakePlayer.setPlayerError(playbackError("Stale error"))
      val player =
        object : ForwardingPlayer(fakePlayer) {
          override fun prepare() {
            super.prepare()
            val windowUid = fakePlayer.currentTimeline.getWindow(0, Timeline.Window()).uid
            fakePlayer.setOriginalDuration(windowUid, 1_000L)
            fakePlayer.setTracks(createTestTracks(selectedVideoTrackIndex = 0))
          }
        }
      val listener = mock(Player.Listener::class.java)
      player.addListener(listener)

      setContentWithMotionPhotoState(
        player,
        initialShowMotion = true,
        initialConfig = MotionPhotoConfig(rangeEnd = Boundary.END_OF_VIDEO.minusMs(100L)),
      )

      verify(listener, never()).onPlayWhenReadyChanged(eq(true), anyInt())
    }

  @Test
  fun rememberMotionPhotoState_whenSynchronousPrepareUpdatesClippingAfterInitialReplace_seeksOnce() =
    runComposeUiTest {
      val initiallyClippedItem =
        testMediaItem
          .buildUpon()
          .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs(100L).build()
          )
          .build()
      val fakePlayer =
        createSeekableFakePlayer(
          durationMs = 800L,
          mediaItem = initiallyClippedItem,
          bufferingDelayMs = 10_000L,
        )
      val player =
        object : ForwardingPlayer(fakePlayer) {
          override fun prepare() {
            super.prepare()
            fakePlayer.setOriginalDuration("motion_photo", 1_000L)
          }
        }
      val seekPositionsMs = recordSeekPositionsMs(player)

      setContentWithMotionPhotoState(
        player,
        initialConfig = MotionPhotoConfig(rangeEnd = Boundary.END_OF_VIDEO.minusMs(100L)),
      )

      assertThat(seekPositionsMs).containsExactly(0L)
      assertThat(fakePlayer.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(0L)
      assertThat(fakePlayer.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(900L)
    }

  @Test
  fun rememberMotionPhotoState_whenDurationClearsEmptyRangeWhileShowingPhoto_doesNotSeek() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = C.TIME_UNSET, bufferingDelayMs = 10_000L)
      setContentWithMotionPhotoState(
        player,
        initialConfig = MotionPhotoConfig(rangeEnd = Boundary.END_OF_VIDEO.minusMs(100L)),
      )
      val seekPositionsMs = recordSeekPositionsMs(player)

      player.setOriginalDuration("motion_photo", 1_000L)
      waitForIdle()

      assertThat(seekPositionsMs).isEmpty()
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(900L)
    }

  @Test
  fun rememberMotionPhotoState_whenPlayToPhotoFallsBackWithoutMetadataWhileShowingPhoto_doesNotSeek() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = Tracks.EMPTY,
          bufferingDelayMs = 10_000L,
        )
      setContentWithMotionPhotoState(player, initialConfig = MotionPhotoConfig.PLAY_TO_PHOTO)
      val seekPositionsMs = recordSeekPositionsMs(player)

      player.setTracks(createTestTracks(selectedVideoTrackIndex = 0))
      waitForIdle()

      assertThat(seekPositionsMs).isEmpty()
    }

  @Test
  fun rememberMotionPhotoState_whenPlayToPhotoFallsBackAfterFirstFrameWhileShowingPhoto_doesNotSeek() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = Tracks.EMPTY,
          bufferingDelayMs = 10_000L,
        )
      setContentWithMotionPhotoState(player, initialConfig = MotionPhotoConfig.PLAY_TO_PHOTO)
      player.renderFirstFrame(true)
      waitForIdle()
      val seekPositionsMs = recordSeekPositionsMs(player)

      player.setTracks(createTestTracks(selectedVideoTrackIndex = 0))
      waitForIdle()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)

      assertThat(seekPositionsMs).isEmpty()
    }

  @Test
  fun rememberMotionPhotoState_whenEmptyRangeClearsAtEndedDuringDeferredRewind_seeksOnce() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTestTracks(selectedVideoTrackIndex = 0),
          bufferingDelayMs = 10_000L,
        )
      val state =
        setContentWithMotionPhotoState(
          player,
          initialShowMotion = true,
          initialConfig =
            MotionPhotoConfig(
              rangeEnd = Boundary.END_OF_VIDEO.minusMs(100L),
              transitionDurationMs = MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS,
            ),
        )
      renderFirstFrameAndCompleteTransition(player)
      mainClock.autoAdvance = false
      state.showStillPhoto()
      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 2)
      player.setOriginalDuration("motion_photo", C.TIME_UNSET)
      player.setPlaybackState(Player.STATE_ENDED)
      waitForIdle()
      val seekPositionsMs = recordSeekPositionsMs(player)

      player.setOriginalDuration("motion_photo", 1_000L)
      waitForIdle()

      assertThat(seekPositionsMs).isEmpty()

      advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS / 2)

      assertThat(seekPositionsMs).containsExactly(0L)
    }

  @Test
  fun rememberMotionPhotoState_whenEmptyRangeClearsAtEndedWhileShowingPhoto_seeksOnce() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTestTracks(selectedVideoTrackIndex = 0),
          bufferingDelayMs = 10_000L,
        )
      setContentWithMotionPhotoState(
        player,
        initialConfig = MotionPhotoConfig(rangeEnd = Boundary.END_OF_VIDEO.minusMs(100L)),
      )
      player.setOriginalDuration("motion_photo", C.TIME_UNSET)
      player.setPosition(400L)
      player.setPlaybackState(Player.STATE_ENDED)
      waitForIdle()
      val seekPositionsMs = recordSeekPositionsMs(player)

      player.setOriginalDuration("motion_photo", 1_000L)
      waitForIdle()

      assertThat(seekPositionsMs).containsExactly(0L)
    }

  @Test
  fun playMotion_afterStopAwayFromStart_preparesAndSeeksOnceToStart() = runComposeUiTest {
    val player =
      createSeekableFakePlayer(
        durationMs = 1_000L,
        tracks = createTestTracks(selectedVideoTrackIndex = 0),
      )
    val state = setContentWithPlayingMotionPhotoState(player)
    player.setPosition(400L)
    player.stop()
    waitForIdle()
    val seekPositionsMs = recordSeekPositionsMs(player)

    state.playMotion()
    waitForIdle()

    assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
    assertThat(seekPositionsMs).containsExactly(0L)
  }

  @Test
  fun playMotion_afterStopAwayFromStartWithResumeTrue_preparesWithoutSeeking() = runComposeUiTest {
    val player =
      createSeekableFakePlayer(
        durationMs = 1_000L,
        tracks = createTestTracks(selectedVideoTrackIndex = 0),
      )
    val state =
      setContentWithPlayingMotionPhotoState(
        player,
        initialConfig = MotionPhotoConfig(resumeVideoFromPreviousPosition = true),
      )
    player.setPosition(400L)
    player.stop()
    waitForIdle()
    val seekPositionsMs = recordSeekPositionsMs(player)

    state.playMotion()
    waitForIdle()

    assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
    assertThat(seekPositionsMs).isEmpty()
  }

  @Test
  fun playMotion_afterShowStillPhotoWithoutSeekCommand_seeksToStart() = runComposeUiTest {
    val fakePlayer =
      createSeekableFakePlayer(
        durationMs = 1_000L,
        tracks = createTestTracks(selectedVideoTrackIndex = 0),
      )
    var isSeekCommandAvailable = true
    val player =
      object : ForwardingPlayer(fakePlayer) {
        override fun isCommandAvailable(command: @Player.Command Int): Boolean =
          if (command == Player.COMMAND_SEEK_TO_DEFAULT_POSITION) {
            isSeekCommandAvailable
          } else {
            super.isCommandAvailable(command)
          }
      }
    val state = setContentWithMotionPhotoState(player, initialShowMotion = true)
    renderFirstFrameAndCompleteTransition(fakePlayer)
    fakePlayer.setPosition(400L)
    isSeekCommandAvailable = false
    mainClock.autoAdvance = false
    state.showStillPhoto()
    advanceTimeAndWait(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)
    isSeekCommandAvailable = true
    val seekPositionsMs = recordSeekPositionsMs(player)

    state.playMotion()
    waitForIdle()

    assertThat(seekPositionsMs).containsExactly(0L)
  }

  @Test
  fun config_whenEmptyRangeClearsWhileStoppedAwayFromStart_preparesAndSeeksOnce() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTestTracks(selectedVideoTrackIndex = 0),
        )
      val state = setContentWithPlayingMotionPhotoState(player)
      player.setPosition(400L)
      player.stop()
      waitForIdle()
      state.config = MotionPhotoConfig(rangeStart = Boundary.END_OF_VIDEO)
      val seekPositionsMs = recordSeekPositionsMs(player)

      state.config = MotionPhotoConfig.DEFAULT
      waitForIdle()

      assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
      assertThat(seekPositionsMs).containsExactly(0L)
    }

  @Test
  fun config_whenEmptyRangeClearsWhileStoppedShowingPhoto_doesNotSeek() = runComposeUiTest {
    val player =
      createSeekableFakePlayer(
        durationMs = 1_000L,
        tracks = createTestTracks(selectedVideoTrackIndex = 0),
      )
    val state = setContentWithMotionPhotoState(player)
    player.stop()
    waitForIdle()
    state.config = MotionPhotoConfig(rangeStart = Boundary.END_OF_VIDEO)
    val seekPositionsMs = recordSeekPositionsMs(player)

    state.config = MotionPhotoConfig.DEFAULT
    waitForIdle()

    assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
    assertThat(seekPositionsMs).isEmpty()
  }

  @Test
  fun config_whenEmptyRangeClearsWithStartChangeWhileStoppedAwayFromStart_preparesAndSeeksOnce() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTestTracks(selectedVideoTrackIndex = 0),
        )
      val state = setContentWithPlayingMotionPhotoState(player)
      player.setPosition(400L)
      player.stop()
      waitForIdle()
      state.config = MotionPhotoConfig(rangeStart = Boundary.END_OF_VIDEO)
      val seekPositionsMs = recordSeekPositionsMs(player)

      state.config = MotionPhotoConfig(rangeStart = Boundary.START_OF_VIDEO.plusMs(100L))
      waitForIdle()

      assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
      assertThat(seekPositionsMs).containsExactly(0L)
      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(100L)
    }

  @Test
  fun config_whenBoundaryChangesAndResumeDisabledWhileShowingPhoto_seeksOnce() = runComposeUiTest {
    val player = createSeekableFakePlayer(durationMs = 1_000L)
    val state =
      setContentWithMotionPhotoState(
        player,
        initialConfig = MotionPhotoConfig(resumeVideoFromPreviousPosition = true),
      )
    val seekPositionsMs = recordSeekPositionsMs(player)

    state.config =
      state.config.copy(
        rangeEnd = Boundary.END_OF_VIDEO.minusMs(100L),
        resumeVideoFromPreviousPosition = false,
      )
    waitForIdle()

    assertThat(seekPositionsMs).containsExactly(0L)
    assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(900L)
  }

  @Test
  fun rememberMotionPhotoState_withOverflowingPositiveStartOffset_leavesNothingToPlay() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 500_000L),
        )

      val state =
        setContentWithMotionPhotoState(
          player,
          initialShowMotion = true,
          initialConfig =
            MotionPhotoConfig(rangeStart = Boundary.PHOTO_FRAME.plusMs(Long.MAX_VALUE)),
        )

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun rememberMotionPhotoState_withTimeUnsetStartOffset_clampsToVideoStart() = runComposeUiTest {
    val player =
      createSeekableFakePlayer(
        durationMs = 1_000L,
        tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 0L),
      )

    setContentWithMotionPhotoState(
      player,
      initialShowMotion = true,
      initialConfig = MotionPhotoConfig(rangeStart = Boundary.PHOTO_FRAME.plusMs(C.TIME_UNSET)),
    )

    assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(0L)
    assertThat(player.playWhenReady).isTrue()
  }

  @Test
  fun rememberMotionPhotoState_withOverflowingPositiveEndOffset_clipsWithoutOverflow() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 500_000L),
        )

      setContentWithMotionPhotoState(
        player,
        initialShowMotion = true,
        initialConfig = MotionPhotoConfig(rangeEnd = Boundary.PHOTO_FRAME.plusMs(Long.MAX_VALUE)),
      )

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs)
        .isEqualTo(Long.MAX_VALUE)
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_withMaxPhotoPresentationTimestampEnd_clipsWithoutOverflow() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks =
            createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = Long.MAX_VALUE),
        )

      setContentWithMotionPhotoState(
        player,
        initialShowMotion = true,
        initialConfig = MotionPhotoConfig.PLAY_TO_PHOTO,
      )

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs)
        .isEqualTo(Long.MAX_VALUE)
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_withMinValueEndOffsetAtZeroPhotoFrame_leavesNothingToPlay() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 0L),
        )

      val state =
        setContentWithMotionPhotoState(
          player,
          initialShowMotion = true,
          initialConfig = MotionPhotoConfig(rangeEnd = Boundary.PHOTO_FRAME.plusMs(Long.MIN_VALUE)),
        )

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun rememberMotionPhotoState_withTimeUnsetEndOffsetAtZeroPhotoFrame_leavesNothingToPlay() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 0L),
        )

      val state =
        setContentWithMotionPhotoState(
          player,
          initialShowMotion = true,
          initialConfig = MotionPhotoConfig(rangeEnd = Boundary.PHOTO_FRAME.plusMs(C.TIME_UNSET)),
        )

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun rememberMotionPhotoState_negativeStartOfVideoOffset_clampsToZeroWithoutDuration() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = C.TIME_UNSET)

      setContentWithMotionPhotoState(
        player,
        initialShowMotion = true,
        initialConfig = MotionPhotoConfig(rangeStart = Boundary.START_OF_VIDEO.minusMs(500L)),
      )

      val clipping = player.currentMediaItem!!.clippingConfiguration
      assertThat(clipping.startPositionUs).isEqualTo(0L)
      assertThat(clipping.endPositionUs).isEqualTo(C.TIME_END_OF_SOURCE)
    }

  @Test
  fun rememberMotionPhotoState_loopFromPhotoFrame_startsFromPhotoFrameAndLoops() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
        )

      setContentWithMotionPhotoState(
        player = player,
        initialShowMotion = true,
        initialConfig = MotionPhotoConfig(loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME),
      )

      assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE)
      assertThat(player.currentPosition).isEqualTo(400L)
    }

  @Test
  fun rememberMotionPhotoState_loopFromPhotoFrameClippedRange_startsRelativeToRangeStart() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
        )

      setContentWithMotionPhotoState(
        player = player,
        initialShowMotion = true,
        initialConfig =
          MotionPhotoConfig(
            loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME,
            rangeStart = Boundary.START_OF_VIDEO.plusMs(100L),
            rangeEnd = Boundary.PHOTO_FRAME.plusMs(1L),
          ),
      )

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionUs)
        .isEqualTo(100_000L)
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs).isEqualTo(401_000L)
      assertThat(player.currentPosition).isEqualTo(300L)
    }

  @Test
  fun rememberMotionPhotoState_loopFromPhotoFramePlayToPhoto_startsFromRangeStart() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
        )
      player.setPosition(250L)

      setContentWithMotionPhotoState(
        player = player,
        initialShowMotion = true,
        initialConfig =
          MotionPhotoConfig.PLAY_TO_PHOTO.copy(
            loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME
          ),
      )

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs).isEqualTo(401_000L)
      assertThat(player.currentPosition).isEqualTo(0L)
    }

  @Test
  fun rememberMotionPhotoState_loopFromPhotoFrameOutsideRange_startsFromRangeStart() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
        )
      player.setPosition(250L)

      setContentWithMotionPhotoState(
        player = player,
        initialShowMotion = true,
        initialConfig =
          MotionPhotoConfig(
            loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME,
            rangeStart = Boundary.START_OF_VIDEO.plusMs(500L),
          ),
      )

      assertThat(player.currentPosition).isEqualTo(0L)
    }

  @Test
  fun rememberMotionPhotoState_loopFromPhotoFrameWithoutMetadata_startsFromRangeStart() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L, tracks = createTestTracks())
      player.setPosition(250L)

      setContentWithMotionPhotoState(
        player = player,
        initialShowMotion = true,
        initialConfig = MotionPhotoConfig(loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME),
      )

      assertThat(player.currentPosition).isEqualTo(0L)
    }

  @Test
  fun rememberMotionPhotoState_whenPhotoFrameBecomesKnownWhilePreparing_seeksOnceToPhotoFrame() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = Tracks.EMPTY,
          bufferingDelayMs = 10_000L,
        )
      setContentWithMotionPhotoState(
        player = player,
        initialShowMotion = true,
        initialConfig = MotionPhotoConfig(loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME),
      )
      val seekPositionsMs = recordSeekPositionsMs(player)

      player.setTracks(createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L))
      waitForIdle()

      assertThat(seekPositionsMs).containsExactly(400L)
      assertThat(player.currentPosition).isEqualTo(400L)
    }

  @Test
  fun rememberMotionPhotoState_whenTracksClearAfterPhotoFrameApplied_revertsOnlyWhenTracksResolve() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
          bufferingDelayMs = 10_000L,
        )
      setContentWithMotionPhotoState(
        player = player,
        initialShowMotion = true,
        initialConfig = MotionPhotoConfig(loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME),
      )
      val seekPositionsMs = recordSeekPositionsMs(player)

      player.setTracks(Tracks.EMPTY)
      waitForIdle()
      val seeksWhileEmpty = seekPositionsMs.toList()
      player.setTracks(createTestTracks(selectedVideoTrackIndex = 0))
      waitForIdle()

      assertThat(seeksWhileEmpty).isEmpty()
      assertThat(player.currentPosition).isEqualTo(0L)
    }

  @Test
  fun showStillPhoto_loopFromPhotoFrame_rewindsToPhotoFrameAfterTransition() = runComposeUiTest {
    val player =
      createSeekableFakePlayer(
        durationMs = 1_000L,
        tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
      )
    val state =
      setContentWithMotionPhotoState(
        player = player,
        initialShowMotion = true,
        initialConfig =
          MotionPhotoConfig(
            loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME,
            transitionDurationMs = 200L,
          ),
      )
    renderFirstFrameAndCompleteTransition(player, transitionDurationMs = 200L)
    mainClock.autoAdvance = false
    player.setPosition(750L)

    state.showStillPhoto()
    advanceTimeAndWait(100L)
    val positionMidTransitionMs = player.currentPosition
    advanceTimeAndWait(100L)

    assertThat(positionMidTransitionMs).isAtLeast(750L)
    assertThat(player.currentPosition).isEqualTo(400L)
  }

  @Test
  fun rememberMotionPhotoState_loopFromPhotoFrameWithLoopEmulation_wrapsToStartOfRange() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
        )
      player.removeCommands(Player.COMMAND_SET_REPEAT_MODE)
      setContentWithMotionPhotoState(
        player = player,
        initialShowMotion = true,
        initialConfig = MotionPhotoConfig(loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME),
      )
      renderFirstFrameAndCompleteTransition(player)
      player.setPosition(900L)

      player.setPlaybackState(Player.STATE_ENDED)
      waitForIdle()

      assertThat(player.currentPosition).isEqualTo(0L)
    }

  @Test
  fun rememberMotionPhotoState_whenSeekInCurrentItemBecomesAvailableLater_seeksToPhotoFrame() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
          isSeekable = false,
          throwWhenCommandUnavailable = true,
        )
      player.setPosition(250L)
      setContentWithMotionPhotoState(
        player = player,
        initialConfig = MotionPhotoConfig(loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME),
      )
      val positionBeforeSeekCommandMs = player.currentPosition

      player.addCommands(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
      waitForIdle()

      assertThat(positionBeforeSeekCommandMs).isEqualTo(0L)
      assertThat(player.currentPosition).isEqualTo(400L)
    }

  @Test
  fun rememberMotionPhotoState_withPreloadedPlayerAndResume_preservesPositionDespitePhotoFrame() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
        )
      player.prepare()
      player.setPosition(250L)

      setContentWithMotionPhotoState(
        player = player,
        initialShowMotion = true,
        initialConfig =
          MotionPhotoConfig(
            loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME,
            resumeVideoFromPreviousPosition = true,
          ),
      )

      assertThat(player.currentPosition).isEqualTo(250L)
    }

  @Test
  fun rememberMotionPhotoState_prepopulatedIdlePlayerWithResume_seeksToPhotoFrame() =
    runComposeUiTest {
      val itemWithId = testMediaItem.buildUpon().setMediaId("motion_photo").build()
      val fakePlayer = FakePlayer(bufferingDelayMs = 10_000L)
      val player = asSeekablePlayer(fakePlayer)
      fakePlayer.setMediaItem(itemWithId)

      setContentWithMotionPhotoState(
        player = player,
        mediaItem = itemWithId,
        initialShowMotion = true,
        initialConfig =
          MotionPhotoConfig(
            loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME,
            resumeVideoFromPreviousPosition = true,
            rangeEnd = Boundary.START_OF_VIDEO.plusMs(800L),
          ),
      )
      fakePlayer.setOriginalDuration("motion_photo", 1_000L)
      fakePlayer.setTracks(
        createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L)
      )
      waitForIdle()

      assertThat(fakePlayer.currentPosition).isEqualTo(400L)
    }

  @Test
  fun config_rangeStartChangedWhileStoppedWithResume_seeksToPhotoFrameOnPrepare() =
    runComposeUiTest {
      val itemWithId = testMediaItem.buildUpon().setMediaId("motion_photo").build()
      val fakePlayer = FakePlayer(bufferingDelayMs = 0)
      val player = asSeekablePlayer(fakePlayer)
      val state =
        setContentWithMotionPhotoState(
          player = player,
          mediaItem = itemWithId,
          initialShowMotion = true,
          initialConfig =
            MotionPhotoConfig(
              loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME,
              resumeVideoFromPreviousPosition = true,
            ),
        )
      fakePlayer.setOriginalDuration("motion_photo", 1_000L)
      fakePlayer.setTracks(
        createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L)
      )
      waitForIdle()
      renderFirstFrameAndCompleteTransition(fakePlayer)
      fakePlayer.stop()
      fakePlayer.setTracks(Tracks.EMPTY)
      waitForIdle()

      state.config = state.config.copy(rangeStart = Boundary.START_OF_VIDEO.plusMs(100L))
      waitForIdle()
      fakePlayer.prepare()
      fakePlayer.setTracks(
        createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L)
      )
      waitForIdle()

      assertThat(fakePlayer.currentPosition).isEqualTo(300L)
    }

  @Test
  fun config_endBoundaryUpdatedBeforeFirstFrameWithLoopFromPhotoFrame_seeksToPhotoFrame() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
          bufferingDelayMs = 10_000L,
        )
      val state =
        setContentWithMotionPhotoState(
          player = player,
          initialShowMotion = true,
          initialConfig =
            MotionPhotoConfig.PLAY_TO_PHOTO.copy(
              loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME
            ),
        )

      state.config = state.config.copy(rangeEnd = MotionPhotoConfig.Boundary.END_OF_VIDEO)
      waitForIdle()

      assertThat(player.currentPosition).isEqualTo(400L)
    }

  @Test
  fun config_exitPlayToPhotoWithoutChangeMediaItems_doesNotSeekToClipEnd() = runComposeUiTest {
    val player =
      createSeekableFakePlayer(
        durationMs = 1_000L,
        tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
        bufferingDelayMs = 10_000L,
      )
    val state =
      setContentWithMotionPhotoState(
        player = player,
        initialShowMotion = true,
        initialConfig =
          MotionPhotoConfig.PLAY_TO_PHOTO.copy(
            loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME
          ),
      )
    player.removeCommands(Player.COMMAND_CHANGE_MEDIA_ITEMS)

    state.config = state.config.copy(rangeEnd = MotionPhotoConfig.Boundary.END_OF_VIDEO)
    waitForIdle()

    assertThat(player.currentPosition).isEqualTo(0L)
  }

  @Test
  fun config_exitTemporaryEmptyRangeAfterAdvancing_rewindsToPhotoFrame() = runComposeUiTest {
    val player =
      createSeekableFakePlayer(
        durationMs = 1_000L,
        tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
      )
    val state =
      setContentWithMotionPhotoState(
        player = player,
        initialShowMotion = true,
        initialConfig = MotionPhotoConfig(loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME),
      )
    renderFirstFrameAndCompleteTransition(player)
    player.setPosition(750L)

    state.config = state.config.copy(rangeEnd = MotionPhotoConfig.Boundary.START_OF_VIDEO)
    waitForIdle()
    state.config = state.config.copy(rangeEnd = MotionPhotoConfig.Boundary.END_OF_VIDEO)
    waitForIdle()

    assertThat(player.currentPosition).isEqualTo(400L)
  }

  @Test
  fun rememberMotionPhotoState_whenMetadataRemovedAfterPhotoFrameApplied_revertsToStartOfRange() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
          bufferingDelayMs = 10_000L,
        )
      setContentWithMotionPhotoState(
        player = player,
        initialShowMotion = true,
        initialConfig = MotionPhotoConfig(loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME),
      )

      player.setTracks(createTestTracks(selectedVideoTrackIndex = 0))
      waitForIdle()

      assertThat(player.currentPosition).isEqualTo(0L)
    }

  @Test
  fun rememberMotionPhotoState_revertWithoutSeekToDefaultCommand_retriesWhenCommandAvailable() =
    runComposeUiTest {
      val fakePlayer =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
          bufferingDelayMs = 10_000L,
        )
      var canSeekToDefault = true
      val player =
        object : ForwardingPlayer(fakePlayer) {
          override fun isCommandAvailable(command: @Player.Command Int): Boolean =
            if (command == Player.COMMAND_SEEK_TO_DEFAULT_POSITION) {
              canSeekToDefault && super.isCommandAvailable(command)
            } else {
              super.isCommandAvailable(command)
            }
        }
      setContentWithMotionPhotoState(
        player = player,
        initialShowMotion = true,
        initialConfig = MotionPhotoConfig(loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME),
      )
      canSeekToDefault = false
      fakePlayer.removeCommands(Player.COMMAND_SET_VOLUME)
      fakePlayer.setTracks(createTestTracks(selectedVideoTrackIndex = 0))
      waitForIdle()
      val positionBeforeCommandMs = fakePlayer.currentPosition

      canSeekToDefault = true
      fakePlayer.addCommands(Player.COMMAND_SET_VOLUME)
      waitForIdle()

      assertThat(positionBeforeCommandMs).isEqualTo(400L)
      assertThat(fakePlayer.currentPosition).isEqualTo(0L)
    }

  @Test
  fun rememberMotionPhotoState_stoppedAndPrepared_rewindsToExpectedStart() = runComposeUiTest {
    val player =
      createSeekableFakePlayer(
        durationMs = 1_000L,
        tracks = createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L),
      )
    val state =
      setContentWithMotionPhotoState(
        player = player,
        initialShowMotion = true,
        initialConfig = MotionPhotoConfig(loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME),
      )
    renderFirstFrameAndCompleteTransition(player)
    player.setPosition(800L)

    player.stop()
    player.setTracks(Tracks.EMPTY)
    waitForIdle()
    player.prepare()
    player.setTracks(createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L))
    waitForIdle()
    val positionAfterPhotoFramePrepareMs = player.currentPosition
    state.config = state.config.copy(loopMode = MotionPhotoConfig.LOOP_MODE_FROM_RANGE_START)
    player.setPosition(800L)
    player.stop()
    waitForIdle()
    player.prepare()
    waitForIdle()

    assertThat(positionAfterPhotoFramePrepareMs).isEqualTo(400L)
    assertThat(player.currentPosition).isEqualTo(0L)
  }

  @Test
  fun rememberMotionPhotoState_firstFrameBeforePhotoFrameSeekReady_holdsPhotoAndCrossfade() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = Tracks.EMPTY,
          bufferingDelayMs = 10_000L,
        )
      val state =
        setContentWithMotionPhotoState(
          player = player,
          initialShowMotion = true,
          initialConfig =
            MotionPhotoConfig(
              loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME,
              transitionDurationMs = 200L,
            ),
        )
      mainClock.autoAdvance = false

      player.setTracks(createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L))
      player.renderFirstFrame(true)
      waitForIdle()
      val isPhotoVisibleWhileBuffering = state.visualState.isPhotoVisible
      val playWhenReadyWhileBuffering = player.playWhenReady
      player.setPlaybackState(Player.STATE_READY)
      waitForIdle()
      val isPhotoVisibleOnReady = state.visualState.isPhotoVisible
      val playWhenReadyBeforeCrossfade = player.playWhenReady
      advanceTimeAndWait(200L)

      assertThat(isPhotoVisibleWhileBuffering).isTrue()
      assertThat(playWhenReadyWhileBuffering).isFalse()
      assertThat(isPhotoVisibleOnReady).isFalse()
      assertThat(playWhenReadyBeforeCrossfade).isFalse()
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun playMotion_afterFirstFrameRenderedAndPhotoFrameSeekBuffering_keepsPhotoUntilReady() =
    runComposeUiTest {
      val player =
        createSeekableFakePlayer(
          durationMs = 1_000L,
          tracks = Tracks.EMPTY,
          bufferingDelayMs = 10_000L,
        )
      val state =
        setContentWithMotionPhotoState(
          player = player,
          initialShowMotion = false,
          initialConfig =
            MotionPhotoConfig(
              loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME,
              transitionDurationMs = 0L,
            ),
        )
      player.setPlaybackState(Player.STATE_READY)
      player.renderFirstFrame(true)
      waitForIdle()

      player.setTracks(createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs = 400_000L))
      waitForIdle()
      state.playMotion()
      waitForIdle()
      val isPhotoVisibleWhileBuffering = state.visualState.isPhotoVisible
      val playWhenReadyWhileBuffering = player.playWhenReady
      player.setPlaybackState(Player.STATE_READY)
      waitForIdle()

      assertThat(isPhotoVisibleWhileBuffering).isTrue()
      assertThat(playWhenReadyWhileBuffering).isFalse()
      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_positiveEndOfVideoOffset_clampsToEndOfSource() = runComposeUiTest {
    val player = createSeekableFakePlayer(durationMs = C.TIME_UNSET)

    setContentWithMotionPhotoState(
      player,
      initialShowMotion = true,
      initialConfig = MotionPhotoConfig(rangeEnd = Boundary.END_OF_VIDEO.plusMs(500L)),
    )

    val clipping = player.currentMediaItem!!.clippingConfiguration
    assertThat(clipping.startPositionUs).isEqualTo(0L)
    assertThat(clipping.endPositionUs).isEqualTo(C.TIME_END_OF_SOURCE)
  }

  @Test
  fun rememberMotionPhotoState_positiveEndOfVideoStart_leavesNothingToPlay() = runComposeUiTest {
    val player = createSeekableFakePlayer(durationMs = 1_000L)

    val state =
      setContentWithMotionPhotoState(
        player,
        initialShowMotion = true,
        initialConfig = MotionPhotoConfig(rangeStart = Boundary.END_OF_VIDEO.plusMs(500L)),
      )

    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playWhenReady).isFalse()
  }

  @Test
  fun rememberMotionPhotoState_negativeStartOfVideoEnd_leavesNothingToPlay() = runComposeUiTest {
    val player = createSeekableFakePlayer(durationMs = 1_000L)

    val state =
      setContentWithMotionPhotoState(
        player,
        initialShowMotion = true,
        initialConfig = MotionPhotoConfig(rangeEnd = Boundary.START_OF_VIDEO.minusMs(500L)),
      )

    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playWhenReady).isFalse()
  }

  @Test
  fun motionPhotoConfig_withInvalidLoopMode_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException::class.java) { MotionPhotoConfig(loopMode = -1) }
    assertThrows(IllegalArgumentException::class.java) { MotionPhotoConfig(loopMode = 3) }
  }

  @Test
  @SuppressLint("Range")
  fun motionPhotoConfig_withNegativeTransitionDuration_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException::class.java) {
      MotionPhotoConfig(transitionDurationMs = -1L)
    }
  }

  @Test
  fun boundary_presets_haveSelfAsOriginAndZeroOffset() {
    assertThat(Boundary.START_OF_VIDEO.origin).isEqualTo(Boundary.START_OF_VIDEO)
    assertThat(Boundary.START_OF_VIDEO.offsetMs).isEqualTo(0L)
    assertThat(Boundary.PHOTO_FRAME.origin).isEqualTo(Boundary.PHOTO_FRAME)
    assertThat(Boundary.PHOTO_FRAME.offsetMs).isEqualTo(0L)
    assertThat(Boundary.END_OF_VIDEO.origin).isEqualTo(Boundary.END_OF_VIDEO)
    assertThat(Boundary.END_OF_VIDEO.offsetMs).isEqualTo(0L)
  }

  @Test
  fun plusMs_keepsOriginAndShiftsOffset() {
    val boundary = Boundary.START_OF_VIDEO.plusMs(500L).minusMs(200L)

    assertThat(boundary.origin).isEqualTo(Boundary.START_OF_VIDEO)
    assertThat(boundary.offsetMs).isEqualTo(300L)
  }

  @Test
  fun minusMs_matchesPlusMsWithNegatedDuration() {
    val photoFrame = Boundary.PHOTO_FRAME

    assertThat(photoFrame.minusMs(250L)).isEqualTo(photoFrame.plusMs(-250L))
    assertThat(photoFrame.minusMs(-250L)).isEqualTo(photoFrame.plusMs(250L))
  }

  @Test
  fun plusMsAndMinusMs_zeroDuration_equalsOriginal() {
    val boundary = Boundary.PHOTO_FRAME.minusMs(250L)

    assertThat(boundary.plusMs(0L)).isEqualTo(boundary)
    assertThat(boundary.minusMs(0L)).isEqualTo(boundary)
  }

  @Test
  fun boundary_equalsAndHashCode_followValueSemantics() {
    val endOffset = Boundary.END_OF_VIDEO.minusMs(250L)
    val equalEndOffset = Boundary.END_OF_VIDEO.minusMs(250L)
    val photoNegative = Boundary.PHOTO_FRAME.minusMs(250L)
    val photoDifferentOffset = Boundary.PHOTO_FRAME.minusMs(100L)
    val photoPositive = Boundary.PHOTO_FRAME.plusMs(250L)
    val startPositive = Boundary.START_OF_VIDEO.plusMs(250L)

    assertThat(endOffset).isEqualTo(equalEndOffset)
    assertThat(endOffset.hashCode()).isEqualTo(equalEndOffset.hashCode())
    assertThat(photoNegative).isNotEqualTo(photoDifferentOffset)
    assertThat(Boundary.PHOTO_FRAME).isNotEqualTo(Boundary.START_OF_VIDEO)
    assertThat(startPositive).isNotEqualTo(photoPositive)
    assertThat(photoNegative).isNotEqualTo(endOffset)
    assertThat(photoNegative).isNotEqualTo(null)
    assertThat(photoNegative).isNotEqualTo(Any())
  }

  @Test
  fun motionPhotoConfig_copyAndPresets_hasExpectedPropertiesAndEquality() {
    val defaultConfig = MotionPhotoConfig.DEFAULT
    val loopConfig = MotionPhotoConfig.LOOP
    val playToPhotoConfig = MotionPhotoConfig.PLAY_TO_PHOTO
    val customConfig =
      defaultConfig.copy(
        loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME,
        transitionDurationMs = 350L,
        resumeVideoFromPreviousPosition = true,
        rangeStart = Boundary.PHOTO_FRAME.minusMs(250L),
        rangeEnd = Boundary.START_OF_VIDEO.plusMs(600L),
      )
    val equalCustomConfig =
      MotionPhotoConfig(
        loopMode = MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME,
        transitionDurationMs = 350L,
        resumeVideoFromPreviousPosition = true,
        rangeStart = Boundary.PHOTO_FRAME.minusMs(250L),
        rangeEnd = Boundary.START_OF_VIDEO.plusMs(600L),
      )
    val differentStartConfig = customConfig.copy(rangeStart = Boundary.PHOTO_FRAME.minusMs(100L))
    val differentEndConfig = customConfig.copy(rangeEnd = Boundary.START_OF_VIDEO.plusMs(500L))
    val differentLoopModeConfig =
      customConfig.copy(loopMode = MotionPhotoConfig.LOOP_MODE_FROM_RANGE_START)

    assertThat(defaultConfig.loopMode).isEqualTo(MotionPhotoConfig.LOOP_MODE_OFF)
    assertThat(defaultConfig.transitionDurationMs).isEqualTo(200L)
    assertThat(defaultConfig.resumeVideoFromPreviousPosition).isFalse()
    assertThat(defaultConfig.rangeStart).isEqualTo(Boundary.START_OF_VIDEO)
    assertThat(defaultConfig.rangeEnd).isEqualTo(Boundary.END_OF_VIDEO)
    assertThat(defaultConfig).isEqualTo(MotionPhotoConfig())
    assertThat(defaultConfig.hashCode()).isEqualTo(MotionPhotoConfig().hashCode())
    assertThat(loopConfig)
      .isEqualTo(defaultConfig.copy(loopMode = MotionPhotoConfig.LOOP_MODE_FROM_RANGE_START))
    assertThat(loopConfig).isNotEqualTo(defaultConfig)
    assertThat(loopConfig.copy(loopMode = MotionPhotoConfig.LOOP_MODE_OFF)).isEqualTo(defaultConfig)
    assertThat(playToPhotoConfig).isEqualTo(defaultConfig.copy(rangeEnd = Boundary.PHOTO_FRAME))
    assertThat(playToPhotoConfig).isNotEqualTo(defaultConfig)
    assertThat(customConfig.loopMode).isEqualTo(MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME)
    assertThat(customConfig.transitionDurationMs).isEqualTo(350L)
    assertThat(customConfig.resumeVideoFromPreviousPosition).isTrue()
    assertThat(customConfig.rangeStart).isEqualTo(Boundary.PHOTO_FRAME.minusMs(250L))
    assertThat(customConfig.rangeEnd).isEqualTo(Boundary.START_OF_VIDEO.plusMs(600L))
    assertThat(customConfig).isEqualTo(equalCustomConfig)
    assertThat(customConfig.hashCode()).isEqualTo(equalCustomConfig.hashCode())
    assertThat(customConfig).isNotEqualTo(defaultConfig)
    assertThat(customConfig).isNotEqualTo(differentStartConfig)
    assertThat(customConfig).isNotEqualTo(differentEndConfig)
    assertThat(customConfig.hashCode()).isNotEqualTo(differentEndConfig.hashCode())
    assertThat(customConfig).isNotEqualTo(differentLoopModeConfig)
    assertThat(customConfig.hashCode()).isNotEqualTo(differentLoopModeConfig.hashCode())
  }

  @Test
  fun observe_nullPlayer_suspendsUntilCancelled() = runTest {
    val state = MotionPhotoState(testMediaItem, coroutineScope = backgroundScope)

    val job = backgroundScope.launch { state.observe() }
    testScheduler.runCurrent()

    assertThat(job.isActive).isTrue()

    job.cancel()
    testScheduler.runCurrent()

    assertThat(job.isCompleted).isTrue()
  }

  @CanIgnoreReturnValue
  private fun ComposeUiTest.setContentWithMotionPhotoState(
    player: Player?,
    mediaItem: MediaItem = testMediaItem,
    initialShowMotion: Boolean = false,
    initialConfig: MotionPhotoConfig = MotionPhotoConfig.DEFAULT,
  ): MotionPhotoState {
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = mediaItem,
          initialShowMotion = initialShowMotion,
          initialConfig = initialConfig,
        )
    }
    waitForIdle()
    return state
  }

  /**
   * Sets content with `initialShowMotion = true` and renders the first frame so motion playback is
   * active (assumes [player] prepares synchronously, e.g. `bufferingDelayMs = 0`).
   */
  private fun ComposeUiTest.setContentWithPlayingMotionPhotoState(
    player: FakePlayer,
    mediaItem: MediaItem = testMediaItem,
    initialConfig: MotionPhotoConfig = MotionPhotoConfig.DEFAULT,
  ): MotionPhotoState {
    val state =
      setContentWithMotionPhotoState(
        player = player,
        mediaItem = mediaItem,
        initialShowMotion = true,
        initialConfig = initialConfig,
      )
    player.renderFirstFrame(true)
    waitForIdle()
    check(player.playWhenReady)
    return state
  }

  private fun ComposeUiTest.advanceTimeAndWait(durationMs: Long) {
    mainClock.advanceTimeBy(durationMs)
    waitForIdle()
  }

  private fun playbackError(message: String): PlaybackException =
    PlaybackException(message, /* cause= */ null, PlaybackException.ERROR_CODE_UNSPECIFIED)

  private fun createSeekableFakePlayer(
    durationMs: Long = 1_000L,
    mediaItem: MediaItem = testMediaItem,
    tracks: Tracks = Tracks.EMPTY,
    bufferingDelayMs: Long = 0L,
    isSeekable: Boolean = true,
    throwWhenCommandUnavailable: Boolean = false,
  ): FakePlayer =
    FakePlayer(
        bufferingDelayMs = bufferingDelayMs,
        throwWhenCommandUnavailable = throwWhenCommandUnavailable,
        playlist =
          listOf(
            MediaItemData.Builder("motion_photo")
              .setMediaItem(mediaItem)
              .setDurationUs(msToUs(durationMs))
              .setIsSeekable(isSeekable)
              .setTracks(tracks)
              .build()
          ),
      )
      .apply {
        if (durationMs != C.TIME_UNSET) {
          setOriginalDuration("motion_photo", durationMs)
        }
      }

  private fun asSeekablePlayer(fakePlayer: FakePlayer): Player =
    object : ForwardingPlayer(fakePlayer) {
      override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands()
          .buildUpon()
          .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
          .build()

      override fun isCommandAvailable(command: @Player.Command Int): Boolean =
        command == Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM || super.isCommandAvailable(command)

      override fun seekTo(positionMs: Long) {
        fakePlayer.setPosition(positionMs)
      }
    }

  /**
   * Renders the first frame on [player] and lets the crossfade from the still photo complete, after
   * which motion playback starts.
   */
  private fun ComposeUiTest.renderFirstFrameAndCompleteTransition(
    player: FakePlayer,
    transitionDurationMs: Long = MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS,
  ) {
    player.renderFirstFrame(true)
    waitForIdle()
    advanceTimeAndWait(transitionDurationMs)
  }

  /**
   * Creates tracks shaped like the ones ExoPlayer reports for the output of the JPEG motion photo
   * extractor: an unselected image track carrying the [MotionPhotoMetadata], which no renderer
   * supports as it has no sample MIME type, next to the selected video track.
   */
  private fun createTracksWithMotionPhotoMetadata(photoPresentationTimestampUs: Long): Tracks =
    Tracks(
      listOf(
        Tracks.Group(
          TrackGroup(
            Format.Builder()
              .setContainerMimeType(MimeTypes.IMAGE_JPEG)
              .setMetadata(
                Metadata(
                  MotionPhotoMetadata(
                    /* photoStartPosition= */ 0L,
                    /* photoSize= */ 1_000L,
                    /* photoPresentationTimestampUs= */ photoPresentationTimestampUs,
                    /* videoStartPosition= */ 1_000L,
                    /* videoSize= */ 5_000L,
                  )
                )
              )
              .build()
          ),
          /* adaptiveSupported= */ false,
          intArrayOf(C.FORMAT_UNSUPPORTED_TYPE),
          booleanArrayOf(false),
        ),
        Tracks.Group(
          TrackGroup(createVideoFormat(1920, 1080)),
          /* adaptiveSupported= */ false,
          intArrayOf(C.FORMAT_HANDLED),
          booleanArrayOf(true),
        ),
      )
    )

  /**
   * Returns a list that records the clipping end position whenever [player]'s current media item
   * changes.
   */
  private fun recordSetClippingEndPositionsUs(player: FakePlayer): List<Long?> {
    val endPositionsUs = mutableListOf<Long?>()
    var previousMediaItem = player.currentMediaItem
    player.addListener(
      object : Player.Listener {
        override fun onTimelineChanged(
          timeline: Timeline,
          reason: @Player.TimelineChangeReason Int,
        ) {
          val currentMediaItem = player.currentMediaItem
          if (currentMediaItem !== previousMediaItem) {
            previousMediaItem = currentMediaItem
            endPositionsUs.add(currentMediaItem?.clippingConfiguration?.endPositionUs)
          }
        }
      }
    )
    return endPositionsUs
  }

  /**
   * Returns a list that records the new position in milliseconds whenever [player] reports a seek
   * discontinuity.
   */
  private fun recordSeekPositionsMs(player: Player): List<Long> {
    val seekPositionsMs = mutableListOf<Long>()
    player.addListener(
      object : Player.Listener {
        override fun onPositionDiscontinuity(
          oldPosition: Player.PositionInfo,
          newPosition: Player.PositionInfo,
          reason: @Player.DiscontinuityReason Int,
        ) {
          if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            seekPositionsMs.add(newPosition.positionMs)
          }
        }
      }
    )
    return seekPositionsMs
  }
}
