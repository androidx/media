/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.container;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;
import static androidx.media3.common.util.Util.castNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/** A queue of buffers, ordered by presentation timestamp. */
@RestrictTo(LIBRARY_GROUP)
public final class ReorderingBufferQueue {

  /** Functional interface to handle a buffer that is being removed from the queue. */
  public interface OutputConsumer {
    /** Handles a buffer that is being removed from the queue. */
    void consume(long presentationTimeUs, ParsableByteArray buffer);
  }

  private final OutputConsumer outputConsumer;

  /** Pool of re-usable {@link ParsableByteArray} objects to avoid repeated allocations. */
  private final ArrayDeque<ParsableByteArray> unusedParsableByteArrays;

  /** Pool of re-usable {@link BuffersWithTimestamp} objects to avoid repeated allocations. */
  private final ArrayDeque<BuffersWithTimestamp> unusedBuffersWithTimestamp;

  private final PriorityQueue<BuffersWithTimestamp> pendingBuffers;

  private int reorderingQueueSize;
  @Nullable private BuffersWithTimestamp lastQueuedBuffer;

  /**
   * Creates an instance, initially with no max size.
   *
   * @param outputConsumer Callback to invoke when buffers are removed from the head of the queue,
   *     either due to exceeding the {@linkplain #setMaxSize(int) max queue size} during a call to
   *     {@link #add(long, ParsableByteArray)}, or due to {@link #flush()}.
   */
  public ReorderingBufferQueue(OutputConsumer outputConsumer) {
    this.outputConsumer = outputConsumer;
    unusedParsableByteArrays = new ArrayDeque<>();
    unusedBuffersWithTimestamp = new ArrayDeque<>();
    pendingBuffers = new PriorityQueue<>();
    reorderingQueueSize = C.LENGTH_UNSET;
  }

  /**
   * Sets the max size of the re-ordering queue.
   *
   * <p>The size is defined in terms of the number of unique presentation timestamps, rather than
   * the number of buffers. This ensures that properties like H.264's {@code
   * max_number_reorder_frames} can be used to set this max size in the case of multiple SEI
   * messages per sample (where multiple SEI messages therefore have the same presentation
   * timestamp).
   *
   * <p>When the queue exceeds this size during a call to {@link #add(long, ParsableByteArray)}, the
   * buffers associated with the least timestamp are passed to the {@link OutputConsumer} provided
   * during construction.
   *
   * <p>If the new size is larger than the number of elements currently in the queue, items are
   * removed from the head of the queue (least first) and passed to the {@link OutputConsumer}
   * provided during construction.
   */
  public void setMaxSize(int reorderingQueueSize) {
    checkState(reorderingQueueSize >= 0);
    this.reorderingQueueSize = reorderingQueueSize;
    flushQueueDownToSize(reorderingQueueSize);
  }

  /**
   * Returns the maximum size of this queue, or {@link C#LENGTH_UNSET} if it is unbounded.
   *
   * <p>See {@link #setMaxSize(int)} for details on how size is defined.
   */
  public int getMaxSize() {
    return reorderingQueueSize;
  }

