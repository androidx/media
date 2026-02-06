/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.effect;

import static android.os.Build.VERSION.SDK_INT;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;

import android.hardware.HardwareBuffer;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.effect.PacketConsumer.Packet;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * An implementation of {@link HardwareBufferFrameQueue} that manages a fixed-capacity pool of
 * {@link HardwareBuffer}s, that are forwarded to a downstream {@link PacketConsumer}.
 *
 * <p>This class maintains an internal pool (default capacity of 5) of buffers.
 */
@RequiresApi(26)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
public class PacketConsumerHardwareBufferFrameQueue implements HardwareBufferFrameQueue {

  /** The maximum number of buffers allowed to exist (pooled + in-flight). */
  private static final int CAPACITY = 5;

  private final Object lock = new Object();

  /** The pool of created {@link HardwareBufferFrame} instances that are ready to be dequeued. */
  @GuardedBy("lock")
  private final Queue<HardwareBufferFrame> pool;

  /** Only accessed on the {@link #releaseFrameExecutor}. */
  private boolean isReleased;

  private final Consumer<Exception> errorConsumer;
  private final Executor releaseFrameExecutor;
  // TODO: b/479134794 - This being nullable and mutable adds complexity, simplify this.
  @Nullable private volatile PacketConsumerCaller<HardwareBufferFrame> output;

  @GuardedBy("lock")
  private int allocatedBufferCount;

  @GuardedBy("lock")
  @Nullable
  private Runnable wakeupListener;

  /**
   * Creates a new instance.
   *
   * @param errorConsumer A consumer for reporting asynchronous errors.
   * @param releaseFrameExecutor The executor that frames will be used to release frames back to the
   *     pool.
   */
  public PacketConsumerHardwareBufferFrameQueue(
      Consumer<Exception> errorConsumer, Executor releaseFrameExecutor) {
    pool = new ArrayDeque<>(CAPACITY);
    this.errorConsumer = errorConsumer;
    this.releaseFrameExecutor = releaseFrameExecutor;
  }

