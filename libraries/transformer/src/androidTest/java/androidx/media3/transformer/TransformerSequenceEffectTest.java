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

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Util.SDK_INT;
import static androidx.media3.effect.DebugTraceUtil.EVENT_SURFACE_TEXTURE_TRANSFORM_FIX;
import static androidx.media3.test.utils.BitmapPixelTestUtil.readBitmap;
import static androidx.media3.transformer.AndroidTestUtil.BT601_MOV_ASSET_FORMAT;
import static androidx.media3.transformer.AndroidTestUtil.BT601_MOV_ASSET_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.JPG_ASSET_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.JPG_PORTRAIT_ASSET_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_AV1_VIDEO_FORMAT;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_AV1_VIDEO_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_CHECKERBOARD_VIDEO_FORMAT;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_CHECKERBOARD_VIDEO_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_FORMAT;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S_FORMAT;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_WITH_INCREASING_TIMESTAMPS_FORMAT;
import static androidx.media3.transformer.AndroidTestUtil.MP4_PORTRAIT_ASSET_FORMAT;
import static androidx.media3.transformer.AndroidTestUtil.MP4_PORTRAIT_ASSET_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.PNG_ASSET_LINES_1080P_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.assumeFormatsSupported;
import static androidx.media3.transformer.AndroidTestUtil.extractBitmapsFromVideo;
import static androidx.media3.transformer.SequenceEffectTestUtil.NO_EFFECT;
import static androidx.media3.transformer.SequenceEffectTestUtil.PSNR_THRESHOLD;
import static androidx.media3.transformer.SequenceEffectTestUtil.PSNR_THRESHOLD_HD;
import static androidx.media3.transformer.SequenceEffectTestUtil.SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS;
import static androidx.media3.transformer.SequenceEffectTestUtil.assertBitmapsMatchExpectedAndSave;
import static androidx.media3.transformer.SequenceEffectTestUtil.assertFramesMatchExpectedPsnrAndSave;
import static androidx.media3.transformer.SequenceEffectTestUtil.clippedVideo;
import static androidx.media3.transformer.SequenceEffectTestUtil.createComposition;
import static androidx.media3.transformer.SequenceEffectTestUtil.decoderProducesWashedOutColours;
import static androidx.media3.transformer.SequenceEffectTestUtil.oneFrameFromImage;
import static androidx.media3.transformer.SequenceEffectTestUtil.tryToExportCompositionWithDecoder;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeFalse;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Util;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.DebugTraceUtil;
import androidx.media3.effect.DefaultVideoFrameProcessor;
import androidx.media3.effect.LanczosResample;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.RgbFilter;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/**
 * Tests for using different {@linkplain Effect effects} for {@link MediaItem MediaItems} in one
 * {@link EditedMediaItemSequence}.
 */
@RunWith(AndroidJUnit4.class)
public final class TransformerSequenceEffectTest {

  private static final String OVERLAY_PNG_ASSET_PATH = "media/png/media3test.png";
  private static final int EXPORT_WIDTH = 360;
  private static final int EXPORT_HEIGHT = 240;

  private final Context context = ApplicationProvider.getApplicationContext();
  @Rule public final TestName testName = new TestName();

  private String testId;

  @Before
  public void setUpTestId() {
    testId = testName.getMethodName();
  }

  @After
  public void tearDown() {
    DebugTraceUtil.enableTracing = false;
  }

