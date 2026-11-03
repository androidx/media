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
package androidx.media3.transformer.codecdb;

import static androidx.media3.common.util.Util.getVideoCodecFlagForMime;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link VideoEncoderConstraints}. */
@RunWith(AndroidJUnit4.class)
public class VideoEncoderConstraintsTest {

  @Test
  public void getVideoCodecFlagForMime_returnsProperConstant_h264() {
    assertThat(getVideoCodecFlagForMime(MimeTypes.VIDEO_H264)).isEqualTo(C.VIDEO_CODEC_FLAG_H264);
    assertThat(getVideoCodecFlagForMime("video/avc")).isEqualTo(C.VIDEO_CODEC_FLAG_H264);
  }

  @Test
  public void getVideoCodecFlagForMime_returnsProperConstant_h265() {
    assertThat(getVideoCodecFlagForMime(MimeTypes.VIDEO_H265)).isEqualTo(C.VIDEO_CODEC_FLAG_H265);
    assertThat(getVideoCodecFlagForMime("video/hevc")).isEqualTo(C.VIDEO_CODEC_FLAG_H265);
  }

  @Test
  public void getVideoCodecFlagForMime_returnsProperConstant_av1() {
    assertThat(getVideoCodecFlagForMime(MimeTypes.VIDEO_AV1)).isEqualTo(C.VIDEO_CODEC_FLAG_AV1);
    assertThat(getVideoCodecFlagForMime("video/av01")).isEqualTo(C.VIDEO_CODEC_FLAG_AV1);
  }

  @Test
  public void getVideoCodecFlagForMime_unrecognizedMime_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class, () -> getVideoCodecFlagForMime("video/unsupported"));
    assertThrows(IllegalArgumentException.class, () -> getVideoCodecFlagForMime("video/vp9"));
  }

  @Test
  public void setMaxBitrate_withNonPositiveValue_throwsIllegalArgumentException() {
    VideoEncoderConstraints.Builder builder =
        new VideoEncoderConstraints.Builder(
            /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0);

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> builder.setMaxBitrate(0));

    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Max bitrate must be Format.NO_VALUE or strictly greater than zero: 0");
  }

  @Test
  public void setTargetQuality_withNonPositiveValue_throwsIllegalArgumentException() {
    VideoEncoderConstraints.Builder builder =
        new VideoEncoderConstraints.Builder(
            /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0);

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> builder.setTargetQuality(0));

    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Target quality must be Format.NO_VALUE or in (0, 100]: 0.0");
  }

  @Test
  public void setTargetQuality_withValueAbove100_throwsIllegalArgumentException() {
    VideoEncoderConstraints.Builder builder =
        new VideoEncoderConstraints.Builder(
            /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0);

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> builder.setTargetQuality(101));

    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Target quality must be Format.NO_VALUE or in (0, 100]: 101.0");
  }

  @Test
  public void setMaxBitrate_whenTargetQualitySet_throwsIllegalStateException() {
    VideoEncoderConstraints.Builder builder =
        new VideoEncoderConstraints.Builder(
                /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0)
            .setTargetQuality(VideoEncoderConstraints.QUALITY_LEVEL_GOOD);

    assertThrows(IllegalStateException.class, () -> builder.setMaxBitrate(2_000_000));
  }

  @Test
  public void setTargetQuality_whenMaxBitrateSet_throwsIllegalStateException() {
    VideoEncoderConstraints.Builder builder =
        new VideoEncoderConstraints.Builder(
                /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0)
            .setMaxBitrate(2_000_000);

    assertThrows(
        IllegalStateException.class,
        () -> builder.setTargetQuality(VideoEncoderConstraints.QUALITY_LEVEL_GOOD));
  }

  @Test
  public void buildUpon_clearTargetQualityThenSetMaxBitrate_setsMaxBitrate() {
    VideoEncoderConstraints constraints =
        new VideoEncoderConstraints.Builder(
                /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0)
            .setTargetQuality(VideoEncoderConstraints.QUALITY_LEVEL_GOOD)
            .build()
            .buildUpon()
            .setTargetQuality(Format.NO_VALUE)
            .setMaxBitrate(2_000_000)
            .build();

    assertThat(constraints.maxBitrate).isEqualTo(2_000_000);
    assertThat(constraints.targetQuality).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void buildUpon_clearMaxBitrateThenSetTargetQuality_setsTargetQuality() {
    VideoEncoderConstraints constraints =
        new VideoEncoderConstraints.Builder(
                /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0)
            .setMaxBitrate(2_000_000)
            .build()
            .buildUpon()
            .setMaxBitrate(Format.NO_VALUE)
            .setTargetQuality(VideoEncoderConstraints.QUALITY_LEVEL_GOOD)
            .build();

    assertThat(constraints.targetQuality).isEqualTo(VideoEncoderConstraints.QUALITY_LEVEL_GOOD);
    assertThat(constraints.maxBitrate).isEqualTo(Format.NO_VALUE);
  }

  @Test
  public void build_whenNeitherTargetQualityNorMaxBitrateSet_defaultsToNearLosslessQuality() {
    VideoEncoderConstraints constraints =
        new VideoEncoderConstraints.Builder(
                /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0)
            .build();

    assertThat(constraints.targetQuality)
        .isEqualTo(VideoEncoderConstraints.QUALITY_LEVEL_NEAR_LOSSLESS);
    assertThat(constraints.maxBitrate).isEqualTo(Format.NO_VALUE);
  }
}
