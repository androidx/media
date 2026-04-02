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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;

import android.hardware.HardwareBuffer;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.Format;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.effect.HardwareBufferPool.HardwareBufferWithFence;
import androidx.media3.effect.PacketConsumer.Packet;

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

  /** The pool of created {@link HardwareBufferFrame} instances that are ready to be dequeued. */
  private final HardwareBufferPool pool;

  private final RenderingPacketConsumer<HardwareBufferFrame, SurfaceInfo> packetRenderer;
  private final Listener listener;

  // TODO: b/479134794 - This being nullable and mutable adds complexity, simplify this.
  private final PacketConsumerCaller<HardwareBufferFrame> output;

  private boolean isRenderSurfaceInfoSet;

  /**
   * Creates a new instance.
   *
   * @param packetRenderer The downstream {@link RenderingPacketConsumer} that will receive the
   *     frames.
   * @param listener The {@link Listener}.
   */
  public PacketConsumerHardwareBufferFrameQueue(
      RenderingPacketConsumer<HardwareBufferFrame, SurfaceInfo> packetRenderer, Listener listener) {
    pool =
        new HardwareBufferPool(
            CAPACITY,
            // TODO: b/484926720 - add executor to the Listener callbacks.
            /* errorExecutor= */ directExecutor(),
            /* errorCallback= */ e -> listener.onError(VideoFrameProcessingException.from(e)));
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
   * <p>Returns a buffer from the pool if one exists with matching {@link
   * HardwareBufferFrameQueue.FrameFormat}. If the pool is empty but capacity is available, a new
   * buffer is created. If the queue is at capacity, returns {@code null} and the most recent {@code
   * wakeupListener} will be notified when capacity is available.
   */
  @Override
  @Nullable
  public HardwareBufferFrame dequeue(
      HardwareBufferFrameQueue.FrameFormat format, Runnable wakeupListener) {
    @Nullable HardwareBufferWithFence bufferWithFence = pool.get(format, wakeupListener);
    if (bufferWithFence != null) {
      return new HardwareBufferFrame.Builder(
              bufferWithFence.hardwareBuffer,
              directExecutor(),
              (releaseFence) -> pool.recycle(bufferWithFence.hardwareBuffer, releaseFence))
          .setAcquireFence(bufferWithFence.acquireFence)
          .build();
    }
    return null;
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
                directExecutor(),
                (releaseFence) -> pool.recycle(checkNotNull(frame.hardwareBuffer), releaseFence))
            .setPresentationTimeUs(frame.presentationTimeUs)
            .setSequencePresentationTimeUs(frame.sequencePresentationTimeUs)
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
   * Releases the internal pool and the downstream caller.
   *
   * <p>This should be called when the pipeline is being shut down to ensure all asynchronous
   * resources are cleaned up.
   */
  @Override
  public void release() {
    output.release();
    pool.release();
  }

  private void sendDownstream(Packet<HardwareBufferFrame> packet) {
    checkNotNull(output).queuePacket(packet);
  }
}
