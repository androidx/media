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

import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import java.util.Arrays;

/**
 * Attempts to detect and refine a fixed frame rate estimate based on frame presentation timestamps.
 */
@UnstableApi
public final class FixedFrameRateEstimator {

  /** A listener for frame rate estimate updates. */
  public interface Listener {
    /**
     * Called when the frame rate estimate changes.
     *
     * @param frameRate The new frame rate estimate, or {@link Format#NO_VALUE} if unknown.
     */
    void onFrameRateEstimateChanged(float frameRate);
  }

  /** The number of consecutive matching frame durations required to detect a fixed frame rate. */
  public static final int CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC = 15;

  /**
   * The maximum amount frame durations can differ for them to be considered matching, in
   * nanoseconds.
   *
   * <p>This constant is set to 1ms to account for container formats that only represent frame
   * presentation timestamps to the nearest millisecond. In such cases, frame durations need to
   * switch between values that are 1ms apart to achieve common fixed frame rates (e.g., 30fps
   * content will need frames that are 33ms and 34ms).
   */
  @VisibleForTesting static final long MAX_MATCHING_FRAME_DIFFERENCE_NS = 1_000_000;

  private static final long MINIMUM_MATCHING_FRAME_DURATION_FOR_HIGH_CONFIDENCE_NS = 5_000_000_000L;
  private static final float MINIMUM_MEDIA_FRAME_RATE_CHANGE_FOR_UPDATE_HIGH_CONFIDENCE = 0.1f;
  private static final float MINIMUM_MEDIA_FRAME_RATE_CHANGE_FOR_UPDATE_LOW_CONFIDENCE = 1f;
  private static final int MINIMUM_FRAMES_WITHOUT_SYNC_TO_CLEAR_FRAME_RATE_ESTIMATE =
      2 * CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC;

  private Matcher currentMatcher;
  private Matcher candidateMatcher;
  private boolean candidateMatcherActive;
  private boolean switchToCandidateMatcherWhenSynced;
  private long lastFramePresentationTimeNs;
  private int framesWithoutSyncCount;
  private float formatFrameRate;
  private float frameRateEstimate;
  private final Listener listener;

  /**
   * Creates a new estimator.
   *
   * @param listener The listener to notify when the frame rate estimate changes.
   */
  public FixedFrameRateEstimator(Listener listener) {
    this.listener = listener;
    currentMatcher = new Matcher();
    candidateMatcher = new Matcher();
    lastFramePresentationTimeNs = C.TIME_UNSET;
    formatFrameRate = Format.NO_VALUE;
    frameRateEstimate = Format.NO_VALUE;
  }

  /**
   * Called when the output format changes.
   *
   * @param formatFrameRate The format's frame rate, or {@link Format#NO_VALUE} if unknown.
   */
  public void onFormatChanged(float formatFrameRate) {
    this.formatFrameRate = formatFrameRate;
    currentMatcher.reset();
    candidateMatcher.reset();
    candidateMatcherActive = false;
    lastFramePresentationTimeNs = C.TIME_UNSET;
    framesWithoutSyncCount = 0;
    updateReportedFrameRate();
  }

  private void updateReportedFrameRate() {
    boolean synced = currentMatcher.isSynced();
    float candidateFrameRate =
        synced
            ? (float) ((double) C.NANOS_PER_SECOND / currentMatcher.getFrameDurationNs())
            : formatFrameRate;
    if (candidateFrameRate == frameRateEstimate) {
      return;
    }

    boolean shouldUpdate;
    if (candidateFrameRate != Format.NO_VALUE && frameRateEstimate != Format.NO_VALUE) {
      boolean candidateIsHighConfidence =
          synced
              && currentMatcher.getMatchingFrameDurationSumNs()
                  >= MINIMUM_MATCHING_FRAME_DURATION_FOR_HIGH_CONFIDENCE_NS;
      float minimumChangeForUpdate =
          candidateIsHighConfidence
              ? MINIMUM_MEDIA_FRAME_RATE_CHANGE_FOR_UPDATE_HIGH_CONFIDENCE
              : MINIMUM_MEDIA_FRAME_RATE_CHANGE_FOR_UPDATE_LOW_CONFIDENCE;
      shouldUpdate = Math.abs(candidateFrameRate - frameRateEstimate) >= minimumChangeForUpdate;
    } else if (candidateFrameRate != Format.NO_VALUE) {
      shouldUpdate = true;
    } else {
      shouldUpdate =
          framesWithoutSyncCount >= MINIMUM_FRAMES_WITHOUT_SYNC_TO_CLEAR_FRAME_RATE_ESTIMATE;
    }

    if (shouldUpdate) {
      frameRateEstimate = candidateFrameRate;
      listener.onFrameRateEstimateChanged(frameRateEstimate);
    }
  }

