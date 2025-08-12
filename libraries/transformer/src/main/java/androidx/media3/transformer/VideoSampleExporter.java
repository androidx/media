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
import static androidx.media3.common.ColorInfo.SRGB_BT709_FULL;
import static androidx.media3.common.ColorInfo.isTransferHdr;
import static androidx.media3.common.VideoFrameProcessor.INPUT_TYPE_BITMAP;
import static androidx.media3.common.VideoFrameProcessor.INPUT_TYPE_SURFACE;
import static androidx.media3.common.VideoFrameProcessor.INPUT_TYPE_TEXTURE_ID;
import static androidx.media3.common.VideoFrameProcessor.RENDER_OUTPUT_FRAME_WITH_PRESENTATION_TIME;
import static androidx.media3.transformer.Composition.HDR_MODE_KEEP_HDR;
import static androidx.media3.transformer.Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL;
import static androidx.media3.transformer.TransformerUtil.getOutputMimeTypeAndHdrModeAfterFallback;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.metrics.LogSessionId;
import android.util.Pair;
import android.view.Surface;
import androidx.annotation.IntRange;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.DebugViewProvider;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.OnInputFrameProcessedListener;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.effect.MultipleInputVideoGraph;
import androidx.media3.effect.SingleInputVideoGraph;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.checkerframework.checker.initialization.qual.Initialized;
import org.checkerframework.checker.lock.qual.GuardedBy;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.dataflow.qual.Pure;

