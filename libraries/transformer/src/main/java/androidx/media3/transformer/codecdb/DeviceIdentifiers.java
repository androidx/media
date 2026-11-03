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

import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.SOURCE;
import static java.nio.charset.StandardCharsets.UTF_8;

import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import androidx.annotation.IntDef;
import androidx.annotation.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.MoreObjects;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Holds device identifiers relevant to CodecDB. */
/* package */ final class DeviceIdentifiers {

  private static final Pattern MEDIATEK_MODEL_PATTERN = Pattern.compile("^MT(\\d{4})[A-Z0-9_]*$");

  /**
   * Device tier. One of {@link #DEVICE_TIER_UNSPECIFIED}, {@link #DEVICE_TIER_HIGH}, {@link
   * #DEVICE_TIER_MID}, or {@link #DEVICE_TIER_LOW}.
   *
   * <p>Represents hardware capability classification used for rate-distortion fallback model
   * selection in CodecDB. Device tiers are subjective and focused solely on the video encoding
   * capabilities of the device.
   */
  @Documented
  @Retention(SOURCE)
  @Target(TYPE_USE)
  @IntDef({DEVICE_TIER_UNSPECIFIED, DEVICE_TIER_HIGH, DEVICE_TIER_MID, DEVICE_TIER_LOW})
  public @interface DeviceTier {}

  /** Unspecified device tier. */
  public static final int DEVICE_TIER_UNSPECIFIED = 0;

  /** Flagship devices. */
  public static final int DEVICE_TIER_HIGH = 1;

  /** Mid-range devices. */
  public static final int DEVICE_TIER_MID = 2;

  /** Entry-level devices. */
  public static final int DEVICE_TIER_LOW = 3;

  /** Unset hash. */
  public static final long FINGERPRINT_HASH_UNSET = 0L;

  /** Builder for {@link DeviceIdentifiers}. */
  public static final class Builder {

    private @DeviceTier int tier;
    private @Nullable String chipsetManufacturer;
    private @Nullable String chipsetModel;
    private @Nullable String deviceType;
    private long fingerprintHash;

    /** Creates an instance with default values. */
    public Builder() {}

    private Builder(DeviceIdentifiers identifiers) {
      this.tier = identifiers.tier;
      this.chipsetManufacturer = identifiers.chipsetManufacturer;
      this.chipsetModel = identifiers.chipsetModel;
      this.deviceType = identifiers.deviceType;
      this.fingerprintHash = identifiers.fingerprintHash;
    }

    /**
     * Sets {@link DeviceIdentifiers#tier}.
     *
     * <p>The default is {@link #DEVICE_TIER_UNSPECIFIED}.
     */
    @CanIgnoreReturnValue
    public Builder setTier(@DeviceTier int tier) {
      this.tier = tier;
      return this;
    }

    /**
     * Sets {@link DeviceIdentifiers#chipsetManufacturer}.
     *
     * <p>The default is {@code null}.
     */
    @CanIgnoreReturnValue
    public Builder setChipsetManufacturer(@Nullable String chipsetManufacturer) {
      this.chipsetManufacturer = chipsetManufacturer;
      return this;
    }

    /**
     * Sets {@link DeviceIdentifiers#chipsetModel}.
     *
     * <p>The default is {@code null}.
     */
    @CanIgnoreReturnValue
    public Builder setChipsetModel(@Nullable String chipsetModel) {
      this.chipsetModel = chipsetModel;
      return this;
    }

    /**
     * Sets {@link DeviceIdentifiers#deviceType}.
     *
     * <p>The default is {@code null}.
     */
    @CanIgnoreReturnValue
    public Builder setDeviceType(@Nullable String deviceType) {
      this.deviceType = deviceType;
      return this;
    }

    /**
     * Sets {@link DeviceIdentifiers#fingerprintHash}.
     *
     * <p>The default is {@link #FINGERPRINT_HASH_UNSET}.
     */
    @CanIgnoreReturnValue
    public Builder setFingerprintHash(long fingerprintHash) {
      this.fingerprintHash = fingerprintHash;
      return this;
    }

    /**
     * Returns a new {@link DeviceIdentifiers} instance.
     *
     * @throws IllegalStateException If no identifier fields are populated.
     */
    public DeviceIdentifiers build() {
      if (tier == DEVICE_TIER_UNSPECIFIED
          && chipsetManufacturer == null
          && chipsetModel == null
          && deviceType == null
          && fingerprintHash == FINGERPRINT_HASH_UNSET) {
        throw new IllegalStateException("At least one identifier field must be set");
      }
      return new DeviceIdentifiers(
          tier, chipsetManufacturer, chipsetModel, deviceType, fingerprintHash);
    }
  }

  /** The {@link DeviceTier} of the device. */
  public final @DeviceTier int tier;

  /**
   * The manufacturer of the chipset (SoC) of the device, corresponding to {@link
   * Build#SOC_MANUFACTURER} when available.
   */
  public final @Nullable String chipsetManufacturer;

  /**
   * The model of the chipset (SoC) of the device, corresponding to {@link Build#SOC_MODEL} when
   * available.
   */
  public final @Nullable String chipsetModel;

  /** The product identifier for the device, corresponding to {@link Build#PRODUCT}. */
  public final @Nullable String deviceType;

  /**
   * The fingerprint hash for the device, used to disambiguate devices when the {@link
   * Build#PRODUCT} is not unique.
   */
  public final long fingerprintHash;

  private DeviceIdentifiers(
      @DeviceTier int tier,
      @Nullable String chipsetManufacturer,
      @Nullable String chipsetModel,
      @Nullable String deviceType,
      long fingerprintHash) {
    this.tier = tier;
    this.chipsetManufacturer = chipsetManufacturer;
    this.chipsetModel = chipsetModel;
    this.deviceType = deviceType;
    this.fingerprintHash = fingerprintHash;
  }

  /** Returns the string representation for a {@link DeviceTier} integer constant. */
  public static String toDeviceTierName(@DeviceTier int tier) {
    switch (tier) {
      case DEVICE_TIER_UNSPECIFIED:
        return "UNSPECIFIED";
      case DEVICE_TIER_LOW:
        return "LOW";
      case DEVICE_TIER_MID:
        return "MID";
      case DEVICE_TIER_HIGH:
        return "HIGH";
      default:
        throw new IllegalArgumentException("Unknown device tier: " + tier);
    }
  }

  /** Returns a {@link DeviceIdentifiers} populated with details about the current device. */
  public static DeviceIdentifiers fromRuntime() {
    return new Builder()
        .setChipsetManufacturer(getChipsetManufacturer())
        .setChipsetModel(getChipsetModel())
        .setDeviceType(Build.PRODUCT)
        .setFingerprintHash(compute64BitMd5(Build.FINGERPRINT))
        .build();
  }

  /** Returns a {@link Builder} populated with the fields of this instance. */
  public Builder buildUpon() {
    return new Builder(this);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DeviceIdentifiers)) {
      return false;
    }
    DeviceIdentifiers that = (DeviceIdentifiers) o;
    return tier == that.tier
        && Objects.equals(chipsetManufacturer, that.chipsetManufacturer)
        && Objects.equals(chipsetModel, that.chipsetModel)
        && Objects.equals(deviceType, that.deviceType)
        && fingerprintHash == that.fingerprintHash;
  }

  @Override
  public int hashCode() {
    return Objects.hash(tier, chipsetManufacturer, chipsetModel, deviceType, fingerprintHash);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("tier", tier)
        .add("chipsetManufacturer", chipsetManufacturer)
        .add("chipsetModel", chipsetModel)
        .add("deviceType", deviceType)
        .add("fingerprintHash", fingerprintHash)
        .toString();
  }

  @VisibleForTesting
  /* package */ static long compute64BitMd5(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      byte[] digest = md.digest(input.getBytes(UTF_8));
      return ByteBuffer.wrap(digest).order(ByteOrder.BIG_ENDIAN).getLong();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("MD5 algorithm not found", e);
    }
  }

  private static @Nullable String getChipsetManufacturer() {
    if (VERSION.SDK_INT >= VERSION_CODES.S) {
      return Build.SOC_MANUFACTURER;
    }

    if (Build.HARDWARE == null) {
      return null;
    }

    String hardware = Ascii.toUpperCase(Build.HARDWARE).trim();
    if (hardware.startsWith("MT")) {
      return "Mediatek";
    }
    if (hardware.equals("QCOM")) {
      return "QTI";
    }

    return null;
  }

  private static @Nullable String getChipsetModel() {
    if (VERSION.SDK_INT >= VERSION_CODES.S) {
      return Build.SOC_MODEL != null ? normalizeChipsetModel(Build.SOC_MODEL) : null;
    }

    if (Build.HARDWARE == null) {
      return null;
    }

    String hardware = Ascii.toUpperCase(Build.HARDWARE).trim();
    if (hardware.startsWith("MT")) {
      return normalizeChipsetModel(hardware);
    }

    return null;
  }

  private static String normalizeChipsetModel(String rawModel) {
    int slashIndex = rawModel.indexOf('/');
    String prefix = (slashIndex >= 0 ? rawModel.substring(0, slashIndex) : rawModel).trim();
    Matcher matcher = MEDIATEK_MODEL_PATTERN.matcher(Ascii.toUpperCase(prefix));
    if (matcher.matches()) {
      return "MT" + matcher.group(1);
    }
    return rawModel;
  }
}
