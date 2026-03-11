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

import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.CommandButton;
import androidx.media3.session.CommandButton.DisplayConstraints;
import androidx.media3.session.LibraryResult;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionResult;
import androidx.media3.session.SessionToken;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.List;

/** Snippets for connect-to-media-app.md. */
@SuppressWarnings({"unused", "CheckReturnValue"})
public class ConnectToMediaApp {

  private ConnectToMediaApp() {}

  private static class PlaybackService {}

  public void createSessionToken(Context context) {
    // [START create_session_token]
    SessionToken sessionToken =
        new SessionToken(context, new ComponentName(context, PlaybackService.class));
    // [END create_session_token]
  }

  public void createMediaController(Context context, SessionToken sessionToken) {
    // [START create_media_controller]
    ListenableFuture<MediaController> controllerFuture =
        new MediaController.Builder(context, sessionToken).buildAsync();
    controllerFuture.addListener(
        () -> {
          // MediaController is available here with controllerFuture.get()
        },
        MoreExecutors.directExecutor());
    // [END create_media_controller]
  }

  public void mediaControllerListener(Context context, SessionToken sessionToken) {
    // [START media_controller_listener]
    ListenableFuture<MediaController> controllerFuture =
        new MediaController.Builder(context, sessionToken)
            .setListener(
                new MediaController.Listener() {
                  @Override
                  public ListenableFuture<SessionResult> onCustomCommand(
                      MediaController controller, SessionCommand command, Bundle args) {
                    // Handle custom command.
                    return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                  }

                  @Override
                  public void onDisconnected(MediaController controller) {
                    // Handle disconnection.
                  }
                })
            .buildAsync();
    // [END media_controller_listener]
  }

  public void releaseMediaController(ListenableFuture<MediaController> controllerFuture) {
    // [START release_media_controller]
    MediaController.releaseFuture(controllerFuture);
    // [END release_media_controller]
  }

  public void createMediaBrowser(Context context, SessionToken sessionToken) {
    // [START create_media_browser]
    ListenableFuture<MediaBrowser> browserFuture =
        new MediaBrowser.Builder(context, sessionToken).buildAsync();
    browserFuture.addListener(
        () -> {
          // MediaBrowser is available here with browserFuture.get()
        },
        MoreExecutors.directExecutor());
    // [END create_media_browser]
  }

  public void getLibraryRoot(MediaBrowser mediaBrowser) {
    // [START get_library_root]
    // Get the library root to start browsing the library tree.
    ListenableFuture<LibraryResult<MediaItem>> rootFuture =
        mediaBrowser.getLibraryRoot(/* params= */ null);
    rootFuture.addListener(
        () -> {
          // Root node MediaItem is available here with rootFuture.get().value
        },
        MoreExecutors.directExecutor());
    // [END get_library_root]
  }

  public void getChildren(MediaBrowser mediaBrowser, MediaItem rootMediaItem) {
    // [START get_children]
    ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> childrenFuture =
        mediaBrowser.getChildren(rootMediaItem.mediaId, 0, Integer.MAX_VALUE, null);
    childrenFuture.addListener(
        () -> {
          // List of children MediaItem nodes is available here with
          // childrenFuture.get().value
        },
        MoreExecutors.directExecutor());
    // [END get_children]
  }

  private int getIconRes(int icon) {
    return 0;
  }

  private void generateUiButton(int uiPosition, int icon, Runnable onClick) {}

  @OptIn(markerClass = UnstableApi.class)
  public void displayPreferences(MediaController controller) {
    // [START display_preferences]
    // Get media button preferences from media app
    List<CommandButton> mediaButtonPreferences = controller.getMediaButtonPreferences();
    // Declare constraints of UI (example: limit overflow button to one)
    DisplayConstraints displayConstraints =
        new DisplayConstraints.Builder()
            .setMaxButtonsForSlot(CommandButton.SLOT_OVERFLOW, 1)
            .build();
    // Resolve media app preferences with constraints
    List<CommandButton> resolvedButtons =
        displayConstraints.resolve(mediaButtonPreferences, controller);
    // Display buttons in UI
    for (CommandButton button : resolvedButtons) {
      generateUiButton(
          /* uiPosition= */ button.slots.get(0),
          /* icon= */ getIconRes(button.icon),
          /* onClick= */ () -> button.executeAction(controller));
    }
    // [END display_preferences]
  }
}
