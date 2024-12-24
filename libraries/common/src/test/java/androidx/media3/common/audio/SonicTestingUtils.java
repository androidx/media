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

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Testing utils class related to {@link Sonic} */
/* package */ final class SonicTestingUtils {
  /**
   * Returns expected accumulated truncation error for {@link Sonic}'s resampling algorithm, given
   * an input length, input sample rate, and resampling rate.
   *
   * <p><b>Note:</b> This method is only necessary until we address b/361768785 and fix the
   * underlying truncation issue.
   *
   * <p>The accumulated truncation error is calculated as follows:
   *
   * <ol>
   *   <li>Individual truncation error: Divide sample rate by resampling rate, and calculate delta
   *       between floating point result and truncated int representation.
   *   <li>Truncation accumulation count: Divide length by sample rate to obtain number of times
   *       that truncation error accumulates.
   *   <li>Accumulated truncation error: Multiply results of 1 and 2.
   * </ol>
   *
   * @param length Length of input in frames.
   * @param sampleRate Input sample rate of {@link Sonic} instance.
   * @param resamplingRate Resampling rate given by {@code pitch * (inputSampleRate /
   *     outputSampleRate)}.
   */
  public static long calculateAccumulatedTruncationErrorForResampling(
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

  private SonicTestingUtils() {}
}
