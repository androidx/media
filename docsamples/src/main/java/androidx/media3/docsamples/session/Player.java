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
package androidx.media3.docsamples.session;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.OptIn;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.util.UnstableApi;

/** Snippets for player.md. */
@SuppressWarnings({"unused", "CheckReturnValue"})
public class Player {

  private Player() {}

  private static class QueryPlaybackPosition {
    private androidx.media3.common.Player player;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // [START query_playback_position]
    boolean checkPlaybackPosition(long delayMs) {
      return handler.postDelayed(
          () -> {
            long currentPosition = player.getCurrentPosition();
            // Update UI based on currentPosition
            checkPlaybackPosition(delayMs);
          },
          delayMs);
    }
    // [END query_playback_position]
  }

  @OptIn(markerClass = UnstableApi.class)
  // [START simple_base_player]
  private static final class CustomPlayer extends SimpleBasePlayer {
    public CustomPlayer(Looper looper) {
      super(looper);
    }

    @Override
    protected State getState() {
      return new State.Builder()
          .setAvailableCommands(Commands.EMPTY) // Set which playback commands the player can handle
          // Configure additional playback properties
          .setPlayWhenReady(true, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
          .setCurrentMediaItemIndex(0)
          .setContentPositionMs(0)
          .build();
    }
  }
  // [END simple_base_player]
}
