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

import static androidx.media3.common.util.GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE;
import static androidx.media3.common.util.GlUtil.checkGlError;
import static androidx.media3.effect.FrameProcessorUtils.runAllAndAccumulateExceptions;
import static androidx.media3.effect.FrameProcessorUtils.useHighPrecisionColorComponents;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.graphics.PixelFormat;
import android.opengl.GLES20;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.ThrowingRunnable;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.ImagePlanesFrame;
import androidx.media3.common.video.ImagePlanesFrame.Plane;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** Converts an {@link ImagePlanesFrame} to a {@link GlTextureFrame}. */
@ExperimentalApi // TODO: b/505721737 Remove once FrameProcessor is production ready.
/* package */ final class ImagePlanesToGlTextureConverter implements FrameToGlTextureConverter {

  private static final int RGBA_BYTES_PER_PIXEL = 4;

  private final Context context;
  private final ColorInfo outputColorInfo;
  private final Consumer<VideoFrameProcessingException> errorConsumer;
  private final Map<ImagePlanesFrame, GlTextureInfo> activeOutputTextures;
  private final Deque<GlTextureInfo> availableOutputTextures;
  private final float[] textureTransformMatrix;

  @Nullable private GlProgram glProgram;
  @Nullable private PlaneTexture planeTexture;
  private int fboId;
  private boolean isClosed;

  /* package */ ImagePlanesToGlTextureConverter(
      Context context,
      ColorInfo outputColorInfo,
      Consumer<VideoFrameProcessingException> errorConsumer) {
    this.context = context.getApplicationContext();
    this.outputColorInfo = outputColorInfo;
    this.errorConsumer = errorConsumer;
    this.activeOutputTextures = new HashMap<>();
    this.availableOutputTextures = new ArrayDeque<>();
    this.textureTransformMatrix = new float[16];
    this.fboId = C.INDEX_UNSET;
  }

  @Override
  @Nullable
  public GlTextureFrame convert(
      Frame frame, Executor glExecutor, Executor listenerExecutor, FrameProcessor.Listener listener)
      throws VideoFrameProcessingException {
    checkArgument(frame instanceof ImagePlanesFrame, "Expected ImagePlanesFrame, got: %s", frame);
    ImagePlanesFrame imagePlanesFrame = (ImagePlanesFrame) frame;
    if (isClosed) {
      return null;
    }

    validatePlanes(imagePlanesFrame);

    Format inputFormat = imagePlanesFrame.getFormat();
    boolean isRotated = inputFormat.rotationDegrees == 90 || inputFormat.rotationDegrees == 270;
    int outputWidth = isRotated ? inputFormat.height : inputFormat.width;
    int outputHeight = isRotated ? inputFormat.width : inputFormat.height;

    @Nullable GlTextureInfo outputTexture = null;
    try {
      GlProgram program = getOrCreateGlProgram();
      uploadTexture(
          imagePlanesFrame.getPlanes().get(0),
          inputFormat.width,
          inputFormat.height,
          GLES20.GL_RGBA,
          RGBA_BYTES_PER_PIXEL);
      outputTexture = setupOutputTextureAndFbo(outputWidth, outputHeight);
      activeOutputTextures.put(imagePlanesFrame, outputTexture);
      bindProgramAndUniforms(program, inputFormat, outputWidth, outputHeight);

      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
      checkGlError();
    } catch (GlException | IOException e) {
      activeOutputTextures.remove(imagePlanesFrame);
      if (outputTexture != null) {
        try {
          outputTexture.release();
        } catch (GlException ex) {
          e.addSuppressed(ex);
        }
      }
      throw VideoFrameProcessingException.from(e);
    }

    return buildGlTextureFrame(
        imagePlanesFrame, outputTexture, glExecutor, listenerExecutor, listener);
  }

  @Override
  public void releaseGlResources(Frame frame) throws VideoFrameProcessingException {
    checkArgument(frame instanceof ImagePlanesFrame, "Expected ImagePlanesFrame, got: %s", frame);
    @Nullable GlTextureInfo outputTexture = activeOutputTextures.remove(frame);
    if (outputTexture != null) {
      if (isClosed) {
        try {
          outputTexture.release();
        } catch (GlException e) {
          throw VideoFrameProcessingException.from(e);
        }
      } else {
        availableOutputTextures.addLast(outputTexture);
      }
    }
  }

  @Override
  public void close() throws VideoFrameProcessingException {
    if (isClosed) {
      return;
    }
    List<ThrowingRunnable<?>> cleanupActions = new ArrayList<>();
    if (glProgram != null) {
      GlProgram programToDelete = glProgram;
      glProgram = null;
      cleanupActions.add(programToDelete::delete);
    }
    if (planeTexture != null) {
      int texId = planeTexture.texId;
      planeTexture = null;
      cleanupActions.add(() -> GlUtil.deleteTexture(texId));
    }
    for (GlTextureInfo outputTexture : activeOutputTextures.values()) {
      cleanupActions.add(outputTexture::release);
    }
    activeOutputTextures.clear();
    for (GlTextureInfo outputTexture : availableOutputTextures) {
      cleanupActions.add(outputTexture::release);
    }
    availableOutputTextures.clear();
    if (fboId != C.INDEX_UNSET) {
      int fboToDelete = fboId;
      fboId = C.INDEX_UNSET;
      cleanupActions.add(() -> GlUtil.deleteFbo(fboToDelete));
    }
    try {
      runAllAndAccumulateExceptions(cleanupActions.toArray(new ThrowingRunnable<?>[0]));
    } finally {
      isClosed = true;
    }
  }

  // TODO: b/531653682 - Add support for planar (I420) and semi-planar (NV12 and NV21) formats.
  private static void validatePlanes(ImagePlanesFrame frame) {
    Format inputFormat = frame.getFormat();
    ImmutableList<Plane> planes = frame.getPlanes();
    checkArgument(
        inputFormat.width > 0 && inputFormat.height > 0,
        "Unsupported frame dimensions: %sx%s",
        inputFormat.width,
        inputFormat.height);
    if (inputFormat.colorInfo != null) {
      int colorSpace = inputFormat.colorInfo.colorSpace;
      checkArgument(
          colorSpace == Format.NO_VALUE
              || colorSpace == C.COLOR_SPACE_BT601
              || colorSpace == C.COLOR_SPACE_BT709,
          "Unsupported color space: %s",
          colorSpace);
    }
    switch (inputFormat.pixelFormat) {
      case PixelFormat.RGBA_8888:
        checkArgument(
            planes.size() == 1 && planes.get(0).getPixelStride() == RGBA_BYTES_PER_PIXEL,
            "Expected 1 plane with pixelStride of %s for RGBA_8888",
            RGBA_BYTES_PER_PIXEL);
        break;
      default:
        throw new UnsupportedOperationException(
            "Unsupported pixel format: " + inputFormat.pixelFormat);
    }
    validatePlaneDimensions(
        planes.get(0), inputFormat.width, inputFormat.height, RGBA_BYTES_PER_PIXEL);
  }

  private static void validatePlaneDimensions(
      Plane plane, int width, int height, int bytesPerPixel) {
    ByteBuffer buffer = plane.getBuffer();
    int rowStride = plane.getRowStride();
    int rowBytes = width * bytesPerPixel;
    checkArgument(
        rowStride >= rowBytes,
        "rowStride (%s) must be at least rowBytes (%s)",
        rowStride,
        rowBytes);
    checkArgument(
        rowStride % bytesPerPixel == 0,
        "rowStride (%s) must be a multiple of bytesPerPixel (%s)",
        rowStride,
        bytesPerPixel);
    // Producers such as ImageReader end a plane's buffer at its last visible pixel, so the final
    // row does not need to be padded to a full rowStride.
    int minimumBufferSize = rowStride * (height - 1) + rowBytes;
    checkArgument(
        buffer.remaining() >= minimumBufferSize,
        "Plane buffer holds %s bytes, but rowStride (%s) and height (%s) require at least %s",
        buffer.remaining(),
        rowStride,
        height,
        minimumBufferSize);
  }

  private GlProgram getOrCreateGlProgram() throws GlException, IOException {
    if (glProgram == null) {
      glProgram =
          new GlProgram(
              context,
              R.raw.vertex_shader_transformation_es2,
              R.raw.color_conversions_es2,
              R.raw.fragment_shader_transformation_sdr_internal_color_conversion_es2);
      glProgram.setBufferAttribute(
          "aFramePosition",
          GlUtil.getNormalizedCoordinateBounds(),
          HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
      glProgram.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix());
      glProgram.setFloatsUniform("uRgbMatrix", GlUtil.create4x4IdentityMatrix());
      int outputColorSpace =
          outputColorInfo.colorSpace != Format.NO_VALUE
              ? outputColorInfo.colorSpace
              : C.COLOR_SPACE_BT709;
      glProgram.setIntUniform("uOutputColorGamut", outputColorSpace);
      glProgram.setIntUniform("uOutputColorTransfer", outputColorInfo.colorTransfer);
    }
    return glProgram;
  }

  private void uploadTexture(Plane plane, int width, int height, int glFormat, int bytesPerPixel)
      throws GlException {
    // OpenGL ES 2.0 lacks GL_UNPACK_ROW_LENGTH, so upload the full rowStride width and crop the
    // row padding via uTexTransformationMatrix.
    int rowStride = plane.getRowStride();
    int textureWidth = rowStride / bytesPerPixel;
    // Bind before both glTexImage2D and glTexSubImage2D, which operate on the currently bound
    // GL_TEXTURE_2D texture (GlUtil.generateTexture() returns an unbound texture ID).
    int texId = planeTexture != null ? planeTexture.texId : GlUtil.generateTexture();
    GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, texId, GLES20.GL_LINEAR);

    boolean needsTextureAllocation =
        planeTexture == null
            || planeTexture.width != textureWidth
            || planeTexture.height != height
            || planeTexture.glFormat != glFormat;
    if (needsTextureAllocation) {
      // Allocate GPU texture storage on the first frame, or reallocate if plane dimensions or
      // format change (glTexImage2D automatically releases and replaces any previous texture
      // memory for texId). The pixel contents are always updated in-place below, to avoid
      // per-frame texture memory reallocation overhead.
      GLES20.glTexImage2D(
          GLES20.GL_TEXTURE_2D,
          /* level= */ 0,
          glFormat,
          textureWidth,
          height,
          /* border= */ 0,
          glFormat,
          GLES20.GL_UNSIGNED_BYTE,
          /* pixels= */ null);
      checkGlError();
      planeTexture = new PlaneTexture(texId, textureWidth, height, glFormat);
    }

    // The final row can be shorter than rowStride (see validatePlaneDimensions), so upload all
    // other rows at their full stride, and then only the visible part of the final row.
    ByteBuffer buffer = plane.getBuffer();
    int finalRowIndex = height - 1;
    if (finalRowIndex > 0) {
      GLES20.glTexSubImage2D(
          GLES20.GL_TEXTURE_2D,
          /* level= */ 0,
          /* xoffset= */ 0,
          /* yoffset= */ 0,
          textureWidth,
          /* height= */ finalRowIndex,
          glFormat,
          GLES20.GL_UNSIGNED_BYTE,
          buffer);
      checkGlError();
    }

    // glTexSubImage2D reads starting from buffer.position() without advancing it, so advance the
    // position to the start of the final row and restore it afterwards.
    int initialPosition = buffer.position();
    try {
      buffer.position(initialPosition + rowStride * finalRowIndex);
      GLES20.glTexSubImage2D(
          GLES20.GL_TEXTURE_2D,
          /* level= */ 0,
          /* xoffset= */ 0,
          /* yoffset= */ finalRowIndex,
          width,
          /* height= */ 1,
          glFormat,
          GLES20.GL_UNSIGNED_BYTE,
          buffer);
      checkGlError();
    } finally {
      buffer.position(initialPosition);
    }
  }

  private GlTextureInfo setupOutputTextureAndFbo(int outputWidth, int outputHeight)
      throws GlException {
    @Nullable GlTextureInfo outputTexture = null;
    while (!availableOutputTextures.isEmpty()) {
      GlTextureInfo candidateOutputTexture = availableOutputTextures.removeFirst();
      if (candidateOutputTexture.width == outputWidth
          && candidateOutputTexture.height == outputHeight) {
        outputTexture = candidateOutputTexture;
        break;
      }
      // Release cached textures whose dimensions no longer match the current frame.
      candidateOutputTexture.release();
    }
    if (outputTexture == null) {
      int outputTexId =
          GlUtil.createTexture(
              outputWidth, outputHeight, useHighPrecisionColorComponents(outputColorInfo));
      outputTexture =
          new GlTextureInfo(
              outputTexId,
              /* fboId= */ C.INDEX_UNSET,
              /* rboId= */ C.INDEX_UNSET,
              outputWidth,
              outputHeight);
    }

    try {
      if (fboId == C.INDEX_UNSET) {
        int[] fboIds = new int[1];
        GLES20.glGenFramebuffers(/* n= */ 1, fboIds, /* offset= */ 0);
        checkGlError();
        fboId = fboIds[0];
      }

      GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
      checkGlError();
      GLES20.glFramebufferTexture2D(
          GLES20.GL_FRAMEBUFFER,
          GLES20.GL_COLOR_ATTACHMENT0,
          GLES20.GL_TEXTURE_2D,
          outputTexture.texId,
          /* level= */ 0);
      checkGlError();

      GlUtil.focusFramebufferUsingCurrentContext(fboId, outputWidth, outputHeight);
    } catch (GlException e) {
      try {
        outputTexture.release();
      } catch (GlException ex) {
        e.addSuppressed(ex);
      }
      throw e;
    }
    return outputTexture;
  }

  private void bindProgramAndUniforms(
      GlProgram program, Format inputFormat, int outputWidth, int outputHeight) throws GlException {
    PlaneTexture planeTexture = checkNotNull(this.planeTexture);
    program.use();

    MatrixUtils.populateTransformationMatrix(
        textureTransformMatrix,
        /* bufferWidth= */ planeTexture.width,
        /* bufferHeight= */ inputFormat.height,
        /* formatWidth= */ outputWidth,
        /* formatHeight= */ outputHeight,
        inputFormat.rotationDegrees);
    program.setFloatsUniform("uTexTransformationMatrix", textureTransformMatrix);

    int inputColorSpace =
        inputFormat.colorInfo != null && inputFormat.colorInfo.colorSpace != Format.NO_VALUE
            ? inputFormat.colorInfo.colorSpace
            : C.COLOR_SPACE_BT709;
    program.setIntUniform("uInputColorGamut", inputColorSpace);
    program.setSamplerTexIdUniform("uTexSampler", planeTexture.texId, /* texUnitIndex= */ 0);
    program.bindAttributesAndUniforms();
  }

  private GlTextureFrame buildGlTextureFrame(
      ImagePlanesFrame inputFrame,
      GlTextureInfo outputTexture,
      Executor glExecutor,
      Executor listenerExecutor,
      FrameProcessor.Listener listener) {
    return new GlTextureFrame.Builder(
            outputTexture,
            /* releaseTextureExecutor= */ glExecutor,
            /* releaseTextureCallback= */ info -> {
              @Nullable GlTextureInfo activeTexture = activeOutputTextures.remove(inputFrame);
              if (activeTexture == null) {
                // The frame's GL resources were already reclaimed, by releaseGlResources() after
                // a downstream rejection or by close(). Notifying the listener here would
                // falsely signal that processing succeeded.
                return;
              }
              if (isClosed) {
                try {
                  activeTexture.release();
                } catch (GlException e) {
                  errorConsumer.accept(VideoFrameProcessingException.from(e));
                }
              } else {
                availableOutputTextures.addLast(activeTexture);
              }
              listenerExecutor.execute(
                  () -> listener.onFrameProcessed(inputFrame, /* onCompleteFence= */ null));
            })
        .setPresentationTimeUs(inputFrame.getContentTimeUs())
        .setFormat(
            inputFrame
                .getFormat()
                .buildUpon()
                .setWidth(outputTexture.width)
                .setHeight(outputTexture.height)
                .setColorInfo(outputColorInfo)
                // Reset rotation to 0 because we rotated the frame physically with OpenGL. The
                // pipeline should always receive frames in their intended orientation.
                .setRotationDegrees(0)
                .build())
        .setMetadata(inputFrame.getMetadata())
        .build();
  }

  /**
   * The GL texture holding one uploaded image plane, and the storage currently allocated for it.
   */
  private static final class PlaneTexture {

    /** The texture ID. */
    private final int texId;

    /** The allocated width in pixels. */
    private final int width;

    /** The allocated height in pixels. */
    private final int height;

    /** The allocated GL format. */
    private final int glFormat;

    private PlaneTexture(int texId, int width, int height, int glFormat) {
      this.texId = texId;
      this.width = width;
      this.height = height;
      this.glFormat = glFormat;
    }
  }
}
