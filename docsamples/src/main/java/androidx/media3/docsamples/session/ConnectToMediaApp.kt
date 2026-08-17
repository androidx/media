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

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.CommandButton.DisplayConstraints
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/** Snippets for connect-to-media-app.md. */
object ConnectToMediaAppKt {

  private class PlaybackService

  fun createSessionToken(context: Context) {
    // [START create_session_token]
    val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    // [END create_session_token]
  }

  fun createMediaController(context: Context, sessionToken: SessionToken) {
    // [START create_media_controller]
    val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
    controllerFuture.addListener(
      {
        // MediaController is available here with controllerFuture.get()
      },
      MoreExecutors.directExecutor(),
    )
    // [END create_media_controller]
  }

  fun mediaControllerListener(context: Context, sessionToken: SessionToken) {
    // [START media_controller_listener]
    val controllerFuture =
      MediaController.Builder(context, sessionToken)
        .setListener(
          object : MediaController.Listener {
            override fun onCustomCommand(
              controller: MediaController,
              command: SessionCommand,
              args: Bundle,
            ): ListenableFuture<SessionResult> {
              // Handle custom command.
              return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            override fun onDisconnected(controller: MediaController) {
              // Handle disconnection.
            }
          }
        )
        .buildAsync()
    // [END media_controller_listener]
  }

  fun releaseMediaController(controllerFuture: ListenableFuture<MediaController>) {
    // [START release_media_controller]
    MediaController.releaseFuture(controllerFuture)
    // [END release_media_controller]
  }

  fun createMediaBrowser(context: Context, sessionToken: SessionToken) {
    // [START create_media_browser]
    val browserFuture = MediaBrowser.Builder(context, sessionToken).buildAsync()
    browserFuture.addListener(
      {
        // MediaBrowser is available here with browserFuture.get()
      },
      MoreExecutors.directExecutor(),
    )
    // [END create_media_browser]
  }

  fun getLibraryRoot(mediaBrowser: MediaBrowser) {
    // [START get_library_root]
    // Get the library root to start browsing the library tree.
    val rootFuture = mediaBrowser.getLibraryRoot(/* params= */ null)
    rootFuture.addListener(
      {
        // Root node MediaItem is available here with rootFuture.get().value
      },
      MoreExecutors.directExecutor(),
    )
    // [END get_library_root]
  }

  fun getChildren(mediaBrowser: MediaBrowser, rootMediaItem: MediaItem) {
    // [START get_children]
    // Get the library root to start browsing the library tree.
    val childrenFuture = mediaBrowser.getChildren(rootMediaItem.mediaId, 0, Int.MAX_VALUE, null)
    childrenFuture.addListener(
      {
        // List of children MediaItem nodes is available here with
        // childrenFuture.get().value
      },
      MoreExecutors.directExecutor(),
    )
    // [END get_children]
  }

  private fun getIconRes(icon: Int): Int {
    return 0
  }

  private fun generateUiButton(uiPosition: Int, icon: Int, onClick: Runnable?) {}

  @OptIn(UnstableApi::class)
  fun displayPreferences(controller: MediaController) {
    // [START display_preferences]
    // Get media button preferences from media app
    val mediaButtonPreferences = controller.getMediaButtonPreferences()
    // Declare constraints of UI (example: limit overflow button to one)
    val displayConstraints =
      DisplayConstraints.Builder().setMaxButtonsForSlot(CommandButton.SLOT_OVERFLOW, 1).build()
    // Resolve media app preferences with constraints
    val resolvedButtons = displayConstraints.resolve(mediaButtonPreferences, controller)
    // Display buttons in UI
    for (button in resolvedButtons) {
      generateUiButton(
        uiPosition = button.slots[0],
        icon = getIconRes(button.icon),
        onClick = { button.executeAction(controller) },
      )
    }
    // [END display_preferences]
  }
}
