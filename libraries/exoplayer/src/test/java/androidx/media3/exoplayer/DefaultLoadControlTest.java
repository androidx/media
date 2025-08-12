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
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.DefaultLoadControl.Builder;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.test.utils.FakeTimeline;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DefaultLoadControl}. */
@RunWith(AndroidJUnit4.class)
public class DefaultLoadControlTest {

  private static final float SPEED = 1f;
  private static final long MAX_BUFFER_US = msToUs(DefaultLoadControl.DEFAULT_MAX_BUFFER_MS);
  private static final long MAX_BUFFER_FOR_LOCAL_PLAYBACK_US =
      msToUs(DefaultLoadControl.DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS);
  private static final long MIN_BUFFER_US = MAX_BUFFER_US / 2;
  private static final int TARGET_BUFFER_BYTES = C.DEFAULT_BUFFER_SEGMENT_SIZE * 2;

  private Builder builder;
  private DefaultAllocator allocator;
  private DefaultLoadControl loadControl;
  private PlayerId playerId;
  private Timeline timeline;
  private MediaSource.MediaPeriodId mediaPeriodId;
  private Timeline timelineLocal;
  private MediaSource.MediaPeriodId mediaPeriodIdLocal;

  @Before
  public void setUp() throws Exception {
    builder = new Builder();
    allocator = new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
    playerId = new PlayerId(/* playerName= */ "");
    timeline =
        new SinglePeriodTimeline(
            /* durationUs= */ 10_000_000L,
            /* isSeekable= */ true,
            /* isDynamic= */ true,
            /* useLiveConfiguration= */ false,
            /* manifest= */ null,
            MediaItem.EMPTY);
    mediaPeriodId =
        new MediaSource.MediaPeriodId(
            timeline.getPeriod(/* periodIndex= */ 0, new Timeline.Period(), /* setIds= */ true)
                .uid);
    timelineLocal =
        new SinglePeriodTimeline(
            /* durationUs= */ 10_000_000L,
            /* isSeekable= */ true,
            /* isDynamic= */ true,
            /* useLiveConfiguration= */ false,
            /* manifest= */ null,
            MediaItem.fromUri("file://local.file"));
    mediaPeriodIdLocal =
        new MediaSource.MediaPeriodId(
            timelineLocal.getPeriod(/* periodIndex= */ 0, new Timeline.Period(), /* setIds= */ true)
                .uid);
  }

  @Test
  public void shouldContinueLoading_trueUntilMaxBufferExceeded() {
    build();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    /* bufferedDurationUs= */ 0L,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    MAX_BUFFER_US - 1,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    MAX_BUFFER_US,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
  }

  @Test
  public void shouldContinueLoading_forLocalPlayback_trueUntilMaxBufferExceeded() {
    build();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timelineLocal,
                    mediaPeriodIdLocal,
                    /* playbackPositionUs= */ 0L,
                    /* bufferedDurationUs= */ 0L,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timelineLocal,
                    mediaPeriodIdLocal,
                    /* playbackPositionUs= */ 0L,
                    MAX_BUFFER_FOR_LOCAL_PLAYBACK_US - 1,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timelineLocal,
                    mediaPeriodIdLocal,
                    /* playbackPositionUs= */ 0L,
                    MAX_BUFFER_FOR_LOCAL_PLAYBACK_US,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
  }

