/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.video;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link VideoFrameReleaseEarlyTimeForecaster}. */
@RunWith(AndroidJUnit4.class)
public class VideoFrameReleaseEarlyTimeForecasterTest {

  @Test
  @SuppressWarnings("Range") // Deliberately testing invalid value
  public void predictEarlyUs_withNonPositiveSpeed_throws() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 0f));
  }

  @Test
  public void predictEarlyUsAtPresentationTimeUs_noProcessedFrame_returnsTimeUnset() {
    VideoFrameReleaseEarlyTimeForecaster forecaster =
        new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 1f);

    long predictedEarlyUs = forecaster.predictEarlyUs(0);

    assertThat(predictedEarlyUs).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void predictEarlyUs_withOneProcessedFrame_assumesInstantaneousProcessing() {
    VideoFrameReleaseEarlyTimeForecaster forecaster =
        new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 1f);

    forecaster.onVideoFrameProcessed(/* framePresentationTimeUs= */ 0, /* earlyUs= */ 0);
    long predictedEarlyUsAtTime100 = forecaster.predictEarlyUs(/* presentationTimeUs= */ 100);

    assertThat(predictedEarlyUsAtTime100).isEqualTo(100);
  }

  @Test
  public void
      predictEarlyUs_withOneProcessedFrameAndCustomPlaybackSpeed_assumesInstantaneousProcessing() {
    VideoFrameReleaseEarlyTimeForecaster forecaster =
        new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 2);

    forecaster.onVideoFrameProcessed(/* framePresentationTimeUs= */ 0, /* earlyUs= */ 0);
    long predictedEarlyUsAtTime100 = forecaster.predictEarlyUs(/* presentationTimeUs= */ 100);

    assertThat(predictedEarlyUsAtTime100).isEqualTo(50);
  }

  @Test
  public void predictEarlyUs_withTwoProcessedFrames_adjustsProcessingSpeedEstimate() {
    VideoFrameReleaseEarlyTimeForecaster forecaster =
        new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 1f);

    forecaster.onVideoFrameProcessed(/* framePresentationTimeUs= */ -100, /* earlyUs= */ 0);
    forecaster.onVideoFrameProcessed(/* framePresentationTimeUs= */ 0, /* earlyUs= */ 0);
    long predictedEarlyUsAtTime100 = forecaster.predictEarlyUs(/* presentationTimeUs= */ 100);

    // Slope 0 between the last two data points. After exponential smoothing with starting value 1
    // and ALPHA = 0.2, the derivative estimate is
    // 0.2 * 0 + 0.8 * 1 = 0.8
    assertThat(predictedEarlyUsAtTime100).isEqualTo(80);
  }

  @Test
  public void predictEarlyUs_withTwoProcessedFramesAndClamping_adjustsProcessingSpeedEstimate() {
    VideoFrameReleaseEarlyTimeForecaster forecaster =
        new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 1f);

    forecaster.onVideoFrameProcessed(/* framePresentationTimeUs= */ -100, /* earlyUs= */ 100);
    forecaster.onVideoFrameProcessed(/* framePresentationTimeUs= */ 0, /* earlyUs= */ 0);
    long predictedEarlyUsAtTime100 = forecaster.predictEarlyUs(/* presentationTimeUs= */ 100);

    // Slope -1 between the last two data points will be clamped to 0.
    // After exponential smoothing with starting value 1 and ALPHA = 0.2, the derivative estimate is
    // 0.2 * 0 + 0.8 * 1 = 0.8
    assertThat(predictedEarlyUsAtTime100).isEqualTo(80);
  }

  @Test
  public void predictEarlyUs_withThreeProcessedFrames_adjustsProcessingSpeedEstimate() {
    VideoFrameReleaseEarlyTimeForecaster forecaster =
        new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 1f);

    forecaster.onVideoFrameProcessed(/* framePresentationTimeUs= */ -200, /* earlyUs= */ -50);
    forecaster.onVideoFrameProcessed(/* framePresentationTimeUs= */ -100, /* earlyUs= */ 0);
    forecaster.onVideoFrameProcessed(/* framePresentationTimeUs= */ 0, /* earlyUs= */ 0);
    long predictedEarlyUsAtTime100 = forecaster.predictEarlyUs(/* presentationTimeUs= */ 100);

    // Slope 0.5 and then 0. After two iterations of exponential smoothing with
    // starting value 1 and ALPHA = 0.2, the derivative estimate is
    // 0.2 * 0.5 + 0.8 * 1 = 0.9
    // 0.2 * 0 + 0.8 * 0.9 = 0.72
    assertThat(predictedEarlyUsAtTime100).isEqualTo(72);
  }

  @Test
  public void reset_afterFrameProcessed_resetsState() {
    VideoFrameReleaseEarlyTimeForecaster forecaster =
        new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 1f);

    forecaster.onVideoFrameProcessed(/* framePresentationTimeUs= */ 0, /* earlyUs= */ 0);
    forecaster.reset();
    long predictedEarlyUsAtTime100 = forecaster.predictEarlyUs(/* presentationTimeUs= */ 100);

    assertThat(predictedEarlyUsAtTime100).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void setPlaybackSpeed_afterFrameProcessed_resetsState() {
    VideoFrameReleaseEarlyTimeForecaster forecaster =
        new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 1f);

    forecaster.onVideoFrameProcessed(/* framePresentationTimeUs= */ 0, /* earlyUs= */ 0);
    forecaster.setPlaybackSpeed(/* playbackSpeed= */ 0.5f);
    long predictedEarlyUsAtTime100 = forecaster.predictEarlyUs(/* presentationTimeUs= */ 100);

    assertThat(predictedEarlyUsAtTime100).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void predictEarlyUs_withSetPlaybackSpeed_changesProcessingSpeedEstimate() {
    VideoFrameReleaseEarlyTimeForecaster forecaster =
        new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 1f);

    forecaster.setPlaybackSpeed(/* playbackSpeed= */ 0.5f);
    forecaster.onVideoFrameProcessed(/* framePresentationTimeUs= */ 0, /* earlyUs= */ 0);
    long predictedEarlyUsAtTime100 = forecaster.predictEarlyUs(/* presentationTimeUs= */ 100);

    assertThat(predictedEarlyUsAtTime100).isEqualTo(/* expected= */ 200);
  }
}
