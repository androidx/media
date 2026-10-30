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
import static androidx.media3.effect.FrameProcessorUtils.releaseOpenGl;
import static androidx.media3.effect.FrameProcessorUtils.setupOpenGl;
import static androidx.media3.effect.FrameProcessorUtils.shutdownGlExecutorService;
import static androidx.media3.effect.FrameProcessorUtils.useHighPrecisionColorComponents;
import static androidx.media3.test.utils.BitmapPixelTestUtil.MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE;
import static androidx.media3.test.utils.BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888;
import static androidx.media3.test.utils.BitmapPixelTestUtil.readBitmap;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
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
import android.graphics.Matrix;
import android.hardware.HardwareBuffer;
import android.opengl.GLES20;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.test.utils.BitmapPixelTestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.SettableFuture;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Instrumentation test for {@link FrameWriterGlTextureFrameConsumer}. */
@RunWith(AndroidJUnit4.class)
public final class FrameWriterGlTextureFrameConsumerTest {

  private static final String TEST_IMAGE_ASSET = "media/png/first_frame_1920x1080.png";

  private static final long TIMEOUT_MS = isRunningOnEmulator() ? 20_000L : 10_000L;
  private static final int BITMAP_WIDTH = 1920;
  private static final int BITMAP_HEIGHT = 1080;
  private static final int SOLID_FRAME_WIDTH = 16;
  private static final int SOLID_FRAME_HEIGHT = 16;
  // Diffuse white in the linear BT.2020 working space, which is anchored on BT.2408 diffuse white.
  private static final float DIFFUSE_WHITE_BT2020_LINEAR = 1f;
  // BT.2408 diffuse white HLG signal level.
  private static final float DIFFUSE_WHITE_HLG = 0.75f;
  // sRGB OETF applied to 0.5: 1.055 * 0.5^(1 / 2.4) - 0.055.
  private static final float HALF_LINEAR_SRGB = 0.7354f;
  private static final float PIXEL_TOLERANCE = 2f;
  // FrameWriterGlTextureFrameConsumer configures the FrameWriter with limited range because
  // encoders output limited range. Only the metadata changes, the written pixels are full range.
  private static final ColorInfo ENCODER_OUTPUT_LIMITED_RANGE_HLG =
      DefaultGlFrameProcessor.BT2020_HLG.buildUpon().setColorRange(C.COLOR_RANGE_LIMITED).build();

  @Rule public final TestName testName;
  private final Context context;

  private @MonotonicNonNull ListeningExecutorService glExecutorService;
  private @MonotonicNonNull GlObjectsProvider glObjectsProvider;
  private @MonotonicNonNull BitmapSavingFrameWriter frameWriter;
  private @MonotonicNonNull ForwardingHardwareBufferJniWrapper fakeJniWrapper;
  private @MonotonicNonNull FrameWriterGlTextureFrameConsumer frameWriterGlTextureFrameConsumer;

  public FrameWriterGlTextureFrameConsumerTest() {
    testName = new TestName();
    context = getApplicationContext();
  }

