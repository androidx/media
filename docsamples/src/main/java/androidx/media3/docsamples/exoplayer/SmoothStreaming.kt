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
import androidx.media3.common.Player
import androidx.media3.common.Player.TimelineChangeReason
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.smoothstreaming.manifest.SsManifest
import androidx.media3.exoplayer.source.MediaSource

// Code snippets for the SmoothStreaming guide.

object SmoothStreamingKt {

  fun ssCreateMediaItem(context: Context, ssUri: Uri) {
    // [START create_media_item]
    // Create a player instance.
    val player = ExoPlayer.Builder(context).build()
    // Set the media item to be played.
    player.setMediaItem(MediaItem.fromUri(ssUri))
    // Prepare the player.
    player.prepare()
    // [END create_media_item]
  }

  @OptIn(UnstableApi::class)
  fun ssCreateMediaSource(ssUri: Uri, context: Context) {
    // [START create_media_source]
    // Create a data source factory.
    val dataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
    // Create a SmoothStreaming media source pointing to a manifest uri.
    val mediaSource: MediaSource =
      SsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(ssUri))
    // Create a player instance.
    val player = ExoPlayer.Builder(context).build()
    // Set the media source to be played.
    player.setMediaSource(mediaSource)
    // Prepare the player.
    player.prepare()
    // [END create_media_source]
  }

  @OptIn(UnstableApi::class)
  fun ssAccessManifest(player: Player) {
    // [START access_manifest]
    player.addListener(
      object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, @TimelineChangeReason reason: Int) {
          val manifest = player.currentManifest
          if (manifest is SsManifest) {
            // Do something with the manifest.
          }
        }
      }
    )
    // [END access_manifest]
  }
}
