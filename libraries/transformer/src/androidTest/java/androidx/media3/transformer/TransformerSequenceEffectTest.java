/*
 * Copyright 2023 The Android Open Source Project
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
 *
 */

package androidx.media3.transformer;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.test.utils.AssetInfo.BT601_MOV_ASSET;
import static androidx.media3.test.utils.AssetInfo.JPG_ASSET;
import static androidx.media3.test.utils.AssetInfo.JPG_PORTRAIT_ASSET;
import static androidx.media3.test.utils.AssetInfo.MP4_ADVANCED_ASSET;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_AV1_VIDEO;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_CHECKERBOARD_VIDEO;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S;
import static androidx.media3.test.utils.AssetInfo.MP4_PORTRAIT_ASSET;
import static androidx.media3.test.utils.AssetInfo.PNG_ASSET_LINES_1080P;
import static androidx.media3.test.utils.BitmapPixelTestUtil.MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE_LUMA;
import static androidx.media3.test.utils.BitmapPixelTestUtil.readBitmap;
import static androidx.media3.test.utils.FormatSupportAssumptions.assumeFormatsSupported;
import static androidx.media3.transformer.AndroidTestUtil.extractBitmapsFromVideo;
import static androidx.media3.transformer.GlFrameProcessorTestUtil.closeTestingGlResources;
import static androidx.media3.transformer.SequenceEffectTestUtil.NO_EFFECT;
import static androidx.media3.transformer.SequenceEffectTestUtil.PSNR_THRESHOLD;
import static androidx.media3.transformer.SequenceEffectTestUtil.PSNR_THRESHOLD_HD;
import static androidx.media3.transformer.SequenceEffectTestUtil.SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS;
import static androidx.media3.transformer.SequenceEffectTestUtil.assertFramesMatchExpectedPsnrAndSave;
import static androidx.media3.transformer.SequenceEffectTestUtil.clippedVideo;
import static androidx.media3.transformer.SequenceEffectTestUtil.createVideoOnlyComposition;
import static androidx.media3.transformer.SequenceEffectTestUtil.decoderProducesWashedOutColours;
import static androidx.media3.transformer.SequenceEffectTestUtil.oneFrameFromImage;
import static androidx.media3.transformer.SequenceEffectTestUtil.tryToExportCompositionWithDecoder;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.DefaultGlFrameProcessor;
import androidx.media3.effect.DefaultGlObjectsProvider;
import androidx.media3.effect.DefaultVideoFrameProcessor;
import androidx.media3.effect.FrameProcessorUtils;
import androidx.media3.effect.LanczosResample;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.RgbFilter;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.effect.ndk.HardwareBufferJni;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * Tests for using different {@linkplain Effect effects} for {@link MediaItem MediaItems} in one
 * {@link EditedMediaItemSequence}.
 */
@RunWith(Parameterized.class)
public final class TransformerSequenceEffectTest {

  private static final String OVERLAY_PNG_ASSET_PATH = "media/png/media3test.png";
  private static final int EXPORT_WIDTH = 360;
  private static final int EXPORT_HEIGHT = 240;
  private static final int SQUARE_SIZE = 240;
  private static final long TEST_TIMEOUT_MS = 10_000;

  private final Context context = ApplicationProvider.getApplicationContext();
  @Rule public final TestName testName = new TestName();

  @Parameters(name = "useDefaultGlFrameProcessor={0}")
  public static ImmutableList<Boolean> parameters() {
    // When false, DefaultVideoFrameProcessor is used.
    return ImmutableList.of(true, false);
  }

  @Parameter public boolean useDefaultGlFrameProcessor;

  private @MonotonicNonNull ListeningExecutorService glExecutorService;
  private @MonotonicNonNull GlObjectsProvider glObjectsProvider;

  private String testId;

