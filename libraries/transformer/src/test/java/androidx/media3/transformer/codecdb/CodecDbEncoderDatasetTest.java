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
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.VideoEncoderSettings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link CodecDbEncoderDataset}. */
@RunWith(AndroidJUnit4.class)
public class CodecDbEncoderDatasetTest {

  @Test
  public void findOptimalConfiguration_withNoBFrames_findsCorrectSettings() {
    ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> configModels =
        ImmutableMap.builder();
    VideoEncoderConfiguration noBConfig =
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(0)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                .build(),
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
            /* isFallback= */ false);
    configModels.put(
        noBConfig,
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                854 * 480 * 30,
                new PciRateDistortionModel(
                    /* a= */ -28438.088719117182,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 249.0,
                    /* minDistortion= */ 48.567283630371094),
                1280 * 720 * 30,
                new PciRateDistortionModel(
                    /* a= */ -64292.67747459876,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 256.0,
                    /* minDistortion= */ 45.93724822998047),
                1920 * 1080 * 30,
                new PciRateDistortionModel(
                    /* a= */ -149780.90500583098,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 503.0,
                    /* minDistortion= */ 42.56543731689453),
                3840 * 2160 * 30,
                new PciRateDistortionModel(
                    /* a= */ -718448.9065150776,
                    /* b= */ 102.37698793558395,
                    /* minRateKbps= */ 5007.0,
                    /* minDistortion= */ 40.03440475463867))));

    VideoEncoderConfiguration oneBConfig =
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(1)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build(),
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
            /* isFallback= */ false);
    configModels.put(
        oneBConfig,
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                854 * 480 * 30,
                new PciRateDistortionModel(
                    /* a= */ -27603.474494100195,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 250.0,
                    /* minDistortion= */ 46.361732482910156),
                1280 * 720 * 30,
                new PciRateDistortionModel(
                    /* a= */ -63487.683520828476,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 252.0,
                    /* minDistortion= */ 43.23570251464844),
                1920 * 1080 * 30,
                new PciRateDistortionModel(
                    /* a= */ -149202.65970768215,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 504.0,
                    /* minDistortion= */ 39.62779998779297),
                3840 * 2160 * 30,
                new PciRateDistortionModel(
                    /* a= */ -682623.4985854008,
                    /* b= */ 101.68688833609218,
                    /* minRateKbps= */ 5000.0,
                    /* minDistortion= */ 40.48951721191406))));

    VideoEncoderConfiguration twoBConfiguration =
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(2)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build(),
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
            /* isFallback= */ false);
    configModels.put(
        twoBConfiguration,
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                854 * 480 * 30,
                new PciRateDistortionModel(
                    /* a= */ -27603.474370493732,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 250.0,
                    /* minDistortion= */ 46.361732482910156),
                1280 * 720 * 30,
                new PciRateDistortionModel(
                    /* a= */ -63483.102410446474,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 252.0,
                    /* minDistortion= */ 43.23570251464844),
                1920 * 1080 * 30,
                new PciRateDistortionModel(
                    /* a= */ -141404.24663191836,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 504.0,
                    /* minDistortion= */ 39.62779998779297),
                3840 * 2160 * 30,
                new PciRateDistortionModel(
                    /* a= */ -682623.4959058407,
                    /* b= */ 101.68688825097823,
                    /* minRateKbps= */ 5000.0,
                    /* minDistortion= */ 40.48951721191406))));

    VideoEncoderConfiguration threeBConfiguration =
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(3)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build(),
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
            /* isFallback= */ false);
    configModels.put(
        threeBConfiguration,
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                854 * 480 * 30,
                new PciRateDistortionModel(
                    /* a= */ -27603.474561910098,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 250.0,
                    /* minDistortion= */ 46.361732482910156),
                1280 * 720 * 30,
                new PciRateDistortionModel(
                    /* a= */ -63483.10236343774,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 252.0,
                    /* minDistortion= */ 43.23570251464844),
                1920 * 1080 * 30,
                new PciRateDistortionModel(
                    /* a= */ -149203.68220602974,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 504.0,
                    /* minDistortion= */ 39.62779998779297),
                3840 * 2160 * 30,
                new PciRateDistortionModel(
                    /* a= */ -682623.4128073071,
                    /* b= */ 101.68688658640522,
                    /* minRateKbps= */ 5000.0,
                    /* minDistortion= */ 40.48951721191406))));

    CodecDbEncoderDataset dataset = createDataset(configModels.buildKeepingLast());
    assertThat(
            dataset.findOptimalConfiguration(
                VideoEncoderConstraintsPresets.forRealTimeCommunication(
                    /* width= */ 1920,
                    /* height= */ 1080,
                    /* frameRate= */ 30.0,
                    /* targetMaxBitrate= */ 5_991_000)))
        .isEqualTo(
            withResolutionAndBitrate(
                noBConfig,
                /* width= */ 1920,
                /* height= */ 1080,
                /* frameRate= */ 30.0,
                noBConfig
                    .videoEncoderSettings
                    .buildUpon()
                    .setiFrameIntervalSeconds(1.0f)
                    .setBitrateMode(BITRATE_MODE_CBR)
                    .build(),
                /* bitrate= */ 5_991_000));
  }

  @Test
  public void findOptimalConfiguration_withLimitedBFrames_findsCorrectSettings() {
    ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> configModels =
        ImmutableMap.builder();
    VideoEncoderConfiguration noBConfig =
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(0)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                .build(),
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
            /* isFallback= */ false);
    configModels.put(
        noBConfig,
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                854 * 480 * 30,
                new PciRateDistortionModel(
                    /* a= */ -28438.088719117182,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 249.0,
                    /* minDistortion= */ 48.567283630371094),
                1280 * 720 * 30,
                new PciRateDistortionModel(
                    /* a= */ -64292.67747459876,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 256.0,
                    /* minDistortion= */ 45.93724822998047),
                1920 * 1080 * 30,
                new PciRateDistortionModel(
                    /* a= */ -149780.90500583098,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 503.0,
                    /* minDistortion= */ 42.56543731689453),
                3840 * 2160 * 30,
                new PciRateDistortionModel(
                    /* a= */ -718448.9065150776,
                    /* b= */ 102.37698793558395,
                    /* minRateKbps= */ 5007.0,
                    /* minDistortion= */ 40.03440475463867))));

    VideoEncoderConfiguration oneBConfig =
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(1)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build(),
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
            /* isFallback= */ false);
    configModels.put(
        oneBConfig,
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                854 * 480 * 30,
                new PciRateDistortionModel(
                    /* a= */ -27603.474494100195,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 250.0,
                    /* minDistortion= */ 46.361732482910156),
                1280 * 720 * 30,
                new PciRateDistortionModel(
                    /* a= */ -63487.683520828476,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 252.0,
                    /* minDistortion= */ 43.23570251464844),
                1920 * 1080 * 30,
                new PciRateDistortionModel(
                    /* a= */ -149202.65970768215,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 504.0,
                    /* minDistortion= */ 39.62779998779297),
                3840 * 2160 * 30,
                new PciRateDistortionModel(
                    /* a= */ -682623.4985854008,
                    /* b= */ 101.68688833609218,
                    /* minRateKbps= */ 5000.0,
                    /* minDistortion= */ 40.48951721191406))));

    VideoEncoderConfiguration twoBConfiguration =
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(2)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build(),
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
            /* isFallback= */ false);
    configModels.put(
        twoBConfiguration,
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                854 * 480 * 30,
                new PciRateDistortionModel(
                    /* a= */ -27603.474370493732,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 250.0,
                    /* minDistortion= */ 46.361732482910156),
                1280 * 720 * 30,
                new PciRateDistortionModel(
                    /* a= */ -63483.102410446474,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 252.0,
                    /* minDistortion= */ 43.23570251464844),
                1920 * 1080 * 30,
                new PciRateDistortionModel(
                    /* a= */ -141404.24663191836,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 504.0,
                    /* minDistortion= */ 39.62779998779297),
                3840 * 2160 * 30,
                new PciRateDistortionModel(
                    /* a= */ -682623.4959058407,
                    /* b= */ 101.68688825097823,
                    /* minRateKbps= */ 5000.0,
                    /* minDistortion= */ 40.48951721191406))));

    VideoEncoderConfiguration threeBConfiguration =
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(3)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build(),
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
            /* isFallback= */ false);
    configModels.put(
        threeBConfiguration,
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                854 * 480 * 30,
                new PciRateDistortionModel(
                    /* a= */ -27603.474561910098,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 250.0,
                    /* minDistortion= */ 46.361732482910156),
                1280 * 720 * 30,
                new PciRateDistortionModel(
                    /* a= */ -63483.10236343774,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 252.0,
                    /* minDistortion= */ 43.23570251464844),
                1920 * 1080 * 30,
                new PciRateDistortionModel(
                    /* a= */ -149203.68220602974,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 504.0,
                    /* minDistortion= */ 39.62779998779297),
                3840 * 2160 * 30,
                new PciRateDistortionModel(
                    /* a= */ -682623.4128073071,
                    /* b= */ 101.68688658640522,
                    /* minRateKbps= */ 5000.0,
                    /* minDistortion= */ 40.48951721191406))));

    CodecDbEncoderDataset dataset = createDataset(configModels.buildKeepingLast());
    assertThat(
            dataset.findOptimalConfiguration(
                VideoEncoderConstraintsPresets.forEditing(
                        /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0)
                    .buildUpon()
                    .setTargetQuality(75)
                    .build()))
        .isEqualTo(
            withResolutionAndBitrate(
                twoBConfiguration,
                /* width= */ 1920,
                /* height= */ 1080,
                /* frameRate= */ 30.0,
                twoBConfiguration
                    .videoEncoderSettings
                    .buildUpon()
                    .setiFrameIntervalSeconds(1.0f)
                    .setBitrateMode(BITRATE_MODE_VBR)
                    .build(),
                /* bitrate= */ 5_657_000));
  }

  @Test
  public void findOptimalConfiguration_withBFrames_findsCorrectSettings() {
    ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> configModels =
        ImmutableMap.builder();
    VideoEncoderConfiguration noBConfig =
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(0)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                .build(),
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
            /* isFallback= */ false);
    configModels.put(
        noBConfig,
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                854 * 480 * 30,
                new PciRateDistortionModel(
                    /* a= */ -28438.088719117182,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 249.0,
                    /* minDistortion= */ 48.567283630371094),
                1280 * 720 * 30,
                new PciRateDistortionModel(
                    /* a= */ -64292.67747459876,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 256.0,
                    /* minDistortion= */ 45.93724822998047),
                1920 * 1080 * 30,
                new PciRateDistortionModel(
                    /* a= */ -149780.90500583098,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 503.0,
                    /* minDistortion= */ 42.56543731689453),
                3840 * 2160 * 30,
                new PciRateDistortionModel(
                    /* a= */ -718448.9065150776,
                    /* b= */ 102.37698793558395,
                    /* minRateKbps= */ 5007.0,
                    /* minDistortion= */ 40.03440475463867))));

    VideoEncoderConfiguration oneBConfig =
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(1)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build(),
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
            /* isFallback= */ false);
    configModels.put(
        oneBConfig,
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                854 * 480 * 30,
                new PciRateDistortionModel(
                    /* a= */ -27603.474494100195,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 250.0,
                    /* minDistortion= */ 46.361732482910156),
                1280 * 720 * 30,
                new PciRateDistortionModel(
                    /* a= */ -63487.683520828476,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 252.0,
                    /* minDistortion= */ 43.23570251464844),
                1920 * 1080 * 30,
                new PciRateDistortionModel(
                    /* a= */ -149202.65970768215,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 504.0,
                    /* minDistortion= */ 39.62779998779297),
                3840 * 2160 * 30,
                new PciRateDistortionModel(
                    /* a= */ -682623.4985854008,
                    /* b= */ 101.68688833609218,
                    /* minRateKbps= */ 5000.0,
                    /* minDistortion= */ 40.48951721191406))));

    VideoEncoderConfiguration twoBConfiguration =
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(2)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build(),
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
            /* isFallback= */ false);
    configModels.put(
        twoBConfiguration,
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                854 * 480 * 30,
                new PciRateDistortionModel(
                    /* a= */ -27603.474370493732,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 250.0,
                    /* minDistortion= */ 46.361732482910156),
                1280 * 720 * 30,
                new PciRateDistortionModel(
                    /* a= */ -63483.102410446474,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 252.0,
                    /* minDistortion= */ 43.23570251464844),
                1920 * 1080 * 30,
                new PciRateDistortionModel(
                    /* a= */ -141404.24663191836,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 504.0,
                    /* minDistortion= */ 39.62779998779297),
                3840 * 2160 * 30,
                new PciRateDistortionModel(
                    /* a= */ -682623.4959058407,
                    /* b= */ 101.68688825097823,
                    /* minRateKbps= */ 5000.0,
                    /* minDistortion= */ 40.48951721191406))));

    VideoEncoderConfiguration threeBConfiguration =
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(3)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build(),
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
            /* isFallback= */ false);
    configModels.put(
        threeBConfiguration,
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                854 * 480 * 30,
                new PciRateDistortionModel(
                    /* a= */ -27603.474561910098,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 250.0,
                    /* minDistortion= */ 46.361732482910156),
                1280 * 720 * 30,
                new PciRateDistortionModel(
                    /* a= */ -63483.10236343774,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 252.0,
                    /* minDistortion= */ 43.23570251464844),
                1920 * 1080 * 30,
                new PciRateDistortionModel(
                    /* a= */ -149203.68220602974,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 504.0,
                    /* minDistortion= */ 39.62779998779297),
                3840 * 2160 * 30,
                new PciRateDistortionModel(
                    /* a= */ -682623.4128073071,
                    /* b= */ 101.68688658640522,
                    /* minRateKbps= */ 5000.0,
                    /* minDistortion= */ 40.48951721191406))));

    CodecDbEncoderDataset dataset = createDataset(configModels.buildKeepingLast());

    assertThat(
            dataset.findOptimalConfiguration(
                VideoEncoderConstraintsPresets.forEditing(
                        /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0)
                    .buildUpon()
                    .setTargetQuality(75)
                    .build()))
        .isEqualTo(
            withResolutionAndBitrate(
                twoBConfiguration,
                /* width= */ 1920,
                /* height= */ 1080,
                /* frameRate= */ 30.0,
                twoBConfiguration
                    .videoEncoderSettings
                    .buildUpon()
                    .setiFrameIntervalSeconds(1.0f)
                    .setBitrateMode(BITRATE_MODE_VBR)
                    .build(),
                /* bitrate= */ 5_657_000));
  }

  @Test
  public void findOptimalConfiguration_withInterpolatedResolution_findsCorrectSettings() {
    ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> configModels =
        ImmutableMap.builder();
    VideoEncoderConfiguration noBConfig =
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(0)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                .build(),
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
            /* isFallback= */ false);
    configModels.put(
        noBConfig,
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                854 * 480 * 30,
                new PciRateDistortionModel(
                    /* a= */ -28438.088719117182,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 249.0,
                    /* minDistortion= */ 48.567283630371094),
                1280 * 720 * 30,
                new PciRateDistortionModel(
                    /* a= */ -64292.67747459876,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 256.0,
                    /* minDistortion= */ 45.93724822998047),
                1920 * 1080 * 30,
                new PciRateDistortionModel(
                    /* a= */ -149780.90500583098,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 503.0,
                    /* minDistortion= */ 42.56543731689453),
                3840 * 2160 * 30,
                new PciRateDistortionModel(
                    /* a= */ -718448.9065150776,
                    /* b= */ 102.37698793558395,
                    /* minRateKbps= */ 5007.0,
                    /* minDistortion= */ 40.03440475463867))));

    VideoEncoderConfiguration oneBConfig =
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(1)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build(),
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
            /* isFallback= */ false);
    configModels.put(
        oneBConfig,
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                854 * 480 * 30,
                new PciRateDistortionModel(
                    /* a= */ -27603.474494100195,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 250.0,
                    /* minDistortion= */ 46.361732482910156),
                1280 * 720 * 30,
                new PciRateDistortionModel(
                    /* a= */ -63487.683520828476,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 252.0,
                    /* minDistortion= */ 43.23570251464844),
                1920 * 1080 * 30,
                new PciRateDistortionModel(
                    /* a= */ -149202.65970768215,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 504.0,
                    /* minDistortion= */ 39.62779998779297),
                3840 * 2160 * 30,
                new PciRateDistortionModel(
                    /* a= */ -682623.4985854008,
                    /* b= */ 101.68688833609218,
                    /* minRateKbps= */ 5000.0,
                    /* minDistortion= */ 40.48951721191406))));

    VideoEncoderConfiguration twoBConfiguration =
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(2)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build(),
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
            /* isFallback= */ false);
    configModels.put(
        twoBConfiguration,
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                854 * 480 * 30,
                new PciRateDistortionModel(
                    /* a= */ -27603.474370493732,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 250.0,
                    /* minDistortion= */ 46.361732482910156),
                1280 * 720 * 30,
                new PciRateDistortionModel(
                    /* a= */ -63483.102410446474,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 252.0,
                    /* minDistortion= */ 43.23570251464844),
                1920 * 1080 * 30,
                new PciRateDistortionModel(
                    /* a= */ -141404.24663191836,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 504.0,
                    /* minDistortion= */ 39.62779998779297),
                3840 * 2160 * 30,
                new PciRateDistortionModel(
                    /* a= */ -682623.4959058407,
                    /* b= */ 101.68688825097823,
                    /* minRateKbps= */ 5000.0,
                    /* minDistortion= */ 40.48951721191406))));

    VideoEncoderConfiguration threeBConfiguration =
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(3)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                .setTemporalLayers(
                    /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                .build(),
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
            /* isFallback= */ false);
    configModels.put(
        threeBConfiguration,
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                854 * 480 * 30,
                new PciRateDistortionModel(
                    /* a= */ -27603.474561910098,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 250.0,
                    /* minDistortion= */ 46.361732482910156),
                1280 * 720 * 30,
                new PciRateDistortionModel(
                    /* a= */ -63483.10236343774,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 252.0,
                    /* minDistortion= */ 43.23570251464844),
                1920 * 1080 * 30,
                new PciRateDistortionModel(
                    /* a= */ -149203.68220602974,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 504.0,
                    /* minDistortion= */ 39.62779998779297),
                3840 * 2160 * 30,
                new PciRateDistortionModel(
                    /* a= */ -682623.4128073071,
                    /* b= */ 101.68688658640522,
                    /* minRateKbps= */ 5000.0,
                    /* minDistortion= */ 40.48951721191406))));

    CodecDbEncoderDataset dataset = createDataset(configModels.buildKeepingLast());

    assertThat(
            dataset.findOptimalConfiguration(
                VideoEncoderConstraintsPresets.forEditing(
                        /* width= */ 954, /* height= */ 1920, /* frameRate= */ 30.0)
                    .buildUpon()
                    .setTargetQuality(Format.NO_VALUE)
                    .setMaxBitrate(4_000_000)
                    .build()))
        .isEqualTo(
            withResolutionAndBitrate(
                twoBConfiguration,
                /* width= */ 954,
                /* height= */ 1920,
                /* frameRate= */ 30.0,
                twoBConfiguration
                    .videoEncoderSettings
                    .buildUpon()
                    .setiFrameIntervalSeconds(1.0f)
                    .setBitrateMode(BITRATE_MODE_VBR)
                    .build(),
                /* bitrate= */ 4_000_000));
  }

  @Test
  public void findOptimalConfiguration_withHardConstraintViolated_returnsNull() {
    ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> configModels =
        ImmutableMap.builder();
    VideoEncoderConfiguration hevcConfig =
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(0)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                .build(),
            new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
            /* isFallback= */ false);
    configModels.put(
        hevcConfig,
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                1280 * 720 * 30,
                new PciRateDistortionModel(
                    /* a= */ -64292.67747459876,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 256.0,
                    /* minDistortion= */ 45.93724822998047),
                1920 * 1080 * 30,
                new PciRateDistortionModel(
                    /* a= */ -149780.90500583098,
                    /* b= */ 100.0,
                    /* minRateKbps= */ 503.0,
                    /* minDistortion= */ 42.56543731689453))));

    CodecDbEncoderDataset dataset = createDataset(configModels.buildKeepingLast());

    VideoEncoderConstraints editingConstraints =
        VideoEncoderConstraintsPresets.forEditing(
                /* width= */ 1920, /* height= */ 1080, /* frameRate= */ 30.0f)
            .buildUpon()
            // Request AVC, but we only have HEVC
            .setAllowedCodecs(C.VIDEO_CODEC_FLAG_H264)
            .setTargetQuality(80)
            .build();
    assertThat(dataset.findOptimalConfiguration(editingConstraints)).isNull();
  }

  @Test
  public void predictQualityFor_withoutBitrate_throwsIllegalArgumentException() {
    CodecDbEncoderDataset dataset = createDataset(ImmutableMap.of());
    VideoEncoderConfiguration configuration =
        new VideoEncoderConfiguration(
            VideoEncoderSettings.DEFAULT,
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(1920)
                .setHeight(1080)
                .setFrameRate(30.0f)
                .build(),
            /* isFallback= */ false);

    assertThrows(IllegalArgumentException.class, () -> dataset.predictQualityFor(configuration));
  }

  private static CodecDbEncoderDataset createDataset(
      Map<VideoEncoderConfiguration, VideoEncoderQualityModel> configModels) {
    return new CodecDbEncoderDataset(
        configModels,
        /* configFallbackProvider= */ constraints -> null,
        /* qualityFallbackProvider= */ configuration -> Double.NaN);
  }

  private static VideoEncoderConfiguration withResolutionAndBitrate(
      VideoEncoderConfiguration base,
      int width,
      int height,
      double frameRate,
      VideoEncoderSettings settings,
      int bitrate) {
    return new VideoEncoderConfiguration(
        settings,
        new Format.Builder()
            .setSampleMimeType(base.format.sampleMimeType)
            .setWidth(width)
            .setHeight(height)
            .setFrameRate((float) frameRate)
            .setAverageBitrate(bitrate)
            .build(),
        base.isFallback);
  }
}
