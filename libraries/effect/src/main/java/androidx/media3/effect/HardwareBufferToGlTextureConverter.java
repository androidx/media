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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.effect.FrameProcessorUtils.createAndBindEglImage;
import static androidx.media3.effect.FrameProcessorUtils.releaseEglImageTexture;
import static androidx.media3.effect.FrameProcessorUtils.runAllAndAccumulateExceptions;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Iterables.getFirst;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Gainmap;
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
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ThrowingRunnable;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.effect.FrameProcessorUtils.EglImageTextureWrapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
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
  private final Map<HardwareBufferFrame, Integer> activeGainmapTextures;

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
    this.activeGainmapTextures = new HashMap<>();
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
    boolean isRotated = inputFormat.rotationDegrees == 90 || inputFormat.rotationDegrees == 270;
    int outputWidth = isRotated ? inputFormat.height : inputFormat.width;
    int outputHeight = isRotated ? inputFormat.width : inputFormat.height;
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
              outputWidth, outputHeight, needsHighPrecisionTexture(outputColorInfo));

      MatrixUtils.populateTransformationMatrix(
          textureTransformMatrix,
          /* bufferWidth= */ hardwareBuffer.getWidth(),
          /* bufferHeight= */ hardwareBuffer.getHeight(),
          /* formatWidth= */ outputWidth,
          /* formatHeight= */ outputHeight,
          inputFormat.rotationDegrees);

      // On API 34+, if the input is an Ultra HDR image with a gainmap and the pipeline output is
      // HDR, apply the gainmap to reconstruct the HDR image. On API <= 33 or when the pipeline
      // output is SDR, falls back to copying the base SDR image without gainmap application.
      boolean isUltraHdr =
          SDK_INT >= 34
              && Objects.equals(inputFormat.sampleMimeType, MimeTypes.IMAGE_JPEG_R)
              && ColorInfo.isTransferHdr(outputColorInfo);

      if (isUltraHdr) {
        Bitmap bitmap = (Bitmap) checkNotNull(getInternalFrame(hardwareBufferFrame));
        Gainmap gainmap = checkNotNull(bitmap.getGainmap());
        int gainmapTexId = GlUtil.createTexture(checkNotNull(gainmap.getGainmapContents()));
        activeGainmapTextures.put(hardwareBufferFrame, gainmapTexId);
        copier.copyTextureUltraHdr(
            texId,
            gainmapTexId,
            gainmap,
            /* outputTexId= */ internalTexId,
            outputWidth,
            outputHeight,
            outputColorInfo,
            textureTransformMatrix);
      } else {
        copier.copyTexture(
            texId,
            /* outputTexId= */ internalTexId,
            outputWidth,
            outputHeight,
            inputColorInfo,
            outputColorInfo,
            textureTransformMatrix,
            isExternalTexture);
      }

      outputTexId = internalTexId;
      internalTexId = C.INDEX_UNSET;
      eglImageTextureWrapper = eglImageTextureWrapper.withOutputTexId(outputTexId);
    } catch (GlException | VideoFrameProcessingException e) {
      Integer activeGainmapTexId = activeGainmapTextures.remove(hardwareBufferFrame);
      if (activeGainmapTexId != null) {
        try {
          GlUtil.deleteTexture(activeGainmapTexId);
        } catch (GlException exception) {
          Log.w(TAG, "Failed to delete gainmap texture during error recovery", exception);
          e.addSuppressed(exception);
        }
      }
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
              Integer activeGainmapTexId = activeGainmapTextures.remove(hardwareBufferFrame);
              EglImageTextureWrapper activeWrapper =
                  activeEglImageTextureWrappers.remove(hardwareBufferFrame);
              if (activeGainmapTexId == null && activeWrapper == null) {
                return;
              }
              AtomicReference<SyncFenceWrapper> glReadFence = new AtomicReference<>();

              runAllAndAccumulateExceptions(
                  errorConsumer,
                  () -> {
                    if (activeGainmapTexId != null) {
                      GlUtil.deleteTexture(activeGainmapTexId);
                    }
                  },
                  () -> {
                    if (activeWrapper != null) {
                      releaseEglImageTexture(activeWrapper, hardwareBufferJniWrapper);
                    }
                  },
                  () -> {
                    @Nullable
                    SyncFenceWrapper syncFence = getFirst(GlUtil.createSyncFences(1), null);
                    if (syncFence != null) {
                      glReadFence.set(syncFence);
                    } else {
                      GLES20.glFinish();
                    }
                  });

              listenerExecutor.execute(
                  () -> listener.onFrameProcessed(hardwareBufferFrame, glReadFence.get()));
            })
        .setPresentationTimeUs(hardwareBufferFrame.getContentTimeUs())
        .setFormat(
            hardwareBufferFrame
                .getFormat()
                .buildUpon()
                .setWidth(outputWidth)
                .setHeight(outputHeight)
                .setColorInfo(outputColorInfo)
                // Reset rotation to 0 because we rotated the frame physically with OpenGL. The
                // pipeline should always receive frames in their intended orientation.
                .setRotationDegrees(0)
                .setColorInfo(outputColorInfo)
                .build())
        .setMetadata(hardwareBufferFrame.getMetadata())
        .build();
  }

  @Override
  public void releaseGlResources(HardwareBufferFrame hardwareBufferFrame)
      throws VideoFrameProcessingException {
    Integer activeGainmapTexId = activeGainmapTextures.remove(hardwareBufferFrame);
    EglImageTextureWrapper wrapper = activeEglImageTextureWrappers.remove(hardwareBufferFrame);

    runAllAndAccumulateExceptions(
        () -> {
          if (activeGainmapTexId != null) {
            GlUtil.deleteTexture(activeGainmapTexId);
          }
        },
        () -> {
          if (wrapper != null) {
            releaseEglImageTexture(wrapper, hardwareBufferJniWrapper);
          }
        });
  }

  @Override
  public void close() throws VideoFrameProcessingException {
    List<ThrowingRunnable<?>> actions = new ArrayList<>();
    if (glTextureCopier != null) {
      GlTextureCopier copier = glTextureCopier;
      glTextureCopier = null;
      actions.add(copier::release);
    }
    for (int gainmapTexId : activeGainmapTextures.values()) {
      actions.add(() -> GlUtil.deleteTexture(gainmapTexId));
    }
    activeGainmapTextures.clear();
    for (EglImageTextureWrapper wrapper : activeEglImageTextureWrappers.values()) {
      actions.add(() -> releaseEglImageTexture(wrapper, hardwareBufferJniWrapper));
    }
    activeEglImageTextureWrappers.clear();
    runAllAndAccumulateExceptions(actions.toArray(new ThrowingRunnable<?>[0]));
  }

  @Nullable
  private static Object getInternalFrame(HardwareBufferFrame hardwareBufferFrame) {
    if (hardwareBufferFrame instanceof DefaultHardwareBufferFrame) {
      return ((DefaultHardwareBufferFrame) hardwareBufferFrame).getInternalImage();
    }
    return null;
  }

  private static boolean needsHighPrecisionTexture(ColorInfo outputColorInfo) {
    // Use FP16 for all HDR content, and RGB_LINEAR.
    return ColorInfo.isWideColorGamut(outputColorInfo)
        || outputColorInfo.colorTransfer == C.COLOR_TRANSFER_LINEAR;
  }
}
