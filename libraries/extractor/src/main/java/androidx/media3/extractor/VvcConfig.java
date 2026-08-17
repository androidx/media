/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.extractor;

import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.NalUnitUtil;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Locale;

/** VVC configuration data. */
@UnstableApi
public final class VvcConfig {

  /**
   * Parses VVC configuration data from a {@code vvcC} box.
   *
   * <p>See ISO/IEC 14496-15:2024, Clause 11.2.4.2.2 for the VvcDecoderConfigurationRecord syntax.
   *
   * @param data The {@code vvcC} box data.
   * @return The parsed configuration.
   * @throws ParserException If the data is malformed.
   */
  public static VvcConfig parse(ParsableByteArray data) throws ParserException {
    try {
      // 11.2.4.3.2: VvcConfigurationBox extends FullBox (version 0, flags 0).
      if (data.readInt() != 0) {
        throw ParserException.createForMalformedContainer("Unsupported VVC version", null);
      }

      // Clause 11.2.4.2.2: VvcDecoderConfigurationRecord
      // reserved (5), lengthSizeMinusOne (2), ptl_present_flag (1)
      int firstByte = data.readUnsignedByte();
      int lengthSizeMinusOne = (firstByte >> 1) & 0x03;
      boolean ptlPresentFlag = (firstByte & 0x01) != 0;
      int nalUnitLengthFieldLength = lengthSizeMinusOne + 1;

      int profileIdc = 0;
      int levelIdc = 0;
      String tierString = "L";
      int bitDepthMinus8 = 0;

      if (ptlPresentFlag) {
        // Clause 11.2.4.2.3: VvcPTLRecord
        data.skipBytes(1); // ols_idx (8)
        // ols_idx (1), num_sublayers (3), constant_frame_rate (2), chroma_format_idc (2)
        int olsAndSublayersByte = data.readUnsignedByte();
        int numSublayers = (olsAndSublayersByte >> 4) & 0x07;

        int bitDepthByte = data.readUnsignedByte();
        // bit_depth_minus8 (3 bits) from bits 7-5
        bitDepthMinus8 = (bitDepthByte >> 5) & 0x07;

        int constraintInfoByte = data.readUnsignedByte();
        // reserved (2), num_bytes_constraint_info (6)
        int numBytesConstraintInfo = constraintInfoByte & 0x3F;

        int profileAndTierByte = data.readUnsignedByte();
        // general_profile_idc (7), general_tier_flag (1)
        profileIdc = (profileAndTierByte >> 1) & 0x7F;
        tierString = (profileAndTierByte & 0x01) != 0 ? "H" : "L";

        levelIdc = data.readUnsignedByte(); // general_level_idc (8)

        data.skipBytes(numBytesConstraintInfo);
        if (numSublayers > 1) {
          // Skip ptl_sublayer_level_present_flag and ptl_reserved_zero_bit (1 byte total)
          int sublayerFlags = data.readUnsignedByte();
          for (int i = 0; i < numSublayers - 1; i++) {
            // Check ptl_sublayer_level_present_flag[i] starting from MSB
            if (((sublayerFlags >> (7 - i)) & 0x01) != 0) {
              data.skipBytes(1); // unsigned int(8) sublayer_level_idc[i]
            }
          }
        }

        int numSubProfiles = data.readUnsignedByte(); // ptl_num_sub_profiles (8)
        data.skipBytes(numSubProfiles * 4); // general_sub_profile_idc[j] (32 bits each)

        // Skip max_picture_width (16), max_picture_height (16), and avg_frame_rate (16)
        data.skipBytes(6);
      }

      int numArrays = data.readUnsignedByte(); // numArrays (8)
      int csdStartPosition = data.getPosition();
      int csdLength = 0;
      // First pass: calculate total length for the single concatenated CSD buffer.
      for (int i = 0; i < numArrays; i++) {
        int arrayHeader = data.readUnsignedByte();
        int nalType = arrayHeader & 0x1F; // array_completeness (1), reserved (2), NAL_unit_type (5)
        int numNalus =
            (nalType != NalUnitUtil.VVC_NAL_UNIT_TYPE_DCI
                    && nalType != NalUnitUtil.VVC_NAL_UNIT_TYPE_OPI)
                ? data.readUnsignedShort()
                : 1;
        for (int j = 0; j < numNalus; j++) {
          int nalUnitLength = data.readUnsignedShort(); // nalUnitLength (16)
          csdLength += 4 + nalUnitLength; // 4-byte start code + NALU
          data.skipBytes(nalUnitLength);
        }
      }

      data.setPosition(csdStartPosition);
      byte[] buffer = new byte[csdLength];
      int bufferPosition = 0;
      // Second pass: fill the buffer with start codes.
      for (int i = 0; i < numArrays; i++) {
        int arrayHeader = data.readUnsignedByte();
        int nalType = arrayHeader & 0x1F;
        int numNalus =
            (nalType != NalUnitUtil.VVC_NAL_UNIT_TYPE_DCI
                    && nalType != NalUnitUtil.VVC_NAL_UNIT_TYPE_OPI)
                ? data.readUnsignedShort()
                : 1;
        for (int j = 0; j < numNalus; j++) {
          int nalUnitLength = data.readUnsignedShort();
          System.arraycopy(NalUnitUtil.NAL_START_CODE, 0, buffer, bufferPosition, 4);
          bufferPosition += 4;
          data.readBytes(buffer, bufferPosition, nalUnitLength);
          bufferPosition += nalUnitLength;
        }
      }

      // Build RFC 6381 codec string: vvc1.profile_idc.tier_and_level
      String codecs = String.format(Locale.US, "vvc1.%d.%s%d", profileIdc, tierString, levelIdc);
      return new VvcConfig(
          ImmutableList.of(buffer), nalUnitLengthFieldLength, codecs, bitDepthMinus8 + 8);
    } catch (ArrayIndexOutOfBoundsException e) {
      throw ParserException.createForMalformedContainer("Error parsing VVC configuration", e);
    }
  }

  /** The initialization data for the VVC decoder. */
  public final List<byte[]> initializationData;

  /** The length in bytes of the NAL unit length field in each sample. */
  public final int nalUnitLengthFieldLength;

  /** An RFC 6381 codec string for the VVC track. */
  public final String codecs;

  /** The bit depth of the luma samples. */
  public final int bitdepthLuma;

  private VvcConfig(
      List<byte[]> initializationData,
      int nalUnitLengthFieldLength,
      String codecs,
      int bitdepthLuma) {
    this.initializationData = initializationData;
    this.nalUnitLengthFieldLength = nalUnitLengthFieldLength;
    this.codecs = codecs;
    this.bitdepthLuma = bitdepthLuma;
  }
}
