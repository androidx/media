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
import android.net.Uri;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

/** Code snippets for live streaming. */
@SuppressWarnings({
  "unused",
  "CheckReturnValue",
  "UnusedAnonymousClass",
  "PrivateConstructorForUtilityClass",
  "ControlFlowWithEmptyBody"
})
public final class LiveStreaming {

  @OptIn(markerClass = UnstableApi.class)
  public static void setLiveConfiguration(Context context, Uri mediaUri) {
    // [START set_live_configuration]
    // Global settings.
    ExoPlayer player =
        new ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(context).setLiveTargetOffsetMs(5000))
            .build();

    // Per MediaItem settings.
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(mediaUri)
            .setLiveConfiguration(
                new MediaItem.LiveConfiguration.Builder().setMaxPlaybackSpeed(1.02f).build())
            .build();
    player.setMediaItem(mediaItem);
    // [END set_live_configuration]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void configureLivePlaybackSpeedControl(Context context) {
    // [START configure_live_playback_speed_control]
    ExoPlayer player =
        new ExoPlayer.Builder(context)
            .setLivePlaybackSpeedControl(
                new DefaultLivePlaybackSpeedControl.Builder()
                    .setFallbackMaxPlaybackSpeed(1.04f)
                    .build())
            .build();
    // [END configure_live_playback_speed_control]
  }

  public static void behindLiveWindowListener(Player player) {
    new Player.Listener() {
      // [START behind_live_window_listener]
      @Override
      public void onPlayerError(PlaybackException error) {
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
          // Re-initialize player at the live edge.
          player.seekToDefaultPosition();
          player.prepare();
        } else {
          // Handle other errors
        }
      }
      // [END behind_live_window_listener]
    };
  }
}
