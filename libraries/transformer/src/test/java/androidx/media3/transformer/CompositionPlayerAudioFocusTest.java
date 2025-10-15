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

package androidx.media3.transformer;

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.play;
import static androidx.media3.transformer.EditedMediaItemSequence.withAudioFrom;
import static androidx.media3.transformer.TestUtil.ASSET_URI_PREFIX;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_RAW;
import static androidx.media3.transformer.TestUtil.createTestCompositionPlayer;
import static androidx.media3.transformer.TestUtil.createTestCompositionPlayerBuilder;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.media.AudioManager;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Player.Listener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.concurrent.TimeoutException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;

/** Unit tests for audio focus handling in {@link CompositionPlayer} */
@RunWith(AndroidJUnit4.class)
public final class CompositionPlayerAudioFocusTest {

  private @MonotonicNonNull CompositionPlayer player;
  private final AudioManager audioManager;

  public CompositionPlayerAudioFocusTest() {
    audioManager = getApplicationContext().getSystemService(AudioManager.class);
  }

  @After
  public void tearDown() {
    if (player != null) {
      player.release();
    }
  }

  @Test
  public void requestAudioFocus_usingBuilderAudioAttributes_doesNotInterruptPlaybackWhenGranted()
      throws PlaybackException, TimeoutException {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);

    Listener listener = mock(Player.Listener.class);
    player =
        createTestCompositionPlayerBuilder()
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
            .build();
    player.addListener(listener);
    player.setComposition(buildSingleItemComposition());
    player.prepare();

    play(player).untilState(Player.STATE_ENDED);

    assertThat(player.getPlayWhenReady()).isTrue();
    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    verify(listener, never()).onPlaybackSuppressionReasonChanged(anyInt());
    verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
  }

  @Test
  public void requestAudioFocus_usingBuilderAudioAttributes_interruptsPlaybackWhenDenied()
      throws TimeoutException {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);

    Listener listener = mock(Player.Listener.class);
    player =
        createTestCompositionPlayerBuilder()
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
            .build();
    player.addListener(listener);
    player.setComposition(buildSingleItemComposition());
    player.prepare();

    play(player).untilPendingCommandsAreFullyHandled();

    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    verify(listener, never()).onPlaybackSuppressionReasonChanged(anyInt());
    verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS);
  }

  @Test
  public void playComposition_withDefaultAudioFocusHandling_ignoresDeniedAudioFocusRequest()
      throws TimeoutException {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);

    Listener listener = mock(Player.Listener.class);
    player = createTestCompositionPlayer();
    player.addListener(listener);
    player.setComposition(buildSingleItemComposition());
    player.prepare();

    play(player).untilPendingCommandsAreFullyHandled();

    assertThat(player.getPlayWhenReady()).isTrue();
    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    verify(listener, never()).onPlaybackSuppressionReasonChanged(anyInt());
    verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
  }

  @Test
  public void transientAudioFocusLossDuck_whilePlaying_continuesPlaybackWithLowerVolume()
      throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    Listener listener = mock(Player.Listener.class);
    AudioSink sink = mock(AudioSink.class);
    player = createTestCompositionPlayerBuilder().setAudioSink(sink).build();
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    player.addListener(listener);
    player.setComposition(buildSingleItemComposition());
    player.prepare();
    play(player).untilPendingCommandsAreFullyHandled();
    // First call happens when setting up the sink when preparing the internal player.
    verify(sink).setVolume(1.0f);

    triggerAudioFocusChangeListener(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
    advance(player).untilPendingCommandsAreFullyHandled();

    assertThat(player.getPlayWhenReady()).isTrue();
    assertThat(player.getPlaybackSuppressionReason())
        .isEqualTo(Player.PLAYBACK_SUPPRESSION_REASON_NONE);
    verify(listener, never()).onPlaybackSuppressionReasonChanged(anyInt());
    verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
    verify(listener, never())
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS);
    // Second call should match AudioFocusManager#VOLUME_MULTIPLIER_DUCK.
    verify(sink).setVolume(0.2f);
  }

  @Test
  public void transientLossDuckAndGain_whilePlaying_restoresOriginalVolume() throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    Listener listener = mock(Player.Listener.class);
    AudioSink sink = mock(AudioSink.class);
    player = createTestCompositionPlayerBuilder().setAudioSink(sink).build();
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    player.addListener(listener);
    player.setComposition(buildSingleItemComposition());
    player.prepare();
    play(player).untilPendingCommandsAreFullyHandled();
    verify(sink).setVolume(1.0f);

    triggerAudioFocusChangeListener(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
    advance(player).untilPendingCommandsAreFullyHandled();
    verify(sink).setVolume(0.2f);

    triggerAudioFocusChangeListener(AudioManager.AUDIOFOCUS_GAIN);
    advance(player).untilPendingCommandsAreFullyHandled();
    verify(sink, times(2)).setVolume(1.0f);

    verify(listener, never()).onPlaybackSuppressionReasonChanged(anyInt());
    verify(listener)
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
    verify(listener, never())
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS);
  }

  @Test
  public void transientLossDuck_whilePaused_lowersVolume() throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    Listener listener = mock(Player.Listener.class);
    AudioSink sink = mock(AudioSink.class);
    player = createTestCompositionPlayerBuilder().setAudioSink(sink).build();
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    player.addListener(listener);
    player.setComposition(buildSingleItemComposition());
    player.prepare();
    play(player).untilPendingCommandsAreFullyHandled();

    player.pause();
    triggerAudioFocusChangeListener(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
    advance(player).untilPendingCommandsAreFullyHandled();

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
    verify(listener, never())
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS);
    verify(sink).setVolume(0.2f);
  }

  @Test
  public void transientLossDuckAndGain_whilePaused_restoresOriginalVolume() throws Exception {
    shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    Listener listener = mock(Player.Listener.class);
    AudioSink sink = mock(AudioSink.class);
    player = createTestCompositionPlayerBuilder().setAudioSink(sink).build();
    player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
    player.addListener(listener);
    player.setComposition(buildSingleItemComposition());
    player.prepare();
    play(player).untilPendingCommandsAreFullyHandled();
    verify(sink).setVolume(1.0f);

    player.pause();
    triggerAudioFocusChangeListener(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
    advance(player).untilPendingCommandsAreFullyHandled();
    verify(sink).setVolume(0.2f);

    triggerAudioFocusChangeListener(AudioManager.AUDIOFOCUS_GAIN);
    advance(player).untilPendingCommandsAreFullyHandled();

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
    verify(listener, never())
        .onPlayWhenReadyChanged(
            /* playWhenReady= */ false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS);
    verify(sink, times(2)).setVolume(1.0f);
  }

  private void triggerAudioFocusChangeListener(int focusChange) {
    shadowOf(audioManager).getLastAudioFocusRequest().listener.onAudioFocusChange(focusChange);
  }

  private static Composition buildSingleItemComposition() {
    // Use raw audio-only assets which can be played in robolectric tests.
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(item));
    return new Composition.Builder(sequence).build();
  }
}
