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
package androidx.media3.common.util;

import static androidx.media3.common.util.CodecSpecificDataUtil.getCodecProfileAndLevel;
import static androidx.media3.common.util.CodecSpecificDataUtil.getMediaCodecProfileAndLevel;
import static com.google.common.truth.Truth.assertThat;

import android.media.MediaCodecInfo;
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link CodecSpecificDataUtil}. */
@RunWith(AndroidJUnit4.class)
public class CodecSpecificDataUtilTest {

  @Test
  public void parseAlacAudioSpecificConfig() {
    byte[] alacSpecificConfig =
        new byte[] {
          0, 0, 16, 0, // frameLength
          0, // compatibleVersion
          16, // bitDepth
          40, 10, 14, // tuning parameters
          2, // numChannels = 2
          0, 0, // maxRun
          0, 0, 64, 4, // maxFrameBytes
          0, 46, -32, 0, // avgBitRate
          0, 1, 119, 0, // sampleRate = 96000
        };
    int[] parsedAlacConfig = CodecSpecificDataUtil.parseAlacAudioSpecificConfig(alacSpecificConfig);
    assertThat(parsedAlacConfig[0]).isEqualTo(96000);
    assertThat(parsedAlacConfig[1]).isEqualTo(2);
    assertThat(parsedAlacConfig[2]).isEqualTo(16);
  }

  @Test
  public void getCodecProfileAndLevel_handlesH263CodecString() {
    assertCodecProfileAndLevelForCodecsString(
        MimeTypes.VIDEO_H263,
        "s263.0.10",
        MediaCodecInfo.CodecProfileLevel.H263ProfileBaseline,
        MediaCodecInfo.CodecProfileLevel.H263Level10);
  }

  @Test
  public void getCodecProfileAndLevel_handlesVp9Profile1CodecString() {
    assertCodecProfileAndLevelForCodecsString(
        MimeTypes.VIDEO_VP9,
        "vp09.01.51",
        MediaCodecInfo.CodecProfileLevel.VP9Profile1,
        MediaCodecInfo.CodecProfileLevel.VP9Level51);
  }

  @Test
  public void getCodecProfileAndLevel_handlesVp9Profile2CodecString() {
    assertCodecProfileAndLevelForCodecsString(
        MimeTypes.VIDEO_VP9,
        "vp09.02.10",
        MediaCodecInfo.CodecProfileLevel.VP9Profile2,
        MediaCodecInfo.CodecProfileLevel.VP9Level1);
  }

  @Test
  public void getCodecProfileAndLevel_handlesFullVp9CodecString() {
    // Example from https://www.webmproject.org/vp9/mp4/#codecs-parameter-string.
    assertCodecProfileAndLevelForCodecsString(
        MimeTypes.VIDEO_VP9,
        "vp09.02.10.10.01.09.16.09.01",
        MediaCodecInfo.CodecProfileLevel.VP9Profile2,
        MediaCodecInfo.CodecProfileLevel.VP9Level1);
  }

  @Test
  public void getCodecProfileAndLevel_handlesDolbyVisionCodecString() {
    assertCodecProfileAndLevelForCodecsString(
        MimeTypes.VIDEO_DOLBY_VISION,
        "dvh1.05.05",
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn,
        MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd60);
  }

  @Test
  public void getCodecProfileAndLevel_handlesDolbyVisionProfile10CodecString() {
    assertCodecProfileAndLevelForCodecsString(
        MimeTypes.VIDEO_DOLBY_VISION,
        "dav1.10.09",
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvav110,
        MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd60);
  }

  @Test
  public void getCodecProfileAndLevel_handlesAv1ProfileMain8CodecString() {
    assertCodecProfileAndLevelForCodecsString(
        MimeTypes.VIDEO_AV1,
        "av01.0.10M.08",
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
        MediaCodecInfo.CodecProfileLevel.AV1Level42);
  }

  @Test
  public void getCodecProfileAndLevel_handlesAv1ProfileMain10CodecString() {
    assertCodecProfileAndLevelForCodecsString(
        MimeTypes.VIDEO_AV1,
        "av01.0.20M.10",
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
        MediaCodecInfo.CodecProfileLevel.AV1Level7);
  }

