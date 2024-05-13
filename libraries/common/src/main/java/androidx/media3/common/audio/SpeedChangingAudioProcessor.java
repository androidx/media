/*
 * Copyright 2022 The Android Open Source Project
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

package androidx.media3.common.audio;

import static androidx.media3.common.util.Assertions.checkArgument;
import static java.lang.Math.min;
import static java.lang.Math.round;

import androidx.annotation.GuardedBy;
import androidx.media3.common.C;
import androidx.media3.common.util.LongArray;
import androidx.media3.common.util.LongArrayQueue;
import androidx.media3.common.util.SpeedProviderUtil;
import androidx.media3.common.util.TimestampConsumer;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.LongConsumer;
import org.checkerframework.checker.initialization.qual.UnknownInitialization;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;

/**
 * An {@link AudioProcessor} that changes the speed of audio samples depending on their timestamp.
 */
// TODO(b/288221200): Consider making the processor inactive and skipping it in the processor chain
//  when speed is 1.
@UnstableApi
public final class SpeedChangingAudioProcessor extends BaseAudioProcessor {

  /** The speed provider that provides the speed for each timestamp. */
  private final SpeedProvider speedProvider;

  /**
   * The {@link SonicAudioProcessor} used to change the speed, when needed. If there is no speed
   * change required, the input buffer is copied to the output buffer and this processor is not
   * used.
   */
  private final SonicAudioProcessor sonicAudioProcessor;

  private final Object pendingCallbacksLock;

  // Elements in the same positions in the queues are associated.
  @GuardedBy("pendingCallbacksLock")
  private final LongArrayQueue pendingCallbackInputTimesUs;

  @GuardedBy("pendingCallbacksLock")
  private final Queue<TimestampConsumer> pendingCallbacks;

  // Elements in the same positions in the arrays are associated.
  private LongArray inputSegmentStartTimesUs;
  private LongArray outputSegmentStartTimesUs;

  private float currentSpeed;
  private long bytesRead;
  private long lastProcessedInputTimeUs;
  private long lastSpeedAdjustedInputTimeUs;
  private long lastSpeedAdjustedOutputTimeUs;
  private boolean endOfStreamQueuedToSonic;

  @GuardedBy("pendingCallbacksLock")
  private long speedAdjustedTimeAsyncInputTimeUs;

  public SpeedChangingAudioProcessor(SpeedProvider speedProvider) {
    this.speedProvider = speedProvider;
    sonicAudioProcessor = new SonicAudioProcessor();
    pendingCallbacksLock = new Object();
    pendingCallbackInputTimesUs = new LongArrayQueue();
    pendingCallbacks = new ArrayDeque<>();
    speedAdjustedTimeAsyncInputTimeUs = C.TIME_UNSET;
    resetState();
  }

  @Override
  public long getDurationAfterProcessorApplied(long durationUs) {
    return SpeedProviderUtil.getDurationAfterSpeedProviderApplied(speedProvider, durationUs);
  }

  @Override
  public AudioFormat onConfigure(AudioFormat inputAudioFormat)
      throws UnhandledAudioFormatException {
    return sonicAudioProcessor.configure(inputAudioFormat);
  }

