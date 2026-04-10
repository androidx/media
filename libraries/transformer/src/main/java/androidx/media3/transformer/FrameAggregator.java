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
import static com.google.common.base.Preconditions.checkState;

import androidx.annotation.Nullable;
import androidx.media3.common.util.Consumer;
import androidx.media3.effect.HardwareBufferFrame;
import com.google.common.collect.ImmutableList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Combines multiple sequences of {@link HardwareBufferFrame}s into one sequence of {@link
 * ImmutableList<HardwareBufferFrame>}.
 */
/* package */ class FrameAggregator {
  private final Consumer<ImmutableList<HardwareBufferFrame>> downstreamConsumer;
  private final Consumer<Integer> onFlush;
  private final List<FrameQueue> inputFrameQueues;
  private final int numSequences;
  private boolean isEnded;

  /**
   * Creates a new {@link FrameAggregator}.
   *
   * @param downstreamConsumer Receives the aggregated {@linkplain
   *     ImmutableList<HardwareBufferFrame> frames}.
   * @param onFlush Callback triggered when {@link #flush(int)} is called.
   */
  public FrameAggregator(
      int numSequences,
      Consumer<ImmutableList<HardwareBufferFrame>> downstreamConsumer,
      Consumer<Integer> onFlush) {
    this.numSequences = numSequences;
    this.downstreamConsumer = downstreamConsumer;
    this.onFlush = onFlush;
    inputFrameQueues = new ArrayList<>();
    for (int i = 0; i < numSequences; i++) {
      inputFrameQueues.add(new FrameQueue());
    }
  }

  /**
   * Registers the given {@code sequenceIndex} with the {@link FrameAggregator}, and indicates
   * whether it should be considered when aggregating frames.
   *
   * <p>All sequences must be registered before frames are queued.
   */
  public void registerSequence(int sequenceIndex, boolean shouldAggregate) {
    checkArgument(sequenceIndex >= 0);
    checkArgument(sequenceIndex < numSequences);
    inputFrameQueues.get(sequenceIndex).initialize(shouldAggregate);
  }

  /**
   * Queues a {@link HardwareBufferFrame} at the given sequence.
   *
   * <p>Once called, the caller must not modify the {@link HardwareBufferFrame}.
   *
   * <p>If the aggregator {@link #isEnded}, the frame will be released immediately.
   *
   * @param frame The {@link HardwareBufferFrame} to queue.
   * @param sequenceIndex The index of the sequence the queued {@link HardwareBufferFrame} is from.
   * @throws IllegalArgumentException If {@code sequenceIndex} is non-positive or greater than or
   *     equal to {@link #numSequences}.
   */
  public void queueFrame(HardwareBufferFrame frame, int sequenceIndex) {
    checkArgument(sequenceIndex >= 0);
    checkArgument(sequenceIndex < numSequences);
    checkState(inputFrameQueues.get(sequenceIndex).isRegistered);
    // Release frames immediately if the primary sequence has already ended
    if (isEnded) {
      frame.release(/* releaseFence= */ null);
      return;
    }
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
    checkState(inputFrameQueues.get(sequenceIndex).isRegistered);
    inputFrameQueues.get(sequenceIndex).setIsEnded(/* isEnded= */ true);
    maybeAggregate();
  }

  /**
   * {@linkplain HardwareBufferFrame#release Releases } all frames that have not been sent
   * downstream.
   */
  // TODO: b/449956936 - Ensure this does not throw away frames in the case where a new decoded
  //   frame is not forwarded from the renderer on a discontinuity.
  public void releaseAllFrames() {
    for (int i = 0; i < inputFrameQueues.size(); i++) {
      @Nullable HardwareBufferFrame nextFrame;
      while ((nextFrame = inputFrameQueues.get(i).frames.poll()) != null) {
        nextFrame.release(/* releaseFence= */ null);
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
    checkState(inputFrameQueues.get(sequenceIndex).isRegistered);
    @Nullable HardwareBufferFrame nextFrame;
    while ((nextFrame = inputFrameQueues.get(sequenceIndex).frames.poll()) != null) {
      nextFrame.release(/* releaseFence= */ null);
    }
    if (sequenceIndex == 0) {
      isEnded = false;
    }
    inputFrameQueues.get(sequenceIndex).setIsEnded(/* isEnded= */ false);
    onFlush.accept(sequenceIndex);
  }

  /**
   * Selects the next frame greater than or equal to the current frame from the primary input stream
   * from each secondary stream.
   */
  private void maybeAggregate() {
    while (!isEnded) {
      // TODO: b/496904840 - This assumes the primary sequence can be aggregated against. Update it
      //  to when this is not the case.
      @Nullable
      HardwareBufferFrame nextPrimaryFrame = checkNotNull(inputFrameQueues.get(0).frames).peek();
      if (nextPrimaryFrame == null) {
        handlePrimaryEndOfStream();
        return;
      }

      ImmutableList<HardwareBufferFrame> matches = findMatchingSecondaryFrames(nextPrimaryFrame);
      if (matches == null) {
        return;
      }

      ImmutableList.Builder<HardwareBufferFrame> outputFramesBuilder =
          new ImmutableList.Builder<>();
      outputFramesBuilder.add(nextPrimaryFrame);
      // Retain the matched secondary frames. We need to reference count them because
      // the same secondary frame might be reused for multiple future primary frames.
      for (HardwareBufferFrame match : matches) {
        outputFramesBuilder.add(match.retain());
      }
      // Remove the primary frame because each one is only matched once.
      checkNotNull(inputFrameQueues.get(0).frames).poll();
      downstreamConsumer.accept(outputFramesBuilder.build());
    }
  }

  private void handlePrimaryEndOfStream() {
    // When the primary sequence ends, send an EOS frame downstream.
    if (inputFrameQueues.get(0).isEnded) {
      downstreamConsumer.accept(ImmutableList.of(HardwareBufferFrame.END_OF_STREAM_FRAME));
      isEnded = true;
    }
  }

  /**
   * Finds matching frames from secondary queues for the given primary frame. Returns null if any
   * secondary queue is not yet at or past the primary frame's timestamp and is not yet ended.
   */
  @Nullable
  private ImmutableList<HardwareBufferFrame> findMatchingSecondaryFrames(
      HardwareBufferFrame nextPrimaryFrame) {
    ImmutableList.Builder<HardwareBufferFrame> matchesBuilder = ImmutableList.builder();
    for (int i = 1; i < numSequences; i++) {
      FrameQueue secondaryQueue = inputFrameQueues.get(i);
      @Nullable
      HardwareBufferFrame matchingFrame = secondaryQueue.getMatchingFrame(nextPrimaryFrame);
      if (matchingFrame == null) {
        if (!secondaryQueue.getIsEnded()) {
          // Need to wait for more frames in this secondary queue.
          return null;
        }
        // Secondary queue ended before this primary frame no match is possible, so this sequence is
        // ignored in the matching frames list.
      } else {
        matchesBuilder.add(matchingFrame);
      }
    }
    return matchesBuilder.build();
  }

  /** A helper class representing a {@link Queue<HardwareBufferFrame>} that can end. */
  private static class FrameQueue {
    final Queue<HardwareBufferFrame> frames;
    private boolean isRegistered;
    private boolean shouldAggregate;
    private boolean isEnded;

    FrameQueue() {
      frames = new ArrayDeque<>();
      // Force FrameAggregator to wait for frames for this sequence, until initialize is called.
      shouldAggregate = true;
    }

    void initialize(boolean shouldAggregate) {
      checkState(!isRegistered);
      this.isRegistered = true;
      this.shouldAggregate = shouldAggregate;
    }

    void setIsEnded(boolean isEnded) {
      this.isEnded = isEnded;
    }

    boolean getIsEnded() {
      checkState(isRegistered);
      return isEnded || !shouldAggregate;
    }

    /**
     * Finds the best matching frame for the given target frame's sequence presentation timestamp.
     *
     * <p>This method iterates through the frame queue, releasing any frames with presentation
     * timestamps strictly less than the {@code targetFrame}'s sequencePresentationTimeUs. This
     * ensures that we don't hold onto frames that can no longer be used for matching.
     *
     * <p>The first frame remaining in the queue, which will have a presentation timestamp greater
     * than or equal to the {@code targetFrame}'s sequencePresentationTimeUs, is considered the
     * match. This frame is not removed from the queue by this method, as it might be needed to
     * match subsequent primary frames (i.e., upsampling the secondary stream).
     *
     * <p>This matching strategy is deterministic: for a given state of the queue and a given {@code
     * targetFrame}, the result of this method will always be the same. It's designed to be
     * efficient by only retaining the necessary frames in the secondary queue – effectively keeping
     * only the secondary frames that are current or in the future relative to the last processed
     * primary frame timestamp.
     *
     * <p>Corner Cases:
     *
     * <ul>
     *   <li>If the queue is empty, returns null.
     *   <li>If all frames in the queue are older than {@code targetFrame}, all frames are released,
     *       and it returns null.
     * </ul>
     *
     * @param targetFrame The primary frame for which to find a matching secondary frame.
     * @return The best matching frame from this queue, or null if no suitable frame is currently
     *     available.
     */
    @Nullable
    private HardwareBufferFrame getMatchingFrame(HardwareBufferFrame targetFrame) {
      long targetTime = targetFrame.sequencePresentationTimeUs;
      if (frames.isEmpty()) {
        return null;
      }

      while (!frames.isEmpty()) {
        HardwareBufferFrame nextFrame = checkNotNull(frames.peek());
        if (nextFrame.sequencePresentationTimeUs < targetTime) {
          frames.poll();
          nextFrame.release(/* releaseFence= */ null);
        } else {
          // Found the first frame >= targetTime
          break;
        }
      }

      return frames.peek();
    }
  }
}
