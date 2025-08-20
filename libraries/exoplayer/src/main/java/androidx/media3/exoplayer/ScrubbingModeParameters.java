/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.exoplayer;

import static com.google.common.base.Preconditions.checkArgument;

import android.media.MediaCodec;
import android.media.MediaFormat;
import androidx.annotation.FloatRange;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.C.TrackType;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Objects;
import java.util.Set;

/**
 * Parameters to control the behavior of {@linkplain ExoPlayer#setScrubbingModeEnabled scrubbing
 * mode}.
 */
@UnstableApi
public final class ScrubbingModeParameters {

  /** An instance which defines sensible default values for many scrubbing use-cases. */
  public static final ScrubbingModeParameters DEFAULT =
      new ScrubbingModeParameters.Builder().build();

  /**
   * Builder for {@link ScrubbingModeParameters} instances.
   *
   * <p>This builder defines some defaults that may change in future releases of the library, and
   * new properties may be added that default to enabled.
   */
  public static final class Builder {
    private ImmutableSet<@TrackType Integer> disabledTrackTypes;
    @Nullable private Double fractionalSeekToleranceBefore;
    @Nullable private Double fractionalSeekToleranceAfter;
    private boolean shouldIncreaseCodecOperatingRate;
    private boolean allowSkippingMediaCodecFlush;
    private boolean allowSkippingKeyFrameReset;
    private boolean shouldEnableDynamicScheduling;
    private boolean useDecodeOnlyFlag;

    /** Creates an instance. */
    public Builder() {
      this.disabledTrackTypes = ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_METADATA);
      shouldIncreaseCodecOperatingRate = true;
      allowSkippingMediaCodecFlush = true;
      allowSkippingKeyFrameReset = true;
      shouldEnableDynamicScheduling = true;
      useDecodeOnlyFlag = true;
    }

    private Builder(ScrubbingModeParameters scrubbingModeParameters) {
      this.disabledTrackTypes = scrubbingModeParameters.disabledTrackTypes;
      this.fractionalSeekToleranceBefore = scrubbingModeParameters.fractionalSeekToleranceBefore;
      this.fractionalSeekToleranceAfter = scrubbingModeParameters.fractionalSeekToleranceAfter;
      this.shouldIncreaseCodecOperatingRate =
          scrubbingModeParameters.shouldIncreaseCodecOperatingRate;
      this.allowSkippingMediaCodecFlush = scrubbingModeParameters.allowSkippingMediaCodecFlush;
      this.allowSkippingKeyFrameReset = scrubbingModeParameters.allowSkippingKeyFrameReset;
      this.shouldEnableDynamicScheduling = scrubbingModeParameters.shouldEnableDynamicScheduling;
      this.useDecodeOnlyFlag = scrubbingModeParameters.useDecodeOnlyFlag;
    }

