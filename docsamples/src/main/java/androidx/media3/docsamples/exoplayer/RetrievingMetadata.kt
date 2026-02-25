/*
 * Copyright 2026 The Android Open Source Project
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
@file:Suppress("unused_parameter", "unused_variable", "unused", "CheckReturnValue")

package androidx.media3.docsamples.exoplayer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.extractor.metadata.MotionPhotoMetadata
import androidx.media3.inspector.MetadataRetriever
import java.io.IOException
import kotlinx.coroutines.guava.await

// Code snippets for the Retrieving metadata guide.

object RetrievingMetadataKt {

  private fun handleTitle(title: CharSequence) {}

  fun onMediaMetadataChanged() {
    object : Player.Listener {
      // [START on_media_metadata_changed]
      override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
        mediaMetadata.title?.let(::handleTitle)
      }
      // [END on_media_metadata_changed]
    }
  }

  @OptIn(UnstableApi::class)
  private fun handleMetadata(trackGroups: TrackGroupArray, timeline: Timeline, durationUs: Long) {}

  private fun handleFailure(throwable: Throwable) {}

  @OptIn(UnstableApi::class)
  suspend fun retrieveMetadataWithoutPlayback(context: Context, mediaItem: MediaItem) {
    // [START retrieve_metadata_without_playback]
    try {
      MetadataRetriever.Builder(context, mediaItem).build().use { metadataRetriever ->
        val trackGroups = metadataRetriever.retrieveTrackGroups().await()
        val timeline = metadataRetriever.retrieveTimeline().await()
        val durationUs = metadataRetriever.retrieveDurationUs().await()
        handleMetadata(trackGroups, timeline, durationUs)
      }
    } catch (e: IOException) {
      handleFailure(e)
    }
    // [END retrieve_metadata_without_playback]
  }

  @OptIn(UnstableApi::class)
  private fun handleMotionPhotoMetadata(motionPhotoMetadata: MotionPhotoMetadata) {}

  @OptIn(UnstableApi::class)
  fun retrieveMotionPhotoMetadata(trackGroups: TrackGroupArray) {
    // [START retrieve_motion_photo_metadata]
    0.until(trackGroups.length)
      .asSequence()
      .mapNotNull { trackGroups[it].getFormat(0).metadata }
      .filter { metadata -> metadata.length() == 1 }
      .map { metadata -> metadata[0] }
      .filterIsInstance<MotionPhotoMetadata>()
      .forEach(::handleMotionPhotoMetadata)
    // [END retrieve_motion_photo_metadata]
  }
}
