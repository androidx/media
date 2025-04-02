/*
 * Copyright 2025 The Android Open Source Project
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

import static androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition.DEFAULT_WINDOW_DURATION_US;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.Player.PositionInfo;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.test.utils.ExoPlayerTestRunner;
import androidx.media3.test.utils.FakeMediaPeriod.TrackDataFactory;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeRenderer;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

/** Tests for {@linkplain ExoPlayer#setScrubbingModeEnabled(boolean) scrubbing mode}. */
@RunWith(AndroidJUnit4.class)
public final class ExoPlayerScrubbingTest {

  @Test
  public void scrubbingMode_suppressesPlayback() throws Exception {
    Timeline timeline = new FakeTimeline();
    FakeRenderer renderer = new FakeRenderer(C.TRACK_TYPE_VIDEO);
    ExoPlayer player =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext())
            .setRenderers(renderer)
            .build();
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);

    player.setMediaSource(new FakeMediaSource(timeline, ExoPlayerTestRunner.VIDEO_FORMAT));
    player.prepare();
    player.play();

    advance(player).untilPosition(0, 2000);

    player.setScrubbingModeEnabled(true);
    verify(mockListener)
        .onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_SCRUBBING);

    player.setScrubbingModeEnabled(false);
    verify(mockListener)
        .onPlaybackSuppressionReasonChanged(Player.PLAYBACK_SUPPRESSION_REASON_NONE);

    player.release();
  }

  @Test
  public void scrubbingMode_pendingSeekIsNotPreempted() throws Exception {
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder().setWindowPositionInFirstPeriodUs(0).build());
    ExoPlayer player =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);

    player.setMediaSource(
        new FakeMediaSource(
            timeline,
            DrmSessionManager.DRM_UNSUPPORTED,
            TrackDataFactory.samplesWithRateDurationAndKeyframeInterval(
                /* initialSampleTimeUs= */ 0,
                /* sampleRate= */ 30,
                /* durationUs= */ DEFAULT_WINDOW_DURATION_US,
                /* keyFrameInterval= */ 60),
            ExoPlayerTestRunner.VIDEO_FORMAT));
    player.prepare();
    player.play();

    advance(player).untilPosition(0, 1000);

    VideoFrameMetadataListener mockVideoFrameMetadataListener =
        mock(VideoFrameMetadataListener.class);
    player.setVideoFrameMetadataListener(mockVideoFrameMetadataListener);
    player.setScrubbingModeEnabled(true);
    advance(player).untilPendingCommandsAreFullyHandled();
    player.seekTo(2500);
    player.seekTo(3000);
    player.seekTo(3500);
    // Allow the 2500 and 3500 seeks to complete (the 3000 seek should be dropped).
    advance(player).untilPendingCommandsAreFullyHandled();

    player.seekTo(4000);
    player.seekTo(4500);
    // Disabling scrubbing mode should immediately execute the last received seek (pre-empting a
    // previous one), so we expect the 4500 seek to be resolved and the 4000 seek to be dropped.
    player.setScrubbingModeEnabled(false);
    advance(player).untilPendingCommandsAreFullyHandled();
    player.clearVideoFrameMetadataListener(mockVideoFrameMetadataListener);

    advance(player).untilState(Player.STATE_ENDED);
    player.release();
    surface.release();

    ArgumentCaptor<Long> presentationTimeUsCaptor = ArgumentCaptor.forClass(Long.class);
    verify(mockVideoFrameMetadataListener, atLeastOnce())
        .onVideoFrameAboutToBeRendered(presentationTimeUsCaptor.capture(), anyLong(), any(), any());

    assertThat(presentationTimeUsCaptor.getAllValues())
        .containsExactly(2_500_000L, 3_500_000L, 4_500_000L)
        .inOrder();

    // Confirm that even though we dropped some intermediate seeks, every seek request still
    // resulted in a position discontinuity callback.
    ArgumentCaptor<PositionInfo> newPositionCaptor = ArgumentCaptor.forClass(PositionInfo.class);
    verify(mockListener, atLeastOnce())
        .onPositionDiscontinuity(
            /* oldPosition= */ any(),
            newPositionCaptor.capture(),
            eq(Player.DISCONTINUITY_REASON_SEEK));
    assertThat(newPositionCaptor.getAllValues().stream().map(p -> p.positionMs))
        .containsExactly(2500L, 3000L, 3500L, 4000L, 4500L)
        .inOrder();
  }
}
