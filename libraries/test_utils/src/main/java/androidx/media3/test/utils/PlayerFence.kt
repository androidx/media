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

import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.transformer.CompositionPlayer
import com.google.common.util.concurrent.ListenableFuture
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.guava.future

/**
 * Suspends until the player enters the provided [targetState].
 *
 * If the player is already in the provided [targetState] this returns immediately.
 *
 * Must be called on the player's application looper thread.
 *
 * @throws IllegalStateException if not called on the player's application looper thread.
 */
@UnstableApi
suspend fun Player.awaitPlaybackState(
  targetState: @Player.State Int,
  failOnNonFatalErrors: Boolean = true,
) {
  check(Looper.myLooper() == applicationLooper) {
    "awaitPlaybackState must be called on the player's application looper thread"
  }
  playerError?.let { throw it }

  if (playbackState == targetState) {
    return
  }

  val stateSeen = CompletableDeferred<Unit>()
  val playerListener =
    object : ErrorFailingPlayerListener(stateSeen) {
      override fun onPlaybackStateChanged(playbackState: @Player.State Int) {
        if (targetState == playbackState) {
          stateSeen.complete(Unit)
        }
      }
    }

  addListener(playerListener)
  val analyticsListener =
    maybeAddAnalyticsListener(failOnNonFatalErrors) { NonFatalFailingAnalyticsListener(stateSeen) }
  try {
    stateSeen.await()
  } finally {
    removeListener(playerListener)
    maybeRemoveAnalyticsListener(analyticsListener)
  }
}

/**
 * Suspends until [Player.Listener.onRenderedFirstFrame] is invoked.
 *
 * This should be called before the callback has been invoked (e.g. before calling
 * [Player.prepare]), otherwise it risks awaiting forever.
 *
 * Must be called on the player's application looper thread.
 */
@UnstableApi
suspend fun Player.awaitFirstFrameRendered(failOnNonFatalErrors: Boolean = true) {
  check(Looper.myLooper() == applicationLooper) {
    "awaitFirstFrameRendered must be called on the player's application looper thread"
  }
  playerError?.let { throw it }

  val renderedFirstFrame = CompletableDeferred<Unit>()
  val playerListener =
    object : ErrorFailingPlayerListener(renderedFirstFrame) {
      override fun onRenderedFirstFrame() {
        renderedFirstFrame.complete(Unit)
      }
    }
  addListener(playerListener)
  val analyticsListener =
    maybeAddAnalyticsListener(failOnNonFatalErrors) {
      NonFatalFailingAnalyticsListener(renderedFirstFrame)
    }
  try {
    renderedFirstFrame.await()
  } finally {
    removeListener(playerListener)
    maybeRemoveAnalyticsListener(analyticsListener)
  }
}

/**
 * A Java-compatible fluent API for waiting for certain [Player] conditions.
 *
 * Kotlin users should strongly prefer directly calling the suspending [Player] extension functions
 * like [awaitPlaybackState] instead.
 *
 * Instances are created via [futureWhen].
 */
@UnstableApi
class PlayerFence
private constructor(private val player: Player, private val failOnNonFatalErrors: Boolean = true) {

  /**
   * Returns a new instance that will not fail the future on non-fatal errors.
   *
   * By default, futures will fail if a non-fatal error (e.g. loading error) occurs while waiting.
   */
  fun ignoringNonFatalErrors(): PlayerFence = PlayerFence(player, failOnNonFatalErrors = false)

  /**
   * Returns a future that completes when the player enters the provided [targetState].
   *
   * If the player is already in the provided [targetState], the future completes immediately.
   *
   * Must be called on the player's application looper thread.
   *
   * @throws IllegalStateException if not called on the player's application looper thread.
   */
  fun entersPlaybackState(targetState: @Player.State Int): ListenableFuture<Void?> = createFuture {
    player.awaitPlaybackState(targetState, failOnNonFatalErrors)
  }

  /**
   * Returns a future that completes when [Player.Listener.onRenderedFirstFrame] is invoked.
   *
   * Must be called on the player's application looper thread.
   */
  fun rendersFirstFrame(): ListenableFuture<Void?> = createFuture {
    player.awaitFirstFrameRendered(failOnNonFatalErrors)
  }

  private fun createFuture(block: suspend () -> Unit): ListenableFuture<Void?> {
    val looper = Looper.myLooper() ?: error("Must be called on a Looper thread")
    val dispatcher = Handler(looper).asCoroutineDispatcher("JavaFenceDispatcher")

    return CoroutineScope(dispatcher).future {
      block()
      return@future null
    }
  }

  companion object {
    /** Entry point for Java callers to get a [ListenableFuture] for a [Player] condition. */
    @JvmStatic fun futureWhen(player: Player): PlayerFence = PlayerFence(player)
  }
}

@CanIgnoreReturnValue
private fun Player.maybeAddAnalyticsListener(
  failOnNonFatalErrors: Boolean = true,
  createListenerFn: () -> AnalyticsListener,
): AnalyticsListener? {
  if (!failOnNonFatalErrors || (this !is ExoPlayer && this !is CompositionPlayer)) return null
  val analyticsListener = createListenerFn()
  when (this) {
    is ExoPlayer -> addAnalyticsListener(analyticsListener)
    is CompositionPlayer -> addAnalyticsListener(analyticsListener)
  }
  return analyticsListener
}

private fun Player.maybeRemoveAnalyticsListener(analyticsListener: AnalyticsListener?) {
  analyticsListener?.let {
    when (this) {
      is ExoPlayer -> removeAnalyticsListener(it)
      is CompositionPlayer -> removeAnalyticsListener(it)
    }
  }
}

private open class ErrorFailingPlayerListener(private val completable: CompletableDeferred<Unit>) :
  Player.Listener {
  override fun onPlayerError(error: PlaybackException) {
    completable.completeExceptionally(error)
  }
}

private class NonFatalFailingAnalyticsListener(private val completable: CompletableDeferred<*>) :
  AnalyticsListener {
  override fun onLoadError(
    eventTime: AnalyticsListener.EventTime,
    loadEventInfo: LoadEventInfo,
    mediaLoadData: MediaLoadData,
    error: IOException,
    wasCanceled: Boolean,
  ) {
    completable.completeExceptionally(error)
  }

  override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
    completable.completeExceptionally(audioSinkError)
  }

  override fun onAudioCodecError(
    eventTime: AnalyticsListener.EventTime,
    audioCodecError: Exception,
  ) {
    completable.completeExceptionally(audioCodecError)
  }

  override fun onVideoCodecError(
    eventTime: AnalyticsListener.EventTime,
    videoCodecError: Exception,
  ) {
    completable.completeExceptionally(videoCodecError)
  }

  override fun onDrmSessionManagerError(eventTime: AnalyticsListener.EventTime, error: Exception) {
    completable.completeExceptionally(error)
  }
}
