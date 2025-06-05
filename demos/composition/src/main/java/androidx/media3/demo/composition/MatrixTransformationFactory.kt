/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.demo.composition

import android.graphics.Matrix
import androidx.media3.common.C
import androidx.media3.common.util.Util
import androidx.media3.effect.GlMatrixTransformation
import androidx.media3.effect.MatrixTransformation
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Factory for [GlMatrixTransformations][GlMatrixTransformation] and
 * [MatrixTransformations][MatrixTransformation] that create video effects by applying
 * transformation matrices to the individual video frames.
 */
internal object MatrixTransformationFactory {
  /**
   * Returns a [MatrixTransformation] that rescales the frames over the first
   * [ZOOM_DURATION_SECONDS] seconds, such that the rectangle filled with the input frame increases
   * linearly in size from a single point to filling the full output frame.
   */
  fun createZoomInTransition(): MatrixTransformation =
    MatrixTransformation(::calculateZoomInTransitionMatrix)

  /** Returns a [MatrixTransformation] that crops frames to a rectangle that moves on an ellipse. */
  fun createDizzyCropEffect(): MatrixTransformation =
    MatrixTransformation(::calculateDizzyCropMatrix)

  /**
   * Returns a [GlMatrixTransformation] that rotates a frame in 3D around the y-axis and applies
   * perspective projection to 2D.
   */
  fun createSpin3dEffect(): GlMatrixTransformation = GlMatrixTransformation(::calculate3dSpinMatrix)

  private const val ZOOM_DURATION_SECONDS = 2f
  private const val DIZZY_CROP_ROTATION_PERIOD_US = 5000000f

  private fun calculateZoomInTransitionMatrix(presentationTimeUs: Long): Matrix {
    val transformationMatrix = Matrix()
    val scale = min(1f, (presentationTimeUs / (C.MICROS_PER_SECOND * ZOOM_DURATION_SECONDS)))
    transformationMatrix.postScale(/* sx= */ scale, /* sy= */ scale)
    return transformationMatrix
  }

  private fun calculateDizzyCropMatrix(presentationTimeUs: Long): Matrix {
    val theta = presentationTimeUs * 2f * Math.PI.toFloat() / DIZZY_CROP_ROTATION_PERIOD_US
    val centerX = 0.5f * cos(theta)
    val centerY = 0.5f * sin(theta)
    val transformationMatrix = Matrix()
    transformationMatrix.postTranslate(/* dx= */ centerX, /* dy= */ centerY)
    transformationMatrix.postScale(/* sx= */ 2f, /* sy= */ 2f)
    return transformationMatrix
  }

  private fun calculate3dSpinMatrix(presentationTimeUs: Long): FloatArray {
    val transformationMatrix = FloatArray(16)
    android.opengl.Matrix.frustumM(
      transformationMatrix,
      /* offset= */ 0,
      /* left= */ -1f,
      /* right= */ 1f,
      /* bottom= */ -1f,
      /* top= */ 1f,
      /* near= */ 3f,
      /* far= */ 5f,
    )
    android.opengl.Matrix.translateM(
      transformationMatrix,
      /* mOffset= */ 0,
      /* x= */ 0f,
      /* y= */ 0f,
      /* z= */ -4f,
    )
    val theta = Util.usToMs(presentationTimeUs) / 10f
    android.opengl.Matrix.rotateM(
      transformationMatrix,
      /* mOffset= */ 0,
      theta,
      /* x= */ 0f,
      /* y= */ 1f,
      /* z= */ 0f,
    )
    return transformationMatrix
  }
}
