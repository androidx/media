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
package androidx.media3.docsamples.exoplayer;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ima.ImaServerSideAdInsertionMediaSource;
import androidx.media3.exoplayer.ima.ImaServerSideAdInsertionUriBuilder;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ads.AdsLoader;
import androidx.media3.ui.PlayerView;

/** Snippets for the ad insertion developer guide. */
@SuppressWarnings({"unused", "EffectivelyPrivate"})
public final class AdInsertion {

  private AdInsertion() {}

  public static void createMediaItem(Uri videoUri, Uri adTagUri) {
    // [START create_media_item]
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(videoUri)
            .setAdsConfiguration(new MediaItem.AdsConfiguration.Builder(adTagUri).build())
            .build();
    // [END create_media_item]
  }

  public static void configurePlayer(
      Context context, AdsLoader.Provider adsLoaderProvider, PlayerView playerView) {
    // [START configure_player]
    MediaSource.Factory mediaSourceFactory =
        new DefaultMediaSourceFactory(context)
            .setLocalAdInsertionComponents(adsLoaderProvider, /* adViewProvider= */ playerView);
    ExoPlayer player =
        new ExoPlayer.Builder(context).setMediaSourceFactory(mediaSourceFactory).build();
    // [END configure_player]
  }

  public static void preparePlayerWithTwoItemsSameId(
      Uri firstVideoUri, Uri adTagUri, Uri secondVideoUri, Player player) {
    // [START prepare_player_with_two_items_same_id]
    // Build the media items, passing the same ads identifier for both items,
    // which means they share ad playback state so ads play only once.
    MediaItem firstItem =
        new MediaItem.Builder()
            .setUri(firstVideoUri)
            .setAdsConfiguration(
                new MediaItem.AdsConfiguration.Builder(adTagUri).setAdsId(adTagUri).build())
            .build();
    MediaItem secondItem =
        new MediaItem.Builder()
            .setUri(secondVideoUri)
            .setAdsConfiguration(
                new MediaItem.AdsConfiguration.Builder(adTagUri).setAdsId(adTagUri).build())
            .build();
    player.addMediaItem(firstItem);
    player.addMediaItem(secondItem);
    // [END prepare_player_with_two_items_same_id]
  }

  public static void buildPseudoAdInsertionTimeline(
      Uri preRollAdUri, Uri contentUri, Uri midRollAdUri, Player player) {
    // [START build_pseudo_ad_insertion_timeline]
    // A pre-roll ad.
    MediaItem preRollAd = MediaItem.fromUri(preRollAdUri);
    // The start of the content.
    MediaItem contentStart =
        new MediaItem.Builder()
            .setUri(contentUri)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setEndPositionMs(120_000).build())
            .build();
    // A mid-roll ad.
    MediaItem midRollAd = MediaItem.fromUri(midRollAdUri);
    // The rest of the content
    MediaItem contentEnd =
        new MediaItem.Builder()
            .setUri(contentUri)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(120_000).build())
            .build();

    // Build the playlist.
    player.addMediaItem(preRollAd);
    player.addMediaItem(contentStart);
    player.addMediaItem(midRollAd);
    player.addMediaItem(contentEnd);
    // [END build_pseudo_ad_insertion_timeline]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void setSsaiMediaSourceFactory(Context context, MediaSource.Factory ssaiFactory) {
    // [START set_ssai_media_source_factory]
    Player player =
        new ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(context)
                    .setServerSideAdInsertionMediaSourceFactory(ssaiFactory))
            .build();
    // [END set_ssai_media_source_factory]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void setupImaSsaiMediaSourceFactory(
      Context context, AdViewProvider adViewProvider) {
    // [START setup_ima_ssai_media_source_factory]
    // MediaSource.Factory to load the actual media stream.
    DefaultMediaSourceFactory defaultMediaSourceFactory = new DefaultMediaSourceFactory(context);
    // AdsLoader that can be reused for multiple playbacks.
    ImaServerSideAdInsertionMediaSource.AdsLoader adsLoader =
        new ImaServerSideAdInsertionMediaSource.AdsLoader.Builder(context, adViewProvider).build();
    // MediaSource.Factory to create the ad sources for the current player.
    ImaServerSideAdInsertionMediaSource.Factory adsMediaSourceFactory =
        new ImaServerSideAdInsertionMediaSource.Factory(adsLoader, defaultMediaSourceFactory);
    // Configure DefaultMediaSourceFactory to create both IMA DAI sources and
    // regular media sources. If you just play IMA DAI streams, you can also use
    // adsMediaSourceFactory directly.
    defaultMediaSourceFactory.setServerSideAdInsertionMediaSourceFactory(adsMediaSourceFactory);
    // Set the MediaSource.Factory on the Player.
    Player player =
        new ExoPlayer.Builder(context).setMediaSourceFactory(defaultMediaSourceFactory).build();
    // Set the player on the AdsLoader
    adsLoader.setPlayer(player);
    // [END setup_ima_ssai_media_source_factory]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void setSsaiUri(String assetKey, Player player) {
    // [START set_ssai_uri]
    Uri ssaiUri =
        new ImaServerSideAdInsertionUriBuilder()
            .setAssetKey(assetKey)
            .setFormat(C.CONTENT_TYPE_HLS)
            .build();
    player.setMediaItem(MediaItem.fromUri(ssaiUri));
    // [END set_ssai_uri]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void releaseSsaiAdsLoader(ImaServerSideAdInsertionMediaSource.AdsLoader adsLoader) {
    // [START release_ssai_ads_loader]
    adsLoader.release();
    // [END release_ssai_ads_loader]
  }
}
