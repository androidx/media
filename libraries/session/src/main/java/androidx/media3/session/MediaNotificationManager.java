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
import android.app.ForegroundServiceStartNotAllowedException;
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
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.Player;
import androidx.media3.common.util.BackgroundExecutor;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.Util;
import androidx.media3.session.MediaNotification.Provider.NotificationChannelInfo;
import androidx.media3.session.MediaSessionService.ShowNotificationForIdlePlayerMode;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages media notifications for a {@link MediaSessionService} and sets the service as
 * foreground/background according to the player state.
 */
/* package */ final class MediaNotificationManager implements Handler.Callback {

  private static final String TAG = "MediaNtfMng";
  private static final int MSG_USER_ENGAGED_TIMEOUT = 1;
  private static final int SHUTDOWN_NOTIFICATION_ID = 20938;
  /* package */ static final String SELF_INTENT_UID_KEY = "androidx.media3.session.intent.uid";

  private final MediaSessionService mediaSessionService;

  private final Object lock;
  private final MediaNotification.ActionFactory actionFactory;
  private final NotificationManager notificationManager;
  private final Handler mainHandler;
  private final Intent startSelfIntent;
  private final String startSelfIntentUid;
  private final Map<MediaSession, ControllerInfo> controllerMap; // write only on main

  @GuardedBy("#lock")
  private final Map<MediaSession, MediaNotification> mediaNotifications;

  private volatile MediaNotification.Provider mediaNotificationProvider;

  /**
   * The session that provides the notification the foreground service is started with, or null if
   * the service is not in foreground at the moment. Each service can only have one foreground
   * notification, so we have to switch this around as needed if we have multiple sessions.
   */
  @GuardedBy("#lock")
  private @Nullable MediaSession foregroundSession;

  @GuardedBy("#lock")
  private boolean isUserEngaged;

  @GuardedBy("#lock")
  private boolean isUserEngagedTimeoutEnabled;

  @GuardedBy("#lock")
  private long userEngagedTimeoutMs;

  @GuardedBy("#lock")
  private boolean notificationsEnabled;

  @ShowNotificationForIdlePlayerMode volatile int showNotificationForIdlePlayerMode;

  public MediaNotificationManager(
      MediaSessionService mediaSessionService,
      MediaNotification.Provider mediaNotificationProvider,
      MediaNotification.ActionFactory actionFactory) {
    this.mediaSessionService = mediaSessionService;
    this.mediaNotificationProvider = mediaNotificationProvider;
    this.actionFactory = actionFactory;
    lock = new Object();
    notificationManager =
        checkNotNull(
            (NotificationManager)
                mediaSessionService.getSystemService(Context.NOTIFICATION_SERVICE));
    mainHandler = Util.createHandler(Looper.getMainLooper(), /* callback= */ this);
    startSelfIntent = new Intent(mediaSessionService, mediaSessionService.getClass());
    startSelfIntentUid = UUID.randomUUID().toString();
    startSelfIntent.putExtra(SELF_INTENT_UID_KEY, startSelfIntentUid);
    controllerMap = new HashMap<>();
    mediaNotifications = new HashMap<>();
    isUserEngagedTimeoutEnabled = true;
    notificationsEnabled = true;
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

  // This method will be called on the main thread.
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
            .setApplicationLooper(checkNotNull(session.getBackgroundLooper()))
            .buildAsync();
    Handler playerHandler = new Handler(session.getPlayer().getApplicationLooper());
    Handler backgroundHandler = new Handler(checkNotNull(session.getBackgroundLooper()));
    controllerMap.put(
        session, new ControllerInfo(controllerFuture, playerHandler, backgroundHandler));
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
        r -> Util.postOrRun(backgroundHandler, r));
  }

  // This method will be called on the main thread.
  public void removeSession(MediaSession session) {
    @Nullable ControllerInfo controllerInfo = controllerMap.remove(session);
    if (controllerInfo != null) {
      // Update the notification count so that if a pending notification callback arrives (e.g., a
      // bitmap is loaded), we don't show a stale notification.
      controllerInfo.notificationSequence++;
      controllerInfo.backgroundHandler.post(
          () -> MediaController.releaseFuture(controllerInfo.controllerFuture));
    }
  }

  // This method will be called on the main thread.
  public void onCustomAction(MediaSession session, String action, Bundle extras) {
    @Nullable ControllerInfo controllerInfo = getConnectedControllerInfoForSession(session);
    if (controllerInfo == null) {
      return;
    }
    MediaController mediaController = getControllerForControllerInfo(controllerInfo);
    // Let the notification provider handle the command first before forwarding it directly.
    Util.postOrRun(
        controllerInfo.playerHandler,
        () -> {
          if (!mediaNotificationProvider.handleCustomCommand(session, action, extras)) {
            Util.postOrRun(
                controllerInfo.backgroundHandler,
                () -> sendCustomCommandIfCommandIsAvailable(mediaController, action, extras));
          }
        });
  }

  /**
   * Updates the media notification provider.
   *
   * <p>This method will be called on the main thread.
   *
   * @param mediaNotificationProvider The {@link MediaNotification.Provider}.
   */
  public void setMediaNotificationProvider(MediaNotification.Provider mediaNotificationProvider) {
    this.mediaNotificationProvider = mediaNotificationProvider;
  }

  /**
   * Updates the notification.
   *
   * <p>This method can be called on any thread.
   *
   * @param session A session that needs notification update.
   * @param startInForegroundRequired Whether the service is required to start in the foreground.
   * @return Future is set to false if a {@link ForegroundServiceStartNotAllowedException} prevented
   *     starting the foreground service, otherwise (even if no start attempted) true.
   */
  public ListenableFuture<Boolean> updateNotification(
      MediaSession session, boolean startInForegroundRequired) {
    @Nullable ControllerInfo controllerInfo = getConnectedControllerInfoForSession(session);
    SettableFuture<Boolean> completion = SettableFuture.create();
    boolean updateStarted = false;
    if (controllerInfo != null) {
      updateStarted =
          Util.postOrRun(
              controllerInfo.backgroundHandler,
              () -> {
                if (!mediaSessionService.isSessionAdded(session)
                    || !shouldShowNotification(session)) {
                  removeNotification(session, startInForegroundRequired);
                  completion.set(true);
                  return;
                }

                int notificationSequence = ++controllerInfo.notificationSequence;
                ImmutableList<CommandButton> mediaButtonPreferences =
                    getControllerForControllerInfo(controllerInfo).getMediaButtonPreferences();
                MediaNotification.Provider.Callback callback =
                    notification ->
                        Util.postOrRun(
                            controllerInfo.backgroundHandler,
                            () ->
                                onNotificationUpdated(
                                    controllerInfo, notificationSequence, session, notification));
                Util.postOrRun(
                    controllerInfo.playerHandler,
                    () -> {
                      MediaNotification mediaNotification =
                          this.mediaNotificationProvider.createNotification(
                              session, mediaButtonPreferences, actionFactory, callback);
                      checkState(
                          /* expression= */ mediaNotification.notificationId
                              != SHUTDOWN_NOTIFICATION_ID,
                          /* errorMessage= */ "notification ID "
                              + SHUTDOWN_NOTIFICATION_ID
                              + " is already used internally.");
                      Util.postOrRun(
                          controllerInfo.backgroundHandler,
                          () ->
                              completion.set(
                                  updateNotificationInternal(
                                      session, mediaNotification, startInForegroundRequired)));
                    });
              });
    }
    if (!updateStarted) {
      postToControllerBackground(
          controllerInfo,
          session,
          () -> {
            removeNotification(session, startInForegroundRequired);
            completion.set(true);
          });
    }
    return completion;
  }

  private void postToControllerBackground(
      @Nullable ControllerInfo controllerInfo, MediaSession session, Runnable r) {
    @Nullable Handler bgHandler = controllerInfo != null ? controllerInfo.backgroundHandler : null;
    if (bgHandler == null) {
      @Nullable Looper bgLooper = session.getBackgroundLooper();
      if (bgLooper != null) {
        bgHandler = new Handler(bgLooper);
      }
    }
    if (bgHandler == null || !bgHandler.post(r)) {
      // If we end up here, the thread already quit. We should use another thread as stand-in.
      BackgroundExecutor.get().execute(r);
    }
  }

  // This method can be called on any thread.
  public boolean isStartedInForeground() {
    synchronized (lock) {
      return foregroundSession != null;
    }
  }

  // This method will be called on the main thread.
  public void setUserEngagedTimeoutMs(long userEngagedTimeoutMs) {
    synchronized (lock) {
      this.userEngagedTimeoutMs = userEngagedTimeoutMs;
    }
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

  // This method can be called on any thread.
  /* package */ ListenableFuture<Boolean> shouldRunInForeground(
      boolean startInForegroundWhenPaused) {
    SettableFuture<Boolean> completion = SettableFuture.create();
    ListenableFuture<Boolean> isUserEngagedFuture =
        isAnySessionUserEngaged(startInForegroundWhenPaused);
    isUserEngagedFuture.addListener(
        () -> {
          boolean isUserEngaged;
          try {
            isUserEngaged = isUserEngagedFuture.get();
          } catch (ExecutionException | InterruptedException e) {
            throw new IllegalStateException(e);
          }
          synchronized (lock) {
            boolean useTimeout = isUserEngagedTimeoutEnabled && userEngagedTimeoutMs > 0;
            if (this.isUserEngaged && !isUserEngaged && useTimeout) {
              mainHandler.sendEmptyMessageDelayed(MSG_USER_ENGAGED_TIMEOUT, userEngagedTimeoutMs);
            } else if (isUserEngaged) {
              mainHandler.removeMessages(MSG_USER_ENGAGED_TIMEOUT);
            }
            this.isUserEngaged = isUserEngaged;
          }
          boolean hasPendingTimeout = mainHandler.hasMessages(MSG_USER_ENGAGED_TIMEOUT);
          completion.set(isUserEngaged || hasPendingTimeout);
        },
        MoreExecutors.directExecutor());
    return completion;
  }

  // This method can be called on any thread.
  private ListenableFuture<Boolean> isAnySessionUserEngaged(boolean startInForegroundWhenPaused) {
    List<MediaSession> sessions = mediaSessionService.getSessions();
    synchronized (lock) {
      if (!notificationsEnabled || sessions.isEmpty()) {
        return Futures.immediateFuture(false);
      }
    }
    SettableFuture<Boolean> outputFuture = SettableFuture.create();
    List<@NullableType ListenableFuture<Boolean>> sessionFutures = new ArrayList<>();
    final AtomicInteger resultCount = new AtomicInteger(0);
    Runnable handleSessionFuturesTask =
        () -> {
          int completedSessionFutureCount = resultCount.incrementAndGet();
          if (completedSessionFutureCount == sessions.size()) {
            boolean value = false;
            for (@NullableType ListenableFuture<Boolean> future : sessionFutures) {
              if (future != null) {
                try {
                  value = future.get();
                } catch (ExecutionException | InterruptedException e) {
                  throw new RuntimeException(e);
                }
                if (value) {
                  break;
                }
              }
            }
            outputFuture.set(value);
          }
        };

    for (int i = 0; i < sessions.size(); i++) {
      MediaSession session = sessions.get(i);
      @Nullable ControllerInfo controllerInfo = getConnectedControllerInfoForSession(session);
      if (controllerInfo != null) {
        MediaController controller = getControllerForControllerInfo(controllerInfo);
        SettableFuture<Boolean> completion = SettableFuture.create();
        Util.postOrRun(
            controllerInfo.backgroundHandler,
            () ->
                completion.set(
                    (controller.getPlayWhenReady() || startInForegroundWhenPaused)
                        && (controller.getPlaybackState() == Player.STATE_READY
                            || controller.getPlaybackState() == Player.STATE_BUFFERING)));
        sessionFutures.add(completion);
        completion.addListener(handleSessionFuturesTask, MoreExecutors.directExecutor());
      } else {
        sessionFutures.add(null);
        handleSessionFuturesTask.run();
      }
    }
    return outputFuture;
  }

  /**
   * Permanently disable the user engaged timeout, which is needed to immediately stop the
   * foreground service.
   *
   * <p>This method will be called on the main thread.
   */
  /* package */ void disableUserEngagedTimeout() {
    synchronized (lock) {
      isUserEngagedTimeoutEnabled = false;
    }
    if (mainHandler.hasMessages(MSG_USER_ENGAGED_TIMEOUT)) {
      mainHandler.removeMessages(MSG_USER_ENGAGED_TIMEOUT);
      List<MediaSession> sessions = mediaSessionService.getSessions();
      for (int i = 0; i < sessions.size(); i++) {
        mediaSessionService.onUpdateNotificationInternal(
            sessions.get(i), /* startInForegroundWhenPaused= */ false);
      }
    }
  }

  /**
   * Permanently disable both the user engaged timeout and posting new notifications, immediately
   * stop the foreground service and cancel all media notifications.
   *
   * <p>This method will be called on the main thread.
   */
  /* package */ void stopForegroundServiceAndDisableNotifications() {
    synchronized (lock) {
      isUserEngagedTimeoutEnabled = false;
      notificationsEnabled = false;
      if (mainHandler.hasMessages(MSG_USER_ENGAGED_TIMEOUT)) {
        mainHandler.removeMessages(MSG_USER_ENGAGED_TIMEOUT);
      }
      List<MediaSession> sessions = mediaSessionService.getSessions();
      for (int i = 0; i < sessions.size(); i++) {
        removeNotification(sessions.get(i), false);
      }
    }
  }

  // Will be called on controller's application thread.
  private void onNotificationUpdated(
      ControllerInfo controllerInfo,
      int notificationSequence,
      MediaSession session,
      MediaNotification mediaNotification) {
    if (controllerInfo.notificationSequence == notificationSequence) {
      ListenableFuture<Boolean> startInForegroundRequiredFuture =
          shouldRunInForeground(/* startInForegroundWhenPaused= */ false);
      startInForegroundRequiredFuture.addListener(
          () -> {
            boolean startInForegroundRequired;
            try {
              startInForegroundRequired = startInForegroundRequiredFuture.get();
            } catch (ExecutionException | InterruptedException e) {
              throw new RuntimeException(e);
            }
            updateNotificationInternal(session, mediaNotification, startInForegroundRequired);
          },
          r -> Util.postOrRun(controllerInfo.backgroundHandler, r));
    }
  }

  // Will be called on controller's application thread.
  private void onNotificationDismissed(MediaSession session) {
    @Nullable ControllerInfo controllerInfo = controllerMap.get(session);
    if (controllerInfo != null) {
      controllerInfo.wasNotificationDismissed = true;
    }
  }

  // Will be called on controller's application thread.
  private boolean updateNotificationInternal(
      MediaSession session,
      MediaNotification mediaNotification,
      boolean startInForegroundRequired) {
    // Call Notification.MediaStyle#setMediaSession() indirectly.
    Token fwkToken = session.getPlatformToken();
    mediaNotification.notification.extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, fwkToken);
    synchronized (lock) {
      if (!notificationsEnabled) {
        return true;
      }
      this.mediaNotifications.put(session, mediaNotification);
      if (startInForegroundRequired) {
        return startForeground(session, mediaNotification);
      } else {
        // Notification manager has to be updated first to avoid missing updates
        // (https://github.com/androidx/media/issues/192).
        notify(mediaNotification);
        stopForeground(session, /* removeNotifications= */ false, false);
        return true;
      }
    }
  }

  /** Removes the notification and stops the foreground service if running. */
  // Can be called on any thread.
  private void removeNotification(MediaSession session, boolean startInForegroundRequired) {
    synchronized (lock) {
      // To hide the notification on all API levels, we need to call both
      // Service.stopForeground(true)
      // and notificationManagerCompat.cancel(notificationId).
      stopForeground(session, /* removeNotifications= */ true, startInForegroundRequired);
      @Nullable MediaNotification mediaNotification = mediaNotifications.remove(session);
      if (mediaNotification != null) {
        notificationManager.cancel(mediaNotification.notificationId);
        @Nullable ControllerInfo controllerInfo = controllerMap.get(session);
        // If the controllerInfo is already removed from the map, removeSession() has increased the
        // sequence, so it's fine.
        if (controllerInfo != null) {
          // Update the notification count so that if a pending notification callback arrives (e.g.,
          // a bitmap is loaded), we don't show the notification.
          controllerInfo.notificationSequence++;
        }
      }
    }
  }

  // Will be called on controller's application thread.
  private boolean shouldShowNotification(MediaSession session) {
    synchronized (lock) {
      if (!notificationsEnabled) {
        return false;
      }
    }
    ControllerInfo controllerInfo = getConnectedControllerInfoForSession(session);
    if (controllerInfo == null) {
      return false;
    }
    MediaController controller = getControllerForControllerInfo(controllerInfo);
    if (controller.getCurrentTimeline().isEmpty()) {
      return false;
    }
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
  private ControllerInfo getConnectedControllerInfoForSession(MediaSession session) {
    @Nullable ControllerInfo controllerInfo = controllerMap.get(session);
    if (controllerInfo == null || !controllerInfo.controllerFuture.isDone()) {
      return null;
    }
    return controllerInfo;
  }

  private static MediaController getControllerForControllerInfo(ControllerInfo controllerInfo) {
    if (!controllerInfo.controllerFuture.isDone()) {
      // This is checked in getConnectedControllerInfoForSession() already, so can't happen.
      throw new IllegalArgumentException("controllerInfo is not done");
    }
    try {
      return Futures.getDone(controllerInfo.controllerFuture);
    } catch (ExecutionException exception) {
      // We should never reach this.
      throw new IllegalStateException(exception);
    }
  }

  // Will be called on controller's application thread.
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
    public void onMediaButtonPreferencesChanged(
        MediaController controller, List<CommandButton> mediaButtonPreferences) {
      mediaSessionService.onUpdateNotificationInternal(
          session, /* startInForegroundWhenPaused= */ false);
    }

    // Note: Because setAvailableCommands can be called from any thread and processing the updated
    // state requires executing code on both player and background thread, MediaSessionLegacyStub is
    // responsible for manually triggering a refresh of the notification instead of this being
    // handled here.

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

    @Override
    public void onEvents(Player player, Player.Events events) {
      // We must limit the frequency of notification updates, otherwise the system may suppress
      // them.
      if (events.containsAny(
          Player.EVENT_PLAYBACK_STATE_CHANGED,
          Player.EVENT_PLAY_WHEN_READY_CHANGED,
          Player.EVENT_MEDIA_METADATA_CHANGED,
          Player.EVENT_TIMELINE_CHANGED,
          Player.EVENT_DEVICE_INFO_CHANGED)) {
        mediaSessionService.onUpdateNotificationInternal(
            session, /* startInForegroundWhenPaused= */ false);
      }
    }
  }

  @GuardedBy("#lock")
  @SuppressLint("InlinedApi") // Using compile time constant FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
  private boolean startForeground(MediaSession session, MediaNotification newMediaNotification) {
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
      hasOtherForegroundSession = false;
    }
    try {
      ContextCompat.startForegroundService(mediaSessionService, startSelfIntent);
      Util.setForegroundServiceNotification(
          mediaSessionService,
          mediaNotification.notificationId,
          mediaNotification.notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
          "mediaPlayback");
    } catch (IllegalStateException e) {
      if (SDK_INT >= 31 && Api31.instanceOfForegroundServiceStartNotAllowedException(e)) {
        Log.e(TAG, "Failed to start foreground", e);
        return false;
      }
      throw e;
    }
    if (hasOtherForegroundSession) {
      // startForeground() normally does this, but as we passed another notification to it, we have
      // to do it manually here.
      notify(newMediaNotification);
    } else {
      foregroundSession = session;
    }
    return true;
  }

  @GuardedBy("#lock")
  private void stopForeground(
      MediaSession session, boolean removeNotifications, boolean startInForegroundRequired) {
    if (foregroundSession != session) {
      // This happens if there is another session in foreground since before this session wanted to
      // go in the foreground. As this other session still wants to be in the foreground, we don't
      // need to stop the foreground service. cancel() will be called by the caller if
      // removeNotifications is true, so we don't need to do anything here.
      return;
    }
    foregroundSession = null;
    if (startInForegroundRequired) {
      // If we shouldn't show a notification for this session anymore, but there's another session
      // and it wants to stay in foreground, we have to change the foreground service notification
      // to the other session.
      List<MediaSession> sessions = mediaSessionService.getSessions();
      for (MediaSession candidateSession : sessions) {
        // Just take the first one willing to show a notification, it doesn't matter which one.
        if (shouldShowNotification(candidateSession)) {
          // Because we are already a foreground service, this should not fail.
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
    public final Handler playerHandler;
    public final Handler backgroundHandler;

    /** Indicates whether the user actively dismissed the notification. */
    public boolean wasNotificationDismissed;

    /** Indicated whether the player has ever been prepared. */
    public boolean hasBeenPrepared;

    /** The notification sequence number. */
    public int notificationSequence;

    public ControllerInfo(
        ListenableFuture<MediaController> controllerFuture,
        Handler playerHandler,
        Handler backgroundHandler) {
      this.controllerFuture = controllerFuture;
      this.playerHandler = playerHandler;
      this.backgroundHandler = backgroundHandler;
    }
  }

  @RequiresApi(31)
  /* package */ static final class Api31 {
    public static boolean instanceOfForegroundServiceStartNotAllowedException(
        IllegalStateException e) {
      return e instanceof ForegroundServiceStartNotAllowedException;
    }
  }
}
