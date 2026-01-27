package androidx.media3.demo.shortform.lazycolumn.composable

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.shortform.lazycolumn.BitmapProvider
import androidx.media3.demo.shortform.lazycolumn.LazyColumnPlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
@Composable
internal fun VideoThumbnail(
    id: String,
    index: Int,
    showThumbnail: Boolean,
    lazyColumnPlayerManager: LazyColumnPlayerManager,
    thumbnailLoaded: (Boolean) -> Unit = {},
) {
    val metadataRetriever = mediaDataRetrieverProvider()
    val cachedPath = cachedPathProvider(id, index, lazyColumnPlayerManager, showThumbnail)
    val bitmapProvider = remember { BitmapProvider() }
    val cachedBitmap = remember { mutableStateOf<Bitmap?>(null) }

    AnimatedVisibility(
        visible = showThumbnail,
        enter = fadeIn(tween(0)),
        exit = fadeOut(tween(100)),
    ) {
        cachedBitmap.value?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentScale = ContentScale.FillBounds,
                contentDescription = "video_thumbnail",
                colorFilter = null,
            )
        }
    }

    LaunchedEffect(key1 = cachedPath, key2 = metadataRetriever) {
        if (metadataRetriever != null) {
            withContext(coroutineContext + Dispatchers.IO) {
                if (cachedBitmap.value == null) {
                    bitmapRetriever(bitmapProvider, cachedPath, metadataRetriever)?.run {
                        cachedBitmap.value = this
                        thumbnailLoaded(true)
                    }
                }
            }
        }
    }

    DisposableEffect(key1 = Unit) {
        onDispose {
            cachedBitmap.value?.recycle()
            cachedBitmap.value = null
            metadataRetriever?.release()
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun cachedPathProvider(
    id: String,
    index: Int,
    lazyColumnPlayerManager: LazyColumnPlayerManager,
    showThumbnail: Boolean
): List<String?>? {
    val result = remember { mutableStateOf<List<String?>?>(null) }
    val isCached = lazyColumnPlayerManager.cachedStatus[index] == true

    LaunchedEffect(showThumbnail, isCached) {
        if (showThumbnail && isCached) {
            withContext(coroutineContext + Dispatchers.IO) {
                delay(50)
                result.value = lazyColumnPlayerManager.getThumbnailPaths(id)
            }
        }
    }
    return result.value
}

@Composable
private fun mediaDataRetrieverProvider(): MediaMetadataRetriever? {
    return produceState<MediaMetadataRetriever?>(initialValue = null) {
        withContext(coroutineContext + Dispatchers.IO) { value = MediaMetadataRetriever() }
    }.value
}

@ReadOnlyComposable
private fun bitmapRetriever(
    bitmapProvider: BitmapProvider,
    cachedPath: List<String?>?,
    metadataRetriever: MediaMetadataRetriever
): Bitmap? = try {
    bitmapProvider.getBitmap(cachedPath, metadataRetriever)
} catch (_: Exception) {
    null
}