  @Test
  public void shouldContinueLoading_twoPlayers_loadingStatesAreSeparated() {
    builder.setBufferDurationsMs(
        /* minBufferMs= */ (int) Util.usToMs(MIN_BUFFER_US),
        /* maxBufferMs= */ (int) Util.usToMs(MAX_BUFFER_US),
        /* bufferForPlaybackMs= */ 0,
        /* bufferForPlaybackAfterRebufferMs= */ 0);
    build();
    // A second player uses the load control.
    PlayerId playerId2 = new PlayerId(/* playerName= */ "");
    Timeline timeline2 = new FakeTimeline();
    MediaSource.MediaPeriodId mediaPeriodId2 =
        new MediaSource.MediaPeriodId(
            timeline2.getPeriod(/* periodIndex= */ 0, new Timeline.Period(), /* setIds= */ true)
                .uid);
    loadControl.onPrepared(playerId2);
    // First player is fully buffered. Buffer starts depleting until it falls under min size.
    loadControl.shouldContinueLoading(
        new LoadControl.Parameters(
            playerId,
            timeline,
            mediaPeriodId,
            /* playbackPositionUs= */ 0L,
            MAX_BUFFER_US,
            SPEED,
            /* playWhenReady= */ false,
            /* rebuffering= */ false,
            /* targetLiveOffsetUs= */ C.TIME_UNSET,
            /* lastRebufferRealtimeMs= */ C.TIME_UNSET));
    // Second player fell below min size and starts loading until max size is reached.
    loadControl.shouldContinueLoading(
        new LoadControl.Parameters(
            playerId2,
            timeline2,
            mediaPeriodId2,
            /* playbackPositionUs= */ 0L,
            MIN_BUFFER_US - 1,
            SPEED,
            /* playWhenReady= */ false,
            /* rebuffering= */ false,
            /* targetLiveOffsetUs= */ C.TIME_UNSET,
            /* lastRebufferRealtimeMs= */ C.TIME_UNSET));

    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    MAX_BUFFER_US - 1,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId2,
                    timeline2,
                    mediaPeriodId2,
                    /* playbackPositionUs= */ 0L,
                    MIN_BUFFER_US,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    MIN_BUFFER_US,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId2,
                    timeline2,
                    mediaPeriodId2,
                    /* playbackPositionUs= */ 0L,
                    MAX_BUFFER_US - 1,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
  }

  @Test
  public void shouldContinueLoading_onceBufferingStopped_falseUntilBelowMinBuffer() {
    builder.setBufferDurationsMsForStreaming(
        /* minBufferMs= */ (int) Util.usToMs(MIN_BUFFER_US),
        /* maxBufferMs= */ (int) Util.usToMs(MAX_BUFFER_US),
        /* bufferForPlaybackMs= */ 0,
        /* bufferForPlaybackAfterRebufferMs= */ 0);
    build();

    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    MAX_BUFFER_US,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    MAX_BUFFER_US - 1,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    MIN_BUFFER_US,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    MIN_BUFFER_US - 1,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
  }

  @Test
  public void
      shouldContinueLoading_onceBufferingStoppedForLocalPlayback_falseUntilBelowMinBuffer() {
    builder.setBufferDurationsMsForLocalPlayback(
        /* minBufferMs= */ (int) Util.usToMs(MIN_BUFFER_US),
        /* maxBufferMs= */ (int) Util.usToMs(MAX_BUFFER_US),
        /* bufferForPlaybackMs= */ 0,
        /* bufferForPlaybackAfterRebufferMs= */ 0);
    build();

    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timelineLocal,
                    mediaPeriodIdLocal,
                    /* playbackPositionUs= */ 0L,
                    MAX_BUFFER_US,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timelineLocal,
                    mediaPeriodIdLocal,
                    /* playbackPositionUs= */ 0L,
                    MAX_BUFFER_US - 1,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timelineLocal,
                    mediaPeriodIdLocal,
                    /* playbackPositionUs= */ 0L,
                    MIN_BUFFER_US,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timelineLocal,
                    mediaPeriodIdLocal,
                    /* playbackPositionUs= */ 0L,
                    MIN_BUFFER_US - 1,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
  }

  @Test
  public void
      shouldContinueLoading_onceBufferingStoppedAndBufferAlmostEmpty_trueEvenIfMinBufferNotReached() {
    builder.setBufferDurationsMs(
        /* minBufferMs= */ 0,
        /* maxBufferMs= */ (int) Util.usToMs(MAX_BUFFER_US),
        /* bufferForPlaybackMs= */ 0,
        /* bufferForPlaybackAfterRebufferMs= */ 0);
    build();

    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    MAX_BUFFER_US,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    5 * C.MICROS_PER_SECOND,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    500L,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
  }

  @Test
  public void
      shouldContinueLoading_withTargetBufferBytesReachedAndPrioritizeTimeOverSize_trueUntilMinBufferReached() {
    builder.setPrioritizeTimeOverSizeThresholdsForStreaming(true);
    builder.setBufferDurationsMs(
        /* minBufferMs= */ (int) Util.usToMs(MIN_BUFFER_US),
        /* maxBufferMs= */ (int) Util.usToMs(MAX_BUFFER_US),
        /* bufferForPlaybackMs= */ 0,
        /* bufferForPlaybackAfterRebufferMs= */ 0);
    build();
    makeSureTargetBufferBytesReached();

    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    /* bufferedDurationUs= */ 0L,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    MIN_BUFFER_US - 1,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    MIN_BUFFER_US,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    MAX_BUFFER_US,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
  }

