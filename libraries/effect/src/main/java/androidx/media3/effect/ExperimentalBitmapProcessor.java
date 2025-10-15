/*
 * Copyright 2025 The Android Open Source Project
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

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;
import static androidx.media3.common.util.GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888;
import static androidx.media3.common.util.GlUtil.getDefaultEglDisplay;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.concurrent.futures.CallbackToFutureAdapter.Completer;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Applies {@link ImmutableList<Effect> effects} to a {@link Bitmap}.
 *
 * <p>This class is experimental and will be renamed or removed in a future release.
 *
 * <p>Only supports SDR input and output.
 *
 * <p>If a call to {@link #setEffectsAsync} fails or is cancelled, subsequent calls to {@link
 * #applyEffectsAsync} will be failed or cancelled with the same result until {@link
 * #setEffectsAsync} is called again.
 *
 * <p>If a call to {@link #applyEffectsAsync} fails, subsequent calls {@link #applyEffectsAsync}
 * fail with the same error until {@link #setEffectsAsync} is called again.
 *
 * <p>Cancellations to {@link #applyEffectsAsync} calls are not propagated up or downstream.
 *
 * <p><b>This class is NOT thread-safe.</b> All public methods must be called from the same thread
 * that instantiated the instance. This is verified by the implementation.
 */
@UnstableApi
@RestrictTo(LIBRARY_GROUP)
public final class ExperimentalBitmapProcessor {

  /** A builder for {@link ExperimentalBitmapProcessor} instances. */
  public static final class Builder {
    private final Context context;
    private Supplier<GlObjectsProvider> glObjectsProviderSupplier;

    /**
     * Creates a new {@link Builder}.
     *
     * @param context The {@link Context}.
     */
    public Builder(Context context) {
      this.context = context;
      this.glObjectsProviderSupplier = DefaultGlObjectsProvider::new;
    }

    /**
     * Sets the {@link GlObjectsProvider}
     *
     * <p>By default, the {@link DefaultGlObjectsProvider} is used.
     */
    @CanIgnoreReturnValue
    public Builder setGlObjectsProvider(GlObjectsProvider glObjectsProvider) {
      checkNotNull(glObjectsProvider);
      this.glObjectsProviderSupplier = () -> glObjectsProvider;
      return this;
    }

    /** Builds a new {@link ExperimentalBitmapProcessor} instance. */
    public ExperimentalBitmapProcessor build() {
      return new ExperimentalBitmapProcessor(this);
    }
  }

  private static final String GL_THREAD_NAME = "Effect:BitmapProcessor:GlThread";

  public final ListeningExecutorService glThreadExecutorService;
  private final GlObjectsProvider glObjectsProvider;
  private final GlTextureFrameProcessorFactory frameProcessorFactory;
  private final Thread callingThread;

  /** Queue of {@link Completer<Bitmap>}s corresponding to the Bitmaps queued to the pipeline. */
  private final Queue<Completer<Bitmap>> pendingCompleters;

  /** All {@link #setEffectsAsync} and {@link #applyEffectsAsync} that have not yet completed. */
  private final Map<Integer, ListenableFuture<Void>> activeFutures;

  private ListenableFuture<Void> lastSetEffectsFuture;
  private ListenableFuture<Void> lastOperationFuture;
  private @MonotonicNonNull ListenableFuture<Void> releaseFuture;
  private @MonotonicNonNull Pipeline pipeline;
  private @MonotonicNonNull EGLDisplay eglDisplay;
  private volatile boolean releaseInitiated;
  private boolean isConfigured;
  private int nextActiveFutureId;
  @Nullable private Exception pipelineException;

  /** Creates a new instance. */
  private ExperimentalBitmapProcessor(Builder builder) {
    this.glThreadExecutorService =
        MoreExecutors.listeningDecorator(Util.newSingleThreadExecutor(GL_THREAD_NAME));
    this.glObjectsProvider = builder.glObjectsProviderSupplier.get();
    this.frameProcessorFactory =
        new GlTextureFrameProcessorFactory(
            builder.context, glThreadExecutorService, glObjectsProvider);
    this.lastSetEffectsFuture = immediateFailedFuture(new IllegalStateException());
    this.lastOperationFuture = lastSetEffectsFuture;
    this.callingThread = Thread.currentThread();
    this.pendingCompleters = new ConcurrentLinkedQueue<>();
    this.activeFutures = new ConcurrentHashMap<>();
  }

