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

import static com.google.common.base.Preconditions.checkArgument;

import android.util.Range;
import androidx.annotation.FloatRange;
import androidx.media3.common.C;

/**
 * Predicts the {@linkplain VideoFrameReleaseControl.FrameReleaseInfo#getEarlyUs() early time
 * compared to the playback position} for future frames.
 */
/* package */ class VideoFrameReleaseEarlyTimeForecaster {

  /**
   * The <a href="https://en.wikipedia.org/wiki/Exponential_smoothing">simple exponential
   * smoothing</a> parameter.
   */
  private static final float SMOOTHING_FACTOR = 0.2f;

  /**
   * The presentation time in microseconds of the last {@linkplain #onVideoFrameProcessed(long,
   * long) processed} frame, or {@link C#TIME_UNSET} if no frames have been processed yet.
   */
  private long lastFramePresentationTimeUs;

  /**
   * The early time in microseconds of the last {@linkplain #onVideoFrameProcessed(long, long)
   * processed} frame, or {@link C#TIME_UNSET} if no frames have been processed yet.
   */
  private long lastFrameEarlyUs;

  /**
   * The rate of change of early time with respect to presentation time. This is a dimensionless
   * quantity.
   *
   * <p>Use double instead of float to avoid loss of precision when working with large timestamps.
   */
  private double derivativeOfEarlyTime;

  /**
   * The range of values that {@link #derivativeOfEarlyTime} can take.
   *
   * <p>The rate of change of early time with respect to presentation time is at least 0 because if
   * the player is correctly configured, frames are processed at least as fast as playback time.
   *
   * <p>The rate of change is at most {@code 1 / playbackSpeed}. The early time is the difference
   * between the frame presentation time and the playback position, divided by playback speed. If
   * frame processing is instantaneous (that is, the playback position doesn't progress between
   * adjacent frames), the early time can only increase as fast as the presentation time.
   */
  private Range<Double> derivativeOfEarlyTimeRange;

  /**
   * Creates an instance.
   *
   * @param playbackSpeed The playback speed.
   * @throws IllegalArgumentException If the playback speed is non-positive.
   */
  public VideoFrameReleaseEarlyTimeForecaster(
      @FloatRange(from = 0, fromInclusive = false) float playbackSpeed) {
    checkArgument(playbackSpeed > 0);
    derivativeOfEarlyTimeRange = new Range<>(/* lower= */ 0.0, /* upper= */ 1.0 / playbackSpeed);
    derivativeOfEarlyTime = derivativeOfEarlyTimeRange.getUpper();
    lastFramePresentationTimeUs = C.TIME_UNSET;
    lastFrameEarlyUs = C.TIME_UNSET;
  }

  /**
   * Updates the frame release forecaster internal state when a new frame has been processed.
   *
   * @param framePresentationTimeUs The frame presentation time, in microseconds.
   * @param earlyUs The frame early time, in microseconds.
   * @throws IllegalArgumentException If {@code framePresentationTimeUs} is {@link C#TIME_UNSET}.
   * @throws IllegalArgumentException If {@code earlyUs} is {@link C#TIME_UNSET}.
   */
  public void onVideoFrameProcessed(long framePresentationTimeUs, long earlyUs) {
    checkArgument(framePresentationTimeUs != C.TIME_UNSET);
    checkArgument(earlyUs != C.TIME_UNSET);
    double derivativeFromLastFrame =
        calculateDerivativeFromLastFrame(framePresentationTimeUs, earlyUs);
    updateDerivativeWithExponentialMovingAverage(
        derivativeOfEarlyTimeRange.clamp(derivativeFromLastFrame));
    lastFramePresentationTimeUs = framePresentationTimeUs;
    lastFrameEarlyUs = earlyUs;
  }

  /**
   * Returns the predicted early time in microseconds for a future frame, or {@link C#TIME_UNSET} if
   * the forecaster hasn't {@linkplain #onVideoFrameProcessed(long, long) processed} any frames
   * since the last {@linkplain #reset() reset}.
   *
   * @param presentationTimeUs The presentation time of the future frame, in microseconds.
   */
  public long predictEarlyUs(long presentationTimeUs) {
    if (lastFramePresentationTimeUs == C.TIME_UNSET) {
      return C.TIME_UNSET;
    }
    return (long)
        (lastFrameEarlyUs
            + (presentationTimeUs - lastFramePresentationTimeUs) * derivativeOfEarlyTime);
  }

  /**
   * Sets the playback speed.
   *
   * @param playbackSpeed The playback speed.
   * @throws IllegalArgumentException If the playback speed is non-positive.
   */
  public void setPlaybackSpeed(@FloatRange(from = 0, fromInclusive = false) float playbackSpeed) {
    checkArgument(playbackSpeed > 0);
    derivativeOfEarlyTimeRange = new Range<>(/* lower= */ 0.0, /* upper= */ 1.0 / playbackSpeed);
    reset();
  }

  /** Resets the frame forecaster. */
  public void reset() {
    derivativeOfEarlyTime = derivativeOfEarlyTimeRange.getUpper();
    lastFramePresentationTimeUs = C.TIME_UNSET;
    lastFrameEarlyUs = C.TIME_UNSET;
  }

  private double calculateDerivativeFromLastFrame(long presentationTimeUs, long earlyUs) {
    if (lastFramePresentationTimeUs != C.TIME_UNSET
        && lastFrameEarlyUs != C.TIME_UNSET
        && presentationTimeUs != lastFramePresentationTimeUs) {
      return (double) (earlyUs - lastFrameEarlyUs)
          / (presentationTimeUs - lastFramePresentationTimeUs);
    }
    return derivativeOfEarlyTimeRange.getUpper();
  }

  private void updateDerivativeWithExponentialMovingAverage(double latestDerivative) {
    derivativeOfEarlyTime =
        derivativeOfEarlyTime * (1 - SMOOTHING_FACTOR) + latestDerivative * SMOOTHING_FACTOR;
  }
}
