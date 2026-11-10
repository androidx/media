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

import static androidx.media3.common.util.GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE;
import static androidx.media3.common.util.GlUtil.checkGlError;
import static androidx.media3.effect.FrameProcessorUtils.runAllAndAccumulateExceptions;
import static androidx.media3.effect.FrameProcessorUtils.validateYuv420Planes;
import static com.google.common.base.Preconditions.checkArgument;

import android.content.Context;
import android.graphics.ImageFormat;
import android.opengl.GLES20;
import android.opengl.Matrix;
import androidx.media3.common.C;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.video.ImagePlanesFrame.Plane;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Converts RGBA OpenGL textures into 8-bit YUV 4:2:0 CPU {@linkplain Plane planes}.
 *
 * <p>Conversion proceeds in three steps:
 *
 * <ol>
 *   <li>Subsamples the input RGBA texture into a half-width ({@code ceil(inputWidth / 2)}) {@code
 *       GL_RGBA} framebuffer in {@link ImageFormat#YUY2} (YUV 4:2:2, {@code [Y0, U, Y1, V]}) order.
 *   <li>Uses {@link GLES20#glReadPixels} to transfer the YUY2 output to CPU memory.
 *   <li>Uses {@link HardwareBufferJniWrapper#convertYuy2ToYuv420Planes} to convert YUY2 to YUV
 *       4:2:0.
 * </ol>
 *
 * <pre>
 * Input RGBA texture (4x2 pixels A..H):
 *   Row 0:  A  B  C  D
 *   Row 1:  E  F  G  H
 *
 * Packed YUY2 GL_RGBA framebuffer (2x2 texels, 4 bytes per texel):
 *   Row 0:  [Y(A), U(A,B), Y(B), V(A,B)]  [Y(C), U(C,D), Y(D), V(C,D)]
 *   Row 1:  [Y(E), U(E,F), Y(F), V(E,F)]  [Y(G), U(G,H), Y(H), V(G,H)]
 *
 * Output YUV 4:2:0 planes:
 *   Y (4x2):  [Y(A), Y(B), Y(C), Y(D)]
 *             [Y(E), Y(F), Y(G), Y(H)]
 *   U (2x1):  [U(A,B,E,F), U(C,D,G,H)]
 *   V (2x1):  [V(A,B,E,F), V(C,D,G,H)]
 * </pre>
 *
 * <p>Assumes the input dimensions do not change; if the frame size changes, a new instance must be
 * created.
 *
 * <p>All methods must be called on the GL thread.
 */
/* package */ final class GlRgbaToYuvConverter {

  private static final int BYTES_PER_YUY2_MACROPIXEL = 4;

  private final Context context;
  private final HardwareBufferJniWrapper hardwareBufferJniWrapper;
  private final int inputWidth;
  private final int inputHeight;
  private final int yuy2OutputWidth;
  private final float[] texTransformationMatrix;

  private @Nullable GlProgram glProgram;
  private @Nullable GlTextureInfo packedYuy2Texture;
  private @Nullable ByteBuffer readbackBuffer;

  /** Creates an instance for frames of the given pixel dimensions. */
  /* package */ GlRgbaToYuvConverter(
      Context context,
      HardwareBufferJniWrapper hardwareBufferJniWrapper,
      int inputWidth,
      int inputHeight) {
    checkArgument(
        inputWidth > 0 && inputHeight > 0,
        "Invalid image dimensions: %sx%s",
        inputWidth,
        inputHeight);
    this.context = context.getApplicationContext();
    this.hardwareBufferJniWrapper = hardwareBufferJniWrapper;
    this.inputWidth = inputWidth;
    this.inputHeight = inputHeight;
    this.yuy2OutputWidth = (inputWidth + 1) / 2;
    this.texTransformationMatrix = GlUtil.create4x4IdentityMatrix();
    // The input texture spans X in [0, 1] (1 / inputWidth per pixel). Each YUY2 texel packs 2
    // input pixels, so yuy2OutputWidth texels cover (2 * yuy2OutputWidth) pixels:
    //
    // 1. Even width (e.g. 4 input px -> 2 YUY2 texels = 4 px):
    //    4 / 4 = 1.0, so X stays [0, 1]:
    //      0.0           0.5           1.0
    //       | px 0 | px 1 | px 2 | px 3 |   (input: 4 px)
    //       |    out 0    |    out 1    |   (YUY2:  2 texels)
    //
    // 2. Odd width (e.g. 3 input px -> 2 YUY2 texels = 4 px):
    //    out 1's right half extends 1 pixel past 1.0 (clamped to px 2 by GL_CLAMP_TO_EDGE in
    //    GlUtil.bindTexture), so 4 / 3 scales X from [0, 1] to [0, 4/3]:
    //      0.0      1/3      2/3      1.0      4/3
    //       |  px 0  |  px 1  |  px 2  | (px 2) |   (input: 3 px + 1 clamped px)
    //       |      out 0      |      out 1      |   (YUY2:  2 texels)
    Matrix.scaleM(
        this.texTransformationMatrix,
        /* mOffset= */ 0,
        /* x= */ (yuy2OutputWidth * 2.0f) / inputWidth,
        /* y= */ 1f,
        /* z= */ 1f);
  }

  /**
   * Converts an SDR RGBA texture to limited-range BT.709 8-bit YUV 4:2:0 and writes the result into
   * {@code outputPlanes} (Y, U, V).
   *
   * <p>The input texture must contain electrical (sRGB) BT.709 RGB values, and its first row must
   * hold the first row of the image, matching the plane row order.
   *
   * @param inputTexId The OpenGL texture ID of the input RGBA texture.
   * @param outputPlanes The destination Y, U, and V planes, validated to be a planar or semi-planar
   *     8-bit YUV 4:2:0 layout with sufficient row strides and remaining buffer capacities for the
   *     configured dimensions.
   * @throws IllegalArgumentException If {@code outputPlanes} is not a valid planar or semi-planar
   *     8-bit YUV 4:2:0 layout for the configured dimensions.
   * @throws VideoFrameProcessingException If an OpenGL or shader resource error occurs.
   */
  /* package */ void convertToYuv420(int inputTexId, List<Plane> outputPlanes)
      throws VideoFrameProcessingException {
    validateYuv420Planes(outputPlanes, inputWidth, inputHeight);
    try {
      GlProgram glProgram = getOrCreateGlProgram();
      GlTextureInfo packedYuy2Texture = getOrCreatePackedYuy2Texture();
      ByteBuffer readbackBuffer = getOrCreateReadbackBuffer();

      glProgram.use();
      glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0);
      glProgram.bindAttributesAndUniforms();

      GlUtil.focusFramebufferUsingCurrentContext(
          packedYuy2Texture.fboId, packedYuy2Texture.width, packedYuy2Texture.height);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
      checkGlError();

      readbackBuffer.clear();
      GLES20.glReadPixels(
          /* x= */ 0,
          /* y= */ 0,
          yuy2OutputWidth,
          inputHeight,
          GLES20.GL_RGBA,
          GLES20.GL_UNSIGNED_BYTE,
          readbackBuffer);
      checkGlError();

      hardwareBufferJniWrapper.convertYuy2ToYuv420Planes(
          readbackBuffer, inputWidth, inputHeight, outputPlanes);
    } catch (GlException | IOException e) {
      throw VideoFrameProcessingException.from(e);
    }
  }

  /** Releases the OpenGL resources held by this instance. */
  /* package */ void release() throws VideoFrameProcessingException {
    @Nullable GlProgram glProgramToDelete = glProgram;
    glProgram = null;
    @Nullable GlTextureInfo textureToRelease = packedYuy2Texture;
    packedYuy2Texture = null;
    readbackBuffer = null;
    runAllAndAccumulateExceptions(
        () -> {
          if (glProgramToDelete != null) {
            glProgramToDelete.delete();
          }
        },
        () -> {
          if (textureToRelease != null) {
            textureToRelease.release();
          }
        });
  }

  private GlProgram getOrCreateGlProgram() throws GlException, IOException {
    if (glProgram != null) {
      return glProgram;
    }
    GlProgram newGlProgram =
        new GlProgram(
            context,
            R.raw.vertex_shader_transformation_es2,
            R.raw.color_conversions_es2,
            R.raw.fragment_shader_rgba_to_yuy2_es2);
    newGlProgram.setBufferAttribute(
        "aFramePosition",
        GlUtil.getNormalizedCoordinateBounds(),
        HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
    newGlProgram.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix());
    newGlProgram.setFloatsUniform("uTexTransformationMatrix", texTransformationMatrix);
    newGlProgram.setFloatUniform("uHalfInputTexelWidth", 0.5f / inputWidth);
    newGlProgram.setInternalTexturesRequireNearestSampling(true);
    glProgram = newGlProgram;
    return newGlProgram;
  }

  private GlTextureInfo getOrCreatePackedYuy2Texture() throws GlException {
    if (packedYuy2Texture != null) {
      return packedYuy2Texture;
    }
    int texId =
        GlUtil.createTexture(
            yuy2OutputWidth, inputHeight, /* useHighPrecisionColorComponents= */ false);
    try {
      int fboId = GlUtil.createFboForTexture(texId);
      packedYuy2Texture =
          new GlTextureInfo(texId, fboId, /* rboId= */ C.INDEX_UNSET, yuy2OutputWidth, inputHeight);
      return packedYuy2Texture;
    } catch (GlException e) {
      try {
        GlUtil.deleteTexture(texId);
      } catch (GlException deleteException) {
        e.addSuppressed(deleteException);
      }
      throw e;
    }
  }

  private ByteBuffer getOrCreateReadbackBuffer() {
    if (readbackBuffer != null) {
      return readbackBuffer;
    }
    readbackBuffer =
        ByteBuffer.allocateDirect(yuy2OutputWidth * inputHeight * BYTES_PER_YUY2_MACROPIXEL);
    return readbackBuffer;
  }
}
