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
package androidx.media3.effect;

import static androidx.media3.effect.DefaultGlFrameProcessor.KEY_FRAME_DISCONTINUITY_NUMBER;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.effect.GlFrameProcessorTestUtil.FakeGlTextureFrameConsumer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link BaseGlTextureFrameProcessor}. */
@RunWith(AndroidJUnit4.class)
public final class BaseGlTextureFrameProcessorTest {

  private static final int TEXTURE_WIDTH = 16;
  private static final int TEXTURE_HEIGHT = 8;
  private static final Format TEST_FORMAT =
      new Format.Builder().setWidth(TEXTURE_WIDTH).setHeight(TEXTURE_HEIGHT).build();

  private ArrayDeque<Runnable> queuedWakeupTasks;
  private Executor wakeupExecutor;
  private ArrayDeque<Runnable> queuedErrorTasks;
  private Executor errorExecutor;
  private FakeGlTextureFrameConsumer downstreamConsumer;
  private AtomicReference<VideoFrameProcessingException> errorReference;
  private FakeGlTextureFrameProcessor processor;

  @Before
  public void setUp() {
    queuedWakeupTasks = new ArrayDeque<>();
    wakeupExecutor = queuedWakeupTasks::add;
    queuedErrorTasks = new ArrayDeque<>();
    errorExecutor = queuedErrorTasks::add;
    downstreamConsumer = new FakeGlTextureFrameConsumer(/* frameWriter= */ null);
    errorReference = new AtomicReference<>();
    processor =
        new FakeGlTextureFrameProcessor(errorExecutor, /* errorConsumer= */ errorReference::set);
    processor.setOutput(downstreamConsumer);
  }

  @After
  public void tearDown() throws Exception {
    processor.close();
  }

  @Test
  public void setOutput_whenNoInFlightFrames_setsDownstreamConsumer() {
    FakeGlTextureFrameConsumer newDownstream =
        new FakeGlTextureFrameConsumer(/* frameWriter= */ null);

    processor.setOutput(newDownstream);

    assertThat(processor.downstreamConsumer).isSameInstanceAs(newDownstream);
  }

  @Test
  public void setOutput_whenHasInFlightFrames_throwsIllegalStateException() {
    processor.hasInFlightFrames = true;
    FakeGlTextureFrameConsumer newDownstream =
        new FakeGlTextureFrameConsumer(/* frameWriter= */ null);

    assertThrows(IllegalStateException.class, () -> processor.setOutput(newDownstream));
  }

  @Test
  public void queue_withDirectExecutor_throwsIllegalArgumentException() {
    GlTextureFrame inputFrame =
        createFrame(/* metadata= */ ImmutableMap.of(), /* releaseCount= */ new AtomicInteger());
    Executor directExecutor = directExecutor();

    assertThrows(
        IllegalArgumentException.class,
        () -> processor.queue(inputFrame, directExecutor, /* wakeupListener= */ () -> {}));
  }

  @Test
  public void queue_beforeSetOutput_throwsIllegalStateException() {
    FakeGlTextureFrameProcessor unconfiguredProcessor =
        new FakeGlTextureFrameProcessor(errorExecutor, /* errorConsumer= */ errorReference::set);
    GlTextureFrame inputFrame =
        createFrame(/* metadata= */ ImmutableMap.of(), /* releaseCount= */ new AtomicInteger());

    assertThrows(
        IllegalStateException.class,
        () ->
            unconfiguredProcessor.queue(
                inputFrame, wakeupExecutor, /* wakeupListener= */ () -> {}));
  }

  @Test
  public void queue_whenOnQueueFrameAccepts_queuesFrameWithoutInvokingWakeupListener()
      throws Exception {
    GlTextureFrame inputFrame =
        createFrame(/* metadata= */ ImmutableMap.of(), /* releaseCount= */ new AtomicInteger());
    AtomicBoolean wakeupCalled = new AtomicBoolean();

    boolean queued =
        processor.queue(
            inputFrame, wakeupExecutor, /* wakeupListener= */ () -> wakeupCalled.set(true));
    // Verify wakeupListener was not stored when the frame was accepted.
    processor.maybeInvokePendingWakeupListener();

    assertThat(queued).isTrue();
    assertThat(processor.attemptedQueuedFrames).containsExactly(inputFrame);
    assertThat(queuedWakeupTasks).isEmpty();
    assertThat(wakeupCalled.get()).isFalse();
    assertThat(queuedErrorTasks).isEmpty();
    assertThat(errorReference.get()).isNull();
  }

