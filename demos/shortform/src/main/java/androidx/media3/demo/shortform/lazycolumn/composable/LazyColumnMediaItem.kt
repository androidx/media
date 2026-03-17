/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.demo.shortform.lazycolumn.composable

import android.view.Surface
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.shortform.lazycolumn.LazyColumnPlayerManager
import androidx.media3.demo.shortform.lazycolumn.SurfaceTextureListener

@UnstableApi
@Composable
fun LazyColumnMediaItem(
    playerManager: LazyColumnPlayerManager,
    index: Int,
    shouldPlay: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val textureView = remember { mutableStateOf(TextureView(context)) }
    val surfaceTextureListener = remember { SurfaceTextureListener() }
    val surfaceWrapper = remember { mutableStateOf<Surface?>(null) }
    val isCached = playerManager.cachedStatus[index] == true
    val showThumbnail = remember { mutableStateOf(isCached) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    playerManager.pause()
                }

                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            surfaceTextureListener.release()
            surfaceWrapper.value?.release()
            surfaceWrapper.value = null
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }


    Box(modifier) {
        AndroidView(
            factory = { textureView.value },
            update = {
                it.surfaceTextureListener = surfaceTextureListener.get(
                ) { surface ->
                    surfaceWrapper.value = surface
                }
            },
        )

        VideoThumbnail(
            id = playerManager.getUrl(index),
            index = index,
            showThumbnail = showThumbnail.value,
            playerManager,
        )
    }

    MediaViewSurface(
        surface = surfaceWrapper.value,
        shouldPlay = shouldPlay,
        playerManager = playerManager,
        onFirstFrame = {
            showThumbnail.value = false
        }
    )
}


@OptIn(UnstableApi::class)
@Composable
private fun MediaViewSurface(
    surface: Surface?,
    shouldPlay: Boolean,
    playerManager: LazyColumnPlayerManager,
    onFirstFrame: (Int) -> Unit = { },
) {
    if (shouldPlay && surface != null) {
        LaunchedEffect(surface) {
            playerManager.onFirstFrame = onFirstFrame
            playerManager.setVideoSurface(surface)
        }
    }
}