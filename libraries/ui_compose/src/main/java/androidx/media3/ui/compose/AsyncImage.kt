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
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.media3.common.ParserException
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.Log
import androidx.media3.common.util.Util
import java.io.FileInputStream
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

/** An encoded image for [AsyncImage] to load. */
internal sealed interface ImageSource {

  /** Compressed image data. Two instances are equal if their [bytes] have the same content. */
  class Bytes(val bytes: ByteArray) : ImageSource {
    override fun equals(other: Any?): Boolean = other is Bytes && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()
  }

  /** A [Uri][android.net.Uri] that points to an image. */
  data class Uri(val uri: android.net.Uri) : ImageSource
}

private const val TAG = "AsyncImage"

/**
 * Displays a bitmap loaded from [source].
 *
 * If [bitmapLoader] is not provided, it falls back to a default implementation that supports
 * reading local URIs (`content`, `android.resource`, and `file` schemes, or file paths without a
 * scheme) and image bytes.
 *
 * @param source The image to load, or `null` if there is no image.
 * @param modifier The [Modifier] to be applied to this composable.
 * @param contentDescription Text used by accessibility services to describe what this image
 *   represents.
 * @param alignment Optional alignment parameter used to place the [Painter] in the given bounds
 *   defined by the width and height.
 * @param contentScale Optional scale parameter used to determine the aspect ratio scaling to be
 *   used if the bounds are a different size from the intrinsic size of the [Painter].
 * @param alpha Optional opacity to be applied to the [Painter] when it is rendered onscreen.
 * @param colorFilter Optional [ColorFilter] to apply for the [Painter] when it is rendered
 *   onscreen.
 * @param filterQuality Sampling algorithm applied to the [bitmap][Bitmap] when it is scaled and
 *   drawn into the destination.
 * @param bitmapLoader Optional custom [BitmapLoader] to load the image. If `null`, a default loader
 *   that only supports local URIs and image bytes is used.
 * @param placeholder Optional [Painter] to display while the image is loading.
 * @param error Optional [Painter] to display if the image fails to load.
 * @param fallback Optional [Painter] to display if [source] is `null`. Defaults to [error].
 * @param keepPreviousWhileLoading Whether to keep displaying the result of the previous load (its
 *   image, [error] or [fallback]) instead of [placeholder] while a new load is in progress.
 * @param loadingContext Optional [CoroutineContext] in which the default loader reads and decodes
 *   the image, for example to run it on an application-provided [CoroutineDispatcher]. Only used if
 *   [bitmapLoader] is `null`. If it doesn't contain a [CoroutineDispatcher], the image is loaded on
 *   [Dispatchers.IO]. Changing it doesn't restart a load in progress.
 */
@Composable
internal fun AsyncImage(
  source: ImageSource?,
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
  keepPreviousWhileLoading: Boolean = false,
  loadingContext: CoroutineContext = EmptyCoroutineContext,
) {
  val appContext = LocalContext.current.applicationContext
  // Not a key of produceState, so that a new instance doesn't restart a load in progress.
  val currentLoadingContext by rememberUpdatedState(loadingContext)
  // The last load that completed, or null if no load has completed yet.
  val load by
    produceState<CompletedLoad?>(initialValue = null, source, bitmapLoader) {
      val bitmap = source?.let {
        runCatching { loadBitmap(appContext, it, bitmapLoader, currentLoadingContext) }
          .onFailure { e ->
            ensureActive()
            Log.w(TAG, "Failed to load image", e)
          }
          .getOrNull()
      }
      value = CompletedLoad(source, bitmapLoader, bitmap)
    }

  val bitmapPainter =
    remember(load?.bitmap, filterQuality) {
      load?.bitmap?.asImageBitmap()?.let {
        BitmapPainter(image = it, filterQuality = filterQuality)
      }
    }

  val displayLoad =
    when {
      source == null -> EMPTY_LOAD
      load?.source == source && load?.bitmapLoader == bitmapLoader -> load
      keepPreviousWhileLoading -> load
      else -> null
    }
  val painter =
    when {
      displayLoad == null -> placeholder
      displayLoad.source == null -> fallback
      displayLoad.bitmap == null -> error
      else -> bitmapPainter
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

// TODO: b/544627271 - Downsample the decoded bitmap to the target rendering size and apply Exif
// rotation (see BitmapUtil.decode).
private suspend fun loadBitmap(
  context: Context,
  source: ImageSource,
  bitmapLoader: BitmapLoader?,
  loadingContext: CoroutineContext,
): Bitmap =
  if (bitmapLoader != null) {
    when (source) {
      is ImageSource.Bytes -> bitmapLoader.decodeBitmap(source.bytes).await()
      is ImageSource.Uri -> bitmapLoader.loadBitmap(source.uri).await()
    }
  } else {
    when (source) {
      is ImageSource.Bytes -> defaultDecodeByteArray(source.bytes, loadingContext)
      is ImageSource.Uri -> defaultLoadUri(context, source.uri, loadingContext)
    }
  }

private suspend fun defaultDecodeByteArray(
  data: ByteArray,
  loadingContext: CoroutineContext,
): Bitmap =
  withContext(Dispatchers.IO + loadingContext) {
    checkDecoded(BitmapFactory.decodeByteArray(data, 0, data.size))
  }

// The URI may be untrusted (e.g. MediaMetadata.artworkUri set by another app's session), but its
// content is only decoded into a bitmap drawn by this composable, and is never returned to whoever
// provided the URI.
@Suppress("ContentResolverUri")
private suspend fun defaultLoadUri(
  context: Context,
  uri: Uri,
  loadingContext: CoroutineContext,
): Bitmap =
  withContext(Dispatchers.IO + loadingContext) {
    // TODO: b/575394983 - Compare schemes case-insensitively, together with DefaultDataSource.
    val scheme = uri.scheme
    val inputStream =
      when {
        // Like DefaultDataSource, treat a URI without a scheme as a file path.
        Util.isLocalFileUri(uri) ->
          FileInputStream(uri.path ?: throw IOException("Invalid file URI: $uri"))
        scheme == ContentResolver.SCHEME_CONTENT ||
          scheme == ContentResolver.SCHEME_ANDROID_RESOURCE ->
          context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open $uri")
        else -> throw IOException("Unsupported URI scheme: ${uri.scheme}")
      }
    inputStream.use { checkDecoded(BitmapFactory.decodeStream(it)) }
  }

/** Returns [bitmap], or throws if it's `null` because [BitmapFactory] couldn't decode the image. */
private fun checkDecoded(bitmap: Bitmap?): Bitmap =
  bitmap
    ?: throw ParserException.createForMalformedContainer(
      "Could not decode image data",
      /* cause= */ null,
    )

/**
 * The result of loading [source] with [bitmapLoader]. [bitmap] is `null` if [source] is `null` or
 * the load failed.
 */
private class CompletedLoad(
  val source: ImageSource?,
  val bitmapLoader: BitmapLoader?,
  val bitmap: Bitmap?,
)

/** The [CompletedLoad] displayed when there is no source to load. */
private val EMPTY_LOAD = CompletedLoad(source = null, bitmapLoader = null, bitmap = null)
