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
package androidx.media3.demo.shortform.viewpager

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.shortform.DemoUtil
import androidx.media3.demo.shortform.MediaItemDatabase
import androidx.media3.demo.shortform.PlayerPool
import androidx.media3.demo.shortform.R
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

@OptIn(UnstableApi::class)
class ViewPagerMediaAdapter(
  private val mediaItemDatabase: MediaItemDatabase,
  numberOfPlayers: Int,
  context: Context,
) : RecyclerView.Adapter<ViewPagerMediaHolder>() {
  private val preloadManager: DefaultPreloadManager
  private val currentMediaItemsAndIndexes: ArrayDeque<Pair<MediaItem, Int>> = ArrayDeque()
  private var playerPool: PlayerPool
  private val holderMap: MutableMap<Int, ViewPagerMediaHolder>
  private val targetPreloadStatusControl: DefaultTargetPreloadStatusControl

  companion object {
    private const val TAG = "ViewPagerMediaAdapter"
    private const val LOAD_CONTROL_MIN_BUFFER_MS = 5_000
    private const val LOAD_CONTROL_MAX_BUFFER_MS = 20_000
    private const val LOAD_CONTROL_BUFFER_FOR_PLAYBACK_MS = 500
    private const val MANAGED_ITEM_COUNT = 20
    private const val ITEM_ADD_REMOVE_COUNT = 4
  }

  init {
    val loadControl =
      DefaultLoadControl.Builder()
        .setBufferDurationsMs(
          LOAD_CONTROL_MIN_BUFFER_MS,
          LOAD_CONTROL_MAX_BUFFER_MS,
          LOAD_CONTROL_BUFFER_FOR_PLAYBACK_MS,
          DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
    targetPreloadStatusControl = DefaultTargetPreloadStatusControl()
    val preloadManagerBuilder =
      DefaultPreloadManager.Builder(context.applicationContext, targetPreloadStatusControl)
        .setLoadControl(loadControl)
        .setCache(DemoUtil.getDownloadCache(context.applicationContext))
    playerPool = PlayerPool(numberOfPlayers, preloadManagerBuilder)
    holderMap = mutableMapOf()
    preloadManager = preloadManagerBuilder.build()
    preloadManager.addListener(DefaultPreloadManagerListener())
    addMediaItems(startIndex = 0, MANAGED_ITEM_COUNT, isAddingToRightEnd = true)
  }

  override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
    playerPool.destroyPlayers()
    preloadManager.release()
    holderMap.clear()
    super.onDetachedFromRecyclerView(recyclerView)
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewPagerMediaHolder {
    val view =
      LayoutInflater.from(parent.context).inflate(R.layout.media_item_view_pager, parent, false)
    val holder = ViewPagerMediaHolder(view, playerPool)
    view.addOnAttachStateChangeListener(holder)
    return holder
  }

  override fun onBindViewHolder(holder: ViewPagerMediaHolder, position: Int) {
    val mediaItem = mediaItemDatabase.get(position)
    Log.d(TAG, "onBindViewHolder: Getting item at position $position")
    var currentMediaSource = preloadManager.getMediaSource(mediaItem)
    if (currentMediaSource == null) {
      preloadManager.add(mediaItem, position)
      currentMediaSource = preloadManager.getMediaSource(mediaItem)!!
    }
    holder.bindData(currentMediaSource)
  }

  override fun onViewAttachedToWindow(holder: ViewPagerMediaHolder) {
    val holderBindingAdapterPosition = holder.bindingAdapterPosition
    holderMap[holderBindingAdapterPosition] = holder

    if (!currentMediaItemsAndIndexes.isEmpty()) {
      val leftMostIndex = currentMediaItemsAndIndexes.first().second
      val rightMostIndex = currentMediaItemsAndIndexes.last().second

      if (rightMostIndex - holderBindingAdapterPosition <= 2) {
        Log.d(TAG, "onViewAttachedToWindow: Approaching to the rightmost item")
        addMediaItems(
          startIndex = rightMostIndex + 1,
          ITEM_ADD_REMOVE_COUNT,
          isAddingToRightEnd = true,
        )
        removeMediaItems(ITEM_ADD_REMOVE_COUNT, isRemovingFromRightEnd = false)
      } else if (holderBindingAdapterPosition - leftMostIndex <= 2) {
        Log.d(TAG, "onViewAttachedToWindow: Approaching to the leftmost item")
        addMediaItems(
          startIndex = leftMostIndex - 1,
          ITEM_ADD_REMOVE_COUNT,
          isAddingToRightEnd = false,
        )
        removeMediaItems(ITEM_ADD_REMOVE_COUNT, isRemovingFromRightEnd = true)
      }
    }
  }

  override fun onViewDetachedFromWindow(holder: ViewPagerMediaHolder) {
    holderMap.remove(holder.bindingAdapterPosition)
  }

  override fun getItemCount(): Int {
    // Effectively infinite scroll
    return Int.MAX_VALUE
  }

  fun onPageSelected(position: Int) {
    holderMap[position]?.playIfPossible()
    targetPreloadStatusControl.currentPlayingIndex = position
    preloadManager.setCurrentPlayingIndex(position)
  }

  private fun addMediaItems(startIndex: Int, count: Int, isAddingToRightEnd: Boolean) {
    val mediaItems = mutableListOf<MediaItem>()
    val rankingDataList = mutableListOf<Int>()
    if (isAddingToRightEnd) {
      for (index in startIndex until startIndex + count) {
        if (index < 0) {
          break
        }
        val mediaItem = mediaItemDatabase.get(index)
        mediaItems.add(mediaItem)
        rankingDataList.add(index)
        currentMediaItemsAndIndexes.addLast(Pair(mediaItem, index))
        Log.d(TAG, "addMediaItems: Adding item at index $index")
      }
    } else {
      for (index in startIndex downTo startIndex - count + 1) {
        if (index < 0) {
          break
        }
        val mediaItem = mediaItemDatabase.get(index)
        mediaItems.add(mediaItemDatabase.get(index))
        rankingDataList.add(startIndex)
        currentMediaItemsAndIndexes.addFirst(Pair(mediaItem, index))
        Log.d(TAG, "addMediaItems: Adding item at index $index")
      }
    }
    preloadManager.addMediaItems(mediaItems, rankingDataList)
  }

  private fun removeMediaItems(count: Int, isRemovingFromRightEnd: Boolean) {
    val mediaItems = mutableListOf<MediaItem>()
    for (i in 0 until count) {
      if (currentMediaItemsAndIndexes.size <= MANAGED_ITEM_COUNT) {
        break
      }
      val itemAndIndexToRemove =
        if (isRemovingFromRightEnd) {
          currentMediaItemsAndIndexes.removeLast()
        } else {
          currentMediaItemsAndIndexes.removeFirst()
        }
      mediaItems.add(itemAndIndexToRemove.first)
      Log.d(TAG, "removeMediaItems: Removing item at index ${itemAndIndexToRemove.second}")
    }
    preloadManager.removeMediaItems(mediaItems)
  }

  inner class DefaultTargetPreloadStatusControl(var currentPlayingIndex: Int = C.INDEX_UNSET) :
    TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {

    override fun getTargetPreloadStatus(rankingData: Int): DefaultPreloadManager.PreloadStatus {
      if (abs(rankingData - currentPlayingIndex) == 1) {
        return DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(/* durationMs= */ 1000L)
      } else if (abs(rankingData - currentPlayingIndex) <= 3) {
        return DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(/* durationMs= */ 1000L)
      }
      return DefaultPreloadManager.PreloadStatus.specifiedRangeCached(5000L)
    }
  }

  inner class DefaultPreloadManagerListener : PreloadManagerListener {
    override fun onCompleted(mediaItem: MediaItem) {
      Log.w(TAG, "onCompleted: " + mediaItem.mediaId)
    }
  }
}
