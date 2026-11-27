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
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource

// Code snippets for the Progressive guide.

object ProgressiveKt {

  fun progressiveCreateMediaItem(context: Context, progressiveUri: Uri) {
    // [START create_media_item]
    // Create a player instance.
    val player = ExoPlayer.Builder(context).build()
    // Set the media item to be played.
    player.setMediaItem(MediaItem.fromUri(progressiveUri))
    // Prepare the player.
    player.prepare()
    // [END create_media_item]
  }

  @OptIn(UnstableApi::class)
  fun progressiveCreateMediaSource(progressiveUri: Uri, context: Context) {
    // [START create_media_source]
    // Create a data source factory.
    val dataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
    // Create a progressive media source pointing to a stream uri.
    val mediaSource: MediaSource =
      ProgressiveMediaSource.Factory(dataSourceFactory)
        .createMediaSource(MediaItem.fromUri(progressiveUri))
    // Create a player instance.
    val player = ExoPlayer.Builder(context).build()
    // Set the media source to be played.
    player.setMediaSource(mediaSource)
    // Prepare the player.
    player.prepare()
    // [END create_media_source]
  }
}
