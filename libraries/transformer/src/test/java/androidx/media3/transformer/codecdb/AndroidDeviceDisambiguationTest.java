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

import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for device disambiguation in {@link CodecDbDatasetLoader} and {@link
 * AndroidEmbeddedDatabase}.
 */
@RunWith(AndroidJUnit4.class)
public final class AndroidDeviceDisambiguationTest {

  private SQLiteDatabase db;
  private AndroidEmbeddedDatabase embeddedDatabase;
  private CodecDbDatasetLoader datasetLoader;

  @Before
  public void setUp() {
    db = SQLiteDatabase.create(null);
    // Create tables
    db.execSQL("CREATE TABLE Tier(TierId INTEGER PRIMARY KEY, Name TEXT COLLATE NOCASE NOT NULL);");

    db.execSQL(
        "CREATE TABLE Chipset("
            + "ChipsetId INTEGER PRIMARY KEY, "
            + "TierId INTEGER NOT NULL, "
            + "Manufacturer TEXT COLLATE NOCASE NOT NULL, "
            + "Model TEXT COLLATE NOCASE, "
            + "FOREIGN KEY(TierId) REFERENCES Tier(TierId));");

    db.execSQL(
        "CREATE TABLE Device("
            + "DeviceId INTEGER PRIMARY KEY, "
            + "ChipsetId INTEGER NOT NULL, "
            + "Type TEXT COLLATE NOCASE NOT NULL, "
            + "FingerprintHash INTEGER, "
            + "FOREIGN KEY(ChipsetId) REFERENCES Chipset(ChipsetId), "
            + "UNIQUE(Type, FingerprintHash));");

    // Populate Tiers
    db.execSQL("INSERT INTO Tier (TierId, Name) VALUES (1, 'HIGH');");

    // Populate Chipsets
    // 1: Snapdragon 8 Gen 2 (SM8550)
    db.execSQL(
        "INSERT INTO Chipset (ChipsetId, TierId, Manufacturer, Model) VALUES (1, 1, 'QTI',"
            + " 'SM8550');");
    // 2: Snapdragon 8 Gen 1 (SM8450)
    db.execSQL(
        "INSERT INTO Chipset (ChipsetId, TierId, Manufacturer, Model) VALUES (2, 1, 'QTI',"
            + " 'SM8450');");

    // Populate Devices
    // 'redfin' -> Chipset 1 (Default/Generic)
    db.execSQL(
        "INSERT INTO Device (DeviceId, ChipsetId, Type, FingerprintHash) VALUES (1, 1, 'redfin',"
            + " NULL);");

    // 'bramble' -> Chipset 1 (Default)
    db.execSQL(
        "INSERT INTO Device (DeviceId, ChipsetId, Type, FingerprintHash) VALUES (2, 1, 'bramble',"
            + " NULL);");
    // 'bramble' -> Chipset 2 (Specific Variant, Hash=12345)
    db.execSQL(
        "INSERT INTO Device (DeviceId, ChipsetId, Type, FingerprintHash) VALUES (3, 2, 'bramble',"
            + " 12345);");

    embeddedDatabase = new AndroidEmbeddedDatabase(db);
  }

  @Test
  public void getResolvedDeviceIdentifiers_populatesChipsetInfo() throws Exception {
    // looking for 'redfin' (Chipset 1: QTI SM8550)
    DeviceIdentifiers ids = new DeviceIdentifiers.Builder().setDeviceType("redfin").build();
    datasetLoader = new CodecDbDatasetLoader(ids, () -> embeddedDatabase);

    DeviceIdentifiers resolved = datasetLoader.getResolvedDeviceIdentifiers(embeddedDatabase);

    assertThat(resolved.chipsetManufacturer).isEqualTo("QTI");
    assertThat(resolved.chipsetModel).isEqualTo("SM8550");
  }

  @Test
  public void getResolvedDeviceIdentifiers_returnsOriginal_whenAlreadyPopulated() throws Exception {
    DeviceIdentifiers ids =
        new DeviceIdentifiers.Builder()
            .setChipsetManufacturer("OriginalManuf")
            .setChipsetModel("OriginalModel")
            .setDeviceType("redfin")
            .build();
    datasetLoader = new CodecDbDatasetLoader(ids, () -> embeddedDatabase);

    DeviceIdentifiers resolved = datasetLoader.getResolvedDeviceIdentifiers(embeddedDatabase);

    assertThat(resolved.chipsetManufacturer).isEqualTo("OriginalManuf");
    assertThat(resolved.chipsetModel).isEqualTo("OriginalModel");
  }

  @Test
  public void findChipsetId_returnsGeneric_whenNoFingerprintMatch() throws Exception {
    // Looking for 'redfin' without fingerprint hash
    DeviceIdentifiers ids = new DeviceIdentifiers.Builder().setDeviceType("redfin").build();

    Long chipsetId = embeddedDatabase.chipsetDao.findChipsetIdForDeviceIdentifiers(ids);

    assertThat(chipsetId).isEqualTo(1L);
  }

  @Test
  public void findChipsetId_returnsGeneric_whenFingerprintMismatch() throws Exception {
    // Looking for 'bramble' with unknown fingerprint hash (99999) -> Should return generic (1L)
    DeviceIdentifiers ids =
        new DeviceIdentifiers.Builder().setDeviceType("bramble").setFingerprintHash(99999L).build();

    Long chipsetId = embeddedDatabase.chipsetDao.findChipsetIdForDeviceIdentifiers(ids);

    assertThat(chipsetId).isEqualTo(1L);
  }

  @Test
  public void findChipsetId_returnsSpecific_whenFingerprintMatch() throws Exception {
    // Looking for 'bramble' with hash 12345 -> Should return specific (2L)
    DeviceIdentifiers ids =
        new DeviceIdentifiers.Builder().setDeviceType("bramble").setFingerprintHash(12345L).build();

    Long chipsetId = embeddedDatabase.chipsetDao.findChipsetIdForDeviceIdentifiers(ids);

    assertThat(chipsetId).isEqualTo(2L);
  }

  @Test
  public void findChipsetId_returnsNull_whenDeviceNotFound() throws Exception {
    DeviceIdentifiers ids = new DeviceIdentifiers.Builder().setDeviceType("unknown_device").build();

    Long chipsetId = embeddedDatabase.chipsetDao.findChipsetIdForDeviceIdentifiers(ids);

    assertThat(chipsetId).isNull();
  }
}
