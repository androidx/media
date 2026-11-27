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
import static androidx.media3.effect.FrameProcessorUtils.runAllAndAccumulateExceptions;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.Size;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Composites a list of {@link GlTextureFrame GlTextureFrames}, and outputs to a downstream {@link
 * GlTextureFrameConsumer}.
 *
 * <p>Methods in this class must be called on a GL thread.
 */
@RequiresApi(26)
/* package */ final class DefaultGlTextureFrameCompositingProcessor implements AutoCloseable {

  /** Draws multiple input frames onto one output texture using a GL program. */
  public interface CompositorGlProgram {
    /**
     * Draws the input frames onto the output texture.
     *
     * @param framesToComposite The {@linkplain GlCompositionFrame input frames} to composite.
     * @param outputTexture The {@link GlTextureInfo} to draw onto.
     * @throws VideoFrameProcessingException If an error occurs during frame processing.
     */
    void drawFrame(List<GlCompositionFrame> framesToComposite, GlTextureInfo outputTexture)
        throws GlException, VideoFrameProcessingException;

    /** Releases all associated resources. */
    void release() throws VideoFrameProcessingException;
  }

  private final GlObjectsProvider glObjectsProvider;
  private final TexturePool outputTexturePool;
  private final Consumer<VideoFrameProcessingException> errorConsumer;
  private final CompositorGlProgram compositorGlProgram;
  private final Executor glExecutor;
  private final GlTextureFrameConsumer downstreamConsumer;
  private final Object lock;

  @Nullable private GlTextureFrame pendingOutputFrame;

  @GuardedBy("lock")
  @Nullable
  private Runnable pendingWakeupListener;

  @GuardedBy("lock")
  @Nullable
  private Executor pendingWakeupExecutor;

  // This is reset when a new batch of frames is queued.
  private boolean inputStreamEnded;

  /**
   * Creates a new instance.
   *
   * @param glObjectsProvider The {@link GlObjectsProvider} that's used in OpenGL operations.
   * @param outputTexturePool The {@link TexturePool} to hold the pending output frames.
   * @param errorConsumer A {@link Consumer} that consumes {@link VideoFrameProcessingException}.
   * @param compositorGlProgram The {@link CompositorGlProgram} implementation that composites input
   *     frames into one output frame.
   * @param glExecutor The {@link Executor} to run OpenGL related tasks. The executor should execute
   *     on a thread with the current OpenGL context. All methods in this class should execute on
   *     this thread.
   * @param downstreamConsumer The {@link GlTextureFrameConsumer} that consumes the output frames.
   */
  public DefaultGlTextureFrameCompositingProcessor(
      GlObjectsProvider glObjectsProvider,
      TexturePool outputTexturePool,
      Consumer<VideoFrameProcessingException> errorConsumer,
      CompositorGlProgram compositorGlProgram,
      Executor glExecutor,
      GlTextureFrameConsumer downstreamConsumer) {
    this.glObjectsProvider = glObjectsProvider;
    this.outputTexturePool = outputTexturePool;
    this.errorConsumer = errorConsumer;
    this.compositorGlProgram = compositorGlProgram;
    this.glExecutor = glExecutor;
    this.downstreamConsumer = downstreamConsumer;
    lock = new Object();
  }

  /**
   * Attempts to queue frames for compositing.
   *
   * <p>If the compositor is at capacity, it returns {@code false} and the {@code wakeupListener}
   * will be invoked on the {@code listenerExecutor} when capacity becomes available.
   *
   * <p>If {@code frames} contains only a single frame, the compositor is bypassed and the frame is
   * passed directly downstream.
   *
   * @param frames The input frames to composite.
   * @param listenerExecutor The executor to run the {@code wakeupListener} on.
   * @param wakeupListener The callback to run when capacity is freed.
   * @return {@code true} if queued successfully, {@code false} otherwise.
   */
  public boolean queue(
      List<GlTextureFrame> frames, Executor listenerExecutor, Runnable wakeupListener)
      throws VideoFrameProcessingException {
    // TODO: b/517424999 - Unify the listeners to follow the same pattern as FrameProcessor.
    checkArgument(!frames.isEmpty());
    if (pendingOutputFrame != null
        || (frames.size() > 1
            && outputTexturePool.isConfigured()
            && outputTexturePool.freeTextureCount() == 0)) {
      synchronized (lock) {
        pendingWakeupListener = wakeupListener;
        pendingWakeupExecutor = listenerExecutor;
      }
      return false;
    }

    inputStreamEnded = false;
    if (frames.size() == 1) {
      return downstreamConsumer.queue(frames.get(0), listenerExecutor, wakeupListener);
    }

    // All the frames should have the same presentation time and compositor settings.
    GlTextureFrame primaryInputFrame = frames.get(0);
    VideoCompositorSettings videoCompositorSettings =
        (VideoCompositorSettings)
            checkNotNull(primaryInputFrame.getMetadata().get(KEY_COMPOSITOR_SETTINGS));

    ImmutableList.Builder<GlCompositionFrame> compositorInput = new ImmutableList.Builder<>();
    ImmutableList.Builder<Size> inputSizes = new ImmutableList.Builder<>();
    long outputPresentationTimeUs = primaryInputFrame.presentationTimeUs;
    for (int i = 0; i < frames.size(); i++) {
      GlTextureFrame frame = frames.get(i);
      GlTextureInfo textureInfo = frame.glTextureInfo;
      int sequenceIndex =
          (int) checkNotNull(frame.getMetadata().get(KEY_COMPOSITION_SEQUENCE_INDEX));
      compositorInput.add(
          new GlCompositionFrame(
              textureInfo,
              videoCompositorSettings.getOverlaySettings(sequenceIndex, outputPresentationTimeUs)));
      inputSizes.add(new Size(textureInfo.width, textureInfo.height));
    }

    Size outputSize = videoCompositorSettings.getOutputSize(inputSizes.build());
    @Nullable GlTextureInfo outputTexture = null;
    try {
      outputTexturePool.ensureConfigured(
          glObjectsProvider, outputSize.getWidth(), outputSize.getHeight());
      outputTexture = outputTexturePool.useTexture();
      checkNotNull(compositorGlProgram).drawFrame(compositorInput.build(), outputTexture);

      GlTextureFrame outputGlTextureFrame =
          new GlTextureFrame.Builder(
                  outputTexture,
                  /* releaseTextureExecutor= */ glExecutor,
                  /* releaseTextureCallback= */ glTextureInfo -> {
                    try {
                      outputTexturePool.freeTexture(glTextureInfo);
                      maybeInvokeWakeupListener();
                    } catch (RuntimeException e) {
                      errorConsumer.accept(VideoFrameProcessingException.from(e));
                    }
                  })
              .setPresentationTimeUs(outputPresentationTimeUs)
              .setMetadata(
                  stripKeys(
                      primaryInputFrame.getMetadata(),
                      KEY_COMPOSITION_SEQUENCE_INDEX,
                      KEY_COMPOSITOR_SETTINGS))
              .setFormat(
                  primaryInputFrame
                      .format
                      .buildUpon()
                      .setWidth(outputTexture.width)
                      .setHeight(outputTexture.height)
                      .build())
              .build();

      pendingOutputFrame = outputGlTextureFrame;
    } catch (GlException | VideoFrameProcessingException | RuntimeException e) {
      if (outputTexture != null) {
        outputTexturePool.freeTexture(outputTexture);
      }
      throw VideoFrameProcessingException.from(e);
    } finally {
      for (int i = 0; i < frames.size(); i++) {
        frames.get(i).release(/* releaseFence= */ null);
      }
    }

    tryQueueToDownstream();
    // Returning true because the input frames are accepted. If queueing to downstream fails, it'll
    // be retried.
    return true;
  }

  /** Notifies the current stream has ended. */
  public void signalEndOfStream() {
    inputStreamEnded = true;
    if (pendingOutputFrame == null) {
      downstreamConsumer.signalEndOfStream();
    }
  }

  @Override
  public void close() throws VideoFrameProcessingException {
    inputStreamEnded = false;
    runAllAndAccumulateExceptions(
        compositorGlProgram::release,
        () -> {
          if (pendingOutputFrame != null) {
            pendingOutputFrame.release(/* releaseFence= */ null);
          }
        },
        outputTexturePool::deleteAllTextures);
  }

  private void tryQueueToDownstream() {
    if (pendingOutputFrame == null) {
      return;
    }
    boolean queued;
    try {
      // TODO: b/517424999 - Unify the listeners to follow the same pattern as
      //  FrameProcessor.
      queued =
          downstreamConsumer.queue(
              pendingOutputFrame, glExecutor, /* wakeupListener= */ this::tryQueueToDownstream);
    } catch (RuntimeException | VideoFrameProcessingException e) {
      if (pendingOutputFrame != null) {
        pendingOutputFrame.release(/* releaseFence= */ null);
        pendingOutputFrame = null;
      }
      errorConsumer.accept(VideoFrameProcessingException.from(e));
      return;
    }
    if (queued) {
      pendingOutputFrame = null;
      maybeInvokeWakeupListener();
      if (inputStreamEnded) {
        downstreamConsumer.signalEndOfStream();
      }
    }
  }

  private void maybeInvokeWakeupListener() {
    Runnable listener;
    Executor executor;
    synchronized (lock) {
      if (pendingWakeupListener == null || pendingWakeupExecutor == null) {
        return;
      }
      listener = pendingWakeupListener;
      executor = pendingWakeupExecutor;
      pendingWakeupListener = null;
      pendingWakeupExecutor = null;
    }
    executor.execute(
        () -> {
          try {
            listener.run();
          } catch (RuntimeException e) {
            errorConsumer.accept(VideoFrameProcessingException.from(e));
          }
        });
  }

  private static ImmutableMap<String, Object> stripKeys(
      Map<String, Object> metadata, String... keys) {
    HashMap<String, Object> strippedMetadata = new HashMap<>(metadata);
    for (String key : keys) {
      strippedMetadata.remove(key);
    }
    return ImmutableMap.copyOf(strippedMetadata);
  }
}
