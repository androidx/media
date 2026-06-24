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
package androidx.media3.cast

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaRouter.RouteInfo

/**
 * Remembers the value of [MediaRouterState] and launches a coroutine to monitor the state of the
 * [MediaRouter].
 *
 * @param context The current [Context].
 * @param selector The [MediaRouteSelector] to be used for monitoring the state of the
 *   [MediaRouter]. The selector must not be empty.
 * @return The [MediaRouterState] that is remembered and updated when the state of the [MediaRouter]
 *   changes.
 */
@Composable
internal fun rememberMediaRouterState(
  context: Context,
  selector: MediaRouteSelector,
): MediaRouterState {
  val mediaRouterState = remember(context, selector) { MediaRouterState(context, selector) }
  LaunchedEffect(mediaRouterState) { mediaRouterState.observe() }
  return mediaRouterState
}

/**
 * Monitors the state of the system [MediaRouter] for a given [MediaRouteSelector].
 *
 * This class handles the platform callback registration and exposes reactive states representing
 * the current active remote routing connection.
 */
internal class MediaRouterState(val context: Context, val selector: MediaRouteSelector) {
  val mediaRouter: MediaRouter = MediaRouter.getInstance(context)

  var isConnectedToRemote by mutableStateOf(isConnectedToRemote(mediaRouter))
    private set

  var connectionState by mutableIntStateOf(getConnectionState(mediaRouter, isConnectedToRemote))
    private set

  /**
   * Observes the [MediaRouter] callback events in a coroutine.
   *
   * The coroutine is automatically cancelled when the calling Composable leaves the composition,
   * which also removes the [MediaRouter] callback to clean up resources.
   */
  suspend fun observe(): Nothing {
    updateMediaRouteState()
    mediaRouter.observeCallback(selector) { updateMediaRouteState() }
  }

  private fun updateMediaRouteState() {
    isConnectedToRemote = isConnectedToRemote(mediaRouter)
    connectionState = getConnectionState(mediaRouter, isConnectedToRemote)
  }

  companion object {
    private fun isConnectedToRemote(mediaRouter: MediaRouter): Boolean =
      !mediaRouter.selectedRoute.isSystemRoute

    private fun getConnectionState(mediaRouter: MediaRouter, isConnectedToRemote: Boolean): Int =
      if (isConnectedToRemote) {
        mediaRouter.selectedRoute.connectionState
      } else {
        RouteInfo.CONNECTION_STATE_DISCONNECTED
      }
  }
}
