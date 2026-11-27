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
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [NextButton]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NextButtonTest {

  @Test
  fun onClick_callsNext() = runComposeUiTest {
    val player =
      FakePlayer(
        playlist =
          listOf(
            MediaItemData.Builder("First").setDurationUs(1_000_000L).build(),
            MediaItemData.Builder("Second").setDurationUs(2_000_000L).build(),
          )
      )
    setContent { NextButton(player, Modifier.testTag("nextButton")) }

    onNodeWithTag("nextButton").performClick()

    assertThat(player.currentMediaItemIndex).isEqualTo(1)
  }

  @Test
  fun onClick_commandNotAvailable_buttonDisabledClickNotPerformed() = runComposeUiTest {
    val player =
      FakePlayer(
        playlist =
          listOf(
            MediaItemData.Builder("First").setDurationUs(1_000_000L).build(),
            MediaItemData.Builder("Second").setDurationUs(2_000_000L).build(),
          )
      )
    player.removeCommands(COMMAND_SEEK_TO_NEXT)

    setContent { NextButton(player, Modifier.testTag("nextButton")) }

    onNodeWithTag("nextButton").performClick()

    onNodeWithTag("nextButton").assertIsNotEnabled()
    assertThat(player.currentMediaItemIndex).isEqualTo(0)
  }

  @Test
  fun customizeContentDescription() = runComposeUiTest {
    val player = FakePlayer()

    setContent {
      NextButton(player, Modifier.testTag("nextButton"), contentDescription = { "Go next" })
    }

    onNodeWithTag("nextButton").assertContentDescriptionEquals("Go next")
  }

  @Test
  fun customizeOnClick() = runComposeUiTest {
    val player =
      FakePlayer(
        playlist =
          listOf(
            MediaItemData.Builder("First").setDurationUs(1_000_000L).build(),
            MediaItemData.Builder("Second").setDurationUs(2_000_000L).build(),
          )
      )
    var onClickCalled = false
    setContent {
      NextButton(
        player,
        Modifier.testTag("nextButton"),
        onClick = {
          this.onClick()
          onClickCalled = true
        },
      )
    }

    onNodeWithTag("nextButton").performClick()

    assertThat(onClickCalled).isTrue()
  }
}
