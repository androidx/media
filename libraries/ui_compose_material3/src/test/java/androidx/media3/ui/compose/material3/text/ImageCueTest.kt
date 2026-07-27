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

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntRect
import androidx.core.graphics.createBitmap
import androidx.media3.common.text.Cue
import androidx.media3.ui.compose.material3.R
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [ImageCue]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ImageCueTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun imageCue_rendersBitmap() = runComposeUiTest {
    val bitmap = createBitmap(50, 20)
    val cue = Cue.Builder().setBitmap(bitmap).build()

    setContent { ImageCue(cue = cue, viewport = IntRect(0, 0, 200, 150)) }

    onNodeWithContentDescription(context.getString(R.string.subtitle_bitmap_cue)).assertExists()
  }

  @Test
  fun textCue_doesNotRender() = runComposeUiTest {
    val cue = Cue.Builder().setText("Hello").build()

    setContent { ImageCue(cue = cue, viewport = IntRect(0, 0, 200, 150)) }

    onNodeWithContentDescription(context.getString(R.string.subtitle_bitmap_cue))
      .assertDoesNotExist()
  }

  @Test
  fun intrinsicSize_unsetBounds() = runComposeUiTest {
    val bitmap = createBitmap(50, 20)
    val cue = Cue.Builder().setBitmap(bitmap).build()

    setContent { ImageCue(cue = cue, viewport = IntRect(0, 0, 200, 150)) }

    val node =
      onNodeWithContentDescription(context.getString(R.string.subtitle_bitmap_cue))
        .fetchSemanticsNode()
    assertThat(node.size.width).isEqualTo(50)
    assertThat(node.size.height).isEqualTo(20)
  }

  @Test
  fun relativeWidthOnly_maintainsAspectRatio() = runComposeUiTest {
    val bitmap = createBitmap(50, 20) // 5:2 aspect ratio
    val cue = Cue.Builder().setBitmap(bitmap).setSize(0.5f).build()

    setContent { ImageCue(cue = cue, viewport = IntRect(0, 0, 200, 150)) }

    val node =
      onNodeWithContentDescription(context.getString(R.string.subtitle_bitmap_cue))
        .fetchSemanticsNode()
    assertThat(node.size.width).isEqualTo(100) // 200 * 0.5
    assertThat(node.size.height).isEqualTo(40) // Proportional to 5:2
  }

  @Test
  fun explicitWidthAndHeight_disregardsAspectRatio() = runComposeUiTest {
    val bitmap = createBitmap(50, 20)
    val cue = Cue.Builder().setBitmap(bitmap).setSize(0.5f).setBitmapHeight(0.2f).build()

    setContent { ImageCue(cue = cue, viewport = IntRect(0, 0, 200, 150)) }

    val node =
      onNodeWithContentDescription(context.getString(R.string.subtitle_bitmap_cue))
        .fetchSemanticsNode()
    assertThat(node.size.width).isEqualTo(100) // 200 * 0.5
    assertThat(node.size.height).isEqualTo(30) // 150 * 0.2
  }

  @Test
  fun relativeHeightOnly_fallsBackToIntrinsicWidth_disregardsAspectRatio() = runComposeUiTest {
    val bitmap = createBitmap(50, 20)
    val cue = Cue.Builder().setBitmap(bitmap).setBitmapHeight(0.5f).build()

    setContent { ImageCue(cue = cue, viewport = IntRect(0, 0, 200, 150)) }

    val node =
      onNodeWithContentDescription(context.getString(R.string.subtitle_bitmap_cue))
        .fetchSemanticsNode()
    assertThat(node.size.width).isEqualTo(50) // Intrinsic width
    assertThat(node.size.height).isEqualTo(75) // 150 * 0.5
  }

  @Test
  fun fallbackToCenter() = runComposeUiTest {
    val bitmap = createBitmap(50, 20)
    val cue = Cue.Builder().setBitmap(bitmap).build() // Position/line are DIMEN_UNSET

    setContent {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        ImageCue(cue = cue, viewport = IntRect(0, 0, 200, 150))
      }
    }

    val node =
      onNodeWithContentDescription(context.getString(R.string.subtitle_bitmap_cue))
        .fetchSemanticsNode()
    assertThat(node.positionInRoot.x).isEqualTo(75f) // (200 - 50) / 2
    assertThat(node.positionInRoot.y).isEqualTo(65f) // (150 - 20) / 2
  }

  @Test
  fun anchorTypeStart() = runComposeUiTest {
    val bitmap = createBitmap(50, 20)
    val cue =
      Cue.Builder()
        .setBitmap(bitmap)
        .setPosition(0.3f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_START)
        .setLine(0.4f, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_START)
        .build()

    setContent {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        ImageCue(cue = cue, viewport = IntRect(0, 0, 200, 150))
      }
    }

    val node =
      onNodeWithContentDescription(context.getString(R.string.subtitle_bitmap_cue))
        .fetchSemanticsNode()
    assertThat(node.positionInRoot.x).isEqualTo(60f) // 200 * 0.3
    assertThat(node.positionInRoot.y).isEqualTo(60f) // 150 * 0.4
  }

  @Test
  fun anchorTypeMiddle() = runComposeUiTest {
    val bitmap = createBitmap(50, 20)
    val cue =
      Cue.Builder()
        .setBitmap(bitmap)
        .setPosition(0.5f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        .setLine(0.5f, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_MIDDLE)
        .build()

    setContent {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        ImageCue(cue = cue, viewport = IntRect(0, 0, 200, 150))
      }
    }

    val node =
      onNodeWithContentDescription(context.getString(R.string.subtitle_bitmap_cue))
        .fetchSemanticsNode()
    // Position 100, but centered around it, so subtract half the bitmap width (50 / 2 = 25)
    assertThat(node.positionInRoot.x).isEqualTo(75f) // (200 - 50) / 2
    // Line 75, centered around it, subtract half height (20 / 2 = 10)
    assertThat(node.positionInRoot.y).isEqualTo(65f) // (150 - 20) / 2
  }

  @Test
  fun anchorTypeEnd() = runComposeUiTest {
    val bitmap = createBitmap(50, 20)
    val cue =
      Cue.Builder()
        .setBitmap(bitmap)
        .setPosition(1.0f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_END)
        .setLine(1.0f, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_END)
        .build()

    setContent {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        ImageCue(cue = cue, viewport = IntRect(0, 0, 200, 150))
      }
    }

    val node =
      onNodeWithContentDescription(context.getString(R.string.subtitle_bitmap_cue))
        .fetchSemanticsNode()
    // Position 200, anchored at end, subtract full width (50)
    assertThat(node.positionInRoot.x).isEqualTo(150f) // 200 - 50
    // Line 150, anchored at end, subtract full height (20)
    assertThat(node.positionInRoot.y).isEqualTo(130f) // 150 - 20
  }

  @Test
  fun nonZeroViewportOrigin_shiftsCoordinates() = runComposeUiTest {
    val bitmap = createBitmap(50, 20)
    val cue =
      Cue.Builder()
        .setBitmap(bitmap)
        .setPosition(0.3f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_START)
        .setLine(0.4f, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_START)
        .build()

    var imagePositionInParent = Offset.Zero

    // Viewport is shifted by 10x and 5y
    setContent {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        ImageCue(
          cue = cue,
          viewport = IntRect(10, 5, 210, 155), // 200x150 shifted by 10, 5
          modifier =
            Modifier.onGloballyPositioned { coordinates ->
              imagePositionInParent = coordinates.positionInParent()
            },
        )
      }
    }

    val node =
      onNodeWithContentDescription(context.getString(R.string.subtitle_bitmap_cue))
        .fetchSemanticsNode()
    assertThat(imagePositionInParent.x).isEqualTo(60f) // 200 * 0.3
    assertThat(imagePositionInParent.y).isEqualTo(60f) // 150 * 0.4
    assertThat(node.positionInRoot.x).isEqualTo(70f) // 10 + 60
    assertThat(node.positionInRoot.y).isEqualTo(65f) // 5 + 60
  }

  @Test
  fun nonZeroViewportOrigin_withAnchor_shiftsCoordinates() = runComposeUiTest {
    val bitmap = createBitmap(50, 20)
    val cue =
      Cue.Builder()
        .setBitmap(bitmap)
        .setPosition(1.0f)
        .setPositionAnchor(Cue.ANCHOR_TYPE_END)
        .setLine(1.0f, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.ANCHOR_TYPE_END)
        .build()

    var imagePositionInParent = Offset.Zero

    // Viewport is shifted by 10x and 5y
    setContent {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        ImageCue(
          cue = cue,
          viewport = IntRect(10, 5, 210, 155), // 200x150 shifted by 10, 5
          modifier =
            Modifier.onGloballyPositioned { coordinates ->
              imagePositionInParent = coordinates.positionInParent()
            },
        )
      }
    }

    val node =
      onNodeWithContentDescription(context.getString(R.string.subtitle_bitmap_cue))
        .fetchSemanticsNode()
    assertThat(imagePositionInParent.x).isEqualTo(150f) // 200 - 50
    assertThat(imagePositionInParent.y).isEqualTo(130f) // 150 - 20
    assertThat(node.positionInRoot.x).isEqualTo(160f) // 10 + 150
    assertThat(node.positionInRoot.y).isEqualTo(135f) // 5 + 130
  }

  @Test
  fun zeroSizedViewport_placedAroundZero() = runComposeUiTest {
    val bitmap = createBitmap(50, 20)
    val cue = Cue.Builder().setBitmap(bitmap).build()

    setContent { ImageCue(cue = cue, viewport = IntRect(0, 0, 0, 0)) }

    val node =
      onNodeWithContentDescription(context.getString(R.string.subtitle_bitmap_cue))
        .fetchSemanticsNode()

    // Intrinsic size should be maintained, but placed relative to a 0x0 viewport.
    assertThat(node.size.width).isEqualTo(50)
    assertThat(node.size.height).isEqualTo(20)
    assertThat(node.positionInRoot.x).isEqualTo(-25f) // (0 - 50) / 2
    assertThat(node.positionInRoot.y).isEqualTo(-10f) // (0 - 20) / 2
  }
}
