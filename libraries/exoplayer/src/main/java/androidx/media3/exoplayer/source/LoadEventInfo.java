/*
 * Copyright (C) 2019 The Android Open Source Project
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
package androidx.media3.exoplayer.source;

import android.net.Uri;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** {@link MediaSource} load event information. */
@UnstableApi
public final class LoadEventInfo {

  /** Builder for {@link LoadEventInfo}. */
  public static final class Builder {
    private long loadTaskId;
    private DataSpec dataSpec;
    private long elapsedRealtimeMs;
    private Uri uri;
    @Nullable private String steeredPathwayId;
    private Map<String, List<String>> responseHeaders;
    private long loadDurationMs;
    private long bytesLoaded;

    /**
     * Creates a builder.
     *
     * @param loadTaskId See {@link LoadEventInfo#loadTaskId}.
     * @param dataSpec See {@link LoadEventInfo#dataSpec}.
     * @param elapsedRealtimeMs See {@link LoadEventInfo#elapsedRealtimeMs}.
     */
    public Builder(long loadTaskId, DataSpec dataSpec, long elapsedRealtimeMs) {
      this.loadTaskId = loadTaskId;
      this.dataSpec = dataSpec;
      this.uri = dataSpec.uri;
      this.elapsedRealtimeMs = elapsedRealtimeMs;
      this.responseHeaders = ImmutableMap.of();
    }

    /**
     * Creates a builder with the values from the given {@link LoadEventInfo} instance.
     *
     * @param loadEventInfo The {@link LoadEventInfo} to build upon.
     */
    private Builder(LoadEventInfo loadEventInfo) {
      this.loadTaskId = loadEventInfo.loadTaskId;
      this.dataSpec = loadEventInfo.dataSpec;
      this.uri = loadEventInfo.uri;
      this.responseHeaders = loadEventInfo.responseHeaders;
      this.elapsedRealtimeMs = loadEventInfo.elapsedRealtimeMs;
      this.loadDurationMs = loadEventInfo.loadDurationMs;
      this.bytesLoaded = loadEventInfo.bytesLoaded;
    }

    /**
     * Sets the {@link LoadEventInfo#loadTaskId}.
     *
     * @param loadTaskId See {@link LoadEventInfo#loadTaskId}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setLoadTaskId(long loadTaskId) {
      this.loadTaskId = loadTaskId;
      return this;
    }

    /**
     * Sets the {@link LoadEventInfo#dataSpec}.
     *
     * <p>This also sets the {@link LoadEventInfo#uri} to the {@link DataSpec#uri} of the given
     * {@link DataSpec}. If you need to set the {@link LoadEventInfo#uri} to a different value, call
     * {@link #setUri(Uri)} to override it.
     *
     * @param dataSpec See {@link LoadEventInfo#dataSpec}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setDataSpec(DataSpec dataSpec) {
      this.dataSpec = dataSpec;
      this.uri = dataSpec.uri;
      return this;
    }

    /**
     * Sets the {@link LoadEventInfo#elapsedRealtimeMs}.
     *
     * @param elapsedRealtimeMs See {@link LoadEventInfo#elapsedRealtimeMs}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setElapsedRealtimeMs(long elapsedRealtimeMs) {
      this.elapsedRealtimeMs = elapsedRealtimeMs;
      return this;
    }

    /**
     * Sets the {@link LoadEventInfo#uri}.
     *
     * @param uri See {@link LoadEventInfo#uri}. The default is {@code dataSpec.uri}, where {@link
     *     DataSpec} is the one passed to the builder constructor or {@link #setDataSpec(DataSpec)}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setUri(Uri uri) {
      this.uri = uri;
      return this;
    }

    /**
     * Sets the {@link LoadEventInfo#steeredPathwayId}, the default is {@code null}.
     *
     * @param steeredPathwayId See {@link LoadEventInfo#steeredPathwayId}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setSteeredPathwayId(@Nullable String steeredPathwayId) {
      this.steeredPathwayId = steeredPathwayId;
      return this;
    }

    /**
     * Sets the {@link LoadEventInfo#responseHeaders}.
     *
     * @param responseHeaders See {@link LoadEventInfo#responseHeaders}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setResponseHeaders(Map<String, List<String>> responseHeaders) {
      this.responseHeaders = responseHeaders;
      return this;
    }

    /**
     * Sets the {@link LoadEventInfo#loadDurationMs}.
     *
     * @param loadDurationMs See {@link LoadEventInfo#loadDurationMs}. The default is 0.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setLoadDurationMs(long loadDurationMs) {
      this.loadDurationMs = loadDurationMs;
      return this;
    }

    /**
     * Sets the {@link LoadEventInfo#bytesLoaded}.
     *
     * @param bytesLoaded See {@link LoadEventInfo#bytesLoaded}. The default is 0.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setBytesLoaded(long bytesLoaded) {
      this.bytesLoaded = bytesLoaded;
      return this;
    }

    /** Builds the {@link LoadEventInfo}. */
    public LoadEventInfo build() {
      return new LoadEventInfo(this);
    }
  }

