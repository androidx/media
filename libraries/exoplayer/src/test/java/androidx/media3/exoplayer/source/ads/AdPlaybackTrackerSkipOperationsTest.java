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
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.createAdsMediaSource;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.createContentMediaItem;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.createDefaultAdMediaSourceFactory;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.createVodContentTimeline;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.test.utils.FakeAdsLoader;
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
 * Unit tests for {@link AdPlaybackTracker} skip operations ({@link AdPlaybackTracker#skipAd} and
 * {@link AdPlaybackTracker#skipAdGroup}).
 */
@RunWith(AndroidJUnit4.class)
public final class AdPlaybackTrackerSkipOperationsTest {

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
  public void skipAd_whilePlayingAd_marksAdSkippedAndTransitionsToContent() throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
            .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 2 * C.MICROS_PER_SECOND)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0);
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, initialState);
    AdsMediaSource adsMediaSource =
        createAdsMediaSource(
            contentTimeline,
            createDefaultAdMediaSourceFactory(),
            fakeAdsLoader,
            adViewProvider,
            ADS_ID);
    player.setMediaSource(adsMediaSource);
    player.prepare();
    player.play();
    advance(player).untilPlayingAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    advance(player).untilState(Player.STATE_READY);

    adPlaybackTracker.skipAd(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    advance(player).untilState(Player.STATE_ENDED);

    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(finalState).isNotNull();
    assertThat(finalState.getAdGroup(0).states[0]).isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdSkipped(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, finalState);
    inOrder.verify(mockSdkBridge).onContentCompleted(ADS_ID);
  }

  // See b/577167822.
  @Test
  public void skipAd_forFirstAdInTwoAdPod_skipsToSecondAdWithoutEndingAdGroupOrDuplicateCallback()
      throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 2)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, AD_MEDIA_ITEM_1);
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, initialState);
    // createAdsMediaSource uses useLazyContentSourcePreparation = true (default).
    AdsMediaSource adsMediaSource =
        createAdsMediaSource(
            contentTimeline,
            createDefaultAdMediaSourceFactory(),
            fakeAdsLoader,
            adViewProvider,
            ADS_ID);
    player.setMediaSource(adsMediaSource);
    player.prepare();
    player.play();
    advance(player).untilPlayingAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    advance(player).untilState(Player.STATE_READY);
    Player.Listener mockPlayerListener = mock(Player.Listener.class);
    player.addListener(mockPlayerListener);

    adPlaybackTracker.skipAd(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    advance(player).untilPlayingAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
    advance(player).untilState(Player.STATE_READY);

    // ExoPlayer emits both onTimelineChanged and onPositionDiscontinuity(DISCONTINUITY_REASON_SKIP)
    // for the transition from (0, 0) to (0, 1); AdPlaybackTracker reports onAdSkipped once.
    verify(mockPlayerListener)
        .onPositionDiscontinuity(any(), any(), eq(Player.DISCONTINUITY_REASON_SKIP));
    verify(mockPlayerListener)
        .onTimelineChanged(any(), eq(Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE));

    advance(player).untilState(Player.STATE_ENDED);

    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(finalState).isNotNull();
    assertThat(finalState.getAdGroup(0).states)
        .asList()
        .containsExactly(AdPlaybackState.AD_STATE_SKIPPED, AdPlaybackState.AD_STATE_PLAYED)
        .inOrder();
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdSkipped(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
    inOrder
        .verify(mockSdkBridge)
        .onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
    inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, finalState);
  }

  @Test
  public void
      skipAd_forUnavailablePrerollAdInTwoAdPodBeforeReady_startsAndSkipsFirstAdThenPlaysSecondAd()
          throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    // The first ad has no media item yet, so the player stays in STATE_BUFFERING on the first ad
    // and the tracker has not started the ad yet.
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 2)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, AD_MEDIA_ITEM_1);
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, initialState);
    AdsMediaSource adsMediaSource =
        createAdsMediaSource(
            contentTimeline,
            createDefaultAdMediaSourceFactory(),
            fakeAdsLoader,
            adViewProvider,
            ADS_ID);
    player.setMediaSource(adsMediaSource);
    player.prepare();
    player.play();
    advance(player).untilPlayingAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    advance(player).untilPendingCommandsAreFullyHandled();
    assertThat(player.getPlaybackState()).isEqualTo(Player.STATE_BUFFERING);

    adPlaybackTracker.skipAd(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    advance(player).untilState(Player.STATE_ENDED);

    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(finalState).isNotNull();
    assertThat(finalState.getAdGroup(0).states)
        .asList()
        .containsExactly(AdPlaybackState.AD_STATE_SKIPPED, AdPlaybackState.AD_STATE_PLAYED)
        .inOrder();
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdSkipped(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
    inOrder
        .verify(mockSdkBridge)
        .onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
    inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, finalState);
    verify(mockSdkBridge, never())
        .onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
  }

  @Test
  public void skipAdGroup_whilePlayingFirstAdInPod_skipsEntirePodAndNotifiesAdGroupEnded()
      throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 2)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, AD_MEDIA_ITEM_1);
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, initialState);
    AdsMediaSource adsMediaSource =
        createAdsMediaSource(
            contentTimeline,
            createDefaultAdMediaSourceFactory(),
            fakeAdsLoader,
            adViewProvider,
            ADS_ID);
    player.setMediaSource(adsMediaSource);
    player.prepare();
    player.play();
    advance(player).untilPlayingAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    advance(player).untilState(Player.STATE_READY);

    adPlaybackTracker.skipAdGroup(ADS_ID, /* adGroupIndex= */ 0);
    advance(player).untilState(Player.STATE_ENDED);

    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(finalState).isNotNull();
    assertThat(finalState.getAdGroup(0).states)
        .asList()
        .containsExactly(AdPlaybackState.AD_STATE_SKIPPED, AdPlaybackState.AD_STATE_SKIPPED)
        .inOrder();
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdSkipped(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, finalState);
    inOrder.verify(mockSdkBridge).onContentCompleted(ADS_ID);
    verify(mockSdkBridge, never())
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
  }

  @Test
  public void skipAdGroup_beforeReachingMidroll_marksUnplayedAdsSkippedAndBypassesAdGroup()
      throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 20 * C.MICROS_PER_SECOND)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 2)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0);
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, initialState);
    AdsMediaSource adsMediaSource =
        createAdsMediaSource(
            contentTimeline,
            createDefaultAdMediaSourceFactory(),
            fakeAdsLoader,
            adViewProvider,
            ADS_ID);
    player.setMediaSource(adsMediaSource);
    player.prepare();
    player.play();
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 5_000L);

    adPlaybackTracker.skipAdGroup(ADS_ID, /* adGroupIndex= */ 0);
    advance(player).untilState(Player.STATE_ENDED);

    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(finalState).isNotNull();
    assertThat(finalState.getAdGroup(0).states)
        .asList()
        .containsExactly(AdPlaybackState.AD_STATE_SKIPPED, AdPlaybackState.AD_STATE_SKIPPED)
        .inOrder();
    verify(mockSdkBridge, never()).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    verify(mockSdkBridge, never()).onAdStarted(eq(ADS_ID), anyInt(), anyInt());
    verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, finalState);
    verify(mockSdkBridge).onContentCompleted(ADS_ID);
  }

  @Test
  public void skipAdGroup_withAlreadyPlayedAndErroredAds_preservesPlayedAndErrorStates() {
    MediaItem contentMediaItem = createContentMediaItem(ADS_ID);
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 4)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0)
            .withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, AD_MEDIA_ITEM_0)
            .withAdLoadError(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 2, AD_MEDIA_ITEM_0);
    // Ad 2 is AD_STATE_AVAILABLE and ad 3 is AD_STATE_UNAVAILABLE.
    adPlaybackTracker.startSession(
        contentMediaItem, adViewProvider, new AdsLoader.EventListener() {}, initialState);

    boolean skipped = adPlaybackTracker.skipAdGroup(ADS_ID, /* adGroupIndex= */ 0);

    assertThat(skipped).isTrue();
    AdPlaybackState updatedState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(updatedState).isNotNull();
    assertThat(updatedState.getAdGroup(0).states)
        .asList()
        .containsExactly(
            AdPlaybackState.AD_STATE_PLAYED,
            AdPlaybackState.AD_STATE_ERROR,
            AdPlaybackState.AD_STATE_SKIPPED,
            AdPlaybackState.AD_STATE_SKIPPED)
        .inOrder();
    verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, updatedState);
  }

  @Test
  public void skipAdAndSkipAdGroup_unknownAdsId_returnsFalse() {
    assertThat(
            adPlaybackTracker.skipAd(
                "unknownAdsId", /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0))
        .isFalse();
    assertThat(adPlaybackTracker.skipAdGroup("unknownAdsId", /* adGroupIndex= */ 0)).isFalse();
    assertThat(adPlaybackTracker.getAdPlaybackState("unknownAdsId")).isNull();
  }

  @Test
  public void
      skipAdAndSkipAdGroup_withOutOfBoundsOrNonActionableIndices_returnsFalseWithoutModifyingState() {
    AdsLoader.EventListener mockEventListener = mock(AdsLoader.EventListener.class);
    AdPlaybackTracker.AdPlaybackStateListener mockStateListener =
        mock(AdPlaybackTracker.AdPlaybackStateListener.class);
    adPlaybackTracker.addListener(mockStateListener);
    MediaItem contentMediaItem = createContentMediaItem(ADS_ID);
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
    adPlaybackTracker.startSession(
        contentMediaItem, adViewProvider, mockEventListener, initialState);

    assertThat(
            adPlaybackTracker.skipAd(ADS_ID, /* adGroupIndex= */ 999, /* adIndexInAdGroup= */ -1))
        .isFalse();
    assertThat(adPlaybackTracker.skipAd(ADS_ID, /* adGroupIndex= */ -1, /* adIndexInAdGroup= */ 0))
        .isFalse();
    assertThat(adPlaybackTracker.skipAd(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ -1))
        .isFalse();
    assertThat(adPlaybackTracker.skipAd(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 999))
        .isFalse();
    assertThat(adPlaybackTracker.skipAdGroup(ADS_ID, /* adGroupIndex= */ 999)).isFalse();
    assertThat(adPlaybackTracker.skipAdGroup(ADS_ID, /* adGroupIndex= */ -1)).isFalse();

    assertThat(adPlaybackTracker.getAdPlaybackState(ADS_ID)).isEqualTo(initialState);
    verify(mockEventListener).onAdPlaybackState(initialState);
    verify(mockStateListener).onAdPlaybackStateChanged(ADS_ID, initialState);
    verify(mockSdkBridge, never()).onAdSkipped(any(), anyInt(), anyInt());
    verify(mockSdkBridge, never()).onAllAdsCompleted(any(), any());

    assertThat(adPlaybackTracker.skipAd(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0))
        .isTrue();
    assertThat(adPlaybackTracker.skipAd(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0))
        .isFalse();
    assertThat(adPlaybackTracker.skipAdGroup(ADS_ID, /* adGroupIndex= */ 0)).isFalse();
  }
}
