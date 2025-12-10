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

package androidx.media3.transformer;

import static android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR;
import static android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.annotation.SuppressLint;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Represents the video encoder settings. */
@UnstableApi
public final class VideoEncoderSettings {

  /** A value for various fields to indicate that the field's value is unknown or not applicable. */
  public static final int NO_VALUE = Format.NO_VALUE;

  /**
   * A value for {@link Builder#setEncoderPerformanceParameters(int, int)} to disable setting
   * performance parameters.
   */
  public static final int RATE_UNSET = NO_VALUE - 1;

  /** The default I-frame interval in seconds. */
  public static final float DEFAULT_I_FRAME_INTERVAL_SECONDS = 1.0f;

  /** A default {@link VideoEncoderSettings}. */
  public static final VideoEncoderSettings DEFAULT = new Builder().build();

  /**
   * The allowed values for {@code bitrateMode}.
   *
   * <ul>
   *   <li>Variable bitrate: {@link MediaCodecInfo.EncoderCapabilities#BITRATE_MODE_VBR}.
   *   <li>Constant bitrate: {@link MediaCodecInfo.EncoderCapabilities#BITRATE_MODE_CBR}.
   * </ul>
   */
  @SuppressLint("InlinedApi")
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    BITRATE_MODE_VBR,
    BITRATE_MODE_CBR,
  })
  public @interface BitrateMode {}

  /** Builds {@link VideoEncoderSettings} instances. */
  public static final class Builder {
    private int bitrate;
    private @BitrateMode int bitrateMode;
    private int profile;
    private int level;
    private float iFrameIntervalSeconds;
    private int operatingRate;
    private int priority;
    private long repeatPreviousFrameIntervalUs;
    private int maxBFrames;
    private int numNonBidirectionalTemporalLayers;
    private int numBidirectionalTemporalLayers;

    /** Creates a new instance. */
    public Builder() {
      this.bitrate = NO_VALUE;
      this.bitrateMode = BITRATE_MODE_VBR;
      this.profile = NO_VALUE;
      this.level = NO_VALUE;
      this.iFrameIntervalSeconds = DEFAULT_I_FRAME_INTERVAL_SECONDS;
      this.operatingRate = NO_VALUE;
      this.priority = NO_VALUE;
      this.repeatPreviousFrameIntervalUs = NO_VALUE;
      this.maxBFrames = NO_VALUE;
      this.numNonBidirectionalTemporalLayers = NO_VALUE;
      this.numBidirectionalTemporalLayers = NO_VALUE;
    }

    private Builder(VideoEncoderSettings videoEncoderSettings) {
      this.bitrate = videoEncoderSettings.bitrate;
      this.bitrateMode = videoEncoderSettings.bitrateMode;
      this.profile = videoEncoderSettings.profile;
      this.level = videoEncoderSettings.level;
      this.iFrameIntervalSeconds = videoEncoderSettings.iFrameIntervalSeconds;
      this.operatingRate = videoEncoderSettings.operatingRate;
      this.priority = videoEncoderSettings.priority;
      this.repeatPreviousFrameIntervalUs = videoEncoderSettings.repeatPreviousFrameIntervalUs;
      this.maxBFrames = videoEncoderSettings.maxBFrames;
      this.numNonBidirectionalTemporalLayers =
          videoEncoderSettings.numNonBidirectionalTemporalLayers;
      this.numBidirectionalTemporalLayers = videoEncoderSettings.numBidirectionalTemporalLayers;
    }

    /**
     * Sets {@link VideoEncoderSettings#bitrate}. The default value is {@link #NO_VALUE}.
     *
     * @param bitrate The {@link VideoEncoderSettings#bitrate} in bits per second.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setBitrate(int bitrate) {
      this.bitrate = bitrate;
      return this;
    }

    /**
     * Sets {@link VideoEncoderSettings#bitrateMode}. The default value is {@code
     * MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR}.
     *
     * <p>Value must be in {@link BitrateMode}.
     *
     * @param bitrateMode The {@link VideoEncoderSettings#bitrateMode}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setBitrateMode(@BitrateMode int bitrateMode) {
      checkArgument(bitrateMode == BITRATE_MODE_VBR || bitrateMode == BITRATE_MODE_CBR);
      this.bitrateMode = bitrateMode;
      return this;
    }

    /**
     * Sets {@link VideoEncoderSettings#profile} and {@link VideoEncoderSettings#level}. The default
     * values are both {@link #NO_VALUE}.
     *
     * <p>The value must be one of the values defined in {@link MediaCodecInfo.CodecProfileLevel},
     * or {@link #NO_VALUE}.
     *
     * <p>Profile settings will be ignored when using {@link DefaultEncoderFactory} and encoding to
     * H264.
     *
     * @param encodingProfile The {@link VideoEncoderSettings#profile}.
     * @param encodingLevel The {@link VideoEncoderSettings#level}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setEncodingProfileLevel(int encodingProfile, int encodingLevel) {
      this.profile = encodingProfile;
      this.level = encodingLevel;
      return this;
    }

    /**
     * Sets {@link VideoEncoderSettings#iFrameIntervalSeconds}. The default value is {@link
     * #DEFAULT_I_FRAME_INTERVAL_SECONDS}.
     *
     * @param iFrameIntervalSeconds The {@link VideoEncoderSettings#iFrameIntervalSeconds}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setiFrameIntervalSeconds(float iFrameIntervalSeconds) {
      this.iFrameIntervalSeconds = iFrameIntervalSeconds;
      return this;
    }

    /**
     * Sets encoding operating rate and priority. The default values are {@link #NO_VALUE}, which is
     * treated as configuring the encoder for maximum throughput.
     *
     * <p>To disable the configuration for either operating rate or priority, use {@link
     * #RATE_UNSET} for that argument.
     *
     * @param operatingRate The {@link MediaFormat#KEY_OPERATING_RATE operating rate} in frames per
     *     second.
     * @param priority The {@link MediaFormat#KEY_PRIORITY priority}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setEncoderPerformanceParameters(int operatingRate, int priority) {
      this.operatingRate = operatingRate;
      this.priority = priority;
      return this;
    }

    /**
     * Sets the threshold duration between input frames beyond which to repeat the previous frame if
     * no new frame has been received, in microseconds. The default value is {@link #NO_VALUE},
     * which means that frames are not automatically repeated.
     *
     * @param repeatPreviousFrameIntervalUs The {@linkplain
     *     MediaFormat#KEY_REPEAT_PREVIOUS_FRAME_AFTER frame repeat interval}, in microseconds.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setRepeatPreviousFrameIntervalUs(long repeatPreviousFrameIntervalUs) {
      this.repeatPreviousFrameIntervalUs = repeatPreviousFrameIntervalUs;
      return this;
    }

    /**
     * Sets the maximum number of B frames allowed between I or P frames in the produced video. The
     * default value is {@link #NO_VALUE} which means that B frame encoding is disabled.
     *
     * @param maxBFrames the {@linkplain MediaFormat#KEY_MAX_B_FRAMES maximum number of B frames}
     *     allowed.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setMaxBFrames(int maxBFrames) {
      this.maxBFrames = maxBFrames;
      return this;
    }

    /**
     * Sets the number of temporal layers to request from the video encoder.
     *
     * <p>The default value for both parameters is {@link #NO_VALUE} which indicates that no
     * {@linkplain MediaFormat#KEY_TEMPORAL_LAYERING temporal layering schema} will be set for the
     * encoder.
     *
     * @param numNonBidirectionalLayers the number of predictive layers to have. This value must be
     *     stricly positive. A value of '0' explicitly requests no temporal layers from the encoder,
     *     regardless of the requested 'numBidirectionalLayers'.
     * @param numBidirectionalLayers the number of bi-directional layers to have. This value must be
     *     greater than or equal to zero. A value greater than 1 constructs a hierarchical-B coding
     *     structure.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setTemporalLayers(int numNonBidirectionalLayers, int numBidirectionalLayers) {
      this.numNonBidirectionalTemporalLayers = numNonBidirectionalLayers;
      this.numBidirectionalTemporalLayers = numBidirectionalLayers;
      return this;
    }

    /** Builds the instance. */
    public VideoEncoderSettings build() {
      return new VideoEncoderSettings(
          bitrate,
          bitrateMode,
          profile,
          level,
          iFrameIntervalSeconds,
          operatingRate,
          priority,
          repeatPreviousFrameIntervalUs,
          maxBFrames,
          numNonBidirectionalTemporalLayers,
          numBidirectionalTemporalLayers);
    }
  }

  /** The encoding bitrate in bits per second. */
  public final int bitrate;

  /** One of {@linkplain BitrateMode}. */
  public final @BitrateMode int bitrateMode;

  /** The encoding profile. */
  public final int profile;

  /** The encoding level. */
  public final int level;

  /** The encoding I-Frame interval in seconds. */
  public final float iFrameIntervalSeconds;

  /** The encoder {@link MediaFormat#KEY_OPERATING_RATE operating rate} in frames per second. */
  public final int operatingRate;

  /** The encoder {@link MediaFormat#KEY_PRIORITY priority}. */
  public final int priority;

  /**
   * The {@linkplain MediaFormat#KEY_REPEAT_PREVIOUS_FRAME_AFTER frame repeat interval}, in
   * microseconds.
   */
  public final long repeatPreviousFrameIntervalUs;

  /**
   * The {@linkplain MediaFormat#KEY_MAX_B_FRAMES maximum number of B frames} allowed between I and
   * P frames in the produced encoded video.
   */
  public final int maxBFrames;

  /** The requested number of non-bidirectional temporal layers requested from the encoder. */
  public final int numNonBidirectionalTemporalLayers;

  /** The requested number of bidirectional temporal layers requested from the encoder. */
  public final int numBidirectionalTemporalLayers;

  private VideoEncoderSettings(
      int bitrate,
      int bitrateMode,
      int profile,
      int level,
      float iFrameIntervalSeconds,
      int operatingRate,
      int priority,
      long repeatPreviousFrameIntervalUs,
      int maxBFrames,
      int numNonBidirectionalTemporalLayers,
      int numBidirectionalTemporalLayers) {
    this.bitrate = bitrate;
    this.bitrateMode = bitrateMode;
    this.profile = profile;
    this.level = level;
    this.iFrameIntervalSeconds = iFrameIntervalSeconds;
    this.operatingRate = operatingRate;
    this.priority = priority;
    this.repeatPreviousFrameIntervalUs = repeatPreviousFrameIntervalUs;
    this.maxBFrames = maxBFrames;
    this.numNonBidirectionalTemporalLayers = numNonBidirectionalTemporalLayers;
    this.numBidirectionalTemporalLayers = numBidirectionalTemporalLayers;
  }

  /**
   * Returns a {@link VideoEncoderSettings.Builder} initialized with the values of this instance.
   */
  public Builder buildUpon() {
    return new Builder(this);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof VideoEncoderSettings)) {
      return false;
    }
    VideoEncoderSettings that = (VideoEncoderSettings) o;
    return bitrate == that.bitrate
        && bitrateMode == that.bitrateMode
        && profile == that.profile
        && level == that.level
        && iFrameIntervalSeconds == that.iFrameIntervalSeconds
        && operatingRate == that.operatingRate
        && priority == that.priority
        && repeatPreviousFrameIntervalUs == that.repeatPreviousFrameIntervalUs
        && maxBFrames == that.maxBFrames
        && numNonBidirectionalTemporalLayers == that.numNonBidirectionalTemporalLayers
        && numBidirectionalTemporalLayers == that.numBidirectionalTemporalLayers;
  }

  @Override
  public int hashCode() {
    int result = 7;
    result = 31 * result + bitrate;
    result = 31 * result + bitrateMode;
    result = 31 * result + profile;
    result = 31 * result + level;
    result = 31 * result + Float.floatToIntBits(iFrameIntervalSeconds);
    result = 31 * result + operatingRate;
    result = 31 * result + priority;
    result =
        31 * result
            + (int) (repeatPreviousFrameIntervalUs ^ (repeatPreviousFrameIntervalUs >>> 32));
    result = 31 * result + maxBFrames;
    result = 31 * result + numNonBidirectionalTemporalLayers;
    result = 31 * result + numBidirectionalTemporalLayers;
    return result;
  }

  @Override
  public String toString() {
    return "VideoEncoderSettings{"
        + "bitrate="
        + bitrate
        + ", bitrateMode="
        + bitrateMode
        + ", profile="
        + profile
        + ", level="
        + level
        + ", iFrameIntervalSeconds="
        + iFrameIntervalSeconds
        + ", operatingRate="
        + operatingRate
        + ", priority="
        + priority
        + ", repeatPreviousFrameIntervalUs="
        + repeatPreviousFrameIntervalUs
        + ", maxBFrames="
        + maxBFrames
        + ", numNonBidirectionalTemporalLayers="
        + numNonBidirectionalTemporalLayers
        + ", numBidirectionalTemporalLayers="
        + numBidirectionalTemporalLayers
        + '}';
  }
}
