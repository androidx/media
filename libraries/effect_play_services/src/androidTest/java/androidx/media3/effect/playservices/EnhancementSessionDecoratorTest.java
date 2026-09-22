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

import androidx.media3.common.Format;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.test.utils.FakeFrameProcessor;
import androidx.media3.test.utils.FakeFrameWriter;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Hermetic unit tests for {@link EnhancementSessionDecorator}. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 33)
public final class EnhancementSessionDecoratorTest {

  private static final int WIDTH = 320;
  private static final int HEIGHT = 240;
  private static final long TIMEOUT_MS = 1_000;

  private FakeFrameProcessor.Factory fakeBaseProcessorFactory;
  private FakeFrameWriter fakeDownstreamOutput;
  private FakeFrameProcessor.Listener testListener;
  private FrameProcessor decorator;

  @Before
  public void setUp() {
    fakeBaseProcessorFactory =
        new FakeFrameProcessor.Factory(/* shouldCompleteIncomingFrames= */ true);
    fakeDownstreamOutput = new FakeFrameWriter();
    testListener = new FakeFrameProcessor.Listener();

    decorator =
        new EnhancementSessionDecorator.Builder(fakeBaseProcessorFactory)
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
    assertThat(fakeBaseProcessorFactory.createdProcessor.queuedFramesCount).isEqualTo(1);
  }

  @Test
  public void configure_validFormat_configuresDownstreamOutput() throws Exception {
    Format format = new Format.Builder().setWidth(WIDTH).setHeight(HEIGHT).build();

    fakeBaseProcessorFactory.createdProcessor.output.configure(
        format, Frame.USAGE_GPU_COLOR_OUTPUT);

    assertThat(fakeDownstreamOutput.configuredLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(fakeDownstreamOutput.configuredFormat).isNotNull();
    assertThat(fakeDownstreamOutput.configuredFormat.width).isEqualTo(WIDTH);
    assertThat(fakeDownstreamOutput.configuredFormat.height).isEqualTo(HEIGHT);
  }

  @Test
  public void signalEndOfStream_whenCalled_forwardsToBaseProcessor() {
    decorator.signalEndOfStream();

    assertThat(fakeBaseProcessorFactory.createdProcessor.eosSignaled.get()).isTrue();
  }

  @Test
  public void close_whenCalled_shutsDownBaseProcessor() {
    decorator.close();

    assertThat(fakeBaseProcessorFactory.createdProcessor.closed.get()).isTrue();
  }
}
