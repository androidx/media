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

import static androidx.media3.effect.FrameProcessorUtils.createAndBindEglImage;
import static androidx.media3.effect.FrameProcessorUtils.generateSyncFences;
import static androidx.media3.effect.FrameProcessorUtils.releaseEglImageTexture;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.getFirst;

import android.content.Context;
import android.hardware.HardwareBuffer;
import android.opengl.EGLDisplay;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.Log;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.effect.FrameProcessorUtils.EglImageTextureWrapper;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

@ExperimentalApi // TODO: b/505721737 Remove once FrameProcessor is production ready.
@RequiresApi(26)
/* package */ final class HardwareBufferToGlTextureConverter
    implements DefaultGlFrameProcessor.HardwareBufferConverter {

  private static final String TAG = "HB2GLConverter";

  private final Context context;
  private final HardwareBufferJniWrapper hardwareBufferJniWrapper;
  private final ColorInfo outputColorInfo;
  private final Consumer<VideoFrameProcessingException> errorConsumer;
  private final Map<HardwareBufferFrame, EglImageTextureWrapper> activeEglImageTextureWrappers;

  @Nullable private GlTextureCopier glTextureCopier;
  private @MonotonicNonNull EGLDisplay eglDisplay;
  private final float[] textureTransformMatrix;

  HardwareBufferToGlTextureConverter(
      Context context,
      HardwareBufferJniWrapper hardwareBufferJniWrapper,
      ColorInfo outputColorInfo,
      Consumer<VideoFrameProcessingException> errorConsumer) {
    this.context = context;
    this.hardwareBufferJniWrapper = hardwareBufferJniWrapper;
    this.outputColorInfo = outputColorInfo;
    this.errorConsumer = errorConsumer;
    this.activeEglImageTextureWrappers = new HashMap<>();
    this.textureTransformMatrix = new float[16];
  }

  /**
   * Converts a {@link HardwareBufferFrame} to a {@link GlTextureFrame}.
   *
   * <p>The release callback in the returned {@link GlTextureFrame} will automatically release the
   * underlying {@link HardwareBuffer}.
   */
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
    int internalTexId = C.INDEX_UNSET;
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

      ColorInfo inputColorInfo =
          inputFormat.colorInfo != null ? inputFormat.colorInfo : ColorInfo.SDR_BT709_LIMITED;
      GlTextureCopier copier = glTextureCopier;
      if (copier == null) {
        copier = new GlTextureCopier(context);
        glTextureCopier = copier;
      }
      internalTexId =
          GlUtil.createTexture(
              outputWidth,
              outputHeight,
              /* useHighPrecisionColorComponents= */ ColorInfo.isTransferHdr(outputColorInfo));

      MatrixUtils.populateTransformationMatrix(
          textureTransformMatrix,
          /* bufferWidth= */ hardwareBuffer.getWidth(),
          /* bufferHeight= */ hardwareBuffer.getHeight(),
          /* formatWidth= */ outputWidth,
          /* formatHeight= */ outputHeight,
          inputFormat.rotationDegrees);

      copier.copyTexture(
          texId,
          /* outputTexId= */ internalTexId,
          outputWidth,
          outputHeight,
          inputColorInfo,
          outputColorInfo,
          textureTransformMatrix,
          isExternalTexture);

      outputTexId = internalTexId;
      internalTexId = C.INDEX_UNSET;
      eglImageTextureWrapper = eglImageTextureWrapper.withOutputTexId(outputTexId);
    } catch (GlException | VideoFrameProcessingException e) {
      if (eglImageTextureWrapper != null) {
        try {
          releaseEglImageTexture(eglImageTextureWrapper, hardwareBufferJniWrapper);
        } catch (GlException exception) {
          Log.w(TAG, "Failed to release EGLImage during error recovery", exception);
          e.addSuppressed(exception);
        }
      }
      if (internalTexId != C.INDEX_UNSET) {
        try {
          GlUtil.deleteTexture(internalTexId);
        } catch (GlException exception) {
          Log.w(TAG, "Failed to delete internal texture during error recovery", exception);
          e.addSuppressed(exception);
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
    @Nullable Exception firstException = null;
    if (glTextureCopier != null) {
      try {
        glTextureCopier.release();
      } catch (VideoFrameProcessingException e) {
        firstException = e;
      }
      glTextureCopier = null;
    }
    for (EglImageTextureWrapper wrapper : activeEglImageTextureWrappers.values()) {
      try {
        releaseEglImageTexture(wrapper, hardwareBufferJniWrapper);
      } catch (GlException e) {
        if (firstException == null) {
          firstException = e;
        } else {
          Log.w(TAG, "Failed to release EGLImage during close", e);
        }
      }
    }
    activeEglImageTextureWrappers.clear();
    if (firstException != null) {
      throw VideoFrameProcessingException.from(firstException);
    }
  }
}
