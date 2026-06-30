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
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.video.VideoDecoderGLSurfaceView
import androidx.media3.test.utils.awaitPlaybackState
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.filters.SdkSuppress
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.testing.junit.testparameterinjector.KotlinTestParameters.namedTestValues
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Playback tests using [Libdav1dVideoRenderer]. */
@RunWith(TestParameterInjector::class)
class Av1PlaybackTest {

  @Before
  fun setUp() {
    assertWithMessage("Dav1d library not available").that(Dav1dLibrary.isAvailable()).isTrue()
  }

  @Test
  fun playback_rendersToGlSurfaceView(
    @TestParameter
    videoUri: String =
      namedTestValues("DEFAULT" to SAMPLE_AV1_URI, "MONOCHROME" to SAMPLE_AV1_MONO_URI)
  ) =
    runBlocking(Dispatchers.Main) {
      runPlaybackTest(videoUri) { player ->
        player.setVideoSurfaceView(VideoDecoderGLSurfaceView(getApplicationContext()))
      }
    }

  // Rendering using ANativeWindow_lock is only reliably supported on API 29+.
  @Test
  @SdkSuppress(minSdkVersion = 29)
  fun playback_rendersToSurface(
    @TestParameter
    videoUri: String =
      namedTestValues("DEFAULT" to SAMPLE_AV1_URI, "MONOCHROME" to SAMPLE_AV1_MONO_URI)
  ) =
    runBlocking(Dispatchers.Main) {
      val surfaceTexture = SurfaceTexture(/* texName= */ 0)
      val surface = Surface(surfaceTexture)
      try {
        runPlaybackTest(videoUri) { player -> player.setVideoSurface(surface) }
      } finally {
        surface.release()
        surfaceTexture.release()
      }
    }

  private suspend fun runPlaybackTest(videoUri: String, setSurfaceAction: (ExoPlayer) -> Unit) {
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
    setSurfaceAction(player)
    player.setMediaItem(MediaItem.fromUri(videoUri))
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
    private const val SAMPLE_AV1_URI = "asset:///media/mp4/sample_av1.mp4"
    private const val SAMPLE_AV1_MONO_URI = "asset:///media/mp4/sample_av1_mono.mp4"
  }
}
