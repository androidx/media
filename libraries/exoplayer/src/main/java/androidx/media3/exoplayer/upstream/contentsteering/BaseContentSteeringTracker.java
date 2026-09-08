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
package androidx.media3.exoplayer.upstream.contentsteering;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.util.ReleasableExecutor;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Abstract base class for Content Steering tracker implementations.
 *
 * <p>It provides common state management for steering manifest tracking, pathway evaluation, and
 * pathway cloning.
 */
@UnstableApi
public abstract class BaseContentSteeringTracker {

  private final SteeringManifestTracker steeringManifestTracker;
  @Nullable private ImmutableList<String> currentPathwayPriority;
  private boolean isActive;

  /**
   * Creates a {@link BaseContentSteeringTracker}.
   *
   * @param dataSourceFactory The {@link DataSource.Factory} to load steering manifests.
   * @param downloadExecutorSupplier A supplier for a {@link ReleasableExecutor} to download
   *     steering manifests.
   */
  protected BaseContentSteeringTracker(
      DataSource.Factory dataSourceFactory,
      @Nullable Supplier<ReleasableExecutor> downloadExecutorSupplier) {
    this.steeringManifestTracker =
        new SteeringManifestTracker(dataSourceFactory, downloadExecutorSupplier);
  }

  /**
   * Starts the Content Steering tracker.
   *
   * @param initialSteeringManifestUri The initial {@link Uri} of the steering manifest.
   * @param initialPathwayIds The IDs of the initial pathways to use before the first steering
   *     manifest is loaded.
   * @param eventDispatcher A {@link MediaSourceEventListener.EventDispatcher} for reporting load
   *     events.
   */
  public final void start(
      Uri initialSteeringManifestUri,
      ImmutableList<String> initialPathwayIds,
      MediaSourceEventListener.EventDispatcher eventDispatcher) {
    checkState(!isActive());
    verifyInitialPathwayIdsAvailable(initialPathwayIds);
    isActive = true;
    onStart(initialPathwayIds);
    steeringManifestTracker.start(
        initialSteeringManifestUri, new SteeringManifestTrackerCallback(), eventDispatcher);
  }

  private void verifyInitialPathwayIdsAvailable(ImmutableList<String> initialPathwayIds) {
    for (String pathwayId : initialPathwayIds) {
      checkState(
          isPathwayAvailable(pathwayId), "The pathway with ID: " + pathwayId + " is not available");
    }
  }

  /**
   * Returns whether the Content Steering tracker is active.
   *
   * <p>If this method returns {@code false}, the caller of the tracker should behave as if content
   * steering is absent.
   */
  public final boolean isActive() {
    return isActive;
  }

  /**
   * Releases the Content Steering tracker.
   *
   * <p>Once released, the tracker cannot be used again.
   */
  public final void release() {
    stopInternal();
  }

  /**
   * Called when the tracker is started.
   *
   * @param initialPathwayIds The IDs of the initial pathways to be used before the first steering
   *     manifest is loaded.
   */
  protected abstract void onStart(ImmutableList<String> initialPathwayIds);

  /**
   * Returns whether the given pathway is currently available.
   *
   * @param pathwayId The ID of the pathway to check.
   * @return Whether the given pathway is available.
   */
  protected abstract boolean isPathwayAvailable(String pathwayId);

  /** Returns the query parameters to include in the steering manifest request. */
  protected abstract ImmutableMap<String, String> getSteeringQueryParameters();

  /**
   * Performs a pathway evaluation based on the passed pathway priority.
   *
   * @param pathwayPriority The {@linkplain SteeringManifest#pathwayPriority pathway priority} to
   *     use for evaluation.
   */
  protected abstract void performPathwayEvaluation(ImmutableList<String> pathwayPriority);

  /**
   * Performs a pathway clone defined in the steering manifest.
   *
   * @param pathwayClone The {@link SteeringManifest.PathwayClone} to perform.
   */
  protected abstract void performPathwayClone(SteeringManifest.PathwayClone pathwayClone);

  /**
   * Called when tracking is stopped.
   *
   * <p>This can be either externally called by {@link #release()} or internally due to an
   * unrecoverable error.
   */
  protected abstract void onStop();

  /**
   * Returns the current pathway priority.
   *
   * @return The current pathway priority, or {@code null} if the tracker is not active.
   */
  @Nullable
  protected final ImmutableList<String> getCurrentPathwayPriority() {
    return currentPathwayPriority;
  }

  /**
   * Applies a {@link SteeringManifest.UriReplacement} to a base URI.
   *
   * @param baseUri The base {@link Uri}.
   * @param uriReplacement The {@link SteeringManifest.UriReplacement}.
   * @return The updated {@link Uri}.
   */
  protected static Uri applyUriReplacement(
      Uri baseUri, SteeringManifest.UriReplacement uriReplacement) {
    Uri.Builder newUrlBuilder = baseUri.buildUpon().clearQuery();
    if (uriReplacement.host != null) {
      newUrlBuilder.authority(uriReplacement.host);
    }
    // Combine existing and new query parameters, giving precedence to new ones.
    Map<String, String> combinedParams = new TreeMap<>();
    // Add existing parameters.
    for (String existingParamName : baseUri.getQueryParameterNames()) {
      combinedParams.put(
          existingParamName, checkNotNull(baseUri.getQueryParameter(existingParamName)));
    }
    // Add new parameters, overwriting existing ones if keys clash.
    combinedParams.putAll(uriReplacement.params);
    // Append all parameters from the sorted map.
    for (Map.Entry<String, String> param : combinedParams.entrySet()) {
      newUrlBuilder.appendQueryParameter(param.getKey(), param.getValue());
    }
    return newUrlBuilder.build();
  }

  private void stopInternal() {
    steeringManifestTracker.stop();
    isActive = false;
    currentPathwayPriority = null;
    onStop();
  }

  private void performPathwayClones(ImmutableList<SteeringManifest.PathwayClone> pathwayClones) {
    for (SteeringManifest.PathwayClone pathwayClone : pathwayClones) {
      if (!isPathwayAvailable(pathwayClone.baseId) || isPathwayAvailable(pathwayClone.id)) {
        continue;
      }
      performPathwayClone(pathwayClone);
    }
  }

  private class SteeringManifestTrackerCallback implements SteeringManifestTracker.Callback {

    @Override
    public ImmutableMap<String, String> getSteeringQueryParameters() {
      checkState(isActive());
      return BaseContentSteeringTracker.this.getSteeringQueryParameters();
    }

    @Override
    public void onSteeringManifestUpdated(SteeringManifest steeringManifest) {
      checkState(isActive());
      performPathwayClones(steeringManifest.pathwayClones);
      currentPathwayPriority = steeringManifest.pathwayPriority;
      performPathwayEvaluation(currentPathwayPriority);
    }

    @Override
    public void onSteeringManifestLoadError(IOException error, boolean canceled) {
      checkState(isActive());
      if (canceled && currentPathwayPriority == null) {
        stopInternal();
      }
    }
  }
}
