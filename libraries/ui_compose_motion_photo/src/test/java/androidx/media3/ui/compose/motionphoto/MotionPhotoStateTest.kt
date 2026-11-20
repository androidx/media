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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper

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
            initialShowMotion = true,
          )
      }
      waitForIdle()

      assertThat(player.currentMediaItem).isSameInstanceAs(existingMediaItem)
      assertThat(player.playWhenReady).isFalse()

      state.showStillPhoto()
      waitForIdle()
      assertThat(player.playWhenReady).isFalse()

      state.playMotion()
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
      waitForIdle()
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
      lateinit var state: MotionPhotoState

      setContent { state = rememberMotionPhotoState(player = null, mediaItem = testMediaItem) }
      waitForIdle()

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
      lateinit var state: MotionPhotoState
      setContent { state = rememberMotionPhotoState(player = player, mediaItem = testMediaItem) }
      waitForIdle()

      state.playMotion()
      assertThat(player.playWhenReady).isTrue()
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isTrue()

      state.showStillPhoto()
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      state.playMotion()
      waitForIdle()
      assertThat(state.visualState.isPhotoVisible).isFalse()
      mainClock.advanceTimeBy(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)
      waitForIdle()
      assertThat(player.playWhenReady).isTrue()

      player.setPlaybackState(Player.STATE_ENDED)
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun toggle_togglesBetweenMotionAndStillPhotoAndReplaysAfterEnded() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    lateinit var state: MotionPhotoState
    setContent { state = rememberMotionPhotoState(player = player, mediaItem = testMediaItem) }
    waitForIdle()

    // Initial toggle starts motion immediately before first frame renders.
    state.toggle()
    assertThat(player.playWhenReady).isTrue()
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isFalse()
    assertThat(player.playWhenReady).isTrue()

    // Toggling while motion is playing returns to the still photo.
    state.toggle()
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playWhenReady).isFalse()

    // Toggling again resumes motion; reaching STATE_ENDED resets the active motion state so the
    // next toggle replays motion from the start.
    state.toggle()
    waitForIdle()
    player.setPlaybackState(Player.STATE_ENDED)
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playWhenReady).isFalse()

    state.toggle()
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isFalse()
    assertThat(player.playWhenReady).isFalse()
    mainClock.advanceTimeBy(200L)
    waitForIdle()
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
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isTrue()

    // Toggling off before a player attaches cancels the queued motion request.
    state.toggle()
    waitForIdle()

    val player1 = FakePlayer(bufferingDelayMs = 0)
    currentPlayer = player1
    waitForIdle()

    assertThat(player1.playWhenReady).isFalse()

    // Toggling on while detached queues motion to start once a player attaches.
    currentPlayer = null
    waitForIdle()
    state.toggle()
    waitForIdle()

    val player2 = FakePlayer(bufferingDelayMs = 0)
    currentPlayer = player2
    waitForIdle()

    assertThat(player2.playWhenReady).isTrue()
  }

  @Test
  fun showStillPhoto_whenAlreadyShowingStillPhoto_doesNotSyncOrSeek() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    lateinit var state: MotionPhotoState
    setContent { state = rememberMotionPhotoState(player = player, mediaItem = testMediaItem) }
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

    state.showStillPhoto()
    waitForIdle()

    assertThat(player.playWhenReady).isFalse()
    assertThat(player.currentPosition).isEqualTo(300L)
    assertThat(seekCount).isEqualTo(0)
  }

  @Test
  fun playMotion_whenAlreadyPlayingMotion_doesNotSeek() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowMotion = true,
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()
    mainClock.autoAdvance = false
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

    state.playMotion()
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isFalse()
    assertThat(player.playWhenReady).isTrue()
    assertThat(player.currentPosition).isAtLeast(300L)
    assertThat(seekCount).isEqualTo(0)
  }

  @Test
  fun showStillPhoto_duringDeferredRewindAfterEnded_forcesPhotoWithoutRestartingRewind() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      player.setPosition(300L)
      player.setPlaybackState(Player.STATE_ENDED)
      waitForIdle()
      mainClock.advanceTimeBy(100L)
      waitForIdle()

      state.showStillPhoto()
      mainClock.advanceTimeBy(100L)
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()
      assertThat(player.currentPosition).isEqualTo(0L)

      // Switching to looping afterward keeps playback paused, confirming showStillPhoto() forced
      // the still photo rather than leaving only the ended state.
      state.config = MotionPhotoConfig.LOOP
      waitForIdle()

      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun config_whenLoopEnabledWhileStillPhotoRequested_keepsStillPhoto() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    lateinit var state: MotionPhotoState
    setContent { state = rememberMotionPhotoState(player = player, mediaItem = testMediaItem) }
    waitForIdle()

    state.config = MotionPhotoConfig.LOOP
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE)
    assertThat(player.playWhenReady).isFalse()
  }

  @Test
  fun showStillPhoto_afterRenderingFirstFrame_pausesImmediatelyAndRewindsAfterTransitionDuration() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      player.setPosition(300L)

      state.showStillPhoto()
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
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
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

      state.showStillPhoto()
      mainClock.advanceTimeBy(200L)
      waitForIdle()
      assertThat(player.currentPosition).isEqualTo(100L)
      assertThat(seekCount).isEqualTo(1)

      state.playMotion()
      waitForIdle()

      assertThat(player.currentPosition).isAtLeast(100L)
      assertThat(seekCount).isEqualTo(1)
    }

  @Test
  fun rememberMotionPhotoState_whenPlaybackFails_fallsBackToPhoto() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowMotion = true,
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()
    assertThat(state.visualState.isPhotoVisible).isFalse()

    player.setPlayerError(
      PlaybackException("Test error", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
    )
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
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowMotion = true,
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()

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
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowMotion = true,
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()

    state.toggle()
    waitForIdle()

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
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowMotion = true,
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
            initialShowMotion = true,
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
      player1.setPlayerError(
        PlaybackException("Detached error", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
      )
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player2.playWhenReady).isTrue()

      // Error on active player2 falls back to still photo.
      player2.setPlayerError(
        PlaybackException("Active error", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
      )
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isTrue()
    }

  @Test
  fun showStillPhoto_afterPlayerError_doesNotPrepareOrClearError() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowMotion = true,
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()
    player.setPlayerError(
      PlaybackException("Test error", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
    )
    waitForIdle()

    state.showStillPhoto()
    waitForIdle()

    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
    assertThat(player.playerError).isNotNull()
    assertThat(player.playWhenReady).isFalse()
  }

  @Test
  fun playMotion_afterPlayerError_repreparesPlayerAndRecovers() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowMotion = true,
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()
    player.setPlayerError(
      PlaybackException("Test error", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
    )
    waitForIdle()

    state.playMotion()
    waitForIdle()
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
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = false,
          )
      }
      waitForIdle()
      ShadowLooper.idleMainLooper(1_000L, TimeUnit.MILLISECONDS)
      waitForIdle()
      player.setPlayerError(
        PlaybackException("Test error", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
      )
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

      mainClock.advanceTimeBy(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)
      waitForIdle()

      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun toggle_afterPlayerError_repreparesPlayerAndRecoversOnFirstToggle() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowMotion = true,
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()
    player.setPlayerError(
      PlaybackException("Test error", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
    )
    waitForIdle()

    state.toggle()
    waitForIdle()
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
      player.setPlayerError(
        PlaybackException("Preexisting error", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
      )
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
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
            initialShowMotion = true,
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
            initialShowMotion = true,
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
      state.showStillPhoto()
      waitForIdle()
      assertThat(state.visualState.isPhotoVisible).isTrue()

      state.playMotion()
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
          initialShowMotion = true,
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
            initialShowMotion = true,
            initialConfig = MotionPhotoConfig.LOOP,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
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
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowMotion = true,
        )
    }
    waitForIdle()
    assertThat(state.config).isEqualTo(MotionPhotoConfig.DEFAULT)
    assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_OFF)

    state.config = MotionPhotoConfig.LOOP
    waitForIdle()

    assertThat(state.config).isEqualTo(MotionPhotoConfig.LOOP)
    assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE)
    assertThat(player.playWhenReady).isTrue()

    state.config = state.config.copy(isLooping = false)
    waitForIdle()

    assertThat(state.config).isEqualTo(MotionPhotoConfig.DEFAULT)
    assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_OFF)
    assertThat(player.playWhenReady).isTrue()
  }

  @Test
  fun config_whenLoopEnabledAfterMotionEnded_restartsMotion() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowMotion = true,
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()
    player.setPosition(300L)
    player.setPlaybackState(Player.STATE_ENDED)
    waitForIdle()
    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playWhenReady).isFalse()

    state.config = MotionPhotoConfig.LOOP
    waitForIdle()

    assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE)
    assertThat(player.currentPosition).isEqualTo(0L)
    mainClock.advanceTimeBy(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)
    waitForIdle()
    assertThat(player.playWhenReady).isTrue()
  }

  @Test
  fun config_whenLoopAndEndBoundaryUpdatedAfterMotionEnded_restartsFromStartPosition() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                endBoundaryOffsetMs = 500L,
              ),
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      player.setPosition(400L)
      player.setPlaybackState(Player.STATE_ENDED)
      waitForIdle()
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.currentPosition).isAtLeast(400L)

      state.config = MotionPhotoConfig.LOOP
      waitForIdle()

      assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE)
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs)
        .isEqualTo(C.TIME_END_OF_SOURCE)
      assertThat(player.currentPosition).isEqualTo(0L)
      mainClock.advanceTimeBy(MotionPhotoConfig.DEFAULT_TRANSITION_DURATION_MS)
      waitForIdle()
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun showStillPhoto_withZeroTransitionDuration_rewindsImmediately() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowMotion = true,
          initialConfig = MotionPhotoConfig(transitionDurationMs = 0L),
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()
    mainClock.autoAdvance = false
    player.setPosition(300L)

    state.showStillPhoto()

    assertThat(player.playWhenReady).isFalse()
    assertThat(player.currentPosition).isEqualTo(0L)
  }

  @Test
  fun playMotion_beforeTransitionElapses_rewindsAndCancelsDeferredRewind() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowMotion = true,
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()
    mainClock.autoAdvance = false
    player.setPosition(300L)
    state.showStillPhoto()
    mainClock.advanceTimeBy(50L)
    waitForIdle()
    assertThat(player.currentPosition).isAtLeast(300L)

    // Re-triggering motion before the 200ms fade-in completes rewinds immediately and cancels the
    // pending deferred rewind.
    state.playMotion()
    waitForIdle()

    assertThat(player.currentPosition).isEqualTo(0L)
    assertThat(player.playWhenReady).isFalse()
    assertThat(state.visualState.isPhotoVisible).isFalse()

    // Advancing past the remaining transition duration verifies the cancelled deferred rewind
    // does not fire and rewind the newly started playback back to 0ms.
    player.setPosition(120L)
    mainClock.advanceTimeBy(200L)
    waitForIdle()
    assertThat(player.playWhenReady).isTrue()
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
            initialShowMotion = true,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      player.setPosition(300L)
      state.showStillPhoto()
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
            initialShowMotion = true,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      player.setPosition(300L)
      state.showStillPhoto()
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
            initialShowMotion = true,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      player.setPosition(300L)
      state.showStillPhoto()
      mainClock.advanceTimeBy(50L)
      waitForIdle()

      state.config = state.config.copy(resumeVideoFromPreviousPosition = true)
      mainClock.advanceTimeBy(200L)
      waitForIdle()

      assertThat(player.currentPosition).isAtLeast(300L)

      state.playMotion()
      waitForIdle()
      assertThat(player.playWhenReady).isFalse()
      mainClock.advanceTimeBy(200L)
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
            initialShowMotion = true,
          )
      }
      waitForIdle()
      firstPlayer.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      firstPlayer.setPosition(300L)
      state.showStillPhoto()
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
            initialShowMotion = true,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      player.setPosition(300L)
      state.showStillPhoto()
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
  fun playMotion_withZeroTransitionDuration_unpausesImmediatelyEvenAfterFirstFrameRendered() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = false,
            initialConfig = MotionPhotoConfig(transitionDurationMs = 0L),
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false

      state.playMotion()

      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun showStillPhoto_beforeDeferredPlayElapses_cancelsPendingPlay() = runComposeUiTest {
    val player = FakePlayer(bufferingDelayMs = 0)
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowMotion = false,
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()
    mainClock.autoAdvance = false

    state.playMotion()
    mainClock.advanceTimeBy(50L)
    waitForIdle()
    assertThat(player.playWhenReady).isFalse()

    state.showStillPhoto()
    mainClock.advanceTimeBy(200L)
    waitForIdle()

    assertThat(player.playWhenReady).isFalse()
  }

  @Test
  fun config_whenBoundaryUpdatedDuringDeferredPlay_updatesClippingInPlaceWithoutRestartingDelay() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = false,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false

      state.playMotion()
      mainClock.advanceTimeBy(100L)
      waitForIdle()
      assertThat(player.playWhenReady).isFalse()

      // Updating the clipping in place keeps the rendered frame and the ongoing crossfade, so the
      // original 200ms delay completes after the remaining 100ms instead of restarting.
      state.config =
        state.config.copy(
          endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
          endBoundaryOffsetMs = 600L,
        )
      mainClock.advanceTimeBy(50L)
      waitForIdle()
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isFalse()

      mainClock.advanceTimeBy(50L)
      waitForIdle()

      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun config_whenTransitionDurationSetToZeroDuringDeferredPlay_completesOriginalDelayBeforePlaying() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = false,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      state.playMotion()
      mainClock.advanceTimeBy(50L)
      waitForIdle()

      state.config = state.config.copy(transitionDurationMs = 0L)
      mainClock.advanceTimeBy(100L)
      waitForIdle()

      // The crossfade that is already running keeps its original 200ms, so play stays deferred
      // until the remaining delay completes.
      assertThat(player.playWhenReady).isFalse()

      mainClock.advanceTimeBy(50L)
      waitForIdle()

      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_withInitialShowMotionTrueWhilePreparing_unpausesAfterFirstFrameAndCrossfade() =
    runComposeUiTest {
      // The player buffers for a while when prepared, as ExoPlayer does.
      val player = createSeekableFakePlayer(durationMs = 1_000L, bufferingDelayMs = 100L)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
          )
      }
      waitForIdle()
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
      mainClock.advanceTimeBy(200L)
      waitForIdle()

      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun rememberMotionPhotoState_whenPlayerBecomesReadyWithoutRenderingFirstFrame_unpausesImmediately() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L, bufferingDelayMs = 100L)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
          )
      }
      waitForIdle()
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
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
          )
      }
      waitForIdle()
      assertThat(player.playWhenReady).isFalse()

      state.showStillPhoto()
      waitForIdle()
      renderFirstFrameAndCompleteTransition(player)

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun config_whenBoundaryBecomesEmptyDuringDeferredPlay_cancelsPendingPlay() = runComposeUiTest {
    val player = createSeekableFakePlayer(durationMs = 1_000L)
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowMotion = false,
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()
    mainClock.autoAdvance = false

    state.playMotion()
    mainClock.advanceTimeBy(50L)
    waitForIdle()
    assertThat(player.playWhenReady).isFalse()

    state.config =
      state.config.copy(
        startBoundary = MotionPhotoConfig.BOUNDARY_END_OF_VIDEO,
        startBoundaryOffsetMs = 0L,
      )
    mainClock.advanceTimeBy(200L)
    waitForIdle()

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
      mainClock.advanceTimeBy(50L)
      waitForIdle()
      assertThat(firstPlayer.playWhenReady).isFalse()

      currentPlayer = secondPlayer
      waitForIdle()
      mainClock.advanceTimeUntil { state.player === secondPlayer }
      mainClock.advanceTimeBy(200L)
      waitForIdle()

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
      mainClock.advanceTimeBy(50L)
      waitForIdle()
      assertThat(player.playWhenReady).isFalse()

      // A new state binds the same player with initialShowMotion = false mid-delay.
      currentItem = secondMediaItem
      waitForIdle()
      mainClock.advanceTimeUntil { player.currentMediaItem == secondMediaItem }
      mainClock.advanceTimeBy(200L)
      waitForIdle()

      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun playMotion_whenPlayPauseCommandRemovedDuringDeferredPlay_keepsPlayWhenReadyFalseWithoutThrowing() =
    runComposeUiTest {
      val player = FakePlayer(bufferingDelayMs = 0, throwWhenCommandUnavailable = true)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = false,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      state.playMotion()
      mainClock.advanceTimeBy(50L)
      waitForIdle()
      assertThat(player.playWhenReady).isFalse()

      player.removeCommands(Player.COMMAND_PLAY_PAUSE)
      mainClock.advanceTimeBy(200L)
      waitForIdle()

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

      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
          )
      }
      waitForIdle()

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
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = false,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      state.playMotion()
      mainClock.advanceTimeBy(50L)
      waitForIdle()
      assertThat(player.playWhenReady).isFalse()

      player.stop()
      mainClock.advanceTimeBy(200L)
      waitForIdle()

      assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
      assertThat(player.playWhenReady).isFalse()
      assertThat(state.visualState.isPhotoVisible).isTrue()

      player.prepare()
      mainClock.advanceTimeBy(200L)
      waitForIdle()

      assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
      assertThat(player.playWhenReady).isFalse()

      player.renderFirstFrame(true)
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isFalse()

      mainClock.advanceTimeBy(200L)
      waitForIdle()

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
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = false,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      state.playMotion()
      mainClock.advanceTimeBy(50L)
      waitForIdle()
      assertThat(player.playWhenReady).isFalse()

      player.setTracks(Tracks.EMPTY)
      mainClock.advanceTimeBy(200L)
      waitForIdle()

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
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
          )
      }
      waitForIdle()
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
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
          )
      }
      waitForIdle()
      renderFirstFrameAndCompleteTransition(player)
      assertThat(player.playWhenReady).isTrue()
      mainClock.autoAdvance = false

      player.addCommands(Player.COMMAND_SET_MEDIA_ITEM)
      mainClock.advanceTimeBy(200L)
      waitForIdle()

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

      state.playMotion()
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      assertThat(player.currentPosition).isAtLeast(250L)
      assertThat(player.playWhenReady).isTrue()

      player.setPosition(400L)
      state.showStillPhoto()
      waitForIdle()

      assertThat(player.playWhenReady).isFalse()
      assertThat(player.currentPosition).isAtLeast(400L)

      state.playMotion()
      waitForIdle()
      assertThat(player.playWhenReady).isFalse()
      mainClock.advanceTimeBy(200L)
      waitForIdle()

      assertThat(player.playWhenReady).isTrue()
      assertThat(player.currentPosition).isAtLeast(400L)

      // Reaching STATE_ENDED returns to photo; subsequent playMotion() rewinds to 0L to restart.
      player.setPlaybackState(Player.STATE_ENDED)
      waitForIdle()
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      state.playMotion()
      waitForIdle()
      assertThat(player.currentPosition).isEqualTo(0L)
      assertThat(player.playWhenReady).isFalse()
      mainClock.advanceTimeBy(200L)
      waitForIdle()

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
            initialShowMotion = true,
            initialConfig = MotionPhotoConfig(resumeVideoFromPreviousPosition = true),
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()

      player.setPosition(350L)
      state.showStillPhoto()
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
            initialShowMotion = true,
            initialConfig = MotionPhotoConfig(resumeVideoFromPreviousPosition = true),
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      player.setPosition(350L)

      state.config = state.config.copy(resumeVideoFromPreviousPosition = false)
      waitForIdle()

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
                startBoundaryOffsetMs = 200L,
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
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                startBoundary = MotionPhotoConfig.BOUNDARY_END_OF_VIDEO,
                startBoundaryOffsetMs = -300L,
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

      player.setOriginalDuration(player.currentTimeline.getWindow(0, Timeline.Window()).uid, 1_000L)
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(700L)
      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isTrue()

      // An offset that reaches before the start of the video is clamped to it.
      state.config = state.config.copy(startBoundaryOffsetMs = -1_500L)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(0L)
      assertThat(player.playWhenReady).isTrue()

      // A start at the end of the video leaves nothing to play: playback pauses and the photo is
      // shown without cancelling the motion request or mutating the clipped item on the player.
      state.config = state.config.copy(startBoundaryOffsetMs = 0L)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(0L)
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      // Restoring a valid offset resumes playback in place after the crossfade from the still photo
      // completes, without resetting the first-frame gate.
      state.config = state.config.copy(startBoundaryOffsetMs = -300L)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(700L)
      assertThat(state.visualState.isPhotoVisible).isFalse()
      assertThat(player.playWhenReady).isFalse()
      mainClock.advanceTimeBy(200L)
      waitForIdle()
      assertThat(player.playWhenReady).isTrue()
    }

  @Test
  fun config_whenStartBoundaryUpdated_updatesClippedMediaItemInPlaceAndRestartsFromStartPosition() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                startBoundaryOffsetMs = 200L,
              ),
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      val clippedItemUid = player.currentTimeline.getWindow(0, Timeline.Window()).uid
      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(200L)
      assertThat(state.visualState.isPhotoVisible).isFalse()
      player.setPosition(300L)

      state.config =
        state.config.copy(
          startBoundary = MotionPhotoConfig.BOUNDARY_END_OF_VIDEO,
          startBoundaryOffsetMs = -350L,
        )
      waitForIdle()

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
      state.config = state.config.copy(startBoundaryOffsetMs = -200L)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(650L)

      player.addCommands(Player.COMMAND_CHANGE_MEDIA_ITEMS)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(800L)

      // An offset equal to the video duration leaves an empty playback range.
      state.config =
        state.config.copy(
          startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
          startBoundaryOffsetMs = 1_000L,
        )
      waitForIdle()

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
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                startBoundaryOffsetMs = 600L,
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
          startBoundaryOffsetMs = -300L,
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
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                resumeVideoFromPreviousPosition = true,
                startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                startBoundaryOffsetMs = 200L,
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
            initialConfig =
              MotionPhotoConfig(
                endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                endBoundaryOffsetMs = 400L,
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
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                endBoundaryOffsetMs = 400L,
              ),
          )
      }
      waitForIdle()
      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(400L)

      // Switching to BOUNDARY_END_OF_VIDEO while duration is still unknown keeps the photo visible
      // and pauses playback until the original duration is reported.
      state.config =
        MotionPhotoConfig(
          endBoundary = MotionPhotoConfig.BOUNDARY_END_OF_VIDEO,
          endBoundaryOffsetMs = -300L,
        )
      waitForIdle()

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
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowMotion = true,
          initialConfig =
            MotionPhotoConfig(
              startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
              startBoundaryOffsetMs = 100L,
            ),
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()
    val clippedItemUid = player.currentTimeline.getWindow(0, Timeline.Window()).uid
    player.setPosition(250L)
    assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs)
      .isEqualTo(C.TIME_END_OF_SOURCE)
    assertThat(state.visualState.isPhotoVisible).isFalse()

    state.config =
      state.config.copy(
        endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
        endBoundaryOffsetMs = 600L,
      )
    waitForIdle()

    // The start position is unchanged, so the clipped item is updated in place keeping the
    // current playback position and without resetting the first-frame gate.
    assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(100L)
    assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
    assertThat(player.currentTimeline.getWindow(0, Timeline.Window()).uid).isEqualTo(clippedItemUid)
    assertThat(player.currentPosition).isAtLeast(250L)
    assertThat(player.playWhenReady).isTrue()
    assertThat(state.visualState.isPhotoVisible).isFalse()

    // An end boundary beyond the video duration is passed through and clamped by the player.
    state.config = state.config.copy(endBoundaryOffsetMs = 1_500L)
    waitForIdle()

    assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(1_500L)
    assertThat(player.playWhenReady).isTrue()
  }

  @Test
  fun config_whenEndBoundaryUpdatedToBeforeCurrentPosition_restartsFromStartPosition() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                startBoundaryOffsetMs = 100L,
              ),
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      player.setPosition(800L)

      state.config =
        state.config.copy(
          endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
          endBoundaryOffsetMs = 600L,
        )
      waitForIdle()

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
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                startBoundaryOffsetMs = 100L,
              ),
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      // Paused so that the position does not advance before the config change is applied.
      player.pause()
      player.setPosition(500L)

      // The new range [100ms, 600ms] is exactly as long as the current position.
      state.config =
        state.config.copy(
          endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
          endBoundaryOffsetMs = 600L,
        )
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
      assertThat(player.currentPosition).isEqualTo(0L)
    }

  @Test
  fun config_whenEndBoundaryUpdatedToJustAfterCurrentPosition_keepsPosition() = runComposeUiTest {
    val player = createSeekableFakePlayer(durationMs = 1_000L)
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowMotion = true,
          initialConfig =
            MotionPhotoConfig(
              startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
              startBoundaryOffsetMs = 100L,
            ),
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()
    // Paused so that the position does not advance before the config change is applied.
    player.pause()
    player.setPosition(499L)

    // The new range [100ms, 600ms] is 1ms longer than the current position.
    state.config =
      state.config.copy(
        endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
        endBoundaryOffsetMs = 600L,
      )
    waitForIdle()

    assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
    assertThat(player.currentPosition).isEqualTo(499L)
  }

  @Test
  fun config_whenStartAndEndBoundaryUpdated_restartsFromNewStartPosition() = runComposeUiTest {
    val player = createSeekableFakePlayer(durationMs = 1_000L)
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowMotion = true,
          initialConfig =
            MotionPhotoConfig(
              startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
              startBoundaryOffsetMs = 100L,
            ),
        )
    }
    waitForIdle()
    player.renderFirstFrame(true)
    waitForIdle()
    player.setPosition(250L)

    state.config =
      state.config.copy(
        startBoundaryOffsetMs = 200L,
        endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
        endBoundaryOffsetMs = 600L,
      )
    waitForIdle()

    assertThat(player.currentMediaItem?.clippingConfiguration?.startPositionMs).isEqualTo(200L)
    assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
    assertThat(player.currentPosition).isEqualTo(0L)
    assertThat(player.playWhenReady).isTrue()
  }

  @Test
  fun config_whenEndBoundaryUpdatedWhileShowingPhotoWithResumeFromPreviousPosition_keepsPosition() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                resumeVideoFromPreviousPosition = true,
                startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                startBoundaryOffsetMs = 100L,
              ),
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      state.showStillPhoto()
      waitForIdle()
      player.setPosition(300L)
      assertThat(player.playWhenReady).isFalse()

      state.config =
        state.config.copy(
          endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
          endBoundaryOffsetMs = 600L,
        )
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
      assertThat(player.currentPosition).isEqualTo(300L)
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun config_whenEndBoundaryUpdatedWhileShowingPhotoWithResumeFalse_restartsFromStartPosition() =
    runComposeUiTest {
      val player = createSeekableFakePlayer(durationMs = 1_000L)
      lateinit var state: MotionPhotoState
      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                resumeVideoFromPreviousPosition = false,
                startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                startBoundaryOffsetMs = 100L,
              ),
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      mainClock.autoAdvance = false
      player.setPosition(300L)
      state.showStillPhoto()
      mainClock.advanceTimeBy(50L)
      waitForIdle()
      assertThat(player.currentPosition).isAtLeast(300L)
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

      state.config =
        state.config.copy(
          endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
          endBoundaryOffsetMs = 600L,
        )
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionMs).isEqualTo(600L)
      assertThat(player.currentPosition).isEqualTo(0L)
      assertThat(player.playWhenReady).isFalse()
      assertThat(seekCount).isEqualTo(1)
      mainClock.advanceTimeBy(200L)
      waitForIdle()
      assertThat(seekCount).isEqualTo(1)
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
                endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                endBoundaryOffsetMs = 600L,
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
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowMotion = false,
        )
    }
    waitForIdle()
    assertThat(player.playWhenReady).isFalse()

    state.config =
      state.config.copy(
        endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
        endBoundaryOffsetMs = 600L,
      )
    waitForIdle()

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
            initialConfig =
              MotionPhotoConfig(
                endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                endBoundaryOffsetMs = 1_500L,
              ),
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
    lateinit var state: MotionPhotoState
    setContent {
      state =
        rememberMotionPhotoState(
          player = player,
          mediaItem = testMediaItem,
          initialShowMotion = true,
          initialConfig =
            MotionPhotoConfig(
              startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
              startBoundaryOffsetMs = 500L,
              endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
              endBoundaryOffsetMs = 500L,
            ),
        )
    }
    waitForIdle()
    assertThat(state.visualState.isPhotoVisible).isTrue()
    assertThat(player.playWhenReady).isFalse()

    state.toggle()
    waitForIdle()

    // Making the range valid afterward does not start playback because toggle() cancelled the
    // motion request.
    state.config = MotionPhotoConfig.DEFAULT
    waitForIdle()

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
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                startBoundaryOffsetMs = 500L,
                endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                endBoundaryOffsetMs = 500L,
              ),
          )
      }
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      // An end before the start leaves nothing to play either.
      state.config = state.config.copy(startBoundaryOffsetMs = 600L)
      waitForIdle()

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
            initialConfig =
              MotionPhotoConfig(
                startBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
                startBoundaryOffsetMs = -50L,
              ),
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
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(startBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME),
          )
      }
      waitForIdle()

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
      setContent {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                startBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
                startBoundaryOffsetMs = -300L,
                endBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
                endBoundaryOffsetMs = 300L,
              ),
          )
      }
      waitForIdle()

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
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig = MotionPhotoConfig.PLAY_TO_PHOTO,
          )
      }
      waitForIdle()

      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()
    }

  @Test
  fun rememberMotionPhotoState_whenPlayerBecomesReadyWithoutReportingTracks_fallsBackToEndOfVideo() =
    runComposeUiTest {
      // The player is still buffering the item when the state binds and never reports any tracks.
      val player = createSeekableFakePlayer(durationMs = 1_000L, bufferingDelayMs = 100L)
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                endBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
                endBoundaryOffsetMs = -300L,
              ),
          )
      }
      waitForIdle()

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
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig = MotionPhotoConfig.PLAY_TO_PHOTO,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()

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
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(startBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME),
          )
      }
      waitForIdle()

      // The still photo is treated as the last frame of the video, so a start at or after it leaves
      // nothing to play: the unclipped item stays on the player with the photo visible and playback
      // paused, without cancelling the motion request.
      assertThat(player.currentMediaItem).isEqualTo(testMediaItem)
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      state.config = state.config.copy(startBoundaryOffsetMs = 100L)
      waitForIdle()

      assertThat(player.currentMediaItem).isEqualTo(testMediaItem)
      assertThat(state.visualState.isPhotoVisible).isTrue()
      assertThat(player.playWhenReady).isFalse()

      // A negative offset resolves relative to BOUNDARY_END_OF_VIDEO rather than staying unset.
      state.config = state.config.copy(startBoundaryOffsetMs = -200L)
      waitForIdle()

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
                startBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
                startBoundaryOffsetMs = -400L,
                endBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
                endBoundaryOffsetMs = -100L,
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
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                endBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
                endBoundaryOffsetMs = -300L,
              ),
          )
      }
      waitForIdle()

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
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig = MotionPhotoConfig.PLAY_TO_PHOTO,
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
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
            initialConfig =
              MotionPhotoConfig(startBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME),
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
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig = MotionPhotoConfig.PLAY_TO_PHOTO,
          )
      }
      waitForIdle()

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
            initialConfig =
              MotionPhotoConfig(
                endBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
                endBoundaryOffsetMs = -100L,
              ),
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
            initialConfig =
              MotionPhotoConfig(
                endBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
                endBoundaryOffsetMs = -300L,
              ),
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
            initialConfig =
              MotionPhotoConfig(
                endBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
                endBoundaryOffsetMs = -300L,
              ),
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
            initialConfig =
              MotionPhotoConfig(
                endBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
                endBoundaryOffsetMs = -300L,
              ),
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
            initialConfig =
              MotionPhotoConfig(
                endBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
                endBoundaryOffsetMs = -300L,
              ),
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
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                endBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
                endBoundaryOffsetMs = -300L,
              ),
          )
      }
      waitForIdle()

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
            initialConfig =
              MotionPhotoConfig(
                endBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
                endBoundaryOffsetMs = -100L,
              ),
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
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                endBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
                endBoundaryOffsetMs = -100L,
              ),
          )
      }
      waitForIdle()
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
            initialConfig =
              MotionPhotoConfig(
                endBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
                endBoundaryOffsetMs = -100L,
              ),
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
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                startBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
                endBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
              ),
          )
      }
      waitForIdle()

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
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialConfig =
              MotionPhotoConfig(
                endBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
                endBoundaryOffsetMs = 500L,
              ),
          )
      }
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs).isEqualTo(900_000L)

      state.config = state.config.copy(endBoundaryOffsetMs = -1L)
      waitForIdle()

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
      setContent {
        val unused =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                startBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
                startBoundaryOffsetMs = -1L,
                endBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
              ),
          )
      }
      waitForIdle()

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
      lateinit var state: MotionPhotoState

      setContent {
        state =
          rememberMotionPhotoState(
            player = player,
            mediaItem = testMediaItem,
            initialShowMotion = true,
            initialConfig =
              MotionPhotoConfig(
                startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
                startBoundaryOffsetMs = 100L,
              ),
          )
      }
      waitForIdle()
      player.renderFirstFrame(true)
      waitForIdle()
      // 500ms into the [100ms, 1000ms] range is 600ms in the video, right at the photo frame.
      player.setPosition(500L)

      state.config = state.config.copy(endBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME)
      waitForIdle()

      assertThat(player.currentMediaItem?.clippingConfiguration?.endPositionUs).isEqualTo(601_000L)
      assertThat(player.currentPosition).isEqualTo(0L)
    }

  @Test
  fun motionPhotoConfig_withInvalidStartBoundaryOrOffset_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException::class.java) { MotionPhotoConfig(startBoundary = 0) }
    assertThrows(IllegalArgumentException::class.java) {
      MotionPhotoConfig(
        startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
        startBoundaryOffsetMs = -1L,
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      MotionPhotoConfig(
        startBoundary = MotionPhotoConfig.BOUNDARY_END_OF_VIDEO,
        startBoundaryOffsetMs = 1L,
      )
    }
  }

  @Test
  fun motionPhotoConfig_withInvalidEndBoundaryOrOffset_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException::class.java) { MotionPhotoConfig(endBoundary = 0) }
    assertThrows(IllegalArgumentException::class.java) {
      MotionPhotoConfig(
        endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
        endBoundaryOffsetMs = -1L,
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      MotionPhotoConfig(
        endBoundary = MotionPhotoConfig.BOUNDARY_END_OF_VIDEO,
        endBoundaryOffsetMs = 1L,
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
    assertThat(defaultConfig.startBoundaryOffsetMs).isEqualTo(0L)
    assertThat(defaultConfig.endBoundary).isEqualTo(MotionPhotoConfig.BOUNDARY_END_OF_VIDEO)
    assertThat(defaultConfig.endBoundaryOffsetMs).isEqualTo(0L)
    assertThat(defaultConfig).isEqualTo(MotionPhotoConfig())
    assertThat(defaultConfig.hashCode()).isEqualTo(MotionPhotoConfig().hashCode())

    val loopConfig = MotionPhotoConfig.LOOP
    assertThat(loopConfig).isEqualTo(defaultConfig.copy(isLooping = true))
    assertThat(loopConfig).isNotEqualTo(defaultConfig)
    assertThat(loopConfig.copy(isLooping = false)).isEqualTo(defaultConfig)

    val playToPhotoConfig = MotionPhotoConfig.PLAY_TO_PHOTO
    assertThat(playToPhotoConfig)
      .isEqualTo(defaultConfig.copy(endBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME))
    assertThat(playToPhotoConfig).isNotEqualTo(defaultConfig)

    val customConfig =
      defaultConfig.copy(
        transitionDurationMs = 350L,
        resumeVideoFromPreviousPosition = true,
        startBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
        startBoundaryOffsetMs = -250L,
        endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
        endBoundaryOffsetMs = 600L,
      )
    assertThat(customConfig.isLooping).isFalse()
    assertThat(customConfig.transitionDurationMs).isEqualTo(350L)
    assertThat(customConfig.resumeVideoFromPreviousPosition).isTrue()
    assertThat(customConfig.startBoundary).isEqualTo(MotionPhotoConfig.BOUNDARY_PHOTO_FRAME)
    assertThat(customConfig.startBoundaryOffsetMs).isEqualTo(-250L)
    assertThat(customConfig.endBoundary).isEqualTo(MotionPhotoConfig.BOUNDARY_START_OF_VIDEO)
    assertThat(customConfig.endBoundaryOffsetMs).isEqualTo(600L)
    assertThat(customConfig).isNotEqualTo(defaultConfig)
    assertThat(customConfig).isNotEqualTo(customConfig.copy(startBoundaryOffsetMs = -100L))
    assertThat(customConfig.hashCode())
      .isNotEqualTo(customConfig.copy(startBoundaryOffsetMs = -100L).hashCode())
    assertThat(customConfig).isNotEqualTo(customConfig.copy(endBoundaryOffsetMs = 500L))
    assertThat(customConfig.hashCode())
      .isNotEqualTo(customConfig.copy(endBoundaryOffsetMs = 500L).hashCode())
    assertThat(
        customConfig.copy(
          endBoundary = MotionPhotoConfig.BOUNDARY_END_OF_VIDEO,
          endBoundaryOffsetMs = 0L,
        )
      )
      .isNotEqualTo(customConfig.copy(endBoundaryOffsetMs = 0L))
    assertThat(
        customConfig
          .copy(endBoundary = MotionPhotoConfig.BOUNDARY_END_OF_VIDEO, endBoundaryOffsetMs = 0L)
          .hashCode()
      )
      .isNotEqualTo(customConfig.copy(endBoundaryOffsetMs = 0L).hashCode())
    assertThat(
        customConfig.copy(
          startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
          startBoundaryOffsetMs = 0L,
        )
      )
      .isNotEqualTo(customConfig.copy(startBoundaryOffsetMs = 0L))
    assertThat(
        customConfig
          .copy(
            startBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
            startBoundaryOffsetMs = 0L,
          )
          .hashCode()
      )
      .isNotEqualTo(customConfig.copy(startBoundaryOffsetMs = 0L).hashCode())
    assertThat(customConfig.hashCode())
      .isEqualTo(
        MotionPhotoConfig(
            transitionDurationMs = 350L,
            resumeVideoFromPreviousPosition = true,
            startBoundary = MotionPhotoConfig.BOUNDARY_PHOTO_FRAME,
            startBoundaryOffsetMs = -250L,
            endBoundary = MotionPhotoConfig.BOUNDARY_START_OF_VIDEO,
            endBoundaryOffsetMs = 600L,
          )
          .hashCode()
      )
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

  private fun createSeekableFakePlayer(
    durationMs: Long = 1_000L,
    mediaItem: MediaItem = testMediaItem,
    tracks: Tracks = Tracks.EMPTY,
    bufferingDelayMs: Long = 0L,
  ): FakePlayer =
    FakePlayer(
        bufferingDelayMs = bufferingDelayMs,
        playlist =
          listOf(
            MediaItemData.Builder("motion_photo")
              .setMediaItem(mediaItem)
              .setDurationUs(msToUs(durationMs))
              .setTracks(tracks)
              .build()
          ),
      )
      .apply {
        if (durationMs != C.TIME_UNSET) {
          setOriginalDuration("motion_photo", durationMs)
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
    mainClock.advanceTimeBy(transitionDurationMs)
    waitForIdle()
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
}
