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
package androidx.media3.docsamples.cast;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Menu;
import android.view.MenuItem;
import androidx.annotation.OptIn;
import androidx.media3.cast.CastPlayer;
import androidx.media3.cast.MediaRouteButtonFactory;
import androidx.media3.cast.MediaRouteButtonViewProvider;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.docsamples.R;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaController;
import androidx.media3.session.MediaSession;
import androidx.media3.ui.PlayerView;
import androidx.mediarouter.app.MediaRouteButton;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.Executor;

/** Snippets for create-castplayer.md. */
@SuppressWarnings({"unused", "CheckReturnValue", "EffectivelyPrivate", "ControlFlowWithEmptyBody"})
@OptIn(markerClass = UnstableApi.class)
public class CreateCastPlayer {

  private CreateCastPlayer() {}

  private static class CreatePlayerExample extends Service {
    private final Context context = null;
    private MediaSession mediaSession = null;

    // [START create_player]
    @Override
    public void onCreate() {
      super.onCreate();

      ExoPlayer exoPlayer = new ExoPlayer.Builder(context).build();
      CastPlayer castPlayer = new CastPlayer.Builder(context).setLocalPlayer(exoPlayer).build();

      mediaSession =
          new MediaSession.Builder(/* context= */ context, /* player= */ castPlayer).build();
    }

    // [END create_player]

    @Override
    public IBinder onBind(Intent intent) {
      return null;
    }
  }

  private static class PlayerMediaButtonExample extends Activity {
    private PlayerView playerView;
    private MediaController mediaController;

    // [START player_media_button]
    @Override
    public void onStart() {
      super.onStart();

      playerView.setPlayer(mediaController);
      playerView.setMediaRouteButtonViewProvider(new MediaRouteButtonViewProvider());
    }
    // [END player_media_button]
  }

  private static class ActionbarMediaButtonExample extends Activity {
    private Context context;
    private Executor executor;

    // [START actionbar_media_button]
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
      // ...
      getMenuInflater().inflate(R.menu.sample_media_route_button_menu, menu);
      ListenableFuture<MenuItem> menuItemFuture =
          MediaRouteButtonFactory.setUpMediaRouteButton(context, menu, R.id.media_route_menu_item);
      Futures.addCallback(
          menuItemFuture,
          new FutureCallback<MenuItem>() {
            @Override
            public void onSuccess(MenuItem menuItem) {
              // Do something with the menu item.
            }

            @Override
            public void onFailure(Throwable t) {
              // Handle the failure.
            }
          },
          executor);
      // ...
      return true;
    }
    // [END actionbar_media_button]
  }

  private static class ViewMediaButtonExample extends Activity {
    private Context context;

    // [START view_media_button]
    @Override
    public void onCreate(Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);
      // ...
      MediaRouteButton button = findViewById(R.id.media_route_button);
      ListenableFuture<Void> setUpFuture =
          MediaRouteButtonFactory.setUpMediaRouteButton(context, button);
    }
    // [END view_media_button]
  }

  private static class ActivityListenerExample extends Activity {
    private MediaController mediaController;

    // [START activity_listener]
    private final Player.Listener playerListener =
        new Player.Listener() {
          @Override
          public void onDeviceInfoChanged(DeviceInfo deviceInfo) {
            if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL) {
              // Add UI changes for local playback.
            } else if (deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
              // Add UI changes for remote playback.
            }
          }
        };

    @Override
    protected void onStart() {
      super.onStart();
      mediaController.addListener(playerListener);
    }

    @Override
    protected void onStop() {
      super.onStop();
      mediaController.removeListener(playerListener);
    }
    // [END activity_listener]
  }
}
