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

import java.util.concurrent.Executor;

/**
 * A consumer that accepts {@link Frame}s for processing.
 *
 * <p>This interface is experimental and subject to change.
 *
 * <p>Methods on this interface must only be called from a single thread.
 *
 * @param <I> The type of the input {@link Frame}.
 */
/* package */ interface FrameConsumer<I extends Frame> {

  /**
   * Attempts to queue an input {@link Frame} for processing.
   *
   * <p>If this method returns {@code true}, the caller transfers {@code frame}'s ownership to this
   * {@link FrameConsumer} instance. The caller must not modify the {@code frame} after it has been
   * successfully queued.
   *
   * @param frame The input {@link Frame} to process.
   * @return {@code true} if the frame was accepted and queued for processing. {@code false} if the
   *     processor is at capacity and cannot accept the frame at this time.
   */
  boolean queueFrame(I frame);

  /**
   * Sets a {@linkplain Runnable callback} to be run when the consumer has capacity available.
   *
   * @param executor The {@link Executor} to run {@code onCapacityAvailableCallback} on.
   * @param onCapacityAvailableCallback The {@link Runnable} to run when there is capacity
   *     available.
   * @throws IllegalStateException If a capacity listener is already set.
   */
  void setOnCapacityAvailableCallback(Executor executor, Runnable onCapacityAvailableCallback);

  /** Unregisters the {@linkplain #setOnCapacityAvailableCallback onCapacityAvailableCallback}. */
  void clearOnCapacityAvailableCallback();
}
