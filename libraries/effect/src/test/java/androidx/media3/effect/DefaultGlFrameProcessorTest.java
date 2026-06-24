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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DefaultGlFrameProcessor} using pluggable non-OpenGL fakes. */
@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29)
public final class DefaultGlFrameProcessorTest {

  private static final long SYNC_TIMEOUT_MS = 1_000;

  private final Context context = getApplicationContext();
  private Queue<Runnable> queuedFrameProcessedTasks;
  private Executor testExecutor;

  private FakeHardwareBufferConverter fakeHardwareBufferConverter;
  private FakeGlShaderProgram fakeGlShaderProgram;
  private FakeGlTextureFrameConsumer fakeGlTextureFrameConsumer;
  private GlEffect fakeEffect;
  private DefaultGlFrameProcessor processor;
  private ListeningExecutorService glExecutorService;
  private NoOpFrameWriter frameWriter;

  @Before
  public void setUp() {
    frameWriter = new NoOpFrameWriter();
    fakeHardwareBufferConverter = new FakeHardwareBufferConverter();
    fakeGlShaderProgram = new FakeGlShaderProgram();
    fakeGlTextureFrameConsumer = new FakeGlTextureFrameConsumer(frameWriter);
    fakeEffect = (context, useHdr) -> fakeGlShaderProgram;
    glExecutorService = listeningDecorator(Util.newSingleThreadExecutor("Effect:GlThread"));
    queuedFrameProcessedTasks = new ConcurrentLinkedQueue<>();
    testExecutor = queuedFrameProcessedTasks::add;

    processor =
        new DefaultGlFrameProcessor.Factory(
                context, glExecutorService, fakeHardwareBufferConverter, fakeGlTextureFrameConsumer)
            .create(
                frameWriter,
                testExecutor,
                new FrameProcessor.Listener() {
                  @Override
                  public void onWakeup() {}

                  @Override
                  public void onError(VideoFrameProcessingException exception) {}

                  @Override
                  public void onFrameProcessed(
                      Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {}
                });
    assertThat(processor).isNotNull();
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
  public void queue_hardwareBufferFrame_propagatesToAllPipelineComponents() throws Exception {
    Frame frame =
        new FakeHardwareBufferFrame(
            ImmutableMap.of(KEY_ITEM_EFFECTS, ImmutableList.of(fakeEffect)));

    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(1);
    assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(1);
    assertThat(fakeGlTextureFrameConsumer.framesReceived).isEqualTo(1);
    assertThat(frameWriter.queuedFrames).isEqualTo(1);

    processor.signalEndOfStream();
    waitUntilGlThreadFinishes();

    assertThat(fakeGlShaderProgram.signalEndOfCurrentInputStreamCalled).isTrue();
    assertThat(fakeGlTextureFrameConsumer.signalEndOfStreamCalled).isTrue();
    assertThat(frameWriter.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queue_hardwareBufferFramesWithAndWithoutEffects_propagatesToAllPipelineComponents()
      throws Exception {
    // 1. First sends a frame with the FakeEffect, verify the frame reaches the end.
    Frame frameWithEffect1 =
        new FakeHardwareBufferFrame(
            ImmutableMap.of(KEY_ITEM_EFFECTS, ImmutableList.of(fakeEffect)));

    assertThat(
            processor.queue(
                ImmutableList.of(new AsyncFrame(frameWithEffect1, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(1);
    assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(1);
    assertThat(fakeGlTextureFrameConsumer.framesReceived).isEqualTo(1);
    assertThat(frameWriter.queuedFrames).isEqualTo(1);

    // 2. Send another frame that has no effect, verify the frame reaches the end.
    Frame frameWithoutEffect = new FakeHardwareBufferFrame(ImmutableMap.of());

    assertThat(
            processor.queue(
                ImmutableList.of(new AsyncFrame(frameWithoutEffect, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(2);
    assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(1);
    assertThat(fakeGlTextureFrameConsumer.framesReceived).isEqualTo(2);
    assertThat(frameWriter.queuedFrames).isEqualTo(2);

    // 3. Send another frame with the fakeEffect, verify the frame reaches the end.
    Frame frameWithEffect2 =
        new FakeHardwareBufferFrame(
            ImmutableMap.of(KEY_ITEM_EFFECTS, ImmutableList.of(fakeEffect)));

    assertThat(
            processor.queue(
                ImmutableList.of(new AsyncFrame(frameWithEffect2, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(3);
    assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(2);
    assertThat(fakeGlTextureFrameConsumer.framesReceived).isEqualTo(3);
    assertThat(frameWriter.queuedFrames).isEqualTo(3);

    processor.signalEndOfStream();
    waitUntilGlThreadFinishes();

    assertThat(fakeGlShaderProgram.signalEndOfCurrentInputStreamCalled).isTrue();
    assertThat(fakeGlTextureFrameConsumer.signalEndOfStreamCalled).isTrue();
    assertThat(frameWriter.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queue_hardwareBufferFrameWithItemAndCompositionEffects_configuresAllEffectsChain()
      throws Exception {
    FakeGlShaderProgram fakeCompositionEffectShaderProgram = new FakeGlShaderProgram();
    GlEffect fakeCompositionEffect = (context, useHdr) -> fakeCompositionEffectShaderProgram;

    Frame frame =
        new FakeHardwareBufferFrame(
            ImmutableMap.of(
                KEY_ITEM_EFFECTS,
                ImmutableList.of(fakeEffect),
                KEY_COMPOSITION_EFFECTS,
                ImmutableList.of(fakeCompositionEffect)));

    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(1);
    assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(1); // Item effect executed
    assertThat(fakeCompositionEffectShaderProgram.framesReceived)
        .isEqualTo(1); // Composition effect executed
    assertThat(fakeGlTextureFrameConsumer.framesReceived).isEqualTo(1);
    assertThat(frameWriter.queuedFrames).isEqualTo(1);
  }

  @Test
  public void queue_hardwareBufferFrameWithAllAdditionalMetadata_forwardsMetadata()
      throws Exception {
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
    FrameProcessor.Listener listener =
        new FrameProcessor.Listener() {
          @Override
          public void onWakeup() {}

          @Override
          public void onError(VideoFrameProcessingException exception) {}

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {
            completedFrames.add(frame);
          }
        };

    boolean frameQueued;
    try (DefaultGlFrameProcessor processor =
        new DefaultGlFrameProcessor.Factory(
                context, glExecutorService, fakeHardwareBufferConverter, fakeGlTextureFrameConsumer)
            .create(frameWriter, testExecutor, listener)) {
      frameQueued =
          processor.queue(ImmutableList.of(new AsyncFrame(frame, /* acquireFence= */ null)));
      waitUntilGlThreadFinishes();
      executeQueuedTasks(queuedFrameProcessedTasks, 1);
    }

    assertThat(frameQueued).isTrue();
    assertThat(completedFrames).hasSize(1);
    assertThat(completedFrames.get(0).getMetadata()).isEqualTo(metadata);
  }

  @Test
  public void queue_multipleInputFrames_processesFirstFrameAndInstantlyCompletesRemainingFrames()
      throws Exception {
    // TODO: b/505721737 - Remove when multi-sequence is supported.
    Frame frame0 =
        new FakeHardwareBufferFrame(
            ImmutableMap.of(KEY_ITEM_EFFECTS, ImmutableList.of(fakeEffect)));
    Frame frame1 = new FakeHardwareBufferFrame(ImmutableMap.of());
    Frame frame2 = new FakeHardwareBufferFrame(ImmutableMap.of());

    List<Frame> completedFrames = new ArrayList<>();
    FrameProcessor.Listener listener =
        new FrameProcessor.Listener() {
          @Override
          public void onWakeup() {}

          @Override
          public void onError(VideoFrameProcessingException exception) {}

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {
            completedFrames.add(frame);
          }
        };
    if (processor != null) {
      processor.close();
    }
    processor =
        new DefaultGlFrameProcessor.Factory(
                context, glExecutorService, fakeHardwareBufferConverter, fakeGlTextureFrameConsumer)
            .create(frameWriter, testExecutor, listener);

    assertThat(
            processor.queue(
                ImmutableList.of(
                    new AsyncFrame(frame0, /* acquireFence= */ null),
                    new AsyncFrame(frame1, /* acquireFence= */ null),
                    new AsyncFrame(frame2, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    // Three tasks are expected: 2 for frames 1 and 2 (instantly completed because multi-frame is
    // not supported yet) and 1 for frame 0 (completed on release by FakeGlTextureFrameConsumer).
    executeQueuedTasks(queuedFrameProcessedTasks, 3);
    // frame0 comes last because frames 1 and 2 are released before processing frame0
    assertThat(completedFrames).containsExactly(frame1, frame2, frame0).inOrder();
    assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(1);
    assertThat(fakeGlShaderProgram.framesReceived).isEqualTo(1);
    assertThat(fakeGlTextureFrameConsumer.framesReceived).isEqualTo(1);
    assertThat(frameWriter.queuedFrames).isEqualTo(1);

    processor.signalEndOfStream();
    waitUntilGlThreadFinishes();

    assertThat(fakeGlShaderProgram.signalEndOfCurrentInputStreamCalled).isTrue();
    assertThat(fakeGlTextureFrameConsumer.signalEndOfStreamCalled).isTrue();
    assertThat(frameWriter.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queue_hardwareBufferFrame_notifiesCompletionListenerWhenFrameIsProcessed()
      throws Exception {
    Frame frame =
        new FakeHardwareBufferFrame(
            ImmutableMap.of(KEY_ITEM_EFFECTS, ImmutableList.of(fakeEffect)));

    List<Frame> completedFrames = new ArrayList<>();
    FrameProcessor.Listener listener =
        new FrameProcessor.Listener() {
          @Override
          public void onWakeup() {}

          @Override
          public void onError(VideoFrameProcessingException exception) {}

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {
            completedFrames.add(frame);
          }
        };
    if (processor != null) {
      processor.close();
    }
    processor =
        new DefaultGlFrameProcessor.Factory(
                context, glExecutorService, fakeHardwareBufferConverter, fakeGlTextureFrameConsumer)
            .create(frameWriter, testExecutor, listener);

    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    executeQueuedTasks(queuedFrameProcessedTasks, 1);
    assertThat(completedFrames).containsExactly(frame).inOrder();
    assertThat(frameWriter.queuedFrames).isEqualTo(1);

    processor.signalEndOfStream();
    waitUntilGlThreadFinishes();

    assertThat(fakeGlShaderProgram.signalEndOfCurrentInputStreamCalled).isTrue();
    assertThat(fakeGlTextureFrameConsumer.signalEndOfStreamCalled).isTrue();
    assertThat(frameWriter.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queue_hardwareBufferFrame_notifiesWakeupListener() throws Exception {
    fakeGlShaderProgram.delayReadyToAcceptInputFrame = true;
    Frame frame1 =
        new FakeHardwareBufferFrame(
            ImmutableMap.of(KEY_ITEM_EFFECTS, ImmutableList.of(fakeEffect)));
    Frame frame2 =
        new FakeHardwareBufferFrame(
            ImmutableMap.of(KEY_ITEM_EFFECTS, ImmutableList.of(fakeEffect)));
    Frame frame3 =
        new FakeHardwareBufferFrame(
            ImmutableMap.of(KEY_ITEM_EFFECTS, ImmutableList.of(fakeEffect)));

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
          public void onError(VideoFrameProcessingException exception) {}

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {}
        };
    if (processor != null) {
      processor.close();
    }
    processor =
        new DefaultGlFrameProcessor.Factory(
                context, glExecutorService, fakeHardwareBufferConverter, fakeGlTextureFrameConsumer)
            .create(frameWriter, directExecutor(), listener);

    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame1, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    assertThat(frameWriter.queuedFrames).isEqualTo(1);

    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame2, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();

    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame3, /* acquireFence= */ null))))
        .isFalse();
    assertThat(wakeupNotified).isEmpty();
    assertThat(frameWriter.queuedFrames).isEqualTo(1);

    glExecutorService.submit(() -> fakeGlShaderProgram.signalReadyToAcceptInputFrame()).get();

    assertThat(latch.await(SYNC_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(wakeupNotified).containsExactly(true);
    assertThat(frameWriter.queuedFrames).isEqualTo(2);

    processor.signalEndOfStream();
    waitUntilGlThreadFinishes();

    assertThat(fakeGlShaderProgram.signalEndOfCurrentInputStreamCalled).isTrue();
    assertThat(fakeGlTextureFrameConsumer.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queue_whenDownstreamConsumerReturnsFalse_doesNotReleaseGlResources()
      throws Exception {
    fakeGlTextureFrameConsumer.shouldAcceptIncomingFrames = false;
    Frame frame = new FakeHardwareBufferFrame(ImmutableMap.of());

    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();
    assertThat(fakeHardwareBufferConverter.framesReceived).isEqualTo(1);
    assertThat(fakeHardwareBufferConverter.framesWithGlResourceReleased).isEqualTo(0);
    assertThat(fakeGlTextureFrameConsumer.framesReceived).isEqualTo(0);
  }

  @Test
  public void queue_cachesFrameAndRetriesOnWakeup_queuesCachedFrame() throws Exception {
    Frame frame0 = new FakeHardwareBufferFrame(ImmutableMap.of());
    Frame frame1 = new FakeHardwareBufferFrame(ImmutableMap.of());

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
            throw new AssertionError("Unexpected frame processing error", exception);
          }

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {}
        };

    try (DefaultGlFrameProcessor processor =
        new DefaultGlFrameProcessor.Factory(
                context, glExecutorService, fakeHardwareBufferConverter, fakeGlTextureFrameConsumer)
            .create(frameWriter, directExecutor(), listener)) {

      fakeGlTextureFrameConsumer.shouldAcceptIncomingFrames = false;

      // Queueing succeeds, frame cached in the processor.
      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame0, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();

      // Queueing another frame fails: downstream is blocked so frame0 remains in cache.
      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame1, /* acquireFence= */ null))))
          .isFalse();

      // Downstream is ready to accept frames now.
      fakeGlTextureFrameConsumer.shouldAcceptIncomingFrames = true;
      fakeGlTextureFrameConsumer.triggerWakeup();

      // Verify wakeup is notified so the caller knows they can try frame1 again.
      assertThat(latch.await(SYNC_TIMEOUT_MS, MILLISECONDS)).isTrue();
      assertThat(wakeupNotified).containsExactly(true);
    }
  }

  @Test
  public void queue_whenDownstreamRejectsMultipleTimes_notifiesWakeupAndAcceptsSubsequentQueue()
      throws Exception {
    Frame frame0 = new FakeHardwareBufferFrame(ImmutableMap.of());
    Frame frame1 = new FakeHardwareBufferFrame(ImmutableMap.of());
    Frame frame2 = new FakeHardwareBufferFrame(ImmutableMap.of());

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
            throw new AssertionError("Unexpected frame processing error", exception);
          }

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {}
        };

    try (DefaultGlFrameProcessor processor =
        new DefaultGlFrameProcessor.Factory(
                context, glExecutorService, fakeHardwareBufferConverter, fakeGlTextureFrameConsumer)
            .create(frameWriter, directExecutor(), listener)) {

      fakeGlTextureFrameConsumer.shouldAcceptIncomingFrames = false;

      // Queueing succeeds, frame cached in the processor.
      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame0, /* acquireFence= */ null))))
          .isTrue();
      waitUntilGlThreadFinishes();

      // Queueing another frame fails: downstream is blocked so frame0 remains in cache.
      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame1, /* acquireFence= */ null))))
          .isFalse();
      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame2, /* acquireFence= */ null))))
          .isFalse();

      fakeGlTextureFrameConsumer.shouldAcceptIncomingFrames = true;
      fakeGlTextureFrameConsumer.triggerWakeup();

      assertThat(latch.await(SYNC_TIMEOUT_MS, MILLISECONDS)).isTrue();
      assertThat(wakeupNotified).containsExactly(true);

      assertThat(fakeGlTextureFrameConsumer.framesReceived).isEqualTo(1);
      assertThat(fakeGlTextureFrameConsumer.lastReceivedFrame).isNotNull();

      // Verify that subsequent queueing of frame2 now succeeds.
      assertThat(
              processor.queue(ImmutableList.of(new AsyncFrame(frame2, /* acquireFence= */ null))))
          .isTrue();
    }
  }

  @Test
  public void queue_whenQueueingThrowsException_releasesGlResourcesAndClearsQueue()
      throws Exception {
    fakeGlTextureFrameConsumer.runtimeExceptionToThrowOnQueueing = new RuntimeException();
    Frame frame = new FakeHardwareBufferFrame(ImmutableMap.of());

    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();

    assertThat(fakeHardwareBufferConverter.framesWithGlResourceReleased).isEqualTo(1);

    // Verify we can queue a new frame (meaning pendingFrames was cleared).
    fakeGlTextureFrameConsumer.runtimeExceptionToThrowOnQueueing = null;
    Frame frame2 = new FakeHardwareBufferFrame(ImmutableMap.of());
    assertThat(processor.queue(ImmutableList.of(new AsyncFrame(frame2, /* acquireFence= */ null))))
        .isTrue();
  }

  @Test
  public void queue_multipleInputFramesOnRetry_doesNotNotifyFrameProcessedMultipleTimes()
      throws Exception {
    fakeGlTextureFrameConsumer.shouldAcceptIncomingFrames = false;
    Frame frame0 = new FakeHardwareBufferFrame(ImmutableMap.of());
    Frame frame1 = new FakeHardwareBufferFrame(ImmutableMap.of());

    List<Frame> completedFrames = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(2);
    FrameProcessor.Listener listener =
        new FrameProcessor.Listener() {
          @Override
          public void onWakeup() {}

          @Override
          public void onError(VideoFrameProcessingException exception) {}

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {
            completedFrames.add(frame);
            latch.countDown();
          }
        };
    if (processor != null) {
      processor.close();
    }
    processor =
        new DefaultGlFrameProcessor.Factory(
                context, glExecutorService, fakeHardwareBufferConverter, fakeGlTextureFrameConsumer)
            .create(frameWriter, directExecutor(), listener);

    assertThat(
            processor.queue(
                ImmutableList.of(
                    new AsyncFrame(frame0, /* acquireFence= */ null),
                    new AsyncFrame(frame1, /* acquireFence= */ null))))
        .isTrue();
    waitUntilGlThreadFinishes();

    // Frame1 is immediately released synchronously (it doesn't support multi-sequence yet)
    assertThat(completedFrames).containsExactly(frame1);

    fakeGlTextureFrameConsumer.shouldAcceptIncomingFrames = true;
    fakeGlTextureFrameConsumer.triggerWakeup();

    // Wait for both frames (frame1 released synchronously, frame0 after retry) to complete.
    assertThat(latch.await(SYNC_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(completedFrames).containsExactly(frame1, frame0).inOrder();
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
          public void onError(VideoFrameProcessingException exception) {}

          @Override
          public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {}
        };

    try (DefaultGlFrameProcessor processor =
        new DefaultGlFrameProcessor.Factory(
                context, glExecutorService, fakeHardwareBufferConverter, fakeGlTextureFrameConsumer)
            .create(frameWriter, directExecutor(), listener)) {

      Random random = new Random(/* seed= */ 0);
      int frameCompleted = 0;
      GlTextureFrameConsumer finalFakeGlTextureFrameConsumer = fakeGlTextureFrameConsumer;

      // Try to queue 500 frames, with downstream rejects frames randomly
      while (frameCompleted < 500) {
        Frame frame = new FakeHardwareBufferFrame(ImmutableMap.of());
        AsyncFrame asyncFrame = new AsyncFrame(frame, /* acquireFence= */ null);
        if (processor.queue(ImmutableList.of(asyncFrame))) {
          frameCompleted++;
          // Randomly block the downstream to trigger contention.
          fakeGlTextureFrameConsumer.shouldAcceptIncomingFrames = random.nextBoolean();
        } else {
          if (wakeupCondition.isOpen()) {
            // If queueing failed, check if wakeup has already been signaled when reaching here.
            wakeupCondition.close();
          } else {
            // Open up for next queueing.
            fakeGlTextureFrameConsumer.shouldAcceptIncomingFrames = true;
            synchronized (finalFakeGlTextureFrameConsumer) {
              if (fakeGlTextureFrameConsumer.wakeupListener != null) {
                fakeGlTextureFrameConsumer.triggerWakeup();
              }
            }
            // Wait for the wakeup notification. If it's on invoked above, the processor invokes it
            // after successfully queueing it to fakeGlTextureFrameConsumer.
            assertThat(wakeupCondition.block(SYNC_TIMEOUT_MS)).isTrue();
            // Reset for next iteration
            wakeupCondition.close();
          }
        }
      }

      processor.signalEndOfStream();
      waitUntilGlThreadFinishes();
    }
  }

  private void waitUntilGlThreadFinishes()
      throws ExecutionException, InterruptedException, TimeoutException {
    // Note this doesn't guarantee all previously queued tasks are executed, if they submit tasks
    // themselves.
    glExecutorService.submit(() -> {}).get(SYNC_TIMEOUT_MS, MILLISECONDS);
  }

  private static void executeQueuedTasks(Queue<Runnable> queuedTasks, int expectedSize) {
    assertThat(queuedTasks).hasSize(expectedSize);
    for (int i = 0; i < expectedSize; i++) {
      queuedTasks.poll().run();
    }
  }
}
