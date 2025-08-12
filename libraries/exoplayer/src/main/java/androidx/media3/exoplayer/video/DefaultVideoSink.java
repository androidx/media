/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.exoplayer.video;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.graphics.Bitmap;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.exoplayer.ExoPlaybackException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * The default {@link VideoSink} implementation. This implementation renders video frames to an
 * output surface.
 *
 * <p>The following operations are not supported:
 *
 * <ul>
 *   <li>Applying video effects
 *   <li>Inputting bitmaps
 *   <li>Redrawing
 *   <li>Setting a buffer timestamp adjustment
 * </ul>
 *
 * <p>The {@linkplain #getInputSurface() input} and {@linkplain #setOutputSurfaceInfo(Surface, Size)
 * output} surfaces are the same.
 */
/* package */ final class DefaultVideoSink implements VideoSink {

  private final VideoFrameReleaseControl videoFrameReleaseControl;
  private final VideoFrameReleaseEarlyTimeForecaster videoFrameReleaseEarlyTimeForecaster;
  private final VideoFrameRenderControl videoFrameRenderControl;
  private final Queue<VideoFrameHandler> videoFrameHandlers;

  @Nullable private Surface outputSurface;
  private Format inputFormat;
  private long streamStartPositionUs;
  private Listener listener;
  private Executor listenerExecutor;
  private VideoFrameMetadataListener videoFrameMetadataListener;

  public DefaultVideoSink(
      VideoFrameReleaseControl videoFrameReleaseControl,
      VideoFrameReleaseEarlyTimeForecaster videoFrameReleaseEarlyTimeForecaster,
      Clock clock) {
    this.videoFrameReleaseControl = videoFrameReleaseControl;
    this.videoFrameReleaseEarlyTimeForecaster = videoFrameReleaseEarlyTimeForecaster;
    videoFrameReleaseControl.setClock(clock);
    videoFrameRenderControl =
        new VideoFrameRenderControl(
            new FrameRendererImpl(),
            videoFrameReleaseControl,
            videoFrameReleaseEarlyTimeForecaster);
    videoFrameHandlers = new ArrayDeque<>();
    inputFormat = new Format.Builder().build();
    streamStartPositionUs = C.TIME_UNSET;
    listener = Listener.NO_OP;
    listenerExecutor = runnable -> {};
    videoFrameMetadataListener = (presentationTimeUs, releaseTimeNs, format, mediaFormat) -> {};
  }

  @Override
  public void startRendering() {
    videoFrameReleaseEarlyTimeForecaster.reset();
    videoFrameReleaseControl.onStarted();
  }

  @Override
  public void stopRendering() {
    videoFrameReleaseEarlyTimeForecaster.reset();
    videoFrameReleaseControl.onStopped();
  }

  @Override
  public void setListener(Listener listener, Executor executor) {
    this.listener = listener;
    this.listenerExecutor = executor;
  }

  @Override
  public boolean initialize(Format sourceFormat) {
    // Do nothing as there is no initialization needed.
    return true;
  }

  @Override
  public boolean isInitialized() {
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * <p>This method will always throw an {@link UnsupportedOperationException}.
   */
  @Override
  public void redraw() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void flush(boolean resetPosition) {
    if (resetPosition) {
      videoFrameReleaseControl.reset();
    }
    videoFrameReleaseEarlyTimeForecaster.reset();
    videoFrameRenderControl.flush();
    videoFrameHandlers.clear();
  }

  @Override
  public boolean isReady(boolean otherwiseReady) {
    return videoFrameReleaseControl.isReady(otherwiseReady);
  }

  @Override
  public void signalEndOfCurrentInputStream() {
    videoFrameRenderControl.signalEndOfInput();
  }

  @Override
  public void signalEndOfInput() {
    // Ignored.
  }

  @Override
  public boolean isEnded() {
    return videoFrameRenderControl.isEnded();
  }

  @Override
  public Surface getInputSurface() {
    return checkNotNull(outputSurface);
  }

  @Override
  public void setVideoFrameMetadataListener(VideoFrameMetadataListener videoFrameMetadataListener) {
    this.videoFrameMetadataListener = videoFrameMetadataListener;
  }

  @Override
  public void setPlaybackSpeed(float speed) {
    videoFrameReleaseControl.setPlaybackSpeed(speed);
  }

  /**
   * {@inheritDoc}
   *
   * <p>This method will always throw an {@link UnsupportedOperationException}.
   */
  @Override
  public void setVideoEffects(List<Effect> videoEffects) {
    throw new UnsupportedOperationException();
  }

  /**
   * {@inheritDoc}
   *
   * <p>This method will always throw an {@link UnsupportedOperationException}.
   */
  @Override
  public void setBufferTimestampAdjustmentUs(long bufferTimestampAdjustmentUs) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setOutputSurfaceInfo(Surface outputSurface, Size outputResolution) {
    this.outputSurface = outputSurface;
    videoFrameReleaseControl.setOutputSurface(outputSurface);
  }

  @Override
  public void clearOutputSurfaceInfo() {
    outputSurface = null;
    videoFrameReleaseControl.setOutputSurface(/* outputSurface= */ null);
  }

  @Override
  public void setChangeFrameRateStrategy(int changeFrameRateStrategy) {
    videoFrameReleaseControl.setChangeFrameRateStrategy(changeFrameRateStrategy);
  }

  /**
   * {@inheritDoc}
   *
   * <p>{@code videoEffects} is required to be empty
   */
  @Override
  public void onInputStreamChanged(
      @InputType int inputType,
      Format format,
      long startPositionUs,
      @FirstFrameReleaseInstruction int firstFrameReleaseInstruction,
      List<Effect> videoEffects) {
    checkState(videoEffects.isEmpty());
    if (format.width != inputFormat.width || format.height != inputFormat.height) {
      videoFrameRenderControl.onVideoSizeChanged(format.width, format.height);
    }
    if (format.frameRate != inputFormat.frameRate) {
      videoFrameReleaseControl.setFrameRate(format.frameRate);
    }
    inputFormat = format;
    if (startPositionUs != this.streamStartPositionUs) {
      videoFrameRenderControl.onStreamChanged(firstFrameReleaseInstruction, startPositionUs);
      this.streamStartPositionUs = startPositionUs;
    }
  }

  @Override
  public void allowReleaseFirstFrameBeforeStarted() {
    videoFrameReleaseControl.allowReleaseFirstFrameBeforeStarted();
  }

  @Override
  public boolean handleInputFrame(
      long framePresentationTimeUs, VideoFrameHandler videoFrameHandler) {
    videoFrameHandlers.add(videoFrameHandler);
    videoFrameRenderControl.onFrameAvailableForRendering(framePresentationTimeUs);
    listenerExecutor.execute(() -> listener.onFrameAvailableForRendering());
    return true;
  }

  /**
   * {@inheritDoc}
   *
   * <p>This method will always throw an {@link UnsupportedOperationException}.
   */
  @Override
  public boolean handleInputBitmap(Bitmap inputBitmap, TimestampIterator bufferTimestampIterator) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void render(long positionUs, long elapsedRealtimeUs) throws VideoSinkException {
    try {
      videoFrameRenderControl.render(positionUs, elapsedRealtimeUs);
    } catch (ExoPlaybackException e) {
      throw new VideoSinkException(e, inputFormat);
    }
  }

  @Override
  public void join(boolean renderNextFrameImmediately) {
    videoFrameReleaseControl.join(renderNextFrameImmediately);
  }

  @Override
  public void release() {}

  private final class FrameRendererImpl implements VideoFrameRenderControl.FrameRenderer {

    private @MonotonicNonNull Format outputFormat;

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
      outputFormat =
          new Format.Builder()
              .setWidth(videoSize.width)
              .setHeight(videoSize.height)
              .setSampleMimeType(MimeTypes.VIDEO_RAW)
              .build();
      listenerExecutor.execute(() -> listener.onVideoSizeChanged(videoSize));
    }

    @Override
    public void renderFrame(long renderTimeNs, long framePresentationTimeUs, boolean isFirstFrame) {
      if (isFirstFrame && outputSurface != null) {
        listenerExecutor.execute(() -> listener.onFirstFrameRendered());
      }
      // TODO - b/292111083: outputFormat is initialized after the first frame is rendered because
      //  onVideoSizeChanged is announced after the first frame is available for rendering.
      Format format = outputFormat == null ? new Format.Builder().build() : outputFormat;
      videoFrameMetadataListener.onVideoFrameAboutToBeRendered(
          /* presentationTimeUs= */ framePresentationTimeUs,
          /* releaseTimeNs= */ renderTimeNs,
          format,
          /* mediaFormat= */ null);
      videoFrameHandlers.remove().render(renderTimeNs);
    }

    @Override
    public void dropFrame() {
      listenerExecutor.execute(() -> listener.onFrameDropped());
      videoFrameHandlers.remove().skip();
    }
  }
}
