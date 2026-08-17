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
package androidx.media3.decoder.iamf

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build.VERSION.SDK_INT
import androidx.core.content.getSystemService
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.IamfUtil
import androidx.media3.test.utils.CapturingAudioSink
import androidx.media3.test.utils.DumpFileAsserts
import androidx.media3.test.utils.awaitPlaybackState
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Playback tests using [IamfAudioRenderer]. */
@RunWith(AndroidJUnit4::class)
class IamfPlaybackTest {

  @Before
  fun setUp() {
    assertWithMessage("Iamf library not available").that(IamfLibrary.isAvailable()).isTrue()
    assertSpatializerNotEnabled()
  }

  @Test
  fun playIamf_producesExpectedOutput() =
    runBlocking(Dispatchers.Main) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val audioSink = CapturingAudioSink.create()
      val renderersFactory = RenderersFactory { eventHandler, _, audioRendererEventListener, _, _ ->
        arrayOf(
          IamfAudioRenderer.Builder(audioSink)
            .setEventHandlerAndListener(eventHandler, audioRendererEventListener)
            .setRequestedOutputLayout(IamfUtil.OUTPUT_LAYOUT_ITU2051_SOUND_SYSTEM_A_0_2_0)
            .build()
        )
      }
      val player = ExoPlayer.Builder(context).setRenderersFactory(renderersFactory).build()
      player.setMediaItem(MediaItem.fromUri("asset:///media/${IAMF_SAMPLE}"))
      player.prepare()
      player.play()

      try {
        player.awaitPlaybackState(Player.STATE_ENDED)
      } finally {
        player.release()
      }

      DumpFileAsserts.assertOutput(context, audioSink, "audiosinkdumps/$IAMF_SAMPLE.audiosink.dump")
    }

  private fun assertSpatializerNotEnabled() {
    // Spatializer is only available on API 32 and above.
    if (SDK_INT < 32) return
    val spatializer =
      ApplicationProvider.getApplicationContext<Context>()
        .getSystemService<AudioManager>()
        ?.spatializer ?: return
    val audioFormat =
      AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
        .build()
    assertWithMessage("Spatializer must be disabled to run this test.")
      .that(
        spatializer.immersiveAudioLevel != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE &&
          spatializer.isAvailable &&
          spatializer.isEnabled &&
          spatializer.canBeSpatialized(AudioAttributes.DEFAULT.platformAudioAttributes, audioFormat)
      )
      .isFalse()
  }

  companion object {
    private const val IAMF_SAMPLE = "mp4/sample_iamf.mp4"
  }
}
