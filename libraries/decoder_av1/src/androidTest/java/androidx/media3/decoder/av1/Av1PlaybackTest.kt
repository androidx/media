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
package androidx.media3.decoder.av1

import android.content.Context
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.Flags
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.video.VideoDecoderGLSurfaceView
import androidx.media3.test.utils.BindFlag
import androidx.media3.test.utils.HostActivity
import androidx.media3.test.utils.Media3FlagsRule
import androidx.media3.test.utils.R
import androidx.media3.test.utils.awaitPlaybackState
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.filters.SdkSuppress
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameter
import org.junit.runners.Parameterized.Parameters

/** Playback tests using [Libdav1dVideoRenderer] and unified [Dav1dCodecAdapter]. */
@RunWith(Parameterized::class)
class Av1PlaybackTest {

  @get:Rule val flagsRule = Media3FlagsRule(this)

  @Parameter(0) @JvmField var title: String = ""
  @Parameter(1) @JvmField var uri: String = ""
  @Parameter(2)
  @JvmField
  @BindFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER)
  var isUnifiedMode: Boolean = false

  @Before
  fun setUp() {
    assertWithMessage("Dav1d library not available").that(Dav1dLibrary.isAvailable()).isTrue()
  }

  @Test
  fun play_withOutputBufferRenderer_completesSuccessfully() {
    runPlayback(uri, surface = null, useOutputBufferRenderer = true)
  }

  @Test
  fun play_withoutOutput_completesSuccessfully() {
    runPlayback(uri, surface = null, useOutputBufferRenderer = false)
  }

  // Pre-API-30 x86 emulator images have a gralloc.ranchu heap buffer overflow when locking YV12
  // buffers via ANativeWindow_lock (see Dav1dDecoderTest.MIN_API_FOR_SURFACE_RENDERING).
  @Test
  @SdkSuppress(minSdkVersion = 30)
  fun play_withSurface_completesSuccessfully() {
    runPlaybackOnHostSurface(uri)
  }

  private fun runPlaybackOnHostSurface(mediaUri: String) {
    ActivityScenario.launch(HostActivity::class.java).use { scenario ->
      val surfaceDeferred = CompletableDeferred<Surface>()
      scenario.onActivity { activity ->
        val hostSurfaceView = activity.findViewById<SurfaceView>(R.id.surface_view)
        val currentSurface = hostSurfaceView.holder.surface
        if (currentSurface != null && currentSurface.isValid) {
          surfaceDeferred.complete(currentSurface)
        } else {
          hostSurfaceView.holder.addCallback(
            object : SurfaceHolder.Callback {
              override fun surfaceCreated(holder: SurfaceHolder) {
                holder.removeCallback(this)
                surfaceDeferred.complete(holder.surface)
              }

              override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
              ) {}

              override fun surfaceDestroyed(holder: SurfaceHolder) {}
            }
          )
        }
      }
      val validSurface = runBlocking { withTimeout(10_000) { surfaceDeferred.await() } }
      runPlayback(mediaUri, validSurface, useOutputBufferRenderer = false)
    }
  }

  private fun runPlayback(mediaUri: String, surface: Surface?, useOutputBufferRenderer: Boolean) {
    runBlocking(Dispatchers.Main) {
      val context = getApplicationContext<Context>()
      val renderersFactory =
        DefaultRenderersFactory(context)
          .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
      var initializedDecoderName: String? = null
      val player = ExoPlayer.Builder(context).setRenderersFactory(renderersFactory).build()
      val processedOutputBufferCount =
        try {
          player.addAnalyticsListener(
            object : AnalyticsListener {
              override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
              ) {
                initializedDecoderName = decoderName
              }
            }
          )
          if (surface != null) {
            player.setVideoSurface(surface)
          } else if (useOutputBufferRenderer) {
            player.setVideoSurfaceView(VideoDecoderGLSurfaceView(context))
          }
          player.setMediaItem(MediaItem.fromUri(mediaUri))
          player.prepare()
          player.play()
          player.awaitPlaybackState(Player.STATE_ENDED)
          val counters = player.videoDecoderCounters
          if (surface != null || useOutputBufferRenderer) {
            counters?.renderedOutputBufferCount ?: 0
          } else {
            (counters?.renderedOutputBufferCount ?: 0) + (counters?.skippedOutputBufferCount ?: 0)
          }
        } finally {
          player.release()
        }

      assertThat(processedOutputBufferCount).isAtLeast(1)
      assertThat(initializedDecoderName)
        .isEqualTo(if (isUnifiedMode) Dav1dCodecInfo.NAME else "libdav1d")
    }
  }

  companion object {
    @JvmStatic
    @Parameters(name = "{0}_unified={2}")
    fun params(): List<Array<Any>> =
      listOf(
        arrayOf("8bit_color_sdr", "asset:///media/mp4/sample_av1.mp4", false),
        arrayOf("8bit_color_sdr", "asset:///media/mp4/sample_av1.mp4", true),
        arrayOf("8bit_monochrome_sdr", "asset:///media/mp4/sample_av1_monochrome_sdr.mp4", false),
        arrayOf("8bit_monochrome_sdr", "asset:///media/mp4/sample_av1_monochrome_sdr.mp4", true),
      )
  }
}
