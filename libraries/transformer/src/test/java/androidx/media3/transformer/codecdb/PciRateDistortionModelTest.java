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
import com.google.common.testing.EqualsTester;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link PciRateDistortionModel}. */
@RunWith(AndroidJUnit4.class)
public final class PciRateDistortionModelTest {

  @Test
  public void predictBitrateForQuality_validInput_returnsExpectedValuesModelOne() {
    PciRateDistortionModel model =
        new PciRateDistortionModel(
            /* a= */ -217162.66393824056,
            /* b= */ 102.3759538121797,
            /* minRateKbps= */ 0.0,
            /* minDistortion= */ 0.0);

    double bitrate = model.predictBitrateForQuality(/* quality= */ 75.0);

    assertThat(bitrate).isWithin(0.001).of(7932.6063);
  }

  @Test
  public void predictBitrateForQuality_validInput_returnsExpectedValuesModelTwo() {
    PciRateDistortionModel model =
        new PciRateDistortionModel(
            /* a= */ -186100.00634980598,
            /* b= */ 101.43649196995636,
            /* minRateKbps= */ 0.0,
            /* minDistortion= */ 0.0);

    double bitrate = model.predictBitrateForQuality(/* quality= */ 85.0);

    assertThat(bitrate).isWithin(0.001).of(11322.3677);
  }

  @Test
  public void predictQualityForBitrate_validInput_returnsExpectedValuesModelOne() {
    PciRateDistortionModel model =
        new PciRateDistortionModel(
            /* a= */ -217162.66393824056,
            /* b= */ 102.3759538121797,
            /* minRateKbps= */ 0.0,
            /* minDistortion= */ 0.0);

    double quality = model.predictQualityForBitrate(/* bitrateKbps= */ 7932.6063);

    assertThat(quality).isWithin(0.001).of(75.0);
  }

  @Test
  public void predictQualityForBitrate_validInput_returnsExpectedValuesModelTwo() {
    PciRateDistortionModel model =
        new PciRateDistortionModel(
            /* a= */ -186100.00634980598,
            /* b= */ 101.43649196995636,
            /* minRateKbps= */ 0.0,
            /* minDistortion= */ 0.0);

    double quality = model.predictQualityForBitrate(/* bitrateKbps= */ 11322.3677);

    assertThat(quality).isWithin(0.001).of(85.0);
  }

  @Test
  public void predictBitrateForQuality_belowFloor_clampsToMinRateKbps() {
    PciRateDistortionModel model =
        new PciRateDistortionModel(
            /* a= */ -1000.0, /* b= */ 110.0, /* minRateKbps= */ 500.0, /* minDistortion= */ 0.0);

    double bitrate = model.predictBitrateForQuality(/* quality= */ 10.0);

    assertThat(bitrate).isEqualTo(500.0);
  }

  @Test
  public void predictQualityForBitrate_belowFloor_clampsToMinDistortion() {
    PciRateDistortionModel model =
        new PciRateDistortionModel(
            /* a= */ -100000.0, /* b= */ 100.0, /* minRateKbps= */ 0.0, /* minDistortion= */ 20.0);

    double quality = model.predictQualityForBitrate(/* bitrateKbps= */ 100.0);

    assertThat(quality).isEqualTo(20.0);
  }

  @Test
  public void predictBitrateForQuality_negativeQuality_throwsIllegalArgumentException() {
    PciRateDistortionModel model =
        new PciRateDistortionModel(
            /* a= */ -1000.0, /* b= */ 100.0, /* minRateKbps= */ 0.0, /* minDistortion= */ 0.0);

    assertThrows(IllegalArgumentException.class, () -> model.predictBitrateForQuality(-1.0));
  }

  @Test
  public void predictBitrateForQuality_qualityOverHundred_throwsIllegalArgumentException() {
    PciRateDistortionModel model =
        new PciRateDistortionModel(
            /* a= */ -1000.0, /* b= */ 100.0, /* minRateKbps= */ 0.0, /* minDistortion= */ 0.0);

    assertThrows(IllegalArgumentException.class, () -> model.predictBitrateForQuality(100.1));
  }

  @Test
  public void predictBitrateForQuality_nanOrInfiniteQuality_throwsIllegalArgumentException() {
    PciRateDistortionModel model =
        new PciRateDistortionModel(
            /* a= */ -1000.0, /* b= */ 100.0, /* minRateKbps= */ 0.0, /* minDistortion= */ 0.0);

    assertThrows(IllegalArgumentException.class, () -> model.predictBitrateForQuality(Double.NaN));
    assertThrows(
        IllegalArgumentException.class,
        () -> model.predictBitrateForQuality(Double.POSITIVE_INFINITY));
  }

