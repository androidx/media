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

import static com.google.common.truth.Truth.assertThat;
import static java.lang.Math.pow;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.video.HagcMetadata.AlternateImage;
import androidx.media3.common.video.HagcMetadata.ComponentMix;
import androidx.media3.common.video.HagcMetadata.GainCurve;
import androidx.media3.common.video.HagcMetadata.HeadroomAdaptiveToneMap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link HagcToneMapper}. */
@RunWith(AndroidJUnit4.class)
public final class HagcToneMapperTest {

  /** Tolerance for irrational {@code log2} and {@code pow} floating-point results. */
  private static final float TOLERANCE = 1e-6f;

  @Test
  public void evaluateGainCurve_belowFirstControlPoint_returnsFirstGain() {
    // Formula (12): for x <= x[0] = 1, GainCurve(x) clamps to y[0] = -0.5.
    GainCurve gainCurve =
        new GainCurve(
            /* x= */ new float[] {1f, 4f},
            /* y= */ new float[] {-0.5f, -2f},
            /* slopes= */ new float[] {0f, -0.5f});

    float gainBelowZero = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ -0.5f);
    float gainAtZero = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ 0f);
    float gainBelowFirst = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ 0.25f);

    assertThat(gainBelowZero).isEqualTo(-0.5f);
    assertThat(gainAtZero).isEqualTo(-0.5f);
    assertThat(gainBelowFirst).isEqualTo(-0.5f);
  }

  @Test
  public void evaluateGainCurve_atControlPoints_returnsControlPointGains() {
    // Formula (12): when x equals a control point x[i], GainCurve(x) returns y[i] directly.
    GainCurve gainCurve =
        new GainCurve(
            /* x= */ new float[] {0.5f, 2f, 8f},
            /* y= */ new float[] {0.25f, -0.5f, -1.75f},
            /* slopes= */ new float[] {-0.2f, -0.4f, -0.1f});

    float gain0 = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ 0.5f);
    float gain1 = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ 2f);
    float gain2 = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ 8f);

    assertThat(gain0).isEqualTo(0.25f);
    assertThat(gain1).isEqualTo(-0.5f);
    assertThat(gain2).isEqualTo(-1.75f);
  }

  @Test
  public void evaluateGainCurve_betweenControlPoints_interpolatesCubicHermiteSpline() {
    // Expected values computed from Formula (11) of Clause 6.5.3:
    // - On [0.5, 1] (width = 0.5, y_i = 1.25, y_{i+1} = 1, m_i = 0, m_{i+1} = 0.5):
    //   c3 = 0.75, c2 = -1, c1 = 0, c0 = 1.25; t = 0.5 -> 1.09375.
    // - On [1, 5] (width = 4, y_i = 1, y_{i+1} = -1, m_i = 0.5, m_{i+1} = -0.25):
    //   c3 = 5, c2 = -9, c1 = 2, c0 = 1; t = 0.25 -> 65/64 = 1.015625 and t = 0.5 -> 3/8 = 0.375.
    // - On [5, 9] (width = 4, y_i = -1, y_{i+1} = -2, m_i = -0.25, m_{i+1} = -0.125):
    //   c3 = 0.5, c2 = -0.5, c1 = -1, c0 = -1; t = 0.5 -> -1.5625.
    GainCurve gainCurve =
        new GainCurve(
            /* x= */ new float[] {0.5f, 1f, 5f, 9f},
            /* y= */ new float[] {1.25f, 1f, -1f, -2f},
            /* slopes= */ new float[] {0f, 0.5f, -0.25f, -0.125f});

    float gainInterval0 = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ 0.75f);
    float gainInterval1Quarter = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ 2f);
    float gainInterval1Mid = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ 3f);
    float gainInterval2Mid = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ 7f);

    assertThat(gainInterval0).isEqualTo(1.09375f);
    assertThat(gainInterval1Quarter).isEqualTo(1.015625f);
    assertThat(gainInterval1Mid).isEqualTo(0.375f);
    assertThat(gainInterval2Mid).isEqualTo(-1.5625f);
  }

  @Test
  public void evaluateGainCurve_pastLastControlPoint_keepsGainedValueConstant() {
    GainCurve gainCurve =
        new GainCurve(
            /* x= */ new float[] {1f, 4f},
            /* y= */ new float[] {0f, -0.5f},
            /* slopes= */ new float[] {0f, -0.25f});
    float expectedGainedValue = (float) (4.0 * pow(2.0, -0.5));

    float gainAtSix = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ 6f);
    float gainAtSixteen = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ 16f);

    // Formula (12): for x > 4, G(x) = -0.5 + log2(4 / x), keeping x * 2^G(x) = 4 * 2^-0.5:
    // - x = 6:  -0.5 + log2(4 / 6) ~= -1.0849625
    // - x = 16: -0.5 + log2(4 / 16) = -0.5 - 2 = -2.5
    assertThat(gainAtSix).isWithin(TOLERANCE).of(-1.0849625f);
    assertThat((float) (6.0 * pow(2.0, gainAtSix))).isWithin(TOLERANCE).of(expectedGainedValue);
    assertThat(gainAtSixteen).isEqualTo(-2.5f);
  }

  @Test
  public void evaluateGainCurve_singleControlPoint_clampsBelowAndExtrapolatesAbove() {
    // Clause 6.5.2 allows a 1-point curve (no cubic intervals): x <= 2 clamps to y[0] = 0.5, and
    // x > 2 extrapolates with Formula (12): 0.5 + log2(2 / 8) = 0.5 - 2 = -1.5.
    GainCurve gainCurve =
        new GainCurve(
            /* x= */ new float[] {2f}, /* y= */ new float[] {0.5f}, /* slopes= */ new float[] {0f});

    float gainBelow = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ 0.5f);
    float gainAt = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ 2f);
    float gainAbove = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ 8f);

    assertThat(gainBelow).isEqualTo(0.5f);
    assertThat(gainAt).isEqualTo(0.5f);
    assertThat(gainAbove).isEqualTo(-1.5f);
  }

  @Test
  public void
      evaluateGainCurve_duplicateControlPointXWithDistinctSlopes_evaluatesBothSidesAndCorner() {
    // Clause 6.5.2 allows x[1] == x[2] when y[1] == y[2], creating a C0 corner at (2, -1) with
    // left slope -1 on [0, 2] and right slope +1 on [2, 6]:
    // - x = 1 (t = 0.5 on [0, 2], c3 = 0, c2 = -1, c1 = 0, c0 = 0) -> -0.25
    // - x = 2 (exact corner hit) -> -1
    // - x = 4 (t = 0.5 on [2, 6], c3 = 4, c2 = -8, c1 = 4, c0 = -1) -> -0.5
    GainCurve gainCurve =
        new GainCurve(
            /* x= */ new float[] {0f, 2f, 2f, 6f},
            /* y= */ new float[] {0f, -1f, -1f, -1f},
            /* slopes= */ new float[] {0f, -1f, 1f, 0f});

    float gainLeft = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ 1f);
    float gainCorner = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ 2f);
    float gainRight = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ 4f);

    assertThat(gainLeft).isEqualTo(-0.25f);
    assertThat(gainCorner).isEqualTo(-1f);
    assertThat(gainRight).isEqualTo(-0.5f);
  }

  @Test
  public void
      evaluateGainCurve_zeroLastControlPointX_returnsFirstGainAtZeroAndNegativeInfinityAbove() {
    // At x = 0, x <= x[0] returns y[0] = -1.5; for x > 0, Formula (12) evaluates
    // -1.5 + log2(0 / x) = -Infinity.
    GainCurve gainCurve =
        new GainCurve(
            /* x= */ new float[] {0f, 0f},
            /* y= */ new float[] {-1.5f, -1.5f},
            /* slopes= */ new float[] {0f, 0f});

    float gainAtZero = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ 0f);
    float gainAboveZero = HagcToneMapper.evaluateGainCurve(gainCurve, /* x= */ 0.5f);

    assertThat(gainAtZero).isEqualTo(-1.5f);
    assertThat(gainAboveZero).isNegativeInfinity();
  }

  @Test
  public void evaluateGainCurve_nanInput_throwsIllegalArgumentException() {
    // Uses a 1-point curve where NaN comparisons would otherwise fall through past lastIndex == 0.
    GainCurve singlePointCurve =
        new GainCurve(
            /* x= */ new float[] {1f}, /* y= */ new float[] {0f}, /* slopes= */ new float[] {0f});

    assertThrows(
        IllegalArgumentException.class,
        () -> HagcToneMapper.evaluateGainCurve(singlePointCurve, /* x= */ Float.NaN));
  }

  @Test
  public void create_nanTargetHeadroom_throwsIllegalArgumentException() {
    // NaN targetHdrHeadroom is rejected up front rather than propagating into weight arithmetic.
    HeadroomAdaptiveToneMap toneMap =
        new HeadroomAdaptiveToneMap(
            /* baselineHdrHeadroom= */ 2f,
            C.COLOR_SPACE_BT2020,
            ImmutableList.of(
                createConstantGainAlternate(/* hdrHeadroom= */ 0f, /* log2Gain= */ -1f)));

    assertThrows(
        IllegalArgumentException.class,
        () -> HagcToneMapper.create(toneMap, /* targetHdrHeadroom= */ Float.NaN));
  }

  @Test
  public void evaluateGreyLog2Gain_emptyAlternateImages_returnsZero() {
    // Clause B.4: with N_alt = 0, entries = [baseline] so every target clamps to zero gain.
    HeadroomAdaptiveToneMap toneMap =
        new HeadroomAdaptiveToneMap(
            /* baselineHdrHeadroom= */ 2f,
            C.COLOR_SPACE_BT2020,
            /* alternateImages= */ ImmutableList.of());

    float gain =
        HagcToneMapper.create(toneMap, /* targetHdrHeadroom= */ 0f)
            .evaluateGreyLog2Gain(/* inputRelativeToWhite= */ 3f);

    assertThat(gain).isEqualTo(0f);
  }

  @Test
  public void evaluateGreyLog2Gain_targetEqualsBaselineHeadroom_returnsZero() {
    // Clause 6.2.5 / 6.3.3: at target == baselineHdrHeadroom (2), the zero gain function applies.
    HeadroomAdaptiveToneMap toneMap =
        new HeadroomAdaptiveToneMap(
            /* baselineHdrHeadroom= */ 2f,
            C.COLOR_SPACE_BT2020,
            ImmutableList.of(
                createConstantGainAlternate(/* hdrHeadroom= */ 0f, /* log2Gain= */ -1.5f),
                createConstantGainAlternate(/* hdrHeadroom= */ 4f, /* log2Gain= */ 1.25f)));

    float gain =
        HagcToneMapper.create(toneMap, /* targetHdrHeadroom= */ 2f)
            .evaluateGreyLog2Gain(/* inputRelativeToWhite= */ 3f);

    assertThat(gain).isEqualTo(0f);
  }

  @Test
  public void evaluateGreyLog2Gain_targetEqualsAlternateHeadroom_returnsAlternateCurveGain() {
    // By Clause 6.4.1, any normalized ComponentMix is the identity on grey (2.5). At x = 2.5
    // (t = 0.5 on [1, 4]) with zero slopes, Formula (11) evaluates to the midpoint (y[0] + y[1])/2:
    // (-0.5 + -2) / 2 = -1.25 at SDR (headroom 0) and (0 + 1.5) / 2 = 0.75 at HDR (headroom 4).
    GainCurve sdrCurve =
        new GainCurve(
            /* x= */ new float[] {1f, 4f},
            /* y= */ new float[] {-0.5f, -2f},
            /* slopes= */ new float[] {0f, 0f});
    GainCurve hdrCurve =
        new GainCurve(
            /* x= */ new float[] {1f, 4f},
            /* y= */ new float[] {0f, 1.5f},
            /* slopes= */ new float[] {0f, 0f});
    ComponentMix customMix =
        new ComponentMix(
            /* red= */ 0.2f,
            /* green= */ 0.5f,
            /* blue= */ 0.1f,
            /* max= */ 0.1f,
            /* min= */ 0.05f,
            /* component= */ 0.05f);
    HeadroomAdaptiveToneMap toneMap =
        new HeadroomAdaptiveToneMap(
            /* baselineHdrHeadroom= */ 2f,
            C.COLOR_SPACE_BT2020,
            ImmutableList.of(
                new AlternateImage(/* hdrHeadroom= */ 0f, ComponentMix.RGB_AND_MAX, sdrCurve),
                new AlternateImage(/* hdrHeadroom= */ 4f, customMix, hdrCurve)));

    float gainAtSdr =
        HagcToneMapper.create(toneMap, /* targetHdrHeadroom= */ 0f)
            .evaluateGreyLog2Gain(/* inputRelativeToWhite= */ 2.5f);
    float gainAtHdr =
        HagcToneMapper.create(toneMap, /* targetHdrHeadroom= */ 4f)
            .evaluateGreyLog2Gain(/* inputRelativeToWhite= */ 2.5f);

    assertThat(gainAtSdr).isEqualTo(-1.25f);
    assertThat(gainAtHdr).isEqualTo(0.75f);
  }

  @Test
  public void evaluateGreyLog2Gain_targetOutsideAlternateEndpoints_clampsToNearestEndpoint() {
    // Sorted headrooms: 1 (gain -0.75) < 2 (baseline, gain 0) < 4 (gain +1.25). Formula (2) clamps
    // target = 0 to H_0 = 1 (-0.75) and target = 5 to H_2 = 4 (+1.25).
    HeadroomAdaptiveToneMap toneMap =
        new HeadroomAdaptiveToneMap(
            /* baselineHdrHeadroom= */ 2f,
            C.COLOR_SPACE_BT2020,
            ImmutableList.of(
                createConstantGainAlternate(/* hdrHeadroom= */ 1f, /* log2Gain= */ -0.75f),
                createConstantGainAlternate(/* hdrHeadroom= */ 4f, /* log2Gain= */ 1.25f)));

    float gainBelowMin =
        HagcToneMapper.create(toneMap, /* targetHdrHeadroom= */ 0f)
            .evaluateGreyLog2Gain(/* inputRelativeToWhite= */ 2f);
    float gainAboveMax =
        HagcToneMapper.create(toneMap, /* targetHdrHeadroom= */ 5f)
            .evaluateGreyLog2Gain(/* inputRelativeToWhite= */ 2f);

    assertThat(gainBelowMin).isEqualTo(-0.75f);
    assertThat(gainAboveMax).isEqualTo(1.25f);
  }

  @Test
  public void evaluateGreyLog2Gain_targetBelowLowestBaselineHeadroom_clampsToZeroGain() {
    // Sorted headrooms: 1 (baseline, gain 0) < 3 (gain +1.5). Formula (2) clamps target = 0 to
    // H_0 = 1 (zero gain).
    HeadroomAdaptiveToneMap toneMap =
        new HeadroomAdaptiveToneMap(
            /* baselineHdrHeadroom= */ 1f,
            C.COLOR_SPACE_BT2020,
            ImmutableList.of(
                createConstantGainAlternate(/* hdrHeadroom= */ 3f, /* log2Gain= */ 1.5f)));

    float gain =
        HagcToneMapper.create(toneMap, /* targetHdrHeadroom= */ 0f)
            .evaluateGreyLog2Gain(/* inputRelativeToWhite= */ 2f);

    assertThat(gain).isEqualTo(0f);
  }

  @Test
  public void evaluateGreyLog2Gain_targetAboveHighestBaselineHeadroom_clampsToZeroGain() {
    // Sorted headrooms: 1 (gain -1.5) < 4 (baseline, gain 0). Formula (2) clamps target = 5 to
    // H_1 = 4 (zero gain).
    HeadroomAdaptiveToneMap toneMap =
        new HeadroomAdaptiveToneMap(
            /* baselineHdrHeadroom= */ 4f,
            C.COLOR_SPACE_BT2020,
            ImmutableList.of(
                createConstantGainAlternate(/* hdrHeadroom= */ 1f, /* log2Gain= */ -1.5f)));

    float gain =
        HagcToneMapper.create(toneMap, /* targetHdrHeadroom= */ 5f)
            .evaluateGreyLog2Gain(/* inputRelativeToWhite= */ 2f);

    assertThat(gain).isEqualTo(0f);
  }

  @Test
  public void
      evaluateGreyLog2Gain_targetBetweenBaselineAndAlternate_interpolatesWithZeroBaselineGain() {
    // Tone map entries: H_0 = 0 (alternate 0, gain -2), H_1 = 2 (baseline, gain 0),
    // H_2 = 4 (alternate 1, gain +3, inverse tone mapping per Clause B.3):
    // - At targetHdrHeadroom = 0.5 (between H_0 = 0 and H_1 = 2):
    //   w_0 = (0.5 - 2) / (0 - 2) = 0.75 -> 0.75 * (-2) = -1.5
    // - At targetHdrHeadroom = 3.0 (between H_1 = 2 and H_2 = 4):
    //   w_2 = (3 - 2) / (4 - 2) = 0.5 -> 0.5 * 3 = 1.5
    HeadroomAdaptiveToneMap toneMap =
        new HeadroomAdaptiveToneMap(
            /* baselineHdrHeadroom= */ 2f,
            C.COLOR_SPACE_BT2020,
            ImmutableList.of(
                createConstantGainAlternate(/* hdrHeadroom= */ 0f, /* log2Gain= */ -2f),
                createConstantGainAlternate(/* hdrHeadroom= */ 4f, /* log2Gain= */ 3f)));

    float gainBelowBaseline =
        HagcToneMapper.create(toneMap, /* targetHdrHeadroom= */ 0.5f)
            .evaluateGreyLog2Gain(/* inputRelativeToWhite= */ 4f);
    float gainAboveBaseline =
        HagcToneMapper.create(toneMap, /* targetHdrHeadroom= */ 3f)
            .evaluateGreyLog2Gain(/* inputRelativeToWhite= */ 4f);

    assertThat(gainBelowBaseline).isEqualTo(-1.5f);
    assertThat(gainAboveBaseline).isEqualTo(1.5f);
  }

  @Test
  public void evaluateGreyLog2Gain_targetBetweenTwoAlternates_interpolatesLinearlyByHeadroom() {
    // Baseline is 0, alternates are at 1 (gain 0.5) and 3 (gain 2.5). At target = 1.5, weight on
    // alternate 1 is (1.5 - 1) / (3 - 1) = 0.25, giving 0.75 * 0.5 + 0.25 * 2.5 = 1.
    HeadroomAdaptiveToneMap toneMap =
        new HeadroomAdaptiveToneMap(
            /* baselineHdrHeadroom= */ 0f,
            C.COLOR_SPACE_BT2020,
            ImmutableList.of(
                createConstantGainAlternate(/* hdrHeadroom= */ 1f, /* log2Gain= */ 0.5f),
                createConstantGainAlternate(/* hdrHeadroom= */ 3f, /* log2Gain= */ 2.5f)));

    float gain =
        HagcToneMapper.create(toneMap, /* targetHdrHeadroom= */ 1.5f)
            .evaluateGreyLog2Gain(/* inputRelativeToWhite= */ 2f);

    assertThat(gain).isEqualTo(1f);
  }

  @Test
  public void evaluateGreyLog2Gain_baselineBetweenFourAlternates_interpolatesAdjacentPair() {
    // Exercises MAX_NUM_ALTERNATE_IMAGES (4) with baselineHdrHeadroom in the middle:
    // sorted headrooms are 0 (gain -2), 1 (gain -1), 2 (baseline, gain 0), 4 (gain 1), 5 (gain 2).
    // - target = 0.5 (midpoint of [0, 1]) -> 0.5 * (-2) + 0.5 * (-1) = -1.5
    // - target = 4.0 (exact hit on H_3 = 4) -> 1
    // - target = 4.5 (midpoint of [4, 5]) -> 0.5 * 1 + 0.5 * 2 = 1.5
    HeadroomAdaptiveToneMap toneMap =
        new HeadroomAdaptiveToneMap(
            /* baselineHdrHeadroom= */ 2f,
            C.COLOR_SPACE_BT2020,
            ImmutableList.of(
                createConstantGainAlternate(/* hdrHeadroom= */ 0f, /* log2Gain= */ -2f),
                createConstantGainAlternate(/* hdrHeadroom= */ 1f, /* log2Gain= */ -1f),
                createConstantGainAlternate(/* hdrHeadroom= */ 4f, /* log2Gain= */ 1f),
                createConstantGainAlternate(/* hdrHeadroom= */ 5f, /* log2Gain= */ 2f)));

    float gainBetweenLowerAlternates =
        HagcToneMapper.create(toneMap, /* targetHdrHeadroom= */ 0.5f)
            .evaluateGreyLog2Gain(/* inputRelativeToWhite= */ 2f);
    float gainAtUpperAlternate =
        HagcToneMapper.create(toneMap, /* targetHdrHeadroom= */ 4f)
            .evaluateGreyLog2Gain(/* inputRelativeToWhite= */ 2f);
    float gainBetweenUpperAlternates =
        HagcToneMapper.create(toneMap, /* targetHdrHeadroom= */ 4.5f)
            .evaluateGreyLog2Gain(/* inputRelativeToWhite= */ 2f);

    assertThat(gainBetweenLowerAlternates).isEqualTo(-1.5f);
    assertThat(gainAtUpperAlternate).isEqualTo(1f);
    assertThat(gainBetweenUpperAlternates).isEqualTo(1.5f);
  }

  @Test
  public void
      evaluateGreyLog2Gain_zeroLastControlPointXCurve_avoidsNaNAndPropagatesNegativeInfinity() {
    // Alternate 0 has x = {0}, which evaluates to -Infinity for inputRelativeToWhite > 0. At
    // target = 1 (exact hit on Alternate 1), Alternate 0 must not be multiplied by 0 to produce
    // NaN; at target = 0.5, blending 0.5 * (-Infinity) + 0.5 * (-1) propagates -Infinity.
    AlternateImage zeroMaxXAlternate =
        new AlternateImage(
            /* hdrHeadroom= */ 0f,
            ComponentMix.MAX,
            new GainCurve(
                /* x= */ new float[] {0f},
                /* y= */ new float[] {0f},
                /* slopes= */ new float[] {0f}));
    HeadroomAdaptiveToneMap toneMap =
        new HeadroomAdaptiveToneMap(
            /* baselineHdrHeadroom= */ 2f,
            C.COLOR_SPACE_BT2020,
            ImmutableList.of(
                zeroMaxXAlternate,
                createConstantGainAlternate(/* hdrHeadroom= */ 1f, /* log2Gain= */ -1f)));

    float gainAtAlternate1 =
        HagcToneMapper.create(toneMap, /* targetHdrHeadroom= */ 1f)
            .evaluateGreyLog2Gain(/* inputRelativeToWhite= */ 1f);
    float gainBlendedWithZeroMaxX =
        HagcToneMapper.create(toneMap, /* targetHdrHeadroom= */ 0.5f)
            .evaluateGreyLog2Gain(/* inputRelativeToWhite= */ 1f);

    assertThat(gainAtAlternate1).isEqualTo(-1f);
    assertThat(gainBlendedWithZeroMaxX).isNegativeInfinity();
  }

  @Test
  public void evaluateGreyLog2Gain_targetBetweenNonConstantAlternates_blendsEvaluatedCurveGains() {
    // Alternate 0 (SDR, headroom 0) clips above 1x white; Alternate 1 (headroom 1) clips above 2x
    // white. At baselinePeakNits = 4x white (baselineHdrHeadroom = 2), Formula (12) gives
    // log2(1 / 4) = -2 and log2(2 / 4) = -1, which blend with weights 0.75 and 0.25 at target 0.25
    // to -1.75.
    GainCurve sdrClipCurve =
        new GainCurve(
            /* x= */ new float[] {1f}, /* y= */ new float[] {0f}, /* slopes= */ new float[] {0f});
    GainCurve oneStopHdrClipCurve =
        new GainCurve(
            /* x= */ new float[] {2f}, /* y= */ new float[] {0f}, /* slopes= */ new float[] {0f});
    HeadroomAdaptiveToneMap toneMap =
        new HeadroomAdaptiveToneMap(
            /* baselineHdrHeadroom= */ 2f,
            C.COLOR_SPACE_BT2020,
            ImmutableList.of(
                new AlternateImage(/* hdrHeadroom= */ 0f, ComponentMix.MAX, sdrClipCurve),
                new AlternateImage(/* hdrHeadroom= */ 1f, ComponentMix.MAX, oneStopHdrClipCurve)));
    float whiteNits = HagcMetadata.DEFAULT_HDR_REFERENCE_WHITE_NITS;
    float baselinePeakNits = 4f * whiteNits;

    float gain =
        HagcToneMapper.create(toneMap, /* targetHdrHeadroom= */ 0.25f)
            .evaluateGreyLog2Gain(/* inputRelativeToWhite= */ baselinePeakNits / whiteNits);

    assertThat(gain).isEqualTo(-1.75f);
  }

  @Test
  public void evaluateGreyLog2Gain_nanInput_throwsIllegalArgumentException() {
    // NaN inputRelativeToWhite is rejected even when a valid alternate curve is active.
    HeadroomAdaptiveToneMap toneMap =
        new HeadroomAdaptiveToneMap(
            /* baselineHdrHeadroom= */ 2f,
            C.COLOR_SPACE_BT2020,
            ImmutableList.of(
                createConstantGainAlternate(/* hdrHeadroom= */ 0f, /* log2Gain= */ -1f)));
    HagcToneMapper toneMapper = HagcToneMapper.create(toneMap, /* targetHdrHeadroom= */ 1f);

    assertThrows(
        IllegalArgumentException.class,
        () -> toneMapper.evaluateGreyLog2Gain(/* inputRelativeToWhite= */ Float.NaN));
  }

  /**
   * Creates an alternate image with constant {@code log2Gain} across the Clause 6.5.2 input range
   * {@code [0, 64]}: setting {@code x[0] = 64} keeps every input {@code <= 64} in the {@code x <=
   * x[0]} constant-gain region of Formula (12) (whereas {@code x[0] = 0} would extrapolate to
   * {@code -Infinity} for {@code x > 0}).
   */
  private static AlternateImage createConstantGainAlternate(float hdrHeadroom, float log2Gain) {
    return new AlternateImage(
        hdrHeadroom,
        ComponentMix.MAX,
        new GainCurve(
            /* x= */ new float[] {64f},
            /* y= */ new float[] {log2Gain},
            /* slopes= */ new float[] {0f}));
  }
}
