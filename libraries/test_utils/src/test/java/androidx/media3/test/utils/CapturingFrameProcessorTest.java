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

import androidx.media3.common.Format;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link CapturingFrameProcessor}. */
@RunWith(AndroidJUnit4.class)
public final class CapturingFrameProcessorTest {

  @Test
  public void signalEndOfStream_addsEosEventAndSignalsOutput() {
    FakeFrameWriter frameWriter = new FakeFrameWriter();
    FakeFrameProcessor.Factory fakeFactory =
        new FakeFrameProcessor.Factory(/* shouldCompleteIncomingFrames= */ false);
    CapturingFrameProcessor.Factory capturingFactory =
        new CapturingFrameProcessor.Factory(fakeFactory);
    CapturingFrameProcessor processor =
        capturingFactory.create(frameWriter, Runnable::run, new FakeFrameProcessor.Listener());

    processor.signalEndOfStream();

    ImmutableList<CapturingFrameProcessor.Event> events = processor.getQueuedEvents();
    assertThat(events).hasSize(1);
    assertThat(events.get(0)).isInstanceOf(CapturingFrameProcessor.EosEvent.class);
    assertThat(frameWriter.eosSignaled.get()).isTrue();
  }

  @Test
  public void queueAndSignalEndOfStream_maintainsOrder() {
    FakeFrameWriter frameWriter = new FakeFrameWriter();
    FakeFrameProcessor.Factory fakeFactory =
        new FakeFrameProcessor.Factory(/* shouldCompleteIncomingFrames= */ false);
    CapturingFrameProcessor.Factory capturingFactory =
        new CapturingFrameProcessor.Factory(fakeFactory);
    CapturingFrameProcessor processor =
        capturingFactory.create(frameWriter, Runnable::run, new FakeFrameProcessor.Listener());
    ImmutableList<AsyncFrame> frames = ImmutableList.of();

    boolean queued = processor.queue(frames);
    processor.signalEndOfStream();

    assertThat(queued).isTrue();
    ImmutableList<CapturingFrameProcessor.Event> events = processor.getQueuedEvents();
    assertThat(events).hasSize(2);
    assertThat(events.get(0)).isInstanceOf(CapturingFrameProcessor.FramesEvent.class);
    assertThat(events.get(1)).isInstanceOf(CapturingFrameProcessor.EosEvent.class);
  }

  @Test
  public void queue_multipleTimes_addsMultipleEvents() throws Exception {
    FakeFrameWriter frameWriter = new FakeFrameWriter();
    FakeFrameProcessor.Factory fakeFactory =
        new FakeFrameProcessor.Factory(/* shouldCompleteIncomingFrames= */ false);
    CapturingFrameProcessor.Factory capturingFactory =
        new CapturingFrameProcessor.Factory(fakeFactory);
    CapturingFrameProcessor processor =
        capturingFactory.create(frameWriter, Runnable::run, new FakeFrameProcessor.Listener());

    // First queue call
    AsyncFrame frame1 = new AsyncFrame(createPlaceholderFrame(), /* acquireFence= */ null);
    ImmutableList<AsyncFrame> frames1 = ImmutableList.of(frame1);

    boolean queued1 = processor.queue(frames1);

    // Second queue call
    AsyncFrame frame2 = new AsyncFrame(createPlaceholderFrame(), /* acquireFence= */ null);
    ImmutableList<AsyncFrame> frames2 = ImmutableList.of(frame2);

    boolean queued2 = processor.queue(frames2);

    assertThat(queued1).isTrue();
    assertThat(queued2).isTrue();

    ImmutableList<CapturingFrameProcessor.Event> events = processor.getQueuedEvents();
    assertThat(events).hasSize(2);
    assertThat(((CapturingFrameProcessor.FramesEvent) events.get(0)).frames)
        .isSameInstanceAs(frames1);
    assertThat(((CapturingFrameProcessor.FramesEvent) events.get(1)).frames)
        .isSameInstanceAs(frames2);
  }

  @Test
  public void queue_withOnQueueListener_invokesListenerWhenQueued() {
    FakeFrameWriter frameWriter = new FakeFrameWriter();
    FakeFrameProcessor.Factory fakeFactory =
        new FakeFrameProcessor.Factory(/* shouldCompleteIncomingFrames= */ false);
    AtomicReference<List<AsyncFrame>> receivedFrames = new AtomicReference<>();
    CapturingFrameProcessor.Factory capturingFactory =
        new CapturingFrameProcessor.Factory(fakeFactory, receivedFrames::set);
    CapturingFrameProcessor processor =
        capturingFactory.create(frameWriter, Runnable::run, new FakeFrameProcessor.Listener());
    AsyncFrame frame = new AsyncFrame(createPlaceholderFrame(), /* acquireFence= */ null);
    ImmutableList<AsyncFrame> frames = ImmutableList.of(frame);

    boolean queued = processor.queue(frames);

    assertThat(queued).isTrue();
    assertThat(receivedFrames.get()).isSameInstanceAs(frames);
  }

  private static Frame createPlaceholderFrame() {
    return new Frame() {
      @Override
      public Format getFormat() {
        return new Format.Builder().build();
      }

      @Override
      public ImmutableMap<String, Object> getMetadata() {
        return ImmutableMap.of();
      }

      @Override
      public long getContentTimeUs() {
        return 0;
      }
    };
  }
}
