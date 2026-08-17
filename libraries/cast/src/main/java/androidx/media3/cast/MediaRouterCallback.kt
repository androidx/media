/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.cast

import android.os.Looper
import androidx.core.os.HandlerCompat
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaRouter.RouteInfo
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Observes the [MediaRouter] callback events in a coroutine of the main thread.
 *
 * This method is a wrapper of [MediaRouter.observeCallbackImpl]. It provides a more convenient way
 * to observe the [MediaRouter] callback events in a coroutine of the main thread.
 *
 * @param selector The [MediaRouteSelector] to observe.
 * @param onEvent The callback function to be called when a [MediaRouter] callback event occurs.
 * @return Nothing
 */
internal suspend fun MediaRouter.observeCallback(
  selector: MediaRouteSelector,
  onEvent: () -> Unit,
): Nothing {
  if (Looper.myLooper() == Looper.getMainLooper()) {
    observeCallbackImpl(selector, onEvent)
  } else {
    withContext(HandlerCompat.createAsync(Looper.getMainLooper()).asCoroutineDispatcher()) {
      observeCallbackImpl(selector, onEvent)
    }
  }
}

/**
 * Observes the [MediaRouter] callback events in a coroutine.
 *
 * This function creates a cancellable continuation to observe the [MediaRouter] callback events in
 * an infinite loop. The coroutine can be cancelled externally, or it can terminate if the [onEvent]
 * lambda throws an exception.
 *
 * Given that `invokeOnCancellation` block can be called at any time, we provide the thread-safety
 * guarantee by:
 * * Removing the callback in the finally block.
 * * Marking the callback as cancelled using `AtomicBoolean` to ensure that this value will be
 *   visible immediately on any non-calling thread due to a memory barrier.
 *
 * @param selector The [MediaRouteSelector] to observe.
 * @param onEvent The callback function to be called when a [MediaRouter] callback event occurs.
 * @return Nothing
 */
private suspend fun MediaRouter.observeCallbackImpl(
  selector: MediaRouteSelector,
  onEvent: () -> Unit,
): Nothing {
  lateinit var callback: MediaRouterCallback
  try {
    suspendCancellableCoroutine { continuation ->
      callback = MediaRouterCallback(onEvent, continuation)
      continuation.invokeOnCancellation { callback.isCancelled.set(true) }
      addCallback(selector, callback)
    }
  } finally {
    removeCallback(callback)
  }
}

private class MediaRouterCallback(
  private val onEvent: () -> Unit,
  private val continuation: CancellableContinuation<Nothing>,
) : MediaRouter.Callback() {

  val isCancelled: AtomicBoolean = AtomicBoolean(false)

  override fun onRouteAdded(router: MediaRouter, info: RouteInfo) {
    notifyOnEvent()
  }

  override fun onRouteRemoved(router: MediaRouter, info: RouteInfo) {
    notifyOnEvent()
  }

  override fun onRouteChanged(router: MediaRouter, info: RouteInfo) {
    notifyOnEvent()
  }

  override fun onRouteSelected(
    router: MediaRouter,
    selectedRoute: RouteInfo,
    reason: Int,
    requestedRoute: RouteInfo,
  ) {
    notifyOnEvent()
  }

  override fun onRouteUnselected(router: MediaRouter, route: RouteInfo, reason: Int) {
    notifyOnEvent()
  }

  override fun onProviderAdded(router: MediaRouter, provider: MediaRouter.ProviderInfo) {
    notifyOnEvent()
  }

  override fun onProviderRemoved(router: MediaRouter, provider: MediaRouter.ProviderInfo) {
    notifyOnEvent()
  }

  override fun onProviderChanged(router: MediaRouter, provider: MediaRouter.ProviderInfo) {
    notifyOnEvent()
  }

  private fun notifyOnEvent() {
    try {
      if (!isCancelled.get()) {
        onEvent()
      }
    } catch (t: Throwable) {
      isCancelled.set(true)
      continuation.resumeWithException(t)
    }
  }
}
