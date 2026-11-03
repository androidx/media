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

/** Utility methods for CodecDB. */
/* package */ final class CodecDbUtil {

  /**
   * Validates that the provided width, height, and frame rate are valid video dimensions.
   *
   * @param width The width in pixels.
   * @param height The height in pixels.
   * @param frameRate The frame rate in frames per second.
   * @throws IllegalArgumentException if width <= 0, height <= 0, or frameRate <= 0.0 or not finite.
   */
  public static void validateDimensions(int width, int height, double frameRate) {
    checkArgument(width > 0, "Width must be strictly greater than 0: %s", width);
    checkArgument(height > 0, "Height must be strictly greater than 0: %s", height);
    checkArgument(
        frameRate > 0.0 && Double.isFinite(frameRate),
        "Frame rate must be strictly greater than 0 and finite: %s",
        frameRate);
  }

  /**
   * Calculates the spatio-temporal throughput in pixels per second using 64-bit precision.
   *
   * @param width The width in pixels.
   * @param height The height in pixels.
   * @param frameRate The frame rate in frames per second.
   * @return The throughput in pixels per second.
   */
  public static long calculatePixelsPerSecond(int width, int height, double frameRate) {
    validateDimensions(width, height, frameRate);
    return Math.round((double) width * height * frameRate);
  }

  private CodecDbUtil() {}
}
