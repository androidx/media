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

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.OptIn;
import androidx.media3.common.ForwardingSimpleBasePlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.docsamples.R;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaController;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSession.ConnectionResult;
import androidx.media3.session.MediaSession.ConnectionResult.AcceptedResultBuilder;
import androidx.media3.session.MediaSession.ControllerInfo;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionError;
import androidx.media3.session.SessionResult;
import androidx.media3.session.SessionToken;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/** Snippets for control-playback.md. */
@SuppressWarnings({"unused", "CheckReturnValue"})
public class ControlPlayback {
  private static final String SAVE_TO_FAVORITES = "SAVE_TO_FAVORITES";

  private ControlPlayback() {}

  public void createMediaSession(Context context) {
    // [START create_media_session]
    ExoPlayer player = new ExoPlayer.Builder(context).build();
    MediaSession mediaSession = new MediaSession.Builder(context, player).build();
    // [END create_media_session]
  }

  @OptIn(markerClass = UnstableApi.class)
  public void commandButton() {
    // [START command_button]
    CommandButton button =
        new CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_15)
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build();
    // [END command_button]
  }

  @OptIn(markerClass = UnstableApi.class)
  public void setMediaButtonPreferences(
      Context context, Player player, CommandButton likeButton, CommandButton favoriteButton) {
    // [START set_media_button_preferences]
    MediaSession mediaSession =
        new MediaSession.Builder(context, player)
            .setMediaButtonPreferences(ImmutableList.of(likeButton, favoriteButton))
            .build();
    // [END set_media_button_preferences]
  }

  @OptIn(markerClass = UnstableApi.class)
  public void updateMediaButtonPreferences(
      MediaSession mediaSession,
      CommandButton likeButton,
      CommandButton removeFromFavoritesButton) {
    // [START update_media_button_preferences]
    // Handle "favoritesButton" action, replace by opposite button
    mediaSession.setMediaButtonPreferences(ImmutableList.of(likeButton, removeFromFavoritesButton));
    // [END update_media_button_preferences]
  }

  @OptIn(markerClass = UnstableApi.class)
  // [START set_custom_commands]
  private static class CustomMediaSessionCallback implements MediaSession.Callback {

    // Configure commands available to the controller in onConnect()
    @Override
    public ListenableFuture<ConnectionResult> onConnectAsync(
        MediaSession session, ControllerInfo controller) {
      SessionCommands sessionCommands =
          ConnectionResult.DEFAULT_SESSION_COMMANDS
              .buildUpon()
              .add(new SessionCommand(SAVE_TO_FAVORITES, new Bundle()))
              .build();
      return Futures.immediateFuture(
          new AcceptedResultBuilder(session).setAvailableSessionCommands(sessionCommands).build());
    }
  }

  // [END set_custom_commands]

  private static void saveToFavorites(MediaItem mediaItem) {}

  // [START on_custom_command_callback]
  private static class CustomCallback implements MediaSession.Callback {
    // ...
    @Override
    public ListenableFuture<SessionResult> onCustomCommand(
        MediaSession session,
        ControllerInfo controller,
        SessionCommand customCommand,
        Bundle args) {
      if (customCommand.customAction.equals(SAVE_TO_FAVORITES)) {
        // Do custom logic here
        saveToFavorites(session.getPlayer().getCurrentMediaItem());
        return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
      }
      // ...
      return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
    }
  }

  // [END on_custom_command_callback]

  @OptIn(markerClass = UnstableApi.class)
  public void forwardingPlayer(Context context, Player player) {
    // [START forwarding_player]
    ForwardingSimpleBasePlayer forwardingPlayer = new ForwardingSimpleBasePlayer(player) {
          // Customizations
        };

    MediaSession mediaSession = new MediaSession.Builder(context, forwardingPlayer).build();
    // [END forwarding_player]
  }

  @OptIn(markerClass = UnstableApi.class)
  // [START controller_for_current_request]
  private static final class CallerAwarePlayer extends ForwardingSimpleBasePlayer {
    private MediaSession session;

    public CallerAwarePlayer(Player player) {
      super(player);
    }

    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
      Log.d(
          "caller",
          "seek operation from package: "
              + session.getControllerForCurrentRequest().getPackageName());
      return super.handleSeek(mediaItemIndex, positionMs, seekCommand);
    }
  }

  // [END controller_for_current_request]

  @OptIn(markerClass = UnstableApi.class)
  public static void controlPlaybackCustomizePlaybackErrors(Player player, Context context) {
    // [START customize_playback_errors]
    MediaSession session =
        new MediaSession.Builder(context, new ErrorForwardingPlayer(context, player)).build();
    // [END customize_playback_errors]
  }

  @OptIn(markerClass = UnstableApi.class)
  // [START customize_playback_errors_player]
  private static class ErrorForwardingPlayer extends ForwardingSimpleBasePlayer {

    private final Context context;

    public ErrorForwardingPlayer(Context context, Player player) {
      super(player);
      this.context = context;
    }

    @Override
    protected State getState() {
      State state = super.getState();
      if (state.playerError != null) {
        state =
            state.buildUpon().setPlayerError(customizePlaybackException(state.playerError)).build();
      }
      return state;
    }

    private PlaybackException customizePlaybackException(PlaybackException error) {
      String buttonLabel;
      String errorMessage;
      switch (error.errorCode) {
        case PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW:
          buttonLabel = context.getString(R.string.err_button_label_restart_stream);
          errorMessage = context.getString(R.string.err_msg_behind_live_window);
          break;
        default:
          buttonLabel = context.getString(R.string.err_button_label_ok);
          errorMessage = context.getString(R.string.err_message_default);
          break;
      }
      Bundle extras = new Bundle();
      extras.putString("button_label", buttonLabel);
      return new PlaybackException(errorMessage, error.getCause(), error.errorCode, extras);
    }
  }

  // [END customize_playback_errors_player]

  @OptIn(markerClass = UnstableApi.class)
  public void sendNonFatalSessionErrors(MediaSession mediaSession, Context context) {
    // [START non_fatal_errors]
    SessionError sessionError =
        new SessionError(
            SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
            context.getString(R.string.error_message_authentication_expired));

    // Option 1: Sending a nonfatal error to all controllers.
    mediaSession.sendError(sessionError);

    // Option 2: Sending a nonfatal error to the media notification controller only
    // to set the error code and error message in the playback state of the platform
    // media session.
    ControllerInfo mediaNotificationControllerInfo =
        mediaSession.getMediaNotificationControllerInfo();
    if (mediaNotificationControllerInfo != null) {
      mediaSession.sendError(mediaNotificationControllerInfo, sessionError);
    }
    // [END non_fatal_errors]
  }

  @OptIn(markerClass = UnstableApi.class)
  public void receiveNonFatalErrors(Context context, SessionToken sessionToken) {
    // [START receive_non_fatal_errors]
    MediaController.Builder future =
        new MediaController.Builder(context, sessionToken)
            .setListener(
                new MediaController.Listener() {
                  @Override
                  public void onError(MediaController controller, SessionError sessionError) {
                    // Handle nonfatal error.
                  }
                });
    // [END receive_non_fatal_errors]
  }
}
