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
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.test.utils.TestSimpleBasePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [PreviousButton]. */
@RunWith(AndroidJUnit4::class)
class PreviousButtonTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun onClick_callsPrevious() {
    val player =
      TestSimpleBasePlayer(
        playlist =
          listOf(
            MediaItemData.Builder("First").setDurationUs(1_000_000L).build(),
            MediaItemData.Builder("Second").setDurationUs(2_000_000L).build(),
          )
      )
    player.seekToNext()

    composeRule.setContent { PreviousButton(player, Modifier.testTag("previousButton")) }

    composeRule.onNodeWithTag("previousButton").performClick()

    assertThat(player.currentMediaItemIndex).isEqualTo(0)
  }

  @Test
  fun onClick_commandNotAvailable_buttonDisabledClickNotPerformed() {
    val player =
      TestSimpleBasePlayer(
        playlist =
          listOf(
            MediaItemData.Builder("First").setDurationUs(1_000_000L).build(),
            MediaItemData.Builder("Second").setDurationUs(2_000_000L).build(),
          )
      )
    player.seekToNext()
    player.removeCommands(COMMAND_SEEK_TO_PREVIOUS)

    composeRule.setContent { PreviousButton(player, Modifier.testTag("previousButton")) }

    composeRule.onNodeWithTag("previousButton").performClick()

    composeRule.onNodeWithTag("previousButton").assertIsNotEnabled()
    assertThat(player.currentMediaItemIndex).isEqualTo(1)
  }

  @Test
  fun customizeContentDescription() {
    val player = TestSimpleBasePlayer()

    composeRule.setContent {
      PreviousButton(player, Modifier.testTag("previousButton"), contentDescription = { "Go back" })
    }

    composeRule.onNodeWithTag("previousButton").assertContentDescriptionEquals("Go back")
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
      PreviousButton(
        player,
        Modifier.testTag("previousButton"),
        onClick = {
          this.onClick()
          onClickCalled = true
        },
      )
    }

    composeRule.onNodeWithTag("previousButton").performClick()

    assertThat(onClickCalled).isTrue()
  }
}
