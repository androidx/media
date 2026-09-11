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
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.RangeSliderState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.demo.compose.R
import androidx.media3.ui.compose.state.PlayerStateObserver
import androidx.media3.ui.compose.state.ProgressStateWithTickCount
import androidx.media3.ui.compose.state.observeState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickCount
import com.google.common.collect.ImmutableList
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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
/** The ratio of the maximum progress slider length to the width of the image row. */
private const val PROGRESS_SLIDER_MAX_LENGTH_RATIO = 1f - 2 * CLIPPING_THUMB_PLAIN_WIDTH_RATIO
/** The ratio of the clipping frame's horizontal bar thickness to the total height of the slider. */
private const val CLIPPING_FRAME_THICKNESS_RATIO = 0.05f

/** The minimum clipping progress delta required to prevent the clipping thumbs from overlapping. */
private const val MIN_CLIPPING_DELTA_FOR_NO_OVERLAP =
  2f * (CLIPPING_THUMB_WIDTH_RATIO - CLIPPING_THUMB_PLAIN_WIDTH_RATIO) /
    PROGRESS_SLIDER_MAX_LENGTH_RATIO

/**
 * A small epsilon value used to check if a progress value is close to the boundaries (0.0 or 1.0).
 */
private const val BOUNDARY_EPSILON = 1e-3f

/**
 * A small positive weight used for layout spacers to avoid zero-weight exceptions in [Row] layout.
 */
private const val MIN_LAYOUT_WEIGHT = 1e-4f

/** The width of the playback position thumb. */
private val POSITION_THUMB_WIDTH = 4.dp

/** The ratio of the playback position thumb height to the slider track height. */
private const val POSITION_THUMB_HEIGHT_RATIO = 1.1f

// TODO: b/505719491
//  - Implement accessibility requirements
//  - Move to material3 module and mark API unstable
//  - Add tests
//  - Optimize ProgressSlider recomposition scope (e.g. sliderColors reading playbackProgress on
// every frame)
/**
 * A Material3 clipping slider that allows users to select a clipping range and track playback
 * position.
 *
 * This component displays a row of bitmaps representing the media content, with a range slider
 * overlaid to define the start and end clipping points. A secondary progress slider allows for
 * seeking within the selected range.
 *
 * This component does not update the player's clipping configuration. The caller is intended to
 * update the clipping configuration (and potentially apply other edits) at the end of the editing
 * experience.
 *
 * @param player The [Player] whose content to clip.
 * @param bitmaps A list of [Bitmap] instances to display as a background preview for the slider.
 *   All bitmaps in the list should have the same size. Because the slider's aspect ratio depends on
 *   the number of bitmaps, the list size should remain fixed, as changing it dynamically will cause
 *   the slider layout to resize each time the list grows or shrinks (for example, when loading
 *   frames asynchronously, pass a fixed-size list upfront using placeholder bitmaps and update
 *   elements as frames load). If this list is empty, the component will render an empty [Box]
 *   instead.
 * @param modifier The [Modifier] to be applied to the slider.
 * @param initialClippingRangeMs The initial clipping range in milliseconds.
 * @param onClippingRangeChange A callback that is invoked continuously as one of the clipping
 *   thumbs is being dragged. The [LongRange] represents the clipping start and end positions in
 *   milliseconds (or [C.TIME_END_OF_SOURCE] as the end position when the clip is untrimmed at the
 *   end of the media).
 * @param onClippingRangeChangeFinished A callback that is invoked when the user finishes dragging a
 *   clipping thumb.
 * @param onProgressChange A callback that is invoked continuously when the user drags the playback
 *   position thumb. The [Float] value represents the playback progress as a fraction between 0.0
 *   and 1.0.
 * @param onProgressChangeFinished A callback that is invoked when the user finishes dragging the
 *   playback position thumb.
 * @param scope The [CoroutineScope] to use for listening to player progress updates.
 * @param minClippedDurationMs The minimum allowed duration of the clipped range in milliseconds.
 *   The slider will prevent the user from selecting a range shorter than this value.
 * @param colors The [ClippingSliderColors] used to style the slider.
 * @param shape The [RoundedCornerShape] used to define the slider's shape.
 * @param clippingThumbPainter A composable lambda that provides icons for the clipping thumbs. The
 *   first boolean passed to the lambda indicates which of the two clipping handles is currently
 *   being painted. The second boolean indicates whether the thumb has reached its absolute boundary
 *   within the media (start of media for the start thumb, end of the media for the end thumb).
 */
