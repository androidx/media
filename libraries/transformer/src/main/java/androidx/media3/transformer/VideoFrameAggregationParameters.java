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
package androidx.media3.transformer;

import static com.google.common.base.Preconditions.checkArgument;

import android.util.Rational;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.Log;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Parameters for video frame aggregation.
 *
 * <p>These parameters control how video frames from multiple sequences are aligned, dropped, or
 * duplicated before entering the processing pipeline.
 */
@ExperimentalApi // TODO: b/526781983 - Remove @ExperimentalApi.
@RestrictTo(Scope.LIBRARY_GROUP) // TODO: b/503214887 - Remove once playback flow is supported.
public final class VideoFrameAggregationParameters {

  /** A builder for {@link VideoFrameAggregationParameters} instances. */
  public static final class Builder {

    @Nullable private Rational frameRate;

    /** Creates a new instance. */
    public Builder() {}

    private Builder(VideoFrameAggregationParameters parameters) {
      this.frameRate = parameters.frameRate;
    }

    /**
     * Sets the target frame rate for aggregating video frames.
     *
     * <p>If set, the aggregation process will drop or reuse frames (with appropriate retiming) to
     * match this rate. This allows the pipeline to guarantee a single, constant frame rate before
     * frames enter the processing layer. For example, if the target frame rate is 30 FPS, a 24 FPS
     * video will have some frames reused, and a 60 FPS video will have some frames dropped to meet
     * the target.
     *
     * <p>If {@code null}, the aggregation follows the primary sequence's physical timestamps. The
     * default is {@code null}.
     *
     * @param frameRate The target frame rate, in frames per second. Must be strictly positive or
     *     {@code null}.
     * @return This builder.
     * @throws IllegalArgumentException If {@code frameRate} is not strictly positive and not {@code
     *     null}.
     */
    @CanIgnoreReturnValue
    public Builder setFrameRate(@Nullable Rational frameRate) {
      checkArgument(
          frameRate == null || (frameRate.getNumerator() > 0 && frameRate.getDenominator() > 0),
          "frameRate must be strictly positive or null");
      this.frameRate = frameRate;
      return this;
    }

    /** Builds a {@link VideoFrameAggregationParameters} instance. */
    public VideoFrameAggregationParameters build() {
      return new VideoFrameAggregationParameters(this);
    }
  }

  /** An instance with default parameters. */
  public static final VideoFrameAggregationParameters DEFAULT = new Builder().build();

  /** The target frame rate, in frames per second, or {@code null} if no target rate is set. */
  @Nullable public final Rational frameRate;

  private VideoFrameAggregationParameters(Builder builder) {
    this.frameRate = builder.frameRate;
  }

  /** Returns a new {@link Builder} initialized with the values of this instance. */
  public Builder buildUpon() {
    return new Builder(this);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof VideoFrameAggregationParameters)) {
      return false;
    }
    VideoFrameAggregationParameters that = (VideoFrameAggregationParameters) o;
    return Objects.equals(frameRate, that.frameRate);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(frameRate);
  }

  @Override
  public String toString() {
    return toJsonObject().toString();
  }

  /** Returns a {@link JSONObject} that represents the {@code VideoFrameAggregationParameters}. */
  /* package */ JSONObject toJsonObject() {
    JSONObject jsonObject = new JSONObject();
    try {
      if (frameRate != null) {
        jsonObject.put("frameRate", frameRate.getNumerator() + "/" + frameRate.getDenominator());
      }
    } catch (JSONException e) {
      Log.w(/* tag= */ "VideoFrameAggParams", "JSON conversion failed.", e);
      return new JSONObject();
    }
    return jsonObject;
  }
}
