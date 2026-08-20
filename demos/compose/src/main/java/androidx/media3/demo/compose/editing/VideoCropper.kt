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
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import androidx.media3.common.Player
import androidx.media3.ui.compose.ContentFrame
import kotlin.math.roundToInt

/** The radius of the touch target for the corners of the crop overlay. */
private val CORNER_TOUCH_TARGET_RADIUS = 24.dp

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

  val gestureState = remember { GestureState() }
  var cropperSize by remember { mutableStateOf(Size.Zero) }

  val density = LocalDensity.current
  val layoutDirection = LocalLayoutDirection.current
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

  val style =
    CropFrameStyle(
      colors = colors,
      bracketThickness = bracketThickness,
      bracketLength = bracketLength,
      cropFrameBorderThickness = cropFrameBorderThickness,
      cropFrameShape = cropFrameShape,
      minCropSize = minCropSize,
    )

  Box(modifier = modifier.clipToBounds().onSizeChanged { cropperSize = it.toSize() }) {
    // Render the player container unconditionally so the player can attach to the surface and
    // expose the video size.
    VideoPlayerContainer(player = state.player, transformation = transformation)
    if (transformation != null) {
      CropFrameContainer(
        cropperState = state,
        gestureState = gestureState,
        transformation = transformation,
        videoFitRect = videoFitRect,
        style = style,
        onCropRectChangeFinished = onCropRectChangeFinished,
        cropFrameControls = cropFrameControls,
      )
    }
  }
}

/**
 * A container that renders the [ContentFrame] video surface and applies the animated scale and
 * translation offsets.
 */
@Composable
private fun VideoPlayerContainer(
  player: Player?,
  transformation: CropFrameTransformation?,
  modifier: Modifier = Modifier,
) {
  ContentFrame(
    player = player,
    modifier =
      modifier.fillMaxSize().graphicsLayer {
        val scale = transformation?.scale ?: 1f
        scaleX = scale
        scaleY = scale
        val translation = transformation?.translation ?: Offset.Zero
        translationX = translation.x
        translationY = translation.y
        transformOrigin = TransformOrigin(0f, 0f)
      },
  )
}

/**
 * A container that wraps the crop frame drawing, controls overlay, and gesture detection.
 *
 * This composable intercepts drag gestures on the screen. It distinguishes between dragging the
 * corners of the crop frame (to resize it) and dragging the video itself (to pan the video content
 * under the crop frame). It updates the crop rectangle coordinates and triggers the corresponding
 * callbacks.
 *
 * @param cropperState The active [VideoCropperState].
 * @param gestureState The [GestureState] tracking active gesture interaction.
 * @param transformation The calculated [CropFrameTransformation] containing the animated scales and
 *   translations.
 * @param videoFitRect The baseline unscaled video rectangle (in pixels).
 * @param style The [CropFrameStyle] configuration.
 * @param modifier The modifier to be applied to the crop frame container.
 * @param onCropRectChangeFinished A callback invoked when the user finishes dragging/panning.
 * @param cropFrameControls An optional overlay content composable.
 */
