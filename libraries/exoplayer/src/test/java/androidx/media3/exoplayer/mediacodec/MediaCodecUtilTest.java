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
import static android.media.MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelHd24;
import static android.media.MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtr;
import static android.media.MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt;
import static android.media.MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51;
import static android.media.MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4;
import static android.media.MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41;
import static android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain;
import static android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10;
import static androidx.media3.exoplayer.mediacodec.MediaCodecUtil.createCodecProfileLevel;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.CodecProfileLevel;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DrmInitData;
import androidx.media3.common.Flags;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.CodecSpecificDataUtil.MediaCodecProfileAndLevel;
import androidx.media3.test.utils.Media3FlagsRule;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Objects;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowBuild;

/** Unit tests for {@link MediaCodecUtil}. */
@RunWith(AndroidJUnit4.class)
public final class MediaCodecUtilTest {

  @Rule public final Media3FlagsRule flagsRule = new Media3FlagsRule(this);

  @After
  public void tearDown() {
    MediaCodecUtil.clearDecoderInfoCache();
  }

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
    // Profile 7.0 (Full Range PQ) which does NOT allow fallback.
    Format formatDvheDtbFullRangePq =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.07.06")
            .setColorInfo(
                new ColorInfo.Builder()
                    .setColorSpace(C.COLOR_SPACE_BT2020)
                    .setColorTransfer(C.COLOR_TRANSFER_ST2084)
                    .setColorRange(C.COLOR_RANGE_FULL)
                    .build())
            .build();
    // Profile 7.0 with raw bitstream VUI (Full Range, Unspecified transfer) which does NOT allow
    // fallback.
    Format formatDvheDtbFullRangeUnspecifiedTransfer =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.07.06")
            .setColorInfo(
                new ColorInfo.Builder()
                    .setColorTransfer(Format.NO_VALUE)
                    .setColorRange(C.COLOR_RANGE_FULL)
                    .build())
            .build();
    // Profile 10.0 (Full Range PQ) which does NOT allow fallback.
    Format formatDav1FullRangePq =
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
    // Profile 10.0 with raw bitstream OBU (Full Range, Unspecified transfer) which does NOT allow
    // fallback.
    Format formatDav1FullRangeUnspecifiedTransfer =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dav1.10.01")
            .setColorInfo(
                new ColorInfo.Builder()
                    .setColorTransfer(Format.NO_VALUE)
                    .setColorRange(C.COLOR_RANGE_FULL)
                    .build())
            .build();

    String dvheDtbFullRangePqAlternative =
        MediaCodecUtil.getAlternativeCodecMimeType(formatDvheDtbFullRangePq);
    String dvheDtbFullRangeUnspecifiedTransferAlternative =
        MediaCodecUtil.getAlternativeCodecMimeType(formatDvheDtbFullRangeUnspecifiedTransfer);
    String dav1FullRangePqAlternative =
        MediaCodecUtil.getAlternativeCodecMimeType(formatDav1FullRangePq);
    String dav1FullRangeUnspecifiedTransferAlternative =
        MediaCodecUtil.getAlternativeCodecMimeType(formatDav1FullRangeUnspecifiedTransfer);

