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
package androidx.media3.session;

import static android.os.Build.VERSION.SDK_INT;
import static android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
import static android.view.KeyEvent.KEYCODE_MEDIA_NEXT;
import static android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
import static android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS;
import static android.view.KeyEvent.KEYCODE_MEDIA_REWIND;
import static android.view.KeyEvent.KEYCODE_MEDIA_STOP;
import static android.view.KeyEvent.KEYCODE_UNKNOWN;
import static androidx.media3.common.Player.COMMAND_PLAY_PAUSE;
import static androidx.media3.common.Player.COMMAND_SEEK_BACK;
import static androidx.media3.common.Player.COMMAND_SEEK_FORWARD;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM;
import static androidx.media3.session.MediaSessionImpl.createSessionUri;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.Player.Command;
import androidx.media3.common.util.UnstableApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * A builder for creating a {@link PendingIntent} for a given {@link Player.Command} to be sent to a
 * {@link MediaSessionService}.
 *
 * <p>This builder is primarily intended for creating {@link PendingIntent} instances used in home
 * screen widgets (see <a
 * href="https://developer.android.com/develop/ui/views/appwidgets/overview">App widgets
 * overview</a>). For other use cases, such as interacting with a Media3 session service from within
 * an app, it is strongly recommended to use a {@link MediaController} or {@link MediaBrowser}
 * instead of a {@link PendingIntent}.
 */
@UnstableApi
public final class PlaybackPendingIntentBuilder {
  private final Context context;
  private final @Player.Command int command;
  private final Class<? extends MediaSessionService> serviceClass;
  private final int keyCode;
  private boolean startAsForegroundService;
  @Nullable private String sessionId;
  private Bundle extras;

  /**
   * Creates a builder for a {@link PendingIntent} for a given {@link Player.Command}.
   *
   * <p>Throws an {@link IllegalArgumentException} if the {@link Player.Command} is not in the set
   * of supported commands. Supported commands are:
   *
   * <ul>
   *   <li>{@link Player#COMMAND_PLAY_PAUSE}
   *   <li>{@link Player#COMMAND_SEEK_BACK}
   *   <li>{@link Player#COMMAND_SEEK_FORWARD}
   *   <li>{@link Player#COMMAND_SEEK_TO_NEXT_MEDIA_ITEM}
   *   <li>{@link Player#COMMAND_SEEK_TO_NEXT}
   *   <li>{@link Player#COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM}
   *   <li>{@link Player#COMMAND_SEEK_TO_PREVIOUS}
   *   <li>{@link Player#COMMAND_STOP}
   * </ul>
   *
   * @param context The context.
   * @param command The {@link Player.Command}.
   * @param serviceClass The class of the service to which the intent should be sent.
   * @throws IllegalArgumentException for unsupported {@linkplain Player.Command player commands}.
   */
  public PlaybackPendingIntentBuilder(
      Context context,
      @Player.Command int command,
      Class<? extends MediaSessionService> serviceClass) {
    this.context = context;
    this.command = command;
    keyCode = toKeyCode(command);
    checkArgument(isSupportedKeyCode(keyCode));
    this.serviceClass = serviceClass;
    startAsForegroundService = false;
    sessionId = null;
    extras = Bundle.EMPTY;
  }

  /**
   * Creates an {@link Intent} for a media button event.
   *
   * <p>Throws an {@link IllegalArgumentException} if the {@link Player.Command} is not in the set
   * of supported commands. Supported commands are:
   *
   * <ul>
   *   <li>{@link Player#COMMAND_PLAY_PAUSE}
   *   <li>{@link Player#COMMAND_SEEK_BACK}
   *   <li>{@link Player#COMMAND_SEEK_FORWARD}
   *   <li>{@link Player#COMMAND_SEEK_TO_NEXT_MEDIA_ITEM}
   *   <li>{@link Player#COMMAND_SEEK_TO_NEXT}
   *   <li>{@link Player#COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM}
   *   <li>{@link Player#COMMAND_SEEK_TO_PREVIOUS}
   *   <li>{@link Player#COMMAND_STOP}
   * </ul>
   *
   * @param context The context.
   * @param command The {@link Player.Command}.
   * @param extras The additional extras.
   * @param sessionId The ID of the session or null if the default session ID should be used.
   * @param serviceClass The class of the service to which the intent should be sent.
   * @return The created {@link Intent}.
   */
  public static Intent createMediaButtonIntent(
      Context context,
      @Command int command,
      @Nullable Bundle extras,
      @Nullable String sessionId,
      Class<? extends MediaSessionService> serviceClass) {
    return createMediaButtonIntentInternal(
        context, toKeyCode(command), extras, sessionId, serviceClass);
  }

