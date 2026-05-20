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
package androidx.media3.test.utils

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.common.truth.Truth.assertThat
import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.HandlerDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith

/** Tests for `awaitXXX` suspend functions of [PlayerFence]. */
@RunWith(AndroidJUnit4::class)
class PlayerFenceTest {

  @Test
  fun awaitPlaybackState_ready() =
    runBlocking(Dispatchers.Main) {
      val player = ExoPlayer.Builder(getInstrumentation().context.applicationContext).build()
      player.setMediaItem(SHORT_MP3_ITEM)

      player.prepare()

      player.awaitPlaybackState(Player.STATE_READY)
    }

  @Test
  fun awaitPlaybackState_bufferingAndReadyInSameLooperIteration_whileWaiting() =
    runBlocking(Dispatchers.Main) {
      val player = FakePlayer(playlist = listOf(SimpleBasePlayer.MediaItemData.Builder(0).build()))

      val playerBuffering = async { player.awaitPlaybackState(Player.STATE_BUFFERING) }
      yield()
      player.playbackState = Player.STATE_BUFFERING
      player.playbackState = Player.STATE_READY
      playerBuffering.await()
      val playerReady = async { player.awaitPlaybackState(Player.STATE_READY) }
      yield()
      playerReady.await()
    }

  @Test
  fun awaitPlaybackState_nonFatalError_propagatesByDefault() =
    runBlocking(Dispatchers.Main) {
      val player =
        ExoPlayer.Builder(getInstrumentation().context.applicationContext)
          .setRenderersFactory(FailingAudioRenderer.Factory())
          .build()
      player.setMediaItem(SHORT_MP3_ITEM)

      player.prepare()

      val throwable =
        assertFailsWith<IllegalStateException> { player.awaitPlaybackState(Player.STATE_READY) }

      assertThat(throwable).hasMessageThat().contains("FailingAudioRenderer")
    }

  @Test
  fun awaitPlaybackState_nonFatalError_doesntPropagateIfDisabled() =
    runBlocking(Dispatchers.Main) {
      val player =
        ExoPlayer.Builder(getInstrumentation().context.applicationContext)
          .setRenderersFactory(FailingAudioRenderer.Factory())
          .build()
      player.setMediaItem(SHORT_MP3_ITEM)
      player.prepare()

      player.awaitPlaybackState(Player.STATE_READY, failOnNonFatalErrors = false)
    }

  /**
   * Check that if a fatal error is thrown between registration & waiting, the wait immediately
   * fails (instead of timing out).
   */
  @Test
  fun awaitPlaybackState_fatalErrorBeforeWaiting_propagates() = runTest {
    withContext(Dispatchers.Main) {
      val player = ExoPlayer.Builder(getInstrumentation().context.applicationContext).build()
      player.setMediaItem(MediaItem.fromUri("file:///not/a/real/file"))
      player.prepare()
      val errorThrown = CompletableDeferred<Unit>()
      player.addListener(
        object : Player.Listener {
          override fun onPlayerError(error: PlaybackException) {
            errorThrown.complete(Unit)
          }
        }
      )
      errorThrown.await()
      val timeBeforeWaiting = testScheduler.timeSource.markNow()
      assertFailsWith<PlaybackException> { player.awaitPlaybackState(Player.STATE_READY) }
      assertThat(timeBeforeWaiting.elapsedNow().inWholeMilliseconds).isEqualTo(0)
    }
  }

  /**
   * Check that if a test registers its own player listeners that fire on the same state change,
   * these are allowed to complete before [PlayerFence] unblocks.
   */
  @Test
  fun awaitPlaybackState_allRegisteredListenersAllowedToComplete() =
    runBlocking(Dispatchers.Main) {
      val player = ExoPlayer.Builder(getInstrumentation().context.applicationContext).build()
      val playbackEndedFromListener = AtomicBoolean(false)
      player.addListener(
        object : Player.Listener {
          override fun onEvents(player: Player, events: Player.Events) {
            if (
              events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
                player.playbackState == Player.STATE_ENDED
            ) {
              playbackEndedFromListener.set(true)
            }
          }
        }
      )

      player.setMediaItem(SHORT_MP3_ITEM)
      player.prepare()
      player.play()
      player.awaitPlaybackState(Player.STATE_ENDED)
      assertThat(playbackEndedFromListener.get()).isTrue()
    }

  @Test
  fun awaitPlaybackState_nonMainThreadPlayer() {
    val backgroundThread = HandlerThread("non-main-thread-for-test").apply { start() }
    val customDispatcher: HandlerDispatcher =
      Handler(backgroundThread.looper).asCoroutineDispatcher(name = "NonMainThreadDispatcher")
    runBlocking(customDispatcher) {
      val player = ExoPlayer.Builder(getInstrumentation().context.applicationContext).build()

      player.setMediaItem(SHORT_MP3_ITEM)
      player.prepare()
      player.play()
      player.awaitPlaybackState(Player.STATE_READY)
      backgroundThread.quit()
    }
  }

  private class FailingAudioRenderer(
    val eventHandler: Handler,
    val eventListener: AudioRendererEventListener,
  ) :
    MediaCodecAudioRenderer(
      getInstrumentation().context.applicationContext,
      MediaCodecSelector.DEFAULT,
      eventHandler,
      eventListener,
    ) {

    class Factory : DefaultRenderersFactory(getInstrumentation().context.applicationContext) {
      override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: @ExtensionRendererMode Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
      ) {
        out.add(FailingAudioRenderer(eventHandler, eventListener))
      }
    }

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
      val result = super.onInputFormatChanged(formatHolder)
      eventHandler.post {
        eventListener.onAudioCodecError(IllegalStateException("FailingAudioRenderer codec error"))
      }
      eventHandler.post {
        eventListener.onAudioSinkError(IllegalStateException("FailingAudioRenderer sink error"))
      }
      return result
    }
  }

  companion object {
    val SHORT_MP3_ITEM = MediaItem.fromUri("asset:///media/mp3/play-trimmed.mp3")
  }
}
