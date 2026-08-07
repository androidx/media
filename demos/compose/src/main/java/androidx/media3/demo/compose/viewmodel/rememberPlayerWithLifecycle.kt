/*
 * Copyright 2026 The Android Open Source Project
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

package androidx.media3.demo.compose.viewmodel

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Connects the [PlayerLifecycleViewModel] to the Compose UI lifecycle and automatically handles
 * initialization, playlist loading, state restoration, and release.
 *
 * See the following resources:
 * - https://developer.android.com/topic/libraries/architecture/lifecycle#onStop-and-savedState
 * - https://developer.android.com/develop/ui/views/layout/support-multi-window-mode#multi-window_mode_configuration
 * - https://developer.android.com/develop/ui/compose/layouts/adaptive/support-multi-window-mode#android_9
 *
 * @return A [State] containing the current [Player] instance.
 */
@Composable
internal fun rememberPlayerWithLifecycle(
  playerViewModel: PlayerLifecycleViewModel,
  mediaItems: List<MediaItem>,
  playlistName: String? = null,
  useCast: Boolean = false,
): State<Player?> {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val player = playerViewModel.player.collectAsState()
  val currentMediaItems by rememberUpdatedState(mediaItems)
  val currentPlaylistName by rememberUpdatedState(playlistName)
  val currentUseCast by rememberUpdatedState(useCast)

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      val activity = context.findActivity()
      if (Build.VERSION.SDK_INT > 23) {
        when (event) {
          Lifecycle.Event.ON_START -> {
            playerViewModel.initializePlayer(currentUseCast)
            playerViewModel.loadPlaylist(currentMediaItems, currentPlaylistName)
          }
          Lifecycle.Event.ON_STOP -> {
            // Ignore simple configuration changes like rotation (see android:configChanges)
            if (activity?.isChangingConfigurations != true) {
              playerViewModel.releasePlayer()
            }
          }
          else -> {}
        }
      } else {
        // Call to onStop() is not guaranteed, hence we release the Player in onPause() instead
        when (event) {
          Lifecycle.Event.ON_RESUME -> {
            playerViewModel.initializePlayer(currentUseCast)
            playerViewModel.loadPlaylist(currentMediaItems, currentPlaylistName)
          }
          Lifecycle.Event.ON_PAUSE -> {
            if (activity?.isChangingConfigurations != true) {
              playerViewModel.releasePlayer()
            }
          }
          else -> {}
        }
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  DisposableEffect(Unit) {
    // When the user navigates back (to SampleChooserScreen), release the player.
    onDispose {
      val activity = context.findActivity()
      if (activity?.isChangingConfigurations != true) {
        playerViewModel.releasePlayer()
        playerViewModel.clearSavedState()
      }
    }
  }

  LaunchedEffect(mediaItems, playlistName, useCast) {
    playerViewModel.initializePlayer(useCast)
    playerViewModel.loadPlaylist(mediaItems, playlistName)
  }

  return player
}

@Composable
internal fun rememberPlayerWithLifecycle(
  playerViewModel: PlayerLifecycleViewModel,
  mediaItem: MediaItem,
  playlistName: String? = null,
  useCast: Boolean = false,
): State<Player?> =
  rememberPlayerWithLifecycle(playerViewModel, listOf(mediaItem), playlistName, useCast)

internal fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }
