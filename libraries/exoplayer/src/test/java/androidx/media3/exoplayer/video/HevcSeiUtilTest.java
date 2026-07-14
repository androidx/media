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

/** Tests for {@link HevcSeiUtil}. */
@RunWith(AndroidJUnit4.class)
public final class HevcSeiUtilTest {

  private static final ImmutableByteArray SEI_NAL_UNIT_HDR10_PLUS =
      ImmutableByteArray.ofUnsigned(
          0x00, 0x00, 0x00, 0x01, // NAL start code
          0x4E, 0x01, // NAL Header (type 39)
          0x04, // payloadType: 4 (T.35)
          0x13, // payloadSize: 19
          0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04, 0x00, // HDR10+ header
          0x00, 0x00, 0x03, 0x00, // Emulation prevention byte (0x03)
          0x00, 0x00, 0x03, 0x01, // Emulation prevention byte (0x03)
          0x01, 0x02, 0x03, 0x04, 0x05, 0x06); // Some payload bytes

  private static final ImmutableByteArray SEI_NAL_UNIT_MULTIPLE_MESSAGES =
      ImmutableByteArray.ofUnsigned(
          0x00,
          0x00,
          0x00,
          0x01, // NAL start code
          0x4E,
          0x01, // NAL Header (type 39)
          // First message: type 5 (User Data Unregistered), size 4
          0x05,
          0x04,
          0xAA,
          0xBB,
          0xCC,
          0xDD,
          // Second message: type 4 (T.35), size 5
          0x04,
          0x05,
          0xB5,
          0x00,
          0x3C,
          0x00,
          0x01,
          // Third message: type 4 (T.35), size 5
          0x04,
          0x05,
          0xB5,
          0x00,
          0x3C,
          0x00,
          0x02,
          0x80); // rbsp_trailing_bits

  private static final ImmutableByteArray SEI_NAL_UNIT_EMULATION_IN_SIZE =
      ImmutableByteArray.ofUnsigned(
          0x00, 0x00, 0x00, 0x01, // NAL start code
          0x4E, 0x01, // NAL Header (type 39)
          0x04, // payloadType: 4 (T.35)
          0x00, 0x00, 0x03, 0xFF, // payloadSize: 255 (with emulation prevention byte)
          0x80); // rbsp_trailing_bits

  @Test
  public void stripAllT35Metadata_rewritesT35PayloadTypeToUnspecified() {
    ByteBuffer buffer = ByteBuffer.wrap(SEI_NAL_UNIT_HDR10_PLUS.toArray());
    int originalPosition = buffer.position();
    int originalLimit = buffer.limit();

    ByteBuffer expectedBuffer = ByteBuffer.wrap(SEI_NAL_UNIT_HDR10_PLUS.toArray());
    expectedBuffer.put(
        6, (byte) HevcSeiUtil.SEI_PAYLOAD_TYPE_UNSPECIFIED); // Change payloadType from 4

    HevcSeiUtil.stripAllT35Metadata(buffer);

    assertThat(buffer.position()).isEqualTo(originalPosition);
    assertThat(buffer.limit()).isEqualTo(originalLimit);
    assertThat(buffer.array()).isEqualTo(expectedBuffer.array());
  }

  @Test
  public void stripAllT35Metadata_multipleMessages_rewritesOnlyT35() {
    ByteBuffer buffer = ByteBuffer.wrap(SEI_NAL_UNIT_MULTIPLE_MESSAGES.toArray());

    ByteBuffer expectedBuffer = ByteBuffer.wrap(SEI_NAL_UNIT_MULTIPLE_MESSAGES.toArray());
    // Offset 6 is first message type (5), stays 5
    // Offset 12 is second message type (4)
    expectedBuffer.put(12, (byte) HevcSeiUtil.SEI_PAYLOAD_TYPE_UNSPECIFIED);
    // Offset 19 is third message type (4)
    expectedBuffer.put(19, (byte) HevcSeiUtil.SEI_PAYLOAD_TYPE_UNSPECIFIED);

    HevcSeiUtil.stripAllT35Metadata(buffer);

    assertThat(buffer.array()).isEqualTo(expectedBuffer.array());
  }

  @Test
  public void stripAllT35Metadata_handlesEmulationPreventionInSize() {
    ByteBuffer buffer = ByteBuffer.wrap(SEI_NAL_UNIT_EMULATION_IN_SIZE.toArray());

    ByteBuffer expectedBuffer = ByteBuffer.wrap(SEI_NAL_UNIT_EMULATION_IN_SIZE.toArray());
    // Offset 6 is payload type (4)
    expectedBuffer.put(6, (byte) HevcSeiUtil.SEI_PAYLOAD_TYPE_UNSPECIFIED);

    HevcSeiUtil.stripAllT35Metadata(buffer);

    assertThat(buffer.array()).isEqualTo(expectedBuffer.array());
  }
}
