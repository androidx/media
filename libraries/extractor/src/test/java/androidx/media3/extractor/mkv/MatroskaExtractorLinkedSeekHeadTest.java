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
package androidx.media3.extractor.mkv;

import static androidx.media3.extractor.Extractor.RESULT_END_OF_INPUT;
import static androidx.media3.extractor.Extractor.RESULT_SEEK;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.extractor.ForwardingExtractorInput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.TrackAwareSeekMap;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.FakeExtractorInput;
import androidx.media3.test.utils.FakeExtractorInput.SimulatedIOException;
import androidx.media3.test.utils.FakeExtractorOutput;
import androidx.media3.test.utils.FakeTrackOutput;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.EOFException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests linked Matroska SeekHeads and optional index recovery. */
@RunWith(AndroidJUnit4.class)
public final class MatroskaExtractorLinkedSeekHeadTest {
  // Offsets in sample.mkv. Append secondary SeekHeads without changing the media samples.
  private static final int SEGMENT_POSITION = 52;
  private static final int ROOT_INDEX_ID_POSITION = 93;
  private static final int ROOT_INDEX_OFFSET_POSITION = 100;
  private static final int ROOT_TAGS_ID_POSITION = 109;
  private static final int ROOT_TAGS_OFFSET_POSITION = 116;
  private static final int NESTED_INDEX_POSITION = 108_536;
  private static final int SEEK_HEAD_SIZE = 21;
  private static final int CLUSTER_POSITION = 5_562;
  private static final int CLUSTER_CONTENT_POSITION = 5_569;
  private static final int CUES_POSITION = 107_445;

  @Test
  public void prepare_chainedSeekHead_eagerLoadsCues() throws Exception {
    assertEagerChainedSeekHead(/* flags= */ 0, /* unknownLength= */ false);
  }

  @Test
  public void prepare_chainedSeekHeadWithUnknownLength_eagerLoadsCues() throws Exception {
    assertEagerChainedSeekHead(/* flags= */ 0, /* unknownLength= */ true);
  }

  @Test
  public void prepare_chainedSeekHeadReadErrors_resumeTraversal() throws Exception {
    for (boolean unknownLength : new boolean[] {false, true}) {
      Harness harness = new Harness(chainedSample(2, false), 0, unknownLength, true);
      harness.prepare();

      assertThat(harness.simulatedIoErrors).isGreaterThan(0);
      assertThat(harness.output.seekMap.isSeekable()).isTrue();
      assertThat(harness.seeks)
          .containsExactly(
              (long) NESTED_INDEX_POSITION,
              (long) NESTED_INDEX_POSITION + SEEK_HEAD_SIZE,
              (long) CUES_POSITION,
              (long) CLUSTER_CONTENT_POSITION)
          .inOrder();
      Harness eager = new Harness(sample(), 0, false);
      eager.prepare();
      assertSameVideoSamples(harness, eager);
    }
  }

  @Test
  public void prepare_cyclicSeekHead_stopsResolvingAndPreservesSamples() throws Exception {
    for (boolean unknownLength : new boolean[] {false, true}) {
      Harness harness = new Harness(chainedSample(true), 0, unknownLength);
      harness.prepare();

      assertThat(harness.output.seekMap.isSeekable()).isFalse();
      assertThat(harness.output.tracksEnded).isTrue();
      assertThat(harness.seeks)
          .containsExactly((long) NESTED_INDEX_POSITION, (long) CLUSTER_CONTENT_POSITION)
          .inOrder();
      Harness eager = new Harness(sample(), 0, false);
      eager.prepare();
      assertSameVideoSamples(harness, eager);
    }
  }

