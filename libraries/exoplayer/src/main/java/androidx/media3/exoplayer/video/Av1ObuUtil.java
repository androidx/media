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

import androidx.annotation.Nullable;
import androidx.media3.common.util.CodecSpecificDataUtil;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.ObuParser;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Utility methods for AV1 OBUs. */
@UnstableApi
public final class Av1ObuUtil {

  private static final String TAG = "Av1ObuUtil";

  private Av1ObuUtil() {}

  /**
   * Extracts HAGC (ST 2094-50) metadata from the first valid HAGC ITU-T T.35 metadata OBU in the
   * buffer. If one is found, all ITU-T T.35 metadata OBUs in the buffer (including HAGC and HDR10+)
   * are rewritten as unknown metadata OBUs so that the decoder does not prefer any in-band T.35
   * metadata over the returned HAGC metadata.
   *
   * <p>If no valid HAGC metadata OBU is found, {@code buffer} remains unchanged. In either case,
   * the {@code position()} and {@code limit()} of {@code buffer} remain unchanged.
   *
   * @param buffer The {@link ByteBuffer} containing AV1 OBUs.
   * @return The HAGC payload without the ITU-T T.35 header and the AV1 OBU trailing bits ({@code
   *     0x80} followed by any zero padding bytes), or {@code null} if no valid HAGC metadata OBU is
   *     found.
   */
  @Nullable
  public static byte[] extractHagcMetadataAndStripAllT35Metadata(ByteBuffer buffer) {
    @Nullable byte[] hagcData = null;
    List<Integer> t35MetadataTypeIndices = new ArrayList<>();
    for (ObuParser.Obu obu : ObuParser.split(buffer)) {
      if (obu.type != ObuParser.OBU_METADATA) {
        continue;
      }
      ObuParser.Metadata metadata;
      try {
        metadata = ObuParser.Metadata.parse(obu);
      } catch (BufferUnderflowException e) {
        // Malformed metadata OBU, do not attempt to process it and let the underlying decoder deal
        // with any potential errors.
        continue;
      }
      if (metadata.type != ObuParser.Metadata.METADATA_TYPE_ITUT_T35) {
        continue;
      }
      t35MetadataTypeIndices.add(obu.payload.position());
      if (!CodecSpecificDataUtil.isHagcMetadata(metadata.payload)) {
        continue;
      }
      if (hagcData != null) {
        Log.w(TAG, "Found multiple HAGC metadata OBUs in the same temporal unit. Using the first.");
        continue;
      }
      hagcData = readHagcPayload(metadata.payload);
    }
    if (hagcData != null) {
      for (int index : t35MetadataTypeIndices) {
        // Mark as an unknown metadata OBU. See stripT35Metadata for details.
        buffer.put(index, (byte) 0x1F);
      }
    }
    return hagcData;
  }

  /**
   * Returns the HAGC payload in {@code payload} without the ITU-T T.35 header and the AV1 OBU
   * trailing bits, or {@code null} if the payload is empty or the trailing bits are missing.
   */
  @Nullable
  private static byte[] readHagcPayload(ByteBuffer payload) {
    int startIndex = payload.position() + CodecSpecificDataUtil.HAGC_T35_HEADER_LENGTH;
    int lastNonZeroIndex = payload.limit() - 1;
    while (lastNonZeroIndex >= startIndex && payload.get(lastNonZeroIndex) == 0) {
      lastNonZeroIndex--;
    }
    int length = lastNonZeroIndex - startIndex;
    if (length <= 0 || (payload.get(lastNonZeroIndex) & 0xFF) != 0x80) {
      // Malformed OBU or empty HAGC payload.
      return null;
    }
    byte[] hagcData = new byte[length];
    payload.position(startIndex);
    payload.get(hagcData);
    return hagcData;
  }

  /**
   * Rewrites all ITU-T T35 metadata OBUs from the buffer. This is done to prevent the decoder from
   * prioritizing in-band T.35 metadata (like HDR10+, Dolby Vision, etc.) over out-of-band metadata.
   * If a T.35 metadata OBU is found, {@code buffer} will be rewritten with that metadata OBU marked
   * as an unknown metadata OBU. If no T.35 metadata OBUs are found, {@code buffer} will remain
   * unchanged. In either case, the {@code position()} and {@code limit()} of the {@code buffer}
   * remain unchanged.
   */
  public static void stripAllT35Metadata(ByteBuffer buffer) {
    stripT35Metadata(buffer, /* keepHdr10Plus= */ false);
  }

  /**
   * Rewrites all ITU-T T35 metadata OBUs from the buffer that are not HDR10+. This is done to
   * prevent the decoder on older SDK versions from misinterpreting them as HDR10+ metadata. If a
   * non-HDR10+ metadata OBU is found, {@code buffer} will be rewritten with that metadata OBU
   * marked as an unknown metadata OBU. If no non-HDR10+ metadata OBUs are found, {@code buffer}
   * will remain unchanged. In either case, the {@code position()} and {@code limit()} of the {@code
   * buffer} remain unchanged. This function is needed only when using MediaCodec on older SDK
   * versions and is not necessary for other AV1 decoders.
   */
  public static void stripNonHdr10PlusT35Metadata(ByteBuffer buffer) {
    stripT35Metadata(buffer, /* keepHdr10Plus= */ true);
  }

  private static void stripT35Metadata(ByteBuffer buffer, boolean keepHdr10Plus) {
    List<ObuParser.Obu> obus = ObuParser.split(buffer.asReadOnlyBuffer());
    for (ObuParser.Obu obu : obus) {
      if (obu.type != ObuParser.OBU_METADATA) {
        continue;
      }
      ObuParser.Metadata metadata;
      try {
        metadata = ObuParser.Metadata.parse(obu);
      } catch (BufferUnderflowException e) {
        // Malformed metadata OBU, do not attempt to rewrite it and let the underlying decoder deal
        // with any potential errors.
        continue;
      }
      if (metadata.type != ObuParser.Metadata.METADATA_TYPE_ITUT_T35) {
        continue;
      }
      if (!keepHdr10Plus || !CodecSpecificDataUtil.isHdr10PlusMetadata(metadata.payload)) {
        // This is a metadata OBU with metadata type ITUT-T35, that we want to rewrite. Set the
        // first byte of the metadata type leb128 in the OBU payload to 0x1F to mark it as an
        // unknown metadata OBU. 0x1F is the leb128() encoding of the value 31 (which according to
        // the AV1 spec is "Unregistered user private":
        // https://aomediacodec.github.io/av1-spec/#general-metadata-obu-semantics)
        buffer.put(obu.payload.position(), (byte) 0x1F);
      }
    }
  }
}
