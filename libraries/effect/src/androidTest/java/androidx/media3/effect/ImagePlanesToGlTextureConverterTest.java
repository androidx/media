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

import static android.opengl.Matrix.multiplyMV;
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
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.Size;
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
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link ImagePlanesToGlTextureConverter}. */
@RunWith(TestParameterInjector.class)
public final class ImagePlanesToGlTextureConverterTest {

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({YUV_PLANE_LAYOUT_I420, YUV_PLANE_LAYOUT_NV12, YUV_PLANE_LAYOUT_NV21})
  private @interface YuvPlaneLayout {}

  private static final int YUV_PLANE_LAYOUT_I420 = 0;
  private static final int YUV_PLANE_LAYOUT_NV12 = 1;
  private static final int YUV_PLANE_LAYOUT_NV21 = 2;

  private static final long TEST_TIMEOUT_MS = isRunningOnEmulator() ? 20_000L : 10_000L;
  private static final int DEFAULT_FRAME_WIDTH = 64;
  private static final int DEFAULT_FRAME_HEIGHT = 32;
  private static final Size DEFAULT_FRAME_SIZE =
      new Size(DEFAULT_FRAME_WIDTH, DEFAULT_FRAME_HEIGHT);

  /**
   * A non-saturated color whose RGB channels are away from 0 and 255.
   *
   * <p>This ensures decoding YUV with the wrong color range (limited vs. full) produces a different
   * color instead of clipping to 0 or 255.
   */
  private static final int DEFAULT_COLOR = Color.rgb(64, 192, 64);

  private static final Bitmap EXPECTED_DEFAULT_COLOR_BITMAP =
      createArgb8888BitmapWithSolidColor(DEFAULT_FRAME_WIDTH, DEFAULT_FRAME_HEIGHT, DEFAULT_COLOR);

  /**
   * Number of padding pixels appended to each row, used to simulate producers that align rows to a
   * stride wider than the visible image.
   */
  private static final int RGBA_ROW_PADDING_PIXELS = 7;

  /** Number of padding pixels appended to each luma row. */
  private static final int LUMA_ROW_PADDING_PIXEL_COUNT = 4;

  /**
   * Number of padding pixels appended to each 4:2:0 chroma row.
   *
   * <p>Chosen to give the half-width chroma planes proportionally more row padding than {@link
   * #LUMA_ROW_PADDING_PIXEL_COUNT}, so that mistakenly reusing the luma plane's crop on the chroma
   * planes reads past the visible chroma pixels into their row padding.
   */
  private static final int CHROMA_ROW_PADDING_PIXEL_COUNT = 6;

  /** Padding fill color, chosen to contrast strongly with {@link #DEFAULT_COLOR}. */
  private static final int RGBA_PADDING_COLOR = Color.RED;

  private static final int RGBA_BYTES_PER_PIXEL = 4;
  private static final int SEMI_PLANAR_CHROMA_PIXEL_STRIDE = 2;

  /**
   * Padding fill value for YUV planes.
   *
   * <p>Chosen to contrast strongly with {@link #DEFAULT_COLOR} in each of the Y, U and V planes.
   */
  private static final byte YUV_PADDING_VALUE = (byte) 255;

  private static final ColorInfo COLOR_INFO_BT601_LIMITED =
      new ColorInfo.Builder()
          .setColorSpace(C.COLOR_SPACE_BT601)
          .setColorRange(C.COLOR_RANGE_LIMITED)
          .setColorTransfer(C.COLOR_TRANSFER_SRGB)
          .build();
  private static final ColorInfo COLOR_INFO_BT601_FULL =
      new ColorInfo.Builder()
          .setColorSpace(C.COLOR_SPACE_BT601)
          .setColorRange(C.COLOR_RANGE_FULL)
          .setColorTransfer(C.COLOR_TRANSFER_SRGB)
          .build();
  private static final ColorInfo COLOR_INFO_BT709_FULL =
      new ColorInfo.Builder()
          .setColorSpace(C.COLOR_SPACE_BT709)
          .setColorRange(C.COLOR_RANGE_FULL)
          .setColorTransfer(C.COLOR_TRANSFER_SRGB)
          .build();

