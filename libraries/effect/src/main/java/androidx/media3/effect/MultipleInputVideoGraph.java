/*
 * Copyright 2023 The Android Open Source Project
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

import static androidx.media3.common.VideoFrameProcessor.INPUT_TYPE_TEXTURE_ID;
import static androidx.media3.common.util.GlUtil.destroyEglContext;
import static androidx.media3.common.util.GlUtil.getDefaultEglDisplay;
import static androidx.media3.common.util.Util.contains;
import static androidx.media3.common.util.Util.newSingleThreadScheduledExecutor;
import static androidx.media3.effect.DebugTraceUtil.COMPONENT_COMPOSITOR;
import static androidx.media3.effect.DebugTraceUtil.COMPONENT_VFP;
import static androidx.media3.effect.DebugTraceUtil.EVENT_OUTPUT_TEXTURE_RENDERED;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.util.SparseArray;
import android.view.Surface;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.OnInputFrameProcessedListener;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** A {@link VideoGraph} that handles multiple input streams. */
@UnstableApi
public final class MultipleInputVideoGraph implements VideoGraph {

  /** A {@link VideoGraph.Factory} for {@link MultipleInputVideoGraph}. */
  public static final class Factory implements VideoGraph.Factory {
    private final VideoFrameProcessor.Factory videoFrameProcessorFactory;

    /**
     * A {@code Factory} for {@link MultipleInputVideoGraph} that uses a {@link
     * DefaultVideoFrameProcessor.Factory}.
     */
    public Factory() {
      this(new DefaultVideoFrameProcessor.Factory.Builder().build());
    }

    public Factory(VideoFrameProcessor.Factory videoFrameProcessorFactory) {
      this.videoFrameProcessorFactory = videoFrameProcessorFactory;
    }

    @Override
    public MultipleInputVideoGraph create(
        Context context,
        ColorInfo outputColorInfo,
        DebugViewProvider debugViewProvider,
        Listener listener,
        Executor listenerExecutor,
        long initialTimestampOffsetUs,
        boolean renderFramesAutomatically) {
      return new MultipleInputVideoGraph(
          context,
          videoFrameProcessorFactory,
          outputColorInfo,
          debugViewProvider,
          listener,
          listenerExecutor,
          renderFramesAutomatically);
    }

    @Override
    public boolean supportsMultipleInputs() {
      return true;
    }
  }

  private static final String TAG = "MultiInputVG";
  private static final String SHARED_EXECUTOR_NAME = "Effect:MultipleInputVideoGraph:Thread";

  private static final long RELEASE_WAIT_TIME_MS = 1_000;
  private static final int PRE_COMPOSITOR_TEXTURE_OUTPUT_CAPACITY = 2;
  private static final int COMPOSITOR_TEXTURE_OUTPUT_CAPACITY = 1;

  private final Context context;
  private final ColorInfo outputColorInfo;
  private final GlObjectsProvider glObjectsProvider;
  private final DebugViewProvider debugViewProvider;
  private final Listener listener;
  private final Executor listenerExecutor;
  private final SparseArray<VideoFrameProcessor> preProcessors;
  private final ExecutorService sharedExecutorService;
  private final DefaultVideoFrameProcessor.Factory videoFrameProcessorFactory;
  private final Queue<TimedGlTextureInfo> compositorOutputTextures;
  private final SparseArray<CompositorOutputTextureRelease> compositorOutputTextureReleases;

  private final boolean renderFramesAutomatically;

  private List<Effect> compositionEffects;
  private VideoCompositorSettings videoCompositorSettings;
  @Nullable private VideoFrameProcessor compositionVideoFrameProcessor;
  @Nullable private VideoCompositor videoCompositor;
  private Size compositorOutputSize;

  private boolean compositorEnded;
  private boolean released;
  private long lastRenderedPresentationTimeUs;

  private volatile boolean hasProducedFrameWithTimestampZero;

