/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.effect.playservices;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Hermetic unit tests for {@link EnhancementSessionDecorator}. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 33)
@ExperimentalApi
public final class EnhancementSessionDecoratorTest {

  private static final int WIDTH = 320;
  private static final int HEIGHT = 240;
  private static final long TIMEOUT_MS = 1_000;

  private FakeBaseFrameProcessor fakeBaseProcessor;
  private FakeFrameWriter fakeDownstreamOutput;
  private TestListener testListener;
  private FrameProcessor decorator;

  @Before
  public void setUp() {
    fakeDownstreamOutput = new FakeFrameWriter();
    testListener = new TestListener();

    decorator =
        new EnhancementSessionDecorator.Builder(
                (output, executor, listener) -> {
                  fakeBaseProcessor = new FakeBaseFrameProcessor(output, listener);
                  return fakeBaseProcessor;
                })
            .build()
            .create(fakeDownstreamOutput, Runnable::run, testListener);
  }

  @After
  public void tearDown() {
    if (decorator != null) {
      decorator.close();
    }
  }

  @Test
  public void queue_validFrames_forwardsDirectlyToBaseProcessor() {
    ImmutableList<AsyncFrame> frames = ImmutableList.of();

    boolean result = decorator.queue(frames);

    assertThat(result).isTrue();
    assertThat(fakeBaseProcessor.queuedFramesCount).isEqualTo(1);
  }

  @Test
  public void configure_validFormat_configuresDownstreamOutput() throws Exception {
    Format format = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();

    fakeBaseProcessor.outputWriter.configure(format, Frame.USAGE_GPU_COLOR_OUTPUT);

    assertThat(fakeDownstreamOutput.configuredLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(fakeDownstreamOutput.configuredFormat).isNotNull();
    assertThat(fakeDownstreamOutput.configuredFormat.width).isEqualTo(WIDTH);
    assertThat(fakeDownstreamOutput.configuredFormat.height).isEqualTo(HEIGHT);
  }

  @Test
  public void onError_whenBaseProcessorReportsError_notifiesListener() {
    fakeBaseProcessor.listener.onError(new VideoFrameProcessingException("Copy failed"));

    assertThat(testListener.error.get()).isNotNull();
    assertThat(testListener.error.get()).hasMessageThat().contains("Copy failed");
  }

  @Test
  public void signalEndOfStream_whenCalled_forwardsToBaseProcessor() {
    decorator.signalEndOfStream();

    assertThat(fakeBaseProcessor.eosSignaled.get()).isTrue();
  }

  @Test
  public void close_whenCalled_shutsDownBaseProcessor() {
    decorator.close();

    assertThat(fakeBaseProcessor.closed.get()).isTrue();
  }

  private static class FakeBaseFrameProcessor implements FrameProcessor {
    final FrameWriter outputWriter;
    final FrameProcessor.Listener listener;
    int queuedFramesCount = 0;
    final AtomicBoolean eosSignaled = new AtomicBoolean(false);
    final AtomicBoolean closed = new AtomicBoolean(false);

    FakeBaseFrameProcessor(FrameWriter outputWriter, FrameProcessor.Listener listener) {
      this.outputWriter = outputWriter;
      this.listener = listener;
    }

    @Override
    public boolean queue(List<AsyncFrame> frames) {
      queuedFramesCount++;
      return true;
    }

    @Override
    public void signalEndOfStream() {
      eosSignaled.set(true);
    }

    @Override
    public void close() {
      closed.set(true);
    }
  }

  private static class FakeFrameWriter implements FrameWriter {
    @Nullable Format configuredFormat;
    final CountDownLatch configuredLatch = new CountDownLatch(1);

    @Override
    public void configure(Format format, long usage) {
      configuredFormat = format;
      configuredLatch.countDown();
    }

    @Override
    public AsyncFrame dequeueInputFrame(Executor executor, Runnable wakeupListener) {
      return null;
    }

    @Override
    public void queueInputFrame(Frame frame, @Nullable SyncFenceWrapper writeCompleteFence) {}

    @Override
    public void signalEndOfStream() {}

    @Override
    public Info getInfo() {
      return (format, usage) -> true;
    }

    @Override
    public void close() {}
  }

  private static class TestListener implements FrameProcessor.Listener {
    final AtomicReference<Exception> error = new AtomicReference<>();

    @Override
    public void onWakeup() {}

    @Override
    public void onError(VideoFrameProcessingException exception) {
      error.set(exception);
    }

    @Override
    public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {}
  }
}
