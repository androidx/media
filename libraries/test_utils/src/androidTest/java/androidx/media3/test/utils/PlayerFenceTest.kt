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
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ElapsedRealtimeTicker
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import com.google.common.base.Stopwatch
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.android.HandlerDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Tests for `awaitXXX` suspend functions of [PlayerFence]. */
@RunWith(AndroidJUnit4::class)
class PlayerFenceTest {

  private lateinit var player: ExoPlayer

  @After
  fun releasePlayer() {
    runBlocking(Dispatchers.Main) { if (::player.isInitialized) player.release() }
  }

  @Test
  fun awaitPlaybackState_ready() =
    runBlocking(Dispatchers.Main) {
      player = ExoPlayer.Builder(getInstrumentation().context.applicationContext).build()
      player.setMediaItem(SHORT_MP3_ITEM)
      player.prepare()

      player.awaitPlaybackState(Player.STATE_READY)

      assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
    }

  @Test
  fun awaitPlaybackState_alreadyInState_returnsImmediately() =
    runBlocking(Dispatchers.Main) {
      player = ExoPlayer.Builder(getInstrumentation().context.applicationContext).build()
      player.setMediaItem(SHORT_MP3_ITEM)
      player.prepare()
      val playerReady = CompletableDeferred<Unit>()
      player.addListener(
        object : Player.Listener {
          override fun onPlaybackStateChanged(playbackState: @Player.State Int) {
            if (playbackState == Player.STATE_READY) {
              playerReady.complete(Unit)
            }
          }
        }
      )
      playerReady.await()

      assertDoesntSuspend { player.awaitPlaybackState(Player.STATE_READY) }

      assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
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
      try {
        playerReady.await()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
      } finally {
        player.release()
      }
    }

  @Test
  fun awaitPlaybackState_nonFatalError_propagatesByDefault() =
    runBlocking(Dispatchers.Main) {
      player =
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
      player =
        ExoPlayer.Builder(getInstrumentation().context.applicationContext)
          .setRenderersFactory(FailingAudioRenderer.Factory())
          .build()
      player.setMediaItem(SHORT_MP3_ITEM)
      player.prepare()

      player.awaitPlaybackState(Player.STATE_READY, failOnNonFatalErrors = false)

      assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
    }

  @Test
  fun awaitPlaybackState_defaultTimeout_timesOutAfter10s() =
    runBlocking(Dispatchers.Main) {
      val player = FakePlayer(playlist = listOf(SimpleBasePlayer.MediaItemData.Builder(0).build()))

      // Don't prepare the player, so it will never become ready.

      val stopwatch = Stopwatch.createStarted(ElapsedRealtimeTicker())
      assertFailsWith<TimeoutCancellationException> {
        player.awaitPlaybackState(Player.STATE_READY)
      }
      assertThat(stopwatch.elapsed(TimeUnit.MILLISECONDS)).isGreaterThan(9_000)
      player.release()
    }

  @Test
  fun awaitPlaybackState_customTimeout_timesOutAfterCustomTime() =
    runBlocking(Dispatchers.Main) {
      val player = FakePlayer(playlist = listOf(SimpleBasePlayer.MediaItemData.Builder(0).build()))

      // Don't prepare the player, so it will never become ready.

      val stopwatch = Stopwatch.createStarted(ElapsedRealtimeTicker())
      assertFailsWith<TimeoutCancellationException> {
        player.awaitPlaybackState(Player.STATE_READY, timeout = 100.milliseconds)
      }
      assertThat(stopwatch.elapsed(TimeUnit.MILLISECONDS)).isLessThan(200)
      player.release()
    }

  /**
   * Check that if a fatal error is thrown between registration & waiting, the wait immediately
   * fails (instead of timing out).
   */
  @Test
  fun awaitPlaybackState_fatalErrorBeforeWaiting_propagatesImmediately(): Unit =
    runBlocking(Dispatchers.Main) {
      player = ExoPlayer.Builder(getInstrumentation().context.applicationContext).build()
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

      assertDoesntSuspend {
        assertFailsWith<PlaybackException> { player.awaitPlaybackState(Player.STATE_READY) }
      }
    }

