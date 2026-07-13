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

import static androidx.media3.common.video.Frame.USAGE_GPU_COLOR_OUTPUT;
import static androidx.media3.effect.FrameProcessorUtils.createAndBindEglImage;
import static androidx.media3.effect.FrameProcessorUtils.generateSyncFences;
import static androidx.media3.effect.FrameProcessorUtils.releaseEglImageTexture;
import static androidx.media3.effect.FrameProcessorUtils.waitAndCloseFence;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.hardware.HardwareBuffer;
import android.opengl.EGLDisplay;
import android.opengl.GLES20;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
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
      outputFormat = establishOutputFormat(inputFrame.format);
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
      eglImageTextureWrapper =
          createAndBindEglImage(
              eglDisplay,
              outputHardwareBuffer,
              hardwareBufferJniWrapper,
              /* target= */ GLES20.GL_TEXTURE_2D,
              /* writesToBoundImage= */ true);

      GlUtil.clearFocusedBuffersOpaque();
      checkNotNull(defaultShaderProgram)
          .drawFrame(inputFrame.glTextureInfo.texId, inputFrame.presentationTimeUs);
      GlUtil.checkGlError();
      ImmutableList<SyncFenceWrapper> fences = generateSyncFences(/* count= */ 2);
      if (fences.size() == 2) {
        glReadCompleteFence = fences.get(0);
        glWriteCompleteFence = fences.get(1);
      }
    } catch (GlException e) {
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
    // Release frame writer first in case releasing the shader program throw
    frameWriter.close();
    if (defaultShaderProgram != null) {
      defaultShaderProgram.release();
      defaultShaderProgram = null;
    }
  }

  /** Establishes the output format based on the first frame. */
  private Format establishOutputFormat(Format inputFormat) {
    int rotationDegrees = calculateOutputRotationDegrees(inputFormat);
    int outputWidth =
        (rotationDegrees == 90 || rotationDegrees == 270) ? inputFormat.height : inputFormat.width;
    int outputHeight =
        (rotationDegrees == 90 || rotationDegrees == 270) ? inputFormat.width : inputFormat.height;
    return updateFormat(
        inputFormat,
        outputWidth,
        outputHeight,
        // Sets the degrees that the player needs to rotate. If we rotated 90 degrees, the player
        // needs to rotate -90 degrees, which is equivalent to rotating it 270 degrees.
        /* rotationDegrees= */ (360 - rotationDegrees) % 360);
  }

  /** Reconfigures the shader program if the input size or color space changed. */
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

    if (defaultShaderProgram != null) {
      defaultShaderProgram.release();
      defaultShaderProgram = null;
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
            /* useHdr= */ ColorInfo.isTransferHdr(inputColorInfo));

    inputSize = new Size(inputFormat.width, inputFormat.height);
    this.inputColorInfo = inputColorInfo;
    Size unusedSize =
        defaultShaderProgram.configure(
            inputFrame.glTextureInfo.width, inputFrame.glTextureInfo.height);
  }

  private int calculateOutputRotationDegrees(Format format) {
    if (format.width >= format.height) {
      // Input is landscape, no rotation needed.
      return 0;
    }

    if (frameWriter.getInfo().isSupported(format, OUTPUT_USAGE)) {
      // Portrait is supported, no rotation needed.
      return 0;
    }

    // If frameWriter doesn't support portrait, try rotating the input by 90 or 270 degrees to swap
    // to landscape dimensions supported by the encoder.
    int rotatedWidth = format.height;
    int rotatedHeight = format.width;
    Format formatRotate90 =
        updateFormat(format, rotatedWidth, rotatedHeight, /* rotationDegrees= */ 90);
    if (frameWriter.getInfo().isSupported(formatRotate90, OUTPUT_USAGE)) {
      return 90;
    }

    Format formatRotate270 =
        updateFormat(format, rotatedWidth, rotatedHeight, /* rotationDegrees= */ 270);
    if (frameWriter.getInfo().isSupported(formatRotate270, OUTPUT_USAGE)) {
      return 270;
    }

    // Fallback if nothing is supported.
    return 0;
  }

  private static Format updateFormat(Format format, int width, int height, int rotationDegrees) {
    return format
        .buildUpon()
        .setWidth(width)
        .setHeight(height)
        .setRotationDegrees(rotationDegrees)
        .build();
  }
}
