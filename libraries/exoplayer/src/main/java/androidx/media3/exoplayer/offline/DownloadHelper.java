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

import static androidx.media3.common.util.Util.castNonNull;
import static androidx.media3.common.util.Util.getFormatSupportString;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.min;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.SparseIntArray;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.StreamKey;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.DefaultRendererCapabilitiesList;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RendererCapabilitiesList;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.MediaSource.MediaSourceCaller;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.source.chunk.MediaChunk;
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator;
import androidx.media3.exoplayer.trackselection.BaseTrackSelection;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector.SelectionOverride;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo;
import androidx.media3.exoplayer.trackselection.TrackSelectionUtil;
import androidx.media3.exoplayer.trackselection.TrackSelectorResult;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.exoplayer.util.ReleasableExecutor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.SeekMap;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/**
 * A helper for initializing and removing downloads.
 *
 * <p>The helper extracts track information from the media, selects tracks for downloading, and
 * creates {@link DownloadRequest download requests} based on the selected tracks.
 *
 * <p>A typical usage of DownloadHelper follows these steps:
 *
 * <ol>
 *   <li>Build the helper using one of the {@code forMediaItem} methods.
 *   <li>Prepare the helper using {@link #prepare(Callback)} and wait for the callback.
 *   <li>Optional: Inspect the selected tracks using {@link #getMappedTrackInfo(int)} and {@link
 *       #getTrackSelections(int, int)}, and make adjustments using {@link
 *       #clearTrackSelections(int)}, {@link #replaceTrackSelections(int, TrackSelectionParameters)}
 *       and {@link #addTrackSelection(int, TrackSelectionParameters)}.
 *   <li>Create a download request for the selected track using {@link #getDownloadRequest(byte[])}.
 *   <li>Release the helper using {@link #release()}.
 * </ol>
 */
@UnstableApi
public final class DownloadHelper {

  /** A factory of {@link DownloadHelper}. */
  public static final class Factory {
    @Nullable private DataSource.Factory dataSourceFactory;
    @Nullable private RenderersFactory renderersFactory;
    private TrackSelectionParameters trackSelectionParameters;
    @Nullable private DrmSessionManager drmSessionManager;
    @Nullable private Supplier<ReleasableExecutor> loadExecutorSupplier;
    private boolean debugLoggingEnabled;

    /** Creates a {@link Factory}. */
    public Factory() {
      this.trackSelectionParameters = DEFAULT_TRACK_SELECTOR_PARAMETERS;
      loadExecutorSupplier = null;
    }

    /**
     * Sets a {@link DataSource.Factory} used to load the manifest for adaptive streams or the
     * {@link SeekMap} for progressive streams. The default is {@code null}.
     *
     * <p>A {@link DataSource.Factory} is required for adaptive streams or when requesting partial
     * downloads for progressive streams. In the latter case, this has to be a {@link
     * CacheDataSource.Factory} for the {@link Cache} into which downloads will be written.
     *
     * @return This factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setDataSourceFactory(@Nullable DataSource.Factory dataSourceFactory) {
      this.dataSourceFactory = dataSourceFactory;
      return this;
    }

    /**
     * Sets a {@link RenderersFactory} creating the renderers for which tracks are selected. The
     * default is {@code null}.
     *
     * <p>This is only used for adaptive streams.
     *
     * @return This factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setRenderersFactory(@Nullable RenderersFactory renderersFactory) {
      this.renderersFactory = renderersFactory;
      return this;
    }

    /**
     * Sets a {@link TrackSelectionParameters} for selecting tracks for downloading. The default is
     * {@link #DEFAULT_TRACK_SELECTOR_PARAMETERS}.
     *
     * <p>This is only used for adaptive streams.
     *
     * @return This factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setTrackSelectionParameters(TrackSelectionParameters trackSelectionParameters) {
      this.trackSelectionParameters = trackSelectionParameters;
      return this;
    }

    /**
     * Sets a {@link DrmSessionManager}. Used to help determine which tracks can be selected. The
     * default is {@code null}.
     *
     * @return This factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setDrmSessionManager(@Nullable DrmSessionManager drmSessionManager) {
      this.drmSessionManager = drmSessionManager;
      return this;
    }

    /**
     * Sets a supplier for an {@link ReleasableExecutor} that is used for loading the media.
     *
     * @param loadExecutor A {@link Supplier} that provides an externally managed {@link
     *     ReleasableExecutor} for loading.
     * @return This factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setLoadExecutor(Supplier<ReleasableExecutor> loadExecutor) {
      this.loadExecutorSupplier = loadExecutor;
      return this;
    }

    /**
     * Sets whether to log debug information. The default is {@code false}.
     *
     * @return This factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setDebugLoggingEnabled(boolean debugLoggingEnabled) {
      this.debugLoggingEnabled = debugLoggingEnabled;
      return this;
    }

    /**
     * Creates a new {@link DownloadHelper}.
     *
     * @param mediaItem The {@link MediaItem} to download.
     * @throws IllegalStateException If the corresponding module is missing for DASH, HLS or
     *     SmoothStreaming media items.
     * @throws IllegalArgumentException If the {@code dataSourceFactory} is null for adaptive
     *     streams.
     */
    public DownloadHelper create(MediaItem mediaItem) {
      boolean isProgressive = isProgressive(checkNotNull(mediaItem.localConfiguration));
      checkArgument(isProgressive || dataSourceFactory != null);
      return new DownloadHelper(
          mediaItem,
          isProgressive && dataSourceFactory == null
              ? null
              : createMediaSourceInternal(
                  mediaItem,
                  castNonNull(dataSourceFactory),
                  drmSessionManager,
                  loadExecutorSupplier),
          trackSelectionParameters,
          renderersFactory != null
              ? new DefaultRendererCapabilitiesList.Factory(renderersFactory)
                  .createRendererCapabilitiesList()
              : new UnreleaseableRendererCapabilitiesList(new RendererCapabilities[0]),
          debugLoggingEnabled);
    }

    /**
     * Creates a new {@link DownloadHelper}.
     *
     * @param mediaSource A {@link MediaSource} to be prepared.
     * @throws IllegalStateException If the corresponding module is missing for DASH, HLS or
     *     SmoothStreaming media items.
     * @throws IllegalArgumentException If the {@code dataSourceFactory} is null for adaptive
     *     streams.
     */
    public DownloadHelper create(MediaSource mediaSource) {
      return new DownloadHelper(
          mediaSource.getMediaItem(),
          mediaSource,
          trackSelectionParameters,
          renderersFactory != null
              ? new DefaultRendererCapabilitiesList.Factory(renderersFactory)
                  .createRendererCapabilitiesList()
              : new UnreleaseableRendererCapabilitiesList(new RendererCapabilities[0]),
          debugLoggingEnabled);
    }
  }

  /** Default track selection parameters for downloading. */
  public static final DefaultTrackSelector.Parameters DEFAULT_TRACK_SELECTOR_PARAMETERS =
      DefaultTrackSelector.Parameters.DEFAULT
          .buildUpon()
          .setForceHighestSupportedBitrate(true)
          .setConstrainAudioChannelCountToDeviceCapabilities(false)
          .build();

