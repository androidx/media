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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.testing.EqualsTester;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** Unit tests for {@link DeviceIdentifiers}. */
@RunWith(AndroidJUnit4.class)
public final class DeviceIdentifiersTest {
  private static final long EXPECTED_EMPTY_STRING_64BIT_MD5 = -3162216497309240828L;
  private static final long EXPECTED_TEST_STRING_64BIT_MD5 = 688887797400064883L;
  private static final long EXPECTED_REDFIN_FINGERPRINT_64BIT_MD5 = 6417143222738882979L;

  @Test
  public void compute64BitMd5_emptyString_returnsExpectedHash() {
    assertThat(DeviceIdentifiers.compute64BitMd5("")).isEqualTo(EXPECTED_EMPTY_STRING_64BIT_MD5);
  }

  @Test
  public void compute64BitMd5_shortString_returnsExpectedHash() {
    assertThat(DeviceIdentifiers.compute64BitMd5("test")).isEqualTo(EXPECTED_TEST_STRING_64BIT_MD5);
  }

  @Test
  public void compute64BitMd5_fingerprintString_returnsExpectedHash() {
    assertThat(
            DeviceIdentifiers.compute64BitMd5(
                "google/redfin/redfin:11/RQ3A.210805.001.A1/7478148:user/release-keys"))
        .isEqualTo(EXPECTED_REDFIN_FINGERPRINT_64BIT_MD5);
  }

  @Test
  public void build_withPopulatedFields_setsExpectedProperties() {
    DeviceIdentifiers identifiers =
        new DeviceIdentifiers.Builder()
            .setTier(DeviceIdentifiers.DEVICE_TIER_HIGH)
            .setChipsetManufacturer("Google")
            .setChipsetModel("Tensor")
            .setDeviceType("Pixel")
            .setFingerprintHash(12345L)
            .build();

    assertThat(identifiers.tier).isEqualTo(DeviceIdentifiers.DEVICE_TIER_HIGH);
    assertThat(identifiers.chipsetManufacturer).isEqualTo("Google");
    assertThat(identifiers.chipsetModel).isEqualTo("Tensor");
    assertThat(identifiers.deviceType).isEqualTo("Pixel");
    assertThat(identifiers.fingerprintHash).isEqualTo(12345L);
  }

  @Test
  public void buildUpon_unmodified_createsEqualInstance() {
    DeviceIdentifiers identifiers =
        new DeviceIdentifiers.Builder()
            .setTier(DeviceIdentifiers.DEVICE_TIER_MID)
            .setChipsetManufacturer("QTI")
            .setChipsetModel("Snapdragon")
            .setDeviceType("Galaxy")
            .setFingerprintHash(67890L)
            .build();

    DeviceIdentifiers copy = identifiers.buildUpon().build();

    assertThat(copy).isEqualTo(identifiers);
    assertThat(copy).isNotSameInstanceAs(identifiers);
  }

  @Test
  public void build_noFieldsSet_throwsIllegalStateException() {
    DeviceIdentifiers.Builder builder = new DeviceIdentifiers.Builder();

    IllegalStateException exception = assertThrows(IllegalStateException.class, builder::build);

    assertThat(exception).hasMessageThat().isEqualTo("At least one identifier field must be set");
  }

  @Test
  public void builder_publicNoArgConstructor_instantiatesSuccessfully() {
    DeviceIdentifiers.Builder builder = new DeviceIdentifiers.Builder();

    assertThat(builder).isNotNull();
  }

  @Test
  @Config(sdk = 31)
  public void fromRuntime_sdk31Plus_populatesSocManufacturerAndModel() {
    ReflectionHelpers.setStaticField(Build.class, "SOC_MANUFACTURER", "Google");
    ReflectionHelpers.setStaticField(Build.class, "SOC_MODEL", "Tensor");
    ReflectionHelpers.setStaticField(Build.class, "PRODUCT", "Pixel");
    ReflectionHelpers.setStaticField(Build.class, "FINGERPRINT", "test");

    DeviceIdentifiers identifiers = DeviceIdentifiers.fromRuntime();

    assertThat(identifiers.chipsetManufacturer).isEqualTo("Google");
    assertThat(identifiers.chipsetModel).isEqualTo("Tensor");
    assertThat(identifiers.deviceType).isEqualTo("Pixel");
    assertThat(identifiers.fingerprintHash).isEqualTo(EXPECTED_TEST_STRING_64BIT_MD5);
  }

  @Test
  @Config(sdk = 31)
  public void fromRuntime_sdk31PlusWithMediatekSlashVariant_collapsesToBaseModel() {
    ReflectionHelpers.setStaticField(Build.class, "SOC_MANUFACTURER", "Mediatek");
    ReflectionHelpers.setStaticField(Build.class, "SOC_MODEL", "MT6789V/CD");
    ReflectionHelpers.setStaticField(Build.class, "PRODUCT", "a15ub");
    ReflectionHelpers.setStaticField(Build.class, "FINGERPRINT", "test");

    DeviceIdentifiers identifiers = DeviceIdentifiers.fromRuntime();

    assertThat(identifiers.chipsetManufacturer).isEqualTo("Mediatek");
    assertThat(identifiers.chipsetModel).isEqualTo("MT6789");
  }

