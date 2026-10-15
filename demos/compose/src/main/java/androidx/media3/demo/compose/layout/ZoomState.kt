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

package androidx.media3.demo.compose.layout

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.layout.times
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import androidx.media3.ui.compose.state.PresentationState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The zoom modes of a [ZoomState], which determine the zoom that a gesture snaps to. */
internal enum class ZoomMode {
  /** The video is scaled by the base [ContentScale] only. Gestures snap back to this zoom. */
  ORIGINAL,
  /** The video is zoomed in just enough to fill the container. Gestures snap to this zoom. */
  FILL,
  /** The video is zoomed in freely. Gestures keep the zoom and offset. */
  FREE,
}

/**
 * State for zooming into and panning the video of a [androidx.media3.ui.compose.material3.Player].
 *
 * The zoom is applied on top of a base [ContentScale] by passing [contentScale] and [alignment] to
 * the Player, which then lays out the video at the zoomed size, positions it according to the
 * [offset] and clips the parts of it that overflow its bounds.
 *
 * Gestures that end close to the [ZoomMode.ORIGINAL] or [ZoomMode.FILL] zoom snap to it, while
 * other gestures keep the zoom and offset ([ZoomMode.FREE]). The chosen mode is kept when the size
 * of the Player changes (see [updateContainerSize]).
 *
 * @param baseContentScale The [ContentScale] that the zoom is applied on top of.
 * @param videoAspectRatio The aspect ratio of the video, or `null` if zooming is not possible.
 * @param scope The [CoroutineScope] in which to run animations.
 */
