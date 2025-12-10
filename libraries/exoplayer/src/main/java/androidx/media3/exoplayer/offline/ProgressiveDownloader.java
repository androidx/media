/*
 * Copyright (C) 2017 The Android Open Source Project
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
package androidx.media3.exoplayer.offline;

import static androidx.annotation.VisibleForTesting.PRIVATE;
import static androidx.media3.common.util.Util.percentFloat;
import static com.google.common.base.Preconditions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PriorityTaskManager;
import androidx.media3.common.PriorityTaskManager.PriorityTooLowException;
import androidx.media3.common.util.RunnableFutureTask;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** A downloader for progressive media streams. */
@UnstableApi
public final class ProgressiveDownloader implements Downloader {

  private final Executor executor;

  @VisibleForTesting(otherwise = PRIVATE)
  /* package */ final DataSpec dataSpec;

  private final CacheDataSource dataSource;
  private final CacheWriter cacheWriter;
  @Nullable private final PriorityTaskManager priorityTaskManager;

  @Nullable private ProgressListener progressListener;
  private volatile @MonotonicNonNull RunnableFutureTask<Void, IOException> downloadRunnable;
  private volatile boolean isCanceled;

  /**
   * Creates a new instance.
   *
   * @param mediaItem The media item with a uri to the stream to be downloaded.
   * @param cacheDataSourceFactory A {@link CacheDataSource.Factory} for the cache into which the
   *     download will be written.
   */
  public ProgressiveDownloader(
      MediaItem mediaItem, CacheDataSource.Factory cacheDataSourceFactory) {
    this(mediaItem, cacheDataSourceFactory, /* executor= */ Runnable::run);
  }

  /**
   * Creates a new instance.
   *
   * @param mediaItem The media item with a uri to the stream to be downloaded.
   * @param cacheDataSourceFactory A {@link CacheDataSource.Factory} for the cache into which the
   *     download will be written.
   * @param position The position of the {@link DataSpec} from which the {@link
   *     ProgressiveDownloader} downloads.
   * @param length The length of the {@link DataSpec} for which the {@link ProgressiveDownloader}
   *     downloads.
   */
  public ProgressiveDownloader(
      MediaItem mediaItem,
      CacheDataSource.Factory cacheDataSourceFactory,
      long position,
      long length) {
    this(mediaItem, cacheDataSourceFactory, /* executor= */ Runnable::run, position, length);
  }

  /**
   * Creates a new instance.
   *
   * @param mediaItem The media item with a uri to the stream to be downloaded.
   * @param cacheDataSourceFactory A {@link CacheDataSource.Factory} for the cache into which the
   *     download will be written.
   * @param executor An {@link Executor} used to make requests for the media being downloaded. In
   *     the future, providing an {@link Executor} that uses multiple threads may speed up the
   *     download by allowing parts of it to be executed in parallel.
   */
  public ProgressiveDownloader(
      MediaItem mediaItem, CacheDataSource.Factory cacheDataSourceFactory, Executor executor) {
    this(
        mediaItem,
        cacheDataSourceFactory,
        executor,
        /* position= */ 0,
        /* length= */ C.LENGTH_UNSET);
  }

  /**
   * Creates a new instance.
   *
   * @param mediaItem The media item with a uri to the stream to be downloaded.
   * @param cacheDataSourceFactory A {@link CacheDataSource.Factory} for the cache into which the
   *     download will be written.
   * @param executor An {@link Executor} used to make requests for the media being downloaded. In
   *     the future, providing an {@link Executor} that uses multiple threads may speed up the
   *     download by allowing parts of it to be executed in parallel.
   * @param position The position of the {@link DataSpec} from which the {@link
   *     ProgressiveDownloader} downloads.
   * @param length The length of the {@link DataSpec} for which the {@link ProgressiveDownloader}
   *     downloads.
   */
  public ProgressiveDownloader(
      MediaItem mediaItem,
      CacheDataSource.Factory cacheDataSourceFactory,
      Executor executor,
      long position,
      long length) {
    this.executor = checkNotNull(executor);
    checkNotNull(mediaItem.localConfiguration);
    dataSpec =
        new DataSpec.Builder()
            .setUri(mediaItem.localConfiguration.uri)
            .setKey(mediaItem.localConfiguration.customCacheKey)
            .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
            .setPosition(position)
            .setLength(length)
            .build();
    dataSource = cacheDataSourceFactory.createDataSourceForDownloading();
    @SuppressWarnings("nullness:methodref.receiver.bound")
    CacheWriter.ProgressListener progressListener = this::onProgress;
    cacheWriter =
        new CacheWriter(dataSource, dataSpec, /* temporaryBuffer= */ null, progressListener);
    priorityTaskManager = cacheDataSourceFactory.getUpstreamPriorityTaskManager();
  }

  @Override
  public void download(@Nullable ProgressListener progressListener)
      throws IOException, InterruptedException {
    this.progressListener = progressListener;
    if (priorityTaskManager != null) {
      priorityTaskManager.add(C.PRIORITY_DOWNLOAD);
    }
    try {
      boolean finished = false;
      while (!finished && !isCanceled) {
        // Recreate downloadRunnable on each loop iteration to avoid rethrowing a previous error.
        downloadRunnable =
            new RunnableFutureTask<Void, IOException>() {
              @Override
              protected Void doWork() throws IOException {
                cacheWriter.cache();
                return null;
              }

              @Override
              protected void cancelWork() {
                cacheWriter.cancel();
              }
            };
        if (priorityTaskManager != null) {
          priorityTaskManager.proceed(C.PRIORITY_DOWNLOAD);
        }
        executor.execute(downloadRunnable);
        try {
          downloadRunnable.get();
          finished = true;
        } catch (ExecutionException e) {
          Throwable cause = checkNotNull(e.getCause());
          if (cause instanceof PriorityTooLowException) {
            // The next loop iteration will block until the task is able to proceed.
          } else if (cause instanceof IOException) {
            throw (IOException) cause;
          } else {
            // The cause must be an uncaught Throwable type.
            Util.sneakyThrow(cause);
          }
        }
      }
    } finally {
      // If the main download thread was interrupted as part of cancelation, then it's possible that
      // the runnable is still doing work. We need to wait until it's finished before returning.
      checkNotNull(downloadRunnable).blockUntilFinished();
      if (priorityTaskManager != null) {
        priorityTaskManager.remove(C.PRIORITY_DOWNLOAD);
      }
    }
  }

  @Override
  public void cancel() {
    isCanceled = true;
    RunnableFutureTask<Void, IOException> downloadRunnable = this.downloadRunnable;
    if (downloadRunnable != null) {
      downloadRunnable.cancel(/* interruptIfRunning= */ true);
    }
  }

  @Override
  public void remove() {
    dataSource.getCache().removeResource(dataSource.getCacheKeyFactory().buildCacheKey(dataSpec));
  }

  private void onProgress(long contentLength, long bytesCached, long newBytesCached) {
    if (progressListener == null) {
      return;
    }
    float percentDownloaded =
        contentLength == C.LENGTH_UNSET || contentLength == 0
            ? C.PERCENTAGE_UNSET
            : percentFloat(bytesCached, contentLength);
    checkNotNull(progressListener).onProgress(contentLength, bytesCached, percentDownloaded);
  }
}
