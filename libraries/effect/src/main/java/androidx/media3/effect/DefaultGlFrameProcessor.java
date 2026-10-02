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

import static androidx.media3.common.ColorInfo.SDR_BT709_LIMITED;
import static androidx.media3.common.ColorInfo.isWideColorGamut;
import static androidx.media3.effect.FrameProcessorUtils.OPEN_GL_VERSION_3;
import static androidx.media3.effect.FrameProcessorUtils.releaseOpenGl;
import static androidx.media3.effect.FrameProcessorUtils.runAllAndAccumulateExceptions;
import static androidx.media3.effect.FrameProcessorUtils.setupOpenGl;
import static androidx.media3.effect.FrameProcessorUtils.shutdownGlExecutorService;
import static androidx.media3.effect.FrameProcessorUtils.waitAndCloseFence;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.opengl.GLES20;
import android.util.SparseArray;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ThrowingRunnable;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.FrameWriter;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A {@link FrameProcessor} implementation that executes a chain of {@link GlTextureFrameConsumer}s.
 */
@ExperimentalApi // TODO: b/505721737 Remove once FrameProcessor is production ready.
public final class DefaultGlFrameProcessor implements FrameProcessor {

  /** A {@link FrameProcessor.Factory} that creates {@link DefaultGlFrameProcessor} instances. */
  // TODO: b/531653682 - Remove @RequiresApi(26) once the pipeline supports API 24+ end to end.
  @RequiresApi(26)
  public static final class Factory implements FrameProcessor.Factory {

    /** A builder for {@link Factory} instances. */
    public static final class Builder {
      private final Context context;
      private final HardwareBufferJniWrapper hardwareBufferJniWrapper;
      @Nullable private GlObjectsProvider glObjectsProvider;
      @Nullable private ExecutorService executorService;
      @Nullable private FrameToGlTextureConverter.Factory frameToGlTextureConverterFactory;
      @Nullable private GlTextureFrameConsumer frameWriterGlTextureFrameConsumer;
      private GlTextureFrameCompositor.Factory glTextureFrameCompositorFactory;
      private boolean assumeSurfacelessContextExtensionSupported;

      /**
       * Creates an instance.
       *
       * @param context The {@link Context}.
       * @param hardwareBufferJniWrapper The {@link HardwareBufferJniWrapper}, for example {@code
       *     HardwareBufferJni.INSTANCE}.
       */
      public Builder(Context context, HardwareBufferJniWrapper hardwareBufferJniWrapper) {
        this.context = context.getApplicationContext();
        this.hardwareBufferJniWrapper = hardwareBufferJniWrapper;
        glTextureFrameCompositorFactory =
            new DefaultGlTextureFrameCompositor.Factory(
                new DefaultCompositorGlProgram.Factory(this.context));
      }

      /**
       * Sets the {@link GlObjectsProvider} and the {@link ExecutorService} to execute OpenGL
       * commands on.
       *
       * <p>If not set, each created {@link DefaultGlFrameProcessor} uses a new {@link
       * DefaultGlObjectsProvider} and a new single-thread executor, and releases both when it's
       * {@linkplain FrameProcessor#close closed}.
       *
       * <p>If set, the caller owns both. After all the {@link DefaultGlFrameProcessor} instances
       * created by the built {@link Factory} have been closed, the caller must release the {@link
       * GlObjectsProvider} on the {@link ExecutorService}'s thread, and then {@linkplain
       * ExecutorService#shutdown shut down} the {@link ExecutorService}.
       *
       * @param glObjectsProvider The {@link GlObjectsProvider}.
       * @param executorService The {@link ExecutorService}.
       * @return This builder.
       */
      @CanIgnoreReturnValue
      public Builder setGlObjectsProviderAndExecutorService(
          GlObjectsProvider glObjectsProvider, ExecutorService executorService) {
        this.glObjectsProvider = glObjectsProvider;
        this.executorService = executorService;
        return this;
      }

