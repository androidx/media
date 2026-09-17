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
package androidx.media3.effect.playservices;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.Format;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.HandlerExecutor;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.common.video.HardwareBufferNativeHelpers;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

/**
 * Creates and provides an output {@link Surface} and forwards frames rendered to it into a
 * downstream {@link FrameWriter} via a hardware buffer copy.
 *
 * <p>Handles {@link ImageReader} buffer acquisition, native hardware buffer copying, and
 * end-of-stream signaling.
 *
 * <p>Thread safety: All public methods ({@link #configure}, {@link #onInputFrameQueued}, {@link
 * #onEndOfStream}) must be called on the thread associated with the {@link Handler} passed to the
 * constructor. {@link #close} may be called from any thread.
 */
@RequiresApi(33)
/* package */ final class SurfaceToFrameWriterAdapter implements AutoCloseable {

  private static final int MAX_IMAGES = 1;
  private static final long CLOSE_TIMEOUT_MS = 500L;

  /**
   * Listener for {@link SurfaceToFrameWriterAdapter} events.
   *
   * <p>All methods are invoked on the handler thread, with the exception of {@link #onError} if
   * {@link #close()} is called from an external thread and times out or is interrupted waiting for
   * cleanup on the handler thread (in which case it is invoked directly on the calling thread).
   */
  interface Listener {
    /** Called on the handler thread when an output frame has been released back to the surface. */
    void onFrameReleased();

    /** Called when an error occurs during output frame draining or copying. */
    void onError(VideoFrameProcessingException exception);
  }

  private final FrameWriter downstreamOutput;
  private final HardwareBufferNativeHelpers hardwareBufferNativeHelpers;
  private final Handler handler;
  private final Executor handlerExecutor;
  private final Listener listener;

  @Nullable private ImageReader imageReader;
  @Nullable private Surface surface;
  @Nullable private Image pendingImage;

  // All state counters and flags below are accessed exclusively on the Handler thread.
  private int totalInputFramesCount;
  private int outputFramesCount;
  private boolean isEndOfStreamReceived;
  private boolean isDownstreamEosSignaled;
  private volatile boolean isClosed;

  /**
   * Creates a new instance.
   *
   * @param downstreamOutput The downstream {@link FrameWriter} to which copied output frames are
   *     forwarded.
   * @param hardwareBufferNativeHelpers Native helper for copying {@link HardwareBuffer} contents.
   * @param handler The {@link Handler} whose thread is used for all public method calls, {@link
   *     ImageReader} callbacks, downstream wakeup notifications, and {@link Listener} callbacks.
   * @param listener The {@link Listener} notified of image releases and errors on the {@code
   *     handler} thread.
   */
  public SurfaceToFrameWriterAdapter(
      FrameWriter downstreamOutput,
      HardwareBufferNativeHelpers hardwareBufferNativeHelpers,
      Handler handler,
      Listener listener) {
    this.downstreamOutput = downstreamOutput;
    this.hardwareBufferNativeHelpers = hardwareBufferNativeHelpers;
    this.handler = handler;
    this.handlerExecutor =
        new HandlerExecutor(handler, e -> listener.onError(VideoFrameProcessingException.from(e)));
    this.listener = listener;
  }

  /**
   * Configures the surface and the downstream {@link FrameWriter}.
   *
   * <p>Must be called on a handler thread.
   *
   * @param width The surface width in pixels.
   * @param height The surface height in pixels.
   * @param inputFormat The input {@link Format}.
   * @return The {@link Surface} created by this instance.
   */
  public Surface configure(int width, int height, Format inputFormat) {
    checkState(Looper.myLooper() == handler.getLooper());
    checkState(imageReader == null);
    // TODO(b/531659135): Set ColorInfo on outputFormat.
    Format outputFormat = inputFormat.buildUpon().setWidth(width).setHeight(height).build();
    downstreamOutput.configure(
        outputFormat,
        Frame.USAGE_GPU_COLOR_OUTPUT | Frame.USAGE_GPU_SAMPLED_IMAGE | Frame.USAGE_CPU_WRITE_OFTEN);

    imageReader =
        ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            MAX_IMAGES,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                | HardwareBuffer.USAGE_CPU_READ_OFTEN);

    imageReader.setOnImageAvailableListener(this::onImageAvailable, handler);
    Surface configuredSurface = imageReader.getSurface();
    surface = configuredSurface;
    return configuredSurface;
  }

  /**
   * Notifies the adapter that an input frame is queued to the surface.
   *
   * <p>Must be called on a handler thread.
   */
  public void onInputFrameQueued() {
    checkState(Looper.myLooper() == handler.getLooper());
    checkState(!isClosed && !isEndOfStreamReceived);
    totalInputFramesCount++;
  }

  /**
   * Signals end-of-stream on input. Checks if downstream EOS can be signaled immediately.
   *
   * <p>Must be called on a handler thread.
   */
  public void onEndOfStream() {
    checkState(Looper.myLooper() == handler.getLooper());
    isEndOfStreamReceived = true;
    maybeSignalDownstreamEos();
  }

  /**
   * Closes the adapter and releases all surfaces and buffers. May be called from any thread. If
   * called from a thread other than the handler thread, this method blocks until cleanup finishes
   * on the handler thread or times out.
   */
  @Override
  public void close() {
    if (isClosed) {
      return;
    }
    if (Looper.myLooper() != handler.getLooper()) {
      CountDownLatch latch = new CountDownLatch(1);
      boolean posted =
          handler.post(
              () -> {
                try {
                  close();
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
      } else {
        closeInternal();
      }
      return;
    }
    closeInternal();
  }

  private void closeInternal() {
    if (isClosed) {
      return;
    }
    isClosed = true;
    if (pendingImage != null) {
      pendingImage.close();
      pendingImage = null;
    }
    if (imageReader != null) {
      imageReader.close();
      imageReader = null;
    }
    if (surface != null) {
      surface.release();
      surface = null;
    }
  }

  private void onImageAvailable(ImageReader reader) {
    if (isClosed) {
      return;
    }
    tryProcessPendingOutputs();
  }

  private void tryProcessPendingOutputs() {
    if (isClosed) {
      return;
    }

    ImageReader reader = imageReader;
    if (reader == null) {
      return;
    }

    Image image = pendingImage;
    if (image == null) {
      try {
        image = reader.acquireNextImage();
      } catch (IllegalStateException e) {
        image = null;
      }
    }

    if (image == null) {
      return;
    }

    if (outputFramesCount >= totalInputFramesCount) {
      close();
      listener.onError(
          new VideoFrameProcessingException(
              "Unexpected output frame acquired beyond input frame count"));
      return;
    }

    AsyncFrame outputFrame =
        downstreamOutput.dequeueInputFrame(handlerExecutor, this::tryProcessPendingOutputs);
    if (outputFrame == null) {
      pendingImage = image;
      return;
    }

    pendingImage = null;
    copyOutputAndQueue(image, outputFrame);
  }

  private void copyOutputAndQueue(Image sourceImage, AsyncFrame targetFrame) {
    VideoFrameProcessingException copyException = null;
    Frame newFrame = null;
    try {
      if (targetFrame.acquireFence != null) {
        targetFrame.acquireFence.awaitForever();
        targetFrame.acquireFence.close();
      }

      try (HardwareBuffer sourceBuffer = checkNotNull(sourceImage.getHardwareBuffer())) {
        HardwareBuffer targetBuffer = ((HardwareBufferFrame) targetFrame.frame).getHardwareBuffer();
        boolean copySuccess =
            hardwareBufferNativeHelpers.nativeCopyHardwareBufferToHardwareBuffer(
                sourceBuffer, targetBuffer);
        if (copySuccess) {
          newFrame =
              ((HardwareBufferFrame) targetFrame.frame)
                  .buildUpon()
                  .setContentTimeUs(sourceImage.getTimestamp() / 1000L)
                  .build();
        } else {
          copyException =
              new VideoFrameProcessingException(
                  "nativeCopyHardwareBufferToHardwareBuffer failed for output");
        }
      }
    } catch (RuntimeException e) {
      copyException = new VideoFrameProcessingException("Failed to copy output frame", e);
    } finally {
      sourceImage.close();
      listener.onFrameReleased();
    }

    if (newFrame != null) {
      outputFramesCount++;
      downstreamOutput.queueInputFrame(newFrame, /* writeCompleteFence= */ null);
    }
    if (copyException != null) {
      listener.onError(copyException);
    }
    maybeSignalDownstreamEos();
  }

  /**
   * Signals end-of-stream to the downstream {@link FrameWriter} once end-of-stream has been
   * received on input and all registered input frames have been processed and forwarded downstream.
   */
  private void maybeSignalDownstreamEos() {
    if (isEndOfStreamReceived
        && outputFramesCount >= totalInputFramesCount
        && !isDownstreamEosSignaled) {
      isDownstreamEosSignaled = true;
      downstreamOutput.signalEndOfStream();
    }
  }
}
