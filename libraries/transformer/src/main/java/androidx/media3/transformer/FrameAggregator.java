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

import androidx.annotation.Nullable;
import androidx.media3.common.util.Consumer;
import androidx.media3.effect.GlTextureFrame;
import com.google.common.collect.ImmutableList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

// TODO: b/449956936 - This is a placeholder implementation, revisit the aggregation and flushing
//  logic to make it more robust.
/**
 * Combines multiple sequences of {@link GlTextureFrame}s into one sequence of {@link
 * ImmutableList<GlTextureFrame>}.
 */
/* package */ class FrameAggregator {
  private final Consumer<List<GlTextureFrame>> downstreamConsumer;
  private final List<FrameQueue> inputFrameQueues;
  private final int numSequences;
  private boolean isEnded;

  /**
   * Creates a new {@link FrameAggregator}.
   *
   * @param downstreamConsumer Receives the aggregated {@linkplain ImmutableList<GlTextureFrame>
   *     frames}.
   */
  public FrameAggregator(int numSequences, Consumer<List<GlTextureFrame>> downstreamConsumer) {
    this.numSequences = numSequences;
    this.downstreamConsumer = downstreamConsumer;
    inputFrameQueues = new ArrayList<>();
    for (int i = 0; i < numSequences; i++) {
      inputFrameQueues.add(new FrameQueue());
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
    inputFrameQueues.get(sequenceIndex).frames.add(frame);
    maybeAggregate();
  }

  /**
   * Notifies the {@code FrameAggregator} that the given sequence has ended.
   *
   * <p>Once called, {@link #flush} the sequenceIndex to reset the ended state.
   *
   * @param sequenceIndex The index of the sequence that has ended.
   * @throws IllegalArgumentException If {@code sequenceIndex} is non-positive or greater than or
   *     equal to {@link #numSequences}.
   */
  public void queueEndOfStream(int sequenceIndex) {
    checkArgument(sequenceIndex >= 0);
    checkArgument(sequenceIndex < numSequences);
    inputFrameQueues.get(sequenceIndex).isEnded = true;
    maybeAggregate();
  }

  /**
   * {@linkplain GlTextureFrame#release() Releases } all frames that have not been sent downstream.
   */
  // TODO: b/449956936 - Ensure this does not throw away frames in the case where a new decoded
  //   frame is not forwarded from the renderer on a discontinuity.
  public void releaseAllFrames() {
    for (int i = 0; i < inputFrameQueues.size(); i++) {
      @Nullable GlTextureFrame nextFrame;
      while ((nextFrame = inputFrameQueues.get(i).frames.poll()) != null) {
        nextFrame.release();
      }
    }
  }

  /**
   * Removes all frames from the given sequence.
   *
   * @param sequenceIndex The index of the sequence to flush.
   * @throws IllegalArgumentException If {@code sequenceIndex} is non-positive or greater than or
   *     equal to {@link #numSequences}.
   */
  public void flush(int sequenceIndex) {
    checkArgument(sequenceIndex >= 0);
    checkArgument(sequenceIndex < numSequences);
    @Nullable GlTextureFrame nextFrame;
    while ((nextFrame = inputFrameQueues.get(sequenceIndex).frames.poll()) != null) {
      nextFrame.release();
    }
    if (sequenceIndex == 0) {
      isEnded = false;
    }
    inputFrameQueues.get(sequenceIndex).isEnded = false;
  }

  /**
   * Selects the next frame greater than or equal to the current frame from the primary input stream
   * from each secondary stream.
   */
  private void maybeAggregate() {
    if (isEnded) {
      return;
    }
    @Nullable GlTextureFrame nextPrimaryFrame = checkNotNull(inputFrameQueues.get(0).frames).peek();
    if (nextPrimaryFrame == null) {
      // When the primary sequence ends, send an EOS frame downstream.
      if (inputFrameQueues.get(0).isEnded) {
        downstreamConsumer.accept(ImmutableList.of(GlTextureFrame.END_OF_STREAM_FRAME));
        isEnded = true;
      }
      return;
    }
    ImmutableList.Builder<GlTextureFrame> outputFramesBuilder = new ImmutableList.Builder<>();
    outputFramesBuilder.add(nextPrimaryFrame);
    for (int i = 1; i < inputFrameQueues.size(); i++) {
      FrameQueue frameQueue = inputFrameQueues.get(i);
      @Nullable GlTextureFrame nextFrame = frameQueue.frames.peek();
      while (nextFrame != null) {
        // Release all frames from the secondary sequence that arrived before the primary sequence
        // frame.
        if (nextFrame.presentationTimeUs < nextPrimaryFrame.presentationTimeUs) {
          nextFrame.release();
          frameQueue.frames.poll();
          nextFrame = frameQueue.frames.peek();
        } else {
          break;
        }
      }
      if (nextFrame == null) {
        if (frameQueue.isEnded) {
          continue;
        }
        return;
      }
      outputFramesBuilder.add(nextFrame);
    }
    downstreamConsumer.accept(outputFramesBuilder.build());
    // TODO: b/449956936 - Allow reusing frames from secondary sequences to handle different frame
    //  rates.
    for (int i = 0; i < numSequences; i++) {
      checkNotNull(inputFrameQueues.get(i).frames).poll();
    }
  }

  /** A helper class representing a {@link Queue<GlTextureFrame>} that can end. */
  private static class FrameQueue {
    final Queue<GlTextureFrame> frames;
    boolean isEnded;

    FrameQueue() {
      frames = new ArrayDeque<>();
    }
  }
}
