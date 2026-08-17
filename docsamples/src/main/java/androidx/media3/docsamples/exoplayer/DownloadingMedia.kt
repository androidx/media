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
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.File
import java.util.concurrent.Executor

// Snippets for Downloading media.

object DownloadingMediaKt {

  @OptIn(UnstableApi::class)
  fun createDownloadManager(context: Context, downloadDirectory: File, requirements: Requirements) {
    // [START create_download_manager]
    // Note: This should be a singleton in your app.
    val databaseProvider = StandaloneDatabaseProvider(context)

    // A download cache should not evict media, so should use a NoopCacheEvictor.
    val downloadCache = SimpleCache(downloadDirectory, NoOpCacheEvictor(), databaseProvider)

    // Create a factory for reading the data from the network.
    val dataSourceFactory = DefaultHttpDataSource.Factory()

    // Choose an executor for downloading data. Using Runnable::run will cause each download task to
    // download data on its own thread. Passing an executor that uses multiple threads will speed up
    // download tasks that can be split into smaller parts for parallel execution. Applications that
    // already have an executor for background downloads may wish to reuse their existing executor.
    val downloadExecutor = Executor(Runnable::run)

    // Create the download manager.
    val downloadManager =
      DownloadManager(context, databaseProvider, downloadCache, dataSourceFactory, downloadExecutor)

    // Optionally, properties can be assigned to configure the download manager.
    downloadManager.requirements = requirements
    downloadManager.maxParallelDownloads = 3
    // [END create_download_manager]
  }

  @OptIn(UnstableApi::class)
  fun addDownload1(contentId: String, contentUri: Uri) {
    // [START add_download_1]
    val downloadRequest = DownloadRequest.Builder(contentId, contentUri).build()
    // [END add_download_1]
  }

  @OptIn(UnstableApi::class)
  private abstract class MyDownloadService protected constructor(foregroundNotificationId: Int) :
    DownloadService(foregroundNotificationId)

  @OptIn(UnstableApi::class)
  fun addDownload2(context: Context, downloadRequest: DownloadRequest) {
    // [START add_download_2]
    DownloadService.sendAddDownload(
      context,
      MyDownloadService::class.java,
      downloadRequest,
      /* foreground= */ false,
    )
    // [END add_download_2]
  }

  @OptIn(UnstableApi::class)
  fun removeDownload(context: Context, contentId: String) {
    // [START remove_download]
    DownloadService.sendRemoveDownload(
      context,
      MyDownloadService::class.java,
      contentId,
      /* foreground= */ false,
    )
    // [END remove_download]
  }

  @OptIn(UnstableApi::class)
  fun setDownloadStopReason(context: Context, contentId: String, stopReason: Int) {
    // [START set_download_stop_reason]
    // Set the stop reason for a single download.
    DownloadService.sendSetStopReason(
      context,
      MyDownloadService::class.java,
      contentId,
      stopReason,
      /* foreground= */ false,
    )

    // Clear the stop reason for a single download.
    DownloadService.sendSetStopReason(
      context,
      MyDownloadService::class.java,
      contentId,
      Download.STOP_REASON_NONE,
      /* foreground= */ false,
    )
    // [END set_download_stop_reason]
  }

  @OptIn(UnstableApi::class)
  fun pauseAndResumeDownloads(context: Context) {
    // [START pause_and_resume_downloads]
    // Pause all downloads.
    DownloadService.sendPauseDownloads(
      context,
      MyDownloadService::class.java,
      /* foreground= */ false,
    )

    // Resume all downloads.
    DownloadService.sendResumeDownloads(
      context,
      MyDownloadService::class.java,
      /* foreground= */ false,
    )
    // [END pause_and_resume_downloads]
  }

  @OptIn(UnstableApi::class)
  fun setDownloadRequirements(context: Context, requirements: Requirements) {
    // [START set_download_requirements]
    // Set the download requirements.
    DownloadService.sendSetRequirements(
      context,
      MyDownloadService::class.java,
      requirements,
      /* foreground= */ false,
    )
    // [END set_download_requirements]
  }

  @OptIn(UnstableApi::class)
  fun addDownloadManagerListener(downloadManager: DownloadManager) {
    // [START add_download_manager_listener]
    downloadManager.addListener(
      object : DownloadManager.Listener { // Override methods of interest here.
      }
    )
    // [END add_download_manager_listener]
  }

  @OptIn(UnstableApi::class)
  fun playDownloadedContent(
    downloadCache: Cache,
    httpDataSourceFactory: HttpDataSource.Factory,
    context: Context,
  ) {
    // [START play_downloaded_content]
    // Create a read-only cache data source factory using the download cache.
    val cacheDataSourceFactory: DataSource.Factory =
      CacheDataSource.Factory()
        .setCache(downloadCache)
        .setUpstreamDataSourceFactory(httpDataSourceFactory)
        .setCacheWriteDataSinkFactory(null) // Disable writing.

    val player =
      ExoPlayer.Builder(context)
        .setMediaSourceFactory(
          DefaultMediaSourceFactory(context).setDataSourceFactory(cacheDataSourceFactory)
        )
        .build()
    // [END play_downloaded_content]
  }

  @OptIn(UnstableApi::class)
  fun playDownloadedContentWithMediaSource(
    cacheDataSourceFactory: CacheDataSource.Factory,
    contentUri: Uri,
    player: ExoPlayer,
  ) {
    // [START play_downloaded_content_with_media_source]
    val mediaSource =
      ProgressiveMediaSource.Factory(cacheDataSourceFactory)
        .createMediaSource(MediaItem.fromUri(contentUri))
    player.setMediaSource(mediaSource)
    player.prepare()
    // [END play_downloaded_content_with_media_source]
  }

  @OptIn(UnstableApi::class)
  fun downloadAdaptiveStream(
    context: Context,
    contentUri: Uri,
    dataSourceFactory: DataSource.Factory,
    callback: DownloadHelper.Callback,
  ) {
    // [START download_adaptive_stream]
    val downloadHelper =
      DownloadHelper.Factory()
        .setRenderersFactory(DefaultRenderersFactory(context))
        .setDataSourceFactory(dataSourceFactory)
        .create(MediaItem.fromUri(contentUri))
    downloadHelper.prepare(callback)
    // [END download_adaptive_stream]
  }
}
