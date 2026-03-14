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

import static androidx.media3.common.util.Util.formatInvariant;

import androidx.media3.common.C;
import androidx.media3.common.C.ColorRange;
import androidx.media3.common.C.ColorSpace;
import androidx.media3.common.C.ColorTransfer;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import java.util.List;

/** AV1 configuration data. */
@UnstableApi
public final class Av1Config {

  private static final String TAG = "Av1Config";

  /**
   * List of buffers containing the codec-specific data to be provided to the decoder.
   *
   * <p>See {@link Format#initializationData}.
   */
  public final List<byte[]> initializationData;

  /** The bit depth of the samples, or {@link Format#NO_VALUE} if unknown. */
  public final int bitdepth;

  /**
   * The {@link C.ColorSpace} of the video, or {@link Format#NO_VALUE} if unknown or not applicable.
   */
  public final @C.ColorSpace int colorSpace;

  /**
   * The {@link C.ColorRange} of the video, or {@link Format#NO_VALUE} if unknown or not applicable.
   */
  public final @C.ColorRange int colorRange;

  /**
   * The {@link C.ColorTransfer} of the video, or {@link Format#NO_VALUE} if unknown or not
   * applicable.
   */
  public final @C.ColorTransfer int colorTransfer;

  /**
   * An RFC 6381 codecs string representing the video format.
   *
   * <p>See {@link Format#codecs} and the <a
   * href="https://aomediacodec.github.io/av1-isobmff/#codecsparam">AV1 codec string spec</a>.
   */
  public final String codecs;

