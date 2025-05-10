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
package androidx.media3.extractor;

import androidx.media3.common.util.UnstableApi;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A utility class for merging multiple {@link ChunkIndex} instances into a single {@link
 * ChunkIndex}.
 *
 * <p>This is useful in scenarios where media is split across multiple segments or sources, and a
 * unified index is needed for seeking or playback.
 */
@UnstableApi
public final class ChunkIndexMerger {

  /** Start time in microseconds to {@link ChunkIndex} mapping. Maintains insertion order. */
  private final Map<Long, ChunkIndex> chunkMap;

  /** Creates an instance. */
  public ChunkIndexMerger() {
    this.chunkMap = new LinkedHashMap<>();
  }

  /**
   * Adds a {@link ChunkIndex} to be merged.
   *
   * <p>Chunk indices with duplicate starting timestamps are ignored to avoid redundant data.
   *
   * @param chunk The {@link ChunkIndex} to add.
   */
  public void add(ChunkIndex chunk) {
    if (chunk.timesUs.length > 0 && !chunkMap.containsKey(chunk.timesUs[0])) {
      chunkMap.put(chunk.timesUs[0], chunk);
    }
  }

  /** Returns a single {@link ChunkIndex} that merges all added chunk indices. */
  public ChunkIndex merge() {
    List<int[]> sizesList = new ArrayList<>();
    List<long[]> offsetsList = new ArrayList<>();
    List<long[]> durationsList = new ArrayList<>();
    List<long[]> timesList = new ArrayList<>();

    for (ChunkIndex chunk : chunkMap.values()) {
      sizesList.add(chunk.sizes);
      offsetsList.add(chunk.offsets);
      durationsList.add(chunk.durationsUs);
      timesList.add(chunk.timesUs);
    }

    return new ChunkIndex(
        Ints.concat(sizesList.toArray(new int[sizesList.size()][])),
        Longs.concat(offsetsList.toArray(new long[offsetsList.size()][])),
        Longs.concat(durationsList.toArray(new long[durationsList.size()][])),
        Longs.concat(timesList.toArray(new long[timesList.size()][])));
  }

  /** Clears all added chunk indices and internal state. */
  public void clear() {
    chunkMap.clear();
  }

  /** Returns the number of chunk indices added so far. */
  public int size() {
    return chunkMap.size();
  }
}
