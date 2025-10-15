/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.play;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link ExoPlayer} testing the logic to adjust the target live offset. */
@RunWith(AndroidJUnit4.class)
public final class ExoPlayerTargetLiveOffsetTest {

  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
  }

  @Test
  public void targetLiveOffsetInMedia_adjustsLiveOffsetToTargetOffset() throws Exception {
    long windowStartUnixTimeMs = 987_654_321_000L;
    long nowUnixTimeMs = windowStartUnixTimeMs + 20_000;
    ExoPlayer player =
        new TestExoPlayerBuilder(context)
            .setClock(
                new FakeClock(/* initialTimeMs= */ nowUnixTimeMs, /* isAutoAdvancing= */ true))
            .build();
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ true,
                /* isLive= */ true,
                /* isPlaceholder= */ false,
                /* durationUs= */ 1000 * C.MICROS_PER_SECOND,
                /* defaultPositionUs= */ 8 * C.MICROS_PER_SECOND,
                /* windowOffsetInFirstPeriodUs= */ Util.msToUs(windowStartUnixTimeMs),
                ImmutableList.of(AdPlaybackState.NONE),
                new MediaItem.Builder()
                    .setUri(Uri.EMPTY)
                    .setLiveConfiguration(
                        new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(9_000).build())
                    .build()));
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);
    player.pause();
    player.setMediaSource(new FakeMediaSource(timeline));
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    long liveOffsetAtStart = player.getCurrentLiveOffset();
    // Verify test setup (now = 20 seconds in live window, default start position = 8 seconds).
    assertThat(liveOffsetAtStart).isIn(Range.closed(11_900L, 12_100L));

    // Play until close to the end of the available live window.
    play(player).untilPositionAtLeast(999_000);
    long liveOffsetAtEnd = player.getCurrentLiveOffset();
    player.release();

    // Assert that player adjusted live offset to the media value.
    assertThat(liveOffsetAtEnd).isIn(Range.closed(8_900L, 9_100L));
    // Assert that none of these playback speed changes were reported.
    verify(mockListener, never()).onPlaybackParametersChanged(any());
  }

  @Test
  public void targetLiveOffsetInMedia_withInitialSeek_adjustsLiveOffsetToInitialSeek()
      throws Exception {
    long windowStartUnixTimeMs = 987_654_321_000L;
    long nowUnixTimeMs = windowStartUnixTimeMs + 20_000;
    ExoPlayer player =
        new TestExoPlayerBuilder(context)
            .setClock(
                new FakeClock(/* initialTimeMs= */ nowUnixTimeMs, /* isAutoAdvancing= */ true))
            .build();
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ true,
                /* isLive= */ true,
                /* isPlaceholder= */ false,
                /* durationUs= */ 1000 * C.MICROS_PER_SECOND,
                /* defaultPositionUs= */ 8 * C.MICROS_PER_SECOND,
                /* windowOffsetInFirstPeriodUs= */ Util.msToUs(windowStartUnixTimeMs),
                ImmutableList.of(AdPlaybackState.NONE),
                new MediaItem.Builder()
                    .setUri(Uri.EMPTY)
                    .setLiveConfiguration(
                        new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(9_000).build())
                    .build()));
    player.pause();

    player.seekTo(18_000);
    player.setMediaSource(new FakeMediaSource(timeline), /* resetPosition= */ false);
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    long liveOffsetAtStart = player.getCurrentLiveOffset();
    // Play until close to the end of the available live window.
    play(player).untilPositionAtLeast(999_000);
    long liveOffsetAtEnd = player.getCurrentLiveOffset();
    player.release();

    // Target should have been permanently adjusted to 2 seconds.
    // (initial now = 20 seconds in live window, initial seek to 18 seconds)
    assertThat(liveOffsetAtStart).isIn(Range.closed(1_900L, 2_100L));
    assertThat(liveOffsetAtEnd).isIn(Range.closed(1_900L, 2_100L));
  }

  @Test
  public void targetLiveOffsetInMedia_withUserSeek_adjustsLiveOffsetToSeek() throws Exception {
    long windowStartUnixTimeMs = 987_654_321_000L;
    long nowUnixTimeMs = windowStartUnixTimeMs + 20_000;
    ExoPlayer player =
        new TestExoPlayerBuilder(context)
            .setClock(
                new FakeClock(/* initialTimeMs= */ nowUnixTimeMs, /* isAutoAdvancing= */ true))
            .build();
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ true,
                /* isLive= */ true,
                /* isPlaceholder= */ false,
                /* durationUs= */ 1000 * C.MICROS_PER_SECOND,
                /* defaultPositionUs= */ 8 * C.MICROS_PER_SECOND,
                /* windowOffsetInFirstPeriodUs= */ Util.msToUs(windowStartUnixTimeMs),
                ImmutableList.of(AdPlaybackState.NONE),
                new MediaItem.Builder()
                    .setUri(Uri.EMPTY)
                    .setLiveConfiguration(
                        new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(9_000).build())
                    .build()));
    player.pause();
    player.setMediaSource(new FakeMediaSource(timeline));
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    long liveOffsetAtStart = player.getCurrentLiveOffset();
    // Verify test setup (now = 20 seconds in live window, default start position = 8 seconds).
    assertThat(liveOffsetAtStart).isIn(Range.closed(11_900L, 12_100L));

    // Seek to a live offset of 2 seconds.
    player.seekTo(18_000);
    // Play until close to the end of the available live window.
    play(player).untilPositionAtLeast(999_000);
    long liveOffsetAtEnd = player.getCurrentLiveOffset();
    player.release();

    // Assert the live offset adjustment was permanent.
    assertThat(liveOffsetAtEnd).isIn(Range.closed(1_900L, 2_100L));
  }

  @Test
  public void targetLiveOffsetInMedia_withUserSeekOutsideMaxLivOffset_adjustsLiveOffsetToSeek()
      throws Exception {
    long windowStartUnixTimeMs = 987_654_321_000L;
    long nowUnixTimeMs = windowStartUnixTimeMs + 20_000;
    ExoPlayer player =
        new TestExoPlayerBuilder(context)
            .setClock(
                new FakeClock(/* initialTimeMs= */ nowUnixTimeMs, /* isAutoAdvancing= */ true))
            .build();
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ true,
                /* isLive= */ true,
                /* isPlaceholder= */ false,
                /* durationUs= */ 1000 * C.MICROS_PER_SECOND,
                /* defaultPositionUs= */ 8 * C.MICROS_PER_SECOND,
                /* windowOffsetInFirstPeriodUs= */ Util.msToUs(windowStartUnixTimeMs),
                ImmutableList.of(AdPlaybackState.NONE),
                new MediaItem.Builder()
                    .setUri(Uri.EMPTY)
                    .setLiveConfiguration(
                        new MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(9_000)
                            .setMaxOffsetMs(10_000)
                            .build())
                    .build()));
    player.pause();
    player.setMediaSource(new FakeMediaSource(timeline));
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    long liveOffsetAtStart = player.getCurrentLiveOffset();
    // Verify test setup (now = 20 seconds in live window, default start position = 8 seconds).
    assertThat(liveOffsetAtStart).isIn(Range.closed(11_900L, 12_100L));

    // Seek to a live offset of 15 seconds (outside of declared max offset of 10 seconds).
    player.seekTo(5_000);
    // Play until close to the end of the available live window.
    play(player).untilPositionAtLeast(999_000);
    long liveOffsetAtEnd = player.getCurrentLiveOffset();
    player.release();

    // Assert the live offset adjustment was permanent.
    assertThat(liveOffsetAtEnd).isIn(Range.closed(14_100L, 15_900L));
  }

  @Test
  public void targetLiveOffsetInMedia_withTimelineUpdate_adjustsLiveOffsetToLatestTimeline()
      throws Exception {
    long windowStartUnixTimeMs = 987_654_321_000L;
    long nowUnixTimeMs = windowStartUnixTimeMs + 20_000;
    ExoPlayer player =
        new TestExoPlayerBuilder(context)
            .setClock(
                new FakeClock(/* initialTimeMs= */ nowUnixTimeMs, /* isAutoAdvancing= */ true))
            .build();
    Timeline initialTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ true,
                /* isLive= */ true,
                /* isPlaceholder= */ false,
                /* durationUs= */ 1000 * C.MICROS_PER_SECOND,
                /* defaultPositionUs= */ 8 * C.MICROS_PER_SECOND,
                /* windowOffsetInFirstPeriodUs= */ Util.msToUs(windowStartUnixTimeMs),
                ImmutableList.of(AdPlaybackState.NONE),
                new MediaItem.Builder()
                    .setUri(Uri.EMPTY)
                    .setLiveConfiguration(
                        new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(9_000).build())
                    .build()));
    Timeline updatedTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ true,
                /* isLive= */ true,
                /* isPlaceholder= */ false,
                /* durationUs= */ 1000 * C.MICROS_PER_SECOND,
                /* defaultPositionUs= */ 8 * C.MICROS_PER_SECOND,
                /* windowOffsetInFirstPeriodUs= */ Util.msToUs(windowStartUnixTimeMs + 50_000),
                ImmutableList.of(AdPlaybackState.NONE),
                new MediaItem.Builder()
                    .setUri(Uri.EMPTY)
                    .setLiveConfiguration(
                        new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(4_000).build())
                    .build()));
    FakeMediaSource fakeMediaSource = new FakeMediaSource(initialTimeline);
    player.pause();
    player.setMediaSource(fakeMediaSource);
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    long liveOffsetAtStart = player.getCurrentLiveOffset();
    // Verify test setup (now = 20 seconds in live window, default start position = 8 seconds).
    assertThat(liveOffsetAtStart).isIn(Range.closed(11_900L, 12_100L));

    // Update the timeline after playing for a while.
    player
        .createMessage((messageType, message) -> fakeMediaSource.setNewSourceInfo(updatedTimeline))
        .setPosition(55_000)
        .send();
    player.play();
    // Play until close to the end of the available live window.
    play(player).untilPositionAtLeast(999_000);
    long liveOffsetAtEnd = player.getCurrentLiveOffset();
    player.release();

    // Assert that adjustment uses target offset from the updated timeline.
    assertThat(liveOffsetAtEnd).isIn(Range.closed(3_900L, 4_100L));
  }

  @Test
  public void targetLiveOffsetInMedia_withSetPlaybackParameters_usesPlaybackParameterSpeed()
      throws Exception {
    long windowStartUnixTimeMs = 987_654_321_000L;
    long nowUnixTimeMs = windowStartUnixTimeMs + 20_000;
    ExoPlayer player =
        new TestExoPlayerBuilder(context)
            .setClock(
                new FakeClock(/* initialTimeMs= */ nowUnixTimeMs, /* isAutoAdvancing= */ true))
            .build();
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ true,
                /* isLive= */ true,
                /* isPlaceholder= */ false,
                /* durationUs= */ 1000 * C.MICROS_PER_SECOND,
                /* defaultPositionUs= */ 20 * C.MICROS_PER_SECOND,
                /* windowOffsetInFirstPeriodUs= */ Util.msToUs(windowStartUnixTimeMs),
                ImmutableList.of(AdPlaybackState.NONE),
                new MediaItem.Builder()
                    .setUri(Uri.EMPTY)
                    .setLiveConfiguration(
                        new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(9_000).build())
                    .build()));
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);
    player.pause();
    player.setMediaSource(new FakeMediaSource(timeline));
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    long liveOffsetAtStart = player.getCurrentLiveOffset();
    // Verify test setup (now = 20 seconds in live window, default start position = 20 seconds).
    assertThat(liveOffsetAtStart).isIn(Range.closed(-100L, 100L));

    player.setPlaybackParameters(new PlaybackParameters(/* speed= */ 2.0f));
    // Play until close to the end of the available live window.
    play(player).untilPositionAtLeast(999_000);
    long liveOffsetAtEnd = player.getCurrentLiveOffset();
    player.release();

    // Assert that the player didn't adjust the live offset to the media value (9 seconds) and
    // instead played the media with double speed (resulting in a negative live offset).
    assertThat(liveOffsetAtEnd).isLessThan(0);
    // Assert that user-set speed was reported
    verify(mockListener).onPlaybackParametersChanged(new PlaybackParameters(2.0f));
  }

  @Test
  public void
      targetLiveOffsetInMedia_afterAutomaticPeriodTransition_adjustsLiveOffsetToTargetOffset()
          throws Exception {
    long windowStartUnixTimeMs = 987_654_321_000L;
    long nowUnixTimeMs = windowStartUnixTimeMs + 10_000;
    ExoPlayer player =
        new TestExoPlayerBuilder(context)
            .setClock(
                new FakeClock(/* initialTimeMs= */ nowUnixTimeMs, /* isAutoAdvancing= */ true))
            .build();
    Timeline nonLiveTimeline = new FakeTimeline();
    Timeline liveTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ true,
                /* isLive= */ true,
                /* isPlaceholder= */ false,
                /* durationUs= */ 1000 * C.MICROS_PER_SECOND,
                /* defaultPositionUs= */ 8 * C.MICROS_PER_SECOND,
                /* windowOffsetInFirstPeriodUs= */ Util.msToUs(windowStartUnixTimeMs),
                ImmutableList.of(AdPlaybackState.NONE),
                new MediaItem.Builder()
                    .setUri(Uri.EMPTY)
                    .setLiveConfiguration(
                        new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(9_000).build())
                    .build()));
    player.pause();
    player.addMediaSource(new FakeMediaSource(nonLiveTimeline));
    player.addMediaSource(new FakeMediaSource(liveTimeline));
    player.prepare();
    advance(player).untilState(Player.STATE_READY);

    // Play until close to the end of the available live window.
    play(player).untilMediaItemIndex(1);
    play(player).untilPositionAtLeast(999_000);
    long liveOffsetAtEnd = player.getCurrentLiveOffset();
    player.release();

    // Assert that player adjusted live offset to the media value.
    assertThat(liveOffsetAtEnd).isIn(Range.closed(8_900L, 9_100L));
  }

  @Test
  public void
      targetLiveOffsetInMedia_afterSeekToDefaultPositionInOtherStream_adjustsLiveOffsetToMediaOffset()
          throws Exception {
    long windowStartUnixTimeMs = 987_654_321_000L;
    long nowUnixTimeMs = windowStartUnixTimeMs + 20_000;
    ExoPlayer player =
        new TestExoPlayerBuilder(context)
            .setClock(
                new FakeClock(/* initialTimeMs= */ nowUnixTimeMs, /* isAutoAdvancing= */ true))
            .build();
    Timeline liveTimeline1 =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ true,
                /* isLive= */ true,
                /* isPlaceholder= */ false,
                /* durationUs= */ 1000 * C.MICROS_PER_SECOND,
                /* defaultPositionUs= */ 8 * C.MICROS_PER_SECOND,
                /* windowOffsetInFirstPeriodUs= */ Util.msToUs(windowStartUnixTimeMs),
                ImmutableList.of(AdPlaybackState.NONE),
                new MediaItem.Builder()
                    .setUri(Uri.EMPTY)
                    .setLiveConfiguration(
                        new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(9_000).build())
                    .build()));
    Timeline liveTimeline2 =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ true,
                /* isLive= */ true,
                /* isPlaceholder= */ false,
                /* durationUs= */ 1000 * C.MICROS_PER_SECOND,
                /* defaultPositionUs= */ 8 * C.MICROS_PER_SECOND,
                /* windowOffsetInFirstPeriodUs= */ Util.msToUs(windowStartUnixTimeMs),
                ImmutableList.of(AdPlaybackState.NONE),
                new MediaItem.Builder()
                    .setUri(Uri.EMPTY)
                    .setLiveConfiguration(
                        new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(4_000).build())
                    .build()));
    player.pause();
    player.addMediaSource(new FakeMediaSource(liveTimeline1));
    player.addMediaSource(new FakeMediaSource(liveTimeline2));
    // Ensure we override the target live offset to a seek position in the first live stream.
    player.seekTo(10_000);
    player.prepare();
    advance(player).untilState(Player.STATE_READY);

    // Seek to default position in second stream.
    player.seekToNextMediaItem();
    // Play until close to the end of the available live window.
    play(player).untilPositionAtLeast(999_000);
    long liveOffsetAtEnd = player.getCurrentLiveOffset();
    player.release();

    // Assert that player adjusted live offset to the media value.
    assertThat(liveOffsetAtEnd).isIn(Range.closed(3_900L, 4_100L));
  }

  @Test
  public void
      targetLiveOffsetInMedia_afterSeekToSpecificPositionInOtherStream_adjustsLiveOffsetToSeekPosition()
          throws Exception {
    long windowStartUnixTimeMs = 987_654_321_000L;
    long nowUnixTimeMs = windowStartUnixTimeMs + 20_000;
    ExoPlayer player =
        new TestExoPlayerBuilder(context)
            .setClock(
                new FakeClock(/* initialTimeMs= */ nowUnixTimeMs, /* isAutoAdvancing= */ true))
            .build();
    Timeline liveTimeline1 =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ true,
                /* isLive= */ true,
                /* isPlaceholder= */ false,
                /* durationUs= */ 1000 * C.MICROS_PER_SECOND,
                /* defaultPositionUs= */ 8 * C.MICROS_PER_SECOND,
                /* windowOffsetInFirstPeriodUs= */ Util.msToUs(windowStartUnixTimeMs),
                ImmutableList.of(AdPlaybackState.NONE),
                new MediaItem.Builder()
                    .setUri(Uri.EMPTY)
                    .setLiveConfiguration(
                        new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(9_000).build())
                    .build()));
    Timeline liveTimeline2 =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ true,
                /* isLive= */ true,
                /* isPlaceholder= */ false,
                /* durationUs= */ 1000 * C.MICROS_PER_SECOND,
                /* defaultPositionUs= */ 8 * C.MICROS_PER_SECOND,
                /* windowOffsetInFirstPeriodUs= */ Util.msToUs(windowStartUnixTimeMs),
                ImmutableList.of(AdPlaybackState.NONE),
                new MediaItem.Builder()
                    .setUri(Uri.EMPTY)
                    .setLiveConfiguration(
                        new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(4_000).build())
                    .build()));
    player.pause();
    player.addMediaSource(new FakeMediaSource(liveTimeline1));
    player.addMediaSource(new FakeMediaSource(liveTimeline2));
    // Ensure we override the target live offset to a seek position in the first live stream.
    player.seekTo(10_000);
    player.prepare();
    advance(player).untilState(Player.STATE_READY);

    // Seek to specific position in second stream (at 2 seconds live offset).
    player.seekTo(/* mediaItemIndex= */ 1, /* positionMs= */ 18_000);
    // Play until close to the end of the available live window.
    play(player).untilPositionAtLeast(999_000);
    long liveOffsetAtEnd = player.getCurrentLiveOffset();
    player.release();

    // Assert that player adjusted live offset to the seek.
    assertThat(liveOffsetAtEnd).isIn(Range.closed(1_900L, 2_100L));
  }

  @Test
  public void targetLiveOffsetInMedia_unknownWindowStartTime_doesNotAdjustLiveOffset()
      throws Exception {
    FakeClock fakeClock =
        new FakeClock(/* initialTimeMs= */ 987_654_321L, /* isAutoAdvancing= */ true);
    ExoPlayer player = new TestExoPlayerBuilder(context).setClock(fakeClock).build();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.EMPTY)
            .setLiveConfiguration(
                new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(4_000).build())
            .build();
    Timeline liveTimeline =
        new SinglePeriodTimeline(
            /* presentationStartTimeMs= */ C.TIME_UNSET,
            /* windowStartTimeMs= */ C.TIME_UNSET,
            /* elapsedRealtimeEpochOffsetMs= */ C.TIME_UNSET,
            /* periodDurationUs= */ 1000 * C.MICROS_PER_SECOND,
            /* windowDurationUs= */ 1000 * C.MICROS_PER_SECOND,
            /* windowPositionInPeriodUs= */ 0,
            /* windowDefaultStartPositionUs= */ 0,
            /* isSeekable= */ true,
            /* isDynamic= */ true,
            /* suppressPositionProjection= */ false,
            /* manifest= */ null,
            mediaItem,
            mediaItem.liveConfiguration);
    player.pause();
    player.setMediaSource(new FakeMediaSource(liveTimeline));
    player.prepare();
    advance(player).untilState(Player.STATE_READY);

    long playbackStartTimeMs = fakeClock.elapsedRealtime();
    play(player).untilPosition(/* mediaItemIndex= */ 0, /* positionMs= */ 999_000);
    long playbackEndTimeMs = fakeClock.elapsedRealtime();
    player.release();

    // Assert that the time it took to play 999 seconds of media is 999 seconds (asserting that no
    // playback speed adjustment was used).
    assertThat(playbackEndTimeMs - playbackStartTimeMs).isEqualTo(999_000);
  }

  @Test
  public void noTargetLiveOffsetInMedia_doesNotAdjustLiveOffset() throws Exception {
    long windowStartUnixTimeMs = 987_654_321_000L;
    long nowUnixTimeMs = windowStartUnixTimeMs + 20_000;
    ExoPlayer player =
        new TestExoPlayerBuilder(context)
            .setClock(
                new FakeClock(/* initialTimeMs= */ nowUnixTimeMs, /* isAutoAdvancing= */ true))
            .build();
    Timeline liveTimelineWithoutTargetLiveOffset =
        new FakeTimeline(
            new TimelineWindowDefinition(
                /* periodCount= */ 1,
                /* id= */ 0,
                /* isSeekable= */ true,
                /* isDynamic= */ true,
                /* isLive= */ true,
                /* isPlaceholder= */ false,
                /* durationUs= */ 1000 * C.MICROS_PER_SECOND,
                /* defaultPositionUs= */ 8 * C.MICROS_PER_SECOND,
                /* windowOffsetInFirstPeriodUs= */ Util.msToUs(windowStartUnixTimeMs),
                ImmutableList.of(AdPlaybackState.NONE),
                new MediaItem.Builder().setUri(Uri.EMPTY).build()));
    player.pause();
    player.setMediaSource(new FakeMediaSource(liveTimelineWithoutTargetLiveOffset));
    player.prepare();
    advance(player).untilState(Player.STATE_READY);
    long liveOffsetAtStart = player.getCurrentLiveOffset();
    // Verify test setup (now = 20 seconds in live window, default start position = 8 seconds).
    assertThat(liveOffsetAtStart).isIn(Range.closed(11_900L, 12_100L));

    // Play until close to the end of the available live window.
    play(player).untilPositionAtLeast(999_000);
    long liveOffsetAtEnd = player.getCurrentLiveOffset();
    player.release();

    // Assert that live offset is still the same (i.e. unadjusted).
    assertThat(liveOffsetAtEnd).isIn(Range.closed(11_900L, 12_100L));
  }
}
