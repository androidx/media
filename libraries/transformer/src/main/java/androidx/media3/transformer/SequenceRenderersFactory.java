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
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.exoplayer.DefaultRenderersFactory.DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS;
import static androidx.media3.exoplayer.DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY;
import static androidx.media3.exoplayer.video.VideoSink.RELEASE_FIRST_FRAME_IMMEDIATELY;
import static androidx.media3.exoplayer.video.VideoSink.RELEASE_FIRST_FRAME_WHEN_PREVIOUS_STREAM_PROCESSED;
import static androidx.media3.exoplayer.video.VideoSink.RELEASE_FIRST_FRAME_WHEN_STARTED;
import static androidx.media3.transformer.EditedMediaItemSequence.getEditedMediaItem;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaFormat;
import android.os.Handler;
import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.ConstantRateTimestampIterator;
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
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** A {@link RenderersFactory} for an {@link EditedMediaItemSequence}. */
/* package */ final class SequenceRenderersFactory implements RenderersFactory {

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

  /** Sets the {@link EditedMediaItemSequence} to render. */
  public void setSequence(EditedMediaItemSequence sequence) {
    if (audioRenderer != null) {
      audioRenderer.setSequence(sequence);
    }
    if (primaryVideoRenderer != null) {
      primaryVideoRenderer.setSequence(sequence);
    }
    if (secondaryVideoRenderer != null) {
      secondaryVideoRenderer.setSequence(sequence);
    }
    if (imageRenderer != null) {
      imageRenderer.setSequence(sequence);
    }
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
        imageRenderer =
            new SequenceImageRenderer(checkStateNotNull(imageDecoderFactory), videoSink);
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
    if (isVideoPrewarmingEnabled() && renderer instanceof SequenceVideoRenderer) {
      if (secondaryVideoRenderer == null) {
        secondaryVideoRenderer =
            new SequenceVideoRenderer(
                context, eventHandler, videoRendererEventListener, new BufferingVideoSink(context));
      }
      return secondaryVideoRenderer;
    }
    return null;
  }

  private static long getOffsetToCompositionTimeUs(
      EditedMediaItemSequence sequence, int mediaItemIndex, long offsetUs) {
    // Reverse engineer how timestamps and offsets are computed with a ConcatenatingMediaSource2
    // to compute an offset converting buffer timestamps to composition timestamps.
    // startPositionUs is not used because it is equal to offsetUs + clipping start time + seek
    // position when seeking from any MediaItem in the playlist to the first MediaItem.
    // The offset to convert the sample timestamps to composition time is negative because we need
    // to remove the large offset added by ExoPlayer to make sure the decoder doesn't received any
    // negative timestamps. We also need to remove the clipping start position.
    long offsetToCompositionTimeUs = -offsetUs;
    if (mediaItemIndex == 0) {
      offsetToCompositionTimeUs -=
          sequence.editedMediaItems.get(0).mediaItem.clippingConfiguration.startPositionUs;
    }
    for (int i = 0; i < mediaItemIndex; i++) {
      offsetToCompositionTimeUs += getEditedMediaItem(sequence, i).getPresentationDurationUs();
    }
    return offsetToCompositionTimeUs;
  }

  private static boolean isLastInSequence(
      Timeline timeline, EditedMediaItemSequence sequence, EditedMediaItem mediaItem) {
    // TODO: b/419479048 - Investigate whether this should always be false for looping sequences.
    int lastEditedMediaItemIndex = timeline.getPeriodCount() - 1;
    return mediaItem == getEditedMediaItem(sequence, lastEditedMediaItemIndex);
  }

  @ChecksSdkIntAtLeast(api = 23)
  private boolean isVideoPrewarmingEnabled() {
    return videoPrewarmingEnabled && SDK_INT >= 23;
  }

  private static final class SequenceAudioRenderer extends MediaCodecAudioRenderer {
    private final AudioGraphInputAudioSink audioSink;
    private final PlaybackAudioGraphWrapper playbackAudioGraphWrapper;

    @Nullable private EditedMediaItem pendingEditedMediaItem;
    private @MonotonicNonNull EditedMediaItemSequence sequence;
    private long pendingOffsetToCompositionTimeUs;

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

    public void setSequence(EditedMediaItemSequence sequence) {
      this.sequence = sequence;
    }

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

    @Override
    protected void onStreamChanged(
        Format[] formats,
        long startPositionUs,
        long offsetUs,
        MediaSource.MediaPeriodId mediaPeriodId)
        throws ExoPlaybackException {
      checkState(getTimeline().getWindowCount() == 1);

      // TODO: b/331392198 - Repeat only looping sequences, after sequences can be of arbitrary
      //  length.
      // The media item might have been repeated in the sequence.
      int periodIndex = getTimeline().getIndexOfPeriod(mediaPeriodId.periodUid);
      // We must first update the pending media item state before calling super.onStreamChanged()
      // because the super method will call onProcessedStreamChange()
      checkStateNotNull(sequence);
      pendingEditedMediaItem = getEditedMediaItem(sequence, periodIndex);
      pendingOffsetToCompositionTimeUs =
          getOffsetToCompositionTimeUs(sequence, periodIndex, offsetUs);
      super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId);
    }

    @Override
    protected void onProcessedStreamChange() {
      super.onProcessedStreamChange();
      onMediaItemChanged();
    }

    @Override
    protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
      super.onPositionReset(positionUs, joining);
      onMediaItemChanged();
    }

    // Other methods

    private void onMediaItemChanged() {
      checkStateNotNull(sequence);
      EditedMediaItem currentEditedMediaItem = checkStateNotNull(pendingEditedMediaItem);
      audioSink.onMediaItemChanged(
          currentEditedMediaItem,
          pendingOffsetToCompositionTimeUs,
          isLastInSequence(getTimeline(), sequence, currentEditedMediaItem));
    }
  }

  private final class SequenceVideoRenderer extends MediaCodecVideoRenderer {

    private final BufferingVideoSink bufferingVideoSink;

    private ImmutableList<Effect> pendingEffects;
    @Nullable private EditedMediaItem currentEditedMediaItem;
    private @MonotonicNonNull EditedMediaItemSequence sequence;
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
      this.pendingEffects = ImmutableList.of();
    }

    public void setSequence(EditedMediaItemSequence sequence) {
      this.sequence = sequence;
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
    protected void onStreamChanged(
        Format[] formats,
        long startPositionUs,
        long offsetUs,
        MediaSource.MediaPeriodId mediaPeriodId)
        throws ExoPlaybackException {
      checkStateNotNull(sequence);
      checkState(getTimeline().getWindowCount() == 1);
      // The media item might have been repeated in the sequence.
      int periodIndex = getTimeline().getIndexOfPeriod(mediaPeriodId.periodUid);
      // The renderer has started processing this item, VideoGraph might still be processing the
      // previous one.
      currentEditedMediaItem = getEditedMediaItem(sequence, periodIndex);
      offsetToCompositionTimeUs = getOffsetToCompositionTimeUs(sequence, periodIndex, offsetUs);
      pendingEffects = checkNotNull(currentEditedMediaItem).effects.videoEffects;
      super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId);
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
      if (isVideoPrewarmingEnabled()
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
      checkStateNotNull(sequence);
      super.renderToEndOfStream();
      if (isLastInSequence(getTimeline(), sequence, checkNotNull(currentEditedMediaItem))) {
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
      if (isVideoPrewarmingEnabled()
          && frameProcessingVideoSink.isInitialized()
          && codec != null
          && !codecNeedsSetOutputSurfaceWorkaround(checkNotNull(getCodecInfo()).name)) {
        setOutputSurfaceV23(codec, frameProcessingVideoSink.getInputSurface());
      }
    }

    private void deactivateBufferingVideoSink() {
      if (!isVideoPrewarmingEnabled()) {
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
    private @MonotonicNonNull EditedMediaItemSequence sequence;
    private @MonotonicNonNull ConstantRateTimestampIterator timestampIterator;
    private @MonotonicNonNull EditedMediaItem currentEditedMediaItem;
    @Nullable private ExoPlaybackException pendingExoPlaybackException;
    private boolean inputStreamPending;
    private long streamStartPositionUs;
    private boolean mayRenderStartOfStream;
    private @VideoSink.FirstFrameReleaseInstruction int nextFirstFrameReleaseInstruction;
    private @MonotonicNonNull WakeupListener wakeupListener;

    public SequenceImageRenderer(ImageDecoder.Factory imageDecoderFactory, VideoSink videoSink) {
      super(imageDecoderFactory, ImageOutput.NO_OP);
      this.videoSink = videoSink;
      videoEffects = ImmutableList.of();
      streamStartPositionUs = C.TIME_UNSET;
    }

    public void setSequence(EditedMediaItemSequence sequence) {
      this.sequence = sequence;
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
    protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
      if (!joining) {
        videoSink.flush(/* resetPosition= */ true);
        timestampIterator = createTimestampIterator(positionUs);
      }
      super.onPositionReset(positionUs, joining);
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
      checkStateNotNull(sequence);
      checkState(getTimeline().getWindowCount() == 1);
      streamStartPositionUs = startPositionUs;
      // The media item might have been repeated in the sequence.
      int periodIndex = getTimeline().getIndexOfPeriod(mediaPeriodId.periodUid);
      currentEditedMediaItem = getEditedMediaItem(sequence, periodIndex);
      long offsetToCompositionTimeUs =
          getOffsetToCompositionTimeUs(sequence, periodIndex, offsetUs);
      videoSink.setBufferTimestampAdjustmentUs(offsetToCompositionTimeUs);
      timestampIterator = createTimestampIterator(/* positionUs= */ startPositionUs);
      videoEffects = checkNotNull(currentEditedMediaItem).effects.videoEffects;
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
      if (!videoSink.handleInputBitmap(outputImage, checkStateNotNull(timestampIterator))) {
        return false;
      }
      videoSink.signalEndOfCurrentInputStream();
      if (isLastInSequence(
          getTimeline(), checkNotNull(sequence), checkNotNull(currentEditedMediaItem))) {
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
      long lastBitmapTimeUs =
          getStreamOffsetUs() + checkNotNull(currentEditedMediaItem).getPresentationDurationUs();
      return new ConstantRateTimestampIterator(
          /* startPositionUs= */ positionUs,
          /* endPositionUs= */ lastBitmapTimeUs,
          DEFAULT_FRAME_RATE);
    }
  }
}
