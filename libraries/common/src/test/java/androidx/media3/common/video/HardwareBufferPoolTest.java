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
package androidx.media3.common.video;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;

import android.hardware.HardwareBuffer;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Unit tests for {@link HardwareBufferPool}. */
@RunWith(AndroidJUnit4.class)
@Config(minSdk = 26)
public final class HardwareBufferPoolTest {

  private static final int CAPACITY = 5;
  private static final int TEST_TIMEOUT_MS = 500;
  private static final Format DEFAULT_FORMAT =
      new Format.Builder()
          .setWidth(100)
          .setHeight(100)
          .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
          .build();
  private static final long DEFAULT_USAGE = Frame.USAGE_GPU_SAMPLED_IMAGE;

  @Test
  public void get_upToCapacity_createsNewFrames() {
    HardwareBufferPool pool = new HardwareBufferPool(CAPACITY);
    Set<HardwareBuffer> seenBuffers = new HashSet<>();

    for (int i = 0; i < CAPACITY; i++) {
      HardwareBufferPool.HardwareBufferWithFence result =
          pool.get(DEFAULT_FORMAT, DEFAULT_USAGE, () -> {});

      assertThat(result).isNotNull();
      HardwareBuffer buffer = result.hardwareBuffer;
      assertThat(buffer).isNotNull();
      assertThat(buffer.getWidth()).isEqualTo(100);
      assertThat(buffer.getHeight()).isEqualTo(100);
      assertThat(buffer.getFormat()).isEqualTo(HardwareBuffer.RGBA_8888);
      assertThat(seenBuffers).doesNotContain(buffer);

      seenBuffers.add(buffer);
    }
  }

  @Test
  public void get_afterRecycle_reusesBufferForSameFormat() {
    HardwareBufferPool pool = new HardwareBufferPool(CAPACITY);

    HardwareBufferPool.HardwareBufferWithFence result1 =
        pool.get(DEFAULT_FORMAT, DEFAULT_USAGE, () -> {});
    HardwareBuffer buffer1 = result1.hardwareBuffer;
    pool.recycle(buffer1, result1.acquireFence);

    HardwareBufferPool.HardwareBufferWithFence result2 =
        pool.get(DEFAULT_FORMAT, DEFAULT_USAGE, () -> {});

    assertThat(result2.hardwareBuffer).isSameInstanceAs(buffer1);
  }

  @Test
  public void get_afterRecycle_createsNewBufferForDifferentFormat() {
    HardwareBufferPool pool = new HardwareBufferPool(CAPACITY);
    Format format2 =
        new Format.Builder()
            .setWidth(200)
            .setHeight(200)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();

    HardwareBufferPool.HardwareBufferWithFence result1 =
        pool.get(DEFAULT_FORMAT, DEFAULT_USAGE, () -> {});
    HardwareBuffer buffer1 = result1.hardwareBuffer;
    pool.recycle(buffer1, result1.acquireFence);

    // Mismatched dimensions should trigger a new allocation and close the old buffer
    HardwareBufferPool.HardwareBufferWithFence result2 = pool.get(format2, DEFAULT_USAGE, () -> {});
    assertThat(result2.hardwareBuffer).isNotSameInstanceAs(buffer1);
    assertThat(buffer1.isClosed()).isTrue();
  }

  @Test
  public void get_differentDimensions_clearsIncompatibleBuffersFromFront() {
    HardwareBufferPool pool = new HardwareBufferPool(CAPACITY);
    Format format2 =
        new Format.Builder()
            .setWidth(200)
            .setHeight(200)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();

    HardwareBufferPool.HardwareBufferWithFence result1 =
        pool.get(DEFAULT_FORMAT, DEFAULT_USAGE, () -> {});
    HardwareBufferPool.HardwareBufferWithFence result2 = pool.get(format2, DEFAULT_USAGE, () -> {});
    HardwareBuffer buffer1 = result1.hardwareBuffer;
    HardwareBuffer buffer2 = result2.hardwareBuffer;

    pool.recycle(buffer1, result1.acquireFence);
    pool.recycle(buffer2, result2.acquireFence);

    HardwareBufferPool.HardwareBufferWithFence resultFrame =
        pool.get(format2, DEFAULT_USAGE, () -> {});
    assertThat(resultFrame.hardwareBuffer).isSameInstanceAs(buffer2);
    assertThat(buffer1.isClosed()).isTrue();
  }

  @Test
  public void get_differentColorInfo_clearsIncompatibleBuffersFromFront() {
    HardwareBufferPool pool = new HardwareBufferPool(CAPACITY);
    Format format2 =
        DEFAULT_FORMAT
            .buildUpon()
            .setColorInfo(
                new ColorInfo.Builder()
                    .setColorSpace(C.COLOR_SPACE_BT2020)
                    .setColorRange(C.COLOR_RANGE_LIMITED)
                    .setColorTransfer(C.COLOR_TRANSFER_HLG)
                    .build())
            .build();

    HardwareBufferPool.HardwareBufferWithFence result1 =
        pool.get(DEFAULT_FORMAT, DEFAULT_USAGE, () -> {});
    HardwareBufferPool.HardwareBufferWithFence result2 = pool.get(format2, DEFAULT_USAGE, () -> {});
    HardwareBuffer buffer1 = result1.hardwareBuffer;
    HardwareBuffer buffer2 = result2.hardwareBuffer;

    pool.recycle(buffer1, result1.acquireFence);
    pool.recycle(buffer2, result2.acquireFence);

    HardwareBufferPool.HardwareBufferWithFence resultFrame =
        pool.get(format2, DEFAULT_USAGE, () -> {});
    assertThat(resultFrame.hardwareBuffer).isSameInstanceAs(buffer2);
    assertThat(buffer1.isClosed()).isTrue();
  }

