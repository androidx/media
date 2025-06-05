/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.media3.exoplayer.audio;

import static androidx.media3.common.util.Util.sampleCountToDurationUs;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.media.AudioTimestamp;
import android.media.AudioTrack;
import androidx.annotation.IntDef;
import androidx.media3.common.C;
import androidx.media3.common.util.Util;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Polls the {@link AudioTrack} timestamp, if the platform supports it, taking care of polling at
 * the appropriate rate to detect when the timestamp starts to advance.
 *
 * <p>When the audio track isn't paused, call {@link #maybePollTimestamp} regularly to check for
 * timestamp updates.
 *
 * <p>If {@link #hasAdvancingTimestamp()} returns {@code true}, call {@link
 * #getTimestampPositionUs(long, float)} to get its position.
 *
 * <p>Call {@link #reset()} when pausing or resuming the track.
 */
/* package */ final class AudioTimestampPoller {

  /** Timestamp polling states. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    STATE_INITIALIZING,
    STATE_TIMESTAMP,
    STATE_TIMESTAMP_ADVANCING,
    STATE_NO_TIMESTAMP,
    STATE_ERROR
  })
  private @interface State {}

  /** State when first initializing. */
  private static final int STATE_INITIALIZING = 0;

  /** State when we have a timestamp and we don't know if it's advancing. */
  private static final int STATE_TIMESTAMP = 1;

  /** State when we have a timestamp and we know it is advancing. */
  private static final int STATE_TIMESTAMP_ADVANCING = 2;

  /** State when the no timestamp is available. */
  private static final int STATE_NO_TIMESTAMP = 3;

  /** State when the last timestamp was rejected as invalid. */
  private static final int STATE_ERROR = 4;

  /** The polling interval for {@link #STATE_INITIALIZING} and {@link #STATE_TIMESTAMP}. */
  private static final int FAST_POLL_INTERVAL_US = 10_000;

  /**
   * The polling interval for {@link #STATE_TIMESTAMP_ADVANCING} and {@link #STATE_NO_TIMESTAMP}.
   */
  private static final int SLOW_POLL_INTERVAL_US = 10_000_000;

  /** The polling interval for {@link #STATE_ERROR}. */
  private static final int ERROR_POLL_INTERVAL_US = 500_000;

  /**
   * The minimum duration to remain in {@link #STATE_INITIALIZING} if no timestamps are being
   * returned before transitioning to {@link #STATE_NO_TIMESTAMP}.
   */
  private static final int INITIALIZING_DURATION_US = 500_000;

  /**
   * The maximum difference in calculated current position between two reported timestamps to
   * consider the timestamps advancing correctly.
   */
  private static final long MAX_POSITION_DRIFT_ADVANCING_TIMESTAMP_US = 1000;

  /**
   * The minimum duration to remain in {@link #STATE_INITIALIZING} and {@link #STATE_TIMESTAMP} if
   * no correctly advancing timestamp is returned before transitioning to {@link #STATE_ERROR}.
   */
  private static final int WAIT_FOR_ADVANCE_DURATION_US = 2_000_000;

  /**
   * AudioTrack timestamps are deemed spurious if they are offset from the system clock by more than
   * this amount.
   *
   * <p>This is a fail safe that should not be required on correctly functioning devices.
   */
  private static final long MAX_AUDIO_TIMESTAMP_OFFSET_US = 5 * C.MICROS_PER_SECOND;

  private final AudioTimestampWrapper audioTimestamp;
  private final int sampleRate;
  private final AudioTrackPositionTracker.Listener errorListener;

  private @State int state;
  private long initializeSystemTimeUs;
  private long sampleIntervalUs;
  private long lastTimestampSampleTimeUs;
  private long initialTimestampPositionFrames;
  private long initialTimestampSystemTimeUs;

  /**
   * Creates a new audio timestamp poller.
   *
   * @param audioTrack The audio track that will provide timestamps.
   * @param errorListener The {@link AudioTrackPositionTracker.Listener} for timestamp errors.
   */
  public AudioTimestampPoller(
      AudioTrack audioTrack, AudioTrackPositionTracker.Listener errorListener) {
    this.audioTimestamp = new AudioTimestampWrapper(audioTrack);
    this.sampleRate = audioTrack.getSampleRate();
    this.errorListener = errorListener;
    reset();
  }

  /**
   * Polls and updates the timestamp if required.
   *
   * <p>The value of {@link #hasAdvancingTimestamp()} may have changed after calling this method.
   *
   * @param systemTimeUs The current system time, in microseconds.
   * @param audioTrackPlaybackSpeed The playback speed of the audio track.
   * @param playbackHeadPositionEstimateUs The current position estimate using the playback head
   *     position, in microseconds.
   */
  public void maybePollTimestamp(
      long systemTimeUs, float audioTrackPlaybackSpeed, long playbackHeadPositionEstimateUs) {
    if ((systemTimeUs - lastTimestampSampleTimeUs) < sampleIntervalUs) {
      return;
    }
    lastTimestampSampleTimeUs = systemTimeUs;
    boolean updatedTimestamp = audioTimestamp.maybeUpdateTimestamp();
    if (updatedTimestamp) {
      checkTimestampIsPlausibleAndUpdateErrorState(
          systemTimeUs, audioTrackPlaybackSpeed, playbackHeadPositionEstimateUs);
    }
    switch (state) {
      case STATE_INITIALIZING:
        if (updatedTimestamp) {
          if (audioTimestamp.getTimestampSystemTimeUs() >= initializeSystemTimeUs) {
            // We have an initial timestamp, but don't know if it's advancing yet.
            initialTimestampPositionFrames = audioTimestamp.getTimestampPositionFrames();
            initialTimestampSystemTimeUs = audioTimestamp.getTimestampSystemTimeUs();
            updateState(STATE_TIMESTAMP);
          }
        } else if (systemTimeUs - initializeSystemTimeUs > INITIALIZING_DURATION_US) {
          // We haven't received a timestamp for a while, so they probably aren't available for the
          // current audio route. Poll infrequently in case the route changes later.
          // TODO: Ideally we should listen for audio route changes in order to detect when a
          // timestamp becomes available again.
          updateState(STATE_NO_TIMESTAMP);
        }
        break;
      case STATE_TIMESTAMP:
        if (updatedTimestamp) {
          if (isTimestampAdvancingFromInitialTimestamp(systemTimeUs, audioTrackPlaybackSpeed)) {
            updateState(STATE_TIMESTAMP_ADVANCING);
          } else if (systemTimeUs - initializeSystemTimeUs > WAIT_FOR_ADVANCE_DURATION_US) {
            // Failed to find a correctly advancing timestamp. Only try again later after waiting
            // for SLOW_POLL_INTERVAL_US.
            updateState(STATE_NO_TIMESTAMP);
          } else {
            // Not yet advancing, try again with the latest timestamp as the initial one.
            initialTimestampPositionFrames = audioTimestamp.getTimestampPositionFrames();
            initialTimestampSystemTimeUs = audioTimestamp.getTimestampSystemTimeUs();
          }
        } else {
          reset();
        }
        break;
      case STATE_TIMESTAMP_ADVANCING:
        if (!updatedTimestamp) {
          // The audio route may have changed, so reset polling.
          reset();
        }
        break;
      case STATE_NO_TIMESTAMP:
        if (updatedTimestamp) {
          // The audio route may have changed, so reset polling.
          reset();
        }
        break;
      case STATE_ERROR:
        // Do nothing. If the caller accepts any new timestamp we'll reset polling.
        break;
      default:
        throw new IllegalStateException();
    }
  }

  /**
   * Returns whether this instance has an advancing timestamp. If {@code true}, call {@link
   * #getTimestampPositionUs(long, float)} to access the current timestamp.
   */
  public boolean hasAdvancingTimestamp() {
    return state == STATE_TIMESTAMP_ADVANCING;
  }

  /**
   * Returns whether this instance is waiting for an advancing timestamp. If {@code false}, there
   * either won't be a timestamp, an error occurred or there already is an {@linkplain
   * #hasAdvancingTimestamp() advancing timestamp}.
   */
  public boolean isWaitingForAdvancingTimestamp() {
    return state == STATE_INITIALIZING || state == STATE_TIMESTAMP;
  }

  /** Resets polling. Should be called whenever the audio track is paused or resumed. */
  public void reset() {
    updateState(STATE_INITIALIZING);
  }

  /**
   * If {@link #hasAdvancingTimestamp()} returns {@code true}, returns the latest timestamp position
   * in microseconds.
   *
   * @param systemTimeUs The current system time, in microseconds.
   * @param audioTrackPlaybackSpeed The playback speed of the audio track.
   */
  public long getTimestampPositionUs(long systemTimeUs, float audioTrackPlaybackSpeed) {
    return computeTimestampPositionUs(systemTimeUs, audioTrackPlaybackSpeed);
  }

  /**
   * Sets up the poller to expect a reset in audio track frame position due to an impending track
   * transition and reusing of the {@link AudioTrack}.
   */
  public void expectTimestampFramePositionReset() {
    audioTimestamp.expectTimestampFramePositionReset();
  }

  private void updateState(@State int state) {
    this.state = state;
    switch (state) {
      case STATE_INITIALIZING:
        // Force polling a timestamp immediately, and poll quickly.
        lastTimestampSampleTimeUs = 0;
        initialTimestampPositionFrames = C.INDEX_UNSET;
        initialTimestampSystemTimeUs = C.TIME_UNSET;
        initializeSystemTimeUs = System.nanoTime() / 1000;
        sampleIntervalUs = FAST_POLL_INTERVAL_US;
        break;
      case STATE_TIMESTAMP:
        sampleIntervalUs = FAST_POLL_INTERVAL_US;
        break;
      case STATE_TIMESTAMP_ADVANCING:
      case STATE_NO_TIMESTAMP:
        sampleIntervalUs = SLOW_POLL_INTERVAL_US;
        break;
      case STATE_ERROR:
        sampleIntervalUs = ERROR_POLL_INTERVAL_US;
        break;
      default:
        throw new IllegalStateException();
    }
  }

  private boolean isTimestampAdvancingFromInitialTimestamp(
      long systemTimeUs, float audioTrackPlaybackSpeed) {
    if (audioTimestamp.getTimestampPositionFrames() <= initialTimestampPositionFrames) {
      // Reported timestamp hasn't been updated.
      return false;
    }
    long positionEstimateUsingInitialTimestampUs =
        computeTimestampPositionUs(
            initialTimestampPositionFrames,
            initialTimestampSystemTimeUs,
            systemTimeUs,
            audioTrackPlaybackSpeed);
    long positionEstimateUsingCurrentTimestampUs =
        computeTimestampPositionUs(systemTimeUs, audioTrackPlaybackSpeed);
    long positionDriftUs =
        Math.abs(positionEstimateUsingCurrentTimestampUs - positionEstimateUsingInitialTimestampUs);
    return positionDriftUs < MAX_POSITION_DRIFT_ADVANCING_TIMESTAMP_US;
  }

  private long computeTimestampPositionUs(long systemTimeUs, float audioTrackPlaybackSpeed) {
    return computeTimestampPositionUs(
        audioTimestamp.getTimestampPositionFrames(),
        audioTimestamp.getTimestampSystemTimeUs(),
        systemTimeUs,
        audioTrackPlaybackSpeed);
  }

  private long computeTimestampPositionUs(
      long timestampPositionFrames,
      long timestampSystemTimeUs,
      long systemTimeUs,
      float audioTrackPlaybackSpeed) {
    long timestampPositionUs = sampleCountToDurationUs(timestampPositionFrames, sampleRate);
    long elapsedSinceTimestampUs = systemTimeUs - timestampSystemTimeUs;
    elapsedSinceTimestampUs =
        Util.getMediaDurationForPlayoutDuration(elapsedSinceTimestampUs, audioTrackPlaybackSpeed);
    return timestampPositionUs + elapsedSinceTimestampUs;
  }

  private void checkTimestampIsPlausibleAndUpdateErrorState(
      long systemTimeUs, float audioTrackPlaybackSpeed, long playbackHeadPositionEstimateUs) {
    long timestampSystemTimeUs = audioTimestamp.getTimestampSystemTimeUs();
    long timestampPositionUs = computeTimestampPositionUs(systemTimeUs, audioTrackPlaybackSpeed);
    if (Math.abs(timestampSystemTimeUs - systemTimeUs) > MAX_AUDIO_TIMESTAMP_OFFSET_US) {
      errorListener.onSystemTimeUsMismatch(
          audioTimestamp.getTimestampPositionFrames(),
          timestampSystemTimeUs,
          systemTimeUs,
          playbackHeadPositionEstimateUs);
      updateState(STATE_ERROR);
    } else if (Math.abs(timestampPositionUs - playbackHeadPositionEstimateUs)
        > MAX_AUDIO_TIMESTAMP_OFFSET_US) {
      errorListener.onPositionFramesMismatch(
          audioTimestamp.getTimestampPositionFrames(),
          timestampSystemTimeUs,
          systemTimeUs,
          playbackHeadPositionEstimateUs);
      updateState(STATE_ERROR);
    } else if (state == STATE_ERROR) {
      reset();
    }
  }

  private static final class AudioTimestampWrapper {

    private final AudioTrack audioTrack;
    private final AudioTimestamp audioTimestamp;

    private long rawTimestampFramePositionWrapCount;
    private long lastTimestampRawPositionFrames;
    private long lastTimestampPositionFrames;

    /**
     * Whether to expect a raw playback head reset.
     *
     * <p>When an {@link AudioTrack} is reused during offloaded playback, the {@link
     * AudioTimestamp#framePosition} is reset upon track transition. {@link AudioTimestampWrapper}
     * must be notified of the impending reset and keep track of total accumulated {@code
     * AudioTimestamp.framePosition}.
     */
    private boolean expectTimestampFramePositionReset;

    private long accumulatedRawTimestampFramePosition;

    /**
     * Creates a new {@link AudioTimestamp} wrapper.
     *
     * @param audioTrack The audio track that will provide timestamps.
     */
    public AudioTimestampWrapper(AudioTrack audioTrack) {
      this.audioTrack = audioTrack;
      audioTimestamp = new AudioTimestamp();
    }

    /**
     * Attempts to update the audio track timestamp. Returns {@code true} if the timestamp was
     * updated, in which case the updated timestamp system time and position can be accessed with
     * {@link #getTimestampSystemTimeUs()} and {@link #getTimestampPositionFrames()}. Returns {@code
     * false} if no timestamp is available, in which case those methods should not be called.
     */
    public boolean maybeUpdateTimestamp() {
      boolean updated = audioTrack.getTimestamp(audioTimestamp);
      if (updated) {
        long rawPositionFrames = audioTimestamp.framePosition;
        if (lastTimestampRawPositionFrames > rawPositionFrames) {
          if (expectTimestampFramePositionReset) {
            accumulatedRawTimestampFramePosition += lastTimestampRawPositionFrames;
            expectTimestampFramePositionReset = false;
          } else {
            // The value must have wrapped around.
            rawTimestampFramePositionWrapCount++;
          }
        }
        lastTimestampRawPositionFrames = rawPositionFrames;
        lastTimestampPositionFrames =
            rawPositionFrames
                + accumulatedRawTimestampFramePosition
                + (rawTimestampFramePositionWrapCount << 32);
      }
      return updated;
    }

    public long getTimestampSystemTimeUs() {
      return audioTimestamp.nanoTime / 1000;
    }

    public long getTimestampPositionFrames() {
      return lastTimestampPositionFrames;
    }

    public void expectTimestampFramePositionReset() {
      expectTimestampFramePositionReset = true;
    }
  }
}
