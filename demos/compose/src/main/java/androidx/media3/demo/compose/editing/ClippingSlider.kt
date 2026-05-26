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
package androidx.media3.demo.compose.editing

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.google.common.collect.ImmutableList

/** The ratio of the total width of a clipping thumb to the width of the image row. */
private const val CLIPPING_THUMB_WIDTH_RATIO = 1f / 15
/** The ratio of the plain, solid width of a clipping thumb to the width of the image row. */
private const val CLIPPING_THUMB_PLAIN_WIDTH_RATIO = CLIPPING_THUMB_WIDTH_RATIO * 0.8f
/** The ratio of the maximum position slider length to the width of the image row. */
private const val POSITION_SLIDER_MAX_LENGTH_RATIO = 1f - (CLIPPING_THUMB_PLAIN_WIDTH_RATIO * 2)

private val CLIPPED_IMAGES_FILTER_COLOR = Color.DarkGray.copy(alpha = 0.5f)

/**
 * A Material3 clipping slider that allows users to select a clipping range and track playback
 * position.
 *
 * This component displays a row of bitmaps representing the media content, with a range slider
 * overlaid to define the start and end clipping points. A secondary position slider allows for
 * seeking within the selected range.
 *
 * This component does not update the player's clipping configuration. The caller is intended to
 * update the clipping configuration (and potentially apply other edits) at the end of the editing
 * experience.
 *
 * @param bitmaps A list of [Bitmap] instances to display as a background preview for the slider.
 *   They should all have the same size. If this list is empty, the component will render an empty
 *   [Box] instead.
 * @param modifier The [Modifier] to be applied to the slider.
 * @param shape The [RoundedCornerShape] used to define the slider's shape.
 */
// TODO: b/505719491
//  - Decide and test what the slider should look like for RTL locales
//  - Move to material3 module and mark API unstable
@Composable
fun ClippingSlider(
  bitmaps: ImmutableList<Bitmap>,
  modifier: Modifier = Modifier,
  shape: RoundedCornerShape = RoundedCornerShape(percent = 30),
) {
  if (bitmaps.isEmpty()) {
    Box(modifier)
    return
  }
  val sliderAspectRatio =
    remember(bitmaps) {
      val firstBitmap = bitmaps[0]
      require(firstBitmap.width > 0 && firstBitmap.height > 0) {
        "Bitmap should have positive width and height"
      }
      (bitmaps.size * firstBitmap.width).toFloat() / firstBitmap.height.toFloat()
    }
  Box(modifier = modifier.aspectRatio(sliderAspectRatio)) {
    // TODO: b/505719491 - Pass actual clippingRange
    val clippingRange = 0.25f..0.75f
    ImageRow(bitmaps, Modifier.fillMaxWidth().clip(shape))
    ClippedImagesFilter(clippingRange, Modifier.fillMaxSize().clip(shape))
  }
}

@Composable
private fun ImageRow(bitmaps: ImmutableList<Bitmap>, modifier: Modifier = Modifier) {
  val imageBitmaps = remember(bitmaps) { bitmaps.map { it.asImageBitmap() } }
  Row(modifier) {
    for (imageBitmap in imageBitmaps) {
      Image(
        imageBitmap,
        contentDescription = null,
        Modifier.weight(1f),
        contentScale = ContentScale.FillWidth,
      )
    }
  }
}

@Composable
private fun ClippedImagesFilter(
  clippingRange: ClosedFloatingPointRange<Float>,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier) {
    val width = size.width
    val height = size.height
    val positionSliderStart = logicalToVisualPositionSliderStart(clippingRange.start) * width
    if (positionSliderStart > 0f) {
      drawRect(CLIPPED_IMAGES_FILTER_COLOR, size = Size(positionSliderStart, height))
    }
    val positionSliderEnd = logicalToVisualPositionSliderEnd(clippingRange.endInclusive) * width
    if (positionSliderEnd < width) {
      drawRect(
        CLIPPED_IMAGES_FILTER_COLOR,
        topLeft = Offset(x = positionSliderEnd, y = 0f),
        size = Size(width - positionSliderEnd, height),
      )
    }
  }
}

/**
 * Returns the visual ratio for the position slider start compared to the width of the image row.
 *
 * The position slider lies between the plain portions of the clipping handles.
 *
 * @param clippingStart The clipping start position of the media, expressed as a ratio of the
 *   duration (value between 0 and 1).
 */
private fun logicalToVisualPositionSliderStart(clippingStart: Float): Float =
  (clippingStart * POSITION_SLIDER_MAX_LENGTH_RATIO) + CLIPPING_THUMB_PLAIN_WIDTH_RATIO

/**
 * Returns the visual ratio for the position slider end compared to the width of the image row.
 *
 * The position slider lies between the plain portions of the clipping handles.
 *
 * @param clippingEnd The clipping end position of the media, expressed as a ratio of the duration
 *   (value between 0 and 1).
 */
private fun logicalToVisualPositionSliderEnd(clippingEnd: Float): Float =
  (clippingEnd * POSITION_SLIDER_MAX_LENGTH_RATIO) + CLIPPING_THUMB_PLAIN_WIDTH_RATIO
