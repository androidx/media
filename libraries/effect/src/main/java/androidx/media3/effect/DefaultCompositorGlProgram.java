/*
 * Copyright 2025 The Android Open Source Project
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

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.opengl.GLES20;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Size;
import java.io.IOException;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A wrapper for a {@link GlProgram}, that draws multiple input {@link InputFrameInfo}s onto one
 * output {@link GlTextureInfo}.
 *
 * <p>All methods must be called on a GL thread, unless otherwise stated.
 */
/* package */ final class DefaultCompositorGlProgram {

  /** Holds required information to composite an input texture. */
  /* package */ static final class InputFrameInfo {
    public final GlTextureInfo glTextureInfo;
    public final OverlaySettings overlaySettings;

    public InputFrameInfo(GlTextureInfo glTextureInfo, OverlaySettings overlaySettings) {
      this.glTextureInfo = glTextureInfo;
      this.overlaySettings = overlaySettings;
    }
  }

  private static final String TAG = "CompositorGlProgram";
  private static final String VERTEX_SHADER_PATH = "shaders/vertex_shader_transformation_es2.glsl";
  private static final String FRAGMENT_SHADER_PATH = "shaders/fragment_shader_alpha_scale_es2.glsl";

  private final Context context;
  private final OverlayMatrixProvider overlayMatrixProvider;
  private @MonotonicNonNull GlProgram glProgram;

  /**
   * Creates an instance.
   *
   * <p>May be called on any thread.
   */
  public DefaultCompositorGlProgram(Context context) {
    this.context = context;
    this.overlayMatrixProvider = new OverlayMatrixProvider();
  }

  /**
   * Draws the {@linkplain InputFrameInfo InputFrameInfos} onto the {@linkplain GlTextureInfo
   * outputTexture}.
   */
  // Enhanced for-loops are discouraged in media3.effect due to short-lived allocations.
  @SuppressWarnings("ListReverse")
  public void drawFrame(List<InputFrameInfo> framesToComposite, GlTextureInfo outputTexture)
      throws GlUtil.GlException, VideoFrameProcessingException {
    ensureConfigured();
    GlUtil.focusFramebufferUsingCurrentContext(
        outputTexture.fboId, outputTexture.width, outputTexture.height);
    overlayMatrixProvider.configure(new Size(outputTexture.width, outputTexture.height));
    GlUtil.clearFocusedBuffers();

    GlProgram glProgram = checkNotNull(this.glProgram);
    glProgram.use();

    // Setup for blending.
    GLES20.glEnable(GLES20.GL_BLEND);
    // Similar to:
    // dst.rgb = src.rgb * src.a + dst.rgb * (1 - src.a)
    // dst.a   = src.a           + dst.a   * (1 - src.a)
    GLES20.glBlendFuncSeparate(
        /* srcRGB= */ GLES20.GL_SRC_ALPHA,
        /* dstRGB= */ GLES20.GL_ONE_MINUS_SRC_ALPHA,
        /* srcAlpha= */ GLES20.GL_ONE,
        /* dstAlpha= */ GLES20.GL_ONE_MINUS_SRC_ALPHA);
    GlUtil.checkGlError();

    // Draw textures from back to front.
    for (int i = framesToComposite.size() - 1; i >= 0; i--) {
      blendOntoFocusedTexture(framesToComposite.get(i));
    }

    GLES20.glDisable(GLES20.GL_BLEND);
    GlUtil.checkGlError();
  }

  public void release() {
    try {
      if (glProgram != null) {
        glProgram.delete();
      }
    } catch (GlUtil.GlException e) {
      Log.e(TAG, "Error releasing GL Program", e);
    }
  }

  private void ensureConfigured() throws VideoFrameProcessingException, GlUtil.GlException {
    if (glProgram != null) {
      return;
    }
    try {
      glProgram = new GlProgram(context, VERTEX_SHADER_PATH, FRAGMENT_SHADER_PATH);
      glProgram.setBufferAttribute(
          "aFramePosition",
          GlUtil.getNormalizedCoordinateBounds(),
          GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
      glProgram.setFloatsUniform("uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix());
    } catch (IOException e) {
      throw new VideoFrameProcessingException(e);
    }
  }

  private void blendOntoFocusedTexture(InputFrameInfo inputFrameInfo) throws GlUtil.GlException {
    GlProgram glProgram = checkNotNull(this.glProgram);
    GlTextureInfo inputTexture = inputFrameInfo.glTextureInfo;
    glProgram.setSamplerTexIdUniform("uTexSampler", inputTexture.texId, /* texUnitIndex= */ 0);
    float[] transformationMatrix =
        overlayMatrixProvider.getTransformationMatrix(
            /* overlaySize= */ new Size(inputTexture.width, inputTexture.height),
            inputFrameInfo.overlaySettings);
    glProgram.setFloatsUniform("uTransformationMatrix", transformationMatrix);
    glProgram.setFloatUniform("uAlphaScale", inputFrameInfo.overlaySettings.getAlphaScale());
    glProgram.bindAttributesAndUniforms();

    // The four-vertex triangle strip forms a quad.
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
    GlUtil.checkGlError();
  }
}
