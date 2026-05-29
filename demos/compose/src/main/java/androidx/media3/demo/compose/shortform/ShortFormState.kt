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
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.exoplayer.util.EventLogger
import java.io.File
import kotlin.math.abs

@MainThread
internal class ShortFormState(
  private val context: Context,
  private val mediaItems: List<MediaItem>,
  private val playerPoolCapacity: Int = 3,
) {
  var isReady by mutableStateOf(false)
    private set

  lateinit var preloadManager: DefaultPreloadManager
    private set

  lateinit var playerPool: PlayerPool<ExoPlayer>
    private set

  private var playerCounter = 0

  private val cacheDelegate = lazy {
    val downloadDirectory = context.getExternalFilesDir(null) ?: context.filesDir
    SimpleCache(
      /* cacheDir = */ File(downloadDirectory, "precache"),
      /* evictor = */ NoOpCacheEvictor(),
      /* databaseProvider = */ StandaloneDatabaseProvider(context),
    )
  }
  private val cache: Cache by cacheDelegate

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

    playerPool =
      PlayerPool(playerPoolCapacity) {
        preloadManagerBuilder.buildExoPlayer().apply {
          playerCounter++
          addAnalyticsListener(EventLogger("player-${playerCounter}-of-$playerPoolCapacity"))
          repeatMode = Player.REPEAT_MODE_ONE
        }
      }
    preloadManager = preloadManagerBuilder.build()

    isReady = true
  }

  fun getMediaItem(index: Int): MediaItem =
    mediaItems[index.mod(mediaItems.size)]
      .buildUpon()
      .setMediaId(index.toString())
      .setCustomCacheKey(index.toString())
      .build()

  internal fun updateCurrentPage(page: Int) {
    targetPreloadStatusControl.currentPlayingIndex = page
    if (::preloadManager.isInitialized) {
      preloadManager.setCurrentPlayingIndex(page)
    }
  }

  internal fun addMediaItems(indices: IntRange) {
    if (!::preloadManager.isInitialized) return
    preloadManager.addMediaItems(indices.map(::getMediaItem), indices.toList())
  }

  internal fun removeMediaItems(indices: IntRange) {
    if (!::preloadManager.isInitialized) return
    preloadManager.removeMediaItems(indices.map(::getMediaItem))
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
    isReady = false
  }
}

@Composable
internal fun rememberShortFormState(
  mediaItems: List<MediaItem>,
  playerPoolCapacity: Int = 3,
): ShortFormState {
  val context = LocalContext.current
  val state =
    remember(context, mediaItems, playerPoolCapacity) {
      ShortFormState(context, mediaItems, playerPoolCapacity)
    }
  LaunchedEffect(state) { state.initialize() }
  DisposableEffect(state) { onDispose(state::release) }
  return state
}
