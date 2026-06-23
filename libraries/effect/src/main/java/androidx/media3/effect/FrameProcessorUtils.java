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
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.hardware.HardwareBuffer;
import android.hardware.SyncFence;
import android.opengl.EGL14;
import android.opengl.EGL15;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSync;
import android.opengl.GLES20;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.Log;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.SyncFenceWrapper;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.time.Duration;
import java.util.concurrent.Callable;

@ExperimentalApi // TODO: b/505721737 Remove once FrameProcessor is production ready.
@RequiresApi(26)
/* package */ final class FrameProcessorUtils {

  private static final String TAG = "FrameProcessorUtils";
  private static final long TIMEOUT_MS = 500;
  private static final Duration FENCE_TIMEOUT_DURATION = Duration.ofMillis(TIMEOUT_MS);

  /** Data class holding handles to {@code EglImage}, texture ID and frame buffer object ID. */
  public static final class EglImageTextureWrapper {
    final long eglImage;
    final int texId;
    final int fboId;

    /**
     * The texture ID passed to downstream pipeline consumers. This is identical to {@link #texId}
     * unless an internal 2D texture copy was generated (e.g. when sampling from an external
     * texture).
     */
    final int outputTexId;

    EglImageTextureWrapper(long eglImage, int texId, int fboId) {
      this(eglImage, texId, fboId, /* outputTexId= */ texId);
    }

    private EglImageTextureWrapper(long eglImage, int texId, int fboId, int outputTexId) {
      this.eglImage = eglImage;
      this.texId = texId;
      this.fboId = fboId;
      this.outputTexId = outputTexId;
    }

    public EglImageTextureWrapper withOutputTexId(int outputTexId) {
      return new EglImageTextureWrapper(eglImage, texId, fboId, outputTexId);
    }
  }

  /**
   * Sets up the OpenGL resources.
   *
   * <p>This method must run on the thread that owns the OpenGL context.
   */
  public static void setupOpenGl(GlObjectsProvider glObjectsProvider) throws GlException {
    EGLDisplay eglDisplay = GlUtil.getDefaultEglDisplay();
    glObjectsProvider.createFocusedPlaceholderEglSurface(
        glObjectsProvider.createEglContext(
            eglDisplay, /* openGlVersion= */ 2, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888),
        eglDisplay);
  }

  /**
   * Releases the OpenGL resources.
   *
   * <p>This method must run on the thread that owns the OpenGL context.
   */
  public static void releaseOpenGl(GlObjectsProvider glObjectsProvider) throws GlException {
    glObjectsProvider.release(GlUtil.getDefaultEglDisplay());
  }

  /** Shuts down the passed in {@link ListeningExecutorService} with a timeout. */
  public static void shutdownGlExecutorService(ListeningExecutorService glExecutorService) {
    glExecutorService.shutdown();
    try {
      if (!glExecutorService.awaitTermination(TIMEOUT_MS, MILLISECONDS)) {
        Log.e(TAG, "Failed to shut down GL Executor");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Creates an EGL image from a {@link HardwareBuffer} and binds it to a newly generated texture.
   *
   * <p>This method should only be called on the thread that owns the OpenGL context.
   */
  public static EglImageTextureWrapper createAndBindEglImage(
      EGLDisplay eglDisplay,
      HardwareBuffer buffer,
      HardwareBufferJniWrapper jniWrapper,
      int target,
      boolean writesToBoundImage)
      throws GlException {
    long eglDisplayHandle = eglDisplay.getNativeHandle();
    long eglImage = jniWrapper.nativeCreateEglImageFromHardwareBuffer(eglDisplayHandle, buffer);
    if (eglImage == 0) {
      throw new GlException("Failed to create EGLImage from HardwareBuffer");
    }

    int texId = GlUtil.generateTexture();
    GlUtil.checkGlError();
    GLES20.glBindTexture(target, texId);
    if (!jniWrapper.nativeBindEGLImage(target, eglImage)) {
      boolean unused = jniWrapper.nativeDestroyEGLImage(eglDisplayHandle, eglImage);
      GlUtil.deleteTexture(texId);
      throw new GlException("Failed to bind EGLImage to texture");
    }

    int fboId = C.INDEX_UNSET;
    if (writesToBoundImage) {
      fboId = GlUtil.createFboForTexture(texId);
      GlUtil.focusFramebufferUsingCurrentContext(fboId, buffer.getWidth(), buffer.getHeight());
    }

    return new EglImageTextureWrapper(eglImage, texId, fboId);
  }

  /** Releases the EGL image, focused frame buffer object, and texture. */
  public static void releaseEglImageTexture(
      EglImageTextureWrapper eglImageTextureWrapper, HardwareBufferJniWrapper jniWrapper)
      throws GlException {
    long eglDisplay = GlUtil.getDefaultEglDisplay().getNativeHandle();
    if (eglImageTextureWrapper.fboId != C.INDEX_UNSET) {
      GlUtil.deleteFbo(eglImageTextureWrapper.fboId);
    }
    if (eglImageTextureWrapper.outputTexId != eglImageTextureWrapper.texId) {
      GlUtil.deleteTexture(eglImageTextureWrapper.outputTexId);
    }
    GlUtil.deleteTexture(eglImageTextureWrapper.texId);
    boolean unused = jniWrapper.nativeDestroyEGLImage(eglDisplay, eglImageTextureWrapper.eglImage);
  }

  /** Generates the requested number of native sync fences. Must be called on the GL thread. */
  public static ImmutableList<SyncFenceWrapper> generateSyncFences(int count) throws GlException {
    // TODO: b/505721737 - Move to utility class.
    EGLDisplay eglDisplay = GlUtil.getDefaultEglDisplay();
    String extensions = EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS);
    if (SDK_INT < 33 || !extensions.contains("EGL_ANDROID_native_fence_sync")) {
      return ImmutableList.of();
    }
    EGLSync eglSync = EGL15.EGL_NO_SYNC;
    ImmutableList.Builder<SyncFenceWrapper> fences = new ImmutableList.Builder<>();
    try {
      eglSync =
          EGL15.eglCreateSync(
              eglDisplay,
              EGLExt.EGL_SYNC_NATIVE_FENCE_ANDROID,
              /* attrib_list= */ new long[] {EGL14.EGL_NONE},
              /* offset= */ 0);
      GlUtil.checkEglException("eglCreateSync failed");
      if (eglSync == EGL15.EGL_NO_SYNC) {
        return ImmutableList.of();
      }
      SyncFence syncFence = EGLExt.eglDupNativeFenceFDANDROID(eglDisplay, eglSync);
      GlUtil.checkEglException("eglDupNativeFenceFDANDROID failed");
      if (!syncFence.isValid()) {
        // Calling eglDupNativeFenceAndroid may produce an invalid fence the first time it
        // is called. See b/18052459.
        GLES20.glFlush();
        syncFence = EGLExt.eglDupNativeFenceFDANDROID(eglDisplay, eglSync);
        GlUtil.checkEglException("eglDupNativeFenceFDANDROID failed after glFlush");
      }
      if (!syncFence.isValid()) {
        return ImmutableList.of();
      }
      fences.add(SyncFenceWrapper.of(syncFence));
      for (int i = 0; i < count - 1; i++) {
        SyncFence duplicatedFence = EGLExt.eglDupNativeFenceFDANDROID(eglDisplay, eglSync);
        GlUtil.checkEglException("eglDupNativeFenceFDANDROID failed for input frame");
        checkState(duplicatedFence.isValid());
        fences.add(SyncFenceWrapper.of(duplicatedFence));
      }

    } finally {
      EGL15.eglDestroySync(eglDisplay, eglSync);
      GlUtil.checkEglException("eglDestroySync failed");
    }
    return fences.build();
  }

  /**
   * Awaits the acquire fence on the {@link AsyncFrame}. Returns {@code true} if the fence was
   * signaled before the timeout, {@code false} otherwise.
   */
  public static boolean waitAndCloseFence(AsyncFrame asyncFrame) {
    @Nullable SyncFenceWrapper acquireFence = asyncFrame.acquireFence;
    if (acquireFence == null) {
      return true;
    }

    boolean fenceSignaled = acquireFence.await(FENCE_TIMEOUT_DURATION);
    acquireFence.close();
    return fenceSignaled;
  }

  /**
   * Submits an {@link Callable operation} to the passed in {@link ListeningExecutorService}.
   *
   * <p>The passed in {@link Consumer errorConsumer} will be invoked if the {@code operation} fails.
   */
  public static void submitToGlExecutor(
      Callable<Void> operation,
      ListeningExecutorService glExecutorService,
      Consumer<VideoFrameProcessingException> errorConsumer) {
    Futures.addCallback(
        glExecutorService.submit(operation),
        new FutureCallback<Object>() {
          @Override
          public void onSuccess(Object result) {}

          @Override
          public void onFailure(Throwable t) {
            errorConsumer.accept(VideoFrameProcessingException.from(new RuntimeException(t)));
          }
        },
        directExecutor());
  }

  /** A runnable that throws an exception. */
  public interface ThrowingRunnable {
    /** Runs the operation. */
    void run() throws Exception;
  }

  /**
   * Executes multiple throwing actions sequentially, ensuring all are run even if some fail.
   *
   * <p>If multiple exceptions are thrown, they are accumulated using {@link
   * Throwable#addSuppressed}.
   *
   * @param actions The throwing actions to execute.
   * @throws VideoFrameProcessingException If any of the actions fail.
   */
  public static void runAllAndAccumulateExceptions(ThrowingRunnable... actions)
      throws VideoFrameProcessingException {
    VideoFrameProcessingException firstException = null;
    for (ThrowingRunnable action : actions) {
      if (action == null) {
        continue;
      }
      try {
        action.run();
      } catch (Exception e) {
        VideoFrameProcessingException vfpe = VideoFrameProcessingException.from(e);
        if (firstException == null) {
          firstException = vfpe;
        } else {
          firstException.addSuppressed(vfpe);
        }
      }
    }
    if (firstException != null) {
      throw firstException;
    }
  }

  private FrameProcessorUtils() {}
}