  /**
   * Parses the av1C configuration record and OBU sequence header and returns an {@link Av1Config}
   * from their data.
   *
   * <p>See av1C configuration record syntax in the <a
   * href="https://aomediacodec.github.io/av1-isobmff/#av1codecconfigurationbox-syntax">AV1 ISOBMFF
   * spec</a>.
   *
   * <p>See av1C OBU syntax in the <a
   * href="https://aomediacodec.github.io/av1-spec/av1-spec.pdf">AV1 Bitstream spec</a>.
   *
   * @param initializationData The exact bytes of the AV1 configuration data to parse.
   * @return A parsed representation of the AV1 configuration data.
   * @throws ParserException If an error occurred parsing the data.
   */
  public static Av1Config parse(byte[] initializationData) throws ParserException {
    try {
      // We can wrap the raw bytes directly in a ParsableBitArray, starting at position 0!
      ParsableBitArray bitArray = new ParsableBitArray(initializationData);

      // Parse av1C config record for bitdepth info.
      // See https://aomediacodec.github.io/av1-isobmff/#av1codecconfigurationbox-syntax.
      bitArray.skipBytes(1); // marker, version
      int seqProfile = bitArray.readBits(3);
      int seqLevelIdx0 = bitArray.readBits(5);
      boolean seqTier0 = bitArray.readBit();
      boolean highBitdepth = bitArray.readBit(); // high_bitdepth
      boolean twelveBit = bitArray.readBit(); // twelve_bit
      int bitdepth;
      if (highBitdepth) {
        bitdepth = twelveBit ? 12 : 10;
      } else {
        bitdepth = 8;
      }
      // Skip monochrome, chroma_subsampling_x, chroma_subsampling_y, chroma_sample_position,
      // reserved and initial_presentation_delay.
      bitArray.skipBits(13);
      String codecs = buildCodecString(seqProfile, seqLevelIdx0, seqTier0, bitdepth);
      // The configOBUs array is optional. If the payload is exactly 4 bytes, we are done.
      if (bitArray.bitsLeft() <= 0) {
        return new Av1Config(initializationData, bitdepth, codecs);
      }

      // 5.3.1. General OBU syntax
      bitArray.skipBit(); // obu_forbidden_bit
      int obuType = bitArray.readBits(4); // obu_type
      if (obuType != 1) { // obu_type != OBU_SEQUENCE_HEADER
        Log.i(TAG, "Unsupported obu_type: " + obuType);
        return new Av1Config(initializationData, bitdepth, codecs);
      }
      if (bitArray.readBit()) { // obu_extension_flag
        Log.i(TAG, "Unsupported obu_extension_flag");
        return new Av1Config(initializationData, bitdepth, codecs);
      }
      boolean obuHasSizeField = bitArray.readBit(); // obu_has_size_field
      bitArray.skipBit(); // obu_reserved_1bit
      // obu_size is unsigned leb128 and if obu_size <= 127 then it can be simplified as
      // readBits(8).
      if (obuHasSizeField && bitArray.readBits(8) > 127) { // obu_size
        Log.i(TAG, "Excessive obu_size");
        return new Av1Config(initializationData, bitdepth, codecs);
      }
      // 5.5.1. General OBU sequence header syntax
      int obuSeqHeaderSeqProfile = bitArray.readBits(3); // seq_profile
      bitArray.skipBit(); // still_picture
      if (bitArray.readBit()) { // reduced_still_picture_header
        Log.i(TAG, "Unsupported reduced_still_picture_header");
        return new Av1Config(initializationData, bitdepth, codecs);
      }
      if (bitArray.readBit()) { // timing_info_present_flag
        Log.i(TAG, "Unsupported timing_info_present_flag");
        return new Av1Config(initializationData, bitdepth, codecs);
      }
      if (bitArray.readBit()) { // initial_display_delay_present_flag
        Log.i(TAG, "Unsupported initial_display_delay_present_flag");
        return new Av1Config(initializationData, bitdepth, codecs);
      }
      int operatingPointsCountMinus1 = bitArray.readBits(5); // operating_points_cnt_minus_1
      for (int i = 0; i <= operatingPointsCountMinus1; i++) {
        bitArray.skipBits(12); // operating_point_idc[i]
        int seqLevelIdx = bitArray.readBits(5); // seq_level_idx[i]
        if (seqLevelIdx > 7) {
          bitArray.skipBit(); // seq_tier[i]
        }
      }
      int frameWidthBitsMinus1 = bitArray.readBits(4); // frame_width_bits_minus_1
      int frameHeightBitsMinus1 = bitArray.readBits(4); // frame_height_bits_minus_1
      bitArray.skipBits(frameWidthBitsMinus1 + 1); // max_frame_width_minus_1
      bitArray.skipBits(frameHeightBitsMinus1 + 1); // max_frame_height_minus_1
      if (bitArray.readBit()) { // frame_id_numbers_present_flag
        bitArray.skipBits(7); // delta_frame_id_length_minus_2, additional_frame_id_length_minus_1
      }
      bitArray.skipBits(7); // use_128x128_superblock...enable_dual_filter: 7 flags
      boolean enableOrderHint = bitArray.readBit(); // enable_order_hint
      if (enableOrderHint) {
        bitArray.skipBits(2); // enable_jnt_comp, enable_ref_frame_mvs
      }
      int seqForceScreenContentTools =
          bitArray.readBit() // seq_choose_screen_content_tools
              ? 2 // SELECT_SCREEN_CONTENT_TOOLS
              : bitArray.readBits(1); // seq_force_screen_content_tools
      if (seqForceScreenContentTools > 0) {
        if (!bitArray.readBit()) { // seq_choose_integer_mv
          bitArray.skipBits(1); // seq_force_integer_mv
        }
      }
      if (enableOrderHint) {
        bitArray.skipBits(3); // order_hint_bits_minus_1
      }
      bitArray.skipBits(3); // enable_superres, enable_cdef, enable_restoration
      // 5.5.2. OBU Color config syntax
      boolean colorConfigHighBitdepth = bitArray.readBit(); // high_bitdepth
      if (obuSeqHeaderSeqProfile == 2 && colorConfigHighBitdepth) {
        bitArray.skipBit(); // twelve_bit
      }

      boolean monochrome = (obuSeqHeaderSeqProfile != 1) && bitArray.readBit(); // mono_chrome

      if (!bitArray.readBit()) { // color_description_present_flag
        return new Av1Config(initializationData, bitdepth, codecs);
      }
      int colorPrimaries = bitArray.readBits(8); // color_primaries
      int transferCharacteristics = bitArray.readBits(8); // transfer_characteristics
      int matrixCoefficients = bitArray.readBits(8); // matrix_coefficients
      int colorRangeValue =
          (!monochrome
                  && colorPrimaries == 1 // CP_BT_709
                  && transferCharacteristics == 13 // TC_SRGB
                  && matrixCoefficients == 0) // MC_IDENTITY
              ? 1
              : bitArray.readBits(1); // color_range;
      @ColorSpace int colorSpace = ColorInfo.isoColorPrimariesToColorSpace(colorPrimaries);
      @ColorRange
      int colorRange = (colorRangeValue == 1) ? C.COLOR_RANGE_FULL : C.COLOR_RANGE_LIMITED;
      @ColorTransfer
      int colorTransfer =
          ColorInfo.isoTransferCharacteristicsToColorTransfer(transferCharacteristics);
      return new Av1Config(
          initializationData, bitdepth, codecs, colorSpace, colorRange, colorTransfer);

    } catch (RuntimeException e) {
      throw ParserException.createForMalformedContainer("Error parsing AV1 config", e);
    }
  }

  /**
   * Builds an RFC 6381 codec string containing only the mandatory values described in the <a
   * href="https://aomediacodec.github.io/av1-isobmff/#codecsparam">AV1 spec</a>.
   */
  private static String buildCodecString(
      int seqProfile, int seqLevelIdx0, boolean seqTier0, int bitdepth) {
    return "av01"
        + "."
        + seqProfile
        + "."
        + formatInvariant("%02d", seqLevelIdx0)
        + (seqTier0 ? "H" : "M")
        + "."
        + formatInvariant("%02d", bitdepth);
  }

  private Av1Config(byte[] initializationData, int bitdepth, String codecs) {
    this(
        initializationData,
        bitdepth,
        codecs,
        /* colorSpace= */ Format.NO_VALUE,
        /* colorRange= */ Format.NO_VALUE,
        /* colorTransfer= */ Format.NO_VALUE);
  }

  private Av1Config(
      byte[] initializationData,
      int bitdepth,
      String codecs,
      @C.ColorSpace int colorSpace,
      @C.ColorRange int colorRange,
      @C.ColorTransfer int colorTransfer) {
    this.initializationData = ImmutableList.of(initializationData);
    this.bitdepth = bitdepth;
    this.codecs = codecs;
    this.colorSpace = colorSpace;
    this.colorRange = colorRange;
    this.colorTransfer = colorTransfer;
  }
}
