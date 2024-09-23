/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.Random;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

/** Parameterized robolectric test for {@link Sonic}. */
@RunWith(ParameterizedRobolectricTestRunner.class)
public final class RandomParameterizedSonicTest {

  private static final int BLOCK_SIZE = 4096;
  private static final int BYTES_PER_SAMPLE = 2;
  private static final int SAMPLE_RATE = 48000;
  // Max 10 min streams.
  private static final long MAX_LENGTH_SAMPLES = 10 * 60 * SAMPLE_RATE;

  /** Defines how many random instances of each parameter the test runner should generate. */
  private static final int PARAM_COUNT = 5;

  private static final int SPEED_DECIMAL_PRECISION = 2;
  private static final ImmutableList<Range<Float>> SPEED_RANGES =
      ImmutableList.of(
          Range.closedOpen(0f, 1f), Range.closedOpen(1f, 2f), Range.closedOpen(2f, 20f));

  private static final Random random = new Random(/* seed */ 0);

  private static final ImmutableList<Object[]> sParams = initParams();

  @Parameters(name = "speed={0}, streamLength={1}")
  public static ImmutableList<Object[]> params() {
    // params() is called multiple times, so return cached parameters to avoid regenerating
    // different random parameter values.
    return sParams;
  }

  /**
   * Returns a list of random parameter combinations with which to run the tests in this class.
   *
   * <p>Each list item contains a value for {{@link #speed}, {@link #streamLength}} stored within an
   * Object array.
   *
   * <p>The method generates {@link #PARAM_COUNT} random {@link #speed} values and {@link
   * #PARAM_COUNT} random {@link #streamLength} values. These generated values are then grouped into
   * all possible combinations, and every group passed as parameters for each test.
   */
  private static ImmutableList<Object[]> initParams() {
    ImmutableSet.Builder<Object[]> paramsBuilder = new ImmutableSet.Builder<>();
    ImmutableSet.Builder<Float> speedsBuilder = new ImmutableSet.Builder<>();

    for (int i = 0; i < PARAM_COUNT; i++) {
      Range<Float> r = SPEED_RANGES.get(i % SPEED_RANGES.size());
      speedsBuilder.add(round(generateFloatInRange(r)));
    }
    ImmutableSet<Float> speeds = speedsBuilder.build();

    ImmutableSet<Long> lengths =
        new ImmutableSet.Builder<Long>()
            .addAll(
                random
                    .longs(/* min */ 0, MAX_LENGTH_SAMPLES)
                    .distinct()
                    .limit(PARAM_COUNT)
                    .iterator())
            .build();
    for (long length : lengths) {
      for (float speed : speeds) {
        paramsBuilder.add(new Object[] {speed, length});
      }
    }
    return paramsBuilder.build().asList();
  }

  @Parameter(0)
  public float speed;

  @Parameter(1)
  public long streamLength;

  @Test
  public void resampling_returnsExpectedNumberOfSamples() {
    byte[] inputBuffer = new byte[BLOCK_SIZE * BYTES_PER_SAMPLE];
    ShortBuffer outBuffer = ShortBuffer.allocate(BLOCK_SIZE);
    // Use same speed and pitch values for Sonic to resample stream.
    Sonic sonic =
        new Sonic(
            /* inputSampleRateHz= */ SAMPLE_RATE,
            /* channelCount= */ 1,
            /* speed= */ speed,
            /* pitch= */ speed,
            /* outputSampleRateHz= */ SAMPLE_RATE);
    long readSampleCount = 0;

    for (long samplesLeft = streamLength; samplesLeft > 0; samplesLeft -= BLOCK_SIZE) {
      random.nextBytes(inputBuffer);
      if (samplesLeft >= BLOCK_SIZE) {
        sonic.queueInput(ByteBuffer.wrap(inputBuffer).asShortBuffer());
      } else {
        // The last buffer to queue might have less samples than BLOCK_SIZE, so we should only queue
        // the remaining number of samples (samplesLeft).
        sonic.queueInput(
            ByteBuffer.wrap(inputBuffer, 0, (int) (samplesLeft * BYTES_PER_SAMPLE))
                .asShortBuffer());
        sonic.queueEndOfStream();
      }
      while (sonic.getOutputSize() > 0) {
        sonic.getOutput(outBuffer);
        readSampleCount += outBuffer.position();
        outBuffer.clear();
      }
    }
    sonic.flush();

    BigDecimal bigSampleRate = new BigDecimal(SAMPLE_RATE);
    BigDecimal bigSpeed = new BigDecimal(String.valueOf(speed));
    BigDecimal bigLength = new BigDecimal(String.valueOf(streamLength));
    // The scale of expectedSize will always be equal to bigLength. Thus, the result will always
    // yield an integer.
    BigDecimal expectedSize = bigLength.divide(bigSpeed, RoundingMode.HALF_EVEN);

    // Calculate number of times that Sonic accumulates truncation error. Set scale to 20 decimal
    // places, so that division doesn't return an integral.
    BigDecimal errorCount =
        bigLength.divide(bigSampleRate, /* scale= */ 20, RoundingMode.HALF_EVEN);

    // Calculate what truncation error Sonic is accumulating, calculated as:
    // inputSampleRate / speed - (int) inputSampleRate / speed. Set scale to 20 decimal places, so
    // that division doesn't return an integral.
    BigDecimal individualError =
        bigSampleRate.divide(bigSpeed, /* scale */ 20, RoundingMode.HALF_EVEN);
    individualError =
        individualError.subtract(individualError.setScale(/* newScale= */ 0, RoundingMode.FLOOR));
    // Calculate total accumulated error = (int) floor(errorCount * individualError).
    BigDecimal accumulatedError =
        errorCount.multiply(individualError).setScale(/* newScale= */ 0, RoundingMode.FLOOR);

    assertThat(readSampleCount)
        .isWithin(1)
        .of(expectedSize.longValueExact() - accumulatedError.longValueExact());
  }

  private static float round(float num) {
    BigDecimal bigDecimal = new BigDecimal(Float.toString(num));
    return bigDecimal.setScale(SPEED_DECIMAL_PRECISION, RoundingMode.HALF_EVEN).floatValue();
  }

  private static float generateFloatInRange(Range<Float> r) {
    return r.lowerEndpoint() + random.nextFloat() * (r.upperEndpoint() - r.lowerEndpoint());
  }
}
