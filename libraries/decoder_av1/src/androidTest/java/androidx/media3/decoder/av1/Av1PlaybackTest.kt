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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.video.VideoDecoderGLSurfaceView
import androidx.media3.test.utils.awaitPlaybackState
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameter
import org.junit.runners.Parameterized.Parameters

/** Playback tests using [Libdav1dVideoRenderer]. */
@RunWith(Parameterized::class)
class Av1PlaybackTest {

  @Parameter(0) @JvmField var title: String = ""
  @Parameter(1) @JvmField var uri: String = ""

  @Before
  fun setUp() {
    assertWithMessage("Dav1d library not available").that(Dav1dLibrary.isAvailable()).isTrue()
  }

  @Test
  fun playback() =
    runBlocking(Dispatchers.Main) {
      val context = getApplicationContext<Context>()
      val renderersFactory = RenderersFactory { eventHandler, videoRendererEventListener, _, _, _ ->
        arrayOf(
          Libdav1dVideoRenderer(
            /* allowedJoiningTimeMs= */ 0,
            eventHandler,
            videoRendererEventListener,
            /* maxDroppedFramesToNotify= */ -1,
          )
        )
      }
      val player = ExoPlayer.Builder(context).setRenderersFactory(renderersFactory).build()
      player.setVideoSurfaceView(VideoDecoderGLSurfaceView(context))
      player.setMediaItem(MediaItem.fromUri(uri))
      player.prepare()
      player.play()

      val renderedOutputBufferCount =
        try {
          player.awaitPlaybackState(Player.STATE_ENDED)
          player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
        } finally {
          player.release()
        }

      assertThat(renderedOutputBufferCount).isAtLeast(1)
    }

  companion object {
    @JvmStatic
    @Parameters(name = "{0}")
    fun params(): List<Array<Any>> =
      listOf(
        arrayOf("8bit_color_sdr", "asset:///media/mp4/sample_av1.mp4"),
        arrayOf("8bit_monochrome_sdr", "asset:///media/mp4/sample_av1_monochrome_sdr.mp4"),
      )
  }
}
