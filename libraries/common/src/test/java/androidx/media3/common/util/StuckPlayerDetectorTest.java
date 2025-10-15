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
package androidx.media3.common.util;

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
  private static final int TEST_STUCK_PLAYING_TIMEOUT_MS = 200_000;
  private static final int TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS = 400_000;
  private static final int TEST_STUCK_SUPPRESSED_TIMEOUT_MS = 300_000;

  private StuckPlayerDetector.Callback callback;
  private TestPlayer player;
  private FakeClock clock;
  private StuckPlayerDetector detector;

  @Before
  public void setUp() {
    callback = mock(StuckPlayerDetector.Callback.class);
    clock = new FakeClock(/* initialTimeMs= */ 0);
    player = new TestPlayer(clock);
    detector =
        new StuckPlayerDetector(
            player,
            callback,
            clock,
            TEST_STUCK_BUFFERING_TIMEOUT_MS,
            TEST_STUCK_PLAYING_TIMEOUT_MS,
            TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS,
            TEST_STUCK_SUPPRESSED_TIMEOUT_MS);
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
            new StuckPlayerException(
                StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS, TEST_STUCK_BUFFERING_TIMEOUT_MS));
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
            new StuckPlayerException(
                StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS, TEST_STUCK_BUFFERING_TIMEOUT_MS));
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
            new StuckPlayerException(
                StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS, TEST_STUCK_BUFFERING_TIMEOUT_MS));
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
            new StuckPlayerException(
                StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS, TEST_STUCK_BUFFERING_TIMEOUT_MS));
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
    // Disable playing timeout to not trigger it when in STATE_READY in this test.
    configureDetectorWithCustomPlayingTimeoutMs(Integer.MAX_VALUE);
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

  @Test
  public void stuckPlayingDetection_stuckInReadyStateWithoutProgress_triggersTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_TIMEOUT_MS);

    verify(callback)
        .onStuckPlayerDetected(
            new StuckPlayerException(
                StuckPlayerException.STUCK_PLAYING_NO_PROGRESS, TEST_STUCK_PLAYING_TIMEOUT_MS));
  }

  @Test
  public void stuckPlayingDetection_playingWithProgress_doesNotTriggerTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_TIMEOUT_MS / 2);
    player.setState(player.buildUponState().setContentPositionMs(getConstant(1000)).build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_TIMEOUT_MS / 2);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckPlayingDetection_stuckInReadyStateAfterProgress_triggersTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_TIMEOUT_MS / 2);
    player.setState(
        player
            .buildUponState()
            .setContentPositionMs(getConstant(1000))
            // Do something else too so that the player gets an update.
            .setNewlyRenderedFirstFrame(true)
            .build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_TIMEOUT_MS);

    verify(callback)
        .onStuckPlayerDetected(
            new StuckPlayerException(
                StuckPlayerException.STUCK_PLAYING_NO_PROGRESS, TEST_STUCK_PLAYING_TIMEOUT_MS));
  }

  @Test
  public void stuckPlayingDetection_notPlayWhenReady_doesNotTriggerTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_TIMEOUT_MS);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckPlayingDetection_playbackSuppressed_doesNotTriggerTimeout() {
    // Disable suppressed timeout to not trigger it when suppressed in this test.
    configureDetectorWithCustomSuppressedTimeoutMs(Integer.MAX_VALUE);
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackSuppressionReason(
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_TIMEOUT_MS);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckPlayingDetection_periodTransition_doesNotTriggerTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_TIMEOUT_MS / 2);
    player.setState(
        player
            .buildUponState()
            .setPlaylist(
                ImmutableList.of(new SimpleBasePlayer.MediaItemData.Builder("newUid").build()))
            .build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_TIMEOUT_MS / 2);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckPlayingDetection_adTransition_doesNotTriggerTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
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

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_TIMEOUT_MS / 2);
    player.setState(
        player
            .buildUponState()
            .setCurrentAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0)
            .build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_TIMEOUT_MS / 2);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckPlayingDetection_buffering_cancelsTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_TIMEOUT_MS / 2);
    player.setState(player.buildUponState().setPlaybackState(Player.STATE_BUFFERING).build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_TIMEOUT_MS);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckPlayingDetection_ended_cancelsTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_TIMEOUT_MS / 2);
    player.setState(player.buildUponState().setPlaybackState(Player.STATE_ENDED).build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_TIMEOUT_MS / 2);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckPlayingDetection_customTimeout_isRespected() {
    int customTimeoutMs = TEST_STUCK_PLAYING_TIMEOUT_MS / 2;
    configureDetectorWithCustomPlayingTimeoutMs(customTimeoutMs);
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build());

    advanceTimeAndIdleMainLooper(clock, customTimeoutMs);

    verify(callback)
        .onStuckPlayerDetected(
            new StuckPlayerException(
                StuckPlayerException.STUCK_PLAYING_NO_PROGRESS, customTimeoutMs));
  }

  @Test
  public void stuckPlayingNotEnding_unknownDuration_doesNotTriggerTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(
                ImmutableList.of(new SimpleBasePlayer.MediaItemData.Builder("uid").build()))
            .setContentPositionMs(10_000)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckPlayingNotEnding_playbackProgressBeyondDuration_triggersTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(
                ImmutableList.of(
                    new SimpleBasePlayer.MediaItemData.Builder("uid")
                        .setDurationUs(10_000_000)
                        .build()))
            .setContentPositionMs(10_000)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS);

    verify(callback)
        .onStuckPlayerDetected(
            new StuckPlayerException(
                StuckPlayerException.STUCK_PLAYING_NOT_ENDING,
                TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS));
  }

  @Test
  public void
      stuckPlayingNotEnding_playbackBeforeDuration_doesOnlyTriggerTimeoutForTimeAfterDuration() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(
                ImmutableList.of(
                    new SimpleBasePlayer.MediaItemData.Builder("uid")
                        .setDurationUs(10_000_000)
                        .build()))
            .setContentPositionMs(5_000)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS);

    verify(callback, never()).onStuckPlayerDetected(any());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS);

    verify(callback)
        .onStuckPlayerDetected(
            new StuckPlayerException(
                StuckPlayerException.STUCK_PLAYING_NOT_ENDING,
                TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS));
  }

  @Test
  public void stuckPlayingNotEnding_notPlayWhenReady_cancelsTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(
                ImmutableList.of(
                    new SimpleBasePlayer.MediaItemData.Builder("uid")
                        .setDurationUs(10_000_000)
                        .build()))
            .setContentPositionMs(11_000)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS / 2);
    player.setState(
        player
            .buildUponState()
            .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckPlayingNotEnding_playbackSuppressed_cancelsTimeout() {
    // Disable suppressed timeout to not trigger it when suppressed in this test.
    configureDetectorWithCustomSuppressedTimeoutMs(Integer.MAX_VALUE);
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(
                ImmutableList.of(
                    new SimpleBasePlayer.MediaItemData.Builder("uid")
                        .setDurationUs(10_000_000)
                        .build()))
            .setContentPositionMs(11_000)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS / 2);
    player.setState(
        player
            .buildUponState()
            .setPlaybackSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING)
            .build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckPlayingNotEnding_buffering_cancelsTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(
                ImmutableList.of(
                    new SimpleBasePlayer.MediaItemData.Builder("uid")
                        .setDurationUs(10_000_000)
                        .build()))
            .setContentPositionMs(11_000)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS / 2);
    player.setState(player.buildUponState().setPlaybackState(Player.STATE_BUFFERING).build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckPlayingNotEnding_ended_cancelsTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(
                ImmutableList.of(
                    new SimpleBasePlayer.MediaItemData.Builder("uid")
                        .setDurationUs(10_000_000)
                        .build()))
            .setContentPositionMs(11_000)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS / 2);
    player.setState(player.buildUponState().setPlaybackState(Player.STATE_ENDED).build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckPlayingNotEnding_idle_cancelsTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(
                ImmutableList.of(
                    new SimpleBasePlayer.MediaItemData.Builder("uid")
                        .setDurationUs(10_000_000)
                        .build()))
            .setContentPositionMs(11_000)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS / 2);
    player.setState(player.buildUponState().setPlaybackState(Player.STATE_IDLE).build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckPlayingNotEnding_updatedLongerDuration_cancelsTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(
                ImmutableList.of(
                    new SimpleBasePlayer.MediaItemData.Builder("uid")
                        .setDurationUs(10_000_000)
                        .build()))
            .setContentPositionMs(11_000)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS / 2);
    player.setState(
        player
            .buildUponState()
            .setPlaylist(
                ImmutableList.of(
                    new SimpleBasePlayer.MediaItemData.Builder("uid")
                        .setDurationUs(999_000_000_000L)
                        .build()))
            .build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckPlayingNotEnding_periodTransition_doesNotTriggerTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(
                ImmutableList.of(
                    new SimpleBasePlayer.MediaItemData.Builder("uid1")
                        .setPeriods(
                            ImmutableList.of(
                                new SimpleBasePlayer.PeriodData.Builder("periodUid1")
                                    .setDurationUs(10_000_000)
                                    .build()))
                        .build(),
                    new SimpleBasePlayer.MediaItemData.Builder("uid2")
                        .setPeriods(
                            ImmutableList.of(
                                new SimpleBasePlayer.PeriodData.Builder("periodUid2")
                                    .setDurationUs(10_000_000)
                                    .build()))
                        .build()))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(11_000)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS / 2);
    player.setState(
        player.buildUponState().setCurrentMediaItemIndex(1).setContentPositionMs(2_000).build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckPlayingNotEnding_adDurationExceeded_triggersTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(
                ImmutableList.of(
                    new SimpleBasePlayer.MediaItemData.Builder("uid")
                        .setPeriods(
                            ImmutableList.of(
                                new SimpleBasePlayer.PeriodData.Builder("periodUid")
                                    .setAdPlaybackState(
                                        new AdPlaybackState(
                                                "adsId", /* adGroupTimesUs...= */ 5_000_000)
                                            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                                            .withAdDurationsUs(
                                                /* adGroupIndex= */ 0, /* adDurationsUs...= */
                                                5_000_000))
                                    .build()))
                        .build()))
            .setCurrentAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0)
            .setContentPositionMs(8_000)
            .setAdPositionMs(6000)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS);

    verify(callback)
        .onStuckPlayerDetected(
            new StuckPlayerException(
                StuckPlayerException.STUCK_PLAYING_NOT_ENDING,
                TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS));
  }

  @Test
  public void stuckPlayingNotEnding_customTimeoutIsRespected() {
    int customTimeoutMs = TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS / 2;
    configureDetectorWithCustomPlayingNotEndingTimeoutMs(customTimeoutMs);
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(
                ImmutableList.of(
                    new SimpleBasePlayer.MediaItemData.Builder("uid")
                        .setDurationUs(10_000_000)
                        .build()))
            .setContentPositionMs(11_000)
            .build());

    advanceTimeAndIdleMainLooper(clock, customTimeoutMs);

    verify(callback)
        .onStuckPlayerDetected(
            new StuckPlayerException(
                StuckPlayerException.STUCK_PLAYING_NOT_ENDING, customTimeoutMs));
  }

  @Test
  public void stuckSuppressed_ready_triggersTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackSuppressionReason(
                Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_SUPPRESSED_TIMEOUT_MS);

    verify(callback)
        .onStuckPlayerDetected(
            new StuckPlayerException(
                StuckPlayerException.STUCK_SUPPRESSED, TEST_STUCK_SUPPRESSED_TIMEOUT_MS));
  }

  @Test
  public void stuckSuppressed_buffering_triggersTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_BUFFERING)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackSuppressionReason(
                Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_SUPPRESSED_TIMEOUT_MS);

    verify(callback)
        .onStuckPlayerDetected(
            new StuckPlayerException(
                StuckPlayerException.STUCK_SUPPRESSED, TEST_STUCK_SUPPRESSED_TIMEOUT_MS));
  }

  @Test
  public void stuckSuppressed_transientAudioFocusLoss_doesNotTriggerTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackSuppressionReason(
                Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_SUPPRESSED_TIMEOUT_MS);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckSuppressed_notPlayWhenReady_doesNotTriggerTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackSuppressionReason(
                Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_SUPPRESSED_TIMEOUT_MS);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckSuppressed_ended_doesNotTriggerTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_ENDED)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackSuppressionReason(
                Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_SUPPRESSED_TIMEOUT_MS);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckSuppressed_idle_doesNotTriggerTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_IDLE)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackSuppressionReason(
                Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_SUPPRESSED_TIMEOUT_MS);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckSuppressed_suppressionEnds_cancelsTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackSuppressionReason(
                Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_SUPPRESSED_TIMEOUT_MS / 2);

    // Remove suppression reason
    player.setState(
        player
            .buildUponState()
            .setPlaybackSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_NONE)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_SUPPRESSED_TIMEOUT_MS);

    verify(callback, never()).onStuckPlayerDetected(any());
  }

  @Test
  public void stuckSuppressed_suppressionReasonChanges_restartsTimeout() {
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackSuppressionReason(
                Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT)
            .build());

    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_SUPPRESSED_TIMEOUT_MS / 2);

    player.setState(
        player
            .buildUponState()
            .setPlaybackSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING)
            .build());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_SUPPRESSED_TIMEOUT_MS / 2);
    // Should not have triggered yet
    verify(callback, never()).onStuckPlayerDetected(any());
    advanceTimeAndIdleMainLooper(clock, TEST_STUCK_SUPPRESSED_TIMEOUT_MS / 2);

    verify(callback)
        .onStuckPlayerDetected(
            new StuckPlayerException(
                StuckPlayerException.STUCK_SUPPRESSED, TEST_STUCK_SUPPRESSED_TIMEOUT_MS));
  }

  @Test
  public void stuckSuppressed_customTimeoutIsRespected() {
    int customTimeoutMs = TEST_STUCK_SUPPRESSED_TIMEOUT_MS / 2;
    configureDetectorWithCustomSuppressedTimeoutMs(customTimeoutMs);
    player.setState(
        player
            .buildUponState()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackSuppressionReason(
                Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT)
            .build());

    advanceTimeAndIdleMainLooper(clock, customTimeoutMs);

    verify(callback)
        .onStuckPlayerDetected(
            new StuckPlayerException(StuckPlayerException.STUCK_SUPPRESSED, customTimeoutMs));
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
    detector =
        new StuckPlayerDetector(
            player,
            callback,
            clock,
            customTimeoutMs,
            TEST_STUCK_PLAYING_TIMEOUT_MS,
            TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS,
            TEST_STUCK_SUPPRESSED_TIMEOUT_MS);
  }

  private void configureDetectorWithCustomPlayingTimeoutMs(int customTimeoutMs) {
    detector.release();
    detector =
        new StuckPlayerDetector(
            player,
            callback,
            clock,
            TEST_STUCK_BUFFERING_TIMEOUT_MS,
            customTimeoutMs,
            TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS,
            TEST_STUCK_SUPPRESSED_TIMEOUT_MS);
  }

  private void configureDetectorWithCustomPlayingNotEndingTimeoutMs(int customTimeoutMs) {
    detector.release();
    detector =
        new StuckPlayerDetector(
            player,
            callback,
            clock,
            TEST_STUCK_BUFFERING_TIMEOUT_MS,
            TEST_STUCK_PLAYING_TIMEOUT_MS,
            customTimeoutMs,
            TEST_STUCK_SUPPRESSED_TIMEOUT_MS);
  }

  private void configureDetectorWithCustomSuppressedTimeoutMs(int customTimeoutMs) {
    detector.release();
    detector =
        new StuckPlayerDetector(
            player,
            callback,
            clock,
            TEST_STUCK_BUFFERING_TIMEOUT_MS,
            TEST_STUCK_PLAYING_TIMEOUT_MS,
            TEST_STUCK_PLAYING_NOT_ENDING_TIMEOUT_MS,
            customTimeoutMs);
  }

  private static void advanceTimeAndIdleMainLooper(FakeClock clock, long timeMs) {
    clock.advanceTime(timeMs);
    idleMainLooper();
  }
}
