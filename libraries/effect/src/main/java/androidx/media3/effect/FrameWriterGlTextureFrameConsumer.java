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

import static androidx.media3.common.ColorInfo.isWideColorGamut;
import static androidx.media3.common.video.Frame.USAGE_GPU_COLOR_OUTPUT;
import static androidx.media3.effect.DefaultGlFrameProcessor.BT2020_HLG;
import static androidx.media3.effect.DefaultGlFrameProcessor.BT709_SRGB;
import static androidx.media3.effect.FrameProcessorUtils.createAndBindEglImage;
import static androidx.media3.effect.FrameProcessorUtils.releaseEglImageTexture;
import static androidx.media3.effect.FrameProcessorUtils.runAllAndAccumulateExceptions;
import static androidx.media3.effect.FrameProcessorUtils.useHighPrecisionColorComponents;
import static androidx.media3.effect.FrameProcessorUtils.waitAndCloseFence;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.hardware.HardwareBuffer;
import android.opengl.EGLDisplay;
import android.opengl.GLES20;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Size;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.effect.FrameProcessorUtils.EglImageTextureWrapper;
import com.google.common.collect.ImmutableList;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Writes input texture frames to a {@link FrameWriter}. */
@ExperimentalApi // TODO: b/505721737 Remove once FrameProcessor is production ready.
@RequiresApi(26)
/* package */ final class FrameWriterGlTextureFrameConsumer implements GlTextureFrameConsumer {

  private static final String TAG = "FrameWriterGlTexCons";
  private static final long OUTPUT_USAGE = USAGE_GPU_COLOR_OUTPUT;

  private final Context context;
  private final FrameWriter frameWriter;
  private final HardwareBufferJniWrapper hardwareBufferJniWrapper;

  @Nullable private DefaultShaderProgram defaultShaderProgram;
  @Nullable private ColorConversionShaderProgram colorConversionShaderProgram;
  @Nullable private GlTextureInfo colorConversionOutputTexture;
  private @MonotonicNonNull EGLDisplay eglDisplay;
  private @MonotonicNonNull Size inputSize;
  private @MonotonicNonNull ColorInfo inputColorInfo;
  private @MonotonicNonNull Format outputFormat;
  private boolean isFrameWriterConfigured;

  FrameWriterGlTextureFrameConsumer(
      Context context, FrameWriter frameWriter, HardwareBufferJniWrapper hardwareBufferJniWrapper) {
    this.context = context;
    this.frameWriter = frameWriter;
    this.hardwareBufferJniWrapper = hardwareBufferJniWrapper;
  }

  /** The method runs on the GL thread. */
  @Override
  public boolean queue(
      GlTextureFrame inputFrame, Executor listenerExecutor, Runnable wakeupListener)
      throws VideoFrameProcessingException {
    if (!isFrameWriterConfigured) {
      @Nullable Format resolvedOutputFormat = resolveOutputFormat(inputFrame.format);
      if (resolvedOutputFormat == null) {
        throw new VideoFrameProcessingException(inputFrame.format.toString());
      }
      outputFormat = resolvedOutputFormat;
      frameWriter.configure(outputFormat, OUTPUT_USAGE);
      isFrameWriterConfigured = true;
    }

    maybeReconfigureShader(inputFrame);

    AsyncFrame asyncFrame = frameWriter.dequeueInputFrame(listenerExecutor, wakeupListener);
    if (asyncFrame == null) {
      return false;
    }
    if (!waitAndCloseFence(asyncFrame)) {
      GLES20.glFinish();
    }

    checkArgument(asyncFrame.frame instanceof HardwareBufferFrame);
    HardwareBufferFrame outputHardwareBufferFrame = (HardwareBufferFrame) asyncFrame.frame;
    HardwareBuffer outputHardwareBuffer = outputHardwareBufferFrame.getHardwareBuffer();

    @Nullable SyncFenceWrapper glWriteCompleteFence = null;
    @Nullable SyncFenceWrapper glReadCompleteFence = null;
    @Nullable EglImageTextureWrapper eglImageTextureWrapper = null;
    try {
      if (eglDisplay == null) {
        eglDisplay = GlUtil.getDefaultEglDisplay();
      }
      int currentTexId = inputFrame.glTextureInfo.texId;
      if (colorConversionShaderProgram != null) {
        // Convert into the intermediate texture first. createAndBindEglImage below focuses the
        // output buffer, which the output pass then draws into.
        GlTextureInfo conversionOutput = checkNotNull(colorConversionOutputTexture);
        GlUtil.focusFramebufferUsingCurrentContext(
            conversionOutput.fboId, conversionOutput.width, conversionOutput.height);
        checkNotNull(colorConversionShaderProgram)
            .drawFrame(currentTexId, inputFrame.presentationTimeUs);
        currentTexId = conversionOutput.texId;
      }
      eglImageTextureWrapper =
          createAndBindEglImage(
              eglDisplay,
              outputHardwareBuffer,
              hardwareBufferJniWrapper,
              /* target= */ GLES20.GL_TEXTURE_2D,
              /* writesToBoundImage= */ true);

      GlUtil.clearFocusedBuffersOpaque();
      checkNotNull(defaultShaderProgram).drawFrame(currentTexId, inputFrame.presentationTimeUs);
      GlUtil.checkGlError();
      ImmutableList<SyncFenceWrapper> fences = GlUtil.createSyncFences(/* count= */ 2);
      if (fences.size() == 2) {
        glReadCompleteFence = fences.get(0);
        glWriteCompleteFence = fences.get(1);
      }
    } catch (GlException | VideoFrameProcessingException e) {
      if (eglImageTextureWrapper != null) {
        try {
          releaseEglImageTexture(eglImageTextureWrapper, hardwareBufferJniWrapper);
        } catch (GlException exception) {
          Log.w(TAG, "Failed to release EGLImage during error recovery", exception);
        }
      }
      throw VideoFrameProcessingException.from(e);
    }
    outputHardwareBufferFrame =
        outputHardwareBufferFrame
            .buildUpon()
            .setContentTimeUs(inputFrame.presentationTimeUs)
            .build();
    if (glWriteCompleteFence == null || glReadCompleteFence == null) {
      GLES20.glFinish();
    }
    frameWriter.queueInputFrame(outputHardwareBufferFrame, glWriteCompleteFence);
    inputFrame.release(/* releaseFence= */ glReadCompleteFence);
    try {
      releaseEglImageTexture(eglImageTextureWrapper, hardwareBufferJniWrapper);
    } catch (GlException e) {
      throw VideoFrameProcessingException.from(e);
    }
    return true;
  }

  @Override
  public void signalEndOfStream() {
    frameWriter.signalEndOfStream();
  }

  @Override
  public void close() throws VideoFrameProcessingException {
    releaseGlResources();
  }

  /** Resolves the output format based on the first frame, or {@code null} if none is supported. */
  @Nullable
  private Format resolveOutputFormat(Format inputFormat) {
    ColorInfo outputColorInfo = resolveOutputColorInfo(checkNotNull(inputFormat.colorInfo));
    // This only sets the tags of the output format, the pixel values don't change. SDR pixels are
    // sRGB encoded, which is preferred for RGB content throughout Android, even where it's only
    // labeled "RGB". Some encoders don't accept an sRGB tag, so SDR output is tagged with the SDR
    // (BT.709) transfer instead. Encoders output limited range for both SDR and HDR.
    // TODO(b/545572413): Allow converting outputting to other gamut, for example BT.601.
    ColorInfo frameWriterColorInfo =
        isWideColorGamut(outputColorInfo)
            ? outputColorInfo.buildUpon().setColorRange(C.COLOR_RANGE_LIMITED).build()
            : ColorInfo.SDR_BT709_LIMITED;

    @Nullable
    Format supportedFormat =
        findSupportedFormatForSize(
            inputFormat.width, inputFormat.height, inputFormat.frameRate, frameWriterColorInfo);
    if (supportedFormat != null) {
      return supportedFormat;
    }

    // TODO: b/570487705 - Let FrameWriter.Info return a supported Format (including larger encoder
    //   alignments such as 16 pixels) instead of probing aligned and rotated candidates here.
    int alignedWidth = alignToEven(inputFormat.width);
    int alignedHeight = alignToEven(inputFormat.height);
    if (alignedWidth != inputFormat.width || alignedHeight != inputFormat.height) {
      return findSupportedFormatForSize(
          alignedWidth, alignedHeight, inputFormat.frameRate, frameWriterColorInfo);
    }

    return null;
  }

  @Nullable
  private Format findSupportedFormatForSize(
      int width, int height, float frameRate, ColorInfo colorInfo) {
    Format unrotatedFormat =
        new Format.Builder()
            .setWidth(width)
            .setHeight(height)
            .setFrameRate(frameRate)
            .setColorInfo(colorInfo)
            .setRotationDegrees(0)
            .build();
    if (frameWriter.getInfo().isSupported(unrotatedFormat, OUTPUT_USAGE)) {
      return unrotatedFormat;
    }

    // TODO: b/570487705 - Let FrameWriter.Info return a supported Format instead of probing a
    //   rotated candidate here.
    // Pass the rotation to the FrameWriter so the muxer can write it as container metadata; the
    // FrameWriter itself ignores rotation when encoding. We rotate the frame by 90 degrees, so the
    // player needs to rotate it by -90 (270) degrees to restore the original orientation.
    Format rotatedFormat =
        new Format.Builder()
            .setWidth(height)
            .setHeight(width)
            .setFrameRate(frameRate)
            .setColorInfo(colorInfo)
            .setRotationDegrees(270)
            .build();
    if (frameWriter.getInfo().isSupported(rotatedFormat, OUTPUT_USAGE)) {
      return rotatedFormat;
    }

    return null;
  }

  /** Reconfigures the shader programs if the input size or color space changed. */
  private void maybeReconfigureShader(GlTextureFrame inputFrame)
      throws VideoFrameProcessingException {
    Format inputFormat = inputFrame.format;
    // The frames in the GL pipeline should always be in the intended orientation.
    checkArgument(inputFormat.rotationDegrees == 0);
    ColorInfo inputColorInfo = checkNotNull(inputFormat.colorInfo);
    if (inputSize != null
        && inputFormat.width == inputSize.getWidth()
        && inputFormat.height == inputSize.getHeight()
        && Objects.equals(inputColorInfo, this.inputColorInfo)) {
      return;
    }

    releaseGlResources();

    int inputTextureWidth = inputFrame.glTextureInfo.width;
    int inputTextureHeight = inputFrame.glTextureInfo.height;
    ColorInfo outputColorInfo = resolveOutputColorInfo(inputColorInfo);
    if (!outputColorInfo.equals(inputColorInfo)) {
      // TODO: b/565711655 - Do the color conversion and the output transformations in one pass,
      //  to avoid rendering to an intermediate texture.
      colorConversionShaderProgram =
          new ColorConversionShaderProgram(context, inputColorInfo, outputColorInfo);
      Size conversionOutputSize =
          colorConversionShaderProgram.configure(inputTextureWidth, inputTextureHeight);
      colorConversionOutputTexture =
          createTextureWithFbo(
              conversionOutputSize.getWidth(),
              conversionOutputSize.getHeight(),
              useHighPrecisionColorComponents(outputColorInfo));
    }

    // Force physical rotation to match the logical rotation of the established output format.
    // The output format stores the rotation that player applies. We convert it back to the degrees
    // the pipeline needs to rotate.
    int rotationDegrees = (360 - checkNotNull(outputFormat).rotationDegrees) % 360;

    // Flip OpenGL coordinate system back and rotate.
    GlMatrixTransformation flipAndRotate =
        new ScaleAndRotateTransformation.Builder()
            .setScale(/* scaleX= */ 1f, /* scaleY= */ -1f)
            .setRotationDegrees(rotationDegrees)
            .build();

    ImmutableList.Builder<GlMatrixTransformation> transformationsBuilder = ImmutableList.builder();
    transformationsBuilder
        .add(flipAndRotate)
        .add(
            Presentation.createForWidthAndHeight(
                outputFormat.width, outputFormat.height, Presentation.LAYOUT_SCALE_TO_FIT));

    defaultShaderProgram =
        DefaultShaderProgram.create(
            context,
            /* matrixTransformations= */ transformationsBuilder.build(),
            /* rgbMatrices= */ ImmutableList.of(),
            /* useHdr= */ ColorInfo.isTransferHdr(outputColorInfo));

    inputSize = new Size(inputFormat.width, inputFormat.height);
    this.inputColorInfo = inputColorInfo;
    Size unusedSize = defaultShaderProgram.configure(inputTextureWidth, inputTextureHeight);
  }

  private void releaseGlResources() throws VideoFrameProcessingException {
    @Nullable DefaultShaderProgram defaultShaderProgram = this.defaultShaderProgram;
    @Nullable
    ColorConversionShaderProgram colorConversionShaderProgram = this.colorConversionShaderProgram;
    @Nullable GlTextureInfo colorConversionOutputTexture = this.colorConversionOutputTexture;
    this.defaultShaderProgram = null;
    this.colorConversionShaderProgram = null;
    this.colorConversionOutputTexture = null;
    runAllAndAccumulateExceptions(
        () -> {
          if (defaultShaderProgram != null) {
            defaultShaderProgram.release();
          }
        },
        () -> {
          if (colorConversionShaderProgram != null) {
            colorConversionShaderProgram.release();
          }
        },
        () -> {
          if (colorConversionOutputTexture != null) {
            colorConversionOutputTexture.release();
          }
        });
  }

  private static int alignToEven(int size) {
    if (size % 2 == 0) {
      return size;
    }
    // Prefer multiples of 10 (for example, 1081 -> 1080), matching EncoderUtil.alignResolution.
    return size > 1 && size % 10 == 1 ? size - 1 : size + 1;
  }

  /** Creates a texture and an FBO for it, deleting the texture if the FBO can't be created. */
  private static GlTextureInfo createTextureWithFbo(
      int width, int height, boolean useHighPrecisionColorComponents)
      throws VideoFrameProcessingException {
    int texId = C.INDEX_UNSET;
    try {
      texId = GlUtil.createTexture(width, height, useHighPrecisionColorComponents);
      return new GlTextureInfo(
          texId, GlUtil.createFboForTexture(texId), /* rboId= */ C.INDEX_UNSET, width, height);
    } catch (GlException e) {
      if (texId != C.INDEX_UNSET) {
        try {
          GlUtil.deleteTexture(texId);
        } catch (GlException deleteException) {
          e.addSuppressed(deleteException);
        }
      }
      throw VideoFrameProcessingException.from(e);
    }
  }

  /**
   * Returns the electrical {@link ColorInfo} that frames arriving in {@code inputColorInfo} are
   * written in.
   *
   * <p>Frames in a linear color space are converted to HLG for BT.2020, and to sRGB for BT.709.
   * Frames that are already electrical, for example because a {@link ColorConversion} was applied,
   * are written unchanged.
   */
  private static ColorInfo resolveOutputColorInfo(ColorInfo inputColorInfo) {
    if (inputColorInfo.colorTransfer != C.COLOR_TRANSFER_LINEAR) {
      return inputColorInfo;
    }
    return isWideColorGamut(inputColorInfo) ? BT2020_HLG : BT709_SRGB;
  }

  @Override
  public boolean isOutputFormatSupported(Format format) {
    // TODO(b/545572413): Check for color space support and fallback to SDR if necessary.
    if (!frameWriter.getInfo().isSupported(format, OUTPUT_USAGE)) {
      return false;
    }
    try {
      if (format.colorInfo != null
          && !GlUtil.isColorTransferSupported(format.colorInfo.colorTransfer)) {
        return false;
      }
    } catch (GlException e) {
      return false;
    }
    return true;
  }
}
