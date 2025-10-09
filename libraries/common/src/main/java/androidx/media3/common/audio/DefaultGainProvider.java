/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.common.audio;

import static androidx.media3.common.util.Util.durationUsToSampleCount;
import static androidx.media3.common.util.Util.sampleCountToDurationUs;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.util.Pair;
import androidx.annotation.IntRange;
import androidx.media3.common.C;
import androidx.media3.common.audio.GainProcessor.GainProvider;
import androidx.media3.common.util.UnstableApi;
import com.google.common.base.Function;
import com.google.common.collect.Range;
import com.google.common.collect.TreeRangeMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Map.Entry;

/**
 * Provides gain automation information to be applied on an audio stream.
 *
 * <p>The class allows combining multiple {@linkplain FadeProvider fade shapes} into one single
 * automation line, with common fade shapes already implemented (e.g. {@link #FADE_IN_LINEAR}).
 *
 * @see GainProcessor
 */
@UnstableApi
public final class DefaultGainProvider implements GainProvider {

  /** A builder for {@link DefaultGainProvider} instances. */
  public static final class Builder {
    private final TreeRangeMap<Long, Function<Pair<Long, Integer>, Float>> gainMap;
    private final float defaultGain;

    /**
     * Returns a {@link DefaultGainProvider} builder.
     *
     * @param defaultGain Default gain value.
     */
    public Builder(float defaultGain) {
      gainMap = TreeRangeMap.create();
      // Add default value for all possible positions.
      this.defaultGain = defaultGain;
      gainMap.put(Range.all(), (a) -> GAIN_UNSET);
    }

    /**
     * Adds a {@code shape} to be applied between [{@code positionUs}; {@code positionUs} + {@code
     * durationUs}).
     *
     * <p>This fade overwrites the shape of any previously added fade if they overlap.
     */
    @CanIgnoreReturnValue
    public Builder addFadeAt(
        @IntRange(from = 0) long positionUs,
        @IntRange(from = 1) long durationUs,
        FadeProvider shape) {
      checkArgument(positionUs >= 0);
      checkArgument(durationUs > 1);
      gainMap.put(
          Range.closedOpen(positionUs, positionUs + durationUs),
          (positionSampleRatePair) -> {
            int sampleRate = positionSampleRatePair.second;
            long relativeSamplePosition =
                positionSampleRatePair.first - durationUsToSampleCount(positionUs, sampleRate);
            return shape.getGainFactorAt(
                relativeSamplePosition, durationUsToSampleCount(durationUs, sampleRate));
          });
      return this;
    }

    /** Returns a new {@link DefaultGainProvider} instance. */
    public DefaultGainProvider build() {
      return new DefaultGainProvider(gainMap, defaultGain);
    }
  }

  /** Represents a time unit-agnostic fade shape to be applied over an automation. */
  public interface FadeProvider {

    /**
     * Returns the gain factor within [0f; 1f] to apply to an audio sample for a specific fade
     * shape.
     *
     * <p>Position and duration are unit agnostic and work as a numerator/denominator pair.
     *
     * <p>You can implement a basic linear fade as follows:
     *
     * <pre>{@code
     * @Override
     * public float getGainFactorAt(long index, long duration) {
     *   return (float) index / duration;
     * }
     * }</pre>
     *
     * @param index Position (numerator) between [0; {@code duration}].
     * @param duration Duration (denominator).
     */
    float getGainFactorAt(@IntRange(from = 0) long index, @IntRange(from = 1) long duration);
  }

  /**
   * Equal gain fade in.
   *
   * <p>Ramps linearly from 0 to 1.
   *
   * <p>Summing this with {@link #FADE_OUT_LINEAR} returns a constant gain of 1 for all valid
   * indexes.
   */
  public static final FadeProvider FADE_IN_LINEAR = (index, duration) -> (float) index / duration;

  /**
   * Equal gain fade out.
   *
   * <p>Ramps linearly from 1 to 0.
   *
   * <p>Summing this with {@link #FADE_IN_LINEAR} returns a constant gain of 1 for all valid
   * indexes.
   */
  public static final FadeProvider FADE_OUT_LINEAR =
      (index, duration) -> (float) (duration - index) / duration;

  /**
   * Equal power fade in.
   *
   * <p>Ramps from 0 to 1 using an equal power curve.
   *
   * <p>Summing this with {@link #FADE_OUT_EQUAL_POWER} returns a constant power of 1 for all valid
   * indexes.
   */
  public static final FadeProvider FADE_IN_EQUAL_POWER =
      (index, duration) -> (float) Math.sin((Math.PI / 2.0) * index / duration);

  /**
   * Equal power fade out.
   *
   * <p>Ramps from 1 to 0 using an equal power curve.
   *
   * <p>Summing this with {@link #FADE_IN_EQUAL_POWER} returns a constant power of 1 for all valid
   * indexes.
   */
  public static final FadeProvider FADE_OUT_EQUAL_POWER =
      (index, duration) -> (float) Math.cos((Math.PI / 2.0) * index / duration);

  private static final float GAIN_UNSET = C.RATE_UNSET;

  /**
   * {@link RangeMap} for representing a sequence of fades applied at specific time ranges over a
   * default gain value.
   *
   * <p>Keys correspond to the position range in microseconds. Entry values correspond to a generic
   * {@link Function} that returns a gain value based on a sample position and sample rate.
   */
  // Use TreeRangeMap instead of ImmutableRangeMap to allow overlapping ranges.
  private final TreeRangeMap<Long, Function<Pair<Long, Integer>, Float>> gainMap;

  private final float defaultGain;

  private DefaultGainProvider(
      TreeRangeMap<Long, Function<Pair<Long, Integer>, Float>> gainMap, float defaultGain) {
    this.gainMap = TreeRangeMap.create();
    this.gainMap.putAll(gainMap);
    this.defaultGain = defaultGain;
  }

  @Override
  public float getGainFactorAtSamplePosition(
      @IntRange(from = 0) long samplePosition, @IntRange(from = 1) int sampleRate) {
    checkState(sampleRate > 0);
    checkArgument(samplePosition >= 0);

    // gainMap has a default value set for all possible values, so it should never return null.
    float gain =
        checkNotNull(gainMap.get(sampleCountToDurationUs(samplePosition, sampleRate)))
            .apply(Pair.create(samplePosition, sampleRate));
    if (gain == GAIN_UNSET) {
      return defaultGain;
    }
    return gain;
  }

  @Override
  // TODO (b/400418589): Add support for non-default value unity ranges.
  public long isUnityUntil(
      @IntRange(from = 0) long samplePosition, @IntRange(from = 1) int sampleRate) {
    checkState(sampleRate > 0);
    checkArgument(samplePosition >= 0);

    long positionUs = sampleCountToDurationUs(samplePosition, sampleRate);
    Entry<Range<Long>, Function<Pair<Long, Integer>, Float>> entry =
        checkNotNull(gainMap.getEntry(positionUs));
    float gainFactor = entry.getValue().apply(Pair.create(samplePosition, sampleRate));

    // If the gain has been manually set to unity, we do not know how long the unity region is.
    if (gainFactor == 1f) {
      return samplePosition + 1;
    }

    if (defaultGain != 1f || gainFactor != GAIN_UNSET) {
      return C.TIME_UNSET;
    }

    if (!entry.getKey().hasUpperBound()) {
      return C.TIME_END_OF_SOURCE;
    }

    return durationUsToSampleCount(entry.getKey().upperEndpoint(), sampleRate);
  }
}
