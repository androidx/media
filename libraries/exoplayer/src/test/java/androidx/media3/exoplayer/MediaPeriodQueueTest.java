/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static androidx.media3.common.util.Util.msToUs;
import static androidx.media3.test.utils.ExoPlayerTestRunner.AUDIO_FORMAT;
import static androidx.media3.test.utils.ExoPlayerTestRunner.VIDEO_FORMAT;
import static androidx.media3.test.utils.FakeMultiPeriodLiveTimeline.AD_PERIOD_DURATION_MS;
import static androidx.media3.test.utils.FakeMultiPeriodLiveTimeline.PERIOD_DURATION_MS;
import static androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition.DEFAULT_WINDOW_DURATION_US;
import static androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.exoplayer.analytics.AnalyticsCollector;
import androidx.media3.exoplayer.analytics.DefaultAnalyticsCollector;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.MediaSource.MediaSourceCaller;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;
import androidx.media3.exoplayer.source.ads.ServerSideAdInsertionMediaSource;
import androidx.media3.exoplayer.source.ads.SinglePeriodAdTimeline;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelectorResult;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeMultiPeriodLiveTimeline;
import androidx.media3.test.utils.FakeShuffleOrder;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link MediaPeriodQueue}. */
@RunWith(AndroidJUnit4.class)
public final class MediaPeriodQueueTest {

  private static final long CONTENT_DURATION_US = 30 * C.MICROS_PER_SECOND;
  private static final long AD_DURATION_US = 10 * C.MICROS_PER_SECOND;
  private static final long FIRST_AD_START_TIME_US = 10 * C.MICROS_PER_SECOND;
  private static final long SECOND_AD_START_TIME_US = 20 * C.MICROS_PER_SECOND;

  private static final MediaItem AD_MEDIA_ITEM = MediaItem.fromUri("https://google.com/empty");
  private static final Timeline CONTENT_TIMELINE =
      new SinglePeriodTimeline(
          CONTENT_DURATION_US,
          /* isSeekable= */ true,
          /* isDynamic= */ false,
          /* useLiveConfiguration= */ false,
          /* manifest= */ null,
          AD_MEDIA_ITEM);

  private MediaPeriodQueue mediaPeriodQueue;
  private AdPlaybackState adPlaybackState;
  private Object firstPeriodUid;

  private PlaybackInfo playbackInfo;
  private RendererCapabilities[] rendererCapabilities;
  private TrackSelector trackSelector;
  private Allocator allocator;
  private MediaSourceList mediaSourceList;
  private FakeMediaSource fakeMediaSource;

  @Before
  public void setUp() {
    AnalyticsCollector analyticsCollector = new DefaultAnalyticsCollector(Clock.DEFAULT);
    analyticsCollector.setPlayer(
        new ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build(),
        Looper.getMainLooper());
    HandlerWrapper handler =
        Clock.DEFAULT.createHandler(Looper.getMainLooper(), /* callback= */ null);
    mediaPeriodQueue =
        new MediaPeriodQueue(
            analyticsCollector,
            handler,
            (info, rendererPositionOffsetUs) ->
                new MediaPeriodHolder(
                    rendererCapabilities,
                    rendererPositionOffsetUs,
                    trackSelector,
                    allocator,
                    mediaSourceList,
                    info,
                    new TrackSelectorResult(
                        new RendererConfiguration[0],
                        new ExoTrackSelection[0],
                        Tracks.EMPTY,
                        /* info= */ null)));
    mediaSourceList =
        new MediaSourceList(
            mock(MediaSourceList.MediaSourceListInfoRefreshListener.class),
            analyticsCollector,
            handler,
            PlayerId.UNSET);
    rendererCapabilities = new RendererCapabilities[0];
    trackSelector = mock(TrackSelector.class);
    allocator = mock(Allocator.class);
  }

