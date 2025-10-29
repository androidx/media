/*
 * Copyright 2019 The Android Open Source Project
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
import static androidx.media3.common.util.Util.postOrRun;
import static androidx.media3.session.SessionUtil.PACKAGE_VALID;
import static androidx.media3.session.SessionUtil.checkPackageValidity;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.app.Activity;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.annotation.CallSuper;
import androidx.annotation.GuardedBy;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.collection.ArrayMap;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.Player;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.session.MediaSession.ControllerInfo;
import androidx.media3.session.legacy.MediaBrowserServiceCompat;
import androidx.media3.session.legacy.MediaSessionManager;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Superclass to be extended by services hosting {@link MediaSession media sessions}.
 *
 * <p>It's highly recommended for an app to use this class if media playback should continue while
 * in the background. The service allows other apps to know that your app supports {@link
 * MediaSession} even when your app isn't running. This way, a user voice command may be able start
 * your app to play media.
 *
 * <p>To extend this class, declare the intent filter in your {@code AndroidManifest.xml}:
 *
 * <pre>{@code
 * <service
 *   android:name="NameOfYourService"
 *   android:foregroundServiceType="mediaPlayback"
 *   android:exported="true">
 *   <intent-filter>
 *     <action android:name="androidx.media3.session.MediaSessionService"/>
 *   </intent-filter>
 * </service>
 * }</pre>
 *
 * <p>You may also declare the action {@code android.media.browse.MediaBrowserService} for
 * compatibility with {@code android.support.v4.media.MediaBrowserCompat}. This service can handle
 * the case automatically.
 *
 * <p>It's recommended for an app to have a single service declared in the manifest. Otherwise, your
 * app might be shown twice in the list of the controller apps, or another app might fail to pick
 * the right service when it wants to start a playback on this app. If you want to provide multiple
 * sessions, take a look at <a href="#MultipleSessions">Supporting Multiple Sessions</a>.
 *
 * <p>Topics covered here:
 *
 * <ol>
 *   <li><a href="#ServiceLifecycle">Service Lifecycle</a>
 *   <li><a href="#MultipleSessions">Supporting Multiple Sessions</a>
 * </ol>
 *
 * <h2 id="ServiceLifecycle">Service Lifecycle</h2>
 *
 * <p>A media session service is a bound service and its <a
 * href="https://developer.android.com/guide/topics/manifest/service-element#foregroundservicetype">
 * foreground service type</a> must include <em>mediaPlayback</em>. When a {@link MediaController}
 * is created for the service, the controller binds to the service. {@link
 * #onGetSession(ControllerInfo)} will be called from {@link #onBind(Intent)}.
 *
 * <p>After binding, the session's {@link MediaSession.Callback#onConnect(MediaSession,
 * MediaSession.ControllerInfo)} will be called to accept or reject the connection request from the
 * controller. If it's accepted, the controller will be available and keep the binding. If it's
 * rejected, the controller will unbind.
 *
 * <p>{@link #onUpdateNotification(MediaSession, boolean)} will be called whenever a notification
 * needs to be shown, updated or cancelled. The default implementation will display notifications
 * using a default UI or using a {@link MediaNotification.Provider} that's set with {@link
 * #setMediaNotificationProvider}. In addition, when playback starts, the service will become a <a
 * href="https://developer.android.com/guide/components/foreground-services">foreground service</a>.
 * It's required to keep the playback after the controller is destroyed. The service will become a
 * background service when all playbacks are stopped. Apps targeting {@code SDK_INT >= 28} must
 * request the permission, {@link android.Manifest.permission#FOREGROUND_SERVICE}, in order to make
 * the service foreground. You can control when to show or hide notifications by overriding {@link
 * #onUpdateNotification(MediaSession, boolean)}. In this case, you must also start or stop the
 * service from the foreground, when playback starts or stops respectively.
 *
 * <p>The service will be destroyed when all sessions are {@linkplain MediaController#release()
 * released}, or no controller is binding to the service while the service is in the background.
 *
 * <h2 id="MultipleSessions">Supporting Multiple Sessions</h2>
 *
 * <p>Generally, multiple sessions aren't necessary for most media apps. One exception is if your
 * app can play multiple media contents at the same time, but only for playback of video-only media
 * or remote playback, since the <a
 * href="https://developer.android.com/media/optimize/audio-focus">audio focus policy</a> recommends
 * not playing multiple audio contents at the same time. Also, keep in mind that multiple media
 * sessions would make Android Auto and Bluetooth devices with a display to show your apps multiple
 * times, because they list up media sessions, not media apps.
 *
 * <p>However, if you're capable of handling multiple playbacks and want to keep their sessions
 * while the app is in the background, create multiple sessions and add them to this service with
 * {@link #addSession(MediaSession)}.
 *
 * <p>Note that a {@link MediaController} can be created with {@link SessionToken} to connect to a
 * session in this service. In that case, {@link #onGetSession(ControllerInfo)} will be called to
 * decide which session to handle the connection request. Pick the best session among the added
 * sessions, or create a new session and return it from {@link #onGetSession(ControllerInfo)}.
 */
