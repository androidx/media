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

import android.content.Context;
import android.hardware.HardwareBuffer;
import android.opengl.EGLDisplay;
import android.opengl.GLES20;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.Log;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.effect.FrameProcessorUtils.EglImageTextureWrapper;
import com.google.common.collect.ImmutableList;
import java.util.concurrent.Executor;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** A {@link GlTextureFrameConsumer} that writes the input to a {@link FrameWriter}. */
@ExperimentalApi // TODO: b/505721737 Remove once FrameProcessor is production ready.
@RequiresApi(26)
/* package */ final class FrameWriterGlTextureFrameConsumer implements GlTextureFrameConsumer {

  private static final String TAG = "FrameWriterGlTexCons";

  private final Context context;
  private final FrameWriter frameWriter;
  private final HardwareBufferJniWrapper hardwareBufferJniWrapper;

  @Nullable private DefaultShaderProgram defaultShaderProgram;
  private @MonotonicNonNull EGLDisplay eglDisplay;
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
      frameWriter.configure(inputFrame.format, USAGE_GPU_COLOR_OUTPUT);
      isFrameWriterConfigured = true;
    }

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

      if (defaultShaderProgram == null) {
        defaultShaderProgram =
            DefaultShaderProgram.create(
                context,
                /* matrixTransformations= */ ImmutableList.of(),
                /* rgbMatrices= */ ImmutableList.of(),
                /* useHdr= */ false);
      }
      defaultShaderProgram.drawFrame(inputFrame.glTextureInfo.texId, inputFrame.presentationTimeUs);
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
    if (defaultShaderProgram != null) {
      defaultShaderProgram.release();
    }
  }
}
