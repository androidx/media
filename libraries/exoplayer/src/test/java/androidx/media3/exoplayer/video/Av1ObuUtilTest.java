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

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.test.utils.ImmutableByteArray;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link Av1ObuUtil}. */
@RunWith(AndroidJUnit4.class)
public final class Av1ObuUtilTest {

  private static final ImmutableByteArray
      TEMPORAL_DELIMITER_SEQUENCE_HEADER_AND_NON_HDR10_PLUS_METADATA =
          ImmutableByteArray.ofUnsigned(
              0x12, 0x00, 0x0a, 0x0e, 0x00, 0x00, 0x00, 0x2d, 0x4c, 0xff, 0xb3, 0xcc, 0xaf, 0x95,
              0x09, 0x12, 0x09, 0x04, 0x2a, 0x34, 0x04, 0xb5, 0x00, 0x90, 0x00, 0x01, 0xe0, 0x40,
              0x59, 0xdc, 0x1b, 0x00, 0x00, 0x00, 0x28, 0x03, 0xe8, 0x04, 0xe2, 0x06, 0xd6, 0x09,
              0xc4, 0x0f, 0xa0, 0x13, 0x3e, 0x27, 0x10, 0x2a, 0x08, 0x33, 0x48, 0x3f, 0x71, 0x51,
              0x60, 0x59, 0xdc, 0x46, 0x50, 0x33, 0x93, 0x32, 0xf2, 0x36, 0x71, 0x3b, 0x19, 0x3c,
              0xcc, 0x80);

  private static final ImmutableByteArray MALFORMED_METADATA =
      ImmutableByteArray.ofUnsigned(
          0x2a, 0x01, 0xb5); // Metadata OBU type, length 1, incomplete payload

  private static final ImmutableByteArray NO_METADATA =
      ImmutableByteArray.ofUnsigned(
          0x12, 0x00, 0x0a, 0x0e, 0x00, 0x00, 0x00, 0x2d, 0x4c, 0xff, 0xb3, 0xcc, 0xaf, 0x95, 0x09);

  private static final ImmutableByteArray
      TEMPORAL_DELIMITER_SEQUENCE_HEADER_AND_HDR10_PLUS_METADATA =
          ImmutableByteArray.ofUnsigned(
              0x12, 0x00, 0x0a, 0x0e, 0x00, 0x00, 0x00, 0x2d, 0x4c, 0xff, 0xb3, 0xcc, 0xaf, 0x95,
              0x09, 0x12, 0x09, 0x04, 0x2a, 0x34, 0x04, 0xb5, 0x00, 0x3c, 0x00, 0x01, 0x04, 0x40,
              0x59, 0xdc, 0x1b, 0x00, 0x00, 0x00, 0x28, 0x03, 0xe8, 0x04, 0xe2, 0x06, 0xd6, 0x09,
              0xc4, 0x0f, 0xa0, 0x13, 0x3e, 0x27, 0x10, 0x2a, 0x08, 0x33, 0x48, 0x3f, 0x71, 0x51,
              0x60, 0x59, 0xdc, 0x46, 0x50, 0x33, 0x93, 0x32, 0xf2, 0x36, 0x71, 0x3b, 0x19, 0x3c,
              0xcc, 0x80);

