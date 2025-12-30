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

import static android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
import static android.view.KeyEvent.KEYCODE_MEDIA_NEXT;
import static android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
import static android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS;
import static android.view.KeyEvent.KEYCODE_MEDIA_REWIND;
import static android.view.KeyEvent.KEYCODE_MEDIA_STOP;
import static androidx.media3.common.Player.COMMAND_PLAY_PAUSE;
import static androidx.media3.common.Player.COMMAND_SEEK_BACK;
import static androidx.media3.common.Player.COMMAND_SEEK_FORWARD;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM;
import static androidx.media3.common.Player.COMMAND_STOP;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.robolectric.Shadows.shadowOf;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import androidx.media3.common.Player;
import androidx.test.core.app.ApplicationProvider;
import com.google.testing.junit.testparameterinjector.TestParameter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** Tests for {@link PlaybackPendingIntentBuilderTest}. */
@RunWith(RobolectricTestParameterInjector.class)
public class PlaybackPendingIntentBuilderTest {

  public enum PlaybackCommandScenario {
    PLAY_PAUSE(COMMAND_PLAY_PAUSE, KEYCODE_MEDIA_PLAY_PAUSE, true),
    SEEK_BACK(COMMAND_SEEK_BACK, KEYCODE_MEDIA_REWIND, false),
    SEEK_FORWARD(COMMAND_SEEK_FORWARD, KEYCODE_MEDIA_FAST_FORWARD, false),
    SEEK_NEXT_ITEM(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, KEYCODE_MEDIA_NEXT, false),
    SEEK_NEXT(COMMAND_SEEK_TO_NEXT, KEYCODE_MEDIA_NEXT, false),
    SEEK_PREV_ITEM(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, KEYCODE_MEDIA_PREVIOUS, false),
    SEEK_PREV(COMMAND_SEEK_TO_PREVIOUS, KEYCODE_MEDIA_PREVIOUS, false),
    STOP(COMMAND_STOP, KEYCODE_MEDIA_STOP, false);

    final int command;
    final int keyCode;
    final boolean startForeground;

    PlaybackCommandScenario(int command, int keyCode, boolean startForeground) {
      this.command = command;
      this.keyCode = keyCode;
      this.startForeground = startForeground;
    }
  }

