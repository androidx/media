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

import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_COMPOSITION_SEQUENCE_INDEX;
import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_COMPOSITOR_SETTINGS;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.effect.GlFrameProcessorTestUtil.FakeCompositorGlProgram;
import androidx.media3.effect.GlFrameProcessorTestUtil.FakeGlObjectsProvider;
import androidx.media3.effect.GlFrameProcessorTestUtil.FakeGlTextureFrameConsumer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link GlTextureFrameAggregator}. */
@RunWith(AndroidJUnit4.class)
public final class GlTextureFrameAggregatorTest {

  private static final int FAKE_GL_TEXTURE_ID = 999;

  private DefaultGlTextureFrameCompositingProcessor compositingProcessor;
  private FakeGlTextureFrameConsumer downstreamConsumer;
  private FakeCompositorGlProgram compositorGlProgram;
  private GlTextureFrameAggregator frameAggregator;
  private AtomicReference<VideoFrameProcessingException> errorReference;

  @Before
  public void setUp() {
    errorReference = new AtomicReference<>();
    downstreamConsumer = new FakeGlTextureFrameConsumer(/* frameWriter= */ null);
    compositorGlProgram = new FakeCompositorGlProgram();
    ListeningExecutorService glExecutorService = newDirectExecutorService();
    compositingProcessor =
        new DefaultGlTextureFrameCompositingProcessor(
            new FakeGlObjectsProvider(),
            new TexturePool(
                /* textureAllocator= */ (width, height, useHighPrecisionColorComponents) ->
                    FAKE_GL_TEXTURE_ID,
                /* useHighPrecisionColorComponents= */ false,
                /* capacity= */ 1),
            errorReference::set,
            compositorGlProgram,
            glExecutorService,
            downstreamConsumer);
    frameAggregator =
        new GlTextureFrameAggregator(compositingProcessor, glExecutorService, errorReference::set);
  }

  @After
  public void tearDown() throws Exception {
    if (frameAggregator != null) {
      frameAggregator.close();
    }
    if (compositingProcessor != null) {
      compositingProcessor.close();
    }
  }

  @Test
  public void queue_singleInput_queuesToCompositingProcessor() throws Exception {
    frameAggregator.configureSequenceIndices(ImmutableSet.of(0));
    GlTextureFrame frame = createGlTextureFrame();

    assertThat(frameAggregator.getInputConsumer(0).queue(frame, directExecutor(), () -> {}))
        .isTrue();
    assertThat(downstreamConsumer.framesReceived).isEqualTo(1);
  }

  @Test
  public void queue_multipleInputs_buffersUntilAllFramesReceived() throws Exception {
    frameAggregator.configureSequenceIndices(/* requestedSequenceIndices= */ ImmutableSet.of(0, 1));
    GlTextureFrame frame1 = createGlTextureFrame(/* sequenceIndex= */ 0);
    GlTextureFrame frame2 = createGlTextureFrame(/* sequenceIndex= */ 1);

    assertThat(frameAggregator.getInputConsumer(0).queue(frame1, directExecutor(), () -> {}))
        .isTrue();
    assertThat(downstreamConsumer.framesReceived).isEqualTo(0); // Waiting for frame2

    assertThat(frameAggregator.getInputConsumer(1).queue(frame2, directExecutor(), () -> {}))
        .isTrue();
    assertThat(downstreamConsumer.framesReceived).isEqualTo(1); // Both frames composited
  }

