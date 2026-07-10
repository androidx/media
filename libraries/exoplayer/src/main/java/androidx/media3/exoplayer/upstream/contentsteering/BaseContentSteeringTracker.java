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
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.util.ReleasableExecutor;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/**
 * Abstract base class for {@link ContentSteeringTracker} implementations.
 *
 * <p>It provides common state management for pathway priority updates, pathway evaluation, and
 * pathway exclusion logic.
 */
@UnstableApi
public abstract class BaseContentSteeringTracker implements ContentSteeringTracker {

  private final SteeringManifestTracker steeringManifestTracker;
  @Nullable private final ContentSteeringTracker.Callback callback;
  private final Set<String> availablePathwayIds;
  private final Set<String> excludedPathwayIds;
  private final HandlerWrapper handler;

  @Nullable private String currentPathwayId;
  @Nullable private ImmutableList<String> currentPathwayPriority;

  /**
   * Creates a {@link BaseContentSteeringTracker}.
   *
   * @param dataSourceFactory The {@link DataSource.Factory} to load steering manifests.
   * @param downloadExecutorSupplier A supplier for a {@link ReleasableExecutor} to download
   *     steering manifests.
   * @param callback A {@link ContentSteeringTracker.Callback} to receive events.
   * @param clock The {@link Clock}.
   * @param availablePathwayIds The set of initially available pathway IDs.
   */
  protected BaseContentSteeringTracker(
      DataSource.Factory dataSourceFactory,
      @Nullable Supplier<ReleasableExecutor> downloadExecutorSupplier,
      @Nullable ContentSteeringTracker.Callback callback,
      Clock clock,
      Set<String> availablePathwayIds) {
    this.steeringManifestTracker =
        new SteeringManifestTracker(dataSourceFactory, downloadExecutorSupplier);
    this.callback = callback;
    this.availablePathwayIds = new HashSet<>(availablePathwayIds);
    this.excludedPathwayIds = new HashSet<>();
    this.handler = clock.createHandler(Util.getCurrentOrMainLooper(), /* callback= */ null);
  }

  @Override
  public final void start(
      Uri initialSteeringManifestUri,
      String initialPathwayId,
      MediaSourceEventListener.EventDispatcher eventDispatcher) {
    checkState(availablePathwayIds.contains(initialPathwayId));
    currentPathwayId = initialPathwayId;
    notifyOnCurrentPathwayUpdated(
        currentPathwayId,
        /* previousPathwayId= */ null,
        /* previousPathwayExcludeDurationMs= */ C.TIME_UNSET);
    steeringManifestTracker.start(
        initialSteeringManifestUri, new SteeringManifestTrackerCallback(), eventDispatcher);
  }

  @Override
  public final boolean excludeCurrentPathway(long excludeDurationMs) {
    if (isActive() && currentPathwayPriority != null) {
      String previousPathwayId = currentPathwayId;
      performPathwayEvaluationAndUpdate(
          /* previousPathwayIdExcludeDurationMs= */ excludeDurationMs);
      if (!Objects.equals(currentPathwayId, previousPathwayId)) {
        excludedPathwayIds.add(previousPathwayId);
        handler.postDelayed(() -> expireExclusion(previousPathwayId), excludeDurationMs);
        return true;
      }
    }
    return false;
  }

  @Override
  @EnsuresNonNullIf(result = true, expression = "currentPathwayId")
  public final boolean isActive() {
    return currentPathwayId != null;
  }

  @Override
  public final void stop() {
    stopInternal();
  }

  /** Returns the query parameters to include in the steering manifest request. */
  protected abstract ImmutableMap<String, String> getSteeringQueryParameters();

  /**
   * Performs a pathway clone defined in the steering manifest.
   *
   * @param pathwayClone The {@link SteeringManifest.PathwayClone} to perform.
   * @return A {@link Pair} where the first element is the list of new cloned URIs and the second
   *     element is the list of corresponding base URIs.
   */
  protected abstract Pair<ImmutableList<Uri>, ImmutableList<Uri>> performPathwayClone(
      SteeringManifest.PathwayClone pathwayClone);

  /** Returns the current pathway ID, or {@code null} if the tracker is not active. */
  @Nullable
  protected final String getCurrentPathwayId() {
    return currentPathwayId;
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

  private void expireExclusion(String pathwayId) {
    checkState(isActive());
    excludedPathwayIds.remove(pathwayId);
    if (currentPathwayPriority != null) {
      performPathwayEvaluationAndUpdate(/* previousPathwayIdExcludeDurationMs= */ C.TIME_UNSET);
    }
  }

  private void stopInternal() {
    steeringManifestTracker.stop();
    handler.removeCallbacksAndMessages(null);
    currentPathwayId = null;
    currentPathwayPriority = null;
    excludedPathwayIds.clear();
  }

  private void performPathwayClones(ImmutableList<SteeringManifest.PathwayClone> pathwayClones) {
    for (SteeringManifest.PathwayClone pathwayClone : pathwayClones) {
      if (!availablePathwayIds.contains(pathwayClone.baseId)
          || availablePathwayIds.contains(pathwayClone.id)) {
        continue;
      }
      availablePathwayIds.add(pathwayClone.id);
      Pair<ImmutableList<Uri>, ImmutableList<Uri>> newAndBaseUris =
          performPathwayClone(pathwayClone);
      if (callback != null) {
        callback.onNewPathwayAvailable(
            pathwayClone.id, pathwayClone.baseId, newAndBaseUris.first, newAndBaseUris.second);
      }
    }
  }

  @RequiresNonNull("currentPathwayPriority")
  private void performPathwayEvaluationAndUpdate(long previousPathwayIdExcludeDurationMs) {
    String previousPathwayId = currentPathwayId;
    for (String pathwayId : currentPathwayPriority) {
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

  private void notifyOnCurrentPathwayUpdated(
      String currentPathwayId,
      @Nullable String previousPathwayId,
      long previousPathwayExcludeDurationMs) {
    if (callback != null) {
      callback.onCurrentPathwayUpdated(
          currentPathwayId, previousPathwayId, previousPathwayExcludeDurationMs);
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
      performPathwayEvaluationAndUpdate(/* previousPathwayIdExcludeDurationMs= */ C.TIME_UNSET);
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
