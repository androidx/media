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

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.util.Pair;
import android.view.Surface;
import androidx.annotation.FloatRange;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.FrameInfo;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PreviewingVideoGraph;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.ExoPlaybackException;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import org.checkerframework.checker.nullness.qual.EnsuresNonNullIf;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Processes input from {@link VideoSink} instances, plumbing the data through a {@link VideoGraph}
 * and rendering the output.
 */
@UnstableApi
@RestrictTo({Scope.LIBRARY_GROUP})
public final class PlaybackVideoGraphWrapper implements VideoSinkProvider, VideoGraph.Listener {

  /** Listener for {@link PlaybackVideoGraphWrapper} events. */
  public interface Listener {
    /**
     * Called when the video frame processor renders the first frame.
     *
     * @param playbackVideoGraphWrapper The {@link PlaybackVideoGraphWrapper} which triggered this
     *     event.
     */
    void onFirstFrameRendered(PlaybackVideoGraphWrapper playbackVideoGraphWrapper);

    /**
     * Called when the video frame processor dropped a frame.
     *
     * @param playbackVideoGraphWrapper The {@link PlaybackVideoGraphWrapper} which triggered this
     *     event.
     */
    void onFrameDropped(PlaybackVideoGraphWrapper playbackVideoGraphWrapper);

    /**
     * Called before a frame is rendered for the first time since setting the surface, and each time
     * there's a change in the size, rotation or pixel aspect ratio of the video being rendered.
     *
     * @param playbackVideoGraphWrapper The {@link PlaybackVideoGraphWrapper} which triggered this
     *     event.
     * @param videoSize The video size.
     */
    void onVideoSizeChanged(
        PlaybackVideoGraphWrapper playbackVideoGraphWrapper, VideoSize videoSize);

    /**
     * Called when the video frame processor encountered an error.
     *
     * @param playbackVideoGraphWrapper The {@link PlaybackVideoGraphWrapper} which triggered this
     *     event.
     * @param videoFrameProcessingException The error.
     */
    void onError(
        PlaybackVideoGraphWrapper playbackVideoGraphWrapper,
        VideoFrameProcessingException videoFrameProcessingException);
  }

  /** A builder for {@link PlaybackVideoGraphWrapper} instances. */
  public static final class Builder {
    private final Context context;
    private final VideoFrameReleaseControl videoFrameReleaseControl;

    private VideoFrameProcessor.@MonotonicNonNull Factory videoFrameProcessorFactory;
    private PreviewingVideoGraph.@MonotonicNonNull Factory previewingVideoGraphFactory;
    private Clock clock;
    private boolean built;

    /** Creates a builder. */
    public Builder(Context context, VideoFrameReleaseControl videoFrameReleaseControl) {
      this.context = context.getApplicationContext();
      this.videoFrameReleaseControl = videoFrameReleaseControl;
      clock = Clock.DEFAULT;
    }

    /**
     * Sets the {@link VideoFrameProcessor.Factory} that will be used for creating {@link
     * VideoFrameProcessor} instances.
     *
     * <p>By default, the {@code DefaultVideoFrameProcessor.Factory} with its default values will be
     * used.
     *
     * @param videoFrameProcessorFactory The {@link VideoFrameProcessor.Factory}.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setVideoFrameProcessorFactory(
        VideoFrameProcessor.Factory videoFrameProcessorFactory) {
      this.videoFrameProcessorFactory = videoFrameProcessorFactory;
      return this;
    }

    /**
     * Sets the {@link PreviewingVideoGraph.Factory} that will be used for creating {@link
     * PreviewingVideoGraph} instances.
     *
     * <p>By default, the {@code PreviewingSingleInputVideoGraph.Factory} will be used.
     *
     * @param previewingVideoGraphFactory The {@link PreviewingVideoGraph.Factory}.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setPreviewingVideoGraphFactory(
        PreviewingVideoGraph.Factory previewingVideoGraphFactory) {
      this.previewingVideoGraphFactory = previewingVideoGraphFactory;
      return this;
    }

    /**
     * Sets the {@link Clock} that will be used.
     *
     * <p>By default, {@link Clock#DEFAULT} will be used.
     *
     * @param clock The {@link Clock}.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setClock(Clock clock) {
      this.clock = clock;
      return this;
    }

    /**
     * Builds the {@link PlaybackVideoGraphWrapper}.
     *
     * <p>This method must be called at most once and will throw an {@link IllegalStateException} if
     * it has already been called.
     */
    public PlaybackVideoGraphWrapper build() {
      checkState(!built);

      if (previewingVideoGraphFactory == null) {
        if (videoFrameProcessorFactory == null) {
          videoFrameProcessorFactory = new ReflectiveDefaultVideoFrameProcessorFactory();
        }
        previewingVideoGraphFactory =
            new ReflectivePreviewingSingleInputVideoGraphFactory(videoFrameProcessorFactory);
      }
      PlaybackVideoGraphWrapper playbackVideoGraphWrapper = new PlaybackVideoGraphWrapper(this);
      built = true;
      return playbackVideoGraphWrapper;
    }
  }

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({STATE_CREATED, STATE_INITIALIZED, STATE_RELEASED})
  private @interface State {}

