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
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.play;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Player.Listener;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.errorprone.annotations.ForOverride;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Test;
import org.mockito.InOrder;

/**
 * A collection of contract tests for {@link Player} implementations that support {@link
 * Player#COMMAND_SET_AUDIO_ATTRIBUTES}.
 *
 * <p>Subclasses should only include the logic necessary to construct the {@link Player} and return
 * the player's internal {@link Clock} and internal processing {@link Looper} by overriding {@link
 * #createPlayerInfo()}.
 *
 * <p>Subclasses shouldn't include any new {@link Test @Test} methods - implementation-specific
 * tests should be in a separate class.
 */
@UnstableApi
public abstract class PlayerAudioFocusContractTest {

  /**
   * Interface that allows access to a {@link Player} instance and its internal {@link Clock} and
   * processing {@link Looper}.
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
  }

  private final AudioManager audioManager;
  private @MonotonicNonNull Player player;

  public PlayerAudioFocusContractTest() {
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
   * Player#setAudioAttributes audio focus handling} initially disabled.
   */
  @ForOverride
  protected abstract PlayerInfo createPlayerInfo();

  @Test
  public void player_hasRequiredCommandsAvailable() {
    player = createPlayerInfo().getPlayer();
    assertThat(player.isCommandAvailable(Player.COMMAND_SET_AUDIO_ATTRIBUTES)).isTrue();
    assertThat(player.isCommandAvailable(Player.COMMAND_PREPARE)).isTrue();
  }

  @Test
  public void getPlaybackSuppressionReason_withUnpreparedPlayer_returnsNone() {
    player = createPlayerInfo().getPlayer();
    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
  }

  @Test
  public void play_withAudioFocusRequestGranted_startsPlayback() throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    Listener listener = mock(Player.Listener.class);
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    player.addListener(listener);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();

    play(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    assertThat(player.getPlayWhenReady()).isTrue();
    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    verify(listener, never()).onPlaybackSuppressionReasonChanged(anyInt());
    verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
  }

  @Test
  public void play_withAudioFocusRequestDenied_doesNotPlay() throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
    Listener listener = mock(Player.Listener.class);
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    player.addListener(listener);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();

