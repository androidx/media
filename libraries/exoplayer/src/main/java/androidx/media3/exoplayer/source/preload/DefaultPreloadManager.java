/*
 * Copyright 2023 The Android Open Source Project
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.abs;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.content.Context;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRendererCapabilitiesList;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.PlaybackLooperProvider;
import androidx.media3.exoplayer.RendererCapabilitiesList;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SampleQueue;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.Executor;

/**
 * A preload manager that preloads with the {@link PreloadMediaSource} to load the media data into
 * the {@link SampleQueue}.
 */
@UnstableApi
public final class DefaultPreloadManager
    extends BasePreloadManager<Integer, DefaultPreloadManager.PreloadStatus> {

  /** A builder for {@link DefaultPreloadManager} instances. */
  public static final class Builder extends BuilderBase<Integer, PreloadStatus> {

    private final Context context;
    private PlaybackLooperProvider preloadLooperProvider;
    private TrackSelector.Factory trackSelectorFactory;
    private Supplier<BandwidthMeter> bandwidthMeterSupplier;
    private Supplier<RenderersFactory> renderersFactorySupplier;
    private Supplier<LoadControl> loadControlSupplier;
    @Nullable private Cache cache;
    private Executor cachingExecutor;
    private Clock clock;
    private boolean buildCalled;
    private boolean buildExoPlayerCalled;

    /**
     * Creates a builder.
     *
     * @param context A {@link Context}.
     * @param targetPreloadStatusControl A {@link TargetPreloadStatusControl}.
     */
    public Builder(
        Context context,
        TargetPreloadStatusControl<Integer, PreloadStatus> targetPreloadStatusControl) {
      super(
          new SimpleRankingDataComparator(),
          targetPreloadStatusControl,
          new MediaSourceFactorySupplier(context));
      this.context = context;
      this.preloadLooperProvider = new PlaybackLooperProvider();
      this.trackSelectorFactory = DefaultTrackSelector::new;
      this.bandwidthMeterSupplier = () -> DefaultBandwidthMeter.getSingletonInstance(context);
      this.renderersFactorySupplier = Suppliers.memoize(() -> new DefaultRenderersFactory(context));
      this.loadControlSupplier = Suppliers.memoize(DefaultLoadControl::new);
      this.cachingExecutor = Runnable::run;
      this.clock = Clock.DEFAULT;
    }

    /**
     * Sets the {@link MediaSource.Factory} that will be used by the built {@link
     * DefaultPreloadManager} and {@link ExoPlayer}.
     *
     * <p>The default is a {@link DefaultMediaSourceFactory}.
     *
     * @param mediaSourceFactory A {@link MediaSource.Factory}
     * @return This builder.
     * @throws IllegalStateException If {@link #build()}, {@link #buildExoPlayer()} or {@link
     *     #buildExoPlayer(ExoPlayer.Builder)} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setMediaSourceFactory(MediaSource.Factory mediaSourceFactory) {
      checkState(!buildCalled && !buildExoPlayerCalled);
      ((MediaSourceFactorySupplier) this.mediaSourceFactorySupplier)
          .setCustomMediaSourceFactory(mediaSourceFactory);
      return this;
    }

    /**
     * Sets the {@link RenderersFactory} that will be used by the built {@link
     * DefaultPreloadManager} and {@link ExoPlayer}.
     *
     * <p>The default is a {@link DefaultRenderersFactory}.
     *
     * @param renderersFactory A {@link RenderersFactory}.
     * @return This builder.
     * @throws IllegalStateException If {@link #build()}, {@link #buildExoPlayer()} or {@link
     *     #buildExoPlayer(ExoPlayer.Builder)} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setRenderersFactory(RenderersFactory renderersFactory) {
      checkState(!buildCalled && !buildExoPlayerCalled);
      this.renderersFactorySupplier = () -> renderersFactory;
      return this;
    }

    /**
     * Sets the {@link TrackSelector.Factory} that will be used by the built {@link
     * DefaultPreloadManager} and {@link ExoPlayer}.
     *
     * <p>The default is a {@link TrackSelector.Factory} that always creates a new {@link
     * DefaultTrackSelector}.
     *
     * @param trackSelectorFactory A {@link TrackSelector.Factory}.
     * @return This builder.
     * @throws IllegalStateException If {@link #build()}, {@link #buildExoPlayer()} or {@link
     *     #buildExoPlayer(ExoPlayer.Builder)} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setTrackSelectorFactory(TrackSelector.Factory trackSelectorFactory) {
      checkState(!buildCalled && !buildExoPlayerCalled);
      this.trackSelectorFactory = trackSelectorFactory;
      return this;
    }

    /**
     * Sets the {@link LoadControl} that will be used by the built {@link DefaultPreloadManager} and
     * {@link ExoPlayer}.
     *
     * <p>The default is a {@link DefaultLoadControl}.
     *
     * @param loadControl A {@link LoadControl}.
     * @return This builder.
     * @throws IllegalStateException If {@link #build()}, {@link #buildExoPlayer()} or {@link
     *     #buildExoPlayer(ExoPlayer.Builder)} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setLoadControl(LoadControl loadControl) {
      checkState(!buildCalled && !buildExoPlayerCalled);
      this.loadControlSupplier = () -> loadControl;
      return this;
    }

    /**
     * Sets the {@link BandwidthMeter} that will be used by the built {@link DefaultPreloadManager}
     * and {@link ExoPlayer}.
     *
     * <p>The default is a {@link DefaultBandwidthMeter}.
     *
     * @param bandwidthMeter A {@link BandwidthMeter}.
     * @return This builder.
     * @throws IllegalStateException If {@link #build()}, {@link #buildExoPlayer()} or {@link
     *     #buildExoPlayer(ExoPlayer.Builder)} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setBandwidthMeter(BandwidthMeter bandwidthMeter) {
      checkState(!buildCalled && !buildExoPlayerCalled);
      this.bandwidthMeterSupplier = () -> bandwidthMeter;
      return this;
    }

    /**
     * Sets the {@link Looper} that will be used for preload and playback.
     *
     * <p>The backing thread should run with priority {@link Process#THREAD_PRIORITY_AUDIO} and
     * should handle messages within 10ms.
     *
     * <p>The default is a looper that is associated with a new thread created internally.
     *
     * @param preloadLooper A {@link Looper}.
     * @return This builder.
     * @throws IllegalStateException If {@link #build()}, {@link #buildExoPlayer()} or {@link
     *     #buildExoPlayer(ExoPlayer.Builder)} has already been called, or when the {@linkplain
     *     Looper#getMainLooper() main looper} is passed in.
     */
    @CanIgnoreReturnValue
    public Builder setPreloadLooper(Looper preloadLooper) {
      checkState(!buildCalled && !buildExoPlayerCalled && preloadLooper != Looper.getMainLooper());
      this.preloadLooperProvider = new PlaybackLooperProvider(preloadLooper);
      return this;
    }

    /**
     * Sets the {@link Cache} that will be used for caching the media items.
     *
     * <p>The default is {@code null}. If an app will return {@link
     * PreloadStatus#specifiedRangeCached(long, long)} or {@link
     * PreloadStatus#specifiedRangeCached(long)} in its implementation of {@link
     * TargetPreloadStatusControl}, a non-null instance must be passed.
     *
     * @param cache A {@link Cache}.
     * @return This builder.
     * @throws IllegalStateException If {@link #build()}, {@link #buildExoPlayer()} or {@link
     *     #buildExoPlayer(ExoPlayer.Builder)} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setCache(@Nullable Cache cache) {
      checkState(!buildCalled && !buildExoPlayerCalled);
      this.cache = cache;
      ((MediaSourceFactorySupplier) this.mediaSourceFactorySupplier).setCache(cache);
      return this;
    }

    /**
     * Sets an {@link Executor} used to cache data.
     *
     * <p>The default is {@code Runnable::run}, which will cause each caching task to download data
     * on a separate download thread. Passing an {@link Executor} that uses multiple threads will
     * speed up caching tasks that can be split into smaller parts for parallel execution.
     *
     * @param executor An {@link Executor}.
     * @return This builder.
     * @throws IllegalStateException If {@link #build()}, {@link #buildExoPlayer()} or {@link
     *     #buildExoPlayer(ExoPlayer.Builder)} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setCachingExecutor(Executor executor) {
      checkState(!buildCalled && !buildExoPlayerCalled);
      this.cachingExecutor = executor;
      return this;
    }

    /**
     * Sets the {@link Clock} that will be used the {@link DefaultPreloadManager}. Should only be
     * set for testing purposes.
     *
     * @return This builder.
     */
    @VisibleForTesting
    @CanIgnoreReturnValue
    public Builder setClock(Clock clock) {
      this.clock = clock;
      return this;
    }

    /**
     * Builds an {@link ExoPlayer}.
     *
     * <p>See {@link #buildExoPlayer(ExoPlayer.Builder)} for the list of values populated on and
     * resulting from this builder that the built {@link ExoPlayer} uses.
     *
     * <p>For the other configurations than above, the built {@link ExoPlayer} uses the default
     * values, see {@link ExoPlayer.Builder#Builder(Context)} for the list of default values.
     *
     * @return An {@link ExoPlayer} instance.
     */
    public ExoPlayer buildExoPlayer() {
      return buildExoPlayer(new ExoPlayer.Builder(context));
    }

    /**
     * Builds an {@link ExoPlayer} with an {@link ExoPlayer.Builder} passed in.
     *
     * <p>The built {@link ExoPlayer} uses the following values populated on and resulting from this
     * builder:
     *
     * <ul>
     *   <li>{@link #setMediaSourceFactory(MediaSource.Factory) MediaSource.Factory}
     *   <li>{@link #setRenderersFactory(RenderersFactory) RenderersFactory}
     *   <li>{@link #setTrackSelectorFactory(TrackSelector.Factory) TrackSelector.Factory}
     *   <li>{@link #setLoadControl(LoadControl) LoadControl}
     *   <li>{@link #setBandwidthMeter(BandwidthMeter) BandwidthMeter}
     *   <li>{@linkplain #setPreloadLooper(Looper)} preload looper}
     * </ul>
     *
     * <p>For the other configurations than above, the built {@link ExoPlayer} uses the values from
     * the passed {@link ExoPlayer.Builder}.
     *
     * @param exoPlayerBuilder An {@link ExoPlayer.Builder} that is used to build the {@link
     *     ExoPlayer}.
     * @return An {@link ExoPlayer} instance.
     */
    public ExoPlayer buildExoPlayer(ExoPlayer.Builder exoPlayerBuilder) {
      buildExoPlayerCalled = true;
      return exoPlayerBuilder
          .setMediaSourceFactory(mediaSourceFactorySupplier.get())
          .setBandwidthMeter(bandwidthMeterSupplier.get())
          .setRenderersFactory(renderersFactorySupplier.get())
          .setLoadControl(loadControlSupplier.get())
          .setPlaybackLooperProvider(preloadLooperProvider)
          .setTrackSelector(trackSelectorFactory.createTrackSelector(context))
          .build();
    }

    /**
     * Builds a {@link DefaultPreloadManager} instance.
     *
     * @throws IllegalStateException If this method has already been called.
     */
    @Override
    public DefaultPreloadManager build() {
      checkState(!buildCalled);
      buildCalled = true;
      return new DefaultPreloadManager(this);
    }
  }

  /** Defines the preload status for the {@link DefaultPreloadManager}. */
  public static final class PreloadStatus {

    /**
     * Stages for the preload status. One of {@link #STAGE_NOT_PRELOADED}, {@link
     * #STAGE_SPECIFIED_RANGE_CACHED}, {@link #STAGE_SOURCE_PREPARED}, {@link
     * #STAGE_TRACKS_SELECTED} or {@link #STAGE_SPECIFIED_RANGE_LOADED}.
     */
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    @Target(TYPE_USE)
    @IntDef(
        value = {
          STAGE_NOT_PRELOADED,
          STAGE_SPECIFIED_RANGE_CACHED,
          STAGE_SOURCE_PREPARED,
          STAGE_TRACKS_SELECTED,
          STAGE_SPECIFIED_RANGE_LOADED
        })
    public @interface Stage {}

    /** The media item has not preloaded yet. */
    public static final int STAGE_NOT_PRELOADED = Integer.MIN_VALUE;

    /**
     * The media item has been cached for a specific range defined by {@code startPositionMs} and
     * {@code durationMs}.
     */
    public static final int STAGE_SPECIFIED_RANGE_CACHED = -1;

    /** The {@link PreloadMediaSource} has completed preparation. */
    public static final int STAGE_SOURCE_PREPARED = 0;

    /** The {@link PreloadMediaSource} has tracks selected. */
    public static final int STAGE_TRACKS_SELECTED = 1;

    /**
     * The {@link PreloadMediaSource} has been loaded for a specific range defined by {@code
     * startPositionMs} and {@code durationMs}.
     */
    public static final int STAGE_SPECIFIED_RANGE_LOADED = 2;

    /** A {@link PreloadStatus} indicating that the media item should not be preloaded. */
    public static final PreloadStatus PRELOAD_STATUS_NOT_PRELOADED =
        new PreloadStatus(
            STAGE_NOT_PRELOADED, /* startPositionMs= */ C.TIME_UNSET, /* durationMs= */ 0);

    /** A {@link PreloadStatus} indicating that the source has completed preparation. */
    public static final PreloadStatus PRELOAD_STATUS_SOURCE_PREPARED =
        new PreloadStatus(
            STAGE_SOURCE_PREPARED, /* startPositionMs= */ C.TIME_UNSET, /* durationMs= */ 0);

    /** A {@link PreloadStatus} indicating that the source has tracks selected. */
    public static final PreloadStatus PRELOAD_STATUS_TRACKS_SELECTED =
        new PreloadStatus(
            STAGE_TRACKS_SELECTED, /* startPositionMs= */ C.TIME_UNSET, /* durationMs= */ 0);

    /** The stage for the preload status. */
    public final @Stage int stage;

    /**
     * The start position in milliseconds from which the source should be loaded, or {@link
     * C#TIME_UNSET} to indicate the default start position.
     */
    public final long startPositionMs;

    /**
     * The duration in milliseconds for which the source should be loaded from the {@code
     * startPositionMs}, or {@link C#TIME_UNSET} to indicate that the source should be loaded to end
     * of source.
     */
    public final long durationMs;

    private PreloadStatus(@Stage int stage, long startPositionMs, long durationMs) {
      checkArgument(startPositionMs == C.TIME_UNSET || startPositionMs >= 0);
      checkArgument(durationMs == C.TIME_UNSET || durationMs >= 0);
      this.stage = stage;
      this.startPositionMs = startPositionMs;
      this.durationMs = durationMs;
    }

    /**
     * Returns a {@link PreloadStatus} indicating to load the source from the default start position
     * and for the specified duration (in milliseconds).
     */
    public static PreloadStatus specifiedRangeLoaded(long durationMs) {
      return new PreloadStatus(
          STAGE_SPECIFIED_RANGE_LOADED, /* startPositionMs= */ C.TIME_UNSET, durationMs);
    }

    /**
     * Returns a {@link PreloadStatus} indicating to load the source from the specified start
     * position (in milliseconds) and for the specified duration (in milliseconds).
     */
    public static PreloadStatus specifiedRangeLoaded(long startPositionMs, long durationMs) {
      return new PreloadStatus(STAGE_SPECIFIED_RANGE_LOADED, startPositionMs, durationMs);
    }

    /**
     * Returns a {@link PreloadStatus} indicating to cache the media item from the default start
     * position and for the specified duration (in milliseconds).
     */
    public static PreloadStatus specifiedRangeCached(long durationMs) {
      return new PreloadStatus(
          STAGE_SPECIFIED_RANGE_CACHED, /* startPositionMs= */ C.TIME_UNSET, durationMs);
    }

    /**
     * Returns a {@link PreloadStatus} indicating to cache the media item from the specified start
     * position (in milliseconds) and for the specified duration (in milliseconds).
     */
    public static PreloadStatus specifiedRangeCached(long startPositionMs, long durationMs) {
      return new PreloadStatus(STAGE_SPECIFIED_RANGE_CACHED, startPositionMs, durationMs);
    }

    private boolean isPreloadingCategory() {
      return stage == STAGE_SOURCE_PREPARED
          || stage == STAGE_TRACKS_SELECTED
          || stage == STAGE_SPECIFIED_RANGE_LOADED;
    }

    private boolean isPreCachingCategory() {
      return stage == STAGE_SPECIFIED_RANGE_CACHED;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null || getClass() != obj.getClass()) {
        return false;
      }
      PreloadStatus other = (PreloadStatus) obj;
      return stage == other.stage
          && startPositionMs == other.startPositionMs
          && durationMs == other.durationMs;
    }

    @Override
    public int hashCode() {
      int result = 17;
      result = 31 * result + stage;
      result = 31 * result + (int) startPositionMs;
      result = 31 * result + (int) durationMs;
      return result;
    }
  }

  private final RendererCapabilitiesList rendererCapabilitiesList;
  private final TrackSelector trackSelector;
  private final PlaybackLooperProvider preloadLooperProvider;
  private final PreloadMediaSource.Factory preloadMediaSourceFactory;
  @Nullable private final HandlerThread preCacheThread;
  @Nullable private final PreCacheHelper.Factory preCacheHelperFactory;
  private final HandlerWrapper preloadHandler;
  private boolean releaseCalled;

  private DefaultPreloadManager(Builder builder) {
    super(
        new SimpleRankingDataComparator(),
        builder.targetPreloadStatusControl,
        builder.mediaSourceFactorySupplier.get());
    rendererCapabilitiesList =
        new DefaultRendererCapabilitiesList.Factory(builder.renderersFactorySupplier.get())
            .createRendererCapabilitiesList();
    preloadLooperProvider = builder.preloadLooperProvider;
    trackSelector = builder.trackSelectorFactory.createTrackSelector(builder.context);
    BandwidthMeter bandwidthMeter = builder.bandwidthMeterSupplier.get();
    trackSelector.init(() -> {}, bandwidthMeter);
    Looper preloadLooper = preloadLooperProvider.obtainLooper();
    preloadMediaSourceFactory =
        new PreloadMediaSource.Factory(
                builder.mediaSourceFactorySupplier.get(),
                new PreloadMediaSourceControl(),
                trackSelector,
                bandwidthMeter,
                rendererCapabilitiesList.getRendererCapabilities(),
                builder.loadControlSupplier.get(),
                preloadLooper)
            .setClock(builder.clock);
    @Nullable Cache cache = builder.cache;
    if (cache != null) {
      preCacheThread = new HandlerThread("DefaultPreloadManager:PreCacheHelper");
      preCacheThread.start();
      preCacheHelperFactory =
          new PreCacheHelper.Factory(builder.context, cache, preCacheThread.getLooper())
              .setDownloadExecutor(builder.cachingExecutor)
              .setListener(new PreCacheHelperListener());
    } else {
      preCacheThread = null;
      preCacheHelperFactory = null;
    }
    preloadHandler = builder.clock.createHandler(preloadLooper, /* callback= */ null);
  }

  /**
   * Sets the index of the current playing media.
   *
   * @param currentPlayingIndex The index of current playing media.
   */
  public void setCurrentPlayingIndex(int currentPlayingIndex) {
    SimpleRankingDataComparator rankingDataComparator =
        (SimpleRankingDataComparator) this.rankingDataComparator;
    rankingDataComparator.setCurrentPlayingIndex(currentPlayingIndex);
  }

  @Override
  protected MediaSourceHolder createMediaSourceHolder(
      MediaItem mediaItem, @Nullable MediaSource mediaSource, Integer rankingData) {
    PreloadMediaSource preloadMediaSource =
        mediaSource != null
            ? preloadMediaSourceFactory.createMediaSource(mediaSource)
            : preloadMediaSourceFactory.createMediaSource(mediaItem);
    return new PreloadMediaSourceHolder(mediaItem, preloadMediaSource, rankingData);
  }

  @Override
  protected void preloadMediaSourceHolderInternal(
      MediaSourceHolder mediaSourceHolder, PreloadStatus targetPreloadStatus) {
    if (releaseCalled) {
      return;
    }
    checkArgument(mediaSourceHolder instanceof PreloadMediaSourceHolder);
    PreloadMediaSourceHolder preloadMediaSourceHolder =
        (PreloadMediaSourceHolder) mediaSourceHolder;
    PreloadMediaSource preloadMediaSource = preloadMediaSourceHolder.getMediaSource();
    maybeClearPreloadMediaSource(preloadMediaSource, targetPreloadStatus);
    if (targetPreloadStatus.equals(PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED)) {
      onSkipped(
          preloadMediaSource,
          preloadStatus -> preloadStatus.stage == PreloadStatus.STAGE_NOT_PRELOADED);
    } else if (targetPreloadStatus.stage == PreloadStatus.STAGE_SPECIFIED_RANGE_CACHED) {
      if (preloadMediaSourceHolder.preCacheHelper == null) {
        PreCacheHelper.Factory preCacheHelperFactory =
            checkNotNull(
                this.preCacheHelperFactory, "DefaultPreloadManager wasn't configured with a Cache");
        preloadMediaSourceHolder.preCacheHelper =
            preCacheHelperFactory.create(mediaSourceHolder.mediaItem);
      }
      checkNotNull(preloadMediaSourceHolder.preCacheHelper)
          .preCache(targetPreloadStatus.startPositionMs, targetPreloadStatus.durationMs);
    } else {
      preloadMediaSource.preload(Util.msToUs(targetPreloadStatus.startPositionMs));
    }
  }

  private void maybeClearPreloadMediaSource(
      PreloadMediaSource preloadMediaSource, PreloadStatus targetPreloadStatus) {
    if (targetPreloadStatus.stage == PreloadStatus.STAGE_NOT_PRELOADED
        || targetPreloadStatus.stage == PreloadStatus.STAGE_SPECIFIED_RANGE_CACHED
        || targetPreloadStatus.stage == PreloadStatus.STAGE_SOURCE_PREPARED) {
      // For simplicity, we only attempt to clear the in-memory sample data when the apps
      // temporarily don't require the current media to be loaded, which means that the cached
      // content on disk may still persist. All data will be removed only when this media is
      // removed from DefaultPreloadManager.
      preloadMediaSource.clear();
    }
  }

  @Override
  protected void releaseMediaSourceHolderInternal(MediaSourceHolder mediaSourceHolder) {
    if (releaseCalled) {
      return;
    }
    super.releaseMediaSourceHolderInternal(mediaSourceHolder);
    checkArgument(mediaSourceHolder instanceof PreloadMediaSourceHolder);
    PreloadMediaSourceHolder preloadMediaSourceHolder =
        (PreloadMediaSourceHolder) mediaSourceHolder;
    preloadMediaSourceHolder.getMediaSource().releasePreloadMediaSource();
    if (preloadMediaSourceHolder.preCacheHelper != null) {
      preloadMediaSourceHolder.preCacheHelper.release(/* removeCachedContent= */ true);
      preloadMediaSourceHolder.preCacheHelper = null;
    }
  }

  @Override
  protected void releaseInternal() {
    releaseCalled = true;
    releasePreloadUtils();
    releasePreCacheUtils();
  }

  private void releasePreloadUtils() {
    preloadHandler.post(
        () -> {
          rendererCapabilitiesList.release();
          trackSelector.release();
          preloadLooperProvider.releaseLooper();
        });
  }

  private void releasePreCacheUtils() {
    if (preCacheThread != null) {
      preCacheThread.quit();
    }
  }

  private static final class SimpleRankingDataComparator implements RankingDataComparator<Integer> {

    private int currentPlayingIndex;
    @Nullable private InvalidationListener invalidationListener;

    public SimpleRankingDataComparator() {
      this.currentPlayingIndex = C.INDEX_UNSET;
    }

    @Override
    public int compare(Integer o1, Integer o2) {
      return Integer.compare(abs(o1 - currentPlayingIndex), abs(o2 - currentPlayingIndex));
    }

    @Override
    public void setInvalidationListener(@Nullable InvalidationListener invalidationListener) {
      this.invalidationListener = invalidationListener;
    }

    public void setCurrentPlayingIndex(int currentPlayingIndex) {
      if (currentPlayingIndex != this.currentPlayingIndex) {
        this.currentPlayingIndex = currentPlayingIndex;
        if (invalidationListener != null) {
          invalidationListener.onRankingDataComparatorInvalidated();
        }
      }
    }
  }

  private final class PreCacheHelperListener implements PreCacheHelper.Listener {

    @Override
    public void onPrepared(MediaItem originalMediaItem, MediaItem updatedMediaItem) {
      PreloadStatus targetPreloadStatus =
          getTargetPreloadStatusIfCurrentlyPreloading(originalMediaItem);
      if (targetPreloadStatus == null || !targetPreloadStatus.isPreCachingCategory()) {
        // If the originalMediaItem is not the currently pre-caching, skip silently as invalidate()
        // must have called.
        return;
      }
      MediaSource updatedMediaSource =
          preloadMediaSourceFactory.createMediaSource(updatedMediaItem);
      onMediaSourceUpdated(originalMediaItem, updatedMediaSource);
    }

    @Override
    public void onPreCacheProgress(
        MediaItem mediaItem, long contentLength, long bytesDownloaded, float percentageDownloaded) {
      if (percentageDownloaded == 100f) {
        PreloadStatus targetPreloadStatus = getTargetPreloadStatusIfCurrentlyPreloading(mediaItem);
        if (targetPreloadStatus == null || !targetPreloadStatus.isPreCachingCategory()) {
          // If the mediaItem is not the currently caching, skip silently as invalidate() must have
          // been called, and a new sequence of preloading must have started.
          return;
        }
        DefaultPreloadManager.this.onCompleted(
            mediaItem, preloadStatus -> preloadStatus.equals(targetPreloadStatus));
      }
    }

    @Override
    public void onPrepareError(MediaItem mediaItem, IOException error) {
      PreloadStatus targetPreloadStatus = getTargetPreloadStatusIfCurrentlyPreloading(mediaItem);
      if (targetPreloadStatus == null || !targetPreloadStatus.isPreCachingCategory()) {
        // If the mediaItem is not the currently caching, skip silently as invalidate() must have
        // been called, and a new sequence of preloading must have started.
        return;
      }
      PreloadException preloadException =
          new PreloadException(mediaItem, /* message= */ null, error);
      DefaultPreloadManager.this.onError(
          preloadException, mediaItem, preloadStatus -> preloadStatus.equals(targetPreloadStatus));
    }

    @Override
    public void onDownloadError(MediaItem mediaItem, IOException error) {
      PreloadStatus targetPreloadStatus = getTargetPreloadStatusIfCurrentlyPreloading(mediaItem);
      if (targetPreloadStatus == null || !targetPreloadStatus.isPreCachingCategory()) {
        // If the mediaItem is not the currently caching, skip silently as invalidate() must have
        // been called, and a new sequence of preloading must have started.
        return;
      }
      PreloadException preloadException =
          new PreloadException(mediaItem, /* message= */ null, error);
      DefaultPreloadManager.this.onError(
          preloadException, mediaItem, preloadStatus -> preloadStatus.equals(targetPreloadStatus));
    }
  }

  private final class PreloadMediaSourceControl implements PreloadMediaSource.PreloadControl {
    @Override
    public boolean onSourcePrepared(PreloadMediaSource mediaSource) {
      return continueOrCompletePreloading(
          mediaSource,
          /* continueLoadingPredicate= */ status ->
              status.stage > PreloadStatus.STAGE_SOURCE_PREPARED);
    }

    @Override
    public boolean onTracksSelected(PreloadMediaSource mediaSource) {
      return continueOrCompletePreloading(
          mediaSource,
          /* continueLoadingPredicate= */ status ->
              status.stage > PreloadStatus.STAGE_TRACKS_SELECTED);
    }

    @Override
    public boolean onContinueLoadingRequested(
        PreloadMediaSource mediaSource, long bufferedDurationUs) {
      return continueOrCompletePreloading(
          mediaSource,
          /* continueLoadingPredicate= */ status ->
              status.stage == PreloadStatus.STAGE_SPECIFIED_RANGE_LOADED
                  && status.durationMs != C.TIME_UNSET
                  && status.durationMs > Util.usToMs(bufferedDurationUs));
    }

    @Override
    public void onUsedByPlayer(PreloadMediaSource mediaSource) {
      PreloadStatus targetPreloadStatus = getTargetPreloadStatusIfCurrentlyPreloading(mediaSource);
      if (targetPreloadStatus == null || !targetPreloadStatus.isPreloadingCategory()) {
        // If the mediaSource is not the currently loading, skip silently as invalidate() must have
        // been called, and a new sequence of preloading must have started.
        return;
      }
      DefaultPreloadManager.this.onSkipped(
          mediaSource, preloadStatus -> preloadStatus.equals(targetPreloadStatus));
    }

    @Override
    public void onLoadedToTheEndOfSource(PreloadMediaSource mediaSource) {
      PreloadStatus targetPreloadStatus = getTargetPreloadStatusIfCurrentlyPreloading(mediaSource);
      if (targetPreloadStatus == null || !targetPreloadStatus.isPreloadingCategory()) {
        // If the mediaSource is not the currently loading, skip silently as invalidate() must have
        // been called, and a new sequence of preloading must have started.
        return;
      }
      DefaultPreloadManager.this.onCompleted(
          mediaSource, preloadStatus -> preloadStatus.equals(targetPreloadStatus));
    }

    @Override
    public void onPreloadError(PreloadException error, PreloadMediaSource mediaSource) {
      @Nullable
      PreloadStatus targetPreloadStatus = getTargetPreloadStatusIfCurrentlyPreloading(mediaSource);
      if (targetPreloadStatus == null || !targetPreloadStatus.isPreloadingCategory()) {
        // If the mediaSource is not the currently loading, skip silently as invalidate() must have
        // been called, and a new sequence of preloading must have started.
        return;
      }
      DefaultPreloadManager.this.onError(
          error, mediaSource, preloadStatus -> preloadStatus.equals(targetPreloadStatus));
    }

    @Override
    public boolean onLoadingUnableToContinue(PreloadMediaSource mediaSource) {
      @Nullable MediaSourceHolder sourceHolder = getMediaSourceHolderToClear();
      if (sourceHolder != null) {
        PreloadMediaSource lowestPriorityPreloadMediaSource =
            (PreloadMediaSource) sourceHolder.getMediaSource();
        lowestPriorityPreloadMediaSource.clear();
        DefaultPreloadManager.this.onSourceCleared();
        return true;
      }
      return false;
    }

    private boolean continueOrCompletePreloading(
        PreloadMediaSource mediaSource, Predicate<PreloadStatus> continueLoadingPredicate) {
      PreloadStatus targetPreloadStatus = getTargetPreloadStatusIfCurrentlyPreloading(mediaSource);
      if (targetPreloadStatus != null && targetPreloadStatus.isPreloadingCategory()) {
        if (continueLoadingPredicate.apply(targetPreloadStatus)) {
          return true;
        }
        DefaultPreloadManager.this.onCompleted(
            mediaSource, preloadStatus -> preloadStatus.equals(targetPreloadStatus));
      }
      // If the mediaSource is not the currently loading, skip silently as invalidate() must have
      // been called, and a new sequence of preloading must have started.
      return false;
    }
  }

  private static class MediaSourceFactorySupplier implements Supplier<MediaSource.Factory> {

    private final Context context;
    private final Supplier<DefaultMediaSourceFactory> defaultMediaSourceFactorySupplier;

    @Nullable private MediaSource.Factory customMediaSourceFactory;
    @Nullable private Cache cache;

    public MediaSourceFactorySupplier(Context context) {
      this.context = context;
      defaultMediaSourceFactorySupplier =
          Suppliers.memoize(() -> new DefaultMediaSourceFactory(context));
    }

    public void setCache(@Nullable Cache cache) {
      this.cache = cache;
    }

    public void setCustomMediaSourceFactory(@Nullable MediaSource.Factory mediaSourceFactory) {
      this.customMediaSourceFactory = mediaSourceFactory;
    }

    @Override
    public MediaSource.Factory get() {
      if (customMediaSourceFactory != null) {
        return customMediaSourceFactory;
      }
      DefaultMediaSourceFactory defaultMediaSourceFactory = defaultMediaSourceFactorySupplier.get();
      @Nullable Cache cache = this.cache;
      if (cache != null) {
        CacheDataSource.Factory cacheDataSourceFactory =
            new CacheDataSource.Factory()
                .setUpstreamDataSourceFactory(new DefaultDataSource.Factory(context))
                .setCache(cache)
                .setCacheWriteDataSinkFactory(null);
        defaultMediaSourceFactory.setDataSourceFactory(cacheDataSourceFactory);
      }
      return defaultMediaSourceFactory;
    }
  }

  private final class PreloadMediaSourceHolder extends MediaSourceHolder {

    @Nullable public PreCacheHelper preCacheHelper;

    public PreloadMediaSourceHolder(
        MediaItem mediaItem, PreloadMediaSource mediaSource, Integer rankingData) {
      super(mediaItem, rankingData, mediaSource);
    }

    @Override
    public synchronized PreloadMediaSource getMediaSource() {
      return (PreloadMediaSource) super.getMediaSource();
    }

    @Override
    public synchronized void setMediaSource(MediaSource mediaSource) {
      getMediaSource().releasePreloadMediaSource();
      super.setMediaSource(mediaSource);
    }
  }
}
