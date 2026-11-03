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

import static com.google.common.base.Preconditions.checkNotNull;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import androidx.media3.transformer.codecdb.DeviceIdentifiers.DeviceTier;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.ArrayList;
import org.jspecify.annotations.Nullable;

/**
 * {@link EmbeddedDatabase} that leverages the Android Platform's built-in SQLite APIs.
 *
 * <p>When running on-device, this implementation should be used.
 */
/* package */ final class AndroidEmbeddedDatabase extends EmbeddedDatabase {

  private final SQLiteDatabase db;

  /* package */ AndroidEmbeddedDatabase(SQLiteDatabase db) {
    super(
        new AndroidCodecDao(db),
        new AndroidDatabaseMetadataDao(db),
        new AndroidDeviceDao(db),
        new AndroidTierDao(db),
        new AndroidChipsetDao(db),
        new AndroidVideoEncoderQualityModelParametersDao(db),
        new AndroidVideoEncoderSettingsDao(db),
        new AndroidVideoEncoderSettingsExtensionDao(db),
        new AndroidFallbackCodecDao(db));
    this.db = db;
  }

  /** Closes the underlying {@link SQLiteDatabase}. */
  @Override
  public void close() {
    db.close();
  }

  /** Provides data access to the Tier table of the CodecDB database. */
  private static class AndroidTierDao implements Tier.Dao {

    private static final String TABLE_NAME = "Tier";

    private final SQLiteDatabase db;

    private AndroidTierDao(SQLiteDatabase db) {
      this.db = db;
    }

    /** Returns the integer ID for the specified tier if it exists, otherwise returns null. */
    @Override
    public @Nullable Long findTierIdForTier(@DeviceTier int tier) {
      String[] cols = {TIER_ID_COLUMN};
      String[] params = {DeviceIdentifiers.toDeviceTierName(tier)};
      try (Cursor cursor =
          db.query(
              /* table= */ TABLE_NAME,
              /* columns= */ cols,
              /* selection= */ NAME_COLUMN + "=?",
              /* selectionArgs= */ params,
              /* groupBy= */ null,
              /* having= */ null,
              /* orderBy= */ null)) {
        if (cursor.moveToNext()) {
          return cursor.getLong(cursor.getColumnIndexOrThrow(TIER_ID_COLUMN));
        }
      }

      return null;
    }
  }

  private static class AndroidFallbackCodecDao implements FallbackCodec.Dao {
    private static final String TABLE_NAME = "FallbackCodec";

    private final SQLiteDatabase db;

    private AndroidFallbackCodecDao(SQLiteDatabase db) {
      this.db = db;
    }

    /**
     * Returns the list of codec IDs for the specified fallback codec.
     *
     * @param chipsetId the database ID for the chipset to look for.
     */
    @Override
    public ImmutableList<Long> findFallbackCodecIdsForChipset(long chipsetId) throws IOException {
      String[] cols = {CODEC_ID_COLUMN};

      try (Cursor cursor =
          db.query(
              /* table= */ TABLE_NAME,
              /* columns= */ cols,
              /* selection= */ CHIPSET_ID_COLUMN + "=" + chipsetId,
              /* selectionArgs= */ null,
              /* groupBy= */ null,
              /* having= */ null,
              /* orderBy= */ null)) {
        return extactResultsFromCursor(cursor);
      }
    }

    private static ImmutableList<Long> extactResultsFromCursor(Cursor cursor) {
      ImmutableList.Builder<Long> results =
          ImmutableList.builderWithExpectedSize(cursor.getCount());

      int codecIdColumnIndex = cursor.getColumnIndexOrThrow(CODEC_ID_COLUMN);

      while (cursor.moveToNext()) {
        results.add(cursor.getLong(codecIdColumnIndex));
      }
      return results.build();
    }
  }

  private static class AndroidDatabaseMetadataDao implements DatabaseMetadata.Dao {

    private static final String TABLE_NAME = "DatabaseMetadata";

    private final SQLiteDatabase db;

    private AndroidDatabaseMetadataDao(SQLiteDatabase db) {
      this.db = db;
    }

    @Override
    public long getForkTime() {
      String[] cols = {FORK_TIME_COLUMN};
      try (Cursor cursor =
          db.query(
              /* table= */ TABLE_NAME,
              /* columns= */ cols,
              /* selection= */ null,
              /* selectionArgs= */ null,
              /* groupBy= */ null,
              /* having= */ null,
              /* orderBy= */ null)) {
        if (cursor.moveToNext()) {
          return cursor.getLong(cursor.getColumnIndexOrThrow(FORK_TIME_COLUMN));
        }
      } catch (SQLiteException sqle) {
        // Do nothing.
        //
        // If we can't identify the version of the database, just return a sentinel value.
      }
      return FORK_TIME_UNKNOWN;
    }
  }

  /** Provides data access to the Device table of the CodecDB database. */
  private static class AndroidDeviceDao implements Device.Dao {

    private static final String TABLE_NAME = "Device";

    private final SQLiteDatabase db;

    private AndroidDeviceDao(SQLiteDatabase db) {
      this.db = db;
    }

    /** Returns the integer ID of the specified device if it exists, otherwise returns null. */
    @Override
    public @Nullable Long findDeviceIdForType(String type) {
      String[] cols = {DEVICE_ID_COLUMN};
      String[] params = {type};
      try (Cursor cursor =
          db.query(
              /* table= */ TABLE_NAME,
              /* columns= */ cols,
              /* selection= */ TYPE_COLUMN + "=?",
              /* selectionArgs= */ params,
              /* groupBy= */ null,
              /* having= */ null,
              /* orderBy= */ null)) {
        if (cursor.moveToNext()) {
          return cursor.getLong(cursor.getColumnIndexOrThrow(DEVICE_ID_COLUMN));
        }
      }

      return null;
    }
  }

  /** Provides data access to the Codec identifiers within the CodecDB database. */
  private static class AndroidCodecDao implements Codec.Dao {

    private static final String TABLE_NAME = "Codec";
    private static final String[] allColumns = {
      CODEC_ID_COLUMN,
      TIER_ID_COLUMN,
      CHIPSET_ID_COLUMN,
      DEVICE_ID_COLUMN,
      CANONICAL_NAME_COLUMN,
      MIME_COLUMN
    };

    private final SQLiteDatabase db;

    private AndroidCodecDao(SQLiteDatabase db) {
      this.db = db;
    }

    private static ImmutableList<Codec.Model> extractResultsFromCursor(
        Cursor cursor, boolean isFallback) {
      ImmutableList.Builder<Codec.Model> results =
          ImmutableList.builderWithExpectedSize(cursor.getCount());

      int codecIdColumnIndex = cursor.getColumnIndexOrThrow(CODEC_ID_COLUMN);
      int tierIdColumnIndex = cursor.getColumnIndexOrThrow(TIER_ID_COLUMN);
      int chipsetIdColumnIndex = cursor.getColumnIndexOrThrow(CHIPSET_ID_COLUMN);
      int deviceIdColumnIndex = cursor.getColumnIndexOrThrow(DEVICE_ID_COLUMN);
      int canonicalNameColumnIndex = cursor.getColumnIndexOrThrow(CANONICAL_NAME_COLUMN);
      int mimeColumnIndex = cursor.getColumnIndexOrThrow(MIME_COLUMN);

      while (cursor.moveToNext()) {
        results.add(
            new Codec.Model(
                cursor.getLong(codecIdColumnIndex),
                cursor.isNull(tierIdColumnIndex) ? null : cursor.getLong(tierIdColumnIndex),
                cursor.isNull(chipsetIdColumnIndex) ? null : cursor.getLong(chipsetIdColumnIndex),
                cursor.isNull(deviceIdColumnIndex) ? null : cursor.getLong(deviceIdColumnIndex),
                cursor.isNull(canonicalNameColumnIndex)
                    ? null
                    : cursor.getString(canonicalNameColumnIndex),
                cursor.isNull(mimeColumnIndex) ? null : cursor.getString(mimeColumnIndex),
                isFallback));
      }

      return results.build();
    }

    /**
     * Finds all Codecs in the CodecDB database that are wholly generic. That is, they do not
     * correspond to a specific device, chipset, or tier.
     *
     * <p>These codecs are the "fallback" codecs in the case that a given device cannot be
     * identified.
     */
    @Override
    public ImmutableList<Codec.Model> findGenericCodecs() {
      try (Cursor cursor =
          db.query(
              /* table= */ TABLE_NAME,
              /* columns= */ allColumns,
              /* selection= */ DEVICE_ID_COLUMN
                  + " IS NULL AND "
                  + CHIPSET_ID_COLUMN
                  + " IS NULL AND "
                  + TIER_ID_COLUMN
                  + " IS NULL",
              /* selectionArgs= */ null,
              /* groupBy= */ null,
              /* having= */ null,
              /* orderBy= */ null)) {
        return extractResultsFromCursor(cursor, /* isFallback= */ false);
      }
    }

    /**
     * Finds all Codecs in the CodecDB database that represent a generic-codec for a given tier of
     * device (e.g. the fallback codec for all high-end devices).
     *
     * @param tierId the database ID for the tier to look for.
     */
    @Override
    public ImmutableList<Codec.Model> findCodecsForTier(long tierId) {
      try (Cursor cursor =
          db.query(
              /* table= */ TABLE_NAME,
              /* columns= */ allColumns,
              /* selection= */ DEVICE_ID_COLUMN
                  + " IS NULL AND "
                  + CHIPSET_ID_COLUMN
                  + " IS NULL AND "
                  + TIER_ID_COLUMN
                  + "="
                  + tierId,
              /* selectionArgs= */ null,
              /* groupBy= */ null,
              /* having= */ null,
              /* orderBy= */ null)) {
        return extractResultsFromCursor(cursor, /* isFallback= */ false);
      }
    }

    /**
     * Find all Codecs in the CodecDB database that represent the codecs for a specific chipset.
     *
     * @param chipsetId the database ID for the chipset to look for.
     */
    @Override
    public ImmutableList<Codec.Model> findCodecsForChipset(long chipsetId) {
      try (Cursor cursor =
          db.query(
              /* table= */ TABLE_NAME,
              /* columns= */ allColumns,
              /* selection= */ DEVICE_ID_COLUMN
                  + " IS NULL AND "
                  + CHIPSET_ID_COLUMN
                  + "="
                  + chipsetId,
              /* selectionArgs= */ null,
              /* groupBy= */ null,
              /* having= */ null,
              /* orderBy= */ null)) {
        return extractResultsFromCursor(cursor, /* isFallback= */ false);
      }
    }

    /**
     * Loads all Codecs in the CodecDB database that represent the fallback codecs for specific
     * codecIds.
     *
     * @param codecIds the list of codecIds to look for.
     */
    @Override
    public ImmutableList<Codec.Model> loadFallbackCodecsForCodecIds(ImmutableList<Long> codecIds) {
      if (codecIds.isEmpty()) {
        return ImmutableList.of();
      }
      StringBuilder selection = new StringBuilder();

      selection.append(CODEC_ID_COLUMN);
      selection.append(" IN (");
      for (Long codecId : codecIds) {
        selection.append(codecId + ", ");
      }
      selection.deleteCharAt(selection.length() - 2);
      selection.append(")");
      try (Cursor cursor =
          db.query(
              /* table= */ TABLE_NAME,
              /* columns= */ allColumns,
              /* selection= */ selection.toString(),
              /* selectionArgs= */ null,
              /* groupBy= */ null,
              /* having= */ null,
              /* orderBy= */ null)) {
        return extractResultsFromCursor(cursor, /* isFallback= */ true);
      }
    }

    /**
     * Finds all Codecs in the CodecDB database that represent the codecs for a specific device.
     *
     * @param deviceId the database ID for the device to look for.
     */
    @Override
    public ImmutableList<Codec.Model> findCodecsForDevice(long deviceId) {
      try (Cursor cursor =
          db.query(
              /* table= */ TABLE_NAME,
              /* columns= */ allColumns,
              /* selection= */ DEVICE_ID_COLUMN + "=" + deviceId,
              /* selectionArgs= */ null,
              /* groupBy= */ null,
              /* having= */ null,
              /* orderBy= */ null)) {
        return extractResultsFromCursor(cursor, false);
      }
    }
  }

  /** Provides data access to the Chipset table of the CodecDB database. */
  private static class AndroidChipsetDao implements Chipset.Dao {

    private static final String TABLE_NAME = "Chipset";

    private final SQLiteDatabase db;

    private AndroidChipsetDao(SQLiteDatabase db) {
      this.db = db;
    }

    /** Returns the integer ID for the specified chipset if it exists, otherwise returns null. */
    @Override
    public @Nullable Long findChipsetIdForChipset(String manufacturer, String model) {
      String[] cols = {CHIPSET_ID_COLUMN};
      String[] params = {manufacturer, model};
      try (Cursor cursor =
          db.query(
              /* table= */ TABLE_NAME,
              /* columns= */ cols,
              /* selection= */ MANUFACTURER_COLUMN + "=" + "? AND " + MODEL_COLUMN + "=?",
              /* selectionArgs= */ params,
              /* groupBy= */ null,
              /* having= */ null,
              /* orderBy= */ null)) {
        if (cursor.moveToNext()) {
          return cursor.getLong(cursor.getColumnIndexOrThrow(CHIPSET_ID_COLUMN));
        }
      }

      return null;
    }

    @Override
    public @Nullable Long findChipsetIdForDeviceIdentifiers(DeviceIdentifiers identifiers)
        throws IOException {

      StringBuilder queryBuilder =
          new StringBuilder(
              "SELECT C.ChipsetId, D.FingerprintHash FROM Chipset C LEFT JOIN Device D ON"
                  + " C.ChipsetId=D.ChipsetId WHERE ");
      ArrayList<String> params = new ArrayList<>(3);

      boolean hasDeviceType = identifiers.deviceType != null;
      if (identifiers.deviceType != null) {
        queryBuilder.append("D.Type=?");
        params.add(identifiers.deviceType);
      }

      if (identifiers.chipsetModel != null && identifiers.chipsetManufacturer != null) {
        if (hasDeviceType) {
          queryBuilder.append(" OR ");
        }
        queryBuilder.append("(C.Manufacturer=? AND C.Model=?)");
        params.add(identifiers.chipsetManufacturer);
        params.add(identifiers.chipsetModel);
      } else if (identifiers.chipsetModel != null) {
        if (hasDeviceType) {
          queryBuilder.append(" OR ");
        }
        queryBuilder.append("C.Model=?");
        params.add(identifiers.chipsetModel);
      }

      Long bestMatch = null;
      try (Cursor cursor = db.rawQuery(queryBuilder.toString(), params.toArray(new String[0]))) {
        int chipsetIdIndex = cursor.getColumnIndexOrThrow(CHIPSET_ID_COLUMN);
        int fingerprintHashIndex = cursor.getColumnIndexOrThrow(Device.Dao.FINGERPRINT_HASH_COLUMN);

        while (cursor.moveToNext()) {
          long chipsetId = cursor.getLong(chipsetIdIndex);
          Long fingerprintHash =
              cursor.isNull(fingerprintHashIndex) ? null : cursor.getLong(fingerprintHashIndex);

          if (fingerprintHash != null
              && identifiers.fingerprintHash != DeviceIdentifiers.FINGERPRINT_HASH_UNSET
              && fingerprintHash.equals(identifiers.fingerprintHash)) {
            return chipsetId;
          }

          if (fingerprintHash == null) {
            if (bestMatch == null) {
              bestMatch = chipsetId;
            }
          }
        }
      }
      return bestMatch;
    }

    @Override
    public ImmutableList<Chipset.Model> findAllChipsets() throws IOException {
      String[] cols = {CHIPSET_ID_COLUMN, TIER_ID_COLUMN, MANUFACTURER_COLUMN, MODEL_COLUMN};
      try (Cursor cursor =
          db.query(
              TABLE_NAME,
              cols,
              /* selection= */ null,
              /* selectionArgs= */ null,
              /* groupBy= */ null,
              /* having= */ null,
              /* orderBy= */ null)) {
        return extractResultsFromCursor(cursor);
      }
    }

    private ImmutableList<Chipset.Model> extractResultsFromCursor(Cursor cursor) {
      ImmutableList.Builder<Chipset.Model> results =
          ImmutableList.builderWithExpectedSize(cursor.getCount());

      int chipsetIdColumnIndex = cursor.getColumnIndexOrThrow(CHIPSET_ID_COLUMN);
      int tierIdColumnIndex = cursor.getColumnIndexOrThrow(TIER_ID_COLUMN);
      int manufacturerColumnIndex = cursor.getColumnIndexOrThrow(MANUFACTURER_COLUMN);
      int modelColumnIndex = cursor.getColumnIndexOrThrow(MODEL_COLUMN);

      while (cursor.moveToNext()) {
        results.add(
            new Chipset.Model(
                cursor.getLong(chipsetIdColumnIndex),
                cursor.getLong(tierIdColumnIndex),
                cursor.isNull(manufacturerColumnIndex)
                    ? null
                    : cursor.getString(manufacturerColumnIndex),
                cursor.isNull(modelColumnIndex) ? null : cursor.getString(modelColumnIndex)));
      }

      return results.build();
    }

    @Override
    public Chipset.@Nullable Model findChipsetById(long chipsetId) {
      String[] cols = {CHIPSET_ID_COLUMN, TIER_ID_COLUMN, MANUFACTURER_COLUMN, MODEL_COLUMN};
      try (Cursor cursor =
          db.query(
              /* table= */ TABLE_NAME,
              /* columns= */ cols,
              /* selection= */ CHIPSET_ID_COLUMN + "=" + chipsetId,
              /* selectionArgs= */ null,
              /* groupBy= */ null,
              /* having= */ null,
              /* orderBy= */ null)) {
        ImmutableList<Chipset.Model> results = extractResultsFromCursor(cursor);
        if (results.isEmpty()) {
          return null;
        }
        return results.get(0);
      }
    }
  }

  /**
   * Provides data access to the VideoEncoderQualityModelParameters table in the CodecDB database.
   */
  private static class AndroidVideoEncoderQualityModelParametersDao
      implements VideoEncoderQualityModelParameters.Dao {

    private static final String TABLE_NAME = "VideoEncoderQualityModelParameters";

    private static final String[] allColumns = {
      CODEC_ID_COLUMN,
      VIDEO_ENCODER_SETTINGS_ID_COLUMN,
      FRAME_PIXELS_COLUMN,
      COEFFICIENT_A_COLUMN,
      COEFFICIENT_B_COLUMN,
      MIN_VMAF_COLUMN,
      MIN_BITRATE_KBPS_COLUMN
    };

    private final SQLiteDatabase db;

    private AndroidVideoEncoderQualityModelParametersDao(SQLiteDatabase db) {
      this.db = db;
    }

    private static ImmutableList<VideoEncoderQualityModelParameters.Model> extractResultsFromCursor(
        Cursor cursor) {
      ImmutableList.Builder<VideoEncoderQualityModelParameters.Model> results =
          ImmutableList.builderWithExpectedSize(cursor.getCount());

      int codecIdColumnIndex = cursor.getColumnIndexOrThrow(CODEC_ID_COLUMN);
      int videoEncoderSettingsIdColumnIndex =
          cursor.getColumnIndexOrThrow(VIDEO_ENCODER_SETTINGS_ID_COLUMN);
      int framePixelsColumnIndex = cursor.getColumnIndexOrThrow(FRAME_PIXELS_COLUMN);
      int coefficientAColumnIndex = cursor.getColumnIndexOrThrow(COEFFICIENT_A_COLUMN);
      int coefficientBColumnIndex = cursor.getColumnIndexOrThrow(COEFFICIENT_B_COLUMN);
      int minVmafColumnIndex = cursor.getColumnIndexOrThrow(MIN_VMAF_COLUMN);
      int minBitrateKbpsColumnIndex = cursor.getColumnIndexOrThrow(MIN_BITRATE_KBPS_COLUMN);

      while (cursor.moveToNext()) {
        results.add(
            new VideoEncoderQualityModelParameters.Model(
                cursor.getLong(codecIdColumnIndex),
                cursor.getLong(videoEncoderSettingsIdColumnIndex),
                cursor.getInt(framePixelsColumnIndex),
                cursor.getDouble(coefficientAColumnIndex),
                cursor.getDouble(coefficientBColumnIndex),
                cursor.getDouble(minVmafColumnIndex),
                cursor.getDouble(minBitrateKbpsColumnIndex)));
      }
      return results.build();
    }

    @Override
    public ImmutableList<VideoEncoderQualityModelParameters.Model>
        findSettingsOrderedVideoEncoderQualityModelParametersForCodec(long codecId) {
      try (Cursor cursor =
          db.query(
              /* table= */ TABLE_NAME,
              /* columns= */ allColumns,
              /* selection= */ CODEC_ID_COLUMN + "=" + codecId,
              /* selectionArgs= */ null,
              /* groupBy= */ null,
              /* having= */ null,

              // Order by settings ID to make aggregation easier later.
              /* orderBy= */ VIDEO_ENCODER_SETTINGS_ID_COLUMN)) {
        return extractResultsFromCursor(cursor);
      }
    }
  }

  /** Provides data access to the VideoEncoderSettings table of the CodecDB database. */
  private static class AndroidVideoEncoderSettingsDao implements VideoEncoderSettings.Dao {

    private static final String TABLE_NAME = "VideoEncoderSettings";

    private static final String[] allColumns = {
      VIDEO_ENCODER_SETTINGS_ID_COLUMN,
      MAX_B_FRAMES_COLUMN,
      KEY_FRAME_INTERVAL_COLUMN,
      PROFILE_COLUMN,
      LEVEL_COLUMN
    };

    private final SQLiteDatabase db;

    private AndroidVideoEncoderSettingsDao(SQLiteDatabase db) {
      this.db = db;
    }

    private static ImmutableList<VideoEncoderSettings.Model> extractResultsFromCursor(
        Cursor cursor) {
      ImmutableList.Builder<VideoEncoderSettings.Model> results =
          ImmutableList.builderWithExpectedSize(cursor.getCount());

      int videoEncoderSettingsIdColumnIndex =
          cursor.getColumnIndexOrThrow(VIDEO_ENCODER_SETTINGS_ID_COLUMN);
      int maxBFramesColumnIndex = cursor.getColumnIndexOrThrow(MAX_B_FRAMES_COLUMN);
      int keyFrameIntervalColumnIndex = cursor.getColumnIndexOrThrow(KEY_FRAME_INTERVAL_COLUMN);
      int profileColumnIndex = cursor.getColumnIndexOrThrow(PROFILE_COLUMN);
      int levelColumnIndex = cursor.getColumnIndexOrThrow(LEVEL_COLUMN);

      while (cursor.moveToNext()) {
        results.add(
            new VideoEncoderSettings.Model(
                cursor.getLong(videoEncoderSettingsIdColumnIndex),
                cursor.getInt(maxBFramesColumnIndex),
                cursor.getDouble(keyFrameIntervalColumnIndex),
                cursor.getInt(profileColumnIndex),
                cursor.getInt(levelColumnIndex)));
      }

      return results.build();
    }

    /**
     * Finds a VideoEncoderSettings entry by its VideoEncoderSettingsId (primary key). Returns null
     * if no VideoEncoderSettings exists with the provided id.
     */
    @Override
    public EmbeddedDatabase.VideoEncoderSettings.@Nullable Model findVideoEncoderSettingsById(
        long id) {
      try (Cursor cursor =
          db.query(
              /* table= */ TABLE_NAME,
              /* columns= */ allColumns,
              /* selection= */ VIDEO_ENCODER_SETTINGS_ID_COLUMN + "=" + id,
              /* selectionArgs= */ null,
              /* groupBy= */ null,
              /* having= */ null,
              /* orderBy= */ null)) {
        ImmutableList<VideoEncoderSettings.Model> results = extractResultsFromCursor(cursor);

        if (results.isEmpty()) {
          return null;
        }

        return results.get(0);
      }
    }
  }

  /** Provides data access to the VideoEncoderSettingsExtension table of the CodecDB database. */
  private static class AndroidVideoEncoderSettingsExtensionDao
      implements VideoEncoderSettingsExtension.Dao {

    private static final String TABLE_NAME = "VideoEncoderSettingsExtension";

    private static final String[] allColumns = {
      VIDEO_ENCODER_SETTINGS_ID_COLUMN, KEY_COLUMN, VALUE_COLUMN
    };

    private final SQLiteDatabase db;

    private AndroidVideoEncoderSettingsExtensionDao(SQLiteDatabase db) {
      this.db = db;
    }

    private static ImmutableList<VideoEncoderSettingsExtension.Model> extractResultsFromCursor(
        Cursor cursor) {
      ImmutableList.Builder<VideoEncoderSettingsExtension.Model> results =
          ImmutableList.builderWithExpectedSize(cursor.getCount());

      int videoEncoderSettingsIdColumnIndex =
          cursor.getColumnIndexOrThrow(VIDEO_ENCODER_SETTINGS_ID_COLUMN);
      int keyColumnIndex = cursor.getColumnIndexOrThrow(KEY_COLUMN);
      int valueColumnIndex = cursor.getColumnIndexOrThrow(VALUE_COLUMN);

      while (cursor.moveToNext()) {
        results.add(
            new VideoEncoderSettingsExtension.Model(
                cursor.getLong(videoEncoderSettingsIdColumnIndex),
                checkNotNull(cursor.getString(keyColumnIndex)),
                checkNotNull(cursor.getString(valueColumnIndex))));
      }

      return results.build();
    }

    @Override
    public ImmutableList<VideoEncoderSettingsExtension.Model>
        findVideoEncoderSettingsExtensionsByVideoEncoderSettingsId(long videoEncoderSettingsId) {
      try (Cursor cursor =
          db.query(
              /* table= */ TABLE_NAME,
              /* columns= */ allColumns,
              /* selection= */ VIDEO_ENCODER_SETTINGS_ID_COLUMN + "=" + videoEncoderSettingsId,
              /* selectionArgs= */ null,
              /* groupBy= */ null,
              /* having= */ null,
              /* orderBy= */ null)) {
        return extractResultsFromCursor(cursor);
      }
    }
  }
}
