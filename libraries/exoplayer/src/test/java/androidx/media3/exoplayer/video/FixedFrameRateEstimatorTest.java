/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static androidx.media3.exoplayer.video.FixedFrameRateEstimator.CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC;
import static androidx.media3.exoplayer.video.FixedFrameRateEstimator.MAX_MATCHING_FRAME_DIFFERENCE_NS;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link FixedFrameRateEstimator}. */
@RunWith(AndroidJUnit4.class)
public final class FixedFrameRateEstimatorTest {

  @Test
  public void fixedFrameRate_withSingleOutlier_syncsAndResyncs() {
    long frameDurationNs = 33_333_333;
    FixedFrameRateEstimator estimator = new FixedFrameRateEstimator(frameRate -> {});

    // Initial frame.
    long framePresentationTimestampNs = 0;
    estimator.onNextFrame(framePresentationTimestampNs);

    assertThat(estimator.isSynced()).isFalse();
    assertThat(estimator.getFrameDurationNs()).isEqualTo(C.TIME_UNSET);

    // Frames with consistent durations, working toward establishing sync.
    for (int i = 0; i < CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC - 1; i++) {
      framePresentationTimestampNs += frameDurationNs;
      estimator.onNextFrame(framePresentationTimestampNs);

      assertThat(estimator.isSynced()).isFalse();
      assertThat(estimator.getFrameDurationNs()).isEqualTo(C.TIME_UNSET);
    }

    // This frame should establish sync.
    framePresentationTimestampNs += frameDurationNs;
    estimator.onNextFrame(framePresentationTimestampNs);

    assertThat(estimator.isSynced()).isTrue();
    assertThat(estimator.getFrameDurationNs()).isEqualTo(frameDurationNs);

    framePresentationTimestampNs += frameDurationNs;
    // Make the frame duration just shorter enough to lose sync.
    framePresentationTimestampNs -= MAX_MATCHING_FRAME_DIFFERENCE_NS + 1;
    estimator.onNextFrame(framePresentationTimestampNs);

    assertThat(estimator.isSynced()).isFalse();
    assertThat(estimator.getFrameDurationNs()).isEqualTo(C.TIME_UNSET);

    // Frames with consistent durations, working toward re-establishing sync.
    for (int i = 0; i < CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC - 1; i++) {
      framePresentationTimestampNs += frameDurationNs;
      estimator.onNextFrame(framePresentationTimestampNs);

      assertThat(estimator.isSynced()).isFalse();
      assertThat(estimator.getFrameDurationNs()).isEqualTo(C.TIME_UNSET);
    }

    // This frame should re-establish sync.
    framePresentationTimestampNs += frameDurationNs;
    estimator.onNextFrame(framePresentationTimestampNs);

    assertThat(estimator.isSynced()).isTrue();
    assertThat(estimator.getFrameDurationNs()).isEqualTo(frameDurationNs);
  }

  @Test
  public void fixedFrameRate_withOutlierFirstFrameDuration_syncs() {
    long frameDurationNs = 33_333_333;
    FixedFrameRateEstimator estimator = new FixedFrameRateEstimator(frameRate -> {});

    // Initial frame with double duration.
    long framePresentationTimestampNs = 0;
    estimator.onNextFrame(framePresentationTimestampNs);

    framePresentationTimestampNs += frameDurationNs * 2;
    estimator.onNextFrame(framePresentationTimestampNs);

    assertThat(estimator.isSynced()).isFalse();
    assertThat(estimator.getFrameDurationNs()).isEqualTo(C.TIME_UNSET);

    // Frames with consistent durations, working toward establishing sync.
    for (int i = 0; i < CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC - 1; i++) {
      framePresentationTimestampNs += frameDurationNs;
      estimator.onNextFrame(framePresentationTimestampNs);

      assertThat(estimator.isSynced()).isFalse();
      assertThat(estimator.getFrameDurationNs()).isEqualTo(C.TIME_UNSET);
    }

    // This frame should establish sync.
    framePresentationTimestampNs += frameDurationNs;
    estimator.onNextFrame(framePresentationTimestampNs);
  }

