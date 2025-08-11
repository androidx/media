/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.exoplayer.rtsp.reader;

import static androidx.media3.common.util.Util.getBytesFromHexString;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.exoplayer.rtsp.RtpPacket;
import androidx.media3.exoplayer.rtsp.RtpPayloadFormat;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Bytes;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link RtpH265Reader}. */
@RunWith(AndroidJUnit4.class)
public class RtpH265ReaderTest {

  private static final long MEDIA_CLOCK_FREQUENCY = 90_000;
  private static final int PACKET_SEQUENCE_NUMBER = 12345;
  private static final long AP_PACKET_RTP_TIMESTAMP = 9_000_000;
  private static final long AP_PACKET_2_RTP_TIMESTAMP = 9_000_040;
  private static final long SINGLE_NALU_PACKET_1_RTP_TIMESTAMP = 9_000_040;
  private static final long SINGLE_NALU_PACKET_2_RTP_TIMESTAMP = 9_000_080;
  private static final byte[] AP_NALU_HEADER = getBytesFromHexString("6001");
  private static final byte[] NALU_1_LENGTH = getBytesFromHexString("000c");
  private static final byte[] NALU_1_INVALID_LENGTH = getBytesFromHexString("00ff");
  private static final byte[] NALU_1_HEADER = getBytesFromHexString("4001");
  private static final byte[] NALU_1_PAYLOAD = getBytesFromHexString("0102030405060708090a");
  private static final byte[] NALU_2_LENGTH = getBytesFromHexString("000e");
  private static final byte[] NALU_2_HEADER = getBytesFromHexString("4201");
  private static final byte[] NALU_2_PAYLOAD = getBytesFromHexString("1112131415161718191a1b1c");
  private static final int FU_PACKET_SEQUENCE_NUMBER = 12342;
  private static final long FU_PACKET_RTP_TIMESTAMP = 9_000_000;
  private static final byte[] FU_NALU_HEADER = getBytesFromHexString("6201");
  private static final byte[] FU_1_PACKET_1_FU_HEADER = getBytesFromHexString("a0");
  private static final byte[] FU_1_PACKET_2_FU_HEADER = getBytesFromHexString("20");
  private static final byte[] FU_1_PACKET_3_FU_HEADER = getBytesFromHexString("60");
  private static final byte[] FU_1_PACKET_1_NALU_PAYLOAD = getBytesFromHexString("010203");
  private static final byte[] FU_1_PACKET_2_NALU_PAYLOAD = getBytesFromHexString("040506");
  private static final byte[] FU_1_PACKET_3_NALU_PAYLOAD = getBytesFromHexString("0708090a");

  private static final RtpPacket SINGLE_NALU_PACKET_1 =
      new RtpPacket.Builder()
          .setTimestamp(SINGLE_NALU_PACKET_1_RTP_TIMESTAMP)
          .setSequenceNumber(PACKET_SEQUENCE_NUMBER + 1)
          .setMarker(true)
          .setPayloadData(Bytes.concat(NALU_1_HEADER, NALU_1_PAYLOAD))
          .build();

  private static final RtpPacket SINGLE_NALU_PACKET_2 =
      new RtpPacket.Builder()
          .setTimestamp(SINGLE_NALU_PACKET_2_RTP_TIMESTAMP)
          .setSequenceNumber(PACKET_SEQUENCE_NUMBER + 2)
          .setMarker(true)
          .setPayloadData(Bytes.concat(NALU_2_HEADER, NALU_2_PAYLOAD))
          .build();

  private static final RtpPacket VALID_AP_PACKET =
      createAggregationPacket(
          PACKET_SEQUENCE_NUMBER,
          AP_PACKET_RTP_TIMESTAMP,
          NALU_1_LENGTH,
          NALU_1_HEADER,
          NALU_1_PAYLOAD,
          NALU_2_LENGTH,
          NALU_2_HEADER,
          NALU_2_PAYLOAD);

  private static final RtpPacket VALID_AP_PACKET_2 =
      createAggregationPacket(
          PACKET_SEQUENCE_NUMBER + 1,
          AP_PACKET_2_RTP_TIMESTAMP,
          NALU_1_LENGTH,
          NALU_1_HEADER,
          NALU_1_PAYLOAD,
          NALU_2_LENGTH,
          NALU_2_HEADER,
          NALU_2_PAYLOAD);

