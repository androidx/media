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

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.media3.test.utils.FakePlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [PlayerDefaults]. */
@RunWith(AndroidJUnit4::class)
class PlayerDefaultsTest {

  @get:Rule val composeTestRule = createComposeRule()
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val topControlsTag = "top_controls"
  private val topControlsContentTag = "top_controls_content"

  private val centerControlsTag = "center_controls"
  private val customButtonTag = "custom_button"

  private val bottomControlsTag = "bottom_controls"
  private val customSliderTag = "custom_slider"
  private val customContentTag = "custom_content"

  @Test
  fun topControls_visibleTrue_isDisplayed() {
    composeTestRule.setContent {
      PlayerDefaults.TopControls(
        player = FakePlayer(),
        visible = true,
        modifier = Modifier.testTag(topControlsTag),
        content = { Box(Modifier.size(100.dp).testTag(topControlsContentTag)) },
      )
    }

    composeTestRule.onNodeWithTag(topControlsContentTag).assertIsDisplayed()
  }

  @Test
  fun topControls_visibleFalse_isNotDisplayed() {
    composeTestRule.setContent {
      PlayerDefaults.TopControls(
        player = FakePlayer(),
        visible = false,
        modifier = Modifier.testTag(topControlsTag),
        content = { Box(Modifier.size(100.dp).testTag(topControlsTag)) },
      )
    }

    composeTestRule.onNodeWithTag(topControlsTag).assertDoesNotExist()
  }

  @Test
  fun topControls_passesPlayerToSlots() {
    val player = FakePlayer()
    composeTestRule.setContent {
      PlayerDefaults.TopControls(
        player = player,
        visible = true,
        content = { p ->
          if (p == player) {
            BasicText("Content received player")
          }
        },
      )
    }

    // Verify that the slot lambda was invoked with the exact player instance
    composeTestRule.onNodeWithText("Content received player").assertIsDisplayed()
  }

  @Test
  fun centerControls_visibleTrue_isDisplayed() {
    composeTestRule.setContent {
      PlayerDefaults.CenterControls(
        player = FakePlayer(),
        visible = true,
        modifier = Modifier.size(100.dp).testTag(centerControlsTag),
      )
    }

    composeTestRule.onNodeWithTag(centerControlsTag).assertIsDisplayed()
  }

  @Test
  fun centerControls_visibleFalse_isNotDisplayed() {
    composeTestRule.setContent {
      PlayerDefaults.CenterControls(
        player = FakePlayer(),
        visible = false,
        modifier = Modifier.size(100.dp).testTag(centerControlsTag),
      )
    }

    composeTestRule.onNodeWithTag(centerControlsTag).assertDoesNotExist()
  }

  @Test
  fun centerControls_defaultContent_displaysAllButtons() {
    val seekForwardDesc =
      context.resources.getQuantityString(R.plurals.seek_forward_by_amount_button, 15)
    val seekBackwardDesc =
      context.resources.getQuantityString(R.plurals.seek_back_by_amount_button, 10)
    val player =
      FakePlayer().apply {
        setSeekForwardIncrementMs(15_000L)
        setSeekBackIncrementMs(10_000L)
      }
    composeTestRule.setContent {
      PlayerDefaults.CenterControls(
        player = player,
        modifier = Modifier.size(400.dp),
        visible = true,
      )
    }

    composeTestRule.onNode(hasContentDescription(seekBackwardDesc)).assertIsDisplayed()
    composeTestRule.onNode(hasContentDescription(seekForwardDesc)).assertIsDisplayed()
    composeTestRule
      .onNode(hasContentDescription(context.getString(R.string.playpause_button_play)))
      .assertIsDisplayed()
    composeTestRule
      .onNode(hasContentDescription(context.getString(R.string.previous_button)))
      .assertIsDisplayed()
    composeTestRule
      .onNode(hasContentDescription(context.getString(R.string.next_button)))
      .assertIsDisplayed()
  }

  @Test
  fun centerControls_customSlot_overridesDefaultButton() {
    composeTestRule.setContent {
      PlayerDefaults.CenterControls(
        player = FakePlayer(),
        visible = true,
        central = { Box(Modifier.size(100.dp).testTag(customButtonTag)) },
      )
    }

    composeTestRule.onNodeWithTag(customButtonTag).assertIsDisplayed()
    composeTestRule
      .onNode(hasContentDescription(context.getString(R.string.playpause_button_play)))
      .assertDoesNotExist()
  }

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
