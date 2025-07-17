/*
 * Copyright 2024 The Android Open Source Project
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

import static androidx.media3.test.utils.TestUtil.repeatFlakyTest;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Player.Listener;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link AnalyticsListener#onAudioPositionAdvancing(AnalyticsListener.EventTime,
 * long)} callback.
 */
// Deliberately using System.currentTimeMillis for consistency with onAudioPositionAdvancing.
@SuppressWarnings("NowMillisWithoutTimeSource")
@RunWith(AndroidJUnit4.class)
public class AudioPositionAdvancingTest {

  private static final int TIMEOUT_MS = 10_000;

  // Regression test for b/378871275
  @Test
  public void calledWhenPlaybackResumesNotPaused() throws Exception {
    MediaItem mediaItem = MediaItem.fromUri("asset:///media/mp3/bear-id3.mp3");
    List<Long> playoutStartSystemTimes = Collections.synchronizedList(new ArrayList<>());
    ConditionVariable onAdvancingCalled = new ConditionVariable();
    AnalyticsListener analyticsListener =
        new AnalyticsListener() {
          @Override
          public void onAudioPositionAdvancing(EventTime eventTime, long playoutStartSystemTimeMs) {
            playoutStartSystemTimes.add(playoutStartSystemTimeMs);
            onAdvancingCalled.open();
          }
        };
    AtomicReference<PlaybackException> playbackException = new AtomicReference<>();
    Player.Listener playerListener =
        new Listener() {
          @Override
          public void onPlayerError(PlaybackException error) {
            playbackException.set(error);
            onAdvancingCalled.open();
          }
        };
    AtomicReference<ExoPlayer> player = new AtomicReference<>();
    AtomicLong playbackTriggeredSystemTimeMs = new AtomicLong(C.TIME_UNSET);
    getInstrumentation()
        .runOnMainSync(
            () -> {
              player.set(new ExoPlayer.Builder(getInstrumentation().getContext()).build());
              player.get().addAnalyticsListener(analyticsListener);
              player.get().addListener(playerListener);
              player.get().setMediaItem(mediaItem);
              player.get().prepare();
              playbackTriggeredSystemTimeMs.set(System.currentTimeMillis());
              player.get().play();
            });

    assertThat(onAdvancingCalled.block(TIMEOUT_MS)).isTrue();
    assertWithMessage("Unexpected playback exception thrown: ")
        .that(playbackException.get())
        .isNull();
    long currentTimeMs = System.currentTimeMillis();
    long playoutStartSystemTimeMs = Iterables.getOnlyElement(playoutStartSystemTimes);
    assertThat(playoutStartSystemTimeMs).isAtLeast(playbackTriggeredSystemTimeMs.get());
    assertThat(playoutStartSystemTimeMs).isAtMost(currentTimeMs);

    onAdvancingCalled.close();
    getInstrumentation().runOnMainSync(() -> player.get().pause());

    // Expect the callback to *not* be called.
    assertThat(onAdvancingCalled.block(50)).isFalse();
    assertWithMessage("Unexpected playback exception thrown: ")
        .that(playbackException.get())
        .isNull();

    getInstrumentation()
        .runOnMainSync(
            () -> {
              playbackTriggeredSystemTimeMs.set(System.currentTimeMillis());
              player.get().play();
            });
    assertThat(onAdvancingCalled.block(TIMEOUT_MS)).isTrue();
    assertWithMessage("Unexpected playback exception thrown: ")
        .that(playbackException.get())
        .isNull();
    currentTimeMs = System.currentTimeMillis();
    playoutStartSystemTimeMs = Iterables.getLast(playoutStartSystemTimes);
    assertThat(playoutStartSystemTimeMs).isAtLeast(playbackTriggeredSystemTimeMs.get());
    assertThat(playoutStartSystemTimeMs).isAtMost(currentTimeMs);

    getInstrumentation().runOnMainSync(() -> player.get().release());
  }

