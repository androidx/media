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
import static androidx.media3.exoplayer.video.VideoSink.RELEASE_FIRST_FRAME_WHEN_STARTED;
import static com.google.common.truth.Truth.assertThat;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.test.utils.FakeClock;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link VideoFrameReleaseControl}. */
@RunWith(AndroidJUnit4.class)
public class VideoFrameReleaseControlTest {

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
  public void isReady_onNewInstance_returnsFalse() {
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();

    assertThat(videoFrameReleaseControl.isReady(/* otherwiseReady= */ true)).isFalse();
    assertThat(videoFrameReleaseControl.isReady(/* otherwiseReady= */ false)).isFalse();
  }

  @Test
  public void isReady_afterReleasingFrame_returnsTrue() {
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();

    assertThat(videoFrameReleaseControl.onFrameReleasedIsFirstFrame()).isTrue();
    assertThat(videoFrameReleaseControl.isReady(/* otherwiseReady= */ true)).isTrue();
  }

  @Test
  public void isReady_withoutSurfaceFirstFrameNotReady_returnsFalse() throws Exception {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    videoFrameReleaseControl.setOutputSurface(/* outputSurface= */ null);

    // Process decode-only frame to ensure it doesn't make the release control ready.
    videoFrameReleaseControl.getFrameReleaseAction(
        /* presentationTimeUs= */ 0,
        /* positionUs= */ 0,
        /* elapsedRealtimeUs= */ 0,
        /* outputStreamStartPositionUs= */ 0,
        /* isDecodeOnlyFrame= */ true,
        /* isLastFrame= */ false,
        frameReleaseInfo);

    assertThat(videoFrameReleaseControl.isReady(/* otherwiseReady= */ true)).isFalse();
  }

  @Test
  public void isReady_withoutSurfaceFirstFrameReady_returnsFalse() throws Exception {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    videoFrameReleaseControl.setOutputSurface(/* outputSurface= */ null);

    // Process first frame.
    videoFrameReleaseControl.getFrameReleaseAction(
        /* presentationTimeUs= */ 0,
        /* positionUs= */ 0,
        /* elapsedRealtimeUs= */ 0,
        /* outputStreamStartPositionUs= */ 0,
        /* isDecodeOnlyFrame= */ false,
        /* isLastFrame= */ false,
        frameReleaseInfo);

    assertThat(videoFrameReleaseControl.isReady(/* otherwiseReady= */ true)).isTrue();
  }

  @Test
  public void isReady_withinJoiningDeadlineWhenRenderingNextFrameImmediately_returnsTrue() {
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl =
        createVideoFrameReleaseControl(/* allowedJoiningTimeMs= */ 100);
    videoFrameReleaseControl.setClock(clock);

    videoFrameReleaseControl.join(/* renderNextFrameImmediately= */ true);

    assertThat(videoFrameReleaseControl.isReady(/* otherwiseReady= */ false)).isTrue();
  }

  @Test
  public void isReady_withinJoiningDeadlineWhenNotRenderingNextFrameImmediately_returnsTrue() {
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl =
        createVideoFrameReleaseControl(/* allowedJoiningTimeMs= */ 100);
    videoFrameReleaseControl.setClock(clock);

    videoFrameReleaseControl.join(/* renderNextFrameImmediately= */ false);

    assertThat(videoFrameReleaseControl.isReady(/* otherwiseReady= */ false)).isTrue();
  }

  @Test
  public void isReady_joiningDeadlineExceededWhenRenderingNextFrameImmediately_returnsFalse() {
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl =
        createVideoFrameReleaseControl(/* allowedJoiningTimeMs= */ 100);
    videoFrameReleaseControl.setClock(clock);

    videoFrameReleaseControl.join(/* renderNextFrameImmediately= */ true);
    assertThat(videoFrameReleaseControl.isReady(/* otherwiseReady= */ false)).isTrue();

    clock.advanceTime(/* timeDiffMs= */ 101);

    assertThat(videoFrameReleaseControl.isReady(/* otherwiseReady= */ false)).isFalse();
  }

