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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageWriter;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.Format;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.SyncFenceWrapper;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A {@link FrameWriter} that receives input frames from upstream and feeds them into a downstream
 * {@link Surface} via an {@link ImageWriter}.
 *
 * <p>Handles zero-copy {@link HardwareBuffer} wrapping, sync fence propagation, and backpressure
 * coordination via atomic wakeup listeners.
 */
@RequiresApi(33)
/* package */ final class FrameToSurfaceFrameWriter implements FrameWriter {

  // Bounded to 1 because the downstream Surface drops frames if multiple are pending concurrently.
  // Tracks frames in flight across ImageWriter and downstream processing until onFrameReleased().
  private static final int MAX_IN_FLIGHT_FRAMES = 1;

  /**
   * Listener for events from {@link FrameToSurfaceFrameWriter}.
   *
   * <p>All listener methods are invoked on the {@link Executor} passed to the constructor.
   */
  interface Listener {
    /** Called when the input format has been configured. */
    void onFormatConfigured(Format format);

    /**
     * Called when a frame with the given presentation timestamp (in microseconds) is about to be
     * queued into the {@link Surface}.
     *
     * <p>While the queued {@link Image} is stamped with a synthetic monotonically increasing
     * timestamp from {@link MonotonicTimestampGenerator}, this callback passes the original
     * upstream {@code presentationTimeUs}.
     *
     * <p>Used in conjunction with {@link #onFrameReleased()} to ensure only one frame is on the
     * {@link Surface} at any one point so that the {@link Surface} does not drop frames.
     */
    void onFrameQueued(long presentationTimeUs);

    /** Called when a frame processing error occurs. */
    void onError(VideoFrameProcessingException exception);

    /** Called when end-of-stream has been received from upstream. */
    void onEndOfStream();
  }

  private static final class WakeupListenerHolder {
    final Executor executor;
    final Runnable listener;

    WakeupListenerHolder(Executor executor, Runnable listener) {
      this.executor = executor;
      this.listener = listener;
    }
  }

  private final Executor listenerExecutor;
  private final Listener listener;

  // Coordinates capacity and backpressure wakeups between the upstream frame processor thread
  // (which calls dequeueInputFrame, queueInputFrame, and close) and the downstream thread (which
  // calls onFrameReleased when an output frame has been consumed or setOutputSurface when ready)
  // without locking.
  private final AtomicInteger inFlightCount;
  private final AtomicReference<@NullableType WakeupListenerHolder> pendingWakeup;
  private final MonotonicTimestampGenerator timestampGenerator;

  private volatile @MonotonicNonNull ImageWriter imageWriter;

  @Nullable private volatile Format configuredFormat;

  public FrameToSurfaceFrameWriter(Executor listenerExecutor, Listener listener) {
    this.listenerExecutor = listenerExecutor;
    this.listener = listener;
    this.inFlightCount = new AtomicInteger(0);
    this.pendingWakeup = new AtomicReference<>();
    timestampGenerator = new MonotonicTimestampGenerator();
  }

  /** Sets the target output {@link Surface} and wakes up any waiting upstream producer. */
  public void setOutputSurface(Surface surface, int width, int height) {
    checkState(imageWriter == null, "Surface already configured");
    imageWriter =
        new ImageWriter.Builder(surface)
            .setWidthAndHeight(width, height)
            .setMaxImages(MAX_IN_FLIGHT_FRAMES)
            .setUsage(
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            .build();
    wakeup();
  }

  @Override
  public Info getInfo() {
    // TODO(b/531659135): Implement getInfo() to validate supported formats and usages (e.g.
    // RGBA_8888 input, possibly of specific resolution).
    return (format, usage) -> true;
  }

  @Override
  public void configure(Format format, @Frame.Usage long usage) {
    // TODO(b/531659135): Take usage flags into account when setting up the ImageWriter.
    checkState(configuredFormat == null, "configure() must only be called once");
    configuredFormat = format;
    listenerExecutor.execute(() -> listener.onFormatConfigured(format));
  }

  @Nullable
  @Override
  public AsyncFrame dequeueInputFrame(Executor wakeupExecutor, Runnable wakeupListener) {
    // Atomically register the wakeup listener before checking capacity to avoid missed wakeups.
    pendingWakeup.set(new WakeupListenerHolder(wakeupExecutor, wakeupListener));

    if (inFlightCount.get() >= MAX_IN_FLIGHT_FRAMES) {
      return null;
    }
    ImageWriter currentImageWriter = imageWriter;
    if (currentImageWriter == null) {
      return null;
    }
    Format format = checkNotNull(configuredFormat);
    Image image;
    try {
      image = currentImageWriter.dequeueInputImage();
    } catch (IllegalStateException e) {
      // ImageWriter#dequeueInputImage throws IllegalStateException when all buffer slots
      // (maxImages) are currently dequeued. Map this to returning null per FrameWriter contract.
      return null;
    }
    if (image == null) {
      return null;
    }
    HardwareBuffer hardwareBuffer = image.getHardwareBuffer();
    if (hardwareBuffer == null) {
      image.close();
      throw new IllegalStateException("Dequeued image does not have a HardwareBuffer");
    }
    pendingWakeup.set(null);
    DefaultHardwareBufferFrame frame =
        new DefaultHardwareBufferFrame.Builder(hardwareBuffer)
            .setFormat(format)
            .setInternalImage(image)
            .build();
    return new AsyncFrame(frame, /* acquireFence= */ null);
  }

  @Override
  public void queueInputFrame(Frame frame, @Nullable SyncFenceWrapper writeCompleteFence) {
    checkArgument(
        frame instanceof DefaultHardwareBufferFrame,
        "Expected DefaultHardwareBufferFrame, got %s",
        frame.getClass().getName());
    DefaultHardwareBufferFrame hardwareBufferFrame = (DefaultHardwareBufferFrame) frame;
    Image image = (Image) checkNotNull(hardwareBufferFrame.getInternalImage());
    try {
      long presentationTimeUs = frame.getContentTimeUs();
      image.setTimestamp(timestampGenerator.getNextTimestampNs());
      if (writeCompleteFence != null) {
        image.setFence(writeCompleteFence.asSyncFence());
      }
      ImageWriter currentImageWriter = checkNotNull(imageWriter);
      inFlightCount.incrementAndGet();
      listenerExecutor.execute(() -> listener.onFrameQueued(presentationTimeUs));
      try {
        currentImageWriter.queueInputImage(image);
      } catch (IllegalStateException e) {
        inFlightCount.decrementAndGet();
        throw e;
      }
    } catch (IllegalStateException | IOException e) {
      try {
        image.close();
      } catch (RuntimeException ignored) {
        // Ignore if image is already closed.
      }
      listenerExecutor.execute(
          () ->
              listener.onError(
                  new VideoFrameProcessingException("Failed to queue input image", e)));
    }
  }

  @Override
  public void signalEndOfStream() {
    listenerExecutor.execute(listener::onEndOfStream);
  }

  @Override
  public void close() {
    pendingWakeup.set(null);
    ImageWriter writer = imageWriter;
    if (writer != null) {
      writer.close();
    }
  }

  /**
   * Notifies the frame writer that a downstream output frame has been processed and released,
   * decrementing the in-flight frame count and waking up any waiting upstream producer.
   */
  public void onFrameReleased() {
    inFlightCount.decrementAndGet();
    wakeup();
  }

  /**
   * Notifies the frame writer that downstream capacity or session state has freed up, triggering
   * any pending wakeup listener.
   */
  private void wakeup() {
    WakeupListenerHolder holder = pendingWakeup.getAndSet(null);
    if (holder != null) {
      holder.executor.execute(holder.listener);
    }
  }
}
