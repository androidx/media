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
package androidx.media3.exoplayer.video;

import static android.os.Build.VERSION.SDK_INT;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Math.abs;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.view.Choreographer;
import android.view.Choreographer.FrameCallback;
import android.view.Display;
import android.view.Surface;
import androidx.annotation.CallSuper;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;

/**
 * A helper to release video frames to a {@link Surface}. This helper:
 *
 * <ul>
 *   <li>Adjusts frame release timestamps to achieve a smoother visual result. The release
 *       timestamps are smoothed, and aligned with the default display's vsync signal.
 *   <li>Adjusts the {@link Surface} frame rate to inform the underlying platform of a fixed frame
 *       rate, when there is one.
 * </ul>
 */
@UnstableApi
public final class VideoFrameReleaseHelper {

  private static final String TAG = "VideoFrameReleaseHelper";

  /**
   * The minimum sum of frame durations used to calculate the current fixed frame rate estimate, for
   * the estimate to be treated as a high confidence estimate.
   */
  private static final long MINIMUM_MATCHING_FRAME_DURATION_FOR_HIGH_CONFIDENCE_NS = 5_000_000_000L;

  /**
   * The minimum change in media frame rate that will trigger a change in surface frame rate, given
   * a high confidence estimate.
   */
  private static final float MINIMUM_MEDIA_FRAME_RATE_CHANGE_FOR_UPDATE_HIGH_CONFIDENCE = 0.1f;

  /**
   * The minimum change in media frame rate that will trigger a change in surface frame rate, given
   * a low confidence estimate.
   */
  private static final float MINIMUM_MEDIA_FRAME_RATE_CHANGE_FOR_UPDATE_LOW_CONFIDENCE = 1f;

  /**
   * The minimum number of frames without a frame rate estimate, for the surface frame rate to be
   * cleared.
   */
  private static final int MINIMUM_FRAMES_WITHOUT_SYNC_TO_CLEAR_SURFACE_FRAME_RATE =
      2 * FixedFrameRateEstimator.CONSECUTIVE_MATCHING_FRAME_DURATIONS_FOR_SYNC;

  /** The period between sampling display VSYNC timestamps, in milliseconds. */
  @VisibleForTesting public static final long VSYNC_SAMPLE_UPDATE_PERIOD_MS = 500;

  /**
   * The maximum adjustment that can be made to a frame release timestamp, in nanoseconds, excluding
   * the part of the adjustment that aligns frame release timestamps with the display VSYNC.
   */
  private static final long MAX_ALLOWED_ADJUSTMENT_NS = 20_000_000;

  /**
   * If a frame is targeted to a display VSYNC with timestamp {@code vsyncTime}, the adjusted frame
   * release timestamp will be calculated as {@code releaseTime = vsyncTime - ((vsyncDuration *
   * VSYNC_OFFSET_PERCENTAGE) / 100)}.
   */
  private static final long VSYNC_OFFSET_PERCENTAGE = 80;

  private final FixedFrameRateEstimator frameRateEstimator;
  private final Context context;

  private boolean vsyncSampleBuilt;
  @Nullable private VSyncSampler vsyncSampler;
  private boolean started;
  @Nullable private Surface surface;

  /** The media frame rate specified in the {@link Format}. */
  private float formatFrameRate;

  /**
   * The media frame rate used to calculate the playback frame rate of the {@link Surface}. This may
   * be different to {@link #formatFrameRate} if {@link #formatFrameRate} is unspecified or
   * inaccurate.
   */
  private float surfaceMediaFrameRate;

  /** The playback frame rate set on the {@link Surface}. */
  private float surfacePlaybackFrameRate;

  private float playbackSpeed;
  private @C.VideoChangeFrameRateStrategy int changeFrameRateStrategy;

  private long lastVsyncHysteresisOffsetNs;
  private long pendingVsyncHysteresisOffsetNs;

