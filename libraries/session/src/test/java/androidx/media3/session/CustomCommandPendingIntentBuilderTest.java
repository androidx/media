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

import static androidx.media3.session.CustomCommandPendingIntentBuilder.ACTION_CUSTOM;
import static androidx.media3.session.CustomCommandPendingIntentBuilder.EXTRAS_KEY_ACTION_CUSTOM;
import static androidx.media3.session.CustomCommandPendingIntentBuilder.EXTRAS_KEY_ACTION_CUSTOM_EXTRAS;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.robolectric.Shadows.shadowOf;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link CustomCommandPendingIntentBuilder}. */
@RunWith(AndroidJUnit4.class)
public class CustomCommandPendingIntentBuilderTest {

  @Test
  public void build_createsCorrectPendingIntent() {
    Context context = ApplicationProvider.getApplicationContext();
    String action = "test_action";
    Bundle extras = new Bundle();
    extras.putString("key", "value");

    PendingIntent pendingIntent =
        new CustomCommandPendingIntentBuilder(
                context, MediaSessionService.class, new SessionCommand(action, extras))
            .setSessionId("test_session_id")
            .build();

    Intent intent = shadowOf(pendingIntent).getSavedIntent();
    assertThat(intent.getAction()).isEqualTo(ACTION_CUSTOM);
    assertThat(intent.getComponent())
        .isEqualTo(new ComponentName(context, MediaSessionService.class));
    assertThat(intent.getData()).isEqualTo(Uri.parse("androidx://media3.session/test_session_id"));
    assertThat(intent.getStringExtra(EXTRAS_KEY_ACTION_CUSTOM)).isEqualTo(action);
    Bundle actualExtras = intent.getBundleExtra(EXTRAS_KEY_ACTION_CUSTOM_EXTRAS);
    assertThat(actualExtras).isNotNull();
    assertThat(actualExtras.getString("key")).isEqualTo("value");
    assertThat(shadowOf(pendingIntent).getFlags())
        .isEqualTo(PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  @Test
  public void build_noSessionId_correctSessionUri() {
    Context context = ApplicationProvider.getApplicationContext();
    String action = "test_action";

    PendingIntent pendingIntent =
        new CustomCommandPendingIntentBuilder(
                context, MediaSessionService.class, new SessionCommand(action, Bundle.EMPTY))
            .build();

    Intent intent = shadowOf(pendingIntent).getSavedIntent();
    assertThat(intent.getData()).isEqualTo(Uri.parse("androidx://media3.session/"));
  }

  @Test
  public void createCustomCommandIntent() {
    Context context = ApplicationProvider.getApplicationContext();
    String action = "test_action";
    Bundle extras = new Bundle();
    extras.putString("key", "value");
    SessionCommand customSessionCommand = new SessionCommand(action, extras);

    Intent intent =
        CustomCommandPendingIntentBuilder.createCustomCommandIntent(
            ApplicationProvider.getApplicationContext(),
            customSessionCommand,
            "sessionId",
            MediaLibraryService.class);

    assertThat(intent.getAction()).isEqualTo(ACTION_CUSTOM);
    assertThat(intent.getComponent())
        .isEqualTo(new ComponentName(context, MediaLibraryService.class));
    assertThat(intent.getData()).isEqualTo(Uri.parse("androidx://media3.session/sessionId"));
    assertThat(MediaSessionImpl.getSessionId(intent.getData())).isEqualTo("sessionId");
    assertThat(intent.getStringExtra(EXTRAS_KEY_ACTION_CUSTOM)).isEqualTo(action);
    Bundle actualExtras = intent.getBundleExtra(EXTRAS_KEY_ACTION_CUSTOM_EXTRAS);
    assertThat(actualExtras).isNotNull();
    assertThat(actualExtras.getString("key")).isEqualTo("value");
  }

  @Test
  public void createCustomCommandIntent_withNullSessionId_usesDefaultSessionId() {
    SessionCommand customSessionCommand = new SessionCommand("test_action", Bundle.EMPTY);

    Intent intent =
        CustomCommandPendingIntentBuilder.createCustomCommandIntent(
            ApplicationProvider.getApplicationContext(),
            customSessionCommand,
            /* sessionId= */ null,
            MediaLibraryService.class);

    assertThat(intent.getData()).isEqualTo(Uri.parse("androidx://media3.session/"));
    assertThat(MediaSessionImpl.getSessionId(intent.getData()))
        .isEqualTo(MediaSession.DEFAULT_SESSION_ID);
  }

  @Test
  public void
      createCustomCommandIntent_withInternalSessionCommand_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CustomCommandPendingIntentBuilder.createCustomCommandIntent(
                ApplicationProvider.getApplicationContext(),
                new SessionCommand(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH),
                /* sessionId= */ null,
                MediaLibraryService.class));
  }
}
