/*
 * Copyright 2021 The Android Open Source Project
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
package androidx.media3.exoplayer.drm

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.common.MediaItem.DrmConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.test.utils.awaitPlaybackState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Instrumentation tests for DRM playback. */
@RunWith(AndroidJUnit4::class)
class DrmPlaybackTest {
  private val context: Context = InstrumentationRegistry.getInstrumentation().context

  private val mockWebServer = MockWebServer()
  private val drmConfiguration =
    DrmConfiguration.Builder(C.CLEARKEY_UUID)
      .setLicenseUri(mockWebServer.url("license").toString())
      .build()

  @Before
  fun setUpLicenseServer() {
    mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(CLEARKEY_RESPONSE))
  }

  @Test
  fun clearkeyPlayback() =
    runBlocking(Dispatchers.Main) {
      val mediaItem =
        MediaItem.Builder()
          .setUri("asset:///media/drm/sample_fragmented_clearkey.mp4")
          .setDrmConfiguration(drmConfiguration)
          .build()
      val player = ExoPlayer.Builder(context).build()
      player.setMediaItem(mediaItem)
      player.prepare()
      player.play()

      player.awaitPlaybackState(Player.STATE_ENDED)

      player.release()
    }

  @Test
  fun clearkeyPlayback_withLateThresholdToDropDecoderInput_dropsInputBuffers() =
    runBlocking(Dispatchers.Main) {
      val mediaItem =
        MediaItem.Builder()
          .setUri("asset:///media/drm/sample_fragmented_clearkey.mp4")
          .setDrmConfiguration(drmConfiguration)
          .build()
      val player =
        ExoPlayer.Builder(
            context,
            DefaultRenderersFactory(context)
              .experimentalSetLateThresholdToDropDecoderInputUs(-100000000L),
            DefaultMediaSourceFactory(context)
              .experimentalSetCodecsToParseWithinGopSampleDependencies(C.VIDEO_CODEC_FLAG_H264),
          )
          .build()

      player.setMediaItem(mediaItem)
      player.prepare()
      player.play()

      player.awaitPlaybackState(Player.STATE_ENDED)
      // Which input buffers are dropped first depends on the number of MediaCodec buffer slots.
      // This means the asserts cannot be isEqualTo.
      assertThat(player.videoDecoderCounters!!.droppedInputBufferCount).isAtLeast(1)

      player.release()
    }

  @Test
  fun clearkeyPlayback_parseAv1SampleDependencies_skipsOnlyFullFrames() =
    runBlocking(Dispatchers.Main) {
      // Only run this test if any AV1 decoder is present. Accept non-secure decoders that likely
      // produce corrupted visual output, as this test asserts only on decoder counters.
      assumeFalse(
        MediaCodecSelector.DEFAULT.getDecoderInfos(
            MimeTypes.VIDEO_AV1,
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false,
          )
          .isEmpty()
      )
      val mediaItem =
        MediaItem.Builder()
          .setUri("asset:///media/drm/sample_av1c_fragmented_clearkey.mp4")
          .setDrmConfiguration(drmConfiguration)
          .setClippingConfiguration(
            ClippingConfiguration.Builder()
              .setAllowUnseekableMedia(true)
              .setStartPositionMs(200)
              .build()
          )
          .build()
      val player =
        ExoPlayer.Builder(
            context,
            DefaultRenderersFactory(context).experimentalSetParseAv1SampleDependencies(true),
          )
          .build()
      player.setMediaItem(mediaItem)
      player.prepare()
      player.play()

      player.awaitPlaybackState(Player.STATE_ENDED)
      assertThat(player.videoDecoderCounters!!.skippedInputBufferCount).isEqualTo(3)

      player.release()
    }

  companion object {
    private const val TIMEOUT_MS = 10000L

    /** The license response needed to play the `drm/sample_fragmented_clearkey.mp4` file. */
    // NOTE: Changing this response *should* make the test fail, but it seems the clearkey CDM
    // implementation is quite robust. This means an 'invalid' response means it just incorrectly
    // decrypts the content, resulting in invalid data fed to the decoder and no video shown on the
    // screen (but no error thrown that's detectable by this test).
    private val CLEARKEY_RESPONSE =
      """
      {
        "keys": [
          {
            "kty": "oct",
            "k": "Y8tfcYTdS2iaXF_xHuajKA",
            "kid": "zX65_4jzTK6wYYWwACTkwg"
          }
        ],
        "type": "temporary"
      }
      """
        .trimIndent()
  }
}
