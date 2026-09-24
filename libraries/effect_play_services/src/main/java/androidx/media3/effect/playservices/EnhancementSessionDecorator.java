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

import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import androidx.annotation.GuardedBy;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.Format;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.HandlerExecutor;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.effect.ndk.HardwareBufferJni;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * A {@link FrameProcessor} decorator that routes video frames through GMS Core {@code
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
    private final FrameProcessor.Factory baseFrameProcessorFactory;

    /** Creates a builder with the specified base {@link FrameProcessor.Factory}. */
    public Builder(FrameProcessor.Factory baseFrameProcessorFactory) {
      this.baseFrameProcessorFactory = baseFrameProcessorFactory;
    }

    /** Builds the {@link FrameProcessor.Factory}. */
    public Factory build() {
      return new Factory(baseFrameProcessorFactory);
    }
  }

  /** Factory for {@link EnhancementSessionDecorator}. */
  public static final class Factory implements FrameProcessor.Factory {
    private final FrameProcessor.Factory baseFrameProcessorFactory;

    public Factory(FrameProcessor.Factory baseFrameProcessorFactory) {
      this.baseFrameProcessorFactory = baseFrameProcessorFactory;
    }

    @Override
    public FrameProcessor create(FrameWriter output, Executor listenerExecutor, Listener listener) {
      return new EnhancementSessionDecorator(
          baseFrameProcessorFactory, output, listenerExecutor, listener);
    }
  }

  private final Object lock;
  private final Executor listenerExecutor;
  private final Listener listener;

  private final HandlerThread handlerThread;
  private final Handler handler;
  private final Executor handlerExecutor;

  private final FrameProcessor baseFrameProcessor;
  private final FrameToSurfaceFrameWriter frameToSurfaceFrameWriter;
  private final SurfaceToFrameWriterAdapter surfaceToFrameWriterAdapter;

  @GuardedBy("lock")
  private @State int state;

  // Safe because handleError is only invoked asynchronously on handlerThread.
  @SuppressWarnings("method.invocation")
  private EnhancementSessionDecorator(
      FrameProcessor.Factory baseFrameProcessorFactory,
      FrameWriter downstreamOutput,
      Executor listenerExecutor,
      Listener listener) {
    lock = new Object();
    state = STATE_WAITING_FOR_FORMAT;
    this.listenerExecutor = listenerExecutor;
    this.listener = listener;

    handlerThread = new HandlerThread("EnhancementDecorator");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());
    handlerExecutor =
        new HandlerExecutor(handler, e -> handleError(VideoFrameProcessingException.from(e)));

    surfaceToFrameWriterAdapter =
        new SurfaceToFrameWriterAdapter(
            downstreamOutput,
            HardwareBufferJni.INSTANCE,
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

  @Override
  public void close() {
    baseFrameProcessor.close();
    frameToSurfaceFrameWriter.close();
    surfaceToFrameWriterAdapter.close();
    synchronized (lock) {
      state = STATE_CLOSED;
    }
    handlerThread.quitSafely();
  }

  private void handleError(Exception e) {
    synchronized (lock) {
      if (state == STATE_ERROR || state == STATE_CLOSED) {
        return;
      }
      state = STATE_ERROR;
    }
    listenerExecutor.execute(() -> listener.onError(VideoFrameProcessingException.from(e)));
  }

  private final class SurfaceToFrameWriterAdapterListener
      implements SurfaceToFrameWriterAdapter.Listener {
    @Override
    public void onFrameReleased() {
      frameToSurfaceFrameWriter.onFrameReleased();
      listenerExecutor.execute(listener::onWakeup);
    }

    @Override
    public void onError(VideoFrameProcessingException exception) {
      handleError(exception);
    }
  }

  private final class BaseFrameProcessorListener implements Listener {
    @Override
    public void onWakeup() {
      listenerExecutor.execute(listener::onWakeup);
    }

    @Override
    public void onError(VideoFrameProcessingException exception) {
      handleError(exception);
    }

    @Override
    public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {
      listenerExecutor.execute(() -> listener.onFrameProcessed(frame, onCompleteFence));
    }
  }

  private final class FrameToSurfaceFrameWriterListener
      implements FrameToSurfaceFrameWriter.Listener {
    @Override
    public void onFormatConfigured(Format format) {
      @Nullable Exception errorToReport = null;
      synchronized (lock) {
        if (state == STATE_WAITING_FOR_FORMAT) {
          state = STATE_INITIALIZING;
          try {
            Surface surface =
                surfaceToFrameWriterAdapter.configure(format.width, format.height, format);
            frameToSurfaceFrameWriter.setOutputSurface(surface, format.width, format.height);
            state = STATE_READY;
          } catch (RuntimeException e) {
            errorToReport = e;
          }
        }
      }
      // Invoke handleError outside synchronized(lock) to avoid dispatching listener callbacks
      // while holding the internal state lock.
      if (errorToReport != null) {
        handleError(errorToReport);
      }
    }

    @Override
    public void onFrameQueued(long presentationTimeUs) {
      surfaceToFrameWriterAdapter.onInputFrameQueued(presentationTimeUs);
    }

    @Override
    public void onEndOfStream() {
      surfaceToFrameWriterAdapter.onEndOfStream();
    }

    @Override
    public void onError(VideoFrameProcessingException exception) {
      handleError(exception);
    }
  }
}
