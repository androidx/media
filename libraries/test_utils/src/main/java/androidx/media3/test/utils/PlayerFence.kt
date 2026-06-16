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
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util.isRunningOnEmulator
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.transformer.CompositionPlayer
import com.google.common.util.concurrent.ListenableFuture
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private val DEFAULT_TIMEOUT = if (isRunningOnEmulator()) 30.seconds else 10.seconds

/**
 * Suspends until the player enters the provided [targetState].
 *
 * If the player is already in the provided [targetState] this returns immediately.
 *
 * Must be called on the player's application looper thread.
 *
 * @param targetState The playback [Player.State] to await.
 * @param failOnNonFatalErrors Whether non-fatal errors (such as those from
 *   [AnalyticsListener.onAudioCodecError]) cause an exception to be thrown immediately.
 * @param timeout The max time to wait for, or `null` to use a default timeout of 10 seconds.
 * @throws IllegalStateException if not called on the player's application looper thread.
 */
@UnstableApi
suspend fun Player.awaitPlaybackState(
  targetState: @Player.State Int,
  failOnNonFatalErrors: Boolean = true,
  timeout: Duration? = null,
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
    object : ErrorFailingPlayerListener(stateSeen::completeExceptionally) {
      override fun onPlaybackStateChanged(playbackState: @Player.State Int) {
        if (targetState == playbackState) {
          stateSeen.complete(Unit)
        }
      }
    }

  addListener(playerListener)
  val analyticsListener =
    maybeAddAnalyticsListener(failOnNonFatalErrors) {
      NonFatalFailingAnalyticsListener(stateSeen::completeExceptionally)
    }
  try {
    withTimeout(timeout) { stateSeen.await() }
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
 *
 * @param failOnNonFatalErrors Whether non-fatal errors (such as those from
 *   [AnalyticsListener.onAudioCodecError]) cause an exception to be thrown immediately.
 * @param timeout The max time to wait for, or `null` to use a default timeout of 10 seconds.
 * @throws IllegalStateException if not called on the player's application looper thread.
 */
@UnstableApi
suspend fun Player.awaitFirstFrameRendered(
  failOnNonFatalErrors: Boolean = true,
  timeout: Duration? = null,
) {
  check(Looper.myLooper() == applicationLooper) {
    "awaitFirstFrameRendered must be called on the player's application looper thread"
  }
  playerError?.let { throw it }

  val renderedFirstFrame = CompletableDeferred<Unit>()
  val playerListener =
    object : ErrorFailingPlayerListener(renderedFirstFrame::completeExceptionally) {
      override fun onRenderedFirstFrame() {
        renderedFirstFrame.complete(Unit)
      }
    }
  addListener(playerListener)
  val analyticsListener =
    maybeAddAnalyticsListener(failOnNonFatalErrors) {
      NonFatalFailingAnalyticsListener(renderedFirstFrame::completeExceptionally)
    }
  try {
    withTimeout(timeout) { renderedFirstFrame.await() }
  } finally {
    removeListener(playerListener)
    maybeRemoveAnalyticsListener(analyticsListener)
  }
}

/**
 * Suspends until the content position reaches (or passes) [targetPositionMs] in the current media
 * item.
 *
 * This makes some assumptions:
 * 1. The condition completes immediately if [Player.getContentPosition] is already at least
 *    [targetPositionMs].
 * 2. Seeking past the target position satisfies the condition.
 * 3. If playback ends, or the media item changes, before the target position is reached, an
 *    exception is thrown.
 *
 * Must be called on the player's application looper thread.
 *
 * @param targetPositionMs The [Player.getContentPosition] to wait for.
 * @param failOnNonFatalErrors Whether non-fatal errors (such as those from
 *   [AnalyticsListener.onAudioCodecError]) cause an exception to be thrown immediately.
 * @param timeout The max time to wait for, or `null` to use a default timeout of 10 seconds.
 * @throws IllegalStateException if not called on the player's application looper thread.
 */
@UnstableApi
suspend fun Player.awaitContentPositionAtLeast(
  targetPositionMs: Long,
  failOnNonFatalErrors: Boolean = true,
  timeout: Duration? = null,
) {
  require(targetPositionMs != C.TIME_UNSET)
  check(Looper.myLooper() == applicationLooper) {
    "awaitContentPositionAtLeast must be called on the player's application looper thread"
  }
  playerError?.let { throw it }

  if (contentPosition >= targetPositionMs) {
    return
  }
  if (playbackState == Player.STATE_ENDED) {
    throw IllegalStateException(
      "Playback already ended at position ${contentPosition}ms, before target of ${targetPositionMs}ms"
    )
  }
  val currentMediaItemIndex = currentMediaItemIndex

  val speedChanges: Flow<Float?> = callbackFlow {
    val listener =
      object : ErrorFailingPlayerListener(::close) {
        override fun onPlaybackStateChanged(playbackState: @Player.State Int) {
          if (playbackState == Player.STATE_ENDED) {
            val contentPositionMs = contentPosition
            if (contentPositionMs >= targetPositionMs) {
              trySend(null).getOrThrow()
            } else {
              close(
                IllegalStateException(
                  "Playback ended at position " +
                    "${contentPositionMs}ms, before target of ${targetPositionMs}ms"
                )
              )
            }
          }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
          trySend(playbackParameters.speed).getOrThrow()
        }

        override fun onPositionDiscontinuity(
          oldPosition: Player.PositionInfo,
          newPosition: Player.PositionInfo,
          reason: @Player.DiscontinuityReason Int,
        ) {
          if (newPosition.mediaItemIndex != currentMediaItemIndex) {
            close(
              IllegalStateException(
                "Playback left item $currentMediaItemIndex " +
                  "before reaching position ${targetPositionMs}ms"
              )
            )
          }
          if (
            newPosition.positionMs >= targetPositionMs ||
              (oldPosition.mediaItemIndex == currentMediaItemIndex &&
                oldPosition.positionMs >= targetPositionMs)
          ) {
            trySend(null).getOrThrow()
          }
        }
      }
    addListener(listener)

    val analyticsListener =
      maybeAddAnalyticsListener(failOnNonFatalErrors) { NonFatalFailingAnalyticsListener(::close) }

    send(playbackParameters.speed)

    awaitClose {
      removeListener(listener)
      maybeRemoveAnalyticsListener(analyticsListener)
    }
  }

  val targetReachedEvents: Flow<Unit> = channelFlow {
    var delayJob: Job? = null
    speedChanges.collect { speed ->
      delayJob?.cancel()
      if (speed == null) {
        send(Unit)
        return@collect
      }
      delayJob = launch {
        while (contentPosition < targetPositionMs) {
          val wallTimeUntilPosition =
            max(10f, (targetPositionMs - contentPosition) / speed).roundToLong().milliseconds
          delay(wallTimeUntilPosition)
        }
        send(Unit)
      }
    }
  }

  withTimeout(timeout) { targetReachedEvents.first() }
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
private constructor(
  private val player: Player,
  private val failOnNonFatalErrors: Boolean = true,
  private val timeout: Duration? = null,
) {

  /**
   * Returns a new instance that will not fail the future on non-fatal errors.
   *
   * By default, futures will fail if a non-fatal error (e.g. loading error) occurs while waiting.
   */
  fun ignoringNonFatalErrors(): PlayerFence =
    PlayerFence(player, failOnNonFatalErrors = false, timeout)

  /**
   * Returns a new instance that will fail the future if the condition is not met within the
   * specified timeout.
   */
  fun withTimeoutMs(timeoutMs: Long): PlayerFence =
    PlayerFence(player, failOnNonFatalErrors, timeout = timeoutMs.milliseconds)

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
    player.awaitPlaybackState(targetState, failOnNonFatalErrors, timeout)
  }

  /**
   * Returns a future that completes when [Player.Listener.onRenderedFirstFrame] is invoked.
   *
   * Must be called on the player's application looper thread.
   */
  fun rendersFirstFrame(): ListenableFuture<Void?> = createFuture {
    player.awaitFirstFrameRendered(failOnNonFatalErrors, timeout)
  }

  /**
   * Returns a future that completes when the content position reaches (or passes)
   * [targetPositionMs] in the current media item.
   *
   * See [Player.awaitContentPositionAtLeast] for assumptions made by this condition.
   *
   * Must be called on the player's application looper thread.
   */
  fun passesContentPosition(targetPositionMs: Long): ListenableFuture<Void?> = createFuture {
    player.awaitContentPositionAtLeast(targetPositionMs, failOnNonFatalErrors, timeout)
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
    /**
     * Entry point for Java callers to get a [ListenableFuture] for a [Player] condition.
     *
     * * Futures returned from the returned `PlayerFence` will fail after a default timeout (10
     *   seconds, or 30 seconds if running on an emulator). This can be customized using
     *   [PlayerFence.withTimeoutMs].
     * * Futures returned from the returned `PlayerFence` will fail immediately when the [Player]
     *   encounters a non-fatal error (such as [AnalyticsListener.onAudioCodecError]). This can be
     *   customized using [PlayerFence.ignoringNonFatalErrors].
     */
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

private suspend fun <T> withTimeout(duration: Duration?, block: suspend CoroutineScope.() -> T): T =
  withTimeout(duration ?: DEFAULT_TIMEOUT, block)

private open class ErrorFailingPlayerListener(private val exceptionConsumer: (Exception) -> Unit) :
  Player.Listener {
  override fun onPlayerError(error: PlaybackException) {
    exceptionConsumer(error)
  }
}

private class NonFatalFailingAnalyticsListener(private val exceptionConsumer: (Exception) -> Unit) :
  AnalyticsListener {
  override fun onLoadError(
    eventTime: AnalyticsListener.EventTime,
    loadEventInfo: LoadEventInfo,
    mediaLoadData: MediaLoadData,
    error: IOException,
    wasCanceled: Boolean,
  ) {
    exceptionConsumer(error)
  }

  override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
    exceptionConsumer(audioSinkError)
  }

  override fun onAudioCodecError(
    eventTime: AnalyticsListener.EventTime,
    audioCodecError: Exception,
  ) {
    exceptionConsumer(audioCodecError)
  }

  override fun onVideoCodecError(
    eventTime: AnalyticsListener.EventTime,
    videoCodecError: Exception,
  ) {
    exceptionConsumer(videoCodecError)
  }

  override fun onDrmSessionManagerError(eventTime: AnalyticsListener.EventTime, error: Exception) {
    exceptionConsumer(error)
  }
}
