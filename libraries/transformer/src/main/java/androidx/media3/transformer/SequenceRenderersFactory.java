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
package androidx.media3.transformer;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED;
import static androidx.media3.common.PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED;
import static androidx.media3.common.PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED;
import static androidx.media3.exoplayer.DefaultRenderersFactory.DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS;
import static androidx.media3.exoplayer.DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY;
import static androidx.media3.exoplayer.video.VideoSink.RELEASE_FIRST_FRAME_IMMEDIATELY;
import static androidx.media3.exoplayer.video.VideoSink.RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED;
import static androidx.media3.exoplayer.video.VideoSink.RELEASE_FIRST_FRAME_WHEN_STARTED;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.ConstantRateTimestampIterator;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.image.ImageDecoder;
import androidx.media3.exoplayer.image.ImageOutput;
import androidx.media3.exoplayer.image.ImageRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.exoplayer.video.VideoSink;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** A {@link RenderersFactory} for an {@link EditedMediaItemSequence}. */
/* package */ final class SequenceRenderersFactory implements RenderersFactory {

  interface CompositionRendererListener {

    /**
     * Called on {@link Renderer#render}.
     *
     * <p>Called on the playback thread.
     *
     * <p>This method should return quickly, and should not block if the renderer is unable to make
     * useful progress.
     *
     * @param compositionTimePositionUs The current media time in the {@link Composition} timescale
     *     in microseconds, measured at the start of the current iteration of the rendering loop.
     * @param elapsedRealtimeUs {@link SystemClock#elapsedRealtime()} in microseconds, measured at
     *     the start of the current iteration of the rendering loop.
     * @param compositionTimeOutputStreamStartPositionUs The start position of the buffer
     *     presentation timestamps of the stream, in the {@link Composition} timescale, in
     *     microseconds.
     */
    void onRender(
        long compositionTimePositionUs,
        long elapsedRealtimeUs,
        long compositionTimeOutputStreamStartPositionUs)
        throws ExoPlaybackException;
  }

  private static final int DEFAULT_FRAME_RATE = 30;

  private final Context context;
  private final PlaybackAudioGraphWrapper playbackAudioGraphWrapper;
  @Nullable private final VideoSink videoSink;
  @Nullable private final ImageDecoder.Factory imageDecoderFactory;
  private final int inputIndex;
  private final boolean videoPrewarmingEnabled;

  private @MonotonicNonNull SequenceAudioRenderer audioRenderer;
  private @MonotonicNonNull SequenceVideoRenderer primaryVideoRenderer;
  private @MonotonicNonNull SequenceVideoRenderer secondaryVideoRenderer;
  private @MonotonicNonNull SequenceImageRenderer imageRenderer;
  private @MonotonicNonNull CompositionRendererListener compositionRendererListener;
  private @MonotonicNonNull CompositionTextureListener compositionTextureListener;

  /** Creates a renderers factory for a player that will play video, image and audio. */
  public static SequenceRenderersFactory create(
      Context context,
      PlaybackAudioGraphWrapper playbackAudioGraphWrapper,
      VideoSink videoSink,
      ImageDecoder.Factory imageDecoderFactory,
      int inputIndex,
      boolean videoPrewarmingEnabled) {
    return new SequenceRenderersFactory(
        context,
        playbackAudioGraphWrapper,
        videoSink,
        imageDecoderFactory,
        inputIndex,
        videoPrewarmingEnabled);
  }

  private SequenceRenderersFactory(
      Context context,
      PlaybackAudioGraphWrapper playbackAudioGraphWrapper,
      @Nullable VideoSink videoSink,
      @Nullable ImageDecoder.Factory imageDecoderFactory,
      int inputIndex,
      boolean videoPrewarmingEnabled) {
    this.context = context;
    this.playbackAudioGraphWrapper = playbackAudioGraphWrapper;
    this.videoSink = videoSink;
    this.imageDecoderFactory = imageDecoderFactory;
    this.inputIndex = inputIndex;
    this.videoPrewarmingEnabled = videoPrewarmingEnabled;
  }

  public void setRequestMediaCodecToneMapping(boolean requestMediaCodecToneMapping) {
    if (primaryVideoRenderer != null) {
      primaryVideoRenderer.setRequestMediaCodecToneMapping(requestMediaCodecToneMapping);
    }
    if (secondaryVideoRenderer != null) {
      secondaryVideoRenderer.setRequestMediaCodecToneMapping(requestMediaCodecToneMapping);
    }
  }

  public void setOnRenderListener(CompositionRendererListener listener) {
    this.compositionRendererListener = listener;
    if (primaryVideoRenderer != null) {
      primaryVideoRenderer.setOnRenderListener(listener);
    }
    if (secondaryVideoRenderer != null) {
      secondaryVideoRenderer.setOnRenderListener(listener);
    }
    if (imageRenderer != null) {
      imageRenderer.setOnRenderListener(listener);
    }
    if (audioRenderer != null) {
      audioRenderer.setOnRenderListener(listener);
    }
  }

  public void setCompositionTextureListener(CompositionTextureListener compositionTextureListener) {
    this.compositionTextureListener = compositionTextureListener;
    if (primaryVideoRenderer != null) {
      primaryVideoRenderer.setCompositionTextureListener(compositionTextureListener);
    }
    if (secondaryVideoRenderer != null) {
      secondaryVideoRenderer.setCompositionTextureListener(compositionTextureListener);
    }
    if (imageRenderer != null) {
      imageRenderer.setCompositionTextureListener(compositionTextureListener);
    }
  }

  @Override
  public Renderer[] createRenderers(
      Handler eventHandler,
      VideoRendererEventListener videoRendererEventListener,
      AudioRendererEventListener audioRendererEventListener,
      TextOutput textRendererOutput,
      MetadataOutput metadataRendererOutput) {
    List<Renderer> renderers = new ArrayList<>();
    if (audioRenderer == null) {
      audioRenderer =
          new SequenceAudioRenderer(
              context,
              eventHandler,
              audioRendererEventListener,
              /* audioSink= */ playbackAudioGraphWrapper.createInput(inputIndex),
              playbackAudioGraphWrapper);
    }
    if (compositionRendererListener != null) {
      audioRenderer.setOnRenderListener(compositionRendererListener);
    }
    renderers.add(audioRenderer);

    if (videoSink != null) {
      if (primaryVideoRenderer == null) {
        primaryVideoRenderer =
            new SequenceVideoRenderer(
                context, eventHandler, videoRendererEventListener, new BufferingVideoSink(context));
      }
      if (compositionRendererListener != null) {
        primaryVideoRenderer.setOnRenderListener(compositionRendererListener);
      }
      if (compositionTextureListener != null) {
        primaryVideoRenderer.setCompositionTextureListener(compositionTextureListener);
      }
      renderers.add(primaryVideoRenderer);
      if (imageRenderer == null) {
        imageRenderer = new SequenceImageRenderer(checkNotNull(imageDecoderFactory), videoSink);
      }
      if (compositionRendererListener != null) {
        imageRenderer.setOnRenderListener(compositionRendererListener);
      }
      if (compositionTextureListener != null) {
        imageRenderer.setCompositionTextureListener(compositionTextureListener);
      }
      renderers.add(imageRenderer);
    }
    return renderers.toArray(new Renderer[0]);
  }

  @Nullable
  @Override
  public Renderer createSecondaryRenderer(
      Renderer renderer,
      Handler eventHandler,
      VideoRendererEventListener videoRendererEventListener,
      AudioRendererEventListener audioRendererEventListener,
      TextOutput textRendererOutput,
      MetadataOutput metadataRendererOutput) {
    if (videoPrewarmingEnabled && renderer instanceof SequenceVideoRenderer) {
      if (secondaryVideoRenderer == null) {
        secondaryVideoRenderer =
            new SequenceVideoRenderer(
                context, eventHandler, videoRendererEventListener, new BufferingVideoSink(context));
      }
      if (compositionRendererListener != null) {
        secondaryVideoRenderer.setOnRenderListener(compositionRendererListener);
      }
      if (compositionTextureListener != null) {
        secondaryVideoRenderer.setCompositionTextureListener(compositionTextureListener);
      }
      return secondaryVideoRenderer;
    }
    return null;
  }

  /**
   * Returns the offset convert the renderers timestamp to the start of the {@link Composition}.
   *
   * @param timeline The {@link Timeline} associated with this renderer.
   * @param mediaPeriodId The {@link MediaSource.MediaPeriodId}.
   * @param offsetUs The offset added to timestamps of buffers to ensure monotonically increasing
   *     timestamps, in microseconds. This is the constant offset between the current MediaPeriod
   *     timestamps and the renderer timestamp.
   *     <p>See <a
   *     href="https://developer.android.com/reference/androidx/media3/exoplayer/Renderer#timestamps-and-offsets">this
   *     corresponding topic on timestamps</a>.
   */
  private static long getOffsetToCompositionTimeUs(
      Timeline timeline, MediaSource.MediaPeriodId mediaPeriodId, long offsetUs) {
    Timeline.Period period =
        timeline.getPeriodByUid(mediaPeriodId.periodUid, new Timeline.Period());
    return -offsetUs + period.positionInWindowUs;
  }

  private static boolean isLastInSequence(
      Timeline timeline, MediaSource.MediaPeriodId mediaPeriodId) {
    // TODO: b/419479048 - Investigate whether this should always be false for looping sequences.
    return timeline.getIndexOfPeriod(mediaPeriodId.periodUid) == timeline.getPeriodCount() - 1;
  }

  private static EditedMediaItem getEditedMediaItem(
      Timeline timeline, MediaSource.MediaPeriodId mediaPeriodId) {
    int index = timeline.getIndexOfPeriod(mediaPeriodId.periodUid);
    Timeline.Period period =
        timeline.getPeriodByUid(mediaPeriodId.periodUid, new Timeline.Period());
    checkState(period.id instanceof EditedMediaItemSequence);
    EditedMediaItemSequence sequence = (EditedMediaItemSequence) period.id;
    return EditedMediaItemSequence.getEditedMediaItem(sequence, index);
  }

  private static final class SequenceAudioRenderer extends MediaCodecAudioRenderer {
    private final AudioGraphInputAudioSink audioSink;
    private final PlaybackAudioGraphWrapper playbackAudioGraphWrapper;

    private @MonotonicNonNull CompositionRendererListener compositionRendererListener;
    private long streamStartPositionUs;
    private long pendingOffsetToCompositionTimeUs;
    private long pendingOffsetToEditedMediaItemStartUs;

    // TODO: b/320007703 - Revisit the abstractions needed here (editedMediaItemProvider and
    //  Supplier<EditedMediaItem>) once we finish all the wiring to support multiple sequences.
    public SequenceAudioRenderer(
        Context context,
        @Nullable Handler eventHandler,
        @Nullable AudioRendererEventListener eventListener,
        AudioGraphInputAudioSink audioSink,
        PlaybackAudioGraphWrapper playbackAudioGraphWrapper) {
      super(context, MediaCodecSelector.DEFAULT, eventHandler, eventListener, audioSink);
      this.audioSink = audioSink;
      this.playbackAudioGraphWrapper = playbackAudioGraphWrapper;
    }

    // MediaCodecAudioRenderer methods

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
      super.render(positionUs, elapsedRealtimeUs);
      if (compositionRendererListener != null) {
        compositionRendererListener.onRender(
            positionUs + pendingOffsetToCompositionTimeUs,
            elapsedRealtimeUs,
            streamStartPositionUs + pendingOffsetToCompositionTimeUs);
      }
      try {
        while (playbackAudioGraphWrapper.processData()) {}
      } catch (ExportException
          | AudioSink.WriteException
          | AudioSink.InitializationException
          | AudioSink.ConfigurationException e) {
        throw createRendererException(e, /* format= */ null, ERROR_CODE_AUDIO_TRACK_WRITE_FAILED);
      }
    }

    @Override
    protected void onStreamChanged(
        Format[] formats,
        long startPositionUs,
        long offsetUs,
        MediaSource.MediaPeriodId mediaPeriodId)
        throws ExoPlaybackException {
      checkState(getTimeline().getWindowCount() == 1);

      streamStartPositionUs = startPositionUs;
      pendingOffsetToCompositionTimeUs =
          getOffsetToCompositionTimeUs(getTimeline(), mediaPeriodId, offsetUs);
      // We cannot use offsetUs for the first EditedMediaItem because the Timeline created by
      // ConcatenatingMediaSource2 returns the original start of the period, without taking into
      // account any clipping. For all other EditedMediaItems, offsetUs is aligned to the clipped
      // start.
      pendingOffsetToEditedMediaItemStartUs =
          getTimeline().getIndexOfPeriod(mediaPeriodId.periodUid) == 0
              ? -pendingOffsetToCompositionTimeUs
              : offsetUs;
      super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId);
    }

    @Override
    protected void onProcessedStreamChange() {
      super.onProcessedStreamChange();
      onMediaItemChanged();
    }

    @Override
    protected void onPositionReset(
        long positionUs, boolean joining, boolean sampleStreamIsResetToKeyFrame)
        throws ExoPlaybackException {
      super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame);
      onMediaItemChanged();
    }

    // Other methods

    private void onMediaItemChanged() {
      // The media item might have been repeated in the sequence.
      MediaSource.MediaPeriodId mediaPeriodId = checkNotNull(getMediaPeriodId());
      EditedMediaItem currentEditedMediaItem = getEditedMediaItem(getTimeline(), mediaPeriodId);
      audioSink.onMediaItemChanged(
          currentEditedMediaItem,
          pendingOffsetToCompositionTimeUs,
          pendingOffsetToEditedMediaItemStartUs,
          isLastInSequence(getTimeline(), mediaPeriodId));
    }

    private void setOnRenderListener(CompositionRendererListener compositionRendererListener) {
      this.compositionRendererListener = compositionRendererListener;
    }
  }

  private final class SequenceVideoRenderer extends MediaCodecVideoRenderer {

    private final BufferingVideoSink bufferingVideoSink;

    private ImmutableList<Effect> pendingEffects;
    @Nullable private CompositionRendererListener compositionRendererListener;
    private @MonotonicNonNull CompositionTextureListener compositionTextureListener;
    private long streamStartPositionUs;
    private long offsetToCompositionTimeUs;
    private boolean requestMediaCodecToneMapping;
    private long nextSampleExpectedTimestampUs;
    private long expectedTimestampDeltaUs;

    public SequenceVideoRenderer(
        Context context,
        Handler eventHandler,
        VideoRendererEventListener videoRendererEventListener,
        BufferingVideoSink bufferingVideoSink) {
      super(
          new Builder(context)
              .setMediaCodecSelector(MediaCodecSelector.DEFAULT)
              .setCodecAdapterFactory(MediaCodecAdapter.Factory.getDefault(context))
              .setAllowedJoiningTimeMs(DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS)
              .setEnableDecoderFallback(false)
              .setEventHandler(eventHandler)
              .setEventListener(videoRendererEventListener)
              .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
              .setAssumedMinimumCodecOperatingRate(DEFAULT_FRAME_RATE)
              .setVideoSink(bufferingVideoSink));
      this.bufferingVideoSink = bufferingVideoSink;
      this.pendingEffects = ImmutableList.of();
      nextSampleExpectedTimestampUs = C.TIME_UNSET;
      expectedTimestampDeltaUs = C.TIME_UNSET;
    }

    public void setRequestMediaCodecToneMapping(boolean requestMediaCodecToneMapping) {
      this.requestMediaCodecToneMapping = requestMediaCodecToneMapping;
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
      super.render(positionUs, elapsedRealtimeUs);
      if (compositionRendererListener != null) {
        compositionRendererListener.onRender(
            positionUs + offsetToCompositionTimeUs,
            elapsedRealtimeUs,
            streamStartPositionUs + offsetToCompositionTimeUs);
      }
    }

    @Override
    protected void onEnabled(boolean joining, boolean mayRenderStartOfStream)
        throws ExoPlaybackException {
      if (mayRenderStartOfStream) {
        // Activate the BufferingVideoSink before calling super.onEnabled(), so that it points to a
        // VideoSink when executing the super method.
        activateBufferingVideoSink();
      }
      super.onEnabled(joining, mayRenderStartOfStream);
    }

    @Override
    protected void onStarted() {
      // Activate the BufferingVideoSink before calling super.onStarted(), so that it points to a
      // VideoSink when executing the super method.
      activateBufferingVideoSink();
      super.onStarted();
    }

    @Override
    protected void onDisabled() {
      super.onDisabled();
      deactivateBufferingVideoSink();
    }

    @Override
    protected void onRelease() {
      super.onRelease();
      bufferingVideoSink.release();
    }

    @Override
    protected void onPositionReset(
        long positionUs, boolean joining, boolean sampleStreamIsResetToKeyFrame)
        throws ExoPlaybackException {
      super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame);
      nextSampleExpectedTimestampUs = C.TIME_UNSET;
    }

    @Override
    protected void onStreamChanged(
        Format[] formats,
        long startPositionUs,
        long offsetUs,
        MediaSource.MediaPeriodId mediaPeriodId)
        throws ExoPlaybackException {
      checkState(getTimeline().getWindowCount() == 1);
      // The media item might have been repeated in the sequence.
      // The renderer has started processing this item, VideoGraph might still be processing the
      // previous one.
      EditedMediaItem editedMediaItem = getEditedMediaItem(getTimeline(), mediaPeriodId);
      streamStartPositionUs = startPositionUs;
      offsetToCompositionTimeUs =
          getOffsetToCompositionTimeUs(getTimeline(), mediaPeriodId, offsetUs);
      pendingEffects = editedMediaItem.effects.videoEffects;
      expectedTimestampDeltaUs =
          editedMediaItem.frameRate == C.RATE_UNSET_INT
              ? C.TIME_UNSET
              : C.MICROS_PER_SECOND / editedMediaItem.frameRate;
      super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId);
    }

    @Override
    protected boolean processOutputBuffer(
        long positionUs,
        long elapsedRealtimeUs,
        @Nullable MediaCodecAdapter codec,
        @Nullable ByteBuffer buffer,
        int bufferIndex,
        int bufferFlags,
        int sampleCount,
        long bufferPresentationTimeUs,
        boolean isDecodeOnlyBuffer,
        boolean isLastBuffer,
        Format format)
        throws ExoPlaybackException {
      long outputStreamOffsetUs = getOutputStreamOffsetUs();
      long presentationTimeUs = bufferPresentationTimeUs - outputStreamOffsetUs;
      // This algorithm will always pick the first sample that is after desired timestamp and then
      // it will start looking for the next desired timestamp.
      // For example, for a 30 fps, the desired timestamps are 0, 33_333, 66_666....
      // When seeking is performed, the desired timestamps are shifted accordingly.
      // For example, when seeking to 1 sec, the desired timestamps are 1_000_000, 1_033_333,
      // 1_066_666....
      // This algorithm has no impact if the target frame rate is greater that input frame rate.
      if (shouldMaintainTargetFrameRate()
          && nextSampleExpectedTimestampUs != C.TIME_UNSET
          && presentationTimeUs < nextSampleExpectedTimestampUs
          && !isLastBuffer) {
        skipOutputBuffer(checkNotNull(codec), bufferIndex, presentationTimeUs);
        return true;
      }
      if (super.processOutputBuffer(
          positionUs,
          elapsedRealtimeUs,
          codec,
          buffer,
          bufferIndex,
          bufferFlags,
          sampleCount,
          bufferPresentationTimeUs,
          isDecodeOnlyBuffer,
          isLastBuffer,
          format)) {
        if (shouldMaintainTargetFrameRate()) {
          nextSampleExpectedTimestampUs =
              (nextSampleExpectedTimestampUs == C.TIME_UNSET)
                  ? (presentationTimeUs + expectedTimestampDeltaUs)
                  : (nextSampleExpectedTimestampUs + expectedTimestampDeltaUs);
        }
        return true;
      }
      return false;
    }

    @Override
    protected MediaFormat getMediaFormat(
        Format format,
        String codecMimeType,
        CodecMaxValues codecMaxValues,
        float codecOperatingRate,
        boolean deviceNeedsNoPostProcessWorkaround,
        int tunnelingAudioSessionId) {
      MediaFormat mediaFormat =
          super.getMediaFormat(
              format,
              codecMimeType,
              codecMaxValues,
              codecOperatingRate,
              deviceNeedsNoPostProcessWorkaround,
              tunnelingAudioSessionId);
      if (requestMediaCodecToneMapping && SDK_INT >= 31) {
        mediaFormat.setInteger(
            MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
      }
      return mediaFormat;
    }

    @Override
    public void handleMessage(@MessageType int messageType, @Nullable Object message)
        throws ExoPlaybackException {
      if (messageType == MSG_TRANSFER_RESOURCES) {
        // Ignore MSG_TRANSFER_RESOURCES to avoid updating the VideoGraph's output surface.
        return;
      }
      super.handleMessage(messageType, message);
    }

    @Override
    protected boolean shouldInitCodec(MediaCodecInfo codecInfo) {
      if (videoPrewarmingEnabled
          && bufferingVideoSink.getVideoSink() == null
          && codecNeedsSetOutputSurfaceWorkaround(codecInfo.name)) {
        // Wait until the BufferingVideoSink points to the effect VideoSink to init the codec, so
        // that the codec output surface is set to the effect VideoSink input surface.
        return false;
      }
      return super.shouldInitCodec(codecInfo);
    }

    @Override
    protected long getBufferTimestampAdjustmentUs() {
      return offsetToCompositionTimeUs;
    }

    @Override
    protected void renderToEndOfStream() {
      super.renderToEndOfStream();
      if (isLastInSequence(getTimeline(), checkNotNull(getMediaPeriodId()))) {
        bufferingVideoSink.signalEndOfInput();
      }
    }

    @Override
    protected void changeVideoSinkInputStream(
        VideoSink videoSink,
        @VideoSink.InputType int inputType,
        Format format,
        @VideoSink.FirstFrameReleaseInstruction int firstFrameReleaseInstruction) {
      videoSink.onInputStreamChanged(
          inputType,
          format,
          getOutputStreamStartPositionUs(),
          firstFrameReleaseInstruction,
          pendingEffects);
    }

    @Override
    protected void renderOutputBufferV21(
        MediaCodecAdapter codec, int index, long presentationTimeUs, long releaseTimeNs) {
      if (compositionTextureListener != null) {
        // TODO: b/449957106 - support component reuse, and decouple the Composition from the
        // CompositionTextureListener.
        int indexOfItem =
            getTimeline().getIndexOfPeriod(checkNotNull(getMediaPeriodId()).periodUid);
        // releaseTimeNs is the timestamp that's carried over the Surface.
        compositionTextureListener.willOutputFrame(
            /* presentationTimeUs= */ releaseTimeNs / 1000, indexOfItem);
      }
      super.renderOutputBufferV21(codec, index, presentationTimeUs, releaseTimeNs);
    }

    private boolean shouldMaintainTargetFrameRate() {
      return expectedTimestampDeltaUs != C.TIME_UNSET;
    }

    private void activateBufferingVideoSink() {
      if (bufferingVideoSink.getVideoSink() != null) {
        return;
      }
      VideoSink frameProcessingVideoSink = checkNotNull(SequenceRenderersFactory.this.videoSink);
      bufferingVideoSink.setVideoSink(frameProcessingVideoSink);
      @Nullable MediaCodecAdapter codec = getCodec();
      if (videoPrewarmingEnabled
          && frameProcessingVideoSink.isInitialized()
          && codec != null
          && !codecNeedsSetOutputSurfaceWorkaround(checkNotNull(getCodecInfo()).name)) {
        setOutputSurfaceV23(codec, frameProcessingVideoSink.getInputSurface());
      }
    }

    private void deactivateBufferingVideoSink() {
      if (!videoPrewarmingEnabled) {
        return;
      }
      bufferingVideoSink.setVideoSink(null);
      // During a seek, it's possible for the renderer to be disabled without having been started.
      // When this happens, the BufferingVideoSink can have pending operations, so they need to be
      // cleared.
      bufferingVideoSink.clearPendingOperations();
      @Nullable MediaCodecAdapter codec = getCodec();
      if (codec == null) {
        return;
      }
      if (!codecNeedsSetOutputSurfaceWorkaround(checkNotNull(getCodecInfo()).name)) {
        // Sets a placeholder surface
        setOutputSurfaceV23(codec, bufferingVideoSink.getInputSurface());
      } else {
        releaseCodec();
      }
    }

    private void setOnRenderListener(CompositionRendererListener compositionRendererListener) {
      this.compositionRendererListener = compositionRendererListener;
    }

    private void setCompositionTextureListener(
        CompositionTextureListener compositionTextureListener) {
      this.compositionTextureListener = compositionTextureListener;
    }
  }

  private static final class SequenceImageRenderer extends ImageRenderer {

    private final VideoSink videoSink;

    private ImmutableList<Effect> videoEffects;
    private @MonotonicNonNull ConstantRateTimestampIterator timestampIterator;
    @Nullable private ExoPlaybackException pendingExoPlaybackException;
    private boolean inputStreamPending;
    private long streamStartPositionUs;
    private long offsetToCompositionTimeUs;
    private boolean mayRenderStartOfStream;
    private @VideoSink.FirstFrameReleaseInstruction int nextFirstFrameReleaseInstruction;
    private @MonotonicNonNull WakeupListener wakeupListener;
    private @MonotonicNonNull CompositionRendererListener compositionRendererListener;
    private @MonotonicNonNull CompositionTextureListener compositionTextureListener;

    public SequenceImageRenderer(ImageDecoder.Factory imageDecoderFactory, VideoSink videoSink) {
      super(imageDecoderFactory, ImageOutput.NO_OP);
      this.videoSink = videoSink;
      videoEffects = ImmutableList.of();
      streamStartPositionUs = C.TIME_UNSET;
    }

    // ImageRenderer methods

    @Override
    protected void onEnabled(boolean joining, boolean mayRenderStartOfStream)
        throws ExoPlaybackException {
      super.onEnabled(joining, mayRenderStartOfStream);
      this.mayRenderStartOfStream = mayRenderStartOfStream;
      nextFirstFrameReleaseInstruction =
          mayRenderStartOfStream
              ? RELEASE_FIRST_FRAME_IMMEDIATELY
              : RELEASE_FIRST_FRAME_WHEN_STARTED;
      // TODO: b/328444280 - Unregister as a listener when the renderer is not used anymore
      videoSink.setListener(
          new VideoSink.Listener() {
            @Override
            public void onFrameAvailableForRendering() {
              if (wakeupListener != null) {
                wakeupListener.onWakeup();
              }
            }
          },
          directExecutor());
    }

    @Override
    public boolean isEnded() {
      return super.isEnded()
          && videoSink.isEnded()
          && (timestampIterator == null || !timestampIterator.hasNext());
    }

    @Override
    public boolean isReady() {
      if (mayRenderStartOfStream) {
        // The image renderer is not playing after a video. We must wait until the first frame is
        // rendered.
        return videoSink.isReady(/* otherwiseReady= */ super.isReady());
      } else {
        // The image renderer is playing after a video. We don't need to wait until the first frame
        // is rendered.
        return super.isReady();
      }
    }

    @Override
    protected void onReset() {
      super.onReset();
      pendingExoPlaybackException = null;
    }

    @Override
    protected void onPositionReset(
        long positionUs, boolean joining, boolean sampleStreamIsResetToKeyFrame)
        throws ExoPlaybackException {
      if (!joining) {
        videoSink.flush(/* resetPosition= */ true);
        timestampIterator = createTimestampIterator(positionUs);
      }
      super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame);
    }

    @Override
    protected boolean maybeInitializeProcessingPipeline() throws ExoPlaybackException {
      if (videoSink.isInitialized()) {
        return true;
      }
      Format format = new Format.Builder().build();
      try {
        return videoSink.initialize(format);
      } catch (VideoSink.VideoSinkException e) {
        throw createRendererException(e, format, ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED);
      }
    }

    @Override
    protected void onStreamChanged(
        Format[] formats,
        long startPositionUs,
        long offsetUs,
        MediaSource.MediaPeriodId mediaPeriodId)
        throws ExoPlaybackException {
      checkState(getTimeline().getWindowCount() == 1);
      streamStartPositionUs = startPositionUs;
      // The media item might have been repeated in the sequence.
      EditedMediaItem editedMediaItem = getEditedMediaItem(getTimeline(), mediaPeriodId);
      offsetToCompositionTimeUs =
          getOffsetToCompositionTimeUs(getTimeline(), mediaPeriodId, offsetUs);
      videoSink.setBufferTimestampAdjustmentUs(offsetToCompositionTimeUs);
      timestampIterator = createTimestampIterator(/* positionUs= */ startPositionUs);
      videoEffects = editedMediaItem.effects.videoEffects;
      inputStreamPending = true;
      super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId);
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
      if (pendingExoPlaybackException != null) {
        ExoPlaybackException exoPlaybackException = pendingExoPlaybackException;
        pendingExoPlaybackException = null;
        throw exoPlaybackException;
      }

      super.render(positionUs, elapsedRealtimeUs);
      if (compositionRendererListener != null) {
        compositionRendererListener.onRender(
            positionUs + offsetToCompositionTimeUs,
            elapsedRealtimeUs,
            streamStartPositionUs + offsetToCompositionTimeUs);
      }
      try {
        videoSink.render(positionUs, elapsedRealtimeUs);
      } catch (VideoSink.VideoSinkException e) {
        throw createRendererException(e, e.format, ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED);
      }
    }

    @Override
    protected boolean processOutputBuffer(
        long positionUs, long elapsedRealtimeUs, Bitmap outputImage, long timeUs) {
      if (inputStreamPending) {
        checkState(streamStartPositionUs != C.TIME_UNSET);
        videoSink.onInputStreamChanged(
            VideoSink.INPUT_TYPE_BITMAP,
            new Format.Builder()
                .setSampleMimeType(MimeTypes.IMAGE_RAW)
                .setWidth(outputImage.getWidth())
                .setHeight(outputImage.getHeight())
                .setColorInfo(ColorInfo.SRGB_BT709_FULL)
                .setFrameRate(/* frameRate= */ DEFAULT_FRAME_RATE)
                .build(),
            streamStartPositionUs,
            nextFirstFrameReleaseInstruction,
            videoEffects);
        nextFirstFrameReleaseInstruction = RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED;
        inputStreamPending = false;
      }
      TimestampIterator copiedTimestampIterator = null;
      if (compositionTextureListener != null) {
        copiedTimestampIterator = checkNotNull(timestampIterator).copyOf();
      }
      if (!videoSink.handleInputBitmap(outputImage, checkNotNull(timestampIterator))) {
        return false;
      }
      if (compositionTextureListener != null) {
        // TODO: b/449957106 - support component reuse, and decouple the Composition from the
        // CompositionTextureListener.
        int indexOfItem =
            getTimeline().getIndexOfPeriod(checkNotNull(getMediaPeriodId()).periodUid);
        checkNotNull(copiedTimestampIterator);
        // TODO: b/449957106 - if performance is a concern, send a TimestampIterator to
        // the CompositionTextureListener many individual frames.
        while (copiedTimestampIterator.hasNext()) {
          long timestampUs = copiedTimestampIterator.next();
          compositionTextureListener.willOutputFrame(
              /* presentationTimeUs= */ timestampUs + offsetToCompositionTimeUs, indexOfItem);
        }
      }
      videoSink.signalEndOfCurrentInputStream();
      if (isLastInSequence(getTimeline(), checkNotNull(getMediaPeriodId()))) {
        videoSink.signalEndOfInput();
      }
      return true;
    }

    @Override
    public void handleMessage(@MessageType int messageType, @Nullable Object message)
        throws ExoPlaybackException {
      switch (messageType) {
        case MSG_SET_WAKEUP_LISTENER:
          this.wakeupListener = (WakeupListener) checkNotNull(message);
          break;
        case MSG_SET_VIDEO_FRAME_METADATA_LISTENER:
          videoSink.setVideoFrameMetadataListener(
              (VideoFrameMetadataListener) checkNotNull(message));
          break;
        default:
          super.handleMessage(messageType, message);
      }
    }

    private ConstantRateTimestampIterator createTimestampIterator(long positionUs) {
      EditedMediaItem editedMediaItem =
          getEditedMediaItem(getTimeline(), checkNotNull(getMediaPeriodId()));
      long lastBitmapTimeUs = getStreamOffsetUs() + editedMediaItem.getPresentationDurationUs();
      return new ConstantRateTimestampIterator(
          /* startPositionUs= */ positionUs,
          /* endPositionUs= */ lastBitmapTimeUs,
          DEFAULT_FRAME_RATE);
    }

    private void setOnRenderListener(CompositionRendererListener compositionRendererListener) {
      this.compositionRendererListener = compositionRendererListener;
    }

    private void setCompositionTextureListener(
        CompositionTextureListener compositionTextureListener) {
      this.compositionTextureListener = compositionTextureListener;
    }
  }
}
