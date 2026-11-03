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

import static androidx.media3.transformer.codecdb.DeviceIdentifiers.DEVICE_TIER_HIGH;
import static androidx.media3.transformer.codecdb.DeviceIdentifiers.DEVICE_TIER_LOW;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.VideoEncoderSettings;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.Chipset;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.Codec;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.Codec.Model;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.DatabaseMetadata;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.Device;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.FallbackCodec;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.Tier.Dao;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.VideoEncoderQualityModelParameters;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.VideoEncoderSettingsExtension;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link VideoEncoderConfigQualityModelLoader}. */
@RunWith(AndroidJUnit4.class)
public class VideoEncoderConfigQualityModelLoaderTest {

  /**
   * The pixel rate of 1080p at 30 fps, used for every quality model in these tests.
   *
   * <p>The database stores this in {@code framePixels} as width * height * frames-per-second.
   */
  private static final int PIXELS_PER_SECOND_1080P30 = 1920 * 1080 * 30;

  /**
   * The pixel rate of 720p at 30 fps. Each quality model in these tests also has a row at this
   * pixel rate, since a quality model needs at least two rate-distortion models.
   */
  private static final int PIXELS_PER_SECOND_720P30 = 1280 * 720 * 30;

  @Rule public MockitoRule mocks = MockitoJUnit.rule();

  @Mock private Dao mockTierDao;

  @Mock private Chipset.Dao mockChipsetDao;

  @Mock private DatabaseMetadata.Dao mockDatabaseMetadataDao;

  @Mock private Device.Dao mockDeviceDao;

  @Mock private Codec.Dao mockCodecDao;

  @Mock private VideoEncoderQualityModelParameters.Dao mockVideoEncoderQualityModelParametersDao;

  @Mock private EmbeddedDatabase.VideoEncoderSettings.Dao mockVideoEncoderSettingsDao;

  @Mock private VideoEncoderSettingsExtension.Dao mockVideoEncoderSettingsExtensionDao;

  @Mock private FallbackCodec.Dao mockFallbackCodecDao;

