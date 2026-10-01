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
import static androidx.media3.effect.EffectsTestUtil.createFocusedEglContextWithFallback;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createArgb8888BitmapWithSolidColor;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createGlTextureFromBitmap;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createUnpremultipliedArgb8888BitmapFromFocusedGlFramebuffer;
import static androidx.media3.test.utils.TestUtil.PSNR_THRESHOLD;
import static androidx.media3.test.utils.TestUtil.assertBitmapsAreSimilar;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.util.Pair;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Pixel tests for {@link ColorConversionShaderProgram}. */
@RunWith(TestParameterInjector.class)
@SdkSuppress(minSdkVersion = 26)
public final class ColorConversionShaderProgramPixelTest {

  private static final int WIDTH = 16;
  private static final int HEIGHT = 16;

  private static final ColorInfo HDR_PQ =
      new ColorInfo.Builder()
          .setColorSpace(C.COLOR_SPACE_BT2020)
          .setColorTransfer(C.COLOR_TRANSFER_ST2084)
          .build();

  private static final ImmutableList<ConversionTestCase> CONVERSION_TEST_CASES =
      ImmutableList.of(
          // 1.0 in the linear HDR working space is the BT.2408 203-nit diffuse white reference,
          // which BT.2408 places at the 75% HLG signal level: round(0.75 * 255) = 191.
          new ConversionTestCase(
              "hdrLinearInputAndHlgOutput_mapsDiffuseWhiteToBt2408SignalLevel",
              /* inputColor= */ Color.WHITE,
              /* inputColorInfo= */ BT2020_LINEAR,
              /* outputColorInfo= */ BT2020_HLG,
              /* expectedColor= */ Color.rgb(191, 191, 191)),
          // SDR 1.0 is anchored at 203 nits, the same as HDR 1.0, so this matches
          // hdrLinearInputAndHlgOutput_mapsDiffuseWhiteToBt2408SignalLevel.
          new ConversionTestCase(
              "sdrLinearInputAndHlgOutput_mapsReferenceWhiteToBt2408SignalLevel",
              /* inputColor= */ Color.WHITE,
              /* inputColorInfo= */ BT709_LINEAR,
              /* outputColorInfo= */ BT2020_HLG,
              /* expectedColor= */ Color.rgb(191, 191, 191)),
          // srgbEotf(1.0) = 1.0, so this matches
          // sdrLinearInputAndHlgOutput_mapsReferenceWhiteToBt2408SignalLevel.
          new ConversionTestCase(
              "srgbInputAndHlgOutput_mapsReferenceWhiteToBt2408SignalLevel",
              /* inputColor= */ Color.WHITE,
              /* inputColorInfo= */ BT709_SRGB,
              /* outputColorInfo= */ BT2020_HLG,
              /* expectedColor= */ Color.rgb(191, 191, 191)),
          // Tone mapping maps the 1,000-nit HDR peak, rather than diffuse white, to SDR peak white.
          // Diffuse white is hlgInverseOetf(0.75) of scene light, and
          // srgbOetf(linearBt2020SceneToLinearBt709Display(hlgInverseOetf(0.75))) = 0.5274, and
          // round(0.5274 * 255) = 134.
          new ConversionTestCase(
              "hdrLinearInputAndSrgbOutput_toneMapsDiffuseWhite",
              /* inputColor= */ Color.WHITE,
              /* inputColorInfo= */ BT2020_LINEAR,
              /* outputColorInfo= */ BT709_SRGB,
              /* expectedColor= */ Color.rgb(134, 134, 134)),
          // HLG 0.75 decodes to 1.0 in the linear HDR working space, so this matches
          // hdrLinearInputAndSrgbOutput_toneMapsDiffuseWhite.
          new ConversionTestCase(
              "hlgInputAndSrgbOutput_toneMapsDiffuseWhite",
              /* inputColor= */ Color.rgb(191, 191, 191),
              /* inputColorInfo= */ BT2020_HLG,
              /* outputColorInfo= */ BT709_SRGB,
              /* expectedColor= */ Color.rgb(134, 134, 134)),
          // 128 / 255 = 0.502 of linear light. srgbOetf(0.502) = 0.7366, and
          // round(0.7366 * 255) = 188. An unconverted output would be 128.
          new ConversionTestCase(
              "sdrLinearInputAndSrgbOutput_appliesSrgbOetf",
              /* inputColor= */ Color.rgb(128, 128, 128),
              /* inputColorInfo= */ BT709_LINEAR,
              /* outputColorInfo= */ BT709_SRGB,
              /* expectedColor= */ Color.rgb(188, 188, 188)),
          // An arbitrary color that is not a fixed point of any transfer function, so that a stray
          // decode or encode would change it.
          new ConversionTestCase(
              "srgbInputAndSrgbOutput_passesThrough",
              /* inputColor= */ Color.rgb(37, 128, 219),
              /* inputColorInfo= */ BT709_SRGB,
              /* outputColorInfo= */ BT709_SRGB,
              /* expectedColor= */ Color.rgb(37, 128, 219)),
          // A saturated primary, unlike the neutral colors above, is only correct if the gamut
          // matrices are applied in the right order and orientation. BT.709 red is inside the
          // BT.2020 gamut, so the conversion is not clipped.
          // hlgOetf(hlgInverseOotf(BT709_TO_BT2020 * (1, 0, 0) * hlgEotf(0.75))) =
          // (0.7087, 0.2666, 0.1299), scaled to 8 bits.
          new ConversionTestCase(
              "sdrLinearInputAndHlgOutput_convertsGamut",
              /* inputColor= */ Color.RED,
              /* inputColorInfo= */ BT709_LINEAR,
              /* outputColorInfo= */ BT2020_HLG,
              /* expectedColor= */ Color.rgb(181, 68, 33)),
          // The transfer functions guard against non-positive inputs, so black also checks that
          // those guards do not leak a NaN or a floor value into the output.
          new ConversionTestCase(
              "hdrLinearInputAndHlgOutput_preservesBlack",
              /* inputColor= */ Color.BLACK,
              /* inputColorInfo= */ BT2020_LINEAR,
              /* outputColorInfo= */ BT2020_HLG,
              /* expectedColor= */ Color.BLACK),
          // An unsupported output color transfer outputs magenta as an error color.
          new ConversionTestCase(
              "unsupportedOutputTransfer_outputsErrorColor",
              /* inputColor= */ Color.WHITE,
              /* inputColorInfo= */ BT2020_LINEAR,
              /* outputColorInfo= */ HDR_PQ,
              /* expectedColor= */ Color.MAGENTA));

