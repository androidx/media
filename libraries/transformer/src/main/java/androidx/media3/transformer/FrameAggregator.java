/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.transformer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Consumer;
import androidx.media3.effect.GlTextureFrame;
import com.google.common.collect.ImmutableList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

// TODO: b/430250432 - This is a placeholder implementation, revisit the aggregation and flushing
//  logic to make it more robust.
/**
 * Combines multiple sequences of {@link GlTextureFrame}s into one sequence of {@link
 * ImmutableList<GlTextureFrame>}.
 */
/* package */ class FrameAggregator {
  private final Consumer<List<GlTextureFrame>> downstreamConsumer;
  private final SparseArray<Queue<GlTextureFrame>> inputFrames;
  private final int numSequences;
  private long lastQueuedPresentationTimeUs;

  /**
   * Creates a new {@link FrameAggregator}.
   *
   * @param downstreamConsumer Receives the aggregated {@linkplain ImmutableList<GlTextureFrame>
   *     frames}.
   */
  public FrameAggregator(int numSequences, Consumer<List<GlTextureFrame>> downstreamConsumer) {
    this.numSequences = numSequences;
    this.downstreamConsumer = downstreamConsumer;
    inputFrames = new SparseArray<>();
    for (int i = 0; i < numSequences; i++) {
      inputFrames.put(i, new ArrayDeque<>());
    }
  }

  /**
   * Queues a {@link GlTextureFrame} at the given sequence.
   *
   * <p>Once called, the caller must not modify the {@link GlTextureFrame}.
   *
   * @param frame The {@link GlTextureFrame} to queue.
   * @param sequenceIndex The index of the sequence the queued {@link GlTextureFrame} is from.
   * @throws IllegalArgumentException If {@code sequenceIndex} is non-positive or greater than or
   *     equal to {@link #numSequences}.
   */
  public void queueFrame(GlTextureFrame frame, int sequenceIndex) {
    checkArgument(sequenceIndex >= 0);
    checkArgument(sequenceIndex < numSequences);
    if (sequenceIndex == 0) {
      // If a primary frame arrived out of order it indicates a seek backwards, flush all frames to
      // ensure aggregation continues to work correctly.
      if (frame.presentationTimeUs < lastQueuedPresentationTimeUs) {
        releaseAllFrames();
      }
      lastQueuedPresentationTimeUs = frame.presentationTimeUs;
    }
    inputFrames.get(sequenceIndex).add(frame);
    maybeAggregate();
  }

  /**
   * {@linkplain GlTextureFrame#release() Releases } all frames that have not been sent downstream.
   */
  // TODO: b/430250432 - Ensure this does not throw away frames in the case where a new decoded
  //   frame is not forwarded from the renderer on a discontinuity.
  public void releaseAllFrames() {
    for (int i = 0; i < inputFrames.size(); i++) {
      @Nullable GlTextureFrame nextFrame;
      while ((nextFrame = inputFrames.get(i).poll()) != null) {
        nextFrame.release();
      }
    }
  }

  /**
   * Selects the next frame greater than or equal to the current frame from the primary input stream
   * from each secondary stream.
   */
  // TODO: Handle when sequences end at different times.
  private void maybeAggregate() {
    @Nullable GlTextureFrame nextPrimaryFrame = checkNotNull(inputFrames.get(0)).peek();
    if (nextPrimaryFrame == null) {
      return;
    }
    ImmutableList.Builder<GlTextureFrame> outputFramesBuilder = new ImmutableList.Builder<>();
    outputFramesBuilder.add(nextPrimaryFrame);
    for (int i = 1; i < inputFrames.size(); i++) {
      Queue<GlTextureFrame> nextInputFrameQueue = inputFrames.get(i);
      @Nullable GlTextureFrame nextFrame = nextInputFrameQueue.peek();
      while (nextFrame != null) {
        // Release all frames from the secondary sequence that arrived before the primary sequence
        // frame.
        if (nextFrame.presentationTimeUs < nextPrimaryFrame.presentationTimeUs) {
          nextFrame.release();
          nextInputFrameQueue.poll();
          nextFrame = nextInputFrameQueue.peek();
        } else {
          break;
        }
      }
      if (nextFrame == null) {
        return;
      }
      outputFramesBuilder.add(nextFrame);
    }
    downstreamConsumer.accept(outputFramesBuilder.build());
    // TODO: b/430250432 - Allow reusing frames from secondary sequences to handle different frame
    //  rates.
    for (int i = 0; i < numSequences; i++) {
      checkNotNull(inputFrames.get(i)).poll();
    }
  }
}
