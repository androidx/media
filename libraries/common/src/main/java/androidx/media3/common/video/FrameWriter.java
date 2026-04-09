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
import androidx.media3.common.Format;
import androidx.media3.common.util.ExperimentalApi;
import java.util.concurrent.Executor;

/**
 * Allocates {@link Frame} instances, allowing callers to dequeue and fill them, before processing
 * the filled frames.
 */
@ExperimentalApi // TODO: b/498176910 Remove once FrameWriter is production ready.
public interface FrameWriter extends AutoCloseable {

  /** Describes the capabilities of a {@link FrameWriter}. Query for format support. */
  interface Info {

    /**
     * Returns whether the combination of {@link Format} and {@code usage} is supported.
     *
     * @param format The format to check.
     * @param usage The {@code @FrameUsage} flags describing how the frame will be used.
     */
    boolean isSupported(Format format, @Frame.Usage long usage);
  }

  /** Returns the {@link Info} for this FrameWriter. */
  Info getInfo();

  /**
   * Format and usage support can be {@linkplain Info#isSupported(Format, long) checked} on the
   * {@link Info} before configuring.
   *
   * <p>Must be called before {@link #dequeueInputFrame}.
   *
   * @param format The format to configure.
   * @param usage The {@code @FrameUsage} flags describing how the frame will be used.
   * @throws IllegalStateException if any frames are dequeued at the point this method is called.
   * @throws IllegalArgumentException if the format or usage is unsupported.
   */
  void configure(Format format, @Frame.Usage long usage);

  /**
   * Dequeues an {@link AsyncFrame} storing a {@link Frame} of the {@linkplain #configure(Format,
   * long) configured} format and usage, and a {@link SyncFenceWrapper} that must be waited on
   * before filling the frame.
   *
   * <p>The returned frame must be filled and {@linkplain #queueInputFrame queued} back for
   * processing.
   *
   * <p>If the {@link FrameWriter} is at capacity, this method returns null and will notify the
   * listener when capacity is available, and it can be called again.
   *
   * <p>Only the most recent {@code wakeupListener} will be notified when capacity is available.
   *
   * @param wakeupExecutor The {@link Executor} on which the {@code wakeupListener} is invoked.
   * @param wakeupListener A {@link Runnable} to be invoked when capacity becomes available.
   * @return The dequeued frame, or null if the writer is at capacity.
   * @throws IllegalStateException if {@link #configure(Format, long)} has not been called.
   */
  @Nullable
  AsyncFrame dequeueInputFrame(Executor wakeupExecutor, Runnable wakeupListener);

  /**
   * Queues a filled frame for further processing.
   *
   * <p>The queued frame must have been previously {@link #dequeueInputFrame dequeued} from this
   * writer.
   *
   * @param frame The filled frame to queue.
   * @param writeCompleteFence A {@link SyncFenceWrapper} that will signal when the caller has
   *     finished writing to the frame, or {@code null} if the write was synchronous.
   */
  void queueInputFrame(Frame frame, @Nullable SyncFenceWrapper writeCompleteFence);

  /**
   * Notifies this {@link FrameWriter} that the current stream has ended.
   *
   * <p>More frames may be queued after calling this method, if the current stream changes.
   */
  void signalEndOfStream();

  /** Blocks until all resources are released. */
  @Override
  void close();
}
