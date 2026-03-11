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

import static androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM;

import android.content.Intent;
import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaController;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSession.ConnectionResult;
import androidx.media3.session.MediaSession.ConnectionResult.AcceptedResultBuilder;
import androidx.media3.session.MediaSession.ControllerInfo;
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;

/** Snippets for background-playback.md. */
@SuppressWarnings({"unused", "CheckReturnValue"})
public class BackgroundPlayback {

  private BackgroundPlayback() {}

  // [START implement_media_session_service]
  class PlaybackService extends MediaSessionService {
    private MediaSession mediaSession = null;

    // Create your Player and MediaSession in the onCreate lifecycle event
    @Override
    public void onCreate() {
      super.onCreate();
      ExoPlayer player = new ExoPlayer.Builder(this).build();
      mediaSession = new MediaSession.Builder(this, player).build();
    }

    // Remember to release the player and media session in onDestroy
    @Override
    public void onDestroy() {
      mediaSession.getPlayer().release();
      mediaSession.release();
      mediaSession = null;
      super.onDestroy();
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
      return mediaSession;
    }
  }

  // [END implement_media_session_service]

  abstract static class PlaybackServiceWithTaskRemoved extends MediaSessionService {
    // [START on_task_removed]
    @OptIn(markerClass = UnstableApi.class)
    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
      pauseAllPlayersAndStopSelf();
    }
    // [END on_task_removed]
  }

  static class PlaybackService2 {
    private MediaSession mediaSession = null;

    // [START on_get_session]
    class PlaybackService extends MediaSessionService {

      // [...] lifecycle methods omitted

      @Override
      public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
      }
    }
    // [END on_get_session]
  }

  public void buildMediaItemWithMetadata(
      Uri mediaUri, Uri artworkUri, MediaController mediaController) {
    // [START build_media_item_with_metadata]
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setMediaId("media-1")
            .setUri(mediaUri)
            .setMediaMetadata(
                new MediaMetadata.Builder()
                    .setArtist("David Bowie")
                    .setTitle("Heroes")
                    .setArtworkUri(artworkUri)
                    .build())
            .build();

    mediaController.setMediaItem(mediaItem);
    mediaController.prepare();
    mediaController.play();
    // [END build_media_item_with_metadata]
  }

  @OptIn(markerClass = UnstableApi.class)
  class PlaybackResumptionCallback implements MediaSession.Callback {
    // [START implement_playback_resumption]
    @Override
    public ListenableFuture<MediaItemsWithStartPosition> onPlaybackResumption(
        MediaSession mediaSession, ControllerInfo controller, boolean isForPlayback) {
      SettableFuture<MediaItemsWithStartPosition> settableFuture = SettableFuture.create();
      settableFuture.addListener(
          () -> {
            // Your app is responsible for storing the playlist, metadata (like title
            // and artwork) of the current item and the start position to use here.
            MediaItemsWithStartPosition resumptionPlaylist = restorePlaylist();
            settableFuture.set(resumptionPlaylist);
          },
          MoreExecutors.directExecutor());
      return settableFuture;
    }

    // [END implement_playback_resumption]

    private MediaItemsWithStartPosition restorePlaylist() {
      return null;
    }
  }

  @OptIn(markerClass = UnstableApi.class)
  class MediaNotificationCallback implements MediaSession.Callback {
    private final CommandButton seekBackButton;
    private final CommandButton seekForwardButton;

    public MediaNotificationCallback(
        CommandButton seekBackButton, CommandButton seekForwardButton) {
      this.seekBackButton = seekBackButton;
      this.seekForwardButton = seekForwardButton;
    }

    // [START media_notification_controller]
    @Override
    public ConnectionResult onConnect(
        MediaSession session, MediaSession.ControllerInfo controller) {
      if (session.isMediaNotificationController(controller)) {
        Player.Commands playerCommands =
            ConnectionResult.DEFAULT_PLAYER_COMMANDS
                .buildUpon()
                .remove(COMMAND_SEEK_TO_PREVIOUS)
                .remove(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .remove(COMMAND_SEEK_TO_NEXT)
                .remove(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build();
        // Custom button preferences and commands to configure the platform session.
        return new AcceptedResultBuilder(session)
            .setMediaButtonPreferences(ImmutableList.of(seekBackButton, seekForwardButton))
            .setAvailablePlayerCommands(playerCommands)
            .build();
      }
      // Default commands with default button preferences for all other controllers.
      return new AcceptedResultBuilder(session).build();
    }
    // [END media_notification_controller]
  }

  @OptIn(markerClass = UnstableApi.class)
  class AutoCompanionCallback implements MediaSession.Callback {
    private final SessionCommand customCommand;

    public AutoCompanionCallback(SessionCommand customCommand) {
      this.customCommand = customCommand;
    }

    // [START auto_companion_controller]
    @Override
    public ConnectionResult onConnect(
        MediaSession session, MediaSession.ControllerInfo controller) {
      SessionCommands sessionCommands =
          ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(customCommand).build();
      if (session.isMediaNotificationController(controller)) {
        // ... See above.
      } else if (session.isAutoCompanionController(controller)) {
        // Available commands to accept incoming custom commands from Auto.
        return new AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .build();
      }
      // Default commands for all other controllers.
      return new AcceptedResultBuilder(session).build();
    }
    // [END auto_companion_controller]
  }
}
