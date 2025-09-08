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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;
import static androidx.media3.common.VideoFrameProcessor.DROP_OUTPUT_FRAME;
import static androidx.media3.common.util.Util.contains;
import static androidx.media3.common.util.Util.getMaxPendingFramesCountForMediaCodecDecoders;
import static androidx.media3.exoplayer.video.VideoSink.INPUT_TYPE_SURFACE;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.util.Pair;
import android.util.SparseArray;
import android.view.Surface;
import androidx.annotation.FloatRange;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.TimedValueQueue;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.common.util.Util;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Processes input from {@link VideoSink} instances, plumbing the data through a {@link VideoGraph}
 * and rendering the output.
 */
@RestrictTo(LIBRARY_GROUP)
public final class PlaybackVideoGraphWrapper implements VideoGraph.Listener {

  /** Listener for {@link PlaybackVideoGraphWrapper} events. */
  public interface Listener {

    /** Called when an output frame is available for rendering. */
    default void onFrameAvailableForRendering() {}

    /** Called when the first output frame is rendered. */
    default void onFirstFrameRendered() {}

    /** Called when an output frame is dropped. */
    default void onFrameDropped() {}

    /**
     * Called before a frame is rendered for the first time since setting the surface, and each time
     * there's a change in the size, rotation or pixel aspect ratio of the video being rendered.
     *
     * @param videoSize The video size.
     */
    default void onVideoSizeChanged(VideoSize videoSize) {}

    /**
     * Called when an error occurs.
     *
     * @param videoFrameProcessingException The error.
     */
    default void onError(VideoFrameProcessingException videoFrameProcessingException) {}
  }

  /** A builder for {@link PlaybackVideoGraphWrapper} instances. */
  public static final class Builder {
    private final Context context;
    private final VideoFrameReleaseControl videoFrameReleaseControl;
    private VideoGraph.@MonotonicNonNull Factory videoGraphFactory;
    private boolean enablePlaylistMode;
    private Clock clock;
    private boolean built;
    private boolean enableReplayableCache;
    private long lateThresholdToDropInputUs;
    private VideoFrameReleaseEarlyTimeForecaster videoFrameReleaseEarlyTimeForecaster;

    /** Creates a builder. */
    public Builder(Context context, VideoFrameReleaseControl videoFrameReleaseControl) {
      this.context = context.getApplicationContext();
      this.videoFrameReleaseControl = videoFrameReleaseControl;
      this.lateThresholdToDropInputUs = LATE_US_TO_DROP_INPUT_FRAME;
      videoFrameReleaseEarlyTimeForecaster =
          new VideoFrameReleaseEarlyTimeForecaster(/* playbackSpeed= */ 1f);
      clock = Clock.DEFAULT;
    }

    /**
     * Sets the {@link VideoGraph.Factory} that will be used for creating {@link VideoGraph}
     * instances.
     *
     * <p>By default, the {@code SingleInputVideoGraph.Factory} will be used.
     *
     * @param videoGraphFactory The {@link VideoGraph.Factory}.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setVideoGraphFactory(VideoGraph.Factory videoGraphFactory) {
      this.videoGraphFactory = videoGraphFactory;
      return this;
    }

    /**
     * Sets whether to enable playlist mode.
     *
     * <p>The default value is {@code false}.
     *
     * <p>This should only be set to {@code true} if there is a single {@linkplain
     * PlaybackVideoGraphWrapper#getSink(int) input sink}.
     *
     * <p>If {@code true}, the {@link VideoGraph} output is considered as a playlist of clips. In
     * this case, {@link PlaybackVideoGraphWrapper#startRendering()} and {@link
     * PlaybackVideoGraphWrapper#stopRendering()} are called internally, when the corresponding
     * methods are caller on the sink.
     *
     * <p>If {@code false}, the {@link VideoGraph} output is considered as a single clip. In this
     * case, the caller is responsible for calling {@link
     * PlaybackVideoGraphWrapper#startRendering()} and {@link
     * PlaybackVideoGraphWrapper#stopRendering()}.
     *
     * @param enablePlaylistMode Whether to enable playlist mode.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setEnablePlaylistMode(boolean enablePlaylistMode) {
      // This is set to true for ExoPlayer.setVideoEffects(). It's always false in
      // CompositionPlayer, even if the Composition has a single sequence, because CompositionPlayer
      // shouldn't behave differently for single and multi-sequence.
      this.enablePlaylistMode = enablePlaylistMode;
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
     * Sets whether to enable replayable cache.
     *
     * <p>By default, the replayable cache is not enabled. Enable it to achieve accurate effect
     * update, at the cost of using more power and computing resources.
     *
     * @param enableReplayableCache Whether replayable cache is enabled.
     * @return This builder, for convenience.
     */
    @CanIgnoreReturnValue
    public Builder setEnableReplayableCache(boolean enableReplayableCache) {
      this.enableReplayableCache = enableReplayableCache;
      return this;
    }

    /**
     * Sets the late threshold for input frames, in microseconds, after which frames may be dropped
     * before applying effects.
     *
     * <p>The default value is {@link #LATE_US_TO_DROP_INPUT_FRAME}.
     *
     * <p>Set this threshold to {@link C#TIME_UNSET} to disable frame dropping before effects are
     * applied.
     *
     * <p>This method is experimental and will be renamed or removed in a future release.
     *
     * @param lateThresholdToDropInputUs The threshold.
     */
    @CanIgnoreReturnValue
    public Builder experimentalSetLateThresholdToDropInputUs(long lateThresholdToDropInputUs) {
      this.lateThresholdToDropInputUs = lateThresholdToDropInputUs;
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

      if (videoGraphFactory == null) {
        videoGraphFactory = new ReflectiveSingleInputVideoGraphFactory(enableReplayableCache);
      }
      PlaybackVideoGraphWrapper playbackVideoGraphWrapper = new PlaybackVideoGraphWrapper(this);
      built = true;
      return playbackVideoGraphWrapper;
    }

