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

package androidx.media3.ui.compose.modifiers

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.testing.junit.testparameterinjector.KotlinTestParameters.testValues
import com.google.testing.junit.testparameterinjector.TestParameter
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestParameterInjector

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestParameterInjector::class)
class ModifiersExtensionsTest {

  @Test
  fun resizeWithContentScale_boundedConstraints_scalesToFit() = runComposeUiTest {
    var measuredSize: IntSize? = null

    setContent {
      TestLayout(
        constraints = Constraints.fixed(300, 100),
        sourceSizeDp = Size(200f, 100f),
        contentScale = ContentScale.Fit,
        onPlaceableMeasured = { measuredSize = it },
      )
    }

    assertThat(measuredSize).isEqualTo(IntSize(200, 100))
  }

  @Test
  fun resizeWithContentScale_unboundedWidth_scalesToHeight(
    @TestParameter
    scale: ContentScale =
      testValues(
        ContentScale.Fit,
        ContentScale.Crop,
        ContentScale.FillWidth,
        ContentScale.FillHeight,
        ContentScale.FillBounds,
      ),
    @TestParameter sourceSize: Size? = testValues(Size(400f, 200f), Size(20f, 10f)),
  ) = runComposeUiTest {
    var measuredSize: IntSize? = null

    setContent {
      TestLayout(
        constraints =
          Constraints(
            minWidth = 0,
            maxWidth = Constraints.Infinity,
            minHeight = 0,
            maxHeight = 100,
          ),
        sourceSizeDp = sourceSize,
        contentScale = scale,
        onPlaceableMeasured = { measuredSize = it },
      )
    }

    assertThat(measuredSize).isEqualTo(IntSize(200, 100))
  }

  @Test
  fun resizeWithContentScale_unboundedHeight_scalesToWidth(
    @TestParameter
    scale: ContentScale =
      testValues(
        ContentScale.Fit,
        ContentScale.Crop,
        ContentScale.FillWidth,
        ContentScale.FillHeight,
        ContentScale.FillBounds,
      ),
    @TestParameter sourceSize: Size? = testValues(Size(200f, 100f), Size(10f, 5f)),
  ) = runComposeUiTest {
    var measuredSize: IntSize? = null

    setContent {
      TestLayout(
        constraints =
          Constraints(
            minWidth = 0,
            maxWidth = 100,
            minHeight = 0,
            maxHeight = Constraints.Infinity,
          ),
        sourceSizeDp = sourceSize,
        contentScale = scale,
        onPlaceableMeasured = { measuredSize = it },
      )
    }

    assertThat(measuredSize).isEqualTo(IntSize(100, 50))
  }

  @Test
  fun resizeWithContentScale_bothUnbounded_usesSourceSize() = runComposeUiTest {
    var measuredSize: IntSize? = null

    setContent {
      TestLayout(
        constraints = Constraints(),
        sourceSizeDp = Size(200f, 100f),
        contentScale = ContentScale.Fit,
        onPlaceableMeasured = { measuredSize = it },
      )
    }

    assertThat(measuredSize).isEqualTo(IntSize(200, 100))
  }

  @Test
  fun resizeWithContentScale_crop_scalesToCrop() = runComposeUiTest {
    var measuredSize: IntSize? = null
    var outerSize: IntSize? = null

    setContent {
      TestLayout(
        constraints = Constraints.fixed(120, 80),
        sourceSizeDp = Size(400f, 200f),
        contentScale = ContentScale.Crop,
        onPlaceableMeasured = { measuredSize = it },
        onOuterMeasured = { outerSize = it },
      )
    }

    // Child scaled down, but still overflowing in width compared to the parent container
    assertThat(measuredSize).isEqualTo(IntSize(160, 80))
    assertThat(outerSize).isEqualTo(IntSize(120, 80))
  }

  @Test
  fun resizeWithContentScale_scaledWidthSmallerThanMinWidth_keepsScaledSize() = runComposeUiTest {
    var measuredSize: IntSize? = null
    var outerSize: IntSize? = null

    setContent {
      TestLayout(
        constraints = Constraints(minWidth = 150, maxWidth = 300, minHeight = 0, maxHeight = 50),
        sourceSizeDp = Size(200f, 100f),
        contentScale = ContentScale.Fit,
        onPlaceableMeasured = { measuredSize = it },
        onOuterMeasured = { outerSize = it },
      )
    }

    // Even though minWidth is 150, we use the smaller maxHeight (50) to scale the source.
    assertThat(measuredSize).isEqualTo(IntSize(100, 50))
    assertThat(outerSize).isEqualTo(IntSize(300, 50))
  }