// TODO: b/473765632 - Delete custom ZoomState and pan boundary clamping once Compose Foundation
// provides a high-level zoomable state/component.
@Stable
internal class ZoomState(
  private val baseContentScale: ContentScale,
  private val videoAspectRatio: Float?,
  private val scope: CoroutineScope,
) {

  /** The size of the Player, in pixels. */
  private var containerSize by mutableStateOf(IntSize.Zero)

  /** The size of the video scaled by the [baseContentScale], or [Size.Zero] if it is unknown. */
  private val baseVideoSize: Size
    get() =
      if (videoAspectRatio == null || containerSize.width <= 0 || containerSize.height <= 0) {
        Size.Zero
      } else {
        // The size of the video fit into the container, which the content scale is applied to
        val container = containerSize.toSize()
        val aspectRatioSize = Size(videoAspectRatio, 1f)
        val fitSize =
          aspectRatioSize * ContentScale.Fit.computeScaleFactor(aspectRatioSize, container)
        fitSize * baseContentScale.computeScaleFactor(fitSize, container)
      }

  /** The zoom at which the video fills the container. */
  private val fillZoom: Float
    get() =
      if (baseVideoSize.isEmpty()) {
        1f
      } else {
        ContentScale.Crop.computeScaleFactor(baseVideoSize, containerSize.toSize()).scaleX
      }

  /** The maximum zoom, which also keeps the size of the zoomed video within layout limits. */
  private val maxZoom: Float
    get() =
      if (baseVideoSize.isEmpty()) {
        1f
      } else {
        (MAX_VIDEO_SIZE_PX / baseVideoSize.maxDimension).coerceIn(1f, MAX_ZOOM)
      }

  /**
   * The [ZoomMode] that the last gesture or action ended in, which is kept when the size of the
   * Player changes, even if it passes through sizes in which the zoom maps to another mode.
   */
  private var chosenMode = ZoomMode.ORIGINAL

  private var animationJob: Job? = null

  /** The zoom applied on top of the base content scale, where 1 means no zoom. */
  var zoom by mutableFloatStateOf(1f)
    private set

  /**
   * The offset of the center of the video from the center of the container, in pixels.
   *
   * The offset is limited such that the video always covers the container in the dimensions in
   * which the video overflows it, and is centered in the other dimensions.
   */
  var offset by mutableStateOf(Offset.Zero)
    private set

  /** Whether an indicator of the current [mode] or [zoom] should be visible. */
  var isIndicatorVisible by mutableStateOf(false)
    private set

  /** The [ZoomMode] of the current [zoom]. */
  val mode: ZoomMode by derivedStateOf { modeFor(zoom) }

  /**
   * Whether zooming is possible, which requires the aspect ratio and container size to be known.
   */
  val isEnabled: Boolean
    get() = !baseVideoSize.isEmpty()

  /** Whether the video can be zoomed in to fill the container. */
  val canZoomToFill: Boolean
    get() = fillZoom >= SNAP_TO_ORIGINAL_THRESHOLD && fillZoom <= maxZoom

  /**
   * Whether the video can be panned with a single pointer, which is the case in [ZoomMode.FREE].
   */
  val canPan: Boolean
    get() = mode == ZoomMode.FREE

  /**
   * The [ContentScale] to pass to the Player, which applies the [zoom] to the base content scale.
   */
  val contentScale: ContentScale =
    object : ContentScale {
      override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor =
        baseContentScale.computeScaleFactor(srcSize, dstSize) * zoom
    }

  /** The [Alignment] to pass to the Player, which centers the video and applies the [offset]. */
  val alignment: Alignment = Alignment { size, space, _ ->
    // Pointer positions are absolute, so the offset does not depend on the layout direction
    IntOffset(
      alignWithOffset(size.width, space.width, offset.x),
      alignWithOffset(size.height, space.height, offset.y),
    )
  }

  /** Starts a zoom or pan gesture, interrupting any ongoing animation. */
  fun onGestureStart() {
    animationJob?.cancel()
    isIndicatorVisible = true
  }

  /**
   * Applies a change of a zoom or pan gesture.
   *
   * @param centroid The centroid of the pointers before the change, in pixels.
   * @param pan The movement of the centroid, in pixels.
   * @param zoomChange The factor by which to change the [zoom].
   */
  fun onGesture(centroid: Offset, pan: Offset, zoomChange: Float) {
    if (!isEnabled) {
      return
    }
    val newZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, maxZoom)
    // Keeps the part of the video under the centroid under it, while moving it along with the pan
    val centroidFromCenter = centroid - containerSize.toSize().center
    offset =
      limitOffset(
        (offset - centroidFromCenter) * (newZoom / zoom) + centroidFromCenter + pan,
        newZoom,
      )
    zoom = newZoom
  }

  /** Ends a zoom or pan gesture, snapping to the zoom of the current [mode] if applicable. */
  fun onGestureEnd() {
    chosenMode = mode
    when (mode) {
      ZoomMode.ORIGINAL -> animateTo(1f, Offset.Zero)
      ZoomMode.FILL -> animateTo(fillZoom, Offset.Zero)
      ZoomMode.FREE -> animateTo(zoom, offset)
    }
  }

  /** Zooms in such that the video fills the container, if [canZoomToFill]. */
  fun zoomToFill() {
    if (canZoomToFill) {
      chosenMode = ZoomMode.FILL
      animateTo(fillZoom, Offset.Zero)
    }
  }

  /** Resets the zoom to [ZoomMode.ORIGINAL]. */
  fun resetZoom() {
    chosenMode = ZoomMode.ORIGINAL
    animateTo(1f, Offset.Zero)
  }

  /**
   * Updates the size of the Player, for example when the device is rotated, keeping the chosen
   * [ZoomMode]. In [ZoomMode.FILL], the video is zoomed in to fill the new size, and in
   * [ZoomMode.FREE], the [zoom] and the part of the video at the center of the Player are kept.
   */
  fun updateContainerSize(size: IntSize) {
    if (size == containerSize) {
      return
    }
    // Interrupts any animation, whose targets apply to the previous size
    animationJob?.cancel()
    isIndicatorVisible = false
    val previousZoomedVideoSize = baseVideoSize * zoom
    val relativeOffset =
      if (previousZoomedVideoSize.isEmpty()) {
        Offset.Zero
      } else {
        Offset(offset.x / previousZoomedVideoSize.width, offset.y / previousZoomedVideoSize.height)
      }
    containerSize = size
    zoom =
      when (chosenMode) {
        ZoomMode.ORIGINAL -> 1f
        ZoomMode.FILL -> if (canZoomToFill) fillZoom else 1f
        ZoomMode.FREE -> zoom.coerceIn(MIN_ZOOM, maxZoom)
      }
    val zoomedVideoSize = baseVideoSize * zoom
    offset =
      limitOffset(
        Offset(relativeOffset.x * zoomedVideoSize.width, relativeOffset.y * zoomedVideoSize.height),
        zoom,
      )
  }

  private fun modeFor(zoom: Float): ZoomMode =
    when {
      zoom < SNAP_TO_ORIGINAL_THRESHOLD -> ZoomMode.ORIGINAL
      canZoomToFill && abs(zoom - fillZoom) <= SNAP_TO_FILL_TOLERANCE -> ZoomMode.FILL
      else -> ZoomMode.FREE
    }

  private fun limitOffset(offset: Offset, zoom: Float): Offset {
    val maxOffsetX = max(0f, (baseVideoSize.width * zoom - containerSize.width) / 2)
    val maxOffsetY = max(0f, (baseVideoSize.height * zoom - containerSize.height) / 2)
    return Offset(
      offset.x.coerceIn(-maxOffsetX, maxOffsetX),
      offset.y.coerceIn(-maxOffsetY, maxOffsetY),
    )
  }

  /** Animates the zoom and offset to the given values, and then hides the indicator. */
  private fun animateTo(targetZoom: Float, targetOffset: Offset) {
    animationJob?.cancel()
    isIndicatorVisible = true
    val startZoom = zoom
    val startOffset = offset
    animationJob = scope.launch {
      if (startZoom != targetZoom || startOffset != targetOffset) {
        animate(0f, 1f, animationSpec = tween(SNAP_ANIMATION_DURATION_MS)) { fraction, _ ->
          zoom = lerp(startZoom, targetZoom, fraction)
          offset = lerp(startOffset, targetOffset, fraction)
        }
      }
      delay(INDICATOR_TIMEOUT)
      isIndicatorVisible = false
    }
  }
}