    /**
     * Sets the {@link VideoFrameReleaseEarlyTimeForecaster} that will be used.
     *
     * <p>By default, a new instance is created by the playback video graph wrapper.
     *
     * <p>This setter is package-private as the VideoFrameReleaseEarlyTimeForecaster is intended for
     * use inside exoplayer's video components only.
     *
     * @param videoFrameReleaseEarlyTimeForecaster The {@link VideoFrameReleaseEarlyTimeForecaster}.
     * @return This builder, for convenience.
     */
    // TODO: b/433591731 - use the same videoFrameReleaseEarlyTimeForecaster instance as
    // MediaCodecVideoRenderer.shouldDiscardDecoderInputBuffer().
    @CanIgnoreReturnValue
    /* package */ Builder setVideoFrameReleaseEarlyTimeForecaster(
        VideoFrameReleaseEarlyTimeForecaster videoFrameReleaseEarlyTimeForecaster) {
      this.videoFrameReleaseEarlyTimeForecaster = videoFrameReleaseEarlyTimeForecaster;
      return this;
    }
  }

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({STATE_CREATED, STATE_INITIALIZED, STATE_RELEASED})
  private @interface State {}

  /**
   * The late time threshold, in microseconds, for dropping input frames before applying effects.
   *
   * <p>If a new video frame is expected to be later than this value, the video frame will be
   * dropped before applying effects.
   *
   * <p>The value was experimentally derived via b/398783183 which did not involve effects
   * processing. There may exist a more appropriate default value
   */
  public static final long LATE_US_TO_DROP_INPUT_FRAME = 15_000;

  private static final String TAG = "PlaybackVidGraphWrapper";
  private static final int STATE_CREATED = 0;
  private static final int STATE_INITIALIZED = 1;
  private static final int STATE_RELEASED = 2;

  private static final int PRIMARY_SEQUENCE_INDEX = 0;

  private static final Executor NO_OP_EXECUTOR = runnable -> {};

  private final Context context;

  private final VideoGraph.Factory videoGraphFactory;
  private final SparseArray<InputVideoSink> inputVideoSinks;
  private final boolean enablePlaylistMode;
  private final VideoSink defaultVideoSink;
  private final VideoSink.VideoFrameHandler videoFrameHandler;
  private final Clock clock;
  private final CopyOnWriteArraySet<PlaybackVideoGraphWrapper.Listener> listeners;
  private final long earlyThresholdToDropInputUs;
  private final VideoFrameReleaseEarlyTimeForecaster videoFrameReleaseEarlyTimeForecaster;

  /**
   * A queue of unprocessed stream changes. Each stream change is associated with the timestamp from
   * which it should be applied.
   */
  private TimedValueQueue<StreamChangeInfo> pendingStreamChanges;

  private Format videoGraphOutputFormat;
  private VideoCompositorSettings compositorSettings;
  private ImmutableList<Effect> compositionEffects;
  private @MonotonicNonNull HandlerWrapper handler;
  private @MonotonicNonNull VideoGraph videoGraph;
  private @MonotonicNonNull VideoFrameMetadataListener videoFrameMetadataListener;
  private boolean requestOpenGlToneMapping;
  private boolean isInputSdrToneMapped;
  private long outputStreamStartPositionUs;
  private @VideoSink.FirstFrameReleaseInstruction int outputStreamFirstFrameReleaseInstruction;
  @Nullable private Pair<Surface, Size> currentSurfaceAndSize;
  private int pendingFlushCount;
  private @State int state;

  /**
   * The frame presentation time of the frame most recently output by the video graph, in
   * microseconds.
   */
  private long lastOutputFramePresentationTimeUs;

  /** The frame presentation time, in microseconds, of the final frame in the stream. */
  private long finalFramePresentationTimeUs;

  private boolean hasSignaledEndOfVideoGraphOutputStream;

  private int totalVideoInputCount;
  private int registeredVideoInputCount;