  @Test
  @Config(sdk = 31)
  public void fromRuntime_sdk31PlusWithMediatekNzaVariant_collapsesToBaseModel() {
    ReflectionHelpers.setStaticField(Build.class, "SOC_MANUFACTURER", "Mediatek");
    ReflectionHelpers.setStaticField(Build.class, "SOC_MODEL", "MT6833V/NZA");
    ReflectionHelpers.setStaticField(Build.class, "PRODUCT", "a13xue");
    ReflectionHelpers.setStaticField(Build.class, "FINGERPRINT", "test");

    DeviceIdentifiers identifiers = DeviceIdentifiers.fromRuntime();

    assertThat(identifiers.chipsetManufacturer).isEqualTo("Mediatek");
    assertThat(identifiers.chipsetModel).isEqualTo("MT6833");
  }

  @Test
  @Config(sdk = 31)
  public void fromRuntime_sdk31PlusWithMediatekLetterSuffixVariants_collapsesToBaseModel() {
    ReflectionHelpers.setStaticField(Build.class, "SOC_MANUFACTURER", "Mediatek");
    ReflectionHelpers.setStaticField(Build.class, "SOC_MODEL", "MT6769T");
    ReflectionHelpers.setStaticField(Build.class, "PRODUCT", "a32xx");
    ReflectionHelpers.setStaticField(Build.class, "FINGERPRINT", "test");

    DeviceIdentifiers mt6769tIdentifiers = DeviceIdentifiers.fromRuntime();
    ReflectionHelpers.setStaticField(Build.class, "SOC_MODEL", "MT6769Z");
    DeviceIdentifiers mt6769zIdentifiers = DeviceIdentifiers.fromRuntime();

    assertThat(mt6769tIdentifiers.chipsetModel).isEqualTo("MT6769");
    assertThat(mt6769zIdentifiers.chipsetModel).isEqualTo("MT6769");
  }

  @Test
  @Config(sdk = 30)
  public void fromRuntime_sdk30WithMediatekHardware_infersManufacturerAndModel() {
    ReflectionHelpers.setStaticField(Build.class, "HARDWARE", "mt6789");
    ReflectionHelpers.setStaticField(Build.class, "PRODUCT", "phone");
    ReflectionHelpers.setStaticField(Build.class, "FINGERPRINT", "test");

    DeviceIdentifiers identifiers = DeviceIdentifiers.fromRuntime();

    assertThat(identifiers.chipsetManufacturer).isEqualTo("Mediatek");
    assertThat(identifiers.chipsetModel).isEqualTo("MT6789");
    assertThat(identifiers.deviceType).isEqualTo("phone");
    assertThat(identifiers.fingerprintHash).isEqualTo(EXPECTED_TEST_STRING_64BIT_MD5);
  }

  @Test
  @Config(sdk = 30)
  public void fromRuntime_sdk30WithMediatekVariantHardware_collapsesToBaseModel() {
    ReflectionHelpers.setStaticField(Build.class, "HARDWARE", "mt6789v/cd");
    ReflectionHelpers.setStaticField(Build.class, "PRODUCT", "phone");
    ReflectionHelpers.setStaticField(Build.class, "FINGERPRINT", "test");

    DeviceIdentifiers identifiers = DeviceIdentifiers.fromRuntime();

    assertThat(identifiers.chipsetManufacturer).isEqualTo("Mediatek");
    assertThat(identifiers.chipsetModel).isEqualTo("MT6789");
  }

  @Test
  @Config(sdk = 30)
  public void fromRuntime_sdk30WithQcomHardware_infersManufacturerAndNullModel() {
    ReflectionHelpers.setStaticField(Build.class, "HARDWARE", "qcom");
    ReflectionHelpers.setStaticField(Build.class, "PRODUCT", "phone_qcom");

    DeviceIdentifiers identifiers = DeviceIdentifiers.fromRuntime();

    assertThat(identifiers.chipsetManufacturer).isEqualTo("QTI");
    assertThat(identifiers.chipsetModel).isNull();
    assertThat(identifiers.deviceType).isEqualTo("phone_qcom");
  }

  @Test
  public void equalsAndHashCode_contract_satisfied() {
    DeviceIdentifiers base =
        new DeviceIdentifiers.Builder()
            .setTier(DeviceIdentifiers.DEVICE_TIER_HIGH)
            .setChipsetManufacturer("Google")
            .setChipsetModel("Tensor")
            .setDeviceType("Pixel")
            .setFingerprintHash(12345L)
            .build();

    DeviceIdentifiers copy = base.buildUpon().build();

    DeviceIdentifiers diffTier =
        base.buildUpon().setTier(DeviceIdentifiers.DEVICE_TIER_LOW).build();
    DeviceIdentifiers diffManufacturer =
        base.buildUpon().setChipsetManufacturer("Different").build();
    DeviceIdentifiers diffModel = base.buildUpon().setChipsetModel("Different").build();
    DeviceIdentifiers diffDevice = base.buildUpon().setDeviceType("Different").build();
    DeviceIdentifiers diffFingerprint = base.buildUpon().setFingerprintHash(99999L).build();

    new EqualsTester()
        .addEqualityGroup(base, copy)
        .addEqualityGroup(diffTier)
        .addEqualityGroup(diffManufacturer)
        .addEqualityGroup(diffModel)
        .addEqualityGroup(diffDevice)
        .addEqualityGroup(diffFingerprint)
        .testEquals();
  }
}
