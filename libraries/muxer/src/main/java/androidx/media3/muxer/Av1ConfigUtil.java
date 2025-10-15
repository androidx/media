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

import static androidx.media3.muxer.BoxUtils.concatenateBuffers;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.ObuParser;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility methods for generating AV1 initialization data.
 *
 * <p><a href=https://aomediacodec.github.io/av1-spec/>AV1 Bitstream and Decoding Process
 * Specification</a>
 */
@UnstableApi
/* package */ final class Av1ConfigUtil {

  private static final int MAX_LEB128_SIZE_BYTES = 8;
  private static final int MAX_HEADER_AND_LENGTH_SIZE_BYTES = 1 + MAX_LEB128_SIZE_BYTES;
  // AV1 configuration record size without config obus.
  private static final int MAX_AV1_CONFIG_RECORD_SIZE_BYTES = 4;
  // Only the last sample is allowed to have obu_has_size_field == 0.
  private static final int OBU_HAS_SIZE_FIELD_BYTES = 1 << 1;

  /**
   * Generates AV1 initialization data from the first sample {@link ByteBuffer}.
   *
   * @param byteBuffer The first sample data, in the format specified by the <a
   *     href=https://aomediacodec.github.io/av1-isobmff/#sampleformat>AV1 Codec ISO Media File
   *     Format Binding</a>.
   * @return The initialization data.
   */
  public static byte[] createAv1CodecConfigurationRecord(ByteBuffer byteBuffer) {
    @Nullable ByteBuffer csdHeader = null;
    @Nullable ByteBuffer configSequenceObu = null;
    List<ByteBuffer> configMetadataObusList = new ArrayList<>();

    List<ObuParser.Obu> obus = ObuParser.split(byteBuffer);

    for (ObuParser.Obu obu : obus) {
      if (obu.type == ObuParser.OBU_METADATA) {
        configMetadataObusList.add(getConfigObuWithHeaderAndLength(obu));
      } else if (obu.type == ObuParser.OBU_SEQUENCE_HEADER && configSequenceObu == null) {
        configSequenceObu = getConfigObuWithHeaderAndLength(obu);
        csdHeader = parseConfigFromSeqHeader(obu);
      }
    }
    checkNotNull(configSequenceObu, "No sequence header available.");
    ByteBuffer configMetadataObus =
        concatenateBuffers(configMetadataObusList.toArray(new ByteBuffer[0]));
    ByteBuffer configObus = configSequenceObu;
    if (configMetadataObus != null) {
      configObus = concatenateBuffers(configObus, configMetadataObus);
    }

    return concatenateBuffers(checkNotNull(csdHeader, "csdHeader is null."), configObus).array();
  }

  private static ByteBuffer getConfigObuWithHeaderAndLength(ObuParser.Obu obu) {
    ByteBuffer configObu =
        ByteBuffer.allocate(MAX_HEADER_AND_LENGTH_SIZE_BYTES + obu.payload.remaining());
    configObu.put(obuHeader(obu.type));
    configObu.put(lebEncode(obu.payload.remaining()));
    configObu.put(obu.payload.duplicate());
    configObu.flip();
    return configObu;
  }

  // Obu header byte - https://aomediacodec.github.io/av1-spec/#obu-header-syntax.
  private static byte obuHeader(int obuType) {
    return (byte) (obuType << 3 | OBU_HAS_SIZE_FIELD_BYTES);
  }

  private static ByteBuffer lebEncode(int value) {
    checkArgument(value > 0);
    int lebSize = lebSizeInBytes(value);
    ByteBuffer sizeBytes = ByteBuffer.allocate(lebSize);
    checkState(lebSize < MAX_LEB128_SIZE_BYTES);
    for (int i = 0; i < lebSize; ++i) {
      int byteValue = (byte) (value & 0x7f);
      value >>= 7;
      if (value != 0) {
        byteValue |= 0x80; // signal that more bytes follow.
      }
      sizeBytes.put((byte) byteValue);
    }
    sizeBytes.flip();
    return sizeBytes;
  }

  private static int lebSizeInBytes(int value) {
    int size = 0;
    do {
      size++;
      value >>= 7;
    } while (value != 0);
    return size;
  }

  private static ByteBuffer parseConfigFromSeqHeader(ObuParser.Obu obu) {
    ByteBuffer csd = ByteBuffer.allocate(MAX_AV1_CONFIG_RECORD_SIZE_BYTES);
    csd.put((byte) (0x1 << 7 | 0x1)); // Marker(0x1) << 7 | Version (0x1)

    @Nullable ObuParser.SequenceHeader sequenceHeader = ObuParser.SequenceHeader.parse(obu);
    checkNotNull(sequenceHeader, "No sequence header available.");
    csd.put((byte) (sequenceHeader.seqProfile << 5 | sequenceHeader.seqLevelIdx0));
    csd.put(
        (byte)
            ((sequenceHeader.seqTier0 > 0 ? 0x80 : 0x0)
                | (sequenceHeader.highBitdepth ? 0x40 : 0x0)
                | (sequenceHeader.twelveBit ? 0x20 : 0x0)
                | (sequenceHeader.monochrome ? 0x10 : 0x0)
                | (sequenceHeader.subsamplingX ? 0x08 : 0x0)
                | (sequenceHeader.subsamplingY ? 0x04 : 0x0)
                | sequenceHeader.chromaSamplePosition));
    csd.put(
        (byte)
            ((sequenceHeader.initialDisplayDelayPresentFlag ? 0x10 : 0x0)
                | (sequenceHeader.initialDisplayDelayPresentFlag
                    ? sequenceHeader.initialDisplayDelayMinus1 & 0x0F
                    : 0x0)));
    csd.flip();
    return csd;
  }

  private Av1ConfigUtil() {}
}