  @Before
  public void setUp() throws Exception {
    testId = testName.getMethodName();
    // Remove the parameter part from the test ID to locate the correct test golden files.
    int bracketIndex = testId.indexOf('[');
    if (bracketIndex != -1) {
      testId = testId.substring(0, bracketIndex);
    }

    if (useDefaultGlFrameProcessor) {
      assumeTrue(SDK_INT >= 28);
      glObjectsProvider = new DefaultGlObjectsProvider();
      glExecutorService = listeningDecorator(Executors.newSingleThreadExecutor());
      glExecutorService
          .submit(
              () -> {
                try {
                  if (SDK_INT >= 26) {
                    FrameProcessorUtils.setupOpenGl(checkNotNull(glObjectsProvider));
                  }
                } catch (GlException | RuntimeException e) {
                  throw new AssertionError(e);
                }
              })
          .get(TEST_TIMEOUT_MS, MILLISECONDS);
    }
  }

  @After
  public void tearDown() {
    @Nullable Exception releasingException = null;
    if (shouldUseDefaultGlFrameProcessor()) {
      releasingException =
          closeTestingGlResources(glExecutorService, glObjectsProvider, TEST_TIMEOUT_MS);
    }
    if (glExecutorService != null) {
      glExecutorService.shutdown();
    }
    if (releasingException != null) {
      throw new AssertionError(releasingException);
    }
  }

  private Transformer.Builder createTransformerBuilder() {
    Transformer.Builder builder = new Transformer.Builder(context);
    if (shouldUseDefaultGlFrameProcessor()) {
      builder.setNativeHardwareBufferHelpers(HardwareBufferJni.INSTANCE);
      builder.setFrameProcessorFactory(
          new DefaultGlFrameProcessor.Factory(
              context, glObjectsProvider, HardwareBufferJni.INSTANCE, glExecutorService));
    }
    return builder;
  }

