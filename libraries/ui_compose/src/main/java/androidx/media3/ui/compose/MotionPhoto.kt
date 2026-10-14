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

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.RestrictTo
import androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.Log
import androidx.media3.ui.compose.state.MotionPhotoState
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

private const val TAG = "MotionPhoto"

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
    PhotoOverlay(
      uri = checkNotNull(state.mediaItem.localConfiguration?.uri),
      contentDescription = contentDescription,
      contentScale = contentScale,
      bitmapLoader = bitmapLoader,
      placeholder = placeholder,
      error = error,
      modifier = Modifier.fillMaxSize(),
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
  Box(modifier = modifier) {
    ContentFrame(player = state.player, contentScale = contentScale, artwork = null)
    // TODO(b/544627271): Keep the photo overlay visible until Player.Listener.onRenderedFirstFrame
    // fires to avoid a black flash, and animate the alpha crossfade.
    Box(
      Modifier.matchParentSize().graphicsLayer {
        alpha = if (state.visualState.isPhotoVisible) 1f else 0f
      },
      contentAlignment = Alignment.Center,
    ) {
      photo()
    }
  }
}

private enum class PhotoOverlayState {
  LOADING,
  SUCCESS,
  ERROR,
}

// TODO(b/544627271): Deduplicate this local URI loading logic with Artwork.kt and pass target
// rendering dimensions so bitmaps are downsampled to the display size rather than decoded at full
// resolution.
@Composable
private fun PhotoOverlay(
  uri: Uri,
  contentDescription: String?,
  contentScale: ContentScale,
  bitmapLoader: BitmapLoader?,
  placeholder: Painter?,
  error: Painter?,
  modifier: Modifier = Modifier,
) {
  val appContext = LocalContext.current.applicationContext
  var bitmapState by remember { mutableStateOf<Bitmap?>(null) }
  var imageState by remember { mutableStateOf(PhotoOverlayState.LOADING) }

  LaunchedEffect(uri, bitmapLoader) {
    bitmapState = null
    imageState = PhotoOverlayState.LOADING
    val loadedBitmap =
      try {
        if (bitmapLoader != null) {
          bitmapLoader.loadBitmap(uri).await()
        } else {
          defaultLoadPhoto(appContext, uri)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to load photo", e)
        null
      }
    if (loadedBitmap != null) {
      bitmapState = loadedBitmap
      imageState = PhotoOverlayState.SUCCESS
    } else {
      bitmapState = null
      imageState = PhotoOverlayState.ERROR
    }
  }

  val bitmapPainter =
    remember(bitmapState) { bitmapState?.asImageBitmap()?.let { BitmapPainter(image = it) } }

  val painter =
    when (imageState) {
      PhotoOverlayState.SUCCESS -> bitmapPainter
      PhotoOverlayState.LOADING -> placeholder
      PhotoOverlayState.ERROR -> error
    }

  painter?.let {
    Image(
      painter = it,
      contentDescription = contentDescription,
      contentScale = contentScale,
      modifier = modifier,
    )
  } ?: Box(modifier)
}

// The URI is provided by the host application via MediaItem.
@SuppressWarnings("ContentResolverUri")
private suspend fun defaultLoadPhoto(context: Context, uri: Uri): Bitmap {
  val scheme = uri.scheme?.lowercase()
  if (
    scheme != ContentResolver.SCHEME_CONTENT &&
      scheme != ContentResolver.SCHEME_ANDROID_RESOURCE &&
      scheme != ContentResolver.SCHEME_FILE
  ) {
    throw IllegalArgumentException(
      "Cannot load motion photo with default loader: unsupported scheme '$scheme'"
    )
  }
  return withContext(Dispatchers.IO) {
    // TODO(b/544627271): Downsample the decoded bitmap to the target rendering size and apply Exif
    // rotation (see BitmapUtil.decode).
    val inputStream =
      context.contentResolver.openInputStream(uri)
        ?: throw IOException("Unable to open input stream")
    inputStream.use { stream ->
      BitmapFactory.decodeStream(stream) ?: throw IOException("Failed to decode bitmap")
    }
  }
}