  private PlaybackVideoGraphWrapper(Builder builder) {
    context = builder.context;
    pendingStreamChanges = new TimedValueQueue<>();
    videoGraphFactory = checkNotNull(builder.videoGraphFactory);
    inputVideoSinks = new SparseArray<>();
    compositionEffects = ImmutableList.of();
    compositorSettings = VideoCompositorSettings.DEFAULT;
    enablePlaylistMode = builder.enablePlaylistMode;
    clock = builder.clock;
    earlyThresholdToDropInputUs =
        builder.lateThresholdToDropInputUs != C.TIME_UNSET
            ? -builder.lateThresholdToDropInputUs
            : C.TIME_UNSET;
    videoFrameReleaseEarlyTimeForecaster = builder.videoFrameReleaseEarlyTimeForecaster;
    defaultVideoSink =
        new DefaultVideoSink(
            builder.videoFrameReleaseControl, videoFrameReleaseEarlyTimeForecaster, clock);
    videoFrameHandler =
        new VideoSink.VideoFrameHandler() {
          @Override
          public void render(long renderTimestampNs) {
            checkNotNull(videoGraph).renderOutputFrame(renderTimestampNs);
          }

          @Override
          public void skip() {
            checkNotNull(videoGraph).renderOutputFrame(DROP_OUTPUT_FRAME);
          }
        };
    listeners = new CopyOnWriteArraySet<>();
    videoGraphOutputFormat = new Format.Builder().build();
    outputStreamStartPositionUs = C.TIME_UNSET;
    lastOutputFramePresentationTimeUs = C.TIME_UNSET;
    finalFramePresentationTimeUs = C.TIME_UNSET;
    totalVideoInputCount = C.LENGTH_UNSET;
    state = STATE_CREATED;
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

  public void setTotalVideoInputCount(int totalVideoInputCount) {
    if (totalVideoInputCount < this.totalVideoInputCount) {
      // Currently we don't allow removing video from a sequence.
      // TODO: b/430250222 - Track types should be fixed after this, and this method could be
      //  removed.
      return;
    }
    this.totalVideoInputCount = totalVideoInputCount;
  }

  /**
   * Returns the {@link VideoSink} to forward video frames for processing.
   *
   * @param inputIndex The index of the {@link VideoSink}.
   * @return The {@link VideoSink} at the given index.
   */
  public VideoSink getSink(int inputIndex) {
    if (contains(inputVideoSinks, inputIndex)) {
      return inputVideoSinks.get(inputIndex);
    }
    InputVideoSink inputVideoSink = new InputVideoSink(context, inputIndex);
    if (inputIndex == PRIMARY_SEQUENCE_INDEX) {
      addListener(inputVideoSink);
    }
    inputVideoSinks.put(inputIndex, inputVideoSink);
    return inputVideoSink;
  }

  /** Sets the output surface info. */
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

  /** Clears the set output surface info. */
  public void clearOutputSurfaceInfo() {
    maybeSetOutputSurfaceInfo(
        /* surface= */ null,
        /* width= */ Size.UNKNOWN.getWidth(),
        /* height= */ Size.UNKNOWN.getHeight());
    currentSurfaceAndSize = null;
  }

  /** Starts rendering to the output surface. */
  public void startRendering() {
    defaultVideoSink.startRendering();
  }

  /** Stops rendering to the output surface. */
  public void stopRendering() {
    defaultVideoSink.stopRendering();
  }

  /** Sets the {@linkplain Effect effects} to apply after compositing the frames from sequences. */
  public void setCompositionEffects(List<Effect> compositionEffects) {
    this.compositionEffects = ImmutableList.copyOf(compositionEffects);
    if (videoGraph != null) {
      videoGraph.setCompositionEffects(compositionEffects);
    }
  }

  /** Sets the {@link VideoCompositorSettings} for frame composition. */
  public void setCompositorSettings(VideoCompositorSettings compositorSettings) {
    this.compositorSettings = compositorSettings;
    if (videoGraph != null) {
      videoGraph.setCompositorSettings(compositorSettings);
    }
  }

  /** Sets whether to tone map the input video with OpenGL. */
  public void setRequestOpenGlToneMapping(boolean requestOpenGlToneMapping) {
    this.requestOpenGlToneMapping = requestOpenGlToneMapping;
  }

  /** Sets whether the input should be treated as if it has been tone mapped to SDR. */
  public void setIsInputSdrToneMapped(boolean isInputSdrToneMapped) {
    this.isInputSdrToneMapped = isInputSdrToneMapped;
  }

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
    onOutputStreamChanged();
  }

  @Override
  public void onOutputFrameRateChanged(float frameRate) {
    videoGraphOutputFormat = videoGraphOutputFormat.buildUpon().setFrameRate(frameRate).build();
    onOutputStreamChanged();
  }

  @Override
  public void onOutputFrameAvailableForRendering(
      long framePresentationTimeUs, boolean isRedrawnFrame) {
    if (pendingFlushCount > 0) {
      // Ignore available frames while flushing
      return;
    }
    for (PlaybackVideoGraphWrapper.Listener listener : listeners) {
      // Wake up the player when not playing to render the frame more promptly.
      listener.onFrameAvailableForRendering();
    }

    if (isRedrawnFrame) {
      // Redrawn frames are rendered directly in the processing pipeline.
      if (videoFrameMetadataListener != null) {
        videoFrameMetadataListener.onVideoFrameAboutToBeRendered(
            /* presentationTimeUs= */ framePresentationTimeUs,
            /* releaseTimeNs= */ C.TIME_UNSET,
            videoGraphOutputFormat,
            /* mediaFormat= */ null);
      }
      return;
    }

    // The frame presentation time is relative to the start of the Composition and without the
    // renderer offset
    lastOutputFramePresentationTimeUs = framePresentationTimeUs;
    StreamChangeInfo streamChangeInfo = pendingStreamChanges.pollFloor(framePresentationTimeUs);
    if (streamChangeInfo != null) {
      outputStreamStartPositionUs = streamChangeInfo.startPositionUs;
      outputStreamFirstFrameReleaseInstruction = streamChangeInfo.firstFrameReleaseInstruction;
      onOutputStreamChanged();
    }
    defaultVideoSink.handleInputFrame(framePresentationTimeUs, videoFrameHandler);
    boolean isLastFrame =
        finalFramePresentationTimeUs != C.TIME_UNSET
            && framePresentationTimeUs >= finalFramePresentationTimeUs;
    if (isLastFrame) {
      signalEndOfVideoGraphOutputStream();
    }
  }

  @Override
  public void onEnded(long finalFramePresentationTimeUs) {
    // Ignored.
  }

  @Override
  public void onError(VideoFrameProcessingException exception) {
    for (PlaybackVideoGraphWrapper.Listener listener : listeners) {
      listener.onError(exception);
    }
  }

  // Internal methods

