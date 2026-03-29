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
package androidx.media3.demo.shortform.lazycolumn

import android.content.Context
import android.view.Surface
import androidx.compose.runtime.mutableStateMapOf
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.demo.shortform.MediaItemDatabase
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadManagerListener

/**
 * Manages media playback for LazyColumn with window-based preloading and caching.
 *
 * This class implements a sliding window approach where only a subset of media items
 * are kept in memory at any time, with intelligent preloading of adjacent items
 * to ensure smooth playback transitions.
 */
@UnstableApi
class LazyColumnPlayerManager(appContext: Context) {

    companion object {
        /** Number of media items to keep in the sliding window */
        private const val MANAGED_ITEM_COUNT = 10
        /** Number of items to add/remove when adjusting the window */
        private const val ITEM_ADD_REMOVE_COUNT = 4
    }

    private val singlePlayerSetupHelper by lazy { SinglePlayerSetupHelper(appContext) }
    private val player = singlePlayerSetupHelper.player
    private val preloadManager: DefaultPreloadManager = singlePlayerSetupHelper.preloadManager
    private var targetPreloadStatusControl: LazyColumnTargetPreloadStatusControl =
        singlePlayerSetupHelper.targetPreloadStatusControl
    private val mediaItemDatabase: MediaItemDatabase = singlePlayerSetupHelper.mediaItemDatabase
    private val cache: SimpleCache = singlePlayerSetupHelper.cache
    private var currentPlayingIndex = -1
    private val currentMediaItemsAndIndexes: ArrayDeque<Pair<MediaItem, Int>> = ArrayDeque()
    var onFirstFrame: ((Int) -> Unit)? = null

    /**
     * Reactive map tracking which media items have been successfully cached.
     * Key: media item index, Value: true if cached, false otherwise
     */
    val cachedStatus = mutableStateMapOf<Int, Boolean>()

    init {
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                onFirstFrame?.invoke(currentPlayingIndex)
            }
        })

        preloadManager.addListener(object : PreloadManagerListener {
            override fun onCompleted(mediaItem: MediaItem) {
                cachedStatus[mediaItem.mediaId.toInt()] = true
                super.onCompleted(mediaItem)
            }
        })

        initializeAllItems()
    }

    private fun initializeAllItems() {
        for (i in 0 until mediaItemDatabase.size()) {
            val mediaItem = mediaItemDatabase.get(i)
            preloadManager.add(mediaItem, i)
            preloadManager.getMediaSource(mediaItem)?.let {
                player.addMediaSource(i, it)
            }
            currentMediaItemsAndIndexes.addLast(Pair(mediaItem, i))
        }
        preloadManager.invalidate()
    }

    /**
     * Retrieves cached file paths for generating video thumbnails.
     *
     * @param url The media URL to get cached paths for
     * @return List of cached file paths if available, null otherwise
     */
    fun getThumbnailPaths(url: String): List<String?>? {
        val cachedSpans = cache.getCachedSpans(url)
        val cachedPaths =
            cachedSpans
                .filter { it.isCached && (it.file?.length() ?: 0) > 0 }
                .mapNotNull { it.file?.path }
        return cachedPaths.ifEmpty { null }
    }

    fun getUrl(index: Int): String {
        return mediaItemDatabase.get(index).localConfiguration?.uri.toString()
    }

    /**
     * Updates the current playing index and manages the sliding window of media items.
     *
     * When the new index approaches the edges of the current window (within 2 items),
     * this method automatically adds new items in the direction of movement and removes
     * items from the opposite end to maintain optimal memory usage.
     *
     * @param newIndex The index of the media item to play
     */
    fun updateCurrentIndex(newIndex: Int) {
        val previousIndex = currentPlayingIndex
        currentPlayingIndex = newIndex
        if (newIndex != previousIndex) {
            if (!currentMediaItemsAndIndexes.isEmpty()) {
                val leftMostIndex = currentMediaItemsAndIndexes.first().second
                val rightMostIndex = currentMediaItemsAndIndexes.last().second

                if (rightMostIndex - newIndex <= 2) {
                    for (i in 1..ITEM_ADD_REMOVE_COUNT) {
                        addMediaItem(index = rightMostIndex + i, isAddingToRightEnd = true)
                        removeMediaItem(isRemovingFromRightEnd = false)
                    }
                }
                else if (newIndex - leftMostIndex <= 2 && leftMostIndex > 0) {
                    for (i in 1..ITEM_ADD_REMOVE_COUNT) {
                        if (leftMostIndex - i >= 0) {
                            addMediaItem(index = leftMostIndex - i, isAddingToRightEnd = false)
                            removeMediaItem(isRemovingFromRightEnd = true)
                        }
                    }
                }
            }

            player.seekTo(newIndex, C.TIME_UNSET)
            player.playWhenReady = true
            targetPreloadStatusControl.currentPlayingIndex = newIndex
            preloadManager.setCurrentPlayingIndex(newIndex)
            preloadManager.invalidate()
        }
    }

    fun play() {
        player.playWhenReady = true
    }

    fun pause() {
        player.playWhenReady = false
    }

    fun release() {
        preloadManager.release()
        player.release()
    }

    fun setVideoSurface(surface: Surface?) {
        player.setVideoSurface(null)
        player.setVideoSurface(surface)
    }

    private fun addMediaItem(index: Int, isAddingToRightEnd: Boolean) {
        if (index < 0) {
            return
        }
        val mediaItem = mediaItemDatabase.get(index)
        preloadManager.add(mediaItem, index)
        if (isAddingToRightEnd) {
            currentMediaItemsAndIndexes.addLast(Pair(mediaItem, index))
        } else {
            currentMediaItemsAndIndexes.addFirst(Pair(mediaItem, index))
        }
    }

    private fun removeMediaItem(isRemovingFromRightEnd: Boolean) {
        if (currentMediaItemsAndIndexes.size <= MANAGED_ITEM_COUNT) {
            return
        }
        val itemAndIndex = if (isRemovingFromRightEnd) {
            currentMediaItemsAndIndexes.removeLast()
        } else {
            currentMediaItemsAndIndexes.removeFirst()
        }
        preloadManager.remove(itemAndIndex.first)
    }

    fun getMediaCount(): Int {
        return mediaItemDatabase.size()
    }
}

