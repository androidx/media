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
package androidx.media3.effect;

import static androidx.media3.effect.FrameProcessorUtils.releaseOpenGl;
import static androidx.media3.effect.FrameProcessorUtils.setupOpenGl;
import static androidx.media3.effect.FrameProcessorUtils.shutdownGlExecutorService;
import static androidx.media3.test.utils.BitmapPixelTestUtil.MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static java.util.concurrent.Executors.newSingleThreadExecutor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.effect.ndk.HardwareBufferJni;
import androidx.media3.test.utils.BitmapPixelTestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Instrumentation test for {@link FrameWriterGlTextureFrameConsumer}. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29)
public final class FrameWriterGlTextureFrameConsumerTest {

  private static final String TEST_IMAGE_ASSET = "media/png/first_frame_1920x1080.png";
  private static final int BITMAP_WIDTH = 1920;
  private static final int BITMAP_HEIGHT = 1080;

  @Rule public final TestName testName = new TestName();
  private final Context context = getApplicationContext();

  private @MonotonicNonNull ListeningExecutorService glExecutorService;
  private @MonotonicNonNull GlObjectsProvider glObjectsProvider;
  private @MonotonicNonNull HardwareBuffer frameWriterHardwareBuffer;
  private @MonotonicNonNull BitmapSavingFrameWriter frameWriter;
  private @MonotonicNonNull FakeHardwareBufferJniWrapper fakeJniWrapper;
  private @MonotonicNonNull FrameWriterGlTextureFrameConsumer frameWriterGlTextureFrameConsumer;
  private final List<Bitmap> actualBitmaps = new CopyOnWriteArrayList<>();

  @Before
  public void setUp() throws Exception {
    glExecutorService = listeningDecorator(newSingleThreadExecutor());
    glObjectsProvider = new DefaultGlObjectsProvider();
    glExecutorService
        .submit(
            () -> {
              setupOpenGl(glObjectsProvider);
              return null;
            })
        .get();

    frameWriterHardwareBuffer =
        HardwareBuffer.create(
            BITMAP_WIDTH,
            BITMAP_HEIGHT,
            HardwareBuffer.RGBA_8888,
            /* layers= */ 1,
            HardwareBuffer.USAGE_CPU_READ_OFTEN
                | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);

    fakeJniWrapper = new FakeHardwareBufferJniWrapper();
    frameWriter = new BitmapSavingFrameWriter(frameWriterHardwareBuffer, actualBitmaps);
    frameWriterGlTextureFrameConsumer =
        new FrameWriterGlTextureFrameConsumer(context, frameWriter, fakeJniWrapper);
  }

  @After
  public void tearDown() throws Exception {
    if (glExecutorService != null) {
      glExecutorService.submit(this::tearDownInternal).get();
      shutdownGlExecutorService(glExecutorService);
    }
    if (frameWriterHardwareBuffer != null) {
      frameWriterHardwareBuffer.close();
    }
  }

  private void tearDownInternal() {
    try {
      if (frameWriterGlTextureFrameConsumer != null) {
        frameWriterGlTextureFrameConsumer.close();
      }
      if (glObjectsProvider != null) {
        releaseOpenGl(glObjectsProvider);
      }
    } catch (VideoFrameProcessingException | GlException e) {
      // Ignore release failures.
    }
  }