  private MultipleInputVideoGraph(
      Context context,
      VideoFrameProcessor.Factory videoFrameProcessorFactory,
      ColorInfo outputColorInfo,
      DebugViewProvider debugViewProvider,
      Listener listener,
      Executor listenerExecutor,
      boolean renderFramesAutomatically) {
    checkArgument(videoFrameProcessorFactory instanceof DefaultVideoFrameProcessor.Factory);
    this.context = context;
    this.outputColorInfo = outputColorInfo;
    this.debugViewProvider = debugViewProvider;
    this.listener = listener;
    this.listenerExecutor = listenerExecutor;
    this.renderFramesAutomatically = renderFramesAutomatically;
    lastRenderedPresentationTimeUs = C.TIME_UNSET;
    preProcessors = new SparseArray<>();
    sharedExecutorService = newSingleThreadScheduledExecutor(SHARED_EXECUTOR_NAME);
    glObjectsProvider = new SingleContextGlObjectsProvider();
    // TODO - b/289986435: Support injecting arbitrary VideoFrameProcessor.Factory.
    this.videoFrameProcessorFactory =
        ((DefaultVideoFrameProcessor.Factory) videoFrameProcessorFactory)
            .buildUpon()
            .setGlObjectsProvider(glObjectsProvider)
            .setExecutorService(sharedExecutorService)
            .build();
    compositorOutputTextures = new ArrayDeque<>();
    compositorOutputTextureReleases = new SparseArray<>();
    compositorOutputSize = Size.UNKNOWN;
    compositionEffects = ImmutableList.of();
    videoCompositorSettings = VideoCompositorSettings.DEFAULT;
  }

  /**
   * {@inheritDoc}
   *
   * <p>This method must be called at most once.
   */
  @Override
  public void initialize() throws VideoFrameProcessingException {
    checkState(
        preProcessors.size() == 0
            && videoCompositor == null
            && compositionVideoFrameProcessor == null
            && !released);

    // Setting up the compositionVideoFrameProcessor
    compositionVideoFrameProcessor =
        videoFrameProcessorFactory.create(
            context,
            debugViewProvider,
            outputColorInfo,
            renderFramesAutomatically,
            /* listenerExecutor= */ MoreExecutors.directExecutor(),
            new VideoFrameProcessor.Listener() {
              // All of this listener's methods are called on the sharedExecutorService.
              @Override
              public void onInputStreamRegistered(
                  @VideoFrameProcessor.InputType int inputType,
                  Format format,
                  List<Effect> effects) {
                queueCompositionOutputInternal();
              }

              @Override
              public void onOutputSizeChanged(int width, int height) {
                listenerExecutor.execute(() -> listener.onOutputSizeChanged(width, height));
              }

              @Override
              public void onOutputFrameRateChanged(float frameRate) {
                listenerExecutor.execute(() -> listener.onOutputFrameRateChanged(frameRate));
              }

              @Override
              public void onOutputFrameAvailableForRendering(
                  long presentationTimeUs, boolean isRedrawnFrame) {
                if (presentationTimeUs == 0) {
                  hasProducedFrameWithTimestampZero = true;
                }
                lastRenderedPresentationTimeUs = presentationTimeUs;

                listenerExecutor.execute(
                    () ->
                        listener.onOutputFrameAvailableForRendering(
                            presentationTimeUs, isRedrawnFrame));
              }

              @Override
              public void onError(VideoFrameProcessingException exception) {
                handleVideoFrameProcessingException(exception);
              }

              @Override
              public void onEnded() {
                listenerExecutor.execute(() -> listener.onEnded(lastRenderedPresentationTimeUs));
              }
            });
    // Release the compositor's output texture.
    compositionVideoFrameProcessor.setOnInputFrameProcessedListener(
        this::onCompositionVideoFrameProcessorInputFrameProcessed);

    // Setting up the compositor.
    videoCompositor =
        new DefaultVideoCompositor(
            context,
            glObjectsProvider,
            sharedExecutorService,
            new VideoCompositor.Listener() {
              // All of this listener's methods are called on the sharedExecutorService.
              @Override
              public void onError(VideoFrameProcessingException exception) {
                handleVideoFrameProcessingException(exception);
              }

              @Override
              public void onEnded() {
                onVideoCompositorEnded();
              }
            },
            /* textureOutputListener= */ this::processCompositorOutputTexture,
            COMPOSITOR_TEXTURE_OUTPUT_CAPACITY);
    videoCompositor.setVideoCompositorSettings(videoCompositorSettings);
  }

