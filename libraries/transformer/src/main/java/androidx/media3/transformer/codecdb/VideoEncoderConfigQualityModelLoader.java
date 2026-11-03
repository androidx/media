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

import static androidx.media3.transformer.EncoderUtil.parseTemporalLayeringSchema;
import static com.google.common.base.Preconditions.checkNotNull;

import android.media.MediaFormat;
import android.util.Pair;
import androidx.media3.common.Format;
import androidx.media3.transformer.VideoEncoderSettings;
import androidx.media3.transformer.codecdb.DeviceIdentifiers.DeviceTier;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.Codec;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.VideoEncoderQualityModelParameters;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.VideoEncoderSettingsExtension.Model;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.TreeMap;

/**
 * Loads pairs of {@link VideoEncoderConfiguration} and {@link VideoEncoderQualityModel} from the
 * CodecDB database for a given selector (e.g. tier/chipset/device).
 *
 * <p>Each loaded model is stored as a mapping from the {@link VideoEncoderConfiguration} to the
 * {@link VideoEncoderQualityModel} for that configuration.
 *
 * <p>In short, this class handles the conversion from the raw database data over to the data
 * classes used by the rest of the library.
 */
/* package */ final class VideoEncoderConfigQualityModelLoader {

  private final EmbeddedDatabase db;

  /* package */ VideoEncoderConfigQualityModelLoader(EmbeddedDatabase db) {
    this.db = db;
  }

  /**
   * Loads and constructs a {@link VideoEncoderSettings} instance for the settings with the provided
   * database ID.
   */
  private VideoEncoderSettings loadVideoEncoderSettings(long videoEncoderSettingsId)
      throws IOException {
    EmbeddedDatabase.VideoEncoderSettings.Model settingsDbResult =
        checkNotNull(
            db.videoEncoderSettingsDao.findVideoEncoderSettingsById(videoEncoderSettingsId));
    ImmutableList<Model> settingsExtensionsDbResults =
        db.videoEncoderSettingsExtensionDao
            .findVideoEncoderSettingsExtensionsByVideoEncoderSettingsId(videoEncoderSettingsId);

    // TODO: b/379898012 - Load the bitrate mode from the database once it is stored as a
    // configurable parameter in the CodecDB database. Until then, this relies on the
    // VideoEncoderSettings.Builder default of BITRATE_MODE_VBR.
    VideoEncoderSettings.Builder settingsBuilder =
        new VideoEncoderSettings.Builder()
            .setMaxBFrames(settingsDbResult.maxBFrames)
            .setiFrameIntervalSeconds((float) settingsDbResult.keyFrameInterval)
            .setEncodingProfileLevel(settingsDbResult.profile, settingsDbResult.level);

    for (Model extensionDbResult : settingsExtensionsDbResults) {
      applyExtensionParameter(settingsBuilder, extensionDbResult.key, extensionDbResult.value);
    }

    return settingsBuilder.build();
  }

  /**
   * Applies a single {@code (key, value)} row of the {@code VideoEncoderSettingsExtension} table to
   * the provided {@link VideoEncoderSettings.Builder}.
   *
   * <p>Every key handled here is a public Android {@link MediaFormat} key with a dedicated field on
   * {@link VideoEncoderSettings}. To support an additional key, add a branch below.
   *
   * <p>Unrecognized keys and unparseable values are deliberately ignored rather than raising, so
   * that a newer database can never crash an older client.
   */
  private static void applyExtensionParameter(
      VideoEncoderSettings.Builder settingsBuilder, String key, String value) {
    if (key.equals(MediaFormat.KEY_TEMPORAL_LAYERING)) {
      try {
        Pair<Integer, Integer> temporalLayers = parseTemporalLayeringSchema(value);
        settingsBuilder.setTemporalLayers(temporalLayers.first, temporalLayers.second);
      } catch (IllegalArgumentException | UnsupportedOperationException e) {
        // Ignore a temporal layering schema that is malformed or unsupported.
      }
    } else if (key.equals(MediaFormat.KEY_COMPLEXITY)) {
      try {
        settingsBuilder.setComplexity(Integer.parseInt(value));
      } catch (NumberFormatException e) {
        // Ignore a complexity that is not an integer.
      }
    }
  }

  /**
   * Loads the full mapping of {@link VideoEncoderConfiguration} to {@link PciRateDistortionModel}
   * for each of the provided Codec DB models.
   */
  private void loadConfigModelsForCodecs(
      ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> configModelsOut,
      ImmutableList<Codec.Model> codecs)
      throws IOException {

    for (int codecIdx = 0; codecIdx < codecs.size(); codecIdx++) {
      Codec.Model codecDbResult = codecs.get(codecIdx);
      String mime = checkNotNull(codecDbResult.mime);

      ImmutableList<VideoEncoderQualityModelParameters.Model> qualityModels =
          db.videoEncoderQualityModelParametersDao
              .findSettingsOrderedVideoEncoderQualityModelParametersForCodec(codecDbResult.codecId);

      if (qualityModels.isEmpty()) {
        continue;
      }

      long currentSettingsId = qualityModels.get(0).videoEncoderSettingsId;
      VideoEncoderSettings currentSettings =
          loadVideoEncoderSettings(qualityModels.get(0).videoEncoderSettingsId);
      // Rate-distortion models for the current settings, keyed by pixels per second. If two rows
      // share a pixel rate, the first one is kept.
      TreeMap<Integer, PciRateDistortionModel> currentRdModels = new TreeMap<>();

      for (int qualityModelIdx = 0; qualityModelIdx < qualityModels.size(); qualityModelIdx++) {
        VideoEncoderQualityModelParameters.Model qualityModelDbResult =
            qualityModels.get(qualityModelIdx);

        if (currentSettingsId != qualityModelDbResult.videoEncoderSettingsId
            && !codecDbResult.isFallback) {
          configModelsOut.put(
              new VideoEncoderConfiguration(
                  currentSettings,
                  new Format.Builder().setSampleMimeType(mime).build(),
                  codecDbResult.isFallback),
              new VideoEncoderQualityModel(currentRdModels));

          currentSettingsId = qualityModelDbResult.videoEncoderSettingsId;
          currentSettings = loadVideoEncoderSettings(qualityModelDbResult.videoEncoderSettingsId);
          currentRdModels = new TreeMap<>();
        }

        currentRdModels.putIfAbsent(
            qualityModelDbResult.framePixels,
            new PciRateDistortionModel(
                qualityModelDbResult.coefficientA,
                qualityModelDbResult.coefficientB,
                qualityModelDbResult.minBitrateKbps,
                qualityModelDbResult.minVmaf));
      }

      configModelsOut.put(
          new VideoEncoderConfiguration(
              currentSettings,
              new Format.Builder().setSampleMimeType(mime).build(),
              codecDbResult.isFallback),
          new VideoEncoderQualityModel(currentRdModels));
    }
  }

  /**
   * Loads the configurations and their corresponding quality models for the generic models that
   * serve as a total fallback in the case a device is unable to be identified.
   */
  /* package */ void loadGenericConfigModels(
      ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> configModelsOut)
      throws IOException {
    loadConfigModelsForCodecs(configModelsOut, db.codecDao.findGenericCodecs());
  }

  /**
   * Loads the configurations and their corresponding quality models for all the generic codecs
   * associated with provided tier.
   */
  /* package */ void loadConfigModelsForTier(
      ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> configModelsOut,
      @DeviceTier int tier)
      throws IOException {
    Long tierId = db.tierDao.findTierIdForTier(tier);

    if (tierId == null) {
      return;
    }

    loadConfigModelsForCodecs(configModelsOut, db.codecDao.findCodecsForTier(tierId));
  }

  /**
   * Loads all the configurations and their corresponding quality models for all chipset-level
   * codecs associated with the specified chipset.
   *
   * <p>This method only loads models explicitly associated with the chipset. It does not load
   * fallback models.
   *
   * @param configModelsOut The builder to populate with loaded models.
   * @param manufacturer The chipset manufacturer (e.g., "Google").
   * @param model The chipset model (e.g., "Tensor G3").
   * @throws IOException If an error occurs while reading from the database.
   */
  /* package */ void loadConfigModelsForChipset(
      ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> configModelsOut,
      String manufacturer,
      String model)
      throws IOException {
    Long chipsetId = db.chipsetDao.findChipsetIdForChipset(manufacturer, model);

    if (chipsetId == null) {
      return;
    }

    loadConfigModelsForCodecs(configModelsOut, db.codecDao.findCodecsForChipset(chipsetId));
  }

  /**
   * Loads all the configurations and their corresponding quality models for all chipset-level
   * codecs associated with the specified chipset.
   *
   * <p>This method uses the device identifiers to resolve the chipset. It only loads models
   * explicitly associated with the chipset and does not load fallback models.
   *
   * @param configModelsOut The builder to populate with loaded models.
   * @param identifiers The device identifiers used to resolve the chipset.
   * @throws IOException If an error occurs while reading from the database.
   */
  /* package */ void loadConfigModelsForChipset(
      ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> configModelsOut,
      DeviceIdentifiers identifiers)
      throws IOException {
    Long chipsetId = db.chipsetDao.findChipsetIdForDeviceIdentifiers(identifiers);

    if (chipsetId == null) {
      return;
    }

    loadConfigModelsForCodecs(configModelsOut, db.codecDao.findCodecsForChipset(chipsetId));
  }

  /**
   * Loads all the configurations and their corresponding quality models for all fallback codecs
   * associated with the specified chipset.
   *
   * <p>Fallback models are used when specific chipset models are not available or sufficient. This
   * method uses the device identifiers to resolve the chipset.
   *
   * @param configModelsOut The builder to populate with loaded models.
   * @param identifiers The device identifiers used to resolve the chipset.
   * @throws IOException If an error occurs while reading from the database.
   */
  /* package */ void loadFallbackConfigModelsForChipset(
      ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> configModelsOut,
      DeviceIdentifiers identifiers)
      throws IOException {
    Long chipsetId = db.chipsetDao.findChipsetIdForDeviceIdentifiers(identifiers);

    if (chipsetId == null) {
      return;
    }

    ImmutableList<Long> fallbackCodecIds =
        db.fallbackCodecDao.findFallbackCodecIdsForChipset(chipsetId);
    if (fallbackCodecIds.isEmpty()) {
      return;
    }
    loadConfigModelsForCodecs(
        configModelsOut, db.codecDao.loadFallbackCodecsForCodecIds(fallbackCodecIds));
  }

  /**
   * Loads all configurations and their corresponding quality models for all device-level codecs
   * associated with the specified device-type.
   */
  /* package */ void loadConfigModelsForDevice(
      ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> configModelsOut,
      String deviceType)
      throws IOException {
    Long deviceId = db.deviceDao.findDeviceIdForType(deviceType);

    if (deviceId == null) {
      return;
    }

    loadConfigModelsForCodecs(configModelsOut, db.codecDao.findCodecsForDevice(deviceId));
  }
}