public abstract class MediaSessionService extends Service {

  /**
   * Listener for {@link MediaSessionService}.
   *
   * <p>The methods will be called on the main thread.
   */
  @UnstableApi
  public interface Listener {
    /**
     * Called when the service fails to start in the foreground and a {@link
     * ForegroundServiceStartNotAllowedException} is thrown on Android 12 or later.
     */
    @RequiresApi(31)
    default void onForegroundServiceStartNotAllowedException() {}
  }

  /** The action for {@link Intent} filter that must be declared by the service. */
  public static final String SERVICE_INTERFACE = "androidx.media3.session.MediaSessionService";

  /**
   * The default timeout for a session to stay in a foreground service state after it paused,
   * stopped, failed or ended.
   */
  @UnstableApi public static final long DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS = 600_000;

  /**
   * The behavior for showing notifications when the {@link Player} is in {@link Player#STATE_IDLE}.
   *
   * <p>One of {@link #SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS}, {@link
   * #SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER}, {@link
   * #SHOW_NOTIFICATION_FOR_IDLE_PLAYER_AFTER_STOP_OR_ERROR}.
   *
   * <p>The default value is {@link #SHOW_NOTIFICATION_FOR_IDLE_PLAYER_AFTER_STOP_OR_ERROR}.
   */
  @UnstableApi
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS,
    SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER,
    SHOW_NOTIFICATION_FOR_IDLE_PLAYER_AFTER_STOP_OR_ERROR
  })
  public @interface ShowNotificationForIdlePlayerMode {}

  /**
   * Always show a notification when the {@link Player} is in {@link Player#STATE_IDLE} and the
   * notification wasn't explicitly dismissed.
   */
  @UnstableApi public static final int SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS = 1;

  /** Never show a notification when the {@link Player} is in {@link Player#STATE_IDLE}. */
  @UnstableApi public static final int SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER = 2;

  /**
   * Shows a notification when the {@link Player} is in {@link Player#STATE_IDLE} due to {@link
   * Player#stop} or an error, and the notification wasn't explicitly dismissed.
   */
  @UnstableApi public static final int SHOW_NOTIFICATION_FOR_IDLE_PLAYER_AFTER_STOP_OR_ERROR = 3;

  /**
   * The behavior for showing notifications when the {@link Player} has no media.
   *
   * <p>One of {@link #SHOW_NOTIFICATION_FOR_EMPTY_PLAYER_ALWAYS}, {@link
   * #SHOW_NOTIFICATION_FOR_EMPTY_PLAYER_NEVER}, {@link
   * #SHOW_NOTIFICATION_FOR_EMPTY_PLAYER_AFTER_STOP_OR_ERROR}.
   *
   * <p>The default value is {@link #SHOW_NOTIFICATION_FOR_EMPTY_PLAYER_NEVER}.
   */
  @UnstableApi
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    SHOW_NOTIFICATION_FOR_EMPTY_PLAYER_ALWAYS,
    SHOW_NOTIFICATION_FOR_EMPTY_PLAYER_NEVER,
    SHOW_NOTIFICATION_FOR_EMPTY_PLAYER_AFTER_STOP_OR_ERROR
  })
  public @interface ShowNotificationForEmptyPlayerMode {}

  /**
   * Always show a notification when the {@link Player} is empty and the notification wasn't
   * explicitly dismissed.
   */
  @UnstableApi public static final int SHOW_NOTIFICATION_FOR_EMPTY_PLAYER_ALWAYS = 1;

  /** Never show a notification when the {@link Player} is empty. */
  @UnstableApi public static final int SHOW_NOTIFICATION_FOR_EMPTY_PLAYER_NEVER = 2;

  /**
   * Shows a notification when the {@link Player} is empty, in {@link Player#STATE_IDLE} due to
   * {@link Player#stop} or an error, and the notification wasn't explicitly dismissed.
   */
  @UnstableApi public static final int SHOW_NOTIFICATION_FOR_EMPTY_PLAYER_AFTER_STOP_OR_ERROR = 3;

  private static final String TAG = "MSessionService";

  private final Object lock;
  private final Handler mainHandler;
  @Nullable private MediaSessionServiceStub stub;
  private @MonotonicNonNull MediaNotificationManager mediaNotificationManager;
  private @MonotonicNonNull DefaultActionFactory actionFactory;

  @GuardedBy("lock")
  private final Map<String, MediaSession> sessions;

  @GuardedBy("lock")
  @Nullable
  private Listener listener;

  private boolean defaultMethodCalled;

  /** Creates a service. */
  public MediaSessionService() {
    lock = new Object();
    mainHandler = new Handler(Looper.getMainLooper());
    sessions = new ArrayMap<>();
    defaultMethodCalled = false;
  }

  /**
   * Called when the service is created.
   *
   * <p>Override this method if you need your own initialization.
   *
   * <p>This method will be called on the main thread.
   */
  @CallSuper
  @Override
  public void onCreate() {
    super.onCreate();
    stub = new MediaSessionServiceStub(this);
  }

  /**
   * Called when a {@link MediaController} is created with this service's {@link SessionToken}.
   * Return a {@link MediaSession} that the controller will connect to, or {@code null} to reject
   * the connection request.
   *
   * <p>Note: This method must not be called directly by app code.
   *
   * <p>The service automatically maintains the returned sessions. In other words, a session
   * returned by this method will be added to the service, and removed from the service when the
   * session is closed. You don't need to manually call {@link #addSession(MediaSession)} nor {@link
   * #removeSession(MediaSession)}.
   *
   * <p>There are two special cases where the {@link ControllerInfo#getPackageName()} returns a
   * non-existent package name:
   *
   * <ul>
   *   <li>When the service is started by a media button event, the package name will be {@link
   *       Intent#ACTION_MEDIA_BUTTON}. If you want to allow the service to be started by media
   *       button events, do not return {@code null}.
   *   <li>When a legacy {@link android.media.browse.MediaBrowser} or a {@code
   *       android.support.v4.media.MediaBrowserCompat} tries to connect, the package name will be
   *       {@link android.service.media.MediaBrowserService#SERVICE_INTERFACE}. If you want to allow
   *       the service to be bound by the legacy media browsers, do not return {@code null}.
   * </ul>
   *
   * <p>For those special cases, the values returned by {@link ControllerInfo#getUid()} and {@link
   * ControllerInfo#getConnectionHints()} have no meaning.
   *
   * <p>This method will be called on the main thread.
   *
   * @param controllerInfo The information of the controller that is trying to connect.
   * @return A {@link MediaSession} for the controller, or {@code null} to reject the connection.
   * @see MediaSession.Builder
   * @see #getSessions()
   */
  @Nullable
  public abstract MediaSession onGetSession(ControllerInfo controllerInfo);

  /**
   * Adds a {@link MediaSession} to this service. This is not necessary for most media apps. See <a
   * href="#MultipleSessions">Supporting Multiple Sessions</a> for details.
   *
   * <p>The added session will be removed automatically {@linkplain MediaSession#release() when the
   * session is released}.
   *
   * <p>This method can be called from any thread.
   *
   * @param session A session to be added.
   * @see #removeSession(MediaSession)
   * @see #getSessions()
   */
  public final void addSession(MediaSession session) {
    checkNotNull(session, "session must not be null");
    checkArgument(!session.isReleased(), "session is already released");
    @Nullable MediaSession old;
    synchronized (lock) {
      old = sessions.get(session.getId());
      checkArgument(old == null || old == session, "Session ID should be unique");
      sessions.put(session.getId(), session);
    }
    if (old == null) {
      // Session has returned for the first time. Register callbacks.
      // TODO(b/191644474): Check whether the session is registered to multiple services.
      postOrRun(
          mainHandler,
          () -> {
            getMediaNotificationManager().addSession(session);
            session.setListener(new MediaSessionListener());
          });
    }
  }

  /**
   * Removes a {@link MediaSession} from this service. This is not necessary for most media apps.
   * See <a href="#MultipleSessions">Supporting Multiple Sessions</a> for details.
   *
   * <p>This method can be called from any thread.
   *
   * @param session A session to be removed.
   * @see #addSession(MediaSession)
   * @see #getSessions()
   */
  public final void removeSession(MediaSession session) {
    checkNotNull(session, "session must not be null");
    synchronized (lock) {
      checkArgument(sessions.containsKey(session.getId()), "session not found");
      sessions.remove(session.getId());
    }
    postOrRun(
        mainHandler,
        () -> {
          getMediaNotificationManager().removeSession(session);
          session.clearListener();
        });
  }

  /**
   * Returns the list of {@linkplain MediaSession sessions} that you've added to this service via
   * {@link #addSession} or {@link #onGetSession(ControllerInfo)}.
   *
   * <p>This method can be called from any thread.
   */
  public final List<MediaSession> getSessions() {
    synchronized (lock) {
      return new ArrayList<>(sessions.values());
    }
  }

  /**
   * Returns whether {@code session} has been added to this service via {@link #addSession} or
   * {@link #onGetSession(ControllerInfo)}.
   *
   * <p>This method can be called from any thread.
   */
  public final boolean isSessionAdded(MediaSession session) {
    synchronized (lock) {
      return sessions.containsKey(session.getId());
    }
  }

  /**
   * Sets the {@linkplain Listener listener}.
   *
   * <p>This method can be called from any thread.
   */
  @UnstableApi
  public final void setListener(Listener listener) {
    synchronized (lock) {
      this.listener = listener;
    }
  }

  /**
   * Clears the {@linkplain Listener listener}.
   *
   * <p>This method can be called from any thread.
   */
  @UnstableApi
  public final void clearListener() {
    synchronized (lock) {
      this.listener = null;
    }
  }

  /**
   * Called when a component is about to bind to the service.
   *
   * <p>The default implementation handles the incoming requests from {@link MediaController
   * controllers}. In this case, the intent will have the action {@link #SERVICE_INTERFACE}.
   * Override this method if this service also needs to handle actions other than {@link
   * #SERVICE_INTERFACE}.
   *
   * <p>This method will be called on the main thread.
   */
  @CallSuper
  @Override
  @Nullable
  public IBinder onBind(@Nullable Intent intent) {
    if (intent == null) {
      return null;
    }
    @Nullable String action = intent.getAction();
    if (action == null) {
      return null;
    }
    switch (action) {
      case MediaSessionService.SERVICE_INTERFACE:
        return getServiceBinder();
      case MediaBrowserServiceCompat.SERVICE_INTERFACE:
        {
          ControllerInfo controllerInfo = ControllerInfo.createLegacyControllerInfo();
          @Nullable MediaSession session = onGetSession(controllerInfo);
          if (session == null) {
            // Legacy MediaBrowser(Compat) cannot connect to this service.
            return null;
          }
          addSession(session);
          // Return a specific session's legacy binder although the Android framework caches
          // the returned binder here and next binding request may reuse cached binder even
          // after the session is closed.
          // Disclaimer: Although MediaBrowserCompat can only get the session that initially
          // set, it doesn't make things bad. Such limitation had been there between
          // MediaBrowserCompat and MediaBrowserServiceCompat.
          return session.getLegacyBrowserServiceBinder();
        }
      default:
        return null;
    }
  }

  /**
   * Called when a component calls {@link android.content.Context#startService(Intent)}.
   *
   * <p>The default implementation handles the incoming media button events. In this case, the
   * intent will have the action {@link Intent#ACTION_MEDIA_BUTTON}. Override this method if this
   * service also needs to handle actions other than {@link Intent#ACTION_MEDIA_BUTTON}.
   *
   * <p>This method will be called on the main thread.
   */
  @CallSuper
  @Override
  public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
    if (intent == null) {
      return START_STICKY;
    }

    DefaultActionFactory actionFactory = getActionFactory();
    @Nullable Uri uri = intent.getData();
    @Nullable MediaSession session = uri != null ? MediaSession.getSession(uri) : null;
    if (actionFactory.isMediaAction(intent)) {
      if (session == null) {
        ControllerInfo controllerInfo = ControllerInfo.createLegacyControllerInfo();
        session = onGetSession(controllerInfo);
        if (session == null) {
          return START_STICKY;
        }
        addSession(session);
      }
      MediaSessionImpl sessionImpl = session.getImpl();
      sessionImpl
          .getApplicationHandler()
          .post(
              () -> {
                ControllerInfo callerInfo = sessionImpl.getMediaNotificationControllerInfo();
                if (callerInfo == null) {
                  callerInfo = createFallbackMediaButtonCaller(intent);
                }
                if (!sessionImpl.onMediaButtonEvent(callerInfo, intent)) {
                  Log.d(TAG, "Ignored unrecognized media button intent.");
                }
              });
    } else if (session != null && actionFactory.isCustomAction(intent)) {
      @Nullable String customAction = actionFactory.getCustomAction(intent);
      if (customAction == null) {
        return START_STICKY;
      }
      Bundle customExtras = actionFactory.getCustomActionExtras(intent);
      getMediaNotificationManager().onCustomAction(session, customAction, customExtras);
    }
    return START_STICKY;
  }

  private static ControllerInfo createFallbackMediaButtonCaller(Intent mediaButtonIntent) {
    @Nullable ComponentName componentName = mediaButtonIntent.getComponent();
    String packageName =
        componentName != null
            ? componentName.getPackageName()
            : "androidx.media3.session.MediaSessionService";
    return new ControllerInfo(
        new MediaSessionManager.RemoteUserInfo(
            packageName,
            MediaSessionManager.RemoteUserInfo.UNKNOWN_PID,
            MediaSessionManager.RemoteUserInfo.UNKNOWN_UID),
        MediaLibraryInfo.VERSION_INT,
        MediaControllerStub.VERSION_INT,
        /* trusted= */ false,
        /* cb= */ null,
        /* connectionHints= */ Bundle.EMPTY,
        /* maxCommandsForMediaItems= */ 0,
        /* isPackageNameVerified= */ false);
  }

  /**
   * Sets the timeout for a session to stay in a foreground service state after it paused, stopped,
   * failed or ended.
   *
   * <p>Can only be called once the {@link Context} of the service is initialized in {@link
   * #onCreate()}.
   *
   * <p>Has no effect on already running timeouts.
   *
   * <p>The default and maximum value is {@link #DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS}. If a larger
   * value is provided, it will be clamped down to {@link #DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS}.
   *
   * <p>This method must be called on the main thread.
   *
   * @param foregroundServiceTimeoutMs The timeout in milliseconds.
   */
  @UnstableApi
  public final void setForegroundServiceTimeoutMs(long foregroundServiceTimeoutMs) {
    getMediaNotificationManager()
        .setUserEngagedTimeoutMs(
            Util.constrainValue(
                foregroundServiceTimeoutMs,
                /* min= */ 0,
                /* max= */ DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS));
  }

  /**
   * Sets whether and when a notification for a {@link Player} in {@link Player#STATE_IDLE} should
   * be shown.
   *
   * @param showNotificationForIdlePlayerMode The {@link ShowNotificationForIdlePlayerMode}.
   */
  @UnstableApi
  public final void setShowNotificationForIdlePlayer(
      @ShowNotificationForIdlePlayerMode int showNotificationForIdlePlayerMode) {
    getMediaNotificationManager()
        .setShowNotificationForIdlePlayer(showNotificationForIdlePlayerMode);
  }

  /**
   * Sets whether and when a notification for a {@link Player} that has no media should be shown.
   *
   * @param showNotificationForEmptyPlayerMode The {@link ShowNotificationForEmptyPlayerMode}.
   */
  @UnstableApi
  public final void setShowNotificationForEmptyPlayer(
      @ShowNotificationForEmptyPlayerMode int showNotificationForEmptyPlayerMode) {
    getMediaNotificationManager()
        .setShowNotificationForEmptyPlayer(showNotificationForEmptyPlayerMode);
  }

  /**
   * Returns whether there is a session with ongoing user-engaged playback that is run in a
   * foreground service.
   *
   * <p>It is only possible to terminate the service with {@link #stopSelf()} if this method returns
   * {@code false}.
   *
   * <p>Note that sessions are kept in foreground and this method returns {@code true} for the
   * {@linkplain #setForegroundServiceTimeoutMs foreground service timeout} after they paused,
   * stopped, failed or ended. Use {@link #pauseAllPlayersAndStopSelf()} to pause all ongoing
   * playbacks immediately and terminate the service.
   *
   * <p>This method must be called on the main thread.
   */
  @UnstableApi
  public final boolean isPlaybackOngoing() {
    return getMediaNotificationManager().isStartedInForeground();
  }

  /**
   * Pauses the player of each session managed by the service, ensures the foreground service is
   * stopped, and calls {@link #stopSelf()}.
   *
   * <p>This terminates the service lifecycle and triggers {@link #onDestroy()} that an app can
   * override to release the sessions and other resources.
   *
   * <p>This method must be called on the main thread.
   */
  @UnstableApi
  public final void pauseAllPlayersAndStopSelf() {
    getMediaNotificationManager().disableUserEngagedTimeout();
    List<MediaSession> sessionList = getSessions();
    for (int i = 0; i < sessionList.size(); i++) {
      sessionList.get(i).getPlayer().setPlayWhenReady(false);
    }
    stopSelf();
  }

  /**
   * {@inheritDoc}
   *
   * <p>This method can be overridden to customize the behavior of when the app is dismissed from
   * the recent apps.
   *
   * <p>The default behavior is that if {@linkplain #isPlaybackOngoing() playback is ongoing}, which
   * means the service is already running in the foreground, and at least one media session {@link
   * Player#isPlaying() is playing}, the service is kept running. Otherwise, playbacks are paused
   * and the service is stopped by calling {@link #pauseAllPlayersAndStopSelf()} which terminates
   * the service lifecycle and triggers {@link #onDestroy()} that an app can override to release the
   * sessions and other resources.
   *
   * <p>An app can safely override this method without calling super to implement a different
   * behaviour, for instance unconditionally calling {@link #pauseAllPlayersAndStopSelf()} to stop
   * the service even when playing. However, if {@linkplain #isPlaybackOngoing() playback is not
   * ongoing}, the service must be terminated otherwise the service will be crashed and restarted by
   * the system.
   *
   * <p>Note: The service <a
   * href="https://developer.android.com/develop/background-work/services/bound-services#Lifecycle">can't
   * be stopped</a> until all media controllers have been unbound. Hence, an app needs to release
   * all internal controllers that have connected to the service (for instance from an activity in
   * {@link Activity#onStop()}). If an app allows external apps to connect a {@link MediaController}
   * to the service, these controllers also need to be disconnected. In such a scenario of external
   * bound clients, an app needs to override this method to release the session before calling
   * {@link #stopSelf()}.
   */
  @Override
  public void onTaskRemoved(@Nullable Intent rootIntent) {
    if (!isPlaybackOngoing() || !isAnySessionPlaying()) {
      // The service needs to be stopped when playback is not ongoing (i.e, the service is not in
      // the foreground). It is also force-stopped if no session is playing.
      pauseAllPlayersAndStopSelf();
    }
  }

  /**
   * Called when the service is no longer used and is being removed.
   *
   * <p>Override this method if you need your own clean up.
   *
   * <p>This method will be called on the main thread.
   */
  @CallSuper
  @Override
  public void onDestroy() {
    super.onDestroy();
    if (mediaNotificationManager != null) {
      mediaNotificationManager.disableUserEngagedTimeout();
    }
    if (stub != null) {
      stub.release();
      stub = null;
    }
  }

  /**
   * @deprecated Use {@link #onUpdateNotification(MediaSession, boolean)} instead.
   */
  @Deprecated
  public void onUpdateNotification(MediaSession session) {
    defaultMethodCalled = true;
  }

  /**
   * Called when a notification needs to be updated. Override this method to show or cancel your own
   * notifications.
   *
   * <p>Note: This method must not be called directly by app code.
   *
   * <p>This method is called whenever the service has detected a change that requires to show,
   * update or cancel a notification with a flag {@code startInForegroundRequired} suggested by the
   * service whether starting in the foreground is required. The method will be called on the
   * application thread of the app that the service belongs to.
   *
   * <p>Override this method to create your own notification and customize the foreground handling
   * of your service.
   *
   * <p>The default implementation will present a default notification or the notification provided
   * by the {@link MediaNotification.Provider} that is {@link
   * #setMediaNotificationProvider(MediaNotification.Provider) set} by the app. Further, the service
   * is started in the <a
   * href="https://developer.android.com/guide/components/foreground-services">foreground</a> when
   * playback is ongoing and put back into background otherwise.
   *
   * <p>Apps targeting {@code SDK_INT >= 28} must request the permission, {@link
   * android.Manifest.permission#FOREGROUND_SERVICE}.
   *
   * <p>This method will be called on the main thread.
   *
   * @param session A session that needs notification update.
   * @param startInForegroundRequired Whether the service is required to start in the foreground.
   */
  @SuppressWarnings("deprecation") // Calling deprecated method.
  public void onUpdateNotification(MediaSession session, boolean startInForegroundRequired) {
    onUpdateNotification(session);
    if (defaultMethodCalled) {
      getMediaNotificationManager().updateNotification(session, startInForegroundRequired);
    }
  }

  /**
   * Manually trigger a notification update.
   *
   * <p>In most cases, this should not be required unless an external event that can't be detected
   * by the session itself requires to update the notification.
   */
  @UnstableApi
  public final void triggerNotificationUpdate() {
    List<MediaSession> sessions = getSessions();
    for (int i = 0; i < sessions.size(); i++) {
      onUpdateNotificationInternal(sessions.get(i), /* startInForegroundWhenPaused= */ false);
    }
  }

  /**
   * Sets the {@link MediaNotification.Provider} to customize notifications.
   *
   * <p>This method can be called from any thread.
   */
  @UnstableApi
  protected final void setMediaNotificationProvider(
      MediaNotification.Provider mediaNotificationProvider) {
    checkNotNull(mediaNotificationProvider);
    Util.postOrRun(
        mainHandler,
        () ->
            getMediaNotificationManager(
                    /* initialMediaNotificationProvider= */ mediaNotificationProvider)
                .setMediaNotificationProvider(mediaNotificationProvider));
  }

  /* package */ IBinder getServiceBinder() {
    return checkNotNull(stub).asBinder();
  }

  /**
   * Triggers notification update and handles {@code ForegroundServiceStartNotAllowedException}.
   *
   * <p>This method will be called on the main thread.
   */
  /* package */ boolean onUpdateNotificationInternal(
      MediaSession session, boolean startInForegroundWhenPaused) {
    try {
      boolean startInForegroundRequired =
          getMediaNotificationManager().shouldRunInForeground(startInForegroundWhenPaused);
      onUpdateNotification(session, startInForegroundRequired);
    } catch (/* ForegroundServiceStartNotAllowedException */ IllegalStateException e) {
      if ((SDK_INT >= 31) && Api31.instanceOfForegroundServiceStartNotAllowedException(e)) {
        Log.e(TAG, "Failed to start foreground", e);
        onForegroundServiceStartNotAllowedException();
        return false;
      }
      throw e;
    }
    return true;
  }

  private MediaNotificationManager getMediaNotificationManager() {
    return getMediaNotificationManager(/* initialMediaNotificationProvider= */ null);
  }

  private MediaNotificationManager getMediaNotificationManager(
      @Nullable MediaNotification.Provider initialMediaNotificationProvider) {
    if (mediaNotificationManager == null) {
      if (initialMediaNotificationProvider == null) {
        checkNotNull(getBaseContext(), "Accessing service context before onCreate()");
        initialMediaNotificationProvider =
            new DefaultMediaNotificationProvider.Builder(getApplicationContext()).build();
      }
      mediaNotificationManager =
          new MediaNotificationManager(
              /* mediaSessionService= */ this,
              initialMediaNotificationProvider,
              getActionFactory());
    }
    return mediaNotificationManager;
  }

  private DefaultActionFactory getActionFactory() {
    if (actionFactory == null) {
      actionFactory = new DefaultActionFactory(/* service= */ this);
    }
    return actionFactory;
  }

  @Nullable
  private Listener getListener() {
    synchronized (lock) {
      return this.listener;
    }
  }

  @RequiresApi(31)
  private void onForegroundServiceStartNotAllowedException() {
    mainHandler.post(
        () -> {
          @Nullable MediaSessionService.Listener serviceListener = getListener();
          if (serviceListener != null) {
            serviceListener.onForegroundServiceStartNotAllowedException();
          }
        });
  }

  private boolean isAnySessionPlaying() {
    List<MediaSession> sessionList = getSessions();
    for (int i = 0; i < sessionList.size(); i++) {
      if (sessionList.get(i).getPlayer().isPlaying()) {
        return true;
      }
    }
    return false;
  }

  private final class MediaSessionListener implements MediaSession.Listener {

    @Override
    public void onNotificationRefreshRequired(MediaSession session) {
      MediaSessionService.this.onUpdateNotificationInternal(
          session, /* startInForegroundWhenPaused= */ false);
    }

    @Override
    public boolean onPlayRequested(MediaSession session) {
      if (SDK_INT < 31 || SDK_INT >= 33) {
        return true;
      }
      // Check if service can start foreground successfully on Android 12 and 12L.
      if (!getMediaNotificationManager().isStartedInForeground()) {
        return onUpdateNotificationInternal(session, /* startInForegroundWhenPaused= */ true);
      }
      return true;
    }
  }

  private static final class MediaSessionServiceStub extends IMediaSessionService.Stub {

    private final WeakReference<MediaSessionService> serviceReference;
    private final Handler handler;
    private final Set<IMediaController> pendingControllers;

    public MediaSessionServiceStub(MediaSessionService serviceReference) {
      this.serviceReference = new WeakReference<>(serviceReference);
      Context context = serviceReference.getApplicationContext();
      handler = new Handler(context.getMainLooper());
      // ConcurrentHashMap has a bug in APIs 21-22 that can result in lost updates.
      pendingControllers = Collections.synchronizedSet(new HashSet<>());
    }

    @Override
    public void connect(
        @Nullable IMediaController caller, @Nullable Bundle connectionRequestBundle) {
      if (caller == null || connectionRequestBundle == null) {
        // Malformed call from potentially malicious controller.
        SessionUtil.disconnectIMediaController(caller);
        return;
      }
      ConnectionRequest request;
      try {
        request = ConnectionRequest.fromBundle(connectionRequestBundle);
      } catch (RuntimeException e) {
        // Malformed call from potentially malicious controller.
        Log.w(TAG, "Ignoring malformed Bundle for ConnectionRequest", e);
        SessionUtil.disconnectIMediaController(caller);
        return;
      }
      @Nullable MediaSessionService mediaSessionService = serviceReference.get();
      if (mediaSessionService == null) {
        SessionUtil.disconnectIMediaController(caller);
        return;
      }
      int callingPid = Binder.getCallingPid();
      int uid = Binder.getCallingUid();
      long token = Binder.clearCallingIdentity();
      int pid = (callingPid != 0) ? callingPid : request.pid;
      if (checkPackageValidity(mediaSessionService, request.packageName, uid) != PACKAGE_VALID) {
        Log.w(
            TAG,
            "Ignoring connection from invalid package name "
                + request.packageName
                + " (uid="
                + uid
                + ")");
        SessionUtil.disconnectIMediaController(caller);
        return;
      }
      MediaSessionManager.RemoteUserInfo remoteUserInfo =
          new MediaSessionManager.RemoteUserInfo(request.packageName, pid, uid);
      boolean isTrusted =
          MediaSessionManager.getSessionManager(mediaSessionService.getApplicationContext())
              .isTrustedForMediaControl(remoteUserInfo);
      pendingControllers.add(caller);
      try {
        handler.post(
            () -> {
              pendingControllers.remove(caller);
              boolean connected = false;
              try {
                @Nullable MediaSessionService service = serviceReference.get();
                if (service == null) {
                  return;
                }
                ControllerInfo controllerInfo =
                    new ControllerInfo(
                        remoteUserInfo,
                        request.libraryVersion,
                        request.controllerInterfaceVersion,
                        isTrusted,
                        new MediaSessionStub.Controller2Cb(
                            caller, request.controllerInterfaceVersion),
                        request.connectionHints,
                        request.maxCommandsForMediaItems,
                        /* isPackageNameVerified= */ true);

                @Nullable MediaSession session = service.onGetSession(controllerInfo);
                if (session == null) {
                  return;
                }
                service.addSession(session);
                session.handleControllerConnectionFromService(caller, controllerInfo);
                connected = true;
              } catch (Exception e) {
                // Don't propagate exception in service to the controller.
                Log.w(TAG, "Failed to add a session to session service", e);
              } finally {
                if (!connected) {
                  SessionUtil.disconnectIMediaController(caller);
                }
              }
            });
      } finally {
        Binder.restoreCallingIdentity(token);
      }
    }

    public void release() {
      serviceReference.clear();
      handler.removeCallbacksAndMessages(null);
      for (IMediaController controller : pendingControllers) {
        SessionUtil.disconnectIMediaController(controller);
      }
      pendingControllers.clear();
    }
  }

  @RequiresApi(31)
  private static final class Api31 {
    public static boolean instanceOfForegroundServiceStartNotAllowedException(
        IllegalStateException e) {
      return e instanceof ForegroundServiceStartNotAllowedException;
    }
  }
}
