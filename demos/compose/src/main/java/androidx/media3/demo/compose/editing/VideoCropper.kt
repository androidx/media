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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import androidx.media3.common.Player
import androidx.media3.ui.compose.ContentFrame

/**
 * A reusable video cropping widget.
 *
 * This component displays the video and a crop overlay. It allows the user to resize the crop area
 * by dragging its corners (preserving the aspect ratio) and pan the video inside the crop area.
 *
 * @param state The [VideoCropperState] object holding the state. Created via
 *   [rememberVideoCropperState].
 * @param modifier The modifier to be applied to the cropping widget.
 * @param onCropRectChangeFinished A callback that is invoked when a user interaction (dragging a
 *   corner or panning the video) finishes.
 * @param cropFrameControls An optional overlay to be rendered on top of the crop frame.
 * @param colors The [VideoCropperColors] used to style the cropping widget. Defaults to
 *   [VideoCropperDefaults.colors].
 * @param cropFramePadding The padding values applied to the crop area boundaries, relative to the
 *   layout bounds of the [VideoCropper] composable. Defaults to
 *   [VideoCropperDefaults.CropFramePadding].
 * @param bracketThickness The stroke thickness of the corner brackets. Defaults to
 *   [VideoCropperDefaults.BracketThickness].
 * @param bracketLength The arm length of the corner brackets. Defaults to
 *   [VideoCropperDefaults.BracketLength].
 * @param cropFrameBorderThickness The stroke thickness of the frame border around the crop area.
 *   Defaults to [VideoCropperDefaults.CropFrameBorderThickness].
 * @param cropFrameShape The [RoundedCornerShape] defining the corner rounding radius of the crop
 *   frame and brackets. Defaults to [VideoCropperDefaults.CropFrameShape].
 * @param minCropSize The minimum width and height of the crop frame. Must be strictly positive.
 *   Defaults to [VideoCropperDefaults.MinCropSize].
 */
@Composable
fun VideoCropper(
  state: VideoCropperState,
  modifier: Modifier = Modifier,
  onCropRectChangeFinished: (() -> Unit)? = null,
  cropFrameControls: (@Composable BoxScope.() -> Unit)? = null,
  colors: VideoCropperColors = VideoCropperDefaults.colors(),
  cropFramePadding: PaddingValues = VideoCropperDefaults.CropFramePadding,
  bracketThickness: Dp = VideoCropperDefaults.BracketThickness,
  bracketLength: Dp = VideoCropperDefaults.BracketLength,
  cropFrameBorderThickness: Dp = VideoCropperDefaults.CropFrameBorderThickness,
  cropFrameShape: RoundedCornerShape = VideoCropperDefaults.CropFrameShape,
  minCropSize: Dp = VideoCropperDefaults.MinCropSize,
) {
  require(minCropSize > 0.dp) { "minCropSize must be strictly positive, but was $minCropSize" }

  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current

  val gestureState = remember { GestureState() }
  var cropperSize by remember { mutableStateOf(Size.Zero) }

  val isReady = !state.videoSize.isEmpty() && !cropperSize.isEmpty()
  val interactingProgressState =
    animateFloatAsState(targetValue = if (state.isInteracting) 1f else 0f)
  val videoFitRect =
    if (isReady) calculateVideoFitRect(cropperSize = cropperSize, videoSize = state.videoSize)
    else Rect.Zero
  val cropConstraintRect =
    if (isReady) {
      calculateCropConstraintRect(
        cropperSize = cropperSize,
        paddingValues = cropFramePadding,
        density = density,
        layoutDirection = layoutDirection,
      )
    } else {
      Rect.Zero
    }
  val transformation =
    remember(
      isReady,
      state,
      gestureState,
      interactingProgressState,
      videoFitRect,
      cropConstraintRect,
    ) {
      if (isReady) {
        CropFrameTransformation(
          cropperState = state,
          gestureState = gestureState,
          interactionProgressState = interactingProgressState,
          videoFitRect = videoFitRect,
          cropConstraintRect = cropConstraintRect,
        )
      } else {
        null
      }
    }

  val currentTransformation by rememberUpdatedState(transformation)
  val scaleProvider = remember { { currentTransformation?.scale ?: 1f } }
  val translationProvider = remember { { currentTransformation?.translation ?: Offset.Zero } }

  Box(modifier = modifier.clipToBounds().onSizeChanged { cropperSize = it.toSize() }) {
    // Render the player container unconditionally so the player can attach to the surface and
    // expose the video size.
    VideoPlayerContainer(
      player = state.player,
      scaleProvider = scaleProvider,
      translationProvider = translationProvider,
    )
  }
}

