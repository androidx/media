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
package androidx.media3.effect;

import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_COMPOSITION_EFFECTS;
import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_COMPOSITION_SEQUENCE_INDEX;
import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_COMPOSITOR_SETTINGS;
import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_ITEM_EFFECTS;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.Util;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.effect.GlFrameProcessorTestUtil.FakeCompositorGlProgram;
import androidx.media3.effect.GlFrameProcessorTestUtil.FakeGlShaderProgram;
import androidx.media3.effect.GlFrameProcessorTestUtil.FakeGlTextureFrameConsumer;
import androidx.media3.effect.GlFrameProcessorTestUtil.FakeHardwareBufferConverter;
import androidx.media3.effect.GlFrameProcessorTestUtil.FakeHardwareBufferFrame;
import androidx.media3.effect.GlFrameProcessorTestUtil.NoOpFrameWriter;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DefaultGlFrameProcessor} using pluggable non-OpenGL fakes. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29)
public final class DefaultGlFrameProcessorTest {

  private static final int COMPOSITOR_CAPACITY = 1;
  private static final long SYNC_TIMEOUT_MS = 1_000;

  private final Context context = getApplicationContext();
  private Queue<Runnable> queuedFrameProcessedTasks;
  private Executor testExecutor;

  private FakeHardwareBufferConverter fakeHardwareBufferConverter;
  private AtomicInteger glTextureFramesReleased;
  private FakeGlShaderProgram fakeGlShaderProgram;
  private FakeGlTextureFrameConsumer fakeFrameWriterGlTextureFrameConsumer;
  private GlEffect fakeEffect;
  private DefaultGlFrameProcessor processor;
  private ListeningExecutorService glExecutorService;
  private NoOpFrameWriter frameWriter;

