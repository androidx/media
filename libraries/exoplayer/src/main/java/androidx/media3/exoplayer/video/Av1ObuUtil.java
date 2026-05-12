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

import static android.os.Build.VERSION.SDK_INT;

import androidx.media3.container.ObuParser;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

/** Utility methods for AV1 OBUs. */
/*package*/ final class Av1ObuUtil {

  // Expected magic bytes for HDR10+ metadata in AV1 OBU. Spec:
  // https://aomediacodec.github.io/av1-hdr10plus/#hdr10plus-metadata
  private static final byte[] expectedAv1Hdr10PlusPayloadPrefix = {
    // itu_t_t35_country_code == 0xB5
    (byte) 0xB5,
    // itu_t_t35_terminal_provider_code == 0x003C
    (byte) 0x00,
    (byte) 0x3C,
    // itu_t_t35_terminal_provider_oriented_code == 0x0001
    (byte) 0x00,
    (byte) 0x01,
    // application_identifier == 0x04
    (byte) 0x04,
  };

  private Av1ObuUtil() {}

  /**
   * Rewrites all ITU-T T35 metadata OBUs from the buffer that are not HDR10+. This is done to
   * prevent the decoder on older SDK versions from misinterpreting them as HDR10+ metadata. If a
   * non-HDR10+ metadata OBU is found, {@code buffer} will be rewritten with that metadata OBU
   * marked as an unknown metadata OBU. If no non-HDR10+ metadata OBUs are found, {@code buffer}
   * will remain unchanged. In either case, the {@code position()} and {@code limit()} of the {@code
   * buffer} remain unchanged. This function is needed only when using MediaCodec on older SDK
   * versions and is not necessary for other AV1 decoders.
   */
  /* package */ static void maybeRewriteAv1MetadataObus(ByteBuffer buffer) {
    if (SDK_INT >= 37) {
      // SDK versions >= 37 do not have this issue, do nothing.
      return;
    }
    List<ObuParser.Obu> obus = ObuParser.split(buffer.asReadOnlyBuffer());
    for (ObuParser.Obu obu : obus) {
      if (hasNonHdr10PlusMetadata(obu)) {
        // This is a metadata OBU with metadata type ITUT-T35, but not HDR10+ metadata. Set the
        // first byte of the OBU to 0x1F to mark it as an unknown OBU. 0x1F is the leb128() encoding
        // of the value 31 (which according to the AV1 spec is "Unregistered user private":
        // https://aomediacodec.github.io/av1-spec/#general-metadata-obu-semantics)
        buffer.put(obu.payload.position(), (byte) 0x1F);
      }
    }
  }

  private static boolean hasNonHdr10PlusMetadata(ObuParser.Obu obu) {
    if (obu.type != ObuParser.OBU_METADATA) {
      return false;
    }
    ObuParser.Metadata metadata;
    try {
      metadata = ObuParser.Metadata.parse(obu);
    } catch (BufferUnderflowException e) {
      // Malformed metadata OBU, do not attempt to rewrite it and let the underlying decoder deal
      // with any potential errors.
      return false;
    }
    if (metadata.type != ObuParser.Metadata.METADATA_TYPE_ITUT_T35) {
      return false;
    }
    if (metadata.payload.remaining() < expectedAv1Hdr10PlusPayloadPrefix.length) {
      return true;
    }
    byte[] prefix = new byte[expectedAv1Hdr10PlusPayloadPrefix.length];
    metadata.payload.asReadOnlyBuffer().get(prefix);
    return !Arrays.equals(prefix, expectedAv1Hdr10PlusPayloadPrefix);
  }
}
