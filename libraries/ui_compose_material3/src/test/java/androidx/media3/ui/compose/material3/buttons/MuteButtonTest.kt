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
import androidx.media3.common.Player.COMMAND_SET_VOLUME
import androidx.media3.test.utils.TestSimpleBasePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [MuteButton]. */
@RunWith(AndroidJUnit4::class)
class MuteButtonTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun onClick_mutesPlayer() {
    val player = TestSimpleBasePlayer()
    composeRule.setContent { MuteButton(player, Modifier.testTag("muteButton")) }
    assertThat(player.volume).isEqualTo(1f)

    composeRule.onNodeWithTag("muteButton").performClick()

    assertThat(player.volume).isEqualTo(0f)
  }

  @Test
  fun onClick_unmutesPlayerToDefaultValue() {
    val player = TestSimpleBasePlayer()
    player.volume = 0f
    composeRule.setContent { MuteButton(player, Modifier.testTag("muteButton")) }

    composeRule.onNodeWithTag("muteButton").performClick()

    assertThat(player.volume).isEqualTo(1f)
  }

  @Test
  fun onClick_unmutesPlayerToOriginalValue() {
    val player = TestSimpleBasePlayer()
    player.volume = 0.5f

    composeRule.setContent { MuteButton(player, Modifier.testTag("muteButton")) }

    composeRule.onNodeWithTag("muteButton").performClick()

    assertThat(player.volume).isEqualTo(0f)

    composeRule.onNodeWithTag("muteButton").performClick()

    assertThat(player.volume).isEqualTo(0.5f)
  }

  @Test
  fun onClick_commandNotAvailable_buttonDisabledClickNotPerformed() {
    val player = TestSimpleBasePlayer()
    player.removeCommands(COMMAND_SET_VOLUME)
    composeRule.setContent { MuteButton(player, Modifier.testTag("muteButton")) }

    composeRule.onNodeWithTag("muteButton").performClick()

    composeRule.onNodeWithTag("muteButton").assertIsNotEnabled()
    assertThat(player.volume).isEqualTo(1f)
  }

  @Test
  fun customizeContentDescription() {
    val player = TestSimpleBasePlayer()
    composeRule.setContent {
      MuteButton(
        player,
        Modifier.testTag("muteButton"),
        contentDescription = { if (showMuted) "shh" else "aah" },
      )
    }

    composeRule.onNodeWithTag("muteButton").assertContentDescriptionEquals("aah")

    composeRule.onNodeWithTag("muteButton").performClick()

    composeRule.onNodeWithTag("muteButton").assertContentDescriptionEquals("shh")
  }

  @Test
  fun customizeOnClick() {
    val player = TestSimpleBasePlayer()
    var onClickCalled = false
    composeRule.setContent {
      MuteButton(
        player,
        Modifier.testTag("muteButton"),
        onClick = {
          this.onClick()
          onClickCalled = true
        },
      )
    }

    composeRule.onNodeWithTag("muteButton").performClick()

    assertThat(onClickCalled).isTrue()
  }
}
