/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
import androidx.media3.common.AdViewProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ima.ImaServerSideAdInsertionMediaSource
import androidx.media3.exoplayer.ima.ImaServerSideAdInsertionUriBuilder
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ads.AdsLoader
import androidx.media3.ui.PlayerView

// Snippets for the ad insertion developer guide.

object AdInsertionKt {

  fun createMediaItem(videoUri: Uri, adTagUri: Uri) {
    // [START create_media_item]
    val mediaItem =
      MediaItem.Builder()
        .setUri(videoUri)
        .setAdsConfiguration(MediaItem.AdsConfiguration.Builder(adTagUri).build())
        .build()
    // [END create_media_item]
  }

  fun configurePlayer(
    context: Context,
    adsLoaderProvider: AdsLoader.Provider,
    playerView: PlayerView,
  ) {
    // [START configure_player]
    val mediaSourceFactory: MediaSource.Factory =
      DefaultMediaSourceFactory(context)
        .setLocalAdInsertionComponents(adsLoaderProvider, playerView)
    val player = ExoPlayer.Builder(context).setMediaSourceFactory(mediaSourceFactory).build()
    // [END configure_player]
  }

  fun preparePlayerWithTwoItemsSameId(
    firstVideoUri: Uri,
    adTagUri: Uri,
    secondVideoUri: Uri,
    player: Player,
  ) {
    // [START prepare_player_with_two_items_same_id]
    // Build the media items, passing the same ads identifier for both items,
    // which means they share ad playback state so ads play only once.
    val firstItem =
      MediaItem.Builder()
        .setUri(firstVideoUri)
        .setAdsConfiguration(
          MediaItem.AdsConfiguration.Builder(adTagUri).setAdsId(adTagUri).build()
        )
        .build()
    val secondItem =
      MediaItem.Builder()
        .setUri(secondVideoUri)
        .setAdsConfiguration(
          MediaItem.AdsConfiguration.Builder(adTagUri).setAdsId(adTagUri).build()
        )
        .build()
    player.addMediaItem(firstItem)
    player.addMediaItem(secondItem)
    // [END prepare_player_with_two_items_same_id]
  }

  fun buildPseudoAdInsertionTimeline(
    preRollAdUri: Uri,
    contentUri: Uri,
    midRollAdUri: Uri,
    player: Player,
  ) {
    // [START build_pseudo_ad_insertion_timeline]
    // A pre-roll ad.
    val preRollAd = MediaItem.fromUri(preRollAdUri)
    // The start of the content.
    val contentStart =
      MediaItem.Builder()
        .setUri(contentUri)
        .setClippingConfiguration(
          MediaItem.ClippingConfiguration.Builder().setEndPositionMs(120000).build()
        )
        .build()
    // A mid-roll ad.
    val midRollAd = MediaItem.fromUri(midRollAdUri)
    // The rest of the content
    val contentEnd =
      MediaItem.Builder()
        .setUri(contentUri)
        .setClippingConfiguration(
          MediaItem.ClippingConfiguration.Builder().setStartPositionMs(120000).build()
        )
        .build()

    // Build the playlist.
    player.addMediaItem(preRollAd)
    player.addMediaItem(contentStart)
    player.addMediaItem(midRollAd)
    player.addMediaItem(contentEnd)
    // [END build_pseudo_ad_insertion_timeline]
  }

  @OptIn(UnstableApi::class)
  fun setSsaiMediaSourceFactory(context: Context, ssaiFactory: MediaSource.Factory) {
    // [START set_ssai_media_source_factory]
    val player =
      ExoPlayer.Builder(context)
        .setMediaSourceFactory(
          DefaultMediaSourceFactory(context).setServerSideAdInsertionMediaSourceFactory(ssaiFactory)
        )
        .build()
    // [END set_ssai_media_source_factory]
  }

  @OptIn(UnstableApi::class)
  fun setupImaSsaiMediaSourceFactory(context: Context, adViewProvider: AdViewProvider) {
    // [START setup_ima_ssai_media_source_factory]
    // MediaSource.Factory to load the actual media stream.
    val defaultMediaSourceFactory = DefaultMediaSourceFactory(context)
    // AdsLoader that can be reused for multiple playbacks.
    val adsLoader =
      ImaServerSideAdInsertionMediaSource.AdsLoader.Builder(context, adViewProvider).build()
    // MediaSource.Factory to create the ad sources for the current player.
    val adsMediaSourceFactory =
      ImaServerSideAdInsertionMediaSource.Factory(adsLoader, defaultMediaSourceFactory)
    // Configure DefaultMediaSourceFactory to create both IMA DAI sources and
    // regular media sources. If you just play IMA DAI streams, you can also use
    // adsMediaSourceFactory directly.
    defaultMediaSourceFactory.setServerSideAdInsertionMediaSourceFactory(adsMediaSourceFactory)
    // Set the MediaSource.Factory on the Player.
    val player = ExoPlayer.Builder(context).setMediaSourceFactory(defaultMediaSourceFactory).build()
    // Set the player on the AdsLoader
    adsLoader.setPlayer(player)
    // [END setup_ima_ssai_media_source_factory]
  }

  @OptIn(UnstableApi::class)
  fun setSsaiUri(assetKey: String, player: Player) {
    // [START set_ssai_uri]
    val ssaiUri =
      ImaServerSideAdInsertionUriBuilder()
        .setAssetKey(assetKey)
        .setFormat(C.CONTENT_TYPE_HLS)
        .build()
    player.setMediaItem(MediaItem.fromUri(ssaiUri))
    // [END set_ssai_uri]
  }

  @OptIn(UnstableApi::class)
  fun releaseSsaiAdsLoader(adsLoader: ImaServerSideAdInsertionMediaSource.AdsLoader) {
    // [START release_ssai_ads_loader]
    adsLoader.release()
    // [END release_ssai_ads_loader]
  }
}
