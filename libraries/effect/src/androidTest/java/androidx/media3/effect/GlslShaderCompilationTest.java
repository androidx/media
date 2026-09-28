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
package androidx.media3.effect;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.text.SpannableString;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Util;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumentation test verifying that all stripped GLSL raw shader resources in {@link R.raw}
 * compile and link into valid OpenGL ES shader programs.
 */
@RunWith(AndroidJUnit4.class)
public final class GlslShaderCompilationTest {

  private final Context context = getApplicationContext();

  private @MonotonicNonNull EGLDisplay eglDisplay;
  private @MonotonicNonNull EGLContext eglContext;

  @Before
  public void setUpEglContext() throws GlUtil.GlException {
    eglDisplay = GlUtil.getDefaultEglDisplay();
    eglContext =
        GlUtil.createEglContext(
            EGL14.EGL_NO_CONTEXT,
            eglDisplay,
            /* openGlVersion= */ 3,
            GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888);
    GlUtil.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
  }

  @After
  public void tearDownEglContext() throws GlUtil.GlException {
    if (eglContext != null && eglDisplay != null) {
      GlUtil.destroyEglContext(eglDisplay, eglContext);
    }
  }

  @Test
  public void compile_allRawGlslShaders_succeeds() throws Exception {
    Set<Integer> compiledResIds = new HashSet<>();

    // Standalone ES2 vertex and fragment shaders.
    compileGlProgram(
        compiledResIds,
        R.raw.vertex_shader_transformation_es2,
        R.raw.fragment_shader_alpha_scale_es2);
    compileGlProgram(
        compiledResIds, R.raw.vertex_shader_transformation_es2, R.raw.fragment_shader_copy_es2);
    compileGlProgram(
        compiledResIds, R.raw.vertex_shader_thumbnail_strip_es2, R.raw.fragment_shader_copy_es2);
    compileGlProgram(
        compiledResIds, R.raw.vertex_shader_transformation_es2, R.raw.fragment_shader_hsl_es2);
    compileGlProgram(
        compiledResIds, R.raw.vertex_shader_transformation_es2, R.raw.fragment_shader_lut_es2);
    compileGlProgram(
        compiledResIds,
        R.raw.vertex_shader_transformation_es2,
        R.raw.fragment_shader_separable_convolution_es2);
    compileGlProgram(
        compiledResIds,
        R.raw.vertex_shader_transformation_es2,
        R.raw.fragment_shader_transformation_es2);
    compileGlProgram(
        compiledResIds,
        R.raw.vertex_shader_transformation_es2,
        R.raw.fragment_shader_transformation_sdr_external_es2);
    compileGlProgram(
        compiledResIds,
        R.raw.vertex_shader_transformation_es2,
        R.raw.fragment_shader_transformation_sdr_internal_es2);
    compileGlProgram(
        compiledResIds,
        R.raw.vertex_shader_transformation_es2,
        R.raw.fragment_shader_transformation_sdr_oetf_es2);

    // Concatenated ES2 color conversion shaders.
    compileGlProgram(
        compiledResIds,
        R.raw.vertex_shader_transformation_es2,
        R.raw.color_conversions_es2,
        R.raw.fragment_shader_transformation_sdr_internal_color_conversion_es2);
    compileGlProgram(
        compiledResIds,
        R.raw.vertex_shader_transformation_es2,
        R.raw.color_conversions_es2,
        R.raw.fragment_shader_transformation_sdr_external_color_conversion_es2);

    // Standalone ES3 vertex and fragment shaders.
    compileGlProgram(
        compiledResIds,
        R.raw.vertex_shader_transformation_es3,
        R.raw.fragment_shader_hdr_to_ultra_hdr_es3);
    compileGlProgram(
        compiledResIds, R.raw.vertex_shader_transformation_es3, R.raw.fragment_shader_oetf_es3);
    compileGlProgram(
        compiledResIds,
        R.raw.vertex_shader_transformation_es3,
        R.raw.fragment_shader_transformation_hdr_internal_es3);
    compileGlProgram(
        compiledResIds,
        R.raw.vertex_shader_transformation_es3,
        R.raw.fragment_shader_transformation_ultra_hdr_es3);

    // Concatenated ES3 color conversion shaders.
    compileGlProgram(
        compiledResIds,
        R.raw.vertex_shader_transformation_es3,
        R.raw.color_conversions_es3,
        R.raw.fragment_shader_transformation_hdr_internal_color_conversion_es3);

    // ES3 external YUV shaders (require GL_EXT_YUV_target).
    compileExternalYuvGlProgram(
        compiledResIds,
        R.raw.vertex_shader_transformation_es3,
        R.raw.fragment_shader_transformation_external_yuv_es3);
    compileExternalYuvGlProgram(
        compiledResIds,
        R.raw.vertex_shader_transformation_es3,
        R.raw.color_conversions_es3,
        R.raw.fragment_shader_transformation_external_yuv_color_conversion_es3);

    // Injected overlay fragment shader snippets (insert_overlay_fragment_shader_methods and
    // insert_ultra_hdr).
    OverlayShaderProgram overlayShaderProgram =
        new OverlayShaderProgram(
            context,
            /* useHdr= */ true,
            ImmutableList.of(
                TextOverlay.createStaticTextOverlay(SpannableString.valueOf("overlay"))));
    overlayShaderProgram.release();
    compiledResIds.add(R.raw.insert_overlay_fragment_shader_methods);
    compiledResIds.add(R.raw.insert_ultra_hdr);

    // Verify that every R.raw.* resource in androidx.media3.effect.R.raw was compiled and has
    // comments stripped.
    Set<Integer> allRawResIds = new HashSet<>();
    for (Field field : R.raw.class.getFields()) {
      if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class) {
        int resId = field.getInt(null);
        allRawResIds.add(resId);
        String shaderSource = Util.loadRawResource(context, resId);
        assertWithMessage("Shader %s is empty", field.getName()).that(shaderSource).isNotEmpty();
        assertWithMessage("Shader %s still contains // comments", field.getName())
            .that(shaderSource)
            .doesNotContain("//");
        assertWithMessage("Shader %s still contains /* comments", field.getName())
            .that(shaderSource)
            .doesNotContain("/*");
      }
    }
    assertThat(compiledResIds).containsExactlyElementsIn(allRawResIds);
  }

  private void compileGlProgram(
      Set<Integer> compiledResIds,
      int vertexShaderResId,
      int firstFragmentShaderResId,
      int... additionalFragmentShaderResIds)
      throws IOException, GlUtil.GlException {
    compiledResIds.add(vertexShaderResId);
    compiledResIds.add(firstFragmentShaderResId);
    for (int resId : additionalFragmentShaderResIds) {
      compiledResIds.add(resId);
    }
    GlProgram glProgram =
        new GlProgram(
            context, vertexShaderResId, firstFragmentShaderResId, additionalFragmentShaderResIds);
    glProgram.delete();
  }

  private void compileExternalYuvGlProgram(
      Set<Integer> compiledResIds, int vertexShaderResId, int... fragmentShaderResIds)
      throws IOException, GlUtil.GlException {
    compiledResIds.add(vertexShaderResId);
    StringBuilder fragmentShader = new StringBuilder();
    for (int resId : fragmentShaderResIds) {
      compiledResIds.add(resId);
      fragmentShader.append(Util.loadRawResource(context, resId)).append('\n');
    }
    String fragmentShaderGlsl = fragmentShader.toString();
    if (!GlUtil.isYuvTargetExtensionSupported()) {
      // On emulators without hardware GL_EXT_YUV_target support, substitute the external sampler
      // extension and type so the rest of the stripped shader is still compiled and linked by the
      // device's OpenGL ES 3.0 compiler.
      fragmentShaderGlsl =
          fragmentShaderGlsl
              .replace(
                  "#extension GL_EXT_YUV_target : require",
                  "#extension GL_OES_EGL_image_external_essl3 : require")
              .replace(
                  "#extension GL_EXT_YUV_target : enable",
                  "#extension GL_OES_EGL_image_external_essl3 : enable")
              .replace("__samplerExternal2DY2YEXT", "samplerExternalOES");
    }
    GlProgram glProgram =
        new GlProgram(Util.loadRawResource(context, vertexShaderResId), fragmentShaderGlsl);
    glProgram.delete();
  }
}
