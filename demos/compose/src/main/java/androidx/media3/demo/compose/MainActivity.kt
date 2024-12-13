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
package androidx.media3.demo.compose

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.demo.compose.buttons.ExtraControls
import androidx.media3.demo.compose.buttons.MinimalControls
import androidx.media3.demo.compose.data.videos
import androidx.media3.demo.compose.layout.noRippleClickable
import androidx.media3.demo.compose.layout.scaledWithAspectRatio
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.state.rememberRenderingState

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApp()
    }
  }
}

@Composable
fun MyApp(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var player by remember { mutableStateOf<Player?>(null) }
  if (Build.VERSION.SDK_INT > 23) {
    LifecycleStartEffect(Unit) {
      player = initializePlayer(context)
      onStopOrDispose { releasePlayer(player); player = null }
    }
  } else {
    LifecycleResumeEffect(Unit) {
      player = initializePlayer(context)
      onPauseOrDispose { releasePlayer(player); player = null }
    }
  }
  player?.let {
    MediaPlayerScreen(
      player = it,
      modifier = modifier.fillMaxSize().navigationBarsPadding()
    )
  }
}

private fun initializePlayer(context: Context): Player =
  ExoPlayer.Builder(context).build().apply {
    setMediaItems(videos.map(MediaItem::fromUri))
    prepare()
  }

private fun releasePlayer(player: Player?) {
  player?.let {
    if (player.availableCommands.contains(Player.COMMAND_RELEASE)) {
      player.release()
    }
  }
}

@Composable
private fun MediaPlayerScreen(player: Player, modifier: Modifier = Modifier) {
  var showControls by remember { mutableStateOf(true) }

  val renderingState = rememberRenderingState(player)
  val scaledModifier = modifier.scaledWithAspectRatio(ContentScale.Fit, renderingState.aspectRatio)

  Box(scaledModifier) {
    PlayerSurface(
      player = player,
      surfaceType = SURFACE_TYPE_SURFACE_VIEW,
      modifier = Modifier.noRippleClickable { showControls = !showControls },
    )
    if (!renderingState.renderedFirstFrame) {
      // hide the surface that is being prepared behind a scrim
      Box(scaledModifier.background(Color.Black))
    }
    if (showControls) {
      MinimalControls(player, Modifier.align(Alignment.Center))
      ExtraControls(
        player,
        Modifier.fillMaxWidth()
          .align(Alignment.BottomCenter)
          .background(Color.Gray.copy(alpha = 0.4f)),
      )
    }
  }
}
