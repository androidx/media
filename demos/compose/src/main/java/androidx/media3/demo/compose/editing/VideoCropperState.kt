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

import androidx.annotation.FloatRange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.compose.state.PlayerStateObserver
import androidx.media3.ui.compose.state.observeState
import kotlin.math.abs

/**
 * A small epsilon value used to compare aspect ratios to account for floating point imprecision.
 */
private const val BOUNDARY_EPSILON = 1e-3f

/**
 * Remembers the value of [VideoCropperState] created based on the input parameters and launch a
 * coroutine to listen to [Player's][Player] changes. If the [Player] instance changes between
 * compositions, produces and remembers a new value.
 *
 * @param player The [Player] whose video content to crop.
 * @param initialCropRect The initial crop rectangle normalized in [0..1] relative to the video
 *   dimensions. If [targetAspectRatio] is set and does not match the aspect ratio of
 *   [initialCropRect], the crop rectangle will automatically be re-centered and resized to the
 *   largest possible rectangle matching [targetAspectRatio] once video dimensions are available.
 * @param targetAspectRatio The desired target aspect ratio for the crop frame (e.g. 1f for square,
 *   1.77f for 16:9). If null, the aspect ratio corresponding to [initialCropRect] is preserved.
 */
@Composable
fun rememberVideoCropperState(
  player: Player?,
  initialCropRect: Rect = Rect(0f, 0f, 1f, 1f),
  @FloatRange(from = 0.0, fromInclusive = false) targetAspectRatio: Float? = null,
): VideoCropperState {
  val cropperState =
    remember(player) {
      VideoCropperState(
        player = player,
        initialCropRect = initialCropRect,
        targetAspectRatio = targetAspectRatio,
      )
    }
  SideEffect { cropperState.targetAspectRatio = targetAspectRatio }
  LaunchedEffect(player) { cropperState.observe() }
  return cropperState
}

/**
 * A state holder object that manages the video cropper state.
 *
 * Use [rememberVideoCropperState] to create an instance that survives recompositions.
 *
 * @property cropRect The current crop rectangle normalized in [0..1] relative to the video
 *   dimensions.
 * @property targetAspectRatio The desired target aspect ratio for the crop frame. If it does not
 *   match the aspect ratio of [cropRect], [cropRect] will automatically be re-centered and resized
 *   to the largest possible rectangle matching [targetAspectRatio] once video dimensions are
 *   available.
 * @property isInteracting Whether the user is currently interacting with the cropper (dragging or
 *   panning).
 */
@Stable
class VideoCropperState(
  internal val player: Player?,
  initialCropRect: Rect = Rect(0f, 0f, 1f, 1f),
  @FloatRange(from = 0.0, fromInclusive = false) targetAspectRatio: Float? = null,
) {

  var cropRect by mutableStateOf(initialCropRect)

  @setparam:FloatRange(from = 0.0, fromInclusive = false)
  var targetAspectRatio: Float?
    get() = _targetAspectRatio.value
    set(newTargetAspectRatio) {
      require(
        newTargetAspectRatio == null ||
          (newTargetAspectRatio > 0f && newTargetAspectRatio.isFinite())
      ) {
        "targetAspectRatio must be positive and finite, but was $newTargetAspectRatio"
      }
      _targetAspectRatio.value = newTargetAspectRatio
      updateCropRect()
    }

  var isInteracting by mutableStateOf(false)
    internal set

  internal var videoSize: Size by mutableStateOf(player?.videoSize?.toDisplaySize() ?: Size.Zero)
    private set

  private val _targetAspectRatio = mutableStateOf(targetAspectRatio)

  private val playerStateObserver: PlayerStateObserver? =
    player?.observeState(Player.EVENT_VIDEO_SIZE_CHANGED) {
      val size = player.videoSize.toDisplaySize()
      if (!size.isEmpty()) {
        videoSize = size
        updateCropRect()
      }
    }

  init {
    require(
      initialCropRect.left in 0f..1f &&
        initialCropRect.top in 0f..1f &&
        initialCropRect.right in 0f..1f &&
        initialCropRect.bottom in 0f..1f &&
        initialCropRect.left < initialCropRect.right &&
        initialCropRect.top < initialCropRect.bottom
    ) {
      "initialCropRect coordinates must be within [0, 1] with positive width and height, but was $initialCropRect"
    }
    require(targetAspectRatio == null || (targetAspectRatio > 0f && targetAspectRatio.isFinite())) {
      "targetAspectRatio must be positive and finite, but was $targetAspectRatio"
    }
    updateCropRect()
  }

  suspend fun observe() {
    playerStateObserver?.observe()
  }

  private fun updateCropRect() {
    if (videoSize.isEmpty()) return
    val currentTargetRatio = targetAspectRatio ?: return
    val videoAspectRatio = videoSize.width / videoSize.height
    val currentRatio = (cropRect.width / cropRect.height) * videoAspectRatio
    if (abs(currentRatio - currentTargetRatio) > BOUNDARY_EPSILON) {
      cropRect = calculateCropRectForAspectRatio(videoAspectRatio, currentTargetRatio)
    }
  }

  /**
   * Calculates the largest centered crop rectangle in normalized [0..1] coordinates for a given
   * target aspect ratio.
   */
  private fun calculateCropRectForAspectRatio(
    videoAspectRatio: Float,
    targetAspectRatio: Float,
  ): Rect {
    if (targetAspectRatio <= 0f || videoAspectRatio <= 0f) {
      return Rect(0f, 0f, 1f, 1f)
    }
    return if (targetAspectRatio <= videoAspectRatio) {
      val normWidth = (targetAspectRatio / videoAspectRatio).coerceAtMost(1f)
      val left = (1f - normWidth) / 2f
      Rect(left = left, top = 0f, right = left + normWidth, bottom = 1f)
    } else {
      val normHeight = (videoAspectRatio / targetAspectRatio).coerceAtMost(1f)
      val top = (1f - normHeight) / 2f
      Rect(left = 0f, top = top, right = 1f, bottom = top + normHeight)
    }
  }

  /**
   * Converts this [VideoSize] to a Compose [Size] representing the display dimensions, scaling the
   * width based on [VideoSize.pixelWidthHeightRatio].
   */
  private fun VideoSize.toDisplaySize(): Size {
    if (width <= 0 || height <= 0) return Size.Zero
    val safeRatio =
      if (pixelWidthHeightRatio <= 0f || pixelWidthHeightRatio.isNaN()) 1f
      else pixelWidthHeightRatio
    val displayWidth = width * safeRatio
    return Size(displayWidth, height.toFloat())
  }
}
