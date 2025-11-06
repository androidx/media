/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.transformer;

import android.graphics.Bitmap;
import android.view.Surface;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.exoplayer.video.VideoSink;
import java.util.List;
import java.util.concurrent.Executor;

/** A {@link VideoSink} that forwards all calls to another {@link VideoSink}. */
@UnstableApi
/* package */ class ForwardingVideoSink implements VideoSink {

  private final VideoSink videoSink;

  /** Creates a new instance. */
  public ForwardingVideoSink(VideoSink videoSink) {
    this.videoSink = videoSink;
  }

  @Override
  public void startRendering() {
    videoSink.startRendering();
  }

  @Override
  public void stopRendering() {
    videoSink.stopRendering();
  }

  @Override
  public void setListener(Listener listener, Executor executor) {
    videoSink.setListener(listener, executor);
  }

  @Override
  public boolean initialize(Format sourceFormat) throws VideoSinkException {
    return videoSink.initialize(sourceFormat);
  }

  @Override
  public boolean isInitialized() {
    return videoSink.isInitialized();
  }

  @Override
  public void redraw() {
    videoSink.redraw();
  }

  @Override
  public void flush(boolean resetPosition) {
    videoSink.flush(resetPosition);
  }

  @Override
  public boolean isReady(boolean otherwiseReady) {
    return videoSink.isReady(otherwiseReady);
  }

  @Override
  public void signalEndOfCurrentInputStream() {
    videoSink.signalEndOfCurrentInputStream();
  }

  @Override
  public void signalEndOfInput() {
    videoSink.signalEndOfInput();
  }

  @Override
  public boolean isEnded() {
    return videoSink.isEnded();
  }

  @Override
  public Surface getInputSurface() {
    return videoSink.getInputSurface();
  }

  @Override
  public void setVideoFrameMetadataListener(VideoFrameMetadataListener videoFrameMetadataListener) {
    videoSink.setVideoFrameMetadataListener(videoFrameMetadataListener);
  }

  @Override
  public void setPlaybackSpeed(float speed) {
    videoSink.setPlaybackSpeed(speed);
  }

  @Override
  public void setVideoEffects(List<Effect> videoEffects) {
    videoSink.setVideoEffects(videoEffects);
  }

  @Override
  public void setBufferTimestampAdjustmentUs(long bufferTimestampAdjustmentUs) {
    videoSink.setBufferTimestampAdjustmentUs(bufferTimestampAdjustmentUs);
  }

  @Override
  public void setOutputSurfaceInfo(Surface outputSurface, Size outputResolution) {
    videoSink.setOutputSurfaceInfo(outputSurface, outputResolution);
  }

  @Override
  public void clearOutputSurfaceInfo() {
    videoSink.clearOutputSurfaceInfo();
  }

  @Override
  public void setChangeFrameRateStrategy(int changeFrameRateStrategy) {
    videoSink.setChangeFrameRateStrategy(changeFrameRateStrategy);
  }

  @Override
  public void onInputStreamChanged(
      @InputType int inputType,
      Format format,
      long startPositionUs,
      @FirstFrameReleaseInstruction int firstFrameReleaseInstruction,
      List<Effect> videoEffects) {
    videoSink.onInputStreamChanged(
        inputType, format, startPositionUs, firstFrameReleaseInstruction, videoEffects);
  }

  @Override
  public void allowReleaseFirstFrameBeforeStarted() {
    videoSink.allowReleaseFirstFrameBeforeStarted();
  }

  @Override
  public boolean handleInputFrame(
      long bufferPresentationTimeUs, VideoFrameHandler videoFrameHandler) {
    return videoSink.handleInputFrame(bufferPresentationTimeUs, videoFrameHandler);
  }

  @Override
  public boolean handleInputBitmap(Bitmap inputBitmap, TimestampIterator bufferTimestampIterator) {
    return videoSink.handleInputBitmap(inputBitmap, bufferTimestampIterator);
  }

  @Override
  public void render(long positionUs, long elapsedRealtimeUs) throws VideoSinkException {
    videoSink.render(positionUs, elapsedRealtimeUs);
  }

  @Override
  public void join(boolean renderNextFrameImmediately) {
    videoSink.join(renderNextFrameImmediately);
  }

  @Override
  public void release() {
    videoSink.release();
  }
}
