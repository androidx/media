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

  /**
   * Response data from the {@link MediaDrmCallback} requests.
   *
   * <p>Encapsulates the license server response data {@link #responseData} along with information
   * ({@link #loadEventInfo} about the network transfer (if any) that was issued to gather the
   * response.
   */
  public class KeyResponse {
    public final byte[] responseData;
    public final LoadEventInfo loadEventInfo;

    public KeyResponse(byte[] responseData, LoadEventInfo loadEventInfo) {
      this.responseData = responseData;
      this.loadEventInfo = loadEventInfo;
    }
  }

  /**
   * Executes a provisioning request.
   *
   * @param uuid The UUID of the content protection scheme.
   * @param request The request.
   * @return A {@link KeyResponse} that holds the response payload, and LoadEventInfo
   * @throws MediaDrmCallbackException If an error occurred executing the request.
   */
  KeyResponse executeProvisionRequest(UUID uuid, ProvisionRequest request)
      throws MediaDrmCallbackException;

  /**
   * Executes a key request.
   *
   * @param uuid The UUID of the content protection scheme.
   * @param request The request.
   * @return A {@link KeyResponse} that holds the response payload, and LoadEventInfo
   * @throws MediaDrmCallbackException If an error occurred executing the request.
   */
  KeyResponse executeKeyRequest(UUID uuid, KeyRequest request) throws MediaDrmCallbackException;
}
