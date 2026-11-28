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
package androidx.media3.extractor.mkv;

import static androidx.media3.extractor.Extractor.RESULT_SEEK;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.content.Context;
import androidx.media3.common.Format;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackAwareSeekMap;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.ForwardingSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Non-parameterized tests for {@link MatroskaExtractor}. */
@RunWith(AndroidJUnit4.class)
public class MatroskaExtractorNonParameterizedTest {

  // The first SeekHead of this file names a second SeekHead, which names a third one, which names
  // the Cues. The second and third SeekHead are the last 42 bytes of the file, 21 bytes each, and
  // end with a Seek entry whose SeekID is 10 bytes and whose SeekPosition is 3 bytes from the end.
  private static final String CHAINED_SEEK_HEADS_FILE =
      "media/mkv/sample_with_chained_seek_heads.mkv";

  private static final int SEGMENT_CONTENT_POSITION = 52;

  @Test
  public void seek_afterSeekMapSent_seekMapRemainsValid() throws Exception {
    String fileName = "media/mkv/sample.mkv";
    byte[] fileBytes = TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), fileName);
    MatroskaExtractor extractor = new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(fileBytes).build();
    PositionHolder positionHolder = new PositionHolder();

    while (output.seekMap == null) {
      int readResult = extractor.read(input, positionHolder);
      if (readResult == RESULT_SEEK) {
        input.setPosition((int) positionHolder.position);
      }
    }
    TrackAwareSeekMap trackAwareSeekMap = (TrackAwareSeekMap) output.seekMap;
    assertThat(trackAwareSeekMap.isSeekable()).isTrue();

    int videoTrackId = 1;
    long timeStampUs = trackAwareSeekMap.getDurationUs() / 2;
    SeekPoint expectedGenericSeekPoint = trackAwareSeekMap.getSeekPoints(timeStampUs).first;
    SeekPoint expectedTrackSpecificSeekPoint =
        trackAwareSeekMap.getSeekPoints(timeStampUs, videoTrackId).first;

    extractor.seek(expectedGenericSeekPoint.position, expectedGenericSeekPoint.timeUs);

    assertThat(trackAwareSeekMap.isSeekable()).isTrue();
    assertThat(trackAwareSeekMap.getSeekPoints(timeStampUs).first)
        .isEqualTo(expectedGenericSeekPoint);
    assertThat(trackAwareSeekMap.getSeekPoints(timeStampUs, videoTrackId).first)
        .isEqualTo(expectedTrackSpecificSeekPoint);
  }

  // https://github.com/androidx/media/issues/3562
  @Test
  public void read_withStray0x80InInfo_skipsElement() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    MatroskaExtractor extractor = new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED);

    FakeExtractorOutput output =
        TestUtil.extractAllSamplesFromFile(
            extractor, context, "media/mkv/sample_with_stray_0x80_in_info.mkv");

    DumpFileAsserts.assertOutput(
        context, output, "extractordumps/mkv/sample_with_chapters.mkv.0.dump");
  }

  @Test
  public void seek_resetsSubtitleParser() throws Exception {
    List<SubtitleParser> createdParsers = new ArrayList<>();
    SubtitleParser.Factory subtitleParserFactory =
        new ForwardingSubtitleParserFactory(new DefaultSubtitleParserFactory()) {
          @Override
          public SubtitleParser create(Format format) {
            SubtitleParser parser = mock(SubtitleParser.class, delegatesTo(super.create(format)));
            createdParsers.add(parser);
            return parser;
          }
        };
    MatroskaExtractor extractor = new MatroskaExtractor(subtitleParserFactory);
    TestUtil.extractAllSamplesFromFile(
        extractor, ApplicationProvider.getApplicationContext(), "media/mkv/sample_with_srt.mkv");
    SubtitleParser subtitleParser = Iterables.getOnlyElement(createdParsers);

    extractor.seek(/* position= */ 0, /* timeUs= */ 0);

    verify(subtitleParser).reset();
  }

  @Test
  public void read_withChainedSeekHeads_outputsSeekableSeekMapEqualToSeekMapOfDirectCues()
      throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    FakeExtractorOutput expectedOutput =
        TestUtil.extractAllSamplesFromFile(
            new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED),
            context,
            "media/mkv/sample.mkv");

    FakeExtractorOutput output =
        TestUtil.extractAllSamplesFromFile(
            new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED),
            context,
            CHAINED_SEEK_HEADS_FILE);

    assertThat(output.seekMap.isSeekable()).isTrue();
    assertThat(output.seekMap.getSeekPoints(expectedOutput.seekMap.getDurationUs() / 2))
        .isEqualTo(
            expectedOutput.seekMap.getSeekPoints(expectedOutput.seekMap.getDurationUs() / 2));
    assertSameSamples(expectedOutput, output);
  }

  @Test
  public void read_withChainedSeekHeadsAndSeekForCuesDisabled_outputsUnseekableSeekMap()
      throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    byte[] fileBytes = TestUtil.getByteArray(context, CHAINED_SEEK_HEADS_FILE);

    FakeExtractorOutput output =
        TestUtil.extractAllSamplesFromByteArray(
            new MatroskaExtractor(
                SubtitleParser.Factory.UNSUPPORTED, MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES),
            fileBytes);

    assertThat(output.seekMap.isSeekable()).isFalse();
    assertSameSamples(extractAllSamplesFromSample(context), output);
  }

  @Test
  public void read_withSeekHeadNamingNoCuesOrSeekHead_outputsUnseekableSeekMap() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    byte[] fileBytes = TestUtil.getByteArray(context, CHAINED_SEEK_HEADS_FILE);
    // Make the last SeekHead name the Info element instead of the Cues.
    writeUnsignedInt(fileBytes, fileBytes.length - 10, 0x1549A966);

    assertUnseekableWithAllSamples(context, fileBytes);
  }

  @Test
  public void read_withSeekHeadNamingItself_outputsUnseekableSeekMap() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    byte[] fileBytes = TestUtil.getByteArray(context, CHAINED_SEEK_HEADS_FILE);
    // Make the last SeekHead name itself instead of the Cues.
    writeUnsignedInt(fileBytes, fileBytes.length - 10, 0x114D9B74);
    writeUnsignedInt24(
        fileBytes, fileBytes.length - 3, fileBytes.length - 21 - SEGMENT_CONTENT_POSITION);

    assertUnseekableWithAllSamples(context, fileBytes);
  }

  @Test
  public void read_withSeekHeadNamingEarlierSeekHead_outputsUnseekableSeekMap() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    byte[] fileBytes = TestUtil.getByteArray(context, CHAINED_SEEK_HEADS_FILE);
    // Make the last SeekHead name the second SeekHead instead of the Cues.
    writeUnsignedInt(fileBytes, fileBytes.length - 10, 0x114D9B74);
    writeUnsignedInt24(
        fileBytes, fileBytes.length - 3, fileBytes.length - 42 - SEGMENT_CONTENT_POSITION);

    assertUnseekableWithAllSamples(context, fileBytes);
  }

  @Test
  public void read_withSeekHeadNamingPositionOutsideFile_outputsUnseekableSeekMap()
      throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    byte[] fileBytes = TestUtil.getByteArray(context, CHAINED_SEEK_HEADS_FILE);
    // Make the second SeekHead name a SeekHead beyond the end of the file.
    writeUnsignedInt24(fileBytes, fileBytes.length - 24, 0xFFFFFF);

    assertUnseekableWithAllSamples(context, fileBytes);
  }

  private static void assertUnseekableWithAllSamples(Context context, byte[] fileBytes)
      throws Exception {
    FakeExtractorOutput output =
        TestUtil.extractAllSamplesFromByteArray(
            new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED), fileBytes);

    assertThat(output.seekMap.isSeekable()).isFalse();
    assertSameSamples(extractAllSamplesFromSample(context), output);
  }

  private static FakeExtractorOutput extractAllSamplesFromSample(Context context) throws Exception {
    return TestUtil.extractAllSamplesFromFile(
        new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED), context, "media/mkv/sample.mkv");
  }

  private static void assertSameSamples(FakeExtractorOutput expected, FakeExtractorOutput actual) {
    assertThat(actual.numberOfTracks).isEqualTo(expected.numberOfTracks);
    for (int i = 0; i < expected.numberOfTracks; i++) {
      assertThat(actual.trackOutputs.valueAt(i).getSampleCount())
          .isEqualTo(expected.trackOutputs.valueAt(i).getSampleCount());
    }
  }

  private static void writeUnsignedInt(byte[] data, int offset, int value) {
    for (int i = 0; i < 4; i++) {
      data[offset + i] = (byte) (value >>> (8 * (3 - i)));
    }
  }

  private static void writeUnsignedInt24(byte[] data, int offset, int value) {
    for (int i = 0; i < 3; i++) {
      data[offset + i] = (byte) (value >>> (8 * (2 - i)));
    }
  }
}
