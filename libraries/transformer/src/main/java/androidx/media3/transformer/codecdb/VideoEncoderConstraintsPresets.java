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

import static android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR;
import static androidx.media3.transformer.codecdb.VideoEncoderConstraints.QUALITY_LEVEL_GOOD;
import static androidx.media3.transformer.codecdb.VideoEncoderConstraints.QUALITY_LEVEL_NEAR_LOSSLESS;
import static androidx.media3.transformer.codecdb.VideoEncoderConstraints.QUALITY_LEVEL_VERY_GOOD;

import androidx.media3.common.util.ExperimentalApi;

/**
 * Factories for {@link VideoEncoderConstraints} with presets for common encoding use cases.
 *
 * <p>Callers can override individual constraints of a preset via {@link
 * VideoEncoderConstraints#buildUpon()}.
 */
@ExperimentalApi // TODO(b/579130160): Promote to @UnstableApi or remove.
public final class VideoEncoderConstraintsPresets {

  /**
   * Returns constraints with presets for high-quality camera recording.
   *
   * <p>The target quality is {@link VideoEncoderConstraints#QUALITY_LEVEL_NEAR_LOSSLESS}. Uses
   * {@linkplain VideoEncoderConstraints.Builder#setBitrateMode VBR rate control} with {@linkplain
   * VideoEncoderConstraints.Builder#setBFramesAllowed B-frames allowed}.
   *
   * @param width The video width, in pixels.
   * @param height The video height, in pixels.
   * @param frameRate The video frame rate, in frames per second.
   * @throws IllegalArgumentException if {@code width} or {@code height} is not positive, or if
   *     {@code frameRate} is not positive and finite.
   */
  public static VideoEncoderConstraints forHqCameraRecording(
      int width, int height, double frameRate) {
    return new VideoEncoderConstraints.Builder(width, height, frameRate)
        .setTargetQuality(QUALITY_LEVEL_NEAR_LOSSLESS)
        .build();
  }

  /**
   * Returns constraints with presets for video editing.
   *
   * <p>The target quality is {@link VideoEncoderConstraints#QUALITY_LEVEL_VERY_GOOD}. Uses
   * {@linkplain VideoEncoderConstraints.Builder#setBitrateMode VBR rate control} with {@linkplain
   * VideoEncoderConstraints.Builder#setBFramesAllowed B-frames allowed}.
   *
   * @param width The video width, in pixels.
   * @param height The video height, in pixels.
   * @param frameRate The video frame rate, in frames per second.
   * @throws IllegalArgumentException if {@code width} or {@code height} is not positive, or if
   *     {@code frameRate} is not positive and finite.
   */
  public static VideoEncoderConstraints forEditing(int width, int height, double frameRate) {
    return new VideoEncoderConstraints.Builder(width, height, frameRate)
        .setTargetQuality(QUALITY_LEVEL_VERY_GOOD)
        .build();
  }

  /**
   * Returns constraints with presets for social media posting.
   *
   * <p>The target quality is {@link VideoEncoderConstraints#QUALITY_LEVEL_GOOD}. Uses {@linkplain
   * VideoEncoderConstraints.Builder#setBitrateMode VBR rate control} with {@linkplain
   * VideoEncoderConstraints.Builder#setBFramesAllowed B-frames allowed}. Currently uses the same
   * presets as {@link #forGalleryExport}, which is a separate use case so that its presets can
   * diverge in the future.
   *
   * @param width The video width, in pixels.
   * @param height The video height, in pixels.
   * @param frameRate The video frame rate, in frames per second.
   * @throws IllegalArgumentException if {@code width} or {@code height} is not positive, or if
   *     {@code frameRate} is not positive and finite.
   */
  public static VideoEncoderConstraints forSocialPosting(int width, int height, double frameRate) {
    return new VideoEncoderConstraints.Builder(width, height, frameRate)
        .setTargetQuality(QUALITY_LEVEL_GOOD)
        .build();
  }

