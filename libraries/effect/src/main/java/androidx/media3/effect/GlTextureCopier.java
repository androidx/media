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

import static com.google.common.base.Preconditions.checkState;

import android.content.Context;
import android.opengl.GLES20;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import java.io.IOException;

/**
 * Copies the contents of one OpenGL texture to another, applying shader-based transformations.
 *
 * <p>All methods must be called on the GL thread.
 *
 * <p>This class encapsulates the shader program management and drawing operations required to
 * perform texture-to-texture copies using framebuffers (FBOs). It currently supports copying from
 * external OES textures (typically used for video decoders and camera inputs) to standard 2D
 * textures, and is designed to be extended to support standard 2D-to-2D copies and HDR color space
 * transformations.
 */
/* package */ final class GlTextureCopier {

  private final Context context;
  @Nullable private GlProgram externalCopyGlProgram;
  private int fboId = C.INDEX_UNSET;

  public GlTextureCopier(Context context) {
    this.context = context;
  }

  /**
   * Copies the content of an external texture to an internal texture.
   *
   * @param inputTexId The ID of the input external texture.
   * @param outputTexId The ID of the output internal texture.
   * @param outputWidth The width of the output texture.
   * @param outputHeight The height of the output texture.
   * @throws VideoFrameProcessingException If an OpenGL error occurs during the copy.
   */
  public void copyExternalTexture(
      int inputTexId, int outputTexId, int outputWidth, int outputHeight)
      throws VideoFrameProcessingException {
    try {
      if (externalCopyGlProgram == null) {
        externalCopyGlProgram =
            new GlProgram(
                context,
                R.raw.vertex_shader_transformation_es2,
                R.raw.fragment_shader_transformation_sdr_external_es2);
        externalCopyGlProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
        externalCopyGlProgram.setFloatsUniform(
            "uTransformationMatrix", GlUtil.create4x4IdentityMatrix());
        externalCopyGlProgram.setFloatsUniform(
            "uTexTransformationMatrix", GlUtil.create4x4IdentityMatrix());
        externalCopyGlProgram.setFloatsUniform("uRgbMatrix", GlUtil.create4x4IdentityMatrix());
        externalCopyGlProgram.setIntUniform("uOutputColorTransfer", C.COLOR_TRANSFER_SDR);
      }

      if (fboId == C.INDEX_UNSET) {
        int[] fboIds = new int[1];
        GLES20.glGenFramebuffers(/* n= */ 1, fboIds, /* offset= */ 0);
        GlUtil.checkGlError();
        fboId = fboIds[0];
      }

      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
      GlUtil.checkGlError();
      GLES20.glFramebufferTexture2D(
          GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, outputTexId, 0);
      GlUtil.checkGlError();

      GlUtil.focusFramebufferUsingCurrentContext(fboId, outputWidth, outputHeight);

      checkState(externalCopyGlProgram != null);
      externalCopyGlProgram.use();
      externalCopyGlProgram.setSamplerTexIdUniform(
          "uTexSampler", inputTexId, /* texUnitIndex= */ 0);
      externalCopyGlProgram.bindAttributesAndUniforms();
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
      GlUtil.checkGlError();
    } catch (GlException | IOException e) {
      throw VideoFrameProcessingException.from(e);
    }
  }

  /** Releases resources used by the copier. */
  public void release() throws VideoFrameProcessingException {
    try {
      if (externalCopyGlProgram != null) {
        externalCopyGlProgram.delete();
        externalCopyGlProgram = null;
      }
      if (fboId != C.INDEX_UNSET) {
        GlUtil.deleteFbo(fboId);
        fboId = C.INDEX_UNSET;
      }
    } catch (GlException e) {
      throw VideoFrameProcessingException.from(e);
    }
  }
}