  private static final int STATE_CREATED = 0;
  private static final int STATE_INITIALIZED = 1;
  private static final int STATE_RELEASED = 2;

  private static final Executor NO_OP_EXECUTOR = runnable -> {};

  private final Context context;
  private final VideoSinkImpl videoSinkImpl;
  private final VideoFrameReleaseControl videoFrameReleaseControl;
  private final VideoFrameRenderControl videoFrameRenderControl;
  private final PreviewingVideoGraph.Factory previewingVideoGraphFactory;
  private final Clock clock;
  private final CopyOnWriteArraySet<PlaybackVideoGraphWrapper.Listener> listeners;

  private @MonotonicNonNull Format outputFormat;
  private @MonotonicNonNull VideoFrameMetadataListener videoFrameMetadataListener;
  private @MonotonicNonNull HandlerWrapper handler;
  private @MonotonicNonNull PreviewingVideoGraph videoGraph;
  @Nullable private Pair<Surface, Size> currentSurfaceAndSize;
  private int pendingFlushCount;
  private @State int state;

  /**
   * Converts the buffer timestamp (the player position, with renderer offset) to the composition
   * timestamp, in microseconds. The composition time starts from zero, add this adjustment to
   * buffer timestamp to get the composition time.
   */
  private long bufferTimestampAdjustmentUs;

  private PlaybackVideoGraphWrapper(Builder builder) {
    context = builder.context;
    videoSinkImpl = new VideoSinkImpl(context);
    clock = builder.clock;
    videoFrameReleaseControl = builder.videoFrameReleaseControl;
    videoFrameReleaseControl.setClock(clock);
    videoFrameRenderControl =
        new VideoFrameRenderControl(new FrameRendererImpl(), videoFrameReleaseControl);
    previewingVideoGraphFactory = checkStateNotNull(builder.previewingVideoGraphFactory);
    listeners = new CopyOnWriteArraySet<>();
    state = STATE_CREATED;
    addListener(videoSinkImpl);
  }

  /**
   * Adds a {@link PlaybackVideoGraphWrapper.Listener}.
   *
   * @param listener The listener to be added.
   */
  public void addListener(PlaybackVideoGraphWrapper.Listener listener) {
    listeners.add(listener);
  }

  /**
   * Removes a {@link PlaybackVideoGraphWrapper.Listener}.
   *
   * @param listener The listener to be removed.
   */
  public void removeListener(PlaybackVideoGraphWrapper.Listener listener) {
    listeners.remove(listener);
  }

  // VideoSinkProvider methods

  @Override
  public VideoSink getSink() {
    return videoSinkImpl;
  }

  @Override
  public void setOutputSurfaceInfo(Surface outputSurface, Size outputResolution) {
    if (currentSurfaceAndSize != null
        && currentSurfaceAndSize.first.equals(outputSurface)
        && currentSurfaceAndSize.second.equals(outputResolution)) {
      return;
    }
    currentSurfaceAndSize = Pair.create(outputSurface, outputResolution);
    maybeSetOutputSurfaceInfo(
        outputSurface, outputResolution.getWidth(), outputResolution.getHeight());
  }

  @Override
  public void clearOutputSurfaceInfo() {
    maybeSetOutputSurfaceInfo(
        /* surface= */ null,
        /* width= */ Size.UNKNOWN.getWidth(),
        /* height= */ Size.UNKNOWN.getHeight());
    currentSurfaceAndSize = null;
  }

  @Override
  public void release() {
    if (state == STATE_RELEASED) {
      return;
    }

    if (handler != null) {
      handler.removeCallbacksAndMessages(/* token= */ null);
    }

    if (videoGraph != null) {
      videoGraph.release();
    }
    currentSurfaceAndSize = null;
    state = STATE_RELEASED;
  }

  // VideoGraph.Listener

  @Override
  public void onOutputSizeChanged(int width, int height) {
    // We forward output size changes to render control even if we are still flushing.
    videoFrameRenderControl.onOutputSizeChanged(width, height);
  }