  /**
   * Configures the {@link ExperimentalBitmapProcessor} with the given {@linkplain List<Effect>
   * effects}.
   *
   * <p>This method will wait for any previously scheduled calls to complete before reconfiguring
   * the pipeline.
   *
   * <p>If a call to this method fails, all subsequent {@link #applyEffectsAsync} calls will also
   * fail until this method is called again to reconfigure the pipeline.
   *
   * @param effects The {@linkplain List<Effect> effects} to apply in subsequent {@link
   *     #applyEffectsAsync} calls.
   * @throws IllegalStateException If called after {@link #releaseAsync}.
   */
  public ListenableFuture<Void> setEffectsAsync(List<Effect> effects) {
    verifyCallingThread();
    if (releaseInitiated) {
      return immediateFailedFuture(new IllegalStateException("BitmapProcessor has been released."));
    }
    // Do not cancel previous calls if later setEffectsAsync calls are cancelled.
    ListenableFuture<?> nonCancellableLastOperation =
        Futures.nonCancellationPropagating(lastOperationFuture);
    ListenableFuture<Void> clearPipelineFuture =
        Futures.whenAllComplete(nonCancellableLastOperation)
            .callAsync(
                () -> {
                  checkState(!releaseInitiated);
                  return maybeReleasePipeline();
                },
                glThreadExecutorService);
    lastSetEffectsFuture =
        Futures.transformAsync(
            clearPipelineFuture, (unused) -> buildPipelineAsync(effects), glThreadExecutorService);
    lastOperationFuture = lastSetEffectsFuture;
    // Track all returned futures so they can be cancelled on releaseAsync.
    int activeFutureId = nextActiveFutureId;
    nextActiveFutureId++;
    activeFutures.put(activeFutureId, lastOperationFuture);
    lastOperationFuture.addListener(() -> activeFutures.remove(activeFutureId), directExecutor());
    return lastSetEffectsFuture;
  }

