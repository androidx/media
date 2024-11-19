/*
 * Copyright 2022 The Android Open Source Project
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
package androidx.media3.common.audio;

import static androidx.media3.common.util.Assertions.checkArgument;

import androidx.media3.common.util.UnstableApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/**
 * An immutable matrix that describes the mapping of input channels to output channels.
 *
 * <p>The matrix coefficients define the scaling factor to use when mixing samples from the input
 * channel (row) to the output channel (column).
 *
 * <p>Examples:
 *
 * <ul>
 *   <li>Stereo to mono with each channel at half volume:
 *       <pre>
 *         [0.5 0.5]</pre>
 *   <li>Stereo to stereo with no mixing or scaling:
 *       <pre>
 *         [1 0
 *          0 1]</pre>
 *   <li>Stereo to stereo with 0.7 volume:
 *       <pre>
 *         [0.7 0
 *          0 0.7]</pre>
 * </ul>
 */
@UnstableApi
public final class ChannelMixingMatrix {
  private final int inputChannelCount;
  private final int outputChannelCount;
  private final float[] coefficients;
  private final boolean isZero;
  private final boolean isDiagonal;
  private final boolean isIdentity;

  /**
   * Creates a basic channel mixing matrix that converts from {@code inputChannelCount} channels to
   * {@code outputChannelCount} channels.
   *
   * <p>If the input and output channel counts match then a simple identity matrix will be returned.
   * Otherwise, default matrix coefficients will be used to best match channel locations and overall
   * power level.
   *
   * @param inputChannelCount Number of input channels.
   * @param outputChannelCount Number of output channels.
   * @return New channel mixing matrix.
   * @throws UnsupportedOperationException If no default matrix coefficients are implemented for the
   *     given input and output channel counts.
   */
  // TODO(b/300467493): Modify create() to use constant power defaults and migrate all users.
  public static ChannelMixingMatrix create(int inputChannelCount, int outputChannelCount) {
    return new ChannelMixingMatrix(
        inputChannelCount,
        outputChannelCount,
        createConstantGainMixingCoefficients(inputChannelCount, outputChannelCount));
  }

  /**
   * Returns default constant power matrix for mixing {@code inputChannelCount} channels into {@code
   * outputChannelCount} channels.
   *
   * <p>If the input and output channel counts match then a simple identity matrix will be returned.
   *
   * @param inputChannelCount Number of input channels.
   * @param outputChannelCount Number of output channels.
   * @return New channel mixing matrix.
   * @throws UnsupportedOperationException If no default coefficients are available for the given
   *     input and output channel counts.
   */
  public static ChannelMixingMatrix createForConstantPower(
      int inputChannelCount, int outputChannelCount) {
    return new ChannelMixingMatrix(
        inputChannelCount,
        outputChannelCount,
        createConstantPowerMixingCoefficients(inputChannelCount, outputChannelCount));
  }

  /**
   * Creates a matrix with the given coefficients in row-major order.
   *
   * @param inputChannelCount Number of input channels (rows in the matrix).
   * @param outputChannelCount Number of output channels (columns in the matrix).
   * @param coefficients Non-negative matrix coefficients in row-major order.
   */
  public ChannelMixingMatrix(int inputChannelCount, int outputChannelCount, float[] coefficients) {
    checkArgument(inputChannelCount > 0, "Input channel count must be positive.");
    checkArgument(outputChannelCount > 0, "Output channel count must be positive.");
    checkArgument(
        coefficients.length == inputChannelCount * outputChannelCount,
        "Coefficient array length is invalid.");
    this.inputChannelCount = inputChannelCount;
    this.outputChannelCount = outputChannelCount;
    this.coefficients = checkCoefficientsValid(coefficients);

    // Calculate matrix properties.
    boolean allDiagonalCoefficientsAreOne = true;
    boolean allCoefficientsAreZero = true;
    boolean allNonDiagonalCoefficientsAreZero = true;
    for (int row = 0; row < inputChannelCount; row++) {
      for (int col = 0; col < outputChannelCount; col++) {
        float coefficient = getMixingCoefficient(row, col);
        boolean onDiagonal = row == col;

        if (coefficient != 1f && onDiagonal) {
          allDiagonalCoefficientsAreOne = false;
        }
        if (coefficient != 0f) {
          allCoefficientsAreZero = false;
          if (!onDiagonal) {
            allNonDiagonalCoefficientsAreZero = false;
          }
        }
      }
    }
    isZero = allCoefficientsAreZero;
    isDiagonal = isSquare() && allNonDiagonalCoefficientsAreZero;
    isIdentity = isDiagonal && allDiagonalCoefficientsAreOne;
  }

