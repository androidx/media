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
import static androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS;
import static androidx.media3.common.Player.COMMAND_PLAY_PAUSE;
import static androidx.media3.common.Player.COMMAND_PREPARE;
import static androidx.media3.common.Player.COMMAND_SEEK_BACK;
import static androidx.media3.common.Player.COMMAND_SEEK_FORWARD;
import static androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_MEDIA_ITEM;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM;
import static androidx.media3.common.Player.COMMAND_SET_MEDIA_ITEM;
import static androidx.media3.common.Player.COMMAND_SET_REPEAT_MODE;
import static androidx.media3.common.Player.COMMAND_SET_SHUFFLE_MODE;
import static androidx.media3.common.Player.COMMAND_SET_SPEED_AND_PITCH;
import static androidx.media3.common.Player.COMMAND_STOP;
import static androidx.media3.common.util.Util.castNonNull;
import static androidx.media3.common.util.Util.postOrRun;
import static androidx.media3.session.MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT;
import static androidx.media3.session.MediaConstants.EXTRAS_KEY_MEDIA_ID_COMPAT;
import static androidx.media3.session.MediaConstants.EXTRAS_KEY_PLAYBACK_SPEED_COMPAT;
import static androidx.media3.session.MediaUtils.intersect;
import static androidx.media3.session.SessionCommand.COMMAND_CODE_CUSTOM;
import static androidx.media3.session.SessionCommand.COMMAND_CODE_SESSION_SET_RATING;
import static androidx.media3.session.SessionError.ERROR_UNKNOWN;
import static androidx.media3.session.SessionResult.RESULT_INFO_SKIPPED;
import static androidx.media3.session.SessionResult.RESULT_SUCCESS;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.KeyEvent;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.util.ObjectsCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
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
import androidx.media3.common.util.Log;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.Util;
import androidx.media3.session.MediaSession.ControllerCb;
import androidx.media3.session.MediaSession.ControllerInfo;
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition;
import androidx.media3.session.SessionCommand.CommandCode;
import androidx.media3.session.legacy.MediaControllerCompat;
import androidx.media3.session.legacy.MediaDescriptionCompat;
import androidx.media3.session.legacy.MediaSessionCompat;
import androidx.media3.session.legacy.MediaSessionCompat.QueueItem;
import androidx.media3.session.legacy.MediaSessionManager;
import androidx.media3.session.legacy.MediaSessionManager.RemoteUserInfo;
import androidx.media3.session.legacy.PlaybackStateCompat;
import androidx.media3.session.legacy.RatingCompat;
import androidx.media3.session.legacy.VolumeProviderCompat;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.checker.initialization.qual.Initialized;

