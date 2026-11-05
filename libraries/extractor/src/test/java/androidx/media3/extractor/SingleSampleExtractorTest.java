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
package androidx.media3.extractor;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link SingleSampleExtractor}. */
@RunWith(AndroidJUnit4.class)
public final class SingleSampleExtractorTest {

  @Test
  public void read_withDataSmallerThanBufferSegmentSize_readsPayloadInSingleChunk()
      throws Exception {
    byte[] data = TestUtil.buildTestData(/* length= */ 10_000);
    SingleSampleExtractor extractor =
        new SingleSampleExtractor(
            /* fileSignature= */ C.INDEX_UNSET,
            /* fileSignatureLength= */ C.LENGTH_UNSET,
            MimeTypes.IMAGE_PNG);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    List<Integer> requestedReadLengths = new ArrayList<>();
    ExtractorInput input =
        createRecordingInput(
            new FakeExtractorInput.Builder().setData(data).build(), requestedReadLengths);
    PositionHolder positionHolder = new PositionHolder();

    int firstResult = extractor.read(input, positionHolder);
    long positionAfterFirstRead = input.getPosition();
    int secondResult = extractor.read(input, positionHolder);
    int thirdResult = extractor.read(input, positionHolder);

    assertThat(firstResult).isEqualTo(Extractor.RESULT_CONTINUE);
    assertThat(positionAfterFirstRead).isEqualTo(data.length);
    assertThat(secondResult).isEqualTo(Extractor.RESULT_CONTINUE);
    assertThat(thirdResult).isEqualTo(Extractor.RESULT_END_OF_INPUT);
    assertThat(requestedReadLengths).containsExactly(data.length, 1).inOrder();
    FakeTrackOutput trackOutput = output.trackOutputs.get(SingleSampleExtractor.IMAGE_TRACK_ID);
    trackOutput.assertSampleCount(1);
    trackOutput.assertSample(
        /* index= */ 0, data, /* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME, /* cryptoData= */ null);
  }

  @Test
  public void read_withDataLargerThanBufferSegmentSize_readsInBufferSegmentSizedChunks()
      throws Exception {
    byte[] data = TestUtil.buildTestData(/* length= */ C.DEFAULT_BUFFER_SEGMENT_SIZE * 2 + 500);
    SingleSampleExtractor extractor =
        new SingleSampleExtractor(
            /* fileSignature= */ C.INDEX_UNSET,
            /* fileSignatureLength= */ C.LENGTH_UNSET,
            MimeTypes.IMAGE_JPEG);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    List<Integer> requestedReadLengths = new ArrayList<>();
    ExtractorInput input =
        createRecordingInput(
            new FakeExtractorInput.Builder().setData(data).build(), requestedReadLengths);
    PositionHolder positionHolder = new PositionHolder();

    int firstResult = extractor.read(input, positionHolder);
    long positionAfterFirstRead = input.getPosition();
    int secondResult = extractor.read(input, positionHolder);
    long positionAfterSecondRead = input.getPosition();
    int thirdResult = extractor.read(input, positionHolder);
    long positionAfterThirdRead = input.getPosition();
    int fourthResult = extractor.read(input, positionHolder);
    int fifthResult = extractor.read(input, positionHolder);

    assertThat(firstResult).isEqualTo(Extractor.RESULT_CONTINUE);
    assertThat(positionAfterFirstRead).isEqualTo(C.DEFAULT_BUFFER_SEGMENT_SIZE);
    assertThat(secondResult).isEqualTo(Extractor.RESULT_CONTINUE);
    assertThat(positionAfterSecondRead).isEqualTo(C.DEFAULT_BUFFER_SEGMENT_SIZE * 2);
    assertThat(thirdResult).isEqualTo(Extractor.RESULT_CONTINUE);
    assertThat(positionAfterThirdRead).isEqualTo(data.length);
    assertThat(fourthResult).isEqualTo(Extractor.RESULT_CONTINUE);
    assertThat(fifthResult).isEqualTo(Extractor.RESULT_END_OF_INPUT);
    assertThat(requestedReadLengths)
        .containsExactly(C.DEFAULT_BUFFER_SEGMENT_SIZE, C.DEFAULT_BUFFER_SEGMENT_SIZE, 500, 1)
        .inOrder();
    FakeTrackOutput trackOutput = output.trackOutputs.get(SingleSampleExtractor.IMAGE_TRACK_ID);
    trackOutput.assertSampleCount(1);
    trackOutput.assertSample(
        /* index= */ 0, data, /* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME, /* cryptoData= */ null);
  }