  @Test
  public void queue_withStaleDiscontinuityNumber_dropsAndReleasesFrame() throws Exception {
    AtomicInteger currentReleaseCount = new AtomicInteger();
    AtomicInteger staleReleaseCount = new AtomicInteger();
    GlTextureFrame currentFrame =
        createFrame(
            /* metadata= */ ImmutableMap.of(KEY_FRAME_DISCONTINUITY_NUMBER, 2),
            currentReleaseCount);
    GlTextureFrame staleFrame =
        createFrame(
            /* metadata= */ ImmutableMap.of(KEY_FRAME_DISCONTINUITY_NUMBER, 1), staleReleaseCount);

    boolean queuedCurrent =
        processor.queue(currentFrame, wakeupExecutor, /* wakeupListener= */ () -> {});
    boolean queuedStale =
        processor.queue(staleFrame, wakeupExecutor, /* wakeupListener= */ () -> {});

    assertThat(queuedCurrent).isTrue();
    assertThat(queuedStale).isTrue();
    assertThat(processor.onFrameDiscontinuityCount).isEqualTo(1);
    assertThat(processor.attemptedQueuedFrames).containsExactly(currentFrame);
    assertThat(currentReleaseCount.get()).isEqualTo(0);
    assertThat(staleReleaseCount.get()).isEqualTo(1);
  }

  @Test
  public void queue_withStaleDiscontinuityAndPendingWakeup_preservesWakeupListener()
      throws Exception {
    processor.acceptNextFrame = false;
    AtomicInteger staleReleaseCount = new AtomicInteger();
    AtomicBoolean wakeupCalled = new AtomicBoolean();
    GlTextureFrame currentFrame =
        createFrame(
            /* metadata= */ ImmutableMap.of(KEY_FRAME_DISCONTINUITY_NUMBER, 2),
            /* releaseCount= */ new AtomicInteger());
    GlTextureFrame staleFrame =
        createFrame(
            /* metadata= */ ImmutableMap.of(KEY_FRAME_DISCONTINUITY_NUMBER, 1), staleReleaseCount);

    boolean queuedCurrent =
        processor.queue(
            currentFrame, wakeupExecutor, /* wakeupListener= */ () -> wakeupCalled.set(true));
    boolean queuedStale =
        processor.queue(staleFrame, wakeupExecutor, /* wakeupListener= */ () -> {});
    processor.maybeInvokePendingWakeupListener();
    queuedWakeupTasks.remove().run();

    assertThat(queuedCurrent).isFalse();
    assertThat(queuedStale).isTrue();
    assertThat(staleReleaseCount.get()).isEqualTo(1);
    assertThat(queuedWakeupTasks).isEmpty();
    assertThat(wakeupCalled.get()).isTrue();
    assertThat(processor.attemptedQueuedFrames).containsExactly(currentFrame);
  }

  @Test
  public void queue_withHigherDiscontinuity_notifiesDiscontinuityClearsWakeupAndQueues()
      throws Exception {
    GlTextureFrame firstFrame =
        createFrame(
            /* metadata= */ ImmutableMap.of(KEY_FRAME_DISCONTINUITY_NUMBER, 0),
            /* releaseCount= */ new AtomicInteger());
    GlTextureFrame secondFrame =
        createFrame(
            /* metadata= */ ImmutableMap.of(KEY_FRAME_DISCONTINUITY_NUMBER, 1),
            /* releaseCount= */ new AtomicInteger());
    AtomicBoolean firstWakeupCalled = new AtomicBoolean();
    processor.acceptNextFrame = false;
    // Force trigger wakeup to verify the wakeup is cleared.
    processor.triggerWakeupInOnFrameDiscontinuity = true;
    assertThat(
            processor.queue(
                firstFrame,
                wakeupExecutor,
                /* wakeupListener= */ () -> firstWakeupCalled.set(true)))
        .isFalse();
    processor.acceptNextFrame = true;

    boolean queuedSecond =
        processor.queue(secondFrame, wakeupExecutor, /* wakeupListener= */ () -> {});
    processor.maybeInvokePendingWakeupListener();

    assertThat(queuedSecond).isTrue();
    assertThat(processor.onFrameDiscontinuityCount).isEqualTo(2);
    assertThat(processor.attemptedQueuedFrames).containsExactly(firstFrame, secondFrame).inOrder();
    assertThat(queuedWakeupTasks).isEmpty();
    assertThat(firstWakeupCalled.get()).isFalse();
  }

