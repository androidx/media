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

package androidx.media3.ui.compose.material3.text

import android.text.Layout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntRect
import androidx.core.graphics.createBitmap
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueUtil
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [TextCue]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TextCueTest {

  @Test
  fun textCue_withText_rendersText() = runComposeUiTest {
    val cue = Cue.Builder().setText("Hello World").build()

    setContent { TextCue(cue, viewport = IntRect(0, 0, 1000, 1000)) }

    onNodeWithText("Hello World").assertExists()
  }

  @Test
  fun textCue_withImageCue_doesNotRender() = runComposeUiTest {
    val bitmap = createBitmap(100, 100)
    val cue = Cue.Builder().setBitmap(bitmap).build()

    setContent { TextCue(cue, viewport = IntRect(0, 0, 1000, 1000)) }

    onRoot().onChildren().assertCountEquals(0)
  }

  @Test
  fun textCue_withRelativeWidthOnly_limitsCueWidth() = runComposeUiTest {
    val longText = "Hello World Hello World Hello World Hello World"
    val cue = Cue.Builder().setText(longText).setSize(0.5f).build()

    setContent { TextCue(cue, viewport = IntRect(0, 0, 200, 150)) }

    val node = onNodeWithText(longText).fetchSemanticsNode()
    assertThat(node.size.width).isAtMost(100)
    assertThat(node.size.width).isGreaterThan(0)
  }

  @Test
  fun textCue_withoutPositionAndLine_fallsBackToDefaultPositions() = runComposeUiTest {
    val cue = Cue.Builder().setText("Hello").build() // Position/line are DIMEN_UNSET

    setTextCueContent(cue, viewport = IntRect(0, 0, 200, 150))

    val node = onNodeWithText("Hello").fetchSemanticsNode()
    val expectedX = (200f - node.size.width) / 2f
    // The bottom of the cue is placed above the default bottom padding.
    val expectedY = 150f * (1 - CueUtil.DEFAULT_BOTTOM_PADDING_FRACTION) - node.size.height

    // allow 1px rounding error
    assertThat(node.positionInRoot.x).isWithin(1f).of(expectedX)
    assertThat(node.positionInRoot.y).isWithin(1f).of(expectedY)
  }

  @Test
  fun textCue_withAnchorTypeStart_positionsCorrectly() = runComposeUiTest {
    val cue =
      Cue.Builder()
        .setText("Hello")
        .setPosition(0.3f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_START)
        .setLine(0.4f, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_START)
        .build()

    setTextCueContent(cue, viewport = IntRect(0, 0, 200, 150))

    val node = onNodeWithText("Hello").fetchSemanticsNode()
    assertThat(node.positionInRoot.x).isWithin(1f).of(200f * 0.3f)
    assertThat(node.positionInRoot.y).isWithin(1f).of(150f * 0.4f)
  }

  @Test
  fun textCue_withAnchorTypeMiddle_positionsCorrectly() = runComposeUiTest {
    val cue =
      Cue.Builder()
        .setText("Hello")
        .setPosition(0.5f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        .setLine(0.5f, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        .build()

    setTextCueContent(cue, viewport = IntRect(0, 0, 200, 150))

    val node = onNodeWithText("Hello").fetchSemanticsNode()
    val expectedX = 100f - node.size.width / 2f
    val expectedY = 75f - node.size.height / 2f
    assertThat(node.positionInRoot.x).isWithin(1f).of(expectedX)
    assertThat(node.positionInRoot.y).isWithin(1f).of(expectedY)
  }

  @Test
  fun textCue_withAnchorTypeEnd_positionsCorrectly() = runComposeUiTest {
    val cue =
      Cue.Builder()
        .setText("Hello")
        .setPosition(1.0f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_END)
        .setLine(1.0f, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_END)
        .build()

    setTextCueContent(cue, viewport = IntRect(0, 0, 200, 150))

    val node = onNodeWithText("Hello").fetchSemanticsNode()
    val expectedX = 200f - node.size.width
    val expectedY = 150f - node.size.height
    assertThat(node.positionInRoot.x).isWithin(1f).of(expectedX)
    assertThat(node.positionInRoot.y).isWithin(1f).of(expectedY)
  }

  @Test
  fun textCue_withNegativeLineNumber_positionsFromBottom() = runComposeUiTest {
    val cue = Cue.Builder().setText("Hello").setLine(-2f, Cue.LINE_TYPE_NUMBER).build()

    setTextCueContent(cue, viewport = IntRect(0, 0, 200, 150))

    val node = onNodeWithText("Hello").fetchSemanticsNode()
    val textLayoutResult = onNodeWithText("Hello").fetchTextLayoutResult()
    val firstLineHeight = textLayoutResult.getLineBottom(0) - textLayoutResult.getLineTop(0)

    // -2 line number means (-2 + 1) * firstLineHeight + viewHeight - textHeight
    // which is viewHeight - textHeight - firstLineHeight
    val expectedY = 150f - node.size.height - firstLineHeight
    assertThat(node.positionInRoot.y).isWithin(1f).of(expectedY)
  }

  @Test
  fun textCue_withNonZeroViewportOrigin_shiftsCoordinates() = runComposeUiTest {
    val cue =
      Cue.Builder()
        .setText("Hello")
        .setPosition(0.3f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_START)
        .setLine(0.4f, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_START)
        .build()

    setTextCueContent(cue, viewport = IntRect(10, 5, 210, 155))

    val node = onNodeWithText("Hello").fetchSemanticsNode()

    // Viewport width = 200, height = 150
    // offsetX = 200 * 0.3 = 60
    // offsetY = 150 * 0.4 = 60
    // Root position should be offset + viewport origin

    assertThat(node.positionInRoot.x).isWithin(1f).of(60f + 10f)
    assertThat(node.positionInRoot.y).isWithin(1f).of(60f + 5f)
  }

  @Test
  fun textCue_withZeroSizedViewport_placedAtZero() = runComposeUiTest {
    val cue = Cue.Builder().setText("Hello").build()

    setContent { TextCue(cue, viewport = IntRect(0, 0, 0, 0)) }

    val node = onNodeWithText("Hello").fetchSemanticsNode()
    // Since the viewport is (0, 0), the clamped offsets will be 0
    assertThat(node.positionInRoot.x).isWithin(1f).of(0f)
    assertThat(node.positionInRoot.y).isWithin(1f).of(0f)
  }

  @Test
  fun textCue_withOffsetExceedingViewport_clampsWithinViewportBounds() = runComposeUiTest {
    val cue =
      Cue.Builder()
        .setText("Hello")
        .setPosition(1.2f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_START)
        .setLine(1.2f, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_START)
        .build()

    setTextCueContent(cue, viewport = IntRect(0, 0, 200, 150))

    val node = onNodeWithText("Hello").fetchSemanticsNode()
    val expectedX = 200f - node.size.width
    val expectedY = 150f - node.size.height

    assertThat(node.positionInRoot.x).isWithin(1f).of(expectedX)
    assertThat(node.positionInRoot.y).isWithin(1f).of(expectedY)
  }

  @Test
  fun textCue_withNegativeOffset_clampsToViewportOrigin() = runComposeUiTest {
    val cue =
      Cue.Builder()
        .setText("Hello")
        .setPosition(-0.5f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_START)
        .setLine(-0.5f, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_START)
        .build()

    setTextCueContent(cue, viewport = IntRect(0, 0, 200, 150))

    val node = onNodeWithText("Hello").fetchSemanticsNode()
    assertThat(node.positionInRoot.x).isWithin(1f).of(0f)
    assertThat(node.positionInRoot.y).isWithin(1f).of(0f)
  }

  @Test
  fun textCue_withAbsoluteTextSize_changesTextSize() = runComposeUiTest {
    val cueSmall =
      Cue.Builder().setText("Small").setTextSize(10f, Cue.TEXT_SIZE_TYPE_ABSOLUTE).build()
    val cueLarge =
      Cue.Builder().setText("Large").setTextSize(50f, Cue.TEXT_SIZE_TYPE_ABSOLUTE).build()

    setContent {
      Box(modifier = Modifier.fillMaxSize()) {
        TextCue(cue = cueSmall, viewport = IntRect(0, 0, 100, 100))
        TextCue(cue = cueLarge, viewport = IntRect(0, 0, 100, 100))
      }
    }

    val smallFontSize =
      onNodeWithText("Small").fetchTextLayoutResult().layoutInput.style.fontSize.value
    val largeFontSize =
      onNodeWithText("Large").fetchTextLayoutResult().layoutInput.style.fontSize.value

    assertThat(smallFontSize).isWithin(0.1f).of(10f)
    assertThat(largeFontSize).isWithin(0.1f).of(50f)
  }

  @Test
  fun textCue_withFractionalTextSize_changesTextSize() = runComposeUiTest {
    val cueSmall =
      Cue.Builder().setText("Small").setTextSize(0.1f, Cue.TEXT_SIZE_TYPE_FRACTIONAL).build()
    val cueLarge =
      Cue.Builder().setText("Large").setTextSize(0.5f, Cue.TEXT_SIZE_TYPE_FRACTIONAL).build()

    setContent {
      Box(modifier = Modifier.fillMaxSize()) {
        TextCue(cue = cueSmall, viewport = IntRect(0, 0, 1000, 1000))
        TextCue(cue = cueLarge, viewport = IntRect(0, 0, 1000, 1000))
      }
    }

    val smallFontSize =
      onNodeWithText("Small").fetchTextLayoutResult().layoutInput.style.fontSize.value
    val largeFontSize =
      onNodeWithText("Large").fetchTextLayoutResult().layoutInput.style.fontSize.value

    // Viewport height is 1000, sizes are 0.1f (100) and 0.5f (500)
    assertThat(smallFontSize).isWithin(0.1f).of(100f)
    assertThat(largeFontSize).isWithin(0.1f).of(500f)
  }

  @Test
  fun textCue_withStartAnchoredPositionNearRightEdge_capsWidthAndKeepsAnchor() = runComposeUiTest {
    val longText = "Hello World Hello World Hello World Hello World"
    val cue =
      Cue.Builder()
        .setText(longText)
        .setPosition(0.8f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_START)
        .build()

    setTextCueContent(cue, viewport = IntRect(0, 0, 200, 150))

    val node = onNodeWithText(longText).fetchSemanticsNode()
    // Remaining width from position 0.8f to 1.0f in a 200px viewport is 40px
    assertThat(node.size.width).isAtMost(40)
    assertThat(node.positionInRoot.x).isWithin(1f).of(160f)
  }

  @Test
  fun textCue_withAlignNormal_isStartAligned() = runComposeUiTest {
    val cue =
      Cue.Builder().setText("A").setTextAlignment(Layout.Alignment.ALIGN_NORMAL).setSize(1f).build()

    setTextCueContent(cue, viewport = IntRect(0, 0, 1000, 1000))

    val textLayoutResult = onNodeWithText("A").fetchTextLayoutResult()
    val lineLeft = textLayoutResult.getLineLeft(0)
    val lineRight = textLayoutResult.getLineRight(0)
    // ALIGN_NORMAL should lay out across the full cue width and align text to the start (left in
    // LTR)
    assertThat(textLayoutResult.size.width).isEqualTo(1000)
    assertThat(lineLeft).isWithin(1f).of(0f)
    assertThat(lineRight).isLessThan(1000f)
  }

  @Test
  fun textCue_withAlignCenter_isCenterAligned() = runComposeUiTest {
    val cue =
      Cue.Builder().setText("A").setTextAlignment(Layout.Alignment.ALIGN_CENTER).setSize(1f).build()

    setTextCueContent(cue, viewport = IntRect(0, 0, 1000, 1000))

    val textLayoutResult = onNodeWithText("A").fetchTextLayoutResult()
    val lineLeft = textLayoutResult.getLineLeft(0)
    val lineWidth = textLayoutResult.getLineRight(0) - lineLeft
    val expectedLeft = (textLayoutResult.size.width - lineWidth) / 2f

    // ALIGN_CENTER should place the line in the middle of the full 1000px cue width
    assertThat(textLayoutResult.size.width).isEqualTo(1000)
    assertThat(lineLeft).isGreaterThan(0f)
    assertThat(lineLeft).isWithin(1f).of(expectedLeft)
  }

  @Test
  fun textCue_withAlignOpposite_isEndAligned() = runComposeUiTest {
    val cue =
      Cue.Builder()
        .setText("A")
        .setTextAlignment(Layout.Alignment.ALIGN_OPPOSITE)
        .setSize(1f)
        .build()

    setTextCueContent(cue, viewport = IntRect(0, 0, 1000, 1000))

    val textLayoutResult = onNodeWithText("A").fetchTextLayoutResult()
    val lineLeft = textLayoutResult.getLineLeft(0)
    val lineRight = textLayoutResult.getLineRight(0)

    // ALIGN_OPPOSITE should place the line at the end of the full 1000px cue width
    assertThat(textLayoutResult.size.width).isEqualTo(1000)
    assertThat(lineLeft).isGreaterThan(0f)
    assertThat(lineRight).isWithin(1f).of(1000f)
  }

  /**
   * Sets the content to a [TextCue] in a full-size [Box], so its position is relative to the root.
   */
  private fun ComposeUiTest.setTextCueContent(cue: Cue, viewport: IntRect) {
    setContent { Box(Modifier.fillMaxSize()) { TextCue(cue, viewport) } }
  }

  private fun SemanticsNodeInteraction.fetchTextLayoutResult(): TextLayoutResult {
    val textLayoutResults = mutableListOf<TextLayoutResult>()
    fetchSemanticsNode().config[GetTextLayoutResult].action?.invoke(textLayoutResults)
    return textLayoutResults.first()
  }
}
