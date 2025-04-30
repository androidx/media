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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.compose.utils.TestPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [PresentationState]. */
@RunWith(AndroidJUnit4::class)
class PresentationStateTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun playerInitialized_presentationStateInitialized() {
    val player = TestPlayer()
    player.playbackState = Player.STATE_IDLE

    lateinit var state: PresentationState
    composeTestRule.setContent { state = rememberPresentationState(player) }

    assertThat(state.coverSurface).isTrue()
    assertThat(state.keepContentOnReset).isFalse()
    assertThat(state.videoSizeDp).isEqualTo(null)
  }

  @Test
  fun playerChangesVideoSizeBeforeEventListenerRegisters_observeGetsTheLatestValues_uiInSync() {
    val player = TestPlayer()
    player.playbackState = Player.STATE_IDLE

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
}
