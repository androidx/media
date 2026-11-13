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
package androidx.media3.session;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession.ControllerInfo;
import androidx.media3.session.legacy.MediaDescriptionCompat;
import androidx.media3.session.legacy.MediaSessionCompat;
import androidx.media3.session.legacy.MediaSessionManager.RemoteUserInfo;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/** Unit tests for {@link MediaSessionLegacyStub}. */
@RunWith(AndroidJUnit4.class)
public class MediaSessionLegacyStubTest {

  private static final String NOTIFICATION_LISTENER_PACKAGE = "com.example.notificationlistener";
  private static final int NOTIFICATION_LISTENER_PID = 4321;

  private final List<ControllerInfo> connectedControllers = new ArrayList<>();
  private final List<ControllerInfo> customCommandCallers = new ArrayList<>();
  private final AtomicReference<ControllerInfo> lastMediaButtonCaller = new AtomicReference<>();

  private Context context;
  private int notificationListenerUid;
  private ExoPlayer player;
  private MediaSession session;

  @Before
  public void setUp() {
    context = getApplicationContext();
    notificationListenerUid = Process.myUid() == 10101 ? 10111 : 10101;
    Settings.Secure.putString(
        context.getContentResolver(),
        "enabled_notification_listeners",
        new ComponentName(NOTIFICATION_LISTENER_PACKAGE, "ListenerService").flattenToString());
    player = new TestExoPlayerBuilder(context).build();
    session =
        new MediaSession.Builder(context, player)
            .setId("MediaSessionLegacyStubTest")
            .setCallback(
                new MediaSession.Callback() {
                  @Override
                  public ListenableFuture<MediaSession.ConnectionResult> onConnectAsync(
                      MediaSession session, ControllerInfo controller) {
                    connectedControllers.add(controller);
                    return immediateFuture(createAcceptedConnectionResult(controller));
                  }

                  @Override
                  public ListenableFuture<SessionResult> onCustomCommand(
                      MediaSession session,
                      ControllerInfo controller,
                      SessionCommand customCommand,
                      Bundle args) {
                    customCommandCallers.add(controller);
                    return immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
                  }

                  @Override
                  public boolean onMediaButtonEvent(
                      MediaSession session, ControllerInfo controllerInfo, Intent intent) {
                    lastMediaButtonCaller.set(controllerInfo);
                    return MediaSession.Callback.super.onMediaButtonEvent(
                        session, controllerInfo, intent);
                  }
                })
            .build();
  }

  @After
  public void tearDown() {
    session.release();
    player.release();
  }

  @Test
  @Config(minSdk = 33)
  public void onPlay_unresolvableEnabledNotificationListenerOnApi33_connectsAsTrustedAndVerified() {
    RemoteUserInfo caller =
        new RemoteUserInfo(
            NOTIFICATION_LISTENER_PACKAGE, NOTIFICATION_LISTENER_PID, notificationListenerUid);

    dispatchToLegacyStub(caller, MediaSessionLegacyStub::onPlay);

    assertThat(connectedControllers).hasSize(1);
    ControllerInfo controller = connectedControllers.get(0);
    assertThat(controller.getPackageName()).isEqualTo(NOTIFICATION_LISTENER_PACKAGE);
    assertThat(controller.isPackageNameVerified()).isTrue();
    assertThat(controller.isTrusted()).isTrue();
    assertThat(player.getPlayWhenReady()).isTrue();
  }

  @Test
  @Config(minSdk = 28, maxSdk = 32)
  public void
      onPlay_unresolvableEnabledNotificationListenerBelowApi33_connectsAsUntrustedAndUnverified() {
    RemoteUserInfo caller =
        new RemoteUserInfo(
            NOTIFICATION_LISTENER_PACKAGE, NOTIFICATION_LISTENER_PID, notificationListenerUid);

    dispatchToLegacyStub(caller, MediaSessionLegacyStub::onPlay);

    assertThat(connectedControllers).hasSize(1);
    ControllerInfo controller = connectedControllers.get(0);
    assertThat(controller.isPackageNameVerified()).isFalse();
    assertThat(controller.isTrusted()).isFalse();
  }

