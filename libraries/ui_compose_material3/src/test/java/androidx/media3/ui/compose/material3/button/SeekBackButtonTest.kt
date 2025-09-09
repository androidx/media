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

package androidx.media3.ui.compose.material3.button

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player.COMMAND_SEEK_BACK
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.test.utils.TestSimpleBasePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [SeekBackButton]. */
@RunWith(AndroidJUnit4::class)
class SeekBackButtonTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun onClick_callsSeekBack() {
    val player =
      TestSimpleBasePlayer(
        playlist =
          listOf(
            MediaItemData.Builder("SingleItem")
              .setDurationUs(10_000_000)
              .setIsSeekable(true)
              .build()
          )
      )
    player.setPosition(5_000)
    player.setSeekBackIncrementMs(1_000)
    composeRule.setContent { SeekBackButton(player, Modifier.testTag("seekBackButton")) }

    composeRule.onNodeWithTag("seekBackButton").performClick()

    assertThat(player.currentPosition).isEqualTo(4_000)
  }

  @Test
  fun onClick_commandNotAvailable_buttonDisabledClickNotPerformed() {
    val player = TestSimpleBasePlayer()
    player.setPosition(5_000)
    player.removeCommands(COMMAND_SEEK_BACK)

    composeRule.setContent { SeekBackButton(player, Modifier.testTag("seekBackButton")) }

    composeRule.onNodeWithTag("seekBackButton").performClick()

    composeRule.onNodeWithTag("seekBackButton").assertIsNotEnabled()
    assertThat(player.currentPosition).isEqualTo(5_000)
  }

  @Test
  fun customizeContentDescription() {
    val player = TestSimpleBasePlayer()

    composeRule.setContent {
      SeekBackButton(player, Modifier.testTag("seekBackButton"), contentDescription = { "Go Back" })
    }

    composeRule.onNodeWithTag("seekBackButton").assertContentDescriptionEquals("Go Back")
  }

  @Test
  fun customizeOnClick() {
    val player =
      TestSimpleBasePlayer(
        playlist =
          listOf(
            MediaItemData.Builder("SingleItem")
              .setDurationUs(10_000_000)
              .setIsSeekable(true)
              .build()
          )
      )
    var onClickCalled = false
    composeRule.setContent {
      SeekBackButton(
        player,
        Modifier.testTag("seekBackButton"),
        onClick = {
          this.onClick()
          onClickCalled = true
        },
      )
    }

    composeRule.onNodeWithTag("seekBackButton").performClick()

    assertThat(onClickCalled).isTrue()
  }
}
