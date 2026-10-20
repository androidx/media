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
package androidx.media3.extractor.ts;

import static androidx.media3.test.utils.TestUtil.getByteArray;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.Format;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.test.utils.ExtractorAsserts;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.primitives.Bytes;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link AdtsExtractor} that test specific behaviours and don't need to be parameterized.
 *
 * <p>For parameterized tests using {@link ExtractorAsserts} see {@link AdtsExtractorTest}.
 */
@RunWith(AndroidJUnit4.class)
public final class AdtsExtractorNonParameterizedTest {

  /** AAC-LC, mono, 44.1 kHz, 144 frames. */
  private static final String SAMPLE_FILE = "media/ts/sample.adts";

  @Test
  public void read_inputStartsWithFalseHeaderFollowedByRealFrames_skipsFalseHeader()
      throws Exception {
    byte[] data = getByteArray(ApplicationProvider.getApplicationContext(), SAMPLE_FILE);
    // A plausible header (88.2 kHz, 8 channels) whose frame length points exactly at the first real
    // frame, which has different fixed header fields.
    byte[] falseHeader =
        createAdtsHeader(/* sampleRateIndex= */ 1, /* channelConfig= */ 7, /* frameLength= */ 300);
    byte[] input = Bytes.concat(falseHeader, new byte[300 - falseHeader.length], data);

    FakeExtractorOutput output =
        TestUtil.extractAllSamplesFromByteArray(new AdtsExtractor(), input);

    FakeTrackOutput trackOutput = output.trackOutputs.get(0);
    assertFormatIsMono44100Hz(trackOutput.lastFormat);
    assertThat(trackOutput.getSampleCount()).isEqualTo(144);
  }

  @Test
  public void read_afterSeekToStartOfNewInput_skipsFalseHeaderMatchingOutputFormat()
      throws Exception {
    byte[] data = getByteArray(ApplicationProvider.getApplicationContext(), SAMPLE_FILE);
    AdtsExtractor extractor = new AdtsExtractor();
    FakeExtractorOutput output = TestUtil.extractAllSamplesFromByteArray(extractor, data);
    FakeTrackOutput trackOutput = output.trackOutputs.get(0);
    int sampleCountBeforeSeek = trackOutput.getSampleCount();
    // A false header with the same fixed header fields as the output format, so that only the
    // extractor's synchronization can reject it, and a frame length that points beyond the first
    // 2 KiB packet. It's followed by the first 4000 bytes of the file, which contain 22 frames.
    byte[] falseHeader =
        createAdtsHeader(/* sampleRateIndex= */ 4, /* channelConfig= */ 1, /* frameLength= */ 3000);
    byte[] newInput = Bytes.concat(new byte[100], falseHeader, Arrays.copyOf(data, 4000));

    // Simulate reconnecting to a live stream: the same extractor is seeked to the start and reads
    // from a new input that starts part way through a frame.
    extractor.seek(/* position= */ 0, /* timeUs= */ 0);
    readToEnd(extractor, newInput);

    assertThat(trackOutput.getSampleCount()).isEqualTo(sampleCountBeforeSeek + 22);
  }

  @Test
  public void sniff_consecutiveFramesWithConsistentHeaders_returnsTrue() throws Exception {
    byte[] data = createAdtsStream(4, 4, 4, 4);

    assertSniff(data, /* expectedResult= */ true);
  }

  @Test
  public void sniff_consecutiveFramesWithInconsistentHeaders_returnsFalse() throws Exception {
    byte[] data = createAdtsStream(4, 3, 4, 3);

    assertSniff(data, /* expectedResult= */ false);
  }

