/*
 * Copyright 2022 The Android Open Source Project
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
package androidx.media3.extractor;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link MpeghUtil}. */
@RunWith(AndroidJUnit4.class)
public final class MpeghUtilTest {

  private static final String MPEGH_TRUNC_PACKET0 = "e1480283e0";
  private static final String MPEGH_3DAFRAME_PACKET0 = "48673000d80ab1020220000f4ce0000000000c6952969696969696969696969696969696969696969696969696969696969696969696969696969696969696969696969696969696969696969696969696969696969696969696969696969696969696969696969695";
  private static final String MPEGH_TRUNCATION_FRAME = MPEGH_TRUNC_PACKET0 + MPEGH_3DAFRAME_PACKET0;

  private static final String MPEGH_SYNC_PACKET1 = "c001a5";
  private static final String MPEGH_3DACONFIG_PACKET1 = "30410b1940800101366069e2d01110088120440110000207fd8020108000103664015900c400008190028002080000bbf8040029aec00a5008000a3a04638b00e40020";
  private static final String MPEGH_ASI_PACKET1 = "70378080c01000080000248400060000000001e000032b73383a1b430b73732b62100a00180000cadcce0aa6c6cadcca84c00080fc01bc0000";
  private static final String MPEGH_BUFFERINFO_PACKET1 = "e0f00100";
  private static final String MPEGH_MARKER_PACKET1 = "e03006e0005c73e212";
  private static final String MPEGH_TRUNC_PACKET1 = "e15002a020";
  private static final String MPEGH_3DAFRAME_PACKET1 = "5132d020200fd40b5a5a401400b05ccc0000007ffd377dfb80fdebdfc29aa83fa2b44d25293baf850d9c0c08102006fd4aa35410ffc466181cb8259f2883bfa798f29c138003cf44589fb89ecea9e697b148eff82fadcfbbeb4e756625a4841ec31be87c2bf1f1fd90c7472217f2942c3fd12574b69a3014e883de42c146c9e2c889ffe158026935e513c9dd2a9bc3c4017b04a541f20ca47c7704790bdd303d55ea819aada8ac811740670457ac471163c734adcb6815e457d65c9100b5e1f42c1c0216a860bc9948255bbdbc0d0d085af3beda99c234d2de38e8bfe00b98307ad2c78791fd9875b392e72bea1e259a6686392bd7e14b3b0b0621007751dcbedbcdc8724577124553438f4c9b38b608e3b81669e80887e2331b1867f8f0868e28cff81ffe261cee52cb86283001f44010b0efdc";
  private static final String MPEGH_CONFIG_FRAME =
      MPEGH_SYNC_PACKET1 + MPEGH_3DACONFIG_PACKET1 + MPEGH_ASI_PACKET1 + MPEGH_BUFFERINFO_PACKET1
          + MPEGH_MARKER_PACKET1 + MPEGH_TRUNC_PACKET1 + MPEGH_3DAFRAME_PACKET1;

  private static final String MPEGH_COMBINED_FRAMES = MPEGH_TRUNCATION_FRAME + MPEGH_CONFIG_FRAME;


  @Test
  public void parseStandardFrameLength_configFrame() {
    byte[] data = Util.getBytesFromHexString(MPEGH_CONFIG_FRAME);
    ByteBuffer buffer = ByteBuffer.wrap(data);
    int frameLength = MpeghUtil.getStandardFrameLength(buffer);
    assertThat(frameLength).isEqualTo(1024);
  }

  @Test
  public void parseStandardFrameLength_combinedFrames() {
    byte[] data = Util.getBytesFromHexString(MPEGH_COMBINED_FRAMES);
    ByteBuffer buffer = ByteBuffer.wrap(data);
    int frameLength = MpeghUtil.getStandardFrameLength(buffer);
    assertThat(frameLength).isEqualTo(1024);
  }

  @Test
  public void parseStandardFrameLength_3daConfigPacket() {
    byte[] data = Util.getBytesFromHexString(MPEGH_3DACONFIG_PACKET1);
    ByteBuffer buffer = ByteBuffer.wrap(data);
    int frameLength = MpeghUtil.getStandardFrameLength(buffer);
    assertThat(frameLength).isEqualTo(1024);
  }

  @Test
  public void parseTruncationSampleCount_configFrame() {
    byte[] data = Util.getBytesFromHexString(MPEGH_CONFIG_FRAME);
    ParsableByteArray byteArray = new ParsableByteArray(data);
    int truncationSamples = MpeghUtil.getTruncationSampleCount(byteArray);
    assertThat(truncationSamples).isEqualTo(32);
  }

  @Test
  public void parseTruncationSampleCount_truncationFrame() {
    byte[] data = Util.getBytesFromHexString(MPEGH_TRUNCATION_FRAME);
    ParsableByteArray byteArray = new ParsableByteArray(data);
    int truncationSamples = MpeghUtil.getTruncationSampleCount(byteArray);
    assertThat(truncationSamples).isEqualTo(992);
  }