  private final Context context = getApplicationContext();

  private @MonotonicNonNull EGLDisplay eglDisplay;
  private @MonotonicNonNull EGLContext eglContext;
  private @MonotonicNonNull EGLSurface placeholderEglSurface;
  private @MonotonicNonNull ColorConversionShaderProgram colorConversionShaderProgram;
  private int inputTexId;
  private int outputTexId;
  private int frameBuffer;

  @Before
  public void createGlObjects() throws Exception {
    eglDisplay = GlUtil.getDefaultEglDisplay();
    Pair<EGLContext, EGLSurface> eglContextAndSurface =
        createFocusedEglContextWithFallback(
            checkNotNull(eglDisplay), GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888);
    eglContext = eglContextAndSurface.first;
    placeholderEglSurface = eglContextAndSurface.second;

    // All four conversions output electrical values in [0, 1], so an 8-bit output is enough.
    outputTexId = GlUtil.createTexture(WIDTH, HEIGHT, /* useHighPrecisionColorComponents= */ false);
    frameBuffer = GlUtil.createFboForTexture(outputTexId);
    GlUtil.focusFramebuffer(
        checkNotNull(eglDisplay),
        checkNotNull(eglContext),
        checkNotNull(placeholderEglSurface),
        frameBuffer,
        WIDTH,
        HEIGHT);
    GlUtil.clearFocusedBuffers();
  }

