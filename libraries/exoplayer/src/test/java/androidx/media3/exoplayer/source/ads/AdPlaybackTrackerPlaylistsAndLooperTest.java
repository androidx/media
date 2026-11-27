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
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.SECOND_ADS_ID;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.createAdsMediaSource;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.createContentMediaItem;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.createDefaultAdMediaSourceFactory;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.createVodContentTimeline;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.verifyStandardAdGroupPlaybackEvents;
import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.test.utils.ExoPlayerTestRunner;
import androidx.media3.test.utils.FakeAdsLoader;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
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
 * Unit tests for {@link AdPlaybackTracker} multi-window playlists and custom looper confinement.
 */
@RunWith(AndroidJUnit4.class)
public final class AdPlaybackTrackerPlaylistsAndLooperTest {

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
      play_transitionToNextWindowAndSeekBackWithoutReset_keepsAdsPlayedInFirstWindowByDefault()
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
    advance(player).untilPosition(/* mediaItemIndex= */ 1, /* positionMs= */ 2_000L);

    // Seek back to window 0 without calling resetAllAds.
    player.seekTo(/* mediaItemIndex= */ 0, /* positionMs= */ 0L);
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 5_000L);

    AdPlaybackState state1 = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(state1).isNotNull();
    assertThat(state1.getAdGroup(0).states[0]).isEqualTo(AdPlaybackState.AD_STATE_PLAYED);
    verify(mockSdkBridge).onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge).onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
  }

  @Test
  public void
      resetAllAds_whenTransitioningToSecondWindowAndSeekingBack_replaysAdsAccordingToResetErrorAdsFlag()
          throws Exception {
    Timeline contentTimeline1 = createVodContentTimeline();
    Timeline contentTimeline2 = createVodContentTimeline();
    AdPlaybackState initialState1 =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 2)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, AD_MEDIA_ITEM_1)
            .withAdLoadError(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
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
    // Advance until playback transitions from window 0 to window 1.
    advance(player).untilPosition(/* mediaItemIndex= */ 1, /* positionMs= */ 2_000L);

    // Reactivate window 0's ads keeping errored ad (0, 1) in AD_STATE_ERROR, then seek back.
    boolean reset = adPlaybackTracker.resetAllAds(ADS_ID, /* resetErrorAds= */ false);
    player.seekTo(/* mediaItemIndex= */ 0, /* positionMs= */ 0L);
    advance(player).untilPosition(/* mediaItemIndex= */ 1, /* positionMs= */ 2_000L);

    assertThat(reset).isTrue();
    AdPlaybackState stateAfterFirstReplay = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(stateAfterFirstReplay).isNotNull();
    assertThat(stateAfterFirstReplay.getAdGroup(0).states)
        .asList()
        .containsExactly(AdPlaybackState.AD_STATE_PLAYED, AdPlaybackState.AD_STATE_ERROR)
        .inOrder();
    verify(mockSdkBridge, times(2))
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, times(2))
        .onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, never())
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
    verify(mockSdkBridge, times(2)).onAllAdsCompleted(eq(ADS_ID), any());
    verify(mockSdkBridge, times(2)).onContentCompleted(ADS_ID);

    // Now reactivate window 0's ads including errored ad (0, 1) and seek back again.
    adPlaybackTracker.resetAllAds(ADS_ID, /* resetErrorAds= */ true);
    player.seekTo(/* mediaItemIndex= */ 0, /* positionMs= */ 0L);
    advance(player).untilState(Player.STATE_ENDED);

    AdPlaybackState stateAfterSecondReplay = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(stateAfterSecondReplay).isNotNull();
    assertThat(stateAfterSecondReplay.getAdGroup(0).states)
        .asList()
        .containsExactly(AdPlaybackState.AD_STATE_PLAYED, AdPlaybackState.AD_STATE_PLAYED)
        .inOrder();
    verify(mockSdkBridge, times(3))
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, times(3))
        .onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge).onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
    verify(mockSdkBridge).onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
    verify(mockSdkBridge, times(3)).onAllAdsCompleted(eq(ADS_ID), any());
    verify(mockSdkBridge, times(3)).onContentCompleted(ADS_ID);
  }

  @Test
  public void
      resetAllAds_fromOnMediaItemTransitionWhenSeekingBetweenWindows_replaysEntirePrerollPod()
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
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 3)
            .withAdDurationsUs(
                /* adGroupIndex= */ 0, AD_DURATION_US, AD_DURATION_US, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, AD_MEDIA_ITEM_1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 2, AD_MEDIA_ITEM_0);
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
    player.addListener(
        new Player.Listener() {
          private @Nullable Object currentAdsId;

          @Override
          public void onEvents(Player player, Player.Events events) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) && currentAdsId != null) {
              adPlaybackTracker.resetAllAds(currentAdsId, /* resetErrorAds= */ true);
            }
            if (events.containsAny(
                Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED)) {
              Timeline timeline = player.getCurrentTimeline();
              currentAdsId =
                  timeline.isEmpty()
                      ? null
                      : timeline.getPeriod(player.getCurrentPeriodIndex(), new Timeline.Period())
                          .adPlaybackState
                          .adsId;
            }
          }
        });
    player.setMediaSources(ImmutableList.of(adsMediaSource1, adsMediaSource2));
    player.prepare();
    player.play();

    // 1. Play window 0 until its preroll completes and content starts.
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 2_000L);
    // 2. Seek to window 1 and play all 3 prerolls in its pod until content starts.
    player.seekTo(/* mediaItemIndex= */ 1, /* positionMs= */ 0L);
    advance(player).untilPosition(/* mediaItemIndex= */ 1, /* positionMs= */ 2_000L);
    // 3. Seek back to window 0.
    player.seekTo(/* mediaItemIndex= */ 0, /* positionMs= */ 0L);
    advance(player).untilPlayingAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    // 4. Seek back again to window 1: preroll 0/0 must start and play (not jump to 0/2).
    player.seekTo(/* mediaItemIndex= */ 1, /* positionMs= */ 0L);
    advance(player)
        .untilAdPositionAtLeast(
            /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* adPositionMs= */ 1_000L);

    AdPlaybackState stateDuringSecondPlayOfWindow1 =
        adPlaybackTracker.getAdPlaybackState(SECOND_ADS_ID);
    assertThat(stateDuringSecondPlayOfWindow1).isNotNull();
    assertThat(stateDuringSecondPlayOfWindow1.getAdGroup(0).states)
        .asList()
        .containsExactly(
            AdPlaybackState.AD_STATE_AVAILABLE,
            AdPlaybackState.AD_STATE_AVAILABLE,
            AdPlaybackState.AD_STATE_AVAILABLE)
        .inOrder();
    assertThat(player.getCurrentAdGroupIndex()).isEqualTo(0);
    assertThat(player.getCurrentAdIndexInAdGroup()).isEqualTo(0);

    // Let window 1 finish playing all 3 ads and content.
    advance(player).untilState(Player.STATE_ENDED);

    verify(mockSdkBridge, times(2))
        .onAdStarted(SECOND_ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, times(2))
        .onAdCompleted(SECOND_ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, times(2))
        .onAdStarted(SECOND_ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
    verify(mockSdkBridge, times(2))
        .onAdCompleted(SECOND_ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
    verify(mockSdkBridge, times(2))
        .onAdStarted(SECOND_ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 2);
    verify(mockSdkBridge, times(2))
        .onAdCompleted(SECOND_ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 2);
  }

  @Test
  public void
      play_playlistWithRegularMediaSourceAndAdsMediaSource_ignoresEventsOnRegularMediaSource()
          throws Exception {
    Timeline regularContentTimeline = createVodContentTimeline();
    Timeline adContentTimeline = createVodContentTimeline();
    FakeMediaSource regularMediaSource =
        new FakeMediaSource(regularContentTimeline, ExoPlayerTestRunner.VIDEO_FORMAT);
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 10 * C.MICROS_PER_SECOND)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0);
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, initialState);
    AdsMediaSource adsMediaSource =
        createAdsMediaSource(
            adContentTimeline,
            createDefaultAdMediaSourceFactory(),
            fakeAdsLoader,
            adViewProvider,
            ADS_ID);

    player.setMediaSources(ImmutableList.of(regularMediaSource, adsMediaSource));
    player.prepare();
    player.play();
    // Advance until the second item (adsMediaSource) begins playing content before its 10s midroll.
    advance(player).untilPosition(/* mediaItemIndex= */ 1, /* positionMs= */ 2_000L);

    // Completing the regular MediaSource (item 0) must not trigger onContentCompleted or ad events
    // for ADS_ID.
    verify(mockSdkBridge, never()).onContentCompleted(any());
    verify(mockSdkBridge, never()).onAdGroupStarted(eq(ADS_ID), anyInt());
    verify(mockSdkBridge, never()).onAllAdsCompleted(eq(ADS_ID), any());

    // Continue playback through the end of the AdsMediaSource (item 1).
    advance(player).untilState(Player.STATE_ENDED);

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
  public void play_volumeChangedDuringRegularMediaSource_notifiesPreStartedAdsSessionImmediately()
      throws Exception {
    FakeMediaSource regularMediaSource =
        new FakeMediaSource(createShortContentTimeline(), ExoPlayerTestRunner.VIDEO_FORMAT);
    AdsMediaSource adsMediaSource = createSinglePrerollAdsMediaSource(ADS_ID, AD_MEDIA_ITEM_0);
    player.setVolume(1f);
    player.setMediaSources(ImmutableList.of(regularMediaSource, adsMediaSource));
    player.prepare();
    player.play();
    runMainLooperUntil(() -> adPlaybackTracker.isSessionActive(ADS_ID));
    assertThat(player.getCurrentMediaItemIndex()).isEqualTo(0);

    player.setVolume(0.4f);
    advance(player).untilPendingCommandsAreFullyHandled();

    assertThat(player.getCurrentMediaItemIndex()).isEqualTo(0);
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder
        .verify(mockSdkBridge)
        .onSessionStarted(ADS_ID, adsMediaSource.getMediaItem(), adViewProvider, /* volume= */ 1f);
    inOrder.verify(mockSdkBridge).onVolumeChanged(ADS_ID, /* volume= */ 0.4f);
    verify(mockSdkBridge).onVolumeChanged(any(), anyFloat());
  }

  @Test
  public void
      play_transitionToPreStartedAdsSessionAfterVolumeChange_doesNotEmitDuplicateVolumeChanged()
          throws Exception {
    FakeMediaSource regularMediaSource =
        new FakeMediaSource(createShortContentTimeline(), ExoPlayerTestRunner.VIDEO_FORMAT);
    AdsMediaSource adsMediaSource = createSinglePrerollAdsMediaSource(ADS_ID, AD_MEDIA_ITEM_0);
    player.setVolume(1f);
    player.setMediaSources(ImmutableList.of(regularMediaSource, adsMediaSource));
    player.prepare();
    player.play();
    runMainLooperUntil(() -> adPlaybackTracker.isSessionActive(ADS_ID));
    assertThat(player.getCurrentMediaItemIndex()).isEqualTo(0);
    player.setVolume(0.4f);
    advance(player).untilPendingCommandsAreFullyHandled();
    verify(mockSdkBridge).onVolumeChanged(ADS_ID, /* volume= */ 0.4f);

    advance(player).untilPosition(/* mediaItemIndex= */ 1, /* positionMs= */ 2_000L);

    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder
        .verify(mockSdkBridge)
        .onSessionStarted(ADS_ID, adsMediaSource.getMediaItem(), adViewProvider, /* volume= */ 1f);
    inOrder.verify(mockSdkBridge).onVolumeChanged(ADS_ID, /* volume= */ 0.4f);
    verifyStandardAdGroupPlaybackEvents(
        inOrder,
        mockSdkBridge,
        ADS_ID,
        /* adGroupIndex= */ 0,
        /* startAdIndexInAdGroup= */ 0,
        /* endAdIndexInAdGroup= */ 0);
    verify(mockSdkBridge).onVolumeChanged(any(), anyFloat());
  }

  @Test
  public void
      play_volumeChangedInMultiItemAdsPlaylist_broadcastsImmediatelyWithoutDuplicateOnTransitions()
          throws Exception {
    AdsMediaSource adsMediaSource1 = createSinglePrerollAdsMediaSource(ADS_ID, AD_MEDIA_ITEM_0);
    AdsMediaSource adsMediaSource2 =
        createSinglePrerollAdsMediaSource(SECOND_ADS_ID, AD_MEDIA_ITEM_1);
    player.setVolume(1f);
    player.setMediaSources(ImmutableList.of(adsMediaSource1, adsMediaSource2));
    player.prepare();
    player.play();
    runMainLooperUntil(() -> adPlaybackTracker.isSessionActive(SECOND_ADS_ID));
    assertThat(player.getCurrentMediaItemIndex()).isEqualTo(0);
    player.setVolume(0.3f);
    advance(player).untilPendingCommandsAreFullyHandled();
    advance(player).untilPosition(/* mediaItemIndex= */ 1, /* positionMs= */ 2_000L);
    player.setVolume(0.7f);
    advance(player).untilPendingCommandsAreFullyHandled();
    verify(mockSdkBridge).onVolumeChanged(ADS_ID, /* volume= */ 0.7f);
    verify(mockSdkBridge).onVolumeChanged(SECOND_ADS_ID, /* volume= */ 0.7f);

    player.seekTo(/* mediaItemIndex= */ 0, /* positionMs= */ 0L);
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 2_000L);

    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder
        .verify(mockSdkBridge)
        .onSessionStarted(ADS_ID, adsMediaSource1.getMediaItem(), adViewProvider, /* volume= */ 1f);
    inOrder
        .verify(mockSdkBridge)
        .onSessionStarted(
            SECOND_ADS_ID, adsMediaSource2.getMediaItem(), adViewProvider, /* volume= */ 1f);
    inOrder.verify(mockSdkBridge).onVolumeChanged(ADS_ID, /* volume= */ 0.3f);
    inOrder.verify(mockSdkBridge).onVolumeChanged(SECOND_ADS_ID, /* volume= */ 0.3f);
    verifyStandardAdGroupPlaybackEvents(
        inOrder,
        mockSdkBridge,
        SECOND_ADS_ID,
        /* adGroupIndex= */ 0,
        /* startAdIndexInAdGroup= */ 0,
        /* endAdIndexInAdGroup= */ 0);
    inOrder.verify(mockSdkBridge).onVolumeChanged(ADS_ID, /* volume= */ 0.7f);
    inOrder.verify(mockSdkBridge).onVolumeChanged(SECOND_ADS_ID, /* volume= */ 0.7f);
    verify(mockSdkBridge, times(2)).onVolumeChanged(eq(ADS_ID), anyFloat());
    verify(mockSdkBridge, times(2)).onVolumeChanged(eq(SECOND_ADS_ID), anyFloat());
  }

  @Test
  public void play_volumeChangedAndRevertedBeforeTransition_notifiesBothChangesAndNoneOnTransition()
      throws Exception {
    FakeMediaSource regularMediaSource =
        new FakeMediaSource(createShortContentTimeline(), ExoPlayerTestRunner.VIDEO_FORMAT);
    AdsMediaSource adsMediaSource = createSinglePrerollAdsMediaSource(ADS_ID, AD_MEDIA_ITEM_0);
    player.setVolume(1f);
    player.setMediaSources(ImmutableList.of(regularMediaSource, adsMediaSource));
    player.prepare();
    player.play();
    runMainLooperUntil(() -> adPlaybackTracker.isSessionActive(ADS_ID));
    assertThat(player.getCurrentMediaItemIndex()).isEqualTo(0);
    player.setVolume(0.4f);
    advance(player).untilPendingCommandsAreFullyHandled();
    player.setVolume(1f);
    advance(player).untilPendingCommandsAreFullyHandled();

    advance(player).untilPosition(/* mediaItemIndex= */ 1, /* positionMs= */ 2_000L);

    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder
        .verify(mockSdkBridge)
        .onSessionStarted(ADS_ID, adsMediaSource.getMediaItem(), adViewProvider, /* volume= */ 1f);
    inOrder.verify(mockSdkBridge).onVolumeChanged(ADS_ID, /* volume= */ 0.4f);
    inOrder.verify(mockSdkBridge).onVolumeChanged(ADS_ID, /* volume= */ 1f);
    verifyStandardAdGroupPlaybackEvents(
        inOrder,
        mockSdkBridge,
        ADS_ID,
        /* adGroupIndex= */ 0,
        /* startAdIndexInAdGroup= */ 0,
        /* endAdIndexInAdGroup= */ 0);
    verify(mockSdkBridge, times(2)).onVolumeChanged(any(), anyFloat());
  }

  @Test
  public void play_withCustomTrackerLooper_confinesBridgeCallbacksToTrackerLooper()
      throws Exception {
    List<Looper> callbackLoopers = new CopyOnWriteArrayList<>();
    List<String> callbackEvents = new CopyOnWriteArrayList<>();
    AdPlaybackTracker.SdkBridge threadRecordingBridge =
        new AdPlaybackTracker.SdkBridge() {
          @Override
          public void onPlayerChanged(@Nullable Player player) {
            record("onPlayerChanged");
          }

          @Override
          public void onSessionStarted(
              Object adsId,
              MediaItem contentMediaItem,
              AdViewProvider adViewProvider,
              float volume) {
            record("onSessionStarted");
          }

          @Override
          public void onAdPrepareCompleted(Object adsId, int adGroupIndex, int adIndexInAdGroup) {
            record("onAdPrepareCompleted");
          }

          @Override
          public void onAdGroupStarted(Object adsId, int adGroupIndex) {
            record("onAdGroupStarted");
          }

          @Override
          public void onAdStarted(Object adsId, int adGroupIndex, int adIndexInAdGroup) {
            record("onAdStarted");
          }

          @Override
          public void onAdCompleted(Object adsId, int adGroupIndex, int adIndexInAdGroup) {
            record("onAdCompleted");
          }

          @Override
          public void onAdGroupEnded(Object adsId, int adGroupIndex) {
            record("onAdGroupEnded");
          }

          @Override
          public void onAllAdsCompleted(Object adsId, AdPlaybackState finalAdPlaybackState) {
            record("onAllAdsCompleted");
          }

          @Override
          public void onContentCompleted(Object adsId) {
            record("onContentCompleted");
          }

          private void record(String event) {
            callbackLoopers.add(Looper.myLooper());
            callbackEvents.add(event);
          }
        };
    HandlerThread trackerThread = new HandlerThread("AdPlaybackTrackerTestThread");
    trackerThread.start();
    Looper customTrackerLooper = trackerThread.getLooper();
    ExoPlayer customLooperPlayer =
        new TestExoPlayerBuilder(context).setLooper(customTrackerLooper).build();
    AdPlaybackTracker customLooperTracker =
        new AdPlaybackTracker(threadRecordingBridge, customTrackerLooper);
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0);
    Timeline timelineWithAd =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setWindowPositionInFirstPeriodUs(0L)
                .setDurationUs(CONTENT_DURATION_US)
                .setAdPlaybackStates(ImmutableList.of(initialState))
                .build());
    FakeMediaSource mediaSourceWithAd =
        new FakeMediaSource(timelineWithAd, ExoPlayerTestRunner.VIDEO_FORMAT);

    try {
      new Handler(customTrackerLooper)
          .post(() -> {
            customLooperTracker.setPlayer(customLooperPlayer);
            customLooperTracker.startSession(
                createContentMediaItem(ADS_ID),
                adViewProvider,
                new AdsLoader.EventListener() {},
                initialState);
            customLooperTracker.handlePrepareComplete(
                ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
            customLooperPlayer.setMediaSource(mediaSourceWithAd);
            customLooperPlayer.prepare();
            customLooperPlayer.play();
          });
      runMainLooperUntil(() -> callbackEvents.contains("onContentCompleted"));
      shadowOf(customTrackerLooper).idle();

      assertThat(callbackEvents)
          .containsAtLeast(
              "onPlayerChanged",
              "onSessionStarted",
              "onAdPrepareCompleted",
              "onAdGroupStarted",
              "onAdStarted",
              "onAdCompleted",
              "onAdGroupEnded",
              "onAllAdsCompleted",
              "onContentCompleted")
          .inOrder();
      for (Looper callbackLooper : callbackLoopers) {
        assertThat(callbackLooper).isEqualTo(customTrackerLooper);
      }
    } finally {
      ConditionVariable releaseFinished = new ConditionVariable();
      new Handler(customTrackerLooper)
          .post(() -> {
            customLooperTracker.release();
            customLooperPlayer.release();
            releaseFinished.open();
          });
      releaseFinished.block(/* timeoutMs= */ 10_000);
      shadowOf(customTrackerLooper).idle();
      shadowOf(Looper.getMainLooper()).idle();
      trackerThread.quit();
    }
  }

  private AdsMediaSource createSinglePrerollAdsMediaSource(Object adsId, MediaItem adMediaItem) {
    AdPlaybackState initialState =
        new AdPlaybackState(adsId, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, adMediaItem);
    fakeAdsLoader.setInitialAdPlaybackState(adsId, initialState);
    return createAdsMediaSource(
        createShortContentTimeline(),
        createDefaultAdMediaSourceFactory(),
        fakeAdsLoader,
        adViewProvider,
        adsId);
  }

  private static Timeline createShortContentTimeline() {
    return new FakeTimeline(
        new TimelineWindowDefinition.Builder()
            .setWindowPositionInFirstPeriodUs(0L)
            .setDurationUs(10 * C.MICROS_PER_SECOND)
            .build());
  }
}
