/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.effect;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;
import static androidx.media3.effect.DefaultGlFrameProcessor.BT2020_HLG;
import static androidx.media3.effect.DefaultGlFrameProcessor.BT709_SRGB;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import androidx.annotation.RestrictTo;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.MetricsProvider;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;

/**
 * Converts frames from an input color space to an electrical output color space.
 *
 * <p>Applying this effect last in an effect chain sets the output color space of the video frame
 * processing pipeline.
 */
@ExperimentalApi // TODO: b/505721737 Remove once FrameProcessor is production ready.
public final class ColorConversion implements GlEffect, MetricsProvider {

  private final ColorInfo outputColorInfo;

  /**
   * Creates an instance that converts frames to {@code outputColorInfo}.
   *
   * <p>Supported outputs are BT.2020 with {@link C#COLOR_TRANSFER_HLG}, and BT.709 with {@link
   * C#COLOR_TRANSFER_SRGB}. Other properties of {@code outputColorInfo}, such as the {@linkplain
   * ColorInfo#colorRange color range}, do not affect the conversion and are propagated to the
   * output frames unchanged.
   *
   * @param outputColorInfo The {@link ColorInfo} to convert frames to.
   * @return A new instance.
   * @throws IllegalArgumentException If {@code outputColorInfo} is not supported.
   */
  public static ColorConversion create(ColorInfo outputColorInfo) {
    checkNotNull(outputColorInfo);
    checkArgument(
        hasSameColorSpaceAndTransfer(outputColorInfo, BT2020_HLG)
            || hasSameColorSpaceAndTransfer(outputColorInfo, BT709_SRGB),
        "Unsupported output color space %s with color transfer %s.",
        outputColorInfo.colorSpace,
        outputColorInfo.colorTransfer);
    return new ColorConversion(outputColorInfo);
  }

  private ColorConversion(ColorInfo outputColorInfo) {
    this.outputColorInfo = outputColorInfo;
  }

  // GlEffect implementation.

  /**
   * Not supported, because the input color space is only known once the pipeline receives frames.
   *
   * @throws UnsupportedOperationException Always. This effect is only supported by {@link
   *     DefaultGlFrameProcessor}.
   */
  @Override
  public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr) {
    throw new UnsupportedOperationException(
        "ColorConversion is only supported by DefaultGlFrameProcessor.");
  }

  /**
   * Returns a {@link GlShaderProgram} that converts frames from {@code inputColorInfo} to this
   * effect's output color space.
   *
   * @param context The {@link Context}.
   * @param inputColorInfo The {@link ColorInfo} of the frames this effect receives, which is the
   *     working color space.
   * @return The {@link GlShaderProgram}.
   * @throws VideoFrameProcessingException If an error occurs while creating the {@link
   *     GlShaderProgram}.
   */
  /* package */ GlShaderProgram toGlShaderProgram(Context context, ColorInfo inputColorInfo)
      throws VideoFrameProcessingException {
    return new ColorConversionShaderProgram(context, inputColorInfo, outputColorInfo);
  }

  /** Returns the {@link ColorInfo} this effect converts frames to. */
  /* package */ ColorInfo getOutputColorInfo() {
    return outputColorInfo;
  }

  // MetricsProvider implementation.

  @Override
  @RestrictTo(LIBRARY_GROUP)
  public void populateMetrics(MetricConsumer consumer) {
    consumer.setCategory(MetricConsumer.CATEGORY_EFFECT_COLOR);
  }

  private static boolean hasSameColorSpaceAndTransfer(ColorInfo colorInfo, ColorInfo other) {
    return colorInfo.colorSpace == other.colorSpace
        && colorInfo.colorTransfer == other.colorTransfer;
  }
}
