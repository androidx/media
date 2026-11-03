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

import static androidx.media3.transformer.codecdb.CodecDbUtil.validateDimensions;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import android.media.MediaCodecInfo.EncoderCapabilities;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.transformer.VideoEncoderSettings;
import com.google.common.base.MoreObjects;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Immutable set of user-provided constraints that CodecDB can optimize around.
 *
 * <p>{@link VideoEncoderConstraintsPresets} provides instances with presets for common encoding use
 * cases. Callers can override individual constraints via {@link #buildUpon()}.
 */
@ExperimentalApi // TODO(b/579130160): Promote to @UnstableApi or remove.
public final class VideoEncoderConstraints {

  /**
   * Target quality level representing the minimum recommended quality (VMAF 70).
   *
   * <p>Use this level only when bitrate is constrained.
   */
  public static final int QUALITY_LEVEL_MINIMUM = 70;

  /**
   * Target quality level representing acceptable quality (VMAF 76).
   *
   * <p>Recommended when bitrate or file size is a concern.
   */
  public static final int QUALITY_LEVEL_ACCEPTABLE = 76;

  /**
   * Target quality level representing good quality (VMAF 82).
   *
   * <p>Recommended for content exported to the device gallery or high-quality social media uploads.
   */
  public static final int QUALITY_LEVEL_GOOD = 82;

  /**
   * Target quality level representing very good quality (VMAF 88).
   *
   * <p>Balances visual fidelity and file size.
   */
  public static final int QUALITY_LEVEL_VERY_GOOD = 88;

  /**
   * Target quality level representing near-lossless quality (VMAF 94).
   *
   * <p>Recommended for camera capture or archival.
   */
  public static final int QUALITY_LEVEL_NEAR_LOSSLESS = 94;

  /** Bitmask representing any supported video codec. */
  /* package */ static final @C.VideoCodecFlags int ANY_VIDEO_CODEC =
      C.VIDEO_CODEC_FLAG_H264 | C.VIDEO_CODEC_FLAG_H265 | C.VIDEO_CODEC_FLAG_AV1;

  /** Builder for {@link VideoEncoderConstraints} instances. */
  public static final class Builder {

    private @C.VideoCodecFlags int allowedCodecs;
    private int width;
    private int height;
    private double frameRate;
    private double targetQuality;
    private int maxBitrate;
    private float keyFrameIntervalSeconds;
    private boolean bFramesAllowed;
    private @VideoEncoderSettings.BitrateMode int bitrateMode;
    private int bitDepth;

    /**
     * Creates a new instance.
     *
     * @param width The {@linkplain #width width} in pixels.
     * @param height The {@linkplain #height height} in pixels.
     * @param frameRate The {@linkplain #frameRate frame rate} in frames per second.
     * @throws IllegalArgumentException if {@code width} or {@code height} is not positive, or if
     *     {@code frameRate} is not positive and finite.
     */
    public Builder(int width, int height, double frameRate) {
      validateDimensions(width, height, frameRate);
      this.width = width;
      this.height = height;
      this.frameRate = frameRate;
      allowedCodecs = ANY_VIDEO_CODEC;
      targetQuality = Format.NO_VALUE;
      maxBitrate = Format.NO_VALUE;
      keyFrameIntervalSeconds = 1;
      bFramesAllowed = true;
      bitrateMode = EncoderCapabilities.BITRATE_MODE_VBR;
      bitDepth = 8;
    }

    private Builder(VideoEncoderConstraints constraints) {
      allowedCodecs = constraints.allowedCodecs;
      width = constraints.width;
      height = constraints.height;
      frameRate = constraints.frameRate;
      targetQuality = constraints.targetQuality;
      maxBitrate = constraints.maxBitrate;
      keyFrameIntervalSeconds = constraints.keyFrameIntervalSeconds;
      bFramesAllowed = constraints.bFramesAllowed;
      bitrateMode = constraints.bitrateMode;
      bitDepth = constraints.bitDepth;
    }

    /**
     * Sets the {@linkplain #width width} in pixels, {@linkplain #height height} in pixels, and
     * {@linkplain #frameRate frame rate} in frames per second.
     *
     * @throws IllegalArgumentException if {@code width} or {@code height} is not positive, or if
     *     {@code frameRate} is not positive and finite.
     */
    @CanIgnoreReturnValue
    public Builder setResolution(int width, int height, double frameRate) {
      validateDimensions(width, height, frameRate);
      this.width = width;
      this.height = height;
      this.frameRate = frameRate;
      return this;
    }

    /**
     * Sets the {@linkplain #allowedCodecs allowed codecs}.
     *
     * <p>The default is {@code C.VIDEO_CODEC_FLAG_H264 | C.VIDEO_CODEC_FLAG_H265 |
     * C.VIDEO_CODEC_FLAG_AV1}.
     */
    @CanIgnoreReturnValue
    public Builder setAllowedCodecs(@C.VideoCodecFlags int allowedCodecs) {
      this.allowedCodecs = allowedCodecs;
      return this;
    }

    /**
     * Sets the {@linkplain #targetQuality target quality}, or {@link Format#NO_VALUE} to clear it.
     *
     * <p>The default is {@link #QUALITY_LEVEL_NEAR_LOSSLESS} when neither {@link #targetQuality}
     * nor {@link #maxBitrate} is set.
     *
     * @throws IllegalArgumentException if {@code targetQuality} is not in (0, 100] and is not
     *     {@link Format#NO_VALUE}.
     * @throws IllegalStateException if {@link #maxBitrate} is already set.
     */
    @CanIgnoreReturnValue
    public Builder setTargetQuality(double targetQuality) {
      checkArgument(
          targetQuality == Format.NO_VALUE || (targetQuality > 0.0 && targetQuality <= 100.0),
          "Target quality must be Format.NO_VALUE or in (0, 100]: %s",
          targetQuality);
      checkState(
          maxBitrate == Format.NO_VALUE,
          "Cannot set targetQuality when maxBitrate is already set.");
      this.targetQuality = targetQuality;
      return this;
    }

    /**
     * Sets the {@linkplain #maxBitrate maximum bitrate}, in bits per second, or {@link
     * Format#NO_VALUE} to clear it.
     *
     * <p>The default is {@link Format#NO_VALUE}.
     *
     * @throws IllegalArgumentException if {@code maxBitrate} is not positive and is not {@link
     *     Format#NO_VALUE}.
     * @throws IllegalStateException if {@link #targetQuality} is already set.
     */
    @CanIgnoreReturnValue
    public Builder setMaxBitrate(int maxBitrate) {
      checkArgument(
          maxBitrate == Format.NO_VALUE || maxBitrate > 0,
          "Max bitrate must be Format.NO_VALUE or strictly greater than zero: %s",
          maxBitrate);
      checkState(
          targetQuality == Format.NO_VALUE,
          "Cannot set maxBitrate when targetQuality is already set.");
      this.maxBitrate = maxBitrate;
      return this;
    }

    /**
     * Sets the {@linkplain #keyFrameIntervalSeconds key-frame interval}, in seconds.
     *
     * <p>The default is one second.
     *
     * @throws IllegalArgumentException if {@code keyFrameIntervalSeconds} is not positive and
     *     finite.
     */
    @CanIgnoreReturnValue
    public Builder setKeyFrameIntervalSeconds(float keyFrameIntervalSeconds) {
      checkArgument(
          keyFrameIntervalSeconds > 0 && Float.isFinite(keyFrameIntervalSeconds),
          "Key-frame interval must be strictly positive and finite: %s",
          keyFrameIntervalSeconds);
      this.keyFrameIntervalSeconds = keyFrameIntervalSeconds;
      return this;
    }

    /**
     * Sets whether {@linkplain #bFramesAllowed B-frames are allowed}.
     *
     * <p>The default is {@code true}.
     */
    @CanIgnoreReturnValue
    public Builder setBFramesAllowed(boolean bFramesAllowed) {
      this.bFramesAllowed = bFramesAllowed;
      return this;
    }

    /**
     * Sets the {@linkplain #bitrateMode bitrate mode}.
     *
     * <p>The default is {@link EncoderCapabilities#BITRATE_MODE_VBR}.
     */
    @CanIgnoreReturnValue
    public Builder setBitrateMode(@VideoEncoderSettings.BitrateMode int bitrateMode) {
      this.bitrateMode = bitrateMode;
      return this;
    }

    /**
     * Sets the {@linkplain #bitDepth bit depth}.
     *
     * <p>The default is {@code 8}.
     */
    @CanIgnoreReturnValue
    public Builder setBitDepth(int bitDepth) {
      this.bitDepth = bitDepth;
      return this;
    }

    /** Builds a {@link VideoEncoderConstraints} instance. */
    public VideoEncoderConstraints build() {
      if (targetQuality == Format.NO_VALUE && maxBitrate == Format.NO_VALUE) {
        targetQuality = QUALITY_LEVEL_NEAR_LOSSLESS;
      }
      return new VideoEncoderConstraints(this);
    }
  }

  /** The set of {@link C.VideoCodecFlags} that are allowed. */
  public final @C.VideoCodecFlags int allowedCodecs;

  /** The user-specified width of the content to be encoded in pixels. */
  public final int width;

  /** The user-specified height of the content to be encoded in pixels. */
  public final int height;

  /** The user-specified frame rate of the content to be encoded in frames per second. */
  public final double frameRate;

  /**
   * The target quality as a VMAF score in (0, 100], or {@link Format#NO_VALUE} if unset.
   *
   * <p>Exactly one of {@link #targetQuality} and {@link #maxBitrate} is set.
   */
  public final double targetQuality;

  /**
   * The maximum bitrate in bits per second, or {@link Format#NO_VALUE} if unset.
   *
   * <p>Exactly one of {@link #targetQuality} and {@link #maxBitrate} is set.
   */
  public final int maxBitrate;

  /** The desired key-frame interval to be set in the encoder, in seconds. */
  public final float keyFrameIntervalSeconds;

  /** Whether B-frames are allowed for the content to be encoded. */
  public final boolean bFramesAllowed;

  /** The required bitrate mode for the encoder. */
  public final @VideoEncoderSettings.BitrateMode int bitrateMode;

  /** The bit depth of the content to be encoded. */
  public final int bitDepth;

  private VideoEncoderConstraints(Builder builder) {
    checkState(
        (builder.targetQuality != Format.NO_VALUE) != (builder.maxBitrate != Format.NO_VALUE));
    allowedCodecs = builder.allowedCodecs;
    width = builder.width;
    height = builder.height;
    frameRate = builder.frameRate;
    targetQuality = builder.targetQuality;
    maxBitrate = builder.maxBitrate;
    keyFrameIntervalSeconds = builder.keyFrameIntervalSeconds;
    bFramesAllowed = builder.bFramesAllowed;
    bitrateMode = builder.bitrateMode;
    bitDepth = builder.bitDepth;
  }

  /** Returns a {@link Builder} initialized with the values of this instance. */
  public Builder buildUpon() {
    return new Builder(this);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof VideoEncoderConstraints)) {
      return false;
    }
    VideoEncoderConstraints that = (VideoEncoderConstraints) o;
    return allowedCodecs == that.allowedCodecs
        && width == that.width
        && height == that.height
        && Double.compare(frameRate, that.frameRate) == 0
        && Double.compare(targetQuality, that.targetQuality) == 0
        && maxBitrate == that.maxBitrate
        && Float.compare(keyFrameIntervalSeconds, that.keyFrameIntervalSeconds) == 0
        && bFramesAllowed == that.bFramesAllowed
        && bitrateMode == that.bitrateMode
        && bitDepth == that.bitDepth;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        allowedCodecs,
        width,
        height,
        frameRate,
        targetQuality,
        maxBitrate,
        keyFrameIntervalSeconds,
        bFramesAllowed,
        bitrateMode,
        bitDepth);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("allowedCodecs", allowedCodecs)
        .add("width", width)
        .add("height", height)
        .add("frameRate", frameRate)
        .add("targetQuality", targetQuality)
        .add("maxBitrate", maxBitrate)
        .add("keyFrameIntervalSeconds", keyFrameIntervalSeconds)
        .add("bFramesAllowed", bFramesAllowed)
        .add("bitrateMode", bitrateMode)
        .add("bitDepth", bitDepth)
        .toString();
  }
}