  /**
   * Sets whether the service should be started into the foreground.
   *
   * <p>This is usually the case for the {@link Player#COMMAND_PLAY_PAUSE} command only, hence when
   * {@link Player#getPlayWhenReady()} is false. The default value is {@code false}.
   *
   * @param startAsForegroundService Whether the service should be started into the foreground.
   * @return This builder.
   */
  @CanIgnoreReturnValue
  public PlaybackPendingIntentBuilder setStartAsForegroundService(
      boolean startAsForegroundService) {
    this.startAsForegroundService = startAsForegroundService;
    return this;
  }

  /**
   * Sets the ID of the session.
   *
   * <p>The default value is {@code null}, which which corresponds to the default ID used when *
   * {@link MediaSession.Builder#setId(String)} was not called when building the session.
   *
   * @param sessionId The ID of the session as set with {@link MediaSession.Builder#setId(String)}
   *     or null if the default ID was used when building the session.
   * @return This builder.
   */
  @CanIgnoreReturnValue
  public PlaybackPendingIntentBuilder setSessionId(@Nullable String sessionId) {
    this.sessionId = sessionId;
    return this;
  }

  /**
   * Sets the additional extras of the {@link Intent}.
   *
   * @param extras The additional extras.
   * @return This builder.
   */
  @CanIgnoreReturnValue
  public PlaybackPendingIntentBuilder setExtras(Bundle extras) {
    this.extras = checkNotNull(extras);
    return this;
  }

  /**
   * Builds the {@link PendingIntent}.
   *
   * <p>Two {@link PendingIntent} instances for the same command are {@linkplain
   * Intent#filterEquals(Intent) considered the same}. The key code of the command is used as the
   * request code.
   *
   * <p>The {@link PendingIntent} is created with the flags {@code PendingIntent.FLAG_UPDATE_CURRENT
   * | PendingIntent.FLAG_IMMUTABLE}.
   */
  @SuppressWarnings("PendingIntentMutability") // We can't use SaferPendingIntent
  public PendingIntent build() {
    return SDK_INT >= 26 && startAsForegroundService && command == COMMAND_PLAY_PAUSE
        ? PendingIntent.getForegroundService(
            context,
            /* requestCode= */ keyCode,
            createMediaButtonIntentInternal(context, keyCode, extras, sessionId, serviceClass),
            /* flags= */ PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)
        : PendingIntent.getService(
            context,
            /* requestCode= */ keyCode,
            createMediaButtonIntentInternal(context, keyCode, extras, sessionId, serviceClass),
            /* flags= */ PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  /**
   * Returns whether the given {@link Player.Command} is supported.
   *
   * @param command The {@link Player.Command}.
   * @return Whether the given {@link Player.Command} is supported.
   */
  public static boolean isCommandSupported(@Command int command) {
    return isSupportedKeyCode(toKeyCode(command));
  }

  private static boolean isSupportedKeyCode(int keyCode) {
    return keyCode == KEYCODE_MEDIA_NEXT
        || keyCode == KEYCODE_MEDIA_PREVIOUS
        || keyCode == KEYCODE_MEDIA_STOP
        || keyCode == KEYCODE_MEDIA_FAST_FORWARD
        || keyCode == KEYCODE_MEDIA_REWIND
        || keyCode == KEYCODE_MEDIA_PLAY_PAUSE;
  }

  private static int toKeyCode(@Player.Command int command) {
    switch (command) {
      case COMMAND_PLAY_PAUSE:
        return KEYCODE_MEDIA_PLAY_PAUSE;
      case COMMAND_SEEK_FORWARD:
        return KEYCODE_MEDIA_FAST_FORWARD;
      case COMMAND_SEEK_BACK:
        return KEYCODE_MEDIA_REWIND;
      case COMMAND_SEEK_TO_NEXT_MEDIA_ITEM:
      case COMMAND_SEEK_TO_NEXT:
        return KEYCODE_MEDIA_NEXT;
      case COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM:
      case COMMAND_SEEK_TO_PREVIOUS:
        return KEYCODE_MEDIA_PREVIOUS;
      case Player.COMMAND_STOP:
        return KEYCODE_MEDIA_STOP;
      default:
        return KEYCODE_UNKNOWN;
    }
  }

  /**
   * Creates an {@link Intent} for a media button event.
   *
   * @param context The context.
   * @param keyCode The key code of the media button event.
   * @param extras The additional extras.
   * @param sessionId The ID of the session.
   * @param serviceClass The class of the service to which the intent should be sent.
   * @return The created {@link Intent}.
   */
  /* package */ static Intent createMediaButtonIntentInternal(
      Context context,
      int keyCode,
      @Nullable Bundle extras,
      @Nullable String sessionId,
      Class<? extends MediaSessionService> serviceClass) {
    checkArgument(isSupportedKeyCode(keyCode));
    Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
    intent.setData(createSessionUri(sessionId));
    intent.setComponent(new ComponentName(context, serviceClass));
    if (extras != null) {
      intent.putExtras(extras);
    }
    intent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
    return intent;
  }
}
