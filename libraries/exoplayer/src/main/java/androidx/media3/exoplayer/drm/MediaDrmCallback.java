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

import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.drm.ExoMediaDrm.KeyRequest;
import androidx.media3.exoplayer.drm.ExoMediaDrm.ProvisionRequest;
import androidx.media3.exoplayer.source.LoadEventInfo;
import java.util.UUID;

/** Performs {@link ExoMediaDrm} key and provisioning requests. */
@UnstableApi
public interface MediaDrmCallback {

  /** Response data from the {@link MediaDrmCallback} requests. */
  final class Response {

    /** The response from the license or provisioning server. */
    public final byte[] data;

    /** Information about the loading of {@link #data}. */
    public final LoadEventInfo loadEventInfo;

    /** Constructs an instance. */
    public Response(byte[] data, LoadEventInfo loadEventInfo) {
      this.data = data;
      this.loadEventInfo = loadEventInfo;
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
   * @return A {@link Response} that holds the response payload, and LoadEventInfo
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
   * @return A {@link Response} that holds the response payload, and LoadEventInfo
   * @throws MediaDrmCallbackException If an error occurred executing the request.
   */
  Response executeKeyRequest(UUID uuid, KeyRequest request) throws MediaDrmCallbackException;
}
