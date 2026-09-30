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
package androidx.media3.exoplayer.rtsp.reader;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
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

/** Unit test for {@link RtpH264Reader}. */
@RunWith(AndroidJUnit4.class)
public class RtpH264ReaderTest {

  private static final long MEDIA_CLOCK_FREQUENCY = 90_000;
  private static final int PACKET_SEQUENCE_NUMBER = 12345;
  private static final long STAP_A_PACKET_RTP_TIMESTAMP = 9_000_000;
  private static final ImmutableByteArray NALU_START_CODE =
      ImmutableByteArray.copyOf(NalUnitUtil.NAL_START_CODE);

  // H264 STAP-A Header: F=0, NRI=3, Type=24 (0x78)
  private static final ImmutableByteArray STAP_A_HEADER = ImmutableByteArray.ofHexString("78");
  // NALU 1: Header(1) + Payload(10) = 11 bytes (0x0b)
  private static final ImmutableByteArray NALU_1_LENGTH = ImmutableByteArray.ofHexString("000b");
  private static final ImmutableByteArray NALU_1_HEADER = ImmutableByteArray.ofHexString("41");
  private static final ImmutableByteArray NALU_1_PAYLOAD =
      ImmutableByteArray.ofHexString("0102030405060708090a");
  private static final ImmutableByteArray NALU_1_START_DELIMITED =
      ImmutableByteArray.concat(NALU_START_CODE, NALU_1_HEADER, NALU_1_PAYLOAD);

  // NALU 2: Header(1) + Payload(12) = 13 bytes (0x0d)
  private static final ImmutableByteArray NALU_2_LENGTH = ImmutableByteArray.ofHexString("000d");
  private static final ImmutableByteArray NALU_2_HEADER = ImmutableByteArray.ofHexString("65");
  private static final ImmutableByteArray NALU_2_PAYLOAD =
      ImmutableByteArray.ofHexString("1112131415161718191a1b1c");
  private static final ImmutableByteArray NALU_2_START_DELIMITED =
      ImmutableByteArray.concat(NALU_START_CODE, NALU_2_HEADER, NALU_2_PAYLOAD);

  private static final RtpPacket VALID_STAP_A_PACKET =
      createAggregationPacket(
          PACKET_SEQUENCE_NUMBER,
          STAP_A_PACKET_RTP_TIMESTAMP,
          NALU_1_LENGTH,
          NALU_1_HEADER,
          NALU_1_PAYLOAD,
          NALU_2_LENGTH,
          NALU_2_HEADER,
          NALU_2_PAYLOAD);

  private static final RtpPayloadFormat H264_FORMAT =
      new RtpPayloadFormat(
          new Format.Builder()
              .setSampleMimeType(MimeTypes.VIDEO_H264)
              .setWidth(1920)
              .setHeight(1080)
              .build(),
          /* rtpPayloadType= */ 96,
          /* clockRate= */ (int) MEDIA_CLOCK_FREQUENCY,
          /* fmtpParameters= */ ImmutableMap.of("packetization-mode", "1"),
          RtpPayloadFormat.RTP_MEDIA_H264);

  private FakeExtractorOutput extractorOutput;
  private RtpH264Reader rtpH264Reader;

  @Before
  public void setUp() {
    extractorOutput = new FakeExtractorOutput();
    rtpH264Reader = new RtpH264Reader(H264_FORMAT);
  }

  @Test
  public void consume_unsupportedPacketizationMode_throwsParserException() {
    // Packetization mode 31 (0x1F) is reserved/unsupported in RFC6184.
    RtpPacket packet =
        new RtpPacket.Builder()
            .setTimestamp(9000)
            .setSequenceNumber(PACKET_SEQUENCE_NUMBER)
            .setPayloadData(new byte[] {(byte) 0x1F})
            .build();
    rtpH264Reader.createTracks(extractorOutput, /* trackId= */ 0);

    assertThrows(ParserException.class, () -> consume(rtpH264Reader, packet));
  }

