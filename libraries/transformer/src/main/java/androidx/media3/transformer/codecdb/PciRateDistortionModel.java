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

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Math.max;

import androidx.annotation.CheckResult;
import com.google.common.base.MoreObjects;
import com.google.errorprone.annotations.Immutable;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Bi-directional inverse rate-distortion model. */
@Immutable
/* package */ final class PciRateDistortionModel {

  /** The linear component for the PCI algorithm. */
  public final double a;

  /** The constant component for the PCI algorithm. */
  public final double b;

  /** The minimum bitrate (in kbps) achieved by the codec configuration producing this model. */
  public final double minRateKbps;

  /** The minimum distortion achieved by the codec configuration producing this model. */
  public final double minDistortion;

  /**
   * Creates a new {@link PciRateDistortionModel}.
   *
   * @param a the linear component for the PCI algorithm.
   * @param b the constant component for the PCI algorithm.
   * @param minRateKbps the minimum bitrate (in kbps) achieved by the codec configuration producing
   *     this model.
   * @param minDistortion the minimum distortion achieved by the codec configuration producing this
   *     model.
   */
  public PciRateDistortionModel(double a, double b, double minRateKbps, double minDistortion) {
    checkArgument(a < 0.0 && Double.isFinite(a), "a must be strictly negative and finite: %s", a);
    checkArgument(Double.isFinite(b), "b must be finite: %s", b);
    checkArgument(
        minRateKbps >= 0.0 && Double.isFinite(minRateKbps),
        "minRateKbps must be non-negative and finite: %s",
        minRateKbps);
    checkArgument(
        minDistortion >= 0.0 && Double.isFinite(minDistortion),
        "minDistortion must be non-negative and finite: %s",
        minDistortion);
    this.a = a;
    this.b = b;
    this.minRateKbps = minRateKbps;
    this.minDistortion = minDistortion;
  }

  /**
   * Predicts the required bitrate to hit a given quality level, returning the bitrate in
   * kilobits-per-second (kbps).
   *
   * @param quality A [0,100] scale value representing overall quality with the 100 value
   *     representing perfect quality. Roughly equivalent to mean-opinion score where the following
   *     mapping occurs:
   *     <ul>
   *       <li>[0,20) -> 1
   *       <li>[20,40) -> 2
   *       <li>[40,60) -> 3
   *       <li>[60,80) -> 4
   *       <li>[80,100) -> 5
   *     </ul>
   */
  @CheckResult
  public double predictBitrateForQuality(double quality) {
    checkArgument(
        quality >= 0.0 && quality <= 100.0 && Double.isFinite(quality),
        "quality must be in range [0, 100]: %s",
        quality);
    return max(this.a / (quality - this.b), this.minRateKbps);
  }

  /**
   * Predicts the expected quality for a given bitrate on this model, returning a value in the
   * [0,100) range representing overall quality, which is defined in the documentation for {@link
   * #predictBitrateForQuality(double)}.
   */
  @CheckResult
  public double predictQualityForBitrate(double bitrateKbps) {
    checkArgument(
        bitrateKbps > 0.0 && Double.isFinite(bitrateKbps),
        "bitrateKbps must be strictly positive: %s",
        bitrateKbps);
    return max((this.a / bitrateKbps) + this.b, this.minDistortion);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof PciRateDistortionModel)) {
      return false;
    }
    PciRateDistortionModel that = (PciRateDistortionModel) o;
    return Double.compare(that.a, a) == 0
        && Double.compare(that.b, b) == 0
        && Double.compare(that.minRateKbps, minRateKbps) == 0
        && Double.compare(that.minDistortion, minDistortion) == 0;
  }

  @Override
  public int hashCode() {
    return Objects.hash(a, b, minRateKbps, minDistortion);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("a", a)
        .add("b", b)
        .add("minRateKbps", minRateKbps)
        .add("minDistortion", minDistortion)
        .toString();
  }
}
