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
import static org.junit.Assert.assertThrows;

import android.hardware.HardwareBuffer;
import androidx.media3.common.util.Consumer;
import androidx.media3.test.utils.RecordingPacketConsumer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
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
  private static final Consumer<Exception> NO_OP_ERROR_CONSUMER = unused -> {};
  private static final HardwareBufferFrameQueue.FrameFormat DEFAULT_FORMAT =
      new HardwareBufferFrameQueue.FrameFormat(
          100, 100, HardwareBuffer.RGBA_8888, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);

  @Test
  public void dequeue_upToCapacity_createsNewFrames() {
    PacketConsumerHardwareBufferFrameQueue supplier =
        new PacketConsumerHardwareBufferFrameQueue(NO_OP_ERROR_CONSUMER);
    Set<HardwareBufferFrame> seenFrames = new HashSet<>();

    for (int i = 0; i < CAPACITY; i++) {
      HardwareBufferFrame frame = supplier.dequeue(DEFAULT_FORMAT, () -> {});

      assertThat(frame).isNotNull();
      assertThat(frame.hardwareBuffer).isNotNull();
      assertThat(frame.hardwareBuffer.getWidth()).isEqualTo(100);
      assertThat(frame.hardwareBuffer.getHeight()).isEqualTo(100);
      assertThat(frame.hardwareBuffer.getFormat()).isEqualTo(HardwareBuffer.RGBA_8888);
      assertThat(seenFrames).doesNotContain(frame);

      seenFrames.add(frame);
    }
  }

  @Test
  public void dequeue_afterRelease_reusesFrameForSameFormat() {
    PacketConsumerHardwareBufferFrameQueue supplier =
        new PacketConsumerHardwareBufferFrameQueue(NO_OP_ERROR_CONSUMER);

    HardwareBufferFrame frame1 = supplier.dequeue(DEFAULT_FORMAT, () -> {});
    HardwareBuffer buffer1 = frame1.hardwareBuffer;
    frame1.release();

    HardwareBufferFrame frame2 = supplier.dequeue(DEFAULT_FORMAT, () -> {});

    assertThat(frame2.hardwareBuffer).isSameInstanceAs(buffer1);
    frame2.release();
  }

  @Test
  public void dequeue_afterRelease_createsNewBufferForDifferentFormat() {
    PacketConsumerHardwareBufferFrameQueue supplier =
        new PacketConsumerHardwareBufferFrameQueue(NO_OP_ERROR_CONSUMER);
    HardwareBufferFrameQueue.FrameFormat format2 =
        new HardwareBufferFrameQueue.FrameFormat(
            200, 200, HardwareBuffer.RGBA_8888, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);

    HardwareBufferFrame frame1 = supplier.dequeue(DEFAULT_FORMAT, () -> {});
    HardwareBuffer buffer1 = frame1.hardwareBuffer;
    frame1.release();

    // Mismatched dimensions should trigger a new allocation and close the old buffer
    HardwareBufferFrame frame2 = supplier.dequeue(format2, () -> {});
    assertThat(frame2.hardwareBuffer).isNotSameInstanceAs(buffer1);
    assertThat(buffer1.isClosed()).isTrue();
    frame2.release();
  }

  @Test
  public void dequeue_clearsIncompatibleBuffersFromFront() {
    PacketConsumerHardwareBufferFrameQueue supplier =
        new PacketConsumerHardwareBufferFrameQueue(NO_OP_ERROR_CONSUMER);
    HardwareBufferFrameQueue.FrameFormat formatB =
        new HardwareBufferFrameQueue.FrameFormat(200, 200, HardwareBuffer.RGBA_8888, 0);

    HardwareBufferFrame frame1 = supplier.dequeue(DEFAULT_FORMAT, () -> {});
    HardwareBuffer buffer1 = frame1.hardwareBuffer;
    HardwareBufferFrame frame2 = supplier.dequeue(formatB, () -> {});
    HardwareBuffer buffer2 = frame2.hardwareBuffer;

    frame1.release();
    frame2.release();

    HardwareBufferFrame resultFrame = supplier.dequeue(formatB, () -> {});
    assertThat(resultFrame.hardwareBuffer).isSameInstanceAs(buffer2);
    assertThat(buffer1.isClosed()).isTrue();
    resultFrame.release();
  }

  @Test
  public void dequeue_atCapacity_returnsNull() {
    PacketConsumerHardwareBufferFrameQueue supplier =
        new PacketConsumerHardwareBufferFrameQueue(NO_OP_ERROR_CONSUMER);

    for (int i = 0; i < CAPACITY; i++) {
      assertThat(supplier.dequeue(DEFAULT_FORMAT, () -> {})).isNotNull();
    }
    assertThat(supplier.dequeue(DEFAULT_FORMAT, () -> {})).isNull();
  }

  @Test
  public void dequeue_atCapacity_notifiesWakeupListenerOnRelease() {
    PacketConsumerHardwareBufferFrameQueue supplier =
        new PacketConsumerHardwareBufferFrameQueue(NO_OP_ERROR_CONSUMER);
    HardwareBufferFrame[] frames = new HardwareBufferFrame[CAPACITY];
    for (int i = 0; i < CAPACITY; i++) {
      frames[i] = supplier.dequeue(DEFAULT_FORMAT, () -> {});
    }

    AtomicBoolean wakeupCalled = new AtomicBoolean(false);
    assertThat(supplier.dequeue(DEFAULT_FORMAT, () -> wakeupCalled.set(true))).isNull();
    assertThat(wakeupCalled.get()).isFalse();

    frames[0].release();
    assertThat(wakeupCalled.get()).isTrue();
  }

  @Test
  public void release_idempotent_doesNotDecrementCapacityTwice() {
    PacketConsumerHardwareBufferFrameQueue supplier =
        new PacketConsumerHardwareBufferFrameQueue(NO_OP_ERROR_CONSUMER);
    HardwareBufferFrame frame = supplier.dequeue(DEFAULT_FORMAT, () -> {});

    frame.release();
    frame.release();

    for (int i = 0; i < CAPACITY; i++) {
      assertThat(supplier.dequeue(DEFAULT_FORMAT, () -> {})).isNotNull();
    }
    assertThat(supplier.dequeue(DEFAULT_FORMAT, () -> {})).isNull();
  }

  @Test
  public void release_closedBuffer_stillDecrementsCapacity() {
    PacketConsumerHardwareBufferFrameQueue supplier =
        new PacketConsumerHardwareBufferFrameQueue(NO_OP_ERROR_CONSUMER);
    HardwareBufferFrame frame = supplier.dequeue(DEFAULT_FORMAT, () -> {});

    frame.hardwareBuffer.close();
    frame.release();

    for (int i = 0; i < CAPACITY; i++) {
      assertThat(supplier.dequeue(DEFAULT_FORMAT, () -> {})).isNotNull();
    }
    assertThat(supplier.dequeue(DEFAULT_FORMAT, () -> {})).isNull();
  }

  @Test
  public void setOutput_calledTwice_throwsException() {
    PacketConsumerHardwareBufferFrameQueue supplier =
        new PacketConsumerHardwareBufferFrameQueue(NO_OP_ERROR_CONSUMER);
    RecordingPacketConsumer<HardwareBufferFrame> output = new RecordingPacketConsumer<>();

    supplier.setOutput(output);
    assertThrows(IllegalStateException.class, () -> supplier.setOutput(output));
  }

  @Test
  public void queue_beforeSetOutput_throwsException() {
    PacketConsumerHardwareBufferFrameQueue supplier =
        new PacketConsumerHardwareBufferFrameQueue(NO_OP_ERROR_CONSUMER);
    HardwareBufferFrame frame = supplier.dequeue(DEFAULT_FORMAT, () -> {});

    assertThrows(IllegalStateException.class, () -> supplier.queue(frame));
  }

  @Test
  public void queue_forwardsFrameDownstream() throws InterruptedException {
    CountDownLatch frameQueuedLatch = new CountDownLatch(1);
    PacketConsumerHardwareBufferFrameQueue supplier =
        new PacketConsumerHardwareBufferFrameQueue(NO_OP_ERROR_CONSUMER);
    RecordingPacketConsumer<HardwareBufferFrame> recordingConsumer =
        new RecordingPacketConsumer<>();
    recordingConsumer.setOnQueue(
        unused -> {
          frameQueuedLatch.countDown();
          return null;
        });
    supplier.setOutput(recordingConsumer);

    HardwareBufferFrame frame = supplier.dequeue(DEFAULT_FORMAT, () -> {});
    supplier.queue(frame);

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
    PacketConsumerHardwareBufferFrameQueue supplier =
        new PacketConsumerHardwareBufferFrameQueue(NO_OP_ERROR_CONSUMER);
    RecordingPacketConsumer<HardwareBufferFrame> recordingConsumer =
        new RecordingPacketConsumer<>();
    recordingConsumer.setOnQueue(
        unused -> {
          frameQueuedLatch.countDown();
          return null;
        });
    supplier.setOutput(recordingConsumer);

    HardwareBufferFrame frame1 = supplier.dequeue(DEFAULT_FORMAT, () -> {});
    // Create a new frame with the same buffer and different release method.
    HardwareBufferFrame frameToQueue =
        new HardwareBufferFrame.Builder(
                checkNotNull(frame1).hardwareBuffer,
                /* releaseExecutor= */ directExecutor(),
                /* releaseCallback= */ () -> {
                  throw new AssertionError();
                })
            .build();

    supplier.queue(frameToQueue);

    assertThat(frameQueuedLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(recordingConsumer.getQueuedPayloads()).hasSize(1);

    HardwareBufferFrame forwardedFrame = recordingConsumer.getQueuedPayloads().get(0);
    forwardedFrame.release();
    HardwareBufferFrame frame2 = supplier.dequeue(DEFAULT_FORMAT, () -> {});

    assertThat(frame2).isNotNull();
    assertThat(frame2.hardwareBuffer).isSameInstanceAs(frame1.hardwareBuffer);
  }

  @Test
  public void downstreamError_reportedToErrorConsumer() throws InterruptedException {
    CountDownLatch errorReportedLatch = new CountDownLatch(1);
    AtomicReference<Exception> reportedError = new AtomicReference<>();
    PacketConsumerHardwareBufferFrameQueue supplier =
        new PacketConsumerHardwareBufferFrameQueue(
            e -> {
              reportedError.set(e);
              errorReportedLatch.countDown();
            });
    RuntimeException cause = new RuntimeException("test error");
    RecordingPacketConsumer<HardwareBufferFrame> recordingConsumer =
        new RecordingPacketConsumer<>();
    recordingConsumer.setOnQueue(
        unused -> {
          throw cause;
        });
    supplier.setOutput(recordingConsumer);

    HardwareBufferFrame frame = supplier.dequeue(DEFAULT_FORMAT, () -> {});
    supplier.queue(frame);

    assertThat(errorReportedLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(reportedError.get()).isSameInstanceAs(cause);
  }
}