  @Test
  public void predictQualityForBitrate_nonPositiveBitrate_throwsIllegalArgumentException() {
    PciRateDistortionModel model =
        new PciRateDistortionModel(
            /* a= */ -1000.0, /* b= */ 100.0, /* minRateKbps= */ 0.0, /* minDistortion= */ 0.0);

    assertThrows(IllegalArgumentException.class, () -> model.predictQualityForBitrate(0.0));
    assertThrows(IllegalArgumentException.class, () -> model.predictQualityForBitrate(-50.0));
  }

  @Test
  public void predictQualityForBitrate_nanOrInfiniteBitrate_throwsIllegalArgumentException() {
    PciRateDistortionModel model =
        new PciRateDistortionModel(
            /* a= */ -1000.0, /* b= */ 100.0, /* minRateKbps= */ 0.0, /* minDistortion= */ 0.0);

    assertThrows(IllegalArgumentException.class, () -> model.predictQualityForBitrate(Double.NaN));
    assertThrows(
        IllegalArgumentException.class,
        () -> model.predictQualityForBitrate(Double.POSITIVE_INFINITY));
  }

  @Test
  public void constructor_infiniteOrNanParameters_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PciRateDistortionModel(
                /* a= */ Double.POSITIVE_INFINITY,
                /* b= */ 100.0,
                /* minRateKbps= */ 0.0,
                /* minDistortion= */ 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PciRateDistortionModel(
                /* a= */ Double.NaN,
                /* b= */ 100.0,
                /* minRateKbps= */ 0.0,
                /* minDistortion= */ 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PciRateDistortionModel(
                /* a= */ -1000.0,
                /* b= */ Double.POSITIVE_INFINITY,
                /* minRateKbps= */ 0.0,
                /* minDistortion= */ 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PciRateDistortionModel(
                /* a= */ -1000.0,
                /* b= */ Double.NaN,
                /* minRateKbps= */ 0.0,
                /* minDistortion= */ 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PciRateDistortionModel(
                /* a= */ -1000.0,
                /* b= */ 100.0,
                /* minRateKbps= */ Double.POSITIVE_INFINITY,
                /* minDistortion= */ 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PciRateDistortionModel(
                /* a= */ -1000.0,
                /* b= */ 100.0,
                /* minRateKbps= */ Double.NaN,
                /* minDistortion= */ 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PciRateDistortionModel(
                /* a= */ -1000.0,
                /* b= */ 100.0,
                /* minRateKbps= */ 0.0,
                /* minDistortion= */ Double.POSITIVE_INFINITY));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PciRateDistortionModel(
                /* a= */ -1000.0,
                /* b= */ 100.0,
                /* minRateKbps= */ 0.0,
                /* minDistortion= */ Double.NaN));
  }

  @Test
  public void constructor_negativeFloors_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PciRateDistortionModel(
                /* a= */ -1000.0,
                /* b= */ 100.0,
                /* minRateKbps= */ -1.0,
                /* minDistortion= */ 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PciRateDistortionModel(
                /* a= */ -1000.0,
                /* b= */ 100.0,
                /* minRateKbps= */ 0.0,
                /* minDistortion= */ -1.0));
  }

  @Test
  public void constructor_nonNegativeA_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PciRateDistortionModel(
                /* a= */ 0.0, /* b= */ 100.0, /* minRateKbps= */ 0.0, /* minDistortion= */ 0.0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PciRateDistortionModel(
                /* a= */ 100.0, /* b= */ 100.0, /* minRateKbps= */ 0.0, /* minDistortion= */ 0.0));
  }

  @Test
  public void equalsAndHashCode_matchingAndDifferingInstances_satisfiesContract() {
    new EqualsTester()
        .addEqualityGroup(
            new PciRateDistortionModel(
                /* a= */ -217162.66,
                /* b= */ 102.38,
                /* minRateKbps= */ 500.0,
                /* minDistortion= */ 10.0),
            new PciRateDistortionModel(
                /* a= */ -217162.66,
                /* b= */ 102.38,
                /* minRateKbps= */ 500.0,
                /* minDistortion= */ 10.0))
        .addEqualityGroup(
            new PciRateDistortionModel(
                /* a= */ -186100.0,
                /* b= */ 101.44,
                /* minRateKbps= */ 500.0,
                /* minDistortion= */ 10.0))
        .addEqualityGroup(
            new PciRateDistortionModel(
                /* a= */ -217162.66,
                /* b= */ 99.0,
                /* minRateKbps= */ 500.0,
                /* minDistortion= */ 10.0))
        .addEqualityGroup(
            new PciRateDistortionModel(
                /* a= */ -217162.66,
                /* b= */ 102.38,
                /* minRateKbps= */ 100.0,
                /* minDistortion= */ 10.0))
        .addEqualityGroup(
            new PciRateDistortionModel(
                /* a= */ -217162.66,
                /* b= */ 102.38,
                /* minRateKbps= */ 500.0,
                /* minDistortion= */ 0.0))
        .testEquals();
  }
}
