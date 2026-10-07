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

import static androidx.media3.effect.EffectsTestUtil.createFocusedEglContextWithFallback;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createArgb8888BitmapWithSolidColor;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createGlTextureFromBitmap;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createUnpremultipliedArgb8888BitmapFromFocusedGlFramebuffer;
import static androidx.media3.test.utils.TestUtil.PSNR_THRESHOLD;
import static androidx.media3.test.utils.TestUtil.assertBitmapsAreSimilar;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Pair;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Pixel tests for the {@code fragment_shader_hdr_to_ultra_hdr_es3} shader.
 *
 * <p>The shader converts a linear BT.2020 frame into an Ultra HDR SDR base (color attachment 0) and
 * gain map (color attachment 1). These tests check that out-of-gamut BT.2020 colors are softly
 * compressed into the BT.709 gamut rather than clamped, and that the gain map is unaffected.
 */
@RunWith(TestParameterInjector.class)
@SdkSuppress(minSdkVersion = 26)
public final class HdrToUltraHdrShaderPixelTest {

  private static final int WIDTH = 16;
  private static final int HEIGHT = 16;

  // Uniform values match FrameExtractorInternal.FrameReadingGlShaderProgram.
  private static final float SDR_REFERENCE_WHITE_NITS = 203.0f;
  private static final float HDR_PEAK_NITS = 1000.0f;
  private static final float MAX_BOOST = HDR_PEAK_NITS / SDR_REFERENCE_WHITE_NITS;
  private static final int EXPECTED_GAINMAP_VALUE = 255;

  private static final ImmutableList<GamutTestCase> GAMUT_TEST_CASES =
      ImmutableList.of(
          // Peak BT.2020 red. BT.709 linear (1.2710, -0.0953, -0.0139). Soft compression gives
          // (255, 29, 40); a clamp would give (255, 0, 0).
          new GamutTestCase(
              "bt2020Red_compressesNegativeChannels",
              /* inputColor= */ Color.rgb(255, 0, 0),
              /* expectedSdrColor= */ Color.rgb(255, 29, 40)),
          // Peak BT.2020 green. BT.709 linear (-0.5437, 1.0482, -0.0931). Soft compression gives
          // (2, 255, 27); a clamp would give (0, 255, 0).
          new GamutTestCase(
              "bt2020Green_compressesNegativeChannels",
              /* inputColor= */ Color.rgb(0, 255, 0),
              /* expectedSdrColor= */ Color.rgb(2, 255, 27)),
          // Peak BT.2020 blue. BT.709 linear (-0.0414, -0.0047, 0.6358). Soft compression gives
          // (23, 31, 209); a clamp would give (0, 0, 209).
          new GamutTestCase(
              "bt2020Blue_compressesNegativeChannels",
              /* inputColor= */ Color.rgb(0, 0, 255),
              /* expectedSdrColor= */ Color.rgb(23, 31, 209)),
          // In gamut, but the BT.2020 to BT.709 conversion pushes R above 1.0. BT.709 linear
          // (1.2127, 0.4015, 0.4498). Soft compression preserves chromaticity and gives
          // (255, 156, 164); a clamp would clip R alone and give (255, 170, 179).
          new GamutTestCase(
              "brightInGamut_normalizesByAchromatic",
              /* inputColor= */ Color.rgb(255, 128, 128),
              /* expectedSdrColor= */ Color.rgb(255, 156, 164)),
          // In gamut and below the compression knee. BT.709 linear (0.4104, 0.4387, 0.9334). Both
          // soft compression and a clamp give (172, 177, 247).
          new GamutTestCase(
              "inGamut_passesThroughUnchanged",
              /* inputColor= */ Color.rgb(128, 128, 255),
              /* expectedSdrColor= */ Color.rgb(172, 177, 247)),
          // Achromatic. BT.709 linear (1.0, 1.0, 1.0). Both soft compression and a clamp give
          // white.
          new GamutTestCase(
              "white_passesThroughUnchanged",
              /* inputColor= */ Color.WHITE,
              /* expectedSdrColor= */ Color.WHITE));

  private final Context context = getApplicationContext();

  private @MonotonicNonNull EGLDisplay eglDisplay;
  private @MonotonicNonNull EGLContext eglContext;
  private @MonotonicNonNull EGLSurface placeholderEglSurface;
  private @MonotonicNonNull GlProgram glProgram;
  private int inputTexId;
  private int sdrTexId;
  private int gainmapTexId;
  private int frameBuffer;

