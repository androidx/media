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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.math.DoubleMath.log2;
import static java.lang.Math.abs;
import static java.lang.Math.min;
import static java.lang.Math.pow;
import static java.lang.Math.signum;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableBitArray;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Represents SMPTE ST 2094-50 (HAGC) dynamic HDR metadata of a frame.
 *
 * <p>Holds {@link Frame#KEY_HDR_ST2094_50_INFO} metadata. The bitstream format is defined in Annex
 * C of <a href="https://pub.smpte.org/pub/st2094-50/">SMPTE ST 2094-50:2026</a>.
 */
/* package */ final class HagcMetadata {

  /** The {@link #hdrReferenceWhiteNits} of a payload that doesn't specify one. */
  public static final float DEFAULT_HDR_REFERENCE_WHITE_NITS = 203f;

  // The highest minimum_application_version that this class can parse. Payloads that require a
  // higher version are ignored (Clause C.2.1).
  private static final int IMPLEMENTED_APPLICATION_VERSION = 0;

  // The maximum number of alternate images (Clause 6.2.2).
  private static final int MAX_NUM_ALTERNATE_IMAGES = 4;

  // The number of chromaticity coordinates of a gain application color space: x and y for the
  // red, green and blue primaries and the white point (Clause 6.2.1).
  private static final int NUM_CHROMATICITIES = 8;

  // The number of component mixing coefficients (Clause 6.4.1).
  private static final int NUM_COMPONENT_MIXING_COEFFICIENTS = 6;

  // Values of the gain_application_space_chromaticities_mode syntax element, which selects the
  // color space in which gains are computed and applied (Clause C.3.5).
  // CHROMATICITIES_MODE_CUSTOM: the payload lists the chromaticities of the color space.
  private static final int CHROMATICITIES_MODE_BT709 = 0;
  private static final int CHROMATICITIES_MODE_P3 = 1;
  private static final int CHROMATICITIES_MODE_BT2020 = 2;
  private static final int CHROMATICITIES_MODE_CUSTOM = 3;

  // Values of the component_mixing_type syntax element, which selects how the red, green and blue
  // components of a color are combined into the input of a gain curve (Clause C.3.6).
  //
  // COMPONENT_MIXING_TYPE_MAX: the input is the maximum of the components.
  // COMPONENT_MIXING_TYPE_COMPONENT: the input is each component on its own, which gives a separate
  //     gain per component.
  // COMPONENT_MIXING_TYPE_RGB_AND_MAX: the input is half the average of the components plus half
  //     their maximum.
  // COMPONENT_MIXING_TYPE_CUSTOM: the payload lists the weights of the combination.
  private static final int COMPONENT_MIXING_TYPE_MAX = 0;
  private static final int COMPONENT_MIXING_TYPE_COMPONENT = 1;
  private static final int COMPONENT_MIXING_TYPE_RGB_AND_MAX = 2;
  private static final int COMPONENT_MIXING_TYPE_CUSTOM = 3;

  // The maximum value of hdr_reference_white, and the divisor that maps it to nits, when
  // has_custom_hdr_reference_white_flag is 1 (Clause C.3.3).
  private static final int MAX_HDR_REFERENCE_WHITE_CODE = 50_000;
  private static final float HDR_REFERENCE_WHITE_DIVISOR = 5f;

  // The divisor that maps baseline_hdr_headroom and alternate_hdr_headrooms to log2 headrooms, and
  // the maximum log2 headroom (Clause C.3.4).
  private static final float HDR_HEADROOM_SCALE = 10_000f;
  private static final float MAX_HDR_HEADROOM = 6f;

  // The divisor that maps gain_application_space_chromaticities and component_mixing_coefficient
  // to the unit interval, which is also their maximum value (Clauses C.3.5 and C.3.6).
  private static final int UNIT_INTERVAL_SCALE = 50_000;

  // The divisors that map gain_curve_control_points_x and gain_curve_control_points_y to control
  // point coordinates, and the maximum coordinates (Clause C.3.7).
  private static final float CONTROL_POINT_X_SCALE = 1_000f;
  private static final float MAX_CONTROL_POINT_X = 64f;
  private static final float CONTROL_POINT_Y_SCALE = 10_000f;
  private static final float MAX_CONTROL_POINT_Y = 6f;

  // The limits of gain_curve_control_points_theta, and the offset and divisor that map it to an
  // angle (Clause C.3.7).
  private static final int MIN_CONTROL_POINT_THETA = 1;
  private static final int MAX_CONTROL_POINT_THETA = 35_999;
  private static final int CONTROL_POINT_THETA_OFFSET = 18_000;
  private static final double CONTROL_POINT_THETA_SCALE = 36_000;

  // The baseline headroom at which the reference white tone mapping (RWTM, Clause C.3.8) scaling
  // saturates: log2 of a 1,000-nit peak over a 203-nit reference white (Formulas (C.1) and (C.2)).
  private static final double RWTM_REFERENCE_HEADROOM = log2(1000 / 203.0);

  // The maximum headroom of the second (intermediate HDR) RWTM alternate image (Formula (C.1)).
  private static final double RWTM_MAX_SECOND_ALTERNATE_HEADROOM = log2(8 / 3.0);

  // The number of control points of each RWTM gain curve, N_cp in Clause C.3.8.
  private static final int RWTM_NUM_CONTROL_POINTS = 8;

  // The RWTM highlight compression factor, κ in Formula (C.3).
  private static final double RWTM_KAPPA = 0.65;

  // The color spaces that a payload can select as its gain application color space (Clause C.3.5),
  // described by the CIE 1931 xy chromaticity coordinates of their red, green and blue primaries
  // and white point, in the order [redX, redY, greenX, greenY, blueX, blueY, whiteX, whiteY].
  private static final float[] BT709_CHROMATICITIES = {
    0.64f, 0.33f, 0.3f, 0.6f, 0.15f, 0.06f, 0.3127f, 0.329f
  };
  private static final float[] P3_CHROMATICITIES = {
    0.68f, 0.32f, 0.265f, 0.69f, 0.15f, 0.06f, 0.3127f, 0.329f
  };
  private static final float[] BT2020_CHROMATICITIES = {
    0.708f, 0.292f, 0.17f, 0.797f, 0.131f, 0.046f, 0.3127f, 0.329f
  };

  /**
   * The luminance of HDR reference white, in nits. Headrooms and gain curve inputs are relative to
   * this luminance.
   */
  public final float hdrReferenceWhiteNits;

  /**
   * The headroom-adaptive tone map, or {@code null} if the payload doesn't specify one, in which
   * case the output system decides how to tone map (Clause A.3).
   */
  public final @Nullable HeadroomAdaptiveToneMap headroomAdaptiveToneMap;

  private HagcMetadata(
      float hdrReferenceWhiteNits, @Nullable HeadroomAdaptiveToneMap headroomAdaptiveToneMap) {
    this.hdrReferenceWhiteNits = hdrReferenceWhiteNits;
    this.headroomAdaptiveToneMap = headroomAdaptiveToneMap;
  }

  /**
   * Parses an SMPTE ST 2094-50 payload.
   *
   * <p>Data after the last syntax element is ignored.
   *
   * @param payload The {@code smpte_st_2094_50_application_info()} bitstream specified in Annex C
   *     of SMPTE ST 2094-50, without the ITU-T T.35 header.
   * @return The parsed metadata, or {@code null} if the payload's minimum application version is
   *     higher than the version this class implements, in which case the specification requires the
   *     payload to be ignored.
   * @throws ParserException If the payload is truncated or violates a constraint of the
   *     specification.
   */
  public static @Nullable HagcMetadata parse(byte[] payload) throws ParserException {
    ParsableBitArray data = new ParsableBitArray(payload);
    try {
      return parseApplicationInfo(data);
    } catch (RuntimeException e) {
      throw ParserException.createForMalformedDataOfUnknownType("Error parsing HAGC metadata", e);
    }
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof HagcMetadata)) {
      return false;
    }
    HagcMetadata other = (HagcMetadata) o;
    return Float.compare(hdrReferenceWhiteNits, other.hdrReferenceWhiteNits) == 0
        && Objects.equals(headroomAdaptiveToneMap, other.headroomAdaptiveToneMap);
  }

  @Override
  public int hashCode() {
    return Objects.hash(hdrReferenceWhiteNits, headroomAdaptiveToneMap);
  }

  private static @Nullable HagcMetadata parseApplicationInfo(ParsableBitArray data)
      throws ParserException {
    data.skipBits(3); // application_version
    int minimumApplicationVersion = data.readBits(3);
    data.skipBits(2); // reserved_zero
    if (minimumApplicationVersion > IMPLEMENTED_APPLICATION_VERSION) {
      return null;
    }

    boolean hasCustomHdrReferenceWhite = data.readBit();
    boolean hasAdaptiveToneMap = data.readBit();
    data.skipBits(6); // reserved_zero
    float hdrReferenceWhiteNits =
        hasCustomHdrReferenceWhite
            ? Util.constrainValue(
                    data.readBits(16), /* min= */ 1, /* max= */ MAX_HDR_REFERENCE_WHITE_CODE)
                / HDR_REFERENCE_WHITE_DIVISOR
            : DEFAULT_HDR_REFERENCE_WHITE_NITS;
    return new HagcMetadata(
        hdrReferenceWhiteNits, hasAdaptiveToneMap ? parseHeadroomAdaptiveToneMap(data) : null);
  }

  private static HeadroomAdaptiveToneMap parseHeadroomAdaptiveToneMap(ParsableBitArray data)
      throws ParserException {
    // As in Clause C.3.7, the sign of the gains depends on the headrooms before clamping.
    float unclampedBaselineHdrHeadroom = data.readBits(16) / HDR_HEADROOM_SCALE;
    float baselineHdrHeadroom = min(unclampedBaselineHdrHeadroom, MAX_HDR_HEADROOM);
    boolean useReferenceWhiteToneMapping = data.readBit();
    if (useReferenceWhiteToneMapping) {
      data.skipBits(7); // reserved_zero
      return createReferenceWhiteToneMap(baselineHdrHeadroom);
    }

    int numAlternateImages = min(data.readBits(3), MAX_NUM_ALTERNATE_IMAGES);
    int chromaticitiesMode = data.readBits(2);
    boolean hasCommonComponentMix = data.readBit();
    boolean hasCommonCurveParams = data.readBit();
    float @Nullable [] customChromaticities =
        chromaticitiesMode == CHROMATICITIES_MODE_CUSTOM ? parseCustomChromaticities(data) : null;

    ImmutableList.Builder<AlternateImage> alternateImages = ImmutableList.builder();
    @Nullable ComponentMix componentMix = null;
    @Nullable GainCurveParams gainCurveParams = null;
    float previousAlternateHdrHeadroom = 0;
    for (int a = 0; a < numAlternateImages; a++) {
      float unclampedAlternateHdrHeadroom = data.readBits(16) / HDR_HEADROOM_SCALE;
      float alternateHdrHeadroom = min(unclampedAlternateHdrHeadroom, MAX_HDR_HEADROOM);
      // Clause 6.2.2 requires the alternate HDR headrooms to differ from the baseline HDR headroom,
      // and to be in increasing order.
      if (alternateHdrHeadroom == baselineHdrHeadroom) {
        throw ParserException.createForMalformedDataOfUnknownType(
            "Alternate HDR headroom equal to the baseline HDR headroom", /* cause= */ null);
      }
      if (a > 0 && alternateHdrHeadroom <= previousAlternateHdrHeadroom) {
        throw ParserException.createForMalformedDataOfUnknownType(
            "Alternate HDR headrooms not in increasing order", /* cause= */ null);
      }
      previousAlternateHdrHeadroom = alternateHdrHeadroom;

      // If the component mix or the gain curve parameters are shared, the alternate images after
      // the first reuse those of the first, as in the else branches of Tables C.4 and C.5.
      if (a == 0 || !hasCommonComponentMix) {
        componentMix = parseComponentMix(data);
      }
      // Gains are positive for alternate images above the baseline, and negative otherwise.
      boolean isAboveBaseline = unclampedBaselineHdrHeadroom < unclampedAlternateHdrHeadroom;
      if (a == 0 || !hasCommonCurveParams) {
        gainCurveParams = parseGainCurveParams(data);
      }
      GainCurve gainCurve = parseGainCurve(data, checkNotNull(gainCurveParams), isAboveBaseline);
      alternateImages.add(
          new AlternateImage(alternateHdrHeadroom, checkNotNull(componentMix), gainCurve));
    }
    return customChromaticities != null
        ? new HeadroomAdaptiveToneMap(
            baselineHdrHeadroom, customChromaticities, alternateImages.build())
        : new HeadroomAdaptiveToneMap(
            baselineHdrHeadroom,
            getGainApplicationColorSpace(chromaticitiesMode),
            alternateImages.build());
  }

  /**
   * Returns the headroom-adaptive tone map of the reference white tone mapping, as specified in
   * Clause C.3.8.
   */
  private static HeadroomAdaptiveToneMap createReferenceWhiteToneMap(float baselineHdrHeadroom) {
    if (baselineHdrHeadroom == 0) {
      return new HeadroomAdaptiveToneMap(
          baselineHdrHeadroom, C.COLOR_SPACE_BT2020, /* alternateImages= */ ImmutableList.of());
    }
    double baselineHeadroomRatio = min(baselineHdrHeadroom / RWTM_REFERENCE_HEADROOM, 1.0);
    // Formulas (C.1) and (C.2).
    double[] alternateHdrHeadrooms = {
      0, RWTM_MAX_SECOND_ALTERNATE_HEADROOM * baselineHeadroomRatio
    };
    double[] whiteYs = {1 - 0.5 * baselineHeadroomRatio, 1};
    ImmutableList.Builder<AlternateImage> alternateImages = ImmutableList.builder();
    for (int i = 0; i < alternateHdrHeadrooms.length; i++) {
      GainCurve gainCurve =
          createBezierGainCurve(
              /* xKnee= */ 1,
              /* yKnee= */ whiteYs[i],
              /* xMax= */ pow(2, baselineHdrHeadroom),
              /* yMax= */ pow(2, alternateHdrHeadrooms[i]));
      alternateImages.add(
          new AlternateImage((float) alternateHdrHeadrooms[i], ComponentMix.MAX, gainCurve));
    }
    return new HeadroomAdaptiveToneMap(
        baselineHdrHeadroom, C.COLOR_SPACE_BT2020, alternateImages.build());
  }

  /**
   * Returns the gain curve of a tone curve defined by a quadratic Bézier curve with a knee, as
   * specified by Formulas (C.3) to (C.6).
   */
  private static GainCurve createBezierGainCurve(
      double xKnee, double yKnee, double xMax, double yMax) {
    // Formula (C.3).
    double xMid = (1 - RWTM_KAPPA) * xKnee + RWTM_KAPPA * (xKnee * yMax / yKnee);
    double yMid = (1 - RWTM_KAPPA) * yKnee + RWTM_KAPPA * yMax;
    // Formula (C.5).
    double ax = xKnee - 2 * xMid + xMax;
    double bx = 2 * xMid - 2 * xKnee;
    double ay = yKnee - 2 * yMid + yMax;
    double by = 2 * yMid - 2 * yKnee;
    int numControlPoints = RWTM_NUM_CONTROL_POINTS;
    float[] x = new float[numControlPoints];
    float[] y = new float[numControlPoints];
    float[] slopes = new float[numControlPoints];
    for (int i = 0; i < numControlPoints; i++) {
      double t = i / (numControlPoints - 1.0);
      // Formula (C.4).
      double curveX = ax * t * t + bx * t + xKnee;
      double curveY = ay * t * t + by * t + yKnee;
      double curveSlope = (2 * ay * t + by) / (2 * ax * t + bx);
      // Formula (C.6).
      x[i] = (float) curveX;
      y[i] = (float) log2(curveY / curveX);
      slopes[i] = (float) ((curveX * curveSlope - curveY) / (Math.log(2) * curveX * curveY));
    }
    return new GainCurve(x, y, slopes);
  }

  /** Returns the color space of a predefined gain application space chromaticities mode. */
  private static @C.ColorSpace int getGainApplicationColorSpace(int chromaticitiesMode) {
    switch (chromaticitiesMode) {
      case CHROMATICITIES_MODE_BT709:
        return C.COLOR_SPACE_BT709;
      case CHROMATICITIES_MODE_P3:
        return C.COLOR_SPACE_DISPLAY_P3;
      case CHROMATICITIES_MODE_BT2020:
        return C.COLOR_SPACE_BT2020;
      default:
        throw new IllegalArgumentException("Not a predefined mode: " + chromaticitiesMode);
    }
  }

  /** Returns the chromaticities of one of the predefined gain application color spaces. */
  private static float[] getChromaticities(@C.ColorSpace int colorSpace) {
    switch (colorSpace) {
      case C.COLOR_SPACE_BT709:
        return BT709_CHROMATICITIES.clone();
      case C.COLOR_SPACE_DISPLAY_P3:
        return P3_CHROMATICITIES.clone();
      case C.COLOR_SPACE_BT2020:
        return BT2020_CHROMATICITIES.clone();
      default:
        throw new IllegalArgumentException("Unsupported color space: " + colorSpace);
    }
  }

  private static float[] parseCustomChromaticities(ParsableBitArray data) throws ParserException {
    int[] chromaticityValues = new int[NUM_CHROMATICITIES];
    for (int r = 0; r < NUM_CHROMATICITIES; r++) {
      chromaticityValues[r] = min(data.readBits(16), UNIT_INTERVAL_SCALE);
    }
    checkPrimariesFormTriangleContainingWhitePoint(chromaticityValues);
    float[] chromaticities = new float[NUM_CHROMATICITIES];
    for (int r = 0; r < NUM_CHROMATICITIES; r++) {
      chromaticities[r] = chromaticityValues[r] / (float) UNIT_INTERVAL_SCALE;
    }
    return chromaticities;
  }

  /**
   * Throws if the primaries don't form a triangle with a non-zero area that has the white point in
   * its interior, as required by Clause 6.2.2.
   *
   * <p>The check uses the syntax element values, which are proportional to the chromaticities, so
   * that it's exact.
   *
   * @param chromaticityValues The gain_application_space_chromaticities values, as {@code [redX,
   *     redY, greenX, greenY, blueX, blueY, whiteX, whiteY]}.
   * @throws ParserException If the chromaticities don't satisfy the constraint.
   */
  private static void checkPrimariesFormTriangleContainingWhitePoint(int[] chromaticityValues)
      throws ParserException {
    int redX = chromaticityValues[0];
    int redY = chromaticityValues[1];
    int greenX = chromaticityValues[2];
    int greenY = chromaticityValues[3];
    int blueX = chromaticityValues[4];
    int blueY = chromaticityValues[5];
    int whiteX = chromaticityValues[6];
    int whiteY = chromaticityValues[7];
    // For points A, B and P, the cross product (B - A) x (P - A) is positive if P is to the left of
    // the line from A to B, negative if P is to the right, and 0 if the three points are collinear.
    // So (green - red) x (blue - red) is 0 if the triangle has a zero area, and otherwise its sign
    // gives the order of the vertices (counterclockwise if positive). Going around the edges in
    // that order, the triangle's interior is on the same side of each edge: the white point is in
    // the interior if it's strictly on that side of all three, so if the three cross products have
    // the triangle's sign. A white point on an edge gives 0, so it's rejected.
    long triangleOrientation =
        crossProduct(greenX - redX, greenY - redY, blueX - redX, blueY - redY);
    if (triangleOrientation == 0) {
      throw ParserException.createForMalformedDataOfUnknownType(
          "Gain application primaries form a triangle with zero area", /* cause= */ null);
    }
    long redGreenOrientation =
        crossProduct(greenX - redX, greenY - redY, whiteX - redX, whiteY - redY);
    long greenBlueOrientation =
        crossProduct(blueX - greenX, blueY - greenY, whiteX - greenX, whiteY - greenY);
    long blueRedOrientation =
        crossProduct(redX - blueX, redY - blueY, whiteX - blueX, whiteY - blueY);
    long triangleSign = Long.signum(triangleOrientation);
    if (Long.signum(redGreenOrientation) != triangleSign
        || Long.signum(greenBlueOrientation) != triangleSign
        || Long.signum(blueRedOrientation) != triangleSign) {
      throw ParserException.createForMalformedDataOfUnknownType(
          "Gain application white point not in the interior of the primaries' triangle",
          /* cause= */ null);
    }
  }

  /** Returns the z component of the cross product of the vectors (x1, y1) and (x2, y2). */
  private static long crossProduct(long x1, long y1, long x2, long y2) {
    return x1 * y2 - y1 * x2;
  }

  private static ComponentMix parseComponentMix(ParsableBitArray data) throws ParserException {
    int componentMixingType = data.readBits(2);
    switch (componentMixingType) {
      case COMPONENT_MIXING_TYPE_MAX:
        data.skipBits(6); // reserved_zero
        return ComponentMix.MAX;
      case COMPONENT_MIXING_TYPE_COMPONENT:
        data.skipBits(6); // reserved_zero
        return ComponentMix.COMPONENT;
      case COMPONENT_MIXING_TYPE_RGB_AND_MAX:
        data.skipBits(6); // reserved_zero
        return ComponentMix.RGB_AND_MAX;
      case COMPONENT_MIXING_TYPE_CUSTOM:
        boolean[] hasCoefficient = new boolean[NUM_COMPONENT_MIXING_COEFFICIENTS];
        for (int k = 0; k < NUM_COMPONENT_MIXING_COEFFICIENTS; k++) {
          hasCoefficient[k] = data.readBit();
        }
        float[] coefficients = new float[NUM_COMPONENT_MIXING_COEFFICIENTS];
        float coefficientSum = 0;
        for (int k = 0; k < NUM_COMPONENT_MIXING_COEFFICIENTS; k++) {
          if (hasCoefficient[k]) {
            coefficients[k] = min(data.readBits(16) / (float) UNIT_INTERVAL_SCALE, 1f);
            coefficientSum += coefficients[k];
          }
        }
        // Clause 6.4.2 requires the coefficients to sum to more than 0.
        if (coefficientSum == 0) {
          throw ParserException.createForMalformedDataOfUnknownType(
              "Component mixing coefficients sum to zero", /* cause= */ null);
        }
        return new ComponentMix(
            coefficients[0],
            coefficients[1],
            coefficients[2],
            coefficients[3],
            coefficients[4],
            coefficients[5]);
      default:
        throw new IllegalStateException();
    }
  }

  /**
   * Parses the gain curve parameters that alternate images can share
   * (has_common_curve_params_flag): the number of control points, whether the slopes are computed
   * with piecewise cubic Hermite interpolation (PCHIP), and the x coordinates of the control
   * points.
   */
  private static GainCurveParams parseGainCurveParams(ParsableBitArray data)
      throws ParserException {
    int numControlPoints = data.readBits(5) + 1;
    boolean usePchipSlopes = data.readBit();
    data.skipBits(2); // reserved_zero
    float[] controlPointsX = new float[numControlPoints];
    for (int c = 0; c < numControlPoints; c++) {
      controlPointsX[c] = min(data.readBits(16) / CONTROL_POINT_X_SCALE, MAX_CONTROL_POINT_X);
      // Clause 6.5.2 requires the x coordinates to be in non-decreasing order.
      if (c > 0 && controlPointsX[c] < controlPointsX[c - 1]) {
        throw ParserException.createForMalformedDataOfUnknownType(
            "Gain curve control point x coordinates not in non-decreasing order",
            /* cause= */ null);
      }
    }
    return new GainCurveParams(controlPointsX, usePchipSlopes);
  }

  /**
   * Parses the y coordinates and slopes of a gain curve.
   *
   * @param data The data to parse from.
   * @param params The parameters of the gain curve that alternate images can share.
   * @param isAboveBaseline Whether the alternate image's HDR headroom is above the baseline HDR
   *     headroom, which makes the gains positive rather than negative.
   * @return The gain curve.
   * @throws ParserException If the gain curve violates a constraint of the specification.
   */
  private static GainCurve parseGainCurve(
      ParsableBitArray data, GainCurveParams params, boolean isAboveBaseline)
      throws ParserException {
    float[] controlPointsX = params.x.clone();
    float[] controlPointsY = new float[controlPointsX.length];
    float previousYMagnitude = 0;
    for (int c = 0; c < controlPointsX.length; c++) {
      float yMagnitude = min(data.readBits(16) / CONTROL_POINT_Y_SCALE, MAX_CONTROL_POINT_Y);
      // Clause 6.5.2 requires control points with equal x coordinates to have equal y coordinates.
      if (c > 0 && controlPointsX[c] == controlPointsX[c - 1] && yMagnitude != previousYMagnitude) {
        throw ParserException.createForMalformedDataOfUnknownType(
            "Gain curve control points with equal x coordinates and different y coordinates",
            /* cause= */ null);
      }
      // Subtracting from 0 rather than negating keeps a zero gain positive zero.
      controlPointsY[c] = isAboveBaseline ? yMagnitude : 0 - yMagnitude;
      previousYMagnitude = yMagnitude;
    }

    if (params.usePchipSlopes) {
      return new GainCurve(
          controlPointsX, controlPointsY, computePchipSlopes(controlPointsX, controlPointsY));
    }
    float[] slopes = new float[controlPointsX.length];
    for (int c = 0; c < controlPointsX.length; c++) {
      int theta =
          Util.constrainValue(data.readBits(16), MIN_CONTROL_POINT_THETA, MAX_CONTROL_POINT_THETA);
      slopes[c] =
          (float)
              Math.tan((theta - CONTROL_POINT_THETA_OFFSET) * Math.PI / CONTROL_POINT_THETA_SCALE);
    }
    return new GainCurve(controlPointsX, controlPointsY, slopes);
  }

  /**
   * Returns the slopes of a gain curve's control points computed with piecewise cubic Hermite
   * interpolation (PCHIP), as specified in Clause C.3.9.
   */
  private static float[] computePchipSlopes(float[] x, float[] y) {
    int numControlPoints = x.length;
    // The width (h in Clause C.3.9) and slope (s) of each interval between control points,
    // Formula (C.7).
    double[] widths = new double[numControlPoints - 1];
    double[] intervalSlopes = new double[numControlPoints - 1];
    for (int i = 0; i < numControlPoints - 1; i++) {
      widths[i] = (double) x[i + 1] - x[i];
      intervalSlopes[i] = widths[i] == 0 ? 0 : ((double) y[i + 1] - y[i]) / widths[i];
    }

    // The slopes of the control points (m in Clause C.3.9).
    float[] slopes = new float[numControlPoints];
    for (int i = 0; i < numControlPoints; i++) {
      boolean hasLeftInterval = i > 0 && x[i - 1] < x[i];
      boolean hasRightInterval = i + 1 < numControlPoints && x[i] < x[i + 1];
      double slope;
      if (hasLeftInterval && hasRightInterval) {
        // Interior control point, Formula (C.8).
        double leftSlope = intervalSlopes[i - 1];
        double rightSlope = intervalSlopes[i];
        if (signum(leftSlope) != signum(rightSlope) || (leftSlope == 0 && rightSlope == 0)) {
          slope = 0;
        } else {
          slope =
              3
                  * (widths[i - 1] + widths[i])
                  * leftSlope
                  * rightSlope
                  / ((2 * widths[i - 1] + widths[i]) * leftSlope
                      + (widths[i - 1] + 2 * widths[i]) * rightSlope);
        }
      } else if (hasRightInterval) {
        // Left control point, Formula (C.9). It only has an interval on its right: it's the first
        // control point, or the one before it has the same x coordinate.
        slope =
            i + 2 < numControlPoints && x[i + 1] < x[i + 2]
                ? computeEndControlPointSlope(
                    widths[i], widths[i + 1], intervalSlopes[i], intervalSlopes[i + 1])
                : intervalSlopes[i];
      } else if (hasLeftInterval) {
        // Right control point, Formula (C.10). It only has an interval on its left: it's the last
        // control point, or the one after it has the same x coordinate.
        slope =
            i >= 2 && x[i - 2] < x[i - 1]
                ? computeEndControlPointSlope(
                    widths[i - 1], widths[i - 2], intervalSlopes[i - 1], intervalSlopes[i - 2])
                : intervalSlopes[i - 1];
      } else {
        // Degenerate control point.
        slope = 0;
      }
      slopes[i] = (float) slope;
    }
    return slopes;
  }

  /**
   * Returns the slope of a control point at the end of a run of increasing control points, as
   * specified in Clause C.3.9: the three-point finite difference of Formula (C.9) or (C.10), set to
   * 0 if its sign differs from the slope of the nearer interval, and limited to three times that
   * slope if its sign differs from the slope of the farther interval and it's more than three times
   * as steep as the nearer interval.
   *
   * @param nearWidth The width of the interval next to the control point.
   * @param farWidth The width of the interval after that one.
   * @param nearSlope The slope of the interval next to the control point.
   * @param farSlope The slope of the interval after that one.
   * @return The limited slope.
   */
  private static double computeEndControlPointSlope(
      double nearWidth, double farWidth, double nearSlope, double farSlope) {
    double slope =
        ((2 * nearWidth + farWidth) * nearSlope - nearWidth * farSlope) / (nearWidth + farWidth);
    if (signum(slope) != signum(nearSlope)) {
      return 0;
    }
    if (signum(slope) != signum(farSlope) && abs(slope) > 3 * abs(nearSlope)) {
      return 3 * nearSlope;
    }
    return slope;
  }

  /** The headroom-adaptive tone map of a frame. */
  /* package */ static final class HeadroomAdaptiveToneMap {

    /**
     * The {@link #gainApplicationColorSpace} of a payload that lists the chromaticities of its gain
     * application color space.
     */
    public static final @C.ColorSpace int COLOR_SPACE_CUSTOM = Format.NO_VALUE;

    /** The baseline log2 HDR headroom, in the range [0, 6]. */
    public final float baselineHdrHeadroom;

    /**
     * The color space in which gain is applied, if the payload selects one of the predefined color
     * spaces of Clause C.3.5: {@link C#COLOR_SPACE_BT709}, {@link C#COLOR_SPACE_DISPLAY_P3} or
     * {@link C#COLOR_SPACE_BT2020}. {@link #COLOR_SPACE_CUSTOM} if the payload lists the
     * chromaticities of the color space.
     */
    public final @C.ColorSpace int gainApplicationColorSpace;

    /**
     * The chromaticities of the color space in which gain is applied, as {@code [redX, redY,
     * greenX, greenY, blueX, blueY, whiteX, whiteY]}. Must not be modified.
     */
    public final float[] gainApplicationSpaceChromaticities;

    /**
     * The alternate images, in increasing order of {@link AlternateImage#hdrHeadroom}. If empty,
     * the tone map is the identity (Clause B.4). For payloads that use the reference white tone
     * mapping, they're computed as specified in Clause C.3.8.
     */
    public final ImmutableList<AlternateImage> alternateImages;

    /** Creates an instance whose gain application color space is a predefined one. */
    /* package */ HeadroomAdaptiveToneMap(
        float baselineHdrHeadroom,
        @C.ColorSpace int gainApplicationColorSpace,
        ImmutableList<AlternateImage> alternateImages) {
      this(
          baselineHdrHeadroom,
          gainApplicationColorSpace,
          getChromaticities(gainApplicationColorSpace),
          alternateImages);
    }

    /** Creates an instance whose gain application color space has custom chromaticities. */
    /* package */ HeadroomAdaptiveToneMap(
        float baselineHdrHeadroom,
        float[] gainApplicationSpaceChromaticities,
        ImmutableList<AlternateImage> alternateImages) {
      this(
          baselineHdrHeadroom,
          COLOR_SPACE_CUSTOM,
          gainApplicationSpaceChromaticities,
          alternateImages);
    }

    private HeadroomAdaptiveToneMap(
        float baselineHdrHeadroom,
        @C.ColorSpace int gainApplicationColorSpace,
        float[] gainApplicationSpaceChromaticities,
        ImmutableList<AlternateImage> alternateImages) {
      this.baselineHdrHeadroom = baselineHdrHeadroom;
      this.gainApplicationColorSpace = gainApplicationColorSpace;
      this.gainApplicationSpaceChromaticities = gainApplicationSpaceChromaticities;
      this.alternateImages = alternateImages;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof HeadroomAdaptiveToneMap)) {
        return false;
      }
      HeadroomAdaptiveToneMap other = (HeadroomAdaptiveToneMap) o;
      return Float.compare(baselineHdrHeadroom, other.baselineHdrHeadroom) == 0
          && gainApplicationColorSpace == other.gainApplicationColorSpace
          && Arrays.equals(
              gainApplicationSpaceChromaticities, other.gainApplicationSpaceChromaticities)
          && alternateImages.equals(other.alternateImages);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          baselineHdrHeadroom,
          gainApplicationColorSpace,
          Arrays.hashCode(gainApplicationSpaceChromaticities),
          alternateImages);
    }
  }

  /** A rendition of the frame for an alternate HDR headroom, described as a gain to the frame. */
  /* package */ static final class AlternateImage {

    /** The log2 HDR headroom of the alternate image, in the range [0, 6]. */
    public final float hdrHeadroom;

    /** How the input of {@link #gainCurve} is computed from a color. */
    public final ComponentMix componentMix;

    /** The log2 gain to apply, as a function of the output of {@link #componentMix}. */
    public final GainCurve gainCurve;

    /* package */ AlternateImage(
        float hdrHeadroom, ComponentMix componentMix, GainCurve gainCurve) {
      this.hdrHeadroom = hdrHeadroom;
      this.componentMix = componentMix;
      this.gainCurve = gainCurve;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof AlternateImage)) {
        return false;
      }
      AlternateImage other = (AlternateImage) o;
      return Float.compare(hdrHeadroom, other.hdrHeadroom) == 0
          && componentMix.equals(other.componentMix)
          && gainCurve.equals(other.gainCurve);
    }

    @Override
    public int hashCode() {
      return Objects.hash(hdrHeadroom, componentMix, gainCurve);
    }
  }

  /**
   * Weights that combine the components of a color into the input of a {@link GainCurve}.
   *
   * <p>The weights are in the range [0, 1]. They're normalized to sum to 1 when the mix is created,
   * as when it's evaluated (Formula (10)), so that equivalent mixes are equal.
   */
  /* package */ static final class ComponentMix {

    /** The mix whose output is the maximum of the components. */
    public static final ComponentMix MAX =
        new ComponentMix(
            /* red= */ 0,
            /* green= */ 0,
            /* blue= */ 0,
            /* max= */ 1,
            /* min= */ 0,
            /* component= */ 0);

    /** The mix whose output is each component on its own. */
    public static final ComponentMix COMPONENT =
        new ComponentMix(
            /* red= */ 0,
            /* green= */ 0,
            /* blue= */ 0,
            /* max= */ 0,
            /* min= */ 0,
            /* component= */ 1);

    /** The mix whose output is half the average of the components plus half their maximum. */
    public static final ComponentMix RGB_AND_MAX =
        new ComponentMix(
            /* red= */ 1 / 6f,
            /* green= */ 1 / 6f,
            /* blue= */ 1 / 6f,
            /* max= */ 1 / 2f,
            /* min= */ 0,
            /* component= */ 0);

    /** The weight of the red component. */
    public final float red;

    /** The weight of the green component. */
    public final float green;

    /** The weight of the blue component. */
    public final float blue;

    /** The weight of the maximum of the red, green and blue components. */
    public final float max;

    /** The weight of the minimum of the red, green and blue components. */
    public final float min;

    /** The weight of each component on its own, which gives a separate gain per component. */
    public final float component;

    /**
     * Creates an instance, normalizing the weights so that they sum to 1.
     *
     * @throws IllegalArgumentException If the weights don't sum to more than 0.
     */
    /* package */ ComponentMix(
        float red, float green, float blue, float max, float min, float component) {
      double sum = (double) red + green + blue + max + min + component;
      checkArgument(sum > 0);
      this.red = (float) (red / sum);
      this.green = (float) (green / sum);
      this.blue = (float) (blue / sum);
      this.max = (float) (max / sum);
      this.min = (float) (min / sum);
      this.component = (float) (component / sum);
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof ComponentMix)) {
        return false;
      }
      ComponentMix other = (ComponentMix) o;
      return Float.compare(red, other.red) == 0
          && Float.compare(green, other.green) == 0
          && Float.compare(blue, other.blue) == 0
          && Float.compare(max, other.max) == 0
          && Float.compare(min, other.min) == 0
          && Float.compare(component, other.component) == 0;
    }

    @Override
    public int hashCode() {
      return Objects.hash(red, green, blue, max, min, component);
    }
  }

  /**
   * A curve that maps the output of a {@link ComponentMix} to a log2 gain, defined by 1 to 32
   * control points (Clause 6.5).
   *
   * <p>The curve is constant below the first control point and is a cubic Hermite spline between
   * control points. Past the last control point, the gain is {@code y[last] + log2(x[last] / v)}
   * for input {@code v}, so that the gained value stays constant (Formula (12)).
   *
   * <p>{@code x}, {@code y} and {@code slopes} have the same length, the number of control points.
   */
  /* package */ static final class GainCurve {

    /**
     * The x coordinates of the control points, in non-decreasing order and in the range [0, 64].
     * Must not be modified.
     */
    public final float[] x;

    /**
     * The y coordinates (log2 gains) of the control points, in the range [-6, 6]. Must not be
     * modified.
     */
    public final float[] y;

    /**
     * The slopes of the curve at the control points. If the payload doesn't specify them, they're
     * computed with piecewise cubic Hermite interpolation (Clause C.3.9). Must not be modified.
     */
    public final float[] slopes;

    /* package */ GainCurve(float[] x, float[] y, float[] slopes) {
      this.x = x;
      this.y = y;
      this.slopes = slopes;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof GainCurve)) {
        return false;
      }
      GainCurve other = (GainCurve) o;
      return Arrays.equals(x, other.x)
          && Arrays.equals(y, other.y)
          && Arrays.equals(slopes, other.slopes);
    }

    @Override
    public int hashCode() {
      return Objects.hash(Arrays.hashCode(x), Arrays.hashCode(y), Arrays.hashCode(slopes));
    }
  }

  /** The gain curve parameters that alternate images can share (has_common_curve_params_flag). */
  private static final class GainCurveParams {

    /** The x coordinates of the control points. */
    public final float[] x;

    /** Whether the slopes are computed with piecewise cubic Hermite interpolation (PCHIP). */
    public final boolean usePchipSlopes;

    public GainCurveParams(float[] x, boolean usePchipSlopes) {
      this.x = x;
      this.usePchipSlopes = usePchipSlopes;
    }
  }
}