  private static final ImmutableByteArray
      SEQUENCE_HEADER_NON_HDR10_PLUS_METADATA_AND_HDR10_PLUS_METADATA =
          ImmutableByteArray.ofUnsigned(
              0x0a, 0x0e, 0x00, 0x00, 0x00, 0x2d, 0x4c, 0xff, 0xb3, 0xcc, 0xaf, 0x95, 0x09, 0x12,
              0x09, 0x04, 0x2a, 0x34, 0x04, 0xb5, 0x00, 0x90, 0x00, 0x01, 0xe0, 0x40, 0x59, 0xdc,
              0x1b, 0x00, 0x00, 0x00, 0x28, 0x03, 0xe8, 0x04, 0xe2, 0x06, 0xd6, 0x09, 0xc4, 0x0f,
              0xa0, 0x13, 0x3e, 0x27, 0x10, 0x2a, 0x08, 0x33, 0x48, 0x3f, 0x71, 0x51, 0x60, 0x59,
              0xdc, 0x46, 0x50, 0x33, 0x93, 0x32, 0xf2, 0x36, 0x71, 0x3b, 0x19, 0x3c, 0xcc, 0x80,
              0x2a, 0x34, 0x04, 0xb5, 0x00, 0x3c, 0x00, 0x01, 0x04, 0x40, 0x59, 0xdc, 0x1b, 0x00,
              0x00, 0x00, 0x28, 0x03, 0xe8, 0x04, 0xe2, 0x06, 0xd6, 0x09, 0xc4, 0x0f, 0xa0, 0x13,
              0x3e, 0x27, 0x10, 0x2a, 0x08, 0x33, 0x48, 0x3f, 0x71, 0x51, 0x60, 0x59, 0xdc, 0x46,
              0x50, 0x33, 0x93, 0x32, 0xf2, 0x36, 0x71, 0x3b, 0x19, 0x3c, 0xcc, 0x80);

  @Test
  public void stripNonHdr10PlusT35Metadata_rewritesNonHdr10PlusMetadataObu() {
    ByteBuffer buffer =
        ByteBuffer.wrap(TEMPORAL_DELIMITER_SEQUENCE_HEADER_AND_NON_HDR10_PLUS_METADATA.toArray());
    int originalPosition = buffer.position();
    int originalLimit = buffer.limit();
    Av1ObuUtil.stripNonHdr10PlusT35Metadata(buffer);
    assertThat(buffer.position()).isEqualTo(originalPosition);
    assertThat(buffer.limit()).isEqualTo(originalLimit);
    ByteBuffer expectedBuffer =
        ByteBuffer.wrap(TEMPORAL_DELIMITER_SEQUENCE_HEADER_AND_NON_HDR10_PLUS_METADATA.toArray());
    expectedBuffer.put(20, (byte) 0x1F);
    assertThat(buffer).isEqualTo(expectedBuffer);
  }

  @Test
  public void stripNonHdr10PlusT35Metadata_rewritesNonHdr10PlusAndLeavesHdr10PlusMetadataObu() {
    ByteBuffer buffer =
        ByteBuffer.wrap(SEQUENCE_HEADER_NON_HDR10_PLUS_METADATA_AND_HDR10_PLUS_METADATA.toArray());
    int originalPosition = buffer.position();
    int originalLimit = buffer.limit();
    Av1ObuUtil.stripNonHdr10PlusT35Metadata(buffer);
    assertThat(buffer.position()).isEqualTo(originalPosition);
    assertThat(buffer.limit()).isEqualTo(originalLimit);
    ByteBuffer expectedBuffer =
        ByteBuffer.wrap(SEQUENCE_HEADER_NON_HDR10_PLUS_METADATA_AND_HDR10_PLUS_METADATA.toArray());
    expectedBuffer.put(18, (byte) 0x1F);
    assertThat(buffer).isEqualTo(expectedBuffer);
  }

  @Test
  public void stripNonHdr10PlusT35Metadata_leavesHdr10PlusMetadataObus() {
    ByteBuffer buffer =
        ByteBuffer.wrap(TEMPORAL_DELIMITER_SEQUENCE_HEADER_AND_HDR10_PLUS_METADATA.toArray());
    Av1ObuUtil.stripNonHdr10PlusT35Metadata(buffer);
    assertThat(buffer)
        .isEqualTo(
            ByteBuffer.wrap(TEMPORAL_DELIMITER_SEQUENCE_HEADER_AND_HDR10_PLUS_METADATA.toArray()));
  }