  private boolean registerInput(Format sourceFormat, int inputIndex)
      throws VideoSink.VideoSinkException {
    if (inputIndex == PRIMARY_SEQUENCE_INDEX) {
      checkState(state == STATE_CREATED);
      ColorInfo inputColorInfo = getAdjustedInputColorInfo(sourceFormat.colorInfo);
      ColorInfo outputColorInfo;
      try {
        if (requestOpenGlToneMapping) {
          outputColorInfo = ColorInfo.SDR_BT709_LIMITED;
        } else if (inputColorInfo.colorTransfer == C.COLOR_TRANSFER_HLG
            && SDK_INT < 34
            && GlUtil.isBt2020PqExtensionSupported()) {
          // PQ SurfaceView output is supported from API 33, but HLG output is supported from API
          // 34. Therefore, convert HLG to PQ if PQ is supported, so that HLG input can be displayed
          // properly on API 33.
          outputColorInfo =
              inputColorInfo.buildUpon().setColorTransfer(C.COLOR_TRANSFER_ST2084).build();
          // Force OpenGL tone mapping if the GL extension required to output HDR colors is not
          // available. OpenGL tone mapping is only supported on API 29+.
        } else if (!GlUtil.isColorTransferSupported(inputColorInfo.colorTransfer)
            && SDK_INT >= 29) {
          Log.w(
              TAG,
              Util.formatInvariant(
                  "Color transfer %d is not supported. Falling back to OpenGl tone mapping.",
                  inputColorInfo.colorTransfer));
          outputColorInfo = ColorInfo.SDR_BT709_LIMITED;
        } else if (inputColorInfo.colorTransfer == C.COLOR_TRANSFER_SRGB
            || inputColorInfo.colorTransfer == C.COLOR_TRANSFER_GAMMA_2_2) {
          outputColorInfo = ColorInfo.SDR_BT709_LIMITED;
        } else {
          outputColorInfo = inputColorInfo;
        }
      } catch (GlException e) {
        throw new VideoSink.VideoSinkException(e, sourceFormat);
      }
      handler = clock.createHandler(checkNotNull(Looper.myLooper()), /* callback= */ null);
      try {
        // TODO: b/412585856 - Allow setting CompositorSetting and CompositionEffects dynamically.
        videoGraph =
            videoGraphFactory.create(
                context,
                outputColorInfo,
                DebugViewProvider.NONE,
                /* listener= */ this,
                /* listenerExecutor= */ handler::post,
                /* initialTimestampOffsetUs= */ 0,
                /* renderFramesAutomatically= */ false);
        videoGraph.setCompositionEffects(compositionEffects);
        videoGraph.setCompositorSettings(compositorSettings);
        videoGraph.initialize();
      } catch (VideoFrameProcessingException e) {
        throw new VideoSink.VideoSinkException(e, sourceFormat);
      }
      if (currentSurfaceAndSize != null) {
        Surface surface = currentSurfaceAndSize.first;
        Size size = currentSurfaceAndSize.second;
        maybeSetOutputSurfaceInfo(surface, size.getWidth(), size.getHeight());
      }
      defaultVideoSink.initialize(sourceFormat);
      defaultVideoSink.setListener(new DefaultVideoSinkListener(), /* executor= */ handler::post);
      state = STATE_INITIALIZED;
    } else {
      if (!isInitialized()) {
        // Make sure the primary sequence is initialized first.
        return false;
      }
    }

    try {
      checkNotNull(videoGraph).registerInput(inputIndex);
    } catch (VideoFrameProcessingException e) {
      throw new VideoSink.VideoSinkException(e, sourceFormat);
    }
    registeredVideoInputCount++;
    return true;
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

  private boolean isReady(boolean otherwiseReady) {
    return defaultVideoSink.isReady(/* otherwiseReady= */ otherwiseReady && pendingFlushCount == 0);
  }

  private void signalEndOfVideoGraphOutputStream() {
    // TODO: b/257464707 - Support extensively modified media.
    defaultVideoSink.signalEndOfCurrentInputStream();
    hasSignaledEndOfVideoGraphOutputStream = true;
  }

  private boolean isEnded() {
    return pendingFlushCount == 0
        && hasSignaledEndOfVideoGraphOutputStream
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
    while (pendingStreamChanges.size() > 1) {
      pendingStreamChanges.pollFirst();
    }
    if (pendingStreamChanges.size() == 1) {
      // Use the latest stream change info if none is passed after flushing.
      StreamChangeInfo streamChangeInfo = checkNotNull(pendingStreamChanges.pollFirst());
      outputStreamStartPositionUs = streamChangeInfo.startPositionUs;
      outputStreamFirstFrameReleaseInstruction = streamChangeInfo.firstFrameReleaseInstruction;
      onOutputStreamChanged();
    }
    lastOutputFramePresentationTimeUs = C.TIME_UNSET;
    finalFramePresentationTimeUs = C.TIME_UNSET;
    hasSignaledEndOfVideoGraphOutputStream = false;
    // Handle pending video graph callbacks to ensure video size changes reach the video render
    // control.
    checkNotNull(handler).post(() -> pendingFlushCount--);
  }

  private void joinPlayback(boolean renderNextFrameImmediately) {
    defaultVideoSink.join(renderNextFrameImmediately);
  }

  private void allowReleaseFirstFrameBeforeStarted() {
    defaultVideoSink.allowReleaseFirstFrameBeforeStarted();
  }

  private void setVideoFrameMetadataListener(
      VideoFrameMetadataListener videoFrameMetadataListener) {
    this.videoFrameMetadataListener = videoFrameMetadataListener;
    defaultVideoSink.setVideoFrameMetadataListener(videoFrameMetadataListener);
  }

  private void setPlaybackSpeed(float speed) {
    videoFrameReleaseEarlyTimeForecaster.setPlaybackSpeed(speed);
    defaultVideoSink.setPlaybackSpeed(speed);
  }

  private void setChangeFrameRateStrategy(
      @C.VideoChangeFrameRateStrategy int changeFrameRateStrategy) {
    defaultVideoSink.setChangeFrameRateStrategy(changeFrameRateStrategy);
  }

  private boolean shouldRenderToInputVideoSink() {
    return totalVideoInputCount != C.LENGTH_UNSET
        && totalVideoInputCount == registeredVideoInputCount;
  }

  private ColorInfo getAdjustedInputColorInfo(@Nullable ColorInfo inputColorInfo) {
    if (inputColorInfo == null || !inputColorInfo.isDataSpaceValid() || isInputSdrToneMapped) {
      return ColorInfo.SDR_BT709_LIMITED;
    }

    return inputColorInfo;
  }

  private void onOutputStreamChanged() {
    defaultVideoSink.onInputStreamChanged(
        INPUT_TYPE_SURFACE,
        videoGraphOutputFormat,
        outputStreamStartPositionUs,
        outputStreamFirstFrameReleaseInstruction,
        /* videoEffects= */ ImmutableList.of());
  }

  /** Receives input from an ExoPlayer renderer and forwards it to the video graph. */
  private final class InputVideoSink implements VideoSink, PlaybackVideoGraphWrapper.Listener {
    /**
     * The maximum number of consecutive dropped input frames.
     *
     * <p>Dropping too many consecutive input buffers can harm user experience.
     */
    private static final int MAX_CONSECUTIVE_FRAMES_TO_DROP = 2;

    private final int videoFrameProcessorMaxPendingFrameCount;
    private final int inputIndex;

    private ImmutableList<Effect> videoEffects;
    @Nullable private Format inputFormat;
    private @InputType int inputType;
    private long inputBufferTimestampAdjustmentUs;

    /** The frame presentation timestamp, in microseconds, of the most recently registered frame. */
    private long lastFramePresentationTimeUs;

    /** The number of consecutive dropped input frames. */
    private int consecutiveDroppedFrames;

    private VideoSink.Listener listener;
    private Executor listenerExecutor;
    private boolean signaledEndOfStream;
    private boolean isInitialized;

    /** Creates a new instance. */
    public InputVideoSink(Context context, int inputIndex) {
      // TODO b/226330223 - Investigate increasing frame count when frame dropping is allowed.
      // TODO b/278234847 - Evaluate whether limiting frame count when frame dropping is not allowed
      //  reduces decoder timeouts, and consider restoring.
      this.inputIndex = inputIndex;
      videoFrameProcessorMaxPendingFrameCount =
          getMaxPendingFramesCountForMediaCodecDecoders(context);
      videoEffects = ImmutableList.of();
      lastFramePresentationTimeUs = C.TIME_UNSET;
      listener = VideoSink.Listener.NO_OP;
      listenerExecutor = NO_OP_EXECUTOR;
    }

    @Override
    public void startRendering() {
      if (enablePlaylistMode) {
        PlaybackVideoGraphWrapper.this.startRendering();
      }
    }

    @Override
    public void stopRendering() {
      if (enablePlaylistMode) {
        PlaybackVideoGraphWrapper.this.stopRendering();
      }
    }

    @Override
    public void setListener(Listener listener, Executor executor) {
      this.listener = listener;
      listenerExecutor = executor;
    }

    @Override
    public boolean initialize(Format sourceFormat) throws VideoSinkException {
      checkState(!isInitialized());
      isInitialized = PlaybackVideoGraphWrapper.this.registerInput(sourceFormat, inputIndex);
      return isInitialized;
    }

    @Override
    public boolean isInitialized() {
      return isInitialized;
    }

    @Override
    public void redraw() {
      if (!isInitialized()) {
        return;
      }
      // Resignal EOS only for the last item.
      boolean needsResignalEndOfCurrentInputStream = signaledEndOfStream;
      long replayedPresentationTimeUs = lastOutputFramePresentationTimeUs;
      PlaybackVideoGraphWrapper.this.flush(/* resetPosition= */ false);
      checkNotNull(videoGraph).redraw();
      lastOutputFramePresentationTimeUs = replayedPresentationTimeUs;
      if (needsResignalEndOfCurrentInputStream) {
        signalEndOfCurrentInputStream();
      }
    }

    @Override
    public void flush(boolean resetPosition) {
      if (isInitialized()) {
        checkNotNull(videoGraph).flush();
      }
      lastFramePresentationTimeUs = C.TIME_UNSET;
      PlaybackVideoGraphWrapper.this.flush(resetPosition);
      signaledEndOfStream = false;
      // Don't change input stream start position or reset the pending input stream timestamp info
      // change so that it's announced with the next input frame.
      // Don't reset isInputStreamChangePending because it's not guaranteed to receive a new input
      // stream after seeking.
    }

    @Override
    public boolean isReady(boolean otherwiseReady) {
      return PlaybackVideoGraphWrapper.this.isReady(
          /* otherwiseReady= */ otherwiseReady && isInitialized());
    }

    @Override
    public void signalEndOfCurrentInputStream() {
      finalFramePresentationTimeUs = lastFramePresentationTimeUs;
      if (lastOutputFramePresentationTimeUs >= finalFramePresentationTimeUs) {
        PlaybackVideoGraphWrapper.this.signalEndOfVideoGraphOutputStream();
      }
    }

    @Override
    public void signalEndOfInput() {
      if (signaledEndOfStream) {
        return;
      }
      if (isInitialized()) {
        checkNotNull(videoGraph).signalEndOfInput(inputIndex);
        signaledEndOfStream = true;
      }
    }

    @Override
    public boolean isEnded() {
      return isInitialized() && PlaybackVideoGraphWrapper.this.isEnded();
    }

    @Override
    public void onInputStreamChanged(
        @InputType int inputType,
        Format format,
        long startPositionUs,
        @FirstFrameReleaseInstruction int firstFrameReleaseInstruction,
        List<Effect> videoEffects) {
      checkState(isInitialized());
      this.videoEffects = ImmutableList.copyOf(videoEffects);
      this.inputType = inputType;
      this.inputFormat = format;
      finalFramePresentationTimeUs = C.TIME_UNSET;
      hasSignaledEndOfVideoGraphOutputStream = false;
      registerInputStream(format);
      boolean isFirstStream = lastFramePresentationTimeUs == C.TIME_UNSET;
      if (enablePlaylistMode || (inputIndex == PRIMARY_SEQUENCE_INDEX && isFirstStream)) {
        long fromTimestampUs;
        if (isFirstStream) {
          // Add a stream change info to the queue with a large negative timestamp to always apply
          // it as long as it is the only one in the queue.
          fromTimestampUs = Long.MIN_VALUE / 2;
        } else {
          fromTimestampUs = lastFramePresentationTimeUs + 1;
        }
        pendingStreamChanges.add(
            fromTimestampUs,
            new StreamChangeInfo(
                /* startPositionUs= */ startPositionUs + inputBufferTimestampAdjustmentUs,
                firstFrameReleaseInstruction,
                fromTimestampUs));
      }
    }

    @Override
    public void allowReleaseFirstFrameBeforeStarted() {
      // We know that this sink is connected to renderers. Each renderer will first queue a stream
      // change that has firstFrameReleaseInstruction set to either RELEASE_FIRST_FRAME_IMMEDIATELY
      // or RELEASE_FIRST_FRAME_WHEN_STARTED, and then queue stream changes that have
      // firstFrameReleaseInstruction set to RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED.
      // When a renderer queues the first stream change, all previous streams should have been fully
      // processed.
      // We want to release the first frame immediately if the firstFrameReleaseInstruction of the
      // first stream change queued by the current renderer was RELEASE_FIRST_FRAME_WHEN_STARTED and
      // the first frame hasn't been released yet.
      if (pendingStreamChanges.size() == 0) {
        // All the stream changes have already been processed by the VideoGraph. Delegate to the
        // downstream component.
        PlaybackVideoGraphWrapper.this.allowReleaseFirstFrameBeforeStarted();
        return;
      }
      TimedValueQueue<StreamChangeInfo> newPendingStreamChanges = new TimedValueQueue<>();
      boolean isFirstStreamChange = true;
      while (pendingStreamChanges.size() > 0) {
        StreamChangeInfo streamChangeInfo = checkNotNull(pendingStreamChanges.pollFirst());
        if (isFirstStreamChange) {
          if (streamChangeInfo.firstFrameReleaseInstruction == RELEASE_FIRST_FRAME_IMMEDIATELY
              || streamChangeInfo.firstFrameReleaseInstruction
                  == RELEASE_FIRST_FRAME_WHEN_STARTED) {
            // The first stream change hasn't been processed by the VideoGraph yet.
            streamChangeInfo =
                new StreamChangeInfo(
                    streamChangeInfo.startPositionUs,
                    RELEASE_FIRST_FRAME_IMMEDIATELY,
                    streamChangeInfo.fromTimestampUs);
          } else {
            // The first stream change has already been processed by the VideoGraph. Delegate to the
            // downstream component.
            PlaybackVideoGraphWrapper.this.allowReleaseFirstFrameBeforeStarted();
          }
          isFirstStreamChange = false;
        }
        newPendingStreamChanges.add(streamChangeInfo.fromTimestampUs, streamChangeInfo);
      }
      pendingStreamChanges = newPendingStreamChanges;
    }

    @Override
    public Surface getInputSurface() {
      checkState(isInitialized());
      return checkNotNull(videoGraph).getInputSurface(inputIndex);
    }

    @Override
    public void setVideoFrameMetadataListener(
        VideoFrameMetadataListener videoFrameMetadataListener) {
      if (inputIndex == PRIMARY_SEQUENCE_INDEX) {
        PlaybackVideoGraphWrapper.this.setVideoFrameMetadataListener(videoFrameMetadataListener);
      }
    }

    @Override
    public void setPlaybackSpeed(@FloatRange(from = 0, fromInclusive = false) float speed) {
      if (inputIndex == PRIMARY_SEQUENCE_INDEX) {
        PlaybackVideoGraphWrapper.this.setPlaybackSpeed(speed);
      }
    }

    @Override
    public void setVideoEffects(List<Effect> videoEffects) {
      if (this.videoEffects.equals(videoEffects)) {
        return;
      }
      this.videoEffects = ImmutableList.copyOf(videoEffects);
      if (inputFormat != null) {
        registerInputStream(inputFormat);
      }
    }

    @Override
    public void setBufferTimestampAdjustmentUs(long bufferTimestampAdjustmentUs) {
      inputBufferTimestampAdjustmentUs = bufferTimestampAdjustmentUs;
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
      if (inputIndex == PRIMARY_SEQUENCE_INDEX) {
        PlaybackVideoGraphWrapper.this.setChangeFrameRateStrategy(changeFrameRateStrategy);
      }
    }

    @Override
    public boolean handleInputFrame(
        long bufferPresentationTimeUs, VideoFrameHandler videoFrameHandler) {
      checkState(isInitialized());

      long framePresentationTimeUs = bufferPresentationTimeUs + inputBufferTimestampAdjustmentUs;
      long predictedEarlyUs =
          videoFrameReleaseEarlyTimeForecaster.predictEarlyUs(framePresentationTimeUs);
      if (predictedEarlyUs != C.TIME_UNSET
          && earlyThresholdToDropInputUs != C.TIME_UNSET
          && predictedEarlyUs < earlyThresholdToDropInputUs
          && consecutiveDroppedFrames < MAX_CONSECUTIVE_FRAMES_TO_DROP) {
        consecutiveDroppedFrames += 1;
        videoFrameHandler.skip();
        return true;
      }
      if (!shouldRenderToInputVideoSink()) {
        return false;
      }
      if (checkNotNull(videoGraph).getPendingInputFrameCount(inputIndex)
          >= videoFrameProcessorMaxPendingFrameCount) {
        return false;
      }
      if (!checkNotNull(videoGraph).registerInputFrame(inputIndex)) {
        return false;
      }

      // The sink takes in frames with monotonically increasing, non-offset frame
      // timestamps. That is, with two 10s long videos, the first frame of the second video should
      // bear a timestamp of 10s seen from VideoFrameProcessor; while in ExoPlayer, the timestamp of
      // the said frame would be 0s, but the streamOffset is incremented by 10s to include the
      // duration of the first video. Thus this correction is needed to account for the different
      // handling of presentation timestamps in ExoPlayer and VideoFrameProcessor.
      //
      // inputBufferTimestampAdjustmentUs adjusts the buffer timestamp (that corresponds to the
      // player position) to the frame presentation time (which is relative to the start of a
      // composition).
      lastFramePresentationTimeUs = framePresentationTimeUs;
      // Use the frame presentation time as render time so that the SurfaceTexture is accompanied
      // by this timestamp. Setting a realtime based release time is only relevant when rendering to
      // a SurfaceView, but we render to a surface in this case.
      videoFrameHandler.render(/* renderTimestampNs= */ framePresentationTimeUs * 1000);
      consecutiveDroppedFrames = 0;
      return true;
    }

    @Override
    public boolean handleInputBitmap(
        Bitmap inputBitmap, TimestampIterator bufferTimestampIterator) {
      checkState(isInitialized());
      if (!shouldRenderToInputVideoSink()) {
        return false;
      }
      TimestampIterator frameTimestampIterator =
          new ShiftingTimestampIterator(bufferTimestampIterator, inputBufferTimestampAdjustmentUs);
      if (!checkNotNull(videoGraph)
          .queueInputBitmap(inputIndex, inputBitmap, frameTimestampIterator)) {
        return false;
      }

      long lastFramePresentationTimeUs = frameTimestampIterator.getLastTimestampUs();
      checkState(lastFramePresentationTimeUs != C.TIME_UNSET);
      this.lastFramePresentationTimeUs = lastFramePresentationTimeUs;
      return true;
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws VideoSinkException {
      PlaybackVideoGraphWrapper.this.render(
          /* positionUs= */ positionUs + inputBufferTimestampAdjustmentUs, elapsedRealtimeUs);
    }

    @Override
    public void join(boolean renderNextFrameImmediately) {
      if (enablePlaylistMode) {
        PlaybackVideoGraphWrapper.this.joinPlayback(renderNextFrameImmediately);
      }
    }

    @Override
    public void release() {
      PlaybackVideoGraphWrapper.this.release();
    }

    // PlaybackVideoGraphWrapper.Listener implementation

    @Override
    public void onFrameAvailableForRendering() {
      VideoSink.Listener currentListener = listener;
      listenerExecutor.execute(currentListener::onFrameAvailableForRendering);
    }

    @Override
    public void onFirstFrameRendered() {
      VideoSink.Listener currentListener = listener;
      listenerExecutor.execute(currentListener::onFirstFrameRendered);
    }

    @Override
    public void onFrameDropped() {
      VideoSink.Listener currentListener = listener;
      listenerExecutor.execute(currentListener::onFrameDropped);
    }

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
      VideoSink.Listener currentListener = listener;
      listenerExecutor.execute(() -> currentListener.onVideoSizeChanged(videoSize));
    }

    @Override
    public void onError(VideoFrameProcessingException videoFrameProcessingException) {
      VideoSink.Listener currentListener = listener;
      listenerExecutor.execute(
          () ->
              currentListener.onError(
                  new VideoSinkException(
                      videoFrameProcessingException, checkNotNull(this.inputFormat))));
    }

    // Private methods

    private void registerInputStream(Format inputFormat) {
      Format adjustedInputFormat =
          inputFormat
              .buildUpon()
              .setColorInfo(getAdjustedInputColorInfo(inputFormat.colorInfo))
              .build();
      @VideoFrameProcessor.InputType
      int videoGraphInputType =
          inputType == INPUT_TYPE_SURFACE
              ? VideoFrameProcessor.INPUT_TYPE_SURFACE
              : VideoFrameProcessor.INPUT_TYPE_BITMAP;
      checkNotNull(videoGraph)
          .registerInputStream(
              inputIndex,
              videoGraphInputType,
              adjustedInputFormat,
              videoEffects,
              /* offsetToAddUs= */ 0);
    }
  }

  private final class DefaultVideoSinkListener implements VideoSink.Listener {

    @Override
    public void onFirstFrameRendered() {
      for (PlaybackVideoGraphWrapper.Listener listener : listeners) {
        listener.onFirstFrameRendered();
      }
    }

    @Override
    public void onFrameDropped() {
      for (PlaybackVideoGraphWrapper.Listener listener : listeners) {
        listener.onFrameDropped();
      }
    }

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
      for (PlaybackVideoGraphWrapper.Listener listener : listeners) {
        listener.onVideoSizeChanged(videoSize);
      }
    }

    @Override
    public void onError(VideoSink.VideoSinkException videoSinkException) {
      for (PlaybackVideoGraphWrapper.Listener listener : listeners) {
        listener.onError(VideoFrameProcessingException.from(videoSinkException));
      }
    }
  }

