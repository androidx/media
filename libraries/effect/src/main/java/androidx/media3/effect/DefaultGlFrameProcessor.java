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

import static androidx.media3.effect.FrameProcessorUtils.runAllAndAccumulateExceptions;
import static androidx.media3.effect.FrameProcessorUtils.waitAndCloseFence;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;

import android.content.Context;
import android.hardware.HardwareBuffer;
import android.opengl.GLES20;
import android.util.SparseArray;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Effect;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.Log;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.effect.DefaultGlTextureFrameCompositingProcessor.CompositorGlProgram;
import androidx.media3.effect.FrameProcessorUtils.ThrowingRunnable;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * A {@link FrameProcessor} implementation that executes a chain of {@link GlTextureFrameConsumer}s.
 */
@ExperimentalApi // TODO: b/505721737 Remove once FrameProcessor is production ready.
@RequiresApi(26)
public final class DefaultGlFrameProcessor implements FrameProcessor {

  private static final String TAG = "GlFrameProcessor";

  /** Converts from {@link HardwareBuffer} to {@link GlTextureFrame}. */
  interface HardwareBufferConverter extends AutoCloseable {

    // TODO: b/517424999 - Unify the listeners to follow the same pattern as FrameProcessor.
    /**
     * Converts from {@link HardwareBufferFrame} to {@link GlTextureFrame}.
     *
     * <p>The returned {@link GlTextureFrame}'s texture is in standard OpenGL coordinate space
     * (upright, Y-up, with origin at bottom-left of the image).
     */
    GlTextureFrame convert(
        HardwareBufferFrame hardwareBufferFrame,
        Executor glExecutor,
        Executor listenerExecutor,
        Listener listener)
        throws VideoFrameProcessingException;

    /**
     * Releases the resources for a converted {@link HardwareBufferFrame}.
     *
     * <p>Don't call this method if the {@linkplain #convert converted} {@link GlTextureFrame} is
     * accepted by a downstream {@link GlTextureFrameConsumer}.
     *
     * <p>This releases associated resources without notifying the {@link Listener#onFrameProcessed}
     * that was passed in via {@link #convert}.
     *
     * <p>This is used when a converted frame is rejected by downstream pipeline consumers,
     * preserving the underlying {@code HardwareBuffer} for subsequent queue retries.
     */
    void releaseGlResources(HardwareBufferFrame hardwareBufferFrame)
        throws VideoFrameProcessingException;

    @Override
    void close() throws VideoFrameProcessingException;
  }

  /** A {@link FrameProcessor.Factory} that creates {@link DefaultGlFrameProcessor} instances. */
  public static final class Factory implements FrameProcessor.Factory {
    private final Context context;
    private final GlObjectsProvider glObjectsProvider;
    private final ExecutorService glExecutorService;
    @Nullable private final HardwareBufferConverter hardwareBufferConverter;
    @Nullable private final HardwareBufferJniWrapper hardwareBufferJniWrapper;
    @Nullable private final GlTextureFrameConsumer frameWriterGlTextureFrameConsumer;
    private final CompositorGlProgram compositorGlProgram;
    private final TexturePool compositorTexturePool;

    /**
     * Creates an instance.
     *
     * <p>The caller is responsible for setting up OpenGL resources and releasing them after
     * {@linkplain FrameProcessor#close closing} the built {@link DefaultGlFrameProcessor}. The
     * caller should also shut down the {@link ExecutorService glExecutorService}.
     */
    public Factory(
        Context context,
        GlObjectsProvider glObjectsProvider,
        HardwareBufferJniWrapper hardwareBufferJniWrapper,
        ListeningExecutorService glExecutorService) {
      this.context = context.getApplicationContext();
      this.glObjectsProvider = glObjectsProvider;
      this.hardwareBufferJniWrapper = hardwareBufferJniWrapper;
      this.glExecutorService = glExecutorService;
      hardwareBufferConverter = null;
      frameWriterGlTextureFrameConsumer = null;
      compositorGlProgram = new DefaultCompositorGlProgram(context);
      compositorTexturePool =
          new TexturePool(
              /* useHighPrecisionColorComponents= */ false, DEFAULT_COMPOSITOR_CAPACITY);
    }

