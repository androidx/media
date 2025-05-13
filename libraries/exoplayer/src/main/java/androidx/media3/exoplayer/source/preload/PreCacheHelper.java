/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.source.preload;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory;
import androidx.media3.exoplayer.offline.DownloadHelper;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.Downloader;
import androidx.media3.exoplayer.offline.DownloaderFactory;
import androidx.media3.exoplayer.source.MediaSource;
import com.google.common.base.Supplier;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.Executor;

/** A helper for pre-caching a single media. */
@UnstableApi
public final class PreCacheHelper {

  /**
   * A listener for {@link PreCacheHelper} events.
   *
   * <p>The methods are called from the thread where the {@link PreCacheHelper} instance is created.
   */
  public interface Listener {

    /**
     * Called from {@link PreCacheHelper} when the {@link MediaItem} is prepared.
     *
     * @param originalMediaItem The original {@link MediaItem} passed to create the {@link
     *     PreCacheHelper}.
     * @param updatedMediaItem The updated {@link MediaItem} after preparation that should be passed
     *     to the {@link ExoPlayer} for making playback read from the cached data.
     */
    default void onPrepared(MediaItem originalMediaItem, MediaItem updatedMediaItem) {}

    /**
     * Called from {@link PreCacheHelper} when progress is made during a download operation.
     *
     * @param mediaItem The {@link MediaItem} passed to create the {@link PreCacheHelper}.
     * @param contentLength The length of the content to be cached in bytes, or {@link
     *     C#LENGTH_UNSET} if unknown.
     * @param bytesDownloaded The number of bytes that have been downloaded.
     * @param percentageDownloaded The percentage of the content that has been downloaded, or {@link
     *     C#PERCENTAGE_UNSET}.
     */
    default void onPreCacheProgress(
        MediaItem mediaItem,
        long contentLength,
        long bytesDownloaded,
        float percentageDownloaded) {}

    /**
     * Called from {@link PreCacheHelper} when error occurs during the preparation.
     *
     * @param mediaItem The {@link MediaItem} passed to create the {@link PreCacheHelper}.
     * @param error The error.
     */
    default void onPrepareError(MediaItem mediaItem, IOException error) {}

    /**
     * Called from {@link PreCacheHelper} when error occurs during the download.
     *
     * @param mediaItem The {@link MediaItem} passed to create the {@link PreCacheHelper}.
     * @param error The error.
     */
    default void onDownloadError(MediaItem mediaItem, IOException error) {}
  }

  /** A factory for {@link PreCacheHelper}. */
  public static final class Factory {
    private final Cache cache;
    private final Looper preCacheLooper;
    private final Supplier<DataSource.Factory> upstreamDataSourceFactorySupplier;
    private final Supplier<RenderersFactory> renderersFactorySupplier;
    private TrackSelectionParameters trackSelectionParameters;
    private Supplier<Executor> downloadExecutorSupplier;
    @Nullable private Listener listener;

    /**
     * Creates a {@link Factory}.
     *
     * @param context The {@link Context}.
     * @param cache The {@link Cache} instance that will be used.
     * @param preCacheLooper The {@link Looper} that operates the pre-caching flow. Note that this
     *     looper is not responsible for downloading the data.
     */
    public Factory(Context context, Cache cache, Looper preCacheLooper) {
      this.cache = cache;
      this.preCacheLooper = preCacheLooper;
      this.upstreamDataSourceFactorySupplier = () -> new DefaultDataSource.Factory(context);
      this.trackSelectionParameters = TrackSelectionParameters.DEFAULT;
      this.renderersFactorySupplier = () -> new DefaultRenderersFactory(context);
      this.downloadExecutorSupplier =
          () -> Util.newSingleThreadScheduledExecutor(PRECACHE_DOWNLOADER_THREAD_NAME);
    }

    /**
     * Creates a {@link Factory}.
     *
     * @param context The {@link Context}.
     * @param cache The {@link Cache} instance that will be used.
     * @param renderersFactory The {@link RenderersFactory} creating the renderers for which tracks
     *     are selected.
     * @param preCacheLooper The {@link Looper} that operates the pre-caching flow. Note that this
     *     looper is not responsible for downloading the data.
     */
    public Factory(
        Context context, Cache cache, RenderersFactory renderersFactory, Looper preCacheLooper) {
      this.cache = cache;
      this.preCacheLooper = preCacheLooper;
      this.upstreamDataSourceFactorySupplier = () -> new DefaultDataSource.Factory(context);
      this.trackSelectionParameters = TrackSelectionParameters.DEFAULT;
      this.renderersFactorySupplier = () -> renderersFactory;
      this.downloadExecutorSupplier =
          () -> Util.newSingleThreadScheduledExecutor(PRECACHE_DOWNLOADER_THREAD_NAME);
    }

