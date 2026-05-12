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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.test.session.common.TestUtils.TIMEOUT_MS;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import android.content.ComponentName;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.test.session.common.HandlerThreadTestRule;
import androidx.media3.test.session.common.MainLooperTestRule;
import androidx.media3.test.session.common.TestHandler;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.FileInputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link MediaSessionManager}. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class MediaSessionManagerTest {

  private static final String TAG = "MediaSessionManagerTest";

  @ClassRule public static final MainLooperTestRule mainLooperTestRule = new MainLooperTestRule();

  @Rule public final HandlerThreadTestRule threadTestRule = new HandlerThreadTestRule(TAG);

  private Context context;
  private TestHandler handler;
  private MediaSession session;
  private Player player;
  private ComponentName testNotificationListener;

  @Before
  public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    handler = threadTestRule.getHandler();

    // Use TestExoPlayerBuilder to create a real player on the handler thread.
    handler.postAndSync(
        () -> {
          player = new TestExoPlayerBuilder(context).build();
          player.setPlayWhenReady(true);
        });

    session = new MediaSession.Builder(context, player).setId(TAG).build();
  }

  @After
  public void tearDown() throws Exception {
    if (session != null) {
      session.release();
    }
    if (player != null) {
      handler.postAndSync(player::release);
    }
    disableTestNotificationListener();
    if (SDK_INT >= 29) {
      InstrumentationRegistry.getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
    }
  }

  @Test
  @SdkSuppress(maxSdkVersion = 36)
  public void getActiveSessions_withoutPermissionForCurrentPackageAndApiBelow37_returnsEmptyList()
      throws Exception {
    ListenableFuture<List<SessionToken>> future =
        MediaSessionManager.getActiveSessions(
            context, context.getPackageName(), /* notificationListener= */ null);
    List<SessionToken> tokens = future.get(TIMEOUT_MS, MILLISECONDS);
    assertThat(tokens).isEmpty();
  }

  @Test
  public void getActiveSessions_withoutPermissionForOtherPackage_throwsSecurityException()
      throws Exception {
    assertThrows(
        SecurityException.class,
        () ->
            MediaSessionManager.getActiveSessions(
                context, /* packageName= */ "other.package", /* notificationListener= */ null));
  }

  @Test
  @SdkSuppress(maxSdkVersion = 36)
  public void addOnActiveSessionsChangedListener_withoutPermissionAndApiBelow37_doesNotNotify()
      throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    MediaSessionManager.OnActiveSessionsChangedListener listener =
        activeSessions -> latch.countDown();

    handler.postAndSync(
        () ->
            MediaSessionManager.addOnActiveSessionsChangedListener(
                context,
                context.getPackageName(),
                listener,
                ContextCompat.getMainExecutor(context),
                /* notificationListener= */ null));

    // Wait for a short period to ensure no unexpected callback occurs.
    assertThat(latch.await(TIMEOUT_MS, MILLISECONDS)).isFalse();

    handler.postAndSync(
        () -> MediaSessionManager.removeOnActiveSessionsChangedListener(context, listener));
  }

  @Test
  public void
      addOnActiveSessionsChangedListener_withoutPermissionForOtherPackage_throwsSecurityException()
          throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    MediaSessionManager.OnActiveSessionsChangedListener listener =
        activeSessions -> latch.countDown();

    assertThrows(
        SecurityException.class,
        () ->
            handler.postAndSync(
                () ->
                    MediaSessionManager.addOnActiveSessionsChangedListener(
                        context,
                        /* packageName= */ "other.package",
                        listener,
                        ContextCompat.getMainExecutor(context),
                        /* notificationListener= */ null)));
  }

  @Test
  @SdkSuppress(minSdkVersion = 37)
  public void getActiveSessions_withoutPermissionForCurrentPackageAndAtLeastApi37_returnsTokens()
      throws Exception {
    List<SessionToken> tokens =
        waitAndGetActiveSessions(context.getPackageName(), /* notificationListener= */ null);

    assertThat(tokens).isNotEmpty();
    assertThat(tokens.get(0).getPackageName()).isEqualTo(context.getPackageName());
  }

  @Test
  @SdkSuppress(minSdkVersion = 37)
  public void
      addOnActiveSessionsChangedListener_withoutPermissionForCurrentPackageAndApi37_notifiesListener()
          throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    MediaSessionManager.OnActiveSessionsChangedListener listener =
        activeSessions -> {
          if (!activeSessions.isEmpty()) {
            latch.countDown();
          }
        };

    handler.postAndSync(
        () ->
            MediaSessionManager.addOnActiveSessionsChangedListener(
                context,
                context.getPackageName(),
                listener,
                ContextCompat.getMainExecutor(context),
                /* notificationListener= */ null));

    List<SessionToken> unused =
        waitAndGetActiveSessions(context.getPackageName(), /* notificationListener= */ null);

    final MediaSession[] session2 = new MediaSession[1];
    final Player[] player2 = new Player[1];
    try {
      handler.postAndSync(
          () -> {
            player2[0] = new TestExoPlayerBuilder(context).build();
            player2[0].setPlayWhenReady(true);
            session2[0] = new MediaSession.Builder(context, player2[0]).setId(TAG + "_2").build();
          });

      assertThat(latch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    } finally {
      handler.postAndSync(
          () -> {
            MediaSessionManager.removeOnActiveSessionsChangedListener(context, listener);
            if (session2[0] != null) {
              session2[0].release();
            }
            if (player2[0] != null) {
              player2[0].release();
            }
          });
    }
  }

  // Shell manifest didn't include MEDIA_CONTENT_CONTROL before API 31
  @Test
  @SdkSuppress(minSdkVersion = 31)
  public void getActiveSessions_withPermission_returnsTokens() throws Exception {
    adoptMediaContentControlPermission();
    List<SessionToken> tokens =
        waitAndGetActiveSessions(/* packageName= */ null, /* notificationListener= */ null);

    Optional<SessionToken> testSessionToken =
        Iterables.tryFind(
            tokens,
            token ->
                token.getPackageName().equals(context.getPackageName())
                    && token.getType() == SessionToken.TYPE_SESSION);
    assertWithMessage("Test session token missing. tokens=%s", tokens)
        .that(testSessionToken)
        .isPresent();
  }

  @Test
  @SdkSuppress(minSdkVersion = 28) // Shell property to enable listener doesn't work before API 28
  public void getActiveSessions_withNotificationListener_returnsTokens() throws Exception {
    enableTestNotificationListener();
    List<SessionToken> tokens =
        waitAndGetActiveSessions(/* packageName= */ null, testNotificationListener);

    Optional<SessionToken> testSessionToken =
        Iterables.tryFind(
            tokens,
            token ->
                token.getPackageName().equals(context.getPackageName())
                    && token.getType() == SessionToken.TYPE_SESSION);
    assertWithMessage("Test session token missing. tokens=%s", tokens)
        .that(testSessionToken)
        .isPresent();
  }

  @Test
  // Shell manifest didn't include MEDIA_CONTENT_CONTROL before API 31
  @SdkSuppress(minSdkVersion = 31)
  public void getActiveSessions_withPermissionForPackage_returnsFilteredTokens() throws Exception {
    adoptMediaContentControlPermission();
    // Wait for session to be active in system.
    List<SessionToken> unused =
        waitAndGetActiveSessions(/* packageName= */ null, /* notificationListener= */ null);

    ListenableFuture<List<SessionToken>> future =
        MediaSessionManager.getActiveSessions(
            context, context.getPackageName(), /* notificationListener= */ null);
    List<SessionToken> tokens = future.get(TIMEOUT_MS, MILLISECONDS);

    assertThat(tokens).hasSize(1);
    assertThat(tokens.get(0).getPackageName()).isEqualTo(context.getPackageName());

    future =
        MediaSessionManager.getActiveSessions(
            context, /* packageName= */ "other.package", /* notificationListener= */ null);
    tokens = future.get(TIMEOUT_MS, MILLISECONDS);
    assertThat(tokens).isEmpty();
  }

  @Test
  @SdkSuppress(minSdkVersion = 28) // Shell property to enable listener doesn't work before API 28
  public void getActiveSessions_withNotificationListenerForPackage_returnsFilteredTokens()
      throws Exception {
    enableTestNotificationListener();
    // Wait for session to be active in system.
    List<SessionToken> unused =
        waitAndGetActiveSessions(/* packageName= */ null, testNotificationListener);

    ListenableFuture<List<SessionToken>> future =
        MediaSessionManager.getActiveSessions(
            context, context.getPackageName(), testNotificationListener);
    List<SessionToken> tokens = future.get(TIMEOUT_MS, MILLISECONDS);

    assertThat(tokens).hasSize(1);
    assertThat(tokens.get(0).getPackageName()).isEqualTo(context.getPackageName());

    future =
        MediaSessionManager.getActiveSessions(
            context, /* packageName= */ "other.package", testNotificationListener);
    tokens = future.get(TIMEOUT_MS, MILLISECONDS);
    assertThat(tokens).isEmpty();
  }

  @Test
  // Shell manifest didn't include MEDIA_CONTENT_CONTROL before API 31
  @SdkSuppress(minSdkVersion = 31)
  public void addOnActiveSessionsChangedListener_withPermission_notifiesListener()
      throws Exception {
    adoptMediaContentControlPermission();
    // Wait for session to be active in system.
    List<SessionToken> unused = waitAndGetActiveSessions(/* packageName= */ null, null);

    CountDownLatch latch = new CountDownLatch(1);
    MediaSessionManager.OnActiveSessionsChangedListener listenerCallback =
        activeSessions -> {
          if (!activeSessions.isEmpty()) {
            latch.countDown();
          }
        };

    // Call on handler thread to ensure looper is prepared.
    handler.postAndSync(
        () ->
            MediaSessionManager.addOnActiveSessionsChangedListener(
                context, null, listenerCallback, ContextCompat.getMainExecutor(context), null));

    // Create a SECOND session to trigger a change!
    final MediaSession[] session2 = new MediaSession[1];
    final Player[] player2 = new Player[1];
    try {
      handler.postAndSync(
          () -> {
            player2[0] = new TestExoPlayerBuilder(context).build();
            player2[0].setPlayWhenReady(true);
            session2[0] = new MediaSession.Builder(context, player2[0]).setId(TAG + "_2").build();
          });

      // Wait for the latch to be counted down.
      assertThat(latch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    } finally {
      handler.postAndSync(
          () -> {
            MediaSessionManager.removeOnActiveSessionsChangedListener(context, listenerCallback);
            if (session2[0] != null) {
              session2[0].release();
            }
            if (player2[0] != null) {
              player2[0].release();
            }
          });
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 28) // Shell property to enable listener doesn't work before API 28
  public void addOnActiveSessionsChangedListener_withNotificationListener_notifiesListener()
      throws Exception {
    enableTestNotificationListener();
    // Wait for session to be active in system.
    List<SessionToken> unused =
        waitAndGetActiveSessions(/* packageName= */ null, testNotificationListener);

    CountDownLatch latch = new CountDownLatch(1);
    MediaSessionManager.OnActiveSessionsChangedListener listenerCallback =
        activeSessions -> {
          if (!activeSessions.isEmpty()) {
            latch.countDown();
          }
        };

    // Call on handler thread to ensure looper is prepared.
    handler.postAndSync(
        () ->
            MediaSessionManager.addOnActiveSessionsChangedListener(
                context,
                null,
                listenerCallback,
                ContextCompat.getMainExecutor(context),
                testNotificationListener));

    // Create a SECOND session to trigger a change!
    final MediaSession[] session2 = new MediaSession[1];
    final Player[] player2 = new Player[1];
    try {
      handler.postAndSync(
          () -> {
            player2[0] = new TestExoPlayerBuilder(context).build();
            player2[0].setPlayWhenReady(true);
            session2[0] = new MediaSession.Builder(context, player2[0]).setId(TAG + "_2").build();
          });

      // Wait for the latch to be counted down.
      assertThat(latch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    } finally {
      handler.postAndSync(
          () -> {
            MediaSessionManager.removeOnActiveSessionsChangedListener(context, listenerCallback);
            if (session2[0] != null) {
              session2[0].release();
            }
            if (player2[0] != null) {
              player2[0].release();
            }
          });
    }
  }

  @RequiresApi(29)
  private void adoptMediaContentControlPermission() {
    InstrumentationRegistry.getInstrumentation()
        .getUiAutomation()
        .adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL");
  }

  private List<SessionToken> waitAndGetActiveSessions(
      @Nullable String packageName, @Nullable ComponentName notificationListener) throws Exception {
    long startTime = SystemClock.elapsedRealtime();
    while (SystemClock.elapsedRealtime() - startTime < TIMEOUT_MS) {
      List<SessionToken> tokens =
          MediaSessionManager.getActiveSessions(context, packageName, notificationListener)
              .get(TIMEOUT_MS, MILLISECONDS);
      if (!tokens.isEmpty()) {
        return tokens;
      }
      Thread.sleep(100);
    }
    return MediaSessionManager.getActiveSessions(context, packageName, notificationListener)
        .get(TIMEOUT_MS, MILLISECONDS);
  }

  private void enableTestNotificationListener() throws Exception {
    testNotificationListener =
        new ComponentName(
            context, "androidx.media3.session.MediaSessionManagerTest$TestNotificationListener");
    String cmd = "cmd notification allow_listener " + testNotificationListener.flattenToString();
    executeShellCommand(cmd);

    // Wait for it to appear in settings
    long startTime = SystemClock.elapsedRealtime();
    boolean enabled = false;
    String testComponentStr = testNotificationListener.flattenToString();
    while (SystemClock.elapsedRealtime() - startTime < 5000) {
      String enabledNotifListeners =
          Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
      if (enabledNotifListeners != null) {
        String[] components = Util.split(enabledNotifListeners, ":");
        for (String componentStr : components) {
          if (componentStr.equals(testComponentStr)) {
            enabled = true;
            break;
          }
        }
      }
      if (enabled) {
        break;
      }
      Thread.sleep(100);
    }
    if (!enabled) {
      fail("Listener not seen as enabled in settings after 5 seconds");
    }
  }

  private void disableTestNotificationListener() throws Exception {
    if (testNotificationListener != null) {
      String cmd =
          "cmd notification disallow_listener " + testNotificationListener.flattenToString();
      executeShellCommand(cmd);
    }
  }

  private static void executeShellCommand(String cmd) throws Exception {
    ParcelFileDescriptor pfd =
        InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(cmd);
    try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
      byte[] buf = new byte[1024];
      while (fis.read(buf) != -1) {
        // Consume output
      }
    }
  }

  @Test
  @SdkSuppress(maxSdkVersion = 32)
  public void getMediaKeyEventSession_beforeApi33_returnsNull() throws Exception {
    ListenableFuture<SessionToken> future = MediaSessionManager.getMediaKeyEventSession(context);
    assertThat(future.get(TIMEOUT_MS, MILLISECONDS)).isNull();
  }

  @Test
  @SdkSuppress(minSdkVersion = 33)
  public void getMediaKeyEventSession_afterApi33_doesNotCrash() throws Exception {
    adoptMediaContentControlPermission();

    handler.postAndSync(
        () -> {
          AudioAttributes audioAttributes =
              new AudioAttributes.Builder()
                  .setUsage(C.USAGE_MEDIA)
                  .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                  .build();
          player.setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true);

          player.setMediaItem(MediaItem.fromUri("asset:///media/mp4/sample.mp4"));
          player.prepare();
          player.play();
        });

    long startTime = SystemClock.elapsedRealtime();
    SessionToken token = null;
    // Retry because it may take some time for the system to register the newly playing session
    // as the media key event session.
    while (SystemClock.elapsedRealtime() - startTime < TIMEOUT_MS) {
      token = MediaSessionManager.getMediaKeyEventSession(context).get(TIMEOUT_MS, MILLISECONDS);
      if (token != null) {
        break;
      }
      Thread.sleep(100);
    }

    if (token != null) {
      assertThat(token.getPackageName()).isEqualTo(context.getPackageName());
    }
  }

  @Test
  // Shell manifest didn't include MEDIA_CONTENT_CONTROL before API 31
  @SdkSuppress(minSdkVersion = 31)
  public void removeOnActiveSessionsChangedListener_withPermission_stopsCallbacks()
      throws Exception {
    adoptMediaContentControlPermission();
    // Wait for session to be active in system.
    List<SessionToken> unused =
        waitAndGetActiveSessions(/* packageName= */ null, /* notificationListener= */ null);

    CountDownLatch latch = new CountDownLatch(1);
    MediaSessionManager.OnActiveSessionsChangedListener listenerCallback =
        activeSessions -> latch.countDown();

    handler.postAndSync(
        () ->
            MediaSessionManager.addOnActiveSessionsChangedListener(
                context,
                /* packageName= */ null,
                listenerCallback,
                ContextCompat.getMainExecutor(context),
                /* notificationListener= */ null));

    // Remove the listener
    handler.postAndSync(
        () -> MediaSessionManager.removeOnActiveSessionsChangedListener(context, listenerCallback));

    // Create a SECOND session to trigger a change!
    final MediaSession[] session2 = new MediaSession[1];
    try {
      handler.postAndSync(
          () -> {
            Player player2 = new TestExoPlayerBuilder(context).build();
            player2.setPlayWhenReady(true);
            session2[0] = new MediaSession.Builder(context, player2).setId(TAG + "_2").build();
          });

      // Latch shouldn't trigger
      assertThat(latch.await(1000, MILLISECONDS)).isFalse();
    } finally {
      if (session2[0] != null) {
        session2[0].release();
      }
    }
  }

  /** A test {@link NotificationListenerService}. */
  public static final class TestNotificationListener extends NotificationListenerService {}
}
