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
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.hardware.HardwareBuffer;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
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

  private static final long TIMEOUT_MS = 5_000;
  private static final int BITMAP_WIDTH = 1920;
  private static final int BITMAP_HEIGHT = 1080;

  @Rule public final TestName testName;
  private final Context context;

  private @MonotonicNonNull ListeningExecutorService glExecutorService;
  private @MonotonicNonNull GlObjectsProvider glObjectsProvider;
  private @MonotonicNonNull BitmapSavingFrameWriter frameWriter;
  private @MonotonicNonNull FakeHardwareBufferJniWrapper fakeJniWrapper;
  private @MonotonicNonNull FrameWriterGlTextureFrameConsumer frameWriterGlTextureFrameConsumer;
  private final List<Bitmap> actualBitmaps;

  public FrameWriterGlTextureFrameConsumerTest() {
    testName = new TestName();
    context = getApplicationContext();
    actualBitmaps = new CopyOnWriteArrayList<>();
  }

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

    fakeJniWrapper = new FakeHardwareBufferJniWrapper();
    frameWriter = new BitmapSavingFrameWriter(actualBitmaps);
    frameWriterGlTextureFrameConsumer =
        new FrameWriterGlTextureFrameConsumer(context, frameWriter, fakeJniWrapper);
  }

  @After
  public void tearDown() throws Exception {
    if (glExecutorService != null) {
      glExecutorService.submit(this::tearDownInternal).get();
      shutdownGlExecutorService(glExecutorService);
    }
  }

  private void tearDownInternal() {
    try {
      if (frameWriterGlTextureFrameConsumer != null) {
        frameWriterGlTextureFrameConsumer.close();
      }
      if (frameWriter != null) {
        frameWriter.close();
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

  @Test
  public void queue_portraitFrameWhenPortraitUnsupported_rotatesToLandscape() throws Exception {
    frameWriter.supportsPortrait = false;
    Bitmap landscapeBitmap = BitmapPixelTestUtil.readBitmap(TEST_IMAGE_ASSET);
    Matrix matrix = new Matrix();
    matrix.postRotate(90);
    Bitmap portraitInputBitmap =
        Bitmap.createBitmap(
            landscapeBitmap,
            /* x= */ 0,
            /* y= */ 0,
            landscapeBitmap.getWidth(),
            landscapeBitmap.getHeight(),
            matrix,
            /* filter= */ true);
    Matrix expectedMatrix = new Matrix();
    expectedMatrix.postRotate(180);
    // The input bitmap is rotated 90 clockwise, and the pipeline rotates it to landscape by another
    // 90 degrees, so the output will be 180 degrees rotated.
    Bitmap expectedBitmap =
        Bitmap.createBitmap(
            landscapeBitmap,
            /* x= */ 0,
            /* y= */ 0,
            landscapeBitmap.getWidth(),
            landscapeBitmap.getHeight(),
            expectedMatrix,
            /* filter= */ true);

    glExecutorService
        .submit(
            () -> {
              try {
                GlTextureFrame inputFrame =
                    createGlTextureFrame(portraitInputBitmap, /* presentationTimeUs= */ 1000);
                assertThat(
                        frameWriterGlTextureFrameConsumer.queue(
                            inputFrame, directExecutor(), /* wakeupListener= */ () -> {}))
                    .isTrue();
              } catch (Exception e) {
                throw new AssertionError(e);
              }
            })
        .get(TIMEOUT_MS, MILLISECONDS);

    Bitmap outputBitmap = actualBitmaps.get(0);
    assertThat(
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedBitmap, outputBitmap, testName.getMethodName()))
        .isLessThan(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);

    Format configuredFrameWriterFormat = checkNotNull(frameWriter.configuredFormat);
    assertThat(configuredFrameWriterFormat.width).isEqualTo(BITMAP_WIDTH);
    assertThat(configuredFrameWriterFormat.height).isEqualTo(BITMAP_HEIGHT);
    assertThat(configuredFrameWriterFormat.rotationDegrees).isEqualTo(270);
  }

  @Test
  public void queue_landscapeThenPortrait_outputsCorrectBitmaps() throws Exception {
    Bitmap inputBitmap1 = BitmapPixelTestUtil.readBitmap(TEST_IMAGE_ASSET); // 1920x1080
    Matrix matrix = new Matrix();
    matrix.postRotate(90);
    Bitmap inputBitmap2 =
        Bitmap.createBitmap(
            inputBitmap1,
            /* x= */ 0,
            /* y= */ 0,
            inputBitmap1.getWidth(),
            inputBitmap1.getHeight(),
            matrix,
            /* filter= */ true); // 1080x1920
    // Construct the expected pillarboxed bitmap.
    // Scale 1080x1920 to fit 1920x1080: 608x1080, hence pillar box width = (1920 - 608) / 2 = 656.
    Bitmap expectedBitmap2 =
        Bitmap.createBitmap(/* width= */ 1920, /* height= */ 1080, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(expectedBitmap2);
    canvas.drawColor(Color.BLACK); // black background
    Bitmap scaledInput2 = Bitmap.createScaledBitmap(inputBitmap2, 608, 1080, /* filter= */ true);
    canvas.drawBitmap(scaledInput2, /* left= */ 656, /* top= */ 0, /* paint= */ null);

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
                throw new AssertionError(e);
              }
            })
        .get();

    // Frame 1: 1920x1080
    Bitmap outputBitmap1 = actualBitmaps.get(0);
    assertThat(
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                inputBitmap1, outputBitmap1, testName.getMethodName() + "_frame1"))
        .isLessThan(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);

    // Frame 2: Conformed to 1920x1080 (pillarboxed)
    Bitmap outputBitmap2 = actualBitmaps.get(1);
    assertThat(
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedBitmap2, outputBitmap2, testName.getMethodName() + "_frame2"))
        .isLessThan(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  public void queue_portraitThenLandscape_outputsCorrectBitmaps() throws Exception {
    Bitmap originalBitmap = BitmapPixelTestUtil.readBitmap(TEST_IMAGE_ASSET); // 1920x1080
    Matrix matrix = new Matrix();
    matrix.postRotate(90);
    Bitmap inputBitmap1 =
        Bitmap.createBitmap(
            originalBitmap,
            /* x= */ 0,
            /* y= */ 0,
            originalBitmap.getWidth(),
            originalBitmap.getHeight(),
            matrix,
            /* filter= */ true); // 1080x1920
    Bitmap inputBitmap2 = originalBitmap; // 1920x1080 (landscape)
    // Construct the expected letterboxed bitmap.
    // Scale 1920x1080 to fit 1080x1920: 1080x608, hence letter box height = (1920 - 608) / 2 = 656.
    Bitmap expectedBitmap2 =
        Bitmap.createBitmap(/* width= */ 1080, /* height= */ 1920, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(expectedBitmap2);
    canvas.drawColor(Color.BLACK); // black background
    Bitmap scaledInput2 =
        Bitmap.createScaledBitmap(
            inputBitmap2, /* dstWidth= */ 1080, /* dstHeight= */ 608, /* filter= */ true);
    canvas.drawBitmap(scaledInput2, /* left= */ 0, /* top= */ 656, /* paint= */ null);

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
                throw new AssertionError(e);
              }
            })
        .get();

    // Frame 1: 1080x1920 (portrait)
    Bitmap outputBitmap1 = actualBitmaps.get(0);
    assertThat(
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                inputBitmap1, outputBitmap1, testName.getMethodName() + "_frame1"))
        .isLessThan(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);

    // Frame 2: Conformed to 1080x1920 (letterboxed)
    Bitmap outputBitmap2 = actualBitmaps.get(1);
    assertThat(
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedBitmap2, outputBitmap2, testName.getMethodName() + "_frame2"))
        .isLessThan(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  /**
   * Creates a {@link GlTextureFrame} that is flipped along the y-axis from the input {@link
   * Bitmap}.
   *
   * <p>This method converts the input {@link Bitmap} to the OpenGL coordinate system that {@link
   * FrameWriterGlTextureFrameConsumer} always receives.
   */
  private static GlTextureFrame createGlTextureFrame(Bitmap bitmap, long presentationTimeUs)
      throws GlException {
    int width = bitmap.getWidth();
    int height = bitmap.getHeight();
    Matrix matrix = new Matrix();
    matrix.postScale(/* sx= */ 1f, /* sy= */ -1f, width / 2f, height / 2f);
    Bitmap flippedBitmap =
        Bitmap.createBitmap(
            bitmap, /* x= */ 0, /* y= */ 0, width, height, matrix, /* filter= */ true);
    int texId = GlUtil.createTexture(flippedBitmap);
    flippedBitmap.recycle();
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
        .setFormat(
            new Format.Builder()
                .setWidth(width)
                .setHeight(height)
                .setColorInfo(ColorInfo.SRGB_BT709_FULL)
                .build())
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
    @Nullable private HardwareBuffer hardwareBuffer;
    private final List<Bitmap> outputBitmaps;
    private boolean hasCapacity;
    @Nullable private Runnable pendingWakeupListener;
    @Nullable private Executor pendingWakeupExecutor;
    private boolean supportsPortrait;
    @Nullable private Format configuredFormat;

    BitmapSavingFrameWriter(List<Bitmap> outputBitmaps) {
      this.outputBitmaps = outputBitmaps;
      supportsPortrait = true;
      hasCapacity = true;
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
      return (format, usage) -> supportsPortrait || format.width >= format.height;
    }

    @Override
    public void configure(Format format, long usage) {
      this.configuredFormat = format;
      if (hardwareBuffer == null
          || hardwareBuffer.getWidth() != format.width
          || hardwareBuffer.getHeight() != format.height) {
        if (hardwareBuffer != null) {
          hardwareBuffer.close();
        }
        hardwareBuffer =
            HardwareBuffer.create(
                format.width,
                format.height,
                HardwareBuffer.RGBA_8888,
                /* layers= */ 1,
                HardwareBuffer.USAGE_CPU_READ_OFTEN
                    | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                    | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
      }
    }

    @Override
    public AsyncFrame dequeueInputFrame(Executor wakeupExecutor, Runnable wakeupListener) {
      if (!hasCapacity) {
        pendingWakeupListener = wakeupListener;
        pendingWakeupExecutor = wakeupExecutor;
        return null;
      }
      return new AsyncFrame(
          new DefaultHardwareBufferFrame.Builder(checkNotNull(hardwareBuffer)).build(),
          /* acquireFence= */ null);
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
    public void close() {
      if (hardwareBuffer != null) {
        hardwareBuffer.close();
      }
    }
  }
}
