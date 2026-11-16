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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.util.Util.isRunningOnEmulator;
import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_COMPOSITION_EFFECTS;
import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_COMPOSITION_SEQUENCE_INDEX;
import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_COMPOSITOR_SETTINGS;
import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_ITEM_EFFECTS;
import static androidx.media3.effect.FrameProcessorUtils.RGBA_8888_BYTES_PER_PIXEL;
import static androidx.media3.effect.FrameProcessorUtils.runAllAndAccumulateExceptions;
import static androidx.media3.effect.FrameProcessorUtils.shutdownGlExecutorService;
import static androidx.media3.test.utils.BitmapPixelTestUtil.MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createArgb8888BitmapWithSolidColor;
import static androidx.media3.test.utils.BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888;
import static androidx.media3.test.utils.TestUtil.PSNR_THRESHOLD;
import static androidx.media3.test.utils.TestUtil.assertBitmapsAreSimilar;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static java.lang.Math.round;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import androidx.annotation.ColorInt;
import androidx.annotation.RequiresApi;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.DefaultImagePlanesFrame;
import androidx.media3.common.video.DefaultImagePlanesFrame.DefaultPlane;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.common.video.ImagePlanesFrame;
import androidx.media3.common.video.ImagePlanesFrame.Plane;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.test.utils.FakeFrameProcessor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link DefaultGlFrameProcessor}. */
@RunWith(AndroidJUnit4.class)
public final class DefaultGlFrameProcessorTest {

  private static final long TEST_TIMEOUT_MS = isRunningOnEmulator() ? 20_000L : 10_000L;
  private static final int DEFAULT_WIDTH = 64;
  private static final int DEFAULT_HEIGHT = 32;
  private static final ImmutableMap<String, Object> DEFAULT_FRAME_METADATA =
      ImmutableMap.of(
          KEY_COMPOSITION_SEQUENCE_INDEX,
          0,
          KEY_COMPOSITOR_SETTINGS,
          VideoCompositorSettings.DEFAULT,
          KEY_COMPOSITION_EFFECTS,
          ImmutableList.of(),
          KEY_ITEM_EFFECTS,
          ImmutableList.of());

  @Rule public final TestName testName = new TestName();

  private final Context context = getApplicationContext();

  private @MonotonicNonNull List<HardwareBuffer> hardwareBuffersToClose;
  private @MonotonicNonNull ListeningExecutorService glExecutorService;
  private @MonotonicNonNull ConditionVariable frameProcessedOrFailedCondition;
  private FakeFrameProcessor.@MonotonicNonNull Listener listener;
  private @MonotonicNonNull BitmapSavingFrameWriter frameWriter;
  private @MonotonicNonNull DefaultGlFrameProcessor processor;

  @Before
  public void setUp() {
    hardwareBuffersToClose = new ArrayList<>();
    glExecutorService = listeningDecorator(Executors.newSingleThreadExecutor());
    frameProcessedOrFailedCondition = new ConditionVariable();
    listener =
        new FakeFrameProcessor.Listener(TEST_TIMEOUT_MS) {
          @Override
          public void onError(VideoFrameProcessingException exception) {
            super.onError(exception);
            frameProcessedOrFailedCondition.open();
          }

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {
            super.onFrameProcessed(frame, onCompleteFence);
            frameProcessedOrFailedCondition.open();
          }
        };
  }

  @After
  public void tearDown() throws Exception {
    runAllAndAccumulateExceptions(
        () -> {
          if (processor != null) {
            processor.close();
          }
        },
        () ->
            glExecutorService
                .submit(() -> {
                  if (frameWriter != null) {
                    frameWriter.close();
                  }
                  return null;
                })
                .get(TEST_TIMEOUT_MS, MILLISECONDS),
        () -> shutdownGlExecutorService(glExecutorService),
        () -> {
          if (SDK_INT >= 26) {
            closeHardwareBuffers();
          }
        });
  }

