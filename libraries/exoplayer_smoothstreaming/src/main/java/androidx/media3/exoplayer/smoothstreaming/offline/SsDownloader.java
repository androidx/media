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
package androidx.media3.exoplayer.smoothstreaming.offline;

import static com.google.common.base.Preconditions.checkNotNull;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.offline.SegmentDownloader;
import androidx.media3.exoplayer.smoothstreaming.manifest.SsManifest;
import androidx.media3.exoplayer.smoothstreaming.manifest.SsManifest.StreamElement;
import androidx.media3.exoplayer.smoothstreaming.manifest.SsManifestParser;
import androidx.media3.exoplayer.upstream.ParsingLoadable.Parser;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * A downloader for SmoothStreaming streams.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * SimpleCache cache = new SimpleCache(downloadFolder, new NoOpCacheEvictor(), databaseProvider);
 * CacheDataSource.Factory cacheDataSourceFactory =
 *     new CacheDataSource.Factory()
 *         .setCache(cache)
 *         .setUpstreamDataSourceFactory(new DefaultHttpDataSource.Factory());
 * // Create a downloader for the first track of the first stream element.
 * SsDownloader ssDownloader =
 *     new SsDownloader.Factory(cacheDataSourceFactory)
 *           .create(new MediaItem.Builder()
 *               .setUri(manifestUri)
 *               .setStreamKeys(ImmutableList.of(new StreamKey(0, 0)))
 *               .build());
 * // Perform the download.
 * ssDownloader.download(progressListener);
 * // Use the downloaded data for playback.
 * SsMediaSource mediaSource =
 *     new SsMediaSource.Factory(cacheDataSourceFactory).createMediaSource(mediaItem);
 * }</pre>
 */
@UnstableApi
public final class SsDownloader extends SegmentDownloader<SsManifest> {

  /** A factory for {@linkplain SsDownloader SmoothStreaming downloaders}. */
  public static final class Factory extends BaseFactory<SsManifest> {

    /**
     * Creates a factory for {@link SsDownloader}.
     *
     * @param cacheDataSourceFactory A {@link CacheDataSource.Factory} for the cache into which the
     *     download will be written.
     */
    public Factory(CacheDataSource.Factory cacheDataSourceFactory) {
      super(cacheDataSourceFactory, new SsManifestParser());
    }

    /**
     * Sets a parser for SmoothStreaming manifests.
     *
     * @return This factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setManifestParser(SsManifestParser manifestParser) {
      this.manifestParser = manifestParser;
      return this;
    }

    /**
     * Sets the {@link Executor} used to make requests for the media being downloaded. Providing an
     * {@link Executor} that uses multiple threads will speed up the download by allowing parts of
     * it to be executed in parallel.
     *
     * @return This factory, for convenience.
     */
    @Override
    @CanIgnoreReturnValue
    public Factory setExecutor(Executor executor) {
      super.setExecutor(executor);
      return this;
    }

    /**
     * Sets the maximum difference of the start time of two segments, up to which the segments (of
     * the same URI) should be merged into a single download segment, in milliseconds.
     *
     * @return This factory, for convenience.
     */
    @Override
    @CanIgnoreReturnValue
    public Factory setMaxMergedSegmentStartTimeDiffMs(long maxMergedSegmentStartTimeDiffMs) {
      super.setMaxMergedSegmentStartTimeDiffMs(maxMergedSegmentStartTimeDiffMs);
      return this;
    }

    /**
     * Sets the start position in microseconds that the download should start from.
     *
     * @return This factory, for convenience.
     */
    @Override
    @CanIgnoreReturnValue
    public Factory setStartPositionUs(long startPositionUs) {
      super.setStartPositionUs(startPositionUs);
      return this;
    }

    /**
     * Sets the duration in microseconds from the {@code startPositionUs} to be downloaded, or
     * {@link C#TIME_UNSET} if the media should be downloaded to the end.
     *
     * @return This factory, for convenience.
     */
    @Override
    @CanIgnoreReturnValue
    public Factory setDurationUs(long durationUs) {
      super.setDurationUs(durationUs);
      return this;
    }