  @Test
  public void parseTruncationSampleCount_combinedFrames() {
    byte[] data = Util.getBytesFromHexString(MPEGH_COMBINED_FRAMES);
    ParsableByteArray byteArray = new ParsableByteArray(data);
    int truncationSamples = MpeghUtil.getTruncationSampleCount(byteArray);
    assertThat(truncationSamples).isEqualTo(1024);
  }

  @Test
  public void parseTruncationSampleCount_truncationPacket0() {
    byte[] data = Util.getBytesFromHexString(MPEGH_TRUNC_PACKET0);
    ParsableByteArray byteArray = new ParsableByteArray(data);
    int truncationSamples = MpeghUtil.getTruncationSampleCount(byteArray);
    assertThat(truncationSamples).isEqualTo(992);
  }

  @Test
  public void parseTruncationSampleCount_truncationPacket1() {
    byte[] data = Util.getBytesFromHexString(MPEGH_TRUNC_PACKET1);
    ParsableByteArray byteArray = new ParsableByteArray(data);
    int truncationSamples = MpeghUtil.getTruncationSampleCount(byteArray);
    assertThat(truncationSamples).isEqualTo(32);
  }

  @Test
  public void parseMhasPacketHeader_syncPacket() throws Exception {
    byte[] data = Util.getBytesFromHexString(MPEGH_SYNC_PACKET1);
    ParsableBitArray bitArray = new ParsableBitArray(data);
    MpeghUtil.MhasPacketHeader header = new MpeghUtil.MhasPacketHeader();
    boolean success = MpeghUtil.parseMhasPacketHeader(bitArray, header);
    assertThat(success).isEqualTo(true);
    assertThat(header.packetType).isEqualTo(MpeghUtil.MhasPacketHeader.PACTYP_SYNC);
    assertThat(header.packetLength).isEqualTo(1);
    assertThat(header.packetLabel).isEqualTo(0);
  }

  @Test
  public void parseMhasPacketHeader_3daConfigPacket() throws Exception {
    byte[] data = Util.getBytesFromHexString(MPEGH_3DACONFIG_PACKET1);
    ParsableBitArray bitArray = new ParsableBitArray(data);
    MpeghUtil.MhasPacketHeader header = new MpeghUtil.MhasPacketHeader();
    boolean success = MpeghUtil.parseMhasPacketHeader(bitArray, header);
    assertThat(success).isEqualTo(true);
    assertThat(header.packetType).isEqualTo(MpeghUtil.MhasPacketHeader.PACTYP_MPEGH3DACFG);
    assertThat(header.packetLength).isEqualTo(65);
    assertThat(header.packetLabel).isEqualTo(2);
  }

  @Test
  public void parseMhasPacketHeader_audioSceneInfoPacket() throws Exception {
    byte[] data = Util.getBytesFromHexString(MPEGH_ASI_PACKET1);
    ParsableBitArray bitArray = new ParsableBitArray(data);
    MpeghUtil.MhasPacketHeader header = new MpeghUtil.MhasPacketHeader();
    boolean success = MpeghUtil.parseMhasPacketHeader(bitArray, header);
    assertThat(success).isEqualTo(true);
    assertThat(header.packetType).isEqualTo(MpeghUtil.MhasPacketHeader.PACTYP_AUDIOSCENEINFO);
    assertThat(header.packetLength).isEqualTo(55);
    assertThat(header.packetLabel).isEqualTo(2);
  }

  @Test
  public void parseMhasPacketHeader_bufferInfoPacket() throws Exception {
    byte[] data = Util.getBytesFromHexString(MPEGH_BUFFERINFO_PACKET1);
    ParsableBitArray bitArray = new ParsableBitArray(data);
    MpeghUtil.MhasPacketHeader header = new MpeghUtil.MhasPacketHeader();
    boolean success = MpeghUtil.parseMhasPacketHeader(bitArray, header);
    assertThat(success).isEqualTo(true);
    assertThat(header.packetType).isEqualTo(MpeghUtil.MhasPacketHeader.PACTYP_BUFFERINFO);
    assertThat(header.packetLength).isEqualTo(1);
    assertThat(header.packetLabel).isEqualTo(2);
  }

  @Test
  public void parseMhasPacketHeader_markerPacket() throws Exception {
    byte[] data = Util.getBytesFromHexString(MPEGH_MARKER_PACKET1);
    ParsableBitArray bitArray = new ParsableBitArray(data);
    MpeghUtil.MhasPacketHeader header = new MpeghUtil.MhasPacketHeader();
    boolean success = MpeghUtil.parseMhasPacketHeader(bitArray, header);
    assertThat(success).isEqualTo(true);
    assertThat(header.packetType).isEqualTo(MpeghUtil.MhasPacketHeader.PACTYP_MARKER);
    assertThat(header.packetLength).isEqualTo(6);
    assertThat(header.packetLabel).isEqualTo(2);
  }