  /**
   * Check that if a test registers its own player listeners that fire on the same state change,
   * these are allowed to complete before [PlayerFence] unblocks.
   */
  @Test
  fun awaitPlaybackState_allRegisteredListenersAllowedToComplete() =
    runBlocking(Dispatchers.Main) {
      player = ExoPlayer.Builder(getInstrumentation().context.applicationContext).build()
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
      assertThat(player.playbackState).isEqualTo(Player.STATE_ENDED)
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
      try {
        player.awaitPlaybackState(Player.STATE_READY)

        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
      } finally {
        player.release()
      }
    }
    backgroundThread.quit()
  }

  @Test
  fun awaitFirstFrameRendered() =
    runBlocking(Dispatchers.Main) {
      player = ExoPlayer.Builder(getInstrumentation().context.applicationContext).build()
      val surfaceTexture = SurfaceTexture(10)
      val surface = Surface(surfaceTexture)
      player.setVideoSurface(surface)
      player.setMediaItem(MP4_ITEM)

      val firstFrameRendered = async { player.awaitFirstFrameRendered() }
      delay(500.milliseconds)
      assertThat(firstFrameRendered.isCompleted).isFalse()

      player.prepare()
      player.play()
      firstFrameRendered.await()

      player.clearVideoSurface()
      surface.release()
      surfaceTexture.release()
    }

  @Test
  fun awaitFirstFrameRendered_consumesEvent() =
    runBlocking(Dispatchers.Main) {
      player = ExoPlayer.Builder(getInstrumentation().context.applicationContext).build()
      val surfaceTexture = SurfaceTexture(10)
      val surface = Surface(surfaceTexture)
      player.setVideoSurface(surface)
      player.setMediaItem(MP4_ITEM)
      player.prepare()
      player.play()

      player.awaitFirstFrameRendered()

      val firstFrameRenderedAgain = async { player.awaitFirstFrameRendered() }
      delay(500.milliseconds)
      assertThat(firstFrameRenderedAgain.isCompleted).isFalse()

      player.seekTo(0)
      firstFrameRenderedAgain.await()

      player.clearVideoSurface()
      surface.release()
      surfaceTexture.release()
    }

  @Test
  fun awaitFirstFrameRendered_nonFatalError_propagatesByDefault() =
    runBlocking(Dispatchers.Main) {
      player =
        ExoPlayer.Builder(getInstrumentation().context.applicationContext)
          .setRenderersFactory(FailingAudioRenderer.Factory())
          .build()
      val surfaceTexture = SurfaceTexture(10)
      val surface = Surface(surfaceTexture)
      player.setVideoSurface(surface)
      player.setMediaItem(MP4_ITEM)
      player.prepare()
      player.play()

      val exception =
        assertFailsWith(IllegalStateException::class) { player.awaitFirstFrameRendered() }
      assertThat(exception).hasMessageThat().contains("FailingAudioRenderer")

      player.clearVideoSurface()
      surface.release()
      surfaceTexture.release()
    }

  @Test
  fun awaitFirstFrameRendered_nonFatalError_doesntPropagateIfDisabled() =
    runBlocking(Dispatchers.Main) {
      player =
        ExoPlayer.Builder(getInstrumentation().context.applicationContext)
          .setRenderersFactory(FailingAudioRenderer.Factory())
          .build()
      val surfaceTexture = SurfaceTexture(10)
      val surface = Surface(surfaceTexture)
      player.setVideoSurface(surface)
      player.setMediaItem(MP4_ITEM)
      player.prepare()
      player.play()

      player.awaitFirstFrameRendered(failOnNonFatalErrors = false)

      player.clearVideoSurface()
      surface.release()
      surfaceTexture.release()
    }

  @Test
  fun awaitContentPositionAtLeast() =
    runBlocking(Dispatchers.Main) {
      player = ExoPlayer.Builder(getInstrumentation().context.applicationContext).build()
      player.setMediaItem(MP3_ITEM)
      player.prepare()
      player.play()

      player.awaitContentPositionAtLeast(800)

      assertThat(player.contentPosition).isAtLeast(800)
      assertThat(player.contentPosition).isAtMost(1500)
    }

  @Test
  fun awaitContentPositionAtLeast_speedChanges() =
    runBlocking(Dispatchers.Main) {
      // We use FakeMediaSource so that speed changes are instant, to avoid the test being flaky.
      val timeline =
        FakeTimeline(TimelineWindowDefinition.Builder().setDurationUs(10_000_000).build())
      val videoFormat =
        Format.Builder()
          .setSampleMimeType(MimeTypes.VIDEO_H264)
          .setWidth(640)
          .setHeight(480)
          .build()
      val fakeMediaSource =
        FakeMediaSource.Builder().setTimeline(timeline).setFormats(videoFormat).build()

      val context = getInstrumentation().context.applicationContext
      val renderersFactory = RenderersFactory { eventHandler, videoListener, _, _, _ ->
        val clockAwareHandler = Clock.DEFAULT.createHandler(eventHandler.looper, null)
        arrayOf(FakeVideoRenderer(clockAwareHandler, videoListener))
      }
      player = ExoPlayer.Builder(context, renderersFactory).setClock(Clock.DEFAULT).build()

      player
        .createMessage { _, _ -> player.setPlaybackSpeed(2f) }
        .setLooper(player.applicationLooper)
        .setPosition(200)
        .send()
      // Do a second speed change, to check PlayerFence can handle it.
      player
        .createMessage { _, _ -> player.setPlaybackSpeed(4f) }
        .setLooper(player.applicationLooper)
        .setPosition(800)
        .send()
      player.setMediaSource(fakeMediaSource)
      player.prepare()
      player.play()

      player.awaitPlaybackState(Player.STATE_READY)
      player.awaitContentPositionAtLeast(1000)

      // Without speed monitoring, PlayerFence will delay for 1000ms of wall-clock time (calculated
      // at the start to reach 1000ms at 1x speed) and in that time playback will reach:
      // * 200ms (in 200ms wall-clock)
      // * then 800ms (in 600ms / 2 = 300ms wall-clock)
      // * leaving 500ms of wall-clock in which playback at 4x progresses another 2000ms (to 2800ms)
      //
      // So the total playback position will be 2800ms, which is after the target (when it should
      // be ~1000ms if speed monitoring is working).
      assertThat(player.contentPosition).isAtLeast(1000)
      assertThat(player.contentPosition).isAtMost(2000)
    }

  @Test
  fun awaitContentPositionAtLeast_seekPastTargetPosition() =
    runBlocking(Dispatchers.Main) {
      player = ExoPlayer.Builder(getInstrumentation().context.applicationContext).build()
      player
        .createMessage { _, _ -> player.seekTo(2000) }
        .setLooper(player.applicationLooper)
        .setPosition(200)
        .send()
      player.setMediaItem(MP3_ITEM)
      player.prepare()
      player.play()

      player.awaitContentPositionAtLeast(1500)

      assertThat(player.contentPosition).isAtLeast(1900)
      assertThat(player.contentPosition).isAtMost(2200)
    }

  @Test
  fun awaitContentPositionAtLeast_endsBeforeReachingPosition() =
    runBlocking(Dispatchers.Main) {
      player = ExoPlayer.Builder(getInstrumentation().context.applicationContext).build()
      player.setMediaItem(MP3_ITEM)
      player.prepare()
      player.play()

      val exception =
        assertFailsWith(IllegalStateException::class) { player.awaitContentPositionAtLeast(5_000) }
      assertThat(exception).hasMessageThat().contains("Playback ended at position")
      assertThat(exception).hasMessageThat().contains("before target of 5000ms")
    }

  @Test
  fun awaitContentPositionAtLeast_newMediaItemBeforeReachingPosition() =
    runBlocking(Dispatchers.Main) {
      player = ExoPlayer.Builder(getInstrumentation().context.applicationContext).build()
      player
        .createMessage { _, _ -> player.seekToNext() }
        .setLooper(player.applicationLooper)
        .setPosition(200)
        .send()
      player.setMediaItems(listOf(MP3_ITEM, SHORT_MP3_ITEM))
      player.prepare()
      player.play()

      val exception =
        assertFailsWith(IllegalStateException::class) { player.awaitContentPositionAtLeast(500) }
      assertThat(exception)
        .hasMessageThat()
        .contains("Playback left item 0 before reaching position 500m")
    }

  @Test
  fun awaitContentPositionAtLeast_nonFatalError_propagatesByDefault() =
    runBlocking(Dispatchers.Main) {
      player =
        ExoPlayer.Builder(getInstrumentation().context.applicationContext)
          .setRenderersFactory(FailingAudioRenderer.Factory())
          .build()
      player.setMediaItem(MP3_ITEM)
      player.prepare()
      player.play()

      val throwable =
        assertFailsWith(IllegalStateException::class) {
          player.awaitContentPositionAtLeast(targetPositionMs = 500)
        }
      assertThat(throwable).hasMessageThat().contains("FailingAudioRenderer")
    }

  @Test
  fun awaitContentPositionAtLeast_nonFatalError_doesntPropagateIfDisabled() =
    runBlocking(Dispatchers.Main) {
      player =
        ExoPlayer.Builder(getInstrumentation().context.applicationContext)
          .setRenderersFactory(FailingAudioRenderer.Factory())
          .build()
      player.setMediaItem(MP3_ITEM)
      player.prepare()
      player.play()

      player.awaitContentPositionAtLeast(targetPositionMs = 500, failOnNonFatalErrors = false)

      assertThat(player.contentPosition).isAtLeast(500)
    }

  private fun CoroutineScope.assertDoesntSuspend(block: suspend () -> Unit) {
    var didSuspend = true
    // Use UNDISPATCHED so that control-flow returns to the outer scope immediately if `block()`
    // suspends.
    launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        block()
      } finally {
        didSuspend = false
      }
    }
    assertWithMessage("Unexpectedly suspended").that(didSuspend).isFalse()
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
    val MP3_ITEM = MediaItem.fromUri("asset:///media/mp3/bear-id3.mp3")
    val SHORT_MP3_ITEM = MediaItem.fromUri("asset:///media/mp3/play-trimmed.mp3")
    val MP4_ITEM = MediaItem.fromUri("asset:///media/mp4/sample.mp4")
  }
}
