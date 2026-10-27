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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import androidx.media3.common.C;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.source.SampleQueue;
import androidx.media3.exoplayer.source.chunk.ChunkExtractor.TrackOutputProvider;
import androidx.media3.extractor.DiscardingTrackOutput;
import androidx.media3.extractor.TrackOutput;
import com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.jspecify.annotations.Nullable;

/**
 * A {@link TrackOutputProvider} that provides {@link TrackOutput TrackOutputs} based on a
 * predefined mapping from track type to output.
 *
 * <p>When multiple sample queues of the same {@link C.TrackType} are provided, each distinct track
 * {@code id} of that type is bound to its own sample queue in the order the IDs are first
 * requested.
 */
@UnstableApi
public final class BaseMediaChunkOutput implements TrackOutputProvider {

  private static final String TAG = "BaseMediaChunkOutput";

  private final ImmutableList<SampleQueueHolder> sampleQueueHolders;

  /**
   * @param trackTypes The track types of the individual track outputs.
   * @param sampleQueues The individual sample queues.
   */
  public BaseMediaChunkOutput(int[] trackTypes, SampleQueue[] sampleQueues) {
    checkArgument(trackTypes.length == sampleQueues.length);
    ImmutableList.Builder<SampleQueueHolder> sampleQueueHoldersBuilder =
        ImmutableList.builderWithExpectedSize(sampleQueues.length);
    for (int i = 0; i < sampleQueues.length; i++) {
      sampleQueueHoldersBuilder.add(new SampleQueueHolder(trackTypes[i], sampleQueues[i]));
    }
    this.sampleQueueHolders = sampleQueueHoldersBuilder.build();
  }

  @Override
  public TrackOutput track(int id, @C.TrackType int type) {
    @Nullable SampleQueueHolder firstMatchingHolder = null;
    for (int i = 0; i < sampleQueueHolders.size(); i++) {
      SampleQueueHolder holder = sampleQueueHolders.get(i);
      if (holder.trackType != type) {
        continue;
      }
      if (firstMatchingHolder == null) {
        firstMatchingHolder = holder;
      }
      if (holder.isBoundTo(id)) {
        return holder.sampleQueue;
      }
      if (!holder.isBound()) {
        holder.bind(id);
        return holder.sampleQueue;
      }
    }
    if (firstMatchingHolder != null) {
      // More outputs of this type than queues. Share the first matching queue.
      return firstMatchingHolder.sampleQueue;
    }
    Log.e(TAG, "Unmatched track of type: " + type);
    return new DiscardingTrackOutput();
  }

  /** Returns the current absolute write indices of the individual sample queues. */
  public int[] getWriteIndices() {
    int[] writeIndices = new int[sampleQueueHolders.size()];
    for (int i = 0; i < sampleQueueHolders.size(); i++) {
      writeIndices[i] = sampleQueueHolders.get(i).sampleQueue.getWriteIndex();
    }
    return writeIndices;
  }

  /**
   * Sets an offset that will be added to the timestamps (and sub-sample timestamps) of samples
   * subsequently written to the sample queues.
   */
  public void setSampleOffsetUs(long sampleOffsetUs) {
    for (int i = 0; i < sampleQueueHolders.size(); i++) {
      sampleQueueHolders.get(i).sampleQueue.setSampleOffsetUs(sampleOffsetUs);
    }
  }

  private static final class SampleQueueHolder {

    public final @C.TrackType int trackType;
    public final SampleQueue sampleQueue;

    private @MonotonicNonNull Integer trackId;

    public SampleQueueHolder(@C.TrackType int trackType, SampleQueue sampleQueue) {
      this.trackType = trackType;
      this.sampleQueue = sampleQueue;
    }

    public boolean isBound() {
      return trackId != null;
    }

    public boolean isBoundTo(int trackId) {
      return this.trackId != null && this.trackId == trackId;
    }

    public void bind(int trackId) {
      checkState(this.trackId == null);
      this.trackId = trackId;
    }
  }
}
