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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import android.hardware.HardwareBuffer;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.util.ExperimentalApi;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * A pool of {@link HardwareBuffer}s.
 *
 * <p>This class manages a fixed-capacity pool of {@link HardwareBuffer}s that can be dequeued with
 * a specific {@link Format} and usage flags. Dequeued buffers should be returned to the pool using
 * {@link #recycle(HardwareBuffer, SyncFenceWrapper)} for reuse.
 *
 * <p>Methods can be called from any thread.
 */
@ExperimentalApi // TODO: b/498176910 - Remove once Frame is production ready.
@RequiresApi(26)
public final class HardwareBufferPool {

  /** Wrapper around a {@link HardwareBuffer} and an {@linkplain SyncFenceWrapper acquireFence}. */
  public static final class HardwareBufferWithFence {
    public final HardwareBuffer hardwareBuffer;

    /**
     * The fence that must be waited on before writing to the {@link #hardwareBuffer}. If {@code
     * null} the buffer can be accessed immediately.
     */
    @Nullable public final SyncFenceWrapper acquireFence;

    /** Creates a new instance. */
    public HardwareBufferWithFence(
        HardwareBuffer hardwareBuffer, @Nullable SyncFenceWrapper acquireFence) {
      this.hardwareBuffer = hardwareBuffer;
      this.acquireFence = acquireFence;
    }
  }

  private final int capacity;
  private final Object lock = new Object();

  @GuardedBy("lock")
  private final Queue<HardwareBufferWithFence> pool;

  @GuardedBy("lock")
  private int allocatedBufferCount;

  @GuardedBy("lock")
  private boolean isReleased;

  @GuardedBy("lock")
  @Nullable
  private Runnable wakeupListener;

  /**
   * Creates a new instance.
   *
   * @param capacity The maximum number of buffers allowed to exist (pooled + in-flight).
   */
  public HardwareBufferPool(int capacity) {
    this.capacity = capacity;
    this.pool = new ArrayDeque<>(capacity);
  }

  /**
   * Attempts to fetch a {@link HardwareBufferWithFence} matching the specified {@code format} and
   * {@code usageFlags}.
   *
   * <p>If the pool has reached its capacity and no buffers are available for reuse, this method
   * returns {@code null}. In this case, the {@code wakeupListener} will be invoked when a buffer is
   * eventually returned to the pool.
   *
   * <p>If this method is called multiple times without returning a buffer, only the most recent
   * {@code wakeupListener} is guaranteed to be invoked.
   *
   * @param format The required {@link Format} for the dequeued buffer.
   * @param usageFlags The requested usage flags for the buffer.
   * @param wakeupListener A callback to notify the caller when a buffer becomes available.
   * @return A {@link HardwareBufferWithFence}, or {@code null} if the pool is currently full.
   */
  @Nullable
  public HardwareBufferWithFence get(
      Format format, @Frame.Usage long usageFlags, Runnable wakeupListener) {
    synchronized (lock) {
      if (isReleased) {
        return null;
      }
      @Nullable HardwareBufferWithFence hardwareBufferWithFence;
      while ((hardwareBufferWithFence = pool.poll()) != null) {
        HardwareBuffer buffer = hardwareBufferWithFence.hardwareBuffer;
        if (isCompatible(buffer, format, usageFlags)) {
          return hardwareBufferWithFence;
        } else {
          buffer.close();
          closeFence(hardwareBufferWithFence.acquireFence);
          allocatedBufferCount--;
        }
      }
      if (allocatedBufferCount >= capacity) {
        this.wakeupListener = wakeupListener;
        return null;
      }
      allocatedBufferCount++;
    }
    return createNewBufferWithFence(format, usageFlags);
  }

  /**
   * Returns a {@link HardwareBuffer} to the pool.
   *
   * @param buffer The {@link HardwareBuffer} to return.
   * @param fence An optional {@link SyncFenceWrapper} that must be reached before the buffer can be
   *     reused.
   * @throws IllegalArgumentException if the {@code buffer} is {@linkplain HardwareBuffer#isClosed()
   *     closed}.
   */
  public void recycle(HardwareBuffer buffer, @Nullable SyncFenceWrapper fence) {
    checkArgument(!buffer.isClosed());
    @Nullable Runnable listenerToRun = null;
    // TODO: b/479415385 - Do not close the fence here, reuse it as the acquire fence for this
    // buffer.
    closeFence(fence);
    synchronized (lock) {
      if (isReleased) {
        buffer.close();
        closeFence(fence);
        return;
      }
      // Ensure the same buffer is not added to the pool multiple times.
      if (!poolContainsBuffer(buffer)) {
        // TODO: b/479415385 - Handle combining fences if the same buffer is returned multiple
        // times.
        pool.add(new HardwareBufferWithFence(buffer, /* acquireFence= */ null));
        if (wakeupListener != null) {
          listenerToRun = wakeupListener;
          wakeupListener = null;
        }
      }
    }
    if (listenerToRun != null) {
      listenerToRun.run();
    }
  }

  /**
   * Releases the pool and all its buffers.
   *
   * <p>This should be called when the pool is no longer needed to ensure all resources are cleaned
   * up.
   */
  public void release() {
    synchronized (lock) {
      if (isReleased) {
        return;
      }
      isReleased = true;
      @Nullable HardwareBufferWithFence hardwareBufferWithFence;
      while ((hardwareBufferWithFence = pool.poll()) != null) {
        hardwareBufferWithFence.hardwareBuffer.close();
        closeFence(hardwareBufferWithFence.acquireFence);
      }
    }
  }

  private static HardwareBufferWithFence createNewBufferWithFence(
      Format format, @Frame.Usage long usageFlags) {
    HardwareBuffer buffer =
        HardwareBuffer.create(
            format.width,
            format.height,
            getPixelFormat(format),
            /* layers= */ 1,
            adjustUsageFlags(usageFlags));
    checkState(!buffer.isClosed());
    return new HardwareBufferWithFence(buffer, /* acquireFence= */ null);
  }

  @GuardedBy("lock")
  private boolean poolContainsBuffer(HardwareBuffer target) {
    for (HardwareBufferWithFence hardwareBufferWithFence : pool) {
      if (hardwareBufferWithFence.hardwareBuffer == target) {
        return true;
      }
    }
    return false;
  }

  private void closeFence(@Nullable SyncFenceWrapper fence) {
    if (fence != null) {
      fence.close();
    }
  }

  private static boolean isCompatible(
      HardwareBuffer buffer, Format format, @Frame.Usage long usageFlags) {
    return buffer.getWidth() == format.width
        && buffer.getHeight() == format.height
        && buffer.getFormat() == getPixelFormat(format)
        && buffer.getUsage() == adjustUsageFlags(usageFlags);
  }

  private static long adjustUsageFlags(long requestedUsageFlags) {
    // Ensure usage flags required by the consumer of this buffer are added.
    return requestedUsageFlags | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE;
  }

  private static int getPixelFormat(Format format) {
    // TODO: b/498547782 - Add pixel format to media3 Format.
    return ColorInfo.isTransferHdr(format.colorInfo)
        ? HardwareBuffer.RGBA_1010102
        : HardwareBuffer.RGBA_8888;
  }
}
