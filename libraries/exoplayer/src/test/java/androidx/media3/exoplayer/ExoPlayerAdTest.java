/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.exoplayer;

import static androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT;
import static androidx.media3.common.Player.DISCONTINUITY_REASON_SKIP;
import static androidx.media3.exoplayer.source.ads.ServerSideAdInsertionUtil.addAdGroupToAdPlaybackState;
import static androidx.media3.test.utils.FakeSampleStream.FakeSampleStreamItem.oneByteSample;
import static androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.play;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.Uri;
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Player.DiscontinuityReason;
import androidx.media3.common.Player.Listener;
import androidx.media3.common.Player.PositionInfo;
import androidx.media3.common.Timeline;
import androidx.media3.common.Timeline.Window;
import androidx.media3.common.util.SystemClock;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.ExoPlayer.PreloadConfiguration;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.source.ads.AdsMediaSource;
import androidx.media3.exoplayer.source.ads.ServerSideAdInsertionMediaSource;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.test.utils.ActionSchedule;
import androidx.media3.test.utils.ActionSchedule.PlayerRunnable;
import androidx.media3.test.utils.ExoPlayerTestRunner;
import androidx.media3.test.utils.FakeAdsLoader;
import androidx.media3.test.utils.FakeMediaPeriod;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeMediaSourceFactory;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.media3.test.utils.FakeVideoRenderer;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Range;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

/** Ad related unit test for {@link ExoPlayer}. */
@RunWith(ParameterizedRobolectricTestRunner.class)
public class ExoPlayerAdTest {

  private static final String TAG = "ExoPlayerTest";

  /**
   * For tests that rely on the player transitioning to the ended state, the duration in
   * milliseconds after starting the player before the test will time out. This is to catch cases
   * where the player under test is not making progress, in which case the test should fail.
   */
  private static final int TIMEOUT_MS = 10_000;

  @Parameters(name = "preload={0}")
  public static ImmutableList<Object[]> params() {
    return ImmutableList.of(
        new Object[] {false, new PreloadConfiguration(C.TIME_UNSET)},
        new Object[] {true, new PreloadConfiguration(5_000_000L)});
  }

  @Rule
  public ShadowMediaCodecConfig mediaCodecConfig =
      ShadowMediaCodecConfig.withAllDefaultSupportedCodecs();

  // The explicit boolean parameter is only used to give clear test names.
  @Parameter(0)
  public boolean unusedIsPreloadEnabled;

