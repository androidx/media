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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.GLES20;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ThrowingRunnable;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.ImagePlanesFrame.Plane;
import androidx.media3.common.video.SyncFenceWrapper;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** Utility methods for FrameProcessor implementations. */
@ExperimentalApi // TODO: b/505721737 Remove once FrameProcessor is production ready.
public final class FrameProcessorUtils {

  private static final String TAG = "FrameProcessorUtils";
  private static final long TIMEOUT_MS = 500;

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

  /** OpenGL ES 2.0 context created. */
  public static final int OPEN_GL_VERSION_2 = 2;

  /** 10-bit HDR-capable OpenGL ES 3.0 context created. */
  public static final int OPEN_GL_VERSION_3 = 3;

  /* package */ static final int RGBA_8888_BYTES_PER_PIXEL = 4;

  /**
   * Sets up the OpenGL context on the current thread.
   *
   * <p>Attempts to create an OpenGL ES 3.0 context with 10-bit HDR configuration ({@link
   * GlUtil#EGL_CONFIG_ATTRIBUTES_RGBA_1010102}) if surfaceless contexts are supported. This allows
   * the pipeline to handle SDR and HDR contents without recreating the context.
   *
   * <p>Falls back to OpenGL ES 2.0 with {@link GlUtil#EGL_CONFIG_ATTRIBUTES_RGBA_8888} if
   * surfaceless contexts are unsupported (e.g., on emulators) or if ES 3.0 context creation fails.
   *
   * @param glObjectsProvider The {@link GlObjectsProvider}.
   * @return The created OpenGL version ({@link #OPEN_GL_VERSION_3} or {@link #OPEN_GL_VERSION_2}).
   */
  public static int setupOpenGl(GlObjectsProvider glObjectsProvider) throws GlException {
    return setupOpenGl(glObjectsProvider, GlUtil.isSurfacelessContextExtensionSupported());
  }

  /* package */ static int setupOpenGl(
      GlObjectsProvider glObjectsProvider, boolean isSurfacelessContextExtensionSupported)
      throws GlException {
    EGLDisplay eglDisplay = GlUtil.getDefaultEglDisplay();
    // 10-bit contexts require EGL_KHR_surfaceless_context support because placeholder surfaces
    // default to 8-bit RGBA_8888, which causes EGL_BAD_MATCH on non-surfaceless platforms (like
    // emulators).
    if (isSurfacelessContextExtensionSupported) {
      EGLContext eglContext = null;
      try {
        eglContext =
            glObjectsProvider.createEglContext(
                eglDisplay, /* openGlVersion= */ 3, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_1010102);
        glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
        return OPEN_GL_VERSION_3;
      } catch (GlException e) {
        Log.w(TAG, "Failed to create OpenGL ES 3.0 context or surface", e);
        if (eglContext != null) {
          GlUtil.destroyEglContext(eglDisplay, eglContext);
        }
      }
    }

    return setupOpenGl2(glObjectsProvider);
  }

  private static int setupOpenGl2(GlObjectsProvider glObjectsProvider) throws GlException {
    EGLDisplay eglDisplay = GlUtil.getDefaultEglDisplay();
    EGLContext eglContext =
        glObjectsProvider.createEglContext(
            eglDisplay, /* openGlVersion= */ 2, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888);
    try {
      glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
      return OPEN_GL_VERSION_2;
    } catch (GlException e) {
      GlUtil.destroyEglContext(eglDisplay, eglContext);
      throw e;
    }
  }

  /**
   * Releases the OpenGL resources.
   *
   * <p>This method must run on the thread that owns the OpenGL context.
   */
  public static void releaseOpenGl(GlObjectsProvider glObjectsProvider) throws GlException {
    glObjectsProvider.release(GlUtil.getDefaultEglDisplay());
  }