  private long frameIndex;
  private long pendingLastAdjustedFrameIndex;
  private long pendingLastAdjustedReleaseTimeNs;
  private long pendingLastPresentationTimeUs;
  private long lastAdjustedFrameIndex;
  private long lastAdjustedReleaseTimeNs;
  private long lastAdjustedPresentationTimeUs;

  /**
   * Constructs an instance.
   *
   * @param context A context from which information about the default display can be retrieved.
   */
  public VideoFrameReleaseHelper(Context context) {
    this.context = context;
    frameRateEstimator = new FixedFrameRateEstimator();
    formatFrameRate = Format.NO_VALUE;
    playbackSpeed = 1f;
    changeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS;
  }

  /**
   * Changes the {@link C.VideoChangeFrameRateStrategy} used when calling {@link
   * Surface#setFrameRate}.
   *
   * <p>The default value is {@link C#VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS}.
   */
  public void setChangeFrameRateStrategy(
      @C.VideoChangeFrameRateStrategy int changeFrameRateStrategy) {
    if (this.changeFrameRateStrategy == changeFrameRateStrategy) {
      return;
    }
    this.changeFrameRateStrategy = changeFrameRateStrategy;
    updateSurfacePlaybackFrameRate(/* forceUpdate= */ true);
  }

  /** Called when rendering starts. */
  public void onStarted() {
    started = true;
    resetAdjustment();
    if (!vsyncSampleBuilt) {
      vsyncSampler = VSyncSampler.maybeBuildInstance(context);
    }
    if (vsyncSampler != null) {
      vsyncSampler.register();
    }
    updateSurfacePlaybackFrameRate(/* forceUpdate= */ false);
  }

  /**
   * Called when the {@link Surface} on which to render changes.
   *
   * @param surface The new {@link Surface}, or {@code null} if there is none.
   */
  public void onSurfaceChanged(@Nullable Surface surface) {
    if (this.surface == surface) {
      return;
    }
    clearSurfaceFrameRate();
    this.surface = surface;
    updateSurfacePlaybackFrameRate(/* forceUpdate= */ true);
  }

  /** Called when the position is reset. */
  public void onPositionReset() {
    resetAdjustment();
  }

  /**
   * Called when the playback speed changes.
   *
   * @param playbackSpeed The factor by which playback is sped up.
   */
  public void onPlaybackSpeed(float playbackSpeed) {
    this.playbackSpeed = playbackSpeed;
    updateSurfacePlaybackFrameRate(/* forceUpdate= */ false);
  }

  /**
   * Called when the output format changes.
   *
   * @param formatFrameRate The format's frame rate, or {@link Format#NO_VALUE} if unknown.
   */
  public void onFormatChanged(float formatFrameRate) {
    this.formatFrameRate = formatFrameRate;
    frameRateEstimator.reset();
    updateSurfaceMediaFrameRate();
  }

  /**
   * Called for each frame, prior to it being skipped, dropped or rendered.
   *
   * @param framePresentationTimeUs The frame presentation timestamp, in microseconds.
   */
  public void onNextFrame(long framePresentationTimeUs) {
    if (pendingLastAdjustedFrameIndex != C.INDEX_UNSET) {
      lastAdjustedFrameIndex = pendingLastAdjustedFrameIndex;
      lastAdjustedReleaseTimeNs = pendingLastAdjustedReleaseTimeNs;
      lastAdjustedPresentationTimeUs = pendingLastPresentationTimeUs;
      lastVsyncHysteresisOffsetNs = pendingVsyncHysteresisOffsetNs;
    }
    frameIndex++;
    frameRateEstimator.onNextFrame(framePresentationTimeUs * 1000);
    updateSurfaceMediaFrameRate();
  }

  /** Called when rendering stops. */
  public void onStopped() {
    started = false;
    if (vsyncSampler != null) {
      vsyncSampler.unregister();
    }
    clearSurfaceFrameRate();
  }

  // Frame release time adjustment.

