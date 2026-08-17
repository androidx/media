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
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import javax.net.SocketFactory;

/** Code snippets for the RTSP guide. */
@SuppressWarnings({
  "unused",
  "CheckReturnValue",
  "UnusedAnonymousClass",
  "PrivateConstructorForUtilityClass"
})
public final class Rtsp {

  public static void createMediaItem(Context context, Uri rtspUri) {
    // [START create_media_item]
    // Create a player instance.
    ExoPlayer player = new ExoPlayer.Builder(context).build();
    // Set the media item to be played.
    player.setMediaItem(MediaItem.fromUri(rtspUri));
    // Prepare the player.
    player.prepare();
    // [END create_media_item]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void createMediaSource(Uri rtspUri, Context context) {
    // [START create_media_source]
    // Create an RTSP media source pointing to an RTSP uri.
    MediaSource mediaSource =
        new RtspMediaSource.Factory().createMediaSource(MediaItem.fromUri(rtspUri));
    // Create a player instance.
    ExoPlayer player = new ExoPlayer.Builder(context).build();
    // Set the media source to be played.
    player.setMediaSource(mediaSource);
    // Prepare the player.
    player.prepare();
    // [END create_media_source]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void createMediaSourceWithSocketFactory(SocketFactory socketFactory, Uri rtspUri) {
    // [START create_media_source_with_socket_factory]
    // Create an RTSP media source pointing to an RTSP uri and override the socket
    // factory.
    MediaSource mediaSource =
        new RtspMediaSource.Factory()
            .setSocketFactory(socketFactory)
            .createMediaSource(MediaItem.fromUri(rtspUri));
    // [END create_media_source_with_socket_factory]
  }

  private Rtsp() {}
}
