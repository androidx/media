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
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import com.google.common.collect.ImmutableMap
import com.google.common.util.concurrent.ListenableFuture

// Snippets for Customization.

object CustomizationKt {

  @OptIn(UnstableApi::class)
  fun customizeServerInteractionsFixed(
    httpDataSourceFactory: HttpDataSource.Factory,
    context: Context,
  ) {
    // [START customize_server_interactions_fixed]
    val dataSourceFactory =
      DataSource.Factory {
        val dataSource = httpDataSourceFactory.createDataSource()
        // Set a custom authentication request header.
        dataSource.setRequestProperty("Header", "Value")
        dataSource
      }
    val player =
      ExoPlayer.Builder(context)
        .setMediaSourceFactory(
          DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory)
        )
        .build()
    // [END customize_server_interactions_fixed]
  }

  private fun getCustomHeaders(uri: Uri): Map<String, String> {
    return ImmutableMap.of()
  }

  @OptIn(UnstableApi::class)
  fun customizeServerInteractionsJustInTimeRequestHeader(
    httpDataSourceFactory: HttpDataSource.Factory
  ) {
    // [START customize_server_interactions_just_in_time_request_header]
    val dataSourceFactory: DataSource.Factory =
      ResolvingDataSource.Factory(httpDataSourceFactory) { dataSpec: DataSpec ->
        // Provide just-in-time request headers.
        dataSpec.withRequestHeaders(getCustomHeaders(dataSpec.uri))
      }
    // [END customize_server_interactions_just_in_time_request_header]
  }

  private fun resolveUri(uri: Uri): Uri {
    return Uri.EMPTY
  }

  @OptIn(UnstableApi::class)
  fun customizeServerInteractionsJustInTimeUriChange(
    httpDataSourceFactory: HttpDataSource.Factory
  ) {
    // [START customize_server_interactions_just_in_time_uri_change]
    val dataSourceFactory: DataSource.Factory =
      ResolvingDataSource.Factory(httpDataSourceFactory) { dataSpec: DataSpec ->
        // Provide just-in-time URI resolution logic.
        dataSpec.withUri(resolveUri(dataSpec.uri))
      }
    // [END customize_server_interactions_just_in_time_uri_change]
  }

  @OptIn(UnstableApi::class)
  fun customizeLoadErrorHandlingPolicy(context: Context) {
    // [START customize_load_error_handling_policy]
    val loadErrorHandlingPolicy: LoadErrorHandlingPolicy =
      object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(
          loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
        ): Long {
          // Implement custom back-off logic here.
          return 0
        }
      }
    val player =
      ExoPlayer.Builder(context)
        .setMediaSourceFactory(
          DefaultMediaSourceFactory(context).setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        )
        .build()
    // [END customize_load_error_handling_policy]
  }

  @OptIn(UnstableApi::class)
  fun setCustomExtractorFlags(context: Context) {
    // [START set_custom_extractor_flags]
    val extractorsFactory =
      DefaultExtractorsFactory().setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
    val player =
      ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory))
        .build()
    // [END set_custom_extractor_flags]
  }

  @OptIn(UnstableApi::class)
  fun enableConstantBitrateSeeking() {
    // [START enable_constant_bitrate_seeking]
    val extractorsFactory = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
    // [END enable_constant_bitrate_seeking]
  }

  @OptIn(UnstableApi::class)
  fun enableAsynchronousBufferQueueing(context: Context) {
    // [START enable_asynchronous_buffer_queueing]
    val renderersFactory =
      DefaultRenderersFactory(context).forceEnableMediaCodecAsynchronousQueueing()
    val exoPlayer = ExoPlayer.Builder(context, renderersFactory).build()
    // [END enable_asynchronous_buffer_queueing]
  }

  @OptIn(markerClass = [UnstableApi::class])
  // [START forwarding_simple_base_player_custom_play]
  class PlayerWithCustomPlay(player: Player) : ForwardingSimpleBasePlayer(player) {
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
      // Add custom logic
      return super.handleSetPlayWhenReady(playWhenReady)
    }
  }

  // [END forwarding_simple_base_player_custom_play]

  @OptIn(markerClass = [UnstableApi::class])
  // [START forwarding_simple_base_player_no_seek]
  class PlayerWithoutSeekToNext(player: Player) : ForwardingSimpleBasePlayer(player) {
    override fun getState(): State {
      val state = super.getState()
      return state
        .buildUpon()
        .setAvailableCommands(
          state.availableCommands.buildUpon().remove(COMMAND_SEEK_TO_NEXT).build()
        )
        .build()
    }

    // We don't need to override handleSeek, because it is guaranteed not to be called for
    // COMMAND_SEEK_TO_NEXT since we've marked that command unavailable.
  }

  // [END forwarding_simple_base_player_no_seek]

  @OptIn(UnstableApi::class)
  fun mediaSourceCustomization(
    customDataSourceFactory: DataSource.Factory,
    customExtractorsFactory: ExtractorsFactory,
    customLoadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    streamUri: Uri,
  ) {
    // [START media_source_customization]
    val mediaSource =
      ProgressiveMediaSource.Factory(customDataSourceFactory, customExtractorsFactory)
        .setLoadErrorHandlingPolicy(customLoadErrorHandlingPolicy)
        .createMediaSource(MediaItem.fromUri(streamUri))
    // [END media_source_customization]
  }
}
