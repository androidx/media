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

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
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

private const val SLIDER_TAG = "slider"

/** Unit test for [ProgressSlider]. */
@RunWith(AndroidJUnit4::class)
class ProgressSliderTest {
  private val testDispatcher = StandardTestDispatcher(TestCoroutineScheduler())
  @OptIn(ExperimentalTestApi::class)
  @get:Rule
  val composeTestRule = createComposeRule(testDispatcher)

  @Test
  fun slider_whenItemIsNotSeekable_becomesDisabled() =
    runTest(testDispatcher) {
      val enabledItem =
        SimpleBasePlayer.MediaItemData.Builder("seekable")
          .setIsSeekable(true)
          .setDurationUs(10_000_000)
          .build()
      val disabledItem =
        SimpleBasePlayer.MediaItemData.Builder("unseekable")
          .setIsSeekable(false)
          .setDurationUs(10_000_000)
          .build()
      val player = FakePlayer(playlist = listOf(enabledItem))
      composeTestRule.setContent { ProgressSlider(player, Modifier.testTag(SLIDER_TAG)) }
      composeTestRule.onNodeWithTag(SLIDER_TAG).assertIsEnabled()

      player.setMediaItem(disabledItem.mediaItem)
      composeTestRule.waitForIdle() // propagate timeline event

      composeTestRule.onNodeWithTag(SLIDER_TAG).assertIsNotEnabled()
    }

  @Test
  fun slider_whenDurationUnknown_becomesDisabled() =
    runTest(testDispatcher) {
      val durationKnownItem =
        SimpleBasePlayer.MediaItemData.Builder("seekable")
          .setIsSeekable(true)
          .setDurationUs(10_000_000)
          .build()
      val durationUnknownItem =
        SimpleBasePlayer.MediaItemData.Builder("unseekable").setIsSeekable(true).build()
      val player = FakePlayer(playlist = listOf(durationKnownItem))
      composeTestRule.setContent { ProgressSlider(player, Modifier.testTag(SLIDER_TAG)) }
      composeTestRule.onNodeWithTag(SLIDER_TAG).assertIsEnabled()

      player.setMediaItem(durationUnknownItem.mediaItem)
      composeTestRule.waitForIdle() // propagate timeline event

      composeTestRule.onNodeWithTag(SLIDER_TAG).assertIsNotEnabled()
    }

  @Test
  fun slider_nonDefaultPosition_displaysScrubberProportionally() =
    runTest(testDispatcher) {
      val player =
        FakePlayer(
          playlist =
            listOf(
              SimpleBasePlayer.MediaItemData.Builder("SingleItem")
                .setIsSeekable(true)
                .setDurationUs(10_000_000)
                .build()
            )
        )
      player.setPosition(3_000)

      composeTestRule.setContent { ProgressSlider(player, Modifier.testTag(SLIDER_TAG)) }

      composeTestRule.onNodeWithTag(SLIDER_TAG).assertIsEnabled()
      assertThatSliderValueEquals(0.3f)
    }