  @Test
  public void newFixedFrameRate_resyncs() {
    long frameDurationNs = 33_333_333;
    FixedFrameRateEstimator estimator = new FixedFrameRateEstimator(frameRate -> {});

    long framePresentationTimestampNs = 0;
    estimator.onNextFrame(framePresentationTimestampNs);
    for (int i = 0; i < CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC; i++) {
      framePresentationTimestampNs += frameDurationNs;
      estimator.onNextFrame(framePresentationTimestampNs);
    }

    assertThat(estimator.isSynced()).isTrue();
    assertThat(estimator.getFrameDurationNs()).isEqualTo(frameDurationNs);

    // Frames durations are halved from this point.
    long halfFrameRateDuration = frameDurationNs / 2;

    // Frames with consistent durations, working toward establishing new sync.
    for (int i = 0; i < CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC - 1; i++) {
      framePresentationTimestampNs += halfFrameRateDuration;
      estimator.onNextFrame(framePresentationTimestampNs);

      assertThat(estimator.isSynced()).isFalse();
      assertThat(estimator.getFrameDurationNs()).isEqualTo(C.TIME_UNSET);
    }

    // This frame should establish sync.
    framePresentationTimestampNs += halfFrameRateDuration;
    estimator.onNextFrame(framePresentationTimestampNs);

    assertThat(estimator.isSynced()).isTrue();
    assertThat(estimator.getFrameDurationNs()).isEqualTo(halfFrameRateDuration);
  }

  @Test
  public void fixedFrameRate_withMillisecondPrecision_syncs() {
    long frameDurationNs = 33_333_333;
    FixedFrameRateEstimator estimator = new FixedFrameRateEstimator(frameRate -> {});

    // Initial frame.
    long framePresentationTimestampNs = 0;
    estimator.onNextFrame(getNsWithMsPrecision(framePresentationTimestampNs));

    assertThat(estimator.isSynced()).isFalse();
    assertThat(estimator.getFrameDurationNs()).isEqualTo(C.TIME_UNSET);

    // Frames with consistent durations, working toward establishing sync.
    for (int i = 0; i < CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC - 1; i++) {
      framePresentationTimestampNs += frameDurationNs;
      estimator.onNextFrame(getNsWithMsPrecision(framePresentationTimestampNs));

      assertThat(estimator.isSynced()).isFalse();
      assertThat(estimator.getFrameDurationNs()).isEqualTo(C.TIME_UNSET);
    }

    // This frame should establish sync.
    framePresentationTimestampNs += frameDurationNs;
    estimator.onNextFrame(getNsWithMsPrecision(framePresentationTimestampNs));

    assertThat(estimator.isSynced()).isTrue();
    // The estimated frame duration should be strictly better than millisecond precision.
    long estimatedFrameDurationNs = estimator.getFrameDurationNs();
    long estimatedFrameDurationErrorNs = Math.abs(estimatedFrameDurationNs - frameDurationNs);
    assertThat(estimatedFrameDurationErrorNs).isLessThan(1000000);
  }

  @Test
  public void variableFrameRate_doesNotSync() {
    long frameDurationNs = 33_333_333;
    FixedFrameRateEstimator estimator = new FixedFrameRateEstimator(frameRate -> {});

    // Initial frame.
    long framePresentationTimestampNs = 0;
    estimator.onNextFrame(framePresentationTimestampNs);

    assertThat(estimator.isSynced()).isFalse();
    assertThat(estimator.getFrameDurationNs()).isEqualTo(C.TIME_UNSET);

    for (int i = 0; i < CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC * 10; i++) {
      framePresentationTimestampNs += frameDurationNs;
      // Adjust a frame that's just different enough, just often enough to prevent sync.
      if ((i % CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC) == 0) {
        framePresentationTimestampNs += MAX_MATCHING_FRAME_DIFFERENCE_NS + 1;
      }
      estimator.onNextFrame(framePresentationTimestampNs);

      assertThat(estimator.isSynced()).isFalse();
      assertThat(estimator.getFrameDurationNs()).isEqualTo(C.TIME_UNSET);
    }
  }