  @Test
  public void queue_withEqualDiscontinuityNumber_queuesFrameWithoutOnFrameDiscontinuity()
      throws Exception {
    GlTextureFrame streamFrame1 =
        createFrame(
            /* metadata= */ ImmutableMap.of(KEY_FRAME_DISCONTINUITY_NUMBER, 1),
            /* releaseCount= */ new AtomicInteger());
    GlTextureFrame streamFrame2 =
        createFrame(
            /* metadata= */ ImmutableMap.of(KEY_FRAME_DISCONTINUITY_NUMBER, 1),
            /* releaseCount= */ new AtomicInteger());

    boolean queuedStream1 =
        processor.queue(streamFrame1, wakeupExecutor, /* wakeupListener= */ () -> {});
    boolean queuedStream2 =
        processor.queue(streamFrame2, wakeupExecutor, /* wakeupListener= */ () -> {});

    assertThat(queuedStream1).isTrue();
    assertThat(queuedStream2).isTrue();
    assertThat(processor.onFrameDiscontinuityCount).isEqualTo(1);
    assertThat(processor.attemptedQueuedFrames)
        .containsExactly(streamFrame1, streamFrame2)
        .inOrder();
  }

  @Test
  public void queue_rejectedTriggersWakeupTwice_invokesWakeupListenerExactlyOnceOnExecutor()
      throws Exception {
    processor.acceptNextFrame = false;
    GlTextureFrame inputFrame =
        createFrame(/* metadata= */ ImmutableMap.of(), /* releaseCount= */ new AtomicInteger());
    AtomicInteger wakeupCount = new AtomicInteger();

    boolean queued =
        processor.queue(
            inputFrame, wakeupExecutor, /* wakeupListener= */ wakeupCount::incrementAndGet);
    processor.maybeInvokePendingWakeupListener();
    processor.maybeInvokePendingWakeupListener();
    queuedWakeupTasks.remove().run();

    assertThat(queued).isFalse();
    assertThat(queuedWakeupTasks).isEmpty();
    assertThat(wakeupCount.get()).isEqualTo(1);
    assertThat(errorReference.get()).isNull();
  }

  @Test
  public void queue_sameFrameQueuedTwice_overwritesPreviousWakeupListener() throws Exception {
    processor.acceptNextFrame = false;
    GlTextureFrame frame =
        createFrame(/* metadata= */ ImmutableMap.of(), /* releaseCount= */ new AtomicInteger());
    AtomicBoolean firstWakeupCalled = new AtomicBoolean();
    AtomicBoolean secondWakeupCalled = new AtomicBoolean();

    boolean queuedFirst =
        processor.queue(
            frame, wakeupExecutor, /* wakeupListener= */ () -> firstWakeupCalled.set(true));
    boolean queuedSecond =
        processor.queue(
            frame, wakeupExecutor, /* wakeupListener= */ () -> secondWakeupCalled.set(true));
    processor.maybeInvokePendingWakeupListener();
    queuedWakeupTasks.remove().run();

    assertThat(queuedFirst).isFalse();
    assertThat(queuedSecond).isFalse();
    assertThat(queuedWakeupTasks).isEmpty();
    assertThat(firstWakeupCalled.get()).isFalse();
    assertThat(secondWakeupCalled.get()).isTrue();
    assertThat(errorReference.get()).isNull();
  }

  @Test
  public void queue_whenAcceptedAfterPriorRejection_clearsStaleWakeupListener() throws Exception {
    processor.acceptNextFrame = false;
    GlTextureFrame frame =
        createFrame(/* metadata= */ ImmutableMap.of(), /* releaseCount= */ new AtomicInteger());
    AtomicBoolean firstWakeupCalled = new AtomicBoolean();
    assertThat(
            processor.queue(
                frame, wakeupExecutor, /* wakeupListener= */ () -> firstWakeupCalled.set(true)))
        .isFalse();
    processor.acceptNextFrame = true;

    boolean queuedSecond = processor.queue(frame, wakeupExecutor, /* wakeupListener= */ () -> {});
    processor.maybeInvokePendingWakeupListener();

    assertThat(queuedSecond).isTrue();
    assertThat(queuedWakeupTasks).isEmpty();
    assertThat(firstWakeupCalled.get()).isFalse();
  }

  @Test
  public void maybeInvokePendingWakeupListener_whenListenerThrowsRuntimeException_reportsError()
      throws Exception {
    processor.acceptNextFrame = false;
    GlTextureFrame inputFrame =
        createFrame(/* metadata= */ ImmutableMap.of(), /* releaseCount= */ new AtomicInteger());
    IllegalStateException failure = new IllegalStateException("wakeup failure");
    assertThat(
            processor.queue(
                inputFrame,
                wakeupExecutor,
                /* wakeupListener= */ () -> {
                  throw failure;
                }))
        .isFalse();

    processor.maybeInvokePendingWakeupListener();
    queuedWakeupTasks.remove().run();
    assertThat(errorReference.get()).isNull();
    assertThat(queuedErrorTasks).hasSize(1);

    queuedErrorTasks.remove().run();
    assertThat(errorReference.get()).hasCauseThat().isSameInstanceAs(failure);
  }

