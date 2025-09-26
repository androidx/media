/*
 * Copyright (C) 2016 The Android Open Source Project
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

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.drm.ExoMediaDrm.KeyRequest;
import androidx.media3.exoplayer.drm.ExoMediaDrm.ProvisionRequest;
import androidx.media3.exoplayer.source.LoadEventInfo;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.UUID;

/** Performs {@link ExoMediaDrm} key and provisioning requests. */
@UnstableApi
public interface MediaDrmCallback {

  /** Response data from the {@link MediaDrmCallback} requests. */
  public final class Response {

    /** Builder for {@link Response} instances. */
    public static final class Builder {

      private final byte[] data;

      @Nullable private LoadEventInfo loadEventInfo;

      /** Constructs an instance. */
      public Builder(byte[] data) {
        this.data = data;
      }

      /** Sets the optional {@link LoadEventInfo} associated with this response. */
      @CanIgnoreReturnValue
      public Builder setLoadEventInfo(LoadEventInfo loadEventInfo) {
        this.loadEventInfo = loadEventInfo;
        return this;
      }

      /** Builds the response. */
      public Response build() {
        return new Response(this);
      }
    }

    /** The response from the license or provisioning server. */
    public final byte[] data;

    /** The optional load info associated with this response. */
    @Nullable public final LoadEventInfo loadEventInfo;

    /** Constructs an instance. */
    public Response(byte[] data) {
      this.data = data;
      this.loadEventInfo = null;
    }

    private Response(Builder builder) {
      this.data = builder.data;
      this.loadEventInfo = builder.loadEventInfo;
    }
  }

  /**
   * Executes a provisioning request.
   *
   * <p>The {@link LoadEventInfo} returned inside the {@link Response} will have the following
   * fields unset, and they must be updated by caller before the {@link LoadEventInfo} is used
   * elsewhere:
   *
   * <ul>
   *   <li>{@link LoadEventInfo#loadTaskId}
   *   <li>{@link LoadEventInfo#loadDurationMs}
   * </ul>
   *
   * @param uuid The UUID of the content protection scheme.
   * @param request The request.
   * @return The response data.
   * @throws MediaDrmCallbackException If an error occurred executing the request.
   */
  Response executeProvisionRequest(UUID uuid, ProvisionRequest request)
      throws MediaDrmCallbackException;

  /**
   * Executes a key request.
   *
   * <p>The {@link LoadEventInfo} returned inside the {@link Response} will have the following
   * fields unset, and they must be updated by caller before the {@link LoadEventInfo} is used
   * elsewhere:
   *
   * <ul>
   *   <li>{@link LoadEventInfo#loadTaskId}
   *   <li>{@link LoadEventInfo#loadDurationMs}
   * </ul>
   *
   * @param uuid The UUID of the content protection scheme.
   * @param request The request.
   * @return The response data.
   * @throws MediaDrmCallbackException If an error occurred executing the request.
   */
  Response executeKeyRequest(UUID uuid, KeyRequest request) throws MediaDrmCallbackException;
}
