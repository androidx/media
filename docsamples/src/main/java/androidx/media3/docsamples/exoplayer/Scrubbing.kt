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
@file:Suppress("unused_parameter", "unused_variable", "unused", "CheckReturnValue")

package androidx.media3.docsamples.exoplayer

import android.view.View
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

// Snippets for Scrubbing mode.

@OptIn(UnstableApi::class)
object ScrubbingKt {

  // [START custom_scrubber_lifecycle]
  fun onScrubStart(exoPlayer: ExoPlayer, positionMs: Long) {
    exoPlayer.setScrubbingModeEnabled(true)
    exoPlayer.seekTo(positionMs)
  }

  fun onScrubMove(exoPlayer: ExoPlayer, positionMs: Long) {
    exoPlayer.seekTo(positionMs)
  }

  fun onScrubStop(exoPlayer: ExoPlayer, positionMs: Long, canceled: Boolean) {
    if (!canceled) {
      exoPlayer.seekTo(positionMs)
    }
    exoPlayer.setScrubbingModeEnabled(false)
  }

  // [END custom_scrubber_lifecycle]

  // [START compose_scrubber_fractional_tolerance]
  private val SCRUBBING_TOLERANCE = 2.dp

  @Composable
  fun CustomScrubbingSlider(exoPlayer: ExoPlayer?, modifier: Modifier = Modifier) {
    var sliderWidthPx by remember { mutableIntStateOf(0) }
    val tolerancePx = with(LocalDensity.current) { SCRUBBING_TOLERANCE.toPx() }
    LaunchedEffect(exoPlayer, sliderWidthPx, tolerancePx) {
      if (exoPlayer != null && sliderWidthPx > 0) {
        val tolerance = (tolerancePx.toDouble() / sliderWidthPx).coerceIn(0.0, 1.0)
        exoPlayer.setFractionalSeekTolerance(tolerance)
      }
    }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer?.setFractionalSeekTolerance(null) } }
    CustomSlider(
      player = exoPlayer,
      modifier = modifier.onSizeChanged { size -> sliderWidthPx = size.width },
    )
  }

  private fun ExoPlayer.setFractionalSeekTolerance(tolerance: Double?) {
    scrubbingModeParameters =
      scrubbingModeParameters
        .buildUpon()
        .setFractionalSeekTolerance(
          /* toleranceBefore= */ tolerance,
          /* toleranceAfter= */ tolerance,
        )
        .build()
  }

  // [END compose_scrubber_fractional_tolerance]

  @Composable private fun CustomSlider(player: Player?, modifier: Modifier = Modifier) {}
}

@OptIn(UnstableApi::class)
object ScrubbingViewKt {

  // [START view_scrubber_fractional_tolerance]
  private const val SCRUBBING_TOLERANCE_DP = 2.0

  fun configureViewFractionalSeekTolerance(
    exoPlayer: ExoPlayer,
    scrubberView: View,
  ): View.OnLayoutChangeListener {
    updateFractionalSeekTolerance(exoPlayer, scrubberView, scrubberView.width)
    val listener = View.OnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
      val widthPx = right - left
      if (widthPx != oldRight - oldLeft) {
        updateFractionalSeekTolerance(exoPlayer, v, widthPx)
      }
    }
    scrubberView.addOnLayoutChangeListener(listener)
    return listener
  }

  fun clearViewFractionalSeekTolerance(
    exoPlayer: ExoPlayer,
    scrubberView: View,
    listener: View.OnLayoutChangeListener,
  ) {
    scrubberView.removeOnLayoutChangeListener(listener)
    setFractionalSeekTolerance(exoPlayer, tolerance = null)
  }

  private fun updateFractionalSeekTolerance(
    exoPlayer: ExoPlayer,
    scrubberView: View,
    widthPx: Int,
  ) {
    val density = scrubberView.resources.displayMetrics.density
    if (widthPx > 0 && density > 0f) {
      val tolerancePx = SCRUBBING_TOLERANCE_DP * density
      setFractionalSeekTolerance(exoPlayer, (tolerancePx / widthPx).coerceIn(0.0, 1.0))
    }
  }

  private fun setFractionalSeekTolerance(exoPlayer: ExoPlayer, tolerance: Double?) {
    exoPlayer.scrubbingModeParameters =
      exoPlayer.scrubbingModeParameters
        .buildUpon()
        .setFractionalSeekTolerance(
          /* toleranceBefore= */ tolerance,
          /* toleranceAfter= */ tolerance,
        )
        .build()
  }
  // [END view_scrubber_fractional_tolerance]
}
