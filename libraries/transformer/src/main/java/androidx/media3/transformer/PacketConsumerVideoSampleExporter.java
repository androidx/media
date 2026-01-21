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

import static androidx.media3.common.ColorInfo.SDR_BT709_LIMITED;
import static androidx.media3.effect.HardwareBufferFrame.END_OF_STREAM_FRAME;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.media.MediaCodec.BufferInfo;
import android.media.metrics.LogSessionId;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.effect.GlTextureFrameRenderer;
import androidx.media3.effect.GlTextureFrameRenderer.Listener;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.effect.PacketConsumer.Packet;
import androidx.media3.effect.PacketConsumer.Packet.EndOfStream;
import androidx.media3.effect.PacketConsumerCaller;
import androidx.media3.effect.PacketConsumerUtil;
import androidx.media3.effect.PacketProcessor;
import androidx.media3.effect.PlaceholderHardwareBufferToGlTextureConverter;
import androidx.media3.transformer.Codec.EncoderFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.checkerframework.checker.initialization.qual.Initialized;

/** Processes, encodes and muxes raw video frames. */
/* package */ final class PacketConsumerVideoSampleExporter extends SampleExporter {

  private static final long RELEASE_TIMEOUT_MS = 100;

  private final VideoEncoderWrapper encoderWrapper;
  private final DecoderInputBuffer encoderOutputBuffer;
  private final GlObjectsProvider glObjectsProvider;
  private final ExecutorService glExecutorService;
  private final Consumer<ExportException> errorConsumer;
  private final GlTextureFrameRenderer glTextureFrameRenderer;
  private final PacketProcessor<List<? extends HardwareBufferFrame>, HardwareBufferFrame>
      packetProcessor;
  private final PacketConsumerCaller<List<? extends HardwareBufferFrame>> packetConsumerCaller;
  private final FrameAggregator frameAggregator;
  private final ImmutableList<HardwareBufferSampleConsumer> sampleConsumers;

  /**
   * The timestamp of the last buffer processed before {@linkplain FrameRendererListener#onEnded()
   * frame processing has ended}.
   */
  private volatile long finalFramePresentationTimeUs;

  private long lastMuxerInputBufferTimestampUs;
  private boolean hasMuxedTimestampZero;
  private boolean hasProducedFrameWithTimestampZero;

  public PacketConsumerVideoSampleExporter(
      Context context,
      Composition composition,
      Format firstInputFormat,
      TransformationRequest transformationRequest,
      PacketProcessor<List<? extends HardwareBufferFrame>, HardwareBufferFrame> packetProcessor,
      GlObjectsProvider glObjectsProvider,
      ExecutorService glExecutorService,
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
    this.glObjectsProvider = glObjectsProvider;
    this.glExecutorService = glExecutorService;
    this.errorConsumer = errorConsumer;

    finalFramePresentationTimeUs = C.TIME_UNSET;
    lastMuxerInputBufferTimestampUs = C.TIME_UNSET;

    // TODO: b/475744934 - Build the encoderWrapper using the Format from the first frame fed from
    //  the PacketProcessor.
    Format encoderInputFormat =
        firstInputFormat.buildUpon().setColorInfo(SDR_BT709_LIMITED).build();
    encoderWrapper =
        new VideoEncoderWrapper(
            encoderFactory,
            encoderInputFormat,
            allowedEncodingRotationDegrees,
            muxerWrapper.getSupportedSampleMimeTypes(C.TRACK_TYPE_VIDEO),
            transformationRequest,
            fallbackListener,
            logSessionId);
    encoderOutputBuffer =
        new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED);

    glTextureFrameRenderer =
        GlTextureFrameRenderer.create(
            context,
            listeningDecorator(glExecutorService),
            glObjectsProvider,
            (e) -> errorConsumer.accept(ExportException.createForVideoFrameProcessingException(e)),
            new FrameRendererListener(errorConsumer));
    this.packetProcessor = packetProcessor;

    // TODO: b/475744934 - This outputs a blank texture for every input frame, replace it with an
    // injectable renderer.
    PlaceholderHardwareBufferToGlTextureConverter converter =
        new PlaceholderHardwareBufferToGlTextureConverter(glExecutorService, glObjectsProvider);
    converter.setOutput(glTextureFrameRenderer);
    this.packetProcessor.setOutput(converter);
    packetConsumerCaller =
        PacketConsumerCaller.create(
            packetProcessor,
            glExecutorService,
            (e) ->
                errorConsumer.accept(
                    ExportException.createForVideoFrameProcessingException(
                        VideoFrameProcessingException.from(e))));
    packetConsumerCaller.run();

    @SuppressWarnings("nullness:assignment")
    @Initialized
    PacketConsumerVideoSampleExporter thisRef = this;

    frameAggregator =
        new FrameAggregator(composition.sequences.size(), thisRef::queueAggregatedFrames);

    ImmutableList.Builder<HardwareBufferSampleConsumer> sampleConsumerBuilder =
        new ImmutableList.Builder<>();
    for (int i = 0; i < composition.sequences.size(); i++) {
      int sequenceIndex = i;
      Consumer<HardwareBufferFrame> frameConsumer =
          (frame) -> {
            if (frame == HardwareBufferFrame.END_OF_STREAM_FRAME) {
              checkNotNull(frameAggregator).queueEndOfStream(sequenceIndex);
            } else {
              checkNotNull(frameAggregator).queueFrame(frame, sequenceIndex);
            }
          };
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
  private void queueAggregatedFrames(List<HardwareBufferFrame> frames) {
    // We don't need to apply backpressure here - it's applied implicitly via the texture listener
    // capacity.
    ListenableFuture<Void> queuePacketFuture;

    if (frames.contains(END_OF_STREAM_FRAME)) {
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
    encoderWrapper.release();
    for (int i = 0; i < sampleConsumers.size(); i++) {
      sampleConsumers.get(i).release();
    }
    // Wait until the frame renderer and packet processors are released.
    ListenableFuture<Void> releaseFrameRendererFuture =
        PacketConsumerUtil.release(glTextureFrameRenderer, glExecutorService);
    ListenableFuture<Void> releasePacketProcessorFuture =
        PacketConsumerUtil.release(packetProcessor, glExecutorService);

    ListenableFuture<List<Void>> releaseFutures =
        Futures.allAsList(
            ImmutableList.of(releasePacketProcessorFuture, releaseFrameRendererFuture));

    @Nullable Exception exception = null;
    try {
      releaseFutures.get(RELEASE_TIMEOUT_MS, MILLISECONDS);
      glObjectsProvider.release(GlUtil.getDefaultEglDisplay());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      exception = e;
    } catch (Exception e) {
      exception = e;
    } finally {
      glExecutorService.shutdown();
      if (exception != null) {
        errorConsumer.accept(ExportException.createForUnexpected(exception));
      }
    }
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
    encoderWrapper.releaseOutputBuffer(/* render= */ false);
  }

  @Override
  protected boolean isMuxerInputEnded() {
    return encoderWrapper.isEnded();
  }

  private class FrameRendererListener implements Listener {

    private final Consumer<ExportException> errorConsumer;

    private FrameRendererListener(Consumer<ExportException> errorConsumer) {
      this.errorConsumer = errorConsumer;
    }

    @Override
    public void onOutputSizeChanged(int width, int height) {
      @Nullable SurfaceInfo surfaceInfo = null;
      try {
        surfaceInfo = encoderWrapper.getSurfaceInfo(width, height);
      } catch (ExportException e) {
        errorConsumer.accept(e);
      }
      glTextureFrameRenderer.setOutputSurfaceInfo(surfaceInfo);
    }

    @Override
    public void onOutputFrameAvailableForRendering(long presentationTimeUs) {
      if (presentationTimeUs == 0) {
        hasProducedFrameWithTimestampZero = true;
      }
    }

    @Override
    public void onEnded() {
      try {
        encoderWrapper.signalEndOfInputStream();
      } catch (ExportException e) {
        errorConsumer.accept(e);
      }
      finalFramePresentationTimeUs = C.TIME_UNSET;
    }
  }
}
