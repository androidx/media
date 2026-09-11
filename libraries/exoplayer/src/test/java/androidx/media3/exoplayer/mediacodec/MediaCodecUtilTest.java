/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.media.MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd30;
import static android.media.MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtr;
import static android.media.MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt;
import static android.media.MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51;
import static android.media.MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4;
import static android.media.MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41;
import static android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain;
import static android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10;
import static androidx.media3.exoplayer.mediacodec.MediaCodecUtil.createCodecProfileLevel;
import static com.google.common.truth.Truth.assertThat;

import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.CodecProfileLevel;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.CodecSpecificDataUtil.MediaCodecProfileAndLevel;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowBuild;

/** Unit tests for {@link MediaCodecUtil}. */
@RunWith(AndroidJUnit4.class)
public final class MediaCodecUtilTest {

  private static final byte[] CSD0 =
      new byte[] {
        // Start code
        0,
        0,
        0,
        1,
        // VPS
        64,
        1,
        12,
        17,
        -1,
        -1,
        1,
        96,
        0,
        0,
        3,
        0,
        -80,
        0,
        0,
        3,
        0,
        0,
        3,
        0,
        120,
        21,
        -63,
        91,
        0,
        32,
        0,
        40,
        36,
        -63,
        -105,
        6,
        2,
        0,
        0,
        3,
        0,
        -65,
        -128,
        0,
        0,
        3,
        0,
        0,
        120,
        -115,
        7,
        -128,
        4,
        64,
        -96,
        30,
        92,
        82,
        -65,
        72,
        // Start code
        0,
        0,
        0,
        1,
        // SPS for layer 0
        66,
        1,
        1,
        1,
        96,
        0,
        0,
        3,
        0,
        -80,
        0,
        0,
        3,
        0,
        0,
        3,
        0,
        120,
        -96,
        3,
        -64,
        -128,
        17,
        7,
        -53,
        -120,
        21,
        -18,
        69,
        -107,
        77,
        64,
        64,
        64,
        64,
        32,
        // Start code
        0,
        0,
        0,
        1,
        // PPS for layer 0
        68,
        1,
        -64,
        44,
        -68,
        20,
        -55,
        // Start code
        0,
        0,
        0,
        1,
        // SEI
        78,
        1,
        -80,
        4,
        4,
        10,
        -128,
        32,
        -128
      };

  private static final byte[] CSD1 =
      new byte[] {
        // Start code
        0,
        0,
        0,
        1,
        // SPS for layer 1
        66,
        9,
        14,
        -126,
        46,
        69,
        -118,
        -96,
        5,
        1,
        // Start code
        0,
        0,
        0,
        1,
        // PPS for layer 1
        68,
        9,
        72,
        2,
        -53,
        -63,
        77,
        -88,
        5
      };

