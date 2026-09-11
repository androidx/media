/*
 * Copyright 2026 The Android Open Source Project
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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.testutils.createPlayerWithTracks
import androidx.media3.ui.compose.testutils.createTestTracks
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [TrackSelectionParametersState]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@UnstableApi
class TrackSelectionParametersStateTest {

  @Test
  fun constructor_nullPlayer_initializesWithDefaults() {
    val state = TrackSelectionParametersState(player = null)

    assertThat(state.tracks).isEqualTo(Tracks.EMPTY)
    assertThat(state.trackSelectionParameters).isEqualTo(TrackSelectionParameters.DEFAULT)
  }

  @Test
  fun constructor_withPlayer_initializesFromPlayerState() {
    val initialTracks = createTestTracks()
    val initialParams =
      TrackSelectionParameters.DEFAULT.buildUpon().setMaxVideoSize(1024, 768).build()

    val player = createPlayerWithTracks(tracks = initialTracks, params = initialParams)
    val state = TrackSelectionParametersState(player)

    assertThat(state.tracks).isEqualTo(initialTracks)
    assertThat(state.trackSelectionParameters).isEqualTo(initialParams)
  }

  @Test
  fun constructor_withCommandGetTracksUnavailable_initializesTracksToEmpty() {
    val initialTracks = createTestTracks()
    val player = createPlayerWithTracks(tracks = initialTracks)
    player.removeCommands(Player.COMMAND_GET_TRACKS)

    val state = TrackSelectionParametersState(player)

    assertThat(state.tracks).isEqualTo(Tracks.EMPTY)
  }

  @Test
  fun updateTrackSelectionParameters_withNewParams_updatesPlayerAndLocalState() = runComposeUiTest {
    val player = createPlayerWithTracks()
    lateinit var state: TrackSelectionParametersState
    setContent { state = rememberTrackSelectionParametersState(player) }

    val newParams =
      TrackSelectionParameters.DEFAULT.buildUpon().setPreferredVideoMimeTypes("video/avc").build()

    state.updateTrackSelectionParameters(newParams)
    waitForIdle()

    assertThat(state.trackSelectionParameters).isEqualTo(newParams)
    assertThat(player.trackSelectionParameters.preferredVideoMimeTypes).containsExactly("video/avc")
  }

  @Test
  fun updateTrackSelectionParameters_nullPlayer_doesNotUpdateState() = runComposeUiTest {
    lateinit var state: TrackSelectionParametersState
    setContent { state = rememberTrackSelectionParametersState(null) }
    val newParams =
      TrackSelectionParameters.DEFAULT.buildUpon().setPreferredAudioLanguage("fr").build()

    state.updateTrackSelectionParameters(newParams)
    waitForIdle()

    assertThat(state.trackSelectionParameters).isEqualTo(TrackSelectionParameters.DEFAULT)
  }

  @Test
  fun updateTrackSelectionParameters_withCommandSetTrackSelectionParametersUnavailable_doesNotApplyToPlayer() =
    runComposeUiTest {
      val player = createPlayerWithTracks()
      player.removeCommands(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
      lateinit var state: TrackSelectionParametersState
      setContent { state = rememberTrackSelectionParametersState(player) }

      val newParams =
        TrackSelectionParameters.DEFAULT.buildUpon().setPreferredVideoMimeTypes("video/avc").build()

      state.updateTrackSelectionParameters(newParams)
      waitForIdle()

      assertThat(player.trackSelectionParameters).isEqualTo(TrackSelectionParameters.DEFAULT)
      assertThat(state.trackSelectionParameters).isEqualTo(TrackSelectionParameters.DEFAULT)
    }

  @Test
  fun rememberTrackSelectionParametersState_nullPlayer_initializesWithDefaults() =
    runComposeUiTest {
      lateinit var state: TrackSelectionParametersState
      setContent { state = rememberTrackSelectionParametersState(null) }

      assertThat(state.tracks).isEqualTo(Tracks.EMPTY)
      assertThat(state.trackSelectionParameters).isEqualTo(TrackSelectionParameters.DEFAULT)
    }

  @Test
  fun rememberTrackSelectionParametersState_withPlayer_initializesFromPlayerState() =
    runComposeUiTest {
      val initialTracks = createTestTracks()
      val initialParams =
        TrackSelectionParameters.DEFAULT.buildUpon().setMaxVideoSize(1024, 768).build()
      val player = createPlayerWithTracks(tracks = initialTracks, params = initialParams)

      lateinit var state: TrackSelectionParametersState
      setContent { state = rememberTrackSelectionParametersState(player) }

      assertThat(state).isNotNull()
      assertThat(state.tracks).isEqualTo(initialTracks)
      assertThat(state.trackSelectionParameters).isEqualTo(initialParams)
    }

  @Test
  fun rememberTrackSelectionParametersState_playerChangesParametersBeforeObserve_uiInSync() =
    runComposeUiTest {
      val player = createPlayerWithTracks()

      lateinit var state: TrackSelectionParametersState
      setContent {
        LaunchedEffect(player) {
          player.setTrackSelectionParameters(
            TrackSelectionParameters.DEFAULT.buildUpon().setMaxVideoSize(1280, 720).build()
          )
        }
        state = rememberTrackSelectionParametersState(player = player)
      }

      waitForIdle()
      assertThat(state.trackSelectionParameters.maxVideoWidth).isEqualTo(1280)
    }

  @Test
  fun rememberTrackSelectionParametersState_withCommandGetTracksUnavailable_tracksRemainEmpty() =
    runComposeUiTest {
      val player = createPlayerWithTracks(tracks = createTestTracks())
      lateinit var state: TrackSelectionParametersState
      setContent { state = rememberTrackSelectionParametersState(player) }

      player.removeCommands(Player.COMMAND_GET_TRACKS)
      waitForIdle()

      assertThat(state.tracks).isEqualTo(Tracks.EMPTY)
    }

  @Test
  fun canSetTrackSelectionParameters_commandChanges_updatesState() = runComposeUiTest {
    val player = createPlayerWithTracks()
    lateinit var state: TrackSelectionParametersState
    setContent { state = rememberTrackSelectionParametersState(player) }

    assertThat(state.canSetTrackSelectionParameters).isTrue()

    player.removeCommands(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
    waitForIdle()

    assertThat(state.canSetTrackSelectionParameters).isFalse()

    player.addCommands(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
    waitForIdle()

    assertThat(state.canSetTrackSelectionParameters).isTrue()
  }
}