    /**
     * Sets which track types should be disabled in scrubbing mode.
     *
     * <p>Defaults to {@link C#TRACK_TYPE_AUDIO} and {@link C#TRACK_TYPE_METADATA} (this may change
     * in a future release).
     *
     * <p>See {@link ScrubbingModeParameters#disabledTrackTypes}.
     *
     * @param disabledTrackTypes The track types to disable in scrubbing mode.
     * @return This builder for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setDisabledTrackTypes(Set<@TrackType Integer> disabledTrackTypes) {
      this.disabledTrackTypes = ImmutableSet.copyOf(disabledTrackTypes);
      return this;
    }

    /**
     * Sets the fraction of the media duration to use for {@link SeekParameters#toleranceBeforeUs}
     * and {@link SeekParameters#toleranceAfterUs} when scrubbing.
     *
     * <p>Pass {@code null} for both values to use the {@linkplain ExoPlayer#getSeekParameters()
     * player-level seek parameters} when scrubbing.
     *
     * <p>Defaults to {code null} for both values, so all seeks are exact (this may change in a
     * future release).
     *
     * <p>See {@link ScrubbingModeParameters#fractionalSeekToleranceBefore} and {@link
     * ScrubbingModeParameters#fractionalSeekToleranceAfter}.
     *
     * @param toleranceBefore The fraction of the media duration to use for {@link
     *     SeekParameters#toleranceBeforeUs}, or null to use the player-level seek parameters.
     * @param toleranceAfter The fraction of the media duration to use for {@link
     *     SeekParameters#toleranceAfterUs}, or null to use the player-level seek parameters.
     * @return This builder for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setFractionalSeekTolerance(
        @Nullable @FloatRange(from = 0, to = 1) Double toleranceBefore,
        @Nullable @FloatRange(from = 0, to = 1) Double toleranceAfter) {
      checkArgument((toleranceBefore == null) == (toleranceAfter == null));
      checkArgument(toleranceBefore == null || (toleranceBefore >= 0 && toleranceBefore <= 1));
      checkArgument(toleranceAfter == null || (toleranceAfter >= 0 && toleranceAfter <= 1));
      this.fractionalSeekToleranceBefore = toleranceBefore;
      this.fractionalSeekToleranceAfter = toleranceAfter;
      return this;
    }

    /**
     * Sets whether the codec operating rate should be increased in scrubbing mode.
     *
     * <p>Defaults to {@code true} (this may change in a future release).
     *
     * <p>See {@link ScrubbingModeParameters#shouldIncreaseCodecOperatingRate}.
     *
     * @param shouldIncreaseCodecOperatingRate whether the codec operating rate should be increased
     *     in scrubbing mode.
     * @return This builder for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setShouldIncreaseCodecOperatingRate(boolean shouldIncreaseCodecOperatingRate) {
      this.shouldIncreaseCodecOperatingRate = shouldIncreaseCodecOperatingRate;
      return this;
    }

    /**
     * Sets whether ExoPlayer's {@linkplain
     * ExoPlayer.Builder#experimentalSetDynamicSchedulingEnabled(boolean) dynamic scheduling} should
     * be enabled in scrubbing mode.
     *
     * <p>When used with {@link MediaCodec} in async mode, this can result in available output
     * buffers being handled more quickly when seeking.
     *
     * <p>If dynamic scheduling is enabled for all playback in {@link ExoPlayer.Builder} (which may
     * become the default in a future release), this method is a no-op (i.e. you cannot disable
     * dynamic scheduling when scrubbing using this method).
     *
     * <p>Defaults to {@code true} (this may change in a future release).
     *
     * @param shouldEnableDynamicScheduling Whether dynamic scheduling should be enabled in
     *     scrubbing mode.
     * @return This builder for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setShouldEnableDynamicScheduling(boolean shouldEnableDynamicScheduling) {
      this.shouldEnableDynamicScheduling = shouldEnableDynamicScheduling;
      return this;
    }

    /**
     * @deprecated Use {@link #setAllowSkippingMediaCodecFlush} instead (but note that the value it
     *     takes is inverted).
     */
    @Deprecated
    @CanIgnoreReturnValue
    public Builder setIsMediaCodecFlushEnabled(boolean isMediaCodecFlushEnabled) {
      this.allowSkippingMediaCodecFlush = !isMediaCodecFlushEnabled;
      return this;
    }

    /**
     * Sets whether to avoid flushing the decoder (where possible) in scrubbing mode.
     *
     * <p>Setting this to {@code true} will avoid flushing the decoder when a new seek starts
     * decoding from a key-frame in compatible content.
     *
     * <p>Defaults to {@code true} (this may change in a future release).
     *
     * @param allowSkippingMediaCodecFlush Whether skip flushing the decoder (where possible) in
     *     scrubbing mode.
     * @return This builder for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setAllowSkippingMediaCodecFlush(boolean allowSkippingMediaCodecFlush) {
      this.allowSkippingMediaCodecFlush = allowSkippingMediaCodecFlush;
      return this;
    }

    /**
     * Sets whether to avoid resetting to a keyframe and flushing the decoder if seeking forwards
     * within the same group of pictures(GOP).
     *
     * <p>Setting this to {@code true} will skip flushing the decoder and decoding previously
     * processed frames.
     *
     * <p>Defaults to {@code true}.
     *
     * @param allowSkippingKeyFrameReset Whether to skip resetting to the keyframe when seeking
     *     forwards in the same group of pictures in scrubbing mode.
     * @return This builder for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setAllowSkippingKeyFrameReset(boolean allowSkippingKeyFrameReset) {
      this.allowSkippingKeyFrameReset = allowSkippingKeyFrameReset;
      return this;
    }

    /**
     * Sets whether to use {@link MediaCodec#BUFFER_FLAG_DECODE_ONLY} in scrubbing mode.
     *
     * <p>When playback is using {@link MediaCodec} on API 34+, this flag can speed up seeking by
     * signalling that the decoded output of buffers between the previous keyframe and the target
     * frame is not needed by the player.
     *
     * <p>If the decode-only flag is {@linkplain
     * MediaCodecVideoRenderer.Builder#experimentalSetEnableMediaCodecBufferDecodeOnlyFlag enabled}
     * (which may become the default in a future release), this method is a no-op (i.e. you cannot
     * disable usage of the decode-only flag when scrubbing using this method).
     *
     * <p>Defaults to {@code true} (this may change in a future release).
     */
    @CanIgnoreReturnValue
    public Builder setUseDecodeOnlyFlag(boolean useDecodeOnlyFlag) {
      this.useDecodeOnlyFlag = useDecodeOnlyFlag;
      return this;
    }