  @Override
  public void queueInput(ByteBuffer inputBuffer) {
    long timeUs =
        Util.scaleLargeTimestamp(
            /* timestamp= */ bytesRead,
            /* multiplier= */ C.MICROS_PER_SECOND,
            /* divisor= */ (long) inputAudioFormat.sampleRate * inputAudioFormat.bytesPerFrame);
    float newSpeed = speedProvider.getSpeed(timeUs);
    if (newSpeed != currentSpeed) {
      updateSpeedChangeArrays(timeUs);
      currentSpeed = newSpeed;
      if (isUsingSonic()) {
        sonicAudioProcessor.setSpeed(newSpeed);
        sonicAudioProcessor.setPitch(newSpeed);
      }
      // Invalidate any previously created buffers in SonicAudioProcessor and the base class.
      sonicAudioProcessor.flush();
      endOfStreamQueuedToSonic = false;
      super.getOutput();
    }

    int inputBufferLimit = inputBuffer.limit();
    long nextSpeedChangeTimeUs = speedProvider.getNextSpeedChangeTimeUs(timeUs);
    int bytesToNextSpeedChange;
    if (nextSpeedChangeTimeUs != C.TIME_UNSET) {
      bytesToNextSpeedChange =
          (int)
              Util.scaleLargeValue(
                  /* timestamp= */ nextSpeedChangeTimeUs - timeUs,
                  /* multiplier= */ (long) inputAudioFormat.sampleRate
                      * inputAudioFormat.bytesPerFrame,
                  /* divisor= */ C.MICROS_PER_SECOND,
                  RoundingMode.CEILING);
      int bytesToNextFrame =
          inputAudioFormat.bytesPerFrame - bytesToNextSpeedChange % inputAudioFormat.bytesPerFrame;
      if (bytesToNextFrame != inputAudioFormat.bytesPerFrame) {
        bytesToNextSpeedChange += bytesToNextFrame;
      }
      // Update the input buffer limit to make sure that all samples processed have the same speed.
      inputBuffer.limit(min(inputBufferLimit, inputBuffer.position() + bytesToNextSpeedChange));
    } else {
      bytesToNextSpeedChange = C.LENGTH_UNSET;
    }

    long startPosition = inputBuffer.position();
    if (isUsingSonic()) {
      sonicAudioProcessor.queueInput(inputBuffer);
      if (bytesToNextSpeedChange != C.LENGTH_UNSET
          && (inputBuffer.position() - startPosition) == bytesToNextSpeedChange) {
        sonicAudioProcessor.queueEndOfStream();
        endOfStreamQueuedToSonic = true;
      }
    } else {
      ByteBuffer buffer = replaceOutputBuffer(/* size= */ inputBuffer.remaining());
      if (inputBuffer.hasRemaining()) {
        buffer.put(inputBuffer);
      }
      buffer.flip();
    }
    bytesRead += inputBuffer.position() - startPosition;
    lastProcessedInputTimeUs = updateLastProcessedInputTime();
    inputBuffer.limit(inputBufferLimit);
  }

  @Override
  protected void onQueueEndOfStream() {
    if (!endOfStreamQueuedToSonic) {
      sonicAudioProcessor.queueEndOfStream();
      endOfStreamQueuedToSonic = true;
    }
  }

  @Override
  public ByteBuffer getOutput() {
    ByteBuffer output = isUsingSonic() ? sonicAudioProcessor.getOutput() : super.getOutput();
    processPendingCallbacks();
    return output;
  }

  @Override
  public boolean isEnded() {
    return super.isEnded() && sonicAudioProcessor.isEnded();
  }

  @Override
  protected void onFlush() {
    resetState();
    sonicAudioProcessor.flush();
  }

  @Override
  protected void onReset() {
    resetState();
    sonicAudioProcessor.reset();
  }

  /**
   * Calculates the time at which the {@code inputTimeUs} is outputted at after the speed changes
   * has been applied.
   *
   * <p>Calls {@linkplain LongConsumer#accept(long) the callback} with the output time as soon as
   * enough audio has been processed to calculate it.
   *
   * <p>If the audio processor has ended, speeds will come out at the last processed speed of the
   * audio processor.
   *
   * <p>Successive calls must have monotonically increasing {@code inputTimeUs}.
   *
   * <p>Can be called from any thread.
   *
   * @param inputTimeUs The input time, in microseconds.
   * @param callback The callback called with the output time. May be called on a different thread
   *     from the caller of this method.
   */
  public void getSpeedAdjustedTimeAsync(long inputTimeUs, TimestampConsumer callback) {
    synchronized (pendingCallbacksLock) {
      checkArgument(speedAdjustedTimeAsyncInputTimeUs < inputTimeUs);
      speedAdjustedTimeAsyncInputTimeUs = inputTimeUs;
      if ((inputTimeUs <= lastProcessedInputTimeUs && pendingCallbackInputTimesUs.isEmpty())
          || isEnded()) {
        callback.onTimestamp(calculateSpeedAdjustedTime(inputTimeUs));
        return;
      }
      pendingCallbackInputTimesUs.add(inputTimeUs);
      pendingCallbacks.add(callback);
    }
  }