  @Test
  public void
      shouldContinueLoading_withTargetBufferBytesReachedAndNotPrioritizeTimeOverSize_returnsTrueAsSoonAsTargetBufferReached() {
    builder.setPrioritizeTimeOverSizeThresholdsForStreaming(false);
    build();

    // Put loadControl in buffering state.
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    /* bufferedDurationUs= */ 0L,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
    makeSureTargetBufferBytesReached();

    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    /* bufferedDurationUs= */ 0L,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    MIN_BUFFER_US - 1,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    MIN_BUFFER_US,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    MAX_BUFFER_US,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
  }

  @Test
  public void
      shouldContinueLoading_withTargetBufferBytesReachedAndNotPrioritizeTimeOverSizeForLocalPlayback_returnsTrueAsSoonAsTargetBufferReached() {
    builder.setPrioritizeTimeOverSizeThresholdsForLocalPlayback(false);
    build();

    // Put loadControl in buffering state.
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timelineLocal,
                    mediaPeriodIdLocal,
                    /* playbackPositionUs= */ 0L,
                    /* bufferedDurationUs= */ 0L,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
    makeSureTargetBufferBytesReached();

    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timelineLocal,
                    mediaPeriodIdLocal,
                    /* playbackPositionUs= */ 0L,
                    /* bufferedDurationUs= */ 0L,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timelineLocal,
                    mediaPeriodIdLocal,
                    /* playbackPositionUs= */ 0L,
                    MIN_BUFFER_US - 1,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timelineLocal,
                    mediaPeriodIdLocal,
                    /* playbackPositionUs= */ 0L,
                    MIN_BUFFER_US,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timelineLocal,
                    mediaPeriodIdLocal,
                    /* playbackPositionUs= */ 0L,
                    MAX_BUFFER_US,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
  }

  @Test
  public void shouldContinueLoading_withMinBufferReachedInFastPlayback_returnsTrue() {
    builder.setBufferDurationsMs(
        /* minBufferMs= */ (int) Util.usToMs(MIN_BUFFER_US),
        /* maxBufferMs= */ (int) Util.usToMs(MAX_BUFFER_US),
        /* bufferForPlaybackMs= */ 0,
        /* bufferForPlaybackAfterRebufferMs= */ 0);
    build();

    // At normal playback speed, we stop buffering when the buffer reaches the minimum.
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    MIN_BUFFER_US,
                    SPEED,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    // At double playback speed, we continue loading.
    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    MIN_BUFFER_US,
                    /* playbackSpeed= */ 2f,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
  }

  @Test
  public void shouldContinueLoading_withNoSelectedTracks_returnsTrue() {
    loadControl = builder.build();
    loadControl.onPrepared(playerId);
    loadControl.onTracksSelected(
        new LoadControl.Parameters(
            playerId,
            timeline,
            mediaPeriodId,
            /* playbackPositionUs= */ 0L,
            /* bufferedDurationUs= */ 0L,
            /* playbackSpeed= */ 1f,
            /* playWhenReady= */ false,
            /* rebuffering= */ false,
            /* targetLiveOffsetUs= */ C.TIME_UNSET,
            /* lastRebufferRealtimeMs= */ C.TIME_UNSET),
        TrackGroupArray.EMPTY,
        new ExoTrackSelection[0]);

    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    /* bufferedDurationUs= */ 0L,
                    /* playbackSpeed= */ 1f,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
  }

