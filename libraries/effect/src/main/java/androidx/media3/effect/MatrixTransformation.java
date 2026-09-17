/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.effect;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;

import android.graphics.Matrix;
import androidx.annotation.RestrictTo;
import androidx.media3.common.MetricsProvider;
import androidx.media3.common.util.UnstableApi;

/**
 * Specifies a 3x3 transformation {@link Matrix} to apply in the vertex shader for each frame.
 *
 * <p>The matrix is applied to points given in normalized device coordinates (-1 to 1 on x and y
 * axes). Transformed pixels that are moved outside of the normal device coordinate range are
 * clipped.
 *
 * <p>Output frame pixels outside of the transformed input frame will be black, with alpha = 0 if
 * applicable.
 */
@UnstableApi
public interface MatrixTransformation extends GlMatrixTransformation, MetricsProvider {
  /**
   * Returns the 3x3 transformation {@link Matrix} to apply to the frame with the given timestamp.
   */
  Matrix getMatrix(long presentationTimeUs);

  @Override
  default float[] getGlMatrixArray(long presentationTimeUs) {
    return MatrixUtils.getGlMatrixArray(getMatrix(presentationTimeUs));
  }

  @Override
  @RestrictTo(LIBRARY_GROUP)
  default void populateMetrics(MetricConsumer consumer) {
    consumer.setCategory(MetricConsumer.CATEGORY_EFFECT_SPATIAL);
  }
}
