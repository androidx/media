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
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.FrameWriter;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** A no-op {@link FrameProcessor} that holds a reference to all queued frames for testing. */
@RequiresApi(26)
@ExperimentalApi // TODO: b/498176910 Remove once FrameProcessor is production ready.
public class FakeFrameProcessor implements FrameProcessor {

  /** A factory for {@link FakeFrameProcessor} implementations. */
  public static class Factory implements FrameProcessor.Factory {

    @Nullable public FakeFrameProcessor createdProcessor;

    @Override
    public FakeFrameProcessor create(FrameWriter output) {
      createdProcessor = new FakeFrameProcessor(output);
      return createdProcessor;
    }
  }

  /** Marker interface for ordering frames and EOS signals. */
  public interface Event {}

  /** Holds the frames queued in a {@link #queue} call. */
  public static final class FramesEvent implements Event {
    public final List<AsyncFrame> frames;

    public FramesEvent(List<AsyncFrame> frames) {
      this.frames = frames;
    }
  }

  /** Marker class representing a {@link #signalEndOfStream()} call. */
  public static final class EosEvent implements Event {}

  /** All the frames and EOS events queued to this {@link FrameProcessor}. */
  private final List<Event> queuedEvents;

  private final FrameWriter output;

  @Nullable public FrameCompletionListener lastCompletionListener;
  @Nullable public List<AsyncFrame> lastFrames;

  public FakeFrameProcessor(FrameWriter output) {
    this.queuedEvents = new ArrayList<>();
    this.output = output;
  }

  @Override
  public boolean queue(
      List<AsyncFrame> frames,
      Executor listenerExecutor,
      Runnable wakeupListener,
      FrameCompletionListener completionListener)
      throws VideoFrameProcessingException {
    queuedEvents.add(new FramesEvent(frames));
    this.lastCompletionListener = completionListener;
    this.lastFrames = frames;
    return true;
  }

  @Override
  public void signalEndOfStream() {
    queuedEvents.add(new EosEvent());
    output.signalEndOfStream();
  }

  @Override
  public void close() {}

  /** Returns an {@link ImmutableList} of all queued events. */
  public ImmutableList<Event> getQueuedEvents() {
    return ImmutableList.copyOf(queuedEvents);
  }
}
