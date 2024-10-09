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
package androidx.media3.transformer;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.exoplayer.video.PlaceholderSurface;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.exoplayer.video.VideoSink;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A {@link VideoSink} that delays the operations performed on it until it {@linkplain
 * #setVideoSink(VideoSink) receives} a sink.
 */
/* package */ final class BufferingVideoSink implements VideoSink {

  private final Context context;
  private final List<ThrowingVideoSinkOperation> pendingOperations;

  @Nullable private VideoSink videoSink;
  private boolean isInitialized;
  private @MonotonicNonNull PlaceholderSurface placeholderSurface;

  public BufferingVideoSink(Context context) {
    this.context = context;
    pendingOperations = new ArrayList<>();
  }

  /**
   * Sets the {@link VideoSink} to execute the pending and future operations on.
   *
   * @param videoSink The {@link VideoSink} to execute the operations on.
   * @throws VideoSinkException If an error occurred executing the pending operations on the sink.
   */
  public void setVideoSink(VideoSink videoSink) throws VideoSinkException {
    this.videoSink = videoSink;
    for (int i = 0; i < pendingOperations.size(); i++) {
      pendingOperations.get(i).execute(videoSink);
    }
    pendingOperations.clear();
  }

  /**
   * Removes the underlying {@link VideoSink} if it is {@linkplain #setVideoSink(VideoSink) set}.
   */
  public void removeVideoSink() {
    this.videoSink = null;
  }

  /** Returns the underlying {@link VideoSink} or {@code null} if there is none. */
  @Nullable
  public VideoSink getVideoSink() {
    return videoSink;
  }

  /** Clears the pending operations. */
  public void clearPendingOperations() {
    pendingOperations.clear();
  }

  @Override
  public void onRendererEnabled(boolean mayRenderStartOfStream) {
    executeOrDelay(videoSink -> videoSink.onRendererEnabled(mayRenderStartOfStream));
  }

  @Override
  public void onRendererDisabled() {
    executeOrDelay(VideoSink::onRendererDisabled);
  }

  @Override
  public void onRendererStarted() {
    executeOrDelay(VideoSink::onRendererStarted);
  }

  @Override
  public void onRendererStopped() {
    executeOrDelay(VideoSink::onRendererStopped);
  }

  @Override
  public void setListener(Listener listener, Executor executor) {
    executeOrDelay(videoSink -> videoSink.setListener(listener, executor));
  }

  @Override
  public void initialize(Format sourceFormat) throws VideoSinkException {
    executeOrDelayThrowing(
        videoSink -> {
          if (videoSink.isInitialized()) {
            return;
          }
          videoSink.initialize(sourceFormat);
        });
    isInitialized = true;
  }

  @Override
  public boolean isInitialized() {
    return isInitialized;
  }

  @Override
  public void flush(boolean resetPosition) {
    executeOrDelay(videoSink -> videoSink.flush(resetPosition));
  }

  @Override
  public boolean isReady(boolean rendererOtherwiseReady) {
    return videoSink == null || videoSink.isReady(rendererOtherwiseReady);
  }

  @Override
  public boolean isEnded() {
    return videoSink != null && videoSink.isEnded();
  }

  /**
   * {@inheritDoc}
   *
   * <p>A {@link PlaceholderSurface} is returned if the {@linkplain #setVideoSink(VideoSink)
   * underlying sink} is {@code null}.
   */
  @Override
  public Surface getInputSurface() {
    return videoSink == null ? getPlaceholderSurface() : videoSink.getInputSurface();
  }

  @Override
  public void setVideoFrameMetadataListener(VideoFrameMetadataListener videoFrameMetadataListener) {
    executeOrDelay(
        videoSink -> videoSink.setVideoFrameMetadataListener(videoFrameMetadataListener));
  }

  @Override
  public void setPlaybackSpeed(float speed) {
    executeOrDelay(videoSink -> videoSink.setPlaybackSpeed(speed));
  }

  @Override
  public void setVideoEffects(List<Effect> videoEffects) {
    executeOrDelay(videoSink -> videoSink.setVideoEffects(videoEffects));
  }

  @Override
  public void setPendingVideoEffects(List<Effect> videoEffects) {
    executeOrDelay(videoSink -> videoSink.setPendingVideoEffects(videoEffects));
  }

  @Override
  public void setStreamTimestampInfo(
      long streamStartPositionUs,
      long streamOffsetUs,
      long bufferTimestampAdjustmentUs,
      long lastResetPositionUs) {
    executeOrDelay(
        videoSink ->
            videoSink.setStreamTimestampInfo(
                streamStartPositionUs,
                streamOffsetUs,
                bufferTimestampAdjustmentUs,
                lastResetPositionUs));
  }

  @Override
  public void setOutputSurfaceInfo(Surface outputSurface, Size outputResolution) {
    executeOrDelay(videoSink -> videoSink.setOutputSurfaceInfo(outputSurface, outputResolution));
  }

  @Override
  public void clearOutputSurfaceInfo() {
    executeOrDelay(VideoSink::clearOutputSurfaceInfo);
  }

  @Override
  public void setChangeFrameRateStrategy(int changeFrameRateStrategy) {
    executeOrDelay(videoSink -> videoSink.setChangeFrameRateStrategy(changeFrameRateStrategy));
  }

  @Override
  public void enableMayRenderStartOfStream() {
    executeOrDelay(VideoSink::enableMayRenderStartOfStream);
  }

  @Override
  public void onInputStreamChanged(@InputType int inputType, Format format) {
    executeOrDelay(videoSink -> videoSink.onInputStreamChanged(inputType, format));
  }

  @Override
  public boolean handleInputFrame(
      long framePresentationTimeUs,
      boolean isLastFrame,
      long positionUs,
      long elapsedRealtimeUs,
      VideoFrameHandler videoFrameHandler)
      throws VideoSinkException {
    return videoSink != null
        && videoSink.handleInputFrame(
            framePresentationTimeUs, isLastFrame, positionUs, elapsedRealtimeUs, videoFrameHandler);
  }

  @Override
  public boolean handleInputBitmap(Bitmap inputBitmap, TimestampIterator timestampIterator) {
    return videoSink != null && videoSink.handleInputBitmap(inputBitmap, timestampIterator);
  }

  @Override
  public void render(long positionUs, long elapsedRealtimeUs) throws VideoSinkException {
    if (videoSink != null) {
      videoSink.render(positionUs, elapsedRealtimeUs);
    }
  }

  @Override
  public void join(boolean renderNextFrameImmediately) {
    executeOrDelay(videoSink -> videoSink.join(renderNextFrameImmediately));
  }

  @Override
  public void release() {
    executeOrDelay(VideoSink::release);
    if (placeholderSurface != null) {
      placeholderSurface.release();
    }
  }

  private void executeOrDelay(VideoSinkOperation operation) {
    if (videoSink != null) {
      operation.execute(videoSink);
    } else {
      pendingOperations.add(operation);
    }
  }

  private void executeOrDelayThrowing(ThrowingVideoSinkOperation operation)
      throws VideoSinkException {
    if (videoSink != null) {
      operation.execute(videoSink);
    } else {
      pendingOperations.add(operation);
    }
  }

  private PlaceholderSurface getPlaceholderSurface() {
    if (placeholderSurface == null) {
      placeholderSurface = PlaceholderSurface.newInstance(context, /* secure= */ false);
    }
    return placeholderSurface;
  }

  private interface ThrowingVideoSinkOperation {

    void execute(VideoSink videoSink) throws VideoSinkException;
  }

  private interface VideoSinkOperation extends ThrowingVideoSinkOperation {

    @Override
    void execute(VideoSink videoSink);
  }
}
