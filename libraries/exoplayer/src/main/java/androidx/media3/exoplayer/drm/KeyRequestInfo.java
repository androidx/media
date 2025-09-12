/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.exoplayer.drm;

import static com.google.common.base.Preconditions.checkState;

import androidx.annotation.Nullable;
import androidx.media3.common.DrmInitData.SchemeData;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.source.LoadEventInfo;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Information related to a completed DRM key request operation. */
// TODO: #1001 - Add sessionId field.
@UnstableApi
public final class KeyRequestInfo {

  /** Builder for {@link KeyRequestInfo} instances. */
  public static final class Builder {
    private final ImmutableList.Builder<LoadEventInfo> loadEventInfos;
    private @MonotonicNonNull ImmutableList<SchemeData> schemeDatas;

    /** Constructs an instance. */
    public Builder() {
      loadEventInfos = ImmutableList.builder();
    }

    /** Set the {@link SchemeData} instances associated with the key request. */
    @CanIgnoreReturnValue
    public Builder setSchemeDatas(List<SchemeData> schemeDatas) {
      this.schemeDatas = ImmutableList.copyOf(schemeDatas);
      return this;
    }

    /**
     * Adds info for a load associated with this key request. May be called again to add info for
     * any retry requests. At least one load info must be provided.
     */
    @CanIgnoreReturnValue
    public Builder addLoadInfo(LoadEventInfo loadEventInfo) {
      this.loadEventInfos.add(loadEventInfo);
      return this;
    }

    /** Builds a {@link KeyRequestInfo} instance. */
    public KeyRequestInfo build() {
      return new KeyRequestInfo(this);
    }
  }

  /**
   * The {@link LoadEventInfo} instances for the requests used to load the key. Guaranteed to have
   * at least one entry (representing the first request), followed by entries for any retries needed
   * to load the key.
   */
  public final ImmutableList<LoadEventInfo> loadInfos;

  /**
   * The DRM {@link SchemeData} that identifies the loaded key, or null if this session uses offline
   * keys.
   */
  @Nullable public final ImmutableList<SchemeData> schemeDatas;

  private KeyRequestInfo(Builder builder) {
    loadInfos = builder.loadEventInfos.build();
    checkState(!loadInfos.isEmpty());
    schemeDatas = builder.schemeDatas;
  }
}
