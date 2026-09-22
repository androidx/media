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
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link FakeFrameProcessor}. */
@RunWith(AndroidJUnit4.class)
public final class FakeFrameProcessorTest {

  @Test
  public void factoryCreate_returnsProcessorAndMaintainsReference() {
    FakeFrameProcessor.Factory factory =
        new FakeFrameProcessor.Factory(/* shouldCompleteIncomingFrames= */ false);

    FakeFrameProcessor processor = factory.create(new FakeFrameWriter());

    assertThat(processor).isNotNull();
    assertThat(factory.createdProcessor).isSameInstanceAs(processor);
  }

  @Test
  public void close_doesNotThrow() {
    FakeFrameProcessor processor =
        new FakeFrameProcessor.Factory(/* shouldCompleteIncomingFrames= */ false)
            .create(new FakeFrameWriter());

    // Should complete without exceptions.
    processor.close();
  }

  @Test
  public void queue_withCompleteIncomingFramesTrue_invokesCompletionListener() throws Exception {
    FakeFrameProcessor.Listener listener = new FakeFrameProcessor.Listener();
    FakeFrameProcessor processor =
        new FakeFrameProcessor.Factory(/* shouldCompleteIncomingFrames= */ true)
            .create(new FakeFrameWriter(), Runnable::run, listener);
    AsyncFrame frame = new AsyncFrame(createPlaceholderFrame(), /* acquireFence= */ null);
    ImmutableList<AsyncFrame> frames = ImmutableList.of(frame);

    boolean queued = processor.queue(frames);

    assertThat(queued).isTrue();
    assertThat(listener.processedFrames).containsExactly(frame.frame);
  }

  @Test
  public void triggerError_invokesOnErrorListener() throws Exception {
    FakeFrameProcessor.Listener listener = new FakeFrameProcessor.Listener();
    FakeFrameProcessor processor =
        new FakeFrameProcessor.Factory(/* shouldCompleteIncomingFrames= */ false)
            .create(new FakeFrameWriter(), Runnable::run, listener);
    VideoFrameProcessingException exception = new VideoFrameProcessingException("Test error");

    processor.triggerError(exception);

    assertThat(listener.error.get()).isSameInstanceAs(exception);
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