  @Test
  public void onError_withException_dispatchesOnErrorConsumerExecutor() {
    IllegalStateException failure = new IllegalStateException("processing failure");

    processor.reportError(failure);
    assertThat(errorReference.get()).isNull();
    assertThat(queuedErrorTasks).hasSize(1);

    queuedErrorTasks.remove().run();
    assertThat(errorReference.get()).hasCauseThat().isSameInstanceAs(failure);
  }

  @Test
  public void signalEndOfStream_afterSetOutput_delegatesToOnEndOfStream() {
    processor.signalEndOfStream();

    assertThat(processor.onEndOfStreamCount).isEqualTo(1);
  }

  @Test
  public void signalEndOfStream_beforeSetOutput_throwsIllegalStateException() {
    FakeGlTextureFrameProcessor unconfiguredProcessor =
        new FakeGlTextureFrameProcessor(errorExecutor, /* errorConsumer= */ errorReference::set);

    assertThrows(IllegalStateException.class, unconfiguredProcessor::signalEndOfStream);
  }

  @Test
  public void close_withPendingWakeupListener_invokesOnCloseClearsWakeupAndLeavesDownstreamOpen()
      throws Exception {
    processor.acceptNextFrame = false;
    GlTextureFrame inputFrame =
        createFrame(/* metadata= */ ImmutableMap.of(), /* releaseCount= */ new AtomicInteger());
    AtomicBoolean wakeupCalled = new AtomicBoolean();
    assertThat(
            processor.queue(
                inputFrame, wakeupExecutor, /* wakeupListener= */ () -> wakeupCalled.set(true)))
        .isFalse();

    processor.close();
    // Verify close() cleared the pending wakeup listener.
    processor.maybeInvokePendingWakeupListener();

    assertThat(processor.onCloseCount).isEqualTo(1);
    assertThat(downstreamConsumer.closeThread).isNull();
    assertThat(queuedWakeupTasks).isEmpty();
    assertThat(wakeupCalled.get()).isFalse();
  }

  private static GlTextureFrame createFrame(
      Map<String, Object> metadata, AtomicInteger releaseCount) {
    return new GlTextureFrame.Builder(
            new GlTextureInfo(
                /* texId= */ 100,
                /* fboId= */ C.INDEX_UNSET,
                /* rboId= */ C.INDEX_UNSET,
                /* width= */ TEXTURE_WIDTH,
                /* height= */ TEXTURE_HEIGHT),
            /* releaseTextureExecutor= */ directExecutor(),
            /* releaseTextureCallback= */ unused -> releaseCount.incrementAndGet())
        .setPresentationTimeUs(/* presentationTimeUs= */ 0)
        .setFormat(TEST_FORMAT)
        .setMetadata(metadata)
        .build();
  }

  private static final class FakeGlTextureFrameProcessor extends BaseGlTextureFrameProcessor {

    final List<GlTextureFrame> attemptedQueuedFrames;
    boolean hasInFlightFrames;
    boolean acceptNextFrame;
    boolean triggerWakeupInOnFrameDiscontinuity;
    int onFrameDiscontinuityCount;
    int onEndOfStreamCount;
    int onCloseCount;

    FakeGlTextureFrameProcessor(
        Executor errorConsumerExecutor, Consumer<VideoFrameProcessingException> errorConsumer) {
      super(errorConsumerExecutor, errorConsumer);
      attemptedQueuedFrames = new ArrayList<>();
      acceptNextFrame = true;
    }

    void reportError(Exception exception) {
      onError(exception);
    }

    @Override
    protected boolean hasInFlightFrames() {
      return hasInFlightFrames;
    }

    @Override
    protected void onFrameDiscontinuity() {
      onFrameDiscontinuityCount++;
      if (triggerWakeupInOnFrameDiscontinuity) {
        maybeInvokePendingWakeupListener();
      }
    }

    @Override
    protected boolean onQueueFrame(
        GlTextureFrame frame, Executor listenerExecutor, Runnable wakeupListener) {
      attemptedQueuedFrames.add(frame);
      if (!acceptNextFrame) {
        setPendingWakeupListener(listenerExecutor, wakeupListener);
      }
      return acceptNextFrame;
    }

    @Override
    protected void onEndOfStream() {
      onEndOfStreamCount++;
    }

    @Override
    protected void onClose() {
      onCloseCount++;
    }
  }
}
