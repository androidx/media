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
@file:Suppress(
  "unused_parameter",
  "unused_variable",
  "unused",
  "CheckReturnValue",
  "ControlFlowWithEmptyBody",
)

package androidx.media3.docsamples.cast

import android.app.Activity
import android.app.Service
import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.MediaRouteButton
import androidx.media3.cast.MediaRouteButtonFactory
import androidx.media3.cast.MediaRouteButtonViewProvider
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.docsamples.R
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.media3.ui.compose.PlayerSurface
import androidx.mediarouter.app.MediaRouteButton
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

/** Snippets for create-castplayer.md. */
@OptIn(UnstableApi::class)
object CreateCastPlayerKt {

  private class CreatePlayerExample(val context: Context) : Service() {
    private var mediaSession: MediaSession? = null

    // [START create_player]
    override fun onCreate() {
      super.onCreate()

      val exoPlayer = ExoPlayer.Builder(context).build()
      val castPlayer = CastPlayer.Builder(context).setLocalPlayer(exoPlayer).build()

      mediaSession = MediaSession.Builder(context, castPlayer).build()
    }

    // [END create_player]

    override fun onBind(intent: android.content.Intent?) = null
  }

  // [START composable_media_button]
  @Composable
  fun PlayerComposeView(player: Player, modifier: Modifier = Modifier) {
    var controlsVisible by remember { mutableStateOf(false) }

    Box(
      modifier = modifier.clickable { controlsVisible = true },
      contentAlignment = Alignment.Center,
    ) {
      PlayerSurface(player = player, modifier = modifier)
      AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
        Box(modifier = Modifier.fillMaxSize()) {
          MediaRouteButton(modifier = Modifier.align(Alignment.TopEnd))
          PrimaryControls(player = player, modifier = Modifier.align(Alignment.Center))
        }
      }
    }
  }

  @Composable
  fun PrimaryControls(player: Player, modifier: Modifier = Modifier) {
    // ...
  }

  // [END composable_media_button]

  private class PlayerMediaButtonExample : Activity() {
    private lateinit var playerView: PlayerView
    private lateinit var mediaController: MediaController

    // [START player_media_button]
    override fun onStart() {
      super.onStart()

      playerView.player = mediaController
      playerView.setMediaRouteButtonViewProvider(MediaRouteButtonViewProvider())
    }
    // [END player_media_button]
  }

  private class ActionbarMediaButtonExample(val context: Context, val executor: Executor) :
    Activity() {
    // [START actionbar_media_button]
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
      // ...
      menuInflater.inflate(R.menu.sample_media_route_button_menu, menu)
      val menuItemFuture: ListenableFuture<MenuItem> =
        MediaRouteButtonFactory.setUpMediaRouteButton(context, menu, R.id.media_route_menu_item)
      Futures.addCallback(
        menuItemFuture,
        object : FutureCallback<MenuItem> {
          override fun onSuccess(menuItem: MenuItem?) {
            // Do something with the menu item.
          }

          override fun onFailure(t: Throwable) {
            // Handle the failure.
          }
        },
        executor,
      )
      // ...
      return true
    }
    // [END actionbar_media_button]
  }

  private class ViewMediaButtonExample(val context: Context) : Activity() {
    // [START view_media_button]
    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)

      findViewById<MediaRouteButton>(R.id.media_route_button)?.also {
        val unused = MediaRouteButtonFactory.setUpMediaRouteButton(context, it)
      }
    }
    // [END view_media_button]
  }

  private class ActivityListenerExample : Activity() {
    private lateinit var mediaController: MediaController

    // [START activity_listener]
    private val playerListener: Player.Listener =
      object : Player.Listener {
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) {
          if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL) {
            // Add UI changes for local playback.
          } else if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
            // Add UI changes for remote playback.
          }
        }
      }

    override fun onStart() {
      super.onStart()
      mediaController.addListener(playerListener)
    }

    override fun onStop() {
      super.onStop()
      mediaController.removeListener(playerListener)
    }
    // [END activity_listener]
  }
}
