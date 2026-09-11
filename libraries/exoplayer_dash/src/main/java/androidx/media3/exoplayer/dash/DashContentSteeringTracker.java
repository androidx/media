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
package androidx.media3.exoplayer.dash;

import static com.google.common.base.Preconditions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.dash.manifest.AdaptationSet;
import androidx.media3.exoplayer.dash.manifest.BaseUrl;
import androidx.media3.exoplayer.dash.manifest.DashManifest;
import androidx.media3.exoplayer.dash.manifest.Location;
import androidx.media3.exoplayer.dash.manifest.Period;
import androidx.media3.exoplayer.dash.manifest.Representation;
import androidx.media3.exoplayer.upstream.contentsteering.BaseContentSteeringTracker;
import androidx.media3.exoplayer.upstream.contentsteering.SteeringManifest;
import androidx.media3.exoplayer.util.ReleasableExecutor;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Tracks the content steering states for a DASH stream. */
@UnstableApi
public final class DashContentSteeringTracker extends BaseContentSteeringTracker {

  /** A callback to be notified of {@link DashContentSteeringTracker} events. */
  public interface Callback {

    /**
     * Called when the service location priority is updated.
     *
     * @param serviceLocationPriority The updated service location priority, or {@code null} if the
     *     tracker becomes inactive.
     */
    void onServiceLocationPriorityUpdated(@Nullable ImmutableList<String> serviceLocationPriority);
  }

  /** Provides the information for the query parameters to request the steering manifests. */
  public interface SteeringQueryParamsProvider {

    /** Returns the steered service locations observed by this provider. */
    ImmutableList<String> getSteeredServiceLocations();
  }

  private static final String PATHWAY_PARAM = "_DASH_pathway";
  private static final String THROUGHPUT_PARAM = "_DASH_throughput";
  @Nullable private final Callback callback;
  private final ServiceLocationFilteringThroughputEstimator throughputEstimator;
  private final Set<SteeringQueryParamsProvider> steeringQueryParamsProviders;

  @Nullable private DashManifest manifest;
  private Map<String, Set<Location>> availableManifestLocations;
  private Map<String, Set<BaseUrl>> availableBaseUrls;

  /**
   * Creates a {@link DashContentSteeringTracker}.
   *
   * @param dataSourceFactory The {@link DataSource.Factory}.
   * @param downloadExecutorSupplier A supplier for a {@link ReleasableExecutor}, or {@code null}.
   * @param callback A {@link Callback}.
   * @param mediaTransferListener The {@link TransferListener} to be used by the {@link DataSource}
   *     for media loading, which should be the {@link TransferListener} passed to the {@link
   *     DashMediaSource} when the source is prepared.
   * @param manifest The {@link DashManifest}.
   */
  public DashContentSteeringTracker(
      DataSource.Factory dataSourceFactory,
      @Nullable Supplier<ReleasableExecutor> downloadExecutorSupplier,
      @Nullable Callback callback,
      @Nullable TransferListener mediaTransferListener,
      DashManifest manifest) {
    super(dataSourceFactory, downloadExecutorSupplier);
    this.callback = callback;
    this.throughputEstimator =
        new ServiceLocationFilteringThroughputEstimator(mediaTransferListener);
    this.steeringQueryParamsProviders = new HashSet<>();
    this.manifest = manifest;
    availableManifestLocations = getAvailableManifestLocations(manifest);
    availableBaseUrls = getAvailableBaseUrls(manifest);
  }

  /** Updates the {@link DashManifest} for the stream. */
  public void updateManifest(DashManifest manifest) {
    if (Objects.equals(manifest, this.manifest)) {
      return;
    }
    this.manifest = manifest;
    this.availableManifestLocations = getAvailableManifestLocations(manifest);
    this.availableBaseUrls = getAvailableBaseUrls(manifest);
  }

  /** Adds a {@link SteeringQueryParamsProvider} to the tracker. */
  public void addSteeringQueryParamsProvider(SteeringQueryParamsProvider provider) {
    steeringQueryParamsProviders.add(provider);
  }

  /** Removes a {@link SteeringQueryParamsProvider} from the tracker. */
  public void removeSteeringQueryParamsProvider(SteeringQueryParamsProvider provider) {
    steeringQueryParamsProviders.remove(provider);
  }

  /**
   * Returns the {@link TransferListener} that should be used by the {@link DataSource} for media
   * loading.
   */
  public TransferListener getMediaTransferListener() {
    return throughputEstimator;
  }

  @Override
  protected void onStart(ImmutableList<String> initialPathwayIds) {
    if (callback != null && !initialPathwayIds.isEmpty()) {
      callback.onServiceLocationPriorityUpdated(initialPathwayIds);
    }
  }