  @Override
  public void registerInput(@IntRange(from = 0) int inputIndex)
      throws VideoFrameProcessingException {
    checkState(!contains(preProcessors, inputIndex));
    checkNotNull(videoCompositor).registerInputSource(inputIndex);
    // Creating a new VideoFrameProcessor for the input.
    VideoFrameProcessor preProcessor =
        videoFrameProcessorFactory
            .buildUpon()
            .setTextureOutput(
                // Texture output to compositor.
                (textureProducer, texture, presentationTimeUs, syncObject) ->
                    queuePreProcessingOutputToCompositor(
                        inputIndex, textureProducer, texture, presentationTimeUs),
                PRE_COMPOSITOR_TEXTURE_OUTPUT_CAPACITY)
            .build()
            .create(
                context,
                DebugViewProvider.NONE,
                outputColorInfo,
                // Pre-processors render frames as soon as available, to VideoCompositor.
                /* renderFramesAutomatically= */ true,
                listenerExecutor,
                new VideoFrameProcessor.Listener() {
                  // All of this listener's methods are called on the sharedExecutorService.
                  @Override
                  public void onError(VideoFrameProcessingException exception) {
                    handleVideoFrameProcessingException(exception);
                  }

                  @Override
                  public void onEnded() {
                    onPreProcessingVideoFrameProcessorEnded(inputIndex);
                  }
                });
    preProcessors.put(inputIndex, preProcessor);
  }

  @Override
  public void setOutputSurfaceInfo(@Nullable SurfaceInfo outputSurfaceInfo) {
    checkNotNull(compositionVideoFrameProcessor).setOutputSurfaceInfo(outputSurfaceInfo);
  }

  @Override
  public boolean hasProducedFrameWithTimestampZero() {
    return hasProducedFrameWithTimestampZero;
  }

  @Override
  public boolean queueInputBitmap(
      int inputIndex, Bitmap inputBitmap, TimestampIterator timestampIterator) {
    return getProcessor(inputIndex).queueInputBitmap(inputBitmap, timestampIterator);
  }

  @Override
  public boolean queueInputTexture(int inputIndex, int textureId, long presentationTimeUs) {
    return getProcessor(inputIndex).queueInputTexture(textureId, presentationTimeUs);
  }

  @Override
  public void setOnInputFrameProcessedListener(
      int inputIndex, OnInputFrameProcessedListener listener) {
    getProcessor(inputIndex).setOnInputFrameProcessedListener(listener);
  }

  @Override
  public void setOnInputSurfaceReadyListener(int inputIndex, Runnable listener) {
    getProcessor(inputIndex).setOnInputSurfaceReadyListener(listener);
  }

  @Override
  public Surface getInputSurface(int inputIndex) {
    return getProcessor(inputIndex).getInputSurface();
  }

  @Override
  public void registerInputStream(
      int inputIndex,
      @VideoFrameProcessor.InputType int inputType,
      Format format,
      List<Effect> effects,
      long offsetToAddUs) {
    getProcessor(inputIndex).registerInputStream(inputType, format, effects, offsetToAddUs);
  }

  @Override
  public void setCompositionEffects(List<Effect> compositionEffects) {
    // TODO: b/412585856 - Support dynamic changing composition effects.
    this.compositionEffects = compositionEffects;
  }

  @Override
  public void setCompositorSettings(VideoCompositorSettings videoCompositorSettings) {
    this.videoCompositorSettings = videoCompositorSettings;
    if (videoCompositor != null) {
      videoCompositor.setVideoCompositorSettings(videoCompositorSettings);
    }
  }

  @Override
  public boolean registerInputFrame(int inputIndex) {
    return getProcessor(inputIndex).registerInputFrame();
  }

  @Override
  public int getPendingInputFrameCount(int inputIndex) {
    return getProcessor(inputIndex).getPendingInputFrameCount();
  }

