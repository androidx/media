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
 * <p>Payloads are written as hex strings, with one group per syntax element (or per byte of packed
 * flags) of Annex C of SMPTE ST 2094-50.
 */
@RunWith(AndroidJUnit4.class)
public final class HagcMetadataTest {

  /**
   * The tolerance for values computed with floating point arithmetic, which is smaller than one
   * encoding step of any syntax element. Decoded values are compared exactly.
   */
  private static final float TOLERANCE = 1e-6f;

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
    ComponentMix maxComponentMix =
        new ComponentMix(
            /* red= */ 0,
            /* green= */ 0,
            /* blue= */ 0,
            /* max= */ 1,
            /* min= */ 0,
            /* component= */ 0);
    AlternateImage alternateImage0 = toneMap.alternateImages.get(0);
    assertThat(alternateImage0.hdrHeadroom).isEqualTo(0f);
    assertThat(alternateImage0.componentMix).isEqualTo(maxComponentMix);
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
    assertThat(alternateImage1.componentMix).isEqualTo(maxComponentMix);
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