  private static final class StreamChangeInfo {
    public final long startPositionUs;
    public final @VideoSink.FirstFrameReleaseInstruction int firstFrameReleaseInstruction;
    public final long fromTimestampUs;

    public StreamChangeInfo(
        long startPositionUs,
        @VideoSink.FirstFrameReleaseInstruction int firstFrameReleaseInstruction,
        long fromTimestampUs) {
      this.startPositionUs = startPositionUs;
      this.firstFrameReleaseInstruction = firstFrameReleaseInstruction;
      this.fromTimestampUs = fromTimestampUs;
    }
  }

  private static final class ShiftingTimestampIterator implements TimestampIterator {

    private final TimestampIterator timestampIterator;
    private final long shift;

    public ShiftingTimestampIterator(TimestampIterator timestampIterator, long shift) {
      this.timestampIterator = timestampIterator;
      this.shift = shift;
    }

    @Override
    public boolean hasNext() {
      return timestampIterator.hasNext();
    }

    @Override
    public long next() {
      return timestampIterator.next() + shift;
    }

    @Override
    public TimestampIterator copyOf() {
      return new ShiftingTimestampIterator(timestampIterator.copyOf(), shift);
    }

    @Override
    public long getLastTimestampUs() {
      long unshiftedLastTimestampUs = timestampIterator.getLastTimestampUs();
      return unshiftedLastTimestampUs == C.TIME_UNSET
          ? C.TIME_UNSET
          : unshiftedLastTimestampUs + shift;
    }
  }

