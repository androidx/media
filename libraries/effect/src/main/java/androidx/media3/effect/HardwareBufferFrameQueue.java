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

import android.hardware.HardwareBuffer;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.ExperimentalApi;
import java.util.Objects;

/**
 * A provider for {@link HardwareBufferFrame} instances that manages allocating the underlying
 * {@link HardwareBuffer} instances, and forwards frames downstream for processing.
 */
@RequiresApi(26)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
public interface HardwareBufferFrameQueue {

  /** Defines the configuration parameters required for a {@link HardwareBuffer}. */
  final class FrameFormat {
    public final int width;
    public final int height;
    public final int pixelFormat;
    public final long usageFlags;

    /**
     * Creates a new {@link FrameFormat} instance.
     *
     * @param width The width of the buffer in pixels.
     * @param height The height of the buffer in pixels.
     * @param pixelFormat The {@linkplain HardwareBuffer#getFormat() format} of the {@link
     *     HardwareBuffer}.
     * @param usageFlags The {@linkplain HardwareBuffer#getUsage() usage flags} of the {@link
     *     HardwareBuffer}.
     */
    public FrameFormat(int width, int height, int pixelFormat, long usageFlags) {
      this.width = width;
      this.height = height;
      this.pixelFormat = pixelFormat;
      this.usageFlags = usageFlags;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      FrameFormat that = (FrameFormat) o;
      return width == that.width
          && height == that.height
          && pixelFormat == that.pixelFormat
          && usageFlags == that.usageFlags;
    }

    @Override
    public int hashCode() {
      return Objects.hash(width, height, pixelFormat, usageFlags);
    }

    @Override
    public String toString() {
      return "FrameFormat{"
          + "width="
          + width
          + ", height="
          + height
          + ", pixelFormat="
          + pixelFormat
          + ", usageFlags="
          + usageFlags
          + '}';
    }
  }

  /**
   * Attempts to dequeue a {@link HardwareBufferFrame} matching the specified {@code format}.
   *
   * <p>If the queue has reached its capacity and no buffers are available for reuse, this method
   * returns {@code null}. In this case, the {@code wakeupListener} will be invoked when a buffer is
   * eventually returned to the pool.
   *
   * <p>If this method is called multiple times without returning a frame, only the most recent
   * {@code wakeupListener} is guaranteed to be invoked.
   *
   * @param format The required format for the dequeued buffer.
   * @param wakeupListener A callback to notify the caller when a buffer becomes available.
   * @return A {@link HardwareBufferFrame}, or {@code null} if the queue is currently full.
   */
  @Nullable
  HardwareBufferFrame dequeue(FrameFormat format, Runnable wakeupListener);

  /**
   * Queues a {@link HardwareBufferFrame} for consumption by the downstream component.
   *
   * <p>Implementations may override {@link HardwareBufferFrame#release()} of the queued frame.
   *
   * <p>The implementation is responsible for ensuring the buffer is correctly reused or released
   * once the downstream component has finished processing it.
   *
   * @param frame The frame to be processed.
   */
  void queue(HardwareBufferFrame frame);

  /**
   * Signals that no more frames will be queued.
   *
   * <p>This propagates an end-of-stream signal to the downstream consumer.
   */
  void signalEndOfStream();
}