    /**
     * Creates an instance for testing only.
     *
     * <p>The caller is responsible for shutting down the {@link ListeningExecutorService
     * glExecutorService} after {@linkplain FrameProcessor#close closing} the built {@link
     * DefaultGlFrameProcessor}.
     */
    @VisibleForTesting
    /* package */ Factory(
        Context context,
        GlObjectsProvider glObjectsProvider,
        ListeningExecutorService glExecutorService,
        HardwareBufferConverter hardwareBufferConverter,
        GlTextureFrameConsumer frameWriterGlTextureFrameConsumer,
        CompositorGlProgram compositorGlProgram,
        TexturePool compositorTexturePool) {
      this.context = context;
      this.glObjectsProvider = glObjectsProvider;
      this.glExecutorService = glExecutorService;
      this.hardwareBufferConverter = hardwareBufferConverter;
      this.frameWriterGlTextureFrameConsumer = frameWriterGlTextureFrameConsumer;
      this.compositorGlProgram = compositorGlProgram;
      this.compositorTexturePool = compositorTexturePool;
      hardwareBufferJniWrapper = null;
    }

    @Override
    public DefaultGlFrameProcessor create(
        FrameWriter output, Executor listenerExecutor, Listener listener) {
      GlTextureFrameConsumer frameWriterGlTextureFrameConsumer =
          this.frameWriterGlTextureFrameConsumer;
      if (frameWriterGlTextureFrameConsumer == null && hardwareBufferJniWrapper != null) {
        frameWriterGlTextureFrameConsumer =
            new FrameWriterGlTextureFrameConsumer(context, output, hardwareBufferJniWrapper);
      }
      HardwareBufferConverter hardwareBufferConverter = this.hardwareBufferConverter;
      if (hardwareBufferConverter == null && hardwareBufferJniWrapper != null) {
        hardwareBufferConverter =
            new HardwareBufferToGlTextureConverter(
                context,
                hardwareBufferJniWrapper,
                // TODO: b/517525358 - Use correct output color info once HDR is supported.
                ColorInfo.SDR_BT709_LIMITED,
                e -> listenerExecutor.execute(() -> listener.onError(e)));
      }
      return new DefaultGlFrameProcessor(
          context,
          listeningDecorator(glExecutorService),
          glObjectsProvider,
          checkNotNull(hardwareBufferConverter),
          checkNotNull(frameWriterGlTextureFrameConsumer),
          compositorGlProgram,
          compositorTexturePool,
          listenerExecutor,
          listener);
    }
  }

  /**
   * Metadata key for storing the {@link List} of video {@linkplain Effect effects} to apply on a
   * single media item, in {@linkplain Frame#getMetadata() frame metadata}.
   *
   * <p>The order of effect application is {@code KEY_ITEM_EFFECTS}, {@code KEY_COMPOSITOR_SETTINGS}
   * and then {@code KEY_COMPOSITION_EFFECTS}.
   */
  public static final String KEY_ITEM_EFFECTS = "KEY_ITEM_EFFECTS";

  /**
   * Metadata key for storing the {@link VideoCompositorSettings}, in {@linkplain
   * Frame#getMetadata() frame metadata}.
   *
   * <p>When using {@code CompositionPlayer} or {@code Transformer}, the value is {@code
   * Composition#videoCompositorSettings}.
   *
   * <p>The order of effect application is {@code KEY_ITEM_EFFECTS}, {@code KEY_COMPOSITOR_SETTINGS}
   * and then {@code KEY_COMPOSITION_EFFECTS}.
   */
  public static final String KEY_COMPOSITOR_SETTINGS = "KEY_COMPOSITOR_SETTINGS";

