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
package androidx.media3.effect;

import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_FRAME_DISCONTINUITY_NUMBER;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import androidx.media3.common.C;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.effect.GlTextureFrameConsumer.GlTextureFrameProcessor;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.jspecify.annotations.Nullable;

/**
 * Base implementation of {@link GlTextureFrameProcessor}.
 *
 * <p>Methods must be called on the OpenGL thread unless otherwise stated.
 */
/* package */ abstract class BaseGlTextureFrameProcessor implements GlTextureFrameProcessor {

  protected @MonotonicNonNull GlTextureFrameConsumer downstreamConsumer;

  private final Executor errorConsumerExecutor;
  private final Consumer<VideoFrameProcessingException> errorConsumer;

  private @Nullable Runnable pendingWakeupListener;
  private @Nullable Executor pendingWakeupExecutor;
  private int currentStreamDiscontinuityNumber;

  /**
   * Creates an instance.
   *
   * <p>May be called on any thread.
   *
   * @param errorConsumerExecutor The {@link Executor} on which {@code errorConsumer} is invoked.
   * @param errorConsumer The {@link Consumer} to report errors to.
   */
  protected BaseGlTextureFrameProcessor(
      Executor errorConsumerExecutor, Consumer<VideoFrameProcessingException> errorConsumer) {
    this.errorConsumerExecutor = errorConsumerExecutor;
    this.errorConsumer = errorConsumer;
    currentStreamDiscontinuityNumber = C.INDEX_UNSET;
  }

  @Override
  public final void setOutput(GlTextureFrameConsumer downstreamConsumer) {
    checkState(!hasInFlightFrames());
    this.downstreamConsumer = downstreamConsumer;
  }

  @Override
  public final boolean queue(
      GlTextureFrame frame, Executor listenerExecutor, Runnable wakeupListener)
      throws VideoFrameProcessingException {
    checkArgument(!listenerExecutor.equals(directExecutor()));
    checkState(downstreamConsumer != null);
    int frameDiscontinuityNumber =
        (int)
            frame
                .getMetadata()
                .getOrDefault(KEY_FRAME_DISCONTINUITY_NUMBER, /* defaultValue= */ C.INDEX_UNSET);
    if (frameDiscontinuityNumber != C.INDEX_UNSET) {
      // Previewing mode.
      if (currentStreamDiscontinuityNumber > frameDiscontinuityNumber) {
        // Frame from the old stream arrives, ignore it.
        frame.release(/* releaseFence= */ null);
        return true;
      }

      if (currentStreamDiscontinuityNumber < frameDiscontinuityNumber) {
        // Discontinuity number changed, need to flush internal state.
        currentStreamDiscontinuityNumber = frameDiscontinuityNumber;
        clearPendingWakeupListener();
        onFrameDiscontinuity();
      }
    }
    clearPendingWakeupListener();
    return onQueueFrame(frame, listenerExecutor, wakeupListener);
  }

  @Override
  public final void signalEndOfStream() {
    checkState(downstreamConsumer != null);
    onEndOfStream();
  }

  @Override
  public final void close() throws VideoFrameProcessingException {
    // TODO: b/575451990 - Guard post-close calls and late callbacks.
    clearPendingWakeupListener();
    onClose();
  }

  /** Registers a wakeup listener to be invoked when capacity becomes available. */
  protected final void setPendingWakeupListener(
      Executor listenerExecutor, Runnable wakeupListener) {
    pendingWakeupExecutor = listenerExecutor;
    pendingWakeupListener = wakeupListener;
  }

  /**
   * Reports an error to {@code errorConsumer} on {@code errorConsumerExecutor}.
   *
   * <p>May be called on any thread.
   */
  protected final void onError(Exception exception) {
    errorConsumerExecutor.execute(
        () -> errorConsumer.accept(VideoFrameProcessingException.from(exception)));
  }

  /**
   * Invokes and clears the pending wakeup listener on its executor, if one is registered.
   *
   * <p>Any {@link RuntimeException} thrown by the listener is forwarded to {@link #onError}.
   */
  protected final void maybeInvokePendingWakeupListener() {
    if (pendingWakeupListener == null || pendingWakeupExecutor == null) {
      return;
    }
    Runnable listener = pendingWakeupListener;
    Executor executor = pendingWakeupExecutor;
    pendingWakeupListener = null;
    pendingWakeupExecutor = null;
    executor.execute(() -> {
      try {
        listener.run();
      } catch (RuntimeException e) {
        onError(e);
      }
    });
  }

  /** Returns whether frames are currently in flight. */
  protected abstract boolean hasInFlightFrames();

  /**
   * Called when an input frame arrives with a higher {@link
   * DefaultGlFrameProcessor#KEY_FRAME_DISCONTINUITY_NUMBER} than the current stream.
   */
  protected abstract void onFrameDiscontinuity();

  /**
   * Attempts to queue {@code frame} for processing.
   *
   * <p>When returning {@code false}: if this processor handles the frame itself, delegate wakeup
   * handling to the base class via {@link #setPendingWakeupListener(Executor, Runnable)}; if it
   * passes the frame through, pass {@code listenerExecutor} and {@code wakeupListener} to {@link
   * #downstreamConsumer} as well.
   *
   * @param frame The input frame to process.
   * @param listenerExecutor The {@link Executor} to run {@code wakeupListener} on.
   * @param wakeupListener The callback invoked when capacity becomes available.
   * @return {@code true} if the frame was accepted, or {@code false} if at capacity.
   */
  // TODO: b/589742674 - Centralize wakeup registration and pass-through in
  // BaseGlTextureFrameProcessor once GlShaderProgramAdapter avoids synchronous wakeups during
  // signalEndOfCurrentInputStream().
  protected abstract boolean onQueueFrame(
      GlTextureFrame frame, Executor listenerExecutor, Runnable wakeupListener)
      throws VideoFrameProcessingException;

  /** Called from {@link #signalEndOfStream()} when an output consumer is set. */
  protected abstract void onEndOfStream();

  /** Called from {@link #close()} to release subclass resources. */
  protected abstract void onClose() throws VideoFrameProcessingException;

  private void clearPendingWakeupListener() {
    pendingWakeupListener = null;
    pendingWakeupExecutor = null;
  }
}
