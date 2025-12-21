/*
 * Copyright 2022 The Android Open Source Project
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
 * limitations under the License
 */
package androidx.media3.session;

import static android.os.Build.VERSION.SDK_INT;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.session.MediaSession.Token;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.Player;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Util;
import androidx.media3.session.MediaNotification.Provider.NotificationChannelInfo;
import androidx.media3.session.MediaSessionService.ShowNotificationForIdlePlayerMode;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

/**
 * Manages media notifications for a {@link MediaSessionService} and sets the service as
 * foreground/background according to the player state.
 *
 * <p>All methods must be called on the main thread.
 */
/* package */ final class MediaNotificationManager implements Handler.Callback {

  private static final String TAG = "MediaNtfMng";
  private static final int MSG_USER_ENGAGED_TIMEOUT = 1;
  private static final int SHUTDOWN_NOTIFICATION_ID = 20938;
  /* package */ static final String SELF_INTENT_UID_KEY = "androidx.media3.session.intent.uid";

  private final MediaSessionService mediaSessionService;

  private final MediaNotification.ActionFactory actionFactory;
  private final NotificationManager notificationManager;
  private final Handler mainHandler;
  private final Executor mainExecutor;
  private final Intent startSelfIntent;
  private final String startSelfIntentUid;
  private final Map<MediaSession, ControllerInfo> controllerMap;
  private final Map<MediaSession, MediaNotification> mediaNotifications;

  private MediaNotification.Provider mediaNotificationProvider;

  /**
   * The session that provides the notification the foreground service is started with, or null if
   * the service is not in foreground at the moment. Each service can only have one foreground
   * notification, so we have to switch this around as needed if we have multiple sessions.
   */
  private @Nullable MediaSession foregroundSession;

  private boolean isUserEngaged;
  private boolean isUserEngagedTimeoutEnabled;
  private long userEngagedTimeoutMs;
  @ShowNotificationForIdlePlayerMode int showNotificationForIdlePlayerMode;

  public MediaNotificationManager(
      MediaSessionService mediaSessionService,
      MediaNotification.Provider mediaNotificationProvider,
      MediaNotification.ActionFactory actionFactory) {
    this.mediaSessionService = mediaSessionService;
    this.mediaNotificationProvider = mediaNotificationProvider;
    this.actionFactory = actionFactory;
    notificationManager =
        checkNotNull(
            (NotificationManager)
                mediaSessionService.getSystemService(Context.NOTIFICATION_SERVICE));
    mainHandler = Util.createHandler(Looper.getMainLooper(), /* callback= */ this);
    mainExecutor = (runnable) -> Util.postOrRun(mainHandler, runnable);
    startSelfIntent = new Intent(mediaSessionService, mediaSessionService.getClass());
    startSelfIntentUid = UUID.randomUUID().toString();
    startSelfIntent.putExtra(SELF_INTENT_UID_KEY, startSelfIntentUid);
    controllerMap = new HashMap<>();
    mediaNotifications = new HashMap<>();
    isUserEngagedTimeoutEnabled = true;
    userEngagedTimeoutMs = MediaSessionService.DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS;
    showNotificationForIdlePlayerMode =
        MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_AFTER_STOP_OR_ERROR;
  }

  /**
   * Returns the UID that is set on the start self {@link Intent} as a string extra with key {@link
   * #SELF_INTENT_UID_KEY}.
   */
  /* package */ String getStartSelfIntentUid() {
    return startSelfIntentUid;
  }

  public void addSession(MediaSession session) {
    if (controllerMap.containsKey(session)) {
      return;
    }
    MediaControllerListener listener = new MediaControllerListener(mediaSessionService, session);
    Bundle connectionHints = new Bundle();
    connectionHints.putBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, true);
    ListenableFuture<MediaController> controllerFuture =
        new MediaController.Builder(mediaSessionService, session.getToken())
            .setConnectionHints(connectionHints)
            .setListener(listener)
            .setApplicationLooper(Looper.getMainLooper())
            .buildAsync();
    controllerMap.put(session, new ControllerInfo(controllerFuture));
    controllerFuture.addListener(
        () -> {
          try {
            MediaController controller = controllerFuture.get(/* time= */ 0, MILLISECONDS);
            listener.onConnected(shouldShowNotification(session));
            controller.addListener(listener);
          } catch (CancellationException
              | ExecutionException
              | InterruptedException
              | TimeoutException e) {
            // MediaSession or MediaController is released too early. Stop monitoring the session.
            mediaSessionService.removeSession(session);
          }
        },
        mainExecutor);
  }

  public void removeSession(MediaSession session) {
    @Nullable ControllerInfo controllerInfo = controllerMap.remove(session);
    if (controllerInfo != null) {
      // Update the notification count so that if a pending notification callback arrives (e.g., a
      // bitmap is loaded), we don't show a stale notification.
      controllerInfo.notificationSequence++;
      MediaController.releaseFuture(controllerInfo.controllerFuture);
    }
  }

  public void onCustomAction(MediaSession session, String action, Bundle extras) {
    @Nullable MediaController mediaController = getConnectedControllerForSession(session);
    if (mediaController == null) {
      return;
    }
    // Let the notification provider handle the command first before forwarding it directly.
    Util.postOrRun(
        new Handler(session.getPlayer().getApplicationLooper()),
        () -> {
          if (!mediaNotificationProvider.handleCustomCommand(session, action, extras)) {
            mainExecutor.execute(
                () -> sendCustomCommandIfCommandIsAvailable(mediaController, action, extras));
          }
        });
  }

  /**
   * Updates the media notification provider.
   *
   * @param mediaNotificationProvider The {@link MediaNotification.Provider}.
   */
  public void setMediaNotificationProvider(MediaNotification.Provider mediaNotificationProvider) {
    this.mediaNotificationProvider = mediaNotificationProvider;
  }

  /**
   * Updates the notification.
   *
   * @param session A session that needs notification update.
   * @param startInForegroundRequired Whether the service is required to start in the foreground.
   */
  public void updateNotification(MediaSession session, boolean startInForegroundRequired) {
    if (!mediaSessionService.isSessionAdded(session) || !shouldShowNotification(session)) {
      removeNotification(session);
      return;
    }

    ControllerInfo controllerInfo = checkNotNull(controllerMap.get(session));
    int notificationSequence = ++controllerInfo.notificationSequence;
    ImmutableList<CommandButton> mediaButtonPreferences =
        checkNotNull(getConnectedControllerForSession(session)).getMediaButtonPreferences();
    MediaNotification.Provider.Callback callback =
        notification ->
            mainExecutor.execute(
                () ->
                    onNotificationUpdated(
                        controllerInfo, notificationSequence, session, notification));
    Util.postOrRun(
        new Handler(session.getPlayer().getApplicationLooper()),
        () -> {
          MediaNotification mediaNotification =
              this.mediaNotificationProvider.createNotification(
                  session, mediaButtonPreferences, actionFactory, callback);
          checkState(
              /* expression= */ mediaNotification.notificationId != SHUTDOWN_NOTIFICATION_ID,
              /* errorMessage= */ "notification ID "
                  + SHUTDOWN_NOTIFICATION_ID
                  + " is already used internally.");
          mainExecutor.execute(
              () ->
                  updateNotificationInternal(
                      session, mediaNotification, startInForegroundRequired));
        });
  }

  public boolean isStartedInForeground() {
    return foregroundSession != null;
  }

  public void setUserEngagedTimeoutMs(long userEngagedTimeoutMs) {
    this.userEngagedTimeoutMs = userEngagedTimeoutMs;
  }

  public void setShowNotificationForIdlePlayer(
      @ShowNotificationForIdlePlayerMode int showNotificationForIdlePlayerMode) {
    this.showNotificationForIdlePlayerMode = showNotificationForIdlePlayerMode;
    List<MediaSession> sessions = mediaSessionService.getSessions();
    for (int i = 0; i < sessions.size(); i++) {
      mediaSessionService.onUpdateNotificationInternal(
          sessions.get(i), /* startInForegroundWhenPaused= */ false);
    }
  }

  @Override
  public boolean handleMessage(Message msg) {
    if (msg.what == MSG_USER_ENGAGED_TIMEOUT) {
      List<MediaSession> sessions = mediaSessionService.getSessions();
      for (int i = 0; i < sessions.size(); i++) {
        mediaSessionService.onUpdateNotificationInternal(
            sessions.get(i), /* startInForegroundWhenPaused= */ false);
      }
      return true;
    }
    return false;
  }

  /* package */ boolean shouldRunInForeground(boolean startInForegroundWhenPaused) {
    boolean isUserEngaged = isAnySessionUserEngaged(startInForegroundWhenPaused);
    boolean useTimeout = isUserEngagedTimeoutEnabled && userEngagedTimeoutMs > 0;
    if (this.isUserEngaged && !isUserEngaged && useTimeout) {
      mainHandler.sendEmptyMessageDelayed(MSG_USER_ENGAGED_TIMEOUT, userEngagedTimeoutMs);
    } else if (isUserEngaged) {
      mainHandler.removeMessages(MSG_USER_ENGAGED_TIMEOUT);
    }
    this.isUserEngaged = isUserEngaged;
    boolean hasPendingTimeout = mainHandler.hasMessages(MSG_USER_ENGAGED_TIMEOUT);
    return isUserEngaged || hasPendingTimeout;
  }

  private boolean isAnySessionUserEngaged(boolean startInForegroundWhenPaused) {
    List<MediaSession> sessions = mediaSessionService.getSessions();
    for (int i = 0; i < sessions.size(); i++) {
      @Nullable MediaController controller = getConnectedControllerForSession(sessions.get(i));
      if (controller != null
          && (controller.getPlayWhenReady() || startInForegroundWhenPaused)
          && (controller.getPlaybackState() == Player.STATE_READY
              || controller.getPlaybackState() == Player.STATE_BUFFERING)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Permanently disable the user engaged timeout, which is needed to immediately stop the
   * foreground service.
   */
  /* package */ void disableUserEngagedTimeout() {
    isUserEngagedTimeoutEnabled = false;
    if (mainHandler.hasMessages(MSG_USER_ENGAGED_TIMEOUT)) {
      mainHandler.removeMessages(MSG_USER_ENGAGED_TIMEOUT);
      List<MediaSession> sessions = mediaSessionService.getSessions();
      for (int i = 0; i < sessions.size(); i++) {
        mediaSessionService.onUpdateNotificationInternal(
            sessions.get(i), /* startInForegroundWhenPaused= */ false);
      }
    }
  }

  private void onNotificationUpdated(
      ControllerInfo controllerInfo,
      int notificationSequence,
      MediaSession session,
      MediaNotification mediaNotification) {
    if (controllerInfo.notificationSequence == notificationSequence) {
      boolean startInForegroundRequired =
          shouldRunInForeground(/* startInForegroundWhenPaused= */ false);
      updateNotificationInternal(session, mediaNotification, startInForegroundRequired);
    }
  }

  private void onNotificationDismissed(MediaSession session) {
    @Nullable ControllerInfo controllerInfo = controllerMap.get(session);
    if (controllerInfo != null) {
      controllerInfo.wasNotificationDismissed = true;
    }
  }

  private void updateNotificationInternal(
      MediaSession session,
      MediaNotification mediaNotification,
      boolean startInForegroundRequired) {
    // Call Notification.MediaStyle#setMediaSession() indirectly.
    Token fwkToken = session.getPlatformToken();
    mediaNotification.notification.extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, fwkToken);
    this.mediaNotifications.put(session, mediaNotification);
    if (startInForegroundRequired) {
      startForeground(session, mediaNotification);
    } else {
      // Notification manager has to be updated first to avoid missing updates
      // (https://github.com/androidx/media/issues/192).
      notify(mediaNotification);
      stopForeground(session, /* removeNotifications= */ false);
    }
  }

  /** Removes the notification and stops the foreground service if running. */
  private void removeNotification(MediaSession session) {
    // To hide the notification on all API levels, we need to call both Service.stopForeground(true)
    // and notificationManagerCompat.cancel(notificationId).
    stopForeground(session, /* removeNotifications= */ true);
    @Nullable MediaNotification mediaNotification = mediaNotifications.remove(session);
    if (mediaNotification != null) {
      notificationManager.cancel(mediaNotification.notificationId);
      // Update the notification count so that if a pending notification callback arrives (e.g., a
      // bitmap is loaded), we don't show the notification.
      @Nullable ControllerInfo controllerInfo = controllerMap.get(session);
      // If the controllerInfo is already removed from the map, removeSession() has increased the
      // sequence, so it's fine.
      if (controllerInfo != null) {
        controllerInfo.notificationSequence++;
      }
    }
  }

  private boolean shouldShowNotification(MediaSession session) {
    MediaController controller = getConnectedControllerForSession(session);
    if (controller == null || controller.getCurrentTimeline().isEmpty()) {
      return false;
    }
    ControllerInfo controllerInfo = checkNotNull(controllerMap.get(session));
    if (controller.getPlaybackState() != Player.STATE_IDLE) {
      // Playback first prepared or restarted, reset previous notification dismissed flag.
      controllerInfo.wasNotificationDismissed = false;
      controllerInfo.hasBeenPrepared = true;
      return true;
    }
    switch (showNotificationForIdlePlayerMode) {
      case MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS:
        return !controllerInfo.wasNotificationDismissed;
      case MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER:
        return false;
      case MediaSessionService.SHOW_NOTIFICATION_FOR_IDLE_PLAYER_AFTER_STOP_OR_ERROR:
        return !controllerInfo.wasNotificationDismissed && controllerInfo.hasBeenPrepared;
      default:
        throw new IllegalStateException();
    }
  }

  @Nullable
  private MediaController getConnectedControllerForSession(MediaSession session) {
    @Nullable ControllerInfo controllerInfo = controllerMap.get(session);
    if (controllerInfo == null || !controllerInfo.controllerFuture.isDone()) {
      return null;
    }
    try {
      return Futures.getDone(controllerInfo.controllerFuture);
    } catch (ExecutionException exception) {
      // We should never reach this.
      throw new IllegalStateException(exception);
    }
  }

  private void sendCustomCommandIfCommandIsAvailable(
      MediaController mediaController, String action, Bundle extras) {
    @Nullable SessionCommand customCommand = null;
    for (SessionCommand command : mediaController.getAvailableSessionCommands().commands) {
      if (command.commandCode == SessionCommand.COMMAND_CODE_CUSTOM
          && command.customAction.equals(action)) {
        customCommand = command;
        break;
      }
    }
    if (customCommand != null || CommandButton.isPredefinedCustomCommandButtonCode(action)) {
      ListenableFuture<SessionResult> future =
          mediaController.sendCustomCommand(new SessionCommand(action, extras), /* args= */ extras);
      Futures.addCallback(
          future,
          new FutureCallback<SessionResult>() {
            @Override
            public void onSuccess(SessionResult result) {
              // Do nothing.
            }

            @Override
            public void onFailure(Throwable t) {
              Log.w(TAG, "custom command " + action + " produced an error: " + t.getMessage(), t);
            }
          },
          MoreExecutors.directExecutor());
    }
  }

  /* package */ Pair<Integer, Notification> createShutdownNotification(Context context) {
    NotificationChannelInfo notificationChannelInfo =
        mediaNotificationProvider.getNotificationChannelInfo();
    // Ensure notification channel exists
    Util.ensureNotificationChannel(
        notificationManager, notificationChannelInfo.getId(), notificationChannelInfo.getName());
    NotificationCompat.Builder builder =
        new NotificationCompat.Builder(context, notificationChannelInfo.getId());
    if (SDK_INT >= 31) {
      builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED);
    }
    Notification notification =
        builder
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.media3_notification_small_icon)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(false)
            .build();
    return new Pair<>(/* notificationId */ SHUTDOWN_NOTIFICATION_ID, notification);
  }

  private final class MediaControllerListener implements MediaController.Listener, Player.Listener {
    private final MediaSessionService mediaSessionService;
    private final MediaSession session;

    public MediaControllerListener(MediaSessionService mediaSessionService, MediaSession session) {
      this.mediaSessionService = mediaSessionService;
      this.session = session;
    }

    public void onConnected(boolean shouldShowNotification) {
      if (shouldShowNotification) {
        mediaSessionService.onUpdateNotificationInternal(
            session, /* startInForegroundWhenPaused= */ false);
      }
    }

    @Override
    public ListenableFuture<SessionResult> onCustomCommand(
        MediaController controller, SessionCommand command, Bundle args) {
      @SessionResult.Code int resultCode = SessionError.ERROR_NOT_SUPPORTED;
      if (command.customAction.equals(MediaNotification.NOTIFICATION_DISMISSED_EVENT_KEY)) {
        onNotificationDismissed(session);
        resultCode = SessionResult.RESULT_SUCCESS;
      }
      return Futures.immediateFuture(new SessionResult(resultCode));
    }

    @Override
    public void onDisconnected(MediaController controller) {
      if (mediaSessionService.isSessionAdded(session)) {
        mediaSessionService.removeSession(session);
      }
      // We may need to hide the notification.
      mediaSessionService.onUpdateNotificationInternal(
          session, /* startInForegroundWhenPaused= */ false);
    }
  }

  @SuppressLint("InlinedApi") // Using compile time constant FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
  private void startForeground(MediaSession session, MediaNotification newMediaNotification) {
    MediaNotification mediaNotification;
    boolean hasOtherForegroundSession;
    if (foregroundSession != null && foregroundSession != session) {
      // If we would use the newly updated notification, the old foreground session's notification
      // would be canceled by the system because the service switched to another foreground service
      // notification. However, we don't want to cancel it, so we just keep using the old session's
      // notification as long as we can, and post our new notification as normal notification.
      // We still need to call startForeground() in any case to avoid
      // ForegroundServiceDidNotStartInTimeException.
      mediaNotification = checkNotNull(mediaNotifications.get(foregroundSession));
      hasOtherForegroundSession = true;
    } else {
      mediaNotification = newMediaNotification;
      foregroundSession = session;
      hasOtherForegroundSession = false;
    }
    ContextCompat.startForegroundService(mediaSessionService, startSelfIntent);
    Util.setForegroundServiceNotification(
        mediaSessionService,
        mediaNotification.notificationId,
        mediaNotification.notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        "mediaPlayback");
    if (hasOtherForegroundSession) {
      // startForeground() normally does this, but as we passed another notification to it, we have
      // to do it manually here.
      notify(newMediaNotification);
    }
  }

  private void stopForeground(MediaSession session, boolean removeNotifications) {
    if (foregroundSession != session) {
      // This happens if there is another session in foreground since before this session wanted to
      // go in the foreground. As this other session still wants to be in the foreground, we don't
      // need to stop the foreground service. cancel() will be called by the caller if
      // removeNotifications is true, so we don't need to do anything here.
      return;
    }
    foregroundSession = null;
    if (shouldRunInForeground(false)) {
      // If we shouldn't show a notification for this session anymore, but there's another session
      // and it wants to stay in foreground, we have to change the foreground service notification
      // to the other session.
      List<MediaSession> sessions = mediaSessionService.getSessions();
      for (MediaSession candidateSession : sessions) {
        // Just take the first one willing to show a notification, it doesn't matter which one.
        if (shouldShowNotification(candidateSession)) {
          startForeground(candidateSession, checkNotNull(mediaNotifications.get(candidateSession)));
          // When calling startForeground() with a different notification ID, the old notification
          // will be canceled by the system. It's an unfortunate limitation of the API we can't do
          // anything about. Hence, if removeNotifications is false, we have to send the
          // notification out again using notify() as we didn't want it to be canceled.
          if (!removeNotifications) {
            notify(checkNotNull(mediaNotifications.get(session)));
          }
          return;
        }
      }
    }
    // Either we don't have any notification left, or we don't want to stay in foreground. It's
    // time to stop the foreground service.
    Util.stopForeground(mediaSessionService, removeNotifications);
  }

  // POST_NOTIFICATIONS permission is not required for media session related notifications.
  // https://developer.android.com/develop/ui/views/notifications/notification-permission#exemptions-media-sessions
  @SuppressLint("MissingPermission")
  private void notify(MediaNotification notification) {
    notificationManager.notify(notification.notificationId, notification.notification);
  }

  private static final class ControllerInfo {

    public final ListenableFuture<MediaController> controllerFuture;

    /** Indicates whether the user actively dismissed the notification. */
    public boolean wasNotificationDismissed;

    /** Indicated whether the player has ever been prepared. */
    public boolean hasBeenPrepared;

    /** The notification sequence number. */
    public int notificationSequence;

    public ControllerInfo(ListenableFuture<MediaController> controllerFuture) {
      this.controllerFuture = controllerFuture;
    }
  }
}