  @Test
  public void shouldContinueLoading_withMaxBufferReachedInFastPlayback_returnsFalse() {
    build();

    assertThat(
            loadControl.shouldContinueLoading(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0L,
                    MAX_BUFFER_US,
                    /* playbackSpeed= */ 100f,
                    /* playWhenReady= */ false,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
  }

  @Test
  public void shouldStartPlayback_returnsTrueOnceBufferForPlaybackReached() {
    builder.setBufferDurationsMsForStreaming(
        /* minBufferMs= */ (int) Util.usToMs(MIN_BUFFER_US),
        /* maxBufferMs= */ (int) Util.usToMs(MAX_BUFFER_US),
        /* bufferForPlaybackMs= */ 500,
        /* bufferForPlaybackAfterRebufferMs= */ 500);
    build();

    assertThat(
            loadControl.shouldStartPlayback(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0,
                    /* bufferedDurationUs= */ 499_999,
                    SPEED,
                    /* playWhenReady= */ true,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldStartPlayback(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0,
                    /* bufferedDurationUs= */ 500_000,
                    SPEED,
                    /* playWhenReady= */ true,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
  }

  @Test
  public void shouldStartPlayback_forLocalPlayback_returnsTrueOnceBufferForPlaybackReached() {
    builder.setBufferDurationsMsForLocalPlayback(
        /* minBufferMs= */ (int) Util.usToMs(MIN_BUFFER_US),
        /* maxBufferMs= */ (int) Util.usToMs(MAX_BUFFER_US),
        /* bufferForPlaybackMs= */ 500,
        /* bufferForPlaybackAfterRebufferMs= */ 500);
    build();

    assertThat(
            loadControl.shouldStartPlayback(
                new LoadControl.Parameters(
                    playerId,
                    timelineLocal,
                    mediaPeriodIdLocal,
                    /* playbackPositionUs= */ 0,
                    /* bufferedDurationUs= */ 499_999,
                    SPEED,
                    /* playWhenReady= */ true,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldStartPlayback(
                new LoadControl.Parameters(
                    playerId,
                    timelineLocal,
                    mediaPeriodIdLocal,
                    /* playbackPositionUs= */ 0,
                    /* bufferedDurationUs= */ 500_000,
                    SPEED,
                    /* playWhenReady= */ true,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
  }

  @Test
  public void
      shouldStartPlayback_withoutTargetLiveOffset_returnsTrueWhenBufferForPlaybackReached() {
    builder.setBufferDurationsMs(
        /* minBufferMs= */ 5_000,
        /* maxBufferMs= */ 20_000,
        /* bufferForPlaybackMs= */ 3_000,
        /* bufferForPlaybackAfterRebufferMs= */ 4_000);
    build();

    assertThat(
            loadControl.shouldStartPlayback(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0,
                    /* bufferedDurationUs= */ 2_999_999L,
                    SPEED,
                    /* playWhenReady= */ true,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldStartPlayback(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0,
                    /* bufferedDurationUs= */ 3_000_000L,
                    SPEED,
                    /* playWhenReady= */ true,
                    /* rebuffering= */ false,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
  }

  @Test
  public void shouldStartPlayback_withTargetLiveOffset_returnsTrueWhenHalfLiveOffsetReached() {
    builder.setBufferDurationsMs(
        /* minBufferMs= */ 5_000,
        /* maxBufferMs= */ 20_000,
        /* bufferForPlaybackMs= */ 3_000,
        /* bufferForPlaybackAfterRebufferMs= */ 4_000);
    build();

    assertThat(
            loadControl.shouldStartPlayback(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0,
                    /* bufferedDurationUs= */ 499_999L,
                    SPEED,
                    /* playWhenReady= */ true,
                    /* rebuffering= */ true,
                    /* targetLiveOffsetUs= */ 1_000_000L,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldStartPlayback(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0,
                    /* bufferedDurationUs= */ 500_000L,
                    SPEED,
                    /* playWhenReady= */ true,
                    /* rebuffering= */ true,
                    /* targetLiveOffsetUs= */ 1_000_000L,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
  }

  @Test
  public void
      shouldStartPlayback_afterRebufferWithoutTargetLiveOffset_returnsTrueWhenBufferForPlaybackAfterRebufferReached() {
    builder.setBufferDurationsMsForStreaming(
        /* minBufferMs= */ 5_000,
        /* maxBufferMs= */ 20_000,
        /* bufferForPlaybackMs= */ 3_000,
        /* bufferForPlaybackAfterRebufferMs= */ 4_000);
    build();

    assertThat(
            loadControl.shouldStartPlayback(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0,
                    /* bufferedDurationUs= */ 3_999_999L,
                    SPEED,
                    /* playWhenReady= */ true,
                    /* rebuffering= */ true,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldStartPlayback(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0,
                    /* bufferedDurationUs= */ 4_000_000L,
                    SPEED,
                    /* playWhenReady= */ true,
                    /* rebuffering= */ true,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
  }

  @Test
  public void
      shouldStartPlayback_afterRebufferForLocalPlayback_returnsTrueWhenBufferForPlaybackAfterRebufferReached() {
    builder.setBufferDurationsMsForLocalPlayback(
        /* minBufferMs= */ 5_000,
        /* maxBufferMs= */ 20_000,
        /* bufferForPlaybackMs= */ 3_000,
        /* bufferForPlaybackAfterRebufferMs= */ 4_000);
    build();

    assertThat(
            loadControl.shouldStartPlayback(
                new LoadControl.Parameters(
                    playerId,
                    timelineLocal,
                    mediaPeriodIdLocal,
                    /* playbackPositionUs= */ 0,
                    /* bufferedDurationUs= */ 3_999_999L,
                    SPEED,
                    /* playWhenReady= */ true,
                    /* rebuffering= */ true,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldStartPlayback(
                new LoadControl.Parameters(
                    playerId,
                    timelineLocal,
                    mediaPeriodIdLocal,
                    /* playbackPositionUs= */ 0,
                    /* bufferedDurationUs= */ 4_000_000L,
                    SPEED,
                    /* playWhenReady= */ true,
                    /* rebuffering= */ true,
                    /* targetLiveOffsetUs= */ C.TIME_UNSET,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
  }

  @Test
  public void
      shouldStartPlayback_afterRebuffer_withTargetLiveOffset_returnsTrueWhenHalfLiveOffsetReached() {
    builder.setBufferDurationsMs(
        /* minBufferMs= */ 5_000,
        /* maxBufferMs= */ 20_000,
        /* bufferForPlaybackMs= */ 3_000,
        /* bufferForPlaybackAfterRebufferMs= */ 4_000);
    build();

    assertThat(
            loadControl.shouldStartPlayback(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0,
                    /* bufferedDurationUs= */ 499_999L,
                    SPEED,
                    /* playWhenReady= */ true,
                    /* rebuffering= */ true,
                    /* targetLiveOffsetUs= */ 1_000_000L,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isFalse();
    assertThat(
            loadControl.shouldStartPlayback(
                new LoadControl.Parameters(
                    playerId,
                    timeline,
                    mediaPeriodId,
                    /* playbackPositionUs= */ 0,
                    /* bufferedDurationUs= */ 500_000L,
                    SPEED,
                    /* playWhenReady= */ true,
                    /* rebuffering= */ true,
                    /* targetLiveOffsetUs= */ 1_000_000L,
                    /* lastRebufferRealtimeMs= */ C.TIME_UNSET)))
        .isTrue();
  }

  @Test
  public void onPrepared_updatesTargetBufferBytes_updatesDefaultTargetBufferSize() {
    PlayerId playerId2 = new PlayerId(/* playerName= */ "");
    loadControl = builder.setAllocator(allocator).build();

    loadControl.onPrepared(playerId);
    loadControl.onPrepared(playerId2);

    assertThat(loadControl.calculateTotalTargetBufferBytes())
        .isEqualTo(2 * DefaultLoadControl.DEFAULT_MIN_BUFFER_SIZE);
  }

  @Test
  public void onTrackSelected_updatesTargetBufferBytes_updatesTargetBufferSizeUsingTrackType() {
    PlayerId playerId2 = new PlayerId(/* playerName= */ "");
    loadControl = builder.setAllocator(allocator).build();
    loadControl.onPrepared(playerId);
    loadControl.onPrepared(playerId2);
    Timeline timeline2 = new FakeTimeline();
    MediaSource.MediaPeriodId mediaPeriodId2 =
        new MediaSource.MediaPeriodId(
            timeline2.getPeriod(/* periodIndex= */ 0, new Timeline.Period(), /* setIds= */ true)
                .uid);
    TrackGroup videoTrackGroup =
        new TrackGroup(new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build());
    TrackGroupArray videoTrackGroupArray = new TrackGroupArray(videoTrackGroup);
    TrackGroup audioTrackGroup =
        new TrackGroup(new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build());
    TrackGroupArray audioTrackGroupArray = new TrackGroupArray(audioTrackGroup);

    loadControl.onTracksSelected(
        new LoadControl.Parameters(
            playerId,
            timeline,
            mediaPeriodId,
            /* playbackPositionUs= */ 0,
            /* bufferedDurationUs= */ 0,
            /* playbackSpeed= */ 1.0f,
            /* playWhenReady= */ false,
            /* rebuffering= */ false,
            /* targetLiveOffsetUs= */ C.TIME_UNSET,
            /* lastRebufferRealtimeMs= */ C.TIME_UNSET),
        videoTrackGroupArray,
        new ExoTrackSelection[] {new FixedTrackSelection(videoTrackGroup, /* track= */ 0)});
    loadControl.onTracksSelected(
        new LoadControl.Parameters(
            playerId2,
            timeline2,
            mediaPeriodId2,
            /* playbackPositionUs= */ 0,
            /* bufferedDurationUs= */ 0,
            /* playbackSpeed= */ 1.0f,
            /* playWhenReady= */ false,
            /* rebuffering= */ false,
            /* targetLiveOffsetUs= */ C.TIME_UNSET,
            /* lastRebufferRealtimeMs= */ C.TIME_UNSET),
        audioTrackGroupArray,
        new ExoTrackSelection[] {new FixedTrackSelection(audioTrackGroup, /* track= */ 0)});

    assertThat(loadControl.calculateTotalTargetBufferBytes())
        .isEqualTo((2000 * C.DEFAULT_BUFFER_SEGMENT_SIZE) + (200 * C.DEFAULT_BUFFER_SEGMENT_SIZE));
  }

  @Test
  public void onTrackSelected_forLocalVideoPlayback_updatesTargetBufferSize() {
    loadControl = builder.setAllocator(allocator).build();
    loadControl.onPrepared(playerId);
    TrackGroup videoTrackGroup =
        new TrackGroup(new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build());
    TrackGroupArray videoTrackGroupArray = new TrackGroupArray(videoTrackGroup);

    loadControl.onTracksSelected(
        new LoadControl.Parameters(
            playerId,
            timelineLocal,
            mediaPeriodIdLocal,
            /* playbackPositionUs= */ 0,
            /* bufferedDurationUs= */ 0,
            /* playbackSpeed= */ 1.0f,
            /* playWhenReady= */ false,
            /* rebuffering= */ false,
            /* targetLiveOffsetUs= */ C.TIME_UNSET,
            /* lastRebufferRealtimeMs= */ C.TIME_UNSET),
        videoTrackGroupArray,
        new ExoTrackSelection[] {new FixedTrackSelection(videoTrackGroup, /* track= */ 0)});

    assertThat(loadControl.calculateTotalTargetBufferBytes())
        .isEqualTo(DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE_FOR_LOCAL_PLAYBACK);
  }

  @Test
  public void onRelease_removesLoadingStateOfPlayer() {
    PlayerId playerId2 = new PlayerId(/* playerName= */ "");
    loadControl = builder.setAllocator(allocator).build();
    loadControl.onPrepared(playerId);
    loadControl.onPrepared(playerId2);
    assertThat(loadControl.calculateTotalTargetBufferBytes())
        .isEqualTo(2 * DefaultLoadControl.DEFAULT_MIN_BUFFER_SIZE);

    loadControl.onReleased(playerId);

    assertThat(loadControl.calculateTotalTargetBufferBytes())
        .isEqualTo(DefaultLoadControl.DEFAULT_MIN_BUFFER_SIZE);

    loadControl.onReleased(playerId2);

    assertThat(loadControl.calculateTotalTargetBufferBytes()).isEqualTo(0);
  }

  private void build() {
    builder.setAllocator(allocator).setTargetBufferBytes(TARGET_BUFFER_BYTES);
    loadControl = builder.build();
    loadControl.onPrepared(playerId);
    loadControl.onTracksSelected(
        new LoadControl.Parameters(
            playerId,
            timeline,
            mediaPeriodId,
            /* playbackPositionUs= */ 0,
            /* bufferedDurationUs= */ 0,
            /* playbackSpeed= */ 1.0f,
            /* playWhenReady= */ false,
            /* rebuffering= */ false,
            /* targetLiveOffsetUs= */ C.TIME_UNSET,
            C.TIME_UNSET),
        /* trackGroups= */ null,
        /* trackSelections= */ null);
  }

  private void makeSureTargetBufferBytesReached() {
    while (allocator.getTotalBytesAllocated() < TARGET_BUFFER_BYTES) {
      allocator.allocate();
    }
  }
}
