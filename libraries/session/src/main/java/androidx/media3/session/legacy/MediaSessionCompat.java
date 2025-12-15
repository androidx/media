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
import static androidx.media3.session.legacy.MediaSessionManager.RemoteUserInfo.LEGACY_CONTROLLER;
import static androidx.media3.session.legacy.MediaSessionManager.RemoteUserInfo.UNKNOWN_PID;
import static androidx.media3.session.legacy.MediaSessionManager.RemoteUserInfo.UNKNOWN_UID;
import static com.google.common.base.Preconditions.checkNotNull;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.Rating;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.BadParcelableException;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import androidx.annotation.GuardedBy;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.NullableType;
import androidx.media3.session.legacy.MediaSessionManager.RemoteUserInfo;
import androidx.versionedparcelable.ParcelUtils;
import androidx.versionedparcelable.VersionedParcelable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Allows interaction with media controllers, volume keys, media buttons, and transport controls.
 *
 * <p>A MediaSession should be created when an app wants to publish media playback information or
 * handle media keys. In general an app only needs one session for all playback, though multiple
 * sessions can be created to provide finer grain controls of media.
 *
 * <p>Once a session is created the owner of the session may pass its {@link #getSessionToken()
 * session token} to other processes to allow them to create a {@link MediaControllerCompat} to
 * interact with the session.
 *
 * <p>To receive commands, media keys, and other events a {@link Callback} must be set with {@link
 * #setCallback}.
 *
 * <p>When an app is finished performing playback it must call {@link #release()} to clean up the
 * session and notify any controllers.
 *
 * <p>MediaSessionCompat objects are not thread safe and all calls should be made from the same
 * thread.
 *
 * <p>This is a helper for accessing features in {@link android.media.session.MediaSession}
 * introduced after API level 4 in a backwards compatible fashion.
 *
 * <h2>Developer Guides</h2>
 *
 * <p>For information about building your media application, read the <a
 * href="{@docRoot}guide/topics/media-apps/index.html">Media Apps</a> developer guide.
 */
@RestrictTo(LIBRARY)
public class MediaSessionCompat {
  static final String TAG = "MediaSessionCompat";

  private final MediaSessionImpl impl;
  private final MediaControllerCompat controller;

  @IntDef(
      flag = true,
      value = {
        FLAG_HANDLES_MEDIA_BUTTONS,
        FLAG_HANDLES_TRANSPORT_CONTROLS,
        FLAG_HANDLES_QUEUE_COMMANDS
      })
  @Retention(RetentionPolicy.SOURCE)
  private @interface SessionFlags {}

  /**
   * Sets this flag on the session to indicate that it can handle media button events.
   *
   * @deprecated This flag is no longer used. All media sessions are expected to handle media button
   *     events now. For backward compatibility, this flag will be always set.
   */
  @SuppressLint("WrongConstant")
  @Deprecated
  public static final int FLAG_HANDLES_MEDIA_BUTTONS = 1 << 0;

  /**
   * Sets this flag on the session to indicate that it handles transport control commands through
   * its {@link Callback}.
   *
   * @deprecated This flag is no longer used. All media sessions are expected to handle transport
   *     controls now. For backward compatibility, this flag will be always set.
   */
  @SuppressLint("WrongConstant")
  @Deprecated
  public static final int FLAG_HANDLES_TRANSPORT_CONTROLS = 1 << 1;

  /**
   * Sets this flag on the session to indicate that it handles queue management commands through its
   * {@link Callback}.
   */
  @SuppressLint("WrongConstant")
  public static final int FLAG_HANDLES_QUEUE_COMMANDS = 1 << 2;

  /**
   * Predefined custom action to flag the media that is currently playing as inappropriate.
   *
   * @see Callback#onCustomAction
   */
  public static final String ACTION_FLAG_AS_INAPPROPRIATE =
      "android.support.v4.media.session.action.FLAG_AS_INAPPROPRIATE";

  /**
   * Predefined custom action to skip the advertisement that is currently playing.
   *
   * @see Callback#onCustomAction
   */
  public static final String ACTION_SKIP_AD = "android.support.v4.media.session.action.SKIP_AD";

  /**
   * Predefined custom action to follow an artist, album, or playlist. The extra bundle must have
   * {@link #ARGUMENT_MEDIA_ATTRIBUTE} to indicate the type of the follow action. The bundle can
   * also have an optional string argument, {@link #ARGUMENT_MEDIA_ATTRIBUTE_VALUE}, to specify the
   * target to follow (e.g., the name of the artist to follow). If this argument is omitted, the
   * currently playing media will be the target of the action. Thus, the session must perform the
   * follow action with the current metadata. If there's no specified attribute in the current
   * metadata, the controller must not omit this argument.
   *
   * @see #ARGUMENT_MEDIA_ATTRIBUTE
   * @see #ARGUMENT_MEDIA_ATTRIBUTE_VALUE
   * @see Callback#onCustomAction
   */
  public static final String ACTION_FOLLOW = "android.support.v4.media.session.action.FOLLOW";

  /**
   * Predefined custom action to unfollow an artist, album, or playlist. The extra bundle must have
   * {@link #ARGUMENT_MEDIA_ATTRIBUTE} to indicate the type of the unfollow action. The bundle can
   * also have an optional string argument, {@link #ARGUMENT_MEDIA_ATTRIBUTE_VALUE}, to specify the
   * target to unfollow (e.g., the name of the artist to unfollow). If this argument is omitted, the
   * currently playing media will be the target of the action. Thus, the session must perform the
   * unfollow action with the current metadata. If there's no specified attribute in the current
   * metadata, the controller must not omit this argument.
   *
   * @see #ARGUMENT_MEDIA_ATTRIBUTE
   * @see #ARGUMENT_MEDIA_ATTRIBUTE_VALUE
   * @see Callback#onCustomAction
   */
  public static final String ACTION_UNFOLLOW = "android.support.v4.media.session.action.UNFOLLOW";

  /** Argument to indicate the media attribute. */
  public static final String ARGUMENT_MEDIA_ATTRIBUTE =
      "android.support.v4.media.session.ARGUMENT_MEDIA_ATTRIBUTE";

  /**
   * String argument to indicate the value of the media attribute (e.g., the name of the artist).
   */
  public static final String ARGUMENT_MEDIA_ATTRIBUTE_VALUE =
      "android.support.v4.media.session.ARGUMENT_MEDIA_ATTRIBUTE_VALUE";

  /** Custom action to invoke playFromUri() for the forward compatibility. */
  public static final String ACTION_PLAY_FROM_URI =
      "android.support.v4.media.session.action.PLAY_FROM_URI";

  /** Custom action to invoke prepare() for the forward compatibility. */
  public static final String ACTION_PREPARE = "android.support.v4.media.session.action.PREPARE";

  /** Custom action to invoke prepareFromMediaId() for the forward compatibility. */
  public static final String ACTION_PREPARE_FROM_MEDIA_ID =
      "android.support.v4.media.session.action.PREPARE_FROM_MEDIA_ID";

  /** Custom action to invoke prepareFromSearch() for the forward compatibility. */
  public static final String ACTION_PREPARE_FROM_SEARCH =
      "android.support.v4.media.session.action.PREPARE_FROM_SEARCH";

  /** Custom action to invoke prepareFromUri() for the forward compatibility. */
  public static final String ACTION_PREPARE_FROM_URI =
      "android.support.v4.media.session.action.PREPARE_FROM_URI";

  /** Custom action to invoke setCaptioningEnabled() for the forward compatibility. */
  public static final String ACTION_SET_CAPTIONING_ENABLED =
      "android.support.v4.media.session.action.SET_CAPTIONING_ENABLED";

  /** Custom action to invoke setRepeatMode() for the forward compatibility. */
  public static final String ACTION_SET_REPEAT_MODE =
      "android.support.v4.media.session.action.SET_REPEAT_MODE";

  /** Custom action to invoke setShuffleMode() for the forward compatibility. */
  public static final String ACTION_SET_SHUFFLE_MODE =
      "android.support.v4.media.session.action.SET_SHUFFLE_MODE";

  /** Custom action to invoke setRating() with extra fields. */
  public static final String ACTION_SET_RATING =
      "android.support.v4.media.session.action.SET_RATING";

  /** Custom action to invoke setPlaybackSpeed() with extra fields. */
  public static final String ACTION_SET_PLAYBACK_SPEED =
      "android.support.v4.media.session.action.SET_PLAYBACK_SPEED";

  /** Argument for use with {@link #ACTION_PREPARE_FROM_MEDIA_ID} indicating media id to play. */
  public static final String ACTION_ARGUMENT_MEDIA_ID =
      "android.support.v4.media.session.action.ARGUMENT_MEDIA_ID";

  /** Argument for use with {@link #ACTION_PREPARE_FROM_SEARCH} indicating search query. */
  public static final String ACTION_ARGUMENT_QUERY =
      "android.support.v4.media.session.action.ARGUMENT_QUERY";

  /**
   * Argument for use with {@link #ACTION_PREPARE_FROM_URI} and {@link #ACTION_PLAY_FROM_URI}
   * indicating URI to play.
   */
  public static final String ACTION_ARGUMENT_URI =
      "android.support.v4.media.session.action.ARGUMENT_URI";

  /** Argument for use with {@link #ACTION_SET_RATING} indicating the rate to be set. */
  public static final String ACTION_ARGUMENT_RATING =
      "android.support.v4.media.session.action.ARGUMENT_RATING";

  /** Argument for use with {@link #ACTION_SET_PLAYBACK_SPEED} indicating the speed to be set. */
  public static final String ACTION_ARGUMENT_PLAYBACK_SPEED =
      "android.support.v4.media.session.action.ARGUMENT_PLAYBACK_SPEED";

  /** Argument for use with various actions indicating extra bundle. */
  public static final String ACTION_ARGUMENT_EXTRAS =
      "android.support.v4.media.session.action.ARGUMENT_EXTRAS";

  /**
   * Argument for use with {@link #ACTION_SET_CAPTIONING_ENABLED} indicating whether captioning is
   * enabled.
   */
  public static final String ACTION_ARGUMENT_CAPTIONING_ENABLED =
      "android.support.v4.media.session.action.ARGUMENT_CAPTIONING_ENABLED";

  /** Argument for use with {@link #ACTION_SET_REPEAT_MODE} indicating repeat mode. */
  public static final String ACTION_ARGUMENT_REPEAT_MODE =
      "android.support.v4.media.session.action.ARGUMENT_REPEAT_MODE";

  /** Argument for use with {@link #ACTION_SET_SHUFFLE_MODE} indicating shuffle mode. */
  public static final String ACTION_ARGUMENT_SHUFFLE_MODE =
      "android.support.v4.media.session.action.ARGUMENT_SHUFFLE_MODE";

  /** */
  public static final String KEY_TOKEN = "android.support.v4.media.session.TOKEN";

  /** */
  public static final String KEY_EXTRA_BINDER = "android.support.v4.media.session.EXTRA_BINDER";

  /** */
  public static final String KEY_SESSION2_TOKEN = "android.support.v4.media.session.SESSION_TOKEN2";

  /**
   * Creates a new session with a specified media button receiver (a component name and/or a pending
   * intent). You must call {@link #release()} when finished with the session.
   *
   * <p>The session will automatically be registered with the system but will not be published until
   * {@link #setActive(boolean) setActive(true)} is called.
   *
   * <p>For API 20 or earlier, note that a media button receiver is required for handling {@link
   * Intent#ACTION_MEDIA_BUTTON}. This constructor will attempt to find an appropriate {@link
   * BroadcastReceiver} from your manifest if it's not specified. See {@link MediaButtonReceiver}
   * for more details. The {@code sessionInfo} can include additional unchanging information about
   * this session. For example, it can include the version of the application, or other app-specific
   * unchanging information.
   *
   * @param context The context to use to create the session.
   * @param tag A short name for debugging purposes.
   * @param mbrComponent The component name for your media button receiver.
   * @param mbrIntent The PendingIntent for your receiver component that handles media button
   *     events. This is optional and will be used on between {@link
   *     android.os.Build.VERSION_CODES#JELLY_BEAN_MR2} and {@link
   *     android.os.Build.VERSION_CODES#KITKAT_WATCH} instead of the component name.
   * @param sessionInfo A bundle for additional information about this session, or {@link
   *     Bundle#EMPTY} if none. Controllers can get this information by calling {@link
   *     MediaControllerCompat#getSessionInfo()}. An {@link IllegalArgumentException} will be thrown
   *     if this contains any non-framework Parcelable objects.
   */
  @SuppressWarnings({
    "method.invocation.invalid",
    "argument.type.incompatible",
    "assignment.type.incompatible"
  }) // registering listener from constructor
  public MediaSessionCompat(
      Context context,
      String tag,
      @Nullable ComponentName mbrComponent,
      @Nullable PendingIntent mbrIntent,
      @Nullable Bundle sessionInfo) {
    if (TextUtils.isEmpty(tag)) {
      throw new IllegalArgumentException("tag must not be null or empty");
    }

    if (mbrComponent == null) {
      mbrComponent = MediaButtonReceiver.getMediaButtonReceiverComponent(context);
      if (mbrComponent == null) {
        Log.i(TAG, "Couldn't find a unique registered media button receiver in the given context.");
      }
    }
    if (mbrComponent != null && mbrIntent == null) {
      // construct a PendingIntent for the media button
      Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
      // the associated intent will be handled by the component being registered
      mediaButtonIntent.setComponent(mbrComponent);
      mbrIntent =
          PendingIntent.getBroadcast(
              context,
              0 /* requestCode, ignored */,
              mediaButtonIntent,
              Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
    }

    if (Build.VERSION.SDK_INT >= 29) {
      impl = new MediaSessionImplApi29(context, tag, sessionInfo);
    } else if (Build.VERSION.SDK_INT >= 28) {
      impl = new MediaSessionImplApi28(context, tag, sessionInfo);
    } else {
      impl = new MediaSessionImplApi23(context, tag, sessionInfo);
    }
    // Set default callback to respond to controllers' extra binder requests.
    Looper myLooper = Looper.myLooper();
    Handler handler = new Handler(myLooper != null ? myLooper : Looper.getMainLooper());
    setCallback(new Callback() {}, handler);
    impl.setMediaButtonReceiver(mbrIntent);

    controller = new MediaControllerCompat(context, this);
  }

  /**
   * Sets the callback to receive updates for the MediaSession. This includes media button and
   * volume events. Set the callback to null to stop receiving events.
   *
   * <p>Don't reuse the callback among the sessions. Callbacks keep internal reference to the
   * session when it's set, so it may misbehave.
   *
   * @param callback The callback to receive updates on.
   * @param handler The handler that events should be posted on.
   */
  public void setCallback(Callback callback, Handler handler) {
    impl.setCallback(callback, handler);
  }

  /**
   * Sets an intent for launching UI for this Session. This can be used as a quick link to an
   * ongoing media screen. The intent should be for an activity that may be started using {@link
   * Activity#startActivity(Intent)}.
   *
   * @param pi The intent to launch to show UI for this Session.
   */
  public void setSessionActivity(@Nullable PendingIntent pi) {
    impl.setSessionActivity(pi);
  }

  /**
   * Sets a pending intent for your media button receiver to allow restarting playback after the
   * session has been stopped. If your app is started in this way an {@link
   * Intent#ACTION_MEDIA_BUTTON} intent will be sent via the pending intent.
   *
   * <p>This method will only work on {@link android.os.Build.VERSION_CODES#LOLLIPOP} and later.
   * Earlier platform versions must include the media button receiver in the constructor.
   *
   * @param mbr The {@link PendingIntent} to send the media button event to.
   */
  public void setMediaButtonReceiver(PendingIntent mbr) {
    impl.setMediaButtonReceiver(mbr);
  }

  /**
   * Sets any flags for the session.
   *
   * @param flags The flags to set for this session.
   */
  public void setFlags(@SessionFlags int flags) {
    impl.setFlags(flags);
  }

  /**
   * Sets the stream this session is playing on. This will affect the system's volume handling for
   * this session. If {@link #setPlaybackToRemote} was previously called it will stop receiving
   * volume commands and the system will begin sending volume changes to the appropriate stream.
   *
   * <p>By default sessions are on {@link AudioManager#STREAM_MUSIC}.
   *
   * @param stream The {@link AudioManager} stream this session is playing on.
   */
  public void setPlaybackToLocal(int stream) {
    impl.setPlaybackToLocal(stream);
  }

  /**
   * Configures this session to use remote volume handling. This must be called to receive volume
   * button events, otherwise the system will adjust the current stream volume for this session. If
   * {@link #setPlaybackToLocal} was previously called that stream will stop receiving volume
   * changes for this session.
   *
   * <p>On platforms earlier than {@link android.os.Build.VERSION_CODES#LOLLIPOP} this will only
   * allow an app to handle volume commands sent directly to the session by a {@link
   * MediaControllerCompat}. System routing of volume keys will not use the volume provider.
   *
   * @param volumeProvider The provider that will handle volume changes. May not be null.
   */
  public void setPlaybackToRemote(VolumeProviderCompat volumeProvider) {
    impl.setPlaybackToRemote(volumeProvider);
  }

  /**
   * Sets if this session is currently active and ready to receive commands. If set to false your
   * session's controller may not be discoverable. You must set the session to active before it can
   * start receiving media button events or transport commands.
   *
   * <p>On platforms earlier than {@link android.os.Build.VERSION_CODES#LOLLIPOP}, a media button
   * event receiver should be set via the constructor to receive media button events.
   *
   * @param active Whether this session is active or not.
   */
  public void setActive(boolean active) {
    impl.setActive(active);
  }

  /**
   * Gets the current active state of this session.
   *
   * @return True if the session is active, false otherwise.
   */
  public boolean isActive() {
    return impl.isActive();
  }

  /**
   * Sends a proprietary event to all MediaControllers listening to this Session. It's up to the
   * Controller/Session owner to determine the meaning of any events.
   *
   * @param event The name of the event to send
   * @param extras Any extras included with the event
   */
  public void sendSessionEvent(String event, @Nullable Bundle extras) {
    if (TextUtils.isEmpty(event)) {
      throw new IllegalArgumentException("event cannot be null or empty");
    }
    impl.sendSessionEvent(event, extras);
  }

  /**
   * This must be called when an app has finished performing playback. If playback is expected to
   * start again shortly the session can be left open, but it must be released if your activity or
   * service is being destroyed.
   */
  public void release() {
    impl.release();
  }

  /**
   * Retrieves a token object that can be used by apps to create a {@link MediaControllerCompat} for
   * interacting with this session. The owner of the session is responsible for deciding how to
   * distribute these tokens.
   *
   * <p>On platform versions before {@link android.os.Build.VERSION_CODES#LOLLIPOP} this token may
   * only be used within your app as there is no way to guarantee other apps are using the same
   * version of the support library.
   *
   * @return A token that can be used to create a media controller for this session.
   */
  public Token getSessionToken() {
    return impl.getSessionToken();
  }

  /**
   * Gets a controller for this session. This is a convenience method to avoid having to cache your
   * own controller in process.
   *
   * @return A controller for this session.
   */
  public MediaControllerCompat getController() {
    return controller;
  }

  /**
   * Updates the current playback state.
   *
   * @param state The current state of playback
   */
  public void setPlaybackState(PlaybackStateCompat state) {
    impl.setPlaybackState(state);
  }

  /**
   * Updates the current metadata. New metadata can be created using {@link
   * androidx.media3.session.legacy.MediaMetadataCompat.Builder}. This operation may take time
   * proportional to the size of the bitmap to replace large bitmaps with a scaled down copy.
   *
   * @param metadata The new metadata
   * @see androidx.media3.session.legacy.MediaMetadataCompat.Builder#putBitmap
   */
  public void setMetadata(@Nullable MediaMetadataCompat metadata) {
    impl.setMetadata(metadata);
  }

  /**
   * Updates the list of items in the play queue. It is an ordered list and should contain the
   * current item, and previous or upcoming items if they exist. The id of each item should be
   * unique within the play queue. Specify null if there is no current play queue.
   *
   * <p>The queue should be of reasonable size. If the play queue is unbounded within your app, it
   * is better to send a reasonable amount in a sliding window instead.
   *
   * @param queue A list of items in the play queue.
   */
  public void setQueue(@Nullable List<QueueItem> queue) {
    if (queue != null) {
      Set<Long> set = new HashSet<>();
      for (QueueItem item : queue) {
        if (set.contains(item.getQueueId())) {
          Log.e(
              TAG,
              "Found duplicate queue id: " + item.getQueueId(),
              new IllegalArgumentException("id of each queue item should be unique"));
        }
        set.add(item.getQueueId());
      }
    }
    impl.setQueue(queue);
  }

  /**
   * Sets the title of the play queue. The UI should display this title along with the play queue
   * itself. e.g. "Play Queue", "Now Playing", or an album name.
   *
   * @param title The title of the play queue.
   */
  public void setQueueTitle(CharSequence title) {
    impl.setQueueTitle(title);
  }

  /**
   * Sets the style of rating used by this session. Apps trying to set the rating should use this
   * style. Must be one of the following:
   *
   * <ul>
   *   <li>{@link RatingCompat#RATING_NONE}
   *   <li>{@link RatingCompat#RATING_3_STARS}
   *   <li>{@link RatingCompat#RATING_4_STARS}
   *   <li>{@link RatingCompat#RATING_5_STARS}
   *   <li>{@link RatingCompat#RATING_HEART}
   *   <li>{@link RatingCompat#RATING_PERCENTAGE}
   *   <li>{@link RatingCompat#RATING_THUMB_UP_DOWN}
   * </ul>
   */
  public void setRatingType(@RatingCompat.Style int type) {
    impl.setRatingType(type);
  }

  /**
   * Sets the repeat mode for this session.
   *
   * <p>Note that if this method is not called before, {@link MediaControllerCompat#getRepeatMode}
   * will return {@link PlaybackStateCompat#REPEAT_MODE_NONE}.
   *
   * @param repeatMode The repeat mode. Must be one of the following: {@link
   *     PlaybackStateCompat#REPEAT_MODE_NONE}, {@link PlaybackStateCompat#REPEAT_MODE_ONE}, {@link
   *     PlaybackStateCompat#REPEAT_MODE_ALL}, {@link PlaybackStateCompat#REPEAT_MODE_GROUP}
   */
  public void setRepeatMode(@PlaybackStateCompat.RepeatMode int repeatMode) {
    impl.setRepeatMode(repeatMode);
  }

  /**
   * Sets the shuffle mode for this session.
   *
   * <p>Note that if this method is not called before, {@link MediaControllerCompat#getShuffleMode}
   * will return {@link PlaybackStateCompat#SHUFFLE_MODE_NONE}.
   *
   * @param shuffleMode The shuffle mode. Must be one of the following: {@link
   *     PlaybackStateCompat#SHUFFLE_MODE_NONE}, {@link PlaybackStateCompat#SHUFFLE_MODE_ALL},
   *     {@link PlaybackStateCompat#SHUFFLE_MODE_GROUP}
   */
  public void setShuffleMode(@PlaybackStateCompat.ShuffleMode int shuffleMode) {
    impl.setShuffleMode(shuffleMode);
  }

  /**
   * Sets some extras that can be associated with the {@link MediaSessionCompat}. No assumptions
   * should be made as to how a {@link MediaControllerCompat} will handle these extras. Keys should
   * be fully qualified (e.g. com.example.MY_EXTRA) to avoid conflicts.
   *
   * @param extras The extras associated with the session.
   */
  public void setExtras(@Nullable Bundle extras) {
    impl.setExtras(extras);
  }

  /**
   * Gets the underlying framework {@link android.media.session.MediaSession} object.
   *
   * <p>This method is only supported on API 21+.
   *
   * @return The underlying {@link android.media.session.MediaSession} object, or null if none.
   */
  @Nullable
  public Object getMediaSession() {
    return impl.getMediaSession();
  }

  /**
   * Gets the controller information who sent the current request.
   *
   * <p>Note: This is only valid while in a request callback, such as {@link Callback#onPlay}.
   *
   * <p>Note: From API 21 to 23, this method returns a fake {@link RemoteUserInfo} which has
   * following values:
   *
   * <ul>
   *   <li>Package name is {@link MediaSessionManager.RemoteUserInfo#LEGACY_CONTROLLER}.
   *   <li>PID and UID will have negative values.
   * </ul>
   *
   * <p>Note: From API 24 to 27, the {@link RemoteUserInfo} returned from this method will have
   * negative uid and pid. Most of the cases it will have the correct package name, but sometimes it
   * will fail to get the right one.
   *
   * @see MediaSessionManager.RemoteUserInfo#LEGACY_CONTROLLER
   * @see MediaSessionManager#isTrustedForMediaControl(RemoteUserInfo)
   */
  @Nullable
  public final RemoteUserInfo getCurrentControllerInfo() {
    return impl.getCurrentControllerInfo();
  }

  /** A helper method for setting the application class loader to the given {@link Bundle}. */
  public static void ensureClassLoader(@Nullable Bundle bundle) {
    if (bundle != null) {
      bundle.setClassLoader(checkNotNull(MediaSessionCompat.class.getClassLoader()));
    }
  }

  /**
   * Tries to unparcel the given {@link Bundle} with the application class loader and returns {@code
   * null} if a {@link BadParcelableException} is thrown while unparcelling, otherwise the given
   * bundle in which the application class loader is set.
   */
  @Nullable
  public static Bundle unparcelWithClassLoader(@Nullable Bundle bundle) {
    if (bundle == null) {
      return null;
    }
    ensureClassLoader(bundle);
    try {
      bundle.isEmpty(); // to call unparcel()
      return bundle;
    } catch (BadParcelableException e) {
      // The exception details will be logged by Parcel class.
      Log.e(TAG, "Could not unparcel the data.");
      return null;
    }
  }

  @Nullable
  @SuppressWarnings("WeakerAccess") /* synthetic access */
  static PlaybackStateCompat getStateWithUpdatedPosition(
      @Nullable PlaybackStateCompat state, @Nullable MediaMetadataCompat metadata) {
    if (state == null || state.getPosition() == PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN) {
      return state;
    }

    if (state.getState() == PlaybackStateCompat.STATE_PLAYING
        || state.getState() == PlaybackStateCompat.STATE_FAST_FORWARDING
        || state.getState() == PlaybackStateCompat.STATE_REWINDING) {
      long updateTime = state.getLastPositionUpdateTime();
      if (updateTime > 0) {
        long currentTime = SystemClock.elapsedRealtime();
        long position =
            (long) (state.getPlaybackSpeed() * (currentTime - updateTime)) + state.getPosition();
        long duration = -1;
        if (metadata != null && metadata.containsKey(MediaMetadataCompat.METADATA_KEY_DURATION)) {
          duration = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
        }

        if (duration >= 0 && position > duration) {
          position = duration;
        } else if (position < 0) {
          position = 0;
        }
        return new PlaybackStateCompat.Builder(state)
            .setState(state.getState(), position, state.getPlaybackSpeed(), currentTime)
            .build();
      }
    }
    return state;
  }

  /**
   * Receives transport controls, media buttons, and commands from controllers and the system. The
   * callback may be set using {@link #setCallback}.
   *
   * <p>Don't reuse the callback among the sessions. Callbacks keep internal reference to the
   * session when it's set, so it may misbehave.
   */
  public abstract static class Callback {
    final Object lock = new Object();
    final MediaSession.Callback callbackFwk;
    private boolean mediaPlayPausePendingOnHandler;

    @GuardedBy("lock")
    WeakReference<MediaSessionImpl> sessionImpl;

    @Nullable
    @GuardedBy("lock")
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    CallbackHandler callbackHandler;

    public Callback() {
      callbackFwk = new MediaSessionCallback();
      sessionImpl = new WeakReference<>(null);
    }

    void setSessionImpl(MediaSessionImpl impl, @Nullable Handler handler) {
      synchronized (lock) {
        sessionImpl = new WeakReference<>(impl);
        if (callbackHandler != null) {
          callbackHandler.removeCallbacksAndMessages(null);
        }
        callbackHandler = handler == null ? null : new CallbackHandler(handler.getLooper());
      }
    }

    /**
     * Called when a controller has sent a custom command to this session. The owner of the session
     * may handle custom commands but is not required to.
     *
     * @param command The command name.
     * @param extras Optional parameters for the command, may be null.
     * @param cb A result receiver to which a result may be sent by the command, may be null.
     */
    public void onCommand(String command, @Nullable Bundle extras, @Nullable ResultReceiver cb) {}

    /**
     * Override to handle media button events.
     *
     * <p>The double tap of {@link KeyEvent#KEYCODE_MEDIA_PLAY_PAUSE} or {@link
     * KeyEvent#KEYCODE_HEADSETHOOK} will call the {@link #onSkipToNext} by default. If the current
     * SDK level is 27 or higher, the default double tap handling is done by framework so this
     * method would do nothing for it.
     *
     * @param mediaButtonEvent The media button event intent.
     * @return True if the event was handled, false otherwise.
     */
    @SuppressWarnings("deprecation")
    public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
      if (android.os.Build.VERSION.SDK_INT >= 27) {
        // Double tap of play/pause as skipping to next is already handled by framework,
        // so we don't need to repeat again here.
        // Note: Double tap would be handled twice for OC-DR1 whose SDK version 26 and
        //       framework handles the double tap.
        return false;
      }
      MediaSessionImpl impl;
      Handler callbackHandler;
      synchronized (lock) {
        impl = sessionImpl.get();
        callbackHandler = this.callbackHandler;
      }
      if (impl == null || callbackHandler == null) {
        return false;
      }
      KeyEvent keyEvent = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
      if (keyEvent == null || keyEvent.getAction() != KeyEvent.ACTION_DOWN) {
        return false;
      }
      RemoteUserInfo remoteUserInfo = impl.getCurrentControllerInfo();
      int keyCode = keyEvent.getKeyCode();
      switch (keyCode) {
        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
        case KeyEvent.KEYCODE_HEADSETHOOK:
          if (keyEvent.getRepeatCount() == 0) {
            if (mediaPlayPausePendingOnHandler) {
              callbackHandler.removeMessages(
                  CallbackHandler.MSG_MEDIA_PLAY_PAUSE_KEY_DOUBLE_TAP_TIMEOUT);
              mediaPlayPausePendingOnHandler = false;
              PlaybackStateCompat state = impl.getPlaybackState();
              long validActions = state == null ? 0 : state.getActions();
              // Consider double tap as the next.
              if ((validActions & PlaybackStateCompat.ACTION_SKIP_TO_NEXT) != 0) {
                onSkipToNext();
              }
            } else {
              mediaPlayPausePendingOnHandler = true;
              callbackHandler.sendMessageDelayed(
                  callbackHandler.obtainMessage(
                      CallbackHandler.MSG_MEDIA_PLAY_PAUSE_KEY_DOUBLE_TAP_TIMEOUT, remoteUserInfo),
                  ViewConfiguration.getDoubleTapTimeout());
            }
          } else {
            // Consider long-press as a single tap.
            handleMediaPlayPauseIfPendingOnHandler(impl, callbackHandler);
          }
          return true;
        default:
          // If another key is pressed within double tap timeout, consider the pending
          // pending play/pause as a single tap to handle media keys in order.
          handleMediaPlayPauseIfPendingOnHandler(impl, callbackHandler);
          break;
      }
      return false;
    }

    void handleMediaPlayPauseIfPendingOnHandler(MediaSessionImpl impl, Handler callbackHandler) {
      if (!mediaPlayPausePendingOnHandler) {
        return;
      }
      mediaPlayPausePendingOnHandler = false;
      callbackHandler.removeMessages(CallbackHandler.MSG_MEDIA_PLAY_PAUSE_KEY_DOUBLE_TAP_TIMEOUT);
      PlaybackStateCompat state = impl.getPlaybackState();
      long validActions = state == null ? 0 : state.getActions();
      boolean isPlaying = state != null && state.getState() == PlaybackStateCompat.STATE_PLAYING;
      boolean canPlay =
          (validActions & (PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PLAY))
              != 0;
      boolean canPause =
          (validActions
                  & (PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_PAUSE))
              != 0;
      if (isPlaying && canPause) {
        onPause();
      } else if (!isPlaying && canPlay) {
        onPlay();
      }
    }

    /**
     * Override to handle requests to prepare playback. Override {@link #onPlay} to handle requests
     * for starting playback.
     */
    public void onPrepare() {}

    /**
     * Override to handle requests to prepare for playing a specific mediaId that was provided by
     * your app. Override {@link #onPlayFromMediaId} to handle requests for starting playback.
     */
    public void onPrepareFromMediaId(@Nullable String mediaId, @Nullable Bundle extras) {}

    /**
     * Override to handle requests to prepare playback from a search query. An empty query indicates
     * that the app may prepare any music. The implementation should attempt to make a smart choice
     * about what to play. Override {@link #onPlayFromSearch} to handle requests for starting
     * playback.
     */
    public void onPrepareFromSearch(@Nullable String query, @Nullable Bundle extras) {}

    /**
     * Override to handle requests to prepare a specific media item represented by a URI. Override
     * {@link #onPlayFromUri} to handle requests for starting playback.
     */
    public void onPrepareFromUri(@Nullable Uri uri, @Nullable Bundle extras) {}

    /** Override to handle requests to begin playback. */
    public void onPlay() {}

    /** Override to handle requests to play a specific mediaId that was provided by your app. */
    public void onPlayFromMediaId(@Nullable String mediaId, @Nullable Bundle extras) {}

    /**
     * Override to handle requests to begin playback from a search query. An empty query indicates
     * that the app may play any music. The implementation should attempt to make a smart choice
     * about what to play.
     */
    public void onPlayFromSearch(@Nullable String query, @Nullable Bundle extras) {}

    /** Override to handle requests to play a specific media item represented by a URI. */
    public void onPlayFromUri(@Nullable Uri uri, @Nullable Bundle extras) {}

    /** Override to handle requests to play an item with a given id from the play queue. */
    public void onSkipToQueueItem(long id) {}

    /** Override to handle requests to pause playback. */
    public void onPause() {}

    /** Override to handle requests to skip to the next media item. */
    public void onSkipToNext() {}

    /** Override to handle requests to skip to the previous media item. */
    public void onSkipToPrevious() {}

    /** Override to handle requests to fast forward. */
    public void onFastForward() {}

    /** Override to handle requests to rewind. */
    public void onRewind() {}

    /** Override to handle requests to stop playback. */
    public void onStop() {}

    /**
     * Override to handle requests to seek to a specific position in ms.
     *
     * @param pos New position to move to, in milliseconds.
     */
    public void onSeekTo(long pos) {}

    /**
     * Override to handle the item being rated.
     *
     * @param rating The rating being set.
     */
    public void onSetRating(@Nullable RatingCompat rating) {}

    /**
     * Override to handle the item being rated.
     *
     * @param rating The rating being set.
     * @param extras The extras can include information about the media item being rated.
     */
    public void onSetRating(@Nullable RatingCompat rating, @Nullable Bundle extras) {}

    /**
     * Override to handle the playback speed change. To update the new playback speed, create a new
     * {@link PlaybackStateCompat} by using {@link PlaybackStateCompat.Builder#setState(int, long,
     * float)}, and set it with {@link #setPlaybackState(PlaybackStateCompat)}.
     *
     * <p>A value of {@code 1.0f} is the default playback value, and a negative value indicates
     * reverse playback. The {@code speed} will not be equal to zero.
     *
     * @param speed the playback speed
     * @see #setPlaybackState(PlaybackStateCompat)
     * @see PlaybackStateCompat.Builder#setState(int, long, float)
     */
    public void onSetPlaybackSpeed(float speed) {}

    /**
     * Override to handle requests to enable/disable captioning.
     *
     * @param enabled {@code true} to enable captioning, {@code false} to disable.
     */
    public void onSetCaptioningEnabled(boolean enabled) {}

    /**
     * Override to handle the setting of the repeat mode.
     *
     * <p>You should call {@link #setRepeatMode} before end of this method in order to notify the
     * change to the {@link MediaControllerCompat}, or {@link MediaControllerCompat#getRepeatMode}
     * could return an invalid value.
     *
     * @param repeatMode The repeat mode which is one of followings: {@link
     *     PlaybackStateCompat#REPEAT_MODE_NONE}, {@link PlaybackStateCompat#REPEAT_MODE_ONE},
     *     {@link PlaybackStateCompat#REPEAT_MODE_ALL}, {@link
     *     PlaybackStateCompat#REPEAT_MODE_GROUP}
     */
    public void onSetRepeatMode(@PlaybackStateCompat.RepeatMode int repeatMode) {}

    /**
     * Override to handle the setting of the shuffle mode.
     *
     * <p>You should call {@link #setShuffleMode} before the end of this method in order to notify
     * the change to the {@link MediaControllerCompat}, or {@link
     * MediaControllerCompat#getShuffleMode} could return an invalid value.
     *
     * @param shuffleMode The shuffle mode which is one of followings: {@link
     *     PlaybackStateCompat#SHUFFLE_MODE_NONE}, {@link PlaybackStateCompat#SHUFFLE_MODE_ALL},
     *     {@link PlaybackStateCompat#SHUFFLE_MODE_GROUP}
     */
    public void onSetShuffleMode(@PlaybackStateCompat.ShuffleMode int shuffleMode) {}

    /**
     * Called when a {@link MediaControllerCompat} wants a {@link PlaybackStateCompat.CustomAction}
     * to be performed.
     *
     * @param action The action that was originally sent in the {@link
     *     PlaybackStateCompat.CustomAction}.
     * @param extras Optional extras specified by the {@link MediaControllerCompat}.
     * @see #ACTION_FLAG_AS_INAPPROPRIATE
     * @see #ACTION_SKIP_AD
     * @see #ACTION_FOLLOW
     * @see #ACTION_UNFOLLOW
     */
    public void onCustomAction(String action, @Nullable Bundle extras) {}

    /**
     * Called when a {@link MediaControllerCompat} wants to add a {@link QueueItem} with the given
     * {@link MediaDescriptionCompat description} at the end of the play queue.
     *
     * @param description The {@link MediaDescriptionCompat} for creating the {@link QueueItem} to
     *     be inserted.
     */
    public void onAddQueueItem(@Nullable MediaDescriptionCompat description) {}

    /**
     * Called when a {@link MediaControllerCompat} wants to add a {@link QueueItem} with the given
     * {@link MediaDescriptionCompat description} at the specified position in the play queue.
     *
     * @param description The {@link MediaDescriptionCompat} for creating the {@link QueueItem} to
     *     be inserted.
     * @param index The index at which the created {@link QueueItem} is to be inserted.
     */
    public void onAddQueueItem(@Nullable MediaDescriptionCompat description, int index) {}

    /**
     * Called when a {@link MediaControllerCompat} wants to remove the first occurrence of the
     * specified {@link QueueItem} with the given {@link MediaDescriptionCompat description} in the
     * play queue.
     *
     * @param description The {@link MediaDescriptionCompat} for denoting the {@link QueueItem} to
     *     be removed.
     */
    public void onRemoveQueueItem(@Nullable MediaDescriptionCompat description) {}

    private class CallbackHandler extends Handler {
      private static final int MSG_MEDIA_PLAY_PAUSE_KEY_DOUBLE_TAP_TIMEOUT = 1;

      CallbackHandler(Looper looper) {
        super(looper);
      }

      @Override
      public void handleMessage(Message msg) {
        if (msg.what == MSG_MEDIA_PLAY_PAUSE_KEY_DOUBLE_TAP_TIMEOUT) {
          // Here we manually set the caller info, since this is not directly called from
          // the session callback. This is triggered by timeout.
          MediaSessionImpl impl;
          Handler callbackHandler;
          synchronized (lock) {
            impl = sessionImpl.get();
            callbackHandler = MediaSessionCompat.Callback.this.callbackHandler;
          }
          if (impl == null
              || MediaSessionCompat.Callback.this != impl.getCallback()
              || callbackHandler == null) {
            return;
          }
          RemoteUserInfo info = (RemoteUserInfo) msg.obj;
          impl.setCurrentControllerInfo(info);
          handleMediaPlayPauseIfPendingOnHandler(impl, callbackHandler);
          impl.setCurrentControllerInfo(null);
        }
      }
    }

    private class MediaSessionCallback extends MediaSession.Callback {
      MediaSessionCallback() {}

      @Override
      public void onCommand(String command, @Nullable Bundle extras, @Nullable ResultReceiver cb) {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        ensureClassLoader(extras);
        setCurrentControllerInfo(sessionImpl);
        try {
          if (command.equals(MediaControllerCompat.COMMAND_GET_EXTRA_BINDER)) {
            if (cb != null) {
              Bundle result = new Bundle();
              Token token = sessionImpl.getSessionToken();
              IMediaSession extraBinder = token.getExtraBinder();
              result.putBinder(
                  KEY_EXTRA_BINDER, extraBinder == null ? null : extraBinder.asBinder());
              ParcelUtils.putVersionedParcelable(
                  result, KEY_SESSION2_TOKEN, token.getSession2Token());
              cb.send(0, result);
            }
          } else if (command.equals(MediaControllerCompat.COMMAND_ADD_QUEUE_ITEM)) {
            if (extras != null) {
              Callback.this.onAddQueueItem(
                  LegacyParcelableUtil.convert(
                      extras.getParcelable(
                          MediaControllerCompat.COMMAND_ARGUMENT_MEDIA_DESCRIPTION),
                      MediaDescriptionCompat.CREATOR));
            }
          } else if (command.equals(MediaControllerCompat.COMMAND_ADD_QUEUE_ITEM_AT)) {
            if (extras != null) {
              Callback.this.onAddQueueItem(
                  LegacyParcelableUtil.convert(
                      extras.getParcelable(
                          MediaControllerCompat.COMMAND_ARGUMENT_MEDIA_DESCRIPTION),
                      MediaDescriptionCompat.CREATOR),
                  extras.getInt(MediaControllerCompat.COMMAND_ARGUMENT_INDEX));
            }
          } else if (command.equals(MediaControllerCompat.COMMAND_REMOVE_QUEUE_ITEM)) {
            if (extras != null) {
              Callback.this.onRemoveQueueItem(
                  LegacyParcelableUtil.convert(
                      extras.getParcelable(
                          MediaControllerCompat.COMMAND_ARGUMENT_MEDIA_DESCRIPTION),
                      MediaDescriptionCompat.CREATOR));
            }
          } else if (command.equals(MediaControllerCompat.COMMAND_REMOVE_QUEUE_ITEM_AT)) {
            List<MediaSessionCompat.QueueItem> queue = sessionImpl.queue;
            if (queue != null && extras != null) {
              int index = extras.getInt(MediaControllerCompat.COMMAND_ARGUMENT_INDEX, -1);
              QueueItem item = (index >= 0 && index < queue.size()) ? queue.get(index) : null;
              if (item != null) {
                Callback.this.onRemoveQueueItem(item.getDescription());
              }
            }
          } else {
            Callback.this.onCommand(command, extras, cb);
          }
        } catch (BadParcelableException e) {
          // Do not print the exception here, since it is already done by the Parcel
          // class.
          Log.e(TAG, "Could not unparcel the extra data.");
        }
        clearCurrentControllerInfo(sessionImpl);
      }

      @Override
      public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return false;
        }
        setCurrentControllerInfo(sessionImpl);
        boolean result = Callback.this.onMediaButtonEvent(mediaButtonIntent);
        clearCurrentControllerInfo(sessionImpl);
        return result || super.onMediaButtonEvent(mediaButtonIntent);
      }

      @Override
      public void onPlay() {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        setCurrentControllerInfo(sessionImpl);
        Callback.this.onPlay();
        clearCurrentControllerInfo(sessionImpl);
      }

      @Override
      public void onPlayFromMediaId(String mediaId, @Nullable Bundle extras) {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        ensureClassLoader(extras);
        setCurrentControllerInfo(sessionImpl);
        Callback.this.onPlayFromMediaId(mediaId, extras);
        clearCurrentControllerInfo(sessionImpl);
      }

      @Override
      public void onPlayFromSearch(String search, @Nullable Bundle extras) {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        ensureClassLoader(extras);
        setCurrentControllerInfo(sessionImpl);
        Callback.this.onPlayFromSearch(search, extras);
        clearCurrentControllerInfo(sessionImpl);
      }

      @Override
      public void onPlayFromUri(Uri uri, @Nullable Bundle extras) {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        ensureClassLoader(extras);
        setCurrentControllerInfo(sessionImpl);
        Callback.this.onPlayFromUri(uri, extras);
        clearCurrentControllerInfo(sessionImpl);
      }

      @Override
      public void onSkipToQueueItem(long id) {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        setCurrentControllerInfo(sessionImpl);
        Callback.this.onSkipToQueueItem(id);
        clearCurrentControllerInfo(sessionImpl);
      }

      @Override
      public void onPause() {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        setCurrentControllerInfo(sessionImpl);
        Callback.this.onPause();
        clearCurrentControllerInfo(sessionImpl);
      }

      @Override
      public void onSkipToNext() {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        setCurrentControllerInfo(sessionImpl);
        Callback.this.onSkipToNext();
        clearCurrentControllerInfo(sessionImpl);
      }

      @Override
      public void onSkipToPrevious() {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        setCurrentControllerInfo(sessionImpl);
        Callback.this.onSkipToPrevious();
        clearCurrentControllerInfo(sessionImpl);
      }

      @Override
      public void onFastForward() {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        setCurrentControllerInfo(sessionImpl);
        Callback.this.onFastForward();
        clearCurrentControllerInfo(sessionImpl);
      }

      @Override
      public void onRewind() {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        setCurrentControllerInfo(sessionImpl);
        Callback.this.onRewind();
        clearCurrentControllerInfo(sessionImpl);
      }

      @Override
      public void onStop() {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        setCurrentControllerInfo(sessionImpl);
        Callback.this.onStop();
        clearCurrentControllerInfo(sessionImpl);
      }

      @Override
      public void onSeekTo(long pos) {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        setCurrentControllerInfo(sessionImpl);
        Callback.this.onSeekTo(pos);
        clearCurrentControllerInfo(sessionImpl);
      }

      @Override
      public void onSetRating(Rating ratingFwk) {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        setCurrentControllerInfo(sessionImpl);
        Callback.this.onSetRating(RatingCompat.fromRating(ratingFwk));
        clearCurrentControllerInfo(sessionImpl);
      }

      @Override
      public void onCustomAction(String action, @Nullable Bundle extras) {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        ensureClassLoader(extras);
        setCurrentControllerInfo(sessionImpl);

        try {
          if (action.equals(ACTION_PLAY_FROM_URI)) {
            if (extras != null) {
              Uri uri = extras.getParcelable(ACTION_ARGUMENT_URI);
              Bundle bundle = extras.getBundle(ACTION_ARGUMENT_EXTRAS);
              ensureClassLoader(bundle);
              Callback.this.onPlayFromUri(uri, bundle);
            }
          } else if (action.equals(ACTION_PREPARE)) {
            Callback.this.onPrepare();
          } else if (action.equals(ACTION_PREPARE_FROM_MEDIA_ID)) {
            if (extras != null) {
              String mediaId = extras.getString(ACTION_ARGUMENT_MEDIA_ID);
              Bundle bundle = extras.getBundle(ACTION_ARGUMENT_EXTRAS);
              ensureClassLoader(bundle);
              Callback.this.onPrepareFromMediaId(mediaId, bundle);
            }
          } else if (action.equals(ACTION_PREPARE_FROM_SEARCH)) {
            if (extras != null) {
              String query = extras.getString(ACTION_ARGUMENT_QUERY);
              Bundle bundle = extras.getBundle(ACTION_ARGUMENT_EXTRAS);
              ensureClassLoader(bundle);
              Callback.this.onPrepareFromSearch(query, bundle);
            }
          } else if (action.equals(ACTION_PREPARE_FROM_URI)) {
            if (extras != null) {
              Uri uri = extras.getParcelable(ACTION_ARGUMENT_URI);
              Bundle bundle = extras.getBundle(ACTION_ARGUMENT_EXTRAS);
              ensureClassLoader(bundle);
              Callback.this.onPrepareFromUri(uri, bundle);
            }
          } else if (action.equals(ACTION_SET_CAPTIONING_ENABLED)) {
            if (extras != null) {
              boolean enabled = extras.getBoolean(ACTION_ARGUMENT_CAPTIONING_ENABLED);
              Callback.this.onSetCaptioningEnabled(enabled);
            }
          } else if (action.equals(ACTION_SET_REPEAT_MODE)) {
            if (extras != null) {
              int repeatMode = extras.getInt(ACTION_ARGUMENT_REPEAT_MODE);
              Callback.this.onSetRepeatMode(repeatMode);
            }
          } else if (action.equals(ACTION_SET_SHUFFLE_MODE)) {
            if (extras != null) {
              int shuffleMode = extras.getInt(ACTION_ARGUMENT_SHUFFLE_MODE);
              Callback.this.onSetShuffleMode(shuffleMode);
            }
          } else if (action.equals(ACTION_SET_RATING)) {
            if (extras != null) {
              RatingCompat rating =
                  LegacyParcelableUtil.convert(
                      extras.getParcelable(ACTION_ARGUMENT_RATING), RatingCompat.CREATOR);
              Bundle bundle = extras.getBundle(ACTION_ARGUMENT_EXTRAS);
              ensureClassLoader(bundle);
              Callback.this.onSetRating(rating, bundle);
            }
          } else if (action.equals(ACTION_SET_PLAYBACK_SPEED)) {
            if (extras != null) {
              float speed = extras.getFloat(ACTION_ARGUMENT_PLAYBACK_SPEED, 1.0f);
              Callback.this.onSetPlaybackSpeed(speed);
            }
          } else {
            Callback.this.onCustomAction(action, extras);
          }
        } catch (BadParcelableException e) {
          // The exception details will be logged by Parcel class.
          Log.e(TAG, "Could not unparcel the data.");
        }
        clearCurrentControllerInfo(sessionImpl);
      }

      @RequiresApi(24)
      @Override
      public void onPrepare() {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        setCurrentControllerInfo(sessionImpl);
        Callback.this.onPrepare();
        clearCurrentControllerInfo(sessionImpl);
      }

      @RequiresApi(24)
      @Override
      public void onPrepareFromMediaId(@Nullable String mediaId, @Nullable Bundle extras) {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        ensureClassLoader(extras);
        setCurrentControllerInfo(sessionImpl);
        Callback.this.onPrepareFromMediaId(mediaId, extras);
        clearCurrentControllerInfo(sessionImpl);
      }

      @RequiresApi(24)
      @Override
      public void onPrepareFromSearch(@Nullable String query, @Nullable Bundle extras) {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        ensureClassLoader(extras);
        setCurrentControllerInfo(sessionImpl);
        Callback.this.onPrepareFromSearch(query, extras);
        clearCurrentControllerInfo(sessionImpl);
      }

      @RequiresApi(24)
      @Override
      public void onPrepareFromUri(@Nullable Uri uri, @Nullable Bundle extras) {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        ensureClassLoader(extras);
        setCurrentControllerInfo(sessionImpl);
        Callback.this.onPrepareFromUri(uri, extras);
        clearCurrentControllerInfo(sessionImpl);
      }

      @RequiresApi(29)
      @Override
      public void onSetPlaybackSpeed(float speed) {
        MediaSessionImplApi23 sessionImpl = getSessionImplIfCallbackIsSet();
        if (sessionImpl == null) {
          return;
        }
        setCurrentControllerInfo(sessionImpl);
        Callback.this.onSetPlaybackSpeed(speed);
        clearCurrentControllerInfo(sessionImpl);
      }

      private void setCurrentControllerInfo(MediaSessionImpl sessionImpl) {
        if (Build.VERSION.SDK_INT >= 28) {
          // From API 28, this method has no effect since
          // MediaSessionImplApi28#getCurrentControllerInfo() returns controller info from
          // framework.
          return;
        }
        String packageName = sessionImpl.getCallingPackage();
        if (TextUtils.isEmpty(packageName)) {
          packageName = LEGACY_CONTROLLER;
        }
        sessionImpl.setCurrentControllerInfo(
            new RemoteUserInfo(packageName, UNKNOWN_PID, UNKNOWN_UID));
      }

      private void clearCurrentControllerInfo(MediaSessionImpl sessionImpl) {
        sessionImpl.setCurrentControllerInfo(null);
      }

      // Returns the MediaSessionImplApi23 if this callback is still set by the session.
      // This prevent callback methods to be called after session is release() or
      // callback is changed.
      @Nullable
      private MediaSessionImplApi23 getSessionImplIfCallbackIsSet() {
        MediaSessionImplApi23 sessionImpl;
        synchronized (lock) {
          sessionImpl = (MediaSessionImplApi23) Callback.this.sessionImpl.get();
        }
        return sessionImpl != null && MediaSessionCompat.Callback.this == sessionImpl.getCallback()
            ? sessionImpl
            : null;
      }
    }
  }

  /** Callback to be called when a controller has registered or unregistered controller callback. */
  public interface RegistrationCallback {
    /**
     * Called when a {@link MediaControllerCompat} registered callback.
     *
     * @param callingPid PID from Binder#getCallingPid()
     * @param callingUid UID from Binder#getCallingUid()
     */
    void onCallbackRegistered(int callingPid, int callingUid);

    /**
     * Called when a {@link MediaControllerCompat} unregistered callback.
     *
     * @param callingPid PID from Binder#getCallingPid()
     * @param callingUid UID from Binder#getCallingUid()
     */
    void onCallbackUnregistered(int callingPid, int callingUid);
  }

  /**
   * Represents an ongoing session. This may be passed to apps by the session owner to allow them to
   * create a {@link MediaControllerCompat} to communicate with the session.
   */
  @SuppressLint("BanParcelableUsage")
  public static final class Token implements Parcelable {
    private final Object lock = new Object();
    private final MediaSession.Token inner;

    @Nullable
    @GuardedBy("lock")
    private IMediaSession extraBinder;

    @Nullable
    @GuardedBy("lock")
    private VersionedParcelable session2Token;

    Token(MediaSession.Token inner) {
      this(inner, /* extraBinder= */ null);
    }

    Token(MediaSession.Token inner, @Nullable IMediaSession extraBinder) {
      this(inner, extraBinder, /* session2Token= */ null);
    }

    Token(
        MediaSession.Token inner,
        @Nullable IMediaSession extraBinder,
        @Nullable VersionedParcelable session2Token) {
      this.inner = inner;
      this.extraBinder = extraBinder;
      this.session2Token = session2Token;
    }

    /**
     * Creates a compat Token from a framework {@link android.media.session.MediaSession.Token}
     * object.
     *
     * @param token The framework token object.
     * @return A compat Token for use with {@link MediaControllerCompat}.
     */
    public static Token fromToken(MediaSession.Token token) {
      return fromToken(token, null);
    }

    /**
     * Creates a compat Token from a framework {@link android.media.session.MediaSession.Token}
     * object, and the extra binder.
     *
     * @param token The framework token object.
     * @param extraBinder The extra binder.
     * @return A compat Token for use with {@link MediaControllerCompat}.
     */
    /* package */ static Token fromToken(
        MediaSession.Token token, @Nullable IMediaSession extraBinder) {
      return new Token(token, extraBinder);
    }

    @Override
    public int describeContents() {
      return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
      dest.writeParcelable(inner, flags);
    }

    @Override
    public int hashCode() {
      return inner.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof Token)) {
        return false;
      }

      Token other = (Token) obj;
      return inner.equals(other.inner);
    }

    /**
     * Gets the underlying framework {@link android.media.session.MediaSession.Token} object.
     *
     * @return The underlying {@link android.media.session.MediaSession.Token} object.
     */
    public MediaSession.Token getToken() {
      return inner;
    }

    @Nullable
    /* package */ IMediaSession getExtraBinder() {
      synchronized (lock) {
        return extraBinder;
      }
    }

    /* package */ void setExtraBinder(@Nullable IMediaSession extraBinder) {
      synchronized (lock) {
        this.extraBinder = extraBinder;
      }
    }

    /** */
    @Nullable
    public VersionedParcelable getSession2Token() {
      synchronized (lock) {
        return session2Token;
      }
    }

    /** */
    public void setSession2Token(@Nullable VersionedParcelable session2Token) {
      synchronized (lock) {
        this.session2Token = session2Token;
      }
    }

    /** */
    public Bundle toBundle() {
      Bundle bundle = new Bundle();
      bundle.putParcelable(
          KEY_TOKEN,
          LegacyParcelableUtil.convert(
              this, android.support.v4.media.session.MediaSessionCompat.Token.CREATOR));
      synchronized (lock) {
        if (extraBinder != null) {
          bundle.putBinder(KEY_EXTRA_BINDER, extraBinder.asBinder());
        }
        if (session2Token != null) {
          ParcelUtils.putVersionedParcelable(bundle, KEY_SESSION2_TOKEN, session2Token);
        }
      }
      return bundle;
    }

    /**
     * Creates a compat Token from a bundle object.
     *
     * @param tokenBundle The {@link Bundle} of this token.
     * @return A compat Token for use with {@link MediaControllerCompat}.
     */
    @Nullable
    public static Token fromBundle(@Nullable Bundle tokenBundle) {
      if (tokenBundle == null) {
        return null;
      }
      ensureClassLoader(tokenBundle);
      IMediaSession extraSession =
          IMediaSession.Stub.asInterface(tokenBundle.getBinder(KEY_EXTRA_BINDER));
      VersionedParcelable session2Token =
          ParcelUtils.getVersionedParcelable(tokenBundle, KEY_SESSION2_TOKEN);
      Token token = LegacyParcelableUtil.convert(tokenBundle.getParcelable(KEY_TOKEN), CREATOR);
      return token == null ? null : new Token(token.inner, extraSession, session2Token);
    }

    public static final Parcelable.Creator<Token> CREATOR =
        new Parcelable.Creator<Token>() {
          @Override
          public Token createFromParcel(Parcel in) {
            MediaSession.Token inner = in.readParcelable(null);
            return new Token(checkNotNull(inner));
          }

          @Override
          public Token[] newArray(int size) {
            return new Token[size];
          }
        };
  }

  /**
   * A single item that is part of the play queue. It contains a description of the item and its id
   * in the queue.
   */
  @SuppressLint("BanParcelableUsage")
  public static final class QueueItem implements Parcelable {
    /** This id is reserved. No items can be explicitly assigned this id. */
    public static final int UNKNOWN_ID = -1;

    private final MediaDescriptionCompat description;
    private final long id;

    @Nullable private MediaSession.QueueItem itemFwk;

    /**
     * Creates a new {@link MediaSessionCompat.QueueItem}.
     *
     * @param description The {@link MediaDescriptionCompat} for this item.
     * @param id An identifier for this item. It must be unique within the play queue and cannot be
     *     {@link #UNKNOWN_ID}.
     */
    public QueueItem(MediaDescriptionCompat description, long id) {
      this(null, description, id);
    }

    private QueueItem(
        @Nullable MediaSession.QueueItem queueItem, MediaDescriptionCompat description, long id) {
      if (id == UNKNOWN_ID) {
        throw new IllegalArgumentException("Id cannot be QueueItem.UNKNOWN_ID");
      }
      this.description = description;
      this.id = id;
      itemFwk = queueItem;
    }

    QueueItem(Parcel in) {
      description = MediaDescriptionCompat.CREATOR.createFromParcel(in);
      id = in.readLong();
    }

    /** Gets the description for this item. */
    public MediaDescriptionCompat getDescription() {
      return description;
    }

    /** Gets the queue id for this item. */
    public long getQueueId() {
      return id;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
      description.writeToParcel(dest, flags);
      dest.writeLong(id);
    }

    @Override
    public int describeContents() {
      return 0;
    }

    /**
     * Gets the underlying {@link android.media.session.MediaSession.QueueItem}.
     *
     * @return The underlying {@link android.media.session.MediaSession.QueueItem} or null.
     */
    public MediaSession.QueueItem getQueueItem() {
      if (itemFwk != null) {
        return itemFwk;
      }
      MediaDescription description = this.description.getMediaDescription();
      itemFwk = new MediaSession.QueueItem(description, id);
      return itemFwk;
    }

    /**
     * Creates an instance from a framework {@link android.media.session.MediaSession.QueueItem}
     * object.
     *
     * @param queueItem A {@link android.media.session.MediaSession.QueueItem} object.
     * @return An equivalent {@link QueueItem} object.
     */
    public static QueueItem fromQueueItem(MediaSession.QueueItem queueItem) {
      MediaDescription mediaDescription = queueItem.getDescription();
      MediaDescriptionCompat description =
          MediaDescriptionCompat.fromMediaDescription(mediaDescription);
      long id = queueItem.getQueueId();
      return new QueueItem(queueItem, description, id);
    }

    /**
     * Creates a list of {@link QueueItem} objects from a framework {@link
     * android.media.session.MediaSession.QueueItem} object list.
     *
     * <p>This method is only supported on API 21+. On API 20 and below, it returns null.
     *
     * @param itemList A list of {@link android.media.session.MediaSession.QueueItem} objects.
     * @return An equivalent list of {@link QueueItem} objects, or null if none.
     */
    @Nullable
    public static List<QueueItem> fromQueueItemList(
        @Nullable List<MediaSession.QueueItem> itemList) {
      if (itemList == null) {
        return null;
      }
      List<QueueItem> items = new ArrayList<>(itemList.size());
      for (MediaSession.QueueItem itemObj : itemList) {
        items.add(fromQueueItem(itemObj));
      }
      return items;
    }

    public static final Creator<MediaSessionCompat.QueueItem> CREATOR =
        new Creator<MediaSessionCompat.QueueItem>() {

          @Override
          public MediaSessionCompat.QueueItem createFromParcel(Parcel p) {
            return new MediaSessionCompat.QueueItem(p);
          }

          @Override
          public MediaSessionCompat.QueueItem[] newArray(int size) {
            return new MediaSessionCompat.QueueItem[size];
          }
        };

    @Override
    public String toString() {
      return "MediaSession.QueueItem { Description=" + description + ", Id=" + id + " }";
    }
  }

  /**
   * This is a wrapper for {@link ResultReceiver} for sending over aidl interfaces. The framework
   * version was not exposed to aidls until {@link android.os.Build.VERSION_CODES#LOLLIPOP}.
   */
  @SuppressLint("BanParcelableUsage")
  /* package */ static final class ResultReceiverWrapper implements Parcelable {
    ResultReceiver resultReceiver;

    ResultReceiverWrapper(Parcel in) {
      resultReceiver = ResultReceiver.CREATOR.createFromParcel(in);
    }

    public static final Creator<ResultReceiverWrapper> CREATOR =
        new Creator<ResultReceiverWrapper>() {
          @Override
          public ResultReceiverWrapper createFromParcel(Parcel p) {
            return new ResultReceiverWrapper(p);
          }

          @Override
          public ResultReceiverWrapper[] newArray(int size) {
            return new ResultReceiverWrapper[size];
          }
        };

    @Override
    public int describeContents() {
      return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
      resultReceiver.writeToParcel(dest, flags);
    }
  }

  interface MediaSessionImpl {
    void setCallback(@Nullable Callback callback, @Nullable Handler handler);

    void setFlags(@SessionFlags int flags);

    void setPlaybackToLocal(int stream);

    void setPlaybackToRemote(VolumeProviderCompat volumeProvider);

    void setActive(boolean active);

    boolean isActive();

    void sendSessionEvent(String event, @Nullable Bundle extras);

    void release();

    Token getSessionToken();

    void setPlaybackState(PlaybackStateCompat state);

    @Nullable
    PlaybackStateCompat getPlaybackState();

    void setMetadata(@Nullable MediaMetadataCompat metadata);

    void setSessionActivity(@Nullable PendingIntent pi);

    void setMediaButtonReceiver(@Nullable PendingIntent mbr);

    void setQueue(@Nullable List<QueueItem> queue);

    void setQueueTitle(CharSequence title);

    void setRatingType(@RatingCompat.Style int type);

    void setRepeatMode(@PlaybackStateCompat.RepeatMode int repeatMode);

    void setShuffleMode(@PlaybackStateCompat.ShuffleMode int shuffleMode);

    void setExtras(@Nullable Bundle extras);

    @Nullable
    Object getMediaSession();

    @Nullable
    String getCallingPackage();

    @Nullable
    RemoteUserInfo getCurrentControllerInfo();

    void setCurrentControllerInfo(@Nullable RemoteUserInfo remoteUserInfo);

    @Nullable
    Callback getCallback();
  }

  static class MediaSessionImplApi23 implements MediaSessionImpl {
    final MediaSession sessionFwk;
    final ExtraSession extraSession;
    final Token token;
    final Object lock = new Object();
    @Nullable Bundle sessionInfo;

    boolean destroyed = false;
    final RemoteCallbackList<IMediaControllerCallback> extraControllerCallbacks =
        new RemoteCallbackList<>();

    @Nullable PlaybackStateCompat playbackState;
    @Nullable List<QueueItem> queue;
    @Nullable MediaMetadataCompat metadata;
    boolean captioningEnabled;
    @PlaybackStateCompat.RepeatMode int repeatMode;
    @PlaybackStateCompat.ShuffleMode int shuffleMode;

    @Nullable
    @GuardedBy("lock")
    Callback callback;

    @Nullable
    @GuardedBy("lock")
    RegistrationCallbackHandler registrationCallbackHandler;

    @Nullable
    @GuardedBy("lock")
    RemoteUserInfo remoteUserInfo;

    // Sharing this in constructor
    @SuppressWarnings({
      "method.invocation.invalid",
      "assignment.type.incompatible",
      "argument.type.incompatible"
    })
    MediaSessionImplApi23(Context context, String tag, @Nullable Bundle sessionInfo) {
      sessionFwk = createFwkMediaSession(context, tag, sessionInfo);
      extraSession = new ExtraSession(/* mediaSessionImpl= */ this);
      token = new Token(sessionFwk.getSessionToken(), extraSession);
      this.sessionInfo = sessionInfo;
      // For backward compatibility, these flags are always set.
      setFlags(FLAG_HANDLES_MEDIA_BUTTONS | FLAG_HANDLES_TRANSPORT_CONTROLS);
    }

    public MediaSession createFwkMediaSession(
        Context context, String tag, @Nullable Bundle sessionInfo) {
      return new MediaSession(context, tag);
    }

    @Override
    public void setCallback(@Nullable Callback callback, @Nullable Handler handler) {
      synchronized (lock) {
        this.callback = callback;
        sessionFwk.setCallback(callback == null ? null : callback.callbackFwk, handler);
        if (callback != null) {
          callback.setSessionImpl(this, handler);
        }
      }
    }

    @SuppressLint("WrongConstant")
    @Override
    public void setFlags(@SessionFlags int flags) {
      // For backward compatibility, always set these deprecated flags.
      sessionFwk.setFlags(flags | FLAG_HANDLES_MEDIA_BUTTONS | FLAG_HANDLES_TRANSPORT_CONTROLS);
    }

    @Override
    public void setPlaybackToLocal(int stream) {
      // TODO update APIs to use support version of AudioAttributes
      AudioAttributes.Builder bob = new AudioAttributes.Builder();
      bob.setLegacyStreamType(stream);
      sessionFwk.setPlaybackToLocal(bob.build());
    }

    @Override
    public void setPlaybackToRemote(VolumeProviderCompat volumeProvider) {
      sessionFwk.setPlaybackToRemote((VolumeProvider) volumeProvider.getVolumeProvider());
    }

    @Override
    public void setActive(boolean active) {
      sessionFwk.setActive(active);
    }

    @Override
    public boolean isActive() {
      return sessionFwk.isActive();
    }

    @Override
    public void sendSessionEvent(String event, @Nullable Bundle extras) {
      sessionFwk.sendSessionEvent(event, extras);
    }

    @Override
    public void release() {
      destroyed = true;
      extraControllerCallbacks.kill();
      if (Build.VERSION.SDK_INT == 27) {
        // This is a workaround for framework MediaSession's bug in API 27.
        try {
          @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
          Field callback = sessionFwk.getClass().getDeclaredField("mCallback");
          callback.setAccessible(true);
          Handler handler = (Handler) callback.get(sessionFwk);
          if (handler != null) {
            handler.removeCallbacksAndMessages(null);
          }
        } catch (Exception e) {
          Log.w(TAG, "Exception happened while accessing MediaSession.mCallback.", e);
        }
      }
      // Prevent from receiving callbacks from released session.
      sessionFwk.setCallback(null);
      extraSession.release();
      sessionFwk.release();
    }

    @Override
    public Token getSessionToken() {
      return token;
    }

    @Override
    public void setPlaybackState(PlaybackStateCompat state) {
      playbackState = state;
      synchronized (lock) {
        int size = extraControllerCallbacks.beginBroadcast();
        for (int i = size - 1; i >= 0; i--) {
          IMediaControllerCallback cb = extraControllerCallbacks.getBroadcastItem(i);
          try {
            cb.onPlaybackStateChanged(state);
          } catch (RemoteException | SecurityException e) {
            Log.e(TAG, "Dead object in setPlaybackState.", e);
          }
        }
        extraControllerCallbacks.finishBroadcast();
      }
      sessionFwk.setPlaybackState(state.getPlaybackState());
    }

    @Nullable
    @Override
    public PlaybackStateCompat getPlaybackState() {
      return playbackState;
    }

    @Override
    public void setMetadata(@Nullable MediaMetadataCompat metadata) {
      this.metadata = metadata;
      sessionFwk.setMetadata(metadata == null ? null : metadata.getMediaMetadata());
    }

    @Override
    public void setSessionActivity(@Nullable PendingIntent pi) {
      sessionFwk.setSessionActivity(pi);
    }

    @Override
    public void setMediaButtonReceiver(@Nullable PendingIntent mbr) {
      sessionFwk.setMediaButtonReceiver(mbr);
    }

    @Override
    public void setQueue(@Nullable List<QueueItem> queue) {
      this.queue = queue;
      if (queue == null) {
        sessionFwk.setQueue(null);
        return;
      }
      ArrayList<MediaSession.QueueItem> queueItemFwks = new ArrayList<>(queue.size());
      for (QueueItem item : queue) {
        queueItemFwks.add(item.getQueueItem());
      }
      sessionFwk.setQueue(queueItemFwks);
    }

    @Override
    public void setQueueTitle(CharSequence title) {
      sessionFwk.setQueueTitle(title);
    }

    @Override
    public void setRatingType(@RatingCompat.Style int type) {
      sessionFwk.setRatingType(type);
    }

    @Override
    public void setRepeatMode(@PlaybackStateCompat.RepeatMode int repeatMode) {
      if (this.repeatMode != repeatMode) {
        this.repeatMode = repeatMode;
        synchronized (lock) {
          int size = extraControllerCallbacks.beginBroadcast();
          for (int i = size - 1; i >= 0; i--) {
            IMediaControllerCallback cb = extraControllerCallbacks.getBroadcastItem(i);
            try {
              cb.onRepeatModeChanged(repeatMode);
            } catch (RemoteException | SecurityException e) {
              Log.e(TAG, "Dead object in setRepeatMode.", e);
            }
          }
          extraControllerCallbacks.finishBroadcast();
        }
      }
    }

    @Override
    public void setShuffleMode(@PlaybackStateCompat.ShuffleMode int shuffleMode) {
      if (this.shuffleMode != shuffleMode) {
        this.shuffleMode = shuffleMode;
        synchronized (lock) {
          int size = extraControllerCallbacks.beginBroadcast();
          for (int i = size - 1; i >= 0; i--) {
            IMediaControllerCallback cb = extraControllerCallbacks.getBroadcastItem(i);
            try {
              cb.onShuffleModeChanged(shuffleMode);
            } catch (RemoteException | SecurityException e) {
              Log.e(TAG, "Dead object in setShuffleMode.", e);
            }
          }
          extraControllerCallbacks.finishBroadcast();
        }
      }
    }

    @Override
    public void setExtras(@Nullable Bundle extras) {
      sessionFwk.setExtras(extras);
    }

    @Nullable
    @Override
    public Object getMediaSession() {
      return sessionFwk;
    }

    @Override
    public void setCurrentControllerInfo(@Nullable RemoteUserInfo remoteUserInfo) {
      synchronized (lock) {
        this.remoteUserInfo = remoteUserInfo;
      }
    }

    @Nullable
    @Override
    public String getCallingPackage() {
      if (android.os.Build.VERSION.SDK_INT < 24) {
        return null;
      } else {
        try {
          Method getCallingPackageMethod = sessionFwk.getClass().getMethod("getCallingPackage");
          return (String) getCallingPackageMethod.invoke(sessionFwk);
        } catch (Exception e) {
          Log.e(TAG, "Cannot execute MediaSession.getCallingPackage()", e);
        }
        return null;
      }
    }

    @Nullable
    @Override
    public RemoteUserInfo getCurrentControllerInfo() {
      synchronized (lock) {
        return remoteUserInfo;
      }
    }

    @Nullable
    @Override
    public Callback getCallback() {
      synchronized (lock) {
        return callback;
      }
    }

    private static class ExtraSession extends IMediaSession.Stub {

      private final WeakReference<@NullableType MediaSessionImplApi23> mediaSessionImplRef;

      ExtraSession(MediaSessionImplApi23 mediaSessionImpl) {
        mediaSessionImplRef = new WeakReference<>(mediaSessionImpl);
      }

      /** Clears the reference to the containing component in order to enable garbage collection. */
      public void release() {
        mediaSessionImplRef.clear();
      }

      @Override
      public void registerCallbackListener(@Nullable IMediaControllerCallback cb) {
        MediaSessionImplApi23 mediaSessionImpl = mediaSessionImplRef.get();
        if (mediaSessionImpl == null || cb == null) {
          return;
        }
        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        RemoteUserInfo info =
            new RemoteUserInfo(RemoteUserInfo.LEGACY_CONTROLLER, callingPid, callingUid);
        mediaSessionImpl.extraControllerCallbacks.register(cb, info);
        synchronized (mediaSessionImpl.lock) {
          if (mediaSessionImpl.registrationCallbackHandler != null) {
            mediaSessionImpl.registrationCallbackHandler.postCallbackRegistered(
                callingPid, callingUid);
          }
        }
      }

      @Override
      public void unregisterCallbackListener(@Nullable IMediaControllerCallback cb) {
        MediaSessionImplApi23 mediaSessionImpl = mediaSessionImplRef.get();
        if (mediaSessionImpl == null || cb == null) {
          return;
        }
        mediaSessionImpl.extraControllerCallbacks.unregister(cb);

        int callingPid = Binder.getCallingPid();
        int callingUid = Binder.getCallingUid();
        synchronized (mediaSessionImpl.lock) {
          if (mediaSessionImpl.registrationCallbackHandler != null) {
            mediaSessionImpl.registrationCallbackHandler.postCallbackUnregistered(
                callingPid, callingUid);
          }
        }
      }

      @Nullable
      @Override
      public Bundle getSessionInfo() {
        MediaSessionImplApi23 mediaSessionImpl = mediaSessionImplRef.get();
        return mediaSessionImpl != null && mediaSessionImpl.sessionInfo != null
            ? new Bundle(mediaSessionImpl.sessionInfo)
            : null;
      }

      @Nullable
      @Override
      public PlaybackStateCompat getPlaybackState() {
        MediaSessionImplApi23 mediaSessionImpl = mediaSessionImplRef.get();
        if (mediaSessionImpl != null) {
          return getStateWithUpdatedPosition(
              mediaSessionImpl.playbackState, mediaSessionImpl.metadata);
        } else {
          return null;
        }
      }

      @Override
      public boolean isCaptioningEnabled() {
        MediaSessionImplApi23 mediaSessionImpl = mediaSessionImplRef.get();
        return mediaSessionImpl != null && mediaSessionImpl.captioningEnabled;
      }

      @Override
      @PlaybackStateCompat.RepeatMode
      public int getRepeatMode() {
        MediaSessionImplApi23 mediaSessionImpl = mediaSessionImplRef.get();
        return mediaSessionImpl != null
            ? mediaSessionImpl.repeatMode
            : PlaybackStateCompat.REPEAT_MODE_INVALID;
      }

      @Override
      @PlaybackStateCompat.ShuffleMode
      public int getShuffleMode() {
        MediaSessionImplApi23 mediaSessionImpl = mediaSessionImplRef.get();
        return mediaSessionImpl != null
            ? mediaSessionImpl.shuffleMode
            : PlaybackStateCompat.SHUFFLE_MODE_INVALID;
      }
    }
  }

  @RequiresApi(28)
  static class MediaSessionImplApi28 extends MediaSessionImplApi23 {
    MediaSessionImplApi28(Context context, String tag, @Nullable Bundle sessionInfo) {
      super(context, tag, sessionInfo);
    }

    @Override
    public void setCurrentControllerInfo(@Nullable RemoteUserInfo remoteUserInfo) {
      // No-op. {@link MediaSession#getCurrentControllerInfo} would work.
    }

    @Nullable
    @Override
    public final RemoteUserInfo getCurrentControllerInfo() {
      android.media.session.MediaSessionManager.RemoteUserInfo info =
          sessionFwk.getCurrentControllerInfo();
      return new RemoteUserInfo(info);
    }
  }

  @RequiresApi(29)
  static class MediaSessionImplApi29 extends MediaSessionImplApi28 {
    MediaSessionImplApi29(Context context, String tag, @Nullable Bundle sessionInfo) {
      super(context, tag, sessionInfo);
    }

    @Override
    public MediaSession createFwkMediaSession(
        Context context, String tag, @Nullable Bundle sessionInfo) {
      return new MediaSession(context, tag, sessionInfo);
    }
  }

  static final class RegistrationCallbackHandler extends Handler {
    private static final int MSG_CALLBACK_REGISTERED = 1001;
    private static final int MSG_CALLBACK_UNREGISTERED = 1002;

    private final RegistrationCallback callback;

    RegistrationCallbackHandler(Looper looper, RegistrationCallback callback) {
      super(looper);
      this.callback = callback;
    }

    @Override
    public void handleMessage(Message msg) {
      super.handleMessage(msg);
      switch (msg.what) {
        case MSG_CALLBACK_REGISTERED:
          callback.onCallbackRegistered(msg.arg1, msg.arg2);
          break;
        case MSG_CALLBACK_UNREGISTERED:
          callback.onCallbackUnregistered(msg.arg1, msg.arg2);
          break;
      }
    }

    public void postCallbackRegistered(int callingPid, int callingUid) {
      obtainMessage(MSG_CALLBACK_REGISTERED, callingPid, callingUid).sendToTarget();
    }

    public void postCallbackUnregistered(int callingPid, int callingUid) {
      obtainMessage(MSG_CALLBACK_UNREGISTERED, callingPid, callingUid).sendToTarget();
    }
  }
}