  public int getInputChannelCount() {
    return inputChannelCount;
  }

  public int getOutputChannelCount() {
    return outputChannelCount;
  }

  /** Gets the scaling factor for the given input and output channel. */
  public float getMixingCoefficient(int inputChannel, int outputChannel) {
    return coefficients[inputChannel * outputChannelCount + outputChannel];
  }

  /** Returns whether all mixing coefficients are zero. */
  public boolean isZero() {
    return isZero;
  }

  /** Returns whether the input and output channel count is the same. */
  public boolean isSquare() {
    return inputChannelCount == outputChannelCount;
  }

  /** Returns whether the matrix is square and all non-diagonal coefficients are zero. */
  public boolean isDiagonal() {
    return isDiagonal;
  }

  /** Returns whether this is an identity matrix. */
  public boolean isIdentity() {
    return isIdentity;
  }

  /** Returns a new matrix with the given scaling factor applied to all coefficients. */
  public ChannelMixingMatrix scaleBy(float scale) {
    float[] scaledCoefficients = new float[coefficients.length];
    for (int i = 0; i < coefficients.length; i++) {
      scaledCoefficients[i] = scale * coefficients[i];
    }
    return new ChannelMixingMatrix(inputChannelCount, outputChannelCount, scaledCoefficients);
  }

  private static float[] initializeIdentityMatrix(int channelCount) {
    float[] coefficients = new float[channelCount * channelCount];
    for (int c = 0; c < channelCount; c++) {
      coefficients[channelCount * c + c] = 1f;
    }
    return coefficients;
  }

  @CanIgnoreReturnValue
  private static float[] checkCoefficientsValid(float[] coefficients) {
    for (int i = 0; i < coefficients.length; i++) {
      if (coefficients[i] < 0f) {
        throw new IllegalArgumentException("Coefficient at index " + i + " is negative.");
      }
    }
    return coefficients;
  }

  private static float[] createConstantGainMixingCoefficients(
      int inputChannelCount, int outputChannelCount) {
    if (inputChannelCount == outputChannelCount) {
      return initializeIdentityMatrix(outputChannelCount);
    }
    if (inputChannelCount == 1 && outputChannelCount == 2) {
      // Mono -> stereo.
      return new float[] {
        1f, // left
        1f // right
      };
    }
    if (inputChannelCount == 2 && outputChannelCount == 1) {
      // Stereo -> mono.
      // 2 channels: [FRONT_LEFT, FRONT_RIGHT]
      return new float[] {0.5f, 0.5f};
    }

    throw new UnsupportedOperationException(
        "Default channel mixing coefficients for "
            + inputChannelCount
            + "->"
            + outputChannelCount
            + " are not yet implemented.");
  }

  private static float[] createConstantPowerMixingCoefficients(
      int inputChannelCount, int outputChannelCount) {
    if (outputChannelCount == 1) {
      return getConstantPowerCoefficientsToMono(inputChannelCount);
    }
    if (outputChannelCount == 2) {
      return getConstantPowerCoefficientsToStereo(inputChannelCount);
    }
    if (inputChannelCount == outputChannelCount) {
      return initializeIdentityMatrix(outputChannelCount);
    }
    throw new UnsupportedOperationException(
        "Default constant power channel mixing coefficients for "
            + inputChannelCount
            + "->"
            + outputChannelCount
            + " are not implemented.");
  }

