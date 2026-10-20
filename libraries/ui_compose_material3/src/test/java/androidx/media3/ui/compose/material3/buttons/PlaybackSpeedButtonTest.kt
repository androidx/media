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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [PlaybackSpeedToggleButton]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackSpeedButtonTest {

  private lateinit var context: Context

  @Test
  fun playbackSpeedToggleButton_noCommandAvailable_disabled() = runComposeUiTest {
    val player = FakePlayer()
    player.removeCommands(Player.COMMAND_SET_SPEED_AND_PITCH)
    setContent { PlaybackSpeedToggleButton(player, Modifier.testTag("toggle")) }

    onNodeWithTag("toggle").assertIsNotEnabled()
  }

  @Test
  fun playbackSpeedToggleButton_cyclesThroughSpeeds() = runComposeUiTest {
    val player = FakePlayer()
    val speeds = listOf(1.0f, 2.0f)
    setContent {
      PlaybackSpeedToggleButton(player, Modifier.testTag("toggle"), speedSelection = speeds)
    }

    assertThat(player.playbackParameters.speed).isEqualTo(1.0f)

    onNodeWithTag("toggle").performClick()
    assertThat(player.playbackParameters.speed).isEqualTo(2.0f)

    onNodeWithTag("toggle").performClick()
    assertThat(player.playbackParameters.speed).isEqualTo(1.0f) // Wraps around
  }

  @Test
  fun playbackSpeedToggleButton_initialSpeedNotInSelection_togglesToNextGreater() =
    runComposeUiTest {
      val player = FakePlayer()
      player.setPlaybackSpeed(1.1f)
      setContent {
        PlaybackSpeedToggleButton(
          player,
          Modifier.testTag("toggle"),
          speedSelection = listOf(0.5f, 1.0f, 1.5f, 2.0f),
        )
      }

      onNodeWithTag("toggle").performClick()
      assertThat(player.playbackParameters.speed).isEqualTo(1.5f) // Next greater speed

      onNodeWithTag("toggle").performClick()
      assertThat(player.playbackParameters.speed).isEqualTo(2.0f)

      onNodeWithTag("toggle").performClick()
      assertThat(player.playbackParameters.speed).isEqualTo(0.5f) // Wraps around
    }

  @Test
  fun playbackSpeedToggleButton_customParametersAndContent_emitsInteractionsAndRendersContent() =
    runComposeUiTest {
      val player = FakePlayer()
      val interactions = mutableListOf<Interaction>()
      setContent {
        val interactionSource = remember { MutableInteractionSource() }
        LaunchedEffect(interactionSource) {
          interactionSource.interactions.collect { interactions.add(it) }
        }
        PlaybackSpeedToggleButton(
          player,
          Modifier.testTag("toggle"),
          shape = RectangleShape,
          elevation = ButtonDefaults.buttonElevation(),
          border = BorderStroke(1.dp, Color.Red),
          contentPadding = PaddingValues(4.dp),
          interactionSource = interactionSource,
        ) {
          Text("Speed=${playbackSpeed}x")
        }
      }

      onNodeWithTag("toggle").assertTextEquals("Speed=1.0x")

      onNodeWithTag("toggle").performClick()

      assertThat(player.playbackParameters.speed).isEqualTo(1.25f)
      onNodeWithTag("toggle").assertTextEquals("Speed=1.25x")
      assertThat(interactions.filterIsInstance<PressInteraction.Release>()).isNotEmpty()
    }

  @Test
  fun playbackSpeedToggleButton_customOnClick_overridesAndCustomizesBehavior() = runComposeUiTest {
    val player = FakePlayer()
    var customOnClickCalled = false
    setContent {
      PlaybackSpeedToggleButton(
        player,
        Modifier.testTag("toggle"),
        onClick = {
          customOnClickCalled = true
          updatePlaybackSpeed(1.75f)
        },
      )
    }

    onNodeWithTag("toggle").performClick()

    assertThat(customOnClickCalled).isTrue()
    assertThat(player.playbackParameters.speed).isEqualTo(1.75f)
  }

  @Test
  fun playbackSpeedToggleButton_customOnClick_runsCustomLogicAndCyclesSpeed() = runComposeUiTest {
    val player = FakePlayer()
    val speeds = listOf(1.0f, 2.0f)
    var customOnClickCalled = false
    setContent {
      PlaybackSpeedToggleButton(
        player,
        Modifier.testTag("toggle"),
        speedSelection = speeds,
        onClick = {
          customOnClickCalled = true
          val nextSpeed = speeds.firstOrNull { it > playbackSpeed } ?: speeds.first()
          updatePlaybackSpeed(nextSpeed)
        },
      )
    }

    onNodeWithTag("toggle").performClick()

    assertThat(customOnClickCalled).isTrue()
    assertThat(player.playbackParameters.speed).isEqualTo(2.0f)
  }
}
