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

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import kotlin.math.abs

// Snippets for PreloadManager creation page.

object PreloadManagerCreateKt {

  @OptIn(UnstableApi::class)
  // [START android_defaultpreloadmanager_MyTargetPreloadStatusControl]
  class MyTargetPreloadStatusControl(var currentPlayingIndex: Int = 0) :
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

  @OptIn(UnstableApi::class)
  private fun create(context: Context) {
    // [START android_defaultpreloadmanager_createPLM]
    val targetPreloadStatusControl = MyTargetPreloadStatusControl()
    val preloadManagerBuilder = DefaultPreloadManager.Builder(context, targetPreloadStatusControl)
    val preloadManager = preloadManagerBuilder.build()
    // [END android_defaultpreloadmanager_createPLM]
  }
}
