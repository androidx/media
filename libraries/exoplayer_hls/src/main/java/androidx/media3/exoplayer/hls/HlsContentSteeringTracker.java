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
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker;
import androidx.media3.exoplayer.hls.playlist.HlsRedundantGroup;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.contentsteering.BaseContentSteeringTracker;
import androidx.media3.exoplayer.upstream.contentsteering.ContentSteeringTracker;
import androidx.media3.exoplayer.upstream.contentsteering.SteeringManifest;
import androidx.media3.exoplayer.util.ReleasableExecutor;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.HashSet;
import java.util.List;

/** Tracks the content steering states for an HLS stream. */
@UnstableApi
public final class HlsContentSteeringTracker extends BaseContentSteeringTracker {

  private static final String PATHWAY_PARAM = "_HLS_pathway";
  private static final String THROUGHPUT_PARAM = "_HLS_throughput";

  private final BandwidthMeter bandwidthMeter;
  private final List<HlsRedundantGroup> variantRedundantGroups;
  private final List<HlsRedundantGroup> videoRenditionRedundantGroups;
  private final List<HlsRedundantGroup> audioRenditionRedundantGroups;
  private final List<HlsRedundantGroup> subtitleRenditionRedundantGroups;

  /**
   * Creates an {@link HlsContentSteeringTracker}.
   *
   * @param dataSourceFactory The {@link HlsDataSourceFactory}.
   * @param downloadExecutorSupplier A supplier to obtain a {@link ReleasableExecutor}, or {@code
   *     null}.
   * @param playlistTracker The {@link HlsPlaylistTracker}.
   * @param callback A {@link ContentSteeringTracker.Callback} to receive events, or {@code null}.
   * @param bandwidthMeter The {@link BandwidthMeter}.
   * @param clock The {@link Clock}.
   */
  public HlsContentSteeringTracker(
      HlsDataSourceFactory dataSourceFactory,
      @Nullable Supplier<ReleasableExecutor> downloadExecutorSupplier,
      HlsPlaylistTracker playlistTracker,
      @Nullable ContentSteeringTracker.Callback callback,
      BandwidthMeter bandwidthMeter,
      Clock clock) {
    super(
        () -> dataSourceFactory.createDataSource(DATA_TYPE_STEERING_MANIFEST),
        downloadExecutorSupplier,
        callback,
        clock,
        new HashSet<>(getFirstVariantRedundantGroup(playlistTracker).getAllPathwayIds()));
    this.bandwidthMeter = bandwidthMeter;
    this.variantRedundantGroups =
        checkNotNull(playlistTracker.getRedundantGroups(HlsRedundantGroup.VARIANT));
    this.videoRenditionRedundantGroups =
        checkNotNull(playlistTracker.getRedundantGroups(HlsRedundantGroup.VIDEO_RENDITION));
    this.audioRenditionRedundantGroups =
        checkNotNull(playlistTracker.getRedundantGroups(HlsRedundantGroup.AUDIO_RENDITION));
    this.subtitleRenditionRedundantGroups =
        checkNotNull(playlistTracker.getRedundantGroups(HlsRedundantGroup.SUBTITLE_RENDITION));
  }

  @Override
  protected ImmutableMap<String, String> getSteeringQueryParameters() {
    return ImmutableMap.of(
        PATHWAY_PARAM,
        checkNotNull(getCurrentPathwayId()),
        THROUGHPUT_PARAM,
        String.valueOf(bandwidthMeter.getBitrateEstimate()));
  }

  @Override
  protected Pair<ImmutableList<Uri>, ImmutableList<Uri>> performPathwayClone(
      SteeringManifest.PathwayClone pathwayClone) {
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
    return Pair.create(newPlaylistUrls.build(), basePlaylistUrls.build());
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
}