  @Test
  public void parseMhasPacketHeader_truncationPacket0() throws Exception {
    byte[] data = Util.getBytesFromHexString(MPEGH_TRUNC_PACKET0);
    ParsableBitArray bitArray = new ParsableBitArray(data);
    MpeghUtil.MhasPacketHeader header = new MpeghUtil.MhasPacketHeader();
    boolean success = MpeghUtil.parseMhasPacketHeader(bitArray, header);
    assertThat(success).isEqualTo(true);
    assertThat(header.packetType).isEqualTo(MpeghUtil.MhasPacketHeader.PACTYP_AUDIOTRUNCATION);
    assertThat(header.packetLength).isEqualTo(2);
    assertThat(header.packetLabel).isEqualTo(1);
  }

  @Test
  public void parseMhasPacketHeader_truncationPacket1() throws Exception {
    byte[] data = Util.getBytesFromHexString(MPEGH_TRUNC_PACKET1);
    ParsableBitArray bitArray = new ParsableBitArray(data);
    MpeghUtil.MhasPacketHeader header = new MpeghUtil.MhasPacketHeader();
    boolean success = MpeghUtil.parseMhasPacketHeader(bitArray, header);
    assertThat(success).isEqualTo(true);
    assertThat(header.packetType).isEqualTo(MpeghUtil.MhasPacketHeader.PACTYP_AUDIOTRUNCATION);
    assertThat(header.packetLength).isEqualTo(2);
    assertThat(header.packetLabel).isEqualTo(2);
  }

  @Test
  public void parseMhasPacketHeader_3daFramePacket0() throws Exception {
    byte[] data = Util.getBytesFromHexString(MPEGH_3DAFRAME_PACKET0);
    ParsableBitArray bitArray = new ParsableBitArray(data);
    MpeghUtil.MhasPacketHeader header = new MpeghUtil.MhasPacketHeader();
    boolean success = MpeghUtil.parseMhasPacketHeader(bitArray, header);
    assertThat(success).isEqualTo(true);
    assertThat(header.packetType).isEqualTo(MpeghUtil.MhasPacketHeader.PACTYP_MPEGH3DAFRAME);
    assertThat(header.packetLength).isEqualTo(103);
    assertThat(header.packetLabel).isEqualTo(1);
  }

  @Test
  public void parseMhasPacketHeader_3daFramePacket1() throws Exception {
    byte[] data = Util.getBytesFromHexString(MPEGH_3DAFRAME_PACKET1);
    ParsableBitArray bitArray = new ParsableBitArray(data);
    MpeghUtil.MhasPacketHeader header = new MpeghUtil.MhasPacketHeader();
    boolean success = MpeghUtil.parseMhasPacketHeader(bitArray, header);
    assertThat(success).isEqualTo(true);
    assertThat(header.packetType).isEqualTo(MpeghUtil.MhasPacketHeader.PACTYP_MPEGH3DAFRAME);
    assertThat(header.packetLength).isEqualTo(306);
    assertThat(header.packetLabel).isEqualTo(2);
  }

  @Test
  public void parseMpegh3daConfig_3daConfigPacket() throws Exception {
    byte[] data = Util.getBytesFromHexString(MPEGH_3DACONFIG_PACKET1.substring(4));
    ParsableBitArray bitArray = new ParsableBitArray(data);
    MpeghUtil.Mpegh3daConfig config = MpeghUtil.parseMpegh3daConfig(bitArray);
    assertThat(config.standardFrameLength).isEqualTo(1024);
    assertThat(config.samplingFrequency).isEqualTo(48000);
    assertThat(config.profileLevelIndication).isEqualTo(11);
    byte[] compat = new byte[1];
    compat[0] = 16;
    assertThat(config.compatibleProfileLevelSet).isEqualTo(compat);
  }

  @Test
  public void parseAudioTruncationInfo_truncationPacket0() {
    byte[] data = Util.getBytesFromHexString(MPEGH_TRUNC_PACKET0.substring(6));
    ParsableBitArray bitArray = new ParsableBitArray(data);
    int truncSamples = MpeghUtil.parseAudioTruncationInfo(bitArray);
    assertThat(truncSamples).isEqualTo(992);
  }

  @Test
  public void parseAudioTruncationInfo_truncationPacket1() {
    byte[] data = Util.getBytesFromHexString(MPEGH_TRUNC_PACKET1.substring(6));
    ParsableBitArray bitArray = new ParsableBitArray(data);
    int truncSamples = MpeghUtil.parseAudioTruncationInfo(bitArray);
    assertThat(truncSamples).isEqualTo(32);
  }
}
