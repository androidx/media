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
@file:Suppress(
  "unused_parameter",
  "unused_variable",
  "unused",
  "CheckReturnValue",
  "ControlFlowWithEmptyBody",
)

package androidx.media3.docsamples.exoplayer

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.AdViewProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
object HlsKt {

  fun hlsCreateMediaItem(context: Context, hlsUri: Uri) {
    // [START hls_create_media_item]
    // Create a player instance.
    val player = ExoPlayer.Builder(context).build()
    // Set the media item to be played.
    player.setMediaItem(MediaItem.fromUri(hlsUri))
    // Prepare the player.
    player.prepare()
    // [END hls_create_media_item]
  }

  fun hlsCreateMediaSource(hlsUri: Uri, context: Context) {
    // [START hls_create_media_source]
    // Create a data source factory.
    val dataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
    // Create a HLS media source pointing to a playlist uri.
    val hlsMediaSource =
      HlsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(hlsUri))
    // Create a player instance.
    val player = ExoPlayer.Builder(context).build()
    // Set the HLS media source as the playlist with a single media item.
    player.setMediaSource(hlsMediaSource)
    // Prepare the player.
    player.prepare()
    // [END hls_create_media_source]
  }

  fun hlsAccessManifest(player: Player) {
    // [START hls_access_manifest]
    player.addListener(
      object : Player.Listener {
        override fun onTimelineChanged(
          timeline: Timeline,
          @Player.TimelineChangeReason reason: Int,
        ) {
          val manifest = player.currentManifest
          if (manifest is HlsManifest) {
            // Do something with the manifest.
          }
        }
      }
    )
    // [END hls_access_manifest]
  }

  fun hlsDisableChunklessPreparation(dataSourceFactory: DataSource.Factory, hlsUri: Uri) {
    // [START hls_disable_chunkless_preparation]
    val hlsMediaSource =
      HlsMediaSource.Factory(dataSourceFactory)
        .setAllowChunklessPreparation(false)
        .createMediaSource(MediaItem.fromUri(hlsUri))
    // [END hls_disable_chunkless_preparation]
  }

  fun useHlsInterstitialsAdsLoaderWithMediaSourceApi(playerView: PlayerView, context: Context) {
    // [START hls_interstitials_setup]
    val hlsInterstitialsAdsLoader = HlsInterstitialsAdsLoader(context)
    // Create a MediaSource.Factory for HLS streams with interstitials.
    val hlsMediaSourceFactory =
      HlsInterstitialsAdsLoader.AdsMediaSourceFactory(
        hlsInterstitialsAdsLoader,
        playerView,
        DefaultMediaSourceFactory(context),
      )

    // Build player with interstitials media source factory
    val player = ExoPlayer.Builder(context).setMediaSourceFactory(hlsMediaSourceFactory).build()

    // Set the player on the ads loader.
    hlsInterstitialsAdsLoader.setPlayer(player)
    playerView.setPlayer(player)
    // [END hls_interstitials_setup]

    // [START hls_interstitials_playlist]
    // Build an HLS media item with ads configuration to be played.
    player.setMediaItem(
      MediaItem.Builder()
        .setUri("https://www.example.com/media.m3u8")
        .setAdsConfiguration(
          MediaItem.AdsConfiguration.Builder("hls://interstitials".toUri())
            .setAdsId("ad-tag-0") // must be unique within playlist
            .build()
        )
        .build()
    )

    player.prepare()
    player.play()
    // [END hls_interstitials_playlist]
  }

  fun useHlsInterstitialsAdsLoaderDirectly(playerView: PlayerView, context: Context) {
    // [START hls_interstitials_media_source]
    val hlsInterstitialsAdsLoader = HlsInterstitialsAdsLoader(context)
    // Create a MediaSource.Factory for HLS streams with interstitials.
    val hlsMediaSourceFactory =
      HlsInterstitialsAdsLoader.AdsMediaSourceFactory(
        hlsInterstitialsAdsLoader,
        playerView,
        context,
      )

    // Build player with default media source factory.
    val player = ExoPlayer.Builder(context).build()

    // Create an media source from an HLS media item with ads configuration.
    val mediaSource =
      hlsMediaSourceFactory.createMediaSource(
        MediaItem.Builder()
          .setUri("https://www.example.com/media.m3u8")
          .setAdsConfiguration(
            MediaItem.AdsConfiguration.Builder("hls://interstitials".toUri())
              .setAdsId("ad-tag-0")
              .build()
          )
          .build()
      )

    // Set the media source on the player.
    player.setMediaSource(mediaSource)
    player.prepare()
    player.play()
    // [END hls_interstitials_media_source]
  }

  fun useHlsInterstitialsAdsLoaderListener(hlsInterstitialsAdsLoader: HlsInterstitialsAdsLoader) {
    // [START hls_interstitials_add_listener]
    val listener = AdsLoaderListener()
    // Add the listener to the ads loader to receive ad loader events.
    hlsInterstitialsAdsLoader.addListener(listener)
    // [END hls_interstitials_add_listener]
  }

  // [START hls_interstitials_listener]
  class AdsLoaderListener : HlsInterstitialsAdsLoader.Listener {

    override fun onStart(mediaItem: MediaItem, adsId: Any, adViewProvider: AdViewProvider) {
      // Do something when HLS media item with interstitials is started.
    }

    override fun onMetadata(
      mediaItem: MediaItem,
      adsId: Any,
      adGroupIndex: Int,
      adIndexInAdGroup: Int,
      metadata: Metadata,
    ) {
      // Do something with metadata that is emitted by the ad media source of the given ad.
    }

    override fun onAdCompleted(
      mediaItem: MediaItem,
      adsId: Any,
      adGroupIndex: Int,
      adIndexInAdGroup: Int,
    ) {
      // Do something when ad completed playback.
    }

    // ... See JavaDoc for further callbacks of HlsInterstitialsAdsLoader.Listener.

    override fun onStop(mediaItem: MediaItem, adsId: Any, adPlaybackState: AdPlaybackState) {
      // Do something with the resulting ad playback state when stopped.
    }
  }

  // [END hls_interstitials_listener]

  // [START hls_interstitials_activity]
  class HlsInterstitialsActivity : Activity() {

    companion object {
      const val ADS_RESUMPTION_STATE_KEY = "ads_resumption_state"
    }

    private var hlsInterstitialsAdsLoader: HlsInterstitialsAdsLoader? = null
    private var playerView: PlayerView? = null
    private var player: ExoPlayer? = null
    private var adsResumptionStates: List<HlsInterstitialsAdsLoader.AdsResumptionState>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
      super.onCreate(savedInstanceState)
      // Create the ads loader instance.
      hlsInterstitialsAdsLoader = HlsInterstitialsAdsLoader(this)
      // Restore ad resumption states.
      savedInstanceState?.getParcelableArrayList<Bundle>(ADS_RESUMPTION_STATE_KEY)?.let { bundles ->
        adsResumptionStates =
          bundles.map { HlsInterstitialsAdsLoader.AdsResumptionState.fromBundle(it) }
      }
    }

    override fun onStart() {
      super.onStart()
      // Build a player and set it on the ads loader.
      initializePlayer()
      hlsInterstitialsAdsLoader?.setPlayer(player)
      // Add any stored ad resumption states to the ads loader.
      adsResumptionStates?.forEach { hlsInterstitialsAdsLoader?.addAdResumptionState(it) }
      adsResumptionStates = null // Consume the states
    }

    override fun onStop() {
      super.onStop()
      // Get ad resumption states.
      adsResumptionStates = hlsInterstitialsAdsLoader?.adsResumptionStates
      releasePlayer()
    }

    override fun onDestroy() {
      // Release the ads loader when not used anymore.
      hlsInterstitialsAdsLoader?.release()
      hlsInterstitialsAdsLoader = null
      super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
      super.onSaveInstanceState(outState)
      // Store the ad resumption states.
      adsResumptionStates?.let {
        outState.putParcelableArrayList(
          ADS_RESUMPTION_STATE_KEY,
          ArrayList(it.map(HlsInterstitialsAdsLoader.AdsResumptionState::toBundle)),
        )
      }
    }

    fun initializePlayer() {
      if (player == null) {
        // Create a media source factory for HLS streams.
        val hlsMediaSourceFactory =
          HlsInterstitialsAdsLoader.AdsMediaSourceFactory(
            checkNotNull(hlsInterstitialsAdsLoader),
            playerView!!,
            /* context= */ this,
          )
        // Build player with interstitials media source
        player =
          ExoPlayer.Builder(/* context= */ this)
            .setMediaSourceFactory(hlsMediaSourceFactory)
            .build()
        // Set the player on the ads loader.
        hlsInterstitialsAdsLoader?.setPlayer(player)
        playerView?.player = player
      }

      // Use a media item with an HLS stream URI, an ad tag URI and ads ID.
      player?.setMediaItem(
        MediaItem.Builder()
          .setUri("https://www.example.com/media.m3u8")
          .setMimeType(MimeTypes.APPLICATION_M3U8)
          .setAdsConfiguration(
            MediaItem.AdsConfiguration.Builder("hls://interstitials".toUri())
              .setAdsId("ad-tag-0") // must be unique within ExoPlayer playlist
              .build()
          )
          .build()
      )
      player?.prepare()
      player?.play()
    }

    fun releasePlayer() {
      player?.release()
      player = null
      hlsInterstitialsAdsLoader?.setPlayer(null)
      playerView?.player = null
    }
  }
  // [END hls_interstitials_activity]
}
