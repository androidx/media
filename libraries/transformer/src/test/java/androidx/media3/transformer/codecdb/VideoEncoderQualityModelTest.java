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

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link VideoEncoderQualityModel}. */
@RunWith(AndroidJUnit4.class)
public final class VideoEncoderQualityModelTest {

  private static final PciRateDistortionModel MODEL_480P =
      new PciRateDistortionModel(
          /* a= */ -78726.665014902,
          /* b= */ 100.529522521153,
          /* minRateKbps= */ 246,
          /* minDistortion= */ 52.7313346862793);
  private static final PciRateDistortionModel MODEL_720P =
      new PciRateDistortionModel(
          /* a= */ -102425.453461735,
          /* b= */ 100.174069072983,
          /* minRateKbps= */ 246,
          /* minDistortion= */ 55.6812973022461);
  private static final PciRateDistortionModel MODEL_1080P =
      new PciRateDistortionModel(
          /* a= */ -139672.751430586,
          /* b= */ 100,
          /* minRateKbps= */ 494,
          /* minDistortion= */ 59.4734992980957);
  private static final PciRateDistortionModel MODEL_4K =
      new PciRateDistortionModel(
          /* a= */ -169893.715747506,
          /* b= */ 100,
          /* minRateKbps= */ 4946,
          /* minDistortion= */ 65.0647659301758);

  @Test
  public void getModel_knownPixelRate_returnsExistingModel() {
    VideoEncoderQualityModel qualityModel =
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                854 * 480 * 30,
                MODEL_480P,
                1280 * 720 * 30,
                MODEL_720P,
                1920 * 1080 * 30,
                MODEL_1080P,
                3840 * 2160 * 30,
                MODEL_4K));

    assertThat(qualityModel.getModel(/* pixelsPerSecond= */ 854 * 480 * 30)).isEqualTo(MODEL_480P);
    assertThat(qualityModel.getModel(/* pixelsPerSecond= */ 1280 * 720 * 30)).isEqualTo(MODEL_720P);
    assertThat(qualityModel.getModel(/* pixelsPerSecond= */ 1920 * 1080 * 30))
        .isEqualTo(MODEL_1080P);
    assertThat(qualityModel.getModel(/* pixelsPerSecond= */ 3840 * 2160 * 30)).isEqualTo(MODEL_4K);
  }

  @Test
  public void getModel_betweenKnownPixelRates_interpolatesModel() {
    VideoEncoderQualityModel qualityModel =
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                854 * 480 * 30,
                MODEL_480P,
                1280 * 720 * 30,
                MODEL_720P,
                1920 * 1080 * 30,
                MODEL_1080P,
                3840 * 2160 * 30,
                MODEL_4K));

    assertThat(qualityModel.getModel(/* pixelsPerSecond= */ 960 * 540 * 30))
        .isEqualTo(
            new PciRateDistortionModel(
                /* a= */ -83750.98601770149,
                /* b= */ 100.45416372257287,
                /* minRateKbps= */ 246,
                /* minDistortion= */ 53.356748899420474));
  }

  @Test
  public void getModel_belowLowestKnownPixelRate_extrapolatesFromLowestTwoModels() {
    VideoEncoderQualityModel qualityModel =
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                1000,
                new PciRateDistortionModel(
                    /* a= */ -1000, /* b= */ 100, /* minRateKbps= */ 200, /* minDistortion= */ 50),
                2000,
                new PciRateDistortionModel(
                    /* a= */ -2000,
                    /* b= */ 100,
                    /* minRateKbps= */ 400,
                    /* minDistortion= */ 60)));

    assertThat(qualityModel.getModel(/* pixelsPerSecond= */ 500))
        .isEqualTo(
            new PciRateDistortionModel(
                /* a= */ -500, /* b= */ 100, /* minRateKbps= */ 200, /* minDistortion= */ 50));
  }

  @Test
  public void getModel_aboveHighestKnownPixelRate_extrapolatesFromHighestTwoModels() {
    VideoEncoderQualityModel qualityModel =
        new VideoEncoderQualityModel(
            ImmutableMap.of(
                1000,
                new PciRateDistortionModel(
                    /* a= */ -1000, /* b= */ 100, /* minRateKbps= */ 200, /* minDistortion= */ 50),
                2000,
                new PciRateDistortionModel(
                    /* a= */ -2000,
                    /* b= */ 100,
                    /* minRateKbps= */ 400,
                    /* minDistortion= */ 60)));

    assertThat(qualityModel.getModel(/* pixelsPerSecond= */ 3000))
        .isEqualTo(
            new PciRateDistortionModel(
                /* a= */ -3000, /* b= */ 100, /* minRateKbps= */ 600, /* minDistortion= */ 70));
  }

  @Test
  public void constructor_fewerThanTwoModels_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new VideoEncoderQualityModel(ImmutableMap.of(854 * 480 * 30, MODEL_480P)));
  }
}