  @Test
  public void getNextMediaPeriodInfo_withoutAds_returnsLastMediaPeriodInfo() {
    setupAdTimeline(/* no ad groups */ );
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ C.TIME_UNSET,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ CONTENT_DURATION_US,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ true,
        /* isLastInWindow= */ true,
        /* isFinal= */ true,
        /* nextAdGroupIndex= */ C.INDEX_UNSET);
  }

  @Test
  public void getNextMediaPeriodInfo_withPrerollAd_returnsCorrectMediaPeriodInfos() {
    setupAdTimeline(/* adGroupTimesUs...= */ 0);
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    assertNextMediaPeriodInfoIsAd(
        firstPeriodUid,
        /* adGroupIndex= */ 0,
        AD_DURATION_US,
        /* contentPositionUs= */ C.TIME_UNSET,
        /* isFollowedByTransitionToSameStream= */ false);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ C.TIME_UNSET,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ CONTENT_DURATION_US,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ true,
        /* isLastInWindow= */ true,
        /* isFinal= */ true,
        /* nextAdGroupIndex= */ C.INDEX_UNSET);
  }

  @Test
  public void getNextMediaPeriodInfo_withMidrollAds_returnsCorrectMediaPeriodInfos() {
    setupAdTimeline(/* adGroupTimesUs...= */ FIRST_AD_START_TIME_US, SECOND_AD_START_TIME_US);
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ C.TIME_UNSET,
        /* endPositionUs= */ FIRST_AD_START_TIME_US,
        /* durationUs= */ FIRST_AD_START_TIME_US,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ false,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ 0);
    advance();
    assertNextMediaPeriodInfoIsAd(
        firstPeriodUid,
        /* adGroupIndex= */ 0,
        /* adDurationUs= */ C.TIME_UNSET,
        /* contentPositionUs= */ FIRST_AD_START_TIME_US,
        /* isFollowedByTransitionToSameStream= */ false);
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    assertNextMediaPeriodInfoIsAd(
        firstPeriodUid,
        /* adGroupIndex= */ 0,
        AD_DURATION_US,
        /* contentPositionUs= */ FIRST_AD_START_TIME_US,
        /* isFollowedByTransitionToSameStream= */ false);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ FIRST_AD_START_TIME_US,
        /* requestedContentPositionUs= */ FIRST_AD_START_TIME_US,
        /* endPositionUs= */ SECOND_AD_START_TIME_US,
        /* durationUs= */ SECOND_AD_START_TIME_US,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ false,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ 1);
    advance();
    setAdGroupLoaded(/* adGroupIndex= */ 1);
    assertNextMediaPeriodInfoIsAd(
        firstPeriodUid,
        /* adGroupIndex= */ 1,
        AD_DURATION_US,
        /* contentPositionUs= */ SECOND_AD_START_TIME_US,
        /* isFollowedByTransitionToSameStream= */ false);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ SECOND_AD_START_TIME_US,
        /* requestedContentPositionUs= */ SECOND_AD_START_TIME_US,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ CONTENT_DURATION_US,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ true,
        /* isLastInWindow= */ true,
        /* isFinal= */ true,
        /* nextAdGroupIndex= */ C.INDEX_UNSET);
  }

  @Test
  public void getNextMediaPeriodInfo_withMidrollAndPostroll_returnsCorrectMediaPeriodInfos() {
    setupAdTimeline(/* adGroupTimesUs...= */ FIRST_AD_START_TIME_US, C.TIME_END_OF_SOURCE);
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ C.TIME_UNSET,
        /* endPositionUs= */ FIRST_AD_START_TIME_US,
        /* durationUs= */ FIRST_AD_START_TIME_US,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ false,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ 0);
    advance();
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    assertNextMediaPeriodInfoIsAd(
        firstPeriodUid,
        /* adGroupIndex= */ 0,
        AD_DURATION_US,
        /* contentPositionUs= */ FIRST_AD_START_TIME_US,
        /* isFollowedByTransitionToSameStream= */ false);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ FIRST_AD_START_TIME_US,
        /* requestedContentPositionUs= */ FIRST_AD_START_TIME_US,
        /* endPositionUs= */ C.TIME_END_OF_SOURCE,
        /* durationUs= */ CONTENT_DURATION_US,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ false,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ 1);
    advance();
    setAdGroupLoaded(/* adGroupIndex= */ 1);
    assertNextMediaPeriodInfoIsAd(
        firstPeriodUid,
        /* adGroupIndex= */ 1,
        AD_DURATION_US,
        /* contentPositionUs= */ CONTENT_DURATION_US,
        /* isFollowedByTransitionToSameStream= */ false);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ CONTENT_DURATION_US - 1,
        /* requestedContentPositionUs= */ CONTENT_DURATION_US,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ CONTENT_DURATION_US,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ true,
        /* isLastInWindow= */ true,
        /* isFinal= */ true,
        /* nextAdGroupIndex= */ C.INDEX_UNSET);
  }

  @Test
  public void getNextMediaPeriodInfo_withAdGroupResumeOffsets_returnsCorrectMediaPeriodInfos() {
    adPlaybackState =
        new AdPlaybackState(
                /* adsId= */ new Object(),
                /* adGroupTimesUs...= */ 0,
                FIRST_AD_START_TIME_US,
                C.TIME_END_OF_SOURCE)
            .withContentDurationUs(CONTENT_DURATION_US)
            .withContentResumeOffsetUs(/* adGroupIndex= */ 0, /* contentResumeOffsetUs= */ 2000)
            .withContentResumeOffsetUs(/* adGroupIndex= */ 1, /* contentResumeOffsetUs= */ 3000)
            .withContentResumeOffsetUs(/* adGroupIndex= */ 2, /* contentResumeOffsetUs= */ 4000);
    SinglePeriodAdTimeline adTimeline =
        new SinglePeriodAdTimeline(CONTENT_TIMELINE, adPlaybackState);
    setupTimeline(adTimeline);

    setAdGroupLoaded(/* adGroupIndex= */ 0);
    assertNextMediaPeriodInfoIsAd(
        firstPeriodUid,
        /* adGroupIndex= */ 0,
        AD_DURATION_US,
        /* contentPositionUs= */ C.TIME_UNSET,
        /* isFollowedByTransitionToSameStream= */ false);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ 2000,
        /* requestedContentPositionUs= */ C.TIME_UNSET,
        /* endPositionUs= */ FIRST_AD_START_TIME_US,
        /* durationUs= */ FIRST_AD_START_TIME_US,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ false,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ 1);
    advance();
    setAdGroupLoaded(/* adGroupIndex= */ 1);
    assertNextMediaPeriodInfoIsAd(
        firstPeriodUid,
        /* adGroupIndex= */ 1,
        AD_DURATION_US,
        /* contentPositionUs= */ FIRST_AD_START_TIME_US,
        /* isFollowedByTransitionToSameStream= */ false);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ FIRST_AD_START_TIME_US + 3000,
        /* requestedContentPositionUs= */ FIRST_AD_START_TIME_US,
        /* endPositionUs= */ C.TIME_END_OF_SOURCE,
        /* durationUs= */ CONTENT_DURATION_US,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ false,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ 2);
    advance();
    setAdGroupLoaded(/* adGroupIndex= */ 2);
    assertNextMediaPeriodInfoIsAd(
        firstPeriodUid,
        /* adGroupIndex= */ 2,
        AD_DURATION_US,
        /* contentPositionUs= */ CONTENT_DURATION_US,
        /* isFollowedByTransitionToSameStream= */ false);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ CONTENT_DURATION_US - 1,
        /* requestedContentPositionUs= */ CONTENT_DURATION_US,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ CONTENT_DURATION_US,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ true,
        /* isLastInWindow= */ true,
        /* isFinal= */ true,
        /* nextAdGroupIndex= */ C.INDEX_UNSET);
  }

  @Test
  public void getNextMediaPeriodInfo_withServerSideInsertedAds_returnsCorrectMediaPeriodInfos() {
    adPlaybackState =
        new AdPlaybackState(
                /* adsId= */ new Object(),
                /* adGroupTimesUs...= */ 0,
                FIRST_AD_START_TIME_US,
                SECOND_AD_START_TIME_US)
            .withContentDurationUs(CONTENT_DURATION_US)
            .withIsServerSideInserted(/* adGroupIndex= */ 0, /* isServerSideInserted= */ true)
            .withIsServerSideInserted(/* adGroupIndex= */ 1, /* isServerSideInserted= */ true)
            .withIsServerSideInserted(/* adGroupIndex= */ 2, /* isServerSideInserted= */ true);
    SinglePeriodAdTimeline adTimeline =
        new SinglePeriodAdTimeline(CONTENT_TIMELINE, adPlaybackState);
    setupTimeline(adTimeline);

    setAdGroupLoaded(/* adGroupIndex= */ 0);
    assertNextMediaPeriodInfoIsAd(
        firstPeriodUid,
        /* adGroupIndex= */ 0,
        AD_DURATION_US,
        /* contentPositionUs= */ C.TIME_UNSET,
        /* isFollowedByTransitionToSameStream= */ true);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ C.TIME_UNSET,
        /* endPositionUs= */ FIRST_AD_START_TIME_US,
        /* durationUs= */ FIRST_AD_START_TIME_US,
        /* isFollowedByTransitionToSameStream= */ true,
        /* isLastInPeriod= */ false,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ 1);
    advance();
    setAdGroupLoaded(/* adGroupIndex= */ 1);
    assertNextMediaPeriodInfoIsAd(
        firstPeriodUid,
        /* adGroupIndex= */ 1,
        AD_DURATION_US,
        /* contentPositionUs= */ FIRST_AD_START_TIME_US,
        /* isFollowedByTransitionToSameStream= */ true);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ FIRST_AD_START_TIME_US,
        /* requestedContentPositionUs= */ FIRST_AD_START_TIME_US,
        /* endPositionUs= */ SECOND_AD_START_TIME_US,
        /* durationUs= */ SECOND_AD_START_TIME_US,
        /* isFollowedByTransitionToSameStream= */ true,
        /* isLastInPeriod= */ false,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ 2);
    advance();
    setAdGroupLoaded(/* adGroupIndex= */ 2);
    assertNextMediaPeriodInfoIsAd(
        firstPeriodUid,
        /* adGroupIndex= */ 2,
        AD_DURATION_US,
        /* contentPositionUs= */ SECOND_AD_START_TIME_US,
        /* isFollowedByTransitionToSameStream= */ true);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ SECOND_AD_START_TIME_US,
        /* requestedContentPositionUs= */ SECOND_AD_START_TIME_US,
        /* endPositionUs= */ CONTENT_DURATION_US,
        /* durationUs= */ CONTENT_DURATION_US,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ true,
        /* isLastInWindow= */ true,
        /* isFinal= */ true,
        /* nextAdGroupIndex= */ C.INDEX_UNSET);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void getNextMediaPeriodInfo_multiPeriodTimelineWithNoAdsAndNoPostrollPlaceholder() {
    long contentPeriodDurationUs = msToUs(PERIOD_DURATION_MS);
    long adPeriodDurationUs = msToUs(AD_PERIOD_DURATION_MS);
    // Multi period timeline without ad playback state.
    FakeMultiPeriodLiveTimeline multiPeriodLiveTimeline =
        new FakeMultiPeriodLiveTimeline(
            /* availabilityStartTimeMs= */ 0,
            /* liveWindowDurationUs= */ 60_000_000,
            /* nowUs= */ 110_000_000,
            /* adSequencePattern= */ new boolean[] {false, true, true},
            /* periodDurationMsPattern= */ new long[] {
              PERIOD_DURATION_MS, AD_PERIOD_DURATION_MS, AD_PERIOD_DURATION_MS
            },
            /* isContentTimeline= */ true,
            /* populateAds= */ false,
            /* playedAds= */ false);
    setupTimeline(multiPeriodLiveTimeline);
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ C.TIME_UNSET,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ contentPeriodDurationUs,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ true,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ C.INDEX_UNSET);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        new Pair<Object, Object>(((Pair<Object, Object>) firstPeriodUid).first, "uid-4[a]"),
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ 0,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ adPeriodDurationUs,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ true,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ C.INDEX_UNSET);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        new Pair<Object, Object>(((Pair<Object, Object>) firstPeriodUid).first, "uid-5[a]"),
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ 0,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ adPeriodDurationUs,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ true,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ C.INDEX_UNSET);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        new Pair<Object, Object>(((Pair<Object, Object>) firstPeriodUid).first, "uid-6[c]"),
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ 0,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ C.TIME_UNSET, // last period in live timeline
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ true,
        /* isLastInWindow= */ true,
        /* isFinal= */ false, // a dynamic window never has a final period
        /* nextAdGroupIndex= */ C.INDEX_UNSET);
    advance();
    assertThat(getNextMediaPeriodInfo()).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void getNextMediaPeriodInfo_multiPeriodTimelineWithPostrollPlaceHolder() {
    long contentPeriodDurationUs = msToUs(PERIOD_DURATION_MS);
    long adPeriodDurationUs = msToUs(AD_PERIOD_DURATION_MS);
    // Multi period timeline without ad playback state.
    FakeMultiPeriodLiveTimeline multiPeriodLiveTimeline =
        new FakeMultiPeriodLiveTimeline(
            /* availabilityStartTimeMs= */ 0,
            /* liveWindowDurationUs= */ 60_000_000,
            /* nowUs= */ 110_000_000,
            /* adSequencePattern= */ new boolean[] {false, true, true},
            /* periodDurationMsPattern= */ new long[] {
              PERIOD_DURATION_MS, AD_PERIOD_DURATION_MS, AD_PERIOD_DURATION_MS
            },
            /* isContentTimeline= */ false,
            /* populateAds= */ false,
            /* playedAds= */ false);
    setupTimeline(multiPeriodLiveTimeline);
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ C.TIME_UNSET,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ contentPeriodDurationUs,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ false,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ 0);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        new Pair<Object, Object>(((Pair<Object, Object>) firstPeriodUid).first, "uid-4[a]"),
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ 0,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ adPeriodDurationUs,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ false,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ 0);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        new Pair<Object, Object>(((Pair<Object, Object>) firstPeriodUid).first, "uid-5[a]"),
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ 0,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ adPeriodDurationUs,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ false,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ 0);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        new Pair<Object, Object>(((Pair<Object, Object>) firstPeriodUid).first, "uid-6[c]"),
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ 0,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ C.TIME_UNSET, // last period in live timeline
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ false,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ 0);
    advance();
    assertThat(getNextMediaPeriodInfo()).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void getNextMediaPeriodInfo_multiPeriodTimelineWithAdsAndWithPostRollPlaceHolder() {
    long contentPeriodDurationUs = msToUs(PERIOD_DURATION_MS);
    long adPeriodDurationUs = msToUs(AD_PERIOD_DURATION_MS);
    FakeMultiPeriodLiveTimeline multiPeriodLiveTimeline =
        new FakeMultiPeriodLiveTimeline(
            /* availabilityStartTimeMs= */ 0,
            /* liveWindowDurationUs= */ 60_000_000,
            /* nowUs= */ 110_000_000,
            /* adSequencePattern= */ new boolean[] {false, true, true},
            /* periodDurationMsPattern= */ new long[] {
              PERIOD_DURATION_MS, AD_PERIOD_DURATION_MS, AD_PERIOD_DURATION_MS
            },
            /* isContentTimeline= */ false,
            /* populateAds= */ true,
            /* playedAds= */ false);
    setupTimeline(multiPeriodLiveTimeline);
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ C.TIME_UNSET,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ contentPeriodDurationUs,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ false,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ 0);
    advance();
    assertNextMediaPeriodInfoIsAd(
        /* periodUid= */ new Pair<Object, Object>(
            ((Pair<Object, Object>) firstPeriodUid).first, "uid-4[a]"),
        /* adGroupIndex= */ 0,
        /* adDurationUs= */ adPeriodDurationUs,
        /* contentPositionUs= */ 0,
        /* isFollowedByTransitionToSameStream= */ true);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        new Pair<Object, Object>(((Pair<Object, Object>) firstPeriodUid).first, "uid-4[a]"),
        /* startPositionUs= */ adPeriodDurationUs,
        /* requestedContentPositionUs= */ 0,
        /* endPositionUs= */ adPeriodDurationUs,
        /* durationUs= */ adPeriodDurationUs,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ true,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ C.INDEX_UNSET);
    advance();
    assertNextMediaPeriodInfoIsAd(
        /* periodUid= */ new Pair<Object, Object>(
            ((Pair<Object, Object>) firstPeriodUid).first, "uid-5[a]"),
        /* adGroupIndex= */ 0,
        /* adDurationUs= */ adPeriodDurationUs,
        /* contentPositionUs= */ 0,
        /* isFollowedByTransitionToSameStream= */ true);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        new Pair<Object, Object>(((Pair<Object, Object>) firstPeriodUid).first, "uid-5[a]"),
        /* startPositionUs= */ adPeriodDurationUs,
        /* requestedContentPositionUs= */ 0,
        /* endPositionUs= */ adPeriodDurationUs,
        /* durationUs= */ adPeriodDurationUs,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ true,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ C.INDEX_UNSET);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        new Pair<Object, Object>(((Pair<Object, Object>) firstPeriodUid).first, "uid-6[c]"),
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ 0,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ C.TIME_UNSET, // Last period in stream.
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ false,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ 0);
    advance();
    assertThat(getNextMediaPeriodInfo()).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  public void getNextMediaPeriodInfo_multiPeriodTimelineWithPlayedAdsAndWithPostRollPlaceHolder() {
    long contentPeriodDurationUs = msToUs(PERIOD_DURATION_MS);
    FakeMultiPeriodLiveTimeline multiPeriodLiveTimeline =
        new FakeMultiPeriodLiveTimeline(
            /* availabilityStartTimeMs= */ 0,
            /* liveWindowDurationUs= */ 60_000_000,
            /* nowUs= */ 110_000_000,
            /* adSequencePattern= */ new boolean[] {false, true, true},
            /* periodDurationMsPattern= */ new long[] {
              PERIOD_DURATION_MS, AD_PERIOD_DURATION_MS, AD_PERIOD_DURATION_MS
            },
            /* isContentTimeline= */ false,
            /* populateAds= */ true,
            /* playedAds= */ true);
    setupTimeline(multiPeriodLiveTimeline);
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ C.TIME_UNSET,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ contentPeriodDurationUs,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ false,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ 0);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        new Pair<Object, Object>(((Pair<Object, Object>) firstPeriodUid).first, "uid-6[c]"),
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ 0,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ C.TIME_UNSET, // Last period in stream.
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ false,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ 0);
    advance();
    assertThat(getNextMediaPeriodInfo()).isNull();
  }

  @Test
  public void getNextMediaPeriodInfo_withPostrollLoadError_returnsEmptyFinalMediaPeriodInfo() {
    setupAdTimeline(/* adGroupTimesUs...= */ C.TIME_END_OF_SOURCE);
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ C.TIME_UNSET,
        /* endPositionUs= */ C.TIME_END_OF_SOURCE,
        /* durationUs= */ CONTENT_DURATION_US,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ false,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ 0);
    advance();
    setAdGroupFailedToLoad(/* adGroupIndex= */ 0);
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ CONTENT_DURATION_US - 1,
        /* requestedContentPositionUs= */ CONTENT_DURATION_US,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ CONTENT_DURATION_US,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ true,
        /* isLastInWindow= */ true,
        /* isFinal= */ true,
        /* nextAdGroupIndex= */ C.INDEX_UNSET);
  }

  @Test
  public void getNextMediaPeriodInfo_withPlayedAdGroups_returnsCorrectMediaPeriodInfos() {
    setupAdTimeline(/* adGroupTimesUs...= */ 0, FIRST_AD_START_TIME_US, C.TIME_END_OF_SOURCE);
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    setAdGroupLoaded(/* adGroupIndex= */ 1);
    setAdGroupLoaded(/* adGroupIndex= */ 2);
    assertNextMediaPeriodInfoIsAd(
        firstPeriodUid,
        /* adGroupIndex= */ 0,
        AD_DURATION_US,
        /* contentPositionUs= */ C.TIME_UNSET,
        /* isFollowedByTransitionToSameStream= */ false);
    setAdGroupPlayed(/* adGroupIndex= */ 0);
    clear();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ C.TIME_UNSET,
        /* endPositionUs= */ FIRST_AD_START_TIME_US,
        /* durationUs= */ FIRST_AD_START_TIME_US,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ false,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ 1);
    setAdGroupPlayed(/* adGroupIndex= */ 1);
    clear();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ C.TIME_UNSET,
        /* endPositionUs= */ C.TIME_END_OF_SOURCE,
        /* durationUs= */ CONTENT_DURATION_US,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ false,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ 2);
    setAdGroupPlayed(/* adGroupIndex= */ 2);
    clear();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ firstPeriodUid,
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ C.TIME_UNSET,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ CONTENT_DURATION_US,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ true,
        /* isLastInWindow= */ true,
        /* isFinal= */ true,
        /* nextAdGroupIndex= */ C.INDEX_UNSET);
  }

  @Test
  public void getNextMediaPeriodInfo_inMultiPeriodWindow_returnsCorrectMediaPeriodInfos() {
    setupTimeline(
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 2,
                /* id= */ new Object(),
                /* isSeekable= */ false,
                /* isDynamic= */ false,
                /* durationUs= */ 2 * CONTENT_DURATION_US)));

    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ playbackInfo.timeline.getUidOfPeriod(/* periodIndex= */ 0),
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ C.TIME_UNSET,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ CONTENT_DURATION_US + DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ true,
        /* isLastInWindow= */ false,
        /* isFinal= */ false,
        /* nextAdGroupIndex= */ C.INDEX_UNSET);
    advance();
    assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
        /* periodUid= */ playbackInfo.timeline.getUidOfPeriod(/* periodIndex= */ 1),
        /* startPositionUs= */ 0,
        /* requestedContentPositionUs= */ 0,
        /* endPositionUs= */ C.TIME_UNSET,
        /* durationUs= */ CONTENT_DURATION_US,
        /* isFollowedByTransitionToSameStream= */ false,
        /* isLastInPeriod= */ true,
        /* isLastInWindow= */ true,
        /* isFinal= */ true,
        /* nextAdGroupIndex= */ C.INDEX_UNSET);
  }

  @Test
  public void
      updateQueuedPeriods_withDurationChangeInPlayingContent_handlesChangeAndRemovesPeriodsAfterChangedPeriod() {
    setupAdTimeline(/* adGroupTimesUs...= */ FIRST_AD_START_TIME_US);
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    enqueueNext(); // Content before ad.
    enqueueNext(); // Ad.
    enqueueNext(); // Content after ad.

    // Change position of first ad (= change duration of playing content before first ad).
    updateAdPlaybackStateAndTimeline(/* adGroupTimesUs...= */ FIRST_AD_START_TIME_US - 2000);
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    long maxRendererReadPositionUs =
        MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US + FIRST_AD_START_TIME_US - 3000;
    boolean changeHandled =
        mediaPeriodQueue.updateQueuedPeriods(
            playbackInfo.timeline,
            /* rendererPositionUs= */ MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US,
            maxRendererReadPositionUs);

    assertThat(changeHandled).isTrue();
    assertThat(getQueueLength()).isEqualTo(1);
    assertThat(mediaPeriodQueue.getPlayingPeriod().info.endPositionUs)
        .isEqualTo(FIRST_AD_START_TIME_US - 2000);
    assertThat(mediaPeriodQueue.getPlayingPeriod().info.durationUs)
        .isEqualTo(FIRST_AD_START_TIME_US - 2000);
  }

  @Test
  public void
      updateQueuedPeriods_withDurationChangeInPlayingContentAfterReadingPosition_doesntHandleChangeAndRemovesPeriodsAfterChangedPeriod() {
    setupAdTimeline(/* adGroupTimesUs...= */ FIRST_AD_START_TIME_US);
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    enqueueNext(); // Content before ad.
    enqueueNext(); // Ad.
    enqueueNext(); // Content after ad.

    // Change position of first ad (= change duration of playing content before first ad).
    updateAdPlaybackStateAndTimeline(/* adGroupTimesUs...= */ FIRST_AD_START_TIME_US - 2000);
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    long maxRendererReadPositionUs =
        MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US + FIRST_AD_START_TIME_US - 1000;
    boolean changeHandled =
        mediaPeriodQueue.updateQueuedPeriods(
            playbackInfo.timeline,
            /* rendererPositionUs= */ MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US,
            maxRendererReadPositionUs);

    assertThat(changeHandled).isFalse();
    assertThat(getQueueLength()).isEqualTo(1);
    assertThat(mediaPeriodQueue.getPlayingPeriod().info.endPositionUs)
        .isEqualTo(FIRST_AD_START_TIME_US - 2000);
    assertThat(mediaPeriodQueue.getPlayingPeriod().info.durationUs)
        .isEqualTo(FIRST_AD_START_TIME_US - 2000);
  }

  @Test
  public void
      updateQueuedPeriods_withDurationChangeInPlayingContentAfterReadingPositionInServerSideInsertedAd_handlesChangeAndRemovesPeriodsAfterChangedPeriod() {
    adPlaybackState =
        new AdPlaybackState(/* adsId= */ new Object(), /* adGroupTimes... */ FIRST_AD_START_TIME_US)
            .withIsServerSideInserted(/* adGroupIndex= */ 0, /* isServerSideInserted= */ true);
    SinglePeriodAdTimeline adTimeline =
        new SinglePeriodAdTimeline(CONTENT_TIMELINE, adPlaybackState);
    setupTimeline(adTimeline);
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    enqueueNext(); // Content before ad.
    enqueueNext(); // Ad.
    enqueueNext(); // Content after ad.

    // Change position of first ad (= change duration of playing content before first ad).
    adPlaybackState =
        new AdPlaybackState(
                /* adsId= */ new Object(), /* adGroupTimesUs...= */ FIRST_AD_START_TIME_US - 2000)
            .withIsServerSideInserted(/* adGroupIndex= */ 0, /* isServerSideInserted= */ true);
    updateTimeline();
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    long maxRendererReadPositionUs =
        MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US + FIRST_AD_START_TIME_US - 1000;
    boolean changeHandled =
        mediaPeriodQueue.updateQueuedPeriods(
            playbackInfo.timeline,
            /* rendererPositionUs= */ MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US,
            maxRendererReadPositionUs);

    assertThat(changeHandled).isTrue();
    assertThat(getQueueLength()).isEqualTo(1);
    assertThat(mediaPeriodQueue.getPlayingPeriod().info.endPositionUs)
        .isEqualTo(FIRST_AD_START_TIME_US - 2000);
    assertThat(mediaPeriodQueue.getPlayingPeriod().info.durationUs)
        .isEqualTo(FIRST_AD_START_TIME_US - 2000);
  }

  @Test
  public void
      updateQueuedPeriods_withDurationChangeAfterReadingPeriod_handlesChangeAndRemovesPeriodsAfterChangedPeriod() {
    setupAdTimeline(/* adGroupTimesUs...= */ FIRST_AD_START_TIME_US, SECOND_AD_START_TIME_US);
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    setAdGroupLoaded(/* adGroupIndex= */ 1);
    enqueueNext(); // Content before first ad.
    enqueueNext(); // First ad.
    enqueueNext(); // Content between ads.
    enqueueNext(); // Second ad.

    // Change position of second ad (= change duration of content between ads).
    updateAdPlaybackStateAndTimeline(
        /* adGroupTimesUs...= */ FIRST_AD_START_TIME_US, SECOND_AD_START_TIME_US - 1000);
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    setAdGroupLoaded(/* adGroupIndex= */ 1);
    boolean changeHandled =
        mediaPeriodQueue.updateQueuedPeriods(
            playbackInfo.timeline,
            /* rendererPositionUs= */ MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US,
            /* maxRendererReadPositionUs= */ MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US);

    assertThat(changeHandled).isTrue();
    assertThat(getQueueLength()).isEqualTo(3);
  }

  @Test
  public void
      updateQueuedPeriods_withDurationChangeBeforeReadingPeriod_doesntHandleChangeAndRemovesPeriodsAfterChangedPeriod() {
    setupAdTimeline(/* adGroupTimesUs...= */ FIRST_AD_START_TIME_US, SECOND_AD_START_TIME_US);
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    setAdGroupLoaded(/* adGroupIndex= */ 1);
    enqueueNext(); // Content before first ad.
    enqueueNext(); // First ad.
    enqueueNext(); // Content between ads.
    enqueueNext(); // Second ad.
    advanceReading(); // Reading first ad.
    advanceReading(); // Reading content between ads.
    advanceReading(); // Reading second ad.

    // Change position of second ad (= change duration of content between ads).
    updateAdPlaybackStateAndTimeline(
        /* adGroupTimesUs...= */ FIRST_AD_START_TIME_US, SECOND_AD_START_TIME_US - 1000);
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    setAdGroupLoaded(/* adGroupIndex= */ 1);
    long maxRendererReadPositionUs =
        MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US + FIRST_AD_START_TIME_US;
    boolean changeHandled =
        mediaPeriodQueue.updateQueuedPeriods(
            playbackInfo.timeline,
            /* rendererPositionUs= */ MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US,
            maxRendererReadPositionUs);

    assertThat(changeHandled).isFalse();
    assertThat(getQueueLength()).isEqualTo(3);
  }

  @Test
  public void
      updateQueuedPeriods_withDurationChangeInReadingPeriodAfterReadingPosition_handlesChangeAndRemovesPeriodsAfterChangedPeriod() {
    setupAdTimeline(/* adGroupTimesUs...= */ FIRST_AD_START_TIME_US, SECOND_AD_START_TIME_US);
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    setAdGroupLoaded(/* adGroupIndex= */ 1);
    enqueueNext(); // Content before first ad.
    enqueueNext(); // First ad.
    enqueueNext(); // Content between ads.
    enqueueNext(); // Second ad.
    advanceReading(); // Reading first ad.
    advanceReading(); // Reading content between ads.

    // Change position of second ad (= change duration of content between ads).
    updateAdPlaybackStateAndTimeline(
        /* adGroupTimesUs...= */ FIRST_AD_START_TIME_US, SECOND_AD_START_TIME_US - 1000);
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    setAdGroupLoaded(/* adGroupIndex= */ 1);
    long readingPositionAtStartOfContentBetweenAds =
        MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US
            + FIRST_AD_START_TIME_US
            + AD_DURATION_US;
    boolean changeHandled =
        mediaPeriodQueue.updateQueuedPeriods(
            playbackInfo.timeline,
            /* rendererPositionUs= */ MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US,
            /* maxRendererReadPositionUs= */ readingPositionAtStartOfContentBetweenAds);

    assertThat(changeHandled).isTrue();
    assertThat(getQueueLength()).isEqualTo(3);
  }

  @Test
  public void
      updateQueuedPeriods_withDurationChangeInReadingPeriodBeforeReadingPosition_doesntHandleChangeAndRemovesPeriodsAfterChangedPeriod() {
    setupAdTimeline(/* adGroupTimesUs...= */ FIRST_AD_START_TIME_US, SECOND_AD_START_TIME_US);
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    setAdGroupLoaded(/* adGroupIndex= */ 1);
    enqueueNext(); // Content before first ad.
    enqueueNext(); // First ad.
    enqueueNext(); // Content between ads.
    enqueueNext(); // Second ad.
    advanceReading(); // Reading first ad.
    advanceReading(); // Reading content between ads.

    // Change position of second ad (= change duration of content between ads).
    updateAdPlaybackStateAndTimeline(
        /* adGroupTimesUs...= */ FIRST_AD_START_TIME_US, SECOND_AD_START_TIME_US - 1000);
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    setAdGroupLoaded(/* adGroupIndex= */ 1);
    long readingPositionAtEndOfContentBetweenAds =
        MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US
            + SECOND_AD_START_TIME_US
            + AD_DURATION_US;
    boolean changeHandled =
        mediaPeriodQueue.updateQueuedPeriods(
            playbackInfo.timeline,
            /* rendererPositionUs= */ MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US,
            /* maxRendererReadPositionUs= */ readingPositionAtEndOfContentBetweenAds);

    assertThat(changeHandled).isFalse();
    assertThat(getQueueLength()).isEqualTo(3);
  }

  @Test
  public void
      updateQueuedPeriods_withDurationChangeInReadingPeriodReadToEnd_doesntHandleChangeAndRemovesPeriodsAfterChangedPeriod() {
    setupAdTimeline(/* adGroupTimesUs...= */ FIRST_AD_START_TIME_US, SECOND_AD_START_TIME_US);
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    setAdGroupLoaded(/* adGroupIndex= */ 1);
    enqueueNext(); // Content before first ad.
    enqueueNext(); // First ad.
    enqueueNext(); // Content between ads.
    enqueueNext(); // Second ad.
    advanceReading(); // Reading first ad.
    advanceReading(); // Reading content between ads.

    // Change position of second ad (= change duration of content between ads).
    updateAdPlaybackStateAndTimeline(
        /* adGroupTimesUs...= */ FIRST_AD_START_TIME_US, SECOND_AD_START_TIME_US - 1000);
    setAdGroupLoaded(/* adGroupIndex= */ 0);
    setAdGroupLoaded(/* adGroupIndex= */ 1);
    boolean changeHandled =
        mediaPeriodQueue.updateQueuedPeriods(
            playbackInfo.timeline,
            /* rendererPositionUs= */ MediaPeriodQueue.INITIAL_RENDERER_POSITION_OFFSET_US,
            /* maxRendererReadPositionUs= */ C.TIME_END_OF_SOURCE);

    assertThat(changeHandled).isFalse();
    assertThat(getQueueLength()).isEqualTo(3);
  }

  @Test
  public void
      resolveMediaPeriodIdForAdsAfterPeriodPositionChange_behindAdPositionInSinglePeriodTimeline_resolvesToAd() {
    long adPositionUs = DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US + 10_000;
    AdPlaybackState adPlaybackState = new AdPlaybackState("adsId", adPositionUs);
    adPlaybackState = adPlaybackState.withAdDurationsUs(/* adGroupIndex= */ 0, 5_000);
    Object windowUid = new Object();
    FakeTimeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ windowUid,
                /* isSeekable= */ true,
                /* isDynamic= */ false,
                TimelineWindowDefinition.DEFAULT_WINDOW_DURATION_US,
                adPlaybackState));

    MediaPeriodId mediaPeriodId =
        mediaPeriodQueue.resolveMediaPeriodIdForAdsAfterPeriodPositionChange(
            timeline, /* periodUid= */ new Pair<>(windowUid, 0), adPositionUs + 1);

    assertThat(mediaPeriodId.adGroupIndex).isEqualTo(0);
    assertThat(mediaPeriodId.adIndexInAdGroup).isEqualTo(0);
    assertThat(mediaPeriodId.nextAdGroupIndex).isEqualTo(-1);
    assertThat(mediaPeriodId.periodUid).isEqualTo(new Pair<>(windowUid, 0));
  }

  @Test
  public void
      resolveMediaPeriodIdForAdsAfterPeriodPositionChange_toAdPositionInSinglePeriodTimeline_resolvesToAd() {
    long adPositionUs = DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US + 10_000;
    AdPlaybackState adPlaybackState = new AdPlaybackState("adsId", adPositionUs);
    adPlaybackState = adPlaybackState.withAdDurationsUs(/* adGroupIndex= */ 0, 5_000);
    Object windowUid = new Object();
    FakeTimeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ windowUid,
                /* isSeekable= */ true,
                /* isDynamic= */ false,
                TimelineWindowDefinition.DEFAULT_WINDOW_DURATION_US,
                adPlaybackState));

    MediaPeriodId mediaPeriodId =
        mediaPeriodQueue.resolveMediaPeriodIdForAdsAfterPeriodPositionChange(
            timeline, /* periodUid= */ new Pair<>(windowUid, 0), adPositionUs);

    assertThat(mediaPeriodId.periodUid).isEqualTo(new Pair<>(windowUid, 0));
    assertThat(mediaPeriodId.adGroupIndex).isEqualTo(0);
    assertThat(mediaPeriodId.adIndexInAdGroup).isEqualTo(0);
    assertThat(mediaPeriodId.nextAdGroupIndex).isEqualTo(-1);
  }

  @Test
  public void
      resolveMediaPeriodIdForAdsAfterPeriodPositionChange_beforeAdPositionInSinglePeriodTimeline_seekNotAdjusted() {
    long adPositionUs = DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US + 10_000;
    AdPlaybackState adPlaybackState =
        new AdPlaybackState("adsId", adPositionUs).withAdDurationsUs(/* adGroupIndex= */ 0, 5_000);
    Object windowUid = new Object();
    FakeTimeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ windowUid,
                /* isSeekable= */ true,
                /* isDynamic= */ false,
                TimelineWindowDefinition.DEFAULT_WINDOW_DURATION_US,
                adPlaybackState));

    MediaPeriodId mediaPeriodId =
        mediaPeriodQueue.resolveMediaPeriodIdForAdsAfterPeriodPositionChange(
            timeline, new Pair<>(windowUid, 0), adPositionUs - 1);

    assertThat(mediaPeriodId.periodUid).isEqualTo(new Pair<>(windowUid, 0));
    assertThat(mediaPeriodId.adGroupIndex).isEqualTo(-1);
    assertThat(mediaPeriodId.adIndexInAdGroup).isEqualTo(-1);
    assertThat(mediaPeriodId.nextAdGroupIndex).isEqualTo(0);
  }

  @Test
  public void
      resolveMediaPeriodIdForAdsAfterPeriodPositionChange_behindAdInMultiPeriodTimeline_rollForward()
          throws InterruptedException {
    Object windowId = new Object();
    Timeline timeline =
        createMultiPeriodServerSideInsertedTimeline(
            windowId,
            /* numberOfPlayedAds= */ 0,
            /* isAdPeriodFlags...= */ true,
            false,
            true,
            true,
            true,
            false);

    MediaPeriodId mediaPeriodId =
        mediaPeriodQueue.resolveMediaPeriodIdForAdsAfterPeriodPositionChange(
            timeline, new Pair<>(windowId, 1), /* positionUs= */ 1);

    assertThat(mediaPeriodId.periodUid).isEqualTo(new Pair<>(windowId, 0));
    assertThat(mediaPeriodId.adGroupIndex).isEqualTo(0);
    assertThat(mediaPeriodId.adIndexInAdGroup).isEqualTo(0);
    assertThat(mediaPeriodId.nextAdGroupIndex).isEqualTo(-1);

    mediaPeriodId =
        mediaPeriodQueue.resolveMediaPeriodIdForAdsAfterPeriodPositionChange(
            timeline, new Pair<>(windowId, 5), /* positionUs= */ 0);

    assertThat(mediaPeriodId.periodUid).isEqualTo(new Pair<>(windowId, 2));
    assertThat(mediaPeriodId.adGroupIndex).isEqualTo(0);
    assertThat(mediaPeriodId.adIndexInAdGroup).isEqualTo(0);
    assertThat(mediaPeriodId.nextAdGroupIndex).isEqualTo(-1);
  }

  @Test
  public void
      resolveMediaPeriodIdForAdsAfterPeriodPositionChange_behindAdInMultiPeriodAllAdsPlayed_seekNotAdjusted()
          throws InterruptedException {
    Object windowId = new Object();
    Timeline timeline =
        createMultiPeriodServerSideInsertedTimeline(
            windowId,
            /* numberOfPlayedAds= */ 4,
            /* isAdPeriodFlags...= */ true,
            false,
            true,
            true,
            true,
            false);

    MediaPeriodId mediaPeriodId =
        mediaPeriodQueue.resolveMediaPeriodIdForAdsAfterPeriodPositionChange(
            timeline, new Pair<>(windowId, 1), /* positionUs= */ 11);

    assertThat(mediaPeriodId.adGroupIndex).isEqualTo(-1);
    assertThat(mediaPeriodId.adIndexInAdGroup).isEqualTo(-1);
    assertThat(mediaPeriodId.nextAdGroupIndex).isEqualTo(-1);
    assertThat(mediaPeriodId.periodUid).isEqualTo(new Pair<>(windowId, 1));

    mediaPeriodId =
        mediaPeriodQueue.resolveMediaPeriodIdForAdsAfterPeriodPositionChange(
            timeline, new Pair<>(windowId, 5), /* positionUs= */ 33);

    assertThat(mediaPeriodId.adGroupIndex).isEqualTo(-1);
    assertThat(mediaPeriodId.adIndexInAdGroup).isEqualTo(-1);
    assertThat(mediaPeriodId.nextAdGroupIndex).isEqualTo(-1);
    assertThat(mediaPeriodId.periodUid).isEqualTo(new Pair<>(windowId, 5));
  }

  @Test
  public void
      resolveMediaPeriodIdForAdsAfterPeriodPositionChange_behindAdInMultiPeriodFirstTwoAdsPlayed_rollForward()
          throws InterruptedException {
    Object windowId = new Object();
    Timeline timeline =
        createMultiPeriodServerSideInsertedTimeline(
            windowId,
            /* numberOfPlayedAds= */ 2,
            /* isAdPeriodFlags...= */ true,
            false,
            true,
            true,
            true,
            false);

    MediaPeriodId mediaPeriodId =
        mediaPeriodQueue.resolveMediaPeriodIdForAdsAfterPeriodPositionChange(
            timeline, new Pair<>(windowId, 5), /* positionUs= */ 33);

    assertThat(mediaPeriodId.adGroupIndex).isEqualTo(0);
    assertThat(mediaPeriodId.adIndexInAdGroup).isEqualTo(0);
    assertThat(mediaPeriodId.nextAdGroupIndex).isEqualTo(-1);
    assertThat(mediaPeriodId.periodUid).isEqualTo(new Pair<>(windowId, 3));
  }

  @Test
  public void
      resolveMediaPeriodIdForAdsAfterPeriodPositionChange_beforeAdInMultiPeriodTimeline_seekNotAdjusted()
          throws InterruptedException {
    Object windowId = new Object();
    Timeline timeline =
        createMultiPeriodServerSideInsertedTimeline(
            windowId, /* numberOfPlayedAds= */ 0, /* isAdPeriodFlags...= */ false, true);

    MediaPeriodId mediaPeriodId =
        mediaPeriodQueue.resolveMediaPeriodIdForAdsAfterPeriodPositionChange(
            timeline, new Pair<>(windowId, 0), /* positionUs= */ 33);

    assertThat(mediaPeriodId.adGroupIndex).isEqualTo(-1);
    assertThat(mediaPeriodId.adIndexInAdGroup).isEqualTo(-1);
    assertThat(mediaPeriodId.nextAdGroupIndex).isEqualTo(-1);
    assertThat(mediaPeriodId.periodUid).isEqualTo(new Pair<>(windowId, 0));
  }

  @Test
  public void
      resolveMediaPeriodIdForAdsAfterPeriodPositionChange_toUnplayedAdInMultiPeriodTimeline_resolvedAsAd()
          throws InterruptedException {
    Object windowId = new Object();
    Timeline timeline =
        createMultiPeriodServerSideInsertedTimeline(
            windowId, /* numberOfPlayedAds= */ 0, /* isAdPeriodFlags...= */ false, true, false);

    MediaPeriodId mediaPeriodId =
        mediaPeriodQueue.resolveMediaPeriodIdForAdsAfterPeriodPositionChange(
            timeline, new Pair<>(windowId, 1), /* positionUs= */ 0);

    assertThat(mediaPeriodId.adGroupIndex).isEqualTo(0);
    assertThat(mediaPeriodId.adIndexInAdGroup).isEqualTo(0);
    assertThat(mediaPeriodId.nextAdGroupIndex).isEqualTo(-1);
    assertThat(mediaPeriodId.periodUid).isEqualTo(new Pair<>(windowId, 1));
  }

  @Test
  public void
      resolveMediaPeriodIdForAdsAfterPeriodPositionChange_toPlayedAdInMultiPeriodTimeline_skipPlayedAd()
          throws InterruptedException {
    Object windowId = new Object();
    Timeline timeline =
        createMultiPeriodServerSideInsertedTimeline(
            windowId, /* numberOfPlayedAds= */ 1, /* isAdPeriodFlags...= */ false, true, false);

    MediaPeriodId mediaPeriodId =
        mediaPeriodQueue.resolveMediaPeriodIdForAdsAfterPeriodPositionChange(
            timeline, new Pair<>(windowId, 1), /* positionUs= */ 0);

    assertThat(mediaPeriodId.adGroupIndex).isEqualTo(-1);
    assertThat(mediaPeriodId.adIndexInAdGroup).isEqualTo(-1);
    assertThat(mediaPeriodId.nextAdGroupIndex).isEqualTo(-1);
    assertThat(mediaPeriodId.periodUid).isEqualTo(new Pair<>(windowId, 2));
  }

  @Test
  public void
      resolveMediaPeriodIdForAdsAfterPeriodPositionChange_toStartOfWindowPlayedAdPreroll_skipsPlayedPrerolls()
          throws InterruptedException {
    Object windowId = new Object();
    Timeline timeline =
        createMultiPeriodServerSideInsertedTimeline(
            windowId, /* numberOfPlayedAds= */ 2, /* isAdPeriodFlags...= */ true, true, false);
    MediaPeriodId mediaPeriodId =
        mediaPeriodQueue.resolveMediaPeriodIdForAdsAfterPeriodPositionChange(
            timeline, new Pair<>(windowId, 0), /* positionUs= */ 0);

    assertThat(mediaPeriodId.adGroupIndex).isEqualTo(-1);
    assertThat(mediaPeriodId.adIndexInAdGroup).isEqualTo(-1);
    assertThat(mediaPeriodId.nextAdGroupIndex).isEqualTo(-1);
    assertThat(mediaPeriodId.periodUid).isEqualTo(new Pair<>(windowId, 2));
  }

  @Test
  public void
      resolveMediaPeriodIdForAdsAfterPeriodPositionChange_toPlayedPostrolls_skipsAllButLastPostroll()
          throws InterruptedException {
    Object windowId = new Object();
    Timeline timeline =
        createMultiPeriodServerSideInsertedTimeline(
            windowId,
            /* numberOfPlayedAds= */ 4,
            /* isAdPeriodFlags...= */ false,
            true,
            true,
            true,
            true);

    MediaPeriodId mediaPeriodId =
        mediaPeriodQueue.resolveMediaPeriodIdForAdsAfterPeriodPositionChange(
            timeline, new Pair<>(windowId, 1), /* positionUs= */ 0);

    assertThat(mediaPeriodId.periodUid).isEqualTo(new Pair<>(windowId, 4));
    assertThat(mediaPeriodId.adGroupIndex).isEqualTo(-1);
    assertThat(mediaPeriodId.adIndexInAdGroup).isEqualTo(-1);
    assertThat(mediaPeriodId.nextAdGroupIndex).isEqualTo(-1);
  }

  @Test
  public void
      resolveMediaPeriodIdForAdsAfterPeriodPositionChange_consecutiveContentPeriods_rollForward()
          throws InterruptedException {
    Object windowId = new Object();
    Timeline timeline =
        createMultiPeriodServerSideInsertedTimeline(
            windowId,
            /* numberOfPlayedAds= */ 0,
            /* isAdPeriodFlags...= */ true,
            false,
            false,
            false);

    MediaPeriodId mediaPeriodId =
        mediaPeriodQueue.resolveMediaPeriodIdForAdsAfterPeriodPositionChange(
            timeline, new Pair<>(windowId, 3), /* positionUs= */ 10_000);

    assertThat(mediaPeriodId.periodUid).isEqualTo(new Pair<>(windowId, 0));
    assertThat(mediaPeriodId.adGroupIndex).isEqualTo(0);
    assertThat(mediaPeriodId.adIndexInAdGroup).isEqualTo(0);
    assertThat(mediaPeriodId.nextAdGroupIndex).isEqualTo(-1);
  }

  @Test
  public void
      resolveMediaPeriodIdForAdsAfterPeriodPositionChange_onlyConsecutiveContentPeriods_seekNotAdjusted()
          throws InterruptedException {
    Object windowId = new Object();
    Timeline timeline =
        createMultiPeriodServerSideInsertedTimeline(
            windowId,
            /* numberOfPlayedAds= */ 0,
            /* isAdPeriodFlags...= */ false,
            false,
            false,
            false);

    MediaPeriodId mediaPeriodId =
        mediaPeriodQueue.resolveMediaPeriodIdForAdsAfterPeriodPositionChange(
            timeline, new Pair<>(windowId, 3), /* positionUs= */ 10_000);

    assertThat(mediaPeriodId.periodUid).isEqualTo(new Pair<>(windowId, 3));
    assertThat(mediaPeriodId.adGroupIndex).isEqualTo(-1);
  }

  private void setupAdTimeline(long... adGroupTimesUs) {
    adPlaybackState =
        new AdPlaybackState(/* adsId= */ new Object(), adGroupTimesUs)
            .withContentDurationUs(CONTENT_DURATION_US);
    SinglePeriodAdTimeline adTimeline =
        new SinglePeriodAdTimeline(CONTENT_TIMELINE, adPlaybackState);
    setupTimeline(adTimeline);
  }

  private void setupTimeline(Timeline timeline) {
    fakeMediaSource = new FakeMediaSource(timeline);
    MediaSourceList.MediaSourceHolder mediaSourceHolder =
        new MediaSourceList.MediaSourceHolder(fakeMediaSource, /* useLazyPreparation= */ false);
    mediaSourceList.setMediaSources(
        ImmutableList.of(mediaSourceHolder), new FakeShuffleOrder(/* length= */ 1));
    mediaSourceHolder.mediaSource.prepareSource(
        mock(MediaSourceCaller.class), /* mediaTransferListener= */ null, PlayerId.UNSET);

    Timeline playlistTimeline = mediaSourceList.createTimeline();
    firstPeriodUid = playlistTimeline.getUidOfPeriod(/* periodIndex= */ 0);

    playbackInfo =
        new PlaybackInfo(
            playlistTimeline,
            mediaPeriodQueue.resolveMediaPeriodIdForAds(
                playlistTimeline, firstPeriodUid, /* positionUs= */ 0),
            /* requestedContentPositionUs= */ C.TIME_UNSET,
            /* discontinuityStartPositionUs= */ 0,
            Player.STATE_READY,
            /* playbackError= */ null,
            /* isLoading= */ false,
            /* trackGroups= */ null,
            /* trackSelectorResult= */ null,
            /* staticMetadata= */ ImmutableList.of(),
            /* loadingMediaPeriodId= */ null,
            /* playWhenReady= */ false,
            Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            /* playbackParameters= */ PlaybackParameters.DEFAULT,
            /* bufferedPositionUs= */ 0,
            /* totalBufferedDurationUs= */ 0,
            /* positionUs= */ 0,
            /* positionUpdateTimeMs= */ 0,
            /* sleepingForOffload= */ false);
  }

  private void advance() {
    enqueueNext();
    if (mediaPeriodQueue.getLoadingPeriod() != mediaPeriodQueue.getPlayingPeriod()) {
      advancePlaying();
    }
  }

  private void advancePlaying() {
    mediaPeriodQueue.advancePlayingPeriod();
  }

  private void advanceReading() {
    mediaPeriodQueue.advanceReadingPeriod();
  }

  private void enqueueNext() {
    mediaPeriodQueue.enqueueNextMediaPeriodHolder(getNextMediaPeriodInfo());
  }

  private void clear() {
    mediaPeriodQueue.clear();
    playbackInfo =
        playbackInfo.copyWithNewPosition(
            mediaPeriodQueue.resolveMediaPeriodIdForAds(
                mediaSourceList.createTimeline(), firstPeriodUid, /* positionUs= */ 0),
            /* positionUs= */ 0,
            /* requestedContentPositionUs= */ C.TIME_UNSET,
            /* discontinuityStartPositionUs= */ 0,
            /* totalBufferedDurationUs= */ 0,
            /* trackGroups= */ null,
            /* trackSelectorResult= */ null,
            /* staticMetadata= */ ImmutableList.of());
  }

  @Nullable
  private MediaPeriodInfo getNextMediaPeriodInfo() {
    return mediaPeriodQueue.getNextMediaPeriodInfo(/* rendererPositionUs= */ 0, playbackInfo);
  }

  private void setAdGroupLoaded(int adGroupIndex) {
    long[][] newDurations = new long[adPlaybackState.adGroupCount][];
    for (int i = 0; i < adPlaybackState.adGroupCount; i++) {
      newDurations[i] =
          i == adGroupIndex
              ? new long[] {AD_DURATION_US}
              : adPlaybackState.getAdGroup(i).durationsUs;
    }
    adPlaybackState =
        adPlaybackState
            .withAdCount(adGroupIndex, /* adCount= */ 1)
            .withAvailableAdMediaItem(adGroupIndex, /* adIndexInAdGroup= */ 0, AD_MEDIA_ITEM)
            .withAdDurationsUs(newDurations);
    updateTimeline();
  }

  private void setAdGroupPlayed(int adGroupIndex) {
    for (int i = 0; i < adPlaybackState.getAdGroup(adGroupIndex).count; i++) {
      adPlaybackState = adPlaybackState.withPlayedAd(adGroupIndex, /* adIndexInAdGroup= */ i);
    }
    updateTimeline();
  }

  private void setAdGroupFailedToLoad(int adGroupIndex) {
    adPlaybackState =
        adPlaybackState
            .withAdCount(adGroupIndex, /* adCount= */ 1)
            .withAdLoadError(adGroupIndex, /* adIndexInAdGroup= */ 0);
    updateTimeline();
  }

  private void updateAdPlaybackStateAndTimeline(long... adGroupTimesUs) {
    adPlaybackState =
        new AdPlaybackState(/* adsId= */ new Object(), adGroupTimesUs)
            .withContentDurationUs(CONTENT_DURATION_US);
    updateTimeline();
  }

  private void updateTimeline() {
    SinglePeriodAdTimeline adTimeline =
        new SinglePeriodAdTimeline(CONTENT_TIMELINE, adPlaybackState);
    fakeMediaSource.setNewSourceInfo(adTimeline);
    // Progress the looper so that the source info events have been executed.
    shadowOf(Looper.getMainLooper()).idle();
    playbackInfo = playbackInfo.copyWithTimeline(mediaSourceList.createTimeline());
  }

  private void assertGetNextMediaPeriodInfoReturnsContentMediaPeriod(
      Object periodUid,
      long startPositionUs,
      long requestedContentPositionUs,
      long endPositionUs,
      long durationUs,
      boolean isFollowedByTransitionToSameStream,
      boolean isLastInPeriod,
      boolean isLastInWindow,
      boolean isFinal,
      int nextAdGroupIndex) {
    assertThat(getNextMediaPeriodInfo())
        .isEqualTo(
            new MediaPeriodInfo(
                new MediaPeriodId(periodUid, /* windowSequenceNumber= */ 0, nextAdGroupIndex),
                startPositionUs,
                requestedContentPositionUs,
                endPositionUs,
                durationUs,
                isFollowedByTransitionToSameStream,
                isLastInPeriod,
                isLastInWindow,
                isFinal));
  }

  private void assertNextMediaPeriodInfoIsAd(
      Object periodUid,
      int adGroupIndex,
      long adDurationUs,
      long contentPositionUs,
      boolean isFollowedByTransitionToSameStream) {
    assertThat(getNextMediaPeriodInfo())
        .isEqualTo(
            new MediaPeriodInfo(
                new MediaPeriodId(
                    periodUid,
                    adGroupIndex,
                    /* adIndexInAdGroup= */ 0,
                    /* windowSequenceNumber= */ 0),
                /* startPositionUs= */ 0,
                /* requestedContentPositionUs= */ contentPositionUs,
                /* endPositionUs= */ C.TIME_UNSET,
                adDurationUs,
                isFollowedByTransitionToSameStream,
                /* isLastInTimelinePeriod= */ false,
                /* isLastInTimelineWindow= */ false,
                /* isFinal= */ false));
  }

  private int getQueueLength() {
    int length = 0;
    MediaPeriodHolder periodHolder = mediaPeriodQueue.getPlayingPeriod();
    while (periodHolder != null) {
      length++;
      periodHolder = periodHolder.getNext();
    }
    return length;
  }

  private static Timeline createMultiPeriodServerSideInsertedTimeline(
      Object windowId, int numberOfPlayedAds, boolean... isAdPeriodFlags)
      throws InterruptedException {
    FakeTimeline fakeContentTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                isAdPeriodFlags.length,
                windowId,
                /* isSeekable= */ true,
                /* isDynamic= */ false,
                /* isLive= */ false,
                /* isPlaceholder= */ false,
                /* durationUs= */ DEFAULT_WINDOW_DURATION_US,
                /* defaultPositionUs= */ 0,
                /* windowOffsetInFirstPeriodUs= */ DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US,
                /* adPlaybackStates= */ ImmutableList.of(AdPlaybackState.NONE),
                MediaItem.EMPTY));
    ImmutableMap<Object, AdPlaybackState> adPlaybackStates =
        FakeTimeline.createMultiPeriodAdTimeline(windowId, numberOfPlayedAds, isAdPeriodFlags)
            .getAdPlaybackStates(/* windowIndex= */ 0);
    ServerSideAdInsertionMediaSource serverSideAdInsertionMediaSource =
        new ServerSideAdInsertionMediaSource(
            new FakeMediaSource(fakeContentTimeline, VIDEO_FORMAT, AUDIO_FORMAT),
            contentTimeline -> false);
    serverSideAdInsertionMediaSource.setAdPlaybackStates(adPlaybackStates, fakeContentTimeline);
    AtomicReference<Timeline> serverSideAdInsertionTimelineRef = new AtomicReference<>();
    CountDownLatch countDownLatch = new CountDownLatch(/* count= */ 1);
    serverSideAdInsertionMediaSource.prepareSource(
        (source, serverSideInsertedAdTimeline) -> {
          serverSideAdInsertionTimelineRef.set(serverSideInsertedAdTimeline);
          countDownLatch.countDown();
        },
        /* mediaTransferListener= */ null,
        PlayerId.UNSET);
    if (!countDownLatch.await(/* timeout= */ 2, SECONDS)) {
      fail();
    }
    return serverSideAdInsertionTimelineRef.get();
  }
}