    /** Creates {@linkplain SsDownloader SmoothStreaming downloaders}. */
    @Override
    public SsDownloader create(MediaItem mediaItem) {
      return new SsDownloader(
          mediaItem
              .buildUpon()
              .setUri(
                  Util.fixSmoothStreamingIsmManifestUri(
                      checkNotNull(mediaItem.localConfiguration).uri))
              .build(),
          manifestParser,
          cacheDataSourceFactory,
          executor,
          maxMergedSegmentStartTimeDiffMs,
          startPositionUs,
          durationUs);
    }
  }

  /**
   * @deprecated Use {@link SsDownloader.Factory#create(MediaItem)} instead.
   */
  @Deprecated
  public SsDownloader(MediaItem mediaItem, CacheDataSource.Factory cacheDataSourceFactory) {
    this(mediaItem, cacheDataSourceFactory, Runnable::run);
  }

  /**
   * @deprecated Use {@link SsDownloader.Factory#create(MediaItem)} instead.
   */
  @Deprecated
  public SsDownloader(
      MediaItem mediaItem, CacheDataSource.Factory cacheDataSourceFactory, Executor executor) {
    super(
        mediaItem
            .buildUpon()
            .setUri(
                Util.fixSmoothStreamingIsmManifestUri(
                    checkNotNull(mediaItem.localConfiguration).uri))
            .build(),
        new SsManifestParser(),
        cacheDataSourceFactory,
        executor,
        DEFAULT_MAX_MERGED_SEGMENT_START_TIME_DIFF_MS,
        /* startPositionUs= */ 0,
        /* durationUs= */ C.TIME_UNSET);
  }

  /**
   * Creates a new instance.
   *
   * @param mediaItem The {@link MediaItem} to be downloaded.
   * @param manifestParser A parser for SmoothStreaming manifests.
   * @param cacheDataSourceFactory A {@link CacheDataSource.Factory} for the cache into which the
   *     download will be written.
   * @param executor An {@link Executor} used to make requests for the media being downloaded.
   *     Providing an {@link Executor} that uses multiple threads will speed up the download by
   *     allowing parts of it to be executed in parallel.
   * @param maxMergedSegmentStartTimeDiffMs The maximum difference of the start time of two
   *     segments, up to which the segments (of the same URI) should be merged into a single
   *     download segment, in milliseconds.
   * @param startPositionUs The start position in microseconds that the download should start from.
   * @param durationUs The duration in microseconds from the {@code startPositionUs} to be
   *     downloaded, or {@link C#TIME_UNSET} if the media should be downloaded to the end.
   */
  private SsDownloader(
      MediaItem mediaItem,
      Parser<SsManifest> manifestParser,
      CacheDataSource.Factory cacheDataSourceFactory,
      Executor executor,
      long maxMergedSegmentStartTimeDiffMs,
      long startPositionUs,
      long durationUs) {
    super(
        mediaItem,
        manifestParser,
        cacheDataSourceFactory,
        executor,
        maxMergedSegmentStartTimeDiffMs,
        startPositionUs,
        durationUs);
  }

  @Override
  protected List<Segment> getSegments(
      DataSource dataSource, SsManifest manifest, boolean removing) {
    ArrayList<Segment> segments = new ArrayList<>();
    long startPositionUs = removing ? 0 : this.startPositionUs;
    long durationUs = removing ? C.TIME_UNSET : this.durationUs;
    for (StreamElement streamElement : manifest.streamElements) {
      for (int i = 0; i < streamElement.formats.length; i++) {
        for (int j = 0; j < streamElement.chunkCount; j++) {
          long chunkStartTimeUs = streamElement.getStartTimeUs(j);
          long chunkDurationUs = streamElement.getChunkDurationUs(j);
          if (chunkStartTimeUs + chunkDurationUs <= startPositionUs) {
            continue;
          }
          if (durationUs != C.TIME_UNSET && chunkStartTimeUs >= startPositionUs + durationUs) {
            break;
          }
          segments.add(
              new Segment(
                  streamElement.getStartTimeUs(j),
                  new DataSpec(streamElement.buildRequestUri(i, j))));
        }
      }
    }
    return segments;
  }
}
