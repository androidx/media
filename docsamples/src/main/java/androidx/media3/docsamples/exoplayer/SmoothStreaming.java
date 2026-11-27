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

import android.content.Context;
import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource;
import androidx.media3.exoplayer.smoothstreaming.manifest.SsManifest;
import androidx.media3.exoplayer.source.MediaSource;

/** Code snippets for the SmoothStreaming guide. */
@SuppressWarnings({
  "unused",
  "CheckReturnValue",
  "UnusedAnonymousClass",
  "PrivateConstructorForUtilityClass"
})
public final class SmoothStreaming {

  public static void createMediaItem(Context context, Uri ssUri) {
    // [START create_media_item]
    // Create a player instance.
    ExoPlayer player = new ExoPlayer.Builder(context).build();
    // Set the media item to be played.
    player.setMediaItem(MediaItem.fromUri(ssUri));
    // Prepare the player.
    player.prepare();
    // [END create_media_item]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void createMediaSource(Uri ssUri, Context context) {
    // [START create_media_source]
    // Create a data source factory.
    DataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory();
    // Create a SmoothStreaming media source pointing to a manifest uri.
    MediaSource mediaSource =
        new SsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(ssUri));
    // Create a player instance.
    ExoPlayer player = new ExoPlayer.Builder(context).build();
    // Set the media source to be played.
    player.setMediaSource(mediaSource);
    // Prepare the player.
    player.prepare();
    // [END create_media_source]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void accessManifest(Player player) {
    // [START access_manifest]
    player.addListener(
        new Player.Listener() {
          @Override
          public void onTimelineChanged(
              Timeline timeline, @Player.TimelineChangeReason int reason) {
            Object manifest = player.getCurrentManifest();
            if (manifest != null) {
              SsManifest ssManifest = (SsManifest) manifest;
              // Do something with the manifest.
            }
          }
        });
    // [END access_manifest]
  }

  private SmoothStreaming() {}
}
