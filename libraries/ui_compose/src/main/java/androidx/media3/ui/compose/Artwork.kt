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

package androidx.media3.ui.compose

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope.Companion.DefaultFilterQuality
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.CurrentMediaItemState
import androidx.media3.ui.compose.text.CurrentMediaItemBox
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Displays the artwork of the current [MediaItem][androidx.media3.common.MediaItem] from the
 * [player].
 *
 * This overload is designed for custom image loading. It delegates the rendering of the artwork to
 * the provided [painter] composable function, which has access to the [CurrentMediaItemState].
 *
 * @param player The [Player] to get the current media item from.
 * @param modifier The [Modifier] to be applied to this composable.
 * @param contentDescription The content description for the artwork. Defaults to title or null.
 * @param alignment Optional scale alignment within the container.
 * @param contentScale Optional scale type for the artwork image.
 * @param alpha Optional opacity to be applied to the artwork image.
 * @param colorFilter Optional [ColorFilter] to apply for the artwork image.
 * @param painter A composable function that returns a [Painter] to display the artwork.
 */
@UnstableApi
@Composable
fun Artwork(
  player: Player?,
  modifier: Modifier = Modifier,
  contentDescription: String? = null,
  alignment: Alignment = Alignment.Center,
  contentScale: ContentScale = ContentScale.Fit,
  alpha: Float = DefaultAlpha,
  colorFilter: ColorFilter? = null,
  painter: @Composable CurrentMediaItemState.() -> Painter?,
) {
  CurrentMediaItemBox(player) {
    painter()?.let {
      Image(
        painter = it,
        contentDescription = contentDescription ?: mediaMetadata.title?.toString(),
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
        alpha = alpha,
        colorFilter = colorFilter,
      )
    } ?: Box(modifier)
  }
}

/**
 * Displays the artwork of the current [MediaItem][androidx.media3.common.MediaItem] from the
 * [player].
 *
 * This overload is designed for displaying artwork using a built-in or custom [BitmapLoader]. It
 * automatically tracks the player's current media item. If [bitmapLoader] is not provided, it falls
 * back to a default implementation that only supports reading local URIs and byte arrays.
 *
 * @param player The [Player] to get the current media item from.
 * @param modifier The [Modifier] to be applied to this composable.
 * @param contentDescription The content description for the artwork. Defaults to title or null.
 * @param alignment Optional scale alignment within the container.
 * @param contentScale Optional scale type for the artwork image.
 * @param alpha Optional opacity to be applied to the artwork image.
 * @param colorFilter Optional [ColorFilter] to apply for the artwork image.
 * @param filterQuality Optional [FilterQuality] to apply to the artwork image bitmap.
 * @param bitmapLoader Optional custom artwork loader. If `null`, defaults to reading artwork data
 *   or URI from [MediaMetadata] using internal BitmapFactory.
 * @param placeholder Optional [Painter] to display while the artwork is loading (after the 1-second
 *   delay).
 * @param error Optional [Painter] to display if the artwork loading throws an exception.
 * @param fallback Optional [Painter] to display if the media item has no artwork. Defaults to
 *   [error].
 * @param coroutineDispatcher Optional [CoroutineDispatcher] to execute background artwork loading.
 *   Defaults to [Dispatchers.IO].
 */
@UnstableApi
@Composable
fun Artwork(
  player: Player?,
  modifier: Modifier = Modifier,
  contentDescription: String? = null,
  alignment: Alignment = Alignment.Center,
  contentScale: ContentScale = ContentScale.Fit,
  alpha: Float = DefaultAlpha,
  colorFilter: ColorFilter? = null,
  filterQuality: FilterQuality = DefaultFilterQuality,
  bitmapLoader: BitmapLoader? = null,
  placeholder: Painter? = null,
  error: Painter? = null,
  fallback: Painter? = error,
  coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  CurrentMediaItemBox(player) {
    Artwork(
      metadata = mediaMetadata,
      modifier = modifier,
      contentDescription = contentDescription ?: mediaMetadata.title?.toString(),
      alignment = alignment,
      contentScale = contentScale,
      alpha = alpha,
      colorFilter = colorFilter,
      filterQuality = filterQuality,
      bitmapLoader = bitmapLoader,
      placeholder = placeholder,
      error = error,
      fallback = fallback,
      coroutineDispatcher = coroutineDispatcher,
    )
  }
}