  /**
   * Returns the input media duration for the given playout duration.
   *
   * <p>Both durations are counted from the last {@link #reset()} or {@link #flush()} of the audio
   * processor.
   *
   * <p>The {@code playoutDurationUs} must be less than last processed buffer output time.
   *
   * @param playoutDurationUs The playout duration in microseconds.
   * @return The corresponding input duration in microseconds.
   */
  public long getMediaDurationUs(long playoutDurationUs) {
    int floorIndex = outputSegmentStartTimesUs.size() - 1;
    while (floorIndex > 0 && outputSegmentStartTimesUs.get(floorIndex) > playoutDurationUs) {
      floorIndex--;
    }
    long lastSegmentOutputDurationUs =
        playoutDurationUs - outputSegmentStartTimesUs.get(floorIndex);
    long lastSegmentInputDurationUs;
    if (floorIndex == outputSegmentStartTimesUs.size() - 1) {
      lastSegmentInputDurationUs = getMediaDurationUsAtCurrentSpeed(lastSegmentOutputDurationUs);

    } else {
      lastSegmentInputDurationUs =
          round(
              lastSegmentOutputDurationUs
                  * divide(
                      inputSegmentStartTimesUs.get(floorIndex + 1)
                          - inputSegmentStartTimesUs.get(floorIndex),
                      outputSegmentStartTimesUs.get(floorIndex + 1)
                          - outputSegmentStartTimesUs.get(floorIndex)));
    }
    return inputSegmentStartTimesUs.get(floorIndex) + lastSegmentInputDurationUs;
  }

  /**
   * Assuming enough audio has been processed, calculates the time at which the {@code inputTimeUs}
   * is outputted at after the speed changes has been applied.
   */
  private long calculateSpeedAdjustedTime(long inputTimeUs) {
    int floorIndex = inputSegmentStartTimesUs.size() - 1;
    while (floorIndex > 0 && inputSegmentStartTimesUs.get(floorIndex) > inputTimeUs) {
      floorIndex--;
    }
    long lastSegmentOutputDurationUs;
    if (floorIndex == inputSegmentStartTimesUs.size() - 1) {
      if (lastSpeedAdjustedInputTimeUs < inputSegmentStartTimesUs.get(floorIndex)) {
        lastSpeedAdjustedInputTimeUs = inputSegmentStartTimesUs.get(floorIndex);
        lastSpeedAdjustedOutputTimeUs = outputSegmentStartTimesUs.get(floorIndex);
      }
      long lastSegmentInputDurationUs = inputTimeUs - lastSpeedAdjustedInputTimeUs;
      lastSegmentOutputDurationUs = getPlayoutDurationUsAtCurrentSpeed(lastSegmentInputDurationUs);
    } else {
      long lastSegmentInputDurationUs = inputTimeUs - lastSpeedAdjustedInputTimeUs;
      lastSegmentOutputDurationUs =
          round(
              lastSegmentInputDurationUs
                  * divide(
                      outputSegmentStartTimesUs.get(floorIndex + 1)
                          - outputSegmentStartTimesUs.get(floorIndex),
                      inputSegmentStartTimesUs.get(floorIndex + 1)
                          - inputSegmentStartTimesUs.get(floorIndex)));
    }
    lastSpeedAdjustedInputTimeUs = inputTimeUs;
    lastSpeedAdjustedOutputTimeUs += lastSegmentOutputDurationUs;
    return lastSpeedAdjustedOutputTimeUs;
  }

