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
package androidx.media3.exoplayer.video;

import androidx.media3.common.util.CodecSpecificDataUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.NalUnitUtil;
import java.nio.ByteBuffer;

/** Utility methods for handling HEVC SEI messages. */
@UnstableApi
public final class HevcSeiUtil {

  private static final int SEI_PAYLOAD_TYPE_ITU_T_T35 = 4;
  /* package */ static final int SEI_PAYLOAD_TYPE_UNSPECIFIED = 254;

  private HevcSeiUtil() {}

  /**
   * Modifies the provided {@link ByteBuffer} in place to strip all ITU-T T.35 SEI messages that are
   * not HDR10+.
   *
   * <p>This is achieved by masking the payload type of any non-HDR10+ T.35 SEI message to an
   * unspecified payload type ({@link #SEI_PAYLOAD_TYPE_UNSPECIFIED}).
   *
   * @param buffer The {@link ByteBuffer} containing the HEVC bitstream with Annex B start codes.
   */
  public static void stripNonHdr10PlusT35Metadata(ByteBuffer buffer) {
    stripT35Metadata(buffer, /* keepHdr10Plus= */ true);
  }

  /**
   * Modifies the provided {@link ByteBuffer} in place to strip all ITU-T T.35 SEI messages.
   *
   * <p>This is achieved by masking the payload type of any T.35 SEI message to an unspecified
   * payload type ({@link #SEI_PAYLOAD_TYPE_UNSPECIFIED}). The NAL unit lengths and message lengths
   * remain unchanged, so the operation can be done in-place with zero memory allocations, avoiding
   * the need to fully parse emulation prevention bytes or copy the buffer.
   *
   * @param buffer The {@link ByteBuffer} containing the HEVC bitstream with Annex B start codes.
   */
  public static void stripAllT35Metadata(ByteBuffer buffer) {
    stripT35Metadata(buffer, /* keepHdr10Plus= */ false);
  }

  private static void stripT35Metadata(ByteBuffer buffer, boolean keepHdr10Plus) {
    int position = buffer.position();
    int limit = buffer.limit();
    if (limit - position < 4) {
      return;
    }

    int searchOffset = position;

    while (searchOffset < limit) {
      int nalUnitOffset = findNalUnit(buffer, searchOffset, limit);
      if (nalUnitOffset == limit) {
        break; // No more NAL units
      }

      if (nalUnitOffset + 3 >= limit) {
        break; // Not enough bytes for a NAL header.
      }

      int nalUnitType = (buffer.get(nalUnitOffset + 3) & 0x7E) >> 1;

      // NAL unit types 0 to 31 indicate VCL NAL units.
      // Prefix SEI messages always precede VCL NAL units, so we can safely stop parsing
      // once we hit a VCL NAL unit to avoid scanning the encoded video data.
      if (nalUnitType >= 0 && nalUnitType <= 31) {
        break;
      }

      if (nalUnitType == NalUnitUtil.H265_NAL_UNIT_TYPE_PREFIX_SEI) {
        int seiOffset = nalUnitOffset + 3 + 2; // Skip 3-byte start code and 2-byte NAL header

        int nextNalUnitOffset = findNalUnit(buffer, seiOffset, limit);
        int seiLimit = nextNalUnitOffset; // The SEI payload ends where the next NAL unit starts

        int consecutiveZeros = 0; // State tracked across the entire SEI NAL unit

        while (seiOffset < seiLimit) {
          // Read payloadType
          int payloadType = 0;
          int t35PayloadTypeOffset = -1;
          while (seiOffset < seiLimit) {
            int b = buffer.get(seiOffset) & 0xFF;
            if (b == 0x03 && consecutiveZeros >= 2) {
              consecutiveZeros = 0;
              seiOffset++;
              continue;
            }
            consecutiveZeros = (b == 0x00) ? consecutiveZeros + 1 : 0;

            int currentOffset = seiOffset;
            seiOffset++;
            payloadType += b;
            if (b != 0xFF) {
              if (payloadType == SEI_PAYLOAD_TYPE_ITU_T_T35) {
                t35PayloadTypeOffset = currentOffset;
              }
              break;
            }
          }

          if (seiOffset >= seiLimit) {
            if (t35PayloadTypeOffset != -1) {
              buffer.put(t35PayloadTypeOffset, (byte) SEI_PAYLOAD_TYPE_UNSPECIFIED);
            }
            break;
          }

          // Read payloadSize
          int payloadSize = 0;
          while (seiOffset < seiLimit) {
            int b = buffer.get(seiOffset) & 0xFF;
            if (b == 0x03 && consecutiveZeros >= 2) {
              consecutiveZeros = 0;
              seiOffset++;
              continue;
            }
            consecutiveZeros = (b == 0x00) ? consecutiveZeros + 1 : 0;

            seiOffset++;
            payloadSize += b;
            if (b != 0xFF) {
              break;
            }
          }

          if (t35PayloadTypeOffset != -1) {
            if (!keepHdr10Plus
                || !CodecSpecificDataUtil.isHdr10PlusMetadata(
                    buffer, seiOffset, Math.min(seiOffset + payloadSize, seiLimit))) {
              buffer.put(t35PayloadTypeOffset, (byte) SEI_PAYLOAD_TYPE_UNSPECIFIED);
            }
          }

          // Skip the logical payloadSize bytes, taking into account physical emulation
          // prevention bytes (0x03) that might be in the payload.
          for (int i = 0; i < payloadSize; i++) {
            if (seiOffset >= seiLimit) {
              break;
            }
            int b = buffer.get(seiOffset++) & 0xFF;
            if (b == 0x03 && consecutiveZeros >= 2) {
              // Emulation prevention byte. Doesn't count towards logical payloadSize.
              consecutiveZeros = 0;
              i--;
            } else {
              consecutiveZeros = (b == 0x00) ? consecutiveZeros + 1 : 0;
            }
          }
        }
        searchOffset = nextNalUnitOffset;
      } else {
        searchOffset = nalUnitOffset + 3;
      }
    }
  }

  /**
   * Finds the first NAL unit start code (0x00 00 01) in the buffer and returns the offset of the
   * first zero byte of the start code. Returns {@code limit} if no start code is found.
   */
  private static int findNalUnit(ByteBuffer buffer, int offset, int limit) {
    int consecutiveZeros = 0;
    int currentOffset = offset;
    while (currentOffset < limit) {
      int b = buffer.get(currentOffset) & 0xFF;
      if (consecutiveZeros >= 2 && b == 0x01) {
        return currentOffset - 2;
      }
      consecutiveZeros = (b == 0x00) ? consecutiveZeros + 1 : 0;
      currentOffset++;
    }
    return limit;
  }
}
