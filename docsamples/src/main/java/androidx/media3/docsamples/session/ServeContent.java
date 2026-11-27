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

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.os.Bundle;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.docsamples.R;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.CommandButton;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.MediaConstants;
import androidx.media3.session.MediaController;
import androidx.media3.session.MediaLibraryService;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSession.ControllerInfo;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionResult;
import androidx.media3.session.SessionToken;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

/** Snippets for serve-content.md. */
@SuppressWarnings({"unused", "CheckReturnValue"})
public class ServeContent {

  private static final String COMMAND_PLAYLIST_ADD = "COMMAND_PLAYLIST_ADD";
  private static final String COMMAND_RADIO = "COMMAND_RADIO";

  private ServeContent() {}

  // [START implement_media_library_service]
  class PlaybackService extends MediaLibraryService {
    MediaLibrarySession mediaLibrarySession = null;
    MediaLibrarySession.Callback callback = new MediaLibrarySession.Callback() {
          /* ... */
        };

    @Override
    public MediaLibrarySession onGetSession(MediaSession.ControllerInfo controllerInfo) {
      // If desired, validate the controller before returning the media library session
      return mediaLibrarySession;
    }

    // Create your player and media library session in the onCreate lifecycle event
    @Override
    public void onCreate() {
      super.onCreate();
      ExoPlayer player = new ExoPlayer.Builder(this).build();
      mediaLibrarySession = new MediaLibrarySession.Builder(this, player, callback).build();
    }

    // Remember to release the player and media library session in onDestroy
    @Override
    public void onDestroy() {
      if (mediaLibrarySession != null) {
        mediaLibrarySession.getPlayer().release();
        mediaLibrarySession.release();
        mediaLibrarySession = null;
      }
      super.onDestroy();
    }
  }

  // [END implement_media_library_service]

  @OptIn(markerClass = UnstableApi.class)
  public void defineCommandButtonsWhenBuildingTheSession(
      Context context, Player player, Bundle playlistAddExtras, Bundle radioExtras) {
    // [START define_commands_for_media_items]
    ImmutableList<CommandButton> allCommandButtons =
        ImmutableList.of(
            new CommandButton.Builder(CommandButton.ICON_PLAYLIST_ADD)
                .setDisplayName(context.getString(R.string.add_to_playlist))
                .setSessionCommand(new SessionCommand(COMMAND_PLAYLIST_ADD, Bundle.EMPTY))
                .setExtras(playlistAddExtras)
                .build(),
            new CommandButton.Builder(CommandButton.ICON_RADIO)
                .setDisplayName(context.getString(R.string.radio_station))
                .setSessionCommand(new SessionCommand(COMMAND_RADIO, Bundle.EMPTY))
                .setExtras(radioExtras)
                .build());
    // Add all command buttons for media items supported by the session.
    MediaSession session =
        new MediaSession.Builder(context, player)
            .setCommandButtonsForMediaItems(allCommandButtons)
            .build();
    // [END define_commands_for_media_items]
  }

  @OptIn(markerClass = UnstableApi.class)
  public void defineSupportedCommandsForMediaItem() {
    // [START define_supported_commands_for_media_item]
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setMediaMetadata(
                new MediaMetadata.Builder()
                    .setSupportedCommands(ImmutableList.of(COMMAND_PLAYLIST_ADD, COMMAND_RADIO))
                    .build())
            .build();
    // [END define_supported_commands_for_media_item]
  }

  private void loadMediaItemAsync(
      SettableFuture<LibraryResult<MediaItem>> future, String mediaId, int maxCommands) {}

  @OptIn(markerClass = UnstableApi.class)
  public void getMaxNumberOfCommandButtonForMediaItemsFromControllerInfo() {
    MediaLibrarySession.Callback callback =
        new MediaLibrarySession.Callback() {
          // [START get_max_commands_from_controller_info]
          @Override
          public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
              MediaLibraryService.MediaLibrarySession session,
              ControllerInfo browser,
              String mediaId) {
            SettableFuture<LibraryResult<MediaItem>> settableFuture = SettableFuture.create();

            int maxCommandsForMediaItems = browser.getMaxCommandsForMediaItems();
            loadMediaItemAsync(settableFuture, mediaId, maxCommandsForMediaItems);

            return settableFuture;
          }
          // [END get_max_commands_from_controller_info]
        };
  }

  private ListenableFuture<SessionResult> handleCustomCommand(
      ControllerInfo controller, SessionCommand command, Bundle args) {
    return null;
  }

  private ListenableFuture<SessionResult> handleCustomCommandForMediaItem(
      ControllerInfo controller, SessionCommand command, String mediaItemId, Bundle args) {
    return null;
  }

  @OptIn(markerClass = UnstableApi.class)
  public void getMediaItemIdWhenHandlingCustomCommand() {
    MediaLibrarySession.Callback callback =
        new MediaLibrarySession.Callback() {
          // [START handle_custom_command_for_media_item]
          @Override
          public ListenableFuture<SessionResult> onCustomCommand(
              MediaSession session,
              ControllerInfo controller,
              SessionCommand customCommand,
              Bundle args) {
            String mediaItemId = args.getString(MediaConstants.EXTRA_KEY_MEDIA_ID);
            return mediaItemId != null
                ? handleCustomCommandForMediaItem(controller, customCommand, mediaItemId, args)
                : handleCustomCommand(controller, customCommand, args);
          }
          // [END handle_custom_command_for_media_item]
        };
  }

  @OptIn(markerClass = UnstableApi.class)
  public void declareMaxNumberOfCommandButtons(Context context, SessionToken sessionToken) {
    // [START declare_max_commands_controller]
    ListenableFuture<MediaBrowser> browserFuture =
        new MediaBrowser.Builder(context, sessionToken).setMaxCommandsForMediaItems(3).buildAsync();
    // [END declare_max_commands_controller]
  }

  @OptIn(markerClass = UnstableApi.class)
  public void getCommandButtonWithController(MediaController controller, MediaItem mediaItem) {
    // [START get_command_buttons_for_media_item]
    ImmutableList<CommandButton> commandButtonsForMediaItem =
        controller.getCommandButtonsForMediaItem(mediaItem);
    // [END get_command_buttons_for_media_item]
  }

  @OptIn(markerClass = UnstableApi.class)
  public void sendingCustomCommandsForMediaItem(
      MediaController controller, CommandButton addToPlaylistButton, MediaItem mediaItem) {
    // [START send_custom_command_for_media_item]
    ListenableFuture<SessionResult> future =
        controller.sendCustomCommand(
            checkNotNull(addToPlaylistButton.sessionCommand), mediaItem, Bundle.EMPTY);
    // [END send_custom_command_for_media_item]
  }
}
