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
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.Util;
import androidx.media3.test.utils.FakeHardwareBufferNativeHelpers;
import androidx.media3.test.utils.ImageReaderSurfaceHolder;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

// TODO: b/507446982 - Fix blocking dequeueInputFrame call and enable on API 28.
/** Instrumentation tests for {@link SurfaceHolderFrameWriter}. */
@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4.class)
public final class SurfaceHolderFrameWriterAndroidTest {

  private static final long TEST_TIMEOUT_MS = 5_000L;
  private static final int WIDTH = 640;
  private static final int HEIGHT = 480;

  private ImageReaderSurfaceHolder surfaceHolder;
  private FakeListener listener;
  private SurfaceHolderFrameWriter frameWriter;
  private ExecutorService surfaceHolderExecutor;
  private FakeHardwareBufferNativeHelpers nativeHelpers;
  private HandlerThread callbackThread;
  private Handler callbackHandler;

  @Before
  public void setUp() {
    callbackThread = new HandlerThread("SurfaceHolderCallbackThread");
    callbackThread.start();
    callbackHandler = new Handler(callbackThread.getLooper());
    surfaceHolder = new ImageReaderSurfaceHolder(callbackHandler);
    listener = new FakeListener();
    surfaceHolderExecutor = Util.newSingleThreadExecutor("SurfaceHolderThread");
    nativeHelpers = new FakeHardwareBufferNativeHelpers();
    frameWriter =
        SurfaceHolderFrameWriter.create(
            surfaceHolder, surfaceHolderExecutor, listener, directExecutor(), nativeHelpers);
  }

  @After
  public void tearDown() {
    if (frameWriter != null) {
      frameWriter.close();
    }
    if (surfaceHolder != null) {
      surfaceHolder.close();
    }
    if (surfaceHolderExecutor != null) {
      surfaceHolderExecutor.close();
    }
    if (callbackThread != null) {
      callbackThread.quitSafely();
    }
    assertThat(listener.lastException).isNull();
  }

