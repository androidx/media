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
import androidx.media3.test.utils.ImmutableByteArray;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableMap;
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
  public static final ImmutableByteArray NALU_START_CODE =
      ImmutableByteArray.copyOf(NalUnitUtil.NAL_START_CODE);
  private static final ImmutableByteArray AP_NALU_HEADER = ImmutableByteArray.ofHexString("6001");
  private static final ImmutableByteArray NALU_1_LENGTH = ImmutableByteArray.ofHexString("000c");
  private static final ImmutableByteArray NALU_1_INVALID_LENGTH =
      ImmutableByteArray.ofHexString("00ff");
  private static final ImmutableByteArray NALU_1_HEADER = ImmutableByteArray.ofHexString("4001");
  private static final ImmutableByteArray NALU_1_PAYLOAD =
      ImmutableByteArray.ofHexString("0102030405060708090a");
  private static final ImmutableByteArray NALU_1_START_DELIMITED =
      ImmutableByteArray.concat(NALU_START_CODE, NALU_1_HEADER, NALU_1_PAYLOAD);
  private static final ImmutableByteArray NALU_2_LENGTH = ImmutableByteArray.ofHexString("000e");
  private static final ImmutableByteArray NALU_2_HEADER = ImmutableByteArray.ofHexString("4201");
  private static final ImmutableByteArray NALU_2_PAYLOAD =
      ImmutableByteArray.ofHexString("1112131415161718191a1b1c");
  private static final ImmutableByteArray NALU_2_START_DELIMITED =
      ImmutableByteArray.concat(NALU_START_CODE, NALU_2_HEADER, NALU_2_PAYLOAD);
  private static final ImmutableByteArray NALU_1_AND_2_START_DELIMITED =
      ImmutableByteArray.concat(NALU_1_START_DELIMITED, NALU_2_START_DELIMITED);
  private static final int FU_PACKET_SEQUENCE_NUMBER = 12342;
  private static final long FU_PACKET_RTP_TIMESTAMP = 9_000_000;
  private static final ImmutableByteArray FU_NALU_HEADER = ImmutableByteArray.ofHexString("6201");
  private static final ImmutableByteArray FU_1_PACKET_1_FU_HEADER =
      ImmutableByteArray.ofHexString("a0");
  private static final ImmutableByteArray FU_1_PACKET_2_FU_HEADER =
      ImmutableByteArray.ofHexString("20");
  private static final ImmutableByteArray FU_1_PACKET_3_FU_HEADER =
      ImmutableByteArray.ofHexString("60");
  private static final ImmutableByteArray FU_1_PACKET_1_NALU_PAYLOAD =
      ImmutableByteArray.ofHexString("010203");
  private static final ImmutableByteArray FU_1_PACKET_2_NALU_PAYLOAD =
      ImmutableByteArray.ofHexString("040506");
  private static final ImmutableByteArray FU_1_PACKET_3_NALU_PAYLOAD =
      ImmutableByteArray.ofHexString("0708090a");

  private static final RtpPacket SINGLE_NALU_PACKET_1 =
      new RtpPacket.Builder()
          .setTimestamp(SINGLE_NALU_PACKET_1_RTP_TIMESTAMP)
          .setSequenceNumber(PACKET_SEQUENCE_NUMBER + 1)
          .setMarker(true)
          .setPayloadData(ImmutableByteArray.concatToArray(NALU_1_HEADER, NALU_1_PAYLOAD))
          .build();

  private static final RtpPacket SINGLE_NALU_PACKET_2 =
      new RtpPacket.Builder()
          .setTimestamp(SINGLE_NALU_PACKET_2_RTP_TIMESTAMP)
          .setSequenceNumber(PACKET_SEQUENCE_NUMBER + 2)
          .setMarker(true)
          .setPayloadData(ImmutableByteArray.concatToArray(NALU_2_HEADER, NALU_2_PAYLOAD))
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
          ImmutableByteArray.ofUnsigned(0x0a));

  private static final RtpPacket INVALID_AP_PACKET_MISSING_BYTE =
      createAggregationPacket(
          PACKET_SEQUENCE_NUMBER,
          AP_PACKET_RTP_TIMESTAMP,
          NALU_1_LENGTH,
          NALU_1_HEADER,
          NALU_1_PAYLOAD,
          NALU_2_LENGTH,
          NALU_2_HEADER,
          NALU_2_PAYLOAD.subArray(0, NALU_2_PAYLOAD.length() - 1));

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
  public void consume_unsupportedPayloadType_throwsParserException() {
    // Payload type 63 (0x3F) is reserved/unsupported in RFC7798.
    // The type is in bits 1-6 of the first byte: (0x3F << 1) = 0x7E.
    RtpPacket packet =
        new RtpPacket.Builder()
            .setTimestamp(9000)
            .setSequenceNumber(PACKET_SEQUENCE_NUMBER)
            .setPayloadData(new byte[] {(byte) 0x7E, 0x01})
            .build();
    rtpH265Reader.createTracks(extractorOutput, /* trackId= */ 0);

    assertThrows(ParserException.class, () -> consume(rtpH265Reader, packet));
  }

  @Test
  public void consume_validPackets() throws ParserException {
    rtpH265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    rtpH265Reader.onReceivingFirstPacket(VALID_AP_PACKET.timestamp, VALID_AP_PACKET.sequenceNumber);
    consume(rtpH265Reader, VALID_AP_PACKET);
    consume(rtpH265Reader, VALID_AP_PACKET_2);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(2);
    assertThat(trackOutput.getSampleData(0)).isEqualTo(NALU_1_AND_2_START_DELIMITED.toArray());
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(0);
    assertThat(trackOutput.getSampleData(1)).isEqualTo(NALU_1_AND_2_START_DELIMITED.toArray());
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
    assertThat(trackOutput.getSampleData(0)).isEqualTo(NALU_1_AND_2_START_DELIMITED.toArray());
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(0);
    assertThat(trackOutput.getSampleData(1)).isEqualTo(NALU_1_START_DELIMITED.toArray());
    assertThat(trackOutput.getSampleTimeUs(1)).isEqualTo(naluPacket1PresentationTimestampUs);
    assertThat(trackOutput.getSampleData(2)).isEqualTo(NALU_2_START_DELIMITED.toArray());
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
    assertThat(trackOutput.getSampleData(0)).isEqualTo(NALU_1_START_DELIMITED.toArray());
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(0);
    assertThat(trackOutput.getSampleData(1)).isEqualTo(NALU_1_START_DELIMITED.toArray());
    assertThat(trackOutput.getSampleTimeUs(1)).isEqualTo(naluPacket1PresentationTimestampUs);
    assertThat(trackOutput.getSampleData(2)).isEqualTo(NALU_2_START_DELIMITED.toArray());
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
    assertThat(trackOutput.getSampleData(0)).isEqualTo(NALU_1_START_DELIMITED.toArray());
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(naluPacket1PresentationTimestampUs);
    assertThat(trackOutput.getSampleData(1)).isEqualTo(NALU_2_START_DELIMITED.toArray());
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
    assertThat(trackOutput.getSampleData(0)).isEqualTo(NALU_1_START_DELIMITED.toArray());
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(naluPacket1PresentationTimestampUs);
    assertThat(trackOutput.getSampleData(1)).isEqualTo(NALU_2_START_DELIMITED.toArray());
    assertThat(trackOutput.getSampleTimeUs(1)).isEqualTo(naluPacket2PresentationTimestampUs);
  }

  @Test
  public void consume_multipleSingleNalUnitsInSingleAccessUnit_reportsCorrectCombinedSize()
      throws ParserException {
    // Both packets share the same timestamp, forming a single Access Unit.
    long accessUnitTimestamp = 9_000_000;
    // Packet 1: Single NAL unit, marker = false (more NAL units follow in this Access Unit).
    RtpPacket packet1 =
        new RtpPacket.Builder()
            .setTimestamp(accessUnitTimestamp)
            .setSequenceNumber(PACKET_SEQUENCE_NUMBER)
            .setMarker(false)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_1_HEADER, NALU_1_PAYLOAD))
            .build();
    // Packet 2: Single NAL unit, marker = true (last packet in the Access Unit).
    RtpPacket packet2 =
        new RtpPacket.Builder()
            .setTimestamp(accessUnitTimestamp)
            .setSequenceNumber(PACKET_SEQUENCE_NUMBER + 1)
            .setMarker(true)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_2_HEADER, NALU_2_PAYLOAD))
            .build();
    rtpH265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    rtpH265Reader.onReceivingFirstPacket(packet1.timestamp, packet1.sequenceNumber);

    consume(rtpH265Reader, packet1);
    consume(rtpH265Reader, packet2);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    int expectedSize = NALU_1_START_DELIMITED.length() + NALU_2_START_DELIMITED.length();
    assertThat(trackOutput.getSampleData(0).length).isEqualTo(expectedSize);
    assertThat(trackOutput.getSampleData(0))
        .isEqualTo(
            ImmutableByteArray.concat(NALU_1_START_DELIMITED, NALU_2_START_DELIMITED).toArray());
  }

  @Test
  public void consume_multipleMixedNalUnitsInSingleAccessUnit_aggregatesCorrectly()
      throws ParserException {
    long accessUnitTimestamp = 9_000_000;
    RtpPacket fu1Packet1 =
        createFragmentedPacket(
            /* sequenceNumber= */ 100,
            accessUnitTimestamp,
            /* marker= */ false,
            FU_1_PACKET_1_FU_HEADER,
            FU_1_PACKET_1_NALU_PAYLOAD);
    RtpPacket fu1Packet2 =
        createFragmentedPacket(
            /* sequenceNumber= */ 101,
            accessUnitTimestamp,
            /* marker= */ false,
            FU_1_PACKET_2_FU_HEADER,
            FU_1_PACKET_2_NALU_PAYLOAD);
    RtpPacket fu1Packet3 =
        createFragmentedPacket(
            /* sequenceNumber= */ 102,
            accessUnitTimestamp,
            /* marker= */ false,
            FU_1_PACKET_3_FU_HEADER,
            FU_1_PACKET_3_NALU_PAYLOAD);
    RtpPacket singleNaluPacket =
        new RtpPacket.Builder()
            .setTimestamp(accessUnitTimestamp)
            .setSequenceNumber(103)
            .setMarker(true) // Only the final packet in the AU has the marker
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_1_HEADER, NALU_1_PAYLOAD))
            .build();
    rtpH265Reader.createTracks(extractorOutput, /* trackId= */ 0);

    consume(rtpH265Reader, fu1Packet1);
    consume(rtpH265Reader, fu1Packet2);
    consume(rtpH265Reader, fu1Packet3);
    consume(rtpH265Reader, singleNaluPacket);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    int expectedSize = NALU_1_START_DELIMITED.length() + NALU_1_START_DELIMITED.length();
    assertThat(trackOutput.getSampleData(0).length).isEqualTo(expectedSize);
  }

  @Test
  public void
      consume_corruptedFragmentationUnitFollowedByValidSingleNALUInSameAccessUnit_discardsWholeSample()
          throws ParserException {
    long accessUnitTimestamp = 9_000_000;
    // Packet 1: FU-A Start (TS 100).
    RtpPacket fuStart =
        createFragmentedPacket(
            PACKET_SEQUENCE_NUMBER,
            accessUnitTimestamp,
            /* marker= */ false,
            FU_1_PACKET_1_FU_HEADER,
            FU_1_PACKET_1_NALU_PAYLOAD);
    // Packet 2: MISSING (Simulated loss)
    // Packet 3: Valid Single NALU (TS 100, Marker True).
    RtpPacket singleNalu =
        new RtpPacket.Builder()
            .setTimestamp(accessUnitTimestamp)
            .setSequenceNumber(PACKET_SEQUENCE_NUMBER + 2) // Gap of 1
            .setMarker(true)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_1_HEADER, NALU_1_PAYLOAD))
            .build();
    rtpH265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    rtpH265Reader.onReceivingFirstPacket(fuStart.timestamp, fuStart.sequenceNumber);

    consume(rtpH265Reader, fuStart);
    consume(rtpH265Reader, singleNalu);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(0);
  }

  @Test
  public void consume_fragmentationUnitContinuationAtAccessUnitStart_discardsWholeSample()
      throws ParserException {
    long auTimestamp = 9_000_000;
    // Packet 1: Fragmentation Unit Continuation (NOT a start packet) at the very beginning of the
    // Access Unit.
    RtpPacket fuContinuation =
        createFragmentedPacket(
            PACKET_SEQUENCE_NUMBER,
            auTimestamp,
            /* marker= */ false,
            FU_1_PACKET_2_FU_HEADER, // '0x20' -> S=0, E=0
            FU_1_PACKET_2_NALU_PAYLOAD);

    // Packet 2: Valid Single NALU in the same AU (Marker = True)
    RtpPacket singleNalu =
        new RtpPacket.Builder()
            .setTimestamp(auTimestamp)
            .setSequenceNumber(PACKET_SEQUENCE_NUMBER + 1)
            .setMarker(true)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_1_HEADER, NALU_1_PAYLOAD))
            .build();

    rtpH265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    consume(rtpH265Reader, fuContinuation);
    consume(rtpH265Reader, singleNalu);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(0);
  }

  @Test
  public void consume_timestampRollover_resetsCorrectly() throws ParserException {
    long maxTimestamp = 0xFFFFFFFFL;
    long nextTimestamp = 0x00000000L;
    RtpPacket p1 =
        createFragmentedPacket(
            /* sequenceNumber= */ 100,
            maxTimestamp,
            /* marker= */ true,
            FU_1_PACKET_1_FU_HEADER,
            FU_1_PACKET_1_NALU_PAYLOAD);
    RtpPacket p2 =
        createFragmentedPacket(
            /* sequenceNumber= */ 101,
            nextTimestamp,
            /* marker= */ true,
            FU_1_PACKET_1_FU_HEADER,
            FU_1_PACKET_1_NALU_PAYLOAD);

    rtpH265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    consume(rtpH265Reader, p1);
    consume(rtpH265Reader, p2);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(2);
    assertThat(trackOutput.getSampleTimeUs(1)).isGreaterThan(trackOutput.getSampleTimeUs(0));
  }

  @Test
  public void seek_resetsStateCorrectly() throws ParserException {
    long seekTimeUs = 1_000_000;
    RtpPacket fuContinuation =
        createFragmentedPacket(
            PACKET_SEQUENCE_NUMBER,
            9000,
            /* marker= */ false,
            FU_1_PACKET_2_FU_HEADER, // S=0, E=0
            FU_1_PACKET_2_NALU_PAYLOAD);
    RtpPacket singleNalu =
        new RtpPacket.Builder()
            .setTimestamp(18000)
            .setSequenceNumber(PACKET_SEQUENCE_NUMBER + 1)
            .setMarker(true)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_1_HEADER, NALU_1_PAYLOAD))
            .build();
    rtpH265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    consume(rtpH265Reader, fuContinuation);

    rtpH265Reader.seek(/* nextRtpTimestamp= */ 18000, /* timeUs= */ seekTimeUs);
    consume(rtpH265Reader, singleNalu);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleData(0).length).isEqualTo(NALU_1_START_DELIMITED.length());
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(seekTimeUs);
  }

  private static RtpPacket createAggregationPacket(
      int sequenceNumber, long timeStamp, ImmutableByteArray... nalUnits) {
    ImmutableByteArray.Builder payloadData =
        new ImmutableByteArray.Builder().addAll(AP_NALU_HEADER);
    for (ImmutableByteArray nalUnit : nalUnits) {
      payloadData.addAll(nalUnit);
    }
    return new RtpPacket.Builder()
        .setTimestamp(timeStamp)
        .setSequenceNumber(sequenceNumber)
        .setMarker(true)
        .setPayloadData(payloadData.build().toArray())
        .build();
  }

  private static RtpPacket createFragmentedPacket(
      int sequenceNumber, long timeStamp, boolean marker, ImmutableByteArray... nalUnits) {
    ImmutableByteArray.Builder payloadData =
        new ImmutableByteArray.Builder().addAll(FU_NALU_HEADER);
    for (ImmutableByteArray nalUnit : nalUnits) {
      payloadData.addAll(nalUnit);
    }
    return new RtpPacket.Builder()
        .setTimestamp(timeStamp)
        .setSequenceNumber(sequenceNumber)
        .setMarker(marker)
        .setPayloadData(payloadData.build().toArray())
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