  private static final RtpPacket INVALID_AP_PACKET_EXTRA_BYTE =
      createAggregationPacket(
          PACKET_SEQUENCE_NUMBER,
          AP_PACKET_RTP_TIMESTAMP,
          NALU_1_LENGTH,
          NALU_1_HEADER,
          NALU_1_PAYLOAD,
          NALU_2_LENGTH,
          NALU_2_HEADER,
          NALU_2_PAYLOAD,
          new byte[] {0x0a});

  private static final RtpPacket INVALID_AP_PACKET_MISSING_BYTE =
      createAggregationPacket(
          PACKET_SEQUENCE_NUMBER,
          AP_PACKET_RTP_TIMESTAMP,
          NALU_1_LENGTH,
          NALU_1_HEADER,
          NALU_1_PAYLOAD,
          NALU_2_LENGTH,
          NALU_2_HEADER,
          Arrays.copyOf(NALU_2_PAYLOAD, NALU_2_PAYLOAD.length - 1));

  private static final RtpPacket INVALID_AP_PACKET_INVALID_NALU_LENGTH =
      createAggregationPacket(
          PACKET_SEQUENCE_NUMBER,
          AP_PACKET_RTP_TIMESTAMP,
          NALU_1_INVALID_LENGTH,
          NALU_1_HEADER,
          NALU_1_PAYLOAD,
          NALU_2_LENGTH,
          NALU_2_HEADER);

  private static final RtpPacket INVALID_AP_PACKET_SINGLE_NALU =
      createAggregationPacket(
          PACKET_SEQUENCE_NUMBER,
          AP_PACKET_RTP_TIMESTAMP,
          NALU_1_LENGTH,
          NALU_1_HEADER,
          NALU_1_PAYLOAD);

  private static final RtpPacket VALID_FU_1_PACKET_1 =
      createFragmentedPacket(
          FU_PACKET_SEQUENCE_NUMBER,
          FU_PACKET_RTP_TIMESTAMP,
          /* marker= */ false,
          FU_1_PACKET_1_FU_HEADER,
          FU_1_PACKET_1_NALU_PAYLOAD);

  private static final RtpPacket VALID_FU_1_PACKET_2 =
      createFragmentedPacket(
          FU_PACKET_SEQUENCE_NUMBER + 1,
          FU_PACKET_RTP_TIMESTAMP,
          /* marker= */ false,
          FU_1_PACKET_2_FU_HEADER,
          FU_1_PACKET_2_NALU_PAYLOAD);

  private static final RtpPacket VALID_FU_1_PACKET_3 =
      createFragmentedPacket(
          FU_PACKET_SEQUENCE_NUMBER + 2,
          FU_PACKET_RTP_TIMESTAMP,
          /* marker= */ true,
          FU_1_PACKET_3_FU_HEADER,
          FU_1_PACKET_3_NALU_PAYLOAD);

  private static final RtpPacket INVALID_FU_1_PACKET_2_INVALID_SEQUENCE =
      createFragmentedPacket(
          FU_PACKET_SEQUENCE_NUMBER + 3,
          FU_PACKET_RTP_TIMESTAMP,
          /* marker= */ false,
          FU_1_PACKET_2_FU_HEADER,
          FU_1_PACKET_2_NALU_PAYLOAD);

  private static final RtpPayloadFormat H265_FORMAT =
      new RtpPayloadFormat(
          new Format.Builder()
              .setSampleMimeType(MimeTypes.VIDEO_H265)
              .setWidth(1920)
              .setHeight(1080)
              .build(),
          /* rtpPayloadType= */ 98,
          /* clockRate= */ (int) MEDIA_CLOCK_FREQUENCY,
          /* fmtpParameters= */ ImmutableMap.of(
              "packetization-mode", "1",
              "profile-level-id", "010101",
              "sprop-pps", "RAHA4MisDBRSQA==",
              "sprop-sps", "QgEBAUAAAAMAgAAAAwAAAwC0oAPAgBDlja5JG2a5cQB/FiU=",
              "sprop-vps", "QAEMAf//AUAAAAMAgAAAAwAAAwC0rAk="),
          RtpPayloadFormat.RTP_MEDIA_H265);

  private FakeExtractorOutput extractorOutput;
  private RtpH265Reader rtpH265Reader;

  @Before
  public void setUp() {
    extractorOutput = new FakeExtractorOutput();
    rtpH265Reader = new RtpH265Reader(H265_FORMAT);
  }