  @Test
  public void get_atCapacity_returnsNull() {
    HardwareBufferPool pool = new HardwareBufferPool(CAPACITY);

    for (int i = 0; i < CAPACITY; i++) {
      assertThat(pool.get(DEFAULT_FORMAT, DEFAULT_USAGE, () -> {})).isNotNull();
    }
    assertThat(pool.get(DEFAULT_FORMAT, DEFAULT_USAGE, () -> {})).isNull();
  }

  @Test
  public void get_atCapacity_notifiesWakeupListenerOnReturn() {
    HardwareBufferPool pool = new HardwareBufferPool(CAPACITY);
    HardwareBufferPool.HardwareBufferWithFence[] results =
        new HardwareBufferPool.HardwareBufferWithFence[CAPACITY];
    for (int i = 0; i < CAPACITY; i++) {
      results[i] = pool.get(DEFAULT_FORMAT, DEFAULT_USAGE, () -> {});
    }

    AtomicBoolean wakeupCalled = new AtomicBoolean(false);
    assertThat(pool.get(DEFAULT_FORMAT, DEFAULT_USAGE, () -> wakeupCalled.set(true))).isNull();
    assertThat(wakeupCalled.get()).isFalse();

    pool.recycle(results[0].hardwareBuffer, results[0].acquireFence);
    assertThat(wakeupCalled.get()).isTrue();
  }

  @Test
  public void recycle_onAlreadyRecycledBuffer_doesNotDecrementCapacityTwice() {
    HardwareBufferPool pool = new HardwareBufferPool(CAPACITY);
    HardwareBufferPool.HardwareBufferWithFence result =
        pool.get(DEFAULT_FORMAT, DEFAULT_USAGE, () -> {});

    pool.recycle(result.hardwareBuffer, /* fence= */ null);
    pool.recycle(result.hardwareBuffer, /* fence= */ null);

    for (int i = 0; i < CAPACITY; i++) {
      assertThat(pool.get(DEFAULT_FORMAT, DEFAULT_USAGE, () -> {})).isNotNull();
    }
    assertThat(pool.get(DEFAULT_FORMAT, DEFAULT_USAGE, () -> {})).isNull();
  }

  @Test
  public void recycle_closedBuffer_throwsIllegalArgumentException() {
    HardwareBufferPool pool = new HardwareBufferPool(CAPACITY);
    HardwareBufferPool.HardwareBufferWithFence result =
        pool.get(DEFAULT_FORMAT, DEFAULT_USAGE, () -> {});

    result.hardwareBuffer.close();

    assertThrows(
        IllegalArgumentException.class,
        () -> pool.recycle(result.hardwareBuffer, /* fence= */ null));
  }

  @Test
  public void release_closesAllPoolBuffers() {
    HardwareBufferPool pool = new HardwareBufferPool(CAPACITY);

    // Fill the pool with returned frames
    HardwareBufferPool.HardwareBufferWithFence result1 =
        pool.get(DEFAULT_FORMAT, DEFAULT_USAGE, () -> {});
    HardwareBuffer buffer1 = result1.hardwareBuffer;
    pool.recycle(buffer1, result1.acquireFence);

    HardwareBufferPool.HardwareBufferWithFence result2 =
        pool.get(DEFAULT_FORMAT, DEFAULT_USAGE, () -> {});
    HardwareBuffer buffer2 = result2.hardwareBuffer;
    pool.recycle(buffer2, result2.acquireFence);

    // Release the pool
    pool.release();

    assertThat(buffer1.isClosed()).isTrue();
    assertThat(buffer2.isClosed()).isTrue();
  }

  @Test
  public void recycle_afterRelease_closesBuffer() {
    HardwareBufferPool pool = new HardwareBufferPool(CAPACITY);
    HardwareBufferPool.HardwareBufferWithFence result =
        pool.get(DEFAULT_FORMAT, DEFAULT_USAGE, () -> {});
    HardwareBuffer buffer = result.hardwareBuffer;

    pool.release();

    // Returning a frame after the pool is released should close its buffer
    pool.recycle(buffer, result.acquireFence);

    assertThat(buffer.isClosed()).isTrue();
  }

  @Test
  public void recycle_concurrentCalls_doNotCorruptPool()
      throws InterruptedException, ExecutionException, TimeoutException {
    try (ExecutorService testExecutor = Executors.newFixedThreadPool(4)) {
      HardwareBufferPool pool = new HardwareBufferPool(CAPACITY);
      HardwareBufferPool.HardwareBufferWithFence[] results =
          new HardwareBufferPool.HardwareBufferWithFence[CAPACITY];

      for (int j = 0; j < 100; j++) {
        for (int i = 0; i < CAPACITY; i++) {
          results[i] = pool.get(DEFAULT_FORMAT, DEFAULT_USAGE, () -> {});
          assertThat(results[i]).isNotNull();
        }

        CountDownLatch returnLatch = new CountDownLatch(CAPACITY);
        for (HardwareBufferPool.HardwareBufferWithFence result : results) {
          testExecutor.execute(
              () -> {
                pool.recycle(result.hardwareBuffer, result.acquireFence);
                returnLatch.countDown();
              });
        }
        assertThat(returnLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
      }
    }
  }
}