    /** Returns the built {@link ScrubbingModeParameters}. */
    public ScrubbingModeParameters build() {
      return new ScrubbingModeParameters(this);
    }
  }

  /** Which track types will be disabled in scrubbing mode. */
  public final ImmutableSet<@TrackType Integer> disabledTrackTypes;

  /**
   * The fraction of the media duration to use for {@link SeekParameters#toleranceBeforeUs} when
   * scrubbing.
   *
   * <p>If this is {@code null} or the media duration is not known then the {@linkplain
   * ExoPlayer#getSeekParameters()} non-scrubbing seek parameters} are used.
   */
  @Nullable
  @FloatRange(from = 0, to = 1)
  public final Double fractionalSeekToleranceBefore;

  /**
   * The fraction of the media duration to use for {@link SeekParameters#toleranceAfterUs} when
   * scrubbing.
   *
   * <p>If this is {@code null} or the media duration is not known then the {@linkplain
   * ExoPlayer#getSeekParameters()} non-scrubbing seek parameters} are used.
   */
  @Nullable
  @FloatRange(from = 0, to = 1)
  public final Double fractionalSeekToleranceAfter;

  /**
   * Whether the codec operating rate should be increased in scrubbing mode.
   *
   * <p>If using {@link MediaCodec} for video decoding, {@link MediaFormat#KEY_OPERATING_RATE} will
   * be set to an increased value in scrubbing mode.
   */
  public final boolean shouldIncreaseCodecOperatingRate;

  /**
   * @deprecated Use {@link #allowSkippingMediaCodecFlush} instead (but note that it's value is
   *     inverted).
   */
  @Deprecated public final boolean isMediaCodecFlushEnabled;

  /**
   * Whether flushing the decoder is avoided where possible in scrubbing mode.
   *
   * <p>Defaults to {@code true}.
   */
  public final boolean allowSkippingMediaCodecFlush;

  /**
   * Whether to enable ExoPlayer's {@linkplain
   * ExoPlayer.Builder#experimentalSetDynamicSchedulingEnabled(boolean) dynamic scheduling} in
   * scrubbing mode.
   */
  public final boolean shouldEnableDynamicScheduling;

  /**
   * Whether to use {@link MediaCodec#BUFFER_FLAG_DECODE_ONLY} in scrubbing mode.
   *
   * <p>This only has an effect on API 34+ when playback is using {@link MediaCodec} for decoding.
   */
  public final boolean useDecodeOnlyFlag;

  /**
   * Whether to avoid resetting to a keyframe during a forward seek within the same GoP.
   *
   * <p>Defaults to {@code true}.
   */
  public final boolean allowSkippingKeyFrameReset;

  private ScrubbingModeParameters(Builder builder) {
    this.disabledTrackTypes = builder.disabledTrackTypes;
    this.fractionalSeekToleranceBefore = builder.fractionalSeekToleranceBefore;
    this.fractionalSeekToleranceAfter = builder.fractionalSeekToleranceAfter;
    this.shouldIncreaseCodecOperatingRate = builder.shouldIncreaseCodecOperatingRate;
    this.isMediaCodecFlushEnabled = !builder.allowSkippingMediaCodecFlush;
    this.allowSkippingMediaCodecFlush = builder.allowSkippingMediaCodecFlush;
    this.allowSkippingKeyFrameReset = builder.allowSkippingKeyFrameReset;
    this.shouldEnableDynamicScheduling = builder.shouldEnableDynamicScheduling;
    this.useDecodeOnlyFlag = builder.useDecodeOnlyFlag;
  }

  /** Returns a {@link Builder} initialized with the values from this instance. */
  public Builder buildUpon() {
    return new Builder(this);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (!(o instanceof ScrubbingModeParameters)) {
      return false;
    }
    ScrubbingModeParameters that = (ScrubbingModeParameters) o;
    return disabledTrackTypes.equals(that.disabledTrackTypes)
        && allowSkippingMediaCodecFlush == that.allowSkippingMediaCodecFlush
        && allowSkippingKeyFrameReset == that.allowSkippingKeyFrameReset
        && Objects.equals(fractionalSeekToleranceBefore, that.fractionalSeekToleranceBefore)
        && Objects.equals(fractionalSeekToleranceAfter, that.fractionalSeekToleranceAfter)
        && shouldIncreaseCodecOperatingRate == that.shouldIncreaseCodecOperatingRate
        && shouldEnableDynamicScheduling == that.shouldEnableDynamicScheduling
        && useDecodeOnlyFlag == that.useDecodeOnlyFlag;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        disabledTrackTypes,
        fractionalSeekToleranceBefore,
        fractionalSeekToleranceAfter,
        shouldIncreaseCodecOperatingRate,
        allowSkippingMediaCodecFlush,
        allowSkippingKeyFrameReset,
        shouldEnableDynamicScheduling,
        useDecodeOnlyFlag);
  }
}
