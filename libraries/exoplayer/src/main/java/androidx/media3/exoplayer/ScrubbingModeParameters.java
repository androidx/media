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

import static androidx.media3.common.util.Assertions.checkArgument;

import androidx.annotation.FloatRange;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.C.TrackType;
import androidx.media3.common.util.UnstableApi;
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

    /** Creates an instance. */
    public Builder() {
      this.disabledTrackTypes = ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_METADATA);
    }

    private Builder(ScrubbingModeParameters scrubbingModeParameters) {
      this.disabledTrackTypes = scrubbingModeParameters.disabledTrackTypes;
      this.fractionalSeekToleranceBefore = scrubbingModeParameters.fractionalSeekToleranceBefore;
      this.fractionalSeekToleranceAfter = scrubbingModeParameters.fractionalSeekToleranceAfter;
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

  private ScrubbingModeParameters(Builder builder) {
    this.disabledTrackTypes = builder.disabledTrackTypes;
    this.fractionalSeekToleranceBefore = builder.fractionalSeekToleranceBefore;
    this.fractionalSeekToleranceAfter = builder.fractionalSeekToleranceAfter;
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
        && Objects.equals(fractionalSeekToleranceBefore, that.fractionalSeekToleranceBefore)
        && Objects.equals(fractionalSeekToleranceAfter, that.fractionalSeekToleranceAfter);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        disabledTrackTypes, fractionalSeekToleranceBefore, fractionalSeekToleranceAfter);
  }
}
