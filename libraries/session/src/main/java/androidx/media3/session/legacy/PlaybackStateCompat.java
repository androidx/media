/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.session.legacy;

import static androidx.annotation.RestrictTo.Scope.LIBRARY;
import static androidx.media3.common.util.Assertions.checkNotNull;

import android.annotation.SuppressLint;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.text.TextUtils;
import androidx.annotation.IntDef;
import androidx.annotation.LongDef;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Playback state for a {@link MediaSessionCompat}. This includes a state like {@link
 * PlaybackStateCompat#STATE_PLAYING}, the current playback position, and the current control
 * capabilities.
 */
@UnstableApi
@RestrictTo(LIBRARY)
@SuppressLint("BanParcelableUsage")
public final class PlaybackStateCompat implements Parcelable {

  /** */
  @LongDef(
      flag = true,
      value = {
        ACTION_STOP,
        ACTION_PAUSE,
        ACTION_PLAY,
        ACTION_REWIND,
        ACTION_SKIP_TO_PREVIOUS,
        ACTION_SKIP_TO_NEXT,
        ACTION_FAST_FORWARD,
        ACTION_SET_RATING,
        ACTION_SEEK_TO,
        ACTION_PLAY_PAUSE,
        ACTION_PLAY_FROM_MEDIA_ID,
        ACTION_PLAY_FROM_SEARCH,
        ACTION_SKIP_TO_QUEUE_ITEM,
        ACTION_PLAY_FROM_URI,
        ACTION_PREPARE,
        ACTION_PREPARE_FROM_MEDIA_ID,
        ACTION_PREPARE_FROM_SEARCH,
        ACTION_PREPARE_FROM_URI,
        ACTION_SET_REPEAT_MODE,
        ACTION_SET_SHUFFLE_MODE,
        ACTION_SET_CAPTIONING_ENABLED,
        ACTION_SET_PLAYBACK_SPEED
      })
  @Retention(RetentionPolicy.SOURCE)
  public @interface Actions {}

  /** */
  @LongDef({
    ACTION_STOP,
    ACTION_PAUSE,
    ACTION_PLAY,
    ACTION_REWIND,
    ACTION_SKIP_TO_PREVIOUS,
    ACTION_SKIP_TO_NEXT,
    ACTION_FAST_FORWARD,
    ACTION_PLAY_PAUSE
  })
  @Retention(RetentionPolicy.SOURCE)
  public @interface MediaKeyAction {}

  /**
   * Indicates this session supports the stop command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_STOP = 1 << 0;

  /**
   * Indicates this session supports the pause command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_PAUSE = 1 << 1;

  /**
   * Indicates this session supports the play command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_PLAY = 1 << 2;

  /**
   * Indicates this session supports the rewind command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_REWIND = 1 << 3;

  /**
   * Indicates this session supports the previous command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_SKIP_TO_PREVIOUS = 1 << 4;

  /**
   * Indicates this session supports the next command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_SKIP_TO_NEXT = 1 << 5;

  /**
   * Indicates this session supports the fast forward command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_FAST_FORWARD = 1 << 6;

  /**
   * Indicates this session supports the set rating command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_SET_RATING = 1 << 7;

  /**
   * Indicates this session supports the seek to command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_SEEK_TO = 1 << 8;

  /**
   * Indicates this session supports the play/pause toggle command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_PLAY_PAUSE = 1 << 9;

  /**
   * Indicates this session supports the play from media id command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_PLAY_FROM_MEDIA_ID = 1 << 10;

  /**
   * Indicates this session supports the play from search command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_PLAY_FROM_SEARCH = 1 << 11;

  /**
   * Indicates this session supports the skip to queue item command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_SKIP_TO_QUEUE_ITEM = 1 << 12;

  /**
   * Indicates this session supports the play from URI command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_PLAY_FROM_URI = 1 << 13;

  /**
   * Indicates this session supports the prepare command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_PREPARE = 1 << 14;

  /**
   * Indicates this session supports the prepare from media id command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_PREPARE_FROM_MEDIA_ID = 1 << 15;

  /**
   * Indicates this session supports the prepare from search command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_PREPARE_FROM_SEARCH = 1 << 16;

  /**
   * Indicates this session supports the prepare from URI command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_PREPARE_FROM_URI = 1 << 17;

  /**
   * Indicates this session supports the set repeat mode command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_SET_REPEAT_MODE = 1 << 18;

  /**
   * Indicates this session supports the set shuffle mode enabled command.
   *
   * @see Builder#setActions(long)
   * @deprecated Use {@link #ACTION_SET_SHUFFLE_MODE} instead.
   */
  @Deprecated public static final long ACTION_SET_SHUFFLE_MODE_ENABLED = 1 << 19;

  /**
   * Indicates this session supports the set captioning enabled command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_SET_CAPTIONING_ENABLED = 1 << 20;

  /**
   * Indicates this session supports the set shuffle mode command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_SET_SHUFFLE_MODE = 1 << 21;

  /**
   * Indicates this session supports the set playback speed command.
   *
   * @see Builder#setActions(long)
   */
  public static final long ACTION_SET_PLAYBACK_SPEED = 1 << 22;

  /** */
  @IntDef({
    STATE_NONE,
    STATE_STOPPED,
    STATE_PAUSED,
    STATE_PLAYING,
    STATE_FAST_FORWARDING,
    STATE_REWINDING,
    STATE_BUFFERING,
    STATE_ERROR,
    STATE_CONNECTING,
    STATE_SKIPPING_TO_PREVIOUS,
    STATE_SKIPPING_TO_NEXT,
    STATE_SKIPPING_TO_QUEUE_ITEM
  })
  @Retention(RetentionPolicy.SOURCE)
  public @interface State {}

  /**
   * This is the default playback state and indicates that no media has been added yet, or the
   * performer has been reset and has no content to play.
   *
   * @see Builder#setState
   */
  public static final int STATE_NONE = 0;

  /**
   * State indicating this item is currently stopped.
   *
   * @see Builder#setState
   */
  public static final int STATE_STOPPED = 1;

  /**
   * State indicating this item is currently paused.
   *
   * @see Builder#setState
   */
  public static final int STATE_PAUSED = 2;

  /**
   * State indicating this item is currently playing.
   *
   * @see Builder#setState
   */
  public static final int STATE_PLAYING = 3;

  /**
   * State indicating this item is currently fast forwarding.
   *
   * @see Builder#setState
   */
  public static final int STATE_FAST_FORWARDING = 4;

  /**
   * State indicating this item is currently rewinding.
   *
   * @see Builder#setState
   */
  public static final int STATE_REWINDING = 5;

  /**
   * State indicating this item is currently buffering and will begin playing when enough data has
   * buffered.
   *
   * @see Builder#setState
   */
  public static final int STATE_BUFFERING = 6;

  /**
   * State indicating this item is currently in an error state. The error code should also be set
   * when entering this state.
   *
   * @see Builder#setState
   * @see Builder#setErrorMessage(int, CharSequence)
   */
  public static final int STATE_ERROR = 7;

  /**
   * State indicating the class doing playback is currently connecting to a route. Depending on the
   * implementation you may return to the previous state when the connection finishes or enter
   * {@link #STATE_NONE}. If the connection failed {@link #STATE_ERROR} should be used.
   *
   * <p>On devices earlier than API 21, this will appear as {@link #STATE_BUFFERING}
   *
   * @see Builder#setState
   */
  public static final int STATE_CONNECTING = 8;

  /**
   * State indicating the player is currently skipping to the previous item.
   *
   * @see Builder#setState
   */
  public static final int STATE_SKIPPING_TO_PREVIOUS = 9;

  /**
   * State indicating the player is currently skipping to the next item.
   *
   * @see Builder#setState
   */
  public static final int STATE_SKIPPING_TO_NEXT = 10;

  /**
   * State indicating the player is currently skipping to a specific item in the queue.
   *
   * <p>On devices earlier than API 21, this will appear as {@link #STATE_SKIPPING_TO_NEXT}
   *
   * @see Builder#setState
   */
  public static final int STATE_SKIPPING_TO_QUEUE_ITEM = 11;

  /** Use this value for the position to indicate the position is not known. */
  public static final long PLAYBACK_POSITION_UNKNOWN = -1;

  /** */
  @IntDef({
    REPEAT_MODE_INVALID,
    REPEAT_MODE_NONE,
    REPEAT_MODE_ONE,
    REPEAT_MODE_ALL,
    REPEAT_MODE_GROUP
  })
  @Retention(RetentionPolicy.SOURCE)
  public @interface RepeatMode {}

  /**
   * {@code MediaControllerCompat.TransportControls#getRepeatMode()} returns this value when the
   * session is not ready for providing its repeat mode.
   */
  public static final int REPEAT_MODE_INVALID = -1;

  /**
   * Use this value with {@link MediaControllerCompat.TransportControls#setRepeatMode} to indicate
   * that the playback will be stopped at the end of the playing media list.
   */
  public static final int REPEAT_MODE_NONE = 0;

  /**
   * Use this value with {@link MediaControllerCompat.TransportControls#setRepeatMode} to indicate
   * that the playback of the current playing media item will be repeated.
   */
  public static final int REPEAT_MODE_ONE = 1;

  /**
   * Use this value with {@link MediaControllerCompat.TransportControls#setRepeatMode} to indicate
   * that the playback of the playing media list will be repeated.
   */
  public static final int REPEAT_MODE_ALL = 2;

  /**
   * Use this value with {@link MediaControllerCompat.TransportControls#setRepeatMode} to indicate
   * that the playback of the playing media group will be repeated. A group is a logical block of
   * media items which is specified in the section 5.7 of the Bluetooth AVRCP 1.6.
   */
  public static final int REPEAT_MODE_GROUP = 3;

  /** */
  @IntDef({SHUFFLE_MODE_INVALID, SHUFFLE_MODE_NONE, SHUFFLE_MODE_ALL, SHUFFLE_MODE_GROUP})
  @Retention(RetentionPolicy.SOURCE)
  public @interface ShuffleMode {}

  /**
   * {@code MediaControllerCompat.TransportControls#getShuffleMode()} returns this value when the
   * session is not ready for providing its shuffle mode.
   */
  public static final int SHUFFLE_MODE_INVALID = -1;

  /**
   * Use this value with {@link MediaControllerCompat.TransportControls#setShuffleMode} to indicate
   * that the media list will be played in order.
   */
  public static final int SHUFFLE_MODE_NONE = 0;

  /**
   * Use this value with {@link MediaControllerCompat.TransportControls#setShuffleMode} to indicate
   * that the media list will be played in shuffled order.
   */
  public static final int SHUFFLE_MODE_ALL = 1;

  /**
   * Use this value with {@link MediaControllerCompat.TransportControls#setShuffleMode} to indicate
   * that the media group will be played in shuffled order. A group is a logical block of media
   * items which is specified in the section 5.7 of the Bluetooth AVRCP 1.6.
   */
  public static final int SHUFFLE_MODE_GROUP = 2;

  /** Supported error codes. */
  @IntDef({
    ERROR_CODE_UNKNOWN_ERROR,
    ERROR_CODE_APP_ERROR,
    ERROR_CODE_NOT_SUPPORTED,
    ERROR_CODE_AUTHENTICATION_EXPIRED,
    ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED,
    ERROR_CODE_CONCURRENT_STREAM_LIMIT,
    ERROR_CODE_PARENTAL_CONTROL_RESTRICTED,
    ERROR_CODE_NOT_AVAILABLE_IN_REGION,
    ERROR_CODE_CONTENT_ALREADY_PLAYING,
    ERROR_CODE_SKIP_LIMIT_REACHED,
    ERROR_CODE_ACTION_ABORTED,
    ERROR_CODE_END_OF_QUEUE
  })
  @Retention(RetentionPolicy.SOURCE)
  public @interface ErrorCode {}

  /**
   * This is the default error code and indicates that none of the other error codes applies. The
   * error code should be set when entering {@link #STATE_ERROR}.
   */
  public static final int ERROR_CODE_UNKNOWN_ERROR = 0;

  /**
   * Error code when the application state is invalid to fulfill the request. The error code should
   * be set when entering {@link #STATE_ERROR}.
   */
  public static final int ERROR_CODE_APP_ERROR = 1;

  /**
   * Error code when the request is not supported by the application. The error code should be set
   * when entering {@link #STATE_ERROR}.
   */
  public static final int ERROR_CODE_NOT_SUPPORTED = 2;

  /**
   * Error code when the request cannot be performed because authentication has expired. The error
   * code should be set when entering {@link #STATE_ERROR}.
   */
  public static final int ERROR_CODE_AUTHENTICATION_EXPIRED = 3;

  /**
   * Error code when a premium account is required for the request to succeed. The error code should
   * be set when entering {@link #STATE_ERROR}.
   */
  public static final int ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED = 4;

  /**
   * Error code when too many concurrent streams are detected. The error code should be set when
   * entering {@link #STATE_ERROR}.
   */
  public static final int ERROR_CODE_CONCURRENT_STREAM_LIMIT = 5;

  /**
   * Error code when the content is blocked due to parental controls. The error code should be set
   * when entering {@link #STATE_ERROR}.
   */
  public static final int ERROR_CODE_PARENTAL_CONTROL_RESTRICTED = 6;

  /**
   * Error code when the content is blocked due to being regionally unavailable. The error code
   * should be set when entering {@link #STATE_ERROR}.
   */
  public static final int ERROR_CODE_NOT_AVAILABLE_IN_REGION = 7;

  /**
   * Error code when the requested content is already playing. The error code should be set when
   * entering {@link #STATE_ERROR}.
   */
  public static final int ERROR_CODE_CONTENT_ALREADY_PLAYING = 8;

  /**
   * Error code when the application cannot skip any more songs because skip limit is reached. The
   * error code should be set when entering {@link #STATE_ERROR}.
   */
  public static final int ERROR_CODE_SKIP_LIMIT_REACHED = 9;

  /**
   * Error code when the action is interrupted due to some external event. The error code should be
   * set when entering {@link #STATE_ERROR}.
   */
  public static final int ERROR_CODE_ACTION_ABORTED = 10;

  /**
   * Error code when the playback navigation (previous, next) is not possible because the queue was
   * exhausted. The error code should be set when entering {@link #STATE_ERROR}.
   */
  public static final int ERROR_CODE_END_OF_QUEUE = 11;

  final int state;
  final long position;
  final long bufferedPosition;
  final float speed;
  final long actions;
  final int errorCode;
  @Nullable final CharSequence errorMessage;
  final long updateTime;
  List<PlaybackStateCompat.CustomAction> customActions;
  final long activeItemId;
  @Nullable final Bundle extras;

  @Nullable private PlaybackState stateFwk;

  PlaybackStateCompat(
      int state,
      long position,
      long bufferedPosition,
      float rate,
      long actions,
      int errorCode,
      @Nullable CharSequence errorMessage,
      long updateTime,
      @Nullable List<PlaybackStateCompat.CustomAction> customActions,
      long activeItemId,
      @Nullable Bundle extras) {
    this.state = state;
    this.position = position;
    this.bufferedPosition = bufferedPosition;
    speed = rate;
    this.actions = actions;
    this.errorCode = errorCode;
    this.errorMessage = errorMessage;
    this.updateTime = updateTime;
    this.customActions =
        customActions == null ? ImmutableList.of() : new ArrayList<>(customActions);
    this.activeItemId = activeItemId;
    this.extras = extras;
  }

  PlaybackStateCompat(Parcel in) {
    state = in.readInt();
    position = in.readLong();
    speed = in.readFloat();
    updateTime = in.readLong();
    bufferedPosition = in.readLong();
    this.actions = in.readLong();
    errorMessage = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
    List<PlaybackStateCompat.CustomAction> actions = in.createTypedArrayList(CustomAction.CREATOR);
    customActions = actions == null ? ImmutableList.of() : actions;
    activeItemId = in.readLong();
    extras = in.readBundle(MediaSessionCompat.class.getClassLoader());
    // New attributes should be added at the end for backward compatibility.
    errorCode = in.readInt();
  }

  @Override
  public String toString() {
    StringBuilder bob = new StringBuilder("PlaybackState {");
    bob.append("state=").append(state);
    bob.append(", position=").append(position);
    bob.append(", buffered position=").append(bufferedPosition);
    bob.append(", speed=").append(speed);
    bob.append(", updated=").append(updateTime);
    bob.append(", actions=").append(actions);
    bob.append(", error code=").append(errorCode);
    bob.append(", error message=").append(errorMessage);
    bob.append(", custom actions=").append(customActions);
    bob.append(", active item id=").append(activeItemId);
    bob.append("}");
    return bob.toString();
  }

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeInt(state);
    dest.writeLong(position);
    dest.writeFloat(speed);
    dest.writeLong(updateTime);
    dest.writeLong(bufferedPosition);
    dest.writeLong(actions);
    TextUtils.writeToParcel(errorMessage, dest, flags);
    dest.writeTypedList(customActions);
    dest.writeLong(activeItemId);
    dest.writeBundle(extras);
    // New attributes should be added at the end for backward compatibility.
    dest.writeInt(errorCode);
  }

  /**
   * Get the current state of playback. One of the following:
   *
   * <ul>
   *   <li>{@link PlaybackStateCompat#STATE_NONE}
   *   <li>{@link PlaybackStateCompat#STATE_STOPPED}
   *   <li>{@link PlaybackStateCompat#STATE_PLAYING}
   *   <li>{@link PlaybackStateCompat#STATE_PAUSED}
   *   <li>{@link PlaybackStateCompat#STATE_FAST_FORWARDING}
   *   <li>{@link PlaybackStateCompat#STATE_REWINDING}
   *   <li>{@link PlaybackStateCompat#STATE_BUFFERING}
   *   <li>{@link PlaybackStateCompat#STATE_ERROR}
   *   <li>{@link PlaybackStateCompat#STATE_CONNECTING}
   *   <li>{@link PlaybackStateCompat#STATE_SKIPPING_TO_PREVIOUS}
   *   <li>{@link PlaybackStateCompat#STATE_SKIPPING_TO_NEXT}
   *   <li>{@link PlaybackStateCompat#STATE_SKIPPING_TO_QUEUE_ITEM}
   * </ul>
   */
  @State
  public int getState() {
    return state;
  }

  /** Get the playback position in ms at last position update time. */
  public long getPosition() {
    return position;
  }

  /**
   * Get the elapsed real time at which position was last updated. If the position has never been
   * set this will return 0;
   *
   * @return The last time the position was updated.
   */
  public long getLastPositionUpdateTime() {
    return updateTime;
  }

  /**
   * Get the current playback position in ms.
   *
   * @param timeDiff Only used for testing, otherwise it should be null.
   * @return The current playback position in ms
   */
  public long getCurrentPosition(@Nullable Long timeDiff) {
    long expectedPosition =
        position
            + (long)
                (speed
                    * ((timeDiff != null) ? timeDiff : SystemClock.elapsedRealtime() - updateTime));
    return Math.max(0, expectedPosition);
  }

  /**
   * Get the current buffered position in ms. This is the farthest playback point that can be
   * reached from the current position using only buffered content.
   */
  public long getBufferedPosition() {
    return bufferedPosition;
  }

  /**
   * Get the current playback speed as a multiple of normal playback. This should be negative when
   * rewinding. A value of 1 means normal playback and 0 means paused.
   *
   * @return The current speed of playback.
   */
  public float getPlaybackSpeed() {
    return speed;
  }

  /**
   * Get the current actions available on this session. This should use a bitmask of the available
   * actions.
   *
   * <ul>
   *   <li>{@link PlaybackStateCompat#ACTION_SKIP_TO_PREVIOUS}
   *   <li>{@link PlaybackStateCompat#ACTION_REWIND}
   *   <li>{@link PlaybackStateCompat#ACTION_PLAY}
   *   <li>{@link PlaybackStateCompat#ACTION_PLAY_PAUSE}
   *   <li>{@link PlaybackStateCompat#ACTION_PAUSE}
   *   <li>{@link PlaybackStateCompat#ACTION_STOP}
   *   <li>{@link PlaybackStateCompat#ACTION_FAST_FORWARD}
   *   <li>{@link PlaybackStateCompat#ACTION_SKIP_TO_NEXT}
   *   <li>{@link PlaybackStateCompat#ACTION_SEEK_TO}
   *   <li>{@link PlaybackStateCompat#ACTION_SET_RATING}
   *   <li>{@link PlaybackStateCompat#ACTION_PLAY_FROM_MEDIA_ID}
   *   <li>{@link PlaybackStateCompat#ACTION_PLAY_FROM_SEARCH}
   *   <li>{@link PlaybackStateCompat#ACTION_SKIP_TO_QUEUE_ITEM}
   *   <li>{@link PlaybackStateCompat#ACTION_PLAY_FROM_URI}
   *   <li>{@link PlaybackStateCompat#ACTION_PREPARE}
   *   <li>{@link PlaybackStateCompat#ACTION_PREPARE_FROM_MEDIA_ID}
   *   <li>{@link PlaybackStateCompat#ACTION_PREPARE_FROM_SEARCH}
   *   <li>{@link PlaybackStateCompat#ACTION_PREPARE_FROM_URI}
   *   <li>{@link PlaybackStateCompat#ACTION_SET_REPEAT_MODE}
   *   <li>{@link PlaybackStateCompat#ACTION_SET_SHUFFLE_MODE}
   *   <li>{@link PlaybackStateCompat#ACTION_SET_CAPTIONING_ENABLED}
   *   <li>{@link PlaybackStateCompat#ACTION_SET_PLAYBACK_SPEED}
   * </ul>
   */
  @Actions
  public long getActions() {
    return actions;
  }

  /** Get the list of custom actions. */
  public List<PlaybackStateCompat.CustomAction> getCustomActions() {
    return customActions;
  }

  /**
   * Get the error code. This should be set when the state is {@link
   * PlaybackStateCompat#STATE_ERROR}.
   *
   * @see #ERROR_CODE_UNKNOWN_ERROR
   * @see #ERROR_CODE_APP_ERROR
   * @see #ERROR_CODE_NOT_SUPPORTED
   * @see #ERROR_CODE_AUTHENTICATION_EXPIRED
   * @see #ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED
   * @see #ERROR_CODE_CONCURRENT_STREAM_LIMIT
   * @see #ERROR_CODE_PARENTAL_CONTROL_RESTRICTED
   * @see #ERROR_CODE_NOT_AVAILABLE_IN_REGION
   * @see #ERROR_CODE_CONTENT_ALREADY_PLAYING
   * @see #ERROR_CODE_SKIP_LIMIT_REACHED
   * @see #ERROR_CODE_ACTION_ABORTED
   * @see #ERROR_CODE_END_OF_QUEUE
   * @see #getErrorMessage()
   */
  @ErrorCode
  public int getErrorCode() {
    return errorCode;
  }

  /**
   * Get the user readable optional error message. This may be set when the state is {@link
   * PlaybackStateCompat#STATE_ERROR}.
   *
   * @see #getErrorCode()
   */
  @Nullable
  public CharSequence getErrorMessage() {
    return errorMessage;
  }

  /**
   * Get the id of the currently active item in the queue. If there is no queue or a queue is not
   * supported by the session this will be {@link MediaSessionCompat.QueueItem#UNKNOWN_ID}.
   *
   * @return The id of the currently active item in the queue or {@link
   *     MediaSessionCompat.QueueItem#UNKNOWN_ID}.
   */
  public long getActiveQueueItemId() {
    return activeItemId;
  }

  /**
   * Get any custom extras that were set on this playback state.
   *
   * @return The extras for this state or null.
   */
  @Nullable
  public Bundle getExtras() {
    return extras;
  }

  /**
   * Creates an instance from a framework {@link android.media.session.PlaybackState} object.
   *
   * @param stateFwk A {@link android.media.session.PlaybackState} object, or null if none.
   * @return An equivalent {@link PlaybackStateCompat} object, or null if none.
   */
  @Nullable
  public static PlaybackStateCompat fromPlaybackState(@Nullable PlaybackState stateFwk) {
    if (stateFwk != null) {
      List<PlaybackState.CustomAction> customActionFwks = stateFwk.getCustomActions();
      List<PlaybackStateCompat.CustomAction> customActions = null;
      if (customActionFwks != null) {
        customActions = new ArrayList<>(customActionFwks.size());
        for (PlaybackState.CustomAction customActionFwk : customActionFwks) {
          if (customActionFwk == null) {
            continue;
          }
          customActions.add(CustomAction.fromCustomAction(customActionFwk));
        }
      }
      Bundle extras;
      if (Build.VERSION.SDK_INT >= 22) {
        extras = Api22Impl.getExtras(stateFwk);
        MediaSessionCompat.ensureClassLoader(extras);
      } else {
        extras = null;
      }
      PlaybackStateCompat stateCompat =
          new PlaybackStateCompat(
              stateFwk.getState(),
              stateFwk.getPosition(),
              stateFwk.getBufferedPosition(),
              stateFwk.getPlaybackSpeed(),
              stateFwk.getActions(),
              ERROR_CODE_UNKNOWN_ERROR,
              stateFwk.getErrorMessage(),
              stateFwk.getLastPositionUpdateTime(),
              customActions,
              stateFwk.getActiveQueueItemId(),
              extras);
      stateCompat.stateFwk = stateFwk;
      return stateCompat;
    } else {
      return null;
    }
  }

  /**
   * Gets the underlying framework {@link android.media.session.PlaybackState} object.
   *
   * @return An equivalent {@link android.media.session.PlaybackState} object.
   */
  @SuppressWarnings("argument.type.incompatible") // setErrorMessage not annotated as nullable
  public PlaybackState getPlaybackState() {
    if (stateFwk == null) {
      PlaybackState.Builder builder = new PlaybackState.Builder();
      builder.setState(state, position, speed, updateTime);
      builder.setBufferedPosition(bufferedPosition);
      builder.setActions(actions);
      builder.setErrorMessage(errorMessage);
      for (PlaybackStateCompat.CustomAction customAction : customActions) {
        PlaybackState.CustomAction action =
            (PlaybackState.CustomAction) customAction.getCustomAction();
        if (action != null) {
          builder.addCustomAction(action);
        }
      }
      builder.setActiveQueueItemId(activeItemId);
      if (Build.VERSION.SDK_INT >= 22) {
        Api22Impl.setExtras(builder, extras);
      }
      stateFwk = builder.build();
    }
    return stateFwk;
  }

  public static final Parcelable.Creator<PlaybackStateCompat> CREATOR =
      new Parcelable.Creator<PlaybackStateCompat>() {
        @Override
        public PlaybackStateCompat createFromParcel(Parcel in) {
          return new PlaybackStateCompat(in);
        }

        @Override
        public PlaybackStateCompat[] newArray(int size) {
          return new PlaybackStateCompat[size];
        }
      };

  /**
   * {@link PlaybackStateCompat.CustomAction CustomActions} can be used to extend the capabilities
   * of the standard transport controls by exposing app specific actions to {@link
   * MediaControllerCompat Controllers}.
   */
  public static final class CustomAction implements Parcelable {
    private final String action;
    private final CharSequence name;
    private final int icon;
    @Nullable private final Bundle extras;

    @Nullable private PlaybackState.CustomAction customActionFwk;

    /** Use {@link PlaybackStateCompat.CustomAction.Builder#build()}. */
    CustomAction(String action, CharSequence name, int icon, @Nullable Bundle extras) {
      this.action = action;
      this.name = name;
      this.icon = icon;
      this.extras = extras;
    }

    CustomAction(Parcel in) {
      action = checkNotNull(in.readString());
      name = checkNotNull(TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in));
      icon = in.readInt();
      extras = in.readBundle(MediaSessionCompat.class.getClassLoader());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
      dest.writeString(action);
      TextUtils.writeToParcel(name, dest, flags);
      dest.writeInt(icon);
      dest.writeBundle(extras);
    }

    @Override
    public int describeContents() {
      return 0;
    }

    /**
     * Creates an instance from a framework {@link android.media.session.PlaybackState.CustomAction}
     * object.
     *
     * @param customActionObj A {@link android.media.session.PlaybackState.CustomAction} object, or
     *     null if none.
     * @return An equivalent {@link PlaybackStateCompat.CustomAction} object, or null if none.
     */
    public static PlaybackStateCompat.CustomAction fromCustomAction(Object customActionObj) {
      PlaybackState.CustomAction customActionFwk = (PlaybackState.CustomAction) customActionObj;
      Bundle extras = customActionFwk.getExtras();
      MediaSessionCompat.ensureClassLoader(extras);
      PlaybackStateCompat.CustomAction customActionCompat =
          new PlaybackStateCompat.CustomAction(
              customActionFwk.getAction(),
              customActionFwk.getName(),
              customActionFwk.getIcon(),
              extras);
      customActionCompat.customActionFwk = customActionFwk;
      return customActionCompat;
    }

    /**
     * Gets the underlying framework {@link android.media.session.PlaybackState.CustomAction}
     * object.
     *
     * @return An equivalent {@link android.media.session.PlaybackState.CustomAction} object, or
     *     null if none.
     */
    @SuppressWarnings("argument.type.incompatible") // setExtras not annotated as nullable
    @Nullable
    public Object getCustomAction() {
      if (customActionFwk != null) {
        return customActionFwk;
      }

      PlaybackState.CustomAction.Builder builder =
          new PlaybackState.CustomAction.Builder(action, name, icon);
      builder.setExtras(extras);
      return builder.build();
    }

    public static final Parcelable.Creator<PlaybackStateCompat.CustomAction> CREATOR =
        new Parcelable.Creator<PlaybackStateCompat.CustomAction>() {

          @Override
          public PlaybackStateCompat.CustomAction createFromParcel(Parcel p) {
            return new PlaybackStateCompat.CustomAction(p);
          }

          @Override
          public PlaybackStateCompat.CustomAction[] newArray(int size) {
            return new PlaybackStateCompat.CustomAction[size];
          }
        };

    /**
     * Returns the action of the {@link CustomAction}.
     *
     * @return The action of the {@link CustomAction}.
     */
    public String getAction() {
      return action;
    }

    /**
     * Returns the display name of this action. e.g. "Favorite"
     *
     * @return The display name of this {@link CustomAction}.
     */
    public CharSequence getName() {
      return name;
    }

    /**
     * Returns the resource id of the icon in the {@link MediaSessionCompat Session's} package.
     *
     * @return The resource id of the icon in the {@link MediaSessionCompat Session's} package.
     */
    public int getIcon() {
      return icon;
    }

    /**
     * Returns extras which provide additional application-specific information about the action, or
     * null if none. These arguments are meant to be consumed by a {@link MediaControllerCompat} if
     * it knows how to handle them.
     *
     * @return Optional arguments for the {@link CustomAction}.
     */
    @Nullable
    public Bundle getExtras() {
      return extras;
    }

    @Override
    public String toString() {
      return "Action:" + "mName='" + name + ", mIcon=" + icon + ", mExtras=" + extras;
    }

    /** Builder for {@link CustomAction} objects. */
    public static final class Builder {
      private final String action;
      private final CharSequence name;
      private final int icon;
      @Nullable private Bundle extras;

      /**
       * Creates a {@link CustomAction} builder with the id, name, and icon set.
       *
       * @param action The action of the {@link CustomAction}.
       * @param name The display name of the {@link CustomAction}. This name will be displayed along
       *     side the action if the UI supports it.
       * @param icon The icon resource id of the {@link CustomAction}. This resource id must be in
       *     the same package as the {@link MediaSessionCompat}. It will be displayed with the
       *     custom action if the UI supports it.
       */
      public Builder(String action, CharSequence name, int icon) {
        if (TextUtils.isEmpty(action)) {
          throw new IllegalArgumentException("You must specify an action to build a CustomAction");
        }
        if (TextUtils.isEmpty(name)) {
          throw new IllegalArgumentException("You must specify a name to build a CustomAction");
        }
        if (icon == 0) {
          throw new IllegalArgumentException(
              "You must specify an icon resource id to build a CustomAction");
        }
        this.action = action;
        this.name = name;
        this.icon = icon;
      }

      /**
       * Set optional extras for the {@link CustomAction}. These extras are meant to be consumed by
       * a {@link MediaControllerCompat} if it knows how to handle them. Keys should be fully
       * qualified (e.g. "com.example.MY_ARG") to avoid collisions.
       *
       * @param extras Optional extras for the {@link CustomAction}.
       * @return this.
       */
      public Builder setExtras(@Nullable Bundle extras) {
        this.extras = extras;
        return this;
      }

      /**
       * Build and return the {@link CustomAction} instance with the specified values.
       *
       * @return A new {@link CustomAction} instance.
       */
      public CustomAction build() {
        return new CustomAction(action, name, icon, extras);
      }
    }
  }

  /** Builder for {@link PlaybackStateCompat} objects. */
  public static final class Builder {
    private final List<PlaybackStateCompat.CustomAction> customActions = new ArrayList<>();

    private int state;
    private long position;
    private long bufferedPosition;
    private float rate;
    private long actions;
    private int errorCode;
    @Nullable private CharSequence errorMessage;
    private long updateTime;
    private long activeItemId = MediaSessionCompat.QueueItem.UNKNOWN_ID;
    @Nullable private Bundle extras;

    /** Create an empty Builder. */
    public Builder() {}

    /**
     * Create a Builder using a {@link PlaybackStateCompat} instance to set the initial values.
     *
     * @param source The playback state to copy.
     */
    public Builder(PlaybackStateCompat source) {
      state = source.state;
      position = source.position;
      rate = source.speed;
      updateTime = source.updateTime;
      bufferedPosition = source.bufferedPosition;
      actions = source.actions;
      errorCode = source.errorCode;
      errorMessage = source.errorMessage;
      if (source.customActions != null) {
        customActions.addAll(source.customActions);
      }
      activeItemId = source.activeItemId;
      extras = source.extras;
    }

    /**
     * Set the current state of playback.
     *
     * <p>The position must be in ms and indicates the current playback position within the track.
     * If the position is unknown use {@link #PLAYBACK_POSITION_UNKNOWN}.
     *
     * <p>The rate is a multiple of normal playback and should be 0 when paused and negative when
     * rewinding. Normal playback rate is 1.0.
     *
     * <p>The state must be one of the following:
     *
     * <ul>
     *   <li>{@link PlaybackStateCompat#STATE_NONE}
     *   <li>{@link PlaybackStateCompat#STATE_STOPPED}
     *   <li>{@link PlaybackStateCompat#STATE_PLAYING}
     *   <li>{@link PlaybackStateCompat#STATE_PAUSED}
     *   <li>{@link PlaybackStateCompat#STATE_FAST_FORWARDING}
     *   <li>{@link PlaybackStateCompat#STATE_REWINDING}
     *   <li>{@link PlaybackStateCompat#STATE_BUFFERING}
     *   <li>{@link PlaybackStateCompat#STATE_ERROR}
     *   <li>{@link PlaybackStateCompat#STATE_CONNECTING}
     *   <li>{@link PlaybackStateCompat#STATE_SKIPPING_TO_PREVIOUS}
     *   <li>{@link PlaybackStateCompat#STATE_SKIPPING_TO_NEXT}
     *   <li>{@link PlaybackStateCompat#STATE_SKIPPING_TO_QUEUE_ITEM}
     * </ul>
     *
     * @param state The current state of playback.
     * @param position The position in the current track in ms.
     * @param playbackSpeed The current rate of playback as a multiple of normal playback.
     */
    public Builder setState(@State int state, long position, float playbackSpeed) {
      return setState(state, position, playbackSpeed, SystemClock.elapsedRealtime());
    }

    /**
     * Set the current state of playback.
     *
     * <p>The position must be in ms and indicates the current playback position within the track.
     * If the position is unknown use {@link #PLAYBACK_POSITION_UNKNOWN}.
     *
     * <p>The rate is a multiple of normal playback and should be 0 when paused and negative when
     * rewinding. Normal playback rate is 1.0.
     *
     * <p>The state must be one of the following:
     *
     * <ul>
     *   <li>{@link PlaybackStateCompat#STATE_NONE}
     *   <li>{@link PlaybackStateCompat#STATE_STOPPED}
     *   <li>{@link PlaybackStateCompat#STATE_PLAYING}
     *   <li>{@link PlaybackStateCompat#STATE_PAUSED}
     *   <li>{@link PlaybackStateCompat#STATE_FAST_FORWARDING}
     *   <li>{@link PlaybackStateCompat#STATE_REWINDING}
     *   <li>{@link PlaybackStateCompat#STATE_BUFFERING}
     *   <li>{@link PlaybackStateCompat#STATE_ERROR}
     *   <li>{@link PlaybackStateCompat#STATE_CONNECTING}
     *   <li>{@link PlaybackStateCompat#STATE_SKIPPING_TO_PREVIOUS}
     *   <li>{@link PlaybackStateCompat#STATE_SKIPPING_TO_NEXT}
     *   <li>{@link PlaybackStateCompat#STATE_SKIPPING_TO_QUEUE_ITEM}
     * </ul>
     *
     * @param state The current state of playback.
     * @param position The position in the current item in ms.
     * @param playbackSpeed The current speed of playback as a multiple of normal playback.
     * @param updateTime The time in the {@link SystemClock#elapsedRealtime} timebase that the
     *     position was updated at.
     * @return this
     */
    public Builder setState(@State int state, long position, float playbackSpeed, long updateTime) {
      this.state = state;
      this.position = position;
      this.updateTime = updateTime;
      rate = playbackSpeed;
      return this;
    }

    /**
     * Set the current buffered position in ms. This is the farthest playback point that can be
     * reached from the current position using only buffered content.
     *
     * @return this
     */
    public Builder setBufferedPosition(long bufferPosition) {
      bufferedPosition = bufferPosition;
      return this;
    }

    /**
     * Set the current capabilities available on this session. This should use a bitmask of the
     * available capabilities.
     *
     * <ul>
     *   <li>{@link PlaybackStateCompat#ACTION_SKIP_TO_PREVIOUS}
     *   <li>{@link PlaybackStateCompat#ACTION_REWIND}
     *   <li>{@link PlaybackStateCompat#ACTION_PLAY}
     *   <li>{@link PlaybackStateCompat#ACTION_PLAY_PAUSE}
     *   <li>{@link PlaybackStateCompat#ACTION_PAUSE}
     *   <li>{@link PlaybackStateCompat#ACTION_STOP}
     *   <li>{@link PlaybackStateCompat#ACTION_FAST_FORWARD}
     *   <li>{@link PlaybackStateCompat#ACTION_SKIP_TO_NEXT}
     *   <li>{@link PlaybackStateCompat#ACTION_SEEK_TO}
     *   <li>{@link PlaybackStateCompat#ACTION_SET_RATING}
     *   <li>{@link PlaybackStateCompat#ACTION_PLAY_FROM_MEDIA_ID}
     *   <li>{@link PlaybackStateCompat#ACTION_PLAY_FROM_SEARCH}
     *   <li>{@link PlaybackStateCompat#ACTION_SKIP_TO_QUEUE_ITEM}
     *   <li>{@link PlaybackStateCompat#ACTION_PLAY_FROM_URI}
     *   <li>{@link PlaybackStateCompat#ACTION_PREPARE}
     *   <li>{@link PlaybackStateCompat#ACTION_PREPARE_FROM_MEDIA_ID}
     *   <li>{@link PlaybackStateCompat#ACTION_PREPARE_FROM_SEARCH}
     *   <li>{@link PlaybackStateCompat#ACTION_PREPARE_FROM_URI}
     *   <li>{@link PlaybackStateCompat#ACTION_SET_REPEAT_MODE}
     *   <li>{@link PlaybackStateCompat#ACTION_SET_SHUFFLE_MODE}
     *   <li>{@link PlaybackStateCompat#ACTION_SET_CAPTIONING_ENABLED}
     *   <li>{@link PlaybackStateCompat#ACTION_SET_PLAYBACK_SPEED}
     * </ul>
     *
     * @return this
     */
    public Builder setActions(@Actions long capabilities) {
      actions = capabilities;
      return this;
    }

    /**
     * Add a custom action to the playback state. Actions can be used to expose additional
     * functionality to {@link MediaControllerCompat Controllers} beyond what is offered by the
     * standard transport controls.
     *
     * <p>e.g. start a radio station based on the current item or skip ahead by 30 seconds.
     *
     * @param action An identifier for this action. It can be sent back to the {@link
     *     MediaSessionCompat} through {@link
     *     MediaControllerCompat.TransportControls#sendCustomAction(String, Bundle)}.
     * @param name The display name for the action. If text is shown with the action or used for
     *     accessibility, this is what should be used.
     * @param icon The resource action of the icon that should be displayed for the action. The
     *     resource should be in the package of the {@link MediaSessionCompat}.
     * @return this
     */
    public Builder addCustomAction(String action, String name, int icon) {
      return addCustomAction(new PlaybackStateCompat.CustomAction(action, name, icon, null));
    }

    /**
     * Add a custom action to the playback state. Actions can be used to expose additional
     * functionality to {@link MediaControllerCompat Controllers} beyond what is offered by the
     * standard transport controls.
     *
     * <p>An example of an action would be to start a radio station based on the current item or to
     * skip ahead by 30 seconds.
     *
     * @param customAction The custom action to add to the {@link PlaybackStateCompat}.
     * @return this
     */
    public Builder addCustomAction(PlaybackStateCompat.CustomAction customAction) {
      customActions.add(customAction);
      return this;
    }

    /**
     * Set the active item in the play queue by specifying its id. The default value is {@link
     * MediaSessionCompat.QueueItem#UNKNOWN_ID}
     *
     * @param id The id of the active item.
     * @return this
     */
    public Builder setActiveQueueItemId(long id) {
      activeItemId = id;
      return this;
    }

    /**
     * Set a user readable error message. This should be set when the state is {@link
     * PlaybackStateCompat#STATE_ERROR}.
     *
     * @return this
     * @deprecated Use {@link #setErrorMessage(int, CharSequence)} instead.
     */
    @Deprecated
    public Builder setErrorMessage(@Nullable CharSequence errorMessage) {
      this.errorMessage = errorMessage;
      return this;
    }

    /**
     * Set the error code with an optional user readable error message. This should be set when the
     * state is {@link PlaybackStateCompat#STATE_ERROR}.
     *
     * @param errorCode The errorCode to set.
     * @param errorMessage The user readable error message. Can be null.
     * @return this
     */
    public Builder setErrorMessage(@ErrorCode int errorCode, @Nullable CharSequence errorMessage) {
      this.errorCode = errorCode;
      this.errorMessage = errorMessage;
      return this;
    }

    /**
     * Set any custom extras to be included with the playback state.
     *
     * @param extras The extras to include.
     * @return this
     */
    public Builder setExtras(@Nullable Bundle extras) {
      this.extras = extras;
      return this;
    }

    /** Creates the playback state object. */
    public PlaybackStateCompat build() {
      return new PlaybackStateCompat(
          state,
          position,
          bufferedPosition,
          rate,
          actions,
          errorCode,
          errorMessage,
          updateTime,
          customActions,
          activeItemId,
          extras);
    }
  }

  @RequiresApi(22)
  private static class Api22Impl {
    private Api22Impl() {}

    @SuppressWarnings("argument.type.incompatible") // Platform class not annotated as nullable
    static void setExtras(PlaybackState.Builder builder, @Nullable Bundle extras) {
      builder.setExtras(extras);
    }

    @Nullable
    static Bundle getExtras(PlaybackState state) {
      return state.getExtras();
    }
  }
}
