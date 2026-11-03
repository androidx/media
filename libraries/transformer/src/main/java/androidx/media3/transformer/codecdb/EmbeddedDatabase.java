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

import androidx.media3.transformer.codecdb.DeviceIdentifiers.DeviceTier;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.Codec.Dao;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import java.io.Closeable;
import java.io.IOException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Provides an abstraction over CodecDB's embedded SQLite Database.
 *
 * <p>The classes nested in this class each represent a table or table join within the CodecDB
 * library-embedded SQLite database. Within each "table" class are models that are mapped to the
 * fields within that table or query as well as the interfaces that define the DAOs used by the rest
 * of the library.
 */
/* package */ abstract class EmbeddedDatabase implements Closeable {

  public final Codec.Dao codecDao;

  public final DatabaseMetadata.Dao databaseMetadataDao;
  public final Device.Dao deviceDao;
  public final Tier.Dao tierDao;
  public final Chipset.Dao chipsetDao;

  public final VideoEncoderQualityModelParameters.Dao videoEncoderQualityModelParametersDao;

  public final VideoEncoderSettings.Dao videoEncoderSettingsDao;

  public final VideoEncoderSettingsExtension.Dao videoEncoderSettingsExtensionDao;

  public final FallbackCodec.Dao fallbackCodecDao;

  /* package */ EmbeddedDatabase(
      Dao codecDao,
      DatabaseMetadata.Dao databaseMetadataDao,
      Device.Dao deviceDao,
      Tier.Dao tierDao,
      Chipset.Dao chipsetDao,
      VideoEncoderQualityModelParameters.Dao videoEncoderQualityModelParametersDao,
      VideoEncoderSettings.Dao videoEncoderSettingsDao,
      VideoEncoderSettingsExtension.Dao videoEncoderSettingsExtensionDao,
      FallbackCodec.Dao fallbackCodecDao) {
    this.codecDao = codecDao;
    this.databaseMetadataDao = databaseMetadataDao;
    this.deviceDao = deviceDao;
    this.tierDao = tierDao;
    this.chipsetDao = chipsetDao;
    this.videoEncoderQualityModelParametersDao = videoEncoderQualityModelParametersDao;

    this.videoEncoderSettingsDao = videoEncoderSettingsDao;
    this.videoEncoderSettingsExtensionDao = videoEncoderSettingsExtensionDao;
    this.fallbackCodecDao = fallbackCodecDao;
  }

  /**
   * Releases any resources held by this database. The DAOs must not be used after this method is
   * called.
   */
  @Override
  public abstract void close();

  /** Represents the Codec table of the CodecDB library-embedded SQLite Database. */
  public static final class Codec {
    private Codec() {}

    /** Facilitates data access to the Codec table of the Android CodecDB embedded database. */
    public interface Dao {
      String CODEC_ID_COLUMN = "CodecId";
      String TIER_ID_COLUMN = "TierId";
      String CHIPSET_ID_COLUMN = "ChipsetId";
      String DEVICE_ID_COLUMN = "DeviceId";
      String CANONICAL_NAME_COLUMN = "CanonicalName";
      String MIME_COLUMN = "Mime";

      /**
       * Finds all Codecs in the CodecDB database that are wholly generic. That is, they do not
       * correspond to a specific device, chipset, or tier.
       *
       * <p>These codecs are the "fallback" codecs in the case that a given device cannot be
       * identified.
       *
       * @throws IOException if any errors occur while attempting to read from the database.
       */
      ImmutableList<Codec.Model> findGenericCodecs() throws IOException;

      /**
       * Finds all Codecs in the CodecDB database that represent a generic-codec for a given tier of
       * device (e.g. the fallback codec for all high-end devices).
       *
       * @param tierId the database ID for the tier to look for.
       * @throws IOException if any errors occur while attempting to read from the database.
       */
      ImmutableList<Codec.Model> findCodecsForTier(long tierId) throws IOException;

      /**
       * Find all Codecs in the CodecDB database that represent the codecs for a specific chipset.
       *
       * @param chipsetId the database ID for the chipset to look for.
       * @throws IOException if any errors occur while attempting to read from the database.
       */
      ImmutableList<Codec.Model> findCodecsForChipset(long chipsetId) throws IOException;

      /**
       * Loads all Codecs in the CodecDB database that represent the fallback codecs for a list of
       * codecIds.
       *
       * @param codecIds the list of codec IDs to look for.
       * @throws IOException if any errors occur while attempting to read from the database.
       */
      ImmutableList<Codec.Model> loadFallbackCodecsForCodecIds(ImmutableList<Long> codecIds)
          throws IOException;

      /**
       * Finds all Codecs in the CodecDB database that represent the codecs for a specific device.
       *
       * @param deviceId the database ID for the device to look for.
       * @throws IOException if any errors occur while attempting to read from the database.
       */
      ImmutableList<Codec.Model> findCodecsForDevice(long deviceId) throws IOException;
    }

    /** POJO mapped to the columns of the Codec table. */
    public static final class Model {

      /** Primary key. */
      public final long codecId;

      /** Nullable foreign-key to the Tier table. */
      public final @Nullable Long tierId;

      /** Nullable foreign-key to the Chipset table. */
      public final @Nullable Long chipsetId;

      /** Nullable foreign-key to the Device table. */
      public final @Nullable Long deviceId;

      /** The "canonical name" of the Android MediaCodec described by this entity. */
      public final @Nullable String canonicalName;

      /** The MIME type of the Android MediaCodec described by this entity. */
      public final @Nullable String mime;

      /** Whether or not this codec is a fallback codec. */
      public final boolean isFallback;

      public Model(
          long codecId,
          @Nullable Long tierId,
          @Nullable Long chipsetId,
          @Nullable Long deviceId,
          @Nullable String canonicalName,
          @Nullable String mime,
          boolean isFallback) {
        this.codecId = codecId;
        this.tierId = tierId;
        this.chipsetId = chipsetId;
        this.deviceId = deviceId;
        this.canonicalName = canonicalName;
        this.mime = mime;
        this.isFallback = isFallback;
      }

      public Model(
          long codecId,
          @Nullable Long tierId,
          @Nullable Long chipsetId,
          @Nullable Long deviceId,
          @Nullable String canonicalName,
          @Nullable String mime) {
        this.codecId = codecId;
        this.tierId = tierId;
        this.chipsetId = chipsetId;
        this.deviceId = deviceId;
        this.canonicalName = canonicalName;
        this.mime = mime;
        this.isFallback = false;
      }

      @Override
      public boolean equals(@Nullable Object o) {
        if (this == o) {
          return true;
        }
        if (!(o instanceof Model)) {
          return false;
        }
        Model model = (Model) o;
        return codecId == model.codecId
            && Objects.equals(tierId, model.tierId)
            && Objects.equals(chipsetId, model.chipsetId)
            && Objects.equals(deviceId, model.deviceId)
            && Objects.equals(canonicalName, model.canonicalName)
            && Objects.equals(mime, model.mime)
            && isFallback == model.isFallback;
      }

      @Override
      public int hashCode() {
        return Objects.hash(codecId, tierId, chipsetId, deviceId, canonicalName, mime, isFallback);
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("codecId", codecId)
            .add("tierId", tierId)
            .add("chipsetId", chipsetId)
            .add("deviceId", deviceId)
            .add("canonicalName", canonicalName)
            .add("mime", mime)
            .add("isFallback", isFallback)
            .toString();
      }
    }
  }

  /** Represents the DatabaseMetadata table of the CodecDB library-embedded SQLite Database. */
  public static final class DatabaseMetadata {
    private DatabaseMetadata() {}

    /** Facilitates data access to the DatabaseMetadata table. */
    public interface Dao {

      /** Sentinel value to indicate that the fork-time of the accessed database is unknown. */
      long FORK_TIME_UNKNOWN = -1;

      String FORK_TIME_COLUMN = "ForkTimeSeconds";

      /**
       * Returns the time (in-seconds) that the CodecDB embedded database was forked from the
       * primary database. This serves as a "version" for the database and is used to determine
       * whether or not the unpacked version of the database is the same as the bundled copy.
       *
       * <p>If a version cannot be identified, returns <code>-1</code>.
       */
      long getForkTime();
    }
  }

  /** Represents the Device table of the CodecDB library-embedded SQLite Database. */
  public static final class Device {
    private Device() {}

    /** Facilitates data access to the Device table of the Android CodecDB embedded database. */
    public interface Dao {

      String DEVICE_ID_COLUMN = "DeviceId";

      String TYPE_COLUMN = "Type";

      String FINGERPRINT_HASH_COLUMN = "FingerprintHash";

      /**
       * Returns the integer ID of the specified device if it exists, otherwise returns null.
       *
       * @param type the "product" of the device (i.e. the second component of a traditional Android
       *     build fingerprint).
       * @throws IOException if any errors occur while attempting to read from the database.
       */
      @Nullable Long findDeviceIdForType(String type) throws IOException;
    }
  }

  /** Represents the Tier table of the CodecDB library-embedded SQLite Database. */
  public static final class Tier {
    private Tier() {}

    /** Facilitates data access to the Tier table of the Android CodecDB embedded database. */
    public interface Dao {

      String TIER_ID_COLUMN = "TierId";

      String NAME_COLUMN = "Name";

      /**
       * Returns the integer ID for the specified tier if it exists, otherwise returns null.
       *
       * @param tier the enum value of the tier (see {@link DeviceTier} for definitions.)
       * @throws IOException if any errors occur while attempting to read from the database.
       */
      @Nullable Long findTierIdForTier(@DeviceTier int tier) throws IOException;
    }
  }

  /** Represents the FallbackCodec table of the CodecDB library-embedded SQLite Database. */
  public static final class FallbackCodec {
    private FallbackCodec() {}

    /**
     * Facilitates data access to the FallbackCodec table of the Android CodecDB embedded database.
     */
    public interface Dao {

      String CODEC_ID_COLUMN = "CodecId";

      String CHIPSET_ID_COLUMN = "ChipsetId";

      /**
       * Returns the List of codec IDs for the specified chipset.
       *
       * @param chipsetId the database ID for the chipset to look for.
       * @throws IOException if any errors occur while attempting to read from the database.
       */
      ImmutableList<Long> findFallbackCodecIdsForChipset(long chipsetId) throws IOException;
    }
  }

  /** Represents the Chipset table of the CodecDB library-embedded SQLite Database. */
  public static final class Chipset {

    private Chipset() {}

    /** Facilitates data access to the Chipset table of the Android CodecDB embedded database. */
    public interface Dao {

      String CHIPSET_ID_COLUMN = "ChipsetId";

      String TIER_ID_COLUMN = "TierId";

      String MANUFACTURER_COLUMN = "Manufacturer";

      String MODEL_COLUMN = "Model";

      /**
       * Returns the integer ID for the specified chipset if it exists, otherwise returns null.
       *
       * @param manufacturer the manufacturer of the SoC as identified in the {@code
       *     Build.SOC_MANUFACTURER} Android build constant.
       * @param model the model of the SoC as identified in the {@code Build.SOC_MODEL} Android
       *     build constant.
       * @throws IOException if any errors occur while attempting to read from the database.
       */
      @Nullable Long findChipsetIdForChipset(String manufacturer, String model) throws IOException;

      @Nullable Long findChipsetIdForDeviceIdentifiers(DeviceIdentifiers identifiers)
          throws IOException;

      /**
       * Returns an {@link ImmutableList} of all chipsets in the CodecDB database.
       *
       * @throws IOException if any errors occur while attempting to read from the database.
       */
      ImmutableList<Chipset.Model> findAllChipsets() throws IOException;

      /**
       * Returns the {@link Chipset.Model} for the specified chipset ID if it exists, otherwise
       * returns null.
       *
       * @param chipsetId the database ID for the chipset to look for.
       * @throws IOException if any errors occur while attempting to read from the database.
       */
      Chipset.@Nullable Model findChipsetById(long chipsetId) throws IOException;
    }

    /** POJO mapped to the columns of the Chipset table. */
    public static final class Model {

      /** Primary key. */
      public final long chipsetId;

      /** Foreign-key to the Tier table. */
      public final long tierId;

      /**
       * The name of the manufacturer of the chipset, in > API 31 devices, this corresponds to the
       * Build.SOC_MANUFACTURER build constant.
       */
      public final @Nullable String manufacturer;

      /**
       * The name of the model of the chipset, in > API 31 devices, this corresponds to the
       * Build.SOC_MODEL build constant.
       */
      public final @Nullable String model;

      public Model(
          long chipsetId, long tierId, @Nullable String manufacturer, @Nullable String model) {
        this.chipsetId = chipsetId;
        this.tierId = tierId;
        this.manufacturer = manufacturer;
        this.model = model;
      }

      @Override
      public boolean equals(@Nullable Object o) {
        if (this == o) {
          return true;
        }
        if (!(o instanceof Model)) {
          return false;
        }
        Model model1 = (Model) o;
        return chipsetId == model1.chipsetId
            && tierId == model1.tierId
            && Objects.equals(manufacturer, model1.manufacturer)
            && Objects.equals(model, model1.model);
      }

      @Override
      public int hashCode() {
        return Objects.hash(chipsetId, tierId, manufacturer, model);
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("chipsetId", chipsetId)
            .add("tierId", tierId)
            .add("manufacturer", manufacturer)
            .add("model", model)
            .toString();
      }
    }
  }

  /**
   * Represents the VideoEncoderQualityModelParameters table of the CodecDB library-embedded SQLite
   * Database.
   */
  public static final class VideoEncoderQualityModelParameters {
    private VideoEncoderQualityModelParameters() {}

    /**
     * Facilitates data access to the VideoEncoderQualityModelsParameter table of the Android
     * CodecDB embedded database.
     */
    public interface Dao {

      String CODEC_ID_COLUMN = "CodecId";
      String VIDEO_ENCODER_SETTINGS_ID_COLUMN = "VideoEncoderSettingsId";
      String FRAME_PIXELS_COLUMN = "FramePixels";
      String COEFFICIENT_A_COLUMN = "CoefficientA";
      String COEFFICIENT_B_COLUMN = "CoefficientB";
      String MIN_VMAF_COLUMN = "MinVmaf";
      String MIN_BITRATE_KBPS_COLUMN = "MinBitrateKbps";

      /**
       * Returns the quality-model parameters for the codec having the specified id in the embedded
       * database ordered by the ID for the settings used to produce this model.
       *
       * <p>The ordering is a convenience to help in the collection of the results at a higher-level
       * and is guaranteed.
       */
      ImmutableList<VideoEncoderQualityModelParameters.Model>
          findSettingsOrderedVideoEncoderQualityModelParametersForCodec(long codecId)
              throws IOException;
    }

    /** POJO mapped to the columns of the VideoEncoderQualityModelParameters table. */
    public static final class Model {

      /** Foreign-key to the Codec table. */
      public final long codecId;

      /** Foreign-key to the VideoEncoderSettings table. */
      public final long videoEncoderSettingsId;

      /** The total numbers of pixels-per-frame-per-second (w x h x fps) for this quality model. */
      public final int framePixels;

      /** The first coefficient for the CodecDB PCI rate-distortion algorithm. */
      public final double coefficientA;

      /** The second coefficient for the CodecDB PCI rate-distortion algorithm. */
      public final double coefficientB;

      /**
       * The minimum observed VMAF score for this model, used to define a lower prediction bound.
       */
      public final double minVmaf;

      /** The minimum observed bitrate for this model in kbps, used to define its bounds. */
      public final double minBitrateKbps;

      public Model(
          long codecId,
          long videoEncoderSettingsId,
          int framePixels,
          double coefficientA,
          double coefficientB,
          double minVmaf,
          double minBitrateKbps) {
        this.codecId = codecId;
        this.videoEncoderSettingsId = videoEncoderSettingsId;
        this.framePixels = framePixels;
        this.coefficientA = coefficientA;
        this.coefficientB = coefficientB;
        this.minVmaf = minVmaf;
        this.minBitrateKbps = minBitrateKbps;
      }

      @Override
      public boolean equals(@Nullable Object o) {
        if (this == o) {
          return true;
        }
        if (!(o instanceof Model)) {
          return false;
        }
        Model model = (Model) o;
        return codecId == model.codecId
            && videoEncoderSettingsId == model.videoEncoderSettingsId
            && framePixels == model.framePixels
            && Double.compare(model.coefficientA, coefficientA) == 0
            && Double.compare(model.coefficientB, coefficientB) == 0
            && Double.compare(model.minVmaf, minVmaf) == 0
            && Double.compare(model.minBitrateKbps, minBitrateKbps) == 0;
      }

      @Override
      public int hashCode() {
        return Objects.hash(
            codecId,
            videoEncoderSettingsId,
            framePixels,
            coefficientA,
            coefficientB,
            minVmaf,
            minBitrateKbps);
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("codecId", codecId)
            .add("videoEncoderSettingsId", videoEncoderSettingsId)
            .add("framePixels", framePixels)
            .add("coefficientA", coefficientA)
            .add("coefficientB", coefficientB)
            .add("minVmaf", minVmaf)
            .add("minBitrateKbps", minBitrateKbps)
            .toString();
      }
    }
  }

  /** Represents the VideoEncoderSettings table of the CodecDB library-embedded SQLite Database. */
  public static final class VideoEncoderSettings {
    private VideoEncoderSettings() {}

    /**
     * Facilitates data access to the VideoEncoderSettings table of the Android CodecDB embedded
     * database.
     */
    public interface Dao {

      String VIDEO_ENCODER_SETTINGS_ID_COLUMN = "VideoEncoderSettingsId";
      String MAX_B_FRAMES_COLUMN = "MaxBFrames";
      String KEY_FRAME_INTERVAL_COLUMN = "KeyFrameInterval";
      String PROFILE_COLUMN = "Profile";
      String LEVEL_COLUMN = "Level";

      /**
       * Finds the VideoEncoderSettings record by its VideoEncoderSettingsId (primary key). Returns
       * null if no VideoEncoderSettings exists in the embedded database with the provided ID.
       */
      EmbeddedDatabase.VideoEncoderSettings.@Nullable Model findVideoEncoderSettingsById(long id)
          throws IOException;
    }

    /** POJO mapped to the columns of the VideoEncoderSettings table. */
    @SuppressWarnings(
        "GoodTime") // Suppressed on this class since it maps directly to the types in the DB table.
    public static final class Model {

      /** Primary key. */
      public final long videoEncoderSettingsId;

      /** Corresponds to the MAX_B_FRAMES setting of an Android MediaFormat. */
      public final int maxBFrames;

      /** Corresponds to the KEY_I_FRAME_INTERVAL setting of an Android MediaFormat. */
      public final double keyFrameInterval;

      /** Corresponds to the KEY_PROFILE setting of an Android MediaFormat. */
      public final int profile;

      /** Corresponds to the KEY_LEVEL setting of an Android MediaFormat. */
      public final int level;

      public Model(
          long videoEncoderSettingsId,
          int maxBFrames,
          double keyFrameInterval,
          int profile,
          int level) {
        this.videoEncoderSettingsId = videoEncoderSettingsId;
        this.maxBFrames = maxBFrames;
        this.keyFrameInterval = keyFrameInterval;
        this.profile = profile;
        this.level = level;
      }

      @Override
      public boolean equals(@Nullable Object o) {
        if (this == o) {
          return true;
        }
        if (!(o instanceof Model)) {
          return false;
        }
        Model model = (Model) o;
        return videoEncoderSettingsId == model.videoEncoderSettingsId
            && maxBFrames == model.maxBFrames
            && Double.compare(model.keyFrameInterval, keyFrameInterval) == 0
            && profile == model.profile
            && level == model.level;
      }

      @Override
      public int hashCode() {
        return Objects.hash(videoEncoderSettingsId, maxBFrames, keyFrameInterval, profile, level);
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("videoEncoderSettingsId", videoEncoderSettingsId)
            .add("maxBFrames", maxBFrames)
            .add("keyFrameInterval", keyFrameInterval)
            .add("profile", profile)
            .add("level", level)
            .toString();
      }
    }
  }

  /**
   * Represents the VideoEncoderSettingsExtension table of the CodecDB library-embedded SQLite
   * Database.
   */
  public static final class VideoEncoderSettingsExtension {
    private VideoEncoderSettingsExtension() {}

    /**
     * Facilitates data access to the VideoEncoderSettingsExtension table of the Android CodecDB
     * embedded database.
     */
    public interface Dao {

      String VIDEO_ENCODER_SETTINGS_ID_COLUMN = "VideoEncoderSettingsId";
      String KEY_COLUMN = "Key";
      String VALUE_COLUMN = "Value";

      /**
       * Returns all MediaFormat extension key/value pairs that correspond to the
       * VideoEncoderSettings record identified with the provided ID.
       *
       * <p>Often, this value should be empty, as most required parameters should be reflected on
       * the VideoEncoderSettings table itself.
       */
      ImmutableList<VideoEncoderSettingsExtension.Model>
          findVideoEncoderSettingsExtensionsByVideoEncoderSettingsId(long videoEncoderSettingsId)
              throws IOException;
    }

    /** POJO mapped to the columns of the VideoEncoderSettingsExtension table. */
    public static final class Model {

      /**
       * Foreign-key to the VideoEncoderSettings table, used to tie this parameter to that settings
       * entity.
       */
      public final long videoEncoderSettingsId;

      /** The Android MediaFormat key of this extension parameter. */
      public final String key;

      /**
       * The Android MediaFormat value extension of this parameter, which can be added to a format
       * with the {@code setString(String, String)} method.
       *
       * <p>This extension only supports string values at the moment.
       */
      public final String value;

      public Model(long videoEncoderSettingsId, String key, String value) {
        this.videoEncoderSettingsId = videoEncoderSettingsId;
        this.key = key;
        this.value = value;
      }

      @Override
      public boolean equals(@Nullable Object o) {
        if (this == o) {
          return true;
        }
        if (!(o instanceof Model)) {
          return false;
        }
        Model model = (Model) o;
        return videoEncoderSettingsId == model.videoEncoderSettingsId
            && Objects.equals(key, model.key)
            && Objects.equals(value, model.value);
      }

      @Override
      public int hashCode() {
        return Objects.hash(videoEncoderSettingsId, key, value);
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("videoEncoderSettingsId", videoEncoderSettingsId)
            .add("key", key)
            .add("value", value)
            .toString();
      }
    }
  }
}
