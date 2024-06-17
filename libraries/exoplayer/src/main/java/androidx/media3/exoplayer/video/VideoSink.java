/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.exoplayer.video;

import static java.lang.annotation.ElementType.TYPE_USE;

import android.graphics.Bitmap;
import android.view.Surface;
import androidx.annotation.FloatRange;
import androidx.annotation.IntDef;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.common.util.UnstableApi;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * A sink that consumes decoded video frames from a video {@link
 * androidx.media3.exoplayer.Renderer}.
 */
@UnstableApi
public interface VideoSink {

  /** Thrown by {@link VideoSink} implementations. */
  final class VideoSinkException extends Exception {
    /**
     * The {@link Format} of the frames set to the {@link VideoSink} when this exception occurred.
     */
    public final Format format;

    /** Creates a new instance. */
    public VideoSinkException(Throwable cause, Format format) {
      super(cause);
      this.format = format;
    }
  }

  /** Listener for {@link VideoSink} events. */
  interface Listener {
    /** Called when the sink renderers the first frame. */
    void onFirstFrameRendered(VideoSink videoSink);

    /** Called when the sink dropped a frame. */
    void onFrameDropped(VideoSink videoSink);

    /**
     * Called before a frame is rendered for the first time since setting the surface, and each time
     * there's a change in the size, rotation or pixel aspect ratio of the video being rendered.
     */
    void onVideoSizeChanged(VideoSink videoSink, VideoSize videoSize);

    /** Called when the {@link VideoSink} encountered an error. */
    void onError(VideoSink videoSink, VideoSinkException videoSinkException);

    /** A no-op listener implementation. */
    Listener NO_OP =
        new Listener() {
          @Override
          public void onFirstFrameRendered(VideoSink videoSink) {}

          @Override
          public void onFrameDropped(VideoSink videoSink) {}

          @Override
          public void onVideoSizeChanged(VideoSink videoSink, VideoSize videoSize) {}

          @Override
          public void onError(VideoSink videoSink, VideoSinkException videoSinkException) {}
        };
  }

  /**
   * Specifies how the input frames are made available to the video sink. One of {@link
   * #INPUT_TYPE_SURFACE} or {@link #INPUT_TYPE_BITMAP}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({INPUT_TYPE_SURFACE, INPUT_TYPE_BITMAP})
  @interface InputType {}

  /** Input frames come from a {@link #getInputSurface surface}. */
  int INPUT_TYPE_SURFACE = 1;

  /** Input frames come from a {@link Bitmap}. */
  int INPUT_TYPE_BITMAP = 2;

  /** Called when the renderer is enabled. */
  void onRendererEnabled(boolean mayRenderStartOfStream);

  /** Called when the renderer is disabled. */
  void onRendererDisabled();

  /** Called when the renderer is started. */
  void onRendererStarted();

  /** Called when the renderer is stopped. */
  void onRendererStopped();

  /**
   * Sets a {@link Listener} on this sink. Callbacks are triggered on the supplied {@link Executor}.
   *
   * @param listener The {@link Listener}.
   * @param executor The {@link Executor} to dispatch the callbacks.
   */
  void setListener(Listener listener, Executor executor);

  /**
   * Initializes the video sink.
   *
   * @param sourceFormat The format of the compressed video.
   * @throws VideoSink.VideoSinkException If initializing the sink failed.
   */
  void initialize(Format sourceFormat) throws VideoSinkException;

  /** Returns whether the video sink is {@linkplain #initialize(Format) initialized}. */
  boolean isInitialized();

  /**
   * Flushes the video sink.
   *
   * <p>After calling this method, any frames stored inside the video sink are discarded.
   *
   * @param resetPosition Whether to reset the current position.
   */
  void flush(boolean resetPosition);

  /**
   * Returns whether the video sink is able to immediately render media from the current position.
   */
  boolean isReady();

  /**
   * Returns whether all queued video frames have been rendered, including the frame marked as last
   * buffer.
   */
  boolean isEnded();

