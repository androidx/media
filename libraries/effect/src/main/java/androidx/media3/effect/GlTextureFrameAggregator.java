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

import static androidx.media3.common.util.Util.contains;
import static androidx.media3.effect.FrameProcessorUtils.runAllAndAccumulateExceptions;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.effect.FrameProcessorUtils.ThrowingRunnable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Encapsulates N-to-1 frame aggregation logic.
 *
 * <p>It receives input via {@link GlTextureFrameConsumer} acquired via {@link #getInputConsumer},
 * and outputs aggregated frames to a {@link DefaultGlTextureFrameCompositingProcessor}.
 *
 * <p>All methods must be called on a GL thread.
 */
// TODO: b/524240959 - Consider sharing logic with FrameAggregator.
@RequiresApi(26)
/* package */ final class GlTextureFrameAggregator implements AutoCloseable {
  private final DefaultGlTextureFrameCompositingProcessor compositingProcessor;
  private final ListeningExecutorService glExecutorService;
  private final Consumer<VideoFrameProcessingException> errorConsumer;
  private final SparseArray<InputFrameConsumer> sequenceConsumers;

  @Nullable private ImmutableSet<Integer> pendingSequenceIndices;
  private boolean isClosed;
  // This is reset when a new batch of frames is queued.
  private boolean allInputStreamsEnded;

  public GlTextureFrameAggregator(
      DefaultGlTextureFrameCompositingProcessor compositingProcessor,
      ListeningExecutorService glExecutorService,
      Consumer<VideoFrameProcessingException> errorConsumer) {
    this.compositingProcessor = compositingProcessor;
    this.glExecutorService = glExecutorService;
    this.errorConsumer = errorConsumer;
    sequenceConsumers = new SparseArray<>();
  }

  /**
   * Returns the {@link GlTextureFrameConsumer} for the specified sequence index.
   *
   * <p>The {@code sequenceIndex} must have been {@linkplain #configureSequenceIndices configured}
   * before calling this method.
   */
  public GlTextureFrameConsumer getInputConsumer(int sequenceIndex) {
    // Checks if a configuration change is pending. The frames from the active sequences could
    // still be pending composition, but we can allow the caller to acquire the consumer and queue
    // the next frame promptly for the new configuration.
    checkArgument(
        pendingSequenceIndices == null
            ? (contains(sequenceConsumers, sequenceIndex)
                && sequenceConsumers.get(sequenceIndex).isActive)
            : pendingSequenceIndices.contains(sequenceIndex),
        "sequenceIndex %s not configured",
        sequenceIndex);
    @Nullable InputFrameConsumer consumer = sequenceConsumers.get(sequenceIndex);
    if (consumer == null) {
      consumer = new InputFrameConsumer(sequenceIndex);
      sequenceConsumers.put(sequenceIndex, consumer);
    }
    return consumer;
  }

  /**
   * Sets the pending active sequence indices for N-to-1 frame gathering.
   *
   * <p>The configuration change is applied when there are no pending compositions.
   *
   * @param requestedSequenceIndices The sequence indices of the upcoming input frames. This set
   *     must not be empty.
   */
  public void configureSequenceIndices(Set<Integer> requestedSequenceIndices) {
    checkArgument(!requestedSequenceIndices.isEmpty());
    if (isClosed) {
      return;
    }
    if (!isBatchPendingComposition()) {
      // If a batch is pending, the configuration change is deferred until the current batch is
      // composited. Thus, it's safe to skip this check when the current frames are pending (or,
      // committed) for the upcoming composition.
      for (int i = 0; i < sequenceConsumers.size(); i++) {
        InputFrameConsumer consumer = sequenceConsumers.valueAt(i);
        if (consumer.isActive) {
          int sequenceIndex = sequenceConsumers.keyAt(i);
          // Upstream processors must not queue a new batch of frames where a sequence has
          // disappeared before previously queued frames are fully composited and sent downstream.
          checkState(
              requestedSequenceIndices.contains(sequenceIndex) || consumer.pendingFrame == null,
              "Sequence disappeared while its frame is still pending composition");
        }
      }
    }
    pendingSequenceIndices = ImmutableSet.copyOf(requestedSequenceIndices);
    if (!isBatchPendingComposition()) {
      applyPendingConfiguration();
    }
  }

  @Override
  public void close() throws VideoFrameProcessingException {
    if (isClosed) {
      return;
    }
    isClosed = true;
    allInputStreamsEnded = false;

    ImmutableList<GlTextureFrame> framesToRelease = retrieveAndClearPendingFrames();
    sequenceConsumers.clear();

    releaseFrames(framesToRelease);
  }

  private void applyPendingConfiguration() {
    @Nullable ImmutableSet<Integer> pendingSequenceIndices = this.pendingSequenceIndices;
    if (isClosed || pendingSequenceIndices == null) {
      return;
    }
    boolean sequencesRemoved = false;
    // Iterate backwards when removing items from a SparseArray to avoid index shifting.
    for (int i = sequenceConsumers.size() - 1; i >= 0; i--) {
      int sequenceIndex = sequenceConsumers.keyAt(i);
      InputFrameConsumer consumer = sequenceConsumers.valueAt(i);
      if (!pendingSequenceIndices.contains(sequenceIndex)) {
        if (consumer.isActive) {
          sequencesRemoved = true;
          checkState(consumer.pendingWakeupListener == null);
          consumer.isActive = false;
          @Nullable GlTextureFrame pendingFrame = consumer.pendingFrame;
          if (pendingFrame != null) {
            consumer.pendingFrame = null;
            try {
              releaseFrames(ImmutableList.of(pendingFrame));
            } catch (VideoFrameProcessingException | RuntimeException e) {
              errorConsumer.accept(VideoFrameProcessingException.from(e));
            }
          }
        }
      }
    }
    for (Integer sequenceIndex : pendingSequenceIndices) {
      @Nullable InputFrameConsumer consumer = sequenceConsumers.get(sequenceIndex);
      if (consumer == null) {
        consumer = new InputFrameConsumer(sequenceIndex);
        sequenceConsumers.put(sequenceIndex, consumer);
      }
      boolean wasActive = consumer.isActive;
      consumer.isActive = true;
      if (!wasActive) {
        consumer.maybeInvokeWakeupListener();
      }
    }
    this.pendingSequenceIndices = null;

    if (sequencesRemoved) {
      maybeSignalEndOfStream();
    }
    tryQueueToCompositingProcessor();
  }

  private void maybeSignalEndOfStream() {
    if (allInputStreamsEnded) {
      return;
    }
    int endedCount = 0;
    int activeCount = 0;
    for (int i = 0; i < sequenceConsumers.size(); i++) {
      InputFrameConsumer consumer = sequenceConsumers.valueAt(i);
      if (consumer.isActive) {
        activeCount++;
        if (consumer.isEnded) {
          endedCount++;
        }
      }
    }
    if (activeCount > 0 && endedCount == activeCount) {
      allInputStreamsEnded = true;
      try {
        releaseFrames(retrieveAndClearPendingFrames());
      } catch (VideoFrameProcessingException | RuntimeException e) {
        errorConsumer.accept(VideoFrameProcessingException.from(e));
      }
      compositingProcessor.signalEndOfStream();
    }
  }

  private void tryQueueToCompositingProcessor() {
    if (isClosed || !isBatchPendingComposition()) {
      return;
    }
    ImmutableList.Builder<GlTextureFrame> framesToComposite = new ImmutableList.Builder<>();
    for (int i = 0; i < sequenceConsumers.size(); i++) {
      InputFrameConsumer consumer = sequenceConsumers.valueAt(i);
      if (consumer.isActive) {
        framesToComposite.add(checkNotNull(consumer.pendingFrame));
      }
    }
    boolean queued;
    try {
      queued =
          compositingProcessor.queue(
              framesToComposite.build(),
              glExecutorService,
              /* wakeupListener= */ this::tryQueueToCompositingProcessor);
    } catch (RuntimeException | VideoFrameProcessingException e) {
      // Propagating the exception synchronously here ensures that synchronous callers catch the
      // failure immediately to release pending resources.
      try {
        releaseFrames(retrieveAndClearPendingFrames());
      } catch (VideoFrameProcessingException | RuntimeException releaseException) {
        e.addSuppressed(releaseException);
      }
      throw new IllegalStateException(e);
    }
    if (queued) {
      for (int i = 0; i < sequenceConsumers.size(); i++) {
        InputFrameConsumer consumer = sequenceConsumers.valueAt(i);
        if (consumer.isActive) {
          consumer.pendingFrame = null;
        }
      }
      for (int i = 0; i < sequenceConsumers.size(); i++) {
        InputFrameConsumer consumer = sequenceConsumers.valueAt(i);
        if (consumer.isActive) {
          consumer.maybeInvokeWakeupListener();
        }
      }
      if (pendingSequenceIndices != null) {
        applyPendingConfiguration();
      }
      maybeSignalEndOfStream();
    }
  }

  private boolean isBatchPendingComposition() {
    int sequenceConsumerCount = sequenceConsumers.size();
    if (sequenceConsumerCount == 0) {
      return false;
    }

    for (int i = 0; i < sequenceConsumerCount; i++) {
      InputFrameConsumer consumer = sequenceConsumers.valueAt(i);
      if (consumer.isActive && consumer.pendingFrame == null) {
        return false;
      }
    }
    return true;
  }

  private ImmutableList<GlTextureFrame> retrieveAndClearPendingFrames() {
    ImmutableList.Builder<GlTextureFrame> frames = new ImmutableList.Builder<>();
    for (int i = 0; i < sequenceConsumers.size(); i++) {
      InputFrameConsumer consumer = sequenceConsumers.valueAt(i);
      @Nullable GlTextureFrame pendingFrame = consumer.pendingFrame;
      if (pendingFrame != null) {
        consumer.pendingFrame = null;
        frames.add(pendingFrame);
      }
    }
    return frames.build();
  }

  private void releaseFrames(List<GlTextureFrame> frames) throws VideoFrameProcessingException {
    ImmutableList.Builder<ThrowingRunnable> releaseActions = new ImmutableList.Builder<>();
    for (int i = 0; i < frames.size(); i++) {
      GlTextureFrame frame = frames.get(i);
      releaseActions.add(() -> frame.release(/* releaseFence= */ null));
    }
    runAllAndAccumulateExceptions(releaseActions.build().toArray(new ThrowingRunnable[0]));
  }

  private final class InputFrameConsumer implements GlTextureFrameConsumer {
    private final int sequenceIndex;
    @Nullable private Runnable pendingWakeupListener;
    @Nullable private Executor pendingWakeupExecutor;
    @Nullable private GlTextureFrame pendingFrame;
    // This is reset when a new batch of frames is queued.
    private boolean isEnded;
    private boolean isActive;

    InputFrameConsumer(int sequenceIndex) {
      this.sequenceIndex = sequenceIndex;
    }

    @Override
    public boolean queue(GlTextureFrame frame, Executor listenerExecutor, Runnable wakeupListener) {
      checkState(
          isActive
              || (pendingSequenceIndices != null && pendingSequenceIndices.contains(sequenceIndex)),
          "Sequence is not active");
      if (isClosed) {
        return false;
      }

      if (isBatchPendingComposition() || pendingFrame != null) {
        pendingWakeupListener = wakeupListener;
        pendingWakeupExecutor = listenerExecutor;
        return false;
      }

      isEnded = false;
      allInputStreamsEnded = false;
      pendingFrame = frame;
      tryQueueToCompositingProcessor();
      // Returning true to signal the input frame is queued. If queueing to the compositing
      // processor fails, this class retries internally.
      return true;
    }

    @Override
    public void signalEndOfStream() {
      isEnded = true;
      if (!isBatchPendingComposition()) {
        maybeSignalEndOfStream();
      }
    }

    @Override
    public void close() {
      // Resources released by outer class.
    }

    void maybeInvokeWakeupListener() {
      if (pendingWakeupListener == null || pendingWakeupExecutor == null) {
        return;
      }
      Runnable listener = pendingWakeupListener;
      Executor executor = pendingWakeupExecutor;
      pendingWakeupListener = null;
      pendingWakeupExecutor = null;
      executor.execute(
          () -> {
            try {
              listener.run();
            } catch (RuntimeException e) {
              errorConsumer.accept(VideoFrameProcessingException.from(e));
            }
          });
    }
  }
}
