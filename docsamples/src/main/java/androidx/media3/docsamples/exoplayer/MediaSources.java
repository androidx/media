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
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ads.AdsLoader;
import androidx.media3.ui.PlayerView;
import java.util.List;

/** Code snippets for media sources. */
@SuppressWarnings({
  "unused",
  "CheckReturnValue",
  "UnusedAnonymousClass",
  "PrivateConstructorForUtilityClass"
})
public final class MediaSources {

  @OptIn(markerClass = UnstableApi.class)
  public static void buildPlayerWithCustomMediaSourceFactory(
      Context context,
      CacheDataSource.Factory cacheDataSourceFactory,
      AdsLoader.Provider adsLoaderProvider,
      PlayerView playerView) {
    // [START build_player_with_custom_media_source_factory]
    MediaSource.Factory mediaSourceFactory =
        new DefaultMediaSourceFactory(context)
            .setDataSourceFactory(cacheDataSourceFactory)
            .setLocalAdInsertionComponents(adsLoaderProvider, /* adViewProvider= */ playerView);
    ExoPlayer player =
        new ExoPlayer.Builder(context).setMediaSourceFactory(mediaSourceFactory).build();
    // [END build_player_with_custom_media_source_factory]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void useMediaSourceApi(
      ExoPlayer exoPlayer,
      List<MediaSource> listOfMediaSources,
      MediaSource anotherMediaSource,
      Uri videoUri) {
    // [START use_media_source_api]
    // Set a list of media sources as initial playlist.
    exoPlayer.setMediaSources(listOfMediaSources);
    // Add a single media source.
    exoPlayer.addMediaSource(anotherMediaSource);

    // Can be combined with the media item API.
    exoPlayer.addMediaItem(/* index= */ 3, MediaItem.fromUri(videoUri));

    exoPlayer.prepare();
    exoPlayer.play();
    // [END use_media_source_api]
  }
}
