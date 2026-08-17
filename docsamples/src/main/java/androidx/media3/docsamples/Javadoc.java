/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.docsamples;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import androidx.annotation.OptIn;
import androidx.core.content.ContextCompat;
import androidx.media3.common.BundleListRetriever;
import androidx.media3.common.ForwardingSimpleBasePlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.StreamKey;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.DatabaseProvider;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.offline.DashDownloader;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.hls.offline.HlsDownloader;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.offline.Downloader.ProgressListener;
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource;
import androidx.media3.exoplayer.smoothstreaming.offline.SsDownloader;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.SessionToken;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.util.concurrent.ExecutionException;

/**
 * Javadoc code snippets.
 *
 * <p>Note: The code below is not an exact replica of the snippets in the guide. In particular, the
 * guide uses "..." to omit unimportant arguments.
 */
@OptIn(markerClass = UnstableApi.class)
@SuppressWarnings({"unused", "ConstantConditions", "CheckReturnValue"})
public final class Javadoc {

  public void player(Player player) {
    player.setTrackSelectionParameters(
        player.getTrackSelectionParameters().buildUpon().setMaxVideoSizeSd().build());
  }

  public void defaultTrackSelector(Player player, DefaultTrackSelector defaultTrackSelector) {
    player.setTrackSelectionParameters(
        player
            .getTrackSelectionParameters()
            .buildUpon()
            .setMaxVideoSizeSd()
            .setPreferredAudioLanguage("de")
            .build());
    defaultTrackSelector.setParameters(
        defaultTrackSelector.getParameters().buildUpon().setTunnelingEnabled(true).build());
  }

  public void dashDownloader(
      File downloadFolder,
      Uri manifestUrl,
      ProgressListener progressListener,
      DatabaseProvider databaseProvider,
      MediaItem mediaItem)
      throws Exception {
    SimpleCache cache = new SimpleCache(downloadFolder, new NoOpCacheEvictor(), databaseProvider);
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(new DefaultHttpDataSource.Factory());
    // Create a downloader for the first representation of the first adaptation set of the first
    // period.
    DashDownloader dashDownloader =
        new DashDownloader.Factory(cacheDataSourceFactory)
            .create(
                new MediaItem.Builder()
                    .setUri(manifestUrl)
                    .setStreamKeys(ImmutableList.of(new StreamKey(0, 0, 0)))
                    .build());
    // Perform the download.
    dashDownloader.download(progressListener);
    // Use the downloaded data for playback.
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(cacheDataSourceFactory).createMediaSource(mediaItem);
  }

  public void ssDownloader(
      File downloadFolder,
      Uri manifestUrl,
      ProgressListener progressListener,
      DatabaseProvider databaseProvider,
      MediaItem mediaItem)
      throws Exception {
    SimpleCache cache = new SimpleCache(downloadFolder, new NoOpCacheEvictor(), databaseProvider);
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(new DefaultHttpDataSource.Factory());
    // Create a downloader for the first track of the first stream element.
    SsDownloader ssDownloader =
        new SsDownloader.Factory(cacheDataSourceFactory)
            .create(
                new MediaItem.Builder()
                    .setUri(manifestUrl)
                    .setStreamKeys(ImmutableList.of(new StreamKey(0, 0)))
                    .build());
    // Perform the download.
    ssDownloader.download(progressListener);
    // Use the downloaded data for playback.
    SsMediaSource mediaSource =
        new SsMediaSource.Factory(cacheDataSourceFactory).createMediaSource(mediaItem);
  }

  public void hlsDownloader(
      File downloadFolder,
      Uri playlistUri,
      ProgressListener progressListener,
      DatabaseProvider databaseProvider,
      MediaItem mediaItem)
      throws Exception {
    SimpleCache cache = new SimpleCache(downloadFolder, new NoOpCacheEvictor(), databaseProvider);
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(new DefaultHttpDataSource.Factory());
    // Create a downloader for the first variant in a multivariant playlist.
    HlsDownloader hlsDownloader =
        new HlsDownloader.Factory(cacheDataSourceFactory)
            .create(
                new MediaItem.Builder()
                    .setUri(playlistUri)
                    .setStreamKeys(
                        ImmutableList.of(
                            new StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, 0)))
                    .build());
    // Perform the download.
    hlsDownloader.download(progressListener);
    // Use the downloaded data for playback.
    HlsMediaSource mediaSource =
        new HlsMediaSource.Factory(cacheDataSourceFactory).createMediaSource(mediaItem);
  }

  public void bundleListRetrieverSender() {
    // Sender
    ImmutableList<Bundle> list = ImmutableList.of();
    IBinder binder = new BundleListRetriever(list);
    Bundle bundle = new Bundle();
    bundle.putBinder("list", binder);
  }

  public void bundleListRetrieverReceiver() {
    // Receiver
    Bundle bundle = new Bundle(); // Received from the sender
    IBinder binder = bundle.getBinder("list");
    ImmutableList<Bundle> list = BundleListRetriever.getList(binder);
  }

  public void trackSelectionParameters(Player player) {
    // Build on the current parameters.
    TrackSelectionParameters currentParameters = player.getTrackSelectionParameters();
    // Build the resulting parameters.
    TrackSelectionParameters newParameters =
        currentParameters.buildUpon().setMaxVideoSizeSd().setPreferredAudioLanguage("de").build();
    // Set the new parameters.
    player.setTrackSelectionParameters(newParameters);
  }

  public void mediaBrowserBuildAsync(Context context, SessionToken sessionToken) {
    MediaBrowser.Builder builder = new MediaBrowser.Builder(context, sessionToken);
    ListenableFuture<MediaBrowser> future = builder.buildAsync();
    future.addListener(
        () -> {
          try {
            MediaBrowser browser = future.get();
            // The session accepted the connection.
          } catch (ExecutionException | InterruptedException e) {
            if (e.getCause() instanceof SecurityException) {
              // The session rejected the connection.
            }
          }
        },
        ContextCompat.getMainExecutor(context));
  }

  public void forwardingSimpleBasePlayer(Player player, Player.Commands filteredCommands) {
    new ForwardingSimpleBasePlayer(player) {
      @Override
      protected State getState() {
        State state = super.getState();
        // Modify current state as required:
        return state.buildUpon().setAvailableCommands(filteredCommands).build();
      }

      @Override
      protected ListenableFuture<?> handleSetRepeatMode(int repeatMode) {
        // Modify actions by directly calling the underlying player as needed:
        getPlayer().setShuffleModeEnabled(true);
        // ..or forward to the default handling with modified parameters:
        return super.handleSetRepeatMode(Player.REPEAT_MODE_ALL);
      }
    };
  }
}
