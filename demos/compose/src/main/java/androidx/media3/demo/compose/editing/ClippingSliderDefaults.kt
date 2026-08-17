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

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Represents the colors used in a [ClippingSlider] to customize its appearance.
 *
 * @property clippingFrameColor The color of the selection frame and of the clipping handles.
 * @property clippingThumbIconColor The color used to tint the icons within the clipping thumbs.
 * @property positionThumbColor The color of the thumb representing the current playback position.
 * @property inactiveTrackColor The color of the filter applied to the inactive areas outside the
 *   selected clipping range on the slider track. The underlying content remains visible if the
 *   color's alpha is less than 1f.
 */
@Immutable
class ClippingSliderColors(
  val clippingFrameColor: Color,
  val clippingThumbIconColor: Color,
  val positionThumbColor: Color,
  val inactiveTrackColor: Color,
) {

  /** Returns a copy of this ClippingSliderColors, optionally overriding some of the values. */
  fun copy(
    clippingFrameColor: Color = this.clippingFrameColor,
    clippingThumbIconColor: Color = this.clippingThumbIconColor,
    positionThumbColor: Color = this.positionThumbColor,
    inactiveTrackColor: Color = this.inactiveTrackColor,
  ): ClippingSliderColors =
    ClippingSliderColors(
      clippingFrameColor,
      clippingThumbIconColor,
      positionThumbColor,
      inactiveTrackColor,
    )

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ClippingSliderColors) return false

    if (clippingFrameColor != other.clippingFrameColor) return false
    if (clippingThumbIconColor != other.clippingThumbIconColor) return false
    if (positionThumbColor != other.positionThumbColor) return false
    if (inactiveTrackColor != other.inactiveTrackColor) return false

    return true
  }

  override fun hashCode(): Int {
    var result = clippingFrameColor.hashCode()
    result = 31 * result + clippingThumbIconColor.hashCode()
    result = 31 * result + positionThumbColor.hashCode()
    result = 31 * result + inactiveTrackColor.hashCode()
    return result
  }
}

/** Contains the default values used by [ClippingSlider]. */
object ClippingSliderDefaults {

  /**
   * Creates a [ClippingSliderColors] with the default colors used in a [ClippingSlider].
   *
   * @param clippingFrameColor The color of the selection frame and of the clipping handles.
   * @param clippingThumbIconColor The color used to tint the icons within the clipping thumbs.
   * @param positionThumbColor The color of the thumb representing the current playback position.
   * @param inactiveTrackColor The color of the filter applied to the inactive areas outside the
   *   selected clipping range on the slider track.
   */
  @Composable
  fun colors(
    clippingFrameColor: Color = MaterialTheme.colorScheme.primary,
    clippingThumbIconColor: Color = MaterialTheme.colorScheme.onPrimary,
    positionThumbColor: Color = MaterialTheme.colorScheme.inversePrimary,
    inactiveTrackColor: Color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
  ): ClippingSliderColors =
    ClippingSliderColors(
      clippingFrameColor = clippingFrameColor,
      clippingThumbIconColor = clippingThumbIconColor,
      positionThumbColor = positionThumbColor,
      inactiveTrackColor = inactiveTrackColor,
    )
}