  @Test
  public void prepare_seekHeadTraversalLimit_preservesSamples() throws Exception {
    Harness harness = new Harness(chainedSample(40, false), 0, false);
    harness.prepare();

    assertThat(harness.output.seekMap.isSeekable()).isFalse();
    assertThat(harness.seeks).hasSize(33);
    assertThat(harness.seeks.get(32)).isEqualTo(CLUSTER_CONTENT_POSITION);
    Harness eager = new Harness(sample(), 0, false);
    eager.prepare();
    assertSameVideoSamples(harness, eager);
  }

  @Test
  public void prepare_seekToStartDuringTraversal_restartsTraversal() throws Exception {
    Harness harness = new Harness(chainedSample(false), 0, false);
    harness.read();
    harness.read();
    assertThat(harness.seeks)
        .containsExactly((long) NESTED_INDEX_POSITION, (long) CUES_POSITION)
        .inOrder();

    harness.extractor.seek(/* position= */ 0, /* timeUs= */ 0);
    harness.data.setPosition(0);
    harness.seeks.clear();
    harness.prepare();

    assertThat(harness.output.seekMap.isSeekable()).isTrue();
    // Reuse the discovered Cues position, but load the data canceled before it was read.
    assertThat(harness.seeks)
        .containsExactly((long) CUES_POSITION, (long) CLUSTER_CONTENT_POSITION)
        .inOrder();
    Harness eager = new Harness(sample(), 0, false);
    eager.prepare();
    assertSameVideoSamples(harness, eager);
  }

  @Test
  public void prepare_secondarySeekHeadOutsideSegment_doesNotSeek() throws Exception {
    byte[] bytes = chainedSample(false);
    writeOffset(bytes, ROOT_INDEX_OFFSET_POSITION, bytes.length);
    for (boolean unknownLength : new boolean[] {false, true}) {
      Harness harness = new Harness(bytes, 0, unknownLength);
      harness.prepare();

      assertThat(harness.output.seekMap.isSeekable()).isFalse();
      assertThat(harness.seeks).isEmpty();
      Harness eager = new Harness(sample(), 0, false);
      eager.prepare();
      assertSameVideoSamples(harness, eager);
    }
  }

  @Test
  public void prepare_inputLengthBecomesKnown_skipsOutOfBoundsSeekHead() throws Exception {
    byte[] bytes = chainedSampleWithOutOfBoundsSeekHead(1);
    Harness harness = new Harness(bytes, /* flags= */ 0, /* unknownLength= */ true);
    harness.read();
    assertThat(harness.seeks).containsExactly((long) NESTED_INDEX_POSITION);

    harness.reportedInputLength = bytes.length;
    harness.prepare();

    assertThat(harness.output.seekMap.isSeekable()).isFalse();
    assertThat(harness.seeks)
        .containsExactly((long) NESTED_INDEX_POSITION, (long) CLUSTER_CONTENT_POSITION)
        .inOrder();
    Harness eager = new Harness(sample(), /* flags= */ 0, /* unknownLength= */ false);
    eager.prepare();
    assertSameVideoSamples(harness, eager);
  }

  @Test
  public void prepare_inputLengthBecomesUnknown_keepsPreviouslyKnownBounds() throws Exception {
    byte[] bytes = chainedSampleWithOutOfBoundsSeekHead(2);
    Harness harness = new Harness(bytes, /* flags= */ 0, /* unknownLength= */ true);
    harness.read();
    harness.reportedInputLength = bytes.length;
    harness.read();
    harness.reportedInputLength = C.LENGTH_UNSET;

    harness.prepare();

    assertThat(harness.output.seekMap.isSeekable()).isFalse();
    assertThat(harness.seeks)
        .containsExactly(
            (long) NESTED_INDEX_POSITION,
            (long) NESTED_INDEX_POSITION + SEEK_HEAD_SIZE,
            (long) CLUSTER_CONTENT_POSITION)
        .inOrder();
    assertPlaybackToEndMatchesSample(harness);
  }

