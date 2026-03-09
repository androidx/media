/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.docsamples.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.docsamples.R;
import androidx.media3.exoplayer.ExoPlayer;

/** Snippets for playerview.md. */
@SuppressWarnings({"unused", "InstantiationOfAbstractClass"})
public final class PlayerView {

  private abstract static class CreatePlayerViewExample extends Activity {
    private androidx.media3.ui.PlayerView playerView = null;

    // [START create_player_view]
    @Override
    protected void onCreate(Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);
      // ...
      playerView = findViewById(R.id.player_view);
    }
    // [END create_player_view]
  }

  public void playerViewSetPlayer(
      Player player,
      Context context,
      androidx.media3.ui.PlayerView playerView,
      MediaItem mediaItem) {
    // [START set_player]
    // Instantiate the player.
    player = new ExoPlayer.Builder(context).build();
    // Attach player to the view.
    playerView.setPlayer(player);
    // Set the media item to be played.
    player.setMediaItem(mediaItem);
    // Prepare the player.
    player.prepare();
    // [END set_player]
  }

  private PlayerView() {}
}
