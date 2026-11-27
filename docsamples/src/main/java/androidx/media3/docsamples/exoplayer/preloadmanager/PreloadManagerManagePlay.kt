/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("unused_parameter", "unused_variable", "unused", "CheckReturnValue")

package androidx.media3.docsamples.exoplayer.preloadmanager

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager

// Snippets for PreloadManager manage and play page.

object PreloadManagerManagePlayKt {

  private fun pullMediaItemsFromService(count: Int): List<MediaItem> {
    return listOf()
  }

  @OptIn(UnstableApi::class)
  private fun addMedia(preloadManager: DefaultPreloadManager) {
    // [START android_defaultpreloadmanager_addMedia]
    val initialMediaItems = pullMediaItemsFromService(count = 20)
    for (index in 0 until initialMediaItems.size) {
      preloadManager.add(initialMediaItems[index], /* rankingData= */ index)
    }
    // items aren't actually loaded yet! need to call invalidate() after this
    // [END android_defaultpreloadmanager_addMedia]
  }

  @OptIn(UnstableApi::class)
  private fun invalidate(preloadManager: DefaultPreloadManager) {
    // [START android_defaultpreloadmanager_invalidate]
    preloadManager.invalidate()
    // [END android_defaultpreloadmanager_invalidate]
  }

  @OptIn(UnstableApi::class)
  private fun getAndPlayMedia(
    preloadManager: DefaultPreloadManager,
    mediaItem: MediaItem,
    player: ExoPlayer,
    currentIndex: Int,
  ) {
    // [START android_defaultpreloadmanager_getAndPlayMedia]
    // When a media item is about to display on the screen
    val mediaSource = preloadManager.getMediaSource(mediaItem)
    if (mediaSource != null) {
      player.setMediaSource(mediaSource)
    } else {
      // If mediaSource is null, that mediaItem hasn't been added to the preload manager
      // yet. So, send it directly to the player when it's about to play
      player.setMediaItem(mediaItem)
    }
    player.prepare()

    // When the media item is displaying at the center of the screen
    player.play()
    preloadManager.setCurrentPlayingIndex(currentIndex)

    // Need to call invalidate() to update the priorities
    preloadManager.invalidate()
    // [END android_defaultpreloadmanager_getAndPlayMedia]
  }

  @OptIn(UnstableApi::class)
  private fun removeMedia(mediaItem: MediaItem, preloadManager: DefaultPreloadManager) {
    // [START android_defaultpreloadmanager_removeItem]
    preloadManager.remove(mediaItem)
    // [END android_defaultpreloadmanager_removeItem]
  }

  @OptIn(UnstableApi::class)
  private fun releasePLM(preloadManager: DefaultPreloadManager) {
    // [START android_defaultpreloadmanager_releasePLM]
    preloadManager.release()
    // [END android_defaultpreloadmanager_releasePLM]
  }
}
