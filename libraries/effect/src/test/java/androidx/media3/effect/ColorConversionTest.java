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

import static androidx.media3.effect.DefaultGlFrameProcessor.BT2020_HLG;
import static androidx.media3.effect.DefaultGlFrameProcessor.BT2020_LINEAR;
import static androidx.media3.effect.DefaultGlFrameProcessor.BT709_LINEAR;
import static androidx.media3.effect.DefaultGlFrameProcessor.BT709_SRGB;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.MetricsProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link ColorConversion}. */
@RunWith(AndroidJUnit4.class)
public final class ColorConversionTest {

  private final Context context = getApplicationContext();

  @Test
  public void create_withHdrHlg_setsOutputColorInfo() {
    ColorConversion colorConversion = ColorConversion.create(BT2020_HLG);

    assertThat(colorConversion.getOutputColorInfo()).isEqualTo(BT2020_HLG);
  }

  @Test
  public void create_withSdrBt709Limited_throwsIllegalArgumentException() {
    // COLOR_TRANSFER_SDR is not accepted, so that there is one way to request a BT.709 output.
    assertThrows(
        IllegalArgumentException.class, () -> ColorConversion.create(ColorInfo.SDR_BT709_LIMITED));
  }

  @Test
  public void create_withSdrSrgb_setsOutputColorInfo() {
    ColorConversion colorConversion = ColorConversion.create(BT709_SRGB);

    assertThat(colorConversion.getOutputColorInfo()).isEqualTo(BT709_SRGB);
  }

  @Test
  public void create_withSrgbBt709Full_setsOutputColorInfo() {
    // The color range does not affect the conversion, so a full range variant of a supported color
    // space is accepted and propagated unchanged.
    ColorConversion colorConversion = ColorConversion.create(ColorInfo.SRGB_BT709_FULL);

    assertThat(colorConversion.getOutputColorInfo()).isEqualTo(ColorInfo.SRGB_BT709_FULL);
  }

  @Test
  public void create_withHdrHlgLimitedRange_setsOutputColorInfo() {
    ColorInfo hlgLimitedRange = BT2020_HLG.buildUpon().setColorRange(C.COLOR_RANGE_LIMITED).build();

    ColorConversion colorConversion = ColorConversion.create(hlgLimitedRange);

    assertThat(colorConversion.getOutputColorInfo()).isEqualTo(hlgLimitedRange);
  }

  @Test
  public void create_withHdrPq_throwsIllegalArgumentException() {
    ColorInfo hdrPq =
        new ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorTransfer(C.COLOR_TRANSFER_ST2084)
            .build();

    assertThrows(IllegalArgumentException.class, () -> ColorConversion.create(hdrPq));
  }

  @Test
  public void create_withHdrLinear_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class, () -> ColorConversion.create(BT2020_LINEAR));
  }

  @Test
  public void create_withSdrLinear_throwsIllegalArgumentException() {
    assertThrows(IllegalArgumentException.class, () -> ColorConversion.create(BT709_LINEAR));
  }

  @Test
  public void create_withHlgTransferAndBt709Gamut_throwsIllegalArgumentException() {
    // The shader derives the output gamut from the output transfer, so a BT.709 HLG output would be
    // mislabelled rather than converted.
    ColorInfo bt709Hlg =
        new ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT709)
            .setColorTransfer(C.COLOR_TRANSFER_HLG)
            .build();

    assertThrows(IllegalArgumentException.class, () -> ColorConversion.create(bt709Hlg));
  }

  @Test
  public void create_withSdrTransferAndBt2020Gamut_throwsIllegalArgumentException() {
    ColorInfo bt2020Sdr =
        new ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorTransfer(C.COLOR_TRANSFER_SDR)
            .build();

    assertThrows(IllegalArgumentException.class, () -> ColorConversion.create(bt2020Sdr));
  }

  @Test
  public void create_withUnsetColorTransfer_throwsIllegalArgumentException() {
    ColorInfo unsetTransfer = new ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT709).build();

    assertThrows(IllegalArgumentException.class, () -> ColorConversion.create(unsetTransfer));
  }

  @Test
  public void create_withNullOutputColorInfo_throwsNullPointerException() {
    assertThrows(NullPointerException.class, () -> ColorConversion.create(null));
  }

  @Test
  public void toGlShaderProgram_withUseHdr_throwsUnsupportedOperationException() {
    ColorConversion colorConversion = ColorConversion.create(BT2020_HLG);

    assertThrows(
        UnsupportedOperationException.class,
        () -> colorConversion.toGlShaderProgram(context, /* useHdr= */ true));
  }

  @Test
  public void populateMetrics_withConsumer_setsColorEffectCategory() {
    ColorConversion colorConversion = ColorConversion.create(BT2020_HLG);
    AtomicInteger categoryRef = new AtomicInteger();

    colorConversion.populateMetrics(
        new MetricsProvider.MetricConsumer() {
          @Override
          public void setCategory(int category) {
            categoryRef.set(category);
          }

          @Override
          public void addMetric(int metricKey, long metricValue) {}
        });

    assertThat(categoryRef.get()).isEqualTo(MetricsProvider.MetricConsumer.CATEGORY_EFFECT_COLOR);
  }
}
