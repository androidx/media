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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
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
  fun resizeWithContentScale_unboundedWidth_scalesToFitHeight() = runComposeUiTest {
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
        sourceSizeDp = Size(400f, 200f),
        contentScale = ContentScale.Fit,
        onPlaceableMeasured = { measuredSize = it },
      )
    }

    assertThat(measuredSize).isEqualTo(IntSize(200, 100))
  }

  @Test
  fun resizeWithContentScale_unboundedHeight_scalesToFitWidth() = runComposeUiTest {
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
        sourceSizeDp = Size(200f, 100f),
        contentScale = ContentScale.Fit,
        onPlaceableMeasured = { measuredSize = it },
      )
    }

    assertThat(measuredSize).isEqualTo(IntSize(100, 50))
  }

  @Test
  fun resizeWithContentScale_nullSourceSize_doesNotScale() = runComposeUiTest {
    var measuredSize: IntSize? = null

    setContent {
      TestLayout(
        constraints = Constraints.fixed(300, 100),
        sourceSizeDp = null,
        contentScale = ContentScale.Fit,
        onPlaceableMeasured = { measuredSize = it },
      )
    }

    // Null source size should result in no scaling, filling constraints
    assertThat(measuredSize).isEqualTo(IntSize(300, 100))
  }

  @Composable
  private fun TestLayout(
    constraints: Constraints,
    sourceSizeDp: Size?,
    contentScale: ContentScale,
    onPlaceableMeasured: (IntSize) -> Unit,
  ) {
    Layout(
      content = {
        Box(
          Modifier.resizeWithContentScale(contentScale, sourceSizeDp)
            .layout { measurable, childConstraints ->
              val placeable = measurable.measure(childConstraints)
              onPlaceableMeasured(IntSize(placeable.width, placeable.height))
              layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .fillMaxSize()
        )
      }
    ) { measurables, _ ->
      val placeable = measurables.first().measure(constraints)
      layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
  }
}
