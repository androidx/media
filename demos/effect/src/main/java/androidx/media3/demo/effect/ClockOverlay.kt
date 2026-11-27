/*
 * Copyright 2026 The Android Open Source Project
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

package androidx.media3.demo.effect

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.StaticOverlaySettings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * An overlay that draws a simple clock with a needle rotating based on the presentation time.
 *
 * The clock is drawn in the bottom-right corner of the screen.
 */
internal class ClockOverlay : CanvasOverlay(/* useInputFrameSize= */ false) {

  private val dialPaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeWidth = DIAL_WIDTH
      color = CLOCK_COLOR
    }

  private val needlePaint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
      strokeWidth = NEEDLE_WIDTH
      color = CLOCK_COLOR
    }

  private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CLOCK_COLOR }

  init {
    setCanvasSize(/* width= */ DIAL_SIZE, /* height= */ DIAL_SIZE)
  }

  override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
    // Clears the canvas
    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

    // Draw the dial
    canvas.drawArc(
      DIAL_BOUND,
      /* startAngle= */ 0f,
      /* sweepAngle= */ 360f,
      /* useCenter= */ false,
      dialPaint,
    )

    // Draw the needle
    val angle = 6 * presentationTimeUs / 1_000_000f - 90
    val radians = angle * (PI.toFloat() / 180f)

    val startX = CENTRE_X - 10 * cos(radians)
    val startY = CENTRE_Y - 10 * sin(radians)
    val endX = CENTRE_X + NEEDLE_LENGTH * cos(radians)
    val endY = CENTRE_Y + NEEDLE_LENGTH * sin(radians)

    canvas.drawLine(startX, startY, endX, endY, needlePaint)

    // Draw a small hub at the center
    canvas.drawCircle(CENTRE_X.toFloat(), CENTRE_Y.toFloat(), HUB_SIZE.toFloat(), hubPaint)
  }

  override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
    return StaticOverlaySettings.Builder()
      .setBackgroundFrameAnchor(
        BOTTOM_RIGHT_ANCHOR_X - ANCHOR_INSET_X,
        BOTTOM_RIGHT_ANCHOR_Y - ANCHOR_INSET_Y,
      )
      .setOverlayFrameAnchor(BOTTOM_RIGHT_ANCHOR_X, BOTTOM_RIGHT_ANCHOR_Y)
      .build()
  }

  companion object {
    private const val CLOCK_COLOR = Color.WHITE

    private const val DIAL_SIZE = 200
    private const val DIAL_WIDTH = 3f
    private const val NEEDLE_WIDTH = 3f
    private const val NEEDLE_LENGTH = DIAL_SIZE / 2 - 20
    private const val CENTRE_X = DIAL_SIZE / 2
    private const val CENTRE_Y = DIAL_SIZE / 2
    private const val DIAL_INSET = 5
    private val DIAL_BOUND =
      RectF(
        /* left= */ DIAL_INSET.toFloat(),
        /* top= */ DIAL_INSET.toFloat(),
        /* right= */ (DIAL_SIZE - DIAL_INSET).toFloat(),
        /* bottom= */ (DIAL_SIZE - DIAL_INSET).toFloat(),
      )
    private const val HUB_SIZE = 5

    private const val BOTTOM_RIGHT_ANCHOR_X = 1f
    private const val BOTTOM_RIGHT_ANCHOR_Y = -1f
    private const val ANCHOR_INSET_X = 0.1f
    private const val ANCHOR_INSET_Y = -0.1f
  }
}
