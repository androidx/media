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
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.primitives.Ints.max;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.transformer.VideoEncoderSettings;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.math.DoubleMath;
import com.google.common.primitives.Ints;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/* package */ class EncoderConfigModelRepository {

  /**
   * Require a bitrate change of at least 10kbps in order to consider a new model as more "optimal".
   */
  private static final int BITRATE_DELTA_MINIMUM = 10;

  /** Require a quality change of at least 0.1 to consider a new model as more "optimal". */
  private static final float QUALITY_DELTA_MINIMUM = 0.1f;

  private static final int NO_MATCH = Integer.MIN_VALUE;

  private final ImmutableSet<EncoderConfigModel> configModels;

  /* package */ EncoderConfigModelRepository(
      ImmutableMap<VideoEncoderConfiguration, VideoEncoderQualityModel> configurationsToModels) {
    ImmutableSet.Builder<EncoderConfigModel> configModelsBuilder = ImmutableSet.builder();

    for (Map.Entry<VideoEncoderConfiguration, VideoEncoderQualityModel> entry :
        configurationsToModels.entrySet()) {
      configModelsBuilder.add(new EncoderConfigModel(entry.getKey(), entry.getValue()));
    }
    this.configModels = configModelsBuilder.build();
  }

  /**
   * Searches the configurations known by this dataset for the optimal one for the provided query,
   * returning null if none exists, or the optimal configuration.
   */
  public @Nullable VideoEncoderConfiguration findOptimalConfiguration(
      VideoEncoderConstraints constraints) {
    int[] matchedConfigIndices =
        getMatchedConfigIndicesForMatchScores(calculateMatchScoresForConstraints(constraints));

    if (matchedConfigIndices.length == 0) {
      return null;
    }

    sortMatchedConfigIndices(matchedConfigIndices);

    PciRateDistortionModel[] models =
        getModelsForPixelRate(
            matchedConfigIndices,
            Ints.checkedCast(
                calculatePixelsPerSecond(
                    constraints.width, constraints.height, constraints.frameRate)));

    int optimalCandidateIndex;
    int averageBitrate;
    if (constraints.maxBitrate != Format.NO_VALUE) {
      // Bitrate is reported in bps, but CodecDB models operate on kbps.
      optimalCandidateIndex =
          identifyOptimalModelForBitrateTarget(models, constraints.maxBitrate / 1_000.0);
      averageBitrate = constraints.maxBitrate;
    } else {
      optimalCandidateIndex =
          identifyOptimalModelForQualityTarget(models, constraints.targetQuality);
      double optimalBitrateKbps =
          models[optimalCandidateIndex].predictBitrateForQuality(constraints.targetQuality);
      // Convert the bitrate produced by the CodecDB library into a bits-per-second value as
      // required by the Android Media Framework.
      averageBitrate =
          Ints.checkedCast(
              DoubleMath.roundToLong(optimalBitrateKbps, RoundingMode.CEILING) * 1_000);
    }

    VideoEncoderConfiguration matchedConfiguration =
        cfgModel(matchedConfigIndices[optimalCandidateIndex]).config;
    Format format =
        new Format.Builder()
            .setSampleMimeType(checkNotNull(matchedConfiguration.format.sampleMimeType))
            .setWidth(constraints.width)
            .setHeight(constraints.height)
            .setFrameRate((float) constraints.frameRate)
            .setAverageBitrate(averageBitrate)
            .build();
    // Carry over every setting of the matched configuration, overriding only those that the
    // caller's constraints dictate.
    VideoEncoderSettings settings =
        matchedConfiguration
            .videoEncoderSettings
            .buildUpon()
            .setiFrameIntervalSeconds(constraints.keyFrameIntervalSeconds)
            .setBitrateMode(constraints.bitrateMode)
            .build();
    return new VideoEncoderConfiguration(settings, format, /* isFallback= */ false);
  }

  /**
   * Returns the {@link EncoderConfigModel} that is the "closest" match for the provided
   * configuration.
   */
  public @Nullable EncoderConfigModel findClosestMatchingConfigModel(
      VideoEncoderConfiguration config) {
    int[] matchScores = calculateMatchScoresForConfig(config);
    int[] matchedConfigIndices = getMatchedConfigIndicesForMatchScores(matchScores);

    if (matchedConfigIndices.length == 0) {
      return null;
    }

    // In the event of a tie, return the first matching ConfigModel since it represents the
    // "closest" match.
    return cfgModel(matchedConfigIndices[0]);
  }

  /**
   * Calculates "match" scores for each of the dataset's known configurations from the provided
   * configuration. Scores start at 0 and are decremented for every field that does not match.
   *
   * <p>Thus, a score of 0 indicates a perfect match, -1 an off-by-one match, etc.
   *
   * <p>Of note, a codec mismatch is heavily penalized more than a simple field mismatch.
   */
  private int[] calculateMatchScoresForConfig(VideoEncoderConfiguration searchConfig) {
    int[] matchScores = new int[configModels.size()];
    for (int i = 0; i < configModels.size(); i++) {
      EncoderConfigModel currentConfig = cfgModel(i);

      // Match codec based on MIME
      if (!Objects.equals(
          currentConfig.config.format.sampleMimeType, searchConfig.format.sampleMimeType)) {
        // Heavily penalize codecs that don't match.
        matchScores[i] -= 1 << 4;
      }

      VideoEncoderSettings searchSettings = searchConfig.videoEncoderSettings;
      VideoEncoderSettings currentSettings = currentConfig.config.videoEncoderSettings;

      if (searchSettings.bitrateMode != currentSettings.bitrateMode) {
        matchScores[i] -= 1 << 2;
      }

      if (searchSettings.profile != currentSettings.profile) {
        matchScores[i] -= 1 << 1;
      }

      if (searchSettings.level != currentSettings.level) {
        matchScores[i] -= 1;
      }

      if (searchSettings.iFrameIntervalSeconds != currentSettings.iFrameIntervalSeconds) {
        matchScores[i] -= 1;
      }

      if (searchSettings.maxBFrames != currentSettings.maxBFrames) {
        matchScores[i] -= 1 << 2;
      }
    }
    return matchScores;
  }

  /** Returns the index of the most-optimal RD model at a target bitrate (kbps). */
  private int identifyOptimalModelForBitrateTarget(
      PciRateDistortionModel[] models, double bitrateTargetKbps) {
    int optimalModel = -1;
    double optimalQuality = -Double.MAX_VALUE;
    for (int i = 0; i < models.length; i++) {
      double expectedQuality = models[i].predictQualityForBitrate(bitrateTargetKbps);

      // Find the maximum quality, only accepting a new one if it meets the QUALITY_DELTA_MINIMUM.
      //
      // e.g. if optimalQuality=75 and expectedQuality = 76, then
      // expectedQuality - optimalQuality = 1 >= QUALITY_DELTA_MINIMUM.
      if (expectedQuality - optimalQuality >= QUALITY_DELTA_MINIMUM) {
        optimalModel = i;
        optimalQuality = expectedQuality;
      }
    }
    return optimalModel;
  }

  /** Returns the index of the most-optimal RD model at a target quality point [0,100]. */
  private int identifyOptimalModelForQualityTarget(
      PciRateDistortionModel[] models, double qualityTarget) {
    int optimalModel = -1;
    double optimalBitrate = Double.MAX_VALUE;
    for (int i = 0; i < models.length; i++) {
      double expectedBitrateKbps = models[i].predictBitrateForQuality(qualityTarget);

      // Find the minimum bitrate, only accepting a new one if it meets the BITRATE_DELTA_MINIMUM.
      //
      // e.g. if optimalBitrate = 5000kbps and expected bitrate = 4900kbps, then
      // optimalBitrate - expectedBitrate = 100kbps >= BITRATE_DELTA_MINIMUM.
      if (optimalBitrate - expectedBitrateKbps >= BITRATE_DELTA_MINIMUM) {
        optimalModel = i;
        optimalBitrate = expectedBitrateKbps;
      }
    }
    return optimalModel;
  }

  /**
   * Loads the individual {@link PciRateDistortionModel} instances from the specified {@link
   * EncoderConfigModel} instances at the specified pixel rate (frames-per-second *
   * pixels-per-frame).
   */
  private PciRateDistortionModel[] getModelsForPixelRate(
      int[] configModelIndices, int pixelsPerSecond) {
    PciRateDistortionModel[] models = new PciRateDistortionModel[configModelIndices.length];
    for (int i = 0; i < configModelIndices.length; i++) {
      models[i] = cfgModel(configModelIndices[i]).model.getModel(pixelsPerSecond);
    }
    return models;
  }

  /**
   * Returns an array of indices corresponding to the {@link EncoderConfigModel} instances that are
   * a match for the provided query.
   */
  private int[] getMatchedConfigIndicesForMatchScores(int[] matchScores) {
    if (matchScores.length == 0) {
      return matchScores;
    }

    // If the max score is NO_MATCH, then we have no valid matches.
    int maxScore = max(matchScores);
    if (maxScore == NO_MATCH) {
      return new int[0];
    }

    int numMatches = 0;
    for (int i = 0; i < matchScores.length; i++) {
      if (matchScores[i] == maxScore) {
        numMatches++;
      }
    }

    int[] matchedConfigIndices = new int[numMatches];
    int matchedConfigIdx = 0;
    for (int i = 0; i < matchScores.length; i++) {
      if (matchScores[i] == maxScore) {
        matchedConfigIndices[matchedConfigIdx++] = i;
      }
    }

    return matchedConfigIndices;
  }

  /**
   * Sorts the found config indices into their natural ordering <b>in place.</b>
   *
   * <p>Functionally, this means that the library prefers older codec standards to newer ones and no
   * b-frames to b-frames when they are equivalent quality.
   */
  @CanIgnoreReturnValue
  private int[] sortMatchedConfigIndices(int[] matchedConfigIndices) {
    for (int i = 0; i < matchedConfigIndices.length; i++) {
      int minPrecedence = cfgModelPrecedence(matchedConfigIndices[i]);
      int minIndex = i;
      for (int j = i + 1; j < matchedConfigIndices.length; j++) {
        int currPrecedence = cfgModelPrecedence(matchedConfigIndices[j]);
        if (currPrecedence < minPrecedence) {
          minPrecedence = currPrecedence;
          minIndex = j;
        }
      }

      // Swap
      int tmp = matchedConfigIndices[i];
      matchedConfigIndices[i] = matchedConfigIndices[minIndex];
      matchedConfigIndices[minIndex] = tmp;
    }

    return matchedConfigIndices;
  }

  /**
   * Calculates a "match" score for each of the dataset's known configurations from the provided
   * query. Scores start at 0 and are decremented for every field that does not match.
   *
   * <p>Thus, a score of 0 indicates a perfect match, -1 an off-by-one match, etc.
   */
  private int[] calculateMatchScoresForConstraints(VideoEncoderConstraints constraints) {
    int[] matchScores = new int[this.configModels.size()];

    // Filter codecs first -- looking by MIME type.
    for (int i = 0; i < this.configModels.size(); i++) {
      @C.VideoCodecFlags int identifiedCodec = cfgModel(i).videoCodec();
      if ((constraints.allowedCodecs & identifiedCodec) == 0) {
        matchScores[i] = NO_MATCH;
      }
    }

    // Filter based on b-frame options.
    for (int i = 0; i < this.configModels.size(); i++) {
      if (matchScores[i] == NO_MATCH) {
        continue;
      }

      int currNumBFrames = cfgModel(i).maxBFrames();
      if (currNumBFrames > 0 && !constraints.bFramesAllowed) {
        matchScores[i] = NO_MATCH;
      }
    }

    return matchScores;
  }

  /** Returns the {@link EncoderConfigModel} at the specified index. */
  private EncoderConfigModel cfgModel(int index) {
    return this.configModels.asList().get(index);
  }

  /**
   * Returns an integer value that can be used to sort config models by order of precedence.
   *
   * <p>A higher value means a more "complex" configuration where as a lower one is more compatible.
   * Functionally, this means that legacy codecs (e.g. H264) get precedence first over newer
   * standards and non-b-frame configurations get precedence over b-frame configurations if there is
   * ever a tie in bitrate/quality.
   *
   * <p>This logic is based on the fact that CodecDB only considers max-b-frame values [0,3], which
   * enables this to produce "unique" values for each config.
   */
  private int cfgModelPrecedence(int index) {
    return 4 * this.configModels.asList().get(index).videoCodec()
        + this.configModels.asList().get(index).maxBFrames();
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof EncoderConfigModelRepository)) {
      return false;
    }
    EncoderConfigModelRepository that = (EncoderConfigModelRepository) o;
    return this.configModels.containsAll(that.configModels)
        && that.configModels.containsAll(this.configModels);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(configModels);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("configModels", configModels).toString();
  }
}