  /**
   * Adds a buffer to the queue.
   *
   * <p>If this causes the queue to exceed its {@linkplain #setMaxSize(int) max size}, buffers
   * associated with the least timestamp (which may be the buffer passed to this method) are passed
   * to the {@link OutputConsumer} provided during construction.
   *
   * <p>buffers with matching timestamps must be added consecutively (this will naturally happen
   * when parsing buffers from a container).
   *
   * @param presentationTimeUs The presentation time of the buffer. {@link C#TIME_UNSET} will cause
   *     the buffer to be emitted immediately.
   * @param buffer The buffer data. The data will be copied, so the provided object can be re-used
   *     after this method returns.
   */
  public void add(long presentationTimeUs, ParsableByteArray buffer) {
    if (presentationTimeUs == C.TIME_UNSET
        || reorderingQueueSize == 0
        || (reorderingQueueSize != C.LENGTH_UNSET
            && pendingBuffers.size() >= reorderingQueueSize
            && presentationTimeUs < castNonNull(pendingBuffers.peek()).presentationTimeUs)) {
      outputConsumer.consume(presentationTimeUs, buffer);
      return;
    }
    // Make a local copy of the buffer data so we can store it in the queue and allow the buffer
    // parameter to be safely re-used after this add() method returns.
    ParsableByteArray bufferCopy = copy(buffer);
    if (lastQueuedBuffer != null && presentationTimeUs == lastQueuedBuffer.presentationTimeUs) {
      lastQueuedBuffer.nalBuffers.add(bufferCopy);
      return;
    }
    BuffersWithTimestamp buffersWithTimestamp =
        unusedBuffersWithTimestamp.isEmpty()
            ? new BuffersWithTimestamp()
            : unusedBuffersWithTimestamp.pop();
    buffersWithTimestamp.init(presentationTimeUs, bufferCopy);
    pendingBuffers.add(buffersWithTimestamp);
    lastQueuedBuffer = buffersWithTimestamp;
    if (reorderingQueueSize != C.LENGTH_UNSET) {
      flushQueueDownToSize(reorderingQueueSize);
    }
  }

  /**
   * Copies {@code input} into a {@link ParsableByteArray} instance from {@link
   * #unusedParsableByteArrays}, or a new instance if that is empty.
   */
  private ParsableByteArray copy(ParsableByteArray input) {
    ParsableByteArray result =
        unusedParsableByteArrays.isEmpty()
            ? new ParsableByteArray()
            : unusedParsableByteArrays.pop();
    result.reset(input.bytesLeft());
    System.arraycopy(
        /* src= */ input.getData(),
        /* srcPos= */ input.getPosition(),
        /* dest= */ result.getData(),
        /* destPos= */ 0,
        /* length= */ result.bytesLeft());
    return result;
  }

  /** Empties the queue, discarding all previously {@linkplain #add added} buffers. */
  public void clear() {
    pendingBuffers.clear();
  }

  /**
   * Empties the queue, passing all buffers (least first) to the {@link OutputConsumer} provided
   * during construction.
   */
  public void flush() {
    flushQueueDownToSize(0);
  }

  private void flushQueueDownToSize(int targetSize) {
    while (pendingBuffers.size() > targetSize) {
      BuffersWithTimestamp buffersWithTimestamp = castNonNull(pendingBuffers.poll());
      for (int i = 0; i < buffersWithTimestamp.nalBuffers.size(); i++) {
        outputConsumer.consume(
            buffersWithTimestamp.presentationTimeUs, buffersWithTimestamp.nalBuffers.get(i));
        unusedParsableByteArrays.push(buffersWithTimestamp.nalBuffers.get(i));
      }
      buffersWithTimestamp.nalBuffers.clear();
      if (lastQueuedBuffer != null
          && lastQueuedBuffer.presentationTimeUs == buffersWithTimestamp.presentationTimeUs) {
        lastQueuedBuffer = null;
      }
      unusedBuffersWithTimestamp.push(buffersWithTimestamp);
    }
  }

  /** Holds the presentation timestamp of a sample and its associated buffers. */
  private static final class BuffersWithTimestamp implements Comparable<BuffersWithTimestamp> {

    public final List<ParsableByteArray> nalBuffers;
    public long presentationTimeUs;

    public BuffersWithTimestamp() {
      presentationTimeUs = C.TIME_UNSET;
      nalBuffers = new ArrayList<>();
    }

    public void init(long presentationTimeUs, ParsableByteArray nalBuffer) {
      checkArgument(presentationTimeUs != C.TIME_UNSET);
      checkState(this.nalBuffers.isEmpty());
      this.presentationTimeUs = presentationTimeUs;
      this.nalBuffers.add(nalBuffer);
    }

    @Override
    public int compareTo(BuffersWithTimestamp other) {
      return Long.compare(this.presentationTimeUs, other.presentationTimeUs);
    }
  }
}
