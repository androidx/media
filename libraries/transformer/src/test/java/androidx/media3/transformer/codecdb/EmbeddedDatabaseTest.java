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
import static androidx.media3.transformer.codecdb.DeviceIdentifiers.DEVICE_TIER_MID;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.transformer.codecdb.EmbeddedDatabase.Chipset;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.Codec;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.Codec.Model;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.DatabaseMetadata;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.Device;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.Tier;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.VideoEncoderQualityModelParameters;
import androidx.media3.transformer.codecdb.EmbeddedDatabase.VideoEncoderSettingsExtension;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link AndroidEmbeddedDatabase}. */
@RunWith(AndroidJUnit4.class)
public class EmbeddedDatabaseTest {

  private static EmbeddedDatabase databaseUnderTest() throws Exception {
    return new AndroidEmbeddedDatabase(
        TestUtil.openTestDatabase(ApplicationProvider.getApplicationContext()));
  }

  @Test
  public void tierDao_findTierIdForTier_returnsCorrectId() throws Exception {
    Tier.Dao dao = databaseUnderTest().tierDao;

    assertThat(dao.findTierIdForTier(DEVICE_TIER_HIGH)).isEqualTo(1);
    assertThat(dao.findTierIdForTier(DEVICE_TIER_MID)).isEqualTo(2);
    assertThat(dao.findTierIdForTier(DEVICE_TIER_LOW)).isEqualTo(3);
  }

  @Test
  public void
      videoEncoderQualityModelParametersDao_findVideoEncoderQualityModelParametersForCodec_returnsCorrectResults()
          throws Exception {
    VideoEncoderQualityModelParameters.Dao dao =
        databaseUnderTest().videoEncoderQualityModelParametersDao;

    ImmutableList<VideoEncoderQualityModelParameters.Model> results =
        dao.findSettingsOrderedVideoEncoderQualityModelParametersForCodec(/* codecId= */ 1);

    assertThat(results)
        .containsExactly(
            new VideoEncoderQualityModelParameters.Model(
                /* codecId= */ 1,
                /* videoEncoderSettingsId= */ 1,
                /* framePixels= */ 409920,
                /* coefficientA= */ -27603.474494100195,
                /* coefficientB= */ 100.0,
                /* minVmaf= */ 46.361732482910156,
                /* minBitrateKbps= */ 250.0),
            new VideoEncoderQualityModelParameters.Model(
                /* codecId= */ 1,
                /* videoEncoderSettingsId= */ 1,
                /* framePixels= */ 921600,
                /* coefficientA= */ -63487.683520828476,
                /* coefficientB= */ 100.0,
                /* minVmaf= */ 43.23570251464844,
                /* minBitrateKbps= */ 252.0),
            new VideoEncoderQualityModelParameters.Model(
                /* codecId= */ 1,
                /* videoEncoderSettingsId= */ 1,
                /* framePixels= */ 2073600,
                /* coefficientA= */ -149202.65970768215,
                /* coefficientB= */ 100.0,
                /* minVmaf= */ 39.62779998779297,
                /* minBitrateKbps= */ 504.0),
            new VideoEncoderQualityModelParameters.Model(
                /* codecId= */ 1,
                /* videoEncoderSettingsId= */ 1,
                /* framePixels= */ 8294400,
                /* coefficientA= */ -682623.4985854008,
                /* coefficientB= */ 101.68688833609218,
                /* minVmaf= */ 40.48951721191406,
                /* minBitrateKbps= */ 5000.0),
            new VideoEncoderQualityModelParameters.Model(
                /* codecId= */ 1,
                /* videoEncoderSettingsId= */ 2,
                /* framePixels= */ 409920,
                /* coefficientA= */ -27603.474370493732,
                /* coefficientB= */ 100.0,
                /* minVmaf= */ 46.361732482910156,
                /* minBitrateKbps= */ 250.0),
            new VideoEncoderQualityModelParameters.Model(
                /* codecId= */ 1,
                /* videoEncoderSettingsId= */ 2,
                /* framePixels= */ 921600,
                /* coefficientA= */ -63483.102410446474,
                /* coefficientB= */ 100.0,
                /* minVmaf= */ 43.23570251464844,
                /* minBitrateKbps= */ 252.0),
            new VideoEncoderQualityModelParameters.Model(
                /* codecId= */ 1,
                /* videoEncoderSettingsId= */ 2,
                /* framePixels= */ 2073600,
                /* coefficientA= */ -141404.24663191836,
                /* coefficientB= */ 100.0,
                /* minVmaf= */ 39.62779998779297,
                /* minBitrateKbps= */ 504.0),
            new VideoEncoderQualityModelParameters.Model(
                /* codecId= */ 1,
                /* videoEncoderSettingsId= */ 2,
                /* framePixels= */ 8294400,
                /* coefficientA= */ -682623.4959058407,
                /* coefficientB= */ 101.68688825097823,
                /* minVmaf= */ 40.48951721191406,
                /* minBitrateKbps= */ 5000.0),
            new VideoEncoderQualityModelParameters.Model(
                /* codecId= */ 1,
                /* videoEncoderSettingsId= */ 3,
                /* framePixels= */ 409920,
                /* coefficientA= */ -27603.474561910098,
                /* coefficientB= */ 100.0,
                /* minVmaf= */ 46.361732482910156,
                /* minBitrateKbps= */ 250.0),
            new VideoEncoderQualityModelParameters.Model(
                /* codecId= */ 1,
                /* videoEncoderSettingsId= */ 3,
                /* framePixels= */ 921600,
                /* coefficientA= */ -63483.10236343774,
                /* coefficientB= */ 100.0,
                /* minVmaf= */ 43.23570251464844,
                /* minBitrateKbps= */ 252.0),
            new VideoEncoderQualityModelParameters.Model(
                /* codecId= */ 1,
                /* videoEncoderSettingsId= */ 3,
                /* framePixels= */ 2073600,
                /* coefficientA= */ -149203.68220602974,
                /* coefficientB= */ 100.0,
                /* minVmaf= */ 39.62779998779297,
                /* minBitrateKbps= */ 504.0),
            new VideoEncoderQualityModelParameters.Model(
                /* codecId= */ 1,
                /* videoEncoderSettingsId= */ 3,
                /* framePixels= */ 8294400,
                /* coefficientA= */ -682623.4128073071,
                /* coefficientB= */ 101.68688658640522,
                /* minVmaf= */ 40.48951721191406,
                /* minBitrateKbps= */ 5000.0),
            new VideoEncoderQualityModelParameters.Model(
                /* codecId= */ 1,
                /* videoEncoderSettingsId= */ 4,
                /* framePixels= */ 409920,
                /* coefficientA= */ -28438.088719117182,
                /* coefficientB= */ 100.0,
                /* minVmaf= */ 48.567283630371094,
                /* minBitrateKbps= */ 249.0),
            new VideoEncoderQualityModelParameters.Model(
                /* codecId= */ 1,
                /* videoEncoderSettingsId= */ 4,
                /* framePixels= */ 921600,
                /* coefficientA= */ -64292.67747459876,
                /* coefficientB= */ 100.0,
                /* minVmaf= */ 45.93724822998047,
                /* minBitrateKbps= */ 256.0),
            new VideoEncoderQualityModelParameters.Model(
                /* codecId= */ 1,
                /* videoEncoderSettingsId= */ 4,
                /* framePixels= */ 2073600,
                /* coefficientA= */ -149780.90500583098,
                /* coefficientB= */ 100.0,
                /* minVmaf= */ 42.56543731689453,
                /* minBitrateKbps= */ 503.0),
            new VideoEncoderQualityModelParameters.Model(
                /* codecId= */ 1,
                /* videoEncoderSettingsId= */ 4,
                /* framePixels= */ 8294400,
                /* coefficientA= */ -718448.9065150776,
                /* coefficientB= */ 102.37698793558395,
                /* minVmaf= */ 40.03440475463867,
                /* minBitrateKbps= */ 5007.0));
  }

