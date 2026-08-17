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
package androidx.media3.docsamples.exoplayer;

import static androidx.media3.common.Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Player.MediaItemTransitionReason;
import androidx.media3.common.Player.TimelineChangeReason;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder;
import com.google.common.collect.ImmutableList;

/** Code snippets for the Playlists guide. */
@SuppressWarnings({
  "unused",
  "CheckReturnValue",
  "UnusedAnonymousClass",
  "PrivateConstructorForUtilityClass"
})
public final class Playlists {

  public static void setMediaItems(Uri firstVideoUri, Uri secondVideoUri, Player player) {
    // [START set_media_items]
    // Build the media items.
    MediaItem firstItem = MediaItem.fromUri(firstVideoUri);
    MediaItem secondItem = MediaItem.fromUri(secondVideoUri);
    // Add the media items to be played.
    player.addMediaItem(firstItem);
    player.addMediaItem(secondItem);
    // Prepare the player.
    player.prepare();
    // Start the playback.
    player.play();
    // [END set_media_items]
  }

  public static void modifyPlaylist(Uri thirdUri, Player player, Uri newUri) {
    // [START modify_playlist]
    // Adds a media item at position 1 in the playlist.
    player.addMediaItem(/* index= */ 1, MediaItem.fromUri(thirdUri));
    // Moves the third media item from position 2 to the start of the playlist.
    player.moveMediaItem(/* currentIndex= */ 2, /* newIndex= */ 0);
    // Removes the first item from the playlist.
    player.removeMediaItem(/* index= */ 0);
    // Replace the second item in the playlist.
    player.replaceMediaItem(/* index= */ 1, MediaItem.fromUri(newUri));
    // [END modify_playlist]
  }

  public static void replaceAndClearPlaylist(Uri fourthUri, Uri fifthUri, Player player) {
    // [START replace_and_clear_playlist]
    // Replaces the playlist with a new one.
    ImmutableList<MediaItem> newItems =
        ImmutableList.of(MediaItem.fromUri(fourthUri), MediaItem.fromUri(fifthUri));
    player.setMediaItems(newItems, /* resetPosition= */ true);
    // Clears the playlist. If prepared, the player transitions to the ended state.
    player.clearMediaItems();
    // [END replace_and_clear_playlist]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void setCustomShuffleOrder(ExoPlayer exoPlayer, int randomSeed) {
    // [START set_custom_shuffle_order]
    // Set a custom shuffle order for the 5 items currently in the playlist:
    exoPlayer.setShuffleOrder(new DefaultShuffleOrder(new int[] {3, 1, 0, 4, 2}, randomSeed));
    // Enable shuffle mode.
    exoPlayer.setShuffleModeEnabled(/* shuffleModeEnabled= */ true);
    // [END set_custom_shuffle_order]
  }

  public static void buildMediaItemWithMediaId(Uri uri, String mediaId) {
    // [START build_media_item_with_media_id]
    // Build a media item with a media ID.
    MediaItem mediaItem = new MediaItem.Builder().setUri(uri).setMediaId(mediaId).build();
    // [END build_media_item_with_media_id]
  }

  public static void buildMediaItemWithTag(Uri uri, Object metadata) {
    // [START build_media_item_with_tag]
    // Build a media item with a custom tag.
    MediaItem mediaItem = new MediaItem.Builder().setUri(uri).setTag(metadata).build();
    // [END build_media_item_with_tag]
  }

  private static void updateUiForPlayingMediaItem(Object parameter) {}

  public static void mediaItemTransitionListener() {
    new Player.Listener() {
      // [START media_item_transition_listener]
      @Override
      public void onMediaItemTransition(
          @Nullable MediaItem mediaItem, @MediaItemTransitionReason int reason) {
        updateUiForPlayingMediaItem(mediaItem);
      }
      // [END media_item_transition_listener]
    };
  }

  private static final class CustomMetadata {}

  public static void mediaItemTransitionListenerWithMetadataTag() {
    new Player.Listener() {
      // [START media_item_transition_listener_with_metadata_tag]
      @Override
      public void onMediaItemTransition(
          @Nullable MediaItem mediaItem, @MediaItemTransitionReason int reason) {
        @Nullable CustomMetadata metadata = null;
        if (mediaItem != null && mediaItem.localConfiguration != null) {
          metadata = (CustomMetadata) mediaItem.localConfiguration.tag;
        }
        updateUiForPlayingMediaItem(metadata);
      }
      // [END media_item_transition_listener_with_metadata_tag]
    };
  }

  private static void updateUiForPlaylist(Timeline timeline) {}

  public static void detectPlaylistChange() {
    new Player.Listener() {
      // [START detect_playlist_change]
      @Override
      public void onTimelineChanged(Timeline timeline, @TimelineChangeReason int reason) {
        if (reason == TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
          // Update the UI according to the modified playlist (add, move or remove).
          updateUiForPlaylist(timeline);
        }
      }
      // [END detect_playlist_change]
    };
  }

  private Playlists() {}
}