      /**
       * Sets the {@link FrameToGlTextureConverter.Factory}.
       *
       * @param frameToGlTextureConverterFactory The {@link FrameToGlTextureConverter.Factory}.
       * @return This builder.
       */
      @VisibleForTesting
      @CanIgnoreReturnValue
      /* package */ Builder setFrameToGlTextureConverterFactory(
          FrameToGlTextureConverter.Factory frameToGlTextureConverterFactory) {
        this.frameToGlTextureConverterFactory = frameToGlTextureConverterFactory;
        return this;
      }

      /**
       * Sets the {@link GlTextureFrameConsumer} for writing frames.
       *
       * @param frameWriterGlTextureFrameConsumer The {@link GlTextureFrameConsumer}.
       * @return This builder.
       */
      @VisibleForTesting
      @CanIgnoreReturnValue
      /* package */ Builder setFrameWriterGlTextureFrameConsumer(
          GlTextureFrameConsumer frameWriterGlTextureFrameConsumer) {
        this.frameWriterGlTextureFrameConsumer = frameWriterGlTextureFrameConsumer;
        return this;
      }

      /**
       * Sets the {@link GlTextureFrameCompositor.Factory}.
       *
       * @param glTextureFrameCompositorFactory The {@link GlTextureFrameCompositor.Factory}.
       * @return This builder.
       */
      @VisibleForTesting
      @CanIgnoreReturnValue
      /* package */ Builder setGlTextureFrameCompositorFactory(
          GlTextureFrameCompositor.Factory glTextureFrameCompositorFactory) {
        this.glTextureFrameCompositorFactory = glTextureFrameCompositorFactory;
        return this;
      }

      /**
       * Sets whether to assume the surfaceless context extension is supported, instead of detecting
       * support at runtime.
       *
       * <p>The default value is {@code false}, meaning support is detected at runtime.
       *
       * @param assumeSurfacelessContextExtensionSupported Whether to assume the extension is
       *     supported.
       * @return This builder.
       */
      @VisibleForTesting
      @CanIgnoreReturnValue
      /* package */ Builder setAssumeSurfacelessContextExtensionSupported(
          boolean assumeSurfacelessContextExtensionSupported) {
        this.assumeSurfacelessContextExtensionSupported =
            assumeSurfacelessContextExtensionSupported;
        return this;
      }

      /** Builds a {@link Factory} instance. */
      public Factory build() {
        if (frameToGlTextureConverterFactory == null) {
          frameToGlTextureConverterFactory =
              (outputColorInfo, errorConsumer) ->
                  new HardwareBufferToGlTextureConverter(
                      context, hardwareBufferJniWrapper, outputColorInfo, errorConsumer);
        }
        return new Factory(this);
      }
    }

    private static final String THREAD_NAME = "Effect:DefaultGlFrameProcessor:GlThread";

    private final Context context;
    @Nullable private final GlObjectsProvider glObjectsProvider;
    @Nullable private final ExecutorService glExecutorService;
    private final FrameToGlTextureConverter.Factory frameToGlTextureConverterFactory;
    private final HardwareBufferJniWrapper hardwareBufferJniWrapper;
    @Nullable private final GlTextureFrameConsumer frameWriterGlTextureFrameConsumer;
    private final GlTextureFrameCompositor.Factory glTextureFrameCompositorFactory;
    // Whether to assume the surfaceless context extension is supported instead of detecting support
    // at runtime. Only set to true in tests.
    private final boolean assumeSurfacelessContextExtensionSupported;
    // TODO(b/545584738): Allow setting a working color space.
    @Nullable private ColorInfo workingColorSpace;

