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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link AdSession}, independent of a {@link Player}. */
@RunWith(AndroidJUnit4.class)
public final class AdSessionTest {

  private static final Object ADS_ID = "adsId";
  private static final MediaItem CONTENT_MEDIA_ITEM =
      MediaItem.fromUri("https://example.com/content.mp4");
  private static final MediaItem AD_MEDIA_ITEM_0 =
      MediaItem.fromUri("https://example.com/ad_0.mp4");
  private static final MediaItem AD_MEDIA_ITEM_1 =
      MediaItem.fromUri("https://example.com/ad_1.mp4");

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private AdPlaybackTracker.SdkBridge mockSdkBridge;

  private List<AdPlaybackState> eventListenerStates;
  private List<AdPlaybackState> callbackStates;
  private AdSession.Callback callback;

  @Before
  public void setUp() {
    eventListenerStates = new ArrayList<>();
    callbackStates = new ArrayList<>();
    callback =
        new AdSession.Callback() {
          @Override
          public void onAdPlaybackStateChanged(AdSession session, AdPlaybackState adPlaybackState) {
            callbackStates.add(adPlaybackState);
          }

          @Override
          public void notifyBridge(Consumer<AdPlaybackTracker.SdkBridge> action) {
            action.accept(mockSdkBridge);
          }
        };
  }

  @Test
  public void publishAdPlaybackState_initialState_publishesToEventListenerAndCallback() {
    AdPlaybackState initialState = createTwoAdState();
    AdSession session = createSession(initialState);

    session.publishAdPlaybackState();

    assertThat(eventListenerStates).containsExactly(initialState);
    assertThat(callbackStates).containsExactly(initialState);
    assertThat(session.getAdPlaybackState()).isEqualTo(initialState);
  }

