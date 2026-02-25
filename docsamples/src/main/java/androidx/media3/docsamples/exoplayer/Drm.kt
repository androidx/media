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
@file:Suppress("unused_parameter", "unused_variable", "unused", "CheckReturnValue")

package androidx.media3.docsamples.exoplayer

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C.CryptoType
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

// Snippets for Digital rights management.

object DrmKt {

  @OptIn(UnstableApi::class)
  private class CustomDrmSessionManager : DrmSessionManager {
    override fun setPlayer(playbackLooper: Looper, playerId: PlayerId) {}

    override fun acquireSession(
      eventDispatcher: DrmSessionEventListener.EventDispatcher?,
      format: Format,
    ): DrmSession? {
      return null
    }

    override fun getCryptoType(format: Format): @CryptoType Int {
      return 0
    }
  }

  @OptIn(UnstableApi::class)
  fun customDrmSessionManager(context: Context) {
    // [START custom_drm_session_manager]
    val customDrmSessionManager: DrmSessionManager = CustomDrmSessionManager()
    // Pass a drm session manager provider to the media source factory.
    val mediaSourceFactory =
      DefaultMediaSourceFactory(context).setDrmSessionManagerProvider { customDrmSessionManager }
    // [END custom_drm_session_manager]
  }
}