  @Test
  public void export_withNoCompositionPresentationAndWithPerMediaItemEffects() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ MP4_ADVANCED_ASSET.videoFormat);
    OverlayEffect overlayEffect = createOverlayEffect();
    Composition composition =
        createVideoOnlyComposition(
            /* presentation= */ null,
            clippedVideo(
                MP4_ADVANCED_ASSET.uri,
                ImmutableList.of(
                    Presentation.createForWidthAndHeight(
                        EXPORT_WIDTH, EXPORT_HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT)),
                SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS),
            oneFrameFromImage(
                JPG_ASSET.uri,
                ImmutableList.of(
                    new ScaleAndRotateTransformation.Builder().setRotationDegrees(72).build(),
                    overlayEffect)),
            oneFrameFromImage(JPG_ASSET.uri, NO_EFFECT),
            // Transition to a different aspect ratio.
            oneFrameFromImage(
                JPG_ASSET.uri,
                ImmutableList.of(
                    Presentation.createForWidthAndHeight(
                        EXPORT_WIDTH / 2, EXPORT_HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT),
                    new ScaleAndRotateTransformation.Builder().setRotationDegrees(90).build(),
                    overlayEffect)));

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, createTransformerBuilder().build())
            .build()
            .run(testId, composition);

    assertThat(new File(result.filePath).length()).isGreaterThan(0);
    assertBitmapsMatchExpectedAndSave(
        extractBitmapsFromVideo(context, checkNotNull(result.filePath)), testId);
  }

  @Test
  public void export1080x720_withAllAvailableDecoders_doesNotStretchOutputOnAny() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ MP4_ADVANCED_ASSET.videoFormat);
    List<MediaCodecInfo> mediaCodecInfoList =
        MediaCodecSelector.DEFAULT.getDecoderInfos(
            checkNotNull(MP4_ADVANCED_ASSET.videoFormat.sampleMimeType),
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);
    Composition composition =
        createVideoOnlyComposition(
            /* presentation= */ null,
            clippedVideo(
                MP4_ADVANCED_ASSET.uri, NO_EFFECT, /* endPositionMs= */ C.MILLIS_PER_SECOND / 4));

    boolean atLeastOneDecoderSucceeds = false;
    for (MediaCodecInfo mediaCodecInfo : mediaCodecInfoList) {
      if (decoderProducesWashedOutColours(mediaCodecInfo)) {
        continue;
      }
      @Nullable
      ExportTestResult result =
          tryToExportCompositionWithDecoder(testId, context, mediaCodecInfo, composition);
      if (result == null) {
        continue;
      }
      atLeastOneDecoderSucceeds = true;

      assertThat(new File(result.filePath).length()).isGreaterThan(0);
      assertFramesMatchExpectedPsnrAndSave(
          context, testId, checkNotNull(result.filePath), PSNR_THRESHOLD_HD, /* frameCount= */ 1);
    }
    assertThat(atLeastOneDecoderSucceeds).isTrue();
  }

  @Test
  public void export720x1080_withAllAvailableDecoders_doesNotStretchOutputOnAny() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_PORTRAIT_ASSET.videoFormat,
        /* outputFormat= */ MP4_PORTRAIT_ASSET.videoFormat);
    List<MediaCodecInfo> mediaCodecInfoList =
        MediaCodecSelector.DEFAULT.getDecoderInfos(
            checkNotNull(MP4_PORTRAIT_ASSET.videoFormat.sampleMimeType),
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);
    Composition composition =
        createVideoOnlyComposition(
            /* presentation= */ null,
            clippedVideo(
                MP4_PORTRAIT_ASSET.uri, NO_EFFECT, /* endPositionMs= */ C.MILLIS_PER_SECOND / 4));

    boolean atLeastOneDecoderSucceeds = false;
    for (MediaCodecInfo mediaCodecInfo : mediaCodecInfoList) {
      if (decoderProducesWashedOutColours(mediaCodecInfo)) {
        continue;
      }
      @Nullable
      ExportTestResult result =
          tryToExportCompositionWithDecoder(testId, context, mediaCodecInfo, composition);
      if (result == null) {
        continue;
      }
      atLeastOneDecoderSucceeds = true;

      assertThat(new File(result.filePath).length()).isGreaterThan(0);
      assertFramesMatchExpectedPsnrAndSave(
          context, testId, checkNotNull(result.filePath), PSNR_THRESHOLD_HD, /* frameCount= */ 1);
    }
    assertThat(atLeastOneDecoderSucceeds).isTrue();
  }

  @Test
  public void export640x428_withAllAvailableDecoders_doesNotStretchOutputOnAny() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ BT601_MOV_ASSET.videoFormat,
        /* outputFormat= */ BT601_MOV_ASSET.videoFormat);
    List<MediaCodecInfo> mediaCodecInfoList =
        MediaCodecSelector.DEFAULT.getDecoderInfos(
            checkNotNull(BT601_MOV_ASSET.videoFormat.sampleMimeType),
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);
    Composition composition =
        createVideoOnlyComposition(
            /* presentation= */ null,
            clippedVideo(
                BT601_MOV_ASSET.uri, NO_EFFECT, /* endPositionMs= */ C.MILLIS_PER_SECOND / 4));

    boolean atLeastOneDecoderSucceeds = false;
    for (MediaCodecInfo mediaCodecInfo : mediaCodecInfoList) {
      if (decoderProducesWashedOutColours(mediaCodecInfo)) {
        continue;
      }
      @Nullable
      ExportTestResult result =
          tryToExportCompositionWithDecoder(testId, context, mediaCodecInfo, composition);
      if (result == null) {
        continue;
      }
      atLeastOneDecoderSucceeds = true;

      assertThat(new File(result.filePath).length()).isGreaterThan(0);
      assertFramesMatchExpectedPsnrAndSave(
          context, testId, checkNotNull(result.filePath), PSNR_THRESHOLD, /* frameCount= */ 1);
    }
    assertThat(atLeastOneDecoderSucceeds).isTrue();
  }

  @Test
  public void export1080x720Av1_withAllAvailableDecoders_doesNotStretchOutputOnAny()
      throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_AV1_VIDEO.videoFormat,
        /* outputFormat= */ MP4_ASSET_AV1_VIDEO.videoFormat);
    List<MediaCodecInfo> mediaCodecInfoList =
        MediaCodecSelector.DEFAULT.getDecoderInfos(
            checkNotNull(MP4_ASSET_AV1_VIDEO.videoFormat.sampleMimeType),
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);
    Composition composition =
        createVideoOnlyComposition(
            /* presentation= */ null,
            clippedVideo(
                MP4_ASSET_AV1_VIDEO.uri, NO_EFFECT, /* endPositionMs= */ C.MILLIS_PER_SECOND / 4));

    boolean atLeastOneDecoderSucceeds = false;
    for (MediaCodecInfo mediaCodecInfo : mediaCodecInfoList) {
      if (decoderProducesWashedOutColours(mediaCodecInfo)) {
        continue;
      }
      @Nullable
      ExportTestResult result =
          tryToExportCompositionWithDecoder(testId, context, mediaCodecInfo, composition);
      if (result == null) {
        continue;
      }
      atLeastOneDecoderSucceeds = true;

      assertThat(new File(result.filePath).length()).isGreaterThan(0);
      assertFramesMatchExpectedPsnrAndSave(
          context, testId, checkNotNull(result.filePath), PSNR_THRESHOLD_HD, /* frameCount= */ 1);
    }
    assertThat(atLeastOneDecoderSucceeds).isTrue();
  }

  @Test
  public void export854x356_withAllAvailableDecoders_doesNotStretchOutputOnAny() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_CHECKERBOARD_VIDEO.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.videoFormat);
    List<MediaCodecInfo> mediaCodecInfoList =
        MediaCodecSelector.DEFAULT.getDecoderInfos(
            checkNotNull(MP4_ASSET_CHECKERBOARD_VIDEO.videoFormat.sampleMimeType),
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);
    Composition composition =
        createVideoOnlyComposition(
            Presentation.createForWidthAndHeight(
                /* width= */ 320, /* height= */ 240, Presentation.LAYOUT_SCALE_TO_FIT),
            clippedVideo(
                MP4_ASSET_CHECKERBOARD_VIDEO.uri,
                NO_EFFECT,
                /* endPositionMs= */ C.MILLIS_PER_SECOND / 4));

    boolean atLeastOneDecoderSucceeds = false;
    for (MediaCodecInfo mediaCodecInfo : mediaCodecInfoList) {
      if (decoderProducesWashedOutColours(mediaCodecInfo)) {
        continue;
      }
      @Nullable
      ExportTestResult result =
          tryToExportCompositionWithDecoder(testId, context, mediaCodecInfo, composition);
      if (result == null) {
        continue;
      }
      atLeastOneDecoderSucceeds = true;

      assertThat(new File(result.filePath).length()).isGreaterThan(0);
      assertFramesMatchExpectedPsnrAndSave(
          context, testId, checkNotNull(result.filePath), PSNR_THRESHOLD, /* frameCount= */ 1);
    }
    assertThat(atLeastOneDecoderSucceeds).isTrue();
  }

  @Test
  public void export_image_samplesFromTextureCorrectly() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoFormat);
    Composition composition =
        createVideoOnlyComposition(
            /* presentation= */ null,
            new EditedMediaItem.Builder(
                    new MediaItem.Builder()
                        .setUri(PNG_ASSET_LINES_1080P.uri)
                        .setImageDurationMs(C.MILLIS_PER_SECOND / 4)
                        .build())
                .setFrameRate(30)
                .build());
    // Some devices need a very high bitrate to avoid encoding artifacts.
    int bitrate = 30_000_000;
    if (Ascii.equalsIgnoreCase(Build.MODEL, "mi a2 lite")
        || Ascii.equalsIgnoreCase(Build.MODEL, "redmi 8")
        || Ascii.equalsIgnoreCase(Build.MODEL, "sm-f711u1")
        || Ascii.equalsIgnoreCase(Build.MODEL, "sm-t870")
        || Ascii.equalsIgnoreCase(Build.MODEL, "sm-f916u1")
        || Ascii.equalsIgnoreCase(Build.MODEL, "sm-f926u1")
        || Ascii.equalsIgnoreCase(Build.MODEL, "sm-g781n")
        || Ascii.equalsIgnoreCase(Build.MODEL, "sm-g781v")
        || Ascii.equalsIgnoreCase(Build.MODEL, "sm-g981u1")
        || Ascii.equalsIgnoreCase(Build.MODEL, "sm-g986u1")
        || Ascii.equalsIgnoreCase(Build.MODEL, "sm-n981u")
        || Ascii.equalsIgnoreCase(Build.MODEL, "tb-q706")
        || Ascii.equalsIgnoreCase(Build.MODEL, "moto g04")
        || Ascii.equalsIgnoreCase(Build.MODEL, "moto e13")
        || Ascii.equalsIgnoreCase(Build.MODEL, "rmx3760")) {
      // And some devices need a lower bitrate because VideoDecodingWrapper fails to decode high
      // bitrate output, or FrameworkMuxer fails to mux.
      bitrate = 10_000_000;
    }
    Codec.EncoderFactory encoderFactory =
        new DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                new VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .build();
    Transformer transformer =
        createTransformerBuilder()
            .setEncoderFactory(new AndroidTestUtil.ForceEncodeEncoderFactory(encoderFactory))
            .setVideoMimeType("video/avc")
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    assertThat(new File(result.filePath).length()).isGreaterThan(0);
    // The PSNR threshold was chosen based on:
    // Pixel 8 with coordinate rounding error during texture sampling, gets PSNR 23.4.
    // After fix -> 29.5
    // rmx3563 with bug fix achieves PSNR 28.8
    assertFramesMatchExpectedPsnrAndSave(
        context,
        testId,
        checkNotNull(result.filePath),
        // TODO: b/530130453 - Lowering PSNR because DefaultGlFrameProcessor doesn't yet process
        //  frames in linear colors.
        /* psnrThreshold= */ useDefaultGlFrameProcessor ? 24 : 28.5f,
        /* frameCount= */ 2);
  }

  @Test
  public void export_imageWithLanczosResample_completesWithHighPsnr() throws Exception {
    int exportWidth = 640;
    int exportHeight = 240;
    Format outputFormat =
        MP4_ASSET_WITH_INCREASING_TIMESTAMPS
            .videoFormat
            .buildUpon()
            .setWidth(exportWidth)
            .setHeight(exportHeight)
            .build();
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoFormat,
        outputFormat);
    Composition composition =
        createVideoOnlyComposition(
            /* presentation= */ null,
            new EditedMediaItem.Builder(
                    new MediaItem.Builder()
                        .setUri(PNG_ASSET_LINES_1080P.uri)
                        .setImageDurationMs(C.MILLIS_PER_SECOND / 4)
                        .build())
                .setFrameRate(30)
                .setEffects(
                    new Effects(
                        ImmutableList.of(),
                        ImmutableList.of(LanczosResample.scaleToFit(exportWidth, exportHeight))))
                .build());
    // Some devices need a high bitrate to avoid encoding artifacts.
    int bitrate = 2_000_000;
    Codec.EncoderFactory encoderFactory =
        new DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                new VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .build();
    Transformer transformer =
        createTransformerBuilder()
            .setEncoderFactory(new AndroidTestUtil.ForceEncodeEncoderFactory(encoderFactory))
            .setVideoMimeType("video/avc")
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    assertThat(new File(result.filePath).length()).isGreaterThan(0);
    // The PSNR threshold was chosen based on:
    // Moto G20 with Lanczos: 30.1
    // Moto G20 with bilinear: 16.3
    assertFramesMatchExpectedPsnrAndSave(
        context,
        testId,
        checkNotNull(result.filePath),
        /* psnrThreshold= */ 24,
        /* frameCount= */ 1);
  }

  @Test
  public void export_withCompositionPresentationAndWithPerMediaItemEffects() throws Exception {
    assumeFalse("OpenGL pipeline doesn't convert color yet.", useDefaultGlFrameProcessor);
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ MP4_ADVANCED_ASSET.videoFormat);
    Composition composition =
        createVideoOnlyComposition(
            Presentation.createForWidthAndHeight(
                /* width= */ SQUARE_SIZE,
                /* height= */ SQUARE_SIZE,
                Presentation.LAYOUT_SCALE_TO_FIT),
            oneFrameFromImage(
                JPG_ASSET.uri,
                ImmutableList.of(
                    new ScaleAndRotateTransformation.Builder().setRotationDegrees(90).build(),
                    Presentation.createForWidthAndHeight(
                        EXPORT_WIDTH, EXPORT_HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT))),
            oneFrameFromImage(JPG_ASSET.uri, NO_EFFECT),
            clippedVideo(
                MP4_ADVANCED_ASSET.uri,
                ImmutableList.of(RgbFilter.createInvertedFilter()),
                SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS),
            clippedVideo(
                MP4_ADVANCED_ASSET.uri,
                ImmutableList.of(
                    Presentation.createForWidthAndHeight(
                        EXPORT_WIDTH / 2, EXPORT_HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT),
                    createOverlayEffect()),
                SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS));

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, getLinearColorSpaceTransformer())
            .build()
            .run(testId, composition);

    assertThat(new File(result.filePath).length()).isGreaterThan(0);
    assertBitmapsMatchExpectedAndSave(
        extractBitmapsFromVideo(context, checkNotNull(result.filePath)), testId);
  }

  @Test
  public void export_withCompositionPresentationAndWithPerMediaItemEffectsLessVideo()
      throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ MP4_ADVANCED_ASSET.videoFormat);
    Composition composition =
        createVideoOnlyComposition(
            Presentation.createForWidthAndHeight(
                /* width= */ SQUARE_SIZE,
                /* height= */ SQUARE_SIZE,
                Presentation.LAYOUT_SCALE_TO_FIT),
            oneFrameFromImage(
                JPG_ASSET.uri,
                ImmutableList.of(
                    new ScaleAndRotateTransformation.Builder().setRotationDegrees(90).build(),
                    Presentation.createForWidthAndHeight(
                        EXPORT_WIDTH, EXPORT_HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT))),
            oneFrameFromImage(JPG_ASSET.uri, NO_EFFECT),
            clippedVideo(
                MP4_ADVANCED_ASSET.uri,
                ImmutableList.of(
                    Presentation.createForWidthAndHeight(
                        EXPORT_WIDTH / 2, EXPORT_HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT),
                    createOverlayEffect()),
                SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS));

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, getLinearColorSpaceTransformer())
            .build()
            .run(testId, composition);

    assertThat(new File(result.filePath).length()).isGreaterThan(0);
    assertBitmapsMatchExpectedAndSave(
        extractBitmapsFromVideo(context, checkNotNull(result.filePath)), testId);
  }

  @Test
  public void export_withCompositionPresentationAndNoVideoEffects() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ MP4_ADVANCED_ASSET.videoFormat);
    Composition composition =
        createVideoOnlyComposition(
            Presentation.createForHeight(EXPORT_HEIGHT),
            oneFrameFromImage(JPG_ASSET.uri, NO_EFFECT),
            clippedVideo(MP4_PORTRAIT_ASSET.uri, NO_EFFECT, SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS),
            clippedVideo(MP4_ADVANCED_ASSET.uri, NO_EFFECT, SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS),
            oneFrameFromImage(JPG_PORTRAIT_ASSET.uri, NO_EFFECT));

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, getLinearColorSpaceTransformer())
            .build()
            .run(testId, composition);

    assertThat(new File(result.filePath).length()).isGreaterThan(0);
    assertBitmapsMatchExpectedAndSave(
        extractBitmapsFromVideo(context, checkNotNull(result.filePath)), testId);
  }

  @Test
  public void export_withCompositionPresentationAndNoVideoEffectsForFirstMediaItem()
      throws Exception {
    assumeFalse("OpenGL pipeline doesn't convert color yet.", useDefaultGlFrameProcessor);
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ MP4_ADVANCED_ASSET.videoFormat);
    Composition composition =
        createVideoOnlyComposition(
            Presentation.createForHeight(EXPORT_HEIGHT),
            clippedVideo(MP4_ADVANCED_ASSET.uri, NO_EFFECT, SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS),
            clippedVideo(
                MP4_PORTRAIT_ASSET.uri,
                ImmutableList.of(RgbFilter.createInvertedFilter()),
                SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS));

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, getLinearColorSpaceTransformer())
            .build()
            .run(testId, composition);

    assertThat(new File(result.filePath).length()).isGreaterThan(0);
    assertBitmapsMatchExpectedAndSave(
        extractBitmapsFromVideo(context, checkNotNull(result.filePath)), testId);
  }

  @Test
  public void export_withBt601AndBt709MediaItems() throws Exception {
    assumeFalse("OpenGL pipeline doesn't convert color yet.", useDefaultGlFrameProcessor);
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ MP4_ADVANCED_ASSET.videoFormat);
    assumeFormatsSupported(
        context, testId, /* inputFormat= */ BT601_MOV_ASSET.videoFormat, /* outputFormat= */ null);
    Composition composition =
        createVideoOnlyComposition(
            Presentation.createForHeight(EXPORT_HEIGHT),
            clippedVideo(
                BT601_MOV_ASSET.uri,
                ImmutableList.of(RgbFilter.createInvertedFilter()),
                SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS),
            clippedVideo(
                MP4_ADVANCED_ASSET.uri, NO_EFFECT, SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS));

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, getLinearColorSpaceTransformer())
            .build()
            .run(testId, composition);

    assertThat(new File(result.filePath).length()).isGreaterThan(0);
    assertBitmapsMatchExpectedAndSave(
        extractBitmapsFromVideo(context, checkNotNull(result.filePath)), testId);
  }

  @Test
  public void export_withBt601VideoAndBt709ImageMediaItems() throws Exception {
    assumeFalse("OpenGL pipeline doesn't convert color yet.", useDefaultGlFrameProcessor);
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ADVANCED_ASSET.videoFormat,
        /* outputFormat= */ MP4_ADVANCED_ASSET.videoFormat);
    assumeFormatsSupported(
        context, testId, /* inputFormat= */ BT601_MOV_ASSET.videoFormat, /* outputFormat= */ null);
    Composition composition =
        createVideoOnlyComposition(
            Presentation.createForHeight(EXPORT_HEIGHT),
            clippedVideo(
                BT601_MOV_ASSET.uri,
                ImmutableList.of(RgbFilter.createInvertedFilter()),
                SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS),
            oneFrameFromImage(JPG_ASSET.uri, NO_EFFECT));

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, getLinearColorSpaceTransformer())
            .build()
            .run(testId, composition);

    assertThat(new File(result.filePath).length()).isGreaterThan(0);
    assertBitmapsMatchExpectedAndSave(
        extractBitmapsFromVideo(context, checkNotNull(result.filePath)), testId);
  }

  private Transformer getLinearColorSpaceTransformer() {
    // Use linear color space for grayscale effects.
    Transformer.Builder builder = createTransformerBuilder();
    if (!useDefaultGlFrameProcessor) {
      builder.setVideoFrameProcessorFactory(
          new DefaultVideoFrameProcessor.Factory.Builder()
              .setSdrWorkingColorSpace(DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_LINEAR)
              .build());
    }
    return builder.build();
  }

  private static OverlayEffect createOverlayEffect() throws IOException {
    return new OverlayEffect(
        ImmutableList.of(
            BitmapOverlay.createStaticBitmapOverlay(readBitmap(OVERLAY_PNG_ASSET_PATH))));
  }

  private void assertBitmapsMatchExpectedAndSave(List<Bitmap> actualBitmaps, String testId)
      throws IOException {
    // TODO: b/530130453 - Using a higher pixel difference because DefaultGlFrameProcessor doesn't
    //  yet process frames in linear colors.
    float maxPixelDifference =
        useDefaultGlFrameProcessor ? 20.0f : MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE_LUMA;
    SequenceEffectTestUtil.assertBitmapsMatchExpectedAndSave(
        actualBitmaps, testId, maxPixelDifference);
  }

  private boolean shouldUseDefaultGlFrameProcessor() {
    return useDefaultGlFrameProcessor && SDK_INT >= 28;
  }
}