  @Test
  public void
      videoEncoderQualityModelParametersDao_findVideoEncoderQualityModelParametersForCodec_returnsEmptyArray_codecWithoutQualityModel()
          throws Exception {
    VideoEncoderQualityModelParameters.Dao dao =
        databaseUnderTest().videoEncoderQualityModelParametersDao;

    assertThat(dao.findSettingsOrderedVideoEncoderQualityModelParametersForCodec(/* codecId= */ 8))
        .isEmpty();
  }

  @Test
  public void
      videoEncoderQualityMOdelParametersDao_findVideoEncoderQualityModelParametersForCodec_returnsEmptyArray_nonexistentCodec()
          throws Exception {
    VideoEncoderQualityModelParameters.Dao dao =
        databaseUnderTest().videoEncoderQualityModelParametersDao;

    assertThat(dao.findSettingsOrderedVideoEncoderQualityModelParametersForCodec(/* codecId= */ 20))
        .isEmpty();
  }

  @Test
  public void videoEncoderSettingsDao_findVideoEncoderSettingsById_returnsCorrectResults()
      throws Exception {
    EmbeddedDatabase.VideoEncoderSettings.Dao dao = databaseUnderTest().videoEncoderSettingsDao;

    EmbeddedDatabase.VideoEncoderSettings.Model result =
        dao.findVideoEncoderSettingsById(/* id= */ 1);

    assertThat(result)
        .isEqualTo(
            new EmbeddedDatabase.VideoEncoderSettings.Model(
                /* videoEncoderSettingsId= */ 1,
                /* maxBFrames= */ 1,
                /* keyFrameInterval= */ 1.0,
                /* profile= */ 1,
                /* level= */ 65536));
  }

