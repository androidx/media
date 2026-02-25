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

import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Player.Events;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.util.EventLogger;

/** Code snippets for listening to player events. */
@SuppressWarnings({
  "unused",
  "CheckReturnValue",
  "UnusedAnonymousClass",
  "EffectivelyPrivate",
  "DuplicateBranches",
  "PrivateConstructorForUtilityClass",
  "PatternVariableCanBeUsed",
  "ControlFlowWithEmptyBody"
})
public final class ListeningToPlayerEvents {

  public static void addListener(Player player, Player.Listener listener) {
    // [START add_listener]
    // Add a listener to receive events from the player.
    player.addListener(listener);
    // [END add_listener]
  }

  public static void onIsPlayingOverride(Player player) {
    // [START on_is_playing_override]
    player.addListener(
        new Player.Listener() {
          @Override
          public void onIsPlayingChanged(boolean isPlaying) {
            if (isPlaying) {
              // Active playback.
            } else {
              // Not playing because playback is paused, ended, suppressed, or the player
              // is buffering, stopped or failed. Check player.getPlayWhenReady,
              // player.getPlaybackState, player.getPlaybackSuppressionReason and
              // player.getPlaybackError for details.
            }
          }
        });
    // [END on_is_playing_override]
  }

  public static void onPlayerErrorOverride(Player player) {
    // [START on_player_error_override]
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPlayerError(PlaybackException error) {
            @Nullable Throwable cause = error.getCause();
            if (cause instanceof HttpDataSourceException) {
              // An HTTP error occurred.
              HttpDataSourceException httpError = (HttpDataSourceException) cause;
              // It's possible to find out more about the error both by casting and by querying
              // the cause.
              if (httpError instanceof HttpDataSource.InvalidResponseCodeException) {
                // Cast to InvalidResponseCodeException and retrieve the response code, message
                // and headers.
              } else {
                // Try calling httpError.getCause() to retrieve the underlying cause, although
                // note that it may be null.
              }
            }
          }
        });
    // [END on_player_error_override]
  }

  private static final class UiModule {
    public void updateUi(Player player) {}
  }

  private static void onEventsCallback(UiModule uiModule) {
    new Player.Listener() {
      // [START on_events_callback]
      @Override
      public void onEvents(Player player, Events events) {
        if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
            || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
          uiModule.updateUi(player);
        }
      }
      // [END on_events_callback]
    };
  }

  public static void addEventLogger(ExoPlayer player) {
    // [START add_event_logger]
    player.addAnalyticsListener(new EventLogger());
    // [END add_event_logger]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void executePlayerMessage(ExoPlayer player, Object customPayloadData) {
    // [START execute_player_message]
    player
        .createMessage(
            (messageType, payload) -> {
              // Do something at the specified playback position.
            })
        .setLooper(Looper.getMainLooper())
        .setPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 120_000)
        .setPayload(customPayloadData)
        .setDeleteAfterDelivery(false)
        .send();
    // [END execute_player_message]
  }
}
