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
package androidx.media3.docsamples.exoplayer;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

/** Snippets for Hello world. */
@SuppressWarnings({"unused", "PrivateConstructorForUtilityClass"})
public class HelloWorld {

  public static void helloWorldCreatePlayer(Context context) {
    // [START hello_world_create_player]
    ExoPlayer player = new ExoPlayer.Builder(context).build();
    // [END hello_world_create_player]
  }

  public static void helloWorldAttachPlayer(PlayerView playerView, Player player) {
    // [START hello_world_attach_player]
    // Bind the player to the view.
    playerView.setPlayer(player);
    // [END hello_world_attach_player]
  }

  public static void helloWorldPreparePlayer(Uri videoUri, Player player) {
    // [START hello_world_prepare_player]
    // Build the media item.
    MediaItem mediaItem = MediaItem.fromUri(videoUri);
    // Set the media item to be played.
    player.setMediaItem(mediaItem);
    // Prepare the player.
    player.prepare();
    // Start the playback.
    player.play();
    // [END hello_world_prepare_player]
  }

  public static void helloWorldPreparePlayerWithTwoMediaItems(
      Uri firstVideoUri, Uri secondVideoUri, Player player) {
    // [START hello_world_prepare_player_with_two_media_items]
    // Build the media items.
    MediaItem firstItem = MediaItem.fromUri(firstVideoUri);
    MediaItem secondItem = MediaItem.fromUri(secondVideoUri);
    // Add the media items to be played.
    player.addMediaItem(firstItem);
    player.addMediaItem(secondItem);
    // Prepare the player.
    player.prepare();
    // Start the playback.
    player.play();
    // [END hello_world_prepare_player_with_two_media_items]
  }
}
