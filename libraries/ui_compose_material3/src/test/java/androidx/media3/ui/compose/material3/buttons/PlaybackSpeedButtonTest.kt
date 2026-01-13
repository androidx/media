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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [PlaybackSpeedToggleButton]. */
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
}