  @Parameter(1)
  public ExoPlayer.PreloadConfiguration preloadConfiguration;

  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    ExoPlayer.Builder.experimentalEnableStuckPlayingDetection = true;
  }

  private TestExoPlayerBuilder parameterizeTestExoPlayerBuilder(TestExoPlayerBuilder builder) {
    return builder.setPreloadConfiguration(preloadConfiguration);
  }

  private ExoPlayerTestRunner.Builder parameterizeExoPlayerTestRunnerBuilder(
      ExoPlayerTestRunner.Builder builder) {
    return builder.setPreloadConfiguration(preloadConfiguration);
  }

  @Test
  public void adGroupWithLoadError_noFurtherAdGroup_isSkipped() throws Exception {
    AdPlaybackState initialAdPlaybackState =
        FakeTimeline.createAdPlaybackState(
            /* adsPerAdGroup= */ 1, /* adGroupTimesUs...= */
            TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US
                + 5 * C.MICROS_PER_SECOND);
    Timeline fakeTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
                .build());
    AdPlaybackState errorAdPlaybackState =
        initialAdPlaybackState.withAdLoadError(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    final Timeline adErrorTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(errorAdPlaybackState))
                .build());
    final FakeMediaSource fakeMediaSource =
        new FakeMediaSource(fakeTimeline, ExoPlayerTestRunner.VIDEO_FORMAT);
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);

    player.setMediaSource(fakeMediaSource);
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    fakeMediaSource.setNewSourceInfo(adErrorTimeline);
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    // Content to content transition is ignored.
    verify(mockListener, never()).onPositionDiscontinuity(any(), any(), anyInt());
  }

  @Test
  public void adGroupWithLoadError_withFurtherAdGroup_isSkipped() throws Exception {
    AdPlaybackState initialAdPlaybackState =
        FakeTimeline.createAdPlaybackState(
            /* adsPerAdGroup= */ 1, /* adGroupTimesUs...= */
            TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US
                + 5 * C.MICROS_PER_SECOND,
            TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US
                + 8 * C.MICROS_PER_SECOND);
    Timeline fakeTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
                .build());
    AdPlaybackState errorAdPlaybackState =
        initialAdPlaybackState.withAdLoadError(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0);
    final Timeline adErrorTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(errorAdPlaybackState))
                .build());
    final FakeMediaSource fakeMediaSource =
        new FakeMediaSource(fakeTimeline, ExoPlayerTestRunner.VIDEO_FORMAT);
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);

    player.setMediaSource(fakeMediaSource);
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    fakeMediaSource.setNewSourceInfo(adErrorTimeline);
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    Timeline.Window window =
        player.getCurrentTimeline().getWindow(/* windowIndex= */ 0, new Timeline.Window());
    Timeline.Period period =
        player
            .getCurrentTimeline()
            .getPeriod(/* periodIndex= */ 0, new Timeline.Period(), /* setIds= */ true);
    player.release();

    // There content to content discontinuity after the failed ad is suppressed.
    PositionInfo positionInfoContentAtSuccessfulAd =
        new PositionInfo(
            window.uid,
            /* mediaItemIndex= */ 0,
            window.mediaItem,
            period.uid,
            /* periodIndex= */ 0,
            /* positionMs= */ 8_000,
            /* contentPositionMs= */ 8_000,
            /* adGroupIndex= */ C.INDEX_UNSET,
            /* adIndexInAdGroup= */ C.INDEX_UNSET);
    PositionInfo positionInfoSuccessfulAdStart =
        new PositionInfo(
            window.uid,
            /* mediaItemIndex= */ 0,
            window.mediaItem,
            period.uid,
            /* periodIndex= */ 0,
            /* positionMs= */ 0,
            /* contentPositionMs= */ 8_000,
            /* adGroupIndex= */ 1,
            /* adIndexInAdGroup= */ 0);
    PositionInfo positionInfoSuccessfulAdEnd =
        new PositionInfo(
            window.uid,
            /* mediaItemIndex= */ 0,
            window.mediaItem,
            period.uid,
            /* periodIndex= */ 0,
            /* positionMs= */ Util.usToMs(
                period.getAdDurationUs(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0)),
            /* contentPositionMs= */ 8_000,
            /* adGroupIndex= */ 1,
            /* adIndexInAdGroup= */ 0);
    verify(mockListener)
        .onPositionDiscontinuity(
            positionInfoContentAtSuccessfulAd,
            positionInfoSuccessfulAdStart,
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION);
    verify(mockListener)
        .onPositionDiscontinuity(
            positionInfoSuccessfulAdEnd,
            positionInfoContentAtSuccessfulAd,
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION);
  }

  @Test
  public void playAds_midRollsWithAndWithoutDuration_clippedOrNotClippedAccordingly()
      throws PlaybackException, TimeoutException {
    Timeline primaryContentTimeline =
        new FakeTimeline(new TimelineWindowDefinition.Builder().setDurationUs(60_000_000L).build());
    AdPlaybackState adPlaybackState =
        new AdPlaybackState("adsId", 133_000_000L, 143_000_000L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://example.com/ad_0_0"))
            .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 1, 5_000_000L) // clip to 5s
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 1,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://example.com/ad_1_0"));
    FakeAdsLoader fakeAdsLoader = new FakeAdsLoader();
    AdsMediaSource adsMediaSource =
        new AdsMediaSource(
            new FakeMediaSource(primaryContentTimeline, ExoPlayerTestRunner.VIDEO_FORMAT),
            new DataSpec(Uri.EMPTY),
            "adsId",
            /* adMediaSourceFactory= */ new FakeMediaSourceFactory(
                new TimelineWindowDefinition.Builder().setDurationUs(10_000_000L)),
            fakeAdsLoader,
            /* adViewProvider= */ () -> null,
            /* useLazyContentSourcePreparation= */ true,
            /* useAdMediaSourceClipping= */ true);
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);
    player.setMediaSource(adsMediaSource);
    player.prepare();
    advance(player)
        .untilBackgroundThreadCondition(
            (Supplier<Boolean>) () -> fakeAdsLoader.eventListeners.get("adsId") != null);
    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(adPlaybackState);

    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    ArgumentCaptor<PositionInfo> oldPositionsCaptor = ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<PositionInfo> newPositionsCaptor = ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<Integer> reasonsCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(mockListener, times(4))
        .onPositionDiscontinuity(
            oldPositionsCaptor.capture(), newPositionsCaptor.capture(), reasonsCaptor.capture());
    assertThat(reasonsCaptor.getAllValues())
        .containsExactly(
            DISCONTINUITY_REASON_AUTO_TRANSITION, // content to midroll
            DISCONTINUITY_REASON_AUTO_TRANSITION, // midroll to content
            DISCONTINUITY_REASON_AUTO_TRANSITION, // content to midroll
            DISCONTINUITY_REASON_AUTO_TRANSITION); // midroll to content
    List<PositionInfo> oldPositions = oldPositionsCaptor.getAllValues();
    List<PositionInfo> newPositions = newPositionsCaptor.getAllValues();
    // midroll (with duration unset) to content
    assertThat(oldPositions.get(1).adGroupIndex).isEqualTo(0);
    assertThat(oldPositions.get(1).positionMs).isEqualTo(133_000L); // full ad duration
    assertThat(oldPositions.get(1).contentPositionMs).isEqualTo(10_000L);
    assertThat(newPositions.get(1).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(newPositions.get(1).positionMs).isEqualTo(10_000L);
    assertThat(newPositions.get(1).contentPositionMs).isEqualTo(10_000L);
    // midroll (with duration set) to content
    assertThat(oldPositions.get(3).adGroupIndex).isEqualTo(1);
    assertThat(oldPositions.get(3).positionMs).isEqualTo(5_000L); // clipped ad duration
    assertThat(oldPositions.get(3).contentPositionMs).isEqualTo(20_000L);
    assertThat(newPositions.get(3).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(newPositions.get(3).positionMs).isEqualTo(20_000L);
    assertThat(newPositions.get(3).contentPositionMs).isEqualTo(20_000L);
  }

  @Test
  public void playAds_withContentResumptionOffset_correctPositionAtDiscontinuity()
      throws PlaybackException, TimeoutException {
    Timeline primaryContentTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setWindowPositionInFirstPeriodUs(0L)
                .setDurationUs(60_000_000L)
                .build());
    AdPlaybackState adPlaybackState =
        new AdPlaybackState("adsId", 0L, 10_000_000L, C.TIME_END_OF_SOURCE)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, 10_000_000L)
            .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 2_000_000L)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://example.com/ad_0_0"))
            .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 1, 10_000_000L)
            .withContentResumeOffsetUs(/* adGroupIndex= */ 1, 3_000_000L)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 1,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://example.com/ad_1_0"))
            .withAdCount(/* adGroupIndex= */ 2, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 2, 10_000_000L)
            .withContentResumeOffsetUs(/* adGroupIndex= */ 2, 4_000_000L)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 2,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://example.com/ad_2_0"));
    FakeAdsLoader fakeAdsLoader = new FakeAdsLoader();
    AdsMediaSource adsMediaSource =
        new AdsMediaSource(
            new FakeMediaSource(primaryContentTimeline, ExoPlayerTestRunner.VIDEO_FORMAT),
            new DataSpec(Uri.EMPTY),
            "adsId",
            /* adMediaSourceFactory= */ new FakeMediaSourceFactory(
                new TimelineWindowDefinition.Builder()),
            fakeAdsLoader,
            /* adViewProvider= */ () -> null,
            /* useLazyContentSourcePreparation= */ true,
            /* useAdMediaSourceClipping= */ true);
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);
    player.setMediaSource(adsMediaSource);
    player.prepare();
    advance(player)
        .untilBackgroundThreadCondition(
            (Supplier<Boolean>) () -> fakeAdsLoader.eventListeners.get("adsId") != null);
    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(adPlaybackState);

    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    ArgumentCaptor<PositionInfo> oldPositionsCaptor = ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<PositionInfo> newPositionsCaptor = ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<Integer> reasonsCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(mockListener, times(5))
        .onPositionDiscontinuity(
            oldPositionsCaptor.capture(), newPositionsCaptor.capture(), reasonsCaptor.capture());
    assertThat(reasonsCaptor.getAllValues())
        .containsExactly(
            DISCONTINUITY_REASON_AUTO_TRANSITION, // preroll to content
            DISCONTINUITY_REASON_AUTO_TRANSITION, // content to midroll
            DISCONTINUITY_REASON_AUTO_TRANSITION, // midroll to content
            DISCONTINUITY_REASON_AUTO_TRANSITION, // content to postroll
            DISCONTINUITY_REASON_AUTO_TRANSITION); // postroll to end of content
    List<PositionInfo> oldPositions = oldPositionsCaptor.getAllValues();
    List<PositionInfo> newPositions = newPositionsCaptor.getAllValues();
    assertThat(oldPositions.get(0).adGroupIndex).isEqualTo(0); // from preroll
    assertThat(oldPositions.get(0).positionMs).isEqualTo(10_000L); // end of ad duration
    assertThat(oldPositions.get(0).contentPositionMs).isEqualTo(0L);
    assertThat(newPositions.get(0).adGroupIndex).isEqualTo(C.INDEX_UNSET); // to content with
    assertThat(newPositions.get(0).positionMs).isEqualTo(2_000L); // 2s offset preroll
    assertThat(newPositions.get(0).contentPositionMs).isEqualTo(2_000L);
    // midroll to content
    assertThat(oldPositions.get(2).adGroupIndex).isEqualTo(1); // from midroll
    assertThat(oldPositions.get(2).positionMs).isEqualTo(10_000L); // end of ad duration
    assertThat(oldPositions.get(2).contentPositionMs).isEqualTo(10_000L);
    assertThat(newPositions.get(2).adGroupIndex).isEqualTo(C.INDEX_UNSET); // content with
    assertThat(newPositions.get(2).positionMs).isEqualTo(13_000L); // 3s offset midroll
    assertThat(newPositions.get(2).contentPositionMs).isEqualTo(13_000L);
    // postroll to content
    assertThat(oldPositions.get(4).adGroupIndex).isEqualTo(2); // from postroll
    assertThat(oldPositions.get(4).positionMs).isEqualTo(10_000L); // end of ad duration
    assertThat(oldPositions.get(4).contentPositionMs).isEqualTo(60_000L);
    assertThat(newPositions.get(4).adGroupIndex).isEqualTo(C.INDEX_UNSET); // ends on content
    assertThat(newPositions.get(4).positionMs).isEqualTo(59_999L); // after post-roll
    assertThat(newPositions.get(4).contentPositionMs).isEqualTo(59_999L);
  }

  @Test
  public void skipAd_withContentResumption_correctPositionAtDiscontinuity()
      throws PlaybackException, TimeoutException {
    Timeline primaryContentTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setWindowPositionInFirstPeriodUs(0L)
                .setDurationUs(60_000_000L)
                .build());
    AdPlaybackState adPlaybackState =
        new AdPlaybackState("adsId", 0L, 10_000_000L, C.TIME_END_OF_SOURCE)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, 10_000_000L)
            .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 2_000_000L)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://example.com/ad_0_0"))
            .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 1, 10_000_000L)
            .withContentResumeOffsetUs(/* adGroupIndex= */ 1, 3_000_000L)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 1,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://example.com/ad_1_0"))
            .withAdCount(/* adGroupIndex= */ 2, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 2, 10_000_000L)
            .withContentResumeOffsetUs(/* adGroupIndex= */ 2, 4_000_000L)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 2,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://example.com/ad_2_0"));
    FakeAdsLoader fakeAdsLoader = new FakeAdsLoader();
    AdsMediaSource adsMediaSource =
        new AdsMediaSource(
            new FakeMediaSource(primaryContentTimeline, ExoPlayerTestRunner.VIDEO_FORMAT),
            new DataSpec(Uri.EMPTY),
            "adsId",
            new FakeMediaSourceFactory(new TimelineWindowDefinition.Builder()),
            fakeAdsLoader,
            /* adViewProvider= */ () -> null);
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);
    player.setMediaSource(adsMediaSource);
    player.prepare();
    advance(player)
        .untilBackgroundThreadCondition(
            (Supplier<Boolean>) () -> fakeAdsLoader.eventListeners.get("adsId") != null);
    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(adPlaybackState);

    player.play();
    advance(player).untilState(Player.STATE_READY);
    fakeAdsLoader
        .eventListeners
        .get("adsId")
        .onAdPlaybackState(
            adPlaybackState.withSkippedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));
    advance(player).untilPositionDiscontinuityWithReason(DISCONTINUITY_REASON_AUTO_TRANSITION);
    advance(player).untilPositionAtLeast(4_000L);
    fakeAdsLoader
        .eventListeners
        .get("adsId")
        .onAdPlaybackState(
            adPlaybackState.withSkippedAd(/* adGroupIndex= */ 1, /* adIndexInAdGroup= */ 0));
    advance(player).untilPositionDiscontinuityWithReason(DISCONTINUITY_REASON_AUTO_TRANSITION);
    advance(player).untilPositionAtLeast(4_000L);
    fakeAdsLoader
        .eventListeners
        .get("adsId")
        .onAdPlaybackState(
            adPlaybackState.withSkippedAd(/* adGroupIndex= */ 2, /* adIndexInAdGroup= */ 0));
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    ArgumentCaptor<PositionInfo> oldPositionsCaptor = ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<PositionInfo> newPositionsCaptor = ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<Integer> reasonsCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(mockListener, times(5))
        .onPositionDiscontinuity(
            oldPositionsCaptor.capture(), newPositionsCaptor.capture(), reasonsCaptor.capture());
    assertThat(reasonsCaptor.getAllValues())
        .containsExactly(
            DISCONTINUITY_REASON_SKIP,
            DISCONTINUITY_REASON_AUTO_TRANSITION,
            DISCONTINUITY_REASON_SKIP,
            DISCONTINUITY_REASON_AUTO_TRANSITION,
            DISCONTINUITY_REASON_SKIP);
    List<PositionInfo> oldPositions = oldPositionsCaptor.getAllValues();
    List<PositionInfo> newPositions = newPositionsCaptor.getAllValues();
    assertThat(oldPositions.get(0).adGroupIndex).isEqualTo(0); // from preroll
    assertThat(oldPositions.get(0).positionMs).isAtMost(1_000L); // skipped before 1s
    assertThat(oldPositions.get(0).contentPositionMs).isEqualTo(0L);
    assertThat(newPositions.get(0).adGroupIndex).isEqualTo(C.INDEX_UNSET); // content with
    assertThat(newPositions.get(0).positionMs).isEqualTo(2_000L); // 2s offset preroll
    assertThat(newPositions.get(0).contentPositionMs).isEqualTo(2_000L);
    // midroll skipped to content
    assertThat(oldPositions.get(2).adGroupIndex).isEqualTo(1); // from midroll
    assertThat(oldPositions.get(2).positionMs).isAtMost(1_000L); // skipped before 1s
    assertThat(oldPositions.get(2).contentPositionMs).isEqualTo(10_000L);
    assertThat(newPositions.get(2).adGroupIndex).isEqualTo(C.INDEX_UNSET); // content with
    assertThat(newPositions.get(2).positionMs).isEqualTo(13_000L); // 3s offset midroll
    assertThat(newPositions.get(2).contentPositionMs).isEqualTo(13_000L);
    // postroll skipped to content
    assertThat(oldPositions.get(4).adGroupIndex).isEqualTo(2); // from postroll
    assertThat(oldPositions.get(4).positionMs).isAtMost(1_000L); // skipped before 1s
    assertThat(oldPositions.get(4).contentPositionMs).isEqualTo(60_000L);
    assertThat(newPositions.get(4).adGroupIndex).isEqualTo(C.INDEX_UNSET); // ends on content
    assertThat(newPositions.get(4).positionMs).isEqualTo(59_999L); // after post-roll
    assertThat(newPositions.get(4).contentPositionMs).isEqualTo(59_999L);
  }

  @Test
  public void skipAd_afterSeek_correctPositionAtDiscontinuity()
      throws PlaybackException, TimeoutException {
    Timeline primaryContentTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setWindowPositionInFirstPeriodUs(0L)
                .setDurationUs(60_000_000L)
                .build());
    AdPlaybackState adPlaybackState =
        new AdPlaybackState("adsId", 10_000_000L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, 10_000_000L)
            .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 11_000_000L)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://example.com/ad_0_0"));
    FakeAdsLoader fakeAdsLoader = new FakeAdsLoader();
    AdsMediaSource adsMediaSource =
        new AdsMediaSource(
            new FakeMediaSource(primaryContentTimeline, ExoPlayerTestRunner.VIDEO_FORMAT),
            new DataSpec(Uri.EMPTY),
            "adsId",
            new FakeMediaSourceFactory(new TimelineWindowDefinition.Builder()),
            fakeAdsLoader,
            /* adViewProvider= */ () -> null);
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);
    player.setMediaSource(adsMediaSource);
    player.prepare();
    advance(player)
        .untilBackgroundThreadCondition(
            (Supplier<Boolean>) () -> fakeAdsLoader.eventListeners.get("adsId") != null);
    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(adPlaybackState);

    player.play();
    advance(player).untilPositionAtLeast(1_000L);
    player.seekTo(44_000L);
    advance(player).untilPositionDiscontinuityWithReason(DISCONTINUITY_REASON_SEEK_ADJUSTMENT);
    fakeAdsLoader
        .eventListeners
        .get("adsId")
        .onAdPlaybackState(
            adPlaybackState.withSkippedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    ArgumentCaptor<PositionInfo> oldPositionsCaptor = ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<PositionInfo> newPositionsCaptor = ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<Integer> reasonsCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(mockListener, times(3))
        .onPositionDiscontinuity(
            oldPositionsCaptor.capture(), newPositionsCaptor.capture(), reasonsCaptor.capture());
    assertThat(reasonsCaptor.getAllValues())
        .containsExactly(
            DISCONTINUITY_REASON_SEEK,
            DISCONTINUITY_REASON_SEEK_ADJUSTMENT,
            DISCONTINUITY_REASON_SKIP)
        .inOrder();
    List<PositionInfo> oldPositions = oldPositionsCaptor.getAllValues();
    List<PositionInfo> newPositions = newPositionsCaptor.getAllValues();
    // initial seek
    assertThat(oldPositions.get(0).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(oldPositions.get(0).positionMs).isEqualTo(1_000L);
    assertThat(oldPositions.get(0).contentPositionMs).isEqualTo(1_000L);
    assertThat(newPositions.get(0).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(newPositions.get(0).positionMs).isEqualTo(44_000L);
    assertThat(newPositions.get(0).contentPositionMs).isEqualTo(44_000L);
    // seek adjustment to midroll
    assertThat(oldPositions.get(1).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(oldPositions.get(1).positionMs).isEqualTo(44_000L);
    assertThat(oldPositions.get(1).contentPositionMs).isEqualTo(44_000L);
    assertThat(newPositions.get(1).adGroupIndex).isEqualTo(0);
    assertThat(newPositions.get(1).positionMs).isEqualTo(0L);
    assertThat(newPositions.get(1).contentPositionMs).isEqualTo(44_000L);
    // skip ad to initial seek target position
    assertThat(oldPositions.get(2).adGroupIndex).isEqualTo(0);
    assertThat(oldPositions.get(2).positionMs).isAtMost(133_000L);
    assertThat(oldPositions.get(2).contentPositionMs).isEqualTo(44_000L);
    assertThat(newPositions.get(2).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(newPositions.get(2).positionMs).isEqualTo(44_000L);
    assertThat(newPositions.get(2).contentPositionMs).isEqualTo(44_000L);
  }

  @Test
  public void skipAd_afterSeekIntoResumptionOffset_correctPositionAtDiscontinuity()
      throws PlaybackException, TimeoutException {
    Timeline primaryContentTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setWindowPositionInFirstPeriodUs(0L)
                .setDurationUs(60_000_000L)
                .build());
    AdPlaybackState adPlaybackState =
        new AdPlaybackState("adsId", 10_000_000L)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, 10_000_000L)
            .withContentResumeOffsetUs(/* adGroupIndex= */ 0, 11_000_000L)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://example.com/ad_0_0"));
    FakeAdsLoader fakeAdsLoader = new FakeAdsLoader();
    AdsMediaSource adsMediaSource =
        new AdsMediaSource(
            new FakeMediaSource(primaryContentTimeline, ExoPlayerTestRunner.VIDEO_FORMAT),
            new DataSpec(Uri.EMPTY),
            "adsId",
            new FakeMediaSourceFactory(new TimelineWindowDefinition.Builder()),
            fakeAdsLoader,
            /* adViewProvider= */ () -> null);
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);
    player.setMediaSource(adsMediaSource);
    player.prepare();
    advance(player)
        .untilBackgroundThreadCondition(
            (Supplier<Boolean>) () -> fakeAdsLoader.eventListeners.get("adsId") != null);
    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(adPlaybackState);

    player.play();
    advance(player).untilPositionAtLeast(1_000L);
    player.seekTo(12_000L);
    advance(player).untilPositionDiscontinuityWithReason(DISCONTINUITY_REASON_SEEK_ADJUSTMENT);
    fakeAdsLoader
        .eventListeners
        .get("adsId")
        .onAdPlaybackState(
            adPlaybackState.withSkippedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    ArgumentCaptor<PositionInfo> oldPositionsCaptor = ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<PositionInfo> newPositionsCaptor = ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<Integer> reasonsCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(mockListener, times(3))
        .onPositionDiscontinuity(
            oldPositionsCaptor.capture(), newPositionsCaptor.capture(), reasonsCaptor.capture());
    assertThat(reasonsCaptor.getAllValues())
        .containsExactly(
            DISCONTINUITY_REASON_SEEK,
            DISCONTINUITY_REASON_SEEK_ADJUSTMENT,
            DISCONTINUITY_REASON_SKIP)
        .inOrder();
    List<PositionInfo> oldPositions = oldPositionsCaptor.getAllValues();
    List<PositionInfo> newPositions = newPositionsCaptor.getAllValues();
    // Initial seek to a position within the resumption offset of the ad.
    assertThat(oldPositions.get(0).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(oldPositions.get(0).positionMs).isEqualTo(1_000L);
    assertThat(oldPositions.get(0).contentPositionMs).isEqualTo(1_000L);
    assertThat(newPositions.get(0).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(newPositions.get(0).positionMs).isEqualTo(12_000L);
    assertThat(newPositions.get(0).contentPositionMs).isEqualTo(12_000L);
    // Seek adjustment sets the contentPositionMs to the resumption offset.
    assertThat(oldPositions.get(1).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(oldPositions.get(1).positionMs).isEqualTo(12_000L);
    assertThat(oldPositions.get(1).contentPositionMs).isEqualTo(12_000L);
    assertThat(newPositions.get(1).adGroupIndex).isEqualTo(0);
    assertThat(newPositions.get(1).positionMs).isEqualTo(0L);
    assertThat(newPositions.get(1).contentPositionMs).isEqualTo(21_000L);
    // Skip ad skips to the adjusted seek position.
    assertThat(oldPositions.get(2).adGroupIndex).isEqualTo(0);
    assertThat(oldPositions.get(2).positionMs).isAtMost(1_000L);
    assertThat(oldPositions.get(2).contentPositionMs).isEqualTo(21_000L);
    assertThat(newPositions.get(2).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(newPositions.get(2).positionMs).isEqualTo(21_000L);
    assertThat(newPositions.get(2).contentPositionMs).isEqualTo(21_000L);
  }

  @Test
  public void contentWithInitialSeekPositionAfterPrerollAdStartsAtSeekPosition() throws Exception {
    AdPlaybackState adPlaybackState =
        FakeTimeline.createAdPlaybackState(/* adsPerAdGroup= */ 3, /* adGroupTimesUs...= */ 0);
    Timeline fakeTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(adPlaybackState))
                .build());
    FakeMediaSource fakeMediaSource = new FakeMediaSource(/* timeline= */ null);
    AtomicReference<Player> playerReference = new AtomicReference<>();
    AtomicLong contentStartPositionMs = new AtomicLong(C.TIME_UNSET);
    Player.Listener playerListener =
        new Player.Listener() {
          @Override
          public void onPositionDiscontinuity(@DiscontinuityReason int reason) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
              contentStartPositionMs.set(playerReference.get().getContentPosition());
            }
          }
        };
    ActionSchedule actionSchedule =
        new ActionSchedule.Builder(TAG)
            .executeRunnable(
                new PlayerRunnable() {
                  @Override
                  public void run(ExoPlayer player) {
                    playerReference.set(player);
                    player.addListener(playerListener);
                  }
                })
            .seek(/* positionMs= */ 5_000)
            .waitForPlaybackState(Player.STATE_BUFFERING)
            .executeRunnable(() -> fakeMediaSource.setNewSourceInfo(fakeTimeline))
            .build();
    parameterizeExoPlayerTestRunnerBuilder(
            new ExoPlayerTestRunner.Builder(context)
                .setMediaSources(fakeMediaSource)
                .setActionSchedule(actionSchedule))
        .build()
        .start()
        .blockUntilEnded(TIMEOUT_MS);

    assertThat(contentStartPositionMs.get()).isAtLeast(5_000L);
  }

  @Test
  public void contentWithoutInitialSeekStartsAtDefaultPositionAfterPrerollAd() throws Exception {
    AdPlaybackState adPlaybackState =
        FakeTimeline.createAdPlaybackState(/* adsPerAdGroup= */ 3, /* adGroupTimesUs...= */ 0);
    Timeline fakeTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setDefaultPositionUs(5_000_000)
                .setAdPlaybackStates(ImmutableList.of(adPlaybackState))
                .build());
    FakeMediaSource fakeMediaSource = new FakeMediaSource(/* timeline= */ null);
    AtomicReference<Player> playerReference = new AtomicReference<>();
    AtomicLong contentStartPositionMs = new AtomicLong(C.TIME_UNSET);
    Player.Listener playerListener =
        new Player.Listener() {
          @Override
          public void onPositionDiscontinuity(@DiscontinuityReason int reason) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
              contentStartPositionMs.set(playerReference.get().getContentPosition());
            }
          }
        };
    ActionSchedule actionSchedule =
        new ActionSchedule.Builder(TAG)
            .executeRunnable(
                new PlayerRunnable() {
                  @Override
                  public void run(ExoPlayer player) {
                    playerReference.set(player);
                    player.addListener(playerListener);
                  }
                })
            .waitForPlaybackState(Player.STATE_BUFFERING)
            .executeRunnable(() -> fakeMediaSource.setNewSourceInfo(fakeTimeline))
            .build();
    parameterizeExoPlayerTestRunnerBuilder(
            new ExoPlayerTestRunner.Builder(context)
                .setMediaSources(fakeMediaSource)
                .setActionSchedule(actionSchedule))
        .build()
        .start()
        .blockUntilEnded(TIMEOUT_MS);

    assertThat(contentStartPositionMs.get()).isAtLeast(5_000L);
  }

  @Test
  public void adInMovingLiveWindow_keepsContentPosition() throws Exception {
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    AdPlaybackState adPlaybackState =
        FakeTimeline.createAdPlaybackState(
            /* adsPerAdGroup= */ 1, /* adGroupTimesUs...= */ 42_000_004_000_000L);
    TimelineWindowDefinition liveWindowDefinition1 =
        new TimelineWindowDefinition.Builder()
            .setDynamic(true)
            .setLive(true)
            .setDefaultPositionUs(3_000_000)
            .setWindowPositionInFirstPeriodUs(42_000_000_000_000L)
            .setAdPlaybackStates(ImmutableList.of(adPlaybackState))
            .build();
    Timeline liveTimeline1 = new FakeTimeline(liveWindowDefinition1);
    Timeline liveTimeline2 =
        new FakeTimeline(
            liveWindowDefinition1
                .buildUpon()
                .setWindowPositionInFirstPeriodUs(42_000_002_000_000L)
                .build());
    FakeMediaSource fakeMediaSource = new FakeMediaSource(liveTimeline1);

    player.setMediaSource(fakeMediaSource);
    player.prepare();
    player.play();
    // Wait until the ad is playing.
    advance(player)
        .untilPositionDiscontinuityWithReason(Player.DISCONTINUITY_REASON_AUTO_TRANSITION);
    long contentPositionBeforeLiveWindowUpdateMs = player.getContentPosition();
    fakeMediaSource.setNewSourceInfo(liveTimeline2);
    advance(player).untilTimelineChanges();
    long contentPositionAfterLiveWindowUpdateMs = player.getContentPosition();
    player.release();

    assertThat(contentPositionBeforeLiveWindowUpdateMs).isEqualTo(4000);
    assertThat(contentPositionAfterLiveWindowUpdateMs).isEqualTo(2000);
  }

  @Test
  public void addMediaSource_whilePlayingAd_correctMasking() throws Exception {
    long contentDurationMs = 10_000;
    long adDurationMs = 5_000;
    AdPlaybackState adPlaybackState =
        new AdPlaybackState(/* adsId= */ new Object(), /* adGroupTimesUs...= */ 0);
    adPlaybackState = adPlaybackState.withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
    adPlaybackState =
        adPlaybackState.withAvailableAdMediaItem(
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0,
            MediaItem.fromUri("https://google.com/ad"));
    long[][] durationsUs = new long[1][];
    durationsUs[0] = new long[] {Util.msToUs(adDurationMs)};
    adPlaybackState = adPlaybackState.withAdDurationsUs(durationsUs);
    Timeline adTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setDurationUs(Util.msToUs(contentDurationMs))
                .setAdPlaybackStates(ImmutableList.of(adPlaybackState))
                .build());
    FakeMediaSource adsMediaSource = new FakeMediaSource(adTimeline);
    int[] mediaItemIndex = new int[] {C.INDEX_UNSET, C.INDEX_UNSET, C.INDEX_UNSET};
    long[] positionMs = new long[] {C.TIME_UNSET, C.TIME_UNSET, C.INDEX_UNSET};
    long[] bufferedPositionMs = new long[] {C.TIME_UNSET, C.TIME_UNSET, C.INDEX_UNSET};
    long[] totalBufferedDurationMs = new long[] {C.TIME_UNSET, C.TIME_UNSET, C.INDEX_UNSET};
    boolean[] isPlayingAd = new boolean[3];
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    player.setMediaSources(ImmutableList.of(adsMediaSource, new FakeMediaSource()));
    player.prepare();
    player.addMediaSource(/* index= */ 1, new FakeMediaSource());
    advance(player).untilState(Player.STATE_READY);

    player.addMediaSource(/* index= */ 1, new FakeMediaSource());
    mediaItemIndex[0] = player.getCurrentMediaItemIndex();
    isPlayingAd[0] = player.isPlayingAd();
    positionMs[0] = player.getCurrentPosition();
    bufferedPositionMs[0] = player.getBufferedPosition();
    totalBufferedDurationMs[0] = player.getTotalBufferedDuration();
    advance(player).untilTimelineChanges();
    mediaItemIndex[1] = player.getCurrentMediaItemIndex();
    isPlayingAd[1] = player.isPlayingAd();
    positionMs[1] = player.getCurrentPosition();
    bufferedPositionMs[1] = player.getBufferedPosition();
    totalBufferedDurationMs[1] = player.getTotalBufferedDuration();
    play(player).untilPositionAtLeast(8000);
    player.addMediaSource(new FakeMediaSource());
    mediaItemIndex[2] = player.getCurrentMediaItemIndex();
    isPlayingAd[2] = player.isPlayingAd();
    positionMs[2] = player.getCurrentPosition();
    bufferedPositionMs[2] = player.getBufferedPosition();
    totalBufferedDurationMs[2] = player.getTotalBufferedDuration();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    assertThat(mediaItemIndex[0]).isEqualTo(0);
    assertThat(isPlayingAd[0]).isTrue();
    assertThat(positionMs[0]).isAtMost(adDurationMs);
    assertThat(bufferedPositionMs[0]).isEqualTo(adDurationMs);
    assertThat(totalBufferedDurationMs[0]).isAtLeast(adDurationMs - positionMs[0]);
    assertThat(mediaItemIndex[1]).isEqualTo(0);
    assertThat(isPlayingAd[1]).isTrue();
    assertThat(positionMs[1]).isAtMost(adDurationMs);
    assertThat(bufferedPositionMs[1]).isEqualTo(adDurationMs);
    assertThat(totalBufferedDurationMs[1]).isAtLeast(adDurationMs - positionMs[1]);
    assertThat(mediaItemIndex[2]).isEqualTo(0);
    assertThat(isPlayingAd[2]).isFalse();
    assertThat(positionMs[2]).isEqualTo(8000);
    assertThat(bufferedPositionMs[2]).isEqualTo(contentDurationMs);
    assertThat(totalBufferedDurationMs[2]).isAtLeast(contentDurationMs - positionMs[2]);
  }

  @Test
  public void removeMediaSources_whilePlayingPostrollAd_correctMasking() throws Exception {
    // Covers bug reported in: https://github.com/androidx/media/issues/2746
    long contentDurationMs = 10_000;
    long adDurationMs = 5_000;
    AdPlaybackState adPlaybackState =
        new AdPlaybackState(
            /* adsId= */ new Object(), /* adGroupTimesUs...= */ C.TIME_END_OF_SOURCE);
    adPlaybackState = adPlaybackState.withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
    adPlaybackState =
        adPlaybackState.withAvailableAdMediaItem(
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0,
            MediaItem.fromUri("https://google.com/ad"));
    long[][] durationsUs = new long[1][];
    durationsUs[0] = new long[] {Util.msToUs(adDurationMs)};
    adPlaybackState = adPlaybackState.withAdDurationsUs(durationsUs);
    Timeline adTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setDurationUs(Util.msToUs(contentDurationMs))
                .setAdPlaybackStates(ImmutableList.of(adPlaybackState))
                .build());
    FakeMediaSource adsMediaSource = new FakeMediaSource(adTimeline);
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    player.setMediaSources(
        ImmutableList.of(adsMediaSource, new FakeMediaSource(), new FakeMediaSource()));
    player.prepare();
    player.play();
    advance(player).untilPlayingAdIs(true);

    player.removeMediaItems(/* fromIndex= */ 1, /* toIndex= */ player.getMediaItemCount());

    assertThat(player.getMediaItemCount()).isEqualTo(1);
    player.release();
  }

  @Test
  public void seekTo_whilePlayingAd_correctMasking() throws Exception {
    long contentDurationMs = 10_000;
    long adDurationMs = 4_000;
    AdPlaybackState adPlaybackState =
        new AdPlaybackState(/* adsId= */ new Object(), /* adGroupTimesUs...= */ 0);
    adPlaybackState = adPlaybackState.withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
    adPlaybackState =
        adPlaybackState.withAvailableAdMediaItem(
            /* adGroupIndex= */ 0,
            /* adIndexInAdGroup= */ 0,
            MediaItem.fromUri("https://google.com/ad"));
    long[][] durationsUs = new long[1][];
    durationsUs[0] = new long[] {Util.msToUs(adDurationMs)};
    adPlaybackState = adPlaybackState.withAdDurationsUs(durationsUs);
    Timeline adTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setDurationUs(Util.msToUs(contentDurationMs))
                .setAdPlaybackStates(ImmutableList.of(adPlaybackState))
                .build());
    FakeMediaSource adsMediaSource = new FakeMediaSource(adTimeline);
    int[] mediaItemIndex = new int[] {C.INDEX_UNSET, C.INDEX_UNSET};
    long[] positionMs = new long[] {C.TIME_UNSET, C.TIME_UNSET};
    long[] bufferedPositionMs = new long[] {C.TIME_UNSET, C.TIME_UNSET};
    long[] totalBufferedDurationMs = new long[] {C.TIME_UNSET, C.TIME_UNSET};
    boolean[] isPlayingAd = new boolean[2];
    ActionSchedule actionSchedule =
        new ActionSchedule.Builder(TAG)
            .pause()
            .waitForPlaybackState(Player.STATE_READY)
            .waitForIsLoading(true)
            .waitForIsLoading(false)
            .executeRunnable(
                new PlayerRunnable() {
                  @Override
                  public void run(ExoPlayer player) {
                    player.seekTo(/* mediaItemIndex= */ 0, /* positionMs= */ 8000);
                    mediaItemIndex[0] = player.getCurrentMediaItemIndex();
                    isPlayingAd[0] = player.isPlayingAd();
                    positionMs[0] = player.getCurrentPosition();
                    bufferedPositionMs[0] = player.getBufferedPosition();
                    totalBufferedDurationMs[0] = player.getTotalBufferedDuration();
                  }
                })
            .waitForPendingPlayerCommands()
            .executeRunnable(
                new PlayerRunnable() {
                  @Override
                  public void run(ExoPlayer player) {
                    mediaItemIndex[1] = player.getCurrentMediaItemIndex();
                    isPlayingAd[1] = player.isPlayingAd();
                    positionMs[1] = player.getCurrentPosition();
                    bufferedPositionMs[1] = player.getBufferedPosition();
                    totalBufferedDurationMs[1] = player.getTotalBufferedDuration();
                  }
                })
            .stop()
            .build();

    parameterizeExoPlayerTestRunnerBuilder(
            new ExoPlayerTestRunner.Builder(context)
                .setMediaSources(adsMediaSource)
                .setActionSchedule(actionSchedule))
        .build()
        .start()
        .blockUntilActionScheduleFinished(TIMEOUT_MS)
        .blockUntilEnded(TIMEOUT_MS);

    assertThat(mediaItemIndex[0]).isEqualTo(0);
    assertThat(isPlayingAd[0]).isTrue();
    assertThat(positionMs[0]).isEqualTo(0);
    assertThat(bufferedPositionMs[0]).isEqualTo(adDurationMs);
    assertThat(totalBufferedDurationMs[0]).isEqualTo(adDurationMs);

    assertThat(mediaItemIndex[1]).isEqualTo(0);
    assertThat(isPlayingAd[1]).isTrue();
    assertThat(positionMs[1]).isEqualTo(0);
    assertThat(bufferedPositionMs[1]).isEqualTo(adDurationMs);
    assertThat(totalBufferedDurationMs[1]).isEqualTo(adDurationMs);
  }

  // https://github.com/google/ExoPlayer/issues/8349
  @Test
  public void seekTo_whilePlayingAd_doesntBlockFutureUpdates() throws Exception {
    long contentDurationMs = 10_000;
    long adDurationMs = 4_000;
    AdPlaybackState adPlaybackState =
        new AdPlaybackState(/* adsId= */ new Object(), /* adGroupTimesUs...= */ 0)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("https://google.com/ad"));
    long[][] durationsUs = new long[1][];
    durationsUs[0] = new long[] {Util.msToUs(adDurationMs)};
    adPlaybackState = adPlaybackState.withAdDurationsUs(durationsUs);
    Timeline adTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setDurationUs(Util.msToUs(contentDurationMs))
                .setAdPlaybackStates(ImmutableList.of(adPlaybackState))
                .build());
    FakeMediaSource adsMediaSource = new FakeMediaSource(adTimeline);

    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    player.setMediaSource(adsMediaSource);
    player.pause();
    player.prepare();
    advance(player).untilState(Player.STATE_READY);

    player.seekTo(0, 8000);
    player.play();

    // This times out if playback info updates after the seek are blocked.
    advance(player).untilState(Player.STATE_ENDED);

    player.release();
  }

  @Test
  public void seekTo_beyondSSAIMidRolls_seekAdjustedAndRequestedContentPositionKept()
      throws Exception {
    ArgumentCaptor<PositionInfo> oldPositionArgumentCaptor =
        ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<PositionInfo> newPositionArgumentCaptor =
        ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<Integer> reasonArgumentCaptor = ArgumentCaptor.forClass(Integer.class);
    // Create a multi-period timeline without ads.
    FakeTimeline fakeContentTimeline =
        new FakeTimeline(
            new FakeTimeline.TimelineWindowDefinition.Builder()
                .setPeriodCount(4)
                .setUid("windowId")
                .setMediaItem(MediaItem.EMPTY)
                .build());
    // Create the ad playback state matching to the periods in the content timeline.
    ImmutableMap<Object, AdPlaybackState> adPlaybackStates =
        FakeTimeline.createMultiPeriodAdTimeline(
                "windowId",
                /* numberOfPlayedAds= */ 0,
                /* isAdPeriodFlags...= */ false,
                true,
                true,
                false)
            .getAdPlaybackStates(/* windowIndex= */ 0);
    Listener listener = mock(Listener.class);
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    player.addListener(listener);
    AtomicReference<ServerSideAdInsertionMediaSource> sourceReference = new AtomicReference<>();
    sourceReference.set(
        new ServerSideAdInsertionMediaSource(
            new FakeMediaSource(fakeContentTimeline),
            contentTimeline -> {
              sourceReference.get().setAdPlaybackStates(adPlaybackStates, contentTimeline);
              return true;
            }));
    player.setMediaSource(sourceReference.get());
    player.pause();
    player.prepare();
    advance(player).untilState(Player.STATE_READY);

    player.seekTo(/* positionMs= */ 4000);
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    verify(listener, times(6))
        .onPositionDiscontinuity(
            oldPositionArgumentCaptor.capture(),
            newPositionArgumentCaptor.capture(),
            reasonArgumentCaptor.capture());
    assertThat(reasonArgumentCaptor.getAllValues()).containsExactly(1, 2, 0, 0, 0, 0).inOrder();
    List<PositionInfo> oldPositions = oldPositionArgumentCaptor.getAllValues();
    List<PositionInfo> newPositions = newPositionArgumentCaptor.getAllValues();
    // seek discontinuities
    assertThat(oldPositions.get(0).periodIndex).isEqualTo(0);
    assertThat(oldPositions.get(0).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(0).periodIndex).isEqualTo(3);
    assertThat(newPositions.get(0).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(0).positionMs).isEqualTo(4000);
    // seek adjustment
    assertThat(oldPositions.get(1).periodIndex).isEqualTo(3);
    assertThat(oldPositions.get(1).adGroupIndex).isEqualTo(-1);
    assertThat(oldPositions.get(1).positionMs).isEqualTo(4000);
    assertThat(newPositions.get(1).periodIndex).isEqualTo(1);
    assertThat(newPositions.get(1).adGroupIndex).isEqualTo(0);
    assertThat(newPositions.get(1).adIndexInAdGroup).isEqualTo(0);
    assertThat(newPositions.get(1).positionMs).isEqualTo(0);
    assertThat(newPositions.get(1).contentPositionMs).isEqualTo(4000);
    // auto transition from ad to end of period
    assertThat(oldPositions.get(2).periodIndex).isEqualTo(1);
    assertThat(oldPositions.get(2).adGroupIndex).isEqualTo(0);
    assertThat(oldPositions.get(2).adIndexInAdGroup).isEqualTo(0);
    assertThat(oldPositions.get(2).positionMs).isEqualTo(2500);
    assertThat(oldPositions.get(2).contentPositionMs).isEqualTo(4000);
    assertThat(newPositions.get(2).periodIndex).isEqualTo(1);
    assertThat(newPositions.get(2).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(2).positionMs).isEqualTo(2500);
    // auto transition to next ad period
    assertThat(oldPositions.get(3).periodIndex).isEqualTo(1);
    assertThat(oldPositions.get(3).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(3).periodIndex).isEqualTo(2);
    assertThat(newPositions.get(3).adGroupIndex).isEqualTo(0);
    assertThat(newPositions.get(3).adIndexInAdGroup).isEqualTo(0);
    assertThat(newPositions.get(3).contentPositionMs).isEqualTo(4000);
    // auto transition from ad to end of period
    assertThat(oldPositions.get(4).periodIndex).isEqualTo(2);
    assertThat(oldPositions.get(4).adGroupIndex).isEqualTo(0);
    assertThat(oldPositions.get(4).adIndexInAdGroup).isEqualTo(0);
    assertThat(newPositions.get(4).periodIndex).isEqualTo(2);
    assertThat(newPositions.get(4).adGroupIndex).isEqualTo(-1);
    // auto transition to final content period with seek position
    assertThat(oldPositions.get(5).periodIndex).isEqualTo(2);
    assertThat(oldPositions.get(5).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(5).periodIndex).isEqualTo(3);
    assertThat(newPositions.get(5).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(5).contentPositionMs).isEqualTo(4000);
  }

  @Test
  public void seekTo_beyondSSAIMidRollsConsecutiveContentPeriods_seekAdjusted() throws Exception {
    ArgumentCaptor<PositionInfo> oldPositionArgumentCaptor =
        ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<PositionInfo> newPositionArgumentCaptor =
        ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<Integer> reasonArgumentCaptor = ArgumentCaptor.forClass(Integer.class);
    // Create a multi-period timeline without ads.
    FakeTimeline fakeContentTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setPeriodCount(4)
                .setUid("windowId")
                .setMediaItem(MediaItem.EMPTY)
                .build());
    // Create the ad playback state matching to the periods in the content timeline.
    ImmutableMap<Object, AdPlaybackState> adPlaybackStates =
        FakeTimeline.createMultiPeriodAdTimeline(
                "windowId",
                /* numberOfPlayedAds= */ 0,
                /* isAdPeriodFlags...= */ false,
                true,
                false,
                false)
            .getAdPlaybackStates(/* windowIndex= */ 0);
    Listener listener = mock(Listener.class);
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    player.addListener(listener);
    AtomicReference<ServerSideAdInsertionMediaSource> sourceReference = new AtomicReference<>();
    sourceReference.set(
        new ServerSideAdInsertionMediaSource(
            new FakeMediaSource(fakeContentTimeline),
            contentTimeline -> {
              sourceReference.get().setAdPlaybackStates(adPlaybackStates, contentTimeline);
              return true;
            }));
    player.setMediaSource(sourceReference.get());
    player.pause();
    player.prepare();
    advance(player).untilState(Player.STATE_READY);

    player.seekTo(/* positionMs= */ 7000);
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    verify(listener, times(5))
        .onPositionDiscontinuity(
            oldPositionArgumentCaptor.capture(),
            newPositionArgumentCaptor.capture(),
            reasonArgumentCaptor.capture());
    assertThat(reasonArgumentCaptor.getAllValues()).containsExactly(1, 2, 0, 0, 0).inOrder();
    List<PositionInfo> oldPositions = oldPositionArgumentCaptor.getAllValues();
    List<PositionInfo> newPositions = newPositionArgumentCaptor.getAllValues();
    // seek
    assertThat(oldPositions.get(0).periodIndex).isEqualTo(0);
    assertThat(oldPositions.get(0).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(0).periodIndex).isEqualTo(3);
    assertThat(newPositions.get(0).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(0).positionMs).isEqualTo(7000);
    // seek adjustment
    assertThat(oldPositions.get(1).periodIndex).isEqualTo(3);
    assertThat(oldPositions.get(1).adGroupIndex).isEqualTo(-1);
    assertThat(oldPositions.get(1).positionMs).isEqualTo(7000);
    assertThat(newPositions.get(1).periodIndex).isEqualTo(1);
    assertThat(newPositions.get(1).adGroupIndex).isEqualTo(0);
    assertThat(newPositions.get(1).positionMs).isEqualTo(0);
  }

  @Test
  public void seekTo_beforeSSAIMidRolls_requestedContentPositionNotPropagatedIntoAds()
      throws Exception {
    ArgumentCaptor<PositionInfo> oldPositionArgumentCaptor =
        ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<PositionInfo> newPositionArgumentCaptor =
        ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<Integer> reasonArgumentCaptor = ArgumentCaptor.forClass(Integer.class);
    // Create a multi-period timeline without ads.
    FakeTimeline fakeContentTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setPeriodCount(4)
                .setUid("windowId")
                .setMediaItem(MediaItem.EMPTY)
                .build());
    // Create the ad playback state matching to the periods in the content timeline.
    ImmutableMap<Object, AdPlaybackState> adPlaybackStates =
        FakeTimeline.createMultiPeriodAdTimeline(
                "windowId",
                /* numberOfPlayedAds= */ 0,
                /* isAdPeriodFlags...= */ false,
                true,
                true,
                false)
            .getAdPlaybackStates(/* windowIndex= */ 0);
    Listener listener = mock(Listener.class);
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    player.addListener(listener);
    AtomicReference<ServerSideAdInsertionMediaSource> sourceReference = new AtomicReference<>();
    sourceReference.set(
        new ServerSideAdInsertionMediaSource(
            new FakeMediaSource(fakeContentTimeline),
            contentTimeline -> {
              sourceReference.get().setAdPlaybackStates(adPlaybackStates, contentTimeline);
              return true;
            }));
    player.setMediaSource(sourceReference.get());
    player.pause();
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    player.play();

    player.seekTo(1600);
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    verify(listener, times(6))
        .onPositionDiscontinuity(
            oldPositionArgumentCaptor.capture(),
            newPositionArgumentCaptor.capture(),
            reasonArgumentCaptor.capture());
    assertThat(reasonArgumentCaptor.getAllValues()).containsExactly(1, 0, 0, 0, 0, 0).inOrder();
    List<PositionInfo> oldPositions = oldPositionArgumentCaptor.getAllValues();
    List<PositionInfo> newPositions = newPositionArgumentCaptor.getAllValues();
    // seek discontinuity
    assertThat(oldPositions.get(0).periodIndex).isEqualTo(0);
    assertThat(newPositions.get(0).periodIndex).isEqualTo(0);
    assertThat(newPositions.get(0).positionMs).isEqualTo(1600);
    assertThat(newPositions.get(0).contentPositionMs).isEqualTo(1600);
    // auto discontinuities through ads has correct content position that is not the seek position.
    assertThat(newPositions.get(1).periodIndex).isEqualTo(1);
    assertThat(newPositions.get(1).adGroupIndex).isEqualTo(0);
    assertThat(newPositions.get(1).adIndexInAdGroup).isEqualTo(0);
    assertThat(newPositions.get(1).positionMs).isEqualTo(0);
    assertThat(newPositions.get(1).contentPositionMs).isEqualTo(2500);
    assertThat(newPositions.get(2).contentPositionMs).isEqualTo(2500);
    assertThat(newPositions.get(3).contentPositionMs).isEqualTo(2500);
    assertThat(newPositions.get(4).contentPositionMs).isEqualTo(2500);
    // Content resumes at expected position that is not the seek position.
    assertThat(newPositions.get(5).periodIndex).isEqualTo(3);
    assertThat(newPositions.get(5).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(5).positionMs).isEqualTo(2500);
    assertThat(newPositions.get(5).contentPositionMs).isEqualTo(2500);
  }

  @Test
  public void seekTo_toSAIMidRolls_playsMidRolls() throws Exception {
    ArgumentCaptor<PositionInfo> oldPositionArgumentCaptor =
        ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<PositionInfo> newPositionArgumentCaptor =
        ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<Integer> reasonArgumentCaptor = ArgumentCaptor.forClass(Integer.class);
    // Create a multi-period timeline without ads.
    FakeTimeline fakeContentTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setPeriodCount(4)
                .setUid("windowId")
                .setMediaItem(MediaItem.EMPTY)
                .build());
    // Create the ad playback state matching to the periods in the content timeline.
    ImmutableMap<Object, AdPlaybackState> adPlaybackStates =
        FakeTimeline.createMultiPeriodAdTimeline(
                "windowId",
                /* numberOfPlayedAds= */ 0,
                /* isAdPeriodFlags...= */ false,
                true,
                true,
                false)
            .getAdPlaybackStates(/* windowIndex= */ 0);
    Listener listener = mock(Listener.class);
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    player.addListener(listener);
    AtomicReference<ServerSideAdInsertionMediaSource> sourceReference = new AtomicReference<>();
    sourceReference.set(
        new ServerSideAdInsertionMediaSource(
            new FakeMediaSource(fakeContentTimeline),
            contentTimeline -> {
              sourceReference.get().setAdPlaybackStates(adPlaybackStates, contentTimeline);
              return true;
            }));
    player.setMediaSource(sourceReference.get());
    player.pause();
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    player.seekTo(2500);
    player.play();

    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    verify(listener, times(6))
        .onPositionDiscontinuity(
            oldPositionArgumentCaptor.capture(),
            newPositionArgumentCaptor.capture(),
            reasonArgumentCaptor.capture());
    assertThat(reasonArgumentCaptor.getAllValues()).containsExactly(1, 2, 0, 0, 0, 0).inOrder();
    List<PositionInfo> oldPositions = oldPositionArgumentCaptor.getAllValues();
    List<PositionInfo> newPositions = newPositionArgumentCaptor.getAllValues();
    // seek discontinuity
    assertThat(oldPositions.get(0).periodIndex).isEqualTo(0);
    assertThat(oldPositions.get(0).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(0).periodIndex).isEqualTo(1);
    assertThat(newPositions.get(0).adGroupIndex).isEqualTo(-1);
    // seek adjustment discontinuity
    assertThat(oldPositions.get(1).periodIndex).isEqualTo(1);
    assertThat(oldPositions.get(1).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(1).periodIndex).isEqualTo(1);
    assertThat(newPositions.get(1).adGroupIndex).isEqualTo(0);
    // auto transition to last frame of first ad period
    assertThat(oldPositions.get(2).periodIndex).isEqualTo(1);
    assertThat(oldPositions.get(2).adGroupIndex).isEqualTo(0);
    assertThat(newPositions.get(2).periodIndex).isEqualTo(1);
    assertThat(newPositions.get(2).adGroupIndex).isEqualTo(-1);
    // auto transition to second ad period
    assertThat(oldPositions.get(3).periodIndex).isEqualTo(1);
    assertThat(oldPositions.get(3).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(3).periodIndex).isEqualTo(2);
    assertThat(newPositions.get(3).adGroupIndex).isEqualTo(0);
    // auto transition to last frame of second ad period
    assertThat(oldPositions.get(4).periodIndex).isEqualTo(2);
    assertThat(oldPositions.get(4).adGroupIndex).isEqualTo(0);
    assertThat(newPositions.get(4).periodIndex).isEqualTo(2);
    assertThat(newPositions.get(4).adGroupIndex).isEqualTo(-1);
    // auto transition to the final content period
    assertThat(oldPositions.get(5).periodIndex).isEqualTo(2);
    assertThat(oldPositions.get(5).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(5).periodIndex).isEqualTo(3);
    assertThat(newPositions.get(5).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(5).positionMs).isEqualTo(2500);
    assertThat(newPositions.get(5).contentPositionMs).isEqualTo(2500);
  }

  @Test
  public void seekTo_toPlayedSAIMidRolls_requestedContentPositionNotPropagatedIntoAds()
      throws Exception {
    ArgumentCaptor<PositionInfo> oldPositionArgumentCaptor =
        ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<PositionInfo> newPositionArgumentCaptor =
        ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<Integer> reasonArgumentCaptor = ArgumentCaptor.forClass(Integer.class);
    // Create a multi-period timeline without ads.
    FakeTimeline fakeContentTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setPeriodCount(4)
                .setUid("windowId")
                .setMediaItem(MediaItem.EMPTY)
                .build());
    // Create the ad playback state matching to the periods in the content timeline.
    ImmutableMap<Object, AdPlaybackState> adPlaybackStates =
        FakeTimeline.createMultiPeriodAdTimeline(
                "windowId",
                /* numberOfPlayedAds= */ 2,
                /* isAdPeriodFlags...= */ false,
                true,
                true,
                false)
            .getAdPlaybackStates(/* windowIndex= */ 0);
    Listener listener = mock(Listener.class);
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    player.addListener(listener);
    AtomicReference<ServerSideAdInsertionMediaSource> sourceReference = new AtomicReference<>();
    sourceReference.set(
        new ServerSideAdInsertionMediaSource(
            new FakeMediaSource(fakeContentTimeline),
            contentTimeline -> {
              sourceReference.get().setAdPlaybackStates(adPlaybackStates, contentTimeline);
              return true;
            }));
    player.setMediaSource(sourceReference.get());
    player.pause();
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    player.seekTo(2500);
    player.play();

    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    verify(listener, times(1))
        .onPositionDiscontinuity(
            oldPositionArgumentCaptor.capture(),
            newPositionArgumentCaptor.capture(),
            reasonArgumentCaptor.capture());
    assertThat(reasonArgumentCaptor.getAllValues()).containsExactly(1).inOrder();
    List<PositionInfo> oldPositions = oldPositionArgumentCaptor.getAllValues();
    List<PositionInfo> newPositions = newPositionArgumentCaptor.getAllValues();
    // seek discontinuity
    assertThat(oldPositions.get(0).periodIndex).isEqualTo(0);
    assertThat(oldPositions.get(0).adGroupIndex).isEqualTo(-1);
    // TODO(bachinger): Incorrect masking. Skipped played prerolls not taken into account by masking
    assertThat(newPositions.get(0).periodIndex).isEqualTo(1);
    assertThat(newPositions.get(0).adGroupIndex).isEqualTo(-1);
  }

  @Test
  public void play_playedSSAIPreMidPostRollsMultiPeriodWindow_contentPeriodTransitionsOnly()
      throws Exception {
    ArgumentCaptor<PositionInfo> oldPositionArgumentCaptor =
        ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<PositionInfo> newPositionArgumentCaptor =
        ArgumentCaptor.forClass(PositionInfo.class);
    ArgumentCaptor<Integer> reasonArgumentCaptor = ArgumentCaptor.forClass(Integer.class);
    // Create a multi-period timeline without ads.
    FakeTimeline fakeContentTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setPeriodCount(8)
                .setUid("windowId")
                .setMediaItem(MediaItem.EMPTY)
                .build());
    // Create the ad playback state matching to the periods in the content timeline.
    ImmutableMap<Object, AdPlaybackState> adPlaybackStates =
        FakeTimeline.createMultiPeriodAdTimeline(
                "windowId",
                /* numberOfPlayedAds= */ Integer.MAX_VALUE,
                /* isAdPeriodFlags...= */ true,
                false,
                true,
                true,
                false,
                true,
                true,
                true)
            .getAdPlaybackStates(/* windowIndex= */ 0);
    Listener listener = mock(Listener.class);
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    player.addListener(listener);
    AtomicReference<ServerSideAdInsertionMediaSource> sourceReference = new AtomicReference<>();
    sourceReference.set(
        new ServerSideAdInsertionMediaSource(
            new FakeMediaSource(fakeContentTimeline, ExoPlayerTestRunner.AUDIO_FORMAT),
            contentTimeline -> {
              sourceReference.get().setAdPlaybackStates(adPlaybackStates, contentTimeline);
              return true;
            }));
    player.setMediaSource(sourceReference.get());
    player.prepare();

    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    ArgumentCaptor<Integer> playbackStateCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(listener, times(3)).onPlaybackStateChanged(playbackStateCaptor.capture());
    assertThat(playbackStateCaptor.getAllValues()).containsExactly(2, 3, 4).inOrder();
    verify(listener, times(3))
        .onPositionDiscontinuity(
            oldPositionArgumentCaptor.capture(),
            newPositionArgumentCaptor.capture(),
            reasonArgumentCaptor.capture());
    assertThat(reasonArgumentCaptor.getAllValues()).containsExactly(0, 0, 0).inOrder();
    List<PositionInfo> oldPositions = oldPositionArgumentCaptor.getAllValues();
    List<PositionInfo> newPositions = newPositionArgumentCaptor.getAllValues();
    // Auto discontinuity from the empty pre-roll period to the first content period.
    assertThat(oldPositions.get(0).periodIndex).isEqualTo(0);
    assertThat(oldPositions.get(0).adGroupIndex).isEqualTo(-1);
    assertThat(oldPositions.get(0).positionMs).isEqualTo(0);
    assertThat(newPositions.get(0).periodIndex).isEqualTo(1);
    assertThat(newPositions.get(0).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(0).positionMs).isEqualTo(0);
    // Auto discontinuity from the first content to the second content period.
    assertThat(oldPositions.get(1).periodIndex).isEqualTo(1);
    assertThat(oldPositions.get(1).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(1).periodIndex).isEqualTo(4);
    assertThat(newPositions.get(1).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(1).positionMs).isEqualTo(1250);
    // Auto discontinuity from the second content period to the last frame of the last ad period.
    assertThat(oldPositions.get(2).periodIndex).isEqualTo(4);
    assertThat(oldPositions.get(2).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(2).periodIndex).isEqualTo(7);
    assertThat(newPositions.get(2).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(2).positionMs).isEqualTo(2500);
  }

  @Test
  public void play_playedSSAIPreMidPostRollsSinglePeriodWindow_noDiscontinuities()
      throws Exception {
    AdPlaybackState adPlaybackState =
        addAdGroupToAdPlaybackState(
            new AdPlaybackState("adsId"),
            /* fromPositionUs= */ DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US,
            /* contentResumeOffsetUs= */ 0,
            /* adDurationsUs...= */ C.MICROS_PER_SECOND);
    adPlaybackState =
        addAdGroupToAdPlaybackState(
            adPlaybackState,
            /* fromPositionUs= */ DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US
                + (3 * C.MICROS_PER_SECOND),
            /* contentResumeOffsetUs= */ 0,
            /* adDurationsUs...= */ C.MICROS_PER_SECOND);
    adPlaybackState =
        addAdGroupToAdPlaybackState(
            adPlaybackState,
            /* fromPositionUs= */ DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US
                + (5 * C.MICROS_PER_SECOND),
            /* contentResumeOffsetUs= */ 0,
            /* adDurationsUs...= */ C.MICROS_PER_SECOND);
    adPlaybackState =
        addAdGroupToAdPlaybackState(
            adPlaybackState,
            /* fromPositionUs= */ DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US
                + (9 * C.MICROS_PER_SECOND),
            /* contentResumeOffsetUs= */ 0,
            /* adDurationsUs...= */ C.MICROS_PER_SECOND);
    adPlaybackState =
        adPlaybackState.withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup+ */ 0);
    adPlaybackState =
        adPlaybackState.withPlayedAd(/* adGroupIndex= */ 1, /* adIndexInAdGroup+ */ 0);
    adPlaybackState =
        adPlaybackState.withPlayedAd(/* adGroupIndex= */ 2, /* adIndexInAdGroup+ */ 0);
    adPlaybackState =
        adPlaybackState.withPlayedAd(/* adGroupIndex= */ 3, /* adIndexInAdGroup+ */ 0);
    // Create a multi-period timeline without ads.
    FakeTimeline fakeContentTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setUid("windowId")
                .setMediaItem(MediaItem.EMPTY)
                .build());
    ImmutableMap<Object, AdPlaybackState> adPlaybackStates =
        ImmutableMap.of(/* period.uid */ new Pair<>("windowId", 0), adPlaybackState);
    Listener listener = mock(Listener.class);
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    player.addListener(listener);
    AtomicReference<ServerSideAdInsertionMediaSource> sourceReference = new AtomicReference<>();
    sourceReference.set(
        new ServerSideAdInsertionMediaSource(
            new FakeMediaSource(fakeContentTimeline, ExoPlayerTestRunner.AUDIO_FORMAT),
            contentTimeline -> {
              sourceReference.get().setAdPlaybackStates(adPlaybackStates, contentTimeline);
              return true;
            }));
    player.setMediaSource(sourceReference.get());
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    long finalPositionMs = player.getCurrentPosition();
    player.release();

    assertThat(finalPositionMs).isEqualTo(6000);
    verify(listener, never()).onPositionDiscontinuity(any(), any(), anyInt());
    ArgumentCaptor<Integer> playbackStateCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(listener, times(3)).onPlaybackStateChanged(playbackStateCaptor.capture());
    assertThat(playbackStateCaptor.getAllValues()).containsExactly(2, 3, 4).inOrder();
  }

  @Test
  public void shortAdFollowedByUnpreparedAd_playbackDoesNotGetStuck() throws Exception {
    AdPlaybackState adPlaybackState =
        FakeTimeline.createAdPlaybackState(/* adsPerAdGroup= */ 2, /* adGroupTimesUs...= */ 0);
    long shortAdDurationMs = 1_000;
    adPlaybackState =
        adPlaybackState.withAdDurationsUs(new long[][] {{shortAdDurationMs, shortAdDurationMs}});
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(ImmutableList.of(adPlaybackState))
                .build());
    // Simulate the second ad not being prepared.
    FakeMediaSource mediaSource =
        new FakeMediaSource(timeline, ExoPlayerTestRunner.VIDEO_FORMAT) {
          @Override
          protected MediaPeriod createMediaPeriod(
              MediaPeriodId id,
              TrackGroupArray trackGroupArray,
              Allocator allocator,
              MediaSourceEventListener.EventDispatcher mediaSourceEventDispatcher,
              DrmSessionManager drmSessionManager,
              DrmSessionEventListener.EventDispatcher drmEventDispatcher,
              @Nullable TransferListener transferListener) {
            return new FakeMediaPeriod(
                trackGroupArray,
                allocator,
                FakeMediaPeriod.TrackDataFactory.singleSampleWithTimeUs(0),
                mediaSourceEventDispatcher,
                drmSessionManager,
                drmEventDispatcher,
                /* deferOnPrepared= */ id.adIndexInAdGroup == 1);
          }
        };
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    player.setMediaSource(mediaSource);
    player.prepare();
    player.play();

    // The player is not stuck in the buffering state.
    advance(player).untilState(Player.STATE_READY);

    player.release();
  }

  @Test
  public void play_withPreMidAndPostRollAd_callsOnDiscontinuityCorrectly() throws Exception {
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    Player.Listener listener = mock(Player.Listener.class);
    player.addListener(listener);
    AdPlaybackState adPlaybackState =
        FakeTimeline.createAdPlaybackState(
            /* adsPerAdGroup= */ 2,
            /* adGroupTimesUs...= */ 0,
            7 * C.MICROS_PER_SECOND,
            C.TIME_END_OF_SOURCE);
    TimelineWindowDefinition adTimeline =
        new TimelineWindowDefinition.Builder()
            .setWindowPositionInFirstPeriodUs(0)
            .setAdPlaybackStates(ImmutableList.of(adPlaybackState))
            .build();
    player.setMediaSource(new FakeMediaSource(new FakeTimeline(adTimeline)));

    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);

    ArgumentCaptor<Player.PositionInfo> oldPosition =
        ArgumentCaptor.forClass(Player.PositionInfo.class);
    ArgumentCaptor<Player.PositionInfo> newPosition =
        ArgumentCaptor.forClass(Player.PositionInfo.class);
    verify(listener, never())
        .onPositionDiscontinuity(
            any(), any(), not(eq(Player.DISCONTINUITY_REASON_AUTO_TRANSITION)));
    verify(listener, times(8))
        .onPositionDiscontinuity(
            oldPosition.capture(),
            newPosition.capture(),
            eq(Player.DISCONTINUITY_REASON_AUTO_TRANSITION));

    // first ad group (pre-roll)
    // starts with ad to ad transition
    List<Player.PositionInfo> oldPositions = oldPosition.getAllValues();
    List<Player.PositionInfo> newPositions = newPosition.getAllValues();
    assertThat(oldPositions.get(0).mediaItemIndex).isEqualTo(0);
    assertThat(oldPositions.get(0).positionMs).isEqualTo(5000);
    assertThat(oldPositions.get(0).contentPositionMs).isEqualTo(0);
    assertThat(oldPositions.get(0).adGroupIndex).isEqualTo(0);
    assertThat(oldPositions.get(0).adIndexInAdGroup).isEqualTo(0);
    assertThat(newPositions.get(0).mediaItemIndex).isEqualTo(0);
    assertThat(newPositions.get(0).positionMs).isEqualTo(0);
    assertThat(newPositions.get(0).contentPositionMs).isEqualTo(0);
    assertThat(newPositions.get(0).adGroupIndex).isEqualTo(0);
    assertThat(newPositions.get(0).adIndexInAdGroup).isEqualTo(1);
    // ad to content transition
    assertThat(oldPositions.get(1).mediaItemIndex).isEqualTo(0);
    assertThat(oldPositions.get(1).positionMs).isEqualTo(5000);
    assertThat(oldPositions.get(1).contentPositionMs).isEqualTo(0);
    assertThat(oldPositions.get(1).adGroupIndex).isEqualTo(0);
    assertThat(oldPositions.get(1).adIndexInAdGroup).isEqualTo(1);
    assertThat(newPositions.get(1).mediaItemIndex).isEqualTo(0);
    assertThat(newPositions.get(1).positionMs).isEqualTo(0);
    assertThat(newPositions.get(1).contentPositionMs).isEqualTo(0);
    assertThat(newPositions.get(1).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(1).adIndexInAdGroup).isEqualTo(-1);

    // second add group (mid-roll)
    assertThat(oldPositions.get(2).mediaItemIndex).isEqualTo(0);
    assertThat(oldPositions.get(2).positionMs).isEqualTo(7000);
    assertThat(oldPositions.get(2).contentPositionMs).isEqualTo(7000);
    assertThat(oldPositions.get(2).adGroupIndex).isEqualTo(-1);
    assertThat(oldPositions.get(2).adIndexInAdGroup).isEqualTo(-1);
    assertThat(newPositions.get(2).mediaItemIndex).isEqualTo(0);
    assertThat(newPositions.get(2).positionMs).isEqualTo(0);
    assertThat(newPositions.get(2).contentPositionMs).isEqualTo(7000);
    assertThat(newPositions.get(2).adGroupIndex).isEqualTo(1);
    assertThat(newPositions.get(2).adIndexInAdGroup).isEqualTo(0);
    // ad to ad transition
    assertThat(oldPositions.get(3).mediaItemIndex).isEqualTo(0);
    assertThat(oldPositions.get(3).positionMs).isEqualTo(5000);
    assertThat(oldPositions.get(3).contentPositionMs).isEqualTo(7000);
    assertThat(oldPositions.get(3).adGroupIndex).isEqualTo(1);
    assertThat(oldPositions.get(3).adIndexInAdGroup).isEqualTo(0);
    assertThat(newPositions.get(3).mediaItemIndex).isEqualTo(0);
    assertThat(newPositions.get(3).positionMs).isEqualTo(0);
    assertThat(newPositions.get(3).contentPositionMs).isEqualTo(7000);
    assertThat(newPositions.get(3).adGroupIndex).isEqualTo(1);
    assertThat(newPositions.get(3).adIndexInAdGroup).isEqualTo(1);
    // ad to content transition
    assertThat(oldPositions.get(4).mediaItemIndex).isEqualTo(0);
    assertThat(oldPositions.get(4).positionMs).isEqualTo(5000);
    assertThat(oldPositions.get(4).contentPositionMs).isEqualTo(7000);
    assertThat(oldPositions.get(4).adGroupIndex).isEqualTo(1);
    assertThat(oldPositions.get(4).adIndexInAdGroup).isEqualTo(1);
    assertThat(newPositions.get(4).mediaItemIndex).isEqualTo(0);
    assertThat(newPositions.get(4).positionMs).isEqualTo(7000);
    assertThat(newPositions.get(4).contentPositionMs).isEqualTo(7000);
    assertThat(newPositions.get(4).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(4).adIndexInAdGroup).isEqualTo(-1);

    // third add group (post-roll)
    assertThat(oldPositions.get(5).mediaItemIndex).isEqualTo(0);
    assertThat(oldPositions.get(5).positionMs).isEqualTo(10000);
    assertThat(oldPositions.get(5).contentPositionMs).isEqualTo(10000);
    assertThat(oldPositions.get(5).adGroupIndex).isEqualTo(-1);
    assertThat(oldPositions.get(5).adIndexInAdGroup).isEqualTo(-1);
    assertThat(newPositions.get(5).mediaItemIndex).isEqualTo(0);
    assertThat(newPositions.get(5).positionMs).isEqualTo(0);
    assertThat(newPositions.get(5).contentPositionMs).isEqualTo(10000);
    assertThat(newPositions.get(5).adGroupIndex).isEqualTo(2);
    assertThat(newPositions.get(5).adIndexInAdGroup).isEqualTo(0);
    // ad to ad transition
    assertThat(oldPositions.get(6).mediaItemIndex).isEqualTo(0);
    assertThat(oldPositions.get(6).positionMs).isEqualTo(5000);
    assertThat(oldPositions.get(6).contentPositionMs).isEqualTo(10000);
    assertThat(oldPositions.get(6).adGroupIndex).isEqualTo(2);
    assertThat(oldPositions.get(6).adIndexInAdGroup).isEqualTo(0);
    assertThat(newPositions.get(6).mediaItemIndex).isEqualTo(0);
    assertThat(newPositions.get(6).positionMs).isEqualTo(0);
    assertThat(newPositions.get(6).contentPositionMs).isEqualTo(10000);
    assertThat(newPositions.get(6).adGroupIndex).isEqualTo(2);
    assertThat(newPositions.get(6).adIndexInAdGroup).isEqualTo(1);
    // post roll ad to end of content transition
    assertThat(oldPositions.get(7).mediaItemIndex).isEqualTo(0);
    assertThat(oldPositions.get(7).positionMs).isEqualTo(5000);
    assertThat(oldPositions.get(7).contentPositionMs).isEqualTo(10000);
    assertThat(oldPositions.get(7).adGroupIndex).isEqualTo(2);
    assertThat(oldPositions.get(7).adIndexInAdGroup).isEqualTo(1);
    assertThat(newPositions.get(7).mediaItemIndex).isEqualTo(0);
    assertThat(newPositions.get(7).positionMs).isEqualTo(9999);
    assertThat(newPositions.get(7).contentPositionMs).isEqualTo(9999);
    assertThat(newPositions.get(7).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(7).adIndexInAdGroup).isEqualTo(-1);
    player.release();
  }

  @Test
  public void seekTo_seekOverMidRoll_callsOnDiscontinuityCorrectly() throws Exception {
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    Player.Listener listener = mock(Player.Listener.class);
    player.addListener(listener);
    AdPlaybackState adPlaybackState =
        FakeTimeline.createAdPlaybackState(
            /* adsPerAdGroup= */ 1, /* adGroupTimesUs...= */ 2 * C.MICROS_PER_SECOND);
    TimelineWindowDefinition adTimeline =
        new TimelineWindowDefinition.Builder()
            .setWindowPositionInFirstPeriodUs(0)
            .setAdPlaybackStates(ImmutableList.of(adPlaybackState))
            .build();
    player.setMediaSource(new FakeMediaSource(new FakeTimeline(adTimeline)));

    player.prepare();
    play(player).untilPositionAtLeast(/* mediaItemIndex= */ 0, /* positionMs= */ 1000);
    player.seekTo(/* positionMs= */ 8_000);
    player.play();
    advance(player).untilState(Player.STATE_ENDED);

    ArgumentCaptor<Player.PositionInfo> oldPosition =
        ArgumentCaptor.forClass(Player.PositionInfo.class);
    ArgumentCaptor<Player.PositionInfo> newPosition =
        ArgumentCaptor.forClass(Player.PositionInfo.class);
    verify(listener)
        .onPositionDiscontinuity(
            oldPosition.capture(), newPosition.capture(), eq(Player.DISCONTINUITY_REASON_SEEK));
    verify(listener)
        .onPositionDiscontinuity(
            oldPosition.capture(),
            newPosition.capture(),
            eq(Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT));
    verify(listener)
        .onPositionDiscontinuity(
            oldPosition.capture(),
            newPosition.capture(),
            eq(Player.DISCONTINUITY_REASON_AUTO_TRANSITION));
    verify(listener, never())
        .onPositionDiscontinuity(any(), any(), eq(Player.DISCONTINUITY_REASON_REMOVE));
    verify(listener, never())
        .onPositionDiscontinuity(any(), any(), eq(Player.DISCONTINUITY_REASON_SKIP));

    List<Player.PositionInfo> oldPositions = oldPosition.getAllValues();
    List<Player.PositionInfo> newPositions = newPosition.getAllValues();
    // SEEK behind mid roll
    assertThat(oldPositions.get(0).mediaItemIndex).isEqualTo(0);
    assertThat(oldPositions.get(0).positionMs).isIn(Range.closed(980L, 1_000L));
    assertThat(oldPositions.get(0).contentPositionMs).isIn(Range.closed(980L, 1_000L));
    assertThat(oldPositions.get(0).adGroupIndex).isEqualTo(-1);
    assertThat(oldPositions.get(0).adIndexInAdGroup).isEqualTo(-1);
    assertThat(newPositions.get(0).mediaItemIndex).isEqualTo(0);
    assertThat(newPositions.get(0).positionMs).isEqualTo(8_000);
    assertThat(newPositions.get(0).contentPositionMs).isEqualTo(8_000);
    assertThat(newPositions.get(0).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(0).adIndexInAdGroup).isEqualTo(-1);
    // SEEK_ADJUSTMENT back to ad
    assertThat(oldPositions.get(1).mediaItemIndex).isEqualTo(0);
    assertThat(oldPositions.get(1).positionMs).isEqualTo(8_000);
    assertThat(oldPositions.get(1).contentPositionMs).isEqualTo(8_000);
    assertThat(oldPositions.get(1).adGroupIndex).isEqualTo(-1);
    assertThat(oldPositions.get(1).adIndexInAdGroup).isEqualTo(-1);
    assertThat(newPositions.get(1).mediaItemIndex).isEqualTo(0);
    assertThat(newPositions.get(1).positionMs).isEqualTo(0);
    assertThat(newPositions.get(1).contentPositionMs).isEqualTo(8000);
    assertThat(newPositions.get(1).adGroupIndex).isEqualTo(0);
    assertThat(newPositions.get(1).adIndexInAdGroup).isEqualTo(0);
    // AUTO_TRANSITION back to content
    assertThat(oldPositions.get(2).mediaItemIndex).isEqualTo(0);
    assertThat(oldPositions.get(2).positionMs).isEqualTo(5_000);
    assertThat(oldPositions.get(2).contentPositionMs).isEqualTo(8_000);
    assertThat(oldPositions.get(2).adGroupIndex).isEqualTo(0);
    assertThat(oldPositions.get(2).adIndexInAdGroup).isEqualTo(0);
    assertThat(newPositions.get(2).mediaItemIndex).isEqualTo(0);
    assertThat(newPositions.get(2).positionMs).isEqualTo(8_000);
    assertThat(newPositions.get(2).contentPositionMs).isEqualTo(8_000);
    assertThat(newPositions.get(2).adGroupIndex).isEqualTo(-1);
    assertThat(newPositions.get(2).adIndexInAdGroup).isEqualTo(-1);

    player.release();
  }

  @Test
  public void play_multiItemPlaylistWidthAds_callsOnDiscontinuityCorrectly() throws Exception {
    ExoPlayer player = parameterizeTestExoPlayerBuilder(new TestExoPlayerBuilder(context)).build();
    Player.Listener listener = mock(Player.Listener.class);
    player.addListener(listener);
    AdPlaybackState postRollAdPlaybackState =
        FakeTimeline.createAdPlaybackState(
            /* adsPerAdGroup= */ 1, /* adGroupTimesUs...= */ C.TIME_END_OF_SOURCE);
    TimelineWindowDefinition postRollWindow =
        new TimelineWindowDefinition.Builder()
            .setUid("id-2")
            .setDurationUs(20 * C.MICROS_PER_SECOND)
            .setWindowPositionInFirstPeriodUs(0)
            .setAdPlaybackStates(ImmutableList.of(postRollAdPlaybackState))
            .build();
    AdPlaybackState preRollAdPlaybackState =
        FakeTimeline.createAdPlaybackState(/* adsPerAdGroup= */ 1, /* adGroupTimesUs...= */ 0);
    TimelineWindowDefinition preRollWindow =
        new TimelineWindowDefinition.Builder()
            .setUid("id-3")
            .setDurationUs(25 * C.MICROS_PER_SECOND)
            .setWindowPositionInFirstPeriodUs(0)
            .setAdPlaybackStates(ImmutableList.of(preRollAdPlaybackState))
            .build();
    player.setMediaSources(
        ImmutableList.of(
            new FakeMediaSource(
                new FakeTimeline(new TimelineWindowDefinition.Builder().setUid("id-0").build())),
            new FakeMediaSource(
                new FakeTimeline(
                    new TimelineWindowDefinition.Builder()
                        .setUid("id-1")
                        .setDurationUs(15 * C.MICROS_PER_SECOND)
                        .build())),
            new FakeMediaSource(new FakeTimeline(postRollWindow)),
            new FakeMediaSource(new FakeTimeline(preRollWindow))));

    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);

    ArgumentCaptor<Player.PositionInfo> oldPosition =
        ArgumentCaptor.forClass(Player.PositionInfo.class);
    ArgumentCaptor<Player.PositionInfo> newPosition =
        ArgumentCaptor.forClass(Player.PositionInfo.class);
    Window window = new Window();
    InOrder inOrder = Mockito.inOrder(listener);
    // from first to second media item
    inOrder
        .verify(listener)
        .onPositionDiscontinuity(
            oldPosition.capture(),
            newPosition.capture(),
            eq(Player.DISCONTINUITY_REASON_AUTO_TRANSITION));
    inOrder
        .verify(listener)
        .onMediaItemTransition(any(), eq(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO));
    assertThat(oldPosition.getValue().windowUid)
        .isEqualTo(player.getCurrentTimeline().getWindow(0, window).uid);
    assertThat(oldPosition.getValue().mediaItemIndex).isEqualTo(0);
    assertThat(oldPosition.getValue().mediaItem.localConfiguration.tag).isEqualTo("id-0");
    assertThat(oldPosition.getValue().positionMs).isEqualTo(10_000);
    assertThat(oldPosition.getValue().contentPositionMs).isEqualTo(10_000);
    assertThat(oldPosition.getValue().adGroupIndex).isEqualTo(-1);
    assertThat(oldPosition.getValue().adIndexInAdGroup).isEqualTo(-1);
    assertThat(newPosition.getValue().windowUid)
        .isEqualTo(player.getCurrentTimeline().getWindow(1, window).uid);
    assertThat(newPosition.getValue().mediaItemIndex).isEqualTo(1);
    assertThat(newPosition.getValue().mediaItem.localConfiguration.tag).isEqualTo("id-1");
    assertThat(newPosition.getValue().positionMs).isEqualTo(0);
    assertThat(newPosition.getValue().contentPositionMs).isEqualTo(0);
    assertThat(newPosition.getValue().adGroupIndex).isEqualTo(-1);
    assertThat(newPosition.getValue().adIndexInAdGroup).isEqualTo(-1);
    // from second media item to third
    inOrder
        .verify(listener)
        .onPositionDiscontinuity(
            oldPosition.capture(),
            newPosition.capture(),
            eq(Player.DISCONTINUITY_REASON_AUTO_TRANSITION));
    inOrder
        .verify(listener)
        .onMediaItemTransition(any(), eq(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO));
    assertThat(oldPosition.getValue().windowUid)
        .isEqualTo(player.getCurrentTimeline().getWindow(1, window).uid);
    assertThat(newPosition.getValue().windowUid)
        .isEqualTo(player.getCurrentTimeline().getWindow(2, window).uid);
    assertThat(oldPosition.getValue().mediaItemIndex).isEqualTo(1);
    assertThat(oldPosition.getValue().mediaItem.localConfiguration.tag).isEqualTo("id-1");
    assertThat(oldPosition.getValue().positionMs).isEqualTo(15_000);
    assertThat(oldPosition.getValue().contentPositionMs).isEqualTo(15_000);
    assertThat(oldPosition.getValue().adGroupIndex).isEqualTo(-1);
    assertThat(oldPosition.getValue().adIndexInAdGroup).isEqualTo(-1);
    assertThat(newPosition.getValue().mediaItemIndex).isEqualTo(2);
    assertThat(newPosition.getValue().mediaItem.localConfiguration.tag).isEqualTo("id-2");
    assertThat(newPosition.getValue().positionMs).isEqualTo(0);
    assertThat(newPosition.getValue().contentPositionMs).isEqualTo(0);
    assertThat(newPosition.getValue().adGroupIndex).isEqualTo(-1);
    assertThat(newPosition.getValue().adIndexInAdGroup).isEqualTo(-1);
    // from third media item content to post roll ad
    @Nullable Object lastNewWindowUid = newPosition.getValue().windowUid;
    inOrder
        .verify(listener)
        .onPositionDiscontinuity(
            oldPosition.capture(),
            newPosition.capture(),
            eq(Player.DISCONTINUITY_REASON_AUTO_TRANSITION));
    assertThat(oldPosition.getValue().mediaItemIndex).isEqualTo(2);
    assertThat(oldPosition.getValue().mediaItem.localConfiguration.tag).isEqualTo("id-2");
    assertThat(oldPosition.getValue().windowUid).isEqualTo(lastNewWindowUid);
    assertThat(oldPosition.getValue().positionMs).isEqualTo(20_000);
    assertThat(oldPosition.getValue().contentPositionMs).isEqualTo(20_000);
    assertThat(oldPosition.getValue().adGroupIndex).isEqualTo(-1);
    assertThat(oldPosition.getValue().adIndexInAdGroup).isEqualTo(-1);
    assertThat(newPosition.getValue().mediaItemIndex).isEqualTo(2);
    assertThat(newPosition.getValue().mediaItem.localConfiguration.tag).isEqualTo("id-2");
    assertThat(newPosition.getValue().positionMs).isEqualTo(0);
    assertThat(newPosition.getValue().contentPositionMs).isEqualTo(20_000);
    assertThat(newPosition.getValue().adGroupIndex).isEqualTo(0);
    assertThat(newPosition.getValue().adIndexInAdGroup).isEqualTo(0);
    // from third media item post roll to third media item content end
    lastNewWindowUid = newPosition.getValue().windowUid;
    inOrder
        .verify(listener)
        .onPositionDiscontinuity(
            oldPosition.capture(),
            newPosition.capture(),
            eq(Player.DISCONTINUITY_REASON_AUTO_TRANSITION));
    assertThat(oldPosition.getValue().windowUid).isEqualTo(lastNewWindowUid);
    assertThat(oldPosition.getValue().mediaItemIndex).isEqualTo(2);
    assertThat(oldPosition.getValue().mediaItem.localConfiguration.tag).isEqualTo("id-2");
    assertThat(oldPosition.getValue().positionMs).isEqualTo(5_000);
    assertThat(oldPosition.getValue().contentPositionMs).isEqualTo(20_000);
    assertThat(oldPosition.getValue().adGroupIndex).isEqualTo(0);
    assertThat(oldPosition.getValue().adIndexInAdGroup).isEqualTo(0);
    assertThat(newPosition.getValue().windowUid).isEqualTo(oldPosition.getValue().windowUid);
    assertThat(newPosition.getValue().mediaItemIndex).isEqualTo(2);
    assertThat(newPosition.getValue().mediaItem.localConfiguration.tag).isEqualTo("id-2");
    assertThat(newPosition.getValue().positionMs).isEqualTo(19_999);
    assertThat(newPosition.getValue().contentPositionMs).isEqualTo(19_999);
    assertThat(newPosition.getValue().adGroupIndex).isEqualTo(-1);
    assertThat(newPosition.getValue().adIndexInAdGroup).isEqualTo(-1);
    // from third media item content end to fourth media item pre roll ad
    lastNewWindowUid = newPosition.getValue().windowUid;
    inOrder
        .verify(listener)
        .onPositionDiscontinuity(
            oldPosition.capture(),
            newPosition.capture(),
            eq(Player.DISCONTINUITY_REASON_AUTO_TRANSITION));
    inOrder
        .verify(listener)
        .onMediaItemTransition(any(), eq(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO));
    assertThat(oldPosition.getValue().windowUid).isEqualTo(lastNewWindowUid);
    assertThat(oldPosition.getValue().mediaItemIndex).isEqualTo(2);
    assertThat(oldPosition.getValue().mediaItem.localConfiguration.tag).isEqualTo("id-2");
    assertThat(oldPosition.getValue().positionMs).isEqualTo(20_000);
    assertThat(oldPosition.getValue().contentPositionMs).isEqualTo(20_000);
    assertThat(oldPosition.getValue().adGroupIndex).isEqualTo(-1);
    assertThat(oldPosition.getValue().adIndexInAdGroup).isEqualTo(-1);
    assertThat(newPosition.getValue().windowUid).isNotEqualTo(oldPosition.getValue().windowUid);
    assertThat(newPosition.getValue().mediaItemIndex).isEqualTo(3);
    assertThat(newPosition.getValue().mediaItem.localConfiguration.tag).isEqualTo("id-3");
    assertThat(newPosition.getValue().positionMs).isEqualTo(0);
    assertThat(newPosition.getValue().contentPositionMs).isEqualTo(0);
    assertThat(newPosition.getValue().adGroupIndex).isEqualTo(0);
    assertThat(newPosition.getValue().adIndexInAdGroup).isEqualTo(0);
    // from fourth media item pre roll ad to fourth media item content
    lastNewWindowUid = newPosition.getValue().windowUid;
    inOrder
        .verify(listener)
        .onPositionDiscontinuity(
            oldPosition.capture(),
            newPosition.capture(),
            eq(Player.DISCONTINUITY_REASON_AUTO_TRANSITION));
    assertThat(oldPosition.getValue().windowUid).isEqualTo(lastNewWindowUid);
    assertThat(oldPosition.getValue().mediaItemIndex).isEqualTo(3);
    assertThat(oldPosition.getValue().mediaItem.localConfiguration.tag).isEqualTo("id-3");
    assertThat(oldPosition.getValue().positionMs).isEqualTo(5_000);
    assertThat(oldPosition.getValue().contentPositionMs).isEqualTo(0);
    assertThat(oldPosition.getValue().adGroupIndex).isEqualTo(0);
    assertThat(oldPosition.getValue().adIndexInAdGroup).isEqualTo(0);
    assertThat(newPosition.getValue().windowUid).isEqualTo(oldPosition.getValue().windowUid);
    assertThat(newPosition.getValue().mediaItemIndex).isEqualTo(3);
    assertThat(newPosition.getValue().mediaItem.localConfiguration.tag).isEqualTo("id-3");
    assertThat(newPosition.getValue().positionMs).isEqualTo(0);
    assertThat(newPosition.getValue().contentPositionMs).isEqualTo(0);
    assertThat(newPosition.getValue().adGroupIndex).isEqualTo(-1);
    assertThat(newPosition.getValue().adIndexInAdGroup).isEqualTo(-1);
    inOrder
        .verify(listener, never())
        .onPositionDiscontinuity(
            any(), any(), not(eq(Player.DISCONTINUITY_REASON_AUTO_TRANSITION)));
    inOrder
        .verify(listener, never())
        .onMediaItemTransition(any(), not(eq(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)));
    player.release();
  }

  @Test
  public void newServerSideInsertedAdAtPlaybackPosition_keepsRenderersEnabled() throws Exception {
    // Injecting renderer to count number of renderer resets.
    AtomicReference<FakeVideoRenderer> videoRenderer = new AtomicReference<>();
    RenderersFactory renderersFactory =
        (handler, videoListener, audioListener, textOutput, metadataOutput) -> {
          videoRenderer.set(
              new FakeVideoRenderer(
                  SystemClock.DEFAULT.createHandler(handler.getLooper(), /* callback= */ null),
                  videoListener));
          return new Renderer[] {videoRenderer.get()};
        };
    ExoPlayer player =
        parameterizeTestExoPlayerBuilder(
                new TestExoPlayerBuilder(context).setRenderersFactory(renderersFactory))
            .build();
    // Live stream timeline with unassigned next ad group.
    AdPlaybackState initialAdPlaybackState =
        new AdPlaybackState(/* adsId= */ new Object())
            .withLivePostrollPlaceholderAppended(/* isServerSideInserted= */ true);
    // Updated timeline with ad group at 18 seconds.
    long firstSampleTimeUs = TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
    TimelineWindowDefinition initialTimelineWindowDefinition =
        new TimelineWindowDefinition.Builder()
            .setDynamic(true)
            .setDurationUs(C.TIME_UNSET)
            .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
            .build();
    Timeline initialTimeline = new FakeTimeline(initialTimelineWindowDefinition);
    AdPlaybackState updatedAdPlaybackState =
        initialAdPlaybackState
            .withNewAdGroup(0, firstSampleTimeUs + 1_800_000)
            .withIsServerSideInserted(/* adGroupIndex= */ 0, /* isServerSideInserted= */ true)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAdDurationsUs(/* adGroupIndex= */ 0, new long[] {1_000_000});
    // Add samples to allow player to load and start playing (but no EOS as this is a live stream).
    FakeMediaSource mediaSource =
        new FakeMediaSource.Builder()
            .setTimeline(initialTimeline)
            .setTrackDataFactory(
                (format, mediaPeriodId) ->
                    ImmutableList.of(
                        oneByteSample(firstSampleTimeUs, C.BUFFER_FLAG_KEY_FRAME),
                        oneByteSample(firstSampleTimeUs + 4_000_000)))
            .setFormats(ExoPlayerTestRunner.VIDEO_FORMAT)
            .build();

    // Set updated ad group once we reach 20 seconds, and then continue playing until 40 seconds.
    player
        .createMessage(
            (message, payload) ->
                mediaSource.setNewSourceInfo(
                    new FakeTimeline(
                        initialTimelineWindowDefinition
                            .buildUpon()
                            .setAdPlaybackStates(ImmutableList.of(updatedAdPlaybackState))
                            .build())))
        .setPosition(2_000L)
        .send();
    player.setMediaSource(mediaSource);
    player.prepare();
    play(player).untilPositionAtLeast(/* mediaItemIndex= */ 0, /* positionMs= */ 4_000L);
    Timeline timeline = player.getCurrentTimeline();
    player.release();

    // Assert that the renderer hasn't been reset despite the inserted ad group.
    assertThat(videoRenderer.get().positionResetCount).isEqualTo(1);
    assertThat(timeline.getPeriod(0, new Timeline.Period()).adPlaybackState.adGroupCount)
        .isEqualTo(2);
  }
}
