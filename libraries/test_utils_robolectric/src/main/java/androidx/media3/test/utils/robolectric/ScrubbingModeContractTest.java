/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.test.utils.robolectric;

import static androidx.media3.test.utils.FakeMediaSource.FAKE_MEDIA_ITEM;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.ExoPlayer;
import com.google.errorprone.annotations.ForOverride;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Test;
import org.mockito.InOrder;

/**
 * A collection of contract tests for {@link Player} implementations that support {@link
 * Player#COMMAND_SET_AUDIO_ATTRIBUTES} and {@linkplain ExoPlayer#setScrubbingModeEnabled scrubbing
 * mode}.
 *
 * <p>Subclasses should only include the logic necessary to construct the {@link Player} and provide
 * access to relevant methods and internals by overriding {@link #createPlayerInfo()}.
 *
 * <p>Subclasses shouldn't include any new {@link Test @Test} methods - implementation-specific
 * tests should be in a separate class.
 */
@UnstableApi
public abstract class ScrubbingModeContractTest {

  /**
   * Interface that allows access to a {@link Player} instance, relevant internals, and other
   * methods not implemented within the {@link Player} interface required for testing.
   */
  public interface PlayerInfo {
    Player getPlayer();

    Clock getClock();

    Looper getPlaybackLooper();

    Looper getAudioFocusListenerLooper();

    /**
     * Sets a {@link MediaItem} on the {@link Player}.
     *
     * <p>Only implement this method if the {@link Player} implementation does not support {@link
     * Player#COMMAND_SET_MEDIA_ITEM}.
     */
    default void setMediaItem(MediaItem item) {
      throw new UnsupportedOperationException(
          "Subclasses must implement this method if they don't support COMMAND_SET_MEDIA_ITEM");
    }

    // TODO: b/426519822 - Remove once scrubbing mode moves to Player interface.
    void setScrubbingModeEnabled(boolean scrubbingModeEnabled);
  }

  private final AudioManager audioManager;
  private @MonotonicNonNull Player player;

  public ScrubbingModeContractTest() {
    audioManager = getApplicationContext().getSystemService(AudioManager.class);
  }

  @After
  public final void tearDown() {
    if (player != null) {
      player.release();
    }
  }

  /**
   * Creates and returns the {@link PlayerInfo} with which to run the tests.
   *
   * <p>The {@linkplain PlayerInfo#getPlayer() player} must have {@linkplain
   * Player#setAudioAttributes audio focus handling} and {@linkplain
   * PlayerInfo#setScrubbingModeEnabled scrubbing mode} initially disabled.
   */
  @ForOverride
  protected abstract PlayerInfo createPlayerInfo();