  @Config(qualifiers = "w320dp-h470dp")
  @Test
  fun slider_timePasses_displaysCorrectPosition() =
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
        ProgressSlider(player, Modifier.testTag(SLIDER_TAG), scope = backgroundScope)
      }
      assertThat(player.currentPosition).isEqualTo(16) // first recomposition
      val sliderWidth =
        composeTestRule.onNodeWithTag(SLIDER_TAG).fetchSemanticsNode().boundsInWindow.width
      assertThat(sliderWidth).isEqualTo(320)

      composeTestRule.runOnUiThread { testScheduler.advanceTimeBy(44.milliseconds) }
      composeTestRule.runOnIdle {
        assertThat(player.currentPosition).isEqualTo(60)
        assertThatSliderValueEquals(0f)
      }

      composeTestRule.runOnUiThread { testScheduler.advanceTimeBy(24.milliseconds) }
      composeTestRule.runOnIdle {
        assertThat(player.currentPosition).isEqualTo(100) // 84 + 16 for recomposition
        assertThatSliderValueEquals(1 / sliderWidth)
      }

      composeTestRule.runOnUiThread { testScheduler.advanceTimeBy(204.milliseconds) }
      composeTestRule.runOnIdle {
        assertThat(player.currentPosition).isEqualTo(320) // 304 + 16 for recomposition
        assertThatSliderValueEquals(2 / sliderWidth)
      }

      composeTestRule.runOnUiThread { testScheduler.advanceTimeBy(204.milliseconds) }
      composeTestRule.runOnIdle {
        assertThat(player.currentPosition).isEqualTo(540) // 524 + 16 for recomposition
        assertThatSliderValueEquals(3 / sliderWidth)
      }

      // The last 4 pixels are: 49375   -> 49531.25 -> 49687.5 -> 49843.75  ->  50000
      //                          |-----x-----|-----x-----|-----x-----|-----x-----|
      //                          a-----------|-----------|--b-----c--|--d----e---|
      testScheduler.advanceTimeBy((49375 - 540 - 16).milliseconds)
      composeTestRule.runOnIdle {
        assertThat(player.currentPosition).isEqualTo(49375) // point a
        assertThatSliderValueEquals(1f - 4 / sliderWidth) // right on the tick
      }

      testScheduler.advanceTimeBy((315 - 16).milliseconds)
      composeTestRule.runOnIdle {
        assertThat(player.currentPosition).isEqualTo(49690) // point b
        assertThatSliderValueEquals(1f - 2 / sliderWidth) // snap to third last tick
      }

      testScheduler.advanceTimeBy((150 - 16).milliseconds)
      composeTestRule.runOnIdle {
        assertThat(player.currentPosition).isEqualTo(49840) // point c
        assertThatSliderValueEquals(1f - 1 / sliderWidth) // snap to penultimate tick
      }

      testScheduler.advanceTimeBy(40.milliseconds) // no "-16", no recomposition
      composeTestRule.runOnIdle {
        assertThat(player.currentPosition).isEqualTo(49880) // point d
        assertThatSliderValueEquals(1f - 1 / sliderWidth) // snap to penultimate tick, like c
      }

      testScheduler.advanceTimeBy((100 - 16).milliseconds) // final recomposition
      composeTestRule.runOnIdle {
        assertThat(player.currentPosition).isEqualTo(49980) // point e
        assertThatSliderValueEquals(1f)
      }
    }

  @Test
  fun slider_tapInCentre_playerPositionUpdatedOnceThumbLifted() =
    runTest(testDispatcher) {
      val player =
        FakePlayer(
          playlist =
            listOf(
              SimpleBasePlayer.MediaItemData.Builder("SingleItem")
                .setIsSeekable(true)
                .setDurationUs(10_000_000)
                .build()
            )
        )
      var slop = 0f
      composeTestRule.setContent {
        slop = LocalViewConfiguration.current.touchSlop
        ProgressSlider(player, Modifier.testTag(SLIDER_TAG), scope = backgroundScope)
      }

      composeTestRule.onNodeWithTag(SLIDER_TAG).performTouchInput {
        down(center)
        moveBy(Offset(slop, 0f)) // drag detection
      }
      testScheduler.runCurrent()

      assertThatSliderValueEquals(0.5f)
      assertThat(player.currentPosition).isEqualTo(0)

      composeTestRule.onNodeWithTag(SLIDER_TAG).performTouchInput { up() }
      testScheduler.runCurrent()

      assertThatSliderValueEquals(0.5f)
      assertThat(player.currentPosition).isEqualTo(5_000)
    }

  @Test
  fun slider_receivesNewPlayer_updatesCorrectly() =
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
        ProgressSlider(currentPlayer.value, Modifier.testTag(SLIDER_TAG))
      }

      assertThatSliderValueEquals(0.2f)

      currentPlayer.value = player2
      composeTestRule.waitForIdle()

      assertThatSliderValueEquals(0.75f)
    }

  @Test
  fun slider_dragAndRelease_firesCallbacksCorrectly() =
    runTest(testDispatcher) {
      val player =
        FakePlayer(
          playlist =
            listOf(
              SimpleBasePlayer.MediaItemData.Builder("SingleItem")
                .setIsSeekable(true)
                .setDurationUs(10_000_000)
                .build()
            )
        )
      var lastProgressValue: Float? = null
      var onValueChangeFinishedCount = 0
      var slop = 0f
      composeTestRule.setContent {
        slop = LocalViewConfiguration.current.touchSlop
        ProgressSlider(
          player = player,
          modifier = Modifier.testTag(SLIDER_TAG),
          onValueChange = { progressValue -> lastProgressValue = progressValue },
          onValueChangeFinished = { onValueChangeFinishedCount++ },
          scope = backgroundScope,
        )
      }

      composeTestRule.onNodeWithTag(SLIDER_TAG).performTouchInput {
        down(center)
        moveBy(Offset(slop, 0f)) // drag detection
      }
      testScheduler.runCurrent()

      assertThat(lastProgressValue).isEqualTo(0.5f)
      assertThat(onValueChangeFinishedCount).isEqualTo(0)

      composeTestRule.onNodeWithTag(SLIDER_TAG).performTouchInput { up() }
      testScheduler.runCurrent()

      assertThat(onValueChangeFinishedCount).isEqualTo(1)
    }

  private fun assertThatSliderValueEquals(value: Float) {
    composeTestRule
      .onNodeWithTag(SLIDER_TAG)
      .assertRangeInfoEquals(ProgressBarRangeInfo(current = value, range = 0f..1f, steps = 0))
  }
}