  @Test
  public void stripAllT35Metadata_rewritesHdr10PlusMetadataObus() {
    ByteBuffer buffer =
        ByteBuffer.wrap(TEMPORAL_DELIMITER_SEQUENCE_HEADER_AND_HDR10_PLUS_METADATA.toArray());
    int originalPosition = buffer.position();
    int originalLimit = buffer.limit();
    ByteBuffer expectedBuffer =
        ByteBuffer.wrap(TEMPORAL_DELIMITER_SEQUENCE_HEADER_AND_HDR10_PLUS_METADATA.toArray());
    expectedBuffer.put(20, (byte) 0x1F);

    Av1ObuUtil.stripAllT35Metadata(buffer);

    assertThat(buffer.position()).isEqualTo(originalPosition);
    assertThat(buffer.limit()).isEqualTo(originalLimit);
    assertThat(buffer).isEqualTo(expectedBuffer);
  }

  @Test
  public void stripNonHdr10PlusT35Metadata_leavesBufferUnchanged_whenNoMetadata() {
    ByteBuffer buffer = ByteBuffer.wrap(NO_METADATA.toArray());
    int originalPosition = buffer.position();
    int originalLimit = buffer.limit();

    Av1ObuUtil.stripNonHdr10PlusT35Metadata(buffer);

    assertThat(buffer.position()).isEqualTo(originalPosition);
    assertThat(buffer.limit()).isEqualTo(originalLimit);
    assertThat(buffer).isEqualTo(ByteBuffer.wrap(NO_METADATA.toArray()));
  }

  @Test
  public void stripAllT35Metadata_rewritesNonHdr10PlusMetadataObus() {
    ByteBuffer buffer =
        ByteBuffer.wrap(TEMPORAL_DELIMITER_SEQUENCE_HEADER_AND_NON_HDR10_PLUS_METADATA.toArray());
    int originalPosition = buffer.position();
    int originalLimit = buffer.limit();
    ByteBuffer expectedBuffer =
        ByteBuffer.wrap(TEMPORAL_DELIMITER_SEQUENCE_HEADER_AND_NON_HDR10_PLUS_METADATA.toArray());
    expectedBuffer.put(20, (byte) 0x1F);

    Av1ObuUtil.stripAllT35Metadata(buffer);

    assertThat(buffer.position()).isEqualTo(originalPosition);
    assertThat(buffer.limit()).isEqualTo(originalLimit);
    assertThat(buffer).isEqualTo(expectedBuffer);
  }

  @Test
  public void stripAllT35Metadata_rewritesBothHdr10PlusAndNonHdr10PlusMetadataObus() {
    ByteBuffer buffer =
        ByteBuffer.wrap(SEQUENCE_HEADER_NON_HDR10_PLUS_METADATA_AND_HDR10_PLUS_METADATA.toArray());
    int originalPosition = buffer.position();
    int originalLimit = buffer.limit();
    ByteBuffer expectedBuffer =
        ByteBuffer.wrap(SEQUENCE_HEADER_NON_HDR10_PLUS_METADATA_AND_HDR10_PLUS_METADATA.toArray());
    expectedBuffer.put(18, (byte) 0x1F);
    expectedBuffer.put(72, (byte) 0x1F);

    Av1ObuUtil.stripAllT35Metadata(buffer);

    assertThat(buffer.position()).isEqualTo(originalPosition);
    assertThat(buffer.limit()).isEqualTo(originalLimit);
    assertThat(buffer).isEqualTo(expectedBuffer);
  }

  @Test
  public void stripNonHdr10PlusT35Metadata_doesNotCrash_whenMalformedMetadata() {
    ByteBuffer buffer = ByteBuffer.wrap(MALFORMED_METADATA.toArray());
    int originalPosition = buffer.position();
    int originalLimit = buffer.limit();

    Av1ObuUtil.stripNonHdr10PlusT35Metadata(buffer);

    assertThat(buffer.position()).isEqualTo(originalPosition);
    assertThat(buffer.limit()).isEqualTo(originalLimit);
    assertThat(buffer).isEqualTo(ByteBuffer.wrap(MALFORMED_METADATA.toArray()));
  }
}