  /** Delays reflection for loading a {@link VideoGraph.Factory SingleInputVideoGraph} instance. */
  private static final class ReflectiveSingleInputVideoGraphFactory implements VideoGraph.Factory {

    private final VideoFrameProcessor.Factory videoFrameProcessorFactory;

    public ReflectiveSingleInputVideoGraphFactory(boolean enableReplayableCache) {
      this.videoFrameProcessorFactory =
          new ReflectiveDefaultVideoFrameProcessorFactory(enableReplayableCache);
    }

    @Override
    public VideoGraph create(
        Context context,
        ColorInfo outputColorInfo,
        DebugViewProvider debugViewProvider,
        VideoGraph.Listener listener,
        Executor listenerExecutor,
        long initialTimestampOffsetUs,
        boolean renderFramesAutomatically) {
      VideoGraph.Factory factory;
      try {
        // LINT.IfChange
        Class<?> singleInputVideoGraphFactoryClass =
            Class.forName("androidx.media3.effect.SingleInputVideoGraph$Factory");
        factory =
            (VideoGraph.Factory)
                singleInputVideoGraphFactoryClass
                    .getConstructor(VideoFrameProcessor.Factory.class)
                    .newInstance(videoFrameProcessorFactory);
        // LINT.ThenChange(
        //     ../../../../../../../proguard-rules.txt,
        //     ../ExoPlayerImpl.java:set_video_effects)
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
      return factory.create(
          context,
          outputColorInfo,
          debugViewProvider,
          listener,
          listenerExecutor,
          initialTimestampOffsetUs,
          renderFramesAutomatically);
    }

    @Override
    public boolean supportsMultipleInputs() {
      return false;
    }
  }