  @Test
  public void queue_rgbaImagePlanesFrameWithRgbaFrameWriter_outputsExpectedFrame()
      throws Exception {
    setUpProcessor(BitmapSavingFrameWriter.createForRgbaImagePlanes(TEST_TIMEOUT_MS));
    Bitmap expectedBitmap =
        createArgb8888BitmapWithSolidColor(DEFAULT_WIDTH, DEFAULT_HEIGHT, Color.GREEN);
    ImagePlanesFrame inputFrame = createRgbaFrame(Color.GREEN);

    boolean queued = queueFrameAndAwait(inputFrame);

    assertThat(queued).isTrue();
    assertThat(listener.error.get()).isNull();
    List<Bitmap> outputBitmaps = frameWriter.outputBitmaps;
    assertThat(outputBitmaps).hasSize(1);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedBitmap, outputBitmaps.get(0), testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
    assertThat(listener.processedFrames).containsExactly(inputFrame);
  }

  @Test
  public void queue_rgbaImagePlanesFrameWithYv12FrameWriter_outputsExpectedFrame()
      throws Exception {
    setUpProcessor(BitmapSavingFrameWriter.createForYv12ImagePlanes(TEST_TIMEOUT_MS));
    Bitmap expectedBitmap =
        createArgb8888BitmapWithSolidColor(DEFAULT_WIDTH, DEFAULT_HEIGHT, Color.GREEN);
    ImagePlanesFrame inputFrame = createRgbaFrame(Color.GREEN);

    boolean queued = queueFrameAndAwait(inputFrame);

    assertThat(queued).isTrue();
    assertThat(listener.error.get()).isNull();
    List<Bitmap> outputBitmaps = frameWriter.outputBitmaps;
    assertThat(outputBitmaps).hasSize(1);
    assertBitmapsAreSimilar(expectedBitmap, outputBitmaps.get(0), PSNR_THRESHOLD);
    assertThat(listener.processedFrames).containsExactly(inputFrame);
  }

  @Test
  public void queue_yuv420ImagePlanesFrameWithYv12FrameWriter_outputsExpectedFrame()
      throws Exception {
    setUpProcessor(BitmapSavingFrameWriter.createForYv12ImagePlanes(TEST_TIMEOUT_MS));
    Bitmap expectedBitmap =
        createArgb8888BitmapWithSolidColor(DEFAULT_WIDTH, DEFAULT_HEIGHT, Color.GREEN);
    ImagePlanesFrame inputFrame = createPlanarYuv420Frame(Color.GREEN);

    boolean queued = queueFrameAndAwait(inputFrame);

    assertThat(queued).isTrue();
    assertThat(listener.error.get()).isNull();
    List<Bitmap> outputBitmaps = frameWriter.outputBitmaps;
    assertThat(outputBitmaps).hasSize(1);
    assertBitmapsAreSimilar(expectedBitmap, outputBitmaps.get(0), PSNR_THRESHOLD);
    assertThat(listener.processedFrames).containsExactly(inputFrame);
  }