  @Test
  public void read_leadingId3HeaderFollowedByZeroPadding_outputsId3HeaderAndRealFrames()
      throws Exception {
    byte[] data = getByteArray(ApplicationProvider.getApplicationContext(), SAMPLE_FILE);
    // Inputs that start with ID3 headers aren't synchronized, but left to the reader.
    byte[] input = Bytes.concat(createId3Tag(/* dataLength= */ 10), new byte[10], data);

    FakeExtractorOutput output =
        TestUtil.extractAllSamplesFromByteArray(new AdtsExtractor(), input);

    assertThat(output.trackOutputs.get(1).getSampleCount()).isEqualTo(1);
    FakeTrackOutput trackOutput = output.trackOutputs.get(0);
    assertFormatIsMono44100Hz(trackOutput.lastFormat);
    assertThat(trackOutput.getSampleCount()).isEqualTo(144);
  }

  private static void assertSniff(byte[] data, boolean expectedResult) throws IOException {
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(data)
            .setSimulateIOErrors(true)
            .setSimulateUnknownLength(true)
            .setSimulatePartialReads(true)
            .build();
    ExtractorAsserts.assertSniff(new AdtsExtractor(), input, expectedResult);
  }

  private static void assertFormatIsMono44100Hz(Format format) {
    assertThat(format).isNotNull();
    assertThat(format.sampleRate).isEqualTo(44100);
    assertThat(format.channelCount).isEqualTo(1);
  }

  private static void readToEnd(Extractor extractor, byte[] data) throws IOException {
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(data).build();
    PositionHolder positionHolder = new PositionHolder();
    int readResult = Extractor.RESULT_CONTINUE;
    while (readResult != Extractor.RESULT_END_OF_INPUT) {
      readResult = extractor.read(input, positionHolder);
      if (readResult == Extractor.RESULT_SEEK) {
        input.setPosition((int) positionHolder.position);
      }
    }
  }

  /**
   * Creates an ADTS stream of 20-byte AAC-LC stereo frames whose headers cycle through the given
   * sample rate indices. The stream is longer than the number of bytes that {@link
   * AdtsExtractor#sniff} searches, so that it gives up rather than reaching the end of the input.
   */
  private static byte[] createAdtsStream(int... sampleRateIndices) {
    int frameCount = 420;
    int frameLength = 20;
    byte[] data = new byte[frameCount * frameLength];
    for (int i = 0; i < frameCount; i++) {
      byte[] header =
          createAdtsHeader(
              sampleRateIndices[i % sampleRateIndices.length], /* channelConfig= */ 2, frameLength);
      System.arraycopy(header, 0, data, i * frameLength, header.length);
    }
    return data;
  }

  /** Creates the 7-byte header of an MPEG-4 AAC-LC ADTS frame without a CRC. */
  private static byte[] createAdtsHeader(int sampleRateIndex, int channelConfig, int frameLength) {
    return TestUtil.createByteArray(
        0xFF,
        0xF1, // syncword, ID=0 (MPEG-4), layer=0, protection_absent=1.
        (1 << 6) | (sampleRateIndex << 2) | (channelConfig >> 2), // profile=1 (AAC-LC).
        ((channelConfig & 0x3) << 6) | (frameLength >> 11),
        (frameLength >> 3) & 0xFF,
        ((frameLength & 0x7) << 5) | 0x1F, // adts_buffer_fullness=0x7FF (VBR).
        0xFC); // number_of_raw_data_blocks_in_frame=0.
  }

  /** Creates an ID3v2.4 tag with {@code dataLength} bytes of (zero) data after its header. */
  private static byte[] createId3Tag(int dataLength) {
    byte[] tag = new byte[10 + dataLength];
    tag[0] = 'I';
    tag[1] = 'D';
    tag[2] = '3';
    tag[3] = 4; // Major version.
    tag[6] = (byte) ((dataLength >> 21) & 0x7F);
    tag[7] = (byte) ((dataLength >> 14) & 0x7F);
    tag[8] = (byte) ((dataLength >> 7) & 0x7F);
    tag[9] = (byte) (dataLength & 0x7F);
    return tag;
  }
}
