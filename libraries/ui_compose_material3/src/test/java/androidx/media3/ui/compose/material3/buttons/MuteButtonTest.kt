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
import androidx.media3.common.Player.COMMAND_SET_VOLUME
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [MuteButton]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MuteButtonTest {

  @Test
  fun onClick_mutesPlayer() = runComposeUiTest {
    val player = FakePlayer()
    setContent { MuteButton(player, Modifier.testTag("muteButton")) }
    assertThat(player.volume).isEqualTo(1f)

    onNodeWithTag("muteButton").performClick()

    assertThat(player.volume).isEqualTo(0f)
  }

  @Test
  fun onClick_unmutesPlayerToDefaultValue() = runComposeUiTest {
    val player = FakePlayer()
    player.volume = 0f
    setContent { MuteButton(player, Modifier.testTag("muteButton")) }

    onNodeWithTag("muteButton").performClick()

    assertThat(player.volume).isEqualTo(1f)
  }

  @Test
  fun onClick_unmutesPlayerToOriginalValue() = runComposeUiTest {
    val player = FakePlayer()
    player.volume = 0.5f

    setContent { MuteButton(player, Modifier.testTag("muteButton")) }

    onNodeWithTag("muteButton").performClick()

    assertThat(player.volume).isEqualTo(0f)

    onNodeWithTag("muteButton").performClick()

    assertThat(player.volume).isEqualTo(0.5f)
  }

  @Test
  fun onClick_commandNotAvailable_buttonDisabledClickNotPerformed() = runComposeUiTest {
    val player = FakePlayer()
    player.removeCommands(COMMAND_SET_VOLUME)
    setContent { MuteButton(player, Modifier.testTag("muteButton")) }

    onNodeWithTag("muteButton").performClick()

    onNodeWithTag("muteButton").assertIsNotEnabled()
    assertThat(player.volume).isEqualTo(1f)
  }

  @Test
  fun customizeContentDescription() = runComposeUiTest {
    val player = FakePlayer()
    setContent {
      MuteButton(
        player,
        Modifier.testTag("muteButton"),
        contentDescription = { if (showMuted) "shh" else "aah" },
      )
    }

    onNodeWithTag("muteButton").assertContentDescriptionEquals("aah")

    onNodeWithTag("muteButton").performClick()

    onNodeWithTag("muteButton").assertContentDescriptionEquals("shh")
  }

  @Test
  fun customizeOnClick() = runComposeUiTest {
    val player = FakePlayer()
    var onClickCalled = false
    setContent {
      MuteButton(
        player,
        Modifier.testTag("muteButton"),
        onClick = {
          this.onClick()
          onClickCalled = true
        },
      )
    }

    onNodeWithTag("muteButton").performClick()

    assertThat(onClickCalled).isTrue()
  }
}
