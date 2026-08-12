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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
    if (isReady) {
      cropFrameTransformation(
        gestureState = gestureState,
        cropRect = state.cropRect,
        targetAspectRatio = state.targetAspectRatio,
        isInteracting = state.isInteracting,
        videoSize = state.videoSize,
        videoFitRect = videoFitRect,
        cropConstraintRect = cropConstraintRect,
      )
    } else {
      null
    }

  val currentTransformation = rememberUpdatedState(transformation)
  val scaleProvider = remember { { currentTransformation.value?.scale ?: 1f } }
  val translationProvider = remember { { currentTransformation.value?.translation ?: Offset.Zero } }

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

@Composable
private fun cropFrameTransformation(
  gestureState: GestureState,
  cropRect: Rect,
  targetAspectRatio: Float?,
  isInteracting: Boolean,
  videoSize: Size,
  videoFitRect: Rect,
  cropConstraintRect: Rect,
): CropFrameTransformation {
  val cropAspectRatio =
    targetAspectRatio ?: ((cropRect.width * videoSize.width) / (cropRect.height * videoSize.height))
  val nonInteractingCropFrame =
    calculateMaxRectInContainer(aspectRatio = cropAspectRatio, container = cropConstraintRect)
  val nonInteractingScale = nonInteractingCropFrame.width / (videoFitRect.width * cropRect.width)
  val nonInteractingTranslation =
    nonInteractingCropFrame.topLeft -
      videoFitRect.topLeft * nonInteractingScale -
      Offset(
        x = cropRect.left * videoFitRect.width * nonInteractingScale,
        y = cropRect.top * videoFitRect.height * nonInteractingScale,
      )

  // Animate transition between states (only on release).
  val interactingProgressState = animateFloatAsState(targetValue = if (isInteracting) 1f else 0f)

  return CropFrameTransformation(
    gestureState = gestureState,
    progressState = interactingProgressState,
    nonInteractingScale = nonInteractingScale,
    nonInteractingTranslation = nonInteractingTranslation,
  )
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
}

/**
 * Holds the calculated scale, translation, and crop frame bounds for both interacting (active
 * dragging/panning) and non-interacting (static preview) states.
 *
 * During the non-interacting state, the crop frame is centered and fits within the widget's
 * padding, while the video is scaled and translated so that the cropped region fills the crop
 * frame. During the interacting state, either the video zoom and position are frozen (when resizing
 * the crop frame), or the crop frame is frozen (when panning the video).
 *
 * This class is a presentation-focused, read-only container calculated during composition. It
 * combines the active interaction state from [GestureState] with static layout constraints to
 * derive the final visual targets.
 *
 * All coordinates and offsets in this class are in pixels.
 *
 * @param gestureState The active [GestureState].
 * @param progressState The animated interaction progress state (0f = idle, 1f = interacting).
 * @property scale The current animated video scale.
 * @property translation The current animated video translation offset.
 * @property nonInteractingScale The target video scale when the user is not interacting.
 * @property nonInteractingTranslation The target video translation when the user is not
 *   interacting.
 */
@Stable
private class CropFrameTransformation(
  private val gestureState: GestureState,
  private val progressState: State<Float>,
  val nonInteractingScale: Float,
  val nonInteractingTranslation: Offset,
) {
  val scale: Float
    get() =
      lerp(
        start = nonInteractingScale,
        stop = gestureState.frozenVideoScale,
        fraction = progressState.value,
      )

  val translation: Offset
    get() =
      lerp(
        start = nonInteractingTranslation,
        stop = gestureState.interactingVideoTranslation,
        fraction = progressState.value,
      )
}
