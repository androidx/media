/*
 * Copyright 2024 The Android Open Source Project
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

import static com.google.common.util.concurrent.Futures.immediateFailedFuture;

import android.graphics.Rect;
import android.opengl.GLES20;
import android.opengl.GLES30;
import androidx.media3.common.C;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A {@link QueuingGlShaderProgram.ConcurrentEffect} implementation which wraps a {@link
 * ByteBufferGlEffect.Processor}.
 *
 * <p>This class is responsible for asynchronously transferring texture frame data to a
 * CPU-accessible {@link ByteBuffer} that will be used by the wrapped {@link
 * ByteBufferGlEffect.Processor}.
 */
/* package */ class ByteBufferConcurrentEffect<T>
    implements QueuingGlShaderProgram.ConcurrentEffect<T> {

  private static final int BYTES_PER_PIXEL = 4;

  private final ByteBufferGlEffect.Processor<T> processor;

  private int inputWidth;
  private int inputHeight;
  private @MonotonicNonNull GlTextureInfo effectInputTexture;

  /**
   * Creates an instance.
   *
   * @param processor The {@linkplain ByteBufferGlEffect.Processor effect}.
   */
  public ByteBufferConcurrentEffect(ByteBufferGlEffect.Processor<T> processor) {
    this.processor = processor;
    inputWidth = C.LENGTH_UNSET;
    inputHeight = C.LENGTH_UNSET;
  }

  @Override
  public Future<T> queueInputFrame(
      GlObjectsProvider glObjectsProvider, GlTextureInfo textureInfo, long presentationTimeUs) {
    try {
      if (effectInputTexture == null
          || textureInfo.width != inputWidth
          || textureInfo.height != inputHeight) {
        inputWidth = textureInfo.width;
        inputHeight = textureInfo.height;
        Size effectInputSize = processor.configure(inputWidth, inputHeight);
        if (effectInputTexture != null) {
          effectInputTexture.release();
        }
        int texId =
            GlUtil.createTexture(
                effectInputSize.getWidth(),
                effectInputSize.getHeight(),
                /* useHighPrecisionColorComponents= */ false);
        effectInputTexture =
            glObjectsProvider.createBuffersForTexture(
                texId, effectInputSize.getWidth(), effectInputSize.getHeight());
      }

      GlUtil.blitFrameBuffer(
          textureInfo.fboId,
          processor.getScaledRegion(presentationTimeUs),
          effectInputTexture.fboId,
          new Rect(
              /* left= */ 0, /* top= */ 0, effectInputTexture.width, effectInputTexture.height));

      GlUtil.focusFramebufferUsingCurrentContext(
          effectInputTexture.fboId, effectInputTexture.width, effectInputTexture.height);
      ByteBuffer pixelBuffer =
          ByteBuffer.allocateDirect(texturePixelBufferSize(effectInputTexture));
      GLES20.glReadPixels(
          /* x= */ 0,
          /* y= */ 0,
          effectInputTexture.width,
          effectInputTexture.height,
          GLES30.GL_RGBA,
          GLES30.GL_UNSIGNED_BYTE,
          pixelBuffer);
      GlUtil.checkGlError();
      return processor.processPixelBuffer(pixelBuffer, presentationTimeUs);
    } catch (GlUtil.GlException | VideoFrameProcessingException e) {
      return immediateFailedFuture(e);
    }
  }

  @Override
  public void finishProcessingAndBlend(GlTextureInfo textureInfo, long presentationTimeUs, T result)
      throws VideoFrameProcessingException {
    processor.finishProcessingAndBlend(textureInfo, presentationTimeUs, result);
  }

  private static int texturePixelBufferSize(GlTextureInfo textureInfo) {
    return textureInfo.width * textureInfo.height * BYTES_PER_PIXEL;
  }
}
