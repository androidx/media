/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.demo.session

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.session.service.R
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future

/** A [MediaLibraryService.MediaLibrarySession.Callback] implementation. */
open class DemoMediaLibrarySessionCallback(val service: DemoPlaybackService) :
  MediaLibraryService.MediaLibrarySession.Callback {

  init {
    MediaItemTree.initialize(service.assets)
  }

  private val commandButtons: List<CommandButton> =
    listOf(
      CommandButton.Builder(CommandButton.ICON_SHUFFLE_OFF)
        .setDisplayName(service.getString(R.string.exo_controls_shuffle_on_description))
        .setSessionCommand(SessionCommand(CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON, Bundle.EMPTY))
        .build(),
      CommandButton.Builder(CommandButton.ICON_SHUFFLE_ON)
        .setDisplayName(service.getString(R.string.exo_controls_shuffle_off_description))
        .setSessionCommand(SessionCommand(CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF, Bundle.EMPTY))
        .build(),
    )

  @OptIn(UnstableApi::class) // MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
  private val sessionCommandsWithCustomCommands =
    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
      .also { builder ->
        // Put all custom session commands in the list that may be used by the notification.
        commandButtons.forEach { commandButton ->
          commandButton.sessionCommand?.let { builder.add(it) }
        }
      }
      .build()

  @OptIn(UnstableApi::class) // Player.Commands.Builder
  private val restrictedAccessPlayerCommands =
    Player.Commands.Builder()
      .addAll(
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        Player.COMMAND_GET_TRACKS,
        Player.COMMAND_GET_METADATA,
      )
      .build()

  @OptIn(UnstableApi::class) // ConnectionResult.AcceptedResultBuilder
  override fun onConnect(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
  ): MediaSession.ConnectionResult {
    if (controller.isTrusted) {
      // Provide full information and specify media button preferences.
      val customButton = commandButtons[if (session.player.shuffleModeEnabled) 1 else 0]
      return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
        .setAvailableSessionCommands(sessionCommandsWithCustomCommands)
        .setMediaButtonPreferences(ImmutableList.of(customButton))
        .build()
    } else {
      // Restricted read-only view on currently playing item only.
      return MediaSession.ConnectionResult.accept(
        SessionCommands.EMPTY,
        restrictedAccessPlayerCommands,
      )
    }
  }

  @OptIn(UnstableApi::class) // MediaSession.isMediaNotificationController
  override fun onCustomCommand(
    session: MediaSession,
    controller: MediaSession.ControllerInfo,
    customCommand: SessionCommand,
    args: Bundle,
  ): ListenableFuture<SessionResult> {
    if (CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON == customCommand.customAction) {
      // Enable shuffling.
      session.player.shuffleModeEnabled = true
      // Change the media button preferences to contain the `Disable shuffling` button.
      session.setMediaButtonPreferences(ImmutableList.of(commandButtons[1]))
      return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    } else if (CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF == customCommand.customAction) {
      // Disable shuffling.
      session.player.shuffleModeEnabled = false
      // Change the media button preferences to contain the `Enable shuffling` button.
      session.setMediaButtonPreferences(ImmutableList.of(commandButtons[0]))
      return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }
    return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
  }

  override fun onGetLibraryRoot(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    params: MediaLibraryService.LibraryParams?,
  ): ListenableFuture<LibraryResult<MediaItem>> {
    return Futures.immediateFuture(LibraryResult.ofItem(MediaItemTree.getRootItem(), params))
  }

  @OptIn(UnstableApi::class) // SessionError.ERROR_BAD_VALUE
  override fun onGetItem(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    mediaId: String,
  ): ListenableFuture<LibraryResult<MediaItem>> {
    MediaItemTree.getItem(mediaId)?.let {
      return Futures.immediateFuture(LibraryResult.ofItem(it, /* params= */ null))
    }
    return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
  }

  @OptIn(UnstableApi::class) // SessionError.ERROR_BAD_VALUE
  override fun onGetChildren(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    parentId: String,
    page: Int,
    pageSize: Int,
    params: MediaLibraryService.LibraryParams?,
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    val children = MediaItemTree.getChildren(parentId)
    if (children.isNotEmpty()) {
      return Futures.immediateFuture(LibraryResult.ofItemList(children, params))
    }
    return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
  }

  override fun onAddMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: List<MediaItem>,
  ): ListenableFuture<List<MediaItem>> {
    return Futures.immediateFuture(resolveMediaItems(mediaItems))
  }

  @OptIn(UnstableApi::class) // MediaSession.MediaItemsWithStartPosition
  override fun onSetMediaItems(
    mediaSession: MediaSession,
    browser: MediaSession.ControllerInfo,
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ): ListenableFuture<MediaItemsWithStartPosition> {
    if (mediaItems.size == 1) {
      // Try to expand a single item to a playlist.
      maybeExpandSingleItemToPlaylist(mediaItems.first(), startIndex, startPositionMs)?.also {
        return Futures.immediateFuture(it)
      }
    }
    return Futures.immediateFuture(
      MediaItemsWithStartPosition(resolveMediaItems(mediaItems), startIndex, startPositionMs)
    )
  }

  @OptIn(UnstableApi::class) // onPlaybackResumption callback + MediaItemsWithStartPosition
  override fun onPlaybackResumption(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    isForPlayback: Boolean,
  ): ListenableFuture<MediaItemsWithStartPosition> {
    return CoroutineScope(Dispatchers.Unconfined).future {
      service.retrieveLastStoredMediaItem()?.apply {
        if (isForPlayback) {
          maybeExpandSingleItemToPlaylist(
              mediaItem = MediaItem.Builder().setMediaId(this.mediaId).build(),
              startIndex = 0,
              startPositionMs = this.positionMs,
            )
            ?.also {
              return@future it
            }
        } else {
          MediaItemTree.getItem(this.mediaId)?.also {
            var extras: Bundle? = null
            if (this.durationMs != C.TIME_UNSET) {
              extras = Bundle()
              extras.putInt(
                MediaConstants.EXTRAS_KEY_COMPLETION_STATUS,
                MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED,
              )
              extras.putDouble(
                MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE,
                max(0.0, min(1.0, this.positionMs.toDouble() / this.durationMs)),
              )
            }
            val updatedItem =
              it
                .buildUpon()
                .setMediaMetadata(
                  it.mediaMetadata
                    .buildUpon()
                    .setArtworkUri(this.artworkOriginalUri.toUri())
                    .setArtworkData(
                      this.artworkData.toByteArray(),
                      MediaMetadata.PICTURE_TYPE_MEDIA,
                    )
                    .setExtras(extras)
                    .build()
                )
                .build()
            return@future MediaItemsWithStartPosition(
              listOf(updatedItem),
              /* startIndex= */ 0,
              /* startPositionMs= */ C.TIME_UNSET,
            )
          }
        }
      }
      throw IllegalStateException("previous media id not found")
    }
  }

  private fun resolveMediaItems(mediaItems: List<MediaItem>): List<MediaItem> {
    val playlist = mutableListOf<MediaItem>()
    mediaItems.forEach { mediaItem ->
      if (mediaItem.mediaId.isNotEmpty()) {
        MediaItemTree.expandItem(mediaItem)?.let { playlist.add(it) }
      } else if (mediaItem.requestMetadata.searchQuery != null) {
        playlist.addAll(MediaItemTree.search(mediaItem.requestMetadata.searchQuery!!))
      }
    }
    return playlist
  }

  @OptIn(UnstableApi::class) // MediaSession.MediaItemsWithStartPosition
  private fun maybeExpandSingleItemToPlaylist(
    mediaItem: MediaItem,
    startIndex: Int,
    startPositionMs: Long,
  ): MediaItemsWithStartPosition? {
    var playlist = listOf<MediaItem>()
    var indexInPlaylist = startIndex
    MediaItemTree.getItem(mediaItem.mediaId)?.apply {
      if (mediaMetadata.isBrowsable == true) {
        // Get children browsable item.
        playlist = MediaItemTree.getChildren(mediaId)
      } else if (requestMetadata.searchQuery == null) {
        // Try to get the parent and its children.
        MediaItemTree.getParentId(mediaId)?.let {
          playlist =
            MediaItemTree.getChildren(it).map { childItem ->
              if (childItem.mediaId == mediaId) MediaItemTree.expandItem(mediaItem)!! else childItem
            }
          indexInPlaylist = MediaItemTree.getIndexInMediaItems(mediaId, playlist)
        }
      }
    }
    if (playlist.isNotEmpty()) {
      return MediaItemsWithStartPosition(playlist, indexInPlaylist, startPositionMs)
    }
    return null
  }

  override fun onSearch(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    params: MediaLibraryService.LibraryParams?,
  ): ListenableFuture<LibraryResult<Void>> {
    session.notifySearchResultChanged(browser, query, MediaItemTree.search(query).size, params)
    return Futures.immediateFuture(LibraryResult.ofVoid())
  }

  override fun onGetSearchResult(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    page: Int,
    pageSize: Int,
    params: MediaLibraryService.LibraryParams?,
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    return Futures.immediateFuture(LibraryResult.ofItemList(MediaItemTree.search(query), params))
  }

  companion object {
    private const val CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_ON =
      "android.media3.session.demo.SHUFFLE_ON"
    private const val CUSTOM_COMMAND_TOGGLE_SHUFFLE_MODE_OFF =
      "android.media3.session.demo.SHUFFLE_OFF"
  }
}
