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

import static androidx.media3.common.VideoFrameProcessor.INPUT_TYPE_BITMAP;
import static androidx.media3.effect.DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_DEFAULT;
import static com.google.common.base.Preconditions.checkState;

import android.content.Context;
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.FrameInfo;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ConstantRateTimestampIterator;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.util.TimestampIterator;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link FrameProcessor} implementation that converts a {@link BitmapFrame} to a {@link
 * GlTextureFrame}.
 *
 * <p>This class is experimental and subject to change.
 */
/* package */ final class BitmapToGlTextureFrameProcessor
    implements FrameProcessor<BitmapFrame, GlTextureFrame>,
        GlShaderProgram.OutputListener,
        GlShaderProgram.ErrorListener {

  /**
   * Creates a new instance.
   *
   * <p>Must be called on the {@linkplain #glThreadExecutorService GL thread}.
   */
  public static BitmapToGlTextureFrameProcessor create(
      Context context,
      ListeningExecutorService glThreadExecutorService,
      GlObjectsProvider glObjectsProvider,
      ColorInfo inputColorInfo,
      ColorInfo outputColorInfo,
      Consumer<VideoFrameProcessingException> onError)
      throws VideoFrameProcessingException {
    VideoFrameProcessingTaskExecutor videoFrameProcessingTaskExecutor =
        new VideoFrameProcessingTaskExecutor(
            glThreadExecutorService, /* shouldShutdownExecutorService= */ false, onError::accept);
    BitmapTextureManager bitmapTextureManager =
        new BitmapTextureManager(
            glObjectsProvider,
            videoFrameProcessingTaskExecutor,
            /* signalRepeatingSequence= */ false);
    DefaultShaderProgram samplingGlShaderProgram =
        DefaultShaderProgram.createWithInternalSampler(
            context,
            inputColorInfo,
            outputColorInfo,
            WORKING_COLOR_SPACE_DEFAULT,
            INPUT_TYPE_BITMAP);
    return create(
        glThreadExecutorService, bitmapTextureManager, samplingGlShaderProgram, outputColorInfo);
  }

  @VisibleForTesting
  /* package */ static BitmapToGlTextureFrameProcessor create(
      ListeningExecutorService glThreadExecutorService,
      TextureManager bitmapTextureManager,
      GlShaderProgram samplingGlShaderProgram,
      ColorInfo outputColorInfo) {
    BitmapToGlTextureFrameProcessor processor =
        new BitmapToGlTextureFrameProcessor(
            glThreadExecutorService,
            bitmapTextureManager,
            samplingGlShaderProgram,
            outputColorInfo);
    bitmapTextureManager.setSamplingGlShaderProgram(samplingGlShaderProgram);
    samplingGlShaderProgram.setOutputListener(processor);
    samplingGlShaderProgram.setInputListener(bitmapTextureManager);
    return processor;
  }

  private final ListeningExecutorService glThreadExecutorService;
  private final TextureManager textureManager;
  private final GlShaderProgram samplingGlShaderProgram;
  private final ColorInfo outputColorInfo;
  private final InputConsumer inputConsumer;
  private final Queue<GlTextureFrame> processedFrames;
  private final AtomicReference<
          @NullableType Pair<Executor, Consumer<VideoFrameProcessingException>>>
      onErrorCallback;
  private final AtomicBoolean isReleased;

  /**
   * Atomic because this is set on thread that calls {@link InputConsumer#queueFrame}, but read on
   * the GL thread.
   */
  private final AtomicReference<@NullableType BitmapFrame> currentInputFrame;

  @Nullable private FrameConsumer<GlTextureFrame> downstreamConsumer;

  /**
   * Creates a new instance.
   *
   * <p>Must be called on the {@linkplain #glThreadExecutorService GL thread}.
   */
  private BitmapToGlTextureFrameProcessor(
      ListeningExecutorService executorService,
      TextureManager textureManager,
      GlShaderProgram samplingGlShaderProgram,
      ColorInfo outputColorInfo) {
    this.glThreadExecutorService = executorService;
    this.textureManager = textureManager;
    this.samplingGlShaderProgram = samplingGlShaderProgram;
    this.outputColorInfo = outputColorInfo;
    this.inputConsumer = new InputConsumer();
    this.processedFrames = new ArrayDeque<>();
    this.currentInputFrame = new AtomicReference<>();
    this.onErrorCallback = new AtomicReference<>();
    this.isReleased = new AtomicBoolean();
  }

  @Override
  public FrameConsumer<BitmapFrame> getInput() {
    checkState(!isReleased.get());
    return inputConsumer;
  }

  @Override
  public ListenableFuture<Void> setOutputAsync(
      @Nullable FrameConsumer<GlTextureFrame> nextOutputConsumer) {
    checkState(!isReleased.get());
    return Futures.submit(() -> setOutputInternal(nextOutputConsumer), glThreadExecutorService);
  }

  @Override
  public ListenableFuture<Void> releaseAsync() {
    if (!isReleased.compareAndSet(false, true)) {
      return Futures.immediateVoidFuture();
    }
    return glThreadExecutorService.submit(
        () -> {
          releaseInternal();
          return null;
        });
  }

  @Override
  public void setOnErrorCallback(
      Executor executor, Consumer<VideoFrameProcessingException> onErrorCallback) {
    this.onErrorCallback.set(new Pair<>(executor, onErrorCallback));
  }

  @Override
  public void clearOnErrorCallback() {
    onErrorCallback.set(null);
  }

  @Override
  public void onCurrentOutputStreamEnded() {
    @Nullable BitmapFrame currentInputFrame = this.currentInputFrame.getAndSet(null);
    if (currentInputFrame != null) {
      currentInputFrame.release();
    }
    inputConsumer.notifyCapacityListener();
  }

  @Override
  public void onOutputFrameAvailable(GlTextureInfo outputTexture, long presentationTimeUs) {
    @Nullable BitmapFrame inputFrame = currentInputFrame.get();
    checkState(inputFrame != null);
    // This method receives the sampled texture from the sampling GlShaderProgram. Combine this
    // texture with the passed in metadata to create the final output frame.
    Format inputFormat = inputFrame.getMetadata().getFormat();
    Format outputFormat = inputFormat.buildUpon().setColorInfo(outputColorInfo).build();
    GlTextureFrame outputFrame =
        new GlTextureFrame.Builder(
                outputTexture,
                glThreadExecutorService,
                /* releaseTextureCallback= */ samplingGlShaderProgram::releaseOutputFrame)
            .setPresentationTimeUs(presentationTimeUs)
            .setFormat(outputFormat)
            .build();
    processedFrames.add(outputFrame);
    maybeDrainProcessedFrames();
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
    if (this.downstreamConsumer != null) {
      this.downstreamConsumer.setOnCapacityAvailableCallback(
          glThreadExecutorService, this::maybeDrainProcessedFrames);
    }
  }

  private void releaseInternal() throws VideoFrameProcessingException {
    @Nullable BitmapFrame currentFrame = currentInputFrame.get();
    if (currentFrame != null) {
      currentFrame.release();
    }
    @Nullable GlTextureFrame currentProcessedFrame = processedFrames.poll();
    while (currentProcessedFrame != null) {
      currentProcessedFrame.release();
      currentProcessedFrame = processedFrames.poll();
    }
    textureManager.release();
    samplingGlShaderProgram.release();
  }

  private void maybeDrainProcessedFrames() {
    if (isReleased.get()) {
      return;
    }
    @Nullable GlTextureFrame nextFrame = processedFrames.peek();
    while (nextFrame != null) {
      if (downstreamConsumer == null || !downstreamConsumer.queueFrame(nextFrame)) {
        return;
      }
      processedFrames.poll();
      nextFrame = processedFrames.peek();
    }
  }

  @Override
  public void onError(VideoFrameProcessingException e) {
    Pair<Executor, Consumer<VideoFrameProcessingException>> errorCallbackPair =
        onErrorCallback.get();
    if (errorCallbackPair != null) {
      errorCallbackPair.first.execute(() -> errorCallbackPair.second.accept(e));
    }
  }

  private class InputConsumer implements FrameConsumer<BitmapFrame> {

    private final AtomicReference<@NullableType Pair<Executor, Runnable>>
        onCapacityAvailableCallbackReference;

    public InputConsumer() {
      onCapacityAvailableCallbackReference = new AtomicReference<>(null);
    }

    @Override
    public boolean queueFrame(BitmapFrame frame) {
      checkState(!isReleased.get());
      if (!currentInputFrame.compareAndSet(null, frame)) {
        return false;
      }
      FrameInfo frameInfo = new FrameInfo(frame.getMetadata().getFormat(), /* offsetToAddUs= */ 0);
      // TODO: b/430250432 - Allow timestamp iteration to be input.
      // Create a single frame from the input Bitmap.
      TimestampIterator timestampIterator =
          new ConstantRateTimestampIterator(
              /* startPositionUs= */ frame.getMetadata().getPresentationTimeUs(),
              /* endPositionUs= */ frame.getMetadata().getPresentationTimeUs() + 1,
              /* frameRate= */ 1);
      textureManager.queueInputBitmap(frame.getBitmap(), frameInfo, timestampIterator);
      textureManager.signalEndOfCurrentInputStream();
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
      if (isReleased.get()) {
        return;
      }
      @Nullable
      Pair<Executor, Runnable> onCapacityAvailableCallbackPair =
          onCapacityAvailableCallbackReference.get();
      if (onCapacityAvailableCallbackPair != null) {
        onCapacityAvailableCallbackPair.first.execute(onCapacityAvailableCallbackPair.second);
      }
    }
  }
}
