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

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ads.AdsLoader
import androidx.media3.ui.PlayerView

/** Code snippets for media sources. */
object MediaSourcesKt {

  @OptIn(UnstableApi::class)
  fun buildPlayerWithCustomMediaSourceFactory(
    context: Context,
    cacheDataSourceFactory: CacheDataSource.Factory,
    adsLoaderProvider: AdsLoader.Provider,
    playerView: PlayerView,
  ) {
    // [START build_player_with_custom_media_source_factory]
    val mediaSourceFactory: MediaSource.Factory =
      DefaultMediaSourceFactory(context)
        .setDataSourceFactory(cacheDataSourceFactory)
        .setLocalAdInsertionComponents(adsLoaderProvider, playerView)
    val player = ExoPlayer.Builder(context).setMediaSourceFactory(mediaSourceFactory).build()
    // [END build_player_with_custom_media_source_factory]
  }

  @OptIn(UnstableApi::class)
  fun useMediaSourceApi(
    exoPlayer: ExoPlayer,
    listOfMediaSources: List<MediaSource>,
    anotherMediaSource: MediaSource,
    videoUri: Uri,
  ) {
    // [START use_media_source_api]
    // Set a list of media sources as initial playlist.
    exoPlayer.setMediaSources(listOfMediaSources)
    // Add a single media source.
    exoPlayer.addMediaSource(anotherMediaSource)

    // Can be combined with the media item API.
    exoPlayer.addMediaItem(/* index= */ 3, MediaItem.fromUri(videoUri))

    exoPlayer.prepare()
    exoPlayer.play()
    // [END use_media_source_api]
  }
}
