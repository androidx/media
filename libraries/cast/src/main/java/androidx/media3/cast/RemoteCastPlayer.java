/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.cast;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.util.Util.castNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.min;

import android.content.Context;
import android.media.MediaRouter2;
import android.media.MediaRouter2.RouteCallback;
import android.media.MediaRouter2.RoutingController;
import android.media.MediaRouter2.TransferCallback;
import android.media.RouteDiscoveryPreference;
import android.os.Handler;
import android.os.Looper;
import android.util.Range;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.BasePlayer;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ListenerSet;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.CastStatusCodes;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaQueueData;
import com.google.android.gms.cast.MediaQueueItem;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.MediaTrack;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.cast.framework.media.RemoteMediaClient.MediaChannelResult;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.InlineMe;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/**
 * {@link Player} implementation that communicates with a Cast receiver app.
 *
 * <p>The behavior of this class depends on the underlying Cast session, which is obtained from the
 * injected {@link CastContext}. To keep track of the session, {@link #isCastSessionAvailable()} can
 * be queried and {@link SessionAvailabilityListener} can be implemented and attached to the player.
 *
 * <p>If no session is available, the player state will remain unchanged and calls to methods that
 * alter it will be ignored. Querying the player state is possible even when no session is
 * available, in which case, the last observed receiver app state is reported.
 *
 * <p>Methods should be called on the application's main thread.
 */
@UnstableApi
public final class RemoteCastPlayer extends BasePlayer {

  /**
   * A builder for {@link RemoteCastPlayer} instances.
   *
   * <p>See {@link #Builder(Context)} for the list of default values.
   */
  @UnstableApi
  public static final class Builder {

    private final Context context;
    private MediaItemConverter mediaItemConverter;
    private long seekBackIncrementMs;
    private long seekForwardIncrementMs;
    private long maxSeekToPreviousPositionMs;
    private boolean buildCalled;

    /**
     * Creates a builder.
     *
     * <p>The builder uses the following default values:
     *
     * <ul>
     *   <li>{@link MediaItemConverter}: {@link DefaultMediaItemConverter}.
     *   <li>{@link #setSeekBackIncrementMs}: {@link C#DEFAULT_SEEK_BACK_INCREMENT_MS}.
     *   <li>{@link #setSeekForwardIncrementMs}: {@link C#DEFAULT_SEEK_FORWARD_INCREMENT_MS}.
     *   <li>{@link #setMaxSeekToPreviousPositionMs}: {@link
     *       C#DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS}.
     * </ul>
     *
     * @param context A {@link Context}.
     */
    public Builder(Context context) {
      this.context = context;
      mediaItemConverter = new DefaultMediaItemConverter();
      seekBackIncrementMs = C.DEFAULT_SEEK_BACK_INCREMENT_MS;
      seekForwardIncrementMs = C.DEFAULT_SEEK_FORWARD_INCREMENT_MS;
      maxSeekToPreviousPositionMs = C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS;
    }

    /**
     * Sets the {@link MediaItemConverter} that will be used by the player to convert {@link
     * MediaItem MediaItems}.
     *
     * @param mediaItemConverter A {@link MediaItemConverter}.
     * @return This builder.
     * @throws IllegalStateException If {@link #build()} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setMediaItemConverter(MediaItemConverter mediaItemConverter) {
      checkState(!buildCalled);
      this.mediaItemConverter = checkNotNull(mediaItemConverter);
      return this;
    }

    /**
     * Sets the {@link #seekBack()} increment.
     *
     * @param seekBackIncrementMs The seek back increment, in milliseconds.
     * @return This builder.
     * @throws IllegalArgumentException If {@code seekBackIncrementMs} is non-positive.
     * @throws IllegalStateException If {@link #build()} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setSeekBackIncrementMs(@IntRange(from = 1) long seekBackIncrementMs) {
      checkArgument(seekBackIncrementMs > 0);
      checkState(!buildCalled);
      this.seekBackIncrementMs = seekBackIncrementMs;
      return this;
    }

    /**
     * Sets the {@link #seekForward()} increment.
     *
     * @param seekForwardIncrementMs The seek forward increment, in milliseconds.
     * @return This builder.
     * @throws IllegalArgumentException If {@code seekForwardIncrementMs} is non-positive.
     * @throws IllegalStateException If {@link #build()} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setSeekForwardIncrementMs(@IntRange(from = 1) long seekForwardIncrementMs) {
      checkArgument(seekForwardIncrementMs > 0);
      checkState(!buildCalled);
      this.seekForwardIncrementMs = seekForwardIncrementMs;
      return this;
    }

    /**
     * Sets the maximum position for which {@link #seekToPrevious()} seeks to the previous {@link
     * MediaItem}.
     *
     * @param maxSeekToPreviousPositionMs The maximum position, in milliseconds.
     * @return This builder.
     * @throws IllegalArgumentException If {@code maxSeekToPreviousPositionMs} is negative.
     * @throws IllegalStateException If {@link #build()} has already been called.
     */
    @CanIgnoreReturnValue
    public Builder setMaxSeekToPreviousPositionMs(
        @IntRange(from = 0) long maxSeekToPreviousPositionMs) {
      checkArgument(maxSeekToPreviousPositionMs >= 0L);
      checkState(!buildCalled);
      this.maxSeekToPreviousPositionMs = maxSeekToPreviousPositionMs;
      return this;
    }

    /**
     * Builds and returns a {@link RemoteCastPlayer} instance.
     *
     * @throws IllegalStateException If this method has already been called.
     */
    public RemoteCastPlayer build() {
      checkState(!buildCalled);
      buildCalled = true;
      return new RemoteCastPlayer(this);
    }
  }

  /**
   * Maximum volume to use for {@link #getDeviceVolume()} and {@link #setDeviceVolume}.
   *
   * <p>These methods are implemented around {@link CastSession#setVolume} and {@link
   * CastSession#getVolume} which operate on a {@code [0, 1]} range. So this value allows us to
   * convert to and from the int-based volume scale that {@link #getDeviceVolume()} uses.
   */
  private static final int MAX_VOLUME = 20;

  /**
   * A {@link DeviceInfo#PLAYBACK_TYPE_REMOTE remote} {@link DeviceInfo} with a null {@link
   * DeviceInfo#routingControllerId}.
   */
  public static final DeviceInfo DEVICE_INFO_REMOTE_EMPTY =
      new DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(MAX_VOLUME).build();

  private static final Range<Integer> RANGE_DEVICE_VOLUME = new Range<>(0, MAX_VOLUME);
  private static final Range<Float> RANGE_VOLUME = new Range<>(0.f, 1.f);

  static {
    MediaLibraryInfo.registerModule("media3.cast");
  }

  @VisibleForTesting
  /* package */ static final Commands PERMANENT_AVAILABLE_COMMANDS =
      new Commands.Builder()
          .addAll(
              COMMAND_PLAY_PAUSE,
              COMMAND_PREPARE,
              COMMAND_STOP,
              COMMAND_SEEK_TO_DEFAULT_POSITION,
              COMMAND_SEEK_TO_MEDIA_ITEM,
              COMMAND_GET_DEVICE_VOLUME,
              COMMAND_ADJUST_DEVICE_VOLUME,
              COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
              COMMAND_SET_DEVICE_VOLUME,
              COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
              COMMAND_SET_REPEAT_MODE,
              COMMAND_SET_SPEED_AND_PITCH,
              COMMAND_GET_CURRENT_MEDIA_ITEM,
              COMMAND_GET_TIMELINE,
              COMMAND_GET_METADATA,
              COMMAND_SET_PLAYLIST_METADATA,
              COMMAND_SET_MEDIA_ITEM,
              COMMAND_CHANGE_MEDIA_ITEMS,
              COMMAND_GET_TRACKS,
              COMMAND_RELEASE)
          .build();

  public static final float MIN_SPEED_SUPPORTED = 0.5f;
  public static final float MAX_SPEED_SUPPORTED = 2.0f;

  private static final String TAG = "RemoteCastPlayer";

  private static final long PROGRESS_REPORT_PERIOD_MS = 1000;
  private static final long[] EMPTY_TRACK_ID_ARRAY = new long[0];

  private final CastContext castContext;
  private final MediaItemConverter mediaItemConverter;
  private final long seekBackIncrementMs;
  private final long seekForwardIncrementMs;
  private final long maxSeekToPreviousPositionMs;
  // TODO: Allow custom implementations of CastTimelineTracker.
  private final CastTimelineTracker timelineTracker;
  private final Timeline.Period period;
  @Nullable private final Api30Impl api30Impl;

  // Result callbacks.
  private final Cast.Listener castListener;

  private final StatusListener statusListener;
  private final SeekResultCallback seekResultCallback;

  // Listeners and notification.
  private final ListenerSet<Listener> listeners;
  @Nullable private SessionAvailabilityListener sessionAvailabilityListener;
  @Nullable private SessionAvailabilityListener internalSessionAvailabilityListener;