  /**
   * Adjusts the release timestamp for the next frame. This is the frame whose presentation
   * timestamp was most recently passed to {@link #onNextFrame}.
   *
   * <p>This method may be called any number of times for each frame, including zero times (for
   * skipped frames, or when rendering the first frame prior to playback starting), or more than
   * once (if the caller wishes to give the helper the opportunity to refine a release time closer
   * to when the frame needs to be released).
   *
   * @param releaseTimeNs The frame's unadjusted release time, in nanoseconds and in the same time
   *     base as {@link System#nanoTime()}.
   * @param presentationTimeUs The frame's presentation timestamp in microsecond.
   * @return The adjusted frame release timestamp, in nanoseconds and in the same time base as
   *     {@link System#nanoTime()}.
   */
  public long adjustReleaseTime(long releaseTimeNs, long presentationTimeUs) {
    // Until we know better, the adjustment will be a no-op.
    long adjustedReleaseTimeNs = releaseTimeNs;

    if (lastAdjustedFrameIndex != C.INDEX_UNSET) {
      long elapsedReleaseTimeSinceLastFrameNs;
      if (frameRateEstimator.isSynced()) {
        long frameDurationNs = frameRateEstimator.getFrameDurationNs();
        elapsedReleaseTimeSinceLastFrameNs =
            (long) ((frameDurationNs * (frameIndex - lastAdjustedFrameIndex)) / playbackSpeed);
      } else {
        elapsedReleaseTimeSinceLastFrameNs =
            (long) ((presentationTimeUs - lastAdjustedPresentationTimeUs) * 1000 / playbackSpeed);
      }
      long candidateAdjustedReleaseTimeNs =
          lastAdjustedReleaseTimeNs + elapsedReleaseTimeSinceLastFrameNs;
      if (adjustmentAllowed(releaseTimeNs, candidateAdjustedReleaseTimeNs)) {
        adjustedReleaseTimeNs = candidateAdjustedReleaseTimeNs;
      } else {
        resetAdjustment();
      }
    }
    pendingLastAdjustedFrameIndex = frameIndex;
    pendingLastAdjustedReleaseTimeNs = adjustedReleaseTimeNs;
    pendingLastPresentationTimeUs = presentationTimeUs;

    if (vsyncSampler == null) {
      return adjustedReleaseTimeNs;
    }
    long sampledVsyncTimeNs = vsyncSampler.sampledVsyncTimeNs;
    long vsyncDurationNs = vsyncSampler.vsyncDurationNs;
    if (sampledVsyncTimeNs == C.TIME_UNSET || vsyncDurationNs == C.TIME_UNSET) {
      return adjustedReleaseTimeNs;
    }
    // Find the timestamp of the closest vsync. This is the vsync that we're targeting.
    long snappedTimeNs =
        findClosestVsyncAndUpdateHysteresis(
            adjustedReleaseTimeNs, sampledVsyncTimeNs, vsyncDurationNs);
    // Apply an offset so that we release before the target vsync, but after the previous one.
    return snappedTimeNs - (vsyncDurationNs * VSYNC_OFFSET_PERCENTAGE) / 100;
  }

  @VisibleForTesting
  public void setVsyncData(long vsyncSampleTimeNs, long vsyncDurationNs) {
    checkNotNull(vsyncSampler).sampledVsyncTimeNs = vsyncSampleTimeNs;
    vsyncSampler.vsyncDurationNs = vsyncDurationNs;
  }

  private void resetAdjustment() {
    frameIndex = 0;
    lastAdjustedFrameIndex = C.INDEX_UNSET;
    pendingLastAdjustedFrameIndex = C.INDEX_UNSET;
    lastVsyncHysteresisOffsetNs = 0;
    pendingVsyncHysteresisOffsetNs = 0;
  }

  private static boolean adjustmentAllowed(
      long unadjustedReleaseTimeNs, long adjustedReleaseTimeNs) {
    return abs(unadjustedReleaseTimeNs - adjustedReleaseTimeNs) <= MAX_ALLOWED_ADJUSTMENT_NS;
  }

  // Surface frame rate adjustment.

