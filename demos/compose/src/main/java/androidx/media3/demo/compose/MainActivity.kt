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

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.demo.compose.buttons.ExtraControls
import androidx.media3.demo.compose.buttons.MinimalControls
import androidx.media3.demo.compose.data.videos
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val context = LocalContext.current
      val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
          setMediaItems(videos.map { MediaItem.fromUri(it) })
          prepare()
        }
      }
      MediaPlayerScreen(
        player = exoPlayer,
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
      )
    }
  }
}

@Composable
private fun MediaPlayerScreen(player: Player, modifier: Modifier = Modifier) {
  var showControls by remember { mutableStateOf(true) }
  Box(modifier) {
    PlayerSurface(
      player = player,
      surfaceType = SURFACE_TYPE_SURFACE_VIEW,
      modifier =
        modifier.clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = null, // to prevent the ripple from the tap
        ) {
          showControls = !showControls
        },
    )
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
