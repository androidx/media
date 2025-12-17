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

import static android.media.AudioTrack.PLAYSTATE_PLAYING;
import static android.media.AudioTrack.PLAYSTATE_STOPPED;
import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.util.Util.castNonNull;
import static androidx.media3.common.util.Util.durationUsToSampleCount;
import static androidx.media3.common.util.Util.getMediaDurationForPlayoutDuration;
import static androidx.media3.common.util.Util.msToUs;
import static androidx.media3.common.util.Util.sampleCountToDurationUs;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.media.AudioTrack;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.Util;
import java.lang.reflect.Method;

/**
 * Wraps an {@link AudioTrack}, exposing a position based on {@link
 * AudioTrack#getPlaybackHeadPosition()} and {@link
 * AudioTrack#getTimestamp(android.media.AudioTimestamp)}.
 *
 * <p>Call {@link #start()} immediately before calling {@link AudioTrack#play()}. Call {@link
 * #pause()} when pausing the track. Call {@link #handleEndOfStream(long)} when no more data will be
 * written to the track. When the audio track is flushed, call {@link #reset()}.
 */
/* package */ final class AudioTrackPositionTracker {

  /** Listener for position tracker events. */
  public interface Listener {

    /**
     * Called when the position tracker's position has increased for the first time since it was
     * last paused or reset.
     *
     * @param playoutStartSystemTimeMs The approximate derived {@link System#currentTimeMillis()} at
     *     which playout started.
     */
    void onPositionAdvancing(long playoutStartSystemTimeMs);

    /**
     * Called when the frame position is too far from the expected frame position.
     *
     * @param audioTimestampPositionFrames The frame position of the last known audio track
     *     timestamp.
     * @param audioTimestampSystemTimeUs The system time associated with the last known audio track
     *     timestamp, in microseconds.
     * @param systemTimeUs The current time.
     * @param playbackPositionUs The current playback head position in microseconds.
     */
    void onPositionFramesMismatch(
        long audioTimestampPositionFrames,
        long audioTimestampSystemTimeUs,
        long systemTimeUs,
        long playbackPositionUs);

    /**
     * Called when the system time associated with the last known audio track timestamp is
     * unexpectedly far from the current time.
     *
     * @param audioTimestampPositionFrames The frame position of the last known audio track
     *     timestamp.
     * @param audioTimestampSystemTimeUs The system time associated with the last known audio track
     *     timestamp, in microseconds.
     * @param systemTimeUs The current time.
     * @param playbackPositionUs The current playback head position in microseconds.
     */
    void onSystemTimeUsMismatch(
        long audioTimestampPositionFrames,
        long audioTimestampSystemTimeUs,
        long systemTimeUs,
        long playbackPositionUs);

    /**
     * Called when the audio track has provided an invalid latency.
     *
     * @param latencyUs The reported latency in microseconds.
     */
    void onInvalidLatency(long latencyUs);
  }

  /**
   * AudioTrack latencies are deemed impossibly large if they are greater than this amount.
   *
   * <p>This is a fail safe that should not be required on correctly functioning devices.
   */
  private static final long MAX_LATENCY_US = 10 * C.MICROS_PER_SECOND;

  /**
   * The maximum offset between the expected position and the reported position to attempt
   * smoothing.
   */
  private static final long MAX_POSITION_DRIFT_FOR_SMOOTHING_US = C.MICROS_PER_SECOND;

  /** The maximum allowed speed change to smooth out position drift in percent. */
  private static final int MAX_POSITION_SMOOTHING_SPEED_CHANGE_PERCENT = 10;

  /** Minimum update interval for getting the raw playback head position, in milliseconds. */
  private static final long RAW_PLAYBACK_HEAD_POSITION_UPDATE_INTERVAL_MS = 5;

  private static final long FORCE_RESET_WORKAROUND_TIMEOUT_MS = 200;

  private static final int MAX_PLAYHEAD_OFFSET_COUNT = 10;
  private static final int MIN_PLAYHEAD_OFFSET_SAMPLE_INTERVAL_US = 30_000;
  private static final int MIN_LATENCY_SAMPLE_INTERVAL_US = 50_0000;

  private final Listener listener;
  private final Clock clock;
  private final long[] playheadOffsets;
  private final AudioTrack audioTrack;
  private final int outputSampleRate;
  private final long bufferSizeUs;
  private final boolean isOutputPcm;

  private AudioTimestampPoller audioTimestampPoller;
  private float audioTrackPlaybackSpeed;
  private long onPositionAdvancingFromPositionUs;

  private long smoothedPlayheadOffsetUs;
  private long lastPlayheadSampleTimeUs;

  @Nullable private Method getLatencyMethod;
  private long latencyUs;

  private long lastLatencySampleTimeUs;
  private long lastRawPlaybackHeadPositionSampleTimeMs;
  private long rawPlaybackHeadPosition;
  private long rawPlaybackHeadWrapCount;
  private int nextPlayheadOffsetIndex;
  private int playheadOffsetCount;
  private long stopTimestampUs;
  private long forceResetWorkaroundTimeMs;
  private long stopPlaybackHeadPosition;
  private long endPlaybackHeadPosition;

  // Results from the previous call to getCurrentPositionUs.
  private long lastPositionUs;
  private long lastSystemTimeUs;

  /**
   * Whether to expect a raw playback head reset.
   *
   * <p>When an {@link AudioTrack} is reused during offloaded playback, rawPlaybackHeadPosition is
   * reset upon track transition. {@link AudioTrackPositionTracker} must be notified of the
   * impending reset and keep track of total accumulated rawPlaybackHeadPosition.
   */
  private boolean expectRawPlaybackHeadReset;

  private long sumRawPlaybackHeadPosition;

  /**
   * Creates a new audio track position tracker.
   *
   * @param listener A listener for position tracking events.
   * @param clock The {@link Clock}.
   * @param audioTrack The audio track to wrap.
   * @param outputEncoding The encoding of the audio track.
   * @param outputPcmFrameSize For PCM output encodings, the frame size. The value is ignored
   *     otherwise.
   * @param bufferSize The audio track buffer size in bytes.
   */
  public AudioTrackPositionTracker(
      Listener listener,
      Clock clock,
      AudioTrack audioTrack,
      @C.Encoding int outputEncoding,
      int outputPcmFrameSize,
      int bufferSize) {
    this.listener = checkNotNull(listener);
    this.clock = clock;
    this.audioTrack = audioTrack;
    try {
      getLatencyMethod = AudioTrack.class.getMethod("getLatency", (Class<?>[]) null);
    } catch (NoSuchMethodException e) {
      // There's no guarantee this method exists. Do nothing.
    }
    playheadOffsets = new long[MAX_PLAYHEAD_OFFSET_COUNT];
    lastSystemTimeUs = C.TIME_UNSET;
    lastPositionUs = C.TIME_UNSET;
    audioTimestampPoller = new AudioTimestampPoller(audioTrack, listener);
    outputSampleRate = audioTrack.getSampleRate();
    isOutputPcm = Util.isEncodingLinearPcm(outputEncoding);
    bufferSizeUs =
        isOutputPcm
            ? sampleCountToDurationUs(bufferSize / outputPcmFrameSize, outputSampleRate)
            : C.TIME_UNSET;
    rawPlaybackHeadPosition = 0;
    rawPlaybackHeadWrapCount = 0;
    expectRawPlaybackHeadReset = false;
    sumRawPlaybackHeadPosition = 0;
    stopTimestampUs = C.TIME_UNSET;
    forceResetWorkaroundTimeMs = C.TIME_UNSET;
    lastLatencySampleTimeUs = 0;
    latencyUs = 0;
    audioTrackPlaybackSpeed = 1f;
    onPositionAdvancingFromPositionUs = C.TIME_UNSET;
  }

  public void setAudioTrackPlaybackSpeed(float audioTrackPlaybackSpeed) {
    this.audioTrackPlaybackSpeed = audioTrackPlaybackSpeed;
    // Extrapolation from the last audio timestamp relies on the audio rate being constant, so we
    // reset audio timestamp tracking and wait for a new timestamp.
    audioTimestampPoller.reset();
    resetSyncParams();
  }

  public long getCurrentPositionUs() {
    AudioTrack audioTrack = checkNotNull(this.audioTrack);
    if (audioTrack.getPlayState() == PLAYSTATE_PLAYING) {
      maybeSampleSyncParams();
    }

    // If the device supports it, use the playback timestamp from AudioTrack.getTimestamp.
    // Otherwise, derive a smoothed position by sampling the track's frame position.
    long systemTimeUs = clock.nanoTime() / 1000;
    boolean useGetTimestampMode = audioTimestampPoller.hasAdvancingTimestamp();
    long positionUs =
        useGetTimestampMode
            ? audioTimestampPoller.getTimestampPositionUs(systemTimeUs, audioTrackPlaybackSpeed)
            : getPlaybackHeadPositionEstimateUs(systemTimeUs);

    int audioTrackPlayState = audioTrack.getPlayState();
    if (audioTrackPlayState == PLAYSTATE_PLAYING) {
      if (useGetTimestampMode || !audioTimestampPoller.isWaitingForAdvancingTimestamp()) {
        // Assume the new position is reliable to estimate the playout start time once we have an
        // advancing timestamp from the AudioTimestampPoller, or we stopped waiting for it.
        maybeTriggerOnPositionAdvancingCallback(positionUs);
      }

      if (lastSystemTimeUs != C.TIME_UNSET) {
        // Only try to smooth if actively playing and having a previous sample to compare with.
        long elapsedSystemTimeUs = systemTimeUs - lastSystemTimeUs;
        long positionDiffUs = positionUs - lastPositionUs;
        long expectedPositionDiffUs =
            getMediaDurationForPlayoutDuration(elapsedSystemTimeUs, audioTrackPlaybackSpeed);
        long expectedPositionUs = lastPositionUs + expectedPositionDiffUs;
        long positionDriftUs = Math.abs(expectedPositionUs - positionUs);
        if (positionDiffUs != 0 && positionDriftUs < MAX_POSITION_DRIFT_FOR_SMOOTHING_US) {
          // Ignore updates without moving position (e.g. stuck audio, not yet started audio). Also
          // ignore updates where the smoothing would take too long and it's preferable to jump to
          // the new timestamp immediately.
          long maxAllowedDriftUs =
              expectedPositionDiffUs * MAX_POSITION_SMOOTHING_SPEED_CHANGE_PERCENT / 100;
          positionUs =
              Util.constrainValue(
                  positionUs,
                  expectedPositionUs - maxAllowedDriftUs,
                  expectedPositionUs + maxAllowedDriftUs);
        }
      }

      lastSystemTimeUs = systemTimeUs;
      lastPositionUs = positionUs;
    } else if (audioTrackPlayState == PLAYSTATE_STOPPED) {
      // Once stopped, the position is simulated anyway and we don't need to wait for the timestamp
      // poller to produce reliable data.
      maybeTriggerOnPositionAdvancingCallback(positionUs);
    }

    return positionUs;
  }

  /** Starts position tracking. Must be called immediately before {@link AudioTrack#play()}. */
  public void start() {
    if (stopTimestampUs != C.TIME_UNSET) {
      stopTimestampUs = msToUs(clock.elapsedRealtime());
    }
    onPositionAdvancingFromPositionUs = getPlaybackHeadPositionUs();
    audioTimestampPoller.reset();
  }

  /** Returns whether the audio track is in the playing state. */
  public boolean isPlaying() {
    return checkNotNull(audioTrack).getPlayState() == PLAYSTATE_PLAYING;
  }

  /** Returns whether the track is in an invalid state and must be recreated. */
  public boolean isStalled(long writtenFrames) {
    return forceResetWorkaroundTimeMs != C.TIME_UNSET
        && writtenFrames > 0
        && clock.elapsedRealtime() - forceResetWorkaroundTimeMs
            >= FORCE_RESET_WORKAROUND_TIMEOUT_MS;
  }

  /**
   * Records the writing position at which the stream ended, so that the reported position can
   * continue to increment while remaining data is played out.
   *
   * @param writtenFrames The number of frames that have been written.
   */
  public void handleEndOfStream(long writtenFrames) {
    stopPlaybackHeadPosition = getPlaybackHeadPosition();
    stopTimestampUs = msToUs(clock.elapsedRealtime());
    endPlaybackHeadPosition = writtenFrames;
  }

  /** Pauses the audio track position tracker. */
  public void pause() {
    resetSyncParams();
    if (stopTimestampUs == C.TIME_UNSET) {
      // The audio track is going to be paused, so reset the timestamp poller to ensure it doesn't
      // supply an advancing position.
      audioTimestampPoller.reset();
    }
    stopPlaybackHeadPosition = getPlaybackHeadPosition();
  }

  /**
   * Sets up the position tracker to expect a reset in raw playback head position due to reusing an
   * {@link AudioTrack} and an impending track transition.
   */
  public void expectRawPlaybackHeadReset() {
    expectRawPlaybackHeadReset = true;
    audioTimestampPoller.expectTimestampFramePositionReset();
  }

  /** Resets the position tracker. Should be called when the audio track is flushed. */
  public void reset() {
    resetSyncParams();
    audioTimestampPoller = new AudioTimestampPoller(audioTrack, listener);
    rawPlaybackHeadPosition = 0;
    rawPlaybackHeadWrapCount = 0;
    expectRawPlaybackHeadReset = false;
    sumRawPlaybackHeadPosition = 0;
    stopTimestampUs = C.TIME_UNSET;
    forceResetWorkaroundTimeMs = C.TIME_UNSET;
    lastLatencySampleTimeUs = 0;
    latencyUs = 0;
    audioTrackPlaybackSpeed = 1f;
    onPositionAdvancingFromPositionUs = C.TIME_UNSET;
  }

  private void maybeTriggerOnPositionAdvancingCallback(long positionUs) {
    if (onPositionAdvancingFromPositionUs == C.TIME_UNSET
        || positionUs < onPositionAdvancingFromPositionUs) {
      return;
    }
    long mediaDurationSinceResumeUs = positionUs - onPositionAdvancingFromPositionUs;
    long playoutDurationSinceLastPositionUs =
        Util.getPlayoutDurationForMediaDuration(
            mediaDurationSinceResumeUs, audioTrackPlaybackSpeed);
    long playoutStartSystemTimeMs =
        clock.currentTimeMillis() - Util.usToMs(playoutDurationSinceLastPositionUs);
    onPositionAdvancingFromPositionUs = C.TIME_UNSET;
    listener.onPositionAdvancing(playoutStartSystemTimeMs);
  }

  private void maybeSampleSyncParams() {
    long systemTimeUs = clock.nanoTime() / 1000;
    if (systemTimeUs - lastPlayheadSampleTimeUs >= MIN_PLAYHEAD_OFFSET_SAMPLE_INTERVAL_US) {
      long playbackPositionUs = getPlaybackHeadPositionUs();
      if (playbackPositionUs == 0) {
        // The AudioTrack hasn't output anything yet.
        return;
      }
      // Take a new sample and update the smoothed offset between the system clock and the playhead.
      playheadOffsets[nextPlayheadOffsetIndex] =
          Util.getPlayoutDurationForMediaDuration(playbackPositionUs, audioTrackPlaybackSpeed)
              - systemTimeUs;
      nextPlayheadOffsetIndex = (nextPlayheadOffsetIndex + 1) % MAX_PLAYHEAD_OFFSET_COUNT;
      if (playheadOffsetCount < MAX_PLAYHEAD_OFFSET_COUNT) {
        playheadOffsetCount++;
      }
      lastPlayheadSampleTimeUs = systemTimeUs;
      smoothedPlayheadOffsetUs = 0;
      for (int i = 0; i < playheadOffsetCount; i++) {
        smoothedPlayheadOffsetUs += playheadOffsets[i] / playheadOffsetCount;
      }
    }

    boolean latencyUpdated = maybeUpdateLatency(systemTimeUs);

    audioTimestampPoller.maybePollTimestamp(
        systemTimeUs,
        audioTrackPlaybackSpeed,
        getPlaybackHeadPositionEstimateUs(systemTimeUs),
        /* forceUpdate= */ latencyUpdated);
  }

  private boolean maybeUpdateLatency(long systemTimeUs) {
    long previousLatencyUs = latencyUs;
    if (isOutputPcm
        && getLatencyMethod != null
        && systemTimeUs - lastLatencySampleTimeUs >= MIN_LATENCY_SAMPLE_INTERVAL_US) {
      try {
        // Compute the audio track latency, excluding the latency due to the buffer (leaving
        // latency due to the mixer and audio hardware driver).
        latencyUs =
            castNonNull((Integer) getLatencyMethod.invoke(checkNotNull(audioTrack))) * 1000L
                - bufferSizeUs;
        // Check that the latency is non-negative.
        latencyUs = max(latencyUs, 0);
        // Check that the latency isn't too large.
        if (latencyUs > MAX_LATENCY_US) {
          listener.onInvalidLatency(latencyUs);
          latencyUs = 0;
        }
      } catch (Exception e) {
        // The method existed, but doesn't work. Don't try again.
        getLatencyMethod = null;
      }
      lastLatencySampleTimeUs = systemTimeUs;
    }
    return previousLatencyUs != latencyUs;
  }

  private long getPlaybackHeadPositionEstimateUs(long systemTimeUs) {
    long positionUs;
    if (playheadOffsetCount == 0) {
      // The AudioTrack has started, but we don't have any samples to compute a smoothed position.
      positionUs =
          stopTimestampUs != C.TIME_UNSET
              ? sampleCountToDurationUs(
                  getSimulatedPlaybackHeadPositionAfterStop(), outputSampleRate)
              : getPlaybackHeadPositionUs();
    } else {
      // getPlaybackHeadPositionUs() only has a granularity of ~20 ms, so we base the position off
      // the system clock (and a smoothed offset between it and the playhead position) so as to
      // prevent jitter in the reported positions.
      positionUs =
          Util.getMediaDurationForPlayoutDuration(
              systemTimeUs + smoothedPlayheadOffsetUs, audioTrackPlaybackSpeed);
    }

    positionUs = max(0, positionUs - latencyUs);
    if (stopTimestampUs != C.TIME_UNSET) {
      positionUs =
          min(sampleCountToDurationUs(endPlaybackHeadPosition, outputSampleRate), positionUs);
    }
    return positionUs;
  }

  private void resetSyncParams() {
    smoothedPlayheadOffsetUs = 0;
    playheadOffsetCount = 0;
    nextPlayheadOffsetIndex = 0;
    lastPlayheadSampleTimeUs = 0;
    lastPositionUs = C.TIME_UNSET;
    lastSystemTimeUs = C.TIME_UNSET;
  }

  private long getPlaybackHeadPositionUs() {
    return sampleCountToDurationUs(getPlaybackHeadPosition(), outputSampleRate);
  }

  /**
   * {@link AudioTrack#getPlaybackHeadPosition()} returns a value intended to be interpreted as an
   * unsigned 32 bit integer, which also wraps around periodically. This method returns the playback
   * head position as a long that will only wrap around if the value exceeds {@link Long#MAX_VALUE}
   * (which in practice will never happen).
   *
   * @return The playback head position, in frames.
   */
  private long getPlaybackHeadPosition() {
    if (stopTimestampUs != C.TIME_UNSET) {
      long simulatedPlaybackHeadPositionAfterStop = getSimulatedPlaybackHeadPositionAfterStop();
      return min(endPlaybackHeadPosition, simulatedPlaybackHeadPositionAfterStop);
    }
    long currentTimeMs = clock.elapsedRealtime();
    if (currentTimeMs - lastRawPlaybackHeadPositionSampleTimeMs
        >= RAW_PLAYBACK_HEAD_POSITION_UPDATE_INTERVAL_MS) {
      updateRawPlaybackHeadPosition(currentTimeMs);
      lastRawPlaybackHeadPositionSampleTimeMs = currentTimeMs;
    }
    return rawPlaybackHeadPosition + sumRawPlaybackHeadPosition + (rawPlaybackHeadWrapCount << 32);
  }

  private long getSimulatedPlaybackHeadPositionAfterStop() {
    if (checkNotNull(this.audioTrack).getPlayState() == AudioTrack.PLAYSTATE_PAUSED) {
      // If AudioTrack is paused while stopping, then return cached playback head position.
      return stopPlaybackHeadPosition;
    }
    // Simulate the playback head position up to the total number of frames submitted.
    long elapsedTimeSinceStopUs = msToUs(clock.elapsedRealtime()) - stopTimestampUs;
    long mediaTimeSinceStopUs =
        Util.getMediaDurationForPlayoutDuration(elapsedTimeSinceStopUs, audioTrackPlaybackSpeed);
    long framesSinceStop = durationUsToSampleCount(mediaTimeSinceStopUs, outputSampleRate);
    return stopPlaybackHeadPosition + framesSinceStop;
  }

  private void updateRawPlaybackHeadPosition(long currentTimeMs) {
    AudioTrack audioTrack = checkNotNull(this.audioTrack);
    int state = audioTrack.getPlayState();
    if (state == PLAYSTATE_STOPPED) {
      // The audio track hasn't been started. Keep initial zero timestamp.
      return;
    }
    long rawPlaybackHeadPosition = 0xFFFFFFFFL & audioTrack.getPlaybackHeadPosition();
    if (SDK_INT <= 29) {
      if (rawPlaybackHeadPosition == 0
          && this.rawPlaybackHeadPosition > 0
          && state == PLAYSTATE_PLAYING) {
        // If connecting a Bluetooth audio device fails, the AudioTrack may be left in a state
        // where its Java API is in the playing state, but the native track is stopped. When this
        // happens the playback head position gets stuck at zero. In this case, return the old
        // playback head position and force the track to be reset after
        // {@link #FORCE_RESET_WORKAROUND_TIMEOUT_MS} has elapsed.
        if (forceResetWorkaroundTimeMs == C.TIME_UNSET) {
          forceResetWorkaroundTimeMs = currentTimeMs;
        }
        return;
      } else {
        forceResetWorkaroundTimeMs = C.TIME_UNSET;
      }
    }

    if (this.rawPlaybackHeadPosition > rawPlaybackHeadPosition) {
      if (expectRawPlaybackHeadReset) {
        sumRawPlaybackHeadPosition += this.rawPlaybackHeadPosition;
        expectRawPlaybackHeadReset = false;
      } else {
        // The value must have wrapped around.
        rawPlaybackHeadWrapCount++;
      }
    }
    this.rawPlaybackHeadPosition = rawPlaybackHeadPosition;
  }
}
