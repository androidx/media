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

import static android.os.Build.VERSION.SDK_INT;
import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Math.round;

import android.os.Build;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.primitives.Ints;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Objects;

/**
 * Repository of chipset-specific recommendations and/or optimizations for using Android platform
 * provided {@linkplain android.media.MediaCodec video codecs}.
 *
 * <p>Recommendations made by this class are based on Google-collected benchmark data collected on
 * production devices leveraging the chipsets represented.
 */
@UnstableApi
public final class CodecDbLite {

  /**
   * Data backing video encoding recommendations made by CodecDB Lite.
   *
   * <p>The data is stored in a {@link ImmutableListMultimap} where the keys represent chipsets. The
   * entries for one chipset are sorted in descending order of compression-efficiency.
   */
  private static final ImmutableListMultimap<Chipset, VideoEncoderEntry> ENCODER_DATASET =
      ImmutableListMultimap.<Chipset, VideoEncoderEntry>builder()
          .put(
              new Chipset("Google", "Tensor G2"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 37538929,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Google", "Tensor G2"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 32739600,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Google", "Tensor G3"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 37538350,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Google", "Tensor G3"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 32750593,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Google", "Tensor G4"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_AV1,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 32844500,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Google", "Tensor G4"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 51851802,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Google", "Tensor G4"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 44206216,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Mediatek", "MT6761"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Mediatek", "MT6762"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Mediatek", "MT6765"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Mediatek", "MT6769T"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Mediatek", "MT6769T"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Mediatek", "MT6769Z"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Mediatek", "MT6769Z"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Mediatek", "MT6785"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Mediatek", "MT6785"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Mediatek", "MT6789V/CD"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Mediatek", "MT6789V/CD"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Mediatek", "MT6833V/NZA"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Mediatek", "MT6833V/NZA"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Mediatek", "MT6893"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 34028841,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Mediatek", "MT6893"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 457499715,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Mediatek", "MT6983"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 36134374,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Mediatek", "MT6983"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 189533581,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("QTI", "SDM450"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("QTI", "SM4350"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("QTI", "SM4350"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("QTI", "SM6125"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("QTI", "SM6125"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("QTI", "SM6225"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("QTI", "SM6225"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("QTI", "SM6375"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("QTI", "SM6375"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("QTI", "SM8250"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("QTI", "SM8250"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("QTI", "SM8350"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("QTI", "SM8350"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("QTI", "SM8450"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 497664000,
                  VideoEncoderEntry.FormatOptimization
                      .FORMAT_OPTIMIZATION_SET_TEMPORAL_LAYERING_FOR_B_FRAMES))
          .put(
              new Chipset("QTI", "SM8450"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 497664000,
                  VideoEncoderEntry.FormatOptimization
                      .FORMAT_OPTIMIZATION_SET_TEMPORAL_LAYERING_FOR_B_FRAMES))
          .put(
              new Chipset("QTI", "SM8475"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 497664000,
                  VideoEncoderEntry.FormatOptimization
                      .FORMAT_OPTIMIZATION_SET_TEMPORAL_LAYERING_FOR_B_FRAMES))
          .put(
              new Chipset("QTI", "SM8475"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 497664000,
                  VideoEncoderEntry.FormatOptimization
                      .FORMAT_OPTIMIZATION_SET_TEMPORAL_LAYERING_FOR_B_FRAMES))
          .put(
              new Chipset("QTI", "SM8550"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 497664000,
                  VideoEncoderEntry.FormatOptimization
                      .FORMAT_OPTIMIZATION_SET_TEMPORAL_LAYERING_FOR_B_FRAMES))
          .put(
              new Chipset("QTI", "SM8550"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 110196681,
                  VideoEncoderEntry.FormatOptimization
                      .FORMAT_OPTIMIZATION_SET_TEMPORAL_LAYERING_FOR_B_FRAMES))
          .put(
              new Chipset("QTI", "SM8650"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 34344411,
                  VideoEncoderEntry.FormatOptimization
                      .FORMAT_OPTIMIZATION_SET_TEMPORAL_LAYERING_FOR_B_FRAMES))
          .put(
              new Chipset("QTI", "SM8650"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 132451733,
                  VideoEncoderEntry.FormatOptimization
                      .FORMAT_OPTIMIZATION_SET_TEMPORAL_LAYERING_FOR_B_FRAMES))
          .put(
              new Chipset("QTI", "SM8750"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 52435727,
                  VideoEncoderEntry.FormatOptimization
                      .FORMAT_OPTIMIZATION_SET_TEMPORAL_LAYERING_FOR_B_FRAMES))
          .put(
              new Chipset("QTI", "SM8750"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 159007069,
                  VideoEncoderEntry.FormatOptimization
                      .FORMAT_OPTIMIZATION_SET_TEMPORAL_LAYERING_FOR_B_FRAMES))
          .put(
              new Chipset("Samsung", "Exynos 850"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Samsung", "Exynos 850"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Samsung", "s5e8825"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Samsung", "s5e8825"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Samsung", "s5e9925"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 1,
                  /* bFrameResolutionCutoff= */ 51506898,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Samsung", "s5e9925"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 2,
                  /* bFrameResolutionCutoff= */ 40856748,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Spreadtrum", "SC9863A"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Spreadtrum", "SC9863A"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Spreadtrum", "T606"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H264,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .put(
              new Chipset("Spreadtrum", "T606"),
              new VideoEncoderEntry(
                  MimeTypes.VIDEO_H265,
                  /* maxBFrames= */ 0,
                  /* bFrameResolutionCutoff= */ 0,
                  VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE))
          .build();

  private static final VideoEncoderEntry ENCODER_DEFAULT =
      new VideoEncoderEntry(
          MimeTypes.VIDEO_H264,
          /* maxBFrames= */ 0,
          /* bFrameResolutionCutoff= */ 0,
          /* formatOptimizations= */ VideoEncoderEntry.FormatOptimization.FORMAT_OPTIMIZATION_NONE);

  private CodecDbLite() {}

  /** Returns the MIME type recommended for video encoding on the runtime device. */
  public static String getRecommendedVideoMimeType() {
    Chipset chipset = Chipset.current();
    if (chipset.equals(Chipset.UNKNOWN)) {
      return ENCODER_DEFAULT.mimeType;
    }

    if (!ENCODER_DATASET.containsKey(chipset)) {
      return ENCODER_DEFAULT.mimeType;
    }

    return ENCODER_DATASET.get(chipset).get(0).mimeType;
  }

  /**
   * Returns the recommended {@link VideoEncoderSettings} for the provided format.
   *
   * @param format the video format to recommend settings for which must include {@link
   *     Format#sampleMimeType} to identify the codec. For best results, {@link Format#width},
   *     {@link Format#height}, {@link Format#frameRate} should be set since some optimizations are
   *     resolution dependent.
   */
  public static VideoEncoderSettings getRecommendedVideoEncoderSettings(Format format) {
    checkArgument(MimeTypes.isVideo(format.sampleMimeType), "MIME must be a video MIME type.");

    Chipset chipset = Chipset.current();
    if (!ENCODER_DATASET.containsKey(chipset)) {
      return VideoEncoderSettings.DEFAULT;
    }

    VideoEncoderSettings.Builder settingsBuilder = new VideoEncoderSettings.Builder();

    @Nullable VideoEncoderEntry entryForCodec = null;
    ImmutableList<VideoEncoderEntry> chipsetEntries = ENCODER_DATASET.get(chipset);
    for (int i = 0; i < chipsetEntries.size(); i++) {
      if (chipsetEntries.get(i).mimeType.equals(format.sampleMimeType)) {
        entryForCodec = chipsetEntries.get(i);
        break;
      }
    }
    if (entryForCodec == null) {
      return settingsBuilder.build();
    }

    int pixelsPerSecond = Integer.MAX_VALUE;
    if (format.getPixelCount() != Format.NO_VALUE && format.frameRate != Format.NO_VALUE) {
      pixelsPerSecond = Ints.saturatedCast(round(format.getPixelCount() * format.frameRate));
    }

    if (pixelsPerSecond < entryForCodec.bFrameResolutionCutoff) {
      settingsBuilder.setMaxBFrames(entryForCodec.maxBFrames);
      if ((entryForCodec.formatOptimizations
              & VideoEncoderEntry.FormatOptimization
                  .FORMAT_OPTIMIZATION_SET_TEMPORAL_LAYERING_FOR_B_FRAMES)
          != 0) {
        // Workaround to enable B-Frame encoding on certain QTI chipsets, which corresponds to a
        // temporal-schema of android.generic.1+2 and must be set in addition to KEY_MAX_B_FRAMES.
        settingsBuilder.setTemporalLayers(
            /* numNonBidirectionalLayers= */ 1, /* numBidirectionalLayers= */ 2);
      }
    }

    return settingsBuilder.build();
  }

  /** Dataclass for chipset identifiers. */
  private static final class Chipset {

    private static final Chipset UNKNOWN = new Chipset("", "");
    private final String manufacturer;
    private final String model;

    public Chipset(String manufacturer, String model) {
      this.manufacturer = manufacturer;
      this.model = model;
    }

    public static Chipset current() {
      if (SDK_INT >= 31) {
        return new Chipset(Build.SOC_MANUFACTURER, Build.SOC_MODEL);
      }

      return Chipset.UNKNOWN;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (!(o instanceof Chipset)) {
        return false;
      }

      Chipset that = (Chipset) o;
      return Objects.equals(manufacturer, that.manufacturer) && Objects.equals(model, that.model);
    }

    @Override
    public int hashCode() {
      return Objects.hash(manufacturer, model);
    }

    @Override
    public String toString() {
      return String.format("Chipset(%s %s)", manufacturer, model);
    }
  }

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
