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

import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackAwareSeekMap;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Non-parameterized tests for {@link MatroskaExtractor}. */
@RunWith(AndroidJUnit4.class)
public class MatroskaExtractorNonParameterizedTest {

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

  private static final int SEGMENT_CONTENT_POSITION = 52;
  private static final int PRIMARY_SEEK_ENTRY_CUES_ID_OFFSET = 93;
  private static final int PRIMARY_SEEK_ENTRY_CUES_POS_OFFSET = 100;
  private static final long CUES_FILE_POSITION = 107445;
  private static final long CLUSTER_FILE_POSITION = 5562;
  private static final long CLUSTER_CONTENT_POSITION = 5569;

  private static final byte[] ID_SEEK_HEAD_BYTES = new byte[] {0x11, 0x4D, (byte) 0x9B, 0x74};
  private static final byte[] ID_CUES_BYTES = new byte[] {0x1C, 0x53, (byte) 0xBB, 0x6B};
  private static final byte[] ID_CLUSTER_BYTES = new byte[] {0x1F, 0x43, (byte) 0xB6, 0x75};

  private static byte[] buildSeekHead(byte[] targetId, long targetFilePosition) {
    long relativePosition = targetFilePosition - SEGMENT_CONTENT_POSITION;
    byte[] seekPos =
        new byte[] {
          0x53,
          (byte) 0xAC,
          (byte) 0x83,
          (byte) ((relativePosition >> 16) & 0xFF),
          (byte) ((relativePosition >> 8) & 0xFF),
          (byte) (relativePosition & 0xFF)
        };
    byte[] seekId =
        new byte[] {
          0x53, (byte) 0xAB, (byte) 0x84, targetId[0], targetId[1], targetId[2], targetId[3]
        };
    int seekContentLen = seekId.length + seekPos.length;
    byte[] seek = new byte[2 + 1 + seekContentLen];
    seek[0] = 0x4D;
    seek[1] = (byte) 0xBB;
    seek[2] = (byte) (0x80 | seekContentLen);
    System.arraycopy(seekId, 0, seek, 3, seekId.length);
    System.arraycopy(seekPos, 0, seek, 3 + seekId.length, seekPos.length);

    byte[] seekHead = new byte[4 + 1 + seek.length];
    seekHead[0] = 0x11;
    seekHead[1] = 0x4D;
    seekHead[2] = (byte) 0x9B;
    seekHead[3] = 0x74;
    seekHead[4] = (byte) (0x80 | seek.length);
    System.arraycopy(seek, 0, seekHead, 5, seek.length);
    return seekHead;
  }

  private static void updatePrimarySeekHeadTarget(
      byte[] fileBytes, byte[] targetId, long targetFilePosition) {
    System.arraycopy(targetId, 0, fileBytes, PRIMARY_SEEK_ENTRY_CUES_ID_OFFSET, 4);
    long relativePosition = targetFilePosition - SEGMENT_CONTENT_POSITION;
    fileBytes[PRIMARY_SEEK_ENTRY_CUES_POS_OFFSET] = (byte) ((relativePosition >> 16) & 0xFF);
    fileBytes[PRIMARY_SEEK_ENTRY_CUES_POS_OFFSET + 1] = (byte) ((relativePosition >> 8) & 0xFF);
    fileBytes[PRIMARY_SEEK_ENTRY_CUES_POS_OFFSET + 2] = (byte) (relativePosition & 0xFF);
  }

  private static byte[] concat(byte[] first, byte[]... rest) {
    int totalLength = first.length;
    for (byte[] b : rest) {
      totalLength += b.length;
    }
    byte[] result = new byte[totalLength];
    System.arraycopy(first, 0, result, 0, first.length);
    int offset = first.length;
    for (byte[] b : rest) {
      System.arraycopy(b, 0, result, offset, b.length);
      offset += b.length;
    }
    return result;
  }

