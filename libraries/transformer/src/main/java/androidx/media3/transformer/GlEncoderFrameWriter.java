/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.transformer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.graphics.Matrix;
import android.hardware.HardwareBuffer;
import android.media.metrics.LogSessionId;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.Log;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.HardwareBufferPool;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.effect.DefaultShaderProgram;
import androidx.media3.effect.DefaultVideoFrameProcessor;
import androidx.media3.effect.HardwareBufferJniWrapper;
import androidx.media3.effect.MatrixUtils;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * An implementation of {@link FrameWriter} that uses OpenGL to render {@link HardwareBuffer}s to a
 * {@linkplain DefaultCodec encoder} {@link Surface}.
 *
 * <p>This implementation does <b>NOT</b> support HDR. Use {@link EncoderFrameWriter} to output HDR.
 */
@RequiresApi(26)
/* package */ final class GlEncoderFrameWriter implements FrameWriter {

  /** Listener for {@link GlEncoderFrameWriter} events. */
  public interface Listener {
    /**
     * Called when the encoder is being configured.
     *
     * @param requestedFormat The {@link Format} requested for the encoder.
     * @return The {@link Format} that the encoder should be configured with. This may differ from
     *     {@code requestedFormat}.
     */
    Format onConfigure(Format requestedFormat);

    /**
     * Called when the encoder has been created.
     *
     * @param encoder The created {@link Codec} encoder.
     */
    void onEncoderCreated(Codec encoder);

    /** Called when the end of the input stream has been signaled to the encoder. */
    void onEndOfStream();

    /**
     * Called when an error occurs during frame writing or encoding.
     *
     * @param e The {@link VideoFrameProcessingException} describing the error.
     */
    void onError(VideoFrameProcessingException e);
  }

  private static final ColorInfo DEFAULT_COLOR_INFO = ColorInfo.SDR_BT709_LIMITED;

  private static final String TAG = "GlEncoderFrameWriter";
  private static final int CAPACITY = 4;
  private static final long RELEASE_TIMEOUT_MS = 1_000;
  private static final Duration FENCE_WAIT_TIMEOUT = Duration.ofMillis(500);

  private final Context context;
  private final Codec.EncoderFactory encoderFactory;
  private final Listener listener;
  private final Executor listenerExecutor;
  private final HardwareBufferJniWrapper hardwareBufferJniWrapper;
  private final HardwareBufferPool hardwareBufferPool;
  private final ListeningExecutorService glExecutorService;
  private final GlObjectsProvider glObjectsProvider;
  private final AtomicBoolean isClosed;
  @Nullable private final LogSessionId logSessionId;

  private EGLDisplay eglDisplay;
  private EGLContext eglContext;
  private EGLSurface placeholderSurface;
  private EGLSurface outputEglSurface;
  private @MonotonicNonNull Format configurationFormat;
  @Nullable private DefaultShaderProgram defaultShaderProgram;
  @Nullable private Codec encoder;
  private @Frame.Usage long usage;
  private boolean isRgba8888Shader;

  /**
   * Creates an instance.
   *
   * @param context The {@link Context}.
   * @param encoderFactory The {@link Codec.EncoderFactory} to create encoders.
   * @param listener The {@link Listener} for asynchronous events.
   * @param listenerExecutor The {@link Executor} on which the listener methods are invoked.
   * @param hardwareBufferJniWrapper The {@link HardwareBufferJniWrapper} to interact with
   *     HardwareBuffers.
   * @param logSessionId The {@link LogSessionId} for logging, or {@code null} if not provided.
   */
  public GlEncoderFrameWriter(
      Context context,
      Codec.EncoderFactory encoderFactory,
      Listener listener,
      Executor listenerExecutor,
      GlObjectsProvider glObjectsProvider,
      ListeningExecutorService glExecutorService,
      HardwareBufferJniWrapper hardwareBufferJniWrapper,
      @Nullable LogSessionId logSessionId) {
    this.context = context;
    this.encoderFactory = encoderFactory;
    this.listener = listener;
    this.listenerExecutor = listenerExecutor;
    this.glObjectsProvider = glObjectsProvider;
    this.glExecutorService = glExecutorService;
    this.hardwareBufferJniWrapper = hardwareBufferJniWrapper;
    this.logSessionId = logSessionId;
    isClosed = new AtomicBoolean(false);
    hardwareBufferPool = new HardwareBufferPool(CAPACITY);
    eglDisplay = EGL14.EGL_NO_DISPLAY;
    eglContext = EGL14.EGL_NO_CONTEXT;
    placeholderSurface = EGL14.EGL_NO_SURFACE;
    outputEglSurface = EGL14.EGL_NO_SURFACE;
  }

  @Override
  public Info getInfo() {
    return (format, usage) -> {
      // This class does not support HDR.
      if (ColorInfo.isTransferHdr(format.colorInfo)) {
        return false;
      }
      return encoderFactory.isVideoFormatSupported(format);
    };
  }

  @Override
  public void configure(Format format, @Frame.Usage long usage) {
    checkState(encoder == null);
    this.usage = usage | Frame.USAGE_GPU_SAMPLED_IMAGE | Frame.USAGE_VIDEO_ENCODE;

    Format encoderFormat = listener.onConfigure(format);
    try {
      encoder = encoderFactory.createForVideoEncoding(encoderFormat, logSessionId);
    } catch (ExportException e) {
      listenerExecutor.execute(() -> listener.onError(VideoFrameProcessingException.from(e)));
      return;
    }

    Surface encoderInputSurface = encoder.getInputSurface();
    Codec nonNullEncoder = encoder;
    listenerExecutor.execute(() -> listener.onEncoderCreated(nonNullEncoder));

    configurationFormat = nonNullEncoder.getConfigurationFormat();

    submitToGlExecutor(
        () -> {
          setupGl(encoderInputSurface);
          return null;
        },
        /* onSuccessListener= */ null);
  }

  @Nullable
  @Override
  public AsyncFrame dequeueInputFrame(Executor wakeupExecutor, Runnable wakeupListener) {
    checkState(configurationFormat != null);
    if (isClosed.get()) {
      return null;
    }

    @Nullable
    HardwareBufferPool.HardwareBufferWithFence bufferWithFence =
        hardwareBufferPool.get(
            configurationFormat, usage, () -> wakeupExecutor.execute(wakeupListener));

    if (bufferWithFence != null) {
      HardwareBuffer hardwareBuffer = bufferWithFence.hardwareBuffer;
      DefaultHardwareBufferFrame frame =
          new DefaultHardwareBufferFrame.Builder(hardwareBuffer)
              .setFormat(configurationFormat)
              .build();
      return new AsyncFrame(frame, bufferWithFence.acquireFence);
    }

    return null;
  }

  @Override
  public void queueInputFrame(Frame frame, @Nullable SyncFenceWrapper writeCompleteFence) {
    checkState(configurationFormat != null);
    checkArgument(frame instanceof DefaultHardwareBufferFrame);
    DefaultHardwareBufferFrame hardwareBufferFrame = (DefaultHardwareBufferFrame) frame;
    HardwareBuffer hardwareBuffer = hardwareBufferFrame.getHardwareBuffer();

    long presentationTimeUs = frame.getContentTimeUs();

    submitToGlExecutor(
        () -> {
          renderFrame(hardwareBuffer, frame.getFormat(), presentationTimeUs, writeCompleteFence);
          return null;
        },
        /* onSuccessListener= */ null);
  }

  @Override
  public void signalEndOfStream() {
    checkState(configurationFormat != null);

    submitToGlExecutor(
        () -> {
          if (!isClosed.get()) {
            checkNotNull(encoder).signalEndOfInputStream();
          }
          return null;
        },
        listener::onEndOfStream);
  }

  @Override
  public void close() {
    if (isClosed.getAndSet(true)) {
      return;
    }
    try {
      Object unused =
          glExecutorService
              .submit(
                  (Callable<Void>)
                      () -> {
                        releaseGl();
                        return null;
                      })
              .get(RELEASE_TIMEOUT_MS, MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      listenerExecutor.execute(() -> listener.onError(VideoFrameProcessingException.from(e)));
    } catch (ExecutionException | TimeoutException e) {
      listenerExecutor.execute(() -> listener.onError(VideoFrameProcessingException.from(e)));
    } finally {
      glExecutorService.shutdown();
    }

    hardwareBufferPool.release();

    if (encoder != null) {
      encoder.release();
      encoder = null;
    }
  }

  /** Configures EGL and GL resources. Must be called on the GL thread. */
  private void setupGl(Surface surface) throws GlUtil.GlException {
    Format format = checkNotNull(configurationFormat);
    eglDisplay = GlUtil.getDefaultEglDisplay();
    checkState(!ColorInfo.isTransferHdr(format.colorInfo));
    // TODO: b/525038944 - Centralize GL setup.
    eglContext =
        glObjectsProvider.createEglContext(
            eglDisplay,
            /* openGlVersion= */ 2,
            /* configAttributes= */ GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888);
    placeholderSurface =
        glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);

    @C.ColorTransfer int colorTransfer = checkNotNull(format.colorInfo).colorTransfer;
    outputEglSurface =
        glObjectsProvider.createEglSurface(
            eglDisplay, surface, colorTransfer, /* isEncoderInputSurface= */ true);
  }

  /** Renders the frame to the output surface. Must be called on the GL thread. */
  private void renderFrame(
      HardwareBuffer hardwareBuffer,
      Format inputFormat,
      long presentationTimeUs,
      @Nullable SyncFenceWrapper writeCompleteFence)
      throws GlUtil.GlException, VideoFrameProcessingException {
    try {
      if (isClosed.get()) {
        return;
      }

      if (writeCompleteFence != null) {
        boolean signaled = writeCompleteFence.await(FENCE_WAIT_TIMEOUT);
        if (!signaled) {
          Log.w(TAG, "Timed out waiting for fence.");
        }
      }

      // TODO: b/523223680 - Reuse textures for the same HardwareBuffer.
      GlTextureWrapper glTextureWrapper = toGlTexture(hardwareBuffer);

      boolean isRgba8888Input = hardwareBuffer.getFormat() == HardwareBuffer.RGBA_8888;
      ensureShaderConfigured(isRgba8888Input);

      DefaultShaderProgram shaderProgram = checkNotNull(defaultShaderProgram);
      shaderProgram.setTextureTransformMatrix(
          constructTransformationMatrix(hardwareBuffer, inputFormat));

      Format format = checkNotNull(configurationFormat);
      GlUtil.focusEglSurface(eglDisplay, eglContext, outputEglSurface, format.width, format.height);
      GlUtil.clearFocusedBuffers();

      shaderProgram.drawFrame(glTextureWrapper.texId, presentationTimeUs);

      long eglPresentationTimeNs = presentationTimeUs * 1000L;
      EGLExt.eglPresentationTimeANDROID(eglDisplay, outputEglSurface, eglPresentationTimeNs);
      EGL14.eglSwapBuffers(eglDisplay, outputEglSurface);

      glTextureWrapper.release();
    } finally {
      // TODO: b/523223680 - Use a fence rather than glFinish.
      GLES20.glFinish();
      if (writeCompleteFence != null) {
        writeCompleteFence.close();
      }
      hardwareBufferPool.recycle(hardwareBuffer, /* fence= */ null);
    }
  }

  /**
   * Ensures the shader program is configured for the texture type. Must be called on the GL thread.
   */
  private void ensureShaderConfigured(boolean isRgba8888Input)
      throws VideoFrameProcessingException {
    if (defaultShaderProgram != null && isRgba8888Shader != isRgba8888Input) {
      defaultShaderProgram.release();
      defaultShaderProgram = null;
    }

    if (defaultShaderProgram == null) {
      Format format = checkNotNull(configurationFormat);
      ColorInfo inputColorInfo = format.colorInfo != null ? format.colorInfo : DEFAULT_COLOR_INFO;
      ColorInfo outputColorInfo = format.colorInfo != null ? format.colorInfo : DEFAULT_COLOR_INFO;

      if (isRgba8888Input) {
        defaultShaderProgram =
            DefaultShaderProgram.createWithInternalSampler(
                context,
                inputColorInfo,
                outputColorInfo,
                DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_DEFAULT,
                VideoFrameProcessor.INPUT_TYPE_TEXTURE_ID);
      } else {
        defaultShaderProgram =
            DefaultShaderProgram.createWithExternalSampler(
                context,
                inputColorInfo,
                outputColorInfo,
                DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_DEFAULT,
                /* sampleWithNearest= */ false);
      }
      Object unused = defaultShaderProgram.configure(format.width, format.height);
      isRgba8888Shader = isRgba8888Input;
    }
  }

  /** Releases EGL and GL resources. Must be called on the GL thread. */
  private void releaseGl() throws GlUtil.GlException, VideoFrameProcessingException {
    if (defaultShaderProgram != null) {
      defaultShaderProgram.release();
      defaultShaderProgram = null;
    }

    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
      if (placeholderSurface != EGL14.EGL_NO_SURFACE) {
        GlUtil.focusEglSurface(
            eglDisplay, eglContext, placeholderSurface, /* width= */ 1, /* height= */ 1);
      }
      if (outputEglSurface != EGL14.EGL_NO_SURFACE) {
        GlUtil.destroyEglSurface(eglDisplay, outputEglSurface);
        outputEglSurface = EGL14.EGL_NO_SURFACE;
      }
      if (placeholderSurface != EGL14.EGL_NO_SURFACE) {
        GlUtil.destroyEglSurface(eglDisplay, placeholderSurface);
        placeholderSurface = EGL14.EGL_NO_SURFACE;
      }
      glObjectsProvider.release(eglDisplay);
      eglContext = EGL14.EGL_NO_CONTEXT;
      eglDisplay = EGL14.EGL_NO_DISPLAY;
    }
  }

  /**
   * Constructs a transformation matrix that flips the hardware buffer vertically (since OpenGL
   * typically has y-axis pointing up, while hardware buffers have y-axis pointing down) then
   * applies the crop from the format.
   */
  private static float[] constructTransformationMatrix(
      HardwareBuffer hardwareBuffer, Format format) {
    Matrix flipMatrix = new Matrix();
    flipMatrix.setScale(1f, -1f);
    flipMatrix.postTranslate(0f, 1f);

    Matrix cropMatrix = new Matrix();
    float croppedWidth = format.width;
    float croppedHeight = format.height;
    float bufferWidth = hardwareBuffer.getWidth();
    float bufferHeight = hardwareBuffer.getHeight();
    checkArgument(
        bufferWidth > 0 && bufferHeight > 0, "HardwareBuffer dimensions must be positive");
    cropMatrix.setScale(croppedWidth / bufferWidth, croppedHeight / bufferHeight);

    Matrix transformMatrix = new Matrix();
    // Rotation is handled by the muxer, not the encoder, so no rotation matrix is applied here.
    transformMatrix.set(flipMatrix);
    transformMatrix.postConcat(cropMatrix);
    return MatrixUtils.getGlMatrixArray(transformMatrix);
  }

  /**
   * Submits a {@link Callable} task to the GL executor service and registers callbacks for its
   * asynchronous completion.
   *
   * <p>If the task completes successfully, the provided {@code onSuccessListener} is executed (if
   * not null). If the task throws an exception during execution, the error is wrapped in a {@link
   * VideoFrameProcessingException} and routed to the configured {@code listener}. Both the success
   * and failure callbacks are executed on the {@code listenerExecutor}.
   *
   * @param callable The task to be executed on the GL thread.
   * @param onSuccessListener An optional {@link Runnable} to execute when the task completes
   *     successfully. May be null if no post-execution action is required.
   */
  private void submitToGlExecutor(Callable<Void> callable, @Nullable Runnable onSuccessListener) {
    Futures.addCallback(
        glExecutorService.submit(callable),
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(@Nullable Void result) {
            if (onSuccessListener != null) {
              onSuccessListener.run();
            }
          }

          @Override
          public void onFailure(Throwable t) {
            listener.onError(new VideoFrameProcessingException(t));
          }
        },
        listenerExecutor);
  }

  /** Holds the GL texture ID and EGLImage handle. */
  private final class GlTextureWrapper {
    private final int texId;
    private final long eglImage;

    private GlTextureWrapper(int texId, long eglImage) {
      this.texId = texId;
      this.eglImage = eglImage;
    }

    private void release() throws GlException, VideoFrameProcessingException {
      if (texId != C.INDEX_UNSET) {
        GlUtil.deleteTexture(texId);
      }
      if (eglImage != 0) {
        if (!hardwareBufferJniWrapper.nativeDestroyEGLImage(
            eglDisplay.getNativeHandle(), eglImage)) {
          throw new VideoFrameProcessingException("Failed to destroy EGLImage.");
        }
      }
    }
  }

  /** Configures a {@link HardwareBuffer} to a GL texture. Must be called on the GL thread. */
  private GlTextureWrapper toGlTexture(HardwareBuffer hardwareBuffer) throws GlUtil.GlException {
    boolean isRgba8888 = hardwareBuffer.getFormat() == HardwareBuffer.RGBA_8888;
    int target = isRgba8888 ? GLES20.GL_TEXTURE_2D : GLES11Ext.GL_TEXTURE_EXTERNAL_OES;

    long eglImageHandle =
        hardwareBufferJniWrapper.nativeCreateEglImageFromHardwareBuffer(
            eglDisplay.getNativeHandle(), hardwareBuffer);
    if (eglImageHandle == 0L) {
      throw new GlUtil.GlException(
          "Unable to create EGLImageKHR via JNI, format:"
              + hardwareBuffer.getFormat()
              + ", usage:"
              + hardwareBuffer.getUsage()
              + ".");
    }
    int texture = GlUtil.generateTexture();
    GLES20.glBindTexture(target, texture);
    GlUtil.checkGlError();
    if (!hardwareBufferJniWrapper.nativeBindEGLImage(target, eglImageHandle)) {
      boolean unused =
          hardwareBufferJniWrapper.nativeDestroyEGLImage(
              eglDisplay.getNativeHandle(), eglImageHandle);
      GlUtil.deleteTexture(texture);
      throw new GlUtil.GlException("Failed to bind EGLImage to texture.");
    }
    return new GlTextureWrapper(texture, eglImageHandle);
  }
}
