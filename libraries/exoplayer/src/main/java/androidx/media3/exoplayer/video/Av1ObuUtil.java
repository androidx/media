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
import androidx.media3.container.ObuParser;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.List;

/** Utility methods for AV1 OBUs. */
@UnstableApi
public final class Av1ObuUtil {

  private Av1ObuUtil() {}

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
