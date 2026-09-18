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

import static androidx.media3.common.util.Util.isRunningOnEmulator;
import static androidx.media3.effect.DefaultGlFrameProcessor.COLORSPACE_HDR_HLG;
import static androidx.media3.effect.DefaultGlFrameProcessor.COLORSPACE_HDR_LINEAR;
import static androidx.media3.effect.DefaultGlFrameProcessor.COLORSPACE_SDR_SRGB;
import static androidx.media3.effect.FrameProcessorUtils.releaseOpenGl;
import static androidx.media3.effect.FrameProcessorUtils.setupOpenGl;
import static androidx.media3.effect.FrameProcessorUtils.shutdownGlExecutorService;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_COLOR_TEST_1080P_HLG10;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createArgb8888BitmapFromFocusedGlFramebuffer;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createArgb8888BitmapWithSolidColor;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createFp16BitmapFromFocusedGlFramebuffer;
import static androidx.media3.test.utils.BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888;
import static androidx.media3.test.utils.BitmapPixelTestUtil.readBitmap;
import static androidx.media3.test.utils.HdrCapabilitiesUtil.assumeDeviceSupportsOpenGlToneMapping;
import static androidx.media3.test.utils.TestUtil.assertFp16BitmapsAreSimilar;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaFormat;
import android.opengl.GLES30;
import android.util.Half;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.common.util.Util;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.effect.ndk.HardwareBufferJni;
import androidx.media3.test.utils.AssetInfo;
import androidx.media3.test.utils.BitmapPixelTestUtil;
import androidx.media3.test.utils.DecodeOneFrameUtil;
import androidx.media3.test.utils.TestUtil;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
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

/** Instrumentation tests for {@link HardwareBufferToGlTextureConverter}. */
@RunWith(TestParameterInjector.class)
@SdkSuppress(minSdkVersion = 29)
public final class HardwareBufferToGlTextureConverterTest {

  @Rule public final TestName testName = new TestName();
  private static final AssetInfo TEST_VIDEO_ASSET = AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS;
  private static final float MAX_PIXEL_DIFFERENCE = 10.f;
  private static final long TEST_TIMEOUT_MS = isRunningOnEmulator() ? 20_000L : 10_000L;
  private static final long FENCE_TIMEOUT_MS = 1_000L;
  private static final ImmutableList<SolidColorTestCase> SOLID_COLOR_TEST_CASES =
      ImmutableList.of(
          new SolidColorTestCase(
              /* name= */ "BLACK",
              /* inputSrgbColor= */ Color.BLACK,
              /* expectedHlgRgb= */ Color.valueOf(0.0f, 0.0f, 0.0f),
              /* expectedBt2020LinearRgb= */ Color.valueOf(0.0f, 0.0f, 0.0f)),
          new SolidColorTestCase(
              /* name= */ "WHITE",
              /* inputSrgbColor= */ Color.WHITE,
              /* expectedHlgRgb= */ Color.valueOf(0.75f, 0.75f, 0.75f),
              /* expectedBt2020LinearRgb= */ Color.valueOf(1.0f, 1.0f, 1.0f)),
          new SolidColorTestCase(
              /* name= */ "GRAY_128",
              /* inputSrgbColor= */ Color.rgb(128, 128, 128),
              /* expectedHlgRgb= */ Color.valueOf(0.4707f, 0.4707f, 0.4707f),
              /* expectedBt2020LinearRgb= */ Color.valueOf(0.2787f, 0.2787f, 0.2787f)),
          new SolidColorTestCase(
              /* name= */ "RED",
              /* inputSrgbColor= */ Color.RED,
              /* expectedHlgRgb= */ Color.valueOf(0.7087f, 0.2666f, 0.1298f),
              /* expectedBt2020LinearRgb= */ Color.valueOf(0.8122f, 0.0894f, 0.0212f)),
          new SolidColorTestCase(
              /* name= */ "GREEN",
              /* inputSrgbColor= */ Color.GREEN,
              /* expectedHlgRgb= */ Color.valueOf(0.5250f, 0.7445f, 0.2720f),
              /* expectedBt2020LinearRgb= */ Color.valueOf(0.3482f, 0.9724f, 0.0931f)),
          new SolidColorTestCase(
              /* name= */ "BLUE",
              /* inputSrgbColor= */ Color.BLUE,
              /* expectedHlgRgb= */ Color.valueOf(0.2309f, 0.1183f, 0.75f),
              /* expectedBt2020LinearRgb= */ Color.valueOf(0.0671f, 0.0176f, 1.0f)));

  private final Context context = getApplicationContext();

  private @MonotonicNonNull ListeningExecutorService glExecutorService;
  private @MonotonicNonNull GlObjectsProvider glObjectsProvider;
  private @MonotonicNonNull HardwareBufferToGlTextureConverter converter;

