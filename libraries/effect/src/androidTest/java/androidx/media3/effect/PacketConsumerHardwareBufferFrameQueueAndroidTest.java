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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assume.assumeTrue;

import android.hardware.HardwareBuffer;
import android.os.Build;
import androidx.media3.common.util.Size;
import androidx.test.filters.SdkSuppress;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Parameterized Android instrumental tests for {@link PacketConsumerHardwareBufferFrameQueue}. */
@RunWith(TestParameterInjector.class)
@SdkSuppress(minSdkVersion = 26)
public final class PacketConsumerHardwareBufferFrameQueueAndroidTest {

  /** Resolutions for testing. */
  public enum TestSize {
    SIZE_720P(1280, 720),
    SIZE_1080P(1920, 1080),
    SIZE_4K(3840, 2160);

    private final int width;
    private final int height;

    TestSize(int width, int height) {
      this.width = width;
      this.height = height;
    }

    public Size toSize() {
      return new Size(width, height);
    }
  }

  /** Common pixel formats supported on API 26+. */
  public enum CommonFormat {
    RGBA_8888(HardwareBuffer.RGBA_8888),
    RGBA_1010102(HardwareBuffer.RGBA_1010102);

    private final int format;

    CommonFormat(int format) {
      this.format = format;
    }
  }

  /** Usage flags to test. */
  public enum UsageFlag {
    SAMPLED_IMAGE(HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE),
    COLOR_OUTPUT(HardwareBuffer.USAGE_GPU_COLOR_OUTPUT),
    VIDEO_ENCODE(HardwareBuffer.USAGE_VIDEO_ENCODE);

    private final long flag;

    UsageFlag(long flag) {
      this.flag = flag;
    }
  }

  @Test
  public void getHardwareBuffer_commonFormats(
      @TestParameter TestSize testSize,
      @TestParameter CommonFormat commonFormat,
      @TestParameter UsageFlag usageFlag) {
    verifyHardwareBufferAllocation(testSize.toSize(), commonFormat.format, usageFlag.flag);
  }

  @Test
  @SdkSuppress(minSdkVersion = 30)
  public void getHardwareBuffer_ycbcrFormat(
      @TestParameter TestSize testSize, @TestParameter UsageFlag usageFlag) {
    verifyHardwareBufferAllocation(testSize.toSize(), HardwareBuffer.YCBCR_420_888, usageFlag.flag);
  }

  /** Shared helper method to verify HardwareBuffer allocation and exact usage flags. */
  private void verifyHardwareBufferAllocation(Size size, int pixelFormat, long requestedUsage) {
    // Check if the combination is supported by the hardware to avoid test failures
    // on devices with limited allocation capabilities (e.g., 4K or 10-bit).
    if (Build.VERSION.SDK_INT >= 29) {
      assumeTrue(
          "HardwareBuffer configuration not supported on this device",
          HardwareBuffer.isSupported(
              size.getWidth(), size.getHeight(), pixelFormat, /* layers= */ 1, requestedUsage));
    }

    PacketConsumerHardwareBufferFrameQueue supplier =
        new PacketConsumerHardwareBufferFrameQueue(unused -> {}, directExecutor());
    HardwareBufferFrameQueue.FrameFormat format =
        new HardwareBufferFrameQueue.FrameFormat(
            size.getWidth(), size.getHeight(), pixelFormat, requestedUsage);

    HardwareBufferFrame frame = supplier.dequeue(format, () -> {});
    assertThat(frame).isNotNull();
    HardwareBuffer buffer = frame.hardwareBuffer;
    assertThat(buffer).isNotNull();

    try {
      assertThat(buffer.getWidth()).isEqualTo(size.getWidth());
      assertThat(buffer.getHeight()).isEqualTo(size.getHeight());
      assertThat(buffer.getFormat()).isEqualTo(pixelFormat);

      long expectedUsage = requestedUsage | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE;
      assertThat(buffer.getUsage()).isEqualTo(expectedUsage);

    } finally {
      frame.release(/* releaseFence= */ null);
    }
  }
}
