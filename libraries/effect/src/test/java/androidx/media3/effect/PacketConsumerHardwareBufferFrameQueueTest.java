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
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.hardware.HardwareBuffer;
import androidx.media3.common.Format;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Unit tests for {@link PacketConsumerHardwareBufferFrameQueue}. */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 28)
public final class PacketConsumerHardwareBufferFrameQueueTest {

  private static final int CAPACITY = 5;
  private static final int TEST_TIMEOUT_MS = 500;
  private static final PacketConsumerHardwareBufferFrameQueue.Listener NO_OP =
      new PacketConsumerHardwareBufferFrameQueue.Listener() {
        @Override
        public SurfaceInfo getRendererSurfaceInfo(Format format) {
          return null;
        }

        @Override
        public void onEndOfStream() {}

        @Override
        public void onError(VideoFrameProcessingException e) {}
      };
  private static final HardwareBufferFrameQueue.FrameFormat DEFAULT_FORMAT =
      new HardwareBufferFrameQueue.FrameFormat.Builder()
          .setWidth(100)
          .setHeight(100)
          .setPixelFormat(HardwareBuffer.RGBA_8888)
          .setUsageFlags(HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
          .build();

  @Test
  public void dequeue_returnsFrameThatReturnsToPool() {
    PacketConsumerHardwareBufferFrameQueue frameQueue =
        new PacketConsumerHardwareBufferFrameQueue(new FakeRenderingPacketConsumer<>(), NO_OP);

    HardwareBufferFrame frame = frameQueue.dequeue(DEFAULT_FORMAT, () -> {});
    assertThat(frame).isNotNull();
    HardwareBuffer buffer = frame.hardwareBuffer;
    frame.release(/* releaseFence= */ null);

    // Re-dequeue should return the same buffer instance, proving it was returned to the pool.
    HardwareBufferFrame frame2 = frameQueue.dequeue(DEFAULT_FORMAT, () -> {});
    assertThat(frame2.hardwareBuffer).isSameInstanceAs(buffer);
  }

  @Test
  public void queue_forwardsFrameDownstream() throws InterruptedException {
    CountDownLatch frameQueuedLatch = new CountDownLatch(1);
    FakeRenderingPacketConsumer<HardwareBufferFrame, SurfaceInfo> recordingConsumer =
        new FakeRenderingPacketConsumer<>();
    recordingConsumer.setOnQueue(
        unused -> {
          frameQueuedLatch.countDown();
          return null;
        });
    PacketConsumerHardwareBufferFrameQueue frameQueue =
        new PacketConsumerHardwareBufferFrameQueue(recordingConsumer, NO_OP);

    HardwareBufferFrame frame = frameQueue.dequeue(DEFAULT_FORMAT, () -> {});
    frameQueue.queue(frame);

    assertThat(frameQueuedLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(recordingConsumer.getQueuedPayloads()).hasSize(1);

    HardwareBufferFrame forwardedFrame = recordingConsumer.getQueuedPayloads().get(0);
    assertThat(forwardedFrame.hardwareBuffer).isSameInstanceAs(frame.hardwareBuffer);
    assertThat(forwardedFrame.format).isSameInstanceAs(frame.format);
    assertThat(forwardedFrame.internalFrame).isSameInstanceAs(frame.internalFrame);
    assertThat(forwardedFrame.releaseTimeNs).isEqualTo(frame.releaseTimeNs);
    assertThat(forwardedFrame.presentationTimeUs).isEqualTo(frame.presentationTimeUs);
    assertThat(forwardedFrame.getMetadata()).isSameInstanceAs(frame.getMetadata());
  }

  @Test
  public void releaseForwardedFrame_readdsBufferToPool() throws InterruptedException {
    CountDownLatch frameQueuedLatch = new CountDownLatch(1);
    FakeRenderingPacketConsumer<HardwareBufferFrame, SurfaceInfo> recordingConsumer =
        new FakeRenderingPacketConsumer<>();
    recordingConsumer.setOnQueue(
        unused -> {
          frameQueuedLatch.countDown();
          return null;
        });
    PacketConsumerHardwareBufferFrameQueue frameQueue =
        new PacketConsumerHardwareBufferFrameQueue(recordingConsumer, NO_OP);

    HardwareBufferFrame frame1 = frameQueue.dequeue(DEFAULT_FORMAT, () -> {});
    // Create a new frame with the same buffer and different release method.
    HardwareBufferFrame frameToQueue =
        new HardwareBufferFrame.Builder(
                checkNotNull(frame1).hardwareBuffer,
                /* releaseExecutor= */ directExecutor(),
                /* releaseCallback= */ (releaseFence) -> {
                  throw new AssertionError();
                })
            .build();

    frameQueue.queue(frameToQueue);

    assertThat(frameQueuedLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(recordingConsumer.getQueuedPayloads()).hasSize(1);

    HardwareBufferFrame forwardedFrame = recordingConsumer.getQueuedPayloads().get(0);
    forwardedFrame.release(/* releaseFence= */ null);
    HardwareBufferFrame frame2 = frameQueue.dequeue(DEFAULT_FORMAT, () -> {});

    assertThat(frame2).isNotNull();
    assertThat(frame2.hardwareBuffer).isSameInstanceAs(frame1.hardwareBuffer);
  }

  @Test
  public void downstreamError_reportedToErrorConsumer() throws InterruptedException {
    CountDownLatch errorReportedLatch = new CountDownLatch(1);
    AtomicReference<Exception> reportedError = new AtomicReference<>();
    RuntimeException cause = new RuntimeException("test error");
    FakeRenderingPacketConsumer<HardwareBufferFrame, SurfaceInfo> recordingConsumer =
        new FakeRenderingPacketConsumer<>();
    recordingConsumer.setOnQueue(
        unused -> {
          throw cause;
        });
    PacketConsumerHardwareBufferFrameQueue frameQueue =
        new PacketConsumerHardwareBufferFrameQueue(
            recordingConsumer,
            new PacketConsumerHardwareBufferFrameQueue.Listener() {
              @Override
              public SurfaceInfo getRendererSurfaceInfo(Format format)
                  throws VideoFrameProcessingException {
                return null;
              }

              @Override
              public void onEndOfStream() {}

              @Override
              public void onError(VideoFrameProcessingException e) {
                reportedError.set(e);
                errorReportedLatch.countDown();
              }
            });

    HardwareBufferFrame frame = frameQueue.dequeue(DEFAULT_FORMAT, () -> {});
    frameQueue.queue(frame);

    assertThat(errorReportedLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(reportedError.get()).hasCauseThat().isSameInstanceAs(cause);
  }

  @Test
  public void release_closesAllPoolBuffers() {
    PacketConsumerHardwareBufferFrameQueue frameQueue =
        new PacketConsumerHardwareBufferFrameQueue(new FakeRenderingPacketConsumer<>(), NO_OP);

    // Fill the pool with released buffers
    HardwareBufferFrame frame1 = frameQueue.dequeue(DEFAULT_FORMAT, () -> {});
    HardwareBuffer buffer1 = frame1.hardwareBuffer;
    frame1.release(/* releaseFence= */ null);

    HardwareBufferFrame frame2 = frameQueue.dequeue(DEFAULT_FORMAT, () -> {});
    HardwareBuffer buffer2 = frame2.hardwareBuffer;
    frame2.release(/* releaseFence= */ null);

    // Release the queue
    frameQueue.release();

    assertThat(buffer1.isClosed()).isTrue();
    assertThat(buffer2.isClosed()).isTrue();
  }

  @Test
  public void release_multipleCalls_doesNotThrow() {
    PacketConsumerHardwareBufferFrameQueue frameQueue =
        new PacketConsumerHardwareBufferFrameQueue(new FakeRenderingPacketConsumer<>(), NO_OP);

    // Fill the pool with released buffers
    HardwareBufferFrame frame1 = frameQueue.dequeue(DEFAULT_FORMAT, () -> {});
    HardwareBuffer buffer1 = frame1.hardwareBuffer;
    frame1.release(/* releaseFence= */ null);

    HardwareBufferFrame frame2 = frameQueue.dequeue(DEFAULT_FORMAT, () -> {});
    HardwareBuffer buffer2 = frame2.hardwareBuffer;
    frame2.release(/* releaseFence= */ null);

    frameQueue.release();
    frameQueue.release();
    frameQueue.release();

    assertThat(buffer1.isClosed()).isTrue();
    assertThat(buffer2.isClosed()).isTrue();
  }

  @Test
  public void returnBuffer_afterRelease_closesBuffer() {
    PacketConsumerHardwareBufferFrameQueue frameQueue =
        new PacketConsumerHardwareBufferFrameQueue(new FakeRenderingPacketConsumer<>(), NO_OP);
    HardwareBufferFrame frame = frameQueue.dequeue(DEFAULT_FORMAT, () -> {});
    HardwareBuffer buffer = frame.hardwareBuffer;

    frameQueue.release();

    // Returning a buffer after the frameQueue is released should close it
    frame.release(/* releaseFence= */ null);

    assertThat(buffer.isClosed()).isTrue();
  }

  @Test
  public void queue_setsRenderSurfaceInfo() throws InterruptedException {
    SurfaceInfo expectedSurfaceInfo =
        new SurfaceInfo(/* surface= */ null, /* width= */ 100, /* height= */ 100);
    CountDownLatch surfaceInfoSet = new CountDownLatch(1);
    AtomicReference<SurfaceInfo> receivedSurfaceInfo = new AtomicReference<>();
    FakeRenderingPacketConsumer<HardwareBufferFrame, SurfaceInfo> recordingConsumer =
        new FakeRenderingPacketConsumer<>();
    recordingConsumer.setOnRenderOutput(
        surfaceInfo -> {
          surfaceInfoSet.countDown();
          receivedSurfaceInfo.set(surfaceInfo);
          return null;
        });
    PacketConsumerHardwareBufferFrameQueue frameQueue =
        new PacketConsumerHardwareBufferFrameQueue(
            recordingConsumer,
            new PacketConsumerHardwareBufferFrameQueue.Listener() {
              @Override
              public SurfaceInfo getRendererSurfaceInfo(Format format) {
                return expectedSurfaceInfo;
              }

              @Override
              public void onEndOfStream() {}

              @Override
              public void onError(VideoFrameProcessingException e) {}
            });

    HardwareBufferFrame frame = frameQueue.dequeue(DEFAULT_FORMAT, () -> {});
    frameQueue.queue(frame);

    assertThat(surfaceInfoSet.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(receivedSurfaceInfo.get()).isSameInstanceAs(expectedSurfaceInfo);
  }

  @Test
  public void concurrentReleases_doNotCorruptPool() throws InterruptedException {
    ExecutorService testExecutor = Executors.newFixedThreadPool(4);
    PacketConsumerHardwareBufferFrameQueue frameQueue =
        new PacketConsumerHardwareBufferFrameQueue(new FakeRenderingPacketConsumer<>(), NO_OP);
    HardwareBufferFrame[] frames = new HardwareBufferFrame[CAPACITY];

    for (int j = 0; j < 100; j++) {
      for (int i = 0; i < CAPACITY; i++) {
        frames[i] = frameQueue.dequeue(DEFAULT_FORMAT, () -> {});
        assertThat(frames[i]).isNotNull();
      }

      CountDownLatch releaseLatch = new CountDownLatch(CAPACITY);
      for (HardwareBufferFrame frame : frames) {
        testExecutor.execute(
            () -> {
              frame.release(/* releaseFence= */ null);
              releaseLatch.countDown();
            });
      }
      assertThat(releaseLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    }
  }
}
