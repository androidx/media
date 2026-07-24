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

import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
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

  /**
   * Drives a header-compressed IP (context type 0x61) TLV packet that wraps an MMTP signaling
   * packet carrying a PA message / MMT Package Table with a single HEVC asset, mirroring how NHK
   * BS4K/BS8K deliver signaling, and verifies that a video track is created.
   */
  @Test
  public void read_compressedIpPacketWithMpt_createsVideoTrack() throws Exception {
    // Signaling payload: signaling header + PA message + inlined MMT Package Table (hev1 asset).
    byte[] signalingPayload =
        TestUtil.createByteArray(
            0x00, 0x00, // Signaling payload header (FI=complete, A=0) + fragmentation_counter.
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1B, // PA: message_id, version, length=27.
            0x00, // number_of_tables=0 (tables inlined).
            0x20, 0x00, 0x00, 0x16, // MPT: table_id, version, length=22.
            0x00, 0x00, 0x00, 0x00, // reserved+mode, pkg_id_len=0, MPT_descriptors_length=0.
            0x01, // number_of_assets=1.
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // identifier_type, asset_id_scheme, asset_id_length.
            0x68, 0x65, 0x76, 0x31, // asset_type="hev1".
            0x00, 0x01, // reserved+clock, location_count=1.
            0x00, 0x10, 0x01, // location_type=0x00, packet_id=0x1001.
            0x00, 0x00 // asset_descriptors_length=0.
            );
    // MMTP packet header: version 0, type 0x02 (signaling), packet_id, timestamp, sequence number.
    byte[] mmtpHeader =
        TestUtil.createByteArray(
            0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00);
    // Compressed-IP context identification header (CID+SN, context type 0x61) then the MMTP packet.
    byte[] tlvPayload =
        Bytes.concat(TestUtil.createByteArray(0x00, 0x00, 0x61), mmtpHeader, signalingPayload);
    byte[] tlvHeader =
        TestUtil.createByteArray(0x7F, 0x03, (tlvPayload.length >> 8) & 0xFF, tlvPayload.length & 0xFF);
    byte[] data = Bytes.concat(tlvHeader, tlvPayload);

    FakeExtractorOutput output = new FakeExtractorOutput();
    TlvExtractor extractor = new TlvExtractor();
    extractor.init(output);
    ExtractorInput input = new FakeExtractorInput.Builder().setData(data).build();
    PositionHolder seekPosition = new PositionHolder();
    int result = Extractor.RESULT_CONTINUE;
    while (result != Extractor.RESULT_END_OF_INPUT) {
      result = extractor.read(input, seekPosition);
    }

    assertThat(output.numberOfTracks).isEqualTo(1);
    assertThat(output.tracksEnded).isTrue();
  }
}