  @Before
  public void setUp() throws Exception {
    glExecutorService = listeningDecorator(newSingleThreadExecutor());
    glObjectsProvider = new DefaultGlObjectsProvider();
    glExecutorService
        .submit(
            () -> {
              int unused = setupOpenGl(glObjectsProvider);
              return null;
            })
        .get();
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
  @SdkSuppress(minSdkVersion = 29)
  public void queue_copiesPixelsCorrectly() throws Exception {
    setUpHardwareBufferFrameConsumer();
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

    List<Bitmap> actualBitmaps = frameWriter.outputBitmaps;
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
  @SdkSuppress(minSdkVersion = 29)
  public void queue_multipleFrames_copiesPixelsCorrectly() throws Exception {
    setUpHardwareBufferFrameConsumer();
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

    List<Bitmap> actualBitmaps = frameWriter.outputBitmaps;
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
  @SdkSuppress(minSdkVersion = 29)
  public void queue_whenFrameWriterHasNoCapacity_returnsFalseAndDoesNotWriteFrame()
      throws Exception {
    setUpHardwareBufferFrameConsumer();
    // BitmapSavingFrameWriter has capacity for 1 frame; dequeuing without queuing back exhausts it.
    Format format = new Format.Builder().setWidth(BITMAP_WIDTH).setHeight(BITMAP_HEIGHT).build();
    glExecutorService
        .submit(
            () -> {
              frameWriter.configure(format, /* usage= */ 0);
              @SuppressWarnings("UnnamedVariable") // TODO(b/561916275): Remove when issue is fixed.
              AsyncFrame unused =
                  checkNotNull(frameWriter.dequeueInputFrame(directExecutor(), () -> {}));
            })
        .get();
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap(TEST_IMAGE_ASSET);

    boolean queueResult =
        glExecutorService
            .submit(
                () ->
                    frameWriterGlTextureFrameConsumer.queue(
                        createGlTextureFrame(inputBitmap, /* presentationTimeUs= */ 1000),
                        directExecutor(),
                        /* wakeupListener= */ () -> {}))
            .get();

    assertThat(queueResult).isFalse();
    assertThat(frameWriter.outputBitmaps).isEmpty();
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void queue_retriedOnWakeupWhenFrameWriterRegainsCapacity_copiesPixelsCorrectly()
      throws Exception {
    setUpHardwareBufferFrameConsumer();
    // BitmapSavingFrameWriter has capacity for 1 frame; dequeuing without queuing back exhausts it.
    Format format = new Format.Builder().setWidth(BITMAP_WIDTH).setHeight(BITMAP_HEIGHT).build();
    AsyncFrame dequeuedFrame =
        glExecutorService
            .submit(
                () -> {
                  frameWriter.configure(format, /* usage= */ 0);
                  return checkNotNull(frameWriter.dequeueInputFrame(directExecutor(), () -> {}));
                })
            .get();
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap(TEST_IMAGE_ASSET);
    SettableFuture<Boolean> retryQueueResult = SettableFuture.create();

    glExecutorService
        .submit(
            () -> {
              GlTextureFrame frame =
                  createGlTextureFrame(inputBitmap, /* presentationTimeUs= */ 1000);
              boolean queued =
                  frameWriterGlTextureFrameConsumer.queue(
                      frame,
                      directExecutor(),
                      /* wakeupListener= */ () ->
                          retryQueueResult.setFuture(
                              glExecutorService.submit(
                                  () ->
                                      frameWriterGlTextureFrameConsumer.queue(
                                          frame,
                                          directExecutor(),
                                          /* wakeupListener= */ () -> {}))));
              frameWriter.queueInputFrame(dequeuedFrame.frame, /* writeCompleteFence= */ null);
              return queued;
            })
        .get();

    assertThat(retryQueueResult.get(TIMEOUT_MS, MILLISECONDS)).isTrue();
    List<Bitmap> actualBitmaps = frameWriter.outputBitmaps;
    assertThat(actualBitmaps).hasSize(2);
    Bitmap outputBitmap = actualBitmaps.get(1);
    assertThat(outputBitmap).isNotNull();
    assertThat(
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                inputBitmap, outputBitmap, testName.getMethodName()))
        .isLessThan(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void queue_portraitFrameWhenPortraitUnsupported_rotatesToLandscape() throws Exception {
    fakeJniWrapper = new ForwardingHardwareBufferJniWrapper();
    frameWriter =
        BitmapSavingFrameWriter.createForHardwareBuffer(
            /* info= */ (format, usage) -> format.width >= format.height, TIMEOUT_MS);
    frameWriterGlTextureFrameConsumer =
        new FrameWriterGlTextureFrameConsumer(context, frameWriter, fakeJniWrapper);
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

    Bitmap outputBitmap = frameWriter.outputBitmaps.get(0);
    assertThat(
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedBitmap, outputBitmap, testName.getMethodName()))
        .isLessThan(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);

    Format configuredFrameWriterFormat = frameWriter.configuredFormat;
    assertThat(configuredFrameWriterFormat.width).isEqualTo(BITMAP_WIDTH);
    assertThat(configuredFrameWriterFormat.height).isEqualTo(BITMAP_HEIGHT);
    assertThat(configuredFrameWriterFormat.rotationDegrees).isEqualTo(270);
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void queue_landscapeThenPortrait_outputsCorrectBitmaps() throws Exception {
    setUpHardwareBufferFrameConsumer();
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
    List<Bitmap> actualBitmaps = frameWriter.outputBitmaps;
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
  @SdkSuppress(minSdkVersion = 29)
  public void queue_portraitThenLandscape_outputsCorrectBitmaps() throws Exception {
    setUpHardwareBufferFrameConsumer();
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
    List<Bitmap> actualBitmaps = frameWriter.outputBitmaps;
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

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void queue_hdrLinearInputAndUnsetOutput_convertsToHlg() throws Exception {
    setUpHardwareBufferFrameConsumer();
    queueFrameOnGlThread(
        () ->
            createSolidGrayGlTextureFrame(
                /* value= */ DIFFUSE_WHITE_BT2020_LINEAR, DefaultGlFrameProcessor.BT2020_LINEAR));

    List<Bitmap> actualBitmaps = frameWriter.outputBitmaps;
    assertThat(frameWriter.configuredFormat.colorInfo).isEqualTo(ENCODER_OUTPUT_LIMITED_RANGE_HLG);
    assertThat(actualBitmaps).hasSize(1);
    assertBitmapIsSolidGray(
        actualBitmaps.get(0),
        /* expectedComponent= */ DIFFUSE_WHITE_HLG * 255,
        testName.getMethodName());
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void queue_sdrLinearInputAndUnsetOutput_convertsToSrgb() throws Exception {
    setUpHardwareBufferFrameConsumer();
    queueFrameOnGlThread(
        () ->
            createSolidGrayGlTextureFrame(/* value= */ 0.5f, DefaultGlFrameProcessor.BT709_LINEAR));

    List<Bitmap> actualBitmaps = frameWriter.outputBitmaps;
    assertThat(frameWriter.configuredFormat.colorInfo).isEqualTo(ColorInfo.SDR_BT709_LIMITED);
    assertThat(actualBitmaps).hasSize(1);
    assertBitmapIsSolidGray(
        actualBitmaps.get(0),
        /* expectedComponent= */ HALF_LINEAR_SRGB * 255,
        testName.getMethodName());
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void queue_srgbInputAndUnsetOutput_writesPixelsUnchanged() throws Exception {
    setUpHardwareBufferFrameConsumer();
    queueFrameOnGlThread(
        () -> createSolidGrayGlTextureFrame(/* value= */ 0.5f, DefaultGlFrameProcessor.BT709_SRGB));

    List<Bitmap> actualBitmaps = frameWriter.outputBitmaps;
    assertThat(frameWriter.configuredFormat.colorInfo).isEqualTo(ColorInfo.SDR_BT709_LIMITED);
    assertThat(actualBitmaps).hasSize(1);
    assertBitmapIsSolidGray(
        actualBitmaps.get(0), /* expectedComponent= */ 0.5f * 255, testName.getMethodName());
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void queue_hlgInputAndUnsetOutput_writesPixelsUnchanged() throws Exception {
    setUpHardwareBufferFrameConsumer();
    queueFrameOnGlThread(
        () -> createSolidGrayGlTextureFrame(/* value= */ 0.5f, DefaultGlFrameProcessor.BT2020_HLG));

    List<Bitmap> actualBitmaps = frameWriter.outputBitmaps;
    assertThat(frameWriter.configuredFormat.colorInfo).isEqualTo(ENCODER_OUTPUT_LIMITED_RANGE_HLG);
    assertThat(actualBitmaps).hasSize(1);
    assertBitmapIsSolidGray(
        actualBitmaps.get(0), /* expectedComponent= */ 0.5f * 255, testName.getMethodName());
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void queue_hdrLinearThenHlgInputAndUnsetOutput_convertsOnlyLinearFrame() throws Exception {
    setUpHardwareBufferFrameConsumer();
    queueFrameOnGlThread(
        () ->
            createSolidGrayGlTextureFrame(
                /* value= */ DIFFUSE_WHITE_BT2020_LINEAR, DefaultGlFrameProcessor.BT2020_LINEAR));
    queueFrameOnGlThread(
        () -> createSolidGrayGlTextureFrame(/* value= */ 0.5f, DefaultGlFrameProcessor.BT2020_HLG));

    List<Bitmap> actualBitmaps = frameWriter.outputBitmaps;
    assertThat(actualBitmaps).hasSize(2);
    assertBitmapIsSolidGray(
        actualBitmaps.get(0),
        /* expectedComponent= */ DIFFUSE_WHITE_HLG * 255,
        testName.getMethodName() + "_frame1");
    assertBitmapIsSolidGray(
        actualBitmaps.get(1),
        /* expectedComponent= */ 0.5f * 255,
        testName.getMethodName() + "_frame2");
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void queue_hdrLinearLandscapeThenPortrait_convertsToHlgAndPillarboxes() throws Exception {
    setUpHardwareBufferFrameConsumer();
    int landscapeWidth = 32;
    int landscapeHeight = 16;
    int portraitWidth = 16;
    int portraitHeight = 32;
    // Linear BT.2020 diffuse white (1.0 = 203 nits) is converted to the 75% HLG signal level
    // (DIFFUSE_WHITE_HLG = 0.75), which scales to round(0.75 * 255) = 191 in 8-bit color channels.
    int convertedHlgGray = Math.round(DIFFUSE_WHITE_HLG * 255);
    int convertedHlgColor = Color.rgb(convertedHlgGray, convertedHlgGray, convertedHlgGray);
    // Scaling 16x32 to fit 32x16 produces an 8x16 center region with 12px pillarbox bars on each
    // side.
    Bitmap expectedPillarboxedBitmap =
        Bitmap.createBitmap(landscapeWidth, landscapeHeight, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(expectedPillarboxedBitmap);
    canvas.drawColor(Color.BLACK);
    Bitmap scaledContentBitmap =
        BitmapPixelTestUtil.createArgb8888BitmapWithSolidColor(
            /* width= */ 8, landscapeHeight, convertedHlgColor);
    canvas.drawBitmap(scaledContentBitmap, /* left= */ 12, /* top= */ 0, /* paint= */ null);

    queueFrameOnGlThread(
        () ->
            createSolidGrayGlTextureFrame(
                landscapeWidth,
                landscapeHeight,
                /* value= */ DIFFUSE_WHITE_BT2020_LINEAR,
                DefaultGlFrameProcessor.BT2020_LINEAR));
    // The first frame sets the output to be landscape. The second portrait frame will be converted
    // to landscape by adding pillar-boxes.
    queueFrameOnGlThread(
        () ->
            createSolidGrayGlTextureFrame(
                portraitWidth,
                portraitHeight,
                /* value= */ DIFFUSE_WHITE_BT2020_LINEAR,
                DefaultGlFrameProcessor.BT2020_LINEAR));

    List<Bitmap> actualBitmaps = frameWriter.outputBitmaps;
    assertBitmapIsSolidGray(
        actualBitmaps.get(0),
        /* expectedComponent= */ DIFFUSE_WHITE_HLG * 255,
        testName.getMethodName() + "_frame1");
    assertThat(
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedPillarboxedBitmap,
                actualBitmaps.get(1),
                testName.getMethodName() + "_frame2"))
        .isAtMost(PIXEL_TOLERANCE);
  }

  @Test
  public void queue_imagePlanesFrame_copiesPixelsCorrectly() throws Exception {
    frameWriter = BitmapSavingFrameWriter.createForImagePlanes(TIMEOUT_MS);
    frameWriterGlTextureFrameConsumer =
        new FrameWriterGlTextureFrameConsumer(
            context, frameWriter, /* hardwareBufferJniWrapper= */ null);
    Bitmap inputBitmap = readBitmap(TEST_IMAGE_ASSET);

    queueFrameOnGlThread(() -> createGlTextureFrame(inputBitmap, /* presentationTimeUs= */ 1000));

    List<Bitmap> actualBitmaps = frameWriter.outputBitmaps;
    assertThat(actualBitmaps).hasSize(1);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                inputBitmap, actualBitmaps.get(0), testName.getMethodName()))
        .isLessThan(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  public void queue_imagePlanesFrameWithBufferOffset_copiesPixelsCorrectly() throws Exception {
    frameWriter =
        BitmapSavingFrameWriter.createForImagePlanes(
            TIMEOUT_MS, /* rgbaRowPaddingBytes= */ 0, /* rgbaBufferPositionOffset= */ 12);
    frameWriterGlTextureFrameConsumer =
        new FrameWriterGlTextureFrameConsumer(
            context, frameWriter, /* hardwareBufferJniWrapper= */ null);
    Bitmap inputBitmap = readBitmap(TEST_IMAGE_ASSET);

    queueFrameOnGlThread(() -> createGlTextureFrame(inputBitmap, /* presentationTimeUs= */ 1000));

    List<Bitmap> actualBitmaps = frameWriter.outputBitmaps;
    assertThat(actualBitmaps).hasSize(1);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                inputBitmap, actualBitmaps.get(0), testName.getMethodName()))
        .isLessThan(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  public void queue_imagePlanesFrameWithRowPaddingAndBufferOffset_copiesPixelsCorrectly()
      throws Exception {
    frameWriter =
        BitmapSavingFrameWriter.createForImagePlanes(
            TIMEOUT_MS, /* rgbaRowPaddingBytes= */ 16, /* rgbaBufferPositionOffset= */ 12);
    frameWriterGlTextureFrameConsumer =
        new FrameWriterGlTextureFrameConsumer(
            context, frameWriter, /* hardwareBufferJniWrapper= */ null);
    Bitmap inputBitmap = readBitmap(TEST_IMAGE_ASSET);

    queueFrameOnGlThread(() -> createGlTextureFrame(inputBitmap, /* presentationTimeUs= */ 1000));

    List<Bitmap> actualBitmaps = frameWriter.outputBitmaps;
    assertThat(actualBitmaps).hasSize(1);
    assertThat(
            getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                inputBitmap, actualBitmaps.get(0), testName.getMethodName()))
        .isLessThan(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void queue_withDisplayTime_forwardsDisplayTime() throws Exception {
    setUpHardwareBufferFrameConsumer();
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap(TEST_IMAGE_ASSET);

    queueFrameOnGlThread(
        () ->
            createGlTextureFrame(inputBitmap, /* presentationTimeUs= */ 1000)
                .buildUpon()
                .setMetadata(ImmutableMap.of(Frame.KEY_DISPLAY_TIME_NS, 123456789L))
                .build());

    assertThat(frameWriter.outputMetadata).hasSize(1);
    assertThat(frameWriter.outputMetadata.get(0))
        .containsEntry(Frame.KEY_DISPLAY_TIME_NS, 123456789L);
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void queue_withoutDisplayTime_forwardsNoDisplayTime() throws Exception {
    setUpHardwareBufferFrameConsumer();
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap(TEST_IMAGE_ASSET);

    queueFrameOnGlThread(() -> createGlTextureFrame(inputBitmap, /* presentationTimeUs= */ 1000));

    assertThat(frameWriter.outputMetadata).hasSize(1);
    assertThat(frameWriter.outputMetadata.get(0)).doesNotContainKey(Frame.KEY_DISPLAY_TIME_NS);
  }

  @Test
  public void queue_imagePlanesFrameWithDisplayTime_forwardsDisplayTime() throws Exception {
    frameWriter = BitmapSavingFrameWriter.createForImagePlanes(TIMEOUT_MS);
    frameWriterGlTextureFrameConsumer =
        new FrameWriterGlTextureFrameConsumer(
            context, frameWriter, /* hardwareBufferJniWrapper= */ null);
    Bitmap inputBitmap = readBitmap(TEST_IMAGE_ASSET);

    queueFrameOnGlThread(
        () ->
            createGlTextureFrame(inputBitmap, /* presentationTimeUs= */ 1000)
                .buildUpon()
                .setMetadata(ImmutableMap.of(Frame.KEY_DISPLAY_TIME_NS, 987654321L))
                .build());

    assertThat(frameWriter.outputMetadata).hasSize(1);
    assertThat(frameWriter.outputMetadata.get(0))
        .containsEntry(Frame.KEY_DISPLAY_TIME_NS, 987654321L);
  }

  @Test
  public void queue_imagePlanesFrameWithoutDisplayTime_forwardsNoDisplayTime() throws Exception {
    frameWriter = BitmapSavingFrameWriter.createForImagePlanes(TIMEOUT_MS);
    frameWriterGlTextureFrameConsumer =
        new FrameWriterGlTextureFrameConsumer(
            context, frameWriter, /* hardwareBufferJniWrapper= */ null);
    Bitmap inputBitmap = readBitmap(TEST_IMAGE_ASSET);

    queueFrameOnGlThread(() -> createGlTextureFrame(inputBitmap, /* presentationTimeUs= */ 1000));

    assertThat(frameWriter.outputMetadata).hasSize(1);
    assertThat(frameWriter.outputMetadata.get(0)).doesNotContainKey(Frame.KEY_DISPLAY_TIME_NS);
  }

  @Test
  @SdkSuppress(minSdkVersion = 37)
  public void queue_hdrOutputWithHagcMetadata_keepsHagcMetadata() throws Exception {
    setUpHardwareBufferFrameConsumer();
    byte[] hagcPayload = new byte[] {1, 2, 3, 4};

    queueFrameOnGlThread(
        () ->
            createSolidGrayGlTextureFrame(
                    /* value= */ DIFFUSE_WHITE_BT2020_LINEAR, DefaultGlFrameProcessor.BT2020_LINEAR)
                .buildUpon()
                .setMetadata(ImmutableMap.of(Frame.KEY_HDR_ST2094_50_INFO, hagcPayload))
                .build());

    assertThat(frameWriter.outputMetadata.get(0).get(Frame.KEY_HDR_ST2094_50_INFO))
        .isEqualTo(new byte[] {1, 2, 3, 4});
  }

  @Test
  @SdkSuppress(minSdkVersion = 37)
  public void queue_hdrOutputWithoutHagcMetadata_forwardsNoMetadata() throws Exception {
    setUpHardwareBufferFrameConsumer();

    queueFrameOnGlThread(
        () ->
            createSolidGrayGlTextureFrame(
                    /* value= */ DIFFUSE_WHITE_BT2020_LINEAR, DefaultGlFrameProcessor.BT2020_LINEAR)
                .buildUpon()
                .setMetadata(ImmutableMap.of(Frame.KEY_PRESENTATION_TIME_US, 123456L))
                .build());

    assertThat(frameWriter.outputMetadata.get(0)).isEmpty();
  }

  @Test
  @SdkSuppress(minSdkVersion = 37)
  public void queue_sdrOutputWithHagcMetadata_dropsHagcMetadata() throws Exception {
    setUpHardwareBufferFrameConsumer();
    byte[] hagcPayload = new byte[] {1, 2, 3, 4};

    queueFrameOnGlThread(
        () ->
            createSolidGrayGlTextureFrame(/* value= */ 0.5f, DefaultGlFrameProcessor.BT709_LINEAR)
                .buildUpon()
                .setMetadata(
                    ImmutableMap.of(
                        Frame.KEY_HDR_ST2094_50_INFO,
                        hagcPayload,
                        Frame.KEY_DISPLAY_TIME_NS,
                        1_000_000_000L))
                .build());

    assertThat(frameWriter.outputMetadata.get(0))
        .containsExactly(Frame.KEY_DISPLAY_TIME_NS, 1_000_000_000L);
  }

  @Test
  @SdkSuppress(minSdkVersion = 37)
  public void queue_hdrOutput_forwardsSelectedMetadata() throws Exception {
    setUpHardwareBufferFrameConsumer();
    byte[] hagcPayload = new byte[] {1, 2, 3};

    queueFrameOnGlThread(
        () ->
            createSolidGrayGlTextureFrame(
                    /* value= */ DIFFUSE_WHITE_BT2020_LINEAR, DefaultGlFrameProcessor.BT2020_LINEAR)
                .buildUpon()
                .setMetadata(
                    ImmutableMap.of(
                        Frame.KEY_HDR_ST2094_50_INFO,
                        hagcPayload,
                        Frame.KEY_DISPLAY_TIME_NS,
                        1_000_000_000L,
                        Frame.KEY_PRESENTATION_TIME_US,
                        123456L))
                .build());

    assertThat(frameWriter.outputMetadata.get(0).keySet())
        .containsExactly(Frame.KEY_HDR_ST2094_50_INFO, Frame.KEY_DISPLAY_TIME_NS);
    assertThat(frameWriter.outputMetadata.get(0).get(Frame.KEY_HDR_ST2094_50_INFO))
        .isEqualTo(new byte[] {1, 2, 3});
    assertThat(frameWriter.outputMetadata.get(0))
        .containsEntry(Frame.KEY_DISPLAY_TIME_NS, 1_000_000_000L);
  }

  @RequiresApi(29)
  private void setUpHardwareBufferFrameConsumer() {
    fakeJniWrapper = new ForwardingHardwareBufferJniWrapper();
    frameWriter = BitmapSavingFrameWriter.createForHardwareBuffer(TIMEOUT_MS);
    frameWriterGlTextureFrameConsumer =
        new FrameWriterGlTextureFrameConsumer(context, frameWriter, fakeJniWrapper);
  }

  /** Creates a {@link GlTextureFrame} on the GL thread and queues it to the consumer under test. */
  private void queueFrameOnGlThread(GlTextureFrameSupplier frameSupplier) throws Exception {
    glExecutorService
        .submit(
            () -> {
              try {
                assertThat(
                        frameWriterGlTextureFrameConsumer.queue(
                            frameSupplier.get(), directExecutor(), /* wakeupListener= */ () -> {}))
                    .isTrue();
              } catch (Exception e) {
                throw new AssertionError(e);
              }
            })
        .get(TIMEOUT_MS, MILLISECONDS);
  }

  /**
   * Asserts that every pixel of {@code bitmap} is the gray {@code expectedComponent}, within {@link
   * #PIXEL_TOLERANCE} on average.
   */
  private static void assertBitmapIsSolidGray(
      Bitmap bitmap, float expectedComponent, String testId) {
    int expectedValue = Math.round(expectedComponent);
    Bitmap expectedBitmap =
        BitmapPixelTestUtil.createArgb8888BitmapWithSolidColor(
            bitmap.getWidth(),
            bitmap.getHeight(),
            Color.rgb(expectedValue, expectedValue, expectedValue));
    assertThat(
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                expectedBitmap, bitmap, testId))
        .isAtMost(PIXEL_TOLERANCE);
  }

  /**
   * Creates a {@link GlTextureFrame} filled with the gray {@code value}, whose format has the given
   * {@link ColorInfo}.
   */
  private static GlTextureFrame createSolidGrayGlTextureFrame(float value, ColorInfo colorInfo)
      throws GlException {
    return createSolidGrayGlTextureFrame(SOLID_FRAME_WIDTH, SOLID_FRAME_HEIGHT, value, colorInfo);
  }

  /**
   * Creates a {@link GlTextureFrame} with the given dimensions filled with the gray {@code value},
   * whose format has the given {@link ColorInfo}.
   */
  private static GlTextureFrame createSolidGrayGlTextureFrame(
      int width, int height, float value, ColorInfo colorInfo) throws GlException {
    int texId = GlUtil.createTexture(width, height, useHighPrecisionColorComponents(colorInfo));
    int fboId = GlUtil.createFboForTexture(texId);
    GlUtil.focusFramebufferUsingCurrentContext(fboId, width, height);
    GLES20.glClearColor(value, value, value, /* alpha= */ 1f);
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    GlUtil.checkGlError();
    return new GlTextureFrame.Builder(
            new GlTextureInfo(texId, fboId, /* rboId= */ C.INDEX_UNSET, width, height),
            directExecutor(),
            /* releaseTextureCallback= */ textureInfo -> {
              try {
                textureInfo.release();
              } catch (GlException e) {
                throw new AssertionError(e);
              }
            })
        .setFormat(
            new Format.Builder().setWidth(width).setHeight(height).setColorInfo(colorInfo).build())
        .build();
  }

  private interface GlTextureFrameSupplier {
    GlTextureFrame get() throws GlException;
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

  @RequiresApi(26)
  private static final class ForwardingHardwareBufferJniWrapper
      implements HardwareBufferJniWrapper {
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
}
