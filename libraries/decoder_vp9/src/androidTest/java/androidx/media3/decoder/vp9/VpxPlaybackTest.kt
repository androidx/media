/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.decoder.vp9

import android.content.Context
import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoDecoderGLSurfaceView
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.test.utils.awaitPlaybackState
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import kotlin.test.assertFailsWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Playback tests using [LibvpxVideoRenderer]. */
@RunWith(AndroidJUnit4::class)
class VpxPlaybackTest {

  @Before
  fun setUp() {
    assertWithMessage("Vpx library not available").that(VpxLibrary.isAvailable()).isTrue()
  }

  @Test
  fun basicPlayback() =
    runBlocking(Dispatchers.Main) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val player = ExoPlayer.Builder(context).setRenderersFactory(VpxRenderersFactory()).build()
      player.setVideoSurfaceView(VideoDecoderGLSurfaceView(context))
      player.setMediaItem(MediaItem.fromUri(BEAR_URI))
      player.prepare()
      player.play()

      try {
        player.awaitPlaybackState(Player.STATE_ENDED)
      } finally {
        player.release()
      }
    }

  @Test
  fun oddDimensionsPlayback() =
    runBlocking(Dispatchers.Main) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val player = ExoPlayer.Builder(context).setRenderersFactory(VpxRenderersFactory()).build()
      player.setVideoSurfaceView(VideoDecoderGLSurfaceView(context))
      player.setMediaItem(MediaItem.fromUri(BEAR_ODD_DIMENSIONS_URI))
      player.prepare()
      player.play()

      try {
        player.awaitPlaybackState(Player.STATE_ENDED)
      } finally {
        player.release()
      }
    }

  @Test
  fun test10BitProfile2Playback() =
    runBlocking(Dispatchers.Main) {
      assumeTrue(VpxLibrary.isHighBitDepthSupported())
      val context = ApplicationProvider.getApplicationContext<Context>()
      val player = ExoPlayer.Builder(context).setRenderersFactory(VpxRenderersFactory()).build()
      player.setVideoSurfaceView(VideoDecoderGLSurfaceView(context))
      player.setMediaItem(MediaItem.fromUri(ROADTRIP_10BIT_URI))
      player.prepare()
      player.play()

      try {
        player.awaitPlaybackState(Player.STATE_ENDED)
      } finally {
        player.release()
      }
    }

  @Test
  fun invalidBitstream(): Unit =
    runBlocking(Dispatchers.Main) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val player = ExoPlayer.Builder(context).setRenderersFactory(VpxRenderersFactory()).build()
      player.setVideoSurfaceView(VideoDecoderGLSurfaceView(context))
      player.setMediaItem(MediaItem.fromUri(INVALID_BITSTREAM_URI))
      player.prepare()
      player.play()

      try {
        assertFailsWith<VpxDecoderException> { player.awaitPlaybackState(Player.STATE_ENDED) }
      } finally {
        player.release()
      }
    }

  private class VpxRenderersFactory : RenderersFactory {
    override fun createRenderers(
      eventHandler: Handler,
      videoRendererEventListener: VideoRendererEventListener,
      audioRendererEventListener: AudioRendererEventListener,
      textRendererOutput: TextOutput,
      metadataRendererOutput: MetadataOutput,
    ): Array<Renderer> {
      return arrayOf(
        LibvpxVideoRenderer(
          /* allowedJoiningTimeMs= */ 0,
          eventHandler,
          videoRendererEventListener,
          /* maxDroppedFramesToNotify= */ -1,
        )
      )
    }
  }

  companion object {
    private const val BEAR_URI = "asset:///media/vp9/bear-vp9.webm"
    private const val BEAR_ODD_DIMENSIONS_URI = "asset:///media/vp9/bear-vp9-odd-dimensions.webm"
    private const val ROADTRIP_10BIT_URI = "asset:///media/vp9/roadtrip-vp92-10bit.webm"
    private const val INVALID_BITSTREAM_URI = "asset:///media/vp9/invalid-bitstream.webm"
    private const val TAG = "VpxPlaybackTest"
  }
}