  @Test
  public void getHevcBaseLayerCodecProfileAndLevel_handlesFallbackFromMvHevc() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_MV_HEVC)
            .setCodecs("hvc1.6.40.L120.BF.80")
            .setInitializationData(ImmutableList.of(CSD0, CSD1))
            .build();
    assertHevcBaseLayerCodecProfileAndLevelForFormat(format, HEVCProfileMain, HEVCMainTierLevel4);
  }

  @Test
  public void getHevcBaseLayerCodecProfileAndLevel_rejectsFormatWithNoInitializationData() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_MV_HEVC)
            .setCodecs("hvc1.6.40.L120.BF.80")
            .build();
    assertThat(MediaCodecUtil.getHevcBaseLayerCodecProfileAndLevel(format)).isNull();
  }

  @Test
  public void getAlternativeCodecMimeType_withNonFallbackCompatibleFormat_returnsNull() {
    // Profile 10.0 (Full Range PQ) which does NOT allow fallback.
    Format formatDav1NoFallbackPossible =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dav1.10.01")
            .setColorInfo(
                new ColorInfo.Builder()
                    .setColorSpace(C.COLOR_SPACE_BT2020)
                    .setColorTransfer(C.COLOR_TRANSFER_ST2084)
                    .setColorRange(C.COLOR_RANGE_FULL)
                    .build())
            .build();
    // Profile 10.1 (Limited Range PQ) which allows fallback to AV1.
    Format formatDav1FallbackToAv1 =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dav1.10.01")
            .setColorInfo(
                new ColorInfo.Builder()
                    .setColorSpace(C.COLOR_SPACE_BT2020)
                    .setColorTransfer(C.COLOR_TRANSFER_ST2084)
                    .setColorRange(C.COLOR_RANGE_LIMITED)
                    .build())
            .build();

    assertThat(MediaCodecUtil.getAlternativeCodecMimeType(formatDav1NoFallbackPossible)).isNull();
    assertThat(MediaCodecUtil.getAlternativeCodecMimeType(formatDav1FallbackToAv1))
        .isEqualTo(MimeTypes.VIDEO_AV1);
  }

  @Test
  public void getAlternativeCodecMimeType_withEac3JocFormatOnNonGoogleDevice_returnsEac3() {
    ShadowBuild.setManufacturer("Samsung");
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC).build();
    assertThat(MediaCodecUtil.getAlternativeCodecMimeType(format)).isEqualTo(MimeTypes.AUDIO_E_AC3);
  }

  @Test
  public void getAlternativeCodecMimeType_withEac3JocFormatOnGoogleDevice_returnsNull() {
    ShadowBuild.setManufacturer("Google");
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC).build();
    assertThat(MediaCodecUtil.getAlternativeCodecMimeType(format)).isNull();
  }

  @Test
  public void
      getDecoderInfosSoftMatchFilteredByFormatSupport_withDecoderNotMatchingFormatSupport_excludesDecoder()
          throws Exception {
    Format formatDvProfile8 =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.08.01")
            .build();
    CodecCapabilities capabilitiesDolbyProfile4 =
        createCodecCapabilities(DolbyVisionProfileDvheDtr, DolbyVisionLevelFhd30);
    CodecCapabilities capabilitiesDolbyProfile8 =
        createCodecCapabilities(DolbyVisionProfileDvheSt, DolbyVisionLevelFhd30);
    MediaCodecInfo decoderProfile4 =
        MediaCodecInfo.newInstance(
            /* name= */ "dv-p4-codec",
            /* mimeType= */ MimeTypes.VIDEO_DOLBY_VISION,
            /* codecMimeType= */ MimeTypes.VIDEO_DOLBY_VISION,
            /* capabilities= */ capabilitiesDolbyProfile4,
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ false,
            /* forceDisableAdaptive= */ false,
            /* forceSecure= */ false);
    MediaCodecInfo decoderProfile8 =
        MediaCodecInfo.newInstance(
            /* name= */ "dv-p8-codec",
            /* mimeType= */ MimeTypes.VIDEO_DOLBY_VISION,
            /* codecMimeType= */ MimeTypes.VIDEO_DOLBY_VISION,
            /* capabilities= */ capabilitiesDolbyProfile8,
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ false,
            /* forceDisableAdaptive= */ false,
            /* forceSecure= */ false);
    MediaCodecSelector mediaCodecSelector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
          if (mimeType.equals(MimeTypes.VIDEO_DOLBY_VISION)) {
            return ImmutableList.of(decoderProfile4, decoderProfile8);
          }
          return ImmutableList.of();
        };

    List<MediaCodecInfo> decoderInfos =
        MediaCodecUtil.getDecoderInfosSoftMatchFilteredByFormatSupport(
            ApplicationProvider.getApplicationContext(),
            mediaCodecSelector,
            formatDvProfile8,
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);

    assertThat(decoderInfos).containsExactly(decoderProfile8);
  }

  @Test
  public void
      getAlternativeDecoderInfosFilteredByFormatSupport_withDecoderNotMatchingFormatSupport_excludesDecoder()
          throws Exception {
    Format formatDvProfile8 =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.08.01")
            .build();
    CodecCapabilities capabilitiesHevcMain =
        createCodecCapabilities(HEVCProfileMain, HEVCMainTierLevel41);
    CodecCapabilities capabilitiesHevcMain10 =
        createCodecCapabilities(HEVCProfileMain10, HEVCHighTierLevel51);
    MediaCodecInfo decoderHevcMain =
        MediaCodecInfo.newInstance(
            /* name= */ "hevc-main-codec",
            /* mimeType= */ MimeTypes.VIDEO_H265,
            /* codecMimeType= */ MimeTypes.VIDEO_H265,
            /* capabilities= */ capabilitiesHevcMain,
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ false,
            /* forceDisableAdaptive= */ false,
            /* forceSecure= */ false);
    MediaCodecInfo decoderHevcMain10 =
        MediaCodecInfo.newInstance(
            /* name= */ "hevc-main10-codec",
            /* mimeType= */ MimeTypes.VIDEO_H265,
            /* codecMimeType= */ MimeTypes.VIDEO_H265,
            /* capabilities= */ capabilitiesHevcMain10,
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ false,
            /* forceDisableAdaptive= */ false,
            /* forceSecure= */ false);
    MediaCodecSelector mediaCodecSelector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
          if (mimeType.equals(MimeTypes.VIDEO_H265)) {
            return ImmutableList.of(decoderHevcMain, decoderHevcMain10);
          }
          return ImmutableList.of();
        };

    List<MediaCodecInfo> decoderInfos =
        MediaCodecUtil.getAlternativeDecoderInfosFilteredByFormatSupport(
            ApplicationProvider.getApplicationContext(),
            mediaCodecSelector,
            formatDvProfile8,
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);

    assertThat(decoderInfos).containsExactly(decoderHevcMain10);
  }

  @Test
  public void
      getDecoderInfosSoftMatchFilteredByFormatSupport_withNoMatchingDecoders_fallsBackToUnfilteredList()
          throws Exception {
    Format formatDvProfile8 =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.08.01")
            .build();
    CodecCapabilities capabilitiesDvProfile4 =
        createCodecCapabilities(DolbyVisionProfileDvheDtr, DolbyVisionLevelFhd30);
    CodecCapabilities capabilitiesHevcMain =
        createCodecCapabilities(HEVCProfileMain, HEVCMainTierLevel41);
    MediaCodecInfo decoderProfile4 =
        MediaCodecInfo.newInstance(
            /* name= */ "dv-profile4-codec",
            /* mimeType= */ MimeTypes.VIDEO_DOLBY_VISION,
            /* codecMimeType= */ MimeTypes.VIDEO_DOLBY_VISION,
            /* capabilities= */ capabilitiesDvProfile4,
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ false,
            /* forceDisableAdaptive= */ false,
            /* forceSecure= */ false);
    MediaCodecInfo decoderHevcMain =
        MediaCodecInfo.newInstance(
            /* name= */ "hevc-main-codec",
            /* mimeType= */ MimeTypes.VIDEO_H265,
            /* codecMimeType= */ MimeTypes.VIDEO_H265,
            /* capabilities= */ capabilitiesHevcMain,
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ false,
            /* forceDisableAdaptive= */ false,
            /* forceSecure= */ false);
    MediaCodecSelector mediaCodecSelector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
          if (mimeType.equals(MimeTypes.VIDEO_DOLBY_VISION)) {
            return ImmutableList.of(decoderProfile4);
          }
          if (mimeType.equals(MimeTypes.VIDEO_H265)) {
            return ImmutableList.of(decoderHevcMain);
          }
          return ImmutableList.of();
        };

    List<MediaCodecInfo> decoderInfos =
        MediaCodecUtil.getDecoderInfosSoftMatchFilteredByFormatSupport(
            ApplicationProvider.getApplicationContext(),
            mediaCodecSelector,
            formatDvProfile8,
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);

    assertThat(decoderInfos).containsExactly(decoderProfile4, decoderHevcMain).inOrder();
  }

  private static CodecCapabilities createCodecCapabilities(int profile, int level) {
    CodecCapabilities capabilities = new CodecCapabilities();
    capabilities.profileLevels = new CodecProfileLevel[] {createCodecProfileLevel(profile, level)};
    return capabilities;
  }

  private static void assertHevcBaseLayerCodecProfileAndLevelForFormat(
      Format format, int profile, int level) {
    MediaCodecProfileAndLevel codecProfileAndLevel =
        MediaCodecUtil.getHevcBaseLayerCodecProfileAndLevel(format);
    assertThat(codecProfileAndLevel.isSupportableByMediaCodec()).isTrue();
    assertThat(codecProfileAndLevel.getProfile()).isEqualTo(profile);
    assertThat(codecProfileAndLevel.getLevel()).isEqualTo(level);
  }
}