  @After
  public void release() throws Exception {
    if (colorConversionShaderProgram != null) {
      colorConversionShaderProgram.release();
    }
    if (inputTexId != 0) {
      GlUtil.deleteTexture(inputTexId);
    }
    if (outputTexId != 0) {
      GlUtil.deleteFbo(frameBuffer);
      GlUtil.deleteTexture(outputTexId);
    }
    if (eglDisplay != null && placeholderEglSurface != null) {
      GlUtil.destroyEglSurface(eglDisplay, placeholderEglSurface);
    }
    if (eglDisplay != null && eglContext != null) {
      GlUtil.destroyEglContext(eglDisplay, eglContext);
    }
  }

  @Test
  public void drawFrame_outputsExpectedColor(
      @TestParameter(valuesProvider = ConversionTestCasesProvider.class)
          ConversionTestCase testCase)
      throws Exception {
    assumeTrue("Device does not support OpenGL ES 3.0", GlUtil.getContextMajorVersion() >= 3);

    Bitmap actualBitmap =
        drawFrame(testCase.inputColor, testCase.inputColorInfo, testCase.outputColorInfo);

    Bitmap expectedBitmap =
        createArgb8888BitmapWithSolidColor(WIDTH, HEIGHT, testCase.expectedColor);
    assertBitmapsAreSimilar(expectedBitmap, actualBitmap, PSNR_THRESHOLD);
  }

  /**
   * Runs the color conversion over a frame of a single solid color, and returns the output frame.
   *
   * <p>The bitmap only carries the 8-bit values. They are uploaded to the texture unchanged, and
   * the shader interprets them as {@code inputColorInfo}.
   *
   * @param inputColor The color of every pixel of the input frame, scaled to 8 bits, expressed in
   *     {@code inputColorInfo}.
   * @param inputColorInfo The working {@link ColorInfo} the input frame is in.
   * @param outputColorInfo The {@link ColorInfo} to convert to.
   * @return The output {@link Bitmap}.
   * @throws Exception If an error occurs during frame processing.
   */
  private Bitmap drawFrame(int inputColor, ColorInfo inputColorInfo, ColorInfo outputColorInfo)
      throws Exception {
    Bitmap inputBitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    inputBitmap.eraseColor(inputColor);
    inputTexId = createGlTextureFromBitmap(inputBitmap);

    colorConversionShaderProgram =
        new ColorConversionShaderProgram(context, inputColorInfo, outputColorInfo);
    Size outputSize = colorConversionShaderProgram.configure(WIDTH, HEIGHT);
    colorConversionShaderProgram.drawFrame(inputTexId, /* presentationTimeUs= */ 0);

    return createUnpremultipliedArgb8888BitmapFromFocusedGlFramebuffer(
        outputSize.getWidth(), outputSize.getHeight());
  }

  private static final class ConversionTestCase {
    private final String name;
    private final int inputColor;
    private final ColorInfo inputColorInfo;
    private final ColorInfo outputColorInfo;
    private final int expectedColor;

    private ConversionTestCase(
        String name,
        int inputColor,
        ColorInfo inputColorInfo,
        ColorInfo outputColorInfo,
        int expectedColor) {
      this.name = name;
      this.inputColor = inputColor;
      this.inputColorInfo = inputColorInfo;
      this.outputColorInfo = outputColorInfo;
      this.expectedColor = expectedColor;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  private static final class ConversionTestCasesProvider extends TestParameterValuesProvider {
    @Override
    protected ImmutableList<ConversionTestCase> provideValues(
        TestParameterValuesProvider.Context context) {
      return CONVERSION_TEST_CASES;
    }
  }
}
