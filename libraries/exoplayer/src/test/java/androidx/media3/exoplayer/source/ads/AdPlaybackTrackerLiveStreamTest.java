/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.source.ads;

import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.ADS_ID;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.AD_DURATION_US;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.AD_MEDIA_ITEM_0;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.AD_MEDIA_ITEM_1;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.CONTENT_DURATION_US;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.createAdsMediaSource;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.createDefaultAdMediaSourceFactory;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.verifyStandardAdGroupPlaybackEvents;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.test.utils.ExoPlayerTestRunner;
import androidx.media3.test.utils.FakeAdsLoader;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests for {@link AdPlaybackTracker} live stream and clock-driven timeline and {@link
 * AdPlaybackState} updates.
 */
@RunWith(AndroidJUnit4.class)
public final class AdPlaybackTrackerLiveStreamTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private AdPlaybackTracker.SdkBridge mockSdkBridge;

  private AdViewProvider adViewProvider;
  private AdPlaybackTracker adPlaybackTracker;
  private FakeAdsLoader fakeAdsLoader;
  private ExoPlayer player;

  @Before
  public void setUp() {
    adViewProvider = () -> null;
    adPlaybackTracker = new AdPlaybackTracker(mockSdkBridge);
    fakeAdsLoader = new FakeAdsLoader(adPlaybackTracker);
    player = new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build();
    fakeAdsLoader.setPlayer(player);
  }

  @After
  public void tearDown() {
    player.release();
    fakeAdsLoader.release();
  }

  @Test
  public void
      play_liveStreamWithClockDrivenTimelineUpdateWhilePlayingAd_preservesActiveAdAndCompletesBreak()
          throws Exception {
    long initialWindowOffsetUs = 100 * C.MICROS_PER_SECOND;
    TimelineWindowDefinition initialLiveWindow =
        new TimelineWindowDefinition.Builder()
            .setDynamic(true)
            .setLive(true)
            .setSeekable(true)
            .setDefaultPositionUs(0L)
            .setDurationUs(CONTENT_DURATION_US)
            .setWindowPositionInFirstPeriodUs(initialWindowOffsetUs)
            .build();
    AdPlaybackState initialLiveAdState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ initialWindowOffsetUs)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0)
            .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false);
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, initialLiveAdState);
    FakeMediaSource contentMediaSource =
        new FakeMediaSource(new FakeTimeline(initialLiveWindow), ExoPlayerTestRunner.VIDEO_FORMAT);
    AdsMediaSource adsMediaSource =
        createAdsMediaSource(
            contentMediaSource,
            createDefaultAdMediaSourceFactory(),
            fakeAdsLoader,
            adViewProvider,
            ADS_ID);
    player.setMediaSource(adsMediaSource);
    player.prepare();
    player.play();
    advance(player).untilPlayingAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    advance(player).untilState(Player.STATE_READY);
    long startElapsedRealtimeMs = player.getClock().elapsedRealtime();

    // Advance clock by 3s while inside the ad, then refresh the live content Timeline only.
    advance(player).untilClockAdvancementMs(3_000L);
    long elapsedUs = Util.msToUs(player.getClock().elapsedRealtime() - startElapsedRealtimeMs);
    contentMediaSource.setNewSourceInfo(
        new FakeTimeline(
            initialLiveWindow
                .buildUpon()
                .setWindowPositionInFirstPeriodUs(initialWindowOffsetUs + elapsedUs)
                .build()));
    advance(player).untilAdGroupEnded(/* adGroupIndex= */ 0);

    AdPlaybackState stateAfterBreak = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(stateAfterBreak).isNotNull();
    assertThat(stateAfterBreak.getAdGroup(0).states[0]).isEqualTo(AdPlaybackState.AD_STATE_PLAYED);
    InOrder inOrder = inOrder(mockSdkBridge);
    verifyStandardAdGroupPlaybackEvents(
        inOrder,
        mockSdkBridge,
        ADS_ID,
        /* adGroupIndex= */ 0,
        /* startAdIndexInAdGroup= */ 0,
        /* endAdIndexInAdGroup= */ 0);
    verify(mockSdkBridge, never()).onAllAdsCompleted(eq(ADS_ID), any());
  }

  @Test
  public void
      updateAdPlaybackState_liveStreamWithClockDrivenTimelineAndAdStateUpdates_playsMultiAdPodAndNotifiesBridge()
          throws Exception {
    long initialWindowOffsetUs = 100 * C.MICROS_PER_SECOND;
    TimelineWindowDefinition initialLiveWindow =
        new TimelineWindowDefinition.Builder()
            .setDynamic(true)
            .setLive(true)
            .setSeekable(true)
            .setDefaultPositionUs(0L)
            .setDurationUs(CONTENT_DURATION_US)
            .setWindowPositionInFirstPeriodUs(initialWindowOffsetUs)
            .build();
    AdPlaybackState initialLiveAdState =
        new AdPlaybackState(ADS_ID)
            .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false);
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, initialLiveAdState);
    FakeMediaSource contentMediaSource =
        new FakeMediaSource(new FakeTimeline(initialLiveWindow), ExoPlayerTestRunner.VIDEO_FORMAT);
    AdsMediaSource adsMediaSource =
        createAdsMediaSource(
            contentMediaSource,
            createDefaultAdMediaSourceFactory(),
            fakeAdsLoader,
            adViewProvider,
            ADS_ID);
    player.setMediaSource(adsMediaSource);
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_READY);
    long startElapsedRealtimeMs = player.getClock().elapsedRealtime();

    // Wait 3s of real time, then update BOTH the live Timeline and AdPlaybackState as a function
    // of elapsed clock time (announcing a 2-ad pod with ad 0 available and ad 1 unavailable).
    advance(player).untilClockAdvancementMs(3_000L);
    long elapsedUsAtRefresh1 =
        Util.msToUs(player.getClock().elapsedRealtime() - startElapsedRealtimeMs);
    long adPodCuePointPeriodUs =
        initialWindowOffsetUs + elapsedUsAtRefresh1 + 3 * C.MICROS_PER_SECOND;
    contentMediaSource.setNewSourceInfo(
        new FakeTimeline(
            initialLiveWindow
                .buildUpon()
                .setWindowPositionInFirstPeriodUs(initialWindowOffsetUs + elapsedUsAtRefresh1)
                .build()));
    adPlaybackTracker.updateAdPlaybackState(
        ADS_ID,
        state ->
            state
                .withNewAdGroup(/* adGroupIndex= */ 0, adPodCuePointPeriodUs)
                .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 2)
                .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US, AD_DURATION_US)
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0));
    // Advance until ad (0, 0) is playing, wait 2s of clock time inside ad (0, 0), and update
    // both the sliding live Timeline and resolve ad (0, 1) just-in-time.
    advance(player).untilPlayingAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    advance(player).untilState(Player.STATE_READY);
    advance(player).untilClockAdvancementMs(2_000L);
    long elapsedUsAtRefresh2 =
        Util.msToUs(player.getClock().elapsedRealtime() - startElapsedRealtimeMs);
    contentMediaSource.setNewSourceInfo(
        new FakeTimeline(
            initialLiveWindow
                .buildUpon()
                .setWindowPositionInFirstPeriodUs(initialWindowOffsetUs + elapsedUsAtRefresh2)
                .build()));
    adPlaybackTracker.updateAdPlaybackState(
        ADS_ID,
        state ->
            state.withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, AD_MEDIA_ITEM_1));
    // Stop right at the completion of ad (0, 0) and assert mid-pod SdkBridge state.
    advance(player).untilAdCompleted(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);

    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
    verify(mockSdkBridge, never()).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);

    // Advance until the entire ad group (adGroupIndex = 0) ends and returns to live content.
    advance(player).untilAdGroupEnded(/* adGroupIndex= */ 0);

    inOrder
        .verify(mockSdkBridge)
        .onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
    inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    verify(mockSdkBridge, never()).onAllAdsCompleted(eq(ADS_ID), any());
  }
}
