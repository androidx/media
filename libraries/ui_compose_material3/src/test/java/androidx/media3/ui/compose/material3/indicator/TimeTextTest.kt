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

package androidx.media3.ui.compose.material3.indicator

import androidx.compose.foundation.clickable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [TimeText]. */
@RunWith(AndroidJUnit4::class)
class TimeTextTest {
  private val testDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
  @OptIn(ExperimentalTestApi::class)
  @get:Rule
  val composeTestRule = createComposeRule(testDispatcher)

  @Test
  fun textDisplayed_position() {
    val player =
      FakePlayer(
        playlist =
          listOf(
            SimpleBasePlayer.MediaItemData.Builder("SingleItem").setDurationUs(10_000_000).build()
          )
      )

    composeTestRule.setContent { PositionText(player, Modifier.testTag("position")) }

    composeTestRule.onNodeWithTag("position").assertTextEquals("00:00")
  }

  @Test
  fun textDisplayed_duration() {
    val player =
      FakePlayer(
        playlist =
          listOf(
            SimpleBasePlayer.MediaItemData.Builder("SingleItem").setDurationUs(10_000_000).build()
          )
      )

    composeTestRule.setContent { DurationText(player, Modifier.testTag("duration")) }

    composeTestRule.onNodeWithTag("duration").assertTextEquals("00:10")
  }

  @Test
  fun textDisplayed_remaining() {
    val player =
      FakePlayer(
        playlist =
          listOf(
            SimpleBasePlayer.MediaItemData.Builder("SingleItem").setDurationUs(10_000_000).build()
          )
      )
    player.setPosition(3_000)

    composeTestRule.setContent { RemainingDurationText(player, Modifier.testTag("remaining")) }

    composeTestRule.onNodeWithTag("remaining").assertTextEquals("00:07")
  }

  @Test
  fun textDisplayed_remainingNegative() {
    val player =
      FakePlayer(
        playlist =
          listOf(
            SimpleBasePlayer.MediaItemData.Builder("SingleItem").setDurationUs(10_000_000).build()
          )
      )
    player.setPosition(3_000)

    composeTestRule.setContent {
      RemainingDurationText(player, Modifier.testTag("remaining"), showNegative = true)
    }

    composeTestRule.onNodeWithTag("remaining").assertTextEquals("-00:07")
  }

  @Test
  fun textDisplayed_combined() {
    val player =
      FakePlayer(
        playlist =
          listOf(
            SimpleBasePlayer.MediaItemData.Builder("SingleItem").setDurationUs(10_000_000).build()
          )
      )
    player.setPosition(3_000)

    composeTestRule.setContent {
      PositionAndDurationText(player, Modifier.testTag("combined"), separator = " / ")
    }

    composeTestRule.onNodeWithTag("combined").assertTextEquals("00:03 / 00:10")
  }

  @Test
  fun textDisplayed_combined_customizeSeparator() =
    runTest(testDispatcher) {
      val player =
        FakePlayer(
          playlist =
            listOf(
              SimpleBasePlayer.MediaItemData.Builder("SingleItem").setDurationUs(10_000_000).build()
            )
        )
      player.setPosition(3_000)

      lateinit var separator: MutableState<String>
      composeTestRule.setContent {
        separator = remember { mutableStateOf(" / ") }
        PositionAndDurationText(player, Modifier.testTag("combined"), separator = separator.value)
      }

      composeTestRule.onNodeWithTag("combined").assertTextEquals("00:03 / 00:10")

      separator.value = " - "
      composeTestRule.waitForIdle()

      composeTestRule.onNodeWithTag("combined").assertTextEquals("00:03 - 00:10")
    }

  @Test
  fun textDisplayed_combined_durationBecomesKnown() =
    runTest(testDispatcher) {
      val player =
        FakePlayer(playlist = listOf(SimpleBasePlayer.MediaItemData.Builder("SingleItem").build()))
      composeTestRule.setContent { PositionAndDurationText(player, Modifier.testTag("combined")) }
      composeTestRule.onNodeWithTag("combined").assertTextEquals("00:00 / 00:00")

      player.setDuration("SingleItem", 12_340)
      testScheduler.runCurrent()

      composeTestRule.onNodeWithTag("combined").assertTextEquals("00:00 / 00:12")
    }

  @Test
  fun textDisplayed_positionMoves() =
    runTest(testDispatcher) {
      val player =
        FakePlayer(
          playbackState = Player.STATE_READY,
          playWhenReady = true,
          playlist =
            listOf(
              SimpleBasePlayer.MediaItemData.Builder("SingleItem").setDurationUs(10_000_000).build()
            ),
        )
      player.setPositionSupplierDrivenBy(testDispatcher.scheduler)
      composeTestRule.setContent {
        PositionAndDurationText(player, Modifier.testTag("combined"), scope = backgroundScope)
      }
      composeTestRule.onNodeWithTag("combined").assertTextEquals("00:00 / 00:10")

      testScheduler.advanceTimeBy(2345.milliseconds)
      testScheduler.runCurrent()

      composeTestRule.onNodeWithTag("combined").assertTextEquals("00:02 / 00:10")
    }

  @Test
  fun textDisplayed_onClick_togglesFromPositionToRemaining() =
    runTest(testDispatcher) {
      val player =
        FakePlayer(
          playlist =
            listOf(
              SimpleBasePlayer.MediaItemData.Builder("SingleItem").setDurationUs(10_000_000).build()
            )
        )
      player.setPosition(3_000)
      composeTestRule.setContent {
        var showRemaining by remember { mutableStateOf(false) }
        TimeText(
          player = player,
          timeFormat = if (showRemaining) TimeFormat.remaining() else TimeFormat.position(),
          modifier =
            Modifier.testTag(if (showRemaining) "remaining" else "position").clickable {
              showRemaining = !showRemaining
            },
        )
      }
      composeTestRule.onNodeWithTag("position").assertTextEquals("00:03")

      composeTestRule.onNodeWithTag("position").performClick()

      composeTestRule.onNodeWithTag("position").assertDoesNotExist()
      composeTestRule.onNodeWithTag("remaining").assertTextEquals("00:07")
    }
}