  /**
   * Returns a mixing coefficients float array from {@code inputChannelCount} to mono.
   *
   * <p>See <a
   * href=https://cs.android.com/android/platform/superproject/main/+/main:system/media/audio_utils/include/audio_utils/ChannelMix.h;l=40;drc=13412bb94816e57e3a2de1018c65192f5b1a7261>android
   * platform channel mixing calculations</a>.
   */
  private static float[] getConstantPowerCoefficientsToMono(int inputChannelCount) {
    switch (inputChannelCount) {
      case 1:
        // 1 channel: [MONO]
        return new float[] {1.0f};
      case 2:
        // 2 channels: [FRONT_LEFT, FRONT_RIGHT]
        return new float[] {0.7071f, 0.7071f};
      case 3:
        // 3 channels: [FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER]
        return new float[] {0.7071f, 0.7071f, 1.0f};
      case 4:
        // 4 channels: [FRONT_LEFT, FRONT_RIGHT, BACK_LEFT, BACK_RIGHT]
        return new float[] {0.7071f, 0.7071f, 0.5f, 0.5f};
      case 5:
        // 5 channels: [FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, BACK_LEFT, BACK_RIGHT]
        return new float[] {0.7071f, 0.7071f, 1.0f, 0.5f, 0.5f};
      case 6:
        // 6 channels: [FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, LOW_FREQUENCY, BACK_LEFT, BACK_RIGHT]
        return new float[] {0.7071f, 0.7071f, 1.0f, 0.7071f, 0.5f, 0.5f};
      default:
        throw new UnsupportedOperationException(
            "Default constant power channel mixing coefficients for "
                + inputChannelCount
                + "->1 are not implemented.");
    }
  }

  /**
   * Returns a mixing coefficients float array from {@code inputChannelCount} to stereo.
   *
   * <p>See <a
   * href=https://cs.android.com/android/platform/superproject/main/+/main:system/media/audio_utils/include/audio_utils/ChannelMix.h;l=40;drc=13412bb94816e57e3a2de1018c65192f5b1a7261>android
   * platform channel mixing calculations</a>.
   */
  private static float[] getConstantPowerCoefficientsToStereo(int inputChannelCount) {
    switch (inputChannelCount) {
      case 1:
        // 1 channel: [FRONT_CENTER]
        return new float[] {
          0.7071f, // left
          0.7071f // right
        };
      case 2:
        // 2 channels: [FRONT_LEFT, FRONT_RIGHT]
        return new float[] {
          /* left */ 1.0f, 0.0f,
          /* right */ 0.0f, 1.0f
        };
      case 3:
        // 3 channels: [FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER]
        return new float[] {
          /* left */ 1.0f, 0.0f, 0.7071f,
          /* right */ 0.0f, 1.0f, 0.7071f
        };
      case 4:
        // 4 channels: [FRONT_LEFT, FRONT_RIGHT, BACK_LEFT, BACK_RIGHT]
        return new float[] {
          /* left */ 1.0f, 0.0f, 0.7071f, 0.0f,
          /* right */ 0.0f, 1.0f, 0.0f, 0.7071f
        };
      case 5:
        // 5 channels: [FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, BACK_LEFT, BACK_RIGHT]
        return new float[] {
          /* left */ 1.0f, 0.0f, 0.7071f, 0.7071f, 0.0f,
          /* right */ 0.0f, 1.0f, 0.7071f, 0.0f, 0.7071f
        };
      case 6:
        // 6 channels: [FRONT_LEFT, FRONT_RIGHT, FRONT_CENTER, LOW_FREQUENCY, BACK_LEFT, BACK_RIGHT]
        return new float[] {
          /* left */ 1.0f, 0.0f, 0.7071f, 0.5f, 0.7071f, 0.0f,
          /* right */ 0.0f, 1.0f, 0.7071f, 0.5f, 0.0f, 0.7071f
        };
      default:
        throw new UnsupportedOperationException(
            "Default constant power channel mixing coefficients for "
                + inputChannelCount
                + "->2 are not implemented.");
    }
  }
}