  /**
   * Updates the media frame rate that's used to calculate the playback frame rate of the current
   * {@link #surface}. If the frame rate is updated then {@link #updateSurfacePlaybackFrameRate} is
   * called to update the surface.
   */
  private void updateSurfaceMediaFrameRate() {
    if (SDK_INT < 30 || surface == null) {
      return;
    }

    float candidateFrameRate =
        frameRateEstimator.isSynced() ? frameRateEstimator.getFrameRate() : formatFrameRate;
    if (candidateFrameRate == surfaceMediaFrameRate) {
      return;
    }

    // The candidate is different to the current surface media frame rate. Decide whether to update
    // the surface media frame rate.
    boolean shouldUpdate;
    if (candidateFrameRate != Format.NO_VALUE && surfaceMediaFrameRate != Format.NO_VALUE) {
      boolean candidateIsHighConfidence =
          frameRateEstimator.isSynced()
              && frameRateEstimator.getMatchingFrameDurationSumNs()
                  >= MINIMUM_MATCHING_FRAME_DURATION_FOR_HIGH_CONFIDENCE_NS;
      float minimumChangeForUpdate =
          candidateIsHighConfidence
              ? MINIMUM_MEDIA_FRAME_RATE_CHANGE_FOR_UPDATE_HIGH_CONFIDENCE
              : MINIMUM_MEDIA_FRAME_RATE_CHANGE_FOR_UPDATE_LOW_CONFIDENCE;
      shouldUpdate = abs(candidateFrameRate - surfaceMediaFrameRate) >= minimumChangeForUpdate;
    } else if (candidateFrameRate != Format.NO_VALUE) {
      shouldUpdate = true;
    } else {
      shouldUpdate =
          frameRateEstimator.getFramesWithoutSyncCount()
              >= MINIMUM_FRAMES_WITHOUT_SYNC_TO_CLEAR_SURFACE_FRAME_RATE;
    }

    if (shouldUpdate) {
      surfaceMediaFrameRate = candidateFrameRate;
      updateSurfacePlaybackFrameRate(/* forceUpdate= */ false);
    }
  }

  /**
   * Updates the playback frame rate of the current {@link #surface} based on the playback speed,
   * frame rate of the content, and whether the rendering started.
   *
   * <p>Does nothing if {@link #changeFrameRateStrategy} is {@link
   * C#VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF}.
   *
   * @param forceUpdate Whether to call {@link Surface#setFrameRate} even if the frame rate is
   *     unchanged.
   */
  private void updateSurfacePlaybackFrameRate(boolean forceUpdate) {
    if (SDK_INT < 30
        || surface == null
        || changeFrameRateStrategy == C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        || !surface.isValid()) {
      return;
    }

    float surfacePlaybackFrameRate = 0;
    if (started && surfaceMediaFrameRate != Format.NO_VALUE) {
      surfacePlaybackFrameRate = surfaceMediaFrameRate * playbackSpeed;
    }
    // We always set the frame-rate if we have a new surface, since we have no way of knowing what
    // it might have been set to previously.
    if (!forceUpdate && this.surfacePlaybackFrameRate == surfacePlaybackFrameRate) {
      return;
    }
    this.surfacePlaybackFrameRate = surfacePlaybackFrameRate;
    Api30.setSurfaceFrameRate(surface, surfacePlaybackFrameRate);
  }

  /**
   * Clears the frame-rate of the current {@link #surface}.
   *
   * <p>Does nothing if {@link #changeFrameRateStrategy} is {@link
   * C#VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF}.
   */
  private void clearSurfaceFrameRate() {
    if (SDK_INT < 30
        || surface == null
        || changeFrameRateStrategy == C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        || surfacePlaybackFrameRate == 0
        || !surface.isValid()) {
      return;
    }
    surfacePlaybackFrameRate = 0;
    Api30.setSurfaceFrameRate(surface, /* frameRate= */ 0);
  }

