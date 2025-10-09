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

import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.min;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.GuardedBy;
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
import androidx.media3.exoplayer.util.ReleasableExecutor;
import com.google.common.base.Supplier;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

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
    private final DataSource.Factory upstreamDataSourceFactory;
    private final RenderersFactory renderersFactory;
    private TrackSelectionParameters trackSelectionParameters;
    private Executor downloadExecutor;
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
      this.upstreamDataSourceFactory = new DefaultDataSource.Factory(context);
      this.trackSelectionParameters = TrackSelectionParameters.DEFAULT;
      this.renderersFactory = new DefaultRenderersFactory(context);
      this.downloadExecutor = Runnable::run;
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
      this.upstreamDataSourceFactory = new DefaultDataSource.Factory(context);
      this.trackSelectionParameters = TrackSelectionParameters.DEFAULT;
      this.renderersFactory = renderersFactory;
      this.downloadExecutor = Runnable::run;
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
      this.upstreamDataSourceFactory = upstreamDataSourceFactory;
      this.trackSelectionParameters = TrackSelectionParameters.DEFAULT;
      this.renderersFactory = new DefaultRenderersFactory(context);
      this.downloadExecutor = Runnable::run;
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
      this.upstreamDataSourceFactory = upstreamDataSourceFactory;
      this.trackSelectionParameters = TrackSelectionParameters.DEFAULT;
      this.renderersFactory = renderersFactory;
      this.downloadExecutor = Runnable::run;
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
     * <p>The default is {@code Runnable::run}.
     *
     * <p>Passing {@code Runnable::run} will cause each download task to download data on its own
     * thread. Passing an {@link Executor} that uses multiple threads will speed up download tasks
     * that can be split into smaller parts for parallel execution.
     *
     * @return This factory, for convenience.
     */
    @CanIgnoreReturnValue
    public PreCacheHelper.Factory setDownloadExecutor(Executor downloadExecutor) {
      this.downloadExecutor = downloadExecutor;
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
      CacheDataSource.Factory cacheDataSourceFactory =
          new CacheDataSource.Factory()
              .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
              .setCache(cache);
      DownloadHelper.Factory downloadHelperFactory =
          new DownloadHelper.Factory()
              .setDataSourceFactory(cacheDataSourceFactory)
              .setRenderersFactory(renderersFactory)
              .setTrackSelectionParameters(trackSelectionParameters);
      DownloaderFactory downloaderFactory =
          new DefaultDownloaderFactory(cacheDataSourceFactory, downloadExecutor);
      return new PreCacheHelper(
          mediaItem,
          /* testMediaSourceFactory= */ null,
          downloadHelperFactory,
          downloaderFactory,
          preCacheLooper,
          listener);
    }
  }

  @VisibleForTesting /* package */ static final int DEFAULT_MIN_RETRY_COUNT = 5;

  private final MediaItem mediaItem;

  @Nullable private final MediaSource.Factory testMediaSourceFactory;
  private final DownloadHelper.Factory downloadHelperFactory;
  private final DownloaderFactory downloaderFactory;
  @Nullable private final Listener listener;
  private final Handler preCacheHandler;
  private final Handler applicationHandler;
  @Nullable private DownloadCallback currentDownloadCallback;

  /* package */ PreCacheHelper(
      MediaItem mediaItem,
      @Nullable MediaSource.Factory testMediaSourceFactory,
      DownloadHelper.Factory downloadHelperFactory,
      DownloaderFactory downloaderFactory,
      Looper preCacheLooper,
      @Nullable Listener listener) {
    this.mediaItem = mediaItem;
    this.testMediaSourceFactory = testMediaSourceFactory;
    this.downloadHelperFactory = downloadHelperFactory;
    this.downloaderFactory = downloaderFactory;
    this.listener = listener;
    this.preCacheHandler = Util.createHandler(preCacheLooper, /* callback= */ null);
    this.applicationHandler = Util.createHandlerForCurrentOrMainLooper();
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
          if (currentDownloadCallback != null
              && currentDownloadCallback.isReusable(startPositionMs, durationMs)) {
            return;
          } else if (currentDownloadCallback != null) {
            currentDownloadCallback.cancel(/* removeCachedContent= */ false);
          }
          currentDownloadCallback = new DownloadCallback(startPositionMs, durationMs);
        });
  }

  /**
   * Stops the pre-caching.
   *
   * <p>Can be called from any thread.
   */
  public void stop() {
    preCacheHandler.post(
        () -> {
          if (currentDownloadCallback != null) {
            currentDownloadCallback.cancel(/* removeCachedContent= */ false);
          }
        });
  }

  /**
   * Releases the {@link PreCacheHelper}.
   *
   * <p>Can be called from any thread.
   *
   * @param removeCachedContent Whether the cached content should be removed. If {@code true}, the
   *     {@link PreCacheHelper} will create a new thread to remove the cached content.
   */
  public void release(boolean removeCachedContent) {
    preCacheHandler.post(
        () -> {
          if (currentDownloadCallback != null) {
            currentDownloadCallback.cancel(removeCachedContent);
            currentDownloadCallback = null;
          }
          preCacheHandler.removeCallbacksAndMessages(null);
        });
  }

  private static final class ReleasableSingleThreadExecutor implements ReleasableExecutor {

    private final ExecutorService executor;
    private final Runnable releaseRunnable;

    private ReleasableSingleThreadExecutor(Runnable releaseRunnable) {
      this.executor = Util.newSingleThreadExecutor("PreCacheHelper:Loader");
      this.releaseRunnable = releaseRunnable;
    }

    @Override
    public void release() {
      execute(releaseRunnable);
      executor.shutdown();
    }

    @Override
    public void execute(Runnable command) {
      executor.execute(command);
    }
  }

  private static final class ReleasableExecutorSupplier implements Supplier<ReleasableExecutor> {
    private final Handler preCacheHandler;
    private @MonotonicNonNull DownloadCallback downloadCallback;

    @GuardedBy("this")
    private int executorCount;

    private ReleasableExecutorSupplier(Handler preCacheHandler) {
      this.preCacheHandler = preCacheHandler;
    }

    public void setDownloadCallback(DownloadCallback downloadCallback) {
      this.downloadCallback = downloadCallback;
    }

    @Override
    public ReleasableSingleThreadExecutor get() {
      synchronized (ReleasableExecutorSupplier.this) {
        executorCount++;
      }
      return new ReleasableSingleThreadExecutor(this::onExecutorReleased);
    }

    private void onExecutorReleased() {
      synchronized (ReleasableExecutorSupplier.this) {
        checkState(executorCount > 0);
        executorCount--;
        if (wereExecutorsReleased()) {
          preCacheHandler.post(
              () -> {
                checkState(wereExecutorsReleased());
                if (downloadCallback != null) {
                  downloadCallback.maybeSubmitPendingDownloadRequest();
                }
              });
        }
      }
    }

    public boolean wereExecutorsReleased() {
      synchronized (ReleasableExecutorSupplier.this) {
        return executorCount == 0;
      }
    }
  }

  private final class DownloadCallback implements DownloadHelper.Callback {

    private final Object lock;
    private final long startPositionMs;
    private final long durationMs;
    @Nullable private final ReleasableExecutorSupplier releasableExecutorSupplier;
    private final DownloadHelper downloadHelper;

    private boolean isPreparationOngoing;
    @Nullable private DownloadRequest pendingDownloadRequest;
    @Nullable private Downloader downloader;
    @Nullable private Task downloaderTask;

    @GuardedBy("lock")
    private boolean isCanceled;

    public DownloadCallback(long startPositionMs, long durationMs) {
      checkState(Looper.myLooper() == preCacheHandler.getLooper());
      this.lock = new Object();
      this.startPositionMs = startPositionMs;
      this.durationMs = durationMs;
      if (testMediaSourceFactory != null) {
        this.releasableExecutorSupplier = null;
        this.downloadHelper =
            downloadHelperFactory.create(testMediaSourceFactory.createMediaSource(mediaItem));
      } else {
        this.releasableExecutorSupplier = new ReleasableExecutorSupplier(preCacheHandler);
        downloadHelperFactory.setLoadExecutor(releasableExecutorSupplier);
        this.downloadHelper = downloadHelperFactory.create(mediaItem);
        this.releasableExecutorSupplier.setDownloadCallback(this);
      }
      this.isPreparationOngoing = true;
      this.downloadHelper.prepare(this);
    }

    @Override
    public void onPrepared(DownloadHelper helper, boolean tracksInfoAvailable) {
      checkState(Looper.myLooper() == preCacheHandler.getLooper());
      checkState(helper == this.downloadHelper);
      isPreparationOngoing = false;
      DownloadRequest downloadRequest =
          helper.getDownloadRequest(/* data= */ null, startPositionMs, durationMs);
      downloadHelper.release();
      MediaItem updatedMediaItem = downloadRequest.toMediaItem(mediaItem.buildUpon());
      notifyListeners(listener -> listener.onPrepared(mediaItem, updatedMediaItem));
      pendingDownloadRequest = downloadRequest;
      if (releasableExecutorSupplier == null
          || releasableExecutorSupplier.wereExecutorsReleased()) {
        maybeSubmitPendingDownloadRequest();
      }
    }

    @Override
    public void onPrepareError(DownloadHelper helper, IOException e) {
      checkState(Looper.myLooper() == preCacheHandler.getLooper());
      checkState(helper == this.downloadHelper);
      isPreparationOngoing = false;
      downloadHelper.release();
      notifyListeners(listener -> listener.onPrepareError(mediaItem, e));
    }

    public void maybeSubmitPendingDownloadRequest() {
      checkState(Looper.myLooper() == preCacheHandler.getLooper());
      if (pendingDownloadRequest != null) {
        downloader = downloaderFactory.createDownloader(pendingDownloadRequest);
        downloaderTask =
            new Task(
                downloader,
                /* isRemove= */ false,
                DEFAULT_MIN_RETRY_COUNT,
                /* downloadCallback= */ this);
        downloaderTask.start();
        pendingDownloadRequest = null;
      }
    }

    public void onDownloadStopped(Task task) {
      preCacheHandler.post(
          () -> {
            if (task != downloaderTask) {
              return;
            }
            downloaderTask = null;
            @Nullable IOException finalException = task.finalException;
            if (!task.isRemove && finalException != null) {
              notifyListeners(listener -> listener.onDownloadError(mediaItem, finalException));
            }
          });
    }

    public void onDownloadProgress(Task task) {
      preCacheHandler.post(
          () -> {
            if (task != downloaderTask) {
              return;
            }
            notifyListeners(
                listener ->
                    listener.onPreCacheProgress(
                        mediaItem,
                        task.contentLength,
                        task.bytesDownloaded,
                        task.percentDownloaded));
          });
    }

    public void cancel(boolean removeCachedContent) {
      checkState(Looper.myLooper() == preCacheHandler.getLooper());
      synchronized (lock) {
        isCanceled = true;
      }
      pendingDownloadRequest = null;
      downloadHelper.release();
      if (downloaderTask != null && downloaderTask.isRemove) {
        return;
      } else if (downloaderTask != null) {
        downloaderTask.cancel();
      }
      if (removeCachedContent && downloader != null) {
        downloaderTask =
            new Task(
                downloader,
                /* isRemove= */ true,
                DEFAULT_MIN_RETRY_COUNT,
                /* downloadCallback= */ this);
        downloaderTask.start();
      }
    }

    public boolean isReusable(long startPositionMs, long durationMs) {
      checkState(Looper.myLooper() == preCacheHandler.getLooper());
      synchronized (lock) {
        return !isCanceled
            && startPositionMs == this.startPositionMs
            && durationMs == this.durationMs
            && (isPreparationOngoing || (downloaderTask != null && !downloaderTask.isRemove));
      }
    }

    private void notifyListeners(Consumer<Listener> callable) {
      applicationHandler.post(
          () -> {
            synchronized (lock) {
              if (isCanceled) {
                return;
              }
              if (listener != null) {
                callable.accept(listener);
              }
            }
          });
    }
  }

  private static class Task extends Thread implements Downloader.ProgressListener {
    private final Downloader downloader;
    private final boolean isRemove;
    private final int minRetryCount;

    @Nullable private DownloadCallback downloadCallback;
    private volatile boolean isCanceled;
    @Nullable private volatile IOException finalException;
    private volatile long contentLength;
    private volatile long bytesDownloaded;
    private volatile float percentDownloaded;

    private Task(
        Downloader downloader,
        boolean isRemove,
        int minRetryCount,
        DownloadCallback downloadCallback) {
      this.downloader = downloader;
      this.isRemove = isRemove;
      this.minRetryCount = minRetryCount;
      this.downloadCallback = downloadCallback;
      this.contentLength = C.LENGTH_UNSET;
    }

    @SuppressWarnings("nullness:assignment")
    public void cancel() {
      downloadCallback = null;
      if (!isCanceled) {
        isCanceled = true;
        downloader.cancel();
        interrupt();
      }
    }

    // Methods running on download thread.

    @Override
    public void run() {
      try {
        if (isRemove) {
          downloader.remove();
        } else {
          int errorCount = 0;
          long errorPosition = C.LENGTH_UNSET;
          while (!isCanceled) {
            try {
              downloader.download(/* progressListener= */ this);
              break;
            } catch (IOException e) {
              if (!isCanceled) {
                if (this.bytesDownloaded != errorPosition) {
                  errorPosition = this.bytesDownloaded;
                  errorCount = 0;
                }
                if (++errorCount > minRetryCount) {
                  throw e;
                }
                Thread.sleep(getRetryDelayMillis(errorCount));
              }
            }
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (CancellationException e) {
        // Do nothing.
      } catch (IOException e) {
        finalException = e;
      }

      if (downloadCallback != null) {
        downloadCallback.onDownloadStopped(this);
      }
    }

    @Override
    public void onProgress(long contentLength, long bytesDownloaded, float percentDownloaded) {
      this.contentLength = contentLength;
      this.bytesDownloaded = bytesDownloaded;
      this.percentDownloaded = percentDownloaded;
      if (downloadCallback != null) {
        downloadCallback.onDownloadProgress(this);
      }
    }

    private static int getRetryDelayMillis(int errorCount) {
      return min((errorCount - 1) * 1000, 5000);
    }
  }
}
