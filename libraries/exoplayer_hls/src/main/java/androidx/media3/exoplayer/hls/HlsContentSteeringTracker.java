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
package androidx.media3.exoplayer.hls;

import static androidx.media3.common.C.DATA_TYPE_STEERING_MANIFEST;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker;
import androidx.media3.exoplayer.hls.playlist.HlsRedundantGroup;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.contentsteering.ContentSteeringTracker;
import androidx.media3.exoplayer.upstream.contentsteering.SteeringManifest;
import androidx.media3.exoplayer.upstream.contentsteering.SteeringManifestTracker;
import androidx.media3.exoplayer.util.ReleasableExecutor;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Tracks the content steering states for an HLS stream. */
@UnstableApi
public final class HlsContentSteeringTracker implements ContentSteeringTracker {

  /** A callback to be notified of {@link HlsContentSteeringTracker} events. */
  public interface Callback {

    /**
     * Called when the current pathway is updated.
     *
     * @param currentPathwayId The current pathway ID after the update.
     * @param previousPathwayId The pathway ID before the update, or {@code null} if the call of
     *     this method is the result of starting the tracker.
     * @param previousPathwayExcludeDurationMs The exclude duration in milliseconds if the update is
     *     due to {@linkplain #excludeCurrentPathway(long) the exclusion of the previous pathway},
     *     or {@link C#TIME_UNSET} if the previous pathway is not excluded.
     */
    void onCurrentPathwayUpdated(
        String currentPathwayId,
        @Nullable String previousPathwayId,
        long previousPathwayExcludeDurationMs);
  }

  private static final String PATHWAY_PARAM = "_HLS_pathway";
  private static final String THROUGHPUT_PARAM = "_HLS_throughput";

  private final SteeringManifestTracker steeringManifestTracker;
  @Nullable private final Callback callback;
  private final BandwidthMeter bandwidthMeter;
  private final ImmutableSet<String> availablePathwayIds;
  private final Set<String> excludedPathwayIds;
  private final HandlerWrapper handler;
  private boolean isActive;
  private String currentPathwayId;
  @Nullable private ImmutableList<String> currentPathwayPriority;

  /**
   * Creates an {@link HlsContentSteeringTracker}.
   *
   * @param dataSourceFactory The {@link HlsDataSourceFactory} to create data sources for loading
   *     steering manifests.
   * @param downloadExecutorSupplier A supplier to obtain a {@link ReleasableExecutor} for
   *     downloading steering manifests, or {@code null}.
   * @param playlistTracker The {@link HlsPlaylistTracker}.
   * @param callback A {@link Callback} to receive events, or {@code null}.
   * @param bandwidthMeter The {@link BandwidthMeter} to obtain throughput estimates.
   * @param clock The {@link Clock} to schedule handler messages.
   */
  public HlsContentSteeringTracker(
      HlsDataSourceFactory dataSourceFactory,
      @Nullable Supplier<ReleasableExecutor> downloadExecutorSupplier,
      HlsPlaylistTracker playlistTracker,
      @Nullable Callback callback,
      BandwidthMeter bandwidthMeter,
      Clock clock) {
    this.steeringManifestTracker =
        new SteeringManifestTracker(
            () -> dataSourceFactory.createDataSource(DATA_TYPE_STEERING_MANIFEST),
            downloadExecutorSupplier);
    this.callback = callback;
    this.bandwidthMeter = bandwidthMeter;
    List<HlsRedundantGroup> variantRedundantGroups =
        checkNotNull(playlistTracker.getRedundantGroups(HlsRedundantGroup.VARIANT));
    checkState(!variantRedundantGroups.isEmpty());
    availablePathwayIds = checkNotNull(variantRedundantGroups.get(0)).getAllPathwayIds();
    excludedPathwayIds = new HashSet<>();
    handler = clock.createHandler(Util.getCurrentOrMainLooper(), /* callback= */ null);
    currentPathwayId = variantRedundantGroups.get(0).getCurrentPathwayId();
  }

