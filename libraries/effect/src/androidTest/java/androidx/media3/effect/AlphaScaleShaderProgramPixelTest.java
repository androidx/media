/*
 * Copyright 2023 The Android Open Source Project
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

import static androidx.media3.effect.EffectsTestUtil.createFocusedEglContextWithFallback;
import static androidx.media3.test.utils.BitmapPixelTestUtil.MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE;
import static androidx.media3.test.utils.BitmapPixelTestUtil.MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE_DIFFERENT_DEVICE_FP16;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createFp16BitmapFromFocusedGlFramebuffer;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createGlTextureFromBitmap;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createUnpremultipliedArgb8888BitmapFromFocusedGlFramebuffer;
import static androidx.media3.test.utils.BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888;
import static androidx.media3.test.utils.BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceFp16;
import static androidx.media3.test.utils.BitmapPixelTestUtil.maybeSaveTestBitmap;
import static androidx.media3.test.utils.BitmapPixelTestUtil.readBitmapUnpremultipliedAlpha;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.util.Pair;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.test.utils.BitmapPixelTestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import java.io.IOException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/**
 * Pixel tests for {@link AlphaScale}.
 *
 * <p>Expected images are taken from an emulator, so tests on different emulators or physical
 * devices may fail. To test on other devices, please increase the {@link
 * BitmapPixelTestUtil#MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE} and/or inspect the saved output
 * bitmaps as recommended in {@link DefaultVideoFrameProcessorPixelTest}.
 */
@RunWith(AndroidJUnit4.class)
public final class AlphaScaleShaderProgramPixelTest {
  @Rule public final TestName testName = new TestName();
  // TODO: b/262694346 - Use media3test_srgb instead of media3test, throughout our tests. This is
  //  this test image is intended to be interpreted as sRGB, but media3test is stored in a niche
  //  color transfer, which can make alpha tests more difficult to debug.
  private static final String ORIGINAL_PNG_ASSET_PATH = "media/png/media3test_srgb.png";
  private static final String DECREASE_ALPHA_PNG_ASSET_PATH =
      "test-generated-goldens/sample_mp4_first_frame/electrical_colors/decrease_alpha.png";
  private static final String INCREASE_ALPHA_PNG_ASSET_PATH =
      "test-generated-goldens/sample_mp4_first_frame/electrical_colors/increase_alpha.png";
  private static final String ZERO_ALPHA_PNG_ASSET_PATH =
      "test-generated-goldens/sample_mp4_first_frame/electrical_colors/zero_alpha.png";
  private static final String ORIGINAL_HLG10_PNG_ASSET_PATH =
      "test-generated-goldens/sample_mp4_first_frame/electrical_colors/original_hlg10.png";

  private final Context context = getApplicationContext();

  private String testId;
  private @MonotonicNonNull EGLDisplay eglDisplay;
  private @MonotonicNonNull EGLContext eglContext;
  private @MonotonicNonNull AlphaScaleShaderProgram alphaScaleShaderProgram;
  private @MonotonicNonNull EGLSurface placeholderEglSurface;
  private int inputTexId;
  private int inputWidth;
  private int inputHeight;

  @Before
  public void createGlObjects() throws IOException, GlUtil.GlException {
    eglDisplay = GlUtil.getDefaultEglDisplay();
    Pair<EGLContext, EGLSurface> eglContextAndSurface =
        createFocusedEglContextWithFallback(
            checkNotNull(eglDisplay), GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888);
    eglContext = eglContextAndSurface.first;
    placeholderEglSurface = eglContextAndSurface.second;

    Bitmap inputBitmap = readBitmapUnpremultipliedAlpha(ORIGINAL_PNG_ASSET_PATH);
    inputWidth = inputBitmap.getWidth();
    inputHeight = inputBitmap.getHeight();
    inputTexId = createGlTextureFromBitmap(inputBitmap);

    int outputTexId =
        GlUtil.createTexture(inputWidth, inputHeight, /* useHighPrecisionColorComponents= */ false);
    int frameBuffer = GlUtil.createFboForTexture(outputTexId);
    GlUtil.focusFramebuffer(
        checkNotNull(eglDisplay),
        checkNotNull(eglContext),
        checkNotNull(placeholderEglSurface),
        frameBuffer,
        inputWidth,
        inputHeight);
    GlUtil.clearFocusedBuffers();
  }

