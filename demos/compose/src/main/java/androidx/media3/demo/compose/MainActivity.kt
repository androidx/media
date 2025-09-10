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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.demo.compose.data.videos
import androidx.media3.demo.compose.layout.MainScreen
import androidx.media3.exoplayer.ExoPlayer

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { ComposeDemoApp() }
  }
}

@Composable
fun ComposeDemoApp(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  var player by remember { mutableStateOf<Player?>(null) }

  // See the following resources
  // https://developer.android.com/topic/libraries/architecture/lifecycle#onStop-and-savedState
  // https://developer.android.com/develop/ui/views/layout/support-multi-window-mode#multi-window_mode_configuration
  // https://developer.android.com/develop/ui/compose/layouts/adaptive/support-multi-window-mode#android_9

  if (Build.VERSION.SDK_INT > 23) {
    // Initialize/release in onStart()/onStop() only because in a multi-window environment multiple
    // apps can be visible at the same time. The apps that are out-of-focus are paused, but video
    // playback should continue.
    LifecycleStartEffect(Unit) {
      player = initializePlayer(context)
      onStopOrDispose {
        player?.apply { release() }
        player = null
      }
    }
  } else {
    // Call to onStop() is not guaranteed, hence we release the Player in onPause() instead
    LifecycleResumeEffect(Unit) {
      player = initializePlayer(context)
      onPauseOrDispose {
        player?.apply { release() }
        player = null
      }
    }
  }

  player?.let { MainScreen(player = it, modifier = modifier.fillMaxSize()) }
}

private fun initializePlayer(context: Context): Player =
  ExoPlayer.Builder(context).build().apply {
    setMediaItems(
      videos.mapIndexed { idx, uri ->
        MediaItem.Builder().setUri(uri).setMediaId(idx.toString()).build()
      }
    )
    prepare()
  }