  @Test
  public void startAd_firstAdOfSession_notifiesAdGroupStartedAndAdStarted() {
    AdSession session = createSession(createTwoAdState());

    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);

    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    assertThat(session.isInAdGroup()).isTrue();
    assertThat(session.isPlayingAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0)).isTrue();
  }

  @Test
  public void startAd_sameAdTwice_notifiesOnce() {
    AdSession session = createSession(createTwoAdState());

    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);

    verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    verify(mockSdkBridge).onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verifyNoMoreInteractions(mockSdkBridge);
  }

  @Test
  public void startAd_nextAdInSameAdGroup_notifiesAdStartedOnly() {
    AdSession session = createSession(createTwoAdState());
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);

    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, /* playWhenReady= */ true);

    verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    verify(mockSdkBridge).onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
    verify(mockSdkBridge, never()).onAdGroupEnded(any(), anyInt());
  }

  @Test
  public void startAd_adInDifferentAdGroup_endsPreviousAdGroupFirst() {
    AdSession session = createSession(createTwoAdGroupState());
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);

    session.startAd(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);

    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 1);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0);
  }

  @Test
  public void startAd_unsetIndices_isIgnored() {
    AdSession session = createSession(createTwoAdState());

    session.startAd(C.INDEX_UNSET, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);
    session.startAd(/* adGroupIndex= */ 0, C.INDEX_UNSET, /* playWhenReady= */ true);

    verifyNoMoreInteractions(mockSdkBridge);
    assertThat(session.isInAdGroup()).isFalse();
  }

  @Test
  public void setPlayWhenReady_togglesWhilePlayingAd_notifiesPausedAndResumedOnTransitionsOnly() {
    AdSession session = createSession(createTwoAdState());
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);

    session.setPlayWhenReady(false);
    session.setPlayWhenReady(false);
    session.setPlayWhenReady(true);
    session.setPlayWhenReady(true);

    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder
        .verify(mockSdkBridge)
        .onAdPaused(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdResumed(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void setPlayWhenReady_adStartedWhilePaused_notifiesResumedOnly() {
    AdSession session = createSession(createTwoAdState());
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ false);

    session.setPlayWhenReady(true);

    verify(mockSdkBridge).onAdResumed(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, never()).onAdPaused(any(), anyInt(), anyInt());
  }

  @Test
  public void setPlayWhenReady_notPlayingAd_isIgnored() {
    AdSession session = createSession(createTwoAdState());

    session.setPlayWhenReady(false);

    verifyNoMoreInteractions(mockSdkBridge);
  }

  @Test
  public void handleAdEnded_autoTransitionFromAvailableAd_marksPlayedAndNotifiesAdCompleted() {
    AdPlaybackState initialState = createTwoAdState();
    AdSession session = createSession(initialState);
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);

    session.handleAdEnded(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 0,
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        /* playWhenReady= */ true);

    verify(mockSdkBridge).onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    assertThat(session.getAdPlaybackState())
        .isEqualTo(initialState.withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));
    assertThat(eventListenerStates).containsExactly(session.getAdPlaybackState());
  }

  @Test
  public void handleAdEnded_autoTransitionFromUnstartedAd_startsAdBeforeCompletingIt() {
    AdSession session = createSession(createTwoAdState());

    session.handleAdEnded(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 0,
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        /* playWhenReady= */ true);

    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
  }

  @Test
  public void handleAdEnded_autoTransitionFromSkippedAd_startsAdAndNotifiesAdSkipped() {
    AdSession session = createSession(createTwoAdState());
    session.skipAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);

    session.handleAdEnded(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 0,
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        /* playWhenReady= */ true);

    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdSkipped(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, never()).onAdCompleted(any(), anyInt(), anyInt());
  }

  @Test
  public void handleAdEnded_sameAdTwice_notifiesOnce() {
    AdSession session = createSession(createTwoAdState());
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);

    session.handleAdEnded(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 0,
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        /* playWhenReady= */ true);
    session.handleAdEnded(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 0,
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        /* playWhenReady= */ true);

    verify(mockSdkBridge).onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
  }

  @Test
  public void handleAdEnded_unstartedAdTwice_notifiesOnce() {
    AdSession session = createSession(createTwoAdState());

    session.handleAdEnded(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 0,
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        /* playWhenReady= */ true);
    session.handleAdEnded(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 0,
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        /* playWhenReady= */ true);

    verify(mockSdkBridge).onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge).onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
  }

  @Test
  public void handleAdEnded_skipFromAvailableAd_marksSkippedAndNotifiesAdSkipped() {
    AdPlaybackState initialState = createTwoAdState();
    AdSession session = createSession(initialState);
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);

    session.handleAdEnded(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 0,
        Player.DISCONTINUITY_REASON_SKIP,
        /* playWhenReady= */ true);

    verify(mockSdkBridge).onAdSkipped(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, never()).onAdCompleted(any(), anyInt(), anyInt());
    assertThat(session.getAdPlaybackState())
        .isEqualTo(initialState.withSkippedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));
  }

  @Test
  public void handleAdEnded_skipFromPlayedAd_isIgnored() {
    AdSession session =
        createSession(
            createTwoAdState().withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));

    session.handleAdEnded(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 0,
        Player.DISCONTINUITY_REASON_SKIP,
        /* playWhenReady= */ true);

    verifyNoMoreInteractions(mockSdkBridge);
    assertThat(eventListenerStates).isEmpty();
  }

  @Test
  public void handleAdEnded_seekReason_isIgnored() {
    AdSession session = createSession(createTwoAdState());
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);

    session.handleAdEnded(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 0,
        Player.DISCONTINUITY_REASON_SEEK,
        /* playWhenReady= */ true);

    verify(mockSdkBridge, never()).onAdCompleted(any(), anyInt(), anyInt());
    verify(mockSdkBridge, never()).onAdSkipped(any(), anyInt(), anyInt());
  }

  @Test
  public void handleAdEnded_activeAdEnded_ignoresSubsequentPauseResumeAndBuffering() {
    AdSession session = createSession(createTwoAdState());
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);

    session.handleAdEnded(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 0,
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        /* playWhenReady= */ true);
    session.setPlayWhenReady(false);
    session.setPlayWhenReady(true);
    session.notifyAdBuffering(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);

    verify(mockSdkBridge, never()).onAdPaused(any(), anyInt(), anyInt());
    verify(mockSdkBridge, never()).onAdResumed(any(), anyInt(), anyInt());
    verify(mockSdkBridge, never()).onAdBuffering(any(), anyInt(), anyInt());
    assertThat(session.isPlayingAd()).isFalse();
  }

  @Test
  public void endAdGroup_inAdGroup_notifiesAdGroupEndedAndResetsState() {
    AdSession session = createSession(createTwoAdState());
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);

    session.endAdGroup();

    verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    assertThat(session.isInAdGroup()).isFalse();
    assertThat(session.isPlayingAd()).isFalse();
  }

  @Test
  public void endAdGroup_notInAdGroup_doesNotNotifyAdGroupEnded() {
    AdSession session = createSession(createTwoAdState());

    session.endAdGroup();

    verify(mockSdkBridge, never()).onAdGroupEnded(any(), anyInt());
  }

  @Test
  public void endAdGroup_afterLastAdCompleted_notifiesAllAdsCompletedAfterAdGroupEnded() {
    AdSession session = createSession(createTwoAdState());
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);
    session.handleAdEnded(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 0,
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        /* playWhenReady= */ true);
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, /* playWhenReady= */ true);
    session.handleAdEnded(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 1,
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        /* playWhenReady= */ true);
    verify(mockSdkBridge, never()).onAllAdsCompleted(any(), any());

    session.endAdGroup();

    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, session.getAdPlaybackState());
  }

  @Test
  public void skipAd_availableAd_marksSkippedAndPublishes() {
    AdPlaybackState initialState = createTwoAdState();
    AdSession session = createSession(initialState);

    session.skipAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);

    AdPlaybackState expectedState =
        initialState.withSkippedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
    assertThat(session.getAdPlaybackState()).isEqualTo(expectedState);
    assertThat(eventListenerStates).containsExactly(expectedState);
    assertThat(callbackStates).containsExactly(expectedState);
  }

  @Test
  public void skipAd_playedAd_isIgnored() {
    AdSession session =
        createSession(
            createTwoAdState().withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));

    session.skipAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);

    assertThat(eventListenerStates).isEmpty();
  }

  @Test
  public void skipAd_indexBeyondLoadedAdsWithUnsetAdCount_marksSkipped() {
    AdPlaybackState initialState = new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L);
    AdSession session = createSession(initialState);

    session.skipAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 2);

    assertThat(session.getAdPlaybackState().getAdGroup(/* adGroupIndex= */ 0).states[2])
        .isEqualTo(AdPlaybackState.AD_STATE_SKIPPED);
  }

  @Test
  public void skipAd_indexBeyondAdCount_isIgnored() {
    AdSession session = createSession(createTwoAdState());

    session.skipAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 2);
    session.skipAd(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0);

    assertThat(eventListenerStates).isEmpty();
  }

  @Test
  public void skipAd_lastUnplayedAdNotInAdGroup_notifiesAllAdsCompleted() {
    AdSession session =
        createSession(
            createTwoAdState().withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));

    session.skipAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);

    verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, session.getAdPlaybackState());
  }

  @Test
  public void skipAd_lastUnplayedAdWhileInAdGroup_doesNotNotifyAllAdsCompleted() {
    AdSession session =
        createSession(
            createTwoAdState().withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, /* playWhenReady= */ true);

    session.skipAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);

    verify(mockSdkBridge, never()).onAllAdsCompleted(any(), any());
  }

  @Test
  public void skipAdGroup_withPlayedAndUnplayedAds_skipsUnplayedAdsOnly() {
    AdPlaybackState initialState =
        createTwoAdState().withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    AdSession session = createSession(initialState);

    session.skipAdGroup(/* adGroupIndex= */ 0);

    AdPlaybackState.AdGroup adGroup = session.getAdPlaybackState().getAdGroup(0);
    assertThat(adGroup.states)
        .asList()
        .containsExactly(AdPlaybackState.AD_STATE_PLAYED, AdPlaybackState.AD_STATE_SKIPPED)
        .inOrder();
    assertThat(eventListenerStates).hasSize(1);
  }

  @Test
  public void skipAdGroup_invalidAdGroupIndex_isIgnored() {
    AdSession session = createSession(createTwoAdState());

    session.skipAdGroup(/* adGroupIndex= */ 1);

    assertThat(eventListenerStates).isEmpty();
  }

  @Test
  public void skipAdGroup_livePostrollPlaceholder_isIgnored() {
    AdPlaybackState initialState =
        createTwoAdState().withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ true);
    AdSession session = createSession(initialState);

    session.skipAdGroup(/* adGroupIndex= */ 1);

    assertThat(session.getAdPlaybackState()).isEqualTo(initialState);
    assertThat(eventListenerStates).isEmpty();
    verifyNoMoreInteractions(mockSdkBridge);
  }

  @Test
  public void handlePrepareError_livePostrollPlaceholder_isIgnored() {
    AdPlaybackState initialState =
        createTwoAdState().withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ true);
    AdSession session = createSession(initialState);
    IOException error = new IOException("prepare failed");

    session.handlePrepareError(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, error);

    assertThat(session.getAdPlaybackState()).isEqualTo(initialState);
    assertThat(eventListenerStates).isEmpty();
    verifyNoMoreInteractions(mockSdkBridge);
  }

  @Test
  public void setAdPlaybackState_changedState_publishesOnce() {
    AdPlaybackState initialState = createTwoAdState();
    AdSession session = createSession(initialState);
    AdPlaybackState updatedState =
        initialState.withAdDurationsUs(/* adGroupIndex= */ 0, 1_000L, 2_000L);

    boolean changed = session.setAdPlaybackState(updatedState);
    boolean changedAgain = session.setAdPlaybackState(updatedState);

    assertThat(changed).isTrue();
    assertThat(changedAgain).isFalse();
    assertThat(eventListenerStates).containsExactly(updatedState);
  }

  @Test
  public void setAdPlaybackState_allAdsPlayedNotInAdGroup_notifiesAllAdsCompleted() {
    AdPlaybackState initialState = createTwoAdState();
    AdSession session = createSession(initialState);
    AdPlaybackState allPlayedState =
        initialState
            .withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0)
            .withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);

    session.setAdPlaybackState(allPlayedState);
    session.setAdPlaybackState(allPlayedState.withAdDurationsUs(/* adGroupIndex= */ 0, 1L, 2L));

    verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, allPlayedState);
  }

  @Test
  public void setAdPlaybackState_reactivatingEndedAd_removesFromEndedAdsAndResetsCompletionFlags() {
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0);
    AdSession session = createSession(initialState);
    session.handleAdEnded(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 0,
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        /* playWhenReady= */ true);
    session.endAdGroup();
    session.notifyContentCompleted();

    session.setAdPlaybackState(
        session.getAdPlaybackState().withResetAdGroup(/* adGroupIndex= */ 0));
    session.handleAdEnded(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 0,
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        /* playWhenReady= */ true);
    session.endAdGroup();
    session.notifyContentCompleted();

    verify(mockSdkBridge, times(2))
        .onAdCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, times(2)).onAllAdsCompleted(eq(ADS_ID), any());
    verify(mockSdkBridge, times(2)).onContentCompleted(ADS_ID);
  }

  @Test
  public void setAdPlaybackState_addingNewAdGroupAfterAllAdsCompleted_resetsAllAdsCompletedFlag() {
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0)
            .withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    AdSession session = createSession(initialState);
    session.endAdGroup();

    AdPlaybackState stateWithNewAdGroup =
        session
            .getAdPlaybackState()
            .withNewAdGroup(/* adGroupIndex= */ 1, /* adGroupTimeUs= */ 10 * C.MICROS_PER_SECOND)
            .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_1);
    session.setAdPlaybackState(stateWithNewAdGroup);
    session.setAdPlaybackState(
        stateWithNewAdGroup.withPlayedAd(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0));

    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, initialState);
    inOrder
        .verify(mockSdkBridge)
        .onAllAdsCompleted(
            ADS_ID,
            stateWithNewAdGroup.withPlayedAd(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0));
  }

  @Test
  public void setAdPlaybackState_unplayedAdMarkedError_notifiesAdErrorAndAllAdsCompleted() {
    AdPlaybackState initialState =
        createTwoAdState().withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    AdSession session = createSession(initialState);
    AdPlaybackState updatedState =
        initialState.withAdLoadError(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);

    session.setAdPlaybackState(updatedState);
    session.handleAdEnded(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 1,
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        /* playWhenReady= */ true);

    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder
        .verify(mockSdkBridge)
        .onAdError(
            eq(ADS_ID),
            /* adGroupIndex= */ eq(0),
            /* adIndexInAdGroup= */ eq(1),
            any(IOException.class));
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, updatedState);
    verify(mockSdkBridge, never()).onAdStarted(any(), anyInt(), anyInt());
  }

  @Test
  public void setAdPlaybackState_unplayedAdMarkedPlayed_addsToEndedAdsAndNotifiesAllAdsCompleted() {
    AdPlaybackState initialState =
        createTwoAdState().withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    AdSession session = createSession(initialState);
    AdPlaybackState updatedState =
        initialState.withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);

    session.setAdPlaybackState(updatedState);
    session.handleAdEnded(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 1,
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        /* playWhenReady= */ true);

    verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, updatedState);
    verify(mockSdkBridge, never()).onAdStarted(any(), anyInt(), anyInt());
    verify(mockSdkBridge, never()).onAdCompleted(any(), anyInt(), anyInt());
  }

  @Test
  public void setAdPlaybackState_withRemovedAdGroup_syncsNonRemovedAdGroupsOnly() {
    AdPlaybackState initialState = createTwoAdGroupState();
    AdSession session = createSession(initialState);
    AdPlaybackState updatedState =
        initialState
            .withRemovedAdGroupCount(1)
            .withPlayedAd(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0);

    session.setAdPlaybackState(updatedState);

    assertThat(session.getAdPlaybackState()).isEqualTo(updatedState);
    verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, updatedState);
  }

  @Test
  public void setAdPlaybackState_invalidModification_throwsIllegalStateException() {
    AdPlaybackState initialState = createTwoAdState();
    AdSession session = createSession(initialState);
    AdPlaybackState stateWithOtherAdsId =
        new AdPlaybackState("otherAdsId", /* adGroupTimesUs...= */ 0L);
    AdPlaybackState stateWithFewerAdGroups = new AdPlaybackState(ADS_ID);

    assertThrows(
        IllegalStateException.class, () -> session.setAdPlaybackState(stateWithOtherAdsId));
    assertThrows(
        IllegalStateException.class, () -> session.setAdPlaybackState(stateWithFewerAdGroups));

    assertThat(session.getAdPlaybackState()).isEqualTo(initialState);
    assertThat(eventListenerStates).isEmpty();
  }

  @Test
  public void resetAllAds_withResetErrorAdsTrue_resetsPlayedSkippedAndErroredAds() {
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 3)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0)
            .withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, AD_MEDIA_ITEM_1)
            .withSkippedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 2, AD_MEDIA_ITEM_0)
            .withAdLoadError(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 2)
            .withAdResumePositionUs(5_000_000L);
    AdSession session = createSession(initialState);
    session.notifyContentCompleted();

    session.resetAllAds(/* resetErrorAds= */ true);
    session.notifyContentCompleted();

    AdPlaybackState resetState = session.getAdPlaybackState();
    assertThat(resetState.adResumePositionUs).isEqualTo(0L);
    assertThat(resetState.getAdGroup(0).states)
        .asList()
        .containsExactly(
            AdPlaybackState.AD_STATE_AVAILABLE,
            AdPlaybackState.AD_STATE_AVAILABLE,
            AdPlaybackState.AD_STATE_AVAILABLE)
        .inOrder();
    verify(mockSdkBridge, times(2)).onContentCompleted(ADS_ID);
  }

  @Test
  public void resetAllAds_withResetErrorAdsFalse_preservesErroredAdsAndResetsPlayedAndSkippedAds() {
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 3)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0)
            .withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, AD_MEDIA_ITEM_1)
            .withSkippedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 2, AD_MEDIA_ITEM_0)
            .withAdLoadError(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 2);
    AdSession session = createSession(initialState);

    session.resetAllAds(/* resetErrorAds= */ false);

    assertThat(session.getAdPlaybackState().getAdGroup(0).states)
        .asList()
        .containsExactly(
            AdPlaybackState.AD_STATE_AVAILABLE,
            AdPlaybackState.AD_STATE_AVAILABLE,
            AdPlaybackState.AD_STATE_ERROR)
        .inOrder();
    verify(mockSdkBridge, never()).onAdError(any(), anyInt(), anyInt(), any());
  }

  @Test
  public void resetAllAds_withRemovedAdGroup_resetsNonRemovedAdGroupsOnly() {
    AdPlaybackState initialState =
        createTwoAdGroupState()
            .withRemovedAdGroupCount(1)
            .withPlayedAd(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0);
    AdSession session = createSession(initialState);

    session.resetAllAds(/* resetErrorAds= */ true);

    AdPlaybackState resetState = session.getAdPlaybackState();
    assertThat(resetState.removedAdGroupCount).isEqualTo(1);
    assertThat(resetState.getAdGroup(/* adGroupIndex= */ 1).states)
        .asList()
        .containsExactly(AdPlaybackState.AD_STATE_AVAILABLE);
  }

  @Test
  public void handlePrepareError_unplayedAd_marksErrorThenNotifiesAdErrorThenAllAdsCompleted() {
    AdPlaybackState initialState =
        createTwoAdState().withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    AdSession session = createSession(initialState);
    IOException error = new IOException("prepare failed");

    session.handlePrepareError(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, error);

    AdPlaybackState expectedState =
        initialState.withAdLoadError(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
    assertThat(eventListenerStates).containsExactly(expectedState);
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder
        .verify(mockSdkBridge)
        .onAdError(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, error);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, expectedState);
  }

  @Test
  public void handlePrepareError_playedAd_notifiesAdErrorWithoutStateChange() {
    AdSession session =
        createSession(
            createTwoAdState().withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));
    IOException error = new IOException("prepare failed");

    session.handlePrepareError(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, error);

    assertThat(eventListenerStates).isEmpty();
    verify(mockSdkBridge)
        .onAdError(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, error);
  }

  @Test
  public void handlePrepareError_invalidIndices_isIgnored() {
    AdSession session = createSession(createTwoAdState());
    IOException error = new IOException("prepare failed");

    session.handlePrepareError(C.INDEX_UNSET, /* adIndexInAdGroup= */ 0, error);
    session.handlePrepareError(/* adGroupIndex= */ 0, C.INDEX_UNSET, error);
    session.handlePrepareError(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, error);
    session.handlePrepareError(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 2, error);

    assertThat(eventListenerStates).isEmpty();
    verifyNoMoreInteractions(mockSdkBridge);
  }

  @Test
  public void handlePlaybackError_unplayedAd_marksErrorAndNotifiesAdErrorOnly() {
    AdPlaybackState initialState =
        createTwoAdState().withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    AdSession session = createSession(initialState);
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, /* playWhenReady= */ true);
    Exception error = new Exception("playback failed");

    session.handlePlaybackError(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, error);

    assertThat(eventListenerStates)
        .containsExactly(
            initialState.withAdLoadError(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1));
    verify(mockSdkBridge)
        .onAdError(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, error);
    verify(mockSdkBridge, never()).onAllAdsCompleted(any(), any());
  }

  @Test
  public void reconcileActiveAd_activeAdSkippedAndPlayerLeftAdGroup_notifiesSkippedAndGroupEnded() {
    AdSession session = createSession(createTwoAdState());
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);
    session.skipAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);

    session.reconcileActiveAd(/* isPlayingAdInSession= */ false, C.INDEX_UNSET, C.INDEX_UNSET);

    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder
        .verify(mockSdkBridge)
        .onAdSkipped(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    assertThat(session.isInAdGroup()).isFalse();
  }

  @Test
  public void reconcileActiveAd_activeAdSkippedAndPlayerMovedToNextAd_staysInAdGroup() {
    AdSession session = createSession(createTwoAdState());
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);
    session.skipAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);

    session.reconcileActiveAd(
        /* isPlayingAdInSession= */ true,
        /* currentAdGroupIndex= */ 0,
        /* currentAdIndexInAdGroup= */ 1);

    verify(mockSdkBridge).onAdSkipped(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, never()).onAdGroupEnded(any(), anyInt());
    assertThat(session.isInAdGroup()).isTrue();
    assertThat(session.isPlayingAd()).isFalse();
  }

  @Test
  public void reconcileActiveAd_stillPlayingSameAd_isIgnored() {
    AdSession session = createSession(createTwoAdState());
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);
    session.skipAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);

    session.reconcileActiveAd(
        /* isPlayingAdInSession= */ true,
        /* currentAdGroupIndex= */ 0,
        /* currentAdIndexInAdGroup= */ 0);

    verify(mockSdkBridge, never()).onAdSkipped(any(), anyInt(), anyInt());
    assertThat(session.isPlayingAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0)).isTrue();
  }

  @Test
  public void reconcileActiveAd_activeAdStillAvailable_isIgnored() {
    AdSession session = createSession(createTwoAdState());
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);

    session.reconcileActiveAd(/* isPlayingAdInSession= */ false, C.INDEX_UNSET, C.INDEX_UNSET);

    verify(mockSdkBridge, never()).onAdGroupEnded(any(), anyInt());
    assertThat(session.isPlayingAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0)).isTrue();
  }

  @Test
  public void
      reconcileActiveAd_lastAdMarkedPlayedAndPlayerLeftAdGroup_notifiesGroupEndedAndAllAdsCompletedWithoutAdCompleted() {
    AdSession session =
        createSession(
            createTwoAdState().withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, /* playWhenReady= */ true);
    session.setAdPlaybackState(
        session
            .getAdPlaybackState()
            .withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1));

    session.reconcileActiveAd(/* isPlayingAdInSession= */ false, C.INDEX_UNSET, C.INDEX_UNSET);

    verify(mockSdkBridge, never()).onAdCompleted(any(), anyInt(), anyInt());
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, session.getAdPlaybackState());
    assertThat(session.isInAdGroup()).isFalse();
  }

  @Test
  public void notifyAdBuffering_matchesPlayingAd_notifiesAdBuffering() {
    AdSession session = createSession(createTwoAdState());
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);

    session.notifyAdBuffering(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    session.notifyAdBuffering(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);

    verify(mockSdkBridge).onAdBuffering(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, never())
        .onAdBuffering(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
  }

  @Test
  public void notifyContentCompleted_calledTwice_notifiesOnce() {
    AdSession session = createSession(createTwoAdState());

    session.notifyContentCompleted();
    session.notifyContentCompleted();

    verify(mockSdkBridge).onContentCompleted(ADS_ID);
  }

  @Test
  public void stop_activeSession_notifiesSessionStoppedWithFinalState() {
    AdSession session = createSession(createTwoAdState());
    session.skipAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);

    session.stop();

    verify(mockSdkBridge).onSessionStopped(ADS_ID, session.getAdPlaybackState());
  }

  @Test
  public void stop_whilePlayingAd_resetsActiveAd() {
    AdSession session = createSession(createTwoAdState());
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, /* playWhenReady= */ true);

    session.stop();
    session.setPlayWhenReady(false);
    session.setPlayWhenReady(true);
    session.notifyAdBuffering(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    session.handleAdEnded(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 0,
        Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        /* playWhenReady= */ true);
    session.endAdGroup();
    session.startAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, /* playWhenReady= */ true);
    session.handlePlaybackError(
        /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, new IOException("playback error"));
    session.handlePrepareError(
        /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, new IOException("prepare error"));
    session.notifyContentCompleted();

    assertThat(session.isInAdGroup()).isFalse();
    assertThat(session.isPlayingAd()).isFalse();
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder.verify(mockSdkBridge).onSessionStopped(ADS_ID, session.getAdPlaybackState());
    verifyNoMoreInteractions(mockSdkBridge);
  }

  @Test
  public void endAdGroup_afterStop_doesNotNotifyAllAdsCompleted() {
    AdSession session =
        createSession(
            createTwoAdState()
                .withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0)
                .withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1));
    session.stop();

    session.endAdGroup();

    verify(mockSdkBridge, never()).onAllAdsCompleted(any(), any());
  }

  private AdSession createSession(AdPlaybackState initialAdPlaybackState) {
    AdsLoader.EventListener eventListener =
        new AdsLoader.EventListener() {
          @Override
          public void onAdPlaybackState(AdPlaybackState adPlaybackState) {
            eventListenerStates.add(adPlaybackState);
          }
        };
    return new AdSession(CONTENT_MEDIA_ITEM, eventListener, initialAdPlaybackState, callback);
  }

  /** Returns a state with a single preroll ad group containing two available ads. */
  private static AdPlaybackState createTwoAdState() {
    return new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
        .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 2)
        .withAvailableAdMediaItem(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0)
        .withAvailableAdMediaItem(
            /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1, AD_MEDIA_ITEM_1);
  }

  /** Returns a state with a preroll and a midroll ad group, each containing one available ad. */
  private static AdPlaybackState createTwoAdGroupState() {
    return new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L, 10 * C.MICROS_PER_SECOND)
        .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
        .withAvailableAdMediaItem(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0)
        .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)
        .withAvailableAdMediaItem(
            /* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_1);
  }
}