  /**
   * Column-major 4x4 RGB-to-YUV matrices (inverses of the column-major {@code *_YUV_TO_RGB}
   * matrices in {@code color_conversions_es2.glsl}).
   */
  private static final float[] bt601LimitedRangeRgbToYuv = {
    0.2568f, -0.1482f, 0.4392f, 0.0f, //
    0.5041f, -0.2910f, -0.3678f, 0.0f, //
    0.0979f, 0.4392f, -0.0714f, 0.0f, //
    0.0f, 0.0f, 0.0f, 1.0f
  };

  private static final float[] bt601FullRangeRgbToYuv = {
    0.2990f, -0.1687f, 0.5000f, 0.0f, //
    0.5870f, -0.3313f, -0.4187f, 0.0f, //
    0.1140f, 0.5000f, -0.0813f, 0.0f, //
    0.0f, 0.0f, 0.0f, 1.0f
  };

  private static final float[] bt709LimitedRangeRgbToYuv = {
    0.1826f, -0.1006f, 0.4392f, 0.0f, //
    0.6142f, -0.3386f, -0.3989f, 0.0f, //
    0.0620f, 0.4392f, -0.0403f, 0.0f, //
    0.0f, 0.0f, 0.0f, 1.0f
  };

  private static final float[] bt709FullRangeRgbToYuv = {
    0.2126f, -0.1146f, 0.5000f, 0.0f, //
    0.7152f, -0.3854f, -0.4542f, 0.0f, //
    0.0722f, 0.5000f, -0.0458f, 0.0f, //
    0.0f, 0.0f, 0.0f, 1.0f
  };

  private static final float[] limitedRangeYuvOffset = {
    16.0f / 255.0f, 128.0f / 255.0f, 128.0f / 255.0f
  };

