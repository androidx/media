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

package androidx.media3.ui.compose.material3.indicator

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private const val INDICATOR_TAG = "indicator"

/** Unit test for [LinearProgressIndicator]. */
@RunWith(AndroidJUnit4::class)
class LinearProgressIndicatorTest {
  private val testDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
  @OptIn(ExperimentalTestApi::class)
  @get:Rule
  val composeTestRule = createComposeRule(testDispatcher)

  @Test
  fun indicator_nonDefaultPosition_displaysProgressProportionally() {
    runTest(testDispatcher) {
      val player =
        FakePlayer(
          playlist =
            listOf(
              SimpleBasePlayer.MediaItemData.Builder("mediaItem")
                .setIsSeekable(true)
                .setDurationUs(10_000_000)
                .build()
            )
        )
      player.setPosition(3_000)

      composeTestRule.setContent {
        LinearProgressIndicator(player, Modifier.testTag(INDICATOR_TAG))
      }

      assertThatIndicatorValueEquals(0.3f)
    }
  }

  @Config(qualifiers = "w320dp-h470dp")
  @Test
  fun indicator_timePasses_displaysCorrectPosition() =
    runTest(testDispatcher) {
      val player =
        FakePlayer(
          playbackState = STATE_READY,
          playWhenReady = true,
          playlist =
            listOf(
              SimpleBasePlayer.MediaItemData.Builder("SingleItem")
                .setDurationUs(50_000_000)
                .setIsSeekable(true)
                .build()
            ),
        )
      player.setPositionSupplierDrivenBy(testDispatcher.scheduler)
      composeTestRule.setContent {
        LinearProgressIndicator(player, Modifier.testTag(INDICATOR_TAG), scope = backgroundScope)
      }

      assertThat(player.currentPosition).isEqualTo(16) // first recomposition
      val indicatorWidth =
        composeTestRule.onNodeWithTag(INDICATOR_TAG).fetchSemanticsNode().boundsInWindow.width
      assertThat(indicatorWidth).isEqualTo(320)

      composeTestRule.runOnUiThread { testScheduler.advanceTimeBy(44.milliseconds) }
      composeTestRule.runOnIdle {
        assertThat(player.currentPosition).isEqualTo(60)
        assertThatIndicatorValueEquals(0f)
      }

      composeTestRule.runOnUiThread { testScheduler.advanceTimeBy(40.milliseconds) }
      composeTestRule.runOnIdle {
        assertThat(player.currentPosition).isEqualTo(100)
        assertThatIndicatorValueEquals(1f / indicatorWidth)
      }

      composeTestRule.runOnUiThread { testScheduler.advanceTimeBy(220.milliseconds) }
      composeTestRule.runOnIdle {
        assertThat(player.currentPosition).isEqualTo(320)
        assertThatIndicatorValueEquals(2f / indicatorWidth)
      }

      composeTestRule.runOnUiThread { testScheduler.advanceTimeBy(220.milliseconds) }
      composeTestRule.runOnIdle {
        assertThat(player.currentPosition).isEqualTo(540)
        assertThatIndicatorValueEquals(3 / indicatorWidth)
      }

      // The last 4 pixels are: 49375   -> 49531.25 -> 49687.5 -> 49843.75  ->  50000
      //                          |-----x-----|-----x-----|-----x-----|-----x-----|
      //                          a-----------|-----------|--b-----c--|--d----e---|
      testScheduler.advanceTimeBy((49375 - 540).milliseconds)
      composeTestRule.runOnIdle {
        assertThat(player.currentPosition).isEqualTo(49375) // point a
        assertThatIndicatorValueEquals(1f - 4 / indicatorWidth) // right on the tick
      }

      testScheduler.advanceTimeBy((315).milliseconds)
      composeTestRule.runOnIdle {
        assertThat(player.currentPosition).isEqualTo(49690) // point b
        assertThatIndicatorValueEquals(1f - 2 / indicatorWidth) // snap to third last tick
      }

      testScheduler.advanceTimeBy((150).milliseconds)
      composeTestRule.runOnIdle {
        assertThat(player.currentPosition).isEqualTo(49840) // point c
        assertThatIndicatorValueEquals(1f - 1 / indicatorWidth) // snap to penultimate tick
      }

      testScheduler.advanceTimeBy(40.milliseconds)
      composeTestRule.runOnIdle {
        assertThat(player.currentPosition).isEqualTo(49880) // point d
        assertThatIndicatorValueEquals(1f - 1 / indicatorWidth) // snap to penultimate tick, like c
      }

      testScheduler.advanceTimeBy((100).milliseconds) // final recomposition
      composeTestRule.runOnIdle {
        assertThat(player.currentPosition).isEqualTo(49980) // point e
        assertThatIndicatorValueEquals(1f)
      }
    }

  @Test
  fun indicator_receivesNewPlayer_updatesCorrectly() =
    runTest(testDispatcher) {
      val player1 =
        FakePlayer(
          playlist =
            listOf(
              SimpleBasePlayer.MediaItemData.Builder("Item1")
                .setDurationUs(10_000_000)
                .setIsSeekable(true)
                .build()
            )
        )
      player1.setPosition(2_000)
      val player2 =
        FakePlayer(
          playlist =
            listOf(
              SimpleBasePlayer.MediaItemData.Builder("Item2")
                .setDurationUs(20_000_000)
                .setIsSeekable(true)
                .build()
            )
        )
      player2.setPosition(15_000)
      val currentPlayer = mutableStateOf(player1)
      composeTestRule.setContent {
        LinearProgressIndicator(currentPlayer.value, Modifier.testTag(INDICATOR_TAG))
      }

      assertThatIndicatorValueEquals(0.2f)

      currentPlayer.value = player2
      composeTestRule.waitForIdle()

      assertThatIndicatorValueEquals(0.75f)
    }

  private fun assertThatIndicatorValueEquals(value: Float) {
    composeTestRule
      .onNodeWithTag(INDICATOR_TAG)
      .assertRangeInfoEquals(ProgressBarRangeInfo(current = value, range = 0f..1f, steps = 0))
  }
}
