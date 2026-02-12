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
package androidx.media3.exoplayer.audio;

import static android.os.Build.VERSION.SDK_INT;

import android.media.AudioDescriptor;
import android.media.AudioFormat;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.util.Log;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;

/** Helper utils for working with {@link android.media.AudioDescriptor}s. */
final class AudioDescriptorUtil {

  static final String TAG = "AudioDescriptorUtil";

  /** Non-instantiable. */
  private AudioDescriptorUtil() {}

  /**
   * Returns the max channel count found in LPCM encodings in Short Audio Descriptors.
   *
   * <p>For more information on Short Audio Descriptors, see:
   * https://en.wikipedia.org/wiki/Extended_Display_Identification_Data#Audio_Data_Blocks
   */
  public static int getMaxLpcmChannelCountFromPcmSads(List<AudioDescriptor> audioDescriptors) {
    if (SDK_INT < 31) {
      // AudioDescriptor.STANDARD_EDID requires API 31.
      return 0;
    }
    int maxChannelCount = 0;
    for (AudioDescriptor audioDescriptor : audioDescriptors) {
      if (audioDescriptor.getStandard() == AudioDescriptor.STANDARD_EDID) {
        byte[] data = audioDescriptor.getDescriptor();
        if (data.length != 3) {
          // Invalid SAD.
          Log.w(TAG, "Invalid SAD length: " + data.length);
          continue;
        }
        byte firstByte = data[0];
        // First three bits are number of channels - 1
        int numChannels = (firstByte & 0b111) + 1;
        // Bits 3–6 are audio format type.  We only care about LPCM (0b0001).
        int audioFormat = (firstByte >> 3) & 0b1111;
        if (audioFormat == 1) {
          maxChannelCount = Math.max(maxChannelCount, numChannels);
        }
      }
    }
    return maxChannelCount;
  }

  /**
   * Returns any channel masks found in the Speaker Allocation Data Block.
   *
   * <p>Returns sorted by the number of channels, in descending order. Will return empty if none are
   * found.
   *
   * <p>Uses speakers as defined by CTA-861.7 (Improvements to CTA-861-I, June 2024), Table 80. Some
   * speakers are deprecated as of that standard, but to accommodate older standards and devices, we
   * will still parse them when possible.
   *
   * <p>For more information on Speaker Allocation Data Blocks, see:
   * https://en.wikipedia.org/wiki/Extended_Display_Identification_Data#Speaker_Allocation_Data_Block
   */
  public static ImmutableList<Integer> getAllChannelMasksFromSadbs(
      List<AudioDescriptor> audioDescriptors) {
    if (SDK_INT < 34) {
      // AudioDescriptor.STANDARD_SADB requires API 34.
      return ImmutableList.of();
    }
    List<Integer> channelMasks = new ArrayList<>();
    for (AudioDescriptor audioDescriptor : audioDescriptors) {
      if (audioDescriptor.getStandard() == AudioDescriptor.STANDARD_SADB) {
        byte[] data = audioDescriptor.getDescriptor();
        if (data.length != 3) {
          // Though it is not clear from the getDescriptor() javadoc, we can see in the
          // implementation of AudioDescriptor that the bytes returned are just the 3-byte payload,
          // not including the header byte.
          Log.w(TAG, "Invalid SADB length: " + data.length);
          continue;
        }
        int channelMask = getChannelMaskFromSadb(data);
        channelMasks.add(channelMask);
      }
    }
    channelMasks.sort((a, b) -> Integer.bitCount(b) - Integer.bitCount(a));
    return ImmutableList.copyOf(channelMasks);
  }

