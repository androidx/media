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
import java.util.UUID;

/** Performs {@link ExoMediaDrm} key and provisioning requests. */
@UnstableApi
public interface MediaDrmCallback {

  /**
   * Executes a provisioning request.
   *
   * @param uuid The UUID of the content protection scheme.
   * @param request The request.
   * @return The response data.
   * @throws MediaDrmCallbackException If an error occurred executing the request.
   */
  byte[] executeProvisionRequest(UUID uuid, ProvisionRequest request)
      throws MediaDrmCallbackException;

  /**
   * Executes a key request.
   *
   * @param uuid The UUID of the content protection scheme.
   * @param request The request.
   * @return The response data.
   * @throws MediaDrmCallbackException If an error occurred executing the request.
   */
  byte[] executeKeyRequest(UUID uuid, KeyRequest request) throws MediaDrmCallbackException;

  /**
   * Get the {@link LoadEventInfo} for the last executed request.
   * <p></p>
   * Valid after a call to {@link #executeKeyRequest(UUID, KeyRequest)} or
   * {@link #executeProvisionRequest(UUID, ProvisionRequest)}, either contains
   * the load event info for that request or null if no load was performed or this
   * implementation does not support reporting load info
   *
   * @return the {@link LoadEventInfo} or null if no load was performed
   */
  @Nullable default LoadEventInfo getLastLoadEventInfo() { return null; }
}
