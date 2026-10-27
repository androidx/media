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

package androidx.media3.demo.compose.layout

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.MotionPhoto
import androidx.media3.ui.compose.state.MotionPhotoConfig
import androidx.media3.ui.compose.state.rememberMotionPhotoState

private val PRESETS =
  listOf("Default" to MotionPhotoConfig.DEFAULT, "Loop" to MotionPhotoConfig.LOOP)

private enum class ConfigMode {
  PRESETS,
  CUSTOM,
}

/**
 * Screen for displaying and interacting with a Motion Photo in the Compose demo.
 *
 * @param mediaItem The [MediaItem] representing the Motion Photo.
 * @param modifier The [Modifier] to be applied to the layout.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun MotionPhotoScreen(mediaItem: MediaItem, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val player = remember(context) { ExoPlayer.Builder(context).build() }
  DisposableEffect(player) { onDispose { player.release() } }

  val appContext = context.applicationContext
  val bitmapLoader = remember(appContext) { DataSourceBitmapLoader.Builder(appContext).build() }
  val motionPhotoState = rememberMotionPhotoState(player = player, mediaItem = mediaItem)

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, motionPhotoState, player) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_START) {
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
          player.prepare()
        }
      } else if (event == Lifecycle.Event.ON_STOP) {
        motionPhotoState.showPhoto = true
        player.stop()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  var configMode by remember(motionPhotoState) { mutableStateOf<ConfigMode?>(null) }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .statusBarsPadding()
        .navigationBarsPadding()
  ) {
    MotionPhoto(
      state = motionPhotoState,
      contentDescription = mediaItem.mediaMetadata.title?.toString(),
      modifier =
        Modifier.fillMaxSize().noRippleClickable {
          motionPhotoState.showPhoto = !motionPhotoState.showPhoto
        },
      bitmapLoader = bitmapLoader,
    )

    Column(
      modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      when (configMode) {
        ConfigMode.PRESETS -> {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, config) in PRESETS) {
              FilterChip(
                selected = motionPhotoState.config == config,
                onClick = { motionPhotoState.config = config },
                label = { Text(label) },
              )
            }
          }
        }
        ConfigMode.CUSTOM -> {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
              selected = motionPhotoState.config.isLooping,
              onClick = {
                motionPhotoState.config =
                  motionPhotoState.config.copy(isLooping = !motionPhotoState.config.isLooping)
              },
              label = { Text("Loop motion") },
            )
            FilterChip(
              selected = motionPhotoState.config.resumeVideoFromPreviousPosition,
              onClick = {
                motionPhotoState.config =
                  motionPhotoState.config.copy(
                    resumeVideoFromPreviousPosition =
                      !motionPhotoState.config.resumeVideoFromPreviousPosition
                  )
              },
              label = { Text("Resume from paused motion") },
            )
          }
        }
        null -> {}
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
          selected = configMode == ConfigMode.PRESETS,
          onClick = {
            if (configMode != ConfigMode.PRESETS) {
              configMode = ConfigMode.PRESETS
              motionPhotoState.config = MotionPhotoConfig.DEFAULT
            }
          },
          label = { Text("Presets") },
        )
        FilterChip(
          selected = configMode == ConfigMode.CUSTOM,
          onClick = {
            if (configMode != ConfigMode.CUSTOM) {
              configMode = ConfigMode.CUSTOM
              motionPhotoState.config = MotionPhotoConfig.DEFAULT
            }
          },
          label = { Text("Custom") },
        )
      }
    }
  }
}