  private static double divide(long dividend, long divisor) {
    return ((double) dividend) / divisor;
  }

  private void processPendingCallbacks() {
    synchronized (pendingCallbacksLock) {
      while (!pendingCallbacks.isEmpty()
          && (pendingCallbackInputTimesUs.element() <= lastProcessedInputTimeUs || isEnded())) {
        pendingCallbacks
            .remove()
            .onTimestamp(calculateSpeedAdjustedTime(pendingCallbackInputTimesUs.remove()));
      }
    }
  }

  private void updateSpeedChangeArrays(long currentSpeedChangeInputTimeUs) {
    long lastSpeedChangeOutputTimeUs =
        outputSegmentStartTimesUs.get(outputSegmentStartTimesUs.size() - 1);
    long lastSpeedChangeInputTimeUs =
        inputSegmentStartTimesUs.get(inputSegmentStartTimesUs.size() - 1);
    long lastSpeedSegmentMediaDurationUs =
        currentSpeedChangeInputTimeUs - lastSpeedChangeInputTimeUs;
    inputSegmentStartTimesUs.add(currentSpeedChangeInputTimeUs);
    outputSegmentStartTimesUs.add(
        lastSpeedChangeOutputTimeUs
            + getPlayoutDurationUsAtCurrentSpeed(lastSpeedSegmentMediaDurationUs));
  }

  private long getPlayoutDurationUsAtCurrentSpeed(long mediaDurationUs) {
    return isUsingSonic()
        ? sonicAudioProcessor.getPlayoutDuration(mediaDurationUs)
        : mediaDurationUs;
  }

  private long getMediaDurationUsAtCurrentSpeed(long playoutDurationUs) {
    return isUsingSonic()
        ? sonicAudioProcessor.getMediaDuration(playoutDurationUs)
        : playoutDurationUs;
  }

  private long updateLastProcessedInputTime() {
    if (isUsingSonic()) {
      // TODO - b/320242819: Investigate whether bytesRead can be used here rather than
      //  sonicAudioProcessor.getProcessedInputBytes().
      long currentProcessedInputDurationUs =
          Util.scaleLargeTimestamp(
              /* timestamp= */ sonicAudioProcessor.getProcessedInputBytes(),
              /* multiplier= */ C.MICROS_PER_SECOND,
              /* divisor= */ (long) inputAudioFormat.sampleRate * inputAudioFormat.bytesPerFrame);
      return inputSegmentStartTimesUs.get(inputSegmentStartTimesUs.size() - 1)
          + currentProcessedInputDurationUs;
    }
    return Util.scaleLargeTimestamp(
        /* timestamp= */ bytesRead,
        /* multiplier= */ C.MICROS_PER_SECOND,
        /* divisor= */ (long) inputAudioFormat.sampleRate * inputAudioFormat.bytesPerFrame);
  }

  private boolean isUsingSonic() {
    return currentSpeed != 1f;
  }

  @EnsuresNonNull({"inputSegmentStartTimesUs", "outputSegmentStartTimesUs"})
  private void resetState(@UnknownInitialization SpeedChangingAudioProcessor this) {
    currentSpeed = 1f;
    bytesRead = 0;
    inputSegmentStartTimesUs = new LongArray();
    outputSegmentStartTimesUs = new LongArray();
    inputSegmentStartTimesUs.add(0);
    outputSegmentStartTimesUs.add(0);
    lastProcessedInputTimeUs = 0;
    lastSpeedAdjustedInputTimeUs = 0;
    lastSpeedAdjustedOutputTimeUs = 0;
    endOfStreamQueuedToSonic = false;
    // TODO: b/339842724 - This should ideally also reset speedAdjustedTimeAsyncInputTimeUs and
    //  clear pendingCallbacks and pendingCallbacksInputTimes. We can't do this at the moment
    //  because some clients register callbacks with getSpeedAdjustedTimeAsync before this audio
    //  processor is flushed.
  }
}
