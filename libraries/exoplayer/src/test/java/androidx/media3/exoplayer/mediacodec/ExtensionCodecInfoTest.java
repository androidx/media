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
package androidx.media3.exoplayer.mediacodec;

import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_DRM_SESSION_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_INITIALIZATION_DATA_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_MIME_TYPE_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_REUSE_NOT_IMPLEMENTED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_NO;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link ExtensionCodecInfo}. */
@RunWith(AndroidJUnit4.class)
public final class ExtensionCodecInfoTest {

  private static final String CODEC_NAME = "c2.media3.test.decoder";
  private static final String MIME_TYPE = MimeTypes.VIDEO_AV1;

  private Context context;
  private ExtensionCodecInfo codecInfo;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    codecInfo = ExtensionCodecInfo.newInstance(CODEC_NAME, MIME_TYPE);
  }

  @Test
  public void newInstance_setsExpectedProperties() {
    Format format = new Format.Builder().setSampleMimeType(MIME_TYPE).build();

    ExtensionCodecInfo info = ExtensionCodecInfo.newInstance(CODEC_NAME, MIME_TYPE);

    assertThat(info.name).isEqualTo(CODEC_NAME);
    assertThat(info.mimeType).isEqualTo(MIME_TYPE);
    assertThat(info.codecMimeType).isEqualTo(MIME_TYPE);
    assertThat(info.hardwareAccelerated).isFalse();
    assertThat(info.softwareOnly).isTrue();
    assertThat(info.vendor).isFalse();
    assertThat(info.adaptive).isTrue();
    assertThat(info.tunneling).isFalse();
    assertThat(info.secure).isFalse();
    assertThat(info.detachedSurfaceSupported).isTrue();
    assertThat(info.isSeamlessAdaptationSupported(format)).isTrue();
  }

  @Test
  public void newInstance_audioMimeType_setsExpectedProperties() {
    Format xheAacFormat =
        new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setCodecs("mp4a.40.42").build();

    ExtensionCodecInfo info =
        ExtensionCodecInfo.newInstance("c2.media3.aac.decoder", MimeTypes.AUDIO_AAC);

    assertThat(info.name).isEqualTo("c2.media3.aac.decoder");
    assertThat(info.mimeType).isEqualTo(MimeTypes.AUDIO_AAC);
    assertThat(info.codecMimeType).isEqualTo(MimeTypes.AUDIO_AAC);
    assertThat(info.hardwareAccelerated).isFalse();
    assertThat(info.softwareOnly).isTrue();
    assertThat(info.vendor).isFalse();
    assertThat(info.adaptive).isFalse();
    assertThat(info.tunneling).isFalse();
    assertThat(info.secure).isFalse();
    assertThat(info.detachedSurfaceSupported).isFalse();
    assertThat(info.isSeamlessAdaptationSupported(xheAacFormat)).isFalse();
  }

  @Test
  public void isFormatSupported_matchingMimeType_returnsTrue() {
    Format format = new Format.Builder().setSampleMimeType(MIME_TYPE).build();

    boolean formatSupported = codecInfo.isFormatSupported(context, format);
    boolean functionallySupported = codecInfo.isFormatFunctionallySupported(context, format);

    assertThat(formatSupported).isTrue();
    assertThat(functionallySupported).isTrue();
  }

  @Test
  public void isFormatSupported_alternativeCodecMimeType_returnsTrue() {
    Format dolbyVisionAv1Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dav1.10.01")
            .build();

    boolean formatSupported = codecInfo.isFormatSupported(context, dolbyVisionAv1Format);
    boolean functionallySupported =
        codecInfo.isFormatFunctionallySupported(context, dolbyVisionAv1Format);

    assertThat(formatSupported).isTrue();
    assertThat(functionallySupported).isTrue();
  }

  @Test
  public void isFormatSupported_differentMimeType_returnsFalse() {
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build();

    boolean formatSupported = codecInfo.isFormatSupported(context, format);
    boolean functionallySupported = codecInfo.isFormatFunctionallySupported(context, format);

    assertThat(formatSupported).isFalse();
    assertThat(functionallySupported).isFalse();
  }

  @Test
  public void isFormatSupported_withEncryptedFormat_returnsFalse() {
    Format encryptedFormat =
        new Format.Builder()
            .setSampleMimeType(MIME_TYPE)
            .setCryptoType(C.CRYPTO_TYPE_FRAMEWORK)
            .build();

    boolean drmSupported = codecInfo.isFormatDrmSupported(encryptedFormat);
    boolean formatSupported = codecInfo.isFormatSupported(context, encryptedFormat);
    boolean functionallySupported =
        codecInfo.isFormatFunctionallySupported(context, encryptedFormat);

    assertThat(drmSupported).isFalse();
    assertThat(formatSupported).isFalse();
    assertThat(functionallySupported).isFalse();
  }

  @Test
  public void isFormatSupported_whenIsFormatDrmSupportedOverriddenForEncryptedFormat_returnsTrue() {
    ExtensionCodecInfo drmCapableCodecInfo =
        new ExtensionCodecInfo(CODEC_NAME, MIME_TYPE) {
          @Override
          public boolean isFormatDrmSupported(Format format) {
            return format.cryptoType == C.CRYPTO_TYPE_NONE
                || format.cryptoType == C.CRYPTO_TYPE_CUSTOM_BASE;
          }
        };
    Format customEncryptedFormat =
        new Format.Builder()
            .setSampleMimeType(MIME_TYPE)
            .setCryptoType(C.CRYPTO_TYPE_CUSTOM_BASE)
            .build();

    boolean drmSupported = drmCapableCodecInfo.isFormatDrmSupported(customEncryptedFormat);
    boolean formatSupported = drmCapableCodecInfo.isFormatSupported(context, customEncryptedFormat);

    assertThat(drmSupported).isTrue();
    assertThat(formatSupported).isTrue();
  }

  @Test
  public void canReuseCodec_sameMimeTypeDifferentResolution_returnsYesWithoutReconfiguration() {
    Format oldFormat =
        new Format.Builder().setSampleMimeType(MIME_TYPE).setWidth(1280).setHeight(720).build();
    Format newFormat =
        new Format.Builder().setSampleMimeType(MIME_TYPE).setWidth(1920).setHeight(1080).build();

    DecoderReuseEvaluation evaluation = codecInfo.canReuseCodec(oldFormat, newFormat);

    assertThat(evaluation.result).isEqualTo(REUSE_RESULT_YES_WITHOUT_RECONFIGURATION);
    assertThat(evaluation.discardReasons).isEqualTo(0);
  }

  @Test
  public void canReuseCodec_differentRotationDegrees_returnsYesWithoutReconfiguration() {
    Format oldFormat =
        new Format.Builder().setSampleMimeType(MIME_TYPE).setRotationDegrees(0).build();
    Format newFormat =
        new Format.Builder().setSampleMimeType(MIME_TYPE).setRotationDegrees(90).build();

    DecoderReuseEvaluation evaluation = codecInfo.canReuseCodec(oldFormat, newFormat);

    assertThat(evaluation.result).isEqualTo(REUSE_RESULT_YES_WITHOUT_RECONFIGURATION);
    assertThat(evaluation.discardReasons).isEqualTo(0);
  }

  @Test
  public void canReuseCodec_differentColorInfo_returnsYesWithoutReconfiguration() {
    ColorInfo hdr10ColorInfo =
        new ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .setColorTransfer(C.COLOR_TRANSFER_ST2084)
            .setLumaBitdepth(10)
            .setChromaBitdepth(10)
            .build();
    Format oldFormat =
        new Format.Builder()
            .setSampleMimeType(MIME_TYPE)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();
    Format newFormat =
        new Format.Builder().setSampleMimeType(MIME_TYPE).setColorInfo(hdr10ColorInfo).build();

    DecoderReuseEvaluation evaluation = codecInfo.canReuseCodec(oldFormat, newFormat);

    assertThat(evaluation.result).isEqualTo(REUSE_RESULT_YES_WITHOUT_RECONFIGURATION);
    assertThat(evaluation.discardReasons).isEqualTo(0);
  }

  @Test
  public void canReuseCodec_differentFrameRate_returnsYesWithoutReconfiguration() {
    Format oldFormat = new Format.Builder().setSampleMimeType(MIME_TYPE).setFrameRate(24f).build();
    Format newFormat = new Format.Builder().setSampleMimeType(MIME_TYPE).setFrameRate(60f).build();

    DecoderReuseEvaluation evaluation = codecInfo.canReuseCodec(oldFormat, newFormat);

    assertThat(evaluation.result).isEqualTo(REUSE_RESULT_YES_WITHOUT_RECONFIGURATION);
    assertThat(evaluation.discardReasons).isEqualTo(0);
  }

  @Test
  public void
      canReuseCodec_dolbyVisionAv1FallbackToDolbyVisionAv1Fallback_returnsYesWithoutReconfiguration() {
    Format oldFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dav1.10.01")
            .build();
    Format newFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dav1.10.08")
            .build();

    DecoderReuseEvaluation evaluation = codecInfo.canReuseCodec(oldFormat, newFormat);

    assertThat(evaluation.result).isEqualTo(REUSE_RESULT_YES_WITHOUT_RECONFIGURATION);
    assertThat(evaluation.discardReasons).isEqualTo(0);
  }

  @Test
  public void canReuseCodec_dolbyVisionAv1FallbackToDolbyVisionHevc_returnsNo() {
    Format oldFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dav1.10.01")
            .build();
    Format newFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.08.01")
            .build();

    DecoderReuseEvaluation evaluation = codecInfo.canReuseCodec(oldFormat, newFormat);

    assertThat(evaluation.result).isEqualTo(REUSE_RESULT_NO);
    assertThat(evaluation.discardReasons).isEqualTo(0);
  }

  @Test
  public void canReuseCodec_dolbyVisionAv1FallbackToDolbyVisionFullRange_returnsNo() {
    ColorInfo fullRangePqColorInfo =
        new ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorRange(C.COLOR_RANGE_FULL)
            .setColorTransfer(C.COLOR_TRANSFER_ST2084)
            .build();
    Format oldFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dav1.10.01")
            .build();
    Format newFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dav1.10.01")
            .setColorInfo(fullRangePqColorInfo)
            .build();

    DecoderReuseEvaluation evaluation = codecInfo.canReuseCodec(oldFormat, newFormat);

    assertThat(evaluation.result).isEqualTo(REUSE_RESULT_NO);
    assertThat(evaluation.discardReasons).isEqualTo(0);
  }

  @Test
  public void canReuseCodec_av1ToDolbyVisionAv1Fallback_returnsNoWithMimeTypeChanged() {
    Format oldFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setCodecs("av01.0.04M.10")
            .build();
    Format newFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dav1.10.01")
            .build();

    DecoderReuseEvaluation evaluation = codecInfo.canReuseCodec(oldFormat, newFormat);

    assertThat(evaluation.result).isEqualTo(REUSE_RESULT_NO);
    assertThat(evaluation.discardReasons).isEqualTo(DISCARD_REASON_MIME_TYPE_CHANGED);
  }

  @Test
  public void canReuseCodec_differentMimeType_returnsNoWithMimeTypeChanged() {
    Format oldFormat = new Format.Builder().setSampleMimeType(MIME_TYPE).build();
    Format newFormat = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build();

    DecoderReuseEvaluation evaluation = codecInfo.canReuseCodec(oldFormat, newFormat);

    assertThat(evaluation.result).isEqualTo(REUSE_RESULT_NO);
    assertThat(evaluation.discardReasons).isEqualTo(DISCARD_REASON_MIME_TYPE_CHANGED);
  }

  @Test
  public void canReuseCodec_differentInitializationData_returnsNoWithInitializationDataChanged() {
    Format oldFormat =
        new Format.Builder()
            .setSampleMimeType(MIME_TYPE)
            .setInitializationData(ImmutableList.of(new byte[] {1, 2, 3}))
            .build();
    Format newFormat =
        new Format.Builder()
            .setSampleMimeType(MIME_TYPE)
            .setInitializationData(ImmutableList.of(new byte[] {4, 5, 6}))
            .build();

    DecoderReuseEvaluation evaluation = codecInfo.canReuseCodec(oldFormat, newFormat);

    assertThat(evaluation.result).isEqualTo(REUSE_RESULT_NO);
    assertThat(evaluation.discardReasons).isEqualTo(DISCARD_REASON_INITIALIZATION_DATA_CHANGED);
  }

  @Test
  public void
      canReuseCodec_whenRequiresInitializationDataMatchReturnsFalse_returnsYesWithoutReconfiguration() {
    ExtensionCodecInfo inBandHeaderCodecInfo =
        new ExtensionCodecInfo(CODEC_NAME, MIME_TYPE) {
          @Override
          protected boolean requiresInitializationDataMatch() {
            return false;
          }
        };
    Format oldFormat =
        new Format.Builder()
            .setSampleMimeType(MIME_TYPE)
            .setInitializationData(ImmutableList.of(new byte[] {1, 2, 3}))
            .build();
    Format newFormat =
        new Format.Builder()
            .setSampleMimeType(MIME_TYPE)
            .setInitializationData(ImmutableList.of(new byte[] {4, 5, 6}))
            .build();

    DecoderReuseEvaluation evaluation = inBandHeaderCodecInfo.canReuseCodec(oldFormat, newFormat);

    assertThat(evaluation.result).isEqualTo(REUSE_RESULT_YES_WITHOUT_RECONFIGURATION);
    assertThat(evaluation.discardReasons).isEqualTo(0);
  }

  @Test
  public void canReuseCodec_withEncryptedNewFormat_returnsNoWithDrmSessionChanged() {
    Format oldFormat = new Format.Builder().setSampleMimeType(MIME_TYPE).build();
    Format newFormat =
        new Format.Builder()
            .setSampleMimeType(MIME_TYPE)
            .setCryptoType(C.CRYPTO_TYPE_FRAMEWORK)
            .build();

    DecoderReuseEvaluation evaluation = codecInfo.canReuseCodec(oldFormat, newFormat);

    assertThat(evaluation.result).isEqualTo(REUSE_RESULT_NO);
    assertThat(evaluation.discardReasons).isEqualTo(DISCARD_REASON_DRM_SESSION_CHANGED);
  }

  @Test
  public void canReuseCodec_withEncryptedOldFormat_returnsNoWithDrmSessionChanged() {
    Format oldFormat =
        new Format.Builder()
            .setSampleMimeType(MIME_TYPE)
            .setCryptoType(C.CRYPTO_TYPE_FRAMEWORK)
            .build();
    Format newFormat = new Format.Builder().setSampleMimeType(MIME_TYPE).build();

    DecoderReuseEvaluation evaluation = codecInfo.canReuseCodec(oldFormat, newFormat);

    assertThat(evaluation.result).isEqualTo(REUSE_RESULT_NO);
    assertThat(evaluation.discardReasons).isEqualTo(DISCARD_REASON_DRM_SESSION_CHANGED);
  }

  @Test
  public void canReuseCodec_withEncryptedOldAndNewFormats_returnsNoWithDrmSessionChanged() {
    Format oldFormat =
        new Format.Builder()
            .setSampleMimeType(MIME_TYPE)
            .setCryptoType(C.CRYPTO_TYPE_FRAMEWORK)
            .build();
    Format newFormat =
        new Format.Builder()
            .setSampleMimeType(MIME_TYPE)
            .setCryptoType(C.CRYPTO_TYPE_FRAMEWORK)
            .build();

    DecoderReuseEvaluation evaluation = codecInfo.canReuseCodec(oldFormat, newFormat);

    assertThat(evaluation.result).isEqualTo(REUSE_RESULT_NO);
    assertThat(evaluation.discardReasons).isEqualTo(DISCARD_REASON_DRM_SESSION_CHANGED);
  }

  @Test
  public void canReuseCodec_whenNewFormatNotSupportedBySubclass_returnsNo() {
    ExtensionCodecInfo customCodecInfo =
        new ExtensionCodecInfo(CODEC_NAME, MIME_TYPE) {
          @Override
          protected boolean isFormatSupported(Format format) {
            return super.isFormatSupported(format) && format.width <= 1920;
          }
        };
    Format oldFormat =
        new Format.Builder().setSampleMimeType(MIME_TYPE).setWidth(1920).setHeight(1080).build();
    Format unsupportedNewFormat =
        new Format.Builder().setSampleMimeType(MIME_TYPE).setWidth(3840).setHeight(2160).build();

    DecoderReuseEvaluation evaluation =
        customCodecInfo.canReuseCodec(oldFormat, unsupportedNewFormat);

    assertThat(evaluation.result).isEqualTo(REUSE_RESULT_NO);
    assertThat(evaluation.discardReasons).isEqualTo(0);
  }

  @Test
  public void canReuseCodec_audioMimeType_returnsNoWithReuseNotImplemented() {
    ExtensionCodecInfo audioCodecInfo =
        ExtensionCodecInfo.newInstance("c2.media3.opus.decoder", MimeTypes.AUDIO_OPUS);
    Format oldFormat = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_OPUS).build();
    Format newFormat = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_OPUS).build();

    DecoderReuseEvaluation evaluation = audioCodecInfo.canReuseCodec(oldFormat, newFormat);

    assertThat(evaluation.result).isEqualTo(REUSE_RESULT_NO);
    assertThat(evaluation.discardReasons).isEqualTo(DISCARD_REASON_REUSE_NOT_IMPLEMENTED);
  }

  @Test
  public void getMaxSupportedFrameRate_returnsRateUnset() {
    assertThat(codecInfo.getMaxSupportedFrameRate(/* width= */ 1920, /* height= */ 1080))
        .isEqualTo(C.RATE_UNSET);
  }

  @Test
  public void isFormatSupported_whenSubclassOverridesWithoutSuper_stillChecksDrmSupport() {
    ExtensionCodecInfo customCodecInfo =
        new ExtensionCodecInfo(CODEC_NAME, MIME_TYPE) {
          @Override
          protected boolean isFormatSupported(Format format) {
            return true;
          }
        };
    Format encryptedFormat =
        new Format.Builder()
            .setSampleMimeType(MIME_TYPE)
            .setCryptoType(C.CRYPTO_TYPE_FRAMEWORK)
            .build();

    boolean formatSupported = customCodecInfo.isFormatSupported(context, encryptedFormat);
    boolean functionallySupported =
        customCodecInfo.isFormatFunctionallySupported(context, encryptedFormat);

    assertThat(formatSupported).isFalse();
    assertThat(functionallySupported).isFalse();
  }
}