  @Test
  fun resizeWithContentScale_noneOrInside_respectsSourceSize(
    @TestParameter densityValue: Float = testValues(1f, 2f),
    @TestParameter scale: ContentScale = testValues(ContentScale.Inside, ContentScale.None),
  ) = runComposeUiTest {
    var measuredSize: IntSize? = null
    var outerSize: IntSize? = null

    setContent {
      CompositionLocalProvider(LocalDensity provides Density(densityValue)) {
        TestLayout(
          constraints = Constraints.fixed(300, 100),
          sourceSizeDp = Size(10f, 5f),
          contentScale = scale,
          onPlaceableMeasured = { measuredSize = it },
          onOuterMeasured = { outerSize = it },
        )
      }
    }

    val expectedWidth = (10 * densityValue).toInt()
    val expectedHeight = (5 * densityValue).toInt()
    assertThat(measuredSize).isEqualTo(IntSize(expectedWidth, expectedHeight))
    assertThat(outerSize).isEqualTo(IntSize(300, 100))
  }

  @Test
  fun resizeWithContentScale_nullSourceSize_doesNotScale() = runComposeUiTest {
    var measuredSize: IntSize? = null
    var outerSize: IntSize? = null

    setContent {
      TestLayout(
        constraints = Constraints(minWidth = 10, maxWidth = 100, minHeight = 20, maxHeight = 200),
        sourceSizeDp = null,
        contentScale = ContentScale.Fit,
        childModifier = Modifier,
        onPlaceableMeasured = { measuredSize = it },
        onOuterMeasured = { outerSize = it },
      )
    }

    // If we only had `wrapContentSize()` but not `fillMaxSize()`, outerSize would be 10x20
    assertThat(outerSize).isEqualTo(IntSize(100, 200)) // fillMaxSize() lead to max constraints
    // If we only had `fillMaxSize()` but not `wrapContentSize()`, measuredSize would be 100x200
    assertThat(measuredSize).isEqualTo(IntSize(0, 0)) // wrapContentSize() drops min constraints
  }

  @Test
  fun resizeWithContentScale_invalidSourceSize_measuresToMinConstraints(
    @TestParameter
    sourceSize: Size? =
      testValues(
        Size(0f, 100f),
        Size(100f, 0f),
        Size(-10f, 20f),
        Size(10f, -20f),
        Size.Unspecified,
        Size(Float.POSITIVE_INFINITY, 100f),
        Size(100f, Float.POSITIVE_INFINITY),
        Size(Float.NaN, 100f),
        Size(100f, Float.NaN),
      )
  ) = runComposeUiTest {
    var measuredSize: IntSize? = null
    var outerSize: IntSize? = null

    setContent {
      TestLayout(
        constraints = Constraints(minWidth = 10, maxWidth = 100, minHeight = 20, maxHeight = 200),
        sourceSizeDp = sourceSize,
        contentScale = ContentScale.Fit,
        onPlaceableMeasured = { measuredSize = it },
        onOuterMeasured = { outerSize = it },
      )
    }

    assertThat(measuredSize).isEqualTo(IntSize(10, 20))
    assertThat(measuredSize).isEqualTo(outerSize)
  }

  @Test
  fun resizeWithContentScale_childSmallerThanScaledConstraints_respectsChildSize() =
    runComposeUiTest {
      var measuredSize: IntSize? = null
      var outerSize: IntSize? = null
      var scaledSize: IntSize? = null

      setContent {
        TestLayout(
          constraints = Constraints.fixed(300, 100),
          sourceSizeDp = Size(100f, 50f),
          contentScale = ContentScale.Fit,
          childModifier = Modifier.size(50.dp, 10.dp),
          onPlaceableMeasured = { measuredSize = it },
          onOuterMeasured = { outerSize = it },
          onScaledSizeCalculated = { scaledSize = it },
        )
      }

      assertThat(scaledSize).isEqualTo(IntSize(200, 100))
      assertThat(measuredSize).isEqualTo(IntSize(50, 10)) // loses the 2:1 aspect ratio
      assertThat(outerSize).isEqualTo(IntSize(300, 100))
    }

