/*
 * Copyright 2021 The Android Open Source Project
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

import static androidx.media3.common.C.COLOR_RANGE_FULL;
import static androidx.media3.common.C.COLOR_SPACE_BT2020;
import static androidx.media3.common.C.COLOR_TRANSFER_HLG;
import static androidx.media3.common.ColorInfo.SDR_BT709_LIMITED;
import static androidx.media3.common.ColorInfo.isTransferHdr;
import static androidx.media3.common.VideoFrameProcessor.RENDER_OUTPUT_FRAME_WITH_PRESENTATION_TIME;
import static androidx.media3.transformer.Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import android.media.MediaCodec;
import android.media.metrics.LogSessionId;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.util.Consumer;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.effect.MultipleInputVideoGraph;
import androidx.media3.effect.SingleInputVideoGraph;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Objects;
import org.checkerframework.checker.initialization.qual.Initialized;

/** Processes, encodes and muxes raw video frames. */
/* package */ final class VideoSampleExporter extends SampleExporter {

  private final VideoGraphWrapper videoGraph;
  private final VideoEncoderWrapper encoderWrapper;
  private final DecoderInputBuffer encoderOutputBuffer;

  /**
   * The timestamp of the last buffer processed before {@linkplain
   * VideoFrameProcessor.Listener#onEnded() frame processing has ended}.
   */
  private volatile long finalFramePresentationTimeUs;

  private long lastMuxerInputBufferTimestampUs;
  private boolean hasMuxedTimestampZero;

  public VideoSampleExporter(
      Context context,
      Format firstInputFormat,
      TransformationRequest transformationRequest,
      VideoCompositorSettings videoCompositorSettings,
      List<Effect> compositionEffects,
      VideoFrameProcessor.Factory videoFrameProcessorFactory,
      Codec.EncoderFactory encoderFactory,
      MuxerWrapper muxerWrapper,
      Consumer<ExportException> errorConsumer,
      FallbackListener fallbackListener,
      DebugViewProvider debugViewProvider,
      long initialTimestampOffsetUs,
      boolean hasMultipleInputs,
      ImmutableList<Integer> allowedEncodingRotationDegrees,
      int maxFramesInEncoder,
      @Nullable LogSessionId logSessionId)
      throws ExportException {
    // TODO: b/278259383 - Consider delaying configuration of VideoSampleExporter to use the decoder
    //  output format instead of the extractor output format, to match AudioSampleExporter behavior.
    super(firstInputFormat, muxerWrapper);
    // Automatically render frames if the sample exporter does not limit the number of frames in
    // the encoder.
    boolean renderFramesAutomatically = maxFramesInEncoder < 1;
    finalFramePresentationTimeUs = C.TIME_UNSET;
    lastMuxerInputBufferTimestampUs = C.TIME_UNSET;

    ColorInfo videoGraphInputColor = checkNotNull(firstInputFormat.colorInfo);
    ColorInfo videoGraphOutputColor;
    if (Objects.equals(firstInputFormat.sampleMimeType, MimeTypes.IMAGE_JPEG_R)
        && videoGraphInputColor.colorTransfer == C.COLOR_TRANSFER_SRGB) {
      // We only support the sRGB color transfer for Ultra HDR images.
      // When an Ultra HDR image transcoded into a video, we use BT2020 HLG full range colors in the
      // resulting HDR video.
      videoGraphOutputColor =
          new ColorInfo.Builder()
              .setColorSpace(COLOR_SPACE_BT2020)
              .setColorTransfer(COLOR_TRANSFER_HLG)
              .setColorRange(COLOR_RANGE_FULL)
              .build();
    } else if (videoGraphInputColor.colorTransfer == C.COLOR_TRANSFER_SRGB
        || videoGraphInputColor.colorTransfer == C.COLOR_TRANSFER_GAMMA_2_2) {
      // Convert to BT.709 which is a more commonly used color space.
      // COLOR_TRANSFER_SDR (BT.709), COLOR_TRANSFER_SRGB and COLOR_TRANSFER_GAMMA_2_2 are similar,
      // so the conversion should not bring a large quality degradation.
      videoGraphOutputColor = SDR_BT709_LIMITED;
    } else {
      videoGraphOutputColor = videoGraphInputColor;
    }

    encoderWrapper =
        new VideoEncoderWrapper(
            encoderFactory,
            firstInputFormat.buildUpon().setColorInfo(videoGraphOutputColor).build(),
            allowedEncodingRotationDegrees,
            muxerWrapper.getSupportedSampleMimeTypes(C.TRACK_TYPE_VIDEO),
            transformationRequest,
            fallbackListener,
            logSessionId);
    encoderOutputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED);

    boolean isGlToneMapping =
        encoderWrapper.getHdrModeAfterFallback() == HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
            && isTransferHdr(videoGraphInputColor);
    if (isGlToneMapping) {
      videoGraphOutputColor = SDR_BT709_LIMITED;
    }

    try {
      videoGraph =
          new VideoGraphWrapper(
              context,
              hasMultipleInputs
                  ? new MultipleInputVideoGraph.Factory(videoFrameProcessorFactory)
                  : new SingleInputVideoGraph.Factory(videoFrameProcessorFactory),
              videoGraphOutputColor,
              debugViewProvider,
              videoCompositorSettings,
              compositionEffects,
              errorConsumer,
              initialTimestampOffsetUs,
              maxFramesInEncoder,
              renderFramesAutomatically);
      videoGraph.initialize();
    } catch (VideoFrameProcessingException e) {
      throw ExportException.createForVideoFrameProcessingException(e);
    }
  }

  @Override
  public GraphInput getInput(EditedMediaItem editedMediaItem, Format format, int inputIndex)
      throws ExportException {
    try {
      return videoGraph.createInput(inputIndex);
    } catch (VideoFrameProcessingException e) {
      throw ExportException.createForVideoFrameProcessingException(e);
    }
  }

  @Override
  public void release() {
    videoGraph.release();
    encoderWrapper.release();
  }

  @Override
  @Nullable
  protected Format getMuxerInputFormat() throws ExportException {
    return encoderWrapper.getOutputFormat();
  }

  @Override
  @Nullable
  protected DecoderInputBuffer getMuxerInputBuffer() throws ExportException {
    encoderOutputBuffer.data = encoderWrapper.getOutputBuffer();
    if (encoderOutputBuffer.data == null) {
      return null;
    }
    MediaCodec.BufferInfo bufferInfo = checkNotNull(encoderWrapper.getOutputBufferInfo());
    if (bufferInfo.presentationTimeUs == 0) {
      // Internal ref b/235045165: Some encoder incorrectly set a zero presentation time on the
      // penultimate buffer (before EOS), and sets the actual timestamp on the EOS buffer. Use the
      // last processed frame presentation time instead.
      if (videoGraph.hasProducedFrameWithTimestampZero() == hasMuxedTimestampZero
          && finalFramePresentationTimeUs != C.TIME_UNSET
          && bufferInfo.size > 0) {
        bufferInfo.presentationTimeUs = finalFramePresentationTimeUs;
      }
    }
    encoderOutputBuffer.timeUs = bufferInfo.presentationTimeUs;
    encoderOutputBuffer.setFlags(bufferInfo.flags);
    lastMuxerInputBufferTimestampUs = bufferInfo.presentationTimeUs;
    return encoderOutputBuffer;
  }

  @Override
  protected void releaseMuxerInputBuffer() throws ExportException {
    if (lastMuxerInputBufferTimestampUs == 0) {
      hasMuxedTimestampZero = true;
    }
    encoderWrapper.releaseOutputBuffer(/* render= */ false);
    videoGraph.onEncoderBufferReleased();
  }

  @Override
  protected boolean isMuxerInputEnded() {
    // Sometimes the encoder fails to produce an output buffer with end of stream flag after
    // end of stream is signalled. See b/365484741.
    // Treat empty encoder (no frames in progress) as if it has ended.
    return encoderWrapper.isEnded() || videoGraph.hasEncoderReleasedAllBuffersAfterEndOfStream();
  }

  public final class VideoGraphWrapper implements VideoGraph.Listener {

    private final VideoGraph videoGraph;
    private final Object lock;
    private final Consumer<ExportException> errorConsumer;
    private final boolean renderFramesAutomatically;
    private final long initialTimestampOffsetUs;
    private final int maxFramesInEncoder;
    private @GuardedBy("lock") int framesInEncoder;
    private @GuardedBy("lock") int framesAvailableToRender;

    public VideoGraphWrapper(
        Context context,
        VideoGraph.Factory videoGraphFactory,
        ColorInfo videoFrameProcessorOutputColor,
        DebugViewProvider debugViewProvider,
        VideoCompositorSettings videoCompositorSettings,
        List<Effect> compositionEffects,
        Consumer<ExportException> errorConsumer,
        long initialTimestampOffsetUs,
        int maxFramesInEncoder,
        boolean renderFramesAutomatically)
        throws VideoFrameProcessingException {
      this.errorConsumer = errorConsumer;
      this.lock = new Object();
      this.renderFramesAutomatically = renderFramesAutomatically;
      this.initialTimestampOffsetUs = initialTimestampOffsetUs;
      this.maxFramesInEncoder = maxFramesInEncoder;

      @SuppressWarnings("nullness:assignment")
      @Initialized
      VideoGraphWrapper thisRef = this;
      videoGraph =
          videoGraphFactory.create(
              context,
              videoFrameProcessorOutputColor,
              debugViewProvider,
              /* listener= */ thisRef,
              /* listenerExecutor= */ directExecutor(),
              initialTimestampOffsetUs,
              renderFramesAutomatically);
      videoGraph.setCompositionEffects(compositionEffects);
      videoGraph.setCompositorSettings(videoCompositorSettings);
    }

    public void initialize() throws VideoFrameProcessingException {
      videoGraph.initialize();
    }

    public boolean hasProducedFrameWithTimestampZero() {
      return videoGraph.hasProducedFrameWithTimestampZero();
    }

    /**
     * Returns a {@link GraphInput} object to which the {@code VideoGraph} inputs are queued.
     *
     * <p>This method must be called after successfully {@linkplain #initialize() initializing} the
     * {@code VideoGraph}.
     *
     * <p>This method must called exactly once for every input stream.
     *
     * <p>If the method throws any {@link Exception}, the caller must call {@link #release}.
     *
     * @param inputIndex The index of the input, which could be used to order the inputs.
     */
    public GraphInput createInput(int inputIndex) throws VideoFrameProcessingException {
      videoGraph.registerInput(inputIndex);
      // Applies the composition effects here if there's only one input. In multiple-input case, the
      // effects are applied as a part of the video graph.
      return new VideoEncoderGraphInput(videoGraph, inputIndex, initialTimestampOffsetUs);
    }

    public void release() {
      videoGraph.release();
    }

    @Override
    public void onOutputSizeChanged(int width, int height) {
      @Nullable SurfaceInfo surfaceInfo = null;
      try {
        surfaceInfo = encoderWrapper.getSurfaceInfo(width, height);
      } catch (ExportException e) {
        errorConsumer.accept(e);
      }
      videoGraph.setOutputSurfaceInfo(surfaceInfo);
    }

    @Override
    public void onOutputFrameAvailableForRendering(
        long framePresentationTimeUs, boolean isRedrawnFrame) {
      if (!renderFramesAutomatically) {
        synchronized (lock) {
          framesAvailableToRender += 1;
        }
        maybeRenderEarliestOutputFrame();
      }
    }

    @Override
    public void onEnded(long finalFramePresentationTimeUs) {
      VideoSampleExporter.this.finalFramePresentationTimeUs = finalFramePresentationTimeUs;
      try {
        encoderWrapper.signalEndOfInputStream();
      } catch (ExportException e) {
        errorConsumer.accept(e);
      }
    }

    @Override
    public void onError(VideoFrameProcessingException e) {
      errorConsumer.accept(ExportException.createForVideoFrameProcessingException(e));
    }

    public boolean hasEncoderReleasedAllBuffersAfterEndOfStream() {
      if (renderFramesAutomatically) {
        // Video graph wrapper does not track encoder buffers.
        return false;
      }
      boolean isEndOfStreamSeen =
          (VideoSampleExporter.this.finalFramePresentationTimeUs != C.TIME_UNSET);
      synchronized (lock) {
        return framesInEncoder == 0 && isEndOfStreamSeen;
      }
    }

    public void onEncoderBufferReleased() {
      if (!renderFramesAutomatically) {
        synchronized (lock) {
          checkState(framesInEncoder > 0);
          framesInEncoder -= 1;
        }
        maybeRenderEarliestOutputFrame();
      }
    }

    private void maybeRenderEarliestOutputFrame() {
      boolean shouldRender = false;
      synchronized (lock) {
        if (framesAvailableToRender > 0 && framesInEncoder < maxFramesInEncoder) {
          framesInEncoder += 1;
          framesAvailableToRender -= 1;
          shouldRender = true;
        }
      }
      if (shouldRender) {
        videoGraph.renderOutputFrame(RENDER_OUTPUT_FRAME_WITH_PRESENTATION_TIME);
      }
    }
  }
}
