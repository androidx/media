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
 */
@UnstableApi
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
    this.then(
      Modifier.fillMaxSize()
        .wrapContentSize()
        .then(
          Modifier.layout { measurable, constraints ->
            val srcSizePx =
              with(density) { Size(Dp(sourceSizeDp.width).toPx(), Dp(sourceSizeDp.height).toPx()) }
            val aspectRatio = srcSizePx.width / srcSizePx.height

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
                val scaleFactor =
                  contentScale.computeScaleFactor(srcSizePx, Size(dstWidth, dstHeight))
                measurable.measure(
                  constraints.copy(
                    maxWidth = (srcSizePx.width * scaleFactor.scaleX).roundToInt(),
                    maxHeight = (srcSizePx.height * scaleFactor.scaleY).roundToInt(),
                  )
                )
              } else {
                measurable.measure(
                  constraints.copy(
                    maxWidth = srcSizePx.width.roundToInt(),
                    maxHeight = srcSizePx.height.roundToInt(),
                  )
                )
              }
            val layoutWidth = placeable.width.coerceIn(constraints.minWidth, constraints.maxWidth)
            val layoutHeight =
              placeable.height.coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(layoutWidth, layoutHeight) {
              val x = (layoutWidth - placeable.width) / 2
              val y = (layoutHeight - placeable.height) / 2
              placeable.place(x, y)
            }
          }
        )
    )
  }
