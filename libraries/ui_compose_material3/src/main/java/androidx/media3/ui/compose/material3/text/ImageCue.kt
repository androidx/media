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

package androidx.media3.ui.compose.material3.text

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueUtil
import androidx.media3.ui.compose.material3.R
import kotlin.math.roundToInt

/**
 * Renders a non-text subtitle cue (i.e., [Cue.bitmap]) using an [Image] composable.
 *
 * This component sizes and places the bitmap within the given [viewport] using a custom layout.
 *
 * @param cue The non-text [Cue] to render. If [Cue.bitmap] is null, this composable renders
 *   nothing.
 * @param viewport The [IntRect] bounds representing the video frame surface. The cue's size and
 *   offsets are calculated relative to this rectangle.
 * @param modifier The [Modifier] to be applied to the component.
 */
@Composable
internal fun ImageCue(cue: Cue, viewport: IntRect, modifier: Modifier = Modifier) {
  val bitmap = cue.bitmap ?: return

  val size =
    getScaledSize(
      relativeCueSize = cue.size,
      relativeCueHeight = cue.bitmapHeight,
      absoluteBitmapWidth = bitmap.width,
      absoluteBitmapHeight = bitmap.height,
      viewWidth = viewport.width,
      viewHeight = viewport.height,
    )

  val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

  Layout(
    modifier = Modifier.offset { IntOffset(viewport.left, viewport.top) },
    content = {
      Image(
        bitmap = imageBitmap,
        contentDescription = stringResource(R.string.subtitle_bitmap_cue),
        modifier = modifier,
        // Override the default Fit to respect scaled cues size calculations.
        contentScale = ContentScale.FillBounds,
      )
    },
  ) { measurables, _ ->
    val placeable = measurables[0].measure(Constraints.fixed(size.width, size.height))

    layout(viewport.width, viewport.height) {
      placeable.place(
        x =
          CueUtil.resolveAnchorOffset(cue.position, cue.positionAnchor, size.width, viewport.width)
            .roundToInt(),
        // Bitmap cues are expected to use fractional line positioning. If lineType is not
        // LINE_TYPE_FRACTION, we treat the line as unset to avoid misinterpreting a line number
        // as a fractional offset, and fall back to centering.
        y =
          CueUtil.resolveAnchorOffset(
              if (cue.lineType == Cue.LINE_TYPE_FRACTION) cue.line else Cue.DIMEN_UNSET,
              cue.lineAnchor,
              size.height,
              viewport.height,
            )
            .roundToInt(),
      )
    }
  }
}

/**
 * Calculates the pixel dimensions of a bitmap cue.
 *
 * If a relative cue size is specified, it scales the width proportionally to the view width. The
 * height is scaled independently if a relative cue height is provided; otherwise, it maintains the
 * aspect ratio of the original bitmap. If no relative sizes are provided, it falls back to the
 * absolute dimensions of the original bitmap.
 *
 * @param relativeCueSize The fractional width of the cue relative to the view width, expressed as a
 *   value between 0 and 1, or [Cue.DIMEN_UNSET].
 * @param relativeCueHeight The fractional height of the cue relative to the view height, expressed
 *   as a value between 0 and 1, or [Cue.DIMEN_UNSET].
 * @param absoluteBitmapWidth The inherent pixel width of the bitmap.
 * @param absoluteBitmapHeight The inherent pixel height of the bitmap.
 * @param viewWidth The pixel width of the viewport.
 * @param viewHeight The pixel height of the viewport.
 * @return The calculated [IntSize] in pixels for rendering the bitmap.
 */
private fun getScaledSize(
  relativeCueSize: Float,
  relativeCueHeight: Float,
  absoluteBitmapWidth: Int,
  absoluteBitmapHeight: Int,
  viewWidth: Int,
  viewHeight: Int,
): IntSize {
  val width =
    if (relativeCueSize != Cue.DIMEN_UNSET) {
      (viewWidth * relativeCueSize).roundToInt()
    } else {
      absoluteBitmapWidth
    }

  val height =
    if (relativeCueHeight != Cue.DIMEN_UNSET) {
      // Prioritize relativeCueHeight over relativeCueSize
      (viewHeight * relativeCueHeight).roundToInt()
    } else if (relativeCueSize != Cue.DIMEN_UNSET) {
      // Maintain aspect ratio with the new [width = viewWidth * relativeCueSize]
      (width * (absoluteBitmapHeight.toFloat() / absoluteBitmapWidth)).roundToInt()
    } else {
      absoluteBitmapHeight
    }

  return IntSize(width, height)
}
