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
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.contentsteering.BaseContentSteeringTracker;
import androidx.media3.exoplayer.upstream.contentsteering.SteeringManifest;
import androidx.media3.exoplayer.util.ReleasableExecutor;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Tracks the content steering states for an HLS stream. */
@UnstableApi
public final class HlsContentSteeringTracker extends BaseContentSteeringTracker {

  /** A callback to be notified of {@link HlsContentSteeringTracker} events. */
  public interface Callback {

    /**
     * Called when the current pathway is updated.
     *
     * @param currentPathwayId The current pathway ID after the update.
     * @param previousPathwayId The pathway ID before the update, or {@code null} if the call of
     *     this method is the result of starting the tracker.
     * @param previousPathwayExcludeDurationMs The exclude duration in milliseconds if the update is
     *     due to the exclusion of the previous pathway, or {@link C#TIME_UNSET} if the previous
     *     pathway is not excluded.
     */
    void onCurrentPathwayUpdated(
        String currentPathwayId,
        @Nullable String previousPathwayId,
        long previousPathwayExcludeDurationMs);

    /**
     * Called when a new pathway cloned from an existing pathway becomes available.
     *
     * <p>The lists {@code newUris} and {@code baseUris} have the equal size, and each URI in the
     * {@code newUris} is cloned from the base URI in the {@code baseUris} of the same index.
     *
     * @param newPathwayId The new pathway ID.
     * @param basePathwayId The base pathway ID.
     * @param newUris The list of new cloned URIs.
     * @param baseUris The list of base URIs.
     */
    void onNewPathwayAvailable(
        String newPathwayId,
        String basePathwayId,
        ImmutableList<Uri> newUris,
        ImmutableList<Uri> baseUris);
  }

  private static final String PATHWAY_PARAM = "_HLS_pathway";
  private static final String THROUGHPUT_PARAM = "_HLS_throughput";

  private final BandwidthMeter bandwidthMeter;
  @Nullable private final Callback callback;
  private final Set<String> availablePathwayIds;
  private final Set<String> excludedPathwayIds;
  private final HandlerWrapper handler;
  private final List<HlsRedundantGroup> variantRedundantGroups;
  private final List<HlsRedundantGroup> videoRenditionRedundantGroups;
  private final List<HlsRedundantGroup> audioRenditionRedundantGroups;
  private final List<HlsRedundantGroup> subtitleRenditionRedundantGroups;

  @Nullable private String currentPathwayId;

  /**
   * Creates an {@link HlsContentSteeringTracker}.
   *
   * @param dataSourceFactory The {@link HlsDataSourceFactory}.
   * @param downloadExecutorSupplier A supplier to obtain a {@link ReleasableExecutor}, or {@code
   *     null}.
   * @param playlistTracker The {@link HlsPlaylistTracker}.
   * @param callback A {@link HlsContentSteeringTracker.Callback} to receive events, or {@code
   *     null}.
   * @param bandwidthMeter The {@link BandwidthMeter}.
   * @param clock The {@link Clock}.
   */
  public HlsContentSteeringTracker(
      HlsDataSourceFactory dataSourceFactory,
      @Nullable Supplier<ReleasableExecutor> downloadExecutorSupplier,
      HlsPlaylistTracker playlistTracker,
      @Nullable HlsContentSteeringTracker.Callback callback,
      BandwidthMeter bandwidthMeter,
      Clock clock) {
    super(
        () -> dataSourceFactory.createDataSource(DATA_TYPE_STEERING_MANIFEST),
        downloadExecutorSupplier);
    this.callback = callback;
    this.bandwidthMeter = bandwidthMeter;
    this.availablePathwayIds =
        new HashSet<>(getFirstVariantRedundantGroup(playlistTracker).getAllPathwayIds());
    this.excludedPathwayIds = new HashSet<>();
    this.variantRedundantGroups =
        checkNotNull(playlistTracker.getRedundantGroups(HlsRedundantGroup.VARIANT));
    this.videoRenditionRedundantGroups =
        checkNotNull(playlistTracker.getRedundantGroups(HlsRedundantGroup.VIDEO_RENDITION));
    this.audioRenditionRedundantGroups =
        checkNotNull(playlistTracker.getRedundantGroups(HlsRedundantGroup.AUDIO_RENDITION));
    this.subtitleRenditionRedundantGroups =
        checkNotNull(playlistTracker.getRedundantGroups(HlsRedundantGroup.SUBTITLE_RENDITION));
    this.handler = clock.createHandler(Util.getCurrentOrMainLooper(), /* callback= */ null);
  }

