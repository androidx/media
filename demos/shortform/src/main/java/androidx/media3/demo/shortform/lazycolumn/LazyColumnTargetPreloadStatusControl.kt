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

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.shortform.lazycolumn.LazyColumnActivity.Companion.LOAD_CONTROL_MIN_BUFFER_MS
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import kotlin.math.abs

@UnstableApi
class LazyColumnTargetPreloadStatusControl(
    var currentPlayingIndex: Int = C.INDEX_UNSET
) : TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {

    override fun getTargetPreloadStatus(rankingData: Int): DefaultPreloadManager.PreloadStatus? {
        if (abs(rankingData - currentPlayingIndex) == 2) {
            return DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(/* durationMs= */ LOAD_CONTROL_MIN_BUFFER_MS.toLong())
        } else if (abs(rankingData - currentPlayingIndex) == 1) {
            return DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(/* durationMs= */ LOAD_CONTROL_MIN_BUFFER_MS.toLong())
        }
        return null
    }
}