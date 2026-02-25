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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

object HelloWorldKt {

  fun helloWorldCreatePlayer(context: Context) {
    // [START hello_world_create_player]
    val player = ExoPlayer.Builder(context).build()
    // [END hello_world_create_player]
  }

  fun helloWorldAttachPlayer(playerView: PlayerView, player: Player) {
    // [START hello_world_attach_player]
    // Bind the player to the view.
    playerView.player = player
    // [END hello_world_attach_player]
  }

  fun helloWorldPreparePlayer(videoUri: Uri, player: Player) {
    // [START hello_world_prepare_player]
    // Build the media item.
    val mediaItem = MediaItem.fromUri(videoUri)
    // Set the media item to be played.
    player.setMediaItem(mediaItem)
    // Prepare the player.
    player.prepare()
    // Start the playback.
    player.play()
    // [END hello_world_prepare_player]
  }

  fun helloWorldPreparePlayerWithTwoMediaItems(
    firstVideoUri: Uri,
    secondVideoUri: Uri,
    player: Player,
  ) {
    // [START hello_world_prepare_player_with_two_media_items]
    // Build the media items.
    val firstItem = MediaItem.fromUri(firstVideoUri)
    val secondItem = MediaItem.fromUri(secondVideoUri)
    // Add the media items to be played.
    player.addMediaItem(firstItem)
    player.addMediaItem(secondItem)
    // Prepare the player.
    player.prepare()
    // Start the playback.
    player.play()
    // [END hello_world_prepare_player_with_two_media_items]
  }
}
