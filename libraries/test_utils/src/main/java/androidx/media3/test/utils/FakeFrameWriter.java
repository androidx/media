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
package androidx.media3.test.utils;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.Format;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.SyncFenceWrapper;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** A fake {@link FrameWriter} implementation for testing. */
@RequiresApi(26)
@ExperimentalApi // TODO: b/498176910 - Remove once FrameWriter is production ready.
public class FakeFrameWriter implements FrameWriter {

  public final List<Frame> queuedFrames;
  public final AtomicBoolean eosSignaled;
  public final CountDownLatch configuredLatch;
  public final CountDownLatch eosLatch;
  public final CountDownLatch dequeueAttemptedLatch;

  @Nullable public volatile Format configuredFormat;
  public volatile CountDownLatch frameLatch;

  private final Queue<AsyncFrame> availableFrames;

  @Nullable private volatile Executor wakeupExecutor;
  @Nullable private volatile Runnable wakeupListener;

  /** Creates a new instance. */
  public FakeFrameWriter() {
    queuedFrames = new CopyOnWriteArrayList<>();
    eosSignaled = new AtomicBoolean(false);
    configuredLatch = new CountDownLatch(1);
    eosLatch = new CountDownLatch(1);
    dequeueAttemptedLatch = new CountDownLatch(1);
    frameLatch = new CountDownLatch(1);
    availableFrames = new ConcurrentLinkedQueue<>();
  }

  @Override
  public void configure(Format format, @Frame.Usage long usage) {
    this.configuredFormat = format;
    configuredLatch.countDown();
  }

  @Override
  @Nullable
  public AsyncFrame dequeueInputFrame(Executor wakeupExecutor, Runnable wakeupListener) {
    this.wakeupExecutor = wakeupExecutor;
    this.wakeupListener = wakeupListener;
    dequeueAttemptedLatch.countDown();
    return availableFrames.poll();
  }

  @Override
  public void queueInputFrame(Frame frame, @Nullable SyncFenceWrapper writeCompleteFence) {
    if (writeCompleteFence != null) {
      writeCompleteFence.close();
    }
    queuedFrames.add(frame);
    frameLatch.countDown();
  }

  @Override
  public void signalEndOfStream() {
    eosSignaled.set(true);
    eosLatch.countDown();
  }

  @Override
  public Info getInfo() {
    return (format, usage) -> true;
  }

  @Override
  public void close() {}

  /** Makes a frame available to be returned by the next {@link #dequeueInputFrame} call. */
  public void prepareInputFrame(Frame frame) {
    availableFrames.add(new AsyncFrame(frame, /* acquireFence= */ null));
    @Nullable Executor executor = this.wakeupExecutor;
    @Nullable Runnable listener = this.wakeupListener;
    if (executor != null && listener != null) {
      executor.execute(listener);
    }
  }
}
