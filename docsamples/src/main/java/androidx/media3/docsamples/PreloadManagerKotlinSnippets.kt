/*
 * Copyright 2025 The Android Open Source Project
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

package androidx.media3.docsamples

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import java.lang.Math.abs

// constants to make the code snippets work
const val currentPlayingIndex = 10

@UnstableApi
// [START android_defaultpreloadmanager_MyTargetPreloadStatusControl]
class MyTargetPreloadStatusControl(currentPlayingIndex: Int = C.INDEX_UNSET) :
  TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {

  override fun getTargetPreloadStatus(index: Int): DefaultPreloadManager.PreloadStatus {
    if (index - currentPlayingIndex == 1) { // next track
      // return a PreloadStatus that is labelled by STAGE_SPECIFIED_RANGE_LOADED and
      // suggest loading 3000ms from the default start position
      return DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(3000L)
    } else if (index - currentPlayingIndex == -1) { // previous track
      // return a PreloadStatus that is labelled by STAGE_SPECIFIED_RANGE_LOADED and
      // suggest loading 3000ms from the default start position
      return DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(3000L)
    } else if (abs(index - currentPlayingIndex) == 2) {
      // return a PreloadStatus that is labelled by STAGE_TRACKS_SELECTED
      return DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED
    } else if (abs(index - currentPlayingIndex) <= 4) {
      // return a PreloadStatus that is labelled by STAGE_SOURCE_PREPARED
      return DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED
    }
    return DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
  }
}

// [END android_defaultpreloadmanager_MyTargetPreloadStatusControl]

class PreloadManagerSnippetsKotlin {

  class PreloadSnippetsActivity : AppCompatActivity() {
    private val context = this

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)

      // [START android_defaultpreloadmanager_createPLM]
      val targetPreloadStatusControl = MyTargetPreloadStatusControl()
      val preloadManagerBuilder = DefaultPreloadManager.Builder(context, targetPreloadStatusControl)
      val preloadManager = preloadManagerBuilder.build()
      // [END android_defaultpreloadmanager_createPLM]

      val player = preloadManagerBuilder.buildExoPlayer()

      // [START android_defaultpreloadmanager_addMedia]
      val initialMediaItems = pullMediaItemsFromService(count = 20)
      for (index in 0 until initialMediaItems.size) {
        preloadManager.add(initialMediaItems.get(index), /* rankingData= */ index)
      }
      // items aren't actually loaded yet! need to call invalidate() after this
      // [END android_defaultpreloadmanager_addMedia]

      // [START android_defaultpreloadmanager_invalidate]
      preloadManager.invalidate()
      // [END android_defaultpreloadmanager_invalidate]
    }

    @OptIn(UnstableApi::class)
    private fun fetchMedia(
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

    // no-op methods to support the code snippets
    private fun pullMediaItemsFromService(count: Int): List<MediaItem> {
      return listOf()
    }
  }
}