  // Internal state.
  private final StateHolder<Boolean> playWhenReady;
  private final StateHolder<Integer> repeatMode;
  private boolean isMuted;
  private int deviceVolume;
  private final StateHolder<Float> volume;
  private final StateHolder<PlaybackParameters> playbackParameters;
  @Nullable private CastSession castSession;
  @Nullable private RemoteMediaClient remoteMediaClient;
  private CastTimeline currentTimeline;
  private Tracks currentTracks;
  private Commands availableCommands;
  private @Player.State int playbackState;
  private int currentWindowIndex;
  private long lastReportedPositionMs;
  private int pendingSeekCount;
  private int pendingSeekWindowIndex;
  private long pendingSeekPositionMs;
  @Nullable private PositionInfo pendingMediaItemRemovalPosition;
  private MediaMetadata mediaMetadata;
  private MediaMetadata playlistMetadata;
  private DeviceInfo deviceInfo;

  private RemoteCastPlayer(Builder builder) {
    this(
        builder.context,
        CastContext.getSharedInstance(builder.context),
        builder.mediaItemConverter,
        builder.seekBackIncrementMs,
        builder.seekForwardIncrementMs,
        builder.maxSeekToPreviousPositionMs);
  }

  /**
   * Constructor.
   *
   * <p>Necessary to keep {@link CastPlayer#CastPlayer(Context, CastContext, MediaItemConverter,
   * long, long, long)} part of the API, for backwards compatibility.
   */
  /* package */ RemoteCastPlayer(
      @Nullable Context context,
      CastContext castContext,
      MediaItemConverter mediaItemConverter,
      @IntRange(from = 1) long seekBackIncrementMs,
      @IntRange(from = 1) long seekForwardIncrementMs,
      @IntRange(from = 0) long maxSeekToPreviousPositionMs) {
    checkArgument(seekBackIncrementMs > 0 && seekForwardIncrementMs > 0);
    checkArgument(maxSeekToPreviousPositionMs >= 0L);
    this.castContext = castContext;
    this.mediaItemConverter = mediaItemConverter;
    this.seekBackIncrementMs = seekBackIncrementMs;
    this.seekForwardIncrementMs = seekForwardIncrementMs;
    this.maxSeekToPreviousPositionMs = maxSeekToPreviousPositionMs;
    timelineTracker = new CastTimelineTracker(mediaItemConverter);
    period = new Timeline.Period();
    castListener = new CastListener();
    statusListener = new StatusListener();
    seekResultCallback = new SeekResultCallback();
    listeners =
        new ListenerSet<>(
            Looper.getMainLooper(),
            Clock.DEFAULT,
            (listener, flags) -> listener.onEvents(/* player= */ this, new Events(flags)));
    playWhenReady = new StateHolder<>(false);
    repeatMode = new StateHolder<>(REPEAT_MODE_OFF);
    deviceVolume = MAX_VOLUME;
    volume = new StateHolder<>(1f);
    playbackParameters = new StateHolder<>(PlaybackParameters.DEFAULT);
    playbackState = STATE_IDLE;
    currentTimeline = CastTimeline.EMPTY_CAST_TIMELINE;
    mediaMetadata = MediaMetadata.EMPTY;
    playlistMetadata = MediaMetadata.EMPTY;
    currentTracks = Tracks.EMPTY;
    availableCommands = new Commands.Builder().addAll(PERMANENT_AVAILABLE_COMMANDS).build();
    pendingSeekWindowIndex = C.INDEX_UNSET;
    pendingSeekPositionMs = C.TIME_UNSET;

    SessionManager sessionManager = castContext.getSessionManager();
    sessionManager.addSessionManagerListener(statusListener, CastSession.class);
    setCastSession(sessionManager.getCurrentCastSession());
    updateInternalStateAndNotifyIfChanged();
    if (SDK_INT >= 30 && context != null) {
      api30Impl = new Api30Impl(context);
      api30Impl.initialize();
      deviceInfo = api30Impl.fetchDeviceInfo();
    } else {
      api30Impl = null;
      deviceInfo = DEVICE_INFO_REMOTE_EMPTY;
    }
  }

  /**
   * Returns the item that corresponds to the period with the given id, or null if no media queue or
   * period with id {@code periodId} exist.
   *
   * @param periodId The id of the period ({@link #getCurrentTimeline}) that corresponds to the item
   *     to get.
   * @return The item that corresponds to the period with the given id, or null if no media queue or
   *     period with id {@code periodId} exist.
   */
  @Nullable
  public MediaQueueItem getItem(int periodId) {
    MediaStatus mediaStatus = getMediaStatus();
    return mediaStatus != null && currentTimeline.getIndexOfPeriod(periodId) != C.INDEX_UNSET
        ? mediaStatus.getItemById(periodId)
        : null;
  }

  // CastSession methods.

  /** Returns whether a cast session is available. */
  public boolean isCastSessionAvailable() {
    return remoteMediaClient != null;
  }

  /**
   * Sets a listener for updates on the cast session availability.
   *
   * @param listener The {@link SessionAvailabilityListener}, or null to clear the listener.
   */
  public void setSessionAvailabilityListener(@Nullable SessionAvailabilityListener listener) {
    sessionAvailabilityListener = listener;
  }

  /**
   * Equivalent to {@link #setSessionAvailabilityListener}, except it's not part of the API.
   *
   * <p>Intended to be called from {@link CastPlayerImpl} without overriding any listeners set by
   * the app, so as to avoid breaking the API.
   */
  /* package */ void setInternalSessionAvailabilityListener(
      @Nullable SessionAvailabilityListener listener) {
    internalSessionAvailabilityListener = listener;
  }

  // Player implementation.

  @Override
  public Looper getApplicationLooper() {
    return Looper.getMainLooper();
  }

  @Override
  public void addListener(Listener listener) {
    listeners.add(listener);
  }

  @Override
  public void removeListener(Listener listener) {
    listeners.remove(listener);
  }

  @Override
  public void setMediaItems(List<MediaItem> mediaItems, boolean resetPosition) {
    int mediaItemIndex = resetPosition ? 0 : getCurrentMediaItemIndex();
    long startPositionMs = resetPosition ? C.TIME_UNSET : getContentPosition();
    setMediaItems(mediaItems, mediaItemIndex, startPositionMs);
  }

  @Override
  public void setMediaItems(List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
    setMediaItemsInternal(mediaItems, startIndex, startPositionMs, repeatMode.value);
  }

  @Override
  public void addMediaItems(int index, List<MediaItem> mediaItems) {
    checkArgument(index >= 0);
    int uid = MediaQueueItem.INVALID_ITEM_ID;
    if (index < currentTimeline.getWindowCount()) {
      uid = (int) currentTimeline.getWindow(/* windowIndex= */ index, window).uid;
    }
    addMediaItemsInternal(mediaItems, uid);
  }

  @Override
  public void moveMediaItems(int fromIndex, int toIndex, int newIndex) {
    checkArgument(fromIndex >= 0 && fromIndex <= toIndex && newIndex >= 0);
    int playlistSize = currentTimeline.getWindowCount();
    toIndex = min(toIndex, playlistSize);
    newIndex = min(newIndex, playlistSize - (toIndex - fromIndex));
    if (fromIndex >= playlistSize || fromIndex == toIndex || fromIndex == newIndex) {
      // Do nothing.
      return;
    }
    int[] uids = new int[toIndex - fromIndex];
    for (int i = 0; i < uids.length; i++) {
      uids[i] = (int) currentTimeline.getWindow(/* windowIndex= */ i + fromIndex, window).uid;
    }
    moveMediaItemsInternal(uids, fromIndex, newIndex);
  }

  @Override
  public void replaceMediaItems(int fromIndex, int toIndex, List<MediaItem> mediaItems) {
    checkArgument(fromIndex >= 0 && fromIndex <= toIndex);
    int playlistSize = currentTimeline.getWindowCount();
    if (fromIndex > playlistSize) {
      return;
    }
    toIndex = min(toIndex, playlistSize);
    addMediaItems(toIndex, mediaItems);
    removeMediaItems(fromIndex, toIndex);
  }

  @Override
  public void removeMediaItems(int fromIndex, int toIndex) {
    checkArgument(fromIndex >= 0 && toIndex >= fromIndex);
    int playlistSize = currentTimeline.getWindowCount();
    toIndex = min(toIndex, playlistSize);
    if (fromIndex >= playlistSize || fromIndex == toIndex) {
      // Do nothing.
      return;
    }
    int[] uids = new int[toIndex - fromIndex];
    for (int i = 0; i < uids.length; i++) {
      uids[i] = (int) currentTimeline.getWindow(/* windowIndex= */ i + fromIndex, window).uid;
    }
    removeMediaItemsInternal(uids);
  }

  @Override
  public Commands getAvailableCommands() {
    return availableCommands;
  }

