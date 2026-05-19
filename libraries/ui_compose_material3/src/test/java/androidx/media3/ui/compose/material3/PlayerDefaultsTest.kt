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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [PlayerDefaults]. */
@RunWith(AndroidJUnit4::class)
class PlayerDefaultsTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val bottomControlsTag = "bottom_controls"
  private val customSliderTag = "custom_slider"
  private val customContentTag = "custom_content"

  @Test
  fun bottomControls_visibleTrue_isDisplayed() {
    composeTestRule.setContent {
      PlayerDefaults.BottomControls(
        player = FakePlayer(),
        visible = true,
        modifier = Modifier.testTag(bottomControlsTag),
      )
    }

    composeTestRule.onNodeWithTag(bottomControlsTag).assertIsDisplayed()
  }

  @Test
  fun bottomControls_visibleFalse_isNotDisplayed() {
    composeTestRule.setContent {
      PlayerDefaults.BottomControls(
        player = FakePlayer(),
        visible = false,
        modifier = Modifier.testTag(bottomControlsTag),
      )
    }

    composeTestRule.onNodeWithTag(bottomControlsTag).assertDoesNotExist()
  }

  @Test
  fun bottomControls_customSlider_isDisplayed() {
    composeTestRule.setContent {
      PlayerDefaults.BottomControls(
        player = FakePlayer(),
        visible = true,
        progressSlider = { Box(Modifier.size(100.dp).testTag(customSliderTag)) },
      )
    }

    composeTestRule.onNodeWithTag(customSliderTag).assertIsDisplayed()
  }

  @Test
  fun bottomControls_customContent_isDisplayed() {
    composeTestRule.setContent {
      PlayerDefaults.BottomControls(
        player = FakePlayer(),
        visible = true,
        above = { Box(Modifier.size(100.dp).testTag(customContentTag)) },
      )
    }

    composeTestRule.onNodeWithTag(customContentTag).assertIsDisplayed()
  }

  @Test
  fun bottomControls_passesPlayerToSlots() {
    val player = FakePlayer()
    composeTestRule.setContent {
      PlayerDefaults.BottomControls(
        player = player,
        visible = true,
        progressSlider = { p ->
          if (p == player) {
            BasicText("Slider received player")
          }
        },
        below = { p ->
          if (p == player) {
            BasicText("Content received player")
          }
        },
      )
    }

    composeTestRule.onNodeWithText("Slider received player").assertIsDisplayed()
    composeTestRule.onNodeWithText("Content received player").assertIsDisplayed()
  }
}