  /**
   * Returns constraints with presets for exporting a video to the device gallery.
   *
   * <p>The target quality is {@link VideoEncoderConstraints#QUALITY_LEVEL_GOOD}. Uses {@linkplain
   * VideoEncoderConstraints.Builder#setBitrateMode VBR rate control} with {@linkplain
   * VideoEncoderConstraints.Builder#setBFramesAllowed B-frames allowed}. Currently uses the same
   * presets as {@link #forSocialPosting}, which is a separate use case so that its presets can
   * diverge in the future.
   *
   * @param width The video width, in pixels.
   * @param height The video height, in pixels.
   * @param frameRate The video frame rate, in frames per second.
   * @throws IllegalArgumentException if {@code width} or {@code height} is not positive, or if
   *     {@code frameRate} is not positive and finite.
   */
  public static VideoEncoderConstraints forGalleryExport(int width, int height, double frameRate) {
    return new VideoEncoderConstraints.Builder(width, height, frameRate)
        .setTargetQuality(QUALITY_LEVEL_GOOD)
        .build();
  }

  /**
   * Returns constraints with presets for messaging, where fitting within a strict file-size or
   * bitrate limit is the top priority.
   *
   * <p>Uses {@linkplain VideoEncoderConstraints.Builder#setBitrateMode VBR rate control} with
   * {@linkplain VideoEncoderConstraints.Builder#setBFramesAllowed B-frames allowed}.
   *
   * @param width The video width, in pixels.
   * @param height The video height, in pixels.
   * @param frameRate The video frame rate, in frames per second.
   * @param targetMaxBitrate The target maximum bitrate, in bits per second (bps).
   * @throws IllegalArgumentException if {@code width}, {@code height}, or {@code targetMaxBitrate}
   *     is not positive, or if {@code frameRate} is not positive and finite.
   */
  public static VideoEncoderConstraints forMessaging(
      int width, int height, double frameRate, int targetMaxBitrate) {
    return new VideoEncoderConstraints.Builder(width, height, frameRate)
        .setMaxBitrate(targetMaxBitrate)
        .build();
  }

  /**
   * Returns constraints with presets for real-time communication (such as video calling) optimized
   * for minimal latency.
   *
   * <p>Uses {@linkplain VideoEncoderConstraints.Builder#setBitrateMode CBR rate control} with
   * {@linkplain VideoEncoderConstraints.Builder#setBFramesAllowed B-frames disallowed}.
   *
   * @param width The video width, in pixels.
   * @param height The video height, in pixels.
   * @param frameRate The video frame rate, in frames per second.
   * @param targetMaxBitrate The target maximum bitrate, in bits per second (bps).
   * @throws IllegalArgumentException if {@code width}, {@code height}, or {@code targetMaxBitrate}
   *     is not positive, or if {@code frameRate} is not positive and finite.
   */
  public static VideoEncoderConstraints forRealTimeCommunication(
      int width, int height, double frameRate, int targetMaxBitrate) {
    return new VideoEncoderConstraints.Builder(width, height, frameRate)
        .setMaxBitrate(targetMaxBitrate)
        .setBFramesAllowed(false)
        .setBitrateMode(BITRATE_MODE_CBR)
        .build();
  }

  /**
   * Returns constraints with presets for live streaming.
   *
   * <p>Uses {@linkplain VideoEncoderConstraints.Builder#setBitrateMode CBR rate control} with
   * {@linkplain VideoEncoderConstraints.Builder#setBFramesAllowed B-frames allowed}.
   *
   * @param width The video width, in pixels.
   * @param height The video height, in pixels.
   * @param frameRate The video frame rate, in frames per second.
   * @param targetMaxBitrate The target maximum bitrate, in bits per second (bps).
   * @throws IllegalArgumentException if {@code width}, {@code height}, or {@code targetMaxBitrate}
   *     is not positive, or if {@code frameRate} is not positive and finite.
   */
  public static VideoEncoderConstraints forLiveStreaming(
      int width, int height, double frameRate, int targetMaxBitrate) {
    return new VideoEncoderConstraints.Builder(width, height, frameRate)
        .setMaxBitrate(targetMaxBitrate)
        .setBitrateMode(BITRATE_MODE_CBR)
        .build();
  }

  private VideoEncoderConstraintsPresets() {}
}