  @Before
  public void setUp() {
    glExecutorService = listeningDecorator(Executors.newSingleThreadExecutor());
    glObjectsProvider = new DefaultGlObjectsProvider();
    converter =
        new HardwareBufferToGlTextureConverter(
            context,
            HardwareBufferJni.INSTANCE,
            // TODO: b/517525358 - Use correct output color info once HDR is supported.
            ColorInfo.SDR_BT709_LIMITED,
            e -> {});
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
      if (converter != null) {
        converter.close();
      }
      if (glObjectsProvider != null) {
        releaseOpenGl(glObjectsProvider);
      }
    } catch (Exception e) {
      // Ignore release failures.
    }
  }

  @SdkSuppress(minSdkVersion = 31)
  @Test
  public void convert_withARGB8888HardwareBuffer_outputsCorrectGlTexture() throws Exception {
    Bitmap expectedBitmap = BitmapPixelTestUtil.readBitmap("media/png/first_frame_1920x1080.png");
    Bitmap hardwareBitmap = expectedBitmap.copy(Bitmap.Config.HARDWARE, /* isMutable= */ false);
    HardwareBuffer hardwareBuffer = hardwareBitmap.getHardwareBuffer();

    AtomicReference<Frame> completedFrame = new AtomicReference<>();
    FrameProcessor.Listener listener =
        new FrameProcessor.Listener() {
          @Override
          public void onWakeup() {}

          @Override
          public void onError(VideoFrameProcessingException exception) {}

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper releaseFence) {
            if (releaseFence != null) {
              assertThat(releaseFence.awaitMs(FENCE_TIMEOUT_MS)).isTrue();
              releaseFence.close();
            }
            hardwareBuffer.close();
            completedFrame.set(frame);
          }
        };

    int width = expectedBitmap.getWidth();
    int height = expectedBitmap.getHeight();
    HardwareBufferFrame hardwareBufferFrame =
        new DefaultHardwareBufferFrame.Builder(hardwareBuffer)
            .setFormat(
                new Format.Builder()
                    .setWidth(width)
                    .setHeight(height)
                    .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
                    .build())
            .build();

    Bitmap actualBitmap = convertAndCaptureBitmap(hardwareBufferFrame, listener);

    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedBitmap, actualBitmap, testName.getMethodName()))
        .isLessThan(MAX_PIXEL_DIFFERENCE);
    assertThat(completedFrame.get()).isSameInstanceAs(hardwareBufferFrame);
  }

  @SdkSuppress(minSdkVersion = 29)
  @Test
  public void convert_withYuv420HardwareBuffer_outputsCorrectGlTexture() throws Exception {
    int width = TEST_VIDEO_ASSET.videoFormat.width;
    int height = TEST_VIDEO_ASSET.videoFormat.height;

    try (ImageReader inputImageReader =
        ImageReader.newInstance(
            width,
            height,
            ImageFormat.YUV_420_888,
            /* maxImages= */ 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)) {
      AtomicReference<MediaFormat> inputMediaFormat = new AtomicReference<>();
      DecodeOneFrameUtil.decodeOneMediaItemFrame(
          MediaItem.fromUri(AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS.uri),
          new DecodeOneFrameUtil.Listener() {
            @Override
            public void onContainerExtracted(MediaFormat mediaFormat) {}

            @Override
            public void onFrameDecoded(MediaFormat mediaFormat) {
              inputMediaFormat.set(mediaFormat);
            }
          },
          inputImageReader.getSurface());

      Image inputImage = checkNotNull(inputImageReader.acquireLatestImage());
      HardwareBuffer inputHardwareBuffer = checkNotNull(inputImage.getHardwareBuffer());

      // Override the input format to force it to be 1920x1080 rather than 1920x1088.
      Format inputFormat =
          MediaFormatUtil.createFormatFromMediaFormat(inputMediaFormat.get())
              .buildUpon()
              .setWidth(width)
              .setHeight(height)
              .build();

      ColorInfo outputColorInfo =
          inputFormat.colorInfo != null ? inputFormat.colorInfo : ColorInfo.SDR_BT709_LIMITED;
      converter =
          new HardwareBufferToGlTextureConverter(
              context, HardwareBufferJni.INSTANCE, outputColorInfo, e -> {});

      AtomicReference<Frame> completedFrame = new AtomicReference<>();
      FrameProcessor.Listener listener =
          new FrameProcessor.Listener() {
            @Override
            public void onWakeup() {}

            @Override
            public void onError(VideoFrameProcessingException exception) {}

            @Override
            public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper releaseFence) {
              if (releaseFence != null) {
                assertThat(releaseFence.awaitMs(FENCE_TIMEOUT_MS)).isTrue();
                releaseFence.close();
              }
              inputHardwareBuffer.close();
              inputImage.close();
              completedFrame.set(frame);
            }
          };

      HardwareBufferFrame inputHardwareBufferFrame =
          new DefaultHardwareBufferFrame.Builder(inputHardwareBuffer)
              .setFormat(inputFormat)
              .build();
      Bitmap expectedBitmap = BitmapPixelTestUtil.readBitmap("media/png/first_frame_1920x1080.png");

      Bitmap actualBitmap = convertAndCaptureBitmap(inputHardwareBufferFrame, listener);

      assertThat(
              getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                  expectedBitmap, actualBitmap, testName.getMethodName()))
          .isLessThan(MAX_PIXEL_DIFFERENCE);
      assertThat(completedFrame.get()).isSameInstanceAs(inputHardwareBufferFrame);
    }
  }

  @SdkSuppress(minSdkVersion = 31)
  @Test
  public void convert_withHlgHardwareBufferAndToneMapping_outputsCorrectGlTexture()
      throws Exception {
    assumeDeviceSupportsOpenGlToneMapping(
        testName.getMethodName(), MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);

    int width = 1920;
    int height = 1080;

    ImageReader inputImageReader =
        ImageReader.newInstance(
            width,
            height,
            // Make sure ImageReader reads 10 bit YUV data.
            ImageFormat.YCBCR_P010,
            /* maxImages= */ 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);

    AtomicReference<MediaFormat> inputMediaFormat = new AtomicReference<>();
    DecodeOneFrameUtil.decodeOneMediaItemFrame(
        MediaItem.fromUri(MP4_ASSET_COLOR_TEST_1080P_HLG10.uri),
        new DecodeOneFrameUtil.Listener() {
          @Override
          public void onContainerExtracted(MediaFormat mediaFormat) {}

          @Override
          public void onFrameDecoded(MediaFormat mediaFormat) {
            inputMediaFormat.set(mediaFormat);
          }
        },
        inputImageReader.getSurface());

    Image inputImage = checkNotNull(inputImageReader.acquireLatestImage());
    HardwareBuffer inputHardwareBuffer = checkNotNull(inputImage.getHardwareBuffer());

    Format inputFormat =
        MediaFormatUtil.createFormatFromMediaFormat(inputMediaFormat.get())
            .buildUpon()
            .setWidth(width)
            .setHeight(height)
            .build();

    assumeTrue(GlUtil.isYuvTargetExtensionSupported());

    converter =
        new HardwareBufferToGlTextureConverter(
            context,
            HardwareBufferJni.INSTANCE,
            /* outputColorInfo= */ COLORSPACE_SDR_SRGB,
            e -> {
              throw new AssertionError(e);
            });

    AtomicReference<Frame> completedFrame = new AtomicReference<>();
    FrameProcessor.Listener listener =
        new FrameProcessor.Listener() {
          @Override
          public void onWakeup() {}

          @Override
          public void onError(VideoFrameProcessingException exception) {
            throw new AssertionError(exception);
          }

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper releaseFence) {
            if (releaseFence != null) {
              assertThat(releaseFence.awaitMs(FENCE_TIMEOUT_MS)).isTrue();
              releaseFence.close();
            }
            inputHardwareBuffer.close();
            inputImage.close();
            completedFrame.set(frame);
          }
        };

    HardwareBufferFrame inputHardwareBufferFrame =
        new DefaultHardwareBufferFrame.Builder(inputHardwareBuffer).setFormat(inputFormat).build();
    Bitmap expectedBitmap =
        BitmapPixelTestUtil.readBitmap(
            "test-generated-goldens/sample_mp4_first_frame/electrical_colors/tone_map_hlg_to_sdr.png");

    Bitmap actualBitmap = convertAndCaptureBitmap(inputHardwareBufferFrame, listener);

    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedBitmap, actualBitmap, testName.getMethodName()))
        .isLessThan(MAX_PIXEL_DIFFERENCE);
    assertThat(completedFrame.get()).isSameInstanceAs(inputHardwareBufferFrame);
    inputImageReader.close();
  }

  @SdkSuppress(minSdkVersion = 31)
  @Test
  public void convert_withRgba8888HardwareBufferAndColorConversion_outputsCorrectGlTexture()
      throws Exception {
    Bitmap expectedBitmap = BitmapPixelTestUtil.readBitmap("media/png/first_frame_1920x1080.png");
    Bitmap hardwareBitmap = expectedBitmap.copy(Bitmap.Config.HARDWARE, /* isMutable= */ false);
    HardwareBuffer hardwareBuffer = hardwareBitmap.getHardwareBuffer();
    assertThat(hardwareBuffer.getFormat()).isEqualTo(HardwareBuffer.RGBA_8888);

    ColorInfo differentSdrColorInfo =
        new ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT709)
            .setColorTransfer(C.COLOR_TRANSFER_SRGB)
            .setColorRange(C.COLOR_RANGE_FULL)
            .build();

    converter =
        new HardwareBufferToGlTextureConverter(
            context, HardwareBufferJni.INSTANCE, differentSdrColorInfo, e -> {});

    AtomicReference<Frame> completedFrame = new AtomicReference<>();
    FrameProcessor.Listener listener =
        new FrameProcessor.Listener() {
          @Override
          public void onWakeup() {}

          @Override
          public void onError(VideoFrameProcessingException exception) {}

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper releaseFence) {
            if (releaseFence != null) {
              assertThat(releaseFence.awaitMs(FENCE_TIMEOUT_MS)).isTrue();
              releaseFence.close();
            }
            hardwareBuffer.close();
            completedFrame.set(frame);
          }
        };

    Format inputFormat =
        new Format.Builder()
            .setWidth(expectedBitmap.getWidth())
            .setHeight(expectedBitmap.getHeight())
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();

    HardwareBufferFrame inputHardwareBufferFrame =
        new DefaultHardwareBufferFrame.Builder(hardwareBuffer).setFormat(inputFormat).build();

    Bitmap actualBitmap = convertAndCaptureBitmap(inputHardwareBufferFrame, listener);

    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedBitmap, actualBitmap, testName.getMethodName()))
        .isLessThan(MAX_PIXEL_DIFFERENCE);
    assertThat(completedFrame.get()).isSameInstanceAs(inputHardwareBufferFrame);
  }

  @SdkSuppress(minSdkVersion = 29)
  @Test
  public void convert_withYuv420Bt601HardwareBuffer_convertsToBt709GlTexture() throws Exception {
    int width = AssetInfo.BT601_MP4_ASSET.videoFormat.width;
    int height = AssetInfo.BT601_MP4_ASSET.videoFormat.height;

    ImageReader inputImageReader =
        ImageReader.newInstance(
            width,
            height,
            ImageFormat.YUV_420_888,
            /* maxImages= */ 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);

    AtomicReference<MediaFormat> inputMediaFormat = new AtomicReference<>();
    DecodeOneFrameUtil.decodeOneMediaItemFrame(
        MediaItem.fromUri(AssetInfo.BT601_MP4_ASSET.uri),
        new DecodeOneFrameUtil.Listener() {
          @Override
          public void onContainerExtracted(MediaFormat mediaFormat) {}

          @Override
          public void onFrameDecoded(MediaFormat mediaFormat) {
            inputMediaFormat.set(mediaFormat);
          }
        },
        inputImageReader.getSurface());

    Image inputImage = checkNotNull(inputImageReader.acquireLatestImage());
    HardwareBuffer inputHardwareBuffer = checkNotNull(inputImage.getHardwareBuffer());

    Format inputFormat =
        MediaFormatUtil.createFormatFromMediaFormat(inputMediaFormat.get())
            .buildUpon()
            .setWidth(width)
            .setHeight(height)
            .build();

    converter =
        new HardwareBufferToGlTextureConverter(
            context,
            HardwareBufferJni.INSTANCE,
            /* outputColorInfo= */ COLORSPACE_SDR_SRGB,
            /* errorConsumer= */ e -> {
              throw new AssertionError(e);
            });

    AtomicReference<Frame> completedFrame = new AtomicReference<>();
    FrameProcessor.Listener listener =
        new FrameProcessor.Listener() {
          @Override
          public void onWakeup() {}

          @Override
          public void onError(VideoFrameProcessingException exception) {
            throw new AssertionError(exception);
          }

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper releaseFence) {
            if (releaseFence != null) {
              assertThat(releaseFence.awaitMs(FENCE_TIMEOUT_MS)).isTrue();
              releaseFence.close();
            }
            inputHardwareBuffer.close();
            inputImage.close();
            completedFrame.set(frame);
          }
        };

    HardwareBufferFrame inputHardwareBufferFrame =
        new DefaultHardwareBufferFrame.Builder(inputHardwareBuffer).setFormat(inputFormat).build();

    Bitmap expectedBitmap =
        readBitmap(
            Util.formatInvariant(
                "test-generated-goldens/HardwareBufferToGlTextureConverterTest/%s.png",
                testName.getMethodName()));

    Bitmap actualBitmap = convertAndCaptureBitmap(inputHardwareBufferFrame, listener);

    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedBitmap, actualBitmap, testName.getMethodName()))
        .isLessThan(MAX_PIXEL_DIFFERENCE);
    assertThat(completedFrame.get()).isSameInstanceAs(inputHardwareBufferFrame);
  }

  @SdkSuppress(minSdkVersion = 31)
  @Test
  public void convert_withRgba8888HardwareBufferAndSdrToHdrConversion_outputsCorrectGlTexture()
      throws Exception {
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap("media/png/first_frame_1920x1080.png");
    Bitmap hardwareBitmap = inputBitmap.copy(Bitmap.Config.HARDWARE, /* isMutable= */ false);
    HardwareBuffer hardwareBuffer = checkNotNull(hardwareBitmap.getHardwareBuffer());
    assertThat(hardwareBuffer.getFormat()).isEqualTo(HardwareBuffer.RGBA_8888);

    Format inputFormat =
        new Format.Builder()
            .setWidth(inputBitmap.getWidth())
            .setHeight(inputBitmap.getHeight())
            .setColorInfo(ColorInfo.SRGB_BT709_FULL)
            .build();
    HardwareBufferFrame inputHardwareBufferFrame =
        new DefaultHardwareBufferFrame.Builder(hardwareBuffer).setFormat(inputFormat).build();

    try {
      ColorInfo hlgColorInfo =
          new ColorInfo.Builder()
              .setColorSpace(C.COLOR_SPACE_BT2020)
              .setColorTransfer(C.COLOR_TRANSFER_HLG)
              .build();

      converter =
          new HardwareBufferToGlTextureConverter(
              context,
              HardwareBufferJni.INSTANCE,
              // Forces SDR - HDR upsampling
              /* outputColorInfo= */ hlgColorInfo,
              e -> {
                throw new AssertionError(e);
              });

      AtomicReference<Frame> completedFrame = new AtomicReference<>();
      FrameProcessor.Listener listener =
          new FrameProcessor.Listener() {
            @Override
            public void onWakeup() {}

            @Override
            public void onError(VideoFrameProcessingException exception) {
              throw new AssertionError(exception);
            }

            @Override
            public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper releaseFence) {
              if (releaseFence != null) {
                assertThat(releaseFence.awaitMs(FENCE_TIMEOUT_MS)).isTrue();
                releaseFence.close();
              }
              completedFrame.set(frame);
            }
          };

      Bitmap expectedBitmap =
          BitmapPixelTestUtil.readBitmap(
              Util.formatInvariant(
                  "test-generated-goldens/HardwareBufferToGlTextureConverterTest/%s.png",
                  testName.getMethodName()));

      Bitmap actualBitmap = convertAndCaptureBitmap(inputHardwareBufferFrame, listener);

      assertFp16BitmapsAreSimilar(expectedBitmap, actualBitmap, TestUtil.PSNR_THRESHOLD);
      assertThat(completedFrame.get()).isSameInstanceAs(inputHardwareBufferFrame);
    } finally {
      hardwareBuffer.close();
    }
  }

  /**
   * Tests SDR to HDR (HLG) conversion.
   *
   * <p>Expected Value Calculation:
   *
   * <ul>
   *   <li>Input SDR electrical values in {@code [0.0, 1.0]} are linearized using the sRGB EOTF
   *       (ITU-R BT.709 display light).
   *   <li>Display light is converted to BT.2020 scene light using {@code BT709_TO_XYZ}, an inverse
   *       HLG OOTF scaling in XYZ luminance ({@code Y^(-1/6)} for system gamma = 1.2), and {@code
   *       XYZ_TO_BT2020} per ITU-R BT.2408 section 5.1.1.
   *   <li>Scene light is scaled down by the BT.2408 diffuse white factor {@code hlgEotf(0.75)}, so
   *       that SDR reference white maps to the 75% HLG signal level (203 nits) rather than to HLG
   *       peak.
   *   <li>The resulting scene light is encoded into HLG electrical signal using the HLG OETF per
   *       ITU-R BT.2100-2 Table 5.
   * </ul>
   */
  @SdkSuppress(minSdkVersion = 31)
  @Test
  public void convert_withSolidColorSdrBuffersAndHlgOutput_outputsCorrectValues(
      @TestParameter(valuesProvider = SolidColorTestCasesProvider.class)
          SolidColorTestCase testCase)
      throws Exception {
    assertSolidColorSdrUpsampling(
        /* outputColorInfo= */ COLORSPACE_HDR_HLG,
        testCase.inputSrgbColor,
        testCase.expectedHlgRgb);
  }

  /**
   * Tests SDR to HDR (Linear) conversion.
   *
   * <p>Expected Value Calculation:
   *
   * <ul>
   *   <li>Input SDR electrical values in {@code [0.0, 1.0]} are linearized using the sRGB EOTF
   *       (ITU-R BT.709 display light).
   *   <li>Display light is converted to BT.2020 scene light using {@code BT709_TO_XYZ}, an inverse
   *       HLG OOTF scaling in XYZ luminance ({@code Y^(-1/6)} for system gamma = 1.2), and {@code
   *       XYZ_TO_BT2020} per ITU-R BT.2408 section 5.1.1.
   *   <li>Output is kept in optical linear BT.2020 scene light without applying an OETF.
   *   <li>No scaling is applied: the scene light is already anchored on the BT.2408 diffuse white
   *       reference, so SDR reference white maps to {@code 1.0}, the same value that HDR diffuse
   *       white ({@code hlgEotf(0.75)}, 203 nits) maps to in this working space.
   * </ul>
   */
  @SdkSuppress(minSdkVersion = 31)
  @Test
  public void convert_withSolidColorSdrBuffersAndHdrLinearOutput_outputsCorrectValues(
      @TestParameter(valuesProvider = SolidColorTestCasesProvider.class)
          SolidColorTestCase testCase)
      throws Exception {
    assertSolidColorSdrUpsampling(
        /* outputColorInfo= */ COLORSPACE_HDR_LINEAR,
        testCase.inputSrgbColor,
        testCase.expectedBt2020LinearRgb);
  }

  @RequiresApi(31)
  private void assertSolidColorSdrUpsampling(
      ColorInfo outputColorInfo, int inputSrgbColor, Color expectedOutputColorRgb)
      throws Exception {
    int width = 64;
    int height = 64;

    Bitmap inputBitmap = createArgb8888BitmapWithSolidColor(width, height, inputSrgbColor);
    Bitmap hardwareBitmap = inputBitmap.copy(Bitmap.Config.HARDWARE, /* isMutable= */ false);
    HardwareBuffer hardwareBuffer = checkNotNull(hardwareBitmap.getHardwareBuffer());
    HardwareBufferFrame inputHardwareBufferFrame =
        new DefaultHardwareBufferFrame.Builder(hardwareBuffer)
            .setFormat(
                new Format.Builder()
                    .setWidth(width)
                    .setHeight(height)
                    .setColorInfo(ColorInfo.SRGB_BT709_FULL)
                    .build())
            .build();

    try {
      converter =
          new HardwareBufferToGlTextureConverter(
              context,
              HardwareBufferJni.INSTANCE,
              /* outputColorInfo= */ outputColorInfo,
              e -> {
                throw new AssertionError(e);
              });

      AtomicReference<Frame> completedFrame = new AtomicReference<>();
      FrameProcessor.Listener listener =
          new FrameProcessor.Listener() {
            @Override
            public void onWakeup() {}

            @Override
            public void onError(VideoFrameProcessingException exception) {
              throw new AssertionError(exception);
            }

            @Override
            public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper releaseFence) {
              if (releaseFence != null) {
                assertThat(releaseFence.awaitMs(FENCE_TIMEOUT_MS)).isTrue();
                releaseFence.close();
              }
              completedFrame.set(frame);
            }
          };

      Bitmap expectedBitmap =
          createFp16BitmapWithSolidColor(
              width,
              height,
              expectedOutputColorRgb.red(),
              expectedOutputColorRgb.green(),
              expectedOutputColorRgb.blue());

      Bitmap actualBitmap = convertAndCaptureBitmap(inputHardwareBufferFrame, listener);

      assertFp16BitmapsAreSimilar(expectedBitmap, actualBitmap, TestUtil.PSNR_THRESHOLD);
      assertThat(completedFrame.get()).isSameInstanceAs(inputHardwareBufferFrame);
    } finally {
      hardwareBuffer.close();
    }
  }

  @SdkSuppress(minSdkVersion = 31)
  @Test
  public void convert_withRgba8888HardwareBufferAndRotation_outputsCorrectGlTexture()
      throws Exception {
    int rotationDegrees = 90;
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap("media/png/first_frame_1920x1080.png");
    Bitmap hardwareBitmap = inputBitmap.copy(Bitmap.Config.HARDWARE, /* isMutable= */ false);
    HardwareBuffer hardwareBuffer = hardwareBitmap.getHardwareBuffer();
    Format inputFormat =
        new Format.Builder()
            .setWidth(inputBitmap.getWidth())
            .setHeight(inputBitmap.getHeight())
            .setRotationDegrees(rotationDegrees)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();
    HardwareBufferFrame inputHardwareBufferFrame =
        new DefaultHardwareBufferFrame.Builder(hardwareBuffer).setFormat(inputFormat).build();

    Matrix rotationMatrix = new Matrix();
    rotationMatrix.postRotate(rotationDegrees);
    Bitmap expectedBitmap =
        Bitmap.createBitmap(
            inputBitmap,
            /* x= */ 0,
            /* y= */ 0,
            /* width= */ inputBitmap.getWidth(),
            /* height= */ inputBitmap.getHeight(),
            rotationMatrix,
            /* filter= */ true);

    FrameProcessor.Listener listener =
        new FrameProcessor.Listener() {
          @Override
          public void onWakeup() {}

          @Override
          public void onError(VideoFrameProcessingException exception) {
            throw new AssertionError(exception);
          }

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper releaseFence) {
            if (releaseFence != null) {
              assertThat(releaseFence.awaitMs(FENCE_TIMEOUT_MS)).isTrue();
              releaseFence.close();
            }
            hardwareBuffer.close();
          }
        };

    Bitmap actualBitmap = convertAndCaptureBitmap(inputHardwareBufferFrame, listener);

    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedBitmap, actualBitmap, testName.getMethodName()))
        .isLessThan(MAX_PIXEL_DIFFERENCE);
  }

  @SdkSuppress(minSdkVersion = 31)
  @Test
  public void convert_withRgba8888HardwareBufferAndCrop_outputsCorrectGlTexture() throws Exception {
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap("media/png/first_frame_1920x1080.png");
    Bitmap hardwareBitmap = inputBitmap.copy(Bitmap.Config.HARDWARE, /* isMutable= */ false);
    HardwareBuffer hardwareBuffer = hardwareBitmap.getHardwareBuffer();
    assertThat(hardwareBuffer.getFormat()).isEqualTo(HardwareBuffer.RGBA_8888);

    int formatWidth = inputBitmap.getWidth() / 2;
    int formatHeight = inputBitmap.getHeight() / 2;

    AtomicReference<Frame> completedFrame = new AtomicReference<>();
    FrameProcessor.Listener listener =
        new FrameProcessor.Listener() {
          @Override
          public void onWakeup() {}

          @Override
          public void onError(VideoFrameProcessingException exception) {}

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper releaseFence) {
            if (releaseFence != null) {
              assertThat(releaseFence.awaitMs(FENCE_TIMEOUT_MS)).isTrue();
              releaseFence.close();
            }
            hardwareBuffer.close();
            completedFrame.set(frame);
          }
        };

    Format inputFormat =
        new Format.Builder()
            .setWidth(formatWidth)
            .setHeight(formatHeight)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();

    HardwareBufferFrame inputHardwareBufferFrame =
        new DefaultHardwareBufferFrame.Builder(hardwareBuffer).setFormat(inputFormat).build();

    Bitmap actualBitmap = convertAndCaptureBitmap(inputHardwareBufferFrame, listener);

    Bitmap expectedBitmap =
        Bitmap.createBitmap(inputBitmap, /* x= */ 0, /* y= */ 0, formatWidth, formatHeight);

    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedBitmap, actualBitmap, testName.getMethodName()))
        .isLessThan(MAX_PIXEL_DIFFERENCE);
    assertThat(completedFrame.get()).isSameInstanceAs(inputHardwareBufferFrame);
  }

  @SdkSuppress(minSdkVersion = 31)
  @Test
  public void convert_releaseAfterReleasingGlResources_doesNotThrow() throws Exception {
    Bitmap expectedBitmap = BitmapPixelTestUtil.readBitmap("media/png/first_frame_1920x1080.png");
    Bitmap hardwareBitmap = expectedBitmap.copy(Bitmap.Config.HARDWARE, /* isMutable= */ false);
    HardwareBuffer hardwareBuffer = hardwareBitmap.getHardwareBuffer();

    HardwareBufferFrame hardwareBufferFrame =
        new DefaultHardwareBufferFrame.Builder(hardwareBuffer)
            .setFormat(new Format.Builder().setWidth(100).setHeight(100).build())
            .build();

    glExecutorService
        .submit(
            () -> {
              try {
                int unused = setupOpenGl(checkNotNull(glObjectsProvider));
                GlTextureFrame glTextureFrame =
                    converter.convert(
                        hardwareBufferFrame,
                        glExecutorService,
                        directExecutor(),
                        new FrameProcessor.Listener() {
                          @Override
                          public void onWakeup() {}

                          @Override
                          public void onError(VideoFrameProcessingException exception) {}

                          @Override
                          public void onFrameProcessed(
                              Frame frame, @Nullable SyncFenceWrapper releaseFence) {}
                        });

                converter.releaseGlResources(hardwareBufferFrame);
                // This should be no-op as GL resources is already released.
                glTextureFrame.release(/* releaseFence= */ null);
              } catch (GlException | VideoFrameProcessingException e) {
                throw new IllegalStateException(e);
              }
            })
        .get();

    hardwareBuffer.close();
  }

  @SdkSuppress(minSdkVersion = 34)
  @Test
  public void convert_withUltraHdrBitmapAndHdrOutput_outputsCorrectGlTexture() throws Exception {
    assertUltraHdrConversion(COLORSPACE_HDR_HLG);
  }

  @SdkSuppress(minSdkVersion = 34)
  @Test
  public void convert_withUltraHdrBitmapAndSdrOutput_outputsCorrectToneMappedGlTexture()
      throws Exception {
    assertUltraHdrConversion(COLORSPACE_SDR_SRGB);
  }

  @SdkSuppress(minSdkVersion = 34)
  @Test
  public void convert_withUltraHdr_releaseAfterReleasingGlResources_doesNotThrow()
      throws Exception {
    Bitmap ultraHdrBitmap = BitmapPixelTestUtil.readBitmap("media/jpeg/ultraHDR.jpg");
    assertThat(ultraHdrBitmap.hasGainmap()).isTrue();
    Bitmap hardwareBitmap = ultraHdrBitmap.copy(Bitmap.Config.HARDWARE, /* isMutable= */ false);
    HardwareBuffer hardwareBuffer = hardwareBitmap.getHardwareBuffer();

    converter =
        new HardwareBufferToGlTextureConverter(
            context,
            HardwareBufferJni.INSTANCE,
            COLORSPACE_HDR_HLG,
            e -> {
              throw new IllegalStateException(e);
            });

    int width = ultraHdrBitmap.getWidth();
    int height = ultraHdrBitmap.getHeight();
    Format inputFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.IMAGE_JPEG_R)
            .setWidth(width)
            .setHeight(height)
            .setColorInfo(ColorInfo.SRGB_BT709_FULL)
            .build();

    HardwareBufferFrame hardwareBufferFrame =
        new DefaultHardwareBufferFrame.Builder(hardwareBuffer)
            .setFormat(inputFormat)
            .setInternalImage(ultraHdrBitmap)
            .build();

    glExecutorService
        .submit(
            () -> {
              try {
                int unused = setupOpenGl(checkNotNull(glObjectsProvider));
                GlTextureFrame glTextureFrame =
                    converter.convert(
                        hardwareBufferFrame,
                        glExecutorService,
                        directExecutor(),
                        new FrameProcessor.Listener() {
                          @Override
                          public void onWakeup() {}

                          @Override
                          public void onError(VideoFrameProcessingException exception) {
                            throw new IllegalStateException(exception);
                          }

                          @Override
                          public void onFrameProcessed(
                              Frame frame, @Nullable SyncFenceWrapper releaseFence) {}
                        });

                converter.releaseGlResources(hardwareBufferFrame);
                // This should be no-op as GL resources are already released.
                glTextureFrame.release(/* releaseFence= */ null);
              } catch (GlException | VideoFrameProcessingException e) {
                throw new IllegalStateException(e);
              }
            })
        .get();

    hardwareBuffer.close();
  }

  private Bitmap convertAndCaptureBitmap(
      HardwareBufferFrame hardwareBufferFrame, FrameProcessor.Listener listener) throws Exception {
    AtomicReference<Bitmap> actualBitmap = new AtomicReference<>();
    glExecutorService
        .submit(
            () -> {
              try {
                int unused = setupOpenGl(checkNotNull(glObjectsProvider));
                GlTextureFrame glTextureFrame =
                    converter.convert(
                        /* hardwareBufferFrame= */ hardwareBufferFrame,
                        glExecutorService,
                        /* listenerExecutor= */ directExecutor(),
                        listener);

                int textureWidth = glTextureFrame.glTextureInfo.width;
                int textureHeight = glTextureFrame.glTextureInfo.height;
                int fboId = GlUtil.createFboForTexture(glTextureFrame.glTextureInfo.texId);
                GlUtil.focusFramebufferUsingCurrentContext(fboId, textureWidth, textureHeight);
                int[] params = new int[1];
                GLES30.glGetFramebufferAttachmentParameteriv(
                    GLES30.GL_FRAMEBUFFER,
                    GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_FRAMEBUFFER_ATTACHMENT_RED_SIZE,
                    params,
                    /* offset= */ 0);
                if (params[0] > 8) {
                  actualBitmap.set(
                      createFp16BitmapFromFocusedGlFramebuffer(textureWidth, textureHeight));
                } else {
                  actualBitmap.set(
                      createArgb8888BitmapFromFocusedGlFramebuffer(textureWidth, textureHeight));
                }
                glTextureFrame.release(/* releaseFence= */ null);
                assertThat(glTextureFrame.format.rotationDegrees).isEqualTo(0);
                GlUtil.deleteFbo(fboId);
              } catch (GlException | VideoFrameProcessingException e) {
                throw new IllegalStateException(e);
              }
            })
        .get(TEST_TIMEOUT_MS, MILLISECONDS);
    // glTextureFrame.release() posts releaseTextureCallback (which invokes
    // listener.onFrameProcessed directly) onto glExecutorService. Wait for that queued
    // task to finish.
    glExecutorService.submit(() -> {}).get(TEST_TIMEOUT_MS, MILLISECONDS);

    return checkNotNull(actualBitmap.get());
  }

  @RequiresApi(34)
  private void assertUltraHdrConversion(ColorInfo outputColorInfo) throws Exception {
    Bitmap ultraHdrBitmap = BitmapPixelTestUtil.readBitmap("media/jpeg/ultraHDR.jpg");
    assertThat(ultraHdrBitmap.hasGainmap()).isTrue();
    Bitmap hardwareBitmap = ultraHdrBitmap.copy(Bitmap.Config.HARDWARE, /* isMutable= */ false);
    HardwareBuffer hardwareBuffer = hardwareBitmap.getHardwareBuffer();

    AtomicReference<VideoFrameProcessingException> errorReference = new AtomicReference<>();
    converter =
        new HardwareBufferToGlTextureConverter(
            context, HardwareBufferJni.INSTANCE, outputColorInfo, errorReference::set);

    AtomicReference<Frame> completedFrame = new AtomicReference<>();
    CountDownLatch frameProcessed = new CountDownLatch(1);
    FrameProcessor.Listener listener =
        new FrameProcessor.Listener() {
          @Override
          public void onWakeup() {}

          @Override
          public void onError(VideoFrameProcessingException exception) {
            errorReference.set(exception);
          }

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper releaseFence) {
            if (releaseFence != null) {
              assertThat(releaseFence.awaitMs(FENCE_TIMEOUT_MS)).isTrue();
              releaseFence.close();
            }
            hardwareBuffer.close();
            completedFrame.set(frame);
            frameProcessed.countDown();
          }
        };

    int width = ultraHdrBitmap.getWidth();
    int height = ultraHdrBitmap.getHeight();
    Format inputFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.IMAGE_JPEG_R)
            .setWidth(width)
            .setHeight(height)
            .setColorInfo(ColorInfo.SRGB_BT709_FULL)
            .build();

    HardwareBufferFrame hardwareBufferFrame =
        new DefaultHardwareBufferFrame.Builder(hardwareBuffer)
            .setFormat(inputFormat)
            .setInternalImage(ultraHdrBitmap)
            .build();

    Bitmap actualBitmap = convertAndCaptureBitmap(hardwareBufferFrame, listener);

    assertThat(actualBitmap.getWidth()).isEqualTo(width);
    assertThat(actualBitmap.getHeight()).isEqualTo(height);
    if (ColorInfo.isTransferHdr(outputColorInfo)) {
      assertThat(actualBitmap.getConfig()).isEqualTo(Bitmap.Config.RGBA_F16);
    }
    assertThat(frameProcessed.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(errorReference.get()).isNull();
    assertThat(completedFrame.get()).isSameInstanceAs(hardwareBufferFrame);
  }

  private static Bitmap createFp16BitmapWithSolidColor(
      int width, int height, float r, float g, float b) {
    // Half.toHalf returns a short. Mask to prevent sign-extension when upcasting.
    long rHalf = (long) Half.toHalf(r) & 0xFFFFL;
    long gHalf = (long) Half.toHalf(g) & 0xFFFFL;
    long bHalf = (long) Half.toHalf(b) & 0xFFFFL;
    long aHalf = (long) Half.toHalf(1.0f) & 0xFFFFL;

    long pixelColor;
    if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
      pixelColor = rHalf | (gHalf << 16) | (bHalf << 32) | (aHalf << 48);
    } else {
      pixelColor = (rHalf << 48) | (gHalf << 32) | (bHalf << 16) | aHalf;
    }

    int numPixels = width * height;
    long[] pixels = new long[numPixels];
    Arrays.fill(pixels, pixelColor);

    ByteBuffer buffer = ByteBuffer.allocateDirect(numPixels * 8).order(ByteOrder.nativeOrder());
    buffer.asLongBuffer().put(pixels);
    buffer.rewind();

    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGBA_F16);
    bitmap.copyPixelsFromBuffer(buffer);
    return bitmap;
  }

  private static final class SolidColorTestCase {
    final String name;
    final int inputSrgbColor;
    final Color expectedHlgRgb;
    final Color expectedBt2020LinearRgb;

    SolidColorTestCase(
        String name, int inputSrgbColor, Color expectedHlgRgb, Color expectedBt2020LinearRgb) {
      this.name = name;
      this.inputSrgbColor = inputSrgbColor;
      this.expectedHlgRgb = expectedHlgRgb;
      this.expectedBt2020LinearRgb = expectedBt2020LinearRgb;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  private static final class SolidColorTestCasesProvider extends TestParameterValuesProvider {
    @Override
    protected ImmutableList<SolidColorTestCase> provideValues(
        com.google.testing.junit.testparameterinjector.TestParameterValuesProvider.Context
            context) {
      return SOLID_COLOR_TEST_CASES;
    }
  }
}
