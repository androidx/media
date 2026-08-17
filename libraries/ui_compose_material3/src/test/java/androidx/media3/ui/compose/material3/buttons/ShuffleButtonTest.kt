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

package androidx.media3.ui.compose.material3.buttons

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.media3.common.Player.COMMAND_SET_SHUFFLE_MODE
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [ShuffleButton]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ShuffleButtonTest {

  @Test
  fun onClick_togglesShuffleMode() = runComposeUiTest {
    val player =
      FakePlayer(
        playbackState = STATE_READY,
        playWhenReady = false,
        playlist = listOf(MediaItemData.Builder("SingleItem").build()),
      )
    setContent { ShuffleButton(player, Modifier.testTag("shuffleButton")) }

    onNodeWithTag("shuffleButton").performClick()

    assertThat(player.shuffleModeEnabled).isTrue()
  }

  @Test
  fun onClick_commandNotAvailable_buttonDisabledClickNotPerformed() = runComposeUiTest {
    val player = FakePlayer()
    player.removeCommands(COMMAND_SET_SHUFFLE_MODE)
    setContent { ShuffleButton(player, Modifier.testTag("shuffleButton")) }

    onNodeWithTag("shuffleButton").performClick()

    onNodeWithTag("shuffleButton").assertIsNotEnabled()
    assertThat(player.shuffleModeEnabled).isFalse()
  }

  @Test
  fun customizeContentDescription() = runComposeUiTest {
    val player =
      FakePlayer(
        playbackState = STATE_READY,
        playWhenReady = false,
        playlist = listOf(MediaItemData.Builder("SingleItem").build()),
      )
    setContent {
      ShuffleButton(
        player,
        Modifier.testTag("shuffleButton"),
        contentDescription = { if (shuffleOn) "on" else "off" },
      )
    }
    onNodeWithTag("shuffleButton").assertContentDescriptionEquals("off")

    onNodeWithTag("shuffleButton").performClick()

    onNodeWithTag("shuffleButton").assertContentDescriptionEquals("on")
  }

  @Test
  fun customizeOnClick() = runComposeUiTest {
    val player =
      FakePlayer(
        playbackState = STATE_READY,
        playWhenReady = false,
        playlist = listOf(MediaItemData.Builder("SingleItem").build()),
      )
    var onClickCalled = false
    setContent {
      ShuffleButton(
        player,
        Modifier.testTag("shuffleButton"),
        onClick = {
          this.onClick()
          onClickCalled = true
        },
      )
    }

    onNodeWithTag("shuffleButton").performClick()

    assertThat(onClickCalled).isTrue()
  }
}