  @Test
  public void videoEncoderSettingsDao_findVideoEncoderSettingsById_returnsNull_nonexistentId()
      throws Exception {
    EmbeddedDatabase.VideoEncoderSettings.Dao dao = databaseUnderTest().videoEncoderSettingsDao;
    assertThat(dao.findVideoEncoderSettingsById(/* id= */ 123)).isNull();
  }

  @Test
  public void
      videoEncoderSettingsExtensionDao_findVideoEncoderSettingsExtensionsByVideoEncoderSettingsId_returnsCorrectResults()
          throws Exception {
    VideoEncoderSettingsExtension.Dao dao = databaseUnderTest().videoEncoderSettingsExtensionDao;

    ImmutableList<VideoEncoderSettingsExtension.Model> results =
        dao.findVideoEncoderSettingsExtensionsByVideoEncoderSettingsId(
            /* videoEncoderSettingsId= */ 1);

    assertThat(results)
        .containsExactly(
            new VideoEncoderSettingsExtension.Model(
                /* videoEncoderSettingsId= */ 1,
                /* key= */ "ts-schema",
                /* value= */ "android.generic.1+2"));
  }

  @Test
  public void
      videoEncoderSettingsExtensionDao_findVideoEncoderSettingsExtensionsByVideoEncoderSettingsId_returnsEmptyArray_nonexistentId()
          throws Exception {
    VideoEncoderSettingsExtension.Dao dao = databaseUnderTest().videoEncoderSettingsExtensionDao;

    assertThat(
            dao.findVideoEncoderSettingsExtensionsByVideoEncoderSettingsId(
                /* videoEncoderSettingsId= */ 123))
        .isEmpty();
  }

  @Test
  public void databaseMetadataDao_getForkTime_returnsSentinelForUnknownTime() throws Exception {
    DatabaseMetadata.Dao dao = databaseUnderTest().databaseMetadataDao;

    assertThat(dao.getForkTime()).isEqualTo(-1);
  }

  @Test
  public void deviceDao_findDeviceIdForType_returnsCorrectId() throws Exception {
    Device.Dao dao = databaseUnderTest().deviceDao;

    assertThat(dao.findDeviceIdForType("panther")).isEqualTo(7);
  }

  @Test
  public void deviceDao_findDeviceIdForType_returnsNull_nonexistentType() throws Exception {
    Device.Dao dao = databaseUnderTest().deviceDao;

    assertThat(dao.findDeviceIdForType("coral")).isNull();
  }

  @Test
  public void codecDao_findGenericCodecs_returnsCorrectResults() throws Exception {
    Codec.Dao dao = databaseUnderTest().codecDao;

    ImmutableList<Model> results = dao.findGenericCodecs();

    assertThat(results)
        .containsExactly(
            new Model(
                /* codecId= */ 11,
                /* tierId= */ null,
                /* chipsetId= */ null,
                /* deviceId= */ null,
                /* canonicalName= */ null,
                /* mime= */ "video/avc"));
  }

  @Test
  public void codecDao_findCodecsForTier_returnsCorrectResults() throws Exception {
    Codec.Dao dao = databaseUnderTest().codecDao;

    // In the test database (1) is equivalent to the HIGH tier.
    ImmutableList<Model> results = dao.findCodecsForTier(/* tierId= */ 1L);

    assertThat(results)
        .containsExactly(
            new Model(
                /* codecId= */ 7L,
                /* tierId= */ 1L,
                /* chipsetId= */ null,
                /* deviceId= */ null,
                /* canonicalName= */ null,
                /* mime= */ "video/hevc"),
            new Model(
                /* codecId= */ 8L,
                /* tierId= */ 1L,
                /* chipsetId= */ null,
                /* deviceId= */ null,
                /* canonicalName= */ null,
                /* mime= */ "video/avc"),
            new Model(
                /* codecId= */ 9L,
                /* tierId= */ 1L,
                /* chipsetId= */ null,
                /* deviceId= */ null,
                /* canonicalName= */ "c2.android.av1.encoder",
                /* mime= */ "video/av01"));
  }

  @Test
  public void codecDao_findCodecsForTier_returnsEmptyArray_tierWithoutCodecs() throws Exception {
    Codec.Dao dao = databaseUnderTest().codecDao;

    // TierId=3 corresponds to the LOW tier, which has no data in
    // the test database.
    assertThat(dao.findCodecsForTier(/* tierId= */ 3L)).isEmpty();
  }