  @Test
  public void export_withNoCompositionPresentationAndWithPerMediaItemEffects() throws Exception {
    assumeFormatsSupported(
        context, testId, /* inputFormat= */ MP4_ASSET_FORMAT, /* outputFormat= */ MP4_ASSET_FORMAT);
    OverlayEffect overlayEffect = createOverlayEffect();
    Composition composition =
        createComposition(
            /* presentation= */ null,
            clippedVideo(
                MP4_ASSET_URI_STRING,
                ImmutableList.of(
                    Presentation.createForWidthAndHeight(
                        EXPORT_WIDTH, EXPORT_HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT)),
                SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS),
            oneFrameFromImage(
                JPG_ASSET_URI_STRING,
                ImmutableList.of(
                    new ScaleAndRotateTransformation.Builder().setRotationDegrees(72).build(),
                    overlayEffect)),
            oneFrameFromImage(JPG_ASSET_URI_STRING, NO_EFFECT),
            // Transition to a different aspect ratio.
            oneFrameFromImage(
                JPG_ASSET_URI_STRING,
                ImmutableList.of(
                    Presentation.createForWidthAndHeight(
                        EXPORT_WIDTH / 2, EXPORT_HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT),
                    new ScaleAndRotateTransformation.Builder().setRotationDegrees(90).build(),
                    overlayEffect)));

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, new Transformer.Builder(context).build())
            .build()
            .run(testId, composition);