  @Test
  public void consume_validPackets() throws ParserException {
    rtpH265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    rtpH265Reader.onReceivingFirstPacket(VALID_AP_PACKET.timestamp, VALID_AP_PACKET.sequenceNumber);
    consume(rtpH265Reader, VALID_AP_PACKET);
    consume(rtpH265Reader, VALID_AP_PACKET_2);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(2);
    assertThat(trackOutput.getSampleData(0))
        .isEqualTo(
            Bytes.concat(
                NalUnitUtil.NAL_START_CODE,
                NALU_1_HEADER,
                NALU_1_PAYLOAD,
                NalUnitUtil.NAL_START_CODE,
                NALU_2_HEADER,
                NALU_2_PAYLOAD));
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(0);
    assertThat(trackOutput.getSampleData(1))
        .isEqualTo(
            Bytes.concat(
                NalUnitUtil.NAL_START_CODE,
                NALU_1_HEADER,
                NALU_1_PAYLOAD,
                NalUnitUtil.NAL_START_CODE,
                NALU_2_HEADER,
                NALU_2_PAYLOAD));
    assertThat(trackOutput.getSampleTimeUs(1))
        .isEqualTo(
            Util.scaleLargeTimestamp(
                (AP_PACKET_2_RTP_TIMESTAMP - AP_PACKET_RTP_TIMESTAMP),
                /* multiplier= */ C.MICROS_PER_SECOND,
                /* divisor= */ MEDIA_CLOCK_FREQUENCY));
  }

  @Test
  public void consume_validPacketsMixedAggregationAndSingleNalu() throws ParserException {
    long naluPacket1PresentationTimestampUs =
        Util.scaleLargeTimestamp(
            (SINGLE_NALU_PACKET_1_RTP_TIMESTAMP - AP_PACKET_RTP_TIMESTAMP),
            /* multiplier= */ C.MICROS_PER_SECOND,
            /* divisor= */ MEDIA_CLOCK_FREQUENCY);
    long naluPacket2PresentationTimestampUs =
        Util.scaleLargeTimestamp(
            (SINGLE_NALU_PACKET_2_RTP_TIMESTAMP - AP_PACKET_RTP_TIMESTAMP),
            /* multiplier= */ C.MICROS_PER_SECOND,
            /* divisor= */ MEDIA_CLOCK_FREQUENCY);
    rtpH265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    rtpH265Reader.onReceivingFirstPacket(VALID_AP_PACKET.timestamp, VALID_AP_PACKET.sequenceNumber);
    consume(rtpH265Reader, VALID_AP_PACKET);
    consume(rtpH265Reader, SINGLE_NALU_PACKET_1);
    consume(rtpH265Reader, SINGLE_NALU_PACKET_2);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(3);
    assertThat(trackOutput.getSampleData(0))
        .isEqualTo(
            Bytes.concat(
                NalUnitUtil.NAL_START_CODE,
                NALU_1_HEADER,
                NALU_1_PAYLOAD,
                NalUnitUtil.NAL_START_CODE,
                NALU_2_HEADER,
                NALU_2_PAYLOAD));
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(0);
    assertThat(trackOutput.getSampleData(1))
        .isEqualTo(Bytes.concat(NalUnitUtil.NAL_START_CODE, NALU_1_HEADER, NALU_1_PAYLOAD));
    assertThat(trackOutput.getSampleTimeUs(1)).isEqualTo(naluPacket1PresentationTimestampUs);
    assertThat(trackOutput.getSampleData(2))
        .isEqualTo(Bytes.concat(NalUnitUtil.NAL_START_CODE, NALU_2_HEADER, NALU_2_PAYLOAD));
    assertThat(trackOutput.getSampleTimeUs(2)).isEqualTo(naluPacket2PresentationTimestampUs);
  }

  @Test
  public void consume_invalidAggregationPacketwithExtraByte_throwsParserException() {
    rtpH265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    rtpH265Reader.onReceivingFirstPacket(
        INVALID_AP_PACKET_EXTRA_BYTE.timestamp, INVALID_AP_PACKET_EXTRA_BYTE.sequenceNumber);
    assertThrows(ParserException.class, () -> consume(rtpH265Reader, INVALID_AP_PACKET_EXTRA_BYTE));
  }

