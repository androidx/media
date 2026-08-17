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

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

// Code snippets for the Migration guide.

object MigrationGuideKt {

  private var mediaSession: MediaSession? = null
  private var mediaLibrarySession: MediaLibrarySession? = null
  private var browser: MediaBrowser? = null

  fun createMediaSession(context: Context) {
    // [START create_media_session]
    val player = ExoPlayer.Builder(context).build()
    mediaSession = MediaSession.Builder(context, player).setCallback(MySessionCallback()).build()
    // [END create_media_session]
  }

  fun releaseMediaSession() {
    // [START release_media_session]
    mediaSession?.run {
      player.release()
      release()
      mediaSession = null
    }
    // [END release_media_session]
  }

  @OptIn(UnstableApi::class)
  fun createMediaLibrarySession(context: Context, player: ExoPlayer) {
    // [START create_media_library_session]
    mediaLibrarySession = MediaLibrarySession.Builder(context, player, MySessionCallback()).build()
    // [END create_media_library_session]
  }

  fun createMediaBrowser(context: Context, scope: CoroutineScope) {
    // [START create_media_browser]
    scope.launch {
      val sessionToken = SessionToken(context, ComponentName(context, "MusicService"))
      browser =
        MediaBrowser.Builder(context, sessionToken)
          .setListener(BrowserListener())
          .buildAsync()
          .await()
    }
    // [END create_media_browser]
  }

  private class MySessionCallback : MediaLibrarySession.Callback

  private class BrowserListener : MediaBrowser.Listener
}
