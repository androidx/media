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

package androidx.media3.ui.compose

import androidx.annotation.RestrictTo
import androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.util.BitmapLoader
import androidx.media3.ui.compose.state.MotionPhotoState

/**
 * Composable for displaying a Motion Photo using a hoisted [MotionPhotoState] and a built-in or
 * custom [BitmapLoader].
 *
 * @param state The [MotionPhotoState] governing playback coordination.
 * @param contentDescription Text used by accessibility services to describe the still photo.
 * @param modifier The [Modifier] to be applied to the layout.
 * @param contentScale The [ContentScale] strategy for the video frame and photo overlay.
 * @param bitmapLoader Optional [BitmapLoader] used to decode the still photo. If `null`, a default
 *   loader that only supports local URIs (`content`, `file`, and `android.resource` schemes) is
 *   used.
 * @param placeholder Optional [Painter] to display while the still photo is loading.
 * @param error Optional [Painter] to display if still photo loading fails.
 */
@RestrictTo(LIBRARY_GROUP)
@Composable
fun MotionPhoto(
  state: MotionPhotoState,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Fit,
  bitmapLoader: BitmapLoader? = null,
  placeholder: Painter? = ColorPainter(Color.Black),
  error: Painter? = ColorPainter(Color.Black),
) {
  MotionPhoto(state = state, modifier = modifier, contentScale = contentScale) {
    AsyncImage(
      source = ImageSource.Uri(checkNotNull(state.mediaItem.localConfiguration?.uri)),
      modifier = Modifier.fillMaxSize(),
      contentDescription = contentDescription,
      contentScale = contentScale,
      bitmapLoader = bitmapLoader,
      placeholder = placeholder,
      error = error,
    )
  }
}

/**
 * Composable for displaying a Motion Photo using a hoisted [MotionPhotoState] and a custom [photo]
 * overlay.
 *
 * @param state The [MotionPhotoState] governing playback coordination.
 * @param modifier The [Modifier] to be applied to the layout.
 * @param contentScale The [ContentScale] strategy for the video frame.
 * @param photo The photo overlay to be displayed when the still photo is visible. To align with the
 *   video frame during transitions, this composable should use the same [ContentScale] as
 *   [contentScale]. When the still photo is hidden during video playback, this composable remains
 *   in the composition with `alpha = 0f` (so accessibility services ignore it, while pointer input
 *   handlers inside it remain active).
 */
@RestrictTo(LIBRARY_GROUP)
@Composable
fun MotionPhoto(
  state: MotionPhotoState,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Fit,
  photo: @Composable () -> Unit,
) {
  val photoAlpha by
    animateFloatAsState(
      targetValue = if (state.visualState.isPhotoVisible) 1f else 0f,
      animationSpec =
        tween(
          durationMillis =
            state.visualState.transitionDurationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        ),
      label = "photoAlpha",
    )
  Box(modifier = modifier) {
    ContentFrame(player = state.player, contentScale = contentScale, artwork = null)
    Box(
      Modifier.matchParentSize().graphicsLayer { alpha = photoAlpha },
      contentAlignment = Alignment.Center,
    ) {
      photo()
    }
  }
}