  @Test
  public void consume_invalidAggregationPacketwithMissingByte_throwsParserException() {
    rtpH265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    rtpH265Reader.onReceivingFirstPacket(
        INVALID_AP_PACKET_MISSING_BYTE.timestamp, INVALID_AP_PACKET_MISSING_BYTE.sequenceNumber);
    assertThrows(
        ParserException.class, () -> consume(rtpH265Reader, INVALID_AP_PACKET_MISSING_BYTE));
  }

  @Test
  public void consume_invalidAggregationPacketWithInvalidNaluLength_throwsParserException() {
    rtpH265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    rtpH265Reader.onReceivingFirstPacket(
        INVALID_AP_PACKET_INVALID_NALU_LENGTH.timestamp,
        INVALID_AP_PACKET_INVALID_NALU_LENGTH.sequenceNumber);
    assertThrows(
        ParserException.class, () -> consume(rtpH265Reader, INVALID_AP_PACKET_INVALID_NALU_LENGTH));
  }

  @Test
  public void consume_invalidAggregationPacketWithSingleNalu_throwsParserException() {
    rtpH265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    rtpH265Reader.onReceivingFirstPacket(
        INVALID_AP_PACKET_SINGLE_NALU.timestamp, INVALID_AP_PACKET_SINGLE_NALU.sequenceNumber);
    assertThrows(
        ParserException.class, () -> consume(rtpH265Reader, INVALID_AP_PACKET_SINGLE_NALU));
  }

  @Test
  public void consume_validMixedFragmentationPacketsAndSingleNALU() throws ParserException {
    long naluPacket1PresentationTimestampUs =
        Util.scaleLargeTimestamp(
            (SINGLE_NALU_PACKET_1_RTP_TIMESTAMP - FU_PACKET_RTP_TIMESTAMP),
            /* multiplier= */ C.MICROS_PER_SECOND,
            /* divisor= */ MEDIA_CLOCK_FREQUENCY);
    long naluPacket2PresentationTimestampUs =
        Util.scaleLargeTimestamp(
            (SINGLE_NALU_PACKET_2_RTP_TIMESTAMP - FU_PACKET_RTP_TIMESTAMP),
            /* multiplier= */ C.MICROS_PER_SECOND,
            /* divisor= */ MEDIA_CLOCK_FREQUENCY);
    rtpH265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    rtpH265Reader.onReceivingFirstPacket(
        VALID_FU_1_PACKET_1.timestamp, VALID_FU_1_PACKET_1.sequenceNumber);
    consume(rtpH265Reader, VALID_FU_1_PACKET_1);
    consume(rtpH265Reader, VALID_FU_1_PACKET_2);
    consume(rtpH265Reader, VALID_FU_1_PACKET_3);
    consume(rtpH265Reader, SINGLE_NALU_PACKET_1);
    consume(rtpH265Reader, SINGLE_NALU_PACKET_2);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(3);
    assertThat(trackOutput.getSampleData(0))
        .isEqualTo(Bytes.concat(NalUnitUtil.NAL_START_CODE, NALU_1_HEADER, NALU_1_PAYLOAD));
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(0);
    assertThat(trackOutput.getSampleData(1))
        .isEqualTo(Bytes.concat(NalUnitUtil.NAL_START_CODE, NALU_1_HEADER, NALU_1_PAYLOAD));
    assertThat(trackOutput.getSampleTimeUs(1)).isEqualTo(naluPacket1PresentationTimestampUs);
    assertThat(trackOutput.getSampleData(2))
        .isEqualTo(Bytes.concat(NalUnitUtil.NAL_START_CODE, NALU_2_HEADER, NALU_2_PAYLOAD));
    assertThat(trackOutput.getSampleTimeUs(2)).isEqualTo(naluPacket2PresentationTimestampUs);
  }

