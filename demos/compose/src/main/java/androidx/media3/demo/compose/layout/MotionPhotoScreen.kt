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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.MotionPhoto
import androidx.media3.ui.compose.state.rememberMotionPhotoState

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
  DisposableEffect(lifecycleOwner, motionPhotoState) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP) {
        motionPhotoState.showPhoto = true
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .statusBarsPadding()
        .navigationBarsPadding()
  ) {
    // TODO(b/544627271): Implement the crossfade transitions in further milestones.
    MotionPhoto(
      state = motionPhotoState,
      contentDescription = mediaItem.mediaMetadata.title?.toString(),
      modifier =
        Modifier.fillMaxSize().noRippleClickable {
          motionPhotoState.showPhoto = !motionPhotoState.showPhoto
        },
      bitmapLoader = bitmapLoader,
    )
  }
}
