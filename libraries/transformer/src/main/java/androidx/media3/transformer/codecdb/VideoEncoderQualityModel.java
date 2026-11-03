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
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Math.max;
import static java.lang.Math.min;

import com.google.common.collect.ImmutableSortedMap;
import java.util.Map;

/**
 * A statistical model used to predict rate from distortion and distortion from rate for a given
 * video encoder configured with a specific set of settings.
 *
 * <p>The predictions in this class are made with the PCI algorithm.
 *
 * <p>Instances are immutable and can be used concurrently from multiple threads.
 */
/* package */ final class VideoEncoderQualityModel {

  /** Source rate-distortion models, keyed and ordered by pixels per second. */
  private final ImmutableSortedMap<Integer, PciRateDistortionModel> sourceModels;

  /**
   * Creates a {@link VideoEncoderQualityModel} from individual rate-distortion models.
   *
   * @param sourceModels The {@link PciRateDistortionModel} for each pixel rate (calculated as
   *     frames-per-second * pixels-per-frame) that has source data.
   * @throws IllegalArgumentException If fewer than two models are provided, or if any pixel rate is
   *     not strictly positive.
   */
  /* package */ VideoEncoderQualityModel(Map<Integer, PciRateDistortionModel> sourceModels) {
    this.sourceModels = ImmutableSortedMap.copyOf(sourceModels);
    checkArgument(
        this.sourceModels.size() >= 2,
        "At least two rate-distortion models are required: %s",
        this.sourceModels.keySet());
    checkArgument(
        this.sourceModels.firstKey() > 0,
        "Pixel rates must be strictly positive: %s",
        this.sourceModels.keySet());
  }

  /**
   * Returns a {@link PciRateDistortionModel} corresponding to the requested pixel rate, either
   * based on the direct data or linearly interpolated if it is not available.
   *
   * <p>Interpolation assumes the model parameters vary linearly with the pixel rate. This
   * estimation approach is generally accurate for quality/bitrate predictions.
   *
   * @param pixelsPerSecond The pixel rate (calculated as frames-per-second * pixels-per-frame).
   * @throws IllegalArgumentException If {@code pixelsPerSecond} is not strictly positive.
   */
  /* package */ PciRateDistortionModel getModel(int pixelsPerSecond) {
    checkArgument(
        pixelsPerSecond > 0, "pixelsPerSecond must be strictly positive: %s", pixelsPerSecond);

    // First, check for a matching model based on source data.
    PciRateDistortionModel exactModel = sourceModels.get(pixelsPerSecond);
    if (exactModel != null) {
      return exactModel;
    }

    // Grab a lower and upper model to use for interpolation. If the pixel rate is outside the
    // range of known models, use the two closest known models.

    // If this is null, then the desired pixel rate is less than the lowest one in sourceModels.
    Map.Entry<Integer, PciRateDistortionModel> lowerEntry =
        sourceModels.floorEntry(pixelsPerSecond);
    if (lowerEntry == null) {
      lowerEntry = checkNotNull(sourceModels.firstEntry());
    }

    // If this is null, then the desired pixel rate is greater than the highest one in
    // sourceModels.
    Map.Entry<Integer, PciRateDistortionModel> upperEntry =
        sourceModels.higherEntry(lowerEntry.getKey());
    if (upperEntry == null) {
      upperEntry = checkNotNull(sourceModels.lastEntry());
      lowerEntry = checkNotNull(sourceModels.lowerEntry(upperEntry.getKey()));
    }

    int lowerPixelsPerSecond = lowerEntry.getKey();
    int upperPixelsPerSecond = upperEntry.getKey();
    PciRateDistortionModel lowerRdModel = lowerEntry.getValue();
    PciRateDistortionModel upperRdModel = upperEntry.getValue();

    // Linearly interpolate each parameter of the model.
    double slopeA =
        (upperRdModel.a - lowerRdModel.a) / (upperPixelsPerSecond - lowerPixelsPerSecond);
    double interceptA = lowerRdModel.a - slopeA * lowerPixelsPerSecond;
    double interpA = pixelsPerSecond * slopeA + interceptA;

    double slopeB =
        (upperRdModel.b - lowerRdModel.b) / (upperPixelsPerSecond - lowerPixelsPerSecond);
    double interceptB = lowerRdModel.b - slopeB * lowerPixelsPerSecond;
    double interpB = pixelsPerSecond * slopeB + interceptB;

    double slopeMinRateKbps =
        (upperRdModel.minRateKbps - lowerRdModel.minRateKbps)
            / (upperPixelsPerSecond - lowerPixelsPerSecond);
    double interceptMinRateKbps =
        lowerRdModel.minRateKbps - slopeMinRateKbps * lowerPixelsPerSecond;
    double interpMinRateKbps = pixelsPerSecond * slopeMinRateKbps + interceptMinRateKbps;

    double slopeMinDistortion =
        (upperRdModel.minDistortion - lowerRdModel.minDistortion)
            / (upperPixelsPerSecond - lowerPixelsPerSecond);
    double interceptMinDistortion =
        lowerRdModel.minDistortion - slopeMinDistortion * lowerPixelsPerSecond;
    double interpMinDistortion = pixelsPerSecond * slopeMinDistortion + interceptMinDistortion;

    return new PciRateDistortionModel(
        /* a= */ interpA,
        /* b= */ interpB,
        /* minRateKbps= */ max(
            interpMinRateKbps, min(lowerRdModel.minRateKbps, upperRdModel.minRateKbps)),
        /* minDistortion= */ max(
            interpMinDistortion, min(lowerRdModel.minDistortion, upperRdModel.minDistortion)));
  }
}