    private Factory(Builder builder) {
      context = builder.context;
      glObjectsProvider = builder.glObjectsProvider;
      glExecutorService = builder.executorService;
      hardwareBufferJniWrapper = builder.hardwareBufferJniWrapper;
      frameToGlTextureConverterFactory = checkNotNull(builder.frameToGlTextureConverterFactory);
      frameWriterGlTextureFrameConsumer = builder.frameWriterGlTextureFrameConsumer;
      glTextureFrameCompositorFactory = builder.glTextureFrameCompositorFactory;
      assumeSurfacelessContextExtensionSupported =
          builder.assumeSurfacelessContextExtensionSupported;
      workingColorSpace = null;
    }

    @Override
    public DefaultGlFrameProcessor create(
        FrameWriter output, Executor listenerExecutor, Listener listener) {
      GlTextureFrameConsumer frameWriterGlTextureFrameConsumer =
          this.frameWriterGlTextureFrameConsumer != null
              ? this.frameWriterGlTextureFrameConsumer
              : new FrameWriterGlTextureFrameConsumer(context, output, hardwareBufferJniWrapper);
      boolean shouldReleaseGlResources = this.glExecutorService == null;
      GlObjectsProvider glObjectsProvider =
          this.glObjectsProvider != null ? this.glObjectsProvider : new DefaultGlObjectsProvider();
      ExecutorService glExecutorService =
          this.glExecutorService != null
              ? this.glExecutorService
              : createDefaultGlExecutorService();
      return new DefaultGlFrameProcessor(
          context,
          listeningDecorator(glExecutorService),
          glObjectsProvider,
          frameToGlTextureConverterFactory,
          frameWriterGlTextureFrameConsumer,
          glTextureFrameCompositorFactory,
          listenerExecutor,
          listener,
          workingColorSpace,
          shouldReleaseGlResources,
          assumeSurfacelessContextExtensionSupported);
    }