  /**
   * Excludes the current pathway for the given duration.
   *
   * @param excludeDurationMs The duration in milliseconds to exclude the current pathway.
   * @return Whether the current pathway was excluded.
   */
  public boolean excludeCurrentPathway(long excludeDurationMs) {
    @Nullable ImmutableList<String> currentPathwayPriority = getCurrentPathwayPriority();
    if (isActive() && currentPathwayPriority != null) {
      String previousPathwayId = checkNotNull(currentPathwayId);
      performPathwayEvaluationAndUpdate(
          currentPathwayPriority, /* previousPathwayIdExcludeDurationMs= */ excludeDurationMs);
      if (!Objects.equals(currentPathwayId, previousPathwayId)) {
        excludedPathwayIds.add(previousPathwayId);
        handler.postDelayed(() -> expireExclusion(previousPathwayId), excludeDurationMs);
        return true;
      }
    }
    return false;
  }

  private void expireExclusion(String pathwayId) {
    checkState(isActive());
    excludedPathwayIds.remove(pathwayId);
    @Nullable ImmutableList<String> currentPathwayPriority = getCurrentPathwayPriority();
    if (currentPathwayPriority != null) {
      performPathwayEvaluationAndUpdate(currentPathwayPriority, C.TIME_UNSET);
    }
  }

  @Override
  protected void onStart(ImmutableList<String> initialPathwayIds) {
    checkState(initialPathwayIds.size() <= 1);
    currentPathwayId =
        !initialPathwayIds.isEmpty()
            ? Iterables.getOnlyElement(initialPathwayIds)
            : variantRedundantGroups.get(0).getCurrentPathwayId();
    notifyOnCurrentPathwayUpdated(
        currentPathwayId,
        /* previousPathwayId= */ null,
        /* previousPathwayExcludeDurationMs= */ C.TIME_UNSET);
  }

  @Override
  protected boolean isPathwayAvailable(String pathwayId) {
    return availablePathwayIds.contains(pathwayId);
  }

  @Override
  protected ImmutableMap<String, String> getSteeringQueryParameters() {
    return ImmutableMap.of(
        PATHWAY_PARAM,
        checkNotNull(currentPathwayId),
        THROUGHPUT_PARAM,
        String.valueOf(bandwidthMeter.getBitrateEstimate()));
  }

  @Override
  protected void performPathwayEvaluation(ImmutableList<String> pathwayPriority) {
    performPathwayEvaluationAndUpdate(pathwayPriority, C.TIME_UNSET);
  }

  private void performPathwayEvaluationAndUpdate(
      ImmutableList<String> pathwayPriority, long previousPathwayIdExcludeDurationMs) {
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
    if (!Objects.equals(currentPathwayId, previousPathwayId)) {
      notifyOnCurrentPathwayUpdated(
          checkNotNull(currentPathwayId), previousPathwayId, previousPathwayIdExcludeDurationMs);
    }
  }

