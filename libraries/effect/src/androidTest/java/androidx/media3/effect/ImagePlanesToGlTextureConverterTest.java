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

import static androidx.media3.common.util.Util.isRunningOnEmulator;
import static androidx.media3.effect.DefaultGlFrameProcessor.BT709_LINEAR;
import static androidx.media3.effect.DefaultGlFrameProcessor.BT709_SRGB;
import static androidx.media3.effect.FrameProcessorUtils.releaseOpenGl;
import static androidx.media3.effect.FrameProcessorUtils.setupOpenGl;
import static androidx.media3.effect.FrameProcessorUtils.shutdownGlExecutorService;
import static androidx.media3.test.utils.BitmapPixelTestUtil.MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE;
import static androidx.media3.test.utils.BitmapPixelTestUtil.MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE_DIFFERENT_DEVICE_FP16;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createArgb8888BitmapFromGlTexture;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createArgb8888BitmapWithSolidColor;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createFp16BitmapFromGlTexture;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createFp16BitmapWithSolidColor;
import static androidx.media3.test.utils.BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888;
import static androidx.media3.test.utils.BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceFp16;
import static androidx.media3.test.utils.BitmapPixelTestUtil.readBitmap;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.video.DefaultImagePlanesFrame;
import androidx.media3.common.video.DefaultImagePlanesFrame.DefaultPlane;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.ImagePlanesFrame;
import androidx.media3.common.video.ImagePlanesFrame.Plane;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.test.utils.FakeFrameProcessor;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link ImagePlanesToGlTextureConverter}. */
@RunWith(TestParameterInjector.class)
public final class ImagePlanesToGlTextureConverterTest {

  private static final long TEST_TIMEOUT_MS = isRunningOnEmulator() ? 20_000L : 10_000L;
  private static final int DEFAULT_FRAME_WIDTH = 64;
  private static final int DEFAULT_FRAME_HEIGHT = 32;
  private static final int DEFAULT_COLOR = Color.GREEN;
  private static final Bitmap EXPECTED_DEFAULT_COLOR_BITMAP =
      createArgb8888BitmapWithSolidColor(DEFAULT_FRAME_WIDTH, DEFAULT_FRAME_HEIGHT, DEFAULT_COLOR);

  /**
   * Number of padding pixels appended to each row, used to simulate producers that align rows to a
   * stride wider than the visible image.
   */
  private static final int RGBA_ROW_PADDING_PIXELS = 7;

  /** Padding fill color, chosen to contrast strongly with {@link #DEFAULT_COLOR}. */
  private static final int RGBA_PADDING_COLOR = Color.RED;

  @Rule public final TestName testName = new TestName();

  private final Context context = getApplicationContext();

  private @MonotonicNonNull ListeningExecutorService glExecutorService;
  private @MonotonicNonNull GlObjectsProvider glObjectsProvider;
  private @MonotonicNonNull ImagePlanesToGlTextureConverter converter;

  @Before
  public void setUp() throws Exception {
    glExecutorService = listeningDecorator(Executors.newSingleThreadExecutor());
    glObjectsProvider = new DefaultGlObjectsProvider();
    glExecutorService
        .submit(() -> setupOpenGl(glObjectsProvider))
        .get(TEST_TIMEOUT_MS, MILLISECONDS);
    converter =
        new ImagePlanesToGlTextureConverter(context, BT709_SRGB, /* errorConsumer= */ e -> {});
  }

  @After
  public void tearDown() throws Exception {
    if (glExecutorService != null) {
      glExecutorService.submit(this::tearDownInternal).get(TEST_TIMEOUT_MS, MILLISECONDS);
      shutdownGlExecutorService(glExecutorService);
    }
  }