  @Test
  public void queue_copiesPixelsCorrectly() throws Exception {
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap(TEST_IMAGE_ASSET);

    glExecutorService
        .submit(
            () -> {
              try {
                GlTextureFrame inputFrame =
                    createGlTextureFrame(inputBitmap, /* presentationTimeUs= */ 1000);
                assertThat(
                        frameWriterGlTextureFrameConsumer.queue(
                            inputFrame, directExecutor(), /* wakeupListener= */ () -> {}))
                    .isTrue();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            })
        .get();

    assertThat(actualBitmaps).hasSize(1);
    assertThat(fakeJniWrapper.destroyEglImageCount).isEqualTo(1);
    Bitmap outputBitmap = actualBitmaps.get(0);
    assertThat(outputBitmap).isNotNull();
    assertThat(
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                inputBitmap, outputBitmap, testName.getMethodName()))
        .isLessThan(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  public void queue_multipleFrames_copiesPixelsCorrectly() throws Exception {
    Bitmap inputBitmap1 = BitmapPixelTestUtil.readBitmap(TEST_IMAGE_ASSET);
    Bitmap inputBitmap2 = inputBitmap1.copy(Bitmap.Config.ARGB_8888, /* isMutable= */ true);
    new Canvas(inputBitmap2).drawColor(Color.BLUE);

    glExecutorService
        .submit(
            () -> {
              try {
                GlTextureFrame inputFrame1 =
                    createGlTextureFrame(inputBitmap1, /* presentationTimeUs= */ 1000);
                assertThat(
                        frameWriterGlTextureFrameConsumer.queue(
                            inputFrame1, directExecutor(), /* wakeupListener= */ () -> {}))
                    .isTrue();

                GlTextureFrame inputFrame2 =
                    createGlTextureFrame(inputBitmap2, /* presentationTimeUs= */ 2000);
                assertThat(
                        frameWriterGlTextureFrameConsumer.queue(
                            inputFrame2, directExecutor(), /* wakeupListener= */ () -> {}))
                    .isTrue();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            })
        .get();

    assertThat(actualBitmaps).hasSize(2);
    assertThat(fakeJniWrapper.destroyEglImageCount).isEqualTo(2);
    Bitmap outputBitmap1 = actualBitmaps.get(0);
    assertThat(outputBitmap1).isNotNull();
    assertThat(
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                inputBitmap1, outputBitmap1, testName.getMethodName() + "_frame1"))
        .isLessThan(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);

    Bitmap outputBitmap2 = actualBitmaps.get(1);
    assertThat(outputBitmap2).isNotNull();
    assertThat(
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                inputBitmap2, outputBitmap2, testName.getMethodName() + "_frame2"))
        .isLessThan(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  public void queue_whenFrameWriterHasNoCapacity_notifiesWakeupListener() throws Exception {
    Bitmap inputBitmap1 = BitmapPixelTestUtil.readBitmap(TEST_IMAGE_ASSET);
    Bitmap inputBitmap2 = inputBitmap1.copy(Bitmap.Config.ARGB_8888, /* isMutable= */ true);
    new Canvas(inputBitmap2).drawColor(Color.BLUE);

    // Queue first frame successfully.
    glExecutorService
        .submit(
            () -> {
              try {
                GlTextureFrame inputFrame1 =
                    createGlTextureFrame(inputBitmap1, /* presentationTimeUs= */ 1000);
                assertThat(
                        frameWriterGlTextureFrameConsumer.queue(
                            inputFrame1, directExecutor(), /* wakeupListener= */ () -> {}))
                    .isTrue();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            })
        .get();

    assertThat(actualBitmaps).hasSize(1);
    assertThat(fakeJniWrapper.destroyEglImageCount).isEqualTo(1);

    // Set frameWriter capacity to false, try queueing second frame and fail.
    frameWriter.setCapacity(false);
    List<Boolean> wakeupNotified = new CopyOnWriteArrayList<>();
    AtomicReference<GlTextureFrame> inputFrame2 = new AtomicReference<>();

    glExecutorService
        .submit(
            () -> {
              try {
                inputFrame2.set(createGlTextureFrame(inputBitmap2, /* presentationTimeUs= */ 2000));
                assertThat(
                        frameWriterGlTextureFrameConsumer.queue(
                            inputFrame2.get(),
                            directExecutor(),
                            /* wakeupListener= */ () -> wakeupNotified.add(true)))
                    .isFalse();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            })
        .get();

    assertThat(actualBitmaps).hasSize(1);
    assertThat(wakeupNotified).isEmpty();
    assertThat(fakeJniWrapper.destroyEglImageCount).isEqualTo(1);

    // Restore capacity, verify wakeupListener is called, and queue second frame again succeeds.
    frameWriter.setCapacity(true);
    assertThat(wakeupNotified).containsExactly(true);

    glExecutorService
        .submit(
            () -> {
              try {
                assertThat(
                        frameWriterGlTextureFrameConsumer.queue(
                            inputFrame2.get(), directExecutor(), /* wakeupListener= */ () -> {}))
                    .isTrue();
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            })
        .get();

    assertThat(actualBitmaps).hasSize(2);
    assertThat(fakeJniWrapper.destroyEglImageCount).isEqualTo(2);
    Bitmap outputBitmap1 = actualBitmaps.get(0);
    assertThat(outputBitmap1).isNotNull();
    assertThat(
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                inputBitmap1, outputBitmap1, testName.getMethodName() + "_frame1"))
        .isLessThan(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);

    Bitmap outputBitmap2 = actualBitmaps.get(1);
    assertThat(outputBitmap2).isNotNull();
    assertThat(
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                inputBitmap2, outputBitmap2, testName.getMethodName() + "_frame2"))
        .isLessThan(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  private static GlTextureFrame createGlTextureFrame(Bitmap bitmap, long presentationTimeUs)
      throws GlException {
    int width = bitmap.getWidth();
    int height = bitmap.getHeight();
    int texId = GlUtil.createTexture(bitmap);
    return new GlTextureFrame.Builder(
            new GlTextureInfo(
                /* texId= */ texId,
                /* fboId= */ C.INDEX_UNSET,
                /* rboId= */ C.INDEX_UNSET,
                width,
                height),
            directExecutor(),
            /* releaseTextureCallback= */ textureInfo -> {
              try {
                GlUtil.deleteTexture(textureInfo.texId);
              } catch (GlException e) {
                // Ignore.
              }
            })
        .setPresentationTimeUs(presentationTimeUs)
        .setFormat(new Format.Builder().setWidth(width).setHeight(height).build())
        .build();
  }

  private static final class FakeHardwareBufferJniWrapper implements HardwareBufferJniWrapper {
    private final HardwareBufferJniWrapper delegate = HardwareBufferJni.INSTANCE;
    int destroyEglImageCount;

    @Override
    public long nativeCreateEglImageFromHardwareBuffer(
        long displayHandle, HardwareBuffer hardwareBuffer) {
      return delegate.nativeCreateEglImageFromHardwareBuffer(displayHandle, hardwareBuffer);
    }

    @Override
    public boolean nativeBindEGLImage(int target, long eglImageHandle) {
      return delegate.nativeBindEGLImage(target, eglImageHandle);
    }

    @Override
    public boolean nativeDestroyEGLImage(long displayHandle, long imageHandle) {
      destroyEglImageCount++;
      return delegate.nativeDestroyEGLImage(displayHandle, imageHandle);
    }

    @Override
    public boolean nativeCopyBitmapToHardwareBuffer(Bitmap bitmap, HardwareBuffer hb) {
      return delegate.nativeCopyBitmapToHardwareBuffer(bitmap, hb);
    }

    @Override
    public boolean nativeCopyHardwareBufferToHardwareBuffer(
        HardwareBuffer srcHb, HardwareBuffer dstHb) {
      return delegate.nativeCopyHardwareBufferToHardwareBuffer(srcHb, dstHb);
    }
  }

  private static final class BitmapSavingFrameWriter implements FrameWriter {
    private final HardwareBuffer hardwareBuffer;
    private final List<Bitmap> outputBitmaps;
    private boolean hasCapacity = true;
    @Nullable private Runnable pendingWakeupListener;
    @Nullable private Executor pendingWakeupExecutor;

    BitmapSavingFrameWriter(HardwareBuffer hardwareBuffer, List<Bitmap> outputBitmaps) {
      this.hardwareBuffer = hardwareBuffer;
      this.outputBitmaps = outputBitmaps;
    }

    void setCapacity(boolean hasCapacity) {
      this.hasCapacity = hasCapacity;
      if (hasCapacity && pendingWakeupListener != null && pendingWakeupExecutor != null) {
        pendingWakeupExecutor.execute(pendingWakeupListener);
        pendingWakeupListener = null;
        pendingWakeupExecutor = null;
      }
    }

    @Override
    public Info getInfo() {
      return (format, usage) -> true;
    }

    @Override
    public void configure(Format format, long usage) {}

    @Override
    public AsyncFrame dequeueInputFrame(Executor wakeupExecutor, Runnable wakeupListener) {
      if (!hasCapacity) {
        pendingWakeupListener = wakeupListener;
        pendingWakeupExecutor = wakeupExecutor;
        return null;
      }
      return new AsyncFrame(
          new DefaultHardwareBufferFrame.Builder(hardwareBuffer).build(), /* acquireFence= */ null);
    }

    @Override
    public void queueInputFrame(Frame frame, @Nullable SyncFenceWrapper writeCompleteFence) {
      try {
        if (writeCompleteFence != null) {
          if (!writeCompleteFence.awaitMs(500)) {
            throw new IllegalStateException("Fence wait timeout");
          }
          writeCompleteFence.close();
        }
        checkArgument(frame instanceof HardwareBufferFrame);
        HardwareBuffer hardwareBuffer = ((HardwareBufferFrame) frame).getHardwareBuffer();
        Bitmap hardwareBitmap =
            Bitmap.wrapHardwareBuffer(hardwareBuffer, ColorSpace.get(ColorSpace.Named.SRGB));
        outputBitmaps.add(hardwareBitmap.copy(Bitmap.Config.ARGB_8888, /* isMutable= */ false));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public void signalEndOfStream() {}

    @Override
    public void close() {}
  }
}