  @Test
  public void listener_notifiedOnSyncAndFormatChange() {
    float[] reportedFrameRate = new float[] {Format.NO_VALUE};
    FixedFrameRateEstimator estimator =
        new FixedFrameRateEstimator(frameRate -> reportedFrameRate[0] = frameRate);

    estimator.onFormatChanged(30.0f);
    assertThat(reportedFrameRate[0]).isEqualTo(30.0f);

    // Process frames to establish sync at 60fps.
    long frameDurationNs = 16_666_666;
    long framePresentationTimestampNs = 0;
    estimator.onNextFrame(framePresentationTimestampNs);
    for (int i = 0; i < CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC; i++) {
      framePresentationTimestampNs += frameDurationNs;
      estimator.onNextFrame(framePresentationTimestampNs);
    }

    // Should update to ~60fps.
    assertThat(reportedFrameRate[0]).isWithin(0.1f).of(60.0f);

    // Lose sync by adding a gap.
    framePresentationTimestampNs += frameDurationNs * 2;
    estimator.onNextFrame(framePresentationTimestampNs);

    // After some frames without sync, it should go back to format frame rate.
    int framesToClear = 2 * CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC;
    for (int i = 0; i < framesToClear; i++) {
      framePresentationTimestampNs += frameDurationNs + (i % 2 == 0 ? 2_000_000 : -2_000_000);
      estimator.onNextFrame(framePresentationTimestampNs);
    }
    assertThat(reportedFrameRate[0]).isEqualTo(30.0f);
  }

  @Test
  public void listener_notifiedOnHighConfidenceUpdate() {
    float[] reportedFrameRate = new float[] {Format.NO_VALUE};
    FixedFrameRateEstimator estimator =
        new FixedFrameRateEstimator(frameRate -> reportedFrameRate[0] = frameRate);

    estimator.onFormatChanged(30.0f);
    assertThat(reportedFrameRate[0]).isEqualTo(30.0f);

    // Process frames to establish sync at 60fps.
    long frameDurationNs = 16_666_666;
    long framePresentationTimestampNs = 0;
    estimator.onNextFrame(framePresentationTimestampNs);
    for (int i = 0; i < CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC; i++) {
      framePresentationTimestampNs += frameDurationNs;
      estimator.onNextFrame(framePresentationTimestampNs);
    }

    // Should update to ~60fps.
    assertThat(reportedFrameRate[0]).isWithin(0.1f).of(60.0f);

    // Feed MORE frames to exceed MINIMUM_MATCHING_FRAME_DURATION_FOR_HIGH_CONFIDENCE_NS (5
    // seconds).
    for (int i = 0; i < 350; i++) {
      framePresentationTimestampNs += frameDurationNs;
      estimator.onNextFrame(framePresentationTimestampNs);
    }

    // Now change frame rate slightly (by 0.5f).
    // New duration = 1000_000_000 / 60.5 = 16_528_925 ns.
    long newFrameDurationNs = 16_528_925;

    // Feed frames at the new rate to establish new sync.
    for (int i = 0; i < 100; i++) {
      framePresentationTimestampNs += newFrameDurationNs;
      estimator.onNextFrame(framePresentationTimestampNs);
    }

    // With 350 frames at 60fps and 100 frames at 60.5fps, the average frame rate
    // is approximately 60.11fps. It would take about 1400 frames at 60.5fps to
    // reach 60.4fps (within 0.1 of 60.5). So we assert it is around 60.1f to
    // verify that the update was triggered (which requires >0.1f change).
    assertThat(reportedFrameRate[0]).isWithin(0.05f).of(60.1f);
  }

  private static long getNsWithMsPrecision(long presentationTimeNs) {
    return (presentationTimeNs / 1000000) * 1000000;
  }
}