  @Before
  public void setUp() {
    frameWriter = new NoOpFrameWriter();
    glTextureFramesReleased = new AtomicInteger();
    fakeHardwareBufferConverter =
        new FakeHardwareBufferConverter(glTextureFramesReleased::incrementAndGet);
    fakeGlShaderProgram = new FakeGlShaderProgram();
    fakeFrameWriterGlTextureFrameConsumer = new FakeGlTextureFrameConsumer(frameWriter);
    fakeEffect = (context, useHdr) -> fakeGlShaderProgram;
    glExecutorService = listeningDecorator(Util.newSingleThreadExecutor("Effect:GlThread"));
    queuedFrameProcessedTasks = new ConcurrentLinkedQueue<>();
    testExecutor = queuedFrameProcessedTasks::add;

    processor =
        createDefaultGlFrameProcessorFactory()
            .create(
                frameWriter,
                testExecutor,
                new FrameProcessor.Listener() {
                  @Override
                  public void onWakeup() {}

                  @Override
                  public void onError(VideoFrameProcessingException exception) {
                    throw new AssertionError(exception);
                  }

                  @Override
                  public void onFrameProcessed(
                      Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {}
                });
  }

  @After
  public void tearDown() {
    if (processor != null) {
      processor.close();
    }
    if (glExecutorService != null) {
      FrameProcessorUtils.shutdownGlExecutorService(glExecutorService);
    }
  }

  @Test
  public void queue_singleSequenceWithNonDefaultSequenceIndex_propagatesToAllPipelineComponents()
      throws Exception {
    Frame frame = createFakeHardwareBufferFrame(/* sequenceIndex= */ 5, fakeEffect);

    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(1);
    assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(1);
    assertThat(fakeFrameWriterGlTextureFrameConsumer.framesReceived).isEqualTo(1);
    assertThat(frameWriter.queuedFrames).isEqualTo(1);

    processor.signalEndOfStream();
    waitUntilGlThreadFinishes();

    assertThat(fakeGlShaderProgram.signalEndOfCurrentInputStreamCalled).isTrue();
    assertThat(fakeFrameWriterGlTextureFrameConsumer.signalEndOfStreamCalled).isTrue();
    assertThat(frameWriter.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queue_hardwareBufferFrame_propagatesToAllPipelineComponents() throws Exception {
    Frame frame = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, fakeEffect);

    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(1);
    assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(1);
    assertThat(fakeFrameWriterGlTextureFrameConsumer.framesReceived).isEqualTo(1);
    assertThat(frameWriter.queuedFrames).isEqualTo(1);

    processor.signalEndOfStream();
    waitUntilGlThreadFinishes();

    assertThat(fakeGlShaderProgram.signalEndOfCurrentInputStreamCalled).isTrue();
    assertThat(fakeFrameWriterGlTextureFrameConsumer.signalEndOfStreamCalled).isTrue();
    assertThat(frameWriter.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queue_hardwareBufferFrameWithAdditionalMetadata_forwardsMetadata() throws Exception {
    FakeGlShaderProgram fakeCompositionEffectShaderProgram = new FakeGlShaderProgram();
    GlEffect fakeCompositionEffect = (context, useHdr) -> fakeCompositionEffectShaderProgram;

    ImmutableMap<String, Object> metadata =
        ImmutableMap.of(
            KEY_ITEM_EFFECTS,
            ImmutableList.of(fakeEffect),
            KEY_COMPOSITION_EFFECTS,
            ImmutableList.of(fakeCompositionEffect),
            KEY_COMPOSITOR_SETTINGS,
            VideoCompositorSettings.DEFAULT,
            KEY_COMPOSITION_SEQUENCE_INDEX,
            1);
    Frame frame = new FakeHardwareBufferFrame(metadata);

    List<Frame> completedFrames = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(1);

    try (DefaultGlFrameProcessor processor =
        createDefaultGlFrameProcessorFactory()
            .create(
                frameWriter,
                glExecutorService,
                new FrameProcessor.Listener() {
                  @Override
                  public void onWakeup() {}

                  @Override
                  public void onError(VideoFrameProcessingException exception) {
                    throw new AssertionError(exception);
                  }

                  @Override
                  public void onFrameProcessed(
                      Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {
                    completedFrames.add(frame);
                    latch.countDown();
                  }
                })) {
      assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame, /* acquireFence= */ null))))
          .isTrue();
      assertThat(latch.await(SYNC_TIMEOUT_MS, MILLISECONDS)).isTrue();
    }

    assertThat(completedFrames).hasSize(1);
    assertThat(completedFrames.get(0).getMetadata()).isEqualTo(metadata);
  }

  @Test
  public void queue_multipleInputFrames_processesAllFrames() throws Exception {
    Frame frame0 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, fakeEffect);
    Frame frame1 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 1, /* itemEffect= */ null);
    Frame frame2 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 2, /* itemEffect= */ null);

    List<Frame> completedFrames = new ArrayList<>();

    try (DefaultGlFrameProcessor processor =
        createDefaultGlFrameProcessorFactory()
            .create(
                frameWriter,
                testExecutor,
                new FrameProcessor.Listener() {
                  @Override
                  public void onWakeup() {}

                  @Override
                  public void onError(VideoFrameProcessingException exception) {
                    throw new AssertionError(exception);
                  }

                  @Override
                  public void onFrameProcessed(
                      Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {
                    completedFrames.add(frame);
                  }
                })) {

      assertThat(
              processor.queue(
                  ImmutableList.of(
                      new AsyncFrame(frame0, /* acquireFence= */ null),
                      new AsyncFrame(frame1, /* acquireFence= */ null),
                      new AsyncFrame(frame2, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();
      executeAllQueuedTasks(queuedFrameProcessedTasks);
      assertThat(completedFrames).containsExactly(frame0, frame1, frame2);
      assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(3);
      assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(1);
      assertThat(fakeFrameWriterGlTextureFrameConsumer.framesReceived).isEqualTo(1);
      assertThat(frameWriter.queuedFrames).isEqualTo(1);

      processor.signalEndOfStream();
      waitUntilGlThreadFinishes();
    }

    assertThat(fakeGlShaderProgram.signalEndOfCurrentInputStreamCalled).isTrue();
    assertThat(fakeFrameWriterGlTextureFrameConsumer.signalEndOfStreamCalled).isTrue();
    assertThat(frameWriter.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queue_multipleInputFramesWithNonMatchingIndices_processesAllFramesCorrectly()
      throws Exception {
    Frame frame0 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 10, fakeEffect);
    Frame frame1 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 5, /* itemEffect= */ null);
    Frame frame2 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 20, /* itemEffect= */ null);
    List<Frame> completedFrames = new ArrayList<>();
    try (DefaultGlFrameProcessor processor =
        createDefaultGlFrameProcessorFactory()
            .create(
                frameWriter,
                testExecutor,
                new FrameProcessor.Listener() {
                  @Override
                  public void onWakeup() {}

                  @Override
                  public void onError(VideoFrameProcessingException exception) {
                    throw new AssertionError(exception);
                  }

                  @Override
                  public void onFrameProcessed(
                      Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {
                    completedFrames.add(frame);
                  }
                })) {

      assertThat(
              processor.queue(
                  ImmutableList.of(
                      new AsyncFrame(frame0, /* acquireFence= */ null),
                      new AsyncFrame(frame1, /* acquireFence= */ null),
                      new AsyncFrame(frame2, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();
      executeAllQueuedTasks(queuedFrameProcessedTasks);
      assertThat(completedFrames).containsExactly(frame0, frame1, frame2);
      assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(3);
      // fakeGlShaderProgram is wired to frame 0.
      assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(1);
      assertThat(fakeFrameWriterGlTextureFrameConsumer.framesReceived).isEqualTo(1);
      assertThat(frameWriter.queuedFrames).isEqualTo(1);

      processor.signalEndOfStream();
      waitUntilGlThreadFinishes();
    }

    assertThat(fakeGlShaderProgram.signalEndOfCurrentInputStreamCalled).isTrue();
    assertThat(fakeFrameWriterGlTextureFrameConsumer.signalEndOfStreamCalled).isTrue();
    assertThat(frameWriter.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queue_notifiesCompletionListenerWhenFrameIsProcessed() throws Exception {
    Frame frame = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, fakeEffect);

    List<Frame> completedFrames = new ArrayList<>();

    try (DefaultGlFrameProcessor processor =
        createDefaultGlFrameProcessorFactory()
            .create(
                frameWriter,
                testExecutor,
                new FrameProcessor.Listener() {
                  @Override
                  public void onWakeup() {}

                  @Override
                  public void onError(VideoFrameProcessingException exception) {
                    throw new AssertionError(exception);
                  }

                  @Override
                  public void onFrameProcessed(
                      Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {
                    completedFrames.add(frame);
                  }
                })) {

      assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();
      executeAllQueuedTasks(queuedFrameProcessedTasks);
      assertThat(completedFrames).containsExactly(frame);
      assertThat(frameWriter.queuedFrames).isEqualTo(1);

      processor.signalEndOfStream();
      waitUntilGlThreadFinishes();
    }

    assertThat(fakeGlShaderProgram.signalEndOfCurrentInputStreamCalled).isTrue();
    assertThat(fakeFrameWriterGlTextureFrameConsumer.signalEndOfStreamCalled).isTrue();
    assertThat(frameWriter.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queue_whenDownstreamRejectsInput_notifiesWakeupListener() throws Exception {
    fakeGlShaderProgram.delayReadyToAcceptInputFrame = true;
    Frame frame1 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, fakeEffect);
    Frame frame2 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, fakeEffect);
    Frame frame3 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, fakeEffect);

    ArrayList<Boolean> wakeupNotified = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(1);

    try (DefaultGlFrameProcessor processor =
        createDefaultGlFrameProcessorFactory()
            .create(
                frameWriter,
                directExecutor(),
                new FrameProcessor.Listener() {
                  @Override
                  public void onWakeup() {
                    wakeupNotified.add(true);
                    latch.countDown();
                  }

                  @Override
                  public void onError(VideoFrameProcessingException exception) {
                    throw new AssertionError(exception);
                  }

                  @Override
                  public void onFrameProcessed(
                      Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {}
                })) {

      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame1, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();
      assertThat(frameWriter.queuedFrames).isEqualTo(1);

      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame2, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();
      assertThat(wakeupNotified).isEmpty();
      assertThat(frameWriter.queuedFrames).isEqualTo(1);

      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame3, /* acquireFence= */ null))))
          .isFalse();
      assertThat(wakeupNotified).isEmpty();

      glExecutorService.submit(() -> fakeGlShaderProgram.signalReadyToAcceptInputFrame()).get();

      assertThat(latch.await(SYNC_TIMEOUT_MS, MILLISECONDS)).isTrue();
      assertThat(wakeupNotified).containsExactly(true);
      assertThat(frameWriter.queuedFrames).isAtLeast(1);

      processor.signalEndOfStream();
      waitUntilGlThreadFinishes();
    }

    assertThat(fakeGlShaderProgram.signalEndOfCurrentInputStreamCalled).isTrue();
    assertThat(fakeFrameWriterGlTextureFrameConsumer.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queue_singleSequenceFailedToQueueConvertedGlTextureFrame_retainsFramesForRetry()
      throws Exception {
    // This is after compositing and post-processing chain.
    Frame frame1 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, /* itemEffect= */ null);
    Frame frame2 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, /* itemEffect= */ null);
    Frame frame3 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, /* itemEffect= */ null);

    fakeFrameWriterGlTextureFrameConsumer.shouldAcceptIncomingFrames = false;
    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame1, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame2, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame3, /* acquireFence= */ null))))
        .isFalse();
    waitUntilGlThreadFinishes();
    assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(2);
    assertThat(glTextureFramesReleased.get()).isEqualTo(0);
    assertThat(fakeFrameWriterGlTextureFrameConsumer.framesReceived).isEqualTo(0);

    processor.close();
    waitUntilGlThreadFinishes();
    assertThat(glTextureFramesReleased.get()).isEqualTo(2);
  }

  @Test
  public void queue_multiSequenceFailedToQueueConvertedGlTextureFrame_retainsFramesForRetry()
      throws Exception {
    Frame frame1 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, fakeEffect);
    Frame frame2 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, fakeEffect);

    fakeGlShaderProgram.delayReadyToAcceptInputFrame = true;
    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame1, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();

    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame2, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();

    assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(2);
    assertThat(glTextureFramesReleased.get()).isEqualTo(1);

    processor.close();
    waitUntilGlThreadFinishes();
    assertThat(glTextureFramesReleased.get()).isEqualTo(2);
  }

  @Test
  public void queue_singleSequencePostProcessorChainRejectsInput_retainsFramesForRetry()
      throws Exception {
    FakeGlShaderProgram fakeCompositionEffectShaderProgram = new FakeGlShaderProgram();
    fakeCompositionEffectShaderProgram.delayReadyToAcceptInputFrame = true;
    GlEffect fakeCompositionEffect = (context, useHdr) -> fakeCompositionEffectShaderProgram;

    ImmutableMap<String, Object> metadata =
        ImmutableMap.of(
            KEY_COMPOSITION_EFFECTS,
            ImmutableList.of(fakeCompositionEffect),
            KEY_ITEM_EFFECTS,
            ImmutableList.of(),
            KEY_COMPOSITION_SEQUENCE_INDEX,
            0);

    Frame frame1 = new FakeHardwareBufferFrame(metadata);
    Frame frame2 = new FakeHardwareBufferFrame(metadata);
    Frame frame3 = new FakeHardwareBufferFrame(metadata);
    Frame frame4 = new FakeHardwareBufferFrame(metadata);

    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame1, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame2, /* acquireFence= */ null))))
        .isTrue(); // Buffered in aggregator
    waitUntilGlThreadFinishes();
    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame3, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame4, /* acquireFence= */ null))))
        .isFalse(); // Rejected by frameAggregator
    waitUntilGlThreadFinishes();

    assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(3);
    assertThat(glTextureFramesReleased.get()).isEqualTo(1);

    processor.close();
    waitUntilGlThreadFinishes();
    assertThat(glTextureFramesReleased.get()).isEqualTo(3);
  }

  @Ignore // TODO: b/529360695 - Re-enable when no longer flaky.
  @Test
  public void queue_withFrameWriterRejectingInput_buffersAndRetriesCorrectly() throws Exception {
    // batch A
    Frame frame0A = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, fakeEffect);
    Frame frame1A = createFakeHardwareBufferFrame(/* sequenceIndex= */ 1, /* itemEffect= */ null);
    Frame frame2A = createFakeHardwareBufferFrame(/* sequenceIndex= */ 2, /* itemEffect= */ null);

    List<Frame> completedFrames = new ArrayList<>();
    CountDownLatch completedFramesLatch = new CountDownLatch(9);

    try (DefaultGlFrameProcessor processor =
        createDefaultGlFrameProcessorFactory()
            .create(
                frameWriter,
                directExecutor(),
                new FrameProcessor.Listener() {
                  @Override
                  public void onWakeup() {}

                  @Override
                  public void onError(VideoFrameProcessingException exception) {
                    throw new AssertionError(exception);
                  }

                  @Override
                  public void onFrameProcessed(
                      Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {
                    completedFrames.add(frame);
                    completedFramesLatch.countDown();
                  }
                })) {

      fakeFrameWriterGlTextureFrameConsumer.shouldAcceptIncomingFrames = false;
      // Queuing batch A, buffered in compositing processor as frameWriter not taking inputs.
      assertThat(
              processor.queue(
                  ImmutableList.of(
                      new AsyncFrame(frame0A, /* acquireFence= */ null),
                      new AsyncFrame(frame1A, /* acquireFence= */ null),
                      new AsyncFrame(frame2A, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();
      assertThat(fakeFrameWriterGlTextureFrameConsumer.framesReceived).isEqualTo(0);

      Frame frame0B = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, fakeEffect);
      Frame frame1B = createFakeHardwareBufferFrame(/* sequenceIndex= */ 1, /* itemEffect= */ null);
      Frame frame2B = createFakeHardwareBufferFrame(/* sequenceIndex= */ 2, /* itemEffect= */ null);

      // Queue batch B while compositing processor buffer is full (it holds batch A as frameWriter
      // doesn't take input). It ends up buffered in aggregator, as compositor is full.
      assertThat(
              processor.queue(
                  ImmutableList.of(
                      new AsyncFrame(frame0B, /* acquireFence= */ null),
                      new AsyncFrame(frame1B, /* acquireFence= */ null),
                      new AsyncFrame(frame2B, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();

      // Queue third batch C while aggregator buffer is full
      Frame frame0C = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, fakeEffect);
      Frame frame1C = createFakeHardwareBufferFrame(/* sequenceIndex= */ 1, /* itemEffect= */ null);
      Frame frame2C = createFakeHardwareBufferFrame(/* sequenceIndex= */ 2, /* itemEffect= */ null);

      // FrameProcessor accepts the batch. Internally frame0C is successfully queued to its
      // pre-processing chain, while frame1C and frame2C bypass pre-processing, are rejected by the
      // aggregator/compositor.
      assertThat(
              processor.queue(
                  ImmutableList.of(
                      new AsyncFrame(frame0C, /* acquireFence= */ null),
                      new AsyncFrame(frame1C, /* acquireFence= */ null),
                      new AsyncFrame(frame2C, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();

      // Now unblock downstream and trigger wakeup retry loop
      fakeFrameWriterGlTextureFrameConsumer.shouldAcceptIncomingFrames = true;
      fakeFrameWriterGlTextureFrameConsumer.triggerWakeup();
      waitUntilGlThreadFinishes();
      assertThat(completedFramesLatch.await(SYNC_TIMEOUT_MS, MILLISECONDS)).isTrue();

      assertThat(fakeFrameWriterGlTextureFrameConsumer.framesReceived).isEqualTo(3);
      // frame1C and frame2C bypass the preprocessor chain (no effects) and were initially rejected
      // by the aggregator because it was holding pending Batch B frames. Their GL resources are
      // preserved in convertedGlTextureFrames for retry. Now, after the wakeup retry, they are
      // successfully processed and onFrameProcessed invoked. Note onFrameProcessed is invoked by
      // HardwareBufferToGlTextureConverted.
      assertThat(completedFrames)
          .containsExactly(
              frame0A, frame1A, frame2A, frame0B, frame0C, frame1B, frame2B, frame1C, frame2C)
          .inOrder();
    }
  }

  @Test
  public void queue_changeSequenceConfigurationWhileFramePending_returnsFalse() throws Exception {
    Frame frame0A = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, /* itemEffect= */ null);
    Frame frame1A = createFakeHardwareBufferFrame(/* sequenceIndex= */ 1, fakeEffect);
    // This disallows the frames to be queued to the frame processor.
    fakeGlShaderProgram.delayUpdatingInputListenerReady = true;

    ArrayList<VideoFrameProcessingException> errors = new ArrayList<>();
    try (DefaultGlFrameProcessor processor =
        createDefaultGlFrameProcessorFactory()
            .create(
                frameWriter,
                directExecutor(),
                new FrameProcessor.Listener() {
                  @Override
                  public void onWakeup() {}

                  @Override
                  public void onError(VideoFrameProcessingException exception) {
                    errors.add(exception);
                  }

                  @Override
                  public void onFrameProcessed(
                      Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {}
                })) {

      // Queueing first batch succeeds, but the shader for sequence 1 is not yet ready.
      assertThat(
              processor.queue(
                  ImmutableList.of(
                      new AsyncFrame(frame0A, /* acquireFence= */ null),
                      new AsyncFrame(frame1A, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();

      Frame frame1B = createFakeHardwareBufferFrame(/* sequenceIndex= */ 1, fakeEffect);

      // Queueing second batch fails as frame1A is pending to be queued to the processing chains.
      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame1B, /* acquireFence= */ null))))
          .isFalse();
      assertThat(errors).isEmpty();
    }
  }

  @Test
  public void queue_dynamicSequenceCountChangesBetweenBatches_reconfiguresPipelineCorrectly()
      throws Exception {
    // 1. Queue Batch A (sequences: {0}).
    Frame frame0A =
        createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, /* itemEffect= */ fakeEffect);

    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame0A, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(1);

    // 2. Queue Batch B: Sequence increases (sequences: {0, 1}).
    Frame frame0B = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, /* itemEffect= */ null);
    Frame frame1B = createFakeHardwareBufferFrame(/* sequenceIndex= */ 1, fakeEffect);

    assertThat(
            processor.queue(
                ImmutableList.of(
                    new AsyncFrame(frame0B, /* acquireFence= */ null),
                    new AsyncFrame(frame1B, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(2); // Sequence 1 executes shader

    // 3. Queue Batch C: Sequence removed (sequences: {1}).
    Frame frame1C = createFakeHardwareBufferFrame(/* sequenceIndex= */ 1, /* itemEffect= */ null);

    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame1C, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    // Passthrough for sequence 1 as it's the only sequence.
    assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(2);
  }

  @Test
  public void queue_effectsChangeForSingleSequenceBetweenBatches_reconfiguresChainCorrectly()
      throws Exception {
    // 1. Queue Batch A: Sequence 0 has fakeEffect, Sequence 1 has no effect.
    Frame frame0A = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, fakeEffect);
    Frame frame1A = createFakeHardwareBufferFrame(/* sequenceIndex= */ 1, /* itemEffect= */ null);

    assertThat(
            processor.queue(
                ImmutableList.of(
                    new AsyncFrame(frame0A, /* acquireFence= */ null),
                    new AsyncFrame(frame1A, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(1); // frame0A

    // 2. Queue Batch B: Sequence 0 effects removed (no effect), Sequence 1 remains no effect.
    Frame frame0B = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, /* itemEffect= */ null);
    Frame frame1B = createFakeHardwareBufferFrame(/* sequenceIndex= */ 1, /* itemEffect= */ null);

    assertThat(
            processor.queue(
                ImmutableList.of(
                    new AsyncFrame(frame0B, /* acquireFence= */ null),
                    new AsyncFrame(frame1B, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    // No new shader frames because effect was removed
    assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(1);

    // 3. Queue Batch C: Sequence 0 remains no effect, Sequence 1 adds fakeEffect.
    Frame frame0C = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, /* itemEffect= */ null);
    Frame frame1C = createFakeHardwareBufferFrame(/* sequenceIndex= */ 1, fakeEffect);

    assertThat(
            processor.queue(
                ImmutableList.of(
                    new AsyncFrame(frame0C, /* acquireFence= */ null),
                    new AsyncFrame(frame1C, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    // Shader created and executed for sequence 1
    assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(2);
  }

  @Test
  public void queue_whenWakeupListenerThrowsRuntimeException_routesToErrorListener()
      throws Exception {
    fakeGlShaderProgram.delayReadyToAcceptInputFrame = true;
    Frame frame1 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, fakeEffect);
    Frame frame2 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, fakeEffect);
    Frame frame3 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, fakeEffect);

    try (DefaultGlFrameProcessor processor =
        createDefaultGlFrameProcessorFactory()
            .create(
                frameWriter,
                testExecutor,
                new FrameProcessor.Listener() {
                  @Override
                  public void onWakeup() {
                    throw new RuntimeException("test");
                  }

                  @Override
                  public void onError(VideoFrameProcessingException exception) {
                    throw new AssertionError(exception);
                  }

                  @Override
                  public void onFrameProcessed(
                      Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {}
                })) {

      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame1, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();
      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame2, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();
      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame3, /* acquireFence= */ null))))
          .isFalse();
      waitUntilGlThreadFinishes();

      glExecutorService.submit(() -> fakeGlShaderProgram.signalReadyToAcceptInputFrame()).get();
      waitUntilGlThreadFinishes();

      assertThrows(RuntimeException.class, () -> executeAllQueuedTasks(queuedFrameProcessedTasks));
    }
  }

  @Test
  public void queue_whenQueueFailsThenSucceedsOnFirstTryAsynchronously_notifiesWakeup()
      throws Exception {
    Frame frame0 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, /* itemEffect= */ null);
    Frame frame1 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, /* itemEffect= */ null);
    Frame frame2 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, /* itemEffect= */ null);

    ArrayList<Boolean> wakeupNotified = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(1);
    FrameProcessor.Listener listener =
        new FrameProcessor.Listener() {
          @Override
          public void onWakeup() {
            wakeupNotified.add(true);
            latch.countDown();
          }

          @Override
          public void onError(VideoFrameProcessingException exception) {
            throw new AssertionError(exception);
          }

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {}
        };

    try (DefaultGlFrameProcessor processor =
        createDefaultGlFrameProcessorFactory().create(frameWriter, directExecutor(), listener)) {

      fakeFrameWriterGlTextureFrameConsumer.shouldAcceptIncomingFrames = false;

      // Queueing succeeds, frame cached in the processor.
      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame0, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();

      // Queueing another frame succeeds on caller thread, fails asynchronously on GL thread.
      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame1, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();

      // Queueing a third frame fails synchronously: downstream is blocked so frame1 remains in
      // pendingFrames.
      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame2, /* acquireFence= */ null))))
          .isFalse();

      // Downstream is ready to accept frames now.
      fakeFrameWriterGlTextureFrameConsumer.shouldAcceptIncomingFrames = true;
      fakeFrameWriterGlTextureFrameConsumer.triggerWakeup();

      assertThat(latch.await(SYNC_TIMEOUT_MS, MILLISECONDS)).isTrue();
      assertThat(wakeupNotified).containsExactly(true);
    }
  }

  @Test
  public void
      queue_whenQueueFailsMultipleTimesThenSucceeds_notifiesWakeupAndAcceptsSubsequentQueue()
          throws Exception {
    Frame frame0 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, /* itemEffect= */ null);
    Frame frame1 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, /* itemEffect= */ null);
    Frame frame2 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, /* itemEffect= */ null);
    Frame frame3 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, /* itemEffect= */ null);

    ArrayList<Boolean> wakeupNotified = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(1);
    FrameProcessor.Listener listener =
        new FrameProcessor.Listener() {
          @Override
          public void onWakeup() {
            wakeupNotified.add(true);
            latch.countDown();
          }

          @Override
          public void onError(VideoFrameProcessingException exception) {
            throw new AssertionError(exception);
          }

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {}
        };

    try (DefaultGlFrameProcessor processor =
        createDefaultGlFrameProcessorFactory().create(frameWriter, directExecutor(), listener)) {

      fakeFrameWriterGlTextureFrameConsumer.shouldAcceptIncomingFrames = false;

      // Queueing succeeds, frame cached in the processor.
      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame0, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();

      // Queueing another frame succeeds on caller thread, fails asynchronously on GL thread.
      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame1, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();

      // Queueing a third frame fails synchronously.
      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame2, /* acquireFence= */ null))))
          .isFalse();

      // Queueing a fourth frame also fails.
      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame3, /* acquireFence= */ null))))
          .isFalse();

      // Downstream is ready to accept frames now.
      fakeFrameWriterGlTextureFrameConsumer.shouldAcceptIncomingFrames = true;
      fakeFrameWriterGlTextureFrameConsumer.triggerWakeup();

      // Verify wakeup is notified.
      assertThat(latch.await(SYNC_TIMEOUT_MS, MILLISECONDS)).isTrue();
      assertThat(wakeupNotified).containsExactly(true);

      // Verify that the first frame (frame0) and second frame (frame1) were accepted.
      assertThat(fakeFrameWriterGlTextureFrameConsumer.framesReceived).isEqualTo(2);
      assertThat(fakeFrameWriterGlTextureFrameConsumer.lastReceivedFrame).isNotNull();

      // Verify that subsequent queueing of frame3 now succeeds.
      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame3, /* acquireFence= */ null))))
          .isTrue();
    }
  }

  @Test
  public void queue_whenQueueingThrowsException_releasesGlResourcesAndClearsQueue()
      throws Exception {
    fakeFrameWriterGlTextureFrameConsumer.runtimeExceptionToThrowOnQueueing =
        new RuntimeException();
    Frame frame = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, /* itemEffect= */ null);

    if (processor != null) {
      processor.close();
    }
    ArrayList<VideoFrameProcessingException> errors = new ArrayList<>();
    processor =
        createDefaultGlFrameProcessorFactory()
            .create(
                frameWriter,
                directExecutor(),
                new FrameProcessor.Listener() {
                  @Override
                  public void onWakeup() {}

                  @Override
                  public void onError(VideoFrameProcessingException exception) {
                    errors.add(exception);
                  }

                  @Override
                  public void onFrameProcessed(
                      Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {}
                });

    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();

    assertThat(glTextureFramesReleased.get()).isEqualTo(1);
    assertThat(errors).hasSize(1);
    assertThat(errors.get(0)).hasCauseThat().isInstanceOf(RuntimeException.class);

    // Verify we can queue a new frame (meaning pendingFrames was cleared).
    fakeFrameWriterGlTextureFrameConsumer.runtimeExceptionToThrowOnQueueing = null;
    Frame frame2 = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, /* itemEffect= */ null);
    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame2, /* acquireFence= */ null))))
        .isTrue();
  }

  @Test
  public void queue_concurrentlyWithWakeup_neverMissesWakeupSignal() throws Exception {
    ConditionVariable wakeupCondition = new ConditionVariable();
    FrameProcessor.Listener listener =
        new FrameProcessor.Listener() {
          @Override
          public void onWakeup() {
            wakeupCondition.open();
          }

          @Override
          public void onError(VideoFrameProcessingException exception) {
            throw new AssertionError(exception);
          }

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {}
        };

    try (DefaultGlFrameProcessor processor =
        createDefaultGlFrameProcessorFactory().create(frameWriter, directExecutor(), listener)) {

      Random random = new Random(/* seed= */ 0);
      int frameCompleted = 0;
      GlTextureFrameConsumer finalFakeFrameWriterGlTextureFrameConsumer =
          fakeFrameWriterGlTextureFrameConsumer;

      // Try to queue 500 frames, with downstream rejects frames randomly
      while (frameCompleted < 500) {
        boolean queueSucceed =
            processor.queue(
                ImmutableList.of(
                    new AsyncFrame(
                        createFakeHardwareBufferFrame(
                            /* sequenceIndex= */ 0, /* itemEffect= */ null),
                        /* acquireFence= */ null)));
        if (queueSucceed) {
          frameCompleted++;
          // Randomly block the downstream to trigger contention.
          fakeFrameWriterGlTextureFrameConsumer.shouldAcceptIncomingFrames = random.nextBoolean();
        } else {
          if (wakeupCondition.isOpen()) {
            // If queueing failed, check if wakeup has already been signaled when reaching here.
            wakeupCondition.close();
          } else {
            // Open up for next queueing.
            synchronized (finalFakeFrameWriterGlTextureFrameConsumer) {
              fakeFrameWriterGlTextureFrameConsumer.shouldAcceptIncomingFrames = true;
              if (fakeFrameWriterGlTextureFrameConsumer.wakeupListener != null) {
                fakeFrameWriterGlTextureFrameConsumer.triggerWakeup();
              }
            }
            // Verify that wakeup will be notified eventually
            assertThat(wakeupCondition.block(SYNC_TIMEOUT_MS)).isTrue();
            wakeupCondition.close();
          }
        }
      }
      processor.signalEndOfStream();
      waitUntilGlThreadFinishes();
    }
  }

  @Test
  public void queue_whenSequenceDisappearsAndReappears_reusesPreProcessingChain() throws Exception {
    AtomicInteger shaderProgramCreationCount = new AtomicInteger();
    GlEffect trackingEffect =
        (context, useHdr) -> {
          shaderProgramCreationCount.incrementAndGet();
          return new FakeGlShaderProgram();
        };

    Frame frame0A = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, trackingEffect);
    Frame frame1A = createFakeHardwareBufferFrame(/* sequenceIndex= */ 1, /* itemEffect= */ null);

    FrameProcessor.Listener listener =
        new FrameProcessor.Listener() {
          @Override
          public void onWakeup() {}

          @Override
          public void onError(VideoFrameProcessingException exception) {
            throw new AssertionError("Unexpected frame processing error", exception);
          }

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {}
        };

    try (DefaultGlFrameProcessor processor =
        createDefaultGlFrameProcessorFactory().create(frameWriter, directExecutor(), listener)) {

      // Queue Batch A: Sequences {0, 1}.
      assertThat(
              processor.queue(
                  ImmutableList.of(
                      new AsyncFrame(frame0A, /* acquireFence= */ null),
                      new AsyncFrame(frame1A, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();
      assertThat(shaderProgramCreationCount.get()).isEqualTo(1);

      // Queue Batch B: Sequence 0 disappears (sequences: {1}).
      Frame frame1B = createFakeHardwareBufferFrame(/* sequenceIndex= */ 1, /* itemEffect= */ null);
      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame1B, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();
      assertThat(shaderProgramCreationCount.get()).isEqualTo(1);

      // Queue Batch C: Sequence 0 re-appears with the same Effect
      // This should reuse the inactive chain for sequence 0 and not re-create the shader program.
      Frame frame0C = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, trackingEffect);
      Frame frame1C = createFakeHardwareBufferFrame(/* sequenceIndex= */ 1, /* itemEffect= */ null);
      assertThat(
              processor.queue(
                  ImmutableList.of(
                      new AsyncFrame(frame0C, /* acquireFence= */ null),
                      new AsyncFrame(frame1C, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();

      // Verifies that the chain was reused and the shader program was NOT re-created!
      assertThat(shaderProgramCreationCount.get()).isEqualTo(1);
    }
  }

  private void waitUntilGlThreadFinishes()
      throws ExecutionException, InterruptedException, TimeoutException {
    // Note this doesn't guarantee all previously queued tasks are executed, if they submit tasks
    // themselves.
    glExecutorService.submit(() -> {}).get(SYNC_TIMEOUT_MS, MILLISECONDS);
  }

  private static void executeAllQueuedTasks(Queue<Runnable> queuedTasks) {
    while (!queuedTasks.isEmpty()) {
      queuedTasks.poll().run();
    }
  }

  @Test
  public void queue_whenEosSignaledWhileFramePending_defersEos() throws Exception {
    Frame frame = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, /* itemEffect= */ null);

    fakeFrameWriterGlTextureFrameConsumer.shouldAcceptIncomingFrames = false;
    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();

    // Signal EOS, should be deferred because frame is pending retry.
    processor.signalEndOfStream();
    waitUntilGlThreadFinishes();

    assertThat(fakeFrameWriterGlTextureFrameConsumer.signalEndOfStreamCalled).isFalse();
    assertThat(frameWriter.signalEndOfStreamCalled).isFalse();

    // Now unblock downstream and trigger wakeup retry loop.
    fakeFrameWriterGlTextureFrameConsumer.shouldAcceptIncomingFrames = true;
    fakeFrameWriterGlTextureFrameConsumer.triggerWakeup();
    waitUntilGlThreadFinishes();

    // Verify EOS is now propagated after successful queueing.
    assertThat(fakeFrameWriterGlTextureFrameConsumer.signalEndOfStreamCalled).isTrue();
    assertThat(frameWriter.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void close_withPendingFramesInShader_releasesFrames() throws Exception {
    fakeGlShaderProgram.delayProcessing = true;
    Frame frame = createFakeHardwareBufferFrame(/* sequenceIndex= */ 0, fakeEffect);

    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();

    // Frame is in shader, not released yet.
    assertThat(glTextureFramesReleased.get()).isEqualTo(0);

    processor.close();
    waitUntilGlThreadFinishes();

    // Frame should be released on close.
    assertThat(glTextureFramesReleased.get()).isEqualTo(1);
  }

  private DefaultGlFrameProcessor.Factory createDefaultGlFrameProcessorFactory() {
    return new DefaultGlFrameProcessor.Factory(
        context,
        new GlFrameProcessorTestUtil.FakeGlObjectsProvider(),
        glExecutorService,
        fakeHardwareBufferConverter,
        fakeFrameWriterGlTextureFrameConsumer,
        new FakeCompositorGlProgram(),
        new TexturePool(
            /* textureAllocator= */ (width, height, useHighPrecisionColorComponents) -> 100,
            /* useHighPrecisionColorComponents= */ false,
            /* capacity= */ COMPOSITOR_CAPACITY));
  }

  private static Frame createFakeHardwareBufferFrame(
      int sequenceIndex, @Nullable GlEffect itemEffect) {
    ImmutableMap.Builder<String, Object> metadataBuilder = new ImmutableMap.Builder<>();
    metadataBuilder
        .put(KEY_COMPOSITION_SEQUENCE_INDEX, sequenceIndex)
        .put(KEY_COMPOSITOR_SETTINGS, VideoCompositorSettings.DEFAULT)
        .put(DefaultGlFrameProcessor.KEY_COMPOSITION_EFFECTS, ImmutableList.of());
    if (itemEffect != null) {
      metadataBuilder.put(KEY_ITEM_EFFECTS, ImmutableList.of(itemEffect));
    } else {
      metadataBuilder.put(KEY_ITEM_EFFECTS, ImmutableList.of());
    }
    return new FakeHardwareBufferFrame(metadataBuilder.buildOrThrow());
  }
}
