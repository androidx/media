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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.OnInputFrameProcessedListener;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.List;
import java.util.concurrent.Executor;

/** A {@link VideoGraph} that handles one input stream. */
@UnstableApi
public class SingleInputVideoGraph implements VideoGraph {

  private final Context context;
  private final VideoFrameProcessor.Factory videoFrameProcessorFactory;
  private final ColorInfo outputColorInfo;
  private final Listener listener;
  private final DebugViewProvider debugViewProvider;
  private final Executor listenerExecutor;
  private final boolean renderFramesAutomatically;

  @Nullable private VideoFrameProcessor videoFrameProcessor;
  @Nullable private SurfaceInfo outputSurfaceInfo;
  private ImmutableList<Effect> compositionEffects;
  private boolean released;
  private volatile boolean hasProducedFrameWithTimestampZero;
  private int inputIndex;

  /** A {@link VideoGraph.Factory} for {@link SingleInputVideoGraph}. */
  public static final class Factory implements VideoGraph.Factory {
    private final VideoFrameProcessor.Factory videoFrameProcessorFactory;

    /**
     * A {@code Factory} for {@link SingleInputVideoGraph} that uses a {@link
     * DefaultVideoFrameProcessor.Factory}.
     */
    public Factory() {
      this(new DefaultVideoFrameProcessor.Factory.Builder().build());
    }

    public Factory(VideoFrameProcessor.Factory videoFrameProcessorFactory) {
      this.videoFrameProcessorFactory = videoFrameProcessorFactory;
    }

    @Override
    public SingleInputVideoGraph create(
        Context context,
        ColorInfo outputColorInfo,
        DebugViewProvider debugViewProvider,
        Listener listener,
        Executor listenerExecutor,
        long initialTimestampOffsetUs,
        boolean renderFramesAutomatically) {
      return new SingleInputVideoGraph(
          context,
          videoFrameProcessorFactory,
          outputColorInfo,
          listener,
          debugViewProvider,
          listenerExecutor,
          renderFramesAutomatically);
    }

    @Override
    public boolean supportsMultipleInputs() {
      return false;
    }
  }

  /**
   * Creates an instance.
   *
   * <p>{@code videoCompositorSettings} must be {@link VideoCompositorSettings#DEFAULT}.
   */
  public SingleInputVideoGraph(
      Context context,
      VideoFrameProcessor.Factory videoFrameProcessorFactory,
      ColorInfo outputColorInfo,
      Listener listener,
      DebugViewProvider debugViewProvider,
      Executor listenerExecutor,
      boolean renderFramesAutomatically) {
    this.context = context;
    this.videoFrameProcessorFactory = videoFrameProcessorFactory;
    this.outputColorInfo = outputColorInfo;
    this.listener = listener;
    this.debugViewProvider = debugViewProvider;
    this.listenerExecutor = listenerExecutor;
    this.compositionEffects = ImmutableList.of();
    this.renderFramesAutomatically = renderFramesAutomatically;
    this.inputIndex = C.INDEX_UNSET;
  }

  /**
   * {@inheritDoc}
   *
   * <p>This method must be called at most once.
   */
  @Override
  public void initialize() {
    // Initialization is deferred to registerInput();
  }

