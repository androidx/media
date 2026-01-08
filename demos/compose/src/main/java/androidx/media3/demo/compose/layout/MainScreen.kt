/*
 * Copyright 2025 The Android Open Source Project
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

package androidx.media3.demo.compose.layout

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

@Composable
fun MainScreen(mediaItems: List<MediaItem>, modifier: Modifier = Modifier) {
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
      player = initializePlayer(context, mediaItems)
      onStopOrDispose {
        player?.apply { release() }
        player = null
      }
    }
  } else {
    // Call to onStop() is not guaranteed, hence we release the Player in onPause() instead
    LifecycleResumeEffect(Unit) {
      player = initializePlayer(context, mediaItems)
      onPauseOrDispose {
        player?.apply { release() }
        player = null
      }
    }
  }

  player?.let { MainScreen(player = it, modifier = modifier.fillMaxSize()) }
}

@Composable
internal fun MainScreen(player: Player, modifier: Modifier = Modifier) {
  var currentContentScaleIndex by remember { mutableIntStateOf(0) }
  var keepContentOnReset by remember { mutableStateOf(false) } // Shutter is on by default

  Box(modifier) {
    AutoHidingPlayerBox(
      player = player,
      contentScale = CONTENT_SCALES[currentContentScaleIndex].second,
      keepContentOnReset = keepContentOnReset,
      controlsTimeoutMs = CONTROLS_VISIBILITY_TIMEOUT_MS,
    )
    ContentScaleButton(
      currentContentScaleIndex,
      Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
      onClick = { currentContentScaleIndex = currentContentScaleIndex.inc() % CONTENT_SCALES.size },
    )
    ShutterToggleButton(
      keepContentOnReset,
      Modifier.align(Alignment.TopEnd).padding(top = 48.dp),
      onClick = { keepContentOnReset = !keepContentOnReset },
    )
  }
}

@Composable
private fun ShutterToggleButton(
  keepContentOnReset: Boolean,
  modifier: Modifier,
  onClick: () -> Unit,
) {
  Button(onClick, modifier) { Text("Shutter ${if (keepContentOnReset) "OFF" else "ON"}") }
}

@Composable
private fun ContentScaleButton(
  currentContentScaleIndex: Int,
  modifier: Modifier,
  onClick: () -> Unit,
) {
  Button(onClick, modifier) {
    Text("ContentScale is ${CONTENT_SCALES[currentContentScaleIndex].first}")
  }
}

private fun initializePlayer(context: Context, mediaItems: List<MediaItem>): Player =
  ExoPlayer.Builder(context).build().apply {
    setMediaItems(mediaItems)
    prepare()
  }

private const val CONTROLS_VISIBILITY_TIMEOUT_MS = 3000L
