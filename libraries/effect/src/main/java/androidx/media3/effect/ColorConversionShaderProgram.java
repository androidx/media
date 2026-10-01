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

import static androidx.media3.effect.FrameProcessorUtils.useHighPrecisionColorComponents;

import android.content.Context;
import android.opengl.GLES20;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import java.io.IOException;

/**
 * Converts frames from an input color space to an electrical output color space.
 *
 * <p>Accepts input in any of the supported working color spaces, whether linear optical or
 * electrical, and outputs electrical colors expected by an encoder or a {@link
 * android.view.Surface}.
 */
/* package */ final class ColorConversionShaderProgram extends BaseGlShaderProgram {

  private final GlProgram glProgram;

  /**
   * Creates a new instance.
   *
   * @param context The {@link Context}.
   * @param inputColorInfo The {@link ColorInfo} of the input frames, which is the frame processor's
   *     working color space.
   * @param outputColorInfo The {@link ColorInfo} to convert frames to.
   * @throws VideoFrameProcessingException If a problem occurs while reading shader files.
   */
  public ColorConversionShaderProgram(
      Context context, ColorInfo inputColorInfo, ColorInfo outputColorInfo)
      throws VideoFrameProcessingException {
    // The output texture holds the electrical output colors, so its precision is determined by the
    // output color space rather than by the working color space the frames arrive in.
    super(
        /* useHighPrecisionColorComponents= */ useHighPrecisionColorComponents(outputColorInfo),
        /* texturePoolCapacity= */ 1);

    try {
      glProgram =
          new GlProgram(
              context,
              /* vertexShaderResId= */ R.raw.vertex_shader_transformation_es3,
              /* firstFragmentShaderResId= */ R.raw.color_conversions_es3,
              R.raw.fragment_shader_color_conversion_es3);
    } catch (IOException | GlUtil.GlException e) {
      throw new VideoFrameProcessingException(e);
    }

    glProgram.setBufferAttribute(
        "aFramePosition",
        GlUtil.getNormalizedCoordinateBounds(),
        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);

    float[] identityMatrix = GlUtil.create4x4IdentityMatrix();
    glProgram.setFloatsUniform("uTransformationMatrix", identityMatrix);
    glProgram.setFloatsUniform("uTexTransformationMatrix", identityMatrix);

    glProgram.setIntUniform("uInputColorGamut", inputColorInfo.colorSpace);
    glProgram.setIntUniform("uInputColorTransfer", inputColorInfo.colorTransfer);
    glProgram.setIntUniform("uOutputColorTransfer", outputColorInfo.colorTransfer);
  }

  @Override
  public Size configure(int inputWidth, int inputHeight) {
    return new Size(inputWidth, inputHeight);
  }

  @Override
  public void drawFrame(int inputTexId, long presentationTimeUs)
      throws VideoFrameProcessingException {
    try {
      glProgram.use();
      glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0);
      glProgram.bindAttributesAndUniforms();

      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
      GlUtil.checkGlError();
    } catch (GlUtil.GlException e) {
      throw new VideoFrameProcessingException(e, presentationTimeUs);
    }
  }

  @Override
  public void release() throws VideoFrameProcessingException {
    super.release();
    try {
      glProgram.delete();
    } catch (GlUtil.GlException e) {
      throw new VideoFrameProcessingException(e);
    }
  }
}