  @Test
  public void queue_yuv420ImagePlanesFrameWithRgbaFrameWriter_outputsExpectedFrame()
      throws Exception {
    setUpProcessor(BitmapSavingFrameWriter.createForRgbaImagePlanes(TEST_TIMEOUT_MS));
    Bitmap expectedBitmap =
        createArgb8888BitmapWithSolidColor(DEFAULT_WIDTH, DEFAULT_HEIGHT, Color.GREEN);
    ImagePlanesFrame inputFrame = createPlanarYuv420Frame(Color.GREEN);

    boolean queued = queueFrameAndAwait(inputFrame);

    assertThat(queued).isTrue();
    assertThat(listener.error.get()).isNull();
    List<Bitmap> outputBitmaps = frameWriter.outputBitmaps;
    assertThat(outputBitmaps).hasSize(1);
    assertBitmapsAreSimilar(expectedBitmap, outputBitmaps.get(0), PSNR_THRESHOLD);
    assertThat(listener.processedFrames).containsExactly(inputFrame);
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void queue_hardwareBufferFrame_outputsExpectedFrame() throws Exception {
    setUpProcessor(BitmapSavingFrameWriter.createForHardwareBuffer(TEST_TIMEOUT_MS));
    Bitmap expectedBitmap =
        createArgb8888BitmapWithSolidColor(DEFAULT_WIDTH, DEFAULT_HEIGHT, Color.BLUE);
    HardwareBufferFrame inputFrame = createHardwareBufferFrame(expectedBitmap);

    boolean queued = queueFrameAndAwait(inputFrame);

    assertThat(queued).isTrue();
    assertThat(listener.error.get()).isNull();
    List<Bitmap> outputBitmaps = frameWriter.outputBitmaps;
    assertThat(outputBitmaps).hasSize(1);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedBitmap, outputBitmaps.get(0), testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
    assertThat(listener.processedFrames).containsExactly(inputFrame);
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void queue_imagePlanesFrameAfterHardwareBufferFrame_outputsExpectedFrames()
      throws Exception {
    setUpProcessor(BitmapSavingFrameWriter.createForHardwareBuffer(TEST_TIMEOUT_MS));
    Bitmap expectedHardwareBitmap =
        createArgb8888BitmapWithSolidColor(DEFAULT_WIDTH, DEFAULT_HEIGHT, Color.BLUE);
    HardwareBufferFrame hardwareBufferFrame = createHardwareBufferFrame(expectedHardwareBitmap);
    Bitmap expectedPlanesBitmap =
        createArgb8888BitmapWithSolidColor(DEFAULT_WIDTH, DEFAULT_HEIGHT, Color.GREEN);
    ImagePlanesFrame imagePlanesFrame = createRgbaFrame(Color.GREEN);

    boolean firstFrameQueued = queueFrameAndAwait(hardwareBufferFrame);
    boolean secondFrameQueued = queueFrameAndAwait(imagePlanesFrame);

    assertThat(firstFrameQueued).isTrue();
    assertThat(secondFrameQueued).isTrue();
    assertThat(listener.error.get()).isNull();
    List<Bitmap> outputBitmaps = frameWriter.outputBitmaps;
    assertThat(outputBitmaps).hasSize(2);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedHardwareBitmap, outputBitmaps.get(0), testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedPlanesBitmap, outputBitmaps.get(1), testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
    assertThat(listener.processedFrames)
        .containsExactly(hardwareBufferFrame, imagePlanesFrame)
        .inOrder();
  }

  private void setUpProcessor(BitmapSavingFrameWriter frameWriter) {
    this.frameWriter = frameWriter;
    processor =
        new DefaultGlFrameProcessor.Factory.Builder(context)
            .setExecutorService(glExecutorService)
            .build()
            .create(frameWriter, directExecutor(), listener);
  }

  private boolean queueFrameAndAwait(Frame inputFrame) throws Exception {
    frameProcessedOrFailedCondition.close();
    boolean queued =
        processor.queue(ImmutableList.of(new AsyncFrame(inputFrame, /* acquireFence= */ null)));
    if (queued) {
      checkState(frameProcessedOrFailedCondition.block(TEST_TIMEOUT_MS));
      glExecutorService.submit(() -> {}).get(TEST_TIMEOUT_MS, MILLISECONDS);
    }
    return queued;
  }

  @RequiresApi(26)
  private void closeHardwareBuffers() {
    for (HardwareBuffer hardwareBuffer : hardwareBuffersToClose) {
      hardwareBuffer.close();
    }
    hardwareBuffersToClose.clear();
  }

  @RequiresApi(26)
  private HardwareBufferFrame createHardwareBufferFrame(Bitmap bitmap) {
    HardwareBuffer hardwareBuffer =
        HardwareBuffer.create(
            bitmap.getWidth(),
            bitmap.getHeight(),
            HardwareBuffer.RGBA_8888,
            /* layers= */ 1,
            HardwareBuffer.USAGE_CPU_WRITE_OFTEN | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
    hardwareBuffersToClose.add(hardwareBuffer);
    checkState(HardwareBufferJni.INSTANCE.nativeCopyBitmapToHardwareBuffer(bitmap, hardwareBuffer));
    return new DefaultHardwareBufferFrame.Builder(hardwareBuffer)
        .setFormat(
            new Format.Builder()
                .setWidth(bitmap.getWidth())
                .setHeight(bitmap.getHeight())
                .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
                .build())
        .setMetadata(DEFAULT_FRAME_METADATA)
        .build();
  }

  private ImagePlanesFrame createRgbaFrame(@ColorInt int color) {
    int rowStride = DEFAULT_WIDTH * RGBA_8888_BYTES_PER_PIXEL;
    ByteBuffer buffer = ByteBuffer.allocateDirect(rowStride * DEFAULT_HEIGHT);
    for (int i = 0; i < DEFAULT_WIDTH * DEFAULT_HEIGHT; i++) {
      buffer.put((byte) Color.red(color));
      buffer.put((byte) Color.green(color));
      buffer.put((byte) Color.blue(color));
      buffer.put((byte) Color.alpha(color));
    }
    buffer.flip();
    Plane plane = new DefaultPlane(buffer, rowStride, /* pixelStride= */ RGBA_8888_BYTES_PER_PIXEL);
    Format format =
        new Format.Builder()
            .setWidth(DEFAULT_WIDTH)
            .setHeight(DEFAULT_HEIGHT)
            .setPixelFormat(PixelFormat.RGBA_8888)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();
    return new DefaultImagePlanesFrame.Builder(ImmutableList.of(plane), format)
        .setMetadata(DEFAULT_FRAME_METADATA)
        .build();
  }

  private static ImagePlanesFrame createPlanarYuv420Frame(@ColorInt int color) {
    double r = Color.red(color) / 255.0;
    double g = Color.green(color) / 255.0;
    double b = Color.blue(color) / 255.0;
    // ITU-R BT.709 limited-range (studio-swing) RGB-to-YUV conversion.
    byte y = (byte) round(16.0 + 219.0 * (0.2126 * r + 0.7152 * g + 0.0722 * b));
    byte u = (byte) round(128.0 + 224.0 * (-0.114572 * r - 0.385428 * g + 0.5 * b));
    byte v = (byte) round(128.0 + 224.0 * (0.5 * r - 0.454153 * g - 0.045847 * b));
    int chromaWidth = (DEFAULT_WIDTH + 1) / 2;
    int chromaHeight = (DEFAULT_HEIGHT + 1) / 2;
    ImmutableList<Plane> planes =
        ImmutableList.of(
            createSolidPlane(DEFAULT_WIDTH, DEFAULT_HEIGHT, y),
            createSolidPlane(chromaWidth, chromaHeight, u),
            createSolidPlane(chromaWidth, chromaHeight, v));
    Format format =
        new Format.Builder()
            .setWidth(DEFAULT_WIDTH)
            .setHeight(DEFAULT_HEIGHT)
            .setPixelFormat(ImageFormat.YUV_420_888)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();
    return new DefaultImagePlanesFrame.Builder(planes, format)
        .setMetadata(DEFAULT_FRAME_METADATA)
        .build();
  }

  private static Plane createSolidPlane(int width, int height, byte value) {
    ByteBuffer buffer = ByteBuffer.allocateDirect(width * height);
    for (int i = 0; i < width * height; i++) {
      buffer.put(value);
    }
    buffer.flip();
    return new DefaultPlane(buffer, /* rowStride= */ width, /* pixelStride= */ 1);
  }
}
