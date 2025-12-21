/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.exoplayer.upstream.contentsteering;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.UriUtil;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.upstream.Loader;
import androidx.media3.exoplayer.upstream.ParsingLoadable;
import androidx.media3.exoplayer.util.ReleasableExecutor;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Tracks the steering manifests. */
@UnstableApi
public final class SteeringManifestTracker {

  /** A callback to be notified of {@link SteeringManifestTracker} events. */
  public interface Callback {

    /**
     * Called by the {@link SteeringManifestTracker} when it requires the steering query parameters
     * to build the url for loading the steering manifest.
     */
    ImmutableMap<String, String> getSteeringQueryParameters();

    /**
     * Called by the {@link SteeringManifestTracker} when the steering manifest is updated.
     *
     * @param steeringManifest The updated {@link SteeringManifest}.
     */
    void onSteeringManifestUpdated(SteeringManifest steeringManifest);
  }

  @VisibleForTesting
  /* package */ static final long FALLBACK_DELAY_UNTIL_NEXT_LOAD_MS = 300_000; // 5 mins.

  private static final String TAG = "SteeringManifestTracker";
  private static final String RETRY_AFTER_HEADER = "Retry-After";

  private final DataSource.Factory dataSourceFactory;
  @Nullable private final Supplier<ReleasableExecutor> downloadExecutorSupplier;
  private final Clock clock;
  private final SteeringManifestLoaderCallback steeringManifestLoaderCallback;

  @Nullable private Uri steeringManifestUrl;
  @Nullable private Callback callback;
  @Nullable private MediaSourceEventListener.EventDispatcher eventDispatcher;
  @Nullable private SteeringManifest steeringManifest;
  @Nullable private HandlerWrapper steeringManifestReloadHandler;
  @Nullable private Loader steeringManifestLoader;
  private boolean hasStarted;

  /**
   * Creates an instance.
   *
   * @param dataSourceFactory The {@link DataSource.Factory} to use for steering manifest loading.
   * @param downloadExecutorSupplier A supplier for a {@link ReleasableExecutor} that is used for
   *     loading the steering manifest.
   */
  public SteeringManifestTracker(
      DataSource.Factory dataSourceFactory,
      @Nullable Supplier<ReleasableExecutor> downloadExecutorSupplier) {
    this(dataSourceFactory, downloadExecutorSupplier, Clock.DEFAULT);
  }

  /**
   * Creates an instance.
   *
   * @param dataSourceFactory The {@link DataSource.Factory} to use for steering manifest loading.
   * @param downloadExecutorSupplier A supplier for a {@link ReleasableExecutor} that is used for
   *     loading the steering manifest.
   * @param clock The {@link Clock} to schedule handler messages.
   */
  /* package */ SteeringManifestTracker(
      DataSource.Factory dataSourceFactory,
      @Nullable Supplier<ReleasableExecutor> downloadExecutorSupplier,
      Clock clock) {
    this.dataSourceFactory = dataSourceFactory;
    this.downloadExecutorSupplier = downloadExecutorSupplier;
    this.clock = clock;
    this.steeringManifestLoaderCallback = new SteeringManifestLoaderCallback();
  }

  /**
   * Starts the {@link SteeringManifestTracker}.
   *
   * @param initialSteeringManifestUrl The initial steering manifest url from the content
   *     description (an HLS multivariant playlist or a DASH MPD).
   * @param callback A {@link Callback}.
   * @param eventDispatcher A dispatcher to notify of events.
   */
  public void start(
      Uri initialSteeringManifestUrl,
      Callback callback,
      MediaSourceEventListener.EventDispatcher eventDispatcher) {
    this.steeringManifestUrl = initialSteeringManifestUrl;
    this.callback = callback;
    this.eventDispatcher = eventDispatcher;
    this.steeringManifestReloadHandler =
        clock.createHandler(Util.getCurrentOrMainLooper(), /* callback= */ null);
    this.steeringManifestLoader =
        downloadExecutorSupplier != null
            ? new Loader(downloadExecutorSupplier.get())
            : new Loader("SteeringManifestTracker");
    this.hasStarted = true;
    loadSteeringManifestImmediately();
  }