    /**
     * Creates a {@link Factory}.
     *
     * @param context The {@link Context}.
     * @param cache The {@link Cache} instance that will be used.
     * @param upstreamDataSourceFactory The upstream {@link DataSource.Factory} for reading data not
     *     in the cache.
     * @param preCacheLooper The {@link Looper} that operates the pre-caching flow. Note that this
     *     looper is not responsible for downloading the data.
     */
    public Factory(
        Context context,
        Cache cache,
        DataSource.Factory upstreamDataSourceFactory,
        Looper preCacheLooper) {
      this.cache = cache;
      this.preCacheLooper = preCacheLooper;
      this.upstreamDataSourceFactorySupplier = () -> upstreamDataSourceFactory;
      this.trackSelectionParameters = TrackSelectionParameters.DEFAULT;
      this.renderersFactorySupplier = () -> new DefaultRenderersFactory(context);
      this.downloadExecutorSupplier =
          () -> Util.newSingleThreadScheduledExecutor(PRECACHE_DOWNLOADER_THREAD_NAME);
    }

    /**
     * Creates a {@link Factory}.
     *
     * @param cache The {@link Cache} instance that will be used.
     * @param upstreamDataSourceFactory The upstream {@link DataSource.Factory} for reading data not
     *     in the cache.
     * @param renderersFactory The {@link RenderersFactory} creating the renderers for which tracks
     *     are selected.
     * @param preCacheLooper The {@link Looper} that operates the pre-caching flow. Note that this
     *     looper is not responsible for downloading the data.
     */
    public Factory(
        Cache cache,
        DataSource.Factory upstreamDataSourceFactory,
        RenderersFactory renderersFactory,
        Looper preCacheLooper) {
      this.cache = cache;
      this.preCacheLooper = preCacheLooper;
      this.upstreamDataSourceFactorySupplier = () -> upstreamDataSourceFactory;
      this.trackSelectionParameters = TrackSelectionParameters.DEFAULT;
      this.renderersFactorySupplier = () -> renderersFactory;
      this.downloadExecutorSupplier =
          () -> Util.newSingleThreadScheduledExecutor(PRECACHE_DOWNLOADER_THREAD_NAME);
    }

    /**
     * Sets a {@link TrackSelectionParameters} for selecting tracks for pre-caching.
     *
     * <p>The default is {@link TrackSelectionParameters#DEFAULT}.
     *
     * <p>This is only used for adaptive streams.
     *
     * @return This factory, for convenience.
     */
    @CanIgnoreReturnValue
    public PreCacheHelper.Factory setTrackSelectionParameters(
        TrackSelectionParameters trackSelectionParameters) {
      this.trackSelectionParameters = trackSelectionParameters;
      return this;
    }

    /**
     * Sets an {@link Executor} used to download data.
     *
     * <p>The default is a single threaded scheduled executor.
     *
     * <p>Passing {@code Runnable::run} will cause each download task to download data on its own
     * thread. Passing an {@link Executor} that uses multiple threads will speed up download tasks
     * that can be split into smaller parts for parallel execution.
     *
     * @return This factory, for convenience.
     */
    @CanIgnoreReturnValue
    public PreCacheHelper.Factory setDownloadExecutor(Executor downloadExecutor) {
      this.downloadExecutorSupplier = () -> downloadExecutor;
      return this;
    }

    /**
     * Sets the {@link Listener}.
     *
     * @return This factory, for convenience.
     */
    @CanIgnoreReturnValue
    public PreCacheHelper.Factory setListener(@Nullable Listener listener) {
      this.listener = listener;
      return this;
    }

    /**
     * Creates a {@link PreCacheHelper} instance.
     *
     * @param mediaItem The {@link MediaItem} to pre-cache.
     */
    public PreCacheHelper create(MediaItem mediaItem) {
      return new PreCacheHelper(
          mediaItem,
          /* mediaSource= */ null,
          upstreamDataSourceFactorySupplier.get(),
          trackSelectionParameters,
          renderersFactorySupplier.get(),
          cache,
          preCacheLooper,
          downloadExecutorSupplier.get(),
          listener);
    }

    /**
     * Creates a {@link PreCacheHelper} instance.
     *
     * @param mediaSource The {@link MediaSource} to pre-cache.
     */
    /* package */ PreCacheHelper create(MediaSource mediaSource) {
      return new PreCacheHelper(
          mediaSource.getMediaItem(),
          mediaSource,
          upstreamDataSourceFactorySupplier.get(),
          trackSelectionParameters,
          renderersFactorySupplier.get(),
          cache,
          preCacheLooper,
          downloadExecutorSupplier.get(),
          listener);
    }
  }

  @VisibleForTesting
  /* package */ static final String PRECACHE_DOWNLOADER_THREAD_NAME = "PreCacheHelper:Downloader";

