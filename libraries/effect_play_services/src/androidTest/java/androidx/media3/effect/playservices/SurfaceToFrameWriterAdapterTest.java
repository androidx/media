/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.effect.playservices;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageWriter;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.test.utils.FakeHardwareBufferNativeHelpers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link SurfaceToFrameWriterAdapter}. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 33)
public final class SurfaceToFrameWriterAdapterTest {

  private static final int WIDTH = 128;
  private static final int HEIGHT = 128;
  private static final int TIMEOUT_MS = 1_000;

  private FakeFrameWriter fakeDownstreamOutput;
  private FakeHardwareBufferNativeHelpers fakeNativeHelpers;
  private HandlerThread handlerThread;
  private Handler handler;
  private TestListener testListener;
  private SurfaceToFrameWriterAdapter writer;
  @Nullable private ImageWriter testProducerWriter;
  private final List<HardwareBuffer> buffersToRelease = new ArrayList<>();

  @Before
  public void setUp() {
    handlerThread = new HandlerThread("SurfaceToFrameWriterAdapterTestThread");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());

    fakeDownstreamOutput = new FakeFrameWriter();
    fakeNativeHelpers = new FakeHardwareBufferNativeHelpers();
    testListener = new TestListener();

    writer =
        new SurfaceToFrameWriterAdapter(
            fakeDownstreamOutput, fakeNativeHelpers, handler, testListener);
  }

  @After
  public void tearDown() throws Exception {
    if (writer != null) {
      closeWriter();
    }
    if (testProducerWriter != null) {
      testProducerWriter.close();
    }
    for (HardwareBuffer buffer : buffersToRelease) {
      buffer.close();
    }
    handlerThread.quitSafely();
  }

  @Test
  public void configure_standardOptions_configuresDownstreamFormatWithSameDimensions()
      throws Exception {
    Format inputFormat = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();
    Surface surface = configureWriter(WIDTH, HEIGHT, inputFormat);

    assertThat(fakeDownstreamOutput.configuredFormat).isNotNull();
    assertThat(fakeDownstreamOutput.configuredFormat.width).isEqualTo(WIDTH);
    assertThat(fakeDownstreamOutput.configuredFormat.height).isEqualTo(HEIGHT);
    assertThat(surface).isNotNull();
  }

  @Test
  public void draining_queuesFrameDownstreamWithCorrectPresentationTimeUs() throws Exception {
    Format inputFormat = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();
    Surface surface = configureWriter(WIDTH, HEIGHT, inputFormat);

    long expectedPtsUs = 42_000L;
    onInputFrameQueued();

    fakeDownstreamOutput.prepareInputFrame(createHardwareBufferFrame(WIDTH, HEIGHT));
    produceInputFrame(surface, expectedPtsUs);

    assertThat(fakeDownstreamOutput.frameLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(fakeDownstreamOutput.queuedFrames).hasSize(1);
    assertThat(fakeDownstreamOutput.queuedFrames.get(0).getContentTimeUs())
        .isEqualTo(expectedPtsUs);
    assertThat(testListener.imageReleasedCount.get()).isAtLeast(1);
  }

  @Test
  public void draining_multipleFrames_maintainsFifoOrderOfTimestamps() throws Exception {
    Format inputFormat = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();
    Surface surface = configureWriter(WIDTH, HEIGHT, inputFormat);

    onInputFrameQueued();
    onInputFrameQueued();

    fakeDownstreamOutput.frameLatch = new CountDownLatch(2);
    fakeDownstreamOutput.prepareInputFrame(createHardwareBufferFrame(WIDTH, HEIGHT));
    fakeDownstreamOutput.prepareInputFrame(createHardwareBufferFrame(WIDTH, HEIGHT));

    produceInputFrame(surface, /* presentationTimeUs= */ 10_000L);
    produceInputFrame(surface, /* presentationTimeUs= */ 20_000L);

    assertThat(fakeDownstreamOutput.frameLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(fakeDownstreamOutput.queuedFrames).hasSize(2);
    assertThat(fakeDownstreamOutput.queuedFrames.get(0).getContentTimeUs()).isEqualTo(10_000L);
    assertThat(fakeDownstreamOutput.queuedFrames.get(1).getContentTimeUs()).isEqualTo(20_000L);
  }

  @Test
  public void draining_extraFramesBeyondInputCount_triggersOnError() throws Exception {
    Format inputFormat = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();
    Surface surface = configureWriter(WIDTH, HEIGHT, inputFormat);

    // Only 1 frame registered on input.
    onInputFrameQueued();

    fakeDownstreamOutput.prepareInputFrame(createHardwareBufferFrame(WIDTH, HEIGHT));
    fakeDownstreamOutput.prepareInputFrame(createHardwareBufferFrame(WIDTH, HEIGHT));

    // Produce 2 frames to the surface when only 1 input frame was registered.
    produceInputFrame(surface);

    assertThat(fakeDownstreamOutput.frameLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(fakeDownstreamOutput.queuedFrames).hasSize(1);

    produceInputFrame(surface);

    assertThat(testListener.errorLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(testListener.error.get()).isNotNull();
    assertThat(testListener.error.get())
        .hasMessageThat()
        .contains("Unexpected output frame acquired beyond input frame count");
    assertThat(fakeDownstreamOutput.queuedFrames).hasSize(1);
  }

  @Test
  public void
      eventDrivenEos_whenInputEosArrivesBeforeFramesDrained_signalsDownstreamOnlyAfterLastFrame()
          throws Exception {
    Format inputFormat = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();
    Surface surface = configureWriter(WIDTH, HEIGHT, inputFormat);

    onInputFrameQueued();
    onInputFrameQueued();

    // Input EOS arrives before outputs have been drained.
    onEndOfStream();

    // Wait for the handler thread to process any pending tasks before checking EOS state.
    CountDownLatch syncLatch = new CountDownLatch(1);
    handler.post(syncLatch::countDown);
    syncLatch.await(TIMEOUT_MS, MILLISECONDS);

    assertThat(fakeDownstreamOutput.eosSignaled.get()).isFalse();

    // Drain first frame.
    fakeDownstreamOutput.frameLatch = new CountDownLatch(1);
    fakeDownstreamOutput.prepareInputFrame(createHardwareBufferFrame(WIDTH, HEIGHT));
    produceInputFrame(surface);

    assertThat(fakeDownstreamOutput.frameLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(fakeDownstreamOutput.eosSignaled.get()).isFalse();

    // Drain second frame.
    fakeDownstreamOutput.frameLatch = new CountDownLatch(1);
    fakeDownstreamOutput.prepareInputFrame(createHardwareBufferFrame(WIDTH, HEIGHT));
    produceInputFrame(surface);

    assertThat(fakeDownstreamOutput.frameLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();

    // Both frames drained: downstream EOS must be signaled!
    assertThat(fakeDownstreamOutput.eosLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(fakeDownstreamOutput.eosSignaled.get()).isTrue();
  }

  @Test
  public void eventDrivenEos_whenInputEosArrivesAfterAllFramesDrained_signalsDownstreamImmediately()
      throws Exception {
    Format inputFormat = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();
    Surface surface = configureWriter(WIDTH, HEIGHT, inputFormat);

    onInputFrameQueued();
    fakeDownstreamOutput.prepareInputFrame(createHardwareBufferFrame(WIDTH, HEIGHT));

    produceInputFrame(surface);

    assertThat(fakeDownstreamOutput.frameLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(fakeDownstreamOutput.eosSignaled.get()).isFalse();

    // Input EOS arrives after frame is already processed.
    onEndOfStream();

    assertThat(fakeDownstreamOutput.eosLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(fakeDownstreamOutput.eosSignaled.get()).isTrue();
  }

  @Test
  public void draining_whenNativeCopyFails_triggersOnError() throws Exception {
    Format inputFormat = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();
    Surface surface = configureWriter(WIDTH, HEIGHT, inputFormat);

    onInputFrameQueued();
    fakeNativeHelpers.shouldSucceed = false;

    fakeDownstreamOutput.prepareInputFrame(createHardwareBufferFrame(WIDTH, HEIGHT));
    produceInputFrame(surface);

    assertThat(testListener.errorLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(testListener.error.get()).isNotNull();
    assertThat(testListener.error.get())
        .hasMessageThat()
        .contains("nativeCopyHardwareBufferToHardwareBuffer failed");
  }

  @Test
  public void close_releasesSurfacesAndCleansUp() throws Exception {
    Format inputFormat = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();
    Surface unused = configureWriter(WIDTH, HEIGHT, inputFormat);
    closeWriter();

    // Further outputs or close calls do not throw.
    closeWriter();
  }

  @Test
  public void close_fromNonHandlerThread_releasesSurfacesAndCleansUp() throws Exception {
    Format inputFormat = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();
    Surface unused = configureWriter(WIDTH, HEIGHT, inputFormat);
    // Call close() directly from test thread (which is not handler thread).
    writer.close();

    // Further close calls do not throw.
    writer.close();
  }

  @Test
  public void close_whenHandlerThreadBlocked_reportsTimeoutError() throws Exception {
    Format inputFormat = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();
    Surface unused = configureWriter(WIDTH, HEIGHT, inputFormat);
    CountDownLatch blockLatch = new CountDownLatch(1);
    CountDownLatch taskRunningLatch = new CountDownLatch(1);
    handler.post(
        () -> {
          taskRunningLatch.countDown();
          try {
            blockLatch.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    assertThat(taskRunningLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();

    try {
      writer.close();
      assertThat(testListener.errorLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
      assertThat(testListener.error.get()).isNotNull();
      assertThat(testListener.error.get())
          .hasMessageThat()
          .contains("Timeout waiting for SurfaceToFrameWriterAdapter to close");
    } finally {
      blockLatch.countDown();
    }
  }

  @Test
  public void close_whenFrameHeldInPendingImage_closesCleanlyWithoutError() throws Exception {
    Format inputFormat = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();
    Surface surface = configureWriter(WIDTH, HEIGHT, inputFormat);
    onInputFrameQueued();
    produceInputFrame(surface);

    assertThat(fakeDownstreamOutput.dequeueAttemptedLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(fakeDownstreamOutput.queuedFrames).isEmpty();

    closeWriter();

    assertThat(testListener.error.get()).isNull();
    assertThat(fakeDownstreamOutput.queuedFrames).isEmpty();
  }

  private Surface configureWriter(int width, int height, Format inputFormat) throws Exception {
    return runOnHandler(() -> writer.configure(width, height, inputFormat));
  }

  private void onInputFrameQueued() throws Exception {
    runOnHandler(writer::onInputFrameQueued);
  }

  private void onEndOfStream() throws Exception {
    runOnHandler(writer::onEndOfStream);
  }

  private void closeWriter() throws Exception {
    runOnHandler(writer::close);
  }

  private <T> T runOnHandler(Callable<T> callable) throws Exception {
    AtomicReference<T> result = new AtomicReference<>();
    AtomicReference<Throwable> exception = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    handler.post(
        () -> {
          try {
            result.set(callable.call());
          } catch (Throwable t) {
            exception.set(t);
          } finally {
            latch.countDown();
          }
        });
    assertThat(latch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    if (exception.get() != null) {
      if (exception.get() instanceof Exception) {
        throw (Exception) exception.get();
      }
      throw new RuntimeException(exception.get());
    }
    return result.get();
  }

  private void runOnHandler(Runnable runnable) throws Exception {
    AtomicReference<Throwable> exception = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    handler.post(
        () -> {
          try {
            runnable.run();
          } catch (Throwable t) {
            exception.set(t);
          } finally {
            latch.countDown();
          }
        });
    assertThat(latch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    if (exception.get() != null) {
      if (exception.get() instanceof Exception) {
        throw (Exception) exception.get();
      }
      throw new RuntimeException(exception.get());
    }
  }

  private void produceInputFrame(Surface surface) {
    produceInputFrame(surface, /* presentationTimeUs= */ 0L);
  }

  private void produceInputFrame(Surface surface, long presentationTimeUs) {
    if (testProducerWriter == null) {
      testProducerWriter = ImageWriter.newInstance(surface, 4, PixelFormat.RGBA_8888);
    }
    Image image = testProducerWriter.dequeueInputImage();
    image.setTimestamp(presentationTimeUs * 1000L);
    testProducerWriter.queueInputImage(image);
  }

  private DefaultHardwareBufferFrame createHardwareBufferFrame(int width, int height) {
    HardwareBuffer buffer =
        HardwareBuffer.create(
            width,
            height,
            HardwareBuffer.RGBA_8888,
            /* layers= */ 1,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                | HardwareBuffer.USAGE_CPU_READ_OFTEN);
    buffersToRelease.add(buffer);
    return new DefaultHardwareBufferFrame.Builder(buffer)
        .setFormat(new Format.Builder().setWidth(width).setHeight(height).build())
        .build();
  }

  private static class FakeFrameWriter implements FrameWriter {
    @Nullable Format configuredFormat;
    final List<Frame> queuedFrames = new ArrayList<>();
    final AtomicBoolean eosSignaled = new AtomicBoolean(false);
    final CountDownLatch eosLatch = new CountDownLatch(1);
    CountDownLatch frameLatch = new CountDownLatch(1);
    final CountDownLatch dequeueAttemptedLatch = new CountDownLatch(1);

    private final Queue<AsyncFrame> availableFrames = new ArrayDeque<>();
    @Nullable private Runnable wakeupListener;

    /** Makes a frame available to be returned by the next {@link #dequeueInputFrame} call. */
    void prepareInputFrame(Frame frame) {
      availableFrames.add(new AsyncFrame(frame, /* acquireFence= */ null));
      if (wakeupListener != null) {
        wakeupListener.run();
      }
    }

    @Override
    public void configure(Format format, long usage) {
      this.configuredFormat = format;
    }

    @Override
    @Nullable
    public AsyncFrame dequeueInputFrame(Executor executor, Runnable wakeupListener) {
      this.wakeupListener = wakeupListener;
      dequeueAttemptedLatch.countDown();
      return availableFrames.poll();
    }

    @Override
    public void queueInputFrame(Frame frame, @Nullable SyncFenceWrapper writeCompleteFence) {
      queuedFrames.add(frame);
      frameLatch.countDown();
    }

    @Override
    public void signalEndOfStream() {
      eosSignaled.set(true);
      eosLatch.countDown();
    }

    @Override
    public Info getInfo() {
      return (format, usage) -> true;
    }

    @Override
    public void close() {}
  }

  private static class TestListener implements SurfaceToFrameWriterAdapter.Listener {
    final AtomicInteger imageReleasedCount = new AtomicInteger(0);
    final AtomicReference<VideoFrameProcessingException> error = new AtomicReference<>();
    final CountDownLatch errorLatch = new CountDownLatch(1);

    @Override
    public void onFrameReleased() {
      imageReleasedCount.incrementAndGet();
    }

    @Override
    public void onError(VideoFrameProcessingException exception) {
      error.set(exception);
      errorLatch.countDown();
    }
  }
}