@Composable
fun ClippingSlider(
  player: Player?,
  bitmaps: ImmutableList<Bitmap>,
  modifier: Modifier = Modifier,
  initialClippingRangeMs: LongRange = 0L..C.TIME_END_OF_SOURCE,
  onClippingRangeChange: ((LongRange) -> Unit)? = null,
  onClippingRangeChangeFinished: (() -> Unit)? = null,
  onProgressChange: ((Float) -> Unit)? = null,
  onProgressChangeFinished: (() -> Unit)? = null,
  scope: CoroutineScope = rememberCoroutineScope(),
  minClippedDurationMs: Long = 1000L,
  colors: ClippingSliderColors = ClippingSliderDefaults.colors(),
  shape: RoundedCornerShape = RoundedCornerShape(percent = 30),
  clippingThumbPainter: @Composable (isStart: Boolean, isAtLimit: Boolean) -> Painter =
    defaultClippingThumbPainter,
) {
  var positionTickCount by remember { mutableIntStateOf(0) }
  val state =
    rememberClippingSliderState(
      player = player,
      positionTickCount = positionTickCount,
      initialClippingRangeMs = initialClippingRangeMs,
      minClippedDurationMs = minClippedDurationMs,
      onClippingRangeChange = onClippingRangeChange,
      onClippingRangeChangeFinished = onClippingRangeChangeFinished,
      onProgressChange = onProgressChange,
      onProgressChangeFinished = onProgressChangeFinished,
      scope = scope,
    )

  LaunchedEffect(state.isUserInteracting, minClippedDurationMs, state.durationMs) {
    if (state.isUserInteracting) return@LaunchedEffect

    val wasAdjusted = state.enforceConstraints(minClippedDurationMs)
    if (wasAdjusted) {
      state.onClippingRangeChange?.invoke(state.clippingRangeMs)
    }
  }

  ClippingSlider(
    state = state,
    bitmaps = bitmaps,
    modifier =
      modifier.onSizeChanged { size ->
        positionTickCount = (PROGRESS_SLIDER_MAX_LENGTH_RATIO * size.width).roundToInt()
      },
    onClippingRangeChange = onClippingRangeChange,
    onClippingRangeChangeFinished = onClippingRangeChangeFinished,
    onProgressChange = onProgressChange,
    onProgressChangeFinished = onProgressChangeFinished,
    colors = colors,
    shape = shape,
    clippingThumbPainter = clippingThumbPainter,
  )
}

/**
 * A Material3 clipping slider that allows users to select a clipping range and track playback
 * position using a [ClippingSliderState].
 *
 * @param state The [ClippingSliderState] controlling the slider.
 * @param bitmaps A list of [Bitmap] instances to display as a background preview for the slider.
 *   All bitmaps in the list should have the same size. Because the slider's aspect ratio depends on
 *   the number of bitmaps, the list size should remain fixed, as changing it dynamically will cause
 *   the slider layout to resize each time the list grows or shrinks (for example, when loading
 *   frames asynchronously, pass a fixed-size list upfront using placeholder bitmaps and update
 *   elements as frames load). If this list is empty, the component will render an empty [Box]
 *   instead.
 * @param modifier The [Modifier] to be applied to the slider.
 * @param onClippingRangeChange A callback that is invoked continuously when the user drags a
 *   clipping thumb.
 * @param onClippingRangeChangeFinished A callback that is invoked when the user finishes dragging a
 *   clipping thumb.
 * @param onProgressChange A callback that is invoked continuously when the user drags the playback
 *   position thumb.
 * @param onProgressChangeFinished A callback that is invoked when the user finishes dragging the
 *   playback position thumb.
 * @param colors The [ClippingSliderColors] used to style the slider.
 * @param shape The [RoundedCornerShape] used to define the slider's shape.
 * @param clippingThumbPainter A composable lambda that provides icons for the clipping thumbs. The
 *   first boolean passed to the lambda indicates which of the two clipping handles is currently
 *   being painted. The second boolean indicates whether the thumb has reached its absolute boundary
 *   within the media (start of media for the start thumb, end of the media for the end thumb).
 */
