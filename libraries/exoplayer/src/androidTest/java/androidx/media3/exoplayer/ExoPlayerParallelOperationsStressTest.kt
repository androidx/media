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
package androidx.media3.exoplayer

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.test.utils.awaitPlaybackState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith

/** Instrumentation stress test with many parallel operations in flight. */
@RunWith(AndroidJUnit4::class)
class ExoPlayerParallelOperationsStressTest {

  private val ITERATIONS = 50

  @Test
  fun testPlayerStress_manyParallelOperationsInFlight() =
    runBlocking(Dispatchers.Main) {
      val context = getInstrumentation().context
      val mediaItem = MediaItem.fromUri("asset:///media/mp4/sample.mp4")

      // Spawn background threads to generate CPU load when activated. This increases scheduler
      // jitter, making the execution timing more random and increasing the likelihood of
      // triggering concurrent race conditions.
      val numThreads = Runtime.getRuntime().availableProcessors() * 2
      for (j in 0 until numThreads) {
        val t = Thread(JitterLoader())
        t.isDaemon = true
        t.start()
      }

      for (i in 0 until ITERATIONS) {
        // Use default renderers to run the test in a realistic environment.
        val player = ExoPlayer.Builder(context).build()
        val exceptionRef = AtomicReference<PlaybackException>()

        player.addListener(
          object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
              exceptionRef.compareAndSet(null, error)
            }
          }
        )

        try {
          // Initial player setup.
          player.setMediaItems(listOf(mediaItem, mediaItem, mediaItem))
          player.seekToDefaultPosition(1)
          player.prepare()
          player.play()

          player.awaitPlaybackState(Player.STATE_READY)

          // Activate the Jitter load generator.
          synchronized(lock) {
            startJitter = true
            lock.notifyAll()
          }
          delay(10)

          // Phase 1: Run a batch of modifications back-to-back without delays.
          player.removeMediaItem(1)
          player.seekToDefaultPosition(1)
          player.addMediaItem(/* index= */ 0, mediaItem)
          player.moveMediaItem(/* currentIndex= */ 2, /* newIndex= */ 0)
          player.pause()
          player.play()

          // Phase 2: Run modifications with delays to test interleaved updates.
          delay(2)
          player.setPlaybackSpeed(2.0f)
          delay(2)
          player.pause()
          delay(2)
          player.play()
          delay(2)
          player.seekTo(/* mediaItemIndex= */ 0, /* positionMs= */ 500)
          delay(2)
          player.addMediaItem(/* index= */ 1, mediaItem)
          delay(2)
          player.removeMediaItem(0)
          delay(2)
          player.removeMediaItem(1)
          delay(2)
          player.stop()
          delay(2)
          player.prepare()
        } catch (e: Exception) {
          if (e is PlaybackException) {
            exceptionRef.compareAndSet(null, e)
          } else {
            exceptionRef.compareAndSet(
              null,
              ExoPlaybackException.createForUnexpected(
                RuntimeException(e),
                PlaybackException.ERROR_CODE_UNSPECIFIED,
              ),
            )
          }
        } finally {
          // Stop Jitter and release player resources in a NonCancellable context.
          withContext(NonCancellable) {
            delay(100)
            synchronized(lock) { startJitter = false }
            delay(200)
            player.release()
          }
        }

        // Fail the test if any unexpected error occurred during the iteration,
        // ignoring transient release timeouts.
        exceptionRef.get()?.let { e ->
          if (e.cause !is ExoTimeoutException) {
            throw e
          }
        }
      }
    }

  companion object {
    @Volatile var startJitter = false
    private val lock = Object()
  }

  private class JitterLoader : Runnable {
    private val array = LongArray(1)

    override fun run() {
      while (true) {
        if (!startJitter) {
          synchronized(lock) {
            while (!startJitter) {
              try {
                lock.wait()
              } catch (e: InterruptedException) {
                // Ignore.
              }
            }
          }
        }
        while (startJitter) {
          array[0]++
        }
      }
    }
  }
}
