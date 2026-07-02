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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import android.content.Context;
import android.opengl.GLES20;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Util;
import java.io.IOException;

/**
 * Copies the contents of one OpenGL texture to another, applying shader-based transformations.
 *
 * <p>All methods must be called on the GL thread.
 *
 * <p>This class encapsulates the shader program management and drawing operations required to
 * perform texture-to-texture copies using framebuffers (FBOs). It currently supports copying from
 * external OES textures (typically used for video decoders and camera inputs) to standard 2D
 * textures. It is designed to be extended to support HDR color space transformations.
 */
/* package */ final class GlTextureCopier {

  private static final String TAG = "GlTextureCopier";

  private final Context context;
  @Nullable private GlProgram sdrExternalCopyGlProgram;
  @Nullable private GlProgram sdrInternalCopyGlProgram;
  private int fboId = C.INDEX_UNSET;

  public GlTextureCopier(Context context) {
    this.context = context;
  }

  /**
   * Copies the content of an input texture to an output texture.
   *
   * @param inputTexId The ID of the input texture.
   * @param outputTexId The ID of the output texture.
   * @param outputWidth The width of the output texture.
   * @param outputHeight The height of the output texture.
   * @param inputColorInfo The {@link ColorInfo} of the input texture.
   * @param requestedOutputColorInfo The {@link ColorInfo} of the requested output texture.
   * @param textureTransformMatrix The 4x4 matrix to apply to texture coordinates.
   * @param isExternalTexture Whether the input texture is an external {@link
   *     android.opengl.GLES11Ext#GL_TEXTURE_EXTERNAL_OES} texture.
   * @throws VideoFrameProcessingException If an OpenGL error occurs during the copy.
   */
  public void copyTexture(
      int inputTexId,
      int outputTexId,
      int outputWidth,
      int outputHeight,
      ColorInfo inputColorInfo,
      ColorInfo requestedOutputColorInfo,
      float[] textureTransformMatrix,
      boolean isExternalTexture)
      throws VideoFrameProcessingException {
    boolean isHdr =
        ColorInfo.isTransferHdr(inputColorInfo)
            || ColorInfo.isTransferHdr(requestedOutputColorInfo);

    // TODO: b/517525358 - Remove this check once HDR color conversion for external textures is
    //  implemented.
    checkArgument(
        !isExternalTexture || !isHdr || inputColorInfo.equals(requestedOutputColorInfo),
        Util.formatInvariant(
            "Color conversion for HDR external textures is not supported yet. Input: %s, Output:"
                + " %s",
            inputColorInfo, requestedOutputColorInfo));
    try {
      GlProgram copyGlProgram;
      if (isExternalTexture) {
        if (sdrExternalCopyGlProgram == null) {
          sdrExternalCopyGlProgram =
              new GlProgram(
                  context,
                  R.raw.vertex_shader_transformation_es2,
                  R.raw.fragment_shader_transformation_sdr_external_es2);
          setupCommonAttributesAndUniforms(sdrExternalCopyGlProgram);
        }
        copyGlProgram = sdrExternalCopyGlProgram;
      } else {
        if (sdrInternalCopyGlProgram == null) {
          sdrInternalCopyGlProgram =
              new GlProgram(
                  context,
                  R.raw.vertex_shader_transformation_es2,
                  R.raw.fragment_shader_transformation_sdr_internal_es2);
          setupCommonAttributesAndUniforms(sdrInternalCopyGlProgram);
          sdrInternalCopyGlProgram.setIntUniform("uSdrWorkingColorSpace", 0);
        }
        copyGlProgram = sdrInternalCopyGlProgram;
        copyGlProgram.setIntUniform("uInputColorTransfer", inputColorInfo.colorTransfer);
        copyGlProgram.setIntUniform("uOutputColorTransfer", requestedOutputColorInfo.colorTransfer);
      }
      copyGlProgram.setIntUniform("uOutputColorTransfer", requestedOutputColorInfo.colorTransfer);

      copyGlProgram.setFloatsUniform("uTexTransformationMatrix", textureTransformMatrix);

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

      checkState(copyGlProgram != null);
      copyGlProgram.use();
      copyGlProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0);
      copyGlProgram.bindAttributesAndUniforms();
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
      GlUtil.checkGlError();
    } catch (GlException | IOException e) {
      throw VideoFrameProcessingException.from(e);
    }
  }

  /** Releases resources used by the copier. */
  public void release() throws VideoFrameProcessingException {
    @Nullable Exception firstException = null;
    if (sdrExternalCopyGlProgram != null) {
      try {
        sdrExternalCopyGlProgram.delete();
      } catch (GlException e) {
        firstException = e;
      }
      sdrExternalCopyGlProgram = null;
    }
    if (sdrInternalCopyGlProgram != null) {
      try {
        sdrInternalCopyGlProgram.delete();
      } catch (GlException e) {
        if (firstException == null) {
          firstException = e;
        } else {
          Log.w(TAG, "Failed to delete sdrInternalCopyGlProgram", e);
          firstException.addSuppressed(e);
        }
      }
      sdrInternalCopyGlProgram = null;
    }
    if (fboId != C.INDEX_UNSET) {
      try {
        GlUtil.deleteFbo(fboId);
      } catch (GlException e) {
        if (firstException == null) {
          firstException = e;
        } else {
          Log.w(TAG, "Failed to delete FBO", e);
          firstException.addSuppressed(e);
        }
      }
      fboId = C.INDEX_UNSET;
    }
    if (firstException != null) {
      throw VideoFrameProcessingException.from(firstException);
    }
  }

  private static void setupCommonAttributesAndUniforms(GlProgram glProgram) {
    glProgram.setBufferAttribute(
        "aFramePosition",
        GlUtil.getNormalizedCoordinateBounds(),
        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
    glProgram.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix());
    glProgram.setFloatsUniform("uRgbMatrix", GlUtil.create4x4IdentityMatrix());
  }
}
