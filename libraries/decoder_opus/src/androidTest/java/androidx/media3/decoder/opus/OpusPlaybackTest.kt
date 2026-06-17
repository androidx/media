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
package androidx.media3.decoder.opus

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.test.utils.CapturingAudioSink
import androidx.media3.test.utils.DumpFileAsserts
import androidx.media3.test.utils.awaitPlaybackState
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import com.google.testing.junit.testparameterinjector.KotlinTestParameters.namedTestValues
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Ignore

/** Playback tests using [LibopusAudioRenderer]. */
@Ignore("Fails on gradle: b/396659095")
@RunWith(TestParameterInjector::class)
class OpusPlaybackTest {

  @Before
  fun setUp() {
    assertWithMessage("Opus library not available").that(OpusLibrary.isAvailable()).isTrue()
    assertWithMessage("Dump files were generated for x86_64")
      .that(System.getProperty("os.arch"))
      .isEqualTo("x86_64")
  }

  @Test
  fun playAndAssertAudioSinkInput(
    @TestParameter
    file: String =
      namedTestValues(
        "BEAR_OPUS" to "mka/bear-opus.mka",
        "BEAR_OPUS_NEGATIVE_GAIN" to "mka/bear-opus-negative-gain.mka",
        "OPUS_5POINT1" to "mka/opus-5.1.mka",
      )
  ) =
    runBlocking(Dispatchers.Main) {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val audioSink = CapturingAudioSink.create()
      val renderersFactory = RenderersFactory { eventHandler, _, audioRendererEventListener, _, _ ->
        arrayOf(LibopusAudioRenderer(eventHandler, audioRendererEventListener, audioSink))
      }
      val player = ExoPlayer.Builder(context).setRenderersFactory(renderersFactory).build()
      player.setMediaItem(MediaItem.fromUri("asset:///media/$file"))
      player.prepare()
      player.play()

      try {
        player.awaitPlaybackState(Player.STATE_ENDED)
      } finally {
        player.release()
      }

      DumpFileAsserts.assertOutput(context, audioSink, "audiosinkdumps/$file.audiosink.dump")
    }
}
