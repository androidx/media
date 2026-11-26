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
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.createVodContentTimeline;
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
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ForwardingTimeline;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;
import androidx.media3.test.utils.ExoPlayerTestRunner;
import androidx.media3.test.utils.FakeAdsLoader;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.concurrent.atomic.AtomicBoolean;
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

  @Test
  public void play_liveStreamTransitioningFromLiveToVod_notifiesAllAdsCompleted() throws Exception {
    TimelineWindowDefinition initialLiveWindow =
        new TimelineWindowDefinition.Builder()
            .setDynamic(true)
            .setLive(true)
            .setSeekable(true)
            .setDefaultPositionUs(0L)
            .setDurationUs(CONTENT_DURATION_US)
            .setWindowPositionInFirstPeriodUs(0L)
            .build();
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, createSinglePrerollLiveAdState());
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
    advance(player).untilAdGroupEnded(/* adGroupIndex= */ 0);
    verify(mockSdkBridge, never()).onAllAdsCompleted(eq(ADS_ID), any());

    // Transition the live window to a static VOD window (e.g., DASH MPD@type="static").
    contentMediaSource.setNewSourceInfo(
        new FakeTimeline(initialLiveWindow.buildUpon().setDynamic(false).setLive(false).build()));
    advance(player).untilTimelineChanges();

    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(finalState).isNotNull();
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, finalState);
  }

  @Test
  public void play_hlsLiveToVodWithIsLiveTrue_notifiesAllAdsCompleted() throws Exception {
    long initialWindowOffsetUs = 100 * C.MICROS_PER_SECOND;
    Timeline initialHlsLiveTimeline =
        createSinglePeriodLiveTimeline(
            /* periodDurationUs= */ C.TIME_UNSET,
            /* windowPositionInPeriodUs= */ initialWindowOffsetUs,
            /* isDynamic= */ true);
    AdPlaybackState initialLiveAdState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ initialWindowOffsetUs)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0)
            .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false);
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, initialLiveAdState);
    FakeMediaSource contentMediaSource =
        new FakeMediaSource(initialHlsLiveTimeline, ExoPlayerTestRunner.VIDEO_FORMAT);
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
    advance(player).untilAdGroupEnded(/* adGroupIndex= */ 0);
    verify(mockSdkBridge, never()).onAllAdsCompleted(eq(ADS_ID), any());

    // HLS #EXT-X-ENDLIST keeps window.isLive() == true while clearing isDynamic and closing
    // periodDurationUs.
    contentMediaSource.setNewSourceInfo(
        createSinglePeriodLiveTimeline(
            /* periodDurationUs= */ initialWindowOffsetUs + CONTENT_DURATION_US,
            /* windowPositionInPeriodUs= */ initialWindowOffsetUs,
            /* isDynamic= */ false));
    advance(player).untilTimelineChanges();

    assertThat(
            player
                .getCurrentTimeline()
                .getWindow(/* windowIndex= */ 0, new Timeline.Window())
                .isLive())
        .isTrue();
    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(finalState).isNotNull();
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, finalState);
  }

  @Test
  public void play_dashEmsgLiveStreamWithUnsetPeriodDuration_suppressesUntilClosed()
      throws Exception {
    Timeline dashEmsgLiveTimeline =
        createSinglePeriodLiveTimeline(
            /* periodDurationUs= */ C.TIME_UNSET,
            /* windowPositionInPeriodUs= */ 0L,
            /* isDynamic= */ false);
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, createSinglePrerollLiveAdState());
    FakeMediaSource contentMediaSource =
        new FakeMediaSource(dashEmsgLiveTimeline, ExoPlayerTestRunner.VIDEO_FORMAT);
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
    advance(player).untilAdGroupEnded(/* adGroupIndex= */ 0);
    Timeline.Window window =
        player.getCurrentTimeline().getWindow(/* windowIndex= */ 0, new Timeline.Window());
    assertThat(window.isDynamic).isFalse();
    assertThat(window.isLive()).isTrue();
    verify(mockSdkBridge, never()).onAllAdsCompleted(eq(ADS_ID), any());

    // DASH-IF IOP Phase 1 live-to-VOD sets finite presentation/period duration while keeping
    // MPD@type="dynamic".
    contentMediaSource.setNewSourceInfo(
        createSinglePeriodLiveTimeline(
            /* periodDurationUs= */ CONTENT_DURATION_US,
            /* windowPositionInPeriodUs= */ 0L,
            /* isDynamic= */ false));
    advance(player).untilTimelineChanges();

    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(finalState).isNotNull();
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, finalState);
  }

  @Test
  public void play_multiPeriodDashEmsgLiveStream_suppressesUntilLastPeriodCloses()
      throws Exception {
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, createSinglePrerollLiveAdState());
    FakeMediaSource contentMediaSource =
        new FakeMediaSource(
            createTwoPeriodLiveTimeline(/* lastPeriodDurationUs= */ C.TIME_UNSET),
            ExoPlayerTestRunner.VIDEO_FORMAT);
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
    advance(player).untilAdGroupEnded(/* adGroupIndex= */ 0);
    assertThat(player.getCurrentPeriodIndex()).isEqualTo(0);
    assertThat(
            player
                .getCurrentTimeline()
                .getPeriod(/* periodIndex= */ 0, new Timeline.Period())
                .durationUs)
        .isEqualTo(CONTENT_DURATION_US / 2);
    verify(mockSdkBridge, never()).onAllAdsCompleted(eq(ADS_ID), any());

    contentMediaSource.setNewSourceInfo(
        createTwoPeriodLiveTimeline(/* lastPeriodDurationUs= */ CONTENT_DURATION_US / 2));
    advance(player).untilTimelineChanges();

    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(finalState).isNotNull();
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, finalState);
  }

  @Test
  public void setPlayer_reattachedAfterTimelineResolvedToVod_notifiesAllAdsCompleted()
      throws Exception {
    Timeline initialHlsLiveTimeline =
        createSinglePeriodLiveTimeline(
            /* periodDurationUs= */ C.TIME_UNSET,
            /* windowPositionInPeriodUs= */ 0L,
            /* isDynamic= */ true);
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, createSinglePrerollLiveAdState());
    FakeMediaSource contentMediaSource =
        new FakeMediaSource(initialHlsLiveTimeline, ExoPlayerTestRunner.VIDEO_FORMAT);
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
    advance(player).untilAdGroupEnded(/* adGroupIndex= */ 0);
    adPlaybackTracker.setPlayer(null);
    contentMediaSource.setNewSourceInfo(createVodContentTimeline());
    advance(player).untilTimelineChanges();
    verify(mockSdkBridge, never()).onAllAdsCompleted(eq(ADS_ID), any());

    adPlaybackTracker.setPlayer(player);

    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(finalState).isNotNull();
    verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, finalState);
  }

  @Test
  public void play_playlistTransitionToVodItemWithLivePostrollPlaceholder_notifiesAllAdsCompleted()
      throws Exception {
    Object adsId1 = "ads-id-1";
    Object adsId2 = "ads-id-2";
    TimelineWindowDefinition vodWindow =
        new TimelineWindowDefinition.Builder()
            .setDynamic(false)
            .setLive(false)
            .setSeekable(true)
            .setDefaultPositionUs(0L)
            .setDurationUs(CONTENT_DURATION_US)
            .setWindowPositionInFirstPeriodUs(0L)
            .build();
    AdPlaybackState vodAdStateWithLivePostrollPlaceholder =
        new AdPlaybackState(adsId2, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0)
            .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false);
    fakeAdsLoader.setInitialAdPlaybackState(adsId1, new AdPlaybackState(adsId1));
    fakeAdsLoader.setInitialAdPlaybackState(adsId2, vodAdStateWithLivePostrollPlaceholder);
    AdsMediaSource adsMediaSource1 =
        createAdsMediaSource(
            new FakeTimeline(vodWindow),
            createDefaultAdMediaSourceFactory(),
            fakeAdsLoader,
            adViewProvider,
            adsId1);
    AdsMediaSource adsMediaSource2 =
        createAdsMediaSource(
            new FakeTimeline(vodWindow),
            createDefaultAdMediaSourceFactory(),
            fakeAdsLoader,
            adViewProvider,
            adsId2);
    player.setMediaSources(ImmutableList.of(adsMediaSource1, adsMediaSource2));
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_READY);
    assertThat(adPlaybackTracker.skipAdGroup(adsId2, /* adGroupIndex= */ 0)).isTrue();
    // Advance past 10s so item 1 buffers within the 50s max buffer and pre-prepares item 2's
    // content window while item 1 is still the current media item.
    advance(player).untilPositionAtLeast(/* positionMs= */ 20_000L);
    assertThat(
            player
                .getCurrentTimeline()
                .getWindow(/* windowIndex= */ 1, new Timeline.Window())
                .isDynamic)
        .isFalse();
    verify(mockSdkBridge, never()).onAllAdsCompleted(eq(adsId2), any());

    advance(player).untilMediaItemIndex(/* mediaItemIndex= */ 1);

    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(adsId2);
    assertThat(finalState).isNotNull();
    verify(mockSdkBridge).onAllAdsCompleted(adsId2, finalState);
  }

  @Test
  public void onTimelineChanged_earlierListenerRemovesPrecedingItem_readsCurrentTimelineWindow()
      throws Exception {
    TimelineWindowDefinition vodWindow =
        new TimelineWindowDefinition.Builder()
            .setDynamic(false)
            .setLive(false)
            .setSeekable(true)
            .setDefaultPositionUs(0L)
            .setDurationUs(CONTENT_DURATION_US)
            .setWindowPositionInFirstPeriodUs(0L)
            .build();
    TimelineWindowDefinition liveWindow =
        new TimelineWindowDefinition.Builder()
            .setDynamic(true)
            .setLive(true)
            .setSeekable(true)
            .setDefaultPositionUs(0L)
            .setDurationUs(CONTENT_DURATION_US)
            .setWindowPositionInFirstPeriodUs(0L)
            .build();
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, createSinglePrerollLiveAdState());
    FakeMediaSource vodMediaSource =
        new FakeMediaSource(new FakeTimeline(vodWindow), ExoPlayerTestRunner.VIDEO_FORMAT);
    AdsMediaSource liveAdsMediaSource =
        createAdsMediaSource(
            new FakeMediaSource(new FakeTimeline(liveWindow), ExoPlayerTestRunner.VIDEO_FORMAT),
            createDefaultAdMediaSourceFactory(),
            fakeAdsLoader,
            adViewProvider,
            ADS_ID);
    AtomicBoolean removePrecedingItemOnTimelineChange = new AtomicBoolean();
    fakeAdsLoader.setPlayer(null);
    player.addListener(
        new Player.Listener() {
          @Override
          public void onTimelineChanged(
              Timeline timeline, @Player.TimelineChangeReason int reason) {
            if (removePrecedingItemOnTimelineChange.compareAndSet(true, false)) {
              player.removeMediaItem(/* index= */ 0);
            }
          }
        });
    fakeAdsLoader.setPlayer(player);
    player.setMediaSources(
        ImmutableList.of(vodMediaSource, liveAdsMediaSource),
        /* startMediaItemIndex= */ 1,
        /* startPositionMs= */ 0L);
    player.prepare();
    player.play();
    advance(player).untilAdGroupEnded(/* adGroupIndex= */ 0);

    removePrecedingItemOnTimelineChange.set(true);
    vodMediaSource.setNewSourceInfo(new FakeTimeline(vodWindow));
    advance(player).untilTimelineChanges();

    verify(mockSdkBridge, never()).onAllAdsCompleted(eq(ADS_ID), any());
  }

  @Test
  public void play_placeholderWindow_doesNotNotifyAllAdsCompleted() throws Exception {
    // Sources like ProgressiveMediaSource initially report a non-dynamic, non-live placeholder
    // window until the real window information is known.
    TimelineWindowDefinition placeholderWindow =
        new TimelineWindowDefinition.Builder()
            .setPlaceholder(true)
            .setDynamic(false)
            .setLive(false)
            .setSeekable(true)
            .setDefaultPositionUs(0L)
            .setDurationUs(CONTENT_DURATION_US)
            .setWindowPositionInFirstPeriodUs(0L)
            .build();
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, createSinglePrerollLiveAdState());
    AdsMediaSource adsMediaSource =
        createAdsMediaSource(
            new FakeMediaSource(
                new FakeTimeline(placeholderWindow), ExoPlayerTestRunner.VIDEO_FORMAT),
            createDefaultAdMediaSourceFactory(),
            fakeAdsLoader,
            adViewProvider,
            ADS_ID);
    player.setMediaSource(adsMediaSource);
    player.prepare();
    player.play();

    advance(player).untilAdGroupEnded(/* adGroupIndex= */ 0);

    assertThat(
            player
                .getCurrentTimeline()
                .getWindow(/* windowIndex= */ 0, new Timeline.Window())
                .isPlaceholder)
        .isTrue();
    verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    verify(mockSdkBridge, never()).onAllAdsCompleted(eq(ADS_ID), any());
  }

  private static AdPlaybackState createSinglePrerollLiveAdState() {
    return new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
        .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
        .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
        .withAvailableAdMediaItem(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0)
        .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ false);
  }

  private static Timeline createSinglePeriodLiveTimeline(
      long periodDurationUs, long windowPositionInPeriodUs, boolean isDynamic) {
    return new SinglePeriodTimeline(
        periodDurationUs,
        /* windowDurationUs= */ CONTENT_DURATION_US,
        windowPositionInPeriodUs,
        /* windowDefaultStartPositionUs= */ 0L,
        /* isSeekable= */ true,
        isDynamic,
        /* useLiveConfiguration= */ true,
        /* manifest= */ null,
        FakeMediaSource.FAKE_MEDIA_ITEM);
  }

  private static Timeline createTwoPeriodLiveTimeline(long lastPeriodDurationUs) {
    return new ForwardingTimeline(
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setPeriodCount(2)
                .setDynamic(false)
                .setLive(true)
                .setDurationUs(CONTENT_DURATION_US)
                .setWindowPositionInFirstPeriodUs(0L)
                .build())) {
      @Override
      public Period getPeriod(int periodIndex, Period period, boolean setIds) {
        super.getPeriod(periodIndex, period, setIds);
        if (periodIndex == 1) {
          period.durationUs = lastPeriodDurationUs;
        }
        return period;
      }
    };
  }
}