  @Test
  public void consume_validPackets() throws ParserException {
    rtpH264Reader.createTracks(extractorOutput, /* trackId= */ 0);
    rtpH264Reader.onReceivingFirstPacket(
        VALID_STAP_A_PACKET.timestamp, VALID_STAP_A_PACKET.sequenceNumber);

    consume(rtpH264Reader, VALID_STAP_A_PACKET);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleData(0))
        .isEqualTo(
            ImmutableByteArray.concat(NALU_1_START_DELIMITED, NALU_2_START_DELIMITED).toArray());
    assertThat(trackOutput.getSampleData(0).length).isEqualTo(32);
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(C.BUFFER_FLAG_KEY_FRAME);
  }

  @Test
  public void consume_fragmentationUnitContinuationAtAccessUnitStart_discardsWholeSample()
      throws ParserException {
    long auTimestamp = 9_000_000;
    // Packet 1: FU-A Continuation (NOT a start packet, Header 0x05 -> S=0, E=0).
    RtpPacket fuContinuation =
        createFragmentedPacket(
            PACKET_SEQUENCE_NUMBER,
            auTimestamp,
            /* marker= */ false,
            ImmutableByteArray.ofHexString("05"),
            ImmutableByteArray.ofHexString("010203"));
    // Packet 2: Valid Single NALU in the same Access Unit.
    RtpPacket singleNalu =
        new RtpPacket.Builder()
            .setTimestamp(auTimestamp)
            .setSequenceNumber(PACKET_SEQUENCE_NUMBER + 1)
            .setMarker(true)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_1_HEADER, NALU_1_PAYLOAD))
            .build();
    rtpH264Reader.createTracks(extractorOutput, /* trackId= */ 0);

    consume(rtpH264Reader, fuContinuation);
    consume(rtpH264Reader, singleNalu);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(0);
  }

  @Test
  public void consume_multipleSingleNalUnitsInSingleAccessUnit_reportsCorrectCombinedSize()
      throws ParserException {
    long auTimestamp = 9_000_000;
    // Packet 1: Single NAL unit, marker = false.
    RtpPacket packet1 =
        new RtpPacket.Builder()
            .setTimestamp(auTimestamp)
            .setSequenceNumber(PACKET_SEQUENCE_NUMBER)
            .setMarker(false)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_1_HEADER, NALU_1_PAYLOAD))
            .build();
    // Packet 2: Single NAL unit, marker = true.
    RtpPacket packet2 =
        new RtpPacket.Builder()
            .setTimestamp(auTimestamp)
            .setSequenceNumber(PACKET_SEQUENCE_NUMBER + 1)
            .setMarker(true)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_2_HEADER, NALU_2_PAYLOAD))
            .build();
    rtpH264Reader.createTracks(extractorOutput, /* trackId= */ 0);

    consume(rtpH264Reader, packet1);
    consume(rtpH264Reader, packet2);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    int expectedSize = NALU_1_START_DELIMITED.length() + NALU_2_START_DELIMITED.length();
    assertThat(trackOutput.getSampleData(0).length).isEqualTo(expectedSize);
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(C.BUFFER_FLAG_KEY_FRAME);
  }

  @Test
  public void consume_interruptedFragmentationUnitByNewFragmentationUnit_discardsSample()
      throws ParserException {
    long auTimestamp = 9_000_000;
    // Packet 1: FU-A Start for NALU A.
    RtpPacket fuStartA =
        createFragmentedPacket(
            /* sequenceNumber= */ 100,
            auTimestamp,
            /* marker= */ false,
            ImmutableByteArray.ofHexString("85"), // S=1, E=0, NALU type 5
            ImmutableByteArray.ofHexString("0102"));
    // Packet 2: Another FU-A Start for NALU B (Interruption).
    RtpPacket fuStartB =
        createFragmentedPacket(
            /* sequenceNumber= */ 101,
            auTimestamp,
            /* marker= */ true,
            ImmutableByteArray.ofHexString("85"),
            ImmutableByteArray.ofHexString("0304"));
    rtpH264Reader.createTracks(extractorOutput, /* trackId= */ 0);

    consume(rtpH264Reader, fuStartA);
    consume(rtpH264Reader, fuStartB);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    // Should discard the entire AU because it's corrupted.
    assertThat(trackOutput.getSampleCount()).isEqualTo(0);
  }

  @Test
  public void consume_corruptedIdrAccessUnitWithMarker_resetsKeyFrameFlagForNextAccessUnit()
      throws ParserException {
    long corruptedAuTimestamp = 9_000_000;
    long nextAuTimestamp = 9_003_000;
    // Packet 1: Valid IDR Single NALU before the interrupted FU-A.
    RtpPacket idrSingleNalu =
        new RtpPacket.Builder()
            .setTimestamp(corruptedAuTimestamp)
            .setSequenceNumber(99)
            .setMarker(false)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_2_HEADER, NALU_2_PAYLOAD))
            .build();
    // Packet 2: FU-A Start for NALU A.
    RtpPacket fuStartA =
        createFragmentedPacket(
            /* sequenceNumber= */ 100,
            corruptedAuTimestamp,
            /* marker= */ false,
            ImmutableByteArray.ofHexString("85"), // S=1, E=0, NALU type 5
            ImmutableByteArray.ofHexString("0102"));
    // Packet 3: Another FU-A Start for NALU B (Interruption, marker = true).
    RtpPacket fuStartB =
        createFragmentedPacket(
            /* sequenceNumber= */ 101,
            corruptedAuTimestamp,
            /* marker= */ true,
            ImmutableByteArray.ofHexString("85"),
            ImmutableByteArray.ofHexString("0304"));
    // Next Access Unit: Valid non-IDR Single NALU.
    RtpPacket nextAuNonIdr =
        new RtpPacket.Builder()
            .setTimestamp(nextAuTimestamp)
            .setSequenceNumber(102)
            .setMarker(true)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_1_HEADER, NALU_1_PAYLOAD))
            .build();
    rtpH264Reader.createTracks(extractorOutput, /* trackId= */ 0);

    consume(rtpH264Reader, idrSingleNalu);
    consume(rtpH264Reader, fuStartA);
    consume(rtpH264Reader, fuStartB);
    consume(rtpH264Reader, nextAuNonIdr);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleData(0)).isEqualTo(NALU_1_START_DELIMITED.toArray());
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(0);
  }

  @Test
  public void consume_newAccessUnit_resetsStateCorrectly() throws ParserException {
    RtpPacket packet1 =
        new RtpPacket.Builder()
            .setTimestamp(9000)
            .setSequenceNumber(PACKET_SEQUENCE_NUMBER)
            .setMarker(false)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_1_HEADER, NALU_1_PAYLOAD))
            .build();
    RtpPacket packet2 =
        new RtpPacket.Builder()
            .setTimestamp(9000)
            .setSequenceNumber(PACKET_SEQUENCE_NUMBER + 1)
            .setMarker(true)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_2_HEADER, NALU_2_PAYLOAD))
            .build();
    RtpPacket incompleteIdrNalu =
        new RtpPacket.Builder()
            .setTimestamp(18000)
            .setSequenceNumber(PACKET_SEQUENCE_NUMBER + 2)
            .setMarker(false)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_2_HEADER, NALU_2_PAYLOAD))
            .build();
    RtpPacket fuContinuation =
        createFragmentedPacket(
            PACKET_SEQUENCE_NUMBER + 3,
            18000,
            /* marker= */ false,
            ImmutableByteArray.ofHexString("05"),
            ImmutableByteArray.ofHexString("010203"));
    RtpPacket packet3 =
        new RtpPacket.Builder()
            .setTimestamp(27000)
            .setSequenceNumber(PACKET_SEQUENCE_NUMBER + 4)
            .setMarker(true)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_1_HEADER, NALU_1_PAYLOAD))
            .build();
    rtpH264Reader.createTracks(extractorOutput, /* trackId= */ 0);

    // Access Unit 1: Two packets (second is IDR).
    consume(rtpH264Reader, packet1);
    consume(rtpH264Reader, packet2);
    // Access Unit 2: IDR packet followed by FU continuation without FU start (corrupted, no
    // marker).
    consume(rtpH264Reader, incompleteIdrNalu);
    consume(rtpH264Reader, fuContinuation);
    // Access Unit 3: One non-IDR packet with different timestamp.
    consume(rtpH264Reader, packet3);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(2);
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(C.BUFFER_FLAG_KEY_FRAME);
    // Verify sizes and flags are independent (not accumulated across access units).
    assertThat(trackOutput.getSampleData(1).length).isEqualTo(NALU_1_START_DELIMITED.length());
    assertThat(trackOutput.getSampleFlags(1)).isEqualTo(0);
  }

  @Test
  public void seek_resetsStateCorrectly() throws ParserException {
    long seekTimeUs = 1_000_000;
    RtpPacket incompleteIdrNalu =
        new RtpPacket.Builder()
            .setTimestamp(9000)
            .setSequenceNumber(PACKET_SEQUENCE_NUMBER)
            .setMarker(false)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_2_HEADER, NALU_2_PAYLOAD))
            .build();
    RtpPacket fuContinuation =
        createFragmentedPacket(
            PACKET_SEQUENCE_NUMBER + 1,
            9000,
            /* marker= */ false,
            ImmutableByteArray.ofHexString("05"), // S=0, E=0
            ImmutableByteArray.ofHexString("010203"));
    RtpPacket singleNalu =
        new RtpPacket.Builder()
            .setTimestamp(18000)
            .setSequenceNumber(PACKET_SEQUENCE_NUMBER + 2)
            .setMarker(true)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_1_HEADER, NALU_1_PAYLOAD))
            .build();
    rtpH264Reader.createTracks(extractorOutput, /* trackId= */ 0);
    consume(rtpH264Reader, incompleteIdrNalu);
    consume(rtpH264Reader, fuContinuation);

    rtpH264Reader.seek(/* nextRtpTimestamp= */ 18000, /* timeUs= */ seekTimeUs);
    consume(rtpH264Reader, singleNalu);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleData(0).length).isEqualTo(NALU_1_START_DELIMITED.length());
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(seekTimeUs);
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(0);
  }

  @Test
  public void consume_idrFragmentationUnitFollowedByNonIdrStapA_setsKeyFrameFlag()
      throws ParserException {
    long idrAuTimestamp = 9_000_000;
    long nonIdrAuTimestamp = 9_003_000;
    ImmutableByteArray fuPayloadPart1 = ImmutableByteArray.ofHexString("010203");
    ImmutableByteArray fuPayloadPart2 = ImmutableByteArray.ofHexString("040506");
    // FU-A indicator 0x7c (F=0, NRI=3) combined with type 5 (IDR) -> 0x65; with type 1 -> 0x61.
    ImmutableByteArray reconstructedFuIdrNalu =
        ImmutableByteArray.concat(
            NALU_START_CODE, ImmutableByteArray.ofHexString("65"), fuPayloadPart1, fuPayloadPart2);
    ImmutableByteArray reconstructedFuNonIdrNalu =
        ImmutableByteArray.concat(
            NALU_START_CODE, ImmutableByteArray.ofHexString("61"), fuPayloadPart1, fuPayloadPart2);
    // Access Unit 1: Leading non-IDR STAP-A -> IDR in FU-A (type 5) -> trailing non-IDR STAP-A
    // (marker = true).
    RtpPacket stapANonIdrStart =
        createAggregationPacket(
            /* sequenceNumber= */ 100,
            idrAuTimestamp,
            /* marker= */ false,
            NALU_1_LENGTH,
            NALU_1_HEADER,
            NALU_1_PAYLOAD,
            NALU_1_LENGTH,
            NALU_1_HEADER,
            NALU_1_PAYLOAD);
    RtpPacket fuIdrStart =
        createFragmentedPacket(
            /* sequenceNumber= */ 101,
            idrAuTimestamp,
            /* marker= */ false,
            ImmutableByteArray.ofHexString("85"), // S=1, E=0, NALU type 5 (IDR)
            fuPayloadPart1);
    RtpPacket fuIdrEnd =
        createFragmentedPacket(
            /* sequenceNumber= */ 102,
            idrAuTimestamp,
            /* marker= */ false,
            ImmutableByteArray.ofHexString("45"), // S=0, E=1, NALU type 5 (IDR)
            fuPayloadPart2);
    RtpPacket stapANonIdrEnd =
        createAggregationPacket(
            /* sequenceNumber= */ 103,
            idrAuTimestamp,
            /* marker= */ true,
            NALU_1_LENGTH,
            NALU_1_HEADER,
            NALU_1_PAYLOAD,
            NALU_1_LENGTH,
            NALU_1_HEADER,
            NALU_1_PAYLOAD);
    // Access Unit 2: Multi-packet non-IDR Access Unit combining STAP-A, FU-A, and Single NALU to
    // verify bufferFlags resets after marker and stays 0 across all non-IDR packetization modes.
    RtpPacket nextAuStapANonIdr =
        createAggregationPacket(
            /* sequenceNumber= */ 104,
            nonIdrAuTimestamp,
            /* marker= */ false,
            NALU_1_LENGTH,
            NALU_1_HEADER,
            NALU_1_PAYLOAD,
            NALU_1_LENGTH,
            NALU_1_HEADER,
            NALU_1_PAYLOAD);
    RtpPacket nextAuFuNonIdrStart =
        createFragmentedPacket(
            /* sequenceNumber= */ 105,
            nonIdrAuTimestamp,
            /* marker= */ false,
            ImmutableByteArray.ofHexString("81"),
            fuPayloadPart1);
    RtpPacket nextAuFuNonIdrEnd =
        createFragmentedPacket(
            /* sequenceNumber= */ 106,
            nonIdrAuTimestamp,
            /* marker= */ false,
            ImmutableByteArray.ofHexString("41"),
            fuPayloadPart2);
    RtpPacket nextAuNonIdrSingleNalu =
        new RtpPacket.Builder()
            .setTimestamp(nonIdrAuTimestamp)
            .setSequenceNumber(107)
            .setMarker(true)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_1_HEADER, NALU_1_PAYLOAD))
            .build();
    rtpH264Reader.createTracks(extractorOutput, /* trackId= */ 0);

    consume(rtpH264Reader, stapANonIdrStart);
    consume(rtpH264Reader, fuIdrStart);
    consume(rtpH264Reader, fuIdrEnd);
    consume(rtpH264Reader, stapANonIdrEnd);
    consume(rtpH264Reader, nextAuStapANonIdr);
    consume(rtpH264Reader, nextAuFuNonIdrStart);
    consume(rtpH264Reader, nextAuFuNonIdrEnd);
    consume(rtpH264Reader, nextAuNonIdrSingleNalu);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(2);
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(C.BUFFER_FLAG_KEY_FRAME);
    assertThat(trackOutput.getSampleData(0))
        .isEqualTo(
            ImmutableByteArray.concat(
                    NALU_1_START_DELIMITED,
                    NALU_1_START_DELIMITED,
                    reconstructedFuIdrNalu,
                    NALU_1_START_DELIMITED,
                    NALU_1_START_DELIMITED)
                .toArray());
    assertThat(trackOutput.getSampleFlags(1)).isEqualTo(0);
    assertThat(trackOutput.getSampleData(1))
        .isEqualTo(
            ImmutableByteArray.concat(
                    NALU_1_START_DELIMITED,
                    NALU_1_START_DELIMITED,
                    reconstructedFuNonIdrNalu,
                    NALU_1_START_DELIMITED)
                .toArray());
  }

  @Test
  public void consume_stapAWithIdrAndTrailingNonIdrNalus_setsKeyFrameFlag() throws ParserException {
    long auTimestamp = 9_000_000;
    // IDR in middle of 3-NALU STAP-A (NALU_1 non-IDR -> NALU_2 IDR -> NALU_1 non-IDR), followed by
    // a trailing non-IDR Single NALU (marker = true).
    RtpPacket stapANonIdrThenIdrThenNonIdr =
        createAggregationPacket(
            /* sequenceNumber= */ 100,
            auTimestamp,
            /* marker= */ false,
            NALU_1_LENGTH,
            NALU_1_HEADER,
            NALU_1_PAYLOAD,
            NALU_2_LENGTH,
            NALU_2_HEADER,
            NALU_2_PAYLOAD,
            NALU_1_LENGTH,
            NALU_1_HEADER,
            NALU_1_PAYLOAD);
    RtpPacket nonIdrSingleNaluEnd =
        new RtpPacket.Builder()
            .setTimestamp(auTimestamp)
            .setSequenceNumber(101)
            .setMarker(true)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_1_HEADER, NALU_1_PAYLOAD))
            .build();
    rtpH264Reader.createTracks(extractorOutput, /* trackId= */ 0);

    consume(rtpH264Reader, stapANonIdrThenIdrThenNonIdr);
    consume(rtpH264Reader, nonIdrSingleNaluEnd);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(C.BUFFER_FLAG_KEY_FRAME);
    assertThat(trackOutput.getSampleData(0))
        .isEqualTo(
            ImmutableByteArray.concat(
                    NALU_1_START_DELIMITED,
                    NALU_2_START_DELIMITED,
                    NALU_1_START_DELIMITED,
                    NALU_1_START_DELIMITED)
                .toArray());
  }

  @Test
  public void consume_idrSingleNaluFollowedByNonIdrFragmentationUnit_setsKeyFrameFlag()
      throws ParserException {
    long auTimestamp = 9_000_000;
    ImmutableByteArray fuPayloadPart1 = ImmutableByteArray.ofHexString("010203");
    ImmutableByteArray fuPayloadPart2 = ImmutableByteArray.ofHexString("040506");
    ImmutableByteArray reconstructedFuNonIdrNalu =
        ImmutableByteArray.concat(
            NALU_START_CODE, ImmutableByteArray.ofHexString("61"), fuPayloadPart1, fuPayloadPart2);
    // Leading non-IDR Single NALU -> IDR in Single NALU (NALU_2 type 5) -> trailing non-IDR FU-A
    // (marker = true).
    RtpPacket nonIdrSingleNaluStart =
        new RtpPacket.Builder()
            .setTimestamp(auTimestamp)
            .setSequenceNumber(100)
            .setMarker(false)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_1_HEADER, NALU_1_PAYLOAD))
            .build();
    RtpPacket idrSingleNalu =
        new RtpPacket.Builder()
            .setTimestamp(auTimestamp)
            .setSequenceNumber(101)
            .setMarker(false)
            .setPayloadData(ImmutableByteArray.concatToArray(NALU_2_HEADER, NALU_2_PAYLOAD))
            .build();
    RtpPacket fuNonIdrStart =
        createFragmentedPacket(
            /* sequenceNumber= */ 102,
            auTimestamp,
            /* marker= */ false,
            ImmutableByteArray.ofHexString("81"), // S=1, E=0, NALU type 1 (non-IDR)
            fuPayloadPart1);
    RtpPacket fuNonIdrEnd =
        createFragmentedPacket(
            /* sequenceNumber= */ 103,
            auTimestamp,
            /* marker= */ true,
            ImmutableByteArray.ofHexString("41"), // S=0, E=1, NALU type 1 (non-IDR)
            fuPayloadPart2);
    rtpH264Reader.createTracks(extractorOutput, /* trackId= */ 0);

    consume(rtpH264Reader, nonIdrSingleNaluStart);
    consume(rtpH264Reader, idrSingleNalu);
    consume(rtpH264Reader, fuNonIdrStart);
    consume(rtpH264Reader, fuNonIdrEnd);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleFlags(0)).isEqualTo(C.BUFFER_FLAG_KEY_FRAME);
    assertThat(trackOutput.getSampleData(0))
        .isEqualTo(
            ImmutableByteArray.concat(
                    NALU_1_START_DELIMITED, NALU_2_START_DELIMITED, reconstructedFuNonIdrNalu)
                .toArray());
  }

  private static RtpPacket createAggregationPacket(
      int sequenceNumber, long timeStamp, ImmutableByteArray... nalUnits) {
    return createAggregationPacket(sequenceNumber, timeStamp, /* marker= */ true, nalUnits);
  }

  private static RtpPacket createAggregationPacket(
      int sequenceNumber, long timeStamp, boolean marker, ImmutableByteArray... nalUnits) {
    ImmutableByteArray.Builder payloadData = new ImmutableByteArray.Builder().addAll(STAP_A_HEADER);
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

  private static RtpPacket createFragmentedPacket(
      int sequenceNumber,
      long timeStamp,
      boolean marker,
      ImmutableByteArray fuHeader,
      ImmutableByteArray payload) {
    return new RtpPacket.Builder()
        .setTimestamp(timeStamp)
        .setSequenceNumber(sequenceNumber)
        .setMarker(marker)
        .setPayloadData(
            // FU-A Indicator: 0x7c
            ImmutableByteArray.concat(ImmutableByteArray.ofHexString("7c"), fuHeader, payload)
                .toArray())
        .build();
  }

  private static void consume(RtpH264Reader h264Reader, RtpPacket rtpPacket)
      throws ParserException {
    h264Reader.consume(
        new ParsableByteArray(rtpPacket.payloadData),
        rtpPacket.timestamp,
        rtpPacket.sequenceNumber,
        rtpPacket.marker);
  }
}