  private final MediaItem mediaItem;
  private final Supplier<DownloadHelper> downloadHelperSupplier;
  private final DownloaderFactory downloaderFactory;
  @Nullable private final Listener listener;
  private final Handler preCacheHandler;
  private final Handler applicationHandler;

  private long startPositionMs;
  private long durationMs;
  private MediaItem updatedMediaItem;
  @Nullable private DownloadHelper downloadHelper;
  @Nullable private Downloader downloader;
  private boolean stopped;

  private PreCacheHelper(
      MediaItem mediaItem,
      @Nullable MediaSource mediaSource,
      DataSource.Factory upstreamDataSourceFactory,
      TrackSelectionParameters trackSelectionParameters,
      RenderersFactory renderersFactory,
      Cache cache,
      Looper preCacheLooper,
      Executor downloadExecutor,
      @Nullable Listener listener) {
    this.mediaItem = mediaItem;
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
            .setCache(cache);
    DownloadHelper.Factory downloadHelperFactory =
        new DownloadHelper.Factory()
            .setDataSourceFactory(cacheDataSourceFactory)
            .setRenderersFactory(renderersFactory)
            .setTrackSelectionParameters(trackSelectionParameters);
    this.downloadHelperSupplier =
        () ->
            mediaSource != null
                ? downloadHelperFactory.create(mediaSource)
                : downloadHelperFactory.create(mediaItem);
    this.downloaderFactory = new DefaultDownloaderFactory(cacheDataSourceFactory, downloadExecutor);
    this.listener = listener;
    this.preCacheHandler = Util.createHandler(preCacheLooper, null);
    this.applicationHandler = Util.createHandlerForCurrentOrMainLooper();

    startPositionMs = C.TIME_UNSET;
    durationMs = C.TIME_UNSET;
    updatedMediaItem = mediaItem;
  }

  /**
   * Pre-caches the underlying media from a {@code startPositionMs} and for a {@code durationMs}.
   *
   * <p>Can be called from any thread.
   *
   * @param startPositionMs The start position from which the media should be pre-cached, in
   *     milliseconds.
   * @param durationMs The duration for which the media should be pre-cached, in milliseconds.
   */
  public void preCache(long startPositionMs, long durationMs) {
    preCacheHandler.post(
        () -> {
          this.stopped = false;
          this.startPositionMs = startPositionMs;
          this.durationMs = durationMs;
          downloadHelper = downloadHelperSupplier.get();
          downloadHelper.prepare(new DownloadHelperCallback());
        });
  }

  /**
   * Stops the pre-caching.
   *
   * <p>Can be called from any thread.
   *
   * @param removeCachedContent Whether the cached content should be removed.
   */
  public void stop(boolean removeCachedContent) {
    preCacheHandler.post(
        () -> {
          if (downloader != null) {
            downloader.cancel();
            if (removeCachedContent) {
              downloader.remove();
            }
            downloader = null;
          }
          releaseDownloadHelper();
          preCacheHandler.removeCallbacksAndMessages(null);
          this.stopped = true;
        });
  }

  private void releaseDownloadHelper() {
    if (downloadHelper != null) {
      downloadHelper.release();
      downloadHelper = null;
    }
  }

  private void notifyListeners(Consumer<Listener> callable) {
    applicationHandler.post(
        () -> {
          if (listener != null) {
            callable.accept(listener);
          }
        });
  }

  private final class DownloadHelperCallback implements DownloadHelper.Callback {

    @Override
    public void onPrepared(DownloadHelper helper, boolean tracksInfoAvailable) {
      if (stopped) {
        return;
      }
      DownloadRequest downloadRequest =
          helper.getDownloadRequest(null, startPositionMs, durationMs);
      updatedMediaItem = downloadRequest.toMediaItem(mediaItem.buildUpon());
      notifyListeners(listener -> listener.onPrepared(mediaItem, updatedMediaItem));
      releaseDownloadHelper();
      if (downloader == null) {
        downloader = downloaderFactory.createDownloader(downloadRequest);
      }
      try {
        downloader.download(new DownloaderProgressListener());
      } catch (InterruptedException e) {
        notifyListeners(
            listener -> listener.onDownloadError(mediaItem, new InterruptedIOException()));
      } catch (IOException e) {
        notifyListeners(listener -> listener.onDownloadError(mediaItem, e));
      }
    }

    @Override
    public void onPrepareError(DownloadHelper helper, IOException e) {
      if (stopped) {
        return;
      }
      releaseDownloadHelper();
      notifyListeners(listener -> listener.onPrepareError(mediaItem, e));
    }
  }

  private final class DownloaderProgressListener implements Downloader.ProgressListener {

    @Override
    public void onProgress(long contentLength, long bytesDownloaded, float percentDownloaded) {
      notifyListeners(
          listener ->
              listener.onPreCacheProgress(
                  mediaItem, contentLength, bytesDownloaded, percentDownloaded));
    }
  }
}
