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

import static android.view.KeyEvent.KEYCODE_HEADSETHOOK;
import static android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
import static android.view.KeyEvent.KEYCODE_MEDIA_NEXT;
import static android.view.KeyEvent.KEYCODE_MEDIA_PAUSE;
import static android.view.KeyEvent.KEYCODE_MEDIA_PLAY;
import static android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
import static android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS;
import static android.view.KeyEvent.KEYCODE_MEDIA_REWIND;
import static android.view.KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD;
import static android.view.KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD;
import static android.view.KeyEvent.KEYCODE_MEDIA_STOP;
import static androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS;
import static androidx.media3.common.Player.COMMAND_SET_MEDIA_ITEM;
import static androidx.media3.common.util.Util.postOrRun;
import static androidx.media3.session.MediaSessionStub.UNKNOWN_SEQUENCE_NUMBER;
import static androidx.media3.session.SessionError.ERROR_SESSION_DISCONNECTED;
import static androidx.media3.session.SessionError.ERROR_UNKNOWN;
import static androidx.media3.session.SessionError.INFO_CANCELLED;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Math.max;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.media.session.MediaSession.Token;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import androidx.annotation.CheckResult;
import androidx.annotation.FloatRange;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Player.DiscontinuityReason;
import androidx.media3.common.Player.PositionInfo;
import androidx.media3.common.Player.RepeatMode;
import androidx.media3.common.Rating;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.BitmapLoader;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.session.MediaSession.ControllerCb;
import androidx.media3.session.MediaSession.ControllerInfo;
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition;
import androidx.media3.session.SequencedFutureManager.SequencedFuture;
import androidx.media3.session.legacy.MediaBrowserServiceCompat;
import androidx.media3.session.legacy.MediaSessionCompat;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import org.checkerframework.checker.initialization.qual.Initialized;

