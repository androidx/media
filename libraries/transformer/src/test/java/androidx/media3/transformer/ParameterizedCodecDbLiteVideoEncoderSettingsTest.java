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

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;
import org.robolectric.shadows.ShadowBuild;

/**
 * Parameterized tests for {@link CodecDbLite} to evaluate the {@link
 * CodecDbLite#getRecommendedVideoEncoderSettings} API across a variety of {@link Format}s and
 * chipset configurations.
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
public class ParameterizedCodecDbLiteVideoEncoderSettingsTest {

  @Parameter public TestParameters params;

  @Parameters(name = "{0}")
  public static ImmutableList<TestParameters> parameters() {
    return ImmutableList.of(
        new TestParameters(
            "Google",
            "Tensor G3",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(3840)
                .setHeight(2160)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Google",
            "Tensor G3",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(1920)
                .setHeight(1080)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Google",
            "Tensor G3",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(1280)
                .setHeight(720)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Google",
            "Tensor G3",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(960)
                .setHeight(540)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Google",
            "Tensor G3",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(854)
                .setHeight(480)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Google",
            "Tensor G3",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(640)
                .setHeight(360)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Google",
            "Tensor G3",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(3840)
                .setHeight(2160)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Google",
            "Tensor G3",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(1920)
                .setHeight(1080)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Google",
            "Tensor G3",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(1280)
                .setHeight(720)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Google",
            "Tensor G3",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(960)
                .setHeight(540)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Google",
            "Tensor G3",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(854)
                .setHeight(480)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Google",
            "Tensor G3",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(640)
                .setHeight(360)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "QTI",
            "SM8650",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(3840)
                .setHeight(2160)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "QTI",
            "SM8650",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(1920)
                .setHeight(1080)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(1)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build()),
        new TestParameters(
            "QTI",
            "SM8650",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(1280)
                .setHeight(720)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(1)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build()),
        new TestParameters(
            "QTI",
            "SM8650",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(960)
                .setHeight(540)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(1)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build()),
        new TestParameters(
            "QTI",
            "SM8650",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(854)
                .setHeight(480)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(1)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build()),
        new TestParameters(
            "QTI",
            "SM8650",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(640)
                .setHeight(360)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(1)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build()),
        new TestParameters(
            "QTI",
            "SM8650",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(3840)
                .setHeight(2160)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "QTI",
            "SM8650",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(1920)
                .setHeight(1080)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "QTI",
            "SM8650",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(1280)
                .setHeight(720)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(1)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build()),
        new TestParameters(
            "QTI",
            "SM8650",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(960)
                .setHeight(540)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(1)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build()),
        new TestParameters(
            "QTI",
            "SM8650",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(854)
                .setHeight(480)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(1)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build()),
        new TestParameters(
            "QTI",
            "SM8650",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(640)
                .setHeight(360)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(1)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build()),
        new TestParameters(
            "Mediatek",
            "MT6983",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(3840)
                .setHeight(2160)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Mediatek",
            "MT6983",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(1920)
                .setHeight(1080)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Mediatek",
            "MT6983",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(1280)
                .setHeight(720)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Mediatek",
            "MT6983",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(960)
                .setHeight(540)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Mediatek",
            "MT6983",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(854)
                .setHeight(480)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Mediatek",
            "MT6983",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(640)
                .setHeight(360)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Mediatek",
            "MT6983",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(3840)
                .setHeight(2160)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Mediatek",
            "MT6983",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(1920)
                .setHeight(1080)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Mediatek",
            "MT6983",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(1280)
                .setHeight(720)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Mediatek",
            "MT6983",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(960)
                .setHeight(540)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Mediatek",
            "MT6983",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(854)
                .setHeight(480)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Mediatek",
            "MT6983",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(640)
                .setHeight(360)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Samsung",
            "s5e9925",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(3840)
                .setHeight(2160)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Samsung",
            "s5e9925",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(1920)
                .setHeight(1080)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Samsung",
            "s5e9925",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(1280)
                .setHeight(720)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(2).build()),
        new TestParameters(
            "Samsung",
            "s5e9925",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(960)
                .setHeight(540)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(2).build()),
        new TestParameters(
            "Samsung",
            "s5e9925",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(854)
                .setHeight(480)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(2).build()),
        new TestParameters(
            "Samsung",
            "s5e9925",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(640)
                .setHeight(360)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(2).build()),
        new TestParameters(
            "Samsung",
            "s5e9925",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(3840)
                .setHeight(2160)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Samsung",
            "s5e9925",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(1920)
                .setHeight(1080)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Samsung",
            "s5e9925",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(1280)
                .setHeight(720)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Samsung",
            "s5e9925",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(960)
                .setHeight(540)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Samsung",
            "s5e9925",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(854)
                .setHeight(480)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Samsung",
            "s5e9925",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(640)
                .setHeight(360)
                .setFrameRate(30.00f)
                .build(),
            new VideoEncoderSettings.Builder().setMaxBFrames(1).build()),
        new TestParameters(
            "Unknown",
            "Chipset",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(640)
                .setHeight(360)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Unknown",
            "Chipset",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(854)
                .setHeight(480)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Unknown",
            "Chipset",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(960)
                .setHeight(540)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Unknown",
            "Chipset",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(1280)
                .setHeight(720)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Unknown",
            "Chipset",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(1920)
                .setHeight(1080)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Unknown",
            "Chipset",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(3840)
                .setHeight(2160)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Unknown",
            "Chipset",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(640)
                .setHeight(360)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Unknown",
            "Chipset",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(854)
                .setHeight(480)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Unknown",
            "Chipset",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(960)
                .setHeight(540)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Unknown",
            "Chipset",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(1280)
                .setHeight(720)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Unknown",
            "Chipset",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(1920)
                .setHeight(1080)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT),
        new TestParameters(
            "Unknown",
            "Chipset",
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(3840)
                .setHeight(2160)
                .setFrameRate(30.00f)
                .build(),
            VideoEncoderSettings.DEFAULT));
  }

  @Test
  public void getRecommendedVideoEncoderSettings_returnsExpectedSettings() {
    ShadowBuild.setSystemOnChipManufacturer(params.socManufacturer);
    ShadowBuild.setSystemOnChipModel(params.socModel);

    assertThat(CodecDbLite.getRecommendedVideoEncoderSettings(params.encoderFormat))
        .isEqualTo(params.expectedSettings);
  }

  private static final class TestParameters {
    private final String socManufacturer;
    private final String socModel;
    private final Format encoderFormat;
    private final VideoEncoderSettings expectedSettings;

    public TestParameters(
        String socManufacturer,
        String socModel,
        Format encoderFormat,
        VideoEncoderSettings expectedSettings) {
      this.socManufacturer = socManufacturer;
      this.socModel = socModel;
      this.encoderFormat = encoderFormat;
      this.expectedSettings = expectedSettings;
    }

    @Override
    public String toString() {
      return String.format(
          "%s %s(%s): %dx%d@%.02f",
          socManufacturer,
          socModel,
          encoderFormat.sampleMimeType,
          encoderFormat.width,
          encoderFormat.height,
          encoderFormat.frameRate);
    }
  }
}
