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
package androidx.media3.extractor.jpeg;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.primitives.Bytes;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link MjpegExtractor}. */
@RunWith(AndroidJUnit4.class)
public final class MjpegExtractorTest {

  private static final byte[] FIRST_FRAME =
      new byte[] {(byte) 0xFF, (byte) 0xD8, 0x01, (byte) 0xFF, (byte) 0xD9};
  private static final byte[] SECOND_FRAME =
      new byte[] {(byte) 0xFF, (byte) 0xD8, 0x02, (byte) 0xFF, (byte) 0xD9};

  @Test
  public void sniff_multipartJpeg_returnsTrue() throws Exception {
    MjpegExtractor extractor = new MjpegExtractor();

    assertThat(extractor.sniff(new FakeExtractorInput.Builder().setData(createStream()).build()))
        .isTrue();
  }

  @Test
  public void sniff_multipartJpegWithoutContentLength_returnsTrue() throws Exception {
    MjpegExtractor extractor = new MjpegExtractor();

    assertThat(
            extractor.sniff(
                new FakeExtractorInput.Builder()
                    .setData(createStreamWithoutContentLength())
                    .build()))
        .isTrue();
  }

  @Test
  public void sniff_doublePrefixedMultipartBoundary_returnsTrue() throws Exception {
    MjpegExtractor extractor = new MjpegExtractor();
    byte[] stream =
        Bytes.concat(
            partHeader("----totalmjpeg", FIRST_FRAME.length),
            FIRST_FRAME,
            "\r\n".getBytes(StandardCharsets.US_ASCII),
            partHeader("----totalmjpeg", SECOND_FRAME.length),
            SECOND_FRAME);

    assertThat(extractor.sniff(new FakeExtractorInput.Builder().setData(stream).build())).isTrue();
  }

  @Test
  public void sniff_rawMjpeg_returnsTrue() throws Exception {
    MjpegExtractor extractor = new MjpegExtractor();

    assertThat(
            extractor.sniff(
                new FakeExtractorInput.Builder()
                    .setData(Bytes.concat(FIRST_FRAME, SECOND_FRAME))
                    .build()))
        .isTrue();
  }

  @Test
  public void sniff_singleJpeg_returnsFalse() throws Exception {
    MjpegExtractor extractor = new MjpegExtractor();

    assertThat(extractor.sniff(new FakeExtractorInput.Builder().setData(FIRST_FRAME).build()))
        .isFalse();
  }