    /**
     * Returns a single-thread {@link ExecutorService} that drops, rather than rejects, tasks
     * submitted after it's shut down.
     *
     * <p>Components such as the {@link FrameWriter} can post tasks, for example wakeups, to the GL
     * executor after the processor that owns it has been {@linkplain FrameProcessor#close closed}.
     */
    private static ExecutorService createDefaultGlExecutorService() {
      return new ThreadPoolExecutor(
          /* corePoolSize= */ 1,
          /* maximumPoolSize= */ 1,
          /* keepAliveTime= */ 0,
          /* unit= */ MILLISECONDS,
          /* workQueue= */ new LinkedBlockingQueue<>(),
          /* threadFactory= */ runnable -> new Thread(runnable, THREAD_NAME),
          /* handler= */ (runnable, executor) ->
              Log.w(TAG, "Dropping GL task submitted after close"));
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

  private static final String TAG = "GlFrameProcessor";
  private static final long RELEASE_TIMEOUT_MS = 1_000;

  /** An SDR color space with BT.709 / sRGB color primaries, and linear transfer function. */
  /* package */ static final ColorInfo BT709_LINEAR =
      new ColorInfo.Builder()
          .setColorSpace(C.COLOR_SPACE_BT709)
          .setColorTransfer(C.COLOR_TRANSFER_LINEAR)
          .build();

  /** An SDR color space with BT.709 / sRGB color primaries, and sRGB transfer function. */
  /* package */ static final ColorInfo BT709_SRGB =
      new ColorInfo.Builder()
          .setColorSpace(C.COLOR_SPACE_BT709)
          .setColorTransfer(C.COLOR_TRANSFER_SRGB)
          .build();

  /**
   * An HDR color space with BT.2020 color primaries, and linear transfer function.
   *
   * <p>Values are display-referred linear optical light anchored on the ITU-R BT.2408 203-nit
   * diffuse white reference, so {@code 1.0} represents diffuse white, the 1,000-nit HLG reference
   * peak evaluates to {@code ~4.9224}, and the 10,000-nit PQ peak evaluates to {@code ~49.2242}.
   *
   * <p>When input is HLG, HLG scene-referred electrical values are converted to display light using
   * the ITU-R BT.2100 HLG EOTF ({@code OOTF_1.2(OETF^-1(E))}) and scaled onto the diffuse white
   * anchor. When input is PQ, PQ electrical values are linearized using the SMPTE ST 2084 EOTF and
   * scaled onto the diffuse white anchor.
   */
  /* package */ static final ColorInfo BT2020_LINEAR =
      new ColorInfo.Builder()
          .setColorSpace(C.COLOR_SPACE_BT2020)
          .setColorTransfer(C.COLOR_TRANSFER_LINEAR)
          .build();

  /**
   * An HDR color space with BT.2020 color primaries, and HLG transfer function.
   *
   * <p>When input is PQ, PQ electrical values are converted to optical linear light in nits and
   * normalized against the 1,000-nit HLG reference display peak. The resulting display light is
   * converted to scene light via inverse HLG OOTF (system gamma 1.2) and encoded into an HLG
   * electrical signal using the BT.2100 HLG OETF.
   *
   * <p>HLG signal cannot represent scene light above 1.0 (either from display light above the
   * 1,000-nit reference peak or saturated primaries where inverse OOTF boosts channel values above
   * 1.0), so scene light is scaled down to fit in {@code [0.0, 1.0]} after the inverse HLG OOTF and
   * before the OETF; all channels are scaled equally to preserve chromaticity.
   *
   * <p>The values are always scene-referred.
   */
  /* package */ static final ColorInfo BT2020_HLG =
      new ColorInfo.Builder()
          .setColorSpace(C.COLOR_SPACE_BT2020)
          .setColorTransfer(C.COLOR_TRANSFER_HLG)
          .build();

  private final Context context;
  private final GlObjectsProvider glObjectsProvider;
  private final ListeningExecutorService glExecutorService;
  private final FrameToGlTextureConverter.Factory frameToGlTextureConverterFactory;
  private final GlTextureFrameCompositor.Factory glTextureFrameCompositorFactory;
  private final GlTextureFrameConsumer frameWriterGlTextureFrameConsumer;
  private final Executor listenerExecutor;
  private final Listener listener;
  private final Consumer<VideoFrameProcessingException> errorConsumer;
  // Accessed on the OpenGL thread.
  private final SparseArray<GlTextureFrameProcessorChain> preProcessingChains;
  private final Set<GlTextureFrame> glTextureFramesQueuedDownstream;
  private final SparseArray<GlTextureFrame> convertedGlTextureFrames;
  private final Object lock;
  // Accessed only on GL thread. This field is to avoid creating a new set on queueing every time.
  private final Set<Integer> activeSequenceIndices;
  private final boolean shouldReleaseGlResources;
  private final boolean assumeSurfacelessContextExtensionSupported;

  private @MonotonicNonNull GlTextureFrameAggregator frameAggregator;
  private @MonotonicNonNull GlTextureFrameCompositor compositingProcessor;
  private @MonotonicNonNull GlTextureFrameProcessorChain postProcessingChain;
  private @MonotonicNonNull FrameToGlTextureConverter frameToGlTextureConverter;

  @GuardedBy("lock")
  @Nullable
  private List<AsyncFrame> pendingFrames;

  @GuardedBy("lock")
  private boolean shouldTriggerWakeupListener;

  @GuardedBy("lock")
  private boolean closed;

  @GuardedBy("lock")
  private boolean shouldSignalEos;

  // Accessed on the GL thread.
  // A null color space means it's unset; and it's inferred from the input color.
  @Nullable private ColorInfo workingColorSpace;
  private boolean isPipelineInitialized;
  private boolean isGlSetup;
  private boolean isHdrSupported;

  private DefaultGlFrameProcessor(
      Context context,
      ListeningExecutorService glExecutorService,
      GlObjectsProvider glObjectsProvider,
      FrameToGlTextureConverter.Factory frameToGlTextureConverterFactory,
      GlTextureFrameConsumer frameWriterGlTextureFrameConsumer,
      GlTextureFrameCompositor.Factory glTextureFrameCompositorFactory,
      Executor listenerExecutor,
      Listener listener,
      @Nullable ColorInfo workingColorSpace,
      boolean shouldReleaseGlResources,
      boolean assumeSurfacelessContextExtensionSupported) {
    this.context = context;
    this.glObjectsProvider = glObjectsProvider;
    this.glExecutorService = glExecutorService;
    this.frameToGlTextureConverterFactory = frameToGlTextureConverterFactory;
    this.frameWriterGlTextureFrameConsumer = frameWriterGlTextureFrameConsumer;
    this.glTextureFrameCompositorFactory = glTextureFrameCompositorFactory;
    this.listenerExecutor = listenerExecutor;
    this.listener = listener;
    this.workingColorSpace = workingColorSpace;
    this.shouldReleaseGlResources = shouldReleaseGlResources;
    this.assumeSurfacelessContextExtensionSupported = assumeSurfacelessContextExtensionSupported;
    this.errorConsumer = e -> listenerExecutor.execute(() -> listener.onError(e));
    this.glTextureFramesQueuedDownstream = Collections.newSetFromMap(new IdentityHashMap<>());
    this.convertedGlTextureFrames = new SparseArray<>();

    preProcessingChains = new SparseArray<>();
    lock = new Object();
    activeSequenceIndices = new HashSet<>();
    isPipelineInitialized = false;
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
            if (!isGlSetup) {
              int openGlVersion;
              try {
                openGlVersion =
                    assumeSurfacelessContextExtensionSupported
                        ? setupOpenGl(
                            glObjectsProvider, /* isSurfacelessContextExtensionSupported= */ true)
                        : setupOpenGl(glObjectsProvider);
              } catch (GlException e) {
                isGlSetup = true;
                throw VideoFrameProcessingException.from(e);
              }
              isHdrSupported = openGlVersion == OPEN_GL_VERSION_3;
              isGlSetup = true;
            }
            if (!isPipelineInitialized) {
              boolean isAnyInputHdr = false;
              for (int i = 0; i < frames.size(); i++) {
                Format inputFormat = frames.get(i).frame.getFormat();
                if (workingColorSpace == null) {
                  workingColorSpace = resolveWorkingColorspace(inputFormat);
                }
                isAnyInputHdr |= isWideColorGamut(inputFormat.colorInfo);
              }
              if (isAnyInputHdr || isWideColorGamut(workingColorSpace)) {
                checkState(
                    isHdrSupported,
                    "OpenGL ES3 and 10 bit context support required for HDR inputs");
              }
              initializePipeline();
            }
            activeSequenceIndices.clear();
            for (int i = 0; i < frames.size(); i++) {
              activeSequenceIndices.add(extractSequenceIndex(frames.get(i).frame));
            }
            // TODO: b/528240409 - Make config signal in-band.
            checkNotNull(frameAggregator).configureSequenceIndices(activeSequenceIndices);

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
      if (closed) {
        return;
      }
      closed = true;
      pendingFrames = null;
    }
    Callable<Void> releaseTask =
        () -> {
          ImmutableList.Builder<ThrowingRunnable<?>> closeActions = ImmutableList.builder();
          closeActions.addAll(getReleaseUnqueuedFramesActions());
          if (frameToGlTextureConverter != null) {
            closeActions.add(frameToGlTextureConverter::close);
          }
          for (int i = 0; i < preProcessingChains.size(); i++) {
            GlTextureFrameProcessorChain processorChain = preProcessingChains.valueAt(i);
            closeActions.add(processorChain::close);
          }
          if (frameAggregator != null) {
            closeActions.add(frameAggregator::close);
          }
          if (compositingProcessor != null) {
            closeActions.add(compositingProcessor::close);
          }
          if (postProcessingChain != null) {
            closeActions.add(postProcessingChain::close);
          }
          closeActions.add(frameWriterGlTextureFrameConsumer::close);
          if (isGlSetup && shouldReleaseGlResources) {
            closeActions.add(() -> releaseOpenGl(glObjectsProvider));
          }
          runAllAndAccumulateExceptions(closeActions.build().toArray(new ThrowingRunnable<?>[0]));
          return null;
        };
    try {
      Object unused = glExecutorService.submit(releaseTask).get(RELEASE_TIMEOUT_MS, MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      errorConsumer.accept(VideoFrameProcessingException.from(e));
    } catch (ExecutionException | TimeoutException e) {
      errorConsumer.accept(VideoFrameProcessingException.from(e));
    } finally {
      if (shouldReleaseGlResources) {
        shutdownGlExecutorService(glExecutorService);
      }
    }
  }

  private void initializePipeline() {
    ColorInfo workingColorSpace = checkNotNull(this.workingColorSpace);
    frameToGlTextureConverter =
        frameToGlTextureConverterFactory.create(workingColorSpace, errorConsumer);
    postProcessingChain =
        new GlTextureFrameProcessorChain(
            context,
            glObjectsProvider,
            glExecutorService,
            errorConsumer,
            frameWriterGlTextureFrameConsumer,
            KEY_COMPOSITION_EFFECTS,
            workingColorSpace);
    compositingProcessor =
        glTextureFrameCompositorFactory.create(
            glObjectsProvider,
            workingColorSpace,
            errorConsumer,
            glExecutorService,
            postProcessingChain);
    frameAggregator =
        new GlTextureFrameAggregator(compositingProcessor, glExecutorService, errorConsumer);
    isPipelineInitialized = true;
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
                checkNotNull(frameAggregator).getInputConsumer(sequenceIndex),
                KEY_ITEM_EFFECTS,
                checkNotNull(this.workingColorSpace));
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
      @Nullable
      GlTextureFrame convertedFrame =
          checkNotNull(frameToGlTextureConverter)
              .convert(frame, glExecutorService, listenerExecutor, listener);
      if (convertedFrame == null) {
        // The converter was closed while this batch was being converted.
        Log.w(TAG, "Converter closed, dropping frame for sequence = " + sequenceIndex);
        continue;
      }
      convertedGlTextureFrames.put(sequenceIndex, convertedFrame);
    }
  }