  @Test
  public void build_supportedCommands_buildsCorrectPendingIntent(
      @TestParameter PlaybackCommandScenario scenario) {
    Context context = ApplicationProvider.getApplicationContext();
    Bundle extras = new Bundle();
    extras.putString("key0", "value0");

    PendingIntent pendingIntent =
        new PlaybackPendingIntentBuilder(context, scenario.command, MediaSessionService.class)
            .setSessionId("test_session_id")
            .setStartAsForegroundService(scenario.startForeground)
            .setExtras(extras)
            .build();

    Intent intent = shadowOf(pendingIntent).getSavedIntent();
    KeyEvent keyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
    assertThat(keyEvent.getAction()).isEqualTo(KeyEvent.ACTION_DOWN);
    assertThat(keyEvent.getKeyCode()).isEqualTo(scenario.keyCode);
    assertThat(intent.getAction()).isEqualTo(Intent.ACTION_MEDIA_BUTTON);
    assertThat(pendingIntent.isForegroundService()).isEqualTo(scenario.startForeground);
    assertThat(intent.getData()).isEqualTo(Uri.parse("androidx://media3.session/test_session_id"));
    assertThat(intent.getComponent())
        .isEqualTo(new ComponentName(context, MediaSessionService.class));
    assertThat(intent.getStringExtra("key0")).isEqualTo("value0");
    assertThat(shadowOf(pendingIntent).getFlags())
        .isEqualTo(PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  @Test
  public void isCommandSupported(@TestParameter PlaybackCommandScenario scenario) {
    assertThat(PlaybackPendingIntentBuilder.isCommandSupported(scenario.command)).isTrue();
  }

  @Test
  public void build_withNullSessionId_buildsCorrectPendingIntent() {
    Context context = ApplicationProvider.getApplicationContext();

    PendingIntent pendingIntent =
        new PlaybackPendingIntentBuilder(context, COMMAND_SEEK_TO_NEXT, MediaSessionService.class)
            .build();

    Intent intent = shadowOf(pendingIntent).getSavedIntent();
    assertThat(intent.getData()).isEqualTo(Uri.parse("androidx://media3.session/"));
  }

  @Test
  public void build_multipleTimes_updatesExtrasInPendingIntent() {
    Context context = ApplicationProvider.getApplicationContext();
    Bundle initialBundle = new Bundle();
    initialBundle.putString("key0", "value_0");
    initialBundle.putString("key1", "value_1");
    Bundle secondBundle = new Bundle();
    secondBundle.putString("key0", "value_updated");
    secondBundle.putString("key1", null);
    PlaybackPendingIntentBuilder builder =
        new PlaybackPendingIntentBuilder(context, COMMAND_SEEK_TO_NEXT, MediaSessionService.class);
    PendingIntent initialPendingIntent = builder.setExtras(initialBundle).build();

    Intent initialIntent = shadowOf(initialPendingIntent).getSavedIntent();

    assertThat(initialIntent.getStringExtra("key0")).isEqualTo("value_0");
    assertThat(initialIntent.getStringExtra("key1")).isEqualTo("value_1");

    PendingIntent secondsPendingIntent = builder.setExtras(secondBundle).build();

    Intent updatedInitialIntent = shadowOf(initialPendingIntent).getSavedIntent();
    assertThat(updatedInitialIntent.getStringExtra("key0")).isEqualTo("value_updated");
    assertThat(updatedInitialIntent.getStringExtra("key1")).isNull();
    Intent secondIntent = shadowOf(secondsPendingIntent).getSavedIntent();
    assertThat(secondIntent.getStringExtra("key0")).isEqualTo("value_updated");
    assertThat(secondIntent.getStringExtra("key1")).isNull();
  }

  @Test
  public void isCommandSupported_unsupportedCommand_returnsFalse() {
    assertThat(
            PlaybackPendingIntentBuilder.isCommandSupported(
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS))
        .isFalse();
  }

  @Test
  public void build_withUnsupportedCommand_throwsIllegalArgumentException() {
    Context context = ApplicationProvider.getApplicationContext();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PlaybackPendingIntentBuilder(
                context, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, MediaSessionService.class));
  }

  @Test
  public void createMediaButtonIntent() {
    Bundle extras = new Bundle();
    extras.putString("key0", "value0");

    Intent intent =
        PlaybackPendingIntentBuilder.createMediaButtonIntent(
            ApplicationProvider.getApplicationContext(),
            COMMAND_SEEK_BACK,
            extras,
            "test_session_id",
            MediaLibraryService.class);

    KeyEvent keyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
    assertThat(keyEvent.getAction()).isEqualTo(KeyEvent.ACTION_DOWN);
    assertThat(keyEvent.getKeyCode()).isEqualTo(KEYCODE_MEDIA_REWIND);
    assertThat(intent.getAction()).isEqualTo(Intent.ACTION_MEDIA_BUTTON);
    assertThat(intent.getData()).isEqualTo(Uri.parse("androidx://media3.session/test_session_id"));
    assertThat(MediaSessionImpl.getSessionId(intent.getData())).isEqualTo("test_session_id");
    assertThat(intent.getComponent())
        .isEqualTo(
            new ComponentName(
                ApplicationProvider.getApplicationContext(), MediaLibraryService.class));
    assertThat(intent.getStringExtra("key0")).isEqualTo("value0");
  }

  @Test
  public void createMediaButtonIntent_withNullSessionId_usesDefaultSessionId() {

    Intent intent =
        PlaybackPendingIntentBuilder.createMediaButtonIntent(
            ApplicationProvider.getApplicationContext(),
            COMMAND_SEEK_BACK,
            Bundle.EMPTY,
            /* sessionId= */ null,
            MediaLibraryService.class);

    assertThat(intent.getData()).isEqualTo(Uri.parse("androidx://media3.session/"));
    assertThat(MediaSessionImpl.getSessionId(intent.getData()))
        .isEqualTo(MediaSession.DEFAULT_SESSION_ID);
  }

  @Test
  public void createMediaButtonIntent_invalidCommand_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PlaybackPendingIntentBuilder.createMediaButtonIntent(
                ApplicationProvider.getApplicationContext(),
                Player.COMMAND_SET_AUDIO_ATTRIBUTES,
                Bundle.EMPTY,
                "test_session_id",
                MediaLibraryService.class));
  }
}
