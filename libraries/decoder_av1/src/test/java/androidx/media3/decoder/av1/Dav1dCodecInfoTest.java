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
package androidx.media3.decoder.av1;

import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameter;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** Unit tests for {@link Dav1dCodecInfo}. */
@RunWith(RobolectricTestParameterInjector.class)
public final class Dav1dCodecInfoTest {

  private Context context;
  private Dav1dCodecInfo codecInfo;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    codecInfo = new Dav1dCodecInfo();
  }

  @Test
  public void constructor_createsInstanceWithExpectedProperties() {
    Dav1dCodecInfo result = new Dav1dCodecInfo();

    assertThat(result.name).isEqualTo(Dav1dCodecInfo.NAME);
    assertThat(result.mimeType).isEqualTo(MimeTypes.VIDEO_AV1);
    assertThat(result.codecMimeType).isEqualTo(MimeTypes.VIDEO_AV1);
  }

  @Test
  public void isFormatSupported_withoutCodecs_returnsTrue() {
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).build();

    boolean isSupported = codecInfo.isFormatSupported(context, format);

    assertThat(isSupported).isTrue();
  }

  @Test
  public void isFormatSupported_withSupportedMainProfileAv1_returnsTrue(
      @TestParameter({
            "av01.0.04M.08", // 8-bit standard level
            "av01.0.04M.10", // 10-bit standard level
            "av01.0.31M.08", // Max-parameters level 31 (not in Android CodecProfileLevel)
          })
          String codecs) {
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).setCodecs(codecs).build();

    boolean isSupported = codecInfo.isFormatSupported(context, format);

    assertThat(isSupported).isTrue();
  }

  @Test
  public void isFormatSupported_withSupportedProfile2MonochromeAv1_returnsTrue(
      @TestParameter({
            "av01.2.04M.08.1.110", // 8-bit monochrome
            "av01.2.04M.10.1.110", // 10-bit monochrome
            "av01.2.00M.08.1.110", // Min level 2.0 (0)
            "av01.2.23M.08.1.110", // Max numbered level 7.3 (23)
            "av01.2.31M.08.1.110", // Max-parameters level (31)
            "av01.2.04M.08.1.112", // Colocated "11x" subsampling
            "av01.2.04M.08.1.000", // "000" subsampling
            "av01.2.04M.08.1", // Omitted subsampling
          })
          String codecs) {
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).setCodecs(codecs).build();

    boolean isSupported = codecInfo.isFormatSupported(context, format);

    assertThat(isSupported).isTrue();
  }

  @Test
  public void isFormatSupported_withMalformedAv1CodecString_returnsTrue(
      @TestParameter({
            "av01", // Bare codec ID without profile/level
            "av01.0.04M", // Missing bit depth
            "av01.x.04M.08", // Non-numeric profile
          })
          String codecs) {
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).setCodecs(codecs).build();

    boolean isSupported = codecInfo.isFormatSupported(context, format);

    assertThat(isSupported).isTrue();
  }

  @Test
  public void isFormatSupported_withMultiCodecStringContainingSupportedAv1_returnsTrue() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setCodecs("opus, av01.0.31M.08")
            .build();

    boolean isSupported = codecInfo.isFormatSupported(context, format);

    assertThat(isSupported).isTrue();
  }

  @Test
  public void isFormatSupported_withDolbyVisionAv1Profile_returnsFalse() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dav1.10.09")
            .build();

    boolean isSupported = codecInfo.isFormatSupported(context, format);
    boolean isFunctionallySupported = codecInfo.isFormatFunctionallySupported(context, format);

    assertThat(isSupported).isFalse();
    assertThat(isFunctionallySupported).isFalse();
  }

  @Test
  public void isFormatSupported_withDolbyVisionNonAv1Profile_returnsFalse() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.05.09")
            .build();

    boolean isSupported = codecInfo.isFormatSupported(context, format);

    assertThat(isSupported).isFalse();
  }

  @Test
  public void isFormatSupported_withNonAv1MimeType_returnsFalse() {
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build();

    boolean isSupported = codecInfo.isFormatSupported(context, format);

    assertThat(isSupported).isFalse();
  }

  @Test
  public void isFormatSupported_withMismatchedVideoCodecsForAv1MimeType_returnsFalse(
      @TestParameter({
            "avc1.42E01E", // H.264 codec string on video/av01
            "dav1.10.09", // Dolby Vision codec string on video/av01
          })
          String codecs) {
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).setCodecs(codecs).build();

    boolean isSupported = codecInfo.isFormatSupported(context, format);

    assertThat(isSupported).isFalse();
  }

  @Test
  public void isFormatSupported_withUnsupportedAv1ProfileOrBitDepth_returnsFalse(
      @TestParameter({
            "av01.0.31M.06", // 6-bit
            "av01.0.31M.12", // 12-bit
            "av01.1.31M.08", // High Profile (profile 1, 4:4:4)
            "av01.1.04M.08.1.110", // High Profile with monochrome fields
            "av01.3.04M.08.1.110", // Reserved profile 3
            "opus, av01.2.19M.12", // Multi-codec string with unsupported 12-bit AV1
          })
          String codecs) {
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).setCodecs(codecs).build();

    boolean isSupported = codecInfo.isFormatSupported(context, format);

    assertThat(isSupported).isFalse();
  }

  @Test
  public void isFormatSupported_withUnsupportedAv1Level_returnsFalse(
      @TestParameter({
            "av01.2.-1M.08.1.110", // Negative level
            "av01.2.24M.08.1.110", // Reserved level 24
            "av01.2.32M.08.1.110", // Out-of-range level 32
          })
          String codecs) {
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).setCodecs(codecs).build();

    boolean isSupported = codecInfo.isFormatSupported(context, format);

    assertThat(isSupported).isFalse();
  }

  @Test
  public void isFormatSupported_withNonMonochromeOrInvalidProfile2Subsampling_returnsFalse(
      @TestParameter({
            "av01.2.04M.10", // Missing monochrome flag
            "av01.2.04M.08.0.110", // monochrome=0
            "av01.2.04M.08.1.100", // 4:2:2 subsampling
            "av01.2.04M.08.1.11", // Too-short subsampling
            "av01.2.04M.08.1.1100", // Too-long subsampling
          })
          String codecs) {
    Format format =
        new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).setCodecs(codecs).build();

    boolean isSupported = codecInfo.isFormatSupported(context, format);

    assertThat(isSupported).isFalse();
  }

  @Test
  public void
      canReuseCodec_withDifferentInitializationData_returnsReuseYesWithoutReconfiguration() {
    Format format1 =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setInitializationData(ImmutableList.of(new byte[] {1, 2, 3}))
            .build();
    Format format2 =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setInitializationData(ImmutableList.of(new byte[] {4, 5, 6}))
            .build();

    DecoderReuseEvaluation evaluation = codecInfo.canReuseCodec(format1, format2);

    assertThat(evaluation.result).isEqualTo(REUSE_RESULT_YES_WITHOUT_RECONFIGURATION);
    assertThat(evaluation.discardReasons).isEqualTo(0);
  }
}
