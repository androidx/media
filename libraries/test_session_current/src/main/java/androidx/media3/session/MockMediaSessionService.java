/*
 * Copyright 2018 The Android Open Source Project
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

import static androidx.media3.test.session.common.CommonConstants.MEDIA_CONTROLLER_PACKAGE_NAME_API_21;
import static androidx.media3.test.session.common.CommonConstants.SUPPORT_APP_PACKAGE_NAME;
import static androidx.media3.test.session.common.MediaSessionConstants.CONNECTION_HINT_KEY_ASYNC_CONNECTION_DELAY_MS;
import static androidx.media3.test.session.common.MediaSessionConstants.CONNECTION_HINT_KEY_ASYNC_CONNECTION_REJECT_DELAY_MS;
import static androidx.media3.test.session.common.MediaSessionConstants.EXTRA_KEY_ASYNC_CONNECTION_CONFIRMATION;
import static com.google.common.util.concurrent.Futures.immediateFuture;

import android.content.Intent;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.IBinder;
import android.text.TextUtils;
import androidx.annotation.Nullable;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.NullableType;
import androidx.media3.session.MediaSession.ConnectionResult;
import androidx.media3.session.MediaSession.ControllerInfo;
import androidx.media3.session.TestServiceRegistry.OnDestroyListener;
import androidx.media3.session.TestServiceRegistry.OnUpdateMediaNotificationAsyncHandler;
import androidx.media3.test.session.common.TestHandler;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** A mock MediaSessionService */
public class MockMediaSessionService extends MediaSessionService {
  /** ID of the session that this service will create. */
  public static final String ID = "TestSession";

  private final AtomicInteger boundControllerCount;
  private final ConditionVariable allControllersUnbound;

  @Nullable public MediaSession session;
  @Nullable private HandlerThread handlerThread;
  @Nullable private TestHandler handler;
  private boolean cleanupServiceRegistryOnDestroy;

  public MockMediaSessionService() {
    boundControllerCount = new AtomicInteger(/* initialValue= */ 0);
    allControllersUnbound = new ConditionVariable();
    allControllersUnbound.open();
    cleanupServiceRegistryOnDestroy = true;
  }

  /**
   * Whether the service should clean up the service registry {@link #onDestroy()} by calling {@link
   * TestServiceRegistry#cleanUp()} on {@link TestServiceRegistry#getInstance()}.
   *
   * <p>The cleanup will release all sessions of the service. A test can clean up when tearing down
   * the test, to prevent the sessions to be released by the service.
   */
  public void setCleanupServiceRegistryOnDestroy(boolean cleanupServiceRegistryOnDestroy) {
    this.cleanupServiceRegistryOnDestroy = cleanupServiceRegistryOnDestroy;
  }

  /** Returns whether at least one controller is bound to this service. */
  public boolean hasBoundController() {
    return !allControllersUnbound.isOpen();
  }

  /**
   * Blocks until all bound controllers unbind.
   *
   * @param timeoutMs The block timeout in milliseconds.
   * @throws TimeoutException If the block timed out.
   * @throws InterruptedException If the block was interrupted.
   */
  public void blockUntilAllControllersUnbind(long timeoutMs)
      throws TimeoutException, InterruptedException {
    if (!allControllersUnbound.block(timeoutMs)) {
      throw new TimeoutException();
    }
  }

  @Override
  public void onCreate() {
    TestServiceRegistry.getInstance().setServiceInstance(this);
    super.onCreate();
    handlerThread = new HandlerThread("MockMediaSessionService");
    handlerThread.start();
    handler = new TestHandler(handlerThread.getLooper());
  }

  @Override
  public IBinder onBind(@Nullable Intent intent) {
    boundControllerCount.incrementAndGet();
    allControllersUnbound.close();
    return super.onBind(intent);
  }

  @Override
  public boolean onUnbind(Intent intent) {
    if (boundControllerCount.decrementAndGet() == 0) {
      allControllersUnbound.open();
    }
    return super.onUnbind(intent);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    OnDestroyListener listener = TestServiceRegistry.getInstance().getOnDestroyListener();
    if (listener != null) {
      listener.onDestroyCalled();
    }
    if (cleanupServiceRegistryOnDestroy) {
      TestServiceRegistry.getInstance().cleanUp();
    }
    handlerThread.quitSafely();
  }

  @Override
  public ListenableFuture<@NullableType Void> onUpdateNotificationAsync(
      MediaSession session, boolean startInForegroundRequired) {
    TestServiceRegistry registry = TestServiceRegistry.getInstance();
    OnUpdateMediaNotificationAsyncHandler onUpdateMediaNotificationAsyncHandler =
        registry.getOnUpdateMediaNotificationAsyncHandler();
    return onUpdateMediaNotificationAsyncHandler != null
        ? onUpdateMediaNotificationAsyncHandler.onUpdateMediaNotificationAsync(
            session, startInForegroundRequired)
        : super.onUpdateNotificationAsync(session, startInForegroundRequired);
  }

  @Override
  public MediaSession onGetSession(ControllerInfo controllerInfo) {
    TestServiceRegistry registry = TestServiceRegistry.getInstance();
    TestServiceRegistry.OnGetSessionHandler onGetSessionHandler = registry.getOnGetSessionHandler();
    if (onGetSessionHandler != null) {
      return onGetSessionHandler.onGetSession(controllerInfo);
    }

    if (session == null) {
      MediaSession.Callback callback = registry.getSessionCallback();
      MockPlayer player =
          new MockPlayer.Builder().setApplicationLooper(handlerThread.getLooper()).build();
      session =
          new MediaSession.Builder(MockMediaSessionService.this, player)
              .setId(ID)
              .setCallback(callback != null ? callback : new TestSessionCallback())
              .build();
    }
    return session;
  }

  private class TestSessionCallback implements MediaSession.Callback {

    @Override
    public ListenableFuture<MediaSession.ConnectionResult> onConnectAsync(
        MediaSession session, ControllerInfo controller) {
      if (TextUtils.equals(SUPPORT_APP_PACKAGE_NAME, controller.getPackageName())
          || TextUtils.equals(MEDIA_CONTROLLER_PACKAGE_NAME_API_21, controller.getPackageName())) {
        Bundle connectionHints = controller.getConnectionHints();
        if (connectionHints.containsKey(CONNECTION_HINT_KEY_ASYNC_CONNECTION_DELAY_MS)) {
          long delayMs = connectionHints.getLong(CONNECTION_HINT_KEY_ASYNC_CONNECTION_DELAY_MS);
          Log.d("connect", "connecting async: " + delayMs);
          SettableFuture<ConnectionResult> future = SettableFuture.create();
          Bundle bundle = new Bundle();
          bundle.putBoolean(EXTRA_KEY_ASYNC_CONNECTION_CONFIRMATION, true);
          handler.postDelayed(
              () -> {
                future.set(
                    new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setSessionExtras(bundle)
                        .build());
              },
              delayMs);
          return future;
        } else if (connectionHints.containsKey(
            CONNECTION_HINT_KEY_ASYNC_CONNECTION_REJECT_DELAY_MS)) {
          long delayMs =
              connectionHints.getLong(CONNECTION_HINT_KEY_ASYNC_CONNECTION_REJECT_DELAY_MS);
          SettableFuture<MediaSession.ConnectionResult> future = SettableFuture.create();
          handler.postDelayed(() -> future.set(MediaSession.ConnectionResult.reject()), delayMs);
          return future;
        }
        return MediaSession.Callback.super.onConnectAsync(session, controller);
      }
      return immediateFuture(MediaSession.ConnectionResult.reject());
    }
  }
}
