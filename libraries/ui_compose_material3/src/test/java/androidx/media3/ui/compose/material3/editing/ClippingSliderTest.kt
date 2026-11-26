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

package androidx.media3.ui.compose.material3.editing

import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTestConfig
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClippingSliderTest {
  private val tag = "clippingSlider"
  private val tolerance = 0.003f
  private val testDispatcher = StandardTestDispatcher()

  @OptIn(ExperimentalTestApi::class)
  @get:Rule
  val rule = createComposeRule(ComposeUiTestConfig(effectContext = testDispatcher))

  private var touchSlop = 0f

  @Test
  fun clippingSlider_defaultRange_selectsWholeMediaWithEnabledThumbs() =
    runTest(testDispatcher) {
      val player = createPlayer(durationMs = 10_000, positionMs = 3_000)

      val state = setClippingSliderContent(player, scope = backgroundScope)

      rule.runOnIdle {
        assertThat(state.clippingRangeMs).isEqualTo(0L..C.TIME_END_OF_SOURCE)
        assertThat(state.playbackProgress).isWithin(tolerance).of(0.3f)
      }
      onThumbs().assertCountEquals(3)
      onStartThumb().assertIsEnabled()
      onEndThumb().assertIsEnabled()
      onPositionThumb().assertIsEnabled()
    }

  @Test
  fun dragStartThumb_whilePlaying_pausesAndSeeksToNewStart() =
    runTest(testDispatcher) {
      val player = createPlayer(durationMs = 10_000, positionMs = 3_000)
      player.play()
      var reportedRangeMs: LongRange? = null
      var rangeChangeFinished = false
      val state =
        setClippingSliderContent(
          player,
          scope = backgroundScope,
          initialClippingRangeMs = 2_000L..8_000L,
          onClippingRangeChange = { reportedRangeMs = it },
          onClippingRangeChangeFinished = { rangeChangeFinished = true },
        )
      rule.runOnIdle { assertThat(state.clippingRangeMs).isEqualTo(2_000L..8_000L) }
      val sliderBounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
      val startThumbCenterX = onStartThumb().fetchSemanticsNode().boundsInRoot.center.x

      // Release the thumb separately, so that the drag interaction is processed while the thumb is
      // still held.
      onStartThumb().performTouchInput {
        down(center)
        moveBy(Offset(touchSlop + 50f, 0f))
      }
      testScheduler.runCurrent()

      // FakePlayer doesn't support scrubbing mode, so the seek is deferred until the release.
      rule.runOnIdle {
        assertThat(state.isUserInteracting).isTrue()
        assertThat(player.playWhenReady).isFalse()
        assertThat(player.currentPosition).isEqualTo(3_000L)
        assertThat(rangeChangeFinished).isFalse()
        assertThat(reportedRangeMs?.first).isGreaterThan(2_000L)
      }

      onStartThumb().performTouchInput { up() }
      testScheduler.runCurrent()

      val expectedSliderStart =
        calculateFraction(
          sliderBounds.left,
          sliderBounds.right,
          startThumbCenterX + touchSlop + 50f,
        )
      // The clipping start is at the inside of the start thumb, rather than at its center.
      val expectedClippingStart =
        expectedSliderStart * CLIPPING_TRACK_WIDTH_RATIO / PROGRESS_SLIDER_MAX_LENGTH_RATIO
      rule.runOnIdle {
        assertThat(state.isUserInteracting).isFalse()
        assertThat(rangeChangeFinished).isTrue()
        assertThat(state.rangeSliderState.startValue).isWithin(tolerance).of(expectedSliderStart)
        assertThat(state.clippingRange.start).isWithin(tolerance).of(expectedClippingStart)
        assertThat(state.clippingRangeMs.first / 10_000f)
          .isWithin(tolerance)
          .of(expectedClippingStart)
        assertThat(state.clippingRangeMs.last).isEqualTo(8_000L)
        assertThat(reportedRangeMs).isEqualTo(state.clippingRangeMs)
        assertThat(player.currentPosition).isEqualTo(state.clippingRangeMs.first)
        assertThat(state.playbackProgress).isWithin(tolerance).of(expectedClippingStart)
      }
    }

  @Test
  fun dragPositionThumb_whilePlaying_pausesAndSeeksOnRelease() =
    runTest(testDispatcher) {
      val player = createPlayer(durationMs = 10_000, positionMs = 5_000)
      player.play()
      var reportedProgress: Float? = null
      var progressChangeFinished = false
      val state =
        setClippingSliderContent(
          player,
          scope = backgroundScope,
          onProgressChange = { reportedProgress = it },
          onProgressChangeFinished = { progressChangeFinished = true },
        )
      assertThat(onPositionThumb().fetchProgress()).isWithin(tolerance).of(0.5f)
      rule.runOnIdle { assertThat(player.playWhenReady).isTrue() }

      onPositionThumb().performTouchInput {
        down(center)
        moveBy(Offset(touchSlop + 50f, 0f))
      }
      testScheduler.runCurrent()

      // FakePlayer doesn't support scrubbing mode, so the seek is deferred until the release.
      val draggedProgress = rule.runOnIdle {
        assertThat(state.isUserInteracting).isTrue()
        assertThat(player.playWhenReady).isFalse()
        assertThat(player.currentPosition).isEqualTo(5_000L)
        assertThat(progressChangeFinished).isFalse()
        state.progressSliderState.value
      }
      assertThat(draggedProgress).isGreaterThan(0.5f)
      assertThat(reportedProgress).isEqualTo(draggedProgress)

      onPositionThumb().performTouchInput { up() }
      testScheduler.runCurrent()

      rule.runOnIdle {
        assertThat(state.isUserInteracting).isFalse()
        assertThat(progressChangeFinished).isTrue()
        assertThat(player.currentPosition).isEqualTo((draggedProgress * 10_000L).toLong())
        assertThat(state.playbackProgress).isWithin(tolerance).of(draggedProgress)
      }
    }

  @Test
  fun clippingSliderWithPlayer_positionBeforeInitialRange_seeksToRangeStart() =
    runTest(testDispatcher) {
      val player = createPlayer(durationMs = 10_000, positionMs = 1_000)

      setClippingSliderContentWithPlayer(
        player,
        scope = backgroundScope,
        initialClippingRangeMs = 2_000L..8_000L,
      )
      testScheduler.runCurrent()

      rule.runOnIdle { assertThat(player.currentPosition).isEqualTo(2_000L) }
      // The thumbs are centered half a thumb outside the clip, so 2 s to 8 s of 10 s is symmetric.
      val expectedStartThumbProgress =
        0.2f * PROGRESS_SLIDER_MAX_LENGTH_RATIO / CLIPPING_TRACK_WIDTH_RATIO
      assertThat(onStartThumb().fetchProgress()).isWithin(tolerance).of(expectedStartThumbProgress)
      assertThat(onEndThumb().fetchProgress())
        .isWithin(tolerance)
        .of(1f - expectedStartThumbProgress)
      onThumbs().assertCountEquals(3)
      onStartThumb().assertIsEnabled()
      onEndThumb().assertIsEnabled()
      onPositionThumb().assertIsEnabled()
    }

  @Test
  fun clippingSliderWithPlayer_unseekableMedia_disablesThumbs() =
    runTest(testDispatcher) {
      val player = createPlayer(durationMs = 10_000, positionMs = 0, isSeekable = false)

      setClippingSliderContentWithPlayer(player, scope = backgroundScope)
      testScheduler.runCurrent()

      onStartThumb().assertIsNotEnabled()
      onEndThumb().assertIsNotEnabled()
      onPositionThumb().assertIsNotEnabled()
    }

  private fun setClippingSliderContent(
    player: Player,
    scope: CoroutineScope,
    initialClippingRangeMs: LongRange = 0L..C.TIME_END_OF_SOURCE,
    onClippingRangeChange: (LongRange) -> Unit = {},
    onClippingRangeChangeFinished: () -> Unit = {},
    onProgressChange: (Float) -> Unit = {},
    onProgressChangeFinished: () -> Unit = {},
  ): ClippingSliderState {
    lateinit var state: ClippingSliderState
    val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
    rule.setContent {
      touchSlop = LocalViewConfiguration.current.touchSlop
      state =
        rememberClippingSliderState(
          player = player,
          // Fine enough for the quantization of the playback progress to be within [tolerance].
          positionTickCount = 10_000,
          initialClippingRangeMs = initialClippingRangeMs,
          minClippedDurationMs = 1_000,
          scope = scope,
        )
      ClippingSlider(
        state = state,
        bitmaps = ImmutableList.of(bitmap, bitmap),
        modifier = Modifier.testTag(tag),
        onClippingRangeChange = onClippingRangeChange,
        onClippingRangeChangeFinished = onClippingRangeChangeFinished,
        onProgressChange = onProgressChange,
        onProgressChangeFinished = onProgressChangeFinished,
      )
    }
    return state
  }

  private fun setClippingSliderContentWithPlayer(
    player: Player,
    scope: CoroutineScope,
    initialClippingRangeMs: LongRange = 0L..C.TIME_END_OF_SOURCE,
  ) {
    val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
    rule.setContent {
      ClippingSlider(
        player = player,
        bitmaps = ImmutableList.of(bitmap, bitmap),
        modifier = Modifier.testTag(tag),
        initialClippingRangeMs = initialClippingRangeMs,
        scope = scope,
      )
    }
  }

  /**
   * Returns the fraction (0 to 1) of the clipping track at [pos], where the track is inset by half
   * a clipping thumb width from the slider's [left] and [right] edges.
   */
  private fun calculateFraction(left: Float, right: Float, pos: Float): Float {
    val inset = (right - left) * CLIPPING_THUMB_WIDTH_RATIO / 2
    val start = left + inset
    val end = right - inset
    return ((pos - start) / (end - start)).coerceIn(0f, 1f)
  }

  /** The start thumb, end thumb and position thumb, in this order. */
  private fun onThumbs(): SemanticsNodeInteractionCollection =
    rule
      .onNodeWithTag(tag)
      .onChildren()
      .filter(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))

  private fun onStartThumb(): SemanticsNodeInteraction = onThumbs()[0]

  private fun onEndThumb(): SemanticsNodeInteraction = onThumbs()[1]

  private fun onPositionThumb(): SemanticsNodeInteraction = onThumbs()[2]

  private fun SemanticsNodeInteraction.fetchProgress(): Float =
    fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

  private fun createPlayer(
    durationMs: Long,
    positionMs: Long,
    isSeekable: Boolean = true,
  ): FakePlayer =
    FakePlayer(
        playbackState = Player.STATE_READY,
        playWhenReady = false,
        bufferingDelayMs = 0,
        playlist =
          listOf(
            MediaItemData.Builder(/* uid= */ "item")
              .setDurationUs(durationMs * 1_000)
              .setIsSeekable(isSeekable)
              .build()
          ),
      )
      .apply { setPosition(positionMs) }
}
