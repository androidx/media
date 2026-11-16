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
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.createContentMediaItem;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.createDefaultAdMediaSourceFactory;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.createVodContentTimeline;
import static androidx.media3.exoplayer.source.ads.AdPlaybackTrackerTestUtil.verifyStandardAdGroupPlaybackEvents;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.test.utils.FakeAdsLoader;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.base.Defaults;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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

/** Unit tests for {@link AdPlaybackTracker} session lifecycle and player attachment. */
@RunWith(AndroidJUnit4.class)
public final class AdPlaybackTrackerSessionLifecycleTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  private Context context;
  @Mock private AdPlaybackTracker.SdkBridge mockSdkBridge;

  private AdViewProvider adViewProvider;
  private AdPlaybackTracker adPlaybackTracker;
  private FakeAdsLoader fakeAdsLoader;
  private ExoPlayer player;
  private @Nullable AdPlaybackTracker standaloneTracker;

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
    if (standaloneTracker != null) {
      standaloneTracker.release();
    }
    player.release();
    fakeAdsLoader.release();
  }

  @Test
  public void setPlayer_beforeAndAfterStartSession_registersAndUnregistersPlayerListener()
      throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
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
    adPlaybackTracker.setPlayer(null);
    player.play();
    advance(player).untilPlayWhenReadyIs(true);

    assertThat(adPlaybackTracker.getPlayer()).isNull();
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onPlayerChanged(player);
    inOrder
        .verify(mockSdkBridge)
        .onSessionStarted(ADS_ID, adsMediaSource.getMediaItem(), adViewProvider);
    inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdPaused(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
    inOrder.verify(mockSdkBridge).onPlayerChanged(null);
    verify(mockSdkBridge, never()).onAdResumed(eq(ADS_ID), anyInt(), anyInt());
  }

  @Test
  public void setPlayer_afterPlayerReleaseWithNonIdleLooper_succeedsAndReplacesPlayer()
      throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
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

    // Releasing player posts adsLoader.stop(...) to the main looper. In common
    // release/initialize sequences, the session is still registered when setting the new player.
    player.release();
    boolean isActiveAfterPlayerRelease = adPlaybackTracker.isSessionActive(ADS_ID);

    ExoPlayer secondPlayer = new TestExoPlayerBuilder(context).build();
    try {

      adPlaybackTracker.setPlayer(secondPlayer);
      // Wait for the queued stop callback posted by AdsMediaSource during release.
      shadowOf(Looper.getMainLooper()).idle();

      assertThat(isActiveAfterPlayerRelease).isTrue();
      assertThat(adPlaybackTracker.getPlayer()).isSameInstanceAs(secondPlayer);
      InOrder inOrder = inOrder(mockSdkBridge);
      inOrder.verify(mockSdkBridge).onPlayerChanged(player);
      inOrder
          .verify(mockSdkBridge)
          .onSessionStarted(ADS_ID, adsMediaSource.getMediaItem(), adViewProvider);
      inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
      inOrder
          .verify(mockSdkBridge)
          .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
      inOrder.verify(mockSdkBridge).onAdGroupEnded(ADS_ID, /* adGroupIndex= */ 0);
      inOrder.verify(mockSdkBridge).onPlayerChanged(secondPlayer);
      assertThat(adPlaybackTracker.isSessionActive(ADS_ID)).isFalse();
      inOrder.verify(mockSdkBridge).onSessionStopped(eq(ADS_ID), any(AdPlaybackState.class));
    } finally {
      secondPlayer.release();
    }
  }

  @Test
  public void setPlayer_samePlayer_doesNotNotifyPlayerChangedAgain() {
    adPlaybackTracker.setPlayer(player);

    assertThat(adPlaybackTracker.getPlayer()).isSameInstanceAs(player);
    verify(mockSdkBridge).onPlayerChanged(player);
    verifyNoMoreInteractions(mockSdkBridge);
  }

  @Test
  public void setPlayer_replacingPlayer_movesListenerToNewPlayer() throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
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
    ExoPlayer secondPlayer = new TestExoPlayerBuilder(context).build();
    try {
      adPlaybackTracker.setPlayer(secondPlayer);
      secondPlayer.setMediaSource(adsMediaSource);
      secondPlayer.prepare();
      advance(secondPlayer).untilState(Player.STATE_READY);

      player.play();
      advance(player).untilPlayWhenReadyIs(true);

      verify(mockSdkBridge, never()).onAdResumed(eq(ADS_ID), anyInt(), anyInt());

      secondPlayer.play();
      advance(secondPlayer).untilPlayWhenReadyIs(true);

      assertThat(adPlaybackTracker.getPlayer()).isSameInstanceAs(secondPlayer);
      InOrder inOrder = inOrder(mockSdkBridge);
      inOrder.verify(mockSdkBridge).onPlayerChanged(player);
      inOrder.verify(mockSdkBridge).onPlayerChanged(secondPlayer);
      inOrder
          .verify(mockSdkBridge)
          .onSessionStarted(ADS_ID, adsMediaSource.getMediaItem(), adViewProvider);
      inOrder
          .verify(mockSdkBridge)
          .onAdPrepareCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
      inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
      inOrder
          .verify(mockSdkBridge)
          .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
      inOrder
          .verify(mockSdkBridge)
          .onAdResumed(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    } finally {
      secondPlayer.release();
    }
  }

  @Test
  public void setPlayer_withMismatchedApplicationLooper_throwsIllegalArgumentException()
      throws Exception {
    HandlerThread otherThread = new HandlerThread("AdPlaybackTrackerTestThread");
    otherThread.start();
    ExoPlayer otherLooperPlayer =
        new TestExoPlayerBuilder(context).setLooper(otherThread.getLooper()).build();
    try {

      assertThrows(
          IllegalArgumentException.class, () -> adPlaybackTracker.setPlayer(otherLooperPlayer));

    } finally {
      ConditionVariable releaseFinished = new ConditionVariable();
      new Handler(otherThread.getLooper())
          .post(() -> {
            otherLooperPlayer.release();
            releaseFinished.open();
          });
      releaseFinished.block(/* timeoutMs= */ 10_000);
      shadowOf(otherThread.getLooper()).idle();
      otherThread.quit();
    }
  }

  @Test
  public void addListener_andRemoveListener_notifiesListenerAfterEventListenerOnStateChanges() {
    AdsLoader.EventListener mockEventListener = mock(AdsLoader.EventListener.class);
    AdPlaybackTracker.AdPlaybackStateListener mockStateListener =
        mock(AdPlaybackTracker.AdPlaybackStateListener.class);
    adPlaybackTracker.addListener(mockStateListener);
    MediaItem contentMediaItem = createContentMediaItem(ADS_ID);
    AdPlaybackState initialState = new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L);
    AdPlaybackState updatedState =
        initialState.withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
    AdPlaybackState skippedState =
        updatedState.withSkippedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);

    adPlaybackTracker.startSession(
        contentMediaItem, adViewProvider, mockEventListener, initialState);
    adPlaybackTracker.updateAdPlaybackState(
        ADS_ID, state -> state.withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1));
    adPlaybackTracker.removeListener(mockStateListener);
    adPlaybackTracker.skipAd(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);

    InOrder inOrder = inOrder(mockEventListener, mockStateListener, mockSdkBridge);
    // EventListener is called with initial state before the ad playback state listener is called.
    inOrder.verify(mockEventListener).onAdPlaybackState(initialState);
    inOrder.verify(mockStateListener).onAdPlaybackStateChanged(ADS_ID, initialState);
    inOrder.verify(mockSdkBridge).onSessionStarted(ADS_ID, contentMediaItem, adViewProvider);
    // Update state with ad count which isn't related to playback and doesn't notify the sdk bridge.
    inOrder.verify(mockEventListener).onAdPlaybackState(updatedState);
    inOrder.verify(mockStateListener).onAdPlaybackStateChanged(ADS_ID, updatedState);
    // After the listener is remove, it's not called again for further state changes.
    inOrder.verify(mockEventListener).onAdPlaybackState(skippedState);
    verify(mockStateListener, never()).onAdPlaybackStateChanged(ADS_ID, skippedState);
  }

  @Test
  public void startSession_withInitialAdPlaybackState_publishesStateAndNotifiesBridge() {
    AdsLoader.EventListener mockEventListener = mock(AdsLoader.EventListener.class);
    MediaItem contentMediaItem = createContentMediaItem(ADS_ID);
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);

    adPlaybackTracker.startSession(
        contentMediaItem, adViewProvider, mockEventListener, initialState);

    assertThat(adPlaybackTracker.isSessionActive(ADS_ID)).isTrue();
    assertThat(adPlaybackTracker.getActiveSessionAdsIds()).containsExactly(ADS_ID);
    assertThat(adPlaybackTracker.getAdPlaybackState(ADS_ID)).isEqualTo(initialState);
    verify(mockEventListener).onAdPlaybackState(initialState);
    verify(mockSdkBridge).onSessionStarted(ADS_ID, contentMediaItem, adViewProvider);
  }

  @Test
  public void startSession_withNullAdsId_throwsNullPointerException() {
    MediaItem contentMediaItem = createContentMediaItem(ADS_ID);

    assertThrows(
        NullPointerException.class,
        () ->
            adPlaybackTracker.startSession(
                contentMediaItem,
                adViewProvider,
                new AdsLoader.EventListener() {},
                AdPlaybackState.NONE));
  }

  @Test
  public void startSession_withoutAdsConfiguration_throwsNullPointerException() {
    MediaItem contentMediaItemWithoutAdsConfiguration =
        MediaItem.fromUri("https://example.com/content.mp4");

    assertThrows(
        NullPointerException.class,
        () ->
            adPlaybackTracker.startSession(
                contentMediaItemWithoutAdsConfiguration,
                adViewProvider,
                new AdsLoader.EventListener() {},
                new AdPlaybackState(ADS_ID)));
  }

  @Test
  public void startSession_withMismatchedAdsConfigurationAdsId_throwsIllegalArgumentException() {
    MediaItem contentMediaItemWithOtherAdsId = createContentMediaItem(SECOND_ADS_ID);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            adPlaybackTracker.startSession(
                contentMediaItemWithOtherAdsId,
                adViewProvider,
                new AdsLoader.EventListener() {},
                new AdPlaybackState(ADS_ID)));
  }

  @Test
  public void startSession_withDuplicateActiveAdsId_throwsIllegalArgumentException() {
    AdsLoader.EventListener eventListener = new AdsLoader.EventListener() {};
    MediaItem contentMediaItem = createContentMediaItem(ADS_ID);
    adPlaybackTracker.startSession(
        contentMediaItem, adViewProvider, eventListener, new AdPlaybackState(ADS_ID));
    AdPlaybackState duplicateState = new AdPlaybackState(ADS_ID);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            adPlaybackTracker.startSession(
                contentMediaItem, adViewProvider, eventListener, duplicateState));
  }

  @Test
  public void updateAdPlaybackState_mutatorChangesAdsId_throwsIllegalStateException() {
    MediaItem contentMediaItem = createContentMediaItem(ADS_ID);
    adPlaybackTracker.startSession(
        contentMediaItem,
        adViewProvider,
        new AdsLoader.EventListener() {},
        new AdPlaybackState(ADS_ID));

    assertThrows(
        IllegalStateException.class,
        () ->
            adPlaybackTracker.updateAdPlaybackState(
                ADS_ID, state -> state.withAdsId(new Object())));
  }

  @Test
  public void updateAdPlaybackState_mutatorReturnsNull_throwsNullPointerException() {
    MediaItem contentMediaItem = createContentMediaItem(ADS_ID);
    adPlaybackTracker.startSession(
        contentMediaItem,
        adViewProvider,
        new AdsLoader.EventListener() {},
        new AdPlaybackState(ADS_ID));

    assertThrows(
        NullPointerException.class,
        () -> adPlaybackTracker.updateAdPlaybackState(ADS_ID, state -> null));
  }

  @Test
  public void sdkBridgeReentrancy_stopSessionFromOnSessionStarted_stopsSessionInOrder() {
    List<String> events = new ArrayList<>();
    AtomicReference<AdPlaybackTracker> trackerRef = new AtomicReference<>();
    AdPlaybackTracker.SdkBridge reentrantBridge =
        new AdPlaybackTracker.SdkBridge() {
          @Override
          public void onSessionStarted(
              Object adsId, MediaItem contentMediaItem, AdViewProvider adViewProvider) {
            events.add("onSessionStarted");
            trackerRef.get().stopSession(adsId);
          }

          @Override
          public void onSessionStopped(Object adsId, AdPlaybackState finalAdPlaybackState) {
            events.add("onSessionStopped");
          }
        };
    standaloneTracker = new AdPlaybackTracker(reentrantBridge);
    standaloneTracker.setPlayer(player);
    trackerRef.set(standaloneTracker);
    MediaItem contentMediaItem = createContentMediaItem(ADS_ID);

    standaloneTracker.startSession(
        contentMediaItem,
        adViewProvider,
        new AdsLoader.EventListener() {},
        new AdPlaybackState(ADS_ID));

    assertThat(events).containsExactly("onSessionStarted", "onSessionStopped").inOrder();
    assertThat(standaloneTracker.isSessionActive(ADS_ID)).isFalse();
  }

  @Test
  public void sdkBridgeReentrancy_stopSessionFromOnAdError_doesNotNotifyAllAdsCompleted() {
    List<String> events = new ArrayList<>();
    AtomicReference<AdPlaybackTracker> trackerRef = new AtomicReference<>();
    AdPlaybackTracker.SdkBridge reentrantBridge =
        new AdPlaybackTracker.SdkBridge() {
          @Override
          public void onAdError(
              Object adsId, int adGroupIndex, int adIndexInAdGroup, Exception error) {
            events.add("onAdError");
            trackerRef.get().stopSession(adsId);
          }

          @Override
          public void onSessionStopped(Object adsId, AdPlaybackState finalAdPlaybackState) {
            events.add("onSessionStopped");
          }

          @Override
          public void onAllAdsCompleted(Object adsId, AdPlaybackState adPlaybackState) {
            events.add("onAllAdsCompleted");
          }
        };
    standaloneTracker = new AdPlaybackTracker(reentrantBridge);
    standaloneTracker.setPlayer(player);
    trackerRef.set(standaloneTracker);
    MediaItem contentMediaItem = createContentMediaItem(ADS_ID);
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
    standaloneTracker.startSession(
        contentMediaItem, adViewProvider, new AdsLoader.EventListener() {}, initialState);

    // Erroring the only ad leaves no unplayed ads, which would normally trigger onAllAdsCompleted.
    // The bridge re-entrantly stops the session from onAdError, which must suppress it.
    standaloneTracker.handlePrepareError(
        ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, new IOException("test"));

    assertThat(events).containsExactly("onAdError", "onSessionStopped").inOrder();
    assertThat(standaloneTracker.isSessionActive(ADS_ID)).isFalse();
  }

  @Test
  public void sdkBridgeReentrancy_updateAdPlaybackStateFromOnSessionStarted_publishesState() {
    AtomicReference<AdPlaybackTracker> trackerRef = new AtomicReference<>();
    AdPlaybackTracker.SdkBridge reentrantBridge =
        new AdPlaybackTracker.SdkBridge() {
          @Override
          public void onSessionStarted(
              Object adsId, MediaItem contentMediaItem, AdViewProvider adViewProvider) {
            trackerRef
                .get()
                .updateAdPlaybackState(
                    adsId, state -> state.withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1));
          }
        };
    standaloneTracker = new AdPlaybackTracker(reentrantBridge);
    standaloneTracker.setPlayer(player);
    trackerRef.set(standaloneTracker);
    AdsLoader.EventListener mockEventListener = mock(AdsLoader.EventListener.class);
    MediaItem contentMediaItem = createContentMediaItem(ADS_ID);
    AdPlaybackState initialState = new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L);

    standaloneTracker.startSession(
        contentMediaItem, adViewProvider, mockEventListener, initialState);

    AdPlaybackState expectedUpdatedState =
        initialState.withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
    InOrder inOrder = inOrder(mockEventListener);
    inOrder.verify(mockEventListener).onAdPlaybackState(initialState);
    inOrder.verify(mockEventListener).onAdPlaybackState(expectedUpdatedState);
    inOrder.verifyNoMoreInteractions();
    assertThat(standaloneTracker.getAdPlaybackState(ADS_ID)).isEqualTo(expectedUpdatedState);
  }

  @Test
  public void stopSession_forActiveSession_removesSessionAndNotifiesBridgeWithFinalState() {
    MediaItem contentMediaItem = createContentMediaItem(ADS_ID);
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
    adPlaybackTracker.startSession(
        contentMediaItem, adViewProvider, new AdsLoader.EventListener() {}, initialState);

    adPlaybackTracker.stopSession(ADS_ID);

    assertThat(adPlaybackTracker.isSessionActive(ADS_ID)).isFalse();
    assertThat(adPlaybackTracker.getActiveSessionAdsIds()).isEmpty();
    assertThat(adPlaybackTracker.getAdPlaybackState(ADS_ID)).isNull();
    verify(mockSdkBridge).onSessionStopped(ADS_ID, initialState);
  }

  @Test
  public void getActiveSessionAdsIds_acrossMultipleSessions_returnsAdsIdsInRegistrationOrder() {
    AdsLoader.EventListener eventListener = new AdsLoader.EventListener() {};

    adPlaybackTracker.startSession(
        createContentMediaItem(ADS_ID), adViewProvider, eventListener, new AdPlaybackState(ADS_ID));
    adPlaybackTracker.startSession(
        createContentMediaItem(SECOND_ADS_ID),
        adViewProvider,
        eventListener,
        new AdPlaybackState(SECOND_ADS_ID));

    assertThat(adPlaybackTracker.isSessionActive(ADS_ID)).isTrue();
    assertThat(adPlaybackTracker.isSessionActive(SECOND_ADS_ID)).isTrue();
    assertThat(adPlaybackTracker.isSessionActive("unknownAdsId")).isFalse();
    assertThat(adPlaybackTracker.getActiveSessionAdsIds())
        .containsExactly(ADS_ID, SECOND_ADS_ID)
        .inOrder();

    adPlaybackTracker.stopSession(ADS_ID);

    assertThat(adPlaybackTracker.isSessionActive(ADS_ID)).isFalse();
    assertThat(adPlaybackTracker.isSessionActive(SECOND_ADS_ID)).isTrue();
    assertThat(adPlaybackTracker.getActiveSessionAdsIds()).containsExactly(SECOND_ADS_ID);
  }

  @Test
  public void
      play_secondAdsMediaSourcePreparedWhileFirstPlayingTowardsEnd_interleavesSessionStartAndTransitionInOrder()
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

    player.setMediaSource(adsMediaSource1);
    player.prepare();
    player.play();
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 50_000L);

    assertThat(adPlaybackTracker.getActiveSessionAdsIds()).containsExactly(ADS_ID);
    verify(mockSdkBridge, never()).onContentCompleted(ADS_ID);

    player.addMediaSource(adsMediaSource2);
    advance(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 55_000L);

    assertThat(player.getCurrentMediaItemIndex()).isEqualTo(0);
    assertThat(adPlaybackTracker.getActiveSessionAdsIds())
        .containsExactly(ADS_ID, SECOND_ADS_ID)
        .inOrder();
    verify(mockSdkBridge)
        .onSessionStarted(SECOND_ADS_ID, adsMediaSource2.getMediaItem(), adViewProvider);
    verify(mockSdkBridge)
        .onAdPrepareCompleted(SECOND_ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, never()).onContentCompleted(ADS_ID);
    verify(mockSdkBridge, never()).onAdGroupStarted(eq(SECOND_ADS_ID), anyInt());

    advance(player).untilState(Player.STATE_ENDED);

    AdPlaybackState finalState1 = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    AdPlaybackState finalState2 = adPlaybackTracker.getAdPlaybackState(SECOND_ADS_ID);
    assertThat(finalState1).isNotNull();
    assertThat(finalState2).isNotNull();
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder
        .verify(mockSdkBridge)
        .onSessionStarted(ADS_ID, adsMediaSource1.getMediaItem(), adViewProvider);
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
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, finalState1);
    inOrder
        .verify(mockSdkBridge)
        .onSessionStarted(SECOND_ADS_ID, adsMediaSource2.getMediaItem(), adViewProvider);
    inOrder
        .verify(mockSdkBridge)
        .onAdPrepareCompleted(SECOND_ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder.verify(mockSdkBridge).onContentCompleted(ADS_ID);
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
  public void release_withActiveSession_stopsSessionAndDetachesPlayerListener() throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
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
    advance(player).untilState(Player.STATE_READY);

    adPlaybackTracker.release();
    adPlaybackTracker.release();
    player.play();
    advance(player).untilPlayWhenReadyIs(true);

    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onPlayerChanged(player);
    inOrder
        .verify(mockSdkBridge)
        .onSessionStarted(ADS_ID, adsMediaSource.getMediaItem(), adViewProvider);
    inOrder
        .verify(mockSdkBridge)
        .onAdPrepareCompleted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder.verify(mockSdkBridge).onAdGroupStarted(ADS_ID, /* adGroupIndex= */ 0);
    inOrder
        .verify(mockSdkBridge)
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    inOrder.verify(mockSdkBridge).onSessionStopped(eq(ADS_ID), any(AdPlaybackState.class));
    inOrder.verify(mockSdkBridge).onPlayerChanged(null);
    verifyNoMoreInteractions(mockSdkBridge);
  }

  @Test
  public void allPublicMethods_afterRelease_throwIllegalStateExceptionOrNoOp() {
    AdPlaybackTracker.SdkBridge standaloneBridge = mock(AdPlaybackTracker.SdkBridge.class);
    standaloneTracker = new AdPlaybackTracker(standaloneBridge);
    standaloneTracker.release();

    assertThrows(IllegalStateException.class, () -> standaloneTracker.setPlayer(player));
    assertThrows(IllegalStateException.class, () -> standaloneTracker.setPlayer(null));
    assertThrows(IllegalStateException.class, () -> standaloneTracker.getPlayer());
    assertThrows(
        IllegalStateException.class,
        () -> standaloneTracker.addListener((adsId, adPlaybackState) -> {}));
    assertThrows(
        IllegalStateException.class,
        () -> standaloneTracker.removeListener((adsId, adPlaybackState) -> {}));
    assertThrows(IllegalStateException.class, () -> standaloneTracker.isSessionActive(ADS_ID));
    assertThrows(IllegalStateException.class, () -> standaloneTracker.getActiveSessionAdsIds());
    assertThrows(IllegalStateException.class, () -> standaloneTracker.getAdPlaybackState(ADS_ID));
    assertThrows(
        IllegalStateException.class,
        () -> standaloneTracker.updateAdPlaybackState(ADS_ID, state -> state));
    assertThrows(
        IllegalStateException.class,
        () -> standaloneTracker.resetAllAds(ADS_ID, /* resetErrorAds= */ true));
    assertThrows(
        IllegalStateException.class,
        () -> standaloneTracker.skipAd(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));
    assertThrows(
        IllegalStateException.class,
        () -> standaloneTracker.skipAdGroup(ADS_ID, /* adGroupIndex= */ 0));

    // Late AdsMediaSource callbacks after release() are handled gracefully without throwing:
    // startSession publishes an empty AdPlaybackState so playback can proceed without ads.
    AdsLoader.EventListener mockEventListener = mock(AdsLoader.EventListener.class);
    AdPlaybackState stateWithAd =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
    standaloneTracker.startSession(
        createContentMediaItem(ADS_ID), adViewProvider, mockEventListener, stateWithAd);
    verify(mockEventListener).onAdPlaybackState(new AdPlaybackState(ADS_ID));
    assertThat(standaloneTracker.stopSession(ADS_ID)).isNull();
    standaloneTracker.handlePrepareComplete(
        ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    standaloneTracker.handlePrepareError(
        ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, new IOException("prepare error"));
    standaloneTracker.release();
    verifyNoMoreInteractions(standaloneBridge);
  }

  @Test
  public void adsMediaSourceCallbacks_whenPlayerIsNull_returnEarlyWithoutRegisteringSession() {
    AdPlaybackTracker.SdkBridge standaloneBridge = mock(AdPlaybackTracker.SdkBridge.class);
    standaloneTracker = new AdPlaybackTracker(standaloneBridge);
    AdsLoader.EventListener mockEventListener = mock(AdsLoader.EventListener.class);
    AdPlaybackState stateWithAd =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);

    standaloneTracker.startSession(
        createContentMediaItem(ADS_ID), adViewProvider, mockEventListener, stateWithAd);
    standaloneTracker.handlePrepareComplete(
        ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    standaloneTracker.handlePrepareError(
        ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, new IOException("prepare error"));
    AdPlaybackState stoppedState = standaloneTracker.stopSession(ADS_ID);

    assertThat(standaloneTracker.isSessionActive(ADS_ID)).isFalse();
    verify(mockEventListener).onAdPlaybackState(new AdPlaybackState(ADS_ID));
    assertThat(stoppedState).isNull();
    verifyNoMoreInteractions(standaloneBridge);
  }

  @Test
  public void
      setPlayerNull_withActiveSession_allowsPrepareCallbacksAndStopSessionBeforeAttachingNewPlayer() {
    AdsLoader.EventListener mockEventListener = mock(AdsLoader.EventListener.class);
    MediaItem contentMediaItem = createContentMediaItem(ADS_ID);
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
    adPlaybackTracker.startSession(
        contentMediaItem, adViewProvider, mockEventListener, initialState);

    adPlaybackTracker.setPlayer(null);
    IOException prepareError = new IOException("prepare error");
    adPlaybackTracker.handlePrepareError(
        ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, prepareError);
    AdPlaybackState stoppedState = adPlaybackTracker.stopSession(ADS_ID);

    AdPlaybackState expectedFinalState =
        initialState.withAdLoadError(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    assertThat(stoppedState).isEqualTo(expectedFinalState);
    assertThat(adPlaybackTracker.isSessionActive(ADS_ID)).isFalse();
    ExoPlayer secondPlayer = new TestExoPlayerBuilder(context).build();
    try {
      adPlaybackTracker.setPlayer(secondPlayer);
      assertThat(adPlaybackTracker.getPlayer()).isSameInstanceAs(secondPlayer);
    } finally {
      secondPlayer.release();
    }
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder.verify(mockSdkBridge).onPlayerChanged(player);
    inOrder.verify(mockSdkBridge).onSessionStarted(ADS_ID, contentMediaItem, adViewProvider);
    inOrder.verify(mockSdkBridge).onPlayerChanged(null);
    inOrder
        .verify(mockSdkBridge)
        .onAdError(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, prepareError);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, expectedFinalState);
    inOrder.verify(mockSdkBridge).onSessionStopped(ADS_ID, expectedFinalState);
    inOrder.verify(mockSdkBridge).onPlayerChanged(secondPlayer);
    verifyNoMoreInteractions(mockSdkBridge);
  }

  @Test
  public void handlePrepareComplete_withInvalidIndicesOrUnknownAdsId_doesNotNotifyBridge() {
    MediaItem contentMediaItem = createContentMediaItem(ADS_ID);
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0);
    adPlaybackTracker.startSession(
        contentMediaItem, adViewProvider, new AdsLoader.EventListener() {}, initialState);

    adPlaybackTracker.handlePrepareComplete(
        "unknownAdsId", /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    adPlaybackTracker.handlePrepareComplete(
        ADS_ID, /* adGroupIndex= */ -1, /* adIndexInAdGroup= */ 0);
    adPlaybackTracker.handlePrepareComplete(
        ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ -1);
    adPlaybackTracker.handlePrepareComplete(
        ADS_ID, /* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0);
    adPlaybackTracker.handlePrepareComplete(
        ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);

    verify(mockSdkBridge, never()).onAdPrepareCompleted(any(), anyInt(), anyInt());
  }

  @Test
  public void allPublicMethods_fromNonTrackerLooperThread_throwIllegalStateException() {
    HandlerThread trackerThread = new HandlerThread("AdPlaybackTrackerTestThread");
    trackerThread.start();
    try {
      AdPlaybackTracker customLooperTracker =
          new AdPlaybackTracker(mockSdkBridge, trackerThread.getLooper());
      List<Method> publicMethods = TestUtil.getPublicMethods(AdPlaybackTracker.class);
      assertThat(publicMethods).isNotEmpty();

      for (Method method : publicMethods) {
        InvocationTargetException exception =
            assertThrows(
                method.getName() + " did not throw when called from wrong thread",
                InvocationTargetException.class,
                () ->
                    method.invoke(
                        customLooperTracker, getDefaultArguments(method.getParameterTypes())));
        assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException.class);
        assertThat(exception)
            .hasCauseThat()
            .hasMessageThat()
            .contains("Method must be called on the tracker looper thread");
      }
    } finally {
      trackerThread.quit();
    }
  }

  // TODO: Deduplicate with Util.createParameters.
  private static Object[] getDefaultArguments(Class<?>[] parameterTypes) {
    Object[] args = new Object[parameterTypes.length];
    for (int i = 0; i < parameterTypes.length; i++) {
      args[i] = Defaults.defaultValue(parameterTypes[i]);
    }
    return args;
  }
}