  @Test
  public void prepare_indexLeadsToEndOfInput_preservesSamples() throws Exception {
    byte[] bytes = chainedSample(false);
    int voidPosition = bytes.length;
    bytes = Arrays.copyOf(bytes, bytes.length + 2);
    bytes[voidPosition] = (byte) 0xec;
    bytes[voidPosition + 1] = (byte) 0x80;
    ByteBuffer.wrap(bytes, SEGMENT_POSITION - Long.BYTES, Long.BYTES)
        .putLong((1L << 56) | (bytes.length - SEGMENT_POSITION));
    writeOffset(bytes, NESTED_INDEX_POSITION + 18, voidPosition);
    for (boolean unknownLength : new boolean[] {false, true}) {
      Harness harness = new Harness(bytes, 0, unknownLength);
      harness.prepare();

      assertThat(harness.output.seekMap.isSeekable()).isFalse();
      assertThat(harness.output.tracksEnded).isTrue();
      assertThat(harness.seeks)
          .containsExactly(
              (long) NESTED_INDEX_POSITION, (long) voidPosition, (long) CLUSTER_CONTENT_POSITION)
          .inOrder();
      Harness eager = new Harness(sample(), 0, false);
      eager.prepare();
      assertSameVideoSamples(harness, eager);
    }
  }

  @Test
  public void prepare_indexLeadsToCluster_preservesSamples() throws Exception {
    byte[] bytes = chainedSample(false);
    writeOffset(bytes, NESTED_INDEX_POSITION + 18, CLUSTER_POSITION);
    Harness harness = new Harness(bytes, 0, false);
    harness.prepare();

    assertThat(harness.output.seekMap.isSeekable()).isFalse();
    assertThat(harness.output.tracksEnded).isTrue();
    assertThat(harness.seeks)
        .containsExactly(
            (long) NESTED_INDEX_POSITION, (long) CLUSTER_POSITION, (long) CLUSTER_CONTENT_POSITION)
        .inOrder();
    Harness eager = new Harness(sample(), 0, false);
    eager.prepare();
    assertSameVideoSamples(harness, eager);
  }

  private static void assertEagerChainedSeekHead(int flags, boolean unknownLength)
      throws Exception {
    for (int seekHeadCount : new int[] {1, 2}) {
      Harness harness = new Harness(chainedSample(seekHeadCount, false), flags, unknownLength);
      harness.prepare();

      assertThat(harness.output.seekMap).isInstanceOf(TrackAwareSeekMap.class);
      assertThat(harness.output.seekMap.isSeekable()).isTrue();
      assertThat(harness.output.tracksEnded).isTrue();
      List<Long> expectedSeeks = new ArrayList<>();
      for (int i = 0; i < seekHeadCount; i++) {
        expectedSeeks.add((long) NESTED_INDEX_POSITION + i * SEEK_HEAD_SIZE);
      }
      expectedSeeks.add((long) CUES_POSITION);
      expectedSeeks.add((long) CLUSTER_CONTENT_POSITION);
      assertThat(harness.seeks).containsExactlyElementsIn(expectedSeeks).inOrder();

      Harness eager = new Harness(sample(), 0, false);
      eager.prepare();
      assertSameVideoSamples(harness, eager);
      long timeUs = eager.output.seekMap.getDurationUs() / 2;
      harness.seek(timeUs);
      eager.seek(timeUs);
      assertSameVideoSamples(harness, eager);
      assertThat(harness.seeks).isEmpty();
    }
  }

  private static void assertPlaybackToEndMatchesSample(Harness actual) throws Exception {
    Harness expected = new Harness(sample(), /* flags= */ 0, /* unknownLength= */ false);
    expected.prepare();
    actual.readToEnd();
    expected.readToEnd();
    FakeTrackOutput actualVideo = actual.output.getTrackOutputsForType(C.TRACK_TYPE_VIDEO).get(0);
    FakeTrackOutput expectedVideo =
        expected.output.getTrackOutputsForType(C.TRACK_TYPE_VIDEO).get(0);
    assertThat(actualVideo.getSampleCount()).isEqualTo(expectedVideo.getSampleCount());
    assertSameVideoSamples(actualVideo, expectedVideo, expectedVideo.getSampleCount());
  }