  @Test
  public void loadGenericConfigModels_loadsModelsCorrectly() throws Exception {
    when(mockCodecDao.findGenericCodecs())
        .thenReturn(
            ImmutableList.of(
                new Model(
                    /* codecId= */ 1,
                    /* tierId= */ null,
                    /* chipsetId= */ null,
                    /* deviceId= */ null,
                    /* canonicalName= */ null,
                    /* mime= */ "video/hevc")));

    when(mockVideoEncoderQualityModelParametersDao
            .findSettingsOrderedVideoEncoderQualityModelParametersForCodec(/* codecId= */ 1))
        .thenReturn(
            ImmutableList.of(
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 1,
                    /* videoEncoderSettingsId= */ 1,
                    /* framePixels= */ PIXELS_PER_SECOND_1080P30,
                    /* coefficientA= */ -100000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 50,
                    /* minBitrateKbps= */ 500),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 1,
                    /* videoEncoderSettingsId= */ 1,
                    /* framePixels= */ PIXELS_PER_SECOND_720P30,
                    /* coefficientA= */ -100000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 50,
                    /* minBitrateKbps= */ 500),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 1,
                    /* videoEncoderSettingsId= */ 2,
                    /* framePixels= */ PIXELS_PER_SECOND_1080P30,
                    /* coefficientA= */ -110000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 45,
                    /* minBitrateKbps= */ 750),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 1,
                    /* videoEncoderSettingsId= */ 2,
                    /* framePixels= */ PIXELS_PER_SECOND_720P30,
                    /* coefficientA= */ -110000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 45,
                    /* minBitrateKbps= */ 750)));

    when(mockVideoEncoderSettingsDao.findVideoEncoderSettingsById(/* id= */ 1))
        .thenReturn(
            new EmbeddedDatabase.VideoEncoderSettings.Model(
                /* videoEncoderSettingsId= */ 1,
                /* maxBFrames= */ 1,
                /* keyFrameInterval= */ 1.0,
                /* profile= */ 1,
                /* level= */ 65536));
    when(mockVideoEncoderSettingsDao.findVideoEncoderSettingsById(/* id= */ 2))
        .thenReturn(
            new EmbeddedDatabase.VideoEncoderSettings.Model(
                /* videoEncoderSettingsId= */ 2,
                /* maxBFrames= */ 0,
                /* keyFrameInterval= */ 1.0,
                /* profile= */ 1,
                /* level= */ 65536));
    when(mockVideoEncoderSettingsExtensionDao
            .findVideoEncoderSettingsExtensionsByVideoEncoderSettingsId(
                /* videoEncoderSettingsId= */ 1))
        .thenReturn(
            ImmutableList.of(
                new VideoEncoderSettingsExtension.Model(2, "ts-schema", "android.generic.1+2")));
    when(mockVideoEncoderSettingsExtensionDao
            .findVideoEncoderSettingsExtensionsByVideoEncoderSettingsId(
                /* videoEncoderSettingsId= */ 2))
        .thenReturn(ImmutableList.of());

    VideoEncoderConfigQualityModelLoader repository =
        new VideoEncoderConfigQualityModelLoader(
            new EmbeddedDatabase(
                mockCodecDao,
                mockDatabaseMetadataDao,
                mockDeviceDao,
                mockTierDao,
                mockChipsetDao,
                mockVideoEncoderQualityModelParametersDao,
                mockVideoEncoderSettingsDao,
                mockVideoEncoderSettingsExtensionDao,
                mockFallbackCodecDao) {
              @Override
              public void close() {}
            });

    ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> resultsBuilder =
        ImmutableMap.builder();
    repository.loadGenericConfigModels(resultsBuilder);
    ImmutableMap<VideoEncoderConfiguration, VideoEncoderQualityModel> results =
        resultsBuilder.buildOrThrow();

    assertThat(getModelsAt1080p30(results))
        .containsExactly(
            new VideoEncoderConfiguration(
                new VideoEncoderSettings.Builder()
                    .setMaxBFrames(0)
                    .setiFrameIntervalSeconds(1.0f)
                    .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                    .build(),
                new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
                /* isFallback= */ false),
            new PciRateDistortionModel(
                /* a= */ -110000, /* b= */ 100, /* minRateKbps= */ 750, /* minDistortion= */ 45.0),
            new VideoEncoderConfiguration(
                new VideoEncoderSettings.Builder()
                    .setMaxBFrames(1)
                    .setiFrameIntervalSeconds(1.0f)
                    .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                    .setTemporalLayers(
                        /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                    .build(),
                new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
                /* isFallback= */ false),
            new PciRateDistortionModel(
                /* a= */ -100000,
                /* b= */ 100,
                /* minRateKbps= */ 500.0,
                /* minDistortion= */ 50.0));
  }

  @Test
  public void loadConfigModelsForTier_loadsModelsCorrectly() throws Exception {
    when(mockTierDao.findTierIdForTier(DEVICE_TIER_HIGH)).thenReturn(1L);
    when(mockCodecDao.findCodecsForTier(eq(1L)))
        .thenReturn(
            ImmutableList.of(
                new Model(
                    /* codecId= */ 1,
                    /* tierId= */ 1L,
                    /* chipsetId= */ null,
                    /* deviceId= */ null,
                    /* canonicalName= */ null,
                    /* mime= */ "video/hevc"),
                new Model(
                    /* codecId= */ 2,
                    /* tierId= */ 1L,
                    /* chipsetId= */ null,
                    /* deviceId= */ null,
                    /* canonicalName= */ null,
                    /* mime= */ "video/avc")));

    when(mockVideoEncoderQualityModelParametersDao
            .findSettingsOrderedVideoEncoderQualityModelParametersForCodec(/* codecId= */ 1))
        .thenReturn(
            ImmutableList.of(
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 1,
                    /* videoEncoderSettingsId= */ 1,
                    /* framePixels= */ PIXELS_PER_SECOND_1080P30,
                    /* coefficientA= */ -100000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 50,
                    /* minBitrateKbps= */ 500),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 1,
                    /* videoEncoderSettingsId= */ 1,
                    /* framePixels= */ PIXELS_PER_SECOND_720P30,
                    /* coefficientA= */ -100000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 50,
                    /* minBitrateKbps= */ 500),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 1,
                    /* videoEncoderSettingsId= */ 2,
                    /* framePixels= */ PIXELS_PER_SECOND_1080P30,
                    /* coefficientA= */ -110000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 45,
                    /* minBitrateKbps= */ 750),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 1,
                    /* videoEncoderSettingsId= */ 2,
                    /* framePixels= */ PIXELS_PER_SECOND_720P30,
                    /* coefficientA= */ -110000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 45,
                    /* minBitrateKbps= */ 750)));
    when(mockVideoEncoderQualityModelParametersDao
            .findSettingsOrderedVideoEncoderQualityModelParametersForCodec(/* codecId= */ 2))
        .thenReturn(
            ImmutableList.of(
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 2,
                    /* videoEncoderSettingsId= */ 1,
                    /* framePixels= */ PIXELS_PER_SECOND_1080P30,
                    /* coefficientA= */ -115000,
                    /* coefficientB= */ 101,
                    /* minVmaf= */ 50,
                    /* minBitrateKbps= */ 500),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 2,
                    /* videoEncoderSettingsId= */ 1,
                    /* framePixels= */ PIXELS_PER_SECOND_720P30,
                    /* coefficientA= */ -115000,
                    /* coefficientB= */ 101,
                    /* minVmaf= */ 50,
                    /* minBitrateKbps= */ 500),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 2,
                    /* videoEncoderSettingsId= */ 2,
                    /* framePixels= */ PIXELS_PER_SECOND_1080P30,
                    /* coefficientA= */ -120000,
                    /* coefficientB= */ 102,
                    /* minVmaf= */ 45,
                    /* minBitrateKbps= */ 750),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 2,
                    /* videoEncoderSettingsId= */ 2,
                    /* framePixels= */ PIXELS_PER_SECOND_720P30,
                    /* coefficientA= */ -120000,
                    /* coefficientB= */ 102,
                    /* minVmaf= */ 45,
                    /* minBitrateKbps= */ 750)));

    when(mockVideoEncoderSettingsDao.findVideoEncoderSettingsById(/* id= */ 1))
        .thenReturn(
            new EmbeddedDatabase.VideoEncoderSettings.Model(
                /* videoEncoderSettingsId= */ 1,
                /* maxBFrames= */ 1,
                /* keyFrameInterval= */ 1.0,
                /* profile= */ 1,
                /* level= */ 65536));
    when(mockVideoEncoderSettingsDao.findVideoEncoderSettingsById(/* id= */ 2))
        .thenReturn(
            new EmbeddedDatabase.VideoEncoderSettings.Model(
                /* videoEncoderSettingsId= */ 2,
                /* maxBFrames= */ 0,
                /* keyFrameInterval= */ 1.0,
                /* profile= */ 1,
                /* level= */ 65536));
    when(mockVideoEncoderSettingsExtensionDao
            .findVideoEncoderSettingsExtensionsByVideoEncoderSettingsId(
                /* videoEncoderSettingsId= */ 1))
        .thenReturn(
            ImmutableList.of(
                new VideoEncoderSettingsExtension.Model(2, "ts-schema", "android.generic.1+2")));
    when(mockVideoEncoderSettingsExtensionDao
            .findVideoEncoderSettingsExtensionsByVideoEncoderSettingsId(
                /* videoEncoderSettingsId= */ 2))
        .thenReturn(ImmutableList.of());

    VideoEncoderConfigQualityModelLoader repository =
        new VideoEncoderConfigQualityModelLoader(
            new EmbeddedDatabase(
                mockCodecDao,
                mockDatabaseMetadataDao,
                mockDeviceDao,
                mockTierDao,
                mockChipsetDao,
                mockVideoEncoderQualityModelParametersDao,
                mockVideoEncoderSettingsDao,
                mockVideoEncoderSettingsExtensionDao,
                mockFallbackCodecDao) {
              @Override
              public void close() {}
            });

    ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> resultsBuilder =
        ImmutableMap.builder();
    repository.loadConfigModelsForTier(resultsBuilder, DEVICE_TIER_HIGH);
    ImmutableMap<VideoEncoderConfiguration, VideoEncoderQualityModel> results =
        resultsBuilder.buildOrThrow();

    assertThat(getModelsAt1080p30(results))
        .containsExactly(
            new VideoEncoderConfiguration(
                new VideoEncoderSettings.Builder()
                    .setMaxBFrames(0)
                    .setiFrameIntervalSeconds(1.0f)
                    .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                    .build(),
                new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
                /* isFallback= */ false),
            new PciRateDistortionModel(
                /* a= */ -120000,
                /* b= */ 102,
                /* minRateKbps= */ 750.0,
                /* minDistortion= */ 45.0),
            new VideoEncoderConfiguration(
                new VideoEncoderSettings.Builder()
                    .setMaxBFrames(0)
                    .setiFrameIntervalSeconds(1.0f)
                    .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                    .build(),
                new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
                /* isFallback= */ false),
            new PciRateDistortionModel(
                /* a= */ -110000, /* b= */ 100, /* minRateKbps= */ 750, /* minDistortion= */ 45.0),
            new VideoEncoderConfiguration(
                new VideoEncoderSettings.Builder()
                    .setMaxBFrames(1)
                    .setiFrameIntervalSeconds(1.0f)
                    .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                    .setTemporalLayers(
                        /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                    .build(),
                new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
                /* isFallback= */ false),
            new PciRateDistortionModel(
                /* a= */ -115000,
                /* b= */ 101,
                /* minRateKbps= */ 500.0,
                /* minDistortion= */ 50.0),
            new VideoEncoderConfiguration(
                new VideoEncoderSettings.Builder()
                    .setMaxBFrames(1)
                    .setiFrameIntervalSeconds(1.0f)
                    .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                    .setTemporalLayers(
                        /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                    .build(),
                new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
                /* isFallback= */ false),
            new PciRateDistortionModel(
                /* a= */ -100000,
                /* b= */ 100,
                /* minRateKbps= */ 500.0,
                /* minDistortion= */ 50.0));
  }

  @Test
  public void loadConfigModelsForTier_returnsEmptyArray_noMatchingTier() throws Exception {
    when(mockTierDao.findTierIdForTier(DEVICE_TIER_LOW)).thenReturn(null);

    VideoEncoderConfigQualityModelLoader repository =
        new VideoEncoderConfigQualityModelLoader(
            new EmbeddedDatabase(
                mockCodecDao,
                mockDatabaseMetadataDao,
                mockDeviceDao,
                mockTierDao,
                mockChipsetDao,
                mockVideoEncoderQualityModelParametersDao,
                mockVideoEncoderSettingsDao,
                mockVideoEncoderSettingsExtensionDao,
                mockFallbackCodecDao) {
              @Override
              public void close() {}
            });

    ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> resultsBuilder =
        ImmutableMap.builder();
    repository.loadConfigModelsForTier(resultsBuilder, DEVICE_TIER_LOW);
    assertThat(resultsBuilder.buildOrThrow()).isEmpty();
  }

  @Test
  public void loadConfigModelsForChipset_loadsModelsCorrectly() throws Exception {
    when(mockChipsetDao.findChipsetIdForChipset("QTI", "SM8550")).thenReturn(2L);
    when(mockCodecDao.findCodecsForChipset(eq(2L)))
        .thenReturn(
            ImmutableList.of(
                new Model(
                    /* codecId= */ 1L,
                    /* tierId= */ 1L,
                    /* chipsetId= */ null,
                    /* deviceId= */ null,
                    /* canonicalName= */ null,
                    /* mime= */ "video/hevc"),
                new Model(
                    /* codecId= */ 2L,
                    /* tierId= */ 1L,
                    /* chipsetId= */ null,
                    /* deviceId= */ null,
                    /* canonicalName= */ null,
                    /* mime= */ "video/avc")));

    when(mockVideoEncoderQualityModelParametersDao
            .findSettingsOrderedVideoEncoderQualityModelParametersForCodec(/* codecId= */ 1))
        .thenReturn(
            ImmutableList.of(
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 1,
                    /* videoEncoderSettingsId= */ 1,
                    /* framePixels= */ PIXELS_PER_SECOND_1080P30,
                    /* coefficientA= */ -100000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 50,
                    /* minBitrateKbps= */ 500),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 1,
                    /* videoEncoderSettingsId= */ 1,
                    /* framePixels= */ PIXELS_PER_SECOND_720P30,
                    /* coefficientA= */ -100000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 50,
                    /* minBitrateKbps= */ 500),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 1,
                    /* videoEncoderSettingsId= */ 2,
                    /* framePixels= */ PIXELS_PER_SECOND_1080P30,
                    /* coefficientA= */ -110000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 45,
                    /* minBitrateKbps= */ 750),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 1,
                    /* videoEncoderSettingsId= */ 2,
                    /* framePixels= */ PIXELS_PER_SECOND_720P30,
                    /* coefficientA= */ -110000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 45,
                    /* minBitrateKbps= */ 750)));
    when(mockVideoEncoderQualityModelParametersDao
            .findSettingsOrderedVideoEncoderQualityModelParametersForCodec(/* codecId= */ 2))
        .thenReturn(
            ImmutableList.of(
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 2,
                    /* videoEncoderSettingsId= */ 1,
                    /* framePixels= */ PIXELS_PER_SECOND_1080P30,
                    /* coefficientA= */ -115000,
                    /* coefficientB= */ 101,
                    /* minVmaf= */ 50,
                    /* minBitrateKbps= */ 500),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 2,
                    /* videoEncoderSettingsId= */ 1,
                    /* framePixels= */ PIXELS_PER_SECOND_720P30,
                    /* coefficientA= */ -115000,
                    /* coefficientB= */ 101,
                    /* minVmaf= */ 50,
                    /* minBitrateKbps= */ 500),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 2,
                    /* videoEncoderSettingsId= */ 2,
                    /* framePixels= */ PIXELS_PER_SECOND_1080P30,
                    /* coefficientA= */ -120000,
                    /* coefficientB= */ 102,
                    /* minVmaf= */ 45,
                    /* minBitrateKbps= */ 750),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 2,
                    /* videoEncoderSettingsId= */ 2,
                    /* framePixels= */ PIXELS_PER_SECOND_720P30,
                    /* coefficientA= */ -120000,
                    /* coefficientB= */ 102,
                    /* minVmaf= */ 45,
                    /* minBitrateKbps= */ 750)));

    when(mockVideoEncoderSettingsDao.findVideoEncoderSettingsById(/* id= */ 1))
        .thenReturn(
            new EmbeddedDatabase.VideoEncoderSettings.Model(
                /* videoEncoderSettingsId= */ 1,
                /* maxBFrames= */ 1,
                /* keyFrameInterval= */ 1.0,
                /* profile= */ 1,
                /* level= */ 65536));
    when(mockVideoEncoderSettingsDao.findVideoEncoderSettingsById(/* id= */ 2))
        .thenReturn(
            new EmbeddedDatabase.VideoEncoderSettings.Model(
                /* videoEncoderSettingsId= */ 2,
                /* maxBFrames= */ 0,
                /* keyFrameInterval= */ 1.0,
                /* profile= */ 1,
                /* level= */ 65536));
    when(mockVideoEncoderSettingsExtensionDao
            .findVideoEncoderSettingsExtensionsByVideoEncoderSettingsId(
                /* videoEncoderSettingsId= */ 1))
        .thenReturn(
            ImmutableList.of(
                new VideoEncoderSettingsExtension.Model(2, "ts-schema", "android.generic.1+2")));
    when(mockVideoEncoderSettingsExtensionDao
            .findVideoEncoderSettingsExtensionsByVideoEncoderSettingsId(
                /* videoEncoderSettingsId= */ 2))
        .thenReturn(ImmutableList.of());

    VideoEncoderConfigQualityModelLoader repository =
        new VideoEncoderConfigQualityModelLoader(
            new EmbeddedDatabase(
                mockCodecDao,
                mockDatabaseMetadataDao,
                mockDeviceDao,
                mockTierDao,
                mockChipsetDao,
                mockVideoEncoderQualityModelParametersDao,
                mockVideoEncoderSettingsDao,
                mockVideoEncoderSettingsExtensionDao,
                mockFallbackCodecDao) {
              @Override
              public void close() {}
            });
    ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> resultsBuilder =
        ImmutableMap.builder();
    repository.loadConfigModelsForChipset(resultsBuilder, "QTI", "SM8550");
    ImmutableMap<VideoEncoderConfiguration, VideoEncoderQualityModel> results =
        resultsBuilder.buildOrThrow();

    assertThat(getModelsAt1080p30(results))
        .containsExactly(
            new VideoEncoderConfiguration(
                new VideoEncoderSettings.Builder()
                    .setMaxBFrames(0)
                    .setiFrameIntervalSeconds(1.0f)
                    .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                    .build(),
                new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
                /* isFallback= */ false),
            new PciRateDistortionModel(
                /* a= */ -120000,
                /* b= */ 102,
                /* minRateKbps= */ 750.0,
                /* minDistortion= */ 45.0),
            new VideoEncoderConfiguration(
                new VideoEncoderSettings.Builder()
                    .setMaxBFrames(0)
                    .setiFrameIntervalSeconds(1.0f)
                    .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                    .build(),
                new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
                /* isFallback= */ false),
            new PciRateDistortionModel(
                /* a= */ -110000, /* b= */ 100, /* minRateKbps= */ 750, /* minDistortion= */ 45.0),
            new VideoEncoderConfiguration(
                new VideoEncoderSettings.Builder()
                    .setMaxBFrames(1)
                    .setiFrameIntervalSeconds(1.0f)
                    .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                    .setTemporalLayers(
                        /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                    .build(),
                new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
                /* isFallback= */ false),
            new PciRateDistortionModel(
                /* a= */ -115000,
                /* b= */ 101,
                /* minRateKbps= */ 500.0,
                /* minDistortion= */ 50.0),
            new VideoEncoderConfiguration(
                new VideoEncoderSettings.Builder()
                    .setMaxBFrames(1)
                    .setiFrameIntervalSeconds(1.0f)
                    .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                    .setTemporalLayers(
                        /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                    .build(),
                new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
                /* isFallback= */ false),
            new PciRateDistortionModel(
                /* a= */ -100000,
                /* b= */ 100,
                /* minRateKbps= */ 500.0,
                /* minDistortion= */ 50.0));
  }

  @Test
  public void loadConfigModelsForChipset_returnsEmptyArray_noMatchingChipset() throws Exception {
    when(mockChipsetDao.findChipsetIdForChipset("QTI", "SM8123")).thenReturn(null);

    VideoEncoderConfigQualityModelLoader repository =
        new VideoEncoderConfigQualityModelLoader(
            new EmbeddedDatabase(
                mockCodecDao,
                mockDatabaseMetadataDao,
                mockDeviceDao,
                mockTierDao,
                mockChipsetDao,
                mockVideoEncoderQualityModelParametersDao,
                mockVideoEncoderSettingsDao,
                mockVideoEncoderSettingsExtensionDao,
                mockFallbackCodecDao) {
              @Override
              public void close() {}
            });

    ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> resultsBuilder =
        ImmutableMap.builder();
    repository.loadConfigModelsForChipset(resultsBuilder, "QTI", "SM8123");
    assertThat(resultsBuilder.buildOrThrow()).isEmpty();
  }

  @Test
  public void loadFallbackConfigModelsForChipset_loadsModelsCorrectly() throws Exception {
    when(mockChipsetDao.findChipsetIdForDeviceIdentifiers(any(DeviceIdentifiers.class)))
        .thenReturn(2L);
    when(mockFallbackCodecDao.findFallbackCodecIdsForChipset(eq(2L)))
        .thenReturn(ImmutableList.of(3L));
    when(mockCodecDao.loadFallbackCodecsForCodecIds(eq(ImmutableList.of(3L))))
        .thenReturn(
            ImmutableList.of(
                new Model(
                    /* codecId= */ 3L,
                    /* tierId= */ null,
                    /* chipsetId= */ null,
                    /* deviceId= */ null,
                    /* canonicalName= */ null,
                    /* mime= */ "video/avc",
                    /* isFallback= */ true)));

    when(mockVideoEncoderQualityModelParametersDao
            .findSettingsOrderedVideoEncoderQualityModelParametersForCodec(/* codecId= */ 3))
        .thenReturn(
            ImmutableList.of(
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 3,
                    /* videoEncoderSettingsId= */ 1,
                    /* framePixels= */ PIXELS_PER_SECOND_1080P30,
                    /* coefficientA= */ -100000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 50,
                    /* minBitrateKbps= */ 500),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 3,
                    /* videoEncoderSettingsId= */ 1,
                    /* framePixels= */ PIXELS_PER_SECOND_720P30,
                    /* coefficientA= */ -100000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 50,
                    /* minBitrateKbps= */ 500)));

    when(mockVideoEncoderSettingsDao.findVideoEncoderSettingsById(/* id= */ 1))
        .thenReturn(
            new EmbeddedDatabase.VideoEncoderSettings.Model(
                /* videoEncoderSettingsId= */ 1,
                /* maxBFrames= */ 1,
                /* keyFrameInterval= */ 1.0,
                /* profile= */ 1,
                /* level= */ 65536));
    when(mockVideoEncoderSettingsExtensionDao
            .findVideoEncoderSettingsExtensionsByVideoEncoderSettingsId(
                /* videoEncoderSettingsId= */ 1))
        .thenReturn(ImmutableList.of());

    VideoEncoderConfigQualityModelLoader repository =
        new VideoEncoderConfigQualityModelLoader(
            new EmbeddedDatabase(
                mockCodecDao,
                mockDatabaseMetadataDao,
                mockDeviceDao,
                mockTierDao,
                mockChipsetDao,
                mockVideoEncoderQualityModelParametersDao,
                mockVideoEncoderSettingsDao,
                mockVideoEncoderSettingsExtensionDao,
                mockFallbackCodecDao) {
              @Override
              public void close() {}
            });

    ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> resultsBuilder =
        ImmutableMap.builder();
    repository.loadFallbackConfigModelsForChipset(
        resultsBuilder,
        new DeviceIdentifiers.Builder()
            .setChipsetManufacturer("QTI")
            .setChipsetModel("SM8550")
            .setFingerprintHash(0L)
            .build());
    ImmutableMap<VideoEncoderConfiguration, VideoEncoderQualityModel> results =
        resultsBuilder.buildOrThrow();

    assertThat(getModelsAt1080p30(results))
        .containsExactly(
            new VideoEncoderConfiguration(
                new VideoEncoderSettings.Builder()
                    .setMaxBFrames(1)
                    .setiFrameIntervalSeconds(1.0f)
                    .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                    .build(),
                new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
                /* isFallback= */ true),
            new PciRateDistortionModel(
                /* a= */ -100000,
                /* b= */ 100,
                /* minRateKbps= */ 500.0,
                /* minDistortion= */ 50.0));
  }

  @Test
  public void loadFallbackConfigModelsForChipset_returnsEmptyArray_noMatchingChipset()
      throws Exception {
    when(mockChipsetDao.findChipsetIdForDeviceIdentifiers(any(DeviceIdentifiers.class)))
        .thenReturn(null);

    VideoEncoderConfigQualityModelLoader repository =
        new VideoEncoderConfigQualityModelLoader(
            new EmbeddedDatabase(
                mockCodecDao,
                mockDatabaseMetadataDao,
                mockDeviceDao,
                mockTierDao,
                mockChipsetDao,
                mockVideoEncoderQualityModelParametersDao,
                mockVideoEncoderSettingsDao,
                mockVideoEncoderSettingsExtensionDao,
                mockFallbackCodecDao) {
              @Override
              public void close() {}
            });

    ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> resultsBuilder =
        ImmutableMap.builder();
    repository.loadFallbackConfigModelsForChipset(
        resultsBuilder,
        new DeviceIdentifiers.Builder()
            .setChipsetManufacturer("QTI")
            .setChipsetModel("SM8123")
            .setFingerprintHash(0L)
            .build());
    assertThat(resultsBuilder.buildOrThrow()).isEmpty();
  }

  @Test
  public void loadConfigModelsForDevice_loadsModelsCorrectly() throws Exception {
    when(mockDeviceDao.findDeviceIdForType("raven")).thenReturn(3L);
    when(mockCodecDao.findCodecsForDevice(eq(3L)))
        .thenReturn(
            ImmutableList.of(
                new Model(
                    /* codecId= */ 1L,
                    /* tierId= */ 1L,
                    /* chipsetId= */ null,
                    /* deviceId= */ null,
                    /* canonicalName= */ null,
                    /* mime= */ "video/hevc"),
                new Model(
                    /* codecId= */ 2L,
                    /* tierId= */ 1L,
                    /* chipsetId= */ null,
                    /* deviceId= */ null,
                    /* canonicalName= */ null,
                    /* mime= */ "video/avc")));

    when(mockVideoEncoderQualityModelParametersDao
            .findSettingsOrderedVideoEncoderQualityModelParametersForCodec(/* codecId= */ 1))
        .thenReturn(
            ImmutableList.of(
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 1,
                    /* videoEncoderSettingsId= */ 1,
                    /* framePixels= */ PIXELS_PER_SECOND_1080P30,
                    /* coefficientA= */ -100000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 50,
                    /* minBitrateKbps= */ 500),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 1,
                    /* videoEncoderSettingsId= */ 1,
                    /* framePixels= */ PIXELS_PER_SECOND_720P30,
                    /* coefficientA= */ -100000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 50,
                    /* minBitrateKbps= */ 500),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 1,
                    /* videoEncoderSettingsId= */ 2,
                    /* framePixels= */ PIXELS_PER_SECOND_1080P30,
                    /* coefficientA= */ -110000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 45,
                    /* minBitrateKbps= */ 750),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 1,
                    /* videoEncoderSettingsId= */ 2,
                    /* framePixels= */ PIXELS_PER_SECOND_720P30,
                    /* coefficientA= */ -110000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 45,
                    /* minBitrateKbps= */ 750)));
    when(mockVideoEncoderQualityModelParametersDao
            .findSettingsOrderedVideoEncoderQualityModelParametersForCodec(/* codecId= */ 2))
        .thenReturn(
            ImmutableList.of(
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 2,
                    /* videoEncoderSettingsId= */ 1,
                    /* framePixels= */ PIXELS_PER_SECOND_1080P30,
                    /* coefficientA= */ -115000,
                    /* coefficientB= */ 101,
                    /* minVmaf= */ 50,
                    /* minBitrateKbps= */ 500),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 2,
                    /* videoEncoderSettingsId= */ 1,
                    /* framePixels= */ PIXELS_PER_SECOND_720P30,
                    /* coefficientA= */ -115000,
                    /* coefficientB= */ 101,
                    /* minVmaf= */ 50,
                    /* minBitrateKbps= */ 500),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 2,
                    /* videoEncoderSettingsId= */ 2,
                    /* framePixels= */ PIXELS_PER_SECOND_1080P30,
                    /* coefficientA= */ -120000,
                    /* coefficientB= */ 102,
                    /* minVmaf= */ 45,
                    /* minBitrateKbps= */ 750),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 2,
                    /* videoEncoderSettingsId= */ 2,
                    /* framePixels= */ PIXELS_PER_SECOND_720P30,
                    /* coefficientA= */ -120000,
                    /* coefficientB= */ 102,
                    /* minVmaf= */ 45,
                    /* minBitrateKbps= */ 750)));

    when(mockVideoEncoderSettingsDao.findVideoEncoderSettingsById(/* id= */ 1))
        .thenReturn(
            new EmbeddedDatabase.VideoEncoderSettings.Model(
                /* videoEncoderSettingsId= */ 1,
                /* maxBFrames= */ 1,
                /* keyFrameInterval= */ 1.0,
                /* profile= */ 1,
                /* level= */ 65536));
    when(mockVideoEncoderSettingsDao.findVideoEncoderSettingsById(/* id= */ 2))
        .thenReturn(
            new EmbeddedDatabase.VideoEncoderSettings.Model(
                /* videoEncoderSettingsId= */ 2,
                /* maxBFrames= */ 0,
                /* keyFrameInterval= */ 1.0,
                /* profile= */ 1,
                /* level= */ 65536));
    when(mockVideoEncoderSettingsExtensionDao
            .findVideoEncoderSettingsExtensionsByVideoEncoderSettingsId(
                /* videoEncoderSettingsId= */ 1))
        .thenReturn(
            ImmutableList.of(
                new VideoEncoderSettingsExtension.Model(2, "ts-schema", "android.generic.1+2")));
    when(mockVideoEncoderSettingsExtensionDao
            .findVideoEncoderSettingsExtensionsByVideoEncoderSettingsId(
                /* videoEncoderSettingsId= */ 2))
        .thenReturn(ImmutableList.of());

    VideoEncoderConfigQualityModelLoader repository =
        new VideoEncoderConfigQualityModelLoader(
            new EmbeddedDatabase(
                mockCodecDao,
                mockDatabaseMetadataDao,
                mockDeviceDao,
                mockTierDao,
                mockChipsetDao,
                mockVideoEncoderQualityModelParametersDao,
                mockVideoEncoderSettingsDao,
                mockVideoEncoderSettingsExtensionDao,
                mockFallbackCodecDao) {
              @Override
              public void close() {}
            });

    ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> resultsBuilder =
        ImmutableMap.builder();
    repository.loadConfigModelsForDevice(resultsBuilder, "raven");
    ImmutableMap<VideoEncoderConfiguration, VideoEncoderQualityModel> results =
        resultsBuilder.buildOrThrow();

    assertThat(getModelsAt1080p30(results))
        .containsExactly(
            new VideoEncoderConfiguration(
                new VideoEncoderSettings.Builder()
                    .setMaxBFrames(0)
                    .setiFrameIntervalSeconds(1.0f)
                    .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                    .build(),
                new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
                /* isFallback= */ false),
            new PciRateDistortionModel(
                /* a= */ -120000,
                /* b= */ 102,
                /* minRateKbps= */ 750.0,
                /* minDistortion= */ 45.0),
            new VideoEncoderConfiguration(
                new VideoEncoderSettings.Builder()
                    .setMaxBFrames(0)
                    .setiFrameIntervalSeconds(1.0f)
                    .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                    .build(),
                new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
                /* isFallback= */ false),
            new PciRateDistortionModel(
                /* a= */ -110000, /* b= */ 100, /* minRateKbps= */ 750, /* minDistortion= */ 45.0),
            new VideoEncoderConfiguration(
                new VideoEncoderSettings.Builder()
                    .setMaxBFrames(1)
                    .setiFrameIntervalSeconds(1.0f)
                    .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                    .setTemporalLayers(
                        /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                    .build(),
                new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
                /* isFallback= */ false),
            new PciRateDistortionModel(
                /* a= */ -115000,
                /* b= */ 101,
                /* minRateKbps= */ 500.0,
                /* minDistortion= */ 50.0),
            new VideoEncoderConfiguration(
                new VideoEncoderSettings.Builder()
                    .setMaxBFrames(1)
                    .setiFrameIntervalSeconds(1.0f)
                    .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                    .setTemporalLayers(
                        /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2)
                    .build(),
                new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build(),
                /* isFallback= */ false),
            new PciRateDistortionModel(
                /* a= */ -100000,
                /* b= */ 100,
                /* minRateKbps= */ 500.0,
                /* minDistortion= */ 50.0));
  }

  @Test
  public void loadConfigModelsForDevice_returnsEmptyArray_noMatchingDevice() throws Exception {
    when(mockDeviceDao.findDeviceIdForType("coral")).thenReturn(null);

    VideoEncoderConfigQualityModelLoader repository =
        new VideoEncoderConfigQualityModelLoader(
            new EmbeddedDatabase(
                mockCodecDao,
                mockDatabaseMetadataDao,
                mockDeviceDao,
                mockTierDao,
                mockChipsetDao,
                mockVideoEncoderQualityModelParametersDao,
                mockVideoEncoderSettingsDao,
                mockVideoEncoderSettingsExtensionDao,
                mockFallbackCodecDao) {
              @Override
              public void close() {}
            });

    ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> resultsBuilder =
        ImmutableMap.builder();
    repository.loadConfigModelsForDevice(resultsBuilder, "coral");
    assertThat(resultsBuilder.buildOrThrow()).isEmpty();
  }

  @Test
  public void loadConfigModels_withTemporalLayeringExtension_setsTemporalLayers() throws Exception {
    VideoEncoderSettings settings =
        loadGenericVideoEncoderSettingsWithExtensions(
            ImmutableList.of(
                new VideoEncoderSettingsExtension.Model(1, "ts-schema", "android.generic.1+2")));

    assertThat(settings.numNonBidirectionalTemporalLayers).isEqualTo(1);
    assertThat(settings.numBidirectionalTemporalLayers).isEqualTo(2);
  }

  @Test
  public void loadConfigModels_withComplexityExtension_setsComplexity() throws Exception {
    VideoEncoderSettings settings =
        loadGenericVideoEncoderSettingsWithExtensions(
            ImmutableList.of(new VideoEncoderSettingsExtension.Model(1, "complexity", "0")));

    assertThat(settings.complexity).isEqualTo(0);
  }

  @Test
  public void loadConfigModels_withExtensions_preservesZeroValuedColumns() throws Exception {
    VideoEncoderSettings settings =
        loadGenericVideoEncoderSettingsWithExtensions(
            ImmutableList.of(
                new VideoEncoderSettingsExtension.Model(1, "ts-schema", "android.generic.1+2"),
                new VideoEncoderSettingsExtension.Model(1, "complexity", "0")));

    assertThat(settings.maxBFrames).isEqualTo(0);
    assertThat(settings.profile).isEqualTo(0);
    assertThat(settings.level).isEqualTo(0);
    assertThat(settings.iFrameIntervalSeconds).isEqualTo(0.0f);
  }

  @Test
  public void loadConfigModels_withUnknownExtensionKey_ignoresExtension() throws Exception {
    VideoEncoderSettings settings =
        loadGenericVideoEncoderSettingsWithExtensions(
            ImmutableList.of(
                new VideoEncoderSettingsExtension.Model(1, "vendor.private.key", "some-value")));

    assertThat(settings)
        .isEqualTo(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(0)
                .setiFrameIntervalSeconds(0.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 0, /* encodingLevel= */ 0)
                .build());
  }

  @Test
  public void loadConfigModels_withUnparseableTemporalLayeringExtension_leavesTemporalLayersUnset()
      throws Exception {
    VideoEncoderSettings settings =
        loadGenericVideoEncoderSettingsWithExtensions(
            ImmutableList.of(
                new VideoEncoderSettingsExtension.Model(1, "ts-schema", "android.generic.oops")));

    assertThat(settings.numNonBidirectionalTemporalLayers).isEqualTo(VideoEncoderSettings.NO_VALUE);
    assertThat(settings.numBidirectionalTemporalLayers).isEqualTo(VideoEncoderSettings.NO_VALUE);
  }

  @Test
  public void loadConfigModels_withUnparseableComplexityExtension_leavesComplexityUnset()
      throws Exception {
    VideoEncoderSettings settings =
        loadGenericVideoEncoderSettingsWithExtensions(
            ImmutableList.of(
                new VideoEncoderSettingsExtension.Model(1, "complexity", "not-an-int")));

    assertThat(settings.complexity).isEqualTo(VideoEncoderSettings.NO_VALUE);
  }

  /**
   * Stubs a single generic HEVC codec with one settings row of zero-valued columns plus {@code
   * extensions}, runs {@link VideoEncoderConfigQualityModelLoader#loadGenericConfigModels}, and
   * returns the resulting {@link VideoEncoderSettings}.
   */
  private VideoEncoderSettings loadGenericVideoEncoderSettingsWithExtensions(
      ImmutableList<VideoEncoderSettingsExtension.Model> extensions) throws Exception {
    when(mockCodecDao.findGenericCodecs())
        .thenReturn(
            ImmutableList.of(
                new Model(
                    /* codecId= */ 1,
                    /* tierId= */ null,
                    /* chipsetId= */ null,
                    /* deviceId= */ null,
                    /* canonicalName= */ null,
                    /* mime= */ MimeTypes.VIDEO_H265)));
    when(mockVideoEncoderQualityModelParametersDao
            .findSettingsOrderedVideoEncoderQualityModelParametersForCodec(/* codecId= */ 1))
        .thenReturn(
            ImmutableList.of(
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 1,
                    /* videoEncoderSettingsId= */ 1,
                    /* framePixels= */ PIXELS_PER_SECOND_1080P30,
                    /* coefficientA= */ -100000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 50.0,
                    /* minBitrateKbps= */ 500.0),
                new VideoEncoderQualityModelParameters.Model(
                    /* codecId= */ 1,
                    /* videoEncoderSettingsId= */ 1,
                    /* framePixels= */ PIXELS_PER_SECOND_720P30,
                    /* coefficientA= */ -100000,
                    /* coefficientB= */ 100,
                    /* minVmaf= */ 50.0,
                    /* minBitrateKbps= */ 500.0)));
    when(mockVideoEncoderSettingsDao.findVideoEncoderSettingsById(/* id= */ 1))
        .thenReturn(
            new EmbeddedDatabase.VideoEncoderSettings.Model(
                /* videoEncoderSettingsId= */ 1,
                /* maxBFrames= */ 0,
                /* keyFrameInterval= */ 0.0,
                /* profile= */ 0,
                /* level= */ 0));
    when(mockVideoEncoderSettingsExtensionDao
            .findVideoEncoderSettingsExtensionsByVideoEncoderSettingsId(
                /* videoEncoderSettingsId= */ 1))
        .thenReturn(extensions);

    ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> resultsBuilder =
        ImmutableMap.builder();
    new VideoEncoderConfigQualityModelLoader(
            new EmbeddedDatabase(
                mockCodecDao,
                mockDatabaseMetadataDao,
                mockDeviceDao,
                mockTierDao,
                mockChipsetDao,
                mockVideoEncoderQualityModelParametersDao,
                mockVideoEncoderSettingsDao,
                mockVideoEncoderSettingsExtensionDao,
                mockFallbackCodecDao) {
              @Override
              public void close() {}
            })
        .loadGenericConfigModels(resultsBuilder);
    return resultsBuilder.buildOrThrow().keySet().iterator().next().videoEncoderSettings;
  }

  /**
   * Returns the {@link PciRateDistortionModel} of each loaded {@link VideoEncoderQualityModel} at
   * {@link #PIXELS_PER_SECOND_1080P30}, so that assertions check the loaded model parameters.
   */
  private static ImmutableMap<VideoEncoderConfiguration, PciRateDistortionModel> getModelsAt1080p30(
      ImmutableMap<VideoEncoderConfiguration, VideoEncoderQualityModel> results) {
    return ImmutableMap.copyOf(
        Maps.transformValues(results, model -> model.getModel(PIXELS_PER_SECOND_1080P30)));
  }
}