  @Override
  public void prepare() {
    // Do nothing.
  }

  @Override
  public @Player.State int getPlaybackState() {
    // The Player interface requires the state to be idle when the timeline is empty. However, the
    // CastSDK will sometimes enter buffering state before the queue is populated. To prevent
    // clients from observing this discrepancy, we only transition out of idle once the timeline is
    // populated.
    return getCurrentTimeline().isEmpty() ? STATE_IDLE : playbackState;
  }

  @Override
  public @PlaybackSuppressionReason int getPlaybackSuppressionReason() {
    return Player.PLAYBACK_SUPPRESSION_REASON_NONE;
  }

  @Override
  @Nullable
  public PlaybackException getPlayerError() {
    return null;
  }

  @Override
  public void setPlayWhenReady(boolean playWhenReady) {
    if (remoteMediaClient == null) {
      return;
    }
    // We update the local state and send the message to the receiver app, which will cause the
    // operation to be perceived as synchronous by the user. When the operation reports a result,
    // the local state will be updated to reflect the state reported by the Cast SDK.
    setPlayerStateAndNotifyIfChanged(
        playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST, playbackState);
    listeners.flushEvents();
    if (getMediaStatus() == null) {
      // No media status means that both play and pause will fail, causing playWhenReady to be reset
      // to true. By not calling play/pause, the playWhenReady state holder remains populated until
      // either:
      // - It's overwritten by an eventual media status update.
      // - It's used to populate autoplay when the client loads media.
      return;
    }
    PendingResult<MediaChannelResult> pendingResult =
        playWhenReady ? remoteMediaClient.play() : remoteMediaClient.pause();
    this.playWhenReady.pendingResultCallback =
        new ResultCallback<MediaChannelResult>() {
          @Override
          public void onResult(MediaChannelResult mediaChannelResult) {
            if (remoteMediaClient != null) {
              updatePlayerStateAndNotifyIfChanged(this);
              listeners.flushEvents();
            }
          }
        };
    pendingResult.setResultCallback(this.playWhenReady.pendingResultCallback);
  }

  @Override
  public boolean getPlayWhenReady() {
    return playWhenReady.value;
  }

