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

import android.content.Context;
import android.hardware.HardwareBuffer;
import android.opengl.GLES20;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.Effect;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.HardwareBufferFrame;
import androidx.media3.effect.FrameProcessorUtils.ThrowingRunnable;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

/**
 * A {@link FrameProcessor} implementation that executes a chain of {@link GlTextureFrameConsumer}s.
 */
@ExperimentalApi // TODO: b/505721737 Remove once FrameProcessor is production ready.
@RequiresApi(26)
public final class DefaultGlFrameProcessor implements FrameProcessor {

  /** Converts from {@link HardwareBuffer} to {@link GlTextureFrame}. */
  interface HardwareBufferConverter extends AutoCloseable {

    // TODO: b/517424999 - Unify the listeners to follow the same pattern as FrameProcessor.
    GlTextureFrame convert(
        HardwareBufferFrame hardwareBufferFrame,
        Executor glExecutor,
        Executor listenerExecutor,
        FrameProcessor.Listener listener)
        throws VideoFrameProcessingException;

    /**
     * Releases the resources for a converted {@link HardwareBufferFrame}.
     *
     * <p>Don't call this method if the {@linkplain #convert converted} {@link GlTextureFrame} is
     * accepted by a downstream {@link GlTextureFrameConsumer}.
     *
     * <p>This releases associated resources without notifying the {@link
     * FrameProcessor.Listener#onFrameProcessed} that was passed in via {@link #convert}.
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
    private final ListeningExecutorService glExecutorService;
    @Nullable private final HardwareBufferConverter hardwareBufferConverter;

    @Nullable private final HardwareBufferJniWrapper hardwareBufferJniWrapper;
    @Nullable private final GlTextureFrameConsumer frameWriterGlTextureFrameConsumer;

    /**
     * Creates an instance.
     *
     * <p>The caller is responsible for setting up OpenGL resources and releasing them after
     * {@linkplain FrameProcessor#close closing} the built {@link DefaultGlFrameProcessor}. The
     * caller should also shut down the {@link ListeningExecutorService glExecutorService}.
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
        ListeningExecutorService glExecutorService,
        HardwareBufferConverter hardwareBufferConverter,
        GlTextureFrameConsumer frameWriterGlTextureFrameConsumer) {
      this.context = context;
      this.glObjectsProvider = new DefaultGlObjectsProvider();
      this.glExecutorService = glExecutorService;
      this.hardwareBufferConverter = hardwareBufferConverter;
      this.frameWriterGlTextureFrameConsumer = frameWriterGlTextureFrameConsumer;
      hardwareBufferJniWrapper = null;
    }

    @Override
    public DefaultGlFrameProcessor create(
        FrameWriter output, Executor listenerExecutor, FrameProcessor.Listener listener) {
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
                e -> listenerExecutor.execute(() -> listener.onError(e)));
      }
      return new DefaultGlFrameProcessor(
          context,
          glExecutorService,
          glObjectsProvider,
          checkNotNull(hardwareBufferConverter),
          checkNotNull(frameWriterGlTextureFrameConsumer),
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

  private final ListeningExecutorService glExecutorService;
  private final HardwareBufferConverter hardwareBufferConverter;
  private final GlTextureFrameConsumer frameWriterGlTextureFrameConsumer;
  private final Executor listenerExecutor;
  private final FrameProcessor.Listener listener;
  private final Consumer<VideoFrameProcessingException> errorConsumer;
  // Accessed on the OpenGL thread.
  private final GlTextureFrameProcessorChain effectProcessorChain;
  private final Object lock;

  @GuardedBy("lock")
  @Nullable
  private List<AsyncFrame> pendingFrames;

  @GuardedBy("lock")
  private boolean pendingWakingupUpstream;

  @GuardedBy("lock")
  private boolean released;

  @GuardedBy("lock")
  private boolean signalEndOfStreamDeferred;

  // Accessed on the OpenGL thread.
  private boolean unsupportedFramesReleased;
  @Nullable private GlTextureFrame convertedGlTextureFrame;

  private DefaultGlFrameProcessor(
      Context context,
      ListeningExecutorService glExecutorService,
      GlObjectsProvider glObjectsProvider,
      HardwareBufferConverter hardwareBufferConverter,
      GlTextureFrameConsumer frameWriterGlTextureFrameConsumer,
      Executor listenerExecutor,
      FrameProcessor.Listener listener) {
    this.glExecutorService = glExecutorService;
    this.hardwareBufferConverter = hardwareBufferConverter;
    this.frameWriterGlTextureFrameConsumer = frameWriterGlTextureFrameConsumer;
    this.listenerExecutor = listenerExecutor;
    this.listener = listener;
    this.errorConsumer = e -> listenerExecutor.execute(() -> listener.onError(e));
    effectProcessorChain =
        new GlTextureFrameProcessorChain(
            context,
            glObjectsProvider,
            glExecutorService,
            errorConsumer,
            frameWriterGlTextureFrameConsumer);
    lock = new Object();
  }

  @Override
  public boolean queue(List<AsyncFrame> frames) {
    checkArgument(!frames.isEmpty());
    synchronized (lock) {
      if (released) {
        return false;
      }
      if (pendingFrames != null) {
        pendingWakingupUpstream = true;
        return false;
      }
      pendingFrames = frames;
    }

    submitToGlExecutor(
        () -> {
          maybeQueueToDownstream();
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
              signalEndOfStreamDeferred = true;
              return null;
            }
          }
          effectProcessorChain.signalEndOfStream();
          return null;
        });
  }

  @Override
  public void close() {
    @Nullable List<AsyncFrame> framesToRelease;
    synchronized (lock) {
      released = true;
      framesToRelease = pendingFrames;
      pendingFrames = null;
    }
    submitToGlExecutor(
        () -> {
          ImmutableList.Builder<ThrowingRunnable> releaseActions = ImmutableList.builder();
          if (framesToRelease != null) {
            for (int i = 0; i < framesToRelease.size(); i++) {
              AsyncFrame asyncFrame = framesToRelease.get(i);
              releaseActions.add(
                  () ->
                      hardwareBufferConverter.releaseGlResources(
                          (HardwareBufferFrame) asyncFrame.frame));
            }
          }
          @Nullable GlTextureFrame convertedGlTextureFrame = this.convertedGlTextureFrame;
          if (convertedGlTextureFrame != null) {
            releaseActions.add(() -> convertedGlTextureFrame.release(null));
          }

          releaseActions
              .add(hardwareBufferConverter::close)
              .add(effectProcessorChain::close)
              .add(frameWriterGlTextureFrameConsumer::close);
          runAllAndAccumulateExceptions(releaseActions.build().toArray(new ThrowingRunnable[0]));
          return null;
        });
  }

  private void submitToGlExecutor(Callable<Void> operation) {
    FrameProcessorUtils.submitToGlExecutor(operation, glExecutorService, errorConsumer);
  }

  /** The method runs on the GL thread. */
  private void maybeQueueToDownstream() {
    @Nullable List<AsyncFrame> frames;
    synchronized (lock) {
      if (released) {
        return;
      }
      frames = pendingFrames;
    }

    if (frames == null) {
      return;
    }

    try {
      if (!unsupportedFramesReleased) {
        releaseUnsupportedFrames(frames);
        unsupportedFramesReleased = true;
      }

      if (convertedGlTextureFrame == null) {
        AsyncFrame asyncFrame = frames.get(0);
        Frame frame = asyncFrame.frame;
        HardwareBufferFrame hardwareBufferFrame = (HardwareBufferFrame) frame;

        if (!waitAndCloseFence(asyncFrame)) {
          GLES20.glFinish();
        }

        effectProcessorChain.configure(
            new ImmutableList.Builder<Effect>()
                .addAll(extractEffects(frame, KEY_ITEM_EFFECTS))
                .addAll(extractEffects(frame, KEY_COMPOSITION_EFFECTS))
                .build());

        convertedGlTextureFrame =
            hardwareBufferConverter.convert(
                hardwareBufferFrame, glExecutorService, listenerExecutor, listener);
      }

      boolean queued =
          effectProcessorChain.queue(
              checkNotNull(convertedGlTextureFrame),
              /* listenerExecutor= */ glExecutorService,
              /* wakeupListener= */ this::maybeQueueToDownstream);

      if (queued) {
        onFrameQueued();
      }
    } catch (VideoFrameProcessingException | RuntimeException e) {
      synchronized (lock) {
        pendingFrames = null;
      }
      unsupportedFramesReleased = false;
      if (convertedGlTextureFrame != null) {
        try {
          hardwareBufferConverter.releaseGlResources((HardwareBufferFrame) frames.get(0).frame);
        } catch (Exception suppressedException) {
          e.addSuppressed(suppressedException);
        }
        convertedGlTextureFrame = null;
      }
      listenerExecutor.execute(() -> listener.onError(VideoFrameProcessingException.from(e)));
    }
  }

