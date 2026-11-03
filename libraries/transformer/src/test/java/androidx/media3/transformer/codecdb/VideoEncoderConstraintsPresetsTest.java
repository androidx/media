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

import static android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR;
import static android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
import static androidx.media3.transformer.codecdb.VideoEncoderConstraints.ANY_VIDEO_CODEC;
import static androidx.media3.transformer.codecdb.VideoEncoderConstraints.QUALITY_LEVEL_GOOD;
import static androidx.media3.transformer.codecdb.VideoEncoderConstraints.QUALITY_LEVEL_NEAR_LOSSLESS;
import static androidx.media3.transformer.codecdb.VideoEncoderConstraints.QUALITY_LEVEL_VERY_GOOD;
import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link VideoEncoderConstraintsPresets}. */
@RunWith(AndroidJUnit4.class)
public class VideoEncoderConstraintsPresetsTest {

  @Test
  public void forHqCameraRecording_withValidArguments_returnsPresetConstraints() {
    VideoEncoderConstraints expected =
        new VideoEncoderConstraints.Builder(
                /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0)
            .setAllowedCodecs(ANY_VIDEO_CODEC)
            .setTargetQuality(QUALITY_LEVEL_NEAR_LOSSLESS)
            .setKeyFrameIntervalSeconds(1f)
            .setBFramesAllowed(true)
            .setBitrateMode(BITRATE_MODE_VBR)
            .setBitDepth(8)
            .build();

    assertThat(
            VideoEncoderConstraintsPresets.forHqCameraRecording(
                /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0))
        .isEqualTo(expected);
  }

  @Test
  public void forEditing_withValidArguments_returnsPresetConstraints() {
    VideoEncoderConstraints expected =
        new VideoEncoderConstraints.Builder(
                /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0)
            .setAllowedCodecs(ANY_VIDEO_CODEC)
            .setTargetQuality(QUALITY_LEVEL_VERY_GOOD)
            .setKeyFrameIntervalSeconds(1f)
            .setBFramesAllowed(true)
            .setBitrateMode(BITRATE_MODE_VBR)
            .setBitDepth(8)
            .build();

    assertThat(
            VideoEncoderConstraintsPresets.forEditing(
                /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0))
        .isEqualTo(expected);
  }

  @Test
  public void forSocialPosting_withValidArguments_returnsPresetConstraints() {
    VideoEncoderConstraints expected =
        new VideoEncoderConstraints.Builder(
                /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0)
            .setAllowedCodecs(ANY_VIDEO_CODEC)
            .setTargetQuality(QUALITY_LEVEL_GOOD)
            .setKeyFrameIntervalSeconds(1f)
            .setBFramesAllowed(true)
            .setBitrateMode(BITRATE_MODE_VBR)
            .setBitDepth(8)
            .build();

    assertThat(
            VideoEncoderConstraintsPresets.forSocialPosting(
                /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0))
        .isEqualTo(expected);
  }

  @Test
  public void forGalleryExport_withValidArguments_returnsPresetConstraints() {
    VideoEncoderConstraints expected =
        new VideoEncoderConstraints.Builder(
                /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0)
            .setAllowedCodecs(ANY_VIDEO_CODEC)
            .setTargetQuality(QUALITY_LEVEL_GOOD)
            .setKeyFrameIntervalSeconds(1f)
            .setBFramesAllowed(true)
            .setBitrateMode(BITRATE_MODE_VBR)
            .setBitDepth(8)
            .build();

    assertThat(
            VideoEncoderConstraintsPresets.forGalleryExport(
                /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0))
        .isEqualTo(expected);
  }

  @Test
  public void forMessaging_withValidArguments_returnsPresetConstraints() {
    VideoEncoderConstraints expected =
        new VideoEncoderConstraints.Builder(
                /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0)
            .setAllowedCodecs(ANY_VIDEO_CODEC)
            .setMaxBitrate(2_000_000)
            .setKeyFrameIntervalSeconds(1f)
            .setBFramesAllowed(true)
            .setBitrateMode(BITRATE_MODE_VBR)
            .setBitDepth(8)
            .build();

    assertThat(
            VideoEncoderConstraintsPresets.forMessaging(
                /* width= */ 1920,
                /* height= */ 1080,
                /* frameRate= */ 30.0,
                /* targetMaxBitrate= */ 2_000_000))
        .isEqualTo(expected);
  }

  @Test
  public void forRealTimeCommunication_withValidArguments_returnsPresetConstraints() {
    VideoEncoderConstraints expected =
        new VideoEncoderConstraints.Builder(
                /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0)
            .setAllowedCodecs(ANY_VIDEO_CODEC)
            .setMaxBitrate(2_500_000)
            .setKeyFrameIntervalSeconds(1f)
            .setBFramesAllowed(false)
            .setBitrateMode(BITRATE_MODE_CBR)
            .setBitDepth(8)
            .build();

    assertThat(
            VideoEncoderConstraintsPresets.forRealTimeCommunication(
                /* width= */ 1920,
                /* height= */ 1080,
                /* frameRate= */ 30.0,
                /* targetMaxBitrate= */ 2_500_000))
        .isEqualTo(expected);
  }

  @Test
  public void forLiveStreaming_withValidArguments_returnsPresetConstraints() {
    VideoEncoderConstraints expected =
        new VideoEncoderConstraints.Builder(
                /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0)
            .setAllowedCodecs(ANY_VIDEO_CODEC)
            .setMaxBitrate(4_000_000)
            .setKeyFrameIntervalSeconds(1f)
            .setBFramesAllowed(true)
            .setBitrateMode(BITRATE_MODE_CBR)
            .setBitDepth(8)
            .build();

    assertThat(
            VideoEncoderConstraintsPresets.forLiveStreaming(
                /* width= */ 1920,
                /* height= */ 1080,
                /* frameRate= */ 30.0,
                /* targetMaxBitrate= */ 4_000_000))
        .isEqualTo(expected);
  }
}
