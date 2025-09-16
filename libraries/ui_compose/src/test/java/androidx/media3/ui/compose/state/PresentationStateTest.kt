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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.test.utils.TestSimpleBasePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [PresentationState]. */
@RunWith(AndroidJUnit4::class)
class PresentationStateTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun playerInitialized_presentationStateInitialized() {
    val player = TestSimpleBasePlayer(playbackState = Player.STATE_IDLE)

    lateinit var state: PresentationState
    composeTestRule.setContent { state = rememberPresentationState(player) }

    assertThat(state.coverSurface).isTrue()
    assertThat(state.keepContentOnReset).isFalse()
    assertThat(state.videoSizeDp).isEqualTo(null)
  }

  @Test
  fun playerChangesVideoSizeBeforeEventListenerRegisters_observeGetsTheLatestValues_uiInSync() {
    val player = TestSimpleBasePlayer(playbackState = Player.STATE_IDLE)

    lateinit var state: PresentationState
    composeTestRule.setContent {
      // Schedule LaunchedEffect to update player state before PresentationState is created.
      // This update could end up being executed *before* PresentationState schedules the start
      // of event listening and we don't want to lose it.
      LaunchedEffect(player) { player.videoSize = VideoSize(480, 360) }
      state = rememberPresentationState(player)
    }

    assertThat(state.videoSizeDp).isEqualTo(Size(480f, 360f))
    assertThat(state.coverSurface).isTrue()
    assertThat(state.keepContentOnReset).isFalse()
  }

  @Test
  fun firstFrameRendered_shutterOpens() {
    val player = TestSimpleBasePlayer(playbackState = Player.STATE_IDLE)

    lateinit var state: PresentationState
    composeTestRule.setContent { state = rememberPresentationState(player) }
    assertThat(state.coverSurface).isTrue()

    player.renderFirstFrame(true)
    composeTestRule.waitForIdle()

    assertThat(state.coverSurface).isFalse()
  }

  @Test
  fun newNonNullPlayer_keepContentOnResetAndShutterAlreadyOpen_doNotCloseShutter() {
    val player0 = TestSimpleBasePlayer(playbackState = Player.STATE_IDLE)
    val player1 = TestSimpleBasePlayer()

    lateinit var playerIndex: MutableIntState
    lateinit var state: PresentationState
    composeTestRule.setContent {
      playerIndex = remember { mutableIntStateOf(0) }
      state =
        rememberPresentationState(
          player = if (playerIndex.intValue == 0) player0 else player1,
          keepContentOnReset = true,
        )
    }

    player0.renderFirstFrame(true)
    playerIndex.intValue = 1
    composeTestRule.waitForIdle()

    assertThat(state.player).isEqualTo(player1)
    assertThat(state.coverSurface).isFalse()
    assertThat(state.keepContentOnReset).isTrue()
  }

  @Test
  fun newNullPlayer_keepContentOnResetAndShutterAlreadyOpen_doNotCloseShutter() {
    val player0 = TestSimpleBasePlayer(playbackState = Player.STATE_IDLE)
    val player1 = null
    lateinit var playerIndex: MutableIntState
    lateinit var state: PresentationState
    composeTestRule.setContent {
      playerIndex = remember { mutableIntStateOf(0) }
      state =
        rememberPresentationState(
          player = if (playerIndex.intValue == 0) player0 else player1,
          keepContentOnReset = true,
        )
    }

    player0.renderFirstFrame(true)
    playerIndex.intValue = 1
    composeTestRule.waitForIdle()

    assertThat(state.player).isEqualTo(player1)
    assertThat(state.coverSurface).isFalse()
    assertThat(state.keepContentOnReset).isTrue()
  }

  @Test
  fun nullChangedToNonNullPlayer_keepContentOnReset_shutterStaysClosed() {
    val player0 = null
    val player1 = TestSimpleBasePlayer()

    lateinit var playerIndex: MutableIntState
    lateinit var state: PresentationState
    composeTestRule.setContent {
      playerIndex = remember { mutableIntStateOf(0) }
      state =
        rememberPresentationState(
          player = if (playerIndex.intValue == 0) player0 else player1,
          keepContentOnReset = true,
        )
    }

    playerIndex.intValue = 1
    composeTestRule.waitForIdle()

    assertThat(state.player).isEqualTo(player1)
    assertThat(state.coverSurface).isTrue()
    assertThat(state.keepContentOnReset).isTrue()
  }

  @Test
  fun newNonNullPlayer_doNotKeepContentOnResetAndShutterAlreadyOpen_closeShutter() {
    val player0 = TestSimpleBasePlayer(playbackState = Player.STATE_IDLE)
    val player1 = TestSimpleBasePlayer()

    lateinit var playerIndex: MutableIntState
    lateinit var state: PresentationState
    composeTestRule.setContent {
      playerIndex = remember { mutableIntStateOf(0) }
      state =
        rememberPresentationState(
          player = if (playerIndex.intValue == 0) player0 else player1,
          keepContentOnReset = false,
        )
    }

    player0.renderFirstFrame(true)
    playerIndex.intValue = 1
    composeTestRule.waitForIdle()

    assertThat(state.player).isEqualTo(player1)
    assertThat(state.coverSurface).isTrue()
    assertThat(state.keepContentOnReset).isFalse()
  }

  @Test
  fun newNullPlayer_doNotKeepContentOnResetAndShutterAlreadyOpen_closeShutter() {
    val player0 = TestSimpleBasePlayer(playbackState = Player.STATE_IDLE)
    val player1 = null

    lateinit var playerIndex: MutableIntState
    lateinit var state: PresentationState
    composeTestRule.setContent {
      playerIndex = remember { mutableIntStateOf(0) }
      state =
        rememberPresentationState(
          player = if (playerIndex.intValue == 0) player0 else player1,
          keepContentOnReset = false,
        )
    }

    player0.renderFirstFrame(true)
    playerIndex.intValue = 1
    composeTestRule.waitForIdle()

    assertThat(state.player).isEqualTo(player1)
    assertThat(state.coverSurface).isTrue()
    assertThat(state.keepContentOnReset).isFalse()
  }

  @Ignore("Internal ref: b/445384212")
  @Test
  fun keepContentOnReset_toggleValue_affectsCoveringSurfaceWithShutter() {
    val player = TestSimpleBasePlayer(playbackState = Player.STATE_IDLE)

    lateinit var keepContentOnReset: MutableState<Boolean>
    lateinit var state: PresentationState
    composeTestRule.setContent {
      keepContentOnReset = remember { mutableStateOf(true) }
      state = rememberPresentationState(player, keepContentOnReset = keepContentOnReset.value)
    }
    assertThat(state.keepContentOnReset).isTrue()
    assertThat(state.coverSurface).isTrue()

    player.renderFirstFrame(true)
    composeTestRule.waitForIdle()

    assertThat(state.keepContentOnReset).isTrue()
    assertThat(state.coverSurface).isFalse()

    keepContentOnReset.value = false
    composeTestRule.waitForIdle()

    assertThat(state.keepContentOnReset).isFalse()
    assertThat(state.coverSurface).isTrue()
  }
}
