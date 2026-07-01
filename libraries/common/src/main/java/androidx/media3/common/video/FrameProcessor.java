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
package androidx.media3.common.video;

import androidx.annotation.Nullable;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import java.util.List;
import java.util.concurrent.Executor;

/** Consumes {@linkplain List<AsyncFrame> frames}, and outputs to a {@link FrameWriter}. */
@ExperimentalApi // TODO: b/498176910 Remove once FrameProcessor is production ready.
public interface FrameProcessor extends AutoCloseable {

  /** Listener for {@link FrameProcessor} events. */
  interface Listener {

    /**
     * Notifies the {@link Listener} that the {@link FrameProcessor} is ready for {@link #queue} to
     * be called again.
     */
    void onWakeup();

    /**
     * Notifies the {@link Listener} that an exception has occurred during frame processing.
     *
     * @param exception The {@link VideoFrameProcessingException}.
     */
    void onError(VideoFrameProcessingException exception);

    /**
     * Notifies the {@link Listener} that a frame has been fully processed.
     *
     * @param frame The queued {@link Frame}.
     * @param onCompleteFence A {@link SyncFenceWrapper} that will signal when the processor has
     *     finished reading from the frame, or {@code null} if the read was synchronous.
     */
    void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence);
  }

  /** Factory for FrameProcessor instances. */
  interface Factory {

    /**
     * Creates a {@link FrameProcessor} that sends frames to the given {@link FrameWriter}.
     *
     * <p>The created {@link FrameProcessor} does not {@linkplain FrameWriter#close() close} the
     * passed in {@link FrameWriter}.
     *
     * @param output The {@link FrameWriter} to which the {@link FrameProcessor} outputs.
     * @param listenerExecutor The {@link Executor} on which the {@code listener} is invoked.
     * @param listener A {@link Listener} to be invoked for {@link FrameProcessor} events.
     */
    FrameProcessor create(FrameWriter output, Executor listenerExecutor, Listener listener);
  }

  /**
   * Attempts to queue a {@link List} of {@linkplain AsyncFrame frames} for processing.
   *
   * <p>All frames provided in a single invocation of this method represent the exact same point in
   * time.
   *
   * <p>If this consumer is at capacity, this method returns {@code false} and the {@link
   * Listener#onWakeup()} will be invoked when capacity becomes available.
   *
   * <p>If this method returns {@code true}, {@link FrameProcessor.Listener#onFrameProcessed} must
   * be called once with every input {@link AsyncFrame#frame} instance queued, once the {@link
   * FrameProcessor} has finished processing the {@code frames}.
   *
   * @param frames The frames to queue.
   * @return {@code true} if the frames were queued, {@code false} if the consumer is at capacity.
   */
  boolean queue(List<AsyncFrame> frames);

  /**
   * Notifies this processor that the current stream has ended.
   *
   * <p>More frames may be queued after calling this method, if the current stream changes.
   */
  void signalEndOfStream();

  /** Blocks until all resources are released. */
  @Override
  void close();
}
