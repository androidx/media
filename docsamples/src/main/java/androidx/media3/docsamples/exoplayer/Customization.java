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
import androidx.media3.common.ForwardingSimpleBasePlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.mp3.Mp3Extractor;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListenableFuture;

/** Snippets for Customization. */
@SuppressWarnings({"unused", "CheckReturnValue", "PrivateConstructorForUtilityClass"})
public class Customization {

  @OptIn(markerClass = UnstableApi.class)
  public static void customizeServerInteractionsFixed(
      HttpDataSource.Factory httpDataSourceFactory, Context context) {
    // [START customize_server_interactions_fixed]
    DataSource.Factory dataSourceFactory =
        () -> {
          HttpDataSource dataSource = httpDataSourceFactory.createDataSource();
          // Set a custom authentication request header.
          dataSource.setRequestProperty("Header", "Value");
          return dataSource;
        };

    ExoPlayer player =
        new ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .build();
    // [END customize_server_interactions_fixed]
  }

  private static ImmutableMap<String, String> getCustomHeaders(Uri uri) {
    return ImmutableMap.of();
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void customizeServerInteractionsJustInTimeRequestHeader(
      HttpDataSource.Factory httpDataSourceFactory) {
    // [START customize_server_interactions_just_in_time_request_header]
    DataSource.Factory dataSourceFactory =
        new ResolvingDataSource.Factory(
            httpDataSourceFactory,
            // Provide just-in-time request headers.
            dataSpec -> dataSpec.withRequestHeaders(getCustomHeaders(dataSpec.uri)));
    // [END customize_server_interactions_just_in_time_request_header]
  }

  private static Uri resolveUri(Uri uri) {
    return Uri.EMPTY;
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void customizeServerInteractionsJustInTimeUriChange(
      HttpDataSource.Factory httpDataSourceFactory) {
    // [START customize_server_interactions_just_in_time_uri_change]
    DataSource.Factory dataSourceFactory =
        new ResolvingDataSource.Factory(
            httpDataSourceFactory,
            // Provide just-in-time URI resolution logic.
            dataSpec -> dataSpec.withUri(resolveUri(dataSpec.uri)));
    // [END customize_server_interactions_just_in_time_uri_change]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void customizeLoadErrorHandlingPolicy(Context context) {
    // [START customize_load_error_handling_policy]
    LoadErrorHandlingPolicy loadErrorHandlingPolicy =
        new DefaultLoadErrorHandlingPolicy() {
          @Override
          public long getRetryDelayMsFor(LoadErrorHandlingPolicy.LoadErrorInfo loadErrorInfo) {
            // Implement custom back-off logic here.
            return 0;
          }
        };

    ExoPlayer player =
        new ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(context)
                    .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy))
            .build();
    // [END customize_load_error_handling_policy]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void setCustomExtractorFlags(Context context) {
    // [START set_custom_extractor_flags]
    DefaultExtractorsFactory extractorsFactory =
        new DefaultExtractorsFactory().setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING);

    ExoPlayer player =
        new ExoPlayer.Builder(context)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(context, extractorsFactory))
            .build();
    // [END set_custom_extractor_flags]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void enableConstantBitrateSeeking() {
    // [START enable_constant_bitrate_seeking]
    DefaultExtractorsFactory extractorsFactory =
        new DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true);
    // [END enable_constant_bitrate_seeking]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void enableAsynchronousBufferQueueing(Context context) {
    // [START enable_asynchronous_buffer_queueing]
    DefaultRenderersFactory renderersFactory =
        new DefaultRenderersFactory(context).forceEnableMediaCodecAsynchronousQueueing();
    ExoPlayer exoPlayer = new ExoPlayer.Builder(context, renderersFactory).build();
    // [END enable_asynchronous_buffer_queueing]
  }

  /**
   * A {@link Player} implementation which adds custom logic when playback is started or stopped.
   */
  @OptIn(markerClass = UnstableApi.class)
  // [START forwarding_simple_base_player_custom_play]
  public static final class PlayerWithCustomPlay extends ForwardingSimpleBasePlayer {

    public PlayerWithCustomPlay(Player player) {
      super(player);
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
      // Add custom logic
      return super.handleSetPlayWhenReady(playWhenReady);
    }
  }

  // [END forwarding_simple_base_player_custom_play]

  /**
   * A {@link Player} implementation which makes {@link Player#COMMAND_SEEK_TO_NEXT} unavailable.
   */
  @OptIn(markerClass = UnstableApi.class)
  // [START forwarding_simple_base_player_no_seek]
  public static final class PlayerWithoutSeekToNext extends ForwardingSimpleBasePlayer {

    public PlayerWithoutSeekToNext(Player player) {
      super(player);
    }

    @Override
    protected State getState() {
      State state = super.getState();
      return state
          .buildUpon()
          .setAvailableCommands(
              state.availableCommands.buildUpon().remove(COMMAND_SEEK_TO_NEXT).build())
          .build();
    }

    // We don't need to override handleSeek, because it is guaranteed not to be called for
    // COMMAND_SEEK_TO_NEXT since we've marked that command unavailable.
  }

  // [END forwarding_simple_base_player_no_seek]

  @OptIn(markerClass = UnstableApi.class)
  public static void mediaSourceCustomization(
      DataSource.Factory customDataSourceFactory,
      ExtractorsFactory customExtractorsFactory,
      LoadErrorHandlingPolicy customLoadErrorHandlingPolicy,
      Uri streamUri) {
    // [START media_source_customization]
    ProgressiveMediaSource mediaSource =
        new ProgressiveMediaSource.Factory(customDataSourceFactory, customExtractorsFactory)
            .setLoadErrorHandlingPolicy(customLoadErrorHandlingPolicy)
            .createMediaSource(MediaItem.fromUri(streamUri));
    // [END media_source_customization]
  }
}
