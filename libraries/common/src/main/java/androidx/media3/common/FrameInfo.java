/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.common;

import static androidx.media3.common.util.Assertions.checkArgument;

import androidx.media3.common.util.UnstableApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/** Value class specifying information about a decoded video frame. */
@UnstableApi
public class FrameInfo {

  /** A builder for {@link FrameInfo} instances. */
  public static final class Builder {

    private int width;
    private int height;
    private float pixelWidthHeightRatio;
    private long streamOffsetUs;
    private long offsetToAddUs;

    /**
     * Creates an instance with default values.
     *
     * @param width The frame width, in pixels.
     * @param height The frame height, in pixels.
     */
    public Builder(int width, int height) {
      this.width = width;
      this.height = height;
      pixelWidthHeightRatio = 1;
    }

    /** Creates an instance with the values of the provided {@link FrameInfo}. */
    public Builder(FrameInfo frameInfo) {
      width = frameInfo.width;
      height = frameInfo.height;
      pixelWidthHeightRatio = frameInfo.pixelWidthHeightRatio;
      streamOffsetUs = frameInfo.streamOffsetUs;
      offsetToAddUs = frameInfo.offsetToAddUs;
    }

    /** Sets the frame width, in pixels. */
    @CanIgnoreReturnValue
    public Builder setWidth(int width) {
      this.width = width;
      return this;
    }

    /** Sets the frame height, in pixels. */
    @CanIgnoreReturnValue
    public Builder setHeight(int height) {
      this.height = height;
      return this;
    }

    /**
     * Sets the ratio of width over height for each pixel.
     *
     * <p>The default value is {@code 1}.
     */
    @CanIgnoreReturnValue
    public Builder setPixelWidthHeightRatio(float pixelWidthHeightRatio) {
      this.pixelWidthHeightRatio = pixelWidthHeightRatio;
      return this;
    }

    /**
     * Sets the {@linkplain FrameInfo#streamOffsetUs stream offset}, in microseconds.
     *
     * <p>The default value is {@code 0}.
     */
    @CanIgnoreReturnValue
    public Builder setStreamOffsetUs(long streamOffsetUs) {
      this.streamOffsetUs = streamOffsetUs;
      return this;
    }

    /**
     * Sets the {@linkplain FrameInfo#offsetToAddUs offset to add} to the frame presentation
     * timestamp, in microseconds.
     *
     * <p>The default value is {@code 0}.
     */
    @CanIgnoreReturnValue
    public Builder setOffsetToAddUs(long offsetToAddUs) {
      this.offsetToAddUs = offsetToAddUs;
      return this;
    }

    /** Builds a {@link FrameInfo} instance. */
    public FrameInfo build() {
      return new FrameInfo(width, height, pixelWidthHeightRatio, streamOffsetUs, offsetToAddUs);
    }
  }

  /** The width of the frame, in pixels. */
  public final int width;
  /** The height of the frame, in pixels. */
  public final int height;
  /** The ratio of width over height for each pixel. */
  public final float pixelWidthHeightRatio;
  /**
   * An offset in microseconds that is part of the input timestamps and should be ignored for
   * processing but added back to the output timestamps.
   *
   * <p>The offset stays constant within a stream. If the first timestamp of the next stream is less
   * than or equal to the last timestamp of the current stream (including the {@linkplain
   * #offsetToAddUs} offset to add), the stream offset must be updated between the streams to ensure
   * that the offset frame timestamps are always monotonically increasing.
   */
  public final long streamOffsetUs;
  /**
   * The offset that must be added to the frame presentation timestamp, in microseconds.
   *
   * <p>This offset is not part of the input timestamps. It is added to the frame timestamps before
   * processing, and is retained in the output timestamps.
   */
  public final long offsetToAddUs;

  // TODO(b/227624622): Add color space information for HDR.

  private FrameInfo(
      int width, int height, float pixelWidthHeightRatio, long streamOffsetUs, long offsetToAddUs) {
    checkArgument(width > 0, "width must be positive, but is: " + width);
    checkArgument(height > 0, "height must be positive, but is: " + height);

    this.width = width;
    this.height = height;
    this.pixelWidthHeightRatio = pixelWidthHeightRatio;
    this.streamOffsetUs = streamOffsetUs;
    this.offsetToAddUs = offsetToAddUs;
  }
}
