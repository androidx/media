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
package androidx.media3.effect.playservices;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.SOURCE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.Format;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.HandlerExecutor;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.HardwareBufferNativeHelpers;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.effect.HardwareBufferJni;
import com.google.android.gms.media.effect.enhancement.EnhancementCallback;
import com.google.android.gms.media.effect.enhancement.EnhancementOptions;
import com.google.android.gms.media.effect.enhancement.EnhancementSession;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A {@link FrameProcessor} decorator that routes video frames through Google Play services {@link
 * EnhancementSession}.
 *
 * <p>Timestamp handling across the surface bridge:
 *
 * <ol>
 *   <li>{@link FrameToSurfaceFrameWriter} receives an upstream {@link Frame} with {@code
 *       presentationTimeUs}, stamps the {@link android.media.Image} with a synthetic strictly
 *       increasing nanosecond timestamp from {@link MonotonicTimestampGenerator} (required because
 *       the underlying MediaPipe graph rejects non-increasing timestamps on seeks), and notifies
 *       {@link FrameToSurfaceFrameWriter.Listener#onFrameQueued(long)} with the original {@code
 *       presentationTimeUs}.
 *   <li>{@link EnhancementSessionDecorator} forwards {@code presentationTimeUs} from {@code
 *       onFrameQueued} to {@link SurfaceToFrameWriterAdapter#onInputFrameQueued(long)}.
 *   <li>{@link SurfaceToFrameWriterAdapter} stores {@code presentationTimeUs} in a FIFO queue and
 *       restores it onto the output {@link Frame} when the processed image arrives on the output
 *       {@link Surface}. A 1:1 FIFO queue is guaranteed to match because {@link
 *       FrameToSurfaceFrameWriter} enforces at most one frame in flight ({@code
 *       MAX_IN_FLIGHT_FRAMES = 1}), waiting for {@link
 *       SurfaceToFrameWriterAdapter.Listener#onFrameReleased()} before queuing the next frame.
 * </ol>
 */
@ExperimentalApi
@RequiresApi(33)
public final class EnhancementSessionDecorator implements FrameProcessor {

  private static final int UPSCALE_FACTOR = 2;
  private static final long CLOSE_TIMEOUT_MS = 500L;

  @Documented
  @Retention(SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    STATE_WAITING_FOR_FORMAT,
    STATE_INITIALIZING,
    STATE_READY,
    STATE_ERROR,
    STATE_CLOSED,
  })
  private @interface State {}

  private static final int STATE_WAITING_FOR_FORMAT = 0;
  private static final int STATE_INITIALIZING = 1;
  private static final int STATE_READY = 2;
  private static final int STATE_ERROR = 3;
  private static final int STATE_CLOSED = 4;

  /** Builder for {@link EnhancementSessionDecorator} factories. */
  public static final class Builder {
    private final Context context;
    private final FrameProcessor.Factory baseFrameProcessorFactory;
    private boolean isTonemappingEnabled;
    private boolean isDeblurAndDenoiseVideoEnabled;
    private boolean isUpscaleVideoEnabled;
    @Nullable private Looper looper;
    @Nullable private EnhancementSessionManager.Client sessionManagerClient;
    @Nullable private HardwareBufferNativeHelpers hardwareBufferNativeHelpers;

    /** Creates a builder with the specified context and base {@link FrameProcessor.Factory}. */
    public Builder(Context context, FrameProcessor.Factory baseFrameProcessorFactory) {
      this.context = context.getApplicationContext();
      this.baseFrameProcessorFactory = baseFrameProcessorFactory;
    }

    /** Sets whether tone mapping is enabled. */
    @CanIgnoreReturnValue
    public Builder setTonemappingEnabled(boolean isTonemappingEnabled) {
      this.isTonemappingEnabled = isTonemappingEnabled;
      return this;
    }

    /** Sets whether deblur and denoise is enabled. */
    @CanIgnoreReturnValue
    public Builder setDeblurAndDenoiseVideoEnabled(boolean isDeblurAndDenoiseVideoEnabled) {
      this.isDeblurAndDenoiseVideoEnabled = isDeblurAndDenoiseVideoEnabled;
      return this;
    }

    /** Sets whether upscale is enabled. */
    @CanIgnoreReturnValue
    public Builder setUpscaleVideoEnabled(boolean isUpscaleVideoEnabled) {
      this.isUpscaleVideoEnabled = isUpscaleVideoEnabled;
      return this;
    }

    /**
     * Sets the {@link Looper} that must be used for all calls to the enhancement session and
     * internal callbacks.
     *
     * <p>If not set, an internal {@link HandlerThread} will be created and managed by each {@link
     * EnhancementSessionDecorator} instance.
     */
    @CanIgnoreReturnValue
    public Builder setLooper(Looper looper) {
      this.looper = looper;
      return this;
    }

    @VisibleForTesting
    @CanIgnoreReturnValue
    /* package */ Builder setSessionManagerClient(
        EnhancementSessionManager.Client sessionManagerClient) {
      this.sessionManagerClient = sessionManagerClient;
      return this;
    }

    @VisibleForTesting
    @CanIgnoreReturnValue
    /* package */ Builder setHardwareBufferNativeHelpers(
        HardwareBufferNativeHelpers hardwareBufferNativeHelpers) {
      this.hardwareBufferNativeHelpers = hardwareBufferNativeHelpers;
      return this;
    }

    /** Builds the {@link FrameProcessor.Factory}. */
    public Factory build() {
      return new Factory(
          context,
          baseFrameProcessorFactory,
          isTonemappingEnabled,
          isDeblurAndDenoiseVideoEnabled,
          isUpscaleVideoEnabled,
          looper,
          sessionManagerClient,
          hardwareBufferNativeHelpers);
    }
  }

  /** Factory for {@link EnhancementSessionDecorator}. */
  public static final class Factory implements FrameProcessor.Factory {
    private final FrameProcessor.Factory baseFrameProcessorFactory;
    private final boolean isTonemappingEnabled;
    private final boolean isDeblurAndDenoiseVideoEnabled;
    private final boolean isUpscaleVideoEnabled;
    @Nullable private final Looper looper;
    private final EnhancementSessionManager.Client sessionManagerClient;
    private final HardwareBufferNativeHelpers hardwareBufferNativeHelpers;

    private Factory(
        Context context,
        FrameProcessor.Factory baseFrameProcessorFactory,
        boolean isTonemappingEnabled,
        boolean isDeblurAndDenoiseVideoEnabled,
        boolean isUpscaleVideoEnabled,
        @Nullable Looper looper,
        @Nullable EnhancementSessionManager.Client sessionManagerClient,
        @Nullable HardwareBufferNativeHelpers hardwareBufferNativeHelpers) {
      this.baseFrameProcessorFactory = baseFrameProcessorFactory;
      this.isTonemappingEnabled = isTonemappingEnabled;
      this.isDeblurAndDenoiseVideoEnabled = isDeblurAndDenoiseVideoEnabled;
      this.isUpscaleVideoEnabled = isUpscaleVideoEnabled;
      this.looper = looper;
      this.sessionManagerClient =
          sessionManagerClient != null
              ? sessionManagerClient
              : new EnhancementSessionManager.PlayServicesClientAdapter(
                  context.getApplicationContext());
      this.hardwareBufferNativeHelpers =
          hardwareBufferNativeHelpers != null
              ? hardwareBufferNativeHelpers
              : HardwareBufferJni.INSTANCE;
    }

    @Override
    public FrameProcessor create(FrameWriter output, Executor listenerExecutor, Listener listener) {
      return new EnhancementSessionDecorator(
          baseFrameProcessorFactory,
          output,
          isTonemappingEnabled,
          isDeblurAndDenoiseVideoEnabled,
          isUpscaleVideoEnabled,
          listenerExecutor,
          listener,
          looper,
          sessionManagerClient,
          hardwareBufferNativeHelpers);
    }
  }

  private final boolean isTonemappingEnabled;
  private final boolean isDeblurAndDenoiseVideoEnabled;
  private final boolean isUpscaleVideoEnabled;
  private final Executor listenerExecutor;
  private final Listener listener;

  private final Looper looper;
  @Nullable private final HandlerThread handlerThread;
  private final Handler handler;
  private final Executor handlerExecutor;

  private final FrameProcessor baseFrameProcessor;
  private final FrameToSurfaceFrameWriter frameToSurfaceFrameWriter;
  private final EnhancementSessionManager sessionManager;
  private final SurfaceToFrameWriterAdapter surfaceToFrameWriterAdapter;

  private @State int state;

  // Accessed only on handlerExecutor.
  private @MonotonicNonNull Format inputFormat;

  // Safe because handleError is only invoked asynchronously on handlerThread.
  @SuppressWarnings("method.invocation")
  private EnhancementSessionDecorator(
      FrameProcessor.Factory baseFrameProcessorFactory,
      FrameWriter downstreamOutput,
      boolean isTonemappingEnabled,
      boolean isDeblurAndDenoiseVideoEnabled,
      boolean isUpscaleVideoEnabled,
      Executor listenerExecutor,
      Listener listener,
      @Nullable Looper looper,
      EnhancementSessionManager.Client sessionManagerClient,
      HardwareBufferNativeHelpers hardwareBufferNativeHelpers) {
    state = STATE_WAITING_FOR_FORMAT;
    this.isTonemappingEnabled = isTonemappingEnabled;
    this.isDeblurAndDenoiseVideoEnabled = isDeblurAndDenoiseVideoEnabled;
    this.isUpscaleVideoEnabled = isUpscaleVideoEnabled;
    this.listenerExecutor = listenerExecutor;
    this.listener = listener;

    if (looper != null) {
      handlerThread = null;
      this.looper = looper;
    } else {
      handlerThread = new HandlerThread("EnhancementDecorator");
      handlerThread.start();
      this.looper = handlerThread.getLooper();
    }
    handler = new Handler(this.looper);
    handlerExecutor =
        new HandlerExecutor(handler, e -> handleError(VideoFrameProcessingException.from(e)));

    sessionManager =
        new EnhancementSessionManager(
            handlerExecutor, new SessionManagerListener(), sessionManagerClient);

    surfaceToFrameWriterAdapter =
        new SurfaceToFrameWriterAdapter(
            downstreamOutput,
            hardwareBufferNativeHelpers,
            handler,
            new SurfaceToFrameWriterAdapterListener());

    frameToSurfaceFrameWriter =
        new FrameToSurfaceFrameWriter(handlerExecutor, new FrameToSurfaceFrameWriterListener());

    baseFrameProcessor =
        baseFrameProcessorFactory.create(
            frameToSurfaceFrameWriter, listenerExecutor, new BaseFrameProcessorListener());
  }

  @Override
  public boolean queue(List<AsyncFrame> frames) {
    return baseFrameProcessor.queue(frames);
  }

  @Override
  public void signalEndOfStream() {
    baseFrameProcessor.signalEndOfStream();
  }

  /**
   * {@inheritDoc}
   *
   * <p>May be called from any thread.
   */
  @Override
  public void close() {
    if (Looper.myLooper() != looper) {
      CountDownLatch latch = new CountDownLatch(1);
      boolean posted =
          handler.post(
              () -> {
                try {
                  closeInternal();
                } finally {
                  latch.countDown();
                }
              });
      if (posted) {
        try {
          if (!latch.await(CLOSE_TIMEOUT_MS, MILLISECONDS)) {
            listener.onError(
                new VideoFrameProcessingException(
                    "Timeout waiting for SurfaceToFrameWriterAdapter to close"));
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          listener.onError(new VideoFrameProcessingException(e));
        }
      }
    } else {
      closeInternal();
    }
  }

  private void closeInternal() {
    checkState(Looper.myLooper() == looper);
    if (state == STATE_CLOSED) {
      return;
    }
    state = STATE_CLOSED;
    baseFrameProcessor.close();
    frameToSurfaceFrameWriter.close();
    sessionManager.release();
    surfaceToFrameWriterAdapter.close();
    if (handlerThread != null) {
      handlerThread.quitSafely();
    }
  }

  private void onSessionReady(EnhancementSession session, EnhancementOptions options) {
    checkState(Looper.myLooper() == looper);
    if (state == STATE_CLOSED || state == STATE_ERROR) {
      sessionManager.release();
      return;
    } else {
      checkState(state == STATE_INITIALIZING);
      state = STATE_READY;
    }
    try {
      Format currentInputFormat = checkNotNull(inputFormat);
      int outputWidth = options.getWidth();
      int outputHeight = options.getHeight();
      if (options.isUpscaleVideoEnabled()) {
        outputWidth *= UPSCALE_FACTOR;
        outputHeight *= UPSCALE_FACTOR;
      }
      Surface outputSurface =
          surfaceToFrameWriterAdapter.configure(outputWidth, outputHeight, currentInputFormat);
      session.setOutputSurface(
          outputSurface,
          options,
          // EnhancementCallback methods called on undefined thread.
          new EnhancementCallback() {
            @Override
            public void onSurfaceProcessed(long timestamp) {}

            @Override
            public void onBitmapProcessed(Bitmap bitmap) {}

            @Override
            public void onError(int statusCode) {
              handleError(
                  new VideoFrameProcessingException(
                      "Google Play services video enhancement error: " + statusCode));
            }

            @Override
            public void onCancelled(int statusCode) {
              handleError(
                  new VideoFrameProcessingException(
                      "Google Play services video enhancement cancelled: " + statusCode));
            }
          });
      frameToSurfaceFrameWriter.setOutputSurface(
          session.getInputSurface(), options.getWidth(), options.getHeight());
    } catch (RuntimeException e) {
      sessionManager.release();
      handleError(e);
    }
  }

  /** Called on any thread. */
  private void handleError(Exception e) {
    handlerExecutor.execute(
        () -> {
          if (state == STATE_ERROR || state == STATE_CLOSED) {
            return;
          }
          state = STATE_ERROR;
          closeInternal();
        });
    listenerExecutor.execute(() -> listener.onError(VideoFrameProcessingException.from(e)));
  }

  /** Methods called on {@link #looper}. */
  private final class SessionManagerListener implements EnhancementSessionManager.Listener {
    @Override
    public void onSessionReady(EnhancementSession session, EnhancementOptions options) {
      checkState(Looper.myLooper() == looper);
      EnhancementSessionDecorator.this.onSessionReady(session, options);
    }

    @Override
    public void onError(VideoFrameProcessingException exception) {
      checkState(Looper.myLooper() == looper);
      handleError(exception);
    }
  }

  /** Methods called on {@link #looper}. */
  private final class SurfaceToFrameWriterAdapterListener
      implements SurfaceToFrameWriterAdapter.Listener {
    @Override
    public void onFrameReleased() {
      checkState(Looper.myLooper() == looper);
      frameToSurfaceFrameWriter.onFrameReleased();
      listenerExecutor.execute(listener::onWakeup);
    }

    @Override
    public void onError(VideoFrameProcessingException exception) {
      checkState(Looper.myLooper() == looper);
      handleError(exception);
    }
  }

  /** Methods called on {@link #listenerExecutor}. */
  private final class BaseFrameProcessorListener implements Listener {
    @Override
    public void onWakeup() {
      listener.onWakeup();
    }

    @Override
    public void onError(VideoFrameProcessingException exception) {
      handleError(exception);
    }

    @Override
    public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {
      listener.onFrameProcessed(frame, onCompleteFence);
    }
  }

  /** Methods called on {@link #looper}. */
  private final class FrameToSurfaceFrameWriterListener
      implements FrameToSurfaceFrameWriter.Listener {
    @Override
    public void onFormatConfigured(Format format) {
      checkState(Looper.myLooper() == looper);
      if (state == STATE_WAITING_FOR_FORMAT) {
        inputFormat = format;
        sessionManager.initializeSession(
            format.width,
            format.height,
            isTonemappingEnabled,
            isDeblurAndDenoiseVideoEnabled,
            isUpscaleVideoEnabled);
        state = STATE_INITIALIZING;
      }
    }

    @Override
    public void onFrameQueued(long presentationTimeUs) {
      checkState(Looper.myLooper() == looper);
      surfaceToFrameWriterAdapter.onInputFrameQueued(presentationTimeUs);
    }

    @Override
    public void onEndOfStream() {
      checkState(Looper.myLooper() == looper);
      surfaceToFrameWriterAdapter.onEndOfStream();
    }

    @Override
    public void onError(VideoFrameProcessingException exception) {
      checkState(Looper.myLooper() == looper);
      handleError(exception);
    }
  }
}
