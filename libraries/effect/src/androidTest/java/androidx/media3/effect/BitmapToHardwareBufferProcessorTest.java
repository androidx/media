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
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.hardware.HardwareBuffer;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import androidx.media3.common.util.GlUtil;
import androidx.media3.effect.ndk.HardwareBufferJni;
import androidx.media3.test.utils.BitmapPixelTestUtil;
import androidx.test.filters.SdkSuppress;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link BitmapToHardwareBufferProcessor}. */
@RunWith(TestParameterInjector.class)
@SdkSuppress(minSdkVersion = 26)
public final class BitmapToHardwareBufferProcessorTest {

  // TODO: b/475511702 - Update with HDR bitmap formats once supported.
  private enum BitmapType {
    HARDWARE,
    ARGB_8888,
  }

  private static final float MAX_AVG_PIXEL_DIFFERENCE = 1.0f;
  private static final String INPUT_PATH = "media/png/first_frame_1920x1080.png";
  private static final long TEST_TIMEOUT_MS = 1000L;

  private @MonotonicNonNull ExecutorService executorService;
  private @MonotonicNonNull BitmapToHardwareBufferProcessor processor;
  private @MonotonicNonNull List<AutoCloseable> resourcesToClose;

  @Rule public final TestName testName = new TestName();

  @Before
  public void setUp() {
    executorService = Executors.newSingleThreadExecutor();
    processor =
        new BitmapToHardwareBufferProcessor(
            HardwareBufferJni.INSTANCE,
            /* internalExecutor= */ executorService,
            /* errorExecutor= */ directExecutor(),
            /* errorCallback= */ (e) -> {
              throw new AssertionError(e);
            });
    resourcesToClose = new ArrayList<>();
  }

  @After
  public void tearDown() throws Exception {
    if (processor != null) {
      processor.close();
    }
    if (executorService != null) {
      executorService.shutdown();
    }
    for (AutoCloseable resource : resourcesToClose) {
      resource.close();
    }
  }

  @Test
  public void process_validBitmap_copiesPixelsCorrectly(@TestParameter BitmapType bitmapType)
      throws Exception {
    Bitmap inputBitmap = readBitmap(bitmapType);
    // The pixel comparison can only run on ARGB_8888 bitmaps, if the input is HARDWARE, copy it to
    // a ARGB_8888 for the assertion.
    Bitmap expectedBitmap =
        bitmapType == BitmapType.ARGB_8888
            ? inputBitmap
            : inputBitmap.copy(Config.ARGB_8888, /* isMutable= */ false);
    HardwareBufferFrame inputFrame =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null, directExecutor(), /* releaseCallback= */ (fence) -> {})
            .setInternalFrame(inputBitmap)
            .build();

    HardwareBufferFrame outputFrame = processor.process(inputFrame);

    assertThat(outputFrame.hardwareBuffer).isNotNull();
    HardwareBuffer hardwareBuffer = outputFrame.hardwareBuffer;

    assertThat(hardwareBuffer.getWidth()).isEqualTo(inputBitmap.getWidth());
    assertThat(hardwareBuffer.getHeight()).isEqualTo(inputBitmap.getHeight());

    Bitmap outputBitmap = readBitmapFromHardwareBuffer(hardwareBuffer);