/** Processes, encodes and muxes raw video frames. */
/* package */ final class VideoSampleExporter extends SampleExporter {

  private final VideoGraphWrapper videoGraph;
  private final EncoderWrapper encoderWrapper;
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
        new EncoderWrapper(
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

  /**
   * Wraps an {@linkplain Codec encoder} and provides its input {@link Surface}.
   *
   * <p>The encoder is created once the {@link Surface} is {@linkplain #getSurfaceInfo(int, int)
   * requested}. If it is {@linkplain #getSurfaceInfo(int, int) requested} again with different
   * dimensions, the same encoder is used and the provided dimensions stay fixed.
   */
  @VisibleForTesting
  /* package */ static final class EncoderWrapper {
    /** MIME type to use for output video if the input type is not a video. */
    private static final String DEFAULT_OUTPUT_MIME_TYPE = MimeTypes.VIDEO_H265;

    private final Codec.EncoderFactory encoderFactory;
    private final Format inputFormat;
    private final ImmutableList<Integer> allowedEncodingRotationDegrees;
    private final List<String> muxerSupportedMimeTypes;
    private final TransformationRequest transformationRequest;
    private final FallbackListener fallbackListener;
    private final String requestedOutputMimeType;
    private final @Composition.HdrMode int hdrModeAfterFallback;
    @Nullable private final LogSessionId logSessionId;

    private @MonotonicNonNull SurfaceInfo encoderSurfaceInfo;

    private volatile @MonotonicNonNull Codec encoder;
    private volatile int outputRotationDegrees;
    private volatile boolean releaseEncoder;

    public EncoderWrapper(
        Codec.EncoderFactory encoderFactory,
        Format inputFormat,
        ImmutableList<Integer> allowedEncodingRotationDegrees,
        List<String> muxerSupportedMimeTypes,
        TransformationRequest transformationRequest,
        FallbackListener fallbackListener,
        @Nullable LogSessionId logSessionId) {
      checkArgument(inputFormat.colorInfo != null);
      this.encoderFactory = encoderFactory;
      this.inputFormat = inputFormat;
      this.allowedEncodingRotationDegrees = allowedEncodingRotationDegrees;
      this.muxerSupportedMimeTypes = muxerSupportedMimeTypes;
      this.transformationRequest = transformationRequest;
      this.fallbackListener = fallbackListener;
      this.logSessionId = logSessionId;
      Pair<String, Integer> outputMimeTypeAndHdrModeAfterFallback =
          getRequestedOutputMimeTypeAndHdrModeAfterFallback(inputFormat, transformationRequest);
      requestedOutputMimeType = outputMimeTypeAndHdrModeAfterFallback.first;
      hdrModeAfterFallback = outputMimeTypeAndHdrModeAfterFallback.second;
    }

    private static Pair<String, Integer> getRequestedOutputMimeTypeAndHdrModeAfterFallback(
        Format inputFormat, TransformationRequest transformationRequest) {
      String inputSampleMimeType = checkNotNull(inputFormat.sampleMimeType);
      String requestedOutputMimeType;
      if (transformationRequest.videoMimeType != null) {
        requestedOutputMimeType = transformationRequest.videoMimeType;
      } else if (MimeTypes.isImage(inputSampleMimeType)) {
        requestedOutputMimeType = DEFAULT_OUTPUT_MIME_TYPE;
      } else {
        requestedOutputMimeType = inputSampleMimeType;
      }

      return getOutputMimeTypeAndHdrModeAfterFallback(
          transformationRequest.hdrMode, requestedOutputMimeType, inputFormat.colorInfo);
    }

    public @Composition.HdrMode int getHdrModeAfterFallback() {
      return hdrModeAfterFallback;
    }

    @Nullable
    public SurfaceInfo getSurfaceInfo(int requestedWidth, int requestedHeight)
        throws ExportException {
      if (releaseEncoder) {
        return null;
      }
      if (encoderSurfaceInfo != null) {
        return encoderSurfaceInfo;
      }

      // Encoders commonly support higher maximum widths than maximum heights. This may rotate the
      // frame before encoding, so the encoded frame's width >= height. In this case, the VideoGraph
      // rotates the decoded video frames counter-clockwise, and the muxer adds a clockwise rotation
      // to the metadata.
      if (requestedWidth < requestedHeight) {
        int temp = requestedWidth;
        requestedWidth = requestedHeight;
        requestedHeight = temp;
        outputRotationDegrees = 90;
      }

      // Try to match the inputFormat's rotation, but preserve landscape/portrait mode. This is a
      // best-effort attempt to preserve input video properties (helpful for trim optimization), but
      // is not guaranteed to work when effects are applied.
      if (inputFormat.rotationDegrees % 180 == outputRotationDegrees % 180) {
        outputRotationDegrees = inputFormat.rotationDegrees;
      }

      if (!allowedEncodingRotationDegrees.contains(outputRotationDegrees)) {
        int alternativeOutputRotationDegreesWithSameWidthAndHeight =
            (outputRotationDegrees + 180) % 360;
        if (allowedEncodingRotationDegrees.contains(
            alternativeOutputRotationDegreesWithSameWidthAndHeight)) {
          outputRotationDegrees = alternativeOutputRotationDegreesWithSameWidthAndHeight;
        } else {
          // No allowed rotation of the same orientation. Swap width and height, and use any allowed
          // orientation.
          int temp = requestedWidth;
          requestedWidth = requestedHeight;
          requestedHeight = temp;
          outputRotationDegrees = allowedEncodingRotationDegrees.get(0);
        }
      }

      // Rotation is handled by this class. The encoder must see a video with zero degrees rotation.
      Format requestedEncoderFormat =
          new Format.Builder()
              .setWidth(requestedWidth)
              .setHeight(requestedHeight)
              .setRotationDegrees(0)
              .setFrameRate(inputFormat.frameRate)
              .setSampleMimeType(requestedOutputMimeType)
              .setColorInfo(getSupportedInputColor())
              .setCodecs(inputFormat.codecs)
              .build();

      // TODO: b/324426022 - Move logic for supported mime types to DefaultEncoderFactory.
      encoder =
          encoderFactory.createForVideoEncoding(
              requestedEncoderFormat
                  .buildUpon()
                  .setSampleMimeType(
                      findSupportedMimeTypeForEncoderAndMuxer(
                          requestedEncoderFormat, muxerSupportedMimeTypes))
                  .build(),
              logSessionId);

      Format actualEncoderFormat = encoder.getConfigurationFormat();

      fallbackListener.onTransformationRequestFinalized(
          createSupportedTransformationRequest(
              transformationRequest,
              /* hasOutputFormatRotation= */ outputRotationDegrees != 0,
              requestedEncoderFormat,
              actualEncoderFormat,
              hdrModeAfterFallback));

      encoderSurfaceInfo =
          new SurfaceInfo(
              encoder.getInputSurface(),
              actualEncoderFormat.width,
              actualEncoderFormat.height,
              outputRotationDegrees,
              /* isEncoderInputSurface= */ true);

      if (releaseEncoder) {
        encoder.release();
      }
      return encoderSurfaceInfo;
    }

    /** Returns the {@link ColorInfo} expected from the input surface. */
    private ColorInfo getSupportedInputColor() {
      boolean isInputToneMapped =
          isTransferHdr(inputFormat.colorInfo) && hdrModeAfterFallback != HDR_MODE_KEEP_HDR;
      if (isInputToneMapped) {
        // When tone-mapping HDR to SDR is enabled, assume we get BT.709 to avoid having the encoder
        // populate default color info, which depends on the resolution.
        return SDR_BT709_LIMITED;
      }
      if (SRGB_BT709_FULL.equals(inputFormat.colorInfo)) {
        return SDR_BT709_LIMITED;
      }
      return checkNotNull(inputFormat.colorInfo);
    }

    /**
     * Creates a {@link TransformationRequest}, based on an original {@code TransformationRequest}
     * and parameters specifying alterations to it that indicate device support.
     *
     * @param transformationRequest The requested transformation.
     * @param hasOutputFormatRotation Whether the input video will be rotated to landscape during
     *     processing, with {@link Format#rotationDegrees} of 90 added to the output format.
     * @param requestedFormat The requested format.
     * @param supportedFormat A format supported by the device.
     * @param supportedHdrMode A {@link Composition.HdrMode} supported by the device.
     * @return The created instance.
     */
    @Pure
    private static TransformationRequest createSupportedTransformationRequest(
        TransformationRequest transformationRequest,
        boolean hasOutputFormatRotation,
        Format requestedFormat,
        Format supportedFormat,
        @Composition.HdrMode int supportedHdrMode) {
      // TODO: b/255953153 - Consider including bitrate in the revised fallback.

      TransformationRequest.Builder supportedRequestBuilder = transformationRequest.buildUpon();
      if (transformationRequest.hdrMode != supportedHdrMode) {
        supportedRequestBuilder.setHdrMode(supportedHdrMode);
      }

      if (!Objects.equals(requestedFormat.sampleMimeType, supportedFormat.sampleMimeType)) {
        supportedRequestBuilder.setVideoMimeType(supportedFormat.sampleMimeType);
      }

      if (hasOutputFormatRotation) {
        if (requestedFormat.width != supportedFormat.width) {
          supportedRequestBuilder.setResolution(/* outputHeight= */ supportedFormat.width);
        }
      } else if (requestedFormat.height != supportedFormat.height) {
        supportedRequestBuilder.setResolution(supportedFormat.height);
      }

      return supportedRequestBuilder.build();
    }

    public void signalEndOfInputStream() throws ExportException {
      if (encoder != null) {
        encoder.signalEndOfInputStream();
      }
    }

    @Nullable
    public Format getOutputFormat() throws ExportException {
      if (encoder == null) {
        return null;
      }
      @Nullable Format outputFormat = encoder.getOutputFormat();
      if (outputFormat != null && outputRotationDegrees != 0) {
        outputFormat = outputFormat.buildUpon().setRotationDegrees(outputRotationDegrees).build();
      }
      return outputFormat;
    }

    @Nullable
    public ByteBuffer getOutputBuffer() throws ExportException {
      return encoder != null ? encoder.getOutputBuffer() : null;
    }

    @Nullable
    public MediaCodec.BufferInfo getOutputBufferInfo() throws ExportException {
      return encoder != null ? encoder.getOutputBufferInfo() : null;
    }

    public void releaseOutputBuffer(boolean render) throws ExportException {
      if (encoder != null) {
        encoder.releaseOutputBuffer(render);
      }
    }

    public boolean isEnded() {
      return encoder != null && encoder.isEnded();
    }

    public void release() {
      if (encoder != null) {
        encoder.release();
      }
      releaseEncoder = true;
    }
  }

  private final class VideoGraphWrapper implements VideoGraph.Listener {

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
      return new VideoGraphInput(videoGraph, inputIndex, initialTimestampOffsetUs);
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

  /** A wrapper for {@link VideoGraph} input that handles {@link GraphInput} events. */
  private static final class VideoGraphInput implements GraphInput {
    private final VideoGraph videoGraph;
    private final int inputIndex;
    private final long initialTimestampOffsetUs;
    private final AtomicLong mediaItemOffsetUs;

    public VideoGraphInput(VideoGraph videoGraph, int inputIndex, long initialTimestampOffsetUs) {
      this.videoGraph = videoGraph;
      this.inputIndex = inputIndex;
      this.initialTimestampOffsetUs = initialTimestampOffsetUs;
      mediaItemOffsetUs = new AtomicLong();
    }

    @Override
    public void onMediaItemChanged(
        EditedMediaItem editedMediaItem,
        long durationUs,
        @Nullable Format decodedFormat,
        boolean isLast,
        @IntRange(from = 0) long positionOffsetUs) {
      boolean isSurfaceAssetLoaderMediaItem = isMediaItemForSurfaceAssetLoader(editedMediaItem);
      durationUs = editedMediaItem.getDurationAfterEffectsApplied(durationUs);
      if (decodedFormat != null) {
        decodedFormat = applyDecoderRotation(decodedFormat);
        videoGraph.registerInputStream(
            inputIndex,
            isSurfaceAssetLoaderMediaItem
                ? VideoFrameProcessor.INPUT_TYPE_SURFACE_AUTOMATIC_FRAME_REGISTRATION
                : getInputTypeForMimeType(checkNotNull(decodedFormat.sampleMimeType)),
            decodedFormat,
            editedMediaItem.effects.videoEffects,
            /* offsetToAddUs= */ initialTimestampOffsetUs + mediaItemOffsetUs.get());
      }
      mediaItemOffsetUs.addAndGet(durationUs);
    }

    @Override
    public @InputResult int queueInputBitmap(
        Bitmap inputBitmap, TimestampIterator timestampIterator) {
      return videoGraph.queueInputBitmap(inputIndex, inputBitmap, timestampIterator)
          ? INPUT_RESULT_SUCCESS
          : INPUT_RESULT_TRY_AGAIN_LATER;
    }

    @Override
    public void setOnInputFrameProcessedListener(OnInputFrameProcessedListener listener) {
      videoGraph.setOnInputFrameProcessedListener(inputIndex, listener);
    }

    @Override
    public void setOnInputSurfaceReadyListener(Runnable runnable) {
      videoGraph.setOnInputSurfaceReadyListener(inputIndex, runnable);
    }

    @Override
    public @InputResult int queueInputTexture(int texId, long presentationTimeUs) {
      return videoGraph.queueInputTexture(inputIndex, texId, presentationTimeUs)
          ? INPUT_RESULT_SUCCESS
          : INPUT_RESULT_TRY_AGAIN_LATER;
    }

    @Override
    public Surface getInputSurface() {
      return videoGraph.getInputSurface(inputIndex);
    }

    @Override
    public int getPendingVideoFrameCount() {
      return videoGraph.getPendingInputFrameCount(inputIndex);
    }

    @Override
    public boolean registerVideoFrame(long presentationTimeUs) {
      return videoGraph.registerInputFrame(inputIndex);
    }

    @Override
    public void signalEndOfVideoInput() {
      videoGraph.signalEndOfInput(inputIndex);
    }

    private static Format applyDecoderRotation(Format format) {
      // The decoder rotates encoded frames for display by format.rotationDegrees.
      if (format.rotationDegrees % 180 == 0) {
        return format;
      }
      return format
          .buildUpon()
          .setWidth(format.height)
          .setHeight(format.width)
          .setRotationDegrees(0)
          .build();
    }

    private static @VideoFrameProcessor.InputType int getInputTypeForMimeType(
        String sampleMimeType) {
      if (MimeTypes.isImage(sampleMimeType)) {
        return INPUT_TYPE_BITMAP;
      }
      if (sampleMimeType.equals(MimeTypes.VIDEO_RAW)) {
        return INPUT_TYPE_TEXTURE_ID;
      }
      if (MimeTypes.isVideo(sampleMimeType)) {
        return INPUT_TYPE_SURFACE;
      }
      throw new IllegalArgumentException("MIME type not supported " + sampleMimeType);
    }

    private static boolean isMediaItemForSurfaceAssetLoader(EditedMediaItem editedMediaItem) {
      @Nullable
      MediaItem.LocalConfiguration localConfiguration =
          editedMediaItem.mediaItem.localConfiguration;
      if (localConfiguration == null) {
        return false;
      }
      @Nullable String scheme = localConfiguration.uri.getScheme();
      if (scheme == null) {
        return false;
      }
      return scheme.equals(SurfaceAssetLoader.MEDIA_ITEM_URI_SCHEME);
    }
  }
}
