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
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.source.MediaSource;

/** Snippets for DASH. */
@SuppressWarnings({"unused", "CheckReturnValue", "PrivateConstructorForUtilityClass"})
public class Dash {

  public static void createMediaItem(Context context, Uri dashUri) {
    // [START create_media_item]
    // Create a player instance.
    ExoPlayer player = new ExoPlayer.Builder(context).build();
    // Set the media item to be played.
    player.setMediaItem(MediaItem.fromUri(dashUri));
    // Prepare the player.
    player.prepare();
    // [END create_media_item]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void createMediaSource(Context context, Uri dashUri) {
    // [START create_media_source]
    DataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory();
    // Create a dash media source pointing to a dash manifest uri.
    MediaSource mediaSource =
        new DashMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(dashUri));
    // Create a player instance which gets an adaptive track selector by default.
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
              DashManifest dashManifest = (DashManifest) manifest;
              // Do something with the manifest.
            }
          }
        });
    // [END access_manifest]
  }
}