  private long findClosestVsyncAndUpdateHysteresis(
      long releaseTimeNs, long sampledVsyncTimeNs, long vsyncDurationNs) {
    long vsyncCount = (releaseTimeNs - sampledVsyncTimeNs) / vsyncDurationNs;
    long snappedTimeNs = sampledVsyncTimeNs + (vsyncDurationNs * vsyncCount);
    long snappedBeforeNs;
    long snappedAfterNs;
    if (releaseTimeNs <= snappedTimeNs) {
      snappedBeforeNs = snappedTimeNs - vsyncDurationNs;
      snappedAfterNs = snappedTimeNs;
    } else {
      snappedBeforeNs = snappedTimeNs;
      snappedAfterNs = snappedTimeNs + vsyncDurationNs;
    }
    long snappedAfterDiffNs = snappedAfterNs - releaseTimeNs;
    long snappedBeforeDiffNs = releaseTimeNs - snappedBeforeNs;

    // Many frame rates oscillate between a clear match and an ambiguous match (e.g. 60fps on 90Hz).
    // Only evaluate hysteresis if the diffs are sufficiently close for it to matter and ignore the
    // clearer matches in between.
    long snappedDiffsDiffNs = abs(snappedAfterDiffNs - snappedBeforeDiffNs);
    boolean shouldEvaluateHysteresis = snappedDiffsDiffNs < vsyncDurationNs / 2;
    if (shouldEvaluateHysteresis) {
      // Apply hysteresis logic: Clear if outside of range, initialize if newly within range.
      long hysteresisRangeNs = vsyncDurationNs / 4;
      boolean isInHysteresisRange = snappedDiffsDiffNs < hysteresisRangeNs;
      if (isInHysteresisRange) {
        if (lastVsyncHysteresisOffsetNs != 0) {
          pendingVsyncHysteresisOffsetNs = lastVsyncHysteresisOffsetNs;
        } else {
          pendingVsyncHysteresisOffsetNs =
              snappedAfterDiffNs < snappedBeforeDiffNs ? -hysteresisRangeNs : hysteresisRangeNs;
        }
      } else {
        pendingVsyncHysteresisOffsetNs = 0;
      }
    } else {
      pendingVsyncHysteresisOffsetNs = lastVsyncHysteresisOffsetNs;
    }
    return snappedAfterDiffNs + pendingVsyncHysteresisOffsetNs < snappedBeforeDiffNs
        ? snappedAfterNs
        : snappedBeforeNs;
  }

  @RequiresApi(30)
  private static final class Api30 {
    public static void setSurfaceFrameRate(Surface surface, float frameRate) {
      int compatibility =
          frameRate == 0
              ? Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
              : Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE;
      try {
        surface.setFrameRate(frameRate, compatibility);
      } catch (IllegalStateException e) {
        Log.e(TAG, "Failed to call Surface.setFrameRate", e);
      }
    }
  }

  private abstract static class VSyncSampler implements DisplayManager.DisplayListener {

    @Nullable
    private static VSyncSampler maybeBuildInstance(Context context) {
      DisplayManager displayManager =
          (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
      if (displayManager == null) {
        return null;
      }
      Choreographer choreographer;
      try {
        choreographer = Choreographer.getInstance();
      } catch (RuntimeException e) {
        // See [Internal: b/213926330].
        Log.w(TAG, "Vsync sampling disabled due to platform error", e);
        return null;
      }
      return SDK_INT >= 33
          ? new VSyncSamplerV33(choreographer, displayManager)
          : new VSyncSamplerBase(choreographer, displayManager);
    }

    /* package */ final Choreographer choreographer;
    /* package */ final DisplayManager displayManager;

    /* package */ volatile long sampledVsyncTimeNs;
    /* package */ volatile long vsyncDurationNs;

    private VSyncSampler(Choreographer choreographer, DisplayManager displayManager) {
      this.choreographer = choreographer;
      this.displayManager = displayManager;
      sampledVsyncTimeNs = C.TIME_UNSET;
      vsyncDurationNs = C.TIME_UNSET;
    }

    @CallSuper
    /* package */ void register() {
      displayManager.registerDisplayListener(this, Util.createHandlerForCurrentLooper());
    }

    @CallSuper
    /* package */ void unregister() {
      displayManager.unregisterDisplayListener(this);
    }

    @Override
    public final void onDisplayAdded(int displayId) {
      // Do nothing.
    }

    @Override
    public final void onDisplayRemoved(int displayId) {
      // Do nothing.
    }
  }

