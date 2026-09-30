/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.transformer;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowBuild;

/** Unit tests for {@link CodecDbLite}. */
@RunWith(AndroidJUnit4.class)
public class CodecDbLiteTest {

  @Test
  public void getRecommendedVideoEncoderSettings_noMimeType_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CodecDbLite.getRecommendedVideoEncoderSettings(new Format.Builder().build()));
  }

  @Test
  public void getRecommendedVideoEncoderSettings_nonVideoMimeType_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CodecDbLite.getRecommendedVideoEncoderSettings(
                new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()));
  }

  @Test
  public void getRecommendedVideoEncoderSettings_avc1080p30_returnsRecommendedBitrate() {
    // Samsung Exynos 850 opts in to the rate model at 1080p.
    ShadowBuild.setSystemOnChipManufacturer("Samsung");
    ShadowBuild.setSystemOnChipModel("Exynos 850");

    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1920)
            .setHeight(1080)
            .setFrameRate(30)
            .build();

    VideoEncoderSettings settings = CodecDbLite.getRecommendedVideoEncoderSettings(format);

    assertThat(settings.bitrate).isEqualTo(15_502_805);
  }

  @Test
  public void getRecommendedVideoEncoderSettings_hevc720p30_returnsRecommendedBitrate() {
    // Samsung s5e9925 keeps its H.265 recommendation and is approved at 720p.
    ShadowBuild.setSystemOnChipManufacturer("Samsung");
    ShadowBuild.setSystemOnChipModel("s5e9925");

    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setWidth(1280)
            .setHeight(720)
            .setFrameRate(30)
            .build();

    VideoEncoderSettings settings = CodecDbLite.getRecommendedVideoEncoderSettings(format);

    assertThat(settings.bitrate).isEqualTo(8_904_992);
  }

  @Test
  public void getRecommendedVideoEncoderSettings_av14k30_returnsHevcRecommendedBitrate() {
    // Google Tensor G4 has an AV1 entry that opts in to the rate model at 4k.
    ShadowBuild.setSystemOnChipManufacturer("Google");
    ShadowBuild.setSystemOnChipModel("Tensor G4");

    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1)
            .setWidth(3840)
            .setHeight(2160)
            .setFrameRate(30)
            .build();

    VideoEncoderSettings settings = CodecDbLite.getRecommendedVideoEncoderSettings(format);

    assertThat(settings.bitrate).isEqualTo(18_947_248);
  }

  @Test
  public void getRecommendedVideoEncoderSettings_unsupportedVideoCodec_returnsNoBitrate() {
    ShadowBuild.setSystemOnChipManufacturer("Google");
    ShadowBuild.setSystemOnChipModel("Tensor G3");

    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_VP9)
            .setWidth(1920)
            .setHeight(1080)
            .setFrameRate(30)
            .build();

    VideoEncoderSettings settings = CodecDbLite.getRecommendedVideoEncoderSettings(format);

    assertThat(settings.bitrate).isEqualTo(VideoEncoderSettings.NO_VALUE);
  }

  @Test
  public void getRecommendedVideoEncoderSettings_unknownChipset_returnsNoBitrate() {
    ShadowBuild.setSystemOnChipManufacturer("Unknown");
    ShadowBuild.setSystemOnChipModel("Unknown");

    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1920)
            .setHeight(1080)
            .setFrameRate(30)
            .build();

    VideoEncoderSettings settings = CodecDbLite.getRecommendedVideoEncoderSettings(format);

    assertThat(settings.bitrate).isEqualTo(VideoEncoderSettings.NO_VALUE);
  }

  @Test
  public void getRecommendedVideoEncoderSettings_chipsetWithoutRateModel1080p_returnsNoBitrate() {
    // Mediatek MT6761 does not opt in to the rate model in any scope.
    ShadowBuild.setSystemOnChipManufacturer("Mediatek");
    ShadowBuild.setSystemOnChipModel("MT6761");

    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1920)
            .setHeight(1080)
            .setFrameRate(30)
            .build();

    VideoEncoderSettings settings = CodecDbLite.getRecommendedVideoEncoderSettings(format);

    assertThat(settings.bitrate).isEqualTo(VideoEncoderSettings.NO_VALUE);
  }

  @Test
  public void getRecommendedVideoEncoderSettings_chipsetApprovedAt4kOnly_1080p_returnsNoBitrate() {
    // Mediatek MT6785 only opts in to the rate model in the 4k scope.
    ShadowBuild.setSystemOnChipManufacturer("Mediatek");
    ShadowBuild.setSystemOnChipModel("MT6785");

    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1920)
            .setHeight(1080)
            .setFrameRate(30)
            .build();

    VideoEncoderSettings settings = CodecDbLite.getRecommendedVideoEncoderSettings(format);

    assertThat(settings.bitrate).isEqualTo(VideoEncoderSettings.NO_VALUE);
  }

  @Test
  public void
      getRecommendedVideoEncoderSettings_chipsetApprovedAt4kOnly_4k_returnsRecommendedBitrate() {
    ShadowBuild.setSystemOnChipManufacturer("Mediatek");
    ShadowBuild.setSystemOnChipModel("MT6785");

    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(3840)
            .setHeight(2160)
            .setFrameRate(30)
            .build();

    VideoEncoderSettings settings = CodecDbLite.getRecommendedVideoEncoderSettings(format);

    assertThat(settings.bitrate).isEqualTo(29_044_193);
  }

  @Test
  public void
      getRecommendedVideoEncoderSettings_chipsetApprovedAt720pNot1080p_720p_returnsRecommendedBitrate() {
    // Samsung s5e9925 opts in to the rate model at 720p and 4k but not at 1080p.
    ShadowBuild.setSystemOnChipManufacturer("Samsung");
    ShadowBuild.setSystemOnChipModel("s5e9925");

    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1280)
            .setHeight(720)
            .setFrameRate(30)
            .build();

    VideoEncoderSettings settings = CodecDbLite.getRecommendedVideoEncoderSettings(format);

    assertThat(settings.bitrate).isEqualTo(10_549_473);
  }

  @Test
  public void
      getRecommendedVideoEncoderSettings_chipsetApprovedAt720pNot1080p_1080p_returnsNoBitrate() {
    ShadowBuild.setSystemOnChipManufacturer("Samsung");
    ShadowBuild.setSystemOnChipModel("s5e9925");

    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1920)
            .setHeight(1080)
            .setFrameRate(30)
            .build();

    VideoEncoderSettings settings = CodecDbLite.getRecommendedVideoEncoderSettings(format);

    assertThat(settings.bitrate).isEqualTo(VideoEncoderSettings.NO_VALUE);
  }

  @Test
  public void getRecommendedVideoMimeType_chipsetWithoutHevcEntry_returnsAvc() {
    // QTI SM6225 has no H.265 entry in the dataset, so H.264 is recommended.
    ShadowBuild.setSystemOnChipManufacturer("QTI");
    ShadowBuild.setSystemOnChipModel("SM6225");

    assertThat(CodecDbLite.getRecommendedVideoMimeType()).isEqualTo(MimeTypes.VIDEO_H264);
  }

  @Test
  public void getRecommendedVideoMimeType_chipsetWithHevcEntry_returnsHevc() {
    // Samsung s5e9925 has an H.265 entry ahead of its H.264 entry.
    ShadowBuild.setSystemOnChipManufacturer("Samsung");
    ShadowBuild.setSystemOnChipModel("s5e9925");

    assertThat(CodecDbLite.getRecommendedVideoMimeType()).isEqualTo(MimeTypes.VIDEO_H265);
  }

  @Test
  public void
      getRecommendedVideoEncoderSettings_tensorG3_720p_hevcReturnsNoBitrate_h264ReturnsBitrate() {
    // Google Tensor G3 carries the 720p rate model flag on its H.264 entry but not on its H.265
    // entry.
    ShadowBuild.setSystemOnChipManufacturer("Google");
    ShadowBuild.setSystemOnChipModel("Tensor G3");

    Format hevcFormat =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setWidth(1280)
            .setHeight(720)
            .setFrameRate(30)
            .build();
    assertThat(CodecDbLite.getRecommendedVideoEncoderSettings(hevcFormat).bitrate)
        .isEqualTo(VideoEncoderSettings.NO_VALUE);

    Format h264Format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1280)
            .setHeight(720)
            .setFrameRate(30)
            .build();
    assertThat(CodecDbLite.getRecommendedVideoEncoderSettings(h264Format).bitrate)
        .isEqualTo(10_549_473);
  }

  @Test
  public void getRecommendedVideoEncoderSettings_wideAspectRatioIn1080pScope_returnsNoBitrate() {
    // 1920x816 is a 2.35:1 crop of 1080p. It is classified as 1080p because its long edge is
    // 1920, and Samsung s5e9925 does not carry the 1080p rate model flag.
    //
    // Classifying by pixel count instead would place its 1,566,720 pixels below the 1080p pixel
    // count and into the 720p scope, where s5e9925 does carry the flag, applying the rate model
    // at a scope it was not validated for.
    ShadowBuild.setSystemOnChipManufacturer("Samsung");
    ShadowBuild.setSystemOnChipModel("s5e9925");

    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(1920)
            .setHeight(816)
            .setFrameRate(30)
            .build();

    VideoEncoderSettings settings = CodecDbLite.getRecommendedVideoEncoderSettings(format);

    assertThat(settings.bitrate).isEqualTo(VideoEncoderSettings.NO_VALUE);
  }

  @Test
  public void
      getRecommendedVideoEncoderSettings_wideAspectRatioIn4kScope_returnsRecommendedBitrate()
          throws Exception {
    // 3840x1608 is a 2.39:1 crop of 4k. It is classified as 4k because its long edge is 3840, and
    // Mediatek MT6785 carries the 4k rate model flag but not the 1080p one.
    //
    // This is the same classifier disagreement as the 1080p case above but in the opposite
    // direction: its 6,174,720 pixels are fewer than a full 4k frame, so bucketing by pixel count
    // would withhold a recommendation the dataset does allow. The exact bitrate is not pinned here
    // because the formula itself is covered by the canonical-resolution tests above; what matters
    // is that the 4k scope flag is honoured.
    ShadowBuild.setSystemOnChipManufacturer("Mediatek");
    ShadowBuild.setSystemOnChipModel("MT6785");

    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(3840)
            .setHeight(1608)
            .setFrameRate(30)
            .build();

    VideoEncoderSettings settings = CodecDbLite.getRecommendedVideoEncoderSettings(format);

    assertThat(settings.bitrate).isNotEqualTo(VideoEncoderSettings.NO_VALUE);
    assertThat(settings.bitrate).isGreaterThan(0);
  }

  @Test
  public void getRecommendedVideoEncoderSettings_belowSmallestModelledScope_returnsNoBitrate() {
    // 854x480 is below the 720p scope, the smallest scope the rate model is validated for, so no
    // recommendation is made even for a chipset that carries every scope flag.
    ShadowBuild.setSystemOnChipManufacturer("Samsung");
    ShadowBuild.setSystemOnChipModel("Exynos 850");

    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setWidth(854)
            .setHeight(480)
            .setFrameRate(30)
            .build();

    VideoEncoderSettings settings = CodecDbLite.getRecommendedVideoEncoderSettings(format);

    assertThat(settings.bitrate).isEqualTo(VideoEncoderSettings.NO_VALUE);
  }
}
