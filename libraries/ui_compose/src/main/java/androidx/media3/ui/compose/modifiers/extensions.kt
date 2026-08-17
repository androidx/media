/*
 * Copyright 2025 The Android Open Source Project
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

package androidx.media3.ui.compose.modifiers

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.media3.common.util.UnstableApi
import kotlin.math.roundToInt

/**
 * Attempts to size the original content rectangle to be inscribed into a destination by applying a
 * specified [ContentScale] type.
 *
 * @param contentScale The [ContentScale] to apply to the content.
 * @param aspectRatio The aspect ratio of the content (width / height). If null, no scaling is
 *   applied.
 */
@UnstableApi
fun Modifier.resizeWithContentScale(contentScale: ContentScale, aspectRatio: Float?): Modifier {
  return if (aspectRatio == null) {
    this.then(Modifier.fillMaxSize().wrapContentSize())
  } else {
    this.resizeWithContentScaleCommon(contentScale, sourceSizePx = null, aspectRatio = aspectRatio)
  }
}

/**
 * Attempts to size the original content rectangle to be inscribed into a destination by applying a
 * specified [ContentScale] type.
 *
 * @param contentScale The [ContentScale] to apply to the content.
 * @param sourceSizeDp The size of the content in DP.
 * @param density The [Density] to use for conversions.
 * @deprecated Use the overload that takes aspectRatio instead.
 */
@UnstableApi
@Deprecated(
  "Use the overload that takes aspectRatio instead.",
  ReplaceWith(
    "this.resizeWithContentScale(contentScale, sourceSizeDp?.run { if (width > 0 && height > 0) width / height else null })"
  ),
)
@Composable
fun Modifier.resizeWithContentScale(
  contentScale: ContentScale,
  sourceSizeDp: Size?,
  density: Density = LocalDensity.current,
): Modifier =
  if (sourceSizeDp == null) {
    this.then(Modifier.fillMaxSize().wrapContentSize())
  } else if (
    !sourceSizeDp.width.isFinite() || !sourceSizeDp.height.isFinite() || sourceSizeDp.isEmpty()
  ) {
    // Grouping !isFinite() with <= 0f ensures that all invalid/unspecified sizes (Zero, NaN,
    // Infinity) behave consistently by falling back to minimum constraints, rather than having Zero
    // hide the layout and NaN/null expand it.
    this.then(
      Modifier.layout { measurable, constraints ->
        val placeable =
          measurable.measure(Constraints.fixed(constraints.minWidth, constraints.minHeight))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
      }
    )
  } else {
    val sourceSizePx =
      with(density) { Size(Dp(sourceSizeDp.width).toPx(), Dp(sourceSizeDp.height).toPx()) }
    val aspectRatio = sourceSizePx.width / sourceSizePx.height
    this.resizeWithContentScaleCommon(
      contentScale,
      sourceSizePx = sourceSizePx,
      aspectRatio = aspectRatio,
    )
  }

private fun Modifier.resizeWithContentScaleCommon(
  contentScale: ContentScale,
  sourceSizePx: Size?,
  aspectRatio: Float,
): Modifier {
  require(aspectRatio > 0 && aspectRatio.isFinite()) {
    "aspectRatio must be positive and finite: $aspectRatio"
  }
  return this.then(
    Modifier.fillMaxSize()
      .wrapContentSize()
      .then(
        Modifier.layout { measurable, constraints ->
          val placeable =
            if (constraints.hasBoundedWidth || constraints.hasBoundedHeight) {
              val dstWidth =
                if (constraints.hasBoundedWidth) {
                  constraints.maxWidth.toFloat()
                } else {
                  constraints.maxHeight * aspectRatio
                }
              val dstHeight =
                if (constraints.hasBoundedHeight) {
                  constraints.maxHeight.toFloat()
                } else {
                  constraints.maxWidth / aspectRatio
                }
              val dstSizePx = Size(dstWidth, dstHeight)

              val syntheticallyScaledSrcSize =
                if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
                  if (dstHeight * aspectRatio < dstWidth) {
                    Size(dstHeight * aspectRatio, dstHeight)
                  } else {
                    Size(dstWidth, dstWidth / aspectRatio)
                  }
                } else {
                  dstSizePx
                }
              val srcSizeForScaling = sourceSizePx ?: syntheticallyScaledSrcSize

              val scaleFactor = contentScale.computeScaleFactor(srcSizeForScaling, dstSizePx)
              measurable.measure(
                constraints.copy(
                  maxWidth = (srcSizeForScaling.width * scaleFactor.scaleX).roundToInt(),
                  maxHeight = (srcSizeForScaling.height * scaleFactor.scaleY).roundToInt(),
                )
              )
            } else {
              // constraints width & height are both unbounded
              if (sourceSizePx != null) {
                // Use source size as a fallback for bounds
                measurable.measure(
                  constraints.copy(
                    maxWidth = sourceSizePx.width.roundToInt(),
                    maxHeight = sourceSizePx.height.roundToInt(),
                  )
                )
              } else {
                // no external or specified bounds, measure with original constraints
                val measuredPlaceable = measurable.measure(constraints)
                if (measuredPlaceable.width == 0 || measuredPlaceable.height == 0) {
                  throw IllegalArgumentException(
                    "resizeWithContentScale measured to 0x0 under unbounded constraints. " +
                      "Ensure the parent layout has a bounded dimension or the child has a non-zero size."
                  )
                }
                measuredPlaceable
              }
            }

          val layoutWidth = placeable.width.coerceIn(constraints.minWidth, constraints.maxWidth)
          val layoutHeight = placeable.height.coerceIn(constraints.minHeight, constraints.maxHeight)
          layout(layoutWidth, layoutHeight) {
            val x = (layoutWidth - placeable.width) / 2
            val y = (layoutHeight - placeable.height) / 2
            placeable.place(x, y)
          }
        }
      )
  )
}