  @Test
  public void read_withUnknownInputLength_extractsFullSampleInBufferSegmentSizedChunks()
      throws Exception {
    byte[] data = TestUtil.buildTestData(/* length= */ C.DEFAULT_BUFFER_SEGMENT_SIZE + 500);
    SingleSampleExtractor extractor =
        new SingleSampleExtractor(
            /* fileSignature= */ C.INDEX_UNSET,
            /* fileSignatureLength= */ C.LENGTH_UNSET,
            MimeTypes.IMAGE_WEBP);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    List<Integer> requestedReadLengths = new ArrayList<>();
    ExtractorInput input =
        createRecordingInput(
            new FakeExtractorInput.Builder().setData(data).setSimulateUnknownLength(true).build(),
            requestedReadLengths);
    PositionHolder positionHolder = new PositionHolder();

    int firstResult = extractor.read(input, positionHolder);
    long positionAfterFirstRead = input.getPosition();
    int secondResult = extractor.read(input, positionHolder);
    long positionAfterSecondRead = input.getPosition();
    int thirdResult = extractor.read(input, positionHolder);
    int fourthResult = extractor.read(input, positionHolder);

    assertThat(firstResult).isEqualTo(Extractor.RESULT_CONTINUE);
    assertThat(positionAfterFirstRead).isEqualTo(C.DEFAULT_BUFFER_SEGMENT_SIZE);
    assertThat(secondResult).isEqualTo(Extractor.RESULT_CONTINUE);
    assertThat(positionAfterSecondRead).isEqualTo(data.length);
    assertThat(thirdResult).isEqualTo(Extractor.RESULT_CONTINUE);
    assertThat(fourthResult).isEqualTo(Extractor.RESULT_END_OF_INPUT);
    assertThat(requestedReadLengths)
        .containsExactly(
            C.DEFAULT_BUFFER_SEGMENT_SIZE,
            C.DEFAULT_BUFFER_SEGMENT_SIZE,
            C.DEFAULT_BUFFER_SEGMENT_SIZE)
        .inOrder();
    FakeTrackOutput trackOutput = output.trackOutputs.get(SingleSampleExtractor.IMAGE_TRACK_ID);
    trackOutput.assertSampleCount(1);
    trackOutput.assertSample(
        /* index= */ 0, data, /* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME, /* cryptoData= */ null);
  }

  @Test
  public void read_withUnderreportedInputLength_extractsFullSample() throws Exception {
    byte[] data = TestUtil.buildTestData(/* length= */ 10_000);
    SingleSampleExtractor extractor =
        new SingleSampleExtractor(
            /* fileSignature= */ C.INDEX_UNSET,
            /* fileSignatureLength= */ C.LENGTH_UNSET,
            MimeTypes.IMAGE_PNG);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    ExtractorInput input =
        new ForwardingExtractorInput(new FakeExtractorInput.Builder().setData(data).build()) {
          @Override
          public long getLength() {
            return 100;
          }
        };
    PositionHolder positionHolder = new PositionHolder();

    int firstResult = extractor.read(input, positionHolder);
    long positionAfterFirstRead = input.getPosition();
    int secondResult = extractor.read(input, positionHolder);
    long positionAfterSecondRead = input.getPosition();
    int thirdResult = extractor.read(input, positionHolder);
    long positionAfterThirdRead = input.getPosition();
    int fourthResult = extractor.read(input, positionHolder);
    int fifthResult = extractor.read(input, positionHolder);

    assertThat(firstResult).isEqualTo(Extractor.RESULT_CONTINUE);
    assertThat(positionAfterFirstRead).isEqualTo(100);
    assertThat(secondResult).isEqualTo(Extractor.RESULT_CONTINUE);
    assertThat(positionAfterSecondRead).isEqualTo(101);
    assertThat(thirdResult).isEqualTo(Extractor.RESULT_CONTINUE);
    assertThat(positionAfterThirdRead).isEqualTo(data.length);
    assertThat(fourthResult).isEqualTo(Extractor.RESULT_CONTINUE);
    assertThat(fifthResult).isEqualTo(Extractor.RESULT_END_OF_INPUT);
    FakeTrackOutput trackOutput = output.trackOutputs.get(SingleSampleExtractor.IMAGE_TRACK_ID);
    trackOutput.assertSampleCount(1);
    trackOutput.assertSample(
        /* index= */ 0, data, /* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME, /* cryptoData= */ null);
  }

