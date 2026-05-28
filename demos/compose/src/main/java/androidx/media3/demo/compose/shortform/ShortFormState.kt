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
package androidx.media3.demo.compose.shortform

import android.content.Context
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import java.io.File
import kotlin.math.abs

@MainThread
internal class ShortFormState(
  private val context: Context,
  private val mediaItems: List<MediaItem>,
  private val playerPoolCapacity: Int = 3,
  private val managedItemCount: Int = 20,
  private val itemAddRemoveCount: Int = 4,
) {
  var isReady by mutableStateOf(false)
    private set

  lateinit var preloadManager: DefaultPreloadManager
    private set

  lateinit var playerPool: PlayerPool
    private set

  private val cacheDelegate = lazy {
    val downloadDirectory = context.getExternalFilesDir(null) ?: context.filesDir
    SimpleCache(
      /* cacheDir = */ File(downloadDirectory, "precache"),
      /* evictor = */ NoOpCacheEvictor(),
      /* databaseProvider = */ StandaloneDatabaseProvider(context),
    )
  }
  private val cache: Cache by cacheDelegate

  private val currentMediaItemsAndIndexes = ArrayDeque<Pair<MediaItem, Int>>()

  private val targetPreloadStatusControl =
    object : TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
      var currentPlayingIndex = C.INDEX_UNSET

      override fun getTargetPreloadStatus(rankingData: Int) =
        when (abs(rankingData - currentPlayingIndex)) {
          1 -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(/* durationMs= */ 3000L)
          in 2..playerPoolCapacity ->
            DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(/* durationMs= */ 1000L)
          else -> DefaultPreloadManager.PreloadStatus.specifiedRangeCached(/* durationMs= */ 5000L)
        }
    }

  fun initialize() {
    if (isReady) return

    val loadControl =
      DefaultLoadControl.Builder()
        .setBufferDurationsMs(
          /* minBufferMs= */ 5_000,
          /* maxBufferMs= */ 20_000,
          /* bufferForPlaybackMs= */ 500,
          /* bufferForPlaybackAfterRebufferMs= */ DefaultLoadControl
            .DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    val preloadManagerBuilder =
      DefaultPreloadManager.Builder(context, targetPreloadStatusControl)
        .setLoadControl(loadControl)
        .setCache(cache)

    playerPool = PlayerPool(playerPoolCapacity, preloadManagerBuilder)
    preloadManager = preloadManagerBuilder.build()

    addMediaItems(
      startIndex = 0,
      count = minOf(mediaItems.size, managedItemCount),
      isAddingToEnd = true,
    )
    isReady = true
  }

  fun getMediaItem(index: Int): MediaItem =
    mediaItems[index.mod(mediaItems.size)]
      .buildUpon()
      .setMediaId(index.toString())
      .setCustomCacheKey(index.toString())
      .build()

  fun updateCurrentPage(page: Int) {
    targetPreloadStatusControl.currentPlayingIndex = page
    if (::preloadManager.isInitialized) {
      preloadManager.setCurrentPlayingIndex(page)
    }

    val firstIndex = currentMediaItemsAndIndexes.firstOrNull()?.second ?: return
    val lastIndex = currentMediaItemsAndIndexes.lastOrNull()?.second ?: return

    when {
      lastIndex - page <= 2 -> {
        addMediaItems(lastIndex + 1, itemAddRemoveCount, isAddingToEnd = true)
        removeMediaItems(itemAddRemoveCount, isRemovingFromEnd = false)
      }
      page - firstIndex <= 2 -> {
        addMediaItems(firstIndex - 1, itemAddRemoveCount, isAddingToEnd = false)
        removeMediaItems(itemAddRemoveCount, isRemovingFromEnd = true)
      }
    }
  }

  private fun addMediaItems(startIndex: Int, count: Int, isAddingToEnd: Boolean) {
    if (startIndex < 0 || !::preloadManager.isInitialized) return

    val indices =
      if (isAddingToEnd) {
        startIndex until (startIndex + count)
      } else {
        startIndex downTo maxOf(0, startIndex - count + 1)
      }

    val newItemsAndIndexes = indices.map { getMediaItem(it) to it }

    if (isAddingToEnd) {
      currentMediaItemsAndIndexes.addAll(newItemsAndIndexes)
    } else {
      newItemsAndIndexes.forEach(currentMediaItemsAndIndexes::addFirst)
    }

    preloadManager.addMediaItems(
      newItemsAndIndexes.map { it.first },
      newItemsAndIndexes.map { it.second },
    )
  }

  private fun removeMediaItems(count: Int, isRemovingFromEnd: Boolean) {
    if (!::preloadManager.isInitialized) return
    val itemsToRemoveCount = minOf(count, currentMediaItemsAndIndexes.size - managedItemCount)
    if (itemsToRemoveCount <= 0) return

    val mediaItemsToRemove =
      List(itemsToRemoveCount) {
        if (isRemovingFromEnd) {
          currentMediaItemsAndIndexes.removeLast().first
        } else {
          currentMediaItemsAndIndexes.removeFirst().first
        }
      }

    preloadManager.removeMediaItems(mediaItemsToRemove)
  }

  /**
   * Release PreloadManager and clear the PlayerPool - quick, safe, and non-blocking operations.
   * Note: Cache is not released because it requires background work, and we cannot guarantee the
   * async call will be completed during Compose teardown (in onDispose).
   */
  fun release() {
    if (!isReady) return
    if (::preloadManager.isInitialized) preloadManager.release()
    if (::playerPool.isInitialized) playerPool.destroyPlayers()
    currentMediaItemsAndIndexes.clear()
    isReady = false
  }
}

@Composable
internal fun rememberShortFormState(
  mediaItems: List<MediaItem>,
  playerPoolCapacity: Int = 3,
  managedItemCount: Int = 20,
  itemAddRemoveCount: Int = 4,
): ShortFormState {
  val context = LocalContext.current
  val state =
    remember(context, mediaItems, playerPoolCapacity, managedItemCount, itemAddRemoveCount) {
      ShortFormState(context, mediaItems, playerPoolCapacity, managedItemCount, itemAddRemoveCount)
    }
  LaunchedEffect(state) { state.initialize() }
  DisposableEffect(state) { onDispose(state::release) }
  return state
}
