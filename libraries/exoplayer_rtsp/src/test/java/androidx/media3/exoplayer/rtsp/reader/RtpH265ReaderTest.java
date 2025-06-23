package androidx.media3.exoplayer.rtsp.reader;

import static androidx.media3.common.util.Util.getBytesFromHexString;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
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
  private static final long AP_PACKET_TIMESTAMP = 9_000_000;
  private static final int AP_PACKET_SEQUENCE_NUMBER = 12345;

  private static final byte[] AP_NALU_HEADER =
      getBytesFromHexString("6001");
  private static final byte[] AP_NALU_1_LENGTH =
      getBytesFromHexString("000c");
  private static final byte[] AP_NALU_1_INVALID_LENGTH =
      getBytesFromHexString("00ff");
  private static final byte[] AP_NALU_1_HEADER =
      getBytesFromHexString("4001");
  private static final byte[] AP_NALU_1_PAYLOAD =
      getBytesFromHexString("0102030405060708090a");
  private static final byte[] AP_NALU_2_LENGTH =
      getBytesFromHexString("000e");
  private static final byte[] AP_NALU_2_HEADER =
      getBytesFromHexString("4201");
  private static final byte[] AP_NALU_2_PAYLOAD =
      getBytesFromHexString("1112131415161718191a1b1c");

  private static final RtpPacket VALID_AP_PACKET = createAggregationPacket(
      AP_NALU_1_LENGTH,
      AP_NALU_1_HEADER,
      AP_NALU_1_PAYLOAD,
      AP_NALU_2_LENGTH,
      AP_NALU_2_HEADER,
      AP_NALU_2_PAYLOAD
  );

  private static final RtpPacket INVALID_AP_PACKET_EXTRA_BYTE = createAggregationPacket(
      AP_NALU_1_LENGTH,
      AP_NALU_1_HEADER,
      AP_NALU_1_PAYLOAD,
      AP_NALU_2_LENGTH,
      AP_NALU_2_HEADER,
      AP_NALU_2_PAYLOAD,
      new byte[]{0x0a}
  );

  private static final RtpPacket INVALID_AP_PACKET_MISSING_BYTE = createAggregationPacket(
      AP_NALU_1_LENGTH,
      AP_NALU_1_HEADER,
      AP_NALU_1_PAYLOAD,
      AP_NALU_2_LENGTH,
      AP_NALU_2_HEADER,
      Arrays.copyOf(AP_NALU_2_PAYLOAD, AP_NALU_2_PAYLOAD.length - 1)
  );

  private static final RtpPacket INVALID_AP_PACKET_INVALID_NALU_LENGTH = createAggregationPacket(
      AP_NALU_1_INVALID_LENGTH,
      AP_NALU_1_HEADER,
      AP_NALU_1_PAYLOAD,
      AP_NALU_2_LENGTH,
      AP_NALU_2_HEADER
  );

  private static final RtpPacket INVALID_AP_PACKET_SINGLE_NALU = createAggregationPacket(
      AP_NALU_1_LENGTH,
      AP_NALU_1_HEADER,
      AP_NALU_1_PAYLOAD
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
          /* fmtpParameters= */ ImmutableMap.of(),
          RtpPayloadFormat.RTP_MEDIA_H265);

  private FakeExtractorOutput extractorOutput;

  @Before
  public void setUp() {
    extractorOutput = new FakeExtractorOutput();
  }

  @Test
  public void consume_validAggregationPacket() throws ParserException {
    RtpH265Reader h265Reader = new RtpH265Reader(H265_FORMAT);
    h265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    consumeFirstPacket(h265Reader, VALID_AP_PACKET);

    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(0);
    assertThat(trackOutput.getSampleCount()).isEqualTo(1);
    assertThat(trackOutput.getSampleData(0)).isEqualTo(
        Bytes.concat(
            NalUnitUtil.NAL_START_CODE,
            AP_NALU_1_HEADER,
            AP_NALU_1_PAYLOAD,
            NalUnitUtil.NAL_START_CODE,
            AP_NALU_2_HEADER,
            AP_NALU_2_PAYLOAD
        )
    );
    assertThat(trackOutput.getSampleTimeUs(0)).isEqualTo(0);
  }

  @Test(expected = ParserException.class)
  public void consume_invalidAggregationPacket_extraByte() throws ParserException {
    RtpH265Reader h265Reader = new RtpH265Reader(H265_FORMAT);
    h265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    consumeFirstPacket(h265Reader, INVALID_AP_PACKET_EXTRA_BYTE);
  }

  @Test(expected = ParserException.class)
  public void consume_invalidAggregationPacket_missingByte() throws ParserException {
    RtpH265Reader h265Reader = new RtpH265Reader(H265_FORMAT);
    h265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    consumeFirstPacket(h265Reader, INVALID_AP_PACKET_MISSING_BYTE);
  }

  @Test(expected = ParserException.class)
  public void consume_invalidAggregationPacket_invalidNALULength() throws ParserException {
    RtpH265Reader h265Reader = new RtpH265Reader(H265_FORMAT);
    h265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    consumeFirstPacket(h265Reader, INVALID_AP_PACKET_INVALID_NALU_LENGTH);
  }

  @Test(expected = ParserException.class)
  public void consume_invalidAggregationPacket_singleNALU() throws ParserException {
    RtpH265Reader h265Reader = new RtpH265Reader(H265_FORMAT);
    h265Reader.createTracks(extractorOutput, /* trackId= */ 0);
    consumeFirstPacket(h265Reader, INVALID_AP_PACKET_SINGLE_NALU);
  }

  private static RtpPacket createAggregationPacket(
      byte[]... nalUnits
  ) {
    return new RtpPacket.Builder()
        .setTimestamp(AP_PACKET_TIMESTAMP)
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
    rtpPacket = copyPacket(rtpPacket);
    h265Reader.consume(
        new ParsableByteArray(rtpPacket.payloadData),
        rtpPacket.timestamp,
        rtpPacket.sequenceNumber,
        rtpPacket.marker
    );
  }

  private static RtpPacket copyPacket(RtpPacket packet) {
    RtpPacket.Builder builder =
        new RtpPacket.Builder()
            .setPadding(packet.padding)
            .setMarker(packet.marker)
            .setPayloadType(packet.payloadType)
            .setSequenceNumber(packet.sequenceNumber)
            .setTimestamp(packet.timestamp)
            .setSsrc(packet.ssrc);

    if (packet.csrc.length > 0) {
      builder.setCsrc(Arrays.copyOf(packet.csrc, packet.csrc.length));
    }
    if (packet.payloadData.length > 0) {
      builder.setPayloadData(Arrays.copyOf(packet.payloadData, packet.payloadData.length));
    }
    return builder.build();
  }
}
