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

package androidx.media3.test.proguard

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.ConditionVariable
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.compose.material3.util.clearFractionalSeekToleranceIfEquals
import androidx.media3.ui.compose.material3.util.isScrubbingModeEnabled
import androidx.media3.ui.compose.material3.util.setFractionalSeekTolerance
import androidx.media3.ui.compose.material3.util.setScrubbingModeEnabled
import java.util.concurrent.atomic.AtomicInteger

internal fun testScrubbingModeReflection(context: Context) {
  val scrubbingModeUpdateCount = AtomicInteger()
  val updatedParametersReceived = ConditionVariable()
  val exoPlayer: Player =
    ExoPlayer.Builder(context) { _, _, _, _, _ ->
        arrayOf(
          object : BaseRenderer(C.TRACK_TYPE_VIDEO) {
            override fun getName(): String = "test"

            override fun render(positionUs: Long, elapsedRealtimeUs: Long) {}

            override fun isReady(): Boolean = false

            override fun isEnded(): Boolean = false

            override fun supportsFormat(format: Format): Int =
              RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)

            override fun handleMessage(messageType: Int, message: Any?) {
              if (messageType == Renderer.MSG_SET_SCRUBBING_MODE && message != null) {
                if (scrubbingModeUpdateCount.incrementAndGet() == 3) {
                  updatedParametersReceived.open()
                }
              }
            }
          }
        )
      }
      .build()
  try {
    exoPlayer.setScrubbingModeEnabled(true)
    check(exoPlayer.isScrubbingModeEnabled())
    exoPlayer.setFractionalSeekTolerance(toleranceBefore = 0.01, toleranceAfter = 0.02)
    exoPlayer.clearFractionalSeekToleranceIfEquals(toleranceBefore = 0.05, toleranceAfter = 0.05)
    exoPlayer.clearFractionalSeekToleranceIfEquals(toleranceBefore = 0.01, toleranceAfter = 0.02)
    check(updatedParametersReceived.block(5_000))
  } finally {
    exoPlayer.release()
  }

  val compPlayer: Player = CompositionPlayer.Builder(context).build()
  try {
    compPlayer.setScrubbingModeEnabled(true)
    check(compPlayer.isScrubbingModeEnabled())
  } finally {
    compPlayer.release()
  }
}