  /**
   * Returns whether frames could be dropped from the sink's {@linkplain #getInputSurface() input
   * surface}.
   */
  boolean isFrameDropAllowedOnInput();

  /**
   * Returns the input {@link Surface} where the video sink consumes input frames from.
   *
   * <p>Must be called after the sink is {@linkplain #initialize(Format) initialized}.
   */
  Surface getInputSurface();

  /** Sets the {@link VideoFrameMetadataListener}. */
  void setVideoFrameMetadataListener(VideoFrameMetadataListener videoFrameMetadataListener);

  /** Sets the playback speed. */
  void setPlaybackSpeed(@FloatRange(from = 0, fromInclusive = false) float speed);

  /** Sets {@linkplain Effect video effects} to apply immediately. */
  void setVideoEffects(List<Effect> videoEffects);

  /**
   * Sets {@linkplain Effect video effects} to apply after the next stream is {@linkplain
   * VideoSink#registerInputStream(int, Format) registered}.
   */
  void setPendingVideoEffects(List<Effect> videoEffects);

  /**
   * Sets the stream offset and buffer time adjustment, in microseconds.
   *
   * @param streamOffsetUs The offset that is added to the video frames presentation timestamps from
   *     the player.
   * @param bufferTimestampAdjustmentUs The timestamp adjustment to convert the player position to
   *     the frame presentation timestamp.
   */
  void setStreamOffsetAndAdjustmentUs(long streamOffsetUs, long bufferTimestampAdjustmentUs);

  /** Sets the output surface info. */
  void setOutputSurfaceInfo(Surface outputSurface, Size outputResolution);

  /** Clears the set output surface info. */
  void clearOutputSurfaceInfo();

  /**
   * Enables this video sink to render the start of the stream even if the renderer is not
   * {@linkplain #onRendererStarted() started} yet.
   *
   * <p>This is used to update the value of {@code mayRenderStartOfStream} passed to {@link
   * #onRendererEnabled(boolean)}.
   */
  void enableMayRenderStartOfStream();

  /**
   * Informs the video sink that a new input stream will be queued.
   *
   * <p>Must be called after the sink is {@linkplain #initialize(Format) initialized}.
   *
   * @param inputType The {@link InputType} of the stream.
   * @param format The {@link Format} of the stream.
   */
  void registerInputStream(@InputType int inputType, Format format);

  /**
   * Informs the video sink that a frame will be queued to its {@linkplain #getInputSurface() input
   * surface}.
   *
   * <p>Must be called after the sink is {@linkplain #initialize(Format) initialized}.
   *
   * @param framePresentationTimeUs The frame's presentation time, in microseconds.
   * @param isLastFrame Whether this is the last frame of the video stream.
   * @return A release timestamp, in nanoseconds, that should be associated when releasing this
   *     frame, or {@link C#TIME_UNSET} if the sink was not able to register the frame and the
   *     caller must try again later.
   */
  long registerInputFrame(long framePresentationTimeUs, boolean isLastFrame);

  /**
   * Provides an input {@link Bitmap} to the video sink.
   *
   * <p>Must be called after the sink is {@linkplain #initialize(Format) initialized}.
   *
   * @param inputBitmap The {@link Bitmap} queued to the video sink.
   * @param timestampIterator The times within the current stream that the bitmap should be shown
   *     at. The timestamps should be monotonically increasing.
   * @return Whether the bitmap was queued successfully. A {@code false} value indicates the caller
   *     must try again later.
   */
  boolean queueBitmap(Bitmap inputBitmap, TimestampIterator timestampIterator);

  /**
   * Incrementally renders processed video frames.
   *
   * @param positionUs The current playback position, in microseconds.
   * @param elapsedRealtimeUs {@link android.os.SystemClock#elapsedRealtime()} in microseconds,
   *     taken approximately at the time the playback position was {@code positionUs}.
   * @throws VideoSinkException If an error occurs during rendering.
   */
  void render(long positionUs, long elapsedRealtimeUs) throws VideoSinkException;

  /** Releases the sink. */
  void release();
}