  @Test
  public void getCodecProfileAndLevel_handlesAv1ProfileMain10HDRWithHdrInfoSet() {
    ColorInfo colorInfo =
        new ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT709)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .setColorTransfer(C.COLOR_TRANSFER_SDR)
            .setHdrStaticInfo(new byte[] {1, 2, 3, 4, 5, 6, 7})
            .build();
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setCodecs("av01.0.21M.10")
            .setColorInfo(colorInfo)
            .build();
    assertCodecProfileAndLevelForFormat(
        format,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10,
        MediaCodecInfo.CodecProfileLevel.AV1Level71);
  }

  @Test
  public void getCodecProfileAndLevel_handlesAv1ProfileMain10HDRWithoutHdrInfoSet() {
    ColorInfo colorInfo =
        new ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT709)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .setColorTransfer(C.COLOR_TRANSFER_HLG)
            .build();
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setCodecs("av01.0.21M.10")
            .setColorInfo(colorInfo)
            .build();
    assertCodecProfileAndLevelForFormat(
        format,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10,
        MediaCodecInfo.CodecProfileLevel.AV1Level71);
  }

  @Test
  public void getMediaCodecProfileAndLevel_handlesAv1ProfileHigh() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setCodecs("av01.1.10M.8")
            .build();

    assertThat(getMediaCodecProfileAndLevel(format).isSupportableByMediaCodec()).isFalse();
  }

  @Test
  public void getCodecProfileAndLevel_handlesFullAv1CodecString() {
    // Example from https://aomediacodec.github.io/av1-isobmff/#codecsparam.
    assertCodecProfileAndLevelForCodecsString(
        MimeTypes.VIDEO_AV1,
        "av01.0.04M.10.0.112.09.16.09.0",
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10,
        MediaCodecInfo.CodecProfileLevel.AV1Level3);
  }

  @Test
  public void getCodecProfileAndLevel_rejectsNullCodecString() {
    Format format = new Format.Builder().setCodecs(null).build();
    assertThat(getCodecProfileAndLevel(format)).isNull();
  }

  @Test
  public void getCodecProfileAndLevel_rejectsEmptyCodecString() {
    Format format = new Format.Builder().setCodecs("").build();
    assertThat(getCodecProfileAndLevel(format)).isNull();
  }

  @Test
  public void buildApvCodecString_withValidApvSpecificConfig_returnsCorrectCodecString() {
    byte[] apvSpecificConfig =
        TestUtil.createByteArray(
            1, // configurationVersion
            1, // number_of_configuration_entry
            1, // pbu_type
            1, // number_of_frame_info
            0, // reserved_zero_6bits, color_description_present_flag(1 bit),
            // capture_time_distance_ignored(1 bit)
            33, // profile_idc
            150, // level_idc
            0, // band_idc
            0, // frame_width (4 bytes)
            0,
            2,
            255,
            0, // frame_height (4 bytes)
            0,
            1,
            224,
            34, // chroma_format_idc (4 bit) + bit_depth_minus8(4 bit)
            0 // capture_time_distance
            );

    String codecString = CodecSpecificDataUtil.buildApvCodecString(apvSpecificConfig);

    assertThat(codecString).isEqualTo("apv1.apvf33.apvl150.apvb0");
  }

  @Test
  public void
      getCodecProfileAndLevel_withApvProfile422_10CodecString_returnsCorrectProfileAndLevel() {
    assertCodecProfileAndLevelForCodecsString(
        MimeTypes.VIDEO_APV,
        "apv1.apvf33.apvl30.apvb1",
        MediaCodecInfo.CodecProfileLevel.APVProfile422_10,
        MediaCodecInfo.CodecProfileLevel.APVLevel1Band1);
  }

  @Test
  public void
      getCodecProfileAndLevel_withApvProfile422_10HDR10PlusCodecString_returnsCorrectProfileAndLevel() {
    assertCodecProfileAndLevelForCodecsString(
        MimeTypes.VIDEO_APV,
        "apv1.apvf44.apvl60.apvb2",
        MediaCodecInfo.CodecProfileLevel.APVProfile422_10HDR10Plus,
        MediaCodecInfo.CodecProfileLevel.APVLevel2Band2);
  }

  @Test
  public void getCodecProfileAndLevel_handlesIamfCodecString_forSimpleProfileOpus() {
    assertCodecProfileAndLevelForCodecsString(
        MimeTypes.AUDIO_IAMF,
        "iamf.000.000.Opus",
        MediaCodecInfo.CodecProfileLevel.IAMFProfileSimpleOpus,
        0);
  }

  @Test
  public void getCodecProfileAndLevel_handlesIamfCodecString_forSimpleProfileAac() {
    assertCodecProfileAndLevelForCodecsString(
        MimeTypes.AUDIO_IAMF,
        "iamf.000.000.mp4a.40.2",
        MediaCodecInfo.CodecProfileLevel.IAMFProfileSimpleAac,
        0);
  }

  @Test
  public void getCodecProfileAndLevel_handlesIamfCodecString_forSimpleProfileFlac() {
    assertCodecProfileAndLevelForCodecsString(
        MimeTypes.AUDIO_IAMF,
        "iamf.000.000.fLaC",
        MediaCodecInfo.CodecProfileLevel.IAMFProfileSimpleFlac,
        0);
  }

  @Test
  public void getCodecProfileAndLevel_handlesIamfCodecString_forSimpleProfilePcm() {
    assertCodecProfileAndLevelForCodecsString(
        MimeTypes.AUDIO_IAMF,
        "iamf.000.000.ipcm",
        MediaCodecInfo.CodecProfileLevel.IAMFProfileSimplePcm,
        0);
  }

  @Test
  public void getCodecProfileAndLevel_handlesIamfCodecString_forBaseProfileOpus() {
    assertCodecProfileAndLevelForCodecsString(
        MimeTypes.AUDIO_IAMF,
        "iamf.001.000.Opus",
        MediaCodecInfo.CodecProfileLevel.IAMFProfileBaseOpus,
        0);
  }

  @Test
  public void getCodecProfileAndLevel_handlesIamfCodecString_forBaseProfileAac() {
    assertCodecProfileAndLevelForCodecsString(
        MimeTypes.AUDIO_IAMF,
        "iamf.001.000.mp4a.40.2",
        MediaCodecInfo.CodecProfileLevel.IAMFProfileBaseAac,
        0);
  }

  @Test
  public void getCodecProfileAndLevel_handlesIamfCodecString_forBaseProfileFlac() {
    assertCodecProfileAndLevelForCodecsString(
        MimeTypes.AUDIO_IAMF,
        "iamf.001.000.fLaC",
        MediaCodecInfo.CodecProfileLevel.IAMFProfileBaseFlac,
        0);
  }

  @Test
  public void getCodecProfileAndLevel_handlesIamfCodecString_forBaseProfilePcm() {
    assertCodecProfileAndLevelForCodecsString(
        MimeTypes.AUDIO_IAMF,
        "iamf.001.000.ipcm",
        MediaCodecInfo.CodecProfileLevel.IAMFProfileBasePcm,
        0);
  }

  @Test
  public void getMediaCodecProfileAndLevel_mvHevcWithNoMatchingMediaCodecConstant_unsupportable() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_MV_HEVC)
            .setCodecs("hvc1.6.40.L120.BF.80")
            .build();
    assertThat(getMediaCodecProfileAndLevel(format).isSupportableByMediaCodec()).isFalse();
  }

  @Test
  public void
      getDolbyVisionBaseLayerMimeType_withNonFallbackCompatibleFormat_returnsBaseEncoding() {
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

    assertThat(CodecSpecificDataUtil.getDolbyVisionBaseLayerMimeType(formatDav1NoFallbackPossible))
        .isEqualTo(MimeTypes.VIDEO_AV1);
    assertThat(CodecSpecificDataUtil.getDolbyVisionBaseLayerMimeType(formatDav1FallbackToAv1))
        .isEqualTo(MimeTypes.VIDEO_AV1);
  }

  private static void assertCodecProfileAndLevelForCodecsString(
      String sampleMimeType, String codecs, int profile, int level) {
    Format format =
        new Format.Builder().setSampleMimeType(sampleMimeType).setCodecs(codecs).build();
    assertCodecProfileAndLevelForFormat(format, profile, level);
  }

  private static void assertCodecProfileAndLevelForFormat(Format format, int profile, int level) {
    @Nullable Pair<Integer, Integer> codecProfileAndLevel = getCodecProfileAndLevel(format);
    assertThat(codecProfileAndLevel).isNotNull();
    assertThat(codecProfileAndLevel.first).isEqualTo(profile);
    assertThat(codecProfileAndLevel.second).isEqualTo(level);
  }
}
