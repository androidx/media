/*
 * Copyright 2022 The Android Open Source Project
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
package androidx.media3.extractor.avi;

import static androidx.media3.common.C.TRACK_TYPE_AUDIO;
import static androidx.media3.common.C.TRACK_TYPE_VIDEO;
import static androidx.media3.common.util.Assertions.checkArgument;
import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.media3.common.C;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackOutput;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

/** Reads chunks holding sample data. */
/* package */ final class ChunkReader {

  /** Parser states. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    CHUNK_TYPE_VIDEO_COMPRESSED,
    CHUNK_TYPE_VIDEO_UNCOMPRESSED,
    CHUNK_TYPE_AUDIO,
  })
  private @interface ChunkType {}

  private static final int INITIAL_INDEX_SIZE = 512;
  private static final int CHUNK_TYPE_VIDEO_COMPRESSED = ('d' << 16) | ('c' << 24);
  private static final int CHUNK_TYPE_VIDEO_UNCOMPRESSED = ('d' << 16) | ('b' << 24);
  private static final int CHUNK_TYPE_AUDIO = ('w' << 16) | ('b' << 24);

  private final AviStreamHeaderChunk streamHeaderChunk;
  private final TrackOutput trackOutput;

  /** The chunk id fourCC (example: `01wb`), as defined in the index and the movi. */
  private final int chunkId;

  /** Secondary chunk id. Bad muxers sometimes use an uncompressed video id (db) for key frames. */
  private final int alternativeChunkId;

  private final long durationUs;

  private int chunkCount;
  private int currentChunkSize;
  private int bytesRemainingInCurrentChunk;

  /** Number of chunks as calculated by the index. */
  private int currentChunkIndex;

  private int indexChunkCount;
  private int indexSize;
  private long firstIndexChunkOffset;
  private long[] keyFrameOffsets;
  private int[] keyFrameIndices;

  public ChunkReader(int id, AviStreamHeaderChunk streamHeaderChunk, TrackOutput trackOutput) {
    this.streamHeaderChunk = streamHeaderChunk;
    @C.TrackType int trackType = streamHeaderChunk.getTrackType();
    checkArgument(trackType == TRACK_TYPE_AUDIO || trackType == TRACK_TYPE_VIDEO);
    @ChunkType
    int chunkType = trackType == TRACK_TYPE_VIDEO ? CHUNK_TYPE_VIDEO_COMPRESSED : CHUNK_TYPE_AUDIO;
    chunkId = getChunkIdFourCc(id, chunkType);
    durationUs = streamHeaderChunk.getDurationUs();
    this.trackOutput = trackOutput;
    alternativeChunkId =
        trackType == TRACK_TYPE_VIDEO ? getChunkIdFourCc(id, CHUNK_TYPE_VIDEO_UNCOMPRESSED) : -1;
    firstIndexChunkOffset = C.INDEX_UNSET;
    keyFrameOffsets = new long[INITIAL_INDEX_SIZE];
    keyFrameIndices = new int[INITIAL_INDEX_SIZE];
    chunkCount = streamHeaderChunk.length;
  }

  public void appendIndexChunk(long offset, boolean isKeyFrame) {
    if (firstIndexChunkOffset == C.INDEX_UNSET) {
      firstIndexChunkOffset = offset;
    }
    if (isKeyFrame) {
      if (indexSize == keyFrameIndices.length) {
        keyFrameOffsets = Arrays.copyOf(keyFrameOffsets, keyFrameOffsets.length * 3 / 2);
        keyFrameIndices = Arrays.copyOf(keyFrameIndices, keyFrameIndices.length * 3 / 2);
      }
      keyFrameOffsets[indexSize] = offset;
      keyFrameIndices[indexSize] = indexChunkCount;
      indexSize++;
    }
    indexChunkCount++;
  }

  public void advanceCurrentChunk() {
    currentChunkIndex++;
  }

  public long getCurrentChunkTimestampUs() {
    return getChunkTimestampUs(currentChunkIndex);
  }

  public long getFrameDurationUs() {
    return getChunkTimestampUs(/* chunkIndex= */ 1);
  }

  public void commitIndex() {
    keyFrameOffsets = Arrays.copyOf(keyFrameOffsets, indexSize);
    keyFrameIndices = Arrays.copyOf(keyFrameIndices, indexSize);
    if (isAudio() && streamHeaderChunk.sampleSize != 0 && indexSize > 0) {
      // In some files the AVI stream header chunk for audio has the number of bytes of audio in
      // dwLength instead of the number of chunks. Overwrite the chunk size to use the size of the
      // index, which should match the number of chunks because we only support formats where every
      // audio sample is a sync sample, and every sync sample should be in the index.
      chunkCount = indexSize;
    }
  }

  public boolean handlesChunkId(int chunkId) {
    return this.chunkId == chunkId || alternativeChunkId == chunkId;
  }

  public boolean isCurrentFrameAKeyFrame() {
    return Arrays.binarySearch(keyFrameIndices, currentChunkIndex) >= 0;
  }

  public boolean isVideo() {
    return (chunkId & CHUNK_TYPE_VIDEO_COMPRESSED) == CHUNK_TYPE_VIDEO_COMPRESSED;
  }

  public boolean isAudio() {
    return (chunkId & CHUNK_TYPE_AUDIO) == CHUNK_TYPE_AUDIO;
  }

  /** Prepares for parsing a chunk with the given {@code size}. */
  public void onChunkStart(int size) {
    currentChunkSize = size;
    bytesRemainingInCurrentChunk = size;
  }

  /**
   * Provides data associated to the current chunk and returns whether the full chunk has been
   * parsed.
   */
  public boolean onChunkData(ExtractorInput input) throws IOException {
    bytesRemainingInCurrentChunk -=
        trackOutput.sampleData(input, bytesRemainingInCurrentChunk, false);
    boolean done = bytesRemainingInCurrentChunk == 0;
    if (done) {
      if (currentChunkSize > 0) {
        trackOutput.sampleMetadata(
            getCurrentChunkTimestampUs(),
            (isCurrentFrameAKeyFrame() ? C.BUFFER_FLAG_KEY_FRAME : 0),
            currentChunkSize,
            0,
            null);
      }
      advanceCurrentChunk();
    }
    return done;
  }

  public void seekToPosition(long position) {
    if (indexSize == 0) {
      currentChunkIndex = 0;
    } else {
      int index =
          Util.binarySearchFloor(
              keyFrameOffsets, position, /* inclusive= */ true, /* stayInBounds= */ true);
      currentChunkIndex = keyFrameIndices[index];
    }
  }

  public SeekMap.SeekPoints getSeekPoints(long timeUs) {
    if (indexSize == 0) {
      // Return the offset of the first chunk as there are no keyframes in the index.
      return new SeekMap.SeekPoints(
          new SeekPoint(/* timeUs= */ 0, /* position= */ firstIndexChunkOffset));
    }
    int targetFrameIndex = (int) (timeUs / getFrameDurationUs());
    int keyFrameIndex =
        Util.binarySearchFloor(
            keyFrameIndices, targetFrameIndex, /* inclusive= */ true, /* stayInBounds= */ true);
    if (keyFrameIndices[keyFrameIndex] == targetFrameIndex) {
      return new SeekMap.SeekPoints(getSeekPoint(keyFrameIndex));
    }
    // The target frame is not a key frame, we look for the two closest ones.
    SeekPoint precedingKeyFrameSeekPoint = getSeekPoint(keyFrameIndex);
    if (keyFrameIndex + 1 < keyFrameOffsets.length) {
      return new SeekMap.SeekPoints(precedingKeyFrameSeekPoint, getSeekPoint(keyFrameIndex + 1));
    } else {
      return new SeekMap.SeekPoints(precedingKeyFrameSeekPoint);
    }
  }

  private long getChunkTimestampUs(int chunkIndex) {
    return durationUs * chunkIndex / chunkCount;
  }

  private SeekPoint getSeekPoint(int keyFrameIndex) {
    return new SeekPoint(
        keyFrameIndices[keyFrameIndex] * getFrameDurationUs(), keyFrameOffsets[keyFrameIndex]);
  }

  private static int getChunkIdFourCc(int streamId, @ChunkType int chunkType) {
    int tens = streamId / 10;
    int ones = streamId % 10;
    return (('0' + ones) << 8) | ('0' + tens) | chunkType;
  }
}
