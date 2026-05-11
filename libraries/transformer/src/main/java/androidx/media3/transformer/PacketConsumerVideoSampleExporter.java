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
import static androidx.media3.effect.HardwareBufferFrame.END_OF_STREAM_FRAME;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import android.hardware.HardwareBuffer;
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
import androidx.media3.common.util.Util;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.effect.BitmapToHardwareBufferProcessor;
import androidx.media3.effect.GlTextureFrameRenderer;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.effect.HardwareBufferFrameQueue;
import androidx.media3.effect.HardwareBufferJniWrapper;
import androidx.media3.effect.HardwareBufferSurfaceRenderer;
import androidx.media3.effect.PacketConsumerHardwareBufferFrameQueue;
import androidx.media3.transformer.Codec.EncoderFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayDeque;
import java.util.Queue;
import org.checkerframework.checker.initialization.qual.Initialized;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Processes, encodes and muxes raw video frames. */
@RequiresApi(26)
/* package */ final class PacketConsumerVideoSampleExporter extends SampleExporter {

  private final DecoderInputBuffer encoderOutputBuffer;

  private final Consumer<ExportException> errorConsumer;
  private final FrameProcessor frameProcessor;
  private final FrameAggregator frameAggregator;
  private final FrameWriter frameWriter;
  private final ImmutableList<HardwareBufferSampleConsumer> sampleConsumers;

  private final Codec.EncoderFactory encoderFactory;
  private final ImmutableList<Integer> allowedEncodingRotationDegrees;
  private final MuxerWrapper muxerWrapper;
  private final FallbackListener fallbackListener;
  private final TransformationRequest transformationRequest;
  @Nullable private final LogSessionId logSessionId;
  @Nullable private final BitmapToHardwareBufferProcessor hardwareBufferPostProcessor;

  private final HandlerWrapper handlerWrapper;
  private final Queue<PendingQueueCall> pendingQueueCalls;
  private boolean hasPendingEos;

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
    this.encoderFactory = encoderFactory;
    this.allowedEncodingRotationDegrees = allowedEncodingRotationDegrees;
    this.muxerWrapper = muxerWrapper;
    this.fallbackListener = fallbackListener;
    this.logSessionId = logSessionId;
    this.handlerWrapper = handlerWrapper;
    this.pendingQueueCalls = new ArrayDeque<>();
    finalFramePresentationTimeUs = C.TIME_UNSET;
    lastMuxerInputBufferTimestampUs = C.TIME_UNSET;
    encoderOutputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED);

    @SuppressWarnings("nullness:assignment")
    @Initialized
    PacketConsumerVideoSampleExporter thisRef = this;

    ComponentListener componentListener = new ComponentListener();

    // TODO: b/484926720 - add executor to the Listener callbacks.
    HardwareBufferFrameQueue queue;
    if (SDK_INT >= 33) {
      queue = new EncoderWriterHardwareBufferQueue(componentListener);
    } else if (hardwareBufferJniWrapper != null) {
      // Convert CPU Bitmaps to HardwareBuffers when the native helpers are available.
      HardwareBufferSurfaceRenderer packetRenderer =
          HardwareBufferSurfaceRenderer.create(
              context,
              hardwareBufferJniWrapper,
              GlTextureFrameRenderer.Listener.NO_OP.INSTANCE,
              (e) ->
                  errorConsumer.accept(
                      ExportException.createForVideoFrameProcessingException(
                          VideoFrameProcessingException.from(e))));
      queue = new PacketConsumerHardwareBufferFrameQueue(packetRenderer, componentListener);
    } else {
      throw new UnsupportedOperationException();
    }
    frameWriter = new HardwareBufferFrameQueueToFrameWriterAdapter(queue);
    frameProcessor = frameProcessorFactory.create(frameWriter);
    if (hardwareBufferJniWrapper != null) {
      hardwareBufferPostProcessor =
          new BitmapToHardwareBufferProcessor(
              hardwareBufferJniWrapper,
              /* internalExecutor= */ Util.newSingleThreadExecutor(
                  "BitmapToHardwareBufferProcessor::Thread"),
              /* errorExecutor= */ directExecutor(),
              /* errorCallback= */ (e) ->
                  errorConsumer.accept(
                      ExportException.createForVideoFrameProcessingException(
                          VideoFrameProcessingException.from(e))));
    } else {
      hardwareBufferPostProcessor = null;
    }

    frameAggregator =
        new FrameAggregator(
            composition.sequences.size(),
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
                    if (frame == HardwareBufferFrame.END_OF_STREAM_FRAME) {
                      checkNotNull(frameAggregator).queueEndOfStream(sequenceIndex);
                    } else if (hardwareBufferPostProcessor != null) {
                      HardwareBufferFrame processedFrame =
                          hardwareBufferPostProcessor.process(frame);
                      checkNotNull(frameAggregator).queueFrame(processedFrame, sequenceIndex);
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
      // TODO: b/496585841 - Handle single asset items with TRACK_TYPE_NONE.
      // Ensure the FrameAggregator ignores audio only sequences.
      boolean shouldAggregateSequence =
          composition.sequences.get(sequenceIndex).trackTypes.contains(TRACK_TYPE_VIDEO);
      frameAggregator.registerSequence(sequenceIndex, shouldAggregateSequence);
    }
    sampleConsumers = sampleConsumerBuilder.build();
  }

  private void queueAggregatedFrames(ImmutableList<HardwareBufferFrame> frames) {
    if (frames.get(0) == END_OF_STREAM_FRAME) {
      if (pendingQueueCalls.isEmpty()) {
        frameProcessor.signalEndOfStream();
      } else {
        hasPendingEos = true;
      }
      return;
    }

    ImmutableList.Builder<AsyncFrame> asyncFrameListBuilder = ImmutableList.builder();
    for (int i = 0; i < frames.size(); i++) {
      HardwareBufferFrame effectFrame = frames.get(i);
      // When encoding, releaseTime and contentTime are the same.
      checkState(effectFrame.releaseTimeNs == effectFrame.sequencePresentationTimeUs * 1000);

      ImmutableMap.Builder<String, Object> metadataBuilder = ImmutableMap.builder();
      metadataBuilder
          .put(Frame.KEY_PRESENTATION_TIME_US, effectFrame.presentationTimeUs)
          .put(Frame.KEY_DISPLAY_TIME_NS, effectFrame.releaseTimeNs);
      if (effectFrame.getMetadata() instanceof CompositionFrameMetadata) {
        metadataBuilder.put(
            CompositionFrameMetadata.KEY_COMPOSITION_FRAME_METADATA, effectFrame.getMetadata());
      }

      HardwareBuffer hardwareBuffer = checkNotNull(effectFrame.hardwareBuffer);
      DefaultHardwareBufferFrame commonFrame =
          new DefaultHardwareBufferFrame.Builder(hardwareBuffer)
              .setFormat(effectFrame.format)
              .setContentTimeUs(effectFrame.sequencePresentationTimeUs)
              .setMetadata(metadataBuilder.buildOrThrow())
              .setInternalImage(effectFrame.internalFrame)
              .build();

      asyncFrameListBuilder.add(new AsyncFrame(commonFrame, effectFrame.acquireFence));
    }

    PendingQueueCall call = new PendingQueueCall(asyncFrameListBuilder.build(), frames);
    pendingQueueCalls.add(call);
    drainPendingQueueCalls();
  }

  @Override
  public GraphInput getInput(EditedMediaItem editedMediaItem, Format format, int inputIndex) {
    return sampleConsumers.get(inputIndex);
  }

  @Override
  public void release() {
    releasePendingQueueCalls();
    for (int i = 0; i < sampleConsumers.size(); i++) {
      sampleConsumers.get(i).release();
    }
    if (hardwareBufferPostProcessor != null) {
      hardwareBufferPostProcessor.close();
    }
    try {
      frameProcessor.close();
      frameWriter.close();
    } catch (RuntimeException e) {
      errorConsumer.accept(ExportException.createForUnexpected(e));
    }
    if (encoderWrapper != null) {
      encoderWrapper.release();
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
        return checkNotNull(
            encoderWrapper.getFixedRotationSurfaceInfo(
                format.width, format.height, format.rotationDegrees));
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

  private void drainPendingQueueCalls() {
    while (!pendingQueueCalls.isEmpty()) {
      PendingQueueCall call = pendingQueueCalls.peek();
      if (call == null) {
        break;
      }
      try {
        boolean queued =
            frameProcessor.queue(
                call.asyncFrames,
                directExecutor(),
                () -> handlerWrapper.post(this::drainPendingQueueCalls),
                (frame, fence) -> {
                  checkArgument(frame instanceof DefaultHardwareBufferFrame);
                  HardwareBuffer hardwareBuffer =
                      ((DefaultHardwareBufferFrame) frame).getHardwareBuffer();
                  for (HardwareBufferFrame effectFrameToRelease : call.hardwareBufferFrames) {
                    if (effectFrameToRelease.hardwareBuffer == hardwareBuffer) {
                      effectFrameToRelease.release(fence);
                      break;
                    }
                  }
                });
        if (queued) {
          pendingQueueCalls.poll();
        } else {
          break;
        }
      } catch (VideoFrameProcessingException e) {
        errorConsumer.accept(ExportException.createForVideoFrameProcessingException(e));
        break;
      }
    }
    if (pendingQueueCalls.isEmpty() && hasPendingEos) {
      frameProcessor.signalEndOfStream();
      hasPendingEos = false;
    }
  }

  private void releasePendingQueueCalls() {
    PendingQueueCall call;
    while ((call = pendingQueueCalls.poll()) != null) {
      for (HardwareBufferFrame frame : call.hardwareBufferFrames) {
        frame.release(/* releaseFence= */ null);
      }
    }
  }

  private static final class PendingQueueCall {
    final ImmutableList<AsyncFrame> asyncFrames;
    final ImmutableList<HardwareBufferFrame> hardwareBufferFrames;

    PendingQueueCall(
        ImmutableList<AsyncFrame> asyncFrames,
        ImmutableList<HardwareBufferFrame> hardwareBufferFrames) {
      this.asyncFrames = asyncFrames;
      this.hardwareBufferFrames = hardwareBufferFrames;
    }
  }
}
