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
 * limitations under the License.
 */
package androidx.media3.session;

import static androidx.media3.common.Player.COMMAND_STOP;
import static androidx.media3.session.CustomCommandPendingIntentBuilder.ACTION_CUSTOM;
import static androidx.media3.session.CustomCommandPendingIntentBuilder.EXTRAS_KEY_ACTION_CUSTOM;
import static androidx.media3.session.CustomCommandPendingIntentBuilder.EXTRAS_KEY_ACTION_CUSTOM_EXTRAS;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.media3.common.Player;

/** The default {@link MediaNotification.ActionFactory}. */
/* package */ final class DefaultActionFactory implements MediaNotification.ActionFactory {

  /**
   * Returns the {@link KeyEvent} that was included in the media action, or {@code null} if no
   * {@link KeyEvent} is found in the {@code intent}.
   */
  @Nullable
  public static KeyEvent getKeyEvent(Intent intent) {
    @Nullable Bundle extras = intent.getExtras();
    if (extras != null && extras.containsKey(Intent.EXTRA_KEY_EVENT)) {
      return extras.getParcelable(Intent.EXTRA_KEY_EVENT);
    }
    return null;
  }

  private final MediaSessionService service;

  public DefaultActionFactory(MediaSessionService service) {
    this.service = service;
  }

  @Override
  public NotificationCompat.Action createMediaAction(
      MediaSession mediaSession, IconCompat icon, CharSequence title, @Player.Command int command) {
    return new NotificationCompat.Action(
        icon, title, createMediaActionPendingIntent(mediaSession, command));
  }

  @Override
  public NotificationCompat.Action createCustomAction(
      MediaSession mediaSession,
      IconCompat icon,
      CharSequence title,
      String customAction,
      Bundle extras) {
    return new NotificationCompat.Action(
        icon,
        title,
        new CustomCommandPendingIntentBuilder(
                service, service.getClass(), new SessionCommand(customAction, extras))
            .setSessionId(mediaSession.getId())
            .build());
  }

  @Override
  public NotificationCompat.Action createCustomActionFromCustomCommandButton(
      MediaSession mediaSession, CommandButton customCommandButton) {
    checkArgument(
        customCommandButton.sessionCommand != null
            && customCommandButton.sessionCommand.commandCode
                == SessionCommand.COMMAND_CODE_CUSTOM);
    SessionCommand customCommand = checkNotNull(customCommandButton.sessionCommand);
    return new NotificationCompat.Action(
        IconCompat.createWithResource(service, customCommandButton.iconResId),
        customCommandButton.displayName,
        new CustomCommandPendingIntentBuilder(service, service.getClass(), customCommand)
            .setSessionId(mediaSession.getId())
            .build());
  }

  @SuppressWarnings("PendingIntentMutability") // We can't use SaferPendingIntent
  @Override
  public PendingIntent createMediaActionPendingIntent(
      MediaSession mediaSession, @Player.Command int command) {
    return new PlaybackPendingIntentBuilder(/* context= */ service, command, service.getClass())
        .setStartAsForegroundService(!mediaSession.getPlayer().getPlayWhenReady())
        .setSessionId(mediaSession.getId())
        .build();
  }

  @Override
  public PendingIntent createNotificationDismissalIntent(MediaSession mediaSession) {
    Bundle extras = new Bundle();
    extras.putBoolean(MediaNotification.NOTIFICATION_DISMISSED_EVENT_KEY, true);
    return new PlaybackPendingIntentBuilder(
            /* context= */ service, COMMAND_STOP, service.getClass())
        .setSessionId(mediaSession.getId())
        .setExtras(extras)
        .build();
  }

  /** Returns whether {@code intent} was part of a {@link #createMediaAction media action}. */
  public boolean isMediaAction(Intent intent) {
    return Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction());
  }

  /** Returns whether {@code intent} was part of a {@link #createCustomAction custom action }. */
  public boolean isCustomAction(Intent intent) {
    return ACTION_CUSTOM.equals(intent.getAction());
  }

  /**
   * Returns the custom action that was included in the {@link #createCustomAction custom action},
   * or {@code null} if no custom action is found in the {@code intent}.
   */
  @Nullable
  public String getCustomAction(Intent intent) {
    @Nullable Bundle extras = intent.getExtras();
    @Nullable Object customAction = extras != null ? extras.get(EXTRAS_KEY_ACTION_CUSTOM) : null;
    return customAction instanceof String ? (String) customAction : null;
  }

  /**
   * Returns extras that were included in the {@link #createCustomAction custom action}, or {@link
   * Bundle#EMPTY} is no extras are found.
   */
  public Bundle getCustomActionExtras(Intent intent) {
    @Nullable Bundle extras = intent.getExtras();
    @Nullable
    Object customExtras = extras != null ? extras.get(EXTRAS_KEY_ACTION_CUSTOM_EXTRAS) : null;
    return customExtras instanceof Bundle ? (Bundle) customExtras : Bundle.EMPTY;
  }
}