  /**
   * Parses the three bytes of Speaker Allocation Data Block data into a channel mask.
   *
   * <p>Uses speakers as defined by CTA-861.7 (Improvements to CTA-861-I, June 2024), Table 80. Some
   * speakers are deprecated as of that standard, but to accommodate older standards and devices, we
   * will still parse them when possible.
   */
  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  /* package */ static int getChannelMaskFromSadb(byte[] data) {
    if (SDK_INT < 34 || data.length != 3) {
      // AudioDescriptor.STANDARD_SADB requires API 34.
      return 0;
    }
    int channelMask = 0;
    byte byte1 = data[0];
    // Byte 1
    // Bit 0 Front left/right (FL/FR).
    if ((byte1 & 0b00000001) != 0) {
      channelMask |= AudioFormat.CHANNEL_OUT_FRONT_LEFT | AudioFormat.CHANNEL_OUT_FRONT_RIGHT;
    }
    // Bit 1 Low-frequency effects (LFE1).
    if ((byte1 & 0b00000010) != 0) {
      channelMask |= AudioFormat.CHANNEL_OUT_LOW_FREQUENCY;
    }
    // Bit 2 Front center (FC).
    if ((byte1 & 0b00000100) != 0) {
      channelMask |= AudioFormat.CHANNEL_OUT_FRONT_CENTER;
    }
    // Bit 3 Back left/right (BL/BR).
    if ((byte1 & 0b00001000) != 0) {
      channelMask |= AudioFormat.CHANNEL_OUT_BACK_LEFT | AudioFormat.CHANNEL_OUT_BACK_RIGHT;
    }
    // Bit 4 Back center (BC).
    if ((byte1 & 0b00010000) != 0) {
      channelMask |= AudioFormat.CHANNEL_OUT_BACK_CENTER;
    }
    // Bit 5 Front left/right center (FLc/FRc).
    if ((byte1 & 0b00100000) != 0) {
      channelMask |=
          AudioFormat.CHANNEL_OUT_FRONT_LEFT_OF_CENTER
              | AudioFormat.CHANNEL_OUT_FRONT_RIGHT_OF_CENTER;
    }
    // Bit 6 Rear left/right center (RLC/RRC) are deprecated and also do not exist in AudioFormat
    // constants.

    // Bit 7 Front left/right wide (FLw/FRw).
    if ((byte1 & 0b10000000) != 0) {
      channelMask |=
          AudioFormat.CHANNEL_OUT_FRONT_WIDE_LEFT | AudioFormat.CHANNEL_OUT_FRONT_WIDE_RIGHT;
    }

    byte byte2 = data[1];
    // Byte 2
    // Bit 0 Top front left/right (TpFL/TpFR).
    if ((byte2 & 0b00000001) != 0) {
      channelMask |=
          AudioFormat.CHANNEL_OUT_TOP_FRONT_LEFT | AudioFormat.CHANNEL_OUT_TOP_FRONT_RIGHT;
    }
    // Bit 1 Top center (TpC).
    if ((byte2 & 0b00000010) != 0) {
      channelMask |= AudioFormat.CHANNEL_OUT_TOP_CENTER;
    }
    // Bit 2 Top front center (TpFC).
    if ((byte2 & 0b00000100) != 0) {
      channelMask |= AudioFormat.CHANNEL_OUT_TOP_FRONT_CENTER;
    }
    // Bit 3 Left surround/right surround (LS/RS).
    if ((byte2 & 0b00001000) != 0) {
      channelMask |= AudioFormat.CHANNEL_OUT_SIDE_LEFT | AudioFormat.CHANNEL_OUT_SIDE_RIGHT;
    }
    // Bit 4 Low-frequency effects 2 (LFE2). [Deprecated]
    if ((byte2 & 0b00010000) != 0) {
      channelMask |= AudioFormat.CHANNEL_OUT_LOW_FREQUENCY_2;
    }
    // Bit 5 Top back center (TpBC). [Deprecated]
    if ((byte2 & 0b00100000) != 0) {
      channelMask |= AudioFormat.CHANNEL_OUT_TOP_BACK_CENTER;
    }
    // Bit 6 Side left/right (SiL/SiR). [Deprecated]
    if ((byte2 & 0b01000000) != 0) {
      // Same as left/right surrounds.
      channelMask |= AudioFormat.CHANNEL_OUT_SIDE_LEFT | AudioFormat.CHANNEL_OUT_SIDE_RIGHT;
    }
    // Bit 7 Top side left/right (TpSiL/TpSiR). [Deprecated]
    if ((byte2 & 0b10000000) != 0) {
      channelMask |= AudioFormat.CHANNEL_OUT_TOP_SIDE_LEFT | AudioFormat.CHANNEL_OUT_TOP_SIDE_RIGHT;
    }

    byte byte3 = data[2];
    // Byte 3
    // Bit 1 Top back left/right (TpBL/TpBR). [Deprecated]
    if ((byte3 & 0b00000001) != 0) {
      channelMask |= AudioFormat.CHANNEL_OUT_TOP_BACK_LEFT | AudioFormat.CHANNEL_OUT_TOP_BACK_RIGHT;
    }
    // Bit 2 Bottom front center (BtFC). [Deprecated]
    if ((byte3 & 0b00000010) != 0) {
      channelMask |= AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_CENTER;
    }
    // Bit 3 Bottom front left/right (BtFL/BtFR). [Deprecated]
    if ((byte3 & 0b00000100) != 0) {
      channelMask |=
          AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_LEFT | AudioFormat.CHANNEL_OUT_BOTTOM_FRONT_RIGHT;
    }
    // Remaining bits are reserved.

    return channelMask;
  }
}