  @Override
  public void onOutputFrameAvailableForRendering(long framePresentationTimeUs) {
    if (pendingFlushCount > 0) {
      // Ignore available frames while flushing
      return;
    }
    // The frame presentation time is relative to the start of the Composition and without the
    // renderer offset
    videoFrameRenderControl.onOutputFrameAvailableForRendering(
        framePresentationTimeUs - bufferTimestampAdjustmentUs);
  }

  @Override
  public void onEnded(long finalFramePresentationTimeUs) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void onError(VideoFrameProcessingException exception) {
    for (PlaybackVideoGraphWrapper.Listener listener : listeners) {
      listener.onError(/* playbackVideoGraphWrapper= */ this, exception);
    }
  }

  // Internal methods

  private VideoFrameProcessor initialize(Format sourceFormat) throws VideoSink.VideoSinkException {
    checkState(state == STATE_CREATED);

    ColorInfo inputColorInfo = getAdjustedInputColorInfo(sourceFormat.colorInfo);
    ColorInfo outputColorInfo = inputColorInfo;
    if (inputColorInfo.colorTransfer == C.COLOR_TRANSFER_HLG && Util.SDK_INT < 34) {
      // PQ SurfaceView output is supported from API 33, but HLG output is supported from API 34.
      // Therefore, convert HLG to PQ below API 34, so that HLG input can be displayed properly on
      // API 33.
      outputColorInfo =
          inputColorInfo.buildUpon().setColorTransfer(C.COLOR_TRANSFER_ST2084).build();
    }
    handler = clock.createHandler(checkStateNotNull(Looper.myLooper()), /* callback= */ null);
    try {
      videoGraph =
          previewingVideoGraphFactory.create(
              context,
              outputColorInfo,
              DebugViewProvider.NONE,
              /* listener= */ this,
              /* listenerExecutor= */ handler::post,
              /* compositionEffects= */ ImmutableList.of(),
              /* initialTimestampOffsetUs= */ 0);
      if (currentSurfaceAndSize != null) {
        Surface surface = currentSurfaceAndSize.first;
        Size size = currentSurfaceAndSize.second;
        maybeSetOutputSurfaceInfo(surface, size.getWidth(), size.getHeight());
      }
      videoGraph.registerInput(/* inputIndex= */ 0);
    } catch (VideoFrameProcessingException e) {
      throw new VideoSink.VideoSinkException(e, sourceFormat);
    }
    state = STATE_INITIALIZED;
    return videoGraph.getProcessor(/* inputIndex= */ 0);
  }

  private boolean isInitialized() {
    return state == STATE_INITIALIZED;
  }

  private void maybeSetOutputSurfaceInfo(@Nullable Surface surface, int width, int height) {
    if (videoGraph != null) {
      // Update the surface on the video graph and the video frame release control together.
      SurfaceInfo surfaceInfo = surface != null ? new SurfaceInfo(surface, width, height) : null;
      videoGraph.setOutputSurfaceInfo(surfaceInfo);
      videoFrameReleaseControl.setOutputSurface(surface);
    }
  }

  private boolean isReady(boolean rendererOtherwiseReady) {
    return videoFrameRenderControl.isReady(
        /* rendererOtherwiseReady= */ rendererOtherwiseReady && pendingFlushCount == 0);
  }

  private boolean hasReleasedFrame(long presentationTimeUs) {
    return pendingFlushCount == 0 && videoFrameRenderControl.hasReleasedFrame(presentationTimeUs);
  }

