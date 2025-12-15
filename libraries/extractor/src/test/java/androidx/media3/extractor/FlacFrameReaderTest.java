/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static androidx.media3.test.utils.TestUtil.createByteArray;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.Metadata;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.FlacFrameReader.SampleNumberHolder;
import androidx.media3.extractor.FlacMetadataReader.FlacStreamMetadataHolder;
import androidx.media3.extractor.FlacStreamMetadata.SeekTable;
import androidx.media3.extractor.flac.FlacConstants;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.UnsignedBytes;
import java.io.IOException;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for {@link FlacFrameReader}.
 *
 * <p>Some expected results in these tests have been retrieved using the <a
 * href="https://xiph.org/flac/documentation_tools_flac.html">flac</a> command.
 */
@RunWith(AndroidJUnit4.class)
public class FlacFrameReaderTest {

  @BeforeClass
  public static void enableParsableByteArrayLimitEnforcement() {
    ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true);
  }

  @AfterClass
  public static void disableParsableByteArrayLimitEnforcement() {
    ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(false);
  }

  @Test
  public void checkAndReadFrameHeader_validData_updatesPosition() throws Exception {
    FlacStreamMetadataHolder streamMetadataHolder =
        new FlacStreamMetadataHolder(/* flacStreamMetadata= */ null);
    ExtractorInput input =
        buildExtractorInputReadingFromFirstFrame(
            "media/flac/bear_one_metadata_block.flac", streamMetadataHolder);
    int frameStartMarker = FlacMetadataReader.getFrameStartMarker(input);
    ParsableByteArray scratch = new ParsableByteArray(FlacConstants.MAX_FRAME_HEADER_SIZE);
    input.read(scratch.getData(), 0, FlacConstants.MAX_FRAME_HEADER_SIZE);

    FlacFrameReader.checkAndReadFrameHeader(
        scratch,
        streamMetadataHolder.flacStreamMetadata,
        frameStartMarker,
        new SampleNumberHolder());

    assertThat(scratch.getPosition()).isEqualTo(FlacConstants.MIN_FRAME_HEADER_SIZE);
  }

  @Test
  public void checkAndReadFrameHeader_validData_isTrue() throws Exception {
    FlacStreamMetadataHolder streamMetadataHolder =
        new FlacStreamMetadataHolder(/* flacStreamMetadata= */ null);
    ExtractorInput input =
        buildExtractorInputReadingFromFirstFrame(
            "media/flac/bear_one_metadata_block.flac", streamMetadataHolder);
    int frameStartMarker = FlacMetadataReader.getFrameStartMarker(input);
    ParsableByteArray scratch = new ParsableByteArray(FlacConstants.MAX_FRAME_HEADER_SIZE);
    input.read(scratch.getData(), 0, FlacConstants.MAX_FRAME_HEADER_SIZE);

    boolean result =
        FlacFrameReader.checkAndReadFrameHeader(
            scratch,
            streamMetadataHolder.flacStreamMetadata,
            frameStartMarker,
            new SampleNumberHolder());

    assertThat(result).isTrue();
  }

  @Test
  public void checkAndReadFrameHeader_validDataWithNoSubframeHeaderIncluded_returnsTrue()
      throws Exception {
    FlacStreamMetadataHolder streamMetadataHolder =
        new FlacStreamMetadataHolder(/* flacStreamMetadata= */ null);
    ExtractorInput input =
        buildExtractorInputReadingFromFirstFrame(
            "media/flac/bear_one_metadata_block.flac", streamMetadataHolder);
    int frameStartMarker = FlacMetadataReader.getFrameStartMarker(input);
    // The first frame header in this file is only 6 bytes.
    ParsableByteArray scratch = new ParsableByteArray(6);
    input.read(scratch.getData(), 0, 6);

    boolean result =
        FlacFrameReader.checkAndReadFrameHeader(
            scratch,
            streamMetadataHolder.flacStreamMetadata,
            frameStartMarker,
            new SampleNumberHolder());

    assertThat(result).isTrue();
  }

  @Test
  public void checkAndReadFrameHeader_validData_writesSampleNumber() throws Exception {
    FlacStreamMetadataHolder streamMetadataHolder =
        new FlacStreamMetadataHolder(/* flacStreamMetadata= */ null);
    ExtractorInput input =
        buildExtractorInputReadingFromFirstFrame(
            "media/flac/bear_one_metadata_block.flac", streamMetadataHolder);
    int frameStartMarker = FlacMetadataReader.getFrameStartMarker(input);
    // Skip first frame.
    input.skip(5030);
    ParsableByteArray scratch = new ParsableByteArray(FlacConstants.MAX_FRAME_HEADER_SIZE);
    input.read(scratch.getData(), 0, FlacConstants.MAX_FRAME_HEADER_SIZE);
    SampleNumberHolder sampleNumberHolder = new SampleNumberHolder();

    FlacFrameReader.checkAndReadFrameHeader(
        scratch, streamMetadataHolder.flacStreamMetadata, frameStartMarker, sampleNumberHolder);

    assertThat(sampleNumberHolder.sampleNumber).isEqualTo(4096);
  }

  @Test
  public void checkAndReadFrameHeader_invalidData_isFalse() throws Exception {
    FlacStreamMetadataHolder streamMetadataHolder =
        new FlacStreamMetadataHolder(/* flacStreamMetadata= */ null);
    ExtractorInput input =
        buildExtractorInputReadingFromFirstFrame(
            "media/flac/bear_one_metadata_block.flac", streamMetadataHolder);
    ParsableByteArray scratch = new ParsableByteArray(FlacConstants.MAX_FRAME_HEADER_SIZE);
    input.read(scratch.getData(), 0, FlacConstants.MAX_FRAME_HEADER_SIZE);

    // The first bytes of the frame are not equal to the frame start marker.
    boolean result =
        FlacFrameReader.checkAndReadFrameHeader(
            scratch,
            streamMetadataHolder.flacStreamMetadata,
            /* frameStartMarker= */ -1,
            new SampleNumberHolder());

    assertThat(result).isFalse();
  }

  // https://github.com/androidx/media/issues/558
  @Test
  public void checkAndReadFrameHeader_blockSizeSmallerThanMinBlockSize_isFalse() throws Exception {
    FlacStreamMetadataHolder streamMetadataHolder =
        new FlacStreamMetadataHolder(/* flacStreamMetadata= */ null);
    ExtractorInput input =
        buildExtractorInputReadingFromFirstFrame(
            "media/flac/bear_one_metadata_block.flac", streamMetadataHolder);
    int frameStartMarker = FlacMetadataReader.getFrameStartMarker(input);
    ParsableByteArray scratch = new ParsableByteArray(FlacConstants.MAX_FRAME_HEADER_SIZE);
    input.read(scratch.getData(), 0, FlacConstants.MAX_FRAME_HEADER_SIZE);

    // Set the block size key to 1, meaning 192 (should be 12, meaning 4096).
    scratch.getData()[2] = (byte) ((scratch.getData()[2] & (byte) 0x0F) | (byte) 0x10);
    // Update the CRC byte to include the block size edit.
    scratch.getData()[5] =
        UnsignedBytes.checkedCast(
            Util.crc8(scratch.getData(), /* start= */ 0, /* end= */ 5, /* initialValue= */ 0));

    boolean result =
        FlacFrameReader.checkAndReadFrameHeader(
            scratch,
            streamMetadataHolder.flacStreamMetadata,
            frameStartMarker,
            new SampleNumberHolder());

    assertThat(result).isFalse();
  }

  // https://github.com/androidx/media/issues/558
  @Test
  public void checkAndReadFrameHeader_sampleNumberLargerThanTotalSamples_isFalse()
      throws Exception {
    FlacStreamMetadataHolder streamMetadataHolder =
        new FlacStreamMetadataHolder(/* flacStreamMetadata= */ null);
    ExtractorInput input =
        buildExtractorInputReadingFromFirstFrame(
            "media/flac/bear_one_metadata_block.flac", streamMetadataHolder);
    int frameStartMarker = FlacMetadataReader.getFrameStartMarker(input);
    ParsableByteArray scratch = new ParsableByteArray(FlacConstants.MAX_FRAME_HEADER_SIZE);
    input.read(scratch.getData(), 0, FlacConstants.MAX_FRAME_HEADER_SIZE);

    // Set the frame number 33, which is larger than the number of frames expected in this stream
    // based on the FlacStreamMetadata (32). This field is a variable length UTF-8 encoded int, but
    // that means for single-byte values the direct byte values is used.
    scratch.getData()[4] = 33;
    // Update the CRC byte to include the frame number edit.
    scratch.getData()[5] =
        UnsignedBytes.checkedCast(
            Util.crc8(scratch.getData(), /* start= */ 0, /* end= */ 5, /* initialValue= */ 0));

    boolean result =
        FlacFrameReader.checkAndReadFrameHeader(
            scratch,
            streamMetadataHolder.flacStreamMetadata,
            frameStartMarker,
            new SampleNumberHolder());

    assertThat(result).isFalse();
  }

  // https://github.com/androidx/media/issues/558
  @Test
  public void checkAndReadFrameHeader_invalidSubframeHeaderReservedBit_isFalse() throws Exception {
    FlacStreamMetadataHolder streamMetadataHolder =
        new FlacStreamMetadataHolder(/* flacStreamMetadata= */ null);
    ExtractorInput input =
        buildExtractorInputReadingFromFirstFrame(
            "media/flac/bear_one_metadata_block.flac", streamMetadataHolder);
    int frameStartMarker = FlacMetadataReader.getFrameStartMarker(input);
    ParsableByteArray scratch = new ParsableByteArray(FlacConstants.MAX_FRAME_HEADER_SIZE);
    input.read(scratch.getData(), 0, FlacConstants.MAX_FRAME_HEADER_SIZE);

    // Set the reserved bit on the subframe header to make it invalid.
    scratch.getData()[6] |= 0x80;

    boolean result =
        FlacFrameReader.checkAndReadFrameHeader(
            scratch,
            streamMetadataHolder.flacStreamMetadata,
            frameStartMarker,
            new SampleNumberHolder());

    assertThat(result).isFalse();
  }

  // https://github.com/androidx/media/issues/558
  @Test
  public void checkAndReadFrameHeader_reservedSubframeType_isFalse() throws Exception {
    FlacStreamMetadataHolder streamMetadataHolder =
        new FlacStreamMetadataHolder(/* flacStreamMetadata= */ null);
    ExtractorInput input =
        buildExtractorInputReadingFromFirstFrame(
            "media/flac/bear_one_metadata_block.flac", streamMetadataHolder);
    int frameStartMarker = FlacMetadataReader.getFrameStartMarker(input);
    ParsableByteArray scratch = new ParsableByteArray(FlacConstants.MAX_FRAME_HEADER_SIZE);
    input.read(scratch.getData(), 0, FlacConstants.MAX_FRAME_HEADER_SIZE);

    // Modify the subframe header to be invalid (reserved subframe type value)
    scratch.getData()[6] = 4;

    boolean result =
        FlacFrameReader.checkAndReadFrameHeader(
            scratch,
            streamMetadataHolder.flacStreamMetadata,
            frameStartMarker,
            new SampleNumberHolder());

    assertThat(result).isFalse();
  }

  @Test
  public void checkFrameHeaderFromPeek_validData_doesNotUpdatePositions() throws Exception {
    String file = "media/flac/bear_one_metadata_block.flac";
    FlacStreamMetadataHolder streamMetadataHolder =
        new FlacStreamMetadataHolder(/* flacStreamMetadata= */ null);
    ExtractorInput input = buildExtractorInputReadingFromFirstFrame(file, streamMetadataHolder);
    int frameStartMarker = FlacMetadataReader.getFrameStartMarker(input);
    long peekPosition = input.getPosition();
    // Set read position to 0.
    input = buildExtractorInput(file);
    input.advancePeekPosition((int) peekPosition);

    FlacFrameReader.checkFrameHeaderFromPeek(
        input, streamMetadataHolder.flacStreamMetadata, frameStartMarker, new SampleNumberHolder());

    assertThat(input.getPosition()).isEqualTo(0);
    assertThat(input.getPeekPosition()).isEqualTo(peekPosition);
  }

  @Test
  public void checkFrameHeaderFromPeek_validData_isTrue() throws Exception {
    FlacStreamMetadataHolder streamMetadataHolder =
        new FlacStreamMetadataHolder(/* flacStreamMetadata= */ null);
    ExtractorInput input =
        buildExtractorInputReadingFromFirstFrame(
            "media/flac/bear_one_metadata_block.flac", streamMetadataHolder);
    int frameStartMarker = FlacMetadataReader.getFrameStartMarker(input);

    boolean result =
        FlacFrameReader.checkFrameHeaderFromPeek(
            input,
            streamMetadataHolder.flacStreamMetadata,
            frameStartMarker,
            new SampleNumberHolder());

    assertThat(result).isTrue();
  }

  @Test
  public void checkFrameHeaderFromPeek_validData_maxHeaderSize() throws Exception {
    byte[] frameHeader =
        Bytes.concat(
            // The sync code and blocking strategy bit (variable block size)
            createByteArray(0xFF, 0xF9),
            // Indicates 16-bit uncommon block size and sample rate, and mono channel count.
            createByteArray(0b0111_1101, 0x00),
            // The smallest 7 byte coded number: 0x8000_0000
            createByteArray(0xFE, 0x82, 0x80, 0x80, 0x80, 0x80, 0x80),
            // The 16-bit uncommon block size (257 - 1) & sample rate (256kHz), as indicated above.
            createByteArray(0x01, 0x00, 0x01, 0x00),
            // The CRC placeholder (updated below).
            createByteArray(0x00));
    frameHeader[15] =
        UnsignedBytes.checkedCast(
            Util.crc8(frameHeader, /* start= */ 0, /* end= */ 15, /* initialValue= */ 0));

    ExtractorInput input = new FakeExtractorInput.Builder().setData(frameHeader).build();
    FlacStreamMetadata flacStreamMetadata =
        new FlacStreamMetadata(
            /* minBlockSizeSamples= */ 16,
            /* maxBlockSizeSamples= */ 257,
            /* minFrameSize= */ 0, /* unknown */
            /* maxFrameSize= */ 0 /* unknown */,
            /* sampleRate= */ 256,
            /* channels= */ 1,
            /* bitsPerSample= */ 16,
            /* totalSamples= */ 0 /* unknown */,
            (SeekTable) null,
            (Metadata) null);

    int frameStartMarker = FlacMetadataReader.getFrameStartMarker(input);

    boolean result =
        FlacFrameReader.checkFrameHeaderFromPeek(
            input, flacStreamMetadata, frameStartMarker, new SampleNumberHolder());

    assertThat(result).isTrue();
  }

  @Test
  public void checkFrameHeaderFromPeek_validData_writesSampleNumber() throws Exception {
    FlacStreamMetadataHolder streamMetadataHolder =
        new FlacStreamMetadataHolder(/* flacStreamMetadata= */ null);
    ExtractorInput input =
        buildExtractorInputReadingFromFirstFrame(
            "media/flac/bear_one_metadata_block.flac", streamMetadataHolder);
    int frameStartMarker = FlacMetadataReader.getFrameStartMarker(input);
    // Skip first frame.
    input.skip(5030);
    SampleNumberHolder sampleNumberHolder = new SampleNumberHolder();

    FlacFrameReader.checkFrameHeaderFromPeek(
        input, streamMetadataHolder.flacStreamMetadata, frameStartMarker, sampleNumberHolder);

    assertThat(sampleNumberHolder.sampleNumber).isEqualTo(4096);
  }

  @Test
  public void checkFrameHeaderFromPeek_invalidData_isFalse() throws Exception {
    FlacStreamMetadataHolder streamMetadataHolder =
        new FlacStreamMetadataHolder(/* flacStreamMetadata= */ null);
    ExtractorInput input =
        buildExtractorInputReadingFromFirstFrame(
            "media/flac/bear_one_metadata_block.flac", streamMetadataHolder);

    // The first bytes of the frame are not equal to the frame start marker.
    boolean result =
        FlacFrameReader.checkFrameHeaderFromPeek(
            input,
            streamMetadataHolder.flacStreamMetadata,
            /* frameStartMarker= */ -1,
            new SampleNumberHolder());

    assertThat(result).isFalse();
  }

  @Test
  public void checkFrameHeaderFromPeek_invalidData_doesNotUpdatePositions() throws Exception {
    String file = "media/flac/bear_one_metadata_block.flac";
    FlacStreamMetadataHolder streamMetadataHolder =
        new FlacStreamMetadataHolder(/* flacStreamMetadata= */ null);
    ExtractorInput input = buildExtractorInputReadingFromFirstFrame(file, streamMetadataHolder);
    long peekPosition = input.getPosition();
    // Set read position to 0.
    input = buildExtractorInput(file);
    input.advancePeekPosition((int) peekPosition);

    // The first bytes of the frame are not equal to the frame start marker.
    FlacFrameReader.checkFrameHeaderFromPeek(
        input,
        streamMetadataHolder.flacStreamMetadata,
        /* frameStartMarker= */ -1,
        new SampleNumberHolder());

    assertThat(input.getPosition()).isEqualTo(0);
    assertThat(input.getPeekPosition()).isEqualTo(peekPosition);
  }

  @Test
  public void getFirstSampleNumber_doesNotUpdateReadPositionAndAlignsPeekPosition()
      throws Exception {
    FlacStreamMetadataHolder streamMetadataHolder =
        new FlacStreamMetadataHolder(/* flacStreamMetadata= */ null);
    ExtractorInput input =
        buildExtractorInputReadingFromFirstFrame(
            "media/flac/bear_one_metadata_block.flac", streamMetadataHolder);
    long initialReadPosition = input.getPosition();
    // Advance peek position after block size bits.
    input.advancePeekPosition(FlacConstants.MAX_FRAME_HEADER_SIZE);

    FlacFrameReader.getFirstSampleNumber(input, streamMetadataHolder.flacStreamMetadata);

    assertThat(input.getPosition()).isEqualTo(initialReadPosition);
    assertThat(input.getPeekPosition()).isEqualTo(input.getPosition());
  }

  @Test
  public void getFirstSampleNumber_returnsSampleNumber() throws Exception {
    FlacStreamMetadataHolder streamMetadataHolder =
        new FlacStreamMetadataHolder(/* flacStreamMetadata= */ null);
    ExtractorInput input =
        buildExtractorInputReadingFromFirstFrame(
            "media/flac/bear_one_metadata_block.flac", streamMetadataHolder);
    // Skip first frame.
    input.skip(5030);

    long result =
        FlacFrameReader.getFirstSampleNumber(input, streamMetadataHolder.flacStreamMetadata);

    assertThat(result).isEqualTo(4096);
  }

  @Test
  public void readFrameBlockSizeSamplesFromKey_keyIs1_returnsCorrectBlockSize() {
    int result =
        FlacFrameReader.readFrameBlockSizeSamplesFromKey(
            new ParsableByteArray(/* limit= */ 0), /* blockSizeKey= */ 1);

    assertThat(result).isEqualTo(192);
  }

  @Test
  public void readFrameBlockSizeSamplesFromKey_keyBetween2and5_returnsCorrectBlockSize() {
    int result =
        FlacFrameReader.readFrameBlockSizeSamplesFromKey(
            new ParsableByteArray(/* limit= */ 0), /* blockSizeKey= */ 3);

    assertThat(result).isEqualTo(1152);
  }

  @Test
  public void readFrameBlockSizeSamplesFromKey_keyBetween6And7_returnsCorrectBlockSize()
      throws Exception {
    ExtractorInput input = buildExtractorInput("media/flac/bear_one_metadata_block.flac");
    // Skip to block size bits of last frame.
    input.skipFully(164033);
    ParsableByteArray scratch = new ParsableByteArray(2);
    input.readFully(scratch.getData(), 0, 2);

    int result = FlacFrameReader.readFrameBlockSizeSamplesFromKey(scratch, /* blockSizeKey= */ 7);

    assertThat(result).isEqualTo(496);
  }

  @Test
  public void readFrameBlockSizeSamplesFromKey_keyBetween8and15_returnsCorrectBlockSize() {
    int result =
        FlacFrameReader.readFrameBlockSizeSamplesFromKey(
            new ParsableByteArray(/* limit= */ 0), /* blockSizeKey= */ 11);

    assertThat(result).isEqualTo(2048);
  }

  @Test
  public void readFrameBlockSizeSamplesFromKey_invalidKey_returnsCorrectBlockSize() {
    int result =
        FlacFrameReader.readFrameBlockSizeSamplesFromKey(
            new ParsableByteArray(/* limit= */ 0), /* blockSizeKey= */ 25);

    assertThat(result).isEqualTo(-1);
  }

  private static ExtractorInput buildExtractorInput(String file) throws IOException {
    byte[] fileData = TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), file);
    return new FakeExtractorInput.Builder().setData(fileData).build();
  }

  private ExtractorInput buildExtractorInputReadingFromFirstFrame(
      String file, FlacStreamMetadataHolder streamMetadataHolder) throws IOException {
    ExtractorInput input = buildExtractorInput(file);

    input.skipFully(FlacConstants.STREAM_MARKER_SIZE);

    boolean lastMetadataBlock = false;
    while (!lastMetadataBlock) {
      lastMetadataBlock = FlacMetadataReader.readMetadataBlock(input, streamMetadataHolder);
    }

    return input;
  }
}
