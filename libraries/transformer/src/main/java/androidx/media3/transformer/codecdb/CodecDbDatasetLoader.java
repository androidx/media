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

import android.database.sqlite.SQLiteException;
import androidx.media3.common.util.ExperimentalApi;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Loads {@link CodecDbEncoderDataset} instances from the CodecDB database for a specific device.
 *
 * <p>Obtain instances via {@link CodecDb#onDevice}.
 */
@ExperimentalApi // TODO(b/579130160): Promote to @UnstableApi or remove.
public final class CodecDbDatasetLoader {

  private final DeviceIdentifiers deviceIdentifiers;
  private final Supplier<EmbeddedDatabase> databaseSupplier;

  /**
   * Creates a loader that reads encoder models for the device described by {@code
   * deviceIdentifiers}.
   *
   * @param deviceIdentifiers The identifiers of the device to load encoder models for. Missing
   *     chipset identifiers are resolved from the database when a dataset is loaded.
   * @param databaseSupplier Returns a newly opened database to load encoder models from. Each load
   *     gets a new database from it and closes that database once the models are in memory. It may
   *     throw {@link SQLiteException} if the database cannot be opened.
   */
  /* package */ CodecDbDatasetLoader(
      DeviceIdentifiers deviceIdentifiers, Supplier<EmbeddedDatabase> databaseSupplier) {
    this.deviceIdentifiers = deviceIdentifiers;
    this.databaseSupplier = databaseSupplier;
  }

  /**
   * Loads a {@link CodecDbEncoderDataset} for the device identifiers provided during creation,
   * using the provided fallback provider for missing configurations.
   *
   * <p>The dataset includes encoding performance models relevant to the specified device, with the
   * following order of precedence:
   *
   * <ul>
   *   <li>Device-specific models
   *   <li>Chipset-specific models
   *   <li>Tier-specific models
   *   <li>Generic models
   * </ul>
   *
   * <p>If no loaded model satisfies the requested constraints, {@link
   * CodecDbEncoderDataset#findOptimalConfiguration} returns the result of the provided fallback
   * provider.
   *
   * @param fallbackProvider A function that is queried with the requested constraints when no model
   *     loaded for this device satisfies them. It returns the configuration to use instead, or
   *     {@code null} if it has none, in which case the query also returns {@code null}. Pass {@code
   *     constraints -> null} to disable the fallback.
   * @return A {@link CodecDbEncoderDataset} containing encoder-specific data.
   * @throws IOException if an error occurs while reading from the database.
   */
  public CodecDbEncoderDataset forEncoding(
      Function<VideoEncoderConstraints, @Nullable VideoEncoderConfiguration> fallbackProvider)
      throws IOException {
    ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel>
        fallbackModelsBuilder = ImmutableMap.builder();
    ImmutableMap.Builder<VideoEncoderConfiguration, VideoEncoderQualityModel> primaryModelsBuilder =
        ImmutableMap.builder();
    // Load all models into memory so that the database can be closed before returning.
    try (EmbeddedDatabase db = databaseSupplier.get()) {
      VideoEncoderConfigQualityModelLoader loader = new VideoEncoderConfigQualityModelLoader(db);

      // Resolve the chipset manufacturer and model if they are not already populated.
      DeviceIdentifiers resolvedDeviceIdentifiers = getResolvedDeviceIdentifiers(db);

      loader.loadGenericConfigModels(fallbackModelsBuilder);

      if (resolvedDeviceIdentifiers.tier != DeviceIdentifiers.DEVICE_TIER_UNSPECIFIED) {
        loader.loadConfigModelsForTier(fallbackModelsBuilder, resolvedDeviceIdentifiers.tier);
      }
      if ((resolvedDeviceIdentifiers.chipsetManufacturer != null
              && resolvedDeviceIdentifiers.chipsetModel != null)
          || resolvedDeviceIdentifiers.deviceType != null) {
        loader.loadFallbackConfigModelsForChipset(fallbackModelsBuilder, resolvedDeviceIdentifiers);
        loader.loadConfigModelsForChipset(primaryModelsBuilder, resolvedDeviceIdentifiers);
      }
      if (resolvedDeviceIdentifiers.deviceType != null) {
        loader.loadConfigModelsForDevice(
            primaryModelsBuilder, resolvedDeviceIdentifiers.deviceType);
      }
    } catch (SQLiteException e) {
      throw new IOException("Unable to read the CodecDB database.", e);
    }

    CodecDbEncoderDataset fallbackDataset =
        new CodecDbEncoderDataset(
            fallbackModelsBuilder.buildKeepingLast(),
            fallbackProvider,
            /* qualityFallbackProvider= */ configuration -> Double.NaN);

    return new CodecDbEncoderDataset(
        primaryModelsBuilder.buildKeepingLast(),
        fallbackDataset::findOptimalConfiguration,
        fallbackDataset::predictQualityFor);
  }

  /**
   * Returns a {@link DeviceIdentifiers} instance that is guaranteed to have the chipset
   * manufacturer and model populated if they can be resolved from the database.
   *
   * @param db The open database to resolve the chipset from.
   * @throws IOException if an error occurs while reading from the database.
   */
  /* package */ DeviceIdentifiers getResolvedDeviceIdentifiers(EmbeddedDatabase db)
      throws IOException {
    if (deviceIdentifiers.chipsetManufacturer != null && deviceIdentifiers.chipsetModel != null) {
      return deviceIdentifiers;
    }

    Long chipsetId = db.chipsetDao.findChipsetIdForDeviceIdentifiers(deviceIdentifiers);
    if (chipsetId != null) {
      EmbeddedDatabase.Chipset.Model chipset = db.chipsetDao.findChipsetById(chipsetId);
      if (chipset != null) {
        return deviceIdentifiers
            .buildUpon()
            .setChipsetManufacturer(chipset.manufacturer)
            .setChipsetModel(chipset.model)
            .build();
      }
    }
    return deviceIdentifiers;
  }
}