  @Before
  public void setUpTestId() {
    testId = testName.getMethodName();
  }

  @After
  public void release() throws GlUtil.GlException, VideoFrameProcessingException {
    if (alphaScaleShaderProgram != null) {
      alphaScaleShaderProgram.release();
    }
    if (eglDisplay != null && placeholderEglSurface != null) {
      GlUtil.destroyEglSurface(eglDisplay, placeholderEglSurface);
    }
    if (eglDisplay != null && eglContext != null) {
      GlUtil.destroyEglContext(eglDisplay, eglContext);
    }
  }

  @Test
  public void noOpAlpha_matchesGoldenFile() throws Exception {
    alphaScaleShaderProgram =
        (AlphaScaleShaderProgram)
            new AlphaScale(1.0f).toGlShaderProgram(context, /* useHdr= */ false);
    Size outputSize = alphaScaleShaderProgram.configure(inputWidth, inputHeight);
    Bitmap expectedBitmap = readBitmapUnpremultipliedAlpha(ORIGINAL_PNG_ASSET_PATH);
    maybeSaveTestBitmap(testId, /* bitmapLabel= */ "input", expectedBitmap, /* path= */ null);

    alphaScaleShaderProgram.drawFrame(inputTexId, /* presentationTimeUs= */ 0);
    Bitmap actualBitmap =
        createUnpremultipliedArgb8888BitmapFromFocusedGlFramebuffer(
            outputSize.getWidth(), outputSize.getHeight());

    // TODO(b/207848601): Switch to using proper tooling for testing against golden data.
    maybeSaveTestBitmap(testId, /* bitmapLabel= */ "actual", actualBitmap, /* path= */ null);
    float averagePixelAbsoluteDifference =
        getBitmapAveragePixelAbsoluteDifferenceArgb8888(expectedBitmap, actualBitmap, testId);
    assertThat(averagePixelAbsoluteDifference).isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  public void zeroAlpha_matchesGoldenFile() throws Exception {
    alphaScaleShaderProgram =
        (AlphaScaleShaderProgram)
            new AlphaScale(0.0f).toGlShaderProgram(context, /* useHdr= */ false);
    Size outputSize = alphaScaleShaderProgram.configure(inputWidth, inputHeight);
    Bitmap expectedBitmap = readBitmapUnpremultipliedAlpha(ZERO_ALPHA_PNG_ASSET_PATH);

    alphaScaleShaderProgram.drawFrame(inputTexId, /* presentationTimeUs= */ 0);
    Bitmap actualBitmap =
        createUnpremultipliedArgb8888BitmapFromFocusedGlFramebuffer(
            outputSize.getWidth(), outputSize.getHeight());

    // TODO(b/207848601): Switch to using proper tooling for testing against golden data.
    maybeSaveTestBitmap(testId, /* bitmapLabel= */ "actual", actualBitmap, /* path= */ null);
    float averagePixelAbsoluteDifference =
        getBitmapAveragePixelAbsoluteDifferenceArgb8888(expectedBitmap, actualBitmap, testId);
    assertThat(averagePixelAbsoluteDifference).isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  public void decreaseAlpha_matchesGoldenFile() throws Exception {
    alphaScaleShaderProgram =
        (AlphaScaleShaderProgram)
            new AlphaScale(0.5f).toGlShaderProgram(context, /* useHdr= */ false);
    Size outputSize = alphaScaleShaderProgram.configure(inputWidth, inputHeight);
    Bitmap expectedBitmap = readBitmapUnpremultipliedAlpha(DECREASE_ALPHA_PNG_ASSET_PATH);

    alphaScaleShaderProgram.drawFrame(inputTexId, /* presentationTimeUs= */ 0);
    Bitmap actualBitmap =
        createUnpremultipliedArgb8888BitmapFromFocusedGlFramebuffer(
            outputSize.getWidth(), outputSize.getHeight());

    // TODO(b/207848601): Switch to using proper tooling for testing against golden data.
    maybeSaveTestBitmap(testId, /* bitmapLabel= */ "actual", actualBitmap, /* path= */ null);
    float averagePixelAbsoluteDifference =
        getBitmapAveragePixelAbsoluteDifferenceArgb8888(expectedBitmap, actualBitmap, testId);
    assertThat(averagePixelAbsoluteDifference).isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  public void increaseAlpha_matchesGoldenFile() throws Exception {
    alphaScaleShaderProgram =
        (AlphaScaleShaderProgram)
            new AlphaScale(1.5f).toGlShaderProgram(context, /* useHdr= */ false);
    Size outputSize = alphaScaleShaderProgram.configure(inputWidth, inputHeight);
    Bitmap expectedBitmap = readBitmapUnpremultipliedAlpha(INCREASE_ALPHA_PNG_ASSET_PATH);

    alphaScaleShaderProgram.drawFrame(inputTexId, /* presentationTimeUs= */ 0);
    Bitmap actualBitmap =
        createUnpremultipliedArgb8888BitmapFromFocusedGlFramebuffer(
            outputSize.getWidth(), outputSize.getHeight());

    // TODO(b/207848601): Switch to using proper tooling for testing against golden data.
    maybeSaveTestBitmap(testId, /* bitmapLabel= */ "actual", actualBitmap, /* path= */ null);
    float averagePixelAbsoluteDifference =
        getBitmapAveragePixelAbsoluteDifferenceArgb8888(expectedBitmap, actualBitmap, testId);
    assertThat(averagePixelAbsoluteDifference).isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE);
  }

  @Test
  @SdkSuppress(minSdkVersion = 33)
  public void noOpAlpha_withHdrHlgInput_matchesGoldenFile() throws Exception {
    assumeTrue("Device does not support OpenGL ES 3.0", GlUtil.getContextMajorVersion() >= 3);

    Bitmap hlgBitmap = readBitmapUnpremultipliedAlpha(ORIGINAL_HLG10_PNG_ASSET_PATH);
    int hlgWidth = hlgBitmap.getWidth();
    int hlgHeight = hlgBitmap.getHeight();
    int hlgTexId = createGlTextureFromBitmap(hlgBitmap);

    int outputTexId =
        GlUtil.createTexture(hlgWidth, hlgHeight, /* useHighPrecisionColorComponents= */ true);
    int frameBuffer = GlUtil.createFboForTexture(outputTexId);
    GlUtil.focusFramebuffer(
        checkNotNull(eglDisplay),
        checkNotNull(eglContext),
        checkNotNull(placeholderEglSurface),
        frameBuffer,
        hlgWidth,
        hlgHeight);
    GlUtil.clearFocusedBuffers();

    alphaScaleShaderProgram =
        (AlphaScaleShaderProgram)
            new AlphaScale(/* alphaScale= */ 1.0f).toGlShaderProgram(context, /* useHdr= */ true);
    Size outputSize = alphaScaleShaderProgram.configure(hlgWidth, hlgHeight);

    alphaScaleShaderProgram.drawFrame(hlgTexId, /* presentationTimeUs= */ 0);

    Bitmap actualBitmap =
        createFp16BitmapFromFocusedGlFramebuffer(outputSize.getWidth(), outputSize.getHeight());
    actualBitmap.setColorSpace(checkNotNull(hlgBitmap.getColorSpace()));

    maybeSaveTestBitmap(testId, /* bitmapLabel= */ "actual", actualBitmap, /* path= */ null);
    float averagePixelAbsoluteDifference =
        getBitmapAveragePixelAbsoluteDifferenceFp16(hlgBitmap, actualBitmap);
    assertThat(averagePixelAbsoluteDifference)
        .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE_DIFFERENT_DEVICE_FP16);

    GlUtil.deleteTexture(hlgTexId);
    GlUtil.deleteTexture(outputTexId);
    GlUtil.deleteFbo(frameBuffer);
  }
}
