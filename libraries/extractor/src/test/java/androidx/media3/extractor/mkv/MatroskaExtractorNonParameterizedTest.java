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

import static androidx.media3.extractor.Extractor.RESULT_END_OF_INPUT;
import static androidx.media3.extractor.Extractor.RESULT_SEEK;
import static com.google.common.base.Preconditions.checkNotNull;
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
import androidx.media3.test.utils.Dumper;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Non-parameterized tests for {@link MatroskaExtractor}. */
@RunWith(AndroidJUnit4.class)
public class MatroskaExtractorNonParameterizedTest {

  private static final byte[] PNG_PREFIX =
      TestUtil.createByteArray(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);

  @Test
  public void read_afterSniffingMkvWithLeadingBytes_shiftsCuePositions() throws Exception {
    byte[] fileBytes =
        TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), "media/mkv/sample.mkv");
    FakeExtractorOutput expected =
        TestUtil.extractAllSamplesFromByteArray(
            new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED), fileBytes);
    MatroskaExtractor extractor = new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED);
    FakeExtractorInput input =
        new FakeExtractorInput.Builder().setData(withPrefix(fileBytes)).build();
    FakeExtractorOutput actual = new FakeExtractorOutput();
    assertThat(extractor.sniff(input)).isTrue();
    assertThat(input.getPosition()).isEqualTo(0);
    input.resetPeekPosition();
    extractor.init(actual);

    readToEnd(extractor, input);

    assertSamplesMatch(actual, expected);
    long timeUs = checkNotNull(expected.seekMap).getDurationUs() / 2;
    SeekPoint expectedPoint = expected.seekMap.getSeekPoints(timeUs).first;
    SeekPoint actualPoint = checkNotNull(actual.seekMap).getSeekPoints(timeUs).first;
    assertThat(actualPoint.timeUs).isEqualTo(expectedPoint.timeUs);
    assertThat(actualPoint.position).isEqualTo(expectedPoint.position + PNG_PREFIX.length);
  }

  @Test
  public void seek_toStartOfMkvWithLeadingBytesWithoutSniffing_readsSameSamples() throws Exception {
    byte[] fileBytes =
        TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), "media/mkv/sample.mkv");
    FakeExtractorOutput expected =
        TestUtil.extractAllSamplesFromByteArray(
            new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED), fileBytes);
    MatroskaExtractor extractor = new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED);
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(withPrefix(fileBytes))
            .setSimulateUnknownLength(true)
            .build();
    FakeExtractorOutput actual = new FakeExtractorOutput();
    extractor.init(actual);
    readToEnd(extractor, input);
    assertSamplesMatch(actual, expected);
    actual.clearTrackOutputs();

    extractor.seek(/* position= */ 0, /* timeUs= */ 0);
    input.setPosition(0);
    readToEnd(extractor, input);

    assertSamplesMatch(actual, expected);
  }

  @Test
  public void sniff_ebmlHeaderBeyondSearchLimit_returnsFalse() throws Exception {
    byte[] fileBytes =
        TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), "media/mkv/sample.mkv");
    byte[] prefix = new byte[1024];
    Arrays.fill(prefix, (byte) 0xFF);
    FakeExtractorInput input =
        new FakeExtractorInput.Builder()
            .setData(
                ByteBuffer.allocate(prefix.length + fileBytes.length)
                    .put(prefix)
                    .put(fileBytes)
                    .array())
            .build();
    MatroskaExtractor extractor = new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED);

    assertThat(extractor.sniff(input)).isFalse();
    assertThat(input.getPosition()).isEqualTo(0);
    assertThat(input.getPeekPosition()).isEqualTo(1024);
  }

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

  private static byte[] withPrefix(byte[] data) {
    return ByteBuffer.allocate(PNG_PREFIX.length + data.length).put(PNG_PREFIX).put(data).array();
  }

  private static void readToEnd(MatroskaExtractor extractor, FakeExtractorInput input)
      throws IOException {
    PositionHolder seekPosition = new PositionHolder();
    int result;
    do {
      result = extractor.read(input, seekPosition);
      if (result == RESULT_SEEK) {
        input.setPosition((int) seekPosition.position);
      }
    } while (result != RESULT_END_OF_INPUT);
  }

  private static void assertSamplesMatch(FakeExtractorOutput actual, FakeExtractorOutput expected) {
    assertThat(actual.numberOfTracks).isEqualTo(expected.numberOfTracks);
    for (int i = 0; i < expected.trackOutputs.size(); i++) {
      int trackId = expected.trackOutputs.keyAt(i);
      assertThat(new Dumper().add(checkNotNull(actual.trackOutputs.get(trackId))).toString())
          .isEqualTo(new Dumper().add(expected.trackOutputs.valueAt(i)).toString());
    }
  }
}