/**
 * A container that renders the [ContentFrame] video surface and applies the animated scale and
 * translation offsets.
 */
@Composable
private fun VideoPlayerContainer(
  player: Player?,
  scaleProvider: () -> Float,
  translationProvider: () -> Offset,
  modifier: Modifier = Modifier,
) {
  ContentFrame(
    player = player,
    modifier =
      modifier.fillMaxSize().graphicsLayer {
        scaleX = scaleProvider()
        scaleY = scaleProvider()
        val translation = translationProvider()
        translationX = translation.x
        translationY = translation.y
        transformOrigin = TransformOrigin(0f, 0f)
      },
  )
}

/**
 * A custom canvas that draws the dim overlay, the solid black crop frame border, and the corner
 * brackets.
 */
@Composable
private fun CropperOverlay(
  transformation: CropFrameTransformation,
  style: CropFrameStyle,
  modifier: Modifier = Modifier,
) {
  val cropFramePath = remember { Path() }
  val borderPath = remember { Path() }
  val bracketsPath = remember { Path() }
  val density = LocalDensity.current
  val bracketStroke =
    remember(density, style.bracketThickness) {
      val thicknessPx = with(density) { style.bracketThickness.toPx() }
      Stroke(width = thicknessPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

  Canvas(modifier = modifier.fillMaxSize()) {
    // Define the crop frame path as a rounded rectangle.
    val cropFrame = transformation.cropFrame
    val topLeftRadius = style.cropFrameShape.topStart.toPx(cropFrame.size, this)
    val topRightRadius = style.cropFrameShape.topEnd.toPx(cropFrame.size, this)
    val bottomRightRadius = style.cropFrameShape.bottomEnd.toPx(cropFrame.size, this)
    val bottomLeftRadius = style.cropFrameShape.bottomStart.toPx(cropFrame.size, this)
    cropFramePath.reset()
    cropFramePath.addRoundRect(
      RoundRect(
        rect = cropFrame,
        topLeft = CornerRadius(topLeftRadius),
        topRight = CornerRadius(topRightRadius),
        bottomRight = CornerRadius(bottomRightRadius),
        bottomLeft = CornerRadius(bottomLeftRadius),
      )
    )

    // Draw the dimming overlay and the outer border.
    val overlayColor =
      lerp(
        style.colors.idleOverlayColor,
        style.colors.interactingOverlayColor,
        transformation.progress,
      )
    val borderThicknessPx = style.cropFrameBorderThickness.toPx()
    val borderRect = cropFrame.inflate(borderThicknessPx)
    borderPath.reset()
    borderPath.addRoundRect(
      RoundRect(
        rect = borderRect,
        topLeft = CornerRadius(if (topLeftRadius > 0f) topLeftRadius + borderThicknessPx else 0f),
        topRight =
          CornerRadius(if (topRightRadius > 0f) topRightRadius + borderThicknessPx else 0f),
        bottomRight =
          CornerRadius(if (bottomRightRadius > 0f) bottomRightRadius + borderThicknessPx else 0f),
        bottomLeft =
          CornerRadius(if (bottomLeftRadius > 0f) bottomLeftRadius + borderThicknessPx else 0f),
      )
    )
    clipPath(path = cropFramePath, clipOp = ClipOp.Difference) {
      drawRect(color = overlayColor)
      drawPath(path = borderPath, color = style.colors.borderColor)
    }

    // Draw the corner brackets.
    val bracketThicknessPx = style.bracketThickness.toPx()
    val bracketOffset = borderThicknessPx + bracketThicknessPx / 2f
    val bracketLengthPx = style.bracketLength.toPx()
    bracketsPath.reset()
    bracketsPath.addCornerBracket(
      corner = cropFrame.topLeft + Offset(-bracketOffset, -bracketOffset),
      dirX = 1f,
      dirY = 1f,
      length = bracketLengthPx,
      radius = topLeftRadius + bracketOffset,
    )
    bracketsPath.addCornerBracket(
      corner = cropFrame.topRight + Offset(bracketOffset, -bracketOffset),
      dirX = -1f,
      dirY = 1f,
      length = bracketLengthPx,
      radius = topRightRadius + bracketOffset,
    )
    bracketsPath.addCornerBracket(
      corner = cropFrame.bottomLeft + Offset(-bracketOffset, bracketOffset),
      dirX = 1f,
      dirY = -1f,
      length = bracketLengthPx,
      radius = bottomLeftRadius + bracketOffset,
    )
    bracketsPath.addCornerBracket(
      corner = cropFrame.bottomRight + Offset(bracketOffset, bracketOffset),
      dirX = -1f,
      dirY = -1f,
      length = bracketLengthPx,
      radius = bottomRightRadius + bracketOffset,
    )
    drawPath(color = style.colors.bracketColor, path = bracketsPath, style = bracketStroke)
  }
}

/**
 * Calculates the baseline unscaled screen rectangle where the video is displayed inside
 * [cropperSize], fitting [videoSize] while preserving its aspect ratio (letterboxed or
 * pillarboxed).
 *
 * This represents the 1x baseline boundaries of the video surface before any zoom or translation
 * transformations are applied.
 */
private fun calculateVideoFitRect(cropperSize: Size, videoSize: Size): Rect =
  calculateMaxRectInContainer(
    aspectRatio = videoSize.width / videoSize.height,
    container = Rect(Offset.Zero, cropperSize),
  )

/**
 * Calculates the largest rectangle with the specified aspect ratio that can fit inside a container
 * rectangle, centering it.
 */
private fun calculateMaxRectInContainer(aspectRatio: Float, container: Rect): Rect {
  val containerAspectRatio = container.width / container.height
  val width: Float
  val height: Float
  if (aspectRatio > containerAspectRatio) {
    width = container.width
    height = container.width / aspectRatio
  } else {
    height = container.height
    width = container.height * aspectRatio
  }
  val left = container.left + (container.width - width) / 2
  val top = container.top + (container.height - height) / 2
  return Rect(left = left, top = top, right = left + width, bottom = top + height)
}

/**
 * Calculates the bounding rectangle that constrains the crop frame, by applying [paddingValues] to
 * the [cropperSize].
 *
 * The crop frame cannot be resized or positioned outside of this returned rectangle.
 *
 * @param cropperSize The total size of the cropper component, in pixels.
 * @param paddingValues The padding to apply to the boundaries.
 * @param density The density to use for converting DP padding values to pixels.
 * @param layoutDirection The layout direction to resolve left/right padding.
 * @return The constraint rectangle in pixel coordinates.
 */
private fun calculateCropConstraintRect(
  cropperSize: Size,
  paddingValues: PaddingValues,
  density: Density,
  layoutDirection: LayoutDirection,
): Rect {
  val left =
    with(density) { paddingValues.calculateLeftPadding(layoutDirection).toPx() }
      .coerceIn(0f, cropperSize.width - 1f)
  val top =
    with(density) { paddingValues.calculateTopPadding().toPx() }
      .coerceIn(0f, cropperSize.height - 1f)
  val right =
    (cropperSize.width -
        with(density) { paddingValues.calculateRightPadding(layoutDirection).toPx() })
      .coerceIn(left + 1f, cropperSize.width)
  val bottom =
    (cropperSize.height - with(density) { paddingValues.calculateBottomPadding().toPx() }).coerceIn(
      top + 1f,
      cropperSize.height,
    )
  return Rect(left = left, top = top, right = right, bottom = bottom)
}

private fun Rect.scale(scale: Float): Rect {
  return Rect(
    left = left * scale,
    top = top * scale,
    right = right * scale,
    bottom = bottom * scale,
  )
}

/**
 * Appends a rounded corner bracket contour to this [Path] starting from the specified [corner]
 * vertex extending along [dirX] and [dirY] directions.
 *
 * All coordinates and dimensions are in pixels.
 *
 * @param corner The corner vertex coordinate on screen.
 * @param dirX Horizontal direction multiplier (+1f for right, -1f for left).
 * @param dirY Vertical direction multiplier (+1f for down, -1f for up).
 * @param length The total length of each bracket arm.
 * @param radius The corner rounding radius where the bracket arms meet.
 */
private fun Path.addCornerBracket(
  corner: Offset,
  dirX: Float,
  dirY: Float,
  length: Float,
  radius: Float,
) {
  moveTo(corner.x + dirX * length, corner.y)
  lineTo(corner.x + dirX * radius, corner.y)
  val centerX = corner.x + dirX * radius
  val centerY = corner.y + dirY * radius
  arcTo(
    rect =
      Rect(
        left = centerX - radius,
        top = centerY - radius,
        right = centerX + radius,
        bottom = centerY + radius,
      ),
    startAngleDegrees = -90f * dirY,
    sweepAngleDegrees = -90f * dirX * dirY,
    forceMoveTo = false,
  )
  lineTo(corner.x, corner.y + dirY * length)
}

/**
 * State holder class managing gesture tracking and transformation calculations during corner drag
 * and panning gestures.
 *
 * This class serves as the source of truth for the active gesture session, tracking mutable state
 * that must persist across recompositions (such as the active corner and the accumulated drag
 * translation). It is decoupled from layout-specific details (like container padding) and UI
 * transition animations, which are managed by [CropFrameTransformation].
 */
@Stable
private class GestureState {

  /** Whether the user is currently panning the video inside the crop frame. */
  var isPanningVideo by mutableStateOf(false)
    private set

  /**
   * The video scale captured at the start of the interaction.
   *
   * This value remains strictly fixed (frozen) during the gesture. This ensures the video zoom
   * level stays stable and does not jump or animate while the user is actively resizing the crop
   * frame or panning the video.
   */
  var frozenVideoScale by mutableFloatStateOf(1f)
    private set

  /**
   * The video translation used during an active interaction (in pixels).
   *
   * - During corner dragging, it remains constant to freeze the video in place.
   * - During panning, it updates dynamically to apply the user's drag offsets.
   */
  var interactingVideoTranslation by mutableStateOf(Offset.Zero)
    private set

  /**
   * The bounds of the crop frame captured at the start of the interaction (in pixels).
   *
   * This rect remains strictly fixed (frozen) during the gesture. During a pan gesture, the visual
   * crop frame stays stationary on screen while the video moves underneath it; this frozen rect is
   * used as a reference to calculate the new crop rectangle relative to the moving video.
   */
  var frozenCropFrame: Rect = Rect.Zero
    private set
}

/**
 * Derives and provides the scale, translation, and crop frame bounds for both interacting (active
 * dragging/panning) and non-interacting (static preview) states.
 *
 * Custom property getters dynamically read state from [VideoCropperState] and [GestureState] during
 * layout, draw, or gesture handling phases to prevent unnecessary recompositions during active
 * interactions.
 *
 * During the non-interacting state, the crop frame is centered and fits within the widget's
 * padding, while the video is scaled and translated so that the cropped region fills the crop
 * frame. During the interacting state, either the video zoom and position are frozen (when resizing
 * the crop frame), or the crop frame is frozen (when panning the video).
 *
 * This class is a presentation-focused, read-only adapter remembered in composition. It combines
 * the active interaction state from [GestureState] and [VideoCropperState] with container layout
 * constraints to derive the final visual targets.
 *
 * All coordinates and offsets in this class are in pixels.
 *
 * @param cropperState The active [VideoCropperState].
 * @param gestureState The active [GestureState].
 * @param interactionProgressState The animated interaction progress state (0f = idle, 1f =
 *   interacting).
 * @param videoFitRect The baseline unscaled video rectangle (in pixels).
 * @param cropConstraintRect The bounding rectangle constraining the crop frame (in pixels).
 * @property progress The current interaction progress (0f = idle, 1f = interacting).
 * @property cropFrame The current animated crop frame bounds.
 * @property scale The current animated video scale.
 * @property translation The current animated video translation offset.
 */
@Stable
private class CropFrameTransformation(
  private val cropperState: VideoCropperState,
  private val gestureState: GestureState,
  private val interactionProgressState: State<Float>,
  private val videoFitRect: Rect,
  private val cropConstraintRect: Rect,
) {

  val progress: Float
    get() = interactionProgressState.value

  val cropFrame: Rect
    get() =
      lerp(
        start = nonInteractingCropFrame,
        stop = interactingCropFrame,
        fraction = interactionProgressState.value,
      )

  val scale: Float
    get() =
      lerp(
        start = nonInteractingScale,
        stop = gestureState.frozenVideoScale,
        fraction = interactionProgressState.value,
      )

  val translation: Offset
    get() =
      lerp(
        start = nonInteractingTranslation,
        stop = gestureState.interactingVideoTranslation,
        fraction = interactionProgressState.value,
      )

  private val cropAspectRatio: Float
    get() =
      cropperState.targetAspectRatio
        ?: ((cropperState.cropRect.width * cropperState.videoSize.width) /
          (cropperState.cropRect.height * cropperState.videoSize.height))

  private val nonInteractingCropFrame: Rect
    get() =
      calculateMaxRectInContainer(aspectRatio = cropAspectRatio, container = cropConstraintRect)

  private val nonInteractingScale: Float
    get() = nonInteractingCropFrame.width / (videoFitRect.width * cropperState.cropRect.width)

  private val nonInteractingTranslation: Offset
    get() =
      nonInteractingCropFrame.topLeft -
        videoFitRect.topLeft * nonInteractingScale -
        Offset(
          x = cropperState.cropRect.left * videoFitRect.width * nonInteractingScale,
          y = cropperState.cropRect.top * videoFitRect.height * nonInteractingScale,
        )

  private val interactingVideoRect: Rect
    get() =
      videoFitRect
        .scale(gestureState.frozenVideoScale)
        .translate(gestureState.interactingVideoTranslation)

  private val interactingCropFrame: Rect
    get() =
      if (gestureState.isPanningVideo) {
        gestureState.frozenCropFrame
      } else {
        val videoRect = interactingVideoRect
        Rect(
          left = videoRect.left + cropperState.cropRect.left * videoRect.width,
          top = videoRect.top + cropperState.cropRect.top * videoRect.height,
          right = videoRect.left + cropperState.cropRect.right * videoRect.width,
          bottom = videoRect.top + cropperState.cropRect.bottom * videoRect.height,
        )
      }
}

/** Encapsulates visual styling and measurement configurations for rendering the crop frame. */
@Immutable
private data class CropFrameStyle(
  val colors: VideoCropperColors,
  val bracketThickness: Dp,
  val bracketLength: Dp,
  val cropFrameBorderThickness: Dp,
  val cropFrameShape: RoundedCornerShape,
  val minCropSize: Dp,
)