  /**
   * Metadata key for storing the {@link List} of video {@linkplain Effect effects} to apply on the
   * composited frames, in {@linkplain Frame#getMetadata() frame metadata}.
   *
   * <p>The order of effect application is {@code KEY_ITEM_EFFECTS}, {@code KEY_COMPOSITOR_SETTINGS}
   * and then {@code KEY_COMPOSITION_EFFECTS}.
   */
  public static final String KEY_COMPOSITION_EFFECTS = "KEY_COMPOSITION_EFFECTS";

  /**
   * Metadata key for storing the integer index to identify the source sequence in a {@code
   * Composition} from which an input frame comes, in {@linkplain Frame#getMetadata() frame
   * metadata}.
   */
  public static final String KEY_COMPOSITION_SEQUENCE_INDEX = "KEY_COMPOSITION_SEQUENCE_INDEX";

  /**
   * Metadata key for storing the frame discontinuity number, in {@link Frame#getMetadata()} and
   * {@link GlTextureFrame#getMetadata()}.
   *
   * <p>The number is an integer, incremented every time the player reports a discontinuity (for
   * example, when seeking).
   *
   * <p>The number is only mandatory for previewing use cases. It is used to trigger flushes in
   * {@link GlShaderProgram} implementations during discontinuities.
   */
  public static final String KEY_FRAME_DISCONTINUITY_NUMBER = "KEY_FRAME_DISCONTINUITY_NUMBER";

  private static final int DEFAULT_COMPOSITOR_CAPACITY = 2;

  private final Context context;
  private final GlObjectsProvider glObjectsProvider;
  private final ListeningExecutorService glExecutorService;
  private final HardwareBufferConverter hardwareBufferConverter;
  private final GlTextureFrameConsumer frameWriterGlTextureFrameConsumer;
  private final Executor listenerExecutor;
  private final Listener listener;
  private final Consumer<VideoFrameProcessingException> errorConsumer;
  // Accessed on the OpenGL thread.
  private final SparseArray<GlTextureFrameProcessorChain> preProcessingChains;
  private final GlTextureFrameAggregator frameAggregator;
  private final DefaultGlTextureFrameCompositingProcessor compositingProcessor;
  private final GlTextureFrameProcessorChain postProcessingChain;
  private final Set<GlTextureFrame> glTextureFramesQueuedDownstream;
  private final SparseArray<GlTextureFrame> convertedGlTextureFrames;
  private final Object lock;
  // Accessed only on GL thread. This field is to avoid creating a new set on queueing every time.
  private final Set<Integer> activeSequenceIndices;

  @GuardedBy("lock")
  @Nullable
  private List<AsyncFrame> pendingFrames;

  @GuardedBy("lock")
  private boolean shouldTriggerWakeupListener;

  @GuardedBy("lock")
  private boolean closed;

  @GuardedBy("lock")
  private boolean shouldSignalEos;

  private DefaultGlFrameProcessor(
      Context context,
      ListeningExecutorService glExecutorService,
      GlObjectsProvider glObjectsProvider,
      HardwareBufferConverter hardwareBufferConverter,
      GlTextureFrameConsumer frameWriterGlTextureFrameConsumer,
      CompositorGlProgram compositorGlProgram,
      TexturePool compositorTexturePool,
      Executor listenerExecutor,
      Listener listener) {
    this.context = context;
    this.glObjectsProvider = glObjectsProvider;
    this.glExecutorService = glExecutorService;
    this.hardwareBufferConverter = hardwareBufferConverter;
    this.frameWriterGlTextureFrameConsumer = frameWriterGlTextureFrameConsumer;
    this.listenerExecutor = listenerExecutor;
    this.listener = listener;
    this.errorConsumer = e -> listenerExecutor.execute(() -> listener.onError(e));
    this.glTextureFramesQueuedDownstream = Collections.newSetFromMap(new IdentityHashMap<>());
    this.convertedGlTextureFrames = new SparseArray<>();

    postProcessingChain =
        new GlTextureFrameProcessorChain(
            context,
            glObjectsProvider,
            glExecutorService,
            errorConsumer,
            frameWriterGlTextureFrameConsumer,
            KEY_COMPOSITION_EFFECTS);

    compositingProcessor =
        new DefaultGlTextureFrameCompositingProcessor(
            glObjectsProvider,
            compositorTexturePool,
            errorConsumer,
            compositorGlProgram,
            glExecutorService,
            postProcessingChain);

    frameAggregator =
        new GlTextureFrameAggregator(compositingProcessor, glExecutorService, errorConsumer);
    preProcessingChains = new SparseArray<>();
    lock = new Object();
    activeSequenceIndices = new HashSet<>();
  }

