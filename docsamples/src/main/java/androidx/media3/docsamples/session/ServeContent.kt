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
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.docsamples.R
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture

/** Snippets for serve-content.md. */
object ServeContentKt {

  private const val COMMAND_PLAYLIST_ADD = "COMMAND_PLAYLIST_ADD"
  private const val COMMAND_RADIO = "COMMAND_RADIO"

  // [START implement_media_library_service]
  class PlaybackService : MediaLibraryService() {
    private var mediaLibrarySession: MediaLibrarySession? = null
    private val callback: MediaLibrarySession.Callback =
      object : MediaLibrarySession.Callback {
        /* ... */
      }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
      // If desired, validate the controller before returning the media library session
      return mediaLibrarySession
    }

    // Create your player and media library session in the onCreate lifecycle event
    override fun onCreate() {
      super.onCreate()
      val player = ExoPlayer.Builder(this).build()
      mediaLibrarySession = MediaLibrarySession.Builder(this, player, callback).build()
    }

    // Remember to release the player and media library session in onDestroy
    override fun onDestroy() {
      mediaLibrarySession?.run {
        player.release()
        release()
        mediaLibrarySession = null
      }
      super.onDestroy()
    }
  }

  // [END implement_media_library_service]

  @OptIn(UnstableApi::class)
  fun defineCommandButtonsWhenBuildingTheSession(
    context: Context,
    player: Player,
    playlistAddExtras: Bundle,
    radioExtras: Bundle,
  ) {
    // [START define_commands_for_media_items]
    val allCommandButtons =
      listOf(
        CommandButton.Builder(CommandButton.ICON_PLAYLIST_ADD)
          .setDisplayName(context.getString(R.string.add_to_playlist))
          .setSessionCommand(SessionCommand(COMMAND_PLAYLIST_ADD, Bundle.EMPTY))
          .setExtras(playlistAddExtras)
          .build(),
        CommandButton.Builder(CommandButton.ICON_RADIO)
          .setDisplayName(context.getString(R.string.radio_station))
          .setSessionCommand(SessionCommand(COMMAND_RADIO, Bundle.EMPTY))
          .setExtras(radioExtras)
          .build(),
      )
    // Add all command buttons for media items supported by the session.
    val session =
      MediaSession.Builder(context, player)
        .setCommandButtonsForMediaItems(allCommandButtons)
        .build()
    // [END define_commands_for_media_items]
  }

  @OptIn(UnstableApi::class)
  fun defineSupportedCommandsForMediaItem() {
    // [START define_supported_commands_for_media_item]
    val mediaItem =
      MediaItem.Builder()
        .setMediaMetadata(
          MediaMetadata.Builder()
            .setSupportedCommands(listOf(COMMAND_PLAYLIST_ADD, COMMAND_RADIO))
            .build()
        )
        .build()
    // [END define_supported_commands_for_media_item]
  }

  private fun loadMediaItemAsync(
    future: SettableFuture<LibraryResult<MediaItem>>,
    mediaId: String,
    maxCommands: Int,
  ) {}

  @OptIn(UnstableApi::class)
  fun getMaxNumberOfCommandButtonForMediaItemsFromControllerInfo() {
    val callback =
      object : MediaLibrarySession.Callback {
        // [START get_max_commands_from_controller_info]
        override fun onGetItem(
          session: MediaLibrarySession,
          browser: MediaSession.ControllerInfo,
          mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
          val settableFuture = SettableFuture.create<LibraryResult<MediaItem>>()

          val maxCommandsForMediaItems = browser.maxCommandsForMediaItems
          loadMediaItemAsync(settableFuture, mediaId, maxCommandsForMediaItems)

          return settableFuture
        }
        // [END get_max_commands_from_controller_info]
      }
  }

  private fun handleCustomCommand(
    controller: MediaSession.ControllerInfo?,
    command: SessionCommand?,
    args: Bundle?,
  ): ListenableFuture<SessionResult> = SettableFuture.create<SessionResult>()

  private fun handleCustomCommandForMediaItem(
    controller: MediaSession.ControllerInfo?,
    command: SessionCommand?,
    mediaItemId: String?,
    args: Bundle?,
  ): ListenableFuture<SessionResult> = SettableFuture.create<SessionResult>()

  @OptIn(UnstableApi::class)
  fun getMediaItemIdWhenHandlingCustomCommand() {
    val callback =
      object : MediaLibrarySession.Callback {
        // [START handle_custom_command_for_media_item]
        override fun onCustomCommand(
          session: MediaSession,
          controller: MediaSession.ControllerInfo,
          customCommand: SessionCommand,
          args: Bundle,
        ): ListenableFuture<SessionResult> {
          val mediaItemId = args.getString(MediaConstants.EXTRA_KEY_MEDIA_ID)
          return if (mediaItemId != null)
            handleCustomCommandForMediaItem(controller, customCommand, mediaItemId, args)
          else handleCustomCommand(controller, customCommand, args)
        }
        // [END handle_custom_command_for_media_item]
      }
  }

  @OptIn(UnstableApi::class)
  fun declareMaxNumberOfCommandButtons(context: Context, sessionToken: SessionToken) {
    // [START declare_max_commands_controller]
    val browserFuture =
      MediaBrowser.Builder(context, sessionToken).setMaxCommandsForMediaItems(3).buildAsync()
    // [END declare_max_commands_controller]
  }

  @OptIn(UnstableApi::class)
  fun getCommandButtonWithController(controller: MediaController, mediaItem: MediaItem) {
    // [START get_command_buttons_for_media_item]
    val commandButtonsForMediaItem = controller.getCommandButtonsForMediaItem(mediaItem)
    // [END get_command_buttons_for_media_item]
  }

  @OptIn(UnstableApi::class)
  fun sendingCustomCommandsForMediaItem(
    controller: MediaController,
    addToPlaylistButton: CommandButton,
    mediaItem: MediaItem,
  ) {
    // [START send_custom_command_for_media_item]
    val future =
      controller.sendCustomCommand(
        requireNotNull(addToPlaylistButton.sessionCommand),
        mediaItem,
        Bundle.EMPTY,
      )
    // [END send_custom_command_for_media_item]
  }
}
