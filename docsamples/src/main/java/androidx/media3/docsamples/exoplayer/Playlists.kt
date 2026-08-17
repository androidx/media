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

package androidx.media3.docsamples.exoplayer

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.MediaItemTransitionReason
import androidx.media3.common.Player.TimelineChangeReason
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder

// Code snippets for the Playlists guide.

object PlaylistsKt {

  fun setMediaItems(firstVideoUri: Uri, secondVideoUri: Uri, player: Player) {
    // [START set_media_items]
    // Build the media items.
    val firstItem = MediaItem.fromUri(firstVideoUri)
    val secondItem = MediaItem.fromUri(secondVideoUri)
    // Add the media items to be played.
    player.addMediaItem(firstItem)
    player.addMediaItem(secondItem)
    // Prepare the player.
    player.prepare()
    // Start the playback.
    player.play()
    // [END set_media_items]
  }

  fun modifyPlaylist(thirdUri: Uri, player: Player, newUri: Uri) {
    // [START modify_playlist]
    // Adds a media item at position 1 in the playlist.
    player.addMediaItem(/* index= */ 1, MediaItem.fromUri(thirdUri))
    // Moves the third media item from position 2 to the start of the playlist.
    player.moveMediaItem(/* currentIndex= */ 2, /* newIndex= */ 0)
    // Removes the first item from the playlist.
    player.removeMediaItem(/* index= */ 0)
    // Replace the second item in the playlist.
    player.replaceMediaItem(/* index= */ 1, MediaItem.fromUri(newUri))
    // [END modify_playlist]
  }

  fun replaceAndClearPlaylist(fourthUri: Uri, fifthUri: Uri, player: Player) {
    // [START replace_and_clear_playlist]
    // Replaces the playlist with a new one.
    val newItems: List<MediaItem> =
      listOf(MediaItem.fromUri(fourthUri), MediaItem.fromUri(fifthUri))
    player.setMediaItems(newItems, /* resetPosition= */ true)
    // Clears the playlist. If prepared, the player transitions to the ended state.
    player.clearMediaItems()
    // [END replace_and_clear_playlist]
  }

  @OptIn(UnstableApi::class)
  fun setCustomShuffleOrder(exoPlayer: ExoPlayer, randomSeed: Long) {
    // [START set_custom_shuffle_order]
    // Set a custom shuffle order for the 5 items currently in the playlist:
    exoPlayer.setShuffleOrder(DefaultShuffleOrder(intArrayOf(3, 1, 0, 4, 2), randomSeed))
    // Enable shuffle mode.
    exoPlayer.shuffleModeEnabled = true
    // [END set_custom_shuffle_order]
  }

  fun buildMediaItemWithMediaId(uri: Uri, mediaId: String) {
    // [START build_media_item_with_media_id]
    // Build a media item with a media ID.
    val mediaItem = MediaItem.Builder().setUri(uri).setMediaId(mediaId).build()
    // [END build_media_item_with_media_id]
  }

  fun buildMediaItemWithTag(uri: Uri, metadata: Any) {
    // [START build_media_item_with_tag]
    // Build a media item with a custom tag.
    val mediaItem = MediaItem.Builder().setUri(uri).setTag(metadata).build()
    // [END build_media_item_with_tag]
  }

  private fun updateUiForPlayingMediaItem(parameter: Any?) {}

  fun mediaItemTransitionListener() {
    object : Player.Listener {
      // [START media_item_transition_listener]
      override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        @MediaItemTransitionReason reason: Int,
      ) {
        updateUiForPlayingMediaItem(mediaItem)
      }
      // [END media_item_transition_listener]
    }
  }

  private class CustomMetadata

  fun mediaItemTransitionListenerWithMetadataTag() {
    object : Player.Listener {
      // [START media_item_transition_listener_with_metadata_tag]
      override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        @MediaItemTransitionReason reason: Int,
      ) {
        var metadata: CustomMetadata? = null
        mediaItem?.localConfiguration?.let { localConfiguration ->
          metadata = localConfiguration.tag as? CustomMetadata
        }
        updateUiForPlayingMediaItem(metadata)
      }
      // [END media_item_transition_listener_with_metadata_tag]
    }
  }

  private fun updateUiForPlaylist(timeline: Timeline) {}

  fun detectPlaylistChange() {
    object : Player.Listener {
      // [START detect_playlist_change]
      override fun onTimelineChanged(timeline: Timeline, @TimelineChangeReason reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
          // Update the UI according to the modified playlist (add, move or remove).
          updateUiForPlaylist(timeline)
        }
      }
      // [END detect_playlist_change]
    }
  }
}