  @Test
  public void queue_whenCompositorRejectsInput_buffersFramesAndRetriesOnWakeup() throws Exception {
    frameAggregator.configureSequenceIndices(/* requestedSequenceIndices= */ ImmutableSet.of(0, 1));
    downstreamConsumer.shouldAcceptIncomingFrames = false;
    queueOnePacketToBlockCompositor(/* numberOfSequences= */ 2);

    // Should still able to queue, these frames will be composited once the downstream accepts
    // input.
    GlTextureFrame frame1 = createGlTextureFrame(/* sequenceIndex= */ 0);
    GlTextureFrame frame2 = createGlTextureFrame(/* sequenceIndex= */ 1);
    assertThat(frameAggregator.getInputConsumer(0).queue(frame1, directExecutor(), () -> {}))
        .isTrue();
    assertThat(frameAggregator.getInputConsumer(1).queue(frame2, directExecutor(), () -> {}))
        .isTrue();
    // Unblock downstream and trigger wakeup listener
    downstreamConsumer.shouldAcceptIncomingFrames = true;
    downstreamConsumer.triggerWakeup();
    assertThat(downstreamConsumer.framesReceived).isEqualTo(2);

    downstreamConsumer.shouldAcceptIncomingFrames = false;
    GlTextureFrame frame3 = createGlTextureFrame(/* sequenceIndex= */ 0);
    assertThat(frameAggregator.getInputConsumer(0).queue(frame3, directExecutor(), () -> {}))
        .isTrue();

    downstreamConsumer.shouldAcceptIncomingFrames = true;
    downstreamConsumer.triggerWakeup();
    // Only queued for sequence 0 in frame3, still waiting for sequence 1.
    assertThat(downstreamConsumer.framesReceived).isEqualTo(2);
  }

  @Test
  public void queue_whenCompositorRejectsInputDuringSequenceTransitions_retriesCorrectly()
      throws Exception {
    // 1. Start with only one sequence, composites input immediately.
    frameAggregator.configureSequenceIndices(/* requestedSequenceIndices= */ ImmutableSet.of(0));
    downstreamConsumer.shouldAcceptIncomingFrames = false;

    GlTextureFrame frame1 = createGlTextureFrame(/* sequenceIndex= */ 0);
    // Buffers frame1 in the aggregator.
    assertThat(frameAggregator.getInputConsumer(0).queue(frame1, directExecutor(), () -> {}))
        .isTrue();
    // compositingProcessor rejects input
    assertThat(downstreamConsumer.framesReceived).isEqualTo(0);

    downstreamConsumer.shouldAcceptIncomingFrames = true;
    downstreamConsumer.triggerWakeup();
    assertThat(downstreamConsumer.framesReceived).isEqualTo(1);

    // 2. Transition to multiple sequences {0, 1}. Queue frame2 and frame3 while downstream rejects.
    frameAggregator.configureSequenceIndices(/* requestedSequenceIndices= */ ImmutableSet.of(0, 1));
    downstreamConsumer.shouldAcceptIncomingFrames = false;

    GlTextureFrame frame2 = createGlTextureFrame(/* sequenceIndex= */ 0);
    GlTextureFrame frame3 = createGlTextureFrame(/* sequenceIndex= */ 1);
    // Buffers frames in the aggregator.
    assertThat(frameAggregator.getInputConsumer(0).queue(frame2, directExecutor(), () -> {}))
        .isTrue();
    assertThat(frameAggregator.getInputConsumer(1).queue(frame3, directExecutor(), () -> {}))
        .isTrue();
    // compositingProcessor rejects input, so the compositor only received frame1.
    assertThat(downstreamConsumer.framesReceived).isEqualTo(1);

    downstreamConsumer.shouldAcceptIncomingFrames = true;
    downstreamConsumer.triggerWakeup();
    assertThat(downstreamConsumer.framesReceived).isEqualTo(2);

    // 3. Transition back to single sequence {0}. Queue frame4 while downstream rejects.
    frameAggregator.configureSequenceIndices(ImmutableSet.of(0));
    downstreamConsumer.shouldAcceptIncomingFrames = false;

    GlTextureFrame frame4 = createGlTextureFrame(/* sequenceIndex= */ 0);
    // Buffers frames in the aggregator.
    assertThat(frameAggregator.getInputConsumer(0).queue(frame4, directExecutor(), () -> {}))
        .isTrue(); // Successfully buffered in frameAggregator
    assertThat(downstreamConsumer.framesReceived)
        .isEqualTo(2); // Attempted but rejected (still 2 from step 2)

    downstreamConsumer.shouldAcceptIncomingFrames = true;
    downstreamConsumer.triggerWakeup();
    assertThat(downstreamConsumer.framesReceived).isEqualTo(3); // Successfully retried
  }