    assertThat(new File(result.filePath).length()).isGreaterThan(0);
    assertBitmapsMatchExpectedAndSave(
        extractBitmapsFromVideo(context, checkNotNull(result.filePath)), testId);
  }

  @Test
  public void export1080x720_withAllAvailableDecoders_doesNotStretchOutputOnAny() throws Exception {
    assumeFormatsSupported(
        context, testId, /* inputFormat= */ MP4_ASSET_FORMAT, /* outputFormat= */ MP4_ASSET_FORMAT);
    List<MediaCodecInfo> mediaCodecInfoList =
        MediaCodecSelector.DEFAULT.getDecoderInfos(
            checkNotNull(MP4_ASSET_FORMAT.sampleMimeType),
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);
    Composition composition =
        createComposition(
            /* presentation= */ null,
            clippedVideo(
                MP4_ASSET_URI_STRING, NO_EFFECT, /* endPositionMs= */ C.MILLIS_PER_SECOND / 4));

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
        /* inputFormat= */ MP4_PORTRAIT_ASSET_FORMAT,
        /* outputFormat= */ MP4_PORTRAIT_ASSET_FORMAT);
    List<MediaCodecInfo> mediaCodecInfoList =
        MediaCodecSelector.DEFAULT.getDecoderInfos(
            checkNotNull(MP4_PORTRAIT_ASSET_FORMAT.sampleMimeType),
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);
    Composition composition =
        createComposition(
            /* presentation= */ null,
            clippedVideo(
                MP4_PORTRAIT_ASSET_URI_STRING,
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
          context, testId, checkNotNull(result.filePath), PSNR_THRESHOLD_HD, /* frameCount= */ 1);
    }
    assertThat(atLeastOneDecoderSucceeds).isTrue();
  }

  @Test
  public void export640x428_withAllAvailableDecoders_doesNotStretchOutputOnAny() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ BT601_MOV_ASSET_FORMAT,
        /* outputFormat= */ BT601_MOV_ASSET_FORMAT);
    List<MediaCodecInfo> mediaCodecInfoList =
        MediaCodecSelector.DEFAULT.getDecoderInfos(
            checkNotNull(BT601_MOV_ASSET_FORMAT.sampleMimeType),
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);
    Composition composition =
        createComposition(
            /* presentation= */ null,
            clippedVideo(
                BT601_MOV_ASSET_URI_STRING,
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
  public void export1080x720Av1_withAllAvailableDecoders_doesNotStretchOutputOnAny()
      throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_AV1_VIDEO_FORMAT,
        /* outputFormat= */ MP4_ASSET_AV1_VIDEO_FORMAT);
    List<MediaCodecInfo> mediaCodecInfoList =
        MediaCodecSelector.DEFAULT.getDecoderInfos(
            checkNotNull(MP4_ASSET_AV1_VIDEO_FORMAT.sampleMimeType),
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);
    Composition composition =
        createComposition(
            /* presentation= */ null,
            clippedVideo(
                MP4_ASSET_AV1_VIDEO_URI_STRING,
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
          context, testId, checkNotNull(result.filePath), PSNR_THRESHOLD_HD, /* frameCount= */ 1);
    }
    assertThat(atLeastOneDecoderSucceeds).isTrue();
  }

  @Test
  public void export854x356_withAllAvailableDecoders_doesNotStretchOutputOnAny() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_CHECKERBOARD_VIDEO_FORMAT,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S_FORMAT);
    List<MediaCodecInfo> mediaCodecInfoList =
        MediaCodecSelector.DEFAULT.getDecoderInfos(
            checkNotNull(MP4_ASSET_CHECKERBOARD_VIDEO_FORMAT.sampleMimeType),
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);
    Composition composition =
        createComposition(
            Presentation.createForWidthAndHeight(
                /* width= */ 320, /* height= */ 240, Presentation.LAYOUT_SCALE_TO_FIT),
            clippedVideo(
                MP4_ASSET_CHECKERBOARD_VIDEO_URI_STRING,
                NO_EFFECT,
                /* endPositionMs= */ C.MILLIS_PER_SECOND / 4));
    DebugTraceUtil.enableTracing = true;

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

    String traceSummary = DebugTraceUtil.generateTraceSummary();
    assertThat(traceSummary.indexOf(EVENT_SURFACE_TEXTURE_TRANSFORM_FIX)).isNotEqualTo(-1);
  }

  @Test
  public void export_image_samplesFromTextureCorrectly() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_FORMAT,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_FORMAT);
    Composition composition =
        createComposition(
            /* presentation= */ null,
            new EditedMediaItem.Builder(MediaItem.fromUri(PNG_ASSET_LINES_1080P_URI_STRING))
                .setFrameRate(30)
                .setDurationUs(C.MICROS_PER_SECOND / 4)
                .build());
    // Some devices need a very high bitrate to avoid encoding artifacts.
    int bitrate = 30_000_000;
    if (Ascii.equalsIgnoreCase(Util.MODEL, "mi a2 lite")
        || Ascii.equalsIgnoreCase(Util.MODEL, "redmi 8")
        || Ascii.equalsIgnoreCase(Util.MODEL, "sm-f711u1")
        || Ascii.equalsIgnoreCase(Util.MODEL, "sm-f916u1")
        || Ascii.equalsIgnoreCase(Util.MODEL, "sm-f926u1")
        || Ascii.equalsIgnoreCase(Util.MODEL, "sm-g981u1")
        || Ascii.equalsIgnoreCase(Util.MODEL, "tb-q706")) {
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
        new Transformer.Builder(context)
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
        /* psnrThreshold= */ 28.5f,
        /* frameCount= */ 2);
  }

  @Test
  public void export_imageWithLanczosResample_completesWithHighPsnr() throws Exception {
    int exportWidth = 640;
    int exportHeight = 240;
    Format outputFormat =
        MP4_ASSET_WITH_INCREASING_TIMESTAMPS_FORMAT
            .buildUpon()
            .setWidth(exportWidth)
            .setHeight(exportHeight)
            .build();
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS_FORMAT,
        outputFormat);
    Composition composition =
        createComposition(
            /* presentation= */ null,
            new EditedMediaItem.Builder(MediaItem.fromUri(PNG_ASSET_LINES_1080P_URI_STRING))
                .setFrameRate(30)
                .setDurationUs(C.MICROS_PER_SECOND / 4)
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
        new Transformer.Builder(context)
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
    // Reference: b/296225823#comment5
    assumeFalse(
        "Some older MediaTek encoders have a pixel alignment of 16, which results in a 360 pixel"
            + " width being re-scaled to 368.",
        SDK_INT == 27
            && (Ascii.equalsIgnoreCase(Util.MODEL, "redmi 6a")
                || Ascii.equalsIgnoreCase(Util.MODEL, "vivo 1820")));

    assumeFormatsSupported(
        context, testId, /* inputFormat= */ MP4_ASSET_FORMAT, /* outputFormat= */ MP4_ASSET_FORMAT);
    Composition composition =
        createComposition(
            Presentation.createForWidthAndHeight(
                EXPORT_WIDTH, /* height= */ EXPORT_WIDTH, Presentation.LAYOUT_SCALE_TO_FIT),
            oneFrameFromImage(
                JPG_ASSET_URI_STRING,
                ImmutableList.of(
                    new ScaleAndRotateTransformation.Builder().setRotationDegrees(90).build(),
                    Presentation.createForWidthAndHeight(
                        EXPORT_WIDTH, EXPORT_HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT))),
            oneFrameFromImage(JPG_ASSET_URI_STRING, NO_EFFECT),
            clippedVideo(
                MP4_ASSET_URI_STRING,
                ImmutableList.of(RgbFilter.createInvertedFilter()),
                SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS),
            clippedVideo(
                MP4_ASSET_URI_STRING,
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
        context, testId, /* inputFormat= */ MP4_ASSET_FORMAT, /* outputFormat= */ MP4_ASSET_FORMAT);
    Composition composition =
        createComposition(
            Presentation.createForHeight(EXPORT_HEIGHT),
            oneFrameFromImage(JPG_ASSET_URI_STRING, NO_EFFECT),
            clippedVideo(
                MP4_PORTRAIT_ASSET_URI_STRING, NO_EFFECT, SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS),
            clippedVideo(MP4_ASSET_URI_STRING, NO_EFFECT, SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS),
            oneFrameFromImage(JPG_PORTRAIT_ASSET_URI_STRING, NO_EFFECT));

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
    assumeFormatsSupported(
        context, testId, /* inputFormat= */ MP4_ASSET_FORMAT, /* outputFormat= */ MP4_ASSET_FORMAT);
    Composition composition =
        createComposition(
            Presentation.createForHeight(EXPORT_HEIGHT),
            clippedVideo(MP4_ASSET_URI_STRING, NO_EFFECT, SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS),
            clippedVideo(
                MP4_PORTRAIT_ASSET_URI_STRING,
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
    assumeFormatsSupported(
        context, testId, /* inputFormat= */ MP4_ASSET_FORMAT, /* outputFormat= */ MP4_ASSET_FORMAT);
    assumeFormatsSupported(
        context, testId, /* inputFormat= */ BT601_MOV_ASSET_FORMAT, /* outputFormat= */ null);
    Composition composition =
        createComposition(
            Presentation.createForHeight(EXPORT_HEIGHT),
            clippedVideo(
                BT601_MOV_ASSET_URI_STRING,
                ImmutableList.of(RgbFilter.createInvertedFilter()),
                SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS),
            clippedVideo(MP4_ASSET_URI_STRING, NO_EFFECT, SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS));

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
    assumeFormatsSupported(
        context, testId, /* inputFormat= */ MP4_ASSET_FORMAT, /* outputFormat= */ MP4_ASSET_FORMAT);
    assumeFormatsSupported(
        context, testId, /* inputFormat= */ BT601_MOV_ASSET_FORMAT, /* outputFormat= */ null);
    Composition composition =
        createComposition(
            Presentation.createForHeight(EXPORT_HEIGHT),
            clippedVideo(
                BT601_MOV_ASSET_URI_STRING,
                ImmutableList.of(RgbFilter.createInvertedFilter()),
                SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS),
            oneFrameFromImage(JPG_ASSET_URI_STRING, NO_EFFECT));

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
    return new Transformer.Builder(context)
        .setVideoFrameProcessorFactory(
            new DefaultVideoFrameProcessor.Factory.Builder()
                .setSdrWorkingColorSpace(DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_LINEAR)
                .build())
        .build();
  }

  private static OverlayEffect createOverlayEffect() throws IOException {
    return new OverlayEffect(
        ImmutableList.of(
            BitmapOverlay.createStaticBitmapOverlay(readBitmap(OVERLAY_PNG_ASSET_PATH))));
  }
}
