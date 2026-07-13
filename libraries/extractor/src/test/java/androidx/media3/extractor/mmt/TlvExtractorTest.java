/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.extractor.mmt;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.extractor.ExtractorInput;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.primitives.Bytes;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link TlvExtractor}. */
@RunWith(AndroidJUnit4.class)
public class TlvExtractorTest {

  /** A single TLV null packet: sync=0x7F, packet_type=0xFF (null), data_length=0. */
  private static final byte[] TLV_NULL_PACKET = TestUtil.createByteArray(0x7F, 0xFF, 0x00, 0x00);

  @Test
  public void sniff_onTlvStream_returnsTrue() throws Exception {
    byte[] data =
        Bytes.concat(
            TLV_NULL_PACKET,
            TLV_NULL_PACKET,
            TLV_NULL_PACKET,
            TLV_NULL_PACKET,
            TLV_NULL_PACKET,
            TLV_NULL_PACKET);
    ExtractorInput input = new FakeExtractorInput.Builder().setData(data).build();

    assertThat(new TlvExtractor().sniff(input)).isTrue();
  }

  @Test
  public void sniff_onNonTlvStream_returnsFalse() throws Exception {
    byte[] data = TestUtil.createByteArray(0x47, 0x40, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00);
    ExtractorInput input = new FakeExtractorInput.Builder().setData(data).build();

    assertThat(new TlvExtractor().sniff(input)).isFalse();
  }

  @Test
  public void sniff_onStreamWithUnknownPacketType_returnsFalse() throws Exception {
    // Valid sync byte but an unknown packet_type (0x55) must not be treated as TLV.
    byte[] data = TestUtil.createByteArray(0x7F, 0x55, 0x00, 0x00, 0x7F, 0x55, 0x00, 0x00);
    ExtractorInput input = new FakeExtractorInput.Builder().setData(data).build();

    assertThat(new TlvExtractor().sniff(input)).isFalse();
  }
}