  @Test
  public void signalEndOfStream_whenCompositorRejectsInput_defersEosUntilWakeup() throws Exception {
    frameAggregator.configureSequenceIndices(/* requestedSequenceIndices= */ ImmutableSet.of(0, 1));
    downstreamConsumer.shouldAcceptIncomingFrames = false;

    GlTextureFrame frame1 = createGlTextureFrame(/* sequenceIndex= */ 0);
    GlTextureFrame frame2 = createGlTextureFrame(/* sequenceIndex= */ 1);

    assertThat(frameAggregator.getInputConsumer(0).queue(frame1, directExecutor(), () -> {}))
        .isTrue();
    assertThat(frameAggregator.getInputConsumer(1).queue(frame2, directExecutor(), () -> {}))
        .isTrue();

    frameAggregator.getInputConsumer(0).signalEndOfStream();
    frameAggregator.getInputConsumer(1).signalEndOfStream();

    assertThat(downstreamConsumer.signalEndOfStreamCalled).isFalse();

    downstreamConsumer.shouldAcceptIncomingFrames = true;
    downstreamConsumer.triggerWakeup();

    assertThat(downstreamConsumer.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void queue_whenDownstreamThrowsVideoFrameProcessingException_routesToErrorConsumer()
      throws Exception {
    frameAggregator.configureSequenceIndices(ImmutableSet.of(0));
    VideoFrameProcessingException exception = new VideoFrameProcessingException("test");
    downstreamConsumer.exceptionToThrowOnQueueing = exception;
    GlTextureFrame frame = createGlTextureFrame();

    assertThat(frameAggregator.getInputConsumer(0).queue(frame, directExecutor(), () -> {}))
        .isTrue();

    assertThat(errorReference.get()).isEqualTo(exception);
  }

  @Test
  public void queue_whenDownstreamThrowsRuntimeExceptionAndWakeup_routesToErrorConsumer()
      throws Exception {
    frameAggregator.configureSequenceIndices(/* requestedSequenceIndices= */ ImmutableSet.of(0, 1));
    downstreamConsumer.shouldAcceptIncomingFrames = false;
    queueOnePacketToBlockCompositor(/* numberOfSequences= */ 2);
    RuntimeException exception = new RuntimeException("test");

    GlTextureFrame frame1 = createGlTextureFrame(/* sequenceIndex= */ 0);
    GlTextureFrame frame2 = createGlTextureFrame(/* sequenceIndex= */ 1);
    assertThat(frameAggregator.getInputConsumer(0).queue(frame1, directExecutor(), () -> {}))
        .isTrue();
    assertThat(frameAggregator.getInputConsumer(1).queue(frame2, directExecutor(), () -> {}))
        .isTrue();

    // Queueing again while buffer is full returns false
    GlTextureFrame frame3 = createGlTextureFrame(/* sequenceIndex= */ 0);
    assertThat(frameAggregator.getInputConsumer(0).queue(frame3, directExecutor(), () -> {}))
        .isFalse();

    downstreamConsumer.runtimeExceptionToThrowOnQueueing = exception;
    downstreamConsumer.shouldAcceptIncomingFrames = true;
    downstreamConsumer.triggerWakeup();

    assertThat(errorReference.get()).hasCauseThat().isEqualTo(exception);
  }

  @Test
  public void queue_whenDownstreamThrowsRuntimeException_routesToErrorConsumer() throws Exception {
    frameAggregator.configureSequenceIndices(ImmutableSet.of(0));
    RuntimeException exception = new RuntimeException("test");
    downstreamConsumer.runtimeExceptionToThrowOnQueueing = exception;
    GlTextureFrame frame = createGlTextureFrame();

    assertThat(frameAggregator.getInputConsumer(0).queue(frame, directExecutor(), () -> {}))
        .isTrue();

    assertThat(errorReference.get()).hasCauseThat().isEqualTo(exception);
  }

  @Test
  public void signalEndOfStream_partialEos_doesNotSignalDownstreamUntilAllStreamsEnd()
      throws Exception {
    frameAggregator.configureSequenceIndices(/* requestedSequenceIndices= */ ImmutableSet.of(0, 1));
    GlTextureFrame frame1 = createGlTextureFrame(/* sequenceIndex= */ 0);
    GlTextureFrame frame2 = createGlTextureFrame(/* sequenceIndex= */ 1);

    assertThat(frameAggregator.getInputConsumer(0).queue(frame1, directExecutor(), () -> {}))
        .isTrue();
    assertThat(frameAggregator.getInputConsumer(1).queue(frame2, directExecutor(), () -> {}))
        .isTrue();

    frameAggregator.getInputConsumer(0).signalEndOfStream();
    assertThat(downstreamConsumer.signalEndOfStreamCalled).isFalse();

    frameAggregator.getInputConsumer(1).signalEndOfStream();
    assertThat(downstreamConsumer.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void
      configureSequenceIndices_withPendingFrameForRemovedSequence_throwsIllegalStateException()
          throws Exception {
    frameAggregator.configureSequenceIndices(/* requestedSequenceIndices= */ ImmutableSet.of(0, 1));
    GlTextureFrame frame = createGlTextureFrame(/* sequenceIndex= */ 0);
    assertThat(frameAggregator.getInputConsumer(0).queue(frame, directExecutor(), () -> {}))
        .isTrue();

    assertThrows(
        IllegalStateException.class,
        () -> frameAggregator.configureSequenceIndices(ImmutableSet.of(1)));
  }

  @Test
  public void configureSequenceIndices_removingSequenceSatisfiesEos_signalsEosDownstream()
      throws Exception {
    frameAggregator.configureSequenceIndices(/* requestedSequenceIndices= */ ImmutableSet.of(0, 1));
    GlTextureFrame frame1 = createGlTextureFrame(/* sequenceIndex= */ 0);
    GlTextureFrame frame2 = createGlTextureFrame(/* sequenceIndex= */ 1);
    assertThat(frameAggregator.getInputConsumer(0).queue(frame1, directExecutor(), () -> {}))
        .isTrue();
    assertThat(frameAggregator.getInputConsumer(1).queue(frame2, directExecutor(), () -> {}))
        .isTrue();

    frameAggregator.getInputConsumer(0).signalEndOfStream();
    assertThat(downstreamConsumer.signalEndOfStreamCalled).isFalse();

    // Remove sequence 1, now only sequence 0 is active, which already signaled EOS.
    frameAggregator.configureSequenceIndices(ImmutableSet.of(0));
    assertThat(downstreamConsumer.signalEndOfStreamCalled).isTrue();
  }

  @Test
  public void configureSequenceIndices_dynamicAddingAndRemovingSequences_aggregatesCorrectly()
      throws Exception {
    // 1. Start with only one sequence, composites input immediately.
    frameAggregator.configureSequenceIndices(/* requestedSequenceIndices= */ ImmutableSet.of(0));
    GlTextureFrame frame1 = createGlTextureFrame(/* sequenceIndex= */ 0);
    assertThat(frameAggregator.getInputConsumer(0).queue(frame1, directExecutor(), () -> {}))
        .isTrue();
    assertThat(downstreamConsumer.framesReceived).isEqualTo(1);

    // 2. Add sequence 1
    frameAggregator.configureSequenceIndices(/* requestedSequenceIndices= */ ImmutableSet.of(0, 1));
    GlTextureFrame frame2 = createGlTextureFrame(/* sequenceIndex= */ 0);
    GlTextureFrame frame3 = createGlTextureFrame(/* sequenceIndex= */ 1);

    assertThat(frameAggregator.getInputConsumer(0).queue(frame2, directExecutor(), () -> {}))
        .isTrue();
    assertThat(downstreamConsumer.framesReceived).isEqualTo(1); // Waiting for Seq 1

    assertThat(frameAggregator.getInputConsumer(1).queue(frame3, directExecutor(), () -> {}))
        .isTrue();
    assertThat(downstreamConsumer.framesReceived).isEqualTo(2); // Both composited

    // 3. Remove sequence 0
    frameAggregator.configureSequenceIndices(ImmutableSet.of(1));
    GlTextureFrame frame4 = createGlTextureFrame(/* sequenceIndex= */ 1);
    assertThat(frameAggregator.getInputConsumer(1).queue(frame4, directExecutor(), () -> {}))
        .isTrue();
    assertThat(downstreamConsumer.framesReceived).isEqualTo(3); // Composited immediately
  }

  @Test
  public void queue_whenCapacityFreesUp_executesPendingWakeupListener() throws Exception {
    frameAggregator.configureSequenceIndices(/* requestedSequenceIndices= */ ImmutableSet.of(0, 1));
    downstreamConsumer.shouldAcceptIncomingFrames = false;
    queueOnePacketToBlockCompositor(/* numberOfSequences= */ 2);

    GlTextureFrame frame1 = createGlTextureFrame(/* sequenceIndex= */ 0);
    GlTextureFrame frame2 = createGlTextureFrame(/* sequenceIndex= */ 1);
    assertThat(frameAggregator.getInputConsumer(0).queue(frame1, directExecutor(), () -> {}))
        .isTrue();
    assertThat(frameAggregator.getInputConsumer(1).queue(frame2, directExecutor(), () -> {}))
        .isTrue();

    GlTextureFrame frame3 = createGlTextureFrame(/* sequenceIndex= */ 0);
    AtomicBoolean wakeupExecuted = new AtomicBoolean(false);
    assertThat(
            frameAggregator
                .getInputConsumer(0)
                .queue(frame3, directExecutor(), () -> wakeupExecuted.set(true)))
        .isFalse();

    downstreamConsumer.shouldAcceptIncomingFrames = true;
    downstreamConsumer.triggerWakeup();

    assertThat(wakeupExecuted.get()).isTrue();
  }

  @Test
  public void configureSequenceIndices_withBatchPendingComposition_defersConfiguration()
      throws Exception {
    frameAggregator.configureSequenceIndices(/* requestedSequenceIndices= */ ImmutableSet.of(0, 1));
    GlTextureFrameConsumer consumer0 = frameAggregator.getInputConsumer(0);
    GlTextureFrameConsumer consumer1 = frameAggregator.getInputConsumer(1);
    downstreamConsumer.shouldAcceptIncomingFrames = false;
    queueOnePacketToBlockCompositor(/* numberOfSequences= */ 2);

    GlTextureFrame frame1 = createGlTextureFrame(/* sequenceIndex= */ 0);
    GlTextureFrame frame2 = createGlTextureFrame(/* sequenceIndex= */ 1);
    assertThat(consumer0.queue(frame1, directExecutor(), () -> {})).isTrue();
    assertThat(consumer1.queue(frame2, directExecutor(), () -> {})).isTrue();

    // Remove sequence 1.
    frameAggregator.configureSequenceIndices(ImmutableSet.of(0));

    // consumer1 should still be able to queue (it returns false because downstream is blocked)
    AtomicBoolean wakeupInvoked = new AtomicBoolean();
    GlTextureFrame frame3 = createGlTextureFrame(/* sequenceIndex= */ 1);
    assertThat(
            consumer1.queue(
                frame3,
                directExecutor(),
                () -> {
                  wakeupInvoked.set(true);
                }))
        .isFalse();

    // Now free downstream.
    downstreamConsumer.shouldAcceptIncomingFrames = true;
    downstreamConsumer.triggerWakeup();
    assertThat(wakeupInvoked.get()).isTrue();
    // Downstream accepted. Configuration is applied. Sequence 1 is removed.
    // Now consumer1 should throw if we try to queue to it.
    GlTextureFrame frame4 = createGlTextureFrame(/* sequenceIndex= */ 1);
    assertThrows(
        IllegalStateException.class, () -> consumer1.queue(frame4, directExecutor(), () -> {}));
  }

  @Test
  public void configureSequenceIndices_newlyActiveConsumerWithPendingWakeup_invokesWakeupListener()
      throws Exception {
    frameAggregator.configureSequenceIndices(/* requestedSequenceIndices= */ ImmutableSet.of(0));
    GlTextureFrameConsumer consumer0 = frameAggregator.getInputConsumer(0);
    downstreamConsumer.shouldAcceptIncomingFrames = false;

    queueOnePacketToBlockCompositor(/* numberOfSequences= */ 1);

    // The consumer is blocked, so queueing returns false.
    AtomicBoolean frame1WokeUp = new AtomicBoolean();
    GlTextureFrame frame1 = createGlTextureFrame(/* sequenceIndex= */ 0);
    assertThat(consumer0.queue(frame1, directExecutor(), () -> frame1WokeUp.set(true))).isFalse();

    // Adding sequence 1 defers configuration, because the downstream has rejected inputs.
    frameAggregator.configureSequenceIndices(/* requestedSequenceIndices= */ ImmutableSet.of(0, 1));

    // We can get a consumer for the new sequence, but queueing returns false, because there's a
    // pending batch.
    GlTextureFrameConsumer consumer1 = frameAggregator.getInputConsumer(1);
    AtomicBoolean frame2WokeUp = new AtomicBoolean();
    GlTextureFrame frame2 = createGlTextureFrame(/* sequenceIndex= */ 1);
    assertThat(consumer1.queue(frame2, directExecutor(), () -> frame2WokeUp.set(true))).isFalse();

    downstreamConsumer.shouldAcceptIncomingFrames = true;
    downstreamConsumer.triggerWakeup();

    assertThat(frame1WokeUp.get()).isTrue();
    assertThat(frame2WokeUp.get()).isTrue();
  }

  @Test
  public void close_withBufferedFrames_releasesBufferedFrames() throws Exception {
    frameAggregator.configureSequenceIndices(ImmutableSet.of(0, 1));
    AtomicBoolean frameReleased = new AtomicBoolean(false);
    GlTextureFrame frame =
        new GlTextureFrame.Builder(
                new GlTextureInfo(
                    /* texId= */ 1,
                    /* fboId= */ -1,
                    /* rboId= */ -1,
                    /* width= */ 100,
                    /* height= */ 100),
                directExecutor(),
                info -> frameReleased.set(true))
            .setMetadata(
                ImmutableMap.of(
                    KEY_COMPOSITOR_SETTINGS,
                    VideoCompositorSettings.DEFAULT,
                    KEY_COMPOSITION_SEQUENCE_INDEX,
                    0))
            .build();

    assertThat(frameAggregator.getInputConsumer(0).queue(frame, directExecutor(), () -> {}))
        .isTrue();

    frameAggregator.close();

    assertThat(frameReleased.get()).isTrue();
  }

  @Test
  public void close_withBufferedFramesAtNonZeroSequenceIndex_releasesBufferedFrames()
      throws Exception {
    frameAggregator.configureSequenceIndices(ImmutableSet.of(5, 6));
    AtomicBoolean frameReleased = new AtomicBoolean(false);
    GlTextureFrame frame =
        new GlTextureFrame.Builder(
                new GlTextureInfo(
                    /* texId= */ 1,
                    /* fboId= */ -1,
                    /* rboId= */ -1,
                    /* width= */ 100,
                    /* height= */ 100),
                directExecutor(),
                info -> frameReleased.set(true))
            .setMetadata(
                ImmutableMap.of(
                    KEY_COMPOSITOR_SETTINGS,
                    VideoCompositorSettings.DEFAULT,
                    KEY_COMPOSITION_SEQUENCE_INDEX,
                    5))
            .build();

    assertThat(frameAggregator.getInputConsumer(5).queue(frame, directExecutor(), () -> {}))
        .isTrue();

    frameAggregator.close();

    assertThat(frameReleased.get()).isTrue();
  }

  private void queueOnePacketToBlockCompositor(int numberOfSequences) throws Exception {
    for (int sequenceIndex = 0; sequenceIndex < numberOfSequences; sequenceIndex++) {
      assertThat(
              frameAggregator
                  .getInputConsumer(sequenceIndex)
                  .queue(createGlTextureFrame(sequenceIndex), directExecutor(), () -> {}))
          .isTrue();
    }
  }

  private static GlTextureFrame createGlTextureFrame() {
    return createGlTextureFrame(/* sequenceIndex= */ 0);
  }

  private static GlTextureFrame createGlTextureFrame(int sequenceIndex) {
    return new GlTextureFrame.Builder(
            new GlTextureInfo(
                /* texId= */ 1,
                /* fboId= */ -1,
                /* rboId= */ -1,
                /* width= */ 100,
                /* height= */ 100),
            directExecutor(),
            /* releaseTextureCallback= */ info -> {})
        .setMetadata(
            ImmutableMap.of(
                KEY_COMPOSITOR_SETTINGS,
                VideoCompositorSettings.DEFAULT,
                KEY_COMPOSITION_SEQUENCE_INDEX,
                sequenceIndex))
        .build();
  }
}
