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
import static androidx.media3.transformer.AndroidTestUtil.BT601_ASSET_FORMAT;
import static androidx.media3.transformer.AndroidTestUtil.BT601_ASSET_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.JPG_ASSET_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.JPG_PORTRAIT_ASSET_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_AV1_VIDEO_FORMAT;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_AV1_VIDEO_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_CHECKERBOARD_VIDEO_FORMAT;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_CHECKERBOARD_VIDEO_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_FORMAT;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S_FORMAT;
import static androidx.media3.transformer.AndroidTestUtil.MP4_PORTRAIT_ASSET_FORMAT;
import static androidx.media3.transformer.AndroidTestUtil.MP4_PORTRAIT_ASSET_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.assumeFormatsSupported;
import static androidx.media3.transformer.AndroidTestUtil.extractBitmapsFromVideo;
import static androidx.media3.transformer.SequenceEffectTestUtil.NO_EFFECT;
import static androidx.media3.transformer.SequenceEffectTestUtil.PSNR_THRESHOLD;
import static androidx.media3.transformer.SequenceEffectTestUtil.PSNR_THRESHOLD_HD;
import static androidx.media3.transformer.SequenceEffectTestUtil.SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS;
import static androidx.media3.transformer.SequenceEffectTestUtil.assertBitmapsMatchExpectedAndSave;
import static androidx.media3.transformer.SequenceEffectTestUtil.assertFirstFrameMatchesExpectedPsnrAndSave;
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
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Util;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.DebugTraceUtil;
import androidx.media3.effect.DefaultVideoFrameProcessor;
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

    assertThat(result.filePath).isNotNull();
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

      assertThat(checkNotNull(result).filePath).isNotNull();
      assertFirstFrameMatchesExpectedPsnrAndSave(
          context, testId, checkNotNull(result.filePath), PSNR_THRESHOLD_HD);
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

      assertThat(checkNotNull(result).filePath).isNotNull();
      assertFirstFrameMatchesExpectedPsnrAndSave(
          context, testId, checkNotNull(result.filePath), PSNR_THRESHOLD_HD);
    }
    assertThat(atLeastOneDecoderSucceeds).isTrue();
  }

  @Test
  public void export640x428_withAllAvailableDecoders_doesNotStretchOutputOnAny() throws Exception {
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ BT601_ASSET_FORMAT,
        /* outputFormat= */ BT601_ASSET_FORMAT);
    List<MediaCodecInfo> mediaCodecInfoList =
        MediaCodecSelector.DEFAULT.getDecoderInfos(
            checkNotNull(BT601_ASSET_FORMAT.sampleMimeType),
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);
    Composition composition =
        createComposition(
            /* presentation= */ null,
            clippedVideo(
                BT601_ASSET_URI_STRING, NO_EFFECT, /* endPositionMs= */ C.MILLIS_PER_SECOND / 4));

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

      assertThat(checkNotNull(result).filePath).isNotNull();
      assertFirstFrameMatchesExpectedPsnrAndSave(
          context, testId, checkNotNull(result.filePath), PSNR_THRESHOLD);
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

      assertThat(checkNotNull(result).filePath).isNotNull();
      assertFirstFrameMatchesExpectedPsnrAndSave(
          context, testId, checkNotNull(result.filePath), PSNR_THRESHOLD_HD);
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

      assertThat(checkNotNull(result).filePath).isNotNull();
      assertFirstFrameMatchesExpectedPsnrAndSave(
          context, testId, checkNotNull(result.filePath), PSNR_THRESHOLD);
    }
    assertThat(atLeastOneDecoderSucceeds).isTrue();

    String traceSummary = DebugTraceUtil.generateTraceSummary();
    assertThat(traceSummary.indexOf(EVENT_SURFACE_TEXTURE_TRANSFORM_FIX)).isNotEqualTo(-1);
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

    assertThat(result.filePath).isNotNull();
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

    assertThat(result.filePath).isNotNull();
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

    assertThat(result.filePath).isNotNull();
    assertBitmapsMatchExpectedAndSave(
        extractBitmapsFromVideo(context, checkNotNull(result.filePath)), testId);
  }

  @Test
  public void export_withBt601AndBt709MediaItems() throws Exception {
    assumeFormatsSupported(
        context, testId, /* inputFormat= */ MP4_ASSET_FORMAT, /* outputFormat= */ MP4_ASSET_FORMAT);
    assumeFormatsSupported(
        context, testId, /* inputFormat= */ BT601_ASSET_FORMAT, /* outputFormat= */ null);
    Composition composition =
        createComposition(
            Presentation.createForHeight(EXPORT_HEIGHT),
            clippedVideo(
                BT601_ASSET_URI_STRING,
                ImmutableList.of(RgbFilter.createInvertedFilter()),
                SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS),
            clippedVideo(MP4_ASSET_URI_STRING, NO_EFFECT, SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS));

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, getLinearColorSpaceTransformer())
            .build()
            .run(testId, composition);

    assertThat(result.filePath).isNotNull();
    assertBitmapsMatchExpectedAndSave(
        extractBitmapsFromVideo(context, checkNotNull(result.filePath)), testId);
  }

  @Test
  public void export_withBt601VideoAndBt709ImageMediaItems() throws Exception {
    assumeFormatsSupported(
        context, testId, /* inputFormat= */ MP4_ASSET_FORMAT, /* outputFormat= */ MP4_ASSET_FORMAT);
    assumeFormatsSupported(
        context, testId, /* inputFormat= */ BT601_ASSET_FORMAT, /* outputFormat= */ null);
    Composition composition =
        createComposition(
            Presentation.createForHeight(EXPORT_HEIGHT),
            clippedVideo(
                BT601_ASSET_URI_STRING,
                ImmutableList.of(RgbFilter.createInvertedFilter()),
                SINGLE_30_FPS_VIDEO_FRAME_THRESHOLD_MS),
            oneFrameFromImage(JPG_ASSET_URI_STRING, NO_EFFECT));

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, getLinearColorSpaceTransformer())
            .build()
            .run(testId, composition);

    assertThat(result.filePath).isNotNull();
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
