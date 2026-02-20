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

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.util.EventLogger

/** Code snippets for listening to player events. */
@Suppress("unused", "CheckReturnValue")
object ListeningToPlayerEventsKt {

  fun addListener(player: Player, listener: Player.Listener) {
    // [START add_listener]
    // Add a listener to receive events from the player.
    player.addListener(listener)
    // [END add_listener]
  }

  fun onIsPlayingOverride(player: Player) {
    // [START on_is_playing_override]
    player.addListener(
      object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
          if (isPlaying) {
            // Active playback.
          } else {
            // Not playing because playback is paused, ended, suppressed, or the player
            // is buffering, stopped or failed. Check player.playWhenReady,
            // player.playbackState, player.playbackSuppressionReason and
            // player.playerError for details.
          }
        }
      }
    )
    // [END on_is_playing_override]
  }

  fun onPlayerErrorOverride(player: Player) {
    // [START on_player_error_override]
    player.addListener(
      object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
          val cause = error.cause
          if (cause is HttpDataSourceException) {
            // An HTTP error occurred.
            val httpError = cause
            // It's possible to find out more about the error both by casting and by querying
            // the cause.
            if (httpError is InvalidResponseCodeException) {
              // Cast to InvalidResponseCodeException and retrieve the response code, message
              // and headers.
            } else {
              // Try calling httpError.getCause() to retrieve the underlying cause, although
              // note that it may be null.
            }
          }
        }
      }
    )
    // [END on_player_error_override]
  }

  class UiModule {
    fun updateUi(player: Player) {}
  }

  fun onEventsCallback(uiModule: UiModule) {
    object : Player.Listener {
      // [START on_events_callback]
      override fun onEvents(player: Player, events: Player.Events) {
        if (
          events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) ||
            events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)
        ) {
          uiModule.updateUi(player)
        }
      }
      // [END on_events_callback]
    }
  }

  fun addEventLogger(player: ExoPlayer) {
    // [START add_event_logger]
    player.addAnalyticsListener(EventLogger())
    // [END add_event_logger]
  }

  @OptIn(UnstableApi::class)
  fun executePlayerMessage(player: ExoPlayer, customPayloadData: Any) {
    // [START execute_player_message]
    player
      .createMessage { messageType: Int, payload: Any? -> }
      .setLooper(Looper.getMainLooper())
      .setPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 120000)
      .setPayload(customPayloadData)
      .setDeleteAfterDelivery(false)
      .send()
    // [END execute_player_message]
  }
}
