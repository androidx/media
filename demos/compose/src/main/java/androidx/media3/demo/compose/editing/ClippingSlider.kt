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
import androidx.annotation.FloatRange
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.media3.demo.compose.R
import com.google.common.collect.ImmutableList

/** The ratio of the total width of a clipping thumb to the width of the image row. */
private const val CLIPPING_THUMB_WIDTH_RATIO = 1f / 15
/** The ratio of the plain, solid width of a clipping thumb to its total width. */
private const val CLIPPING_THUMB_PLAIN_RATIO = 0.8f
/** The ratio of the plain, solid width of a clipping thumb to the width of the image row. */
private const val CLIPPING_THUMB_PLAIN_WIDTH_RATIO =
  CLIPPING_THUMB_WIDTH_RATIO * CLIPPING_THUMB_PLAIN_RATIO
/**
 * The ratio of the track width available for the clipping thumbs to move, relative to the image row
 * width.
 *
 * The clipping slider track is shorter than the image row because the clipping start and end
 * positions correspond to the center of the thumb. Specifically, the track starts
 * [CLIPPING_THUMB_WIDTH_RATIO] / 2 after the image row start and ends [CLIPPING_THUMB_WIDTH_RATIO]
 * / 2 before the image row end.
 */
private const val CLIPPING_TRACK_WIDTH_RATIO = 1f - CLIPPING_THUMB_WIDTH_RATIO
/** The ratio of the maximum position slider length to the width of the image row. */
private const val POSITION_SLIDER_MAX_LENGTH_RATIO = 1f - (CLIPPING_THUMB_PLAIN_WIDTH_RATIO * 2)
/** The ratio of the clipping frame's horizontal bar thickness to the total height of the slider. */
private const val CLIPPING_FRAME_THICKNESS_RATIO = 0.05f

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
 * @param colors The [ClippingSliderColors] used to style the slider.
 * @param shape The [RoundedCornerShape] used to define the slider's shape.
 * @param clippingThumbPainter A composable lambda that provides icons for the clipping thumbs. The
 *   first boolean passed to the lambda indicates which of the two clipping handles is currently
 *   being painted. The second boolean indicates whether the thumb has reached its absolute boundary
 *   within the media (start of media for the start thumb, end of the media for the end thumb).
 */
// TODO: b/505719491
//  - Implement accessibility requirements
//  - Implement color defaults
//  - Decide and test what the slider should look like for RTL locales
//  - Move to material3 module and mark API unstable
@Composable
fun ClippingSlider(
  bitmaps: ImmutableList<Bitmap>,
  modifier: Modifier = Modifier,
  colors: ClippingSliderColors,
  shape: RoundedCornerShape = RoundedCornerShape(percent = 30),
  clippingThumbPainter: @Composable (isStart: Boolean, isAtLimit: Boolean) -> Painter =
    defaultClippingThumbPainterIcon,
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
    ClippedImagesFilter(
      clippingRange,
      Modifier.fillMaxSize().clip(shape),
      colors.clippedFilterColor,
    )
  }
}

/**
 * Represents the colors used in a [ClippingSlider] to customize its appearance.
 *
 * @property clippingFrameColor The color of the selection frame and of the clipping handles.
 * @property clippingIconColor The color used to tint the icons within the clipping thumbs.
 * @property positionThumbColor The color of the thumb representing the current playback position.
 * @property clippedFilterColor The color of the filter applied to the clipped areas of the slider.
 *   The image bitmaps underneath remain visible if the alpha is less than 1.
 */
@Immutable
class ClippingSliderColors(
  val clippingFrameColor: Color,
  val clippingIconColor: Color,
  val positionThumbColor: Color,
  val clippedFilterColor: Color,
) {

  /** Returns a copy of this ClippingSliderColors, optionally overriding some of the values. */
  fun copy(
    clippingFrameColor: Color = this.clippingFrameColor,
    clippingIconColor: Color = this.clippingIconColor,
    positionThumbColor: Color = this.positionThumbColor,
    clippedFilterColor: Color = this.clippedFilterColor,
  ): ClippingSliderColors =
    ClippingSliderColors(
      clippingFrameColor,
      clippingIconColor,
      positionThumbColor,
      clippedFilterColor,
    )

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ClippingSliderColors) return false

    if (clippingFrameColor != other.clippingFrameColor) return false
    if (clippingIconColor != other.clippingIconColor) return false
    if (positionThumbColor != other.positionThumbColor) return false
    if (clippedFilterColor != other.clippedFilterColor) return false

    return true
  }

  override fun hashCode(): Int {
    var result = clippingFrameColor.hashCode()
    result = 31 * result + clippingIconColor.hashCode()
    result = 31 * result + positionThumbColor.hashCode()
    result = 31 * result + clippedFilterColor.hashCode()
    return result
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
  @FloatRange(from = 0.0, to = 1.0) clippingRange: ClosedFloatingPointRange<Float>,
  modifier: Modifier = Modifier,
  clippedFilterColor: Color,
) {
  Canvas(modifier) {
    val width = size.width
    val height = size.height
    val positionSliderStart = logicalToVisualPositionSliderStart(clippingRange.start) * width
    if (positionSliderStart > 0f) {
      drawRect(clippedFilterColor, size = Size(positionSliderStart, height))
    }
    val positionSliderEnd = logicalToVisualPositionSliderEnd(clippingRange.endInclusive) * width
    if (positionSliderEnd < width) {
      drawRect(
        clippedFilterColor,
        topLeft = Offset(x = positionSliderEnd, y = 0f),
        size = Size(width - positionSliderEnd, height),
      )
    }
  }
}

