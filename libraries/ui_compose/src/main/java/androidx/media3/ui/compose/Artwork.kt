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

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope.Companion.DefaultFilterQuality
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.CurrentMediaItemState
import androidx.media3.ui.compose.text.CurrentMediaItemBox
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

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
 * @param bitmapLoader Optional custom artwork loader. It loads [MediaMetadata.artworkData] with
 *   [BitmapLoader.decodeBitmap], or if there is no artwork data, [MediaMetadata.artworkUri] with
 *   [BitmapLoader.loadBitmap]. If `null`, a default loader that only supports artwork data and
 *   local URIs is used.
 * @param placeholder Optional [Painter] to display while the artwork is loading. When the artwork
 *   changes, the previous artwork stays visible for up to 1 second before the placeholder is shown.
 * @param error Optional [Painter] to display if the artwork fails to load.
 * @param fallback Optional [Painter] to display if the media item has no artwork. Defaults to
 *   [error].
 * @param loadingContext Optional [CoroutineContext] in which the default loader reads and decodes
 *   the artwork, for example to run it on an application-provided [CoroutineDispatcher]. Only used
 *   if [bitmapLoader] is `null`. If it doesn't contain a [CoroutineDispatcher], the artwork is
 *   loaded on [Dispatchers.IO]. Changing it doesn't restart a load in progress.
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
  loadingContext: CoroutineContext = EmptyCoroutineContext,
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
      loadingContext = loadingContext,
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
 * @param bitmapLoader Optional custom artwork loader. It loads [MediaMetadata.artworkData] with
 *   [BitmapLoader.decodeBitmap], or if there is no artwork data, [MediaMetadata.artworkUri] with
 *   [BitmapLoader.loadBitmap]. If `null`, a default loader that only supports artwork data and
 *   local URIs is used.
 * @param placeholder Optional [Painter] to display while the artwork is loading. When the artwork
 *   changes, the previous artwork stays visible for up to 1 second before the placeholder is shown.
 * @param error Optional [Painter] to display if the artwork fails to load.
 * @param fallback Optional [Painter] to display if the media item has no artwork. Defaults to
 *   [error].
 * @param loadingContext Optional [CoroutineContext] in which the default loader reads and decodes
 *   the artwork, for example to run it on an application-provided [CoroutineDispatcher]. Only used
 *   if [bitmapLoader] is `null`. If it doesn't contain a [CoroutineDispatcher], the artwork is
 *   loaded on [Dispatchers.IO]. Changing it doesn't restart a load in progress.
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
  loadingContext: CoroutineContext = EmptyCoroutineContext,
) {
  val source =
    metadata?.artworkData?.let { ImageSource.Bytes(it) }
      ?: metadata?.artworkUri?.let { ImageSource.Uri(it) }
  // Keep the previous artwork visible for up to 1 second while the new artwork loads, so that fast
  // artwork changes don't flash the placeholder.
  var keepPreviousArtwork by remember(source) { mutableStateOf(true) }
  LaunchedEffect(source) {
    delay(1.seconds)
    keepPreviousArtwork = false
  }

  AsyncImage(
    source = source,
    modifier = modifier,
    contentDescription = contentDescription,
    alignment = alignment,
    contentScale = contentScale,
    alpha = alpha,
    colorFilter = colorFilter,
    filterQuality = filterQuality,
    bitmapLoader = bitmapLoader,
    placeholder = placeholder,
    error = error,
    fallback = fallback,
    keepPreviousWhileLoading = keepPreviousArtwork,
    loadingContext = loadingContext,
  )
}