    play(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    verify(listener, never()).onPlaybackSuppressionReasonChanged(anyInt());
    verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS);
  }

  @Test
  public void play_withHandleAudioFocusDisabled_ignoresDeniedRequestAndStartsPlayback()
      throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
    Listener listener = mock(Player.Listener.class);
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    player.addListener(listener);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();

    play(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    assertThat(player.getPlayWhenReady()).isTrue();
    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    verify(listener, never()).onPlaybackSuppressionReasonChanged(anyInt());
    verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
  }

  @Test
  public void audioFocusLost_whilePlaying_pausesPlayback() throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    Listener listener = mock(Player.Listener.class);
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    player.addListener(listener);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();
    play(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    triggerAudioFocusChangeListener(
        playerInfo.getAudioFocusListenerLooper(), AudioManager.AUDIOFOCUS_LOSS);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    verify(listener, never()).onPlaybackSuppressionReasonChanged(anyInt());
    InOrder inOrder = inOrder(listener);
    inOrder
        .verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
    inOrder
        .verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS);
  }

  @Test
  public void transientLossAndGain_whilePlaying_suppressesPlaybackWhileLost() throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    Listener listener = mock(Player.Listener.class);
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    player.addListener(listener);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();
    play(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    triggerAudioFocusChangeListener(
        playerInfo.getAudioFocusListenerLooper(), AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());
    boolean playWhenReady = player.getPlayWhenReady();
    @Player.PlaybackSuppressionReason int suppressionReason = player.getPlaybackSuppressionReason();
    triggerAudioFocusChangeListener(
        playerInfo.getAudioFocusListenerLooper(), AudioManager.AUDIOFOCUS_GAIN);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());
    boolean playWhenReadyAfterGain = player.getPlayWhenReady();
    @Player.PlaybackSuppressionReason
    int suppressionReasonAfterGain = player.getPlaybackSuppressionReason();

    assertThat(playWhenReady).isTrue();
    assertThat(suppressionReason)
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS);
    assertThat(playWhenReadyAfterGain).isTrue();
    assertThat(suppressionReasonAfterGain).isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    InOrder inOrder = inOrder(listener);
    inOrder
        .verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
    inOrder
        .verify(listener)
        .onPlaybackSuppressionReasonChanged(
            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS);
    inOrder
        .verify(listener)
        .onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    verify(listener, never())
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS);
  }

  @Test
  public void pause_duringTransientLossWhilePlaying_keepsPlaybackPausedAndSuppressed()
      throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    Listener listener = mock(Player.Listener.class);
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    player.addListener(listener);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();
    play(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    triggerAudioFocusChangeListener(
        playerInfo.getAudioFocusListenerLooper(), AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());
    player.pause();
    boolean playWhenReadyInitial = player.getPlayWhenReady();
    @Player.PlaybackSuppressionReason
    int suppressionReasonInitial = player.getPlaybackSuppressionReason();
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());
    boolean playWhenReadyFinal = player.getPlayWhenReady();
    @Player.PlaybackSuppressionReason
    int suppressionReasonFinal = player.getPlaybackSuppressionReason();

    assertThat(playWhenReadyInitial).isFalse();
    assertThat(suppressionReasonInitial)
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS);
    assertThat(playWhenReadyFinal).isFalse();
    assertThat(suppressionReasonFinal)
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS);
    InOrder inOrder = inOrder(listener);
    inOrder
        .verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
    inOrder
        .verify(listener)
        .onPlaybackSuppressionReasonChanged(
            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS);
    inOrder
        .verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
    verify(listener, never())
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS);
  }

  @Test
  public void audioFocusLoss_whilePaused_rereportsPausedWithFocusLoss() throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    Listener listener = mock(Player.Listener.class);
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    player.addListener(listener);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();
    play(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    player.pause();
    triggerAudioFocusChangeListener(
        playerInfo.getAudioFocusListenerLooper(), AudioManager.AUDIOFOCUS_LOSS);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    verify(listener, never()).onPlaybackSuppressionReasonChanged(anyInt());
    InOrder inOrder = inOrder(listener);
    inOrder
        .verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
    inOrder
        .verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
    inOrder
        .verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS);
  }

  @Test
  public void transientLossAndGain_whilePaused_suppressesPlayback() throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    Listener listener = mock(Player.Listener.class);
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    player.addListener(listener);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();
    play(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    player.pause();
    triggerAudioFocusChangeListener(
        playerInfo.getAudioFocusListenerLooper(), AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());
    boolean playWhenReady = player.getPlayWhenReady();
    @Player.PlaybackSuppressionReason int suppressionReason = player.getPlaybackSuppressionReason();
    triggerAudioFocusChangeListener(
        playerInfo.getAudioFocusListenerLooper(), AudioManager.AUDIOFOCUS_GAIN);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());
    boolean playWhenReadyAfterGain = player.getPlayWhenReady();
    @Player.PlaybackSuppressionReason
    int suppressionReasonAfterGain = player.getPlaybackSuppressionReason();

    assertThat(playWhenReady).isFalse();
    assertThat(playWhenReadyAfterGain).isFalse();
    assertThat(suppressionReason)
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS);
    assertThat(suppressionReasonAfterGain).isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    InOrder inOrder = inOrder(listener);
    inOrder
        .verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
    inOrder
        .verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
    inOrder
        .verify(listener)
        .onPlaybackSuppressionReasonChanged(
            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS);
    inOrder
        .verify(listener)
        .onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    verify(listener, never())
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS);
  }

  @Test
  public void play_duringTransientLossWhilePaused_continuesPlayback() throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    Listener listener = mock(Player.Listener.class);
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    player.addListener(listener);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();
    play(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    player.pause();
    triggerAudioFocusChangeListener(
        playerInfo.getAudioFocusListenerLooper(), AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());
    player.play();
    boolean playWhenReadyInitial = player.getPlayWhenReady();
    @Player.PlaybackSuppressionReason
    int suppressionReasonInitial = player.getPlaybackSuppressionReason();
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());
    boolean playWhenReadyFinal = player.getPlayWhenReady();
    @Player.PlaybackSuppressionReason
    int suppressionReasonFinal = player.getPlaybackSuppressionReason();

    assertThat(playWhenReadyInitial).isTrue();
    assertThat(suppressionReasonInitial).isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    assertThat(playWhenReadyFinal).isTrue();
    assertThat(suppressionReasonFinal).isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    InOrder inOrder = inOrder(listener);
    inOrder
        .verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
    inOrder
        .verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
    inOrder
        .verify(listener)
        .onPlaybackSuppressionReasonChanged(
            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS);
    inOrder
        .verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
    inOrder
        .verify(listener)
        .onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    verify(listener, never())
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS);
  }

  @Test
  public void play_duringTransientLossWhilePlaying_continuesPlayback() throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    Listener listener = mock(Player.Listener.class);
    PlayerInfo playerInfo = createPlayerInfo();
    player = playerInfo.getPlayer();
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    player.addListener(listener);
    setMediaItem(playerInfo, FAKE_MEDIA_ITEM);
    player.prepare();
    play(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());

    triggerAudioFocusChangeListener(
        playerInfo.getAudioFocusListenerLooper(), AudioManager.AUDIOFOCUS_LOSS_TRANSIENT);
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());
    player.play();
    boolean playWhenReadyInitial = player.getPlayWhenReady();
    @Player.PlaybackSuppressionReason
    int suppressionReasonInitial = player.getPlaybackSuppressionReason();
    advance(player)
        .untilPendingCommandsAreFullyHandled(playerInfo.getClock(), playerInfo.getPlaybackLooper());
    boolean playWhenReadyFinal = player.getPlayWhenReady();
    @Player.PlaybackSuppressionReason
    int suppressionReasonFinal = player.getPlaybackSuppressionReason();

    assertThat(playWhenReadyInitial).isTrue();
    assertThat(suppressionReasonInitial).isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    assertThat(playWhenReadyFinal).isTrue();
    assertThat(suppressionReasonFinal).isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    InOrder inOrder = inOrder(listener);
    inOrder
        .verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
    inOrder
        .verify(listener)
        .onPlaybackSuppressionReasonChanged(
            Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS);
    inOrder
        .verify(listener)
        .onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    verify(listener, never())
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
