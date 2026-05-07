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
package androidx.media3.transformer;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.Util;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.effect.HardwareBufferFrameQueue;
import androidx.media3.effect.PacketConsumer;
import androidx.media3.effect.PacketConsumerCaller;
import androidx.media3.effect.PacketConsumerUtil;
import androidx.media3.effect.RenderingPacketConsumer;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Adapts a {@link RenderingPacketConsumer} to the {@link FrameProcessor} interface. */
@RequiresApi(26)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
public final class RenderingPacketConsumerToFrameProcessorAdapter implements FrameProcessor {

  private static final long RELEASE_TIMEOUT_MS = 1000L;
  private final RenderingPacketConsumer<
          ImmutableList<HardwareBufferFrame>, HardwareBufferFrameQueue>
      packetConsumer;
  private final PacketConsumerCaller<ImmutableList<HardwareBufferFrame>> packetConsumerCaller;
  private final ExecutorService executorService;

  private volatile @MonotonicNonNull Exception pendingException;
  @Nullable private volatile Runnable pendingWakeupListener;
  @Nullable private volatile Executor pendingWakeupExecutor;

  @SuppressWarnings("nullness:methodref.receiver.bound.invalid") // "this" reference
  public RenderingPacketConsumerToFrameProcessorAdapter(
      RenderingPacketConsumer<ImmutableList<HardwareBufferFrame>, HardwareBufferFrameQueue>
          packetConsumer) {
    this.packetConsumer = packetConsumer;
    this.packetConsumer.setErrorConsumer(this::onException);
    this.executorService =
        Util.newSingleThreadExecutor("RenderingPacketConsumerToFrameProcessorAdapter::Thread");
    this.packetConsumerCaller =
        PacketConsumerCaller.create(packetConsumer, executorService, this::onException);
    this.packetConsumerCaller.run();
  }

  @Override
  public boolean queue(
      List<AsyncFrame> frames,
      Executor listenerExecutor,
      Runnable wakeupListener,
      FrameCompletionListener completionListener)
      throws VideoFrameProcessingException {

    Exception exception = pendingException;
    if (exception != null) {
      throw VideoFrameProcessingException.from(exception);
    }

    this.pendingWakeupListener = wakeupListener;
    this.pendingWakeupExecutor = listenerExecutor;

    ImmutableList.Builder<HardwareBufferFrame> frameListBuilder = ImmutableList.builder();
    for (AsyncFrame asyncFrame : frames) {
      checkState(
          asyncFrame.frame instanceof DefaultHardwareBufferFrame,
          "Expected DefaultHardwareBufferFrame");
      DefaultHardwareBufferFrame hardwareBufferFrame =
          (DefaultHardwareBufferFrame) asyncFrame.frame;

      HardwareBufferFrame.Builder effectFrameBuilder =
          new HardwareBufferFrame.Builder(
              hardwareBufferFrame.getHardwareBuffer(),
              directExecutor(),
              releaseFence ->
                  listenerExecutor.execute(
                      () -> completionListener.onFrameProcessed(asyncFrame.frame, releaseFence)));

      effectFrameBuilder.setPresentationTimeUs(hardwareBufferFrame.getContentTimeUs());

      effectFrameBuilder.setSequencePresentationTimeUs(hardwareBufferFrame.getContentTimeUs());
      // When encoding, releaseTime and contentTime are the same.
      effectFrameBuilder.setReleaseTimeNs(hardwareBufferFrame.getContentTimeUs() * 1000);

      effectFrameBuilder.setAcquireFence(asyncFrame.acquireFence);
      effectFrameBuilder.setFormat(hardwareBufferFrame.getFormat());
      effectFrameBuilder.setInternalFrame(hardwareBufferFrame.getInternalImage());

      frameListBuilder.add(effectFrameBuilder.build());
    }

    packetConsumerCaller.queuePacket(PacketConsumer.Packet.Companion.of(frameListBuilder.build()));

    return true;
  }

  @Override
  public void signalEndOfStream() {
    packetConsumerCaller.queueEndOfStream();
  }

  @Override
  public void close() {
    packetConsumerCaller.release();
    try {
      PacketConsumerUtil.release(packetConsumer, executorService)
          .get(RELEASE_TIMEOUT_MS, MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Failed to release packet consumer", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("Failed to release packet consumer", e);
    } finally {
      executorService.shutdown();
    }
  }

  private void onException(Exception exception) {
    if (pendingException == null) {
      pendingException = exception;
      Runnable wakeupListener = pendingWakeupListener;
      Executor wakeupExecutor = pendingWakeupExecutor;
      if (wakeupListener != null && wakeupExecutor != null) {
        wakeupExecutor.execute(wakeupListener);
      }
    }
  }
}