  @Test
  public void consume_mixedInvalidFragmentationPacketAndValidSingleNALU() throws ParserException {
    long naluPacket1PresentationTimestampUs =
        Util.scaleLargeTimestamp(
            (SINGLE_NALU_PACKET_1_RTP_TIMESTAMP - FU_PACKET_RTP_TIMESTAMP),
            /* multiplier= */ C.MICROS_PER_SECOND,
            /* divisor= */ MEDIA_CLOCK_FREQUENCY);
    long naluPacket2PresentationTimestampUs =
        Util.scaleLargeTimestamp(
            (SINGLE_NALU_PACKET_2_RTP_TIMESTAMP - FU_PACKET_RTP_TIMESTAMP),
            /* multiplier= */ C.MICROS_PER_SECOND,
            /* divisor= */ MEDIA_CLOCK_FREQUENCY);
    rtpH265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    rtpH265Reader.onReceivingFirstPacket(
        VALID_FU_1_PACKET_1.timestamp, VALID_FU_1_PACKET_1.sequenceNumber);
    consume(rtpH265Reader, VALID_FU_1_PACKET_1);
    consume(rtpH265Reader, INVALID_FU_1_PACKET_2_INVALID_SEQUENCE);
    consume(rtpH265Reader, VALID_FU_1_PACKET_3);
    consume(rtpH265Reader, SINGLE_NALU_PACKET_1);
    consume(rtpH265Reader, SINGLE_NALU_PACKET_2);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleData(0))
        .isEqualTo(Bytes.concat(NalUnitUtil.NAL_START_CODE, NALU_1_HEADER, NALU_1_PAYLOAD));
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(naluPacket1PresentationTimestampUs);
    assertThat(trackOutput.getSampleData(1))
        .isEqualTo(Bytes.concat(NalUnitUtil.NAL_START_CODE, NALU_2_HEADER, NALU_2_PAYLOAD));
    assertThat(trackOutput.getSampleTimeUs(1)).isEqualTo(naluPacket2PresentationTimestampUs);
  }

  @Test
  public void consume_mixedWithMissingEndFragmentationPacketAndValidSingleNALU()
      throws ParserException {
    long naluPacket1PresentationTimestampUs =
        Util.scaleLargeTimestamp(
            (SINGLE_NALU_PACKET_1_RTP_TIMESTAMP - FU_PACKET_RTP_TIMESTAMP),
            /* multiplier= */ C.MICROS_PER_SECOND,
            /* divisor= */ MEDIA_CLOCK_FREQUENCY);
    long naluPacket2PresentationTimestampUs =
        Util.scaleLargeTimestamp(
            (SINGLE_NALU_PACKET_2_RTP_TIMESTAMP - FU_PACKET_RTP_TIMESTAMP),
            /* multiplier= */ C.MICROS_PER_SECOND,
            /* divisor= */ MEDIA_CLOCK_FREQUENCY);
    rtpH265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    rtpH265Reader.onReceivingFirstPacket(
        VALID_FU_1_PACKET_1.timestamp, VALID_FU_1_PACKET_1.sequenceNumber);
    consume(rtpH265Reader, VALID_FU_1_PACKET_1);
    consume(rtpH265Reader, VALID_FU_1_PACKET_2);
    consume(rtpH265Reader, SINGLE_NALU_PACKET_1);
    consume(rtpH265Reader, SINGLE_NALU_PACKET_2);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleData(0))
        .isEqualTo(Bytes.concat(NalUnitUtil.NAL_START_CODE, NALU_1_HEADER, NALU_1_PAYLOAD));
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(naluPacket1PresentationTimestampUs);
    assertThat(trackOutput.getSampleData(1))
        .isEqualTo(Bytes.concat(NalUnitUtil.NAL_START_CODE, NALU_2_HEADER, NALU_2_PAYLOAD));
    assertThat(trackOutput.getSampleTimeUs(1)).isEqualTo(naluPacket2PresentationTimestampUs);
  }

  private static RtpPacket createAggregationPacket(
      int sequenceNumber, long timeStamp, byte[]... nalUnits) {
    return new RtpPacket.Builder()
        .setTimestamp(timeStamp)
        .setSequenceNumber(sequenceNumber)
        .setMarker(true)
        .setPayloadData(Bytes.concat(AP_NALU_HEADER, Bytes.concat(nalUnits)))
        .build();
  }

  private static RtpPacket createFragmentedPacket(
      int sequenceNumber, long timeStamp, boolean marker, byte[]... nalUnits) {
    return new RtpPacket.Builder()
        .setTimestamp(timeStamp)
        .setSequenceNumber(sequenceNumber)
        .setMarker(marker)
        .setPayloadData(Bytes.concat(FU_NALU_HEADER, Bytes.concat(nalUnits)))
        .build();
  }

  private static void consume(RtpH265Reader h265Reader, RtpPacket rtpPacket)
      throws ParserException {
    h265Reader.consume(
        new ParsableByteArray(rtpPacket.payloadData),
        rtpPacket.timestamp,
        rtpPacket.sequenceNumber,
        rtpPacket.marker);
  }
}
