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

import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link MmtSignalingParser}. */
@RunWith(AndroidJUnit4.class)
public class MmtSignalingParserTest {

  /**
   * A complete, non-aggregated signaling payload carrying a PA message whose MMT Package Table
   * declares a single {@code hev1} asset delivered on {@code packet_id} 0x1001.
   */
  private static final byte[] PA_MESSAGE_WITH_SINGLE_HEVC_ASSET =
      TestUtil.createByteArray(
          // Signaling payload header: FI=complete, LEF=0, A=0; fragmentation_counter=0.
          0x00, 0x00,
          // PA signaling_message: message_id=0x0000, version=0x00, length (32-bit, unused)=27.
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1B,
          // PA body: number_of_tables=0 and the tables inlined directly (as ARIB STD-B60 streams
          // such as NHK BS4K/BS8K actually transmit them).
          0x00,
          // MMT Package Table (26 bytes):
          0x20, 0x00, // table_id, version.
          0x00, 0x16, // table_length = 22 (bytes following this field).
          0x00, // reserved(6) + MPT_mode(2).
          0x00, // MMT_package_id_length.
          0x00, 0x00, // MPT_descriptors_length.
          0x01, // number_of_assets.
          // Asset:
          0x00, // identifier_type.
          0x00, 0x00, 0x00, 0x00, // asset_id_scheme.
          0x00, // asset_id_length.
          0x68, 0x65, 0x76, 0x31, // asset_type = "hev1".
          0x00, // reserved(7) + asset_clock_relation_flag(1).
          0x01, // location_count.
          0x00, 0x10, 0x01, // location_type=0x00 (same flow), packet_id=0x1001.
          0x00, 0x00 // asset_descriptors_length.
          );

  @Test
  public void consume_paMessageWithHevcAsset_discoversAsset() {
    MmtSignalingParser parser = new MmtSignalingParser();

    boolean updated =
        parser.consume(new ParsableByteArray(PA_MESSAGE_WITH_SINGLE_HEVC_ASSET));

    assertThat(updated).isTrue();
    ImmutableList<MmtSignalingParser.Asset> assets = parser.getAssets();
    assertThat(assets).hasSize(1);
    assertThat(assets.get(0).packetId).isEqualTo(0x1001);
    assertThat(assets.get(0).assetType).isEqualTo(MmtSignalingParser.ASSET_TYPE_HEV1);
  }

  @Test
  public void consume_nonPaMessage_leavesAssetsEmpty() {
    MmtSignalingParser parser = new MmtSignalingParser();
    // Signaling header (complete) + a message with message_id 0x8000 (not a PA message).
    byte[] payload = TestUtil.createByteArray(0x00, 0x00, 0x80, 0x00, 0x00, 0x00, 0x00);

    boolean updated = parser.consume(new ParsableByteArray(payload));

    assertThat(updated).isFalse();
    assertThat(parser.getAssets()).isEmpty();
  }
}
