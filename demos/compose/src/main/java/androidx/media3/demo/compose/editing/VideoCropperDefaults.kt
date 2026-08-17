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

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Represents the colors used in a [VideoCropper] to customize its appearance.
 *
 * @property idleOverlayColor The color of the mask applied to the area outside the crop frame when
 *   the user is not interacting.
 * @property interactingOverlayColor The color of the mask applied to the area outside the crop
 *   frame when the user is interacting.
 * @property bracketColor The color of the corner brackets.
 * @property borderColor The color of the crop frame border.
 */
@Immutable
class VideoCropperColors(
  val idleOverlayColor: Color,
  val interactingOverlayColor: Color,
  val bracketColor: Color,
  val borderColor: Color,
) {

  /** Returns a copy of this VideoCropperColors, optionally overriding some of the values. */
  fun copy(
    idleOverlayColor: Color = this.idleOverlayColor,
    interactingOverlayColor: Color = this.interactingOverlayColor,
    bracketColor: Color = this.bracketColor,
    borderColor: Color = this.borderColor,
  ): VideoCropperColors =
    VideoCropperColors(
      idleOverlayColor = idleOverlayColor,
      interactingOverlayColor = interactingOverlayColor,
      bracketColor = bracketColor,
      borderColor = borderColor,
    )

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is VideoCropperColors) return false
    return idleOverlayColor == other.idleOverlayColor &&
      interactingOverlayColor == other.interactingOverlayColor &&
      bracketColor == other.bracketColor &&
      borderColor == other.borderColor
  }

  override fun hashCode(): Int {
    var result = idleOverlayColor.hashCode()
    result = 31 * result + interactingOverlayColor.hashCode()
    result = 31 * result + bracketColor.hashCode()
    result = 31 * result + borderColor.hashCode()
    return result
  }
}

/** Contains default values used by [VideoCropper]. */
object VideoCropperDefaults {

  /** Creates a [VideoCropperColors] with the default colors used in a [VideoCropper]. */
  @Composable
  fun colors(
    idleOverlayColor: Color = Color.Black,
    interactingOverlayColor: Color = Color.Black.copy(alpha = 0.5f),
    bracketColor: Color = Color.White,
    borderColor: Color = Color.Black,
  ): VideoCropperColors =
    VideoCropperColors(
      idleOverlayColor = idleOverlayColor,
      interactingOverlayColor = interactingOverlayColor,
      bracketColor = bracketColor,
      borderColor = borderColor,
    )

  val CropFramePadding: PaddingValues = PaddingValues(36.dp)
  val BracketThickness: Dp = 4.dp
  val BracketLength: Dp = 24.dp
  val CropFrameBorderThickness: Dp = 4.dp
  val CropFrameShape: RoundedCornerShape = RoundedCornerShape(8.dp)
  val MinCropSize: Dp = 40.dp
}
