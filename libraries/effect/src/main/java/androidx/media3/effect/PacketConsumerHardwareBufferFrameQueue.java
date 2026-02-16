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
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.hardware.HardwareBuffer;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.Format;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.effect.PacketConsumer.Packet;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
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

  /**
   * Listener for {@link PacketConsumerHardwareBufferFrameQueue} events.
   *
   * <p>The methods are called on an internal processing thread before packets are forwarded to the
   * downstream {@link PacketConsumer}.
   */
  public interface Listener {
    /** Returns a {@link SurfaceInfo} based on the given {@link Format}. */
    SurfaceInfo getRendererSurfaceInfo(Format format) throws VideoFrameProcessingException;

    /** Called when the end of stream has been reached and is about to be signaled downstream. */
    void onEndOfStream();

    /** Called when an asynchronous error occurs. */
    void onError(VideoFrameProcessingException e);
  }

  private final Object lock = new Object();

  /** The pool of created {@link HardwareBufferFrame} instances that are ready to be dequeued. */
  @GuardedBy("lock")
  private final Queue<HardwareBufferFrame> pool;

  /** Only accessed on the {@link #releaseFrameExecutor}. */
  private boolean isReleased;

  private final Executor releaseFrameExecutor;
  private final RenderingPacketConsumer<HardwareBufferFrame, SurfaceInfo> packetRenderer;
  private final Listener listener;

  // TODO: b/479134794 - This being nullable and mutable adds complexity, simplify this.
  private final PacketConsumerCaller<HardwareBufferFrame> output;

  @GuardedBy("lock")
  private int allocatedBufferCount;

  @GuardedBy("lock")
  @Nullable
  private Runnable wakeupListener;

  private boolean isRenderSurfaceInfoSet;

  /**
   * Creates a new instance.
   *
   * @param releaseFrameExecutor The executor that frames will be used to release frames back to the
   *     pool.
   * @param packetRenderer The downstream {@link RenderingPacketConsumer} that will receive the
   *     frames.
   * @param listener The {@link Listener}.
   */
  public PacketConsumerHardwareBufferFrameQueue(
      Executor releaseFrameExecutor,
      RenderingPacketConsumer<HardwareBufferFrame, SurfaceInfo> packetRenderer,
      Listener listener) {
    pool = new ArrayDeque<>(CAPACITY);
    this.releaseFrameExecutor = releaseFrameExecutor;
    this.packetRenderer = packetRenderer;
    this.listener = listener;
    packetRenderer.setErrorConsumer(e -> listener.onError(VideoFrameProcessingException.from(e)));
    output =
        PacketConsumerCaller.create(
            packetRenderer,
            newDirectExecutorService(),
            e -> listener.onError(VideoFrameProcessingException.from(e)));
    output.run();
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
    if (!isRenderSurfaceInfoSet) {
      SurfaceInfo surfaceInfo;
      try {
        surfaceInfo = listener.getRendererSurfaceInfo(frame.format);
      } catch (VideoFrameProcessingException e) {
        listener.onError(e);
        return;
      }
      isRenderSurfaceInfoSet = true;
      checkNotNull(packetRenderer).setRenderOutput(surfaceInfo);
    }
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
    listener.onEndOfStream();
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
    output.release();
    ListenableFuture<Void> releaseFrameRendererFuture =
        PacketConsumerUtil.release(packetRenderer, newDirectExecutorService());
    @Nullable Exception exception = null;
    try {
      releaseFrameRendererFuture.get(/* timeout= */ 500, MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      exception = e;
    } catch (Exception e) {
      exception = e;
    } finally {
      if (exception != null) {
        listener.onError(VideoFrameProcessingException.from(exception));
      }
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
    Futures.addCallback(
        output.queuePacket(packet),
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void result) {}

          @Override
          public void onFailure(Throwable t) {
            listener.onError(new VideoFrameProcessingException(t));
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
          listener.onError(VideoFrameProcessingException.from(e));
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