  /** Used for the generation of unique ids. */
  private static final AtomicLong idSource = new AtomicLong();

  /** Returns an non-negative identifier which is unique to the JVM instance. */
  public static long getNewId() {
    return idSource.getAndIncrement();
  }

  /** Identifies the load task to which this event corresponds. */
  public final long loadTaskId;

  /** Defines the requested data. */
  public final DataSpec dataSpec;

  /**
   * The {@link Uri} from which data is being read. The uri will be identical to the one in {@link
   * #dataSpec}.uri unless redirection has occurred. If redirection has occurred, this is the uri
   * after redirection.
   */
  public final Uri uri;

  /**
   * The ID of the steered pathway from which data is being loaded, or {@code null} if not
   * applicable.
   */
  @Nullable public final String steeredPathwayId;

  /** The response headers associated with the load, or an empty map if unavailable. */
  public final Map<String, List<String>> responseHeaders;

  /** The value of {@link SystemClock#elapsedRealtime} at the time of the load event. */
  public final long elapsedRealtimeMs;

  /** The duration of the load up to the event time. */
  public final long loadDurationMs;

  /** The number of bytes that were loaded up to the event time. */
  public final long bytesLoaded;

  /**
   * @deprecated Use {@link Builder} instead.
   */
  @Deprecated
  public LoadEventInfo(long loadTaskId, DataSpec dataSpec, long elapsedRealtimeMs) {
    this(
        loadTaskId,
        dataSpec,
        dataSpec.uri,
        ImmutableMap.of(),
        elapsedRealtimeMs,
        /* loadDurationMs= */ 0,
        /* bytesLoaded= */ 0);
  }

  /**
   * @deprecated Use {@link Builder} instead.
   */
  @Deprecated
  public LoadEventInfo(
      long loadTaskId,
      DataSpec dataSpec,
      Uri uri,
      Map<String, List<String>> responseHeaders,
      long elapsedRealtimeMs,
      long loadDurationMs,
      long bytesLoaded) {
    this.loadTaskId = loadTaskId;
    this.dataSpec = dataSpec;
    this.uri = uri;
    this.steeredPathwayId = null;
    this.responseHeaders = responseHeaders;
    this.elapsedRealtimeMs = elapsedRealtimeMs;
    this.loadDurationMs = loadDurationMs;
    this.bytesLoaded = bytesLoaded;
  }

  /**
   * Creates a {@link LoadEventInfo} from a {@link Builder}.
   *
   * @param builder The builder.
   */
  private LoadEventInfo(Builder builder) {
    this.loadTaskId = builder.loadTaskId;
    this.dataSpec = builder.dataSpec;
    this.uri = builder.uri;
    this.steeredPathwayId = builder.steeredPathwayId;
    this.responseHeaders = builder.responseHeaders;
    this.elapsedRealtimeMs = builder.elapsedRealtimeMs;
    this.loadDurationMs = builder.loadDurationMs;
    this.bytesLoaded = builder.bytesLoaded;
  }

  /** Initializes a new {@link Builder} with the values from this instance. */
  public Builder buildUpon() {
    return new Builder(this);
  }

  /**
   * @deprecated Use {@link #buildUpon()} instead.
   */
  @Deprecated
  public LoadEventInfo copyWithTaskIdAndDurationMs(long loadTaskId, long loadDurationMs) {
    return buildUpon().setLoadTaskId(loadTaskId).setLoadDurationMs(loadDurationMs).build();
  }
}
