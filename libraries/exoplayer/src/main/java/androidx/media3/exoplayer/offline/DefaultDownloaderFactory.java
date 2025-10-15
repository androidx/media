/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static androidx.media3.common.util.Util.contains;
import static com.google.common.base.Preconditions.checkNotNull;

import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.cache.CacheDataSource;
import java.util.concurrent.Executor;

/**
 * Default {@link DownloaderFactory}, supporting creation of progressive, DASH, HLS and
 * SmoothStreaming downloaders. Note that for the latter three, the corresponding library module
 * must be built into the application.
 */
@UnstableApi
public class DefaultDownloaderFactory implements DownloaderFactory {

  private final CacheDataSource.Factory cacheDataSourceFactory;
  private final Executor executor;
  private final SparseArray<SegmentDownloaderFactory> segmentDownloaderFactories;

  /**
   * Creates an instance.
   *
   * @param cacheDataSourceFactory A {@link CacheDataSource.Factory} for the cache into which
   *     downloads will be written.
   * @deprecated Use {@link #DefaultDownloaderFactory(CacheDataSource.Factory, Executor)}.
   */
  @Deprecated
  public DefaultDownloaderFactory(CacheDataSource.Factory cacheDataSourceFactory) {
    this(cacheDataSourceFactory, /* executor= */ Runnable::run);
  }

  /**
   * Creates an instance.
   *
   * @param cacheDataSourceFactory A {@link CacheDataSource.Factory} for the cache into which
   *     downloads will be written.
   * @param executor An {@link Executor} used to download data. Passing {@code Runnable::run} will
   *     cause each download task to download data on its own thread. Passing an {@link Executor}
   *     that uses multiple threads will speed up download tasks that can be split into smaller
   *     parts for parallel execution.
   */
  public DefaultDownloaderFactory(
      CacheDataSource.Factory cacheDataSourceFactory, Executor executor) {
    this.cacheDataSourceFactory = checkNotNull(cacheDataSourceFactory);
    this.executor = checkNotNull(executor);
    this.segmentDownloaderFactories = new SparseArray<>();
  }

  @Override
  public Downloader createDownloader(DownloadRequest request) {
    @C.ContentType
    int contentType = Util.inferContentTypeForUriAndMimeType(request.uri, request.mimeType);
    switch (contentType) {
      case C.CONTENT_TYPE_DASH:
      case C.CONTENT_TYPE_HLS:
      case C.CONTENT_TYPE_SS:
        return createSegmentDownloader(request, contentType);
      case C.CONTENT_TYPE_OTHER:
        @Nullable DownloadRequest.ByteRange byteRange = request.byteRange;
        return new ProgressiveDownloader(
            new MediaItem.Builder()
                .setUri(request.uri)
                .setCustomCacheKey(request.customCacheKey)
                .build(),
            cacheDataSourceFactory,
            executor,
            (byteRange != null) ? byteRange.offset : 0,
            (byteRange != null) ? byteRange.length : C.LENGTH_UNSET);
      default:
        throw new IllegalArgumentException("Unsupported type: " + contentType);
    }
  }

  private Downloader createSegmentDownloader(
      DownloadRequest request, @C.ContentType int contentType) {
    SegmentDownloaderFactory downloaderFactory =
        getSegmentDownloaderFactory(contentType, cacheDataSourceFactory);
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(request.uri)
            .setStreamKeys(request.streamKeys)
            .setCustomCacheKey(request.customCacheKey)
            .build();
    if (request.timeRange != null) {
      downloaderFactory
          .setStartPositionUs(request.timeRange.startPositionUs)
          .setDurationUs(request.timeRange.durationUs);
    }
    return downloaderFactory.setExecutor(executor).create(mediaItem);
  }

  // LINT.IfChange
  private SegmentDownloaderFactory getSegmentDownloaderFactory(
      @C.ContentType int contentType, CacheDataSource.Factory cacheDataSourceFactory) {
    if (contains(segmentDownloaderFactories, contentType)) {
      return segmentDownloaderFactories.get(contentType);
    }

    SegmentDownloaderFactory downloaderFactory;
    try {
      downloaderFactory = loadSegmentDownloaderFactory(contentType, cacheDataSourceFactory);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("Module missing for content type " + contentType, e);
    }
    return downloaderFactory;
  }

  private SegmentDownloaderFactory loadSegmentDownloaderFactory(
      @C.ContentType int contentType, CacheDataSource.Factory cacheDataSourceFactory)
      throws ClassNotFoundException {
    SegmentDownloaderFactory factory;
    switch (contentType) {
      case C.CONTENT_TYPE_DASH:
        factory =
            createSegmentDownloaderFactory(
                Class.forName("androidx.media3.exoplayer.dash.offline.DashDownloader$Factory")
                    .asSubclass(SegmentDownloaderFactory.class),
                cacheDataSourceFactory);
        break;
      case C.CONTENT_TYPE_HLS:
        factory =
            createSegmentDownloaderFactory(
                Class.forName("androidx.media3.exoplayer.hls.offline.HlsDownloader$Factory")
                    .asSubclass(SegmentDownloaderFactory.class),
                cacheDataSourceFactory);
        break;
      case C.CONTENT_TYPE_SS:
        factory =
            createSegmentDownloaderFactory(
                Class.forName(
                        "androidx.media3.exoplayer.smoothstreaming.offline.SsDownloader$Factory")
                    .asSubclass(SegmentDownloaderFactory.class),
                cacheDataSourceFactory);
        break;
      default:
        throw new IllegalArgumentException("Unsupported type: " + contentType);
    }
    segmentDownloaderFactories.put(contentType, factory);
    return factory;
  }

  private static SegmentDownloaderFactory createSegmentDownloaderFactory(
      Class<? extends SegmentDownloaderFactory> clazz,
      CacheDataSource.Factory cacheDataSourceFactory) {
    try {
      return clazz
          .getConstructor(CacheDataSource.Factory.class)
          .newInstance(cacheDataSourceFactory);
    } catch (Exception e) {
      throw new IllegalStateException("Downloader factory missing", e);
    }
  }
  // LINT.ThenChange(../../../../../../../proguard-rules.txt)
}
