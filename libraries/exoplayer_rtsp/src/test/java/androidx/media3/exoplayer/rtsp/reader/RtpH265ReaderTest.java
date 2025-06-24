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

/**
 * Unit test for {@link RtpH265Reader}.
 */
@RunWith(AndroidJUnit4.class)
public class RtpH265ReaderTest {

  private static final long MEDIA_CLOCK_FREQUENCY = 90_000;
  private static final long AP_PACKET_RTP_TIMESTAMP = 9_000_000;
  private static final int AP_PACKET_SEQUENCE_NUMBER = 12345;
  private static final long SINGLE_NALU_PACKET_1_RTP_TIMESTAMP = 9_000_040;
  private static final long SINGLE_NALU_PACKET_2_RTP_TIMESTAMP = 9_000_080;

  private static final byte[] AP_NALU_HEADER =
      getBytesFromHexString("6001");
  private static final byte[] NALU_1_LENGTH =
      getBytesFromHexString("000c");
  private static final byte[] NALU_1_INVALID_LENGTH =
      getBytesFromHexString("00ff");
  private static final byte[] NALU_1_HEADER =
      getBytesFromHexString("4001");
  private static final byte[] NALU_1_PAYLOAD =
      getBytesFromHexString("0102030405060708090a");
  private static final byte[] NALU_2_LENGTH =
      getBytesFromHexString("000e");
  private static final byte[] NALU_2_HEADER =
      getBytesFromHexString("4201");
  private static final byte[] NALU_2_PAYLOAD =
      getBytesFromHexString("1112131415161718191a1b1c");

  private static final long SINGLE_NALU_PACKET_1_PRESNETATION_TIMESTAMP_US = Util.scaleLargeTimestamp(
      (SINGLE_NALU_PACKET_1_RTP_TIMESTAMP - AP_PACKET_RTP_TIMESTAMP),
      /* multiplier= */ C.MICROS_PER_SECOND,
      /* divisor= */ MEDIA_CLOCK_FREQUENCY
  );

  private static final long SINGLE_NALU_PACKET_2_PRESNETATION_TIMESTAMP_US = Util.scaleLargeTimestamp(
      (SINGLE_NALU_PACKET_2_RTP_TIMESTAMP - AP_PACKET_RTP_TIMESTAMP),
      /* multiplier= */ C.MICROS_PER_SECOND,
      /* divisor= */ MEDIA_CLOCK_FREQUENCY
  );

  private static final RtpPacket SINGLE_NALU_PACKET_1 = new RtpPacket.Builder()
      .setTimestamp(SINGLE_NALU_PACKET_1_RTP_TIMESTAMP)
      .setSequenceNumber(AP_PACKET_SEQUENCE_NUMBER + 1)
      .setMarker(true)
      .setPayloadData(
          Bytes.concat(
              NALU_1_HEADER,
              NALU_1_PAYLOAD
          )
      )
      .build();

  private static final RtpPacket SINGLE_NALU_PACKET_2 = new RtpPacket.Builder()
      .setTimestamp(SINGLE_NALU_PACKET_2_RTP_TIMESTAMP)
      .setSequenceNumber(AP_PACKET_SEQUENCE_NUMBER + 2)
      .setMarker(true)
      .setPayloadData(
          Bytes.concat(
              NALU_2_HEADER,
              NALU_2_PAYLOAD
          )
      )
      .build();

  private static final RtpPacket VALID_AP_PACKET = createAggregationPacket(
      NALU_1_LENGTH,
      NALU_1_HEADER,
      NALU_1_PAYLOAD,
      NALU_2_LENGTH,
      NALU_2_HEADER,
      NALU_2_PAYLOAD
  );

  private static final RtpPacket INVALID_AP_PACKET_EXTRA_BYTE = createAggregationPacket(
      NALU_1_LENGTH,
      NALU_1_HEADER,
      NALU_1_PAYLOAD,
      NALU_2_LENGTH,
      NALU_2_HEADER,
      NALU_2_PAYLOAD,
      new byte[]{0x0a}
  );

  private static final RtpPacket INVALID_AP_PACKET_MISSING_BYTE = createAggregationPacket(
      NALU_1_LENGTH,
      NALU_1_HEADER,
      NALU_1_PAYLOAD,
      NALU_2_LENGTH,
      NALU_2_HEADER,
      Arrays.copyOf(NALU_2_PAYLOAD, NALU_2_PAYLOAD.length - 1)
  );

  private static final RtpPacket INVALID_AP_PACKET_INVALID_NALU_LENGTH = createAggregationPacket(
      NALU_1_INVALID_LENGTH,
      NALU_1_HEADER,
      NALU_1_PAYLOAD,
      NALU_2_LENGTH,
      NALU_2_HEADER
  );

