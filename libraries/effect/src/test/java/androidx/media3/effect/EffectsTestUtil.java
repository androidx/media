/*
 * Copyright 2025 The Android Open Source Project
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

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.fail;

import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import androidx.annotation.Nullable;
import androidx.media3.common.C.ColorTransfer;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.util.GlUtil.GlException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

/** Utilities for effects unit tests. */
/* package */ final class EffectsTestUtil {
  public static GlObjectsProvider fakeGlObjectsProvider() {
    return new GlObjectsProvider() {
      @Override
      public EGLContext createEglContext(
          EGLDisplay eglDisplay, int openGlVersion, int[] configAttributes) throws GlException {
        throw new UnsupportedOperationException();
      }

      @Override
      public EGLSurface createEglSurface(
          EGLDisplay eglDisplay,
          Object surface,
          @ColorTransfer int colorTransfer,
          boolean isEncoderInputSurface)
          throws GlException {
        throw new UnsupportedOperationException();
      }

      @Override
      public EGLSurface createFocusedPlaceholderEglSurface(
          EGLContext eglContext, EGLDisplay eglDisplay) throws GlException {
        throw new UnsupportedOperationException();
      }

      @Override
      public GlTextureInfo createBuffersForTexture(int texId, int width, int height)
          throws GlException {
        throw new UnsupportedOperationException();
      }

      @Override
      public void release(EGLDisplay eglDisplay) throws GlException {
        throw new UnsupportedOperationException();
      }
    };
  }

  public static class FakeGlShaderProgram implements GlShaderProgram {
    public static class QueuedFrameInfo {
      final GlTextureInfo textureInfo;
      final long presentationTimeUs;

      public QueuedFrameInfo(GlTextureInfo textureInfo, long presentationTimeUs) {
        this.textureInfo = textureInfo;
        this.presentationTimeUs = presentationTimeUs;
      }
    }

    public InputListener inputListener;
    public OutputListener outputListener;
    public ErrorListener errorListener;
    public final List<QueuedFrameInfo> queuedFrames;
    @Nullable public CountDownLatch queuedFramesLatch;

    public FakeGlShaderProgram() {
      inputListener = new InputListener() {};
      outputListener = new OutputListener() {};
      errorListener = e -> {};
      queuedFrames = new ArrayList<>();
    }

    @Override
    public void setInputListener(InputListener inputListener) {
      this.inputListener = inputListener;
      inputListener.onReadyToAcceptInputFrame();
    }

    @Override
    public void setOutputListener(OutputListener outputListener) {
      this.outputListener = outputListener;
    }

    @Override
    public void setErrorListener(Executor executor, ErrorListener errorListener) {
      this.errorListener = errorListener;
    }

    @Override
    public void queueInputFrame(
        GlObjectsProvider glObjectsProvider, GlTextureInfo inputTexture, long presentationTimeUs) {
      queuedFrames.add(new QueuedFrameInfo(inputTexture, presentationTimeUs));
      if (queuedFramesLatch != null) {
        queuedFramesLatch.countDown();
      }
    }

    @Override
    public void releaseOutputFrame(GlTextureInfo outputTexture) {}

    @Override
    public void signalEndOfCurrentInputStream() {
      outputListener.onCurrentOutputStreamEnded();
    }

    @Override
    public void flush() {
      inputListener.onFlush();
    }

    @Override
    public void release() {}
  }

  /** A fake {@link FrameProcessor} for capturing output frames. */
  public static final class FakeFrameConsumer<I extends Frame> implements FrameConsumer<I> {
    private final CountDownLatch queueFrameLatch;
    public final List<I> receivedFrames;
    @Nullable private Runnable onCapacityAvailableCallback;
    @Nullable private Executor onCapacityAvailableExecutor;
    public boolean acceptFrames;

    public FakeFrameConsumer(int expectedNumberOfFrames) {
      this.acceptFrames = true;
      this.queueFrameLatch = new CountDownLatch(expectedNumberOfFrames);
      this.receivedFrames = new ArrayList<>();
    }

    @Override
    public boolean queueFrame(I frame) {
      if (!acceptFrames) {
        return false;
      }
      receivedFrames.add(frame);
      queueFrameLatch.countDown();
      return true;
    }

    @Override
    public void setOnCapacityAvailableCallback(
        Executor executor, Runnable onCapacityAvailableCallback) {
      this.onCapacityAvailableCallback = onCapacityAvailableCallback;
      this.onCapacityAvailableExecutor = executor;
    }

    @Override
    public void clearOnCapacityAvailableCallback() {
      this.onCapacityAvailableCallback = null;
      this.onCapacityAvailableExecutor = null;
    }

    public void notifyCallbackListener() {
      checkNotNull(onCapacityAvailableExecutor).execute(checkNotNull(onCapacityAvailableCallback));
    }

    public void awaitFrame(long timeoutMs) throws InterruptedException {
      if (!queueFrameLatch.await(timeoutMs, MILLISECONDS)) {
        fail("Timeout waiting for output frame");
      }
    }
  }

  private EffectsTestUtil() {}
}
