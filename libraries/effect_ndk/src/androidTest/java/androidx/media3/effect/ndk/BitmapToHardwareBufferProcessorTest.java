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
package androidx.media3.effect.ndk;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.GlUtil;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.test.utils.BitmapPixelTestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link BitmapToHardwareBufferProcessor}. */
@RunWith(AndroidJUnit4.class)
public final class BitmapToHardwareBufferProcessorTest {
  private static final float MAX_AVG_PIXEL_DIFFERENCE = 1.0f;

  private @MonotonicNonNull ExecutorService executorService;
  private @MonotonicNonNull BitmapToHardwareBufferProcessor processor;

  @Rule public final TestName testName = new TestName();

  @Before
  public void setUp() {
    executorService = Executors.newSingleThreadExecutor();
    processor = new BitmapToHardwareBufferProcessor(directExecutor());
  }

  @After
  public void tearDown() throws Exception {
    if (processor != null) {
      processor.close();
    }
    if (executorService != null) {
      executorService.shutdown();
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 26)
  public void process_withBitmap_copiesPixelsCorrectly() throws Exception {
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap("first_frame.png");
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
                inputBitmap, outputBitmap, testName.getMethodName()))
        .isLessThan(MAX_AVG_PIXEL_DIFFERENCE);

    outputFrame.release(null);
  }

  @Test
  @SdkSuppress(minSdkVersion = 26)
  public void process_invalidBitmap_throwsIllegalStateException() throws Exception {
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap("first_frame.png");
    HardwareBufferFrame inputFrame =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null, directExecutor(), /* releaseCallback= */ (fence) -> {})
            .setInternalFrame(inputBitmap)
            .build();

    inputBitmap.recycle();

    assertThrows(IllegalStateException.class, () -> processor.process(inputFrame));
  }

  @Test
  @SdkSuppress(minSdkVersion = 26)
  public void process_alreadyHasHardwareBuffer_returnsOriginalFrame() {
    try (HardwareBuffer hardwareBuffer = createHardwareBuffer()) {
      HardwareBufferFrame inputFrame =
          new HardwareBufferFrame.Builder(
                  hardwareBuffer, directExecutor(), /* releaseCallback= */ (fence) -> {})
              .build();

      HardwareBufferFrame outputFrame = processor.process(inputFrame);

      assertThat(outputFrame).isSameInstanceAs(inputFrame);

      outputFrame.release(null);
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 26)
  public void process_notABitmap_returnsOriginalFrame() {
    HardwareBufferFrame inputFrame =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null, directExecutor(), /* releaseCallback= */ (fence) -> {})
            .setInternalFrame(new Object())
            .build();

    HardwareBufferFrame outputFrame = processor.process(inputFrame);

    assertThat(outputFrame).isSameInstanceAs(inputFrame);

    outputFrame.release(null);
  }

  @Test
  @SdkSuppress(maxSdkVersion = 25)
  public void process_sdkBelow26_returnsOriginalFrame() {
    Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
    HardwareBufferFrame inputFrame =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null, directExecutor(), /* releaseCallback= */ (fence) -> {})
            .setInternalFrame(bitmap)
            .build();

    HardwareBufferFrame outputFrame = processor.process(inputFrame);

    assertThat(outputFrame).isSameInstanceAs(inputFrame);

    outputFrame.release(null);
  }

  @Test
  @SdkSuppress(minSdkVersion = 26)
  public void process_repeatedBitmap_reusesSameBuffer() throws IOException {
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap("first_frame.png");
    HardwareBufferFrame inputFrame1 = createBitmapFrame(inputBitmap);
    HardwareBufferFrame inputFrame2 = createBitmapFrame(inputBitmap);

    HardwareBufferFrame outputFrame1 = processor.process(inputFrame1);
    HardwareBufferFrame outputFrame2 = processor.process(inputFrame2);

    assertThat(outputFrame1.hardwareBuffer).isSameInstanceAs(outputFrame2.hardwareBuffer);
    assertThat(outputFrame1.hardwareBuffer.isClosed()).isFalse();

    outputFrame1.release(null);
    outputFrame2.release(null);
  }

  @Test
  @SdkSuppress(minSdkVersion = 26)
  public void process_repeatedBitmapAfterRelease_reusesSameBuffer() throws IOException {
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap("first_frame.png");
    HardwareBufferFrame inputFrame1 = createBitmapFrame(inputBitmap);
    HardwareBufferFrame inputFrame2 = createBitmapFrame(inputBitmap);

    HardwareBufferFrame outputFrame1 = processor.process(inputFrame1);
    outputFrame1.release(null);
    HardwareBufferFrame outputFrame2 = processor.process(inputFrame2);

    assertThat(outputFrame1.hardwareBuffer).isSameInstanceAs(outputFrame2.hardwareBuffer);
    assertThat(outputFrame1.hardwareBuffer.isClosed()).isFalse();

    outputFrame2.release(null);
  }

  @Test
  @SdkSuppress(minSdkVersion = 26)
  public void process_differentBitmap_createsNewBuffer() {
    Bitmap bitmap1 = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
    Bitmap bitmap2 = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);

    HardwareBufferFrame outputFrame1 = processor.process(createBitmapFrame(bitmap1));
    HardwareBufferFrame outputFrame2 = processor.process(createBitmapFrame(bitmap2));

    assertThat(outputFrame1.hardwareBuffer).isNotSameInstanceAs(outputFrame2.hardwareBuffer);

    outputFrame1.release(null);
    outputFrame2.release(null);
  }

  @Test
  @SdkSuppress(minSdkVersion = 26)
  public void process_differentGenerationId_createsNewBuffer() {
    Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);

    HardwareBufferFrame outputFrame1 = processor.process(createBitmapFrame(bitmap));
    bitmap.eraseColor(Color.RED);
    HardwareBufferFrame outputFrame2 = processor.process(createBitmapFrame(bitmap));

    assertThat(outputFrame1.hardwareBuffer).isNotSameInstanceAs(outputFrame2.hardwareBuffer);

    outputFrame1.release(null);
    outputFrame2.release(null);
  }

  @Test
  @SdkSuppress(minSdkVersion = 26)
  public void process_differentBitmap_releasesOldBuffer() {
    Bitmap bitmap1 = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
    Bitmap bitmap2 = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);

    HardwareBufferFrame outputFrame1 = processor.process(createBitmapFrame(bitmap1));
    HardwareBufferFrame outputFrame2 = processor.process(createBitmapFrame(bitmap2));
    HardwareBuffer buffer1 = outputFrame1.hardwareBuffer;

    // Processor released its hold on buffer1 when bitmap2 was converted.
    // buffer1 is still held by outputFrame1.
    assertThat(buffer1.isClosed()).isFalse();

    outputFrame1.release(null);
    assertThat(buffer1.isClosed()).isTrue();

    outputFrame2.release(null);
  }

  @Test
  @SdkSuppress(minSdkVersion = 26)
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

    assertThat(releasedLatch.await(1000, SECONDS)).isTrue();

    outputFrame.release(null);
  }

  @Test
  @SdkSuppress(minSdkVersion = 26)
  public void releaseOutputFrame_sharedBuffer_doesNotCloseSharedBuffer() throws Exception {
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap("first_frame.png");
    HardwareBufferFrame inputFrame1 = createBitmapFrame(inputBitmap);
    HardwareBufferFrame inputFrame2 = createBitmapFrame(inputBitmap);

    HardwareBufferFrame outputFrame1 = processor.process(inputFrame1);
    HardwareBufferFrame outputFrame2 = processor.process(inputFrame2);

    outputFrame1.release(null);
    assertThat(outputFrame1.hardwareBuffer.isClosed()).isFalse();
    assertThat(outputFrame2.hardwareBuffer.isClosed()).isFalse();

    outputFrame2.release(null);
    assertThat(outputFrame1.hardwareBuffer.isClosed()).isFalse();
    assertThat(outputFrame2.hardwareBuffer.isClosed()).isFalse();
  }

  @Test
  @SdkSuppress(minSdkVersion = 26)
  public void close_releasesInternalBuffer() throws Exception {
    Bitmap bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
    HardwareBufferFrame outputFrame = processor.process(createBitmapFrame(bitmap));
    HardwareBuffer hardwareBuffer = outputFrame.hardwareBuffer;

    processor.close();
    // Processor released its reference, but the frame still holds one.
    assertThat(hardwareBuffer.isClosed()).isFalse();

    outputFrame.release(null);
    assertThat(hardwareBuffer.isClosed()).isTrue();
  }

  @RequiresApi(26)
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

    return bitmap;
  }

  private static HardwareBufferFrame createBitmapFrame(Bitmap bitmap) {
    return new HardwareBufferFrame.Builder(
            /* hardwareBuffer= */ null, directExecutor(), /* releaseCallback= */ (fence) -> {})
        .setInternalFrame(bitmap)
        .build();
  }

  @RequiresApi(26)
  private static HardwareBuffer createHardwareBuffer() {
    return HardwareBuffer.create(
        10,
        10,
        /* format= */ HardwareBuffer.RGBA_8888,
        /* layers= */ 1,
        /* usage= */ HardwareBuffer.USAGE_CPU_WRITE_OFTEN | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
  }
}
