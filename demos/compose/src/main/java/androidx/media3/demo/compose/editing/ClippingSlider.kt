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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.RangeSlider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.demo.compose.R
import androidx.media3.ui.compose.state.PlayerStateObserver
import androidx.media3.ui.compose.state.ProgressStateWithTickCount
import androidx.media3.ui.compose.state.observeState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickCount
import com.google.common.collect.ImmutableList
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
private const val POSITION_SLIDER_MAX_LENGTH_RATIO = 1f - 2 * CLIPPING_THUMB_PLAIN_WIDTH_RATIO
/** The ratio of the clipping frame's horizontal bar thickness to the total height of the slider. */
private const val CLIPPING_FRAME_THICKNESS_RATIO = 0.05f

/** The minimum clipping progress delta required to prevent the clipping thumbs from overlapping. */
private const val MIN_CLIPPING_DELTA_FOR_NO_OVERLAP =
  2f * (CLIPPING_THUMB_WIDTH_RATIO - CLIPPING_THUMB_PLAIN_WIDTH_RATIO) /
    POSITION_SLIDER_MAX_LENGTH_RATIO

/**
 * A small epsilon value used to check if a progress value is close to the boundaries (0.0 or 1.0).
 */
private const val BOUNDARY_EPSILON = 1e-3f

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
 * @param player The [Player] whose content to clip.
 * @param clippingRangeMs The selected clipping range in milliseconds. To set the end of the
 *   clipping range to the full duration of the media, the caller should pass [C.TIME_END_OF_SOURCE]
 *   as the end time in the [LongRange].
 * @param onClippingRangeChange A callback that is invoked continuously as one of the clipping
 *   thumbs is being dragged. The [LongRange] represents the clipping start and end positions in
 *   milliseconds (or [C.TIME_END_OF_SOURCE] as the end position when the clip is untrimmed at the
 *   end of the media) and should be used to update [clippingRangeMs].
 * @param bitmaps A list of [Bitmap] instances to display as a background preview for the slider.
 *   They should all have the same size. If this list is empty, the component will render an empty
 *   [Box] instead.
 * @param modifier The [Modifier] to be applied to the slider.
 * @param onClippingRangeChangeFinished A callback that is invoked when the user finishes dragging a
 *   clipping thumb. This callback shouldn't be used to update the range slider values (use
 *   [onClippingRangeChange] for that), but rather to know when the user has completed selecting a
 *   new value by ending a drag.
 * @param minClippedDurationMs The minimum allowed duration of the clipped range in milliseconds.
 *   The slider will prevent the user from selecting a range shorter than this value.
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
//  - Remove @OptIn(ExperimentalMaterial3Api::class) annotations once the RangeSlider is stable
//  - Move to material3 module and mark API unstable
//  - Add tests
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClippingSlider(
  player: Player?,
  clippingRangeMs: LongRange,
  onClippingRangeChange: (LongRange) -> Unit,
  bitmaps: ImmutableList<Bitmap>,
  modifier: Modifier = Modifier,
  onClippingRangeChangeFinished: (() -> Unit)? = null,
  minClippedDurationMs: Long = 1000L,
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
  var positionTickCount by remember { mutableIntStateOf(0) }
  val state =
    rememberClippingSliderState(player, positionTickCount, clippingRangeMs, minClippedDurationMs)
  var isDraggingClippingThumb by remember { mutableStateOf(false) }
  val currentOnClippingRangeChange by rememberUpdatedState(onClippingRangeChange)

  LaunchedEffect(clippingRangeMs, minClippedDurationMs, state, state.durationMs) {
    if (isDraggingClippingThumb) return@LaunchedEffect

    val wasAdjusted = state.syncExternalRange(clippingRangeMs, minClippedDurationMs)
    if (wasAdjusted) {
      currentOnClippingRangeChange(state.clippingRangeMs)
    }
  }

  Box(
    modifier =
      modifier.aspectRatio(sliderAspectRatio).onSizeChanged { size ->
        positionTickCount = (POSITION_SLIDER_MAX_LENGTH_RATIO * size.width).roundToInt()
      }
  ) {
    ImageRow(bitmaps, Modifier.fillMaxWidth().clip(shape))
    ClippedImagesFilter(
      clippingRangeProvider = { state.clippingRange },
      colors.clippedFilterColor,
      Modifier.fillMaxSize().clip(shape),
    )
    ClippingRangeSlider(
      state = state,
      onClippingRangeChange = onClippingRangeChange,
      onClippingRangeChangeFinished = onClippingRangeChangeFinished,
      minClippedDurationMs = minClippedDurationMs,
      colors = colors,
      shape = shape,
      thumbPainter = clippingThumbPainter,
      onDraggingChanged = { isDraggingClippingThumb = it },
      modifier = Modifier.fillMaxSize(),
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

/**
 * A composable that draws a visual filter (overlay) over the clipped (inactive) areas of the
 * slider.
 *
 * This component dims the regions of the image row that fall outside the active clipping range (to
 * the left of the start thumb and to the right of the end thumb).
 *
 * @param clippingRangeProvider A provider that returns the current active clipping range as a
 *   fraction (0.0 to 1.0) of the total duration.
 * @param clippedFilterColor The color (usually translucent) used to overlay the clipped areas.
 * @param modifier The [Modifier] to be applied to this composable.
 */
@Composable
private fun ClippedImagesFilter(
  clippingRangeProvider: () -> ClosedFloatingPointRange<Float>,
  clippedFilterColor: Color,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier) {
    val clippingRange = clippingRangeProvider()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClippingRangeSlider(
  state: ClippingSliderState,
  onClippingRangeChange: (LongRange) -> Unit,
  onClippingRangeChangeFinished: (() -> Unit)?,
  minClippedDurationMs: Long,
  colors: ClippingSliderColors,
  shape: RoundedCornerShape,
  thumbPainter: @Composable (isStart: Boolean, isAtLimit: Boolean) -> Painter,
  onDraggingChanged: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val startThumbInteractionSource = remember { MutableInteractionSource() }
  val endThumbInteractionSource = remember { MutableInteractionSource() }
  val isDraggingStartThumb by startThumbInteractionSource.collectIsDraggedAsState()
  val isDraggingEndThumb by endThumbInteractionSource.collectIsDraggedAsState()
  val isDragging = isDraggingStartThumb || isDraggingEndThumb
  val clippingSliderRange = sliderRangeFromClippingRange(state.clippingRange)
  val minProgressDelta = state.calculateMinProgressDelta(minClippedDurationMs)
  LaunchedEffect(isDragging) {
    onDraggingChanged(isDragging)
    if (isDragging) {
      state.pause()
    }
  }
  RangeSlider(
    value = clippingSliderRange,
    onValueChange = { newClippingSliderRange ->
      if (!isDragging) return@RangeSlider // Filter out tapping events

      val proposedRange = clippingRangeFromSliderRange(newClippingSliderRange)
      var constrainedStart = proposedRange.start
      var constrainedEnd = proposedRange.endInclusive
      if (constrainedEnd - constrainedStart < minProgressDelta) {
        if (isDraggingStartThumb) {
          constrainedStart = (constrainedEnd - minProgressDelta).coerceAtLeast(0f)
        } else {
          constrainedEnd = (constrainedStart + minProgressDelta).coerceAtMost(1f)
        }
      }
      state.clippingRange = constrainedStart..constrainedEnd
      onClippingRangeChange(state.clippingRangeMs)
    },
    modifier = modifier,
    enabled = state.durationMs > 0,
    onValueChangeFinished = {
      if (!isDragging) return@RangeSlider
      state.committedClippingRange = state.clippingRange
      val snapPosition =
        if (isDraggingStartThumb) state.clippingRange.start else state.clippingRange.endInclusive
      state.seekTo(snapPosition)
      onClippingRangeChangeFinished?.invoke()
    },
    startInteractionSource = startThumbInteractionSource,
    endInteractionSource = endThumbInteractionSource,
    startThumb = {
      val isStart = true
      val isAtLimit = clippingSliderRange.start <= BOUNDARY_EPSILON
      ClippingThumb(
        isStart,
        colors,
        shape,
        thumbPainter(isStart, isAtLimit),
        Modifier.fillMaxWidth(CLIPPING_THUMB_WIDTH_RATIO).fillMaxHeight(),
      )
    },
    endThumb = {
      val isStart = false
      val isAtLimit = clippingSliderRange.endInclusive >= 1f - BOUNDARY_EPSILON
      ClippingThumb(
        isStart,
        colors,
        shape,
        thumbPainter(isStart, isAtLimit),
        Modifier.fillMaxWidth(CLIPPING_THUMB_WIDTH_RATIO).fillMaxHeight(),
      )
    },
    track = {
      ClippingTrack(
        clippingSliderRangeProvider = { clippingSliderRange },
        colors.clippingFrameColor,
        Modifier.fillMaxSize(),
      )
    },
  )
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
 *
 * @param clippingSliderRangeProvider A provider that returns the current clipping range as a
 *   fraction (0.0 to 1.0) of the total duration.
 * @param clippingFrameColor The color used to draw the horizontal bars of the track.
 * @param modifier The [Modifier] to be applied to this composable.
 */
@Composable
private fun ClippingTrack(
  clippingSliderRangeProvider: () -> ClosedFloatingPointRange<Float>,
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
      val frameThickness = CLIPPING_FRAME_THICKNESS_RATIO * height
      onDrawBehind {
        val clippingSliderRange = clippingSliderRangeProvider()
        // Shift the start and end by half a thumb so that the horizontal bars are strictly between
        // the thumbs (instead of between the thumb centers). Shift again by 1 pixel to avoid holes
        // between the bars and the thumbs due to rounding errors.
        val clippingStartPx = clippingSliderRange.start * width + thumbWidthPx / 2f - 1f
        val clippingEndPx = clippingSliderRange.endInclusive * width - thumbWidthPx / 2f + 1f
        val frameWidth = clippingEndPx - clippingStartPx
        val rectSize = Size(width = frameWidth, height = frameThickness)
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

@Composable
private fun rememberClippingSliderState(
  player: Player?,
  positionTickCount: Int,
  initialClippingRangeMs: LongRange,
  minClippedDurationMs: Long,
): ClippingSliderState {
  val positionProgressState =
    rememberProgressStateWithTickCount(player, totalTickCount = positionTickCount)
  val clippingSliderState =
    remember(player, positionProgressState) {
      ClippingSliderState(
        player,
        positionProgressState,
        initialClippingRangeMs,
        minClippedDurationMs,
      )
    }
  LaunchedEffect(clippingSliderState) { clippingSliderState.observe() }
  return clippingSliderState
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

/**
 * Converts the clipping slider start and end positions to the actual clipping end points.
 *
 * The slider positions need to be corrected because they correspond to the center of the thumbs,
 * while the clipping range should correspond to the inside of the thumbs.
 *
 * This is the inverse of [sliderRangeFromClippingRange].
 *
 * @param clippingSliderRange The clipping slider range. Both values should be between 0 and 1.
 * @return The clipping range that should be applied to the media, expressed as a fraction of the
 *   total duration. Both values are between 0 and 1.
 */
private fun clippingRangeFromSliderRange(
  clippingSliderRange: ClosedFloatingPointRange<Float>
): ClosedFloatingPointRange<Float> {
  // The distance between the min clipping start (corresponding to the inside of the left thumb when
  // at the start of the slider) and the actual clipping start (corresponding to the inside of the
  // left thumb), divided by the total width.
  val clippingStartWidthRatio = clippingSliderRange.start * CLIPPING_TRACK_WIDTH_RATIO
  val clippingStart = clippingStartWidthRatio / POSITION_SLIDER_MAX_LENGTH_RATIO
  // The distance between the min clipping start and the actual clipping end (corresponding to the
  // inside of the right thumb), divided by the total width.
  val clippingEndWidthRatio =
    clippingSliderRange.endInclusive * CLIPPING_TRACK_WIDTH_RATIO -
      2 * CLIPPING_THUMB_PLAIN_WIDTH_RATIO + CLIPPING_THUMB_WIDTH_RATIO
  val clippingEnd = clippingEndWidthRatio / POSITION_SLIDER_MAX_LENGTH_RATIO
  return clippingStart..clippingEnd
}

/**
 * Converts the actual clipping range back into the clipping slider start and end positions.
 *
 * The clipping range needs to be corrected because the slider positions correspond to the center of
 * the thumbs, while the clipping range corresponds to the inside of the thumbs.
 *
 * This is the inverse of [clippingRangeFromSliderRange].
 *
 * @param clippingRange The clipping range that should be applied to the media, expressed as a
 *   fraction of the total duration. Both values should be between 0 and 1.
 * @return The clipping slider range. Both values are between 0 and 1.
 */
private fun sliderRangeFromClippingRange(
  clippingRange: ClosedFloatingPointRange<Float>
): ClosedFloatingPointRange<Float> {
  // The distance between the min slider start (corresponding to the center of the
  // left thumb when at the start of the slider) and the actual slider start
  // (corresponding to the center of the left thumb), divided by the total width.
  val sliderStartWidthRatio = clippingRange.start * POSITION_SLIDER_MAX_LENGTH_RATIO
  val sliderStart = sliderStartWidthRatio / CLIPPING_TRACK_WIDTH_RATIO
  // The distance between the min slider start (corresponding to the center of the
  // left thumb when at the start of the slider) and the actual slider end
  // (corresponding to the center of the right thumb), divided by the total width.
  val sliderEndWidthRatio =
    clippingRange.endInclusive * POSITION_SLIDER_MAX_LENGTH_RATIO +
      2 * CLIPPING_THUMB_PLAIN_WIDTH_RATIO - CLIPPING_THUMB_WIDTH_RATIO
  val sliderEnd = sliderEndWidthRatio / CLIPPING_TRACK_WIDTH_RATIO
  return sliderStart..sliderEnd
}

private val defaultClippingThumbPainterIcon:
  @Composable
  (isStart: Boolean, isAtLimit: Boolean) -> Painter =
  @Composable { isStart, isAtLimit ->
    if (isAtLimit) painterResource(R.drawable.media3_icon_clip_thumb_limit)
    else if (isStart) painterResource(R.drawable.media3_icon_clip_thumb_left_arrow)
    else painterResource(R.drawable.media3_icon_clip_thumb_right_arrow)
  }

private class ClippingSliderState(
  private val player: Player?,
  private val positionProgressState: ProgressStateWithTickCount,
  initialClippingRangeMs: LongRange,
  minClippedDurationMs: Long,
) {
  /** The current clipping range expressed as a fraction of the total duration (0 to 1). */
  var clippingRange by mutableStateOf(0f..1f)
  /**
   * The clipping range that has been confirmed by the user. This is typically updated when a drag
   * operation on the clipping thumbs finishes.
   */
  var committedClippingRange by mutableStateOf(0f..1f)

  /**
   * The current clipping range in milliseconds, preserving [C.TIME_END_OF_SOURCE] when the end
   * boundary is reached.
   */
  val clippingRangeMs: LongRange
    get() {
      val startMs = progressToPosition(clippingRange.start)
      val endMs =
        if (clippingRange.endInclusive >= 1f - BOUNDARY_EPSILON) {
          C.TIME_END_OF_SOURCE
        } else {
          progressToPosition(clippingRange.endInclusive)
        }
      return startMs..endMs
    }

  /**
   * The current playback position as a fraction of the total duration (0 to 1), or null if unknown.
   */
  val playbackProgress: Float?
    get() = if (durationMs > 0) positionProgressState.currentPositionProgress else null

  /** Returns whether the playback position has reached the clipping end position. */
  val isPlaybackAtEnd: Boolean
    get() {
      val progress = playbackProgress ?: return false
      return progress >= committedClippingRange.endInclusive
    }

  /** The total duration of the media in milliseconds, or [C.TIME_UNSET] if unknown/empty. */
  val durationMs: Long
    get() = positionProgressState.durationMs

  private var isPlaying by mutableStateOf(false)

  private val playerStateObserver: PlayerStateObserver? =
    player?.observeState(Player.EVENT_IS_PLAYING_CHANGED) { isPlaying = it.isPlaying }

  init {
    val unused = syncExternalRange(initialClippingRangeMs, minClippedDurationMs)
  }

  fun pause() {
    player?.let { if (it.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) it.pause() }
  }

  fun seekTo(progress: Float) {
    positionProgressState.updateCurrentPositionProgress(progress)
  }

  /** Converts a fraction (0 to 1) of the total duration into a position in milliseconds. */
  fun progressToPosition(progress: Float) = positionProgressState.progressToPosition(progress)

  /**
   * Syncs the state with an externally provided clipping range, enforcing constraints and seeking
   * the player if necessary.
   *
   * @param clippingRangeMs The new desired clipping range in milliseconds.
   * @param minClippedDurationMs The minimum permitted duration between clipping start and end in
   *   milliseconds.
   * @return Whether the requested range was adjusted to enforce constraints or match source bounds.
   */
  fun syncExternalRange(clippingRangeMs: LongRange, minClippedDurationMs: Long): Boolean {
    val (newRange, wasAdjusted) =
      calculateClippingRangeProgress(clippingRangeMs, minClippedDurationMs)
    clippingRange = newRange
    committedClippingRange = clippingRange
    val currentProgress = playbackProgress
    if (currentProgress != null) {
      if (currentProgress < clippingRange.start) {
        seekTo(clippingRange.start)
      } else if (currentProgress > clippingRange.endInclusive) {
        seekTo(clippingRange.endInclusive)
      }
    }
    return wasAdjusted
  }

  /**
   * Calculates the minimum progress delta required to prevent the clipping thumbs from overlapping.
   */
  fun calculateMinProgressDelta(minClippedDurationMs: Long): Float =
    if (durationMs <= 0) {
      MIN_CLIPPING_DELTA_FOR_NO_OVERLAP
    } else {
      maxOf(minClippedDurationMs.toFloat() / durationMs, MIN_CLIPPING_DELTA_FOR_NO_OVERLAP)
        .coerceAtMost(1f)
    }

  private fun play() {
    player?.let { if (it.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) it.play() }
  }

  /**
   * Converts a clipping range in milliseconds to a progress-based range (0 to 1), enforcing the
   * minimum progress delta constraint.
   *
   * @return A [Pair] containing the calculated progress range (0 to 1) and a boolean indicating
   *   whether the requested range was adjusted to enforce constraints or match source bounds.
   */
  private fun calculateClippingRangeProgress(
    clippingRangeMs: LongRange,
    minClippedDurationMs: Long,
  ): Pair<ClosedFloatingPointRange<Float>, Boolean> {
    if (durationMs <= 0) {
      return (0f..1f) to false
    }
    val rawStart = clippingRangeMs.first.toFloat() / durationMs
    var start = rawStart.coerceIn(0f, 1f)
    val originalEndMs =
      if (clippingRangeMs.last == C.TIME_END_OF_SOURCE) durationMs else clippingRangeMs.last
    val rawEnd = originalEndMs.toFloat() / durationMs
    var end = rawEnd.coerceIn(0f, 1f)
    val minProgressDelta = calculateMinProgressDelta(minClippedDurationMs)
    if (end - start < minProgressDelta) {
      end = (start + minProgressDelta).coerceAtMost(1f)
      start = (end - minProgressDelta).coerceAtLeast(0f)
    }
    val wasAdjusted = rawStart != start || rawEnd != end
    return (start..end) to wasAdjusted
  }

  suspend fun observe() {
    coroutineScope {
      launch { playerStateObserver?.observe() }
      launch {
        snapshotFlow { isPlaybackAtEnd && isPlaying }
          .collect { shouldLoop ->
            if (shouldLoop) {
              // TODO: b/505719491 - Fix playback exceeding clipping end before seeking to clipping
              //  start during playback
              pause()
              seekTo(committedClippingRange.start)
              play()
            }
          }
      }
    }
  }
}
