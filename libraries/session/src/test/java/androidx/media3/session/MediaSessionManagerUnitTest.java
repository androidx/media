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
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertThrows;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.session.MediaController;
import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.util.concurrent.ListenableFuture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/** Robolectric unit tests for {@link MediaSessionManager}. */
@RunWith(AndroidJUnit4.class)
public final class MediaSessionManagerUnitTest {

  private static final String OTHER_PACKAGE_NAME = "com.example.other";

  private Context context;
  private android.media.session.MediaSession platformSession;

  @Before
  public void setUp() {
    context = getApplicationContext();
    platformSession = new android.media.session.MediaSession(context, "test");
  }

  @After
  public void tearDown() {
    shadowOf(context.getSystemService(android.media.session.MediaSessionManager.class))
        .clearControllers();
    platformSession.release();
  }

  @Ignore("TODO(b/576970537): Enable with Robolectric package name fix.")
  @Test
  public void getActiveSessions_withMediaContentControlPermission_returnsSessions()
      throws Exception {
    shadowOf((Application) context)
        .grantPermissions(android.Manifest.permission.MEDIA_CONTENT_CONTROL);
    addActivePlatformController(OTHER_PACKAGE_NAME);

    ListenableFuture<List<SessionToken>> tokensFuture =
        MediaSessionManager.getActiveSessions(
            context, /* packageName= */ null, /* notificationListener= */ null);
    idleAllLoopers();
    List<SessionToken> tokens = tokensFuture.get();

    assertThat(tokens).hasSize(1);
    assertThat(tokens.get(0).getPackageName()).isEqualTo(context.getPackageName());
  }

  @Test
  @Config(maxSdk = 36)
  public void getActiveSessions_withoutPermissionForOwnPackageBelowApi37_returnsEmptyList()
      throws Exception {
    addActivePlatformController(context.getPackageName());

    ListenableFuture<List<SessionToken>> tokensFuture =
        MediaSessionManager.getActiveSessions(
            context, context.getPackageName(), /* notificationListener= */ null);
    idleAllLoopers();
    List<SessionToken> tokens = tokensFuture.get();

    assertThat(tokens).isEmpty();
  }

  @Test
  public void getActiveSessions_withoutPermissionForOtherPackage_throwsSecurityException() {
    assertThrows(
        SecurityException.class,
        () ->
            MediaSessionManager.getActiveSessions(
                context, OTHER_PACKAGE_NAME, /* notificationListener= */ null));
  }

  @Ignore("TODO(b/576970537): Enable with Robolectric package name fix.")
  @Test
  public void
      addOnActiveSessionsChangedListener_withMediaContentControlPermission_notifiesListener()
          throws Exception {
    shadowOf((Application) context)
        .grantPermissions(android.Manifest.permission.MEDIA_CONTENT_CONTROL);
    List<List<SessionToken>> receivedUpdates = new ArrayList<>();
    MediaSessionManager.OnActiveSessionsChangedListener listener = receivedUpdates::add;

    MediaSessionManager.addOnActiveSessionsChangedListener(
        context, OTHER_PACKAGE_NAME, listener, directExecutor(), /* notificationListener= */ null);
    addActivePlatformController(OTHER_PACKAGE_NAME);
    idleAllLoopers();
    MediaSessionManager.removeOnActiveSessionsChangedListener(context, listener);

    assertThat(receivedUpdates).hasSize(1);
    assertThat(receivedUpdates.get(0)).hasSize(1);
  }

  @Test
  @Config(maxSdk = 36)
  public void
      addOnActiveSessionsChangedListener_withoutPermissionForOwnPackageBelowApi37_doesNotNotifyListener()
          throws Exception {
    List<List<SessionToken>> receivedUpdates = new ArrayList<>();
    MediaSessionManager.OnActiveSessionsChangedListener listener = receivedUpdates::add;

    MediaSessionManager.addOnActiveSessionsChangedListener(
        context,
        context.getPackageName(),
        listener,
        directExecutor(),
        /* notificationListener= */ null);
    addActivePlatformController(context.getPackageName());
    idleAllLoopers();
    MediaSessionManager.removeOnActiveSessionsChangedListener(context, listener);

    assertThat(receivedUpdates).isEmpty();
  }

  @Test
  public void
      addOnActiveSessionsChangedListener_withoutPermissionForOtherPackage_throwsSecurityException() {
    MediaSessionManager.OnActiveSessionsChangedListener listener = activeSessions -> {};
    Executor executor = directExecutor();

    assertThrows(
        SecurityException.class,
        () ->
            MediaSessionManager.addOnActiveSessionsChangedListener(
                context, OTHER_PACKAGE_NAME, listener, executor, /* notificationListener= */ null));
  }

  @Ignore("TODO(b/576970537): Enable with Robolectric package name fix.")
  @Test
  public void
      addOnActiveSessionsChangedListener_afterPlatformRegistrationFails_allowsRetryWithSameListener()
          throws Exception {
    shadowOf((Application) context)
        .grantPermissions(android.Manifest.permission.MEDIA_CONTENT_CONTROL);
    Context contextWithoutPlatformManager =
        new ContextWrapper(context) {
          @Override
          public Context getApplicationContext() {
            return this;
          }

          @Override
          public @Nullable Object getSystemService(String name) {
            if (name.equals(Context.MEDIA_SESSION_SERVICE)) {
              return null;
            }
            return super.getSystemService(name);
          }
        };
    List<List<SessionToken>> receivedUpdates = new ArrayList<>();
    MediaSessionManager.OnActiveSessionsChangedListener listener = receivedUpdates::add;
    Executor executor = directExecutor();

    assertThrows(
        RuntimeException.class,
        () ->
            MediaSessionManager.addOnActiveSessionsChangedListener(
                contextWithoutPlatformManager,
                OTHER_PACKAGE_NAME,
                listener,
                executor,
                /* notificationListener= */ null));
    MediaSessionManager.addOnActiveSessionsChangedListener(
        context, OTHER_PACKAGE_NAME, listener, executor, /* notificationListener= */ null);
    addActivePlatformController(OTHER_PACKAGE_NAME);
    idleAllLoopers();
    MediaSessionManager.removeOnActiveSessionsChangedListener(context, listener);

    assertThat(receivedUpdates).hasSize(1);
    assertThat(receivedUpdates.get(0)).hasSize(1);
  }

  private void addActivePlatformController(String packageName) {
    MediaController controller = new MediaController(context, platformSession.getSessionToken());
    shadowOf(controller).setPackageName(packageName);
    shadowOf(context.getSystemService(android.media.session.MediaSessionManager.class))
        .addController(controller);
  }

  private static void idleAllLoopers() throws InterruptedException {
    shadowOf(Looper.getMainLooper()).idle();
    List<Thread> sessionTokenThreads = new ArrayList<>();
    for (Looper looper : ShadowLooper.getAllLoopers()) {
      if (looper.getThread().getName().equals("SessionTokenThread")) {
        sessionTokenThreads.add(looper.getThread());
      }
    }
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
    for (Thread thread : sessionTokenThreads) {
      thread.join();
    }
    shadowOf(Looper.getMainLooper()).idle();
  }
}
