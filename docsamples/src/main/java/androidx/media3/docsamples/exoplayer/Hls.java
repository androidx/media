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

import static com.google.common.base.Preconditions.checkNotNull;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaItem.AdsConfiguration;
import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader;
import androidx.media3.exoplayer.hls.HlsManifest;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;
import java.util.List;

/** Snippets for HLS. */
@SuppressWarnings({"unused", "CheckReturnValue", "PrivateConstructorForUtilityClass"})
public class Hls {

  public static void hlsCreateMediaItem(Context context, Uri hlsUri) {
    // [START hls_create_media_item]
    // Create a player instance.
    ExoPlayer player = new ExoPlayer.Builder(context).build();
    // Set the media item to be played.
    player.setMediaItem(MediaItem.fromUri(hlsUri));
    // Prepare the player.
    player.prepare();
    // [END hls_create_media_item]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void hlsCreateMediaSource(Uri hlsUri, Context context) {
    // [START hls_create_media_source]
    // Create a data source factory.
    DataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory();
    // Create a HLS media source pointing to a playlist uri.
    HlsMediaSource hlsMediaSource =
        new HlsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(hlsUri));
    // Create a player instance.
    ExoPlayer player = new ExoPlayer.Builder(context).build();
    // Set the HLS media source as the playlist with a single media item.
    player.setMediaSource(hlsMediaSource);
    // Prepare the player.
    player.prepare();
    // [END hls_create_media_source]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void hlsAccessManifest(Player player) {
    // [START hls_access_manifest]
    player.addListener(
        new Player.Listener() {
          @Override
          public void onTimelineChanged(
              Timeline timeline, @Player.TimelineChangeReason int reason) {
            Object manifest = player.getCurrentManifest();
            if (manifest != null) {
              HlsManifest hlsManifest = (HlsManifest) manifest;
              // Do something with the manifest.
            }
          }
        });
    // [END hls_access_manifest]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void hlsDisableChunklessPreparation(
      DataSource.Factory dataSourceFactory, Uri hlsUri) {
    // [START hls_disable_chunkless_preparation]
    HlsMediaSource hlsMediaSource =
        new HlsMediaSource.Factory(dataSourceFactory)
            .setAllowChunklessPreparation(false)
            .createMediaSource(MediaItem.fromUri(hlsUri));
    // [END hls_disable_chunkless_preparation]
  }

  @OptIn(markerClass = UnstableApi.class)
  private void useHlsInterstitialsAdsLoaderWithMediaSourceApi(
      PlayerView playerView, Context context) {
    // [START hls_interstitials_setup]
    HlsInterstitialsAdsLoader hlsInterstitialsAdsLoader = new HlsInterstitialsAdsLoader(context);
    // Create a MediaSource.Factory for HLS streams with interstitials.
    MediaSource.Factory hlsMediaSourceFactory =
        new HlsInterstitialsAdsLoader.AdsMediaSourceFactory(
            hlsInterstitialsAdsLoader, playerView, new DefaultMediaSourceFactory(context));

    // Build player with interstitials media source factory
    ExoPlayer player =
        new ExoPlayer.Builder(context).setMediaSourceFactory(hlsMediaSourceFactory).build();

    // Set the player on the ads loader.
    hlsInterstitialsAdsLoader.setPlayer(player);
    playerView.setPlayer(player);
    // [END hls_interstitials_setup]

    // [START hls_interstitials_playlist]
    // Build an HLS media item with ads configuration to be played.
    player.setMediaItem(
        new MediaItem.Builder()
            .setUri("https://www.example.com/media.m3u8")
            .setAdsConfiguration(
                new AdsConfiguration.Builder(Uri.parse("hls://interstitials"))
                    .setAdsId("ad-tag-0") // must be unique within playlist
                    .build())
            .build());
    player.prepare();
    player.play();
    // [END hls_interstitials_playlist]
  }

  @OptIn(markerClass = UnstableApi.class)
  private void useHlsInterstitialsAdsLoaderDirectly(PlayerView playerView, Context context) {
    // [START hls_interstitials_media_source]
    HlsInterstitialsAdsLoader hlsInterstitialsAdsLoader = new HlsInterstitialsAdsLoader(context);
    // Create a MediaSource.Factory for HLS streams with interstitials.
    MediaSource.Factory hlsMediaSourceFactory =
        new HlsInterstitialsAdsLoader.AdsMediaSourceFactory(
            hlsInterstitialsAdsLoader, playerView, context);

    // Build player with default media source factory.
    ExoPlayer player = new ExoPlayer.Builder(context).build();

    // Create an media source from an HLS media item with ads configuration.
    MediaSource mediaSource =
        hlsMediaSourceFactory.createMediaSource(
            new MediaItem.Builder()
                .setUri("https://www.example.com/media.m3u8")
                .setAdsConfiguration(
                    new MediaItem.AdsConfiguration.Builder(Uri.parse("hls://interstitials"))
                        .setAdsId("ad-tag-0")
                        .build())
                .build());

    // Set the media source on the player.
    player.setMediaSource(mediaSource);
    player.prepare();
    player.play();
    // [END hls_interstitials_media_source]
  }

  @OptIn(markerClass = UnstableApi.class)
  private void useHlsInterstitialsAdsLoaderListener(
      HlsInterstitialsAdsLoader hlsInterstitialsAdsLoader) {
    // [START hls_interstitials_add_listener]
    AdsLoaderListener listener = new AdsLoaderListener();
    // Add the listener to the ads loader to receive ad loader events.
    hlsInterstitialsAdsLoader.addListener(listener);
    // [END hls_interstitials_add_listener]
  }

  // [START hls_interstitials_listener]
  @OptIn(markerClass = UnstableApi.class)
  private static class AdsLoaderListener implements HlsInterstitialsAdsLoader.Listener {

    // implement HlsInterstitialsAdsLoader.Listener
    @Override
    public void onStart(MediaItem mediaItem, Object adsId, AdViewProvider adViewProvider) {
      // Do something when HLS media item with interstitials is started.
    }

    @Override
    public void onMetadata(
        MediaItem mediaItem,
        Object adsId,
        int adGroupIndex,
        int adIndexInAdGroup,
        Metadata metadata) {
      // Do something with metadata that is emitted by the ad media source of the given ad.
    }

    @Override
    public void onAdCompleted(
        MediaItem mediaItem, Object adsId, int adGroupIndex, int adIndexInAdGroup) {
      // Do something when ad completed playback.
    }

    // ... See JavaDoc for further callbacks

    @Override
    public void onStop(MediaItem mediaItem, Object adsId, AdPlaybackState adPlaybackState) {
      // Do something with the resulting ad playback state when stopped.
    }
  }

  // [END hls_interstitials_listener]

  /** Example activity */
  // [START hls_interstitials_activity]
  @OptIn(markerClass = UnstableApi.class)
  public static class HlsInterstitialsActivity extends Activity {

    public static final String ADS_RESUMPTION_STATE_KEY = "ads_resumption_state";

    @Nullable private HlsInterstitialsAdsLoader hlsInterstitialsAdsLoader;
    @Nullable private PlayerView playerView;
    @Nullable private ExoPlayer player;

    private List<HlsInterstitialsAdsLoader.AdsResumptionState> adsResumptionStates;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);
      // Create the ads loader instance.
      hlsInterstitialsAdsLoader = new HlsInterstitialsAdsLoader(this);
      // Restore ad resumption states.
      if (savedInstanceState != null) {
        ArrayList<Bundle> bundles =
            savedInstanceState.getParcelableArrayList(ADS_RESUMPTION_STATE_KEY);
        if (bundles != null) {
          adsResumptionStates = new ArrayList<>();
          for (Bundle bundle : bundles) {
            adsResumptionStates.add(
                HlsInterstitialsAdsLoader.AdsResumptionState.fromBundle(bundle));
          }
        }
      }
    }

    @Override
    protected void onStart() {
      super.onStart();
      // Build a player and set it on the ads loader.
      initializePlayer();
      // Add any stored ad resumption states to the ads loader.
      if (adsResumptionStates != null) {
        for (HlsInterstitialsAdsLoader.AdsResumptionState state : adsResumptionStates) {
          hlsInterstitialsAdsLoader.addAdResumptionState(state);
        }
        adsResumptionStates = null; // Consume the states
      }
    }

    @Override
    protected void onStop() {
      super.onStop();
      // Get ad resumption states before releasing the player.
      if (hlsInterstitialsAdsLoader != null) {
        adsResumptionStates = hlsInterstitialsAdsLoader.getAdsResumptionStates();
      }
      releasePlayer();
    }

    @Override
    protected void onDestroy() {
      // Release the ads loader when not used anymore.
      if (hlsInterstitialsAdsLoader != null) {
        hlsInterstitialsAdsLoader.release();
        hlsInterstitialsAdsLoader = null;
      }
      super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
      super.onSaveInstanceState(outState);
      // Store the ad resumption states.
      if (adsResumptionStates != null) {
        ArrayList<Bundle> bundles = new ArrayList<>();
        for (HlsInterstitialsAdsLoader.AdsResumptionState state : adsResumptionStates) {
          bundles.add(state.toBundle());
        }
        outState.putParcelableArrayList(ADS_RESUMPTION_STATE_KEY, bundles);
      }
    }

    private void initializePlayer() {
      if (player == null) {
        // Create a media source factory for HLS streams.
        MediaSource.Factory hlsMediaSourceFactory =
            new HlsInterstitialsAdsLoader.AdsMediaSourceFactory(
                checkNotNull(hlsInterstitialsAdsLoader), playerView, /* context= */ this);
        // Build player with interstitials media source
        player =
            new ExoPlayer.Builder(/* context= */ this)
                .setMediaSourceFactory(hlsMediaSourceFactory)
                .build();
        // Set the player on the ads loader.
        hlsInterstitialsAdsLoader.setPlayer(player);
        playerView.setPlayer(player);
      }

      // Use a media item with an HLS stream URI, an ad tag URI and ads ID.
      player.setMediaItem(
          new MediaItem.Builder()
              .setUri("https://www.example.com/media.m3u8")
              .setMimeType(MimeTypes.APPLICATION_M3U8)
              .setAdsConfiguration(
                  new MediaItem.AdsConfiguration.Builder(Uri.parse("hls://interstitials"))
                      .setAdsId("ad-tag-0") // must be unique within ExoPlayer playlist
                      .build())
              .build());
      player.prepare();
      player.play();
    }

    private void releasePlayer() {
      if (player != null) {
        player.release();
        player = null;
      }
      if (hlsInterstitialsAdsLoader != null) {
        hlsInterstitialsAdsLoader.setPlayer(null);
      }
      if (playerView != null) {
        playerView.setPlayer(null);
      }
    }
  }
  // [END hls_interstitials_activity]
}
