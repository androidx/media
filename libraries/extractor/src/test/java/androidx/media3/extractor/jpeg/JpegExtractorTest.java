/*
 * Copyright 2020 The Android Open Source Project
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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.Metadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ForwardingExtractorInput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SingleSampleExtractor;
import androidx.media3.extractor.metadata.MotionPhotoMetadata;
import androidx.media3.test.utils.ExtractorAsserts;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.primitives.Bytes;
import com.google.testing.junit.testparameterinjector.TestParameter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** Unit tests for {@link JpegExtractor}. */
@RunWith(RobolectricTestParameterInjector.class)
public final class JpegExtractorTest {

  private static final String EXIF_APP1_HEADER = "Exif\0\0";
  private static final String XMP_APP1_HEADER = "http://ns.adobe.com/xap/1.0/\0";
  private static final String EXTENDED_XMP_APP1_HEADER = "http://ns.adobe.com/xmp/extension/\0";
  private static final int XMP_APP1_HEADER_LENGTH = Util.getUtf8Bytes(XMP_APP1_HEADER).length;

  @Test
  public void sampleNonMotionPhotoShortened_noFlags_extractImage(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        JpegExtractor::new,
        "media/jpeg/non-motion-photo-shortened.jpg",
        /* peekLimit= */ -1,
        simulationConfig);
  }

  @Test
  public void sampleNonMotionPhotoShortened_readImageFlag_extractImage(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        () -> new JpegExtractor(JpegExtractor.FLAG_READ_IMAGE),
        "media/jpeg/non-motion-photo-shortened.jpg",
        /* peekLimit= */ 2,
        simulationConfig);
  }

  @Test
  public void samplePixelMotionPhotoShortened_readImageFlag_extractImage(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        () -> new JpegExtractor(JpegExtractor.FLAG_READ_IMAGE),
        "media/jpeg/pixel-motion-photo-shortened.jpg",
        /* peekLimit= */ 2,
        new ExtractorAsserts.AssertionConfig.Builder()
            .setDumpFilesPrefix("extractordumps/jpeg/pixel-motion-photo-shortened.jpg_image")
            .build(),
        simulationConfig);
  }

  @Test
  public void samplePixelMotionPhotoShortened_extractMotionPhoto(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        JpegExtractor::new,
        "media/jpeg/pixel-motion-photo-shortened.jpg",
        /* peekLimit= */ 2235,
        simulationConfig);
  }

  @Test
  public void samplePixelMotionPhotoJfifSegmentShortened_extractMotionPhoto(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        JpegMotionPhotoExtractor::new,
        "media/jpeg/pixel-motion-photo-jfif-segment-shortened.jpg",
        /* peekLimit= */ 5413,
        simulationConfig);
  }

  @Test
  public void samplePixelMotionPhotoVideoRemovedShortened_extractMotionPhoto(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        JpegMotionPhotoExtractor::new,
        "media/jpeg/pixel-motion-photo-video-removed-shortened.jpg",
        /* peekLimit= */ 2235,
        simulationConfig);
  }

  @Test
  public void samplePixelMotionPhotoWithoutExifShortened_extractMotionPhoto(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        JpegMotionPhotoExtractor::new,
        "media/jpeg/pixel-motion-photo-without-exif-shortened.jpg",
        /* peekLimit= */ 1264,
        simulationConfig);
  }

  @Test
  public void sampleSsMotionPhotoShortened_extractMotionPhoto(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        JpegMotionPhotoExtractor::new,
        "media/jpeg/ss-motion-photo-shortened.jpg",
        /* peekLimit= */ 11924,
        simulationConfig);
  }

  /** Regression test for [internal b/301025983]. */
  @Test
  public void samplePixelMotionPhotoWithTwoHevcTracks_extractMotionPhoto(
      @TestParameter(valuesProvider = ExtractorAsserts.ConfigProvider.class)
          ExtractorAsserts.SimulationConfig simulationConfig)
      throws Exception {
    ExtractorAsserts.assertBehavior(
        JpegMotionPhotoExtractor::new,
        "media/jpeg/pixel-motion-photo-2-hevc-tracks.jpg",
        /* peekLimit= */ 24344,
        simulationConfig);
  }

  @Test
  public void sniff_onMotionPhotoWithDefaultFlags_returnsTrue() throws Exception {
    JpegExtractor extractor = new JpegExtractor();
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                TestUtil.getByteArray(
                    ApplicationProvider.getApplicationContext(),
                    "media/jpeg/pixel-motion-photo-shortened.jpg"))
            .build();

    assertThat(extractor.sniff(input)).isTrue();
  }

  @Test
  public void sniff_onMotionPhotoWithReadImageFlag_returnsTrue() throws Exception {
    JpegExtractor extractor = new JpegExtractor(JpegExtractor.FLAG_READ_IMAGE);
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                TestUtil.getByteArray(
                    ApplicationProvider.getApplicationContext(),
                    "media/jpeg/pixel-motion-photo-shortened.jpg"))
            .build();

    assertThat(extractor.sniff(input)).isTrue();
  }

  @Test
  public void sniff_onNonMotionPhotoWithDefaultFlags_returnsTrue() throws Exception {
    JpegExtractor extractor = new JpegExtractor();
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                TestUtil.getByteArray(
                    ApplicationProvider.getApplicationContext(),
                    "media/jpeg/non-motion-photo-shortened.jpg"))
            .build();

    assertThat(extractor.sniff(input)).isTrue();
  }

  @Test
  public void sniff_onNonMotionPhotoWithReadImageFlag_returnsTrue() throws Exception {
    JpegExtractor extractor = new JpegExtractor(JpegExtractor.FLAG_READ_IMAGE);
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                TestUtil.getByteArray(
                    ApplicationProvider.getApplicationContext(),
                    "media/jpeg/non-motion-photo-shortened.jpg"))
            .build();

    assertThat(extractor.sniff(input)).isTrue();
  }

  @Test
  public void sniff_onNonMotionPhoto_returnsFalseWithoutPeekingNonXmpApp1Payloads()
      throws Exception {
    int largePayloadPaddingLength = 10_000;
    byte[] exifSegment =
        buildApp1Segment(
            Bytes.concat(Util.getUtf8Bytes(EXIF_APP1_HEADER), new byte[largePayloadPaddingLength]));
    byte[] extendedXmpSegment =
        buildApp1Segment(
            Bytes.concat(
                Util.getUtf8Bytes(EXTENDED_XMP_APP1_HEADER), new byte[largePayloadPaddingLength]));
    byte[] jpegBytes =
        insertSegmentsAfterSoi(
            getAssetBytes("media/jpeg/non-motion-photo-shortened.jpg"),
            exifSegment,
            extendedXmpSegment);
    JpegMotionPhotoExtractor extractor = new JpegMotionPhotoExtractor();
    RecordingExtractorInput input =
        new RecordingExtractorInput(new FakeExtractorInput.Builder().setData(jpegBytes).build());

    boolean sniffResult = extractor.sniff(input);

    assertThat(sniffResult).isFalse();
    assertThat(Collections.max(input.peekLengths)).isLessThan(largePayloadPaddingLength);
  }

  @Test
  public void sniff_withShortAndHeaderOnlyApp1Segments_returnsTrue() throws Exception {
    byte[] shortApp1Segment = buildApp1Segment(new byte[10]);
    byte[] headerOnlyApp1Segment = buildApp1Segment(Util.getUtf8Bytes(XMP_APP1_HEADER));
    byte[] combinedBytes =
        insertSegmentsAfterSoi(
            getAssetBytes("media/jpeg/pixel-motion-photo-without-exif-shortened.jpg"),
            shortApp1Segment,
            headerOnlyApp1Segment);
    JpegMotionPhotoExtractor extractor = new JpegMotionPhotoExtractor();
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(combinedBytes).build();

    boolean sniffResult = extractor.sniff(input);

    assertThat(sniffResult).isTrue();
  }

  @Test
  public void sniff_withNonMotionPhotoXmpBeforeMotionPhotoXmp_returnsTrue() throws Exception {
    byte[] nonMotionPhotoXmpSegment =
        buildApp1Segment(
            Util.getUtf8Bytes(XMP_APP1_HEADER + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>"));
    byte[] combinedBytes =
        insertSegmentsAfterSoi(
            getAssetBytes("media/jpeg/pixel-motion-photo-without-exif-shortened.jpg"),
            nonMotionPhotoXmpSegment);
    JpegMotionPhotoExtractor extractor = new JpegMotionPhotoExtractor();
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(combinedBytes).build();

    boolean sniffResult = extractor.sniff(input);

    assertThat(sniffResult).isTrue();
  }

  @Test
  public void sniff_withInvalidSegmentLengthLessThanTwo_returnsFalse() throws Exception {
    JpegMotionPhotoExtractor extractor = new JpegMotionPhotoExtractor();
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(TestUtil.createByteArray(0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x01))
            .build();

    boolean sniffResult = extractor.sniff(input);

    assertThat(sniffResult).isFalse();
  }

  @Test
  public void read_onMotionPhoto_skipsExifAndTrailingApp1Payloads() throws Exception {
    int largePayloadPaddingLength = 10_000;
    byte[] motionPhotoBytes =
        getAssetBytes("media/jpeg/pixel-motion-photo-without-exif-shortened.jpg");
    int motionPhotoXmpSegmentEnd =
        4 + (((motionPhotoBytes[4] & 0xFF) << 8) | (motionPhotoBytes[5] & 0xFF));
    byte[] exifSegment =
        buildApp1Segment(
            Bytes.concat(Util.getUtf8Bytes(EXIF_APP1_HEADER), new byte[largePayloadPaddingLength]));
    byte[] extendedXmpSegment =
        buildApp1Segment(
            Bytes.concat(
                Util.getUtf8Bytes(EXTENDED_XMP_APP1_HEADER), new byte[largePayloadPaddingLength]));
    byte[] trailingXmpSegment =
        buildApp1Segment(
            Bytes.concat(Util.getUtf8Bytes(XMP_APP1_HEADER), new byte[largePayloadPaddingLength]));
    byte[] combinedBytes =
        Bytes.concat(
            Arrays.copyOfRange(motionPhotoBytes, /* from= */ 0, /* to= */ 2),
            exifSegment,
            extendedXmpSegment,
            Arrays.copyOfRange(motionPhotoBytes, /* from= */ 2, motionPhotoXmpSegmentEnd),
            trailingXmpSegment,
            Arrays.copyOfRange(
                motionPhotoBytes, motionPhotoXmpSegmentEnd, motionPhotoBytes.length));
    JpegMotionPhotoExtractor extractor = new JpegMotionPhotoExtractor();
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    RecordingExtractorInput input =
        new RecordingExtractorInput(
            new FakeExtractorInput.Builder().setData(combinedBytes).build());

    readToEnd(extractor, input);

    assertThat(output.tracksEnded).isTrue();
    assertThat(output.numberOfTracks).isEqualTo(1);
    assertThat(output.trackOutputs.get(SingleSampleExtractor.IMAGE_TRACK_ID)).isNotNull();
    assertThat(output.seekMap).isNotNull();
    assertThat(output.seekMap.isSeekable()).isTrue();
    assertThat(Collections.max(input.peekLengths)).isLessThan(largePayloadPaddingLength);
    assertThat(Collections.max(input.readLengths)).isLessThan(largePayloadPaddingLength);
  }

  @Test
  public void read_withNonMotionPhotoXmpBeforeMotionPhotoXmp_extractsMotionPhoto()
      throws Exception {
    byte[] nonMotionPhotoXmpSegment =
        buildApp1Segment(
            Util.getUtf8Bytes(XMP_APP1_HEADER + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>"));
    byte[] combinedBytes =
        insertSegmentsAfterSoi(
            getAssetBytes("media/jpeg/pixel-motion-photo-without-exif-shortened.jpg"),
            nonMotionPhotoXmpSegment);

    FakeExtractorOutput output =
        TestUtil.extractAllSamplesFromByteArray(new JpegMotionPhotoExtractor(), combinedBytes);

    assertThat(output.tracksEnded).isTrue();
    assertThat(output.numberOfTracks).isEqualTo(1);
    assertThat(output.trackOutputs.get(SingleSampleExtractor.IMAGE_TRACK_ID)).isNotNull();
    assertThat(output.seekMap).isNotNull();
    assertThat(output.seekMap.isSeekable()).isTrue();
  }

  @Test
  public void read_withInvalidSegmentLengthLessThanTwo_endsTracksWithUnseekableSeekMap()
      throws Exception {
    byte[] malformedJpegBytes = TestUtil.createByteArray(0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x01);

    FakeExtractorOutput output =
        TestUtil.extractAllSamplesFromByteArray(new JpegMotionPhotoExtractor(), malformedJpegBytes);

    assertThat(output.tracksEnded).isTrue();
    assertThat(output.numberOfTracks).isEqualTo(0);
    assertThat(output.seekMap).isNotNull();
    assertThat(output.seekMap.isSeekable()).isFalse();
  }

  @Test
  public void read_withUnknownInputLength_skipsApp1WithoutPeekingXmpHeader() throws Exception {
    JpegMotionPhotoExtractor extractor = new JpegMotionPhotoExtractor();
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    RecordingExtractorInput input =
        new RecordingExtractorInput(
            new FakeExtractorInput.Builder()
                .setData(getAssetBytes("media/jpeg/pixel-motion-photo-without-exif-shortened.jpg"))
                .setSimulateUnknownLength(true)
                .build());

    readToEnd(extractor, input);

    assertThat(output.tracksEnded).isTrue();
    assertThat(output.numberOfTracks).isEqualTo(0);
    if (!input.peekLengths.isEmpty()) {
      assertThat(Collections.max(input.peekLengths)).isLessThan(XMP_APP1_HEADER_LENGTH);
    }
    assertThat(Collections.max(input.readLengths)).isLessThan(XMP_APP1_HEADER_LENGTH);
  }

  @Test
  public void seek_toZeroAfterReadingMotionPhotoHeader_resetsMotionPhotoState() throws Exception {
    JpegMotionPhotoExtractor extractor = new JpegMotionPhotoExtractor();
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput motionPhotoInput =
        new FakeExtractorInput.Builder()
            .setData(getAssetBytes("media/jpeg/pixel-motion-photo-without-exif-shortened.jpg"))
            .build();
    FakeExtractorInput nonMotionPhotoInput =
        new FakeExtractorInput.Builder()
            .setData(getAssetBytes("media/jpeg/non-motion-photo-shortened.jpg"))
            .build();
    checkState(advanceUntilNotContinue(extractor, motionPhotoInput) == Extractor.RESULT_SEEK);

    extractor.seek(/* position= */ 0, /* timeUs= */ 0);
    readToEnd(extractor, nonMotionPhotoInput);

    assertThat(output.tracksEnded).isTrue();
    assertThat(output.numberOfTracks).isEqualTo(0);
    assertThat(output.seekMap).isNotNull();
    assertThat(output.seekMap.isSeekable()).isFalse();
  }

  @Test
  public void seek_toZeroDuringMotionPhotoVideoReading_resetsMetadataAndMp4Extractor()
      throws Exception {
    JpegMotionPhotoExtractor extractor = new JpegMotionPhotoExtractor();
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput firstMotionPhotoInput =
        new FakeExtractorInput.Builder()
            .setData(getAssetBytes("media/jpeg/pixel-motion-photo-2-hevc-tracks.jpg"))
            .build();
    FakeExtractorInput secondMotionPhotoInput =
        new FakeExtractorInput.Builder()
            .setData(getAssetBytes("media/jpeg/pixel-motion-photo-jfif-segment-shortened.jpg"))
            .build();
    PositionHolder positionHolder = new PositionHolder();
    while (output.trackOutputs.get(0) == null || output.trackOutputs.get(0).getSampleCount() == 0) {
      int readResult = extractor.read(firstMotionPhotoInput, positionHolder);
      checkState(readResult != Extractor.RESULT_END_OF_INPUT);
      if (readResult == Extractor.RESULT_SEEK) {
        firstMotionPhotoInput.setPosition((int) positionHolder.position);
      }
    }
    output.clearTrackOutputs();

    extractor.seek(/* position= */ 0, /* timeUs= */ 0);
    readToEnd(extractor, secondMotionPhotoInput);

    MotionPhotoMetadata secondMetadata =
        extractMotionPhotoMetadata(output.trackOutputs.get(SingleSampleExtractor.IMAGE_TRACK_ID));
    assertThat(secondMetadata.videoStartPosition).isEqualTo(6377);
    FakeTrackOutput videoTrack = output.trackOutputs.get(0);
    assertThat(videoTrack.lastFormat).isNotNull();
    assertThat(videoTrack.lastFormat.sampleMimeType).isEqualTo(MimeTypes.VIDEO_H264);
    assertThat(videoTrack.getSampleCount()).isEqualTo(1);
    assertThat(output.seekMap.getSeekPoints(/* timeUs= */ 0).first.position).isEqualTo(6425);
  }

  private static byte[] getAssetBytes(String assetPath) throws IOException {
    return TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), assetPath);
  }

  private static byte[] insertSegmentsAfterSoi(byte[] jpegBytes, byte[]... segments) {
    return Bytes.concat(
        Arrays.copyOfRange(jpegBytes, /* from= */ 0, /* to= */ 2),
        Bytes.concat(segments),
        Arrays.copyOfRange(jpegBytes, /* from= */ 2, jpegBytes.length));
  }

  private static MotionPhotoMetadata extractMotionPhotoMetadata(FakeTrackOutput imageTrack) {
    assertThat(imageTrack.lastFormat).isNotNull();
    Metadata metadata = imageTrack.lastFormat.metadata;
    assertThat(metadata).isNotNull();
    assertThat(metadata.length()).isEqualTo(1);
    assertThat(metadata.get(0)).isInstanceOf(MotionPhotoMetadata.class);
    return (MotionPhotoMetadata) metadata.get(0);
  }

  private static byte[] buildApp1Segment(byte[] payload) {
    int segmentLengthWithHeader = payload.length + 2;
    byte[] header =
        TestUtil.createByteArray(
            0xFF, 0xE1, (segmentLengthWithHeader >> 8) & 0xFF, segmentLengthWithHeader & 0xFF);
    return Bytes.concat(header, payload);
  }

  private static int advanceUntilNotContinue(Extractor extractor, FakeExtractorInput input)
      throws IOException {
    PositionHolder positionHolder = new PositionHolder();
    int readResult = Extractor.RESULT_CONTINUE;
    while (readResult == Extractor.RESULT_CONTINUE) {
      readResult = extractor.read(input, positionHolder);
    }
    return readResult;
  }

  private static void readToEnd(Extractor extractor, FakeExtractorInput input) throws IOException {
    readToEnd(extractor, new RecordingExtractorInput(input));
  }

  private static void readToEnd(Extractor extractor, RecordingExtractorInput input)
      throws IOException {
    PositionHolder positionHolder = new PositionHolder();
    int readResult = Extractor.RESULT_CONTINUE;
    while (readResult != Extractor.RESULT_END_OF_INPUT) {
      readResult = extractor.read(input, positionHolder);
      if (readResult == Extractor.RESULT_SEEK) {
        input.delegate.setPosition((int) positionHolder.position);
      }
    }
  }

  /**
   * A {@link ForwardingExtractorInput} wrapping a {@link FakeExtractorInput} that records positive
   * byte counts from successful peek and read operations.
   */
  private static final class RecordingExtractorInput extends ForwardingExtractorInput {

    public final List<Integer> peekLengths;
    public final List<Integer> readLengths;

    private final FakeExtractorInput delegate;

    public RecordingExtractorInput(FakeExtractorInput delegate) {
      super(delegate);
      this.delegate = delegate;
      peekLengths = new ArrayList<>();
      readLengths = new ArrayList<>();
    }

    @Override
    public int peek(byte[] target, int offset, int length) throws IOException {
      int bytesPeeked = super.peek(target, offset, length);
      if (bytesPeeked > 0) {
        peekLengths.add(bytesPeeked);
      }
      return bytesPeeked;
    }

    @Override
    public boolean peekFully(byte[] target, int offset, int length, boolean allowEndOfInput)
        throws IOException {
      boolean peeked = super.peekFully(target, offset, length, allowEndOfInput);
      if (peeked && length > 0) {
        peekLengths.add(length);
      }
      return peeked;
    }

    @Override
    public void peekFully(byte[] target, int offset, int length) throws IOException {
      super.peekFully(target, offset, length);
      if (length > 0) {
        peekLengths.add(length);
      }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int bytesRead = super.read(buffer, offset, length);
      if (bytesRead > 0) {
        readLengths.add(bytesRead);
      }
      return bytesRead;
    }

    @Override
    public boolean readFully(byte[] target, int offset, int length, boolean allowEndOfInput)
        throws IOException {
      boolean read = super.readFully(target, offset, length, allowEndOfInput);
      if (read && length > 0) {
        readLengths.add(length);
      }
      return read;
    }

    @Override
    public void readFully(byte[] target, int offset, int length) throws IOException {
      super.readFully(target, offset, length);
      if (length > 0) {
        readLengths.add(length);
      }
    }
  }
}
