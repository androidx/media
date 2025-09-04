/*
 * Copyright 2025 The Android Open Source Project
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

import androidx.annotation.Nullable;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.Executor;

/**
 * A processor that transforms input {@link Frame}s into 0 or more output {@link Frame}s.
 *
 * <p>This interface is experimental and subject to change.
 *
 * <p>Implementations of this interface must be thread-safe. All methods may be called from any
 * thread.
 *
 * @param <I> The type of the input {@link Frame}.
 * @param <O> The type of the output {@link Frame}.
 */
/* package */ interface FrameProcessor<I extends Frame, O extends Frame> {

  /**
   * Returns the {@link FrameConsumer} that accepts input for this processor.
   *
   * <p>Upstream components should queue frames to this consumer to have them processed.
   */
  FrameConsumer<I> getInput();

  /**
   * Sets the {@link FrameConsumer} that will receive the output frames from this processor.
   *
   * <p>{@linkplain FrameConsumer#queueFrame Queueing a frame} after this method returns guarantees
   * that the {@code output} will be set when the frame is processed.
   *
   * @param output The {@link FrameConsumer} to which output frames will be sent.
   * @return A {@link ListenableFuture} that completes when output processor has been set.
   */
  ListenableFuture<Void> setOutputAsync(@Nullable FrameConsumer<O> output);

  /**
   * Releases all resources.
   *
   * @return A {@link ListenableFuture} that completes when the processor has been released.
   */
  ListenableFuture<Void> releaseAsync();

  /**
   * Sets a {@linkplain Consumer<VideoFrameProcessingException> callback} to be run when an
   * unrecoverable error occurs.
   *
   * <p>If a callback and executor are already set, they will be replaced.
   *
   * @param executor The {@link Executor} to invoke {@code onErrorCallback} on.
   * @param onErrorCallback The {@linkplain Consumer<VideoFrameProcessingException> callback} to run
   *     when there is an error.
   */
  void setOnErrorCallback(
      Executor executor, Consumer<VideoFrameProcessingException> onErrorCallback);

  /** Unregisters the {@linkplain #setOnErrorCallback error callback}. */
  void clearOnErrorCallback();
}