  /**
   * Called with each frame presentation timestamp.
   *
   * @param framePresentationTimeNs The frame presentation timestamp, in nanoseconds.
   */
  public void onNextFrame(long framePresentationTimeNs) {
    if (framePresentationTimeNs == lastFramePresentationTimeNs) {
      return;
    }
    currentMatcher.onNextFrame(framePresentationTimeNs);
    if (currentMatcher.isSynced() && !switchToCandidateMatcherWhenSynced) {
      candidateMatcherActive = false;
    } else if (lastFramePresentationTimeNs != C.TIME_UNSET) {
      if (!candidateMatcherActive || candidateMatcher.isLastFrameOutlier()) {
        // Reset the candidate with the last and current frame presentation timestamps, so that it
        // will try and match against the duration of the previous frame.
        candidateMatcher.reset();
        candidateMatcher.onNextFrame(lastFramePresentationTimeNs);
      }
      candidateMatcherActive = true;
      candidateMatcher.onNextFrame(framePresentationTimeNs);
    }
    if (candidateMatcherActive && candidateMatcher.isSynced()) {
      // The candidate matcher should be promoted to be the current matcher. The current matcher
      // can be re-used as the next candidate matcher.
      Matcher previousMatcher = currentMatcher;
      currentMatcher = candidateMatcher;
      candidateMatcher = previousMatcher;
      candidateMatcherActive = false;
      switchToCandidateMatcherWhenSynced = false;
    }
    lastFramePresentationTimeNs = framePresentationTimeNs;
    framesWithoutSyncCount = currentMatcher.isSynced() ? 0 : framesWithoutSyncCount + 1;
    updateReportedFrameRate();
  }

  /**
   * The currently detected fixed frame duration estimate in nanoseconds, or {@link C#TIME_UNSET} if
   * the estimator has not detected a fixed frame rate. Whilst synced, the estimate is refined each
   * time {@link #onNextFrame} is called with a new frame presentation timestamp.
   */
  public long getFrameDurationNs() {
    return currentMatcher.isSynced() ? currentMatcher.getFrameDurationNs() : C.TIME_UNSET;
  }

  /** Tries to match frame durations against the duration of the first frame it receives. */
  private static final class Matcher {

    private long firstFramePresentationTimeNs;
    private long firstFrameDurationNs;
    private long lastFramePresentationTimeNs;
    private long frameCount;

    /** The total number of frames that have matched the frame duration being tracked. */
    private long matchingFrameCount;

    /** The sum of the frame durations of all matching frames. */
    private long matchingFrameDurationSumNs;

    /** Cyclic buffer of flags indicating whether the most recent frame durations were outliers. */
    private final boolean[] recentFrameOutlierFlags;

    /**
     * The number of recent frame durations that were outliers. Equal to the number of {@code true}
     * values in {@link #recentFrameOutlierFlags}.
     */
    private int recentFrameOutlierCount;

    public Matcher() {
      recentFrameOutlierFlags = new boolean[CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC];
    }

    public void reset() {
      frameCount = 0;
      matchingFrameCount = 0;
      matchingFrameDurationSumNs = 0;
      recentFrameOutlierCount = 0;
      Arrays.fill(recentFrameOutlierFlags, false);
    }

    public boolean isSynced() {
      return frameCount > CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC
          && recentFrameOutlierCount == 0;
    }

    public boolean isLastFrameOutlier() {
      if (frameCount == 0) {
        return false;
      }
      return recentFrameOutlierFlags[getRecentFrameOutlierIndex(frameCount - 1)];
    }

    public long getMatchingFrameDurationSumNs() {
      return matchingFrameDurationSumNs;
    }

    public long getFrameDurationNs() {
      return matchingFrameCount == 0 ? 0 : (matchingFrameDurationSumNs / matchingFrameCount);
    }

    public void onNextFrame(long framePresentationTimeNs) {
      if (frameCount == 0) {
        firstFramePresentationTimeNs = framePresentationTimeNs;
      } else if (frameCount == 1) {
        // This is the frame duration that the tracker will match against.
        firstFrameDurationNs = framePresentationTimeNs - firstFramePresentationTimeNs;
        matchingFrameDurationSumNs = firstFrameDurationNs;
        matchingFrameCount = 1;
      } else {
        long lastFrameDurationNs = framePresentationTimeNs - lastFramePresentationTimeNs;
        int recentFrameOutlierIndex = getRecentFrameOutlierIndex(frameCount);
        if (Math.abs(lastFrameDurationNs - firstFrameDurationNs)
            <= MAX_MATCHING_FRAME_DIFFERENCE_NS) {
          matchingFrameCount++;
          matchingFrameDurationSumNs += lastFrameDurationNs;
          if (recentFrameOutlierFlags[recentFrameOutlierIndex]) {
            recentFrameOutlierFlags[recentFrameOutlierIndex] = false;
            recentFrameOutlierCount--;
          }
        } else {
          if (!recentFrameOutlierFlags[recentFrameOutlierIndex]) {
            recentFrameOutlierFlags[recentFrameOutlierIndex] = true;
            recentFrameOutlierCount++;
          }
        }
      }

      frameCount++;
      lastFramePresentationTimeNs = framePresentationTimeNs;
    }

    private static int getRecentFrameOutlierIndex(long frameCount) {
      return (int) (frameCount % CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC);
    }
  }
}
