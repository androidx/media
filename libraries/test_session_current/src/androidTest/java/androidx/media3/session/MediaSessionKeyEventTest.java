/*
 * Copyright 2018 The Android Open Source Project
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
import static androidx.media.MediaSessionManager.RemoteUserInfo.LEGACY_CONTROLLER;
import static androidx.media3.common.Player.STATE_ENDED;
import static androidx.media3.session.MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION;
import static androidx.media3.test.session.common.CommonConstants.SUPPORT_APP_PACKAGE_NAME;
import static androidx.media3.test.session.common.TestUtils.LONG_TIMEOUT_MS;
import static androidx.media3.test.session.common.TestUtils.TIMEOUT_MS;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import androidx.annotation.Nullable;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.Player;
import androidx.media3.session.MediaSession.ControllerInfo;
import androidx.media3.test.session.common.HandlerThreadTestRule;
import androidx.media3.test.session.common.MainLooperTestRule;
import androidx.media3.test.session.common.R;
import androidx.media3.test.session.common.TestHandler;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.LargeTest;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for key event handling of {@link MediaSession}. In order to get the media key events, the
 * player state is set to 'Playing' before every test method.
 */
@RunWith(TestParameterInjector.class)
@LargeTest
public class MediaSessionKeyEventTest {

  @ClassRule public static MainLooperTestRule mainLooperTestRule = new MainLooperTestRule();

  @Rule
  public final HandlerThreadTestRule threadTestRule =
      new HandlerThreadTestRule("MediaSessionKeyEventTest");

  // Intentionally member variable to prevent GC while playback is running.
  // Should be only used on the sHandler.
  private MediaPlayer mediaPlayer;

  private AudioManager audioManager;
  private TestHandler handler;
  private MediaSession session;
  private MockPlayer player;
  private TestSessionCallback sessionCallback;
  private CallerCollectorPlayer callerCollectorPlayer;

  @Before
  public void setUp() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    handler = threadTestRule.getHandler();
    player =
        new MockPlayer.Builder().setMediaItems(1).setApplicationLooper(handler.getLooper()).build();
    sessionCallback = new TestSessionCallback();
    callerCollectorPlayer = new CallerCollectorPlayer(player);
    session =
        new MediaSession.Builder(context, callerCollectorPlayer)
            .setCallback(sessionCallback)
            .build();