// Getting the commands from MediaControllerCompat'
/* package */ class MediaSessionLegacyStub extends MediaSessionCompat.Callback {

  private static final String TAG = "MediaSessionLegacyStub";

  private static final int PENDING_INTENT_FLAG_MUTABLE =
      SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
  private static final String DEFAULT_MEDIA_SESSION_TAG_PREFIX = "androidx.media3.session.id";
  private static final String DEFAULT_MEDIA_SESSION_TAG_DELIM = ".";

  // Used to call onDisconnected() after the timeout.
  private static final int DEFAULT_CONNECTION_TIMEOUT_MS = 300_000; // 5 min.

  private final ConnectedControllersManager<RemoteUserInfo> connectedControllersManager;

  private final MediaSessionImpl sessionImpl;
  private final MediaSessionManager sessionManager;
  private final ControllerLegacyCbForBroadcast controllerLegacyCbForBroadcast;
  private final ConnectionTimeoutHandler connectionTimeoutHandler;
  private final boolean mayNeedButtonReservationWorkaroundForSeekbar;
  @Nullable private final AndroidAutoConnectionStateObserver androidAutoObserver;
  private final MediaSessionCompat sessionCompat;
  @Nullable private final MediaButtonReceiver runtimeBroadcastReceiver;
  @Nullable private final ComponentName broadcastReceiverComponentName;
  private final boolean playIfSuppressed;

  private volatile long connectionTimeoutMs;
  @Nullable private FutureCallback<Bitmap> pendingBitmapLoadCallback;
  @Nullable private VolumeProviderCompat volumeProviderCompat;
  private int sessionFlags;
  @Nullable private LegacyError legacyError;
  private Bundle legacyExtras;
  private ImmutableList<CommandButton> customLayout;
  private ImmutableList<CommandButton> mediaButtonPreferences;
  private SessionCommands availableSessionCommands;
  private Player.Commands availablePlayerCommands;
  @Nullable private PlaybackException customPlaybackException;
  @Nullable private Player.Commands playerCommandsForErrorState;

  @SuppressWarnings({
    "PendingIntentMutability", // We can't use SaferPendingIntent
    "nullness:method.invocation.invalid" // Method calls on non-final constructor
  })
  public MediaSessionLegacyStub(
      MediaSessionImpl session,
      Uri sessionUri,
      Bundle tokenExtras,
      boolean playIfSuppressed,
      ImmutableList<CommandButton> customLayout,
      ImmutableList<CommandButton> mediaButtonPreferences,
      SessionCommands availableSessionCommands,
      Player.Commands availablePlayerCommands,
      Bundle legacyExtras,
      Looper backgroundLooper) {
    this.sessionImpl = session;
    this.playIfSuppressed = playIfSuppressed;
    this.customLayout = customLayout;
    this.mediaButtonPreferences = mediaButtonPreferences;
    this.availableSessionCommands = availableSessionCommands;
    this.availablePlayerCommands = availablePlayerCommands;
    this.legacyExtras = new Bundle(legacyExtras);
    Context context = sessionImpl.getContext();
    sessionManager = MediaSessionManager.getSessionManager(context);
    controllerLegacyCbForBroadcast = new ControllerLegacyCbForBroadcast();
    connectedControllersManager = new ConnectedControllersManager<>(session);
    connectionTimeoutMs = DEFAULT_CONNECTION_TIMEOUT_MS;
    connectionTimeoutHandler =
        new ConnectionTimeoutHandler(
            session.getApplicationHandler().getLooper(), connectedControllersManager);
    mayNeedButtonReservationWorkaroundForSeekbar =
        mayNeedButtonReservationWorkaroundForSeekbar(context);

    if (!mediaButtonPreferences.isEmpty()) {
      updateCustomLayoutAndLegacyExtrasForMediaButtonPreferences();
    }

    // Assume an app that intentionally puts a `MediaButtonReceiver` into the manifest has
    // implemented some kind of resumption of the last recently played media item.
    broadcastReceiverComponentName = queryPackageManagerForMediaButtonReceiver(context);
    @Nullable ComponentName receiverComponentName = broadcastReceiverComponentName;
    boolean isReceiverComponentAService = false;
    if (receiverComponentName == null || SDK_INT < 31) {
      // Below API 26, media button events are sent to the receiver at runtime also. We always want
      // these to arrive at the service at runtime. release() then set the receiver for restart if
      // available.
      receiverComponentName =
          getServiceComponentByAction(context, MediaLibraryService.SERVICE_INTERFACE);
      if (receiverComponentName == null) {
        receiverComponentName =
            getServiceComponentByAction(context, MediaSessionService.SERVICE_INTERFACE);
      }
      isReceiverComponentAService =
          receiverComponentName != null
              && !Objects.equals(receiverComponentName, broadcastReceiverComponentName);
    }
    Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON, sessionUri);
    PendingIntent mediaButtonIntent;
    if (receiverComponentName == null) {
      // Neither a media button receiver from the app manifest nor a service available that could
      // handle media button events. Create a runtime receiver and a pending intent for it.
      runtimeBroadcastReceiver = new MediaButtonReceiver();
      IntentFilter filter = new IntentFilter(Intent.ACTION_MEDIA_BUTTON);
      filter.addDataScheme(castNonNull(sessionUri.getScheme()));
      Util.registerReceiverNotExported(context, runtimeBroadcastReceiver, filter);
      // Create a pending intent to be broadcast to the receiver.
      intent.setPackage(context.getPackageName());
      mediaButtonIntent =
          PendingIntent.getBroadcast(
              context, /* requestCode= */ 0, intent, PENDING_INTENT_FLAG_MUTABLE);
      // Creates a fake ComponentName for MediaSessionCompat in pre-L or without a service.
      receiverComponentName = new ComponentName(context, context.getClass());
    } else {
      intent.setComponent(receiverComponentName);
      mediaButtonIntent =
          isReceiverComponentAService
              ? (SDK_INT >= 26
                  ? PendingIntent.getForegroundService(
                      context, /* requestCode= */ 0, intent, PENDING_INTENT_FLAG_MUTABLE)
                  : PendingIntent.getService(
                      context, /* requestCode= */ 0, intent, PENDING_INTENT_FLAG_MUTABLE))
              : PendingIntent.getBroadcast(
                  context, /* requestCode= */ 0, intent, PENDING_INTENT_FLAG_MUTABLE);
      runtimeBroadcastReceiver = null;
    }

    String sessionCompatId =
        TextUtils.join(
            DEFAULT_MEDIA_SESSION_TAG_DELIM,
            new String[] {DEFAULT_MEDIA_SESSION_TAG_PREFIX, session.getId()});
    sessionCompat =
        new MediaSessionCompat(
            context,
            sessionCompatId,
            SDK_INT < 31 ? receiverComponentName : null,
            SDK_INT < 31 ? mediaButtonIntent : null,
            session.getSessionActivity(),
            /* sessionInfo= */ tokenExtras,
            backgroundLooper);
    if (SDK_INT >= 31 && broadcastReceiverComponentName != null) {
      Api31.setMediaButtonBroadcastReceiver(sessionCompat, broadcastReceiverComponentName);
    }

    @SuppressWarnings("nullness:assignment")
    @Initialized
    MediaSessionLegacyStub thisRef = this;
    sessionCompat.setCallback(thisRef, session.getApplicationHandler());

    androidAutoObserver =
        mayNeedButtonReservationWorkaroundForSeekbar
            ? new AndroidAutoConnectionStateObserver(
                context, thisRef::onAndroidAutoConnectionStateChanged)
            : null;
  }

  /**
   * Sets the available commands for the platform session.
   *
   * @param sessionCommands The available {@link SessionCommands}.
   * @param playerCommands The available {@link Player.Commands}.
   */
  public void setAvailableCommands(
      SessionCommands sessionCommands, Player.Commands playerCommands) {
    if (customPlaybackException != null) {
      return;
    }
    boolean commandGetTimelineChanged =
        availablePlayerCommands.contains(Player.COMMAND_GET_TIMELINE)
            != playerCommands.contains(Player.COMMAND_GET_TIMELINE);

    this.availableSessionCommands = sessionCommands;
    this.availablePlayerCommands = playerCommands;

    if (!mediaButtonPreferences.isEmpty()) {
      updateCustomLayoutAndLegacyExtrasForMediaButtonPreferencesAndInformExtrasChanged();
    }

    PlayerWrapper playerWrapper = sessionImpl.getPlayerWrapper();
    postOrRun(
        sessionImpl.getApplicationHandler(),
        () -> {
          // Because setAvailableCommands can be called from any thread and processing the updated
          // state requires going through both player and background thread, we must manually
          // trigger a refresh of the notification and cannot rely on MediaNotificationManager.
          ListenableFuture<Void> completion =
              sessionCompat.setPlaybackState(createPlaybackStateCompat(playerWrapper));
          if (commandGetTimelineChanged) {
            // will refresh notification once all bitmaps are loaded
            controllerLegacyCbForBroadcast.updateQueue(
                playerWrapper.getAvailableCommands().contains(Player.COMMAND_GET_TIMELINE)
                    ? playerWrapper.getCurrentTimeline()
                    : Timeline.EMPTY);
          } else {
            completion.addListener(
                sessionImpl::onNotificationRefreshRequired, MoreExecutors.directExecutor());
          }
        });
  }

  /**
   * Returns the {@link MediaSession.ConnectionResult} using the available commands and settings of
   * the platform session.
   */
  public MediaSession.ConnectionResult getPlatformConnectionResult(MediaSession mediaSession) {
    MediaSession.ConnectionResult.AcceptedResultBuilder result =
        new MediaSession.ConnectionResult.AcceptedResultBuilder(mediaSession)
            .setAvailableSessionCommands(availableSessionCommands)
            .setAvailablePlayerCommands(availablePlayerCommands);
    if (!mediaButtonPreferences.isEmpty()) {
      result.setMediaButtonPreferences(mediaButtonPreferences);
    } else {
      result.setCustomLayout(customLayout);
    }
    return result.build();
  }

  /**
   * Sets the custom layout for the platform session.
   *
   * @param customLayout The list of {@link CommandButton} for defining the custom layout.
   */
  public void setPlatformCustomLayout(ImmutableList<CommandButton> customLayout) {
    this.customLayout = customLayout;
  }

  /**
   * Sets new media button preferences for the platform session.
   *
   * @param mediaButtonPreferences The list of {@link CommandButton} defining the media button
   *     preferences.
   */
  public void setPlatformMediaButtonPreferences(
      ImmutableList<CommandButton> mediaButtonPreferences) {
    this.mediaButtonPreferences = mediaButtonPreferences;
    updateCustomLayoutAndLegacyExtrasForMediaButtonPreferencesAndInformExtrasChanged();
  }

  /**
   * Sets or clears a playback exception override for the platform session.
   *
   * @param playbackException The {@link PlaybackException} or null.
   * @param playerCommandsForErrorState The available {@link Player.Commands} while the exception
   *     override is set.
   */
  public void setPlaybackException(
      @Nullable PlaybackException playbackException,
      @Nullable Player.Commands playerCommandsForErrorState) {
    checkArgument(
        (playbackException == null && playerCommandsForErrorState == null)
            || (playbackException != null && playerCommandsForErrorState != null));
    customPlaybackException = playbackException;
    this.playerCommandsForErrorState = playerCommandsForErrorState;
    if (playbackException != null) {
      maybeUpdateFlags(sessionImpl.getPlayerWrapper());
      updateLegacySessionPlaybackState(sessionImpl.getPlayerWrapper());
    }
  }

  /**
   * Sets the legacy error that will be used when the next {@link PlaybackStateCompat} legacy
   * playback state is created}.
   *
   * <p>This sets the legacy {@link PlaybackStateCompat} to {@link PlaybackStateCompat#STATE_ERROR}
   * if the error is fatal, calls {@link PlaybackStateCompat.Builder#setErrorMessage(int,
   * CharSequence)} and includes the entries of the extras in the {@link Bundle} set with {@link
   * PlaybackStateCompat.Builder#setExtras(Bundle)}.
   *
   * <p>Use {@link #clearLegacyErrorStatus()} to clear the error.
   *
   * @param result The {@link LibraryResult} to convert to a legacy error.
   * @param isFatal Whether the legacy error is fatal.
   */
  public void setLegacyError(LibraryResult<?> result, boolean isFatal) {
    @SuppressLint("WrongConstant")
    @PlaybackStateCompat.ErrorCode
    int legacyErrorCode = LegacyConversions.convertToLegacyErrorCode(result.resultCode);
    if (legacyError != null && legacyError.code == legacyErrorCode) {
      return;
    }
    // Mapping this error to the legacy error state provides backwards compatibility for the
    // documented AAOS error flow:
    // https://developer.android.com/training/cars/media/automotive-os#-error-handling
    String errorMessage =
        result.sessionError != null
            ? result.sessionError.message
            : SessionError.DEFAULT_ERROR_MESSAGE;
    Bundle bundle = Bundle.EMPTY;
    if (result.params != null
        && result.params.extras.containsKey(EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT)) {
      // Backwards compatibility for Callbacks before SessionError was introduced.
      bundle = result.params.extras;
    } else if (result.sessionError != null) {
      bundle = result.sessionError.extras;
    }
    legacyError = new LegacyError(isFatal, legacyErrorCode, errorMessage, bundle);
    updateLegacySessionPlaybackState(sessionImpl.getPlayerWrapper());
  }

  /**
   * Clears the legacy error to resolve the error when creating the next {@link
   * PlaybackStateCompat}.
   */
  public void clearLegacyErrorStatus() {
    if (legacyError != null) {
      legacyError = null;
      updateLegacySessionPlaybackState(sessionImpl.getPlayerWrapper());
    }
  }

  @Nullable
  @SuppressWarnings("QueryPermissionsNeeded") // Needs to be provided in the app manifest.
  private static ComponentName queryPackageManagerForMediaButtonReceiver(Context context) {
    PackageManager pm = context.getPackageManager();
    Intent queryIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
    queryIntent.setPackage(context.getPackageName());
    List<ResolveInfo> resolveInfos = pm.queryBroadcastReceivers(queryIntent, /* flags= */ 0);
    if (resolveInfos.size() == 1) {
      ResolveInfo resolveInfo = resolveInfos.get(0);
      return new ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name);
    } else if (resolveInfos.isEmpty()) {
      return null;
    } else {
      throw new IllegalStateException(
          "Expected 1 broadcast receiver that handles "
              + Intent.ACTION_MEDIA_BUTTON
              + ", found "
              + resolveInfos.size());
    }
  }

  /** Starts to receive commands. */
  public void start() {
    sessionCompat.setActive(true);
  }

  @SuppressWarnings("PendingIntentMutability") // We can't use SaferPendingIntent.
  public void release() {
    if (runtimeBroadcastReceiver != null) {
      sessionImpl.getContext().unregisterReceiver(runtimeBroadcastReceiver);
    }
    if (SDK_INT < 31) {
      if (broadcastReceiverComponentName == null) {
        // No broadcast receiver available. Playback resumption not supported.
        /* mediaButtonReceiverIntent= */ sessionCompat.setMediaButtonReceiver(null);
      } else {
        // Override the runtime receiver with the broadcast receiver for playback resumption.
        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON, sessionImpl.getUri());
        intent.setComponent(broadcastReceiverComponentName);
        PendingIntent mediaButtonReceiverIntent =
            PendingIntent.getBroadcast(
                sessionImpl.getContext(),
                /* requestCode= */ 0,
                intent,
                PENDING_INTENT_FLAG_MUTABLE);
        sessionCompat.setMediaButtonReceiver(mediaButtonReceiverIntent);
      }
    }
    if (androidAutoObserver != null) {
      androidAutoObserver.release();
    }
    // No check for COMMAND_RELEASE needed as MediaControllers can always be released.
    sessionCompat.release();
  }

  public MediaSessionCompat.Token getSessionToken() {
    return sessionCompat.getSessionToken(); // Safe to call on any thread.
  }

  public MediaControllerCompat getControllerCompat() {
    return sessionCompat.getController(); // Safe to call on any thread.
  }

  @Override
  public void onCommand(String commandName, @Nullable Bundle args, @Nullable ResultReceiver cb) {
    checkNotNull(commandName);
    if (commandName.equals(MediaConstants.SESSION_COMMAND_MEDIA3_CHANGE_REQUEST)) {
      // Only applicable to controllers on Media3 1.5, where this command was sent via sendCommand
      // instead of sendCustomAction. No need to handle this command here.
      return;
    }
    if (commandName.equals(MediaConstants.SESSION_COMMAND_REQUEST_SESSION3_TOKEN) && cb != null) {
      cb.send(RESULT_SUCCESS, sessionImpl.getToken().toBundle());
      return;
    }
    SessionCommand command = new SessionCommand(commandName, /* extras= */ Bundle.EMPTY);
    dispatchSessionTaskWithSessionCommand(
        command,
        controller -> {
          ListenableFuture<SessionResult> future =
              sessionImpl.onCustomCommandOnHandler(
                  controller,
                  /* progressReporter= */ null,
                  command,
                  args == null ? Bundle.EMPTY : args);
          if (cb != null) {
            sendCustomCommandResultWhenReady(cb, future);
          } else {
            ignoreFuture(future);
          }
        });
  }

  @Override
  public void onCustomAction(String action, @Nullable Bundle args) {
    if (action.equals(MediaConstants.SESSION_COMMAND_MEDIA3_CHANGE_REQUEST)) {
      // Ignore, no need to handle the custom action.
      return;
    }
    Bundle nonNullArgs = args != null ? args : Bundle.EMPTY;
    SessionCommand command = new SessionCommand(action, nonNullArgs);
    if (CommandButton.isPredefinedCustomCommandButtonCode(command.customAction)) {
      dispatchCustomCommandAsPredefinedCommand(command);
      return;
    }
    dispatchSessionTaskWithSessionCommand(
        command,
        controller ->
            ignoreFuture(
                sessionImpl.onCustomCommandOnHandler(
                    controller, /* progressReporter= */ null, command, nonNullArgs)));
  }

  @Override
  public boolean onMediaButtonEvent(Intent intent) {
    return sessionImpl.onMediaButtonEvent(
        new ControllerInfo(
            checkNotNull(sessionCompat.getCurrentControllerInfo()),
            ControllerInfo.LEGACY_CONTROLLER_VERSION,
            ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
            /* trusted= */ false,
            /* cb= */ null,
            /* connectionHints= */ Bundle.EMPTY,
            /* maxCommandsForMediaItems= */ 0,
            /* isPackageNameVerified= */ SDK_INT >= 33),
        intent);
  }

  /* package */ void maybeUpdateFlags(PlayerWrapper playerWrapper) {
    int newFlags =
        playerWrapper.isCommandAvailable(COMMAND_CHANGE_MEDIA_ITEMS)
            ? MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS
            : 0;
    if (sessionFlags != newFlags) {
      sessionFlags = newFlags;
      sessionCompat.setFlags(sessionFlags);
    }
  }

  /* package */ void handleMediaPlayPauseOnHandler(RemoteUserInfo remoteUserInfo) {
    dispatchSessionTaskWithPlayerCommand(
        COMMAND_PLAY_PAUSE,
        controller ->
            Util.handlePlayPauseButtonAction(
                sessionImpl.getPlayerWrapper(), sessionImpl.shouldPlayIfSuppressed()),
        remoteUserInfo,
        /* callOnPlayerInteractionFinished= */ true);
  }

  @Override
  public void onPrepare() {
    dispatchSessionTaskWithPlayerCommand(
        COMMAND_PREPARE,
        controller -> sessionImpl.getPlayerWrapper().prepare(),
        sessionCompat.getCurrentControllerInfo(),
        /* callOnPlayerInteractionFinished= */ true);
  }

  @Override
  public void onPrepareFromMediaId(@Nullable String mediaId, @Nullable Bundle extras) {
    handleMediaRequest(
        createMediaItemForMediaRequest(
            mediaId, /* mediaUri= */ null, /* searchQuery= */ null, extras),
        /* play= */ false);
  }

  @Override
  public void onPrepareFromSearch(@Nullable String query, @Nullable Bundle extras) {
    handleMediaRequest(
        createMediaItemForMediaRequest(/* mediaId= */ null, /* mediaUri= */ null, query, extras),
        /* play= */ false);
  }

  @Override
  public void onPrepareFromUri(@Nullable Uri mediaUri, @Nullable Bundle extras) {
    handleMediaRequest(
        createMediaItemForMediaRequest(
            /* mediaId= */ null, mediaUri, /* searchQuery= */ null, extras),
        /* play= */ false);
  }

  @Override
  public void onPlay() {
    dispatchSessionTaskWithPlayRequest();
  }

  @Override
  public void onPlayFromMediaId(@Nullable String mediaId, @Nullable Bundle extras) {
    handleMediaRequest(
        createMediaItemForMediaRequest(
            mediaId, /* mediaUri= */ null, /* searchQuery= */ null, extras),
        /* play= */ true);
  }

  @Override
  public void onPlayFromSearch(@Nullable String query, @Nullable Bundle extras) {
    handleMediaRequest(
        createMediaItemForMediaRequest(/* mediaId= */ null, /* mediaUri= */ null, query, extras),
        /* play= */ true);
  }

  @Override
  public void onPlayFromUri(@Nullable Uri mediaUri, @Nullable Bundle extras) {
    handleMediaRequest(
        createMediaItemForMediaRequest(
            /* mediaId= */ null, mediaUri, /* searchQuery= */ null, extras),
        /* play= */ true);
  }

  @Override
  public void onPause() {
    dispatchSessionTaskWithPlayerCommand(
        COMMAND_PLAY_PAUSE,
        controller -> Util.handlePauseButtonAction(sessionImpl.getPlayerWrapper()),
        sessionCompat.getCurrentControllerInfo(),
        /* callOnPlayerInteractionFinished= */ true);
  }

  @Override
  public void onStop() {
    dispatchSessionTaskWithPlayerCommand(
        COMMAND_STOP,
        controller -> sessionImpl.getPlayerWrapper().stop(),
        sessionCompat.getCurrentControllerInfo(),
        /* callOnPlayerInteractionFinished= */ true);
  }

  @Override
  public void onSeekTo(long pos) {
    dispatchSessionTaskWithPlayerCommand(
        COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        controller -> sessionImpl.getPlayerWrapper().seekTo(pos),
        sessionCompat.getCurrentControllerInfo(),
        /* callOnPlayerInteractionFinished= */ true);
  }

  @Override
  public void onSkipToNext() {
    if (sessionImpl.getPlayerWrapper().isCommandAvailable(COMMAND_SEEK_TO_NEXT)) {
      dispatchSessionTaskWithPlayerCommand(
          COMMAND_SEEK_TO_NEXT,
          controller -> sessionImpl.getPlayerWrapper().seekToNext(),
          sessionCompat.getCurrentControllerInfo(),
          /* callOnPlayerInteractionFinished= */ true);
    } else {
      dispatchSessionTaskWithPlayerCommand(
          COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
          controller -> sessionImpl.getPlayerWrapper().seekToNextMediaItem(),
          sessionCompat.getCurrentControllerInfo(),
          /* callOnPlayerInteractionFinished= */ true);
    }
  }

  @Override
  public void onSkipToPrevious() {
    if (sessionImpl.getPlayerWrapper().isCommandAvailable(COMMAND_SEEK_TO_PREVIOUS)) {
      dispatchSessionTaskWithPlayerCommand(
          COMMAND_SEEK_TO_PREVIOUS,
          controller -> sessionImpl.getPlayerWrapper().seekToPrevious(),
          sessionCompat.getCurrentControllerInfo(),
          /* callOnPlayerInteractionFinished= */ true);
    } else {
      dispatchSessionTaskWithPlayerCommand(
          COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
          controller -> sessionImpl.getPlayerWrapper().seekToPreviousMediaItem(),
          sessionCompat.getCurrentControllerInfo(),
          /* callOnPlayerInteractionFinished= */ true);
    }
  }

  @Override
  public void onSetPlaybackSpeed(float speed) {
    if (!(speed > 0f)) {
      return;
    }
    dispatchSessionTaskWithPlayerCommand(
        COMMAND_SET_SPEED_AND_PITCH,
        controller -> sessionImpl.getPlayerWrapper().setPlaybackSpeed(speed),
        sessionCompat.getCurrentControllerInfo(),
        /* callOnPlayerInteractionFinished= */ true);
  }

  @Override
  public void onSkipToQueueItem(long queueId) {
    if (queueId < 0) {
      return;
    }
    dispatchSessionTaskWithPlayerCommand(
        COMMAND_SEEK_TO_MEDIA_ITEM,
        controller -> {
          PlayerWrapper playerWrapper = sessionImpl.getPlayerWrapper();
          // Use queueId as an index as we've published {@link QueueItem} as so.
          // see: {@link MediaUtils#convertToQueueItem}.
          playerWrapper.seekToDefaultPosition((int) queueId);
        },
        sessionCompat.getCurrentControllerInfo(),
        /* callOnPlayerInteractionFinished= */ true);
  }

  @Override
  public void onFastForward() {
    dispatchSessionTaskWithPlayerCommand(
        COMMAND_SEEK_FORWARD,
        controller -> sessionImpl.getPlayerWrapper().seekForward(),
        sessionCompat.getCurrentControllerInfo(),
        /* callOnPlayerInteractionFinished= */ true);
  }

  @Override
  public void onRewind() {
    dispatchSessionTaskWithPlayerCommand(
        COMMAND_SEEK_BACK,
        controller -> sessionImpl.getPlayerWrapper().seekBack(),
        sessionCompat.getCurrentControllerInfo(),
        /* callOnPlayerInteractionFinished= */ true);
  }

  @Override
  public void onSetRating(@Nullable RatingCompat ratingCompat) {
    onSetRating(ratingCompat, null);
  }

  @Override
  public void onSetRating(@Nullable RatingCompat ratingCompat, @Nullable Bundle unusedExtras) {
    @Nullable Rating rating = LegacyConversions.convertToRating(ratingCompat);
    if (rating == null) {
      Log.w(TAG, "Ignoring invalid RatingCompat " + ratingCompat);
      return;
    }
    dispatchSessionTaskWithSetRatingSessionCommand(rating);
  }

  @Override
  public void onSetCaptioningEnabled(boolean enabled) {
    // no-op
  }

  @Override
  public void onSetRepeatMode(@PlaybackStateCompat.RepeatMode int playbackStateCompatRepeatMode) {
    dispatchSessionTaskWithPlayerCommand(
        COMMAND_SET_REPEAT_MODE,
        controller ->
            sessionImpl
                .getPlayerWrapper()
                .setRepeatMode(
                    LegacyConversions.convertToRepeatMode(playbackStateCompatRepeatMode)),
        sessionCompat.getCurrentControllerInfo(),
        /* callOnPlayerInteractionFinished= */ true);
  }

  @Override
  public void onSetShuffleMode(@PlaybackStateCompat.ShuffleMode int shuffleMode) {
    dispatchSessionTaskWithPlayerCommand(
        COMMAND_SET_SHUFFLE_MODE,
        controller ->
            sessionImpl
                .getPlayerWrapper()
                .setShuffleModeEnabled(LegacyConversions.convertToShuffleModeEnabled(shuffleMode)),
        sessionCompat.getCurrentControllerInfo(),
        /* callOnPlayerInteractionFinished= */ true);
  }

  @Override
  public void onAddQueueItem(@Nullable MediaDescriptionCompat description) {
    handleOnAddQueueItem(description, /* index= */ C.INDEX_UNSET);
  }

  @Override
  public void onAddQueueItem(@Nullable MediaDescriptionCompat description, int index) {
    handleOnAddQueueItem(description, index);
  }

  @Override
  public void onRemoveQueueItem(@Nullable MediaDescriptionCompat description) {
    if (description == null) {
      return;
    }
    dispatchSessionTaskWithPlayerCommand(
        COMMAND_CHANGE_MEDIA_ITEMS,
        controller -> {
          @Nullable String mediaId = description.getMediaId();
          if (TextUtils.isEmpty(mediaId)) {
            Log.w(TAG, "onRemoveQueueItem(): Media ID shouldn't be null");
            return;
          }
          PlayerWrapper player = sessionImpl.getPlayerWrapper();
          if (!player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) {
            Log.w(TAG, "Can't remove item by ID without COMMAND_GET_TIMELINE being available");
            return;
          }
          Timeline timeline = player.getCurrentTimeline();
          Timeline.Window window = new Timeline.Window();
          for (int i = 0; i < timeline.getWindowCount(); i++) {
            MediaItem mediaItem = timeline.getWindow(i, window).mediaItem;
            if (TextUtils.equals(mediaItem.mediaId, mediaId)) {
              player.removeMediaItem(i);
              return;
            }
          }
        },
        sessionCompat.getCurrentControllerInfo(),
        /* callOnPlayerInteractionFinished= */ true);
  }

  public ControllerCb getControllerLegacyCbForBroadcast() {
    return controllerLegacyCbForBroadcast;
  }

  public ConnectedControllersManager<RemoteUserInfo> getConnectedControllersManager() {
    return connectedControllersManager;
  }

  /* package */ boolean canResumePlaybackOnStart() {
    return broadcastReceiverComponentName != null;
  }

  private void dispatchSessionTaskWithPlayRequest() {
    dispatchSessionTaskWithPlayerCommand(
        COMMAND_PLAY_PAUSE,
        controller -> {
          ListenableFuture<SessionResult> resultFuture =
              sessionImpl.handleMediaControllerPlayRequest(
                  controller, /* callOnPlayerInteractionFinished= */ true);
          Futures.addCallback(
              resultFuture,
              new FutureCallback<SessionResult>() {
                @Override
                public void onSuccess(SessionResult result) {
                  if (result.resultCode != RESULT_SUCCESS) {
                    Log.w(TAG, "onPlay() failed: " + result + " (from: " + controller + ")");
                  }
                }

                @Override
                public void onFailure(Throwable t) {
                  Log.e(TAG, "Unexpected exception in onPlay() of " + controller, t);
                }
              },
              MoreExecutors.directExecutor());
        },
        sessionCompat.getCurrentControllerInfo(),
        /* callOnPlayerInteractionFinished= */ false);
  }

  private void dispatchSessionTaskWithPlayerCommand(
      @Player.Command int command,
      SessionTask task,
      @Nullable RemoteUserInfo remoteUserInfo,
      boolean callOnPlayerInteractionFinished) {
    if (sessionImpl.isReleased()) {
      return;
    }
    if (remoteUserInfo == null) {
      Log.d(TAG, "RemoteUserInfo is null, ignoring command=" + command);
      return;
    }
    if (sessionImpl.isReleased()) {
      return;
    }
    if (!sessionCompat.isActive()) {
      Log.w(
          TAG,
          "Ignore incoming player command before initialization. command="
              + command
              + ", pid="
              + remoteUserInfo.getPid());
      return;
    }
    @Nullable ControllerInfo controller = tryGetController(remoteUserInfo);
    if (controller == null) {
      // Failed to get controller since connection was rejected.
      return;
    }
    if (!connectedControllersManager.isPlayerCommandAvailable(controller, command)) {
      if (command == COMMAND_PLAY_PAUSE && !sessionImpl.getPlayerWrapper().getPlayWhenReady()) {
        Log.w(
            TAG,
            "Calling play() omitted due to COMMAND_PLAY_PAUSE not being available. If this"
                + " play command has started the service for instance for playback"
                + " resumption, this may prevent the service from being started into the"
                + " foreground.");
      }
      return;
    }
    int resultCode = sessionImpl.onPlayerCommandRequestOnHandler(controller, command);
    if (resultCode != RESULT_SUCCESS) {
      // Don't run rejected command.
      return;
    }

    sessionImpl
        .callWithControllerForCurrentRequestSet(
            controller,
            () -> {
              try {
                task.run(controller);
              } catch (RemoteException e) {
                // Currently it's TransactionTooLargeException or DeadSystemException.
                // We'd better to leave log for those cases because
                //   - TransactionTooLargeException means that we may need to fix our code.
                //     (e.g. add pagination or special way to deliver Bitmap)
                //   - DeadSystemException means that errors around it can be ignored.
                Log.w(TAG, "Exception in " + controller, e);
              }
            })
        .run();
    if (callOnPlayerInteractionFinished) {
      sessionImpl.onPlayerInteractionFinishedOnHandler(
          controller, new Player.Commands.Builder().add(command).build());
    }
  }

  private void dispatchSessionTaskWithSetRatingSessionCommand(Rating rating) {
    dispatchSessionTaskWithSessionCommandInternal(
        /* sessionCommand= */ null,
        COMMAND_CODE_SESSION_SET_RATING,
        controller -> {
          @Nullable
          MediaItem currentItem =
              sessionImpl.getPlayerWrapper().getCurrentMediaItemWithCommandCheck();
          if (currentItem == null) {
            return;
          }
          // MediaControllerCompat#setRating doesn't return a value.
          ignoreFuture(sessionImpl.onSetRatingOnHandler(controller, currentItem.mediaId, rating));
        },
        sessionCompat.getCurrentControllerInfo());
  }

  private void dispatchSessionTaskWithSessionCommand(
      SessionCommand sessionCommand, SessionTask task) {
    dispatchSessionTaskWithSessionCommandInternal(
        sessionCommand, COMMAND_CODE_CUSTOM, task, sessionCompat.getCurrentControllerInfo());
  }

  private void dispatchSessionTaskWithSessionCommandInternal(
      @Nullable SessionCommand sessionCommand,
      @CommandCode int commandCode,
      SessionTask task,
      @Nullable RemoteUserInfo remoteUserInfo) {
    if (remoteUserInfo == null) {
      Log.d(
          TAG,
          "RemoteUserInfo is null, ignoring command="
              + (sessionCommand == null ? commandCode : sessionCommand));
      return;
    }
    if (sessionImpl.isReleased()) {
      return;
    }
    if (!sessionCompat.isActive()) {
      Log.w(
          TAG,
          "Ignore incoming session command before initialization. command="
              + (sessionCommand == null ? commandCode : sessionCommand.customAction)
              + ", pid="
              + remoteUserInfo.getPid());
      return;
    }
    @Nullable ControllerInfo controller = tryGetController(remoteUserInfo);
    if (controller == null) {
      // Failed to get controller since connection was rejected.
      return;
    }
    if (sessionCommand != null) {
      if (!connectedControllersManager.isSessionCommandAvailable(controller, sessionCommand)) {
        return;
      }
    } else {
      if (!connectedControllersManager.isSessionCommandAvailable(controller, commandCode)) {
        return;
      }
    }
    try {
      task.run(controller);
    } catch (RemoteException e) {
      // Currently it's TransactionTooLargeException or DeadSystemException.
      // We'd better to leave log for those cases because
      //   - TransactionTooLargeException means that we may need to fix our code.
      //     (e.g. add pagination or special way to deliver Bitmap)
      //   - DeadSystemException means that errors around it can be ignored.
      Log.w(TAG, "Exception in " + controller, e);
    }
  }

  private void dispatchCustomCommandAsPredefinedCommand(SessionCommand command) {
    CommandButton actualCommand;
    try {
      actualCommand = CommandButton.convertFromPredefinedCustomCommand(command);
    } catch (RuntimeException e) {
      // Catch exception caused by malformed data from a controller.
      Log.w(TAG, "Failed to convert predefined custom command: " + command.customAction, e);
      return;
    }
    if (!actualCommand.canExecuteAction()) {
      Log.w(TAG, "Can't execute predefined custom command: " + command.customAction);
      return;
    }
    if (actualCommand.sessionCommand != null) {
      checkState(actualCommand.sessionCommand.commandCode == COMMAND_CODE_SESSION_SET_RATING);
      dispatchSessionTaskWithSetRatingSessionCommand(
          (Rating) checkNotNull(actualCommand.parameter));
    } else {
      if (actualCommand.isPlayRequestPlayerAction(sessionImpl.getPlayerWrapper())) {
        dispatchSessionTaskWithPlayRequest();
      } else if (actualCommand.playerCommand == COMMAND_SET_MEDIA_ITEM) {
        handleMediaRequest(
            (MediaItem) checkNotNull(actualCommand.parameter),
            /* prepare= */ false,
            /* play= */ false);
      } else {
        dispatchSessionTaskWithPlayerCommand(
            actualCommand.playerCommand,
            controller -> actualCommand.executePlayerAction(sessionImpl.getPlayerWrapper()),
            sessionCompat.getCurrentControllerInfo(),
            /* callOnPlayerInteractionFinished= */ true);
      }
    }
  }

  @Nullable
  private ControllerInfo tryGetController(RemoteUserInfo remoteUserInfo) {
    @Nullable ControllerInfo controller = connectedControllersManager.getController(remoteUserInfo);
    if (controller == null) {
      // Try connect.
      ControllerCb controllerCb = new ControllerLegacyCb(remoteUserInfo);
      controller =
          new ControllerInfo(
              remoteUserInfo,
              ControllerInfo.LEGACY_CONTROLLER_VERSION,
              ControllerInfo.LEGACY_CONTROLLER_INTERFACE_VERSION,
              sessionManager.isTrustedForMediaControl(remoteUserInfo),
              controllerCb,
              /* connectionHints= */ Bundle.EMPTY,
              /* maxCommandsForMediaItems= */ 0,
              /* isPackageNameVerified= */ SDK_INT >= 33);
      MediaSession.ConnectionResult connectionResult = sessionImpl.onConnectOnHandler(controller);
      if (!connectionResult.isAccepted) {
        controllerCb.onDisconnected(/* seq= */ 0);
        return null;
      }
      connectedControllersManager.addController(
          controller.getRemoteUserInfo(),
          controller,
          connectionResult.availableSessionCommands,
          connectionResult.availablePlayerCommands);
      sessionImpl.onPostConnectOnHandler(controller);
    }
    // Reset disconnect timeout.
    connectionTimeoutHandler.disconnectControllerAfterTimeout(controller, connectionTimeoutMs);

    return controller;
  }

  public void setLegacyControllerDisconnectTimeoutMs(long timeoutMs) {
    connectionTimeoutMs = timeoutMs;
  }

  public void updateLegacySessionPlaybackState(PlayerWrapper playerWrapper) {
    postOrRun(
        sessionImpl.getApplicationHandler(),
        () -> sessionCompat.setPlaybackState(createPlaybackStateCompat(playerWrapper)));
  }

  private void handleMediaRequest(MediaItem mediaItem, boolean play) {
    handleMediaRequest(mediaItem, /* prepare= */ true, play);
  }

  private void handleMediaRequest(MediaItem mediaItem, boolean prepare, boolean play) {
    dispatchSessionTaskWithPlayerCommand(
        COMMAND_SET_MEDIA_ITEM,
        controller -> {
          ListenableFuture<MediaItemsWithStartPosition> mediaItemsFuture =
              sessionImpl.onSetMediaItemsOnHandler(
                  controller, ImmutableList.of(mediaItem), C.INDEX_UNSET, C.TIME_UNSET);
          Futures.addCallback(
              mediaItemsFuture,
              new FutureCallback<MediaItemsWithStartPosition>() {
                @Override
                public void onSuccess(MediaItemsWithStartPosition mediaItemsWithStartPosition) {
                  postOrRun(
                      sessionImpl.getApplicationHandler(),
                      sessionImpl.callWithControllerForCurrentRequestSet(
                          controller,
                          () -> {
                            PlayerWrapper player = sessionImpl.getPlayerWrapper();
                            MediaUtils.setMediaItemsWithStartIndexAndPosition(
                                player, mediaItemsWithStartPosition);
                            @Player.State int playbackState = player.getPlaybackState();
                            if (prepare) {
                              if (playbackState == Player.STATE_IDLE) {
                                player.prepareIfCommandAvailable();
                              } else if (playbackState == Player.STATE_ENDED) {
                                player.seekToDefaultPositionIfCommandAvailable();
                              }
                            }
                            if (play) {
                              player.playIfCommandAvailable();
                            }
                            sessionImpl.onPlayerInteractionFinishedOnHandler(
                                controller,
                                new Player.Commands.Builder()
                                    .addAll(COMMAND_SET_MEDIA_ITEM, COMMAND_PREPARE)
                                    .addIf(COMMAND_PLAY_PAUSE, play)
                                    .build());
                          }));
                }

                @Override
                public void onFailure(Throwable t) {
                  // Do nothing, the session is free to ignore these requests.
                }
              },
              MoreExecutors.directExecutor());
        },
        sessionCompat.getCurrentControllerInfo(),
        /* callOnPlayerInteractionFinished= */ false);
  }

  private void handleOnAddQueueItem(@Nullable MediaDescriptionCompat description, int index) {
    if (description == null || (index != C.INDEX_UNSET && index < 0)) {
      return;
    }
    dispatchSessionTaskWithPlayerCommand(
        COMMAND_CHANGE_MEDIA_ITEMS,
        controller -> {
          @Nullable String mediaId = description.getMediaId();
          if (TextUtils.isEmpty(mediaId)) {
            Log.w(TAG, "onAddQueueItem(): Media ID shouldn't be empty");
            return;
          }
          MediaItem mediaItem = LegacyConversions.convertToMediaItem(description);
          ListenableFuture<List<MediaItem>> mediaItemsFuture =
              sessionImpl.onAddMediaItemsOnHandler(controller, ImmutableList.of(mediaItem));
          Futures.addCallback(
              mediaItemsFuture,
              new FutureCallback<List<MediaItem>>() {
                @Override
                public void onSuccess(List<MediaItem> mediaItems) {
                  postOrRun(
                      sessionImpl.getApplicationHandler(),
                      sessionImpl.callWithControllerForCurrentRequestSet(
                          controller,
                          () -> {
                            if (index == C.INDEX_UNSET) {
                              sessionImpl.getPlayerWrapper().addMediaItems(mediaItems);
                            } else {
                              sessionImpl.getPlayerWrapper().addMediaItems(index, mediaItems);
                            }
                            sessionImpl.onPlayerInteractionFinishedOnHandler(
                                controller,
                                new Player.Commands.Builder()
                                    .add(COMMAND_CHANGE_MEDIA_ITEMS)
                                    .build());
                          }));
                }

                @Override
                public void onFailure(Throwable t) {
                  // Do nothing, the session is free to ignore these requests.
                }
              },
              MoreExecutors.directExecutor());
        },
        sessionCompat.getCurrentControllerInfo(),
        /* callOnPlayerInteractionFinished= */ false);
  }

  private static void sendCustomCommandResultWhenReady(
      ResultReceiver receiver, ListenableFuture<SessionResult> future) {
    future.addListener(
        () -> {
          SessionResult result;
          try {
            result = checkNotNull(future.get(), "SessionResult must not be null");
          } catch (CancellationException e) {
            Log.w(TAG, "Custom command cancelled", e);
            result = new SessionResult(RESULT_INFO_SKIPPED);
          } catch (ExecutionException | InterruptedException e) {
            Log.w(TAG, "Custom command failed", e);
            result = new SessionResult(ERROR_UNKNOWN);
          }
          receiver.send(result.resultCode, result.extras);
        },
        MoreExecutors.directExecutor());
  }

  private static <T> void ignoreFuture(Future<T> unused) {
    // no-op
  }

  private boolean isQueueEnabled() {
    PlayerWrapper playerWrapper = sessionImpl.getPlayerWrapper();
    return availablePlayerCommands.contains(Player.COMMAND_GET_TIMELINE)
        && playerWrapper.getAvailableCommands().contains(Player.COMMAND_GET_TIMELINE);
  }

  private void onAndroidAutoConnectionStateChanged() {
    postOrRun(
        sessionImpl.getApplicationHandler(),
        this::updateCustomLayoutAndLegacyExtrasForMediaButtonPreferencesAndInformExtrasChanged);
  }

  private void updateCustomLayoutAndLegacyExtrasForMediaButtonPreferencesAndInformExtrasChanged() {
    boolean hadPrevReservation =
        legacyExtras.getBoolean(
            MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_PREV, /* defaultValue= */ false);
    boolean hadNextReservation =
        legacyExtras.getBoolean(
            MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_NEXT, /* defaultValue= */ false);
    updateCustomLayoutAndLegacyExtrasForMediaButtonPreferences();
    boolean extrasChanged =
        (legacyExtras.getBoolean(
                    MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_PREV,
                    /* defaultValue= */ false)
                != hadPrevReservation)
            || (legacyExtras.getBoolean(
                    MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_NEXT,
                    /* defaultValue= */ false)
                != hadNextReservation);
    if (extrasChanged) {
      sessionCompat.setExtras(legacyExtras);
    }
  }

  private void updateCustomLayoutAndLegacyExtrasForMediaButtonPreferences() {
    ImmutableList<CommandButton> mediaButtonPreferencesWithUnavailableButtonsDisabled =
        CommandButton.copyWithUnavailableButtonsDisabled(
            mediaButtonPreferences,
            availableSessionCommands,
            playerCommandsForErrorState != null
                ? playerCommandsForErrorState
                : availablePlayerCommands);
    customLayout =
        CommandButton.getCustomLayoutFromMediaButtonPreferences(
            mediaButtonPreferencesWithUnavailableButtonsDisabled,
            /* backSlotAllowed= */ true,
            /* forwardSlotAllowed= */ true,
            MediaLibraryInfo.INTERFACE_VERSION);
    if (needsButtonReservationWorkaroundForSeekbar(androidAutoObserver)) {
      // When applying the workaround, if no custom back slot button is defined and other custom
      // forward or overflow buttons exist, we need to reserve the back slot to prevent the other
      // buttons from moving into this slot. The forward slot should never be reserved to avoid gaps
      // in the output. We explicitly clear the value to avoid any manually defined extras to
      // interfere with our logic.
      boolean reserveBackSpaceSlot =
          !customLayout.isEmpty()
              && !CommandButton.containsButtonForSlot(customLayout, CommandButton.SLOT_BACK);
      legacyExtras.putBoolean(
          MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_PREV, reserveBackSpaceSlot);
      legacyExtras.putBoolean(MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_NEXT, false);
    } else {
      // Without the workaround, set the reservations to match our actual slot definition.
      legacyExtras.putBoolean(
          MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_PREV,
          !CommandButton.containsButtonForSlot(customLayout, CommandButton.SLOT_BACK));
      legacyExtras.putBoolean(
          MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_NEXT,
          !CommandButton.containsButtonForSlot(customLayout, CommandButton.SLOT_FORWARD));
    }
  }

  private static MediaItem createMediaItemForMediaRequest(
      @Nullable String mediaId,
      @Nullable Uri mediaUri,
      @Nullable String searchQuery,
      @Nullable Bundle extras) {
    return new MediaItem.Builder()
        .setMediaId(mediaId == null ? MediaItem.DEFAULT_MEDIA_ID : mediaId)
        .setRequestMetadata(
            new MediaItem.RequestMetadata.Builder()
                .setMediaUri(mediaUri)
                .setSearchQuery(searchQuery)
                .setExtras(extras)
                .build())
        .build();
  }

  /* @FunctionalInterface */
  private interface SessionTask {

    void run(ControllerInfo controller) throws RemoteException;
  }

  private static final class ControllerLegacyCb implements ControllerCb {

    private final RemoteUserInfo remoteUserInfo;

    public ControllerLegacyCb(RemoteUserInfo remoteUserInfo) {
      this.remoteUserInfo = remoteUserInfo;
    }

    @Override
    public int hashCode() {
      return ObjectsCompat.hash(remoteUserInfo);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null || obj.getClass() != ControllerLegacyCb.class) {
        return false;
      }
      ControllerLegacyCb other = (ControllerLegacyCb) obj;
      return Objects.equals(remoteUserInfo, other.remoteUserInfo);
    }
  }

  private final class ControllerLegacyCbForBroadcast implements ControllerCb {

    private MediaMetadata lastMediaMetadata;
    private String lastMediaId;
    @Nullable private Uri lastMediaUri;
    private long lastDurationMs;

    public ControllerLegacyCbForBroadcast() {
      lastMediaMetadata = MediaMetadata.EMPTY;
      lastMediaId = MediaItem.DEFAULT_MEDIA_ID;
      lastDurationMs = C.TIME_UNSET;
    }

    /**
     * Returns whether to skip updates of the {@linkplain android.media.session.PlaybackState
     * playback state} of the platform session to leave the playback state unchanged.
     *
     * @return True if updates should be skipped.
     */
    public boolean skipLegacySessionPlaybackStateUpdates() {
      return customPlaybackException != null;
    }

    @Override
    public void onAvailableCommandsChangedFromPlayer(int seq, Player.Commands availableCommands) {
      if (skipLegacySessionPlaybackStateUpdates()) {
        return;
      }
      PlayerWrapper playerWrapper = sessionImpl.getPlayerWrapper();
      maybeUpdateFlags(playerWrapper);
      updateLegacySessionPlaybackState(playerWrapper);
    }

    @Override
    public void onDisconnected(int seq) {
      // Calling MediaSessionCompat#release() is already done in release().
    }

    @Override
    public void onPlayerChanged(
        int seq, @Nullable PlayerWrapper oldPlayerWrapper, PlayerWrapper newPlayerWrapper)
        throws RemoteException {
      // Tells the playlist change first, so current media item index change notification
      // can point to the valid current media item in the playlist.
      Timeline newTimeline = newPlayerWrapper.getCurrentTimelineWithCommandCheck();
      if (oldPlayerWrapper == null
          || !Objects.equals(oldPlayerWrapper.getCurrentTimelineWithCommandCheck(), newTimeline)) {
        onTimelineChanged(seq, newTimeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED);
      }
      MediaMetadata newPlaylistMetadata = newPlayerWrapper.getPlaylistMetadataWithCommandCheck();
      if (oldPlayerWrapper == null
          || !Objects.equals(
              oldPlayerWrapper.getPlaylistMetadataWithCommandCheck(), newPlaylistMetadata)) {
        onPlaylistMetadataChanged(seq, newPlaylistMetadata);
      }
      MediaMetadata newMediaMetadata = newPlayerWrapper.getMediaMetadataWithCommandCheck();
      if (oldPlayerWrapper == null
          || !Objects.equals(
              oldPlayerWrapper.getMediaMetadataWithCommandCheck(), newMediaMetadata)) {
        onMediaMetadataChanged(seq, newMediaMetadata);
      }
      if (oldPlayerWrapper == null
          || oldPlayerWrapper.getShuffleModeEnabled() != newPlayerWrapper.getShuffleModeEnabled()) {
        onShuffleModeEnabledChanged(seq, newPlayerWrapper.getShuffleModeEnabled());
      }
      if (oldPlayerWrapper == null
          || oldPlayerWrapper.getRepeatMode() != newPlayerWrapper.getRepeatMode()) {
        onRepeatModeChanged(seq, newPlayerWrapper.getRepeatMode());
      }

      // Forcefully update device info to update VolumeProviderCompat attached to the old player.
      onDeviceInfoChanged(seq, newPlayerWrapper.getDeviceInfo());

      // Rest of changes are all notified via PlaybackStateCompat.
      maybeUpdateFlags(newPlayerWrapper);
      @Nullable MediaItem newMediaItem = newPlayerWrapper.getCurrentMediaItemWithCommandCheck();
      if (oldPlayerWrapper == null
          || !Objects.equals(
              oldPlayerWrapper.getCurrentMediaItemWithCommandCheck(), newMediaItem)) {
        // Note: This will update both PlaybackStateCompat and metadata.
        onMediaItemTransition(
            seq, newMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED);
      } else if (!skipLegacySessionPlaybackStateUpdates()) {
        // If PlaybackStateCompat isn't updated by above if-statement, forcefully update
        // PlaybackStateCompat to tell the latest position and its event
        // time. This would also update playback speed, buffering state, player state, and error.
        updateLegacySessionPlaybackState(newPlayerWrapper);
      }
    }

    @Override
    public void onPlayerError(int seq, @Nullable PlaybackException playerError) {
      if (skipLegacySessionPlaybackStateUpdates()) {
        return;
      }
      updateLegacySessionPlaybackState(sessionImpl.getPlayerWrapper());
    }

    @Override
    public void setCustomLayout(int seq, List<CommandButton> layout) {
      updateLegacySessionPlaybackState(sessionImpl.getPlayerWrapper());
    }

    @Override
    public void setMediaButtonPreferences(int seq, List<CommandButton> mediaButtonPreferences) {
      updateLegacySessionPlaybackState(sessionImpl.getPlayerWrapper());
    }

    @Override
    public void onSessionExtrasChanged(int seq, Bundle sessionExtras) {
      checkArgument(!sessionExtras.containsKey(EXTRAS_KEY_PLAYBACK_SPEED_COMPAT));
      checkArgument(!sessionExtras.containsKey(EXTRAS_KEY_MEDIA_ID_COMPAT));
      legacyExtras = new Bundle(sessionExtras);
      if (!mediaButtonPreferences.isEmpty()) {
        // Re-calculate custom layout in case we have to set any additional extras.
        updateCustomLayoutAndLegacyExtrasForMediaButtonPreferences();
      }
      sessionCompat.setExtras(legacyExtras);
      updateLegacySessionPlaybackState(sessionImpl.getPlayerWrapper());
    }

    @Override
    public void onSessionActivityChanged(int seq, @Nullable PendingIntent sessionActivity) {
      sessionCompat.setSessionActivity(sessionActivity);
    }

    @Override
    public void onError(int seq, SessionError sessionError) {
      PlayerWrapper playerWrapper = sessionImpl.getPlayerWrapper();
      legacyError =
          new LegacyError(
              /* isFatal= */ false,
              LegacyConversions.convertToLegacyErrorCode(sessionError.code),
              sessionError.message,
              sessionError.extras);
      if (!skipLegacySessionPlaybackStateUpdates()) {
        PlaybackStateCompat playbackStateCompat = createPlaybackStateCompat(playerWrapper);
        sessionCompat.setPlaybackState(playbackStateCompat);
        legacyError = null;
        updateLegacySessionPlaybackState(playerWrapper);
      }
    }

    @Override
    public void sendCustomCommand(int seq, SessionCommand command, Bundle args) {
      Bundle extras;
      if (args.isEmpty()) {
        extras = command.customExtras;
      } else if (command.customExtras.isEmpty()) {
        extras = args;
      } else {
        extras = new Bundle(command.customExtras);
        extras.putAll(args);
      }
      sessionCompat.sendSessionEvent(command.customAction, extras);
    }

    @Override
    public void onPlayWhenReadyChanged(
        int seq, boolean playWhenReady, @Player.PlaybackSuppressionReason int reason) {
      if (skipLegacySessionPlaybackStateUpdates()) {
        return;
      }
      // Note: This method does not use any of the given arguments.
      updateLegacySessionPlaybackState(sessionImpl.getPlayerWrapper());
    }

    @Override
    public void onPlaybackSuppressionReasonChanged(
        int seq, @Player.PlaybackSuppressionReason int reason) {
      if (skipLegacySessionPlaybackStateUpdates()) {
        return;
      }
      updateLegacySessionPlaybackState(sessionImpl.getPlayerWrapper());
    }

    @Override
    public void onPlaybackStateChanged(
        int seq, @Player.State int state, @Nullable PlaybackException playerError) {
      if (skipLegacySessionPlaybackStateUpdates()) {
        return;
      }
      // Note: This method does not use any of the given arguments.
      updateLegacySessionPlaybackState(sessionImpl.getPlayerWrapper());
    }

    @Override
    public void onIsPlayingChanged(int seq, boolean isPlaying) {
      if (skipLegacySessionPlaybackStateUpdates()) {
        return;
      }
      updateLegacySessionPlaybackState(sessionImpl.getPlayerWrapper());
    }

    @Override
    public void onPositionDiscontinuity(
        int seq,
        PositionInfo oldPosition,
        PositionInfo newPosition,
        @DiscontinuityReason int reason) {
      if (skipLegacySessionPlaybackStateUpdates()) {
        return;
      }
      // Note: This method does not use any of the given arguments.
      updateLegacySessionPlaybackState(sessionImpl.getPlayerWrapper());
    }

    @Override
    public void onPlaybackParametersChanged(int seq, PlaybackParameters playbackParameters) {
      if (skipLegacySessionPlaybackStateUpdates()) {
        return;
      }
      // Note: This method does not use any of the given arguments.
      updateLegacySessionPlaybackState(sessionImpl.getPlayerWrapper());
    }

    @Override
    public void onMediaItemTransition(
        int seq, @Nullable MediaItem mediaItem, @Player.MediaItemTransitionReason int reason) {
      if (skipLegacySessionPlaybackStateUpdates()) {
        return;
      }
      // MediaMetadataCompat needs to be updated when the media ID or URI of the media item changes.
      updateMetadataIfChanged();
      if (mediaItem == null) {
        sessionCompat.setRatingType(RatingCompat.RATING_NONE);
      } else {
        sessionCompat.setRatingType(
            LegacyConversions.getRatingCompatStyle(mediaItem.mediaMetadata.userRating));
      }
      sessionCompat.setPlaybackState(createPlaybackStateCompat(sessionImpl.getPlayerWrapper()));
    }

    @Override
    public void onMediaMetadataChanged(int seq, MediaMetadata mediaMetadata) {
      if (skipLegacySessionPlaybackStateUpdates()) {
        return;
      }
      updateMetadataIfChanged();
    }

    @Override
    public void onTimelineChanged(
        int seq, Timeline timeline, @Player.TimelineChangeReason int reason) {
      if (skipLegacySessionPlaybackStateUpdates()) {
        return;
      }
      updateQueue(timeline);
      // Duration might be unknown at onMediaItemTransition and become available afterward.
      updateMetadataIfChanged();
    }

    private void updateQueue(Timeline timeline) {
      if (!isQueueEnabled() || timeline.isEmpty()) {
        ListenableFuture<Void> completion = sessionCompat.computeAndSetQueue(null);
        completion.addListener(
            sessionImpl::onNotificationRefreshRequired, MoreExecutors.directExecutor());
        return;
      }
      List<MediaItem> mediaItemList = LegacyConversions.convertToMediaItemList(timeline);
      List<@NullableType ListenableFuture<Bitmap>> bitmapFutures = new ArrayList<>();
      final AtomicInteger resultCount = new AtomicInteger(0);
      Runnable handleBitmapFuturesTask =
          () -> {
            int completedBitmapFutureCount = resultCount.incrementAndGet();
            if (completedBitmapFutureCount == mediaItemList.size()) {
              handleBitmapFuturesAllCompletedAndSetQueue(bitmapFutures, mediaItemList);
            }
          };

      for (int i = 0; i < mediaItemList.size(); i++) {
        MediaItem mediaItem = mediaItemList.get(i);
        MediaMetadata metadata = mediaItem.mediaMetadata;
        if (metadata.artworkData == null) {
          bitmapFutures.add(null);
          handleBitmapFuturesTask.run();
        } else {
          ListenableFuture<Bitmap> bitmapFuture =
              sessionImpl.getBitmapLoader().decodeBitmap(metadata.artworkData);
          bitmapFutures.add(bitmapFuture);
          bitmapFuture.addListener(
              handleBitmapFuturesTask, sessionImpl.getApplicationHandler()::post);
        }
      }
    }

    private void handleBitmapFuturesAllCompletedAndSetQueue(
        List<@NullableType ListenableFuture<Bitmap>> bitmapFutures, List<MediaItem> mediaItems) {
      // Framework MediaSession#setQueue() uses ParceledListSlice,
      // which means we can safely send long lists.
      // Do conversions on a background thread instead of the session thread, as we may
      // have a lot
      // of media items to convert.
      ListenableFuture<Void> completion =
          sessionCompat.computeAndSetQueue(
              () -> {
                // Do conversions on a background thread instead of the session thread, as we may
                // have a lot
                // of media items to convert.
                List<QueueItem> queueItemList = new ArrayList<>(bitmapFutures.size());
                for (int i = 0; i < bitmapFutures.size(); i++) {
                  @Nullable ListenableFuture<Bitmap> future = bitmapFutures.get(i);
                  @Nullable Bitmap bitmap = null;
                  if (future != null) {
                    try {
                      bitmap = Futures.getDone(future);
                    } catch (CancellationException | ExecutionException e) {
                      Log.d(TAG, "Failed to get bitmap", e);
                    }
                  }
                  queueItemList.add(
                      LegacyConversions.convertToQueueItem(mediaItems.get(i), i, bitmap));
                }
                return queueItemList;
              });
      // Because we had to wait for futures to complete before we can switch to the background
      // thread, we have to manually notify SystemUI that the notification should be updated,
      // MediaNotificationManager cannot do it for us.
      completion.addListener(
          sessionImpl::onNotificationRefreshRequired, MoreExecutors.directExecutor());
    }

    @Override
    public void onPlaylistMetadataChanged(int seq, MediaMetadata playlistMetadata) {
      if (skipLegacySessionPlaybackStateUpdates()) {
        // Don't update when player is in custom error state for the framework session.
        return;
      }
      // Since there is no 'queue metadata', only set title of the queue.
      @Nullable CharSequence queueTitle = sessionCompat.getController().getQueueTitle();
      @Nullable CharSequence newTitle = isQueueEnabled() ? playlistMetadata.title : null;
      if (!TextUtils.equals(queueTitle, newTitle)) {
        sessionCompat.setQueueTitle(newTitle);
      }
    }

    @Override
    public void onShuffleModeEnabledChanged(int seq, boolean shuffleModeEnabled) {
      sessionCompat.setShuffleMode(
          LegacyConversions.convertToPlaybackStateCompatShuffleMode(shuffleModeEnabled));
    }

    @Override
    public void onRepeatModeChanged(int seq, @RepeatMode int repeatMode) throws RemoteException {
      sessionCompat.setRepeatMode(
          LegacyConversions.convertToPlaybackStateCompatRepeatMode(repeatMode));
    }

    @Override
    public void onAudioAttributesChanged(int seq, AudioAttributes audioAttributes) {
      @DeviceInfo.PlaybackType
      int playbackType = sessionImpl.getPlayerWrapper().getDeviceInfo().playbackType;
      if (playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL) {
        sessionCompat.setPlaybackToLocal(audioAttributes);
      }
    }

    @Override
    public void onDeviceInfoChanged(int seq, DeviceInfo deviceInfo) {
      PlayerWrapper player = sessionImpl.getPlayerWrapper();
      volumeProviderCompat = createVolumeProviderCompat(player);
      if (volumeProviderCompat == null) {
        AudioAttributes audioAttributes = player.getAudioAttributesWithCommandCheck();
        sessionCompat.setPlaybackToLocal(audioAttributes);
      } else {
        sessionCompat.setPlaybackToRemote(volumeProviderCompat);
      }
    }

    @Override
    public void onDeviceVolumeChanged(int seq, int volume, boolean muted) {
      if (volumeProviderCompat != null) {
        volumeProviderCompat.setCurrentVolume(muted ? 0 : volume);
      }
    }

    @Override
    public void onPeriodicSessionPositionInfoChanged(
        int unusedSeq,
        SessionPositionInfo unusedSessionPositionInfo,
        boolean unusedCanAccessCurrentMediaItem,
        boolean unusedCanAccessTimeline,
        int controllerInterfaceVersion) {
      if (!skipLegacySessionPlaybackStateUpdates()) {
        updateLegacySessionPlaybackState(sessionImpl.getPlayerWrapper());
      }
    }

    private void updateMetadataIfChanged() {
      PlayerWrapper player = sessionImpl.getPlayerWrapper();
      @Nullable MediaItem currentMediaItem = player.getCurrentMediaItemWithCommandCheck();
      MediaMetadata newMediaMetadata = player.getMediaMetadataWithCommandCheck();
      long newDurationMs =
          player.isCurrentMediaItemLiveWithCommandCheck()
              ? C.TIME_UNSET
              : player.getDurationWithCommandCheck();
      String newMediaId =
          currentMediaItem != null ? currentMediaItem.mediaId : MediaItem.DEFAULT_MEDIA_ID;
      @Nullable
      Uri newMediaUri =
          currentMediaItem != null && currentMediaItem.requestMetadata.mediaUri != null
              ? currentMediaItem.requestMetadata.mediaUri
              : null;

      if (Objects.equals(lastMediaMetadata, newMediaMetadata)
          && Objects.equals(lastMediaId, newMediaId)
          && Objects.equals(lastMediaUri, newMediaUri)
          && lastDurationMs == newDurationMs) {
        return;
      }

      lastMediaId = newMediaId;
      lastMediaUri = newMediaUri;
      lastMediaMetadata = newMediaMetadata;
      lastDurationMs = newDurationMs;

      @Nullable Bitmap artworkBitmap = null;
      ListenableFuture<Bitmap> bitmapFuture =
          sessionImpl.getBitmapLoader().loadBitmapFromMetadata(newMediaMetadata);
      if (bitmapFuture != null) {
        pendingBitmapLoadCallback = null;
        if (bitmapFuture.isDone()) {
          try {
            artworkBitmap = Futures.getDone(bitmapFuture);
          } catch (CancellationException | ExecutionException e) {
            Log.w(TAG, getBitmapLoadErrorMessage(e));
          }
        } else {
          pendingBitmapLoadCallback =
              new FutureCallback<Bitmap>() {
                @Override
                public void onSuccess(Bitmap result) {
                  if (this != pendingBitmapLoadCallback) {
                    return;
                  }
                  ListenableFuture<Void> completion =
                      sessionCompat.setMetadata(
                          LegacyConversions.convertToMediaMetadataCompat(
                              newMediaMetadata,
                              newMediaId,
                              newMediaUri,
                              newDurationMs,
                              /* artworkBitmap= */ result));
                  completion.addListener(
                      sessionImpl::onNotificationRefreshRequired, MoreExecutors.directExecutor());
                }

                @Override
                public void onFailure(Throwable t) {
                  if (this != pendingBitmapLoadCallback) {
                    return;
                  }
                  Log.w(TAG, getBitmapLoadErrorMessage(t));
                }
              };
          Futures.addCallback(
              bitmapFuture,
              pendingBitmapLoadCallback,
              /* executor= */ sessionImpl.getApplicationHandler()::post);
        }
      }
      sessionCompat.setMetadata(
          LegacyConversions.convertToMediaMetadataCompat(
              newMediaMetadata, newMediaId, newMediaUri, newDurationMs, artworkBitmap));
    }
  }

  private static class ConnectionTimeoutHandler extends Handler {

    private static final int MSG_CONNECTION_TIMED_OUT = 1001;

    private final ConnectedControllersManager<RemoteUserInfo> connectedControllersManager;

    public ConnectionTimeoutHandler(
        Looper looper, ConnectedControllersManager<RemoteUserInfo> connectedControllersManager) {
      super(looper);
      this.connectedControllersManager = connectedControllersManager;
    }

    @Override
    public void handleMessage(Message msg) {
      ControllerInfo controller = (ControllerInfo) msg.obj;
      if (connectedControllersManager.isConnected(controller)) {
        checkNotNull(controller.getControllerCb()).onDisconnected(/* seq= */ 0);
        connectedControllersManager.removeController(controller);
      }
    }

    public void disconnectControllerAfterTimeout(
        ControllerInfo controller, long disconnectTimeoutMs) {
      removeMessages(MSG_CONNECTION_TIMED_OUT, controller);
      Message msg = obtainMessage(MSG_CONNECTION_TIMED_OUT, controller);
      sendMessageDelayed(msg, disconnectTimeoutMs);
    }
  }

  private static String getBitmapLoadErrorMessage(Throwable throwable) {
    return "Failed to load bitmap: " + throwable.getMessage();
  }

  @Nullable
  @SuppressWarnings("QueryPermissionsNeeded") // Needs to be provided in the app manifest.
  private static ComponentName getServiceComponentByAction(Context context, String action) {
    PackageManager pm = context.getPackageManager();
    Intent queryIntent = new Intent(action);
    queryIntent.setPackage(context.getPackageName());
    List<ResolveInfo> resolveInfos = pm.queryIntentServices(queryIntent, /* flags= */ 0);
    if (resolveInfos == null || resolveInfos.isEmpty()) {
      return null;
    }
    ResolveInfo resolveInfo = resolveInfos.get(0);
    return new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name);
  }

  private PlaybackStateCompat createPlaybackStateCompat(PlayerWrapper player) {
    LegacyError legacyError = this.legacyError;
    if (customPlaybackException == null && legacyError != null && legacyError.isFatal) {
      // A fatal legacy error automatically set by Media3 upon a calling
      // MediaLibrarySession.Callback according to the configured LibraryErrorReplicationMode.
      Bundle extras = new Bundle(legacyError.extras);
      extras.putAll(legacyExtras);
      return new PlaybackStateCompat.Builder()
          .setState(
              PlaybackStateCompat.STATE_ERROR,
              /* position= */ PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
              /* playbackSpeed= */ .0f,
              /* updateTime= */ SystemClock.elapsedRealtime())
          .setActions(0)
          .setBufferedPosition(0)
          .setExtras(extras)
          .setErrorMessage(legacyError.code, checkNotNull(legacyError.message))
          .setExtras(legacyError.extras)
          .build();
    }
    // The custom error from the session if present, or the actual error from the player, if any.
    @Nullable
    PlaybackException publicPlaybackException =
        customPlaybackException != null ? customPlaybackException : player.getPlayerError();
    boolean canReadPositions =
        player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            && !player.isCurrentMediaItemLive();
    boolean shouldShowPlayButton =
        publicPlaybackException != null || Util.shouldShowPlayButton(player, playIfSuppressed);
    int state =
        publicPlaybackException != null
            ? PlaybackStateCompat.STATE_ERROR
            : LegacyConversions.convertToPlaybackStateCompatState(player, shouldShowPlayButton);
    // Always advertise ACTION_SET_RATING.
    long actions = PlaybackStateCompat.ACTION_SET_RATING;
    Player.Commands availableCommandsFromPlayer = player.getAvailableCommands();
    Player.Commands availableCommands =
        playerCommandsForErrorState != null
            ? intersect(playerCommandsForErrorState, availableCommandsFromPlayer)
            : intersect(availablePlayerCommands, availableCommandsFromPlayer);
    for (int i = 0; i < availableCommands.size(); i++) {
      actions |=
          convertCommandToPlaybackStateActions(availableCommands.get(i), shouldShowPlayButton);
    }
    if (!mediaButtonPreferences.isEmpty()
        && CommandButton.containsButtonForSlot(customLayout, CommandButton.SLOT_BACK)) {
      actions &= ~PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
    }
    if (!mediaButtonPreferences.isEmpty()
        && CommandButton.containsButtonForSlot(customLayout, CommandButton.SLOT_FORWARD)) {
      actions &= ~PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
    }
    if (!canReadPositions) {
      actions &= ~PlaybackStateCompat.ACTION_SEEK_TO;
    }
    long queueItemId =
        player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)
            ? LegacyConversions.convertToQueueItemId(player.getCurrentMediaItemIndex())
            : MediaSessionCompat.QueueItem.UNKNOWN_ID;
    float playbackSpeed = player.getPlaybackParameters().speed;
    float sessionPlaybackSpeed = player.isPlaying() && canReadPositions ? playbackSpeed : 0f;
    Bundle extras =
        publicPlaybackException != null ? new Bundle(publicPlaybackException.extras) : new Bundle();
    if (publicPlaybackException == null && legacyError != null) {
      extras.putAll(legacyError.extras);
    }
    extras.putAll(legacyExtras);
    extras.putFloat(EXTRAS_KEY_PLAYBACK_SPEED_COMPAT, playbackSpeed);
    @Nullable MediaItem currentMediaItem = player.getCurrentMediaItemWithCommandCheck();
    if (currentMediaItem != null && !MediaItem.DEFAULT_MEDIA_ID.equals(currentMediaItem.mediaId)) {
      extras.putString(EXTRAS_KEY_MEDIA_ID_COMPAT, currentMediaItem.mediaId);
    }
    long compatPosition =
        canReadPositions
            ? player.getCurrentPosition()
            : PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN;
    long compatBufferedPosition =
        canReadPositions
            ? player.getBufferedPosition()
            : PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN;
    PlaybackStateCompat.Builder builder =
        new PlaybackStateCompat.Builder()
            .setState(state, compatPosition, sessionPlaybackSpeed, SystemClock.elapsedRealtime())
            .setActions(actions)
            .setActiveQueueItemId(queueItemId)
            .setBufferedPosition(compatBufferedPosition)
            .setExtras(extras);
    for (int i = 0; i < customLayout.size(); i++) {
      CommandButton commandButton = customLayout.get(i);
      SessionCommand sessionCommand = commandButton.sessionCommand;
      if (sessionCommand != null
          && commandButton.isEnabled
          && sessionCommand.commandCode == SessionCommand.COMMAND_CODE_CUSTOM
          && (CommandButton.isButtonCommandAvailable(
                  commandButton, availableSessionCommands, availableCommands)
              || CommandButton.isPredefinedCustomCommandButtonCode(sessionCommand.customAction))) {
        boolean hasIcon = commandButton.icon != CommandButton.ICON_UNDEFINED;
        boolean hasIconUri = commandButton.iconUri != null;
        Bundle actionExtras =
            hasIcon || hasIconUri || !commandButton.extras.isEmpty()
                ? new Bundle(sessionCommand.customExtras)
                : sessionCommand.customExtras;
        if (!commandButton.extras.isEmpty()) {
          actionExtras.putAll(commandButton.extras);
        }
        if (hasIcon) {
          actionExtras.putInt(
              MediaConstants.EXTRAS_KEY_COMMAND_BUTTON_ICON_COMPAT, commandButton.icon);
        }
        if (hasIconUri) {
          actionExtras.putString(
              MediaConstants.EXTRAS_KEY_COMMAND_BUTTON_ICON_URI_COMPAT,
              checkNotNull(commandButton.iconUri).toString());
        }
        builder.addCustomAction(
            new PlaybackStateCompat.CustomAction.Builder(
                    sessionCommand.customAction, commandButton.displayName, commandButton.iconResId)
                .setExtras(actionExtras)
                .build());
      }
    }
    if (publicPlaybackException != null) {
      builder.setErrorMessage(
          LegacyConversions.convertToLegacyErrorCode(publicPlaybackException),
          publicPlaybackException.getMessage());
    } else if (legacyError != null) {
      builder.setErrorMessage(legacyError.code, legacyError.message);
    }
    return builder.build();
  }

  @SuppressWarnings("deprecation") // Uses deprecated PlaybackStateCompat actions.
  private static long convertCommandToPlaybackStateActions(
      @Player.Command int command, boolean shouldShowPlayButton) {
    switch (command) {
      case Player.COMMAND_PLAY_PAUSE:
        return shouldShowPlayButton
            ? PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PLAY_PAUSE
            : PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE;
      case Player.COMMAND_PREPARE:
        return PlaybackStateCompat.ACTION_PREPARE;
      case Player.COMMAND_SEEK_BACK:
        return PlaybackStateCompat.ACTION_REWIND;
      case Player.COMMAND_SEEK_FORWARD:
        return PlaybackStateCompat.ACTION_FAST_FORWARD;
      case Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM:
        return PlaybackStateCompat.ACTION_SEEK_TO;
      case Player.COMMAND_SEEK_TO_MEDIA_ITEM:
        return PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM;
      case Player.COMMAND_SEEK_TO_NEXT:
      case Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM:
        return PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
      case Player.COMMAND_SEEK_TO_PREVIOUS:
      case Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM:
        return PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
      case Player.COMMAND_SET_MEDIA_ITEM:
        return PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
            | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
            | PlaybackStateCompat.ACTION_PLAY_FROM_URI
            | PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID
            | PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH
            | PlaybackStateCompat.ACTION_PREPARE_FROM_URI;
      case Player.COMMAND_SET_REPEAT_MODE:
        return PlaybackStateCompat.ACTION_SET_REPEAT_MODE;
      case Player.COMMAND_SET_SPEED_AND_PITCH:
        return PlaybackStateCompat.ACTION_SET_PLAYBACK_SPEED;
      case Player.COMMAND_SET_SHUFFLE_MODE:
        return PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE
            | PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE_ENABLED;
      case Player.COMMAND_STOP:
        return PlaybackStateCompat.ACTION_STOP;
      case Player.COMMAND_ADJUST_DEVICE_VOLUME:
      case Player.COMMAND_CHANGE_MEDIA_ITEMS:
      // TODO(b/227346735): Handle this through
      // MediaSessionCompat.setFlags(FLAG_HANDLES_QUEUE_COMMANDS)
      case Player.COMMAND_GET_AUDIO_ATTRIBUTES:
      case Player.COMMAND_GET_CURRENT_MEDIA_ITEM:
      case Player.COMMAND_GET_DEVICE_VOLUME:
      case Player.COMMAND_GET_METADATA:
      case Player.COMMAND_GET_TEXT:
      case Player.COMMAND_GET_TIMELINE:
      case Player.COMMAND_GET_TRACKS:
      case Player.COMMAND_GET_VOLUME:
      case Player.COMMAND_INVALID:
      case Player.COMMAND_SEEK_TO_DEFAULT_POSITION:
      case Player.COMMAND_SET_DEVICE_VOLUME:
      case Player.COMMAND_SET_PLAYLIST_METADATA:
      case Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS:
      case Player.COMMAND_SET_VIDEO_SURFACE:
      case Player.COMMAND_SET_VOLUME:
      default:
        return 0;
    }
  }

  @Nullable
  @SuppressWarnings("deprecation") // Backwards compatibility with old volume commands
  private static VolumeProviderCompat createVolumeProviderCompat(PlayerWrapper player) {
    if (player.getDeviceInfo().playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL) {
      return null;
    }
    Player.Commands availableCommands = player.getAvailableCommands();
    int volumeControlType = VolumeProviderCompat.VOLUME_CONTROL_FIXED;
    if (availableCommands.containsAny(
        Player.COMMAND_ADJUST_DEVICE_VOLUME, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)) {
      volumeControlType = VolumeProviderCompat.VOLUME_CONTROL_RELATIVE;
      if (availableCommands.containsAny(
          Player.COMMAND_SET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)) {
        volumeControlType = VolumeProviderCompat.VOLUME_CONTROL_ABSOLUTE;
      }
    }
    Handler handler = new Handler(player.getApplicationLooper());
    int currentVolume = player.getDeviceVolumeWithCommandCheck();
    int legacyVolumeFlag = C.VOLUME_FLAG_SHOW_UI;
    DeviceInfo deviceInfo = player.getDeviceInfo();
    return new VolumeProviderCompat(
        volumeControlType, deviceInfo.maxVolume, currentVolume, deviceInfo.routingControllerId) {
      @Override
      public void onSetVolumeTo(int volume) {
        postOrRun(
            handler,
            () -> {
              if (!player.isCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME)
                  && !player.isCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)) {
                return;
              }
              if (player.isCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)) {
                player.setDeviceVolume(volume, legacyVolumeFlag);
              } else {
                player.setDeviceVolume(volume);
              }
            });
      }

      @Override
      public void onAdjustVolume(int direction) {
        postOrRun(
            handler,
            () -> {
              if (!player.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME)
                  && !player.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)) {
                return;
              }
              switch (direction) {
                case AudioManager.ADJUST_RAISE:
                  if (player.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)) {
                    player.increaseDeviceVolume(legacyVolumeFlag);
                  } else {
                    player.increaseDeviceVolume();
                  }
                  break;
                case AudioManager.ADJUST_LOWER:
                  if (player.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)) {
                    player.decreaseDeviceVolume(legacyVolumeFlag);
                  } else {
                    player.decreaseDeviceVolume();
                  }
                  break;
                case AudioManager.ADJUST_MUTE:
                  if (player.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)) {
                    player.setDeviceMuted(true, legacyVolumeFlag);
                  } else {
                    player.setDeviceMuted(true);
                  }
                  break;
                case AudioManager.ADJUST_UNMUTE:
                  if (player.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)) {
                    player.setDeviceMuted(false, legacyVolumeFlag);
                  } else {
                    player.setDeviceMuted(false);
                  }
                  break;
                case AudioManager.ADJUST_TOGGLE_MUTE:
                  if (player.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)) {
                    player.setDeviceMuted(
                        !player.isDeviceMutedWithCommandCheck(), legacyVolumeFlag);
                  } else {
                    player.setDeviceMuted(!player.isDeviceMutedWithCommandCheck());
                  }
                  break;
                default:
                  Log.w(
                      "VolumeProviderCompat",
                      "onAdjustVolume: Ignoring unknown direction: " + direction);
                  break;
              }
            });
      }
    };
  }

  private boolean needsButtonReservationWorkaroundForSeekbar(
      @Nullable AndroidAutoConnectionStateObserver androidAutoObserver) {
    // Check if the device is generally known to require the workaround. Also disable the workaround
    // when connected to Android Auto under the assumption that it is the main user interface while
    // connected. See https://github.com/androidx/media/issues/3041.
    if (!mayNeedButtonReservationWorkaroundForSeekbar) {
      return false;
    }
    return androidAutoObserver == null || !androidAutoObserver.isConnected();
  }

  private static boolean mayNeedButtonReservationWorkaroundForSeekbar(Context context) {
    // The stock system UMO has an issue that when a navigation button is reserved, it doesn't
    // automatically fill its empty space with an extended seek bar, leaving an unexpected gap.
    // This affects all manufacturers known to rely on the stock UMO from API 33. See
    // https://github.com/androidx/media/issues/2976.
    if (SDK_INT < 33) {
      return false;
    }
    if (Util.isAutomotive(context)) {
      return false;
    }
    return Build.MANUFACTURER.equals("Google")
        || Build.MANUFACTURER.equals("motorola")
        || Build.MANUFACTURER.equals("vivo")
        || Build.MANUFACTURER.equals("Sony")
        || Build.MANUFACTURER.equals("Nothing")
        || Build.MANUFACTURER.equals("unknown");
  }

  /** Describes a legacy error. */
  private static final class LegacyError {
    public final boolean isFatal;
    @PlaybackStateCompat.ErrorCode public final int code;
    @Nullable public final String message;
    public final Bundle extras;

    /** Creates an instance. */
    private LegacyError(
        boolean isFatal,
        @PlaybackStateCompat.ErrorCode int code,
        @Nullable String message,
        @Nullable Bundle extras) {
      this.isFatal = isFatal;
      this.code = code;
      this.message = message;
      this.extras = extras != null ? extras : Bundle.EMPTY;
    }
  }

  private final class MediaButtonReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
      if (!Objects.equals(intent.getAction(), Intent.ACTION_MEDIA_BUTTON)) {
        return;
      }
      KeyEvent keyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
      if (keyEvent == null) {
        return;
      }
      sessionCompat.getController().dispatchMediaButtonEvent(keyEvent);
    }
  }

  @RequiresApi(31)
  private static final class Api31 {
    public static void setMediaButtonBroadcastReceiver(
        MediaSessionCompat mediaSessionCompat, ComponentName broadcastReceiver) {
      try {
        ((android.media.session.MediaSession) checkNotNull(mediaSessionCompat.getMediaSession()))
            .setMediaButtonBroadcastReceiver(broadcastReceiver);
      } catch (IllegalArgumentException e) {
        if (Build.MANUFACTURER.equals("motorola")) {
          // Internal bug ref: b/367415658
          Log.e(
              TAG,
              "caught IllegalArgumentException on a motorola device when attempting to set the"
                  + " media button broadcast receiver. See"
                  + " https://github.com/androidx/media/issues/1730 for details.",
              e);
        } else {
          throw e;
        }
      }
    }
  }
}