  private void onFrameQueued() {
    boolean invokeWakeup = false;
    boolean signalEndOfStream = false;
    convertedGlTextureFrame = null;
    synchronized (lock) {
      pendingFrames = null;
      if (pendingWakingupUpstream) {
        pendingWakingupUpstream = false;
        invokeWakeup = true;
      }
      if (signalEndOfStreamDeferred) {
        signalEndOfStreamDeferred = false;
        signalEndOfStream = true;
      }
    }
    unsupportedFramesReleased = false;
    if (signalEndOfStream) {
      effectProcessorChain.signalEndOfStream();
    }
    if (invokeWakeup) {
      listenerExecutor.execute(listener::onWakeup);
    }
  }

  /**
   * Releases the GL resources of all input frames in the batch except the first one.
   *
   * <p>This is temporary until multi-sequence support is integrated.
   */
  private void releaseUnsupportedFrames(List<AsyncFrame> frames) {
    for (int i = 1; i < frames.size(); i++) {
      // TODO: b/337107769 - Support multiple input frames.
      AsyncFrame asyncFrame = frames.get(i);
      if (!waitAndCloseFence(asyncFrame)) {
        GLES20.glFinish();
      }
      listenerExecutor.execute(
          () -> listener.onFrameProcessed(asyncFrame.frame, /* onCompleteFence= */ null));
    }
  }

  private static ImmutableList<Effect> extractEffects(Frame frame, String effectKey) {
    if (!frame.getMetadata().containsKey(effectKey)) {
      return ImmutableList.of();
    }
    @SuppressWarnings("unchecked") // Metadata values are Objects.
    List<Effect> castEffects = checkNotNull((List<Effect>) frame.getMetadata().get(effectKey));
    return ImmutableList.copyOf(castEffects);
  }
}