  @Override
  public void renderOutputFrame(long renderTimeNs) {
    checkNotNull(compositionVideoFrameProcessor).renderOutputFrame(renderTimeNs);
  }

  @Override
  public void redraw() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void flush() {
    for (int i = 0; i < preProcessors.size(); i++) {
      preProcessors.get(preProcessors.keyAt(i)).flush();
    }
  }

  @Override
  public void signalEndOfInput(int inputIndex) {
    getProcessor(inputIndex).signalEndOfInput();
  }

  @Override
  public void release() {
    if (released) {
      return;
    }

    // Needs to release the frame processors before their internal executor services are released.
    for (int i = 0; i < preProcessors.size(); i++) {
      preProcessors.get(preProcessors.keyAt(i)).release();
    }

    if (videoCompositor != null) {
      videoCompositor.release();
      videoCompositor = null;
    }

    if (compositionVideoFrameProcessor != null) {
      compositionVideoFrameProcessor.release();
      compositionVideoFrameProcessor = null;
    }

    Future<?> unused =
        sharedExecutorService.submit(
            () -> {
              try {
                glObjectsProvider.release(getDefaultEglDisplay());
              } catch (Exception e) {
                Log.e(TAG, "Error releasing GlObjectsProvider", e);
              }
            });

    sharedExecutorService.shutdown();
    try {
      sharedExecutorService.awaitTermination(RELEASE_WAIT_TIME_MS, MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Log.e(TAG, "Thread interrupted while waiting for executor service termination");
    }

    released = true;
  }

  private VideoFrameProcessor getProcessor(int inputIndex) {
    checkState(contains(preProcessors, inputIndex));
    return preProcessors.get(inputIndex);
  }

  // This method is called on the sharedExecutorService.
  private void queuePreProcessingOutputToCompositor(
      int videoCompositorInputId,
      GlTextureProducer textureProducer,
      GlTextureInfo texture,
      long presentationTimeUs) {
    DebugTraceUtil.logEvent(COMPONENT_VFP, EVENT_OUTPUT_TEXTURE_RENDERED, presentationTimeUs);
    checkNotNull(videoCompositor)
        .queueInputTexture(
            videoCompositorInputId,
            textureProducer,
            texture,
            // Color is converted to outputColor in pre processing.
            /* colorInfo= */ outputColorInfo,
            presentationTimeUs);
  }

  // This method is called on the sharedExecutorService.
  private void processCompositorOutputTexture(
      GlTextureProducer textureProducer,
      GlTextureInfo outputTexture,
      long presentationTimeUs,
      long syncObject) {
    checkState(!compositorEnded);
    DebugTraceUtil.logEvent(
        COMPONENT_COMPOSITOR, EVENT_OUTPUT_TEXTURE_RENDERED, presentationTimeUs);

    compositorOutputTextures.add(new TimedGlTextureInfo(outputTexture, presentationTimeUs));
    compositorOutputTextureReleases.put(
        outputTexture.texId,
        new CompositorOutputTextureRelease(textureProducer, presentationTimeUs));
    queueCompositionOutputInternal();
  }

  // This method is called on the sharedExecutorService.
  private void onCompositionVideoFrameProcessorInputFrameProcessed(int textureId, long syncObject) {
    // CompositionVideoFrameProcessor's input is VideoCompositor's output.
    checkState(contains(compositorOutputTextureReleases, textureId));
    compositorOutputTextureReleases.get(textureId).release();
    compositorOutputTextureReleases.remove(textureId);
    queueCompositionOutputInternal();
  }

  // This method is called on the sharedExecutorService.
  private void onPreProcessingVideoFrameProcessorEnded(int videoCompositorInputId) {
    checkNotNull(videoCompositor).signalEndOfInputSource(videoCompositorInputId);
  }

  // This method is called on the sharedExecutorService.
  private void onVideoCompositorEnded() {
    compositorEnded = true;
    if (compositorOutputTextures.isEmpty()) {
      checkNotNull(compositionVideoFrameProcessor).signalEndOfInput();
    } else {
      queueCompositionOutputInternal();
    }
  }

  // This method is called on the sharedExecutorService.
  private void queueCompositionOutputInternal() {
    @Nullable TimedGlTextureInfo outputTexture = compositorOutputTextures.peek();
    if (outputTexture == null) {
      return;
    }
    VideoFrameProcessor compositionVideoFrameProcessor =
        checkNotNull(this.compositionVideoFrameProcessor);
    int width = outputTexture.glTextureInfo.width;
    int height = outputTexture.glTextureInfo.height;
    if (width != compositorOutputSize.getWidth() || height != compositorOutputSize.getHeight()) {
      compositionVideoFrameProcessor.registerInputStream(
          INPUT_TYPE_TEXTURE_ID,
          // Pre-processing VideoFrameProcessors have converted the inputColor to outputColor
          // already, so use outputColorInfo for the input color to the
          // compositionVideoFrameProcessor.
          new Format.Builder()
              .setColorInfo(outputColorInfo)
              .setWidth(width)
              .setHeight(height)
              .build(),
          compositionEffects,
          /* offsetToAddUs= */ 0);
      compositorOutputSize = new Size(width, height);
    }
    if (!compositionVideoFrameProcessor.queueInputTexture(
        outputTexture.glTextureInfo.texId, outputTexture.presentationTimeUs)) {
      return;
    }
    compositorOutputTextures.remove();
    if (compositorEnded && compositorOutputTextures.isEmpty()) {
      compositionVideoFrameProcessor.signalEndOfInput();
    }
  }

  // This method is called on the sharedExecutorService.
  private void handleVideoFrameProcessingException(Exception e) {
    listenerExecutor.execute(
        () ->
            listener.onError(
                e instanceof VideoFrameProcessingException
                    ? (VideoFrameProcessingException) e
                    : VideoFrameProcessingException.from(e)));
  }

  private static final class CompositorOutputTextureRelease {
    private final GlTextureProducer textureProducer;
    private final long presentationTimeUs;

    public CompositorOutputTextureRelease(
        GlTextureProducer textureProducer, long presentationTimeUs) {
      this.textureProducer = textureProducer;
      this.presentationTimeUs = presentationTimeUs;
    }

    public void release() {
      textureProducer.releaseOutputTexture(presentationTimeUs);
    }
  }

  /**
   * A {@link GlObjectsProvider} that creates a new {@link EGLContext} in {@link #createEglContext}
   * with the same shared EGLContext.
   */
  private static final class SingleContextGlObjectsProvider implements GlObjectsProvider {
    private final GlObjectsProvider glObjectsProvider;
    private @MonotonicNonNull EGLContext singleEglContext;

    public SingleContextGlObjectsProvider() {
      this.glObjectsProvider = new DefaultGlObjectsProvider();
    }

    @Override
    public EGLContext createEglContext(
        EGLDisplay eglDisplay, int openGlVersion, int[] configAttributes) throws GlException {
      if (singleEglContext == null) {
        singleEglContext =
            glObjectsProvider.createEglContext(eglDisplay, openGlVersion, configAttributes);
      }
      return singleEglContext;
    }

    @Override
    public EGLSurface createEglSurface(
        EGLDisplay eglDisplay,
        Object surface,
        @C.ColorTransfer int colorTransfer,
        boolean isEncoderInputSurface)
        throws GlException {
      return glObjectsProvider.createEglSurface(
          eglDisplay, surface, colorTransfer, isEncoderInputSurface);
    }

    @Override
    public EGLSurface createFocusedPlaceholderEglSurface(
        EGLContext eglContext, EGLDisplay eglDisplay) throws GlException {
      return glObjectsProvider.createFocusedPlaceholderEglSurface(eglContext, eglDisplay);
    }

    @Override
    public GlTextureInfo createBuffersForTexture(int texId, int width, int height)
        throws GlException {
      return glObjectsProvider.createBuffersForTexture(texId, width, height);
    }

    @Override
    public void release(EGLDisplay eglDisplay) throws GlException {
      if (singleEglContext != null) {
        destroyEglContext(eglDisplay, singleEglContext);
      }
    }
  }
}
