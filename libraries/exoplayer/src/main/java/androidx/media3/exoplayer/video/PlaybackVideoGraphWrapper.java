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

import static androidx.media3.common.VideoFrameProcessor.DROP_OUTPUT_FRAME;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.exoplayer.video.VideoSink.INPUT_TYPE_SURFACE;
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
import androidx.media3.common.PreviewingVideoGraph;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.TimedValueQueue;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.Renderer;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
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
    private List<Effect> compositionEffects;
    private Clock clock;
    private boolean built;

    /** Creates a builder. */
    public Builder(Context context, VideoFrameReleaseControl videoFrameReleaseControl) {
      this.context = context.getApplicationContext();
      this.videoFrameReleaseControl = videoFrameReleaseControl;
      compositionEffects = ImmutableList.of();
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
     * Sets the {@linkplain Effect effects} to apply after compositing the sinks' data.
     *
     * @param compositionEffects The composition {@linkplain Effect effects}.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setCompositionEffects(List<Effect> compositionEffects) {
      this.compositionEffects = compositionEffects;
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
  private final InputVideoSink inputVideoSink;

  /**
   * A queue of unprocessed input frame start positions. Each position is associated with the
   * timestamp from which it should be applied.
   */
  private final TimedValueQueue<Long> streamStartPositionsUs;

  private final PreviewingVideoGraph.Factory previewingVideoGraphFactory;
  private final List<Effect> compositionEffects;
  private final VideoSink defaultVideoSink;
  private final VideoSink.VideoFrameHandler videoFrameHandler;
  private final Clock clock;
  private final CopyOnWriteArraySet<PlaybackVideoGraphWrapper.Listener> listeners;

  private Format videoGraphOutputFormat;
  private @MonotonicNonNull HandlerWrapper handler;
  private @MonotonicNonNull PreviewingVideoGraph videoGraph;
  private long outputStreamStartPositionUs;
  @Nullable private Pair<Surface, Size> currentSurfaceAndSize;
  private int pendingFlushCount;
  private @State int state;
  @Nullable private Renderer.WakeupListener wakeupListener;

  /** The buffer presentation time, in microseconds, of the final frame in the stream. */
  private long finalBufferPresentationTimeUs;

  private boolean hasSignaledEndOfCurrentInputStream;

  /**
   * Converts the buffer timestamp (the player position, with renderer offset) to the composition
   * timestamp, in microseconds. The composition time starts from zero, add this adjustment to
   * buffer timestamp to get the composition time.
   */
  private long bufferTimestampAdjustmentUs;

  private PlaybackVideoGraphWrapper(Builder builder) {
    context = builder.context;
    inputVideoSink = new InputVideoSink(context);
    streamStartPositionsUs = new TimedValueQueue<>();
    previewingVideoGraphFactory = checkStateNotNull(builder.previewingVideoGraphFactory);
    compositionEffects = builder.compositionEffects;
    clock = builder.clock;
    defaultVideoSink = new DefaultVideoSink(builder.videoFrameReleaseControl, clock);
    videoFrameHandler =
        new VideoSink.VideoFrameHandler() {
          @Override
          public void render(long renderTimestampNs) {
            checkStateNotNull(videoGraph).renderOutputFrame(renderTimestampNs);
          }

          @Override
          public void skip() {
            checkStateNotNull(videoGraph).renderOutputFrame(DROP_OUTPUT_FRAME);
          }
        };
    listeners = new CopyOnWriteArraySet<>();
    listeners.add(inputVideoSink);
    videoGraphOutputFormat = new Format.Builder().build();
    state = STATE_CREATED;
    finalBufferPresentationTimeUs = C.TIME_UNSET;
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
    return inputVideoSink;
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
    // We forward output size changes to the sink even if we are still flushing.
    videoGraphOutputFormat =
        videoGraphOutputFormat.buildUpon().setWidth(width).setHeight(height).build();
    defaultVideoSink.onInputStreamChanged(INPUT_TYPE_SURFACE, videoGraphOutputFormat);
  }

  @Override
  public void onOutputFrameRateChanged(float frameRate) {
    videoGraphOutputFormat = videoGraphOutputFormat.buildUpon().setFrameRate(frameRate).build();
    defaultVideoSink.onInputStreamChanged(INPUT_TYPE_SURFACE, videoGraphOutputFormat);
  }

  @Override
  public void onOutputFrameAvailableForRendering(long framePresentationTimeUs) {
    if (pendingFlushCount > 0) {
      // Ignore available frames while flushing
      return;
    }
    if (wakeupListener != null) {
      // Wake up the player when not playing to render the frame more promptly.
      wakeupListener.onWakeup();
    }
    // The frame presentation time is relative to the start of the Composition and without the
    // renderer offset
    long bufferPresentationTimeUs = framePresentationTimeUs - bufferTimestampAdjustmentUs;
    Long newOutputStreamStartPositionUs =
        streamStartPositionsUs.pollFloor(bufferPresentationTimeUs);
    if (newOutputStreamStartPositionUs != null
        && newOutputStreamStartPositionUs != outputStreamStartPositionUs) {
      defaultVideoSink.setStreamTimestampInfo(
          newOutputStreamStartPositionUs, bufferTimestampAdjustmentUs, /* unused */ C.TIME_UNSET);
      outputStreamStartPositionUs = newOutputStreamStartPositionUs;
    }
    boolean isLastFrame =
        finalBufferPresentationTimeUs != C.TIME_UNSET
            && bufferPresentationTimeUs >= finalBufferPresentationTimeUs;
    defaultVideoSink.handleInputFrame(framePresentationTimeUs, isLastFrame, videoFrameHandler);
    if (isLastFrame) {
      // TODO b/257464707 - Support extensively modified media.
      defaultVideoSink.signalEndOfCurrentInputStream();
      hasSignaledEndOfCurrentInputStream = true;
    }
  }

  @Override
  public void onEnded(long finalFramePresentationTimeUs) {
    // Ignored.
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
    defaultVideoSink.setListener(new DefaultVideoSinkListener(), /* executor= */ handler::post);
    defaultVideoSink.initialize(sourceFormat);
    state = STATE_INITIALIZED;
    return videoGraph.getProcessor(/* inputIndex= */ 0);
  }

  private boolean isInitialized() {
    return state == STATE_INITIALIZED;
  }

  private void maybeSetOutputSurfaceInfo(@Nullable Surface surface, int width, int height) {
    if (videoGraph == null) {
      return;
    }
    // Update the surface on the video graph and the default video sink together.
    if (surface != null) {
      videoGraph.setOutputSurfaceInfo(new SurfaceInfo(surface, width, height));
      defaultVideoSink.setOutputSurfaceInfo(surface, new Size(width, height));
    } else {
      videoGraph.setOutputSurfaceInfo(/* outputSurfaceInfo= */ null);
      defaultVideoSink.clearOutputSurfaceInfo();
    }
  }

  private boolean isReady(boolean rendererOtherwiseReady) {
    return defaultVideoSink.isReady(
        /* rendererOtherwiseReady= */ rendererOtherwiseReady && pendingFlushCount == 0);
  }

  private boolean isEnded() {
    return pendingFlushCount == 0
        && hasSignaledEndOfCurrentInputStream
        && defaultVideoSink.isEnded();
  }

  /**
   * Incrementally renders available video frames.
   *
   * @param positionUs The current playback position, in microseconds.
   * @param elapsedRealtimeUs {@link android.os.SystemClock#elapsedRealtime()} in microseconds,
   *     taken approximately at the time the playback position was {@code positionUs}.
   */
  private void render(long positionUs, long elapsedRealtimeUs) throws VideoSink.VideoSinkException {
    defaultVideoSink.render(positionUs, elapsedRealtimeUs);
  }

  private void flush(boolean resetPosition) {
    if (!isInitialized()) {
      return;
    }
    pendingFlushCount++;
    defaultVideoSink.flush(resetPosition);
    while (streamStartPositionsUs.size() > 1) {
      streamStartPositionsUs.pollFirst();
    }
    if (streamStartPositionsUs.size() == 1) {
      long lastStartPositionUs = checkNotNull(streamStartPositionsUs.pollFirst());
      // defaultVideoSink should use the latest startPositionUs if none is passed after flushing.
      defaultVideoSink.setStreamTimestampInfo(
          lastStartPositionUs, bufferTimestampAdjustmentUs, /* unused */ C.TIME_UNSET);
    }
    finalBufferPresentationTimeUs = C.TIME_UNSET;
    hasSignaledEndOfCurrentInputStream = false;
    // Handle pending video graph callbacks to ensure video size changes reach the video render
    // control.
    checkStateNotNull(handler).post(() -> pendingFlushCount--);
  }

  private void setVideoFrameMetadataListener(
      VideoFrameMetadataListener videoFrameMetadataListener) {
    defaultVideoSink.setVideoFrameMetadataListener(videoFrameMetadataListener);
  }

  private void setPlaybackSpeed(float speed) {
    defaultVideoSink.setPlaybackSpeed(speed);
  }

  private void setBufferTimestampAdjustment(long bufferTimestampAdjustmentUs) {
    this.bufferTimestampAdjustmentUs = bufferTimestampAdjustmentUs;
    defaultVideoSink.setStreamTimestampInfo(
        outputStreamStartPositionUs, bufferTimestampAdjustmentUs, /* unused */ C.TIME_UNSET);
  }

  private static ColorInfo getAdjustedInputColorInfo(@Nullable ColorInfo inputColorInfo) {
    if (inputColorInfo == null || !inputColorInfo.isDataSpaceValid()) {
      return ColorInfo.SDR_BT709_LIMITED;
    }

    return inputColorInfo;
  }

  /** Receives input from an ExoPlayer renderer and forwards it to the video graph. */
  private final class InputVideoSink implements VideoSink, PlaybackVideoGraphWrapper.Listener {

    private final int videoFrameProcessorMaxPendingFrameCount;

    private ImmutableList<Effect> videoEffects;
    private @MonotonicNonNull VideoFrameProcessor videoFrameProcessor;
    @Nullable private Format inputFormat;
    private @InputType int inputType;
    private long inputBufferTimestampAdjustmentUs;
    private long lastResetPositionUs;

    /**
     * The buffer presentation timestamp, in microseconds, of the most recently registered frame.
     */
    private long lastBufferPresentationTimeUs;

    private VideoSink.Listener listener;
    private Executor listenerExecutor;
    private boolean signaledEndOfStream;

    /** Creates a new instance. */
    public InputVideoSink(Context context) {
      // TODO b/226330223 - Investigate increasing frame count when frame dropping is allowed.
      // TODO b/278234847 - Evaluate whether limiting frame count when frame dropping is not allowed
      //  reduces decoder timeouts, and consider restoring.
      videoFrameProcessorMaxPendingFrameCount =
          Util.getMaxPendingFramesCountForMediaCodecDecoders(context);
      videoEffects = ImmutableList.of();
      lastBufferPresentationTimeUs = C.TIME_UNSET;
      listener = VideoSink.Listener.NO_OP;
      listenerExecutor = NO_OP_EXECUTOR;
    }

    @Override
    public void onRendererEnabled(boolean mayRenderStartOfStream) {
      defaultVideoSink.onRendererEnabled(mayRenderStartOfStream);
    }

    @Override
    public void onRendererDisabled() {
      defaultVideoSink.onRendererDisabled();
    }

    @Override
    public void onRendererStarted() {
      defaultVideoSink.onRendererStarted();
    }

    @Override
    public void onRendererStopped() {
      defaultVideoSink.onRendererStopped();
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
      lastBufferPresentationTimeUs = C.TIME_UNSET;
      PlaybackVideoGraphWrapper.this.flush(resetPosition);
      signaledEndOfStream = false;
      // Don't change input stream start position or reset the pending input stream timestamp info
      // change so that it's announced with the next input frame.
      // Don't reset isInputStreamChangePending because it's not guaranteed to receive a new input
      // stream after seeking.
    }

    @Override
    public boolean isReady(boolean rendererOtherwiseReady) {
      return PlaybackVideoGraphWrapper.this.isReady(
          /* rendererOtherwiseReady= */ rendererOtherwiseReady && isInitialized());
    }

    @Override
    public void signalEndOfCurrentInputStream() {
      finalBufferPresentationTimeUs = lastBufferPresentationTimeUs;
    }

    @Override
    public void signalEndOfInput() {
      if (signaledEndOfStream) {
        return;
      }
      if (isInitialized()) {
        videoFrameProcessor.signalEndOfInput();
        signaledEndOfStream = true;
      }
    }

    @Override
    public boolean isEnded() {
      return isInitialized() && PlaybackVideoGraphWrapper.this.isEnded();
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
      this.inputType = inputType;
      this.inputFormat = format;
      finalBufferPresentationTimeUs = C.TIME_UNSET;
      hasSignaledEndOfCurrentInputStream = false;
      registerInputStream(format);
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
      if (inputFormat != null) {
        registerInputStream(inputFormat);
      }
    }

    @Override
    public void setPendingVideoEffects(List<Effect> videoEffects) {
      this.videoEffects =
          new ImmutableList.Builder<Effect>()
              .addAll(videoEffects)
              .addAll(compositionEffects)
              .build();
    }

    @Override
    public void setStreamTimestampInfo(
        long streamStartPositionUs, long bufferTimestampAdjustmentUs, long lastResetPositionUs) {
      // Input timestamps should always be positive because they are offset by ExoPlayer. Adding a
      // position to the queue with timestamp 0 should therefore always apply it as long as it is
      // the only position in the queue.
      streamStartPositionsUs.add(
          lastBufferPresentationTimeUs == C.TIME_UNSET ? 0 : lastBufferPresentationTimeUs + 1,
          streamStartPositionUs);
      inputBufferTimestampAdjustmentUs = bufferTimestampAdjustmentUs;
      // The buffer timestamp adjustment is only allowed to change after a flush to make sure that
      // the buffer timestamps are increasing. We can update the buffer timestamp adjustment
      // directly at the output of the VideoGraph because no frame has been input yet following the
      // flush.
      PlaybackVideoGraphWrapper.this.setBufferTimestampAdjustment(inputBufferTimestampAdjustmentUs);
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
      defaultVideoSink.setChangeFrameRateStrategy(changeFrameRateStrategy);
    }

    @Override
    public void enableMayRenderStartOfStream() {
      defaultVideoSink.enableMayRenderStartOfStream();
    }

    @Override
    public boolean handleInputFrame(
        long framePresentationTimeUs, boolean isLastFrame, VideoFrameHandler videoFrameHandler) {
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
      if (bufferPresentationTimeUs < lastResetPositionUs && !isLastFrame) {
        videoFrameHandler.skip();
        return true;
      }

      if (checkStateNotNull(videoFrameProcessor).getPendingInputFrameCount()
          >= videoFrameProcessorMaxPendingFrameCount) {
        return false;
      }
      if (!checkStateNotNull(videoFrameProcessor).registerInputFrame()) {
        return false;
      }

      lastBufferPresentationTimeUs = bufferPresentationTimeUs;
      // Use the frame presentation time as render time so that the SurfaceTexture is accompanied
      // by this timestamp. Setting a realtime based release time is only relevant when rendering to
      // a SurfaceView, but we render to a surface in this case.
      videoFrameHandler.render(/* renderTimestampNs= */ framePresentationTimeUs * 1000);
      return true;
    }

    @Override
    public boolean handleInputBitmap(Bitmap inputBitmap, TimestampIterator timestampIterator) {
      checkState(isInitialized());
      if (!checkStateNotNull(videoFrameProcessor)
          .queueInputBitmap(inputBitmap, timestampIterator)) {
        return false;
      }
      // TimestampIterator generates frame time.
      long lastBufferPresentationTimeUs =
          timestampIterator.getLastTimestampUs() - inputBufferTimestampAdjustmentUs;
      checkState(lastBufferPresentationTimeUs != C.TIME_UNSET);
      this.lastBufferPresentationTimeUs = lastBufferPresentationTimeUs;
      return true;
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws VideoSinkException {
      PlaybackVideoGraphWrapper.this.render(positionUs, elapsedRealtimeUs);
    }

    @Override
    public void setWakeupListener(Renderer.WakeupListener wakeupListener) {
      PlaybackVideoGraphWrapper.this.wakeupListener = wakeupListener;
    }

    @Override
    public void join(boolean renderNextFrameImmediately) {
      defaultVideoSink.join(renderNextFrameImmediately);
    }

    @Override
    public void release() {
      PlaybackVideoGraphWrapper.this.release();
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

    // Private methods

    private void registerInputStream(Format inputFormat) {
      Format adjustedInputFormat =
          inputFormat
              .buildUpon()
              .setColorInfo(getAdjustedInputColorInfo(inputFormat.colorInfo))
              .build();
      checkStateNotNull(videoFrameProcessor)
          .registerInputStream(
              inputType, adjustedInputFormat, videoEffects, /* offsetToAddUs= */ 0);
    }
  }

  private final class DefaultVideoSinkListener implements VideoSink.Listener {

    @Override
    public void onFirstFrameRendered(VideoSink videoSink) {
      for (PlaybackVideoGraphWrapper.Listener listener : listeners) {
        listener.onFirstFrameRendered(PlaybackVideoGraphWrapper.this);
      }
    }

    @Override
    public void onFrameDropped(VideoSink videoSink) {
      for (PlaybackVideoGraphWrapper.Listener listener : listeners) {
        listener.onFrameDropped(PlaybackVideoGraphWrapper.this);
      }
    }

    @Override
    public void onVideoSizeChanged(VideoSink videoSink, VideoSize videoSize) {
      for (PlaybackVideoGraphWrapper.Listener listener : listeners) {
        listener.onVideoSizeChanged(PlaybackVideoGraphWrapper.this, videoSize);
      }
    }

    @Override
    public void onError(VideoSink videoSink, VideoSink.VideoSinkException videoSinkException) {
      for (PlaybackVideoGraphWrapper.Listener listener : listeners) {
        listener.onError(
            PlaybackVideoGraphWrapper.this, VideoFrameProcessingException.from(videoSinkException));
      }
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
