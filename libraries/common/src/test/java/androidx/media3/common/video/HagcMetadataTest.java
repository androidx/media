/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.common.video;

import static androidx.media3.common.util.Util.getBytesFromHexString;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.video.HagcMetadata.AlternateImage;
import androidx.media3.common.video.HagcMetadata.ComponentMix;
import androidx.media3.common.video.HagcMetadata.GainCurve;
import androidx.media3.common.video.HagcMetadata.HeadroomAdaptiveToneMap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for {@link HagcMetadata}.
 *
 * <p>Payloads are written as hex strings, with one group per syntax element of Annex C of SMPTE ST
 * 2094-50, per byte of packed flags, or per pair of chromaticity coordinates.
 */
@RunWith(AndroidJUnit4.class)
public final class HagcMetadataTest {

  /**
   * The tolerance for values computed with floating point arithmetic. It's smaller than one
   * encoding step of any syntax element, so comparing decoded values within it, as {@link
   * #assertGainCurveIsApproximately} does, still catches a wrongly decoded value.
   */
  private static final float TOLERANCE = 1e-6f;

  /**
   * The start of a payload with an adaptive tone map that has one alternate image, below the
   * baseline and with a max component mix. The alternate image's gain curve follows.
   */
  private static final String ONE_ALTERNATE_IMAGE_PAYLOAD_PREFIX =
      "00" // Version 0, minimum version 0.
          + "40" // Default reference white, adaptive tone map.
          + "2710" // Baseline headroom 10000 / 10000 = 1.
          + "18" // 1 alternate image, BT.2020 primaries, no shared parameters.
          + "0000" // Alternate headroom 0, below the baseline.
          + "00"; // Component mixing type 0 (max).