  /**
   * Sets the downstream {@link PacketConsumer} that will receive the frames.
   *
   * <p>This method must be called before calling {@link #queue(HardwareBufferFrame)}.
   *
   * @param output The downstream consumer.
   * @throws IllegalStateException If an output has already been set.
   */
  public void setOutput(PacketConsumer<HardwareBufferFrame> output) {
    checkState(this.output == null);
    this.output = PacketConsumerCaller.create(output, newDirectExecutorService(), errorConsumer);
    this.output.run();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Returns a buffer from the pool if one exists with matching {@link FrameFormat}. If the pool
   * is empty but capacity is available, a new buffer is created. If the queue is at capacity,
   * returns {@code null} and the most recent {@code wakeupListener} will be notified when capacity
   * is available.
   */
  @Override
  public @Nullable HardwareBufferFrame dequeue(FrameFormat format, Runnable wakeupListener) {
    synchronized (lock) {
      @Nullable HardwareBufferFrame frame;
      while ((frame = pool.poll()) != null) {
        HardwareBuffer buffer = checkNotNull(frame.hardwareBuffer);
        if (isCompatible(buffer, format)) {
          return frame;
        } else {
          buffer.close();
          closeFence(frame.acquireFence);
          allocatedBufferCount--;
        }
      }
      if (allocatedBufferCount >= CAPACITY) {
        this.wakeupListener = wakeupListener;
        return null;
      }
      allocatedBufferCount++;
    }
    return createNewBuffer(format);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Overrides the callback called in {@link HardwareBufferFrame#release} to return the queued
   * frame to the internal {@link #pool}.
   *
   * @param frame The frame to be queued.
   */
  @Override
  public void queue(HardwareBufferFrame frame) {
    HardwareBufferFrame outputFrame =
        new HardwareBufferFrame.Builder(
                frame.hardwareBuffer,
                releaseFrameExecutor,
                /* releaseCallback= */ (releaseFence) ->
                    returnHardwareBuffer(checkNotNull(frame.hardwareBuffer), releaseFence))
            .setPresentationTimeUs(frame.presentationTimeUs)
            .setReleaseTimeNs(frame.releaseTimeNs)
            .setAcquireFence(frame.acquireFence)
            .setMetadata(frame.getMetadata())
            .setInternalFrame(frame.internalFrame)
            .setFormat(frame.format)
            .build();
    sendDownstream(Packet.of(outputFrame));
  }

  @Override
  @SuppressWarnings("unchecked")
  public void signalEndOfStream() {
    sendDownstream(Packet.EndOfStream.INSTANCE);
  }

  /**
   * Releases the queue and the downstream caller.
   *
   * <p>Must be called on the same thread as the {@link #releaseFrameExecutor}.
   *
   * <p>This should be called when the pipeline is being shut down to ensure all asynchronous
   * resources are cleaned up.
   */
  public void release() {
    if (isReleased) {
      return;
    }
    isReleased = true;
    PacketConsumerCaller<HardwareBufferFrame> output = this.output;
    if (output != null) {
      output.release();
    }
    synchronized (lock) {
      HardwareBufferFrame frame;
      while ((frame = pool.poll()) != null) {
        HardwareBuffer buffer = checkNotNull(frame.hardwareBuffer);
        if (!buffer.isClosed()) {
          buffer.close();
        }
      }
    }
  }

  private HardwareBufferFrame createNewBuffer(FrameFormat format) {
    HardwareBuffer buffer =
        HardwareBuffer.create(
            format.width,
            format.height,
            format.pixelFormat,
            /* layers= */ 1,
            adjustUsageFlags(format.usageFlags));
    checkState(!buffer.isClosed());
    return new HardwareBufferFrame.Builder(
            buffer,
            releaseFrameExecutor,
            /* releaseCallback= */ (releaseFence) -> returnHardwareBuffer(buffer, releaseFence))
        .build();
  }

  /** Always called on the {@link #releaseFrameExecutor}. */
  private void returnHardwareBuffer(HardwareBuffer buffer, @Nullable SyncFenceCompat releaseFence) {
    checkArgument(!buffer.isClosed());
    // TODO: b/479415385 - Use the releaseFence as acquireFence when reusing this buffer.
    closeFence(releaseFence);
    if (isReleased) {
      if (!buffer.isClosed()) {
        buffer.close();
      }
      return;
    }
    Runnable listenerToRun = null;
    synchronized (lock) {
      // Ensure the same buffer is not added to the pool multiple times.
      if (!poolContainsBuffer(buffer)) {
        // TODO: b/475744934 - Set the acquireFence to the previous releaseFence.
        HardwareBufferFrame frame =
            new HardwareBufferFrame.Builder(
                    buffer,
                    releaseFrameExecutor,
                    /* releaseCallback= */ (fence) -> returnHardwareBuffer(buffer, fence))
                .build();
        pool.add(frame);
        if (wakeupListener != null) {
          listenerToRun = wakeupListener;
          wakeupListener = null;
        }
      }
    }
    // Run listener outside of the synchronized block.
    if (listenerToRun != null) {
      listenerToRun.run();
    }
  }

  private void sendDownstream(Packet<HardwareBufferFrame> packet) {
    checkState(output != null);
    Futures.addCallback(
        output.queuePacket(packet),
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void result) {}

          @Override
          public void onFailure(Throwable t) {
            errorConsumer.accept(new Exception(t));
          }
        },
        directExecutor());
  }

  private void closeFence(@Nullable SyncFenceCompat fence) {
    if (SDK_INT >= 33) {
      if (fence != null) {
        try {
          fence.close();
        } catch (IOException e) {
          errorConsumer.accept(e);
        }
      }
    }
  }

  @GuardedBy("lock")
  private boolean poolContainsBuffer(HardwareBuffer target) {
    for (HardwareBufferFrame frame : pool) {
      if (frame.hardwareBuffer == target) {
        return true;
      }
    }
    return false;
  }

  private boolean isCompatible(HardwareBuffer buffer, FrameFormat format) {
    return buffer.getWidth() == format.width
        && buffer.getHeight() == format.height
        && buffer.getFormat() == format.pixelFormat
        && buffer.getUsage() == adjustUsageFlags(format.usageFlags);
  }

  private long adjustUsageFlags(long requestedUsageFlags) {
    // Ensure usage flags required by the consumer of this buffer are added.
    return requestedUsageFlags | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE;
  }
}