  @Override
  protected void performPathwayClone(SteeringManifest.PathwayClone pathwayClone) {
    availablePathwayIds.add(pathwayClone.id);
    ImmutableList.Builder<Uri> newPlaylistUrls = new ImmutableList.Builder<>();
    ImmutableList.Builder<Uri> basePlaylistUrls = new ImmutableList.Builder<>();
    performPathwayCloneForVariants(
        pathwayClone, variantRedundantGroups, newPlaylistUrls, basePlaylistUrls);
    performPathwayCloneForRenditions(
        pathwayClone, videoRenditionRedundantGroups, newPlaylistUrls, basePlaylistUrls);
    performPathwayCloneForRenditions(
        pathwayClone, audioRenditionRedundantGroups, newPlaylistUrls, basePlaylistUrls);
    performPathwayCloneForRenditions(
        pathwayClone, subtitleRenditionRedundantGroups, newPlaylistUrls, basePlaylistUrls);
    if (callback != null) {
      callback.onNewPathwayAvailable(
          pathwayClone.id, pathwayClone.baseId, newPlaylistUrls.build(), basePlaylistUrls.build());
    }
  }

  @Override
  protected void onStop() {
    currentPathwayId = null;
    handler.removeCallbacksAndMessages(null);
    excludedPathwayIds.clear();
  }

  private void performPathwayCloneForVariants(
      SteeringManifest.PathwayClone pathwayClone,
      List<HlsRedundantGroup> variantRedundantGroups,
      ImmutableList.Builder<Uri> newPlaylistUrls,
      ImmutableList.Builder<Uri> basePlaylistUrls) {
    ImmutableMap<String, Uri> perVariantUris = pathwayClone.uriReplacement.perVariantUris;
    for (HlsRedundantGroup variantRedundantGroup : variantRedundantGroups) {
      String basePathwayId = pathwayClone.baseId;
      Uri basePlaylistUrl = checkNotNull(variantRedundantGroup.getPlaylistUrl(basePathwayId));
      Uri newPlaylistUrl =
          perVariantUris.containsKey(variantRedundantGroup.groupKey.stableId)
              ? checkNotNull(perVariantUris.get(variantRedundantGroup.groupKey.stableId))
              : applyUriReplacement(basePlaylistUrl, pathwayClone.uriReplacement);
      newPlaylistUrls.add(newPlaylistUrl);
      basePlaylistUrls.add(basePlaylistUrl);
    }
  }

  private void performPathwayCloneForRenditions(
      SteeringManifest.PathwayClone pathwayClone,
      List<HlsRedundantGroup> renditionRedundantGroups,
      ImmutableList.Builder<Uri> newPlaylistUrls,
      ImmutableList.Builder<Uri> basePlaylistUrls) {
    ImmutableMap<String, Uri> perRenditionUris = pathwayClone.uriReplacement.perRenditionUris;
    for (HlsRedundantGroup renditionRedundantGroup : renditionRedundantGroups) {
      String basePathwayId = pathwayClone.baseId;
      Uri basePlaylistUrl = checkNotNull(renditionRedundantGroup.getPlaylistUrl(basePathwayId));
      Uri newPlaylistUrl =
          perRenditionUris.containsKey(renditionRedundantGroup.groupKey.stableId)
              ? checkNotNull(perRenditionUris.get(renditionRedundantGroup.groupKey.stableId))
              : applyUriReplacement(basePlaylistUrl, pathwayClone.uriReplacement);
      newPlaylistUrls.add(newPlaylistUrl);
      basePlaylistUrls.add(basePlaylistUrl);
    }
  }

  private static HlsRedundantGroup getFirstVariantRedundantGroup(
      HlsPlaylistTracker playlistTracker) {
    List<HlsRedundantGroup> variantRedundantGroups =
        checkNotNull(playlistTracker.getRedundantGroups(HlsRedundantGroup.VARIANT));
    checkState(!variantRedundantGroups.isEmpty());
    return variantRedundantGroups.get(0);
  }

  private void notifyOnCurrentPathwayUpdated(
      String currentPathwayId,
      @Nullable String previousPathwayId,
      long previousPathwayExcludeDurationMs) {
    if (callback != null) {
      callback.onCurrentPathwayUpdated(
          currentPathwayId, previousPathwayId, previousPathwayExcludeDurationMs);
    }
  }
}
