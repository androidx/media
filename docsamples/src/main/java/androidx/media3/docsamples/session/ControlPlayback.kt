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

package androidx.media3.docsamples.session

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.docsamples.R
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.ConnectionResult.AcceptedResultBuilder
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Snippets for control-playback.md. */
object ControlPlaybackKt {
  private const val SAVE_TO_FAVORITES = "SAVE_TO_FAVORITES"

  fun createMediaSession(context: Context) {
    // [START create_media_session]
    val player = ExoPlayer.Builder(context).build()
    val mediaSession = MediaSession.Builder(context, player).build()
    // [END create_media_session]
  }

  @OptIn(UnstableApi::class)
  fun commandButton() {
    // [START command_button]
    val button =
      CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_15)
        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
        .setSlots(CommandButton.SLOT_FORWARD)
        .build()
    // [END command_button]
  }

  @OptIn(UnstableApi::class)
  fun setMediaButtonPreferences(
    context: Context,
    player: Player,
    likeButton: CommandButton,
    favoriteButton: CommandButton,
  ) {
    // [START set_media_button_preferences]
    val mediaSession =
      MediaSession.Builder(context, player)
        .setMediaButtonPreferences(ImmutableList.of(likeButton, favoriteButton))
        .build()
    // [END set_media_button_preferences]
  }

  @OptIn(UnstableApi::class)
  fun updateMediaButtonPreferences(
    mediaSession: MediaSession,
    likeButton: CommandButton,
    removeFromFavoritesButton: CommandButton,
  ) {
    // [START update_media_button_preferences]
    // Handle "favoritesButton" action, replace by opposite button
    mediaSession.setMediaButtonPreferences(ImmutableList.of(likeButton, removeFromFavoritesButton))
    // [END update_media_button_preferences]
  }

  @OptIn(UnstableApi::class)
  // [START set_custom_commands]
  private class CustomMediaSessionCallback : MediaSession.Callback {

    // Configure commands available to the controller in onConnect()
    override fun onConnectAsync(
      session: MediaSession,
      controller: ControllerInfo,
    ): ListenableFuture<ConnectionResult> {
      val sessionCommands =
        ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
          .add(SessionCommand(SAVE_TO_FAVORITES, Bundle.EMPTY))
          .build()
      return Futures.immediateFuture(
        AcceptedResultBuilder(session).setAvailableSessionCommands(sessionCommands).build()
      )
    }
  }

  // [END set_custom_commands]

  private fun saveToFavorites(mediaItem: MediaItem?) {}

  // [START on_custom_command_callback]
  private class CustomCallback : MediaSession.Callback {
    // ...
    override fun onCustomCommand(
      session: MediaSession,
      controller: ControllerInfo,
      customCommand: SessionCommand,
      args: Bundle,
    ): ListenableFuture<SessionResult> {
      if (customCommand.customAction == SAVE_TO_FAVORITES) {
        // Do custom logic here
        saveToFavorites(session.player.currentMediaItem)
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
      }
      // ...
      return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }
  }

  // [END on_custom_command_callback]

  @OptIn(UnstableApi::class)
  fun forwardingPlayer(context: Context, player: Player) {
    // [START forwarding_player]
    val forwardingPlayer =
      object : ForwardingSimpleBasePlayer(player) {
        // Customizations
      }

    val mediaSession = MediaSession.Builder(context, forwardingPlayer).build()
    // [END forwarding_player]
  }

  @OptIn(UnstableApi::class)
  // [START controller_for_current_request]
  private class CallerAwarePlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
    private lateinit var session: MediaSession

    override fun handleSeek(
      mediaItemIndex: Int,
      positionMs: Long,
      seekCommand: Int,
    ): ListenableFuture<*> {
      Log.d(
        "caller",
        "seek operation from package ${session.controllerForCurrentRequest?.packageName}",
      )
      return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }
  }

  // [END controller_for_current_request]

  @OptIn(UnstableApi::class)
  fun controlPlaybackCustomizePlaybackErrors(player: Player, context: Context) {
    // [START customize_playback_errors]
    val session = MediaSession.Builder(context, ErrorForwardingPlayer(context, player)).build()
    // [END customize_playback_errors]
  }

  @OptIn(UnstableApi::class)
  // [START customize_playback_errors_player]
  private class ErrorForwardingPlayer(private val context: Context, player: Player) :
    ForwardingSimpleBasePlayer(player) {

    override fun getState(): State {
      var state = super.getState()
      if (state.playerError != null) {
        state =
          state.buildUpon().setPlayerError(customizePlaybackException(state.playerError!!)).build()
      }
      return state
    }

    private fun customizePlaybackException(error: PlaybackException): PlaybackException {
      val buttonLabel: String
      val errorMessage: String
      when (error.errorCode) {
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
          buttonLabel = context.getString(R.string.err_button_label_restart_stream)
          errorMessage = context.getString(R.string.err_msg_behind_live_window)
        }
        else -> {
          buttonLabel = context.getString(R.string.err_button_label_ok)
          errorMessage = context.getString(R.string.err_message_default)
        }
      }
      val extras = Bundle()
      extras.putString("button_label", buttonLabel)
      return PlaybackException(errorMessage, error.cause, error.errorCode, extras)
    }
  }

  // [END customize_playback_errors_player]

  @OptIn(UnstableApi::class)
  fun sendNonFatalSessionErrors(mediaSession: MediaSession, context: Context) {
    // [START non_fatal_errors]
    val sessionError =
      SessionError(
        SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
        context.getString(R.string.error_message_authentication_expired),
      )

    // Option 1: Sending a nonfatal error to all controllers.
    mediaSession.sendError(sessionError)

    // Option 2: Sending a nonfatal error to the media notification controller only
    // to set the error code and error message in the playback state of the platform
    // media session.
    mediaSession.mediaNotificationControllerInfo?.let { mediaSession.sendError(it, sessionError) }
    // [END non_fatal_errors]
  }

  @OptIn(UnstableApi::class)
  fun receiveNonFatalErrors(context: Context, sessionToken: SessionToken) {
    // [START receive_non_fatal_errors]
    val future =
      MediaController.Builder(context, sessionToken)
        .setListener(
          object : MediaController.Listener {
            override fun onError(controller: MediaController, sessionError: SessionError) {
              // Handle nonfatal error.
            }
          }
        )
        .buildAsync()
    // [END receive_non_fatal_errors]
  }
}
