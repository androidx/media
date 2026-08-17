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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.FrameWriter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * A {@link FrameProcessor} that delegates to an underlying {@link FrameProcessor} and records
 * queued frames and EOS signals for testing.
 */
@RequiresApi(26)
@ExperimentalApi // TODO: b/498176910 Remove once FrameProcessor is production ready.
public class CapturingFrameProcessor implements FrameProcessor {

  /** A factory for {@link CapturingFrameProcessor} instances. */
  public static class Factory implements FrameProcessor.Factory {

    private final FrameProcessor.Factory underlyingFactory;
    @Nullable private volatile CapturingFrameProcessor createdProcessor;

    /**
     * Creates a new instance.
     *
     * @param underlyingFactory The factory that creates the underlying {@link FrameProcessor}.
     */
    public Factory(FrameProcessor.Factory underlyingFactory) {
      this.underlyingFactory = checkNotNull(underlyingFactory);
    }

    @Override
    public CapturingFrameProcessor create(
        FrameWriter output, Executor listenerExecutor, Listener listener) {
      checkState(createdProcessor == null, "Factory can only create one processor");
      FrameProcessor underlyingProcessor =
          underlyingFactory.create(output, listenerExecutor, listener);
      createdProcessor = new CapturingFrameProcessor(underlyingProcessor);
      return checkNotNull(createdProcessor);
    }

    /** Returns the created {@link CapturingFrameProcessor} instance. */
    @Nullable
    public CapturingFrameProcessor getCreatedProcessor() {
      return createdProcessor;
    }
  }

  /** Marker interface for ordering frames and EOS signals. */
  public interface Event {}

  /** Holds the frames queued in a {@link #queue} call. */
  public static final class FramesEvent implements Event {
    public final ImmutableList<AsyncFrame> frames;

    /**
     * Creates a new instance.
     *
     * @param frames The frames to record. Will be defensively copied.
     */
    public FramesEvent(List<AsyncFrame> frames) {
      this.frames = ImmutableList.copyOf(frames);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Performs an identity-based comparison of the frames in the list, as {@link AsyncFrame}
     * does not implement {@code equals()}.
     */
    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      FramesEvent that = (FramesEvent) o;
      return frames.equals(that.frames);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(frames);
    }

    @Override
    public String toString() {
      ImmutableList<Long> contentTimesUs =
          frames.stream()
              .map(asyncFrame -> asyncFrame.frame.getContentTimeUs())
              .collect(toImmutableList());
      return "FramesEvent(" + contentTimesUs + ")";
    }
  }

  /** Marker class representing a {@link #signalEndOfStream()} call. */
  public static final class EosEvent implements Event {
    @Override
    public boolean equals(@Nullable Object o) {
      return o instanceof EosEvent;
    }

    @Override
    public int hashCode() {
      return EosEvent.class.hashCode();
    }

    @Override
    public String toString() {
      return "EOS";
    }
  }

  private final FrameProcessor underlyingProcessor;

  /** All the frames and EOS events queued to this {@link FrameProcessor}. */
  private final CopyOnWriteArrayList<Event> queuedEvents;

  private CapturingFrameProcessor(FrameProcessor underlyingProcessor) {
    this.underlyingProcessor = underlyingProcessor;
    this.queuedEvents = new CopyOnWriteArrayList<>();
  }

  @Override
  public boolean queue(List<AsyncFrame> frames) {
    FramesEvent event = new FramesEvent(frames);
    boolean queued = underlyingProcessor.queue(frames);
    if (queued) {
      queuedEvents.add(event);
    }
    return queued;
  }

  @Override
  public void signalEndOfStream() {
    queuedEvents.add(new EosEvent());
    underlyingProcessor.signalEndOfStream();
  }

  @Override
  public void close() {
    underlyingProcessor.close();
  }

  /** Returns true if the last {@link Event} queued was an {@link EosEvent}. */
  public boolean isEnded() {
    return !queuedEvents.isEmpty() && Iterables.getLast(queuedEvents) instanceof EosEvent;
  }

  /** Returns an {@link ImmutableList} of all queued events. */
  public ImmutableList<Event> getQueuedEvents() {
    return ImmutableList.copyOf(queuedEvents);
  }

  /**
   * Returns a list of timestamp lists for all queued events. The list for an EOS event contains a
   * single {@link C#TIME_UNSET} value.
   */
  public ImmutableList<ImmutableList<Long>> getQueuedContentTimesUs() {
    return queuedEvents.stream()
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