  /**
   * Incrementally renders available video frames.
   *
   * @param positionUs The current playback position, in microseconds.
   * @param elapsedRealtimeUs {@link android.os.SystemClock#elapsedRealtime()} in microseconds,
   *     taken approximately at the time the playback position was {@code positionUs}.
   */
  private void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
    if (pendingFlushCount == 0) {
      videoFrameRenderControl.render(positionUs, elapsedRealtimeUs);
    }
  }

  private void flush() {
    if (!isInitialized()) {
      return;
    }
    pendingFlushCount++;
    // Flush the render control now to ensure it has no data, eg calling isReady() must return false
    // and render() should not render any frames.
    videoFrameRenderControl.flush();
    // Finish flushing after handling pending video graph callbacks to ensure video size changes
    // reach the video render control.
    checkStateNotNull(handler).post(this::flushInternal);
  }

  private void flushInternal() {
    pendingFlushCount--;
    if (pendingFlushCount > 0) {
      // Another flush has been issued.
      return;
    } else if (pendingFlushCount < 0) {
      throw new IllegalStateException(String.valueOf(pendingFlushCount));
    }
    // Flush the render control again.
    videoFrameRenderControl.flush();
  }

  private void setVideoFrameMetadataListener(
      VideoFrameMetadataListener videoFrameMetadataListener) {
    this.videoFrameMetadataListener = videoFrameMetadataListener;
  }

  private void setPlaybackSpeed(float speed) {
    videoFrameRenderControl.setPlaybackSpeed(speed);
  }

  private void onStreamOffsetChange(
      long bufferTimestampAdjustmentUs, long bufferPresentationTimeUs, long streamOffsetUs) {
    this.bufferTimestampAdjustmentUs = bufferTimestampAdjustmentUs;
    videoFrameRenderControl.onStreamOffsetChange(bufferPresentationTimeUs, streamOffsetUs);
  }

  private static ColorInfo getAdjustedInputColorInfo(@Nullable ColorInfo inputColorInfo) {
    if (inputColorInfo == null || !inputColorInfo.isDataSpaceValid()) {
      return ColorInfo.SDR_BT709_LIMITED;
    }

    return inputColorInfo;
  }

  /** Receives input from an ExoPlayer renderer and forwards it to the video graph. */
  private final class VideoSinkImpl implements VideoSink, PlaybackVideoGraphWrapper.Listener {

    private final int videoFrameProcessorMaxPendingFrameCount;
    private final ArrayList<Effect> videoEffects;
    private final VideoFrameReleaseControl.FrameReleaseInfo frameReleaseInfo;

    private @MonotonicNonNull VideoFrameProcessor videoFrameProcessor;
    @Nullable private Format inputFormat;
    private @InputType int inputType;
    private long inputStreamStartPositionUs;
    private long inputStreamOffsetUs;
    private long inputBufferTimestampAdjustmentUs;
    private long lastResetPositionUs;
    private boolean pendingInputStreamOffsetChange;

    /** The buffer presentation time, in microseconds, of the final frame in the stream. */
    private long finalBufferPresentationTimeUs;

    /**
     * The buffer presentation timestamp, in microseconds, of the most recently registered frame.
     */
    private long lastBufferPresentationTimeUs;

    private boolean hasRegisteredFirstInputStream;
    private boolean isInputStreamChangePending;
    private long pendingInputStreamBufferPresentationTimeUs;
    private VideoSink.Listener listener;
    private Executor listenerExecutor;

    /** Creates a new instance. */
    public VideoSinkImpl(Context context) {
      // TODO b/226330223 - Investigate increasing frame count when frame dropping is allowed.
      // TODO b/278234847 - Evaluate whether limiting frame count when frame dropping is not allowed
      //  reduces decoder timeouts, and consider restoring.
      videoFrameProcessorMaxPendingFrameCount =
          Util.getMaxPendingFramesCountForMediaCodecDecoders(context);
      videoEffects = new ArrayList<>();
      frameReleaseInfo = new VideoFrameReleaseControl.FrameReleaseInfo();
      finalBufferPresentationTimeUs = C.TIME_UNSET;
      lastBufferPresentationTimeUs = C.TIME_UNSET;
      listener = VideoSink.Listener.NO_OP;
      listenerExecutor = NO_OP_EXECUTOR;
    }

    // VideoSink impl

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
      this.listener = listener;
      listenerExecutor = executor;
    }

    @Override
    public void initialize(Format sourceFormat) throws VideoSinkException {
      checkState(!isInitialized());
      videoFrameProcessor = PlaybackVideoGraphWrapper.this.initialize(sourceFormat);
    }

    @Override
    @EnsuresNonNullIf(result = true, expression = "videoFrameProcessor")
    public boolean isInitialized() {
      return videoFrameProcessor != null;
    }

    @Override
    public void flush(boolean resetPosition) {
      if (isInitialized()) {
        videoFrameProcessor.flush();
      }
      hasRegisteredFirstInputStream = false;
      finalBufferPresentationTimeUs = C.TIME_UNSET;
      lastBufferPresentationTimeUs = C.TIME_UNSET;
      PlaybackVideoGraphWrapper.this.flush();
      if (resetPosition) {
        videoFrameReleaseControl.reset();
      }
      pendingInputStreamBufferPresentationTimeUs = C.TIME_UNSET;
      // Don't change input stream offset or reset the pending input stream offset change so that
      // it's announced with the next input frame.
      // Don't reset isInputStreamChangePending because it's not guaranteed to receive a new input
      // stream after seeking.
    }

    @Override
    public boolean isReady(boolean rendererOtherwiseReady) {
      return PlaybackVideoGraphWrapper.this.isReady(
          /* rendererOtherwiseReady= */ rendererOtherwiseReady && isInitialized());
    }

    @Override
    public boolean isEnded() {
      return isInitialized()
          && finalBufferPresentationTimeUs != C.TIME_UNSET
          && PlaybackVideoGraphWrapper.this.hasReleasedFrame(finalBufferPresentationTimeUs);
    }

    @Override
    public void onInputStreamChanged(@InputType int inputType, Format format) {
      checkState(isInitialized());
      switch (inputType) {
        case INPUT_TYPE_SURFACE:
        case INPUT_TYPE_BITMAP:
          break;
        default:
          throw new UnsupportedOperationException("Unsupported input type " + inputType);
      }
      videoFrameReleaseControl.setFrameRate(format.frameRate);
      this.inputType = inputType;
      this.inputFormat = format;

      if (!hasRegisteredFirstInputStream) {
        maybeRegisterInputStream();
        hasRegisteredFirstInputStream = true;
        // If an input stream registration is pending and seek causes a format change, execution
        // reaches here before registerInputFrame(). Reset pendingInputStreamTimestampUs to
        // avoid registering the same input stream again in registerInputFrame().
        isInputStreamChangePending = false;
        pendingInputStreamBufferPresentationTimeUs = C.TIME_UNSET;
      } else {
        // If we reach this point, we must have registered at least one frame for processing.
        checkState(lastBufferPresentationTimeUs != C.TIME_UNSET);
        isInputStreamChangePending = true;
        pendingInputStreamBufferPresentationTimeUs = lastBufferPresentationTimeUs;
      }
    }

    @Override
    public Surface getInputSurface() {
      checkState(isInitialized());
      return checkStateNotNull(videoFrameProcessor).getInputSurface();
    }

    @Override
    public void setVideoFrameMetadataListener(
        VideoFrameMetadataListener videoFrameMetadataListener) {
      PlaybackVideoGraphWrapper.this.setVideoFrameMetadataListener(videoFrameMetadataListener);
    }

    @Override
    public void setPlaybackSpeed(@FloatRange(from = 0, fromInclusive = false) float speed) {
      PlaybackVideoGraphWrapper.this.setPlaybackSpeed(speed);
    }

    @Override
    public void setVideoEffects(List<Effect> videoEffects) {
      if (this.videoEffects.equals(videoEffects)) {
        return;
      }
      setPendingVideoEffects(videoEffects);
      maybeRegisterInputStream();
    }

    @Override
    public void setPendingVideoEffects(List<Effect> videoEffects) {
      this.videoEffects.clear();
      this.videoEffects.addAll(videoEffects);
    }

    @Override
    public void setStreamTimestampInfo(
        long streamStartPositionUs,
        long streamOffsetUs,
        long bufferTimestampAdjustmentUs,
        long lastResetPositionUs) {
      // Ors because this method could be called multiple times on a stream offset change.
      pendingInputStreamOffsetChange |=
          inputStreamOffsetUs != streamOffsetUs
              || inputBufferTimestampAdjustmentUs != bufferTimestampAdjustmentUs;
      inputStreamStartPositionUs = streamStartPositionUs;
      inputStreamOffsetUs = streamOffsetUs;
      inputBufferTimestampAdjustmentUs = bufferTimestampAdjustmentUs;
      this.lastResetPositionUs = lastResetPositionUs;
    }

    @Override
    public void setOutputSurfaceInfo(Surface outputSurface, Size outputResolution) {
      PlaybackVideoGraphWrapper.this.setOutputSurfaceInfo(outputSurface, outputResolution);
    }

    @Override
    public void clearOutputSurfaceInfo() {
      PlaybackVideoGraphWrapper.this.clearOutputSurfaceInfo();
    }

    @Override
    public void setChangeFrameRateStrategy(
        @C.VideoChangeFrameRateStrategy int changeFrameRateStrategy) {
      videoFrameReleaseControl.setChangeFrameRateStrategy(changeFrameRateStrategy);
    }

    @Override
    public void enableMayRenderStartOfStream() {
      videoFrameReleaseControl.allowReleaseFirstFrameBeforeStarted();
    }

    @Override
    public boolean handleInputFrame(
        long framePresentationTimeUs,
        boolean isLastFrame,
        long positionUs,
        long elapsedRealtimeUs,
        VideoFrameHandler videoFrameHandler)
        throws VideoSinkException {
      checkState(isInitialized());

      // The sink takes in frames with monotonically increasing, non-offset frame
      // timestamps. That is, with two ten-second long videos, the first frame of the second video
      // should bear a timestamp of 10s seen from VideoFrameProcessor; while in ExoPlayer, the
      // timestamp of the said frame would be 0s, but the streamOffset is incremented by 10s to
      // include the duration of the first video. Thus this correction is needed to account for the
      // different handling of presentation timestamps in ExoPlayer and VideoFrameProcessor.
      //
      // inputBufferTimestampAdjustmentUs adjusts the frame presentation time (which is relative to
      // the start of a composition) to the buffer timestamp (that corresponds to the player
      // position).
      long bufferPresentationTimeUs = framePresentationTimeUs - inputBufferTimestampAdjustmentUs;
      // The frame release action should be retrieved for all frames (even the ones that will be
      // skipped), because the release control estimates the content frame rate from frame
      // timestamps and we want to have this information known as early as possible, especially
      // during seeking.
      @VideoFrameReleaseControl.FrameReleaseAction int frameReleaseAction;
      try {
        frameReleaseAction =
            videoFrameReleaseControl.getFrameReleaseAction(
                bufferPresentationTimeUs,
                positionUs,
                elapsedRealtimeUs,
                inputStreamStartPositionUs,
                isLastFrame,
                frameReleaseInfo);
      } catch (ExoPlaybackException e) {
        throw new VideoSinkException(e, checkStateNotNull(inputFormat));
      }
      if (frameReleaseAction == VideoFrameReleaseControl.FRAME_RELEASE_IGNORE) {
        // The buffer is no longer valid and needs to be ignored.
        return false;
      }

      if (bufferPresentationTimeUs < lastResetPositionUs && !isLastFrame) {
        videoFrameHandler.skip();
        return true;
      }

      // Drain the sink to make room for a new input frame.
      render(positionUs, elapsedRealtimeUs);

      // An input stream is fully decoded, wait until all of its frames are released before queueing
      // input frame from the next input stream.
      if (isInputStreamChangePending) {
        if (pendingInputStreamBufferPresentationTimeUs == C.TIME_UNSET
            || PlaybackVideoGraphWrapper.this.hasReleasedFrame(
                pendingInputStreamBufferPresentationTimeUs)) {
          maybeRegisterInputStream();
          isInputStreamChangePending = false;
          pendingInputStreamBufferPresentationTimeUs = C.TIME_UNSET;
        } else {
          return false;
        }
      }
      if (checkStateNotNull(videoFrameProcessor).getPendingInputFrameCount()
          >= videoFrameProcessorMaxPendingFrameCount) {
        return false;
      }
      if (!checkStateNotNull(videoFrameProcessor).registerInputFrame()) {
        return false;
      }

      maybeSetStreamOffsetChange(bufferPresentationTimeUs);
      lastBufferPresentationTimeUs = bufferPresentationTimeUs;
      if (isLastFrame) {
        finalBufferPresentationTimeUs = bufferPresentationTimeUs;
      }
      // Use the frame presentation time as render time so that the SurfaceTexture is accompanied
      // by this timestamp. Setting a realtime based release time is only relevant when rendering to
      // a SurfaceView, but we render to a surface in this case.
      videoFrameHandler.render(/* renderTimestampNs= */ framePresentationTimeUs * 1000);
      return true;
    }

    @Override
    public boolean handleInputBitmap(Bitmap inputBitmap, TimestampIterator timestampIterator) {
      checkState(isInitialized());

      if (!maybeRegisterPendingInputStream()) {
        return false;
      }

      if (!checkStateNotNull(videoFrameProcessor)
          .queueInputBitmap(inputBitmap, timestampIterator)) {
        return false;
      }

      // Create a copy of iterator because we need to take the next timestamp but we must not alter
      // the state of the iterator.
      TimestampIterator copyTimestampIterator = timestampIterator.copyOf();
      long bufferPresentationTimeUs = copyTimestampIterator.next();
      // TimestampIterator generates frame time.
      long lastBufferPresentationTimeUs =
          copyTimestampIterator.getLastTimestampUs() - inputBufferTimestampAdjustmentUs;
      checkState(lastBufferPresentationTimeUs != C.TIME_UNSET);
      maybeSetStreamOffsetChange(bufferPresentationTimeUs);
      this.lastBufferPresentationTimeUs = lastBufferPresentationTimeUs;
      finalBufferPresentationTimeUs = lastBufferPresentationTimeUs;
      return true;
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws VideoSinkException {
      try {
        PlaybackVideoGraphWrapper.this.render(positionUs, elapsedRealtimeUs);
      } catch (ExoPlaybackException e) {
        throw new VideoSinkException(
            e, inputFormat != null ? inputFormat : new Format.Builder().build());
      }
    }

    @Override
    public void join(boolean renderNextFrameImmediately) {
      videoFrameReleaseControl.join(renderNextFrameImmediately);
    }

    @Override
    public void release() {
      PlaybackVideoGraphWrapper.this.release();
    }

    // Other methods

    private void maybeSetStreamOffsetChange(long bufferPresentationTimeUs) {
      if (pendingInputStreamOffsetChange) {
        PlaybackVideoGraphWrapper.this.onStreamOffsetChange(
            inputBufferTimestampAdjustmentUs,
            bufferPresentationTimeUs,
            /* streamOffsetUs= */ inputStreamOffsetUs);
        pendingInputStreamOffsetChange = false;
      }
    }

    /**
     * Attempt to register any pending input stream to the video graph input and returns {@code
     * true} if a pending stream was registered and/or there is no pending input stream waiting for
     * registration, hence it's safe to queue images or frames to the video graph input.
     */
    private boolean maybeRegisterPendingInputStream() {
      if (!isInputStreamChangePending) {
        return true;
      }
      // An input stream is fully decoded, wait until all of its frames are released before queueing
      // input frame from the next input stream.
      if (pendingInputStreamBufferPresentationTimeUs == C.TIME_UNSET
          || PlaybackVideoGraphWrapper.this.hasReleasedFrame(
              pendingInputStreamBufferPresentationTimeUs)) {
        maybeRegisterInputStream();
        isInputStreamChangePending = false;
        pendingInputStreamBufferPresentationTimeUs = C.TIME_UNSET;
        return true;
      }
      return false;
    }

    private void maybeRegisterInputStream() {
      if (inputFormat == null) {
        return;
      }

      ArrayList<Effect> effects = new ArrayList<>(videoEffects);
      Format inputFormat = checkNotNull(this.inputFormat);
      checkStateNotNull(videoFrameProcessor)
          .registerInputStream(
              inputType,
              effects,
              new FrameInfo.Builder(
                      getAdjustedInputColorInfo(inputFormat.colorInfo),
                      inputFormat.width,
                      inputFormat.height)
                  .setPixelWidthHeightRatio(inputFormat.pixelWidthHeightRatio)
                  .build());
      finalBufferPresentationTimeUs = C.TIME_UNSET;
    }

    // PlaybackVideoGraphWrapper.Listener implementation

    @Override
    public void onFirstFrameRendered(PlaybackVideoGraphWrapper playbackVideoGraphWrapper) {
      VideoSink.Listener currentListener = listener;
      listenerExecutor.execute(() -> currentListener.onFirstFrameRendered(/* videoSink= */ this));
    }

    @Override
    public void onFrameDropped(PlaybackVideoGraphWrapper playbackVideoGraphWrapper) {
      VideoSink.Listener currentListener = listener;
      listenerExecutor.execute(
          () -> currentListener.onFrameDropped(checkStateNotNull(/* reference= */ this)));
    }

    @Override
    public void onVideoSizeChanged(
        PlaybackVideoGraphWrapper playbackVideoGraphWrapper, VideoSize videoSize) {
      VideoSink.Listener currentListener = listener;
      listenerExecutor.execute(
          () -> currentListener.onVideoSizeChanged(/* videoSink= */ this, videoSize));
    }

    @Override
    public void onError(
        PlaybackVideoGraphWrapper playbackVideoGraphWrapper,
        VideoFrameProcessingException videoFrameProcessingException) {
      VideoSink.Listener currentListener = listener;
      listenerExecutor.execute(
          () ->
              currentListener.onError(
                  /* videoSink= */ this,
                  new VideoSinkException(
                      videoFrameProcessingException, checkStateNotNull(this.inputFormat))));
    }
  }

  private final class FrameRendererImpl implements VideoFrameRenderControl.FrameRenderer {

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
      outputFormat =
          new Format.Builder()
              .setWidth(videoSize.width)
              .setHeight(videoSize.height)
              .setSampleMimeType(MimeTypes.VIDEO_RAW)
              .build();
      for (PlaybackVideoGraphWrapper.Listener listener : listeners) {
        listener.onVideoSizeChanged(PlaybackVideoGraphWrapper.this, videoSize);
      }
    }

    @Override
    public void renderFrame(
        long renderTimeNs,
        long bufferPresentationTimeUs,
        long streamOffsetUs,
        boolean isFirstFrame) {
      if (isFirstFrame && currentSurfaceAndSize != null) {
        for (PlaybackVideoGraphWrapper.Listener listener : listeners) {
          listener.onFirstFrameRendered(PlaybackVideoGraphWrapper.this);
        }
      }
      if (videoFrameMetadataListener != null) {
        // TODO b/292111083 - outputFormat is initialized after the first frame is rendered because
        //  onVideoSizeChanged is announced after the first frame is available for rendering.
        Format format = outputFormat == null ? new Format.Builder().build() : outputFormat;
        videoFrameMetadataListener.onVideoFrameAboutToBeRendered(
            /* presentationTimeUs= */ bufferPresentationTimeUs,
            clock.nanoTime(),
            format,
            /* mediaFormat= */ null);
      }
      checkStateNotNull(videoGraph).renderOutputFrame(renderTimeNs);
    }

    @Override
    public void dropFrame() {
      for (PlaybackVideoGraphWrapper.Listener listener : listeners) {
        listener.onFrameDropped(PlaybackVideoGraphWrapper.this);
      }
      checkStateNotNull(videoGraph).renderOutputFrame(VideoFrameProcessor.DROP_OUTPUT_FRAME);
    }
  }

  /**
   * Delays reflection for loading a {@linkplain PreviewingVideoGraph.Factory
   * PreviewingSingleInputVideoGraph} instance.
   */
  private static final class ReflectivePreviewingSingleInputVideoGraphFactory
      implements PreviewingVideoGraph.Factory {

    private final VideoFrameProcessor.Factory videoFrameProcessorFactory;

    public ReflectivePreviewingSingleInputVideoGraphFactory(
        VideoFrameProcessor.Factory videoFrameProcessorFactory) {
      this.videoFrameProcessorFactory = videoFrameProcessorFactory;
    }

    @Override
    public PreviewingVideoGraph create(
        Context context,
        ColorInfo outputColorInfo,
        DebugViewProvider debugViewProvider,
        VideoGraph.Listener listener,
        Executor listenerExecutor,
        List<Effect> compositionEffects,
        long initialTimestampOffsetUs)
        throws VideoFrameProcessingException {
      try {
        Class<?> previewingSingleInputVideoGraphFactoryClass =
            Class.forName("androidx.media3.effect.PreviewingSingleInputVideoGraph$Factory");
        PreviewingVideoGraph.Factory factory =
            (PreviewingVideoGraph.Factory)
                previewingSingleInputVideoGraphFactoryClass
                    .getConstructor(VideoFrameProcessor.Factory.class)
                    .newInstance(videoFrameProcessorFactory);
        return factory.create(
            context,
            outputColorInfo,
            debugViewProvider,
            listener,
            listenerExecutor,
            compositionEffects,
            initialTimestampOffsetUs);
      } catch (Exception e) {
        throw VideoFrameProcessingException.from(e);
      }
    }
  }

  /**
   * Delays reflection for loading a {@linkplain VideoFrameProcessor.Factory
   * DefaultVideoFrameProcessor.Factory} instance.
   */
  private static final class ReflectiveDefaultVideoFrameProcessorFactory
      implements VideoFrameProcessor.Factory {
    private static final Supplier<VideoFrameProcessor.Factory>
        VIDEO_FRAME_PROCESSOR_FACTORY_SUPPLIER =
            Suppliers.memoize(
                () -> {
                  try {
                    Class<?> defaultVideoFrameProcessorFactoryBuilderClass =
                        Class.forName(
                            "androidx.media3.effect.DefaultVideoFrameProcessor$Factory$Builder");
                    Object builder =
                        defaultVideoFrameProcessorFactoryBuilderClass
                            .getConstructor()
                            .newInstance();
                    return (VideoFrameProcessor.Factory)
                        checkNotNull(
                            defaultVideoFrameProcessorFactoryBuilderClass
                                .getMethod("build")
                                .invoke(builder));
                  } catch (Exception e) {
                    throw new IllegalStateException(e);
                  }
                });

    @Override
    public VideoFrameProcessor create(
        Context context,
        DebugViewProvider debugViewProvider,
        ColorInfo outputColorInfo,
        boolean renderFramesAutomatically,
        Executor listenerExecutor,
        VideoFrameProcessor.Listener listener)
        throws VideoFrameProcessingException {
      return VIDEO_FRAME_PROCESSOR_FACTORY_SUPPLIER
          .get()
          .create(
              context,
              debugViewProvider,
              outputColorInfo,
              renderFramesAutomatically,
              listenerExecutor,
              listener);
    }
  }
}
