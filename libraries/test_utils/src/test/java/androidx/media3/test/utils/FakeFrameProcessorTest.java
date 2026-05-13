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

import static com.google.common.truth.Truth.assertThat;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor.FrameCompletionListener;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.concurrent.Executor;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link FakeFrameProcessor}. */
@RunWith(AndroidJUnit4.class)
public final class FakeFrameProcessorTest {

  @Test
  public void factoryCreate_returnsProcessorAndMaintainsReference() {
    FakeFrameProcessor.Factory factory = new FakeFrameProcessor.Factory();
    TestFrameWriter frameWriter = new TestFrameWriter();

    FakeFrameProcessor processor = factory.create(frameWriter);

    assertThat(processor).isNotNull();
    assertThat(factory.createdProcessor).isSameInstanceAs(processor);
  }

  @Test
  public void queue_addsFramesEventAndStoresListeners() throws Exception {
    TestFrameWriter frameWriter = new TestFrameWriter();
    FakeFrameProcessor processor = new FakeFrameProcessor(frameWriter);
    ImmutableList<AsyncFrame> frames = ImmutableList.of();
    FrameCompletionListener completionListener = (frame, fence) -> {};

    boolean result =
        processor.queue(
            frames,
            /* listenerExecutor= */ Runnable::run,
            /* wakeupListener= */ () -> {},
            completionListener);

    assertThat(result).isTrue();
    assertThat(processor.lastFrames).isSameInstanceAs(frames);
    assertThat(processor.lastCompletionListener).isSameInstanceAs(completionListener);

    ImmutableList<FakeFrameProcessor.Event> events = processor.getQueuedEvents();
    assertThat(events).hasSize(1);
    assertThat(events.get(0)).isInstanceOf(FakeFrameProcessor.FramesEvent.class);
    assertThat(((FakeFrameProcessor.FramesEvent) events.get(0)).frames).isSameInstanceAs(frames);
  }

  @Test
  public void signalEndOfStream_addsEosEventAndSignalsOutput() {
    TestFrameWriter frameWriter = new TestFrameWriter();
    FakeFrameProcessor processor = new FakeFrameProcessor(frameWriter);

    processor.signalEndOfStream();

    ImmutableList<FakeFrameProcessor.Event> events = processor.getQueuedEvents();
    assertThat(events).hasSize(1);
    assertThat(events.get(0)).isInstanceOf(FakeFrameProcessor.EosEvent.class);
    assertThat(frameWriter.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queueAndSignalEndOfStream_maintainsOrder() throws Exception {
    TestFrameWriter frameWriter = new TestFrameWriter();
    FakeFrameProcessor processor = new FakeFrameProcessor(frameWriter);
    ImmutableList<AsyncFrame> frames = ImmutableList.of();

    boolean queued =
        processor.queue(
            frames,
            /* listenerExecutor= */ Runnable::run,
            /* wakeupListener= */ () -> {},
            /* completionListener= */ (frame, fence) -> {});
    processor.signalEndOfStream();

    assertThat(queued).isTrue();
    ImmutableList<FakeFrameProcessor.Event> events = processor.getQueuedEvents();
    assertThat(events).hasSize(2);
    assertThat(events.get(0)).isInstanceOf(FakeFrameProcessor.FramesEvent.class);
    assertThat(events.get(1)).isInstanceOf(FakeFrameProcessor.EosEvent.class);
  }

  @Test
  public void close_doesNotThrow() {
    TestFrameWriter frameWriter = new TestFrameWriter();
    FakeFrameProcessor processor = new FakeFrameProcessor(frameWriter);

    // Should complete without exceptions.
    processor.close();
  }

  @Test
  public void queue_multipleTimes_updatesLastFieldsAndAddsMultipleEvents() throws Exception {
    TestFrameWriter frameWriter = new TestFrameWriter();
    FakeFrameProcessor processor = new FakeFrameProcessor(frameWriter);

    // First queue call
    AsyncFrame frame1 = new AsyncFrame(/* frame= */ null, /* acquireFence= */ null);
    ImmutableList<AsyncFrame> frames1 = ImmutableList.of(frame1);
    FrameCompletionListener listener1 = (frame, fence) -> {};

    boolean queued1 =
        processor.queue(
            frames1,
            /* listenerExecutor= */ Runnable::run,
            /* wakeupListener= */ () -> {},
            listener1);

    // Second queue call
    AsyncFrame frame2 = new AsyncFrame(/* frame= */ null, /* acquireFence= */ null);
    ImmutableList<AsyncFrame> frames2 = ImmutableList.of(frame2);
    FrameCompletionListener listener2 = (frame, fence) -> {};

    boolean queued2 =
        processor.queue(
            frames2,
            /* listenerExecutor= */ Runnable::run,
            /* wakeupListener= */ () -> {},
            listener2);

    assertThat(queued1).isTrue();
    assertThat(queued2).isTrue();
    assertThat(processor.lastFrames).isSameInstanceAs(frames2);
    assertThat(processor.lastCompletionListener).isSameInstanceAs(listener2);

    ImmutableList<FakeFrameProcessor.Event> events = processor.getQueuedEvents();
    assertThat(events).hasSize(2);
    assertThat(((FakeFrameProcessor.FramesEvent) events.get(0)).frames).isSameInstanceAs(frames1);
    assertThat(((FakeFrameProcessor.FramesEvent) events.get(1)).frames).isSameInstanceAs(frames2);
  }

  /** A simple Fake {@link FrameWriter} for testing. */
  private static final class TestFrameWriter implements FrameWriter {

    private boolean signalEndOfStreamCalled;

    @Override
    public Info getInfo() {
      throw new UnsupportedOperationException();
    }

    @Override
    public void configure(Format format, @Frame.Usage long usage) {}

    @Nullable
    @Override
    public AsyncFrame dequeueInputFrame(Executor wakeupExecutor, Runnable wakeupListener) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void queueInputFrame(Frame frame, @Nullable SyncFenceWrapper writeCompleteFence) {}

    @Override
    public void signalEndOfStream() {
      signalEndOfStreamCalled = true;
    }

    @Override
    public void close() {}
  }
}
