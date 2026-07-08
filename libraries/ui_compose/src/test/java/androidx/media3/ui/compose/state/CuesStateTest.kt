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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [CuesState]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CuesStateTest {

  @Test
  fun initialState_withNullPlayer_hasEmptyCues() = runComposeUiTest {
    lateinit var state: CuesState
    setContent { state = rememberCuesState(player = null) }

    assertThat(state.cues).isEmpty()
  }

  @Test
  fun playerWithCues_updatesStateCorrectly() = runComposeUiTest {
    val cue = Cue.Builder().setText("Hello World").build()
    val player = FakePlayer()

    lateinit var state: CuesState
    setContent { state = rememberCuesState(player = player) }

    assertThat(state.cues).isEmpty()

    player.setCurrentCues(CueGroup(ImmutableList.of(cue), 0))
    waitForIdle()

    assertThat(state.cues).hasSize(1)
    assertThat(state.cues[0].text).isEqualTo("Hello World")
  }

  @Test
  fun commandChanges_updateProperties() = runComposeUiTest {
    val cue = Cue.Builder().setText("Hello World").build()
    val player = FakePlayer()

    lateinit var state: CuesState
    setContent { state = rememberCuesState(player = player) }

    player.setCurrentCues(CueGroup(ImmutableList.of(cue), 0))
    waitForIdle()

    assertThat(state.cues).hasSize(1)

    player.removeCommands(Player.COMMAND_GET_TEXT)
    waitForIdle()

    assertThat(state.cues).isEmpty()

    player.addCommands(Player.COMMAND_GET_TEXT)
    waitForIdle()

    assertThat(state.cues).hasSize(1)
    assertThat(state.cues[0].text).isEqualTo("Hello World")
  }

  @Test
  fun initialState_withPopulatedPlayer_hasCuesImmediately() = runComposeUiTest {
    val cue = Cue.Builder().setText("Pre-existing").build()
    val player = FakePlayer()
    player.setCurrentCues(CueGroup(ImmutableList.of(cue), 0))

    lateinit var state: CuesState
    setContent { state = rememberCuesState(player = player) }

    // Should be available immediately, no waitForIdle() or event needed.
    assertThat(state.cues).hasSize(1)
    assertThat(state.cues[0].text).isEqualTo("Pre-existing")
  }

  @Test
  fun playerInstanceChanged_updatesToNewPlayerCues() = runComposeUiTest {
    val cue1 = Cue.Builder().setText("Player 1").build()
    val player1 = FakePlayer()
    player1.setCurrentCues(CueGroup(ImmutableList.of(cue1), 0))

    val cue2 = Cue.Builder().setText("Player 2").build()
    val player2 = FakePlayer()
    player2.setCurrentCues(CueGroup(ImmutableList.of(cue2), 0))

    var currentPlayer by mutableStateOf<Player>(player1)
    lateinit var state: CuesState

    setContent { state = rememberCuesState(player = currentPlayer) }

    assertThat(state.cues).hasSize(1)
    assertThat(state.cues[0].text).isEqualTo("Player 1")

    currentPlayer = player2
    waitForIdle()

    assertThat(state.cues).hasSize(1)
    assertThat(state.cues[0].text).isEqualTo("Player 2")
  }

  @Test
  fun playerCuesCleared_updatesStateToEmpty() = runComposeUiTest {
    val cue = Cue.Builder().setText("Hello World").build()
    val player = FakePlayer()

    lateinit var state: CuesState
    setContent { state = rememberCuesState(player = player) }

    player.setCurrentCues(CueGroup(ImmutableList.of(cue), 0))
    waitForIdle()

    assertThat(state.cues).hasSize(1)

    player.setCurrentCues(CueGroup.EMPTY_TIME_ZERO)
    waitForIdle()

    assertThat(state.cues).isEmpty()
  }
}