  private static void readToEnd(MatroskaExtractor extractor, FakeExtractorInput input)
      throws Exception {
    PositionHolder positionHolder = new PositionHolder();
    int readResult = Extractor.RESULT_CONTINUE;
    while (readResult != Extractor.RESULT_END_OF_INPUT) {
      readResult = extractor.read(input, positionHolder);
      if (readResult == Extractor.RESULT_SEEK) {
        input.setPosition((int) positionHolder.position);
      }
    }
  }

  private static void updateSegmentSize(byte[] fileBytes) {
    long segmentDataLength = fileBytes.length - SEGMENT_CONTENT_POSITION;
    fileBytes[48] = (byte) ((segmentDataLength >> 24) & 0xFF);
    fileBytes[49] = (byte) ((segmentDataLength >> 16) & 0xFF);
    fileBytes[50] = (byte) ((segmentDataLength >> 8) & 0xFF);
    fileBytes[51] = (byte) (segmentDataLength & 0xFF);
  }

  private static List<Long> readToEndAndRecordSeeks(
      MatroskaExtractor extractor, FakeExtractorInput input) throws Exception {
    List<Long> seeks = new ArrayList<>();
    PositionHolder positionHolder = new PositionHolder();
    int readResult = Extractor.RESULT_CONTINUE;
    int maxSeeks = 1000;
    while (readResult != Extractor.RESULT_END_OF_INPUT && maxSeeks-- > 0) {
      readResult = extractor.read(input, positionHolder);
      if (readResult == Extractor.RESULT_SEEK) {
        seeks.add(positionHolder.position);
        input.setPosition((int) positionHolder.position);
      }
    }
    return seeks;
  }

