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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.common.C.TRACK_TYPE_VIDEO;
import static androidx.media3.transformer.FrameAggregator.STRATEGY_EXPECT_NO_FRAMES;
import static androidx.media3.transformer.FrameAggregator.STRATEGY_MATCH_FRAME_AT_OR_AFTER_TARGET;
import static androidx.media3.transformer.FrameAggregator.STRATEGY_MATCH_FRAME_CLOSEST_TO_TARGET;
import static androidx.media3.transformer.TransformerUtil.END_OF_STREAM_ASYNC_FRAME;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;

import android.content.Context;
import android.media.MediaCodec.BufferInfo;
import android.media.metrics.LogSessionId;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.HandlerExecutor;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.Util;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.effect.DefaultGlObjectsProvider;
import androidx.media3.effect.HardwareBufferJniWrapper;
import androidx.media3.transformer.Codec.EncoderFactory;
import androidx.media3.transformer.FrameAggregator.AggregationStrategy;
import com.google.common.collect.ImmutableList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import org.checkerframework.checker.initialization.qual.Initialized;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Processes, encodes and muxes raw video frames. */
@RequiresApi(26)
/* package */ final class PacketConsumerVideoSampleExporter extends SampleExporter {

  private static final String DEFAULT_OUTPUT_MIME_TYPE = MimeTypes.VIDEO_H265;

  private final DecoderInputBuffer encoderOutputBuffer;
  private final Consumer<ExportException> errorConsumer;
  private final FrameProcessor frameProcessor;
  private final FrameAggregator frameAggregator;
  private final FrameWriter frameWriter;
  private final ImmutableList<HardwareBufferSampleConsumer> sampleConsumers;

  private final Queue<ImmutableList<AsyncFrame>> pendingPackets;
  private final InFlightFrameManager inFlightFrameManager;
  private boolean hasPendingEos;
  private volatile boolean released;

  /**
   * The timestamp of the last buffer processed before {@linkplain
   * VideoFrameProcessor.Listener#onEnded() frame processing has ended}.
   */
  private volatile long finalFramePresentationTimeUs;

  private long lastMuxerInputBufferTimestampUs;
  private boolean hasMuxedTimestampZero;
  private volatile boolean hasProducedFrameWithTimestampZero;
  private boolean hasSignaledEndOfStream;

  // Written on the frame processor thread (in FrameWriterEncoderFactory.createForVideoEncoding)
  // and read on the playback thread.
  private volatile int outputRotationDegrees;
  private volatile @MonotonicNonNull Codec encoder;

  public PacketConsumerVideoSampleExporter(
      Context context,
      Composition composition,
      Format firstInputFormat,
      TransformationRequest transformationRequest,
      FrameProcessor.Factory frameProcessorFactory,
      HardwareBufferJniWrapper hardwareBufferJniWrapper,
      EncoderFactory encoderFactory,
      MuxerWrapper muxerWrapper,
      Consumer<ExportException> errorConsumer,
      FallbackListener fallbackListener,
      ImmutableList<Integer> allowedEncodingRotationDegrees,
      @Nullable LogSessionId logSessionId,
      Looper playbackLooper,
      HandlerWrapper handlerWrapper) {
    // TODO: b/278259383 - Consider delaying configuration of VideoSampleExporter to use the decoder
    //  output format instead of the extractor output format, to match AudioSampleExporter behavior.
    super(firstInputFormat, muxerWrapper);
    this.errorConsumer = errorConsumer;
    this.pendingPackets = new ArrayDeque<>();
    this.inFlightFrameManager = new InFlightFrameManager();
    finalFramePresentationTimeUs = C.TIME_UNSET;
    lastMuxerInputBufferTimestampUs = C.TIME_UNSET;
    encoderOutputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED);

    @SuppressWarnings("nullness:assignment")
    @Initialized
    PacketConsumerVideoSampleExporter thisRef = this;

    ComponentListener componentListener = new ComponentListener();
    Executor listenerExecutor = new HandlerExecutor(handlerWrapper, componentListener);

    // TODO: b/484926720 - add executor to the Listener callbacks.
    Handler playbackHandler = new Handler(playbackLooper);
    Codec.EncoderFactory strictEncoderFactory = encoderFactory;
    if (encoderFactory instanceof DefaultEncoderFactory) {
      strictEncoderFactory =
          ((DefaultEncoderFactory) encoderFactory)
              .buildUpon()
              .setEnableFormatFallback(false)
              .build();
    }

    Codec.EncoderFactory frameWriterEncoderFactory =
        thisRef
        .new FrameWriterEncoderFactory(
            strictEncoderFactory,
            getRequestedOutputMimeType(firstInputFormat, transformationRequest),
            muxerWrapper.getSupportedSampleMimeTypes(TRACK_TYPE_VIDEO));

    Executor playbackExecutor = new HandlerExecutor(playbackHandler, componentListener);
    if (SDK_INT >= 33) {
      frameWriter =
          new EncoderFrameWriter(
              frameWriterEncoderFactory,
              componentListener,
              playbackExecutor,
              playbackHandler,
              logSessionId);
    } else {
      frameWriter =
          new GlEncoderFrameWriter(
              context,
              frameWriterEncoderFactory,
              componentListener,
              playbackExecutor,
              new DefaultGlObjectsProvider(),
              listeningDecorator(Util.newSingleThreadExecutor("GlEncoderFrameWriter::Thread")),
              hardwareBufferJniWrapper,
              logSessionId);
    }
    frameProcessor =
        frameProcessorFactory.create(
            frameWriter, listenerExecutor, /* listener= */ componentListener);

    frameAggregator =
        new FrameAggregator(
            composition.sequences.size(),
            composition.videoFrameAggregationParameters.frameRate,
            thisRef::queueAggregatedFrames,
            /* onFlush= */ (unused) -> {});
    // Create the per sequence consumers that feed buffers from the decoders into the
    // FrameAggregator.
    ImmutableList.Builder<HardwareBufferSampleConsumer> sampleConsumerBuilder =
        new ImmutableList.Builder<>();
    for (int i = 0; i < composition.sequences.size(); i++) {
      int sequenceIndex = i;
      Consumer<AsyncFrame> frameConsumer =
          // TODO: b/478781219 - Remove the handlerWrapper.post once HardwareBufferSampleConsumer is
          // only accessed from a single thread.
          (asyncFrame) ->
              handlerWrapper.post(
                  () -> {
                    // Frames may be produced by the underlying players after Transformer has been
                    // canceled. Immediately release these frames.
                    if (released) {
                      TransformerUtil.releaseIfNeeded(asyncFrame.frame, /* releaseFence= */ null);
                      return;
                    }
                    if (asyncFrame == END_OF_STREAM_ASYNC_FRAME) {
                      checkNotNull(frameAggregator).queueEndOfStream(sequenceIndex);
                    } else {
                      checkNotNull(frameAggregator).queueFrame(asyncFrame, sequenceIndex);
                    }
                  });
      HardwareBufferSampleConsumer sampleConsumer =
          new HardwareBufferSampleConsumer(
              composition,
              sequenceIndex,
              playbackLooper,
              handlerWrapper,
              frameConsumer,
              errorConsumer,
              hardwareBufferJniWrapper);
      sampleConsumerBuilder.add(sampleConsumer);
      // TODO: b/496585841 - Handle single asset items with TRACK_TYPE_NONE.
      boolean sequenceContainsVideo =
          composition.sequences.get(sequenceIndex).trackTypes.contains(TRACK_TYPE_VIDEO);
      @AggregationStrategy int aggregationStrategy;
      if (!sequenceContainsVideo) {
        // Audio only sequences never produce video frames.
        aggregationStrategy = STRATEGY_EXPECT_NO_FRAMES;
      } else if (HardwareBufferFrameReader.CAPACITY >= 3) {
        // Retaining the previous frame in FrameAggregator requires reader capacity >= 3 since
        // InFlightFrameManager also holds a frame in flight downstream.
        aggregationStrategy = STRATEGY_MATCH_FRAME_CLOSEST_TO_TARGET;
      } else {
        aggregationStrategy = STRATEGY_MATCH_FRAME_AT_OR_AFTER_TARGET;
      }
      frameAggregator.registerSequence(sequenceIndex, aggregationStrategy);
    }
    sampleConsumers = sampleConsumerBuilder.build();
  }

  private void queueAggregatedFrames(ImmutableList<AsyncFrame> frames) {
    if (frames.get(0) == END_OF_STREAM_ASYNC_FRAME) {
      if (pendingPackets.isEmpty()) {
        frameProcessor.signalEndOfStream();
      } else {
        hasPendingEos = true;
      }
      return;
    }

    pendingPackets.add(frames);
    drainPendingPackets();
  }

  @Override
  public GraphInput getInput(EditedMediaItem editedMediaItem, Format format, int inputIndex) {
    return sampleConsumers.get(inputIndex);
  }

  @Override
  public void release() {
    if (released) {
      return;
    }
    released = true;
    releasePendingPackets();
    inFlightFrameManager.releaseAll();
    for (int i = 0; i < sampleConsumers.size(); i++) {
      sampleConsumers.get(i).release();
    }
    frameAggregator.close();
    try {
      frameProcessor.close();
      frameWriter.close();
    } catch (RuntimeException e) {
      errorConsumer.accept(ExportException.createForUnexpected(e));
    }
    if (encoder != null) {
      encoder.release();
    }
  }

  @Override
  @Nullable
  protected Format getMuxerInputFormat() throws ExportException {
    if (encoder != null) {
      @Nullable Format outputFormat = encoder.getOutputFormat();
      if (outputFormat != null && outputRotationDegrees != 0) {
        outputFormat = outputFormat.buildUpon().setRotationDegrees(outputRotationDegrees).build();
      }
      return outputFormat;
    }
    return null;
  }

  @Override
  @Nullable
  protected DecoderInputBuffer getMuxerInputBuffer() throws ExportException {
    if (encoder == null) {
      return null;
    }
    encoderOutputBuffer.data = encoder.getOutputBuffer();
    if (encoderOutputBuffer.data == null) {
      return null;
    }
    BufferInfo bufferInfo = checkNotNull(encoder.getOutputBufferInfo());
    if (bufferInfo.presentationTimeUs == 0) {
      // Internal ref b/235045165: Some encoder incorrectly set a zero presentation time on the
      // penultimate buffer (before EOS), and sets the actual timestamp on the EOS buffer. Use the
      // last processed frame presentation time instead.
      if (hasProducedFrameWithTimestampZero == hasMuxedTimestampZero
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
    if (encoder != null) {
      encoder.releaseOutputBuffer(/* render= */ false);
    }
  }

  @Override
  protected boolean isMuxerInputEnded() {
    if (encoder != null) {
      return encoder.isEnded();
    }
    return false;
  }

  private final class ComponentListener
      implements GlEncoderFrameWriter.Listener,
          EncoderFrameWriter.Listener,
          FrameProcessor.Listener,
          HandlerExecutor.Listener {

    @Override
    public void onEndOfStream() {
      checkState(!hasSignaledEndOfStream);
      if (encoder != null) {
        hasSignaledEndOfStream = true;
      }
      finalFramePresentationTimeUs = C.TIME_UNSET;
    }

    // FrameProcessor.Listener methods

    @Override
    public void onWakeup() {
      drainPendingPackets();
    }

    @Override
    public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper releaseFence) {
      inFlightFrameManager.onFrameProcessed(frame, releaseFence);
    }

    @Override
    public void onError(VideoFrameProcessingException e) {
      errorConsumer.accept(ExportException.createForVideoFrameProcessingException(e));
    }

    // HandlerExecutor.Listener methods

    @Override
    public void onError(RuntimeException e) {
      onError(VideoFrameProcessingException.from(e));
    }
  }

  private void drainPendingPackets() {
    while (!pendingPackets.isEmpty()) {
      ImmutableList<AsyncFrame> packet = pendingPackets.peek();
      if (packet == null) {
        break;
      }
      boolean queued = inFlightFrameManager.trackIfSuccessful(frameProcessor::queue, packet);
      if (queued) {
        pendingPackets.poll();
      } else {
        break;
      }
    }
    if (pendingPackets.isEmpty() && hasPendingEos) {
      frameProcessor.signalEndOfStream();
      hasPendingEos = false;
    }
  }

  private void releasePendingPackets() {
    @Nullable ImmutableList<AsyncFrame> packet;
    while ((packet = pendingPackets.poll()) != null) {
      releasePacket(packet);
    }
  }

  private static void releasePacket(@Nullable List<AsyncFrame> packet) {
    if (packet == null) {
      return;
    }
    for (int i = 0; i < packet.size(); i++) {
      TransformerUtil.releaseIfNeeded(packet.get(i).frame, /* releaseFence= */ null);
    }
  }

  private static String getRequestedOutputMimeType(
      Format inputFormat, TransformationRequest transformationRequest) {
    String inputSampleMimeType = checkNotNull(inputFormat.sampleMimeType);
    if (transformationRequest.videoMimeType != null) {
      return transformationRequest.videoMimeType;
    } else if (MimeTypes.isImage(inputSampleMimeType)) {
      return DEFAULT_OUTPUT_MIME_TYPE;
    } else {
      return inputSampleMimeType;
    }
  }

  /**
   * A {@link Codec.EncoderFactory} for the {@link FrameWriter} implementation that writes to an
   * encoder. Encoders are created by the wrapped factory.
   *
   * <p>The caller of the {@link FrameWriter} sets the size, {@link ColorInfo}, frame rate, pixel
   * format and rotation of the frames. For the output {@linkplain Format#sampleMimeType MIME type},
   * this factory uses the MIME type requested at construction when the muxer and a device encoder
   * support it, or falls back to a MIME type that is supported by the muxer, for example, {@link
   * MimeTypes#VIDEO_H264}. The MIME type choice doesn't depend on the size. For HDR, only encoders
   * that support the {@link ColorInfo} count. The encoder created by {@link
   * #createForVideoEncoding} is configured with a rotation of 0, and the frame rotation is saved on
   * the outer {@link PacketConsumerVideoSampleExporter} to be written as container metadata.
   */
  /* package */ final class FrameWriterEncoderFactory extends ForwardingEncoderFactory {

    private final String requestedSampleMimeType;
    private final ImmutableList<String> muxerSupportedSampleMimeTypes;

    /**
     * Creates an instance.
     *
     * @param encoderFactory The {@link Codec.EncoderFactory} to forward to.
     * @param requestedSampleMimeType The preferred output {@linkplain MimeTypes MIME type}.
     * @param muxerSupportedSampleMimeTypes The video {@linkplain MimeTypes MIME types} that the
     *     muxer supports.
     */
    public FrameWriterEncoderFactory(
        Codec.EncoderFactory encoderFactory,
        String requestedSampleMimeType,
        List<String> muxerSupportedSampleMimeTypes) {
      super(encoderFactory);
      this.requestedSampleMimeType = requestedSampleMimeType;
      this.muxerSupportedSampleMimeTypes = ImmutableList.copyOf(muxerSupportedSampleMimeTypes);
    }

    @Override
    public boolean isVideoFormatSupported(Format format) {
      Format encoderFormat = getEncoderFormat(format);
      return encoderFormat.sampleMimeType != null && super.isVideoFormatSupported(encoderFormat);
    }

    @Override
    public Codec createForVideoEncoding(Format format, @Nullable LogSessionId logSessionId)
        throws ExportException {
      checkState(PacketConsumerVideoSampleExporter.this.encoder == null);
      Codec encoder = super.createForVideoEncoding(getEncoderFormat(format), logSessionId);
      // TODO: b/523216171 - Check allowedEncodingRotationDegrees and prioritise landscape.
      // The encoder doesn't rotate frames, so the muxer writes the rotation as metadata.
      outputRotationDegrees = format.rotationDegrees;
      hasProducedFrameWithTimestampZero = true;
      PacketConsumerVideoSampleExporter.this.encoder = encoder;
      return encoder;
    }

    /**
     * Returns the {@link Format} to configure the encoder with for frames in the given {@link
     * Format}.
     *
     * <p>The returned format's {@linkplain Format#sampleMimeType MIME type} is null if no MIME type
     * is supported.
     */
    private Format getEncoderFormat(Format format) {
      // Create a new Format to control exactly which fields are passed into the encoder, which
      // avoids encoder failures if an app sets an unsupported field on the format.
      Format.Builder formatBuilder =
          new Format.Builder()
              .setWidth(format.width)
              .setHeight(format.height)
              .setFrameRate(format.frameRate)
              .setPixelFormat(format.pixelFormat)
              .setColorInfo(format.colorInfo)
              // The encoder doesn't rotate frames, so the encoder's rotation is set to 0. The
              // requested rotation will be applied as container metadata.
              .setRotationDegrees(0);
      @Nullable
      String sampleMimeType =
          findSupportedMimeTypeForEncoderAndMuxer(
              formatBuilder.setSampleMimeType(requestedSampleMimeType).build(),
              muxerSupportedSampleMimeTypes);
      return formatBuilder.setSampleMimeType(sampleMimeType).build();
    }
  }
}
