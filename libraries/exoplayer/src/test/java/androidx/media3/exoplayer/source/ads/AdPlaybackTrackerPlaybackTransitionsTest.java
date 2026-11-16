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
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.SECOND_ADS_ID;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.createAdsMediaSource;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.createDefaultAdMediaSourceFactory;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.createVodContentTimeline;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.verifyStandardAdGroupPlaybackEvents;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.oneByteSample;
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
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ForwardingMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.test.utils.ExoPlayerTestRunner;
import androidx.media3.test.utils.FakeAdsLoader;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link AdPlaybackTracker} single-ad and multi-ad pod playback transitions. */
@RunWith(AndroidJUnit4.class)
public final class AdPlaybackTrackerPlaybackTransitionsTest {

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
  public void play_singlePrerollAd_transitionsToPlayedAndNotifiesBridgeInOrder() throws Exception {
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
    advance(player).untilState(Player.STATE_ENDED);

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
  public void onPlayWhenReadyChanged_pauseAndResumeWhilePlayingAd_notifiesAdPausedAndAdResumed()
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

    player.pause();
    advance(player).untilPlayWhenReadyIs(false);
    player.play();
    advance(player).untilPlayWhenReadyIs(true);
    advance(player).untilState(Player.STATE_ENDED);

    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdPaused(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdResumed(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
  }

  @Test
  public void play_multiAdPod_notifiesAdGroupStartedOnceAndEachAdStartedAndCompleted()
      throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 10 * C.MICROS_PER_SECOND)
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
    inOrder.verify(mockSdkBridge).onContentCompleted(ADS_ID);
    // Verify total invocation count across the pod (inOrder does not fail on extra calls between
    // verified steps, such as between ad 0 completing and ad 1 starting).
    verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
  }

