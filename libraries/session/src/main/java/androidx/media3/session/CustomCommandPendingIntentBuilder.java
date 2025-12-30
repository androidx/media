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

import static androidx.media3.session.MediaSessionImpl.createSessionUri;
import static com.google.common.base.Preconditions.checkArgument;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds a {@link PendingIntent} to send a custom command to a {@link MediaSessionService}.
 *
 * <p>This builder is primarily intended for creating {@link PendingIntent} instances used in home
 * screen widgets (see <a
 * href="https://developer.android.com/develop/ui/views/appwidgets/overview">App widgets
 * overview</a>). For other use cases, such as interacting with a Media3 session service from within
 * an app, it is strongly recommended to use a {@link MediaController} or {@link MediaBrowser}
 * instead of a {@link PendingIntent}.
 */
@UnstableApi
public final class CustomCommandPendingIntentBuilder {

  /* package */ static final String ACTION_CUSTOM =
      "androidx.media3.session.CUSTOM_NOTIFICATION_ACTION";
  /* package */ static final String EXTRAS_KEY_ACTION_CUSTOM =
      "androidx.media3.session.EXTRAS_KEY_CUSTOM_NOTIFICATION_ACTION";
  /* package */ static final String EXTRAS_KEY_ACTION_CUSTOM_EXTRAS =
      "androidx.media3.session.EXTRAS_KEY_CUSTOM_NOTIFICATION_ACTION_EXTRAS";

  private final Context context;
  private final Class<? extends MediaSessionService> serviceClass;
  private final SessionCommand customSessionCommand;
  @Nullable private String sessionId;

  /**
   * Creates an instance.
   *
   * @param context The context.
   * @param serviceClass The class of the service to which the intent should be sent.
   * @param customSessionCommand The custom {@link SessionCommand}.
   */
  public CustomCommandPendingIntentBuilder(
      Context context,
      Class<? extends MediaSessionService> serviceClass,
      SessionCommand customSessionCommand) {
    checkArgument(customSessionCommand.commandCode == SessionCommand.COMMAND_CODE_CUSTOM);
    this.context = context;
    this.serviceClass = serviceClass;
    this.customSessionCommand = customSessionCommand;
    this.sessionId = null;
  }

  /**
   * Creates an {@link Intent} to send a custom command to a {@link MediaSessionService}.
   *
   * @param context The context.
   * @param customSessionCommand The custom {@link SessionCommand}.
   * @param sessionId The ID of the session as set with {@link MediaSession.Builder#setId(String)}
   *     or null if the default ID was used when building the session.
   * @param serviceClass The class of the service to which the intent should be sent.
   * @return The {@link Intent}.
   */
  public static Intent createCustomCommandIntent(
      Context context,
      SessionCommand customSessionCommand,
      @Nullable String sessionId,
      Class<? extends MediaSessionService> serviceClass) {
    checkArgument(customSessionCommand.commandCode == SessionCommand.COMMAND_CODE_CUSTOM);
    Intent intent = new Intent(ACTION_CUSTOM);
    intent.setData(createSessionUri(sessionId));
    intent.setComponent(new ComponentName(context, serviceClass));
    intent.putExtra(EXTRAS_KEY_ACTION_CUSTOM, customSessionCommand.customAction);
    intent.putExtra(EXTRAS_KEY_ACTION_CUSTOM_EXTRAS, customSessionCommand.customExtras);
    return intent;
  }

  /**
   * Sets the ID of the session.
   *
   * <p>The default value is {@code null}, which which corresponds to the default ID used when
   * {@link MediaSession.Builder#setId(String)} was not called when building the session.
   *
   * @param sessionId The ID of the session as set with {@link MediaSession.Builder#setId(String)}
   *     or null if the default ID was used when building the session.
   * @return This builder.
   */
  @CanIgnoreReturnValue
  public CustomCommandPendingIntentBuilder setSessionId(@Nullable String sessionId) {
    this.sessionId = sessionId;
    return this;
  }

  /**
   * Builds the {@link PendingIntent}.
   *
   * <p>Each {@link PendingIntent} has a random request code. Any created {@link PendingIntent} can
   * be {@linkplain Intent#filterEquals(Intent) considered different from all other instance}.
   *
   * <p>The {@link PendingIntent} is created with the flags {@code PendingIntent.FLAG_UPDATE_CURRENT
   * | PendingIntent.FLAG_IMMUTABLE}.
   */
  @SuppressWarnings("PendingIntentMutability") // We can't use SaferPendingIntent
  public PendingIntent build() {
    // Custom actions always start the service in the background.
    return PendingIntent.getService(
        context,
        /* requestCode= */ ThreadLocalRandom.current().nextInt(),
        createCustomCommandIntent(context, customSessionCommand, sessionId, serviceClass),
        /* flags= */ PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }
}
