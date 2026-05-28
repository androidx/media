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

import static android.media.MediaFormat.COLOR_TRANSFER_SDR_VIDEO;
import static androidx.media3.effect.FrameProcessorUtils.createAndBindEglImage;
import static androidx.media3.effect.FrameProcessorUtils.generateSyncFences;
import static androidx.media3.effect.FrameProcessorUtils.releaseEglImageTexture;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.getFirst;

import android.content.Context;
import android.hardware.HardwareBuffer;
import android.opengl.EGLDisplay;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.Log;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.effect.FrameProcessorUtils.EglImageTextureWrapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.Executor;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

@ExperimentalApi // TODO: b/505721737 Remove once FrameProcessor is production ready.
@RequiresApi(26)
/* package */ final class HardwareBufferToGlTextureConverter
    implements DefaultGlFrameProcessor.HardwareBufferConverter {

  private static final String TAG = "HB2GLConverter";

  private final Context context;
  private final HardwareBufferJniWrapper hardwareBufferJniWrapper;
  private final Consumer<VideoFrameProcessingException> errorConsumer;
  private final HashMap<HardwareBufferFrame, EglImageTextureWrapper> activeEglImageTextureWrappers;

  @Nullable private GlProgram externalCopyGlProgram;
  private @MonotonicNonNull EGLDisplay eglDisplay;

  HardwareBufferToGlTextureConverter(
      Context context,
      HardwareBufferJniWrapper hardwareBufferJniWrapper,
      Consumer<VideoFrameProcessingException> errorConsumer) {
    this.context = context;
    this.hardwareBufferJniWrapper = hardwareBufferJniWrapper;
    this.errorConsumer = errorConsumer;
    this.activeEglImageTextureWrappers = new HashMap<>();
  }

  @Override
  public GlTextureFrame convert(
      HardwareBufferFrame hardwareBufferFrame,
      Executor glExecutor,
      Executor listenerExecutor,
      FrameProcessor.Listener listener)
      throws VideoFrameProcessingException {

    HardwareBuffer hardwareBuffer = checkNotNull(hardwareBufferFrame.getHardwareBuffer());
    boolean isExternalTexture = hardwareBuffer.getFormat() != HardwareBuffer.RGBA_8888;
    int outputTexId;
    @Nullable EglImageTextureWrapper eglImageTextureWrapper = null;
    Format inputFormat = hardwareBufferFrame.getFormat();
    int outputWidth = inputFormat.width;
    int outputHeight = inputFormat.height;
    try {
      if (eglDisplay == null) {
        eglDisplay = GlUtil.getDefaultEglDisplay();
      }
      eglImageTextureWrapper =
          createAndBindEglImage(
              eglDisplay,
              hardwareBuffer,
              hardwareBufferJniWrapper,
              /* target= */ isExternalTexture
                  ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES
                  : GLES20.GL_TEXTURE_2D,
              /* writesToBoundImage= */ false);
      int texId = eglImageTextureWrapper.texId;

      int internalTexId = C.INDEX_UNSET;
      if (isExternalTexture) {
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
          externalCopyGlProgram.setIntUniform("uOutputColorTransfer", COLOR_TRANSFER_SDR_VIDEO);
        }
        internalTexId =
            GlUtil.createTexture(
                outputWidth, outputHeight, /* useHighPrecisionColorComponents= */ false);

        int internalFboId = GlUtil.createFboForTexture(internalTexId);
        GlUtil.focusFramebufferUsingCurrentContext(internalFboId, outputWidth, outputHeight);

        checkState(externalCopyGlProgram != null);
        externalCopyGlProgram.use();
        externalCopyGlProgram.setSamplerTexIdUniform("uTexSampler", texId, /* texUnitIndex= */ 0);
        externalCopyGlProgram.bindAttributesAndUniforms();
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
        GlUtil.checkGlError();
        GlUtil.deleteFbo(internalFboId);
      }
      outputTexId = isExternalTexture ? internalTexId : texId;
      eglImageTextureWrapper = eglImageTextureWrapper.withOutputTexId(outputTexId);
    } catch (GlException | IOException e) {
      if (eglImageTextureWrapper != null && isExternalTexture) {
        try {
          releaseEglImageTexture(eglImageTextureWrapper, hardwareBufferJniWrapper);
        } catch (GlException exception) {
          Log.w(TAG, "Failed to release EGLImage during error recovery", exception);
        }
      }
      throw VideoFrameProcessingException.from(e);
    }

    activeEglImageTextureWrappers.put(hardwareBufferFrame, eglImageTextureWrapper);
    return new GlTextureFrame.Builder(
            new GlTextureInfo(
                outputTexId,
                /* fboId= */ C.INDEX_UNSET,
                /* rboId= */ C.INDEX_UNSET,
                outputWidth,
                outputHeight),
            /* releaseTextureExecutor= */ glExecutor,
            /* releaseTextureCallback= */ info -> {
              try {
                EglImageTextureWrapper activeWrapper =
                    activeEglImageTextureWrappers.remove(hardwareBufferFrame);
                if (activeWrapper != null) {
                  releaseEglImageTexture(activeWrapper, hardwareBufferJniWrapper);
                }
                // The GlTextureFrame is released by the first effect in the chain after it has
                // finished reading from it. Generate a sync fence here to signal when the input
                // frame is no longer needed.
                @Nullable SyncFenceWrapper glReadFence = getFirst(generateSyncFences(1), null);
                if (glReadFence == null) {
                  GLES20.glFinish();
                }
                // TODO: b/505721737 - The input should be released only after writing to the
                //  output surface, in previewing.
                listenerExecutor.execute(
                    () -> listener.onFrameProcessed(hardwareBufferFrame, glReadFence));
              } catch (GlException e) {
                errorConsumer.accept(VideoFrameProcessingException.from(e));
              }
            })
        .setPresentationTimeUs(hardwareBufferFrame.getContentTimeUs())
        .setFormat(hardwareBufferFrame.getFormat())
        .setMetadata(hardwareBufferFrame.getMetadata())
        .build();
  }

  @Override
  public void releaseGlResources(HardwareBufferFrame hardwareBufferFrame)
      throws VideoFrameProcessingException {
    EglImageTextureWrapper wrapper = activeEglImageTextureWrappers.remove(hardwareBufferFrame);
    if (wrapper == null) {
      return;
    }
    try {
      releaseEglImageTexture(wrapper, hardwareBufferJniWrapper);
    } catch (GlException e) {
      throw VideoFrameProcessingException.from(e);
    }
  }

  @Override
  public void close() throws VideoFrameProcessingException {
    try {
      if (externalCopyGlProgram != null) {
        externalCopyGlProgram.delete();
        externalCopyGlProgram = null;
      }
      for (EglImageTextureWrapper wrapper : activeEglImageTextureWrappers.values()) {
        releaseEglImageTexture(wrapper, hardwareBufferJniWrapper);
      }
      activeEglImageTextureWrappers.clear();
    } catch (GlException e) {
      throw VideoFrameProcessingException.from(e);
    }
  }
}
