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

import static androidx.media3.transformer.codecdb.CodecDbUtil.calculatePixelsPerSecond;
import static com.google.common.base.Preconditions.checkArgument;

import androidx.media3.common.Format;
import androidx.media3.common.util.ExperimentalApi;
import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Ints;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import org.jspecify.annotations.Nullable;

/**
 * A loaded dataset of CodecDB data that can be queried for expected quality or optimal settings.
 *
 * <p>Obtain instances via {@link CodecDbDatasetLoader}.
 */
@ExperimentalApi // TODO(b/579130160): Promote to @UnstableApi or remove.
public final class CodecDbEncoderDataset {
  private final EncoderConfigModelRepository configModels;

  private final Function<VideoEncoderConstraints, @Nullable VideoEncoderConfiguration>
      configFallbackProvider;

  private final ToDoubleFunction<VideoEncoderConfiguration> qualityFallbackProvider;

  /**
   * Creates a new {@link CodecDbEncoderDataset} from a map of encoder configurations to quality
   * models and fallback providers.
   *
   * @param configModels The encoder configurations and their quality models.
   * @param configFallbackProvider A function that is queried with the requested constraints when no
   *     configuration in {@code configModels} satisfies them. It returns the configuration to use
   *     instead, or {@code null} if it has none, in which case the query returns {@code null}. Pass
   *     {@code constraints -> null} to disable the fallback.
   * @param qualityFallbackProvider A function that is queried with a configuration when no model in
   *     {@code configModels} matches it. It returns the predicted VMAF score, or {@link Double#NaN}
   *     if it cannot predict one. Pass {@code configuration -> Double.NaN} to disable the fallback.
   */
  /* package */ CodecDbEncoderDataset(
      Map<VideoEncoderConfiguration, VideoEncoderQualityModel> configModels,
      Function<VideoEncoderConstraints, @Nullable VideoEncoderConfiguration> configFallbackProvider,
      ToDoubleFunction<VideoEncoderConfiguration> qualityFallbackProvider) {
    this.configModels = new EncoderConfigModelRepository(ImmutableMap.copyOf(configModels));
    this.configFallbackProvider = configFallbackProvider;
    this.qualityFallbackProvider = qualityFallbackProvider;
  }

  /**
   * Returns the optimal {@link VideoEncoderConfiguration} that satisfies the provided {@link
   * VideoEncoderConstraints} based on the loaded CodecDB dataset (or its fallback provider), or
   * {@code null} if no suitable configuration is found.
   *
   * <p>Callers can obtain {@link VideoEncoderConstraints} for common encoding use cases from {@link
   * VideoEncoderConstraintsPresets} (such as {@link VideoEncoderConstraintsPresets#forEditing},
   * {@link VideoEncoderConstraintsPresets#forHqCameraRecording}, or {@link
   * VideoEncoderConstraintsPresets#forRealTimeCommunication}).
   *
   * @param constraints The encoding constraints to optimize around.
   */
  public @Nullable VideoEncoderConfiguration findOptimalConfiguration(
      VideoEncoderConstraints constraints) {
    VideoEncoderConfiguration configuration = configModels.findOptimalConfiguration(constraints);
    if (configuration != null) {
      return configuration;
    }

    return this.configFallbackProvider.apply(constraints);
  }

  /**
   * Attempts to predict the expected quality of the provided {@link VideoEncoderConfiguration}.
   *
   * <p>The predicted quality is on a [0, 100] scale, representing the Video Multi-method Assessment
   * Fusion (VMAF) score. If no configuration in this dataset matches, the quality fallback provider
   * is queried instead. Returns {@link Double#NaN} if neither can predict a quality.
   *
   * @param videoEncoderConfiguration The configuration to predict quality for.
   * @return The predicted VMAF quality score, or {@link Double#NaN} if unavailable.
   * @throws IllegalArgumentException If the resolution or bitrate of {@code
   *     videoEncoderConfiguration} is {@link Format#NO_VALUE}.
   */
  public double predictQualityFor(VideoEncoderConfiguration videoEncoderConfiguration) {
    Format format = videoEncoderConfiguration.format;
    checkArgument(
        format.width != Format.NO_VALUE && format.height != Format.NO_VALUE,
        "videoEncoderConfiguration must have a resolution set");
    checkArgument(
        format.averageBitrate != Format.NO_VALUE,
        "videoEncoderConfiguration must have a bitrate set");
    EncoderConfigModel matchedConfigModel =
        this.configModels.findClosestMatchingConfigModel(videoEncoderConfiguration);
    if (matchedConfigModel == null) {
      return this.qualityFallbackProvider.applyAsDouble(videoEncoderConfiguration);
    }

    long pixelsPerSecond = calculatePixelsPerSecond(format.width, format.height, format.frameRate);
    return matchedConfigModel
        .model
        .getModel(Ints.checkedCast(pixelsPerSecond))
        .predictQualityForBitrate((double) format.averageBitrate / 1_000);
  }
}