  @Test
  public void read_withOverreportedInputLength_extractsFullSample() throws Exception {
    byte[] data = TestUtil.buildTestData(/* length= */ 10_000);
    SingleSampleExtractor extractor =
        new SingleSampleExtractor(
            /* fileSignature= */ C.INDEX_UNSET,
            /* fileSignatureLength= */ C.LENGTH_UNSET,
            MimeTypes.IMAGE_PNG);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    ExtractorInput input =
        new ForwardingExtractorInput(new FakeExtractorInput.Builder().setData(data).build()) {
          @Override
          public long getLength() {
            return 20_000;
          }
        };
    PositionHolder positionHolder = new PositionHolder();

    int firstResult = extractor.read(input, positionHolder);
    long positionAfterFirstRead = input.getPosition();
    int secondResult = extractor.read(input, positionHolder);
    int thirdResult = extractor.read(input, positionHolder);

    assertThat(firstResult).isEqualTo(Extractor.RESULT_CONTINUE);
    assertThat(positionAfterFirstRead).isEqualTo(data.length);
    assertThat(secondResult).isEqualTo(Extractor.RESULT_CONTINUE);
    assertThat(thirdResult).isEqualTo(Extractor.RESULT_END_OF_INPUT);
    FakeTrackOutput trackOutput = output.trackOutputs.get(SingleSampleExtractor.IMAGE_TRACK_ID);
    trackOutput.assertSampleCount(1);
    trackOutput.assertSample(
        /* index= */ 0, data, /* timeUs= */ 0, C.BUFFER_FLAG_KEY_FRAME, /* cryptoData= */ null);
  }

  @Test
  public void read_withEmptyInput_outputsEmptySample() throws Exception {
    SingleSampleExtractor extractor =
        new SingleSampleExtractor(
            /* fileSignature= */ C.INDEX_UNSET,
            /* fileSignatureLength= */ C.LENGTH_UNSET,
            MimeTypes.IMAGE_PNG);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input =
        new FakeExtractorInput.Builder().setData(Util.EMPTY_BYTE_ARRAY).build();
    PositionHolder positionHolder = new PositionHolder();

    int firstResult = extractor.read(input, positionHolder);
    int secondResult = extractor.read(input, positionHolder);

    assertThat(firstResult).isEqualTo(Extractor.RESULT_CONTINUE);
    assertThat(secondResult).isEqualTo(Extractor.RESULT_END_OF_INPUT);
    FakeTrackOutput trackOutput = output.trackOutputs.get(SingleSampleExtractor.IMAGE_TRACK_ID);
    trackOutput.assertSampleCount(1);
    trackOutput.assertSample(
        /* index= */ 0,
        Util.EMPTY_BYTE_ARRAY,
        /* timeUs= */ 0,
        C.BUFFER_FLAG_KEY_FRAME,
        /* cryptoData= */ null);
  }

  private static ExtractorInput createRecordingInput(
      ExtractorInput delegate, List<Integer> requestedReadLengths) {
    return new ForwardingExtractorInput(delegate) {
      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
        requestedReadLengths.add(length);
        return super.read(buffer, offset, length);
      }
    };
  }
}
