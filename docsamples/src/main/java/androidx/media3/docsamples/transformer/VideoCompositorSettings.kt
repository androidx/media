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

package androidx.media3.docsamples.transformer

import androidx.annotation.OptIn
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.StaticOverlaySettings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Snippets for videocompositorsettings.md. */
@OptIn(UnstableApi::class)
object VideoCompositorSettingsKt {
  val gridCompositorSettings =
    object : VideoCompositorSettings {
      override fun getOutputSize(inputSizes: List<Size>): Size {
        return inputSizes[0]
      }

      // [START grid_overlay_settings]
      override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        return when (inputId) {
          // Position the first sequence in the top-left
          0 -> {
            StaticOverlaySettings.Builder()
              // Scale the video down to 1/4th the size of the frame
              .setScale(0.5f, 0.5f)
              // Anchor the sequence in the middle of frame
              .setOverlayFrameAnchor(0f, 0f)
              // Position the video in the top-left section of the frame
              .setBackgroundFrameAnchor(-0.5f, 0.5f)
              .build()
          }
          // Add more cases for remaining input sequences
          else -> StaticOverlaySettings.Builder().build()
        }
      }
      // [END grid_overlay_settings]
    }

  val pipCompositorSettings =
    object : VideoCompositorSettings {
      // 10 seconds
      val cycleTimeUs = 10_000_000L

      override fun getOutputSize(inputSizes: List<Size>): Size {
        return inputSizes[0]
      }

      // [START pip_overlay_settings]
      override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        return if (inputId == 0) {
          // Use the first sequence as the overlay
          val cycleRadians = 2 * PI * (presentationTimeUs.toDouble() / cycleTimeUs)
          StaticOverlaySettings.Builder()
            // Scale the overlay down
            .setScale(0.35f, 0.35f)
            // Anchor the overlay in the top-middle of the frame
            .setOverlayFrameAnchor(0f, 1f)
            // Move the overlay over time
            .setBackgroundFrameAnchor(sin(cycleRadians).toFloat() * 0.5f, -0.2f)
            // Rotate the overlay over time
            .setRotationDegrees(cos(cycleRadians).toFloat() * -10f)
            .build()
        } else {
          // Present the second sequence in the background as normal
          StaticOverlaySettings.Builder().build()
        }
      }
      // [END pip_overlay_settings]
    }
}
