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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.annotation.RestrictTo.Scope.LIBRARY;
import static androidx.media3.common.util.Util.convertToNullIfInvalid;
import static androidx.media3.session.legacy.MediaControllerCompat.PlaybackInfo.PLAYBACK_TYPE_LOCAL;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import android.app.PendingIntent;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.text.TextUtils;
import android.view.KeyEvent;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.util.Log;
import androidx.media3.session.legacy.MediaSessionCompat.QueueItem;
import androidx.media3.session.legacy.PlaybackStateCompat.CustomAction;
import androidx.versionedparcelable.ParcelUtils;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Allows an app to interact with an ongoing media session. Media buttons and other commands can be
 * sent to the session. A callback may be registered to receive updates from the session, such as
 * metadata and play state changes.
 *
 * <p>A MediaController can be created if you have a {@link MediaSessionCompat.Token} from the
 * session owner.
 *
 * <p>MediaController objects are thread-safe.
 *
 * <p>This is a helper for accessing features in {@link android.media.session.MediaSession}
 * introduced after API level 4 in a backwards compatible fashion.
 *
 * <p class="note">If MediaControllerCompat is created with a {@link MediaSessionCompat.Token
 * session token} from another process, following methods will not work directly after the creation
 * if the {@link MediaSessionCompat.Token session token} is not passed through a {@link
 * MediaBrowserCompat}:
 *
 * <ul>
 *   <li>{@link #getPlaybackState()}.{@link PlaybackStateCompat#getExtras() getExtras()}
 *   <li>{@link #getRatingType()}
 *   <li>{@link #getRepeatMode()}
 *   <li>{@link #getSessionInfo()}
 *   <li>{@link #getShuffleMode()}
 *   <li>{@link #isCaptioningEnabled()}
 * </ul>
 *
 * <div class="special reference">
 *
 * <h2>Developer Guides</h2>
 *
 * <p>For information about building your media application, read the <a
 * href="{@docRoot}guide/topics/media-apps/index.html">Media Apps</a> developer guide. </div>
 */
@RestrictTo(LIBRARY)
public final class MediaControllerCompat {
  static final String TAG = "MediaControllerCompat";

  /** */
  public static final String COMMAND_GET_EXTRA_BINDER =
      "android.support.v4.media.session.command.GET_EXTRA_BINDER";

  /** */
  public static final String COMMAND_ADD_QUEUE_ITEM =
      "android.support.v4.media.session.command.ADD_QUEUE_ITEM";

  /** */
  public static final String COMMAND_ADD_QUEUE_ITEM_AT =
      "android.support.v4.media.session.command.ADD_QUEUE_ITEM_AT";

  /** */
  public static final String COMMAND_REMOVE_QUEUE_ITEM =
      "android.support.v4.media.session.command.REMOVE_QUEUE_ITEM";

  /** */
  public static final String COMMAND_REMOVE_QUEUE_ITEM_AT =
      "android.support.v4.media.session.command.REMOVE_QUEUE_ITEM_AT";

  /** */
  public static final String COMMAND_ARGUMENT_MEDIA_DESCRIPTION =
      "android.support.v4.media.session.command.ARGUMENT_MEDIA_DESCRIPTION";

  /** */
  public static final String COMMAND_ARGUMENT_INDEX =
      "android.support.v4.media.session.command.ARGUMENT_INDEX";

  @SuppressWarnings("WeakerAccess") /* synthetic access */
  static void validateCustomAction(@Nullable String action, @Nullable Bundle args) {
    if (action == null) {
      return;
    }
    switch (action) {
      case MediaSessionCompat.ACTION_FOLLOW:
      case MediaSessionCompat.ACTION_UNFOLLOW:
        if (args == null || !args.containsKey(MediaSessionCompat.ARGUMENT_MEDIA_ATTRIBUTE)) {
          throw new IllegalArgumentException(
              "An extra field "
                  + MediaSessionCompat.ARGUMENT_MEDIA_ATTRIBUTE
                  + " is required "
                  + "for this action "
                  + action
                  + ".");
        }
        break;
    }
  }

  private final MediaControllerImpl impl;
  private final MediaSessionCompat.Token token;
  // This set is used to keep references to registered callbacks to prevent them being GCed,
  // since we only keep weak references for callbacks in this class and its inner classes.
  private final Set<Callback> registeredCallbacks;

  /**
   * Creates a media controller from a session.
   *
   * @param context A context.
   * @param session The session to be controlled.
   */
  public MediaControllerCompat(Context context, MediaSessionCompat session) {
    this(context, session.getSessionToken());
  }

  /**
   * Creates a media controller from a session token which may have been obtained from another
   * process.
   *
   * @param context A context.
   * @param sessionToken The token of the session to be controlled.
   */
  public MediaControllerCompat(Context context, MediaSessionCompat.Token sessionToken) {
    registeredCallbacks = Collections.synchronizedSet(new HashSet<>());
    token = sessionToken;

    if (Build.VERSION.SDK_INT >= 29) {
      impl = new MediaControllerImplApi29(context, sessionToken);
    } else {
      impl = new MediaControllerImplApi23(context, sessionToken);
    }
  }

  /**
   * Gets a {@link TransportControls} instance for this session.
   *
   * @return A controls instance
   */
  public TransportControls getTransportControls() {
    return impl.getTransportControls();
  }

  /**
   * Sends the specified media button event to the session. Only media keys can be sent by this
   * method, other keys will be ignored.
   *
   * @param keyEvent The media button event to dispatch.
   * @return true if the event was sent to the session, false otherwise.
   */
  public boolean dispatchMediaButtonEvent(@Nullable KeyEvent keyEvent) {
    if (keyEvent == null) {
      throw new IllegalArgumentException("KeyEvent may not be null");
    }
    return impl.dispatchMediaButtonEvent(keyEvent);
  }

  /**
   * Gets the current playback state for this session.
   *
   * <p>If the session is not ready, {@link PlaybackStateCompat#getExtras()} on the result of this
   * method may return null.
   *
   * @return The current PlaybackState or null
   * @see #isSessionReady
   * @see Callback#onSessionReady
   */
  @Nullable
  public PlaybackStateCompat getPlaybackState() {
    return impl.getPlaybackState();
  }

  /**
   * Gets the current metadata for this session.
   *
   * @return The current MediaMetadata or null.
   */
  @Nullable
  public MediaMetadataCompat getMetadata() {
    return impl.getMetadata();
  }

  /**
   * Gets the current play queue for this session if one is set. If you only care about the current
   * item {@link #getMetadata()} should be used.
   *
   * @return The current play queue or null.
   */
  @Nullable
  public List<QueueItem> getQueue() {
    return impl.getQueue();
  }

  /**
   * Adds a queue item from the given {@code description} at the end of the play queue of this
   * session. Not all sessions may support this. To know whether the session supports this, get the
   * session's flags with {@link #getFlags()} and check that the flag {@link
   * MediaSessionCompat#FLAG_HANDLES_QUEUE_COMMANDS} is set.
   *
   * @param description The {@link MediaDescriptionCompat} for creating the {@link
   *     MediaSessionCompat.QueueItem} to be inserted.
   * @throws UnsupportedOperationException If this session doesn't support this.
   * @see #getFlags()
   * @see MediaSessionCompat#FLAG_HANDLES_QUEUE_COMMANDS
   */
  public void addQueueItem(MediaDescriptionCompat description) {
    impl.addQueueItem(description);
  }

  /**
   * Adds a queue item from the given {@code description} at the specified position in the play
   * queue of this session. Shifts the queue item currently at that position (if any) and any
   * subsequent queue items to the right (adds one to their indices). Not all sessions may support
   * this. To know whether the session supports this, get the session's flags with {@link
   * #getFlags()} and check that the flag {@link MediaSessionCompat#FLAG_HANDLES_QUEUE_COMMANDS} is
   * set.
   *
   * @param description The {@link MediaDescriptionCompat} for creating the {@link
   *     MediaSessionCompat.QueueItem} to be inserted.
   * @param index The index at which the created {@link MediaSessionCompat.QueueItem} is to be
   *     inserted.
   * @throws UnsupportedOperationException If this session doesn't support this.
   * @see #getFlags()
   * @see MediaSessionCompat#FLAG_HANDLES_QUEUE_COMMANDS
   */
  public void addQueueItem(MediaDescriptionCompat description, int index) {
    impl.addQueueItem(description, index);
  }

  /**
   * Removes the first occurrence of the specified {@link MediaSessionCompat.QueueItem} with the
   * given {@link MediaDescriptionCompat description} in the play queue of the associated session.
   * Not all sessions may support this. To know whether the session supports this, get the session's
   * flags with {@link #getFlags()} and check that the flag {@link
   * MediaSessionCompat#FLAG_HANDLES_QUEUE_COMMANDS} is set.
   *
   * @param description The {@link MediaDescriptionCompat} for denoting the {@link
   *     MediaSessionCompat.QueueItem} to be removed.
   * @throws UnsupportedOperationException If this session doesn't support this.
   * @see #getFlags()
   * @see MediaSessionCompat#FLAG_HANDLES_QUEUE_COMMANDS
   */
  public void removeQueueItem(MediaDescriptionCompat description) {
    impl.removeQueueItem(description);
  }

  /**
   * Removes a queue item at the specified position in the play queue of this session. Not all
   * sessions may support this. To know whether the session supports this, get the session's flags
   * with {@link #getFlags()} and check that the flag {@link
   * MediaSessionCompat#FLAG_HANDLES_QUEUE_COMMANDS} is set.
   *
   * @param index The index of the element to be removed.
   * @throws UnsupportedOperationException If this session doesn't support this.
   * @see #getFlags()
   * @see MediaSessionCompat#FLAG_HANDLES_QUEUE_COMMANDS
   * @deprecated Use {@link #removeQueueItem(MediaDescriptionCompat)} instead.
   */
  @Deprecated
  public void removeQueueItemAt(int index) {
    List<QueueItem> queue = getQueue();
    if (queue != null && index >= 0 && index < queue.size()) {
      QueueItem item = queue.get(index);
      if (item != null) {
        removeQueueItem(item.getDescription());
      }
    }
  }

  /** Gets the queue title for this session. */
  @Nullable
  public CharSequence getQueueTitle() {
    return impl.getQueueTitle();
  }

  /** Gets the extras for this session. */
  @Nullable
  public Bundle getExtras() {
    return impl.getExtras();
  }

  /**
   * Gets the rating type supported by the session. One of:
   *
   * <ul>
   *   <li>{@link RatingCompat#RATING_NONE}
   *   <li>{@link RatingCompat#RATING_HEART}
   *   <li>{@link RatingCompat#RATING_THUMB_UP_DOWN}
   *   <li>{@link RatingCompat#RATING_3_STARS}
   *   <li>{@link RatingCompat#RATING_4_STARS}
   *   <li>{@link RatingCompat#RATING_5_STARS}
   *   <li>{@link RatingCompat#RATING_PERCENTAGE}
   * </ul>
   *
   * <p>If the session is not ready, it will return {@link RatingCompat#RATING_NONE}.
   *
   * @return The supported rating type, or {@link RatingCompat#RATING_NONE} if the value is not set
   *     or the session is not ready.
   * @see #isSessionReady
   * @see Callback#onSessionReady
   */
  public int getRatingType() {
    return impl.getRatingType();
  }

  /**
   * Returns whether captioning is enabled for this session.
   *
   * <p>If the session is not ready, it will return a {@code false}.
   *
   * @return {@code true} if captioning is enabled, {@code false} if disabled or not set.
   * @see #isSessionReady
   * @see Callback#onSessionReady
   */
  public boolean isCaptioningEnabled() {
    return impl.isCaptioningEnabled();
  }

  /**
   * Gets the repeat mode for this session.
   *
   * @return The latest repeat mode set to the session, {@link PlaybackStateCompat#REPEAT_MODE_NONE}
   *     if not set, or {@link PlaybackStateCompat#REPEAT_MODE_INVALID} if the session is not ready
   *     yet.
   * @see #isSessionReady
   * @see Callback#onSessionReady
   */
  public int getRepeatMode() {
    return impl.getRepeatMode();
  }

  /**
   * Gets the shuffle mode for this session.
   *
   * @return The latest shuffle mode set to the session, or {@link
   *     PlaybackStateCompat#SHUFFLE_MODE_NONE} if disabled or not set, or {@link
   *     PlaybackStateCompat#SHUFFLE_MODE_INVALID} if the session is not ready yet.
   * @see #isSessionReady
   * @see Callback#onSessionReady
   */
  public int getShuffleMode() {
    return impl.getShuffleMode();
  }

  /**
   * Gets the flags for this session. Flags are defined in {@link MediaSessionCompat}.
   *
   * @return The current set of flags for the session.
   */
  public long getFlags() {
    return impl.getFlags();
  }

  /**
   * Gets the current playback info for this session.
   *
   * @return The current playback info or null.
   */
  @Nullable
  public PlaybackInfo getPlaybackInfo() {
    return impl.getPlaybackInfo();
  }

  /**
   * Gets an intent for launching UI associated with this session if one exists.
   *
   * @return A {@link PendingIntent} to launch UI or null.
   */
  @Nullable
  public PendingIntent getSessionActivity() {
    return impl.getSessionActivity();
  }

  /**
   * Gets the token for the session that this controller is connected to.
   *
   * @return The session's token.
   */
  public MediaSessionCompat.Token getSessionToken() {
    return token;
  }

  /**
   * Sets the volume of the output this session is playing on. The command will be ignored if it
   * does not support {@link VolumeProviderCompat#VOLUME_CONTROL_ABSOLUTE}. The flags in {@link
   * AudioManager} may be used to affect the handling.
   *
   * @see #getPlaybackInfo()
   * @param value The value to set it to, between 0 and the reported max.
   * @param flags Flags from {@link AudioManager} to include with the volume request.
   */
  public void setVolumeTo(int value, int flags) {
    impl.setVolumeTo(value, flags);
  }

  /**
   * Adjusts the volume of the output this session is playing on. The direction must be one of
   * {@link AudioManager#ADJUST_LOWER}, {@link AudioManager#ADJUST_RAISE}, or {@link
   * AudioManager#ADJUST_SAME}. The command will be ignored if the session does not support {@link
   * VolumeProviderCompat#VOLUME_CONTROL_RELATIVE} or {@link
   * VolumeProviderCompat#VOLUME_CONTROL_ABSOLUTE}. The flags in {@link AudioManager} may be used to
   * affect the handling.
   *
   * @see #getPlaybackInfo()
   * @param direction The direction to adjust the volume in.
   * @param flags Any flags to pass with the command.
   */
  public void adjustVolume(int direction, int flags) {
    impl.adjustVolume(direction, flags);
  }

  /**
   * Adds a callback to receive updates from the session. Updates will be posted on the specified
   * handler's thread.
   *
   * @param callback The callback object, must not be null.
   * @param handler The handler to post updates on. If null the callers thread will be used.
   */
  public void registerCallback(Callback callback, @Nullable Handler handler) {
    if (!registeredCallbacks.add(callback)) {
      Log.w(TAG, "the callback has already been registered");
      return;
    }
    if (handler == null) {
      handler = new Handler();
    }
    callback.setHandler(handler);
    impl.registerCallback(callback, handler);
  }

  /**
   * Stops receiving updates on the specified callback. If an update has already been posted you may
   * still receive it after calling this method.
   *
   * @param callback The callback to remove
   */
  public void unregisterCallback(Callback callback) {
    if (!registeredCallbacks.remove(callback)) {
      Log.w(TAG, "the callback has never been registered");
      return;
    }
    try {
      impl.unregisterCallback(callback);
    } finally {
      callback.setHandler(null);
    }
  }

  /**
   * Sends a generic command to the session. It is up to the session creator to decide what commands
   * and parameters they will support. As such, commands should only be sent to sessions that the
   * controller owns.
   *
   * @param command The command to send
   * @param params Any parameters to include with the command. Can be {@code null}.
   * @param cb The callback to receive the result on. Can be {@code null}.
   */
  public void sendCommand(String command, @Nullable Bundle params, @Nullable ResultReceiver cb) {
    if (TextUtils.isEmpty(command)) {
      throw new IllegalArgumentException("command must neither be null nor empty");
    }
    impl.sendCommand(command, params, cb);
  }

  /**
   * Returns whether the session is ready or not.
   *
   * <p>If the session is not ready, following methods can work incorrectly.
   *
   * <ul>
   *   <li>{@link #getPlaybackState()}
   *   <li>{@link #getRatingType()}
   *   <li>{@link #getRepeatMode()}
   *   <li>{@link #getSessionInfo()}}
   *   <li>{@link #getShuffleMode()}
   *   <li>{@link #isCaptioningEnabled()}
   * </ul>
   *
   * @return true if the session is ready, false otherwise.
   * @see Callback#onSessionReady()
   */
  public boolean isSessionReady() {
    return impl.isSessionReady();
  }

  /**
   * Gets the session owner's package name.
   *
   * @return The package name of the session owner.
   */
  @Nullable
  public String getPackageName() {
    return impl.getPackageName();
  }

  /**
   * Gets the additional session information which was set when the session was created. The
   * returned {@link Bundle} can include additional unchanging information about the session. For
   * example, it can include the version of the session application, or other app-specific
   * unchanging information.
   *
   * @return The additional session information, or {@link Bundle#EMPTY} if the session didn't set
   *     the information or if the session is not ready.
   * @see #isSessionReady
   * @see Callback#onSessionReady
   */
  public Bundle getSessionInfo() {
    return impl.getSessionInfo();
  }

  /**
   * Gets the underlying framework {@link android.media.session.MediaController} object.
   *
   * <p>This method is only supported on API 21+.
   *
   * @return The underlying {@link android.media.session.MediaController} object, or null if none.
   */
  @Nullable
  public Object getMediaController() {
    return impl.getMediaController();
  }

  /**
   * Callback for receiving updates on from the session. A Callback can be registered using {@link
   * #registerCallback}
   */
  public abstract static class Callback implements IBinder.DeathRecipient {
    @Nullable final MediaController.Callback callbackFwk;
    @Nullable MessageHandler handler;
    @Nullable IMediaControllerCallback iControllerCallback;

    // Sharing this in constructor
    @SuppressWarnings({"assignment.type.incompatible", "argument.type.incompatible"})
    public Callback() {
      callbackFwk = new MediaControllerCallback(this);
    }

    /**
     * Override to handle the session being ready.
     *
     * @see MediaControllerCompat#isSessionReady
     */
    public void onSessionReady() {}

    /**
     * Override to handle the session being destroyed. The session is no longer valid after this
     * call and calls to it will be ignored.
     */
    public void onSessionDestroyed() {}

    /**
     * Override to handle custom events sent by the session owner without a specified interface.
     * Controllers should only handle these for sessions they own.
     *
     * @param event The event from the session.
     * @param extras Optional parameters for the event.
     */
    public void onSessionEvent(@Nullable String event, @Nullable Bundle extras) {}

    /**
     * Override to handle changes in playback state.
     *
     * @param state The new playback state of the session
     */
    public void onPlaybackStateChanged(@Nullable PlaybackStateCompat state) {}

    /**
     * Override to handle changes to the current metadata.
     *
     * @param metadata The current metadata for the session or null if none.
     * @see MediaMetadataCompat
     */
    public void onMetadataChanged(@Nullable MediaMetadataCompat metadata) {}

    /**
     * Override to handle changes to items in the queue.
     *
     * @see MediaSessionCompat.QueueItem
     * @param queue A list of items in the current play queue. It should include the currently
     *     playing item as well as previous and upcoming items if applicable.
     */
    public void onQueueChanged(@Nullable List<QueueItem> queue) {}

    /**
     * Override to handle changes to the queue title.
     *
     * @param title The title that should be displayed along with the play queue such as "Now
     *     Playing". May be null if there is no such title.
     */
    public void onQueueTitleChanged(@Nullable CharSequence title) {}

    /**
     * Override to handle changes to the {@link MediaSessionCompat} extras.
     *
     * @param extras The extras that can include other information associated with the {@link
     *     MediaSessionCompat}.
     */
    public void onExtrasChanged(@Nullable Bundle extras) {}

    /**
     * Override to handle changes to the audio info.
     *
     * @param info The current audio info for this session.
     */
    public void onAudioInfoChanged(@Nullable PlaybackInfo info) {}

    /**
     * Override to handle changes to the captioning enabled status.
     *
     * @param enabled {@code true} if captioning is enabled, {@code false} otherwise.
     */
    public void onCaptioningEnabledChanged(boolean enabled) {}

    /**
     * Override to handle changes to the repeat mode.
     *
     * @param repeatMode The repeat mode. It should be one of followings: {@link
     *     PlaybackStateCompat#REPEAT_MODE_NONE}, {@link PlaybackStateCompat#REPEAT_MODE_ONE},
     *     {@link PlaybackStateCompat#REPEAT_MODE_ALL}, {@link
     *     PlaybackStateCompat#REPEAT_MODE_GROUP}
     */
    public void onRepeatModeChanged(@PlaybackStateCompat.RepeatMode int repeatMode) {}

    /**
     * Override to handle changes to the shuffle mode.
     *
     * @param shuffleMode The shuffle mode. Must be one of the following: {@link
     *     PlaybackStateCompat#SHUFFLE_MODE_NONE}, {@link PlaybackStateCompat#SHUFFLE_MODE_ALL},
     *     {@link PlaybackStateCompat#SHUFFLE_MODE_GROUP}
     */
    public void onShuffleModeChanged(@PlaybackStateCompat.ShuffleMode int shuffleMode) {}

    @Override
    public void binderDied() {
      postToHandler(MessageHandler.MSG_DESTROYED, null, null);
    }

    /** Set the handler to use for callbacks. */
    void setHandler(@Nullable Handler handler) {
      if (handler == null) {
        if (this.handler != null) {
          this.handler.registered = false;
          this.handler.removeCallbacksAndMessages(null);
          this.handler = null;
        }
      } else {
        this.handler = new MessageHandler(handler.getLooper());
        this.handler.registered = true;
      }
    }

    void postToHandler(int what, @Nullable Object obj, @Nullable Bundle data) {
      if (handler != null) {
        Message msg = handler.obtainMessage(what, obj);
        if (data != null) {
          msg.setData(data);
        }
        msg.sendToTarget();
      }
    }

    // Callback methods in this class are run on handler which was given to registerCallback().
    private static class MediaControllerCallback extends MediaController.Callback {
      private final WeakReference<MediaControllerCompat.Callback> callback;

      MediaControllerCallback(MediaControllerCompat.Callback callback) {
        this.callback = new WeakReference<>(callback);
      }

      @Override
      public void onSessionDestroyed() {
        MediaControllerCompat.Callback callback = this.callback.get();
        if (callback != null) {
          callback.onSessionDestroyed();
        }
      }

      @Override
      public void onSessionEvent(String event, @Nullable Bundle extras) {
        extras = convertToNullIfInvalid(extras);
        MediaControllerCompat.Callback callback = this.callback.get();
        if (callback != null) {
          callback.onSessionEvent(event, extras);
        }
      }

      @Override
      public void onPlaybackStateChanged(@Nullable PlaybackState stateObj) {
        MediaControllerCompat.Callback callback = this.callback.get();
        if (callback != null) {
          if (callback.iControllerCallback != null) {
            // Ignore. ExtraCallback will handle this.
          } else {
            callback.onPlaybackStateChanged(PlaybackStateCompat.fromPlaybackState(stateObj));
          }
        }
      }

      @Override
      public void onMetadataChanged(@Nullable MediaMetadata metadataObj) {
        MediaControllerCompat.Callback callback = this.callback.get();
        if (callback != null) {
          callback.onMetadataChanged(MediaMetadataCompat.fromMediaMetadata(metadataObj));
        }
      }

      @Override
      public void onQueueChanged(@Nullable List<MediaSession.QueueItem> queue) {
        MediaControllerCompat.Callback callback = this.callback.get();
        if (callback != null) {
          callback.onQueueChanged(QueueItem.fromQueueItemList(queue));
        }
      }

      @Override
      public void onQueueTitleChanged(@Nullable CharSequence title) {
        MediaControllerCompat.Callback callback = this.callback.get();
        if (callback != null) {
          callback.onQueueTitleChanged(title);
        }
      }

      @Override
      public void onExtrasChanged(@Nullable Bundle extras) {
        extras = convertToNullIfInvalid(extras);
        MediaControllerCompat.Callback callback = this.callback.get();
        if (callback != null) {
          callback.onExtrasChanged(extras);
        }
      }

      @Override
      public void onAudioInfoChanged(@Nullable MediaController.PlaybackInfo info) {
        MediaControllerCompat.Callback callback = this.callback.get();
        if (callback != null && info != null) {
          int playbackType = info.getPlaybackType();
          String volumeControlId = SDK_INT >= 30 ? info.getVolumeControlId() : null;
          checkArgument(playbackType != PLAYBACK_TYPE_LOCAL || volumeControlId == null);
          callback.onAudioInfoChanged(
              new PlaybackInfo(
                  playbackType,
                  AudioAttributes.fromPlatformAudioAttributes(info.getAudioAttributes()),
                  info.getVolumeControl(),
                  info.getMaxVolume(),
                  info.getCurrentVolume(),
                  volumeControlId));
        }
      }
    }

    private static class CallbackStub extends IMediaControllerCallback.Stub {
      private final WeakReference<MediaControllerCompat.Callback> callback;

      CallbackStub(MediaControllerCompat.Callback callback) {
        this.callback = new WeakReference<>(callback);
      }

      @Override
      public void onPlaybackStateChanged(@Nullable PlaybackStateCompat state) {
        MediaControllerCompat.Callback callback = this.callback.get();
        if (callback != null) {
          callback.postToHandler(MessageHandler.MSG_UPDATE_PLAYBACK_STATE, state, null);
        }
      }

      @Override
      public void onCaptioningEnabledChanged(boolean enabled) {
        MediaControllerCompat.Callback callback = this.callback.get();
        if (callback != null) {
          callback.postToHandler(MessageHandler.MSG_UPDATE_CAPTIONING_ENABLED, enabled, null);
        }
      }

      @Override
      public void onRepeatModeChanged(int repeatMode) {
        MediaControllerCompat.Callback callback = this.callback.get();
        if (callback != null) {
          callback.postToHandler(MessageHandler.MSG_UPDATE_REPEAT_MODE, repeatMode, null);
        }
      }

      @Override
      public void onShuffleModeChanged(int shuffleMode) {
        MediaControllerCompat.Callback callback = this.callback.get();
        if (callback != null) {
          callback.postToHandler(MessageHandler.MSG_UPDATE_SHUFFLE_MODE, shuffleMode, null);
        }
      }

      @Override
      public void onSessionReady() {
        MediaControllerCompat.Callback callback = this.callback.get();
        if (callback != null) {
          callback.postToHandler(MessageHandler.MSG_SESSION_READY, null, null);
        }
      }
    }

    private class MessageHandler extends Handler {
      private static final int MSG_UPDATE_PLAYBACK_STATE = 2;
      private static final int MSG_DESTROYED = 8;
      private static final int MSG_UPDATE_REPEAT_MODE = 9;
      private static final int MSG_UPDATE_CAPTIONING_ENABLED = 11;
      private static final int MSG_UPDATE_SHUFFLE_MODE = 12;
      private static final int MSG_SESSION_READY = 13;

      boolean registered = false;

      MessageHandler(Looper looper) {
        super(looper);
      }

      @Override
      @SuppressWarnings("unchecked")
      public void handleMessage(Message msg) {
        if (!registered) {
          return;
        }
        switch (msg.what) {
          case MSG_UPDATE_PLAYBACK_STATE:
            onPlaybackStateChanged((PlaybackStateCompat) msg.obj);
            break;
          case MSG_UPDATE_CAPTIONING_ENABLED:
            onCaptioningEnabledChanged((boolean) msg.obj);
            break;
          case MSG_UPDATE_REPEAT_MODE:
            onRepeatModeChanged((int) msg.obj);
            break;
          case MSG_UPDATE_SHUFFLE_MODE:
            onShuffleModeChanged((int) msg.obj);
            break;
          case MSG_DESTROYED:
            onSessionDestroyed();
            break;
          case MSG_SESSION_READY:
            onSessionReady();
            break;
        }
      }
    }
  }

  /**
   * Interface for controlling media playback on a session. This allows an app to send media
   * transport commands to the session.
   */
  public abstract static class TransportControls {
    /**
     * Used as an integer extra field in {@link #playFromMediaId(String, Bundle)} or {@link
     * #prepareFromMediaId(String, Bundle)} to indicate the stream type to be used by the media
     * player when playing or preparing the specified media id. See {@link AudioManager} for a list
     * of stream types.
     *
     * @deprecated Use {@link MediaConstants#TRANSPORT_CONTROLS_EXTRAS_KEY_LEGACY_STREAM_TYPE}
     *     instead.
     */
    @Deprecated
    public static final String EXTRA_LEGACY_STREAM_TYPE =
        MediaConstants.TRANSPORT_CONTROLS_EXTRAS_KEY_LEGACY_STREAM_TYPE;

    TransportControls() {}

    /**
     * Request that the player prepare for playback. This can decrease the time it takes to start
     * playback when a play command is received. Preparation is not required. You can call {@link
     * #play} without calling this method beforehand.
     */
    public abstract void prepare();

    /**
     * Request that the player prepare playback for a specific media id. This can decrease the time
     * it takes to start playback when a play command is received. Preparation is not required. You
     * can call {@link #playFromMediaId} without calling this method beforehand.
     *
     * @param mediaId The id of the requested media.
     * @param extras Optional extras that can include extra information about the media item to be
     *     prepared.
     */
    public abstract void prepareFromMediaId(String mediaId, @Nullable Bundle extras);

    /**
     * Request that the player prepare playback for a specific search query. This can decrease the
     * time it takes to start playback when a play command is received. An empty or null query
     * should be treated as a request to prepare any music. Preparation is not required. You can
     * call {@link #playFromSearch} without calling this method beforehand.
     *
     * @param query The search query.
     * @param extras Optional extras that can include extra information about the query.
     */
    public abstract void prepareFromSearch(String query, @Nullable Bundle extras);

    /**
     * Request that the player prepare playback for a specific {@link Uri}. This can decrease the
     * time it takes to start playback when a play command is received. Preparation is not required.
     * You can call {@link #playFromUri} without calling this method beforehand.
     *
     * @param uri The URI of the requested media.
     * @param extras Optional extras that can include extra information about the media item to be
     *     prepared.
     */
    public abstract void prepareFromUri(Uri uri, @Nullable Bundle extras);

    /** Request that the player start its playback at its current position. */
    public abstract void play();

    /**
     * Request that the player start playback for a specific media id.
     *
     * @param mediaId The id of the requested media.
     * @param extras Optional extras that can include extra information about the media item to be
     *     played.
     */
    public abstract void playFromMediaId(String mediaId, @Nullable Bundle extras);

    /**
     * Request that the player start playback for a specific search query. An empty or null query
     * should be treated as a request to play any music.
     *
     * @param query The search query.
     * @param extras Optional extras that can include extra information about the query.
     */
    public abstract void playFromSearch(String query, @Nullable Bundle extras);

    /**
     * Request that the player start playback for a specific {@link Uri}.
     *
     * @param uri The URI of the requested media.
     * @param extras Optional extras that can include extra information about the media item to be
     *     played.
     */
    public abstract void playFromUri(Uri uri, @Nullable Bundle extras);

    /**
     * Plays an item with a specific id in the play queue. If you specify an id that is not in the
     * play queue, the behavior is undefined.
     */
    public abstract void skipToQueueItem(long id);

    /** Request that the player pause its playback and stay at its current position. */
    public abstract void pause();

    /**
     * Request that the player stop its playback; it may clear its state in whatever way is
     * appropriate.
     */
    public abstract void stop();

    /**
     * Moves to a new location in the media stream.
     *
     * @param pos Position to move to, in milliseconds.
     */
    public abstract void seekTo(long pos);

    /**
     * Starts fast forwarding. If playback is already fast forwarding this may increase the rate.
     */
    public abstract void fastForward();

    /** Skips to the next item. */
    public abstract void skipToNext();

    /** Starts rewinding. If playback is already rewinding this may increase the rate. */
    public abstract void rewind();

    /** Skips to the previous item. */
    public abstract void skipToPrevious();

    /**
     * Rates the current content. This will cause the rating to be set for the current user. The
     * rating type of the given {@link RatingCompat} must match the type returned by {@link
     * #getRatingType()}.
     *
     * @param rating The rating to set for the current content
     */
    public abstract void setRating(RatingCompat rating);

    /**
     * Rates a media item. This will cause the rating to be set for the specific media item. The
     * rating type of the given {@link RatingCompat} must match the type returned by {@link
     * #getRatingType()}.
     *
     * @param rating The rating to set for the media item.
     * @param extras Optional arguments that can include information about the media item to be
     *     rated.
     * @see MediaSessionCompat#ARGUMENT_MEDIA_ATTRIBUTE
     * @see MediaSessionCompat#ARGUMENT_MEDIA_ATTRIBUTE_VALUE
     */
    public abstract void setRating(RatingCompat rating, @Nullable Bundle extras);

    /**
     * Sets the playback speed. A value of {@code 1.0f} is the default playback value, and a
     * negative value indicates reverse playback. {@code 0.0f} is not allowed.
     *
     * @param speed The playback speed
     * @throws IllegalArgumentException if the {@code speed} is equal to zero.
     */
    public void setPlaybackSpeed(float speed) {}

    /**
     * Sets the repeat mode for this session.
     *
     * @param repeatMode The repeat mode. Must be one of the following: {@link
     *     PlaybackStateCompat#REPEAT_MODE_NONE}, {@link PlaybackStateCompat#REPEAT_MODE_ONE},
     *     {@link PlaybackStateCompat#REPEAT_MODE_ALL}, {@link
     *     PlaybackStateCompat#REPEAT_MODE_GROUP}
     */
    public abstract void setRepeatMode(@PlaybackStateCompat.RepeatMode int repeatMode);

    /**
     * Sets the shuffle mode for this session.
     *
     * @param shuffleMode The shuffle mode. Must be one of the following: {@link
     *     PlaybackStateCompat#SHUFFLE_MODE_NONE}, {@link PlaybackStateCompat#SHUFFLE_MODE_ALL},
     *     {@link PlaybackStateCompat#SHUFFLE_MODE_GROUP}
     */
    public abstract void setShuffleMode(@PlaybackStateCompat.ShuffleMode int shuffleMode);

    /**
     * Sends a custom action for the {@link MediaSessionCompat} to perform.
     *
     * @param customAction The action to perform.
     * @param args Optional arguments to supply to the {@link MediaSessionCompat} for this custom
     *     action.
     */
    public abstract void sendCustomAction(
        PlaybackStateCompat.CustomAction customAction, @Nullable Bundle args);

    /**
     * Sends the id and args from a custom action for the {@link MediaSessionCompat} to perform.
     *
     * @see #sendCustomAction(PlaybackStateCompat.CustomAction action, Bundle args)
     * @see MediaSessionCompat#ACTION_FLAG_AS_INAPPROPRIATE
     * @see MediaSessionCompat#ACTION_SKIP_AD
     * @see MediaSessionCompat#ACTION_FOLLOW
     * @see MediaSessionCompat#ACTION_UNFOLLOW
     * @param action The action identifier of the {@link PlaybackStateCompat.CustomAction} as
     *     specified by the {@link MediaSessionCompat}.
     * @param args Optional arguments to supply to the {@link MediaSessionCompat} for this custom
     *     action.
     */
    public abstract void sendCustomAction(String action, @Nullable Bundle args);
  }

  /** Holds information about the way volume is handled for this session. */
  public static final class PlaybackInfo {
    /** The session uses local playback. */
    public static final int PLAYBACK_TYPE_LOCAL = 1;

    /** The session uses remote playback. */
    public static final int PLAYBACK_TYPE_REMOTE = 2;

    private final int playbackType;
    private final AudioAttributes audioAttributes;
    private final int volumeControl;
    private final int maxVolume;
    private final int currentVolume;
    @Nullable private final String volumeControlId;

    PlaybackInfo(
        int type,
        AudioAttributes audioAttributes,
        int control,
        int max,
        int current,
        @Nullable String volumeControlId) {
      playbackType = type;
      this.audioAttributes = audioAttributes;
      volumeControl = control;
      maxVolume = max;
      currentVolume = current;
      this.volumeControlId = volumeControlId;
    }

    /**
     * Gets the type of volume handling, either local or remote. One of:
     *
     * <ul>
     *   <li>{@link PlaybackInfo#PLAYBACK_TYPE_LOCAL}
     *   <li>{@link PlaybackInfo#PLAYBACK_TYPE_REMOTE}
     * </ul>
     *
     * @return The type of volume handling this session is using.
     */
    public int getPlaybackType() {
      return playbackType;
    }

    /**
     * Get the audio attributes for this session. The attributes will affect volume handling for the
     * session. When the volume type is {@link PlaybackInfo#PLAYBACK_TYPE_REMOTE} these may be
     * ignored by the remote volume handler.
     *
     * @return The attributes for this session.
     */
    public AudioAttributes getAudioAttributes() {
      return audioAttributes;
    }

    /**
     * Gets the type of volume control that can be used. One of:
     *
     * <ul>
     *   <li>{@link VolumeProviderCompat#VOLUME_CONTROL_ABSOLUTE}
     *   <li>{@link VolumeProviderCompat#VOLUME_CONTROL_RELATIVE}
     *   <li>{@link VolumeProviderCompat#VOLUME_CONTROL_FIXED}
     * </ul>
     *
     * @return The type of volume control that may be used with this session.
     */
    public int getVolumeControl() {
      return volumeControl;
    }

    /**
     * Gets the maximum volume that may be set for this session.
     *
     * @return The maximum allowed volume where this session is playing.
     */
    public int getMaxVolume() {
      return maxVolume;
    }

    /**
     * Gets the current volume for this session.
     *
     * @return The current volume where this session is playing.
     */
    public int getCurrentVolume() {
      return currentVolume;
    }

    /**
     * Get the routing controller ID for this session. Returns null if unset, or if {@link
     * #getPlaybackType()} is {@link #PLAYBACK_TYPE_LOCAL}.
     */
    @Nullable
    public String getVolumeControlId() {
      return volumeControlId;
    }
  }

  interface MediaControllerImpl {
    void registerCallback(Callback callback, Handler handler);

    void unregisterCallback(Callback callback);

    boolean dispatchMediaButtonEvent(KeyEvent keyEvent);

    TransportControls getTransportControls();

    @Nullable
    PlaybackStateCompat getPlaybackState();

    @Nullable
    MediaMetadataCompat getMetadata();

    @Nullable
    List<QueueItem> getQueue();

    void addQueueItem(MediaDescriptionCompat description);

    void addQueueItem(MediaDescriptionCompat description, int index);

    void removeQueueItem(MediaDescriptionCompat description);

    @Nullable
    CharSequence getQueueTitle();

    @Nullable
    Bundle getExtras();

    int getRatingType();

    boolean isCaptioningEnabled();

    int getRepeatMode();

    int getShuffleMode();

    long getFlags();

    @Nullable
    PlaybackInfo getPlaybackInfo();

    @Nullable
    PendingIntent getSessionActivity();

    void setVolumeTo(int value, int flags);

    void adjustVolume(int direction, int flags);

    void sendCommand(String command, @Nullable Bundle params, @Nullable ResultReceiver cb);

    boolean isSessionReady();

    @Nullable
    String getPackageName();

    Bundle getSessionInfo();

    @Nullable
    Object getMediaController();
  }

  static class MediaControllerImplApi23 implements MediaControllerImpl {
    protected final MediaController controllerFwk;

    final Object lock = new Object();

    @GuardedBy("lock")
    private final List<Callback> pendingCallbacks = new ArrayList<>();

    private final HashMap<Callback, Callback.CallbackStub> callbackMap = new HashMap<>();

    @Nullable protected Bundle sessionInfo;

    final MediaSessionCompat.Token sessionToken;

    // Calling method from constructor
    @SuppressWarnings({"assignment.type.incompatible", "method.invocation.invalid"})
    MediaControllerImplApi23(Context context, MediaSessionCompat.Token sessionToken) {
      this.sessionToken = sessionToken;
      controllerFwk = new MediaController(context, this.sessionToken.getToken());
      if (this.sessionToken.getExtraBinder() == null) {
        requestExtraBinder();
      }
    }

    @Override
    public final void registerCallback(Callback callback, Handler handler) {
      controllerFwk.registerCallback(checkNotNull(callback.callbackFwk), handler);
      synchronized (lock) {
        IMediaSession extraBinder = sessionToken.getExtraBinder();
        if (extraBinder != null) {
          Callback.CallbackStub callbackStub = new Callback.CallbackStub(callback);
          callbackMap.put(callback, callbackStub);
          callback.iControllerCallback = callbackStub;
          try {
            extraBinder.registerCallbackListener(callbackStub);
            callback.postToHandler(Callback.MessageHandler.MSG_SESSION_READY, null, null);
          } catch (RemoteException | SecurityException e) {
            Log.e(TAG, "Dead object in registerCallback.", e);
          }
        } else {
          callback.iControllerCallback = null;
          pendingCallbacks.add(callback);
        }
      }
    }

    @Override
    public final void unregisterCallback(Callback callback) {
      controllerFwk.unregisterCallback(checkNotNull(callback.callbackFwk));
      synchronized (lock) {
        IMediaSession extraBinder = sessionToken.getExtraBinder();
        if (extraBinder != null) {
          try {
            Callback.CallbackStub callbackStub = callbackMap.remove(callback);
            if (callbackStub != null) {
              callback.iControllerCallback = null;
              extraBinder.unregisterCallbackListener(callbackStub);
            }
          } catch (RemoteException | SecurityException e) {
            Log.e(TAG, "Dead object in unregisterCallback.", e);
          }
        } else {
          pendingCallbacks.remove(callback);
        }
      }
    }

    @Override
    public boolean dispatchMediaButtonEvent(KeyEvent event) {
      return controllerFwk.dispatchMediaButtonEvent(event);
    }

    @Override
    public TransportControls getTransportControls() {
      MediaController.TransportControls controlsFwk = controllerFwk.getTransportControls();
      if (Build.VERSION.SDK_INT >= 29) {
        return new TransportControlsApi29(controlsFwk);
      } else if (Build.VERSION.SDK_INT >= 24) {
        return new TransportControlsApi24(controlsFwk);
      } else {
        return new TransportControlsApi23(controlsFwk);
      }
    }

    @Nullable
    @Override
    public PlaybackStateCompat getPlaybackState() {
      IMediaSession extraBinder = sessionToken.getExtraBinder();
      if (extraBinder != null) {
        try {
          return extraBinder.getPlaybackState();
        } catch (RemoteException | SecurityException e) {
          Log.e(TAG, "Dead object in getPlaybackState.", e);
        }
      }
      PlaybackState stateFwk = controllerFwk.getPlaybackState();
      return stateFwk != null ? PlaybackStateCompat.fromPlaybackState(stateFwk) : null;
    }

    @Nullable
    @Override
    public MediaMetadataCompat getMetadata() {
      MediaMetadata metadataFwk = controllerFwk.getMetadata();
      return metadataFwk != null ? MediaMetadataCompat.fromMediaMetadata(metadataFwk) : null;
    }

    @Nullable
    @Override
    public List<QueueItem> getQueue() {
      List<MediaSession.QueueItem> queueFwks = controllerFwk.getQueue();
      return queueFwks != null ? QueueItem.fromQueueItemList(queueFwks) : null;
    }

    @Override
    public void addQueueItem(MediaDescriptionCompat description) {
      long flags = getFlags();
      if ((flags & MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS) == 0) {
        throw new UnsupportedOperationException(
            "This session doesn't support queue management operations");
      }
      Bundle params = new Bundle();
      params.putParcelable(
          COMMAND_ARGUMENT_MEDIA_DESCRIPTION,
          LegacyParcelableUtil.convert(
              description, android.support.v4.media.MediaDescriptionCompat.CREATOR));
      sendCommand(COMMAND_ADD_QUEUE_ITEM, params, null);
    }

    @Override
    public void addQueueItem(MediaDescriptionCompat description, int index) {
      long flags = getFlags();
      if ((flags & MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS) == 0) {
        throw new UnsupportedOperationException(
            "This session doesn't support queue management operations");
      }
      Bundle params = new Bundle();
      params.putParcelable(
          COMMAND_ARGUMENT_MEDIA_DESCRIPTION,
          LegacyParcelableUtil.convert(
              description, android.support.v4.media.MediaDescriptionCompat.CREATOR));
      params.putInt(COMMAND_ARGUMENT_INDEX, index);
      sendCommand(COMMAND_ADD_QUEUE_ITEM_AT, params, null);
    }

    @Override
    public void removeQueueItem(MediaDescriptionCompat description) {
      long flags = getFlags();
      if ((flags & MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS) == 0) {
        throw new UnsupportedOperationException(
            "This session doesn't support queue management operations");
      }
      Bundle params = new Bundle();
      params.putParcelable(
          COMMAND_ARGUMENT_MEDIA_DESCRIPTION,
          LegacyParcelableUtil.convert(
              description, android.support.v4.media.MediaDescriptionCompat.CREATOR));
      sendCommand(COMMAND_REMOVE_QUEUE_ITEM, params, null);
    }

    @Nullable
    @Override
    public CharSequence getQueueTitle() {
      return controllerFwk.getQueueTitle();
    }

    @Nullable
    @Override
    public Bundle getExtras() {
      return convertToNullIfInvalid(controllerFwk.getExtras());
    }

    @Override
    public int getRatingType() {
      return controllerFwk.getRatingType();
    }

    @Override
    public boolean isCaptioningEnabled() {
      IMediaSession extraBinder = sessionToken.getExtraBinder();
      if (extraBinder != null) {
        try {
          return extraBinder.isCaptioningEnabled();
        } catch (RemoteException | SecurityException e) {
          Log.e(TAG, "Dead object in isCaptioningEnabled.", e);
        }
      }
      return false;
    }

    @Override
    public int getRepeatMode() {
      IMediaSession extraBinder = sessionToken.getExtraBinder();
      if (extraBinder != null) {
        try {
          return extraBinder.getRepeatMode();
        } catch (RemoteException | SecurityException e) {
          Log.e(TAG, "Dead object in getRepeatMode.", e);
        }
      }
      return PlaybackStateCompat.REPEAT_MODE_INVALID;
    }

    @Override
    public int getShuffleMode() {
      IMediaSession extraBinder = sessionToken.getExtraBinder();
      if (extraBinder != null) {
        try {
          return extraBinder.getShuffleMode();
        } catch (RemoteException | SecurityException e) {
          Log.e(TAG, "Dead object in getShuffleMode.", e);
        }
      }
      return PlaybackStateCompat.SHUFFLE_MODE_INVALID;
    }

    @Override
    public long getFlags() {
      return controllerFwk.getFlags();
    }

    @Nullable
    @Override
    public PlaybackInfo getPlaybackInfo() {
      MediaController.PlaybackInfo volumeInfoFwk = controllerFwk.getPlaybackInfo();
      return volumeInfoFwk != null
          ? new PlaybackInfo(
              volumeInfoFwk.getPlaybackType(),
              AudioAttributes.fromPlatformAudioAttributes(volumeInfoFwk.getAudioAttributes()),
              volumeInfoFwk.getVolumeControl(),
              volumeInfoFwk.getMaxVolume(),
              volumeInfoFwk.getCurrentVolume(),
              SDK_INT >= 30 ? volumeInfoFwk.getVolumeControlId() : null)
          : null;
    }

    @Nullable
    @Override
    public PendingIntent getSessionActivity() {
      return controllerFwk.getSessionActivity();
    }

    @Override
    public void setVolumeTo(int value, int flags) {
      controllerFwk.setVolumeTo(value, flags);
    }

    @Override
    public void adjustVolume(int direction, int flags) {
      controllerFwk.adjustVolume(direction, flags);
    }

    @Override
    public void sendCommand(String command, @Nullable Bundle params, @Nullable ResultReceiver cb) {
      controllerFwk.sendCommand(command, params, cb);
    }

    @Override
    public boolean isSessionReady() {
      return sessionToken.getExtraBinder() != null;
    }

    @Override
    public String getPackageName() {
      return controllerFwk.getPackageName();
    }

    @Override
    public Bundle getSessionInfo() {
      if (sessionInfo != null) {
        return new Bundle(sessionInfo);
      }

      IMediaSession extraBinder = sessionToken.getExtraBinder();
      if (extraBinder != null) {
        try {
          sessionInfo = extraBinder.getSessionInfo();
        } catch (RemoteException | SecurityException e) {
          Log.e(TAG, "Dead object in getSessionInfo.", e);
          sessionInfo = Bundle.EMPTY;
        }
      }

      sessionInfo = convertToNullIfInvalid(sessionInfo);
      return sessionInfo == null ? Bundle.EMPTY : new Bundle(sessionInfo);
    }

    @Nullable
    @Override
    public Object getMediaController() {
      return controllerFwk;
    }

    private void requestExtraBinder() {
      sendCommand(COMMAND_GET_EXTRA_BINDER, null, new ExtraBinderRequestResultReceiver(this));
    }

    @GuardedBy("lock")
    void processPendingCallbacksLocked() {
      IMediaSession extraBinder = sessionToken.getExtraBinder();
      if (extraBinder == null) {
        return;
      }
      for (Callback callback : pendingCallbacks) {
        Callback.CallbackStub callbackStub = new Callback.CallbackStub(callback);
        callbackMap.put(callback, callbackStub);
        callback.iControllerCallback = callbackStub;
        try {
          extraBinder.registerCallbackListener(callbackStub);
        } catch (RemoteException | SecurityException e) {
          Log.e(TAG, "Dead object in registerCallback.", e);
          break;
        }
        callback.postToHandler(Callback.MessageHandler.MSG_SESSION_READY, null, null);
      }
      pendingCallbacks.clear();
    }

    private static class ExtraBinderRequestResultReceiver extends ResultReceiver {
      private final WeakReference<MediaControllerImplApi23> mediaControllerImpl;

      ExtraBinderRequestResultReceiver(MediaControllerImplApi23 mediaControllerImpl) {
        super(null /* handler */);
        this.mediaControllerImpl = new WeakReference<>(mediaControllerImpl);
      }

      @Override
      protected void onReceiveResult(int resultCode, Bundle resultData) {
        MediaControllerImplApi23 mediaControllerImpl = this.mediaControllerImpl.get();
        if (mediaControllerImpl == null || resultData == null) {
          return;
        }
        synchronized (mediaControllerImpl.lock) {
          mediaControllerImpl.sessionToken.setExtraBinder(
              IMediaSession.Stub.asInterface(
                  resultData.getBinder(MediaSessionCompat.KEY_EXTRA_BINDER)));
          mediaControllerImpl.sessionToken.setSession2Token(
              ParcelUtils.getVersionedParcelable(
                  resultData, MediaSessionCompat.KEY_SESSION2_TOKEN));
          mediaControllerImpl.processPendingCallbacksLocked();
        }
      }
    }
  }

  @RequiresApi(29)
  static class MediaControllerImplApi29 extends MediaControllerImplApi23 {
    MediaControllerImplApi29(Context context, MediaSessionCompat.Token sessionToken) {
      super(context, sessionToken);
    }

    @Override
    public Bundle getSessionInfo() {
      if (sessionInfo != null) {
        return new Bundle(sessionInfo);
      }
      sessionInfo = controllerFwk.getSessionInfo();
      sessionInfo = convertToNullIfInvalid(sessionInfo);
      return sessionInfo == null ? Bundle.EMPTY : new Bundle(sessionInfo);
    }
  }

  static class TransportControlsApi23 extends TransportControls {
    protected final MediaController.TransportControls controlsFwk;

    TransportControlsApi23(MediaController.TransportControls controlsFwk) {
      this.controlsFwk = controlsFwk;
    }

    @Override
    public void prepare() {
      sendCustomAction(MediaSessionCompat.ACTION_PREPARE, null);
    }

    @Override
    public void prepareFromMediaId(String mediaId, @Nullable Bundle extras) {
      Bundle bundle = new Bundle();
      bundle.putString(MediaSessionCompat.ACTION_ARGUMENT_MEDIA_ID, mediaId);
      bundle.putBundle(MediaSessionCompat.ACTION_ARGUMENT_EXTRAS, extras);
      sendCustomAction(MediaSessionCompat.ACTION_PREPARE_FROM_MEDIA_ID, bundle);
    }

    @Override
    public void prepareFromSearch(String query, @Nullable Bundle extras) {
      Bundle bundle = new Bundle();
      bundle.putString(MediaSessionCompat.ACTION_ARGUMENT_QUERY, query);
      bundle.putBundle(MediaSessionCompat.ACTION_ARGUMENT_EXTRAS, extras);
      sendCustomAction(MediaSessionCompat.ACTION_PREPARE_FROM_SEARCH, bundle);
    }

    @Override
    public void prepareFromUri(Uri uri, @Nullable Bundle extras) {
      Bundle bundle = new Bundle();
      bundle.putParcelable(MediaSessionCompat.ACTION_ARGUMENT_URI, uri);
      bundle.putBundle(MediaSessionCompat.ACTION_ARGUMENT_EXTRAS, extras);
      sendCustomAction(MediaSessionCompat.ACTION_PREPARE_FROM_URI, bundle);
    }

    @Override
    public void play() {
      controlsFwk.play();
    }

    @Override
    public void pause() {
      controlsFwk.pause();
    }

    @Override
    public void stop() {
      controlsFwk.stop();
    }

    @Override
    public void seekTo(long pos) {
      controlsFwk.seekTo(pos);
    }

    @Override
    public void fastForward() {
      controlsFwk.fastForward();
    }

    @Override
    public void rewind() {
      controlsFwk.rewind();
    }

    @Override
    public void skipToNext() {
      controlsFwk.skipToNext();
    }

    @Override
    public void skipToPrevious() {
      controlsFwk.skipToPrevious();
    }

    @SuppressWarnings("argument.type.incompatible") // Platform controller accepts null rating
    @Override
    public void setRating(RatingCompat rating) {
      controlsFwk.setRating((Rating) rating.getRating());
    }

    @Override
    public void setRating(RatingCompat rating, @Nullable Bundle extras) {
      Bundle bundle = new Bundle();
      bundle.putParcelable(
          MediaSessionCompat.ACTION_ARGUMENT_RATING,
          LegacyParcelableUtil.convert(rating, android.support.v4.media.RatingCompat.CREATOR));
      bundle.putBundle(MediaSessionCompat.ACTION_ARGUMENT_EXTRAS, extras);
      sendCustomAction(MediaSessionCompat.ACTION_SET_RATING, bundle);
    }

    @Override
    public void setPlaybackSpeed(float speed) {
      if (speed == 0.0f) {
        throw new IllegalArgumentException("speed must not be zero");
      }
      Bundle bundle = new Bundle();
      bundle.putFloat(MediaSessionCompat.ACTION_ARGUMENT_PLAYBACK_SPEED, speed);
      sendCustomAction(MediaSessionCompat.ACTION_SET_PLAYBACK_SPEED, bundle);
    }

    @Override
    public void setRepeatMode(@PlaybackStateCompat.RepeatMode int repeatMode) {
      Bundle bundle = new Bundle();
      bundle.putInt(MediaSessionCompat.ACTION_ARGUMENT_REPEAT_MODE, repeatMode);
      sendCustomAction(MediaSessionCompat.ACTION_SET_REPEAT_MODE, bundle);
    }

    @Override
    public void setShuffleMode(@PlaybackStateCompat.ShuffleMode int shuffleMode) {
      Bundle bundle = new Bundle();
      bundle.putInt(MediaSessionCompat.ACTION_ARGUMENT_SHUFFLE_MODE, shuffleMode);
      sendCustomAction(MediaSessionCompat.ACTION_SET_SHUFFLE_MODE, bundle);
    }

    @SuppressWarnings("argument.type.incompatible") // Platform controller accepts null extras
    @Override
    public void playFromMediaId(String mediaId, @Nullable Bundle extras) {
      controlsFwk.playFromMediaId(mediaId, extras);
    }

    @SuppressWarnings("argument.type.incompatible") // Platform controller accepts null extras
    @Override
    public void playFromSearch(String query, @Nullable Bundle extras) {
      controlsFwk.playFromSearch(query, extras);
    }

    @SuppressWarnings("argument.type.incompatible") // Framework controller is missing annotation
    @Override
    public void playFromUri(Uri uri, @Nullable Bundle extras) {
      controlsFwk.playFromUri(uri, extras);
    }

    @Override
    public void skipToQueueItem(long id) {
      controlsFwk.skipToQueueItem(id);
    }

    @Override
    public void sendCustomAction(CustomAction customAction, @Nullable Bundle args) {
      validateCustomAction(customAction.getAction(), args);
      controlsFwk.sendCustomAction(customAction.getAction(), args);
    }

    @Override
    public void sendCustomAction(String action, @Nullable Bundle args) {
      validateCustomAction(action, args);
      controlsFwk.sendCustomAction(action, args);
    }
  }

  @RequiresApi(24)
  static class TransportControlsApi24 extends TransportControlsApi23 {
    TransportControlsApi24(MediaController.TransportControls controlsFwk) {
      super(controlsFwk);
    }

    @Override
    public void prepare() {
      controlsFwk.prepare();
    }

    @SuppressWarnings("argument.type.incompatible") // Framework controller is missing annotation
    @Override
    public void prepareFromMediaId(String mediaId, @Nullable Bundle extras) {
      controlsFwk.prepareFromMediaId(mediaId, extras);
    }

    @SuppressWarnings("argument.type.incompatible") // Platform controller accepts null extra
    @Override
    public void prepareFromSearch(String query, @Nullable Bundle extras) {
      controlsFwk.prepareFromSearch(query, extras);
    }

    @SuppressWarnings("argument.type.incompatible") // Platform controller accepts null extra
    @Override
    public void prepareFromUri(Uri uri, @Nullable Bundle extras) {
      controlsFwk.prepareFromUri(uri, extras);
    }
  }

  @RequiresApi(29)
  static class TransportControlsApi29 extends TransportControlsApi24 {
    TransportControlsApi29(MediaController.TransportControls controlsFwk) {
      super(controlsFwk);
    }

    @Override
    public void setPlaybackSpeed(float speed) {
      if (speed == 0.0f) {
        throw new IllegalArgumentException("speed must not be zero");
      }
      controlsFwk.setPlaybackSpeed(speed);
    }
  }
}