  /**
   * @deprecated Use {@link #DEFAULT_TRACK_SELECTOR_PARAMETERS} instead.
   */
  @Deprecated
  public static final DefaultTrackSelector.Parameters
      DEFAULT_TRACK_SELECTOR_PARAMETERS_WITHOUT_CONTEXT = DEFAULT_TRACK_SELECTOR_PARAMETERS;

  /**
   * @deprecated Use {@link #DEFAULT_TRACK_SELECTOR_PARAMETERS} instead.
   */
  @Deprecated
  public static DefaultTrackSelector.Parameters getDefaultTrackSelectorParameters(Context context) {
    return DEFAULT_TRACK_SELECTOR_PARAMETERS;
  }

  @Documented
  @Retention(SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    MODE_NOT_PREPARE,
    MODE_PREPARE_PROGRESSIVE_SOURCE,
    MODE_PREPARE_NON_PROGRESSIVE_SOURCE_AND_SELECT_TRACKS
  })
  private @interface Mode {}

  private static final int MODE_NOT_PREPARE = 0;
  private static final int MODE_PREPARE_PROGRESSIVE_SOURCE = 1;
  private static final int MODE_PREPARE_NON_PROGRESSIVE_SOURCE_AND_SELECT_TRACKS = 2;

  /** A callback to be notified when the {@link DownloadHelper} is prepared. */
  public interface Callback {

    /**
     * Called when preparation completes.
     *
     * @param helper The reporting {@link DownloadHelper}.
     * @param tracksInfoAvailable Whether tracks information is available.
     */
    void onPrepared(DownloadHelper helper, boolean tracksInfoAvailable);

    /**
     * Called when preparation fails.
     *
     * @param helper The reporting {@link DownloadHelper}.
     * @param e The error.
     */
    void onPrepareError(DownloadHelper helper, IOException e);
  }

  /** Thrown at an attempt to download live content. */
  public static class LiveContentUnsupportedException extends IOException {}

  /**
   * @deprecated Use {@link Factory#create(MediaItem)} instead.
   */
  @Deprecated
  public static DownloadHelper forMediaItem(Context context, MediaItem mediaItem) {
    checkArgument(isProgressive(checkNotNull(mediaItem.localConfiguration)));
    return new DownloadHelper.Factory().create(mediaItem);
  }

  /**
   * @deprecated Use {@link Factory#create(MediaItem)} instead.
   */
  @Deprecated
  public static DownloadHelper forMediaItem(
      Context context, MediaItem mediaItem, DataSource.Factory dataSourceFactory) {
    return new DownloadHelper.Factory().setDataSourceFactory(dataSourceFactory).create(mediaItem);
  }

  /**
   * @deprecated Use {@link Factory#create(MediaItem)} instead.
   */
  @Deprecated
  public static DownloadHelper forMediaItem(
      Context context,
      MediaItem mediaItem,
      DataSource.Factory dataSourceFactory,
      boolean debugLoggingEnabled) {
    return new DownloadHelper.Factory()
        .setDataSourceFactory(dataSourceFactory)
        .setDebugLoggingEnabled(debugLoggingEnabled)
        .create(mediaItem);
  }

  /**
   * @deprecated Use {@link Factory#create(MediaItem)} instead.
   */
  @Deprecated
  public static DownloadHelper forMediaItem(
      Context context,
      MediaItem mediaItem,
      @Nullable RenderersFactory renderersFactory,
      @Nullable DataSource.Factory dataSourceFactory) {
    return new DownloadHelper.Factory()
        .setDataSourceFactory(dataSourceFactory)
        .setRenderersFactory(renderersFactory)
        .create(mediaItem);
  }

  /**
   * @deprecated Use {@link Factory#create(MediaItem)} instead.
   */
  @Deprecated
  public static DownloadHelper forMediaItem(
      Context context,
      MediaItem mediaItem,
      @Nullable RenderersFactory renderersFactory,
      @Nullable DataSource.Factory dataSourceFactory,
      boolean debugLoggingEnabled) {
    return new DownloadHelper.Factory()
        .setDataSourceFactory(dataSourceFactory)
        .setRenderersFactory(renderersFactory)
        .setDebugLoggingEnabled(debugLoggingEnabled)
        .create(mediaItem);
  }

  /**
   * @deprecated Use {@link Factory#create(MediaItem)} instead.
   */
  @Deprecated
  public static DownloadHelper forMediaItem(
      MediaItem mediaItem,
      TrackSelectionParameters trackSelectionParameters,
      @Nullable RenderersFactory renderersFactory,
      @Nullable DataSource.Factory dataSourceFactory) {
    return new DownloadHelper.Factory()
        .setDataSourceFactory(dataSourceFactory)
        .setTrackSelectionParameters(trackSelectionParameters)
        .setRenderersFactory(renderersFactory)
        .create(mediaItem);
  }

  /**
   * @deprecated Use {@link Factory#create(MediaItem)} instead.
   */
  @Deprecated
  public static DownloadHelper forMediaItem(
      MediaItem mediaItem,
      TrackSelectionParameters trackSelectionParameters,
      @Nullable RenderersFactory renderersFactory,
      @Nullable DataSource.Factory dataSourceFactory,
      boolean debugLoggingEnabled) {
    return new DownloadHelper.Factory()
        .setDataSourceFactory(dataSourceFactory)
        .setTrackSelectionParameters(trackSelectionParameters)
        .setRenderersFactory(renderersFactory)
        .setDebugLoggingEnabled(debugLoggingEnabled)
        .create(mediaItem);
  }

  /**
   * @deprecated Use {@link Factory#create(MediaItem)} instead.
   */
  @Deprecated
  public static DownloadHelper forMediaItem(
      MediaItem mediaItem,
      TrackSelectionParameters trackSelectionParameters,
      @Nullable RenderersFactory renderersFactory,
      @Nullable DataSource.Factory dataSourceFactory,
      @Nullable DrmSessionManager drmSessionManager) {
    return new DownloadHelper.Factory()
        .setDataSourceFactory(dataSourceFactory)
        .setTrackSelectionParameters(trackSelectionParameters)
        .setRenderersFactory(renderersFactory)
        .setDrmSessionManager(drmSessionManager)
        .create(mediaItem);
  }

  /**
   * @deprecated Use {@link Factory#create(MediaItem)} instead.
   */
  @Deprecated
  public static DownloadHelper forMediaItem(
      MediaItem mediaItem,
      TrackSelectionParameters trackSelectionParameters,
      @Nullable RenderersFactory renderersFactory,
      @Nullable DataSource.Factory dataSourceFactory,
      @Nullable DrmSessionManager drmSessionManager,
      boolean debugLoggingEnabled) {
    return new DownloadHelper.Factory()
        .setDataSourceFactory(dataSourceFactory)
        .setTrackSelectionParameters(trackSelectionParameters)
        .setRenderersFactory(renderersFactory)
        .setDrmSessionManager(drmSessionManager)
        .setDebugLoggingEnabled(debugLoggingEnabled)
        .create(mediaItem);
  }

  /**
   * Equivalent to {@link #createMediaSource(DownloadRequest, DataSource.Factory, DrmSessionManager)
   * createMediaSource(downloadRequest, dataSourceFactory, null)}.
   */
  public static MediaSource createMediaSource(
      DownloadRequest downloadRequest, DataSource.Factory dataSourceFactory) {
    return createMediaSource(downloadRequest, dataSourceFactory, /* drmSessionManager= */ null);
  }

  /**
   * Utility method to create a {@link MediaSource} that only exposes the tracks defined in {@code
   * downloadRequest}.
   *
   * @param downloadRequest A {@link DownloadRequest}.
   * @param dataSourceFactory A factory for {@link DataSource}s to read the media.
   * @param drmSessionManager An optional {@link DrmSessionManager} to be passed to the {@link
   *     MediaSource}.
   * @return A {@link MediaSource} that only exposes the tracks defined in {@code downloadRequest}.
   */
  public static MediaSource createMediaSource(
      DownloadRequest downloadRequest,
      DataSource.Factory dataSourceFactory,
      @Nullable DrmSessionManager drmSessionManager) {
    return createMediaSourceInternal(
        downloadRequest.toMediaItem(),
        dataSourceFactory,
        drmSessionManager,
        /* loadExecutorSupplier= */ null);
  }

  private static final String TAG = "DownloadHelper";

  private final MediaItem.LocalConfiguration localConfiguration;
  @Nullable private final MediaSource mediaSource;
  private final @Mode int mode;
  private final DefaultTrackSelector trackSelector;
  private final RendererCapabilitiesList rendererCapabilities;
  private final boolean debugLoggingEnabled;
  private final SparseIntArray scratchSet;
  private final Handler callbackHandler;
  private final Timeline.Window window;

  private boolean isPreparedWithMedia;
  private boolean areTracksSelected;
  private @MonotonicNonNull Callback callback;
  private @MonotonicNonNull MediaPreparer mediaPreparer;
  private TrackGroupArray @MonotonicNonNull [] trackGroupArrays;
  private MappedTrackInfo @MonotonicNonNull [] mappedTrackInfos;
  private List<ExoTrackSelection> @MonotonicNonNull [][] trackSelectionsByPeriodAndRenderer;
  private List<ExoTrackSelection> @MonotonicNonNull [][]
      immutableTrackSelectionsByPeriodAndRenderer;

  /**
   * Creates download helper.
   *
   * @param mediaItem The media item.
   * @param mediaSource A {@link MediaSource} for which tracks are selected, or null if no track
   *     selection needs to be made.
   * @param trackSelectionParameters {@link TrackSelectionParameters} for selecting tracks for
   *     downloading.
   * @param rendererCapabilities The {@link RendererCapabilitiesList} of the renderers for which
   *     tracks are selected.
   */
  public DownloadHelper(
      MediaItem mediaItem,
      @Nullable MediaSource mediaSource,
      TrackSelectionParameters trackSelectionParameters,
      RendererCapabilitiesList rendererCapabilities) {
    this(
        mediaItem,
        mediaSource,
        trackSelectionParameters,
        rendererCapabilities,
        /* debugLoggingEnabled= */ false);
  }

  /**
   * Creates download helper.
   *
   * @param mediaItem The media item.
   * @param mediaSource A {@link MediaSource} to be prepared, or null if no preparation needs to be
   *     done.
   * @param trackSelectionParameters {@link TrackSelectionParameters} for selecting tracks for
   *     downloading.
   * @param rendererCapabilities The {@link RendererCapabilitiesList} of the renderers for which
   *     tracks are selected.
   * @param debugLoggingEnabled Whether to log debug information.
   */
  public DownloadHelper(
      MediaItem mediaItem,
      @Nullable MediaSource mediaSource,
      TrackSelectionParameters trackSelectionParameters,
      RendererCapabilitiesList rendererCapabilities,
      boolean debugLoggingEnabled) {
    this.localConfiguration = checkNotNull(mediaItem.localConfiguration);
    this.mediaSource = mediaSource;
    this.mode =
        (mediaSource == null)
            ? MODE_NOT_PREPARE
            : (mediaSource instanceof ProgressiveMediaSource)
                ? MODE_PREPARE_PROGRESSIVE_SOURCE
                : MODE_PREPARE_NON_PROGRESSIVE_SOURCE_AND_SELECT_TRACKS;
    this.trackSelector =
        new DefaultTrackSelector(trackSelectionParameters, new DownloadTrackSelection.Factory());
    this.rendererCapabilities = rendererCapabilities;
    this.debugLoggingEnabled = debugLoggingEnabled;
    this.scratchSet = new SparseIntArray();
    trackSelector.init(/* listener= */ () -> {}, new FakeBandwidthMeter());
    callbackHandler = Util.createHandlerForCurrentOrMainLooper();
    window = new Timeline.Window();
  }

  /**
   * Initializes the helper for starting a download.
   *
   * @param callback A callback to be notified when preparation completes or fails.
   * @throws IllegalStateException If the download helper has already been prepared.
   */
  public void prepare(Callback callback) {
    checkState(this.callback == null);
    this.callback = callback;
    if (mode != MODE_NOT_PREPARE) {
      mediaPreparer = new MediaPreparer(checkNotNull(mediaSource), /* downloadHelper= */ this);
    } else {
      callbackHandler.post(() -> callback.onPrepared(this, /* tracksInfoAvailable= */ false));
    }
  }

  /** Releases the helper and all resources it is holding. */
  public void release() {
    if (mediaPreparer != null) {
      mediaPreparer.release();
    }
    trackSelector.release();
    rendererCapabilities.release();
  }

  /**
   * Returns the manifest, or null if no manifest is loaded. Must not be called until {@link
   * Callback#onPrepared(DownloadHelper, boolean)} is triggered.
   */
  @Nullable
  public Object getManifest() {
    if (mode == MODE_NOT_PREPARE) {
      return null;
    }
    assertPreparedWithMedia();
    return mediaPreparer.timeline.getWindowCount() > 0
        ? mediaPreparer.timeline.getWindow(/* windowIndex= */ 0, window).manifest
        : null;
  }

  /**
   * Returns the number of periods for which media is available. Must not be called until {@link
   * Callback#onPrepared(DownloadHelper, boolean)} is triggered.
   */
  public int getPeriodCount() {
    if (mode == MODE_NOT_PREPARE) {
      return 0;
    }
    assertPreparedWithMedia();
    return mediaPreparer.mediaPeriods.length;
  }

  /**
   * Returns {@link Tracks} for the given period. Must not be called until {@link
   * Callback#onPrepared(DownloadHelper, boolean)} is triggered and the passed {@code
   * tracksInfoAvailable} is {@code true}.
   *
   * @param periodIndex The period index.
   * @return The {@link Tracks} for the period. May be {@link Tracks#EMPTY} for single stream
   *     content.
   */
  public Tracks getTracks(int periodIndex) {
    assertPreparedWithNonProgressiveSourceAndTracksSelected();
    return TrackSelectionUtil.buildTracks(
        mappedTrackInfos[periodIndex], immutableTrackSelectionsByPeriodAndRenderer[periodIndex]);
  }

  /**
   * Returns the track groups for the given period. Must not be called until {@link
   * Callback#onPrepared(DownloadHelper, boolean)} is triggered and the passed {@code
   * tracksInfoAvailable} is {@code true}.
   *
   * <p>Use {@link #getMappedTrackInfo(int)} to get the track groups mapped to renderers.
   *
   * @param periodIndex The period index.
   * @return The track groups for the period. May be {@link TrackGroupArray#EMPTY} for single stream
   *     content.
   */
  public TrackGroupArray getTrackGroups(int periodIndex) {
    assertPreparedWithNonProgressiveSourceAndTracksSelected();
    return trackGroupArrays[periodIndex];
  }

  /**
   * Returns the mapped track info for the given period. Must not be called until {@link
   * Callback#onPrepared(DownloadHelper, boolean)} is triggered and the passed {@code
   * tracksInfoAvailable} is {@code true}.
   *
   * @param periodIndex The period index.
   * @return The {@link MappedTrackInfo} for the period.
   */
  public MappedTrackInfo getMappedTrackInfo(int periodIndex) {
    assertPreparedWithNonProgressiveSourceAndTracksSelected();
    return mappedTrackInfos[periodIndex];
  }

  /**
   * Returns all {@link ExoTrackSelection track selections} for a period and renderer. Must not be
   * called until {@link Callback#onPrepared(DownloadHelper, boolean)} is triggered and the passed
   * {@code tracksInfoAvailable} is {@code true}.
   *
   * @param periodIndex The period index.
   * @param rendererIndex The renderer index.
   * @return A list of selected {@link ExoTrackSelection track selections}.
   */
  public List<ExoTrackSelection> getTrackSelections(int periodIndex, int rendererIndex) {
    assertPreparedWithNonProgressiveSourceAndTracksSelected();
    return immutableTrackSelectionsByPeriodAndRenderer[periodIndex][rendererIndex];
  }

  /**
   * Clears the selection of tracks for a period. Must not be called until {@link
   * Callback#onPrepared(DownloadHelper, boolean)} is triggered and the passed {@code
   * tracksInfoAvailable} is {@code true}.
   *
   * @param periodIndex The period index for which track selections are cleared.
   */
  public void clearTrackSelections(int periodIndex) {
    assertPreparedWithNonProgressiveSourceAndTracksSelected();
    for (int i = 0; i < rendererCapabilities.size(); i++) {
      trackSelectionsByPeriodAndRenderer[periodIndex][i].clear();
    }
  }

  /**
   * Replaces a selection of tracks to be downloaded. Must not be called until {@link
   * Callback#onPrepared(DownloadHelper, boolean)} is triggered and the passed {@code
   * tracksInfoAvailable} is {@code true}.
   *
   * @param periodIndex The period index for which the track selection is replaced.
   * @param trackSelectionParameters The {@link TrackSelectionParameters} to obtain the new
   *     selection of tracks.
   */
  public void replaceTrackSelections(
      int periodIndex, TrackSelectionParameters trackSelectionParameters) {
    try {
      assertPreparedWithNonProgressiveSourceAndTracksSelected();
      clearTrackSelections(periodIndex);
      addTrackSelectionInternal(periodIndex, trackSelectionParameters);
    } catch (ExoPlaybackException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Adds a selection of tracks to be downloaded. Must not be called until {@link
   * Callback#onPrepared(DownloadHelper, boolean)} is triggered and the passed {@code
   * tracksInfoAvailable} is {@code true}.
   *
   * @param periodIndex The period index this track selection is added for.
   * @param trackSelectionParameters The {@link TrackSelectionParameters} to obtain the new
   *     selection of tracks.
   */
  public void addTrackSelection(
      int periodIndex, TrackSelectionParameters trackSelectionParameters) {
    try {
      assertPreparedWithNonProgressiveSourceAndTracksSelected();
      addTrackSelectionInternal(periodIndex, trackSelectionParameters);
    } catch (ExoPlaybackException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Convenience method to add selections of tracks for all specified audio languages. If an audio
   * track in one of the specified languages is not available, the default fallback audio track is
   * used instead. Must not be called until {@link Callback#onPrepared(DownloadHelper, boolean)} is
   * triggered and the passed {@code tracksInfoAvailable} is {@code true}.
   *
   * @param languages A list of audio languages for which tracks should be added to the download
   *     selection, as IETF BCP 47 conformant tags.
   */
  public void addAudioLanguagesToSelection(String... languages) {
    try {
      assertPreparedWithNonProgressiveSourceAndTracksSelected();

      TrackSelectionParameters.Builder parametersBuilder =
          DEFAULT_TRACK_SELECTOR_PARAMETERS.buildUpon();
      // Prefer highest supported bitrate for downloads.
      parametersBuilder.setForceHighestSupportedBitrate(true);
      // Disable all non-audio track types supported by the renderers.
      for (RendererCapabilities capabilities : rendererCapabilities.getRendererCapabilities()) {
        @C.TrackType int trackType = capabilities.getTrackType();
        parametersBuilder.setTrackTypeDisabled(
            trackType, /* disabled= */ trackType != C.TRACK_TYPE_AUDIO);
      }

      // Add a track selection to each period for each of the languages.
      int periodCount = getPeriodCount();
      for (String language : languages) {
        TrackSelectionParameters parameters =
            parametersBuilder.setPreferredAudioLanguage(language).build();
        for (int periodIndex = 0; periodIndex < periodCount; periodIndex++) {
          addTrackSelectionInternal(periodIndex, parameters);
        }
      }
    } catch (ExoPlaybackException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Convenience method to add selections of tracks for all specified text languages. Must not be
   * called until {@link Callback#onPrepared(DownloadHelper, boolean)} is triggered and the passed
   * {@code tracksInfoAvailable} is {@code true}.
   *
   * @param selectUndeterminedTextLanguage Whether a text track with undetermined language should be
   *     selected for downloading if no track with one of the specified {@code languages} is
   *     available.
   * @param languages A list of text languages for which tracks should be added to the download
   *     selection, as IETF BCP 47 conformant tags.
   */
  public void addTextLanguagesToSelection(
      boolean selectUndeterminedTextLanguage, String... languages) {
    try {
      assertPreparedWithNonProgressiveSourceAndTracksSelected();

      TrackSelectionParameters.Builder parametersBuilder =
          DEFAULT_TRACK_SELECTOR_PARAMETERS.buildUpon();
      parametersBuilder.setSelectUndeterminedTextLanguage(selectUndeterminedTextLanguage);
      // Prefer highest supported bitrate for downloads.
      parametersBuilder.setForceHighestSupportedBitrate(true);
      // Disable all non-text track types supported by the renderers.
      for (RendererCapabilities capabilities : rendererCapabilities.getRendererCapabilities()) {
        @C.TrackType int trackType = capabilities.getTrackType();
        parametersBuilder.setTrackTypeDisabled(
            trackType, /* disabled= */ trackType != C.TRACK_TYPE_TEXT);
      }

      // Add a track selection to each period for each of the languages.
      int periodCount = getPeriodCount();
      for (String language : languages) {
        TrackSelectionParameters parameters =
            parametersBuilder.setPreferredTextLanguage(language).build();
        for (int periodIndex = 0; periodIndex < periodCount; periodIndex++) {
          addTrackSelectionInternal(periodIndex, parameters);
        }
      }
    } catch (ExoPlaybackException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Convenience method to add a selection of tracks to be downloaded for a single renderer. Must
   * not be called until {@link Callback#onPrepared(DownloadHelper, boolean)} is triggered and the
   * passed {@code tracksInfoAvailable} is {@code true}.
   *
   * @param periodIndex The period index the track selection is added for.
   * @param rendererIndex The renderer index the track selection is added for.
   * @param trackSelectorParameters The {@link DefaultTrackSelector.Parameters} to obtain the new
   *     selection of tracks.
   * @param overrides A list of {@link SelectionOverride SelectionOverrides} to apply to the {@code
   *     trackSelectorParameters}. If empty, {@code trackSelectorParameters} are used as they are.
   */
  public void addTrackSelectionForSingleRenderer(
      int periodIndex,
      int rendererIndex,
      DefaultTrackSelector.Parameters trackSelectorParameters,
      List<SelectionOverride> overrides) {
    try {
      assertPreparedWithNonProgressiveSourceAndTracksSelected();
      DefaultTrackSelector.Parameters.Builder builder = trackSelectorParameters.buildUpon();
      for (int i = 0; i < mappedTrackInfos[periodIndex].getRendererCount(); i++) {
        builder.setRendererDisabled(/* rendererIndex= */ i, /* disabled= */ i != rendererIndex);
      }
      if (overrides.isEmpty()) {
        addTrackSelectionInternal(periodIndex, builder.build());
      } else {
        TrackGroupArray trackGroupArray =
            mappedTrackInfos[periodIndex].getTrackGroups(rendererIndex);
        for (int i = 0; i < overrides.size(); i++) {
          builder.setSelectionOverride(rendererIndex, trackGroupArray, overrides.get(i));
          addTrackSelectionInternal(periodIndex, builder.build());
        }
      }
    } catch (ExoPlaybackException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Builds a {@link DownloadRequest} for downloading the selected tracks. Must not be called until
   * after preparation completes. The uri of the {@link DownloadRequest} will be used as content id.
   *
   * @param data Application provided data to store in {@link DownloadRequest#data}.
   */
  public DownloadRequest getDownloadRequest(@Nullable byte[] data) {
    return getDownloadRequest(localConfiguration.uri.toString(), data);
  }

  /**
   * Builds a {@link DownloadRequest} for downloading the selected tracks and time range. Must not
   * be called until preparation completes.
   *
   * <p>This method is only supported for progressive streams.
   *
   * @param data Application provided data to store in {@link DownloadRequest#data}.
   * @param startPositionMs The start position (in milliseconds) of the media that download should
   *     cover from, or {@link C#TIME_UNSET} if the download should cover from the default start
   *     position.
   * @param durationMs The end position (in milliseconds) of the media that download should cover
   *     to, or {@link C#TIME_UNSET} if the download should cover to the end of the media. If the
   *     {@code endPositionMs} is larger than the duration of the media, then the download will
   *     cover to the end of the media.
   */
  public DownloadRequest getDownloadRequest(
      @Nullable byte[] data, long startPositionMs, long durationMs) {
    return getDownloadRequest(localConfiguration.uri.toString(), data, startPositionMs, durationMs);
  }

  /**
   * Builds a {@link DownloadRequest} for downloading the selected tracks. Must not be called until
   * after preparation completes.
   *
   * @param id The unique content id.
   * @param data Application provided data to store in {@link DownloadRequest#data}.
   */
  public DownloadRequest getDownloadRequest(String id, @Nullable byte[] data) {
    return getDownloadRequestBuilder(id, data).build();
  }

  /**
   * Builds a {@link DownloadRequest} for downloading the selected tracks and time range. Must not
   * be called until preparation completes.
   *
   * <p>This method is only supported for progressive streams.
   *
   * @param id The unique content id.
   * @param data Application provided data to store in {@link DownloadRequest#data}.
   * @param startPositionMs The start position (in milliseconds) of the media that download should
   *     cover from, or {@link C#TIME_UNSET} if the download should cover from the default start
   *     position.
   * @param durationMs The duration (in milliseconds) of the media that download should cover, or
   *     {@link C#TIME_UNSET} if the download should cover to the end of the media. If the end
   *     position resolved from {@code startPositionMs} and {@code durationMs} is beyond the
   *     duration of the media, then the download will just cover to the end of the media.
   */
  public DownloadRequest getDownloadRequest(
      String id, @Nullable byte[] data, long startPositionMs, long durationMs) {
    DownloadRequest.Builder builder = getDownloadRequestBuilder(id, data);
    assertPreparedWithMedia();
    populateDownloadRequestBuilderWithDownloadRange(builder, startPositionMs, durationMs);
    return builder.build();
  }

  private DownloadRequest.Builder getDownloadRequestBuilder(String id, @Nullable byte[] data) {
    DownloadRequest.Builder requestBuilder =
        new DownloadRequest.Builder(id, localConfiguration.uri)
            .setMimeType(localConfiguration.mimeType)
            .setKeySetId(
                localConfiguration.drmConfiguration != null
                    ? localConfiguration.drmConfiguration.getKeySetId()
                    : null)
            .setCustomCacheKey(localConfiguration.customCacheKey)
            .setData(data);
    if (mode == MODE_PREPARE_NON_PROGRESSIVE_SOURCE_AND_SELECT_TRACKS) {
      assertPreparedWithNonProgressiveSourceAndTracksSelected();
      List<StreamKey> streamKeys = new ArrayList<>();
      List<ExoTrackSelection> allSelections = new ArrayList<>();
      int periodCount = trackSelectionsByPeriodAndRenderer.length;
      for (int periodIndex = 0; periodIndex < periodCount; periodIndex++) {
        allSelections.clear();
        int rendererCount = trackSelectionsByPeriodAndRenderer[periodIndex].length;
        for (int rendererIndex = 0; rendererIndex < rendererCount; rendererIndex++) {
          allSelections.addAll(trackSelectionsByPeriodAndRenderer[periodIndex][rendererIndex]);
        }
        streamKeys.addAll(mediaPreparer.mediaPeriods[periodIndex].getStreamKeys(allSelections));
      }
      requestBuilder.setStreamKeys(streamKeys);
    }
    return requestBuilder;
  }

  private void populateDownloadRequestBuilderWithDownloadRange(
      DownloadRequest.Builder requestBuilder, long startPositionMs, long durationMs) {
    switch (mode) {
      case MODE_PREPARE_PROGRESSIVE_SOURCE:
        populateDownloadRequestBuilderWithByteRange(requestBuilder, startPositionMs, durationMs);
        break;
      case MODE_PREPARE_NON_PROGRESSIVE_SOURCE_AND_SELECT_TRACKS:
        populateDownloadRequestBuilderWithTimeRange(requestBuilder, startPositionMs, durationMs);
        break;
      default:
        break;
    }
  }

  private void populateDownloadRequestBuilderWithByteRange(
      DownloadRequest.Builder requestBuilder, long startPositionMs, long durationMs) {
    assertPreparedWithProgressiveSource();
    Timeline timeline = mediaPreparer.timeline;
    Timeline.Window window = new Timeline.Window();
    Timeline.Period period = new Timeline.Period();
    long periodStartPositionUs =
        timeline.getPeriodPositionUs(
                window,
                period,
                /* windowIndex= */ 0,
                /* windowPositionUs= */ Util.msToUs(startPositionMs))
            .second;

    long periodEndPositionUs = C.TIME_UNSET;
    if (durationMs != C.TIME_UNSET) {
      periodEndPositionUs = periodStartPositionUs + Util.msToUs(durationMs);
      if (period.durationUs != C.TIME_UNSET) {
        periodEndPositionUs = min(periodEndPositionUs, period.durationUs - 1);
      }
    }

    // SeekMap should be available for prepared progressive media.
    SeekMap seekMap = mediaPreparer.seekMap;
    if (seekMap.isSeekable()) {
      long byteRangeStartPositionOffset =
          seekMap.getSeekPoints(periodStartPositionUs).first.position;
      long byteRangeLength = C.LENGTH_UNSET;
      if (periodEndPositionUs != C.TIME_UNSET) {
        long byteRangeEndPositionOffset =
            seekMap.getSeekPoints(periodEndPositionUs).second.position;
        // When the start and end positions are after the last seek point, they will both have only
        // that one mapped seek point. Then we should download from that seek point to the end of
        // the media, otherwise nothing will be downloaded as the resolved length is 0.
        boolean areStartAndEndPositionsAfterTheLastSeekPoint =
            periodStartPositionUs != periodEndPositionUs
                && byteRangeStartPositionOffset == byteRangeEndPositionOffset;
        byteRangeLength =
            !areStartAndEndPositionsAfterTheLastSeekPoint
                ? byteRangeEndPositionOffset - byteRangeStartPositionOffset
                : C.LENGTH_UNSET;
      }
      requestBuilder.setByteRange(byteRangeStartPositionOffset, byteRangeLength);
    } else {
      Log.w(TAG, "Cannot set download byte range for progressive stream that is unseekable");
    }
  }

  private void populateDownloadRequestBuilderWithTimeRange(
      DownloadRequest.Builder requestBuilder, long startPositionMs, long durationMs) {
    assertPreparedWithNonProgressiveSourceAndTracksSelected();
    Timeline timeline = mediaPreparer.timeline;
    Timeline.Window window = timeline.getWindow(0, new Timeline.Window());

    long startPositionUs =
        startPositionMs == C.TIME_UNSET
            ? window.getDefaultPositionUs()
            : Util.msToUs(startPositionMs);
    long windowDurationUs = window.getDurationUs();
    long durationUs = durationMs == C.TIME_UNSET ? windowDurationUs : Util.msToUs(durationMs);
    if (windowDurationUs != C.TIME_UNSET) {
      startPositionUs = min(startPositionUs, windowDurationUs);
      durationUs = min(durationUs, windowDurationUs - startPositionUs);
    }
    requestBuilder.setTimeRange(startPositionUs, durationUs);
  }

  @RequiresNonNull({
    "trackGroupArrays",
    "trackSelectionsByPeriodAndRenderer",
    "mediaPreparer",
    "mediaPreparer.timeline"
  })
  private void addTrackSelectionInternal(
      int periodIndex, TrackSelectionParameters trackSelectionParameters)
      throws ExoPlaybackException {
    trackSelector.setParameters(trackSelectionParameters);
    runTrackSelection(periodIndex);
    // TrackSelectionParameters can contain multiple overrides for each track type. The track
    // selector will only use one of them (because it's designed for playback), but for downloads we
    // want to use all of them. Run selection again with each override being the only one of its
    // type, to ensure that all of the desired tracks are included.
    for (TrackSelectionOverride override : trackSelectionParameters.overrides.values()) {
      trackSelector.setParameters(
          trackSelectionParameters.buildUpon().setOverrideForType(override).build());
      runTrackSelection(periodIndex);
    }
  }

  @SuppressWarnings("unchecked") // Initialization of array of Lists.
  private void onMediaPrepared() throws ExoPlaybackException {
    checkNotNull(mediaPreparer);
    checkNotNull(mediaPreparer.mediaPeriods);
    checkNotNull(mediaPreparer.timeline);
    boolean tracksInfoAvailable;
    if (mode == MODE_PREPARE_NON_PROGRESSIVE_SOURCE_AND_SELECT_TRACKS) {
      int periodCount = mediaPreparer.mediaPeriods.length;
      int rendererCount = rendererCapabilities.size();
      trackSelectionsByPeriodAndRenderer =
          (List<ExoTrackSelection>[][]) new List<?>[periodCount][rendererCount];
      immutableTrackSelectionsByPeriodAndRenderer =
          (List<ExoTrackSelection>[][]) new List<?>[periodCount][rendererCount];
      for (int i = 0; i < periodCount; i++) {
        for (int j = 0; j < rendererCount; j++) {
          trackSelectionsByPeriodAndRenderer[i][j] = new ArrayList<>();
          immutableTrackSelectionsByPeriodAndRenderer[i][j] =
              Collections.unmodifiableList(trackSelectionsByPeriodAndRenderer[i][j]);
        }
      }
      trackGroupArrays = new TrackGroupArray[periodCount];
      mappedTrackInfos = new MappedTrackInfo[periodCount];
      for (int i = 0; i < periodCount; i++) {
        trackGroupArrays[i] = mediaPreparer.mediaPeriods[i].getTrackGroups();
        TrackSelectorResult trackSelectorResult = runTrackSelection(/* periodIndex= */ i);
        trackSelector.onSelectionActivated(trackSelectorResult.info);
        mappedTrackInfos[i] = checkNotNull(trackSelector.getCurrentMappedTrackInfo());
      }
      tracksInfoAvailable = true;
      setPreparedWithNonProgressiveSourceAndTracksSelected();
    } else {
      checkState(mode == MODE_PREPARE_PROGRESSIVE_SOURCE);
      checkNotNull(mediaPreparer.seekMap);
      tracksInfoAvailable = false;
      setPreparedWithProgressiveSource();
    }
    checkNotNull(callbackHandler)
        .post(() -> checkNotNull(callback).onPrepared(this, tracksInfoAvailable));
  }

  private void onMediaPreparationFailed(IOException error) {
    checkNotNull(callbackHandler).post(() -> checkNotNull(callback).onPrepareError(this, error));
  }

  @RequiresNonNull({
    "trackGroupArrays",
    "mappedTrackInfos",
    "trackSelectionsByPeriodAndRenderer",
    "immutableTrackSelectionsByPeriodAndRenderer",
    "mediaPreparer",
    "mediaPreparer.timeline",
    "mediaPreparer.mediaPeriods"
  })
  private void setPreparedWithNonProgressiveSourceAndTracksSelected() {
    isPreparedWithMedia = true;
    areTracksSelected = true;
  }

  @RequiresNonNull({
    "mediaPreparer",
    "mediaPreparer.timeline",
    "mediaPreparer.seekMap",
    "mediaPreparer.mediaPeriods"
  })
  private void setPreparedWithProgressiveSource() {
    isPreparedWithMedia = true;
  }

  @EnsuresNonNull({"mediaPreparer", "mediaPreparer.timeline", "mediaPreparer.mediaPeriods"})
  @SuppressWarnings("nullness:contracts.postcondition")
  private void assertPreparedWithMedia() {
    checkState(mode != MODE_NOT_PREPARE);
    checkState(isPreparedWithMedia);
  }

  @EnsuresNonNull({
    "trackGroupArrays",
    "mappedTrackInfos",
    "trackSelectionsByPeriodAndRenderer",
    "immutableTrackSelectionsByPeriodAndRenderer",
    "mediaPreparer",
    "mediaPreparer.timeline",
    "mediaPreparer.mediaPeriods"
  })
  @SuppressWarnings("nullness:contracts.postcondition")
  private void assertPreparedWithNonProgressiveSourceAndTracksSelected() {
    checkState(mode == MODE_PREPARE_NON_PROGRESSIVE_SOURCE_AND_SELECT_TRACKS);
    checkState(isPreparedWithMedia);
    checkState(areTracksSelected);
  }

  @EnsuresNonNull({
    "mediaPreparer",
    "mediaPreparer.timeline",
    "mediaPreparer.seekMap",
    "mediaPreparer.mediaPeriods"
  })
  @SuppressWarnings("nullness:contracts.postcondition")
  private void assertPreparedWithProgressiveSource() {
    checkState(mode == MODE_PREPARE_PROGRESSIVE_SOURCE);
    checkState(isPreparedWithMedia);
  }

  /**
   * Runs the track selection for a given period index with the current parameters. The selected
   * tracks will be added to {@link #trackSelectionsByPeriodAndRenderer}.
   */
  @RequiresNonNull({
    "trackGroupArrays",
    "trackSelectionsByPeriodAndRenderer",
    "mediaPreparer",
    "mediaPreparer.timeline"
  })
  private TrackSelectorResult runTrackSelection(int periodIndex) throws ExoPlaybackException {
    TrackSelectorResult trackSelectorResult =
        trackSelector.selectTracks(
            rendererCapabilities.getRendererCapabilities(),
            trackGroupArrays[periodIndex],
            new MediaPeriodId(mediaPreparer.timeline.getUidOfPeriod(periodIndex)),
            mediaPreparer.timeline);
    for (int i = 0; i < trackSelectorResult.length; i++) {
      @Nullable ExoTrackSelection newSelection = trackSelectorResult.selections[i];
      if (newSelection == null) {
        continue;
      }
      List<ExoTrackSelection> existingSelectionList =
          trackSelectionsByPeriodAndRenderer[periodIndex][i];
      boolean mergedWithExistingSelection = false;
      for (int j = 0; j < existingSelectionList.size(); j++) {
        ExoTrackSelection existingSelection = existingSelectionList.get(j);
        if (existingSelection.getTrackGroup().equals(newSelection.getTrackGroup())) {
          // Merge with existing selection.
          scratchSet.clear();
          for (int k = 0; k < existingSelection.length(); k++) {
            scratchSet.put(existingSelection.getIndexInTrackGroup(k), 0);
          }
          for (int k = 0; k < newSelection.length(); k++) {
            scratchSet.put(newSelection.getIndexInTrackGroup(k), 0);
          }
          int[] mergedTracks = new int[scratchSet.size()];
          for (int k = 0; k < scratchSet.size(); k++) {
            mergedTracks[k] = scratchSet.keyAt(k);
          }
          existingSelectionList.set(
              j, new DownloadTrackSelection(existingSelection.getTrackGroup(), mergedTracks));
          mergedWithExistingSelection = true;
          break;
        }
      }
      if (!mergedWithExistingSelection) {
        existingSelectionList.add(newSelection);
      }
    }
    if (debugLoggingEnabled) {
      logTrackSelectorResult(periodIndex, trackSelectorResult);
    }
    return trackSelectorResult;
  }

  private static MediaSource createMediaSourceInternal(
      MediaItem mediaItem,
      DataSource.Factory dataSourceFactory,
      @Nullable DrmSessionManager drmSessionManager,
      @Nullable Supplier<ReleasableExecutor> loadExecutorSupplier) {
    MediaSource.Factory mediaSourceFactory =
        isProgressive(checkNotNull(mediaItem.localConfiguration))
            ? new ProgressiveMediaSource.Factory(dataSourceFactory)
            : new DefaultMediaSourceFactory(dataSourceFactory, ExtractorsFactory.EMPTY);
    if (loadExecutorSupplier != null) {
      mediaSourceFactory.setDownloadExecutor(loadExecutorSupplier);
    }
    if (drmSessionManager != null) {
      mediaSourceFactory.setDrmSessionManagerProvider(unusedMediaItem -> drmSessionManager);
    }
    return mediaSourceFactory.createMediaSource(mediaItem);
  }

  private static boolean isProgressive(MediaItem.LocalConfiguration localConfiguration) {
    return Util.inferContentTypeForUriAndMimeType(
            localConfiguration.uri, localConfiguration.mimeType)
        == C.CONTENT_TYPE_OTHER;
  }

  private static void logTrackSelectorResult(
      int periodIndex, TrackSelectorResult trackSelectorResult) {
    Log.d(TAG, "Track selections changed, period index: " + periodIndex + ", tracks [");
    ImmutableList<Tracks.Group> trackGroups = trackSelectorResult.tracks.getGroups();
    for (int groupIndex = 0; groupIndex < trackGroups.size(); groupIndex++) {
      Tracks.Group trackGroup = trackGroups.get(groupIndex);
      Log.d(TAG, "  group [");
      for (int trackIndex = 0; trackIndex < trackGroup.length; trackIndex++) {
        String status = trackGroup.isTrackSelected(trackIndex) ? "[X]" : "[ ]";
        String formatSupport = getFormatSupportString(trackGroup.getTrackSupport(trackIndex));
        Log.d(
            TAG,
            "    "
                + status
                + " Track:"
                + trackIndex
                + ", "
                + Format.toLogString(trackGroup.getTrackFormat(trackIndex))
                + ", supported="
                + formatSupport);
      }
      Log.d(TAG, "  ]");
    }
    Log.d(TAG, "]");
  }

  private static final class MediaPreparer
      implements MediaSourceCaller,
          ProgressiveMediaSource.Listener,
          MediaPeriod.Callback,
          Handler.Callback {

    private static final int MESSAGE_PREPARE_SOURCE = 1;
    private static final int MESSAGE_CHECK_FOR_FAILURE = 2;
    private static final int MESSAGE_CONTINUE_LOADING = 3;
    private static final int MESSAGE_RELEASE = 4;

    private static final int DOWNLOAD_HELPER_CALLBACK_MESSAGE_PREPARED = 1;
    private static final int DOWNLOAD_HELPER_CALLBACK_MESSAGE_FAILED = 2;

    private final MediaSource mediaSource;
    private final DownloadHelper downloadHelper;
    private final Allocator allocator;
    private final ArrayList<MediaPeriod> pendingMediaPeriods;
    private final Handler downloadHelperHandler;
    private final HandlerThread mediaSourceThread;
    private final Handler mediaSourceHandler;

    public @MonotonicNonNull Timeline timeline;
    public @MonotonicNonNull SeekMap seekMap;
    public MediaPeriod @MonotonicNonNull [] mediaPeriods;

    private boolean released;

    public MediaPreparer(MediaSource mediaSource, DownloadHelper downloadHelper) {
      this.mediaSource = mediaSource;
      this.downloadHelper = downloadHelper;
      allocator = new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
      pendingMediaPeriods = new ArrayList<>();
      @SuppressWarnings("nullness:methodref.receiver.bound")
      Handler downloadThreadHandler =
          Util.createHandlerForCurrentOrMainLooper(this::handleDownloadHelperCallbackMessage);
      this.downloadHelperHandler = downloadThreadHandler;
      mediaSourceThread = new HandlerThread("ExoPlayer:DownloadHelper");
      mediaSourceThread.start();
      mediaSourceHandler = Util.createHandler(mediaSourceThread.getLooper(), /* callback= */ this);
      mediaSourceHandler.sendEmptyMessage(MESSAGE_PREPARE_SOURCE);
    }

    public void release() {
      if (released) {
        return;
      }
      released = true;
      mediaSourceHandler.sendEmptyMessage(MESSAGE_RELEASE);
    }

    // Handler.Callback

    @Override
    public boolean handleMessage(Message msg) {
      switch (msg.what) {
        case MESSAGE_PREPARE_SOURCE:
          if (mediaSource instanceof ProgressiveMediaSource) {
            ((ProgressiveMediaSource) mediaSource).setListener(this);
          }
          mediaSource.prepareSource(
              /* caller= */ this, /* mediaTransferListener= */ null, PlayerId.UNSET);
          mediaSourceHandler.sendEmptyMessage(MESSAGE_CHECK_FOR_FAILURE);
          return true;
        case MESSAGE_CHECK_FOR_FAILURE:
          try {
            if (mediaPeriods == null) {
              mediaSource.maybeThrowSourceInfoRefreshError();
            } else {
              for (int i = 0; i < pendingMediaPeriods.size(); i++) {
                pendingMediaPeriods.get(i).maybeThrowPrepareError();
              }
            }
            mediaSourceHandler.sendEmptyMessageDelayed(
                MESSAGE_CHECK_FOR_FAILURE, /* delayMillis= */ 100);
          } catch (IOException e) {
            downloadHelperHandler
                .obtainMessage(DOWNLOAD_HELPER_CALLBACK_MESSAGE_FAILED, /* obj= */ e)
                .sendToTarget();
          }
          return true;
        case MESSAGE_CONTINUE_LOADING:
          MediaPeriod mediaPeriod = (MediaPeriod) msg.obj;
          if (pendingMediaPeriods.contains(mediaPeriod)) {
            mediaPeriod.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
          }
          return true;
        case MESSAGE_RELEASE:
          if (mediaPeriods != null) {
            for (MediaPeriod period : mediaPeriods) {
              mediaSource.releasePeriod(period);
            }
          }
          if (mediaSource instanceof ProgressiveMediaSource) {
            ((ProgressiveMediaSource) mediaSource).clearListener();
          }
          mediaSource.releaseSource(this);
          mediaSourceHandler.removeCallbacksAndMessages(null);
          mediaSourceThread.quit();
          return true;
        default:
          return false;
      }
    }

    // MediaSource.MediaSourceCaller implementation.

    @Override
    public void onSourceInfoRefreshed(MediaSource source, Timeline timeline) {
      if (this.timeline != null) {
        // Ignore dynamic updates.
        return;
      }
      if (timeline.getWindow(/* windowIndex= */ 0, new Timeline.Window()).isLive()) {
        downloadHelperHandler
            .obtainMessage(
                DOWNLOAD_HELPER_CALLBACK_MESSAGE_FAILED,
                /* obj= */ new LiveContentUnsupportedException())
            .sendToTarget();
        return;
      }
      this.timeline = timeline;
      mediaPeriods = new MediaPeriod[timeline.getPeriodCount()];
      for (int i = 0; i < mediaPeriods.length; i++) {
        MediaPeriod mediaPeriod =
            mediaSource.createPeriod(
                new MediaPeriodId(timeline.getUidOfPeriod(/* periodIndex= */ i)),
                allocator,
                /* startPositionUs= */ 0);
        mediaPeriods[i] = mediaPeriod;
        pendingMediaPeriods.add(mediaPeriod);
      }
      for (MediaPeriod mediaPeriod : mediaPeriods) {
        mediaPeriod.prepare(/* callback= */ this, /* positionUs= */ 0);
      }
    }

    // ProgressiveMediaSource.Listener implementation.

    @Override
    public void onSeekMap(MediaSource source, SeekMap seekMap) {
      this.seekMap = seekMap;
    }

    // MediaPeriod.Callback implementation.

    @Override
    public void onPrepared(MediaPeriod mediaPeriod) {
      pendingMediaPeriods.remove(mediaPeriod);
      if (pendingMediaPeriods.isEmpty()) {
        mediaSourceHandler.removeMessages(MESSAGE_CHECK_FOR_FAILURE);
        downloadHelperHandler.sendEmptyMessage(DOWNLOAD_HELPER_CALLBACK_MESSAGE_PREPARED);
      }
    }

    @Override
    public void onContinueLoadingRequested(MediaPeriod mediaPeriod) {
      if (pendingMediaPeriods.contains(mediaPeriod)) {
        mediaSourceHandler.obtainMessage(MESSAGE_CONTINUE_LOADING, mediaPeriod).sendToTarget();
      }
    }

    private boolean handleDownloadHelperCallbackMessage(Message msg) {
      if (released) {
        // Stale message.
        return false;
      }
      switch (msg.what) {
        case DOWNLOAD_HELPER_CALLBACK_MESSAGE_PREPARED:
          try {
            downloadHelper.onMediaPrepared();
          } catch (ExoPlaybackException e) {
            downloadHelperHandler
                .obtainMessage(
                    DOWNLOAD_HELPER_CALLBACK_MESSAGE_FAILED, /* obj= */ new IOException(e))
                .sendToTarget();
          }
          return true;
        case DOWNLOAD_HELPER_CALLBACK_MESSAGE_FAILED:
          release();
          downloadHelper.onMediaPreparationFailed((IOException) castNonNull(msg.obj));
          return true;
        default:
          return false;
      }
    }
  }

  private static final class DownloadTrackSelection extends BaseTrackSelection {

    private static final class Factory implements ExoTrackSelection.Factory {

      @Override
      public @NullableType ExoTrackSelection[] createTrackSelections(
          @NullableType Definition[] definitions,
          BandwidthMeter bandwidthMeter,
          MediaPeriodId mediaPeriodId,
          Timeline timeline) {
        @NullableType ExoTrackSelection[] selections = new ExoTrackSelection[definitions.length];
        for (int i = 0; i < definitions.length; i++) {
          selections[i] =
              definitions[i] == null
                  ? null
                  : new DownloadTrackSelection(definitions[i].group, definitions[i].tracks);
        }
        return selections;
      }
    }

    public DownloadTrackSelection(TrackGroup trackGroup, int[] tracks) {
      super(trackGroup, tracks);
    }

    @Override
    public int getSelectedIndex() {
      return 0;
    }

    @Override
    public @C.SelectionReason int getSelectionReason() {
      return C.SELECTION_REASON_UNKNOWN;
    }

    @Override
    @Nullable
    public Object getSelectionData() {
      return null;
    }

    @Override
    public void updateSelectedTrack(
        long playbackPositionUs,
        long bufferedDurationUs,
        long availableDurationUs,
        List<? extends MediaChunk> queue,
        MediaChunkIterator[] mediaChunkIterators) {
      // Do nothing.
    }
  }

  private static final class FakeBandwidthMeter implements BandwidthMeter {

    @Override
    public long getBitrateEstimate() {
      return 0;
    }

    @Override
    @Nullable
    public TransferListener getTransferListener() {
      return null;
    }

    @Override
    public void addEventListener(Handler eventHandler, EventListener eventListener) {
      // Do nothing.
    }

    @Override
    public void removeEventListener(EventListener eventListener) {
      // Do nothing.
    }
  }

  private static final class UnreleaseableRendererCapabilitiesList
      implements RendererCapabilitiesList {

    private final RendererCapabilities[] rendererCapabilities;

    private UnreleaseableRendererCapabilitiesList(RendererCapabilities[] rendererCapabilities) {
      this.rendererCapabilities = rendererCapabilities;
    }

    @Override
    public RendererCapabilities[] getRendererCapabilities() {
      return rendererCapabilities;
    }

    @Override
    public int size() {
      return rendererCapabilities.length;
    }

    @Override
    public void release() {}
  }
}