/* package */ class MediaSessionImpl {

  private static final String ANDROID_AUTOMOTIVE_LAUNCHER_PACKAGE_NAME =
      "com.android.car.carlauncher";
  private static final String ANDROID_AUTOMOTIVE_MEDIA_PACKAGE_NAME = "com.android.car.media";
  private static final String ANDROID_AUTO_PACKAGE_NAME = "com.google.android.projection.gearhead";
  private static final String SYSTEM_UI_PACKAGE_NAME = "com.android.systemui";
  private static final String WRONG_THREAD_ERROR_MESSAGE =
      "Player callback method is called from a wrong thread. "
          + "See javadoc of MediaSession for details.";

  private static final long DEFAULT_SESSION_POSITION_UPDATE_DELAY_MS = 3_000;

  public static final String TAG = "MediaSessionImpl";

  private static final SessionResult RESULT_WHEN_CLOSED = new SessionResult(INFO_CANCELLED);
  private static final String SESSION_URI_SCHEME = "androidx";
  private static final String SESSION_URI_AUTHORITY = "media3.session";

  private final Object lock = new Object();

  private final Uri sessionUri;
  private final PlayerInfoChangedHandler onPlayerInfoChangedHandler;
  private final MediaPlayPauseKeyHandler mediaPlayPauseKeyHandler;
  private final MediaSession.Callback callback;
  private final Context context;
  private final MediaSessionStub sessionStub;
  private final MediaSessionLegacyStub sessionLegacyStub;
  private final String sessionId;
  private final SessionToken sessionToken;
  private final MediaSession instance;
  private final Handler applicationHandler;
  private final BitmapLoader bitmapLoader;
  private final Runnable periodicSessionPositionInfoUpdateRunnable;
  private final Handler mainHandler;
  private final boolean playIfSuppressed;
  private final boolean isPeriodicPositionUpdateEnabled;
  private final boolean useLegacySurfaceHandling;
  private final ImmutableList<CommandButton> commandButtonsForMediaItems;

  private PlayerInfo playerInfo;
  private PlayerWrapper playerWrapper;
  @Nullable private PendingIntent sessionActivity;
  @Nullable private PlayerListener playerListener;
  @Nullable private MediaSession.Listener mediaSessionListener;
  @Nullable private ControllerInfo controllerForCurrentRequest;

  @GuardedBy("lock")
  @Nullable
  private MediaSessionServiceLegacyStub browserServiceLegacyStub;

  @GuardedBy("lock")
  private boolean closed;

  // Should be only accessed on the application looper
  private long sessionPositionUpdateDelayMs;
  private boolean isMediaNotificationControllerConnected;
  private ImmutableList<CommandButton> customLayout;
  private ImmutableList<CommandButton> mediaButtonPreferences;
  private Bundle sessionExtras;
  @Nullable private PlaybackException playbackException;

  @SuppressWarnings("argument.type.incompatible") // Using this in System.identityHashCode
  public MediaSessionImpl(
      MediaSession instance,
      Context context,
      String id,
      Player player,
      @Nullable PendingIntent sessionActivity,
      ImmutableList<CommandButton> customLayout,
      ImmutableList<CommandButton> mediaButtonPreferences,
      ImmutableList<CommandButton> commandButtonsForMediaItems,
      MediaSession.Callback callback,
      Bundle tokenExtras,
      Bundle sessionExtras,
      BitmapLoader bitmapLoader,
      boolean playIfSuppressed,
      boolean isPeriodicPositionUpdateEnabled,
      boolean useLegacySurfaceHandling) {
    Log.i(
        TAG,
        "Init "
            + Integer.toHexString(System.identityHashCode(this))
            + " ["
            + MediaLibraryInfo.VERSION_SLASHY
            + "] ["
            + Util.DEVICE_DEBUG_INFO
            + "]");
    this.instance = instance;
    this.context = context;
    sessionId = id;
    this.sessionActivity = sessionActivity;
    this.customLayout = customLayout;
    this.mediaButtonPreferences = mediaButtonPreferences;
    this.commandButtonsForMediaItems = commandButtonsForMediaItems;
    this.callback = callback;
    this.sessionExtras = sessionExtras;
    this.bitmapLoader = bitmapLoader;
    this.playIfSuppressed = playIfSuppressed;
    this.isPeriodicPositionUpdateEnabled = isPeriodicPositionUpdateEnabled;
    this.useLegacySurfaceHandling = useLegacySurfaceHandling;

    @SuppressWarnings("nullness:assignment")
    @Initialized
    MediaSessionImpl thisRef = this;

    sessionStub = new MediaSessionStub(thisRef);

    mainHandler = new Handler(Looper.getMainLooper());
    Looper applicationLooper = player.getApplicationLooper();
    applicationHandler = new Handler(applicationLooper);

    playerInfo = PlayerInfo.DEFAULT;
    onPlayerInfoChangedHandler = new PlayerInfoChangedHandler(applicationLooper);
    mediaPlayPauseKeyHandler = new MediaPlayPauseKeyHandler(applicationLooper);

    sessionUri = createSessionUri(id);

    // For MediaSessionLegacyStub, use the same default commands as the proxy controller gets when
    // the app doesn't overrides the default commands in `onConnect`. When the default is overridden
    // by the app in `onConnect`, the default set here will be overridden with these values.
    MediaSession.ConnectionResult connectionResult =
        new MediaSession.ConnectionResult.AcceptedResultBuilder(instance).build();
    sessionLegacyStub =
        new MediaSessionLegacyStub(
            /* session= */ thisRef,
            sessionUri,
            applicationHandler,
            tokenExtras,
            playIfSuppressed,
            customLayout,
            mediaButtonPreferences,
            connectionResult.availableSessionCommands,
            connectionResult.availablePlayerCommands,
            sessionExtras);

    Token platformToken = sessionLegacyStub.getSessionCompat().getSessionToken().getToken();
    sessionToken =
        new SessionToken(
            Process.myUid(),
            SessionToken.TYPE_SESSION,
            MediaLibraryInfo.VERSION_INT,
            MediaLibraryInfo.INTERFACE_VERSION,
            context.getPackageName(),
            sessionStub,
            tokenExtras,
            platformToken);

    PlayerWrapper playerWrapper = new PlayerWrapper(player);
    this.playerWrapper = playerWrapper;
    postOrRun(
        applicationHandler,
        () ->
            thisRef.setPlayerInternal(
                /* oldPlayerWrapper= */ null, /* newPlayerWrapper= */ playerWrapper));

    sessionPositionUpdateDelayMs = DEFAULT_SESSION_POSITION_UPDATE_DELAY_MS;
    periodicSessionPositionInfoUpdateRunnable =
        thisRef::notifyPeriodicSessionPositionInfoChangesOnHandler;
    postOrRun(applicationHandler, thisRef::schedulePeriodicSessionPositionInfoChanges);
  }

  public void setPlayer(Player player) {
    if (player == playerWrapper.getWrappedPlayer()) {
      return;
    }
    setPlayerInternal(/* oldPlayerWrapper= */ playerWrapper, new PlayerWrapper(player));
  }

  private void setPlayerInternal(
      @Nullable PlayerWrapper oldPlayerWrapper, PlayerWrapper newPlayerWrapper) {
    playerWrapper = newPlayerWrapper;
    if (oldPlayerWrapper != null) {
      oldPlayerWrapper.removeListener(checkNotNull(this.playerListener));
    }
    PlayerListener playerListener = new PlayerListener(this, newPlayerWrapper);
    newPlayerWrapper.addListener(playerListener);
    this.playerListener = playerListener;

    dispatchRemoteControllerTaskToLegacyStub(
        (callback, seq) -> callback.onPlayerChanged(seq, oldPlayerWrapper, newPlayerWrapper));

    // Check whether it's called in constructor where previous player can be null.
    if (oldPlayerWrapper == null) {
      // Do followings at the last moment. Otherwise commands through framework would be sent to
      // this session while initializing, and end up with unexpected situation.
      sessionLegacyStub.start();
    }

    playerInfo = newPlayerWrapper.createInitialPlayerInfo();
    handleAvailablePlayerCommandsChanged(newPlayerWrapper.getAvailableCommands());
  }

  public void release() {
    Log.i(
        TAG,
        "Release "
            + Integer.toHexString(System.identityHashCode(this))
            + " ["
            + MediaLibraryInfo.VERSION_SLASHY
            + "] ["
            + Util.DEVICE_DEBUG_INFO
            + "] ["
            + MediaLibraryInfo.registeredModules()
            + "]");
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
    }
    mediaPlayPauseKeyHandler.clearPendingPlayPauseTask();
    applicationHandler.removeCallbacksAndMessages(null);
    try {
      postOrRun(
          applicationHandler,
          () -> {
            if (playerListener != null) {
              playerWrapper.removeListener(playerListener);
            }
          });
    } catch (Exception e) {
      // Catch all exceptions to ensure the rest of this method to be executed as exceptions may be
      // thrown by user if, for example, the application thread is dead or removeListener throws an
      // exception.
      Log.w(TAG, "Exception thrown while closing", e);
    }
    sessionLegacyStub.release();
    sessionStub.release();
  }

  public PlayerWrapper getPlayerWrapper() {
    return playerWrapper;
  }

  @CheckResult
  public Runnable callWithControllerForCurrentRequestSet(
      @Nullable ControllerInfo controllerForCurrentRequest, Runnable runnable) {
    return () -> {
      this.controllerForCurrentRequest = controllerForCurrentRequest;
      runnable.run();
      this.controllerForCurrentRequest = null;
    };
  }

  public String getId() {
    return sessionId;
  }

  public Uri getUri() {
    return sessionUri;
  }

  public SessionToken getToken() {
    return sessionToken;
  }

  public List<ControllerInfo> getConnectedControllers() {
    ImmutableList<ControllerInfo> media3Controllers =
        sessionStub.getConnectedControllersManager().getConnectedControllers();
    ImmutableList<ControllerInfo> platformControllers =
        sessionLegacyStub.getConnectedControllersManager().getConnectedControllers();
    ImmutableList.Builder<ControllerInfo> controllers =
        ImmutableList.builderWithExpectedSize(
            media3Controllers.size() + platformControllers.size());
    if (!isMediaNotificationControllerConnected) {
      return controllers.addAll(media3Controllers).addAll(platformControllers).build();
    }
    for (int i = 0; i < media3Controllers.size(); i++) {
      ControllerInfo controllerInfo = media3Controllers.get(i);
      if (!isSystemUiController(controllerInfo)) {
        controllers.add(controllerInfo);
      }
    }
    for (int i = 0; i < platformControllers.size(); i++) {
      ControllerInfo controllerInfo = platformControllers.get(i);
      if (!isSystemUiController(controllerInfo)) {
        controllers.add(controllerInfo);
      }
    }
    return controllers.build();
  }

  @Nullable
  public ControllerInfo getControllerForCurrentRequest() {
    return controllerForCurrentRequest != null
        ? resolveControllerInfoForCallback(controllerForCurrentRequest)
        : null;
  }

  public boolean isConnected(ControllerInfo controller) {
    return sessionStub.getConnectedControllersManager().isConnected(controller)
        || sessionLegacyStub.getConnectedControllersManager().isConnected(controller);
  }

  /**
   * Returns whether the given {@link ControllerInfo} belongs to the the System UI controller.
   *
   * @param controllerInfo The controller info.
   * @return Whether the controller info belongs to the System UI controller.
   */
  protected boolean isSystemUiController(@Nullable MediaSession.ControllerInfo controllerInfo) {
    return controllerInfo != null
        && Objects.equals(controllerInfo.getPackageName(), SYSTEM_UI_PACKAGE_NAME);
  }

  /**
   * Returns whether the given {@link ControllerInfo} belongs to the media notification controller.
   *
   * @param controllerInfo The controller info.
   * @return Whether the given controller info belongs to the media notification controller.
   */
  public boolean isMediaNotificationController(MediaSession.ControllerInfo controllerInfo) {
    return Objects.equals(controllerInfo.getPackageName(), context.getPackageName())
        && controllerInfo.getControllerVersion() != ControllerInfo.LEGACY_CONTROLLER_VERSION
        && controllerInfo
            .getConnectionHints()
            .getBoolean(
                MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, /* defaultValue= */ false);
  }

  /**
   * Returns whether the given {@link ControllerInfo} belongs to an Automotive OS controller.
   *
   * @param controllerInfo The controller info.
   * @return Whether the given controller info belongs to an Automotive OS controller.
   */
  public boolean isAutomotiveController(ControllerInfo controllerInfo) {
    return (controllerInfo.getPackageName().equals(ANDROID_AUTOMOTIVE_MEDIA_PACKAGE_NAME)
        || controllerInfo.getPackageName().equals(ANDROID_AUTOMOTIVE_LAUNCHER_PACKAGE_NAME));
  }

  /**
   * Returns whether the given {@link ControllerInfo} belongs to an Android Auto companion app
   * controller.
   *
   * @param controllerInfo The controller info.
   * @return Whether the given controller info belongs to an Android Auto companion app controller.
   */
  public boolean isAutoCompanionController(ControllerInfo controllerInfo) {
    return controllerInfo.getPackageName().equals(ANDROID_AUTO_PACKAGE_NAME);
  }

  /**
   * Returns the {@link ControllerInfo} of the system UI notification controller, or {@code null} if
   * the System UI controller is not connected.
   */
  @Nullable
  protected ControllerInfo getSystemUiControllerInfo() {
    ImmutableList<ControllerInfo> connectedControllers =
        sessionLegacyStub.getConnectedControllersManager().getConnectedControllers();
    for (int i = 0; i < connectedControllers.size(); i++) {
      ControllerInfo controllerInfo = connectedControllers.get(i);
      if (isSystemUiController(controllerInfo)) {
        return controllerInfo;
      }
    }
    connectedControllers = sessionStub.getConnectedControllersManager().getConnectedControllers();
    for (int i = 0; i < connectedControllers.size(); i++) {
      ControllerInfo controllerInfo = connectedControllers.get(i);
      if (isSystemUiController(controllerInfo)) {
        return controllerInfo;
      }
    }
    return null;
  }

  /**
   * Returns the {@link ControllerInfo} of the media notification controller, or {@code null} if the
   * media notification controller is not connected.
   */
  @Nullable
  public ControllerInfo getMediaNotificationControllerInfo() {
    ImmutableList<ControllerInfo> connectedControllers =
        sessionStub.getConnectedControllersManager().getConnectedControllers();
    for (int i = 0; i < connectedControllers.size(); i++) {
      ControllerInfo controllerInfo = connectedControllers.get(i);
      if (isMediaNotificationController(controllerInfo)) {
        return controllerInfo;
      }
    }
    return null;
  }

  /** Returns whether the media notification controller is connected. */
  protected boolean isMediaNotificationControllerConnected() {
    return isMediaNotificationControllerConnected;
  }

  /**
   * Sets the custom layout for the given {@link MediaController}.
   *
   * @param controller The controller.
   * @param customLayout The custom layout.
   * @return The session result from the controller.
   */
  public ListenableFuture<SessionResult> setCustomLayout(
      ControllerInfo controller, ImmutableList<CommandButton> customLayout) {
    if (isMediaNotificationController(controller)) {
      sessionLegacyStub.setPlatformCustomLayout(customLayout);
      sessionLegacyStub.updateLegacySessionPlaybackState(playerWrapper);
    }
    return dispatchRemoteControllerTask(
        controller, (controller1, seq) -> controller1.setCustomLayout(seq, customLayout));
  }

  /** Sets the custom layout of the session and sends the custom layout to all controllers. */
  public void setCustomLayout(ImmutableList<CommandButton> customLayout) {
    this.customLayout = customLayout;
    sessionLegacyStub.setPlatformCustomLayout(customLayout);
    dispatchRemoteControllerTaskWithoutReturn(
        (controller, seq) -> controller.setCustomLayout(seq, customLayout));
  }

  /**
   * Sets the media button preferences for the given {@link MediaController}.
   *
   * @param controller The controller.
   * @param mediaButtonPreferences The media button preferences.
   * @return The session result from the controller.
   */
  public ListenableFuture<SessionResult> setMediaButtonPreferences(
      ControllerInfo controller, ImmutableList<CommandButton> mediaButtonPreferences) {
    if (isMediaNotificationController(controller)) {
      sessionLegacyStub.setPlatformMediaButtonPreferences(mediaButtonPreferences);
      sessionLegacyStub.updateLegacySessionPlaybackState(playerWrapper);
    }
    return dispatchRemoteControllerTask(
        controller,
        (controller1, seq) -> controller1.setMediaButtonPreferences(seq, mediaButtonPreferences));
  }

  /**
   * Sets the media button preferences of the session and sends the media button preferences to all
   * controllers.
   */
  public void setMediaButtonPreferences(ImmutableList<CommandButton> mediaButtonPreferences) {
    this.mediaButtonPreferences = mediaButtonPreferences;
    sessionLegacyStub.setPlatformMediaButtonPreferences(mediaButtonPreferences);
    dispatchRemoteControllerTaskWithoutReturn(
        (controller, seq) -> controller.setMediaButtonPreferences(seq, mediaButtonPreferences));
  }

  public void setPlaybackException(
      ControllerInfo controllerInfo, @Nullable PlaybackException playbackException) {
    ConnectedControllersManager<IBinder> controllerManager =
        sessionStub.getConnectedControllersManager();
    PlaybackException oldPlaybackException = controllerManager.getPlaybackException(controllerInfo);
    if (!controllerManager.isConnected(controllerInfo)
        || PlaybackException.areErrorInfosEqual(playbackException, oldPlaybackException)) {
      return;
    }
    Player.Commands originalPlayerCommands =
        oldPlaybackException == null
            ? controllerManager.getAvailablePlayerCommands(controllerInfo)
            : controllerManager.getPlayerCommandsBeforePlaybackException(controllerInfo);
    if (isMediaNotificationController(controllerInfo)) {
      sessionLegacyStub.setPlaybackException(
          playbackException,
          playbackException != null
              ? createPlayerCommandsForCustomErrorState(originalPlayerCommands)
              : null);
    }
    Player.Commands commands =
        playbackException != null
            ? createPlayerCommandsForCustomErrorState(originalPlayerCommands)
            : controllerManager.getPlayerCommandsBeforePlaybackException(controllerInfo);
    SessionCommands sessionCommands = controllerManager.getAvailableSessionCommands(controllerInfo);
    if (commands != null && sessionCommands != null) {
      controllerManager.resetPlaybackException(controllerInfo);
      setAvailableCommands(controllerInfo, sessionCommands, commands);
      if (playbackException != null) {
        controllerManager.setPlaybackException(
            controllerInfo, playbackException, checkNotNull(originalPlayerCommands));
      }
    }
  }

  public void setPlaybackException(@Nullable PlaybackException playbackException) {
    // Do not check for equality and return as a no-op if equal. Some controller may have a
    // different exception set individually that we want to override.
    this.playbackException = playbackException;
    ImmutableList<ControllerInfo> connectedControllers =
        sessionStub.getConnectedControllersManager().getConnectedControllers();
    for (int i = 0; i < connectedControllers.size(); i++) {
      setPlaybackException(connectedControllers.get(i), playbackException);
    }
  }

  @Nullable
  public PlaybackException getPlaybackException() {
    return playbackException;
  }

  @Nullable
  /* package */ static Player.Commands createPlayerCommandsForCustomErrorState(
      @Nullable Player.Commands playerCommandsBeforeException) {
    if (playerCommandsBeforeException == null) {
      // This may happen when the controller is already disconnected.
      return null;
    }
    Player.Commands.Builder commandsDuringErrorState = Player.Commands.EMPTY.buildUpon();
    if (playerCommandsBeforeException.contains(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)) {
      commandsDuringErrorState.add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM);
    }
    if (playerCommandsBeforeException.contains(Player.COMMAND_GET_TIMELINE)) {
      commandsDuringErrorState.add(Player.COMMAND_GET_TIMELINE);
    }
    if (playerCommandsBeforeException.contains(Player.COMMAND_GET_METADATA)) {
      commandsDuringErrorState.add(Player.COMMAND_GET_METADATA);
    }
    if (playerCommandsBeforeException.contains(Player.COMMAND_GET_AUDIO_ATTRIBUTES)) {
      commandsDuringErrorState.add(Player.COMMAND_GET_AUDIO_ATTRIBUTES);
    }
    if (playerCommandsBeforeException.contains(Player.COMMAND_GET_VOLUME)) {
      commandsDuringErrorState.add(Player.COMMAND_GET_VOLUME);
    }
    if (playerCommandsBeforeException.contains(Player.COMMAND_GET_DEVICE_VOLUME)) {
      commandsDuringErrorState.add(Player.COMMAND_GET_DEVICE_VOLUME);
    }
    if (playerCommandsBeforeException.contains(Player.COMMAND_GET_TRACKS)) {
      commandsDuringErrorState.add(Player.COMMAND_GET_TRACKS);
    }
    if (playerCommandsBeforeException.contains(Player.COMMAND_RELEASE)) {
      commandsDuringErrorState.add(Player.COMMAND_RELEASE);
    }
    return commandsDuringErrorState.build();
  }

  /** Returns the current {@link PlayerInfo}. */
  public PlayerInfo getPlayerInfo() {
    return playerInfo;
  }

  /** Returns the custom layout. */
  public ImmutableList<CommandButton> getCustomLayout() {
    return customLayout;
  }

  /** Returns the media button preferences. */
  public ImmutableList<CommandButton> getMediaButtonPreferences() {
    return mediaButtonPreferences;
  }

  /** Returns the command buttons for media items. */
  public ImmutableList<CommandButton> getCommandButtonsForMediaItems() {
    return commandButtonsForMediaItems;
  }

  public void setSessionExtras(Bundle sessionExtras) {
    this.sessionExtras = sessionExtras;
    dispatchRemoteControllerTaskWithoutReturn(
        (controller, seq) -> controller.onSessionExtrasChanged(seq, sessionExtras));
  }

  public void setSessionExtras(ControllerInfo controller, Bundle sessionExtras) {
    if (sessionStub.getConnectedControllersManager().isConnected(controller)) {
      dispatchRemoteControllerTaskWithoutReturn(
          controller, (callback, seq) -> callback.onSessionExtrasChanged(seq, sessionExtras));
      if (isMediaNotificationController(controller)) {
        dispatchRemoteControllerTaskToLegacyStub(
            (callback, seq) -> callback.onSessionExtrasChanged(seq, sessionExtras));
      }
    }
  }

  public Bundle getSessionExtras() {
    return sessionExtras;
  }

  public BitmapLoader getBitmapLoader() {
    return bitmapLoader;
  }

  public boolean shouldPlayIfSuppressed() {
    return playIfSuppressed;
  }

  public boolean shouldUseLegacySurfaceHandling() {
    return useLegacySurfaceHandling;
  }

  public void setAvailableCommands(
      ControllerInfo controller, SessionCommands sessionCommands, Player.Commands playerCommands) {
    if (sessionStub.getConnectedControllersManager().isConnected(controller)) {
      if (isMediaNotificationController(controller)) {
        sessionLegacyStub.setAvailableCommands(sessionCommands, playerCommands);
        ControllerInfo systemUiInfo = getSystemUiControllerInfo();
        if (systemUiInfo != null) {
          // Set the available commands of the proxy controller to the ConnectedControllerRecord of
          // the hidden System UI controller.
          ConnectedControllersManager<?> controllersManager =
              systemUiInfo.getControllerVersion() == ControllerInfo.LEGACY_CONTROLLER_VERSION
                  ? sessionLegacyStub.getConnectedControllersManager()
                  : sessionStub.getConnectedControllersManager();
          controllersManager.updateCommandsFromSession(
              systemUiInfo, sessionCommands, playerCommands);
        }
      }
      sessionStub
          .getConnectedControllersManager()
          .updateCommandsFromSession(controller, sessionCommands, playerCommands);
      // Read the available player commands from the manager again after update.
      Player.Commands availablePlayerCommands =
          sessionStub.getConnectedControllersManager().getAvailablePlayerCommands(controller);
      if (availablePlayerCommands != null) {
        dispatchRemoteControllerTaskWithoutReturn(
            controller,
            (callback, seq) ->
                callback.onAvailableCommandsChangedFromSession(
                    seq, sessionCommands, availablePlayerCommands));
        onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
            /* excludeTimeline= */ false, /* excludeTracks= */ false);
      }
    } else if (controller.getControllerVersion() == ControllerInfo.LEGACY_CONTROLLER_VERSION) {
      sessionLegacyStub
          .getConnectedControllersManager()
          .updateCommandsFromSession(controller, sessionCommands, playerCommands);
    }
  }

  public void broadcastCustomCommand(SessionCommand command, Bundle args) {
    dispatchRemoteControllerTaskWithoutReturn(
        (controller, seq) -> controller.sendCustomCommand(seq, command, args));
  }

  private void dispatchOnPlayerInfoChanged(
      PlayerInfo playerInfo, boolean excludeTimeline, boolean excludeTracks) {
    playerInfo = sessionStub.generateAndCacheUniqueTrackGroupIds(playerInfo);
    List<ControllerInfo> controllers =
        sessionStub.getConnectedControllersManager().getConnectedControllers();
    for (int i = 0; i < controllers.size(); i++) {
      ControllerInfo controller = controllers.get(i);
      try {
        int seq;
        ConnectedControllersManager<IBinder> controllersManager =
            sessionStub.getConnectedControllersManager();
        SequencedFutureManager manager = controllersManager.getSequencedFutureManager(controller);
        if (manager != null) {
          seq = manager.obtainNextSequenceNumber();
        } else {
          if (!isConnected(controller)) {
            return;
          }
          // 0 is OK for legacy controllers, because they didn't have sequence numbers.
          seq = 0;
        }
        PlayerInfo playerInfoInErrorStateForController =
            controllersManager.getPlayerInfoForPlaybackException(controller);
        if (playerInfoInErrorStateForController != null) {
          // Don't update controller already in error state.
          continue;
        }
        PlaybackException playbackExceptionForController =
            controllersManager.getPlaybackException(controller);
        if (playbackExceptionForController != null) {
          playerInfoInErrorStateForController =
              createPlayerInfoForCustomPlaybackException(
                  playerInfo, playbackExceptionForController);
          controllersManager.setPlayerInfoForPlaybackException(
              controller, playerInfoInErrorStateForController);
        }
        Player.Commands intersectedCommands =
            MediaUtils.intersect(
                controllersManager.getAvailablePlayerCommands(controller),
                getPlayerWrapper().getAvailableCommands());
        checkNotNull(controller.getControllerCb())
            .onPlayerInfoChanged(
                seq,
                playerInfoInErrorStateForController == null
                    ? playerInfo
                    : playerInfoInErrorStateForController,
                intersectedCommands,
                excludeTimeline,
                excludeTracks);
      } catch (DeadObjectException e) {
        onDeadObjectException(controller);
      } catch (RemoteException e) {
        // Currently it's TransactionTooLargeException or DeadSystemException.
        // We'd better to leave log for those cases because
        //   - TransactionTooLargeException means that we may need to fix our code.
        //     (e.g. add pagination or special way to deliver Bitmap)
        //   - DeadSystemException means that errors around it can be ignored.
        Log.w(TAG, "Exception in " + controller, e);
      }
    }
  }

  /* package */ static PlayerInfo createPlayerInfoForCustomPlaybackException(
      PlayerInfo playerInfo, PlaybackException playbackException) {
    return playerInfo
        .copyWithPlaybackState(Player.STATE_IDLE, playbackException)
        .copyWithSessionPositionInfo(
            new SessionPositionInfo(
                playerInfo.sessionPositionInfo.positionInfo,
                playerInfo.sessionPositionInfo.isPlayingAd,
                playerInfo.sessionPositionInfo.eventTimeMs,
                playerInfo.sessionPositionInfo.durationMs,
                /* bufferedPositionMs= */ 0,
                /* bufferedPercentage= */ 0,
                /* totalBufferedDurationMs= */ 0,
                playerInfo.sessionPositionInfo.currentLiveOffsetMs,
                playerInfo.sessionPositionInfo.contentDurationMs,
                /* contentBufferedPositionMs= */ 0));
  }

  public ListenableFuture<SessionResult> sendCustomCommand(
      ControllerInfo controller, SessionCommand command, Bundle args) {
    return dispatchRemoteControllerTask(
        controller, (cb, seq) -> cb.sendCustomCommand(seq, command, args));
  }

  public void sendCustomCommandProgressUpdate(
      ControllerInfo controller,
      int customCommandFutureSequence,
      SessionCommand command,
      Bundle args,
      Bundle progressData) {
    dispatchRemoteControllerTaskWithoutReturn(
        controller,
        (cb, seq) ->
            cb.sendCustomCommandProgressUpdate(
                customCommandFutureSequence, command, args, progressData));
  }

  public void sendError(ControllerInfo controllerInfo, SessionError sessionError) {
    if (controllerInfo.getControllerVersion() != ControllerInfo.LEGACY_CONTROLLER_VERSION
        && controllerInfo.getInterfaceVersion() < 4) {
      // IMediaController.onError introduced with interface version 4.
      return;
    }
    if (isMediaNotificationController(controllerInfo)
        || controllerInfo.getControllerVersion() == ControllerInfo.LEGACY_CONTROLLER_VERSION) {
      // Media notification controller or legacy controllers update the platform session.
      dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onError(seq, sessionError));
    } else {
      // Media3 controller are notified individually.
      dispatchRemoteControllerTaskWithoutReturn(
          controllerInfo, (callback, seq) -> callback.onError(seq, sessionError));
    }
  }

  public void sendError(SessionError sessionError) {
    // Send error messages to Media3 controllers.
    ImmutableList<ControllerInfo> connectedControllers =
        sessionStub.getConnectedControllersManager().getConnectedControllers();
    for (int i = 0; i < connectedControllers.size(); i++) {
      ControllerInfo controllerInfo = connectedControllers.get(i);
      if (!isMediaNotificationController(controllerInfo)) {
        // Omit sending to the media notification controller. Instead the error will be dispatched
        // through the legacy stub below to avoid updating the legacy session multiple times.
        sendError(controllerInfo, sessionError);
      }
    }
    // Send error messages to legacy controllers.
    dispatchRemoteControllerTaskToLegacyStub(
        (callback, seq) -> callback.onError(seq, sessionError));
  }

  public MediaSession.ConnectionResult onConnectOnHandler(ControllerInfo controller) {
    if (isMediaNotificationControllerConnected && isSystemUiController(controller)) {
      // Hide System UI and provide the connection result from the platform state.
      return sessionLegacyStub.getPlatformConnectionResult(instance);
    }
    MediaSession.ConnectionResult connectionResult =
        checkNotNull(
            callback.onConnect(instance, controller),
            "Callback.onConnect must return non-null future");
    if (isMediaNotificationController(controller) && connectionResult.isAccepted) {
      isMediaNotificationControllerConnected = true;
      ImmutableList<CommandButton> mediaButtonPreferences =
          connectionResult.mediaButtonPreferences != null
              ? connectionResult.mediaButtonPreferences
              : instance.getMediaButtonPreferences();
      if (mediaButtonPreferences.isEmpty()) {
        sessionLegacyStub.setPlatformCustomLayout(
            connectionResult.customLayout != null
                ? connectionResult.customLayout
                : instance.getCustomLayout());
      } else {
        sessionLegacyStub.setPlatformMediaButtonPreferences(mediaButtonPreferences);
      }
      sessionLegacyStub.setAvailableCommands(
          connectionResult.availableSessionCommands, connectionResult.availablePlayerCommands);
    }
    return connectionResult;
  }

  public void onPostConnectOnHandler(ControllerInfo controller) {
    if (isMediaNotificationControllerConnected && isSystemUiController(controller)) {
      // Hide System UI. Apps can use the media notification controller to maintain the platform
      // session
      return;
    }
    callback.onPostConnect(instance, controller);
  }

  public void onDisconnectedOnHandler(ControllerInfo controller) {
    if (isMediaNotificationControllerConnected) {
      if (isSystemUiController(controller)) {
        // Hide System UI controller. Apps can use the media notification controller to maintain the
        // platform session.
        return;
      } else if (isMediaNotificationController(controller)) {
        isMediaNotificationControllerConnected = false;
      }
    }
    callback.onDisconnected(instance, controller);
  }

  @SuppressWarnings("deprecation") // Calling deprecated callback method.
  public @SessionResult.Code int onPlayerCommandRequestOnHandler(
      ControllerInfo controller, @Player.Command int playerCommand) {
    return callback.onPlayerCommandRequest(
        instance, resolveControllerInfoForCallback(controller), playerCommand);
  }

  public ListenableFuture<SessionResult> onSetRatingOnHandler(
      ControllerInfo controller, String mediaId, Rating rating) {
    return checkNotNull(
        callback.onSetRating(
            instance, resolveControllerInfoForCallback(controller), mediaId, rating),
        "Callback.onSetRating must return non-null future");
  }

  public ListenableFuture<SessionResult> onSetRatingOnHandler(
      ControllerInfo controller, Rating rating) {
    return checkNotNull(
        callback.onSetRating(instance, resolveControllerInfoForCallback(controller), rating),
        "Callback.onSetRating must return non-null future");
  }

  public ListenableFuture<SessionResult> onCustomCommandOnHandler(
      ControllerInfo controller,
      @Nullable MediaSession.ProgressReporter progressReporter,
      SessionCommand command,
      Bundle extras) {
    return checkNotNull(
        callback.onCustomCommand(
            instance,
            resolveControllerInfoForCallback(controller),
            command,
            extras,
            progressReporter),
        "Callback.onCustomCommandOnHandler must return non-null future");
  }

  protected ListenableFuture<List<MediaItem>> onAddMediaItemsOnHandler(
      ControllerInfo controller, List<MediaItem> mediaItems) {
    return checkNotNull(
        callback.onAddMediaItems(
            instance, resolveControllerInfoForCallback(controller), mediaItems),
        "Callback.onAddMediaItems must return a non-null future");
  }

  protected ListenableFuture<MediaItemsWithStartPosition> onSetMediaItemsOnHandler(
      ControllerInfo controller, List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
    return checkNotNull(
        callback.onSetMediaItems(
            instance,
            resolveControllerInfoForCallback(controller),
            mediaItems,
            startIndex,
            startPositionMs),
        "Callback.onSetMediaItems must return a non-null future");
  }

  protected void onPlayerInteractionFinishedOnHandler(
      ControllerInfo controller, Player.Commands playerCommands) {
    callback.onPlayerInteractionFinished(
        instance, resolveControllerInfoForCallback(controller), playerCommands);
  }

  public void connectFromService(IMediaController caller, ControllerInfo controllerInfo) {
    sessionStub.connect(caller, controllerInfo);
  }

  @SuppressWarnings("UnnecessarilyFullyQualified") // Avoiding confusion by just using "Token"
  public android.media.session.MediaSession.Token getPlatformToken() {
    return sessionLegacyStub.getSessionCompat().getSessionToken().getToken();
  }

  public void setLegacyControllerConnectionTimeoutMs(long timeoutMs) {
    sessionLegacyStub.setLegacyControllerDisconnectTimeoutMs(timeoutMs);
  }

  protected MediaSessionLegacyStub getMediaSessionLegacyStub() {
    return sessionLegacyStub;
  }

  protected Context getContext() {
    return context;
  }

  protected Handler getApplicationHandler() {
    return applicationHandler;
  }

  protected boolean isReleased() {
    synchronized (lock) {
      return closed;
    }
  }

  @Nullable
  protected PendingIntent getSessionActivity() {
    return sessionActivity;
  }

  @UnstableApi
  protected void setSessionActivity(@Nullable PendingIntent sessionActivity) {
    this.sessionActivity = sessionActivity;
    ImmutableList<ControllerInfo> connectedControllers =
        sessionStub.getConnectedControllersManager().getConnectedControllers();
    for (int i = 0; i < connectedControllers.size(); i++) {
      setSessionActivity(connectedControllers.get(i), sessionActivity);
    }
  }

  @UnstableApi
  protected void setSessionActivity(
      ControllerInfo controller, @Nullable PendingIntent sessionActivity) {
    if (controller.getControllerVersion() >= 3
        && sessionStub.getConnectedControllersManager().isConnected(controller)) {
      dispatchRemoteControllerTaskWithoutReturn(
          controller, (callback, seq) -> callback.onSessionActivityChanged(seq, sessionActivity));
      if (isMediaNotificationController(controller)) {
        dispatchRemoteControllerTaskToLegacyStub(
            (callback, seq) -> callback.onSessionActivityChanged(seq, sessionActivity));
      }
    }
  }

  protected ControllerInfo resolveControllerInfoForCallback(ControllerInfo controller) {
    return isMediaNotificationControllerConnected && isSystemUiController(controller)
        ? checkNotNull(getMediaNotificationControllerInfo())
        : controller;
  }

  /**
   * Gets the service binder from the MediaBrowserServiceCompat. Should be only called by the thread
   * with a Looper.
   */
  @Nullable
  protected IBinder getLegacyBrowserServiceBinder() {
    MediaSessionServiceLegacyStub legacyStub;
    synchronized (lock) {
      if (browserServiceLegacyStub == null) {
        browserServiceLegacyStub =
            createLegacyBrowserService(sessionLegacyStub.getSessionCompat().getSessionToken());
      }
      legacyStub = browserServiceLegacyStub;
    }
    Intent intent = new Intent(MediaBrowserServiceCompat.SERVICE_INTERFACE);
    return legacyStub.onBind(intent);
  }

  protected MediaSessionServiceLegacyStub createLegacyBrowserService(
      MediaSessionCompat.Token compatToken) {
    MediaSessionServiceLegacyStub stub = new MediaSessionServiceLegacyStub(this);
    stub.initialize(compatToken);
    return stub;
  }

  protected void setSessionPositionUpdateDelayMsOnHandler(long updateDelayMs) {
    verifyApplicationThread();
    sessionPositionUpdateDelayMs = updateDelayMs;
    schedulePeriodicSessionPositionInfoChanges();
  }

  @Nullable
  protected MediaSessionServiceLegacyStub getLegacyBrowserService() {
    synchronized (lock) {
      return browserServiceLegacyStub;
    }
  }

  /**
   * Creates a session URI for the given session ID.
   *
   * @param sessionId The session ID, or {@code null} if not set with {@link
   *     MediaSession.Builder#setId(String)}.
   * @return The session URI to identify the session.
   */
  /* package */ static Uri createSessionUri(@Nullable String sessionId) {
    return new Uri.Builder()
        .scheme(SESSION_URI_SCHEME)
        .authority(SESSION_URI_AUTHORITY)
        .appendPath(sessionId == null ? MediaSession.DEFAULT_SESSION_ID : sessionId)
        .build();
  }

  /**
   * Returns the session ID encoded in the session URI or {@link MediaSession#DEFAULT_SESSION_ID} if
   * the URI passed in is not a valid session URI.
   *
   * @param sessionUri The session URI from which to extract the session ID.
   * @return The session ID.
   */
  /* package */ static String getSessionId(Uri sessionUri) {
    List<String> pathSegments = sessionUri.getPathSegments();
    return !Objects.equals(sessionUri.getScheme(), SESSION_URI_SCHEME)
            || !Objects.equals(sessionUri.getAuthority(), SESSION_URI_AUTHORITY)
            || pathSegments.isEmpty()
        ? MediaSession.DEFAULT_SESSION_ID
        : pathSegments.get(0);
  }

  /* package */ boolean canResumePlaybackOnStart() {
    return sessionLegacyStub.canResumePlaybackOnStart();
  }

  /* package */ void setMediaSessionListener(MediaSession.Listener listener) {
    this.mediaSessionListener = listener;
  }

  /* package */ void clearMediaSessionListener() {
    this.mediaSessionListener = null;
  }

  /* package */ void onNotificationRefreshRequired() {
    postOrRun(
        mainHandler,
        () -> {
          if (this.mediaSessionListener != null) {
            this.mediaSessionListener.onNotificationRefreshRequired(instance);
          }
        });
  }

  /* package */ ListenableFuture<Boolean> onPlayRequested() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      return CallbackToFutureAdapter.<Boolean>getFuture(
              completer -> {
                mainHandler.post(
                    () -> {
                      try {
                        completer.set(onPlayRequested().get());
                      } catch (ExecutionException | InterruptedException e) {
                        completer.setException(new IllegalStateException(e));
                      }
                    });
                return "onPlayRequested";
              });
    }
    if (this.mediaSessionListener != null) {
      return Futures.immediateFuture(this.mediaSessionListener.onPlayRequested(instance));
    }
    return Futures.immediateFuture(true);
  }

  /**
   * Handles a play request from a media controller.
   *
   * <p>Attempts to prepare and play for playback resumption if the playlist is empty. {@link
   * Player#play()} is called regardless of success or failure of playback resumption.
   *
   * @param controller The controller requesting to play.
   */
  /* package */ ListenableFuture<SessionResult> handleMediaControllerPlayRequest(
      ControllerInfo controller, boolean callOnPlayerInteractionFinished) {
    return Futures.transformAsync(
        onPlayRequested(),
        playRequested -> {
          if (!playRequested) {
            // Request denied, e.g. due to missing foreground service abilities.
            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_ERROR_UNKNOWN));
          }
          boolean hasCurrentMediaItem =
              playerWrapper.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                  && playerWrapper.getCurrentMediaItem() != null;
          boolean canAddMediaItems =
              playerWrapper.isCommandAvailable(COMMAND_SET_MEDIA_ITEM)
                  || playerWrapper.isCommandAvailable(COMMAND_CHANGE_MEDIA_ITEMS);
          ControllerInfo controllerForRequest = resolveControllerInfoForCallback(controller);
          Player.Commands playCommand =
              new Player.Commands.Builder().add(Player.COMMAND_PLAY_PAUSE).build();
          if (hasCurrentMediaItem || !canAddMediaItems) {
            // No playback resumption needed or possible.
            if (!hasCurrentMediaItem) {
              Log.w(
                  TAG,
                  "Play requested without current MediaItem, but playback resumption prevented by"
                      + " missing available commands");
            }
            Util.handlePlayButtonAction(playerWrapper);
            if (callOnPlayerInteractionFinished) {
              onPlayerInteractionFinishedOnHandler(controllerForRequest, playCommand);
            }
            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
          } else {
            ListenableFuture<SessionResult> future =
                Futures.transform(
                    checkNotNull(
                      callback.onPlaybackResumption(
                         instance, controllerForRequest, /* isForPlayback= */ true),
                      "Callback.onPlaybackResumption must return a non-null future"),
                    mediaItemsWithStartPosition -> {
                      callWithControllerForCurrentRequestSet(
                          controllerForRequest,
                          () -> {
                            MediaUtils.setMediaItemsWithStartIndexAndPosition(
                                playerWrapper, mediaItemsWithStartPosition);
                            Util.handlePlayButtonAction(playerWrapper);
                            if (callOnPlayerInteractionFinished) {
                              onPlayerInteractionFinishedOnHandler(
                                  controllerForRequest, playCommand);
                            }
                          })
                          .run();
                      return new SessionResult(SessionResult.RESULT_SUCCESS);
                    },
                    this::postOrRunOnApplicationHandler);
            return Futures.catching(
                future,
                Throwable.class,
                t -> {
                  if (t instanceof UnsupportedOperationException) {
                    Log.w(
                        TAG,
                        "UnsupportedOperationException: Make sure to implement"
                            + " MediaSession.Callback.onPlaybackResumption() if you add a media"
                            + " button receiver to your manifest or if you implement the recent"
                            + " media item contract with your MediaLibraryService.",
                        t);
                  } else {
                    Log.e(
                        TAG,
                        "Failure calling MediaSession.Callback.onPlaybackResumption(): "
                            + t.getMessage(),
                        t);
                  }
                  // Play as requested even if playback resumption fails.
                  Util.handlePlayButtonAction(playerWrapper);
                  if (callOnPlayerInteractionFinished) {
                    onPlayerInteractionFinishedOnHandler(controllerForRequest, playCommand);
                  }
                  return new SessionResult(SessionResult.RESULT_SUCCESS);
                },
                this::postOrRunOnApplicationHandler
            );
          }
        },
        this::postOrRunOnApplicationHandler);
  }

  /* package */ void triggerPlayerInfoUpdate() {
    onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
        /* excludeTimeline= */ true, /* excludeTracks= */ true);
  }

  private void dispatchRemoteControllerTaskToLegacyStub(RemoteControllerTask task) {
    try {
      task.run(sessionLegacyStub.getControllerLegacyCbForBroadcast(), /* seq= */ 0);
    } catch (RemoteException e) {
      Log.e(TAG, "Exception in using media1 API", e);
    }
  }

  private void dispatchOnPeriodicSessionPositionInfoChanged(
      SessionPositionInfo sessionPositionInfo) {
    ConnectedControllersManager<IBinder> controllersManager =
        sessionStub.getConnectedControllersManager();
    ImmutableList<ControllerInfo> controllers = controllersManager.getConnectedControllers();
    for (int i = 0; i < controllers.size(); i++) {
      ControllerInfo controller = controllers.get(i);
      if (controllersManager.getPlaybackException(controller) != null) {
        continue;
      }
      boolean canAccessCurrentMediaItem =
          controllersManager.isPlayerCommandAvailable(
              controller, Player.COMMAND_GET_CURRENT_MEDIA_ITEM);
      boolean canAccessTimeline =
          controllersManager.isPlayerCommandAvailable(controller, Player.COMMAND_GET_TIMELINE);
      dispatchRemoteControllerTaskWithoutReturn(
          controller,
          (controllerCb, seq) ->
              controllerCb.onPeriodicSessionPositionInfoChanged(
                  seq,
                  sessionPositionInfo,
                  canAccessCurrentMediaItem,
                  canAccessTimeline,
                  controller.getInterfaceVersion()));
    }
    try {
      sessionLegacyStub
          .getControllerLegacyCbForBroadcast()
          .onPeriodicSessionPositionInfoChanged(
              /* seq= */ 0,
              sessionPositionInfo,
              /* canAccessCurrentMediaItem= */ true,
              /* canAccessTimeline= */ true,
              ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION);
    } catch (RemoteException e) {
      Log.e(TAG, "Exception in using media1 API", e);
    }
  }

  private void dispatchRemoteControllerTaskWithoutReturn(RemoteControllerTask task) {
    List<ControllerInfo> controllers =
        sessionStub.getConnectedControllersManager().getConnectedControllers();
    for (int i = 0; i < controllers.size(); i++) {
      ControllerInfo controller = controllers.get(i);
      dispatchRemoteControllerTaskWithoutReturn(controller, task);
    }
    try {
      task.run(sessionLegacyStub.getControllerLegacyCbForBroadcast(), /* seq= */ 0);
    } catch (RemoteException e) {
      Log.e(TAG, "Exception in using media1 API", e);
    }
  }

  protected final void dispatchRemoteControllerTaskWithoutReturn(
      ControllerInfo controller, RemoteControllerTask task) {
    try {
      int seq;
      @Nullable
      SequencedFutureManager manager =
          sessionStub.getConnectedControllersManager().getSequencedFutureManager(controller);
      if (manager != null) {
        seq = manager.obtainNextSequenceNumber();
      } else {
        if (!isConnected(controller)) {
          return;
        }
        // 0 is OK for legacy controllers, because they didn't have sequence numbers.
        seq = 0;
      }
      ControllerCb cb = controller.getControllerCb();
      if (cb != null) {
        task.run(cb, seq);
      }
    } catch (DeadObjectException e) {
      onDeadObjectException(controller);
    } catch (RemoteException e) {
      // Currently it's TransactionTooLargeException or DeadSystemException.
      // We'd better to leave log for those cases because
      //   - TransactionTooLargeException means that we may need to fix our code.
      //     (e.g. add pagination or special way to deliver Bitmap)
      //   - DeadSystemException means that errors around it can be ignored.
      Log.w(TAG, "Exception in " + controller, e);
    }
  }

  private ListenableFuture<SessionResult> dispatchRemoteControllerTask(
      ControllerInfo controller, RemoteControllerTask task) {
    try {
      ListenableFuture<SessionResult> future;
      int seq;
      @Nullable
      SequencedFutureManager manager =
          sessionStub.getConnectedControllersManager().getSequencedFutureManager(controller);
      if (manager != null) {
        future = manager.createSequencedFuture(RESULT_WHEN_CLOSED);
        seq = ((SequencedFuture<SessionResult>) future).getSequenceNumber();
      } else {
        if (!isConnected(controller)) {
          return Futures.immediateFuture(new SessionResult(ERROR_SESSION_DISCONNECTED));
        }
        // 0 is OK for legacy controllers, because they didn't have sequence numbers.
        seq = 0;
        // Tell that operation is successful, although we don't know the actual result.
        future = Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
      }
      ControllerCb cb = controller.getControllerCb();
      if (cb != null) {
        task.run(cb, seq);
      }
      return future;
    } catch (DeadObjectException e) {
      onDeadObjectException(controller);
      return Futures.immediateFuture(new SessionResult(ERROR_SESSION_DISCONNECTED));
    } catch (RemoteException e) {
      // Currently it's TransactionTooLargeException or DeadSystemException.
      // We'd better to leave log for those cases because
      //   - TransactionTooLargeException means that we may need to fix our code.
      //     (e.g. add pagination or special way to deliver Bitmap)
      //   - DeadSystemException means that errors around it can be ignored.
      Log.w(TAG, "Exception in " + controller, e);
    }
    return Futures.immediateFuture(new SessionResult(ERROR_UNKNOWN));
  }

  /** Removes controller. Call this when DeadObjectException is happened with binder call. */
  private void onDeadObjectException(ControllerInfo controller) {
    // Note: Only removing from MediaSessionStub and ignoring (legacy) stubs would be fine for
    //       now. Because calls to the legacy stubs doesn't throw DeadObjectException.
    sessionStub.getConnectedControllersManager().removeController(controller);
  }

  private void verifyApplicationThread() {
    if (Looper.myLooper() != applicationHandler.getLooper()) {
      throw new IllegalStateException(WRONG_THREAD_ERROR_MESSAGE);
    }
  }

  private void notifyPeriodicSessionPositionInfoChangesOnHandler() {
    synchronized (lock) {
      if (closed) {
        return;
      }
    }
    SessionPositionInfo sessionPositionInfo = playerWrapper.createSessionPositionInfo();
    if (!onPlayerInfoChangedHandler.hasPendingPlayerInfoChangedUpdate()
        && MediaUtils.areSessionPositionInfosInSamePeriodOrAd(
            sessionPositionInfo, playerInfo.sessionPositionInfo)) {
      // Send a periodic position info only if a PlayerInfo update is not already already pending
      // and the player state is still corresponding to the currently known PlayerInfo. Both
      // conditions will soon trigger a new PlayerInfo update with the latest position info anyway
      // and we also don't want to send a new position info early if the corresponding Timeline
      // update hasn't been sent yet (see [internal b/277301159]).
      dispatchOnPeriodicSessionPositionInfoChanged(sessionPositionInfo);
    }
    schedulePeriodicSessionPositionInfoChanges();
  }

  private void schedulePeriodicSessionPositionInfoChanges() {
    applicationHandler.removeCallbacks(periodicSessionPositionInfoUpdateRunnable);
    if (isPeriodicPositionUpdateEnabled
        && sessionPositionUpdateDelayMs > 0
        && (playerWrapper.isPlaying() || playerWrapper.isLoading())) {
      applicationHandler.postDelayed(
          periodicSessionPositionInfoUpdateRunnable, sessionPositionUpdateDelayMs);
    }
  }

  private void handleAvailablePlayerCommandsChanged(Player.Commands availableCommands) {
    // Update PlayerInfo and do not force exclude elements in case they need to be updated because
    // an available command has been removed.
    onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
        /* excludeTimeline= */ false, /* excludeTracks= */ false);
    dispatchRemoteControllerTaskWithoutReturn(
        (callback, seq) -> callback.onAvailableCommandsChangedFromPlayer(seq, availableCommands));

    // Forcefully update playback info to update VolumeProviderCompat in case
    // COMMAND_ADJUST_DEVICE_VOLUME or COMMAND_SET_DEVICE_VOLUME value has changed.
    dispatchRemoteControllerTaskToLegacyStub(
        (callback, seq) -> callback.onDeviceInfoChanged(seq, playerInfo.deviceInfo));
  }

  /**
   * Returns true if the media button event was handled, false otherwise.
   *
   * <p>Must be called on the application thread of the session.
   *
   * @param callerInfo The calling {@link ControllerInfo}.
   * @param intent The media button intent.
   * @return True if the event was handled, false otherwise.
   */
  /* package */ boolean onMediaButtonEvent(ControllerInfo callerInfo, Intent intent) {
    KeyEvent keyEvent = DefaultActionFactory.getKeyEvent(intent);
    ComponentName intentComponent = intent.getComponent();
    if (!Objects.equals(intent.getAction(), Intent.ACTION_MEDIA_BUTTON)
        || (intentComponent != null
            && !Objects.equals(intentComponent.getPackageName(), context.getPackageName()))
        || keyEvent == null) {
      return false;
    }

    verifyApplicationThread();
    if (callback.onMediaButtonEvent(instance, callerInfo, intent)) {
      // Event handled by app callback.
      return true;
    }

    if (keyEvent.getAction() != KeyEvent.ACTION_DOWN) {
      switch (keyEvent.getKeyCode()) {
        case KEYCODE_MEDIA_PLAY_PAUSE:
        case KEYCODE_HEADSETHOOK:
        case KEYCODE_MEDIA_PLAY:
        case KEYCODE_MEDIA_PAUSE:
        case KEYCODE_MEDIA_NEXT:
        case KEYCODE_MEDIA_SKIP_FORWARD:
        case KEYCODE_MEDIA_PREVIOUS:
        case KEYCODE_MEDIA_SKIP_BACKWARD:
        case KEYCODE_MEDIA_FAST_FORWARD:
        case KEYCODE_MEDIA_REWIND:
        case KEYCODE_MEDIA_STOP:
          // The default implementation is handling action down of these key codes. Signal to handle
          // corresponding non-down actions as well.
          return true;
        default:
          return false;
      }
    }

    // Double tap detection.
    int keyCode = keyEvent.getKeyCode();
    boolean isTvApp = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    boolean doubleTapCompleted = false;
    switch (keyCode) {
      case KEYCODE_MEDIA_PLAY_PAUSE:
      case KEYCODE_HEADSETHOOK:
        if (isTvApp
            || callerInfo.getControllerVersion() != ControllerInfo.LEGACY_CONTROLLER_VERSION
            || keyEvent.getRepeatCount() != 0) {
          // Double tap detection is only for mobile apps that receive a media button event from
          // external sources (for instance Bluetooth) and excluding long press (repeatCount > 0).
          mediaPlayPauseKeyHandler.flush();
        } else if (mediaPlayPauseKeyHandler.hasPendingPlayPauseTask()) {
          // A double tap arrived. Clear the pending playPause task.
          mediaPlayPauseKeyHandler.clearPendingPlayPauseTask();
          doubleTapCompleted = true;
        } else {
          // Handle event with a delayed callback that's run if no double tap arrives in time.
          mediaPlayPauseKeyHandler.setPendingPlayPauseTask(callerInfo, keyEvent);
          return true;
        }
        break;
      default:
        // If another key is pressed within double tap timeout, make play/pause as a single tap to
        // handle media keys in order.
        mediaPlayPauseKeyHandler.flush();
        break;
    }

    if (!isMediaNotificationControllerConnected()) {
      if ((keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KEYCODE_HEADSETHOOK)
          && doubleTapCompleted) {
        // Double tap completion for legacy when media notification controller is disabled.
        sessionLegacyStub.onSkipToNext();
        return true;
      } else if (callerInfo.getControllerVersion() != ControllerInfo.LEGACY_CONTROLLER_VERSION) {
        sessionLegacyStub.getSessionCompat().getController().dispatchMediaButtonEvent(keyEvent);
        return true;
      }
      // This is an unhandled framework event. Return false to let the framework resolve by calling
      // `MediaSessionCompat.Callback.onXyz()`.
      return false;
    }
    // Send from media notification controller.
    boolean isDismissNotificationEvent =
        intent.getBooleanExtra(
            MediaNotification.NOTIFICATION_DISMISSED_EVENT_KEY, /* defaultValue= */ false);
    return keyEvent.getRepeatCount() > 0
        || applyMediaButtonKeyEvent(keyEvent, doubleTapCompleted, isDismissNotificationEvent);
  }

  private boolean applyMediaButtonKeyEvent(
      KeyEvent keyEvent, boolean doubleTapCompleted, boolean isDismissNotificationEvent) {
    ControllerInfo controllerInfo = checkNotNull(instance.getMediaNotificationControllerInfo());
    Runnable command;
    int keyCode = keyEvent.getKeyCode();
    if ((keyCode == KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KEYCODE_HEADSETHOOK)
        && doubleTapCompleted) {
      keyCode = KEYCODE_MEDIA_NEXT;
    }
    switch (keyCode) {
      case KEYCODE_MEDIA_PLAY_PAUSE:
      case KEYCODE_HEADSETHOOK:
        command =
            getPlayerWrapper().getPlayWhenReady()
                ? () -> sessionStub.pauseForControllerInfo(controllerInfo, UNKNOWN_SEQUENCE_NUMBER)
                : () -> sessionStub.playForControllerInfo(controllerInfo, UNKNOWN_SEQUENCE_NUMBER);
        break;
      case KEYCODE_MEDIA_PLAY:
        command = () -> sessionStub.playForControllerInfo(controllerInfo, UNKNOWN_SEQUENCE_NUMBER);
        break;
      case KEYCODE_MEDIA_PAUSE:
        command = () -> sessionStub.pauseForControllerInfo(controllerInfo, UNKNOWN_SEQUENCE_NUMBER);
        break;
      case KEYCODE_MEDIA_NEXT:
      case KEYCODE_MEDIA_SKIP_FORWARD:
        command =
            () -> sessionStub.seekToNextForControllerInfo(controllerInfo, UNKNOWN_SEQUENCE_NUMBER);
        break;
      case KEYCODE_MEDIA_PREVIOUS:
      case KEYCODE_MEDIA_SKIP_BACKWARD:
        command =
            () ->
                sessionStub.seekToPreviousForControllerInfo(
                    controllerInfo, UNKNOWN_SEQUENCE_NUMBER);
        break;
      case KEYCODE_MEDIA_FAST_FORWARD:
        command =
            () -> sessionStub.seekForwardForControllerInfo(controllerInfo, UNKNOWN_SEQUENCE_NUMBER);
        break;
      case KEYCODE_MEDIA_REWIND:
        command =
            () -> sessionStub.seekBackForControllerInfo(controllerInfo, UNKNOWN_SEQUENCE_NUMBER);
        break;
      case KEYCODE_MEDIA_STOP:
        command = () -> sessionStub.stopForControllerInfo(controllerInfo, UNKNOWN_SEQUENCE_NUMBER);
        break;
      default:
        return false;
    }
    postOrRun(
        getApplicationHandler(),
        () -> {
          if (isDismissNotificationEvent) {
            ListenableFuture<SessionResult> ignored =
                sendCustomCommand(
                    controllerInfo,
                    new SessionCommand(
                        MediaNotification.NOTIFICATION_DISMISSED_EVENT_KEY,
                        /* extras= */ Bundle.EMPTY),
                    /* args= */ Bundle.EMPTY);
          }
          command.run();
          sessionStub.getConnectedControllersManager().flushCommandQueue(controllerInfo);
        });
    return true;
  }

  private void postOrRunOnApplicationHandler(Runnable runnable) {
    Util.postOrRun(getApplicationHandler(), runnable);
  }

  /* @FunctionalInterface */
  interface RemoteControllerTask {

    void run(ControllerCb controller, int seq) throws RemoteException;
  }

  private static class PlayerListener implements Player.Listener {

    private final WeakReference<MediaSessionImpl> session;
    private final WeakReference<PlayerWrapper> player;

    public PlayerListener(MediaSessionImpl session, PlayerWrapper player) {
      this.session = new WeakReference<>(session);
      this.player = new WeakReference<>(player);
    }

    @Override
    public void onPlayerError(PlaybackException error) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo = session.playerInfo.copyWithPlayerError(error);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onPlayerError(seq, error));
    }

    @Override
    public void onMediaItemTransition(
        @Nullable MediaItem mediaItem, @Player.MediaItemTransitionReason int reason) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo = session.playerInfo.copyWithMediaItemTransitionReason(reason);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onMediaItemTransition(seq, mediaItem, reason));
    }

    @Override
    public void onPlayWhenReadyChanged(
        boolean playWhenReady, @Player.PlayWhenReadyChangeReason int reason) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo =
          session.playerInfo.copyWithPlayWhenReady(
              playWhenReady, reason, session.playerInfo.playbackSuppressionReason);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onPlayWhenReadyChanged(seq, playWhenReady, reason));
    }

    @Override
    public void onPlaybackSuppressionReasonChanged(@Player.PlaybackSuppressionReason int reason) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo =
          session.playerInfo.copyWithPlayWhenReady(
              session.playerInfo.playWhenReady,
              session.playerInfo.playWhenReadyChangeReason,
              reason);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onPlaybackSuppressionReasonChanged(seq, reason));
    }

    @Override
    public void onPlaybackStateChanged(@Player.State int playbackState) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo =
          session.playerInfo.copyWithPlaybackState(playbackState, player.getPlayerError());
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> {
            callback.onPlaybackStateChanged(seq, playbackState, player.getPlayerError());
          });
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo = session.playerInfo.copyWithIsPlaying(isPlaying);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onIsPlayingChanged(seq, isPlaying));
      session.schedulePeriodicSessionPositionInfoChanges();
    }

    @Override
    public void onIsLoadingChanged(boolean isLoading) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo = session.playerInfo.copyWithIsLoading(isLoading);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onIsLoadingChanged(seq, isLoading));
      session.schedulePeriodicSessionPositionInfoChanges();
    }

    @Override
    public void onPositionDiscontinuity(
        PositionInfo oldPosition, PositionInfo newPosition, @DiscontinuityReason int reason) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }

      session.playerInfo =
          session.playerInfo.copyWithPositionInfos(oldPosition, newPosition, reason);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) ->
              callback.onPositionDiscontinuity(seq, oldPosition, newPosition, reason));
    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo = session.playerInfo.copyWithPlaybackParameters(playbackParameters);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onPlaybackParametersChanged(seq, playbackParameters));
    }

    @Override
    public void onSeekBackIncrementChanged(long seekBackIncrementMs) {
      MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo = session.playerInfo.copyWithSeekBackIncrement(seekBackIncrementMs);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onSeekBackIncrementChanged(seq, seekBackIncrementMs));
    }

    @Override
    public void onSeekForwardIncrementChanged(long seekForwardIncrementMs) {
      MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo = session.playerInfo.copyWithSeekForwardIncrement(seekForwardIncrementMs);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onSeekForwardIncrementChanged(seq, seekForwardIncrementMs));
    }

    @Override
    public void onTimelineChanged(Timeline timeline, @Player.TimelineChangeReason int reason) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo =
          session.playerInfo.copyWithTimelineAndSessionPositionInfo(
              timeline, player.createSessionPositionInfo(), reason);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ false, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onTimelineChanged(seq, timeline, reason));
    }

    @Override
    public void onPlaylistMetadataChanged(MediaMetadata playlistMetadata) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      session.playerInfo = session.playerInfo.copyWithPlaylistMetadata(playlistMetadata);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onPlaylistMetadataChanged(seq, playlistMetadata));
    }

    @Override
    public void onRepeatModeChanged(@RepeatMode int repeatMode) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo = session.playerInfo.copyWithRepeatMode(repeatMode);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onRepeatModeChanged(seq, repeatMode));
    }

    @Override
    public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo = session.playerInfo.copyWithShuffleModeEnabled(shuffleModeEnabled);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onShuffleModeEnabledChanged(seq, shuffleModeEnabled));
    }

    @Override
    public void onAudioSessionIdChanged(int audioSessionId) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo = session.playerInfo.copyWithAudioSessionId(audioSessionId);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onAudioSessionIdChanged(seq, audioSessionId));
    }

    @Override
    public void onAudioAttributesChanged(AudioAttributes attributes) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo = session.playerInfo.copyWithAudioAttributes(attributes);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (controller, seq) -> controller.onAudioAttributesChanged(seq, attributes));
    }

    @Override
    public void onVideoSizeChanged(VideoSize size) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      session.playerInfo = session.playerInfo.copyWithVideoSize(size);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onVideoSizeChanged(seq, size));
    }

    @Override
    public void onVolumeChanged(@FloatRange(from = 0, to = 1) float volume) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      session.playerInfo = session.playerInfo.copyWithVolume(volume);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onVolumeChanged(seq, volume));
    }

    @Override
    public void onCues(CueGroup cueGroup) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo = new PlayerInfo.Builder(session.playerInfo).setCues(cueGroup).build();
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
    }

    @Override
    public void onDeviceInfoChanged(DeviceInfo deviceInfo) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo = session.playerInfo.copyWithDeviceInfo(deviceInfo);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onDeviceInfoChanged(seq, deviceInfo));
    }

    @Override
    public void onDeviceVolumeChanged(int volume, boolean muted) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo = session.playerInfo.copyWithDeviceVolume(volume, muted);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onDeviceVolumeChanged(seq, volume, muted));
    }

    @Override
    public void onAvailableCommandsChanged(Player.Commands availableCommands) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.handleAvailablePlayerCommandsChanged(availableCommands);
    }

    @Override
    public void onTracksChanged(Tracks tracks) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo = session.playerInfo.copyWithCurrentTracks(tracks);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ false);
      session.dispatchRemoteControllerTaskWithoutReturn(
          (callback, seq) -> callback.onTracksChanged(seq, tracks));
    }

    @Override
    public void onTrackSelectionParametersChanged(TrackSelectionParameters parameters) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo = session.playerInfo.copyWithTrackSelectionParameters(parameters);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskWithoutReturn(
          (callback, seq) -> callback.onTrackSelectionParametersChanged(seq, parameters));
    }

    @Override
    public void onMediaMetadataChanged(MediaMetadata mediaMetadata) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo = session.playerInfo.copyWithMediaMetadata(mediaMetadata);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
      session.dispatchRemoteControllerTaskToLegacyStub(
          (callback, seq) -> callback.onMediaMetadataChanged(seq, mediaMetadata));
    }

    @Override
    public void onSurfaceSizeChanged(int width, int height) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.dispatchRemoteControllerTaskWithoutReturn(
          (controller, seq) -> controller.onSurfaceSizeChanged(seq, width, height));
    }

    @Override
    public void onRenderedFirstFrame() {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      ConnectedControllersManager<IBinder> controllerManager =
          session.sessionStub.getConnectedControllersManager();
      ImmutableList<ControllerInfo> controllers = controllerManager.getConnectedControllers();
      for (int i = 0; i < controllers.size(); i++) {
        ControllerInfo controller = controllers.get(i);
        if (controllerManager.getPlaybackException(controller) == null) {
          session.dispatchRemoteControllerTaskWithoutReturn(
              controller, ControllerCb::onRenderedFirstFrame);
        }
      }
    }

    @Override
    public void onMaxSeekToPreviousPositionChanged(long maxSeekToPreviousPositionMs) {
      @Nullable MediaSessionImpl session = getSession();
      if (session == null) {
        return;
      }
      session.verifyApplicationThread();
      @Nullable PlayerWrapper player = this.player.get();
      if (player == null) {
        return;
      }
      session.playerInfo =
          session.playerInfo.copyWithMaxSeekToPreviousPositionMs(maxSeekToPreviousPositionMs);
      session.onPlayerInfoChangedHandler.sendPlayerInfoChangedMessage(
          /* excludeTimeline= */ true, /* excludeTracks= */ true);
    }

    @Nullable
    private MediaSessionImpl getSession() {
      return this.session.get();
    }
  }

  /**
   * A handler for double click detection.
   *
   * <p>All methods must be called on the application thread.
   */
  private class MediaPlayPauseKeyHandler extends Handler {

    @Nullable private Runnable playPauseTask;

    public MediaPlayPauseKeyHandler(Looper applicationLooper) {
      super(applicationLooper);
    }

    public void setPendingPlayPauseTask(ControllerInfo controllerInfo, KeyEvent keyEvent) {
      playPauseTask =
          () -> {
            if (isMediaNotificationController(controllerInfo)) {
              applyMediaButtonKeyEvent(
                  keyEvent,
                  /* doubleTapCompleted= */ false,
                  /* isDismissNotificationEvent= */ false);
            } else {
              sessionLegacyStub.handleMediaPlayPauseOnHandler(
                  checkNotNull(controllerInfo.getRemoteUserInfo()));
            }
            playPauseTask = null;
          };
      postDelayed(playPauseTask, ViewConfiguration.getDoubleTapTimeout());
    }

    @Nullable
    public Runnable clearPendingPlayPauseTask() {
      if (playPauseTask != null) {
        removeCallbacks(playPauseTask);
        Runnable task = playPauseTask;
        playPauseTask = null;
        return task;
      }
      return null;
    }

    public boolean hasPendingPlayPauseTask() {
      return playPauseTask != null;
    }

    public void flush() {
      @Nullable Runnable task = clearPendingPlayPauseTask();
      if (task != null) {
        postOrRun(this, task);
      }
    }
  }

  private class PlayerInfoChangedHandler extends Handler {

    private static final int MSG_PLAYER_INFO_CHANGED = 1;

    private boolean excludeTimeline;
    private boolean excludeTracks;

    public PlayerInfoChangedHandler(Looper looper) {
      super(looper);
      excludeTimeline = true;
      excludeTracks = true;
    }

    @Override
    public void handleMessage(Message msg) {
      if (msg.what == MSG_PLAYER_INFO_CHANGED) {
        playerInfo =
            playerInfo.copyWithTimelineAndSessionPositionInfo(
                getPlayerWrapper().getCurrentTimelineWithCommandCheck(),
                getPlayerWrapper().createSessionPositionInfo(),
                playerInfo.timelineChangeReason);
        dispatchOnPlayerInfoChanged(playerInfo, excludeTimeline, excludeTracks);
        excludeTimeline = true;
        excludeTracks = true;
      } else {
        throw new IllegalStateException("Invalid message what=" + msg.what);
      }
    }

    public boolean hasPendingPlayerInfoChangedUpdate() {
      return hasMessages(MSG_PLAYER_INFO_CHANGED);
    }

    public void sendPlayerInfoChangedMessage(boolean excludeTimeline, boolean excludeTracks) {
      this.excludeTimeline = this.excludeTimeline && excludeTimeline;
      this.excludeTracks = this.excludeTracks && excludeTracks;
      if (!hasMessages(MSG_PLAYER_INFO_CHANGED)) {
        sendEmptyMessage(MSG_PLAYER_INFO_CHANGED);
      }
    }
  }

  private static final Supplier<Integer> mediaMetadataBitmapMaxSize =
      Suppliers.memoize(MediaSessionImpl::getMediaMetadataBitmapMaxSize);

  @SuppressWarnings("DiscouragedApi") // Using Resources.getIdentifier() is less efficient
  private static int getMediaMetadataBitmapMaxSize() {
    Resources res = Resources.getSystem();
    int maxSize = res.getDisplayMetrics().widthPixels;
    try {
      int id = res.getIdentifier("config_mediaMetadataBitmapMaxSize", "dimen", "android");
      maxSize = res.getDimensionPixelSize(id);
    } catch (Resources.NotFoundException e) {
      // do nothing
    }
    return maxSize;
  }

  /** Returns the maximum dimension of the bitmap (either width or height) that can be used */
  public static int getBitmapDimensionLimit(Context context) {
    int maxSize = mediaMetadataBitmapMaxSize.get();

    // NotificationCompat will scale the bitmaps on API < 27
    if (Build.VERSION.SDK_INT < 27) {
      // Hard-code the value of compat_notification_large_icon_max_width and
      // compat_notification_large_icon_max_width as 320dp because the resource IDs are not public
      // in
      // https://cs.android.com/android/platform/superproject/+/androidx-main:frameworks/support/core/core/src/main/res/values/dimens.xml
      // and therefore cannot be used from here.
      int notificationCompatMaxSize =
          (int)
              TypedValue.applyDimension(
                  TypedValue.COMPLEX_UNIT_DIP, 320, context.getResources().getDisplayMetrics());
      maxSize = max(maxSize, notificationCompatMaxSize);
    }
    return maxSize;
  }
}
