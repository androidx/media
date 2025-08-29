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

import static com.google.common.base.Preconditions.checkState;

import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.NullableType;
import androidx.media3.effect.GlTextureFrame.Metadata;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link FrameProcessor} implementation that operates on {@link GlTextureFrame}s and wraps a
 * {@link GlShaderProgram}.
 *
 * <p>This class is experimental and subject to change.
 *
 * <p>The internal {@link GlShaderProgram} must output one frame for every input frame.
 */
/* package */ class GlShaderProgramFrameProcessor
    implements FrameProcessor<GlTextureFrame, GlTextureFrame>,
        GlShaderProgram.InputListener,
        GlShaderProgram.OutputListener,
        GlShaderProgram.ErrorListener {

  /**
   * Creates a new {@link GlShaderProgramFrameProcessor}.
   *
   * <p>Must be called on the GL thread.
   */
  public static GlShaderProgramFrameProcessor create(
      ListeningExecutorService executorService,
      GlShaderProgram shaderProgram,
      GlObjectsProvider glObjectsProvider) {
    GlShaderProgramFrameProcessor processor =
        new GlShaderProgramFrameProcessor(executorService, shaderProgram, glObjectsProvider);
    shaderProgram.setInputListener(processor);
    shaderProgram.setOutputListener(processor);
    shaderProgram.setErrorListener(executorService, processor);
    return processor;
  }

  private final ListeningExecutorService glThreadExecutorService;
  private final InputConsumer inputConsumer;

  /** Only accessed on the GL thread. */
  private final GlShaderProgram shaderProgram;

  /** Only accessed on the GL thread. */
  private final GlObjectsProvider glObjectsProvider;

  /** Atomic because this needs to be read on the queueing thread but set on the GL thread. */
  private final AtomicBoolean canAcceptInput;

  private final AtomicReference<
          @NullableType Pair<Executor, Consumer<VideoFrameProcessingException>>>
      onErrorCallbackReference;

  /** Only accessed on the GL thread. */
  @Nullable private FrameConsumer<GlTextureFrame> downstreamConsumer;

  /** Only accessed on the GL thread. */
  @Nullable private GlTextureFrame currentInputFrame;

  /** Only accessed on the GL thread. */
  @Nullable private GlTextureFrame.Metadata currentInputMetadata;

  /** Only accessed on the GL thread. */
  @Nullable private GlTextureFrame currentProcessedFrame;

  /**
   * Creates a new {@link GlShaderProgramFrameProcessor}.
   *
   * <p>Must be called on the GL thread.
   */
  private GlShaderProgramFrameProcessor(
      ListeningExecutorService executorService,
      GlShaderProgram shaderProgram,
      GlObjectsProvider glObjectsProvider) {
    this.glThreadExecutorService = executorService;
    this.shaderProgram = shaderProgram;
    this.glObjectsProvider = glObjectsProvider;
    this.inputConsumer = new InputConsumer();
    this.canAcceptInput = new AtomicBoolean(false);
    this.onErrorCallbackReference = new AtomicReference<>(null);
  }

  @Override
  public FrameConsumer<GlTextureFrame> getInput() {
    return inputConsumer;
  }

  @Override
  public void setOutput(@Nullable FrameConsumer<GlTextureFrame> nextOutputConsumer) {
    Futures.addCallback(
        glThreadExecutorService.submit(
            () -> {
              setOutputInternal(nextOutputConsumer);
              return null;
            }),
        new FutureCallback<Object>() {
          @Override
          public void onSuccess(Object result) {}

          @Override
          public void onFailure(Throwable t) {
            onError(new VideoFrameProcessingException(t));
          }
        },
        glThreadExecutorService);
  }

  @Override
  public ListenableFuture<Void> releaseAsync() {
    return glThreadExecutorService.submit(
        () -> {
          releaseInternal();
          return null;
        });
  }

  @Override
  public void setOnErrorCallback(
      Executor executor, Consumer<VideoFrameProcessingException> onErrorCallback) {
    onErrorCallbackReference.set(new Pair<>(executor, onErrorCallback));
  }

  @Override
  public void clearOnErrorCallback() {
    onErrorCallbackReference.set(null);
  }

  // Methods called on the GL thread

  @Override
  public void onError(VideoFrameProcessingException e) {
    Pair<Executor, Consumer<VideoFrameProcessingException>> errorCallbackPair =
        onErrorCallbackReference.get();
    if (errorCallbackPair != null) {
      errorCallbackPair.first.execute(() -> errorCallbackPair.second.accept(e));
    }
  }

  @Override
  public void onReadyToAcceptInputFrame() {
    // Internal GlShaderProgram is expected to have a capacity of 1.
    checkState(canAcceptInput.compareAndSet(false, true));
    if (currentInputFrame != null) {
      currentInputFrame.release();
      currentInputFrame = null;
    }
    inputConsumer.notifyCapacityListener();
  }

  @Override
  public void onOutputFrameAvailable(GlTextureInfo outputTexture, long presentationTimeUs) {
    if (currentProcessedFrame != null) {
      onError(
          new VideoFrameProcessingException(
              new IllegalStateException(
                  "currentProcessedFrame is not null when onOutputFrameAvailable at"
                      + " presentationTimeUs: "
                      + presentationTimeUs)));
    }
    if (currentInputMetadata == null) {
      onError(
          new VideoFrameProcessingException(
              new IllegalStateException(
                  "currentInputMetadata is null when onOutputFrameAvailable at presentationTimeUs: "
                      + presentationTimeUs)));
      return;
    }
    // Use the presentationTimeUs from the GlShaderProgram in case it has modified it.
    GlTextureFrame.Metadata outputFrameMetadata =
        new Metadata(presentationTimeUs, currentInputMetadata.getFormat());
    currentProcessedFrame =
        new GlTextureFrame(
            outputTexture,
            outputFrameMetadata,
            glThreadExecutorService,
            shaderProgram::releaseOutputFrame);
    maybeForwardProcessedFrame();
  }

  private void setOutputInternal(@Nullable FrameConsumer<GlTextureFrame> nextOutputConsumer) {
    @Nullable FrameConsumer<GlTextureFrame> oldConsumer = this.downstreamConsumer;
    if (oldConsumer == nextOutputConsumer) {
      return;
    }
    if (oldConsumer != null) {
      oldConsumer.clearOnCapacityAvailableCallback();
    }
    this.downstreamConsumer = nextOutputConsumer;
    if (downstreamConsumer != null) {
      downstreamConsumer.setOnCapacityAvailableCallback(
          glThreadExecutorService, this::maybeForwardProcessedFrame);
    }
  }

  private void releaseInternal() throws VideoFrameProcessingException {
    if (currentInputFrame != null) {
      currentInputFrame.release();
    }
    if (currentProcessedFrame != null) {
      currentProcessedFrame.release();
    }
    shaderProgram.release();
  }

  private void maybeForwardProcessedFrame() {
    if (currentProcessedFrame != null
        && downstreamConsumer != null
        && downstreamConsumer.queueFrame(currentProcessedFrame)) {
      currentProcessedFrame = null;
    }
  }

  private class InputConsumer implements FrameConsumer<GlTextureFrame> {

    private final AtomicReference<@NullableType Pair<Executor, Runnable>>
        onCapacityAvailableCallbackReference;

    private InputConsumer() {
      onCapacityAvailableCallbackReference = new AtomicReference<>(null);
    }

    // Methods called on the queueing thread.

    @Override
    public boolean queueFrame(GlTextureFrame inputFrame) {
      if (!canAcceptInput.compareAndSet(true, false)) {
        return false;
      }
      Futures.addCallback(
          // TODO: b/430250432 - Cancel pending tasks on release.
          glThreadExecutorService.submit(
              () -> {
                GlTextureInfo nextFrameTextureInfo = inputFrame.getGlTextureInfo();
                GlTextureFrame.Metadata nextFrameMetadata = inputFrame.getMetadata();
                currentInputFrame = inputFrame;
                currentInputMetadata = inputFrame.getMetadata();
                shaderProgram.queueInputFrame(
                    glObjectsProvider,
                    nextFrameTextureInfo,
                    nextFrameMetadata.getPresentationTimeUs());
                return null;
              }),
          new FutureCallback<Object>() {
            @Override
            public void onSuccess(Object result) {}

            @Override
            public void onFailure(Throwable t) {
              onError(new VideoFrameProcessingException(t));
            }
          },
          glThreadExecutorService);
      return true;
    }

    @Override
    public void setOnCapacityAvailableCallback(
        Executor onCapacityAvailableExecutor, Runnable onCapacityAvailableCallback) {
      if (!onCapacityAvailableCallbackReference.compareAndSet(
          null, new Pair<>(onCapacityAvailableExecutor, onCapacityAvailableCallback))) {
        throw new IllegalStateException("onCapacityAvailableCallback already set");
      }
    }

    @Override
    public void clearOnCapacityAvailableCallback() {
      onCapacityAvailableCallbackReference.set(null);
    }

    // Called on the GL thread.
    private void notifyCapacityListener() {
      @Nullable
      Pair<Executor, Runnable> onCapacityAvailableCallbackPair =
          onCapacityAvailableCallbackReference.get();
      if (onCapacityAvailableCallbackPair != null) {
        onCapacityAvailableCallbackPair.first.execute(onCapacityAvailableCallbackPair.second);
      }
    }
  }
}
