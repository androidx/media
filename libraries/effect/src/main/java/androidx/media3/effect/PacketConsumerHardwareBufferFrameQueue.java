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
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;

import android.hardware.HardwareBuffer;
import android.hardware.SyncFence;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.effect.PacketConsumer.Packet;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * An implementation of {@link HardwareBufferFrameQueue} that manages a fixed-capacity pool of
 * {@link HardwareBuffer}s, that are forwarded to a downstream {@link PacketConsumer}.
 *
 * <p>This class maintains an internal pool (default capacity of 5) of buffers.
 *
 * <p><b>Thread Safety:</b> This class is <b>not thread-safe</b>.
 */
@RequiresApi(26)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
public class PacketConsumerHardwareBufferFrameQueue implements HardwareBufferFrameQueue {

  /** The maximum number of buffers allowed to exist (pooled + in-flight). */
  private static final int CAPACITY = 5;

  /** The pool of created {@link HardwareBufferFrame} instances that are ready to be dequeued. */
  private final Queue<HardwareBufferFrame> pool;

  private final Consumer<Exception> errorConsumer;
  // TODO: b/479134794 - This being nullable and mutable adds complexity, simplify this.
  private @Nullable PacketConsumerCaller<HardwareBufferFrame> output;
  private int capacityInUse;
  @Nullable private Runnable wakeupListener;

  /**
   * Creates a new instance.
   *
   * @param errorConsumer A consumer for reporting asynchronous errors.
   */
  public PacketConsumerHardwareBufferFrameQueue(Consumer<Exception> errorConsumer) {
    pool = new ArrayDeque<>(CAPACITY);
    this.errorConsumer = errorConsumer;
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
    if (capacityInUse >= CAPACITY) {
      this.wakeupListener = wakeupListener;
      return null;
    }
    HardwareBufferFrame frame = getOrCreateBuffer(format);
    capacityInUse++;
    return frame;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Overrides the callback called in {@link HardwareBufferFrame#release()} to return the queued
   * frame to the internal {@link #pool}.
   *
   * @param frame The frame to be queued.
   */
  @Override
  public void queue(HardwareBufferFrame frame) {
    HardwareBufferFrame outputFrame =
        new HardwareBufferFrame.Builder(
                frame.hardwareBuffer,
                directExecutor(),
                /* releaseCallback= */ () ->
                    returnHardwareBuffer(checkNotNull(frame.hardwareBuffer)))
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
   * <p>This should be called when the pipeline is being shut down to ensure all asynchronous
   * resources are cleaned up.
   */
  public void release() {
    if (output != null) {
      output.release();
    }
  }

  private HardwareBufferFrame getOrCreateBuffer(FrameFormat format) {
    while (!pool.isEmpty()) {
      HardwareBufferFrame frame = pool.remove();
      HardwareBuffer buffer = checkNotNull(frame.hardwareBuffer);
      if (!buffer.isClosed()
          && buffer.getWidth() == format.width
          && buffer.getHeight() == format.height
          && buffer.getFormat() == format.pixelFormat
          && buffer.getUsage() == adjustUsageFlags(format.usageFlags)) {
        return frame;
      } else {
        if (!buffer.isClosed()) {
          buffer.close();
        }
        closeFence(frame.acquireFence);
      }
    }
    HardwareBuffer buffer =
        HardwareBuffer.create(
            format.width,
            format.height,
            format.pixelFormat,
            /* layers= */ 1,
            adjustUsageFlags(format.usageFlags));
    return new HardwareBufferFrame.Builder(
            buffer,
            MoreExecutors.directExecutor(),
            /* releaseCallback= */ () -> returnHardwareBuffer(buffer))
        .build();
  }

  private void returnHardwareBuffer(HardwareBuffer buffer) {
    // Ensure the same buffer is not added to the pool multiple times.
    if (poolContainsBuffer(buffer)) {
      return;
    }
    try {
      if (!buffer.isClosed() && pool.size() < CAPACITY) {
        // TODO: b/475744934 - Set the acquireFence to the previous releaseFence.
        HardwareBufferFrame frame =
            new HardwareBufferFrame.Builder(
                    buffer,
                    MoreExecutors.directExecutor(),
                    /* releaseCallback= */ () -> returnHardwareBuffer(buffer))
                .build();
        pool.add(frame);
      } else if (!buffer.isClosed()) {
        buffer.close();
      }
    } finally {
      checkState(capacityInUse-- >= 0);
      Runnable listener = wakeupListener;
      if (capacityInUse < CAPACITY && listener != null) {
        wakeupListener = null;
        listener.run();
      }
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

  private void closeFence(@Nullable SyncFence fence) {
    if (SDK_INT >= 33) {
      if (fence != null && fence.isValid()) {
        fence.close();
      }
    }
  }

  private boolean poolContainsBuffer(HardwareBuffer target) {
    for (HardwareBufferFrame frame : pool) {
      if (frame.hardwareBuffer == target) {
        return true;
      }
    }
    return false;
  }

  private long adjustUsageFlags(long requestedUsageFlags) {
    // Ensure usage flags required by the consumer of this buffer are added.
    return requestedUsageFlags | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE;
  }
}