  @Test
  @Config(minSdk = 33)
  public void
      onCustomAction_unresolvableEnabledNotificationListenerOnApi33_connectsAsTrustedAndVerified() {
    RemoteUserInfo caller =
        new RemoteUserInfo(
            NOTIFICATION_LISTENER_PACKAGE, NOTIFICATION_LISTENER_PID, notificationListenerUid);

    dispatchToLegacyStub(
        caller, legacyStub -> legacyStub.onCustomAction("com.example.CUSTOM_ACTION", Bundle.EMPTY));

    assertThat(connectedControllers).hasSize(1);
    ControllerInfo controller = connectedControllers.get(0);
    assertThat(controller.isPackageNameVerified()).isTrue();
    assertThat(controller.isTrusted()).isTrue();
    assertThat(customCommandCallers).hasSize(1);
  }

  @Test
  @Config(minSdk = 33)
  public void
      onCommand_unresolvableEnabledNotificationListenerOnApi33_connectsAsUntrustedAndUnverified() {
    RemoteUserInfo caller =
        new RemoteUserInfo(
            NOTIFICATION_LISTENER_PACKAGE, NOTIFICATION_LISTENER_PID, notificationListenerUid);

    dispatchToLegacyStub(
        caller,
        legacyStub ->
            legacyStub.onCommand("com.example.CUSTOM_COMMAND", Bundle.EMPTY, /* cb= */ null));

    assertThat(connectedControllers).hasSize(1);
    ControllerInfo controller = connectedControllers.get(0);
    assertThat(controller.isPackageNameVerified()).isFalse();
    assertThat(controller.isTrusted()).isFalse();
    assertThat(customCommandCallers).isEmpty();
  }

  @Test
  @Config(minSdk = 33)
  public void
      onAddQueueItem_unresolvableEnabledNotificationListenerOnApi33_connectsAsUntrustedAndUnverified() {
    RemoteUserInfo caller =
        new RemoteUserInfo(
            NOTIFICATION_LISTENER_PACKAGE, NOTIFICATION_LISTENER_PID, notificationListenerUid);
    MediaDescriptionCompat description =
        new MediaDescriptionCompat.Builder().setMediaId("media_1").build();

    dispatchToLegacyStub(caller, legacyStub -> legacyStub.onAddQueueItem(description));

    assertThat(connectedControllers).hasSize(1);
    ControllerInfo controller = connectedControllers.get(0);
    assertThat(controller.isPackageNameVerified()).isFalse();
    assertThat(controller.isTrusted()).isFalse();
  }

  @Test
  @Config(minSdk = 33)
  public void
      onRemoveQueueItem_unresolvableEnabledNotificationListenerOnApi33_connectsAsUntrustedAndUnverified() {
    RemoteUserInfo caller =
        new RemoteUserInfo(
            NOTIFICATION_LISTENER_PACKAGE, NOTIFICATION_LISTENER_PID, notificationListenerUid);
    MediaDescriptionCompat description =
        new MediaDescriptionCompat.Builder().setMediaId("media_1").build();

    dispatchToLegacyStub(caller, legacyStub -> legacyStub.onRemoveQueueItem(description));

    assertThat(connectedControllers).hasSize(1);
    ControllerInfo controller = connectedControllers.get(0);
    assertThat(controller.isPackageNameVerified()).isFalse();
    assertThat(controller.isTrusted()).isFalse();
  }

  @Test
  @Config(minSdk = 33)
  public void onCommand_afterVerifiedOnPlayOnApi33_keepsTrustedAndVerifiedConnection() {
    RemoteUserInfo caller =
        new RemoteUserInfo(
            NOTIFICATION_LISTENER_PACKAGE, NOTIFICATION_LISTENER_PID, notificationListenerUid);
    dispatchToLegacyStub(caller, MediaSessionLegacyStub::onPlay);

    dispatchToLegacyStub(
        caller,
        legacyStub ->
            legacyStub.onCommand("com.example.CUSTOM_COMMAND", Bundle.EMPTY, /* cb= */ null));

    assertThat(connectedControllers).hasSize(1);
    assertThat(session.getConnectedControllers()).hasSize(1);
    assertThat(session.getConnectedControllers().get(0).isPackageNameVerified()).isTrue();
    assertThat(session.getConnectedControllers().get(0).isTrusted()).isTrue();
    assertThat(customCommandCallers).hasSize(1);
    assertThat(customCommandCallers.get(0).isPackageNameVerified()).isTrue();
    assertThat(customCommandCallers.get(0).isTrusted()).isTrue();
  }

