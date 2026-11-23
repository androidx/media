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
import static com.google.common.math.DoubleMath.log2;

import androidx.media3.common.util.Util;
import androidx.media3.common.video.HagcMetadata.AlternateImage;
import androidx.media3.common.video.HagcMetadata.GainCurve;
import androidx.media3.common.video.HagcMetadata.HeadroomAdaptiveToneMap;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Evaluates SMPTE ST 2094-50 (HAGC) gain curves and headroom-adaptive tone maps (Clauses 6.2.5 and
 * 6.5.3).
 */
/* package */ final class HagcToneMapper {

  private static final HagcToneMapper IDENTITY =
      new HagcToneMapper(
          /* alternate0= */ null, /* weight0= */ 0.0, /* alternate1= */ null, /* weight1= */ 0.0);

  // Contributing alternate images and weights from Clause 6.2.5: 0 and 1 correspond to i and i + 1
  // in Formulas (3) and (4) (when only one alternate has non-zero gain, it is stored in 0 with
  // alternate1 == null; when gain is zero everywhere, both alternates are null).
  private final @Nullable AlternateImage alternate0;
  private final double weight0;
  private final @Nullable AlternateImage alternate1;
  private final double weight1;

  private HagcToneMapper(
      @Nullable AlternateImage alternate0,
      double weight0,
      @Nullable AlternateImage alternate1,
      double weight1) {
    this.alternate0 = alternate0;
    this.weight0 = weight0;
    this.alternate1 = alternate1;
    this.weight1 = weight1;
  }

  /**
   * Resolves the contributing alternate images and weights of {@code toneMap} at {@code
   * targetHdrHeadroom}, as specified in SMPTE ST 2094-50 Clause 6.2.5 (Formulas (2) and (3)).
   *
   * @param toneMap The headroom-adaptive tone map to resolve.
   * @param targetHdrHeadroom The targeted log2 HDR headroom.
   * @return The resolved tone mapper for {@code targetHdrHeadroom}.
   * @throws IllegalArgumentException If {@code targetHdrHeadroom} is {@link Float#NaN}.
   */
  /* package */ static HagcToneMapper create(
      HeadroomAdaptiveToneMap toneMap, float targetHdrHeadroom) {
    checkArgument(!Float.isNaN(targetHdrHeadroom));
    ImmutableList<AlternateImage> alternateImages = toneMap.alternateImages;
    float baselineHdrHeadroom = toneMap.baselineHdrHeadroom;
    if (alternateImages.isEmpty() || targetHdrHeadroom == baselineHdrHeadroom) {
      return IDENTITY;
    }

    // Clause 6.2.5: form the sorted list H_0, ..., H_{N-1} (N = N_alt + 1) by inserting
    // baselineHdrHeadroom into the sorted list of alternate headrooms (Clause 6.2.2) with the zero
    // color gain function (Clause 6.3.3, represented by null) at that same position.
    List<@Nullable AlternateImage> entries = new ArrayList<>(alternateImages.size() + 1);
    int baselineIndex = 0;
    while (baselineIndex < alternateImages.size()
        && alternateImages.get(baselineIndex).hdrHeadroom < baselineHdrHeadroom) {
      entries.add(alternateImages.get(baselineIndex));
      baselineIndex++;
    }
    entries.add(null);
    entries.addAll(alternateImages.subList(baselineIndex, alternateImages.size()));

    // Formula (2): if an entry H_i equals clamp(targetHdrHeadroom, H_0, H_{N-1}), return Gain_i.
    float clampedTarget =
        Util.constrainValue(
            targetHdrHeadroom,
            /* min= */ getHeadroom(entries.get(0), baselineHdrHeadroom),
            /* max= */ getHeadroom(entries.get(entries.size() - 1), baselineHdrHeadroom));
    int i = 0;
    while (i + 1 < entries.size()
        && getHeadroom(entries.get(i + 1), baselineHdrHeadroom) <= clampedTarget) {
      i++;
    }
    @Nullable AlternateImage alternateI = entries.get(i);
    float headroomI = getHeadroom(alternateI, baselineHdrHeadroom);
    if (clampedTarget == headroomI) {
      return singleAlternate(alternateI, /* weight= */ 1.0);
    }

    // Formulas (3) and (4): H_i < targetHdrHeadroom < H_{i+1}.
    @Nullable AlternateImage alternateNext = entries.get(i + 1);
    float headroomNext = getHeadroom(alternateNext, baselineHdrHeadroom);
    double weightI =
        ((double) targetHdrHeadroom - headroomNext) / ((double) headroomI - headroomNext);
    double weightNext =
        ((double) targetHdrHeadroom - headroomI) / ((double) headroomNext - headroomI);
    if (alternateI == null) {
      return singleAlternate(alternateNext, weightNext);
    }
    if (alternateNext == null) {
      return singleAlternate(alternateI, weightI);
    }
    return new HagcToneMapper(alternateI, weightI, alternateNext, weightNext);
  }

  /**
   * Returns the log2 gain of {@code gainCurve} at {@code x}, as specified in SMPTE ST 2094-50
   * Clause 6.5.3 (Formulas (11) and (12)).
   *
   * @param gainCurve The gain curve to evaluate.
   * @param x The component-mixed input value relative to {@link
   *     HagcMetadata#hdrReferenceWhiteNits}.
   * @return The log2 gain, or {@link Float#NEGATIVE_INFINITY} when the last control point's x
   *     coordinate is 0 and {@code x > 0f}.
   * @throws IllegalArgumentException If {@code x} is {@link Float#NaN}.
   */
  /* package */ static float evaluateGainCurve(GainCurve gainCurve, float x) {
    checkArgument(!Float.isNaN(x));
    if (x <= gainCurve.x[0]) {
      return gainCurve.y[0];
    }
    int lastIndex = gainCurve.x.length - 1;
    if (x == gainCurve.x[lastIndex]) {
      return gainCurve.y[lastIndex];
    }
    if (x > gainCurve.x[lastIndex]) {
      // Formula (12) of Clause 6.5.3.
      return (float) (gainCurve.y[lastIndex] + log2(gainCurve.x[lastIndex] / (double) x));
    }

    // Formula (12): if x equals a control point, return y_i (when adjacent control points share an
    // x coordinate, Clause 6.5.2 guarantees they also share y_i); otherwise insertionPoint =
    // -index - 1 is the first control point strictly above x, so x_i < x < x_{i+1} for i =
    // insertionPoint - 1.
    int index = Arrays.binarySearch(gainCurve.x, x);
    if (index >= 0) {
      return gainCurve.y[index];
    }
    int i = -index - 2;

    // Formula (11) of Clause 6.5.3.
    double width = (double) gainCurve.x[i + 1] - gainCurve.x[i];
    double mHatI = width * gainCurve.slopes[i];
    double mHatNext = width * gainCurve.slopes[i + 1];
    double yI = gainCurve.y[i];
    double yNext = gainCurve.y[i + 1];
    double c3 = 2 * yI + mHatI - 2 * yNext + mHatNext;
    double c2 = -3 * yI + 3 * yNext - 2 * mHatI - mHatNext;
    double c1 = mHatI;
    double c0 = yI;
    double t = ((double) x - gainCurve.x[i]) / width;
    return (float) (((c3 * t + c2) * t + c1) * t + c0);
  }

  /**
   * Returns the log2 gain for an achromatic {@code inputRelativeToWhite} at the targeted headroom,
   * as specified in SMPTE ST 2094-50 Clause 6.2.5 (Formulas (2) and (4)).
   *
   * <p>For an achromatic color {@code (v, v, v)}, every normalized {@link
   * HagcMetadata.ComponentMix} evaluates to {@code v} (Clause 6.4.1), so each contributing
   * alternate image's color gain function reduces to evaluating its {@link GainCurve} at {@code
   * inputRelativeToWhite}.
   *
   * @param inputRelativeToWhite The linear achromatic input component value relative to {@link
   *     HagcMetadata#hdrReferenceWhiteNits}.
   * @return The log2 gain, or {@link Float#NEGATIVE_INFINITY} when a gain curve with a non-zero
   *     weight has a last control point with x coordinate 0 and {@code inputRelativeToWhite > 0f}.
   * @throws IllegalArgumentException If {@code inputRelativeToWhite} is {@link Float#NaN}.
   */
  /* package */ float evaluateGreyLog2Gain(float inputRelativeToWhite) {
    checkArgument(!Float.isNaN(inputRelativeToWhite));
    if (alternate0 == null) {
      return 0f;
    }
    double gain0 = evaluateGainCurve(alternate0.gainCurve, /* x= */ inputRelativeToWhite);
    if (alternate1 == null) {
      return (float) (weight0 * gain0);
    }
    double gain1 = evaluateGainCurve(alternate1.gainCurve, /* x= */ inputRelativeToWhite);
    return (float) (weight0 * gain0 + weight1 * gain1);
  }

  private static float getHeadroom(@Nullable AlternateImage alternate, float baselineHdrHeadroom) {
    return alternate != null ? alternate.hdrHeadroom : baselineHdrHeadroom;
  }

  private static HagcToneMapper singleAlternate(@Nullable AlternateImage alternate, double weight) {
    return alternate == null
        ? IDENTITY
        : new HagcToneMapper(
            alternate, /* weight0= */ weight, /* alternate1= */ null, /* weight1= */ 0.0);
  }
}
