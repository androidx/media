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

import static com.google.common.math.DoubleMath.log2;
import static java.lang.Math.min;
import static java.lang.Math.pow;

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

  // The maximum value of hdr_reference_white, and the divisor that maps it to nits, when
  // has_custom_hdr_reference_white_flag is 1 (Clause C.3.3).
  private static final int MAX_HDR_REFERENCE_WHITE_CODE = 50_000;
  private static final float HDR_REFERENCE_WHITE_DIVISOR = 5f;

  // The divisor that maps baseline_hdr_headroom and alternate_hdr_headrooms to log2 headrooms, and
  // the maximum log2 headroom (Clause C.3.4).
  private static final float HDR_HEADROOM_SCALE = 10_000f;
  private static final float MAX_HDR_HEADROOM = 6f;

  // The baseline headroom at which the reference white tone mapping (RWTM, Clause C.3.8) scaling
  // saturates: log2 of a 1,000-nit peak over a 203-nit reference white (Formulas (C.1) and (C.2)).
  private static final double RWTM_REFERENCE_HEADROOM = log2(1000 / 203.0);

  // The maximum headroom of the second (intermediate HDR) RWTM alternate image (Formula (C.1)).
  private static final double RWTM_MAX_SECOND_ALTERNATE_HEADROOM = log2(8 / 3.0);

  // The number of control points of each RWTM gain curve, N_cp in Clause C.3.8.
  private static final int RWTM_NUM_CONTROL_POINTS = 8;

  // The RWTM highlight compression factor, κ in Formula (C.3).
  private static final double RWTM_KAPPA = 0.65;

  // The CIE 1931 xy chromaticity coordinates of the BT.2020 red, green and blue primaries and
  // white point, in the order [redX, redY, greenX, greenY, blueX, blueY, whiteX, whiteY]. This
  // is the gain application color space of the reference white tone mapping (Clause C.3.8).
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
    float baselineHdrHeadroom = min(data.readBits(16) / HDR_HEADROOM_SCALE, MAX_HDR_HEADROOM);
    boolean useReferenceWhiteToneMapping = data.readBit();
    if (!useReferenceWhiteToneMapping) {
      // TODO: b/575451438 - Parse the alternate images as specified in Clause C.2.3.
      throw ParserException.createForUnsupportedContainerFeature(
          "HAGC headroom-adaptive tone maps without reference white tone mapping aren't"
              + " supported yet");
    }
    data.skipBits(7); // reserved_zero
    return createReferenceWhiteToneMap(baselineHdrHeadroom);
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
    ComponentMix componentMix =
        new ComponentMix(
            /* red= */ 0,
            /* green= */ 0,
            /* blue= */ 0,
            /* max= */ 1,
            /* min= */ 0,
            /* component= */ 0);
    ImmutableList.Builder<AlternateImage> alternateImages = ImmutableList.builder();
    for (int i = 0; i < alternateHdrHeadrooms.length; i++) {
      GainCurve gainCurve =
          createBezierGainCurve(
              /* xKnee= */ 1,
              /* yKnee= */ whiteYs[i],
              /* xMax= */ pow(2, baselineHdrHeadroom),
              /* yMax= */ pow(2, alternateHdrHeadrooms[i]));
      alternateImages.add(
          new AlternateImage((float) alternateHdrHeadrooms[i], componentMix, gainCurve));
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

  /** Returns the chromaticities of one of the predefined gain application color spaces. */
  private static float[] getChromaticities(@C.ColorSpace int colorSpace) {
    if (colorSpace != C.COLOR_SPACE_BT2020) {
      throw new IllegalArgumentException("Unsupported color space: " + colorSpace);
    }
    return BT2020_CHROMATICITIES.clone();
  }

  /** The headroom-adaptive tone map of a frame. */
  /* package */ static final class HeadroomAdaptiveToneMap {

    /** The baseline log2 HDR headroom, in the range [0, 6]. */
    public final float baselineHdrHeadroom;

    /**
     * The color space in which gain is applied, if the payload selects one of the predefined color
     * spaces of Clause C.3.5: {@link C#COLOR_SPACE_BT709}, {@link C#COLOR_SPACE_DISPLAY_P3} or
     * {@link C#COLOR_SPACE_BT2020}. {@link Format#NO_VALUE} if the payload lists the chromaticities
     * of the color space.
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
   * <p>The weights are in the range [0, 1] and are normalized to sum to 1, as when the mix is
   * evaluated (Formula (10)).
   */
  /* package */ static final class ComponentMix {

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

    /* package */ ComponentMix(
        float red, float green, float blue, float max, float min, float component) {
      this.red = red;
      this.green = green;
      this.blue = blue;
      this.max = max;
      this.min = min;
      this.component = component;
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

    /** The slopes of the curve at the control points. Must not be modified. */
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
}