  @Override
  public void registerInput(int inputIndex) throws VideoFrameProcessingException {
    checkState(videoFrameProcessor == null && !released);
    checkState(this.inputIndex == C.INDEX_UNSET, "This VideoGraph supports only one input.");

    this.inputIndex = inputIndex;
    videoFrameProcessor =
        videoFrameProcessorFactory.create(
            context,
            debugViewProvider,
            outputColorInfo,
            renderFramesAutomatically,
            /* listenerExecutor= */ MoreExecutors.directExecutor(),
            new VideoFrameProcessor.Listener() {
              private long lastProcessedFramePresentationTimeUs;

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
                // Frames are rendered automatically.
                if (presentationTimeUs == 0) {
                  hasProducedFrameWithTimestampZero = true;
                }
                lastProcessedFramePresentationTimeUs = presentationTimeUs;
                listenerExecutor.execute(
                    () ->
                        listener.onOutputFrameAvailableForRendering(
                            presentationTimeUs, isRedrawnFrame));
              }

              @Override
              public void onError(VideoFrameProcessingException exception) {
                listenerExecutor.execute(() -> listener.onError(exception));
              }

              @Override
              public void onEnded() {
                listenerExecutor.execute(
                    () -> listener.onEnded(lastProcessedFramePresentationTimeUs));
              }
            });
    if (outputSurfaceInfo != null) {
      videoFrameProcessor.setOutputSurfaceInfo(outputSurfaceInfo);
    }
  }

  @Override
  public void setOutputSurfaceInfo(@Nullable SurfaceInfo outputSurfaceInfo) {
    this.outputSurfaceInfo = outputSurfaceInfo;
    if (videoFrameProcessor != null) {
      videoFrameProcessor.setOutputSurfaceInfo(outputSurfaceInfo);
    }
  }

  @Override
  public boolean hasProducedFrameWithTimestampZero() {
    return hasProducedFrameWithTimestampZero;
  }

  @Override
  public boolean queueInputBitmap(
      int inputIndex, Bitmap inputBitmap, TimestampIterator timestampIterator) {
    checkNotNull(videoFrameProcessor);
    return videoFrameProcessor.queueInputBitmap(inputBitmap, timestampIterator);
  }

  @Override
  public boolean queueInputTexture(int inputIndex, int textureId, long presentationTimeUs) {
    checkNotNull(videoFrameProcessor);
    return videoFrameProcessor.queueInputTexture(textureId, presentationTimeUs);
  }

  @Override
  public void setOnInputFrameProcessedListener(
      int inputIndex, OnInputFrameProcessedListener listener) {
    checkNotNull(videoFrameProcessor);
    videoFrameProcessor.setOnInputFrameProcessedListener(listener);
  }

  @Override
  public void setOnInputSurfaceReadyListener(int inputIndex, Runnable listener) {
    checkNotNull(videoFrameProcessor);
    videoFrameProcessor.setOnInputSurfaceReadyListener(listener);
  }

  @Override
  public Surface getInputSurface(int inputIndex) {
    checkNotNull(videoFrameProcessor);
    return videoFrameProcessor.getInputSurface();
  }

  @Override
  public void registerInputStream(
      int inputIndex,
      @VideoFrameProcessor.InputType int inputType,
      Format format,
      List<Effect> effects,
      long offsetToAddUs) {
    checkNotNull(videoFrameProcessor);
    videoFrameProcessor.registerInputStream(
        inputType,
        format,
        new ImmutableList.Builder<Effect>().addAll(effects).addAll(compositionEffects).build(),
        offsetToAddUs);
  }

  @Override
  public void setCompositionEffects(List<Effect> compositionEffects) {
    this.compositionEffects = ImmutableList.copyOf(compositionEffects);
  }

  @Override
  public void setCompositorSettings(VideoCompositorSettings videoCompositorSettings) {
    checkArgument(
        videoCompositorSettings.equals(VideoCompositorSettings.DEFAULT),
        "SingleInputVideoGraph does not use VideoCompositor, and therefore cannot apply"
            + " VideoCompositorSettings");
  }

  @Override
  public boolean registerInputFrame(int inputIndex) {
    checkNotNull(videoFrameProcessor);
    return videoFrameProcessor.registerInputFrame();
  }

  @Override
  public int getPendingInputFrameCount(int inputIndex) {
    checkNotNull(videoFrameProcessor);
    return videoFrameProcessor.getPendingInputFrameCount();
  }

  @Override
  public void renderOutputFrame(long renderTimeNs) {
    checkNotNull(videoFrameProcessor);
    videoFrameProcessor.renderOutputFrame(renderTimeNs);
  }

  @Override
  public void redraw() {
    checkNotNull(videoFrameProcessor).redraw();
  }

  @Override
  public void flush() {
    checkNotNull(videoFrameProcessor);
    videoFrameProcessor.flush();
  }

  @Override
  public void signalEndOfInput(int inputIndex) {
    checkNotNull(videoFrameProcessor);
    videoFrameProcessor.signalEndOfInput();
  }

  @Override
  public void release() {
    if (released) {
      return;
    }
    if (videoFrameProcessor != null) {
      videoFrameProcessor.release();
    }
    released = true;
  }
}