  private void tearDownInternal() {
    try {
      if (converter != null) {
        // Releases all active and cached output textures created during the test.
        converter.close();
      }
      if (glObjectsProvider != null) {
        releaseOpenGl(glObjectsProvider);
      }
    } catch (GlException | VideoFrameProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  public void convert_validFrame_setsOutputFormat() throws Exception {
    ImagePlanesFrame frame = createRgbaFrame(DEFAULT_COLOR);

    GlTextureFrame glTextureFrame = convert(frame);

    assertThat(glTextureFrame.glTextureInfo.texId).isNotEqualTo(C.INDEX_UNSET);
    assertThat(glTextureFrame.format.width).isEqualTo(DEFAULT_FRAME_WIDTH);
    assertThat(glTextureFrame.format.height).isEqualTo(DEFAULT_FRAME_HEIGHT);
    assertThat(glTextureFrame.format.rotationDegrees).isEqualTo(0);
    assertThat(glTextureFrame.format.colorInfo).isEqualTo(BT709_SRGB);
  }

  @Test
  public void convert_rotatedFrame_rotatesPixelsAndTransposesOutputDimensions() throws Exception {
    int rotationDegrees = 90;
    Bitmap inputBitmap = readBitmap("media/png/first_frame_1920x1080.png");
    ByteBuffer buffer = ByteBuffer.allocateDirect(inputBitmap.getByteCount());
    inputBitmap.copyPixelsToBuffer(buffer);
    buffer.rewind();
    Plane plane =
        new DefaultPlane(buffer, /* rowStride= */ inputBitmap.getRowBytes(), /* pixelStride= */ 4);
    Format inputFormat =
        new Format.Builder()
            .setWidth(inputBitmap.getWidth())
            .setHeight(inputBitmap.getHeight())
            .setRotationDegrees(rotationDegrees)
            .setPixelFormat(PixelFormat.RGBA_8888)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();
    ImagePlanesFrame rotatedFrame =
        new DefaultImagePlanesFrame.Builder(ImmutableList.of(plane)).setFormat(inputFormat).build();
    Matrix rotationMatrix = new Matrix();
    rotationMatrix.postRotate(rotationDegrees);
    Bitmap expectedBitmap =
        Bitmap.createBitmap(
            inputBitmap,
            /* x= */ 0,
            /* y= */ 0,
            inputBitmap.getWidth(),
            inputBitmap.getHeight(),
            rotationMatrix,
            /* filter= */ true);

    GlTextureFrame glTextureFrame = convert(rotatedFrame);

    Bitmap actualBitmap = createArgb8888BitmapFromGlTextureFrame(glTextureFrame);
    assertThat(glTextureFrame.glTextureInfo.width).isEqualTo(inputBitmap.getHeight());
    assertThat(glTextureFrame.glTextureInfo.height).isEqualTo(inputBitmap.getWidth());
    assertThat(glTextureFrame.format.width).isEqualTo(inputBitmap.getHeight());
    assertThat(glTextureFrame.format.height).isEqualTo(inputBitmap.getWidth());
    assertThat(glTextureFrame.format.rotationDegrees).isEqualTo(0);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedBitmap, actualBitmap, testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  public void convert_rgbaPlanesFrame_outputsCorrectGlTexture(
      @TestParameter boolean usePaddedRowStride) throws Exception {
    ImagePlanesFrame rgbaFrame =
        createRgbaFrame(DEFAULT_COLOR, usePaddedRowStride ? RGBA_ROW_PADDING_PIXELS : 0);

    GlTextureFrame glTextureFrame = convert(rgbaFrame);

    Bitmap actualBitmap = createArgb8888BitmapFromGlTextureFrame(glTextureFrame);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                EXPECTED_DEFAULT_COLOR_BITMAP, actualBitmap, testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  public void convert_multipleFramesWithVaryingDimensionsAndStrides_outputsCorrectGlTextures()
      throws Exception {
    ImagePlanesFrame firstFrame =
        createRgbaFrame(
            Color.GREEN,
            /* rowPaddingPixels= */ RGBA_ROW_PADDING_PIXELS,
            /* width= */ 64,
            /* height= */ 32);
    ImagePlanesFrame secondFrameSameDimensions =
        createRgbaFrame(
            Color.BLUE,
            /* rowPaddingPixels= */ RGBA_ROW_PADDING_PIXELS,
            /* width= */ 64,
            /* height= */ 32);
    ImagePlanesFrame thirdFrameDifferentDimensions =
        createRgbaFrame(Color.YELLOW, /* rowPaddingPixels= */ 0, /* width= */ 32, /* height= */ 16);

    GlTextureFrame firstGlTextureFrame = convert(firstFrame);
    int firstTexId = firstGlTextureFrame.glTextureInfo.texId;
    Bitmap firstBitmap = createArgb8888BitmapFromGlTextureFrame(firstGlTextureFrame);
    firstGlTextureFrame.release(/* releaseFence= */ null);
    GlTextureFrame secondGlTextureFrame = convert(secondFrameSameDimensions);
    int secondTexId = secondGlTextureFrame.glTextureInfo.texId;
    Bitmap secondBitmap = createArgb8888BitmapFromGlTextureFrame(secondGlTextureFrame);
    secondGlTextureFrame.release(/* releaseFence= */ null);
    GlTextureFrame thirdGlTextureFrame = convert(thirdFrameDifferentDimensions);
    Bitmap thirdBitmap = createArgb8888BitmapFromGlTextureFrame(thirdGlTextureFrame);
    thirdGlTextureFrame.release(/* releaseFence= */ null);

    assertThat(firstGlTextureFrame.glTextureInfo.width).isEqualTo(64);
    assertThat(firstGlTextureFrame.glTextureInfo.height).isEqualTo(32);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createArgb8888BitmapWithSolidColor(/* width= */ 64, /* height= */ 32, Color.GREEN),
                firstBitmap,
                testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
    assertThat(secondTexId).isEqualTo(firstTexId);
    assertThat(secondGlTextureFrame.glTextureInfo.width).isEqualTo(64);
    assertThat(secondGlTextureFrame.glTextureInfo.height).isEqualTo(32);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createArgb8888BitmapWithSolidColor(/* width= */ 64, /* height= */ 32, Color.BLUE),
                secondBitmap,
                testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
    assertThat(thirdGlTextureFrame.glTextureInfo.width).isEqualTo(32);
    assertThat(thirdGlTextureFrame.glTextureInfo.height).isEqualTo(16);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createArgb8888BitmapWithSolidColor(/* width= */ 32, /* height= */ 16, Color.YELLOW),
                thirdBitmap,
                testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @SdkSuppress(minSdkVersion = 29)
  @Test
  public void convert_rgbaPlanesFrameWithLinearOutputTransfer_appliesSrgbEotf() throws Exception {
    int srgbMidTone = Color.rgb(128, 128, 128);
    // srgbEotf(128 / 255.0) = ((128 / 255.0 + 0.055) / 1.055)^2.4 = 0.2158605f
    float expectedLinearChannel = 0.2158605f;
    Bitmap expectedLinearBitmap =
        createFp16BitmapWithSolidColor(
            DEFAULT_FRAME_WIDTH,
            DEFAULT_FRAME_HEIGHT,
            expectedLinearChannel,
            expectedLinearChannel,
            expectedLinearChannel);
    ImagePlanesFrame rgbaFrame = createRgbaFrame(srgbMidTone);
    converter =
        new ImagePlanesToGlTextureConverter(context, BT709_LINEAR, /* errorConsumer= */ e -> {});

    GlTextureFrame glTextureFrame = convert(rgbaFrame);

    Bitmap actualBitmap = createFp16BitmapFromGlTextureFrame(glTextureFrame);
    assertThat(glTextureFrame.format.colorInfo).isEqualTo(BT709_LINEAR);
    assertThat(getBitmapAveragePixelAbsoluteDifferenceFp16(expectedLinearBitmap, actualBitmap))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE_DIFFERENT_DEVICE_FP16);
  }

  @Test
  public void release_validFrame_notifiesListener() throws Exception {
    ImagePlanesFrame frame = createRgbaFrame(DEFAULT_COLOR);
    CapturingFrameProcessorListener listener = new CapturingFrameProcessorListener();

    GlTextureFrame glTextureFrame = convert(frame, listener);
    glTextureFrame.release(/* releaseFence= */ null);

    assertThat(listener.frameProcessed.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(listener.processedFrame.get()).isSameInstanceAs(frame);
    assertThat(listener.onCompleteFence.get()).isNull();
  }

  @Test
  public void release_afterReleasingGlResources_doesNotNotifyListener() throws Exception {
    ImagePlanesFrame frame = createRgbaFrame(DEFAULT_COLOR);
    CapturingFrameProcessorListener listener = new CapturingFrameProcessorListener();

    glExecutorService
        .submit(
            () -> {
              GlTextureFrame glTextureFrame =
                  converter.convert(
                      frame,
                      /* glExecutor= */ glExecutorService,
                      /* listenerExecutor= */ directExecutor(),
                      listener);
              converter.releaseGlResources(frame);
              glTextureFrame.release(/* releaseFence= */ null);
              return null;
            })
        .get(TEST_TIMEOUT_MS, MILLISECONDS);

    // The release callback runs on the GL executor and notifies the listener synchronously via
    // directExecutor(), so once the GL executor is drained the listener would already have been
    // notified if the converter were going to notify it at all.
    glExecutorService.submit(() -> {}).get(TEST_TIMEOUT_MS, MILLISECONDS);
    assertThat(listener.processedFrame.get()).isNull();
  }

  private GlTextureFrame convert(ImagePlanesFrame frame) throws Exception {
    return convert(frame, new FakeFrameProcessor.Listener());
  }

  private GlTextureFrame convert(ImagePlanesFrame frame, FrameProcessor.Listener listener)
      throws Exception {
    return glExecutorService
        .submit(
            () ->
                converter.convert(
                    frame,
                    /* glExecutor= */ glExecutorService,
                    /* listenerExecutor= */ directExecutor(),
                    listener))
        .get(TEST_TIMEOUT_MS, MILLISECONDS);
  }

  private Bitmap createArgb8888BitmapFromGlTextureFrame(GlTextureFrame glTextureFrame)
      throws Exception {
    return glExecutorService
        .submit(() -> createArgb8888BitmapFromGlTexture(glTextureFrame.glTextureInfo))
        .get(TEST_TIMEOUT_MS, MILLISECONDS);
  }

  @RequiresApi(26)
  private Bitmap createFp16BitmapFromGlTextureFrame(GlTextureFrame glTextureFrame)
      throws Exception {
    return glExecutorService
        .submit(() -> createFp16BitmapFromGlTexture(glTextureFrame.glTextureInfo))
        .get(TEST_TIMEOUT_MS, MILLISECONDS);
  }

  private static DefaultImagePlanesFrame createRgbaFrame(int color) {
    return createRgbaFrame(color, /* rowPaddingPixels= */ 0);
  }

  private static DefaultImagePlanesFrame createRgbaFrame(int color, int rowPaddingPixels) {
    return createRgbaFrame(color, rowPaddingPixels, DEFAULT_FRAME_WIDTH, DEFAULT_FRAME_HEIGHT);
  }

  private static DefaultImagePlanesFrame createRgbaFrame(
      int color, int rowPaddingPixels, int width, int height) {
    int rowStride = (width + rowPaddingPixels) * 4;
    ByteBuffer buffer = ByteBuffer.allocateDirect(rowStride * height);
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width + rowPaddingPixels; x++) {
        int pixelColor = x < width ? color : RGBA_PADDING_COLOR;
        buffer.put((byte) Color.red(pixelColor));
        buffer.put((byte) Color.green(pixelColor));
        buffer.put((byte) Color.blue(pixelColor));
        buffer.put((byte) Color.alpha(pixelColor));
      }
    }
    buffer.flip();
    Plane plane = new DefaultPlane(buffer, rowStride, /* pixelStride= */ 4);
    Format format =
        new Format.Builder()
            .setWidth(width)
            .setHeight(height)
            .setPixelFormat(PixelFormat.RGBA_8888)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();
    return new DefaultImagePlanesFrame.Builder(ImmutableList.of(plane)).setFormat(format).build();
  }

  private static final class CapturingFrameProcessorListener implements FrameProcessor.Listener {
    final CountDownLatch frameProcessed = new CountDownLatch(1);
    final AtomicReference<Frame> processedFrame = new AtomicReference<>();
    final AtomicReference<SyncFenceWrapper> onCompleteFence = new AtomicReference<>();

    @Override
    public void onWakeup() {}

    @Override
    public void onError(VideoFrameProcessingException exception) {
      throw new AssertionError(exception);
    }

    @Override
    public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {
      this.processedFrame.set(frame);
      this.onCompleteFence.set(onCompleteFence);
      this.frameProcessed.countDown();
    }
  }
}