  @Override
  public boolean queue(List<AsyncFrame> frames) {
    checkArgument(!frames.isEmpty());
    synchronized (lock) {
      if (closed) {
        return false;
      }
      if (pendingFrames != null) {
        shouldTriggerWakeupListener = true;
        return false;
      }
      pendingFrames = frames;
    }

    submitToGlExecutor(
        () -> {
          try {
            synchronized (lock) {
              if (closed) {
                return null;
              }
            }
            activeSequenceIndices.clear();
            for (int i = 0; i < frames.size(); i++) {
              activeSequenceIndices.add(extractSequenceIndex(frames.get(i).frame));
            }
            // TODO: b/528240409 - Make config signal in-band.
            frameAggregator.configureSequenceIndices(activeSequenceIndices);

            convertToGlTextureFrames(frames);
            for (int i = 0; i < frames.size(); i++) {
              int sequenceIndex = extractSequenceIndex(frames.get(i).frame);
              queueOrRetry(sequenceIndex);
            }
          } catch (RuntimeException | VideoFrameProcessingException e) {
            handleError(e);
          }
          return null;
        });

    return true;
  }

  @Override
  public void signalEndOfStream() {
    submitToGlExecutor(
        () -> {
          synchronized (lock) {
            if (pendingFrames != null) {
              shouldSignalEos = true;
              return null;
            }
          }
          for (int i = 0; i < preProcessingChains.size(); i++) {
            preProcessingChains.valueAt(i).signalEndOfStream();
          }
          return null;
        });
  }

  @Override
  public void close() {
    synchronized (lock) {
      closed = true;
      pendingFrames = null;
    }
    submitToGlExecutor(
        () -> {
          ImmutableList.Builder<ThrowingRunnable> closeActions = ImmutableList.builder();
          closeActions.addAll(getReleaseUnqueuedFramesActions());
          closeActions.add(hardwareBufferConverter::close);
          for (int i = 0; i < preProcessingChains.size(); i++) {
            GlTextureFrameProcessorChain processorChain = preProcessingChains.valueAt(i);
            closeActions.add(processorChain::close);
          }
          closeActions
              .add(frameAggregator::close)
              .add(compositingProcessor::close)
              .add(postProcessingChain::close)
              .add(frameWriterGlTextureFrameConsumer::close);
          runAllAndAccumulateExceptions(closeActions.build().toArray(new ThrowingRunnable[0]));
          return null;
        });
  }

  private void submitToGlExecutor(Callable<Void> operation) {
    FrameProcessorUtils.submitToGlExecutor(operation, glExecutorService, errorConsumer);
  }

  /** The method runs on the GL thread. */
  private void queueOrRetry(int sequenceIndex) {
    synchronized (lock) {
      if (closed) {
        return;
      }
    }
    @Nullable GlTextureFrame glTextureFrame = convertedGlTextureFrames.get(sequenceIndex);
    if (glTextureFrame == null) {
      Log.d(TAG, "Converted GL frame not found for sequence=" + sequenceIndex);
      return;
    }
    try {
      @Nullable
      GlTextureFrameProcessorChain processingChain = preProcessingChains.get(sequenceIndex);
      if (processingChain == null) {
        processingChain =
            new GlTextureFrameProcessorChain(
                context,
                glObjectsProvider,
                glExecutorService,
                errorConsumer,
                frameAggregator.getInputConsumer(sequenceIndex),
                KEY_ITEM_EFFECTS);
        preProcessingChains.put(sequenceIndex, processingChain);
      }

      boolean queued =
          processingChain.queue(
              glTextureFrame,
              /* listenerExecutor= */ glExecutorService,
              /* wakeupListener= */ () -> queueOrRetry(sequenceIndex));

      if (queued) {
        // If not queued, the wakeupListener passed to ProcessingChain retries queuing the same
        // frame again.
        glTextureFramesQueuedDownstream.add(glTextureFrame);
        onFramesQueued();
      }
    } catch (VideoFrameProcessingException | RuntimeException e) {
      handleError(e);
    }
  }

