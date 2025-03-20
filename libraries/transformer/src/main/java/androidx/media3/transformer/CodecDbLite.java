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
package androidx.media3.transformer;

import androidx.annotation.IntDef;
import androidx.media3.common.util.UnstableApi;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Repository of chipset-specific recommendations and/or optimizations for using Android platform
 * provided {@linkplain android.media.MediaCodec video codecs}.
 *
 * <p>Recommendations made by this class are based on Google-collected benchmark data collected on
 * production devices leveraging the chipsets represented.
 */
@UnstableApi
public final class CodecDbLite {

  private CodecDbLite() {}

  /**
   * Internal entry in CodecDB Lite which represents a single codec and the recommended B-frame
   * configuration for that codec.
   */
  private static final class VideoEncoderEntry {

    /**
     * The MIME type of the recommended video encoder to use.
     *
     * <p>This value can be passed into Transformer via {@link
     * Transformer.Builder#setVideoMimeType(String)}.
     */
    private final String mimeType;

    /**
     * The recommended value for {@link android.media.MediaFormat#KEY_MAX_B_FRAMES}, where any value
     * greater than 0 indicates that B-Frames should be used.
     *
     * <p>If a non-zero value is recommended, enabling B-frames will generally result in a gain in
     * user-perceived video quality versus not enabling them.
     */
    private final int maxBFrames;

    /**
     * A composite spatial resolution (width x height) indicating where B-frames should be disabled.
     *
     * <p>In general, B-frame encoding for hardware-encoders in the Android ecosystem tend to
     * perform better at lower-resolutions. This resolution indicates a cutoff above which B-frames
     * should not be enabled.
     */
    private final int bFrameResolutionCutoff;

    /**
     * Flags that indicate other optimizations that should be performed on the video encoder
     * specified by this recommendation.
     */
    private final @VideoEncoderEntry.FormatOptimization int formatOptimizations;

    private VideoEncoderEntry(
        String mimeType,
        int maxBFrames,
        int bFrameResolutionCutoff,
        @VideoEncoderEntry.FormatOptimization int formatOptimizations) {
      this.mimeType = mimeType;
      this.maxBFrames = maxBFrames;
      this.bFrameResolutionCutoff = bFrameResolutionCutoff;
      this.formatOptimizations = formatOptimizations;
    }

    /**
     * Flags that indicate a specific optimization should be made to the {@link
     * android.media.MediaFormat} used to configure a specific video encoder.
     */
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
    @IntDef(
        flag = true,
        value = {
          VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE,
          VideoEncoderEntry.FormatOptimization
              .FORMAT_OPTIMIZATION_SET_TEMPORAL_LAYERING_FOR_B_FRAMES
        })
    private @interface FormatOptimization {
      /** Flag indicating that no additional optimizations need to be made to the encoder. */
      int FORMAT_OPTIMIZATION_NONE = 0;

      /**
       * Flag indicating that to enable B-frames on a given device, that {@link
       * android.media.MediaFormat#KEY_TEMPORAL_LAYERING} must also be set.
       */
      int FORMAT_OPTIMIZATION_SET_TEMPORAL_LAYERING_FOR_B_FRAMES = 1;
    }
  }
}
