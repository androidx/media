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

package androidx.media3.demo.compose.text

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.media3.demo.compose.R
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The fraction of the width of the player that the overlay is centered in, on the tapped side. */
private const val SIDE_FRACTION = 0.4f

private val TEXT_SHADOW = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 8f)

internal class SeekOverlayState(private val scope: CoroutineScope) {
  var visible by mutableStateOf(false)
    private set

  /**
   * The seek amount in milliseconds (negative for seek back, positive for seek forward), added up
   * over repeated seeks in the same direction while the overlay is visible.
   */
  var seekAmountMs by mutableLongStateOf(0L)
    private set

  private var hideJob: Job? = null

  fun show(seekAmountMs: Long, durationMillis: Long = 1000L) {
    val sameDirection = (seekAmountMs > 0) == (this.seekAmountMs > 0)
    this.seekAmountMs =
      if (visible && sameDirection) this.seekAmountMs + seekAmountMs else seekAmountMs
    visible = true
    hideJob?.cancel()
    hideJob = scope.launch {
      delay(durationMillis)
      visible = false
    }
  }
}

/**
 * Shows the seek amount below arrows that light up one after another in the seek direction, on the
 * side of the player that was double tapped. Meant to fill the player.
 */
@Composable
internal fun SeekOverlay(state: SeekOverlayState, modifier: Modifier = Modifier) {
  AnimatedVisibility(
    visible = state.visible,
    enter = fadeIn(),
    exit = fadeOut(),
    modifier = modifier,
  ) {
    val forward = state.seekAmountMs > 0
    Box(Modifier.fillMaxSize()) {
      Column(
        Modifier.fillMaxHeight()
          .fillMaxWidth(SIDE_FRACTION)
          .align(if (forward) AbsoluteAlignment.CenterRight else AbsoluteAlignment.CenterLeft),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        AnimatedArrows(forward)
        val seconds = (abs(state.seekAmountMs) / 1000).toInt()
        Text(
          text = pluralStringResource(R.plurals.seek_overlay_seconds, seconds, seconds),
          color = Color.White,
          style = MaterialTheme.typography.labelLarge.copy(shadow = TEXT_SHADOW),
        )
      }
    }
  }
}

/** Three arrows pointing in the seek direction that light up one after another. */
@Composable
private fun AnimatedArrows(forward: Boolean) {
  val progress by
    rememberInfiniteTransition(label = "arrows")
      .animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing)),
        label = "progress",
      )
  Canvas(Modifier.size(width = 54.dp, height = 18.dp)) {
    val arrowWidth = size.width / 3
    for (i in 0 until 3) {
      // Number the arrows in the seek direction, so that the light moves that way.
      val order = if (forward) i else 2 - i
      drawArrow(i * arrowWidth, arrowWidth, forward, alpha = arrowAlpha(progress, order))
    }
  }
}

/** Returns the alpha of the arrow at [index] (counted in the seek direction) at [progress]. */
private fun arrowAlpha(progress: Float, index: Int): Float {
  // Each arrow brightens and dims within its own quarter of the cycle, one after another, and all
  // arrows are dim in the last quarter, so that the animation loops smoothly.
  val peak = (index + 0.5f) / 4
  return 0.25f + 0.75f * (1 - abs(progress - peak) * 8).coerceIn(0f, 1f)
}

private fun DrawScope.drawArrow(left: Float, width: Float, forward: Boolean, alpha: Float) {
  val tipX = if (forward) left + width * 0.9f else left + width * 0.1f
  val baseX = if (forward) left + width * 0.1f else left + width * 0.9f
  val path =
    Path().apply {
      moveTo(baseX, 0f)
      lineTo(tipX, size.height / 2)
      lineTo(baseX, size.height)
      close()
    }
  // A soft shadow keeps the arrow visible on bright video.
  translate(top = 1.dp.toPx()) { drawPath(path, Color.Black.copy(alpha = 0.3f * alpha)) }
  drawPath(path, Color.White.copy(alpha = alpha))
}
