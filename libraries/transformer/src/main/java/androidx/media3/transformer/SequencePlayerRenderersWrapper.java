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

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.exoplayer.DefaultRenderersFactory.DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS;
import static androidx.media3.exoplayer.DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
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
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.video.CompositingVideoSinkProvider;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.exoplayer.video.VideoSink;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Wraps {@link EditedMediaItemSequence} specific rendering logic and state. */
/* package */ final class SequencePlayerRenderersWrapper implements RenderersFactory {

  private static final int DEFAULT_FRAME_RATE = 30;

  private final Context context;
  private final EditedMediaItemSequence sequence;
  private final PreviewAudioPipeline previewAudioPipeline;
  @Nullable private final CompositingVideoSinkProvider compositingVideoSinkProvider;
  @Nullable private final ImageDecoder.Factory imageDecoderFactory;

  /** Creates a renderers wrapper for a player that will play video, image and audio. */
  public static SequencePlayerRenderersWrapper create(
      Context context,
      EditedMediaItemSequence sequence,
      PreviewAudioPipeline previewAudioPipeline,
      CompositingVideoSinkProvider compositingVideoSinkProvider,
      ImageDecoder.Factory imageDecoderFactory) {
    return new SequencePlayerRenderersWrapper(
        context, sequence, previewAudioPipeline, compositingVideoSinkProvider, imageDecoderFactory);
  }

  /** Creates a renderers wrapper that for a player that will only play audio. */
  public static SequencePlayerRenderersWrapper createForAudio(
      Context context,
      EditedMediaItemSequence sequence,
      PreviewAudioPipeline previewAudioPipeline) {
    return new SequencePlayerRenderersWrapper(
        context,
        sequence,
        previewAudioPipeline,
        /* compositingVideoSinkProvider= */ null,
        /* imageDecoderFactory= */ null);
  }

  private SequencePlayerRenderersWrapper(
      Context context,
      EditedMediaItemSequence sequence,
      PreviewAudioPipeline previewAudioPipeline,
      @Nullable CompositingVideoSinkProvider compositingVideoSinkProvider,
      @Nullable ImageDecoder.Factory imageDecoderFactory) {
    this.context = context;
    this.sequence = sequence;
    this.previewAudioPipeline = previewAudioPipeline;
    this.compositingVideoSinkProvider = compositingVideoSinkProvider;
    this.imageDecoderFactory = imageDecoderFactory;
  }

  @Override
  public Renderer[] createRenderers(
      Handler eventHandler,
      VideoRendererEventListener videoRendererEventListener,
      AudioRendererEventListener audioRendererEventListener,
      TextOutput textRendererOutput,
      MetadataOutput metadataRendererOutput) {
    List<Renderer> renderers = new ArrayList<>();
    renderers.add(
        new SequenceAudioRenderer(
            context,
            /* sequencePlayerRenderersWrapper= */ this,
            eventHandler,
            audioRendererEventListener,
            previewAudioPipeline.createInput()));

    if (compositingVideoSinkProvider != null) {
      renderers.add(
          new SequenceVideoRenderer(
              checkStateNotNull(context),
              eventHandler,
              videoRendererEventListener,
              /* sequencePlayerRenderersWrapper= */ this));
      renderers.add(new SequenceImageRenderer(/* sequencePlayerRenderersWrapper= */ this));
    }

    return renderers.toArray(new Renderer[0]);
  }

  private long getOffsetToCompositionTimeUs(int mediaItemIndex, long offsetUs) {
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
      offsetToCompositionTimeUs += sequence.editedMediaItems.get(i).getPresentationDurationUs();
    }
    return offsetToCompositionTimeUs;
  }

  private static final class SequenceAudioRenderer extends MediaCodecAudioRenderer {
    private final SequencePlayerRenderersWrapper sequencePlayerRenderersWrapper;
    private final AudioGraphInputAudioSink audioSink;

    @Nullable private EditedMediaItem pendingEditedMediaItem;
    private long pendingOffsetToCompositionTimeUs;

    // TODO - b/320007703: Revisit the abstractions needed here (editedMediaItemProvider and
    //  Supplier<EditedMediaItem>) once we finish all the wiring to support multiple sequences.
    public SequenceAudioRenderer(
        Context context,
        SequencePlayerRenderersWrapper sequencePlayerRenderersWrapper,
        @Nullable Handler eventHandler,
        @Nullable AudioRendererEventListener eventListener,
        AudioGraphInputAudioSink audioSink) {
      super(context, MediaCodecSelector.DEFAULT, eventHandler, eventListener, audioSink);
      this.sequencePlayerRenderersWrapper = sequencePlayerRenderersWrapper;
      this.audioSink = audioSink;
    }

    // MediaCodecAudioRenderer methods

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
      super.render(positionUs, elapsedRealtimeUs);
      try {
        while (sequencePlayerRenderersWrapper.previewAudioPipeline.processData()) {}
      } catch (ExportException
          | AudioSink.WriteException
          | AudioSink.InitializationException
          | AudioSink.ConfigurationException e) {
        throw createRendererException(
            e, /* format= */ null, ExoPlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED);
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
      int mediaItemIndex = getTimeline().getIndexOfPeriod(mediaPeriodId.periodUid);
      // We must first update the pending media item state before calling super.onStreamChanged()
      // because the super method will call onProcessedStreamChange()
      pendingEditedMediaItem =
          sequencePlayerRenderersWrapper.sequence.editedMediaItems.get(mediaItemIndex);
      pendingOffsetToCompositionTimeUs =
          sequencePlayerRenderersWrapper.getOffsetToCompositionTimeUs(mediaItemIndex, offsetUs);
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
      EditedMediaItem currentEditedMediaItem = checkStateNotNull(pendingEditedMediaItem);
      // Use reference equality intentionally.
      boolean isLastInSequence =
          currentEditedMediaItem
              == Iterables.getLast(sequencePlayerRenderersWrapper.sequence.editedMediaItems);
      audioSink.onMediaItemChanged(
          currentEditedMediaItem, pendingOffsetToCompositionTimeUs, isLastInSequence);
    }
  }

  private static final class SequenceVideoRenderer extends MediaCodecVideoRenderer {
    private final SequencePlayerRenderersWrapper sequencePlayerRenderersWrapper;
    private final VideoSink videoSink;
    @Nullable private ImmutableList<Effect> pendingEffect;
    private long offsetToCompositionTimeUs;

    public SequenceVideoRenderer(
        Context context,
        Handler eventHandler,
        VideoRendererEventListener videoRendererEventListener,
        SequencePlayerRenderersWrapper sequencePlayerRenderersWrapper) {
      super(
          context,
          MediaCodecAdapter.Factory.getDefault(context),
          MediaCodecSelector.DEFAULT,
          DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS,
          /* enableDecoderFallback= */ false,
          eventHandler,
          videoRendererEventListener,
          MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
          /* assumedMinimumCodecOperatingRate= */ DEFAULT_FRAME_RATE,
          checkStateNotNull(sequencePlayerRenderersWrapper.compositingVideoSinkProvider));
      this.sequencePlayerRenderersWrapper = sequencePlayerRenderersWrapper;
      videoSink =
          checkStateNotNull(sequencePlayerRenderersWrapper.compositingVideoSinkProvider).getSink();
    }

    @Override
    protected void onStreamChanged(
        Format[] formats,
        long startPositionUs,
        long offsetUs,
        MediaSource.MediaPeriodId mediaPeriodId)
        throws ExoPlaybackException {
      checkState(getTimeline().getWindowCount() == 1);
      super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId);
      int mediaItemIndex = getTimeline().getIndexOfPeriod(mediaPeriodId.periodUid);
      offsetToCompositionTimeUs =
          sequencePlayerRenderersWrapper.getOffsetToCompositionTimeUs(mediaItemIndex, offsetUs);
      pendingEffect =
          sequencePlayerRenderersWrapper.sequence.editedMediaItems.get(mediaItemIndex)
              .effects
              .videoEffects;
    }

    @Override
    protected long getBufferTimestampAdjustmentUs() {
      return offsetToCompositionTimeUs;
    }

    @Override
    protected void onReadyToRegisterVideoSinkInputStream() {
      @Nullable ImmutableList<Effect> pendingEffect = this.pendingEffect;
      if (pendingEffect != null) {
        videoSink.setPendingVideoEffects(pendingEffect);
        this.pendingEffect = null;
      }
    }
  }

  private static final class SequenceImageRenderer extends ImageRenderer {
    private final SequencePlayerRenderersWrapper sequencePlayerRenderersWrapper;
    private final CompositingVideoSinkProvider compositingVideoSinkProvider;
    private final VideoSink videoSink;
    private final VideoFrameReleaseControl videoFrameReleaseControl;

    private ImmutableList<Effect> videoEffects;
    private @MonotonicNonNull ConstantRateTimestampIterator timestampIterator;
    private @MonotonicNonNull EditedMediaItem editedMediaItem;
    @Nullable private ExoPlaybackException pendingExoPlaybackException;
    private boolean inputStreamPendingRegistration;
    private long streamOffsetUs;
    private boolean mayRenderStartOfStream;
    private long offsetToCompositionTimeUs;

    public SequenceImageRenderer(SequencePlayerRenderersWrapper sequencePlayerRenderersWrapper) {
      super(
          checkStateNotNull(sequencePlayerRenderersWrapper.imageDecoderFactory), ImageOutput.NO_OP);
      this.sequencePlayerRenderersWrapper = sequencePlayerRenderersWrapper;
      compositingVideoSinkProvider =
          checkStateNotNull(sequencePlayerRenderersWrapper.compositingVideoSinkProvider);
      videoSink = compositingVideoSinkProvider.getSink();
      videoFrameReleaseControl =
          checkStateNotNull(compositingVideoSinkProvider.getVideoFrameReleaseControl());
      videoEffects = ImmutableList.of();
      streamOffsetUs = C.TIME_UNSET;
    }

    // ImageRenderer methods

    @Override
    protected void onEnabled(boolean joining, boolean mayRenderStartOfStream)
        throws ExoPlaybackException {
      super.onEnabled(joining, mayRenderStartOfStream);
      this.mayRenderStartOfStream = mayRenderStartOfStream;
      videoSink.onRendererEnabled(mayRenderStartOfStream);
      if (joining) {
        videoFrameReleaseControl.join(/* renderNextFrameImmediately= */ false);
      }
      if (!videoSink.isInitialized()) {
        Format format = new Format.Builder().build();
        try {
          videoSink.initialize(format);
        } catch (VideoSink.VideoSinkException e) {
          throw createRendererException(
              e, format, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED);
        }
      }
      // TODO - b/328444280: Do not set a listener on VideoSink, but MediaCodecVideoRenderer must
      //  unregister itself as a listener too.
      videoSink.setListener(VideoSink.Listener.NO_OP, /* executor= */ (runnable) -> {});
    }

    @Override
    protected void onDisabled() {
      super.onDisabled();
      videoSink.onRendererDisabled();
    }

    @Override
    public boolean isEnded() {
      return super.isEnded()
          && videoSink.isEnded()
          && (timestampIterator == null || !timestampIterator.hasNext());
    }

    @Override
    public boolean isReady() {
      // If the renderer was enabled with mayRenderStartOfStream set to false, meaning the image
      // renderer is playing after a video, we don't need to wait until the first frame is rendered.
      // If the renderer was enabled with mayRenderStartOfStream, we must wait until the first frame
      // is rendered, which is checked by VideoSink.isReady().
      return super.isReady() && (!mayRenderStartOfStream || videoSink.isReady());
    }

    @Override
    protected void onReset() {
      super.onReset();
      pendingExoPlaybackException = null;
    }

    @Override
    protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
      videoSink.flush(/* resetPosition= */ true);
      super.onPositionReset(positionUs, joining);
      timestampIterator = createTimestampIterator(positionUs);
      if (joining) {
        videoFrameReleaseControl.join(/* renderNextFrameImmediately= */ false);
      }
    }

    @Override
    protected void onStarted() throws ExoPlaybackException {
      super.onStarted();
      videoSink.onRendererStarted();
    }

    @Override
    protected void onStopped() {
      super.onStopped();
      videoSink.onRendererStopped();
    }

    @Override
    protected void onStreamChanged(
        Format[] formats,
        long startPositionUs,
        long offsetUs,
        MediaSource.MediaPeriodId mediaPeriodId)
        throws ExoPlaybackException {
      checkState(getTimeline().getWindowCount() == 1);
      super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId);
      streamOffsetUs = offsetUs;
      int mediaItemIndex = getTimeline().getIndexOfPeriod(mediaPeriodId.periodUid);
      editedMediaItem =
          sequencePlayerRenderersWrapper.sequence.editedMediaItems.get(mediaItemIndex);
      offsetToCompositionTimeUs =
          sequencePlayerRenderersWrapper.getOffsetToCompositionTimeUs(mediaItemIndex, offsetUs);
      timestampIterator = createTimestampIterator(/* positionUs= */ startPositionUs);
      videoEffects = editedMediaItem.effects.videoEffects;
      inputStreamPendingRegistration = true;
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
      if (pendingExoPlaybackException != null) {
        ExoPlaybackException exoPlaybackException = pendingExoPlaybackException;
        pendingExoPlaybackException = null;
        throw exoPlaybackException;
      }
      super.render(positionUs, elapsedRealtimeUs);
      compositingVideoSinkProvider.render(positionUs, elapsedRealtimeUs);
    }

    @Override
    protected boolean processOutputBuffer(
        long positionUs, long elapsedRealtimeUs, Bitmap outputImage, long timeUs) {
      if (inputStreamPendingRegistration) {
        checkState(streamOffsetUs != C.TIME_UNSET);
        videoSink.setPendingVideoEffects(videoEffects);
        videoSink.setStreamOffsetAndAdjustmentUs(
            streamOffsetUs, /* bufferTimestampAdjustmentUs= */ offsetToCompositionTimeUs);
        videoSink.registerInputStream(
            VideoSink.INPUT_TYPE_BITMAP,
            new Format.Builder()
                .setSampleMimeType(MimeTypes.IMAGE_RAW)
                .setWidth(outputImage.getWidth())
                .setHeight(outputImage.getHeight())
                .setColorInfo(ColorInfo.SRGB_BT709_FULL)
                .setFrameRate(/* frameRate= */ DEFAULT_FRAME_RATE)
                .build());
        inputStreamPendingRegistration = false;
      }
      return videoSink.queueBitmap(outputImage, checkStateNotNull(timestampIterator));
    }

    private ConstantRateTimestampIterator createTimestampIterator(long positionUs) {
      long imageBaseTimestampUs = streamOffsetUs + offsetToCompositionTimeUs;
      long positionWithinImage = positionUs - streamOffsetUs;
      long firstBitmapTimeUs = imageBaseTimestampUs + positionWithinImage;
      long lastBitmapTimeUs =
          imageBaseTimestampUs + checkNotNull(editedMediaItem).getPresentationDurationUs();
      return new ConstantRateTimestampIterator(
          /* startPositionUs= */ firstBitmapTimeUs,
          /* endPositionUs= */ lastBitmapTimeUs,
          DEFAULT_FRAME_RATE);
    }
  }
}