/**
 * A composable that draws one of the clipping handles (thumbs).
 *
 * This component draws a "C-shaped" frame and an icon within it.
 */
@Composable
private fun ClippingThumb(
  isStart: Boolean,
  colors: ClippingSliderColors,
  imageRowShape: RoundedCornerShape,
  painter: Painter,
  modifier: Modifier = Modifier,
) {
  Box(
    // Isolate drawing to its own layer to avoid redraws when the playback position advances.
    modifier.graphicsLayer().drawWithCache {
      // Create a square size bounded by the height. This forces percentage-based shapes to resolve
      // against the height, making the radius perfectly match the ImageRow.
      val squareSize = Size(size.height, size.height)
      val topLeft = if (isStart) imageRowShape.topStart.toPx(squareSize, this) else 0f
      val topRight = if (isStart) 0f else imageRowShape.topEnd.toPx(squareSize, this)
      val bottomRight = if (isStart) 0f else imageRowShape.bottomEnd.toPx(squareSize, this)
      val bottomLeft = if (isStart) imageRowShape.bottomStart.toPx(squareSize, this) else 0f

      val thumbPlainWidth = CLIPPING_THUMB_PLAIN_RATIO * size.width
      val frameThickness = CLIPPING_FRAME_THICKNESS_RATIO * size.height

      val outerRectanglePath =
        Path().apply {
          addRoundRect(
            RoundRect(
              left = 0f,
              top = 0f,
              right = size.width,
              bottom = size.height,
              topLeftCornerRadius = CornerRadius(topLeft),
              topRightCornerRadius = CornerRadius(topRight),
              bottomRightCornerRadius = CornerRadius(bottomRight),
              bottomLeftCornerRadius = CornerRadius(bottomLeft),
            )
          )
        }
      val innerRectanglePath =
        Path().apply {
          addRoundRect(
            RoundRect(
              left = if (isStart) thumbPlainWidth else 0f,
              top = frameThickness,
              right = if (isStart) size.width else size.width - thumbPlainWidth,
              bottom = size.height - frameThickness,
              topLeftCornerRadius = CornerRadius(topLeft / 2),
              topRightCornerRadius = CornerRadius(topRight / 2),
              bottomRightCornerRadius = CornerRadius(bottomRight / 2),
              bottomLeftCornerRadius = CornerRadius(bottomLeft / 2),
            )
          )
        }
      onDrawBehind {
        clipPath(innerRectanglePath, clipOp = ClipOp.Difference) {
          drawPath(outerRectanglePath, colors.clippingFrameColor)
        }
      }
    },
    contentAlignment = if (isStart) Alignment.CenterStart else Alignment.CenterEnd,
  ) {
    Image(
      painter,
      contentDescription = null,
      modifier = Modifier.fillMaxHeight().fillMaxWidth(CLIPPING_THUMB_PLAIN_RATIO),
      contentScale = ContentScale.FillBounds,
      colorFilter = ColorFilter.tint(colors.clippingIconColor),
    )
  }
}

/**
 * A composable that draws the horizontal track connecting the two clipping thumbs.
 *
 * This component draws the two horizontal bars of the clipping frame.
 */
@Composable
private fun ClippingTrack(
  @FloatRange(from = 0.0, to = 1.0) clippingSliderRange: ClosedFloatingPointRange<Float>,
  clippingFrameColor: Color,
  modifier: Modifier = Modifier,
) {
  Spacer(
    // Isolate drawing to its own layer to avoid redraws when the playback position advances.
    modifier.graphicsLayer().drawWithCache {
      // Draw the 2 horizontal bars of the clipping frame.
      val width = size.width
      val height = size.height
      val thumbWidthPx = CLIPPING_THUMB_WIDTH_RATIO / CLIPPING_TRACK_WIDTH_RATIO * width
      // Shift the start and end by half a thumb so that the horizontal bars are strictly between
      // the thumbs (instead of between the thumb centers). Shift again by 1 pixel to avoid holes
      // between the bars and the thumbs due to rounding errors.
      val clippingStartPx = clippingSliderRange.start * width + thumbWidthPx / 2f - 1f
      val clippingEndPx = clippingSliderRange.endInclusive * width - thumbWidthPx / 2f + 1f
      val frameWidth = clippingEndPx - clippingStartPx
      val frameThickness = CLIPPING_FRAME_THICKNESS_RATIO * height
      val rectSize = Size(width = frameWidth, height = frameThickness)
      onDrawBehind {
        drawRect(clippingFrameColor, topLeft = Offset(x = clippingStartPx, y = 0f), size = rectSize)
        drawRect(
          clippingFrameColor,
          topLeft = Offset(clippingStartPx, height - frameThickness),
          size = rectSize,
        )
      }
    }
  )
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

private val defaultClippingThumbPainterIcon:
  @Composable
  (isStart: Boolean, isAtLimit: Boolean) -> Painter =
  @Composable { isStart, isAtLimit ->
    if (isAtLimit) painterResource(R.drawable.media3_icon_clip_thumb_limit)
    else if (isStart) painterResource(R.drawable.media3_icon_clip_thumb_left_arrow)
    else painterResource(R.drawable.media3_icon_clip_thumb_right_arrow)
  }
