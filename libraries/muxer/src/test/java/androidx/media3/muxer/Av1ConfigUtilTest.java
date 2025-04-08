/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.muxer;

import static androidx.media3.muxer.Av1ConfigUtil.createAv1CodecConfigurationRecord;
import static androidx.media3.test.utils.TestUtil.createByteArray;
import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link Av1ConfigUtil} */
@RunWith(AndroidJUnit4.class)
public class Av1ConfigUtilTest {

  private static final ByteBuffer SEQUENCE_HEADER_AND_METADATA =
      ByteBuffer.wrap(
          createByteArray(
              0x0A, 0x0E, 0x00, 0x00, 0x00, 0x24, 0xC6, 0xAB, 0xDF, 0x3E, 0xFE, 0x24, 0x04, 0x04,
              0x04, 0x10, 0x2A, 0x11, 0x0E, 0x10, 0x00, 0xC8, 0xC6, 0x00, 0x00, 0x0C, 0x00, 0x00,
              0x00, 0x12, 0x03, 0xCE, 0x0A, 0x50, 0x24));
  private static final ByteBuffer METADATA_AND_SEQUENCE_HEADER =
      ByteBuffer.wrap(
          createByteArray(
              0x2A, 0x11, 0x0E, 0x10, 0x00, 0xC8, 0xC6, 0x00, 0x00, 0x0C, 0x00, 0x00, 0x00, 0x12,
              0x03, 0xCE, 0x0A, 0x50, 0x24, 0x0A, 0x0E, 0x00, 0x00, 0x00, 0x24, 0xC6, 0xAB, 0xDF,
              0x3E, 0xFE, 0x24, 0x04, 0x04, 0x04, 0x10));
  private static final ByteBuffer SEQUENCE_HEADER_YUV444 =
      ByteBuffer.wrap(
          createByteArray(
              0x0A, 0x0B, 0x20, 0x00, 0x00, 0x2D, 0x3D, 0xAB, 0xDF, 0x3E, 0xFE, 0x24, 0x04));

  @Test
  public void createAv1ConfigRecord_withSequenceHeaderAndMetaData_matchesExpected() {
    byte[] initializationData = createAv1CodecConfigurationRecord(SEQUENCE_HEADER_AND_METADATA);

    byte[] expectedInitializationData = {
      -127, 4, 12, 0, 10, 14, 0, 0, 0, 36, -58, -85, -33, 62, -2, 36, 4, 4, 4, 16, 42, 17, 14, 16,
      0, -56, -58, 0, 0, 12, 0, 0, 0, 18, 3, -50, 10, 80, 36
    };
    assertThat(initializationData).isEqualTo(expectedInitializationData);
  }

  @Test
  public void createAv1ConfigRecord_withMetaDataAndSequenceHeader_matchesExpected() {
    byte[] initializationData = createAv1CodecConfigurationRecord(METADATA_AND_SEQUENCE_HEADER);

    byte[] expectedInitializationData = {
      -127, 4, 12, 0, 10, 14, 0, 0, 0, 36, -58, -85, -33, 62, -2, 36, 4, 4, 4, 16, 42, 17, 14, 16,
      0, -56, -58, 0, 0, 12, 0, 0, 0, 18, 3, -50, 10, 80, 36
    };
    assertThat(initializationData).isEqualTo(expectedInitializationData);
  }

  @Test
  public void createAv1ConfigRecord_withSequenceHeaderYuv444_matchesExpected() {
    byte[] initializationData = createAv1CodecConfigurationRecord(SEQUENCE_HEADER_YUV444);

    byte[] expectedInitializationData = {
      -127, 37, 0, 0, 10, 11, 32, 0, 0, 45, 61, -85, -33, 62, -2, 36, 4
    };
    assertThat(initializationData).isEqualTo(expectedInitializationData);
  }
}
