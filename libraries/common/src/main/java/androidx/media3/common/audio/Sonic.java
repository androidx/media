/*
 * Copyright (C) 2017 The Android Open Source Project
 * Copyright (C) 2010 Bill Cox, Sonic Library
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

import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;
import static java.lang.Math.min;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.checkerframework.checker.nullness.qual.NonNull;

/**
 * Sonic audio stream processor for time/pitch stretching.
 *
 * <p>Based on https://github.com/waywardgeek/sonic.
 */
/* package */ final class Sonic {

  private static final int MINIMUM_PITCH = 65;
  private static final int MAXIMUM_PITCH = 400;
  private static final int AMDF_FREQUENCY = 4000;

  private static final float MINIMUM_SPEEDUP_RATE = 1.00001f;
  private static final float MINIMUM_SLOWDOWN_RATE = 0.99999f;

  private final int inputSampleRateHz;
  private final int channelCount;
  private final float speed;
  private final float pitch;
  private final float rate;
  private final int minPeriod;
  private final int maxPeriod;
  private final int maxRequiredFrameCount;
  private final SonicImpl<?> impl;

  private int inputFrameCount;
  private int outputFrameCount;
  private int pitchFrameCount;
  private int oldRatePosition;
  private int newRatePosition;

  /**
   * Number of frames pending to be copied from {@link SonicImpl#getInputBuffer()} directly to
   * {@link SonicImpl#getOutputBuffer()}.
   *
   * <p>This field is only relevant to time-stretching or pitch-shifting in {@link
   * #changeSpeed(double)}, particularly when more frames need to be copied to the {@link
   * SonicImpl#getOutputBuffer()} than are available in {@link SonicImpl#getInputBuffer()} and Sonic
   * must wait until the next buffer (or EOS) is queued.
   */
  private int remainingInputToCopyFrameCount;

  private int prevPeriod;
  private double accumulatedSpeedAdjustmentError;

  /**
   * Returns the estimated output frame count for a given configuration and input frame count.
   *
   * <p>Please note that the returned value might not be mathematically exact, as Sonic incurs in
   * truncation and precision errors that accumulate on the output.
   */
  public static long getExpectedFrameCountAfterProcessorApplied(
      int inputSampleRateHz,
      int outputSampleRateHz,
      float speed,
      float pitch,
      long inputFrameCount) {
    float resamplingRate = (float) inputSampleRateHz / outputSampleRateHz;
    resamplingRate *= pitch;
    double speedRate = speed / pitch;
    BigDecimal bigResamplingRate = new BigDecimal(String.valueOf(resamplingRate));

    BigDecimal length = BigDecimal.valueOf(inputFrameCount);
    BigDecimal framesAfterTimeStretching;
    if (speedRate > MINIMUM_SPEEDUP_RATE || speedRate < MINIMUM_SLOWDOWN_RATE) {
      framesAfterTimeStretching =
          length.divide(BigDecimal.valueOf(speedRate), RoundingMode.HALF_EVEN);
    } else {
      // If speed is almost 1, then just copy the buffers without modifying them.
      framesAfterTimeStretching = length;
    }

    if (resamplingRate == 1.0f) {
      return framesAfterTimeStretching.longValueExact();
    }

    BigDecimal framesAfterResampling =
        framesAfterTimeStretching.divide(bigResamplingRate, RoundingMode.HALF_EVEN);

    return framesAfterResampling.longValueExact()
        - calculateAccumulatedTruncationErrorForResampling(
            framesAfterTimeStretching, BigDecimal.valueOf(inputSampleRateHz), bigResamplingRate);
  }

  /**
   * Returns expected accumulated truncation error for {@link Sonic}'s resampling algorithm, given
   * an input length, input sample rate, and resampling rate.
   *
   * <p><b>Note:</b> This method is only necessary until we address b/361768785 and fix the
   * underlying truncation issue.
   *
   * @param length Length of input in frames.
   * @param sampleRate Input sample rate of {@link Sonic} instance.
   * @param resamplingRate Resampling rate given by {@code pitch * (inputSampleRate /
   *     outputSampleRate)}.
   */
  /* package */ static long calculateAccumulatedTruncationErrorForResampling(
      BigDecimal length, BigDecimal sampleRate, BigDecimal resamplingRate) {
    // Calculate number of times that Sonic accumulates truncation error. Set scale to 20 decimal
    // places, so that division doesn't return an integer.
    BigDecimal errorCount = length.divide(sampleRate, /* scale= */ 20, RoundingMode.HALF_EVEN);

    // Calculate what truncation error Sonic is accumulating, calculated as:
    // inputSampleRate / resamplingRate - (int) inputSampleRate / resamplingRate. Set scale to 20
    // decimal places, so that division doesn't return an integer.
    BigDecimal individualError =
        sampleRate.divide(resamplingRate, /* scale */ 20, RoundingMode.HALF_EVEN);
    individualError =
        individualError.subtract(individualError.setScale(/* newScale= */ 0, RoundingMode.FLOOR));
    // Calculate total accumulated error = (int) floor(errorCount * individualError).
    BigDecimal accumulatedError =
        errorCount.multiply(individualError).setScale(/* newScale= */ 0, RoundingMode.FLOOR);

    return accumulatedError.longValueExact();
  }

  /**
   * Returns the number of input frames required for Sonic to produce the given number of output
   * frames under the specified parameters.
   *
   * <p>This method is the inverse of {@link #getExpectedFrameCountAfterProcessorApplied}.
   *
   * @param inputSampleRateHz Input sample rate in Hertz.
   * @param outputSampleRateHz Output sample rate in Hertz.
   * @param speed Speed rate.
   * @param pitch Pitch rate.
   * @param outputFrameCount Number of output frames to calculate the required input frame count of.
   */
  /* package */ static long getExpectedInputFrameCountForOutputFrameCount(
      int inputSampleRateHz,
      int outputSampleRateHz,
      float speed,
      float pitch,
      long outputFrameCount) {
    float resamplingRate = (float) inputSampleRateHz / outputSampleRateHz;
    resamplingRate *= pitch;
    BigDecimal bigResamplingRate = new BigDecimal(String.valueOf(resamplingRate));
    long framesBeforeResampling =
        getFrameCountBeforeResamplingForOutputCount(
            BigDecimal.valueOf(inputSampleRateHz),
            bigResamplingRate,
            BigDecimal.valueOf(outputFrameCount));
    double speedRate = speed / pitch;

    if (speedRate > MINIMUM_SPEEDUP_RATE || speedRate < MINIMUM_SLOWDOWN_RATE) {
      return BigDecimal.valueOf(framesBeforeResampling)
          .multiply(BigDecimal.valueOf(speedRate))
          .setScale(0, RoundingMode.FLOOR)
          .longValueExact();
    } else {
      // If speed is almost 1, then just copy the buffers without modifying them.
      return framesBeforeResampling;
    }
  }

  /**
   * Returns the expected input frame count prior to resampling with Sonic.
   *
   * <p>See {@link #getExpectedFrameCountAfterProcessorApplied} for more information.
   *
   * @param sampleRate Input sample rate of {@link Sonic} instance.
   * @param resamplingRate Resampling rate given by {@code (inputSampleRate / outputSampleRate) *
   *     pitch}.
   * @param outputLength Length of output in frames.
   */
  private static long getFrameCountBeforeResamplingForOutputCount(
      BigDecimal sampleRate, BigDecimal resamplingRate, BigDecimal outputLength) {
    BigDecimal denominator = sampleRate.divide(resamplingRate, /* scale */ 0, RoundingMode.FLOOR);
    BigDecimal numerator = sampleRate.multiply(outputLength);
    return numerator.divide(denominator, /* scale */ 0, RoundingMode.FLOOR).longValueExact();
  }

  /**
   * Creates a new Sonic audio stream processor.
   *
   * @param inputSampleRateHz The sample rate of input audio, in hertz.
   * @param channelCount The number of channels in the input audio.
   * @param speed The speedup factor for output audio.
   * @param pitch The pitch factor for output audio.
   * @param outputSampleRateHz The sample rate for output audio, in hertz.
   * @param useFloatSamples Whether the input stream contains float PCM samples or 16 bit PCM
   *     samples.
   */
  public Sonic(
      int inputSampleRateHz,
      int channelCount,
      float speed,
      float pitch,
      int outputSampleRateHz,
      boolean useFloatSamples) {
    this.inputSampleRateHz = inputSampleRateHz;
    this.channelCount = channelCount;
    this.speed = speed;
    this.pitch = pitch;
    rate = (float) inputSampleRateHz / outputSampleRateHz;
    minPeriod = inputSampleRateHz / MAXIMUM_PITCH;
    maxPeriod = inputSampleRateHz / MINIMUM_PITCH;
    maxRequiredFrameCount = 2 * maxPeriod;
    impl = useFloatSamples ? new SonicFloatImpl() : new SonicShortImpl();
  }

  /**
   * Returns the number of bytes that have been input, but will not be processed until more input
   * data is provided.
   */
  public int getPendingInputBytes() {
    return inputFrameCount * channelCount * impl.bytesPerSample();
  }

  /**
   * Queues remaining data from {@code buffer}, and advances its position by the number of bytes
   * consumed.
   *
   * @param buffer A {@link ByteBuffer} containing input data between its position and limit.
   */
  public void queueInput(ByteBuffer buffer) {
    int bytesToWrite = buffer.remaining();
    int framesToWrite = bytesToWrite / (channelCount * impl.bytesPerSample());
    impl.ensureAdditionalFramesInInputBuffer(framesToWrite);
    impl.copyBufferToInputBuffer(buffer, bytesToWrite);
    inputFrameCount += framesToWrite;
    processStreamInput();
  }

  /**
   * Writes available output starting from the {@linkplain ByteBuffer#position() buffer's position}
   * until no more output is available or the buffer's limit is reached. The buffer's position will
   * be advanced by the number of bytes written.
   *
   * @param buffer A {@link ByteBuffer} into which output will be written.
   */
  public void getOutput(ByteBuffer buffer) {
    checkState(outputFrameCount >= 0);
    int framesToRead =
        min(buffer.remaining() / (channelCount * impl.bytesPerSample()), outputFrameCount);
    impl.copyOutputToByteBuffer(buffer, framesToRead);
    outputFrameCount -= framesToRead;
    System.arraycopy(
        impl.getOutputBuffer(),
        framesToRead * channelCount,
        impl.getOutputBuffer(),
        0,
        outputFrameCount * channelCount);
  }

  /**
   * Forces generating output using whatever data has been queued already. No extra delay will be
   * added to the output, but flushing in the middle of words could introduce distortion.
   */
  public void queueEndOfStream() {
    int remainingFrameCount = inputFrameCount;
    double s = speed / pitch;
    double r = rate * pitch;

    // If there are frames to be copied directly onto the output buffer, we should not count those
    // as "input frames" because Sonic is not applying any processing on them.
    int adjustedRemainingFrames = remainingFrameCount - remainingInputToCopyFrameCount;

    // We add directly to the output the number of frames in remainingInputToCopyFrameCount.
    // Otherwise, expectedOutputFrames will be off and will make Sonic output an incorrect number of
    // frames.
    int expectedOutputFrames =
        outputFrameCount
            + (int)
                ((adjustedRemainingFrames / s
                            + remainingInputToCopyFrameCount
                            + accumulatedSpeedAdjustmentError
                            + pitchFrameCount)
                        / r
                    + 0.5);
    accumulatedSpeedAdjustmentError = 0;

    // Add enough silence to flush both input and pitch buffers.
    impl.ensureAdditionalFramesInInputBuffer(remainingFrameCount + 2 * maxRequiredFrameCount);
    impl.zeroInputBuffer(remainingFrameCount * channelCount, 2 * maxRequiredFrameCount);
    inputFrameCount += 2 * maxRequiredFrameCount;
    processStreamInput();
    // Throw away any extra frames we generated due to the silence we added.
    if (outputFrameCount > expectedOutputFrames) {
      // expectedOutputFrames might be negative, so set lower bound to 0.
      outputFrameCount = max(expectedOutputFrames, 0);
    }
    // Empty input and pitch buffers.
    inputFrameCount = 0;
    remainingInputToCopyFrameCount = 0;
    pitchFrameCount = 0;
  }

  /** Clears state in preparation for receiving a new stream of input buffers. */
  public void flush() {
    inputFrameCount = 0;
    outputFrameCount = 0;
    pitchFrameCount = 0;
    oldRatePosition = 0;
    newRatePosition = 0;
    remainingInputToCopyFrameCount = 0;
    prevPeriod = 0;
    accumulatedSpeedAdjustmentError = 0;
    impl.flush();
  }

  /** Returns the size of output that can be read with {@link #getOutput(ByteBuffer)}, in bytes. */
  public int getOutputSize() {
    checkState(outputFrameCount >= 0);
    return outputFrameCount * channelCount * impl.bytesPerSample();
  }

  // Internal methods.

  private void copyToOutput(int positionFrames, int frameCount) {
    impl.ensureAdditionalFramesInOutputBuffer(frameCount);
    System.arraycopy(
        impl.getInputBuffer(),
        positionFrames * channelCount,
        impl.getOutputBuffer(),
        outputFrameCount * channelCount,
        frameCount * channelCount);
    outputFrameCount += frameCount;
  }

  private int copyInputToOutput(int positionFrames) {
    int frameCount = min(maxRequiredFrameCount, remainingInputToCopyFrameCount);
    copyToOutput(positionFrames, frameCount);
    remainingInputToCopyFrameCount -= frameCount;
    return frameCount;
  }

  private int findPitchPeriod(int positionFrames) {
    // Find the pitch period. This is a critical step, and we may have to try multiple ways to get a
    // good answer. This version uses AMDF. To improve speed, we down sample by an integer factor
    // get in the 11 kHz range, and then do it again with a narrower frequency range without down
    // sampling.
    int period;
    int retPeriod;
    int skip = inputSampleRateHz > AMDF_FREQUENCY ? inputSampleRateHz / AMDF_FREQUENCY : 1;
    if (channelCount == 1 && skip == 1) {
      period = impl.findPitchPeriodInRangeWithInputBuffer(positionFrames, minPeriod, maxPeriod);
    } else {
      impl.downSampleInput(positionFrames, skip);
      period =
          impl.findPitchPeriodInRangeWithDownsampleBuffer(0, minPeriod / skip, maxPeriod / skip);
      if (skip != 1) {
        period *= skip;
        int minP = period - (skip * 4);
        int maxP = period + (skip * 4);
        if (minP < minPeriod) {
          minP = minPeriod;
        }
        if (maxP > maxPeriod) {
          maxP = maxPeriod;
        }
        if (channelCount == 1) {
          period = impl.findPitchPeriodInRangeWithInputBuffer(positionFrames, minP, maxP);
        } else {
          impl.downSampleInput(positionFrames, 1);
          period = impl.findPitchPeriodInRangeWithDownsampleBuffer(0, minP, maxP);
        }
      }
    }
    if (impl.isPreviousPeriodBetter()) {
      retPeriod = prevPeriod;
    } else {
      retPeriod = period;
    }
    impl.updatePreviousMinDiff();
    prevPeriod = period;
    return retPeriod;
  }

  private void adjustRate(float rate, int originalOutputFrameCount) {
    if (outputFrameCount == originalOutputFrameCount) {
      return;
    }

    // Use long to avoid overflows int-int multiplications. The actual value of newSampleRate and
    // oldSampleRate should always be comfortably within the int range.
    long newSampleRate = (long) (inputSampleRateHz / rate);
    long oldSampleRate = inputSampleRateHz;
    // Set these values to help with the integer math.
    while (newSampleRate != 0
        && oldSampleRate != 0
        && newSampleRate % 2 == 0
        && oldSampleRate % 2 == 0) {
      newSampleRate /= 2;
      oldSampleRate /= 2;
    }
    moveNewSamplesToPitchBuffer(originalOutputFrameCount);
    // Leave at least one pitch sample in the buffer.
    for (int position = 0; position < pitchFrameCount - 1; position++) {
      // Cast to long to avoid overflow.
      while ((oldRatePosition + 1) * newSampleRate > newRatePosition * oldSampleRate) {
        impl.ensureAdditionalFramesInOutputBuffer(/* additionalFrameCount= */ 1);
        impl.interpolateFrame(position, oldSampleRate, newSampleRate);
        newRatePosition++;
        outputFrameCount++;
      }
      oldRatePosition++;
      if (oldRatePosition == oldSampleRate) {
        oldRatePosition = 0;
        checkState(newRatePosition == newSampleRate);
        newRatePosition = 0;
      }
    }
    removePitchFrames(pitchFrameCount - 1);
  }

  private void moveNewSamplesToPitchBuffer(int originalOutputFrameCount) {
    int frameCount = outputFrameCount - originalOutputFrameCount;
    impl.ensureAdditionalFramesInPitchBuffer(frameCount);
    System.arraycopy(
        impl.getOutputBuffer(),
        originalOutputFrameCount * channelCount,
        impl.getPitchBuffer(),
        pitchFrameCount * channelCount,
        frameCount * channelCount);
    outputFrameCount = originalOutputFrameCount;
    pitchFrameCount += frameCount;
  }

  private void removePitchFrames(int frameCount) {
    if (frameCount == 0) {
      return;
    }
    System.arraycopy(
        impl.getPitchBuffer(),
        frameCount * channelCount,
        impl.getPitchBuffer(),
        0,
        (pitchFrameCount - frameCount) * channelCount);
    pitchFrameCount -= frameCount;
  }

  private int skipPitchPeriod(int position, double speed, int period) {
    // Skip over a pitch period, and copy period/speed samples to the output.
    int newFrameCount;
    if (speed >= 2.0f) {
      double expectedFrameCount = period / (speed - 1.0) + accumulatedSpeedAdjustmentError;
      newFrameCount = (int) Math.round(expectedFrameCount);
      accumulatedSpeedAdjustmentError = expectedFrameCount - newFrameCount;
    } else {
      newFrameCount = period;
      double expectedInputToCopy =
          period * (2.0f - speed) / (speed - 1.0f) + accumulatedSpeedAdjustmentError;
      remainingInputToCopyFrameCount = (int) Math.round(expectedInputToCopy);
      accumulatedSpeedAdjustmentError = expectedInputToCopy - remainingInputToCopyFrameCount;
    }
    impl.ensureAdditionalFramesInOutputBuffer(newFrameCount);
    impl.overlapAdd(newFrameCount, channelCount, outputFrameCount, position, position + period);
    outputFrameCount += newFrameCount;
    return newFrameCount;
  }

  private int insertPitchPeriod(int position, double speed, int period) {
    // Insert a pitch period, and determine how much input to copy directly.
    int newFrameCount;
    if (speed < 0.5f) {
      double expectedFrameCount = period * speed / (1.0f - speed) + accumulatedSpeedAdjustmentError;
      newFrameCount = (int) Math.round(expectedFrameCount);
      accumulatedSpeedAdjustmentError = expectedFrameCount - newFrameCount;
    } else {
      newFrameCount = period;
      double expectedInputToCopy =
          period * (2.0f * speed - 1.0f) / (1.0f - speed) + accumulatedSpeedAdjustmentError;
      remainingInputToCopyFrameCount = (int) Math.round(expectedInputToCopy);
      accumulatedSpeedAdjustmentError = expectedInputToCopy - remainingInputToCopyFrameCount;
    }
    impl.ensureAdditionalFramesInOutputBuffer(period + newFrameCount);
    System.arraycopy(
        impl.getInputBuffer(),
        position * channelCount,
        impl.getOutputBuffer(),
        outputFrameCount * channelCount,
        period * channelCount);
    impl.overlapAdd(
        newFrameCount, channelCount, outputFrameCount + period, position + period, position);
    outputFrameCount += period + newFrameCount;
    return newFrameCount;
  }

  private void changeSpeed(double speed) {
    if (inputFrameCount < maxRequiredFrameCount) {
      return;
    }
    int frameCount = inputFrameCount;
    int positionFrames = 0;
    do {
      if (remainingInputToCopyFrameCount > 0) {
        positionFrames += copyInputToOutput(positionFrames);
      } else {
        int period = findPitchPeriod(positionFrames);
        if (speed > 1.0) {
          positionFrames += period + skipPitchPeriod(positionFrames, speed, period);
        } else {
          positionFrames += insertPitchPeriod(positionFrames, speed, period);
        }
      }
    } while (positionFrames + maxRequiredFrameCount <= frameCount);
    removeProcessedInputFrames(positionFrames);
  }

  private void removeProcessedInputFrames(int positionFrames) {
    int remainingFrames = inputFrameCount - positionFrames;
    System.arraycopy(
        impl.getInputBuffer(),
        positionFrames * channelCount,
        impl.getInputBuffer(),
        0,
        remainingFrames * channelCount);
    inputFrameCount = remainingFrames;
  }

  private void processStreamInput() {
    // Resample as many pitch periods as we have buffered on the input.
    int originalOutputFrameCount = outputFrameCount;
    double s = speed / pitch;
    float r = rate * pitch;
    if (s > MINIMUM_SPEEDUP_RATE || s < MINIMUM_SLOWDOWN_RATE) {
      changeSpeed(s);
    } else {
      copyToOutput(0, inputFrameCount);
      inputFrameCount = 0;
    }
    if (r != 1.0f) {
      adjustRate(r, originalOutputFrameCount);
    }
  }

  /** Interface that exposes sample format-specific operations to {@link Sonic}. */
  private interface SonicImpl<T extends @NonNull Object> {

    /** Returns the number of bytes in a sample. */
    int bytesPerSample();

    /**
     * Interpolates two contiguous frames in the {@linkplain #getPitchBuffer() pitch buffer} at
     * {@code positionFrames} and writes the result to the {@linkplain #getOutputBuffer() output
     * buffer}.
     *
     * @param positionFrames The position in frames of the first frame to interpolate from the pitch
     *     buffer.
     * @param oldSampleRate The old sample rate.
     * @param newSampleRate The new sample rate.
     */
    void interpolateFrame(int positionFrames, long oldSampleRate, long newSampleRate);

    /**
     * Returns whether the previous pitch period estimate is a better approximation, which can occur
     * at the abrupt end of voiced words.
     */
    boolean isPreviousPeriodBetter();

    /**
     * Downsamples {@link #maxRequiredFrameCount} / {@code skip} frames from the input buffer onto
     * the downsampling buffer.
     *
     * @param positionFrames The position in frames from which to start downsampling the input
     *     buffer.
     * @param skip The number of frames to skip per downsampled frame.
     */
    void downSampleInput(int positionFrames, int skip);

    /**
     * Returns the pitch period within {@code [minPeriod; maxPeriod]} at {@code positionFrames} of
     * the downsample buffer.
     */
    int findPitchPeriodInRangeWithDownsampleBuffer(
        int positionFrames, int minPeriod, int maxPeriod);

    /**
     * Returns the pitch period within {@code [minPeriod; maxPeriod]} at {@code positionFrames} of
     * the input buffer.
     */
    int findPitchPeriodInRangeWithInputBuffer(int positionFrames, int minPeriod, int maxPeriod);

    /** Clears state in preparation for receiving a new stream of input buffers. */
    void flush();

    /**
     * Overlap-adds {@code frameCount} frames to the output buffer from {@code rampDownPosition} and
     * {@code rampUpPosition} in the input buffer.
     *
     * @param frameCount The number of frames to overlap-add.
     * @param channelCount The number of channels in a frame.
     * @param outPosition The starting position in the output buffer to write to.
     * @param rampDownPosition The starting position in the input buffer to ramp down.
     * @param rampUpPosition The starting position in the input buffer to ramp up.
     */
    void overlapAdd(
        int frameCount,
        int channelCount,
        int outPosition,
        int rampDownPosition,
        int rampUpPosition);

    /**
     * Updates the previous minimum diff with the current diff.
     *
     * @see #findPitchPeriod(int)
     */
    void updatePreviousMinDiff();

    /**
     * Adds {@code additionalFrameCount} frames to the input buffer if there are less than {@code
     * additionalFrameCount} available frames.
     */
    void ensureAdditionalFramesInInputBuffer(int additionalFrameCount);

    /**
     * Adds {@code additionalFrameCount} frames to the output buffer if there are less than {@code
     * additionalFrameCount} available frames.
     */
    void ensureAdditionalFramesInOutputBuffer(int additionalFrameCount);

    /**
     * Adds {@code additionalFrameCount} frames to the pitch buffer if there are less than {@code
     * additionalFrameCount} available frames.
     */
    void ensureAdditionalFramesInPitchBuffer(int additionalFrameCount);

    /** Zeroes the input buffer from {@code startPosition} to {@code startPosition + length}. */
    void zeroInputBuffer(int startPosition, int length);

    /** Copies {@code bytesToWrite} bytes from {@code buffer} onto the input buffer. */
    void copyBufferToInputBuffer(ByteBuffer buffer, int bytesToWrite);

    /** Copies {@code framesToRead} frames from the output buffer onto {@code buffer}. */
    void copyOutputToByteBuffer(ByteBuffer buffer, int framesToRead);

    /** Returns the input buffer. */
    T getInputBuffer();

    /** Returns the output buffer. */
    T getOutputBuffer();

    /** Returns the pitch buffer. */
    T getPitchBuffer();
  }

  private final class SonicFloatImpl implements SonicImpl<float[]> {
    private final float[] downSampleBuffer;

    private float[] inputBuffer;
    private float[] outputBuffer;
    private float[] pitchBuffer;

    private double minDiff;
    private double maxDiff;
    private double prevMinDiff;

    /** Implementation of {@link SonicImpl} for float PCM samples. */
    SonicFloatImpl() {
      downSampleBuffer = new float[maxRequiredFrameCount];
      inputBuffer = new float[maxRequiredFrameCount * channelCount];
      outputBuffer = new float[maxRequiredFrameCount * channelCount];
      pitchBuffer = new float[maxRequiredFrameCount * channelCount];
    }

    @Override
    public int bytesPerSample() {
      return 4;
    }

    @Override
    public void interpolateFrame(int positionFrame, long oldSampleRate, long newSampleRate) {
      for (int i = 0; i < channelCount; i++) {
        outputBuffer[outputFrameCount * channelCount + i] =
            interpolate(
                pitchBuffer, positionFrame * channelCount + i, oldSampleRate, newSampleRate);
      }
    }

    @Override
    public boolean isPreviousPeriodBetter() {
      if (minDiff == 0 || prevPeriod == 0) {
        return false;
      }
      if (maxDiff > minDiff * 3) {
        // Got a reasonable match this period.
        return false;
      }
      if (minDiff * 2 <= prevMinDiff * 3) {
        // Mismatch is not that much greater this period.
        return false;
      }
      return true;
    }

    @Override
    public void downSampleInput(int positionFrames, int skip) {
      // If skip is greater than one, average skip samples together and write them to the
      // down-sample
      // buffer. If channelCount is greater than one, mix the channels together as we down sample.
      int frameCount = maxRequiredFrameCount / skip;
      int samplesPerValue = channelCount * skip;
      positionFrames *= channelCount;
      for (int i = 0; i < frameCount; i++) {
        double value = 0;
        for (int j = 0; j < samplesPerValue; j++) {
          value += inputBuffer[positionFrames + i * samplesPerValue + j];
        }
        value /= samplesPerValue;
        downSampleBuffer[i] = (float) value;
      }
    }

    @Override
    public int findPitchPeriodInRangeWithDownsampleBuffer(
        int positionFrames, int minPeriod, int maxPeriod) {
      return findPitchPeriodInRange(downSampleBuffer, positionFrames, minPeriod, maxPeriod);
    }

    @Override
    public int findPitchPeriodInRangeWithInputBuffer(
        int positionFrames, int minPeriod, int maxPeriod) {
      return findPitchPeriodInRange(inputBuffer, positionFrames, minPeriod, maxPeriod);
    }

    @Override
    public void flush() {
      prevMinDiff = 0;
      minDiff = 0;
      maxDiff = 0;
    }

    @Override
    public void overlapAdd(
        int frameCount,
        int channelCount,
        int outPosition,
        int rampDownPosition,
        int rampUpPosition) {
      overlapAdd(
          frameCount,
          channelCount,
          outputBuffer,
          outPosition,
          inputBuffer,
          rampDownPosition,
          inputBuffer,
          rampUpPosition);
    }

    private void overlapAdd(
        int frameCount,
        int channelCount,
        float[] out,
        int outPosition,
        float[] rampDown,
        int rampDownPosition,
        float[] rampUp,
        int rampUpPosition) {
      for (int i = 0; i < channelCount; i++) {
        int o = outPosition * channelCount + i;
        int u = rampUpPosition * channelCount + i;
        int d = rampDownPosition * channelCount + i;
        for (int t = 0; t < frameCount; t++) {
          out[o] = (rampDown[d] * (frameCount - t) + rampUp[u] * t) / frameCount;
          o += channelCount;
          d += channelCount;
          u += channelCount;
        }
      }
    }

    @Override
    public void updatePreviousMinDiff() {
      prevMinDiff = minDiff;
    }

    @Override
    public void ensureAdditionalFramesInInputBuffer(int additionalFrameCount) {
      inputBuffer =
          ensureSpaceForAdditionalFrames(inputBuffer, inputFrameCount, additionalFrameCount);
    }

    @Override
    public void ensureAdditionalFramesInOutputBuffer(int additionalFrameCount) {
      outputBuffer =
          ensureSpaceForAdditionalFrames(outputBuffer, outputFrameCount, additionalFrameCount);
    }

    @Override
    public void ensureAdditionalFramesInPitchBuffer(int additionalFrameCount) {
      pitchBuffer =
          ensureSpaceForAdditionalFrames(pitchBuffer, pitchFrameCount, additionalFrameCount);
    }

    @Override
    public void zeroInputBuffer(int startPosition, int length) {
      for (int i = 0; i < length * channelCount; i++) {
        inputBuffer[startPosition + i] = 0;
      }
    }

    @Override
    public void copyBufferToInputBuffer(ByteBuffer buffer, int bytesToWrite) {
      buffer
          .asFloatBuffer()
          .get(inputBuffer, inputFrameCount * channelCount, bytesToWrite / bytesPerSample());
      buffer.position(buffer.position() + bytesToWrite);
    }

    @Override
    public void copyOutputToByteBuffer(ByteBuffer buffer, int framesToRead) {
      buffer.asFloatBuffer().put(outputBuffer, 0, framesToRead * channelCount);
      buffer.position(buffer.position() + framesToRead * bytesPerSample() * channelCount);
    }

    @Override
    public float[] getInputBuffer() {
      return inputBuffer;
    }

    @Override
    public float[] getOutputBuffer() {
      return outputBuffer;
    }

    @Override
    public float[] getPitchBuffer() {
      return pitchBuffer;
    }

    private float interpolate(float[] in, int inPos, long oldSampleRate, long newSampleRate) {
      float left = in[inPos];
      float right = in[inPos + channelCount];
      long position = newRatePosition * oldSampleRate;
      long leftPosition = oldRatePosition * newSampleRate;
      long rightPosition = (oldRatePosition + 1) * newSampleRate;
      long ratio = rightPosition - position;
      long width = rightPosition - leftPosition;
      return (ratio * left + (width - ratio) * right) / width;
    }

    private int findPitchPeriodInRange(
        float[] samples, int positionFrames, int minPeriod, int maxPeriod) {
      // Find the best frequency match in the range, and given a sample skip multiple. For now, just
      // find the pitch of the first channel.
      int bestPeriod = 0;
      int worstPeriod = 255;
      double minDiff = 1;
      double maxDiff = 0;
      positionFrames *= channelCount;
      for (int period = minPeriod; period <= maxPeriod; period++) {
        double diff = 0;
        for (int i = 0; i < period; i++) {
          float sVal = samples[positionFrames + i];
          float pVal = samples[positionFrames + period + i];
          diff += Math.abs(sVal - pVal);
        }
        if (diff * bestPeriod < minDiff * period) {
          minDiff = diff;
          bestPeriod = period;
        }
        if (diff * worstPeriod > maxDiff * period) {
          maxDiff = diff;
          worstPeriod = period;
        }
      }
      this.minDiff = minDiff / bestPeriod;
      this.maxDiff = maxDiff / worstPeriod;
      return bestPeriod;
    }

    /**
     * Returns {@code buffer} or a copy of it, such that there is enough space in the returned
     * buffer to store {@code newFrameCount} additional frames.
     *
     * @param buffer The buffer.
     * @param frameCount The number of frames already in the buffer.
     * @param additionalFrameCount The number of additional frames that need to be stored in the
     *     buffer.
     * @return A buffer with enough space for the additional frames.
     */
    private float[] ensureSpaceForAdditionalFrames(
        float[] buffer, int frameCount, int additionalFrameCount) {
      int currentCapacityFrames = buffer.length / channelCount;
      if (frameCount + additionalFrameCount <= currentCapacityFrames) {
        return buffer;
      } else {
        int newCapacityFrames = 3 * currentCapacityFrames / 2 + additionalFrameCount;
        return Arrays.copyOf(buffer, newCapacityFrames * channelCount);
      }
    }
  }

  /** Implementation of {@link SonicImpl} for 16 bit PCM samples. */
  private final class SonicShortImpl implements SonicImpl<short[]> {

    private final short[] downSampleBuffer;

    private short[] inputBuffer;
    private short[] outputBuffer;
    private short[] pitchBuffer;

    private int minDiff;
    private int maxDiff;
    private int prevMinDiff;

    SonicShortImpl() {
      downSampleBuffer = new short[maxRequiredFrameCount];
      inputBuffer = new short[maxRequiredFrameCount * channelCount];
      outputBuffer = new short[maxRequiredFrameCount * channelCount];
      pitchBuffer = new short[maxRequiredFrameCount * channelCount];
    }

    @Override
    public int bytesPerSample() {
      return 2;
    }

    @Override
    public void interpolateFrame(int positionFrames, long oldSampleRate, long newSampleRate) {
      for (int i = 0; i < channelCount; i++) {
        outputBuffer[outputFrameCount * channelCount + i] =
            interpolate(
                pitchBuffer, positionFrames * channelCount + i, oldSampleRate, newSampleRate);
      }
    }

    @Override
    public boolean isPreviousPeriodBetter() {
      if (minDiff == 0 || prevPeriod == 0) {
        return false;
      }
      if (maxDiff > minDiff * 3) {
        // Got a reasonable match this period.
        return false;
      }
      if (minDiff * 2 <= prevMinDiff * 3) {
        // Mismatch is not that much greater this period.
        return false;
      }
      return true;
    }

    @Override
    public void downSampleInput(int positionFrames, int skip) {
      short[] samples = inputBuffer;
      // If skip is greater than one, average skip samples together and write them to the
      // down-sample
      // buffer. If channelCount is greater than one, mix the channels together as we down sample.
      int frameCount = maxRequiredFrameCount / skip;
      int samplesPerValue = channelCount * skip;
      positionFrames *= channelCount;
      for (int i = 0; i < frameCount; i++) {
        int value = 0;
        for (int j = 0; j < samplesPerValue; j++) {
          value += samples[positionFrames + i * samplesPerValue + j];
        }
        value /= samplesPerValue;
        downSampleBuffer[i] = (short) value;
      }
    }

    @Override
    public int findPitchPeriodInRangeWithDownsampleBuffer(
        int positionFrames, int minPeriod, int maxPeriod) {
      return findPitchPeriodInRange(downSampleBuffer, positionFrames, minPeriod, maxPeriod);
    }

    @Override
    public int findPitchPeriodInRangeWithInputBuffer(
        int positionFrames, int minPeriod, int maxPeriod) {
      return findPitchPeriodInRange(inputBuffer, positionFrames, minPeriod, maxPeriod);
    }

    @Override
    public void flush() {
      prevMinDiff = 0;
      minDiff = 0;
      maxDiff = 0;
    }

    @Override
    public void overlapAdd(
        int frameCount,
        int channelCount,
        int outPosition,
        int rampDownPosition,
        int rampUpPosition) {
      overlapAdd(
          frameCount,
          channelCount,
          outputBuffer,
          outPosition,
          inputBuffer,
          rampDownPosition,
          inputBuffer,
          rampUpPosition);
    }

    private void overlapAdd(
        int frameCount,
        int channelCount,
        short[] out,
        int outPosition,
        short[] rampDown,
        int rampDownPosition,
        short[] rampUp,
        int rampUpPosition) {
      for (int i = 0; i < channelCount; i++) {
        int o = outPosition * channelCount + i;
        int u = rampUpPosition * channelCount + i;
        int d = rampDownPosition * channelCount + i;
        for (int t = 0; t < frameCount; t++) {
          out[o] = (short) ((rampDown[d] * (frameCount - t) + rampUp[u] * t) / frameCount);
          o += channelCount;
          d += channelCount;
          u += channelCount;
        }
      }
    }

    @Override
    public void updatePreviousMinDiff() {
      prevMinDiff = minDiff;
    }

    @Override
    public void ensureAdditionalFramesInInputBuffer(int additionalFrameCount) {
      inputBuffer =
          ensureSpaceForAdditionalFrames(inputBuffer, inputFrameCount, additionalFrameCount);
    }

    @Override
    public void ensureAdditionalFramesInOutputBuffer(int additionalFrameCount) {
      outputBuffer =
          ensureSpaceForAdditionalFrames(outputBuffer, outputFrameCount, additionalFrameCount);
    }

    @Override
    public void ensureAdditionalFramesInPitchBuffer(int additionalFrameCount) {
      pitchBuffer =
          ensureSpaceForAdditionalFrames(pitchBuffer, pitchFrameCount, additionalFrameCount);
    }

    @Override
    public void zeroInputBuffer(int startPosition, int length) {
      for (int i = 0; i < length * channelCount; i++) {
        inputBuffer[startPosition + i] = 0;
      }
    }

    @Override
    public void copyBufferToInputBuffer(ByteBuffer buffer, int bytesToWrite) {
      buffer.asShortBuffer().get(inputBuffer, inputFrameCount * channelCount, bytesToWrite / 2);
      buffer.position(buffer.position() + bytesToWrite);
    }

    @Override
    public void copyOutputToByteBuffer(ByteBuffer buffer, int framesToRead) {
      buffer.asShortBuffer().put(outputBuffer, 0, framesToRead * channelCount);
      buffer.position(buffer.position() + framesToRead * bytesPerSample() * channelCount);
    }

    @Override
    public short[] getInputBuffer() {
      return inputBuffer;
    }

    @Override
    public short[] getOutputBuffer() {
      return outputBuffer;
    }

    @Override
    public short[] getPitchBuffer() {
      return pitchBuffer;
    }

    private int findPitchPeriodInRange(
        short[] samples, int positionFrames, int minPeriod, int maxPeriod) {
      // Find the best frequency match in the range, and given a sample skip multiple. For now, just
      // find the pitch of the first channel.
      int bestPeriod = 0;
      int worstPeriod = 255;
      int minDiff = 1;
      int maxDiff = 0;
      positionFrames *= channelCount;
      for (int period = minPeriod; period <= maxPeriod; period++) {
        int diff = 0;
        for (int i = 0; i < period; i++) {
          short sVal = samples[positionFrames + i];
          short pVal = samples[positionFrames + period + i];
          diff += Math.abs(sVal - pVal);
        }
        // Note that the highest number of samples we add into diff will be less than 256, since we
        // skip samples. Thus, diff is a 24 bit number, and we can safely multiply by numSamples
        // without overflow.
        if (diff * bestPeriod < minDiff * period) {
          minDiff = diff;
          bestPeriod = period;
        }
        if (diff * worstPeriod > maxDiff * period) {
          maxDiff = diff;
          worstPeriod = period;
        }
      }
      this.minDiff = minDiff / bestPeriod;
      this.maxDiff = maxDiff / worstPeriod;
      return bestPeriod;
    }

    private short interpolate(short[] in, int inPos, long oldSampleRate, long newSampleRate) {
      short left = in[inPos];
      short right = in[inPos + channelCount];
      long position = newRatePosition * oldSampleRate;
      long leftPosition = oldRatePosition * newSampleRate;
      long rightPosition = (oldRatePosition + 1) * newSampleRate;
      long ratio = rightPosition - position;
      long width = rightPosition - leftPosition;
      return (short) ((ratio * left + (width - ratio) * right) / width);
    }

    /**
     * Returns {@code buffer} or a copy of it, such that there is enough space in the returned
     * buffer to store {@code newFrameCount} additional frames.
     *
     * @param buffer The buffer.
     * @param frameCount The number of frames already in the buffer.
     * @param additionalFrameCount The number of additional frames that need to be stored in the
     *     buffer.
     * @return A buffer with enough space for the additional frames.
     */
    private short[] ensureSpaceForAdditionalFrames(
        short[] buffer, int frameCount, int additionalFrameCount) {
      int currentCapacityFrames = buffer.length / channelCount;
      if (frameCount + additionalFrameCount <= currentCapacityFrames) {
        return buffer;
      } else {
        int newCapacityFrames = 3 * currentCapacityFrames / 2 + additionalFrameCount;
        return Arrays.copyOf(buffer, newCapacityFrames * channelCount);
      }
    }
  }
}
