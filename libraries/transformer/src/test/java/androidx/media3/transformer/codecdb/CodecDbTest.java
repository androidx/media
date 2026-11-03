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

import android.content.Context;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.VideoEncoderSettings;
import androidx.test.core.app.ApplicationProvider;
import com.google.testing.junit.testparameterinjector.TestParameter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;
import org.robolectric.shadows.ShadowBuild;

/** Unit tests for {@link CodecDb}. */
@RunWith(RobolectricTestParameterInjector.class)
public class CodecDbTest {

  @Test
  public void findOptimalConfiguration_withValidDeviceProfile_returnsExpectedConfiguration(
      @TestParameter CodecDbTestCase testCase) throws Exception {
    ShadowBuild.setProduct(testCase.product);
    ShadowBuild.setSystemOnChipManufacturer(testCase.socManufacturer);
    ShadowBuild.setSystemOnChipModel(testCase.socModel);

    assertThat(
            CodecDb.onDevice(ApplicationProvider.getApplicationContext())
                .forEncoding(constraints -> null)
                .findOptimalConfiguration(
                    VideoEncoderConstraintsPresets.forEditing(
                            testCase.config.format.width,
                            testCase.config.format.height,
                            testCase.config.format.frameRate)
                        .buildUpon()
                        .setTargetQuality(testCase.targetQuality)
                        .build()))
        .isEqualTo(testCase.config);
  }

  @Test
  public void predictQualityFor_withValidInput_returnsExpectedQuality(
      @TestParameter CodecDbTestCase testCase) throws Exception {
    ShadowBuild.setProduct(testCase.product);
    ShadowBuild.setSystemOnChipManufacturer(testCase.socManufacturer);
    ShadowBuild.setSystemOnChipModel(testCase.socModel);

    assertThat(
            CodecDb.onDevice(ApplicationProvider.getApplicationContext())
                .forEncoding(constraints -> null)
                .predictQualityFor(testCase.config))
        .isWithin(0.1)
        .of(testCase.targetQuality);
  }

  @Test
  public void predictQualityFor_withOptimalConfig_providesInverse(
      @TestParameter CodecDbTestCase testCase) throws Exception {
    ShadowBuild.setProduct(testCase.product);
    ShadowBuild.setSystemOnChipManufacturer(testCase.socManufacturer);
    ShadowBuild.setSystemOnChipModel(testCase.socModel);

    VideoEncoderConfiguration configuration =
        CodecDb.onDevice(ApplicationProvider.getApplicationContext())
            .forEncoding(constraints -> null)
            .findOptimalConfiguration(
                VideoEncoderConstraintsPresets.forEditing(
                        testCase.config.format.width,
                        testCase.config.format.height,
                        testCase.config.format.frameRate)
                    .buildUpon()
                    .setTargetQuality(testCase.targetQuality)
                    .build());

    assertThat(
            CodecDb.onDevice(ApplicationProvider.getApplicationContext())
                .forEncoding(constraints -> null)
                .predictQualityFor(configuration))
        .isWithin(0.1)
        .of(testCase.targetQuality);
  }

  @Test
  public void databaseForkTime_matchesBundledDatabase() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();

    long databaseForkTime;
    try (EmbeddedDatabase database =
        new AndroidEmbeddedDatabase(TestUtil.openBundledDatabase(context))) {
      databaseForkTime = database.databaseMetadataDao.getForkTime();
    }

    assertThat(databaseForkTime).isEqualTo(CodecDb.DATABASE_FORK_TIME);
  }

  enum CodecDbTestCase {
    QTI_SM8550(
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(0)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                .build(),
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(1920)
                .setHeight(1080)
                .setFrameRate(30.0f)
                .setAverageBitrate(7_641_000)
                .build(),
            /* isFallback= */ false),
        /* product= */ "some_device_typed",
        /* socManufacturer= */ "QTI",
        /* socModel= */ "SM8550",
        /* bitrate= */ 7_641_000,
        /* targetQuality= */ 80),
    MT6878(
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(0)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 65536)
                .build(),
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setWidth(1920)
                .setHeight(1080)
                .setFrameRate(30.0f)
                .setAverageBitrate(6_697_000)
                .build(),
            /* isFallback= */ false),
        /* product= */ "product",
        /* socManufacturer= */ "Mediatek",
        /* socModel= */ "MT6878",
        /* bitrate= */ 6_697_000,
        /* targetQuality= */ 80),
    TENSOR_G3_FROM_DEVICE_TYPE(
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(1)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 8192)
                .setComplexity(0)
                .build(),
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_AV1)
                .setWidth(1280)
                .setHeight(720)
                .setFrameRate(30.0f)
                .setAverageBitrate(4_119_000)
                .build(),
            /* isFallback= */ false),
        /* product= */ "akita",
        /* socManufacturer= */ "Google",
        /* socModel= */ "GS301",
        /* bitrate= */ 4_119_000,
        /* targetQuality= */ 80),
    TENSOR_G3(
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(1)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 8192)
                .setComplexity(0)
                .build(),
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_AV1)
                .setWidth(1280)
                .setHeight(720)
                .setFrameRate(30.0f)
                .setAverageBitrate(4_119_000)
                .build(),
            /* isFallback= */ false),
        /* product= */ "husky",
        /* socManufacturer= */ "Google",
        /* socModel= */ "Tensor G3",
        /* bitrate= */ 4_119_000,
        /* targetQuality= */ 80),
    TENSOR_G3_INTERPOLATED(
        // This value should meet (3116 < bitrate < 4119) since those are the two known
        // resolution models (480p and 720p respectively).
        new VideoEncoderConfiguration(
            new VideoEncoderSettings.Builder()
                .setMaxBFrames(1)
                .setiFrameIntervalSeconds(1.0f)
                .setEncodingProfileLevel(/* encodingProfile= */ 1, /* encodingLevel= */ 8192)
                .setComplexity(0)
                .build(),
            new Format.Builder()
                .setSampleMimeType(MimeTypes.VIDEO_AV1)
                .setWidth(960)
                .setHeight(540)
                .setFrameRate(30.0f)
                .setAverageBitrate(3_325_000)
                .build(),
            /* isFallback= */ false),
        /* product= */ "husky",
        /* socManufacturer= */ "Google",
        /* socModel= */ "Tensor G3",
        /* bitrate= */ 3_325_000,
        /* targetQuality= */ 80);

    // VideoEncoderConfiguration is effectively immutable, but is no longer annotated @Immutable
    // because androidx.media3.transformer.VideoEncoderSettings is not annotated as such.
    @SuppressWarnings("ImmutableEnumChecker")
    final VideoEncoderConfiguration config;

    final String product;
    final String socManufacturer;
    final String socModel;
    final double bitrate;
    final double targetQuality;

    CodecDbTestCase(
        VideoEncoderConfiguration config,
        String product,
        String socManufacturer,
        String socModel,
        double bitrate,
        double targetQuality) {
      this.config = config;
      this.product = product;
      this.socManufacturer = socManufacturer;
      this.socModel = socModel;
      this.bitrate = bitrate;
      this.targetQuality = targetQuality;
    }
  }
}
