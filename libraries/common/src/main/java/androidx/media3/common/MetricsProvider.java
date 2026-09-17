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

package androidx.media3.common;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import androidx.annotation.IntDef;
import androidx.annotation.RestrictTo;
import androidx.media3.common.util.UnstableApi;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/** An internal provider of diagnostic metrics for Media3 components. */
@UnstableApi
@RestrictTo(LIBRARY_GROUP)
public interface MetricsProvider {

  /** Consumer for quantitative and categorical diagnostic metrics. */
  interface MetricConsumer {

    /**
     * Component categories for Media3 video effects and audio processors. One of:
     *
     * <ul>
     *   <li>{@link #CATEGORY_EFFECT_COLOR}
     *   <li>{@link #CATEGORY_EFFECT_SPATIAL}
     *   <li>{@link #CATEGORY_EFFECT_CONVOLUTION}
     *   <li>{@link #CATEGORY_EFFECT_OVERLAY}
     *   <li>{@link #CATEGORY_EFFECT_TEMPORAL}
     *   <li>{@link #CATEGORY_EFFECT_BUFFER}
     *   <li>{@link #CATEGORY_EFFECT_CUSTOM}
     *   <li>{@link #CATEGORY_AUDIO_SPEED_AND_PITCH}
     *   <li>{@link #CATEGORY_AUDIO_CHANNEL_MANIPULATION}
     *   <li>{@link #CATEGORY_AUDIO_TEMPORAL_TRIM}
     *   <li>{@link #CATEGORY_AUDIO_PROCESSOR_CUSTOM}
     * </ul>
     */
    @Documented
    @Retention(SOURCE)
    @Target(TYPE_USE)
    @IntDef({
      CATEGORY_EFFECT_COLOR,
      CATEGORY_EFFECT_SPATIAL,
      CATEGORY_EFFECT_CONVOLUTION,
      CATEGORY_EFFECT_OVERLAY,
      CATEGORY_EFFECT_TEMPORAL,
      CATEGORY_EFFECT_BUFFER,
      CATEGORY_EFFECT_CUSTOM,
      CATEGORY_AUDIO_SPEED_AND_PITCH,
      CATEGORY_AUDIO_CHANNEL_MANIPULATION,
      CATEGORY_AUDIO_TEMPORAL_TRIM,
      CATEGORY_AUDIO_PROCESSOR_CUSTOM,
    })
    @interface Category {}

    /** Color adjustment effect category (e.g., LUTs, RGB matrices, HSL). */
    int CATEGORY_EFFECT_COLOR = 1;

    /** Spatial transformation effect category (e.g., crop, scale, rotate, resample). */
    int CATEGORY_EFFECT_SPATIAL = 2;

    /** Convolution filter effect category (e.g., Gaussian blur, separable convolution). */
    int CATEGORY_EFFECT_CONVOLUTION = 3;

    /** Overlay compositing effect category. */
    int CATEGORY_EFFECT_OVERLAY = 4;

    /** Temporal frame manipulation effect category (e.g., frame drop). */
    int CATEGORY_EFFECT_TEMPORAL = 5;

    /** Frame caching or byte-buffer processing effect category. */
    int CATEGORY_EFFECT_BUFFER = 6;

    /** Custom or uncategorized video effect category. */
    int CATEGORY_EFFECT_CUSTOM = 7;

    /** Audio speed or pitch modification processor category. */
    int CATEGORY_AUDIO_SPEED_AND_PITCH = 8;

    /** Audio channel mixing, mapping, or gain modification processor category. */
    int CATEGORY_AUDIO_CHANNEL_MANIPULATION = 9;

    /** Audio temporal trimming or silence skipping processor category. */
    int CATEGORY_AUDIO_TEMPORAL_TRIM = 10;

    /** Custom or uncategorized audio processor category. */
    int CATEGORY_AUDIO_PROCESSOR_CUSTOM = 11;

    /**
     * Quantitative metric keys for Media3 video effects and audio processors. One of:
     *
     * <ul>
     *   <li>{@link #METRIC_SPEED_MULTIPLIER}
     *   <li>{@link #METRIC_ROTATION_DEGREES}
     *   <li>{@link #METRIC_FILTER_SAMPLE_COUNT}
     *   <li>{@link #METRIC_OVERLAY_COUNT}
     * </ul>
     */
    @Documented
    @Retention(SOURCE)
    @Target(TYPE_USE)
    @IntDef({
      METRIC_SPEED_MULTIPLIER,
      METRIC_ROTATION_DEGREES,
      METRIC_FILTER_SAMPLE_COUNT,
      METRIC_OVERLAY_COUNT,
    })
    @interface MetricKey {}

    /**
     * Audio speed multiplier scaled by 10,000 (e.g., {@code 1.5x} speed is reported as {@code
     * 15000}).
     */
    int METRIC_SPEED_MULTIPLIER = 1;

    /** Counter-clockwise rotation angle in integer degrees. */
    int METRIC_ROTATION_DEGREES = 2;

    /** Convolution filter sample count (tap count). */
    int METRIC_FILTER_SAMPLE_COUNT = 3;

    /** Number of overlays applied in an overlay effect. */
    int METRIC_OVERLAY_COUNT = 4;

    /**
     * Sets the component category for this component.
     *
     * @param category The {@link Category}.
     */
    void setCategory(@Category int category);

    /**
     * Adds a quantitative metric key and value.
     *
     * <p>Implementations should omit quantitative metrics when parameter values represent a no-op
     * or identity state (for example, {@code speed == 1.0f} or {@code rotationDegrees == 0f}).
     *
     * @param metricKey The {@link MetricKey}.
     * @param metricValue The quantitative metric value.
     */
    void addMetric(@MetricKey int metricKey, long metricValue);
  }

  /**
   * Populates diagnostic category and quantitative metrics for this component.
   *
   * <p>Implementations should call {@link MetricConsumer#setCategory(int)} and optionally {@link
   * MetricConsumer#addMetric(int, long)} for non-identity parameter values.
   *
   * @param consumer The {@link MetricConsumer} receiving diagnostic metrics.
   */
  void populateMetrics(MetricConsumer consumer);
}
