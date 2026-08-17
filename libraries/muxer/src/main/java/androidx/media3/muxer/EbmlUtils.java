/*
 * Copyright (C) 2025 The Android Open Source Project
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
package androidx.media3.muxer;

import static com.google.common.base.Preconditions.checkArgument;

import androidx.annotation.IntRange;
import java.nio.ByteBuffer;

/** Utility class for EBML format. */
/* package */ final class EbmlUtils {

  private EbmlUtils() {}

  /**
   * Encodes a long value as a variable-length integer (VINT), with a specified width.
   *
   * <p>Encodes the value by setting a leading length descriptor bit as below:
   *
   * <pre>
   * 1xxxxxxx                                                                   - 1-byte values
   * 01xxxxxx xxxxxxxx                                                          -
   * 001xxxxx xxxxxxxx xxxxxxxx                                                 -
   * 0001xxxx xxxxxxxx xxxxxxxx xxxxxxxx                                        - ...
   * 00001xxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx                               -
   * 000001xx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx                      -
   * 0000001x xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx             -
   * 00000001 xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx xxxxxxxx    - 8-byte values
   * </pre>
   *
   * @param value The value to encode.
   * @param width The desired width of the VINT in bytes (1-8).
   * @return A {@link ByteBuffer} containing the VINT-encoded value.
   */
  public static ByteBuffer encodeVIntWithWidth(long value, @IntRange(from = 1, to = 8) int width) {
    checkArgument(width >= 1 && width <= 8);
    byte[] encodedBytes = new byte[width];
    long encodedValue = (1L << (width * 7)) | value;

    for (int i = 0; i < width; i++) {
      encodedBytes[i] = (byte) (encodedValue >>> ((width - 1 - i) * 8));
    }
    return ByteBuffer.wrap(encodedBytes);
  }

  /**
   * Encodes a long value as a variable-length integer (VINT) using the minimum possible width.
   *
   * @see #encodeVIntWithWidth(long value, int width)
   * @param value The value to encode.
   * @return A {@link ByteBuffer} containing the VINT-encoded value.
   */
  public static ByteBuffer encodeVInt(long value) {
    return encodeVIntWithWidth(value, calculateMinimumVIntLength(value));
  }

  /**
   * Calculates the minimum number of bytes required to VINT-encode a value.
   *
   * @param value The value to be encoded. It must be positive number.
   * @return The length of the VINT in bytes (1-8).
   */
  public static int calculateMinimumVIntLength(long value) {
    // Value must be positive.
    checkArgument(value >= 0);
    if (value <= ((1L << 7) - 2)) {
      return 1;
    } else if (value <= ((1L << 14) - 2)) {
      return 2;
    } else if (value <= ((1L << 21) - 2)) {
      return 3;
    } else if (value <= ((1L << 28) - 2)) {
      return 4;
    } else if (value <= ((1L << 35) - 2)) {
      return 5;
    } else if (value <= ((1L << 42) - 2)) {
      return 6;
    } else if (value <= ((1L << 49) - 2)) {
      return 7;
    } else if (value <= ((1L << 56) - 2)) {
      return 8;
    }
    throw new IllegalArgumentException("Value " + value + " is too large for a VINT.");
  }
}