  @Test
  public void play_prerollMidrollAndPostroll_notifiesAllAdsCompletedAfterPostroll()
      throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    AdPlaybackState initialState =
        new AdPlaybackState(
                ADS_ID, /* adGroupTimesUs...= */ 0L, 20 * C.MICROS_PER_SECOND, C.TIME_END_OF_SOURCE)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0)
            .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 1, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0)
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
    player.play();
    advance(player).untilAdGroupEnded(/* adGroupIndex= */ 0);
    verify(mockSdkBridge, never()).onAllAdsCompleted(eq(ADS_ID), any());
    advance(player).untilAdGroupEnded(/* adGroupIndex= */ 1);
    verify(mockSdkBridge, never()).onAllAdsCompleted(eq(ADS_ID), any());
    advance(player).untilState(Player.STATE_ENDED);

    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(finalState).isNotNull();
    assertThat(finalState.getAdGroup(0).states[0]).isEqualTo(AdPlaybackState.AD_STATE_PLAYED);
    assertThat(finalState.getAdGroup(1).states[0]).isEqualTo(AdPlaybackState.AD_STATE_PLAYED);
    assertThat(finalState.getAdGroup(2).states[0]).isEqualTo(AdPlaybackState.AD_STATE_PLAYED);
    InOrder inOrder = inOrder(mockSdkBridge);
    verifyStandardAdGroupPlaybackEvents(
        inOrder,
        mockSdkBridge,
        ADS_ID,
        /* adGroupIndex= */ 0,
        /* startAdIndexInAdGroup= */ 0,
        /* endAdIndexInAdGroup= */ 0);
    verifyStandardAdGroupPlaybackEvents(
        inOrder,
        mockSdkBridge,
        ADS_ID,
        /* adGroupIndex= */ 1,
        /* startAdIndexInAdGroup= */ 0,
        /* endAdIndexInAdGroup= */ 0);
    verifyStandardAdGroupPlaybackEvents(
        inOrder,
        mockSdkBridge,
        ADS_ID,
        /* adGroupIndex= */ 2,
        /* startAdIndexInAdGroup= */ 0,
        /* endAdIndexInAdGroup= */ 0);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, finalState);
    inOrder.verify(mockSdkBridge).onContentCompleted(ADS_ID);
  }

  @Test
  public void
      onPlaybackStateChanged_whenPlayerEntersBufferingDuringAdPlayback_notifiesBridgeOnAdBuffering()
          throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0);
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, initialState);
    MediaSource.Factory stallingAdMediaSourceFactory =
        new ForwardingMediaSourceFactory(createDefaultAdMediaSourceFactory()) {
          @Override
          public MediaSource createMediaSource(MediaItem mediaItem) {
            return new FakeMediaSource.Builder()
                .setTimeline(
                    new FakeTimeline(
                        new TimelineWindowDefinition.Builder()
                            .setDurationUs(AD_DURATION_US)
                            .build()))
                .setFormats(ExoPlayerTestRunner.VIDEO_FORMAT)
                .setTrackDataFactory((format, mediaPeriodId) ->
                    ImmutableList.of(
                        oneByteSample(/* timeUs= */ 0L, C.BUFFER_FLAG_KEY_FRAME),
                        oneByteSample(
                            /* timeUs= */ 5 * C.MICROS_PER_SECOND, C.BUFFER_FLAG_KEY_FRAME)))
                .build();
          }
        };
    AdsMediaSource adsMediaSource =
        createAdsMediaSource(
            contentTimeline, stallingAdMediaSourceFactory, fakeAdsLoader, adViewProvider, ADS_ID);

    player.setMediaSource(adsMediaSource);
    player.prepare();
    player.play();
    advance(player).untilPlayingAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    advance(player).untilState(Player.STATE_READY);
    advance(player).untilState(Player.STATE_BUFFERING);

    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdBuffering(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
  }

  @Test
  public void seekTo_midrollAdInAnotherMediaItemInTimeline_triggersAdGroupStartedAndAdStarted()
      throws Exception {
    Timeline contentTimeline1 = createVodContentTimeline();
    Timeline contentTimeline2 = createVodContentTimeline();
    AdPlaybackState initialState1 = new AdPlaybackState(ADS_ID);
    AdPlaybackState initialState2 =
        new AdPlaybackState(SECOND_ADS_ID, /* adGroupTimesUs...= */ 20 * C.MICROS_PER_SECOND)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_1);
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, initialState1);
    fakeAdsLoader.setInitialAdPlaybackState(SECOND_ADS_ID, initialState2);
    AdsMediaSource adsMediaSource1 =
        createAdsMediaSource(
            contentTimeline1,
            createDefaultAdMediaSourceFactory(),
            fakeAdsLoader,
            adViewProvider,
            ADS_ID);
    AdsMediaSource adsMediaSource2 =
        createAdsMediaSource(
            contentTimeline2,
            createDefaultAdMediaSourceFactory(),
            fakeAdsLoader,
            adViewProvider,
            SECOND_ADS_ID);
    player.setMediaSources(ImmutableList.of(adsMediaSource1, adsMediaSource2));
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_READY);

    player.seekTo(/* mediaItemIndex= */ 1, /* positionMs= */ 20_000L);
    advance(player).untilState(Player.STATE_ENDED);

    AdPlaybackState finalState2 = adPlaybackTracker.getAdPlaybackState(SECOND_ADS_ID);
    assertThat(finalState2).isNotNull();
    assertThat(finalState2.getAdGroup(0).states[0]).isEqualTo(AdPlaybackState.AD_STATE_PLAYED);
    InOrder inOrder = inOrder(mockSdkBridge);
    verifyStandardAdGroupPlaybackEvents(
        inOrder,
        mockSdkBridge,
        SECOND_ADS_ID,
        /* adGroupIndex= */ 0,
        /* startAdIndexInAdGroup= */ 0,
        /* endAdIndexInAdGroup= */ 0);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(SECOND_ADS_ID, finalState2);
    inOrder.verify(mockSdkBridge).onContentCompleted(SECOND_ADS_ID);
  }

  @Test
  public void
      updateAdPlaybackState_firstAdInPodSkippedViaTimelineUpdateWhileWaitingForPodToStart_playsSecondAdWithoutDuplicateStarts()
          throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    // Ad (0, 0) is initially unavailable (player waits in STATE_BUFFERING at (0, 0)), while ad
    // (0, 1) is available.
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
    assertThat(player.getPlaybackState()).isEqualTo(Player.STATE_BUFFERING);

    // Dynamically skip the first ad in the pod via a timeline update while waiting for the pod to
    // start, shifting playback directly to the second ad (0, 1).
    adPlaybackTracker.updateAdPlaybackState(
        ADS_ID, state -> state.withSkippedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));
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
    inOrder.verify(mockSdkBridge).onContentCompleted(ADS_ID);
    // Verify total invocation count across the test (inOrder does not fail on extra calls after
    // the verified step, such as a duplicate onAdStarted(0, 1)).
    verify(mockSdkBridge).onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge).onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
    verify(mockSdkBridge, never())
        .onAdBuffering(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
  }

  @Test
  public void
      onPositionDiscontinuity_withRemoveDiscontinuityIntoNewAd_endsOldAdGroupAndStartsNewAd()
          throws Exception {
    Timeline contentTimeline1 = createVodContentTimeline();
    Timeline contentTimeline2 = createVodContentTimeline();
    AdPlaybackState initialState1 =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0);
    AdPlaybackState initialState2 =
        new AdPlaybackState(SECOND_ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_1);
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, initialState1);
    fakeAdsLoader.setInitialAdPlaybackState(SECOND_ADS_ID, initialState2);
    AdsMediaSource adsMediaSource1 =
        createAdsMediaSource(
            contentTimeline1,
            createDefaultAdMediaSourceFactory(),
            fakeAdsLoader,
            adViewProvider,
            ADS_ID);
    AdsMediaSource adsMediaSource2 =
        createAdsMediaSource(
            contentTimeline2,
            createDefaultAdMediaSourceFactory(),
            fakeAdsLoader,
            adViewProvider,
            SECOND_ADS_ID);
    player.setMediaSources(ImmutableList.of(adsMediaSource1, adsMediaSource2));
    player.prepare();
    player.play();
    advance(player).untilPlayingAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    advance(player).untilState(Player.STATE_READY);

    player.removeMediaItem(/* index= */ 0);
    advance(player).untilPlayingAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    advance(player).untilState(Player.STATE_READY);

    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    inOrder.verify(mockSdkBridge).onAdGroupStarted(SECOND_ADS_ID, /* adGroupIndex= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(SECOND_ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, never()).onAllAdsCompleted(eq(ADS_ID), any());
  }

  @Test
  public void removeMediaItem_onlyItemInPlaylistWhilePlayingAd_endsAdGroup() throws Exception {
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

    player.removeMediaItem(/* index= */ 0);

    assertThat(player.getCurrentTimeline().isEmpty()).isTrue();
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    verify(mockSdkBridge, never()).onAllAdsCompleted(eq(ADS_ID), any());
  }
}
