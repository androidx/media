/*
 * Copyright (C) 2026 The Android Open Source Project
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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Utilities for creating synthetic TrueHD access units and inspecting MAT frames in tests. */
/* package */ final class TrueHdTestUtil {

  /** Ratebits for 48 kHz. */
  public static final int RATEBITS_48_KHZ = 0;

  /** Ratebits for 44.1 kHz. */
  public static final int RATEBITS_44_1_KHZ = 8;

  /** Value of {@code outputTiming} parameters for access units without a restart header. */
  public static final int NO_OUTPUT_TIMING = -1;

  /** Byte that fills access unit payloads, distinct from padding. */
  public static final byte PAYLOAD_FILL = 0x55;

  /** Offset of the MAT start code in a MAT frame. */
  public static final int MAT_START_CODE_OFFSET = 8;

  /** Offset of the first audio data in a MAT frame that doesn't start with carried over data. */
  public static final int MAT_FIRST_DATA_OFFSET = 28;

  /** Offset of the MAT middle code in a MAT frame. */
  public static final int MAT_MIDDLE_CODE_OFFSET = 30716;

  /** Offset of the MAT end code in a MAT frame. */
  public static final int MAT_END_CODE_OFFSET = 61416;

  /** The IEC 61937 burst preamble as big endian 16-bit words. */
  public static final byte[] IEC61937_TRUEHD_PREAMBLE =
      toBytes(0xF8, 0x72, 0x4E, 0x1F, 0x00, 0x16, 0xEF, 0xF0);

  public static final byte[] MAT_START_CODE =
      toBytes(
          0x07, 0x9E, 0x00, 0x03, 0x84, 0x01, 0x01, 0x01, 0x80, 0x00, 0x56, 0xA5, 0x3B, 0xF4, 0x81,
          0x83, 0x49, 0x80, 0x77, 0xE0);

  public static final byte[] MAT_MIDDLE_CODE =
      toBytes(0xC3, 0xC1, 0x42, 0x49, 0x3B, 0xFA, 0x82, 0x83, 0x49, 0x80, 0x77, 0xE0);

  public static final byte[] MAT_END_CODE =
      toBytes(
          0xC3, 0xC2, 0xC0, 0xC4, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x97,
          0x11, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00);

  private TrueHdTestUtil() {}

  /**
   * Creates a TrueHD access unit.
   *
   * @param size The size of the access unit, in bytes. Must be even, and at least 38 for a major
   *     sync with an output timing.
   * @param frameTime The 16-bit input timing of the access unit.
   * @param isMajorSync Whether the access unit starts with a major sync.
   * @param ratebits The ratebits written in the major sync.
   * @param outputTiming The 16-bit output timing written in a restart header of the first
   *     substream, or {@link #NO_OUTPUT_TIMING} to write a major sync without substreams.
   */
  public static byte[] createAccessUnit(
      int size, int frameTime, boolean isMajorSync, int ratebits, int outputTiming) {
    byte[] data = new byte[size];
    Arrays.fill(data, PAYLOAD_FILL);
    int lengthInWords = size / 2;
    data[0] = (byte) (0xF0 | ((lengthInWords >> 8) & 0x0F));
    data[1] = (byte) lengthInWords;
    data[2] = (byte) (frameTime >> 8);
    data[3] = (byte) frameTime;
    if (isMajorSync) {
      data[4] = (byte) 0xF8;
      data[5] = (byte) 0x72;
      data[6] = (byte) 0x6F;
      data[7] = (byte) 0xBA;
      data[8] = (byte) (ratebits << 4);
      // Substream count in the high nibble.
      data[20] = (byte) (outputTiming == NO_OUTPUT_TIMING ? 0x00 : 0x10);
      // No major sync extension.
      data[29] = 0;
      if (outputTiming != NO_OUTPUT_TIMING) {
        // Substream directory entry without extra word.
        data[32] = 0;
        data[33] = 0;
        // block_header_exists, restart_header_exists, restart_sync_word (zero), output_timing.
        data[34] = (byte) 0xC0;
        data[35] = 0;
        data[36] = (byte) (outputTiming >> 8);
        data[37] = (byte) outputTiming;
      }
    }
    return data;
  }

  /** Creates a non-major sync TrueHD access unit. */
  public static byte[] createAccessUnit(int size, int frameTime) {
    return createAccessUnit(
        size, frameTime, /* isMajorSync= */ false, RATEBITS_48_KHZ, NO_OUTPUT_TIMING);
  }

  /** Creates a 48 kHz major sync TrueHD access unit without restart header. */
  public static byte[] createMajorSyncAccessUnit(int size, int frameTime) {
    return createAccessUnit(
        size, frameTime, /* isMajorSync= */ true, RATEBITS_48_KHZ, NO_OUTPUT_TIMING);
  }

  /** Concatenates access units into a direct, native order buffer. */
  public static ByteBuffer toInputBuffer(byte[]... accessUnits) {
    int size = 0;
    for (byte[] accessUnit : accessUnits) {
      size += accessUnit.length;
    }
    ByteBuffer buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
    for (byte[] accessUnit : accessUnits) {
      buffer.put(accessUnit);
    }
    buffer.flip();
    return buffer;
  }

  /**
   * Converts a MAT frame from native order 16-bit words to the big endian byte stream it encodes.
   */
  public static byte[] toBigEndianBytes(ByteBuffer frame) {
    ByteBuffer nativeOrderFrame = frame.duplicate().order(ByteOrder.nativeOrder());
    byte[] bytes = new byte[nativeOrderFrame.remaining()];
    for (int i = 0; i < bytes.length; i += 2) {
      short word = nativeOrderFrame.getShort(nativeOrderFrame.position() + i);
      bytes[i] = (byte) (word >> 8);
      bytes[i + 1] = (byte) word;
    }
    return bytes;
  }

  /**
   * Converts bytes written to an IEC 61937 track (native order 16-bit words) to the big endian
   * byte stream they encode.
   */
  public static byte[] toBigEndianBytes(byte[] nativeOrderWords) {
    return toBigEndianBytes(ByteBuffer.wrap(nativeOrderWords).order(ByteOrder.nativeOrder()));
  }

  /** Returns a copy of {@code length} bytes of {@code data}, starting at {@code offset}. */
  public static byte[] slice(byte[] data, int offset, int length) {
    return Arrays.copyOfRange(data, offset, offset + length);
  }

  private static byte[] toBytes(int... values) {
    byte[] bytes = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      bytes[i] = (byte) values[i];
    }
    return bytes;
  }
}
