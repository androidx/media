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
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.createContentMediaItem;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests verifying that {@link AdPlaybackTracker} never hands out or retains {@link
 * AdPlaybackState} instances whose array fields are shared with its internal state.
 */
@RunWith(AndroidJUnit4.class)
public final class AdPlaybackTrackerDefensiveCopyTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private AdPlaybackTracker.SdkBridge mockSdkBridge;

  private AdViewProvider adViewProvider;
  private AdsLoader.EventListener eventListener;
  private MediaItem contentMediaItem;
  private AdPlaybackTracker adPlaybackTracker;
  private ExoPlayer player;

  @Before
  public void setUp() {
    adViewProvider = () -> null;
    eventListener = new AdsLoader.EventListener() {};
    contentMediaItem = createContentMediaItem(ADS_ID);
    adPlaybackTracker = new AdPlaybackTracker(mockSdkBridge);
    player = new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build();
    adPlaybackTracker.setPlayer(player);
  }

  @After
  public void tearDown() {
    adPlaybackTracker.release();
    player.release();
  }

  @Test
  public void startSession_mutatingInitialStateArraysAfterwards_doesNotAffectTrackerState() {
    AdPlaybackState initialState = createAvailableAdState();
    adPlaybackTracker.startSession(contentMediaItem, adViewProvider, eventListener, initialState);

    initialState.getAdGroup(/* adGroupIndex= */ 0).states[0] = AdPlaybackState.AD_STATE_PLAYED;

    assertThat(adPlaybackTracker.getAdPlaybackState(ADS_ID)).isEqualTo(createAvailableAdState());
  }

  @Test
  public void getAdPlaybackState_mutatingReturnedArrays_doesNotAffectTrackerState() {
    adPlaybackTracker.startSession(
        contentMediaItem, adViewProvider, eventListener, createAvailableAdState());
    AdPlaybackState returnedState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(returnedState).isNotNull();

    returnedState.getAdGroup(/* adGroupIndex= */ 0).states[0] = AdPlaybackState.AD_STATE_PLAYED;
    returnedState.getAdGroup(/* adGroupIndex= */ 0).durationsUs[0] = 1;

    assertThat(adPlaybackTracker.getAdPlaybackState(ADS_ID)).isEqualTo(createAvailableAdState());
  }

  @Test
  public void updateAdPlaybackState_mutatorMutatesInputArrays_doesNotAffectTrackerState() {
    AdsLoader.EventListener mockEventListener = mock(AdsLoader.EventListener.class);
    adPlaybackTracker.startSession(
        contentMediaItem, adViewProvider, mockEventListener, createAvailableAdState());

    boolean updated =
        adPlaybackTracker.updateAdPlaybackState(
            ADS_ID,
            state -> {
              // Scribble on the input and return an unrelated, unchanged state. If the input were
              // the tracker's own instance, the scribble would leak into the tracker's state.
              state.getAdGroup(/* adGroupIndex= */ 0).states[0] = AdPlaybackState.AD_STATE_PLAYED;
              return createAvailableAdState();
            });

    assertThat(updated).isTrue();
    assertThat(adPlaybackTracker.getAdPlaybackState(ADS_ID)).isEqualTo(createAvailableAdState());
    // Only the initial publish; the unchanged state returned by the mutator is not re-published.
    verify(mockEventListener).onAdPlaybackState(createAvailableAdState());
  }

  @Test
  public void updateAdPlaybackState_mutatingReturnedStateArraysAfterwards_doesNotAffectTracker() {
    adPlaybackTracker.startSession(
        contentMediaItem, adViewProvider, eventListener, createAvailableAdState());
    AdPlaybackState[] retainedState = new AdPlaybackState[1];
    adPlaybackTracker.updateAdPlaybackState(
        ADS_ID,
        state -> {
          retainedState[0] =
              state
                  .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 2)
                  .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US, AD_DURATION_US);
          return retainedState[0];
        });
    AdPlaybackState expectedState = retainedState[0].copy();

    retainedState[0].getAdGroup(/* adGroupIndex= */ 0).states[1] = AdPlaybackState.AD_STATE_ERROR;
    retainedState[0].getAdGroup(/* adGroupIndex= */ 0).durationsUs[1] = 1;

    assertThat(adPlaybackTracker.getAdPlaybackState(ADS_ID)).isEqualTo(expectedState);
  }

  @Test
  public void updateAdPlaybackState_callerRetainsDurationsArray_doesNotAffectTrackerState() {
    adPlaybackTracker.startSession(
        contentMediaItem, adViewProvider, eventListener, createAvailableAdState());
    long[] durationsUs = new long[] {AD_DURATION_US};
    adPlaybackTracker.updateAdPlaybackState(
        ADS_ID, state -> state.withAdDurationsUs(/* adGroupIndex= */ 0, durationsUs));

    durationsUs[0] = 1;

    AdPlaybackState trackerState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(trackerState).isNotNull();
    assertThat(trackerState.getAdGroup(/* adGroupIndex= */ 0).durationsUs[0])
        .isEqualTo(AD_DURATION_US);
  }

  @Test
  public void eventListener_mutatingReceivedArrays_doesNotAffectTrackerState() {
    AdsLoader.EventListener mockEventListener = mock(AdsLoader.EventListener.class);
    adPlaybackTracker.startSession(
        contentMediaItem, adViewProvider, mockEventListener, createAvailableAdState());
    ArgumentCaptor<AdPlaybackState> stateCaptor = ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockEventListener).onAdPlaybackState(stateCaptor.capture());

    stateCaptor.getValue().getAdGroup(/* adGroupIndex= */ 0).states[0] =
        AdPlaybackState.AD_STATE_PLAYED;

    assertThat(adPlaybackTracker.getAdPlaybackState(ADS_ID)).isEqualTo(createAvailableAdState());
  }

  @Test
  public void adPlaybackStateListener_mutatingReceivedArrays_doesNotAffectTrackerState() {
    AdPlaybackTracker.AdPlaybackStateListener mockStateListener =
        mock(AdPlaybackTracker.AdPlaybackStateListener.class);
    adPlaybackTracker.addListener(mockStateListener);
    adPlaybackTracker.startSession(
        contentMediaItem, adViewProvider, eventListener, createAvailableAdState());
    ArgumentCaptor<AdPlaybackState> stateCaptor = ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockStateListener).onAdPlaybackStateChanged(eq(ADS_ID), stateCaptor.capture());

    stateCaptor.getValue().getAdGroup(/* adGroupIndex= */ 0).states[0] =
        AdPlaybackState.AD_STATE_PLAYED;

    assertThat(adPlaybackTracker.getAdPlaybackState(ADS_ID)).isEqualTo(createAvailableAdState());
  }

  @Test
  public void sdkBridgeOnAllAdsCompleted_mutatingReceivedArrays_doesNotAffectTrackerState() {
    adPlaybackTracker.startSession(
        contentMediaItem, adViewProvider, eventListener, createAvailableAdState());
    adPlaybackTracker.skipAd(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    AdPlaybackState expectedState =
        createAvailableAdState().withSkippedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    ArgumentCaptor<AdPlaybackState> stateCaptor = ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockSdkBridge).onAllAdsCompleted(eq(ADS_ID), stateCaptor.capture());
    assertThat(stateCaptor.getValue()).isEqualTo(expectedState);

    stateCaptor.getValue().getAdGroup(/* adGroupIndex= */ 0).states[0] =
        AdPlaybackState.AD_STATE_PLAYED;

    assertThat(adPlaybackTracker.getAdPlaybackState(ADS_ID)).isEqualTo(expectedState);
  }

  @Test
  public void stopSession_mutatingReturnedStateArrays_doesNotAffectBridgeState() {
    adPlaybackTracker.startSession(
        contentMediaItem, adViewProvider, eventListener, createAvailableAdState());
    AdPlaybackState returnedState = adPlaybackTracker.stopSession(ADS_ID);
    assertThat(returnedState).isNotNull();
    ArgumentCaptor<AdPlaybackState> stateCaptor = ArgumentCaptor.forClass(AdPlaybackState.class);
    verify(mockSdkBridge).onSessionStopped(eq(ADS_ID), stateCaptor.capture());
    AdPlaybackState bridgeState = stateCaptor.getValue();
    assertThat(bridgeState).isEqualTo(createAvailableAdState());
    assertThat(returnedState).isEqualTo(createAvailableAdState());

    returnedState.getAdGroup(/* adGroupIndex= */ 0).states[0] = AdPlaybackState.AD_STATE_PLAYED;

    assertThat(bridgeState).isEqualTo(createAvailableAdState());
  }

  private static AdPlaybackState createAvailableAdState() {
    return new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
        .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
        .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
        .withAvailableAdMediaItem(
            /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0);
  }
}
