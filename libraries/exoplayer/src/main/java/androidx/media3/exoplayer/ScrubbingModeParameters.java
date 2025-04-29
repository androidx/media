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

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.C.TrackType;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
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

    /** Creates an instance. */
    public Builder() {
      this.disabledTrackTypes = ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_METADATA);
    }

    private Builder(ScrubbingModeParameters scrubbingModeParameters) {
      this.disabledTrackTypes = scrubbingModeParameters.disabledTrackTypes;
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

    /** Returns the built {@link ScrubbingModeParameters}. */
    public ScrubbingModeParameters build() {
      return new ScrubbingModeParameters(this);
    }
  }

  /** Which track types will be disabled in scrubbing mode. */
  public final ImmutableSet<@TrackType Integer> disabledTrackTypes;

  private ScrubbingModeParameters(Builder builder) {
    this.disabledTrackTypes = builder.disabledTrackTypes;
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
    return disabledTrackTypes.equals(that.disabledTrackTypes);
  }

  @Override
  public int hashCode() {
    return disabledTrackTypes.hashCode();
  }
}