  @Test
  @Config(minSdk = 33)
  public void onMediaButtonEvent_mediaSessionKeyOnApi33_marksTrustedAndPackageNameVerified() {
    RemoteUserInfo caller =
        new RemoteUserInfo(
            NOTIFICATION_LISTENER_PACKAGE, NOTIFICATION_LISTENER_PID, notificationListenerUid);
    Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
    mediaButtonIntent.putExtra(
        Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));

    dispatchMediaButtonEvent(caller, mediaButtonIntent);

    ControllerInfo controller = lastMediaButtonCaller.get();
    assertThat(controller).isNotNull();
    assertThat(controller.isPackageNameVerified()).isTrue();
    assertThat(controller.isTrusted()).isTrue();
  }

  @Test
  @Config(minSdk = 33)
  public void
      onMediaButtonEvent_nonMediaSessionKeyOnApi33_marksUntrustedAndPackageNameUnverified() {
    RemoteUserInfo caller =
        new RemoteUserInfo(
            NOTIFICATION_LISTENER_PACKAGE, NOTIFICATION_LISTENER_PID, notificationListenerUid);
    Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
    mediaButtonIntent.putExtra(
        Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP));

    dispatchMediaButtonEvent(caller, mediaButtonIntent);

    ControllerInfo controller = lastMediaButtonCaller.get();
    assertThat(controller).isNotNull();
    assertThat(controller.isPackageNameVerified()).isFalse();
    assertThat(controller.isTrusted()).isFalse();
  }

  @Test
  @Config(minSdk = 28, maxSdk = 32)
  public void
      onMediaButtonEvent_mediaSessionKeyBelowApi33_marksUntrustedAndPackageNameUnverified() {
    RemoteUserInfo caller =
        new RemoteUserInfo(
            NOTIFICATION_LISTENER_PACKAGE, NOTIFICATION_LISTENER_PID, notificationListenerUid);
    Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
    mediaButtonIntent.putExtra(
        Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY));

    dispatchMediaButtonEvent(caller, mediaButtonIntent);

    ControllerInfo controller = lastMediaButtonCaller.get();
    assertThat(controller).isNotNull();
    assertThat(controller.isPackageNameVerified()).isFalse();
    assertThat(controller.isTrusted()).isFalse();
  }

  @Test
  @Config(minSdk = 33)
  public void
      onMediaButtonEvent_playPauseKeyOnApi33_connectsAsTrustedAndVerifiedAfterDoubleTapTimeout() {
    RemoteUserInfo caller =
        new RemoteUserInfo(
            NOTIFICATION_LISTENER_PACKAGE, NOTIFICATION_LISTENER_PID, notificationListenerUid);
    Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
    mediaButtonIntent.putExtra(
        Intent.EXTRA_KEY_EVENT,
        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));

    dispatchMediaButtonEvent(caller, mediaButtonIntent);
    ShadowLooper.idleMainLooper(ViewConfiguration.getDoubleTapTimeout(), MILLISECONDS);

    assertThat(connectedControllers).hasSize(1);
    ControllerInfo controller = connectedControllers.get(0);
    assertThat(controller.isPackageNameVerified()).isTrue();
    assertThat(controller.isTrusted()).isTrue();
    assertThat(player.getPlayWhenReady()).isTrue();
  }

  @Test
  @Config(minSdk = 28, maxSdk = 32)
  public void
      onMediaButtonEvent_playPauseKeyBelowApi33_connectsAsUntrustedAndUnverifiedAfterDoubleTapTimeout() {
    RemoteUserInfo caller =
        new RemoteUserInfo(
            NOTIFICATION_LISTENER_PACKAGE, NOTIFICATION_LISTENER_PID, notificationListenerUid);
    Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
    mediaButtonIntent.putExtra(
        Intent.EXTRA_KEY_EVENT,
        new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));

    dispatchMediaButtonEvent(caller, mediaButtonIntent);
    ShadowLooper.idleMainLooper(ViewConfiguration.getDoubleTapTimeout(), MILLISECONDS);

    assertThat(connectedControllers).hasSize(1);
    ControllerInfo controller = connectedControllers.get(0);
    assertThat(controller.isPackageNameVerified()).isFalse();
    assertThat(controller.isTrusted()).isFalse();
    assertThat(player.getPlayWhenReady()).isFalse();
  }

  @Test
  @Config(minSdk = 33)
  public void onCommand_installedEnabledNotificationListenerOnApi33_connectsAsTrustedAndVerified() {
    installPackage(NOTIFICATION_LISTENER_PACKAGE, notificationListenerUid);
    RemoteUserInfo caller =
        new RemoteUserInfo(
            NOTIFICATION_LISTENER_PACKAGE, NOTIFICATION_LISTENER_PID, notificationListenerUid);

    dispatchToLegacyStub(
        caller,
        legacyStub ->
            legacyStub.onCommand("com.example.CUSTOM_COMMAND", Bundle.EMPTY, /* cb= */ null));

    assertThat(connectedControllers).hasSize(1);
    ControllerInfo controller = connectedControllers.get(0);
    assertThat(controller.isPackageNameVerified()).isTrue();
    assertThat(controller.isTrusted()).isTrue();
    assertThat(customCommandCallers).hasSize(1);
  }

  @Test
  @Config(minSdk = 28, maxSdk = 32)
  public void onPlay_installedEnabledNotificationListenerBelowApi33_connectsAsTrustedAndVerified() {
    installPackage(NOTIFICATION_LISTENER_PACKAGE, notificationListenerUid);
    RemoteUserInfo caller =
        new RemoteUserInfo(
            NOTIFICATION_LISTENER_PACKAGE, NOTIFICATION_LISTENER_PID, notificationListenerUid);

    dispatchToLegacyStub(caller, MediaSessionLegacyStub::onPlay);

    assertThat(connectedControllers).hasSize(1);
    ControllerInfo controller = connectedControllers.get(0);
    assertThat(controller.isPackageNameVerified()).isTrue();
    assertThat(controller.isTrusted()).isTrue();
    assertThat(player.getPlayWhenReady()).isTrue();
  }

  @Test
  @Config(minSdk = 33)
  public void
      onMediaButtonEvent_nonMediaSessionKeyFromInstalledEnabledNotificationListenerOnApi33_marksTrustedAndVerified() {
    installPackage(NOTIFICATION_LISTENER_PACKAGE, notificationListenerUid);
    RemoteUserInfo caller =
        new RemoteUserInfo(
            NOTIFICATION_LISTENER_PACKAGE, NOTIFICATION_LISTENER_PID, notificationListenerUid);
    Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
    mediaButtonIntent.putExtra(
        Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP));

    dispatchMediaButtonEvent(caller, mediaButtonIntent);

    ControllerInfo controller = lastMediaButtonCaller.get();
    assertThat(controller).isNotNull();
    assertThat(controller.isPackageNameVerified()).isTrue();
    assertThat(controller.isTrusted()).isTrue();
  }

  @Test
  @Config(minSdk = 33)
  public void
      onPlay_installedUntrustedUidSpoofingNotificationListenerOnApi33_connectsAsUntrustedAndUnverified() {
    int untrustedUid = Process.myUid() == 10303 ? 10304 : 10303;
    installPackage("com.example.untrusted", untrustedUid);
    RemoteUserInfo caller =
        new RemoteUserInfo(NOTIFICATION_LISTENER_PACKAGE, NOTIFICATION_LISTENER_PID, untrustedUid);

    dispatchToLegacyStub(caller, MediaSessionLegacyStub::onPlay);

    assertThat(connectedControllers).hasSize(1);
    ControllerInfo controller = connectedControllers.get(0);
    assertThat(controller.isPackageNameVerified()).isFalse();
    assertThat(controller.isTrusted()).isFalse();
    assertThat(player.getPlayWhenReady()).isFalse();
  }

  @Test
  @Config(minSdk = 33)
  public void
      onPlay_unresolvableUidSpoofingInstalledNotificationListenerOnApi33_connectsAsUntrustedAndUnverified() {
    int untrustedInvisibleUid = Process.myUid() == 10303 ? 10304 : 10303;
    installPackage(NOTIFICATION_LISTENER_PACKAGE, notificationListenerUid);
    RemoteUserInfo caller =
        new RemoteUserInfo(
            NOTIFICATION_LISTENER_PACKAGE, NOTIFICATION_LISTENER_PID, untrustedInvisibleUid);

    dispatchToLegacyStub(caller, MediaSessionLegacyStub::onPlay);

    assertThat(connectedControllers).hasSize(1);
    ControllerInfo controller = connectedControllers.get(0);
    assertThat(controller.isPackageNameVerified()).isFalse();
    assertThat(controller.isTrusted()).isFalse();
    assertThat(player.getPlayWhenReady()).isFalse();
  }

  @Test
  @Config(minSdk = 33)
  public void
      onPlay_enabledNotificationListenerInDifferentUserWhenInstalledInCurrentUserOnApi33_connectsAsUntrustedAndVerified() {
    int otherUserUid = 10 * 100_000 + notificationListenerUid;
    installPackage(NOTIFICATION_LISTENER_PACKAGE, notificationListenerUid);
    RemoteUserInfo caller =
        new RemoteUserInfo(NOTIFICATION_LISTENER_PACKAGE, NOTIFICATION_LISTENER_PID, otherUserUid);

    dispatchToLegacyStub(caller, MediaSessionLegacyStub::onPlay);

    assertThat(connectedControllers).hasSize(1);
    ControllerInfo controller = connectedControllers.get(0);
    assertThat(controller.isPackageNameVerified()).isTrue();
    assertThat(controller.isTrusted()).isFalse();
    assertThat(player.getPlayWhenReady()).isFalse();
  }

  private MediaSession.ConnectionResult createAcceptedConnectionResult(ControllerInfo controller) {
    MediaSession.ConnectionResult.AcceptedResultBuilder builder =
        new MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller);
    if (controller.isTrusted()) {
      builder.setAvailableSessionCommands(
          MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
              .buildUpon()
              .add(new SessionCommand("com.example.CUSTOM_COMMAND", Bundle.EMPTY))
              .add(new SessionCommand("com.example.CUSTOM_ACTION", Bundle.EMPTY))
              .build());
    }
    return builder.build();
  }

  private void installPackage(String packageName, int uid) {
    ApplicationInfo applicationInfo = new ApplicationInfo();
    applicationInfo.packageName = packageName;
    applicationInfo.uid = uid;
    PackageInfo packageInfo = new PackageInfo();
    packageInfo.packageName = packageName;
    packageInfo.applicationInfo = applicationInfo;
    shadowOf(context.getPackageManager()).installPackage(packageInfo);
  }

  private void dispatchMediaButtonEvent(RemoteUserInfo caller, Intent mediaButtonIntent) {
    dispatchToLegacyStub(
        caller,
        legacyStub -> {
          boolean unused = legacyStub.onMediaButtonEvent(mediaButtonIntent);
        });
  }

  private void dispatchToLegacyStub(
      RemoteUserInfo caller, Consumer<MediaSessionLegacyStub> action) {
    MediaSessionLegacyStub legacyStub = session.getImpl().getMediaSessionLegacyStub();
    MediaSessionCompat sessionCompat = legacyStub.getSessionCompat();
    sessionCompat.setCurrentControllerInfo(caller);
    try {
      action.accept(legacyStub);
    } finally {
      sessionCompat.setCurrentControllerInfo(null);
    }
    ShadowLooper.idleMainLooper();
  }
}