  /** Stops the {@link SteeringManifestTracker}. */
  public void stop() {
    steeringManifest = null;
    if (steeringManifestLoader != null) {
      steeringManifestLoader.release();
      steeringManifestLoader = null;
    }
    if (steeringManifestReloadHandler != null) {
      steeringManifestReloadHandler.removeCallbacksAndMessages(/* token= */ null);
      steeringManifestReloadHandler = null;
    }
    callback = null;
    eventDispatcher = null;
    hasStarted = false;
  }

  private void loadSteeringManifestImmediately() {
    checkState(hasStarted);
    Uri.Builder steeringManifestUrlBuilder = checkNotNull(steeringManifestUrl).buildUpon();
    ImmutableMap<String, String> steeringQueryParameters =
        checkNotNull(callback).getSteeringQueryParameters();
    for (Map.Entry<String, String> entry : steeringQueryParameters.entrySet()) {
      steeringManifestUrlBuilder.appendQueryParameter(entry.getKey(), entry.getValue());
    }
    DataSpec dataSpec =
        new DataSpec.Builder().setUri(checkNotNull(steeringManifestUrlBuilder.build())).build();
    ParsingLoadable<SteeringManifest> steeringManifestLoadable =
        new ParsingLoadable<>(
            dataSourceFactory.createDataSource(),
            dataSpec,
            C.DATA_TYPE_STEERING_MANIFEST,
            new SteeringManifestParser());
    checkNotNull(steeringManifestLoader)
        .startLoading(
            steeringManifestLoadable,
            /* callback= */ steeringManifestLoaderCallback,
            /* defaultMinRetryCount= */ 0);
  }

  private static Uri getSteeringManifestUrl(
      Uri previousSteeringManifestUrl, @Nullable Uri reloadUri) {
    if (reloadUri == null) {
      // The reloadUri is null, then we continue using the previousSteeringManifestUrl.
      return previousSteeringManifestUrl;
    }
    if (UriUtil.isAbsolute(reloadUri.toString())) {
      // The reloadUri is absolute, then we use it directly.
      return reloadUri;
    }
    // The reloadUri is relative, then we use the relative resolution of it with respect to the
    // previousSteeringManifestUrl.
    return UriUtil.resolveToUri(previousSteeringManifestUrl.toString(), reloadUri.toString());
  }

  private static LoadEventInfo buildLoadEventInfo(
      ParsingLoadable<SteeringManifest> loadable, long elapsedRealtimeMs, long loadDurationMs) {
    return new LoadEventInfo(
        loadable.loadTaskId,
        loadable.dataSpec,
        loadable.getUri(),
        loadable.getResponseHeaders(),
        elapsedRealtimeMs,
        loadDurationMs,
        loadable.bytesLoaded());
  }