  @Before
  public void setUp() throws Exception {
    eglDisplay = GlUtil.getDefaultEglDisplay();
    Pair<EGLContext, EGLSurface> eglContextAndSurface =
        createFocusedEglContextWithFallback(
            checkNotNull(eglDisplay), GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888);
    eglContext = eglContextAndSurface.first;
    placeholderEglSurface = eglContextAndSurface.second;
    assumeTrue("Device does not support OpenGL ES 3.0", GlUtil.getContextMajorVersion() >= 3);

    // Same multiple-render-target layout as FrameExtractorInternal: an 8-bit RGBA SDR base on
    // color attachment 0 and an 8-bit single-channel gain map on color attachment 1.
    sdrTexId = GlUtil.createTexture(WIDTH, HEIGHT, /* useHighPrecisionColorComponents= */ false);
    frameBuffer = GlUtil.createFboForTexture(sdrTexId);
    gainmapTexId = GlUtil.generateTexture();
    GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, gainmapTexId, GLES20.GL_LINEAR);
    GLES30.glTexImage2D(
        GLES30.GL_TEXTURE_2D,
        /* level= */ 0,
        GLES30.GL_R8,
        WIDTH,
        HEIGHT,
        /* border= */ 0,
        GLES30.GL_RED,
        GLES30.GL_UNSIGNED_BYTE,
        /* pixels= */ null);
    GlUtil.checkGlError();
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBuffer);
    GLES20.glFramebufferTexture2D(
        GLES20.GL_FRAMEBUFFER,
        GLES30.GL_COLOR_ATTACHMENT1,
        GLES20.GL_TEXTURE_2D,
        gainmapTexId,
        /* level= */ 0);
    GlUtil.checkGlError();
    int[] drawBuffers = {GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1};
    GLES30.glDrawBuffers(/* n= */ 2, drawBuffers, /* offset= */ 0);
    GlUtil.checkGlError();

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
  public void tearDown() throws Exception {
    if (glProgram != null) {
      glProgram.delete();
    }
    if (inputTexId != 0) {
      GlUtil.deleteTexture(inputTexId);
    }
    if (frameBuffer != 0) {
      GlUtil.deleteFbo(frameBuffer);
    }
    if (sdrTexId != 0) {
      GlUtil.deleteTexture(sdrTexId);
    }
    if (gainmapTexId != 0) {
      GlUtil.deleteTexture(gainmapTexId);
    }
    if (eglDisplay != null && placeholderEglSurface != null) {
      GlUtil.destroyEglSurface(eglDisplay, placeholderEglSurface);
    }
    if (eglDisplay != null && eglContext != null) {
      GlUtil.destroyEglContext(eglDisplay, eglContext);
    }
  }

  @Test
  public void drawFrame_withVariousGamutColors_outputsExpectedSdrBaseAndGainmap(
      @TestParameter(valuesProvider = GamutTestCasesProvider.class) GamutTestCase testCase)
      throws Exception {
    Bitmap expectedSdrBitmap =
        createArgb8888BitmapWithSolidColor(WIDTH, HEIGHT, testCase.expectedSdrColor);

    Pair<Bitmap, Integer> actualSdrBitmapAndGainmapValue =
        drawFrameAndReadOutputs(testCase.inputColor);

    assertBitmapsAreSimilar(
        expectedSdrBitmap, actualSdrBitmapAndGainmapValue.first, PSNR_THRESHOLD);
    assertThat((float) actualSdrBitmapAndGainmapValue.second)
        .isWithin(1f)
        .of(EXPECTED_GAINMAP_VALUE);
  }

  /**
   * Runs the shader over a frame of a single solid color and reads back its outputs.
   *
   * <p>The input bitmap only carries the 8-bit values. They are uploaded to the texture unchanged,
   * and the shader interprets them as linear BT.2020, where 1.0 corresponds to {@link
   * #HDR_PEAK_NITS}.
   *
   * @return The SDR base image read from color attachment 0, and the 8-bit gain map value at the
   *     center of color attachment 1.
   */
  private Pair<Bitmap, Integer> drawFrameAndReadOutputs(int inputColor) throws Exception {
    Bitmap inputBitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    inputBitmap.eraseColor(inputColor);
    inputTexId = createGlTextureFromBitmap(inputBitmap);

    glProgram =
        new GlProgram(
            context,
            /* vertexShaderResId= */ R.raw.vertex_shader_transformation_es3,
            /* fragmentShaderResId= */ R.raw.fragment_shader_hdr_to_ultra_hdr_es3);
    glProgram.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix());
    glProgram.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix());
    glProgram.setFloatUniform("uMaxBoost", MAX_BOOST);
    glProgram.setFloatUniform("uSdrReferenceWhiteNits", SDR_REFERENCE_WHITE_NITS);
    glProgram.setFloatUniform("uHdrPeakNits", HDR_PEAK_NITS);
    glProgram.setBufferAttribute(
        "aFramePosition",
        GlUtil.getNormalizedCoordinateBounds(),
        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
    glProgram.use();
    glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0);
    glProgram.bindAttributesAndUniforms();
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
    GlUtil.checkGlError();

    Bitmap sdrBitmap = createUnpremultipliedArgb8888BitmapFromFocusedGlFramebuffer(WIDTH, HEIGHT);

    ByteBuffer gainmapPixel = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder());
    GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT1);
    // A one-byte GL_RED row is not a multiple of the default 4-byte pack alignment.
    GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1);
    GLES20.glReadPixels(
        /* x= */ WIDTH / 2,
        /* y= */ HEIGHT / 2,
        /* width= */ 1,
        /* height= */ 1,
        /* format= */ GLES30.GL_RED,
        /* type= */ GLES20.GL_UNSIGNED_BYTE,
        gainmapPixel);
    // Restore the GL default pack alignment.
    GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 4);
    GLES30.glReadBuffer(GLES30.GL_COLOR_ATTACHMENT0);
    GlUtil.checkGlError();

    return Pair.create(sdrBitmap, gainmapPixel.get(0) & 0xFF);
  }

  private static final class GamutTestCase {
    private final String name;
    private final int inputColor;
    private final int expectedSdrColor;

    private GamutTestCase(String name, int inputColor, int expectedSdrColor) {
      this.name = name;
      this.inputColor = inputColor;
      this.expectedSdrColor = expectedSdrColor;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  private static final class GamutTestCasesProvider extends TestParameterValuesProvider {
    @Override
    protected ImmutableList<GamutTestCase> provideValues(
        TestParameterValuesProvider.Context context) {
      return GAMUT_TEST_CASES;
    }
  }
}
