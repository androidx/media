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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.AdViewProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ForwardingMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.test.utils.ExoPlayerTestRunner;
import androidx.media3.test.utils.FakeAdsLoader;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeRenderer;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
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
 * Unit tests for {@link AdPlaybackTracker} error handling ({@link
 * AdPlaybackTracker#handlePrepareError} and {@code Player.Listener.onPlayerError}).
 */
@RunWith(AndroidJUnit4.class)
public final class AdPlaybackTrackerErrorHandlingTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private AdPlaybackTracker.SdkBridge mockSdkBridge;

  private AdViewProvider adViewProvider;
  private AdPlaybackTracker adPlaybackTracker;
  private FakeAdsLoader fakeAdsLoader;
  private AtomicBoolean shouldThrowRendererError;
  private ExoPlayer player;

  @Before
  public void setUp() {
    adViewProvider = () -> null;
    adPlaybackTracker = new AdPlaybackTracker(mockSdkBridge);
    fakeAdsLoader = new FakeAdsLoader(adPlaybackTracker);
    shouldThrowRendererError = new AtomicBoolean();
    FakeRenderer videoRenderer =
        new FakeRenderer(C.TRACK_TYPE_VIDEO) {
          @Override
          public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
            super.render(positionUs, elapsedRealtimeUs);
            if (shouldThrowRendererError.get()) {
              throw createRendererException(
                  new IllegalStateException("Ad decoder failure"),
                  ExoPlayerTestRunner.VIDEO_FORMAT,
                  PlaybackException.ERROR_CODE_DECODING_FAILED);
            }
          }
        };
    player =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext())
            .setRenderers(videoRenderer)
            .build();
    fakeAdsLoader.setPlayer(player);
  }

  @After
  public void tearDown() {
    player.release();
    fakeAdsLoader.release();
  }

  @Test
  public void handlePrepareError_withOutOfBoundsIndices_returnsGracefullyWithoutModifyingState() {
    AdsLoader.EventListener mockEventListener = mock(AdsLoader.EventListener.class);
    MediaItem contentMediaItem = createContentMediaItem(ADS_ID);
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
    adPlaybackTracker.startSession(
        contentMediaItem, adViewProvider, mockEventListener, initialState);
    IOException prepareException = new IOException("Ad load failed");

    adPlaybackTracker.handlePrepareError(
        ADS_ID, /* adGroupIndex= */ 999, /* adIndexInAdGroup= */ -1, prepareException);
    adPlaybackTracker.handlePrepareError(
        ADS_ID, /* adGroupIndex= */ -1, /* adIndexInAdGroup= */ 0, prepareException);
    adPlaybackTracker.handlePrepareError(
        ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ -1, prepareException);
    adPlaybackTracker.handlePrepareError(
        ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 999, prepareException);

    assertThat(adPlaybackTracker.getAdPlaybackState(ADS_ID)).isEqualTo(initialState);
    verify(mockEventListener).onAdPlaybackState(initialState);
    verify(mockSdkBridge, never()).onAdError(any(), anyInt(), anyInt(), any());
    verify(mockSdkBridge, never()).onAllAdsCompleted(any(), any());
  }

  @Test
  public void
      handlePrepareError_whenAdMediaSourceFailsToPrepare_marksAdErrorAndSkipsToNextAdAndContent()
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
    IOException prepareException = new IOException("Ad manifest 404");
    MediaSource.Factory failingFirstAdMediaSourceFactory =
        new ForwardingMediaSourceFactory(createDefaultAdMediaSourceFactory()) {
          @Override
          public MediaSource createMediaSource(MediaItem mediaItem) {
            if (mediaItem.equals(AD_MEDIA_ITEM_0)) {
              return new FakeMediaSource(/* timeline= */ null) {
                @Override
                public void maybeThrowSourceInfoRefreshError() throws IOException {
                  throw prepareException;
                }
              };
            }
            return super.createMediaSource(mediaItem);
          }
        };
    AdsMediaSource adsMediaSource =
        createAdsMediaSource(
            contentTimeline,
            failingFirstAdMediaSourceFactory,
            fakeAdsLoader,
            adViewProvider,
            ADS_ID);

    player.setMediaSource(adsMediaSource);
    player.prepare();
    player.play();
    // Ignore the non-fatal AdLoadException emitted by AdsMediaSource to AnalyticsListener.
    advance(player)
        .ignoringNonFatalErrors()
        .untilPlayingAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 1);
    advance(player)
        .ignoringNonFatalErrors()
        .untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 1_000L);
    assertThat(player.isPlayingAd()).isFalse();
    advance(player).ignoringNonFatalErrors().untilState(Player.STATE_ENDED);

    AdPlaybackState finalState = adPlaybackTracker.getAdPlaybackState(ADS_ID);
    assertThat(finalState).isNotNull();
    assertThat(finalState.getAdGroup(0).states)
        .asList()
        .containsExactly(AdPlaybackState.AD_STATE_ERROR, AdPlaybackState.AD_STATE_PLAYED)
        .inOrder();
    InOrder inOrder = inOrder(mockSdkBridge);
    inOrder
        .verify(mockSdkBridge)
        .onAdError(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, prepareException);
    verifyStandardAdGroupPlaybackEvents(
        inOrder,
        mockSdkBridge,
        ADS_ID,
        /* adGroupIndex= */ 0,
        /* startAdIndexInAdGroup= */ 1,
        /* endAdIndexInAdGroup= */ 1);
    inOrder.verify(mockSdkBridge).onAllAdsCompleted(ADS_ID, finalState);
    inOrder.verify(mockSdkBridge).onContentCompleted(ADS_ID);
    verify(mockSdkBridge, never())
        .onAdStarted(ADS_ID, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    verify(mockSdkBridge, never()).onAdSkipped(any(), anyInt(), anyInt());
  }

  @Test
  public void onPlayerError_afterSessionStopped_isNoOp() throws Exception {
    Timeline contentTimeline = createVodContentTimeline();
    AdPlaybackState initialState =
        new AdPlaybackState(ADS_ID, /* adGroupTimesUs...= */ 0L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, AD_DURATION_US)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM_0);
    fakeAdsLoader.setInitialAdPlaybackState(ADS_ID, initialState);
    MediaSource.Factory videoAdMediaSourceFactory =
        new ForwardingMediaSourceFactory(createDefaultAdMediaSourceFactory()) {
          @Override
          public MediaSource createMediaSource(MediaItem mediaItem) {
            return new FakeMediaSource(
                new FakeTimeline(
                    new TimelineWindowDefinition.Builder()
                        .setDurationUs(AD_DURATION_US)
                        .setMediaItem(mediaItem)
                        .build()),
                ExoPlayerTestRunner.VIDEO_FORMAT);
          }
        };
    AdsMediaSource adsMediaSource =
        createAdsMediaSource(
            contentTimeline, videoAdMediaSourceFactory, fakeAdsLoader, adViewProvider, ADS_ID);
    player.setMediaSource(adsMediaSource);
    player.prepare();
    player.play();
    advance(player).untilPlayingAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    advance(player).untilState(Player.STATE_READY);

    // Trigger a fatal renderer error on the playback thread; AdsMediaSource releases and stops the
    // session before onPlayerError is dispatched on the main thread.
    shouldThrowRendererError.set(true);
    advance(player).untilPlayerError();

    assertThat(adPlaybackTracker.isSessionActive(ADS_ID)).isFalse();
    verify(mockSdkBridge).onSessionStopped(ADS_ID, initialState);
    verify(mockSdkBridge, never()).onAdError(any(), anyInt(), anyInt(), any());
  }
}