/**
 * Creates and remembers a [ZoomState] for the video of the given [presentationState].
 *
 * Zooming is only possible while the video is visible, i.e. not covered by the shutter. The zoom is
 * reset when the [baseContentScale] or the aspect ratio or visibility of the video changes, and
 * kept when the [containerSize] changes, for example when the device is rotated or the Player
 * enters fullscreen.
 *
 * @param presentationState The [PresentationState] of the video to zoom into.
 * @param baseContentScale The [ContentScale] that the zoom is applied on top of.
 * @param containerSize The size of the Player, in pixels.
 */
@Composable
internal fun rememberZoomState(
  presentationState: PresentationState,
  baseContentScale: ContentScale,
  containerSize: IntSize,
): ZoomState {
  val videoAspectRatio =
    presentationState.videoAspectRatio.takeUnless { presentationState.coverSurface }
  val scope = rememberCoroutineScope()
  val zoomState =
    remember(baseContentScale, videoAspectRatio) {
      ZoomState(baseContentScale, videoAspectRatio, scope)
    }
  SideEffect { zoomState.updateContainerSize(containerSize) }
  return zoomState
}

/**
 * Returns the position of content of the given [size] within the given [space], which centers the
 * content if it is smaller than the space, and applies the [offset] from the center otherwise. The
 * offset is limited such that the content always covers the space.
 */
private fun alignWithOffset(size: Int, space: Int, offset: Float): Int {
  val centered = (space - size) / 2f
  val maxOffset = max(0f, -centered)
  return (centered + offset.coerceIn(-maxOffset, maxOffset)).roundToInt()
}

/** The minimum zoom, slightly below 1 to give feedback when pinching in at the original size. */
private const val MIN_ZOOM = 0.98f
private const val MAX_ZOOM = 8f

/** The zoom below which a gesture snaps back to [ZoomMode.ORIGINAL]. */
private const val SNAP_TO_ORIGINAL_THRESHOLD = 1.05f

/** The maximum difference from the zoom of [ZoomMode.FILL] at which a gesture snaps to it. */
private const val SNAP_TO_FILL_TOLERANCE = 0.2f

/**
 * The maximum width and height of the zoomed video, in pixels, which keeps them within the limits
 * of [androidx.compose.ui.unit.Constraints].
 */
private const val MAX_VIDEO_SIZE_PX = 32_000f

private const val SNAP_ANIMATION_DURATION_MS = 300
private val INDICATOR_TIMEOUT = 1000.milliseconds
