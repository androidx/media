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

import android.util.Log;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Reads chunks holding sample data. */
/* package */ class ChunkReader {

  public static final int CHUNK_TYPE_VIDEO = 'd' << 16;
  public static final int CHUNK_TYPE_AUDIO = 'w' << 16;
  private static final int CHUNK_TYPE_MASK = 0xff0000;
  private static final int CHUNK_ID_MASK = 0xffffff;

  protected final TrackOutput trackOutput;

  /** The chunk id fourCC with the final char stripped (example: `01w`), as defined in the index and the movi. */
  private final int chunkId;

  /**
   * Map of file offsets to chunk indices for KeyFrames in this stream
   */
  private final LongIntMap keyFrameMap = new LongIntMap();

  /**
   * Map of file offsets to chunk indices for KeyFrames in other streams
   */
  private final LongIntMap foreignKeyFrameMap = new LongIntMap();

  private final long durationUs;

  private int currentChunkSize;
  private int bytesRemainingInCurrentChunk;

  /** Number of chunks as calculated by the index */
  private int currentChunkIndex;

  private int indexChunkCount;

  public ChunkReader(
      int id,
      @C.TrackType int trackType,
      long durationUs,
      TrackOutput trackOutput) {
    Assertions.checkArgument(trackType == C.TRACK_TYPE_AUDIO || trackType == C.TRACK_TYPE_VIDEO);
    this.durationUs = durationUs;
    this.trackOutput = trackOutput;
    int chunkType =
        trackType == C.TRACK_TYPE_VIDEO ? CHUNK_TYPE_VIDEO : CHUNK_TYPE_AUDIO;
    chunkId = getChunkIdFourCc(id, chunkType);
  }

  public void appendKeyFrame(long offset) {
    keyFrameMap.put(offset, indexChunkCount);
  }

  public void appendForeignKeyFrame(long offset) {
    foreignKeyFrameMap.put(offset, indexChunkCount);
  }

  public void advanceCurrentChunk() {
    currentChunkIndex++;
  }

  public long getCurrentChunkTimestampUs() {
    return getChunkTimestampUs(currentChunkIndex);
  }

  public long getFrameDurationUs() {
    return getChunkTimestampUs(1);
  }

  public void incrementIndexChunkCount() {
    indexChunkCount++;
  }

  public void compactIndices() {
    keyFrameMap.compact();
    foreignKeyFrameMap.compact();
  }

  public boolean handlesChunkId(int chunkId) {
    return (chunkId & CHUNK_ID_MASK) == this.chunkId;
  }

  public boolean isCurrentFrameAKeyFrame() {
    return getChunkType() == CHUNK_TYPE_AUDIO ||
        keyFrameMap.indexOf(currentChunkIndex) >= 0;
  }

  public int getChunkType() {
    return chunkId & CHUNK_TYPE_MASK;
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
        final ByteBuffer byteBuffer = ByteBuffer.allocate(4);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        byteBuffer.putInt(chunkId);
        Log.d("Test",new String(byteBuffer.array(),0,3) + " " + String.format("%.3f", getCurrentChunkTimestampUs() / 1_000_000.0) + " " + isCurrentFrameAKeyFrame());
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
    if (position == 0L) {
      currentChunkIndex = 0;
    } else {
      int index = keyFrameMap.indexOf(position);
      if (index >= 0) {
        currentChunkIndex = keyFrameMap.getInt(index);
      } else {
        index = foreignKeyFrameMap.indexOf(position);
        if (index >= 0) {
          currentChunkIndex = foreignKeyFrameMap.getInt(index);
        }
      }
    }
  }

  @Nullable
  public SeekMap.SeekPoints getSeekPoints(long timeUs) {
    if (keyFrameMap.size == 0) {
      return null;
    }
    int targetFrameIndex = (int) (timeUs / getFrameDurationUs());
    int keyFrameIndex = keyFrameMap.getFloorIndex(targetFrameIndex);
    if (keyFrameMap.getInt(keyFrameIndex) == targetFrameIndex) {
      return new SeekMap.SeekPoints(getSeekPoint(keyFrameIndex));
    }
    // The target frame is not a key frame, we look for the two closest ones.
    SeekPoint precedingKeyFrameSeekPoint = getSeekPoint(keyFrameIndex);
    if (keyFrameIndex + 1 < keyFrameMap.size) {
      return new SeekMap.SeekPoints(precedingKeyFrameSeekPoint, getSeekPoint(keyFrameIndex + 1));
    } else {
      return new SeekMap.SeekPoints(precedingKeyFrameSeekPoint);
    }
  }

  private long getChunkTimestampUs(int chunkIndex) {
    return durationUs * chunkIndex / indexChunkCount;
  }

  private SeekPoint getSeekPoint(int keyFrameIndex) {
    return new SeekPoint(keyFrameMap.getInt(keyFrameIndex) * getFrameDurationUs(),
        keyFrameMap.getLong(keyFrameIndex));
  }

  private static int getChunkIdFourCc(int streamId, int chunkType) {
    int tens = streamId / 10;
    int ones = streamId % 10;
    return (('0' + ones) << 8) | ('0' + tens) | chunkType;
  }

  private static class LongIntMap {
    private static final int INITIAL_SIZE = 512;

    private long[] longs = new long[INITIAL_SIZE];
    private int[] ints = new int[INITIAL_SIZE];
    private int size;

    public void put(long l, int i) {
      if (size == longs.length) {
        longs = Arrays.copyOf(longs, size * 3 / 2);
        ints = Arrays.copyOf(ints, size * 3 / 2);
      }
      longs[size] = l;
      ints[size] = i;
      size++;
    }

    public int indexOf(int i) {
      return Arrays.binarySearch(ints, i);
    }

    public int indexOf(long l) {
      return Arrays.binarySearch(longs, l);
    }

    public int getInt(int index) {
      return ints[index];
    }

    public long getLong(int index) {
      return longs[index];
    }

    public int getFloorIndex(int i) {
      return Util.binarySearchFloor(ints, i, /* inclusive= */ true, /* stayInBounds= */ true);
    }

    public void compact() {
      longs = Arrays.copyOf(longs, size);
      ints = Arrays.copyOf(ints, size);
    }
  }
}
