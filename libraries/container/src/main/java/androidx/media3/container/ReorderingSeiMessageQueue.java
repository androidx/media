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
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Util.castNonNull;

import androidx.annotation.RestrictTo;
import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;

/** A queue of SEI messages, ordered by presentation timestamp. */
@UnstableApi
@RestrictTo(LIBRARY_GROUP)
public final class ReorderingSeiMessageQueue {

  /** Functional interface to handle an SEI message that is being removed from the queue. */
  public interface SeiConsumer {
    /** Handles an SEI message that is being removed from the queue. */
    void consume(long presentationTimeUs, ParsableByteArray seiBuffer);
  }

  private final SeiConsumer seiConsumer;
  private final AtomicLong tieBreakGenerator = new AtomicLong();

  /**
   * Pool of re-usable {@link SeiMessage} objects to avoid repeated allocations. Elements should be
   * added and removed from the 'tail' of the queue (with {@link Deque#push(Object)} and {@link
   * Deque#pop()}), to avoid unnecessary array copying.
   */
  private final ArrayDeque<SeiMessage> unusedSeiMessages;

  private final PriorityQueue<SeiMessage> pendingSeiMessages;

  private int reorderingQueueSize;

  /**
   * Creates an instance, initially with no max size.
   *
   * @param seiConsumer Callback to invoke when SEI messages are removed from the head of queue,
   *     either due to exceeding the {@linkplain #setMaxSize(int) max queue size} during a call to
   *     {@link #add(long, ParsableByteArray)}, or due to {@link #flush()}.
   */
  public ReorderingSeiMessageQueue(SeiConsumer seiConsumer) {
    this.seiConsumer = seiConsumer;
    unusedSeiMessages = new ArrayDeque<>();
    pendingSeiMessages = new PriorityQueue<>();
    reorderingQueueSize = C.LENGTH_UNSET;
  }

  /**
   * Sets the max size of the re-ordering queue.
   *
   * <p>When the queue exceeds this size during a call to {@link #add(long, ParsableByteArray)}, the
   * least message is passed to the {@link SeiConsumer} provided during construction.
   *
   * <p>If the new size is larger than the number of elements currently in the queue, items are
   * removed from the head of the queue (least first) and passed to the {@link SeiConsumer} provided
   * during construction.
   */
  public void setMaxSize(int reorderingQueueSize) {
    checkState(reorderingQueueSize >= 0);
    this.reorderingQueueSize = reorderingQueueSize;
    flushQueueDownToSize(reorderingQueueSize);
  }

  /**
   * Returns the maximum size of this queue, or {@link C#LENGTH_UNSET} if it is unbounded.
   *
   * <p>See {@link #setMaxSize(int)}.
   */
  public int getMaxSize() {
    return reorderingQueueSize;
  }

  /**
   * Adds a message to the queue.
   *
   * <p>If this causes the queue to exceed its {@linkplain #setMaxSize(int) max size}, the least
   * message (which may be the one passed to this method) is passed to the {@link SeiConsumer}
   * provided during construction.
   *
   * @param presentationTimeUs The presentation time of the SEI message.
   * @param seiBuffer The SEI data. The data will be copied, so the provided object can be re-used.
   */
  public void add(long presentationTimeUs, ParsableByteArray seiBuffer) {
    if (reorderingQueueSize == 0
        || (reorderingQueueSize != C.LENGTH_UNSET
            && pendingSeiMessages.size() >= reorderingQueueSize
            && presentationTimeUs < castNonNull(pendingSeiMessages.peek()).presentationTimeUs)) {
      seiConsumer.consume(presentationTimeUs, seiBuffer);
      return;
    }
    SeiMessage seiMessage =
        unusedSeiMessages.isEmpty() ? new SeiMessage() : unusedSeiMessages.poll();
    seiMessage.reset(presentationTimeUs, tieBreakGenerator.getAndIncrement(), seiBuffer);
    pendingSeiMessages.add(seiMessage);
    if (reorderingQueueSize != C.LENGTH_UNSET) {
      flushQueueDownToSize(reorderingQueueSize);
    }
  }

  /**
   * Empties the queue, passing all messages (least first) to the {@link SeiConsumer} provided
   * during construction.
   */
  public void flush() {
    flushQueueDownToSize(0);
  }

  private void flushQueueDownToSize(int targetSize) {
    while (pendingSeiMessages.size() > targetSize) {
      SeiMessage seiMessage = castNonNull(pendingSeiMessages.poll());
      seiConsumer.consume(seiMessage.presentationTimeUs, seiMessage.data);
      unusedSeiMessages.push(seiMessage);
    }
  }

  /** Holds data from a SEI sample with its presentation timestamp. */
  private static final class SeiMessage implements Comparable<SeiMessage> {

    private final ParsableByteArray data;

    private long presentationTimeUs;

    /**
     * {@link PriorityQueue} breaks ties arbitrarily. This field ensures that insertion order is
     * preserved when messages have the same {@link #presentationTimeUs}.
     */
    private long tieBreak;

    public SeiMessage() {
      presentationTimeUs = C.TIME_UNSET;
      data = new ParsableByteArray();
    }

    public void reset(long presentationTimeUs, long tieBreak, ParsableByteArray nalBuffer) {
      checkState(presentationTimeUs != C.TIME_UNSET);
      this.presentationTimeUs = presentationTimeUs;
      this.tieBreak = tieBreak;
      this.data.reset(nalBuffer.bytesLeft());
      System.arraycopy(
          /* src= */ nalBuffer.getData(),
          /* srcPos= */ nalBuffer.getPosition(),
          /* dest= */ data.getData(),
          /* destPos= */ 0,
          /* length= */ nalBuffer.bytesLeft());
    }

    @Override
    public int compareTo(SeiMessage other) {
      int timeComparison = Long.compare(this.presentationTimeUs, other.presentationTimeUs);
      return timeComparison != 0 ? timeComparison : Long.compare(this.tieBreak, other.tieBreak);
    }
  }
}
