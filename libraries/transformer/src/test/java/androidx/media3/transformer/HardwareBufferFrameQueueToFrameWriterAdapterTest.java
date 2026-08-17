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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.hardware.HardwareBuffer;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.effect.HardwareBufferFrameQueue;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link HardwareBufferFrameQueueToFrameWriterAdapter}. */
@RunWith(AndroidJUnit4.class)
@OptIn(markerClass = ExperimentalApi.class)
public final class HardwareBufferFrameQueueToFrameWriterAdapterTest {

  @Test
  public void dequeueInputFrame_propagatesPixelFormat() {
    FakeHardwareBufferFrameQueue fakeQueue = new FakeHardwareBufferFrameQueue();
    HardwareBufferFrameQueueToFrameWriterAdapter adapter =
        new HardwareBufferFrameQueueToFrameWriterAdapter(fakeQueue);
    Format format =
        new Format.Builder()
            .setWidth(100)
            .setHeight(200)
            .setPixelFormat(HardwareBuffer.RGBA_FP16)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();
    adapter.configure(format, /* usage= */ 0);

    AsyncFrame unused = adapter.dequeueInputFrame(directExecutor(), () -> {});

    assertThat(fakeQueue.lastFormat).isNotNull();
    assertThat(fakeQueue.lastFormat.pixelFormat).isEqualTo(HardwareBuffer.RGBA_FP16);
  }

  @Test
  public void dequeueInputFrame_noPixelFormat_defaultsToSdr() {
    FakeHardwareBufferFrameQueue fakeQueue = new FakeHardwareBufferFrameQueue();
    HardwareBufferFrameQueueToFrameWriterAdapter adapter =
        new HardwareBufferFrameQueueToFrameWriterAdapter(fakeQueue);
    Format format =
        new Format.Builder()
            .setWidth(100)
            .setHeight(200)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();
    adapter.configure(format, /* usage= */ 0);

    AsyncFrame unused = adapter.dequeueInputFrame(directExecutor(), () -> {});

    assertThat(fakeQueue.lastFormat).isNotNull();
    assertThat(fakeQueue.lastFormat.pixelFormat).isEqualTo(HardwareBuffer.RGBA_8888);
  }

  @Test
  public void dequeueInputFrame_noPixelFormat_defaultsToHdr() {
    FakeHardwareBufferFrameQueue fakeQueue = new FakeHardwareBufferFrameQueue();
    HardwareBufferFrameQueueToFrameWriterAdapter adapter =
        new HardwareBufferFrameQueueToFrameWriterAdapter(fakeQueue);
    ColorInfo hdrColorInfo =
        new ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .setColorTransfer(C.COLOR_TRANSFER_ST2084)
            .build();
    Format format =
        new Format.Builder().setWidth(100).setHeight(200).setColorInfo(hdrColorInfo).build();
    adapter.configure(format, /* usage= */ 0);

    AsyncFrame unused = adapter.dequeueInputFrame(directExecutor(), () -> {});

    assertThat(fakeQueue.lastFormat).isNotNull();
    assertThat(fakeQueue.lastFormat.pixelFormat).isEqualTo(HardwareBuffer.RGBA_1010102);
  }

  private static final class FakeHardwareBufferFrameQueue implements HardwareBufferFrameQueue {
    @Nullable private FrameFormat lastFormat;

    @Nullable
    @Override
    public HardwareBufferFrame dequeue(FrameFormat format, Runnable wakeupListener) {
      lastFormat = format;
      return null;
    }

    @Override
    public void queue(HardwareBufferFrame frame) {}

    @Override
    public void signalEndOfStream() {}

    @Override
    public void release() {}
  }
}