  /**
   * {@inheritDoc}
   *
   * @throws IllegalStateException If the {@code initialPathwayId} is not declared in the redundant
   *     group.
   */
  @Override
  public void start(
      Uri initialSteeringManifestUri,
      @Nullable String initialPathwayId,
      MediaSourceEventListener.EventDispatcher eventDispatcher) {
    if (initialPathwayId != null) {
      checkState(availablePathwayIds.contains(initialPathwayId));
      currentPathwayId = initialPathwayId;
    }
    isActive = true;
    notifyOnCurrentPathwayUpdated(
        /* previousPathwayId= */ null, /* previousPathwayExcludeDurationMs= */ C.TIME_UNSET);
    steeringManifestTracker.start(
        initialSteeringManifestUri, new SteeringManifestTrackerCallback(), eventDispatcher);
  }

  @Override
  public boolean excludeCurrentPathway(long excludeDurationMs) {
    if (isActive && currentPathwayPriority != null) {
      String previousPathwayId = currentPathwayId;
      performPathwayEvaluationAndUpdate(
          currentPathwayPriority, /* previousPathwayIdExcludeDurationMs= */ excludeDurationMs);
      if (!currentPathwayId.equals(previousPathwayId)) {
        excludedPathwayIds.add(previousPathwayId);
        handler.postDelayed(() -> expireExclusion(previousPathwayId), excludeDurationMs);
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean isActive() {
    return isActive;
  }

  @Override
  public void stop() {
    stopInternal();
  }

  private void expireExclusion(String pathwayId) {
    checkState(isActive);
    excludedPathwayIds.remove(pathwayId);
    if (currentPathwayPriority != null) {
      performPathwayEvaluationAndUpdate(
          currentPathwayPriority, /* previousPathwayIdExcludeDurationMs= */ C.TIME_UNSET);
    }
  }

  private void stopInternal() {
    steeringManifestTracker.stop();
    handler.removeCallbacksAndMessages(null);
    isActive = false;
  }

  private void performPathwayEvaluationAndUpdate(
      List<String> pathwayPriority, long previousPathwayIdExcludeDurationMs) {
    String previousPathwayId = currentPathwayId;
    for (String pathwayId : pathwayPriority) {
      if (previousPathwayIdExcludeDurationMs != C.TIME_UNSET
          && pathwayId.equals(previousPathwayId)) {
        continue;
      }
      if (availablePathwayIds.contains(pathwayId) && !excludedPathwayIds.contains(pathwayId)) {
        currentPathwayId = pathwayId;
        break;
      }
    }
    if (!currentPathwayId.equals(previousPathwayId)) {
      notifyOnCurrentPathwayUpdated(previousPathwayId, previousPathwayIdExcludeDurationMs);
    }
  }

  private void notifyOnCurrentPathwayUpdated(
      @Nullable String previousPathwayId, long previousPathwayExcludeDurationMs) {
    if (callback != null) {
      callback.onCurrentPathwayUpdated(
          currentPathwayId, previousPathwayId, previousPathwayExcludeDurationMs);
    }
  }

  private class SteeringManifestTrackerCallback implements SteeringManifestTracker.Callback {

    @Override
    public ImmutableMap<String, String> getSteeringQueryParameters() {
      checkState(isActive);
      return ImmutableMap.of(
          PATHWAY_PARAM,
          currentPathwayId,
          THROUGHPUT_PARAM,
          String.valueOf(bandwidthMeter.getBitrateEstimate()));
    }

    @Override
    public void onSteeringManifestUpdated(SteeringManifest steeringManifest) {
      checkState(isActive);
      currentPathwayPriority = steeringManifest.pathwayPriority;
      performPathwayEvaluationAndUpdate(
          currentPathwayPriority, /* previousPathwayIdExcludeDurationMs= */ C.TIME_UNSET);
    }

    @Override
    public void onSteeringManifestLoadError(IOException error, boolean canceled) {
      checkState(isActive);
      if (canceled && currentPathwayPriority == null) {
        stopInternal();
      }
    }
  }
}
