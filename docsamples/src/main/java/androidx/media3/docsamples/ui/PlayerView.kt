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

package androidx.media3.docsamples.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.docsamples.R
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView as Media3PlayerView

/** Snippets for playerview.md. */
object PlayerViewKt {

  private abstract class CreatePlayerViewExample : Activity() {
    private var playerView: Media3PlayerView? = null

    // [START create_player_view]
    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      // ...
      playerView = findViewById(R.id.player_view)
    }
    // [END create_player_view]
  }

  fun playerViewSetPlayer(context: Context, playerView: Media3PlayerView, mediaItem: MediaItem) {
    // [START set_player]
    // Instantiate the player.
    val player = ExoPlayer.Builder(context).build()
    // Attach player to the view.
    playerView.player = player
    // Set the media item to be played.
    player.setMediaItem(mediaItem)
    // Prepare the player.
    player.prepare()
    // [END set_player]
  }
}
