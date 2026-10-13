/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.test.utils.robolectric;

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Clock;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link TestPlayerRunHelper}. */
@RunWith(AndroidJUnit4.class)
public final class TestPlayerRunHelperTest {

  private Context context;
  private ExoPlayer player;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    player = new TestExoPlayerBuilder(context).build();
  }

  @After
  public void tearDown() {
    player.release();
  }

  @Test
  public void untilBackgroundThreadCondition_afterTimeout_stopsPollingCondition() throws Exception {
    AtomicInteger conditionCheckCount = new AtomicInteger();
    // The timeout is measured in real time, so keep it short.
    assertThrows(
        TimeoutException.class,
        () ->
            advance(player)
                .withTimeoutMs(100)
                .untilBackgroundThreadCondition(
                    () -> {
                      conditionCheckCount.incrementAndGet();
                      return false;
                    }));
    int conditionCheckCountAfterTimeout = conditionCheckCount.get();
    assertThat(conditionCheckCountAfterTimeout).isGreaterThan(0);

    // Keep running the main looper with an auto-advancing clock. A polling runnable that was left
    // behind by the timed out call would be invoked again on every clock advancement.
    player.setMediaSource(new FakeMediaSource(new FakeTimeline()));
    player.prepare();
    player.play();
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 2_000L);

    assertThat(conditionCheckCount.get()).isEqualTo(conditionCheckCountAfterTimeout);
  }

  @Test
  public void untilPlayingAd_withTwoAdsInAdGroup_waitsUntilSpecifiedAdIsPlaying() throws Exception {
    AdPlaybackState adPlaybackState =
        FakeTimeline.createAdPlaybackState(/* adsPerAdGroup= */ 2, /* adGroupTimesUs...= */ 0L);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(adPlaybackState))
                .build());
    player.setMediaSource(new FakeMediaSource(timeline));
    player.prepare();
    player.play();

    advance(player).untilPlayingAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);

    assertThat(player.isPlayingAd()).isTrue();
    assertThat(player.getCurrentAdGroupIndex()).isEqualTo(0);
    assertThat(player.getCurrentAdIndexInAdGroup()).isEqualTo(1);
  }

  @Test
  public void untilPlayingAd_withNegativeIndices_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            advance(player)
                .untilPlayingAd(/* adGroupIndex= */ C.INDEX_UNSET, /* adIndexInAdGroup= */ 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            advance(player)
                .untilPlayingAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ C.INDEX_UNSET));
  }

  @Test
  public void untilAdCompleted_withTwoAdsInAdGroup_waitsUntilFirstAdTransitionsToSecondAd()
      throws Exception {
    AdPlaybackState adPlaybackState =
        FakeTimeline.createAdPlaybackState(/* adsPerAdGroup= */ 2, /* adGroupTimesUs...= */ 0L);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(adPlaybackState))
                .build());
    player.setMediaSource(new FakeMediaSource(timeline));
    player.prepare();
    player.play();

    advance(player).untilAdCompleted(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);

    assertThat(player.isPlayingAd()).isTrue();
    assertThat(player.getCurrentAdGroupIndex()).isEqualTo(0);
    assertThat(player.getCurrentAdIndexInAdGroup()).isEqualTo(1);
  }

  @Test
  public void untilAdCompleted_withNegativeIndices_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            advance(player)
                .untilAdCompleted(/* adGroupIndex= */ C.INDEX_UNSET, /* adIndexInAdGroup= */ 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            advance(player)
                .untilAdCompleted(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ C.INDEX_UNSET));
  }

  @Test
  public void untilAdGroupEnded_withMidrollAdGroup_waitsUntilPlaybackReturnsToContent()
      throws Exception {
    AdPlaybackState adPlaybackState =
        FakeTimeline.createAdPlaybackState(
            /* adsPerAdGroup= */ 2, /* adGroupTimesUs...= */ 2_000_000L);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setWindowPositionInFirstPeriodUs(0L)
                .setAdPlaybackStates(ImmutableList.of(adPlaybackState))
                .build());
    player.setMediaSource(new FakeMediaSource(timeline));
    player.prepare();
    player.play();

    advance(player).untilAdGroupEnded(/* adGroupIndex= */ 0);

    assertThat(player.isPlayingAd()).isFalse();
    assertThat(player.getCurrentAdGroupIndex()).isEqualTo(C.INDEX_UNSET);
    assertThat(player.getCurrentPosition()).isAtLeast(2_000L);
  }

  @Test
  public void untilAdGroupEnded_withNegativeAdGroupIndex_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> advance(player).untilAdGroupEnded(/* adGroupIndex= */ C.INDEX_UNSET));
  }

  @Test
  public void untilAdPositionAtLeast_duringAdPlayback_waitsUntilAdReachesTargetPosition()
      throws Exception {
    AdPlaybackState adPlaybackState =
        FakeTimeline.createAdPlaybackState(/* adsPerAdGroup= */ 1, /* adGroupTimesUs...= */ 0L);
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(adPlaybackState))
                .build());
    player.setMediaSource(new FakeMediaSource(timeline));
    player.prepare();
    player.play();

    advance(player)
        .untilAdPositionAtLeast(
            /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* adPositionMs= */ 2_000L);

    assertThat(player.isPlayingAd()).isTrue();
    assertThat(player.getCurrentAdGroupIndex()).isEqualTo(0);
    assertThat(player.getCurrentAdIndexInAdGroup()).isEqualTo(0);
    assertThat(player.getCurrentPosition()).isAtLeast(2_000L);
  }

  @Test
  public void untilAdPositionAtLeast_withNegativeArguments_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            advance(player)
                .untilAdPositionAtLeast(
                    /* adGroupIndex= */ C.INDEX_UNSET,
                    /* adIndexInAdGroup= */ 0,
                    /* adPositionMs= */ 0L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            advance(player)
                .untilAdPositionAtLeast(
                    /* adGroupIndex= */ 0,
                    /* adIndexInAdGroup= */ C.INDEX_UNSET,
                    /* adPositionMs= */ 0L));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            advance(player)
                .untilAdPositionAtLeast(
                    /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* adPositionMs= */ -1L));
  }

  @Test
  public void untilPosition_duringPlayback_waitsUntilPositionIsReached() throws Exception {
    player.setMediaSource(new FakeMediaSource(new FakeTimeline()));
    player.prepare();
    player.play();

    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 2_000L);

    assertThat(player.getCurrentMediaItemIndex()).isEqualTo(0);
    assertThat(player.getCurrentPosition()).isAtLeast(2_000L);
  }

  @Test
  public void untilClockAdvancementMs_withDefaultFakeClock_advancesClockByAtLeastSpecifiedDuration()
      throws Exception {
    // TestExoPlayerBuilder uses an auto-advancing FakeClock with
    // FakeClock.DEFAULT_MAX_AUTO_ADVANCING_TIME_DIFF_MS by default.
    assertThat(player.getClock()).isInstanceOf(FakeClock.class);
    long initialElapsedRealtimeMs = player.getClock().elapsedRealtime();

    advance(player).untilClockAdvancementMs(2_500L);

    assertThat(player.getClock().elapsedRealtime() - initialElapsedRealtimeMs).isAtLeast(2_500L);
  }

  @Test
  public void
      untilClockAdvancementMs_withCustomMaxAutoAdvancingTimeDiff_advancesClockByAtLeastSpecifiedDuration()
          throws Exception {
    // A FakeClock only auto-advances to the next message if it is due within the configured maximum
    // time difference. Use a much smaller value than the default so the helper is proven to work
    // without relying on FakeClock.DEFAULT_MAX_AUTO_ADVANCING_TIME_DIFF_MS.
    FakeClock fakeClock =
        new FakeClock.Builder().setIsAutoAdvancing(true).setMaxAutoAdvancingTimeDiffMs(10L).build();
    player.release();
    player = new TestExoPlayerBuilder(context).setClock(fakeClock).build();
    long initialElapsedRealtimeMs = fakeClock.elapsedRealtime();

    advance(player).untilClockAdvancementMs(2_500L);

    assertThat(fakeClock.elapsedRealtime() - initialElapsedRealtimeMs).isAtLeast(2_500L);
  }

  @Test
  public void untilClockAdvancementMs_withRealClock_advancesClockByAtLeastSpecifiedDuration()
      throws Exception {
    // Use the real (Robolectric-shadowed) SystemClock instead of a FakeClock. Robolectric advances
    // it when running delayed main Looper tasks, but RobolectricUtil only runs tasks that are due
    // within RobolectricUtil.DEFAULT_MAX_TIME_DIFF_MS, so the helper must stay within that window.
    player.release();
    player = new TestExoPlayerBuilder(context).setClock(Clock.DEFAULT).build();
    long initialElapsedRealtimeMs = Clock.DEFAULT.elapsedRealtime();

    advance(player).untilClockAdvancementMs(2_500L);

    assertThat(Clock.DEFAULT.elapsedRealtime() - initialElapsedRealtimeMs).isAtLeast(2_500L);
  }

  @Test
  public void untilClockAdvancementMs_withNonAutoAdvancingFakeClock_throwsTimeoutException() {
    // A FakeClock that never advances on its own can never satisfy the condition. The timeout is
    // measured in real time, so keep it short.
    player.release();
    player =
        new TestExoPlayerBuilder(context)
            .setClock(new FakeClock(/* isAutoAdvancing= */ false))
            .build();

    assertThrows(
        TimeoutException.class,
        () -> advance(player).withTimeoutMs(100).untilClockAdvancementMs(2_500L));
  }

  @Test
  public void untilClockAdvancementMs_withNegativeDuration_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class, () -> advance(player).untilClockAdvancementMs(-1L));
  }
}