@Composable
private fun ClippingSlider(
  state: ClippingSliderState,
  bitmaps: ImmutableList<Bitmap>,
  modifier: Modifier = Modifier,
  onClippingRangeChange: ((LongRange) -> Unit)? = null,
  onClippingRangeChangeFinished: (() -> Unit)? = null,
  onProgressChange: ((Float) -> Unit)? = null,
  onProgressChangeFinished: (() -> Unit)? = null,
  colors: ClippingSliderColors = ClippingSliderDefaults.colors(),
  shape: RoundedCornerShape = RoundedCornerShape(percent = 30),
  clippingThumbPainter: @Composable (isStart: Boolean, isAtLimit: Boolean) -> Painter =
    defaultClippingThumbPainter,
) {
  if (onClippingRangeChange != null) {
    state.onClippingRangeChange = onClippingRangeChange
  }
  if (onClippingRangeChangeFinished != null) {
    state.onClippingRangeChangeFinished = onClippingRangeChangeFinished
  }
  if (onProgressChange != null) {
    state.onProgressChange = onProgressChange
  }
  if (onProgressChangeFinished != null) {
    state.onProgressChangeFinished = onProgressChangeFinished
  }
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
    ClippingRangeSlider(
      rangeSliderState = state.rangeSliderState,
      bitmaps = bitmaps,
      modifier = Modifier.fillMaxSize(),
      enabled = state.changingProgressEnabled && state.durationMs > 0,
      startThumbInteractionSource = state.startThumbInteractionSource,
      endThumbInteractionSource = state.endThumbInteractionSource,
      clippingRangeProvider = { state.clippingRange },
      onValueChangeFinished = { state.onRangeSliderValueChangeFinished() },
      colors = colors,
      shape = shape,
      clippingThumbPainter = clippingThumbPainter,
    )
    ProgressSlider(
      state = state.progressSliderState,
      modifier = Modifier.fillMaxSize(),
      enabled = state.changingProgressEnabled && state.durationMs > 0,
      trackRange = state.activeValueRange,
      onValueChange = { state.onProgressSliderValueChange(it) },
      onValueChangeFinished = { state.onProgressSliderValueChangeFinished() },
      interactionSource = state.progressThumbInteractionSource,
      positionThumbColor = colors.positionThumbColor,
    )
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
 * A composable that draws a visual filter (overlay) over the inactive track areas of the slider.
 *
 * This component dims the regions of the image row that fall outside the active clipping range (to
 * the left of the start thumb and to the right of the end thumb).
 *
 * @param clippingRangeProvider A provider that returns the current active clipping range as a
 *   fraction (0.0 to 1.0) of the total duration.
 * @param inactiveTrackColor The color (usually translucent) used to overlay the inactive track
 *   areas.
 * @param modifier The [Modifier] to be applied to this composable.
 */
@Composable
private fun InactiveTrackFilter(
  clippingRangeProvider: () -> ClosedFloatingPointRange<Float>,
  inactiveTrackColor: Color,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier) {
    val clippingRange = clippingRangeProvider()
    val width = size.width
    val height = size.height
    val progressSliderStart = logicalToVisualProgressSliderStart(clippingRange.start) * width
    if (progressSliderStart > 0f) {
      drawRect(inactiveTrackColor, size = Size(progressSliderStart, height))
    }
    val progressSliderEnd = logicalToVisualProgressSliderEnd(clippingRange.endInclusive) * width
    if (progressSliderEnd < width) {
      drawRect(
        inactiveTrackColor,
        topLeft = Offset(x = progressSliderEnd, y = 0f),
        size = Size(width - progressSliderEnd, height),
      )
    }
  }
}

/**
 * A composable that renders the range slider used to select the clipping range over preview
 * bitmaps.
 *
 * This component displays the background [ImageRow], the [InactiveTrackFilter] overlay, and a
 * [RangeSlider] configured with custom thumbs ([ClippingThumb]) and track ([ClippingTrack]).
 *
 * @param rangeSliderState The [RangeSliderState] controlling the slider thumbs.
 * @param bitmaps A list of [Bitmap] instances to display as a background preview for the slider.
 *   All bitmaps in the list should have the same size. Because the slider's aspect ratio depends on
 *   the number of bitmaps, the list size should remain fixed, as changing it dynamically will cause
 *   the slider layout to resize each time the list grows or shrinks (for example, when loading
 *   frames asynchronously, pass a fixed-size list upfront using placeholder bitmaps and update
 *   elements as frames load). If this list is empty, the [ClippingSlider] component will render an
 *   empty [Box] instead.
 * @param modifier The [Modifier] to be applied to this composable.
 * @param enabled Whether interaction with the range slider is enabled.
 * @param startThumbInteractionSource The [MutableInteractionSource] for the start clipping thumb.
 * @param endThumbInteractionSource The [MutableInteractionSource] for the end clipping thumb.
 * @param clippingRangeProvider A provider returning the current clipping range as fractions (0.0 to
 *   1.0) of the total duration.
 * @param colors The [ClippingSliderColors] used to style the slider.
 * @param shape The [RoundedCornerShape] used to define the slider's shape.
 * @param clippingThumbPainter A composable lambda that provides icons for the clipping thumbs. The
 *   first boolean passed to the lambda indicates which of the two clipping handles is currently
 *   being painted. The second boolean indicates whether the thumb has reached its absolute boundary
 *   within the media (start of media for the start thumb, end of the media for the end thumb).
 */