  @Test
  fun resizeWithContentScale_childLargerThanScaledConstraints_clampedToScaledConstraints() =
    runComposeUiTest {
      var measuredSize: IntSize? = null
      var outerSize: IntSize? = null
      var scaledSize: IntSize? = null

      setContent {
        TestLayout(
          constraints = Constraints.fixed(300, 100),
          sourceSizeDp = Size(100f, 50f),
          contentScale = ContentScale.Fit,
          childModifier = Modifier.size(250.dp, 200.dp),
          onPlaceableMeasured = { measuredSize = it },
          onOuterMeasured = { outerSize = it },
          onScaledSizeCalculated = { scaledSize = it },
        )
      }

      assertThat(scaledSize).isEqualTo(IntSize(200, 100))
      assertThat(measuredSize).isEqualTo(IntSize(200, 100)) // Ignoring child's larger size 250x200
      assertThat(outerSize).isEqualTo(IntSize(300, 100))
    }

  @Test
  fun resizeWithContentScale_childWidthSmallerHeightLarger_clampsOnlyHeight() = runComposeUiTest {
    var measuredSize: IntSize? = null
    var outerSize: IntSize? = null
    var scaledSize: IntSize? = null

    setContent {
      TestLayout(
        constraints = Constraints.fixed(300, 100),
        sourceSizeDp = Size(100f, 50f),
        contentScale = ContentScale.Fit,
        childModifier = Modifier.size(50.dp, 150.dp),
        onPlaceableMeasured = { measuredSize = it },
        onOuterMeasured = { outerSize = it },
        onScaledSizeCalculated = { scaledSize = it },
      )
    }

    assertThat(scaledSize).isEqualTo(IntSize(200, 100))
    assertThat(measuredSize).isEqualTo(IntSize(50, 100)) // Width 50 respected, height 150 clamped
    assertThat(outerSize).isEqualTo(IntSize(300, 100))
  }

  /**
   * A helper layout to test [resizeWithContentScale] modifier sizing behavior.
   *
   * It uses a nested layout structure to simulate parent constraints and intercept child
   * measurement:
   * 1. **Outer Layout**: Receives the parent's [constraints] and measures its single child (the
   *    Inner Layout) with them. It reports the final outer measured size via [onOuterMeasured].
   * 2. **Inner Layout**: Simulates the content surface (like PlayerSurface) being sized. It applies
   *    [resizeWithContentScale] to scale incoming constraints based on [sourceSizeDp]. It then
   *    applies a custom [layout] modifier to intercept the scaled constraints and report the
   *    resulting size via [onPlaceableMeasured]. Finally, [fillMaxSize] forces the empty core
   *    layout to fill the scaled constraints, simulating a child that expands to fill the size
   *    calculated by [resizeWithContentScale].
   */
  @Composable
  private fun TestLayout(
    constraints: Constraints,
    sourceSizeDp: Size?,
    contentScale: ContentScale,
    @SuppressLint("ModifierParameter") childModifier: Modifier = Modifier.fillMaxSize(),
    onPlaceableMeasured: (IntSize) -> Unit,
    onOuterMeasured: (IntSize) -> Unit = {},
    onScaledSizeCalculated: (IntSize) -> Unit = {},
  ) {
    Layout(
      content = {
        Layout(
          content = {},
          modifier =
            Modifier.resizeWithContentScale(contentScale, sourceSizeDp)
              .layout { measurable, childConstraints ->
                onScaledSizeCalculated(
                  IntSize(childConstraints.maxWidth, childConstraints.maxHeight)
                )
                val placeable = measurable.measure(childConstraints)
                onPlaceableMeasured(IntSize(placeable.width, placeable.height))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
              }
              .then(childModifier),
        ) { _, childConstraints ->
          layout(childConstraints.minWidth, childConstraints.minHeight) {}
        }
      }
    ) { measurables, _ ->
      val placeable = measurables.first().measure(constraints)
      onOuterMeasured(IntSize(placeable.width, placeable.height))
      layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
  }
}