  private static final RtpPacket INVALID_AP_PACKET_SINGLE_NALU = createAggregationPacket(
      NALU_1_LENGTH,
      NALU_1_HEADER,
      NALU_1_PAYLOAD
  );

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
          "sprop-vps", "QAEMAf//AUAAAAMAgAAAAwAAAwC0rAk="
      ),
          RtpPayloadFormat.RTP_MEDIA_H265
      );

  private FakeExtractorOutput extractorOutput;

  @Before
  public void setUp() {
    extractorOutput = new FakeExtractorOutput();
  }

  @Test
  public void consume_validPackets() throws ParserException {
    RtpH265Reader h265Reader = new RtpH265Reader(H265_FORMAT);
    h265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    consumeFirstPacket(h265Reader, VALID_AP_PACKET);
    consume(h265Reader, SINGLE_NALU_PACKET_1);
    consume(h265Reader, SINGLE_NALU_PACKET_2);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(3);
    assertThat(trackOutput.getSampleData(0)).isEqualTo(
        Bytes.concat(
            NalUnitUtil.NAL_START_CODE,
            NALU_1_HEADER,
            NALU_1_PAYLOAD,
            NalUnitUtil.NAL_START_CODE,
            NALU_2_HEADER,
            NALU_2_PAYLOAD
        )
    );
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(0);
    assertThat(trackOutput.getSampleData(1)).isEqualTo(
        Bytes.concat(
            NalUnitUtil.NAL_START_CODE,
            NALU_1_HEADER,
            NALU_1_PAYLOAD
        )
    );
    assertThat(trackOutput.getSampleTimeUs(1))
        .isEqualTo(SINGLE_NALU_PACKET_1_PRESNETATION_TIMESTAMP_US);
    assertThat(trackOutput.getSampleData(2)).isEqualTo(
        Bytes.concat(
            NalUnitUtil.NAL_START_CODE,
            NALU_2_HEADER,
            NALU_2_PAYLOAD
        )
    );
    assertThat(trackOutput.getSampleTimeUs(2))
        .isEqualTo(SINGLE_NALU_PACKET_2_PRESNETATION_TIMESTAMP_US);
  }

  @Test
  public void consume_invalidAggregationPacket_extraByte() {
    RtpH265Reader h265Reader = new RtpH265Reader(H265_FORMAT);
    h265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    assertThrows(ParserException.class,
        () -> consumeFirstPacket(h265Reader, INVALID_AP_PACKET_EXTRA_BYTE));
  }

  @Test
  public void consume_invalidAggregationPacket_missingByte() {
    RtpH265Reader h265Reader = new RtpH265Reader(H265_FORMAT);
    h265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    assertThrows(ParserException.class,
        () -> consumeFirstPacket(h265Reader, INVALID_AP_PACKET_MISSING_BYTE));
  }

  @Test
  public void consume_invalidAggregationPacket_invalidNALULength() {
    RtpH265Reader h265Reader = new RtpH265Reader(H265_FORMAT);
    h265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    assertThrows(ParserException.class,
        () -> consumeFirstPacket(h265Reader, INVALID_AP_PACKET_INVALID_NALU_LENGTH));
  }

  @Test
  public void consume_invalidAggregationPacket_singleNALU() {
    RtpH265Reader h265Reader = new RtpH265Reader(H265_FORMAT);
    h265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    assertThrows(ParserException.class,
        () -> consumeFirstPacket(h265Reader, INVALID_AP_PACKET_SINGLE_NALU));
  }

  private static RtpPacket createAggregationPacket(
      byte[]... nalUnits
  ) {
    return new RtpPacket.Builder()
        .setTimestamp(AP_PACKET_RTP_TIMESTAMP)
        .setSequenceNumber(AP_PACKET_SEQUENCE_NUMBER)
        .setMarker(true)
        .setPayloadData(Bytes.concat(AP_NALU_HEADER, Bytes.concat(nalUnits)))
        .build();
  }

  private static void consumeFirstPacket(
      RtpH265Reader h265Reader,
      RtpPacket rtpPacket
  ) throws ParserException {
    h265Reader.onReceivingFirstPacket(rtpPacket.timestamp, rtpPacket.sequenceNumber);
    consume(h265Reader, rtpPacket);
  }

  private static void consume(RtpH265Reader h265Reader, RtpPacket rtpPacket)
      throws ParserException {
    h265Reader.consume(
        new ParsableByteArray(rtpPacket.payloadData),
        rtpPacket.timestamp,
        rtpPacket.sequenceNumber,
        rtpPacket.marker
    );
  }
}