    // Here's the requirement for an app to receive media key events via MediaSession.
    // - SDK < 26: Player should be playing for receiving key events
    // - SDK >= 26: Play a media item in the same process of the session for receiving key events.
    if (SDK_INT < 26) {
      handler.postAndSync(
          () -> {
            player.notifyPlayWhenReadyChanged(
                /* playWhenReady= */ true,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                Player.PLAYBACK_SUPPRESSION_REASON_NONE);
            player.notifyPlaybackStateChanged(Player.STATE_READY);
          });
    } else {
      CountDownLatch latch = new CountDownLatch(1);
      handler.postAndSync(
          () -> {
            // Pick the shortest media to finish within the timeout.
            mediaPlayer = MediaPlayer.create(context, R.raw.camera_click);
            mediaPlayer.setOnCompletionListener(
                player -> {
                  if (mediaPlayer != null) {
                    mediaPlayer.release();
                    mediaPlayer = null;
                    latch.countDown();
                  }
                });
            mediaPlayer.start();
          });
      assertThat(latch.await(LONG_TIMEOUT_MS, MILLISECONDS)).isTrue();
    }
  }

  @After
  public void tearDown() throws Exception {
    handler.postAndSync(
        () -> {
          if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
          }
        });
    session.release();
  }

  @Test
  public void playKeyEvent() throws Exception {
    handler.postAndSync(
        () -> {
          // Update state to allow play event to be triggered.
          player.notifyPlayWhenReadyChanged(
              /* playWhenReady= */ false,
              Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
              Player.PLAYBACK_SUPPRESSION_REASON_NONE);
        });
    dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY, false);

    player.awaitMethodCalled(MockPlayer.METHOD_PLAY, TIMEOUT_MS);
  }

  @Test
  public void pauseKeyEvent() throws Exception {
    handler.postAndSync(
        () -> {
          // Update state to allow pause event to be triggered.
          player.notifyPlayWhenReadyChanged(
              /* playWhenReady= */ true,
              Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
              Player.PLAYBACK_SUPPRESSION_REASON_NONE);
          player.notifyPlaybackStateChanged(Player.STATE_READY);
        });
    dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE, false);

    player.awaitMethodCalled(MockPlayer.METHOD_PAUSE, TIMEOUT_MS);
  }

  @Test
  public void nextKeyEvent() throws Exception {
    dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT, false);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_NEXT, TIMEOUT_MS);
  }

  @Test
  public void previousKeyEvent() throws Exception {
    dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS, false);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_PREVIOUS, TIMEOUT_MS);
  }

  @Test
  public void
      fastForwardKeyEvent_mediaNotificationControllerConnected_callFromNotificationController()
          throws Exception {
    MediaController controller = connectMediaNotificationController();
    dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, /* doubleTap= */ false);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_FORWARD, TIMEOUT_MS);
    assertThat(callerCollectorPlayer.callers).hasSize(1);
    assertThat(callerCollectorPlayer.callers.get(0).getControllerVersion())
        .isNotEqualTo(LEGACY_CONTROLLER_VERSION);
    assertThat(callerCollectorPlayer.callers.get(0).getPackageName())
        .isEqualTo("androidx.media3.test.session");
    assertThat(callerCollectorPlayer.callers.get(0).getConnectionHints().size()).isEqualTo(1);
    assertThat(
            callerCollectorPlayer
                .callers
                .get(0)
                .getConnectionHints()
                .getBoolean(
                    MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG,
                    /* defaultValue= */ false))
        .isTrue();
    threadTestRule.getHandler().postAndSync(controller::release);
  }

  @Test
  public void
      fastForwardKeyEvent_mediaNotificationControllerNotConnected_callFromLegacyFallbackController()
          throws Exception {
    dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, false);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_FORWARD, TIMEOUT_MS);
    List<ControllerInfo> controllers = callerCollectorPlayer.callers;
    assertThat(controllers).hasSize(1);
    assertThat(controllers.get(0).getControllerVersion()).isEqualTo(LEGACY_CONTROLLER_VERSION);
    assertThat(controllers.get(0).getConnectionHints().size()).isEqualTo(0);
    assertThat(controllers.get(0).getPackageName())
        .isEqualTo(getExpectedControllerPackageName(controllers.get(0)));
  }

  @Test
  public void rewindKeyEvent_mediaNotificationControllerConnected_callFromNotificationController()
      throws Exception {
    MediaController controller = connectMediaNotificationController();

    dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_REWIND, false);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_BACK, TIMEOUT_MS);
    List<ControllerInfo> controllers = callerCollectorPlayer.callers;
    assertThat(controllers).hasSize(1);
    assertThat(controllers.get(0).getPackageName()).isEqualTo("androidx.media3.test.session");
    assertThat(controllers.get(0).getControllerVersion()).isNotEqualTo(LEGACY_CONTROLLER_VERSION);
    assertThat(controllers.get(0).getConnectionHints().size()).isEqualTo(1);
    assertThat(
            controllers
                .get(0)
                .getConnectionHints()
                .getBoolean(
                    MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG,
                    /* defaultValue= */ false))
        .isTrue();
    threadTestRule.getHandler().postAndSync(controller::release);
  }

  @Test
  public void
      rewindKeyEvent_mediaNotificationControllerNotConnected_callFromLegacyFallbackController()
          throws Exception {
    dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_REWIND, false);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_BACK, TIMEOUT_MS);
    List<ControllerInfo> controllers = callerCollectorPlayer.callers;
    assertThat(controllers).hasSize(1);
    assertThat(controllers.get(0).getControllerVersion()).isEqualTo(LEGACY_CONTROLLER_VERSION);
    assertThat(controllers.get(0).getConnectionHints().size()).isEqualTo(0);
    assertThat(controllers.get(0).getPackageName())
        .isEqualTo(getExpectedControllerPackageName(controllers.get(0)));
  }

  @Test
  public void stopKeyEvent() throws Exception {
    dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_STOP, false);

    player.awaitMethodCalled(MockPlayer.METHOD_STOP, TIMEOUT_MS);
  }

  // We don't receive media key events when we are not playing on API < 26, so we can't test this
  // case as it's not supported.
  @SdkSuppress(minSdkVersion = 26)
  @Test
  public void playPauseKeyEvent_paused_play(@TestParameter PlayPauseEvent playPauseEvent)
      throws Exception {
    handler.postAndSync(
        () -> {
          player.playbackState = Player.STATE_READY;
        });

    dispatchMediaKeyEvent(playPauseEvent.keyCode, /* doubleTap= */ false);

    player.awaitMethodCalled(MockPlayer.METHOD_PLAY, TIMEOUT_MS);
  }

  // We don't receive media key events when we are not playing on API < 26, so we can't test this
  // case as it's not supported.
  @SdkSuppress(minSdkVersion = 26)
  @Test
  public void playPauseKeyEvent_fromIdle_prepareAndPlay(
      @TestParameter PlayPauseEvent playPauseEvent) throws Exception {
    handler.postAndSync(
        () -> {
          player.playbackState = Player.STATE_IDLE;
        });

    dispatchMediaKeyEvent(playPauseEvent.keyCode, /* doubleTap= */ false);

    player.awaitMethodCalled(MockPlayer.METHOD_PREPARE, TIMEOUT_MS);
    player.awaitMethodCalled(MockPlayer.METHOD_PLAY, TIMEOUT_MS);
  }

  // We don't receive media key events when we are not playing on API < 26, so we can't test this
  // case as it's not supported.
  @SdkSuppress(minSdkVersion = 26)
  @Test
  public void playPauseKeyEvent_playWhenReadyAndEnded_seekAndPlay(
      @TestParameter PlayPauseEvent playPauseEvent) throws Exception {
    handler.postAndSync(
        () -> {
          player.playWhenReady = true;
          player.playbackState = STATE_ENDED;
        });

    dispatchMediaKeyEvent(playPauseEvent.keyCode, /* doubleTap= */ false);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_DEFAULT_POSITION, TIMEOUT_MS);
    player.awaitMethodCalled(MockPlayer.METHOD_PLAY, TIMEOUT_MS);
  }

  @Test
  public void playPauseKeyEvent_playing_pause() throws Exception {
    handler.postAndSync(
        () -> {
          player.playWhenReady = true;
          player.playbackState = Player.STATE_READY;
        });

    dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, /* doubleTap= */ false);

    player.awaitMethodCalled(MockPlayer.METHOD_PAUSE, TIMEOUT_MS);
  }

  @Test
  public void headsetHookKeyEvent_playing_pause() throws Exception {
    handler.postAndSync(
        () -> {
          player.playWhenReady = true;
          player.playbackState = Player.STATE_READY;
        });

    dispatchMediaKeyEvent(KeyEvent.KEYCODE_HEADSETHOOK, /* doubleTap= */ false);

    player.awaitMethodCalled(MockPlayer.METHOD_PAUSE, TIMEOUT_MS);
  }

  @Test
  public void playPauseKeyEvent_doubleTapOnPlayPause_seekNext() throws Exception {
    handler.postAndSync(
        () -> {
          player.playWhenReady = true;
          player.playbackState = Player.STATE_READY;
        });

    dispatchMediaKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, /* doubleTap= */ true);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_NEXT, TIMEOUT_MS);
  }

  @Test
  public void headsetHookKeyEvent_doubleTapOnPlayPause_seekNext() throws Exception {
    handler.postAndSync(
        () -> {
          player.playWhenReady = true;
          player.playbackState = Player.STATE_READY;
        });

    dispatchMediaKeyEvent(KeyEvent.KEYCODE_HEADSETHOOK, /* doubleTap= */ true);

    player.awaitMethodCalled(MockPlayer.METHOD_SEEK_TO_NEXT, TIMEOUT_MS);
  }

  @Test
  public void longPress_repeatedThreeTimesTheActionUp_dispatchedToPlayingSession()
      throws Exception {
    CountDownLatch latch = new CountDownLatch(5);
    sessionCallback.setOnMediaButtonEventLatch(latch);
    KeyEvent longPressKeyEvent0 =
        KeyEvent.changeTimeRepeat(
            new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY),
            SystemClock.uptimeMillis(),
            /* newRepeat= */ 0);
    KeyEvent longPressKeyEvent1 =
        KeyEvent.changeTimeRepeat(
            longPressKeyEvent0,
            longPressKeyEvent0.getEventTime() + 100,
            /* newRepeat= */ 1,
            KeyEvent.FLAG_LONG_PRESS);
    KeyEvent longPressKeyEvent2 =
        KeyEvent.changeTimeRepeat(
            longPressKeyEvent0,
            longPressKeyEvent0.getEventTime() + 200,
            /* newRepeat= */ 2,
            KeyEvent.FLAG_LONG_PRESS);
    KeyEvent longPressKeyEvent3 =
        KeyEvent.changeTimeRepeat(
            longPressKeyEvent0,
            longPressKeyEvent0.getEventTime() + 300,
            /* newRepeat= */ 3,
            KeyEvent.FLAG_LONG_PRESS);
    KeyEvent actionUpKeyEvent = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY);

    audioManager.dispatchMediaKeyEvent(longPressKeyEvent0);
    audioManager.dispatchMediaKeyEvent(longPressKeyEvent1);
    audioManager.dispatchMediaKeyEvent(longPressKeyEvent2);
    audioManager.dispatchMediaKeyEvent(longPressKeyEvent3);
    audioManager.dispatchMediaKeyEvent(actionUpKeyEvent);

    assertThat(latch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();

    ImmutableList<KeyEvent> expectedEvents =
        ImmutableList.of(
            longPressKeyEvent0,
            longPressKeyEvent1,
            longPressKeyEvent2,
            longPressKeyEvent3,
            actionUpKeyEvent);
    assertThat(sessionCallback.mediaButtonKeyEvents).hasSize(expectedEvents.size());
    for (int i = 0; i < expectedEvents.size(); i++) {
      KeyEvent expected = expectedEvents.get(i);
      KeyEvent actual = sessionCallback.mediaButtonKeyEvents.get(i);
      assertThat(actual.getAction()).isEqualTo(expected.getAction());
      assertThat(actual.getEventTime()).isEqualTo(expected.getEventTime());
      assertThat(actual.getRepeatCount()).isEqualTo(expected.getRepeatCount());
      assertThat(actual.getFlags()).isEqualTo(expected.getFlags());
    }
  }

  private MediaController connectMediaNotificationController() throws Exception {
    return threadTestRule
        .getHandler()
        .postAndSync(
            () -> {
              Bundle connectionHints = new Bundle();
              connectionHints.putBoolean(
                  MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, /* value= */ true);
              return new MediaController.Builder(
                      ApplicationProvider.getApplicationContext(), session.getToken())
                  .setConnectionHints(connectionHints)
                  .buildAsync()
                  .get();
            });
  }

  private void dispatchMediaKeyEvent(int keyCode, boolean doubleTap) {
    audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
    audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    if (doubleTap) {
      audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
      audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }
  }

  private static String getExpectedControllerPackageName(ControllerInfo controllerInfo) {
    if (controllerInfo.getControllerVersion() != ControllerInfo.LEGACY_CONTROLLER_VERSION) {
      return SUPPORT_APP_PACKAGE_NAME;
    }
    // Legacy controllers
    if (SDK_INT >= 28) {
      // Above API 28: package of the app using AudioManager.
      return SUPPORT_APP_PACKAGE_NAME;
    } else if (SDK_INT >= 24) {
      // API 24 - 27: KeyEvent from system service has the package name "android".
      return "android";
    } else {
      // API 23: Fallback set by MediaSessionCompat#getCurrentControllerInfo
      return LEGACY_CONTROLLER;
    }
  }

  private static class TestSessionCallback implements MediaSession.Callback {

    private final List<KeyEvent> mediaButtonKeyEvents;
    @Nullable private CountDownLatch latch;

    private TestSessionCallback() {
      mediaButtonKeyEvents = new ArrayList<>();
    }

    /**
     * Set the latch to be count down for each call to {@link #onMediaButtonEvent(MediaSession,
     * ControllerInfo, Intent)}.
     */
    public void setOnMediaButtonEventLatch(CountDownLatch latch) {
      this.latch = latch;
    }

    @Override
    public MediaSession.ConnectionResult onConnect(
        MediaSession session, ControllerInfo controller) {
      if (session.isMediaNotificationController(controller)
          || getExpectedControllerPackageName(controller).equals(controller.getPackageName())) {
        return MediaSession.Callback.super.onConnect(session, controller);
      }
      return MediaSession.ConnectionResult.reject();
    }

    @Override
    public boolean onMediaButtonEvent(
        MediaSession session, ControllerInfo controllerInfo, Intent intent) {
      mediaButtonKeyEvents.add(DefaultActionFactory.getKeyEvent(intent));
      if (latch != null) {
        latch.countDown();
      }
      return MediaSession.Callback.super.onMediaButtonEvent(session, controllerInfo, intent);
    }
  }

  private class CallerCollectorPlayer extends ForwardingPlayer {
    private final List<ControllerInfo> callers;

    public CallerCollectorPlayer(Player player) {
      super(player);
      callers = new ArrayList<>();
    }

    @Override
    public void seekForward() {
      callers.add(session.getControllerForCurrentRequest());
      super.seekForward();
    }

    @Override
    public void seekBack() {
      callers.add(session.getControllerForCurrentRequest());
      super.seekBack();
    }
  }

  private enum PlayPauseEvent {
    MEDIA_PLAY_PAUSE(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
    MEDIA_PLAY(KeyEvent.KEYCODE_MEDIA_PLAY),
    HEADSETHOOK(KeyEvent.KEYCODE_HEADSETHOOK);

    final int keyCode;

    PlayPauseEvent(int keyCode) {
      this.keyCode = keyCode;
    }
  }
}
