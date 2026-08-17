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

import android.content.ComponentName;
import android.content.Context;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaBrowser;
import androidx.media3.session.MediaLibraryService.MediaLibrarySession;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionToken;
import com.google.common.util.concurrent.ListenableFuture;

/** Code snippets for the Migration guide. */
@SuppressWarnings({
  "unused",
  "CheckReturnValue",
  "UnusedAnonymousClass",
  "PrivateConstructorForUtilityClass"
})
public final class MigrationGuide {

  private static MediaSession mediaSession = null;
  private static MediaLibrarySession mediaLibrarySession = null;

  public static void createMediaSession(Context context) {
    // [START create_media_session]
    ExoPlayer player = new ExoPlayer.Builder(context).build();
    mediaSession =
        new MediaSession.Builder(context, player).setCallback(new MySessionCallback()).build();
    // [END create_media_session]
  }

  public static void releaseMediaSession() {
    // [START release_media_session]
    if (mediaSession != null) {
      mediaSession.getPlayer().release();
      mediaSession.release();
      mediaSession = null;
    }
    // [END release_media_session]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void createMediaLibrarySession(Context context, ExoPlayer player) {
    // [START create_media_library_session]
    mediaLibrarySession =
        new MediaLibrarySession.Builder(context, player, new MySessionCallback()).build();
    // [END create_media_library_session]
  }

  public static void createMediaBrowser(Context context) {
    // [START create_media_browser]
    SessionToken sessionToken =
        new SessionToken(context, new ComponentName(context, "MusicService"));
    ListenableFuture<MediaBrowser> browserFuture =
        new MediaBrowser.Builder(context, sessionToken)
            .setListener(new BrowserListener())
            .buildAsync();
    // [END create_media_browser]
  }

  private static class MySessionCallback implements MediaLibrarySession.Callback {}

  private static class BrowserListener implements MediaBrowser.Listener {}

  private MigrationGuide() {}
}
