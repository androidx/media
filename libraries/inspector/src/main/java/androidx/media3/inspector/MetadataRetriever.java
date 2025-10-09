/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.inspector;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.MetadataRetrieverInternal;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.mp4.Mp4Extractor;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * Retrieves information from a {@link MediaItem} without playback.
 *
 * <p>An instance is created for a single {@link MediaItem} via a {@link Builder}. It provides
 * methods to asynchronously retrieve metadata. The instance must be {@link #close() closed} after
 * use to release resources.
 */
@UnstableApi
public final class MetadataRetriever implements AutoCloseable {

  /** Builder for {@link MetadataRetriever} instances. */
  public static final class Builder {

    @Nullable private final Context context;
    private final MediaItem mediaItem;
    @Nullable private MediaSource.Factory mediaSourceFactory;
    private Clock clock;

    /**
     * Creates a new builder.
     *
     * @param context The {@link Context}. Can be {@code null} if a {@link MediaSource.Factory} is
     *     provided via {@link #setMediaSourceFactory(MediaSource.Factory)}.
     * @param mediaItem The {@link MediaItem} to retrieve metadata from.
     */
    public Builder(@Nullable Context context, MediaItem mediaItem) {
      this.context = context != null ? context.getApplicationContext() : null;
      this.mediaItem = checkNotNull(mediaItem);
      this.clock = Clock.DEFAULT;
    }

    /**
     * Sets the {@link MediaSource.Factory} to be used to read the data. If not set, a {@link
     * DefaultMediaSourceFactory} with default extractors will be used.
     *
     * @param mediaSourceFactory The {@link MediaSource.Factory}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setMediaSourceFactory(MediaSource.Factory mediaSourceFactory) {
      this.mediaSourceFactory = checkNotNull(mediaSourceFactory);
      return this;
    }

    /**
     * Sets the {@link Clock} to be used. If not set, {@link Clock#DEFAULT} is used.
     *
     * @param clock The {@link Clock}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setClock(Clock clock) {
      this.clock = checkNotNull(clock);
      return this;
    }

    /** Builds the {@link MetadataRetriever} instance. */
    public MetadataRetriever build() {
      if (mediaSourceFactory == null) {
        checkState(context != null, "Context must be provided if MediaSource.Factory is not set.");
        ExtractorsFactory extractorsFactory =
            new DefaultExtractorsFactory()
                .setMp4ExtractorFlags(
                    Mp4Extractor.FLAG_READ_SEF_DATA | Mp4Extractor.FLAG_OMIT_TRACK_SAMPLE_TABLE);
        mediaSourceFactory = new DefaultMediaSourceFactory(context, extractorsFactory);
      }
      MetadataRetrieverInternal internalRetriever =
          new MetadataRetrieverInternal(mediaItem, checkNotNull(mediaSourceFactory), clock);
      return new MetadataRetriever(internalRetriever);
    }
  }

  /** The default number of maximum parallel retrievals. */
  public static final int DEFAULT_MAXIMUM_PARALLEL_RETRIEVALS = 5;

  private final MetadataRetrieverInternal internalRetriever;

  private MetadataRetriever(MetadataRetrieverInternal internalRetriever) {
    this.internalRetriever = internalRetriever;
  }

  /**
   * Asynchronously retrieves the {@link TrackGroupArray} for the {@link MediaItem}.
   *
   * @return A {@link ListenableFuture} that will be populated with the {@link TrackGroupArray}.
   */
  public ListenableFuture<TrackGroupArray> retrieveTrackGroups() {
    return internalRetriever.retrieveTrackGroups();
  }

  /**
   * Asynchronously retrieves the {@link Timeline} for the {@link MediaItem}.
   *
   * @return A {@link ListenableFuture} that will be populated with the {@link Timeline}.
   */
  public ListenableFuture<Timeline> retrieveTimeline() {
    return internalRetriever.retrieveTimeline();
  }

  /**
   * Asynchronously retrieves the duration for the {@link MediaItem}.
   *
   * @return A {@link ListenableFuture} that will be populated with the duration in microseconds, or
   *     {@link C#TIME_UNSET} if unknown.
   */
  public ListenableFuture<Long> retrieveDurationUs() {
    return internalRetriever.retrieveDurationUs();
  }

  /**
   * Sets the maximum number of metadata retrievals run in parallel.
   *
   * <p>The default is {@link #DEFAULT_MAXIMUM_PARALLEL_RETRIEVALS}.
   *
   * @param maximumParallelRetrievals The maximum number of parallel retrievals.
   */
  public static void setMaximumParallelRetrievals(int maximumParallelRetrievals) {
    checkArgument(maximumParallelRetrievals >= 1);
    MetadataRetrieverInternal.SharedWorkerThread.MAX_PARALLEL_RETRIEVALS.set(
        maximumParallelRetrievals);
  }

  @Override
  public void close() {
    internalRetriever.close();
  }
}
