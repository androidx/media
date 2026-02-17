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
import androidx.media3.common.ColorInfo;
import androidx.media3.common.util.ExperimentalApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
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

    /** A builder for {@link FrameFormat} instances. */
    public static final class Builder {
      private int width;
      private int height;
      private int pixelFormat;
      private long usageFlags;
      private ColorInfo colorInfo;

      /**
       * Creates a new builder with default values.
       *
       * <p>Width and height default to 0, {@code pixelFormat} defaults to {@link
       * HardwareBuffer#RGBA_8888}, {@code usageFlags} defaults to {@link
       * HardwareBuffer#USAGE_GPU_SAMPLED_IMAGE}, and {@link ColorInfo} defaults to {@link
       * ColorInfo#SDR_BT709_LIMITED}.
       */
      public Builder() {
        pixelFormat = HardwareBuffer.RGBA_8888;
        usageFlags = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE;
        colorInfo = ColorInfo.SDR_BT709_LIMITED;
      }

      /**
       * Sets the width of the buffer in pixels.
       *
       * @param width The width.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setWidth(int width) {
        this.width = width;
        return this;
      }

      /**
       * Sets the height of the buffer in pixels.
       *
       * @param height The height.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setHeight(int height) {
        this.height = height;
        return this;
      }

      /**
       * Sets the {@linkplain HardwareBuffer#getFormat() format} of the {@link HardwareBuffer}.
       *
       * @param pixelFormat The pixel format.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setPixelFormat(int pixelFormat) {
        this.pixelFormat = pixelFormat;
        return this;
      }

      /**
       * Sets the {@linkplain HardwareBuffer#getUsage() usage flags} of the {@link HardwareBuffer}.
       *
       * @param usageFlags The usage flags.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setUsageFlags(long usageFlags) {
        this.usageFlags = usageFlags;
        return this;
      }

      /**
       * Sets the {@link ColorInfo}.
       *
       * @param colorInfo The {@link ColorInfo}.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setColorInfo(ColorInfo colorInfo) {
        this.colorInfo = colorInfo;
        return this;
      }

      /** Builds the {@link FrameFormat} instance. */
      public FrameFormat build() {
        return new FrameFormat(width, height, pixelFormat, usageFlags, colorInfo);
      }
    }

    public final int width;
    public final int height;
    public final int pixelFormat;
    public final long usageFlags;
    public final ColorInfo colorInfo;

    private FrameFormat(
        int width, int height, int pixelFormat, long usageFlags, ColorInfo colorInfo) {
      this.width = width;
      this.height = height;
      this.pixelFormat = pixelFormat;
      this.usageFlags = usageFlags;
      this.colorInfo = colorInfo;
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
          && usageFlags == that.usageFlags
          && Objects.equals(colorInfo, that.colorInfo);
    }

    @Override
    public int hashCode() {
      return Objects.hash(width, height, pixelFormat, usageFlags, colorInfo);
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
          + ", colorInfo="
          + colorInfo
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
   * <p>Implementations may override {@link Frame#release(SyncFenceCompat)} of the queued frame.
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

  /** Releases all resources associated with this instance. */
  void release();
}