    assertThat(
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedBitmap, outputBitmap, testName.getMethodName()))
        .isLessThan(MAX_AVG_PIXEL_DIFFERENCE);

    outputFrame.release(/* releaseFence= */ null);
  }

  @Test
  public void process_recycledBitmap_throwsIllegalStateException() throws Exception {
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap(INPUT_PATH);
    HardwareBufferFrame inputFrame =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null, directExecutor(), /* releaseCallback= */ (fence) -> {})
            .setInternalFrame(inputBitmap)
            .build();

    inputBitmap.recycle();

    assertThrows(IllegalStateException.class, () -> processor.process(inputFrame));
  }

  @Test
  public void process_alreadyHasHardwareBuffer_returnsOriginalFrame() {
    try (HardwareBuffer hardwareBuffer = createHardwareBuffer()) {
      HardwareBufferFrame inputFrame =
          new HardwareBufferFrame.Builder(
                  hardwareBuffer, directExecutor(), /* releaseCallback= */ (fence) -> {})
              .build();

      HardwareBufferFrame outputFrame = processor.process(inputFrame);

      assertThat(outputFrame).isSameInstanceAs(inputFrame);

      outputFrame.release(/* releaseFence= */ null);
    }
  }

  @Test
  public void process_notABitmap_returnsOriginalFrame() {
    HardwareBufferFrame inputFrame =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null, directExecutor(), /* releaseCallback= */ (fence) -> {})
            .setInternalFrame(new Object())
            .build();

    HardwareBufferFrame outputFrame = processor.process(inputFrame);

    assertThat(outputFrame).isSameInstanceAs(inputFrame);

    outputFrame.release(/* releaseFence= */ null);
  }

  @Test
  public void process_repeatedBitmap_reusesSameBuffer(@TestParameter BitmapType bitmapType)
      throws IOException {
    Bitmap inputBitmap = readBitmap(bitmapType);
    HardwareBufferFrame inputFrame1 = createBitmapFrame(inputBitmap);
    HardwareBufferFrame inputFrame2 = createBitmapFrame(inputBitmap);

    HardwareBufferFrame outputFrame1 = processor.process(inputFrame1);
    HardwareBufferFrame outputFrame2 = processor.process(inputFrame2);

    assertThat(outputFrame1.hardwareBuffer).isSameInstanceAs(outputFrame2.hardwareBuffer);
    assertThat(outputFrame1.hardwareBuffer.isClosed()).isFalse();

    outputFrame1.release(/* releaseFence= */ null);
    outputFrame2.release(/* releaseFence= */ null);
  }

  @Test
  public void process_repeatedBitmapAfterRelease_reusesSameBuffer(
      @TestParameter BitmapType bitmapType) throws IOException {
    Bitmap inputBitmap = readBitmap(bitmapType);
    HardwareBufferFrame inputFrame1 = createBitmapFrame(inputBitmap);
    HardwareBufferFrame inputFrame2 = createBitmapFrame(inputBitmap);

    HardwareBufferFrame outputFrame1 = processor.process(inputFrame1);
    outputFrame1.release(createSignaledFence());
    HardwareBufferFrame outputFrame2 = processor.process(inputFrame2);

    assertThat(outputFrame1.hardwareBuffer).isSameInstanceAs(outputFrame2.hardwareBuffer);
    assertThat(outputFrame1.hardwareBuffer.isClosed()).isFalse();

    outputFrame2.release(/* releaseFence= */ null);
  }

  @Test
  public void process_differentBitmap_createsNewBufferAndRemovesReferenceToOldBuffer(
      @TestParameter BitmapType bitmapType)
      throws IOException, ExecutionException, InterruptedException, TimeoutException {
    Bitmap bitmap1 = readBitmap(bitmapType);
    Bitmap bitmap2 = readBitmap(bitmapType);

    HardwareBufferFrame outputFrame1 = processor.process(createBitmapFrame(bitmap1));
    HardwareBufferFrame outputFrame2 = processor.process(createBitmapFrame(bitmap2));

    assertThat(outputFrame1.hardwareBuffer).isNotSameInstanceAs(outputFrame2.hardwareBuffer);

    // Processor released its hold on buffer1 when bitmap2 was converted.
    // buffer1 is still held by outputFrame1.
    assertThat(outputFrame1.hardwareBuffer.isClosed()).isFalse();

    outputFrame1.release(createSignaledFence());
    // Ensure there are no pending tasks left on the executor.
    executorService.submit(() -> {}).get(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(outputFrame1.hardwareBuffer.isClosed()).isTrue();

    outputFrame2.release(/* releaseFence= */ null);
  }

  @Test
  public void process_differentGenerationId_createsNewBuffer() {
    // Cannot be tested with Config.Hardware because hardware backed bitmaps are immutable.
    Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);

    HardwareBufferFrame outputFrame1 = processor.process(createBitmapFrame(bitmap));
    bitmap.eraseColor(Color.RED);
    HardwareBufferFrame outputFrame2 = processor.process(createBitmapFrame(bitmap));

    assertThat(outputFrame1.hardwareBuffer).isNotSameInstanceAs(outputFrame2.hardwareBuffer);

    outputFrame1.release(/* releaseFence= */ null);
    outputFrame2.release(/* releaseFence= */ null);
  }

  @Test
  public void process_releasesInputFrame() throws Exception {
    CountDownLatch releasedLatch = new CountDownLatch(1);
    HardwareBufferFrame inputFrame =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null,
                directExecutor(),
                /* releaseCallback= */ (fence) -> releasedLatch.countDown())
            .setInternalFrame(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
            .build();

    HardwareBufferFrame outputFrame = processor.process(inputFrame);

    assertThat(releasedLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();

    outputFrame.release(createSignaledFence());
  }

  @Test
  public void releaseOutputFrame_sharedBuffer_doesNotCloseSharedBuffer(
      @TestParameter BitmapType bitmapType) throws Exception {
    Bitmap inputBitmap = readBitmap(bitmapType);
    HardwareBufferFrame inputFrame1 = createBitmapFrame(inputBitmap);
    HardwareBufferFrame inputFrame2 = createBitmapFrame(inputBitmap);

    HardwareBufferFrame outputFrame1 = processor.process(inputFrame1);
    HardwareBufferFrame outputFrame2 = processor.process(inputFrame2);

    outputFrame1.release(createSignaledFence());
    // Ensure there are no pending tasks left on the executor.
    executorService.submit(() -> {}).get(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(outputFrame1.hardwareBuffer.isClosed()).isFalse();
    assertThat(outputFrame2.hardwareBuffer.isClosed()).isFalse();

    outputFrame2.release(createSignaledFence());
    // Ensure there are no pending tasks left on the executor.
    executorService.submit(() -> {}).get(TEST_TIMEOUT_MS, MILLISECONDS);

    assertThat(outputFrame1.hardwareBuffer.isClosed()).isFalse();
    assertThat(outputFrame2.hardwareBuffer.isClosed()).isFalse();
  }

  @Test
  public void close_shutsDownInternalExecutor() {
    Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
    HardwareBufferFrame outputFrame = processor.process(createBitmapFrame(bitmap));
    outputFrame.release(/* releaseFence= */ null);

    processor.close();

    assertThat(executorService.isShutdown()).isTrue();
  }

  @Test
  public void releaseOutputFrame_afterClose_closesBuffer() throws IOException {
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap(INPUT_PATH);
    HardwareBufferFrame outputFrame = processor.process(createBitmapFrame(inputBitmap));
    HardwareBuffer hardwareBuffer = outputFrame.hardwareBuffer;

    processor.close();

    // Processor released its reference, but the frame still holds one.
    assertThat(hardwareBuffer.isClosed()).isFalse();

    outputFrame.release(createSignaledFence());

    // This will run synchronously because the internal executor is shutdown.
    assertThat(hardwareBuffer.isClosed()).isTrue();
  }

  @Test
  public void process_afterClose_throwsIllegalStateException() {
    Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);

    processor.close();

    assertThrows(IllegalStateException.class, () -> processor.process(createBitmapFrame(bitmap)));
  }

  private Bitmap readBitmapFromHardwareBuffer(HardwareBuffer hardwareBuffer) throws Exception {
    if (Build.VERSION.SDK_INT >= 29) {
      Bitmap hardwareBitmap =
          Bitmap.wrapHardwareBuffer(hardwareBuffer, ColorSpace.get(ColorSpace.Named.SRGB));
      assertThat(hardwareBitmap).isNotNull();
      return hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
    }

    int width = hardwareBuffer.getWidth();
    int height = hardwareBuffer.getHeight();
    EGLDisplay eglDisplay = GlUtil.getDefaultEglDisplay();
    EGLContext eglContext = GlUtil.createEglContext(eglDisplay);
    EGLSurface eglSurface = GlUtil.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);

    int texId = GlUtil.createTexture(width, height, false);
    long eglImage =
        HardwareBufferJni.INSTANCE.nativeCreateEglImageFromHardwareBuffer(
            eglDisplay.getNativeHandle(), hardwareBuffer);
    assertThat(eglImage).isNotEqualTo(0L);

    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
    assertThat(HardwareBufferJni.INSTANCE.nativeBindEGLImage(GLES20.GL_TEXTURE_2D, eglImage))
        .isTrue();

    int fboId = GlUtil.createFboForTexture(texId);
    GlUtil.focusFramebufferUsingCurrentContext(fboId, width, height);
    Bitmap bitmap = BitmapPixelTestUtil.createArgb8888BitmapFromFocusedGlFramebuffer(width, height);

    GLES20.glDeleteTextures(1, new int[] {texId}, 0);
    assertThat(
            HardwareBufferJni.INSTANCE.nativeDestroyEGLImage(
                eglDisplay.getNativeHandle(), eglImage))
        .isTrue();
    GlUtil.destroyEglSurface(eglDisplay, eglSurface);
    GlUtil.destroyEglContext(eglDisplay, eglContext);

    // OpenGL returns an upside down bitmap due to coordinate system differences, flip it before
    // returning.
    Matrix matrix = new Matrix();
    matrix.postScale(1, -1, bitmap.getWidth() / 2f, bitmap.getHeight() / 2f);
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
  }

  private static Bitmap readBitmap(BitmapType bitmapType) throws IOException {
    switch (bitmapType) {
      case ARGB_8888:
        return BitmapPixelTestUtil.readBitmap(INPUT_PATH);
      case HARDWARE:
        return BitmapPixelTestUtil.readBitmap(INPUT_PATH)
            .copy(Config.HARDWARE, /* isMutable= */ false);
    }
    throw new IllegalArgumentException();
  }

  private static HardwareBufferFrame createBitmapFrame(Bitmap bitmap) {
    return new HardwareBufferFrame.Builder(
            /* hardwareBuffer= */ null, directExecutor(), /* releaseCallback= */ (fence) -> {})
        .setInternalFrame(bitmap)
        .build();
  }

  private static HardwareBuffer createHardwareBuffer() {
    return HardwareBuffer.create(
        10,
        10,
        /* format= */ HardwareBuffer.RGBA_8888,
        /* layers= */ 1,
        /* usage= */ HardwareBuffer.USAGE_CPU_WRITE_OFTEN | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
  }

  private SyncFenceCompat createSignaledFence() {
    try {
      FileDescriptor[] pipe = Os.pipe();
      ParcelFileDescriptor readPfd = ParcelFileDescriptor.dup(pipe[0]);
      ParcelFileDescriptor writePfd = ParcelFileDescriptor.dup(pipe[1]);
      resourcesToClose.add(readPfd);
      resourcesToClose.add(writePfd);
      // Clean up the original pipe file descriptors as they have been duped.
      Os.close(pipe[0]);
      Os.close(pipe[1]);

      // Write a single byte to the pipe, this causes the read-end to signal POLLIN.
      Os.write(writePfd.getFileDescriptor(), new byte[] {1}, 0, 1);

      return SyncFenceCompat.adoptFenceFileDescriptor(readPfd.detachFd());
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }
}
