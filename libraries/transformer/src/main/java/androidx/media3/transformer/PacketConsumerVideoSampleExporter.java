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
import static androidx.media3.common.C.TRACK_TYPE_AUDIO;
import static androidx.media3.common.C.TRACK_TYPE_VIDEO;
import static androidx.media3.effect.HardwareBufferFrame.END_OF_STREAM_FRAME;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.media.MediaCodec.BufferInfo;
import android.media.metrics.LogSessionId;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.VideoFrameProcessor;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.effect.HardwareBufferFrameQueue;
import androidx.media3.effect.PacketConsumer.Packet;
import androidx.media3.effect.PacketConsumer.Packet.EndOfStream;
import androidx.media3.effect.PacketConsumerCaller;
import androidx.media3.effect.PacketConsumerHardwareBufferFrameQueue;
import androidx.media3.effect.PacketConsumerUtil;
import androidx.media3.effect.RenderingPacketConsumer;
import androidx.media3.transformer.Codec.EncoderFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.checkerframework.checker.initialization.qual.Initialized;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Processes, encodes and muxes raw video frames. */
@RequiresApi(26)
/* package */ final class PacketConsumerVideoSampleExporter extends SampleExporter {

  private static final long RELEASE_TIMEOUT_MS = 100;

  private final DecoderInputBuffer encoderOutputBuffer;

  private final Consumer<ExportException> errorConsumer;
  private final RenderingPacketConsumer<
          ImmutableList<HardwareBufferFrame>, HardwareBufferFrameQueue>
      packetProcessor;

  private final PacketConsumerCaller<ImmutableList<HardwareBufferFrame>> packetConsumerCaller;
  private final FrameAggregator frameAggregator;
  private final HardwareBufferFrameQueue hardwareBufferFrameQueue;
  private final ImmutableList<HardwareBufferSampleConsumer> sampleConsumers;
  private final ComponentListener componentListener;

  private final Codec.EncoderFactory encoderFactory;
  private final ImmutableList<Integer> allowedEncodingRotationDegrees;
  private final MuxerWrapper muxerWrapper;
  private final FallbackListener fallbackListener;
  private final TransformationRequest transformationRequest;
  @Nullable private final LogSessionId logSessionId;

  /**
   * The timestamp of the last buffer processed before {@linkplain
   * VideoFrameProcessor.Listener#onEnded() frame processing has ended}.
   */
  private volatile long finalFramePresentationTimeUs;

  private long lastMuxerInputBufferTimestampUs;
  private boolean hasMuxedTimestampZero;
  private boolean hasProducedFrameWithTimestampZero;
  private boolean hasSignaledEndOfStream;
  private @MonotonicNonNull VideoEncoderWrapper encoderWrapper;

  public PacketConsumerVideoSampleExporter(
      Composition composition,
      Format firstInputFormat,
      TransformationRequest transformationRequest,
      RenderingPacketConsumer<ImmutableList<HardwareBufferFrame>, HardwareBufferFrameQueue>
          packetProcessor,
      @Nullable RenderingPacketConsumer<HardwareBufferFrame, SurfaceInfo> packetRenderer,
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
    this.encoderFactory = encoderFactory;
    this.allowedEncodingRotationDegrees = allowedEncodingRotationDegrees;
    this.muxerWrapper = muxerWrapper;
    this.fallbackListener = fallbackListener;
    this.logSessionId = logSessionId;
    this.packetProcessor = packetProcessor;
    packetProcessor.setErrorConsumer(
        (e) ->
            errorConsumer.accept(
                ExportException.createForVideoFrameProcessingException(
                    VideoFrameProcessingException.from(e))));
    finalFramePresentationTimeUs = C.TIME_UNSET;
    lastMuxerInputBufferTimestampUs = C.TIME_UNSET;
    encoderOutputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED);

    @SuppressWarnings("nullness:assignment")
    @Initialized
    PacketConsumerVideoSampleExporter thisRef = this;

    componentListener = new ComponentListener();

    // TODO: b/484926720 - add executor to the Listener callbacks.
    if (packetRenderer != null) {
      hardwareBufferFrameQueue =
          new PacketConsumerHardwareBufferFrameQueue(
              /* releaseFrameExecutor= */ handlerWrapper::post, packetRenderer, componentListener);
    } else if (SDK_INT >= 33) {
      hardwareBufferFrameQueue = new EncoderWriterHardwareBufferQueue(componentListener);
    } else {
      throw new UnsupportedOperationException();
    }

    // Create a Java wrapper to feed frames into the effects pipeline.
    packetConsumerCaller =
        PacketConsumerCaller.create(
            packetProcessor,
            newDirectExecutorService(),
            (e) ->
                errorConsumer.accept(
                    ExportException.createForVideoFrameProcessingException(
                        VideoFrameProcessingException.from(e))));
    packetConsumerCaller.run();
    packetProcessor.setRenderOutput(hardwareBufferFrameQueue);

    // Create the FrameAggregator, ignoring audio only sequences.
    int numVideoSequences = getNumVideoSequences(composition);
    frameAggregator = new FrameAggregator(numVideoSequences, thisRef::queueAggregatedFrames);

    // Create the per sequence consumers that feed buffers from the decoders into the
    // FrameAggregator.
    ImmutableList.Builder<HardwareBufferSampleConsumer> sampleConsumerBuilder =
        new ImmutableList.Builder<>();
    for (int i = 0; i < numVideoSequences; i++) {
      int sequenceIndex = i;
      Consumer<HardwareBufferFrame> frameConsumer =
          // TODO: b/478781219 - Remove the handlerWrapper.post once HardwareBufferSampleConsumer is
          // only accessed from a single thread.
          (frame) ->
              handlerWrapper.post(
                  () -> {
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
              errorConsumer);
      sampleConsumerBuilder.add(sampleConsumer);
    }
    sampleConsumers = sampleConsumerBuilder.build();
  }

  @SuppressWarnings("unchecked")
  private void queueAggregatedFrames(ImmutableList<HardwareBufferFrame> frames) {
    // We don't need to apply backpressure here - it's applied implicitly via the texture listener
    // capacity.
    ListenableFuture<Void> queuePacketFuture;
    if (frames.get(0) == END_OF_STREAM_FRAME) {
      queuePacketFuture = packetConsumerCaller.queuePacket(EndOfStream.INSTANCE);
    } else {
      queuePacketFuture = packetConsumerCaller.queuePacket(Packet.of(frames));
    }
    Futures.addCallback(
        queuePacketFuture,
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void result) {}

          @Override
          public void onFailure(Throwable t) {
            errorConsumer.accept(ExportException.createForUnexpected(t));
          }
        },
        directExecutor());
  }

  @Override
  public GraphInput getInput(EditedMediaItem editedMediaItem, Format format, int inputIndex) {
    return sampleConsumers.get(inputIndex);
  }

  @Override
  public void release() {
    for (int i = 0; i < sampleConsumers.size(); i++) {
      sampleConsumers.get(i).release();
    }
    // Wait until the frame renderer and packet processors are released.
    ListenableFuture<Void> releasePacketProcessorFuture =
        PacketConsumerUtil.release(packetProcessor, newDirectExecutorService());

    hardwareBufferFrameQueue.release();
    if (encoderWrapper != null) {
      encoderWrapper.release();
    }

    @Nullable Exception exception = null;
    try {
      releasePacketProcessorFuture.get(RELEASE_TIMEOUT_MS, MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      exception = e;
    } catch (Exception e) {
      exception = e;
    } finally {
      if (exception != null) {
        errorConsumer.accept(ExportException.createForUnexpected(exception));
      }
    }
  }

  @Override
  @Nullable
  protected Format getMuxerInputFormat() throws ExportException {
    return encoderWrapper == null ? null : encoderWrapper.getOutputFormat();
  }

  @Override
  @Nullable
  protected DecoderInputBuffer getMuxerInputBuffer() throws ExportException {
    if (encoderWrapper == null) {
      return null;
    }
    encoderOutputBuffer.data = encoderWrapper.getOutputBuffer();
    if (encoderOutputBuffer.data == null) {
      return null;
    }
    BufferInfo bufferInfo = checkNotNull(encoderWrapper.getOutputBufferInfo());
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
    if (encoderWrapper != null) {
      encoderWrapper.releaseOutputBuffer(/* render= */ false);
    }
  }

  @Override
  protected boolean isMuxerInputEnded() {
    return encoderWrapper != null && encoderWrapper.isEnded();
  }

  private static int getNumVideoSequences(Composition composition) {
    int numVideoSequences = 0;
    for (int i = 0; i < composition.sequences.size(); i++) {
      if (composition.sequences.get(i).trackTypes.size() == 1
          && composition.sequences.get(i).trackTypes.contains(TRACK_TYPE_AUDIO)) {
        continue;
      }
      numVideoSequences++;
    }
    return numVideoSequences;
  }

  private final class ComponentListener implements PacketConsumerHardwareBufferFrameQueue.Listener {

    @Override
    public SurfaceInfo getRendererSurfaceInfo(Format format) throws VideoFrameProcessingException {
      try {
        checkState(encoderWrapper == null);
        VideoEncoderWrapper encoderWrapper =
            new VideoEncoderWrapper(
                encoderFactory,
                format,
                allowedEncodingRotationDegrees,
                muxerWrapper.getSupportedSampleMimeTypes(TRACK_TYPE_VIDEO),
                transformationRequest,
                fallbackListener,
                logSessionId);

        PacketConsumerVideoSampleExporter.this.encoderWrapper = encoderWrapper;
        hasProducedFrameWithTimestampZero = true;
        // TODO: b/475744934 - Notify the effects pipeline if the encoder fell back to a format that
        // differs from the input format.
        return checkNotNull(encoderWrapper.getSurfaceInfo(format.width, format.height));
      } catch (ExportException e) {
        throw VideoFrameProcessingException.from(e);
      }
    }

    @Override
    public void onEndOfStream() {
      checkState(!hasSignaledEndOfStream);
      if (encoderWrapper != null) {
        try {
          hasSignaledEndOfStream = true;
          // TODO: b/475744934 - Update this to only end the encoder once the renderer has received
          // the EOS.
          encoderWrapper.signalEndOfInputStream();
        } catch (ExportException e) {
          errorConsumer.accept(e);
        }
      }
      finalFramePresentationTimeUs = C.TIME_UNSET;
    }

    @Override
    public void onError(VideoFrameProcessingException e) {
      errorConsumer.accept(ExportException.createForVideoFrameProcessingException(e));
    }
  }
}
