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
package androidx.media3.ui.compose.material3

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.test.utils.FakePlayer
import androidx.media3.ui.compose.material3.buttons.PreviousButton
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [MiniController]. */
@RunWith(AndroidJUnit4::class)
class MiniControllerTest {
  @get:Rule val composeTestRule = createComposeRule()

  private val miniControllerTag = "mini_controller"

  @Test
  fun miniController_initiallyVisible() {
    composeTestRule.setContent {
      MiniController(player = FakePlayer(), modifier = Modifier.testTag(miniControllerTag))
    }

    composeTestRule.onNodeWithTag(miniControllerTag).assertIsDisplayed()
  }

  @Test
  fun miniController_onClick_invokesOnClick() {
    var clicked = false
    composeTestRule.setContent {
      MiniController(
        player = FakePlayer(),
        modifier = Modifier.testTag(miniControllerTag),
        onClick = { clicked = true },
      )
    }
    composeTestRule.onNodeWithTag(miniControllerTag).performClick()

    assertThat(clicked).isTrue()
  }

  @Test
  fun miniController_customPlayerControls_invokesCustomPlayerControls() {
    composeTestRule.setContent {
      MiniController(
        player = FakePlayer(),
        modifier = Modifier.testTag(miniControllerTag),
        playerControls = { PreviousButton(it, modifier = Modifier.testTag("previous_button")) },
      )
    }
    composeTestRule.onNodeWithTag("previous_button").assertIsDisplayed()
  }

  @Test
  fun miniController_displaysTitleAndArtist() {
    val player = FakePlayer()
    val mediaMetadata =
      MediaMetadata.Builder().setTitle("Sample Title").setArtist("Sample Artist").build()
    val mediaItem = MediaItem.Builder().setMediaMetadata(mediaMetadata).build()
    player.setMediaItem(mediaItem)

    composeTestRule.setContent { MiniController(player = player) }
    composeTestRule.onNodeWithText("Sample Title").assertIsDisplayed()
    composeTestRule.onNodeWithText("Sample Artist").assertIsDisplayed()
  }
}
