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
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadHelper;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.DownloadService;
import androidx.media3.exoplayer.scheduler.Requirements;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import java.io.File;
import java.util.concurrent.Executor;

/** Snippets for Downloading media. */
@SuppressWarnings({
  "unused",
  "CheckReturnValue",
  "PrivateConstructorForUtilityClass",
  "EffectivelyPrivate",
  "ParameterCanBeLocal",
  "resource"
})
public class DownloadingMedia {

  @OptIn(markerClass = UnstableApi.class)
  public static void createDownloadManager(
      StandaloneDatabaseProvider databaseProvider,
      Context context,
      SimpleCache downloadCache,
      File downloadDirectory,
      DefaultHttpDataSource.Factory dataSourceFactory,
      DownloadManager downloadManager,
      Requirements requirements) {
    // [START create_download_manager]
    // Note: This should be a singleton in your app.
    databaseProvider = new StandaloneDatabaseProvider(context);

    // A download cache should not evict media, so should use a NoopCacheEvictor.
    downloadCache = new SimpleCache(downloadDirectory, new NoOpCacheEvictor(), databaseProvider);

    // Create a factory for reading the data from the network.
    dataSourceFactory = new DefaultHttpDataSource.Factory();

    // Choose an executor for downloading data. Using Runnable::run will cause each download task to
    // download data on its own thread. Passing an executor that uses multiple threads will speed up
    // download tasks that can be split into smaller parts for parallel execution. Applications that
    // already have an executor for background downloads may wish to reuse their existing executor.
    Executor downloadExecutor = Runnable::run;

    // Create the download manager.
    downloadManager =
        new DownloadManager(
            context, databaseProvider, downloadCache, dataSourceFactory, downloadExecutor);

    // Optionally, setters can be called to configure the download manager.
    downloadManager.setRequirements(requirements);
    downloadManager.setMaxParallelDownloads(3);
    // [END create_download_manager]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void addDownload1(String contentId, Uri contentUri) {
    // [START add_download_1]
    DownloadRequest downloadRequest = new DownloadRequest.Builder(contentId, contentUri).build();
    // [END add_download_1]
  }

  @OptIn(markerClass = UnstableApi.class)
  private abstract static class MyDownloadService extends DownloadService {

    protected MyDownloadService(int foregroundNotificationId) {
      super(foregroundNotificationId);
    }
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void addDownload2(Context context, DownloadRequest downloadRequest) {
    // [START add_download_2]
    DownloadService.sendAddDownload(
        context, MyDownloadService.class, downloadRequest, /* foreground= */ false);
    // [END add_download_2]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void removeDownload(Context context, String contentId) {
    // [START remove_download]
    DownloadService.sendRemoveDownload(
        context, MyDownloadService.class, contentId, /* foreground= */ false);
    // [END remove_download]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void setDownloadStopReason(Context context, String contentId, int stopReason) {
    // [START set_download_stop_reason]
    // Set the stop reason for a single download.
    DownloadService.sendSetStopReason(
        context, MyDownloadService.class, contentId, stopReason, /* foreground= */ false);

    // Clear the stop reason for a single download.
    DownloadService.sendSetStopReason(
        context,
        MyDownloadService.class,
        contentId,
        Download.STOP_REASON_NONE,
        /* foreground= */ false);
    // [END set_download_stop_reason]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void pauseAndResumeDownloads(Context context) {
    // [START pause_and_resume_downloads]
    // Pause all downloads.
    DownloadService.sendPauseDownloads(context, MyDownloadService.class, /* foreground= */ false);

    // Resume all downloads.
    DownloadService.sendResumeDownloads(context, MyDownloadService.class, /* foreground= */ false);
    // [END pause_and_resume_downloads]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void setDownloadRequirements(Context context, Requirements requirements) {
    // [START set_download_requirements]
    // Set the download requirements.
    DownloadService.sendSetRequirements(
        context, MyDownloadService.class, requirements, /* foreground= */ false);
    // [END set_download_requirements]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void addDownloadManagerListener(DownloadManager downloadManager) {
    // [START add_download_manager_listener]
    downloadManager.addListener(
        new DownloadManager.Listener() {
          // Override methods of interest here.
        });
    // [END add_download_manager_listener]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void playDownloadedContent(
      Cache downloadCache, HttpDataSource.Factory httpDataSourceFactory, Context context) {
    // [START play_downloaded_content]
    // Create a read-only cache data source factory using the download cache.
    DataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setCacheWriteDataSinkFactory(null); // Disable writing.

    ExoPlayer player =
        new ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(context).setDataSourceFactory(cacheDataSourceFactory))
            .build();
    // [END play_downloaded_content]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void playDownloadedContentWithMediaSource(
      CacheDataSource.Factory cacheDataSourceFactory, Uri contentUri, ExoPlayer player) {
    // [START play_downloaded_content_with_media_source]
    ProgressiveMediaSource mediaSource =
        new ProgressiveMediaSource.Factory(cacheDataSourceFactory)
            .createMediaSource(MediaItem.fromUri(contentUri));
    player.setMediaSource(mediaSource);
    player.prepare();
    // [END play_downloaded_content_with_media_source]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void downloadAdaptiveStream(
      Context context,
      Uri contentUri,
      DataSource.Factory dataSourceFactory,
      DownloadHelper.Callback callback) {
    // [START download_adaptive_stream]
    DownloadHelper downloadHelper =
        new DownloadHelper.Factory()
            .setRenderersFactory(new DefaultRenderersFactory(context))
            .setDataSourceFactory(dataSourceFactory)
            .create(MediaItem.fromUri(contentUri));
    downloadHelper.prepare(callback);
    // [END download_adaptive_stream]
  }
}
