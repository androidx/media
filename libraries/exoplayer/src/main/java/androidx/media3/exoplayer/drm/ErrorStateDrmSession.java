/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.google.common.base.Preconditions.checkNotNull;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoConfig;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A {@link DrmSession} that's in a terminal error state. */
@UnstableApi
public final class ErrorStateDrmSession implements DrmSession {

  private final DrmSessionException error;

  public ErrorStateDrmSession(DrmSessionException error) {
    this.error = checkNotNull(error);
  }

  @Override
  public int getState() {
    return STATE_ERROR;
  }

  @Override
  public boolean playClearSamplesWithoutKeys() {
    return false;
  }

  @Override
  @Nullable
  public DrmSessionException getError() {
    return error;
  }

  @Override
  public final UUID getSchemeUuid() {
    return C.UUID_NIL;
  }

  @Override
  @Nullable
  public CryptoConfig getCryptoConfig() {
    return null;
  }

  @Override
  @Nullable
  public Map<String, String> queryKeyStatus() {
    return null;
  }

  @Override
  public byte @Nullable [] getOfflineLicenseKeySetId() {
    return null;
  }

  @Override
  public boolean requiresSecureDecoder(String mimeType) {
    return false;
  }

  @Override
  public void acquire(DrmSessionEventListener.@Nullable EventDispatcher eventDispatcher) {
    // Do nothing.
  }

  @Override
  public void release(DrmSessionEventListener.@Nullable EventDispatcher eventDispatcher) {
    // Do nothing.
  }
}
