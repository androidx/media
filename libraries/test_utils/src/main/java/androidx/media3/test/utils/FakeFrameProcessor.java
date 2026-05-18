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

import static com.google.common.collect.ImmutableList.toImmutableList;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.FrameWriter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
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
    public FakeFrameProcessor create(FrameWriter output) {
      createdProcessor = new FakeFrameProcessor(output, shouldCompleteIncomingFrames);
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
  private final boolean shouldCompleteIncomingFrames;

  @Nullable public FrameCompletionListener lastCompletionListener;
  @Nullable public List<AsyncFrame> lastFrames;

  public FakeFrameProcessor(FrameWriter output, boolean shouldCompleteIncomingFrames) {
    this.queuedEvents = new ArrayList<>();
    this.output = output;
    this.shouldCompleteIncomingFrames = shouldCompleteIncomingFrames;
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
    if (shouldCompleteIncomingFrames) {
      for (AsyncFrame asyncFrame : frames) {
        listenerExecutor.execute(() -> completionListener.onFrameProcessed(asyncFrame.frame, null));
      }
    }
    return true;
  }

  @Override
  public void signalEndOfStream() {
    queuedEvents.add(new EosEvent());
    output.signalEndOfStream();
  }

  @Override
  public void close() {}

  /** Returns true if the last {@link Event} queued was an {@link EosEvent}. */
  public boolean isEnded() {
    return !queuedEvents.isEmpty() && Iterables.getLast(queuedEvents) instanceof EosEvent;
  }

  /** Returns an {@link ImmutableList} of all queued events. */
  public ImmutableList<Event> getQueuedEvents() {
    return ImmutableList.copyOf(queuedEvents);
  }

  /**
   * Returns an {@link ImmutableList} of content timestamps for all queued frames. EOS frames return
   * {@link C#TIME_UNSET}.
   */
  public ImmutableList<ImmutableList<Long>> getQueuedContentTimesUs() {
    ImmutableList<Event> currentEvents = getQueuedEvents();
    return currentEvents.stream()
        .map(
            event -> {
              if (event instanceof EosEvent) {
                return ImmutableList.of(C.TIME_UNSET);
              }
              FramesEvent framesEvent = (FramesEvent) event;
              return framesEvent.frames.stream()
                  .map(asyncFrame -> asyncFrame.frame.getContentTimeUs())
                  .collect(toImmutableList());
            })
        .collect(toImmutableList());
  }
}