    assertThat(dvheDtbFullRangePqAlternative).isNull();
    assertThat(dvheDtbFullRangeUnspecifiedTransferAlternative).isNull();
    assertThat(dav1FullRangePqAlternative).isNull();
    assertThat(dav1FullRangeUnspecifiedTransferAlternative).isNull();
  }

  @Test
  public void
      getAlternativeCodecMimeType_withFallbackCompatibleDolbyVisionFormat_returnsAlternativeMimeType() {
    // Profile 7.6 (Limited Range PQ) which allows fallback to H.265.
    Format formatDvheDtbLimitedRangePq =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.07.06")
            .setColorInfo(
                new ColorInfo.Builder()
                    .setColorSpace(C.COLOR_SPACE_BT2020)
                    .setColorTransfer(C.COLOR_TRANSFER_ST2084)
                    .setColorRange(C.COLOR_RANGE_LIMITED)
                    .build())
            .build();
    Format formatDvheDtbWithoutColorInfo =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.07.06")
            .build();
    Format formatDvheDtbFullRangeHlg =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.07.06")
            .setColorInfo(
                new ColorInfo.Builder()
                    .setColorSpace(C.COLOR_SPACE_BT2020)
                    .setColorTransfer(C.COLOR_TRANSFER_HLG)
                    .setColorRange(C.COLOR_RANGE_FULL)
                    .build())
            .build();
    // Profile 10.1 (Limited Range PQ) which allows fallback to AV1.
    Format formatDav1LimitedRangePq =
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
    Format formatDav1WithoutColorInfo =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dav1.10.01")
            .build();
    Format formatDav1FullRangeHlg =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dav1.10.01")
            .setColorInfo(
                new ColorInfo.Builder()
                    .setColorSpace(C.COLOR_SPACE_BT2020)
                    .setColorTransfer(C.COLOR_TRANSFER_HLG)
                    .setColorRange(C.COLOR_RANGE_FULL)
                    .build())
            .build();

    String dvheDtbLimitedRangePqAlternative =
        MediaCodecUtil.getAlternativeCodecMimeType(formatDvheDtbLimitedRangePq);
    String dvheDtbWithoutColorInfoAlternative =
        MediaCodecUtil.getAlternativeCodecMimeType(formatDvheDtbWithoutColorInfo);
    String dvheDtbFullRangeHlgAlternative =
        MediaCodecUtil.getAlternativeCodecMimeType(formatDvheDtbFullRangeHlg);
    String dav1LimitedRangePqAlternative =
        MediaCodecUtil.getAlternativeCodecMimeType(formatDav1LimitedRangePq);
    String dav1WithoutColorInfoAlternative =
        MediaCodecUtil.getAlternativeCodecMimeType(formatDav1WithoutColorInfo);
    String dav1FullRangeHlgAlternative =
        MediaCodecUtil.getAlternativeCodecMimeType(formatDav1FullRangeHlg);

    assertThat(dvheDtbLimitedRangePqAlternative).isEqualTo(MimeTypes.VIDEO_H265);
    assertThat(dvheDtbWithoutColorInfoAlternative).isEqualTo(MimeTypes.VIDEO_H265);
    assertThat(dvheDtbFullRangeHlgAlternative).isEqualTo(MimeTypes.VIDEO_H265);
    assertThat(dav1LimitedRangePqAlternative).isEqualTo(MimeTypes.VIDEO_AV1);
    assertThat(dav1WithoutColorInfoAlternative).isEqualTo(MimeTypes.VIDEO_AV1);
    assertThat(dav1FullRangeHlgAlternative).isEqualTo(MimeTypes.VIDEO_AV1);
  }

  @Test
  public void getAlternativeCodecMimeType_dolbyVisionProfile20_returnsNull() {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvh1.20.01")
            .build();

    assertThat(MediaCodecUtil.getAlternativeCodecMimeType(format)).isNull();
  }

  @Test
  public void getAlternativeCodecMimeType_dolbyVisionWithNullCodecs_returnsNull() {
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).build();

    assertThat(MediaCodecUtil.getAlternativeCodecMimeType(format)).isNull();
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
            /* vendor= */ false);
    MediaCodecInfo decoderProfile8 =
        MediaCodecInfo.newInstance(
            /* name= */ "dv-p8-codec",
            /* mimeType= */ MimeTypes.VIDEO_DOLBY_VISION,
            /* codecMimeType= */ MimeTypes.VIDEO_DOLBY_VISION,
            /* capabilities= */ capabilitiesDolbyProfile8,
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ false);
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
            /* vendor= */ false);
    MediaCodecInfo decoderHevcMain10 =
        MediaCodecInfo.newInstance(
            /* name= */ "hevc-main10-codec",
            /* mimeType= */ MimeTypes.VIDEO_H265,
            /* codecMimeType= */ MimeTypes.VIDEO_H265,
            /* capabilities= */ capabilitiesHevcMain10,
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ false);
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
      getDecoderInfosSoftMatchFilteredByFormatSupport_withNoFullyMatchingDecoders_fallsBackToFunctionallySupportedAndAlternativeDecoders()
          throws Exception {
    Format formatDvProfile8LevelFhd30 =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.08.04")
            .build();
    CodecCapabilities capabilitiesDvProfile4 =
        createCodecCapabilities(DolbyVisionProfileDvheDtr, DolbyVisionLevelFhd30);
    CodecCapabilities capabilitiesDvProfile8LowLevel =
        createCodecCapabilities(DolbyVisionProfileDvheSt, DolbyVisionLevelHd24);
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
            /* vendor= */ false);
    MediaCodecInfo decoderProfile8LowLevel =
        MediaCodecInfo.newInstance(
            /* name= */ "dv-profile8-low-level-codec",
            /* mimeType= */ MimeTypes.VIDEO_DOLBY_VISION,
            /* codecMimeType= */ MimeTypes.VIDEO_DOLBY_VISION,
            /* capabilities= */ capabilitiesDvProfile8LowLevel,
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ false);
    MediaCodecInfo decoderHevcMain =
        MediaCodecInfo.newInstance(
            /* name= */ "hevc-main-codec",
            /* mimeType= */ MimeTypes.VIDEO_H265,
            /* codecMimeType= */ MimeTypes.VIDEO_H265,
            /* capabilities= */ capabilitiesHevcMain,
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ false);
    MediaCodecSelector mediaCodecSelector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
          if (mimeType.equals(MimeTypes.VIDEO_DOLBY_VISION)) {
            return ImmutableList.of(decoderProfile4, decoderProfile8LowLevel);
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
            formatDvProfile8LevelFhd30,
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);

    assertThat(decoderInfos).containsExactly(decoderProfile8LowLevel, decoderHevcMain).inOrder();
  }

  @Test
  public void
      getDecoderInfosSoftMatchFilteredByFormatSupport_withOnlyIncompatibleProfileAndNoAlternative_returnsEmptyList()
          throws Exception {
    Format formatDvProfile5 =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvhe.05.01")
            .build();
    CodecCapabilities capabilitiesDvProfile4 =
        createCodecCapabilities(DolbyVisionProfileDvheDtr, DolbyVisionLevelFhd30);
    MediaCodecInfo decoderProfile4 =
        MediaCodecInfo.newInstance(
            /* name= */ "dv-profile4-codec",
            /* mimeType= */ MimeTypes.VIDEO_DOLBY_VISION,
            /* codecMimeType= */ MimeTypes.VIDEO_DOLBY_VISION,
            /* capabilities= */ capabilitiesDvProfile4,
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ false);
    MediaCodecSelector mediaCodecSelector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) -> {
          if (mimeType.equals(MimeTypes.VIDEO_DOLBY_VISION)) {
            return ImmutableList.of(decoderProfile4);
          }
          return ImmutableList.of();
        };

    List<MediaCodecInfo> decoderInfos =
        MediaCodecUtil.getDecoderInfosSoftMatchFilteredByFormatSupport(
            ApplicationProvider.getApplicationContext(),
            mediaCodecSelector,
            formatDvProfile5,
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);

    assertThat(decoderInfos).isEmpty();
  }

  @Test
  public void
      getDecoderInfosSoftMatch_withEncryptedFormat_filtersOnlyExtensionDecodersNotSupportingDrm()
          throws Exception {
    Format encryptedAv1Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setCryptoType(C.CRYPTO_TYPE_CUSTOM_BASE)
            .build();
    ExtensionCodecInfo defaultExtensionInfo =
        ExtensionCodecInfo.newInstance(
            /* name= */ "c2.media3.av1.default.decoder", MimeTypes.VIDEO_AV1);
    ExtensionCodecInfo drmCapableExtensionInfo =
        new ExtensionCodecInfo(/* name= */ "c2.media3.av1.drm.decoder", MimeTypes.VIDEO_AV1) {
          @Override
          public boolean isFormatDrmSupported(Format format) {
            return format.cryptoType == C.CRYPTO_TYPE_CUSTOM_BASE;
          }
        };
    MediaCodecSelector mediaCodecSelector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
            ImmutableList.of(defaultExtensionInfo, drmCapableExtensionInfo);

    List<MediaCodecInfo> decoderInfos =
        MediaCodecUtil.getDecoderInfosSoftMatch(
            mediaCodecSelector,
            encryptedAv1Format,
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);

    assertThat(decoderInfos).containsExactly(drmCapableExtensionInfo);
  }

  @Test
  public void getExtensionDecoderInfos_forNullMimeType_returnsEmpty() {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);

    List<MediaCodecInfo> decoders =
        MediaCodecUtil.getExtensionDecoderInfos(
            /* mimeType= */ null,
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);

    assertThat(decoders).isEmpty();
  }

  @Test
  public void getDecoderInfosSoftMatch_withDrmInitDataOnly_excludesExtensionDecoders()
      throws Exception {
    MediaCodecInfo platformCodecInfo =
        MediaCodecInfo.newInstance(
            /* name= */ "c2.android.av1.decoder",
            /* mimeType= */ MimeTypes.VIDEO_AV1,
            /* codecMimeType= */ MimeTypes.VIDEO_AV1,
            /* capabilities= */ null,
            /* hardwareAccelerated= */ false,
            /* softwareOnly= */ true,
            /* vendor= */ false);
    ExtensionCodecInfo extensionCodecInfo =
        ExtensionCodecInfo.newInstance(
            /* name= */ "c2.media3.av1.dav1d.decoder", MimeTypes.VIDEO_AV1);
    MediaCodecSelector selector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
            ImmutableList.of(platformCodecInfo, extensionCodecInfo);
    Format drmInitDataFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setDrmInitData(
                new DrmInitData(
                    new DrmInitData.SchemeData(
                        C.WIDEVINE_UUID, MimeTypes.VIDEO_MP4, new byte[] {1})))
            .build();

    List<MediaCodecInfo> decoderInfos =
        MediaCodecUtil.getDecoderInfosSoftMatch(
            selector,
            drmInitDataFormat,
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);

    assertThat(decoderInfos).containsExactly(platformCodecInfo);
  }

  @Test
  public void getDecoderInfosSoftMatch_withUnencryptedFormat_includesExtensionDecoders()
      throws Exception {
    MediaCodecInfo platformCodecInfo =
        MediaCodecInfo.newInstance(
            /* name= */ "c2.android.av1.decoder",
            /* mimeType= */ MimeTypes.VIDEO_AV1,
            /* codecMimeType= */ MimeTypes.VIDEO_AV1,
            /* capabilities= */ null,
            /* hardwareAccelerated= */ false,
            /* softwareOnly= */ true,
            /* vendor= */ false);
    ExtensionCodecInfo extensionCodecInfo =
        ExtensionCodecInfo.newInstance(
            /* name= */ "c2.media3.av1.dav1d.decoder", MimeTypes.VIDEO_AV1);
    MediaCodecSelector selector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
            ImmutableList.of(platformCodecInfo, extensionCodecInfo);
    Format unencryptedFormat = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_AV1).build();

    List<MediaCodecInfo> decoderInfos =
        MediaCodecUtil.getDecoderInfosSoftMatch(
            selector,
            unencryptedFormat,
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);

    assertThat(decoderInfos).containsExactly(platformCodecInfo, extensionCodecInfo).inOrder();
  }

  @Test
  public void getAlternativeDecoderInfos_withEncryptedFormat_excludesExtensionDecoders()
      throws Exception {
    MediaCodecInfo platformCodecInfo =
        MediaCodecInfo.newInstance(
            /* name= */ "c2.android.av1.decoder",
            /* mimeType= */ MimeTypes.VIDEO_AV1,
            /* codecMimeType= */ MimeTypes.VIDEO_AV1,
            /* capabilities= */ null,
            /* hardwareAccelerated= */ false,
            /* softwareOnly= */ true,
            /* vendor= */ false);
    ExtensionCodecInfo extensionCodecInfo =
        ExtensionCodecInfo.newInstance(
            /* name= */ "c2.media3.av1.dav1d.decoder", MimeTypes.VIDEO_AV1);
    MediaCodecSelector selector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
            Objects.equals(mimeType, MimeTypes.VIDEO_AV1)
                ? ImmutableList.of(platformCodecInfo, extensionCodecInfo)
                : ImmutableList.of();
    Format encryptedDolbyVisionAv1Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dav1.10.01")
            .setCryptoType(C.CRYPTO_TYPE_FRAMEWORK)
            .build();

    List<MediaCodecInfo> decoderInfos =
        MediaCodecUtil.getAlternativeDecoderInfos(
            selector,
            encryptedDolbyVisionAv1Format,
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);

    assertThat(decoderInfos).containsExactly(platformCodecInfo);
  }

  @Test
  public void getAlternativeDecoderInfos_withUnsupportedExtensionDecoder_excludesExtensionDecoder()
      throws Exception {
    MediaCodecInfo platformCodecInfo =
        MediaCodecInfo.newInstance(
            /* name= */ "c2.android.av1.decoder",
            /* mimeType= */ MimeTypes.VIDEO_AV1,
            /* codecMimeType= */ MimeTypes.VIDEO_AV1,
            /* capabilities= */ null,
            /* hardwareAccelerated= */ false,
            /* softwareOnly= */ true,
            /* vendor= */ false);
    ExtensionCodecInfo av1OnlyExtensionCodecInfo =
        new ExtensionCodecInfo(/* name= */ "c2.media3.av1.only.decoder", MimeTypes.VIDEO_AV1) {
          @Override
          protected boolean isFormatSupported(Format format) {
            return Objects.equals(format.sampleMimeType, MimeTypes.VIDEO_AV1);
          }
        };
    MediaCodecSelector selector =
        (mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
            Objects.equals(mimeType, MimeTypes.VIDEO_AV1)
                ? ImmutableList.of(av1OnlyExtensionCodecInfo, platformCodecInfo)
                : ImmutableList.of();
    Format dolbyVisionAv1Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dav1.10.01")
            .build();

    List<MediaCodecInfo> decoderInfos =
        MediaCodecUtil.getAlternativeDecoderInfos(
            selector,
            dolbyVisionAv1Format,
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);

    assertThat(decoderInfos).containsExactly(platformCodecInfo);
  }

  @Test
  public void getExtensionDecoderInfos_forVideoAv1WithoutExtensionBundled_returnsEmpty() {
    Flags.enableFlag(Flags.FLAG_UNIFIED_DECODER_RENDERER);

    List<MediaCodecInfo> decoders =
        MediaCodecUtil.getExtensionDecoderInfos(
            MimeTypes.VIDEO_AV1,
            /* requiresSecureDecoder= */ false,
            /* requiresTunnelingDecoder= */ false);

    assertThat(decoders).isEmpty();
  }

  @Test
  public void
      getDecoderInfosSortedByFormatSupport_whenHwExceedsCapabilitiesAndExtensionSupportsFormat_promotesExtension() {
    Context context = ApplicationProvider.getApplicationContext();
    MediaCodecInfo hwLevel4 =
        MediaCodecInfo.newInstance(
            /* name= */ "c2.qti.hevc.decoder",
            /* mimeType= */ MimeTypes.VIDEO_H265,
            /* codecMimeType= */ MimeTypes.VIDEO_H265,
            createCodecCapabilities(HEVCProfileMain, HEVCMainTierLevel4),
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ true);
    ExtensionCodecInfo extensionHevc =
        ExtensionCodecInfo.newInstance(/* name= */ "c2.media3.hevc.decoder", MimeTypes.VIDEO_H265);
    Format level51Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.1.6.H153.B0")
            .build();

    List<MediaCodecInfo> sorted =
        MediaCodecUtil.getDecoderInfosSortedByFormatSupport(
            context, ImmutableList.of(hwLevel4, extensionHevc), level51Format);

    assertThat(sorted).containsExactly(extensionHevc, hwLevel4).inOrder();
  }

  @Test
  public void
      getDecoderInfosSortedByFormatSupport_whenBothHwAndExtensionSupportFormat_preservesInputOrder() {
    Context context = ApplicationProvider.getApplicationContext();
    MediaCodecInfo hwLevel51 =
        MediaCodecInfo.newInstance(
            /* name= */ "c2.qti.hevc.decoder",
            /* mimeType= */ MimeTypes.VIDEO_H265,
            /* codecMimeType= */ MimeTypes.VIDEO_H265,
            createCodecCapabilities(HEVCProfileMain, HEVCHighTierLevel51),
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ true);
    ExtensionCodecInfo extensionHevc =
        ExtensionCodecInfo.newInstance(/* name= */ "c2.media3.hevc.decoder", MimeTypes.VIDEO_H265);
    Format level4Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.1.6.L120.B0")
            .build();

    List<MediaCodecInfo> sortedModeOn =
        MediaCodecUtil.getDecoderInfosSortedByFormatSupport(
            context, ImmutableList.of(hwLevel51, extensionHevc), level4Format);
    List<MediaCodecInfo> sortedModePrefer =
        MediaCodecUtil.getDecoderInfosSortedByFormatSupport(
            context, ImmutableList.of(extensionHevc, hwLevel51), level4Format);

    assertThat(sortedModeOn).containsExactly(hwLevel51, extensionHevc).inOrder();
    assertThat(sortedModePrefer).containsExactly(extensionHevc, hwLevel51).inOrder();
  }

  @Test
  public void
      getDecoderInfosSortedByFormatSupport_whenHwExceedsCapabilitiesAndPlatformSwSupportsFormat_preservesHwFirst() {
    Context context = ApplicationProvider.getApplicationContext();
    MediaCodecInfo hwLevel4 =
        MediaCodecInfo.newInstance(
            /* name= */ "c2.qti.hevc.decoder",
            /* mimeType= */ MimeTypes.VIDEO_H265,
            /* codecMimeType= */ MimeTypes.VIDEO_H265,
            createCodecCapabilities(HEVCProfileMain, HEVCMainTierLevel4),
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ true);
    MediaCodecInfo platformSwLevel51 =
        MediaCodecInfo.newInstance(
            /* name= */ "c2.android.hevc.decoder",
            /* mimeType= */ MimeTypes.VIDEO_H265,
            /* codecMimeType= */ MimeTypes.VIDEO_H265,
            createCodecCapabilities(HEVCProfileMain, HEVCHighTierLevel51),
            /* hardwareAccelerated= */ false,
            /* softwareOnly= */ true,
            /* vendor= */ false);
    ExtensionCodecInfo extensionHevc =
        ExtensionCodecInfo.newInstance(/* name= */ "c2.media3.hevc.decoder", MimeTypes.VIDEO_H265);
    Format level51Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.1.6.H153.B0")
            .build();

    List<MediaCodecInfo> sorted =
        MediaCodecUtil.getDecoderInfosSortedByFormatSupport(
            context, ImmutableList.of(hwLevel4, platformSwLevel51, extensionHevc), level51Format);

    assertThat(sorted).containsExactly(hwLevel4, platformSwLevel51, extensionHevc).inOrder();
  }

  @Test
  public void
      getDecoderInfosSortedByFormatSupport_whenExtensionPrecedesPlatformSwAndHwExceedsCapabilities_promotesExtension() {
    Context context = ApplicationProvider.getApplicationContext();
    MediaCodecInfo hwLevel4 =
        MediaCodecInfo.newInstance(
            /* name= */ "c2.qti.hevc.decoder",
            /* mimeType= */ MimeTypes.VIDEO_H265,
            /* codecMimeType= */ MimeTypes.VIDEO_H265,
            createCodecCapabilities(HEVCProfileMain, HEVCMainTierLevel4),
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ true);
    ExtensionCodecInfo extensionHevc =
        ExtensionCodecInfo.newInstance(/* name= */ "c2.media3.hevc.decoder", MimeTypes.VIDEO_H265);
    MediaCodecInfo platformSwLevel51 =
        MediaCodecInfo.newInstance(
            /* name= */ "c2.android.hevc.decoder",
            /* mimeType= */ MimeTypes.VIDEO_H265,
            /* codecMimeType= */ MimeTypes.VIDEO_H265,
            createCodecCapabilities(HEVCProfileMain, HEVCHighTierLevel51),
            /* hardwareAccelerated= */ false,
            /* softwareOnly= */ true,
            /* vendor= */ false);
    Format level51Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.1.6.H153.B0")
            .build();

    List<MediaCodecInfo> sorted =
        MediaCodecUtil.getDecoderInfosSortedByFormatSupport(
            context, ImmutableList.of(hwLevel4, extensionHevc, platformSwLevel51), level51Format);

    assertThat(sorted).containsExactly(extensionHevc, hwLevel4, platformSwLevel51).inOrder();
  }

  @Test
  public void
      getDecoderInfosSortedByFormatSupport_whenHwExceedsCapabilitiesAndExtensionUnsupported_keepsHwFirst() {
    Context context = ApplicationProvider.getApplicationContext();
    MediaCodecInfo hwLevel4 =
        MediaCodecInfo.newInstance(
            /* name= */ "c2.qti.hevc.decoder",
            /* mimeType= */ MimeTypes.VIDEO_H265,
            /* codecMimeType= */ MimeTypes.VIDEO_H265,
            createCodecCapabilities(HEVCProfileMain, HEVCMainTierLevel4),
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ true);
    ExtensionCodecInfo unsupportedExtension =
        new ExtensionCodecInfo(/* name= */ "c2.media3.hevc.decoder", MimeTypes.VIDEO_H265) {
          @Override
          protected boolean isFormatSupported(Format format) {
            return false;
          }
        };
    Format level51Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.1.6.H153.B0")
            .build();

    List<MediaCodecInfo> sorted =
        MediaCodecUtil.getDecoderInfosSortedByFormatSupport(
            context, ImmutableList.of(hwLevel4, unsupportedExtension), level51Format);

    assertThat(sorted).containsExactly(hwLevel4, unsupportedExtension).inOrder();
  }

  @Test
  public void
      getDecoderInfosSortedByFormatSupport_whenPlatformCapabilitiesUnknown_preservesInputOrder() {
    Context context = ApplicationProvider.getApplicationContext();
    MediaCodecInfo hwUnknownCapabilities =
        MediaCodecInfo.newInstance(
            /* name= */ "c2.android.av1.hardware.decoder",
            /* mimeType= */ MimeTypes.VIDEO_AV1,
            /* codecMimeType= */ MimeTypes.VIDEO_AV1,
            /* capabilities= */ null,
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ true);
    ExtensionCodecInfo extensionAv1 =
        ExtensionCodecInfo.newInstance(
            /* name= */ "c2.media3.av1.dav1d.decoder", MimeTypes.VIDEO_AV1);
    Format av1Format720p =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setWidth(1280)
            .setHeight(720)
            .build();

    List<MediaCodecInfo> sorted =
        MediaCodecUtil.getDecoderInfosSortedByFormatSupport(
            context, ImmutableList.of(hwUnknownCapabilities, extensionAv1), av1Format720p);

    assertThat(sorted).containsExactly(hwUnknownCapabilities, extensionAv1).inOrder();
  }

  @Test
  public void
      getDecoderInfosSortedByFormatSupport_withPreferredAndFallbackExtensionsAroundFullSupportingHw_promotesOnlyPreferredExtension() {
    Context context = ApplicationProvider.getApplicationContext();
    ExtensionCodecInfo preferredExtensionHevc =
        ExtensionCodecInfo.newInstance(
            /* name= */ "c2.media3.hevc.preferred.decoder", MimeTypes.VIDEO_H265);
    MediaCodecInfo hwLevel51 =
        MediaCodecInfo.newInstance(
            /* name= */ "c2.qti.hevc.decoder",
            /* mimeType= */ MimeTypes.VIDEO_H265,
            /* codecMimeType= */ MimeTypes.VIDEO_H265,
            createCodecCapabilities(HEVCProfileMain, HEVCHighTierLevel51),
            /* hardwareAccelerated= */ true,
            /* softwareOnly= */ false,
            /* vendor= */ true);
    ExtensionCodecInfo fallbackExtensionHevc =
        ExtensionCodecInfo.newInstance(
            /* name= */ "c2.media3.hevc.fallback.decoder", MimeTypes.VIDEO_H265);
    Format level4Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.1.6.L120.B0")
            .build();

    List<MediaCodecInfo> sorted =
        MediaCodecUtil.getDecoderInfosSortedByFormatSupport(
            context,
            ImmutableList.of(preferredExtensionHevc, hwLevel51, fallbackExtensionHevc),
            level4Format);

    assertThat(sorted)
        .containsExactly(preferredExtensionHevc, hwLevel51, fallbackExtensionHevc)
        .inOrder();
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