  @Test
  public void enableScrubbingMode_updatesPlaybackSuppressionReason() {
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    Player.Listener listener = mock(Player.Listener.class);
    player.addListener(listener);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();

    playerInfo.setScrubbingModeEnabled(true);

    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING);
    verify(listener)
        .onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING);
  }

  @Test
  public void disableScrubbing_whilePlaying_updatesPlaybackSuppressionReason() {
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    Player.Listener listener = mock(Player.Listener.class);
    player.addListener(listener);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();
    player.play();
    playerInfo.setScrubbingModeEnabled(true);

    playerInfo.setScrubbingModeEnabled(false);

    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    verify(listener).onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
  }

  @Test
  public void disableScrubbing_whilePaused_updatesSuppressionReasonAndDoesNotPlay() {
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    Player.Listener listener = mock(Player.Listener.class);
    player.addListener(listener);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();
    player.pause();
    playerInfo.setScrubbingModeEnabled(true);

    playerInfo.setScrubbingModeEnabled(false);

    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    verify(listener).onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    assertThat(player.getPlayWhenReady()).isFalse();
  }

  @Test
  public void audioFocusLoss_whileScrubbing_setsPlayWhenReadyToFalse() throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    Player.Listener listener = mock(Player.Listener.class);
    player.addListener(listener);
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();
    player.play();
    playerInfo.setScrubbingModeEnabled(true);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    triggerAudioFocusChangeListener(
        playerInfo.getAudioFocusListenerLooper(), AudioManager.AUDIOFOCUS_LOSS);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING);
    assertThat(player.getPlayWhenReady()).isFalse();
    verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS);
  }

  @Test
  public void
      disableScrubbing_audioFocusLostWhileScrubbingWhenPreviouslyPlaying_playsWithGrantedAudioFocus()
          throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    Player.Listener listener = mock(Player.Listener.class);
    InOrder inOrder = inOrder(listener);
    player.addListener(listener);
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();
    player.play();
    playerInfo.setScrubbingModeEnabled(true);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());
    triggerAudioFocusChangeListener(
        playerInfo.getAudioFocusListenerLooper(), AudioManager.AUDIOFOCUS_LOSS);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING);
    assertThat(player.getPlayWhenReady()).isFalse();
    inOrder
        .verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS);

    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    playerInfo.setScrubbingModeEnabled(false);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    verify(listener).onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    assertThat(player.getPlayWhenReady()).isTrue();
    inOrder
        .verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
  }

  @Test
  public void disableScrubbing_afterLossOfAudioFocusWhileScrubbing_handlesDeniedFocusRequest()
      throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    Player.Listener listener = mock(Player.Listener.class);
    InOrder inOrder = inOrder(listener);
    player.addListener(listener);
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();
    player.play();
    playerInfo.setScrubbingModeEnabled(true);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    triggerAudioFocusChangeListener(
        playerInfo.getAudioFocusListenerLooper(), AudioManager.AUDIOFOCUS_LOSS);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING);
    assertThat(player.getPlayWhenReady()).isFalse();
    inOrder
        .verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS);

    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
    playerInfo.setScrubbingModeEnabled(false);

    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());
    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    verify(listener).onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    assertThat(player.getPlayWhenReady()).isFalse();
  }

  @Test
  public void transientAudioFocusLoss_whileScrubbing_onlyUpdatesSuppressionReason()
      throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    Player.Listener listener = mock(Player.Listener.class);
    player.addListener(listener);
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();
    player.play();
    playerInfo.setScrubbingModeEnabled(true);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    triggerAudioFocusChangeListener(
        playerInfo.getAudioFocusListenerLooper(), AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS);
    verify(listener)
        .onPlaybackSuppressionReasonChanged(
            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS);
    assertThat(player.getPlayWhenReady()).isTrue();
    // Verify onPlayWhenReadyChanged() was only called once during the entire test.
    verify(listener).onPlayWhenReadyChanged(anyBoolean(), anyInt());
  }

  @Test
  public void recoverAudioFocus_afterTransientLossWhileScrubbing_returnsToScrubbingSuppression()
      throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    Player.Listener listener = mock(Player.Listener.class);
    player.addListener(listener);
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();
    player.play();
    playerInfo.setScrubbingModeEnabled(true);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    triggerAudioFocusChangeListener(
        playerInfo.getAudioFocusListenerLooper(), AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());
    triggerAudioFocusChangeListener(
        playerInfo.getAudioFocusListenerLooper(), AudioManager.AUDIOFOCUS_GAIN);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING);
    verify(listener, times(2))
        .onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING);
    assertThat(player.getPlayWhenReady()).isTrue();
    // Verify onPlayWhenReadyChanged() was only called once during the entire test.
    verify(listener).onPlayWhenReadyChanged(anyBoolean(), anyInt());
  }

  @Test
  public void disableScrubbing_afterTransientAudioFocusLoss_requestsAudioFocus() throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    Player.Listener listener = mock(Player.Listener.class);
    player.addListener(listener);
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();
    player.play();
    playerInfo.setScrubbingModeEnabled(true);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());
    triggerAudioFocusChangeListener(
        playerInfo.getAudioFocusListenerLooper(), AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    playerInfo.setScrubbingModeEnabled(false);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    verify(listener).onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    assertThat(player.getPlayWhenReady()).isTrue();
    // Verify onPlayWhenReadyChanged() was only called once during the entire test.
    verify(listener).onPlayWhenReadyChanged(anyBoolean(), anyInt());
  }

  @Test
  public void disableScrubbing_afterTransientAudioFocusLoss_handlesDeniedFocusRequest()
      throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    Player.Listener listener = mock(Player.Listener.class);
    player.addListener(listener);
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();
    player.play();
    playerInfo.setScrubbingModeEnabled(true);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());
    triggerAudioFocusChangeListener(
        playerInfo.getAudioFocusListenerLooper(), AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
    playerInfo.setScrubbingModeEnabled(false);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    verify(listener).onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    assertThat(player.getPlayWhenReady()).isFalse();
    verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS);
  }

  private void triggerAudioFocusChangeListener(Looper listenerLooper, int focusChange) {
    Util.postOrRun(
        new Handler(listenerLooper),
        () ->
            shadowOf(audioManager)
                .getLastAudioFocusRequest()
                .listener
                .onAudioFocusChange(focusChange));
  }

  private static void setMediaItem(PlayerInfo playerInfo, MediaItem item) {
    if (playerInfo.getPlayer().isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM)) {
      playerInfo.getPlayer().setMediaItem(item);
    } else {
      playerInfo.setMediaItem(item);
    }
  }
}