  @Test
  public void codecDao_findCodecsForTier_returnsEmptyArray_nonexistentTier() throws Exception {
    Codec.Dao dao = databaseUnderTest().codecDao;

    // TierId=4 does not exist in the test database.
    assertThat(dao.findCodecsForTier(/* tierId= */ 4L)).isEmpty();
  }

  @Test
  public void codecDao_findCodecsForChipset_returnsCorrectResults() throws Exception {
    Codec.Dao dao = databaseUnderTest().codecDao;

    assertThat(dao.findCodecsForChipset(/* chipsetId= */ 3L))
        .containsExactly(
            new Model(
                /* codecId= */ 3L,
                /* tierId= */ 1L,
                /* chipsetId= */ 3L,
                /* deviceId= */ null,
                /* canonicalName= */ null,
                /* mime= */ "video/hevc"));
  }

  @Test
  public void codecDao_findCodecsForChipset_returnsEmptyArray_chipsetWithoutCodecs()
      throws Exception {
    Codec.Dao dao = databaseUnderTest().codecDao;

    assertThat(dao.findCodecsForChipset(/* chipsetId= */ 4L)).isEmpty();
  }

  @Test
  public void codecDao_findCodecsForChipset_returnsEmptyArray_nonexistentChipset()
      throws Exception {
    Codec.Dao dao = databaseUnderTest().codecDao;

    assertThat(dao.findCodecsForChipset(/* chipsetId= */ 123L)).isEmpty();
  }

  @Test
  public void codecDao_findCodecsForDevice_returnsCorrectResults() throws Exception {
    Codec.Dao dao = databaseUnderTest().codecDao;

    assertThat(dao.findCodecsForDevice(/* deviceId= */ 10L))
        .containsExactly(
            new Model(
                /* codecId= */ 10L,
                /* tierId= */ 1L,
                /* chipsetId= */ 5L,
                /* deviceId= */ 10L,
                /* canonicalName= */ "c2.mtk.hevc.encoder",
                /* mime= */ "video/hevc"));
  }

  @Test
  public void codecDao_findCodecsForDevice_returnsEmptyArray_deviceWithoutCodecs()
      throws Exception {
    Codec.Dao dao = databaseUnderTest().codecDao;

    assertThat(dao.findCodecsForDevice(/* deviceId= */ 4L)).isEmpty();
  }

  @Test
  public void codecDao_findCodecsForDevice_returnsEmptyArray_nonexistentDevice() throws Exception {
    Codec.Dao dao = databaseUnderTest().codecDao;

    assertThat(dao.findCodecsForDevice(/* deviceId= */ 123L)).isEmpty();
  }

  @Test
  public void chipsetDao_findChipsetIdForChipset_returnsCorrectId() throws Exception {
    Chipset.Dao dao = databaseUnderTest().chipsetDao;

    // The MediaTek Dimensity 9000 is in the test database at ID 6.
    assertThat(dao.findChipsetIdForChipset("Mediatek", "MT6983")).isEqualTo(5);
  }

  @Test
  public void chipsetDao_findChipsetIdForChipset_returnsNull_nonexistentChipset() throws Exception {
    Chipset.Dao dao = databaseUnderTest().chipsetDao;

    // The Qualcomm Snapdragon 855 is NOT in the test database.
    assertThat(dao.findChipsetIdForChipset("QTI", "SM8150")).isNull();
  }

  @Test
  public void chipsetDao_findChipsetIdForDeviceIdentifiers_returnsCorrectId_unknownSoc()
      throws Exception {
    Chipset.Dao dao = databaseUnderTest().chipsetDao;

    assertThat(
            dao.findChipsetIdForDeviceIdentifiers(
                new DeviceIdentifiers.Builder().setDeviceType("husky").build()))
        .isEqualTo(4);
  }

  @Test
  public void chipsetDao_findChipsetIdForDeviceIdentifiers_returnsCorrectId_onlySocModel()
      throws Exception {
    Chipset.Dao dao = databaseUnderTest().chipsetDao;

    assertThat(
            dao.findChipsetIdForDeviceIdentifiers(
                new DeviceIdentifiers.Builder().setChipsetModel("Tensor G3").build()))
        .isEqualTo(4);
  }

  @Test
  public void chipsetDao_findAllChipsets_returnsAllChipsets() throws Exception {
    Chipset.Dao dao = databaseUnderTest().chipsetDao;

    assertThat(dao.findAllChipsets())
        .containsExactly(
            new Chipset.Model(1, 1, "QTI", "SM8550"),
            new Chipset.Model(2, 1, "QTI", "SM8350"),
            new Chipset.Model(3, 1, "Google", "Tensor G2"),
            new Chipset.Model(4, 1, "Google", "Tensor G3"),
            new Chipset.Model(5, 1, "Mediatek", "MT6983"),
            new Chipset.Model(6, 1, "Samsung", "s5e9925"));
  }
}
