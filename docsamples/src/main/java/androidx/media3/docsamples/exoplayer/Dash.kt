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
@file:Suppress(
  "unused_parameter",
  "unused_variable",
  "unused",
  "CheckReturnValue",
  "ControlFlowWithEmptyBody",
)

package androidx.media3.docsamples.exoplayer

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.source.MediaSource

// Snippets for DASH.

object DashKt {

  fun dashCreateMediaItem(context: Context, dashUri: Uri) {
    // [START create_media_item]
    // Create a player instance.
    val player = ExoPlayer.Builder(context).build()
    // Set the media item to be played.
    player.setMediaItem(MediaItem.fromUri(dashUri))
    // Prepare the player.
    player.prepare()
    // [END create_media_item]
  }

  @OptIn(UnstableApi::class)
  fun dashCreateMediaSource(context: Context, dashUri: Uri) {
    // [START create_media_source]
    val dataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
    // Create a dash media source pointing to a dash manifest uri.
    val mediaSource: MediaSource =
      DashMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(dashUri))
    // Create a player instance which gets an adaptive track selector by default.
    val player = ExoPlayer.Builder(context).build()
    // Set the media source to be played.
    player.setMediaSource(mediaSource)
    // Prepare the player.
    player.prepare()
    // [END create_media_source]
  }

  @OptIn(UnstableApi::class)
  fun dashAccessManifest(player: Player) {
    // [START access_manifest]
    player.addListener(
      object : Player.Listener {
        override fun onTimelineChanged(
          timeline: Timeline,
          @Player.TimelineChangeReason reason: Int,
        ) {
          val manifest = player.currentManifest
          if (manifest is DashManifest) {
            // Do something with the manifest.
          }
        }
      }
    )
    // [END access_manifest]
  }
}
