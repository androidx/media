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
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.Util;
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
import com.google.common.primitives.Bytes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Non-parameterized tests for {@link MatroskaExtractor}. */
@RunWith(AndroidJUnit4.class)
public class MatroskaExtractorNonParameterizedTest {

  @Test
  public void read_webmWebVttSubtitlesAndCaptions_outputsTimedCues() throws Exception {
    String fileName = "media/mkv/sample_with_webm_webvtt.webm";
    MatroskaExtractor extractor = new MatroskaExtractor(new DefaultSubtitleParserFactory());

    FakeExtractorOutput output =
        TestUtil.extractAllSamplesFromFile(
            extractor, ApplicationProvider.getApplicationContext(), fileName);

    List<FakeTrackOutput> textTracks = output.getTrackOutputsForType(C.TRACK_TYPE_TEXT);
    assertThat(textTracks).hasSize(2);
    assertThat(textTracks.get(0).lastFormat.roleFlags & C.ROLE_FLAG_CAPTION).isEqualTo(0);
    assertThat(textTracks.get(1).lastFormat.roleFlags & C.ROLE_FLAG_CAPTION)
        .isEqualTo(C.ROLE_FLAG_CAPTION);
    CueDecoder decoder = new CueDecoder();
    for (FakeTrackOutput track : textTracks) {
      assertThat(track.getSampleCount()).isEqualTo(1);
      byte[] data = track.getSampleData(0);
      CuesWithTiming cues = decoder.decode(track.getSampleTimeUs(0), data, 0, data.length);
      assertThat(cues.startTimeUs).isEqualTo(250_000);
      assertThat(cues.durationUs).isEqualTo(875_000);
      assertThat(Iterables.getOnlyElement(cues.cues).text.toString())
          .isEqualTo("First line\nSecond line");
      assertThat(Iterables.getOnlyElement(cues.cues).line).isEqualTo(0.18f);
    }
  }

  @Test
  public void read_webmWebVttNotePrefixIdentifier_outputsCue() throws Exception {
    byte[] webm =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(), "media/mkv/sample_with_webm_webvtt.webm");
    byte[] previousId = "first-cue".getBytes(StandardCharsets.UTF_8);
    byte[] notePrefixId = "NOTE-cue0".getBytes(StandardCharsets.UTF_8);
    int identifierOffset = Bytes.indexOf(webm, previousId);
    assertThat(identifierOffset).isAtLeast(0);
    System.arraycopy(notePrefixId, 0, webm, identifierOffset, notePrefixId.length);

    FakeExtractorOutput output =
        TestUtil.extractAllSamplesFromByteArray(
            new MatroskaExtractor(new DefaultSubtitleParserFactory()), webm);

    List<FakeTrackOutput> textTracks = output.getTrackOutputsForType(C.TRACK_TYPE_TEXT);
    assertThat(textTracks).hasSize(2);
    for (FakeTrackOutput track : textTracks) {
      assertThat(track.getSampleCount()).isEqualTo(1);
    }
  }

  @Test
  public void read_webmWebVttWithRawSubtitleOutput_reconstructsIdentifierAndSettings()
      throws Exception {
    MatroskaExtractor extractor =
        new MatroskaExtractor(
            SubtitleParser.Factory.UNSUPPORTED, MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA);

    FakeExtractorOutput output =
        TestUtil.extractAllSamplesFromFile(
            extractor,
            ApplicationProvider.getApplicationContext(),
            "media/mkv/sample_with_webm_webvtt.webm");

    List<FakeTrackOutput> textTracks = output.getTrackOutputsForType(C.TRACK_TYPE_TEXT);
    assertThat(textTracks).hasSize(2);
    for (FakeTrackOutput track : textTracks) {
      assertThat(Util.fromUtf8Bytes(track.getSampleData(0)))
          .isEqualTo(
              "WEBVTT\n\nfirst-cue\n00:00:00.000 --> 00:00:00.875 line:18%\nFirst line\nSecond line");
    }
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
