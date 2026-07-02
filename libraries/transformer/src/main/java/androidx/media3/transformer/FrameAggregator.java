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
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.util.Rational;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.Util;
import androidx.media3.effect.HardwareBufferFrame;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.InlineMe;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Combines multiple sequences of {@link HardwareBufferFrame}s into one sequence of {@link
 * ImmutableList<HardwareBufferFrame>}.
 */
/* package */ class FrameAggregator implements AutoCloseable {
  private static final VirtualFrameToken VIRTUAL_FRAME_TOKEN = new VirtualFrameToken();

  private final Consumer<ImmutableList<HardwareBufferFrame>> downstreamConsumer;
  private final Consumer<Integer> onFlush;
  private final List<FrameQueue> inputFrameQueues;
  private final int numSequences;
  @Nullable private final Rational frameRate;
  @Nullable private final HardwareBufferFrame.Builder virtualFrameBuilder;

  private volatile boolean isEnded;
  private volatile boolean isClosed;
  // Index of the next virtual reference tick, or C.INDEX_UNSET if awaiting initial frame.
  private volatile long nextVirtualFrameIndex;
  @Nullable private volatile HardwareBufferFrame cachedVirtualFrame;
  private volatile long cachedVirtualFrameIndex;

  /**
   * @deprecated Use {@link #FrameAggregator(int, Rational, Consumer, Consumer)} instead.
   */
  @InlineMe(replacement = "this(numSequences, null, downstreamConsumer, onFlush)")
  @Deprecated
  /* package */ FrameAggregator(
      int numSequences,
      Consumer<ImmutableList<HardwareBufferFrame>> downstreamConsumer,
      Consumer<Integer> onFlush) {
    this(numSequences, /* frameRate= */ null, downstreamConsumer, onFlush);
  }

  /**
   * Creates a new {@link FrameAggregator}.
   *
   * <p>When {@code frameRate} is {@code null}, secondary sequences are aligned to the presentation
   * timestamps of frames queued to the primary sequence (at index 0), and aggregation ends when the
   * primary sequence ends.
   *
   * <p>When {@code frameRate} is set, an internal virtual clock generates reference ticks at the
   * requested rate, and all sequences are retimed and aligned to these reference ticks. In this
   * case, aggregation ends when all active sequences have completed and drained.
   *
   * @param numSequences The number of sequences to expect frames from.
   * @param frameRate The target frame rate in frames per second, or {@code null} to use the primary
   *     sequence as the reference timeline.
   * @param downstreamConsumer Receives the aggregated {@linkplain
   *     ImmutableList<HardwareBufferFrame> frames}.
   * @param onFlush Callback triggered when {@link #flush(int)} is called.
   * @throws IllegalArgumentException If {@code numSequences} is less than 1, or if {@code
   *     frameRate} has a zero or negative numerator or denominator.
   */
  /* package */ FrameAggregator(
      int numSequences,
      @Nullable Rational frameRate,
      Consumer<ImmutableList<HardwareBufferFrame>> downstreamConsumer,
      Consumer<Integer> onFlush) {
    checkArgument(numSequences > 0, "numSequences must be at least 1.");
    checkArgument(
        frameRate == null || (frameRate.getNumerator() > 0 && frameRate.getDenominator() > 0));
    this.numSequences = numSequences;
    this.frameRate = frameRate;
    this.downstreamConsumer = downstreamConsumer;
    this.onFlush = onFlush;
    inputFrameQueues = new ArrayList<>();
    for (int i = 0; i < numSequences; i++) {
      inputFrameQueues.add(new FrameQueue());
    }
    nextVirtualFrameIndex = C.INDEX_UNSET;
    cachedVirtualFrame = null;
    cachedVirtualFrameIndex = C.INDEX_UNSET;
    if (frameRate != null) {
      virtualFrameBuilder =
          new HardwareBufferFrame.Builder(
                  /* hardwareBuffer= */ null,
                  directExecutor(),
                  /* releaseCallback= */ (fence) -> {})
              // HardwareBufferFrame.Builder requires a non-null internal frame object when
              // hardwareBuffer is null. A named class is used instead of a generic Object to
              // clearly identify the token in heap dumps and memory profiles.
              .setInternalFrame(VIRTUAL_FRAME_TOKEN);
    } else {
      virtualFrameBuilder = null;
    }
  }

  /**
   * Registers the given {@code sequenceIndex} with the {@link FrameAggregator}, and indicates
   * whether it should be considered when aggregating frames.
   *
   * <p>All sequences must be registered before frames are queued.
   *
   * @throws IllegalArgumentException If {@code sequenceIndex} is negative or greater than or equal
   *     to the number of sequences.
   */
  public void registerSequence(int sequenceIndex, boolean shouldAggregate) {
    checkArgument(sequenceIndex >= 0);
    checkArgument(sequenceIndex < numSequences);
    checkState(!isClosed);
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
   * @throws IllegalArgumentException If {@code sequenceIndex} is negative or greater than or equal
   *     to the number of sequences.
   */
  public void queueFrame(HardwareBufferFrame frame, int sequenceIndex) {
    checkArgument(sequenceIndex >= 0);
    checkArgument(sequenceIndex < numSequences);
    checkState(inputFrameQueues.get(sequenceIndex).isRegistered);
    // Release frames immediately if the primary sequence has already ended, or the aggregator is
    // closed.
    if (isEnded || isClosed) {
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
   * @throws IllegalArgumentException If {@code sequenceIndex} is negative or greater than or equal
   *     to the number of sequences.
   */
  public void queueEndOfStream(int sequenceIndex) {
    checkArgument(sequenceIndex >= 0);
    checkArgument(sequenceIndex < numSequences);
    checkState(inputFrameQueues.get(sequenceIndex).isRegistered);
    if (isClosed) {
      return;
    }
    inputFrameQueues.get(sequenceIndex).setIsEnded(/* isEnded= */ true);
    maybeAggregate();
  }

  /**
   * Removes all frames from the given sequence.
   *
   * @param sequenceIndex The index of the sequence to flush.
   * @throws IllegalArgumentException If {@code sequenceIndex} is negative or greater than or equal
   *     to the number of sequences.
   */
  public void flush(int sequenceIndex) {
    checkArgument(sequenceIndex >= 0);
    checkArgument(sequenceIndex < numSequences);
    checkState(inputFrameQueues.get(sequenceIndex).isRegistered);
    if (isClosed) {
      return;
    }
    @Nullable HardwareBufferFrame nextFrame;
    while ((nextFrame = inputFrameQueues.get(sequenceIndex).frames.poll()) != null) {
      nextFrame.release(/* releaseFence= */ null);
    }
    // CompositionPlayer does not support independent sequence flushing and always flushes all
    // sequences iteratively. We can just reset the global isEnded state when sequence 0 is flushed.
    if (sequenceIndex == 0) {
      isEnded = false;
    }
    inputFrameQueues.get(sequenceIndex).setIsEnded(/* isEnded= */ false);
    // The virtual clock index is currently cleared whenever any sequence is flushed. This is
    // safe because CompositionPlayer does not support independent sequence flushing and always
    // flushes all sequences iteratively. If independent flushing is supported in the future,
    // this behavior may need to be re-evaluated based on the new requirements.
    if (frameRate != null) {
      nextVirtualFrameIndex = C.INDEX_UNSET;
      clearCachedVirtualFrame();
    }
    onFlush.accept(sequenceIndex);
  }

  /**
   * {@linkplain HardwareBufferFrame#release Releases } all frames that have not been sent
   * downstream, and forces this instance to immediately release any newly queued frames.
   */
  @Override
  public void close() {
    isClosed = true;
    for (int i = 0; i < inputFrameQueues.size(); i++) {
      @Nullable HardwareBufferFrame nextFrame;
      while ((nextFrame = inputFrameQueues.get(i).frames.poll()) != null) {
        nextFrame.release(/* releaseFence= */ null);
      }
    }
    clearCachedVirtualFrame();
  }

  /**
   * Selects the next frame greater than or equal to the current frame from the primary input stream
   * from each secondary stream.
   */
  @SuppressWarnings("NonAtomicVolatileUpdate")
  private void maybeAggregate() {
    while (!isEnded) {
      @Nullable HardwareBufferFrame referenceFrame = getNextReferenceFrame();
      if (referenceFrame == null) {
        handlePrimaryEndOfStream();
        return;
      }
      if (referenceFrame == HardwareBufferFrame.END_OF_STREAM_FRAME) {
        downstreamConsumer.accept(ImmutableList.of(HardwareBufferFrame.END_OF_STREAM_FRAME));
        isEnded = true;
        return;
      }

      ImmutableList<HardwareBufferFrame> matches = findMatchingFrames(referenceFrame);
      if (matches == null) {
        return;
      }

      ImmutableList.Builder<HardwareBufferFrame> outputFramesBuilder =
          new ImmutableList.Builder<>();
      if (frameRate == null) {
        outputFramesBuilder.add(referenceFrame);
      }
      // Retain the matched secondary frames. We need to reference count them because
      // the same secondary frame might be reused for multiple future primary frames.
      for (HardwareBufferFrame match : matches) {
        HardwareBufferFrame frame = match.retain();
        // In the frameRate-unset case, physical Sequence 0 defines the primary output presentation
        // timestamp. Under a virtual clock, primary track frames may be duplicated and those new
        // frames must be retimed. We retime all the frames here for the sake of consistency and
        // simplicity. Note that the maximum timestamp shift is bounded by the inter-frame
        // duration of the track.
        if (frameRate != null) {
          long shiftDeltaUs =
              referenceFrame.sequencePresentationTimeUs - match.sequencePresentationTimeUs;
          frame =
              frame
                  .buildUpon()
                  .setPresentationTimeUs(match.presentationTimeUs + shiftDeltaUs)
                  .setSequencePresentationTimeUs(referenceFrame.sequencePresentationTimeUs)
                  .setReleaseTimeNs(referenceFrame.sequencePresentationTimeUs * 1_000L)
                  .build();
        }
        outputFramesBuilder.add(frame);
      }
      if (frameRate == null) {
        // Remove the primary frame because each one is only matched once.
        checkNotNull(inputFrameQueues.get(0).frames).poll();
      } else {
        // Only advance the virtual clock tick once all input queues are ready (matches != null)
        // and an output packet is successfully assembled. nextVirtualFrameIndex is guaranteed
        // to be initialized by getNextReferenceFrame().
        checkState(nextVirtualFrameIndex != C.INDEX_UNSET);
        nextVirtualFrameIndex++;
      }
      ImmutableList<HardwareBufferFrame> outputFrames = outputFramesBuilder.build();
      if (outputFrames.isEmpty()) {
        downstreamConsumer.accept(ImmutableList.of(HardwareBufferFrame.END_OF_STREAM_FRAME));
        isEnded = true;
        return;
      }
      downstreamConsumer.accept(outputFrames);
    }
  }

  /**
   * Returns the next reference frame.
   *
   * <p>When a target frame rate is set, this synthesizes a virtual reference frame ticking at the
   * requested rate. Otherwise, this returns the physical frame sitting at the head of Sequence 0.
   *
   * @return The next reference frame, or {@code null} if awaiting input data, or {@link
   *     HardwareBufferFrame#END_OF_STREAM_FRAME} if all active sequences have fully completed.
   */
  @Nullable
  private HardwareBufferFrame getNextReferenceFrame() {
    if (frameRate != null) {
      if (isAllSequencesEndedAndEmpty()) {
        return HardwareBufferFrame.END_OF_STREAM_FRAME;
      }
      if (nextVirtualFrameIndex == C.INDEX_UNSET) {
        long firstAvailableTimeUs = getFirstAvailableFrameTimeUs();
        if (firstAvailableTimeUs == C.TIME_UNSET) {
          return null; // Await initial frame.
        }
        // By ceiling the index, we guarantee that the corresponding virtual tick's timestamp is
        // always >= firstAvailableTimeUs. This ensures that the virtual clock never produces frames
        // with timestamps earlier than the current available frames (e.g. before the seek
        // position).
        nextVirtualFrameIndex = getVirtualFrameIndexCeil(firstAvailableTimeUs, frameRate);
      }
      return getOrGenerateVirtualFrame();
    } else {
      // TODO: b/496904840 - This assumes the primary sequence can be aggregated against. Update it
      //  to when this is not the case.
      return checkNotNull(inputFrameQueues.get(0).frames).peek();
    }
  }

  @Nullable
  private HardwareBufferFrame getOrGenerateVirtualFrame() {
    if (isClosed || frameRate == null || virtualFrameBuilder == null) {
      return null;
    }
    if (cachedVirtualFrame != null && cachedVirtualFrameIndex == nextVirtualFrameIndex) {
      return cachedVirtualFrame;
    }
    long frameDurationUsNumerator = 1_000_000L * frameRate.getDenominator();
    long frameDurationUsDenominator = frameRate.getNumerator();
    long targetTimeUs =
        Util.scaleLargeValue(
            /* value= */ nextVirtualFrameIndex,
            /* multiplier= */ frameDurationUsNumerator,
            /* divisor= */ frameDurationUsDenominator,
            RoundingMode.HALF_UP);
    HardwareBufferFrame newFrame =
        virtualFrameBuilder
            .setPresentationTimeUs(targetTimeUs)
            .setSequencePresentationTimeUs(targetTimeUs)
            .setReleaseTimeNs(targetTimeUs * 1_000L)
            .build();
    if (cachedVirtualFrame != null) {
      cachedVirtualFrame.release(/* releaseFence= */ null);
    }
    cachedVirtualFrame = newFrame;
    cachedVirtualFrameIndex = nextVirtualFrameIndex;
    return newFrame;
  }

  private void clearCachedVirtualFrame() {
    if (cachedVirtualFrame != null) {
      cachedVirtualFrame.release(/* releaseFence= */ null);
      cachedVirtualFrame = null;
      cachedVirtualFrameIndex = C.INDEX_UNSET;
    }
  }

  /** Returns whether all active input queues are ended and empty. */
  private boolean isAllSequencesEndedAndEmpty() {
    for (int i = 0; i < numSequences; i++) {
      FrameQueue queue = inputFrameQueues.get(i);
      if (!queue.getIsEnded() || !queue.frames.isEmpty()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns the earliest presentation time across all active input queues, or {@link C#TIME_UNSET}
   * if no frames are currently available.
   */
  private long getFirstAvailableFrameTimeUs() {
    long firstAvailableTimeUs = Long.MAX_VALUE;
    for (int i = 0; i < numSequences; i++) {
      FrameQueue queue = inputFrameQueues.get(i);
      if (!queue.getIsEnded() && queue.frames.isEmpty()) {
        // Must wait for all active sequences to provide their first frame.
        return C.TIME_UNSET;
      }
      if (!queue.frames.isEmpty()) {
        firstAvailableTimeUs =
            Math.min(
                firstAvailableTimeUs, checkNotNull(queue.frames.peek()).sequencePresentationTimeUs);
      }
    }
    return firstAvailableTimeUs == Long.MAX_VALUE ? C.TIME_UNSET : firstAvailableTimeUs;
  }

  private void handlePrimaryEndOfStream() {
    // Without a target frame rate, ending Sequence 0 ends the composition.
    if (frameRate == null && inputFrameQueues.get(0).isEnded) {
      downstreamConsumer.accept(ImmutableList.of(HardwareBufferFrame.END_OF_STREAM_FRAME));
      isEnded = true;
    }
  }

  /**
   * Finds matching frames from the input queues for the given reference frame. Returns null if any
   * active queue is not yet at or past the reference frame's timestamp and is not yet ended.
   */
  @Nullable
  private ImmutableList<HardwareBufferFrame> findMatchingFrames(
      HardwareBufferFrame referenceFrame) {
    ImmutableList.Builder<HardwareBufferFrame> matchesBuilder = ImmutableList.builder();
    // In virtual-reference mode, align all physical sequences (index 0+).
    // In physical-reference mode (Sequence 0), only match secondary sequences (index 1+).
    int firstSequenceToMatch = frameRate != null ? 0 : 1;
    for (int i = firstSequenceToMatch; i < numSequences; i++) {
      FrameQueue secondaryQueue = inputFrameQueues.get(i);
      @Nullable HardwareBufferFrame matchingFrame = secondaryQueue.getMatchingFrame(referenceFrame);
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

  /**
   * Returns the index of the virtual tick that occurs at or immediately following the given {@code
   * timeUs}, or {@link C#INDEX_UNSET} if {@code frameRate} is {@code null} or {@code timeUs} is
   * {@link C#TIME_UNSET}.
   */
  private static long getVirtualFrameIndexCeil(long timeUs, @Nullable Rational frameRate) {
    // TODO: b/525309275 - Consider subtracting a small tolerance (e.g. 0.5 us) from timeUs before
    // calculating the ceiling. Without it, the first available frame after a seek/flush might be
    // dropped if its timestamp was rounded up to the nearest microsecond by upstream components,
    // causing the strict ceiling division to overshoot and snap to the next virtual tick.
    if (frameRate == null || timeUs == C.TIME_UNSET) {
      return C.INDEX_UNSET;
    }
    return Util.scaleLargeValue(
        /* value= */ timeUs,
        /* multiplier= */ frameRate.getNumerator(),
        /* divisor= */ 1_000_000L * frameRate.getDenominator(),
        RoundingMode.CEILING);
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

  private static final class VirtualFrameToken {}
}
