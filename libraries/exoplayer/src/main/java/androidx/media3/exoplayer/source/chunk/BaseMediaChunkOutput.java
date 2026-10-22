/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.source.chunk;

import android.util.SparseIntArray;
import androidx.media3.common.C;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.source.SampleQueue;
import androidx.media3.exoplayer.source.chunk.ChunkExtractor.TrackOutputProvider;
import androidx.media3.extractor.DiscardingTrackOutput;
import androidx.media3.extractor.TrackOutput;

/**
 * A {@link TrackOutputProvider} that provides {@link TrackOutput TrackOutputs} based on a
 * predefined mapping from track type to output.
 *
 * <p>If several text outputs are requested (for example multiple embedded CEA-608 channels), each
 * distinct {@code id} is bound to its own text sample queue, in the order the ids are first
 * requested. Outputs of any other type are always bound to the first sample queue of that type.
 */
@UnstableApi
public final class BaseMediaChunkOutput implements TrackOutputProvider {

  private static final String TAG = "BaseMediaChunkOutput";

  private final @C.TrackType int[] trackTypes;
  private final SampleQueue[] sampleQueues;
  private final SparseIntArray queueIndexById;
  private final boolean[] queueAssigned;

  /**
   * @param trackTypes The track types of the individual track outputs.
   * @param sampleQueues The individual sample queues.
   */
  public BaseMediaChunkOutput(int[] trackTypes, SampleQueue[] sampleQueues) {
    this.trackTypes = trackTypes;
    this.sampleQueues = sampleQueues;
    queueIndexById = new SparseIntArray();
    queueAssigned = new boolean[sampleQueues.length];
  }

  @Override
  public TrackOutput track(int id, @C.TrackType int type) {
    int cachedIndex = queueIndexById.get(id, C.INDEX_UNSET);
    if (cachedIndex != C.INDEX_UNSET && trackTypes[cachedIndex] == type) {
      return sampleQueues[cachedIndex];
    }
    int firstMatchingIndex = C.INDEX_UNSET;
    for (int i = 0; i < trackTypes.length; i++) {
      if (trackTypes[i] != type) {
        continue;
      }
      if (firstMatchingIndex == C.INDEX_UNSET) {
        firstMatchingIndex = i;
      }
      if (!queueAssigned[i]) {
        queueAssigned[i] = true;
        queueIndexById.put(id, i);
        return sampleQueues[i];
      }
    }
    if (firstMatchingIndex != C.INDEX_UNSET) {
      // More outputs of this type than queues. Share the first matching queue.
      queueIndexById.put(id, firstMatchingIndex);
      return sampleQueues[firstMatchingIndex];
    }
    Log.e(TAG, "Unmatched track of type: " + type);
    return new DiscardingTrackOutput();
  }

  /** Returns the current absolute write indices of the individual sample queues. */
  public int[] getWriteIndices() {
    int[] writeIndices = new int[sampleQueues.length];
    for (int i = 0; i < sampleQueues.length; i++) {
      writeIndices[i] = sampleQueues[i].getWriteIndex();
    }
    return writeIndices;
  }

  /**
   * Sets an offset that will be added to the timestamps (and sub-sample timestamps) of samples
   * subsequently written to the sample queues.
   */
  public void setSampleOffsetUs(long sampleOffsetUs) {
    for (SampleQueue sampleQueue : sampleQueues) {
      sampleQueue.setSampleOffsetUs(sampleOffsetUs);
    }
  }
}
