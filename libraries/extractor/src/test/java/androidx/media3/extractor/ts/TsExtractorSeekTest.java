/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.net.Uri;
import android.util.SparseArray;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.datasource.DataSourceUtil;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.ForwardingSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.jspecify.annotations.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Seeking tests for {@link TsExtractor}. */
@RunWith(AndroidJUnit4.class)
public final class TsExtractorSeekTest {

  private static final String TEST_FILE = "media/ts/bbb_2500ms.ts";
  private static final int DURATION_US = 2_500_000;
  private static final int AUDIO_TRACK_ID = 257;
  private static final long MAXIMUM_TIMESTAMP_DELTA_US = 500_000L;

  private static final Random random = new Random(1234L);

  private FakeTrackOutput expectedTrackOutput;
  private DefaultDataSource dataSource;
  private PositionHolder positionHolder;

  @Before
  public void setUp() throws IOException {
    positionHolder = new PositionHolder();
    expectedTrackOutput =
        TestUtil.extractAllSamplesFromFile(
                new TsExtractor(new DefaultSubtitleParserFactory()),
                ApplicationProvider.getApplicationContext(),
                TEST_FILE)
            .trackOutputs
            .get(AUDIO_TRACK_ID);

    dataSource =
        new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext())
            .createDataSource();
  }

  @Test
  public void tsExtractorReads_nonSeekTableFile_returnSeekableSeekMap() throws IOException {
    Uri fileUri = TestUtil.buildAssetUri(TEST_FILE);
    TsExtractor extractor = new TsExtractor(new DefaultSubtitleParserFactory());

    SeekMap seekMap =
        TestUtil.extractSeekMap(extractor, new FakeExtractorOutput(), dataSource, fileUri);

    assertThat(seekMap).isNotNull();
    assertThat(seekMap.getDurationUs()).isEqualTo(DURATION_US);
    assertThat(seekMap.isSeekable()).isTrue();
  }

  @Test
  public void handlePendingSeek_handlesSeekingToPositionInFile_extractsCorrectFrame()
      throws IOException {
    TsExtractor extractor = new TsExtractor(new DefaultSubtitleParserFactory());
    Uri fileUri = TestUtil.buildAssetUri(TEST_FILE);

    FakeExtractorOutput extractorOutput = new FakeExtractorOutput();
    SeekMap seekMap = TestUtil.extractSeekMap(extractor, extractorOutput, dataSource, fileUri);
    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(AUDIO_TRACK_ID);

    long targetSeekTimeUs = 987_000;
    int extractedFrameIndex =
        TestUtil.seekToTimeUs(
            extractor, seekMap, targetSeekTimeUs, dataSource, trackOutput, fileUri);

    assertThat(extractedFrameIndex).isNotEqualTo(-1);
    assertFirstFrameAfterSeekContainTargetSeekTime(
        trackOutput, targetSeekTimeUs, extractedFrameIndex);
  }

  @Test
  public void handlePendingSeek_handlesSeekToEoF_extractsLastFrame() throws IOException {
    TsExtractor extractor = new TsExtractor(new DefaultSubtitleParserFactory());
    Uri fileUri = TestUtil.buildAssetUri(TEST_FILE);

    FakeExtractorOutput extractorOutput = new FakeExtractorOutput();
    SeekMap seekMap = TestUtil.extractSeekMap(extractor, extractorOutput, dataSource, fileUri);
    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(AUDIO_TRACK_ID);

    long targetSeekTimeUs = seekMap.getDurationUs();

    int extractedFrameIndex =
        TestUtil.seekToTimeUs(
            extractor, seekMap, targetSeekTimeUs, dataSource, trackOutput, fileUri);

    assertThat(extractedFrameIndex).isNotEqualTo(-1);
    assertFirstFrameAfterSeekContainTargetSeekTime(
        trackOutput, targetSeekTimeUs, extractedFrameIndex);
  }

  @Test
  public void handlePendingSeek_handlesSeekingBackward_extractsCorrectFrame() throws IOException {
    TsExtractor extractor = new TsExtractor(new DefaultSubtitleParserFactory());
    Uri fileUri = TestUtil.buildAssetUri(TEST_FILE);

    FakeExtractorOutput extractorOutput = new FakeExtractorOutput();
    SeekMap seekMap = TestUtil.extractSeekMap(extractor, extractorOutput, dataSource, fileUri);
    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(AUDIO_TRACK_ID);

    long firstSeekTimeUs = 987_000;
    TestUtil.seekToTimeUs(extractor, seekMap, firstSeekTimeUs, dataSource, trackOutput, fileUri);

    long targetSeekTimeUs = 0;
    int extractedFrameIndex =
        TestUtil.seekToTimeUs(
            extractor, seekMap, targetSeekTimeUs, dataSource, trackOutput, fileUri);

    assertThat(extractedFrameIndex).isNotEqualTo(-1);
    assertFirstFrameAfterSeekContainTargetSeekTime(
        trackOutput, targetSeekTimeUs, extractedFrameIndex);
  }

  @Test
  public void handlePendingSeek_handlesSeekingForward_extractsCorrectFrame() throws IOException {
    TsExtractor extractor = new TsExtractor(new DefaultSubtitleParserFactory());
    Uri fileUri = TestUtil.buildAssetUri(TEST_FILE);

    FakeExtractorOutput extractorOutput = new FakeExtractorOutput();
    SeekMap seekMap = TestUtil.extractSeekMap(extractor, extractorOutput, dataSource, fileUri);
    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(AUDIO_TRACK_ID);

    long firstSeekTimeUs = 987_000;
    TestUtil.seekToTimeUs(extractor, seekMap, firstSeekTimeUs, dataSource, trackOutput, fileUri);

    long targetSeekTimeUs = 1_234_000;
    int extractedFrameIndex =
        TestUtil.seekToTimeUs(
            extractor, seekMap, targetSeekTimeUs, dataSource, trackOutput, fileUri);

    assertThat(extractedFrameIndex).isNotEqualTo(-1);
    assertFirstFrameAfterSeekContainTargetSeekTime(
        trackOutput, targetSeekTimeUs, extractedFrameIndex);
  }

  @Test
  public void handlePendingSeek_handlesRandomSeeks_extractsCorrectFrame() throws IOException {
    TsExtractor extractor = new TsExtractor(new DefaultSubtitleParserFactory());
    Uri fileUri = TestUtil.buildAssetUri(TEST_FILE);

    FakeExtractorOutput extractorOutput = new FakeExtractorOutput();
    SeekMap seekMap = TestUtil.extractSeekMap(extractor, extractorOutput, dataSource, fileUri);
    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(AUDIO_TRACK_ID);

    long numSeek = 100;
    for (long i = 0; i < numSeek; i++) {
      long targetSeekTimeUs = random.nextInt(DURATION_US + 1);
      int extractedFrameIndex =
          TestUtil.seekToTimeUs(
              extractor, seekMap, targetSeekTimeUs, dataSource, trackOutput, fileUri);

      assertThat(extractedFrameIndex).isNotEqualTo(-1);
      assertFirstFrameAfterSeekContainTargetSeekTime(
          trackOutput, targetSeekTimeUs, extractedFrameIndex);
    }
  }

  @Test
  public void handlePendingSeek_handlesRandomSeeksAfterReadingFileOnce_extractsCorrectFrame()
      throws IOException {
    TsExtractor extractor = new TsExtractor(new DefaultSubtitleParserFactory());
    Uri fileUri = TestUtil.buildAssetUri(TEST_FILE);

    FakeExtractorOutput extractorOutput = new FakeExtractorOutput();
    readInputFileOnce(extractor, extractorOutput, fileUri);
    SeekMap seekMap = extractorOutput.seekMap;
    FakeTrackOutput trackOutput = extractorOutput.trackOutputs.get(AUDIO_TRACK_ID);

    long numSeek = 100;
    for (long i = 0; i < numSeek; i++) {
      long targetSeekTimeUs = random.nextInt(DURATION_US + 1);
      int extractedFrameIndex =
          TestUtil.seekToTimeUs(
              extractor, seekMap, targetSeekTimeUs, dataSource, trackOutput, fileUri);

      assertThat(extractedFrameIndex).isNotEqualTo(-1);
      assertFirstFrameAfterSeekContainTargetSeekTime(
          trackOutput, targetSeekTimeUs, extractedFrameIndex);
    }
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
    TsPayloadReader.Factory payloadReaderFactory =
        new TsPayloadReader.Factory() {
          private final DefaultTsPayloadReaderFactory defaultFactory =
              new DefaultTsPayloadReaderFactory();

          @Override
          public SparseArray<TsPayloadReader> createInitialPayloadReaders() {
            return defaultFactory.createInitialPayloadReaders();
          }

          @Override
          public @Nullable TsPayloadReader createPayloadReader(
              int streamType, TsPayloadReader.EsInfo esInfo) {
            if (streamType == TsExtractor.TS_STREAM_TYPE_MPA) {
              return new PesReader(
                  new DvbSubtitleReader(
                      ImmutableList.of(
                          new TsPayloadReader.DvbSubtitleInfo(
                              /* language= */ "en",
                              /* type= */ 0,
                              /* initializationData= */ new byte[4])),
                      MimeTypes.VIDEO_MP2T));
            }
            return defaultFactory.createPayloadReader(streamType, esInfo);
          }
        };
    TsExtractor extractor =
        new TsExtractor(
            TsExtractor.MODE_SINGLE_PMT,
            /* extractorFlags= */ 0,
            subtitleParserFactory,
            new TimestampAdjuster(0),
            payloadReaderFactory,
            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES);
    TestUtil.extractAllSamplesFromFile(
        extractor,
        ApplicationProvider.getApplicationContext(),
        "media/ts/sample_h262_mpeg_audio.ts");
    SubtitleParser subtitleParser = Iterables.getOnlyElement(createdParsers);
    // Clear invocations from TsExtractor's internal seek(0, 0) via pendingSeekToStart during
    // initial extraction.
    clearInvocations(subtitleParser);

    extractor.seek(/* position= */ 0, /* timeUs= */ 0);

    verify(subtitleParser).reset();
  }

  // Internal methods

  private void readInputFileOnce(
      TsExtractor extractor, FakeExtractorOutput extractorOutput, Uri fileUri) throws IOException {
    extractor.init(extractorOutput);
    int readResult = Extractor.RESULT_CONTINUE;
    ExtractorInput input = TestUtil.getExtractorInputFromPosition(dataSource, 0, fileUri);
    while (readResult != Extractor.RESULT_END_OF_INPUT) {
      try {
        while (readResult == Extractor.RESULT_CONTINUE) {
          readResult = extractor.read(input, positionHolder);
        }
      } finally {
        DataSourceUtil.closeQuietly(dataSource);
      }
      if (readResult == Extractor.RESULT_SEEK) {
        input =
            TestUtil.getExtractorInputFromPosition(dataSource, positionHolder.position, fileUri);
        readResult = Extractor.RESULT_CONTINUE;
      }
    }
  }

  private void assertFirstFrameAfterSeekContainTargetSeekTime(
      FakeTrackOutput trackOutput, long seekTimeUs, int firstFrameIndexAfterSeek) {
    long outputSampleTimeUs = trackOutput.getSampleTimeUs(firstFrameIndexAfterSeek);
    int expectedSampleIndex =
        findOutputFrameInExpectedOutput(trackOutput.getSampleData(firstFrameIndexAfterSeek));
    // Assert that after seeking, the first sample frame written to output exists in the sample list
    assertThat(expectedSampleIndex).isNotEqualTo(-1);
    // Assert that the timestamp output for first sample after seek is near the seek point.
    // For Ts seeking, unfortunately we can't guarantee exact frame seeking, since PID timestamp is
    // not too reliable.
    assertThat(Math.abs(outputSampleTimeUs - seekTimeUs)).isLessThan(MAXIMUM_TIMESTAMP_DELTA_US);
    // Assert that the timestamp output for first sample after seek is near the actual sample
    // at seek point.
    // Note that the timestamp output for first sample after seek might *NOT* be equal to the
    // timestamp of that same sample when reading from the beginning, because if first timestamp in
    // the stream was not read before the seek, then the timestamp of the first sample after the
    // seek is just approximated from the seek point.
    assertThat(
            Math.abs(outputSampleTimeUs - expectedTrackOutput.getSampleTimeUs(expectedSampleIndex)))
        .isLessThan(MAXIMUM_TIMESTAMP_DELTA_US);
    trackOutput.assertSample(
        firstFrameIndexAfterSeek,
        expectedTrackOutput.getSampleData(expectedSampleIndex),
        outputSampleTimeUs,
        expectedTrackOutput.getSampleFlags(expectedSampleIndex),
        expectedTrackOutput.getSampleCryptoData(expectedSampleIndex));
  }

  private int findOutputFrameInExpectedOutput(byte[] sampleData) {
    for (int i = 0; i < expectedTrackOutput.getSampleCount(); i++) {
      byte[] currentSampleData = expectedTrackOutput.getSampleData(i);
      if (Arrays.equals(currentSampleData, sampleData)) {
        return i;
      }
    }
    return -1;
  }
}