  /** Shuts down the passed in {@link ExecutorService} with a timeout. */
  public static void shutdownGlExecutorService(ExecutorService glExecutorService) {
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
  @RequiresApi(26)
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
  @RequiresApi(26)
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

  /**
   * Awaits the acquire fence on the {@link AsyncFrame}. Returns {@code true} if the fence was
   * signaled before the timeout, {@code false} otherwise.
   */
  public static boolean waitAndCloseFence(AsyncFrame asyncFrame) {
    @Nullable SyncFenceWrapper acquireFence = asyncFrame.acquireFence;
    if (acquireFence == null) {
      return true;
    }

    boolean fenceSignaled = acquireFence.awaitMs(TIMEOUT_MS);
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

  /**
   * Executes multiple throwing actions sequentially, ensuring all are run even if some fail.
   *
   * <p>If multiple exceptions are thrown, they are accumulated using {@link
   * Throwable#addSuppressed}.
   *
   * @param actions The throwing actions to execute.
   * @throws VideoFrameProcessingException If any of the actions fail.
   */
  public static void runAllAndAccumulateExceptions(ThrowingRunnable<?>... actions)
      throws VideoFrameProcessingException {
    @Nullable VideoFrameProcessingException exception =
        runAllAndAccumulateExceptionInternal(actions);
    if (exception != null) {
      throw exception;
    }
  }

  /**
   * Executes multiple throwing actions sequentially, ensuring all are run even if some fail.
   *
   * <p>If multiple exceptions are thrown, they are accumulated using {@link
   * Throwable#addSuppressed}.
   *
   * @param errorConsumer The {@link Consumer} to which the accumulated {@link
   *     VideoFrameProcessingException} is routed.
   * @param actions The throwing actions to execute.
   */
  public static void runAllAndAccumulateExceptions(
      Consumer<VideoFrameProcessingException> errorConsumer, ThrowingRunnable<?>... actions) {
    @Nullable VideoFrameProcessingException exception =
        runAllAndAccumulateExceptionInternal(actions);
    if (exception != null) {
      errorConsumer.accept(exception);
    }
  }

  private static @Nullable VideoFrameProcessingException runAllAndAccumulateExceptionInternal(
      ThrowingRunnable<?>... actions) {
    @Nullable VideoFrameProcessingException firstException = null;
    for (ThrowingRunnable<?> action : actions) {
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
    return firstException;
  }

  /**
   * Returns whether the color space requires high precision (16-bit floating-point) color
   * components.
   */
  public static boolean useHighPrecisionColorComponents(@Nullable ColorInfo colorInfo) {
    if (colorInfo == null) {
      return false;
    }
    return colorInfo.colorTransfer == C.COLOR_TRANSFER_LINEAR
        || ColorInfo.isTransferHdr(colorInfo)
        || ColorInfo.isWideColorGamut(colorInfo)
        || colorInfo.lumaBitdepth > 8;
  }

  /**
   * Validates {@link PixelFormat#RGBA_8888} planes for a frame of the given dimensions.
   *
   * <p>Checks that {@code planes} contains a single plane with a pixel stride of {@link
   * #RGBA_8888_BYTES_PER_PIXEL} and sufficient row stride and buffer capacity for {@code width} and
   * {@code height}.
   */
  /* package */ static void validateRgba8888Planes(List<Plane> planes, int width, int height) {
    checkArgument(
        planes.size() == 1 && planes.get(0).getPixelStride() == RGBA_8888_BYTES_PER_PIXEL,
        "Expected 1 plane with pixelStride of %s for RGBA_8888",
        RGBA_8888_BYTES_PER_PIXEL);
    validatePlaneDimensions(planes.get(0), width, height);
  }

  /**
   * Validates the row stride and remaining buffer capacity of a non-interleaved {@link Plane}.
   *
   * <p>Checks that {@code plane} (where each sample occupies {@link Plane#getPixelStride()} bytes)
   * can hold {@code width} by {@code height} pixels at its {@linkplain Plane#getPixelStride() pixel
   * stride} and {@linkplain Plane#getRowStride() row stride}.
   */
  /* package */ static void validatePlaneDimensions(Plane plane, int width, int height) {
    validatePlaneDimensions(plane, width, height, /* bytesPerSample= */ plane.getPixelStride());
  }

  /**
   * Validates the row stride and remaining buffer capacity of a {@link Plane}.
   *
   * <p>Checks that {@code plane} (where each sample occupies {@code bytesPerSample} bytes and
   * samples are spaced {@link Plane#getPixelStride()} bytes apart) can hold {@code width} by {@code
   * height} samples at its {@linkplain Plane#getPixelStride() pixel stride} and {@linkplain
   * Plane#getRowStride() row stride}.
   */
  /* package */ static void validatePlaneDimensions(
      Plane plane, int width, int height, int bytesPerSample) {
    ByteBuffer buffer = plane.getBuffer();
    int rowStride = plane.getRowStride();
    int pixelStride = plane.getPixelStride();
    int rowBytes = width * pixelStride;
    checkArgument(
        rowStride >= rowBytes,
        "rowStride (%s) must be at least rowBytes (%s)",
        rowStride,
        rowBytes);
    // Producers such as ImageReader end each plane's buffer right after its own last sample,
    // without trailing row-stride padding or interleaved bytes from another plane. For example,
    // in a 3-sample-wide NV12 chroma row (width = 3, pixelStride = 2, bytesPerSample = 1):
    //
    //   Interleaved memory: [U0, V0, U1, V1, U2, V2]  (rowBytes = 3 * 2 = 6 bytes)
    //   uPlane final row:   [U0, V0, U1, V1, U2]      (5 bytes: ends at U2, without V2)
    //   vPlane final row:       [V0, U1, V1, U2, V2]  (5 bytes: starts at V0, ends at V2)
    //
    // In general, the first (width - 1) samples in the final row span (width - 1) * pixelStride
    // bytes and the last sample occupies bytesPerSample bytes, so the minimum final row length
    // (minimumFinalRowBytes) is (width - 1) * pixelStride + bytesPerSample:
    // - For tightly packed planes (pixelStride == bytesPerSample, such as RGBA or planar YUV),
    //   minimumFinalRowBytes equals width * pixelStride (rowBytes).
    // - For semi-planar chroma (pixelStride == 2, bytesPerSample == 1), minimumFinalRowBytes
    //   equals width * pixelStride - 1 (rowBytes - 1).
    int fullStrideRowsBytes = rowStride * (height - 1);
    int finalRowOffsetToLastSample = (width - 1) * pixelStride;
    int minimumFinalRowBytes = finalRowOffsetToLastSample + bytesPerSample;
    int minimumBufferSize = fullStrideRowsBytes + minimumFinalRowBytes;
    checkArgument(
        buffer.remaining() >= minimumBufferSize,
        "Plane buffer holds %s bytes, but rowStride (%s) and height (%s) require at least %s",
        buffer.remaining(),
        rowStride,
        height,
        minimumBufferSize);
  }

  private FrameProcessorUtils() {}
}