  private static byte[] chainedSampleWithOutOfBoundsSeekHead(int seekHeadCount) throws Exception {
    byte[] bytes = chainedSample(seekHeadCount, /* cyclic= */ true);
    // Use the Tags seek entry for the valid chain, visited before the invalid entry.
    System.arraycopy(bytes, ROOT_INDEX_ID_POSITION, bytes, ROOT_TAGS_ID_POSITION, 4);
    writeOffset(bytes, ROOT_INDEX_OFFSET_POSITION, bytes.length + 1);
    writeOffset(bytes, ROOT_TAGS_OFFSET_POSITION, NESTED_INDEX_POSITION);
    ByteBuffer.wrap(bytes, SEGMENT_POSITION - Long.BYTES, Long.BYTES)
        .putLong((1L << 56) | (bytes.length + 2L - SEGMENT_POSITION));
    return bytes;
  }

  private static byte[] sample() throws Exception {
    return TestUtil.getByteArray(
        ApplicationProvider.getApplicationContext(), "media/mkv/sample.mkv");
  }

  private static byte[] chainedSample(boolean cyclic) throws Exception {
    return chainedSample(/* seekHeadCount= */ 1, cyclic);
  }

  private static void writeOffset(byte[] data, int offset, int position) {
    int value = position - SEGMENT_POSITION;
    data[offset] = (byte) (value >> 16);
    data[offset + 1] = (byte) (value >> 8);
    data[offset + 2] = (byte) value;
  }

  private static byte[] chainedSample(int seekHeadCount, boolean cyclic) throws Exception {
    byte[] bytes = Arrays.copyOf(sample(), NESTED_INDEX_POSITION + seekHeadCount * SEEK_HEAD_SIZE);
    ByteBuffer.wrap(bytes, SEGMENT_POSITION - Long.BYTES, Long.BYTES)
        .putLong((1L << 56) | (bytes.length - SEGMENT_POSITION));
    byte[] seekHeadId = {0x11, 0x4d, (byte) 0x9b, 0x74};
    System.arraycopy(seekHeadId, 0, bytes, ROOT_INDEX_ID_POSITION, 4);
    writeOffset(bytes, ROOT_INDEX_OFFSET_POSITION, NESTED_INDEX_POSITION);
    byte[] nested = {
      0x11,
      0x4d,
      (byte) 0x9b,
      0x74,
      (byte) 0x90,
      0x4d,
      (byte) 0xbb,
      (byte) 0x8d,
      0x53,
      (byte) 0xab,
      (byte) 0x84,
      0x1c,
      0x53,
      (byte) 0xbb,
      0x6b,
      0x53,
      (byte) 0xac,
      (byte) 0x83,
      0,
      0,
      0
    };
    byte[] cuesId = Arrays.copyOfRange(nested, 11, 15);
    for (int i = 0; i < seekHeadCount; i++) {
      int position = NESTED_INDEX_POSITION + i * SEEK_HEAD_SIZE;
      boolean hasNextSeekHead = i + 1 < seekHeadCount;
      System.arraycopy(hasNextSeekHead || cyclic ? seekHeadId : cuesId, 0, nested, 11, 4);
      writeOffset(
          nested,
          nested.length - 3,
          hasNextSeekHead
              ? position + SEEK_HEAD_SIZE
              : cyclic ? NESTED_INDEX_POSITION : CUES_POSITION);
      System.arraycopy(nested, 0, bytes, position, nested.length);
    }
    return bytes;
  }

  private static void assertSameVideoSamples(Harness actual, Harness expected) throws Exception {
    assertSameVideoSamples(
        actual.readVideoSamples(3), expected.readVideoSamples(3), /* sampleCount= */ 3);
  }

