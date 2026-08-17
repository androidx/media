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

@file:Suppress("UNUSED_PARAMETER")

package androidx.media3.docsamples.inspector.frame

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.frame.FrameExtractor
import kotlinx.coroutines.guava.await

@OptIn(UnstableApi::class)
object ExtractFramesKt {

  // [START extract_frames]
  suspend fun extractFrames(context: Context, mediaItem: MediaItem) {
    try {
      // 1. Build the frame extractor.
      // `FrameExtractor` implements `AutoCloseable`, so wrap it in
      // a Kotlin `.use` block, which calls `close()` automatically.
      FrameExtractor.Builder(context, mediaItem).build().use { extractor ->
        // 2. Extract frames asynchronously.
        val frame = extractor.getFrame(5000L).await()
        val thumbnail = extractor.thumbnail.await()
        handleFrame(frame, thumbnail)
      }
    } catch (e: Exception) {
      handleFailure(e)
    }
  }

  // [END extract_frames]

  private fun handleFrame(frame: FrameExtractor.Frame, thumbnail: FrameExtractor.Frame) {}

  private fun handleFailure(e: Exception) {}
}
