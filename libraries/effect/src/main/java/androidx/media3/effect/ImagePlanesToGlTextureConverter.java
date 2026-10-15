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
import static androidx.media3.effect.FrameProcessorUtils.RGBA_8888_BYTES_PER_PIXEL;
import static androidx.media3.effect.FrameProcessorUtils.runAllAndAccumulateExceptions;
import static androidx.media3.effect.FrameProcessorUtils.useHighPrecisionColorComponents;
import static androidx.media3.effect.FrameProcessorUtils.validatePlaneDimensions;
import static androidx.media3.effect.FrameProcessorUtils.validateRgba8888Planes;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.opengl.GLES20;
import androidx.annotation.Nullable;
import androidx.annotation.RawRes;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.ThrowingRunnable;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.ImagePlanesFrame;
import androidx.media3.common.video.ImagePlanesFrame.Plane;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Converts an {@link ImagePlanesFrame} to a {@link GlTextureFrame}.
 *
 * <p>Supports single-plane {@link PixelFormat#RGBA_8888} frames and planar {@link
 * ImageFormat#YUV_420_888} (I420, {@code pixelStride == 1}) frames.
 */
@ExperimentalApi // TODO: b/505721737 Remove once FrameProcessor is production ready.
/* package */ final class ImagePlanesToGlTextureConverter implements FrameToGlTextureConverter {

  private final Context context;
  private final ColorInfo outputColorInfo;
  private final Consumer<VideoFrameProcessingException> errorConsumer;
  private final Map<ImagePlanesFrame, GlTextureInfo> activeOutputTextures;
  private final Deque<GlTextureInfo> availableOutputTextures;

  private @MonotonicNonNull PlaneLayoutRenderer rgbaPlaneLayoutRenderer;
  private @MonotonicNonNull PlaneLayoutRenderer i420PlaneLayoutRenderer;
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

    Format inputFormat = imagePlanesFrame.getFormat();
    PlaneLayoutRenderer planeLayoutRenderer = resolvePlaneLayoutRenderer(inputFormat.pixelFormat);
    planeLayoutRenderer.validateFrame(imagePlanesFrame);

    boolean isRotated = inputFormat.rotationDegrees == 90 || inputFormat.rotationDegrees == 270;
    int outputWidth = isRotated ? inputFormat.height : inputFormat.width;
    int outputHeight = isRotated ? inputFormat.width : inputFormat.height;

    @Nullable GlTextureInfo outputTexture = null;
    try {
      outputTexture = setupOutputTextureAndFbo(outputWidth, outputHeight);
      activeOutputTextures.put(imagePlanesFrame, outputTexture);
      planeLayoutRenderer.render(imagePlanesFrame, outputWidth, outputHeight);
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
    if (rgbaPlaneLayoutRenderer != null) {
      cleanupActions.add(rgbaPlaneLayoutRenderer::release);
    }
    if (i420PlaneLayoutRenderer != null) {
      cleanupActions.add(i420PlaneLayoutRenderer::release);
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

  // TODO(b/531653682): Add support for semi-planar (NV12 and NV21) formats.
  private PlaneLayoutRenderer resolvePlaneLayoutRenderer(int pixelFormat) {
    switch (pixelFormat) {
      case PixelFormat.RGBA_8888:
        if (rgbaPlaneLayoutRenderer == null) {
          rgbaPlaneLayoutRenderer = new RgbaPlaneLayoutRenderer();
        }
        return rgbaPlaneLayoutRenderer;
      case ImageFormat.YUV_420_888:
        if (i420PlaneLayoutRenderer == null) {
          i420PlaneLayoutRenderer = new I420PlaneLayoutRenderer();
        }
        return i420PlaneLayoutRenderer;
      default:
        throw new UnsupportedOperationException("Unsupported pixel format: " + pixelFormat);
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
   * Validates, uploads, and renders the planes of an {@link ImagePlanesFrame} with a specific plane
   * layout into the currently focused output framebuffer.
   */
  private abstract class PlaneLayoutRenderer {

    /** The GL textures holding the uploaded planes of the current frame. */
    /* package */ final @NullableType PlaneTexture[] planeTextures;

    /**
     * The fraction of each plane texture's width that holds visible pixels (excluding row-stride
     * padding).
     */
    /* package */ final float[] planeWidthRatios;

    @Nullable /* package */ GlProgram glProgram;

    @RawRes private final int fragmentShaderResId;
    private final float[] textureTransformMatrix;

    /* package */ PlaneLayoutRenderer(@RawRes int fragmentShaderResId, int planeCount) {
      this.planeTextures = new PlaneTexture[planeCount];
      this.planeWidthRatios = new float[planeCount];
      this.fragmentShaderResId = fragmentShaderResId;
      this.textureTransformMatrix = new float[16];
    }

    /** Validates that {@code planes} match this layout's count, pixel strides, and dimensions. */
    /* package */ abstract void validatePlanes(List<Plane> planes, int width, int height);

    /** Called when {@link #glProgram} is first created. */
    /* package */ void onGlProgramCreated() {}

    /** Uploads {@code planes} into {@link #planeTextures}. */
    /* package */ abstract void uploadPlaneTextures(List<Plane> planes, int width, int height)
        throws GlException;

    /**
     * Returns the buffer width to pass to {@link MatrixUtils#populateTransformationMatrix} for
     * horizontal row-padding cropping.
     */
    /* package */ abstract int getTextureCropBufferWidth(Format inputFormat);

    /** Sets the format-specific uniforms and texture samplers on {@link #glProgram}. */
    /* package */ abstract void setFormatSpecificUniforms(Format inputFormat);

    /* package */ void uploadPlaneToTexture(
        int planeIndex, Plane plane, int width, int height, int glFormat, int bytesPerPixel)
        throws GlException {
      // OpenGL ES 2.0 lacks GL_UNPACK_ROW_LENGTH, so upload the full rowStride width and crop the
      // row padding when sampling.
      int rowStride = plane.getRowStride();
      int textureWidth = rowStride / bytesPerPixel;
      planeWidthRatios[planeIndex] = (float) width / textureWidth;

      @Nullable PlaneTexture planeTexture = planeTextures[planeIndex];
      // Bind before both glTexImage2D and glTexSubImage2D, which operate on the currently bound
      // GL_TEXTURE_2D texture (GlUtil.generateTexture() returns an unbound texture ID).
      int texId = planeTexture != null ? planeTexture.texId : GlUtil.generateTexture();
      GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, texId, GLES20.GL_NEAREST);

      boolean needsTextureAllocation =
          planeTexture == null
              || planeTexture.width != textureWidth
              || planeTexture.height != height;
      if (needsTextureAllocation) {
        // Allocate GPU texture storage on the first frame, or reallocate if plane dimensions
        // change (glTexImage2D automatically releases and replaces any previous texture memory for
        // texId). The pixel contents are always updated in-place below, to avoid per-frame texture
        // memory reallocation overhead.
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
        planeTextures[planeIndex] = new PlaneTexture(texId, textureWidth, height);
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

    private void validateFrame(ImagePlanesFrame frame) {
      Format inputFormat = frame.getFormat();
      checkArgument(
          inputFormat.width > 0 && inputFormat.height > 0,
          "Unsupported frame dimensions: %sx%s",
          inputFormat.width,
          inputFormat.height);
      checkArgument(
          !ColorInfo.isWideColorGamut(inputFormat.colorInfo),
          "Unsupported color space: %s",
          inputFormat.colorInfo);
      validatePlanes(frame.getPlanes(), inputFormat.width, inputFormat.height);
    }

    private void render(ImagePlanesFrame frame, int outputWidth, int outputHeight)
        throws GlException, IOException {
      Format inputFormat = frame.getFormat();
      initializeGlProgram();
      uploadPlanes(frame.getPlanes(), inputFormat.width, inputFormat.height);
      bindProgramAndUniforms(inputFormat, outputWidth, outputHeight);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
      checkGlError();
    }

    private void initializeGlProgram() throws GlException, IOException {
      if (glProgram != null) {
        return;
      }
      GlProgram program =
          new GlProgram(
              context,
              R.raw.vertex_shader_transformation_es2,
              R.raw.color_conversions_es2,
              fragmentShaderResId);
      program.setInternalTexturesRequireNearestSampling(true);
      program.setBufferAttribute(
          "aFramePosition",
          GlUtil.getNormalizedCoordinateBounds(),
          HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
      program.setFloatsUniform("uTransformationMatrix", GlUtil.create4x4IdentityMatrix());
      int outputColorSpace =
          outputColorInfo.colorSpace != Format.NO_VALUE
              ? outputColorInfo.colorSpace
              : C.COLOR_SPACE_BT709;
      program.setIntUniform("uOutputColorGamut", outputColorSpace);
      program.setIntUniform("uOutputColorTransfer", outputColorInfo.colorTransfer);
      glProgram = program;
      onGlProgramCreated();
    }

    private void uploadPlanes(List<Plane> planes, int width, int height) throws GlException {
      // By default, glTexSubImage2D rounds each row's byte length up to a multiple of 4 bytes
      // (GL_UNPACK_ALIGNMENT = 4). Set alignment to 1 byte so 1-byte-per-texel GL_LUMINANCE planes
      // whose rowStride is not a multiple of 4 are read without extra per-row padding.
      GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
      try {
        uploadPlaneTextures(planes, width, height);
      } finally {
        // The GL context is shared with the rest of the pipeline, so restore the GL default.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
      }
    }

    private void bindProgramAndUniforms(Format inputFormat, int outputWidth, int outputHeight)
        throws GlException {
      GlProgram program = checkNotNull(glProgram);
      program.use();

      MatrixUtils.populateTransformationMatrix(
          textureTransformMatrix,
          /* bufferWidth= */ getTextureCropBufferWidth(inputFormat),
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

      setFormatSpecificUniforms(inputFormat);

      program.bindAttributesAndUniforms();
    }

    private void release() throws VideoFrameProcessingException {
      List<ThrowingRunnable<?>> cleanupActions = new ArrayList<>();
      if (glProgram != null) {
        GlProgram programToDelete = glProgram;
        glProgram = null;
        cleanupActions.add(programToDelete::delete);
      }
      for (int i = 0; i < planeTextures.length; i++) {
        @Nullable PlaneTexture planeTexture = planeTextures[i];
        if (planeTexture != null) {
          int texId = planeTexture.texId;
          planeTextures[i] = null;
          cleanupActions.add(() -> GlUtil.deleteTexture(texId));
        }
      }
      runAllAndAccumulateExceptions(cleanupActions.toArray(new ThrowingRunnable<?>[0]));
    }
  }

  private final class RgbaPlaneLayoutRenderer extends PlaneLayoutRenderer {

    private RgbaPlaneLayoutRenderer() {
      super(
          R.raw.fragment_shader_transformation_sdr_internal_color_conversion_es2,
          /* planeCount= */ 1);
    }

    @Override
    /* package */ void validatePlanes(List<Plane> planes, int width, int height) {
      validateRgba8888Planes(planes, width, height);
      // OpenGL can only specify row widths in texels, not bytes, and uploadPlaneToTexture uploads
      // the padded rows as rowStride / RGBA_8888_BYTES_PER_PIXEL texels. rowStride must therefore
      // be a multiple of the 4-byte texel size.
      // TODO(b/573528333): Investigate whether a rowStride that is not a multiple of
      // RGBA_8888_BYTES_PER_PIXEL needs to be supported.
      int rowStride = planes.get(0).getRowStride();
      checkArgument(
          rowStride % RGBA_8888_BYTES_PER_PIXEL == 0,
          "rowStride (%s) must be a multiple of %s",
          rowStride,
          RGBA_8888_BYTES_PER_PIXEL);
    }

    @Override
    /* package */ void onGlProgramCreated() {
      checkNotNull(glProgram).setFloatsUniform("uRgbMatrix", GlUtil.create4x4IdentityMatrix());
    }

    @Override
    /* package */ void uploadPlaneTextures(List<Plane> planes, int width, int height)
        throws GlException {
      uploadPlaneToTexture(
          /* planeIndex= */ 0,
          planes.get(0),
          width,
          height,
          GLES20.GL_RGBA,
          RGBA_8888_BYTES_PER_PIXEL);
    }

    @Override
    /* package */ int getTextureCropBufferWidth(Format inputFormat) {
      // For single-plane RGBA, populateTransformationMatrix crops horizontal row padding by scaling
      // X by (inputFormat.width / bufferWidth).
      return checkNotNull(planeTextures[0]).width;
    }

    @Override
    /* package */ void setFormatSpecificUniforms(Format inputFormat) {
      checkNotNull(glProgram)
          .setSamplerTexIdUniform(
              "uTexSampler", checkNotNull(planeTextures[0]).texId, /* texUnitIndex= */ 0);
    }
  }

  private final class I420PlaneLayoutRenderer extends PlaneLayoutRenderer {

    private static final int LUMA_BYTES_PER_PIXEL = 1;
    private static final int I420_CHROMA_PLANE_STRIDE = 1;

    private I420PlaneLayoutRenderer() {
      super(R.raw.fragment_shader_yuv_planar_es2, /* planeCount= */ 3);
    }

    @Override
    /* package */ void validatePlanes(List<Plane> planes, int width, int height) {
      checkArgument(
          planes.size() == 3
              && planes.get(0).getPixelStride() == LUMA_BYTES_PER_PIXEL
              && planes.get(1).getPixelStride() == I420_CHROMA_PLANE_STRIDE
              && planes.get(2).getPixelStride() == I420_CHROMA_PLANE_STRIDE,
          "Expected 3 planes with luma pixelStride %s and chroma pixelStride %s for I420",
          LUMA_BYTES_PER_PIXEL,
          I420_CHROMA_PLANE_STRIDE);
      validatePlaneDimensions(planes.get(0), width, height);
      // Round up odd dimensions so the subsampled UV planes cover the full Y plane.
      int chromaWidth = (width + 1) / 2;
      int chromaHeight = (height + 1) / 2;
      validatePlaneDimensions(planes.get(1), chromaWidth, chromaHeight);
      validatePlaneDimensions(planes.get(2), chromaWidth, chromaHeight);
    }

    @Override
    /* package */ void uploadPlaneTextures(List<Plane> planes, int width, int height)
        throws GlException {
      uploadPlaneToTexture(
          /* planeIndex= */ 0,
          planes.get(0),
          width,
          height,
          GLES20.GL_LUMINANCE,
          LUMA_BYTES_PER_PIXEL);
      int chromaWidth = (width + 1) / 2;
      int chromaHeight = (height + 1) / 2;
      uploadPlaneToTexture(
          /* planeIndex= */ 1,
          planes.get(1),
          chromaWidth,
          chromaHeight,
          GLES20.GL_LUMINANCE,
          I420_CHROMA_PLANE_STRIDE);
      uploadPlaneToTexture(
          /* planeIndex= */ 2,
          planes.get(2),
          chromaWidth,
          chromaHeight,
          GLES20.GL_LUMINANCE,
          I420_CHROMA_PLANE_STRIDE);
    }

    @Override
    /* package */ int getTextureCropBufferWidth(Format inputFormat) {
      // Multi-plane YUV formats crop each plane separately in the fragment shader via
      // uPlaneWidthRatios (since luma and chroma padding ratios can differ), so pass
      // inputFormat.width to apply no crop in uTexTransformationMatrix.
      return inputFormat.width;
    }

    @Override
    /* package */ void setFormatSpecificUniforms(Format inputFormat) {
      GlProgram program = checkNotNull(glProgram);
      boolean isFullRange =
          inputFormat.colorInfo != null && inputFormat.colorInfo.colorRange == C.COLOR_RANGE_FULL;
      program.setIntUniform("uIsInputColorRangeFull", isFullRange ? 1 : 0);
      program.setFloatsUniform("uPlaneWidthRatios", planeWidthRatios);
      program.setSamplerTexIdUniform(
          "uTexY", checkNotNull(planeTextures[0]).texId, /* texUnitIndex= */ 0);
      program.setSamplerTexIdUniform(
          "uTexU", checkNotNull(planeTextures[1]).texId, /* texUnitIndex= */ 1);
      program.setSamplerTexIdUniform(
          "uTexV", checkNotNull(planeTextures[2]).texId, /* texUnitIndex= */ 2);
    }
  }

  /**
   * The GL texture holding one uploaded image plane, and the dimensions currently allocated for it.
   */
  private static final class PlaneTexture {

    /** The texture ID. */
    private final int texId;

    /** The allocated width in pixels. */
    private final int width;

    /** The allocated height in pixels. */
    private final int height;

    private PlaneTexture(int texId, int width, int height) {
      this.texId = texId;
      this.width = width;
      this.height = height;
    }
  }
}
