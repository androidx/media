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
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.MimeTypes.VIDEO_VP9
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [PresentationState]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PresentationStateTest {

  @Test
  fun playerInitialized_presentationStateInitialized() = runComposeUiTest {
    val player = FakePlayer(playbackState = Player.STATE_IDLE)

    lateinit var state: PresentationState
    setContent { state = rememberPresentationState(player) }

    assertThat(state.coverSurface).isTrue()
    assertThat(state.keepContentOnReset).isFalse()
    @Suppress("DEPRECATION") assertThat(state.videoSizeDp).isNull()
    assertThat(state.videoAspectRatio).isNull()
  }

  @Test
  fun videoProperties_reflectPlayerVideoSize() = runComposeUiTest {
    val player = FakePlayer()
    player.setVideoSize(VideoSize(1920, 1080))

    lateinit var state: PresentationState
    setContent { state = rememberPresentationState(player) }

    @Suppress("DEPRECATION") assertThat(state.videoSizeDp).isEqualTo(Size(1920f, 1080f))
    assertThat(state.videoAspectRatio).isEqualTo(1920f / 1080f)
  }

  @Test
  fun videoProperties_reflectPlayerVideoSize_withPixelAspectRatio() = runComposeUiTest {
    val player = FakePlayer()
    player.setVideoSize(VideoSize(1920, 1620, /* pixelWidthHeightRatio= */ 1.5f))

    lateinit var state: PresentationState
    setContent { state = rememberPresentationState(player) }

    @Suppress("DEPRECATION") assertThat(state.videoSizeDp).isEqualTo(Size(1920f, 1080f))
    assertThat(state.videoAspectRatio).isEqualTo(1920f / 1080f)
  }

  @Test
  fun rememberPresentationState_recomposition_hasAspectRatioOnFirstPass() = runComposeUiTest {
    val player = FakePlayer(playbackState = Player.STATE_IDLE)
    player.setVideoSize(VideoSize(1920, 1080))
    val observedAspectRatios = mutableSetOf<Float?>()

    setContent {
      val state = rememberPresentationState(player)
      // Capture the value of videoAspectRatio exactly as it is seen during the composition pass.
      // This happens before LaunchedEffect gets a chance to run.
      observedAspectRatios.add(state.videoAspectRatio)
    }

    // Assert that all composition passes had the correct aspect ratio.
    assertThat(observedAspectRatios).containsExactly(1920f / 1080f)
  }

  @Test
  fun rememberPresentationState_recomposition_syncsVideoAspectRatioImmediately() =
    runComposeUiTest {
      val player = FakePlayer()
      player.setVideoSize(VideoSize(1920, 1080))

      lateinit var recomposeKey: MutableIntState
      lateinit var state: PresentationState

      setContent {
        recomposeKey = remember { mutableIntStateOf(0) }
        key(recomposeKey.intValue) { state = rememberPresentationState(player) }
      }

      assertThat(state.videoAspectRatio).isEqualTo(1920f / 1080f)

      recomposeKey.intValue = 1
      waitForIdle()

      assertThat(state.videoAspectRatio).isEqualTo(1920f / 1080f)
    }

  @Test
  fun playerChangesVideoSizeBeforeEventListenerRegisters_observeGetsTheLatestValues_uiInSync() =
    runComposeUiTest {
      val player = FakePlayer(playbackState = Player.STATE_IDLE)

      lateinit var state: PresentationState
      setContent {
        // Schedule LaunchedEffect to update player state before PresentationState is created.
        // This update could end up being executed *before* PresentationState schedules the start
        // of event listening and we don't want to lose it.
        LaunchedEffect(player) { player.videoSize = VideoSize(480, 360) }
        state = rememberPresentationState(player)
      }

      assertThat(state.videoAspectRatio).isEqualTo(480f / 360f)
      assertThat(state.coverSurface).isTrue()
      assertThat(state.keepContentOnReset).isFalse()
    }

  @Test
  fun firstFrameRendered_shutterOpens() = runComposeUiTest {
    val player = FakePlayer(playbackState = Player.STATE_IDLE)

    lateinit var state: PresentationState
    setContent { state = rememberPresentationState(player) }
    assertThat(state.coverSurface).isTrue()

    player.renderFirstFrame(true)
    waitForIdle()

    assertThat(state.coverSurface).isFalse()
  }

  @Test
  fun newNonNullPlayer_keepContentOnResetAndShutterAlreadyOpen_doNotCloseShutter() =
    runComposeUiTest {
      val player0 = FakePlayer(playbackState = Player.STATE_IDLE)
      val player1 = FakePlayer()

      lateinit var playerIndex: MutableIntState
      lateinit var state: PresentationState
      setContent {
        playerIndex = remember { mutableIntStateOf(0) }
        state =
          rememberPresentationState(
            player = if (playerIndex.intValue == 0) player0 else player1,
            keepContentOnReset = true,
          )
      }

      player0.renderFirstFrame(true)
      playerIndex.intValue = 1
      waitForIdle()

      assertThat(state.player).isEqualTo(player1)
      assertThat(state.coverSurface).isFalse()
      assertThat(state.keepContentOnReset).isTrue()
    }

  @Test
  fun newNullPlayer_keepContentOnResetAndShutterAlreadyOpen_doNotCloseShutter() = runComposeUiTest {
    val player0 = FakePlayer(playbackState = Player.STATE_IDLE)
    val player1 = null
    lateinit var playerIndex: MutableIntState
    lateinit var state: PresentationState
    setContent {
      playerIndex = remember { mutableIntStateOf(0) }
      state =
        rememberPresentationState(
          player = if (playerIndex.intValue == 0) player0 else player1,
          keepContentOnReset = true,
        )
    }

    player0.renderFirstFrame(true)
    playerIndex.intValue = 1
    waitForIdle()

    assertThat(state.player).isEqualTo(player1)
    assertThat(state.coverSurface).isFalse()
    assertThat(state.keepContentOnReset).isTrue()
  }

  @Test
  fun nullChangedToNonNullPlayer_keepContentOnReset_shutterStaysClosed() = runComposeUiTest {
    val player0 = null
    val player1 = FakePlayer()

    lateinit var playerIndex: MutableIntState
    lateinit var state: PresentationState
    setContent {
      playerIndex = remember { mutableIntStateOf(0) }
      state =
        rememberPresentationState(
          player = if (playerIndex.intValue == 0) player0 else player1,
          keepContentOnReset = true,
        )
    }

    playerIndex.intValue = 1
    waitForIdle()

    assertThat(state.player).isEqualTo(player1)
    assertThat(state.coverSurface).isTrue()
    assertThat(state.keepContentOnReset).isTrue()
  }

  @Test
  fun newNonNullPlayer_doNotKeepContentOnResetAndShutterAlreadyOpen_closeShutter() =
    runComposeUiTest {
      val player0 = FakePlayer(playbackState = Player.STATE_IDLE)
      val player1 = FakePlayer()

      lateinit var playerIndex: MutableIntState
      lateinit var state: PresentationState
      setContent {
        playerIndex = remember { mutableIntStateOf(0) }
        state =
          rememberPresentationState(
            player = if (playerIndex.intValue == 0) player0 else player1,
            keepContentOnReset = false,
          )
      }

      player0.renderFirstFrame(true)
      playerIndex.intValue = 1
      waitForIdle()

      assertThat(state.player).isEqualTo(player1)
      assertThat(state.coverSurface).isTrue()
      assertThat(state.keepContentOnReset).isFalse()
    }

  @Test
  fun newNullPlayer_doNotKeepContentOnResetAndShutterAlreadyOpen_closeShutter() = runComposeUiTest {
    val player0 = FakePlayer(playbackState = Player.STATE_IDLE)
    val player1 = null

    lateinit var playerIndex: MutableIntState
    lateinit var state: PresentationState
    setContent {
      playerIndex = remember { mutableIntStateOf(0) }
      state =
        rememberPresentationState(
          player = if (playerIndex.intValue == 0) player0 else player1,
          keepContentOnReset = false,
        )
    }

    player0.renderFirstFrame(true)
    playerIndex.intValue = 1
    waitForIdle()

    assertThat(state.player).isEqualTo(player1)
    assertThat(state.coverSurface).isTrue()
    assertThat(state.keepContentOnReset).isFalse()
  }

  @Test
  fun keepContentOnReset_toggleValue_affectsCoveringSurfaceWithShutter() = runComposeUiTest {
    val player = FakePlayer(playbackState = Player.STATE_IDLE)

    lateinit var keepContentOnReset: MutableState<Boolean>
    lateinit var state: PresentationState
    setContent {
      keepContentOnReset = remember { mutableStateOf(true) }
      state = rememberPresentationState(player, keepContentOnReset = keepContentOnReset.value)
    }
    assertThat(state.keepContentOnReset).isTrue()
    assertThat(state.coverSurface).isTrue()

    player.renderFirstFrame(true)
    waitForIdle()

    assertThat(state.keepContentOnReset).isTrue()
    assertThat(state.coverSurface).isFalse()

    keepContentOnReset.value = false
    waitForIdle()

    assertThat(state.keepContentOnReset).isFalse()
    assertThat(state.coverSurface).isTrue()
  }

  @Test
  fun timelineChanged_differentWindow_coversSurface() = runComposeUiTest {
    val group =
      Tracks.Group(
        /* mediaTrackGroup = */ TrackGroup(Format.Builder().setSampleMimeType(VIDEO_VP9).build()),
        /* adaptiveSupported = */ true,
        /* trackSupport = */ intArrayOf(C.FORMAT_HANDLED),
        /* trackSelected = */ booleanArrayOf(true),
      )
    val validTracks = Tracks(listOf(group))
    val player =
      FakePlayer(
        playlist =
          listOf(
            MediaItemData.Builder("uid1").setTracks(Tracks.EMPTY).build(),
            MediaItemData.Builder("uid2").setTracks(validTracks).build(),
          )
      )
    var remotableTimeline: Timeline? = null
    val presentationPlayer =
      object : ForwardingPlayer(player) {
        override fun getCurrentTimeline() = remotableTimeline ?: super.getCurrentTimeline()
      }
    lateinit var state: PresentationState
    setContent { state = rememberPresentationState(presentationPlayer) }
    waitForIdle()
    assertThat(state.coverSurface).isTrue()

    player.seekToNext()
    player.renderFirstFrame(true)
    waitForIdle()
    assertThat(state.coverSurface).isFalse()

    // Enable remotable timeline, strips period and window UIDs
    remotableTimeline =
      Timeline.fromBundle(
        player.currentTimeline.toBundle(MediaLibraryInfo.INTERFACE_VERSION),
        MediaLibraryInfo.INTERFACE_VERSION,
      )

    // Seek back to 0 (no tracks, different media item) -> should cover surface
    player.seekToPrevious().also { waitForIdle() }

    assertThat(state.coverSurface).isTrue()
  }

  @Test
  fun timelineChanged_sameWindowUnique_keepsSurfaceVisible() = runComposeUiTest {
    val group =
      Tracks.Group(
        /* mediaTrackGroup = */ TrackGroup(Format.Builder().setSampleMimeType(VIDEO_VP9).build()),
        /* adaptiveSupported = */ true,
        /* trackSupport = */ intArrayOf(C.FORMAT_HANDLED),
        /* trackSelected = */ booleanArrayOf(true),
      )
    val validTracks = Tracks(listOf(group))
    val mediaItemA = MediaItem.Builder().setMediaId("uid1").build()
    val mediaItemB = MediaItem.Builder().setMediaId("uid2").build()
    val mediaItemC = MediaItem.Builder().setMediaId("uid3").build()

    val itemA =
      MediaItemData.Builder("uid1").setMediaItem(mediaItemA).setTracks(Tracks.EMPTY).build()
    val itemB =
      MediaItemData.Builder("uid2").setMediaItem(mediaItemB).setTracks(validTracks).build()
    val itemC =
      MediaItemData.Builder("uid3").setMediaItem(mediaItemC).setTracks(Tracks.EMPTY).build()
    val player = FakePlayer(playlist = listOf(itemA, itemB, itemC))
    var remotableTimeline: Timeline? = null
    val presentationPlayer =
      object : ForwardingPlayer(player) {
        override fun getCurrentTimeline() = remotableTimeline ?: super.getCurrentTimeline()
      }
    lateinit var state: PresentationState
    setContent { state = rememberPresentationState(presentationPlayer) }

    player.seekToNext()
    player.renderFirstFrame(true)
    waitForIdle()
    assertThat(state.coverSurface).isFalse()

    val itemAUnprepared =
      MediaItemData.Builder("uid1").setMediaItem(mediaItemA).setTracks(Tracks.EMPTY).build()
    val itemBUnprepared =
      MediaItemData.Builder("uid2").setMediaItem(mediaItemB).setTracks(Tracks.EMPTY).build()

    val ad = MediaItemData.Builder("ad").setTracks(Tracks.EMPTY).build()
    val tempPlayer = FakePlayer(playlist = listOf(ad, itemAUnprepared, itemBUnprepared))
    // Enable remotable timeline, strips period and window UIDs
    remotableTimeline =
      Timeline.fromBundle(
        tempPlayer.currentTimeline.toBundle(MediaLibraryInfo.INTERFACE_VERSION),
        MediaLibraryInfo.INTERFACE_VERSION,
      )

    // player.seekToNextMediaItem would originally go from Video B (index 1) to Video C (2).
    // With new remotableTimeline (ad, A, B), going from index 1 to 2 results in Video B.
    // But we are already on itemB! -> Suppress shutter from appearing
    player.seekToNextMediaItem().also { waitForIdle() }

    assertThat(state.coverSurface).isFalse()
  }

  @Test
  fun timelineChanged_sameWindowDuplicate_coversSurface() = runComposeUiTest {
    val group =
      Tracks.Group(
        /* mediaTrackGroup = */ TrackGroup(Format.Builder().setSampleMimeType(VIDEO_VP9).build()),
        /* adaptiveSupported = */ true,
        /* trackSupport = */ intArrayOf(C.FORMAT_HANDLED),
        /* trackSelected = */ booleanArrayOf(true),
      )
    val validTracks = Tracks(listOf(group))
    val mediaItemA = MediaItem.Builder().setMediaId("uid1").build()
    val mediaItemB = MediaItem.Builder().setMediaId("uid2").build()

    val itemA1 =
      MediaItemData.Builder("uid1").setMediaItem(mediaItemA).setTracks(validTracks).build()
    val itemB =
      MediaItemData.Builder("uid2").setMediaItem(mediaItemB).setTracks(Tracks.EMPTY).build()
    val itemA2 =
      MediaItemData.Builder("uid3").setMediaItem(mediaItemA).setTracks(Tracks.EMPTY).build()
    val player = FakePlayer(playlist = listOf(itemA1, itemB, itemA2))
    var remotableTimeline: Timeline? = null
    val presentationPlayer =
      object : ForwardingPlayer(player) {
        override fun getCurrentTimeline() = remotableTimeline ?: super.getCurrentTimeline()
      }
    lateinit var state: PresentationState
    setContent { state = rememberPresentationState(presentationPlayer) }
    player.renderFirstFrame(true)
    waitForIdle()
    assertThat(state.coverSurface).isFalse()

    remotableTimeline =
      Timeline.fromBundle(
        player.currentTimeline.toBundle(MediaLibraryInfo.INTERFACE_VERSION),
        MediaLibraryInfo.INTERFACE_VERSION,
      )

    // Seek to index 2 (Video A's second occurrence)
    player.seekTo(2, 0L).also { waitForIdle() }

    // Since Video A is duplicated, we cannot uniquely resolve it.
    // It should safely fall back to covering the surface.
    assertThat(state.coverSurface).isTrue()
  }

  @Test
  fun tracksChanged_sameWindowUnique_sameTimeline_keepsSurfaceVisible() = runComposeUiTest {
    val group =
      Tracks.Group(
        /* mediaTrackGroup = */ TrackGroup(Format.Builder().setSampleMimeType(VIDEO_VP9).build()),
        /* adaptiveSupported = */ true,
        /* trackSupport = */ intArrayOf(C.FORMAT_HANDLED),
        /* trackSelected = */ booleanArrayOf(true),
      )
    val validTracks = Tracks(listOf(group))
    val mediaItemA = MediaItem.Builder().setMediaId("uid1").build()
    val mediaItemB = MediaItem.Builder().setMediaId("uid2").build()
    val player =
      FakePlayer(
        playlist =
          listOf(
            MediaItemData.Builder("uid1").setMediaItem(mediaItemA).setTracks(Tracks.EMPTY).build(),
            MediaItemData.Builder("uid2").setMediaItem(mediaItemB).setTracks(validTracks).build(),
          )
      )
    val staticTimeline = player.currentTimeline
    val presentationPlayer =
      object : ForwardingPlayer(player) {
        override fun getCurrentTimeline() = staticTimeline
      }
    lateinit var state: PresentationState
    setContent { state = rememberPresentationState(presentationPlayer) }

    // Go at 1 (has tracks)
    player.seekToNext()
    player.renderFirstFrame(true).also { waitForIdle() }
    assertThat(state.coverSurface).isFalse()

    // Organically trigger EVENT_TRACKS_CHANGED on the same index by replacing the playlist
    // with the same items. FakePlayer will rebuild the playlist with default (empty) tracks,
    // simulating transition to unprepared state.
    player.setMediaItems(listOf(mediaItemA, mediaItemB), /* resetPosition= */ false)
    waitForIdle()

    // Should remain visible (shutter open) because we are in the same window on the same timeline
    assertThat(state.coverSurface).isFalse()
  }
}