  @Test
  public void read_withSecondarySeekHeadPointingToCues_formatsEmittedBeforeSamplesAndSeekMapIsSeekable()
      throws Exception {
    byte[] baseData =
        TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), "media/mkv/sample.mkv");
    long secondarySeekHeadPos = baseData.length;
    byte[] secondarySeekHead = buildSeekHead(ID_CUES_BYTES, CUES_FILE_POSITION);
    updatePrimarySeekHeadTarget(baseData, ID_SEEK_HEAD_BYTES, secondarySeekHeadPos);
    byte[] testData = concat(baseData, secondarySeekHead);

    MatroskaExtractor extractor = new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(testData).build();

    readToEnd(extractor, input);

    assertThat(output.seekMap).isNotNull();
    assertThat(output.seekMap.isSeekable()).isTrue();
    assertThat(output.numberOfTracks).isGreaterThan(0);
  }

  @Test
  public void read_withMultipleSecondarySeekHeadHops_resolvesCuesSuccessfully()
      throws Exception {
    byte[] baseData =
        TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), "media/mkv/sample.mkv");
    long posA = baseData.length;
    byte[] seekHeadA = buildSeekHead(ID_SEEK_HEAD_BYTES, 0);
    long posB = posA + seekHeadA.length;
    byte[] seekHeadB = buildSeekHead(ID_SEEK_HEAD_BYTES, 0);
    long posC = posB + seekHeadB.length;
    byte[] seekHeadC = buildSeekHead(ID_CUES_BYTES, CUES_FILE_POSITION);

    seekHeadB = buildSeekHead(ID_SEEK_HEAD_BYTES, posC);
    seekHeadA = buildSeekHead(ID_SEEK_HEAD_BYTES, posB);
    updatePrimarySeekHeadTarget(baseData, ID_SEEK_HEAD_BYTES, posA);

    byte[] testData = concat(baseData, seekHeadA, seekHeadB, seekHeadC);

    MatroskaExtractor extractor = new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(testData).build();

    readToEnd(extractor, input);

    assertThat(output.seekMap).isNotNull();
    assertThat(output.seekMap.isSeekable()).isTrue();
    assertThat(output.numberOfTracks).isGreaterThan(0);
  }

  @Test
  public void read_withSecondarySeekHeadContainingOnlyClusterEntries_playsWithoutError()
      throws Exception {
    byte[] baseData =
        TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), "media/mkv/sample.mkv");
    long secondarySeekHeadPos = baseData.length;
    byte[] secondarySeekHead = buildSeekHead(ID_CLUSTER_BYTES, CLUSTER_FILE_POSITION);
    updatePrimarySeekHeadTarget(baseData, ID_SEEK_HEAD_BYTES, secondarySeekHeadPos);
    byte[] testData = concat(baseData, secondarySeekHead);

    MatroskaExtractor extractor = new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(testData).build();

    readToEnd(extractor, input);

    assertThat(output.seekMap).isNotNull();
    assertThat(output.seekMap.isSeekable()).isFalse();
    assertThat(output.numberOfTracks).isGreaterThan(0);
  }

  @Test
  public void read_withCyclicSeekHeads_terminatesSafelyAndCompletesPlayback()
      throws Exception {
    byte[] baseData =
        TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), "media/mkv/sample.mkv");
    long posA = baseData.length;
    byte[] seekHeadA = buildSeekHead(ID_SEEK_HEAD_BYTES, 0);
    long posB = posA + seekHeadA.length;
    byte[] seekHeadB = buildSeekHead(ID_SEEK_HEAD_BYTES, posA);
    seekHeadA = buildSeekHead(ID_SEEK_HEAD_BYTES, posB);
    updatePrimarySeekHeadTarget(baseData, ID_SEEK_HEAD_BYTES, posA);

    byte[] testData = concat(baseData, seekHeadA, seekHeadB);

    MatroskaExtractor extractor = new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(testData).build();

    readToEnd(extractor, input);

    assertThat(output.seekMap).isNotNull();
    assertThat(output.seekMap.isSeekable()).isFalse();
    assertThat(output.numberOfTracks).isGreaterThan(0);
  }

  @Test
  public void seek_beforeSeekMapSent_resetsTraversalStateAndAllowsReExtraction()
      throws Exception {
    byte[] baseData =
        TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), "media/mkv/sample.mkv");
    long secondarySeekHeadPos = baseData.length;
    byte[] secondarySeekHead = buildSeekHead(ID_CUES_BYTES, CUES_FILE_POSITION);
    updatePrimarySeekHeadTarget(baseData, ID_SEEK_HEAD_BYTES, secondarySeekHeadPos);
    byte[] testData = concat(baseData, secondarySeekHead);

    MatroskaExtractor extractor = new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(testData).build();

    PositionHolder positionHolder = new PositionHolder();
    int readResult = extractor.read(input, positionHolder);
    if (readResult == Extractor.RESULT_SEEK) {
      input.setPosition((int) positionHolder.position);
    }
    assertThat(output.seekMap).isNull();

    // Reset before seek map was sent
    extractor.seek(0, 0);
    input.setPosition(0);

    // Re-extract from beginning
    readToEnd(extractor, input);

    assertThat(output.seekMap).isNotNull();
    assertThat(output.seekMap.isSeekable()).isTrue();
    assertThat(output.numberOfTracks).isGreaterThan(0);
  }

  @Test
  public void read_withCuesReferencePointingToCluster_terminatesSeeksAndOutputsSamples()
      throws Exception {
    byte[] baseData =
        TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), "media/mkv/sample.mkv");
    long secondarySeekHeadPos = baseData.length;
    byte[] secondarySeekHead = buildSeekHead(ID_CUES_BYTES, CLUSTER_FILE_POSITION);
    updatePrimarySeekHeadTarget(baseData, ID_SEEK_HEAD_BYTES, secondarySeekHeadPos);
    byte[] testData = concat(baseData, secondarySeekHead);
    updateSegmentSize(testData);

    MatroskaExtractor extractor = new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(testData).build();

    List<Long> seeks = readToEndAndRecordSeeks(extractor, input);

    assertThat(seeks).containsExactly(secondarySeekHeadPos, CLUSTER_FILE_POSITION).inOrder();
    assertThat(output.seekMap).isNotNull();
    assertThat(output.seekMap.isSeekable()).isFalse();
    assertThat(output.numberOfTracks).isGreaterThan(0);
    assertThat(output.trackOutputs.get(1).getSampleCount()).isGreaterThan(0);
    assertThat(output.trackOutputs.get(1).getSampleTimeUs(0)).isEqualTo(67_000);
  }

  @Test
  public void read_withCuesReferenceReachingEof_terminatesFallbackAndOutputsSamples()
      throws Exception {
    byte[] baseData =
        TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), "media/mkv/sample.mkv");
    long secondarySeekHeadPos = baseData.length;
    long voidPos = secondarySeekHeadPos + 21; // 108557
    byte[] secondarySeekHead = buildSeekHead(ID_CUES_BYTES, voidPos);
    byte[] voidElement = new byte[] {(byte) 0xEC, (byte) 0x80}; // Empty Void element
    updatePrimarySeekHeadTarget(baseData, ID_SEEK_HEAD_BYTES, secondarySeekHeadPos);
    byte[] testData = concat(baseData, secondarySeekHead, voidElement);
    updateSegmentSize(testData);

    MatroskaExtractor extractor = new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(testData).build();

    List<Long> seeks = readToEndAndRecordSeeks(extractor, input);

    assertThat(seeks).contains(voidPos);
    assertThat(output.seekMap).isNotNull();
    assertThat(output.seekMap.isSeekable()).isFalse();
    assertThat(output.numberOfTracks).isGreaterThan(0);
    assertThat(output.trackOutputs.get(1).getSampleCount()).isGreaterThan(0);
    assertThat(output.trackOutputs.get(1).getSampleTimeUs(0)).isEqualTo(67_000);
  }

  @Test
  public void read_withCuesReferencePointingToSecondarySeekHeadWithoutCues_terminatesAndOutputsSamples()
      throws Exception {
    byte[] baseData =
        TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), "media/mkv/sample.mkv");
    long posA = baseData.length;
    long posB = posA + 21;
    byte[] seekHeadA = buildSeekHead(ID_CUES_BYTES, posB);
    byte[] seekHeadB = buildSeekHead(ID_CLUSTER_BYTES, CLUSTER_FILE_POSITION);
    updatePrimarySeekHeadTarget(baseData, ID_SEEK_HEAD_BYTES, posA);
    byte[] testData = concat(baseData, seekHeadA, seekHeadB);
    updateSegmentSize(testData);

    MatroskaExtractor extractor = new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(testData).build();

    List<Long> seeks = readToEndAndRecordSeeks(extractor, input);

    assertThat(seeks).containsExactly(posA, posB, CLUSTER_CONTENT_POSITION).inOrder();
    assertThat(output.seekMap).isNotNull();
    assertThat(output.seekMap.isSeekable()).isFalse();
    assertThat(output.numberOfTracks).isGreaterThan(0);
    assertThat(output.trackOutputs.get(1).getSampleCount()).isGreaterThan(0);
    assertThat(output.trackOutputs.get(1).getSampleTimeUs(0)).isEqualTo(67_000);
  }

  @Test
  public void read_withCyclicCuesReferences_terminatesSafelyAndOutputsSamples()
      throws Exception {
    byte[] baseData =
        TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), "media/mkv/sample.mkv");
    long posA = baseData.length;
    long posB = posA + 21;
    byte[] seekHeadA = buildSeekHead(ID_CUES_BYTES, posB);
    byte[] seekHeadB = buildSeekHead(ID_CUES_BYTES, posA);
    updatePrimarySeekHeadTarget(baseData, ID_SEEK_HEAD_BYTES, posA);
    byte[] testData = concat(baseData, seekHeadA, seekHeadB);
    updateSegmentSize(testData);

    MatroskaExtractor extractor = new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED);
    FakeExtractorOutput output = new FakeExtractorOutput();
    extractor.init(output);
    FakeExtractorInput input = new FakeExtractorInput.Builder().setData(testData).build();

    List<Long> seeks = readToEndAndRecordSeeks(extractor, input);

    assertThat(seeks).containsExactly(posA, posB, CLUSTER_CONTENT_POSITION).inOrder();
    assertThat(output.seekMap).isNotNull();
    assertThat(output.seekMap.isSeekable()).isFalse();
    assertThat(output.numberOfTracks).isGreaterThan(0);
    assertThat(output.trackOutputs.get(1).getSampleCount()).isGreaterThan(0);
    assertThat(output.trackOutputs.get(1).getSampleTimeUs(0)).isEqualTo(67_000);
  }
}

