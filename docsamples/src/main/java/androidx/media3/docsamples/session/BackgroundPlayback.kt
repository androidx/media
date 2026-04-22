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

import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.ConnectionResult.AcceptedResultBuilder
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import com.google.common.util.concurrent.Futures.immediateFuture
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture

/** Snippets for background-playback.md. */
object BackgroundPlaybackKt {

  // [START implement_media_session_service]
  class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    // Create your Player and MediaSession in the onCreate lifecycle event
    override fun onCreate() {
      super.onCreate()
      val player = ExoPlayer.Builder(this).build()
      mediaSession = MediaSession.Builder(this, player).build()
    }

    // Remember to release the player and media session in onDestroy
    override fun onDestroy() {
      mediaSession?.run {
        player.release()
        release()
        mediaSession = null
      }
      super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
      mediaSession
  }

  // [END implement_media_session_service]

  abstract class PlaybackServiceWithTaskRemoved : MediaSessionService() {

    // [START on_task_removed]
    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
      pauseAllPlayersAndStopSelf()
    }
    // [END on_task_removed]
  }

  object PlaybackService2 {
    private var mediaSession: MediaSession? = null

    // [START on_get_session]
    class PlaybackService : MediaSessionService() {

      // [...] lifecycle methods omitted

      override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession
    }
    // [END on_get_session]
  }

  fun buildMediaItemWithMetadata(mediaUri: Uri, artworkUri: Uri, mediaController: MediaController) {
    // [START build_media_item_with_metadata]
    val mediaItem =
      MediaItem.Builder()
        .setMediaId("media-1")
        .setUri(mediaUri)
        .setMediaMetadata(
          MediaMetadata.Builder()
            .setArtist("David Bowie")
            .setTitle("Heroes")
            .setArtworkUri(artworkUri)
            .build()
        )
        .build()

    mediaController.setMediaItem(mediaItem)
    mediaController.prepare()
    mediaController.play()
    // [END build_media_item_with_metadata]
  }

  @OptIn(UnstableApi::class)
  class PlaybackResumptionCallback : MediaSession.Callback {
    // [START implement_playback_resumption]
    override fun onPlaybackResumption(
      mediaSession: MediaSession,
      controller: MediaSession.ControllerInfo,
      isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
      val settableFuture = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
      settableFuture.addListener(
        {
          // Your app is responsible for storing the playlist, metadata (like title
          // and artwork) of the current item and the start position to use here.
          val resumptionPlaylist = restorePlaylist()
          settableFuture.set(resumptionPlaylist)
        },
        MoreExecutors.directExecutor(),
      )
      return settableFuture
    }

    // [END implement_playback_resumption]

    private fun restorePlaylist(): MediaSession.MediaItemsWithStartPosition? = null
  }

  @OptIn(UnstableApi::class)
  class MediaNotificationCallback(
    private val seekBackButton: CommandButton,
    private val seekForwardButton: CommandButton,
  ) : MediaSession.Callback {
    // [START media_notification_controller]
    override fun onConnectAsync(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
    ): ListenableFuture<ConnectionResult> {
      if (session.isMediaNotificationController(controller)) {
        val playerCommands =
          ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            .remove(COMMAND_SEEK_TO_PREVIOUS)
            .remove(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .remove(COMMAND_SEEK_TO_NEXT)
            .remove(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .build()
        // Custom button preferences and commands to configure the platform session.
        return immediateFuture(
          AcceptedResultBuilder(session)
            .setMediaButtonPreferences(listOf(seekBackButton, seekForwardButton))
            .setAvailablePlayerCommands(playerCommands)
            .build()
        )
      }
      // Default commands with default button preferences for all other controllers.
      return immediateFuture(AcceptedResultBuilder(session).build())
    }
    // [END media_notification_controller]
  }

  @OptIn(UnstableApi::class)
  class AutoCompanionCallback(private val customCommand: SessionCommand) : MediaSession.Callback {
    // [START auto_companion_controller]
    override fun onConnectAsync(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
    ): ListenableFuture<ConnectionResult> {
      val sessionCommands =
        ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(customCommand).build()
      if (session.isMediaNotificationController(controller)) {
        // ... See above.
      } else if (session.isAutoCompanionController(controller)) {
        // Available commands to accept incoming custom commands from Auto.
        return immediateFuture(
          AcceptedResultBuilder(session).setAvailableSessionCommands(sessionCommands).build()
        )
      }
      // Default commands for all other controllers.
      return immediateFuture(AcceptedResultBuilder(session).build())
    }
    // [END auto_companion_controller]
  }
}
