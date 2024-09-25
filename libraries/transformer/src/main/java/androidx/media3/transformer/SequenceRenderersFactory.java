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
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
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
  private final EditedMediaItemSequence sequence;
  private final PlaybackAudioGraphWrapper playbackAudioGraphWrapper;
  @Nullable private final VideoSink videoSink;
  @Nullable private final ImageDecoder.Factory imageDecoderFactory;
  private final int inputIndex;

  /** Creates a renderers factory for a player that will play video, image and audio. */
  public static SequenceRenderersFactory create(
      Context context,
      EditedMediaItemSequence sequence,
      PlaybackAudioGraphWrapper playbackAudioGraphWrapper,
      VideoSink videoSink,
      ImageDecoder.Factory imageDecoderFactory,
      int inputIndex) {
    return new SequenceRenderersFactory(
        context, sequence, playbackAudioGraphWrapper, videoSink, imageDecoderFactory, inputIndex);
  }

  /** Creates a renderers factory that for a player that will only play audio. */
  public static SequenceRenderersFactory createForAudio(
      Context context,
      EditedMediaItemSequence sequence,
      PlaybackAudioGraphWrapper playbackAudioGraphWrapper,
      int inputIndex) {
    return new SequenceRenderersFactory(
        context,
        sequence,
        playbackAudioGraphWrapper,
        /* videoSink= */ null,
        /* imageDecoderFactory= */ null,
        inputIndex);
  }

  private SequenceRenderersFactory(
      Context context,
      EditedMediaItemSequence sequence,
      PlaybackAudioGraphWrapper playbackAudioGraphWrapper,
      @Nullable VideoSink videoSink,
      @Nullable ImageDecoder.Factory imageDecoderFactory,
      int inputIndex) {
    this.context = context;
    this.sequence = sequence;
    this.playbackAudioGraphWrapper = playbackAudioGraphWrapper;
    this.videoSink = videoSink;
    this.imageDecoderFactory = imageDecoderFactory;
    this.inputIndex = inputIndex;
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
            eventHandler,
            audioRendererEventListener,
            sequence,
            /* audioSink= */ playbackAudioGraphWrapper.createInput(inputIndex),
            playbackAudioGraphWrapper));

    if (videoSink != null) {
      renderers.add(
          new SequenceVideoRenderer(
              checkStateNotNull(context),
              eventHandler,
              videoRendererEventListener,
              sequence,
              videoSink));
      renderers.add(
          new SequenceImageRenderer(sequence, checkStateNotNull(imageDecoderFactory), videoSink));
    }

    return renderers.toArray(new Renderer[0]);
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
      offsetToCompositionTimeUs +=
          getRepeatedEditedMediaItem(sequence, i).getPresentationDurationUs();
    }
    return offsetToCompositionTimeUs;
  }

  /**
   * Gets the {@link EditedMediaItem} of a given {@code index}.
   *
   * <p>The index could be greater than {@link EditedMediaItemSequence#editedMediaItems} because the
   * sequence might be {@linkplain EditedMediaItemSequence#isLooping looping}.
   */
  private static EditedMediaItem getRepeatedEditedMediaItem(
      EditedMediaItemSequence sequence, int index) {
    if (sequence.isLooping) {
      index %= sequence.editedMediaItems.size();
    }
    return sequence.editedMediaItems.get(index);
  }

  private static final class SequenceAudioRenderer extends MediaCodecAudioRenderer {
    private final EditedMediaItemSequence sequence;
    private final AudioGraphInputAudioSink audioSink;
    private final PlaybackAudioGraphWrapper playbackAudioGraphWrapper;

    @Nullable private EditedMediaItem pendingEditedMediaItem;
    private long pendingOffsetToCompositionTimeUs;

    // TODO - b/320007703: Revisit the abstractions needed here (editedMediaItemProvider and
    //  Supplier<EditedMediaItem>) once we finish all the wiring to support multiple sequences.
    public SequenceAudioRenderer(
        Context context,
        @Nullable Handler eventHandler,
        @Nullable AudioRendererEventListener eventListener,
        EditedMediaItemSequence sequence,
        AudioGraphInputAudioSink audioSink,
        PlaybackAudioGraphWrapper playbackAudioGraphWrapper) {
      super(context, MediaCodecSelector.DEFAULT, eventHandler, eventListener, audioSink);
      this.sequence = sequence;
      this.audioSink = audioSink;
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

      // TODO: b/331392198 - Repeat only looping sequences, after sequences can be of arbitrary
      //  length.
      // The media item might have been repeated in the sequence.
      int mediaItemIndex = getTimeline().getIndexOfPeriod(mediaPeriodId.periodUid);
      // We must first update the pending media item state before calling super.onStreamChanged()
      // because the super method will call onProcessedStreamChange()
      pendingEditedMediaItem = getRepeatedEditedMediaItem(sequence, mediaItemIndex);
      pendingOffsetToCompositionTimeUs =
          getOffsetToCompositionTimeUs(sequence, mediaItemIndex, offsetUs);
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
      int lastEditedMediaItemIndex = getTimeline().getPeriodCount() - 1;
      boolean isLastInSequence =
          currentEditedMediaItem == getRepeatedEditedMediaItem(sequence, lastEditedMediaItemIndex);
      audioSink.onMediaItemChanged(
          currentEditedMediaItem, pendingOffsetToCompositionTimeUs, isLastInSequence);
    }
  }

  private static final class SequenceVideoRenderer extends MediaCodecVideoRenderer {
    private final EditedMediaItemSequence sequence;
    private final VideoSink videoSink;

    @Nullable private ImmutableList<Effect> pendingEffect;
    private long offsetToCompositionTimeUs;

    public SequenceVideoRenderer(
        Context context,
        Handler eventHandler,
        VideoRendererEventListener videoRendererEventListener,
        EditedMediaItemSequence sequence,
        VideoSink videoSink) {
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
          videoSink);
      this.sequence = sequence;
      this.videoSink = videoSink;
      experimentalEnableProcessedStreamChangedAtStart();
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
      // The media item might have been repeated in the sequence.
      int mediaItemIndex = getTimeline().getIndexOfPeriod(mediaPeriodId.periodUid);
      offsetToCompositionTimeUs = getOffsetToCompositionTimeUs(sequence, mediaItemIndex, offsetUs);
      pendingEffect = sequence.editedMediaItems.get(mediaItemIndex).effects.videoEffects;
    }

    @Override
    protected long getBufferTimestampAdjustmentUs() {
      return offsetToCompositionTimeUs;
    }

    @Override
    protected void onReadyToChangeVideoSinkInputStream() {
      @Nullable ImmutableList<Effect> pendingEffect = this.pendingEffect;
      if (pendingEffect != null) {
        videoSink.setPendingVideoEffects(pendingEffect);
        this.pendingEffect = null;
      }
    }
  }

  private static final class SequenceImageRenderer extends ImageRenderer {

    private final EditedMediaItemSequence sequence;
    private final VideoSink videoSink;

    private ImmutableList<Effect> videoEffects;
    private @MonotonicNonNull ConstantRateTimestampIterator timestampIterator;
    private @MonotonicNonNull EditedMediaItem editedMediaItem;
    @Nullable private ExoPlaybackException pendingExoPlaybackException;
    private boolean inputStreamPending;
    private long streamStartPositionUs;
    private boolean mayRenderStartOfStream;
    private long offsetToCompositionTimeUs;

    public SequenceImageRenderer(
        EditedMediaItemSequence sequence,
        ImageDecoder.Factory imageDecoderFactory,
        VideoSink videoSink) {
      super(imageDecoderFactory, ImageOutput.NO_OP);
      this.sequence = sequence;
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
      videoSink.onRendererEnabled(mayRenderStartOfStream);
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
      if (mayRenderStartOfStream) {
        // The image renderer is not playing after a video. We must wait until the first frame is
        // rendered.
        return videoSink.isReady(/* rendererOtherwiseReady= */ super.isReady());
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
      videoSink.flush(/* resetPosition= */ true);
      super.onPositionReset(positionUs, joining);
      timestampIterator = createTimestampIterator(positionUs);
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
      streamStartPositionUs = startPositionUs;
      // The media item might have been repeated in the sequence.
      int mediaItemIndex = getTimeline().getIndexOfPeriod(mediaPeriodId.periodUid);
      editedMediaItem = sequence.editedMediaItems.get(mediaItemIndex);
      offsetToCompositionTimeUs = getOffsetToCompositionTimeUs(sequence, mediaItemIndex, offsetUs);
      timestampIterator = createTimestampIterator(/* positionUs= */ startPositionUs);
      videoEffects = editedMediaItem.effects.videoEffects;
      inputStreamPending = true;
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
        throw createRendererException(
            e, e.format, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED);
      }
    }

    @Override
    protected boolean processOutputBuffer(
        long positionUs, long elapsedRealtimeUs, Bitmap outputImage, long timeUs) {
      if (inputStreamPending) {
        checkState(streamStartPositionUs != C.TIME_UNSET);
        videoSink.setPendingVideoEffects(videoEffects);
        videoSink.setStreamTimestampInfo(
            streamStartPositionUs,
            getStreamOffsetUs(),
            /* bufferTimestampAdjustmentUs= */ offsetToCompositionTimeUs,
            getLastResetPositionUs());
        videoSink.onInputStreamChanged(
            VideoSink.INPUT_TYPE_BITMAP,
            new Format.Builder()
                .setSampleMimeType(MimeTypes.IMAGE_RAW)
                .setWidth(outputImage.getWidth())
                .setHeight(outputImage.getHeight())
                .setColorInfo(ColorInfo.SRGB_BT709_FULL)
                .setFrameRate(/* frameRate= */ DEFAULT_FRAME_RATE)
                .build());
        inputStreamPending = false;
      }
      return videoSink.handleInputBitmap(outputImage, checkStateNotNull(timestampIterator));
    }

    private ConstantRateTimestampIterator createTimestampIterator(long positionUs) {
      long streamOffsetUs = getStreamOffsetUs();
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
