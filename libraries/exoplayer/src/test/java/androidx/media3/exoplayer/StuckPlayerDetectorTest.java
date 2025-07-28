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
package androidx.media3.exoplayer;

import static androidx.media3.common.SimpleBasePlayer.PositionSupplier.getConstant;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.robolectric.shadows.ShadowLooper.idleMainLooper;

import android.os.Looper;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.util.Clock;
import androidx.media3.test.utils.FakeClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link StuckPlayerDetector}. */
@RunWith(AndroidJUnit4.class)
public final class StuckPlayerDetectorTest {

  private static final int TEST_STUCK_BUFFERING_TIMEOUT_MS = 600_000;

  private StuckPlayerDetector.Callback callback;
  private TestPlayer player;
  private FakeClock clock;
  private StuckPlayerDetector detector;

  @Before
  public void setUp() {
    callback = mock(StuckPlayerDetector.Callback.class);
    clock = new FakeClock(/* initialTimeMs= */ 0);
    player = new TestPlayer(clock);
    detector = new StuckPlayerDetector(player, callback, clock, TEST_STUCK_BUFFERING_TIMEOUT_MS);
  }

  @After
  public void tearDown() {
    detector.release();
  }

  @Test
  public void stuckBufferingDetection_stuckInBufferingInitially_triggersTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS);

    verify(callback)
        .onStuckPlayerDetected(
            new StuckPlayerException(StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS));
  }

  @Test
  public void stuckBufferingDetection_bufferingWithProgress_doesNotTriggerTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);
    player.setState(
        player.buildUponState().setContentBufferedPositionMs(getConstant(1000)).build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckBufferingDetection_stuckInBufferingAfterProgress_triggersTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);
    player.setState(
        player
            .buildUponState()
            .setContentBufferedPositionMs(getConstant(1000))
            // Do something else too so that the player gets an update.
            .setNewlyRenderedFirstFrame(true)
            .build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS);

    verify(callback)
        .onStuckPlayerDetected(
            new StuckPlayerException(StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS));
  }

  @Test
  public void stuckBufferingDetection_bufferingWithProgressInOtherPeriod_doesNotTriggerTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(
                ImmutableList.of(
                    new SimpleBasePlayer.MediaItemData.Builder("uid1").build(),
                    new SimpleBasePlayer.MediaItemData.Builder("uid2").build()))
            .setContentPositionMs(getConstant(500))
            .setContentBufferedPositionMs(getConstant(1000))
            .setTotalBufferedDurationMs(getConstant(2000))
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);
    player.setState(player.buildUponState().setTotalBufferedDurationMs(getConstant(2500)).build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckBufferingDetection_stuckInBufferWithoutProgressInOtherPeriod_triggersTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(
                ImmutableList.of(
                    new SimpleBasePlayer.MediaItemData.Builder("uid1").build(),
                    new SimpleBasePlayer.MediaItemData.Builder("uid2").build()))
            .setContentPositionMs(getConstant(500))
            .setContentBufferedPositionMs(getConstant(1000))
            .setTotalBufferedDurationMs(getConstant(2000))
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS);

    verify(callback)
        .onStuckPlayerDetected(
            new StuckPlayerException(StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS));
  }

  @Test
  public void stuckBufferingDetection_notPlayWhenReady_doesNotTriggerTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckBufferingDetection_playbackSuppressed_doesNotTriggerTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackSuppressionReason(
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckBufferingDetection_liveUpdateWithoutBufferingProgress_triggersTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(
                ImmutableList.of(
                    new SimpleBasePlayer.MediaItemData.Builder("uid")
                        .setIsDynamic(true)
                        .setPositionInFirstPeriodUs(4_000_000)
                        .build()))
            .setContentBufferedPositionMs(getConstant(6_000))
            .build());

    // Live update that moves the live window without affecting the buffered position in the period.
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);
    player.setState(
        player
            .buildUponState()
            .setPlaylist(
                ImmutableList.of(
                    new SimpleBasePlayer.MediaItemData.Builder("uid")
                        .setIsDynamic(true)
                        .setPositionInFirstPeriodUs(8_000_000)
                        .build()))
            .setContentBufferedPositionMs(getConstant(2_000))
            .build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);

    verify(callback)
        .onStuckPlayerDetected(
            new StuckPlayerException(StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS));
  }

  @Test
  public void stuckBufferingDetection_liveUpdateWithBufferingProgress_doesNotTriggerTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(
                ImmutableList.of(
                    new SimpleBasePlayer.MediaItemData.Builder("uid")
                        .setIsDynamic(true)
                        .setPositionInFirstPeriodUs(4_000_000)
                        .build()))
            .setContentBufferedPositionMs(getConstant(6_000))
            .build());

    // Live update that moves the live window but keeps the same buffering position in the window.
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);
    player.setState(
        player
            .buildUponState()
            .setPlaylist(
                ImmutableList.of(
                    new SimpleBasePlayer.MediaItemData.Builder("uid")
                        .setIsDynamic(true)
                        .setPositionInFirstPeriodUs(8_000_000)
                        .build()))
            .setContentBufferedPositionMs(getConstant(6_000))
            .build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckBufferingDetection_bufferingWithPeriodTransition_doesNotTriggerTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);
    player.setState(
        player
            .buildUponState()
            .setPlaylist(
                ImmutableList.of(new SimpleBasePlayer.MediaItemData.Builder("newUid").build()))
            .build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckBufferingDetection_ready_cancelsTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);
    player.setState(player.buildUponState().setPlaybackState(Player.STATE_READY).build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckBufferingDetection_ended_cancelsTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);
    player.setState(player.buildUponState().setPlaybackState(Player.STATE_ENDED).build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckBufferingDetection_idle_cancelsTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);
    player.setState(player.buildUponState().setPlaybackState(Player.STATE_IDLE).build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckBufferingDetection_release_cancelsTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);
    detector.release();
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckBufferingDetection_customTimeout_isRespected() {
    int customTimeoutMs = TEST_STUCK_BUFFERING_TIMEOUT_MS / 2;
    configureDetectorWithCustomBufferingTimeoutMs(customTimeoutMs);
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build());

    advanceTimeAndIdleMainLooper(clock, customTimeoutMs);

    verify(callback, atLeastOnce()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckBufferingDetection_adTransition_cancelsTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(
                ImmutableList.of(
                    new SimpleBasePlayer.MediaItemData.Builder("newUid")
                        .setPeriods(
                            ImmutableList.of(
                                new SimpleBasePlayer.PeriodData.Builder("uid")
                                    .setAdPlaybackState(
                                        new AdPlaybackState("adsId", /* adGroupTimesUs...= */ 2000))
                                    .build()))
                        .build()))
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);
    player.setState(
        player
            .buildUponState()
            .setCurrentAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0)
            .build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_BUFFERING_TIMEOUT_MS / 2);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  private static final class TestPlayer extends SimpleBasePlayer {

    private State state;

    public TestPlayer(Clock clock) {
      super(Looper.getMainLooper(), clock);
      state =
          new State.Builder()
              .setAvailableCommands(new Commands.Builder().add(Player.COMMAND_GET_TIMELINE).build())
              .setPlaylist(
                  ImmutableList.of(new SimpleBasePlayer.MediaItemData.Builder("uid").build()))
              .build();
      invalidateState();
    }

    public void setState(State state) {
      this.state = state;
      invalidateState();
      idleMainLooper();
    }

    public State.Builder buildUponState() {
      return state.buildUpon();
    }

    @Override
    protected State getState() {
      return state;
    }
  }

  private void configureDetectorWithCustomBufferingTimeoutMs(int customTimeoutMs) {
    detector.release();
    detector = new StuckPlayerDetector(player, callback, clock, customTimeoutMs);
  }

  private static void advanceTimeAndIdleMainLooper(FakeClock clock, long timeMs) {
    clock.advanceTime(timeMs);
    idleMainLooper();
  }
}