  /**
   * Applies the effects used in the last {@link #setEffectsAsync} call to the given {@link Bitmap}.
   *
   * <p>The input {@link Bitmap} is {@linkplain Bitmap#recycle() recycled} so should not be reused
   * after calling this method.
   *
   * <p>If a call to this method fails, all subsequent calls will also fail until {@link
   * #setEffectsAsync} is called again to reconfigure the pipeline.
   *
   * @param inputBitmap The bitmap to apply effects to.
   * @return A ListenableFuture that resolves to a new {@link Bitmap} instance with the configured
   *     effects applied after processing is complete.
   * @throws IllegalStateException If called after {@link #releaseAsync}.
   */
  // TODO: b/429347981 - Allow reusing the input bitmap.
  // TODO: b/429347981 - Support HDR input and output.
  public ListenableFuture<Bitmap> applyEffectsAsync(Bitmap inputBitmap) {
    verifyCallingThread();
    if (releaseInitiated) {
      return immediateFailedFuture(new IllegalStateException("BitmapProcessor has been released."));
    }
    ListenableFuture<Bitmap> applyEffectsFuture =
        CallbackToFutureAdapter.getFuture(
            completer -> {
              Futures.addCallback(
                  lastSetEffectsFuture,
                  new FutureCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                      processNext(completer, inputBitmap);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                      // If the previous setEffects call fails, do not add this completer to the
                      // queue and fail it immediately.
                      completer.setException(t);
                    }
                  },
                  glThreadExecutorService);
              return "BitmapProcessor::applyEffects";
            });
    lastOperationFuture = Futures.transform(applyEffectsFuture, (bitmap) -> null, directExecutor());
    // Track all returned futures so they can be cancelled on releaseAsync.
    int activeFutureId = nextActiveFutureId;
    nextActiveFutureId++;
    activeFutures.put(activeFutureId, lastOperationFuture);
    lastOperationFuture.addListener(() -> activeFutures.remove(activeFutureId), directExecutor());
    return applyEffectsFuture;
  }

  /**
   * Initiates the release of the BitmapProcessor's resources and shuts down the internal executor
   * service.
   *
   * <p>Once all pending operations are executed, the method shuts down the internal executor.
   *
   * @return A {@link ListenableFuture<Void>} that completes when the shutdown process has been
   *     completed on the GL thread.
   */
  public ListenableFuture<Void> releaseAsync() {
    verifyCallingThread();
    if (releaseFuture != null) {
      return releaseFuture;
    }
    // Stop accepting new requests.
    releaseInitiated = true;

    // Cancel all pending futures.
    @Nullable Completer<Bitmap> completer;
    while ((completer = pendingCompleters.poll()) != null) {
      completer.setCancelled();
    }
    for (ListenableFuture<Void> future : activeFutures.values()) {
      future.cancel(/* mayInterruptIfRunning= */ false);
    }
    activeFutures.clear();

    // Clean up GL resources.
    ListenableFuture<Void> releaseEglDisplay =
        Futures.whenAllComplete(maybeReleasePipeline())
            .call(
                () -> {
                  if (eglDisplay != null) {
                    glObjectsProvider.release(eglDisplay);
                  }
                  return null;
                },
                glThreadExecutorService);
    releaseFuture =
        Futures.whenAllComplete(releaseEglDisplay)
            .call(
                () -> {
                  glThreadExecutorService.shutdownNow();
                  return null;
                },
                directExecutor());
    return releaseFuture;
  }

  private void verifyCallingThread() {
    Thread currentThread = Thread.currentThread();
    if (callingThread != currentThread) {
      throw new IllegalStateException(
          "Object accessed from incorrect thread. Owner: "
              + callingThread.getName()
              + ", Current: "
              + currentThread.getName());
    }
  }

  private ListenableFuture<Void> maybeReleasePipeline() {
    return pipeline == null ? Futures.immediateVoidFuture() : pipeline.releaseAsync();
  }

  private void processNext(Completer<Bitmap> completer, Bitmap inputBitmap) {
    AtomicBoolean cancelled = new AtomicBoolean(false);
    completer.addCancellationListener(() -> cancelled.set(true), directExecutor());
    if (cancelled.get()) {
      return;
    }
    if (releaseInitiated) {
      completer.setException(new IllegalStateException("BitmapProcessor is released"));
      return;
    }
    if (pipelineException != null) {
      completer.setException(
          new IllegalStateException(
              "BitmapProcessor previously failed with exception", pipelineException));
      return;
    }
    if (pipeline == null) {
      completer.setException(new IllegalStateException("setEffectsAsync has not been called"));
      return;
    }
    Format inputFormat =
        new Format.Builder()
            .setWidth(inputBitmap.getWidth())
            .setHeight(inputBitmap.getHeight())
            .setColorInfo(ColorInfo.SRGB_BT709_FULL)
            .build();
    BitmapFrame inputFrame =
        new BitmapFrame(
            inputBitmap, new BitmapFrame.Metadata(/* presentationTimeUs= */ 0, inputFormat));
    if (!pipeline.getInput().queueFrame(inputFrame)) {
      completer.setException(new IllegalStateException("Expected pipeline to accept input frame."));
      return;
    }
    pendingCompleters.add(completer);
  }

  private void handleException(VideoFrameProcessingException e) {
    pipelineException = e;
    // The only completers in the queue are for in flight frames, and they should all be failed
    // immediately as soon as there is an exception.
    @Nullable Completer<Bitmap> completer = pendingCompleters.poll();
    while (completer != null) {
      completer.setException(e);
      completer = pendingCompleters.poll();
    }
  }

  /** Creates a {@link Pipeline} with {@link FrameProcessor}s matching the given effects. */
  private ListenableFuture<Void> buildPipelineAsync(List<Effect> effects)
      throws VideoFrameProcessingException, GlException {
    maybeConfigureGlContext();
    List<GlEffect> glEffects = new ArrayList<>();
    for (Effect effect : effects) {
      if (!(effect instanceof GlEffect)) {
        throw new IllegalArgumentException("BitmapProcessor can only be applied to GlEffect");
      }
      glEffects.add((GlEffect) effect);
    }

    BitmapToGlTextureFrameProcessor inputProcessor =
        frameProcessorFactory.buildBitmapToGlTextureFrameProcessor(
            ColorInfo.SRGB_BT709_FULL, ColorInfo.SDR_BT709_LIMITED, this::handleException);
    List<GlShaderProgramFrameProcessor> effectsProcessors =
        frameProcessorFactory.buildFrameProcessors(glEffects, /* useHdr= */ false);
    GlTextureToBitmapFrameProcessor outputProcessor =
        frameProcessorFactory.buildGlTextureToBitmapFrameProcessor(/* useHdr= */ false);

    ListenableFuture<Pipeline> pipelineFuture =
        Pipeline.createAsync(
            inputProcessor,
            effectsProcessors,
            outputProcessor,
            glThreadExecutorService,
            this::handleException,
            this::onOutputFrameAvailable);
    return Futures.transform(
        pipelineFuture,
        (pipeline) -> {
          pipelineException = null;
          this.pipeline = pipeline;
          return null;
        },
        glThreadExecutorService);
  }

  private void maybeConfigureGlContext() throws GlUtil.GlException {
    if (isConfigured) {
      return;
    }
    eglDisplay = getDefaultEglDisplay();
    EGLContext eglContext =
        glObjectsProvider.createEglContext(
            eglDisplay, /* openGlVersion= */ 2, EGL_CONFIG_ATTRIBUTES_RGBA_8888);
    glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
    isConfigured = true;
  }

  /** Receives processed frames from the pipeline. */
  private void onOutputFrameAvailable(BitmapFrame frame) {
    Completer<Bitmap> completer = pendingCompleters.poll();
    if (completer != null) {
      // Cancellation listener is invoked instantaneously if the returned future is already
      // cancelled.
      AtomicBoolean cancelled = new AtomicBoolean(false);
      completer.addCancellationListener(() -> cancelled.set(true), directExecutor());
      if (cancelled.get()) {
        frame.release();
        return;
      }
      completer.set(frame.getBitmap());
    } else {
      // This case could happen if a frame submitted to the pipeline later failed, but this frame
      // still managed to be processed. In an ideal case we would return the correctly processed
      // frame but as there is currently no way to match this frame to it's completer, release it
      // and accept that it has failed.
      frame.release();
    }
  }

  /** Helper class for holding the created {@linkplain FrameProcessor frame processors}. */
  private static final class Pipeline {

    /**
     * Creates a {@link Pipeline} that chains the given {@link FrameProcessor}s and forwards output
     * frames to {@code onProcessedFrameAvailable}.
     *
     * <p>Queue {@link Bitmap}s to this pipeline through {@link Pipeline#inputConsumer}, and they
     * will be processed when there is capacity.
     *
     * <p>Output {@link Bitmap}s will be sent to the {@code onProcessedFrameAvailable} callback, and
     * errors on the {@code onError} callback.
     */
    public static ListenableFuture<Pipeline> createAsync(
        BitmapToGlTextureFrameProcessor inputProcessor,
        List<GlShaderProgramFrameProcessor> effectsProcessors,
        GlTextureToBitmapFrameProcessor outputProcessor,
        ListeningExecutorService glThreadExecutorService,
        Consumer<VideoFrameProcessingException> onError,
        Consumer<BitmapFrame> onProcessedFrameAvailable) {
      FrameProcessor<? extends Frame, GlTextureFrame> currentProcessor = inputProcessor;
      List<ListenableFuture<Void>> setOutputFutures = new ArrayList<>();
      for (int i = 0; i < effectsProcessors.size(); i++) {
        setOutputFutures.add(currentProcessor.setOutputAsync(effectsProcessors.get(i).getInput()));
        currentProcessor.setOnErrorCallback(glThreadExecutorService, onError);
        currentProcessor = effectsProcessors.get(i);
      }
      setOutputFutures.add(currentProcessor.setOutputAsync(outputProcessor.getInput()));
      currentProcessor.setOnErrorCallback(glThreadExecutorService, onError);
      outputProcessor.setOnErrorCallback(glThreadExecutorService, onError);

      InputConsumer inputConsumer = new InputConsumer(inputProcessor.getInput());
      inputProcessor
          .getInput()
          .setOnCapacityAvailableCallback(
              glThreadExecutorService, inputConsumer::maybeDrainInputFrames);
      FinalConsumer finalConsumer = new FinalConsumer(onProcessedFrameAvailable);
      setOutputFutures.add(outputProcessor.setOutputAsync(finalConsumer));

      ArrayList<FrameProcessor<?, ?>> frameProcessors = new ArrayList<>();
      frameProcessors.add(inputProcessor);
      frameProcessors.addAll(effectsProcessors);
      frameProcessors.add(outputProcessor);
      return Futures.transform(
          Futures.allAsList(setOutputFutures),
          (unused) -> new Pipeline(inputConsumer, frameProcessors),
          directExecutor());
    }

    private final InputConsumer inputConsumer;
    private final List<FrameProcessor<?, ?>> frameProcessors;

    public Pipeline(InputConsumer inputConsumer, List<FrameProcessor<?, ?>> frameProcessors) {
      this.inputConsumer = inputConsumer;
      this.frameProcessors = frameProcessors;
    }

    public FrameConsumer<BitmapFrame> getInput() {
      return inputConsumer;
    }

    /**
     * Releases all internal {@link FrameProcessor}s.
     *
     * @return {@link ListenableFuture<Void>} that completes once all processors have been released.
     */
    public ListenableFuture<Void> releaseAsync() {
      List<ListenableFuture<Void>> releaseFutures = new ArrayList<>();
      inputConsumer.release();
      for (FrameProcessor<?, ?> processor : frameProcessors) {
        releaseFutures.add(processor.releaseAsync());
      }
      return Futures.transform(
          Futures.allAsList(releaseFutures), (unused) -> null, directExecutor());
    }
  }

  /**
   * {@link FrameConsumer} that holds a queue of {@link BitmapFrame}s that are sent downstream when
   * possible.
   */
  private static final class InputConsumer implements FrameConsumer<BitmapFrame> {

    private final FrameConsumer<BitmapFrame> downstreamConsumer;
    private final Queue<BitmapFrame> inputFrames;
    private boolean isReleased;

    public InputConsumer(FrameConsumer<BitmapFrame> downstreamConsumer) {
      this.downstreamConsumer = downstreamConsumer;
      this.inputFrames = new ArrayDeque<>();
    }

    public void release() {
      isReleased = true;
      inputFrames.clear();
    }

    @Override
    public boolean queueFrame(BitmapFrame frame) {
      checkState(!isReleased);
      inputFrames.add(frame);
      maybeDrainInputFrames();
      return true;
    }

    private void maybeDrainInputFrames() {
      @Nullable BitmapFrame nextFrame = inputFrames.peek();
      while (nextFrame != null && downstreamConsumer.queueFrame(nextFrame)) {
        inputFrames.poll();
        nextFrame = inputFrames.peek();
      }
    }

    @Override
    public void setOnCapacityAvailableCallback(
        Executor executor, Runnable onCapacityAvailableCallback) {
      // Do nothing. This consumer can always accept frames.
    }

    @Override
    public void clearOnCapacityAvailableCallback() {
      // Do nothing. This consumer can always accept frames.
    }
  }

  /**
   * {@link FrameConsumer} that notifies a {@linkplain Consumer callback} when it receives a frame.
   */
  private static final class FinalConsumer implements FrameConsumer<BitmapFrame> {

    private final Consumer<BitmapFrame> onQueueFrameCallback;

    public FinalConsumer(Consumer<BitmapFrame> onQueueFrameCallback) {
      this.onQueueFrameCallback = onQueueFrameCallback;
    }

    @Override
    public boolean queueFrame(BitmapFrame frame) {
      onQueueFrameCallback.accept(frame);
      return true;
    }

    @Override
    public void setOnCapacityAvailableCallback(
        Executor executor, Runnable onCapacityAvailableCallback) {
      // Do nothing. This consumer can always accept frames.
    }

    @Override
    public void clearOnCapacityAvailableCallback() {
      // Do nothing. This consumer can always accept frames.
    }
  }
}