  /**
   * Delays reflection for loading a {@linkplain VideoFrameProcessor.Factory
   * DefaultVideoFrameProcessor.Factory} instance.
   */
  private static final class ReflectiveDefaultVideoFrameProcessorFactory
      implements VideoFrameProcessor.Factory {

    private static final Supplier<Class<?>> DEFAULT_VIDEO_FRAME_PROCESSOR_FACTORY_BUILDER_CLASS =
        Suppliers.memoize(
            () -> {
              try {
                // LINT.IfChange
                return Class.forName(
                    "androidx.media3.effect.DefaultVideoFrameProcessor$Factory$Builder");
                // LINT.ThenChange(../../../../../../../proguard-rules.txt)
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });

    private final boolean enableReplayableCache;

    public ReflectiveDefaultVideoFrameProcessorFactory(boolean enableReplayableCache) {
      this.enableReplayableCache = enableReplayableCache;
    }

    @Override
    public VideoFrameProcessor create(
        Context context,
        DebugViewProvider debugViewProvider,
        ColorInfo outputColorInfo,
        boolean renderFramesAutomatically,
        Executor listenerExecutor,
        VideoFrameProcessor.Listener listener)
        throws VideoFrameProcessingException {
      try {
        Class<?> defaultVideoFrameProcessorFactoryBuilderClass =
            DEFAULT_VIDEO_FRAME_PROCESSOR_FACTORY_BUILDER_CLASS.get();
        Object builder =
            defaultVideoFrameProcessorFactoryBuilderClass.getConstructor().newInstance();
        Method setUseReplayableCacheMethod =
            defaultVideoFrameProcessorFactoryBuilderClass.getMethod(
                "setEnableReplayableCache", boolean.class);
        setUseReplayableCacheMethod.invoke(builder, enableReplayableCache);
        VideoFrameProcessor.Factory factory =
            (VideoFrameProcessor.Factory)
                checkNotNull(
                    defaultVideoFrameProcessorFactoryBuilderClass
                        .getMethod("build")
                        .invoke(builder));
        return factory.create(
            context,
            debugViewProvider,
            outputColorInfo,
            renderFramesAutomatically,
            listenerExecutor,
            listener);
      } catch (Exception e) {
        throw new VideoFrameProcessingException(e);
      }
    }
  }
}