@Composable
private fun CropFrameContainer(
  cropperState: VideoCropperState,
  gestureState: GestureState,
  transformation: CropFrameTransformation,
  videoFitRect: Rect,
  style: CropFrameStyle,
  modifier: Modifier = Modifier,
  onCropRectChangeFinished: (() -> Unit)? = null,
  cropFrameControls: (@Composable BoxScope.() -> Unit)? = null,
) {
  val density = LocalDensity.current
  val currentTransformation by rememberUpdatedState(transformation)
  val currentVideoFitRect by rememberUpdatedState(videoFitRect)
  val currentMinCropSizePx by rememberUpdatedState(with(density) { style.minCropSize.toPx() })
  val currentOnCropRectChangeFinished by rememberUpdatedState(onCropRectChangeFinished)
  val currentTouchTargetSizePx by
    rememberUpdatedState(with(density) { CORNER_TOUCH_TARGET_RADIUS.toPx() })
  Box(
    modifier =
      modifier.fillMaxSize().pointerInput(Unit) {
        detectDragGestures(
          onDragStart = { touchPoint ->
            gestureState.startDrag(
              cropRect = cropperState.cropRect,
              transformation = currentTransformation,
              touchPoint = touchPoint,
              touchTargetSize = currentTouchTargetSizePx,
            )
            cropperState.isInteracting = true
          },
          onDrag = { change, dragAmount ->
            change.consume()
            cropperState.cropRect =
              gestureState.dragBy(
                dragAmount = dragAmount,
                transformation = currentTransformation,
                videoFitRect = currentVideoFitRect,
                minCropSize = currentMinCropSizePx,
              )
          },
          onDragEnd = {
            gestureState.finishDrag()
            cropperState.isInteracting = false
            currentOnCropRectChangeFinished?.invoke()
          },
          onDragCancel = {
            gestureState.finishDrag()
            cropperState.isInteracting = false
            currentOnCropRectChangeFinished?.invoke()
          },
        )
      }
  ) {
    CropperOverlay(transformation = transformation, style = style)
    cropFrameControls?.let { controls ->
      CropFrameControlsOverlay(transformation = transformation, content = controls)
    }
  }
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
 * A custom layout wrapper that measures and places the crop controls overlay exactly on top of the
 * active crop frame bounds.
 */
@Composable
private fun CropFrameControlsOverlay(
  transformation: CropFrameTransformation,
  modifier: Modifier = Modifier,
  content: @Composable BoxScope.() -> Unit,
) {
  Box(
    modifier =
      modifier.layout { measurable, _ ->
        val cropFrame = transformation.cropFrame
        val width = cropFrame.width.roundToInt()
        val height = cropFrame.height.roundToInt()
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(width, height) {
          placeable.placeWithLayer(x = cropFrame.left.roundToInt(), y = cropFrame.top.roundToInt())
        }
      }
  ) {
    content()
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

  /** The corner currently being dragged, or `null` if not dragging a corner. */
  var activeCorner by mutableStateOf<Corner?>(null)
    private set

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

  /**
   * The crop rectangle in normalized video coordinates captured at the start of the interaction.
   *
   * This rect remains strictly fixed (frozen) during the gesture. During a pan gesture, its width
   * and height are used as a reference to keep the crop rectangle's aspect ratio and dimensions
   * constant, preventing drift from accumulating floating-point errors.
   */
  var frozenCropRect: Rect = Rect.Zero
    private set

  /**
   * Initializes the interaction state at the start of a drag gesture.
   *
   * Detects if the touch point is near any corner of the crop frame to initiate a corner resize. If
   * not, it initiates a video panning interaction.
   *
   * All coordinates and sizes are expressed in pixels.
   *
   * @param cropRect The current crop rectangle in normalized video coordinates.
   * @param transformation The active [CropFrameTransformation].
   * @param touchPoint The touch point coordinate.
   * @param touchTargetSize The radius around corners within which a touch is registered as a corner
   *   drag.
   */
  fun startDrag(
    cropRect: Rect,
    transformation: CropFrameTransformation,
    touchPoint: Offset,
    touchTargetSize: Float,
  ) {
    activeCorner =
      detectCorner(
        cropFrame = transformation.cropFrame,
        touchPoint = touchPoint,
        touchTargetSize = touchTargetSize,
      )
    isPanningVideo = activeCorner == null
    frozenVideoScale = transformation.nonInteractingScale
    interactingVideoTranslation = transformation.nonInteractingTranslation
    frozenCropFrame = transformation.cropFrame
    frozenCropRect = cropRect
  }

  /**
   * Updates the crop rectangle or video translation based on the drag progress.
   *
   * If a corner is active, it resizes the crop frame. If panning, it translates the video under the
   * stationary crop frame.
   *
   * All coordinates, offsets, and sizes are expressed in pixels.
   *
   * @param dragAmount The offset of the drag step since the last update.
   * @param transformation The active [CropFrameTransformation].
   * @param videoFitRect The bounds of the video when fit inside the [VideoCropper] container.
   * @param minCropSize The minimum allowed size of the crop frame on screen.
   * @return The updated normalized crop rectangle.
   */
  fun dragBy(
    dragAmount: Offset,
    transformation: CropFrameTransformation,
    videoFitRect: Rect,
    minCropSize: Float,
  ): Rect {
    val corner = activeCorner
    if (corner != null) {
      // Resize the crop frame.
      val newCropFrame =
        resizeCropFrame(
          cropFrame = transformation.interactingCropFrame,
          corner = corner,
          dragAmount = dragAmount,
          aspectRatio = transformation.cropAspectRatio,
          bounds = transformation.interactingVideoRect,
          minCropSize = minCropSize,
        )

      // Convert the on-screen crop frame pixel bounds into normalized [0..1] video coordinates
      // relative to the video's active on-screen bounds.
      val newCropRect =
        Rect(
          left =
            (newCropFrame.left - transformation.interactingVideoRect.left) /
              transformation.interactingVideoRect.width,
          top =
            (newCropFrame.top - transformation.interactingVideoRect.top) /
              transformation.interactingVideoRect.height,
          right =
            (newCropFrame.right - transformation.interactingVideoRect.left) /
              transformation.interactingVideoRect.width,
          bottom =
            (newCropFrame.bottom - transformation.interactingVideoRect.top) /
              transformation.interactingVideoRect.height,
        )
      return newCropRect
    }

    // Otherwise, pan the video under the stationary crop frame.
    val videoWidth = videoFitRect.width * frozenVideoScale
    val videoHeight = videoFitRect.height * frozenVideoScale
    val tentativeLeft =
      videoFitRect.left * frozenVideoScale + interactingVideoTranslation.x + dragAmount.x
    val tentativeTop =
      videoFitRect.top * frozenVideoScale + interactingVideoTranslation.y + dragAmount.y

    // Clamp the video position so it fully covers the stationary on-screen crop frame. Use minOf
    // to guard against float rounding discrepancies where minimum > maximum.
    val minVideoLeft = frozenCropFrame.right - videoWidth
    val maxVideoLeft = frozenCropFrame.left
    val clampedVideoLeft = tentativeLeft.coerceIn(minOf(minVideoLeft, maxVideoLeft), maxVideoLeft)
    val minVideoTop = frozenCropFrame.bottom - videoHeight
    val maxVideoTop = frozenCropFrame.top
    val clampedVideoTop = tentativeTop.coerceIn(minOf(minVideoTop, maxVideoTop), maxVideoTop)
    interactingVideoTranslation =
      Offset(clampedVideoLeft, clampedVideoTop) - videoFitRect.topLeft * frozenVideoScale

    // Convert the stationary on-screen crop frame position into normalized [0..1] video
    // coordinates, clamped to avoid floating-point rounding violations.
    val normalizedLeft =
      ((frozenCropFrame.left - clampedVideoLeft) / videoWidth).coerceIn(
        0f,
        1f - frozenCropRect.width,
      )
    val normalizedTop =
      ((frozenCropFrame.top - clampedVideoTop) / videoHeight).coerceIn(
        0f,
        1f - frozenCropRect.height,
      )
    val newCropRect =
      Rect(
        left = normalizedLeft,
        top = normalizedTop,
        right = normalizedLeft + frozenCropRect.width,
        bottom = normalizedTop + frozenCropRect.height,
      )
    return newCropRect
  }

  /** Resets the interaction state when the drag gesture finishes or is canceled. */
  fun finishDrag() {
    activeCorner = null
    isPanningVideo = false
  }

  /**
   * Determines which corner of the crop frame, if any, is close to the given touch point.
   *
   * @param cropFrame The current bounds of the on-screen crop frame.
   * @param touchPoint The touch point coordinate.
   * @param touchTargetSize The radius around corners within which a touch is registered as a corner
   *   drag.
   * @return The detected [Corner], or `null` if the touch point is not near any corner.
   */
  private fun detectCorner(cropFrame: Rect, touchPoint: Offset, touchTargetSize: Float): Corner? {
    val touchTargetSizeSquared = touchTargetSize * touchTargetSize
    return when {
      (touchPoint - cropFrame.topLeft).getDistanceSquared() < touchTargetSizeSquared ->
        Corner.TOP_LEFT
      (touchPoint - cropFrame.topRight).getDistanceSquared() < touchTargetSizeSquared ->
        Corner.TOP_RIGHT
      (touchPoint - cropFrame.bottomLeft).getDistanceSquared() < touchTargetSizeSquared ->
        Corner.BOTTOM_LEFT
      (touchPoint - cropFrame.bottomRight).getDistanceSquared() < touchTargetSizeSquared ->
        Corner.BOTTOM_RIGHT
      else -> null
    }
  }

  /**
   * Resizes the [cropFrame] crop frame rectangle by dragging a specific [corner] by [dragAmount],
   * while strictly preserving [aspectRatio] and remaining within [bounds] and above [minCropSize].
   *
   * To maintain the exact aspect ratio during unconstrained diagonal dragging, the touch coordinate
   * is projected perpendicularly onto a linear constraint line passing through the stationary
   * anchor corner (diagonally opposite to [corner]) with slope determined by [aspectRatio].
   *
   * All coordinates, offsets, and sizes are expressed in pixels.
   *
   * @param cropFrame The current crop frame rectangle before applying [dragAmount].
   * @param corner The active [Corner] being dragged by the user.
   * @param dragAmount The touch displacement vector for this drag event.
   * @param aspectRatio The required width-to-height aspect ratio of the crop frame.
   * @param bounds The maximum allowable screen bounding box constraining the crop frame.
   * @param minCropSize The minimum allowable width and height for the crop frame.
   * @return The resized and clamped crop frame rectangle.
   */
  private fun resizeCropFrame(
    cropFrame: Rect,
    corner: Corner,
    dragAmount: Offset,
    aspectRatio: Float,
    bounds: Rect,
    minCropSize: Float,
  ): Rect {
    val isLeft = corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT
    val isTop = corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT

    // The anchor is the stationary corner diagonally opposite to the dragged corner.
    val anchorX = if (isLeft) cropFrame.right else cropFrame.left
    val anchorY = if (isTop) cropFrame.bottom else cropFrame.top

    // Signs indicate whether dragging along the positive axes (+x / +y) expands (+1f) or
    // contracts (-1f) the frame's width and height relative to the stationary anchor.
    val signX = if (isLeft) -1f else 1f
    val signY = if (isTop) -1f else 1f

    // Tentative dimensions from applying the drag delta.
    val tentativeWidth = cropFrame.width + dragAmount.x * signX
    val tentativeHeight = cropFrame.height + dragAmount.y * signY

    // Project (tentativeWidth, tentativeHeight) onto the aspect ratio line.
    val projectedHeight =
      (tentativeWidth * aspectRatio + tentativeHeight) / (aspectRatio * aspectRatio + 1f)

    // Determine the allowable height range constrained by bounds and minCropSize.
    val maxBoundaryWidth = if (signX > 0) bounds.right - anchorX else anchorX - bounds.left
    val maxBoundaryHeight = if (signY > 0) bounds.bottom - anchorY else anchorY - bounds.top
    val maxHeight = minOf(maxBoundaryWidth / aspectRatio, maxBoundaryHeight)
    val minHeight = maxOf(minCropSize, minCropSize / aspectRatio).coerceAtMost(maxHeight)

    // Clamp height and derive width.
    val newHeight = projectedHeight.coerceIn(minHeight, maxHeight)
    val newWidth = newHeight * aspectRatio

    val left = if (signX > 0) anchorX else anchorX - newWidth
    val top = if (signY > 0) anchorY else anchorY - newHeight
    return Rect(left = left, top = top, right = left + newWidth, bottom = top + newHeight)
  }
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
 * @property cropAspectRatio The active aspect ratio of the crop frame.
 * @property nonInteractingScale The target video scale when the user is not interacting.
 * @property nonInteractingTranslation The target video translation when the user is not
 *   interacting.
 * @property interactingVideoRect The video bounds during active interaction.
 * @property interactingCropFrame The crop frame bounds during active interaction.
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

  val cropAspectRatio: Float
    get() =
      cropperState.targetAspectRatio
        ?: ((cropperState.cropRect.width * cropperState.videoSize.width) /
          (cropperState.cropRect.height * cropperState.videoSize.height))

  val nonInteractingScale: Float
    get() = nonInteractingCropFrame.width / (videoFitRect.width * cropperState.cropRect.width)

  val nonInteractingTranslation: Offset
    get() =
      nonInteractingCropFrame.topLeft -
        videoFitRect.topLeft * nonInteractingScale -
        Offset(
          x = cropperState.cropRect.left * videoFitRect.width * nonInteractingScale,
          y = cropperState.cropRect.top * videoFitRect.height * nonInteractingScale,
        )

  val interactingVideoRect: Rect
    get() =
      videoFitRect
        .scale(gestureState.frozenVideoScale)
        .translate(gestureState.interactingVideoTranslation)

  val interactingCropFrame: Rect
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

  private val nonInteractingCropFrame: Rect
    get() =
      calculateMaxRectInContainer(aspectRatio = cropAspectRatio, container = cropConstraintRect)
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

private enum class Corner {
  TOP_LEFT,
  TOP_RIGHT,
  BOTTOM_LEFT,
  BOTTOM_RIGHT,
}