  private class SteeringManifestLoaderCallback
      implements Loader.Callback<ParsingLoadable<SteeringManifest>> {

    @Override
    public void onLoadStarted(
        ParsingLoadable<SteeringManifest> loadable,
        long elapsedRealtimeMs,
        long loadDurationMs,
        int retryCount) {
      if (!hasStarted) {
        return;
      }
      LoadEventInfo loadEventInfo = buildLoadEventInfo(loadable, elapsedRealtimeMs, loadDurationMs);
      checkNotNull(eventDispatcher)
          .loadStarted(loadEventInfo, C.DATA_TYPE_STEERING_MANIFEST, retryCount);
    }

    @Override
    public void onLoadCompleted(
        ParsingLoadable<SteeringManifest> loadable, long elapsedRealtimeMs, long loadDurationMs) {
      if (!hasStarted) {
        return;
      }
      SteeringManifest newSteeringManifest = checkNotNull(loadable.getResult());
      steeringManifest = newSteeringManifest;
      checkNotNull(callback).onSteeringManifestUpdated(newSteeringManifest);
      steeringManifestUrl =
          getSteeringManifestUrl(checkNotNull(steeringManifestUrl), newSteeringManifest.reloadUri);
      long delayUntilNextLoadMs =
          newSteeringManifest.timeToLiveMs != C.TIME_UNSET
              ? newSteeringManifest.timeToLiveMs
              : FALLBACK_DELAY_UNTIL_NEXT_LOAD_MS;
      checkNotNull(steeringManifestReloadHandler)
          .postDelayed(
              SteeringManifestTracker.this::loadSteeringManifestImmediately, delayUntilNextLoadMs);
      LoadEventInfo loadEventInfo = buildLoadEventInfo(loadable, elapsedRealtimeMs, loadDurationMs);
      checkNotNull(eventDispatcher).loadCompleted(loadEventInfo, C.DATA_TYPE_STEERING_MANIFEST);
    }

    @Override
    public void onLoadCanceled(
        ParsingLoadable<SteeringManifest> loadable,
        long elapsedRealtimeMs,
        long loadDurationMs,
        boolean released) {
      if (!hasStarted) {
        return;
      }
      LoadEventInfo loadEventInfo = buildLoadEventInfo(loadable, elapsedRealtimeMs, loadDurationMs);
      checkNotNull(eventDispatcher).loadCanceled(loadEventInfo, C.DATA_TYPE_STEERING_MANIFEST);
    }

    @Override
    public Loader.LoadErrorAction onLoadError(
        ParsingLoadable<SteeringManifest> loadable,
        long elapsedRealtimeMs,
        long loadDurationMs,
        IOException error,
        int errorCount) {
      if (!hasStarted) {
        return Loader.DONT_RETRY;
      }
      int responseCode = Integer.MAX_VALUE;
      if (error instanceof HttpDataSource.InvalidResponseCodeException) {
        responseCode = ((HttpDataSource.InvalidResponseCodeException) error).responseCode;
      }
      // See https://datatracker.ietf.org/doc/html/draft-pantos-content-steering-01#section-7
      // (sub-term 7).
      long delayUntilNextLoadMs =
          FALLBACK_DELAY_UNTIL_NEXT_LOAD_MS; // Use fallback TTL unless the below cases.
      if (responseCode == 410) {
        // If HTTP 410 Gone is in response, we will not reload for the remainder of
        // the session.
        delayUntilNextLoadMs = C.TIME_UNSET;
        checkNotNull(steeringManifestLoader).release();
        checkNotNull(steeringManifestReloadHandler).removeCallbacksAndMessages(/* token= */ null);
      } else if (responseCode == 429) {
        // If HTTP 429 Too Many Requests with a Retry-After header is in response, we will wait
        // for the specified time until reload.
        @Nullable List<String> retryAfter = loadable.getResponseHeaders().get(RETRY_AFTER_HEADER);
        if (retryAfter != null) {
          try {
            delayUntilNextLoadMs = Long.parseLong(retryAfter.get(0)) * 1000;
          } catch (NumberFormatException e) {
            Log.w(TAG, "Retry-After header string doesn't contain a parsable long");
          }
        }
      } else if (steeringManifest != null && steeringManifest.timeToLiveMs != C.TIME_UNSET) {
        // If there has been a steeringManifest with a TTL, we will wait for that previously
        // specified TTL until reload.
        delayUntilNextLoadMs = steeringManifest.timeToLiveMs;
      }
      if (delayUntilNextLoadMs != C.TIME_UNSET) {
        checkNotNull(steeringManifestReloadHandler)
            .postDelayed(
                SteeringManifestTracker.this::loadSteeringManifestImmediately,
                delayUntilNextLoadMs);
      }
      LoadEventInfo loadEventInfo = buildLoadEventInfo(loadable, elapsedRealtimeMs, loadDurationMs);
      checkNotNull(eventDispatcher)
          .loadError(
              loadEventInfo,
              C.DATA_TYPE_STEERING_MANIFEST,
              error,
              /* wasCanceled= */ (delayUntilNextLoadMs == C.TIME_UNSET));
      // We do not retry here but will still reload with the latest parameters after the delay.
      return Loader.DONT_RETRY;
    }
  }
}
