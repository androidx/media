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
package androidx.media3.demo.compose.layout

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.demo.compose.shortform.CacheManager
import androidx.media3.demo.compose.shortform.PlayerPool
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.ui.compose.material3.Player
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import kotlin.math.abs

private const val PLAYER_POOL_SIZE = 3
private const val MANAGED_ITEM_COUNT = 20
private const val ITEM_ADD_REMOVE_COUNT = 4

@Composable
internal fun ShortFormPlayerScreen(
  playlistName: String,
  mediaItems: List<MediaItem>,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current

  val targetPreloadStatusControl = remember {
    object : TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
      var currentPlayingIndex = C.INDEX_UNSET

      override fun getTargetPreloadStatus(rankingData: Int): DefaultPreloadManager.PreloadStatus {
        val distance = abs(rankingData - currentPlayingIndex)
        return if (distance <= PLAYER_POOL_SIZE) {
          DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(/* durationMs= */ 1000L)
        } else {
          DefaultPreloadManager.PreloadStatus.specifiedRangeCached(/* durationMs= */ 5000L)
        }
      }
    }
  }

  val (preloadManager, playerPool) =
    remember {
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
          .setCache(CacheManager.getDownloadCache(context))

      val pool = PlayerPool(PLAYER_POOL_SIZE, preloadManagerBuilder)
      val manager = preloadManagerBuilder.build()

      Pair(manager, pool)
    }

  val currentMediaItemsAndIndexes = remember { ArrayDeque<Pair<MediaItem, Int>>() }

  fun addMediaItems(startIndex: Int, count: Int, isAddingToRightEnd: Boolean) {
    if (startIndex < 0) return
    val indices =
      if (isAddingToRightEnd) {
        (startIndex until startIndex + count).toList()
      } else {
        (startIndex downTo maxOf(0, startIndex - count + 1)).toList()
      }

    val newItemsAndIndexes = indices.map { index -> getMediaItem(mediaItems, index) to index }

    if (isAddingToRightEnd) {
      currentMediaItemsAndIndexes.addAll(newItemsAndIndexes)
    } else {
      newItemsAndIndexes.forEach { currentMediaItemsAndIndexes.addFirst(it) }
    }

    val mediaItems = newItemsAndIndexes.map { it.first }
    val rankingDataList = newItemsAndIndexes.map { it.second }

    preloadManager.addMediaItems(mediaItems, rankingDataList)
  }

  fun removeMediaItems(count: Int, isRemovingFromRightEnd: Boolean) {
    val overLimit = currentMediaItemsAndIndexes.size - MANAGED_ITEM_COUNT
    val itemsToRemoveCount = minOf(count, overLimit)

    if (itemsToRemoveCount <= 0) return

    val mediaItemsToRemove =
      (0 until itemsToRemoveCount).map {
        if (isRemovingFromRightEnd) {
          currentMediaItemsAndIndexes.removeLast().first
        } else {
          currentMediaItemsAndIndexes.removeFirst().first
        }
      }

    preloadManager.removeMediaItems(mediaItemsToRemove)
  }

  DisposableEffect(Unit) {
    val initialCount = minOf(mediaItems.size, MANAGED_ITEM_COUNT)
    addMediaItems(startIndex = 0, count = initialCount, isAddingToRightEnd = true)

    onDispose {
      preloadManager.release()
      playerPool.destroyPlayers()
    }
  }

  val pagerState = rememberPagerState { Int.MAX_VALUE }

  LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.settledPage }
      .collect { page ->
        targetPreloadStatusControl.currentPlayingIndex = page
        preloadManager.setCurrentPlayingIndex(page)

        if (currentMediaItemsAndIndexes.isNotEmpty()) {
          val leftMostIndex = currentMediaItemsAndIndexes.first().second
          val rightMostIndex = currentMediaItemsAndIndexes.last().second

          if (rightMostIndex - page <= 2) {
            // Approaching the rightmost item
            addMediaItems(
              startIndex = rightMostIndex + 1,
              count = ITEM_ADD_REMOVE_COUNT,
              isAddingToRightEnd = true,
            )
            removeMediaItems(count = ITEM_ADD_REMOVE_COUNT, isRemovingFromRightEnd = false)
          } else if (page - leftMostIndex <= 2) {
            // Approaching the leftmost item
            addMediaItems(
              startIndex = leftMostIndex - 1,
              count = ITEM_ADD_REMOVE_COUNT,
              isAddingToRightEnd = false,
            )
            removeMediaItems(count = ITEM_ADD_REMOVE_COUNT, isRemovingFromRightEnd = true)
          }
        }
      }
  }

  VerticalPager(state = pagerState, modifier = modifier.fillMaxSize()) { page ->
    ShortFormPlayer(
      mediaItem = getMediaItem(mediaItems, page),
      pageIndex = page,
      playerPool = playerPool,
      preloadManager = preloadManager,
      isPageActive = page == pagerState.settledPage,
      modifier = Modifier.fillMaxSize(),
    )
  }
}

@androidx.annotation.OptIn(ExperimentalApi::class)
@Composable
internal fun ShortFormPlayer(
  mediaItem: MediaItem,
  pageIndex: Int,
  playerPool: PlayerPool,
  preloadManager: DefaultPreloadManager,
  isPageActive: Boolean,
  modifier: Modifier = Modifier,
) {
  var player: ExoPlayer? by remember { mutableStateOf(null) }
  val lifecycleOwner = LocalLifecycleOwner.current

  DisposableEffect(key1 = lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_START && isPageActive) {
        player?.let { playerPool.play(it) } // Updated to use the pool
      } else if (event == Lifecycle.Event.ON_STOP) {
        player?.pause()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  DisposableEffect(Unit) {
    playerPool.acquirePlayer(pageIndex) { acquiredPlayer ->
      player = acquiredPlayer
      val mediaSource =
        preloadManager.getMediaSource(mediaItem)
          ?: run {
            preloadManager.add(mediaItem, pageIndex)
            preloadManager.getMediaSource(mediaItem)!!
          }
      acquiredPlayer.setMediaSource(mediaSource)
      acquiredPlayer.prepare()
      if (isPageActive) {
        playerPool.play(acquiredPlayer)
      }
    }
    onDispose { playerPool.releasePlayer(pageIndex, player) }
  }

  LaunchedEffect(isPageActive, player) {
    if (isPageActive) {
      player?.let { playerPool.play(it) }
    } else {
      player?.pause()
    }
  }

  val playPauseButtonState = rememberPlayPauseButtonState(player)
  Player(
    player = player,
    showControls = false,
    contentScale = ContentScale.Crop,
    modifier = modifier.noRippleClickable(playPauseButtonState::onClick),
  )
}

fun getMediaItem(mediaItems: List<MediaItem>, index: Int): MediaItem {
  val originalItem = mediaItems[index.mod(mediaItems.size)]
  return originalItem
    .buildUpon()
    .setMediaId(index.toString())
    .setCustomCacheKey(index.toString())
    .build()
}