  @Test
  public void read_multipartJpeg_outputsTimestampedImageSamples() throws Exception {
    FakeClock clock = new FakeClock.Builder().setInitialTimeMs(1_000).build();
    MjpegExtractor extractor = new MjpegExtractor(clock);
    FakeExtractorOutput output = extractToEnd(extractor, createStream(), clock, 200);

    FakeTrackOutput trackOutput = output.trackOutputs.get(0);
    assertThat(trackOutput.getType()).isEqualTo(C.TRACK_TYPE_IMAGE);
    assertThat(trackOutput.lastFormat.containerMimeType).isEqualTo(MimeTypes.MULTIPART_MJPEG);
    assertThat(trackOutput.lastFormat.sampleMimeType).isEqualTo(MimeTypes.IMAGE_JPEG);
    trackOutput.assertSample(
        0, FIRST_FRAME, /* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME, /* cryptoData= */ null);
    trackOutput.assertSample(
        1, SECOND_FRAME, /* timeUs= */ 200_000, C.BUFFER_FLAG_KEY_FRAME, /* cryptoData= */ null);
    assertThat(output.seekMap.isSeekable()).isFalse();
    assertThat(output.seekMap.getDurationUs()).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void read_multipartWithoutContentLength_scansJpegMarkers() throws Exception {
    FakeClock clock = new FakeClock.Builder().setInitialTimeMs(1_000).build();
    MjpegExtractor extractor = new MjpegExtractor(clock);

    FakeExtractorOutput output =
        extractToEnd(extractor, createStreamWithoutContentLength(), clock, 100);

    FakeTrackOutput trackOutput = output.trackOutputs.get(0);
    trackOutput.assertSample(
        0, FIRST_FRAME, /* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME, /* cryptoData= */ null);
    trackOutput.assertSample(
        1, SECOND_FRAME, /* timeUs= */ 100_000, C.BUFFER_FLAG_KEY_FRAME, /* cryptoData= */ null);
  }

  @Test
  public void read_multipartWithXTimestamp_usesSourceTimestamps() throws Exception {
    FakeClock clock = new FakeClock.Builder().setInitialTimeMs(1_000).build();
    MjpegExtractor extractor = new MjpegExtractor(clock);
    byte[] stream =
        Bytes.concat(
            partHeader(FIRST_FRAME.length, "123.100000"),
            FIRST_FRAME,
            "\r\n".getBytes(StandardCharsets.US_ASCII),
            partHeader(SECOND_FRAME.length, "123.350000"),
            SECOND_FRAME,
            "\r\n--myboundary--\r\n".getBytes(StandardCharsets.US_ASCII));

    FakeExtractorOutput output = extractToEnd(extractor, stream, clock, /* advanceTimeMs= */ 0);

    FakeTrackOutput trackOutput = output.trackOutputs.get(0);
    assertThat(trackOutput.getSampleTimesUs()).containsExactly(0L, 250_000L).inOrder();
  }

  @Test
  public void read_rawMjpeg_outputsFramesAtDefaultFrameRate() throws Exception {
    FakeClock clock = new FakeClock.Builder().setInitialTimeMs(1_000).build();
    MjpegExtractor extractor = new MjpegExtractor(clock);

    FakeExtractorOutput output =
        extractToEnd(
            extractor,
            Bytes.concat(FIRST_FRAME, SECOND_FRAME),
            clock,
            /* advanceTimeMs= */ 0);

    FakeTrackOutput trackOutput = output.trackOutputs.get(0);
    assertThat(trackOutput.lastFormat.containerMimeType).isEqualTo(MimeTypes.VIDEO_MJPEG);
    assertThat(trackOutput.lastFormat.frameRate).isEqualTo(25);
    trackOutput.assertSample(
        0, FIRST_FRAME, /* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME, /* cryptoData= */ null);
    trackOutput.assertSample(
        1, SECOND_FRAME, /* timeUs= */ 40_000, C.BUFFER_FLAG_KEY_FRAME, /* cryptoData= */ null);
  }

  private static byte[] createStream() {
    return Bytes.concat(
        partHeader(FIRST_FRAME.length),
        FIRST_FRAME,
        "\r\n".getBytes(StandardCharsets.US_ASCII),
        partHeader(SECOND_FRAME.length),
        SECOND_FRAME,
        "\r\n--myboundary--\r\n".getBytes(StandardCharsets.US_ASCII));
  }

  private static byte[] createStreamWithoutContentLength() {
    return Bytes.concat(
        partHeaderWithoutContentLength(),
        FIRST_FRAME,
        "\r\n".getBytes(StandardCharsets.US_ASCII),
        partHeaderWithoutContentLength(),
        SECOND_FRAME,
        "\r\n--myboundary--\r\n".getBytes(StandardCharsets.US_ASCII));
  }

  private static byte[] partHeader(int contentLength) {
    return partHeader("--myboundary", contentLength);
  }

  private static byte[] partHeader(String boundary, int contentLength) {
    return (boundary
            + "\r\n"
            + "Content-length: "
            + contentLength
            + "\r\n"
            + "Content-type: image/jpeg\r\n"
            + "\r\n")
        .getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] partHeader(int contentLength, String timestamp) {
    return ("--myboundary\r\n"
            + "Content-Length: "
            + contentLength
            + "\r\n"
            + "Content-Type: image/jpeg\r\n"
            + "X-Timestamp: "
            + timestamp
            + "\r\n"
            + "\r\n")
        .getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] partHeaderWithoutContentLength() {
    return ("--myboundary\r\n" + "Content-Type: image/jpeg\r\n" + "\r\n")
        .getBytes(StandardCharsets.US_ASCII);
  }

  private static FakeExtractorOutput extractToEnd(
      MjpegExtractor extractor, byte[] stream, FakeClock clock, long advanceTimeMs)
      throws Exception {
    FakeExtractorInput input =
        new FakeExtractorInput.Builder().setData(stream).setSimulatePartialReads(true).build();
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    PositionHolder positionHolder = new PositionHolder();
    int previousSampleCount = 0;
    int result = Extractor.RESULT_CONTINUE;
    while (result != Extractor.RESULT_END_OF_INPUT) {
      result = extractor.read(input, positionHolder);
      FakeTrackOutput trackOutput = output.trackOutputs.get(0);
      if (trackOutput.getSampleCount() > previousSampleCount) {
        previousSampleCount = trackOutput.getSampleCount();
        clock.advanceTime(advanceTimeMs);
      }
    }
    return output;
  }
}
