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

  /** Factory for FrameProcessor instances. */
  interface Factory {

    /** Creates a {@link FrameProcessor} that sends frames to the given {@link FrameWriter}. */
    FrameProcessor create(FrameWriter output);
  }

  /** A listener for frame completion events. */
  interface FrameCompletionListener {

    /**
     * Called when a frame has been fully processed.
     *
     * @param frame The queued {@link Frame}.
     * @param onCompleteFence A {@link SyncFenceWrapper} that will signal when the processor has
     *     finished reading from the frame, or {@code null} if the read was synchronous.
     */
    void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence);
  }

  /**
   * Attempts to queue a {@link List} of {@linkplain AsyncFrame frames} for processing.
   *
   * <p>All frames provided in a single invocation of this method represent the exact same point in
   * time.
   *
   * <p>If this consumer is at capacity, this method returns {@code false} and the {@code
   * wakeupListener} will be invoked on the {@code listenerExecutor} when capacity becomes
   * available.
   *
   * <p>Only the most recent {@code wakeupListener} will be notified when capacity is available.
   *
   * <p>If this method returns {@code true}, the {@code completionListener} must be called on the
   * {@code listenerExecutor} once for every input {@link AsyncFrame#frame} queued in {@code
   * frames}.
   *
   * @param frames The frames to queue.
   * @param listenerExecutor The {@link Executor} on which the {@code wakeupListener} and {@code
   *     completionListener} are invoked.
   * @param wakeupListener A {@link Runnable} to be invoked when capacity becomes available.
   * @param completionListener A {@link FrameCompletionListener} to be invoked when the frame has
   *     been processed.
   * @return {@code true} if the frames were queued, {@code false} if the consumer is at capacity.
   * @throws VideoFrameProcessingException If processing fails.
   */
  boolean queue(
      List<AsyncFrame> frames,
      Executor listenerExecutor,
      Runnable wakeupListener,
      FrameCompletionListener completionListener)
      throws VideoFrameProcessingException;

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
