/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.extractor.mp3;

import androidx.media3.common.C;
import androidx.media3.common.util.Util;
import java.math.RoundingMode;

/* package */ class Mp3Util {

  /* package */ static int computeAverageBitrate(long dataSize, long durationUs) {
    if (dataSize <= 0 || durationUs <= 0) {
      return C.RATE_UNSET_INT;
    }
    long averageBitrate =
        Util.scaleLargeValue(
            dataSize, C.BITS_PER_BYTE * C.MICROS_PER_SECOND, durationUs, RoundingMode.HALF_UP);
    return averageBitrate > 0 && averageBitrate <= Integer.MAX_VALUE
        ? (int) averageBitrate
        : C.RATE_UNSET_INT;
  }

  private Mp3Util() {}
}
