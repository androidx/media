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

package androidx.media3.ui.compose.material3.buttons

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.media3.common.Player
import androidx.media3.test.utils.FakePlayer
import androidx.media3.ui.compose.material3.R
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [PlaybackSpeedToggleButton] and [PlaybackSpeedBottomSheetButton]. */
@RunWith(AndroidJUnit4::class)
class PlaybackSpeedButtonTest {

  @get:Rule val composeRule = createComposeRule()

  private lateinit var context: Context

  @Test
  fun playbackSpeedToggleButton_noCommandAvailable_disabled() {
    val player = FakePlayer()
    player.removeCommands(Player.COMMAND_SET_SPEED_AND_PITCH)
    composeRule.setContent { PlaybackSpeedToggleButton(player, Modifier.testTag("toggle")) }

    composeRule.onNodeWithTag("toggle").assertIsNotEnabled()
  }

  @Test
  fun playbackSpeedToggleButton_cyclesThroughSpeeds() {
    val player = FakePlayer()
    val speeds = listOf(1.0f, 2.0f)
    composeRule.setContent {
      PlaybackSpeedToggleButton(player, Modifier.testTag("toggle"), speedSelection = speeds)
    }

    assertThat(player.playbackParameters.speed).isEqualTo(1.0f)

    composeRule.onNodeWithTag("toggle").performClick()
    assertThat(player.playbackParameters.speed).isEqualTo(2.0f)

    composeRule.onNodeWithTag("toggle").performClick()
    assertThat(player.playbackParameters.speed).isEqualTo(1.0f) // Wraps around
  }

  @Test
  fun playbackSpeedToggleButton_initialSpeedNotInSelection_togglesToNextGreater() {
    val player = FakePlayer()
    player.setPlaybackSpeed(1.1f)
    composeRule.setContent {
      PlaybackSpeedToggleButton(
        player,
        Modifier.testTag("toggle"),
        speedSelection = listOf(0.5f, 1.0f, 1.5f, 2.0f),
      )
    }

    composeRule.onNodeWithTag("toggle").performClick()
    assertThat(player.playbackParameters.speed).isEqualTo(1.5f) // Next greater speed

    composeRule.onNodeWithTag("toggle").performClick()
    assertThat(player.playbackParameters.speed).isEqualTo(2.0f)

    composeRule.onNodeWithTag("toggle").performClick()
    assertThat(player.playbackParameters.speed).isEqualTo(0.5f) // Wraps around
  }

  @Test
  fun playbackSpeedButtonToBottomSheet_click_opensBottomSheetAndUpdatesSpeed() {
    val player = FakePlayer()
    composeRule.setContent {
      context = LocalContext.current
      PlaybackSpeedBottomSheetButton(player, Modifier.testTag("entryButton"))
    }
    val originalSpeed = player.playbackParameters.speed
    val decreaseSpeedDescription = context.getString(R.string.playback_speed_decrease, 0.05f)
    val increaseSpeedDescription = context.getString(R.string.playback_speed_increase, 0.05f)

    // Open the bottom sheet
    composeRule.onNodeWithTag("entryButton").performClick()
    composeRule.onNodeWithContentDescription(decreaseSpeedDescription).assertIsDisplayed()
    composeRule.onNodeWithContentDescription(increaseSpeedDescription).assertIsDisplayed()

    // Click '+' button (adds 0.05 by default)
    composeRule.onNodeWithContentDescription(increaseSpeedDescription).performClick()
    assertThat(player.playbackParameters.speed).isEqualTo(originalSpeed + 0.05f)
  }

  @OptIn(ExperimentalMaterial3Api::class)
  @Test
  fun playbackSpeedButton_strippedDown_clickOpensOnlyPresets() {
    val player = FakePlayer()
    composeRule.setContent {
      context = LocalContext.current
      PlaybackSpeedBottomSheetButton(player, Modifier.testTag("entryButton")) { onDismissRequest ->
        PlaybackSpeedBottomSheet(
          this,
          modifier = Modifier.testTag("sheet"),
          header = {},
          controls = {},
          presetSpeeds = listOf(0.25f, 1.0f, 2.0f),
          onDismissRequest = onDismissRequest,
        )
      }
    }
    val decreaseSpeedDescription = context.getString(R.string.playback_speed_decrease, 0.05f)
    val increaseSpeedDescription = context.getString(R.string.playback_speed_increase, 0.05f)

    // Open the bottom sheet with no controls
    composeRule.onNodeWithTag("entryButton").performClick()
    composeRule.onNodeWithTag("sheet").assertExists()
    composeRule.onNodeWithContentDescription(decreaseSpeedDescription).assertDoesNotExist()
    composeRule.onNodeWithContentDescription(increaseSpeedDescription).assertDoesNotExist()

    // Click a value from presetSpeeds
    composeRule.onNodeWithText("2.0", substring = true).performClick()
    assertThat(player.playbackParameters.speed).isEqualTo(2.0f)

    // The sheet is dismissed after preset selection is called
    composeRule.onNodeWithTag("sheet").assertDoesNotExist()
  }

  @OptIn(ExperimentalMaterial3Api::class)
  @Test
  fun playbackSpeedBottomSheet_adjusterButtons_disabledWhenOutOfRange() {
    val player = FakePlayer()

    composeRule.setContent {
      context = LocalContext.current
      PlaybackSpeedBottomSheetButton(player, Modifier.testTag("entryButton")) { onDismissRequest ->
        PlaybackSpeedBottomSheet(
          this,
          modifier = Modifier.testTag("sheet"),
          onDismissRequest = onDismissRequest,
          speedRange = 0.5f..1.5f,
          speedStep = 0.1f,
        )
      }
    }

    val decreaseSpeedDescription = context.getString(R.string.playback_speed_decrease, 0.1f)
    val increaseSpeedDescription = context.getString(R.string.playback_speed_increase, 0.1f)

    // Speed is below the range of the slider
    player.setPlaybackSpeed(0.4f)
    composeRule.onNodeWithTag("entryButton").performClick()
    composeRule.onNodeWithContentDescription(decreaseSpeedDescription).assertIsNotEnabled()
    composeRule.onNodeWithContentDescription(increaseSpeedDescription).assertIsEnabled()
    composeRule.onNodeWithTag("sheet").performTouchInput { swipeDown() } // Dismiss sheet

    // Speed is above the range of the slider
    player.setPlaybackSpeed(1.6f)
    composeRule.onNodeWithTag("entryButton").performClick()
    composeRule.onNodeWithContentDescription(decreaseSpeedDescription).assertIsEnabled()
    composeRule.onNodeWithContentDescription(increaseSpeedDescription).assertIsNotEnabled()
    composeRule.onNodeWithTag("sheet").performTouchInput { swipeDown() } // Dismiss sheet
  }
}
