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

import static java.lang.Math.min;
import static java.lang.Math.round;

import androidx.media3.common.C;
import androidx.media3.common.util.LongArray;
import androidx.media3.common.util.LongArrayQueue;
import androidx.media3.common.util.SpeedProviderUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.LongConsumer;

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
  private final LongArrayQueue pendingCallbackInputTimesUs;
  private final Queue<LongConsumer> pendingCallbacks;

  // Elements in the same positions in the arrays are associated.
  private final LongArray inputSegmentStartTimesUs;
  private final LongArray outputSegmentStartTimesUs;

  private float currentSpeed;
  private long bytesRead;
  private long lastProcessedInputTime;
  private boolean endOfStreamQueuedToSonic;

  public SpeedChangingAudioProcessor(SpeedProvider speedProvider) {
    this.speedProvider = speedProvider;
    sonicAudioProcessor = new SonicAudioProcessor();
    pendingCallbacksLock = new Object();
    pendingCallbackInputTimesUs = new LongArrayQueue();
    pendingCallbacks = new ArrayDeque<>();
    inputSegmentStartTimesUs = new LongArray();
    outputSegmentStartTimesUs = new LongArray();
    inputSegmentStartTimesUs.add(0);
    outputSegmentStartTimesUs.add(0);
    currentSpeed = 1f;
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
      flush();
    }

    int inputBufferLimit = inputBuffer.limit();
    long nextSpeedChangeTimeUs = speedProvider.getNextSpeedChangeTimeUs(timeUs);
    int bytesToNextSpeedChange;
    if (nextSpeedChangeTimeUs != C.TIME_UNSET) {
      bytesToNextSpeedChange =
          (int)
              Util.scaleLargeTimestamp(
                  /* timestamp= */ nextSpeedChangeTimeUs - timeUs,
                  /* multiplier= */ (long) inputAudioFormat.sampleRate
                      * inputAudioFormat.bytesPerFrame,
                  /* divisor= */ C.MICROS_PER_SECOND);
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
    lastProcessedInputTime = updateLastProcessedInputTime();
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
    sonicAudioProcessor.flush();
    endOfStreamQueuedToSonic = false;
  }

  @Override
  protected void onReset() {
    currentSpeed = 1f;
    bytesRead = 0;
    sonicAudioProcessor.reset();
    endOfStreamQueuedToSonic = false;
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
  public void getSpeedAdjustedTimeAsync(long inputTimeUs, LongConsumer callback) {
    synchronized (pendingCallbacksLock) {
      if (inputTimeUs <= lastProcessedInputTime || isEnded()) {
        callback.accept(calculateSpeedAdjustedTime(inputTimeUs));
        return;
      }
      pendingCallbackInputTimesUs.add(inputTimeUs);
      pendingCallbacks.add(callback);
    }
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
    long lastSegmentInputDuration = inputTimeUs - inputSegmentStartTimesUs.get(floorIndex);
    long lastSegmentOutputDuration;
    if (floorIndex == inputSegmentStartTimesUs.size() - 1) {
      lastSegmentOutputDuration = getPlayoutDurationAtCurrentSpeed(lastSegmentInputDuration);
    } else {
      lastSegmentOutputDuration =
          round(
              lastSegmentInputDuration
                  * divide(
                      outputSegmentStartTimesUs.get(floorIndex + 1)
                          - outputSegmentStartTimesUs.get(floorIndex),
                      inputSegmentStartTimesUs.get(floorIndex + 1)
                          - inputSegmentStartTimesUs.get(floorIndex)));
    }
    return outputSegmentStartTimesUs.get(floorIndex) + lastSegmentOutputDuration;
  }

  private static double divide(long dividend, long divisor) {
    return ((double) dividend) / divisor;
  }

  private void processPendingCallbacks() {
    synchronized (pendingCallbacksLock) {
      while (!pendingCallbacks.isEmpty()
          && (pendingCallbackInputTimesUs.element() <= lastProcessedInputTime || isEnded())) {
        pendingCallbacks
            .remove()
            .accept(calculateSpeedAdjustedTime(pendingCallbackInputTimesUs.remove()));
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
            + getPlayoutDurationAtCurrentSpeed(lastSpeedSegmentMediaDurationUs));
  }

  private long getPlayoutDurationAtCurrentSpeed(long mediaDuration) {
    return isUsingSonic() ? sonicAudioProcessor.getPlayoutDuration(mediaDuration) : mediaDuration;
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
}