  private void convertToGlTextureFrames(List<AsyncFrame> frames)
      throws VideoFrameProcessingException {
    for (int i = 0; i < frames.size(); i++) {
      AsyncFrame asyncFrame = frames.get(i);
      Frame frame = asyncFrame.frame;
      int sequenceIndex = extractSequenceIndex(frame);
      if (!waitAndCloseFence(asyncFrame)) {
        GLES20.glFinish();
      }
      convertedGlTextureFrames.put(
          sequenceIndex,
          checkNotNull(
              hardwareBufferConverter.convert(
                  (HardwareBufferFrame) frame, glExecutorService, listenerExecutor, listener)));
    }
  }

  private void handleError(Exception exception) {
    synchronized (lock) {
      pendingFrames = null;
    }
    runAllAndAccumulateExceptions(
        /* errorConsumer= */ exception::addSuppressed,
        getReleaseUnqueuedFramesActions().toArray(new ThrowingRunnable[0]));
    convertedGlTextureFrames.clear();
    glTextureFramesQueuedDownstream.clear();
    listenerExecutor.execute(() -> listener.onError(VideoFrameProcessingException.from(exception)));
  }

  private void onFramesQueued() {
    synchronized (lock) {
      if (checkNotNull(pendingFrames).size() != glTextureFramesQueuedDownstream.size()) {
        return;
      }
    }

    boolean signalEndOfStream = false;
    boolean invokeWakeup = false;
    // Downstream releases the GlTextureFrames upon completion.
    convertedGlTextureFrames.clear();
    glTextureFramesQueuedDownstream.clear();
    synchronized (lock) {
      pendingFrames = null;
      if (shouldTriggerWakeupListener) {
        shouldTriggerWakeupListener = false;
        invokeWakeup = true;
      }
      if (shouldSignalEos) {
        shouldSignalEos = false;
        signalEndOfStream = true;
      }
    }
    if (signalEndOfStream) {
      for (int i = 0; i < preProcessingChains.size(); i++) {
        preProcessingChains.valueAt(i).signalEndOfStream();
      }
    }
    if (invokeWakeup) {
      listenerExecutor.execute(listener::onWakeup);
    }
  }

  private static int extractSequenceIndex(Frame frame) {
    checkArgument(
        frame.getMetadata().containsKey(KEY_COMPOSITION_SEQUENCE_INDEX),
        "Frame metadata must contain KEY_COMPOSITION_SEQUENCE_INDEX");
    return (Integer) checkNotNull(frame.getMetadata().get(KEY_COMPOSITION_SEQUENCE_INDEX));
  }

  private ImmutableList<ThrowingRunnable> getReleaseUnqueuedFramesActions() {
    ImmutableList.Builder<ThrowingRunnable> actions = ImmutableList.builder();
    for (int i = 0; i < convertedGlTextureFrames.size(); i++) {
      GlTextureFrame glTextureFrame = convertedGlTextureFrames.valueAt(i);
      if (!glTextureFramesQueuedDownstream.contains(glTextureFrame)) {
        actions.add(() -> glTextureFrame.release(/* releaseFence= */ null));
      }
    }
    actions.add(
        () -> {
          convertedGlTextureFrames.clear();
          glTextureFramesQueuedDownstream.clear();
        });
    return actions.build();
  }
}