  private static void assertSameVideoSamples(
      FakeTrackOutput actualVideo, FakeTrackOutput expectedVideo, int sampleCount) {
    for (int i = 0; i < sampleCount; i++) {
      assertThat(actualVideo.getSampleTimeUs(i)).isEqualTo(expectedVideo.getSampleTimeUs(i));
      assertThat(actualVideo.getSampleFlags(i)).isEqualTo(expectedVideo.getSampleFlags(i));
      assertThat(actualVideo.getSampleData(i)).isEqualTo(expectedVideo.getSampleData(i));
    }
  }

  @Test
  public void prepare_cuesAtIndexSeekLimit_countTowardLimit() throws Exception {
    for (int seekHeadCount : new int[] {31, 32}) {
      Harness harness = new Harness(chainedSample(seekHeadCount, false), 0, false);
      harness.prepare();

      boolean reachedCues = seekHeadCount == 31;
      assertThat(harness.output.seekMap.isSeekable()).isEqualTo(reachedCues);
      assertThat(harness.seeks.contains((long) CUES_POSITION)).isEqualTo(reachedCues);
      assertThat(harness.seeks).hasSize(33);
    }
  }

  @Test
  public void prepare_staleSeekHeadInsideVoid_preservesSamples() throws Exception {
    byte[] sample = chainedSample(false);
    byte[] bytes = Arrays.copyOf(sample, sample.length + 2);
    int seekHeadPosition = NESTED_INDEX_POSITION + 2;
    System.arraycopy(sample, NESTED_INDEX_POSITION, bytes, seekHeadPosition, SEEK_HEAD_SIZE);
    // A revised file replaced an old index with a Void but retained the earlier reference.
    bytes[NESTED_INDEX_POSITION] = (byte) 0xEC;
    bytes[NESTED_INDEX_POSITION + 1] = (byte) (0x80 | SEEK_HEAD_SIZE);
    // The obsolete Seek element has its SeekID but no SeekPosition.
    bytes[seekHeadPosition + 15] = (byte) 0xEC;
    bytes[seekHeadPosition + 16] = (byte) 0x84;
    Arrays.fill(bytes, seekHeadPosition + 17, seekHeadPosition + SEEK_HEAD_SIZE, (byte) 0);
    writeOffset(bytes, ROOT_INDEX_OFFSET_POSITION, seekHeadPosition);
    ByteBuffer.wrap(bytes, SEGMENT_POSITION - Long.BYTES, Long.BYTES)
        .putLong((1L << 56) | (bytes.length - SEGMENT_POSITION));

    Harness harness = new Harness(bytes, 0, false);
    harness.prepare();

    assertThat(harness.output.seekMap.isSeekable()).isFalse();
    assertThat(harness.seeks)
        .containsExactly((long) seekHeadPosition, (long) CLUSTER_CONTENT_POSITION)
        .inOrder();
    assertPlaybackToEndMatchesSample(harness);
  }

  @Test
  public void prepare_unknownInputAndSegmentLengths_doesNotJumpToLinkedSeekHead() throws Exception {
    byte[] bytes = chainedSample(false);
    ByteBuffer.wrap(bytes, SEGMENT_POSITION - Long.BYTES, Long.BYTES).putLong((1L << 57) - 1);
    Harness harness = new Harness(bytes, 0, /* unknownLength= */ true);
    harness.prepare();

    assertThat(harness.seeks).isEmpty();
    assertThat(harness.output.seekMap.isSeekable()).isFalse();
    assertThat(harness.readVideoSamples(1).getSampleCount()).isAtLeast(1);
  }

