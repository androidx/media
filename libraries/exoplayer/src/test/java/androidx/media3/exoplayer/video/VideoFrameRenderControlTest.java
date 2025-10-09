/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.exoplayer.video;

import static androidx.media3.exoplayer.video.VideoSink.RELEASE_FIRST_FRAME_IMMEDIATELY;
import static androidx.media3.exoplayer.video.VideoSink.RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import androidx.media3.common.VideoSize;
import androidx.media3.test.utils.FakeClock;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mockito;

/** Tests for {@link VideoFrameRenderControl}. */
@RunWith(AndroidJUnit4.class)
public class VideoFrameRenderControlTest {

  private static final int VIDEO_WIDTH = 640;
  private static final int VIDEO_HEIGHT = 480;

  private Surface surface;

  @Before
  public void setUp() {
    surface = new Surface(new SurfaceTexture(/* texName= */ 0));
  }

  @After
  public void tearDown() {
    surface.release();
  }

  @Test
  public void releaseFirstFrame() throws Exception {
    VideoFrameRenderControl.FrameRenderer frameRenderer =
        mock(VideoFrameRenderControl.FrameRenderer.class);
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    VideoFrameReleaseEarlyTimeForecaster forecaster =
        mock(VideoFrameReleaseEarlyTimeForecaster.class);
    VideoFrameRenderControl videoFrameRenderControl =
        new VideoFrameRenderControl(frameRenderer, videoFrameReleaseControl, forecaster);

    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);
    videoFrameRenderControl.onVideoSizeChanged(
        /* width= */ VIDEO_WIDTH, /* height= */ VIDEO_HEIGHT);
    videoFrameRenderControl.onFrameAvailableForRendering(/* presentationTimeUs= */ 0);
    videoFrameRenderControl.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    InOrder inOrder = Mockito.inOrder(frameRenderer);
    inOrder
        .verify(frameRenderer)
        .onVideoSizeChanged(new VideoSize(/* width= */ VIDEO_WIDTH, /* height= */ VIDEO_HEIGHT));
    inOrder
        .verify(frameRenderer)
        .renderFrame(
            /* renderTimeNs= */ anyLong(),
            /* presentationTimeUs= */ eq(0L),
            /* isFirstFrame= */ eq(true));
    verify(forecaster).onVideoFrameProcessed(/* framePresentationTimeUs= */ 0, /* earlyUs= */ 0);
  }

  @Test
  public void releaseFirstAndSecondFrame() throws Exception {
    VideoFrameRenderControl.FrameRenderer frameRenderer =
        mock(VideoFrameRenderControl.FrameRenderer.class);
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    videoFrameReleaseControl.setClock(clock);
    VideoFrameReleaseEarlyTimeForecaster videoFrameReleaseEarlyTimeForecaster =
        new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 1f);
    VideoFrameRenderControl videoFrameRenderControl =
        new VideoFrameRenderControl(
            frameRenderer, videoFrameReleaseControl, videoFrameReleaseEarlyTimeForecaster);

    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);
    videoFrameReleaseControl.onStarted();
    videoFrameRenderControl.onVideoSizeChanged(
        /* width= */ VIDEO_WIDTH, /* height= */ VIDEO_HEIGHT);
    videoFrameRenderControl.onFrameAvailableForRendering(/* presentationTimeUs= */ 0);
    videoFrameRenderControl.onFrameAvailableForRendering(/* presentationTimeUs= */ 10_000);

    videoFrameRenderControl.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    InOrder inOrder = Mockito.inOrder(frameRenderer);
    inOrder
        .verify(frameRenderer)
        .onVideoSizeChanged(new VideoSize(/* width= */ VIDEO_WIDTH, /* height= */ VIDEO_HEIGHT));
    // First frame.
    inOrder
        .verify(frameRenderer)
        .renderFrame(
            /* renderTimeNs= */ anyLong(),
            /* presentationTimeUs= */ eq(0L),
            /* isFirstFrame= */ eq(true));
    inOrder.verifyNoMoreInteractions();

    // 5 seconds pass
    clock.advanceTime(/* timeDiffMs= */ 5);
    videoFrameRenderControl.render(/* positionUs= */ 5_000, /* elapsedRealtimeUs= */ 5_000);

    // Second frame
    inOrder
        .verify(frameRenderer)
        .renderFrame(
            /* renderTimeNs= */ anyLong(),
            /* presentationTimeUs= */ eq(10_000L),
            /* isFirstFrame= */ eq(false));
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void release_firstAndSecondFrames_forecasterReceivesUpdates() throws Exception {
    VideoFrameRenderControl.FrameRenderer frameRenderer =
        mock(VideoFrameRenderControl.FrameRenderer.class);
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    videoFrameReleaseControl.setClock(clock);
    VideoFrameReleaseEarlyTimeForecaster forecaster =
        mock(VideoFrameReleaseEarlyTimeForecaster.class);
    VideoFrameRenderControl videoFrameRenderControl =
        new VideoFrameRenderControl(frameRenderer, videoFrameReleaseControl, forecaster);

    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);
    videoFrameReleaseControl.onStarted();
    videoFrameRenderControl.onVideoSizeChanged(
        /* width= */ VIDEO_WIDTH, /* height= */ VIDEO_HEIGHT);
    videoFrameRenderControl.onFrameAvailableForRendering(/* presentationTimeUs= */ 0);
    videoFrameRenderControl.onFrameAvailableForRendering(/* presentationTimeUs= */ 10_000);

    videoFrameRenderControl.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    InOrder inOrder = Mockito.inOrder(forecaster);
    inOrder
        .verify(forecaster)
        .onVideoFrameProcessed(/* framePresentationTimeUs= */ eq(0L), /* earlyUs= */ eq(0L));
    inOrder.verifyNoMoreInteractions();

    // 5 seconds pass
    clock.advanceTime(/* timeDiffMs= */ 5);
    videoFrameRenderControl.render(/* positionUs= */ 5_000, /* elapsedRealtimeUs= */ 5_000);

    // Second frame
    inOrder
        .verify(forecaster)
        .onVideoFrameProcessed(
            /* framePresentationTimeUs= */ eq(10_000L), /* earlyUs= */ anyLong());
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void renderFrames_withStreamStartPositionChange_firstFrameAgain() throws Exception {
    VideoFrameRenderControl.FrameRenderer frameRenderer =
        mock(VideoFrameRenderControl.FrameRenderer.class);
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    videoFrameReleaseControl.setClock(clock);
    VideoFrameReleaseEarlyTimeForecaster videoFrameReleaseEarlyTimeForecaster =
        new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 1f);
    VideoFrameRenderControl videoFrameRenderControl =
        new VideoFrameRenderControl(
            frameRenderer, videoFrameReleaseControl, videoFrameReleaseEarlyTimeForecaster);

    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);
    videoFrameReleaseControl.onStarted();
    videoFrameRenderControl.onVideoSizeChanged(
        /* width= */ VIDEO_WIDTH, /* height= */ VIDEO_HEIGHT);
    videoFrameRenderControl.onStreamChanged(
        RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED, /* streamStartPositionUs= */ 10_000);
    videoFrameRenderControl.onFrameAvailableForRendering(/* presentationTimeUs= */ 0);
    videoFrameRenderControl.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    InOrder inOrder = Mockito.inOrder(frameRenderer);
    inOrder
        .verify(frameRenderer)
        .onVideoSizeChanged(new VideoSize(/* width= */ VIDEO_WIDTH, /* height= */ VIDEO_HEIGHT));
    // First frame has the first stream start position.
    inOrder.verify(frameRenderer).renderFrame(anyLong(), eq(0L), eq(true));
    inOrder.verifyNoMoreInteractions();

    // 10 milliseconds pass
    clock.advanceTime(/* timeDiffMs= */ 10);
    videoFrameRenderControl.onStreamChanged(
        RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED, /* streamStartPositionUs= */ 20_000);
    videoFrameRenderControl.onFrameAvailableForRendering(/* presentationTimeUs= */ 10_000);
    videoFrameRenderControl.render(/* positionUs= */ 10_000, /* elapsedRealtimeUs= */ 0);

    // Second frame has the second stream start position and it is also a first frame.
    inOrder
        .verify(frameRenderer)
        .renderFrame(
            /* renderTimeNs= */ anyLong(),
            /* presentationTimeUs= */ eq(10_000L),
            /* isFirstFrame= */ eq(true));
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void dropFrames() throws Exception {
    VideoFrameRenderControl.FrameRenderer frameRenderer =
        mock(VideoFrameRenderControl.FrameRenderer.class);
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl =
        createVideoFrameReleaseControl(
            new TestFrameTimingEvaluator(
                /* shouldForceReleaseFrames= */ false,
                /* shouldDropFrames= */ true,
                /* shouldIgnoreFrames= */ false));
    videoFrameReleaseControl.setClock(clock);
    VideoFrameReleaseEarlyTimeForecaster videoFrameReleaseEarlyTimeForecaster =
        new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 1f);
    VideoFrameRenderControl videoFrameRenderControl =
        new VideoFrameRenderControl(
            frameRenderer, videoFrameReleaseControl, videoFrameReleaseEarlyTimeForecaster);

    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);
    videoFrameReleaseControl.onStarted();
    videoFrameRenderControl.onVideoSizeChanged(
        /* width= */ VIDEO_WIDTH, /* height= */ VIDEO_HEIGHT);
    videoFrameRenderControl.onFrameAvailableForRendering(/* presentationTimeUs= */ 0);
    videoFrameRenderControl.onFrameAvailableForRendering(/* presentationTimeUs= */ 10_000);
    videoFrameRenderControl.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    InOrder inOrder = Mockito.inOrder(frameRenderer);
    inOrder
        .verify(frameRenderer)
        .onVideoSizeChanged(new VideoSize(/* width= */ VIDEO_WIDTH, /* height= */ VIDEO_HEIGHT));
    // First frame was rendered because the fist frame is force released.
    inOrder
        .verify(frameRenderer)
        .renderFrame(
            /* renderTimeNs= */ anyLong(),
            /* presentationTimeUs= */ eq(0L),
            /* isFirstFrame= */ eq(true));
    inOrder.verifyNoMoreInteractions();

    clock.advanceTime(/* timeDiffMs= */ 100);
    videoFrameRenderControl.render(/* positionUs= */ 100_000, /* elapsedRealtimeUs= */ 100_000);

    // Second frame was dropped.
    inOrder.verify(frameRenderer).dropFrame();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void flush_removesAvailableFramesForRendering_doesNotFlushOnVideoSizeChange()
      throws Exception {
    VideoFrameRenderControl.FrameRenderer frameRenderer =
        mock(VideoFrameRenderControl.FrameRenderer.class);
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    VideoFrameReleaseEarlyTimeForecaster videoFrameReleaseEarlyTimeForecaster =
        new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 1f);
    VideoFrameRenderControl videoFrameRenderControl =
        new VideoFrameRenderControl(
            frameRenderer, videoFrameReleaseControl, videoFrameReleaseEarlyTimeForecaster);

    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);
    videoFrameReleaseControl.onStarted();
    videoFrameRenderControl.onVideoSizeChanged(
        /* width= */ VIDEO_WIDTH, /* height= */ VIDEO_HEIGHT);
    videoFrameRenderControl.onFrameAvailableForRendering(/* presentationTimeUs= */ 0);
    videoFrameRenderControl.flush();
    videoFrameRenderControl.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    InOrder inOrder = Mockito.inOrder(frameRenderer);
    inOrder.verifyNoMoreInteractions();

    videoFrameRenderControl.onFrameAvailableForRendering(/* presentationTimeUs= */ 10_000);
    videoFrameRenderControl.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);

    // First frame was rendered with pending video size change.
    inOrder
        .verify(frameRenderer)
        .onVideoSizeChanged(new VideoSize(/* width= */ VIDEO_WIDTH, /* height= */ VIDEO_HEIGHT));
    inOrder
        .verify(frameRenderer)
        .renderFrame(
            /* renderTimeNs= */ anyLong(),
            /* presentationTimeUs= */ eq(10_000L),
            /* isFirstFrame= */ eq(true));
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void isEnded_endOfInputNotSignaled_returnsFalse() {
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    VideoFrameReleaseEarlyTimeForecaster videoFrameReleaseEarlyTimeForecaster =
        new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 1f);
    VideoFrameRenderControl videoFrameRenderControl =
        new VideoFrameRenderControl(
            mock(VideoFrameRenderControl.FrameRenderer.class),
            videoFrameReleaseControl,
            videoFrameReleaseEarlyTimeForecaster);

    assertThat(videoFrameRenderControl.isEnded()).isFalse();
  }

  @Test
  public void isEnded_endOfInputSignaled_returnsTrue() throws Exception {
    VideoFrameRenderControl.FrameRenderer frameRenderer =
        mock(VideoFrameRenderControl.FrameRenderer.class);
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    VideoFrameReleaseEarlyTimeForecaster videoFrameReleaseEarlyTimeForecaster =
        new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 1f);
    VideoFrameRenderControl videoFrameRenderControl =
        new VideoFrameRenderControl(
            frameRenderer, videoFrameReleaseControl, videoFrameReleaseEarlyTimeForecaster);

    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);
    videoFrameRenderControl.onVideoSizeChanged(
        /* width= */ VIDEO_WIDTH, /* height= */ VIDEO_HEIGHT);
    videoFrameRenderControl.onFrameAvailableForRendering(/* presentationTimeUs= */ 0);
    videoFrameRenderControl.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    videoFrameRenderControl.signalEndOfInput();

    assertThat(videoFrameRenderControl.isEnded()).isTrue();
  }

  @Test
  public void isEnded_afterFlush_returnsFalse() throws Exception {
    VideoFrameRenderControl.FrameRenderer frameRenderer =
        mock(VideoFrameRenderControl.FrameRenderer.class);
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    VideoFrameReleaseEarlyTimeForecaster videoFrameReleaseEarlyTimeForecaster =
        new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 1f);
    VideoFrameRenderControl videoFrameRenderControl =
        new VideoFrameRenderControl(
            frameRenderer, videoFrameReleaseControl, videoFrameReleaseEarlyTimeForecaster);

    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);
    videoFrameRenderControl.onVideoSizeChanged(
        /* width= */ VIDEO_WIDTH, /* height= */ VIDEO_HEIGHT);
    videoFrameRenderControl.onFrameAvailableForRendering(/* presentationTimeUs= */ 0);
    videoFrameRenderControl.render(/* positionUs= */ 0, /* elapsedRealtimeUs= */ 0);
    videoFrameRenderControl.flush();

    assertThat(videoFrameRenderControl.isEnded()).isFalse();
  }

  private VideoFrameReleaseControl createVideoFrameReleaseControl() {
    return createVideoFrameReleaseControl(
        new TestFrameTimingEvaluator(
            /* shouldForceReleaseFrames= */ false,
            /* shouldDropFrames= */ false,
            /* shouldIgnoreFrames= */ false));
  }

  private VideoFrameReleaseControl createVideoFrameReleaseControl(
      VideoFrameReleaseControl.FrameTimingEvaluator frameTimingEvaluator) {
    VideoFrameReleaseControl videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            ApplicationProvider.getApplicationContext(),
            frameTimingEvaluator,
            /* allowedJoiningTimeMs= */ 0);
    videoFrameReleaseControl.setOutputSurface(surface);
    return videoFrameReleaseControl;
  }

  private static class TestFrameTimingEvaluator
      implements VideoFrameReleaseControl.FrameTimingEvaluator {
    private final boolean shouldForceReleaseFrames;
    private final boolean shouldDropFrames;
    private final boolean shouldIgnoreFrames;

    public TestFrameTimingEvaluator(
        boolean shouldForceReleaseFrames, boolean shouldDropFrames, boolean shouldIgnoreFrames) {
      this.shouldForceReleaseFrames = shouldForceReleaseFrames;
      this.shouldDropFrames = shouldDropFrames;
      this.shouldIgnoreFrames = shouldIgnoreFrames;
    }

    @Override
    public boolean shouldForceReleaseFrame(long earlyUs, long elapsedSinceLastReleaseUs) {
      return shouldForceReleaseFrames;
    }

    @Override
    public boolean shouldDropFrame(long earlyUs, long elapsedRealtimeUs, boolean isLastFrame) {
      return shouldDropFrames;
    }

    @Override
    public boolean shouldIgnoreFrame(
        long earlyUs,
        long positionUs,
        long elapsedRealtimeUs,
        boolean isLastFrame,
        boolean treatDroppedBuffersAsSkipped) {
      return shouldIgnoreFrames;
    }
  }
}