  @Test
  public void configureThenDequeue_notifiesWakeupListener() throws Exception {
    AtomicReference<AsyncFrame> asyncFrameRef = new AtomicReference<>();
    CountDownLatch wakeupLatch = new CountDownLatch(1);
    ConditionVariable allowSurfaceHolderExecution = new ConditionVariable();
    Format format =
        new Format.Builder()
            .setWidth(WIDTH)
            .setHeight(HEIGHT)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();
    Future<?> unused =
        surfaceHolderExecutor.submit(() -> allowSurfaceHolderExecution.block(TEST_TIMEOUT_MS));

    frameWriter.configure(format, /* usage= */ Frame.USAGE_GPU_SAMPLED_IMAGE);
    AsyncFrame asyncFrame =
        frameWriter.dequeueInputFrame(
            /* wakeupExecutor= */ directExecutor(),
            /* wakeupListener= */ () -> {
              asyncFrameRef.set(frameWriter.dequeueInputFrame(directExecutor(), () -> {}));
              wakeupLatch.countDown();
            });

    assertThat(asyncFrame).isNull();

    allowSurfaceHolderExecution.open();

    assertThat(wakeupLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    asyncFrame = asyncFrameRef.get();
    assertThat(asyncFrame).isNotNull();
    HardwareBufferFrame frame = (HardwareBufferFrame) asyncFrame.frame;
    assertThat(frame.getFormat()).isEqualTo(format);
    assertThat(frame.getHardwareBuffer().getHeight()).isEqualTo(HEIGHT);
    assertThat(frame.getHardwareBuffer().getWidth()).isEqualTo(WIDTH);
    assertThat(frame.getHardwareBuffer().getFormat()).isEqualTo(HardwareBuffer.RGBA_8888);
  }

  @Test
  public void dequeueThenQueue_notifiesListener() throws Exception {
    long contentTimeUs = 100_000L;
    long displayTimeNs = Clock.DEFAULT.nanoTime();
    AtomicReference<AsyncFrame> asyncFrameRef = new AtomicReference<>();
    CountDownLatch wakeupLatch = new CountDownLatch(1);
    ConditionVariable allowSurfaceHolderExecution = new ConditionVariable();
    Format format =
        new Format.Builder()
            .setWidth(WIDTH)
            .setHeight(HEIGHT)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();
    Future<?> unused =
        surfaceHolderExecutor.submit(() -> allowSurfaceHolderExecution.block(TEST_TIMEOUT_MS));

    frameWriter.configure(format, /* usage= */ Frame.USAGE_GPU_SAMPLED_IMAGE);
    AsyncFrame asyncFrame =
        frameWriter.dequeueInputFrame(
            /* wakeupExecutor= */ directExecutor(),
            /* wakeupListener= */ () -> {
              asyncFrameRef.set(frameWriter.dequeueInputFrame(directExecutor(), () -> {}));
              wakeupLatch.countDown();
            });

    assertThat(asyncFrame).isNull();

    allowSurfaceHolderExecution.open();

    assertThat(wakeupLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    HardwareBufferFrame frame = (HardwareBufferFrame) asyncFrameRef.get().frame;

    HardwareBufferFrame outputFrame =
        frame
            .buildUpon()
            .setMetadata(ImmutableMap.of(Frame.KEY_DISPLAY_TIME_NS, displayTimeNs))
            .setContentTimeUs(contentTimeUs)
            .build();
    frameWriter.queueInputFrame(outputFrame, /* writeCompleteFence= */ null);

    assertThat(listener.renderedLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(listener.lastPresentationTimeUs).isEqualTo(contentTimeUs);
    assertThat(listener.lastReleaseTimeNs).isEqualTo(displayTimeNs);
    assertThat(listener.lastFormat).isEqualTo(format);
  }

  @Test
  public void dequeueThenQueue_multipleFrames_notifiesListener() throws Exception {
    long contentTimeUs = 100_000L;
    long displayTimeNs = Clock.DEFAULT.nanoTime();
    AtomicReference<AsyncFrame> asyncFrameRef = new AtomicReference<>();
    CountDownLatch wakeupLatch = new CountDownLatch(1);
    ConditionVariable allowSurfaceHolderExecution = new ConditionVariable();
    Format format =
        new Format.Builder()
            .setWidth(WIDTH)
            .setHeight(HEIGHT)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();
    Future<?> unused =
        surfaceHolderExecutor.submit(() -> allowSurfaceHolderExecution.block(TEST_TIMEOUT_MS));

    frameWriter.configure(format, /* usage= */ Frame.USAGE_GPU_SAMPLED_IMAGE);
    AsyncFrame asyncFrame =
        frameWriter.dequeueInputFrame(
            /* wakeupExecutor= */ directExecutor(),
            /* wakeupListener= */ () -> {
              asyncFrameRef.set(frameWriter.dequeueInputFrame(directExecutor(), () -> {}));
              wakeupLatch.countDown();
            });

    assertThat(asyncFrame).isNull();

    allowSurfaceHolderExecution.open();

    assertThat(wakeupLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    HardwareBufferFrame frame = (HardwareBufferFrame) asyncFrameRef.get().frame;

    HardwareBufferFrame outputFrame =
        frame
            .buildUpon()
            .setMetadata(ImmutableMap.of(Frame.KEY_DISPLAY_TIME_NS, displayTimeNs))
            .setContentTimeUs(contentTimeUs)
            .build();
    frameWriter.queueInputFrame(outputFrame, /* writeCompleteFence= */ null);

    assertThat(listener.renderedLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(listener.lastPresentationTimeUs).isEqualTo(contentTimeUs);
    assertThat(listener.lastReleaseTimeNs).isEqualTo(displayTimeNs);
    assertThat(listener.lastFormat).isEqualTo(format);

    surfaceHolder.drainSurface();

    // All subsequent frames should be dequeued synchronously.
    for (int i = 0; i < 10; i++) {
      contentTimeUs *= 2;
      displayTimeNs = Clock.DEFAULT.nanoTime();
      asyncFrame =
          frameWriter.dequeueInputFrame(
              /* wakeupExecutor= */ directExecutor(),
              /* wakeupListener= */ () -> {
                throw new AssertionError("Unexpected wakeup listener");
              });

      assertThat(asyncFrame).isNotNull();
      frame = (HardwareBufferFrame) asyncFrame.frame;

      outputFrame =
          frame
              .buildUpon()
              .setMetadata(ImmutableMap.of(Frame.KEY_DISPLAY_TIME_NS, displayTimeNs))
              .setContentTimeUs(contentTimeUs)
              .build();
      frameWriter.queueInputFrame(outputFrame, /* writeCompleteFence= */ null);

      assertThat(listener.renderedLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
      assertThat(listener.lastPresentationTimeUs).isEqualTo(contentTimeUs);
      assertThat(listener.lastReleaseTimeNs).isEqualTo(displayTimeNs);
      assertThat(listener.lastFormat).isEqualTo(format);

      surfaceHolder.drainSurface();
    }
  }

  @Test
  public void dequeueThenConfigure_returnsNewFormatOnNextDequeue() throws Exception {
    AtomicReference<AsyncFrame> asyncFrameRef = new AtomicReference<>();
    AtomicReference<CountDownLatch> wakeupLatchRef = new AtomicReference<>(new CountDownLatch(1));
    ConditionVariable allowSurfaceHolderExecution = new ConditionVariable();
    Format format1 =
        new Format.Builder()
            .setWidth(WIDTH)
            .setHeight(HEIGHT)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();
    Format format2 =
        new Format.Builder()
            .setWidth(WIDTH * 2)
            .setHeight(HEIGHT * 2)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();
    Future<?> unused =
        surfaceHolderExecutor.submit(() -> allowSurfaceHolderExecution.block(TEST_TIMEOUT_MS));

    frameWriter.configure(format1, /* usage= */ Frame.USAGE_GPU_SAMPLED_IMAGE);
    AsyncFrame asyncFrame1 =
        frameWriter.dequeueInputFrame(
            /* wakeupExecutor= */ directExecutor(),
            /* wakeupListener= */ () -> {
              asyncFrameRef.set(frameWriter.dequeueInputFrame(directExecutor(), () -> {}));
              wakeupLatchRef.get().countDown();
            });

    assertThat(asyncFrame1).isNull();

    allowSurfaceHolderExecution.open();

    assertThat(wakeupLatchRef.get().await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    HardwareBufferFrame frame1 = (HardwareBufferFrame) asyncFrameRef.get().frame;
    assertThat(frame1.getFormat()).isEqualTo(format1);

    frameWriter.queueInputFrame(frame1, /* writeCompleteFence= */ null);

    assertThat(listener.renderedLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(listener.lastFormat).isEqualTo(format1);

    surfaceHolder.drainSurface();

    // Reset the latches for the next frame.
    allowSurfaceHolderExecution.close();
    Future<?> unused2 =
        surfaceHolderExecutor.submit(() -> allowSurfaceHolderExecution.block(TEST_TIMEOUT_MS));
    wakeupLatchRef.set(new CountDownLatch(1));
    listener.renderedLatch = new CountDownLatch(1);
    CountDownLatch callbackLatch = new CountDownLatch(1);
    callbackHandler.post(callbackLatch::countDown);
    assertThat(callbackLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();

    frameWriter.configure(format2, /* usage= */ Frame.USAGE_GPU_SAMPLED_IMAGE);
    AsyncFrame asyncFrame2 =
        frameWriter.dequeueInputFrame(
            /* wakeupExecutor= */ directExecutor(),
            /* wakeupListener= */ () -> {
              asyncFrameRef.set(frameWriter.dequeueInputFrame(directExecutor(), () -> {}));
              wakeupLatchRef.get().countDown();
            });

    assertThat(asyncFrame2).isNull();

    allowSurfaceHolderExecution.open();

    assertThat(wakeupLatchRef.get().await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    HardwareBufferFrame frame2 = (HardwareBufferFrame) asyncFrameRef.get().frame;
    assertThat(frame2.getFormat()).isEqualTo(format2);

    frameWriter.queueInputFrame(frame2, /* writeCompleteFence= */ null);

    assertThat(listener.renderedLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(listener.lastFormat).isEqualTo(format2);
  }

  @Test
  @SdkSuppress(minSdkVersion = 29, maxSdkVersion = 32)
  public void queueInputFrame_whenNativeCopyFails_reportsError() throws Exception {
    AtomicReference<AsyncFrame> asyncFrameRef = new AtomicReference<>();
    CountDownLatch wakeupLatch = new CountDownLatch(1);
    ConditionVariable allowSurfaceHolderExecution = new ConditionVariable();
    Format format =
        new Format.Builder()
            .setWidth(WIDTH)
            .setHeight(HEIGHT)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();
    nativeHelpers.shouldSucceed = false;
    Future<?> unused =
        surfaceHolderExecutor.submit(() -> allowSurfaceHolderExecution.block(TEST_TIMEOUT_MS));

    frameWriter.configure(format, /* usage= */ Frame.USAGE_GPU_SAMPLED_IMAGE);
    AsyncFrame asyncFrame =
        frameWriter.dequeueInputFrame(
            directExecutor(),
            () -> {
              asyncFrameRef.set(frameWriter.dequeueInputFrame(directExecutor(), () -> {}));
              wakeupLatch.countDown();
            });

    assertThat(asyncFrame).isNull();
    allowSurfaceHolderExecution.open();
    assertThat(wakeupLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();

    HardwareBufferFrame frame = (HardwareBufferFrame) asyncFrameRef.get().frame;
    frameWriter.queueInputFrame(frame, /* writeCompleteFence= */ null);

    assertThat(listener.errorLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(listener.lastException).isNotNull();
    assertThat(listener.lastException)
        .hasMessageThat()
        .contains("Failed to copy HardwareBuffer via JNI.");
    listener.lastException = null;
  }

  private static class FakeListener implements SurfaceHolderFrameWriter.Listener {
    final CountDownLatch errorLatch;
    CountDownLatch renderedLatch;
    private long lastPresentationTimeUs;
    private long lastReleaseTimeNs;
    @Nullable private Format lastFormat;
    @Nullable private VideoFrameProcessingException lastException;

    private FakeListener() {
      renderedLatch = new CountDownLatch(1);
      errorLatch = new CountDownLatch(1);
      lastPresentationTimeUs = C.TIME_UNSET;
      lastReleaseTimeNs = C.TIME_UNSET;
    }

    @Override
    public void onFrameAboutToBeRendered(
        long presentationTimeUs, long releaseTimeNs, Format format) {
      lastPresentationTimeUs = presentationTimeUs;
      lastReleaseTimeNs = releaseTimeNs;
      lastFormat = format;
      renderedLatch.countDown();
    }

    @Override
    public void onError(VideoFrameProcessingException videoFrameProcessingException) {
      lastException = videoFrameProcessingException;
      errorLatch.countDown();
    }

    @Override
    public void onEnded() {}
  }
}