@Composable
private fun ClippingRangeSlider(
  rangeSliderState: RangeSliderState,
  bitmaps: ImmutableList<Bitmap>,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  startThumbInteractionSource: MutableInteractionSource = remember { MutableInteractionSource() },
  endThumbInteractionSource: MutableInteractionSource = remember { MutableInteractionSource() },
  clippingRangeProvider: () -> ClosedFloatingPointRange<Float> = {
    clippingRangeFromSliderRange(rangeSliderState.startValue..rangeSliderState.endValue)
  },
  onValueChangeFinished: (() -> Unit)? = null,
  colors: ClippingSliderColors = ClippingSliderDefaults.colors(),
  shape: RoundedCornerShape = RoundedCornerShape(percent = 30),
  clippingThumbPainter: @Composable (isStart: Boolean, isAtLimit: Boolean) -> Painter =
    defaultClippingThumbPainter,
) {
  Box(modifier = modifier) {
    ImageRow(bitmaps, Modifier.fillMaxWidth().clip(shape))
    InactiveTrackFilter(
      clippingRangeProvider = clippingRangeProvider,
      colors.inactiveTrackColor,
      Modifier.fillMaxSize().clip(shape),
    )
    RangeSlider(
      state = rangeSliderState,
      // TODO: b/505719491 - Once onValueChange and onValueChangeFinished callbacks are added to
      // this stateful overload, move them here from RangeSliderState, and implement minRangeDelta
      // clamping logic
      modifier = Modifier.fillMaxSize(),
      enabled = enabled,
      onValueChangeFinished = onValueChangeFinished,
      startThumbInteractionSource = startThumbInteractionSource,
      endThumbInteractionSource = endThumbInteractionSource,
      startThumb = {
        val isStart = true
        val isAtLimit = rangeSliderState.startValue <= BOUNDARY_EPSILON
        ClippingThumb(
          isStart,
          colors,
          shape,
          clippingThumbPainter(isStart, isAtLimit),
          Modifier.fillMaxWidth(CLIPPING_THUMB_WIDTH_RATIO).fillMaxHeight(),
        )
      },
      endThumb = {
        val isStart = false
        val isAtLimit = rangeSliderState.endValue >= 1f - BOUNDARY_EPSILON
        ClippingThumb(
          isStart,
          colors,
          shape,
          clippingThumbPainter(isStart, isAtLimit),
          Modifier.fillMaxWidth(CLIPPING_THUMB_WIDTH_RATIO).fillMaxHeight(),
        )
      },
      track = {
        ClippingTrack(
          clippingSliderRangeProvider = { rangeSliderState.startValue..rangeSliderState.endValue },
          colors.clippingFrameColor,
          Modifier.fillMaxSize(),
        )
      },
    )
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
      colorFilter = ColorFilter.tint(colors.clippingThumbIconColor),
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

/**
 * A composable that renders the playback position scrubber slider within the clipping bounds.
 *
 * @param state The [SliderState] controlling the playback position thumb.
 * @param modifier The [Modifier] to be applied to this composable.
 * @param enabled Whether interaction with the progress slider is enabled.
 * @param trackRange The allowed range of values for the progress slider, corresponding to the
 *   committed clipping range.
 * @param interactionSource The [MutableInteractionSource] for the progress slider.
 * @param positionThumbColor The color used to render the playback position thumb.
 */
@Composable
private fun ProgressSlider(
  state: SliderState,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  trackRange: ClosedFloatingPointRange<Float> = state.trackRange,
  onValueChange: ((Float) -> Unit)? = null,
  onValueChangeFinished: (() -> Unit)? = null,
  interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
  positionThumbColor: Color = ClippingSliderDefaults.colors().positionThumbColor,
) {
  // Use trackRange (which corresponds to the pre-drag clipping bounds) to compute the progress
  // slider layout so it remains visually stable during drag gestures.
  val visualProgressSliderStart = logicalToVisualProgressSliderStart(trackRange.start)
  val visualProgressSliderEnd = logicalToVisualProgressSliderEnd(trackRange.endInclusive)
  val density = LocalDensity.current
  var sliderHeight by remember { mutableStateOf(0.dp) }
  Row(modifier) {
    Spacer(modifier = Modifier.weight(visualProgressSliderStart.coerceAtLeast(MIN_LAYOUT_WEIGHT)))
    Box(
      modifier =
        Modifier.weight(
            (visualProgressSliderEnd - visualProgressSliderStart).coerceAtLeast(MIN_LAYOUT_WEIGHT)
          )
          .fillMaxHeight()
          .onSizeChanged { size -> sliderHeight = with(density) { size.height.toDp() } }
    ) {
      val sliderColors =
        SliderDefaults.colors(
          thumbColor = positionThumbColor,
          activeTrackColor = Color.Transparent,
          inactiveTrackColor = Color.Transparent,
          disabledThumbColor = Color.Transparent,
          disabledActiveTrackColor = Color.Transparent,
          disabledInactiveTrackColor = Color.Transparent,
        )

      Slider(
        state = state,
        enabled = enabled,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier =
          Modifier.fillMaxWidth()
            // Use unbounded height so the slider and its position thumb can exceed the track height
            // (sliderHeight) without being clamped by the parent Box's maxHeight constraint.
            .wrapContentHeight(unbounded = true)
            // Expand the slider by 1px on each side to avoid rounding gaps. Row measurement rounds
            // weighted dimensions to the nearest integer pixel, which can leave a sub-pixel gap
            // between the clipping thumbs and the slider where the background image is visible.
            // This 1px overlap ensures the seam is always covered.
            .layout { measurable, constraints ->
              val expandedConstraints = constraints.offset(horizontal = 2)
              val placeable = measurable.measure(expandedConstraints)
              layout(placeable.width - 2, placeable.height) { placeable.place(x = -1, y = 0) }
            },
        interactionSource = interactionSource,
        colors = sliderColors,
        thumb = {
          if (enabled) {
            SliderDefaults.Thumb(
              interactionSource = interactionSource,
              colors = sliderColors,
              thumbSize =
                DpSize(
                  width = POSITION_THUMB_WIDTH,
                  height = sliderHeight * POSITION_THUMB_HEIGHT_RATIO,
                ),
            )
          }
        },
      )
    }
    Spacer(
      modifier = Modifier.weight((1f - visualProgressSliderEnd).coerceAtLeast(MIN_LAYOUT_WEIGHT))
    )
  }
}

@Composable
private fun rememberClippingSliderState(
  player: Player?,
  positionTickCount: Int,
  initialClippingRangeMs: LongRange = 0L..C.TIME_END_OF_SOURCE,
  minClippedDurationMs: Long,
  onClippingRangeChange: ((LongRange) -> Unit)? = null,
  onClippingRangeChangeFinished: (() -> Unit)? = null,
  onProgressChange: ((Float) -> Unit)? = null,
  onProgressChangeFinished: (() -> Unit)? = null,
  scope: CoroutineScope = rememberCoroutineScope(),
): ClippingSliderState {
  val positionProgressState =
    rememberProgressStateWithTickCount(
      player = player,
      totalTickCount = positionTickCount,
      scope = scope,
    )
  val currentOnClippingRangeChange by rememberUpdatedState(onClippingRangeChange)
  val currentOnClippingRangeChangeFinished by rememberUpdatedState(onClippingRangeChangeFinished)
  val currentOnProgressChange by rememberUpdatedState(onProgressChange)
  val currentOnProgressChangeFinished by rememberUpdatedState(onProgressChangeFinished)
  val clippingSliderState =
    remember(player, positionProgressState) {
      ClippingSliderState(
        player,
        positionProgressState,
        initialClippingRangeMs,
        minClippedDurationMs,
      )
    }
  clippingSliderState.onClippingRangeChange = currentOnClippingRangeChange
  clippingSliderState.onClippingRangeChangeFinished = currentOnClippingRangeChangeFinished
  clippingSliderState.onProgressChange = currentOnProgressChange
  clippingSliderState.onProgressChangeFinished = currentOnProgressChangeFinished
  clippingSliderState.minClippedDurationMs = minClippedDurationMs
  LaunchedEffect(clippingSliderState) { clippingSliderState.observe() }
  return clippingSliderState
}

/**
 * Returns the visual ratio for the progress slider start compared to the width of the image row.
 *
 * The progress slider lies between the plain portions of the clipping handles.
 *
 * @param clippingStart The clipping start position of the media, expressed as a ratio of the
 *   duration (value between 0 and 1).
 */
private fun logicalToVisualProgressSliderStart(clippingStart: Float): Float =
  (clippingStart * PROGRESS_SLIDER_MAX_LENGTH_RATIO) + CLIPPING_THUMB_PLAIN_WIDTH_RATIO

/**
 * Returns the visual ratio for the progress slider end compared to the width of the image row.
 *
 * The progress slider lies between the plain portions of the clipping handles.
 *
 * @param clippingEnd The clipping end position of the media, expressed as a ratio of the duration
 *   (value between 0 and 1).
 */
private fun logicalToVisualProgressSliderEnd(clippingEnd: Float): Float =
  (clippingEnd * PROGRESS_SLIDER_MAX_LENGTH_RATIO) + CLIPPING_THUMB_PLAIN_WIDTH_RATIO

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
  val clippingStart = clippingStartWidthRatio / PROGRESS_SLIDER_MAX_LENGTH_RATIO
  // The distance between the min clipping start and the actual clipping end (corresponding to the
  // inside of the right thumb), divided by the total width.
  val clippingEndWidthRatio =
    clippingSliderRange.endInclusive * CLIPPING_TRACK_WIDTH_RATIO -
      2 * CLIPPING_THUMB_PLAIN_WIDTH_RATIO + CLIPPING_THUMB_WIDTH_RATIO
  val clippingEnd = clippingEndWidthRatio / PROGRESS_SLIDER_MAX_LENGTH_RATIO
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
 *   fraction of the total duration. Both values are between 0 and 1.
 * @return The clipping slider range. Both values are between 0 and 1.
 */
private fun sliderRangeFromClippingRange(
  clippingRange: ClosedFloatingPointRange<Float>
): ClosedFloatingPointRange<Float> {
  // The distance between the min slider start (corresponding to the center of the
  // left thumb when at the start of the slider) and the actual slider start
  // (corresponding to the center of the left thumb), divided by the total width.
  val sliderStartWidthRatio = clippingRange.start * PROGRESS_SLIDER_MAX_LENGTH_RATIO
  val sliderStart = sliderStartWidthRatio / CLIPPING_TRACK_WIDTH_RATIO
  // The distance between the min slider start (corresponding to the center of the
  // left thumb when at the start of the slider) and the actual slider end
  // (corresponding to the center of the right thumb), divided by the total width.
  val sliderEndWidthRatio =
    clippingRange.endInclusive * PROGRESS_SLIDER_MAX_LENGTH_RATIO +
      2 * CLIPPING_THUMB_PLAIN_WIDTH_RATIO - CLIPPING_THUMB_WIDTH_RATIO
  val sliderEnd = sliderEndWidthRatio / CLIPPING_TRACK_WIDTH_RATIO
  return sliderStart..sliderEnd
}

private val defaultClippingThumbPainter:
  @Composable
  (isStart: Boolean, isAtLimit: Boolean) -> Painter =
  @Composable { isStart, isAtLimit ->
    if (isAtLimit) painterResource(R.drawable.media3_icon_clip_thumb_limit)
    else if (isStart) painterResource(R.drawable.media3_icon_clip_thumb_left_arrow)
    else painterResource(R.drawable.media3_icon_clip_thumb_right_arrow)
  }

/**
 * Calculates the minimum progress delta required to prevent the clipping thumbs from overlapping.
 */
private fun calculateMinRangeDelta(minClippedDurationMs: Long, durationMs: Long): Float =
  if (durationMs <= 0) {
    MIN_CLIPPING_DELTA_FOR_NO_OVERLAP
  } else {
    maxOf(minClippedDurationMs.toFloat() / durationMs, MIN_CLIPPING_DELTA_FOR_NO_OVERLAP)
      .coerceAtMost(1f)
  }

/**
 * State object that manages a [ClippingSlider] connected to a [Player].
 *
 * @param player The [Player] to observe and control, or `null`.
 * @param positionProgressState The [ProgressStateWithTickCount] tracking media progress.
 * @param initialClippingRangeMs The initial clipping range in milliseconds.
 * @param initialMinClippedDurationMs The initial minimum allowed duration between clipping start
 *   and end in milliseconds.
 */
private class ClippingSliderState(
  private val player: Player?,
  private val positionProgressState: ProgressStateWithTickCount,
  private val initialClippingRangeMs: LongRange,
  initialMinClippedDurationMs: Long,
) {
  var minClippedDurationMs by mutableLongStateOf(initialMinClippedDurationMs)
  var onClippingRangeChange: ((LongRange) -> Unit)? = null
  var onClippingRangeChangeFinished: (() -> Unit)? = null
  var onProgressChange: ((Float) -> Unit)? = null
  var onProgressChangeFinished: (() -> Unit)? = null
  val startThumbInteractionSource = MutableInteractionSource()
  val endThumbInteractionSource = MutableInteractionSource()
  val progressThumbInteractionSource = MutableInteractionSource()
  private var lastChangedBoundaryIsStart = true
  val rangeSliderState = RangeSliderState(startValue = 0f, endValue = 1f)

  fun onRangeSliderValueChangeFinished() {
    val snapPosition =
      if (lastChangedBoundaryIsStart) clippingRange.start else clippingRange.endInclusive
    seekTo(snapPosition)
    isClipping = false
    isUserInteracting = false
    preDragClippingRange = clippingRange
    updateProgressSliderRange(clippingRange)
    onClippingRangeChangeFinished?.invoke()
  }

  var progressSliderState: SliderState by mutableStateOf(createProgressSliderState(0f..1f))
    private set

  private fun createProgressSliderState(
    range: ClosedFloatingPointRange<Float>,
    initialValue: Float = range.start,
  ): SliderState =
    SliderState(
      // Coerce within the active clipping range so the position thumb sticks to the clipping
      // thumb when the clipping thumb crosses the position thumb.
      value = initialValue.coerceIn(range),
      trackRange = range,
    )

  fun onProgressSliderValueChange(newValue: Float) {
    val coerced =
      if (clippingRange.start < clippingRange.endInclusive) {
        newValue.coerceIn(clippingRange)
      } else {
        newValue
      }
    progressSliderState.value = coerced
    onProgressChange?.invoke(coerced)
  }

  fun onProgressSliderValueChangeFinished() {
    seekTo(progressSliderState.value)
    isUserInteracting = false
    onProgressChangeFinished?.invoke()
  }

  private fun updateProgressSliderRange(range: ClosedFloatingPointRange<Float>) {
    if (range.start >= range.endInclusive) return
    if (progressSliderState.trackRange == range) return
    val currentValue = progressSliderState.value.coerceIn(range)
    progressSliderState = createProgressSliderState(range, currentValue)
  }

  /** The current clipping range expressed as a fraction of the total duration (0 to 1). */
  val clippingRange: ClosedFloatingPointRange<Float>
    get() = clippingRangeFromSliderRange(rangeSliderState.startValue..rangeSliderState.endValue)

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

  /**
   * Whether the user is actively interacting with the slider (either adjusting clipping or
   * scrubbing progress).
   */
  var isUserInteracting by mutableStateOf(false)

  /** Whether the user is actively dragging one of the clipping thumbs. */
  private var isClipping by mutableStateOf(false)

  /**
   * The clipping range as a fraction of the total duration (0 to 1) captured before the current
   * interaction started.
   */
  var preDragClippingRange by mutableStateOf(0f..1f)
    private set

  /**
   * The value range used by the progress slider to layout its track. Anchors to
   * [preDragClippingRange] while [isClipping] is true so that the progress scrubber layout remains
   * visually stable while clipping thumbs are being dragged.
   */
  val activeValueRange: ClosedFloatingPointRange<Float>
    get() = if (isClipping) preDragClippingRange else clippingRange

  /** Whether changing the playback progress is enabled. */
  val changingProgressEnabled: Boolean
    get() = positionProgressState.changingProgressEnabled

  /** The total duration of the media in milliseconds, or [C.TIME_UNSET] if unknown/empty. */
  val durationMs: Long
    get() = positionProgressState.durationMs

  /**
   * The intended playback state, tracked synchronously to prevent race conditions during UI
   * interactions.
   */
  private var playWhenReady by mutableStateOf(player?.playWhenReady ?: false)

  private var isPlaying by mutableStateOf(false)

  private val playerStateObserver: PlayerStateObserver? =
    player?.observeState(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED) {
      player ->
      isPlaying = player.isPlaying
      playWhenReady = player.playWhenReady
    }

  init {
    setClippingRange(initialClippingRangeMs)
  }

  fun pause() {
    if (!playWhenReady) return
    playWhenReady = false
    player?.let { if (it.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) it.pause() }
  }

  fun seekTo(progress: Float) {
    positionProgressState.updateCurrentPositionProgress(progress)
  }

  /** Converts a fraction (0 to 1) of the total duration into a position in milliseconds. */
  fun progressToPosition(progress: Float) = positionProgressState.progressToPosition(progress)

  fun onDragStarted(isStart: Boolean) {
    lastChangedBoundaryIsStart = isStart
    preDragClippingRange = clippingRange
    isClipping = true
    isUserInteracting = true
    pause()
  }

  /**
   * Sets the clipping range, enforcing constraints against media duration and
   * [minClippedDurationMs].
   *
   * @param clippingRangeMs The desired clipping range in milliseconds.
   */
  fun setClippingRange(clippingRangeMs: LongRange) {
    val (newRange, _) = calculateClippingRangeProgress(clippingRangeMs, minClippedDurationMs)
    applyClippingRange(newRange, updateSlider = true)
  }

  /**
   * Validates the internal clipping range against current constraints and media duration. If the
   * bounds violate constraints (e.g., they are too close together or exceed media bounds), they are
   * clamped to legal values.
   *
   * @param minClippedDurationMs The minimum permitted duration between clipping start and end.
   * @return Whether the internal range was adjusted to enforce constraints.
   */
  fun enforceConstraints(minClippedDurationMs: Long): Boolean {
    this.minClippedDurationMs = minClippedDurationMs
    val (newRange, wasAdjusted) =
      calculateClippingRangeProgress(this.clippingRangeMs, minClippedDurationMs)
    applyClippingRange(newRange, updateSlider = wasAdjusted)
    return wasAdjusted
  }

  private fun applyClippingRange(newRange: ClosedFloatingPointRange<Float>, updateSlider: Boolean) {
    if (updateSlider) {
      val sliderRange = sliderRangeFromClippingRange(newRange)
      if (rangeSliderState.startValue != sliderRange.start) {
        rangeSliderState.startValue = sliderRange.start
      }
      if (rangeSliderState.endValue != sliderRange.endInclusive) {
        rangeSliderState.endValue = sliderRange.endInclusive
      }
      preDragClippingRange = newRange
      updateProgressSliderRange(newRange)
    }

    // Ensure the player's current position hasn't been left outside the new bounds
    val currentProgress = playbackProgress
    if (currentProgress != null) {
      if (currentProgress < newRange.start) {
        seekTo(newRange.start)
      } else if (currentProgress > newRange.endInclusive) {
        seekTo(newRange.endInclusive)
      }
    }
  }

  private fun play() {
    if (playWhenReady) return
    playWhenReady = true
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
    val minRangeDelta = calculateMinRangeDelta(minClippedDurationMs, durationMs)
    if (end - start < minRangeDelta) {
      end = (start + minRangeDelta).coerceAtMost(1f)
      start = (end - minRangeDelta).coerceAtLeast(0f)
    }
    val wasAdjusted = rawStart != start || rawEnd != end
    return (start..end) to wasAdjusted
  }

  suspend fun observe() {
    coroutineScope {
      // Observe Player events (isPlaying, playWhenReady) to keep playback states synchronized.
      launch { playerStateObserver?.observe() }

      // Apply initial clipping range once media duration is known.
      launch {
        snapshotFlow { durationMs }.first { it > 0 }
        setClippingRange(initialClippingRangeMs)
      }

      // Update progress slider state range when activeValueRange changes.
      launch {
        snapshotFlow { activeValueRange }.collect { range -> updateProgressSliderRange(range) }
      }

      // Update progress slider state as playback progresses.
      launch {
        snapshotFlow { playbackProgress }
          .collect { progress ->
            if (
              !isUserInteracting &&
                progress != null &&
                clippingRange.start < clippingRange.endInclusive
            ) {
              progressSliderState.value = progress.coerceIn(clippingRange)
            }
          }
      }

      // Listen for touch/drag gestures on the start thumb to pause playback and record interaction.
      launch {
        startThumbInteractionSource.interactions.collect { interaction ->
          if (interaction is DragInteraction.Start || interaction is PressInteraction.Press) {
            onDragStarted(isStart = true)
          }
        }
      }

      // Listen for touch/drag gestures on the end thumb to pause playback and record interaction.
      launch {
        endThumbInteractionSource.interactions.collect { interaction ->
          if (interaction is DragInteraction.Start || interaction is PressInteraction.Press) {
            onDragStarted(isStart = false)
          }
        }
      }

      // Listen for touch/drag gestures on the progress thumb to pause playback and record
      // interaction.
      launch {
        progressThumbInteractionSource.interactions.collect { interaction ->
          if (interaction is DragInteraction.Start || interaction is PressInteraction.Press) {
            isUserInteracting = true
            pause()
          }
        }
      }

      // Observe slider movements to enforce minRangeDelta constraints.
      // TODO: b/505719491 - Potentially migrate to RangeSlider's onValueChange callback
      //  minRangeDelta clamping logic can be there instead of observing snapshotFlow here.
      launch {
        var previousRange = rangeSliderState.startValue..rangeSliderState.endValue
        snapshotFlow { rangeSliderState.startValue..rangeSliderState.endValue }
          .collect { currentRange ->
            if (currentRange.start != previousRange.start) {
              lastChangedBoundaryIsStart = true
            } else if (currentRange.endInclusive != previousRange.endInclusive) {
              lastChangedBoundaryIsStart = false
            }
            previousRange = currentRange

            if (isClipping) {
              val minDelta = calculateMinRangeDelta(minClippedDurationMs, durationMs)
              val proposedRange = clippingRangeFromSliderRange(currentRange)
              var start = proposedRange.start
              var end = proposedRange.endInclusive
              if (end - start < minDelta) {
                if (lastChangedBoundaryIsStart) {
                  start = (end - minDelta).coerceAtLeast(0f)
                } else {
                  end = (start + minDelta).coerceAtMost(1f)
                }
                val clampedSliderRange = sliderRangeFromClippingRange(start..end)
                if (rangeSliderState.startValue != clampedSliderRange.start) {
                  rangeSliderState.startValue = clampedSliderRange.start
                }
                if (rangeSliderState.endValue != clampedSliderRange.endInclusive) {
                  rangeSliderState.endValue = clampedSliderRange.endInclusive
                }
              }
              if (clippingRange.start < clippingRange.endInclusive) {
                progressSliderState.value = progressSliderState.value.coerceIn(clippingRange)
              }
              onClippingRangeChange?.invoke(clippingRangeMs)
            }
          }
      }

      // Loop playback back to clipping start when the player reaches or exceeds the clipping end.
      launch {
        snapshotFlow {
            val progress = playbackProgress
            !isUserInteracting &&
              playWhenReady &&
              isPlaying &&
              progress != null &&
              progress >= clippingRange.endInclusive
          }
          .collect { shouldLoop ->
            if (shouldLoop) {
              // TODO: b/505719491 - Fix playback exceeding clipping end before seeking to clipping
              //  start during playback
              pause()
              seekTo(clippingRange.start)
              play()
            }
          }
      }
    }
  }
}
