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
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.verifyStandardAdGroupPlaybackEvents;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.test.utils.FakeAdsLoader;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
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
 * Unit tests for {@link AdPlaybackTracker} external {@link AdPlaybackState} manipulation
 * mid-playback.
 */
@RunWith(AndroidJUnit4.class)
public final class AdPlaybackTrackerExternalStateManipulationTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  private Context context;
  @Mock private AdPlaybackTracker.SdkBridge mockSdkBridge;

  private AdViewProvider adViewProvider;
  private AdPlaybackTracker adPlaybackTracker;
  private FakeAdsLoader fakeAdsLoader;
  private ExoPlayer player;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    adViewProvider = () -> null;
    adPlaybackTracker = new AdPlaybackTracker(mockSdkBridge);
    fakeAdsLoader = new FakeAdsLoader(adPlaybackTracker);
    player = new TestExoPlayerBuilder(context).build();
    fakeAdsLoader.setPlayer(player);
  }

  @After
  public void tearDown() {
    player.release();
    fakeAdsLoader.release();
  }

  @Test
  public void
      updateAdPlaybackState_jitMidrollResolutionDuringContentPlayback_playsAdAndNotifiesBridge()
          throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    // Initial schedule has an unresolved mid-roll placeholder at 10s (AD_STATE_UNAVAILABLE).
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 10 * C.MICROS_PER_SECOND)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
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
    // Play primary content up to 5s (before the 10s mid-roll cue point).
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 5_000L);
    assertThat(player.isPlayingAd()).isFalse();

    // Externally mutate AdPlaybackState on AdPlaybackTracker to make the mid-roll available.
    boolean updated =
        adPlaybackTracker.updateAdPlaybackState(
            ADS_ID,
            state ->
                state
                    .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
                    .withAvailableAdMediaItem(
                        /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0));
    advance(player).untilState(Player.STATE_ENDED);

    assertThat(updated).isTrue();
    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(finalState).isNotNull();
    assertThat(finalState.getAdGroup(0).states[0]).isEqualTo(AdPlaybackState.AD_STATE_PLAYED);
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onPlayerChanged(player);
    inOrder
        .verify(mockSdkBridge)
        .onSessionStarted(ADS_ID, adsMediaSource.getMediaItem(), adViewProvider);
    inOrder
        .verify(mockSdkBridge)
        .onAdPrepareCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verifyStandardAdGroupPlaybackEvents(
        inOrder,
        mockSdkBridge,
        ADS_ID,
        /* adGroupIndex= */ 0,
        /* startAdIndexInAdGroup= */ 0,
        /* endAdIndexInAdGroup= */ 0);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, finalState);
    inOrder.verify(mockSdkBridge).onContentCompleted(ADS_ID);
  }

  @Test
  public void
      updateAdPlaybackState_dynamicAdGroupInsertedAtCurrentPosition_transitionsToAdAndPlays()
          throws Exception {
    // Recreate the player with enforceAdPlaybackOnTimelineRefresh enabled so that inserting a new
    // ad group at the current playback position transitions into the ad on timeline refresh.
    player.release();
    player = new TestExoPlayerBuilder(context).setEnforceAdPlaybackOnTimelineRefresh(true).build();
    fakeAdsLoader.setPlayer(player);
    Timeline contentTimeline = createVodContentTimeline();
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
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 15_000L);

    boolean updated =
        adPlaybackTracker.updateAdPlaybackState(
            ADS_ID,
            state ->
                state
                    .withNewAdGroup(/* adGroupIndex= */ 0, /* adGroupTimeUs= */ 15_000_000L)
                    .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                    .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
                    .withAvailableAdMediaItem(
                        /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0));
    advance(player).untilState(Player.STATE_ENDED);

    assertThat(updated).isTrue();
    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(finalState).isNotNull();
    assertThat(finalState.getAdGroup(0).states[0]).isEqualTo(AdPlaybackState.AD_STATE_PLAYED);
    InOrder inOrder = inOrder(mockSdkBridge);
    verifyStandardAdGroupPlaybackEvents(
        inOrder,
        mockSdkBridge,
        ADS_ID,
        /* adGroupIndex= */ 0,
        /* startAdIndexInAdGroup= */ 0,
        /* endAdIndexInAdGroup= */ 0);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, finalState);
    inOrder.verify(mockSdkBridge).onContentCompleted(ADS_ID);
  }

  @Test
  public void updateAdPlaybackState_adGroupExpandedWhileFirstAdInPodIsPlaying_playsNewlyAppendedAd()
      throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
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
    advance(player)
        .untilAdPositionAtLeast(
            /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* adPositionMs= */ 2_000L);

    // Expand the active ad pod from 1 ad to 2 ads while ad (0, 0) is playing.
    adPlaybackTracker.updateAdPlaybackState(
        ADS_ID,
        state ->
            state
                .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 2)
                .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US, AD_DURATION_US)
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, AD_MEDIA_ITEM_1));
    advance(player).untilState(Player.STATE_ENDED);

    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(finalState).isNotNull();
    assertThat(finalState.getAdGroup(0).states)
        .asList()
        .containsExactly(AdPlaybackState.AD_STATE_PLAYED, AdPlaybackState.AD_STATE_PLAYED)
        .inOrder();
    InOrder inOrder = inOrder(mockSdkBridge);
    verifyStandardAdGroupPlaybackEvents(
        inOrder,
        mockSdkBridge,
        ADS_ID,
        /* adGroupIndex= */ 0,
        /* startAdIndexInAdGroup= */ 0,
        /* endAdIndexInAdGroup= */ 1);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, finalState);
  }

  @Test
  public void updateAdPlaybackState_unknownAdsId_returnsFalse() {
    boolean updated =
        adPlaybackTracker.updateAdPlaybackState(
            "unknownAdsId",
            state ->
                state
                    .withNewAdGroup(/* adGroupIndex= */ 0, /* adGroupTimeUs= */ 0L)
                    .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1));

    assertThat(updated).isFalse();
    assertThat(adPlaybackTracker.getAdPlaybackState("unknownAdsId")).isNull();
  }

  @Test
  public void
      updateAdPlaybackState_returningEmptyStateWhenAdGroupsExist_throwsAndInterceptsUpdate() {
    AdsLoader.EventListener mockEventListener = mock(AdsLoader.EventListener.class);
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0);
    adPlaybackTracker.startSession(
        createContentMediaItem(ADS_ID), adViewProvider, mockEventListener, initialState);

    assertThrows(
        IllegalStateException.class,
        () ->
            adPlaybackTracker.updateAdPlaybackState(ADS_ID, state -> new AdPlaybackState(ADS_ID)));

    assertThat(adPlaybackTracker.getAdPlaybackState(ADS_ID)).isEqualTo(initialState);
    verify(mockEventListener).onAdPlaybackState(initialState);
  }

  @Test
  public void updateAdPlaybackState_invalidModifications_throwsIllegalStateException() {
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L, 10 * C.MICROS_PER_SECOND)
            .withRemovedAdGroupCount(1)
            .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 2)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 1, AD_MEDIA_ITEM_1);
    adPlaybackTracker.startSession(
        createContentMediaItem(ADS_ID),
        adViewProvider,
        new AdsLoader.EventListener() {},
        initialState);

    // Changing existing ad group timeUs.
    assertThrows(
        IllegalStateException.class,
        () ->
            adPlaybackTracker.updateAdPlaybackState(
                ADS_ID,
                state ->
                    state.withAdGroupTimeUs(
                        /* adGroupIndex= */ 1, /* adGroupTimeUs= */ 20 * C.MICROS_PER_SECOND)));
    // Shrinking an ad group with unplayed ads.
    assertThrows(
        IllegalStateException.class,
        () ->
            adPlaybackTracker.updateAdPlaybackState(
                ADS_ID,
                state ->
                    state.withRemovedAdsAfterIndex(
                        /* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0)));
    // Replacing an already available ad MediaItem.
    assertThrows(
        IllegalStateException.class,
        () ->
            adPlaybackTracker.updateAdPlaybackState(
                ADS_ID,
                state ->
                    state.withAvailableAdMediaItem(
                        /* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_1)));
    // Decreasing removedAdGroupCount.
    assertThrows(
        IllegalStateException.class,
        () ->
            adPlaybackTracker.updateAdPlaybackState(
                ADS_ID, state -> new AdPlaybackState(ADS_ID, 0L, 10 * C.MICROS_PER_SECOND)));

    assertThat(adPlaybackTracker.getAdPlaybackState(ADS_ID)).isEqualTo(initialState);
  }

  @Test
  public void
      updateAdPlaybackState_newAdGroupAddedAfterAllAdsCompleted_playsNewAdAndNotifiesAllAdsCompletedAgain()
          throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
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
    // Play past the preroll into content at 5s; onAllAdsCompleted has already been notified once.
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 5_000L);
    AdPlaybackState stateAfterPreroll = adPlaybackTracker.getAdPlaybackState(ADS_ID);

    // Dynamically append a midroll at 15s after all initial ads completed.
    adPlaybackTracker.updateAdPlaybackState(
        ADS_ID,
        state ->
            state
                .withNewAdGroup(
                    /* adGroupIndex= */ 1, /* adGroupTimeUs= */ 15 * C.MICROS_PER_SECOND)
                .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)
                .withAdDurationsUs(/* adGroupIndex= */ 1, AD_DURATION_US)
                .withAvailableAdMediaItem(
                    /* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_1));
    advance(player).untilState(Player.STATE_ENDED);

    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(finalState).isNotNull();
    InOrder inOrder = inOrder(mockSdkBridge);
    verifyStandardAdGroupPlaybackEvents(
        inOrder,
        mockSdkBridge,
        ADS_ID,
        /* adGroupIndex= */ 0,
        /* startAdIndexInAdGroup= */ 0,
        /* endAdIndexInAdGroup= */ 0);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, stateAfterPreroll);
    verifyStandardAdGroupPlaybackEvents(
        inOrder,
        mockSdkBridge,
        ADS_ID,
        /* adGroupIndex= */ 1,
        /* startAdIndexInAdGroup= */ 0,
        /* endAdIndexInAdGroup= */ 0);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, finalState);
    inOrder.verify(mockSdkBridge).onContentCompleted(ADS_ID);
  }

  @Test
  public void
      updateAdPlaybackState_resetAdGroupAndSeekBack_replaysAdAndNotifiesAllAdsCompletedAgain()
          throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
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

    // Reactivate the played preroll via updateAdPlaybackState and seek back to 0.
    adPlaybackTracker.updateAdPlaybackState(
        ADS_ID, state -> state.withResetAdGroup(/* adGroupIndex= */ 0));
    player.seekTo(/* positionMs= */ 0L);
    advance(player).untilState(Player.STATE_ENDED);

    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(finalState).isNotNull();
    assertThat(finalState.getAdGroup(0).states[0]).isEqualTo(AdPlaybackState.AD_STATE_PLAYED);
    verify(mockSdkBridge, times(2)).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    verify(mockSdkBridge, times(2))
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, times(2))
        .onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, times(2)).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    verify(mockSdkBridge, times(2)).onAllAdsCompleted(ADS_ID, finalState);
  }

  @Test
  public void updateAdPlaybackState_markActiveAdError_notifiesAdErrorAndAdGroupEnded()
      throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
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

    adPlaybackTracker.updateAdPlaybackState(
        ADS_ID, state -> state.withAdLoadError(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));
    advance(player).untilState(Player.STATE_ENDED);

    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(finalState).isNotNull();
    assertThat(finalState.getAdGroup(0).states[0]).isEqualTo(AdPlaybackState.AD_STATE_ERROR);
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdError(
            eq(ADS_ID),
            /* adGroupIndex= */ eq(0),
            /* adIndexInAdGroup= */ eq(0),
            any(IOException.class));
    inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, finalState);
    inOrder.verify(mockSdkBridge).onContentCompleted(ADS_ID);
    verify(mockSdkBridge, never())
        .onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, never())
        .onAdSkipped(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
  }

  @Test
  public void
      updateAdPlaybackState_removingTwoOfThreeAdGroupsThenLastAdGroup_updatesStateWithoutThrowing()
          throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    AdPlaybackState initialState =
        new AdPlaybackState(
                ADS_ID,
                /* adGroupTimesUs...= */ 0L,
                10 * C.MICROS_PER_SECOND,
                20 * C.MICROS_PER_SECOND)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0)
            .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 1, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_1)
            .withAdCount(/* adGroupIndex= */ 2, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 2, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 2, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0);
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
    advance(player).untilState(Player.STATE_READY);

    boolean firstUpdate =
        adPlaybackTracker.updateAdPlaybackState(ADS_ID, state -> state.withRemovedAdGroupCount(2));
    AdPlaybackState stateAfterFirstRemove = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    boolean secondUpdate =
        adPlaybackTracker.updateAdPlaybackState(ADS_ID, state -> state.withRemovedAdGroupCount(3));
    player.play();
    advance(player).untilState(Player.STATE_ENDED);

    assertThat(firstUpdate).isTrue();
    assertThat(stateAfterFirstRemove).isNotNull();
    assertThat(stateAfterFirstRemove.adGroupCount).isEqualTo(3);
    assertThat(stateAfterFirstRemove.removedAdGroupCount).isEqualTo(2);
    assertThat(secondUpdate).isTrue();
    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(finalState).isNotNull();
    assertThat(finalState.adGroupCount).isEqualTo(3);
    assertThat(finalState.removedAdGroupCount).isEqualTo(3);
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    inOrder.verify(mockSdkBridge).onContentCompleted(ADS_ID);
    verify(mockSdkBridge, never())
        .onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, never())
        .onAdSkipped(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, never()).onAllAdsCompleted(eq(ADS_ID), any());
  }

  @Test
  public void resetAllAds_whilePlayingLaterAdInPod_preemptsWithFirstAdWithoutMarkingLaterAdPlayed()
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
    // Play through ad (0, 0) until ad (0, 1) has started playing.
    advance(player)
        .untilAdPositionAtLeast(
            /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, /* adPositionMs= */ 1_000L);

    // Reset all ads while ad (0, 1) is actively playing: ad (0, 0) becomes AVAILABLE again and
    // preempts ad (0, 1). Ad (0, 1) must not be marked PLAYED by the backward transition.
    adPlaybackTracker.resetAllAds(ADS_ID, /* resetErrorAds= */ true);
    advance(player)
        .untilAdPositionAtLeast(
            /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* adPositionMs= */ 1_000L);

    AdPlaybackState stateAfterPreemption = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(stateAfterPreemption).isNotNull();
    assertThat(stateAfterPreemption.getAdGroup(0).states)
        .asList()
        .containsExactly(AdPlaybackState.AD_STATE_AVAILABLE, AdPlaybackState.AD_STATE_AVAILABLE)
        .inOrder();
    verify(mockSdkBridge).onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, never())
        .onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);

    // Finish playing both ads and content.
    advance(player).untilState(Player.STATE_ENDED);

    verify(mockSdkBridge, times(2))
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, times(2))
        .onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, times(2))
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
    verify(mockSdkBridge).onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
  }
}