/**
 * Displays artwork for the given [MediaMetadata].
 *
 * This overload is designed for displaying artwork from a specific [MediaMetadata] instance,
 * independent of the player's current item (e.g. for displaying artwork in a playlist). If
 * [bitmapLoader] is not provided, it falls back to a default implementation that only supports
 * reading local URIs and byte arrays.
 *
 * @param metadata The [MediaMetadata] containing artwork info.
 * @param modifier The [Modifier] to be applied to this composable.
 * @param contentDescription The content description for the artwork. Defaults to metadata title.
 * @param alignment Optional scale alignment within the container.
 * @param contentScale Optional scale type for the artwork image.
 * @param alpha Optional opacity to be applied to the artwork image.
 * @param colorFilter Optional [ColorFilter] to apply for the artwork image.
 * @param filterQuality Optional [FilterQuality] to apply to the artwork image bitmap.
 * @param bitmapLoader Optional custom artwork loader. If `null`, defaults to reading artwork data
 *   or URI from [MediaMetadata] using internal BitmapFactory.
 * @param placeholder Optional [Painter] to display while the artwork is loading (after the 1-second
 *   delay).
 * @param error Optional [Painter] to display if the artwork loading throws an exception.
 * @param fallback Optional [Painter] to display if the media item has no artwork. Defaults to
 *   [error].
 * @param coroutineDispatcher Optional [CoroutineDispatcher] to execute background artwork loading.
 *   Defaults to [Dispatchers.IO].
 */
@UnstableApi
@Composable
fun Artwork(
  metadata: MediaMetadata?,
  modifier: Modifier = Modifier,
  contentDescription: String? = metadata?.title?.toString(),
  alignment: Alignment = Alignment.Center,
  contentScale: ContentScale = ContentScale.Fit,
  alpha: Float = DefaultAlpha,
  colorFilter: ColorFilter? = null,
  filterQuality: FilterQuality = DefaultFilterQuality,
  bitmapLoader: BitmapLoader? = null,
  placeholder: Painter? = null,
  error: Painter? = null,
  fallback: Painter? = error,
  coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  val context = LocalContext.current
  var bitmapState by remember { mutableStateOf<Bitmap?>(null) }
  var artworkState by remember { mutableStateOf(ArtworkState.LOADING) }
  val currentBitmapLoader by rememberUpdatedState(bitmapLoader)

  LaunchedEffect(metadata, currentBitmapLoader, coroutineDispatcher) {
    if (metadata == null) {
      bitmapState = null
      artworkState = ArtworkState.FALLBACK
      return@LaunchedEffect
    }

    val clearJob = launch {
      delay(1000.milliseconds)
      bitmapState = null
      artworkState = ArtworkState.LOADING
    }

    val result = runCatching {
      currentBitmapLoader?.loadBitmapFromMetadata(metadata)?.await()
        ?: defaultLoadArtwork(context, metadata, coroutineDispatcher)
    }

    clearJob.cancel()
    if (result.isSuccess) {
      val loadedBitmap = result.getOrNull()
      if (loadedBitmap != null) {
        bitmapState = loadedBitmap
        artworkState = ArtworkState.SUCCESS
      } else {
        bitmapState = null
        artworkState = ArtworkState.FALLBACK
      }
    } else {
      bitmapState = null
      artworkState = ArtworkState.ERROR
    }
  }

  val painter =
    when (artworkState) {
      ArtworkState.SUCCESS ->
        bitmapState?.asImageBitmap()?.let {
          BitmapPainter(image = it, filterQuality = filterQuality)
        }
      ArtworkState.LOADING -> placeholder
      ArtworkState.ERROR -> error
      ArtworkState.FALLBACK -> fallback
    }

  painter?.let {
    Image(
      painter = it,
      contentDescription = contentDescription,
      modifier = modifier,
      alignment = alignment,
      contentScale = contentScale,
      alpha = alpha,
      colorFilter = colorFilter,
    )
  } ?: Box(modifier)
}

private enum class ArtworkState {
  LOADING,
  SUCCESS,
  ERROR,
  FALLBACK,
}

@SuppressWarnings("UNSAFE_URI")
private suspend fun defaultLoadArtwork(
  context: Context,
  metadata: MediaMetadata,
  coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
): Bitmap? =
  withContext(coroutineDispatcher) {
    runCatching {
        val data = metadata.artworkData
        if (data != null) {
          return@runCatching BitmapFactory.decodeByteArray(data, 0, data.size)
        }

        val uri = metadata.artworkUri ?: return@runCatching null
        val scheme = uri.scheme?.lowercase()
        if (
          scheme != ContentResolver.SCHEME_CONTENT &&
            scheme != ContentResolver.SCHEME_ANDROID_RESOURCE &&
            scheme != ContentResolver.SCHEME_FILE
        ) {
          return@runCatching null
        }

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
          BitmapFactory.decodeStream(inputStream)
        }
      }
      .getOrNull()
  }
