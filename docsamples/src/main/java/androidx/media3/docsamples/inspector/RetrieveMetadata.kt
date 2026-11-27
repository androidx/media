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

package androidx.media3.docsamples.inspector

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.inspector.MetadataRetriever
import kotlinx.coroutines.guava.await

@OptIn(UnstableApi::class)
object RetrieveMetadataKt {

  // [START retrieve_metadata]
  suspend fun retrieveMetadata(context: Context, mediaItem: MediaItem) {
    try {
      // 1. Build the retriever.
      // `MetadataRetriever` implements `AutoCloseable`, so wrap it in
      // a Kotlin `.use` block, which calls `close()` automatically.
      MetadataRetriever.Builder(context, mediaItem).build().use { retriever ->
        // 2. Retrieve metadata asynchronously.
        val trackGroups = retriever.retrieveTrackGroups().await()
        val timeline = retriever.retrieveTimeline().await()
        val durationUs = retriever.retrieveDurationUs().await()
        handleMetadata(trackGroups, timeline, durationUs)
      }
    } catch (e: Exception) {
      handleFailure(e)
    }
  }

  // [END retrieve_metadata]

  private fun handleMetadata(
    trackGroups: TrackGroupArray?,
    timeline: Timeline?,
    durationUs: Long?,
  ) {}

  private fun handleFailure(e: Exception) {}
}
