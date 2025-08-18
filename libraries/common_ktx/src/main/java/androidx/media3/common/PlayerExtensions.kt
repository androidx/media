/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.media3.common

import android.os.Looper
import androidx.core.os.HandlerCompat
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Continuously listens to the [Player.Listener.onEvents] callback, passing the received
 * [Player.Events] to the provided [onEvents] function.
 *
 * This function can be called from any thread. The [onEvents] function will be invoked on the
 * thread associated with [Player.getApplicationLooper].
 *
 * If, during the execution of [onEvents], an exception is thrown, the coroutine corresponding to
 * listening to the Player will be terminated. Any used resources will be cleaned up (e.g. removing
 * of the listeners) and exception will be re-thrown right after the last suspension point.
 *
 * @param onEvents The function to handle player events.
 * @return Nothing This function never returns normally. It will either continue indefinitely or
 *   terminate due to an exception or cancellation.
 */
@UnstableApi
suspend fun Player.listen(onEvents: Player.(Player.Events) -> Unit): Nothing {
  if (Looper.myLooper() == applicationLooper) {
    listenImpl(null, onEvents)
  } else {
    withContext(HandlerCompat.createAsync(applicationLooper).asCoroutineDispatcher()) {
      listenImpl(null, onEvents)
    }
  }
}

/**
 * Continuously listens to the [Player.Listener.onEvents] callback, passing the received
 * [Player.Events] to the provided [onEvents] function. A non-zero number of events has to be
 * specified (hence [firstEvent] and [otherEvents]). The order is not important.
 *
 * This function can be called from any thread. The [onEvents] function will be invoked on the
 * thread associated with [Player.getApplicationLooper].
 *
 * If, during the execution of [onEvents], an exception is thrown, the coroutine corresponding to
 * listening to the Player will be terminated. Any used resources will be cleaned up (e.g. removing
 * of the listeners) and exception will be re-thrown right after the last suspension point.
 *
 * @param firstEvent One of the [events][Player.Event] to listen to. Does not have to actually be
 *   the first one, since the order is not taken into account. This parameter is separated from
 *   [otherEvents] to avoid creating an array when only one event is needed or avoid accidentally
 *   not passing any events.
 * @param otherEvents The set of other [events][Player.Event] to listen for.
 * @param onEvents The function to handle player events.
 * @return Nothing This function never returns normally. It will either continue indefinitely or
 *   terminate due to an exception or cancellation.
 */
@UnstableApi
@SuppressWarnings("WrongConstant") // Lint complains about unpacking/spreading events
suspend fun Player.listenTo(
  firstEvent: @Player.Event Int,
  vararg otherEvents: @Player.Event Int,
  onEvents: Player.(Player.Events) -> Unit,
): Nothing {
  val playerEvents = Player.Events(FlagSet.Builder().add(firstEvent).addAll(*otherEvents).build())
  if (Looper.myLooper() == applicationLooper) {
    listenImpl(playerEvents, onEvents)
  } else {
    withContext(HandlerCompat.createAsync(applicationLooper).asCoroutineDispatcher()) {
      listenImpl(playerEvents, onEvents)
    }
  }
}

/**
 * Implements the core listening logic for [Player.Events].
 *
 * This function creates a cancellable coroutine that listens to [Player.Events] in an infinite
 * loop. The coroutine can be cancelled externally, or it can terminate if the [onEvents] lambda
 * throws an exception.
 *
 * Given that `invokeOnCancellation` block can be called at any time, we provide the thread-safety
 * guarantee by:
 * * unregistering the callback (i.e. removing the listener) in the finally block to keep on the
 *   calling context which was previously ensured to be the application thread
 * * marking the listener as cancelled using `AtomicBoolean` to ensure that this value will be
 *   visible immediately on any non-calling thread due to a memory barrier.
 *
 * A note on [kotlinx.coroutines.flow.callbackFlow] vs [suspendCancellableCoroutine]:
 *
 * Despite [kotlinx.coroutines.flow.callbackFlow] being recommended for a multi-shot API (like
 * [Player]'s), a [suspendCancellableCoroutine] is a lower-level construct that allows us to
 * overcome the limitations of [kotlinx.coroutines.flow.Flow]'s buffered dispatch. In our case, we
 * will not be waiting for a particular callback to resume the continuation (i.e. the common
 * single-shot use of [suspendCancellableCoroutine]), but rather handle incoming Events
 * indefinitely. This approach controls the timing of dispatching events to the caller more tightly
 * than [kotlinx.coroutines.flow.Flow]s. Such timing guarantees are critical for responding to
 * events with frame-perfect timing and become more relevant in the context of front-end UI
 * development (e.g. using Compose).
 */
private suspend fun Player.listenImpl(
  events: Player.Events?,
  onEvents: Player.(Player.Events) -> Unit,
): Nothing {
  lateinit var listener: PlayerListener
  try {
    suspendCancellableCoroutine { continuation ->
      listener = PlayerListener(events, onEvents, continuation)
      continuation.invokeOnCancellation { listener.isCancelled.set(true) }
      addListener(listener)
    }
  } finally {
    removeListener(listener)
  }
}

private class PlayerListener(
  private val events: Player.Events?,
  private val onEvents: Player.(Player.Events) -> Unit,
  private val continuation: CancellableContinuation<Nothing>,
) : Player.Listener {

  val isCancelled: AtomicBoolean = AtomicBoolean(false)

  override fun onEvents(player: Player, events: Player.Events) {
    try {
      if (!isCancelled.get()) {
        if (this.events == null || events.containsAny(this.events)) {
          player.onEvents(events)
        }
      }
    } catch (t: Throwable) {
      isCancelled.set(true)
      continuation.resumeWithException(t)
    }
  }
}
