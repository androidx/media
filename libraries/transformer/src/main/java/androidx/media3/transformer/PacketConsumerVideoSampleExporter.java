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
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.Util;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.effect.DefaultGlObjectsProvider;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.effect.HardwareBufferJniWrapper;
import androidx.media3.transformer.Codec.EncoderFactory;
import com.google.common.collect.ImmutableList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
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

  private final MuxerWrapper muxerWrapper;
  private final TransformationRequest transformationRequest;
  private final Format firstInputFormat;

  private final Queue<ImmutableList<AsyncFrame>> pendingPackets;
  private final Set<Frame> inFlightFrames;
  private boolean hasPendingEos;
  private int outputRotationDegrees;
  private volatile boolean released;

  /**
   * The timestamp of the last buffer processed before {@linkplain
   * VideoFrameProcessor.Listener#onEnded() frame processing has ended}.
   */
  private volatile long finalFramePresentationTimeUs;

  private long lastMuxerInputBufferTimestampUs;
  private boolean hasMuxedTimestampZero;
  private boolean hasProducedFrameWithTimestampZero;
  private boolean hasSignaledEndOfStream;
  private @MonotonicNonNull Codec encoder;

  public PacketConsumerVideoSampleExporter(
      Context context,
      Composition composition,
      Format firstInputFormat,
      TransformationRequest transformationRequest,
      FrameProcessor.Factory frameProcessorFactory,
      @Nullable HardwareBufferJniWrapper hardwareBufferJniWrapper,
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
    this.transformationRequest = transformationRequest;
    this.errorConsumer = errorConsumer;
    this.muxerWrapper = muxerWrapper;
    this.firstInputFormat = firstInputFormat;
    this.pendingPackets = new ArrayDeque<>();
    this.inFlightFrames = new HashSet<>();
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

    if (SDK_INT >= 33) {
      frameWriter =
          new EncoderFrameWriter(
              strictEncoderFactory,
              componentListener,
              playbackHandler::post,
              playbackHandler,
              logSessionId);
    } else {
      checkState(hardwareBufferJniWrapper != null);
      frameWriter =
          new GlEncoderFrameWriter(
              context,
              strictEncoderFactory,
              componentListener,
              playbackHandler::post,
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
      Consumer<HardwareBufferFrame> frameConsumer =
          // TODO: b/478781219 - Remove the handlerWrapper.post once HardwareBufferSampleConsumer is
          // only accessed from a single thread.
          (frame) ->
              handlerWrapper.post(
                  () -> {
                    // Frames may be produced by the underlying players after Transformer has been
                    // canceled. Immediately release these frames.
                    if (released) {
                      if (frame != HardwareBufferFrame.END_OF_STREAM_FRAME) {
                        frame.release(/* releaseFence= */ null);
                      }
                      return;
                    }
                    if (frame == HardwareBufferFrame.END_OF_STREAM_FRAME) {
                      checkNotNull(frameAggregator).queueEndOfStream(sequenceIndex);
                    } else {
                      checkNotNull(frameAggregator).queueFrame(frame, sequenceIndex);
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
      // Ensure the FrameAggregator ignores audio only sequences.
      boolean shouldAggregateSequence =
          composition.sequences.get(sequenceIndex).trackTypes.contains(TRACK_TYPE_VIDEO);
      frameAggregator.registerSequence(sequenceIndex, shouldAggregateSequence);
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
    TransformerUtil.releaseIfNeeded(new ArrayList<>(inFlightFrames));
    inFlightFrames.clear();
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
          FrameProcessor.Listener {

    @Override
    public Format onConfigure(Format requestedFormat) {
      // Create a new Format to control exactly which fields are passed into the encoder, which
      // avoids encoder failures if an app sets an unsupported field on the format.
      Format.Builder formatBuilder =
          new Format.Builder()
              .setWidth(requestedFormat.width)
              .setHeight(requestedFormat.height)
              .setFrameRate(requestedFormat.frameRate)
              .setPixelFormat(requestedFormat.pixelFormat)
              .setColorInfo(requestedFormat.colorInfo);
      // TODO: b/523216171 - Check allowedEncodingRotationDegrees and prioritise landscape.
      // Rotation is handled by the muxer, update the encoder format so rotation is always 0.
      formatBuilder.setRotationDegrees(0);
      if (requestedFormat.rotationDegrees != 0) {
        outputRotationDegrees = requestedFormat.rotationDegrees;
      }
      // Use the MimeType set on Transformer to determine the supported output MimeType.
      String sampleMimeType =
          findSupportedMimeTypeForEncoderAndMuxer(
              formatBuilder
                  .setSampleMimeType(
                      getRequestedOutputMimeType(firstInputFormat, transformationRequest))
                  .build(),
              muxerWrapper.getSupportedSampleMimeTypes(TRACK_TYPE_VIDEO));
      return formatBuilder.setSampleMimeType(sampleMimeType).build();
    }

    @Override
    public void onEncoderCreated(Codec encoder) {
      checkState(PacketConsumerVideoSampleExporter.this.encoder == null);
      PacketConsumerVideoSampleExporter.this.encoder = encoder;
      hasProducedFrameWithTimestampZero = true;
    }

    @Override
    public void onEndOfStream() {
      checkState(!hasSignaledEndOfStream);
      if (encoder != null) {
        hasSignaledEndOfStream = true;
      }
      finalFramePresentationTimeUs = C.TIME_UNSET;
    }

    @Override
    public void onError(VideoFrameProcessingException e) {
      errorConsumer.accept(ExportException.createForVideoFrameProcessingException(e));
    }

    // FrameProcessor.Listener methods

    @Override
    public void onWakeup() {
      drainPendingPackets();
    }

    @Override
    public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper releaseFence) {
      boolean removed = inFlightFrames.remove(frame);
      if (removed) {
        TransformerUtil.releaseIfNeeded(frame, releaseFence);
      } else if (releaseFence != null) {
        releaseFence.close();
      }
    }
  }

  private void drainPendingPackets() {
    while (!pendingPackets.isEmpty()) {
      ImmutableList<AsyncFrame> packet = pendingPackets.peek();
      if (packet == null) {
        break;
      }
      boolean queued = frameProcessor.queue(packet);
      if (queued) {
        for (int i = 0; i < packet.size(); i++) {
          inFlightFrames.add(packet.get(i).frame);
        }
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

  private static final class HandlerExecutor implements Executor {
    private final HandlerWrapper handler;
    private final ComponentListener componentListener;

    private HandlerExecutor(HandlerWrapper handler, ComponentListener componentListener) {
      this.handler = handler;
      this.componentListener = componentListener;
    }

    @Override
    public void execute(Runnable command) {
      handler.post(
          () -> {
            try {
              command.run();
            } catch (RuntimeException e) {
              componentListener.onError(VideoFrameProcessingException.from(e));
            }
          });
    }
  }
}
