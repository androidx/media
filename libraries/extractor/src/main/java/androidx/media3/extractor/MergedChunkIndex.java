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
import androidx.media3.common.util.Util;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A utility class for merging multiple {@link ChunkIndex} instances into a single {@link
 * ChunkIndex}.
 *
 * <p>This is useful in scenarios where media is split across multiple segments or sources, and a
 * unified index is needed for seeking or playback.
 */
@UnstableApi
public class MergedChunkIndex {

  /** The individual {@link ChunkIndex} entries being merged. */
  private final List<ChunkIndex> chunks;

  /** The set of first start times seen so far across added {@link ChunkIndex} instances. */
  private final Set<Long> uniqueStartTimes;

  /** Creates an instance. */
  public MergedChunkIndex() {
    this.chunks = new ArrayList<>();
    this.uniqueStartTimes = new HashSet<>();
  }

  /**
   * Adds a {@link ChunkIndex} to be merged.
   *
   * <p>Chunk indices with duplicate starting timestamps are ignored to avoid redundant data.
   *
   * @param chunk The {@link ChunkIndex} to add.
   */
  public void merge(ChunkIndex chunk) {
    if (chunk.timesUs.length > 0 && !uniqueStartTimes.contains(chunk.timesUs[0])) {
      chunks.add(chunk);
      uniqueStartTimes.add(chunk.timesUs[0]);
    }
  }

  /** Returns a single {@link ChunkIndex} that combines all added chunk indices. */
  public ChunkIndex toChunkIndex() {
    List<int[]> sizesList = new ArrayList<>();
    List<long[]> offsetsList = new ArrayList<>();
    List<long[]> durationsList = new ArrayList<>();
    List<long[]> timesList = new ArrayList<>();

    for (ChunkIndex chunk : chunks) {
      sizesList.add(chunk.sizes);
      offsetsList.add(chunk.offsets);
      durationsList.add(chunk.durationsUs);
      timesList.add(chunk.timesUs);
    }

    return new ChunkIndex(
        Util.nullSafeIntArraysConcatenation(sizesList),
        Util.nullSafeLongArraysConcatenation(offsetsList),
        Util.nullSafeLongArraysConcatenation(durationsList),
        Util.nullSafeLongArraysConcatenation(timesList));
  }

  /** Clears all added chunk indices and internal state. */
  public void clear() {
    chunks.clear();
    uniqueStartTimes.clear();
  }

  /** Returns the number of chunk indices added so far. */
  public int size() {
    return chunks.size();
  }
}
