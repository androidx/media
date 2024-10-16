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

import android.graphics.Bitmap;
import android.view.Surface;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.TimestampIterator;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * The default {@link VideoSink} implementation. This implementation renders video frames to an
 * output surface. Applying {@linkplain Effect video effects} is unsupported.
 */
/* package */ final class DefaultVideoSink implements VideoSink {

  private final VideoFrameReleaseControl videoFrameReleaseControl;
  private final VideoFrameRenderControl videoFrameRenderControl;

  private Format inputFormat;

  public DefaultVideoSink(
      VideoFrameReleaseControl videoFrameReleaseControl,
      VideoFrameRenderControl videoFrameRenderControl) {
    this.videoFrameReleaseControl = videoFrameReleaseControl;
    this.videoFrameRenderControl = videoFrameRenderControl;
    inputFormat = new Format.Builder().build();
  }

  @Override
  public void onRendererEnabled(boolean mayRenderStartOfStream) {
    videoFrameReleaseControl.onEnabled(mayRenderStartOfStream);
  }

  @Override
  public void onRendererDisabled() {
    videoFrameReleaseControl.onDisabled();
  }

  @Override
  public void onRendererStarted() {
    videoFrameReleaseControl.onStarted();
  }

  @Override
  public void onRendererStopped() {
    videoFrameReleaseControl.onStopped();
  }

  @Override
  public void setListener(Listener listener, Executor executor) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void initialize(Format sourceFormat) {
    // Do nothing as there is no initialization needed.
  }

  @Override
  public boolean isInitialized() {
    return true;
  }

  @Override
  public void flush(boolean resetPosition) {
    if (resetPosition) {
      videoFrameReleaseControl.reset();
    }
    videoFrameRenderControl.flush();
  }

  @Override
  public boolean isReady(boolean rendererOtherwiseReady) {
    return videoFrameReleaseControl.isReady(rendererOtherwiseReady);
  }

  @Override
  public boolean isEnded() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Surface getInputSurface() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setVideoFrameMetadataListener(VideoFrameMetadataListener videoFrameMetadataListener) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setPlaybackSpeed(float speed) {
    videoFrameReleaseControl.setPlaybackSpeed(speed);
  }

  @Override
  public void setVideoEffects(List<Effect> videoEffects) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setPendingVideoEffects(List<Effect> videoEffects) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setStreamTimestampInfo(
      long streamStartPositionUs,
      long streamOffsetUs,
      long bufferTimestampAdjustmentUs,
      long lastResetPositionUs) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setOutputSurfaceInfo(Surface outputSurface, Size outputResolution) {
    videoFrameReleaseControl.setOutputSurface(outputSurface);
  }

  @Override
  public void clearOutputSurfaceInfo() {
    videoFrameReleaseControl.setOutputSurface(/* outputSurface= */ null);
  }

  @Override
  public void setChangeFrameRateStrategy(int changeFrameRateStrategy) {
    videoFrameReleaseControl.setChangeFrameRateStrategy(changeFrameRateStrategy);
  }

  @Override
  public void enableMayRenderStartOfStream() {
    videoFrameReleaseControl.allowReleaseFirstFrameBeforeStarted();
  }

  @Override
  public void onInputStreamChanged(@InputType int inputType, Format format) {
    if (format.width != inputFormat.width || format.height != inputFormat.height) {
      videoFrameRenderControl.onOutputSizeChanged(format.width, format.height);
    }
    inputFormat = format;
  }

  @Override
  public boolean handleInputFrame(
      long framePresentationTimeUs,
      boolean isLastFrame,
      long positionUs,
      long elapsedRealtimeUs,
      VideoFrameHandler videoFrameHandler) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean handleInputBitmap(Bitmap inputBitmap, TimestampIterator timestampIterator) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void render(long positionUs, long elapsedRealtimeUs) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void join(boolean renderNextFrameImmediately) {
    videoFrameReleaseControl.join(renderNextFrameImmediately);
  }

  @Override
  public void release() {}
}