  @Test
  public void read_truncatedSeekHead_resumesMedia() throws Exception {
    byte[] complete = chainedSample(false);
    for (int length : new int[] {NESTED_INDEX_POSITION + 5, complete.length - 1}) {
      byte[] bytes = Arrays.copyOf(complete, length);
      if (length == NESTED_INDEX_POSITION + 5) {
        bytes[NESTED_INDEX_POSITION + 4] = 0x40;
      }
      Harness harness = new Harness(bytes, 0, false);
      harness.prepare();

      assertThat(harness.output.seekMap.isSeekable()).isFalse();
      assertThat(harness.seeks)
          .containsExactly((long) NESTED_INDEX_POSITION, (long) CLUSTER_CONTENT_POSITION)
          .inOrder();
      assertPlaybackToEndMatchesSample(harness);
    }
  }

  @Test
  public void read_truncatedMediaBlock_propagatesEof() throws Exception {
    byte[] bytes = Arrays.copyOf(sample(), CLUSTER_CONTENT_POSITION + 64);
    Harness harness = new Harness(bytes, 0, false);

    assertThrows(EOFException.class, harness::readToEnd);
  }

  @Test
  public void prepare_seekForCuesDisabled_doesNotVisitLinkedSeekHead() throws Exception {
    Harness harness =
        new Harness(chainedSample(false), MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES, false);
    harness.prepare();

    assertThat(harness.output.seekMap.isSeekable()).isFalse();
    assertThat(harness.output.tracksEnded).isTrue();
    assertThat(harness.seeks).isEmpty();
  }

  private static final class Harness {
    public final FakeExtractorOutput output = new FakeExtractorOutput();
    public final List<Long> seeks = new ArrayList<>();
    public int simulatedIoErrors;
    private final MatroskaExtractor extractor;
    private final FakeExtractorInput data;
    private final ForwardingExtractorInput input;
    private final PositionHolder position = new PositionHolder();
    private long reportedInputLength;

    public Harness(byte[] bytes, int flags, boolean unknownLength) {
      this(bytes, flags, unknownLength, /* simulateIoErrors= */ false);
    }

    public Harness(byte[] bytes, int flags, boolean unknownLength, boolean simulateIoErrors) {
      extractor = new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, flags);
      extractor.init(output);
      reportedInputLength = unknownLength ? C.LENGTH_UNSET : bytes.length;
      data =
          new FakeExtractorInput.Builder()
              .setData(bytes)
              .setSimulateIOErrors(simulateIoErrors)
              .build();
      input =
          new ForwardingExtractorInput(data) {
            @Override
            public long getLength() {
              return reportedInputLength;
            }
          };
    }

    public void prepare() throws Exception {
      for (int i = 0; output.seekMap == null && i < 1000; i++) read();
      assertThat(output.seekMap).isNotNull();
    }

    public void seek(long timeUs) {
      long offset = output.seekMap.getSeekPoints(timeUs).first.position;
      extractor.seek(offset, timeUs);
      data.setPosition((int) offset);
      seeks.clear();
      output.clearTrackOutputs();
    }

    public FakeTrackOutput readVideoSamples(int count) throws Exception {
      FakeTrackOutput video = output.getTrackOutputsForType(C.TRACK_TYPE_VIDEO).get(0);
      for (int i = 0; video.getSampleCount() < count && i < 1000; i++) read();
      assertThat(video.getSampleCount()).isAtLeast(count);
      return video;
    }

    public void readToEnd() throws Exception {
      int result;
      do {
        result = extractor.read(input, position);
        if (result == RESULT_SEEK) {
          seeks.add(position.position);
          data.setPosition((int) position.position);
        }
      } while (result != RESULT_END_OF_INPUT);
    }

    private void read() throws Exception {
      int result;
      while (true) {
        try {
          result = extractor.read(input, position);
          break;
        } catch (SimulatedIOException e) {
          // Like a progressive load with a known duration, resume without resetting the extractor.
          simulatedIoErrors++;
        }
      }
      assertThat(result).isNotEqualTo(RESULT_END_OF_INPUT);
      if (result == RESULT_SEEK) {
        seeks.add(position.position);
        data.setPosition((int) position.position);
      }
    }
  }
}
