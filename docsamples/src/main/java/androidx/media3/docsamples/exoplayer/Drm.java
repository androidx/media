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
package androidx.media3.docsamples.exoplayer;

import android.content.Context;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C.CryptoType;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSession;
import androidx.media3.exoplayer.drm.DrmSessionEventListener.EventDispatcher;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;

/** Snippets for Digital rights management. */
@SuppressWarnings({"unused", "CheckReturnValue", "PrivateConstructorForUtilityClass"})
public class Drm {

  @OptIn(markerClass = UnstableApi.class)
  private static final class CustomDrmSessionManager implements DrmSessionManager {

    @Override
    public void setPlayer(Looper playbackLooper, PlayerId playerId) {}

    @Nullable
    @Override
    public DrmSession acquireSession(@Nullable EventDispatcher eventDispatcher, Format format) {
      return null;
    }

    @Override
    public @CryptoType int getCryptoType(Format format) {
      return 0;
    }
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void customDrmSessionManager(Context context) {
    // [START custom_drm_session_manager]
    DrmSessionManager customDrmSessionManager = new CustomDrmSessionManager(/* ... */ );
    // Pass a drm session manager provider to the media source factory.
    MediaSource.Factory mediaSourceFactory =
        new DefaultMediaSourceFactory(context)
            .setDrmSessionManagerProvider(mediaItem -> customDrmSessionManager);
    // [END custom_drm_session_manager]
  }
}
