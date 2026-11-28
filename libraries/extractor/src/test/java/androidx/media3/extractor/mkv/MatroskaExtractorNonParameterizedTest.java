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
import static org.junit.Assert.assertThrows;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.ParserException;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackAwareSeekMap;
import androidx.media3.extractor.text.CueDecoder;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.ForwardingSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
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

  @Test
  public void read_explicitZlibSubtitle_outputsCue() throws Exception {
    assertZlibSubtitleCue("sample_with_zlib_subtitle.mkv");
  }

  @Test
  public void read_defaultZlibSubtitleWithSmallerWindow_outputsCue() throws Exception {
    assertZlibSubtitleCue("sample_with_default_zlib_subtitle.mkv");
  }

  @Test
  public void read_truncatedZlibSubtitle_throwsParserException() {
    ParserException exception =
        assertThrows(
            ParserException.class,
            () -> readZlibSubtitleFile("sample_with_truncated_zlib_subtitle.mkv"));
    assertThat(exception).hasMessageThat().contains("Invalid zlib subtitle sample");
  }

  @Test
  public void read_oversizedZlibSubtitle_throwsParserException() {
    ParserException exception =
        assertThrows(
            ParserException.class,
            () -> readZlibSubtitleFile("sample_with_oversized_zlib_subtitle.mkv"));
    assertThat(exception).hasMessageThat().contains("Inflated subtitle sample exceeds");
  }

  private static void assertZlibSubtitleCue(String fileName) throws Exception {
    FakeExtractorOutput output = readZlibSubtitleFile(fileName);
    FakeTrackOutput track =
        Iterables.getOnlyElement(output.getTrackOutputsForType(C.TRACK_TYPE_TEXT));
    assertThat(track.getSampleCount()).isEqualTo(1);
    byte[] sample = track.getSampleData(0);
    CuesWithTiming cues =
        new CueDecoder().decode(track.getSampleTimeUs(0), sample, 0, sample.length);
    assertThat(cues.startTimeUs).isEqualTo(250_000);
    assertThat(cues.durationUs).isEqualTo(875_000);
    assertThat(Iterables.getOnlyElement(cues.cues).text.toString())
        .isEqualTo("One compressed subtitle cue.");
  }

  private static FakeExtractorOutput readZlibSubtitleFile(String fileName) throws Exception {
    return TestUtil.extractAllSamplesFromFile(
        new MatroskaExtractor(new DefaultSubtitleParserFactory()),
        ApplicationProvider.getApplicationContext(),
        "media/mkv/" + fileName);
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
}