  @Override
  protected boolean isPathwayAvailable(String pathwayId) {
    return availableManifestLocations.containsKey(pathwayId)
        || availableBaseUrls.containsKey(pathwayId);
  }

  @Override
  protected ImmutableMap<String, String> getSteeringQueryParameters() {
    if (getCurrentPathwayPriority() == null) {
      // We omit the _DASH_pathway and _DASH_throughput query parameters for the first steering
      // manifest request.
      return ImmutableMap.of();
    }
    Map<String, Long> serviceLocationsToThroughputs = new HashMap<>();
    for (SteeringQueryParamsProvider provider : steeringQueryParamsProviders) {
      ImmutableList<String> steeredServiceLocations = provider.getSteeredServiceLocations();
      for (String steeredServiceLocation : steeredServiceLocations) {
        if (serviceLocationsToThroughputs.containsKey(steeredServiceLocation)) {
          continue;
        }
        long throughput =
            checkNotNull(throughputEstimator).getThroughputEstimate(steeredServiceLocation);
        serviceLocationsToThroughputs.put(steeredServiceLocation, throughput);
      }
    }
    ArrayList<String> steeredServiceLocations = new ArrayList<>();
    ArrayList<String> throughputs = new ArrayList<>();
    boolean hasAnyThroughputEstimate = false;
    for (Map.Entry<String, Long> entry : serviceLocationsToThroughputs.entrySet()) {
      steeredServiceLocations.add(entry.getKey());
      long throughput = entry.getValue();
      if (throughput != 0L) {
        hasAnyThroughputEstimate = true;
        throughputs.add(String.valueOf(throughput));
      } else {
        throughputs.add("");
      }
    }
    ImmutableMap.Builder<String, String> queryParameters = ImmutableMap.builder();
    if (!steeredServiceLocations.isEmpty()) {
      queryParameters.put(PATHWAY_PARAM, String.join(",", steeredServiceLocations));
      if (hasAnyThroughputEstimate) {
        queryParameters.put(THROUGHPUT_PARAM, String.join(",", throughputs));
      }
    }
    return queryParameters.buildOrThrow();
  }

  @Override
  protected void performPathwayEvaluation(ImmutableList<String> pathwayPriority) {
    if (callback != null) {
      callback.onServiceLocationPriorityUpdated(pathwayPriority);
    }
  }

  @Override
  protected void performPathwayClone(SteeringManifest.PathwayClone pathwayClone) {
    // TODO: Add the support of Pathway Clone
  }

  @Override
  protected void onStop() {
    manifest = null;
    if (callback != null) {
      callback.onServiceLocationPriorityUpdated(null);
    }
    availableManifestLocations.clear();
    availableBaseUrls.clear();
    steeringQueryParamsProviders.clear();
    throughputEstimator.stopThroughputEstimate();
  }

  private static Map<String, Set<BaseUrl>> getAvailableBaseUrls(DashManifest manifest) {
    Map<String, Set<BaseUrl>> availableBaseUrls = new HashMap<>();
    for (int i = 0; i < manifest.getPeriodCount(); i++) {
      Period period = manifest.getPeriod(i);
      for (AdaptationSet adaptationSet : period.adaptationSets) {
        for (Representation representation : adaptationSet.representations) {
          for (BaseUrl baseUrl : representation.baseUrls) {
            String serviceLocation = baseUrl.serviceLocation;
            @Nullable
            Set<BaseUrl> availableBaseUrlsForServiceLocation =
                availableBaseUrls.get(serviceLocation);
            if (availableBaseUrlsForServiceLocation == null) {
              availableBaseUrlsForServiceLocation = new HashSet<>();
              availableBaseUrls.put(serviceLocation, availableBaseUrlsForServiceLocation);
            }
            availableBaseUrlsForServiceLocation.add(baseUrl);
          }
        }
      }
    }
    return availableBaseUrls;
  }

  private static Map<String, Set<Location>> getAvailableManifestLocations(DashManifest manifest) {
    Map<String, Set<Location>> availableManifestLocations = new HashMap<>();
    for (int i = 0; i < manifest.locations.size(); i++) {
      Location location = manifest.locations.get(i);
      String serviceLocation = location.serviceLocation;
      @Nullable
      Set<Location> availableLocationsForServiceLocation =
          availableManifestLocations.get(serviceLocation);
      if (availableLocationsForServiceLocation == null) {
        availableLocationsForServiceLocation = new HashSet<>();
        availableManifestLocations.put(serviceLocation, availableLocationsForServiceLocation);
      }
      availableLocationsForServiceLocation.add(location);
    }
    return availableManifestLocations;
  }
}