  // We still call Listener#onPositionDiscontinuity(@DiscontinuityReason int) for backwards
  // compatibility with listeners that don't implement
  // onPositionDiscontinuity(PositionInfo, PositionInfo, @DiscontinuityReason int).
  @SuppressWarnings("deprecation")
  @Override
  protected void seekTo(
      int mediaItemIndex,
      long positionMs,
      @Player.Command int seekCommand,
      boolean isRepeatingCurrentItem) {
    if (mediaItemIndex == C.INDEX_UNSET) {
      return;
    }
    checkArgument(mediaItemIndex >= 0);
    if (!currentTimeline.isEmpty() && mediaItemIndex >= currentTimeline.getWindowCount()) {
      return;
    }
    MediaStatus mediaStatus = getMediaStatus();
    // We assume the default position is 0. There is no support for seeking to the default position
    // in RemoteMediaClient.
    positionMs = positionMs != C.TIME_UNSET ? positionMs : 0;
    if (mediaStatus != null) {
      if (getCurrentMediaItemIndex() != mediaItemIndex) {
        remoteMediaClient
            .queueJumpToItem(
                (int) currentTimeline.getPeriod(mediaItemIndex, period).uid, positionMs, null)
            .setResultCallback(seekResultCallback);
      } else {
        remoteMediaClient.seek(positionMs).setResultCallback(seekResultCallback);
      }
      PositionInfo oldPosition = getCurrentPositionInfo();
      pendingSeekCount++;
      pendingSeekWindowIndex = mediaItemIndex;
      pendingSeekPositionMs = positionMs;
      PositionInfo newPosition = getCurrentPositionInfo();
      listeners.queueEvent(
          Player.EVENT_POSITION_DISCONTINUITY,
          listener -> {
            listener.onPositionDiscontinuity(DISCONTINUITY_REASON_SEEK);
            listener.onPositionDiscontinuity(oldPosition, newPosition, DISCONTINUITY_REASON_SEEK);
          });
      if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
        // TODO(internal b/182261884): queue `onMediaItemTransition` event when the media item is
        // repeated.
        MediaItem mediaItem = getCurrentTimeline().getWindow(mediaItemIndex, window).mediaItem;
        listeners.queueEvent(
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            listener ->
                listener.onMediaItemTransition(mediaItem, MEDIA_ITEM_TRANSITION_REASON_SEEK));
        MediaMetadata oldMediaMetadata = mediaMetadata;
        mediaMetadata = getMediaMetadataInternal();
        if (!oldMediaMetadata.equals(mediaMetadata)) {
          listeners.queueEvent(
              Player.EVENT_MEDIA_METADATA_CHANGED,
              listener -> listener.onMediaMetadataChanged(mediaMetadata));
        }
      }
      updateAvailableCommandsAndNotifyIfChanged();
    }
    listeners.flushEvents();
  }

  @Override
  public long getSeekBackIncrement() {
    return seekBackIncrementMs;
  }

  @Override
  public long getSeekForwardIncrement() {
    return seekForwardIncrementMs;
  }

  @Override
  public long getMaxSeekToPreviousPosition() {
    return maxSeekToPreviousPositionMs;
  }

  @Override
  public PlaybackParameters getPlaybackParameters() {
    return playbackParameters.value;
  }

  @Override
  public void stop() {
    playbackState = STATE_IDLE;
    if (remoteMediaClient != null) {
      // TODO(b/69792021): Support or emulate stop without position reset.
      remoteMediaClient.stop();
    }
  }

  @Override
  public void release() {
    // The SDK_INT check is not necessary, but it prevents a lint error for the release call.
    if (SDK_INT >= 30 && api30Impl != null) {
      api30Impl.release();
    }
    SessionManager sessionManager = castContext.getSessionManager();
    sessionManager.removeSessionManagerListener(statusListener, CastSession.class);
    sessionManager.endCurrentSession(false);
  }

  @Override
  public void setPlaybackParameters(PlaybackParameters playbackParameters) {
    if (remoteMediaClient == null) {
      return;
    }
    PlaybackParameters actualPlaybackParameters =
        new PlaybackParameters(
            Util.constrainValue(
                playbackParameters.speed, MIN_SPEED_SUPPORTED, MAX_SPEED_SUPPORTED));
    setPlaybackParametersAndNotifyIfChanged(actualPlaybackParameters);
    listeners.flushEvents();
    PendingResult<MediaChannelResult> pendingResult =
        remoteMediaClient.setPlaybackRate(actualPlaybackParameters.speed, /* customData= */ null);
    this.playbackParameters.pendingResultCallback =
        new ResultCallback<MediaChannelResult>() {
          @Override
          public void onResult(MediaChannelResult mediaChannelResult) {
            if (remoteMediaClient != null) {
              updatePlaybackRateAndNotifyIfChanged(this);
              listeners.flushEvents();
            }
          }
        };
    pendingResult.setResultCallback(this.playbackParameters.pendingResultCallback);
  }

  @Override
  public void setRepeatMode(@RepeatMode int repeatMode) {
    if (remoteMediaClient == null) {
      return;
    }
    // We update the local state and send the message to the receiver app, which will cause the
    // operation to be perceived as synchronous by the user. When the operation reports a result,
    // the local state will be updated to reflect the state reported by the Cast SDK.
    setRepeatModeAndNotifyIfChanged(repeatMode);
    listeners.flushEvents();
    PendingResult<MediaChannelResult> pendingResult =
        remoteMediaClient.queueSetRepeatMode(getCastRepeatMode(repeatMode), /* customData= */ null);
    this.repeatMode.pendingResultCallback =
        new ResultCallback<MediaChannelResult>() {
          @Override
          public void onResult(MediaChannelResult mediaChannelResult) {
            if (remoteMediaClient != null) {
              updateRepeatModeAndNotifyIfChanged(this);
              listeners.flushEvents();
            }
          }
        };
    pendingResult.setResultCallback(this.repeatMode.pendingResultCallback);
  }

  @Override
  public @RepeatMode int getRepeatMode() {
    return repeatMode.value;
  }

  @Override
  public void setShuffleModeEnabled(boolean shuffleModeEnabled) {
    // TODO: Support shuffle mode.
  }

  @Override
  public boolean getShuffleModeEnabled() {
    // TODO: Support shuffle mode.
    return false;
  }

  @Override
  public Tracks getCurrentTracks() {
    return currentTracks;
  }

  @Override
  public TrackSelectionParameters getTrackSelectionParameters() {
    return TrackSelectionParameters.DEFAULT;
  }

  @Override
  public void setTrackSelectionParameters(TrackSelectionParameters parameters) {}

  @Override
  public MediaMetadata getMediaMetadata() {
    return mediaMetadata;
  }

  @Override
  public MediaMetadata getPlaylistMetadata() {
    return playlistMetadata;
  }

  @Override
  public void setPlaylistMetadata(MediaMetadata playlistMetadata) {
    checkNotNull(playlistMetadata);
    if (playlistMetadata.equals(this.playlistMetadata)) {
      return;
    }
    this.playlistMetadata = playlistMetadata;
    listeners.sendEvent(
        EVENT_PLAYLIST_METADATA_CHANGED,
        listener -> listener.onPlaylistMetadataChanged(this.playlistMetadata));
  }

  @Override
  public Timeline getCurrentTimeline() {
    return currentTimeline;
  }

  @Override
  public int getCurrentPeriodIndex() {
    return getCurrentMediaItemIndex();
  }

  @Override
  public int getCurrentMediaItemIndex() {
    return pendingSeekWindowIndex != C.INDEX_UNSET ? pendingSeekWindowIndex : currentWindowIndex;
  }

  // TODO: Fill the cast timeline information with ProgressListener's duration updates.
  // See [Internal: b/65152553].
  @Override
  public long getDuration() {
    return getContentDuration();
  }

  @Override
  public long getCurrentPosition() {
    return pendingSeekPositionMs != C.TIME_UNSET
        ? pendingSeekPositionMs
        : remoteMediaClient != null
            ? remoteMediaClient.getApproximateStreamPosition()
            : lastReportedPositionMs;
  }

  @Override
  public long getBufferedPosition() {
    return getCurrentPosition();
  }

  @Override
  public long getTotalBufferedDuration() {
    long bufferedPosition = getBufferedPosition();
    long currentPosition = getCurrentPosition();
    return bufferedPosition == C.TIME_UNSET || currentPosition == C.TIME_UNSET
        ? 0
        : bufferedPosition - currentPosition;
  }

  @Override
  public boolean isPlayingAd() {
    return false;
  }

  @Override
  public int getCurrentAdGroupIndex() {
    return C.INDEX_UNSET;
  }

  @Override
  public int getCurrentAdIndexInAdGroup() {
    return C.INDEX_UNSET;
  }

  @Override
  public boolean isLoading() {
    return false;
  }

  @Override
  public long getContentPosition() {
    return getCurrentPosition();
  }

  @Override
  public long getContentBufferedPosition() {
    return getBufferedPosition();
  }

  /** This method is not supported and returns {@link AudioAttributes#DEFAULT}. */
  @Override
  public AudioAttributes getAudioAttributes() {
    return AudioAttributes.DEFAULT;
  }

  @Override
  public void setVolume(float volume) {
    if (remoteMediaClient == null) {
      return;
    }
    // We update the local state and send the message to the receiver app, which will cause the
    // operation to be perceived as synchronous by the user. When the operation reports a result,
    // the local state will be updated to reflect the state reported by the Cast SDK.
    volume = RANGE_VOLUME.clamp(volume);
    setVolumeAndNotifyIfChanged(volume);
    listeners.flushEvents();
    PendingResult<MediaChannelResult> pendingResult = remoteMediaClient.setStreamVolume(volume);
    this.volume.pendingResultCallback =
        new ResultCallback<MediaChannelResult>() {
          @Override
          public void onResult(MediaChannelResult result) {
            if (remoteMediaClient != null) {
              updateVolumeAndNotifyIfChanged(this);
              listeners.flushEvents();
            }
          }
        };
    pendingResult.setResultCallback(this.volume.pendingResultCallback);
  }

  @Override
  public float getVolume() {
    return volume.value;
  }

  /** This method is not supported and does nothing. */
  @Override
  public void mute() {}

  /** This method is not supported and does nothing. */
  @Override
  public void unmute() {}

  /** This method is not supported and does nothing. */
  @Override
  public void clearVideoSurface() {}

  /** This method is not supported and does nothing. */
  @Override
  public void clearVideoSurface(@Nullable Surface surface) {}

  /** This method is not supported and does nothing. */
  @Override
  public void setVideoSurface(@Nullable Surface surface) {}

  /** This method is not supported and does nothing. */
  @Override
  public void setVideoSurfaceHolder(@Nullable SurfaceHolder surfaceHolder) {}

  /** This method is not supported and does nothing. */
  @Override
  public void clearVideoSurfaceHolder(@Nullable SurfaceHolder surfaceHolder) {}

  /** This method is not supported and does nothing. */
  @Override
  public void setVideoSurfaceView(@Nullable SurfaceView surfaceView) {}

  /** This method is not supported and does nothing. */
  @Override
  public void clearVideoSurfaceView(@Nullable SurfaceView surfaceView) {}

  /** This method is not supported and does nothing. */
  @Override
  public void setVideoTextureView(@Nullable TextureView textureView) {}

  /** This method is not supported and does nothing. */
  @Override
  public void clearVideoTextureView(@Nullable TextureView textureView) {}

  /** This method is not supported and returns {@link VideoSize#UNKNOWN}. */
  @Override
  public VideoSize getVideoSize() {
    return VideoSize.UNKNOWN;
  }

  /** This method is not supported and returns {@link Size#UNKNOWN}. */
  @Override
  public Size getSurfaceSize() {
    return Size.UNKNOWN;
  }

  /** This method is not supported and returns an empty {@link CueGroup}. */
  @Override
  public CueGroup getCurrentCues() {
    return CueGroup.EMPTY_TIME_ZERO;
  }

  /**
   * Returns a {@link DeviceInfo} describing the receiver device. Returns {@link
   * #DEVICE_INFO_REMOTE_EMPTY} if no {@link Context} was provided at construction, or if the Cast
   * {@link RoutingController} could not be identified.
   */
  @Override
  public DeviceInfo getDeviceInfo() {
    return deviceInfo;
  }

  @Override
  public int getDeviceVolume() {
    return deviceVolume;
  }

  @Override
  public boolean isDeviceMuted() {
    return isMuted;
  }

  /**
   * @deprecated Use {@link #setDeviceVolume(int, int)} instead.
   */
  @InlineMe(replacement = "this.setDeviceVolume(volume, 0)")
  @Deprecated
  @Override
  public void setDeviceVolume(@IntRange(from = 0) int volume) {
    setDeviceVolume(volume, /* flags= */ 0);
  }

  @Override
  public void setDeviceVolume(@IntRange(from = 0) int volume, @C.VolumeFlags int flags) {
    if (castSession == null) {
      return;
    }
    volume = RANGE_DEVICE_VOLUME.clamp(volume);
    try {
      // See [Internal ref: b/399691860] for context on why we don't use
      // RemoteMediaClient.setStreamVolume.
      castSession.setVolume((float) volume / MAX_VOLUME);
    } catch (IOException e) {
      Log.w(TAG, "Ignoring setDeviceVolume due to exception", e);
      return;
    }
    setDeviceVolumeAndNotifyIfChanged(volume, isMuted);
    listeners.flushEvents();
  }

  /**
   * @deprecated Use {@link #increaseDeviceVolume(int)} instead.
   */
  @InlineMe(replacement = "this.increaseDeviceVolume(0)")
  @Deprecated
  @Override
  public void increaseDeviceVolume() {
    increaseDeviceVolume(/* flags= */ 0);
  }

  @Override
  public void increaseDeviceVolume(@C.VolumeFlags int flags) {
    setDeviceVolume(getDeviceVolume() + 1, flags);
  }

  /**
   * @deprecated Use {@link #decreaseDeviceVolume(int)} (int)} instead.
   */
  @InlineMe(replacement = "this.decreaseDeviceVolume(0)")
  @Deprecated
  @Override
  public void decreaseDeviceVolume() {
    decreaseDeviceVolume(/* flags= */ 0);
  }

  @Override
  public void decreaseDeviceVolume(@C.VolumeFlags int flags) {
    setDeviceVolume(getDeviceVolume() - 1, flags);
  }

  /**
   * @deprecated Use {@link #setDeviceMuted(boolean, int)} instead.
   */
  @InlineMe(replacement = "this.setDeviceMuted(muted, 0)")
  @Deprecated
  @Override
  public void setDeviceMuted(boolean muted) {
    setDeviceMuted(muted, /* flags= */ 0);
  }

  @Override
  public void setDeviceMuted(boolean muted, @C.VolumeFlags int flags) {
    if (castSession == null) {
      return;
    }
    try {
      castSession.setMute(muted);
    } catch (IOException e) {
      Log.w(TAG, "Ignoring setDeviceMuted due to exception", e);
      return;
    }
    setDeviceVolumeAndNotifyIfChanged(deviceVolume, muted);
    listeners.flushEvents();
  }

  /** This method is not supported and does nothing. */
  @Override
  public void setAudioAttributes(AudioAttributes audioAttributes, boolean handleAudioFocus) {}

  // Internal methods.

  // Call deprecated callbacks.
  @SuppressWarnings("deprecation")
  private void updateInternalStateAndNotifyIfChanged() {
    if (remoteMediaClient == null) {
      // There is no session. We leave the state of the player as it is now.
      return;
    }
    int oldWindowIndex = this.currentWindowIndex;
    MediaMetadata oldMediaMetadata = mediaMetadata;
    @Nullable
    Object oldPeriodUid =
        !getCurrentTimeline().isEmpty()
            ? getCurrentTimeline().getPeriod(oldWindowIndex, period, /* setIds= */ true).uid
            : null;
    updatePlayerStateAndNotifyIfChanged(/* resultCallback= */ null);
    updateDeviceVolumeAndNotifyIfChanged();
    updateRepeatModeAndNotifyIfChanged(/* resultCallback= */ null);
    updateVolumeAndNotifyIfChanged(/* resultCallback= */ null);
    updatePlaybackRateAndNotifyIfChanged(/* resultCallback= */ null);
    boolean playingPeriodChangedByTimelineChange = updateTimelineAndNotifyIfChanged();
    Timeline currentTimeline = getCurrentTimeline();
    currentWindowIndex = fetchCurrentWindowIndex(remoteMediaClient, currentTimeline);
    mediaMetadata = getMediaMetadataInternal();
    @Nullable
    Object currentPeriodUid =
        !currentTimeline.isEmpty()
            ? currentTimeline.getPeriod(currentWindowIndex, period, /* setIds= */ true).uid
            : null;
    if (!playingPeriodChangedByTimelineChange
        && !Objects.equals(oldPeriodUid, currentPeriodUid)
        && pendingSeekCount == 0) {
      // Report discontinuity and media item auto transition.
      currentTimeline.getPeriod(oldWindowIndex, period, /* setIds= */ true);
      currentTimeline.getWindow(oldWindowIndex, window);
      long windowDurationMs = window.getDurationMs();
      PositionInfo oldPosition =
          new PositionInfo(
              window.uid,
              period.windowIndex,
              window.mediaItem,
              period.uid,
              period.windowIndex,
              /* positionMs= */ windowDurationMs,
              /* contentPositionMs= */ windowDurationMs,
              /* adGroupIndex= */ C.INDEX_UNSET,
              /* adIndexInAdGroup= */ C.INDEX_UNSET);
      currentTimeline.getPeriod(currentWindowIndex, period, /* setIds= */ true);
      currentTimeline.getWindow(currentWindowIndex, window);
      PositionInfo newPosition =
          new PositionInfo(
              window.uid,
              period.windowIndex,
              window.mediaItem,
              period.uid,
              period.windowIndex,
              /* positionMs= */ window.getDefaultPositionMs(),
              /* contentPositionMs= */ window.getDefaultPositionMs(),
              /* adGroupIndex= */ C.INDEX_UNSET,
              /* adIndexInAdGroup= */ C.INDEX_UNSET);
      listeners.queueEvent(
          Player.EVENT_POSITION_DISCONTINUITY,
          listener -> {
            listener.onPositionDiscontinuity(DISCONTINUITY_REASON_AUTO_TRANSITION);
            listener.onPositionDiscontinuity(
                oldPosition, newPosition, DISCONTINUITY_REASON_AUTO_TRANSITION);
          });
      listeners.queueEvent(
          Player.EVENT_MEDIA_ITEM_TRANSITION,
          listener ->
              listener.onMediaItemTransition(
                  getCurrentMediaItem(), MEDIA_ITEM_TRANSITION_REASON_AUTO));
    }
    if (updateTracksAndSelectionsAndNotifyIfChanged()) {
      listeners.queueEvent(
          Player.EVENT_TRACKS_CHANGED, listener -> listener.onTracksChanged(currentTracks));
    }
    if (!oldMediaMetadata.equals(mediaMetadata)) {
      listeners.queueEvent(
          Player.EVENT_MEDIA_METADATA_CHANGED,
          listener -> listener.onMediaMetadataChanged(mediaMetadata));
    }
    updateAvailableCommandsAndNotifyIfChanged();
    listeners.flushEvents();
  }

  private MediaMetadata getMediaMetadataInternal() {
    MediaItem currentMediaItem = getCurrentMediaItem();
    return currentMediaItem != null ? currentMediaItem.mediaMetadata : MediaMetadata.EMPTY;
  }

  /**
   * Updates {@link #playWhenReady} and {@link #playbackState} to match the Cast {@code
   * remoteMediaClient} state, and notifies listeners of any state changes.
   *
   * <p>This method will only update values whose {@link StateHolder#pendingResultCallback} matches
   * the given {@code resultCallback}.
   */
  @RequiresNonNull("remoteMediaClient")
  private void updatePlayerStateAndNotifyIfChanged(@Nullable ResultCallback<?> resultCallback) {
    boolean newPlayWhenReadyValue = playWhenReady.value;
    if (playWhenReady.acceptsUpdate(resultCallback)) {
      newPlayWhenReadyValue = !remoteMediaClient.isPaused();
      playWhenReady.clearPendingResultCallback();
    }
    @PlayWhenReadyChangeReason
    int playWhenReadyChangeReason =
        newPlayWhenReadyValue != playWhenReady.value
            ? PLAY_WHEN_READY_CHANGE_REASON_REMOTE
            : PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST;
    // We do not mask the playback state, so try setting it regardless of the playWhenReady masking.
    setPlayerStateAndNotifyIfChanged(
        newPlayWhenReadyValue, playWhenReadyChangeReason, fetchPlaybackState(remoteMediaClient));
  }

  @RequiresNonNull("remoteMediaClient")
  private void updatePlaybackRateAndNotifyIfChanged(@Nullable ResultCallback<?> resultCallback) {
    if (playbackParameters.acceptsUpdate(resultCallback)) {
      @Nullable MediaStatus mediaStatus = remoteMediaClient.getMediaStatus();
      float speed =
          mediaStatus != null
              ? (float) mediaStatus.getPlaybackRate()
              : PlaybackParameters.DEFAULT.speed;
      if (speed > 0.0f) {
        // Set the speed if not paused.
        setPlaybackParametersAndNotifyIfChanged(new PlaybackParameters(speed));
      }
      playbackParameters.clearPendingResultCallback();
    }
  }

  @RequiresNonNull("castSession")
  private void updateDeviceVolumeAndNotifyIfChanged() {
    if (castSession != null) {
      int deviceVolume =
          RANGE_DEVICE_VOLUME.clamp((int) Math.round(castSession.getVolume() * MAX_VOLUME));
      setDeviceVolumeAndNotifyIfChanged(deviceVolume, castSession.isMute());
    }
  }

  @RequiresNonNull("remoteMediaClient")
  private void updateVolumeAndNotifyIfChanged(@Nullable ResultCallback<?> resultCallback) {
    if (volume.acceptsUpdate(resultCallback)) {
      float remoteVolume = RANGE_VOLUME.clamp(fetchVolume(remoteMediaClient));
      setVolumeAndNotifyIfChanged(remoteVolume);
      volume.clearPendingResultCallback();
    }
  }

  @RequiresNonNull("remoteMediaClient")
  private void updateRepeatModeAndNotifyIfChanged(@Nullable ResultCallback<?> resultCallback) {
    if (repeatMode.acceptsUpdate(resultCallback)) {
      setRepeatModeAndNotifyIfChanged(fetchRepeatMode(remoteMediaClient));
      repeatMode.clearPendingResultCallback();
    }
  }

  /**
   * Updates the timeline and notifies {@link Player.Listener event listeners} if required.
   *
   * @return Whether the timeline change has caused a change of the period currently being played.
   */
  @SuppressWarnings("deprecation") // Calling deprecated listener method.
  private boolean updateTimelineAndNotifyIfChanged() {
    Timeline oldTimeline = currentTimeline;
    int oldWindowIndex = currentWindowIndex;
    boolean playingPeriodChanged = false;
    if (updateTimeline()) {
      // TODO: Differentiate TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED and
      //     TIMELINE_CHANGE_REASON_SOURCE_UPDATE [see internal: b/65152553].
      Timeline timeline = currentTimeline;
      // Call onTimelineChanged.
      listeners.queueEvent(
          Player.EVENT_TIMELINE_CHANGED,
          listener ->
              listener.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE));

      // Call onPositionDiscontinuity if required.
      Timeline currentTimeline = getCurrentTimeline();
      boolean playingPeriodRemoved = false;
      if (!oldTimeline.isEmpty()) {
        Object oldPeriodUid =
            castNonNull(oldTimeline.getPeriod(oldWindowIndex, period, /* setIds= */ true).uid);
        playingPeriodRemoved = currentTimeline.getIndexOfPeriod(oldPeriodUid) == C.INDEX_UNSET;
      }
      if (playingPeriodRemoved) {
        PositionInfo oldPosition;
        if (pendingMediaItemRemovalPosition != null) {
          oldPosition = pendingMediaItemRemovalPosition;
          pendingMediaItemRemovalPosition = null;
        } else {
          // If the media item has been removed by another client, we don't know the removal
          // position. We use the current position as a fallback.
          oldTimeline.getPeriod(oldWindowIndex, period, /* setIds= */ true);
          oldTimeline.getWindow(period.windowIndex, window);
          oldPosition =
              new PositionInfo(
                  window.uid,
                  period.windowIndex,
                  window.mediaItem,
                  period.uid,
                  period.windowIndex,
                  getCurrentPosition(),
                  getContentPosition(),
                  /* adGroupIndex= */ C.INDEX_UNSET,
                  /* adIndexInAdGroup= */ C.INDEX_UNSET);
        }
        PositionInfo newPosition = getCurrentPositionInfo();
        listeners.queueEvent(
            Player.EVENT_POSITION_DISCONTINUITY,
            listener -> {
              listener.onPositionDiscontinuity(DISCONTINUITY_REASON_REMOVE);
              listener.onPositionDiscontinuity(
                  oldPosition, newPosition, DISCONTINUITY_REASON_REMOVE);
            });
      }

      // Call onMediaItemTransition if required.
      playingPeriodChanged =
          currentTimeline.isEmpty() != oldTimeline.isEmpty() || playingPeriodRemoved;
      if (playingPeriodChanged) {
        listeners.queueEvent(
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            listener ->
                listener.onMediaItemTransition(
                    getCurrentMediaItem(), MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED));
      }
      updateAvailableCommandsAndNotifyIfChanged();
    }
    return playingPeriodChanged;
  }

  /**
   * Updates the current timeline. The current window index may change as a result.
   *
   * @return Whether the current timeline has changed.
   */
  private boolean updateTimeline() {
    CastTimeline oldTimeline = currentTimeline;
    MediaStatus status = getMediaStatus();
    currentTimeline =
        status != null
            ? timelineTracker.getCastTimeline(remoteMediaClient)
            : CastTimeline.EMPTY_CAST_TIMELINE;
    boolean timelineChanged = !oldTimeline.equals(currentTimeline);
    if (timelineChanged) {
      currentWindowIndex = fetchCurrentWindowIndex(remoteMediaClient, currentTimeline);
    }
    return timelineChanged;
  }

  /** Updates the internal tracks and selection and returns whether they have changed. */
  private boolean updateTracksAndSelectionsAndNotifyIfChanged() {
    if (remoteMediaClient == null) {
      // There is no session. We leave the state of the player as it is now.
      return false;
    }

    @Nullable MediaStatus mediaStatus = getMediaStatus();
    @Nullable MediaInfo mediaInfo = mediaStatus != null ? mediaStatus.getMediaInfo() : null;
    @Nullable
    List<MediaTrack> castMediaTracks = mediaInfo != null ? mediaInfo.getMediaTracks() : null;
    if (castMediaTracks == null || castMediaTracks.isEmpty()) {
      boolean hasChanged = !Tracks.EMPTY.equals(currentTracks);
      currentTracks = Tracks.EMPTY;
      return hasChanged;
    }
    @Nullable long[] activeTrackIds = mediaStatus.getActiveTrackIds();
    if (activeTrackIds == null) {
      activeTrackIds = EMPTY_TRACK_ID_ARRAY;
    }

    Tracks.Group[] trackGroups = new Tracks.Group[castMediaTracks.size()];
    for (int i = 0; i < castMediaTracks.size(); i++) {
      MediaTrack mediaTrack = castMediaTracks.get(i);
      TrackGroup trackGroup =
          CastUtils.mediaTrackToTrackGroup(/* trackGroupId= */ String.valueOf(i), mediaTrack);
      @C.FormatSupport int[] trackSupport = new int[] {C.FORMAT_HANDLED};
      boolean[] trackSelected = new boolean[] {isTrackActive(mediaTrack.getId(), activeTrackIds)};
      trackGroups[i] =
          new Tracks.Group(trackGroup, /* adaptiveSupported= */ false, trackSupport, trackSelected);
    }
    Tracks newTracks = new Tracks(ImmutableList.copyOf(trackGroups));
    if (!newTracks.equals(currentTracks)) {
      currentTracks = newTracks;
      return true;
    }
    return false;
  }

  private void updateAvailableCommandsAndNotifyIfChanged() {
    Commands previousAvailableCommands = availableCommands;
    availableCommands =
        Util.getAvailableCommands(/* player= */ this, PERMANENT_AVAILABLE_COMMANDS)
            .buildUpon()
            .addIf(COMMAND_GET_VOLUME, isSetVolumeCommandAvailable())
            .addIf(COMMAND_SET_VOLUME, isSetVolumeCommandAvailable())
            .build();
    if (!availableCommands.equals(previousAvailableCommands)) {
      listeners.queueEvent(
          Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
          listener -> listener.onAvailableCommandsChanged(availableCommands));
    }
  }

  private boolean isSetVolumeCommandAvailable() {
    if (remoteMediaClient != null) {
      MediaStatus mediaStatus = remoteMediaClient.getMediaStatus();
      if (mediaStatus != null) {
        return mediaStatus.isMediaCommandSupported(MediaStatus.COMMAND_SET_VOLUME);
      }
    }
    return false;
  }

  private void setMediaItemsInternal(
      List<MediaItem> mediaItems,
      int startIndex,
      long startPositionMs,
      @RepeatMode int repeatMode) {
    if (remoteMediaClient == null || mediaItems.isEmpty()) {
      return;
    }
    startPositionMs = startPositionMs == C.TIME_UNSET ? 0 : startPositionMs;
    if (startIndex == C.INDEX_UNSET) {
      startIndex = getCurrentMediaItemIndex();
      startPositionMs = getCurrentPosition();
    }
    Timeline currentTimeline = getCurrentTimeline();
    if (!currentTimeline.isEmpty()) {
      pendingMediaItemRemovalPosition = getCurrentPositionInfo();
    }
    MediaQueueItem[] mediaQueueItems = toMediaQueueItems(mediaItems);
    timelineTracker.onMediaItemsSet(mediaItems, mediaQueueItems);
    MediaQueueData mediaQueueData =
        new MediaQueueData.Builder()
            .setItems(Arrays.asList(mediaQueueItems))
            .setStartIndex(min(startIndex, mediaItems.size() - 1))
            .setRepeatMode(getCastRepeatMode(repeatMode))
            .setStartTime(startPositionMs)
            .build();
    // TODO: b/432716880 - Populate playback speed and repeat mode values.
    // TODO: b/434761431 - Remove setCurrentTime call once setStartTime (above) is handled correctly
    // by the Cast framework.
    MediaLoadRequestData loadRequestData =
        new MediaLoadRequestData.Builder()
            .setAutoplay(getPlayWhenReady())
            .setQueueData(mediaQueueData)
            .setCurrentTime(startPositionMs)
            .build();
    // We don't use the pending result because the timeline tracker is taking care of the masking.
    PendingResult<MediaChannelResult> unused = remoteMediaClient.load(loadRequestData);
  }

  private void addMediaItemsInternal(List<MediaItem> mediaItems, int uid) {
    if (remoteMediaClient == null || getMediaStatus() == null) {
      return;
    }
    MediaQueueItem[] itemsToInsert = toMediaQueueItems(mediaItems);
    timelineTracker.onMediaItemsAdded(mediaItems, itemsToInsert);
    remoteMediaClient.queueInsertItems(itemsToInsert, uid, /* customData= */ null);
  }

  private void moveMediaItemsInternal(int[] uids, int fromIndex, int newIndex) {
    if (remoteMediaClient == null || getMediaStatus() == null) {
      return;
    }
    int insertBeforeIndex = fromIndex < newIndex ? newIndex + uids.length : newIndex;
    int insertBeforeItemId = MediaQueueItem.INVALID_ITEM_ID;
    if (insertBeforeIndex < currentTimeline.getWindowCount()) {
      insertBeforeItemId = (int) currentTimeline.getWindow(insertBeforeIndex, window).uid;
    }
    remoteMediaClient.queueReorderItems(uids, insertBeforeItemId, /* customData= */ null);
  }

  @Nullable
  private PendingResult<MediaChannelResult> removeMediaItemsInternal(int[] uids) {
    if (remoteMediaClient == null || getMediaStatus() == null) {
      return null;
    }
    Timeline timeline = getCurrentTimeline();
    if (!timeline.isEmpty()) {
      Object periodUid =
          castNonNull(timeline.getPeriod(getCurrentPeriodIndex(), period, /* setIds= */ true).uid);
      for (int uid : uids) {
        if (periodUid.equals(uid)) {
          pendingMediaItemRemovalPosition = getCurrentPositionInfo();
          break;
        }
      }
    }
    return remoteMediaClient.queueRemoveItems(uids, /* customData= */ null);
  }

  private PositionInfo getCurrentPositionInfo() {
    Timeline currentTimeline = getCurrentTimeline();
    @Nullable Object newPeriodUid = null;
    @Nullable Object newWindowUid = null;
    @Nullable MediaItem newMediaItem = null;
    if (!currentTimeline.isEmpty()) {
      newPeriodUid =
          currentTimeline.getPeriod(getCurrentPeriodIndex(), period, /* setIds= */ true).uid;
      newWindowUid = currentTimeline.getWindow(period.windowIndex, window).uid;
      newMediaItem = window.mediaItem;
    }
    return new PositionInfo(
        newWindowUid,
        getCurrentMediaItemIndex(),
        newMediaItem,
        newPeriodUid,
        getCurrentPeriodIndex(),
        getCurrentPosition(),
        getContentPosition(),
        /* adGroupIndex= */ C.INDEX_UNSET,
        /* adIndexInAdGroup= */ C.INDEX_UNSET);
  }

  private void setDeviceVolumeAndNotifyIfChanged(
      @IntRange(from = 0) int deviceVolume, boolean isMuted) {
    if (this.deviceVolume != deviceVolume || this.isMuted != isMuted) {
      this.deviceVolume = deviceVolume;
      this.isMuted = isMuted;
      listeners.queueEvent(
          Player.EVENT_DEVICE_VOLUME_CHANGED,
          listener -> listener.onDeviceVolumeChanged(deviceVolume, isMuted));
    }
  }

  private void setRepeatModeAndNotifyIfChanged(@Player.RepeatMode int repeatMode) {
    if (this.repeatMode.value != repeatMode) {
      this.repeatMode.value = repeatMode;
      listeners.queueEvent(
          Player.EVENT_REPEAT_MODE_CHANGED, listener -> listener.onRepeatModeChanged(repeatMode));
      updateAvailableCommandsAndNotifyIfChanged();
    }
  }

  private void setVolumeAndNotifyIfChanged(float volume) {
    if (this.volume.value != volume) {
      this.volume.value = volume;
      listeners.queueEvent(
          Player.EVENT_VOLUME_CHANGED, listener -> listener.onVolumeChanged(volume));
      updateAvailableCommandsAndNotifyIfChanged();
    }
  }

  private void setPlaybackParametersAndNotifyIfChanged(PlaybackParameters playbackParameters) {
    if (this.playbackParameters.value.equals(playbackParameters)) {
      return;
    }
    this.playbackParameters.value = playbackParameters;
    listeners.queueEvent(
        Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
        listener -> listener.onPlaybackParametersChanged(playbackParameters));
    updateAvailableCommandsAndNotifyIfChanged();
  }

  @SuppressWarnings("deprecation")
  private void setPlayerStateAndNotifyIfChanged(
      boolean playWhenReady,
      @Player.PlayWhenReadyChangeReason int playWhenReadyChangeReason,
      @Player.State int playbackState) {
    boolean wasPlaying = this.playbackState == Player.STATE_READY && this.playWhenReady.value;
    boolean playWhenReadyChanged = this.playWhenReady.value != playWhenReady;
    boolean playbackStateChanged = this.playbackState != playbackState;
    if (playWhenReadyChanged || playbackStateChanged) {
      this.playbackState = playbackState;
      this.playWhenReady.value = playWhenReady;
      listeners.queueEvent(
          /* eventFlag= */ C.INDEX_UNSET,
          listener -> listener.onPlayerStateChanged(playWhenReady, playbackState));
      if (playbackStateChanged) {
        listeners.queueEvent(
            Player.EVENT_PLAYBACK_STATE_CHANGED,
            listener -> listener.onPlaybackStateChanged(playbackState));
      }
      if (playWhenReadyChanged) {
        listeners.queueEvent(
            Player.EVENT_PLAY_WHEN_READY_CHANGED,
            listener -> listener.onPlayWhenReadyChanged(playWhenReady, playWhenReadyChangeReason));
      }
      boolean isPlaying = playbackState == Player.STATE_READY && playWhenReady;
      if (wasPlaying != isPlaying) {
        listeners.queueEvent(
            Player.EVENT_IS_PLAYING_CHANGED, listener -> listener.onIsPlayingChanged(isPlaying));
      }
    }
  }

  private void setCastSession(@Nullable CastSession castSession) {
    if (this.castSession != null) {
      this.castSession.removeCastListener(castListener);
    }
    if (castSession != null) {
      castSession.addCastListener(castListener);
    }
    this.castSession = castSession;
    RemoteMediaClient remoteMediaClient =
        castSession != null ? castSession.getRemoteMediaClient() : null;
    if (this.remoteMediaClient == remoteMediaClient) {
      // Do nothing.
      return;
    }
    if (this.remoteMediaClient != null) {
      this.remoteMediaClient.unregisterCallback(statusListener);
      this.remoteMediaClient.removeProgressListener(statusListener);
    }
    this.remoteMediaClient = remoteMediaClient;
    if (remoteMediaClient != null) {
      if (sessionAvailabilityListener != null) {
        sessionAvailabilityListener.onCastSessionAvailable();
      }
      if (internalSessionAvailabilityListener != null) {
        internalSessionAvailabilityListener.onCastSessionAvailable();
      }
      remoteMediaClient.registerCallback(statusListener);
      remoteMediaClient.addProgressListener(statusListener, PROGRESS_REPORT_PERIOD_MS);
      updateInternalStateAndNotifyIfChanged();
    } else {
      if (sessionAvailabilityListener != null) {
        sessionAvailabilityListener.onCastSessionUnavailable();
      }
      if (internalSessionAvailabilityListener != null) {
        internalSessionAvailabilityListener.onCastSessionUnavailable();
      }
    }
  }

  @Nullable
  private MediaStatus getMediaStatus() {
    return remoteMediaClient != null ? remoteMediaClient.getMediaStatus() : null;
  }

  /**
   * Retrieves the playback state from {@code remoteMediaClient} and maps it into a {@link Player}
   * state
   */
  private static int fetchPlaybackState(RemoteMediaClient remoteMediaClient) {
    int receiverAppStatus = remoteMediaClient.getPlayerState();
    switch (receiverAppStatus) {
      case MediaStatus.PLAYER_STATE_BUFFERING:
      case MediaStatus.PLAYER_STATE_LOADING:
        return STATE_BUFFERING;
      case MediaStatus.PLAYER_STATE_PLAYING:
      case MediaStatus.PLAYER_STATE_PAUSED:
        return STATE_READY;
      case MediaStatus.PLAYER_STATE_IDLE:
      case MediaStatus.PLAYER_STATE_UNKNOWN:
      default:
        return STATE_IDLE;
    }
  }

  /**
   * Retrieves the repeat mode from {@code remoteMediaClient} and maps it into a {@link
   * Player.RepeatMode}.
   */
  private static @RepeatMode int fetchRepeatMode(RemoteMediaClient remoteMediaClient) {
    MediaStatus mediaStatus = remoteMediaClient.getMediaStatus();
    if (mediaStatus == null) {
      // No media session active, yet.
      return REPEAT_MODE_OFF;
    }
    int castRepeatMode = mediaStatus.getQueueRepeatMode();
    switch (castRepeatMode) {
      case MediaStatus.REPEAT_MODE_REPEAT_SINGLE:
        return REPEAT_MODE_ONE;
      case MediaStatus.REPEAT_MODE_REPEAT_ALL:
      case MediaStatus.REPEAT_MODE_REPEAT_ALL_AND_SHUFFLE:
        return REPEAT_MODE_ALL;
      case MediaStatus.REPEAT_MODE_REPEAT_OFF:
        return REPEAT_MODE_OFF;
      default:
        throw new IllegalStateException();
    }
  }

  private static float fetchVolume(RemoteMediaClient remoteMediaClient) {
    MediaStatus mediaStatus = remoteMediaClient.getMediaStatus();
    if (mediaStatus == null) {
      return 1f;
    }
    return (float) mediaStatus.getStreamVolume();
  }

  private static int fetchCurrentWindowIndex(
      @Nullable RemoteMediaClient remoteMediaClient, Timeline timeline) {
    if (remoteMediaClient == null) {
      return 0;
    }

    int currentWindowIndex = C.INDEX_UNSET;
    @Nullable MediaQueueItem currentItem = remoteMediaClient.getCurrentItem();
    if (currentItem != null) {
      currentWindowIndex = timeline.getIndexOfPeriod(currentItem.getItemId());
    }
    if (currentWindowIndex == C.INDEX_UNSET) {
      // The timeline is empty. Fall back to index 0.
      currentWindowIndex = 0;
    }
    return currentWindowIndex;
  }

  private static boolean isTrackActive(long id, long[] activeTrackIds) {
    for (long activeTrackId : activeTrackIds) {
      if (activeTrackId == id) {
        return true;
      }
    }
    return false;
  }

  @SuppressWarnings("VisibleForTests")
  private static int getCastRepeatMode(@RepeatMode int repeatMode) {
    switch (repeatMode) {
      case REPEAT_MODE_ONE:
        return MediaStatus.REPEAT_MODE_REPEAT_SINGLE;
      case REPEAT_MODE_ALL:
        return MediaStatus.REPEAT_MODE_REPEAT_ALL;
      case REPEAT_MODE_OFF:
        return MediaStatus.REPEAT_MODE_REPEAT_OFF;
      default:
        throw new IllegalArgumentException();
    }
  }

  private MediaQueueItem[] toMediaQueueItems(List<MediaItem> mediaItems) {
    MediaQueueItem[] mediaQueueItems = new MediaQueueItem[mediaItems.size()];
    for (int i = 0; i < mediaItems.size(); i++) {
      mediaQueueItems[i] = mediaItemConverter.toMediaQueueItem(mediaItems.get(i));
    }
    return mediaQueueItems;
  }

  // Internal classes.

  private final class StatusListener extends RemoteMediaClient.Callback
      implements SessionManagerListener<CastSession>, RemoteMediaClient.ProgressListener {

    // RemoteMediaClient.ProgressListener implementation.

    @Override
    public void onProgressUpdated(long progressMs, long unusedDurationMs) {
      lastReportedPositionMs = progressMs;
    }

    // RemoteMediaClient.Callback implementation.

    @Override
    public void onStatusUpdated() {
      updateInternalStateAndNotifyIfChanged();
    }

    @Override
    public void onMetadataUpdated() {}

    @Override
    public void onQueueStatusUpdated() {
      updateTimelineAndNotifyIfChanged();
      listeners.flushEvents();
    }

    @Override
    public void onPreloadStatusUpdated() {}

    @Override
    public void onSendingRemoteMediaRequest() {}

    @Override
    public void onAdBreakStatusUpdated() {}

    // SessionManagerListener implementation.

    @Override
    public void onSessionStarted(CastSession castSession, String s) {
      setCastSession(castSession);
    }

    @Override
    public void onSessionResumed(CastSession castSession, boolean b) {
      setCastSession(castSession);
    }

    @Override
    public void onSessionEnded(CastSession castSession, int i) {
      setCastSession(null);
    }

    @Override
    public void onSessionSuspended(CastSession castSession, int i) {
      setCastSession(null);
    }

    @Override
    public void onSessionResumeFailed(CastSession castSession, int statusCode) {
      Log.e(
          TAG,
          "Session resume failed. Error code "
              + statusCode
              + ": "
              + CastUtils.getLogString(statusCode));
    }

    @Override
    public void onSessionStarting(CastSession castSession) {
      // Do nothing.
    }

    @Override
    public void onSessionStartFailed(CastSession castSession, int statusCode) {
      Log.e(
          TAG,
          "Session start failed. Error code "
              + statusCode
              + ": "
              + CastUtils.getLogString(statusCode));
    }

    @Override
    public void onSessionEnding(CastSession castSession) {
      // Do nothing.
    }

    @Override
    public void onSessionResuming(CastSession castSession, String s) {
      // Do nothing.
    }
  }

  private final class SeekResultCallback implements ResultCallback<MediaChannelResult> {

    @Override
    public void onResult(MediaChannelResult result) {
      int statusCode = result.getStatus().getStatusCode();
      if (statusCode != CastStatusCodes.SUCCESS && statusCode != CastStatusCodes.REPLACED) {
        Log.e(
            TAG,
            "Seek failed. Error code " + statusCode + ": " + CastUtils.getLogString(statusCode));
      }
      if (--pendingSeekCount == 0) {
        currentWindowIndex = pendingSeekWindowIndex;
        pendingSeekWindowIndex = C.INDEX_UNSET;
        pendingSeekPositionMs = C.TIME_UNSET;
      }
    }
  }

  /**
   * Holds the value and the masking status of a specific part of the {@link RemoteCastPlayer}
   * state.
   */
  private static final class StateHolder<T> {

    /** The user-facing value of a specific part of the {@link RemoteCastPlayer} state. */
    public T value;

    /**
     * If {@link #value} is being masked, holds the result callback for the operation that triggered
     * the masking. Or null if {@link #value} is not being masked.
     */
    @Nullable public ResultCallback<MediaChannelResult> pendingResultCallback;

    public StateHolder(T initialValue) {
      value = initialValue;
    }

    public void clearPendingResultCallback() {
      pendingResultCallback = null;
    }

    /**
     * Returns whether this state holder accepts updates coming from the given result callback.
     *
     * <p>A null {@code resultCallback} means that the update is a regular receiver state update, in
     * which case the update will only be accepted if {@link #value} is not being masked. If {@link
     * #value} is being masked, the update will only be accepted if {@code resultCallback} is the
     * same as the {@link #pendingResultCallback}.
     *
     * @param resultCallback A result callback. May be null if the update comes from a regular
     *     receiver status update.
     */
    public boolean acceptsUpdate(@Nullable ResultCallback<?> resultCallback) {
      return pendingResultCallback == resultCallback;
    }
  }

  @RequiresApi(30)
  private final class Api30Impl {

    private final MediaRouter2 mediaRouter2;
    private final TransferCallback transferCallback;
    private final RouteCallback emptyRouteCallback;
    private final Handler handler;

    public Api30Impl(Context context) {
      mediaRouter2 = MediaRouter2.getInstance(context);
      transferCallback = new MediaRouter2TransferCallbackImpl();
      emptyRouteCallback = new MediaRouter2RouteCallbackImpl();
      handler = new Handler(Looper.getMainLooper());
    }

    /** Acquires necessary resources and registers callbacks. */
    public void initialize() {
      mediaRouter2.registerTransferCallback(handler::post, transferCallback);
      // We need at least one route callback registered in order to get transfer callback updates.
      mediaRouter2.registerRouteCallback(
          handler::post,
          emptyRouteCallback,
          new RouteDiscoveryPreference.Builder(ImmutableList.of(), /* activeScan= */ false)
              .build());
    }

    /**
     * Releases any resources acquired in {@link #initialize()} and unregisters any registered
     * callbacks.
     */
    public void release() {
      mediaRouter2.unregisterTransferCallback(transferCallback);
      mediaRouter2.unregisterRouteCallback(emptyRouteCallback);
      handler.removeCallbacksAndMessages(/* token= */ null);
    }

    /** Updates the device info with an up-to-date value and notifies the listeners. */
    private void updateDeviceInfo() {
      DeviceInfo oldDeviceInfo = deviceInfo;
      DeviceInfo newDeviceInfo = fetchDeviceInfo();
      deviceInfo = newDeviceInfo;
      if (!deviceInfo.equals(oldDeviceInfo)) {
        listeners.sendEvent(
            EVENT_DEVICE_INFO_CHANGED, listener -> listener.onDeviceInfoChanged(newDeviceInfo));
      }
    }

    /**
     * Returns a {@link DeviceInfo} with the {@link RoutingController#getId() id} that corresponds
     * to the Cast session, or {@link #DEVICE_INFO_REMOTE_EMPTY} if not available.
     */
    public DeviceInfo fetchDeviceInfo() {
      // TODO: b/364833997 - Fetch this information from the AndroidX MediaRouter selected route
      // once the selected route id matches the controller id.
      List<RoutingController> controllers = mediaRouter2.getControllers();
      // The controller at position zero is always the system controller (local playback). All other
      // controllers are for remote playback, and could be the Cast one.
      if (controllers.size() != 2) {
        // There's either no remote routing controller, or there's more than one. In either case we
        // don't populate the device info because either there's no Cast routing controller, or we
        // cannot safely identify the Cast routing controller.
        return DEVICE_INFO_REMOTE_EMPTY;
      } else {
        // There's only one remote routing controller. It's safe to assume it's the Cast routing
        // controller.
        RoutingController remoteController = controllers.get(1);
        return new DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
            .setMaxVolume(MAX_VOLUME)
            .setRoutingControllerId(remoteController.getId())
            .build();
      }
    }

    /**
     * Empty {@link RouteCallback} implementation necessary for registering the {@link MediaRouter2}
     * instance with the system_server.
     *
     * <p>This callback must be registered so that the media router service notifies the {@link
     * MediaRouter2TransferCallbackImpl} of transfer events.
     */
    private final class MediaRouter2RouteCallbackImpl extends RouteCallback {}

    /**
     * {@link TransferCallback} implementation to listen for {@link RoutingController} creation and
     * releases.
     */
    private final class MediaRouter2TransferCallbackImpl extends TransferCallback {

      @Override
      public void onTransfer(RoutingController oldController, RoutingController newController) {
        updateDeviceInfo();
      }

      @Override
      public void onStop(RoutingController controller) {
        updateDeviceInfo();
      }
    }
  }

  private final class CastListener extends Cast.Listener {

    @Override
    public void onVolumeChanged() {
      updateDeviceVolumeAndNotifyIfChanged();
      listeners.flushEvents();
    }
  }
}
