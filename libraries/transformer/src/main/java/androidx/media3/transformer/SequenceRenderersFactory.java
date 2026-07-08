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
import static androidx.media3.transformer.TransformerUtil.getEditedMediaItem;
import static androidx.media3.transformer.TransformerUtil.getOffsetToCompositionTimeUs;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
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
import androidx.media3.common.util.NullableType;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.image.ImageDecoder;
import androidx.media3.exoplayer.image.ImageMetadataListener;
import androidx.media3.exoplayer.image.ImageOutput;
import androidx.media3.exoplayer.image.ImageRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.exoplayer.video.VideoSink;
import androidx.media3.transformer.HardwareBufferFrameReader.RendererWakeupListener;
import com.google.common.base.Supplier;
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

    /**
     * Called on {@link Renderer#isEnded}.
     *
     * <p>Called on the playback thread.
     */
    boolean isEnded();
  }

  private static final int DEFAULT_FRAME_RATE = 30;

  private final Context context;
  private final PlaybackAudioGraphWrapper playbackAudioGraphWrapper;
  @Nullable private final VideoSink videoSink;
  @Nullable private final ImageDecoder.Factory imageDecoderFactory;
  private final int inputIndex;
  private final boolean videoPrewarmingEnabled;
  @Nullable private final CompositionRendererListener compositionRendererListener;
  private final Supplier<@NullableType HardwareBufferFrameReader> hardwareBufferFrameReaderSupplier;
  private final long lateThresholdToDropInputUs;

  private @MonotonicNonNull SequenceAudioRenderer audioRenderer;
  private @MonotonicNonNull SequenceVideoRenderer primaryVideoRenderer;
  private @MonotonicNonNull SequenceVideoRenderer secondaryVideoRenderer;
  private @MonotonicNonNull SequenceImageRenderer imageRenderer;

  /** Creates an instance for rendering to a {@link VideoSink}. */
  public static SequenceRenderersFactory createForVideoSink(
      Context context,
      PlaybackAudioGraphWrapper playbackAudioGraphWrapper,
      VideoSink videoSink,
      @Nullable ImageDecoder.Factory imageDecoderFactory,
      int inputIndex,
      boolean videoPrewarmingEnabled,
      long lateThresholdToDropInputUs) {
    return new SequenceRenderersFactory(
        context,
        playbackAudioGraphWrapper,
        videoSink,
        imageDecoderFactory,
        inputIndex,
        videoPrewarmingEnabled,
        /* compositionRendererListener= */ null,
        /* hardwareBufferFrameReaderSupplier= */ () -> null,
        lateThresholdToDropInputUs);
  }

  /** Creates an instance for rendering to a {@link HardwareBufferFrameReader}. */
  public static SequenceRenderersFactory createForHardwareBuffer(
      Context context,
      PlaybackAudioGraphWrapper playbackAudioGraphWrapper,
      @Nullable ImageDecoder.Factory imageDecoderFactory,
      int inputIndex,
      boolean videoPrewarmingEnabled,
      CompositionRendererListener compositionRendererListener,
      Supplier<@NullableType HardwareBufferFrameReader> hardwareBufferFrameReaderSupplier,
      long lateThresholdToDropInputUs) {
    return new SequenceRenderersFactory(
        context,
        playbackAudioGraphWrapper,
        /* videoSink= */ null,
        imageDecoderFactory,
        inputIndex,
        videoPrewarmingEnabled,
        compositionRendererListener,
        hardwareBufferFrameReaderSupplier,
        lateThresholdToDropInputUs);
  }

  private SequenceRenderersFactory(
      Context context,
      PlaybackAudioGraphWrapper playbackAudioGraphWrapper,
      @Nullable VideoSink videoSink,
      @Nullable ImageDecoder.Factory imageDecoderFactory,
      int inputIndex,
      boolean videoPrewarmingEnabled,
      @Nullable CompositionRendererListener compositionRendererListener,
      Supplier<@NullableType HardwareBufferFrameReader> hardwareBufferFrameReaderSupplier,
      long lateThresholdToDropInputUs) {
    this.context = context;
    this.playbackAudioGraphWrapper = playbackAudioGraphWrapper;
    this.videoSink = videoSink;
    this.imageDecoderFactory = imageDecoderFactory;
    this.inputIndex = inputIndex;
    this.videoPrewarmingEnabled = videoPrewarmingEnabled;
    this.compositionRendererListener = compositionRendererListener;
    this.hardwareBufferFrameReaderSupplier = hardwareBufferFrameReaderSupplier;
    this.lateThresholdToDropInputUs = lateThresholdToDropInputUs;
  }

  public void setRequestMediaCodecToneMapping(boolean requestMediaCodecToneMapping) {
    if (primaryVideoRenderer != null) {
      primaryVideoRenderer.setRequestMediaCodecToneMapping(requestMediaCodecToneMapping);
    }
    if (secondaryVideoRenderer != null) {
      secondaryVideoRenderer.setRequestMediaCodecToneMapping(requestMediaCodecToneMapping);
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
    renderers.add(audioRenderer);

    if (videoSink != null) {
      if (primaryVideoRenderer == null) {
        primaryVideoRenderer =
            new SequenceVideoRenderer(
                context, eventHandler, videoRendererEventListener, new BufferingVideoSink(context));
      }
      renderers.add(primaryVideoRenderer);
      if (imageRenderer == null) {
        imageRenderer = new SequenceImageRenderer(checkNotNull(imageDecoderFactory), videoSink);
      }
      renderers.add(imageRenderer);
    } else {
      renderers.add(
          new HardwareBufferVideoRenderer(
              context,
              eventHandler,
              videoRendererEventListener,
              checkNotNull(compositionRendererListener),
              hardwareBufferFrameReaderSupplier,
              lateThresholdToDropInputUs));
      renderers.add(
          new HardwareBufferImageRenderer(
              checkNotNull(imageDecoderFactory),
              checkNotNull(compositionRendererListener),
              hardwareBufferFrameReaderSupplier));
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
    if (!videoPrewarmingEnabled) {
      return null;
    }
    if (renderer instanceof SequenceVideoRenderer) {
      if (secondaryVideoRenderer == null) {
        secondaryVideoRenderer =
            new SequenceVideoRenderer(
                context, eventHandler, videoRendererEventListener, new BufferingVideoSink(context));
      }
      return secondaryVideoRenderer;
    }
    if (renderer instanceof HardwareBufferVideoRenderer) {
      return new HardwareBufferVideoRenderer(
          context,
          eventHandler,
          videoRendererEventListener,
          checkNotNull(compositionRendererListener),
          hardwareBufferFrameReaderSupplier,
          lateThresholdToDropInputUs);
    }
    return null;
  }

  private static boolean isLastInSequence(Timeline timeline, MediaPeriodId mediaPeriodId) {
    // TODO: b/419479048 - Investigate whether this should always be false for looping sequences.
    return timeline.getIndexOfPeriod(mediaPeriodId.periodUid) == timeline.getPeriodCount() - 1;
  }

  private static final class SequenceAudioRenderer extends MediaCodecAudioRenderer {

    private final PlaybackAudioGraphWrapper playbackAudioGraphWrapper;

    public SequenceAudioRenderer(
        Context context,
        @Nullable Handler eventHandler,
        @Nullable AudioRendererEventListener eventListener,
        AudioGraphInputAudioSink audioSink,
        PlaybackAudioGraphWrapper playbackAudioGraphWrapper) {
      super(context, MediaCodecSelector.DEFAULT, eventHandler, eventListener, audioSink);
      this.playbackAudioGraphWrapper = playbackAudioGraphWrapper;
    }

    // MediaCodecAudioRenderer methods

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
      super.render(positionUs, elapsedRealtimeUs);
      try {
        while (playbackAudioGraphWrapper.processData()) {}
      } catch (ExportException
          | AudioSink.WriteException
          | AudioSink.InitializationException
          | AudioSink.ConfigurationException e) {
        throw createRendererException(e, /* format= */ null, ERROR_CODE_AUDIO_TRACK_WRITE_FAILED);
      }
    }
  }

  private final class SequenceVideoRenderer extends MediaCodecVideoRenderer {

    private final BufferingVideoSink bufferingVideoSink;
    private final TargetFrameRateHelper targetFrameRateHelper;

    private ImmutableList<Effect> pendingEffects;
    private long offsetToCompositionTimeUs;
    private boolean requestMediaCodecToneMapping;

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
      this.targetFrameRateHelper = new TargetFrameRateHelper();
      this.pendingEffects = ImmutableList.of();
    }

    public void setRequestMediaCodecToneMapping(boolean requestMediaCodecToneMapping) {
      this.requestMediaCodecToneMapping = requestMediaCodecToneMapping;
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
      targetFrameRateHelper.onPositionReset();
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
      offsetToCompositionTimeUs =
          getOffsetToCompositionTimeUs(getTimeline(), mediaPeriodId, offsetUs);
      pendingEffects = editedMediaItem.effects.videoEffects;
      targetFrameRateHelper.onStreamChanged(editedMediaItem);
      super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId);
    }

    @Override
    protected void onQueueInputBuffer(DecoderInputBuffer buffer) throws ExoPlaybackException {
      targetFrameRateHelper.onQueueInputBuffer(buffer, getCodecInputFormat());
      super.onQueueInputBuffer(buffer);
    }

    @Override
    protected int getCodecBufferFlags(DecoderInputBuffer buffer) {
      return super.getCodecBufferFlags(buffer) | targetFrameRateHelper.getCodecBufferFlags(buffer);
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
      if (targetFrameRateHelper.shouldDropOutputFrame(presentationTimeUs) && !isLastBuffer) {
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
        targetFrameRateHelper.onOutputFrameRendered(presentationTimeUs);
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
    @Nullable private ImageMetadataListener imageMetadataListener;
    private @MonotonicNonNull Format outputFormat;
    private long streamOffsetUs;
    private boolean listenerSet;

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
      if (mayRenderStartOfStream) {
        maybeSetVideoSinkListener();
      }
    }

    @Override
    protected void onStarted() throws ExoPlaybackException {
      super.onStarted();
      maybeSetVideoSinkListener();
    }

    @Override
    protected void onDisabled() {
      super.onDisabled();
      listenerSet = false;
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
      streamOffsetUs = offsetUs;
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
        outputFormat =
            new Format.Builder()
                .setSampleMimeType(MimeTypes.IMAGE_RAW)
                .setWidth(outputImage.getWidth())
                .setHeight(outputImage.getHeight())
                .setColorInfo(ColorInfo.SRGB_BT709_FULL)
                .setFrameRate(/* frameRate= */ DEFAULT_FRAME_RATE)
                .build();
        videoSink.onInputStreamChanged(
            VideoSink.INPUT_TYPE_BITMAP,
            outputFormat,
            streamStartPositionUs,
            nextFirstFrameReleaseInstruction,
            videoEffects);
        nextFirstFrameReleaseInstruction = RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED;
        inputStreamPending = false;
      }
      if (!videoSink.handleInputBitmap(outputImage, checkNotNull(timestampIterator))) {
        return false;
      }
      videoSink.signalEndOfCurrentInputStream();
      if (isLastInSequence(getTimeline(), checkNotNull(getMediaPeriodId()))) {
        videoSink.signalEndOfInput();
      }
      if (imageMetadataListener != null) {
        imageMetadataListener.onImageAboutToBeAvailable(
            timeUs - streamOffsetUs, checkNotNull(outputFormat));
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
        case Renderer.MSG_SET_IMAGE_METADATA_LISTENER:
          imageMetadataListener = (ImageMetadataListener) message;
          break;
        default:
          super.handleMessage(messageType, message);
      }
    }

    private void maybeSetVideoSinkListener() {
      if (!listenerSet) {
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
        listenerSet = true;
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
  }

  /**
   * A {@link MediaCodecVideoRenderer} that outputs decoded frames to a {@link
   * HardwareBufferFrameReader}.
   */
  private static final class HardwareBufferVideoRenderer extends MediaCodecVideoRenderer
      implements RendererWakeupListener {
    private final CompositionRendererListener compositionRendererListener;
    private @MonotonicNonNull HardwareBufferFrameReader hardwareBufferFrameReader;
    private final long lateThresholdToDropInputUs;
    private final TargetFrameRateHelper targetFrameRateHelper;

    private final Supplier<@NullableType HardwareBufferFrameReader>
        hardwareBufferFrameReaderSupplier;

    private MediaSource.@MonotonicNonNull MediaPeriodId mediaPeriodId;
    private @MonotonicNonNull Format nextFormat;
    @Nullable private VideoFrameMetadataListener frameMetadataListener;
    private long streamStartPositionUs;
    private long offsetToCompositionTimeUs;
    private boolean hasOutputSurface = false;

    private HardwareBufferVideoRenderer(
        Context context,
        Handler eventHandler,
        VideoRendererEventListener videoRendererEventListener,
        CompositionRendererListener compositionRendererListener,
        Supplier<@NullableType HardwareBufferFrameReader> hardwareBufferFrameReaderSupplier,
        long lateThresholdToDropInputUs) {
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
              .experimentalSetLateThresholdToDropDecoderInputUs(lateThresholdToDropInputUs)
              .setSkipBuffersWithIdenticalReleaseTime(false));
      this.compositionRendererListener = compositionRendererListener;
      this.hardwareBufferFrameReaderSupplier = hardwareBufferFrameReaderSupplier;
      this.lateThresholdToDropInputUs = lateThresholdToDropInputUs;
      this.targetFrameRateHelper = new TargetFrameRateHelper();
    }

    @Override
    protected void onEnabled(boolean joining, boolean mayRenderStartOfStream)
        throws ExoPlaybackException {
      if (hardwareBufferFrameReader == null) {
        // Initialize hardwareBufferFrameReader on the first onEnabled() call.
        hardwareBufferFrameReader = checkNotNull(hardwareBufferFrameReaderSupplier.get());
      }
      hardwareBufferFrameReader.addRendererWakeupListener(/* rendererWakeupListener= */ this);
      super.onEnabled(joining, mayRenderStartOfStream);
    }

    @Override
    protected void onDisabled() {
      super.onDisabled();
      checkNotNull(hardwareBufferFrameReader)
          .removeRendererWakeupListener(/* rendererWakeupListener= */ this);
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
      super.render(positionUs, elapsedRealtimeUs);
      compositionRendererListener.onRender(
          /* compositionTimePositionUs= */ positionUs + offsetToCompositionTimeUs,
          elapsedRealtimeUs,
          /* compositionTimeOutputStreamStartPositionUs= */ streamStartPositionUs
              + offsetToCompositionTimeUs);
      checkNotNull(hardwareBufferFrameReader).pollImage();
    }

    @Override
    protected void onPositionReset(
        long positionUs, boolean joining, boolean sampleStreamIsResetToKeyFrame)
        throws ExoPlaybackException {
      super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame);
      targetFrameRateHelper.onPositionReset();
    }

    @Override
    protected void onStreamChanged(
        Format[] formats,
        long startPositionUs,
        long offsetUs,
        MediaSource.MediaPeriodId mediaPeriodId)
        throws ExoPlaybackException {
      checkState(getTimeline().getWindowCount() == 1);
      this.mediaPeriodId = mediaPeriodId;
      // The media item might have been repeated in the sequence.
      EditedMediaItem editedMediaItem = getEditedMediaItem(getTimeline(), mediaPeriodId);
      // The renderer has started processing this item, VideoGraph might still be processing the
      // previous one.
      streamStartPositionUs = startPositionUs;
      offsetToCompositionTimeUs =
          getOffsetToCompositionTimeUs(getTimeline(), mediaPeriodId, offsetUs);
      targetFrameRateHelper.onStreamChanged(editedMediaItem);
      super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId);
    }

    @Override
    protected void onQueueInputBuffer(DecoderInputBuffer buffer) throws ExoPlaybackException {
      targetFrameRateHelper.onQueueInputBuffer(buffer, getCodecInputFormat());
      super.onQueueInputBuffer(buffer);
    }

    @Override
    protected int getCodecBufferFlags(DecoderInputBuffer buffer) {
      return super.getCodecBufferFlags(buffer) | targetFrameRateHelper.getCodecBufferFlags(buffer);
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
      checkNotNull(codec);
      // Allow decode only buffers to be dropped before this renderer is ready to output frames,
      // so the first frame that will be processed is guaranteed to be output.
      if (isDecodeOnlyBuffer && !isLastBuffer) {
        skipOutputBuffer(codec, bufferIndex, bufferPresentationTimeUs);
        return true;
      }
      // When prewarming is enabled this method will be called when the renderer is enabled, which
      // is well before item should be displayed. Frames should not be rendered until a Surface is
      // set on this renderer.
      if (!hasOutputSurface) {
        return false;
      }
      nextFormat = format;
      long outputStreamOffsetUs = getOutputStreamOffsetUs();
      long presentationTimeUs = bufferPresentationTimeUs - outputStreamOffsetUs;
      if (targetFrameRateHelper.shouldDropOutputFrame(presentationTimeUs) && !isLastBuffer) {
        skipOutputBuffer(codec, bufferIndex, presentationTimeUs);
        return true;
      }
      if (!checkNotNull(hardwareBufferFrameReader).canAcceptFrameViaSurface()) {
        return false;
      }
      long releaseTimeNs = getClock().nanoTime();
      if (frameMetadataListener != null) {
        frameMetadataListener.onVideoFrameAboutToBeRendered(
            presentationTimeUs, releaseTimeNs, format, getCodecOutputMediaFormat());
      }
      // Force frames to be released to the FrameAggregator as soon as they are decoded, regardless
      // of the wall-clock time. This ensures that the aggregator can immediately find matching
      // frames for all sequences and avoids stalling the pipeline while waiting for frames that
      // have already been decoded.
      renderOutputBufferV21(codec, bufferIndex, presentationTimeUs, releaseTimeNs);
      targetFrameRateHelper.onOutputFrameRendered(presentationTimeUs);
      return true;
    }

    @Override
    protected void renderOutputBufferV21(
        MediaCodecAdapter codec, int index, long presentationTimeUs, long releaseTimeNs) {
      long sequenceOffsetUs = getOutputStreamOffsetUs() + offsetToCompositionTimeUs;
      // TODO: b/449956936 - This can probably be replaced by VideoFrameMetadataListener, but the
      // backpressure in processOutputBuffer can't. See what parts of this logic can be moved to
      // vanilla ExoPlayer.
      checkNotNull(hardwareBufferFrameReader)
          .queueFrameViaSurface(
              /* presentationTimeUs= */ presentationTimeUs,
              sequenceOffsetUs,
              indexOfCurrentItem(),
              checkNotNull(nextFormat));
      super.renderOutputBufferV21(
          codec, index, presentationTimeUs, /* releaseTimeNs= */ presentationTimeUs * 1000);
    }

    @Override
    protected boolean shouldDropOutputBuffer(
        long earlyUs, long elapsedRealtimeUs, boolean isLastBuffer) {
      if (lateThresholdToDropInputUs == C.TIME_UNSET) {
        // Disable frame dropping if requested.
        return false;
      }
      return super.shouldDropOutputBuffer(earlyUs, elapsedRealtimeUs, isLastBuffer);
    }

    @Override
    protected boolean shouldDropBuffersToKeyframe(
        long earlyUs, long elapsedRealtimeUs, boolean isLastBuffer) {
      if (lateThresholdToDropInputUs == C.TIME_UNSET) {
        // Disable frame dropping if requested.
        return false;
      }
      return super.shouldDropBuffersToKeyframe(earlyUs, elapsedRealtimeUs, isLastBuffer);
    }

    @Override
    protected void renderToEndOfStream() {
      super.renderToEndOfStream();
      if (isLastInSequence(getTimeline(), checkNotNull(getMediaPeriodId()))) {
        checkNotNull(hardwareBufferFrameReader).queueEndOfStream();
      }
    }

    @Override
    public boolean isEnded() {
      // Wait until the listener has also ended before ending the renderer, to avoid frames being
      // stuck between the renderer and listener if this renderer ends too early.
      return super.isEnded()
          && (!isLastInSequence(getTimeline(), checkNotNull(mediaPeriodId))
              || compositionRendererListener.isEnded());
    }

    @Override
    public void handleMessage(@MessageType int messageType, @Nullable Object message)
        throws ExoPlaybackException {
      switch (messageType) {
        case MSG_SET_VIDEO_OUTPUT:
          hasOutputSurface = (message != null);
          break;
        case MSG_TRANSFER_RESOURCES:
          hasOutputSurface = false;
          break;
        case MSG_SET_VIDEO_FRAME_METADATA_LISTENER:
          frameMetadataListener = (VideoFrameMetadataListener) checkNotNull(message);
          break;
        default:
          break;
      }
      super.handleMessage(messageType, message);
    }

    // RendererWakeupListener methods

    @Override
    public void onWakeup() {
      WakeupListener wakeupListener = getWakeupListener();
      if (wakeupListener != null) {
        wakeupListener.onWakeup();
      }
    }

    private int indexOfCurrentItem() {
      return getTimeline().getIndexOfPeriod(checkNotNull(getMediaPeriodId()).periodUid);
    }
  }

  /**
   * An {@link ImageRenderer} that outputs decoded images to a {@link HardwareBufferFrameReader}.
   */
  private static final class HardwareBufferImageRenderer extends ImageRenderer {

    private final CompositionRendererListener compositionRendererListener;
    private final Supplier<@NullableType HardwareBufferFrameReader>
        hardwareBufferFrameReaderSupplier;
    private @MonotonicNonNull HardwareBufferFrameReader hardwareBufferFrameReader;
    private @MonotonicNonNull ConstantRateTimestampIterator timestampIterator;
    private MediaSource.@MonotonicNonNull MediaPeriodId mediaPeriodId;
    private long streamStartPositionUs;
    private long offsetToCompositionTimeUs;
    @Nullable private ImageMetadataListener imageMetadataListener;
    private @MonotonicNonNull Format outputFormat;
    private long streamOffsetUs;

    HardwareBufferImageRenderer(
        ImageDecoder.Factory imageDecoderFactory,
        CompositionRendererListener compositionRendererListener,
        Supplier<@NullableType HardwareBufferFrameReader> hardwareBufferFrameReaderSupplier) {
      super(imageDecoderFactory, ImageOutput.NO_OP);
      this.compositionRendererListener = compositionRendererListener;
      this.hardwareBufferFrameReaderSupplier = hardwareBufferFrameReaderSupplier;
      streamStartPositionUs = C.TIME_UNSET;
    }

    @Override
    public void handleMessage(@Renderer.MessageType int messageType, @Nullable Object message)
        throws ExoPlaybackException {
      if (messageType == Renderer.MSG_SET_IMAGE_METADATA_LISTENER) {
        imageMetadataListener = (ImageMetadataListener) message;
        return;
      }
      super.handleMessage(messageType, message);
    }

    // ImageRenderer methods
    @Override
    protected void onEnabled(boolean joining, boolean mayRenderStartOfStream)
        throws ExoPlaybackException {
      super.onEnabled(joining, mayRenderStartOfStream);
      if (hardwareBufferFrameReader == null) {
        // Initialize hardwareBufferFrameReader on the first onEnabled() call.
        this.hardwareBufferFrameReader = checkNotNull(hardwareBufferFrameReaderSupplier.get());
      }
    }

    @Override
    protected void onPositionReset(
        long positionUs, boolean joining, boolean sampleStreamIsResetToKeyFrame)
        throws ExoPlaybackException {
      if (!joining) {
        timestampIterator = createTimestampIterator(positionUs);
      }
      super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame);
    }

    @Override
    protected void onStreamChanged(
        Format[] formats,
        long startPositionUs,
        long offsetUs,
        MediaSource.MediaPeriodId mediaPeriodId)
        throws ExoPlaybackException {
      // CompositionPlayer doesn't support timelines with multiple playlist items (aka windows).
      // While this is not a strict requirement, multiple playlist items are not tested or
      // deliberately supported by this renderer.
      checkState(getTimeline().getWindowCount() == 1);
      this.mediaPeriodId = mediaPeriodId;
      streamStartPositionUs = startPositionUs;
      // The media item might have been repeated in the sequence.
      offsetToCompositionTimeUs =
          getOffsetToCompositionTimeUs(getTimeline(), mediaPeriodId, offsetUs);
      timestampIterator = createTimestampIterator(/* positionUs= */ startPositionUs);
      streamOffsetUs = offsetUs;
      super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId);
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
      super.render(positionUs, elapsedRealtimeUs);
      compositionRendererListener.onRender(
          /* compositionTimePositionUs= */ positionUs + offsetToCompositionTimeUs,
          elapsedRealtimeUs,
          /* compositionTimeOutputStreamStartPositionUs= */ streamStartPositionUs
              + offsetToCompositionTimeUs);
    }

    @Override
    protected boolean processOutputBuffer(
        long positionUs, long elapsedRealtimeUs, Bitmap outputImage, long timeUs) {
      checkNotNull(timestampIterator);
      int indexOfItem = getTimeline().getIndexOfPeriod(checkNotNull(getMediaPeriodId()).periodUid);
      long sequenceOffsetUs = getStreamOffsetUs() + offsetToCompositionTimeUs;
      checkNotNull(hardwareBufferFrameReader)
          .outputBitmap(outputImage, timestampIterator, sequenceOffsetUs, indexOfItem);
      if (isLastInSequence(getTimeline(), checkNotNull(mediaPeriodId))) {
        hardwareBufferFrameReader.queueEndOfStream();
      }
      if (imageMetadataListener != null) {
        if (outputFormat == null
            || outputFormat.width != outputImage.getWidth()
            || outputFormat.height != outputImage.getHeight()) {
          outputFormat =
              new Format.Builder()
                  .setSampleMimeType(MimeTypes.IMAGE_RAW)
                  .setWidth(outputImage.getWidth())
                  .setHeight(outputImage.getHeight())
                  .setColorInfo(ColorInfo.SRGB_BT709_FULL)
                  .setFrameRate(DEFAULT_FRAME_RATE)
                  .build();
        }
        checkNotNull(imageMetadataListener)
            .onImageAboutToBeAvailable(timeUs - streamOffsetUs, outputFormat);
      }
      return true;
    }

    @Override
    public boolean isEnded() {
      // Wait until the listener has also ended before ending the renderer, to avoid frames being
      // stuck between the renderer and listener if this renderer ends too early.
      return super.isEnded()
          && (!isLastInSequence(getTimeline(), checkNotNull(mediaPeriodId))
              || compositionRendererListener.isEnded());
    }

    private ConstantRateTimestampIterator createTimestampIterator(long positionUs) {
      EditedMediaItem editedMediaItem =
          getEditedMediaItem(getTimeline(), checkNotNull(getMediaPeriodId()));
      // positionUs is the stream position with all the previous media item durations added.
      long firstBitmapTimeUs = positionUs - getStreamOffsetUs();
      long lastBitmapTimeUs = editedMediaItem.getPresentationDurationUs();
      return new ConstantRateTimestampIterator(
          /* startPositionUs= */ firstBitmapTimeUs,
          /* endPositionUs= */ lastBitmapTimeUs,
          editedMediaItem.frameRate == C.RATE_UNSET_INT
              ? DEFAULT_FRAME_RATE
              : editedMediaItem.frameRate);
    }
  }

  /** Helper class that encapsulates frame dropping logic for video renderers. */
  private static final class TargetFrameRateHelper {

    private long nextDecoderInputExpectedTimestampUs;
    private long nextDecoderOutputExpectedTimestampUs;
    private long expectedTimestampDeltaUs;
    private long decodeOnlyBufferTimestampUs;

    private TargetFrameRateHelper() {
      nextDecoderInputExpectedTimestampUs = C.TIME_UNSET;
      nextDecoderOutputExpectedTimestampUs = C.TIME_UNSET;
      expectedTimestampDeltaUs = C.TIME_UNSET;
      decodeOnlyBufferTimestampUs = C.TIME_UNSET;
    }

    private void onPositionReset() {
      nextDecoderInputExpectedTimestampUs = C.TIME_UNSET;
      nextDecoderOutputExpectedTimestampUs = C.TIME_UNSET;
      decodeOnlyBufferTimestampUs = C.TIME_UNSET;
    }

    private void onStreamChanged(EditedMediaItem editedMediaItem) {
      expectedTimestampDeltaUs =
          editedMediaItem.frameRate == C.RATE_UNSET_INT
              ? C.TIME_UNSET
              : C.MICROS_PER_SECOND / editedMediaItem.frameRate;
    }

    private void onQueueInputBuffer(DecoderInputBuffer buffer, @Nullable Format codecInputFormat) {
      if (SDK_INT >= 34 && shouldMaintainTargetFrameRate()) {
        if (shouldDropDecoderInputFrameToMaintainTargetFrameRate(
                buffer.timeUs, nextDecoderInputExpectedTimestampUs, codecInputFormat)
            && !buffer.isEndOfStream()
            && !buffer.isLastSample()) {
          // Mark this buffer as DECODE_ONLY. The frame will be dropped by the renderer. We track
          // the timestamp to later add the DECODE_ONLY flag in getCodecBufferFlags.
          decodeOnlyBufferTimestampUs = buffer.timeUs;
        } else {
          nextDecoderInputExpectedTimestampUs =
              (nextDecoderInputExpectedTimestampUs == C.TIME_UNSET)
                  ? (buffer.timeUs + expectedTimestampDeltaUs)
                  : (nextDecoderInputExpectedTimestampUs + expectedTimestampDeltaUs);
        }
      }
    }

    private int getCodecBufferFlags(DecoderInputBuffer buffer) {
      if (SDK_INT >= 34 && decodeOnlyBufferTimestampUs == buffer.timeUs) {
        return MediaCodec.BUFFER_FLAG_DECODE_ONLY;
      }
      return 0;
    }

    private boolean shouldDropOutputFrame(long presentationTimeUs) {
      return shouldDropFrameToMaintainTargetFrameRate(
          presentationTimeUs, nextDecoderOutputExpectedTimestampUs);
    }

    private void onOutputFrameRendered(long presentationTimeUs) {
      if (shouldMaintainTargetFrameRate()) {
        nextDecoderOutputExpectedTimestampUs =
            (nextDecoderOutputExpectedTimestampUs == C.TIME_UNSET)
                ? (presentationTimeUs + expectedTimestampDeltaUs)
                : (nextDecoderOutputExpectedTimestampUs + expectedTimestampDeltaUs);
      }
    }

    private boolean shouldMaintainTargetFrameRate() {
      return expectedTimestampDeltaUs != C.TIME_UNSET;
    }

    private boolean shouldDropDecoderInputFrameToMaintainTargetFrameRate(
        long presentationTimeUs,
        long nextExpectedPresentationTimeUs,
        @Nullable Format codecInputFormat) {
      checkNotNull(codecInputFormat);
      boolean mediaItemContainsBFrames = codecInputFormat.maxNumReorderSamples > 0;
      return !mediaItemContainsBFrames
          && shouldDropFrameToMaintainTargetFrameRate(
              presentationTimeUs, nextExpectedPresentationTimeUs);
    }

    private boolean shouldDropFrameToMaintainTargetFrameRate(
        long presentationTimeUs, long nextExpectedPresentationTimeUs) {
      // This algorithm will always pick the first sample that is after desired timestamp and then
      // it will start looking for the next desired timestamp.
      // For example, for a 30 fps, the desired timestamps are 0, 33_333, 66_666....
      // When seeking is performed, the desired timestamps are shifted accordingly.
      // For example, when seeking to 1 sec, the desired timestamps are 1_000_000, 1_033_333,
      // 1_066_666....
      // This algorithm has no impact if the target frame rate is greater that input frame rate.
      return shouldMaintainTargetFrameRate()
          && nextExpectedPresentationTimeUs != C.TIME_UNSET
          && presentationTimeUs < nextExpectedPresentationTimeUs;
    }
  }
}
