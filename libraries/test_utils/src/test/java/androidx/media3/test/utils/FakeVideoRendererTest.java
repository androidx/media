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

package androidx.media3.test.utils;

import static androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition.DEFAULT_WINDOW_DURATION_US;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.test.utils.FakeMediaPeriod.TrackDataFactory;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

/** Tests for {@link FakeVideoRenderer}. */
@RunWith(AndroidJUnit4.class)
public final class FakeVideoRendererTest {
  @Test
  public void videoFrameMetadataListener_skipsDecodeOnlySamples() throws Exception {
    Timeline timeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder().setWindowPositionInFirstPeriodUs(0).build());
    ExoPlayer player =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext()).build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    VideoFrameMetadataListener mockVideoFrameMetadataListener =
        mock(VideoFrameMetadataListener.class);
    player.setVideoFrameMetadataListener(mockVideoFrameMetadataListener);

    player.setMediaSource(
        new FakeMediaSource.Builder()
            .setTimeline(timeline)
            .setTrackDataFactory(
                TrackDataFactory.samplesWithRateDurationAndKeyframeInterval(
                    /* initialSampleTimeUs= */ 0,
                    /* sampleRate= */ 30,
                    /* durationUs= */ DEFAULT_WINDOW_DURATION_US,
                    /* keyFrameInterval= */ 60))
            .setFormats(ExoPlayerTestRunner.VIDEO_FORMAT)
            .build());
    player.prepare();
    player.play();

    advance(player).untilPosition(0, 100);
    player.seekTo(2500);
    advance(player).untilPosition(0, 2600);
    player.stop();
    player.release();
    surface.release();

    ArgumentCaptor<Long> presentationTimeUsCaptor = ArgumentCaptor.forClass(Long.class);
    verify(mockVideoFrameMetadataListener, atLeastOnce())
        .onVideoFrameAboutToBeRendered(presentationTimeUsCaptor.capture(), anyLong(), any(), any());
    assertThat(presentationTimeUsCaptor.getAllValues())
        .containsExactly(
            0L, 33_333L, 66_666L, 100_000L, 2_500_000L, 2_533_333L, 2_566_666L, 2_600_000L)
        .inOrder();
  }
}
