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
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player.COMMAND_SET_SHUFFLE_MODE
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.test.utils.TestSimpleBasePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [ShuffleButton]. */
@RunWith(AndroidJUnit4::class)
class ShuffleButtonTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun onClick_togglesShuffleMode() {
    val player =
      TestSimpleBasePlayer(
        playbackState = STATE_READY,
        playWhenReady = false,
        playlist = listOf(MediaItemData.Builder("SingleItem").build()),
      )
    composeRule.setContent { ShuffleButton(player, Modifier.testTag("shuffleButton")) }

    composeRule.onNodeWithTag("shuffleButton").performClick()

    assertThat(player.shuffleModeEnabled).isTrue()
  }

  @Test
  fun onClick_commandNotAvailable_buttonDisabledClickNotPerformed() {
    val player = TestSimpleBasePlayer()
    player.removeCommands(COMMAND_SET_SHUFFLE_MODE)
    composeRule.setContent { ShuffleButton(player, Modifier.testTag("shuffleButton")) }

    composeRule.onNodeWithTag("shuffleButton").performClick()

    composeRule.onNodeWithTag("shuffleButton").assertIsNotEnabled()
    assertThat(player.shuffleModeEnabled).isFalse()
  }

  @Test
  fun customizeContentDescription() {
    val player =
      TestSimpleBasePlayer(
        playbackState = STATE_READY,
        playWhenReady = false,
        playlist = listOf(MediaItemData.Builder("SingleItem").build()),
      )
    composeRule.setContent {
      ShuffleButton(
        player,
        Modifier.testTag("shuffleButton"),
        contentDescription = { if (shuffleOn) "on" else "off" },
      )
    }
    composeRule.onNodeWithTag("shuffleButton").assertContentDescriptionEquals("off")

    composeRule.onNodeWithTag("shuffleButton").performClick()

    composeRule.onNodeWithTag("shuffleButton").assertContentDescriptionEquals("on")
  }

  @Test
  fun customizeOnClick() {
    val player =
      TestSimpleBasePlayer(
        playbackState = STATE_READY,
        playWhenReady = false,
        playlist = listOf(MediaItemData.Builder("SingleItem").build()),
      )
    var onClickCalled = false
    composeRule.setContent {
      ShuffleButton(
        player,
        Modifier.testTag("shuffleButton"),
        onClick = {
          this.onClick()
          onClickCalled = true
        },
      )
    }

    composeRule.onNodeWithTag("shuffleButton").performClick()

    assertThat(onClickCalled).isTrue()
  }
}