  @Test
  public void pauseThenPlayInSameLooperIteration() throws Exception {
    MediaItem mediaItem = MediaItem.fromUri("asset:///media/mp3/bear-id3.mp3");
    List<Long> playoutStartSystemTimes = Collections.synchronizedList(new ArrayList<>());
    ConditionVariable onAdvancingCalled = new ConditionVariable();
    AnalyticsListener analyticsListener =
        new AnalyticsListener() {
          @Override
          public void onAudioPositionAdvancing(EventTime eventTime, long playoutStartSystemTimeMs) {
            playoutStartSystemTimes.add(playoutStartSystemTimeMs);
            onAdvancingCalled.open();
          }
        };
    AtomicReference<PlaybackException> playbackException = new AtomicReference<>();
    Player.Listener playerListener =
        new Listener() {
          @Override
          public void onPlayerError(PlaybackException error) {
            playbackException.set(error);
            onAdvancingCalled.open();
          }
        };
    AtomicReference<ExoPlayer> player = new AtomicReference<>();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              player.set(new ExoPlayer.Builder(getInstrumentation().getContext()).build());
              player.get().addAnalyticsListener(analyticsListener);
              player.get().addListener(playerListener);
              player.get().setMediaItem(mediaItem);
              player.get().prepare();
              player.get().play();
            });

    assertThat(onAdvancingCalled.block(TIMEOUT_MS)).isTrue();
    assertWithMessage("Unexpected playback exception thrown: ")
        .that(playbackException.get())
        .isNull();
    onAdvancingCalled.close();

    AtomicLong playbackTriggeredSystemTimeMs = new AtomicLong(C.TIME_UNSET);
    getInstrumentation()
        .runOnMainSync(
            () -> {
              player.get().pause();
              playbackTriggeredSystemTimeMs.set(System.currentTimeMillis());
              player.get().play();
            });

    assertThat(onAdvancingCalled.block(TIMEOUT_MS)).isTrue();
    assertWithMessage("Unexpected playback exception thrown: ")
        .that(playbackException.get())
        .isNull();
    long currentTimeMs = System.currentTimeMillis();
    assertThat(playoutStartSystemTimes).hasSize(2);
    long playoutStartSystemTimeMs = playoutStartSystemTimes.get(1);
    assertThat(playoutStartSystemTimeMs).isAtLeast(playbackTriggeredSystemTimeMs.get());
    assertThat(playoutStartSystemTimeMs).isAtMost(currentTimeMs);

    getInstrumentation().runOnMainSync(() -> player.get().release());
  }

  @Test
  public void playFromStart_reportedStartTimeEventuallyMatchesPlaybackPosition() throws Exception {
    // Try up to two times as the audio track behavior is not fully reliable and can't be
    // controlled, which results in a 1-2% flakiness rate.
    repeatFlakyTest(
        /* maxRepetitions= */ 2,
        () -> {
          MediaItem mediaItem = MediaItem.fromUri("asset:///media/mp3/bear-id3.mp3");
          AtomicLong reportedStartSystemTimeMs = new AtomicLong();
          ConditionVariable onAdvancingCalled = new ConditionVariable();
          AnalyticsListener analyticsListener =
              new AnalyticsListener() {
                @Override
                public void onAudioPositionAdvancing(
                    EventTime eventTime, long playoutStartSystemTimeMs) {
                  reportedStartSystemTimeMs.set(playoutStartSystemTimeMs);
                  onAdvancingCalled.open();
                }
              };
          AtomicReference<PlaybackException> playbackException = new AtomicReference<>();
          Player.Listener playerListener =
              new Listener() {
                @Override
                public void onPlayerError(PlaybackException error) {
                  playbackException.set(error);
                  onAdvancingCalled.open();
                }
              };
          AtomicReference<ExoPlayer> playerRef = new AtomicReference<>();
          AtomicLong calculatedStartSystemTimeMs = new AtomicLong();
          ConditionVariable calculationMessageHandled = new ConditionVariable();
          getInstrumentation()
              .runOnMainSync(
                  () -> {
                    ExoPlayer player =
                        new ExoPlayer.Builder(getInstrumentation().getContext()).build();
                    playerRef.set(player);
                    player.addAnalyticsListener(analyticsListener);
                    player.addListener(playerListener);
                    player.setMediaItem(mediaItem);
                    player.prepare();
                    // Assume that after 1 second, the playback position is reliable enough to
                    // calculate the actual start time manually.
                    player
                        .createMessage(
                            (type, message) -> {
                              calculatedStartSystemTimeMs.set(
                                  System.currentTimeMillis() - player.getCurrentPosition());
                              calculationMessageHandled.open();
                            })
                        .setLooper(Looper.getMainLooper())
                        .setPosition(1000)
                        .send();
                    player.play();
                  });

          assertThat(onAdvancingCalled.block(TIMEOUT_MS)).isTrue();
          assertWithMessage("Unexpected playback exception thrown: ")
              .that(playbackException.get())
              .isNull();
          assertThat(calculationMessageHandled.block(TIMEOUT_MS)).isTrue();
          assertThat(reportedStartSystemTimeMs.get())
              .isWithin(10)
              .of(calculatedStartSystemTimeMs.get());

          getInstrumentation().runOnMainSync(() -> playerRef.get().release());
        });
  }
}