  private void handleError(Exception exception) {
    synchronized (lock) {
      if (closed) {
        return;
      }
      pendingFrames = null;
    }
    runAllAndAccumulateExceptions(
        /* errorConsumer= */ exception::addSuppressed,
        getReleaseUnqueuedFramesActions().toArray(new ThrowingRunnable<?>[0]));
    convertedGlTextureFrames.clear();
    glTextureFramesQueuedDownstream.clear();
    listenerExecutor.execute(() -> listener.onError(VideoFrameProcessingException.from(exception)));
  }

  private void onFramesQueued() {
    synchronized (lock) {
      if (closed
          || pendingFrames == null
          || pendingFrames.size() != glTextureFramesQueuedDownstream.size()) {
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

  private ImmutableList<ThrowingRunnable<?>> getReleaseUnqueuedFramesActions() {
    ImmutableList.Builder<ThrowingRunnable<?>> actions = ImmutableList.builder();
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

  private static ColorInfo resolveWorkingColorspace(Format format) {
    ColorInfo inputColorInfo = format.colorInfo == null ? SDR_BT709_LIMITED : format.colorInfo;
    if (Objects.equals(format.sampleMimeType, MimeTypes.IMAGE_JPEG_R)
        && inputColorInfo.colorTransfer == C.COLOR_TRANSFER_SRGB) {
      return BT2020_HLG;
    }
    if (isWideColorGamut(inputColorInfo)) {
      // Process HDR in linear light. FrameWriterGlTextureFrameConsumer converts frames to an
      // electrical color space before writing them, unless an effect already did.
      return BT2020_LINEAR;
    } else {
      // All SDR input are treated as sRGB.
      // TODO(b/545591224): Allow converting outputting to other gamut, for example BT.601.
      return BT709_SRGB;
    }
  }
}