  @Test
  public void isReady_joiningDeadlineExceededWhenNotRenderingNextFrameImmediately_returnsFalse() {
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl =
        createVideoFrameReleaseControl(/* allowedJoiningTimeMs= */ 100);
    videoFrameReleaseControl.setClock(clock);

    videoFrameReleaseControl.join(/* renderNextFrameImmediately= */ false);
    assertThat(videoFrameReleaseControl.isReady(/* otherwiseReady= */ false)).isTrue();

    clock.advanceTime(/* timeDiffMs= */ 101);

    assertThat(videoFrameReleaseControl.isReady(/* otherwiseReady= */ false)).isFalse();
  }

  @Test
  public void onFrameReleasedIsFirstFrame_resetsAfterOnStreamChanged() {
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();

    videoFrameReleaseControl.onFrameReleasedIsFirstFrame();
    assertThat(videoFrameReleaseControl.onFrameReleasedIsFirstFrame()).isFalse();
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);

    assertThat(videoFrameReleaseControl.onFrameReleasedIsFirstFrame()).isTrue();
  }

  @Test
  public void onFrameReleasedIsFirstFrame_resetsAfterSetOutputSurface() {
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();

    assertThat(videoFrameReleaseControl.onFrameReleasedIsFirstFrame()).isTrue();
    videoFrameReleaseControl.setOutputSurface(/* outputSurface= */ null);

    assertThat(videoFrameReleaseControl.onFrameReleasedIsFirstFrame()).isTrue();
  }

  @Test
  public void isReady_afterReset_returnsFalse() {
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();

    videoFrameReleaseControl.onFrameReleasedIsFirstFrame();
    assertThat(videoFrameReleaseControl.isReady(/* otherwiseReady= */ true)).isTrue();
    videoFrameReleaseControl.reset();

    assertThat(videoFrameReleaseControl.isReady(/* otherwiseReady= */ true)).isFalse();
  }

  @Test
  public void getFrameReleaseAction_firstFrameAllowedBeforeStart_returnsReleaseImmediately()
      throws ExoPlaybackException {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);

    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 0,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY);
  }

  @Test
  public void getFrameReleaseAction_firstFrameNotAllowedBeforeStart_returnsTryAgainLater()
      throws ExoPlaybackException {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_WHEN_STARTED);

    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 0,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER);
  }

  @Test
  public void
      getFrameReleaseAction_firstFrameNotAllowedBeforeStartAndStarted_returnsReleaseImmediately()
          throws ExoPlaybackException {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    videoFrameReleaseControl.setClock(clock);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_WHEN_STARTED);

    videoFrameReleaseControl.onStarted();

    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 0,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY);
  }

  @Test
  public void getFrameReleaseAction_secondFrameWhileStartedAndPositionAdvancing_returnsScheduled()
      throws ExoPlaybackException {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    videoFrameReleaseControl.setClock(clock);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);
    videoFrameReleaseControl.onStarted();

    // First frame released.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 0,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY);
    videoFrameReleaseControl.onFrameReleasedIsFirstFrame();

    // Second frame, advancing position.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 1_000,
                /* positionUs= */ 500,
                /* elapsedRealtimeUs= */ 1,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_SCHEDULED);
  }

  @Test
  public void getFrameReleaseAction_secondFrameWhileNotStarted_returnsTryAgainLater()
      throws ExoPlaybackException {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    videoFrameReleaseControl.setClock(clock);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);

    // First frame released.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 0,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY);
    videoFrameReleaseControl.onFrameReleasedIsFirstFrame();

    // Second frame, advancing position.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 1_000,
                /* positionUs= */ 500,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER);
  }

  @Test
  public void getFrameReleaseAction_secondFrameWhilePositionNotAdvancing_returnsTryAgainLater()
      throws ExoPlaybackException {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    videoFrameReleaseControl.setClock(clock);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);
    videoFrameReleaseControl.onStarted();

    // First frame released.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 0,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY);
    videoFrameReleaseControl.onFrameReleasedIsFirstFrame();

    // Second frame, without advancing position.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 1_000,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 1,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER);
  }

  @Test
  public void
      getFrameReleaseAction_secondFrameWhileStatedButPositionAlreadyAdvanced_returnsTryAgainLaterUntilAdvancing()
          throws ExoPlaybackException {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    videoFrameReleaseControl.setClock(clock);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);

    // First frame released.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 0,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY);
    videoFrameReleaseControl.onFrameReleasedIsFirstFrame();

    // Second frame, position already advanced when started.
    videoFrameReleaseControl.onStarted();
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 1_000,
                /* positionUs= */ 500,
                /* elapsedRealtimeUs= */ 1,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER);

    // Advancing position while started.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 1_000,
                /* positionUs= */ 600,
                /* elapsedRealtimeUs= */ 2,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_SCHEDULED);
  }

  @Test
  public void getFrameReleaseAction_secondFrameEarly_returnsTryAgainLater()
      throws ExoPlaybackException {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    videoFrameReleaseControl.setClock(clock);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);

    videoFrameReleaseControl.onStarted();

    // First frame released.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 0,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY);
    videoFrameReleaseControl.onFrameReleasedIsFirstFrame();
    clock.advanceTime(/* timeDiffMs= */ 10);

    // Second frame is 90 ms too soon.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 100_000,
                /* positionUs= */ 10_000,
                /* elapsedRealtimeUs= */ 10_000,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER);
  }

  @Test
  public void
      getFrameReleaseAction_forceReleaseWhileStartedAndPositionAdvancing_returnsReleaseImmediately()
          throws ExoPlaybackException {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            ApplicationProvider.getApplicationContext(),
            new TestFrameTimingEvaluator(
                /* shouldForceRelease= */ true,
                /* shouldDropFrame= */ false,
                /* shouldIgnoreFrame= */ false),
            /* allowedJoiningTimeMs= */ 0);
    videoFrameReleaseControl.setOutputSurface(surface);
    videoFrameReleaseControl.setClock(clock);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);
    videoFrameReleaseControl.onStarted();

    // First frame released.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 0,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY);
    videoFrameReleaseControl.onFrameReleasedIsFirstFrame();

    // New frame, advancing position.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 1_000,
                /* positionUs= */ 500,
                /* elapsedRealtimeUs= */ 1,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY);
  }

  @Test
  public void getFrameReleaseAction_forceReleaseWhileNotStarted_returnsTryAgainLater()
      throws ExoPlaybackException {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            ApplicationProvider.getApplicationContext(),
            new TestFrameTimingEvaluator(
                /* shouldForceRelease= */ true,
                /* shouldDropFrame= */ false,
                /* shouldIgnoreFrame= */ false),
            /* allowedJoiningTimeMs= */ 0);
    videoFrameReleaseControl.setOutputSurface(surface);
    videoFrameReleaseControl.setClock(clock);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);

    // First frame released.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 0,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY);
    videoFrameReleaseControl.onFrameReleasedIsFirstFrame();

    // Second frame, position advancing.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 1_000,
                /* positionUs= */ 500,
                /* elapsedRealtimeUs= */ 1,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER);
  }

  @Test
  public void getFrameReleaseAction_forceReleaseWhilePositionNotAdvancing_returnsTryAgainLater()
      throws ExoPlaybackException {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            ApplicationProvider.getApplicationContext(),
            new TestFrameTimingEvaluator(
                /* shouldForceRelease= */ true,
                /* shouldDropFrame= */ false,
                /* shouldIgnoreFrame= */ false),
            /* allowedJoiningTimeMs= */ 0);
    videoFrameReleaseControl.setOutputSurface(surface);
    videoFrameReleaseControl.setClock(clock);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);
    videoFrameReleaseControl.onStarted();

    // First frame released.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 0,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY);
    videoFrameReleaseControl.onFrameReleasedIsFirstFrame();

    // New frame, but same position as before.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 1_000,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 1,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER);
  }

  @Test
  public void
      getFrameReleaseAction_withExperimentalDisableAdvancingTimestampCheckAndForceReleaseWhilePositionNotAdvancing_returnsReleaseImmediately()
          throws ExoPlaybackException {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            ApplicationProvider.getApplicationContext(),
            new TestFrameTimingEvaluator(
                /* shouldForceRelease= */ true,
                /* shouldDropFrame= */ false,
                /* shouldIgnoreFrame= */ false),
            /* allowedJoiningTimeMs= */ 0);
    videoFrameReleaseControl.setOutputSurface(surface);
    videoFrameReleaseControl.setClock(clock);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);
    videoFrameReleaseControl.onStarted();
    // First frame released.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 0,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY);
    videoFrameReleaseControl.onFrameReleasedIsFirstFrame();

    // New frame, but same position as before.
    videoFrameReleaseControl.experimentalDisableAdvancingTimestampChecks();
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 1_000,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 1,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY);
  }

  @Test
  public void
      getFrameReleaseAction_forceReleaseWhileStartedButPositionAlreadyAdvanced_returnsTryAgainLaterUntilAdvancing()
          throws ExoPlaybackException {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            ApplicationProvider.getApplicationContext(),
            new TestFrameTimingEvaluator(
                /* shouldForceRelease= */ true,
                /* shouldDropFrame= */ false,
                /* shouldIgnoreFrame= */ false),
            /* allowedJoiningTimeMs= */ 0);
    videoFrameReleaseControl.setOutputSurface(surface);
    videoFrameReleaseControl.setClock(clock);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);

    // First frame released.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 0,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY);
    videoFrameReleaseControl.onFrameReleasedIsFirstFrame();

    // New frame, position already advanced when started.
    videoFrameReleaseControl.onStarted();
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 1_000,
                /* positionUs= */ 500,
                /* elapsedRealtimeUs= */ 1,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER);

    // Advancing position while started.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 1_000,
                /* positionUs= */ 600,
                /* elapsedRealtimeUs= */ 1,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY);
  }

  @Test
  public void getFrameReleaseAction_frameLate_returnsDrop() throws ExoPlaybackException {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            ApplicationProvider.getApplicationContext(),
            new TestFrameTimingEvaluator(
                /* shouldForceRelease= */ false,
                /* shouldDropFrame= */ true,
                /* shouldIgnoreFrame= */ false),
            /* allowedJoiningTimeMs= */ 0);
    videoFrameReleaseControl.setOutputSurface(surface);
    videoFrameReleaseControl.setClock(clock);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);

    videoFrameReleaseControl.onStarted();

    // First frame released.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 0,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY);
    videoFrameReleaseControl.onFrameReleasedIsFirstFrame();
    clock.advanceTime(/* timeDiffMs= */ 40);

    // Second frame.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 10_000,
                /* positionUs= */ 10_000,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_DROP);
  }

  @Test
  public void
      getFrameReleaseAction_lateFrameWhileJoiningWhenNotRenderingFirstFrameImmediately_returnsSkip()
          throws ExoPlaybackException {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            ApplicationProvider.getApplicationContext(),
            new TestFrameTimingEvaluator(
                /* shouldForceRelease= */ false,
                /* shouldDropFrame= */ true,
                /* shouldIgnoreFrame= */ false),
            /* allowedJoiningTimeMs= */ 1234);
    videoFrameReleaseControl.setOutputSurface(surface);
    videoFrameReleaseControl.setClock(clock);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);
    videoFrameReleaseControl.onStarted();

    // Start joining.
    videoFrameReleaseControl.join(/* renderNextFrameImmediately= */ false);

    // First output is TRY_AGAIN_LATER because the time hasn't moved yet
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 5_000,
                /* positionUs= */ 10_000,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER);
    // Late frame should be marked as skipped
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 5_000,
                /* positionUs= */ 11_000,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_SKIP);
  }

  @Test
  public void
      getFrameReleaseAction_lateFrameWhileJoiningWhenRenderingFirstFrameImmediately_returnsDropAfterInitialImmediateRelease()
          throws ExoPlaybackException {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            ApplicationProvider.getApplicationContext(),
            new TestFrameTimingEvaluator(
                /* shouldForceRelease= */ false,
                /* shouldDropFrame= */ true,
                /* shouldIgnoreFrame= */ false),
            /* allowedJoiningTimeMs= */ 1234);
    videoFrameReleaseControl.setOutputSurface(surface);
    videoFrameReleaseControl.setClock(clock);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);
    videoFrameReleaseControl.onStarted();

    // Start joining.
    videoFrameReleaseControl.join(/* renderNextFrameImmediately= */ true);

    // First output is to force render the next frame.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 5_000,
                /* positionUs= */ 10_000,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY);
    videoFrameReleaseControl.onFrameReleasedIsFirstFrame();
    // Further late frames should be marked as dropped.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 6_000,
                /* positionUs= */ 11_000,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_DROP);
  }

  @Test
  public void getFrameReleaseAction_shouldIgnore() throws ExoPlaybackException {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            ApplicationProvider.getApplicationContext(),
            new TestFrameTimingEvaluator(
                /* shouldForceRelease= */ false,
                /* shouldDropFrame= */ false,
                /* shouldIgnoreFrame= */ true),
            /* allowedJoiningTimeMs= */ 0);
    videoFrameReleaseControl.setOutputSurface(surface);
    videoFrameReleaseControl.setClock(clock);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);

    videoFrameReleaseControl.onStarted();

    // First frame released.
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 0,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY);
    videoFrameReleaseControl.onFrameReleasedIsFirstFrame();
    clock.advanceTime(/* timeDiffMs= */ 1_000);

    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 1_000,
                /* positionUs= */ 1_000,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IGNORE);
  }

  @Test
  public void getFrameReleaseAction_decodeOnlyFrame_returnsSkip() throws Exception {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);

    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 0,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ true,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_SKIP);
  }

  @Test
  public void getFrameReleaseAction_decodeOnlyAndLastFrame_returnsReleaseImmediately()
      throws Exception {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);

    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 0,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ true,
                /* isLastFrame= */ true,
                frameReleaseInfo))
        .isEqualTo(RELEASE_FIRST_FRAME_IMMEDIATELY);
  }

  @Test
  public void getFrameReleaseAction_decodeOnlyFrameWithoutSurface_returnsSkip() throws Exception {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    videoFrameReleaseControl.setOutputSurface(/* outputSurface= */ null);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);

    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 0,
                /* positionUs= */ 0,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ true,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_SKIP);
  }

  @Test
  public void getFrameReleaseAction_withoutSurfaceOnTime_returnsTryAgainLater() throws Exception {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    videoFrameReleaseControl.setOutputSurface(/* outputSurface= */ null);
    videoFrameReleaseControl.setClock(clock);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);

    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 100_000,
                /* positionUs= */ 50_000,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER);
  }

  @Test
  public void getFrameReleaseAction_withoutSurfaceShouldIgnore_returnsIgnore() throws Exception {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            ApplicationProvider.getApplicationContext(),
            new TestFrameTimingEvaluator(
                /* shouldForceRelease= */ false,
                /* shouldDropFrame= */ false,
                /* shouldIgnoreFrame= */ true),
            /* allowedJoiningTimeMs= */ 0);
    videoFrameReleaseControl.setOutputSurface(/* outputSurface= */ null);
    videoFrameReleaseControl.setClock(clock);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);

    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 100_000,
                /* positionUs= */ 50_000,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_IGNORE);
  }

  @Test
  public void getFrameReleaseAction_withoutSurfaceFrameLateNotStarted_returnsTryAgainLater()
      throws Exception {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    videoFrameReleaseControl.setOutputSurface(/* outputSurface= */ null);
    videoFrameReleaseControl.setClock(clock);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);

    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 100_000,
                /* positionUs= */ 90_000,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER);
  }

  @Test
  public void getFrameReleaseAction_withoutSurfaceFrameLateAndStarted_returnsSkip()
      throws Exception {
    VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo =
        new VideoFrameReleaseControl.FrameReleaseInfo();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ false);
    VideoFrameReleaseControl videoFrameReleaseControl = createVideoFrameReleaseControl();
    videoFrameReleaseControl.setOutputSurface(/* outputSurface= */ null);
    videoFrameReleaseControl.setClock(clock);
    videoFrameReleaseControl.onStreamChanged(RELEASE_FIRST_FRAME_IMMEDIATELY);

    videoFrameReleaseControl.onStarted();
    assertThat(
            videoFrameReleaseControl.getFrameReleaseAction(
                /* presentationTimeUs= */ 100_000,
                /* positionUs= */ 90_000,
                /* elapsedRealtimeUs= */ 0,
                /* outputStreamStartPositionUs= */ 0,
                /* isDecodeOnlyFrame= */ false,
                /* isLastFrame= */ false,
                frameReleaseInfo))
        .isEqualTo(VideoFrameReleaseControl.FRAME_RELEASE_SKIP);
  }

  private VideoFrameReleaseControl createVideoFrameReleaseControl() {
    return createVideoFrameReleaseControl(/* allowedJoiningTimeMs= */ 0);
  }

  private VideoFrameReleaseControl createVideoFrameReleaseControl(long allowedJoiningTimeMs) {
    VideoFrameReleaseControl videoFrameReleaseControl =
        new VideoFrameReleaseControl(
            ApplicationProvider.getApplicationContext(),
            new TestFrameTimingEvaluator(),
            allowedJoiningTimeMs);
    videoFrameReleaseControl.setOutputSurface(surface);
    return videoFrameReleaseControl;
  }

  private static class TestFrameTimingEvaluator
      implements VideoFrameReleaseControl.FrameTimingEvaluator {

    private final boolean shouldForceRelease;
    private final boolean shouldDropFrame;
    private final boolean shouldIgnoreFrame;

    public TestFrameTimingEvaluator() {
      this(/* shouldForceRelease= */ false);
    }

    public TestFrameTimingEvaluator(boolean shouldForceRelease) {
      this(shouldForceRelease, /* shouldDropFrame= */ false, /* shouldIgnoreFrame= */ false);
    }

    public TestFrameTimingEvaluator(
        boolean shouldForceRelease, boolean shouldDropFrame, boolean shouldIgnoreFrame) {
      this.shouldForceRelease = shouldForceRelease;
      this.shouldDropFrame = shouldDropFrame;
      this.shouldIgnoreFrame = shouldIgnoreFrame;
    }

    @Override
    public boolean shouldForceReleaseFrame(long earlyUs, long elapsedSinceLastReleaseUs) {
      return shouldForceRelease;
    }

    @Override
    public boolean shouldDropFrame(long earlyUs, long elapsedRealtimeUs, boolean isLastFrame) {
      return shouldDropFrame;
    }

    @Override
    public boolean shouldIgnoreFrame(
        long earlyUs,
        long positionUs,
        long elapsedRealtimeUs,
        boolean isLastFrame,
        boolean treatDroppedBuffersAsSkipped) {
      return shouldIgnoreFrame;
    }
  }
}
