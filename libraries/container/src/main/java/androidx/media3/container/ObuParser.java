/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.container;

import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility methods for parsing AV1 Open Bitstream Units or OBUs.
 *
 * <p><a href=https://aomediacodec.github.io/av1-spec/>AV1 Bitstream and Decoding Process
 * Specification</a>
 */
@UnstableApi
public final class ObuParser {

  /** OBU type sequence header. */
  public static final int OBU_SEQUENCE_HEADER = 1;

  /** OBU type temporal delimiter. */
  public static final int OBU_TEMPORAL_DELIMITER = 2;

  /** OBU type frame header. */
  public static final int OBU_FRAME_HEADER = 3;

  /** OBU type frame. */
  public static final int OBU_FRAME = 6;

  /** OBU type padding. */
  public static final int OBU_PADDING = 15;

  /** Open bitstream unit. */
  public static final class Obu {

    /** The OBU type. See {@code obu_type} in the AV1 spec. */
    public final int type;

    /** The OBU data, excluding the header. */
    public final ByteBuffer payload;

    private Obu(int type, ByteBuffer payload) {
      this.type = type;
      this.payload = payload;
    }
  }

  /**
   * Splits the input sample into a list of OBUs.
   *
   * <p>Expects the AV1 sample format specified by the <a
   * href=https://aomediacodec.github.io/av1-isobmff/#sampleformat>AV1 Codec ISO Media File Format
   * Binding</a>. That is, each OBU has the {@code obu_has_size_field} set to 1 except for the last
   * OBU in the sample, for which {@code obu_has_size_field} may be set to 0.
   *
   * @param sample The sample data.
   * @return The list of OBUs contained within the sample data.
   */
  public static List<Obu> split(ByteBuffer sample) {
    // Create a read-only buffer that shares content with the sample to avoid modifying the
    // input buffer's position, mark or limit.
    ByteBuffer readOnlySample = sample.asReadOnlyBuffer();
    List<Obu> obuList = new ArrayList<>();
    while (readOnlySample.hasRemaining()) {
      int headerByte = readOnlySample.get();
      int obuType = (headerByte >> 3) & 0xF;
      int extensionFlag = (headerByte >> 2) & 0x1;
      if (extensionFlag != 0) {
        readOnlySample.get(); // skip obu_extension_header()
      }
      int obuHasSizeField = (headerByte >> 1) & 0x1;
      int obuSize;
      if (obuHasSizeField != 0) {
        obuSize = leb128(readOnlySample);
      } else {
        // Only the last sample is allowed to have obu_has_size_field == 0, and the size is assumed
        // to fill the remainder of the sample.
        obuSize = readOnlySample.remaining();
      }
      ByteBuffer payload = readOnlySample.duplicate();
      payload.limit(readOnlySample.position() + obuSize);
      obuList.add(new Obu(obuType, payload));
      readOnlySample.position(readOnlySample.position() + obuSize);
    }
    return obuList;
  }

  private static int leb128(ByteBuffer data) {
    int value = 0;
    for (int i = 0; i < 8; i++) {
      int leb128Byte = data.get();
      value |= ((leb128Byte & 0x7F) << (i * 7));
      if ((leb128Byte & 0x80) == 0) {
        break;
      }
    }
    return value;
  }

  private ObuParser() {
    // Prevent instantiation.
  }
}