  private static final float[] fullRangeYuvOffset = {0.0f, 128.0f / 255.0f, 128.0f / 255.0f};

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
        new ImagePlanesToGlTextureConverter(
            context, HardwareBufferJni.INSTANCE, BT709_SRGB, /* errorConsumer= */ e -> {});
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
        new DefaultPlane(
            buffer,
            /* rowStride= */ inputBitmap.getRowBytes(),
            /* pixelStride= */ RGBA_BYTES_PER_PIXEL);
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
  public void convert_rgbaPlanesFrameWithTruncatedFinalRow_outputsCorrectGlTexture()
      throws Exception {
    ImagePlanesFrame rgbaFrame =
        createRgbaFrame(
            DEFAULT_COLOR,
            RGBA_ROW_PADDING_PIXELS,
            DEFAULT_FRAME_WIDTH,
            DEFAULT_FRAME_HEIGHT,
            /* padFinalRow= */ false);

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

  @Test
  public void convert_yuvBt601LimitedRange_decodesYuvCorrectly(
      @TestParameter(valuesProvider = YuvPlaneLayoutProvider.class) @YuvPlaneLayout int layout)
      throws Exception {
    ImagePlanesFrame yuvFrame =
        createYuvFrame(DEFAULT_COLOR, /* yuvColorInfo= */ COLOR_INFO_BT601_LIMITED, layout);
    converter =
        new ImagePlanesToGlTextureConverter(
            context,
            HardwareBufferJni.INSTANCE,
            COLOR_INFO_BT601_LIMITED,
            /* errorConsumer= */ e -> {
              throw new AssertionError(e);
            });

    GlTextureFrame glTextureFrame = convert(yuvFrame);

    Bitmap actualBitmap = createArgb8888BitmapFromGlTextureFrame(glTextureFrame);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                EXPECTED_DEFAULT_COLOR_BITMAP, actualBitmap, testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  public void convert_yuvBt601FullRange_decodesYuvCorrectly(
      @TestParameter(valuesProvider = YuvPlaneLayoutProvider.class) @YuvPlaneLayout int layout)
      throws Exception {
    ImagePlanesFrame yuvFrame =
        createYuvFrame(DEFAULT_COLOR, /* yuvColorInfo= */ COLOR_INFO_BT601_FULL, layout);
    converter =
        new ImagePlanesToGlTextureConverter(
            context,
            HardwareBufferJni.INSTANCE,
            COLOR_INFO_BT601_FULL,
            /* errorConsumer= */ e -> {
              throw new AssertionError(e);
            });

    GlTextureFrame glTextureFrame = convert(yuvFrame);

    Bitmap actualBitmap = createArgb8888BitmapFromGlTextureFrame(glTextureFrame);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                EXPECTED_DEFAULT_COLOR_BITMAP, actualBitmap, testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  public void convert_yuvBt709LimitedRange_decodesYuvCorrectly(
      @TestParameter(valuesProvider = YuvPlaneLayoutProvider.class) @YuvPlaneLayout int layout)
      throws Exception {
    ImagePlanesFrame yuvFrame =
        createYuvFrame(DEFAULT_COLOR, /* yuvColorInfo= */ ColorInfo.SDR_BT709_LIMITED, layout);

    GlTextureFrame glTextureFrame = convert(yuvFrame);

    Bitmap actualBitmap = createArgb8888BitmapFromGlTextureFrame(glTextureFrame);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                EXPECTED_DEFAULT_COLOR_BITMAP, actualBitmap, testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  public void convert_yuvBt709FullRange_decodesYuvCorrectly(
      @TestParameter(valuesProvider = YuvPlaneLayoutProvider.class) @YuvPlaneLayout int layout)
      throws Exception {
    ImagePlanesFrame yuvFrame =
        createYuvFrame(DEFAULT_COLOR, /* yuvColorInfo= */ COLOR_INFO_BT709_FULL, layout);

    GlTextureFrame glTextureFrame = convert(yuvFrame);

    Bitmap actualBitmap = createArgb8888BitmapFromGlTextureFrame(glTextureFrame);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                EXPECTED_DEFAULT_COLOR_BITMAP, actualBitmap, testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  public void convert_yuvBt601InputWithBt709Output_appliesGamutConversion(
      @TestParameter(valuesProvider = YuvPlaneLayoutProvider.class) @YuvPlaneLayout int layout)
      throws Exception {
    ImagePlanesFrame yuvFrame =
        createYuvFrame(DEFAULT_COLOR, /* yuvColorInfo= */ COLOR_INFO_BT601_LIMITED, layout);
    // DEFAULT_COLOR (64, 192, 64) converted from BT.601 gamut to BT.709 gamut:
    // 1. Linearize sRGB (srgbEotf): (64, 192, 64) / 255 -> (0.0513, 0.5271, 0.0513)
    // 2. Convert gamut (BT601_TO_BT709): -> (0.0751, 0.5108, 0.0492)
    // 3. Re-encode sRGB (srgbOetf): (0.3045, 0.7422, 0.2454) * 255 -> (78, 189, 63)
    Bitmap expectedBt709Bitmap =
        createArgb8888BitmapWithSolidColor(
            DEFAULT_FRAME_WIDTH, DEFAULT_FRAME_HEIGHT, Color.rgb(78, 189, 63));

    GlTextureFrame glTextureFrame = convert(yuvFrame);

    Bitmap actualBitmap = createArgb8888BitmapFromGlTextureFrame(glTextureFrame);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedBt709Bitmap, actualBitmap, testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  public void convert_yuvWithPaddedRowStride_cropsRowPadding(
      @TestParameter(valuesProvider = YuvPlaneLayoutProvider.class) @YuvPlaneLayout int layout)
      throws Exception {
    ImagePlanesFrame paddedYuvFrame =
        createYuvFrame(
            DEFAULT_COLOR,
            /* yuvColorInfo= */ ColorInfo.SDR_BT709_LIMITED,
            layout,
            LUMA_ROW_PADDING_PIXEL_COUNT,
            CHROMA_ROW_PADDING_PIXEL_COUNT);

    GlTextureFrame glTextureFrame = convert(paddedYuvFrame);

    Bitmap actualBitmap = createArgb8888BitmapFromGlTextureFrame(glTextureFrame);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                EXPECTED_DEFAULT_COLOR_BITMAP, actualBitmap, testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  public void convert_yuvWithOddDimensions_decodesYuvCorrectly(
      @TestParameter(valuesProvider = YuvPlaneLayoutProvider.class) @YuvPlaneLayout int layout)
      throws Exception {
    Size oddSize = new Size(/* width= */ 63, /* height= */ 31);
    ImagePlanesFrame oddYuvFrame =
        createYuvFrame(
            DEFAULT_COLOR, /* yuvColorInfo= */ ColorInfo.SDR_BT709_LIMITED, layout, oddSize);
    Bitmap expectedBitmap =
        createArgb8888BitmapWithSolidColor(oddSize.getWidth(), oddSize.getHeight(), DEFAULT_COLOR);

    GlTextureFrame glTextureFrame = convert(oddYuvFrame);

    Bitmap actualBitmap = createArgb8888BitmapFromGlTextureFrame(glTextureFrame);
    assertThat(glTextureFrame.glTextureInfo.width).isEqualTo(oddSize.getWidth());
    assertThat(glTextureFrame.glTextureInfo.height).isEqualTo(oddSize.getHeight());
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedBitmap, actualBitmap, testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  public void convert_yuvWithPaddedRowStride_doesNotBlendPaddingIntoRightmostColumn(
      @TestParameter(valuesProvider = YuvPlaneLayoutProvider.class) @YuvPlaneLayout int layout)
      throws Exception {
    ImagePlanesFrame paddedYuvFrame =
        createYuvFrame(
            DEFAULT_COLOR,
            /* yuvColorInfo= */ ColorInfo.SDR_BT709_LIMITED,
            layout,
            LUMA_ROW_PADDING_PIXEL_COUNT,
            CHROMA_ROW_PADDING_PIXEL_COUNT);
    Bitmap expectedRightmostColumn =
        createArgb8888BitmapWithSolidColor(/* width= */ 1, DEFAULT_FRAME_HEIGHT, DEFAULT_COLOR);

    GlTextureFrame glTextureFrame = convert(paddedYuvFrame);

    // The converter crops out the padding columns by mapping the 64 (DEFAULT_FRAME_WIDTH) output
    // columns onto only the visible pixels of each plane:
    // - In the 1:1 luma plane, there is one luma pixel per output column, so each output column's
    //   center lands directly on its luma pixel's center and never blends with adjacent luma
    //   pixels or padding.
    // - In the half-width 4:2:0 chroma planes, each chroma pixel spans 2 output columns, so the
    //   last visible chroma pixel's center sits right between the last two columns (col 62 and
    //   col 63):
    //
    //   |--- last visible chroma pixel ---|--- 1st padding chroma pixel ----|
    //   |     col 62     |     col 63     |                                 |
    //           x        *        x                         *
    //             (chroma center)                    (padding center)
    //
    //   Col 62 is to the left of the last visible chroma center, whereas col 63 is to the right of
    //   the last visible chroma center—falling between the last visible chroma center and the first
    //   padding center, so bilinear (GL_LINEAR) filtering would blend the last visible chroma pixel
    //   with the first padding pixel. Sampling with GL_NEAREST snaps both col 62 and col 63 to the
    //   last visible chroma pixel without reading the padding.
    //
    // Here, we compare only that 1-pixel-wide rightmost column so any padding bleed is not averaged
    // out across all 64 columns.
    Bitmap actualBitmap = createArgb8888BitmapFromGlTextureFrame(glTextureFrame);
    Bitmap actualRightmostColumn =
        Bitmap.createBitmap(
            actualBitmap,
            /* x= */ DEFAULT_FRAME_WIDTH - 1,
            /* y= */ 0,
            /* width= */ 1,
            /* height= */ DEFAULT_FRAME_HEIGHT);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedRightmostColumn, actualRightmostColumn, testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  public void convert_rgbaFrameThenYuvFrame_decodesBothFramesCorrectly(
      @TestParameter(valuesProvider = YuvPlaneLayoutProvider.class) @YuvPlaneLayout int layout)
      throws Exception {
    ImagePlanesFrame rgbaFrame = createRgbaFrame(Color.BLUE);
    ImagePlanesFrame yuvFrame =
        createYuvFrame(DEFAULT_COLOR, /* yuvColorInfo= */ ColorInfo.SDR_BT709_LIMITED, layout);

    GlTextureFrame rgbaGlTextureFrame = convert(rgbaFrame);
    Bitmap actualRgbaBitmap = createArgb8888BitmapFromGlTextureFrame(rgbaGlTextureFrame);
    rgbaGlTextureFrame.release(/* releaseFence= */ null);
    GlTextureFrame yuvGlTextureFrame = convert(yuvFrame);
    Bitmap actualYuvBitmap = createArgb8888BitmapFromGlTextureFrame(yuvGlTextureFrame);
    yuvGlTextureFrame.release(/* releaseFence= */ null);

    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                createArgb8888BitmapWithSolidColor(
                    DEFAULT_FRAME_WIDTH, DEFAULT_FRAME_HEIGHT, Color.BLUE),
                actualRgbaBitmap,
                testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                EXPECTED_DEFAULT_COLOR_BITMAP, actualYuvBitmap, testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  public void convert_planarYuvFrameThenSemiPlanarYuvFrame_decodesBothFramesCorrectly()
      throws Exception {
    ImagePlanesFrame i420Frame =
        createYuvFrame(
            DEFAULT_COLOR, /* yuvColorInfo= */ ColorInfo.SDR_BT709_LIMITED, YUV_PLANE_LAYOUT_I420);
    ImagePlanesFrame nv12Frame =
        createYuvFrame(
            DEFAULT_COLOR, /* yuvColorInfo= */ ColorInfo.SDR_BT709_LIMITED, YUV_PLANE_LAYOUT_NV12);

    GlTextureFrame i420GlTextureFrame = convert(i420Frame);
    Bitmap actualI420Bitmap = createArgb8888BitmapFromGlTextureFrame(i420GlTextureFrame);
    i420GlTextureFrame.release(/* releaseFence= */ null);
    GlTextureFrame nv12GlTextureFrame = convert(nv12Frame);
    Bitmap actualNv12Bitmap = createArgb8888BitmapFromGlTextureFrame(nv12GlTextureFrame);
    nv12GlTextureFrame.release(/* releaseFence= */ null);

    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                EXPECTED_DEFAULT_COLOR_BITMAP, actualI420Bitmap, testName.getMethodName()))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                EXPECTED_DEFAULT_COLOR_BITMAP, actualNv12Bitmap, testName.getMethodName()))
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
        new ImagePlanesToGlTextureConverter(
            context, HardwareBufferJni.INSTANCE, BT709_LINEAR, /* errorConsumer= */ e -> {});

    GlTextureFrame glTextureFrame = convert(rgbaFrame);

    Bitmap actualBitmap = createFp16BitmapFromGlTextureFrame(glTextureFrame);
    assertThat(glTextureFrame.format.colorInfo).isEqualTo(BT709_LINEAR);
    assertThat(getBitmapAveragePixelAbsoluteDifferenceFp16(expectedLinearBitmap, actualBitmap))
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE_DIFFERENT_DEVICE_FP16);
  }

  @SdkSuppress(minSdkVersion = 29)
  @Test
  public void convert_yuvWithLinearOutputTransfer_appliesSrgbEotf(
      @TestParameter(valuesProvider = YuvPlaneLayoutProvider.class) @YuvPlaneLayout int layout)
      throws Exception {
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
    ImagePlanesFrame yuvFrame =
        createYuvFrame(srgbMidTone, /* yuvColorInfo= */ ColorInfo.SDR_BT709_LIMITED, layout);
    converter =
        new ImagePlanesToGlTextureConverter(
            context,
            HardwareBufferJni.INSTANCE,
            BT709_LINEAR,
            /* errorConsumer= */ e -> {
              throw new AssertionError(e);
            });

    GlTextureFrame glTextureFrame = convert(yuvFrame);

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
    return createRgbaFrame(color, rowPaddingPixels, width, height, /* padFinalRow= */ true);
  }

  /**
   * Returns a solid color RGBA_8888 frame, with each row padded by {@code rowPaddingPixels} pixels
   * of {@link #RGBA_PADDING_COLOR}, or with the final row ending at the last visible pixel when
   * {@code padFinalRow} is {@code false} (matching {@code ImageReader} plane buffers).
   */
  private static DefaultImagePlanesFrame createRgbaFrame(
      int color, int rowPaddingPixels, int width, int height, boolean padFinalRow) {
    int paddedWidth = width + rowPaddingPixels;
    int rowStride = paddedWidth * RGBA_BYTES_PER_PIXEL;
    int bufferPixels = paddedWidth * (height - 1) + (padFinalRow ? paddedWidth : width);
    int bufferSize = bufferPixels * RGBA_BYTES_PER_PIXEL;
    ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
    // The first `height - 1` rows are always padded.
    for (int y = 0; y < height - 1; y++) {
      for (int x = 0; x < width; x++) {
        addRgbaToBuffer(color, buffer);
      }
      for (int x = width; x < paddedWidth; x++) {
        addRgbaToBuffer(RGBA_PADDING_COLOR, buffer);
      }
    }
    // The final row only includes padding when `padFinalRow` is true.
    for (int x = 0; x < width; x++) {
      addRgbaToBuffer(color, buffer);
    }
    if (padFinalRow) {
      for (int x = width; x < paddedWidth; x++) {
        addRgbaToBuffer(RGBA_PADDING_COLOR, buffer);
      }
    }
    buffer.flip();
    Plane plane = new DefaultPlane(buffer, rowStride, /* pixelStride= */ RGBA_BYTES_PER_PIXEL);
    Format format =
        new Format.Builder()
            .setWidth(width)
            .setHeight(height)
            .setPixelFormat(PixelFormat.RGBA_8888)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();
    return new DefaultImagePlanesFrame.Builder(ImmutableList.of(plane)).setFormat(format).build();
  }

  private static void addRgbaToBuffer(int pixelColor, ByteBuffer buffer) {
    buffer.put((byte) Color.red(pixelColor));
    buffer.put((byte) Color.green(pixelColor));
    buffer.put((byte) Color.blue(pixelColor));
    buffer.put((byte) Color.alpha(pixelColor));
  }

  private static DefaultImagePlanesFrame createYuvFrame(
      @ColorInt int rgbColor, ColorInfo yuvColorInfo, @YuvPlaneLayout int layout) {
    return createYuvFrame(rgbColor, yuvColorInfo, layout, DEFAULT_FRAME_SIZE);
  }

  private static DefaultImagePlanesFrame createYuvFrame(
      @ColorInt int rgbColor, ColorInfo yuvColorInfo, @YuvPlaneLayout int layout, Size size) {
    ImmutableList<Plane> planes =
        createYuvPlanes(
            rgbToYuv(rgbColor, yuvColorInfo.colorSpace, yuvColorInfo.colorRange),
            layout,
            size,
            /* lumaRowPaddingPixelCount= */ 0,
            /* chromaRowPaddingPixelCount= */ 0);
    return createYuvFrameFromPlanes(planes, yuvColorInfo, size);
  }

  private static DefaultImagePlanesFrame createYuvFrame(
      @ColorInt int rgbColor,
      ColorInfo yuvColorInfo,
      @YuvPlaneLayout int layout,
      int lumaRowPaddingPixelCount,
      int chromaRowPaddingPixelCount) {
    ImmutableList<Plane> planes =
        createYuvPlanes(
            rgbToYuv(rgbColor, yuvColorInfo.colorSpace, yuvColorInfo.colorRange),
            layout,
            DEFAULT_FRAME_SIZE,
            lumaRowPaddingPixelCount,
            chromaRowPaddingPixelCount);
    return createYuvFrameFromPlanes(planes, yuvColorInfo, DEFAULT_FRAME_SIZE);
  }

  private static DefaultImagePlanesFrame createYuvFrameFromPlanes(
      ImmutableList<Plane> planes, ColorInfo yuvColorInfo, Size size) {
    Format format =
        new Format.Builder()
            .setWidth(size.getWidth())
            .setHeight(size.getHeight())
            .setPixelFormat(ImageFormat.YUV_420_888)
            .setColorInfo(yuvColorInfo)
            .build();
    return new DefaultImagePlanesFrame.Builder(planes).setFormat(format).build();
  }

  /**
   * Returns Y, U, and V planes for a solid color YUV frame in {@code layout}.
   *
   * <p>Each row except the final one of each plane is padded by {@link #YUV_PADDING_VALUE}.
   *
   * <p>For {@link #YUV_PLANE_LAYOUT_NV12} and {@link #YUV_PLANE_LAYOUT_NV21}, the U and V planes
   * are views of the same interleaved chroma, each ending at its own last sample (matching {@code
   * ImageReader} plane buffers).
   */
  // Switch expressions need Java 14+, but Gradle compiles Media3 tests with Java 8.
  // TODO(b/561916275): Remove once test targets apply Media3 javacopts.
  @SuppressWarnings("StatementSwitchToExpressionSwitch")
  private static ImmutableList<Plane> createYuvPlanes(
      byte[] yuv,
      @YuvPlaneLayout int layout,
      Size size,
      int lumaRowPaddingPixelCount,
      int chromaRowPaddingPixelCount) {
    byte ySample = yuv[0];
    byte uSample = yuv[1];
    byte vSample = yuv[2];
    int width = size.getWidth();
    int height = size.getHeight();
    int chromaWidth = (width + 1) / 2;
    int chromaHeight = (height + 1) / 2;
    int semiPlanarChromaRowStride =
        (chromaWidth + chromaRowPaddingPixelCount) * SEMI_PLANAR_CHROMA_PIXEL_STRIDE;
    Plane yPlane = createPlane(ySample, width, height, lumaRowPaddingPixelCount);
    Plane uPlane;
    Plane vPlane;
    switch (layout) {
      case YUV_PLANE_LAYOUT_I420:
        uPlane = createPlane(uSample, chromaWidth, chromaHeight, chromaRowPaddingPixelCount);
        vPlane = createPlane(vSample, chromaWidth, chromaHeight, chromaRowPaddingPixelCount);
        break;
      case YUV_PLANE_LAYOUT_NV12:
        ByteBuffer nv12Chroma =
            createInterleavedChromaBuffer(
                /* firstSample= */ uSample,
                /* secondSample= */ vSample,
                chromaWidth,
                chromaHeight,
                chromaRowPaddingPixelCount);
        uPlane =
            createSemiPlanarChromaPlane(nv12Chroma, /* offset= */ 0, semiPlanarChromaRowStride);
        vPlane =
            createSemiPlanarChromaPlane(nv12Chroma, /* offset= */ 1, semiPlanarChromaRowStride);
        break;
      case YUV_PLANE_LAYOUT_NV21:
        ByteBuffer nv21Chroma =
            createInterleavedChromaBuffer(
                /* firstSample= */ vSample,
                /* secondSample= */ uSample,
                chromaWidth,
                chromaHeight,
                chromaRowPaddingPixelCount);
        vPlane =
            createSemiPlanarChromaPlane(nv21Chroma, /* offset= */ 0, semiPlanarChromaRowStride);
        uPlane =
            createSemiPlanarChromaPlane(nv21Chroma, /* offset= */ 1, semiPlanarChromaRowStride);
        break;
      default:
        throw new AssertionError("Unexpected layout: " + layout);
    }
    return ImmutableList.of(yPlane, uPlane, vPlane);
  }

  /**
   * Returns a plane with {@code pixelStride == 1}, filled with {@code value}.
   *
   * <p>Each row except the final one is padded by {@code rowPaddingPixelCount} bytes of {@link
   * #YUV_PADDING_VALUE}, so the buffer ends at the last visible pixel (matching {@code ImageReader}
   * plane buffers).
   */
  private static Plane createPlane(byte value, int width, int height, int rowPaddingPixelCount) {
    int rowStride = width + rowPaddingPixelCount;
    int bufferSize = rowStride * (height - 1) + width;
    ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
    // The first `height - 1` rows are always padded.
    for (int y = 0; y < height - 1; y++) {
      for (int x = 0; x < width; x++) {
        buffer.put(value);
      }
      for (int x = width; x < rowStride; x++) {
        buffer.put(YUV_PADDING_VALUE);
      }
    }
    // The final row ends at the last visible pixel, without any padding.
    for (int x = 0; x < width; x++) {
      buffer.put(value);
    }
    buffer.flip();
    return new DefaultPlane(buffer, rowStride, /* pixelStride= */ 1);
  }

  /**
   * Returns interleaved chroma in which each pair of samples is {@code firstSample} followed by
   * {@code secondSample}.
   *
   * <p>Each row except the final one is padded by {@code rowPaddingPixelCount} pairs of {@link
   * #YUV_PADDING_VALUE}, so the buffer ends at the last visible sample (matching {@code
   * ImageReader} plane buffers).
   */
  private static ByteBuffer createInterleavedChromaBuffer(
      byte firstSample, byte secondSample, int width, int height, int rowPaddingPixelCount) {
    int rowStride = (width + rowPaddingPixelCount) * SEMI_PLANAR_CHROMA_PIXEL_STRIDE;
    int finalRowBytes = width * SEMI_PLANAR_CHROMA_PIXEL_STRIDE;
    int bufferSize = rowStride * (height - 1) + finalRowBytes;
    ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
    // The first `height - 1` rows are always padded.
    for (int y = 0; y < height - 1; y++) {
      for (int x = 0; x < width; x++) {
        buffer.put(firstSample).put(secondSample);
      }
      for (int x = 0; x < rowPaddingPixelCount; x++) {
        buffer.put(YUV_PADDING_VALUE).put(YUV_PADDING_VALUE);
      }
    }
    // The final row ends at the last visible sample, without any padding.
    for (int x = 0; x < width; x++) {
      buffer.put(firstSample).put(secondSample);
    }
    buffer.flip();
    return buffer;
  }

  /**
   * Returns a plane with {@code pixelStride == 2} that is a view of {@code interleavedChroma}.
   *
   * <p>The view starts at {@code offset} (0 or 1) and ends at its own last sample.
   */
  private static Plane createSemiPlanarChromaPlane(
      ByteBuffer interleavedChroma, int offset, int rowStride) {
    ByteBuffer view = interleavedChroma.duplicate();
    view.position(offset);
    // The interleaved chroma ends with the last sample of the view that starts at offset 1.
    view.limit(interleavedChroma.limit() - 1 + offset);
    return new DefaultPlane(view.slice(), rowStride, SEMI_PLANAR_CHROMA_PIXEL_STRIDE);
  }

  private static byte[] rgbToYuv(
      @ColorInt int rgbColor, @C.ColorSpace int colorSpace, @C.ColorRange int colorRange) {
    boolean isBt601 = colorSpace == C.COLOR_SPACE_BT601;
    boolean isFullRange = colorRange == C.COLOR_RANGE_FULL;
    float[] rgbToYuvMatrix;
    if (isBt601) {
      rgbToYuvMatrix = isFullRange ? bt601FullRangeRgbToYuv : bt601LimitedRangeRgbToYuv;
    } else {
      rgbToYuvMatrix = isFullRange ? bt709FullRangeRgbToYuv : bt709LimitedRangeRgbToYuv;
    }
    float[] yuvOffset = isFullRange ? fullRangeYuvOffset : limitedRangeYuvOffset;
    float[] rgb = {
      Color.red(rgbColor) / 255.0f,
      Color.green(rgbColor) / 255.0f,
      Color.blue(rgbColor) / 255.0f,
      1.0f
    };
    float[] yuv = new float[4];
    multiplyMV(
        /* resultVec= */ yuv,
        /* resultVecOffset= */ 0,
        /* lhsMat= */ rgbToYuvMatrix,
        /* lhsMatOffset= */ 0,
        /* rhsVec= */ rgb,
        /* rhsVecOffset= */ 0);
    return new byte[] {
      (byte) Math.round((yuv[0] + yuvOffset[0]) * 255.0f),
      (byte) Math.round((yuv[1] + yuvOffset[1]) * 255.0f),
      (byte) Math.round((yuv[2] + yuvOffset[2]) * 255.0f)
    };
  }

  private static final class YuvPlaneLayoutProvider extends TestParameterValuesProvider {
    @Override
    protected ImmutableList<?> provideValues(TestParameterValuesProvider.Context context) {
      return ImmutableList.of(
          value(YUV_PLANE_LAYOUT_I420).withName("I420"),
          value(YUV_PLANE_LAYOUT_NV12).withName("NV12"),
          value(YUV_PLANE_LAYOUT_NV21).withName("NV21"));
    }
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
