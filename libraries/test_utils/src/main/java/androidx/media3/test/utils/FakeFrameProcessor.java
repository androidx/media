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

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.SyncFenceWrapper;
import java.util.List;
import java.util.concurrent.Executor;

/** A fake {@link FrameProcessor} implementation. */
@RequiresApi(26)
@ExperimentalApi // TODO: b/498176910 Remove once FrameProcessor is production ready.
public class FakeFrameProcessor implements FrameProcessor {

  /** A factory for {@link FakeFrameProcessor} implementations. */
  public static class Factory implements FrameProcessor.Factory {

    @Nullable public FakeFrameProcessor createdProcessor;
    private final boolean shouldCompleteIncomingFrames;

    /**
     * Creates a new instance.
     *
     * @param shouldCompleteIncomingFrames When true, the {@link FrameProcessor} will call the
     *     completion listener for every queued frame.
     */
    public Factory(boolean shouldCompleteIncomingFrames) {
      this.shouldCompleteIncomingFrames = shouldCompleteIncomingFrames;
    }

    @Override
    public FakeFrameProcessor create(
        FrameWriter output, Executor listenerExecutor, Listener listener) {
      createdProcessor =
          new FakeFrameProcessor(output, listenerExecutor, listener, shouldCompleteIncomingFrames);
      return createdProcessor;
    }

    /** Helper create method for tests that don't need to specify listener. */
    public FakeFrameProcessor create(FrameWriter output) {
      createdProcessor =
          new FakeFrameProcessor(
              output,
              /* listenerExecutor= */ Runnable::run,
              new Listener() {
                @Override
                public void onWakeup() {}

                @Override
                public void onError(VideoFrameProcessingException exception) {}

                @Override
                public void onFrameProcessed(
                    Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {}
              },
              shouldCompleteIncomingFrames);
      return createdProcessor;
    }
  }

  private final FrameWriter output;
  private final Executor listenerExecutor;
  private final Listener listener;
  private final boolean shouldCompleteIncomingFrames;

  private boolean configured;

  private FakeFrameProcessor(
      FrameWriter output,
      Executor listenerExecutor,
      Listener listener,
      boolean shouldCompleteIncomingFrames) {
    this.output = output;
    this.listenerExecutor = listenerExecutor;
    this.listener = listener;
    this.shouldCompleteIncomingFrames = shouldCompleteIncomingFrames;
  }

  @Override
  public boolean queue(List<AsyncFrame> frames) {
    if (!configured && !frames.isEmpty()) {
      output.configure(frames.get(0).frame.getFormat(), /* usage= */ 0);
      configured = true;
      @Nullable
      AsyncFrame placeholderFrame =
          output.dequeueInputFrame(directExecutor(), /* wakeupListener= */ () -> {});
      if (placeholderFrame != null) {
        // This forces lazy configuration on some FrameWriter implementations.
        output.queueInputFrame(placeholderFrame.frame, /* writeCompleteFence= */ null);
      }
    }
    if (shouldCompleteIncomingFrames) {
      for (AsyncFrame asyncFrame : frames) {
        listenerExecutor.execute(
            () -> listener.onFrameProcessed(asyncFrame.frame, /* onCompleteFence= */ null));
      }
    }
    return true;
  }

  @Override
  public void signalEndOfStream() {
    output.signalEndOfStream();
  }

  @Override
  public void close() {}

  /** Simulates a video frame processing error by notifying the listener. */
  public void triggerError(VideoFrameProcessingException exception) {
    listenerExecutor.execute(() -> listener.onError(exception));
  }
}