  @Test
  public void parse_payloadWithReferenceWhiteOnly_returnsReferenceWhiteWithoutToneMap()
      throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "80" // Custom reference white, no adaptive tone map.
                + "01F4"); // Reference white 500 / 5 = 100 nits.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    assertThat(metadata.hdrReferenceWhiteNits).isEqualTo(100f);
    assertThat(metadata.headroomAdaptiveToneMap).isNull();
  }

  @Test
  public void parse_payloadWithReferenceWhiteToneMapping_returnsComputedAlternateImages()
      throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "C0" // Custom reference white, adaptive tone map.
                + "01F4" // Reference white 500 / 5 = 100 nits.
                + "07D0" // Baseline headroom 2000 / 10000 = 0.2.
                + "80"); // Reference white tone mapping.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    // The expected values were computed in double precision from Formulas (C.1) to (C.6) of
    // Clause C.3.8. The last control point of each gain curve maps the baseline headroom to the
    // alternate headroom (Clause 6.2.3).
    assertThat(metadata.hdrReferenceWhiteNits).isEqualTo(100f);
    HeadroomAdaptiveToneMap toneMap = metadata.headroomAdaptiveToneMap;
    assertThat(toneMap.baselineHdrHeadroom).isEqualTo(0.2f);
    assertThat(toneMap.gainApplicationSpaceChromaticities)
        .isEqualTo(new float[] {0.708f, 0.292f, 0.17f, 0.797f, 0.131f, 0.046f, 0.3127f, 0.329f});
    assertThat(toneMap.gainApplicationColorSpace).isEqualTo(C.COLOR_SPACE_BT2020);
    assertThat(toneMap.alternateImages).hasSize(2);
    AlternateImage alternateImage0 = toneMap.alternateImages.get(0);
    assertThat(alternateImage0.hdrHeadroom).isEqualTo(0f);
    assertThat(alternateImage0.componentMix).isEqualTo(ComponentMix.MAX);
    assertGainCurveIsApproximately(
        alternateImage0.gainCurve,
        new float[][] {
          // {x, y, slope}
          {1f, -0.06411756f, 0f}, // The knee: reference white, with a zero slope.
          {1.0102688f, -0.06712986f, -0.49552733f},
          {1.0241956f, -0.07603963f, -0.7481289f},
          {1.0417802f, -0.09062276f, -0.8929263f},
          {1.0630229f, -0.110616036f, -0.9796607f},
          {1.0879234f, -0.13572472f, -1.0311396f},
          {1.1164819f, -0.16563061f, -1.0594549f},
          {1.1486983f, -0.2f, -1.0717331f}, // The peak: maps 2^0.2 to 2^0.
        });
    AlternateImage alternateImage1 = toneMap.alternateImages.get(1);
    assertThat(alternateImage1.hdrHeadroom).isWithin(TOLERANCE).of(0.12302276f);
    assertThat(alternateImage1.componentMix).isEqualTo(ComponentMix.MAX);
    assertGainCurveIsApproximately(
        alternateImage1.gainCurve,
        new float[][] {
          // {x, y, slope}
          {1f, 0f, 0f}, // The knee: reference white, with a zero slope.
          {1.0172043f, -0.0017285802f, -0.19179794f},
          {1.0357547f, -0.006802431f, -0.34811363f},
          {1.0556512f, -0.0150599815f, -0.4763149f},
          {1.0768938f, -0.026347801f, -0.58194894f},
          {1.0994825f, -0.04052042f, -0.66927254f},
          {1.1234174f, -0.057440188f, -0.74160737f},
          {1.1486983f, -0.076977246f, -0.80158395f}, // The peak: maps 2^0.2 to 2^0.123.
        });
  }

  @Test
  public void parse_referenceWhiteToneMappingWithHighBaselineHeadroom_returnsClampedAlternates()
      throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "7530" // Baseline headroom 30000 / 10000 = 3, above log2(1000 / 203).
                + "80"); // Reference white tone mapping.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    // The baseline headroom is above log2(1000 / 203), so the ratio in Formulas (C.1) and (C.2)
    // is clamped to 1.
    HeadroomAdaptiveToneMap toneMap = metadata.headroomAdaptiveToneMap;
    GainCurve gainCurve0 = toneMap.alternateImages.get(0).gainCurve;
    // The first curve starts at the knee, at reference white.
    assertThat(gainCurve0.x[0]).isWithin(TOLERANCE).of(1f);
    // With the ratio at 1, Formula (C.2) puts the knee at y = 0.5, a gain of log2(0.5).
    assertThat(gainCurve0.y[0]).isWithin(TOLERANCE).of(-1f);
    // The curve is flat at the knee.
    assertThat(gainCurve0.slopes[0]).isWithin(TOLERANCE).of(0f);
    // The curve ends at the baseline peak, 2^3.
    assertThat(gainCurve0.x[7]).isWithin(TOLERANCE).of(8f);
    // It maps the baseline peak to the first alternate's peak, 2^0.
    assertThat(gainCurve0.y[7]).isWithin(TOLERANCE).of(-3f);
    AlternateImage alternateImage1 = toneMap.alternateImages.get(1);
    // With the ratio at 1, Formula (C.1) gives the second alternate headroom log2(8 / 3).
    assertThat(alternateImage1.hdrHeadroom).isWithin(TOLERANCE).of(1.4150375f);
    // The second curve's knee is at reference white with no gain.
    assertThat(alternateImage1.gainCurve.y[0]).isWithin(TOLERANCE).of(0f);
    // It maps the baseline peak to the second alternate's peak.
    assertThat(alternateImage1.gainCurve.y[7]).isWithin(TOLERANCE).of(1.4150375f - 3);
  }

  @Test
  public void parse_referenceWhiteToneMappingWithZeroBaselineHeadroom_returnsNoAlternateImages()
      throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "0000" // Baseline headroom 0.
                + "80"); // Reference white tone mapping.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    assertThat(metadata.headroomAdaptiveToneMap.alternateImages).isEmpty();
  }

  @Test
  public void parse_payloadWithoutCustomReferenceWhite_returnsDefaultReferenceWhite()
      throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "00"); // Default reference white, no adaptive tone map.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    assertThat(metadata.hdrReferenceWhiteNits)
        .isEqualTo(HagcMetadata.DEFAULT_HDR_REFERENCE_WHITE_NITS);
  }

  @Test
  public void parse_payloadFromSampleWithIt35Track_returnsDecodedValues() throws Exception {
    // The payload of the first frame of media/mp4/sample_with_it35_track.mp4, which has three
    // alternate images that share a custom component mix and the x coordinates of 16 control
    // points with explicit slopes. The expected values are decoded as specified in Clause C.3.
    byte[] payload =
        getBytesFromHexString(
            "00402F923B0000FC19A7423605CB61A878000A000B000F001D00360060009E00F5016901FE02B8039B04AC"
                + "05EE0765091600CD00D500F601A302F50551083A0B510E4010D0137016CC1BAF217F27A62F921F"
                + "E922DB32261E8A164D1BB0232D27042FEB34FC387335B3332A339035AD2D760FDB006D00710086"
                + "00F101DA03890585077A09160A590BB10DBE1127155919CD1FB72ACF2D89397C29131CBF238C2B"
                + "F4329639923DA73EB93B313846385A3A0F2D761FB70034003700400074010202190341042B0486"
                + "047E0480052306F709700C190FDB37AA39614004367D25882DDE366F40FD4429475B459741823E"
                + "333DCA3EE32D76");

    HagcMetadata metadata = HagcMetadata.parse(payload);

    assertThat(metadata.hdrReferenceWhiteNits)
        .isEqualTo(HagcMetadata.DEFAULT_HDR_REFERENCE_WHITE_NITS);
    HeadroomAdaptiveToneMap toneMap = metadata.headroomAdaptiveToneMap;
    assertThat(toneMap.baselineHdrHeadroom).isEqualTo(1.2178f);
    assertThat(toneMap.gainApplicationSpaceChromaticities)
        .isEqualTo(new float[] {0.708f, 0.292f, 0.17f, 0.797f, 0.131f, 0.046f, 0.3127f, 0.329f});
    assertThat(toneMap.gainApplicationColorSpace).isEqualTo(C.COLOR_SPACE_BT2020);
    assertThat(toneMap.alternateImages).hasSize(3);
    assertThat(toneMap.alternateImages.get(0).hdrHeadroom).isEqualTo(0f);
    assertThat(toneMap.alternateImages.get(1).hdrHeadroom).isEqualTo(0.4059f);
    assertThat(toneMap.alternateImages.get(2).hdrHeadroom).isEqualTo(0.8119f);
    ComponentMix expectedComponentMix =
        new ComponentMix(
            /* red= */ 0.13134f,
            /* green= */ 0.339f,
            /* blue= */ 0.02966f,
            /* max= */ 0.5f,
            /* min= */ 0,
            /* component= */ 0);
    for (AlternateImage alternateImage : toneMap.alternateImages) {
      assertThat(alternateImage.componentMix).isEqualTo(expectedComponentMix);
    }
    assertGainCurveIsApproximately(
        toneMap.alternateImages.get(0).gainCurve,
        new float[][] {
          // {x, y, slope}
          {0.01f, -0.0205f, -1.1566733f},
          {0.011f, -0.0213f, -1.0135301f},
          {0.015f, -0.0246f, -0.48363423f},
          {0.029f, -0.0419f, -1.2309387f},
          {0.054f, -0.0757f, -1.8383263f},
          {0.096f, -0.1361f, -1.4050583f},
          {0.158f, -0.2106f, -0.99912775f},
          {0.245f, -0.2897f, -0.8408857f},
          {0.361f, -0.3648f, -0.5466903f},
          {0.51f, -0.4304f, -0.4076853f},
          {0.696f, -0.4976f, -0.3200063f},
          {0.923f, -0.5836f, -0.38918015f},
          {1.196f, -0.7087f, -0.45593706f},
          {1.518f, -0.8575f, -0.4452287f},
          {1.893f, -1.015f, -0.38978317f},
          {2.326f, -1.2178f, -0.6202679f},
        });
    assertGainCurveIsApproximately(
        toneMap.alternateImages.get(1).gainCurve,
        new float[][] {
          // {x, y, slope}
          {0.01f, -0.0109f, -0.7055531f},
          {0.011f, -0.0113f, -0.6179743f},
          {0.015f, -0.0134f, -0.29469535f},
          {0.029f, -0.0241f, -0.7652494f},
          {0.054f, -0.0474f, -1.3369708f},
          {0.096f, -0.0905f, -0.98269725f},
          {0.158f, -0.1413f, -0.6679262f},
          {0.245f, -0.1914f, -0.47163063f},
          {0.361f, -0.2326f, -0.29260993f},
          {0.51f, -0.2649f, -0.19592033f},
          {0.696f, -0.2993f, -0.17120259f},
          {0.923f, -0.3518f, -0.253689f},
          {1.196f, -0.4391f, -0.3243409f},
          {1.518f, -0.5465f, -0.3224131f},
          {1.893f, -0.6605f, -0.28080487f},
          {2.326f, -0.8119f, -0.6202679f},
        });
    assertGainCurveIsApproximately(
        toneMap.alternateImages.get(2).gainCurve,
        new float[][] {
          // {x, y, slope}
          {0.01f, -0.0052f, -0.33945426f},
          {0.011f, -0.0055f, -0.29725796f},
          {0.015f, -0.0064f, -0.14160888f},
          {0.029f, -0.0116f, -0.3690186f},
          {0.054f, -0.0258f, -0.8991407f},
          {0.096f, -0.0537f, -0.6077705f},
          {0.158f, -0.0833f, -0.37040734f},
          {0.245f, -0.1067f, -0.11950831f},
          {0.361f, -0.1158f, -0.048120912f},
          {0.51f, -0.115f, 0.023304364f},
          {0.696f, -0.1152f, -0.016145699f},
          {0.923f, -0.1315f, -0.10775188f},
          {1.196f, -0.1783f, -0.18326373f},
          {1.518f, -0.2416f, -0.19275066f},
          {1.893f, -0.3097f, -0.16743232f},
          {2.326f, -0.4059f, -0.6202679f},
        });
  }

  @Test
  public void parse_payloadWithPchipSlopes_returnsComputedSlopes() throws Exception {
    byte[] payload =
        getBytesFromHexString(
            ONE_ALTERNATE_IMAGE_PAYLOAD_PREFIX
                + "14" // 3 control points, PCHIP slopes.
                + "03E8" // x[0] = 1000 / 1000 = 1.
                + "07D0" // x[1] = 2.
                + "0FA0" // x[2] = 4.
                + "0000" // y[0] = 0.
                + "0BB8" // y[1] = -3000 / 10000 = -0.3, negative as the alternate is below.
                + "0FA0"); // y[2] = -0.4.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    // The expected slopes follow Clause C.3.9: a three-point difference at the left control
    // point, Formula (C.8) at the interior one, and 0 at the right one, whose three-point
    // difference has the wrong sign.
    assertGainCurveIsApproximately(
        metadata.headroomAdaptiveToneMap.alternateImages.get(0).gainCurve,
        new float[][] {
          // {x, y, slope}
          {1f, 0f, -23f / 60f},
          {2f, -0.3f, -27f / 290f},
          {4f, -0.4f, 0f},
        });
  }

  @Test
  public void parse_pchipSlopesAtTurningPoint_limitsOvershootingEndSlope() throws Exception {
    byte[] payload =
        getBytesFromHexString(
            ONE_ALTERNATE_IMAGE_PAYLOAD_PREFIX
                + "14" // 3 control points, PCHIP slopes.
                + "03E8" // x[0] = 1.
                + "07D0" // x[1] = 2.
                + "0BB8" // x[2] = 3.
                + "2710" // y[0] = -1.
                + "2328" // y[1] = -0.9.
                + "4A38"); // y[2] = -1.9.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    // The expected slopes follow Clause C.3.9. The left three-point difference, 0.65, is limited
    // to 0.3, three times the slope of its interval (0.1). The interior slope is 0 as the curve
    // turns.
    assertGainCurveIsApproximately(
        metadata.headroomAdaptiveToneMap.alternateImages.get(0).gainCurve,
        new float[][] {
          // {x, y, slope}
          {1f, -1f, 0.3f},
          {2f, -0.9f, 0f},
          {3f, -1.9f, -1.55f},
        });
  }

  @Test
  public void parse_equalControlPointXAtStartWithEqualY_returnsZeroSlopeAtDegeneratePoint()
      throws Exception {
    byte[] payload =
        getBytesFromHexString(
            ONE_ALTERNATE_IMAGE_PAYLOAD_PREFIX
                + "14" // 3 control points, PCHIP slopes.
                + "03E8" // x[0] = 1.
                + "03E8" // x[1] = 1.
                + "07D0" // x[2] = 2.
                + "1388" // y[0] = -0.5.
                + "1388" // y[1] = -0.5.
                + "0BB8"); // y[2] = -0.3.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    // The first control point is degenerate. The other two only have the interval between them,
    // so their slopes are its slope.
    assertGainCurveIsApproximately(
        metadata.headroomAdaptiveToneMap.alternateImages.get(0).gainCurve,
        new float[][] {
          // {x, y, slope}
          {1f, -0.5f, 0f},
          {1f, -0.5f, 0.2f},
          {2f, -0.3f, 0.2f},
        });
  }

  @Test
  public void parse_equalControlPointXAtEndWithEqualY_returnsZeroSlopeAtDegeneratePoint()
      throws Exception {
    byte[] payload =
        getBytesFromHexString(
            ONE_ALTERNATE_IMAGE_PAYLOAD_PREFIX
                + "14" // 3 control points, PCHIP slopes.
                + "03E8" // x[0] = 1.
                + "07D0" // x[1] = 2.
                + "07D0" // x[2] = 2.
                + "0BB8" // y[0] = -0.3.
                + "1388" // y[1] = -0.5.
                + "1388"); // y[2] = -0.5.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    // The first two control points only have the interval between them, so their slopes are its
    // slope. The last control point is degenerate.
    assertGainCurveIsApproximately(
        metadata.headroomAdaptiveToneMap.alternateImages.get(0).gainCurve,
        new float[][] {
          // {x, y, slope}
          {1f, -0.3f, -0.2f},
          {2f, -0.5f, -0.2f},
          {2f, -0.5f, 0f},
        });
  }

  @Test
  public void parse_pchipSlopesWithOneControlPoint_returnsZeroSlope() throws Exception {
    byte[] payload =
        getBytesFromHexString(
            ONE_ALTERNATE_IMAGE_PAYLOAD_PREFIX
                + "04" // 1 control point, PCHIP slopes.
                + "03E8" // x[0] = 1.
                + "1388"); // y[0] = -0.5.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    assertGainCurveIsApproximately(
        metadata.headroomAdaptiveToneMap.alternateImages.get(0).gainCurve,
        new float[][] {
          // {x, y, slope}
          {1f, -0.5f, 0f},
        });
  }

  @Test
  public void parse_alternateHeadroomAboveBaseline_returnsPositiveGains() throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "0000" // Baseline headroom 0.
                + "18" // 1 alternate image, BT.2020 primaries, no shared parameters.
                + "4E20" // Alternate headroom 20000 / 10000 = 2.
                + "00" // Component mixing type 0 (max).
                + "00" // 1 control point, explicit slopes.
                + "03E8" // x[0] = 1.
                + "1388" // y[0] = 0.5, positive as the alternate is above.
                + "4650"); // theta[0] = 18000, a slope of tan(0) = 0.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    assertThat(metadata.headroomAdaptiveToneMap.alternateImages.get(0).gainCurve.y)
        .isEqualTo(new float[] {0.5f});
  }

  @Test
  public void parse_zeroGainBelowBaseline_returnsZero() throws Exception {
    byte[] payload =
        getBytesFromHexString(
            ONE_ALTERNATE_IMAGE_PAYLOAD_PREFIX
                + "00" // 1 control point, explicit slopes.
                + "03E8" // x[0] = 1.
                + "0000" // y[0] = 0.
                + "4650"); // theta[0] = 18000, a slope of tan(0) = 0.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    // The gain is +0f, not -0f: equals() and hashCode() distinguish them, and so does isEqualTo().
    assertThat(metadata.headroomAdaptiveToneMap.alternateImages.get(0).gainCurve.y)
        .isEqualTo(new float[] {0f});
  }

  @Test
  public void parse_unsharedParametersWithTwoAlternates_returnsParametersOfEachAlternate()
      throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "EA60" // Baseline headroom 60000 / 10000 = 6.
                + "28" // 2 alternate images, BT.2020 primaries, no shared parameters.
                + "0000" // Alternate 0: headroom 0.
                + "00" // Alternate 0: component mixing type 0 (max).
                + "00" // Alternate 0: 1 control point, explicit slopes.
                + "03E8" // Alternate 0: x[0] = 1.
                + "1388" // Alternate 0: y[0] = -0.5.
                + "4650" // Alternate 0: theta[0] = 18000, a slope of tan(0) = 0.
                + "2710" // Alternate 1: headroom 1.
                + "40" // Alternate 1: component mixing type 1 (component).
                + "08" // Alternate 1: 2 control points, explicit slopes.
                + "03E8" // Alternate 1: x[0] = 1.
                + "07D0" // Alternate 1: x[1] = 2.
                + "0FA0" // Alternate 1: y[0] = -0.4.
                + "07D0" // Alternate 1: y[1] = -0.2.
                + "4650" // Alternate 1: theta[0] = 18000, a slope of tan(0) = 0.
                + "6978"); // Alternate 1: theta[1] = 27000, a slope of tan(pi / 4) = 1.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    AlternateImage alternateImage1 = metadata.headroomAdaptiveToneMap.alternateImages.get(1);
    assertThat(alternateImage1.hdrHeadroom).isEqualTo(1f);
    assertThat(alternateImage1.componentMix).isEqualTo(ComponentMix.COMPONENT);
    assertThat(alternateImage1.gainCurve.x).isEqualTo(new float[] {1f, 2f});
    assertThat(alternateImage1.gainCurve.y).isEqualTo(new float[] {-0.4f, -0.2f});
    assertThat(alternateImage1.gainCurve.slopes)
        .usingTolerance(TOLERANCE)
        .containsExactly(0f, 1f)
        .inOrder();
  }

  @Test
  public void parse_sharedCurveParametersAndUnsharedMixes_returnsMixAndGainSignOfEachAlternate()
      throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "2710" // Baseline headroom 1.
                + "29" // 2 alternate images, BT.2020 primaries, shared curve parameters only.
                + "0000" // Alternate 0: headroom 0, below the baseline.
                + "00" // Alternate 0: component mixing type 0 (max).
                + "00" // Alternate 0: 1 control point, explicit slopes.
                + "03E8" // Alternate 0: x[0] = 1.
                + "1388" // Alternate 0: y[0] = -0.5, negative as the alternate is below.
                + "4650" // Alternate 0: theta[0] = 18000, a slope of tan(0) = 0.
                + "4E20" // Alternate 1: headroom 2, above the baseline.
                + "40" // Alternate 1: component mixing type 1 (component).
                + "1388" // Alternate 1: y[0] = 0.5, positive as the alternate is above.
                + "4650"); // Alternate 1: theta[0] = 18000, a slope of tan(0) = 0.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    AlternateImage alternateImage0 = metadata.headroomAdaptiveToneMap.alternateImages.get(0);
    assertThat(alternateImage0.componentMix).isEqualTo(ComponentMix.MAX);
    assertThat(alternateImage0.gainCurve.y).isEqualTo(new float[] {-0.5f});
    AlternateImage alternateImage1 = metadata.headroomAdaptiveToneMap.alternateImages.get(1);
    assertThat(alternateImage1.componentMix).isEqualTo(ComponentMix.COMPONENT);
    assertThat(alternateImage1.gainCurve.x).isEqualTo(new float[] {1f});
    assertThat(alternateImage1.gainCurve.y).isEqualTo(new float[] {0.5f});
  }

  @Test
  public void parse_customComponentMixingCoefficients_returnsNormalizedWeights() throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "2710" // Baseline headroom 1.
                + "18" // 1 alternate image, BT.2020 primaries, no shared parameters.
                + "0000" // Alternate headroom 0.
                + "E4" // Custom component mixing, with red and max coefficients.
                + "30D4" // Red coefficient 12500 / 50000 = 0.25.
                + "30D4" // Max coefficient 0.25.
                + "00" // 1 control point, explicit slopes.
                + "03E8" // x[0] = 1.
                + "1388" // y[0] = -0.5.
                + "4650"); // theta[0] = 18000, a slope of tan(0) = 0.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    assertThat(metadata.headroomAdaptiveToneMap.alternateImages.get(0).componentMix)
        .isEqualTo(
            new ComponentMix(
                /* red= */ 0.5f,
                /* green= */ 0,
                /* blue= */ 0,
                /* max= */ 0.5f,
                /* min= */ 0,
                /* component= */ 0));
  }

  @Test
  public void parse_componentMixingType2_returnsRgbAndMaxWeights() throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "2710" // Baseline headroom 1.
                + "18" // 1 alternate image, BT.2020 primaries, no shared parameters.
                + "0000" // Alternate headroom 0.
                + "80" // Component mixing type 2.
                + "00" // 1 control point, explicit slopes.
                + "03E8" // x[0] = 1.
                + "1388" // y[0] = -0.5.
                + "4650"); // theta[0] = 18000, a slope of tan(0) = 0.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    assertThat(metadata.headroomAdaptiveToneMap.alternateImages.get(0).componentMix)
        .isEqualTo(
            new ComponentMix(
                /* red= */ 1 / 6f,
                /* green= */ 1 / 6f,
                /* blue= */ 1 / 6f,
                /* max= */ 1 / 2f,
                /* min= */ 0,
                /* component= */ 0));
  }

  @Test
  public void parse_bt709ChromaticitiesMode_returnsBt709Chromaticities() throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "2710" // Baseline headroom 1.
                + "00"); // No alternate images, BT.709 primaries.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    HeadroomAdaptiveToneMap toneMap = metadata.headroomAdaptiveToneMap;
    assertThat(toneMap.gainApplicationSpaceChromaticities)
        .isEqualTo(new float[] {0.64f, 0.33f, 0.3f, 0.6f, 0.15f, 0.06f, 0.3127f, 0.329f});
    assertThat(toneMap.gainApplicationColorSpace).isEqualTo(C.COLOR_SPACE_BT709);
  }

  @Test
  public void parse_p3ChromaticitiesMode_returnsP3Chromaticities() throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "2710" // Baseline headroom 1.
                + "04"); // No alternate images, P3 primaries.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    HeadroomAdaptiveToneMap toneMap = metadata.headroomAdaptiveToneMap;
    assertThat(toneMap.gainApplicationSpaceChromaticities)
        .isEqualTo(new float[] {0.68f, 0.32f, 0.265f, 0.69f, 0.15f, 0.06f, 0.3127f, 0.329f});
    assertThat(toneMap.gainApplicationColorSpace).isEqualTo(C.COLOR_SPACE_DISPLAY_P3);
  }

  @Test
  public void parse_payloadWithCustomChromaticities_returnsDecodedChromaticitiesAndAlternate()
      throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "2710" // Baseline headroom 1.
                + "1C" // 1 alternate image, custom primaries, no shared parameters.
                + "82DC" // Red x = 33500 / 50000 = 0.67.
                + "4074" // Red y = 0.33.
                + "2904" // Green x = 0.21.
                + "8AAC" // Green y = 0.71.
                + "1B58" // Blue x = 0.14.
                + "0FA0" // Blue y = 0.08.
                + "3C8C" // White x = 0.31.
                + "3DB8" // White y = 0.316.
                + "0000" // Alternate headroom 0.
                + "00" // Component mixing type 0 (max).
                + "00" // 1 control point, explicit slopes.
                + "03E8" // x[0] = 1.
                + "1388" // y[0] = -0.5.
                + "4650"); // theta[0] = 18000, a slope of tan(0) = 0.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    HeadroomAdaptiveToneMap toneMap = metadata.headroomAdaptiveToneMap;
    assertThat(toneMap.gainApplicationSpaceChromaticities)
        .isEqualTo(new float[] {0.67f, 0.33f, 0.21f, 0.71f, 0.14f, 0.08f, 0.31f, 0.316f});
    assertThat(toneMap.gainApplicationColorSpace)
        .isEqualTo(HeadroomAdaptiveToneMap.COLOR_SPACE_CUSTOM);
    assertThat(toneMap.alternateImages.get(0).gainCurve.y).isEqualTo(new float[] {-0.5f});
  }

  @Test
  public void parse_customChromaticitiesInClockwiseOrder_returnsDecodedChromaticities()
      throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "2710" // Baseline headroom 1.
                + "0C" // No alternate images, custom primaries.
                + "13881388" // Red (5000, 5000) / 50000 = (0.1, 0.1).
                + "27109C40" // Green (0.2, 0.8).
                + "9C402710" // Blue (0.8, 0.2), so red, green, blue are in clockwise order.
                + "3A983A98"); // White (0.3, 0.3), inside the triangle.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    assertThat(metadata.headroomAdaptiveToneMap.gainApplicationSpaceChromaticities)
        .isEqualTo(new float[] {0.1f, 0.1f, 0.2f, 0.8f, 0.8f, 0.2f, 0.3f, 0.3f});
  }

  @Test
  public void parse_referenceWhiteBelowItsRange_returnsClampedReferenceWhite() throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "80" // Custom reference white, no adaptive tone map.
                + "0000"); // Reference white 0, clamped to 1 / 5 = 0.2 nits.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    assertThat(metadata.hdrReferenceWhiteNits).isEqualTo(0.2f);
  }

  @Test
  public void parse_referenceWhiteAboveItsRange_returnsClampedReferenceWhite() throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "80" // Custom reference white, no adaptive tone map.
                + "FFFF"); // Reference white 65535, clamped to 50000 / 5 = 10000 nits.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    assertThat(metadata.hdrReferenceWhiteNits).isEqualTo(10_000f);
  }

  @Test
  public void parse_baselineHeadroomAboveItsRange_returnsClampedBaselineHeadroom()
      throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "FFFF" // Baseline headroom 65535, clamped to 60000 / 10000 = 6.
                + "80"); // Reference white tone mapping.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    assertThat(metadata.headroomAdaptiveToneMap.baselineHdrHeadroom).isEqualTo(6f);
  }

  @Test
  public void parse_outOfRangeValues_returnsClampedValues() throws Exception {
    // The float spacing around the expected slope is about 0.001.
    float largeSlopeTolerance = 0.01f;
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "FFFF" // Baseline headroom 65535, clamped to 60000 / 10000 = 6.
                + "18" // 1 alternate image, BT.2020 primaries, no shared parameters.
                + "0000" // Alternate headroom 0.
                + "C4" // Custom component mixing, with a max coefficient only.
                + "FFFF" // Max coefficient 65535, clamped to 50000 / 50000 = 1.
                + "00" // 1 control point, explicit slopes.
                + "FFFF" // x[0] = 65535, clamped to 64000 / 1000 = 64.
                + "FFFF" // y[0] = 65535, clamped to -60000 / 10000 = -6.
                + "0000"); // theta[0] = 0, clamped to 1, a slope of tan(-pi / 2 + pi / 36000).

    HagcMetadata metadata = HagcMetadata.parse(payload);

    HeadroomAdaptiveToneMap toneMap = metadata.headroomAdaptiveToneMap;
    AlternateImage alternateImage = toneMap.alternateImages.get(0);
    assertThat(alternateImage.componentMix).isEqualTo(ComponentMix.MAX);
    assertThat(alternateImage.gainCurve.x).isEqualTo(new float[] {64f});
    assertThat(alternateImage.gainCurve.y).isEqualTo(new float[] {-6f});
    assertThat(alternateImage.gainCurve.slopes)
        .usingTolerance(largeSlopeTolerance)
        .containsExactly(-11459.156f);
  }

  @Test
  public void parse_headroomAndChromaticityValuesAboveTheirRanges_returnsClampedValues()
      throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "0000" // Baseline headroom 0.
                + "1C" // 1 alternate image, custom primaries, no shared parameters.
                + "FFFF" // Red x = 65535, clamped to 50000 / 50000 = 1.
                + "0000" // Red y = 0.
                + "0000" // Green x = 0.
                + "FFFF" // Green y = 65535, clamped to 1.
                + "0000" // Blue x = 0.
                + "0000" // Blue y = 0.
                + "3A98" // White x = 15000 / 50000 = 0.3.
                + "3A98" // White y = 0.3.
                + "FFFF" // Alternate headroom 65535, clamped to 60000 / 10000 = 6.
                + "00" // Component mixing type 0 (max).
                + "00" // 1 control point, explicit slopes.
                + "03E8" // x[0] = 1.
                + "1388" // y[0] = 0.5.
                + "4650"); // theta[0] = 18000, a slope of tan(0) = 0.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    HeadroomAdaptiveToneMap toneMap = metadata.headroomAdaptiveToneMap;
    assertThat(toneMap.gainApplicationSpaceChromaticities)
        .isEqualTo(new float[] {1f, 0f, 0f, 1f, 0f, 0f, 0.3f, 0.3f});
    assertThat(toneMap.alternateImages.get(0).hdrHeadroom).isEqualTo(6f);
  }

  @Test
  public void parse_numAlternateImagesAboveFour_returnsFourAlternateImages() throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "EA60" // Baseline headroom 6.
                + "7B" // 7 alternate images, BT.2020 primaries, shared mix and curve parameters.
                + "0000" // Alternate 0: headroom 0.
                + "00" // Alternate 0: component mixing type 0 (max).
                + "00" // Alternate 0: 1 control point, explicit slopes.
                + "03E8" // Alternate 0: x[0] = 1.
                + "1388" // Alternate 0: y[0] = -0.5.
                + "4650" // Alternate 0: theta[0] = 18000, a slope of tan(0) = 0.
                + "2710" // Alternate 1: headroom 1.
                + "0FA0" // Alternate 1: y[0] = -0.4.
                + "4650" // Alternate 1: theta[0] = 18000, a slope of tan(0) = 0.
                + "4E20" // Alternate 2: headroom 2.
                + "0BB8" // Alternate 2: y[0] = -0.3.
                + "4650" // Alternate 2: theta[0] = 18000, a slope of tan(0) = 0.
                + "7530" // Alternate 3: headroom 3.
                + "07D0" // Alternate 3: y[0] = -0.2.
                + "4650"); // Alternate 3: theta[0] = 18000, a slope of tan(0) = 0.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    assertThat(metadata.headroomAdaptiveToneMap.alternateImages).hasSize(4);
    assertThat(metadata.headroomAdaptiveToneMap.alternateImages.get(3).hdrHeadroom).isEqualTo(3f);
    assertThat(metadata.headroomAdaptiveToneMap.alternateImages.get(3).gainCurve.y)
        .isEqualTo(new float[] {-0.2f});
  }

  @Test
  public void parse_payloadWithTrailingBytes_ignoresTrailingBytes() throws Exception {
    HagcMetadata expectedMetadata = HagcMetadata.parse(getBytesFromHexString("008001F4"));
    byte[] payloadWithTrailingBytes = getBytesFromHexString("008001F4FFFF");

    HagcMetadata metadata = HagcMetadata.parse(payloadWithTrailingBytes);

    assertThat(metadata).isEqualTo(expectedMetadata);
  }

  @Test
  public void parse_payloadWithNonZeroReservedBits_ignoresReservedBits() throws Exception {
    HagcMetadata expectedMetadata = HagcMetadata.parse(getBytesFromHexString("008001F4"));
    byte[] payloadWithReservedBitsSet =
        getBytesFromHexString(
            "03" // Version 0, minimum version 0, reserved bits set.
                + "BF" // Custom reference white, no adaptive tone map, reserved bits set.
                + "01F4"); // Reference white 100 nits.

    HagcMetadata metadata = HagcMetadata.parse(payloadWithReservedBitsSet);

    assertThat(metadata).isEqualTo(expectedMetadata);
  }

  @Test
  public void parse_payloadWithReferenceWhiteToneMappingAndReservedBitsSet_ignoresReservedBits()
      throws Exception {
    HagcMetadata expectedMetadata = HagcMetadata.parse(getBytesFromHexString("00C001F407D080"));
    byte[] payloadWithReservedBitsSet =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "C0" // Custom reference white, adaptive tone map.
                + "01F4" // Reference white 100 nits.
                + "07D0" // Baseline headroom 0.2.
                + "FF"); // Reference white tone mapping, reserved bits set.

    HagcMetadata metadata = HagcMetadata.parse(payloadWithReservedBitsSet);

    assertThat(metadata).isEqualTo(expectedMetadata);
  }

  @Test
  public void parse_payloadWithExplicitToneMapAndReservedBitsSet_ignoresReservedBits()
      throws Exception {
    HagcMetadata expectedMetadata =
        HagcMetadata.parse(
            getBytesFromHexString(
                "00" // Version 0, minimum version 0.
                    + "40" // Default reference white, adaptive tone map.
                    + "2710" // Baseline headroom 1.
                    + "18" // 1 alternate image, BT.2020 primaries, no shared parameters.
                    + "0000" // Alternate headroom 0.
                    + "00" // Component mixing type 0 (max).
                    + "00" // 1 control point, explicit slopes.
                    + "03E8" // x[0] = 1.
                    + "1388" // y[0] = -0.5.
                    + "4650")); // theta[0] = 18000, a slope of tan(0) = 0.
    byte[] payloadWithReservedBitsSet =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "2710" // Baseline headroom 1.
                + "18" // 1 alternate image, BT.2020 primaries, no shared parameters.
                + "0000" // Alternate headroom 0.
                + "3F" // Component mixing type 0 (max), reserved bits set.
                + "03" // 1 control point, explicit slopes, reserved bits set.
                + "03E8" // x[0] = 1.
                + "1388" // y[0] = -0.5.
                + "4650"); // theta[0] = 18000, a slope of tan(0) = 0.

    HagcMetadata metadata = HagcMetadata.parse(payloadWithReservedBitsSet);

    assertThat(metadata).isEqualTo(expectedMetadata);
  }

  @Test
  public void parse_nonZeroApplicationVersion_returnsParsedMetadata() throws Exception {
    HagcMetadata expectedMetadata = HagcMetadata.parse(getBytesFromHexString("008001F4"));
    byte[] payloadWithApplicationVersion7 =
        getBytesFromHexString(
            "E0" // Version 7, minimum version 0.
                + "8001F4");

    HagcMetadata metadata = HagcMetadata.parse(payloadWithApplicationVersion7);

    assertThat(metadata).isEqualTo(expectedMetadata);
  }

  @Test
  public void parse_unsupportedMinimumApplicationVersion_returnsNull() throws Exception {
    byte[] payload =
        getBytesFromHexString(
            "04" // Version 0, minimum version 1.
                + "FF"); // Data that would be truncated if it was parsed.

    HagcMetadata metadata = HagcMetadata.parse(payload);

    assertThat(metadata).isNull();
  }

  @Test
  public void parse_emptyPayload_throwsParserException() {
    byte[] payload = new byte[0];

    assertThrows(ParserException.class, () -> HagcMetadata.parse(payload));
  }

  @Test
  public void parse_truncatedPayload_throwsParserException() {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "80" // Custom reference white, no adaptive tone map.
                + "01"); // Only the first byte of the reference white.

    assertThrows(ParserException.class, () -> HagcMetadata.parse(payload));
  }

  @Test
  public void parse_alternateHeadroomEqualToBaseline_throwsParserException() {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "2710" // Baseline headroom 1.
                + "18" // 1 alternate image, BT.2020 primaries, no shared parameters.
                + "2710" // Alternate headroom 1.
                + "00" // Component mixing type 0 (max).
                + "00" // 1 control point, explicit slopes.
                + "03E8" // x[0] = 1.
                + "1388" // y[0] = -0.5.
                + "4650"); // theta[0] = 18000, a slope of tan(0) = 0.

    assertThrows(ParserException.class, () -> HagcMetadata.parse(payload));
  }

  @Test
  public void parse_alternateHeadroomsNotIncreasing_throwsParserException() {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "EA60" // Baseline headroom 6.
                + "2B" // 2 alternate images, BT.2020 primaries, shared mix and curve parameters.
                + "2710" // Alternate 0: headroom 1.
                + "00" // Alternate 0: component mixing type 0 (max).
                + "00" // Alternate 0: 1 control point, explicit slopes.
                + "03E8" // Alternate 0: x[0] = 1.
                + "1388" // Alternate 0: y[0] = -0.5.
                + "4650" // Alternate 0: theta[0] = 18000, a slope of tan(0) = 0.
                + "2710" // Alternate 1: headroom 1.
                + "0FA0" // Alternate 1: y[0] = -0.4.
                + "4650"); // Alternate 1: theta[0] = 18000, a slope of tan(0) = 0.

    assertThrows(ParserException.class, () -> HagcMetadata.parse(payload));
  }

  @Test
  public void parse_customChromaticitiesWithWhitePointOutsidePrimaries_throwsParserException() {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "2710" // Baseline headroom 1.
                + "0C" // No alternate images, custom primaries.
                + "84D03E80" // Red (34000, 16000) / 50000 = (0.68, 0.32).
                + "33C286C4" // Green (0.265, 0.69).
                + "1D4C0BB8" // Blue (0.15, 0.06).
                + "AFC8" // White x = 0.9.
                + "AFC8"); // White y = 0.9.

    assertThrows(ParserException.class, () -> HagcMetadata.parse(payload));
  }

  @Test
  public void parse_customChromaticitiesWithCollinearPrimaries_throwsParserException() {
    // The primaries are collinear, so their triangle has zero area. Checking this with decoded
    // floating point values doesn't give exactly zero for these values.
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "2710" // Baseline headroom 1.
                + "0C" // No alternate images, custom primaries.
                + "012D30CB" // Red (301, 12491) / 50000.
                + "02E9342F" // Green (745, 13359) / 50000.
                + "1EA96A6F" // Blue (7849, 27247) / 50000.
                + "0DC04960"); // White (3520, 18784) / 50000, on the same line.

    assertThrows(ParserException.class, () -> HagcMetadata.parse(payload));
  }

  @Test
  public void parse_customChromaticitiesWithWhitePointOnEdge_throwsParserException() {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "2710" // Baseline headroom 1.
                + "0C" // No alternate images, custom primaries.
                + "9C402710" // Red (40000, 10000) / 50000 = (0.8, 0.2).
                + "27109C40" // Green (0.2, 0.8).
                + "13881388" // Blue (0.1, 0.1).
                + "61A861A8"); // White (0.5, 0.5), on the edge between red and green.

    assertThrows(ParserException.class, () -> HagcMetadata.parse(payload));
  }

  @Test
  public void parse_customComponentMixingCoefficientsSummingToZero_throwsParserException() {
    byte[] payload =
        getBytesFromHexString(
            "00" // Version 0, minimum version 0.
                + "40" // Default reference white, adaptive tone map.
                + "2710" // Baseline headroom 1.
                + "18" // 1 alternate image, BT.2020 primaries, no shared parameters.
                + "0000" // Alternate headroom 0.
                + "C0" // Custom component mixing, without coefficients.
                + "00" // 1 control point, explicit slopes.
                + "03E8" // x[0] = 1.
                + "1388" // y[0] = -0.5.
                + "4650"); // theta[0] = 18000, a slope of tan(0) = 0.

    assertThrows(ParserException.class, () -> HagcMetadata.parse(payload));
  }

  @Test
  public void parse_decreasingControlPointX_throwsParserException() {
    byte[] payload =
        getBytesFromHexString(
            ONE_ALTERNATE_IMAGE_PAYLOAD_PREFIX
                + "08" // 2 control points, explicit slopes.
                + "07D0" // x[0] = 2.
                + "03E8" // x[1] = 1.
                + "0000" // y[0] = 0.
                + "1388" // y[1] = -0.5.
                + "4650" // theta[0] = 18000, a slope of tan(0) = 0.
                + "4650"); // theta[1] = 18000, a slope of tan(0) = 0.

    assertThrows(ParserException.class, () -> HagcMetadata.parse(payload));
  }

  @Test
  public void parse_equalControlPointXWithDifferentY_throwsParserException() {
    byte[] payload =
        getBytesFromHexString(
            ONE_ALTERNATE_IMAGE_PAYLOAD_PREFIX
                + "08" // 2 control points, explicit slopes.
                + "03E8" // x[0] = 1.
                + "03E8" // x[1] = 1.
                + "0000" // y[0] = 0.
                + "1388" // y[1] = -0.5.
                + "4650" // theta[0] = 18000, a slope of tan(0) = 0.
                + "4650"); // theta[1] = 18000, a slope of tan(0) = 0.

    assertThrows(ParserException.class, () -> HagcMetadata.parse(payload));
  }

  /**
   * Asserts that a gain curve has the expected control points, within {@link #TOLERANCE}.
   *
   * @param gainCurve The gain curve.
   * @param expectedControlPoints The expected control points, as {@code {x, y, slope}}.
   */
  private static void assertGainCurveIsApproximately(
      GainCurve gainCurve, float[][] expectedControlPoints) {
    assertThat(gainCurve.x).hasLength(expectedControlPoints.length);
    for (int i = 0; i < expectedControlPoints.length; i++) {
      assertWithMessage("x[%s]", i)
          .that(gainCurve.x[i])
          .isWithin(TOLERANCE)
          .of(expectedControlPoints[i][0]);
      assertWithMessage("y[%s]", i)
          .that(gainCurve.y[i])
          .isWithin(TOLERANCE)
          .of(expectedControlPoints[i][1]);
      assertWithMessage("slopes[%s]", i)
          .that(gainCurve.slopes[i])
          .isWithin(TOLERANCE)
          .of(expectedControlPoints[i][2]);
    }
  }
}
