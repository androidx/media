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
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_SET_REPEAT_MODE
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [RepeatButton]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RepeatButtonTest {

  @Test
  fun onClick_togglesRepeatMode() = runComposeUiTest {
    val player =
      FakePlayer(
        playbackState = STATE_READY,
        playWhenReady = false,
        playlist = listOf(MediaItemData.Builder("SingleItem").build()),
      )
    setContent {
      RepeatButton(
        player,
        Modifier.testTag("repeatButton"),
        toggleModeSequence =
          listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL),
      )
    }

    onNodeWithTag("repeatButton").performClick()

    assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE)
  }

  @Test
  fun onClick_commandNotAvailable_buttonDisabledClickNotPerformed() = runComposeUiTest {
    val player = FakePlayer()
    player.removeCommands(COMMAND_SET_REPEAT_MODE)
    setContent { RepeatButton(player, Modifier.testTag("repeatButton")) }

    onNodeWithTag("repeatButton").performClick()

    onNodeWithTag("repeatButton").assertIsNotEnabled()
    assertThat(player.repeatMode).isEqualTo(Player.REPEAT_MODE_OFF)
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
      RepeatButton(
        player,
        Modifier.testTag("repeatButton"),
        toggleModeSequence = listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE),
        contentDescription = { if (repeatModeState == Player.REPEAT_MODE_OFF) "off" else "one" },
      )
    }
    onNodeWithTag("repeatButton").assertContentDescriptionEquals("off")

    onNodeWithTag("repeatButton").performClick()

    onNodeWithTag("repeatButton").assertContentDescriptionEquals("one")
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
      RepeatButton(
        player,
        Modifier.testTag("repeatButton"),
        onClick = {
          this.onClick()
          onClickCalled = true
        },
      )
    }

    onNodeWithTag("repeatButton").performClick()

    assertThat(onClickCalled).isTrue()
  }
}