  /** Samples display vsync timestamps. */
  private static final class VSyncSamplerBase extends VSyncSampler implements FrameCallback {

    private VSyncSamplerBase(Choreographer choreographer, DisplayManager displayManager) {
      super(choreographer, displayManager);
    }

    @Override
    /* package */ void register() {
      super.register();
      choreographer.postFrameCallback(this);
      vsyncDurationNs = getVsyncDurationNsFromDefaultDisplay(displayManager);
    }

    /**
     * Notifies the sampler that a {@link VideoFrameReleaseHelper} is no longer observing {@link
     * #sampledVsyncTimeNs}.
     */
    @Override
    /* package */ void unregister() {
      super.unregister();
      choreographer.removeFrameCallback(this);
      sampledVsyncTimeNs = C.TIME_UNSET;
      vsyncDurationNs = C.TIME_UNSET;
    }

    @Override
    public void doFrame(long vsyncTimeNs) {
      sampledVsyncTimeNs = vsyncTimeNs;
      choreographer.postFrameCallbackDelayed(this, VSYNC_SAMPLE_UPDATE_PERIOD_MS);
    }

    @Override
    public void onDisplayChanged(int displayId) {
      if (displayId == Display.DEFAULT_DISPLAY) {
        choreographer.postFrameCallback(this);
        vsyncDurationNs = getVsyncDurationNsFromDefaultDisplay(displayManager);
      }
    }

    private static long getVsyncDurationNsFromDefaultDisplay(DisplayManager displayManager) {
      Display defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
      if (defaultDisplay != null) {
        double defaultDisplayRefreshRate = defaultDisplay.getRefreshRate();
        return (long) (C.NANOS_PER_SECOND / defaultDisplayRefreshRate);
      } else {
        Log.w(TAG, "Unable to query display refresh rate");
        return C.TIME_UNSET;
      }
    }
  }

  /** Samples display vsync timestamps using APIs available from API 33. */
  @RequiresApi(33)
  private static final class VSyncSamplerV33 extends VSyncSampler
      implements Choreographer.VsyncCallback {

    private final Handler handler;

    private VSyncSamplerV33(Choreographer choreographer, DisplayManager displayManager) {
      super(choreographer, displayManager);
      this.handler = Util.createHandlerForCurrentLooper();
    }

    @Override
    /* package */ void register() {
      super.register();
      choreographer.postVsyncCallback(this);
    }

    @Override
    /* package */ void unregister() {
      super.unregister();
      handler.removeCallbacksAndMessages(/* token= */ null);
      choreographer.removeVsyncCallback(this);
      sampledVsyncTimeNs = C.TIME_UNSET;
      vsyncDurationNs = C.TIME_UNSET;
    }

    @Override
    public void onVsync(Choreographer.FrameData data) {
      sampledVsyncTimeNs = data.getFrameTimeNanos();
      Choreographer.FrameTimeline[] frameTimelines = data.getFrameTimelines();
      if (frameTimelines.length >= 2) {
        long vsyncDurationNs =
            frameTimelines[1].getExpectedPresentationTimeNanos()
                - frameTimelines[0].getExpectedPresentationTimeNanos();
        this.vsyncDurationNs = vsyncDurationNs == 0 ? C.TIME_UNSET : vsyncDurationNs;
      } else {
        vsyncDurationNs = C.TIME_UNSET;
      }
      handler.postDelayed(
          () -> choreographer.postVsyncCallback(this), VSYNC_SAMPLE_UPDATE_PERIOD_MS);
    }

    @Override
    public void onDisplayChanged(int displayId) {
      if (displayId == Display.DEFAULT_DISPLAY) {
        choreographer.postVsyncCallback(this);
      }
    }
  }
}
