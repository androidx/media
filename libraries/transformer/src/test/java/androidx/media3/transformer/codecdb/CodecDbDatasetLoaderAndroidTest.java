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

import static android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
import static com.google.common.truth.Truth.assertThat;

import android.database.sqlite.SQLiteDatabase;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.VideoEncoderSettings;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link CodecDbDatasetLoader}. */
@RunWith(AndroidJUnit4.class)
public class CodecDbDatasetLoaderAndroidTest {

  /**
   * This test serves as a "smoke" test for most of the CodecDB Android database stack, as the data
   * loaded here is loaded directly from the test database with all intermediate components doing
   * their jobs.
   */
  @Test
  public void load_loadsCorrectData_testDatabase() throws Exception {
    CodecDbEncoderDataset dataset =
        new CodecDbDatasetLoader(
                new DeviceIdentifiers.Builder()
                    .setTier(DeviceIdentifiers.DEVICE_TIER_HIGH)
                    .setChipsetManufacturer("QTI")
                    .setChipsetModel("SM8550")
                    .setDeviceType("dm3quew")
                    .build(),
                CodecDbDatasetLoaderAndroidTest::openTestDatabase)
            .forEncoding(constraints -> null);

    // Verify Primary (HEVC)
    VideoEncoderConfiguration hevcConfig =
        dataset.findOptimalConfiguration(
            VideoEncoderConstraintsPresets.forEditing(
                    /* width= */ 1280, /* height= */ 720, /* frameRate= */ 30.0)
                .buildUpon()
                .setAllowedCodecs(C.VIDEO_CODEC_FLAG_H265)
                .setTargetQuality(80)
                .build());

    assertThat(hevcConfig).isNotNull();
    assertThat(hevcConfig.format.sampleMimeType).isEqualTo("video/hevc");

    // Verify Fallback (AVC)
    VideoEncoderConfiguration avcConfig =
        dataset.findOptimalConfiguration(
            VideoEncoderConstraintsPresets.forEditing(
                    /* width= */ 1280, /* height= */ 720, /* frameRate= */ 30.0)
                .buildUpon()
                .setAllowedCodecs(C.VIDEO_CODEC_FLAG_H264)
                .setTargetQuality(80)
                .build());

    assertThat(avcConfig).isNotNull();
    assertThat(avcConfig.format.sampleMimeType).isEqualTo("video/avc");
    // The primary (device and chipset) models have no AVC model, so the prediction comes from the
    // fallback models.
    assertThat(dataset.predictQualityFor(avcConfig)).isAtLeast(80.0);
  }

  @Test
  public void load_withFallbackProvider_usesProviderForFallback() throws Exception {
    final VideoEncoderConfiguration fallbackConfig =
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(0)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 1)
                .setBitrateMode(BITRATE_MODE_VBR)
                .build(),
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(1920)
                .setHeight(1080)
                .setFrameRate(30.0f)
                .setAverageBitrate(1000000)
                .build(),
            /* isFallback= */ false);

    CodecDbEncoderDataset dataset =
        new CodecDbDatasetLoader(
                new DeviceIdentifiers.Builder()
                    .setTier(DeviceIdentifiers.DEVICE_TIER_LOW)
                    .setChipsetManufacturer("Rockchip")
                    .setChipsetModel("RK3588")
                    .setDeviceType("some_device")
                    .build(),
                CodecDbDatasetLoaderAndroidTest::openTestDatabase)
            .forEncoding(constraints -> fallbackConfig);

    VideoEncoderConfiguration config =
        dataset.findOptimalConfiguration(
            VideoEncoderConstraintsPresets.forEditing(
                    /* width= */ 1280, /* height= */ 720, /* frameRate= */ 30.0)
                .buildUpon()
                // Request a codec that is not in the database, so that we use the fallback
                // provider.
                .setAllowedCodecs(C.VIDEO_CODEC_FLAG_AV1)
                .setTargetQuality(80)
                .build());

    assertThat(config).isEqualTo(fallbackConfig);
  }

  @Test
  public void forEncoding_afterLoading_closesDatabase() throws Exception {
    List<SQLiteDatabase> openedDatabases = new ArrayList<>();
    CodecDbDatasetLoader loader =
        new CodecDbDatasetLoader(
            new DeviceIdentifiers.Builder()
                .setTier(DeviceIdentifiers.DEVICE_TIER_HIGH)
                .setChipsetManufacturer("QTI")
                .setChipsetModel("SM8550")
                .setDeviceType("dm3quew")
                .build(),
            () -> {
              SQLiteDatabase db = openTestSqliteDatabase();
              openedDatabases.add(db);
              return new AndroidEmbeddedDatabase(db);
            });

    CodecDbEncoderDataset unused = loader.forEncoding(constraints -> null);

    assertThat(openedDatabases).hasSize(1);
    assertThat(openedDatabases.get(0).isOpen()).isFalse();
  }

  private static EmbeddedDatabase openTestDatabase() {
    return new AndroidEmbeddedDatabase(openTestSqliteDatabase());
  }

  private static SQLiteDatabase openTestSqliteDatabase() {
    try {
      return TestUtil.openTestDatabase(ApplicationProvider.getApplicationContext());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
